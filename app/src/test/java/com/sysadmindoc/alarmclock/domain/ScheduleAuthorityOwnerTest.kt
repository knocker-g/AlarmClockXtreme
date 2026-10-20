package com.sysadmindoc.alarmclock.domain

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.sysadmindoc.alarmclock.data.local.AlarmDatabase
import com.sysadmindoc.alarmclock.data.local.ScheduleAuthorityDao
import com.sysadmindoc.alarmclock.data.local.entity.ScheduleAuthority
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class ScheduleAuthorityOwnerTest {

    private lateinit var context: Context
    private lateinit var database: AlarmDatabase
    private lateinit var authorityDao: ScheduleAuthorityDao
    private lateinit var owner: ScheduleAuthorityOwner

    private val initialAuthority = ScheduleAuthority(
        alarmId = 42L,
        instanceToken = "token-instance-42",
        definitionRevision = 1L,
        scheduleRevision = 1L,
        lifecycle = "LIVE",
        desiredOperation = "SET",
        desiredTriggerMillis = 1700000000000L,
        reconcileState = "SETTLED",
        restoreEpoch = 100L,
        actionKey = "action-init-1"
    )

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        database = Room.inMemoryDatabaseBuilder(context, AlarmDatabase::class.java)
            .allowMainThreadQueries()
            .addCallback(AlarmDatabase.DATABASE_CALLBACK)
            .build()
        authorityDao = database.scheduleAuthorityDao()
        owner = ScheduleAuthorityOwner(
            authorityDao = authorityDao,
            exactPermissionChecker = { true }
        )
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun `duplicate same-action request returns DuplicateIgnored without advancing revision`() = runBlocking {
        authorityDao.insertAuthority(initialAuthority)

        val result = owner.executeOperation(
            alarmId = 42L,
            desiredOperation = "SNOOZE",
            desiredTriggerMillis = 1700000600000L,
            actionKey = "action-init-1" // Duplicate matching current actionKey!
        ) {
            true
        }

        assertEquals(OwnerResult.DuplicateIgnored, result)

        val current = authorityDao.getAuthorityByAlarmId(42L)
        assertEquals("Revision must NOT be advanced on duplicate actionKey", 1L, current?.scheduleRevision)
    }

    @Test
    fun `OS SET(A) then CANCEL(B) results in final CANCEL SETTLED state`() = runBlocking {
        authorityDao.insertAuthority(initialAuthority)

        val osHistory = mutableListOf<String>()

        val resA = owner.executeOperation(
            alarmId = 42L,
            desiredOperation = "SET",
            desiredTriggerMillis = 2000L,
            actionKey = "action-set-a"
        ) {
            osHistory.add("SET_2000")
            true
        }

        assertTrue("First operation SET(A) must succeed", resA is OwnerResult.OsAccepted)

        val resB = owner.executeOperation(
            alarmId = 42L,
            desiredOperation = "CANCEL",
            desiredTriggerMillis = null,
            actionKey = "action-cancel-b"
        ) {
            osHistory.add("CANCEL")
            true
        }

        assertTrue("Second operation CANCEL(B) must succeed", resB is OwnerResult.OsAccepted)

        val finalAuth = authorityDao.getAuthorityByAlarmId(42L)
        assertNotNull(finalAuth)
        assertEquals(3L, finalAuth?.scheduleRevision)
        assertEquals("CANCEL", finalAuth?.desiredOperation)
        assertEquals("SETTLED", finalAuth?.reconcileState)
        assertEquals(listOf("SET_2000", "CANCEL"), osHistory)
    }

    @Test
    fun `OS CANCEL(A) then SET(B) results in final SET SETTLED state`() = runBlocking {
        authorityDao.insertAuthority(initialAuthority)

        val osHistory = mutableListOf<String>()

        owner.executeOperation(
            alarmId = 42L,
            desiredOperation = "CANCEL",
            desiredTriggerMillis = null,
            actionKey = "act-cancel-a"
        ) {
            osHistory.add("CANCEL")
            true
        }

        owner.executeOperation(
            alarmId = 42L,
            desiredOperation = "SET",
            desiredTriggerMillis = 5000L,
            actionKey = "act-set-b"
        ) {
            osHistory.add("SET_5000")
            true
        }

        val finalAuth = authorityDao.getAuthorityByAlarmId(42L)
        assertNotNull(finalAuth)
        assertEquals(3L, finalAuth?.scheduleRevision)
        assertEquals("SET", finalAuth?.desiredOperation)
        assertEquals(5000L, finalAuth?.desiredTriggerMillis)
        assertEquals("SETTLED", finalAuth?.reconcileState)
        assertEquals(listOf("CANCEL", "SET_5000"), osHistory)
    }

    @Test
    fun `permission blocked returns PermissionBlocked result`() = runBlocking {
        authorityDao.insertAuthority(initialAuthority)

        val blockedOwner = ScheduleAuthorityOwner(
            authorityDao = authorityDao,
            exactPermissionChecker = { false } // Permission revoked!
        )

        val result = blockedOwner.executeOperation(
            alarmId = 42L,
            desiredOperation = "SET",
            desiredTriggerMillis = 9000L,
            actionKey = "act-blocked"
        ) {
            true
        }

        assertTrue("Operation must be blocked when exact permission is false", result is OwnerResult.PermissionBlocked)
        val current = authorityDao.getAuthorityByAlarmId(42L)
        assertEquals("RECONCILING", current?.reconcileState)
    }

    @Test
    fun `OS action returning false returns OutcomeUnknown result`() = runBlocking {
        authorityDao.insertAuthority(initialAuthority)

        val result = owner.executeOperation(
            alarmId = 42L,
            desiredOperation = "SET",
            desiredTriggerMillis = 9000L,
            actionKey = "act-fail"
        ) {
            false // OS action returned false!
        }

        assertTrue("OS action failure must return OutcomeUnknown", result is OwnerResult.OutcomeUnknown)
    }
}
