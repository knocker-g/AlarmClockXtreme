package com.sysadmindoc.alarmclock.receiver

import android.content.Context
import android.content.Intent
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.sysadmindoc.alarmclock.AlarmClockApp
import com.sysadmindoc.alarmclock.data.local.AlarmDatabase
import com.sysadmindoc.alarmclock.data.model.Alarm
import java.time.DayOfWeek
import com.sysadmindoc.alarmclock.data.preferences.AppSettings
import com.sysadmindoc.alarmclock.data.preferences.PreferencesManager
import com.sysadmindoc.alarmclock.data.repository.AlarmEventRepository
import com.sysadmindoc.alarmclock.data.repository.AlarmIncidentRepository
import com.sysadmindoc.alarmclock.data.repository.AlarmRepository
import com.sysadmindoc.alarmclock.domain.AlarmScheduler
import com.sysadmindoc.alarmclock.domain.NextAlarmCalculator
import com.sysadmindoc.alarmclock.domain.ScheduleOutcome
import com.sysadmindoc.alarmclock.service.WebhookService
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SkipTransitionTest {

    private lateinit var context: Context
    private lateinit var db: AlarmDatabase
    private lateinit var alarmRepo: AlarmRepository
    private lateinit var incidentRepo: AlarmIncidentRepository
    private lateinit var eventRepo: AlarmEventRepository
    private lateinit var scheduler: AlarmScheduler
    private lateinit var mockEntryPoint: AlarmClockApp.AppEntryPoint

    private val calculator: NextAlarmCalculator = mockk(relaxed = true)
    private val preferencesManager: PreferencesManager = mockk(relaxed = true)
    private val webhookService: WebhookService = mockk(relaxed = true)

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        org.robolectric.shadows.ShadowAlarmManager.setCanScheduleExactAlarms(true)
        db = Room.inMemoryDatabaseBuilder(context, AlarmDatabase::class.java)
            .allowMainThreadQueries()
            .build()

        alarmRepo = AlarmRepository(
            database = db,
            dao = db.alarmDao(),
            groupDao = db.alarmGroupDao()
        )

        incidentRepo = AlarmIncidentRepository(
            dao = db.alarmIncidentEventDao(),
            context = context
        )

        eventRepo = AlarmEventRepository(
            dao = db.alarmEventDao()
        )

        every { preferencesManager.getCachedSettings() } returns AppSettings(activeAlarmId = null)
        coEvery { preferencesManager.getCurrentSettings() } returns AppSettings(activeAlarmId = null)

        scheduler = AlarmScheduler(
            context = context,
            repository = alarmRepo,
            calculator = calculator,
            preferencesManager = preferencesManager,
            holidayRepository = mockk(relaxed = true),
            alarmIncidentRepository = incidentRepo,
            weatherRepository = mockk(relaxed = true)
        )

        mockEntryPoint = mockk<AlarmClockApp.AppEntryPoint>(relaxed = true)
        every { mockEntryPoint.alarmRepository() } returns alarmRepo
        every { mockEntryPoint.alarmScheduler() } returns scheduler
        every { mockEntryPoint.nextAlarmCalculator() } returns calculator
        every { mockEntryPoint.alarmEventRepository() } returns eventRepo
        every { mockEntryPoint.webhookService() } returns webhookService
        every { mockEntryPoint.preferencesManager() } returns preferencesManager
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `SkipNextReceiver accepts valid expectedScheduledAt and advances repeating alarm`() = runBlocking {
        val currentTrigger = System.currentTimeMillis() + 3600_000L
        val nextTrigger = currentTrigger + 86400_000L

        every { calculator.calculate(any(), any()) } returns nextTrigger
        every { calculator.calculate(any()) } returns nextTrigger

        val alarmId = alarmRepo.save(
            Alarm(
                hour = 7,
                minute = 0,
                isEnabled = true,
                repeatDays = setOf(DayOfWeek.MONDAY, DayOfWeek.TUESDAY),
                nextTriggerTime = currentTrigger
            )
        )

        val intent = Intent(context, SkipNextReceiver::class.java).apply {
            putExtra(AlarmScheduler.EXTRA_ALARM_ID, alarmId)
            putExtra(AlarmScheduler.EXTRA_SCHEDULED_AT, currentTrigger)
        }

        val receiver = SkipNextReceiver().apply {
            testScope = this@runBlocking
            testEntryPoint = mockEntryPoint
        }
        receiver.onReceive(context, intent)
        kotlinx.coroutines.delay(100)

        val updated = alarmRepo.getById(alarmId)
        assertNotNull("Updated alarm must exist in DB", updated)
        assertNotEquals("Next trigger MUST advance after skip", currentTrigger, updated?.nextTriggerTime)
        assertEquals(nextTrigger, updated?.nextTriggerTime)
    }

    @Test
    fun `SkipNextReceiver rejects stale expectedScheduledAt without mutating DB or recording event`() = runBlocking {
        val currentTrigger = System.currentTimeMillis() + 3600_000L
        val staleExpectedTrigger = currentTrigger - 3600_000L

        val alarmId = alarmRepo.save(
            Alarm(
                hour = 7,
                minute = 0,
                isEnabled = true,
                repeatDays = setOf(DayOfWeek.MONDAY),
                nextTriggerTime = currentTrigger
            )
        )

        val intent = Intent(context, SkipNextReceiver::class.java).apply {
            putExtra(AlarmScheduler.EXTRA_ALARM_ID, alarmId)
            putExtra(AlarmScheduler.EXTRA_SCHEDULED_AT, staleExpectedTrigger)
        }

        val receiver = SkipNextReceiver().apply {
            testScope = this@runBlocking
            testEntryPoint = mockEntryPoint
        }
        receiver.onReceive(context, intent)
        kotlinx.coroutines.delay(100)

        val updated = alarmRepo.getById(alarmId)
        assertEquals("DB trigger MUST remain unchanged on stale skip", currentTrigger, updated?.nextTriggerTime)
    }

    @Test
    fun `SkipNextReceiver rejects skip when alarm is FIRING in active session`() = runBlocking {
        val currentTrigger = System.currentTimeMillis() + 3600_000L

        val alarmId = alarmRepo.save(
            Alarm(
                hour = 7,
                minute = 0,
                isEnabled = true,
                repeatDays = setOf(DayOfWeek.MONDAY),
                nextTriggerTime = currentTrigger
            )
        )

        val firingSettings = AppSettings(
            activeAlarmId = alarmId,
            activeAlarmState = "FIRING"
        )
        every { preferencesManager.getCachedSettings() } returns firingSettings
        coEvery { preferencesManager.getCurrentSettings() } returns firingSettings

        val intent = Intent(context, SkipNextReceiver::class.java).apply {
            putExtra(AlarmScheduler.EXTRA_ALARM_ID, alarmId)
            putExtra(AlarmScheduler.EXTRA_SCHEDULED_AT, currentTrigger)
        }

        val receiver = SkipNextReceiver().apply {
            testScope = this@runBlocking
            testEntryPoint = mockEntryPoint
        }
        receiver.onReceive(context, intent)
        kotlinx.coroutines.delay(100)

        val updated = alarmRepo.getById(alarmId)
        assertEquals("FIRING alarm MUST NOT be skipped", currentTrigger, updated?.nextTriggerTime)
    }

    @Test
    fun `SkipNextReceiver fails closed for stale one-shot skip without disabling newer occurrence`() = runBlocking {
        val currentTrigger = System.currentTimeMillis() + 3600_000L
        val staleTrigger = currentTrigger - 3600_000L

        val alarmId = alarmRepo.save(
            Alarm(
                hour = 8,
                minute = 0,
                isEnabled = true,
                repeatDays = emptySet(), // One-shot alarm
                nextTriggerTime = currentTrigger
            )
        )

        val intent = Intent(context, SkipNextReceiver::class.java).apply {
            putExtra(AlarmScheduler.EXTRA_ALARM_ID, alarmId)
            putExtra(AlarmScheduler.EXTRA_SCHEDULED_AT, staleTrigger)
        }

        val receiver = SkipNextReceiver().apply {
            testScope = this@runBlocking
            testEntryPoint = mockEntryPoint
        }
        receiver.onReceive(context, intent)

        val updated = alarmRepo.getById(alarmId)
        assertNotNull(updated)
        assertTrue("One-shot alarm MUST remain enabled when stale skip is rejected", updated?.isEnabled == true)
        assertEquals(currentTrigger, updated?.nextTriggerTime)
    }

    @Test
    fun `casDisableOneShot atomically disables one shot alarm when expectedTrigger matches`() = runBlocking {
        val triggerTime = System.currentTimeMillis() + 3600_000L
        val id = alarmRepo.save(Alarm(hour = 8, minute = 0, isEnabled = true, nextTriggerTime = triggerTime))

        val rows = alarmRepo.casDisableOneShot(id, triggerTime)
        assertEquals("Atomic one-shot disable MUST update exactly 1 row", 1, rows)

        val updated = alarmRepo.getById(id)
        assertNotNull(updated)
        assertFalse("One-shot alarm MUST be disabled", updated!!.isEnabled)
        assertEquals(0L, updated.nextTriggerTime)
    }

    @Test
    fun `casDisableOneShot fails closed when expectedTrigger mismatches`() = runBlocking {
        val triggerTime = System.currentTimeMillis() + 3600_000L
        val staleTrigger = triggerTime - 1000L
        val id = alarmRepo.save(Alarm(hour = 8, minute = 0, isEnabled = true, nextTriggerTime = triggerTime))

        val rows = alarmRepo.casDisableOneShot(id, staleTrigger)
        assertEquals("Stale one-shot disable MUST update 0 rows", 0, rows)

        val updated = alarmRepo.getById(id)
        assertNotNull(updated)
        assertTrue("One-shot alarm MUST remain enabled when expectedTrigger mismatches", updated!!.isEnabled)
        assertEquals(triggerTime, updated.nextTriggerTime)
    }

    @Test
    fun `SkipNextReceiver fails closed when expectedScheduledAt is missing or non-positive`() = runBlocking {
        val currentTrigger = System.currentTimeMillis() + 3600_000L

        val alarmId = alarmRepo.save(
            Alarm(
                hour = 7,
                minute = 0,
                isEnabled = true,
                repeatDays = setOf(DayOfWeek.MONDAY),
                nextTriggerTime = currentTrigger
            )
        )

        // Missing EXTRA_SCHEDULED_AT
        val intent = Intent(context, SkipNextReceiver::class.java).apply {
            putExtra(AlarmScheduler.EXTRA_ALARM_ID, alarmId)
        }

        val receiver = SkipNextReceiver().apply {
            testScope = this@runBlocking
            testEntryPoint = mockEntryPoint
        }
        receiver.onReceive(context, intent)
        kotlinx.coroutines.delay(100)

        val updated = alarmRepo.getById(alarmId)
        assertEquals("DB trigger MUST remain unchanged when occurrence identity is missing", currentTrigger, updated?.nextTriggerTime)
    }

    @Test
    fun `SkipNextReceiver rejects duplicate skip on same occurrence`() = runBlocking {
        val currentTrigger = System.currentTimeMillis() + 3600_000L
        val nextTrigger = currentTrigger + 86400_000L

        every { calculator.calculate(any(), any()) } returns nextTrigger
        every { calculator.calculate(any()) } returns nextTrigger

        val alarmId = alarmRepo.save(
            Alarm(
                hour = 7,
                minute = 0,
                isEnabled = true,
                repeatDays = setOf(DayOfWeek.MONDAY),
                nextTriggerTime = currentTrigger
            )
        )

        val intent = Intent(context, SkipNextReceiver::class.java).apply {
            putExtra(AlarmScheduler.EXTRA_ALARM_ID, alarmId)
            putExtra(AlarmScheduler.EXTRA_SCHEDULED_AT, currentTrigger)
        }

        val receiver1 = SkipNextReceiver().apply {
            testScope = this@runBlocking
            testEntryPoint = mockEntryPoint
        }
        receiver1.onReceive(context, intent)
        kotlinx.coroutines.delay(100)

        val firstUpdated = alarmRepo.getById(alarmId)
        assertEquals("First skip MUST advance trigger to nextTrigger", nextTrigger, firstUpdated?.nextTriggerTime)

        // Duplicate skip targeting original currentTrigger
        val receiver2 = SkipNextReceiver().apply {
            testScope = this@runBlocking
            testEntryPoint = mockEntryPoint
        }
        receiver2.onReceive(context, intent)
        kotlinx.coroutines.delay(100)

        val secondUpdated = alarmRepo.getById(alarmId)
        assertEquals("Duplicate skip MUST be rejected and DB trigger MUST stay at nextTrigger", nextTrigger, secondUpdated?.nextTriggerTime)
    }
}
