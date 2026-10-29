package com.sysadmindoc.alarmclock.domain.evaluator

import com.sysadmindoc.alarmclock.data.local.entity.ScheduleAuthority
import com.sysadmindoc.alarmclock.data.local.entity.ScheduleException
import com.sysadmindoc.alarmclock.data.model.Alarm
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

class CurrentNextDecisionPlannerTest {

    private val planner = CurrentNextDecisionPlanner()
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

    private val validAuthority = ScheduleAuthority(
        alarmId = 1L,
        instanceToken = "auth-token-42",
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
    fun `1 expectedGeneration欠損`() {
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

        val missingGenSnapshot = CurrentNextActionSnapshot(
            alarmId = 1L,
            expectedScheduledTargetEpochMs = wed0700Ms,
            expectedBaseTriggerTimeEpochMs = wed0700Ms,
            expectedGeneration = null,
            authorityFingerprint = validFingerprint
        )

        val decision = planner.planCurrentNext(
            alarm = alarm,
            actionSnapshot = missingGenSnapshot,
            nowEpochMs = nowEpochMs,
            storedAuthority = validAuthority,
            evaluationZoneId = zoneId
        )

        assertEquals("Missing expectedGeneration MUST fail-close as STALE_OCCURRENCE", CurrentNextDecisionOutcome.STALE_OCCURRENCE, decision.outcome)
        assertFalse(decision.isSkipActionEligible)
        assertEquals(CurrentNextDecisionPlanner.REASON_GENERATION_MISMATCH, decision.decisionReason)
    }

    @Test
    fun `2 expectedBaseTriggerTime欠損`() {
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

        val missingBaseSnapshot = CurrentNextActionSnapshot(
            alarmId = 1L,
            expectedScheduledTargetEpochMs = wed0700Ms,
            expectedBaseTriggerTimeEpochMs = null,
            expectedGeneration = 1,
            authorityFingerprint = validFingerprint
        )

        val decision = planner.planCurrentNext(
            alarm = alarm,
            actionSnapshot = missingBaseSnapshot,
            nowEpochMs = nowEpochMs,
            storedAuthority = validAuthority,
            evaluationZoneId = zoneId
        )

        assertEquals("Missing expectedBaseTriggerTime MUST fail-close as STALE_OCCURRENCE", CurrentNextDecisionOutcome.STALE_OCCURRENCE, decision.outcome)
        assertFalse(decision.isSkipActionEligible)
        assertEquals(CurrentNextDecisionPlanner.REASON_MISSING_EXPECTED_TARGET, decision.decisionReason)
    }

    @Test
    fun `3 authorityFingerprint欠損`() {
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

        val missingFpSnapshot = CurrentNextActionSnapshot(
            alarmId = 1L,
            expectedScheduledTargetEpochMs = wed0700Ms,
            expectedBaseTriggerTimeEpochMs = wed0700Ms,
            expectedGeneration = 1,
            authorityFingerprint = null
        )

        val decision = planner.planCurrentNext(
            alarm = alarm,
            actionSnapshot = missingFpSnapshot,
            nowEpochMs = nowEpochMs,
            storedAuthority = validAuthority,
            evaluationZoneId = zoneId
        )

        assertEquals("Missing authorityFingerprint MUST fail-close as STALE_OCCURRENCE", CurrentNextDecisionOutcome.STALE_OCCURRENCE, decision.outcome)
        assertFalse(decision.isSkipActionEligible)
        assertEquals(CurrentNextDecisionPlanner.REASON_AUTHORITY_MISMATCH, decision.decisionReason)
    }

    @Test
    fun `4 storedAuthority欠損`() {
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

        val snapshot = CurrentNextActionSnapshot(
            alarmId = 1L,
            expectedScheduledTargetEpochMs = wed0700Ms,
            expectedBaseTriggerTimeEpochMs = wed0700Ms,
            expectedGeneration = 1,
            authorityFingerprint = validFingerprint
        )

        val decision = planner.planCurrentNext(
            alarm = alarm,
            actionSnapshot = snapshot,
            nowEpochMs = nowEpochMs,
            storedAuthority = null,
            evaluationZoneId = zoneId
        )

        assertEquals("Missing storedAuthority MUST fail-close as STALE_OCCURRENCE", CurrentNextDecisionOutcome.STALE_OCCURRENCE, decision.outcome)
        assertFalse(decision.isSkipActionEligible)
        assertEquals(CurrentNextDecisionPlanner.REASON_AUTHORITY_MISMATCH, decision.decisionReason)
    }

    @Test
    fun `5 action alarmId不一致`() {
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

        val wrongAlarmIdSnapshot = CurrentNextActionSnapshot(
            alarmId = 99L,
            expectedScheduledTargetEpochMs = wed0700Ms,
            expectedBaseTriggerTimeEpochMs = wed0700Ms,
            expectedGeneration = 1,
            authorityFingerprint = validFingerprint
        )

        val decision = planner.planCurrentNext(
            alarm = alarm,
            actionSnapshot = wrongAlarmIdSnapshot,
            nowEpochMs = nowEpochMs,
            storedAuthority = validAuthority,
            evaluationZoneId = zoneId
        )

        assertEquals("Mismatched action alarmId MUST fail-close as STALE_OCCURRENCE", CurrentNextDecisionOutcome.STALE_OCCURRENCE, decision.outcome)
        assertFalse(decision.isSkipActionEligible)
        assertEquals(CurrentNextDecisionPlanner.REASON_ACTION_ALARM_ID_MISMATCH, decision.decisionReason)
    }

    @Test
    fun `6 Authority alarmId不一致`() {
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

        val mismatchedAuthority = validAuthority.copy(alarmId = 99L)
        val snapshot = CurrentNextActionSnapshot(
            alarmId = 1L,
            expectedScheduledTargetEpochMs = wed0700Ms,
            expectedBaseTriggerTimeEpochMs = wed0700Ms,
            expectedGeneration = 1,
            authorityFingerprint = validFingerprint
        )

        val decision = planner.planCurrentNext(
            alarm = alarm,
            actionSnapshot = snapshot,
            nowEpochMs = nowEpochMs,
            storedAuthority = mismatchedAuthority,
            evaluationZoneId = zoneId
        )

        assertEquals("Mismatched Authority alarmId MUST fail-close as STALE_OCCURRENCE", CurrentNextDecisionOutcome.STALE_OCCURRENCE, decision.outcome)
        assertFalse(decision.isSkipActionEligible)
        assertEquals(CurrentNextDecisionPlanner.REASON_AUTHORITY_ALARM_ID_MISMATCH, decision.decisionReason)
    }

    @Test
    fun `7 expected TARGETとcanonical TARGETは同じだが、保存済みnextTriggerTimeだけ異なるまたは0L`() {
        // Case A: alarm.nextTriggerTime = 0L (Defect A strict check: nextTriggerTime = 0L MUST fail-close)
        val zeroProjectionAlarm = Alarm(
            id = 1L,
            hour = 7,
            minute = 0,
            isEnabled = true,
            scheduleKind = Alarm.SCHEDULE_KIND_REPEATING,
            repeatDays = setOf(DayOfWeek.WEDNESDAY),
            generation = 1,
            revision = 1,
            nextTriggerTime = 0L // Unset / 0L projection
        )

        val snapshot = CurrentNextActionSnapshot(
            alarmId = 1L,
            expectedScheduledTargetEpochMs = wed0700Ms,
            expectedBaseTriggerTimeEpochMs = wed0700Ms,
            expectedGeneration = 1,
            authorityFingerprint = validFingerprint
        )

        val decisionZero = planner.planCurrentNext(
            alarm = zeroProjectionAlarm,
            actionSnapshot = snapshot,
            nowEpochMs = nowEpochMs,
            storedAuthority = validAuthority,
            evaluationZoneId = zoneId
        )

        assertEquals("Zero stored nextTriggerTime MUST fail-close as STALE_OCCURRENCE", CurrentNextDecisionOutcome.STALE_OCCURRENCE, decisionZero.outcome)
        assertFalse(decisionZero.isSkipActionEligible)
        assertEquals(CurrentNextDecisionPlanner.REASON_PROJECTION_MISMATCH, decisionZero.decisionReason)

        // Case B: alarm.nextTriggerTime = wed0700Ms + 3600000L (Mismatch)
        val mismatchProjectionAlarm = zeroProjectionAlarm.copy(nextTriggerTime = wed0700Ms + 3600000L)
        val decisionMismatch = planner.planCurrentNext(
            alarm = mismatchProjectionAlarm,
            actionSnapshot = snapshot,
            nowEpochMs = nowEpochMs,
            storedAuthority = validAuthority,
            evaluationZoneId = zoneId
        )

        assertEquals("Stored nextTriggerTime mismatch MUST be rejected as STALE_OCCURRENCE", CurrentNextDecisionOutcome.STALE_OCCURRENCE, decisionMismatch.outcome)
        assertFalse(decisionMismatch.isSkipActionEligible)
        assertEquals(CurrentNextDecisionPlanner.REASON_PROJECTION_MISMATCH, decisionMismatch.decisionReason)
    }

    @Test
    fun `8 同一TARGETでgeneration変更`() {
        val alarmGen2 = Alarm(
            id = 1L,
            hour = 7,
            minute = 0,
            isEnabled = true,
            scheduleKind = Alarm.SCHEDULE_KIND_REPEATING,
            repeatDays = setOf(DayOfWeek.WEDNESDAY),
            generation = 2,
            revision = 1,
            nextTriggerTime = wed0700Ms
        )

        val staleGen1Snapshot = CurrentNextActionSnapshot(
            alarmId = 1L,
            expectedScheduledTargetEpochMs = wed0700Ms,
            expectedBaseTriggerTimeEpochMs = wed0700Ms,
            expectedGeneration = 1,
            authorityFingerprint = validFingerprint
        )

        val decision = planner.planCurrentNext(
            alarm = alarmGen2,
            actionSnapshot = staleGen1Snapshot,
            nowEpochMs = nowEpochMs,
            storedAuthority = validAuthority,
            evaluationZoneId = zoneId
        )

        assertEquals("Generation mismatch (1 vs 2) MUST be rejected as STALE_OCCURRENCE", CurrentNextDecisionOutcome.STALE_OCCURRENCE, decision.outcome)
        assertFalse(decision.isSkipActionEligible)
        assertEquals(CurrentNextDecisionPlanner.REASON_GENERATION_MISMATCH, decision.decisionReason)
    }

    @Test
    fun `9 同一TARGETでinstanceToken変更`() {
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

        val staleTokenFingerprint = validFingerprint.copy(instanceToken = "old-instance-token")
        val snapshot = CurrentNextActionSnapshot(
            alarmId = 1L,
            expectedScheduledTargetEpochMs = wed0700Ms,
            expectedBaseTriggerTimeEpochMs = wed0700Ms,
            expectedGeneration = 1,
            authorityFingerprint = staleTokenFingerprint
        )

        val decision = planner.planCurrentNext(
            alarm = alarm,
            actionSnapshot = snapshot,
            nowEpochMs = nowEpochMs,
            storedAuthority = validAuthority,
            evaluationZoneId = zoneId
        )

        assertEquals("instanceToken mismatch MUST be rejected as STALE_OCCURRENCE", CurrentNextDecisionOutcome.STALE_OCCURRENCE, decision.outcome)
        assertFalse(decision.isSkipActionEligible)
        assertEquals(CurrentNextDecisionPlanner.REASON_AUTHORITY_MISMATCH, decision.decisionReason)
    }

    @Test
    fun `10 不支持のEvaluator decision`() {
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

        val cancelException = ScheduleException(
            alarmId = 1L,
            baseTriggerTime = wed0700Ms,
            exceptionType = ScheduleException.TYPE_CANCEL,
            generation = 1,
            revision = 1
        )

        val snapshot = CurrentNextActionSnapshot(
            alarmId = 1L,
            expectedScheduledTargetEpochMs = wed0700Ms,
            expectedBaseTriggerTimeEpochMs = wed0700Ms,
            expectedGeneration = 1,
            authorityFingerprint = validFingerprint
        )

        val decision = planner.planCurrentNext(
            alarm = alarm,
            actionSnapshot = snapshot,
            nowEpochMs = nowEpochMs,
            exceptions = listOf(cancelException),
            storedAuthority = validAuthority,
            evaluationZoneId = zoneId
        )

        assertEquals("TYPE_CANCEL exception MUST evaluate UNSUPPORTED_SCHEDULE", CurrentNextDecisionOutcome.UNSUPPORTED_SCHEDULE, decision.outcome)
        assertFalse(decision.isSkipActionEligible)
        assertEquals(CurrentNextDecisionPlanner.REASON_UNSUPPORTED_EXCEPTION, decision.decisionReason)
    }

    @Test
    fun `11 Time OnceでBASEとTARGETが異なる正常例`() {
        val wedOverride0930Ms = LocalDate.of(2026, 10, 28)
            .atTime(LocalTime.of(9, 30))
            .atZone(zoneId)
            .toInstant()
            .toEpochMilli()

        val alarm = Alarm(
            id = 1L,
            hour = 7,
            minute = 0,
            isEnabled = true,
            scheduleKind = Alarm.SCHEDULE_KIND_REPEATING,
            repeatDays = setOf(DayOfWeek.WEDNESDAY),
            generation = 1,
            revision = 1,
            nextTriggerTime = wedOverride0930Ms // Stored projection equals targetTriggerTime
        )

        val overrideException = ScheduleException(
            alarmId = 1L,
            baseTriggerTime = wed0700Ms,
            exceptionType = ScheduleException.TYPE_TIME_OVERRIDE,
            overrideTriggerTime = wedOverride0930Ms,
            generation = 1,
            revision = 1
        )

        val snapshot = CurrentNextActionSnapshot(
            alarmId = 1L,
            expectedScheduledTargetEpochMs = wedOverride0930Ms,
            expectedBaseTriggerTimeEpochMs = wed0700Ms,
            expectedGeneration = 1,
            authorityFingerprint = validFingerprint
        )

        val decision = planner.planCurrentNext(
            alarm = alarm,
            actionSnapshot = snapshot,
            nowEpochMs = nowEpochMs,
            exceptions = listOf(overrideException),
            storedAuthority = validAuthority,
            evaluationZoneId = zoneId
        )

        assertEquals(CurrentNextDecisionOutcome.CURRENT_NEXT_VALID, decision.outcome)
        assertTrue(decision.isSkipActionEligible)
        assertEquals(wed0700Ms, decision.evaluatedOccurrence!!.baseTriggerTimeEpochMs)
        assertEquals(wedOverride0930Ms, decision.evaluatedOccurrence!!.targetTriggerTimeEpochMs)
        assertEquals(CurrentNextDecisionPlanner.REASON_VALID, decision.decisionReason)
    }

    @Test
    fun `12 ラベル変更のみでrevision増加した正常例`() {
        val alarmRev1 = Alarm(
            id = 1L,
            hour = 7,
            minute = 0,
            isEnabled = true,
            label = "Original Label",
            scheduleKind = Alarm.SCHEDULE_KIND_REPEATING,
            repeatDays = setOf(DayOfWeek.WEDNESDAY),
            generation = 1,
            revision = 1,
            nextTriggerTime = wed0700Ms
        )

        // Label update increments revision to 5, generation remains 1
        val alarmRev5 = alarmRev1.copy(label = "Updated Label", revision = 5)

        val snapshot = CurrentNextActionSnapshot(
            alarmId = 1L,
            expectedScheduledTargetEpochMs = wed0700Ms,
            expectedBaseTriggerTimeEpochMs = wed0700Ms,
            expectedGeneration = 1,
            authorityFingerprint = validFingerprint
        )

        val decision = planner.planCurrentNext(
            alarm = alarmRev5,
            actionSnapshot = snapshot,
            nowEpochMs = nowEpochMs,
            storedAuthority = validAuthority,
            evaluationZoneId = zoneId
        )

        assertEquals("Revision increase due to label edit MUST be accepted as CURRENT_NEXT_VALID", CurrentNextDecisionOutcome.CURRENT_NEXT_VALID, decision.outcome)
        assertTrue(decision.isSkipActionEligible)
        assertEquals(5, decision.activeRevision)
        assertEquals(1, decision.activeGeneration)
        assertEquals(CurrentNextDecisionPlanner.REASON_VALID, decision.decisionReason)
    }
}
