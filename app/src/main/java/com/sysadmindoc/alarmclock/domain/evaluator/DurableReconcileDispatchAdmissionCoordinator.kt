package com.sysadmindoc.alarmclock.domain.evaluator

import androidx.room.withTransaction
import com.sysadmindoc.alarmclock.data.local.AlarmDatabase
import com.sysadmindoc.alarmclock.data.local.entity.DurableOperationIntent
import com.sysadmindoc.alarmclock.domain.OsOperationOutcome
import com.sysadmindoc.alarmclock.domain.RecoveryAdjudicator
import com.sysadmindoc.alarmclock.domain.RecoveryDecisionOutcome
import com.sysadmindoc.alarmclock.domain.RecoveryEvidenceSnapshot

/**
 * In-DB Durable Reconcile Dispatch Admission & Settlement Coordinator (KNO-154 Phase 0C-B Safety Closure).
 * Evaluates dispatch admission eligibility and enforces strict settlement state machine safety in-DB
 * without invoking OS AlarmManager, creating PendingIntents, or switching scheduler ownership.
 *
 * Immutability Guarantee:
 * - [DurableOperationIntent.operationEpoch] represents record creation time and is strictly IMMUTABLE.
 * - [DurableOperationIntent.phase] transitions between PENDING, IN_FLIGHT, COMPLETED, and ABORTED without mutating creation epoch.
 */
class DurableReconcileDispatchAdmissionCoordinator(
    private val database: AlarmDatabase,
    private val adjudicator: RecoveryAdjudicator = RecoveryAdjudicator()
) {
    companion object {
        const val REASON_INTENT_NOT_FOUND = "INTENT_NOT_FOUND"
        const val REASON_DEFINITION_MISSING = "ALARM_DEFINITION_MISSING"
        const val REASON_DISABLED_OR_QUICK = "ALARM_DISABLED_OR_QUICK"
        const val REASON_MISSING_AUTHORITY = "MISSING_OR_BLANK_SCHEDULE_AUTHORITY"
        const val REASON_LEASE_ACTIVE = "IN_FLIGHT_LEASE_ACTIVE"
        const val REASON_ADMISSION_GRANTED = "DISPATCH_ADMISSION_GRANTED_IN_FLIGHT"
        const val REASON_NOT_IN_FLIGHT = "SETTLEMENT_REJECTED_NOT_IN_FLIGHT"
        const val REASON_STALE_AUTHORITY = "SETTLEMENT_REJECTED_STALE_AUTHORITY"
        const val REASON_UNCONNECTED_OS = "SETTLEMENT_REJECTED_UNCONNECTED_OS_ADAPTER"
    }

    /**
     * Evaluates whether an unresolved [DurableOperationIntent] is eligible for OS dispatch admission.
     * Transitions intent phase to [DurableOperationIntent.PHASE_IN_FLIGHT] if admission is granted.
     */
    suspend fun evaluateDispatchAdmission(
        intentToken: String,
        nowEpochMs: Long
    ): DurableDispatchResult {
        return database.withTransaction {
            val alarmDao = database.alarmDao()
            val authorityDao = database.scheduleAuthorityDao()
            val durableDao = database.durableOperationIntentDao()

            // 1. Re-read DurableOperationIntent
            val intent = durableDao.getIntentByToken(intentToken)
                ?: return@withTransaction DurableDispatchResult(
                    outcome = DurableDispatchOutcome.UNSUPPORTED_SCHEDULE,
                    alarmId = 0L,
                    intentToken = intentToken,
                    adjudicationDecision = null,
                    updatedIntent = null,
                    decisionReason = REASON_INTENT_NOT_FOUND
                )

            val alarmId = intent.alarmId

            // 2. Terminal phase safety check
            if (intent.phase == DurableOperationIntent.PHASE_COMPLETED ||
                intent.phase == DurableOperationIntent.PHASE_ABORTED ||
                intent.phase == DurableOperationIntent.PHASE_EXPIRED
            ) {
                return@withTransaction DurableDispatchResult(
                    outcome = DurableDispatchOutcome.ADMISSION_REJECTED_QUARANTINED,
                    alarmId = alarmId,
                    intentToken = intentToken,
                    adjudicationDecision = null,
                    updatedIntent = intent,
                    decisionReason = "INTENT_TERMINAL_${intent.phase}"
                )
            }

            // 3. Active IN_FLIGHT lease check (rejects duplicate concurrent dispatch)
            if (intent.phase == DurableOperationIntent.PHASE_IN_FLIGHT) {
                return@withTransaction DurableDispatchResult(
                    outcome = DurableDispatchOutcome.ADMISSION_REJECTED_IN_FLIGHT_LEASE_ACTIVE,
                    alarmId = alarmId,
                    intentToken = intentToken,
                    adjudicationDecision = null,
                    updatedIntent = intent,
                    decisionReason = REASON_LEASE_ACTIVE
                )
            }

            // 4. Re-read Alarm definition
            val alarm = alarmDao.getById(alarmId)
            if (alarm == null || !alarm.isEnabled || alarm.isQuickAlarm) {
                durableDao.updateIntentPhase(intentToken, DurableOperationIntent.PHASE_ABORTED)
                val updated = durableDao.getIntentByToken(intentToken)
                return@withTransaction DurableDispatchResult(
                    outcome = DurableDispatchOutcome.UNSUPPORTED_SCHEDULE,
                    alarmId = alarmId,
                    intentToken = intentToken,
                    adjudicationDecision = null,
                    updatedIntent = updated,
                    decisionReason = if (alarm == null) REASON_DEFINITION_MISSING else REASON_DISABLED_OR_QUICK
                )
            }

            // 5. Re-read ScheduleAuthority
            val storedAuthority = authorityDao.getAuthorityByAlarmId(alarmId)
            if (storedAuthority == null || storedAuthority.instanceToken.isBlank()) {
                durableDao.updateIntentPhase(intentToken, DurableOperationIntent.PHASE_ABORTED)
                val updated = durableDao.getIntentByToken(intentToken)
                return@withTransaction DurableDispatchResult(
                    outcome = DurableDispatchOutcome.ADMISSION_REJECTED_QUARANTINED,
                    alarmId = alarmId,
                    intentToken = intentToken,
                    adjudicationDecision = null,
                    updatedIntent = updated,
                    decisionReason = REASON_MISSING_AUTHORITY
                )
            }

            // 6. Adjudicate via RecoveryAdjudicator
            val snapshot = RecoveryEvidenceSnapshot(
                alarmId = alarmId,
                authority = storedAuthority,
                restoreEpochMeta = storedAuthority.restoreEpoch,
                durableIntent = intent,
                activeSession = null,
                localIncarnationId = storedAuthority.instanceToken,
                osOutcome = OsOperationOutcome.NOT_ATTEMPTED,
                definitionExists = true,
                evaluationTimeMillis = nowEpochMs
            )

            val decision = adjudicator.adjudicate(snapshot)

            if (decision.outcome != RecoveryDecisionOutcome.RECONCILE_AUTHORITY &&
                decision.outcome != RecoveryDecisionOutcome.NO_ACTION
            ) {
                durableDao.updateIntentPhase(intentToken, DurableOperationIntent.PHASE_ABORTED)
                val updated = durableDao.getIntentByToken(intentToken)
                return@withTransaction DurableDispatchResult(
                    outcome = DurableDispatchOutcome.ADMISSION_REJECTED_QUARANTINED,
                    alarmId = alarmId,
                    intentToken = intentToken,
                    adjudicationDecision = decision,
                    updatedIntent = updated,
                    decisionReason = decision.reasonCode
                )
            }

            // Grant admission: transition intent phase to PHASE_IN_FLIGHT (preserving creation operationEpoch immutable)
            durableDao.updateIntentPhase(intentToken, DurableOperationIntent.PHASE_IN_FLIGHT)
            val updated = durableDao.getIntentByToken(intentToken)

            return@withTransaction DurableDispatchResult(
                outcome = DurableDispatchOutcome.ADMISSION_GRANTED_IN_FLIGHT,
                alarmId = alarmId,
                intentToken = intentToken,
                adjudicationDecision = decision,
                updatedIntent = updated,
                decisionReason = REASON_ADMISSION_GRANTED
            )
        }
    }

    /**
     * Verifies settlement outcome for [intentToken].
     *
     * Fix C: Enforces that settlement is ONLY permitted for IN_FLIGHT intents. Non-IN_FLIGHT or terminal intents are rejected.
     * Fix A: Until KNO-228 trusted OS adapter attachment, un-connected ACCEPTED inputs are rejected to prevent forged PHASE_COMPLETED transitions.
     */
    suspend fun settleDispatchOutcome(
        intentToken: String,
        osOutcome: OsOperationOutcome,
        proof: DurableDispatchProof?,
        nowEpochMs: Long
    ): DurableDispatchResult {
        return database.withTransaction {
            val authorityDao = database.scheduleAuthorityDao()
            val durableDao = database.durableOperationIntentDao()

            val intent = durableDao.getIntentByToken(intentToken)
                ?: return@withTransaction DurableDispatchResult(
                    outcome = DurableDispatchOutcome.UNSUPPORTED_SCHEDULE,
                    alarmId = 0L,
                    intentToken = intentToken,
                    adjudicationDecision = null,
                    updatedIntent = null,
                    decisionReason = REASON_INTENT_NOT_FOUND
                )

            val alarmId = intent.alarmId

            // Fix C: Reject settlement for non-IN_FLIGHT intents without mutating terminal phases!
            if (intent.phase != DurableOperationIntent.PHASE_IN_FLIGHT) {
                return@withTransaction DurableDispatchResult(
                    outcome = DurableDispatchOutcome.SETTLEMENT_REJECTED_FORGED_PROOF,
                    alarmId = alarmId,
                    intentToken = intentToken,
                    adjudicationDecision = null,
                    updatedIntent = intent,
                    decisionReason = REASON_NOT_IN_FLIGHT
                )
            }

            // Fix C: Re-verify ScheduleAuthority in-transaction (checking owner token, schedule revision, and restoreEpoch)
            val storedAuthority = authorityDao.getAuthorityByAlarmId(alarmId)
            if (storedAuthority == null ||
                storedAuthority.instanceToken != intent.expectedOwnerToken ||
                storedAuthority.scheduleRevision != intent.expectedScheduleRevision ||
                storedAuthority.restoreEpoch != intent.expectedRestoreEpoch
            ) {
                durableDao.updateIntentPhase(intentToken, DurableOperationIntent.PHASE_ABORTED)
                val updated = durableDao.getIntentByToken(intentToken)
                return@withTransaction DurableDispatchResult(
                    outcome = DurableDispatchOutcome.SETTLEMENT_REJECTED_FORGED_PROOF,
                    alarmId = alarmId,
                    intentToken = intentToken,
                    adjudicationDecision = null,
                    updatedIntent = updated,
                    decisionReason = REASON_STALE_AUTHORITY
                )
            }

            // Fix A: Trusted OS adapter is not yet connected; reject ACCEPTED inputs to prevent forged PHASE_COMPLETED transitions
            durableDao.updateIntentPhase(intentToken, DurableOperationIntent.PHASE_PENDING)
            val updated = durableDao.getIntentByToken(intentToken)

            return@withTransaction DurableDispatchResult(
                outcome = DurableDispatchOutcome.SETTLEMENT_REJECTED_FORGED_PROOF,
                alarmId = alarmId,
                intentToken = intentToken,
                adjudicationDecision = null,
                updatedIntent = updated,
                decisionReason = REASON_UNCONNECTED_OS
            )
        }
    }
}
