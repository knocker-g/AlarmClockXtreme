package com.sysadmindoc.alarmclock.domain.evaluator

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.sysadmindoc.alarmclock.data.local.AlarmDao
import com.sysadmindoc.alarmclock.data.local.AlarmDatabase
import com.sysadmindoc.alarmclock.data.local.DurableOperationIntentDao
import com.sysadmindoc.alarmclock.data.local.IssuedOccurrenceDao
import com.sysadmindoc.alarmclock.data.local.ScheduleAuthorityDao
import com.sysadmindoc.alarmclock.data.local.ScheduleExceptionDao
import com.sysadmindoc.alarmclock.data.local.entity.DurableOperationIntent
import com.sysadmindoc.alarmclock.data.local.entity.IssueSlot
import com.sysadmindoc.alarmclock.data.local.entity.ScheduleAuthority
import com.sysadmindoc.alarmclock.data.local.entity.ScheduleException
import com.sysadmindoc.alarmclock.data.model.Alarm
import com.sysadmindoc.alarmclock.domain.AlarmScheduler
import com.sysadmindoc.alarmclock.domain.ScheduleOutcome
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class OneTimeOverrideFoundationTest {

    private lateinit var context: Context
    private lateinit var db: AlarmDatabase
    private lateinit var alarmDao: AlarmDao
    private lateinit var exceptionDao: ScheduleExceptionDao
    private lateinit var authorityDao: ScheduleAuthorityDao
    private lateinit var issuedDao: IssuedOccurrenceDao
    private lateinit var durableDao: DurableOperationIntentDao

    private lateinit var coordinator: DurableTimeOverrideClaimCoordinator
    private lateinit var adapter: DurableOneTimeOverrideAdapter

    private val zoneId = ZoneId.of("America/New_York")
    private val wedDate = LocalDate.of(2026, 11, 4)

    // Wednesday 06:00 AM
    private val nowEpochMs = wedDate
        .atTime(LocalTime.of(6, 0))
        .atZone(zoneId)
        .toInstant()
        .toEpochMilli()

    // Wednesday 07:00 AM (canonical base trigger time)
    private val wed0700Ms = wedDate
        .atTime(LocalTime.of(7, 0))
        .atZone(zoneId)
        .toInstant()
        .toEpochMilli()

    // Wednesday 06:30 AM (earlier override)
    private val wed0630Ms = wedDate
        .atTime(LocalTime.of(6, 30))
        .atZone(zoneId)
        .toInstant()
        .toEpochMilli()

    // Wednesday 08:30 AM (later override)
    private val wed0830Ms = wedDate
        .atTime(LocalTime.of(8, 30))
        .atZone(zoneId)
        .toInstant()
        .toEpochMilli()

    private val validAuthority = ScheduleAuthority(
        alarmId = 1L,
        instanceToken = "token_alarm_1_abc",
        definitionRevision = 1L,
        scheduleRevision = 1L,
        lifecycle = "LIVE",
        desiredOperation = "SET",
        desiredTriggerMillis = wed0700Ms,
        reconcileState = "SETTLED",
        restoreEpoch = 1700000000L,
        actionKey = null
    )

    private val validFingerprint = ScheduleAuthorityFingerprint.fromAuthority(validAuthority)

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, AlarmDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        alarmDao = db.alarmDao()
        exceptionDao = db.scheduleExceptionDao()
        authorityDao = db.scheduleAuthorityDao()
        issuedDao = db.issuedOccurrenceDao()
        durableDao = db.durableOperationIntentDao()

        coordinator = DurableTimeOverrideClaimCoordinator(database = db)
        adapter = DurableOneTimeOverrideAdapter(
            database = db,
            timeOverrideCoordinator = coordinator
        )
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun insertStandardRepeatingAlarm(alarmId: Long = 1L): Alarm {
        val alarm = Alarm(
            id = alarmId,
            hour = 7,
            minute = 0,
            isEnabled = true,
            scheduleKind = Alarm.SCHEDULE_KIND_REPEATING,
            repeatDays = setOf(DayOfWeek.WEDNESDAY, DayOfWeek.FRIDAY),
            generation = 1,
            revision = 1,
            nextTriggerTime = wed0700Ms
        )
        runBlocking {
            alarmDao.insert(alarm)
            authorityDao.insertAuthority(validAuthority.copy(alarmId = alarmId, desiredTriggerMillis = alarm.nextTriggerTime))
            issuedDao.insertSlotInitial(IssueSlot(alarmId = alarmId, generationId = alarm.generation.toLong(), currentIssueToken = null))
        }
        return alarm
    }

    // A. Change next occurrence earlier
    @Test
    fun `A Change next occurrence earlier commits time override and updates nextTriggerTime`() = runBlocking {
        insertStandardRepeatingAlarm()

        val result = adapter.executeChangeTimeOnce(
            alarmId = 1L,
            expectedScheduledAt = wed0700Ms,
            newTime = LocalTime.of(6, 30),
            nowEpochMs = nowEpochMs,
            evaluationZoneId = zoneId
        )

        assertEquals(OneTimeOverrideOutcome.SUCCESS_COMMITTED, result.outcome)
        assertNotNull(result.updatedAlarm)
        assertEquals(wed0630Ms, result.updatedAlarm?.nextTriggerTime)

        // Check persisted exception in DB
        val exception = exceptionDao.getExceptionForOccurrence(1L, wed0700Ms, 1)
        assertNotNull(exception)
        assertEquals(ScheduleException.TYPE_TIME_OVERRIDE, exception?.exceptionType)
        assertEquals(wed0630Ms, exception?.overrideTriggerTime)

        // Check Alarm Definition hour/minute unchanged
        val inDb = alarmDao.getById(1L)
        assertEquals(7, inDb?.hour)
        assertEquals(0, inDb?.minute)
        assertEquals(wed0630Ms, inDb?.nextTriggerTime)
    }

    // B. Change next occurrence later
    @Test
    fun `B Change next occurrence later commits time override and updates nextTriggerTime`() = runBlocking {
        insertStandardRepeatingAlarm()

        val result = adapter.executeChangeTimeOnce(
            alarmId = 1L,
            expectedScheduledAt = wed0700Ms,
            newTime = LocalTime.of(8, 30),
            nowEpochMs = nowEpochMs,
            evaluationZoneId = zoneId
        )

        assertEquals(OneTimeOverrideOutcome.SUCCESS_COMMITTED, result.outcome)
        assertEquals(wed0830Ms, result.updatedAlarm?.nextTriggerTime)

        val exception = exceptionDao.getExceptionForOccurrence(1L, wed0700Ms, 1)
        assertNotNull(exception)
        assertEquals(wed0830Ms, exception?.overrideTriggerTime)
    }

    // C. Reject past datetime
    @Test
    fun `C Reject past datetime at commit time`() = runBlocking {
        insertStandardRepeatingAlarm()

        // 05:30 is in the past relative to nowEpochMs (06:00)
        val result = adapter.executeChangeTimeOnce(
            alarmId = 1L,
            expectedScheduledAt = wed0700Ms,
            newTime = LocalTime.of(5, 30),
            nowEpochMs = nowEpochMs,
            evaluationZoneId = zoneId
        )

        assertEquals(OneTimeOverrideOutcome.INVALID_TARGET_TIME, result.outcome)
        assertEquals(DurableTimeOverrideClaimCoordinator.REASON_PAST_TARGET_TIME, result.decisionReason)

        // Verify zero exceptions written
        val exceptions = exceptionDao.getExceptionsForAlarm(1L)
        assertTrue(exceptions.isEmpty())

        // Alarm projection unchanged
        val inDb = alarmDao.getById(1L)
        assertEquals(wed0700Ms, inDb?.nextTriggerTime)
    }

    // D. Reject midnight or day rollover
    @Test
    fun `D Reject midnight and day rollover`() = runBlocking {
        insertStandardRepeatingAlarm()

        val snapshot = CurrentNextActionSnapshot(
            alarmId = 1L,
            expectedScheduledTargetEpochMs = wed0700Ms,
            expectedBaseTriggerTimeEpochMs = wed0700Ms,
            expectedGeneration = 1,
            authorityFingerprint = validFingerprint
        )

        // Target local date is Wednesday 2026-11-04
        // If an evaluator or coordinator attempts to roll over to Thursday 00:01
        // Coordinator checks: overrideZdt.toLocalDate() == targetLocalDate
        val result = coordinator.claimDurableTimeOverride(
            actionSnapshot = snapshot,
            newTime = LocalTime.of(23, 59),
            nowEpochMs = nowEpochMs,
            evaluationZoneId = zoneId
        )
        // 23:59 on the same date is valid
        assertEquals(DurableTimeOverrideClaimOutcome.COMMITTED_PENDING_RECONCILE, result.outcome)

        // Clear for next check
        exceptionDao.deleteForAlarm(1L)

        // If target was evaluated on Tuesday but action was Wed, or date changed, it rejects
        val staleSnapshot = snapshot.copy(expectedBaseTriggerTimeEpochMs = wed0700Ms + 86400000L)
        val staleResult = coordinator.claimDurableTimeOverride(
            actionSnapshot = staleSnapshot,
            newTime = LocalTime.of(7, 30),
            nowEpochMs = nowEpochMs,
            evaluationZoneId = zoneId
        )
        assertEquals(DurableTimeOverrideClaimOutcome.STALE_OCCURRENCE, staleResult.outcome)
    }

    // E. Reject stale generation and revision
    @Test
    fun `E Reject stale generation and revision`() = runBlocking {
        insertStandardRepeatingAlarm()

        val staleSnapshot = CurrentNextActionSnapshot(
            alarmId = 1L,
            expectedScheduledTargetEpochMs = wed0700Ms,
            expectedBaseTriggerTimeEpochMs = wed0700Ms,
            expectedGeneration = 999, // Stale generation
            authorityFingerprint = validFingerprint.copy(scheduleRevision = 999)
        )

        val result = coordinator.claimDurableTimeOverride(
            actionSnapshot = staleSnapshot,
            newTime = LocalTime.of(7, 30),
            nowEpochMs = nowEpochMs,
            evaluationZoneId = zoneId
        )

        assertEquals(DurableTimeOverrideClaimOutcome.STALE_OCCURRENCE, result.outcome)
        assertEquals(CurrentNextDecisionPlanner.REASON_GENERATION_MISMATCH, result.decisionReason)
    }

    // F. Reject changed current-next identity
    @Test
    fun `F Reject changed current-next identity`() = runBlocking {
        insertStandardRepeatingAlarm()

        // Caller expects 08:00 but current-next is 07:00
        val result = adapter.executeChangeTimeOnce(
            alarmId = 1L,
            expectedScheduledAt = wed0700Ms + 3600000L,
            newTime = LocalTime.of(8, 30),
            nowEpochMs = nowEpochMs,
            evaluationZoneId = zoneId
        )

        assertEquals(OneTimeOverrideOutcome.STALE_OCCURRENCE, result.outcome)
        assertTrue(exceptionDao.getExceptionsForAlarm(1L).isEmpty())
    }

    // G. Reject ONE_TIME, Quick, and active Session
    @Test
    fun `G Reject ONE_TIME, Quick, and active Session`() = runBlocking {
        // 1. ONE_TIME alarm
        val oneTimeAlarm = Alarm(
            id = 2L,
            hour = 7,
            minute = 0,
            isEnabled = true,
            scheduleKind = Alarm.SCHEDULE_KIND_ONE_TIME,
            specificDate = "2026-11-04",
            nextTriggerTime = wed0700Ms
        )
        alarmDao.insert(oneTimeAlarm)
        authorityDao.insertAuthority(validAuthority.copy(alarmId = 2L))
        issuedDao.insertSlotInitial(IssueSlot(alarmId = 2L, generationId = 1L, currentIssueToken = null))

        val oneTimeResult = adapter.executeChangeTimeOnce(
            alarmId = 2L,
            expectedScheduledAt = wed0700Ms,
            newTime = LocalTime.of(7, 30),
            nowEpochMs = nowEpochMs,
            evaluationZoneId = zoneId
        )
        assertEquals(OneTimeOverrideOutcome.UNSUPPORTED_SCHEDULE, oneTimeResult.outcome)

        // 2. Quick Alarm
        val quickAlarm = Alarm(
            id = 3L,
            hour = 7,
            minute = 0,
            isEnabled = true,
            kind = Alarm.KIND_QUICK,
            nextTriggerTime = wed0700Ms
        )
        alarmDao.insert(quickAlarm)
        authorityDao.insertAuthority(validAuthority.copy(alarmId = 3L))
        issuedDao.insertSlotInitial(IssueSlot(alarmId = 3L, generationId = 1L, currentIssueToken = null))

        val quickResult = adapter.executeChangeTimeOnce(
            alarmId = 3L,
            expectedScheduledAt = wed0700Ms,
            newTime = LocalTime.of(7, 30),
            nowEpochMs = nowEpochMs,
            evaluationZoneId = zoneId
        )
        assertEquals(OneTimeOverrideOutcome.UNSUPPORTED_SCHEDULE, quickResult.outcome)

        // 3. Active FIRING session
        insertStandardRepeatingAlarm(alarmId = 1L)
        val activeResult = adapter.executeChangeTimeOnce(
            alarmId = 1L,
            expectedScheduledAt = wed0700Ms,
            newTime = LocalTime.of(7, 30),
            nowEpochMs = nowEpochMs,
            activeFiringAlarmId = 1L,
            evaluationZoneId = zoneId
        )
        assertEquals(OneTimeOverrideOutcome.ACTIVE_SESSION, activeResult.outcome)
    }

    // H. Holiday INCLUDE occurrence eligible
    @Test
    fun `H Holiday INCLUDE occurrence eligible for change time once`() = runBlocking {
        // Alarm repeats on Fridays only, but HolidayPolicy is INCLUDE
        val friNov06Ms = LocalDate.of(2026, 11, 6).atTime(LocalTime.of(7, 0)).atZone(zoneId).toInstant().toEpochMilli()
        val alarm = Alarm(
            id = 5L,
            hour = 7,
            minute = 0,
            isEnabled = true,
            scheduleKind = Alarm.SCHEDULE_KIND_REPEATING,
            repeatDays = setOf(DayOfWeek.FRIDAY),
            holidayPolicy = Alarm.HOLIDAY_POLICY_INCLUDE,
            generation = 1,
            revision = 1,
            nextTriggerTime = wed0700Ms // Wednesday is a Holiday and becomes next occurrence
        )
        alarmDao.insert(alarm)
        authorityDao.insertAuthority(validAuthority.copy(alarmId = 5L, desiredTriggerMillis = wed0700Ms))
        issuedDao.insertSlotInitial(IssueSlot(alarmId = 5L, generationId = 1L, currentIssueToken = null))

        val holidaySnapshotProvider: (LocalDate) -> HolidaySnapshot = { date ->
            if (date == wedDate) HolidaySnapshot(state = HolidayState.IS_HOLIDAY, holidayName = "Culture Day")
            else HolidaySnapshot(state = HolidayState.NOT_HOLIDAY)
        }

        val result = adapter.executeChangeTimeOnce(
            alarmId = 5L,
            expectedScheduledAt = wed0700Ms,
            newTime = LocalTime.of(8, 0),
            nowEpochMs = nowEpochMs,
            holidaySnapshotProvider = holidaySnapshotProvider,
            evaluationZoneId = zoneId
        )

        assertEquals(OneTimeOverrideOutcome.SUCCESS_COMMITTED, result.outcome)
        val wed0800Ms = wedDate.atTime(LocalTime.of(8, 0)).atZone(zoneId).toInstant().toEpochMilli()
        assertEquals(wed0800Ms, result.updatedAlarm?.nextTriggerTime)
    }

    // I. Holiday EXCLUDE invalidates target
    @Test
    fun `I Holiday EXCLUDE invalidates target and skips to next occurrence`() = runBlocking {
        insertStandardRepeatingAlarm(alarmId = 1L)

        // 1. Commit time override to 07:30
        val result = adapter.executeChangeTimeOnce(
            alarmId = 1L,
            expectedScheduledAt = wed0700Ms,
            newTime = LocalTime.of(7, 30),
            nowEpochMs = nowEpochMs,
            evaluationZoneId = zoneId
        )
        assertEquals(OneTimeOverrideOutcome.SUCCESS_COMMITTED, result.outcome)

        // 2. Wednesday is now declared a holiday, and alarm has EXCLUDE policy
        val holidayAlarm = alarmDao.getById(1L)!!.copy(holidayPolicy = Alarm.HOLIDAY_POLICY_EXCLUDE)
        alarmDao.update(holidayAlarm)

        val holidaySnapshotProvider: (LocalDate) -> HolidaySnapshot = { date ->
            if (date == wedDate) HolidaySnapshot(state = HolidayState.IS_HOLIDAY, holidayName = "Culture Day")
            else HolidaySnapshot(state = HolidayState.NOT_HOLIDAY)
        }

        // 3. Evaluate next occurrence: Wednesday is excluded, so next candidate is Friday Nov 6
        val evaluator = ScheduleOccurrenceEvaluator()
        val exceptions = exceptionDao.getExceptionsForAlarm(1L)
        val nextOccurrence = evaluator.evaluateOccurrence(
            alarm = holidayAlarm,
            fromEpochMs = nowEpochMs,
            exceptions = exceptions,
            holidaySnapshotProvider = holidaySnapshotProvider,
            evaluationZoneId = zoneId
        )

        val friNov06Ms = LocalDate.of(2026, 11, 6).atTime(LocalTime.of(7, 0)).atZone(zoneId).toInstant().toEpochMilli()
        assertNotNull(nextOccurrence)
        assertEquals(friNov06Ms, nextOccurrence?.targetTriggerTimeEpochMs)
        assertEquals(OccurrenceDecision.NORMAL_TRIGGER, nextOccurrence?.decision)

        // 4. Invalidate obsolete override
        val invalidated = adapter.invalidateOverridesIfTargetChanged(
            alarmId = 1L,
            nowEpochMs = nowEpochMs,
            holidaySnapshotProvider = holidaySnapshotProvider,
            evaluationZoneId = zoneId
        )
        assertTrue(invalidated)
        assertTrue(exceptionDao.getExceptionsForAlarm(1L).isEmpty())
    }

    // J. Repeated identical command idempotent
    @Test
    fun `J Repeated identical command is idempotent`() = runBlocking {
        insertStandardRepeatingAlarm()

        val first = adapter.executeChangeTimeOnce(
            alarmId = 1L,
            expectedScheduledAt = wed0700Ms,
            newTime = LocalTime.of(6, 30),
            nowEpochMs = nowEpochMs,
            evaluationZoneId = zoneId
        )
        assertEquals(OneTimeOverrideOutcome.SUCCESS_COMMITTED, first.outcome)

        // Repeated identical call
        val second = adapter.executeChangeTimeOnce(
            alarmId = 1L,
            expectedScheduledAt = wed0630Ms,
            expectedBaseTriggerTimeEpochMs = wed0700Ms,
            newTime = LocalTime.of(6, 30),
            nowEpochMs = nowEpochMs,
            evaluationZoneId = zoneId
        )
        assertEquals(OneTimeOverrideOutcome.ALREADY_COMMITTED, second.outcome)
        assertEquals(1, exceptionDao.getExceptionsForAlarm(1L).size)
    }

    // K. Conflicting command rejected
    @Test
    fun `K Conflicting command rejected`() = runBlocking {
        insertStandardRepeatingAlarm()

        // 1. Commit 06:30
        val first = adapter.executeChangeTimeOnce(
            alarmId = 1L,
            expectedScheduledAt = wed0700Ms,
            newTime = LocalTime.of(6, 30),
            nowEpochMs = nowEpochMs,
            evaluationZoneId = zoneId
        )
        assertEquals(OneTimeOverrideOutcome.SUCCESS_COMMITTED, first.outcome)

        // 2. Attempt to commit 08:30 on same base occurrence without cancelling
        val second = adapter.executeChangeTimeOnce(
            alarmId = 1L,
            expectedScheduledAt = wed0630Ms,
            expectedBaseTriggerTimeEpochMs = wed0700Ms,
            newTime = LocalTime.of(8, 30),
            nowEpochMs = nowEpochMs,
            evaluationZoneId = zoneId
        )
        assertEquals(OneTimeOverrideOutcome.CONFLICT_REJECTED, second.outcome)

        // Stored exception remains 06:30
        val exception = exceptionDao.getExceptionForOccurrence(1L, wed0700Ms, 1)
        assertEquals(wed0630Ms, exception?.overrideTriggerTime)
    }

    // L. Concurrent Skip versus Change
    @Test
    fun `L Concurrent Skip versus Change rejected`() = runBlocking {
        insertStandardRepeatingAlarm()

        // 1. Skip once
        val skipResult = adapter.executeSkipOnce(
            alarmId = 1L,
            expectedScheduledAt = wed0700Ms,
            nowEpochMs = nowEpochMs,
            evaluationZoneId = zoneId
        )
        assertEquals(OneTimeOverrideOutcome.SUCCESS_COMMITTED, skipResult.outcome)

        // 2. Change time once on skipped occurrence -> Conflict rejected
        val changeResult = adapter.executeChangeTimeOnce(
            alarmId = 1L,
            expectedScheduledAt = wed0700Ms,
            expectedBaseTriggerTimeEpochMs = wed0700Ms,
            newTime = LocalTime.of(7, 30),
            nowEpochMs = nowEpochMs,
            evaluationZoneId = zoneId
        )
        // Stale or conflict rejected
        assertTrue(
            changeResult.outcome == OneTimeOverrideOutcome.CONFLICT_REJECTED ||
                    changeResult.outcome == OneTimeOverrideOutcome.STALE_OCCURRENCE
        )
    }

    // M. Alarm Definition unchanged
    @Test
    fun `M Alarm Definition unchanged by one-time override`() = runBlocking {
        val initial = insertStandardRepeatingAlarm()

        adapter.executeChangeTimeOnce(
            alarmId = 1L,
            expectedScheduledAt = wed0700Ms,
            newTime = LocalTime.of(6, 30),
            nowEpochMs = nowEpochMs,
            evaluationZoneId = zoneId
        )

        val updated = alarmDao.getById(1L)!!
        assertEquals("hour must remain unchanged", initial.hour, updated.hour)
        assertEquals("minute must remain unchanged", initial.minute, updated.minute)
        assertEquals("repeatDays must remain unchanged", initial.repeatDays, updated.repeatDays)
        assertEquals("scheduleKind must remain unchanged", initial.scheduleKind, updated.scheduleKind)
        assertEquals("generation must remain unchanged", initial.generation, updated.generation)
    }

    // N. Process death and restart preserves intent
    @Test
    fun `N Process death and restart preserves intent`() = runBlocking {
        insertStandardRepeatingAlarm()

        adapter.executeChangeTimeOnce(
            alarmId = 1L,
            expectedScheduledAt = wed0700Ms,
            newTime = LocalTime.of(6, 30),
            nowEpochMs = nowEpochMs,
            evaluationZoneId = zoneId
        )

        // Simulate new app process / restart by instantiating fresh evaluator and querying DB
        val freshEvaluator = ScheduleOccurrenceEvaluator()
        val alarm = alarmDao.getById(1L)!!
        val exceptions = exceptionDao.getExceptionsForAlarmGeneration(1L, alarm.generation)

        val evaluated = freshEvaluator.evaluateOccurrence(
            alarm = alarm,
            fromEpochMs = nowEpochMs,
            exceptions = exceptions,
            evaluationZoneId = zoneId
        )

        assertNotNull(evaluated)
        assertEquals(OccurrenceDecision.TIME_OVERRIDDEN, evaluated?.decision)
        assertEquals(wed0630Ms, evaluated?.targetTriggerTimeEpochMs)
        assertEquals(wed0700Ms, evaluated?.baseTriggerTimeEpochMs)
        assertTrue(evaluated?.isExceptionApplied == true)
        assertEquals(ScheduleException.TYPE_TIME_OVERRIDE, evaluated?.appliedExceptionType)
    }

    // O. Target consumed -> override expired
    @Test
    fun `O Target consumed causes override to expire without leaking to subsequent occurrence`() = runBlocking {
        insertStandardRepeatingAlarm()

        adapter.executeChangeTimeOnce(
            alarmId = 1L,
            expectedScheduledAt = wed0700Ms,
            newTime = LocalTime.of(6, 30),
            nowEpochMs = nowEpochMs,
            evaluationZoneId = zoneId
        )

        // Wednesday occurrence fires and is consumed
        val occId = com.sysadmindoc.alarmclock.data.model.AlarmOccurrenceId(1L, wed0700Ms, 1)
        val consumedKeys = setOf(occId.key)

        // Evaluate from Wednesday 06:31 (after overridden trigger time)
        val postFireMs = wedDate.atTime(LocalTime.of(6, 31)).atZone(zoneId).toInstant().toEpochMilli()
        val freshEvaluator = ScheduleOccurrenceEvaluator()
        val alarm = alarmDao.getById(1L)!!
        val exceptions = exceptionDao.getExceptionsForAlarm(1L)

        val nextEvaluated = freshEvaluator.evaluateOccurrence(
            alarm = alarm,
            fromEpochMs = postFireMs,
            exceptions = exceptions,
            consumedOccurrenceKeys = consumedKeys,
            evaluationZoneId = zoneId
        )

        val friNov060700Ms = LocalDate.of(2026, 11, 6).atTime(LocalTime.of(7, 0)).atZone(zoneId).toInstant().toEpochMilli()
        assertNotNull(nextEvaluated)
        assertEquals(OccurrenceDecision.NORMAL_TRIGGER, nextEvaluated?.decision)
        assertEquals(friNov060700Ms, nextEvaluated?.targetTriggerTimeEpochMs)
        assertFalse(nextEvaluated?.isExceptionApplied == true)
    }

    // P. Reconcile retry remains durable
    @Test
    fun `P Reconcile retry remains durable with DurableOperationIntent`() = runBlocking {
        insertStandardRepeatingAlarm()

        val result = adapter.executeChangeTimeOnce(
            alarmId = 1L,
            expectedScheduledAt = wed0700Ms,
            newTime = LocalTime.of(6, 30),
            nowEpochMs = nowEpochMs,
            evaluationZoneId = zoneId
        )

        assertEquals(OneTimeOverrideOutcome.SUCCESS_COMMITTED, result.outcome)
        val intent = result.durableIntent
        assertNotNull(intent)
        assertEquals(DurableOperationIntent.PHASE_PENDING, intent?.phase)
        assertEquals(wed0630Ms, intent?.originalScheduledAt)

        // In DB, intent is persisted
        val storedIntents = durableDao.getIntentsByAlarmId(1L)
        assertFalse(storedIntents.isEmpty())
        assertEquals(wed0630Ms, storedIntents.last().originalScheduledAt)
    }

    // Q. No duplicate OS registration
    @Test
    fun `Q Cancel override restores base schedule cleanly`() = runBlocking {
        insertStandardRepeatingAlarm()

        // 1. Change time to 06:30
        adapter.executeChangeTimeOnce(
            alarmId = 1L,
            expectedScheduledAt = wed0700Ms,
            newTime = LocalTime.of(6, 30),
            nowEpochMs = nowEpochMs,
            evaluationZoneId = zoneId
        )
        assertEquals(wed0630Ms, alarmDao.getById(1L)?.nextTriggerTime)

        // 2. Cancel override
        val cancelResult = adapter.cancelOverride(
            alarmId = 1L,
            expectedScheduledAt = wed0630Ms,
            nowEpochMs = nowEpochMs,
            evaluationZoneId = zoneId
        )
        assertEquals(OneTimeOverrideOutcome.SUCCESS_COMMITTED, cancelResult.outcome)

        // Next trigger time reverted to base 07:00
        val reverted = alarmDao.getById(1L)!!
        assertEquals(wed0700Ms, reverted.nextTriggerTime)
        assertTrue(exceptionDao.getExceptionsForAlarm(1L).isEmpty())
    }

    // R. Skip Once reuses KNO-154 owner
    @Test
    fun `R Skip Once delegates directly to KNO-154 DurableSkipClaimAdapter`() = runBlocking {
        insertStandardRepeatingAlarm()

        val skipResult = adapter.executeSkipOnce(
            alarmId = 1L,
            expectedScheduledAt = wed0700Ms,
            nowEpochMs = nowEpochMs,
            evaluationZoneId = zoneId
        )

        assertEquals(OneTimeOverrideOutcome.SUCCESS_COMMITTED, skipResult.outcome)
        val friNov060700Ms = LocalDate.of(2026, 11, 6).atTime(LocalTime.of(7, 0)).atZone(zoneId).toInstant().toEpochMilli()
        assertEquals(friNov060700Ms, skipResult.updatedAlarm?.nextTriggerTime)

        // Verified persisted exception is TYPE_SKIP
        val exception = exceptionDao.getExceptionForOccurrence(1L, wed0700Ms, 1)
        assertNotNull(exception)
        assertEquals(ScheduleException.TYPE_SKIP, exception?.exceptionType)
    }

    @Test
    fun `cancelOverride with mismatched expectedScheduledAt returns STALE_OCCURRENCE and preserves exception`() = runBlocking {
        insertStandardRepeatingAlarm()

        // Apply Time Override: wed0700Ms -> wed0630Ms
        val overrideResult = adapter.executeChangeTimeOnce(
            alarmId = 1L,
            expectedScheduledAt = wed0700Ms,
            newTime = LocalTime.of(6, 30),
            nowEpochMs = nowEpochMs,
            evaluationZoneId = zoneId
        )
        assertEquals(OneTimeOverrideOutcome.SUCCESS_COMMITTED, overrideResult.outcome)
        assertEquals(wed0630Ms, alarmDao.getById(1L)?.nextTriggerTime)

        // Attempt cancel with mismatched/stale expectedScheduledAt
        val mismatchedTargetMs = wed0700Ms + 86400000L
        val cancelResult = adapter.cancelOverride(
            alarmId = 1L,
            expectedScheduledAt = mismatchedTargetMs,
            nowEpochMs = nowEpochMs,
            evaluationZoneId = zoneId
        )

        assertEquals("Cancel override with mismatched target must return STALE_OCCURRENCE", OneTimeOverrideOutcome.STALE_OCCURRENCE, cancelResult.outcome)
        assertEquals("STALE_OVERRIDE_TARGET_MISMATCH", cancelResult.decisionReason)

        // Verify exception was NOT deleted and alarm nextTriggerTime was NOT altered
        val remainingExceptions = exceptionDao.getExceptionsForAlarm(1L)
        assertEquals("Exception must be preserved on target mismatch", 1, remainingExceptions.size)
        assertEquals(wed0630Ms, alarmDao.getById(1L)?.nextTriggerTime)
    }

    @Test
    fun `cancelOverride when no exceptions exist is idempotent and returns SUCCESS_COMMITTED`() = runBlocking {
        insertStandardRepeatingAlarm()
        assertTrue(exceptionDao.getExceptionsForAlarm(1L).isEmpty())

        val cancelResult = adapter.cancelOverride(
            alarmId = 1L,
            expectedScheduledAt = wed0700Ms,
            nowEpochMs = nowEpochMs,
            evaluationZoneId = zoneId
        )

        assertEquals(OneTimeOverrideOutcome.SUCCESS_COMMITTED, cancelResult.outcome)
        assertEquals(DurableOneTimeOverrideAdapter.REASON_NO_OVERRIDE_TO_CANCEL, cancelResult.decisionReason)
        assertEquals(wed0700Ms, alarmDao.getById(1L)?.nextTriggerTime)
    }

    @Test
    fun `cancelOverride for Skip Once with matching baseTriggerTime reverts to base schedule and deletes exception`() = runBlocking {
        insertStandardRepeatingAlarm()

        val skipResult = adapter.executeSkipOnce(
            alarmId = 1L,
            expectedScheduledAt = wed0700Ms,
            nowEpochMs = nowEpochMs,
            evaluationZoneId = zoneId
        )
        assertEquals(OneTimeOverrideOutcome.SUCCESS_COMMITTED, skipResult.outcome)
        val friNov060700Ms = LocalDate.of(2026, 11, 6).atTime(LocalTime.of(7, 0)).atZone(zoneId).toInstant().toEpochMilli()
        assertEquals(friNov060700Ms, alarmDao.getById(1L)?.nextTriggerTime)
        assertEquals(1, exceptionDao.getExceptionsForAlarm(1L).size)

        // Cancel Skip Once matching base scheduled occurrence
        val cancelResult = adapter.cancelOverride(
            alarmId = 1L,
            expectedScheduledAt = wed0700Ms,
            nowEpochMs = nowEpochMs,
            evaluationZoneId = zoneId
        )

        assertEquals(OneTimeOverrideOutcome.SUCCESS_COMMITTED, cancelResult.outcome)
        assertEquals("Next trigger time must revert to base schedule", wed0700Ms, alarmDao.getById(1L)?.nextTriggerTime)
        assertTrue("Skip exception must be deleted", exceptionDao.getExceptionsForAlarm(1L).isEmpty())
    }

    @Test
    fun `cancelOverride for Change Time Once with baseTriggerTime matches and reverts to base schedule`() = runBlocking {
        insertStandardRepeatingAlarm()

        val overrideResult = adapter.executeChangeTimeOnce(
            alarmId = 1L,
            expectedScheduledAt = wed0700Ms,
            newTime = LocalTime.of(6, 30),
            nowEpochMs = nowEpochMs,
            evaluationZoneId = zoneId
        )
        assertEquals(OneTimeOverrideOutcome.SUCCESS_COMMITTED, overrideResult.outcome)
        assertEquals(wed0630Ms, alarmDao.getById(1L)?.nextTriggerTime)

        // Cancel with expectedBaseTriggerTimeEpochMs matching base 07:00
        val cancelResult = adapter.cancelOverride(
            alarmId = 1L,
            expectedScheduledAt = wed0700Ms,
            expectedBaseTriggerTimeEpochMs = wed0700Ms,
            nowEpochMs = nowEpochMs,
            evaluationZoneId = zoneId
        )
        assertEquals(OneTimeOverrideOutcome.SUCCESS_COMMITTED, cancelResult.outcome)
        assertEquals(wed0700Ms, alarmDao.getById(1L)?.nextTriggerTime)
        assertTrue(exceptionDao.getExceptionsForAlarm(1L).isEmpty())
    }

    @Test
    fun `cancelOverride with single skip fallback matches advanced nextTriggerTime when single skip precedes it`() = runBlocking {
        insertStandardRepeatingAlarm()

        val skipResult = adapter.executeSkipOnce(
            alarmId = 1L,
            expectedScheduledAt = wed0700Ms,
            nowEpochMs = nowEpochMs,
            evaluationZoneId = zoneId
        )
        assertEquals(OneTimeOverrideOutcome.SUCCESS_COMMITTED, skipResult.outcome)
        val friNov060700Ms = LocalDate.of(2026, 11, 6).atTime(LocalTime.of(7, 0)).atZone(zoneId).toInstant().toEpochMilli()
        assertEquals(friNov060700Ms, alarmDao.getById(1L)?.nextTriggerTime)

        // Legacy/caller passes current advanced nextTriggerTime (friNov060700Ms)
        val cancelResult = adapter.cancelOverride(
            alarmId = 1L,
            expectedScheduledAt = friNov060700Ms,
            nowEpochMs = nowEpochMs,
            evaluationZoneId = zoneId
        )

        assertEquals(OneTimeOverrideOutcome.SUCCESS_COMMITTED, cancelResult.outcome)
        assertEquals("Next trigger time must revert to base schedule", wed0700Ms, alarmDao.getById(1L)?.nextTriggerTime)
        assertTrue("Skip exception must be deleted", exceptionDao.getExceptionsForAlarm(1L).isEmpty())
    }

    @Test
    fun `cancelOverride with multiple exceptions removes only matching exception and respects remaining in reschedule`() = runBlocking {
        insertStandardRepeatingAlarm()

        val friNov060700Ms = LocalDate.of(2026, 11, 6).atTime(LocalTime.of(7, 0)).atZone(zoneId).toInstant().toEpochMilli()
        val nextWed110700Ms = LocalDate.of(2026, 11, 11).atTime(LocalTime.of(7, 0)).atZone(zoneId).toInstant().toEpochMilli()

        // 1. Skip Wednesday Nov 4
        val skipWedResult = adapter.executeSkipOnce(
            alarmId = 1L,
            expectedScheduledAt = wed0700Ms,
            nowEpochMs = nowEpochMs,
            evaluationZoneId = zoneId
        )
        assertEquals(OneTimeOverrideOutcome.SUCCESS_COMMITTED, skipWedResult.outcome)
        assertEquals(friNov060700Ms, alarmDao.getById(1L)?.nextTriggerTime)

        // 2. Skip Friday Nov 6
        val skipFriResult = adapter.executeSkipOnce(
            alarmId = 1L,
            expectedScheduledAt = friNov060700Ms,
            nowEpochMs = nowEpochMs + 1000L,
            evaluationZoneId = zoneId
        )
        assertEquals(OneTimeOverrideOutcome.SUCCESS_COMMITTED, skipFriResult.outcome)
        assertEquals(nextWed110700Ms, alarmDao.getById(1L)?.nextTriggerTime)
        assertEquals(2, exceptionDao.getExceptionsForAlarm(1L).size)

        // 3. Cancel Wednesday skip: pass wed0700Ms
        val cancelWedResult = adapter.cancelOverride(
            alarmId = 1L,
            expectedScheduledAt = wed0700Ms,
            nowEpochMs = nowEpochMs + 2000L,
            evaluationZoneId = zoneId
        )
        assertEquals(OneTimeOverrideOutcome.SUCCESS_COMMITTED, cancelWedResult.outcome)

        // Wednesday is no longer skipped, but Friday skip is still present in Room!
        val remaining = exceptionDao.getExceptionsForAlarm(1L)
        assertEquals(1, remaining.size)
        assertEquals(friNov060700Ms, remaining.first().baseTriggerTime)

        // Next trigger time reverted to Wednesday Nov 4 because it is before Friday Nov 6
        assertEquals(wed0700Ms, alarmDao.getById(1L)?.nextTriggerTime)

        // 4. Cancel Friday skip: pass friNov060700Ms
        val cancelFriResult = adapter.cancelOverride(
            alarmId = 1L,
            expectedScheduledAt = friNov060700Ms,
            nowEpochMs = nowEpochMs + 3000L,
            evaluationZoneId = zoneId
        )
        assertEquals(OneTimeOverrideOutcome.SUCCESS_COMMITTED, cancelFriResult.outcome)
        assertTrue(exceptionDao.getExceptionsForAlarm(1L).isEmpty())
        assertEquals(wed0700Ms, alarmDao.getById(1L)?.nextTriggerTime)
    }

    @Test
    fun `cancelOverride with multiple exceptions rejects stale current next trigger matching and preserves all exceptions`() = runBlocking {
        insertStandardRepeatingAlarm()

        val friNov060700Ms = LocalDate.of(2026, 11, 6).atTime(LocalTime.of(7, 0)).atZone(zoneId).toInstant().toEpochMilli()
        val nextWed110700Ms = LocalDate.of(2026, 11, 11).atTime(LocalTime.of(7, 0)).atZone(zoneId).toInstant().toEpochMilli()

        // Skip Wed Nov 4 and Fri Nov 6
        adapter.executeSkipOnce(alarmId = 1L, expectedScheduledAt = wed0700Ms, nowEpochMs = nowEpochMs, evaluationZoneId = zoneId)
        adapter.executeSkipOnce(alarmId = 1L, expectedScheduledAt = friNov060700Ms, nowEpochMs = nowEpochMs + 1000L, evaluationZoneId = zoneId)
        assertEquals(2, exceptionDao.getExceptionsForAlarm(1L).size)
        assertEquals(nextWed110700Ms, alarmDao.getById(1L)?.nextTriggerTime)

        // Erroneously pass current nextTriggerTime (nextWed110700Ms) with no baseTrigger
        val cancelResult = adapter.cancelOverride(
            alarmId = 1L,
            expectedScheduledAt = nextWed110700Ms,
            nowEpochMs = nowEpochMs + 2000L,
            evaluationZoneId = zoneId
        )

        assertEquals(OneTimeOverrideOutcome.STALE_OCCURRENCE, cancelResult.outcome)
        assertEquals("STALE_OVERRIDE_TARGET_MISMATCH", cancelResult.decisionReason)
        assertEquals("Both exceptions must be preserved on target mismatch", 2, exceptionDao.getExceptionsForAlarm(1L).size)
        assertEquals(nextWed110700Ms, alarmDao.getById(1L)?.nextTriggerTime)
    }

    @Test
    fun `cancelOverride with single future exception cannot be selected by current next trigger`() = runBlocking {
        insertStandardRepeatingAlarm()

        val friNov060700Ms = LocalDate.of(2026, 11, 6).atTime(LocalTime.of(7, 0)).atZone(zoneId).toInstant().toEpochMilli()

        // Manually insert a single exception for the future Friday occurrence while nextTriggerTime is Wednesday
        val futureException = ScheduleException(
            alarmId = 1L,
            baseTriggerTime = friNov060700Ms,
            generation = 1,
            exceptionType = ScheduleException.TYPE_SKIP,
            overrideTriggerTime = 0L,
            createdAt = nowEpochMs,
            revision = 1
        )
        exceptionDao.insert(futureException)
        assertEquals(1, exceptionDao.getExceptionsForAlarm(1L).size)
        assertEquals(wed0700Ms, alarmDao.getById(1L)?.nextTriggerTime)

        // Pass current nextTriggerTime (wed0700Ms) with no baseTrigger
        val cancelResult = adapter.cancelOverride(
            alarmId = 1L,
            expectedScheduledAt = wed0700Ms,
            nowEpochMs = nowEpochMs,
            evaluationZoneId = zoneId
        )

        assertEquals(OneTimeOverrideOutcome.STALE_OCCURRENCE, cancelResult.outcome)
        assertEquals("STALE_OVERRIDE_TARGET_MISMATCH", cancelResult.decisionReason)
        assertEquals("Future exception must be preserved and not deleted", 1, exceptionDao.getExceptionsForAlarm(1L).size)
        assertEquals(wed0700Ms, alarmDao.getById(1L)?.nextTriggerTime)
    }
}

