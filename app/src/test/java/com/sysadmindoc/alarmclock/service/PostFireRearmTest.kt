package com.sysadmindoc.alarmclock.service

import android.app.AlarmManager
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.sysadmindoc.alarmclock.data.local.AlarmDatabase
import com.sysadmindoc.alarmclock.data.model.Alarm
import java.time.DayOfWeek
import com.sysadmindoc.alarmclock.data.preferences.AppSettings
import com.sysadmindoc.alarmclock.data.preferences.PreferencesManager
import com.sysadmindoc.alarmclock.data.repository.AlarmIncidentRepository
import com.sysadmindoc.alarmclock.data.repository.AlarmRepository
import com.sysadmindoc.alarmclock.domain.AlarmScheduler
import com.sysadmindoc.alarmclock.domain.NextAlarmCalculator
import com.sysadmindoc.alarmclock.domain.ScheduleOutcome
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
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
class PostFireRearmTest {

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
    fun `handleAlarmFired for repeating alarm successfully re-arms next occurrence`() = runBlocking {
        val alarmId = 5001L
        val completedAt = System.currentTimeMillis()
        val nextTargetTime = completedAt + 86400_000L // Tomorrow

        every { calculator.calculate(any(), any()) } returns nextTargetTime
        every { calculator.calculate(any()) } returns nextTargetTime

        val repeatingAlarm = Alarm(
            id = alarmId,
            hour = 7,
            minute = 0,
            isEnabled = true,
            repeatDays = setOf(DayOfWeek.MONDAY, DayOfWeek.TUESDAY),
            nextTriggerTime = completedAt
        )
        alarmRepo.save(repeatingAlarm)

        val outcome = scheduler.handleAlarmFired(alarmId, firedScheduledAt = completedAt)

        assertTrue("Post-fire re-arm outcome MUST be Armed", outcome is ScheduleOutcome.Armed)
        val armed = outcome as ScheduleOutcome.Armed
        assertEquals(alarmId, armed.alarmId)
        assertEquals(nextTargetTime, armed.triggerTime)

        val updatedInDb = alarmRepo.getById(alarmId)
        assertTrue("Repeating alarm MUST remain enabled in DB", updatedInDb?.isEnabled == true)
        assertEquals(nextTargetTime, updatedInDb?.nextTriggerTime)
    }

    @Test
    fun `handleAlarmFired for repeating alarm retains enabled state when OS registration fails`() = runBlocking {
        val alarmId = 5002L
        val completedAt = System.currentTimeMillis()
        val nextTargetTime = completedAt + 86400_000L

        every { calculator.calculate(any(), any()) } returns nextTargetTime
        every { calculator.calculate(any()) } returns nextTargetTime

        val spyAlarmManager = mockk<AlarmManager>(relaxed = true) {
            every { canScheduleExactAlarms() } returns true
            every { setAlarmClock(any(), any()) } throws SecurityException("Permission revoked")
            every { setExactAndAllowWhileIdle(any(), any(), any()) } throws SecurityException("Permission revoked")
        }
        scheduler.testAlarmManager = spyAlarmManager

        val repeatingAlarm = Alarm(
            id = alarmId,
            hour = 7,
            minute = 0,
            isEnabled = true,
            repeatDays = setOf(DayOfWeek.MONDAY),
            nextTriggerTime = completedAt
        )
        alarmRepo.save(repeatingAlarm)

        val outcome = scheduler.handleAlarmFired(alarmId, firedScheduledAt = completedAt)

        assertFalse("Outcome MUST NOT be Armed when OS registration fails", outcome.isArmed)
        assertTrue("Outcome MUST be PermissionBlocked", outcome is ScheduleOutcome.PermissionBlocked)

        val updatedInDb = alarmRepo.getById(alarmId)
        assertTrue("Repeating alarm MUST remain enabled in DB even on registration failure for future recovery", updatedInDb?.isEnabled == true)

        scheduler.testAlarmManager = null
    }

    @Test
    fun `handleAlarmFired for one-shot alarm disables alarm and returns NoArm ONE_SHOT_DISABLED`() = runBlocking {
        val alarmId = 5003L
        val completedAt = System.currentTimeMillis()

        val oneShotAlarm = Alarm(
            id = alarmId,
            hour = 8,
            minute = 30,
            isEnabled = true,
            repeatDays = emptySet(),
            nextTriggerTime = completedAt
        )
        alarmRepo.save(oneShotAlarm)

        val outcome = scheduler.handleAlarmFired(alarmId, firedScheduledAt = completedAt)

        assertTrue("One-shot completion outcome MUST be NoArm", outcome is ScheduleOutcome.NoArm)
        val noArm = outcome as ScheduleOutcome.NoArm
        assertEquals(alarmId, noArm.alarmId)
        assertEquals("ONE_SHOT_DISABLED", noArm.reason)

        val updatedInDb = alarmRepo.getById(alarmId)
        assertFalse("One-shot alarm MUST be disabled in DB after firing", updatedInDb?.isEnabled == true)
        assertEquals(0L, updatedInDb?.nextTriggerTime)
    }

    @Test
    fun `handleAlarmFired for repeating alarm returns RegistrationFailed and keeps alarm enabled when OS throws`() = runBlocking {
        val alarmId = 5005L
        val completedAt = System.currentTimeMillis()
        val nextTargetTime = completedAt + 86400_000L

        every { calculator.calculate(any(), any()) } returns nextTargetTime
        every { calculator.calculate(any()) } returns nextTargetTime

        val spyAlarmManager = mockk<AlarmManager>(relaxed = true) {
            every { canScheduleExactAlarms() } returns true
            every { setAlarmClock(any(), any()) } throws IllegalStateException("OS internal error")
            every { setExactAndAllowWhileIdle(any(), any(), any()) } throws IllegalStateException("OS internal error")
        }
        scheduler.testAlarmManager = spyAlarmManager

        val repeatingAlarm = Alarm(
            id = alarmId,
            hour = 7,
            minute = 0,
            isEnabled = true,
            repeatDays = setOf(DayOfWeek.MONDAY),
            nextTriggerTime = completedAt
        )
        alarmRepo.save(repeatingAlarm)

        val outcome = scheduler.handleAlarmFired(alarmId, firedScheduledAt = completedAt)

        assertTrue("Outcome MUST be RegistrationFailed when OS registration throws IllegalStateException", outcome is ScheduleOutcome.RegistrationFailed)
        val failed = outcome as ScheduleOutcome.RegistrationFailed
        assertEquals(alarmId, failed.alarmId)

        val updatedInDb = alarmRepo.getById(alarmId)
        assertTrue("Repeating alarm MUST remain enabled in DB even when registration throws", updatedInDb?.isEnabled == true)

        scheduler.testAlarmManager = null
    }

    @Test
    fun `handleAlarmFired uses notBeforeMillis floor to prevent double-advancement on early fire`() = runBlocking {
        val alarmId = 5004L
        val earlyFireScheduledAt = System.currentTimeMillis() + 600_000L // 10 minutes in future
        val nextTomorrowTarget = earlyFireScheduledAt + 86400_000L

        every { calculator.calculate(any(), any()) } returns nextTomorrowTarget

        val repeatingAlarm = Alarm(
            id = alarmId,
            hour = 7,
            minute = 0,
            isEnabled = true,
            repeatDays = setOf(DayOfWeek.MONDAY, DayOfWeek.TUESDAY),
            nextTriggerTime = earlyFireScheduledAt
        )
        alarmRepo.save(repeatingAlarm)

        val outcome = scheduler.handleAlarmFired(alarmId, firedScheduledAt = earlyFireScheduledAt)

        assertTrue("Outcome MUST be Armed", outcome is ScheduleOutcome.Armed)
        val armed = outcome as ScheduleOutcome.Armed
        assertEquals(nextTomorrowTarget, armed.triggerTime)

        // Verify calculator was invoked with notBeforeMillis floor (firedScheduledAt + 60_000L)
        io.mockk.verify {
            calculator.calculate(
                any(),
                java.time.Instant.ofEpochMilli(earlyFireScheduledAt + 60_000L).atZone(java.time.ZoneId.systemDefault())
            )
        }
    }
}
