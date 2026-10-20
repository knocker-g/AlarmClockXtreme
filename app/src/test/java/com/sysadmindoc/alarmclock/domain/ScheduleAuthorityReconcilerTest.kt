package com.sysadmindoc.alarmclock.domain

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.sysadmindoc.alarmclock.data.local.AlarmDatabase
import com.sysadmindoc.alarmclock.data.local.ScheduleAuthorityDao
import com.sysadmindoc.alarmclock.data.local.entity.ScheduleAuthority
import com.sysadmindoc.alarmclock.data.model.Alarm
import com.sysadmindoc.alarmclock.data.repository.AlarmRepository
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class ScheduleAuthorityReconcilerTest {

    private lateinit var context: Context
    private lateinit var database: AlarmDatabase
    private lateinit var authorityDao: ScheduleAuthorityDao
    private lateinit var reconciler: ScheduleAuthorityReconciler
    private lateinit var bootstrapAdapter: ScheduleAuthorityBootstrapAdapter
    private lateinit var alarmRepository: AlarmRepository
    private val fakeOsSlot = mutableListOf<String>()

    private val initialUnreconciled = ScheduleAuthority(
        alarmId = 42L,
        instanceToken = "token-rec-42",
        definitionRevision = 1L,
        scheduleRevision = 1L,
        lifecycle = "LIVE",
        desiredOperation = "SET",
        desiredTriggerMillis = 2000L,
        reconcileState = "RECONCILING",
        restoreEpoch = 100L,
        actionKey = "act-rec"
    )

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        database = Room.inMemoryDatabaseBuilder(context, AlarmDatabase::class.java)
            .allowMainThreadQueries()
            .addCallback(AlarmDatabase.DATABASE_CALLBACK)
            .build()
        authorityDao = database.scheduleAuthorityDao()
        alarmRepository = mockk()

        fakeOsSlot.clear()
        val slotApplier = MainSlotApplier { authority ->
            fakeOsSlot.clear()
            if (authority.desiredOperation == "SET") {
                fakeOsSlot.add("SET_${authority.desiredTriggerMillis}")
            } else {
                fakeOsSlot.add("CANCEL")
            }
            true
        }

        val owner = ScheduleAuthorityOwner(
            authorityDao = authorityDao,
            mainSlotApplier = slotApplier,
            exactPermissionChecker = { true }
        )

        reconciler = ScheduleAuthorityReconciler(
            authorityDao = authorityDao,
            owner = owner
        )

        bootstrapAdapter = ScheduleAuthorityBootstrapAdapter(
            authorityDao = authorityDao,
            alarmRepository = alarmRepository
        )
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun `reconciler discovers RECONCILING authority, applies OS operation, and transitions to SETTLED`() = runBlocking {
        authorityDao.insertAuthority(initialUnreconciled)

        val count = reconciler.reconcilePendingAuthorities()

        assertEquals(1, count)
        val settledAuth = authorityDao.getAuthorityByAlarmId(42L)
        assertNotNull(settledAuth)
        assertEquals("SETTLED", settledAuth?.reconcileState)
        assertEquals(listOf("SET_2000"), fakeOsSlot)
    }

    @Test
    fun `bootstrapAdapter creates RECONCILING authority for enabled alarm lacking authority`() = runBlocking {
        val alarm = Alarm(id = 99L, hour = 8, minute = 0, label = "Bootstrap Alarm", isEnabled = true, nextTriggerTime = 5000L)
        coEvery { alarmRepository.getAll() } returns listOf(alarm)

        val bootstrapped = bootstrapAdapter.bootstrapMissingAuthorities()

        assertEquals(1, bootstrapped.size)
        val authInDb = authorityDao.getAuthorityByAlarmId(99L)
        assertNotNull(authInDb)
        assertEquals("RECONCILING", authInDb?.reconcileState)
        assertEquals("SET", authInDb?.desiredOperation)
        assertEquals(5000L, authInDb?.desiredTriggerMillis)

        // Run reconciler to settle bootstrapped authority
        val reconciledCount = reconciler.reconcilePendingAuthorities()
        assertEquals(1, reconciledCount)
        assertEquals("SETTLED", authorityDao.getAuthorityByAlarmId(99L)?.reconcileState)
        assertEquals(listOf("SET_5000"), fakeOsSlot)
    }
}
