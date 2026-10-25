package com.sysadmindoc.alarmclock.service

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.sysadmindoc.alarmclock.data.local.AlarmDatabase
import com.sysadmindoc.alarmclock.data.local.entity.AlarmIncidentEvent
import com.sysadmindoc.alarmclock.data.model.Alarm
import com.sysadmindoc.alarmclock.data.preferences.AppSettings
import com.sysadmindoc.alarmclock.data.preferences.PreferencesManager
import com.sysadmindoc.alarmclock.data.repository.AlarmIncidentRepository
import com.sysadmindoc.alarmclock.data.repository.AlarmRepository
import com.sysadmindoc.alarmclock.domain.AlarmScheduler
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.spyk
import io.mockk.verify
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
class SmartWakeHandoffTest {

    private lateinit var context: Context
    private lateinit var db: AlarmDatabase
    private lateinit var alarmRepo: AlarmRepository
    private lateinit var incidentRepo: AlarmIncidentRepository
    private lateinit var scheduler: AlarmScheduler

    private val calculator: com.sysadmindoc.alarmclock.domain.NextAlarmCalculator = mockk(relaxed = true)
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

        scheduler = spyk(
            AlarmScheduler(
                context = context,
                repository = alarmRepo,
                calculator = mockk(relaxed = true),
                preferencesManager = preferencesManager,
                holidayRepository = mockk(relaxed = true),
                alarmIncidentRepository = incidentRepo,
                weatherRepository = mockk(relaxed = true)
            )
        )
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `cancelMainScheduledAlarm rejects stale trigger when alarm nextTriggerTime is newer`() = runBlocking {
        val alarmId = 101L
        val originalTargetTime = 1700000000000L
        val newerTargetTime = 1800000000000L

        // Alarm 101 saved with a newer target time
        alarmRepo.save(
            Alarm(
                id = alarmId,
                hour = 7,
                minute = 0,
                isEnabled = true,
                nextTriggerTime = newerTargetTime
            )
        )

        // Smart wake attempts to cancel the original target time
        val cancelled = scheduler.cancelMainScheduledAlarm(alarmId, originalTargetTime)
        assertFalse("Stale SmartWake cancellation MUST be rejected when newer trigger exists", cancelled)
    }

    @Test
    fun `cancelMainScheduledAlarm succeeds when target time matches current alarm trigger`() = runBlocking {
        val alarmId = 102L
        val targetTime = System.currentTimeMillis() + 3600_000L

        every { calculator.calculate(any()) } returns targetTime

        val alarm = Alarm(
            id = alarmId,
            hour = 7,
            minute = 0,
            isEnabled = true,
            nextTriggerTime = targetTime
        )
        alarmRepo.save(alarm)
        val scheduleSuccess = scheduler.scheduleAt(alarm, targetTime, requestWidgetUpdate = false)
        assertTrue("scheduleAt MUST succeed", scheduleSuccess)

        val cancelled = scheduler.cancelMainScheduledAlarm(alarmId, targetTime)
        assertTrue("SmartWake cancellation MUST succeed when target time matches current trigger and PendingIntent exists", cancelled)
    }

    @Test
    fun `positive delivery proof count returns 0 for REQUESTED status alone and 1 for SUCCEEDED status`() = runBlocking {
        val alarmId = 103L
        val scheduledAt = 1700000000000L
        val fireId = AlarmIncidentEvent.fireIdFor(alarmId, scheduledAt)

        // 1. Record STATUS_REQUESTED incident (FGS start requested)
        incidentRepo.record(
            alarmId = alarmId,
            fireId = fireId,
            scheduledAt = scheduledAt,
            type = AlarmIncidentEvent.TYPE_BROADCAST,
            status = AlarmIncidentEvent.STATUS_REQUESTED,
            reasonCode = "START_FOREGROUND_SERVICE",
            source = "Test"
        )

        // STATUS_REQUESTED is NOT delivery proof
        val requestedProof = incidentRepo.occurrenceDeliveryCount(alarmId, scheduledAt)
        assertEquals("STATUS_REQUESTED alone MUST NOT constitute positive delivery proof", 0, requestedProof)

        // 2. Record STATUS_SUCCEEDED incident (Alarm notification posted)
        incidentRepo.record(
            alarmId = alarmId,
            fireId = fireId,
            scheduledAt = scheduledAt,
            type = AlarmIncidentEvent.TYPE_NOTIFICATION,
            status = AlarmIncidentEvent.STATUS_SUCCEEDED,
            reasonCode = "ALARM_NOTIFICATION_POSTED",
            source = "Test"
        )

        // STATUS_SUCCEEDED constitutes positive delivery proof
        val succeededProof = incidentRepo.occurrenceDeliveryCount(alarmId, scheduledAt)
        assertEquals("STATUS_SUCCEEDED notification MUST constitute positive delivery proof", 1, succeededProof)
    }
}
