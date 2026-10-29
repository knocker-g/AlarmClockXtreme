package com.sysadmindoc.alarmclock.domain.evaluator

import androidx.room.withTransaction
import com.sysadmindoc.alarmclock.data.local.AlarmDatabase
import com.sysadmindoc.alarmclock.data.local.entity.DurableOperationIntent
import com.sysadmindoc.alarmclock.domain.OsOperationOutcome
import com.sysadmindoc.alarmclock.domain.RecoveryAdjudicator
import com.sysadmindoc.alarmclock.domain.RecoveryDecisionOutcome
import com.sysadmindoc.alarmclock.domain.RecoveryEvidenceSnapshot

/**
 * In-DB Durable Reconcile Dispatch Admission & Settlement Coordinator (KNO-154 Phase 0C-B Lease Attempt Fencing Closure).
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
                    decisionReason = "Intent not found in database"
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
                    decisionReason = "Intent already terminal in phase ${intent.phase}"
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
                    decisionReason = "Intent currently in-flight; duplicate admission rejected"
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
                    decisionReason = if (alarm == null) "Alarm definition missing" else "Alarm disabled or Quick"
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
                    decisionReason = "ScheduleAuthority missing or blank"
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
                decisionReason = "Dispatch admission granted; intent transitioned to PHASE_IN_FLIGHT"
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
                    decisionReason = "Intent not found in database"
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
                    decisionReason = "Settlement rejected: intent phase is ${intent.phase}, MUST be IN_FLIGHT"
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
                    decisionReason = "Stale authority detected during settlement; intent aborted"
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
                decisionReason = "OS adapter un-connected; settlement to PHASE_COMPLETED disabled until KNO-228 trusted OS adapter attachment"
            )
        }
    }
}
