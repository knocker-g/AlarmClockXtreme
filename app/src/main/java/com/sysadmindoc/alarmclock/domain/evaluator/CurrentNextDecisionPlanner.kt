package com.sysadmindoc.alarmclock.domain.evaluator

import com.sysadmindoc.alarmclock.data.local.entity.ScheduleAuthority
import com.sysadmindoc.alarmclock.data.local.entity.ScheduleException
import com.sysadmindoc.alarmclock.data.model.Alarm
import java.time.LocalDate
import java.time.ZoneId

/**
 * Pure, side-effect-free Current-Next Decision Planner for Normal Alarms (KNO-154).
 * Reuses [ScheduleOccurrenceEvaluator] to evaluate expected targets, stale generation checks,
 * active firing sessions, and Skip-Next eligibility without mutating DB state or invoking OS AlarmManager.
 */
class CurrentNextDecisionPlanner(
    private val evaluator: ScheduleOccurrenceEvaluator = ScheduleOccurrenceEvaluator()
) {
    companion object {
        const val REASON_ACTIVE_FIRING = "ACTIVE_FIRING_SESSION"
        const val REASON_DISABLED = "ALARM_DISABLED"
        const val REASON_QUICK_UNSUPPORTED = "QUICK_ALARM_UNSUPPORTED"
        const val REASON_EVALUATOR_NULL = "EVALUATOR_NULL"
        const val REASON_NEEDS_REPAIR = "SCHEDULE_NEEDS_REPAIR"
        const val REASON_UNSUPPORTED_EXCEPTION = "UNSUPPORTED_EXCEPTION"
        const val REASON_SKIPPED_OCCURRENCE = "SKIPPED_OCCURRENCE_INELIGIBLE"
        const val REASON_NO_CURRENT_OCCURRENCE = "NO_CURRENT_OCCURRENCE"
        const val REASON_ALREADY_CONSUMED = "ALREADY_CONSUMED"
        const val REASON_MISSING_EXPECTED_TARGET = "MISSING_EXPECTED_TARGET"
        const val REASON_MISSING_ACTION_SNAPSHOT = "MISSING_ACTION_SNAPSHOT"
        const val REASON_ACTION_ALARM_ID_MISMATCH = "ACTION_ALARM_ID_MISMATCH"
        const val REASON_AUTHORITY_ALARM_ID_MISMATCH = "AUTHORITY_ALARM_ID_MISMATCH"
        const val REASON_GENERATION_MISMATCH = "GENERATION_MISMATCH"
        const val REASON_AUTHORITY_MISMATCH = "SCHEDULE_AUTHORITY_FINGERPRINT_MISMATCH"
        const val REASON_PROJECTION_MISMATCH = "STORED_NEXT_TRIGGER_PROJECTION_MISMATCH"
        const val REASON_TARGET_MISMATCH = "SCHEDULED_TARGET_MISMATCH"
        const val REASON_BASE_TIME_MISMATCH = "BASE_TRIGGER_TIME_MISMATCH"
        const val REASON_VALID = "CURRENT_NEXT_VALID"
    }

    /**
     * Evaluates current-next planning decision for a Normal [alarm] at [nowEpochMs].
     */
    fun planCurrentNext(
        alarm: Alarm,
        actionSnapshot: CurrentNextActionSnapshot?,
        nowEpochMs: Long,
        exceptions: List<ScheduleException> = emptyList(),
        storedAuthority: ScheduleAuthority? = null,
        holidaySnapshotProvider: (LocalDate) -> HolidaySnapshot = { HolidaySnapshot() },
        holidayPolicyOverride: HolidayPolicy? = null,
        consumedOccurrenceKeys: Set<String> = emptySet(),
        activeFiringAlarmId: Long? = null,
        evaluationZoneId: ZoneId = ZoneId.systemDefault(),
        allowHidePersonalRuntimeFeatures: Boolean = false
    ): CurrentNextDecision {
        // 1. Guard: Active FIRING Session
        if (activeFiringAlarmId != null && activeFiringAlarmId == alarm.id) {
            return CurrentNextDecision(
                outcome = CurrentNextDecisionOutcome.ACTIVE_SESSION,
                alarmId = alarm.id,
                expectedTargetEpochMs = actionSnapshot?.expectedScheduledTargetEpochMs,
                evaluatedOccurrence = null,
                canonicalOccurrenceId = null,
                activeGeneration = alarm.generation,
                activeRevision = alarm.revision,
                isSkipActionEligible = false,
                decisionReason = REASON_ACTIVE_FIRING
            )
        }

        // 2. Guard: Disabled or Quick Alarm
        if (!alarm.isEnabled || alarm.isQuickAlarm) {
            return CurrentNextDecision(
                outcome = CurrentNextDecisionOutcome.UNSUPPORTED_SCHEDULE,
                alarmId = alarm.id,
                expectedTargetEpochMs = actionSnapshot?.expectedScheduledTargetEpochMs,
                evaluatedOccurrence = null,
                canonicalOccurrenceId = null,
                activeGeneration = alarm.generation,
                activeRevision = alarm.revision,
                isSkipActionEligible = false,
                decisionReason = if (!alarm.isEnabled) REASON_DISABLED else REASON_QUICK_UNSUPPORTED
            )
        }

        // 3. Strict Action Snapshot & Mandatory Identity Parameters Check
        if (actionSnapshot == null) {
            return CurrentNextDecision(
                outcome = CurrentNextDecisionOutcome.STALE_OCCURRENCE,
                alarmId = alarm.id,
                expectedTargetEpochMs = null,
                evaluatedOccurrence = null,
                canonicalOccurrenceId = null,
                activeGeneration = alarm.generation,
                activeRevision = alarm.revision,
                isSkipActionEligible = false,
                decisionReason = REASON_MISSING_ACTION_SNAPSHOT
            )
        }

        if (actionSnapshot.alarmId != alarm.id) {
            return CurrentNextDecision(
                outcome = CurrentNextDecisionOutcome.STALE_OCCURRENCE,
                alarmId = alarm.id,
                expectedTargetEpochMs = actionSnapshot.expectedScheduledTargetEpochMs,
                evaluatedOccurrence = null,
                canonicalOccurrenceId = null,
                activeGeneration = alarm.generation,
                activeRevision = alarm.revision,
                isSkipActionEligible = false,
                decisionReason = REASON_ACTION_ALARM_ID_MISMATCH
            )
        }

        val expectedTargetMs = actionSnapshot.expectedScheduledTargetEpochMs
        if (expectedTargetMs == null || expectedTargetMs <= 0L) {
            return CurrentNextDecision(
                outcome = CurrentNextDecisionOutcome.STALE_OCCURRENCE,
                alarmId = alarm.id,
                expectedTargetEpochMs = expectedTargetMs,
                evaluatedOccurrence = null,
                canonicalOccurrenceId = null,
                activeGeneration = alarm.generation,
                activeRevision = alarm.revision,
                isSkipActionEligible = false,
                decisionReason = REASON_MISSING_EXPECTED_TARGET
            )
        }

        if (actionSnapshot.expectedBaseTriggerTimeEpochMs == null || actionSnapshot.expectedBaseTriggerTimeEpochMs <= 0L) {
            return CurrentNextDecision(
                outcome = CurrentNextDecisionOutcome.STALE_OCCURRENCE,
                alarmId = alarm.id,
                expectedTargetEpochMs = expectedTargetMs,
                evaluatedOccurrence = null,
                canonicalOccurrenceId = null,
                activeGeneration = alarm.generation,
                activeRevision = alarm.revision,
                isSkipActionEligible = false,
                decisionReason = REASON_MISSING_EXPECTED_TARGET
            )
        }

        // 4. Generation Mismatch Check
        if (actionSnapshot.expectedGeneration == null || actionSnapshot.expectedGeneration != alarm.generation) {
            return CurrentNextDecision(
                outcome = CurrentNextDecisionOutcome.STALE_OCCURRENCE,
                alarmId = alarm.id,
                expectedTargetEpochMs = expectedTargetMs,
                evaluatedOccurrence = null,
                canonicalOccurrenceId = null,
                activeGeneration = alarm.generation,
                activeRevision = alarm.revision,
                isSkipActionEligible = false,
                decisionReason = REASON_GENERATION_MISMATCH
            )
        }

        // 5. Authority & Authority Fingerprint Mandatory Validation
        if (actionSnapshot.authorityFingerprint == null || storedAuthority == null) {
            return CurrentNextDecision(
                outcome = CurrentNextDecisionOutcome.STALE_OCCURRENCE,
                alarmId = alarm.id,
                expectedTargetEpochMs = expectedTargetMs,
                evaluatedOccurrence = null,
                canonicalOccurrenceId = null,
                activeGeneration = alarm.generation,
                activeRevision = alarm.revision,
                isSkipActionEligible = false,
                decisionReason = REASON_AUTHORITY_MISMATCH
            )
        }

        if (storedAuthority.alarmId != alarm.id || actionSnapshot.authorityFingerprint.alarmId != alarm.id) {
            return CurrentNextDecision(
                outcome = CurrentNextDecisionOutcome.STALE_OCCURRENCE,
                alarmId = alarm.id,
                expectedTargetEpochMs = expectedTargetMs,
                evaluatedOccurrence = null,
                canonicalOccurrenceId = null,
                activeGeneration = alarm.generation,
                activeRevision = alarm.revision,
                isSkipActionEligible = false,
                decisionReason = REASON_AUTHORITY_ALARM_ID_MISMATCH
            )
        }

        val fp = actionSnapshot.authorityFingerprint
        val isAuthMatch = fp.instanceToken == storedAuthority.instanceToken &&
                fp.restoreEpoch == storedAuthority.restoreEpoch &&
                fp.scheduleRevision == storedAuthority.scheduleRevision &&
                fp.definitionRevision == storedAuthority.definitionRevision

        if (!isAuthMatch) {
            return CurrentNextDecision(
                outcome = CurrentNextDecisionOutcome.STALE_OCCURRENCE,
                alarmId = alarm.id,
                expectedTargetEpochMs = expectedTargetMs,
                evaluatedOccurrence = null,
                canonicalOccurrenceId = null,
                activeGeneration = alarm.generation,
                activeRevision = alarm.revision,
                isSkipActionEligible = false,
                decisionReason = REASON_AUTHORITY_MISMATCH
            )
        }

        // 6. Defect A Strict Projection Check (alarm.nextTriggerTime > 0L && alarm.nextTriggerTime == expectedTargetMs)
        if (alarm.nextTriggerTime <= 0L || alarm.nextTriggerTime != expectedTargetMs) {
            return CurrentNextDecision(
                outcome = CurrentNextDecisionOutcome.STALE_OCCURRENCE,
                alarmId = alarm.id,
                expectedTargetEpochMs = expectedTargetMs,
                evaluatedOccurrence = null,
                canonicalOccurrenceId = null,
                activeGeneration = alarm.generation,
                activeRevision = alarm.revision,
                isSkipActionEligible = false,
                decisionReason = REASON_PROJECTION_MISMATCH
            )
        }

        // 7. Evaluate active candidate occurrence using ScheduleOccurrenceEvaluator
        val evaluated = evaluator.evaluateOccurrence(
            alarm = alarm,
            fromEpochMs = nowEpochMs,
            exceptions = exceptions,
            holidaySnapshotProvider = holidaySnapshotProvider,
            holidayPolicyOverride = holidayPolicyOverride,
            consumedOccurrenceKeys = consumedOccurrenceKeys,
            evaluationZoneId = evaluationZoneId,
            allowHidePersonalRuntimeFeatures = allowHidePersonalRuntimeFeatures
        )

        if (evaluated == null) {
            return CurrentNextDecision(
                outcome = CurrentNextDecisionOutcome.UNSUPPORTED_SCHEDULE,
                alarmId = alarm.id,
                expectedTargetEpochMs = expectedTargetMs,
                evaluatedOccurrence = null,
                canonicalOccurrenceId = null,
                activeGeneration = alarm.generation,
                activeRevision = alarm.revision,
                isSkipActionEligible = false,
                decisionReason = REASON_EVALUATOR_NULL
            )
        }

        if (evaluated.decision == OccurrenceDecision.INVALID_SCHEDULE_NEEDS_REPAIR) {
            return CurrentNextDecision(
                outcome = CurrentNextDecisionOutcome.UNSUPPORTED_SCHEDULE,
                alarmId = alarm.id,
                expectedTargetEpochMs = expectedTargetMs,
                evaluatedOccurrence = evaluated,
                canonicalOccurrenceId = null,
                activeGeneration = alarm.generation,
                activeRevision = alarm.revision,
                isSkipActionEligible = false,
                decisionReason = REASON_NEEDS_REPAIR
            )
        }

        if (evaluated.decision == OccurrenceDecision.UNSUPPORTED_EXCEPTION_TYPE) {
            return CurrentNextDecision(
                outcome = CurrentNextDecisionOutcome.UNSUPPORTED_SCHEDULE,
                alarmId = alarm.id,
                expectedTargetEpochMs = expectedTargetMs,
                evaluatedOccurrence = evaluated,
                canonicalOccurrenceId = evaluated.occurrenceId,
                activeGeneration = alarm.generation,
                activeRevision = alarm.revision,
                isSkipActionEligible = false,
                decisionReason = REASON_UNSUPPORTED_EXCEPTION
            )
        }

        if (evaluated.decision == OccurrenceDecision.SKIPPED_BY_EXCEPTION || evaluated.decision == OccurrenceDecision.SKIPPED_BY_HOLIDAY) {
            return CurrentNextDecision(
                outcome = CurrentNextDecisionOutcome.UNSUPPORTED_SCHEDULE,
                alarmId = alarm.id,
                expectedTargetEpochMs = expectedTargetMs,
                evaluatedOccurrence = evaluated,
                canonicalOccurrenceId = evaluated.occurrenceId,
                activeGeneration = alarm.generation,
                activeRevision = alarm.revision,
                isSkipActionEligible = false,
                decisionReason = REASON_SKIPPED_OCCURRENCE
            )
        }

        if (evaluated.decision == OccurrenceDecision.VALID_NO_CURRENT_OCCURRENCE) {
            return CurrentNextDecision(
                outcome = CurrentNextDecisionOutcome.NO_CURRENT_OCCURRENCE,
                alarmId = alarm.id,
                expectedTargetEpochMs = expectedTargetMs,
                evaluatedOccurrence = evaluated,
                canonicalOccurrenceId = null,
                activeGeneration = alarm.generation,
                activeRevision = alarm.revision,
                isSkipActionEligible = false,
                decisionReason = REASON_NO_CURRENT_OCCURRENCE
            )
        }

        if (evaluated.decision == OccurrenceDecision.ALREADY_CONSUMED) {
            return CurrentNextDecision(
                outcome = CurrentNextDecisionOutcome.ALREADY_CONSUMED,
                alarmId = alarm.id,
                expectedTargetEpochMs = expectedTargetMs,
                evaluatedOccurrence = evaluated,
                canonicalOccurrenceId = evaluated.occurrenceId,
                activeGeneration = alarm.generation,
                activeRevision = alarm.revision,
                isSkipActionEligible = false,
                decisionReason = REASON_ALREADY_CONSUMED
            )
        }

        // 8. Expected Target Match Check
        if (expectedTargetMs != evaluated.targetTriggerTimeEpochMs) {
            return CurrentNextDecision(
                outcome = CurrentNextDecisionOutcome.STALE_OCCURRENCE,
                alarmId = alarm.id,
                expectedTargetEpochMs = expectedTargetMs,
                evaluatedOccurrence = evaluated,
                canonicalOccurrenceId = evaluated.occurrenceId,
                activeGeneration = alarm.generation,
                activeRevision = alarm.revision,
                isSkipActionEligible = false,
                decisionReason = REASON_TARGET_MISMATCH
            )
        }

        // 9. Expected Base Trigger Time Check
        if (actionSnapshot.expectedBaseTriggerTimeEpochMs != evaluated.baseTriggerTimeEpochMs) {
            return CurrentNextDecision(
                outcome = CurrentNextDecisionOutcome.STALE_OCCURRENCE,
                alarmId = alarm.id,
                expectedTargetEpochMs = expectedTargetMs,
                evaluatedOccurrence = evaluated,
                canonicalOccurrenceId = evaluated.occurrenceId,
                activeGeneration = alarm.generation,
                activeRevision = alarm.revision,
                isSkipActionEligible = false,
                decisionReason = REASON_BASE_TIME_MISMATCH
            )
        }

        // 10. Defect B: CURRENT_NEXT_VALID is returned ONLY for NORMAL_TRIGGER or TIME_OVERRIDDEN
        val isTriggeringDecision = evaluated.decision == OccurrenceDecision.NORMAL_TRIGGER || evaluated.decision == OccurrenceDecision.TIME_OVERRIDDEN
        if (!isTriggeringDecision || evaluated.occurrenceId == null) {
            return CurrentNextDecision(
                outcome = CurrentNextDecisionOutcome.UNSUPPORTED_SCHEDULE,
                alarmId = alarm.id,
                expectedTargetEpochMs = expectedTargetMs,
                evaluatedOccurrence = evaluated,
                canonicalOccurrenceId = evaluated.occurrenceId,
                activeGeneration = alarm.generation,
                activeRevision = alarm.revision,
                isSkipActionEligible = false,
                decisionReason = REASON_SKIPPED_OCCURRENCE
            )
        }

        return CurrentNextDecision(
            outcome = CurrentNextDecisionOutcome.CURRENT_NEXT_VALID,
            alarmId = alarm.id,
            expectedTargetEpochMs = expectedTargetMs,
            evaluatedOccurrence = evaluated,
            canonicalOccurrenceId = evaluated.occurrenceId,
            activeGeneration = alarm.generation,
            activeRevision = alarm.revision,
            isSkipActionEligible = true,
            decisionReason = REASON_VALID
        )
    }
}
