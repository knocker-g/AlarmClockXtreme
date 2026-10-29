package com.sysadmindoc.alarmclock.domain.evaluator

import com.sysadmindoc.alarmclock.data.local.entity.ScheduleAuthority
import com.sysadmindoc.alarmclock.data.local.entity.ScheduleException
import com.sysadmindoc.alarmclock.data.model.Alarm
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

class NormalScheduleReconcilePlannerTest {

    private val planner = NormalScheduleReconcilePlanner()
    private val zoneId = ZoneId.of("America/New_York")
    private val nowEpochMs = LocalDate.of(2026, 10, 28)
        .atTime(LocalTime.of(6, 0))
        .atZone(zoneId)
        .toInstant()
        .toEpochMilli()

    private val wed0700Ms = LocalDate.of(2026, 10, 28)
        .atTime(LocalTime.of(7, 0))
        .atZone(zoneId)
        .toInstant()
        .toEpochMilli()

    private val nov040700Ms = LocalDate.of(2026, 11, 4)
        .atTime(LocalTime.of(7, 0))
        .atZone(zoneId)
        .toInstant()
        .toEpochMilli()

    private val validAuthority = ScheduleAuthority(
        alarmId = 1L,
        instanceToken = "auth-token-1",
        definitionRevision = 1L,
        scheduleRevision = 1L,
        lifecycle = "LIVE",
        desiredOperation = "SET",
        desiredTriggerMillis = wed0700Ms,
        reconcileState = "SETTLED",
        restoreEpoch = 100L,
        actionKey = null
    )

    private val validFingerprint = ScheduleAuthorityFingerprint.fromAuthority(validAuthority)

    @Test
    fun `1 対象SKIPが存在しないReconcile`() {
        val alarm = Alarm(
            id = 1L,
            hour = 7,
            minute = 0,
            isEnabled = true,
            scheduleKind = Alarm.SCHEDULE_KIND_REPEATING,
            repeatDays = setOf(DayOfWeek.WEDNESDAY),
            generation = 1,
            revision = 1,
            nextTriggerTime = wed0700Ms
        )

        val decision = planner.planReconcile(
            alarm = alarm,
            storedAuthority = validAuthority,
            exceptions = emptyList(), // No active skip in DB
            nowEpochMs = nowEpochMs,
            expectedAuthorityFingerprint = validFingerprint,
            evaluationZoneId = zoneId
        )

        assertEquals("Reconcile without active TYPE_SKIP MUST return NO_ACTIVE_SKIP_EXCEPTION", ReconcileOutcome.NO_ACTIVE_SKIP_EXCEPTION, decision.outcome)
        assertFalse(decision.isOsRescheduleCandidate)
    }

    @Test
    fun `2 旧GenerationのSKIP`() {
        val alarmGen2 = Alarm(
            id = 1L,
            hour = 7,
            minute = 0,
            isEnabled = true,
            scheduleKind = Alarm.SCHEDULE_KIND_REPEATING,
            repeatDays = setOf(DayOfWeek.WEDNESDAY),
            generation = 2, // Active generation 2
            revision = 1,
            nextTriggerTime = wed0700Ms
        )

        val staleGen1Exception = ScheduleException(
            alarmId = 1L,
            baseTriggerTime = wed0700Ms,
            exceptionType = ScheduleException.TYPE_SKIP,
            generation = 1 // Exception from gen 1
        )

        val decision = planner.planReconcile(
            alarm = alarmGen2,
            storedAuthority = validAuthority,
            exceptions = listOf(staleGen1Exception),
            nowEpochMs = nowEpochMs,
            expectedAuthorityFingerprint = validFingerprint,
            evaluationZoneId = zoneId
        )

        assertEquals("Stale generation skip MUST return NO_ACTIVE_SKIP_EXCEPTION", ReconcileOutcome.NO_ACTIVE_SKIP_EXCEPTION, decision.outcome)
        assertFalse(decision.isOsRescheduleCandidate)
    }

    @Test
    fun `3 非発火Evaluator結果`() = runBlocking {
        val disabledAlarm = Alarm(id = 1L, hour = 7, minute = 0, isEnabled = false)
        val decisionDisabled = planner.planReconcile(disabledAlarm, validAuthority, emptyList(), nowEpochMs, expectedAuthorityFingerprint = validFingerprint)
        assertEquals(ReconcileOutcome.UNSUPPORTED_SCHEDULE, decisionDisabled.outcome)

        val quickAlarm = Alarm(id = 2L, hour = 7, minute = 0, kind = Alarm.KIND_QUICK, isEnabled = true)
        val decisionQuick = planner.planReconcile(quickAlarm, validAuthority, emptyList(), nowEpochMs, expectedAuthorityFingerprint = validFingerprint)
        assertEquals(ReconcileOutcome.UNSUPPORTED_SCHEDULE, decisionQuick.outcome)
    }

    @Test
    fun `4 Reconcile fingerprint欠損`() {
        val alarm = Alarm(
            id = 1L,
            hour = 7,
            minute = 0,
            isEnabled = true,
            scheduleKind = Alarm.SCHEDULE_KIND_REPEATING,
            repeatDays = setOf(DayOfWeek.WEDNESDAY),
            generation = 1,
            revision = 1,
            nextTriggerTime = wed0700Ms
        )

        val decision = planner.planReconcile(
            alarm = alarm,
            storedAuthority = validAuthority,
            exceptions = emptyList(),
            nowEpochMs = nowEpochMs,
            expectedAuthorityFingerprint = null, // Missing expectedAuthorityFingerprint
            evaluationZoneId = zoneId
        )

        assertEquals("Missing expectedAuthorityFingerprint MUST fail-close as MISSING_AUTHORITY", ReconcileOutcome.MISSING_AUTHORITY, decision.outcome)
        assertFalse(decision.isOsRescheduleCandidate)
    }

    @Test
    fun `5 保存済みAuthorityのAlarm ID不一致`() {
        val alarm = Alarm(
            id = 1L,
            hour = 7,
            minute = 0,
            isEnabled = true,
            scheduleKind = Alarm.SCHEDULE_KIND_REPEATING,
            repeatDays = setOf(DayOfWeek.WEDNESDAY),
            generation = 1,
            revision = 1,
            nextTriggerTime = wed0700Ms
        )

        val mismatchedAuthority = validAuthority.copy(alarmId = 99L) // Mismatched authority alarmId

        val decision = planner.planReconcile(
            alarm = alarm,
            storedAuthority = mismatchedAuthority,
            exceptions = emptyList(),
            nowEpochMs = nowEpochMs,
            expectedAuthorityFingerprint = validFingerprint,
            evaluationZoneId = zoneId
        )

        assertEquals("Mismatched authority alarmId MUST fail-close as MISSING_AUTHORITY", ReconcileOutcome.MISSING_AUTHORITY, decision.outcome)
        assertFalse(decision.isOsRescheduleCandidate)
    }

    @Test
    fun `6 Evaluatorが候補を返さない`() {
        val expiredOneTime = Alarm(
            id = 1L,
            hour = 7,
            minute = 0,
            isEnabled = true,
            scheduleKind = Alarm.SCHEDULE_KIND_ONE_TIME,
            specificDate = "2026-10-25"
        )

        val skipException = ScheduleException(
            alarmId = 1L,
            baseTriggerTime = wed0700Ms,
            exceptionType = ScheduleException.TYPE_SKIP,
            generation = 1
        )

        val decision = planner.planReconcile(
            alarm = expiredOneTime,
            storedAuthority = validAuthority,
            exceptions = listOf(skipException),
            nowEpochMs = nowEpochMs,
            expectedAuthorityFingerprint = validFingerprint,
            evaluationZoneId = zoneId
        )

        assertEquals(ReconcileOutcome.NO_VALID_CANDIDATE_OCCURRENCE, decision.outcome)
        assertFalse(decision.isOsRescheduleCandidate)
    }

    @Test
    fun `7 projection一致だがOS状態は不明`() {
        val alarm = Alarm(
            id = 1L,
            hour = 7,
            minute = 0,
            isEnabled = true,
            scheduleKind = Alarm.SCHEDULE_KIND_REPEATING,
            repeatDays = setOf(DayOfWeek.WEDNESDAY),
            generation = 1,
            revision = 1,
            nextTriggerTime = nov040700Ms // Stored projection matches post-skip target
        )

        val skipException = ScheduleException(
            alarmId = 1L,
            baseTriggerTime = wed0700Ms,
            exceptionType = ScheduleException.TYPE_SKIP,
            generation = 1
        )

        val decision = planner.planReconcile(
            alarm = alarm,
            storedAuthority = validAuthority,
            exceptions = listOf(skipException),
            nowEpochMs = nowEpochMs,
            expectedAuthorityFingerprint = validFingerprint,
            evaluationZoneId = zoneId
        )

        assertEquals("Projection match MUST return PROJECTION_UPDATED_UNVERIFIED_OS", ReconcileOutcome.PROJECTION_UPDATED_UNVERIFIED_OS, decision.outcome)
        assertFalse(decision.isOsRescheduleCandidate)
        assertEquals(nov040700Ms, decision.evaluatedPostSkipNextTargetEpochMs)
    }

    @Test
    fun `8 永続化済みSKIP＋古いprojection`() {
        val alarm = Alarm(
            id = 1L,
            hour = 7,
            minute = 0,
            isEnabled = true,
            scheduleKind = Alarm.SCHEDULE_KIND_REPEATING,
            repeatDays = setOf(DayOfWeek.WEDNESDAY),
            generation = 1,
            revision = 1,
            nextTriggerTime = wed0700Ms // Stored projection points to old pre-skip trigger
        )

        val skipException = ScheduleException(
            alarmId = 1L,
            baseTriggerTime = wed0700Ms,
            exceptionType = ScheduleException.TYPE_SKIP,
            generation = 1
        )

        val decision = planner.planReconcile(
            alarm = alarm,
            storedAuthority = validAuthority,
            exceptions = listOf(skipException),
            nowEpochMs = nowEpochMs,
            expectedAuthorityFingerprint = validFingerprint,
            evaluationZoneId = zoneId
        )

        assertEquals("Old projection diff MUST return PROJECTION_DIFFERS_RECONCILE_REQUIRED", ReconcileOutcome.PROJECTION_DIFFERS_RECONCILE_REQUIRED, decision.outcome)
        assertTrue(decision.isOsRescheduleCandidate)
        assertEquals(nov040700Ms, decision.evaluatedPostSkipNextTargetEpochMs)
    }

    @Test
    fun `9 Holiday UNKNOWNで必要な判断ができる場合`() {
        val holidayAlarm = Alarm(
            id = 1L,
            hour = 7,
            minute = 0,
            isEnabled = true,
            scheduleKind = Alarm.SCHEDULE_KIND_REPEATING,
            repeatDays = setOf(DayOfWeek.WEDNESDAY),
            skipOnHolidays = true,
            nextTriggerTime = wed0700Ms
        )

        val skipException = ScheduleException(
            alarmId = 1L,
            baseTriggerTime = wed0700Ms,
            exceptionType = ScheduleException.TYPE_SKIP,
            generation = 1
        )

        val decision = planner.planReconcile(
            alarm = holidayAlarm,
            storedAuthority = validAuthority,
            exceptions = listOf(skipException),
            nowEpochMs = nowEpochMs,
            expectedAuthorityFingerprint = validFingerprint,
            holidaySnapshotProvider = { HolidaySnapshot(state = HolidayState.UNKNOWN) },
            evaluationZoneId = zoneId
        )

        assertEquals(ReconcileOutcome.PROJECTION_DIFFERS_RECONCILE_REQUIRED, decision.outcome)
        assertEquals(nov040700Ms, decision.evaluatedPostSkipNextTargetEpochMs)
    }

    @Test
    fun `10 Personal HIDE-only`() {
        val solarAlarm = Alarm(
            id = 1L,
            hour = 7,
            minute = 0,
            isEnabled = true,
            scheduleKind = Alarm.SCHEDULE_KIND_REPEATING,
            repeatDays = setOf(DayOfWeek.WEDNESDAY),
            solarOffsetMinutes = 15
        )

        val decision = planner.planReconcile(
            alarm = solarAlarm,
            storedAuthority = validAuthority,
            exceptions = emptyList(),
            nowEpochMs = nowEpochMs
        )

        assertEquals(ReconcileOutcome.UNSUPPORTED_SCHEDULE, decision.outcome)
        assertFalse(decision.isOsRescheduleCandidate)
    }
}
