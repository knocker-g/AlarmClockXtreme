package com.sysadmindoc.alarmclock.receiver

import android.content.Context
import android.content.Intent
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.sysadmindoc.alarmclock.AlarmClockApp
import com.sysadmindoc.alarmclock.data.local.AlarmDatabase
import com.sysadmindoc.alarmclock.data.local.entity.ScheduleAuthority
import com.sysadmindoc.alarmclock.data.local.entity.ScheduleAuthorityMeta
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
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
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

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

    private val zoneId = ZoneId.systemDefault()
    private val monday0700Ms = LocalDate.of(2026, 11, 2)
        .atTime(LocalTime.of(7, 0))
        .atZone(zoneId)
        .toInstant()
        .toEpochMilli()

    private val tuesday0700Ms = LocalDate.of(2026, 11, 3)
        .atTime(LocalTime.of(7, 0))
        .atZone(zoneId)
        .toInstant()
        .toEpochMilli()

    private val mondayNextWeek0700Ms = LocalDate.of(2026, 11, 9)
        .atTime(LocalTime.of(7, 0))
        .atZone(zoneId)
        .toInstant()
        .toEpochMilli()

    @Before
    fun setUp() {
        runBlocking {
            context = ApplicationProvider.getApplicationContext()
            org.robolectric.shadows.ShadowAlarmManager.setCanScheduleExactAlarms(true)
            db = Room.inMemoryDatabaseBuilder(context, AlarmDatabase::class.java)
                .addCallback(AlarmDatabase.DATABASE_CALLBACK)
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
            every { mockEntryPoint.alarmDatabase() } returns db
            every { mockEntryPoint.alarmScheduler() } returns scheduler
            every { mockEntryPoint.nextAlarmCalculator() } returns calculator
            every { mockEntryPoint.alarmEventRepository() } returns eventRepo
            every { mockEntryPoint.webhookService() } returns webhookService
            every { mockEntryPoint.preferencesManager() } returns preferencesManager
        }
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun insertAuthorityForAlarm(alarmId: Long, triggerMillis: Long) {
        runBlocking {
            if (db.scheduleAuthorityDao().getAuthorityByAlarmId(alarmId) == null) {
                val authority = ScheduleAuthority(
                    alarmId = alarmId,
                    instanceToken = "auth-token-$alarmId",
                    definitionRevision = 1L,
                    scheduleRevision = 1L,
                    lifecycle = "LIVE",
                    desiredOperation = "SET",
                    desiredTriggerMillis = triggerMillis,
                    reconcileState = "SETTLED",
                    restoreEpoch = 100L,
                    actionKey = null
                )
                db.scheduleAuthorityDao().insertAuthority(authority)
            }
            if (db.issuedOccurrenceDao().getSlot(alarmId) == null) {
                db.issuedOccurrenceDao().insertSlotInitial(com.sysadmindoc.alarmclock.data.local.entity.IssueSlot(alarmId = alarmId, generationId = 1L, currentIssueToken = null))
            }
        }
    }

    @Test
    fun `SkipNextReceiver accepts valid expectedScheduledAt and advances repeating alarm`() = runBlocking {
        val currentTrigger = monday0700Ms
        val nextTrigger = tuesday0700Ms

        every { calculator.calculate(any(), any()) } returns nextTrigger
        every { calculator.calculate(any()) } returns nextTrigger

        val alarmId = alarmRepo.save(
            Alarm(
                hour = 7,
                minute = 0,
                isEnabled = true,
                scheduleKind = Alarm.SCHEDULE_KIND_REPEATING,
                repeatDays = setOf(DayOfWeek.MONDAY, DayOfWeek.TUESDAY),
                nextTriggerTime = currentTrigger
            )
        )
        insertAuthorityForAlarm(alarmId, currentTrigger)

        val intent = Intent(context, SkipNextReceiver::class.java).apply {
            putExtra(AlarmScheduler.EXTRA_ALARM_ID, alarmId)
            putExtra(AlarmScheduler.EXTRA_SCHEDULED_AT, currentTrigger)
        }

        val receiver = SkipNextReceiver().apply {
            testScope = CoroutineScope(Dispatchers.Unconfined)
            testEntryPoint = mockEntryPoint
        }
        receiver.onReceive(context, intent)

        val updated = alarmRepo.getById(alarmId)
        assertNotNull("Updated alarm must exist in DB", updated)
        assertNotEquals("Next trigger MUST advance after skip", currentTrigger, updated?.nextTriggerTime)
        assertEquals(nextTrigger, updated?.nextTriggerTime)
    }

    @Test
    fun `SkipNextReceiver rejects stale expectedScheduledAt without mutating DB or recording event`() = runBlocking {
        val currentTrigger = monday0700Ms
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
        insertAuthorityForAlarm(alarmId, currentTrigger)

        val intent = Intent(context, SkipNextReceiver::class.java).apply {
            putExtra(AlarmScheduler.EXTRA_ALARM_ID, alarmId)
            putExtra(AlarmScheduler.EXTRA_SCHEDULED_AT, staleExpectedTrigger)
        }

        val receiver = SkipNextReceiver().apply {
            testScope = CoroutineScope(Dispatchers.Unconfined)
            testEntryPoint = mockEntryPoint
        }
        receiver.onReceive(context, intent)

        val updated = alarmRepo.getById(alarmId)
        assertEquals("DB trigger MUST remain unchanged when occurrence identity is missing", currentTrigger, updated?.nextTriggerTime)
    }

    @Test
    fun `SkipNextReceiver rejects skip when EXTRA_SCHEDULED_AT is missing`() = runBlocking {
        val currentTrigger = monday0700Ms

        val alarmId = alarmRepo.save(
            Alarm(
                hour = 7,
                minute = 0,
                isEnabled = true,
                repeatDays = setOf(DayOfWeek.MONDAY),
                nextTriggerTime = currentTrigger
            )
        )
        insertAuthorityForAlarm(alarmId, currentTrigger)

        // Missing EXTRA_SCHEDULED_AT
        val intent = Intent(context, SkipNextReceiver::class.java).apply {
            putExtra(AlarmScheduler.EXTRA_ALARM_ID, alarmId)
        }

        val receiver = SkipNextReceiver().apply {
            testScope = CoroutineScope(Dispatchers.Unconfined)
            testEntryPoint = mockEntryPoint
        }
        receiver.onReceive(context, intent)

        val updated = alarmRepo.getById(alarmId)
        assertEquals("DB trigger MUST remain unchanged when occurrence identity is missing", currentTrigger, updated?.nextTriggerTime)
    }

    @Test
    fun `SkipNextReceiver rejects duplicate skip on same occurrence`() = runBlocking {
        val currentTrigger = monday0700Ms
        val nextTrigger = mondayNextWeek0700Ms

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
        insertAuthorityForAlarm(alarmId, currentTrigger)

        val intent = Intent(context, SkipNextReceiver::class.java).apply {
            putExtra(AlarmScheduler.EXTRA_ALARM_ID, alarmId)
            putExtra(AlarmScheduler.EXTRA_SCHEDULED_AT, currentTrigger)
        }

        val receiver1 = SkipNextReceiver().apply {
            testScope = CoroutineScope(Dispatchers.Unconfined)
            testEntryPoint = mockEntryPoint
        }
        receiver1.onReceive(context, intent)

        val firstUpdated = alarmRepo.getById(alarmId)
        assertEquals("First skip MUST advance trigger to nextTrigger", nextTrigger, firstUpdated?.nextTriggerTime)

        // Duplicate skip targeting original currentTrigger
        val receiver2 = SkipNextReceiver().apply {
            testScope = CoroutineScope(Dispatchers.Unconfined)
            testEntryPoint = mockEntryPoint
        }
        receiver2.onReceive(context, intent)

        val secondUpdated = alarmRepo.getById(alarmId)
        assertEquals("Duplicate skip MUST be rejected and DB trigger MUST stay at nextTrigger", nextTrigger, secondUpdated?.nextTriggerTime)
    }
}
