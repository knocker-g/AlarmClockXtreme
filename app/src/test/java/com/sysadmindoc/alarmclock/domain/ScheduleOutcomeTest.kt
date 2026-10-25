package com.sysadmindoc.alarmclock.domain

import android.app.AlarmManager
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.sysadmindoc.alarmclock.data.local.AlarmDatabase
import com.sysadmindoc.alarmclock.data.model.Alarm
import com.sysadmindoc.alarmclock.data.preferences.AppSettings
import com.sysadmindoc.alarmclock.data.preferences.PreferencesManager
import com.sysadmindoc.alarmclock.data.repository.AlarmIncidentRepository
import com.sysadmindoc.alarmclock.data.repository.AlarmRepository
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.spyk
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ScheduleOutcomeTest {

    private lateinit var context: Context
    private lateinit var db: AlarmDatabase
    private lateinit var alarmRepo: AlarmRepository
    private lateinit var incidentRepo: AlarmIncidentRepository
    private lateinit var scheduler: AlarmScheduler

    private val calculator: NextAlarmCalculator = mockk(relaxed = true)
    private val preferencesManager: PreferencesManager = mockk(relaxed = true)

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        org.robolectric.shadows.ShadowAlarmManager.setCanScheduleExactAlarms(true)
        db = Room.inMemoryDatabaseBuilder(context, AlarmDatabase::class.java)
            .allowMainThreadQueries()
            .build()

        alarmRepo = AlarmRepository(
            dao = db.alarmDao(),
            groupDao = db.alarmGroupDao(),
            database = db
        )

        incidentRepo = AlarmIncidentRepository(
            dao = db.alarmIncidentEventDao(),
            context = context
        )

        every { preferencesManager.getCachedSettings() } returns AppSettings()
        coEvery { preferencesManager.getCurrentSettings() } returns AppSettings()

        scheduler = AlarmScheduler(
            context = context,
            repository = alarmRepo,
            calculator = calculator,
            preferencesManager = preferencesManager,
            holidayRepository = mockk(relaxed = true),
            alarmIncidentRepository = incidentRepo,
            weatherRepository = mockk(relaxed = true)
        )
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `successful exact registration returns ScheduleOutcome Armed`() = runBlocking {
        val alarmId = 1001L
        val triggerTime = System.currentTimeMillis() + 3600_000L

        every { calculator.calculate(any()) } returns triggerTime
        every { calculator.calculate(any(), any()) } returns triggerTime

        val alarm = Alarm(id = alarmId, hour = 7, minute = 0, isEnabled = true, nextTriggerTime = triggerTime)
        alarmRepo.save(alarm)

        val outcome = scheduler.schedule(alarm)
        assertTrue("Outcome MUST be Armed, got: $outcome", outcome is ScheduleOutcome.Armed)
        val armed = outcome as ScheduleOutcome.Armed
        assertEquals(alarmId, armed.alarmId)
        assertEquals(triggerTime, armed.triggerTime)
        assertTrue(outcome.isArmed)
    }

    @Test
    fun `disabled alarm returns ScheduleOutcome NoArm with reason DISABLED`() = runBlocking {
        val alarmId = 1002L
        val alarm = Alarm(id = alarmId, hour = 7, minute = 0, isEnabled = false, nextTriggerTime = 0L)
        alarmRepo.save(alarm)

        val outcome = scheduler.schedule(alarm)
        assertTrue("Outcome MUST be NoArm", outcome is ScheduleOutcome.NoArm)
        val noArm = outcome as ScheduleOutcome.NoArm
        assertEquals(alarmId, noArm.alarmId)
        assertEquals("DISABLED", noArm.reason)
        assertFalse(outcome.isArmed)
    }

    @Test
    fun `exact alarm permission revoked returns ScheduleOutcome PermissionBlocked`() = runBlocking {
        val alarmId = 1003L
        val triggerTime = System.currentTimeMillis() + 3600_000L
        org.robolectric.shadows.ShadowAlarmManager.setCanScheduleExactAlarms(false)

        val alarm = Alarm(id = alarmId, hour = 7, minute = 0, isEnabled = true, nextTriggerTime = triggerTime)
        alarmRepo.save(alarm)

        val outcome = scheduler.schedule(alarm)
        assertTrue("Outcome MUST be PermissionBlocked", outcome is ScheduleOutcome.PermissionBlocked)
        val blocked = outcome as ScheduleOutcome.PermissionBlocked
        assertEquals(alarmId, blocked.alarmId)
        assertEquals("EXACT_ALARM_PERMISSION_REVOKED", blocked.reason)
        assertFalse(outcome.isArmed)

        org.robolectric.shadows.ShadowAlarmManager.setCanScheduleExactAlarms(true)
    }

    @Test
    fun `paused app settings returns ScheduleOutcome NoArm with reason PAUSED`() = runBlocking {
        val alarmId = 1004L
        val triggerTime = System.currentTimeMillis() + 3600_000L
        val pausedSettings = AppSettings(pauseUntilMillis = System.currentTimeMillis() + 86400_000L)

        every { preferencesManager.getCachedSettings() } returns pausedSettings
        coEvery { preferencesManager.getCurrentSettings() } returns pausedSettings

        val alarm = Alarm(id = alarmId, hour = 7, minute = 0, isEnabled = true, nextTriggerTime = triggerTime)
        alarmRepo.save(alarm)

        val outcome = scheduler.schedule(alarm)
        assertTrue("Outcome MUST be NoArm PAUSED", outcome is ScheduleOutcome.NoArm)
        val noArm = outcome as ScheduleOutcome.NoArm
        assertEquals(alarmId, noArm.alarmId)
        assertEquals("PAUSED", noArm.reason)
    }

    @Test
    fun `OS AlarmManager SecurityException returns ScheduleOutcome PermissionBlocked`() = runBlocking {
        val alarmId = 1005L
        val triggerTime = System.currentTimeMillis() + 3600_000L

        every { calculator.calculate(any()) } returns triggerTime
        every { calculator.calculate(any(), any()) } returns triggerTime

        val spyAlarmManager = mockk<AlarmManager>(relaxed = true) {
            every { canScheduleExactAlarms() } returns true
            every { setAlarmClock(any(), any()) } throws SecurityException("Permission revoked at runtime")
            every { setExactAndAllowWhileIdle(any(), any(), any()) } throws SecurityException("Permission revoked at runtime")
        }
        scheduler.testAlarmManager = spyAlarmManager

        val alarm = Alarm(id = alarmId, hour = 7, minute = 0, isEnabled = true, nextTriggerTime = triggerTime)
        alarmRepo.save(alarm)

        val outcome = scheduler.schedule(alarm)
        assertTrue("Outcome MUST be PermissionBlocked", outcome is ScheduleOutcome.PermissionBlocked)
        val blocked = outcome as ScheduleOutcome.PermissionBlocked
        assertEquals(alarmId, blocked.alarmId)
        assertEquals("EXACT_ALARM_PERMISSION_REVOKED", blocked.reason)

        scheduler.testAlarmManager = null
    }

    @Test
    fun `rescheduleAllInBatches returns truthful BatchResult with armed, blocked, and noArm counts`() = runBlocking {
        val alarm1 = Alarm(id = 2001L, hour = 7, minute = 0, isEnabled = true, nextTriggerTime = System.currentTimeMillis() + 3600_000L)
        val alarm2 = Alarm(id = 2002L, hour = 8, minute = 0, isEnabled = false, nextTriggerTime = 0L)

        alarmRepo.save(alarm1)
        alarmRepo.save(alarm2)

        every { calculator.calculate(any()) } returns System.currentTimeMillis() + 3600_000L

        val batchResult = scheduler.rescheduleAllInBatches()
        assertEquals(1, batchResult.totalProcessed)
        assertEquals(1, batchResult.armedCount)
        assertEquals(0, batchResult.failedCount)
        assertEquals(0, batchResult.blockedCount)
        assertTrue(batchResult.isFullSuccess)
    }
}
