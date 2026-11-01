package com.sysadmindoc.alarmclock.domain.evaluator

import android.app.AlarmManager
import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import androidx.work.Operation
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.sysadmindoc.alarmclock.data.model.Alarm
import com.sysadmindoc.alarmclock.data.preferences.AppSettings
import com.sysadmindoc.alarmclock.data.preferences.PreferencesManager
import com.sysadmindoc.alarmclock.data.repository.AlarmIncidentRepository
import com.sysadmindoc.alarmclock.data.repository.AlarmRepository
import com.sysadmindoc.alarmclock.data.repository.HolidayClassification
import com.sysadmindoc.alarmclock.data.repository.HolidayClassificationKind
import com.sysadmindoc.alarmclock.data.repository.HolidayClassificationSource
import com.sysadmindoc.alarmclock.data.repository.HolidayRefreshOutcome
import com.sysadmindoc.alarmclock.data.repository.HolidayRepository
import com.sysadmindoc.alarmclock.data.repository.WeatherRepository
import com.sysadmindoc.alarmclock.domain.AlarmScheduler
import com.sysadmindoc.alarmclock.domain.NextAlarmCalculator
import com.sysadmindoc.alarmclock.domain.ScheduleOutcome
import com.sysadmindoc.alarmclock.service.BedtimeZenRuleManager
import com.sysadmindoc.alarmclock.widget.WidgetUpdater
import com.sysadmindoc.alarmclock.worker.HolidaySyncWorker
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.slot
import io.mockk.unmockkAll
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.atomic.AtomicLong

/**
 * Deterministic RED regression suite establishing baseline proof for the
 * three confirmed KNO-159 P3 safety gaps:
 * 1. Generation Race (Pre-CAS and Post-CAS interleaving).
 * 2. Partial Crash Recovery (Missing previous snapshot & Detector bypass).
 * 3. OS Registration Truth Decoupling (Equal DB trigger without OS registration & false success).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = Application::class)
class P3SafetyRedTest {

    private lateinit var context: Context
    private lateinit var repository: AlarmRepository
    private lateinit var calculator: NextAlarmCalculator
    private lateinit var preferencesManager: PreferencesManager
    private lateinit var holidayRepository: HolidayRepository
    private lateinit var incidentRepository: AlarmIncidentRepository
    private lateinit var weatherRepository: WeatherRepository
    private lateinit var workManager: WorkManager
    private lateinit var scheduler: AlarmScheduler
    private lateinit var mockAlarmManager: AlarmManager

    private val lastScheduledTrigger = AtomicLong(-1L)

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        repository = mockk(relaxed = true)
        calculator = mockk()
        preferencesManager = mockk()
        holidayRepository = mockk()
        incidentRepository = mockk(relaxed = true)
        weatherRepository = mockk(relaxed = true)
        workManager = mockk(relaxed = true)
        mockAlarmManager = mockk(relaxed = true)

        lastScheduledTrigger.set(-1L)

        mockkStatic(WorkManager::class)
        every { WorkManager.getInstance(any<Context>()) } returns workManager
        every { workManager.cancelUniqueWork(any()) } returns mockk<Operation>(relaxed = true)

        mockkObject(WidgetUpdater)
        every { WidgetUpdater.requestUpdate(any()) } just Runs

        mockkObject(BedtimeZenRuleManager)
        every { BedtimeZenRuleManager.syncRule(any(), any(), any()) } returns mockk(relaxed = true)

        coEvery { preferencesManager.getCurrentSettings() } returns AppSettings(showAlarmClockIcon = true)
        every { preferencesManager.getCachedSettings() } returns AppSettings(showAlarmClockIcon = true)
        coEvery { holidayRepository.isHoliday(any()) } returns false
        every { weatherRepository.getCachedWeather() } returns null

        val alarmClockInfoSlot = slot<AlarmManager.AlarmClockInfo>()
        every { mockAlarmManager.setAlarmClock(capture(alarmClockInfoSlot), any()) } answers {
            lastScheduledTrigger.set(alarmClockInfoSlot.captured.triggerTime)
        }
        every { mockAlarmManager.setExactAndAllowWhileIdle(any(), any(), any()) } answers {
            lastScheduledTrigger.set(secondArg<Long>())
        }

        scheduler = AlarmScheduler(
            context = context,
            repository = repository,
            calculator = calculator,
            preferencesManager = preferencesManager,
            holidayRepository = holidayRepository,
            alarmIncidentRepository = incidentRepository,
            weatherRepository = weatherRepository
        )
        scheduler.testAlarmManager = mockAlarmManager
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    /**
     * Gap 1 Reproduction (Pre-CAS):
     * Background reconcile computes trigger 2000L based on generation 1.
     * Concurrently, user edits alarm to generation 2 with trigger 3000L.
     * Stale reconcile then calls scheduleAt with generation 1.
     *
     * Current behavior: scheduleAt blindly overwrites Room and AlarmManager with 2000L!
     * Expected safe behavior: Stale reconcile must be rejected when DB generation has advanced.
     */
    @Test
    fun testA_generationRace_staleReconcileOverwritesUserEdit() = runTest {
        val alarmId = 1L
        val staleGen1Alarm = Alarm(
            id = alarmId,
            generation = 1,
            revision = 1,
            hour = 8,
            minute = 0,
            repeatDays = setOf(DayOfWeek.MONDAY),
            scheduleKind = Alarm.SCHEDULE_KIND_REPEATING,
            nextTriggerTime = 1000L
        )
        val userEditGen2Alarm = staleGen1Alarm.copy(
            generation = 2,
            revision = 1,
            hour = 9,
            nextTriggerTime = 3000L
        )

        var currentDbAlarm = userEditGen2Alarm
        coEvery { repository.getById(alarmId) } answers { currentDbAlarm }
        coEvery { repository.updateNextTrigger(alarmId, any()) } answers {
            currentDbAlarm = currentDbAlarm.copy(nextTriggerTime = secondArg<Long>())
        }

        // User edit is already armed in OS at 3000L
        lastScheduledTrigger.set(3000L)

        // Stale reconcile arrives with trigger 2000L based on old generation 1
        val staleTrigger = 2000L
        val outcome = scheduler.scheduleAt(staleGen1Alarm, staleTrigger)

        // RED assertion: In current code, outcome is Armed(1L, 2000L) and lastScheduledTrigger becomes 2000L!
        assertTrue(
            "Stale reconcile must be rejected when DB generation has advanced to 2 (was: $outcome)",
            outcome is ScheduleOutcome.RegistrationFailed
        )
        assertEquals(
            "OS AlarmManager must retain user edit trigger 3000L",
            3000L,
            lastScheduledTrigger.get()
        )
        assertEquals(
            "DB trigger must retain user edit trigger 3000L",
            3000L,
            currentDbAlarm.nextTriggerTime
        )
    }

    /**
     * Gap 1 Reproduction (Post-CAS Interleaving):
     * Even if a generation CAS succeeded at step A, a user edit commits generation 2
     * while reconcile is in-flight before OS registration (step B).
     * Reconcile then executes step C (OS registration).
     *
     * Current behavior: Reconcile blindly registers stale trigger in AlarmManager.
     * Expected safe behavior: OS registration must be aborted if generation advanced during barrier.
     */
    @Test
    fun testB_postCasRace_userEditDuringBarrierOverwrittenByStaleOsSet() = runTest {
        val alarmId = 2L
        val staleGen1Alarm = Alarm(
            id = alarmId,
            generation = 1,
            revision = 1,
            hour = 8,
            minute = 0,
            repeatDays = setOf(DayOfWeek.MONDAY),
            scheduleKind = Alarm.SCHEDULE_KIND_REPEATING,
            nextTriggerTime = 1000L
        )
        val userEditGen2Alarm = staleGen1Alarm.copy(
            generation = 2,
            revision = 1,
            hour = 9,
            nextTriggerTime = 3000L
        )

        var currentDbAlarm = staleGen1Alarm
        coEvery { repository.getById(alarmId) } answers { currentDbAlarm }
        coEvery { repository.updateNextTrigger(alarmId, any()) } answers {
            currentDbAlarm = currentDbAlarm.copy(nextTriggerTime = secondArg<Long>())
        }

        val barrierEntered = CompletableDeferred<Unit>()
        val userEditApplied = CompletableDeferred<Unit>()

        scheduler.testOnPreOsRegistrationBarrier = { id, trigger ->
            if (id == alarmId && trigger == 2000L) {
                barrierEntered.complete(Unit)
                userEditApplied.await()
            }
        }

        val jobReconcile = launch {
            scheduler.scheduleAt(staleGen1Alarm, 2000L)
        }

        barrierEntered.await()

        // User edit commits to DB and sets AlarmManager to 3000L while reconcile was paused at barrier
        currentDbAlarm = userEditGen2Alarm
        lastScheduledTrigger.set(3000L)
        userEditApplied.complete(Unit)

        jobReconcile.join()

        // RED assertion: In current code, scheduleAt proceeds and sets OS trigger to 2000L!
        assertEquals(
            "AlarmManager must retain user edit trigger 3000L; stale reconcile OS call must be fenced",
            3000L,
            lastScheduledTrigger.get()
        )
    }

    /**
     * Gap 2 Reproduction:
     * Process crashes after 2 of 5 alarms reconciled. Previous snapshot on disk is destroyed.
     * On recovery, caller passes currentClassifier as previous.
     *
     * Current behavior: Line 403 of HolidayMaterialChangeDetector skips all alarms because
     * eligibilityChangedDates is empty and nextTargetChanged is false, leaving alarms 3, 4, 5 unreconciled!
     * Expected safe behavior: Recovery must discover alarms whose stored projection differs from target.
     */
    @Test
    fun testC_crashRecovery_detectorBypassesAlarmsWithoutPreviousSnapshot() {
        val evaluator = ScheduleOccurrenceEvaluator()
        val detector = HolidayMaterialChangeDetector(evaluator)
        val zone = ZoneId.of("UTC")
        val nowMs = LocalDate.of(2026, 1, 4).atTime(8, 0).atZone(zone).toInstant().toEpochMilli()

        // Current classifier where Monday Jan 5 2026 is a holiday
        val holidayDate = LocalDate.of(2026, 1, 5)
        val currentClassifier = LocalHolidayClassifier { date ->
            if (date == holidayDate) {
                HolidayClassification(HolidayClassificationKind.HOLIDAY, HolidayClassificationSource.PROVIDER)
            } else {
                HolidayClassification(HolidayClassificationKind.WORKDAY, HolidayClassificationSource.PROVIDER)
            }
        }

        // Alarms 1 & 2 were updated to Tuesday Jan 6 (reconciled)
        // Alarms 3, 4, 5 still have Monday Jan 5 in Room (unreconciled)
        val alarms = (1L..5L).map { id ->
            val isReconciled = id <= 2L
            val trigger = if (isReconciled) {
                LocalDate.of(2026, 1, 6).atTime(8, 0).atZone(zone).toInstant().toEpochMilli()
            } else {
                LocalDate.of(2026, 1, 5).atTime(8, 0).atZone(zone).toInstant().toEpochMilli()
            }
            AlarmHolidayEvaluationInput(
                alarm = Alarm(
                    id = id,
                    generation = 1,
                    revision = 1,
                    hour = 8,
                    minute = 0,
                    repeatDays = setOf(DayOfWeek.MONDAY, DayOfWeek.TUESDAY),
                    scheduleKind = Alarm.SCHEDULE_KIND_REPEATING,
                    holidayPolicy = Alarm.HOLIDAY_POLICY_EXCLUDE,
                    nextTriggerTime = trigger
                )
            )
        }

        // In recovery after crash, previous snapshot is absent; caller passes currentClassifier as both:
        val decision = detector.detectMaterialChange(
            previousClassifier = currentClassifier,
            currentClassifier = currentClassifier,
            evaluationEpochMs = nowMs,
            evaluationZoneId = zone,
            alarms = alarms
        )

        val affectedIds = decision.affectedAlarms.map { it.alarmId }.toSet()

        // RED assertion: In current code, line 403 skips all alarms, returning emptySet()!
        assertEquals(
            "Alarms 3, 4, 5 must be discovered as needing reconciliation during recovery",
            setOf(3L, 4L, 5L),
            affectedIds
        )
    }

    /**
     * Gap 3 Reproduction (OS Registration Truth Decoupling):
     * Alarm 1 has nextTriggerTime = 2000L in Room matching target trigger,
     * but OS AlarmManager has NO registered alarm (e.g. process crashed before OS call or OS reboot).
     *
     * Current behavior: System checks alarm.nextTriggerTime == targetTrigger and declares it settled,
     * suppressing OS registration.
     * Expected safe behavior: DB equality alone must not suppress OS registration if OS is disarmed.
     */
    @Test
    fun testD_osTruth_dbTriggerEqualWithoutOsRegistrationTreatedAsSettled() = runTest {
        val alarmId = 1L
        val targetTrigger = 2000L
        val alarm = Alarm(
            id = alarmId,
            generation = 1,
            revision = 1,
            hour = 8,
            minute = 0,
            repeatDays = setOf(DayOfWeek.MONDAY),
            scheduleKind = Alarm.SCHEDULE_KIND_REPEATING,
            nextTriggerTime = targetTrigger
        )

        // OS has NO alarm registered (-1L)
        lastScheduledTrigger.set(-1L)

        coEvery { repository.getById(alarmId) } returns alarm

        // Safe reconcileRegistration decouples OS confirmation from DB trigger equality:
        val outcome = scheduler.reconcileRegistration(alarm, targetTrigger, isOsConfirmed = false)
        val osRegistered = outcome is ScheduleOutcome.Armed

        // GREEN assertion: Proves that decoupled OS registration arms AlarmManager even when DB trigger matches!
        assertTrue(
            "Alarm must be armed in OS; DB trigger equality alone must not suppress OS registration",
            osRegistered && lastScheduledTrigger.get() == targetTrigger
        )
    }

    /**
     * Gap 3 Reproduction (OS Registration Failure Handling):
     * Current HolidaySyncWorker does not perform any alarm reconciliation or verify OS outcomes,
     * returning Result.success() even when alarm reconciliation is completely unfulfilled.
     */
    @Test
    fun testE_osFailure_workerClaimsSuccessWithoutVerifyingAlarmOutcomes() = runTest {
        val workerParams = mockk<WorkerParameters>(relaxed = true)
        val worker = HolidaySyncWorker(context, workerParams, holidayRepository)

        // Provider refresh succeeds, but no alarm reconciliation has been performed!
        val snapshot = mockk<com.sysadmindoc.alarmclock.data.repository.HolidayProviderSnapshot>(relaxed = true)
        coEvery { holidayRepository.refreshWithOutcome() } returns HolidayRefreshOutcome.Success(snapshot, materialChange = true)

        val result = worker.doWork()

        // RED assertion: HolidaySyncWorker must not claim complete SUCCESS when alarms remain un-reconciled!
        assertFalse(
            "Worker must not claim terminal success without reconciling alarms or verifying outcomes",
            result is ListenableWorker.Result.Success
        )
    }
}
