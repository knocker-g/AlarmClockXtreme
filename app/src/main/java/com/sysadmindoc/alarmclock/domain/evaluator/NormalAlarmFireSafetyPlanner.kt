package com.sysadmindoc.alarmclock.domain.evaluator

import com.sysadmindoc.alarmclock.data.local.entity.IssuedOccurrence
import com.sysadmindoc.alarmclock.data.local.entity.ScheduleException
import com.sysadmindoc.alarmclock.data.model.Alarm

/**
 * Pure, side-effect-free Fire Safety Planner for Normal Alarms (KNO-154 Phase 0C-A).
 * Validates incoming firing Intent data against persisted [ScheduleException] records and issuance proofs
 * to suppress firing of skipped occurrences without executing side effects.
 */
class NormalAlarmFireSafetyPlanner {
    companion object {
        const val REASON_CONFIRMED_SKIP = "CONFIRMED_SKIP_EXCEPTION_EXISTS"
        const val REASON_ALLOW_FIRE_VERIFIED = "VERIFIED_ISSUANCE_PROOF_ACTIVE"
        const val REASON_STALE_GENERATION = "FIRED_GENERATION_MISMATCH"
        const val REASON_CONSUMED_OCCURRENCE = "OCCURRENCE_ALREADY_CONSUMED"
        const val REASON_UNVERIFIED_INTENT = "UNVERIFIED_LEGACY_INTENT_DATA"
        const val REASON_MISSING_ISSUANCE_PROOF = "MISSING_VERIFIED_ISSUANCE_PROOF"
        const val REASON_AUTHORITY_FINGERPRINT_MISMATCH = "AUTHORITY_FINGERPRINT_MISMATCH"
        const val REASON_REVOKED_ISSUANCE = "ISSUANCE_PROOF_REVOKED"
        const val REASON_UNSUPPORTED_SCHEDULE = "UNSUPPORTED_SCHEDULE_OR_DISABLED"
    }

    /**
     * Evaluates fire safety decision for a fired Intent at [firedTargetEpochMs].
     */
    fun planFireSafety(
        alarm: Alarm,
        firedTargetEpochMs: Long,
        firedBaseTriggerTimeEpochMs: Long,
        firedGeneration: Int,
        exceptions: List<ScheduleException>,
        issuanceProof: FireIssuanceProof? = null,
        expectedAuthorityFingerprint: ScheduleAuthorityFingerprint? = null,
        consumedOccurrenceKeys: Set<String> = emptySet(),
        allowHidePersonalRuntimeFeatures: Boolean = false
    ): FireSafetyDecision {
        // 1. Guard: Unverified Intent Data (0L or negative timestamps)
        if (firedTargetEpochMs <= 0L || firedBaseTriggerTimeEpochMs <= 0L || firedGeneration <= 0) {
            return FireSafetyDecision(
                outcome = FireSafetyOutcome.UNVERIFIED_INTENT_DATA,
                alarmId = alarm.id,
                firedTargetEpochMs = firedTargetEpochMs,
                firedBaseTriggerTimeEpochMs = firedBaseTriggerTimeEpochMs,
                firedGeneration = firedGeneration,
                isFireSuppressionRequired = false,
                isFireAdmissionConfirmed = false,
                decisionReason = REASON_UNVERIFIED_INTENT
            )
        }

        // 2. Guard: Disabled, Quick Alarm, or Personal HIDE-only feature
        if (!alarm.isEnabled || alarm.isQuickAlarm) {
            return FireSafetyDecision(
                outcome = FireSafetyOutcome.UNVERIFIED_INTENT_DATA,
                alarmId = alarm.id,
                firedTargetEpochMs = firedTargetEpochMs,
                firedBaseTriggerTimeEpochMs = firedBaseTriggerTimeEpochMs,
                firedGeneration = firedGeneration,
                isFireSuppressionRequired = false,
                isFireAdmissionConfirmed = false,
                decisionReason = REASON_UNSUPPORTED_SCHEDULE
            )
        }

        if (!allowHidePersonalRuntimeFeatures) {
            val usesHideFeatures = alarm.shiftPattern.isNotBlank() ||
                    alarm.timezonePolicy == Alarm.TIMEZONE_POLICY_FIXED ||
                    alarm.solarOffsetMinutes != 0 ||
                    alarm.smartAlarmEnabled ||
                    alarm.weatherEarlyMinutes != 0

            if (usesHideFeatures) {
                return FireSafetyDecision(
                    outcome = FireSafetyOutcome.UNVERIFIED_INTENT_DATA,
                    alarmId = alarm.id,
                    firedTargetEpochMs = firedTargetEpochMs,
                    firedBaseTriggerTimeEpochMs = firedBaseTriggerTimeEpochMs,
                    firedGeneration = firedGeneration,
                    isFireSuppressionRequired = false,
                    isFireAdmissionConfirmed = false,
                    decisionReason = REASON_UNSUPPORTED_SCHEDULE
                )
            }
        }

        // 3. Guard: Generation Mismatch
        if (firedGeneration != alarm.generation) {
            return FireSafetyDecision(
                outcome = FireSafetyOutcome.REJECT_STALE_GENERATION,
                alarmId = alarm.id,
                firedTargetEpochMs = firedTargetEpochMs,
                firedBaseTriggerTimeEpochMs = firedBaseTriggerTimeEpochMs,
                firedGeneration = firedGeneration,
                isFireSuppressionRequired = true,
                isFireAdmissionConfirmed = false,
                decisionReason = REASON_STALE_GENERATION
            )
        }

        // 4. Guard: Consumed Occurrence Key Check
        val occurrenceIdKey = "alarm_${alarm.id}_gen_${alarm.generation}_base_$firedBaseTriggerTimeEpochMs"
        if (occurrenceIdKey in consumedOccurrenceKeys) {
            return FireSafetyDecision(
                outcome = FireSafetyOutcome.REJECT_CONSUMED_OCCURRENCE,
                alarmId = alarm.id,
                firedTargetEpochMs = firedTargetEpochMs,
                firedBaseTriggerTimeEpochMs = firedBaseTriggerTimeEpochMs,
                firedGeneration = firedGeneration,
                isFireSuppressionRequired = true,
                isFireAdmissionConfirmed = false,
                decisionReason = REASON_CONSUMED_OCCURRENCE
            )
        }

        // 5. Check for matching TYPE_SKIP exception on (alarmId, firedBaseTriggerTime, firedGeneration)
        val matchingSkip = exceptions.firstOrNull {
            it.alarmId == alarm.id &&
                    it.generation == alarm.generation &&
                    it.baseTriggerTime == firedBaseTriggerTimeEpochMs &&
                    it.exceptionType == ScheduleException.TYPE_SKIP
        }

        if (matchingSkip != null) {
            return FireSafetyDecision(
                outcome = FireSafetyOutcome.SUPPRESS_CONFIRMED_SKIP,
                alarmId = alarm.id,
                firedTargetEpochMs = firedTargetEpochMs,
                firedBaseTriggerTimeEpochMs = firedBaseTriggerTimeEpochMs,
                firedGeneration = firedGeneration,
                isFireSuppressionRequired = true,
                isFireAdmissionConfirmed = false,
                decisionReason = REASON_CONFIRMED_SKIP
            )
        }

        // 6. Strict Issuance Proof Validation
        if (issuanceProof == null ||
            issuanceProof.issueToken.isBlank() ||
            issuanceProof.alarmId != alarm.id ||
            issuanceProof.alarmGeneration != alarm.generation ||
            issuanceProof.scheduledAt != firedTargetEpochMs
        ) {
            return FireSafetyDecision(
                outcome = FireSafetyOutcome.UNVERIFIED_INTENT_DATA,
                alarmId = alarm.id,
                firedTargetEpochMs = firedTargetEpochMs,
                firedBaseTriggerTimeEpochMs = firedBaseTriggerTimeEpochMs,
                firedGeneration = firedGeneration,
                isFireSuppressionRequired = false,
                isFireAdmissionConfirmed = false,
                decisionReason = REASON_MISSING_ISSUANCE_PROOF
            )
        }

        if (issuanceProof.status == IssuedOccurrence.STATUS_REVOKED || issuanceProof.status == IssuedOccurrence.STATUS_SET_FAILED) {
            return FireSafetyDecision(
                outcome = FireSafetyOutcome.UNVERIFIED_INTENT_DATA,
                alarmId = alarm.id,
                firedTargetEpochMs = firedTargetEpochMs,
                firedBaseTriggerTimeEpochMs = firedBaseTriggerTimeEpochMs,
                firedGeneration = firedGeneration,
                isFireSuppressionRequired = false,
                isFireAdmissionConfirmed = false,
                decisionReason = REASON_REVOKED_ISSUANCE
            )
        }

        if (issuanceProof.authorityFingerprint != null && expectedAuthorityFingerprint != null) {
            val fp = issuanceProof.authorityFingerprint
            val exp = expectedAuthorityFingerprint
            val isMatch = fp.alarmId == alarm.id &&
                    fp.instanceToken == exp.instanceToken &&
                    fp.restoreEpoch == exp.restoreEpoch &&
                    fp.scheduleRevision == exp.scheduleRevision &&
                    fp.definitionRevision == exp.definitionRevision

            if (!isMatch) {
                return FireSafetyDecision(
                    outcome = FireSafetyOutcome.UNVERIFIED_INTENT_DATA,
                    alarmId = alarm.id,
                    firedTargetEpochMs = firedTargetEpochMs,
                    firedBaseTriggerTimeEpochMs = firedBaseTriggerTimeEpochMs,
                    firedGeneration = firedGeneration,
                    isFireSuppressionRequired = false,
                    isFireAdmissionConfirmed = false,
                    decisionReason = REASON_AUTHORITY_FINGERPRINT_MISMATCH
                )
            }
        }

        // 7. Verified issuance proof + No skip exception -> Allow confirmed firing
        return FireSafetyDecision(
            outcome = FireSafetyOutcome.ALLOW_FIRE_VERIFIED,
            alarmId = alarm.id,
            firedTargetEpochMs = firedTargetEpochMs,
            firedBaseTriggerTimeEpochMs = firedBaseTriggerTimeEpochMs,
            firedGeneration = firedGeneration,
            isFireSuppressionRequired = false,
            isFireAdmissionConfirmed = true,
            decisionReason = REASON_ALLOW_FIRE_VERIFIED
        )
    }
}
