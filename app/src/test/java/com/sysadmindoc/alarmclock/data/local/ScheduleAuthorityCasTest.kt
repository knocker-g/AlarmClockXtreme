package com.sysadmindoc.alarmclock.data.local

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.sysadmindoc.alarmclock.data.local.entity.ScheduleAuthority
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class ScheduleAuthorityCasTest {

    private lateinit var context: Context
    private lateinit var database: AlarmDatabase
    private lateinit var authorityDao: ScheduleAuthorityDao

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
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun `claim with matching fingerprint succeeds and advances revision to RECONCILING`() = runBlocking {
        authorityDao.insertAuthority(initialAuthority)

        val rowsUpdated = authorityDao.casClaimAuthority(
            alarmId = 42L,
            instanceToken = "token-instance-42",
            restoreEpoch = 100L,
            expectedRevision = 1L,
            newRevision = 2L,
            desiredOperation = "SNOOZE",
            desiredTriggerMillis = 1700000600000L,
            actionKey = "action-snooze-2"
        )

        assertEquals(1, rowsUpdated)

        val updated = authorityDao.getAuthorityByAlarmId(42L)
        assertNotNull(updated)
        assertEquals(2L, updated?.scheduleRevision)
        assertEquals("RECONCILING", updated?.reconcileState)
        assertEquals("SNOOZE", updated?.desiredOperation)
        assertEquals(1700000600000L, updated?.desiredTriggerMillis)
        assertEquals("action-snooze-2", updated?.actionKey)
    }

    @Test
    fun `stale revision claim is rejected and returns 0`() = runBlocking {
        authorityDao.insertAuthority(initialAuthority)

        val rowsUpdated = authorityDao.casClaimAuthority(
            alarmId = 42L,
            instanceToken = "token-instance-42",
            restoreEpoch = 100L,
            expectedRevision = 99L, // Stale revision!
            newRevision = 100L,
            desiredOperation = "CANCEL",
            desiredTriggerMillis = null,
            actionKey = "action-stale"
        )

        assertEquals(0, rowsUpdated)

        // Verify DB record remained unchanged
        val current = authorityDao.getAuthorityByAlarmId(42L)
        assertEquals(initialAuthority, current)
    }

    @Test
    fun `stale instance token claim is rejected and returns 0`() = runBlocking {
        authorityDao.insertAuthority(initialAuthority)

        val rowsUpdated = authorityDao.casClaimAuthority(
            alarmId = 42L,
            instanceToken = "stale-instance-token", // Stale instance token!
            restoreEpoch = 100L,
            expectedRevision = 1L,
            newRevision = 2L,
            desiredOperation = "CANCEL",
            desiredTriggerMillis = null,
            actionKey = "action-stale-token"
        )

        assertEquals(0, rowsUpdated)
        assertEquals(initialAuthority, authorityDao.getAuthorityByAlarmId(42L))
    }

    @Test
    fun `stale restore epoch claim is rejected and returns 0`() = runBlocking {
        authorityDao.insertAuthority(initialAuthority)

        val rowsUpdated = authorityDao.casClaimAuthority(
            alarmId = 42L,
            instanceToken = "token-instance-42",
            restoreEpoch = 999L, // Stale restore epoch!
            expectedRevision = 1L,
            newRevision = 2L,
            desiredOperation = "CANCEL",
            desiredTriggerMillis = null,
            actionKey = "action-stale-epoch"
        )

        assertEquals(0, rowsUpdated)
        assertEquals(initialAuthority, authorityDao.getAuthorityByAlarmId(42L))
    }

    @Test
    fun `duplicate action does not advance revision if CAS fails`() = runBlocking {
        authorityDao.insertAuthority(initialAuthority)

        val firstClaim = authorityDao.casClaimAuthority(
            alarmId = 42L,
            instanceToken = "token-instance-42",
            restoreEpoch = 100L,
            expectedRevision = 1L,
            newRevision = 2L,
            desiredOperation = "SNOOZE",
            desiredTriggerMillis = 1700000600000L,
            actionKey = "action-snooze-2"
        )
        assertEquals(1, firstClaim)

        // Attempting duplicate claim with stale expectedRevision = 1L
        val duplicateClaim = authorityDao.casClaimAuthority(
            alarmId = 42L,
            instanceToken = "token-instance-42",
            restoreEpoch = 100L,
            expectedRevision = 1L,
            newRevision = 3L,
            desiredOperation = "SNOOZE",
            desiredTriggerMillis = 1700000600000L,
            actionKey = "action-snooze-2"
        )
        assertEquals(0, duplicateClaim)

        val current = authorityDao.getAuthorityByAlarmId(42L)
        assertEquals(2L, current?.scheduleRevision)
    }

    @Test
    fun `old settlement after new claim is rejected and returns 0`() = runBlocking {
        authorityDao.insertAuthority(initialAuthority)

        // Claim 1: revision 1 -> 2 (RECONCILING)
        authorityDao.casClaimAuthority(
            alarmId = 42L, instanceToken = "token-instance-42", restoreEpoch = 100L,
            expectedRevision = 1L, newRevision = 2L, desiredOperation = "SET", desiredTriggerMillis = 2000L, actionKey = "act-2"
        )

        // Claim 2: revision 2 -> 3 (RECONCILING)
        authorityDao.casClaimAuthority(
            alarmId = 42L, instanceToken = "token-instance-42", restoreEpoch = 100L,
            expectedRevision = 2L, newRevision = 3L, desiredOperation = "SET", desiredTriggerMillis = 3000L, actionKey = "act-3"
        )

        // Old settlement attempt for revision 2
        val settledRev2 = authorityDao.settleAuthority(
            alarmId = 42L, instanceToken = "token-instance-42", restoreEpoch = 100L, claimedRevision = 2L
        )
        assertEquals("Settling old revision 2 after newer claim 3 must return 0", 0, settledRev2)

        // Settlement for current revision 3
        val settledRev3 = authorityDao.settleAuthority(
            alarmId = 42L, instanceToken = "token-instance-42", restoreEpoch = 100L, claimedRevision = 3L
        )
        assertEquals("Settling current revision 3 must succeed and return 1", 1, settledRev3)

        val finalAuthority = authorityDao.getAuthorityByAlarmId(42L)
        assertEquals("SETTLED", finalAuthority?.reconcileState)
        assertEquals(3L, finalAuthority?.scheduleRevision)
    }

    @Test
    fun `concurrent claims - exactly one wins and advances revision`() = runBlocking {
        authorityDao.insertAuthority(initialAuthority)

        // Run 5 concurrent CAS attempts with expectedRevision = 1L
        val results = (1..5).map { taskIdx ->
            async {
                authorityDao.casClaimAuthority(
                    alarmId = 42L,
                    instanceToken = "token-instance-42",
                    restoreEpoch = 100L,
                    expectedRevision = 1L,
                    newRevision = 2L,
                    desiredOperation = "OP_$taskIdx",
                    desiredTriggerMillis = 1000L + taskIdx,
                    actionKey = "action-$taskIdx"
                )
            }
        }.awaitAll()

        val successCount = results.count { it == 1 }
        val failureCount = results.count { it == 0 }

        assertEquals("Exactly one concurrent CAS claim must succeed", 1, successCount)
        assertEquals("Remaining 4 concurrent CAS claims must fail", 4, failureCount)

        val current = authorityDao.getAuthorityByAlarmId(42L)
        assertEquals(2L, current?.scheduleRevision)
        assertEquals("RECONCILING", current?.reconcileState)
    }

    @Test
    fun `absent authority returns null and does not imply OS armed`() = runBlocking {
        val authority = authorityDao.getAuthorityByAlarmId(999L)
        assertNull(authority)
    }
}
