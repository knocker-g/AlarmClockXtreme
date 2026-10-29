package com.sysadmindoc.alarmclock.domain.evaluator

import com.sysadmindoc.alarmclock.data.local.entity.ScheduleAuthority
import com.sysadmindoc.alarmclock.data.local.entity.ScheduleException
import com.sysadmindoc.alarmclock.data.model.Alarm
import java.time.LocalDate
import java.time.ZoneId

/**
 * Pure, side-effect-free Reconcile Planner for Normal Alarms (KNO-154 Phase 0C-A).
 * Evaluates whether a persisted `TYPE_SKIP` exception requires updating stored projection and OS AlarmManager.
 */
class NormalScheduleReconcilePlanner(
    private val evaluator: ScheduleOccurrenceEvaluator = ScheduleOccurrenceEvaluator()
) {
    companion object {
        const val REASON_DISABLED = "ALARM_DISABLED"
        const val REASON_QUICK_UNSUPPORTED = "QUICK_ALARM_UNSUPPORTED"
        const val REASON_UNSUPPORTED_HIDE_FEATURE = "UNSUPPORTED_HIDE_FEATURE"
        const val REASON_MISSING_AUTHORITY = "MISSING_AUTHORITY"
        const val REASON_AUTHORITY_ALARM_ID_MISMATCH = "AUTHORITY_ALARM_ID_MISMATCH"
        const val REASON_AUTHORITY_FINGERPRINT_MISMATCH = "AUTHORITY_FINGERPRINT_MISMATCH"
        const val REASON_NO_ACTIVE_SKIP_EXCEPTION = "NO_ACTIVE_SKIP_EXCEPTION_FOUND"
        const val REASON_NO_VALID_CANDIDATE = "NO_VALID_CANDIDATE_OCCURRENCE"
        const val REASON_PROJECTION_DIFFERS = "PROJECTION_DIFFERS_RECONCILE_REQUIRED"
        const val REASON_PROJECTION_MATCHES_UNVERIFIED_OS = "PROJECTION_MATCHES_UNVERIFIED_OS"
    }

    /**
     * Evaluates reconciliation planning decision for a Normal [alarm] at [nowEpochMs].
     */
    fun planReconcile(
        alarm: Alarm,
        storedAuthority: ScheduleAuthority?,
        exceptions: List<ScheduleException>,
        nowEpochMs: Long,
        expectedAuthorityFingerprint: ScheduleAuthorityFingerprint? = null,
        holidaySnapshotProvider: (LocalDate) -> HolidaySnapshot = { HolidaySnapshot() },
        holidayPolicyOverride: HolidayPolicy? = null,
        consumedOccurrenceKeys: Set<String> = emptySet(),
        evaluationZoneId: ZoneId = ZoneId.systemDefault(),
        allowHidePersonalRuntimeFeatures: Boolean = false
    ): ReconcileDecision {
        // 1. Guard: Disabled or Quick Alarm
        if (!alarm.isEnabled || alarm.isQuickAlarm) {
            return ReconcileDecision(
                outcome = ReconcileOutcome.UNSUPPORTED_SCHEDULE,
                alarmId = alarm.id,
                currentStoredNextTriggerTimeEpochMs = alarm.nextTriggerTime,
                evaluatedPostSkipNextTargetEpochMs = null,
                activeGeneration = alarm.generation,
                isOsRescheduleCandidate = false,
                decisionReason = if (!alarm.isEnabled) REASON_DISABLED else REASON_QUICK_UNSUPPORTED
            )
        }

        // 2. Guard: Personal HIDE runtime boundary
        if (!allowHidePersonalRuntimeFeatures) {
            val usesHideFeatures = alarm.shiftPattern.isNotBlank() ||
                    alarm.timezonePolicy == Alarm.TIMEZONE_POLICY_FIXED ||
                    alarm.solarOffsetMinutes != 0 ||
                    alarm.smartAlarmEnabled ||
                    alarm.weatherEarlyMinutes != 0

            if (usesHideFeatures) {
                return ReconcileDecision(
                    outcome = ReconcileOutcome.UNSUPPORTED_SCHEDULE,
                    alarmId = alarm.id,
                    currentStoredNextTriggerTimeEpochMs = alarm.nextTriggerTime,
                    evaluatedPostSkipNextTargetEpochMs = null,
                    activeGeneration = alarm.generation,
                    isOsRescheduleCandidate = false,
                    decisionReason = REASON_UNSUPPORTED_HIDE_FEATURE
                )
            }
        }

        // 3. Guard: Authority Check (storedAuthority and expectedAuthorityFingerprint are MANDATORY)
        if (storedAuthority == null || expectedAuthorityFingerprint == null) {
            return ReconcileDecision(
                outcome = ReconcileOutcome.MISSING_AUTHORITY,
                alarmId = alarm.id,
                currentStoredNextTriggerTimeEpochMs = alarm.nextTriggerTime,
                evaluatedPostSkipNextTargetEpochMs = null,
                activeGeneration = alarm.generation,
                isOsRescheduleCandidate = false,
                decisionReason = REASON_MISSING_AUTHORITY
            )
        }

        if (storedAuthority.alarmId != alarm.id || expectedAuthorityFingerprint.alarmId != alarm.id) {
            return ReconcileDecision(
                outcome = ReconcileOutcome.MISSING_AUTHORITY,
                alarmId = alarm.id,
                currentStoredNextTriggerTimeEpochMs = alarm.nextTriggerTime,
                evaluatedPostSkipNextTargetEpochMs = null,
                activeGeneration = alarm.generation,
                isOsRescheduleCandidate = false,
                decisionReason = REASON_AUTHORITY_ALARM_ID_MISMATCH
            )
        }

        val fp = expectedAuthorityFingerprint
        val isMatch = fp.instanceToken == storedAuthority.instanceToken &&
                fp.restoreEpoch == storedAuthority.restoreEpoch &&
                fp.scheduleRevision == storedAuthority.scheduleRevision &&
                fp.definitionRevision == storedAuthority.definitionRevision

        if (!isMatch) {
            return ReconcileDecision(
                outcome = ReconcileOutcome.STALE_GENERATION,
                alarmId = alarm.id,
                currentStoredNextTriggerTimeEpochMs = alarm.nextTriggerTime,
                evaluatedPostSkipNextTargetEpochMs = null,
                activeGeneration = alarm.generation,
                isOsRescheduleCandidate = false,
                decisionReason = REASON_AUTHORITY_FINGERPRINT_MISMATCH
            )
        }

        // 4. Guard: Presence of active TYPE_SKIP exception for current generation is MANDATORY for Skip Reconcile
        val activeSkipExceptions = exceptions.filter {
            it.alarmId == alarm.id &&
                    it.generation == alarm.generation &&
                    it.exceptionType == ScheduleException.TYPE_SKIP
        }

        if (activeSkipExceptions.isEmpty()) {
            return ReconcileDecision(
                outcome = ReconcileOutcome.NO_ACTIVE_SKIP_EXCEPTION,
                alarmId = alarm.id,
                currentStoredNextTriggerTimeEpochMs = alarm.nextTriggerTime,
                evaluatedPostSkipNextTargetEpochMs = null,
                activeGeneration = alarm.generation,
                isOsRescheduleCandidate = false,
                decisionReason = REASON_NO_ACTIVE_SKIP_EXCEPTION
            )
        }

        // Filter exceptions strictly for current alarm and current generation
        val activeExceptions = exceptions.filter { it.alarmId == alarm.id && it.generation == alarm.generation }

        // Evaluate post-skip next candidate occurrence
        val postSkipEvaluated = evaluator.evaluateOccurrence(
            alarm = alarm,
            fromEpochMs = nowEpochMs,
            exceptions = activeExceptions,
            holidaySnapshotProvider = holidaySnapshotProvider,
            holidayPolicyOverride = holidayPolicyOverride,
            consumedOccurrenceKeys = consumedOccurrenceKeys,
            evaluationZoneId = evaluationZoneId,
            allowHidePersonalRuntimeFeatures = allowHidePersonalRuntimeFeatures
        )

        val postSkipNextTargetMs = postSkipEvaluated?.targetTriggerTimeEpochMs

        if (postSkipEvaluated == null || postSkipEvaluated.decision == OccurrenceDecision.VALID_NO_CURRENT_OCCURRENCE || postSkipNextTargetMs == null) {
            return ReconcileDecision(
                outcome = ReconcileOutcome.NO_VALID_CANDIDATE_OCCURRENCE,
                alarmId = alarm.id,
                currentStoredNextTriggerTimeEpochMs = alarm.nextTriggerTime,
                evaluatedPostSkipNextTargetEpochMs = null,
                activeGeneration = alarm.generation,
                isOsRescheduleCandidate = false,
                decisionReason = REASON_NO_VALID_CANDIDATE
            )
        }

        // Compare stored projection with evaluated post-skip next target
        val isRescheduleNeeded = alarm.nextTriggerTime != postSkipNextTargetMs

        val outcome = if (isRescheduleNeeded) ReconcileOutcome.PROJECTION_DIFFERS_RECONCILE_REQUIRED else ReconcileOutcome.PROJECTION_UPDATED_UNVERIFIED_OS
        val reason = if (isRescheduleNeeded) REASON_PROJECTION_DIFFERS else REASON_PROJECTION_MATCHES_UNVERIFIED_OS

        return ReconcileDecision(
            outcome = outcome,
            alarmId = alarm.id,
            currentStoredNextTriggerTimeEpochMs = alarm.nextTriggerTime,
            evaluatedPostSkipNextTargetEpochMs = postSkipNextTargetMs,
            activeGeneration = alarm.generation,
            isOsRescheduleCandidate = isRescheduleNeeded,
            decisionReason = reason
        )
    }
}
