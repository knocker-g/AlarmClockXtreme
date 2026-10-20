package com.sysadmindoc.alarmclock.domain

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.sysadmindoc.alarmclock.data.local.AlarmDatabase
import com.sysadmindoc.alarmclock.data.local.ScheduleAuthorityDao
import com.sysadmindoc.alarmclock.data.local.entity.ScheduleAuthorityMeta
import com.sysadmindoc.alarmclock.data.model.Alarm
import com.sysadmindoc.alarmclock.data.repository.AlarmRepository
import io.mockk.coEvery
import io.mockk.mockk
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
class ScheduleAuthorityBootstrapAdapterTest {

    private lateinit var context: Context
    private lateinit var database: AlarmDatabase
    private lateinit var authorityDao: ScheduleAuthorityDao
    private lateinit var alarmRepository: AlarmRepository
    private lateinit var calculator: NextAlarmCalculator
    private lateinit var bootstrapAdapter: ScheduleAuthorityBootstrapAdapter

    private val testAlarm = Alarm(
        id = 42L,
        hour = 7,
        minute = 30,
        label = "Test Bootstrap Alarm",
        isEnabled = true,
        nextTriggerTime = 1000L // Legacy nextTriggerTime
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
        calculator = mockk()

        bootstrapAdapter = ScheduleAuthorityBootstrapAdapter(
            authorityDao = authorityDao,
            alarmRepository = alarmRepository,
            calculator = calculator
        )
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun `missing incarnation evidence returns QUARANTINE without fabricating instanceToken`() = runBlocking {
        coEvery { alarmRepository.getById(42L) } returns testAlarm
        authorityDao.insertMeta(ScheduleAuthorityMeta(id = 1, restoreEpoch = 100L, bootstrapState = "ENFORCED"))

        val result = bootstrapAdapter.bootstrapAuthorityForAlarm(alarmId = 42L, instanceToken = null)

        assertEquals(BootstrapDecision.QUARANTINE, result)
        assertNull("No authority row must be created when instanceToken is missing", authorityDao.getAuthorityByAlarmId(42L))
    }

    @Test
    fun `missing restore epoch meta returns QUARANTINE without fabricating restoreEpoch`() = runBlocking {
        coEvery { alarmRepository.getById(42L) } returns testAlarm
        // Meta NOT inserted in authorityDao!

        val result = bootstrapAdapter.bootstrapAuthorityForAlarm(alarmId = 42L, instanceToken = "token-verified-42")

        assertEquals(BootstrapDecision.QUARANTINE, result)
        assertNull("No authority row must be created when restoreEpoch meta is missing", authorityDao.getAuthorityByAlarmId(42L))
    }

    @Test
    fun `valid evidence recalculates trigger time from calculator instead of trusting nextTriggerTime`() = runBlocking {
        coEvery { alarmRepository.getById(42L) } returns testAlarm
        authorityDao.insertMeta(ScheduleAuthorityMeta(id = 1, restoreEpoch = 100L, bootstrapState = "ENFORCED"))

        val recalculatedTarget = System.currentTimeMillis() + 600_000L
        coEvery { calculator.calculate(any(), any()) } returns recalculatedTarget

        val result = bootstrapAdapter.bootstrapAuthorityForAlarm(alarmId = 42L, instanceToken = "token-verified-42")

        assertEquals(BootstrapDecision.RECONCILE, result)
        val created = authorityDao.getAuthorityByAlarmId(42L)
        assertNotNull(created)
        assertEquals("RECONCILING", created?.reconcileState)
        assertEquals("token-verified-42", created?.instanceToken)
        assertEquals(100L, created?.restoreEpoch)
        assertEquals("Calculated trigger target must be used instead of legacy 1000L", recalculatedTarget, created?.desiredTriggerMillis)
    }
}
