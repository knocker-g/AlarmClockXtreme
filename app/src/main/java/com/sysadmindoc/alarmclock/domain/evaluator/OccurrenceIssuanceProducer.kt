package com.sysadmindoc.alarmclock.domain.evaluator

import androidx.room.withTransaction
import com.sysadmindoc.alarmclock.data.local.AlarmDatabase
import com.sysadmindoc.alarmclock.data.local.entity.IssueSlot
import com.sysadmindoc.alarmclock.data.local.entity.IssuedOccurrence
import com.sysadmindoc.alarmclock.data.model.Alarm
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

/**
 * In-Transaction Occurrence Issuance Producer (KNO-154 Phase 0C-B Subtask 2 Final Interface Closure).
 * Captures full canonical V35 provenance fields directly from authentic [Alarm] and [ScheduleAuthority]
 * records and commits a new [IssuedOccurrence] into Room DB inside a single transaction.
 *
 * Enforces symmetrical propagation of all 5 evaluation context parameters (Modification A),
 * in-transaction canonical re-evaluation, and mandatory Authority fingerprint binding.
 */
class OccurrenceIssuanceProducer(
    private val database: AlarmDatabase,
    private val occurrenceEvaluator: ScheduleOccurrenceEvaluator = ScheduleOccurrenceEvaluator()
) {
    /**
     * Issues and persists a new [IssuedOccurrence] with complete V35 provenance.
     */
    suspend fun issueOccurrence(
        alarmId: Long,
        baseTriggerTimeEpochMs: Long,
        scheduledTargetEpochMs: Long,
        nowEpochMs: Long,
        expectedAuthorityFingerprint: ScheduleAuthorityFingerprint? = null,
        evaluatedOccurrence: EvaluatedOccurrence? = null,
        fireId: String = "fire_${alarmId}_${scheduledTargetEpochMs}",
        holidaySnapshotProvider: (LocalDate) -> HolidaySnapshot = { HolidaySnapshot() },
        holidayPolicyOverride: HolidayPolicy? = null,
        consumedOccurrenceKeys: Set<String> = emptySet(),
        evaluationZoneId: ZoneId = ZoneId.systemDefault(),
        allowHidePersonalRuntimeFeatures: Boolean = false
    ): IssuanceProducerResult {
        // Validate BASE and TARGET timestamps
        if (baseTriggerTimeEpochMs <= 0L || scheduledTargetEpochMs <= 0L) {
            return IssuanceProducerResult(
                outcome = IssuanceProducerOutcome.UNSUPPORTED_SCHEDULE,
                alarmId = alarmId,
                issuedOccurrence = null,
                issueSlot = null,
                decisionReason = "Invalid base or target trigger timestamp"
            )
        }

        return database.withTransaction {
            val alarmDao = database.alarmDao()
            val authorityDao = database.scheduleAuthorityDao()
            val exceptionDao = database.scheduleExceptionDao()
            val issuedDao = database.issuedOccurrenceDao()

            // 1. Re-read Alarm from DB
            val alarm = alarmDao.getById(alarmId)
            if (alarm == null || !alarm.isEnabled || alarm.isQuickAlarm) {
                return@withTransaction IssuanceProducerResult(
                    outcome = IssuanceProducerOutcome.UNSUPPORTED_SCHEDULE,
                    alarmId = alarmId,
                    issuedOccurrence = null,
                    issueSlot = null,
                    decisionReason = if (alarm == null) "Alarm not found" else "Alarm disabled or Quick"
                )
            }

            // 2. Guard: Personal HIDE features
            if (!allowHidePersonalRuntimeFeatures) {
                val usesHideFeatures = alarm.shiftPattern.isNotBlank() ||
                        alarm.timezonePolicy == Alarm.TIMEZONE_POLICY_FIXED ||
                        alarm.solarOffsetMinutes != 0 ||
                        alarm.smartAlarmEnabled ||
                        alarm.weatherEarlyMinutes != 0

                if (usesHideFeatures) {
                    return@withTransaction IssuanceProducerResult(
                        outcome = IssuanceProducerOutcome.UNSUPPORTED_SCHEDULE,
                        alarmId = alarmId,
                        issuedOccurrence = null,
                        issueSlot = null,
                        decisionReason = "Unsupported Personal HIDE feature"
                    )
                }
            }

            // 3. Re-read ScheduleAuthority & mandatory fingerprint verification
            val storedAuthority = authorityDao.getAuthorityByAlarmId(alarmId)
            if (storedAuthority == null || storedAuthority.alarmId != alarmId || storedAuthority.instanceToken.isBlank()) {
                return@withTransaction IssuanceProducerResult(
                    outcome = IssuanceProducerOutcome.MISSING_AUTHORITY,
                    alarmId = alarmId,
                    issuedOccurrence = null,
                    issueSlot = null,
                    decisionReason = "Missing or blank ScheduleAuthority"
                )
            }

            val currentFingerprint = ScheduleAuthorityFingerprint.fromAuthority(storedAuthority)
            if (expectedAuthorityFingerprint != null) {
                val fp = expectedAuthorityFingerprint
                val isMatch = fp.alarmId == alarmId &&
                        fp.instanceToken == storedAuthority.instanceToken &&
                        fp.restoreEpoch == storedAuthority.restoreEpoch &&
                        fp.scheduleRevision == storedAuthority.scheduleRevision &&
                        fp.definitionRevision == storedAuthority.definitionRevision

                if (!isMatch) {
                    return@withTransaction IssuanceProducerResult(
                        outcome = IssuanceProducerOutcome.STALE_GENERATION,
                        alarmId = alarmId,
                        issuedOccurrence = null,
                        issueSlot = null,
                        decisionReason = "Authority fingerprint mismatch"
                    )
                }
            }

            // 4. Modification A: In-transaction canonical re-evaluation with all 5 context parameters
            val exceptions = exceptionDao.getExceptionsForAlarmGeneration(alarmId, alarm.generation)
            val inTxEvaluated = occurrenceEvaluator.evaluateOccurrence(
                alarm = alarm,
                fromEpochMs = nowEpochMs - 1000L,
                exceptions = exceptions,
                holidaySnapshotProvider = holidaySnapshotProvider,
                holidayPolicyOverride = holidayPolicyOverride,
                consumedOccurrenceKeys = consumedOccurrenceKeys,
                evaluationZoneId = evaluationZoneId,
                allowHidePersonalRuntimeFeatures = allowHidePersonalRuntimeFeatures
            )

            // Modification B: Reject null canonical evaluation cleanly
            if (inTxEvaluated == null) {
                return@withTransaction IssuanceProducerResult(
                    outcome = IssuanceProducerOutcome.UNSUPPORTED_SCHEDULE,
                    alarmId = alarmId,
                    issuedOccurrence = null,
                    issueSlot = null,
                    decisionReason = "In-transaction canonical occurrence evaluation returned null"
                )
            }

            // Mandatory occurrenceId non-null check & strict property verification
            val occId = inTxEvaluated.occurrenceId
            if (occId == null ||
                occId.alarmId != alarmId ||
                occId.generation != alarm.generation ||
                occId.baseTriggerTime != baseTriggerTimeEpochMs ||
                inTxEvaluated.baseTriggerTimeEpochMs != baseTriggerTimeEpochMs ||
                inTxEvaluated.targetTriggerTimeEpochMs != scheduledTargetEpochMs
            ) {
                return@withTransaction IssuanceProducerResult(
                    outcome = IssuanceProducerOutcome.UNSUPPORTED_SCHEDULE,
                    alarmId = alarmId,
                    issuedOccurrence = null,
                    issueSlot = null,
                    decisionReason = "Canonical occurrence evaluation mismatch (occurrenceId/base/target)"
                )
            }

            // If caller provided an evaluatedOccurrence, verify strict 1-to-1 match against in-transaction model
            if (evaluatedOccurrence != null) {
                val callerOccId = evaluatedOccurrence.occurrenceId
                val callerMatch = callerOccId != null &&
                        callerOccId == inTxEvaluated.occurrenceId &&
                        evaluatedOccurrence.alarmId == inTxEvaluated.alarmId &&
                        evaluatedOccurrence.generation == inTxEvaluated.generation &&
                        evaluatedOccurrence.baseTriggerTimeEpochMs == inTxEvaluated.baseTriggerTimeEpochMs &&
                        evaluatedOccurrence.targetTriggerTimeEpochMs == inTxEvaluated.targetTriggerTimeEpochMs &&
                        evaluatedOccurrence.baseTriggerTimeEpochMs == baseTriggerTimeEpochMs &&
                        evaluatedOccurrence.targetTriggerTimeEpochMs == scheduledTargetEpochMs &&
                        evaluatedOccurrence.decision == inTxEvaluated.decision

                if (!callerMatch) {
                    return@withTransaction IssuanceProducerResult(
                        outcome = IssuanceProducerOutcome.UNSUPPORTED_SCHEDULE,
                        alarmId = alarmId,
                        issuedOccurrence = null,
                        issueSlot = null,
                        decisionReason = "Caller-provided EvaluatedOccurrence mismatches in-transaction evaluation"
                    )
                }
            }

            // Reject non-issuable decisions (e.g. SKIPPED_BY_EXCEPTION, SKIPPED_BY_HOLIDAY, INVALID_SCHEDULE_NEEDS_REPAIR)
            val isIssuableDecision = inTxEvaluated.decision == OccurrenceDecision.NORMAL_TRIGGER ||
                    inTxEvaluated.decision == OccurrenceDecision.TIME_OVERRIDDEN

            if (!isIssuableDecision) {
                return@withTransaction IssuanceProducerResult(
                    outcome = IssuanceProducerOutcome.UNSUPPORTED_SCHEDULE,
                    alarmId = alarmId,
                    issuedOccurrence = null,
                    issueSlot = null,
                    decisionReason = "Occurrence decision is non-issuable: ${inTxEvaluated.decision}"
                )
            }

            // 5. Get or initialize IssueSlot
            var slot = issuedDao.getSlot(alarmId)
            if (slot == null) {
                val initialSlot = IssueSlot(
                    alarmId = alarmId,
                    generationId = 1L,
                    currentIssueToken = null
                )
                issuedDao.insertSlotInitial(initialSlot)
                slot = initialSlot
            }

            val newIssueToken = "issue_${alarmId}_gen_${alarm.generation}_${scheduledTargetEpochMs}_${UUID.randomUUID()}"

            // 6. Execute CAS update and MANDATORILY verify updated count
            val casUpdatedCount = issuedDao.casUpdateSlotCurrentTokenOnly(
                alarmId = alarmId,
                expectedGenId = slot.generationId,
                expectedToken = slot.currentIssueToken,
                newToken = newIssueToken
            )

            if (casUpdatedCount == 0) {
                return@withTransaction IssuanceProducerResult(
                    outcome = IssuanceProducerOutcome.CONFLICT_ALREADY_ISSUED,
                    alarmId = alarmId,
                    issuedOccurrence = null,
                    issueSlot = slot,
                    decisionReason = "IssueSlot CAS update failed due to concurrent modification"
                )
            }

            val updatedSlot = slot.copy(currentIssueToken = newIssueToken)

            // 7. Construct V35 IssuedOccurrence with complete V35 provenance
            val newOccurrence = IssuedOccurrence(
                issueToken = newIssueToken,
                alarmId = alarmId,
                scheduledAt = scheduledTargetEpochMs,
                fireId = fireId,
                generationId = updatedSlot.generationId,
                status = IssuedOccurrence.STATUS_ISSUED,
                createdAt = nowEpochMs,
                updatedAt = nowEpochMs,
                baseTriggerTime = baseTriggerTimeEpochMs,
                alarmGeneration = alarm.generation,
                authorityInstanceToken = currentFingerprint.instanceToken,
                restoreEpoch = currentFingerprint.restoreEpoch,
                scheduleRevision = currentFingerprint.scheduleRevision,
                definitionRevision = currentFingerprint.definitionRevision
            )

            issuedDao.insertOccurrence(newOccurrence)

            return@withTransaction IssuanceProducerResult(
                outcome = IssuanceProducerOutcome.ISSUED_COMMITTED,
                alarmId = alarmId,
                issuedOccurrence = newOccurrence,
                issueSlot = updatedSlot,
                decisionReason = "ISSUANCE_PROVENANCE_COMMITTED"
            )
        }
    }
}
