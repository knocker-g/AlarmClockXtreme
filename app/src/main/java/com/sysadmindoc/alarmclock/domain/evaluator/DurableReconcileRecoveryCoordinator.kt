package com.sysadmindoc.alarmclock.domain.evaluator

import androidx.room.withTransaction
import com.sysadmindoc.alarmclock.data.local.AlarmDatabase
import com.sysadmindoc.alarmclock.data.local.entity.DurableOperationIntent
import com.sysadmindoc.alarmclock.domain.OsOperationOutcome
import com.sysadmindoc.alarmclock.domain.RecoveryAdjudicator
import com.sysadmindoc.alarmclock.domain.RecoveryDecisionOutcome
import com.sysadmindoc.alarmclock.domain.RecoveryEvidenceSnapshot

/**
 * In-DB Durable Reconcile & Recovery Coordinator (KNO-154 Phase 0C-B Subtask 3 OS Settlement Safety Closure).
 * Performs in-database recovery adjudication and fencing for unresolved [DurableOperationIntent] records
 * without invoking OS AlarmManager, creating PendingIntents, or switching scheduler ownership.
 *
 * Enforces OS Settlement Safety: Does NOT transition intents to PHASE_COMPLETED based solely on DB IssuedOccurrence
 * or un-connected caller ACCEPTED inputs until KNO-228 trusted OS adapter attachment.
 */
class DurableReconcileRecoveryCoordinator(
    private val database: AlarmDatabase,
    private val adjudicator: RecoveryAdjudicator = RecoveryAdjudicator()
) {
    companion object {
        const val REASON_INTENT_NOT_FOUND = "INTENT_NOT_FOUND"
        const val REASON_DEFINITION_MISSING = "ALARM_DEFINITION_MISSING"
        const val REASON_DISABLED_OR_QUICK = "ALARM_DISABLED_OR_QUICK"
        const val REASON_MISSING_AUTHORITY = "MISSING_OR_BLANK_SCHEDULE_AUTHORITY"
        const val REASON_OS_CONFIRMED = "OS_REGISTRATION_CONFIRMED_COMPLETED"
        const val REASON_OS_PENDING = "OS_REGISTRATION_PENDING_RETRY"
    }

    /**
     * Recovers and adjudicates an unresolved [DurableOperationIntent] by its [intentToken].
     */
    suspend fun recoverUnresolvedIntent(
        intentToken: String,
        nowEpochMs: Long,
        osOutcome: OsOperationOutcome = OsOperationOutcome.NOT_ATTEMPTED
    ): DurableRecoveryResult {
        return database.withTransaction {
            val alarmDao = database.alarmDao()
            val authorityDao = database.scheduleAuthorityDao()
            val durableDao = database.durableOperationIntentDao()

            // 1. Re-read DurableOperationIntent
            val intent = durableDao.getIntentByToken(intentToken)
                ?: return@withTransaction DurableRecoveryResult(
                    outcome = DurableRecoveryOutcome.UNSUPPORTED_SCHEDULE,
                    alarmId = 0L,
                    intentToken = intentToken,
                    adjudicationDecision = null,
                    updatedIntent = null,
                    decisionReason = REASON_INTENT_NOT_FOUND
                )

            val alarmId = intent.alarmId

            // 2. Check if intent is already resolved
            if (!DurableOperationIntent.isUnresolvedPhase(intent.phase)) {
                return@withTransaction DurableRecoveryResult(
                    outcome = DurableRecoveryOutcome.ALREADY_SETTLED_IDEMPOTENT,
                    alarmId = alarmId,
                    intentToken = intentToken,
                    adjudicationDecision = null,
                    updatedIntent = intent,
                    decisionReason = "INTENT_ALREADY_RESOLVED_${intent.phase}"
                )
            }

            // 3. Re-read Alarm definition
            val alarm = alarmDao.getById(alarmId)
            if (alarm == null || !alarm.isEnabled || alarm.isQuickAlarm) {
                durableDao.updateIntentPhase(intentToken, DurableOperationIntent.PHASE_ABORTED)
                val updated = durableDao.getIntentByToken(intentToken)
                return@withTransaction DurableRecoveryResult(
                    outcome = DurableRecoveryOutcome.UNSUPPORTED_SCHEDULE,
                    alarmId = alarmId,
                    intentToken = intentToken,
                    adjudicationDecision = null,
                    updatedIntent = updated,
                    decisionReason = if (alarm == null) REASON_DEFINITION_MISSING else REASON_DISABLED_OR_QUICK
                )
            }

            // 4. Re-read ScheduleAuthority
            val storedAuthority = authorityDao.getAuthorityByAlarmId(alarmId)
            if (storedAuthority == null || storedAuthority.instanceToken.isBlank()) {
                durableDao.updateIntentPhase(intentToken, DurableOperationIntent.PHASE_ABORTED)
                val updated = durableDao.getIntentByToken(intentToken)
                return@withTransaction DurableRecoveryResult(
                    outcome = DurableRecoveryOutcome.INTENT_QUARANTINED_ABORTED,
                    alarmId = alarmId,
                    intentToken = intentToken,
                    adjudicationDecision = null,
                    updatedIntent = updated,
                    decisionReason = REASON_MISSING_AUTHORITY
                )
            }

            // 5. Adjudicate via RecoveryAdjudicator
            val snapshot = RecoveryEvidenceSnapshot(
                alarmId = alarmId,
                authority = storedAuthority,
                restoreEpochMeta = storedAuthority.restoreEpoch,
                durableIntent = intent,
                activeSession = null,
                localIncarnationId = storedAuthority.instanceToken,
                osOutcome = osOutcome,
                definitionExists = true,
                evaluationTimeMillis = nowEpochMs
            )

            val decision = adjudicator.adjudicate(snapshot)

            when (decision.outcome) {
                RecoveryDecisionOutcome.QUARANTINE -> {
                    durableDao.updateIntentPhase(intentToken, DurableOperationIntent.PHASE_ABORTED)
                    val updated = durableDao.getIntentByToken(intentToken)
                    return@withTransaction DurableRecoveryResult(
                        outcome = DurableRecoveryOutcome.INTENT_QUARANTINED_ABORTED,
                        alarmId = alarmId,
                        intentToken = intentToken,
                        adjudicationDecision = decision,
                        updatedIntent = updated,
                        decisionReason = decision.reasonCode
                    )
                }
                RecoveryDecisionOutcome.NEEDS_EVIDENCE -> {
                    return@withTransaction DurableRecoveryResult(
                        outcome = DurableRecoveryOutcome.NEEDS_EVIDENCE_DEFERRED,
                        alarmId = alarmId,
                        intentToken = intentToken,
                        adjudicationDecision = decision,
                        updatedIntent = intent,
                        decisionReason = decision.reasonCode
                    )
                }
                else -> {
                    // OS Settlement Safety: Until KNO-228 trusted OS adapter attachment, keep PHASE_PENDING for retry eligibility
                    return@withTransaction DurableRecoveryResult(
                        outcome = DurableRecoveryOutcome.RECOVERY_DISPATCH_ELIGIBLE,
                        alarmId = alarmId,
                        intentToken = intentToken,
                        adjudicationDecision = decision,
                        updatedIntent = intent,
                        decisionReason = REASON_OS_PENDING
                    )
                }
            }
        }
    }
}
