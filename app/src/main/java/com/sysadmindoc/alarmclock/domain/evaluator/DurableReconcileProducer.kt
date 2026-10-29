package com.sysadmindoc.alarmclock.domain.evaluator

import androidx.room.withTransaction
import com.sysadmindoc.alarmclock.data.local.AlarmDatabase
import com.sysadmindoc.alarmclock.data.local.entity.DurableOperationIntent
import com.sysadmindoc.alarmclock.data.model.Alarm
import java.time.LocalDate
import java.time.ZoneId

/**
 * In-Transaction Durable Reconcile Producer (KNO-154 Phase 0C-B Final Interface Closure).
 * Consumes [ReconcileDecision], updates DB stored projection [Alarm.nextTriggerTime],
 * produces a new V35 [IssuedOccurrence], and commits a [DurableOperationIntent] record
 * for OS rescheduling dispatch inside a single transaction.
 *
 * Enforces atomic rollback on issuance failure (Item B), unverified OS state classification (Item D),
 * canonical ScheduleAuthority incarnation binding (Modification A), and symmetrical context parameter propagation.
 */
class DurableReconcileProducer(
    private val database: AlarmDatabase,
    private val reconcileVerifier: ReconcileProvenanceVerifier = ReconcileProvenanceVerifier(database),
    private val issuanceProducer: OccurrenceIssuanceProducer = OccurrenceIssuanceProducer(database),
    private val occurrenceEvaluator: ScheduleOccurrenceEvaluator = ScheduleOccurrenceEvaluator()
) {
    companion object {
        const val REASON_NOT_FOUND = "ALARM_NOT_FOUND"
        const val REASON_DISABLED_OR_QUICK = "ALARM_DISABLED_OR_QUICK"
        const val REASON_MISSING_AUTHORITY = "MISSING_OR_INVALID_SCHEDULE_AUTHORITY"
        const val REASON_UNVERIFIED_OS = "PROJECTION_MATCHES_UNVERIFIED_OS_STATE"
        const val REASON_EVALUATION_NULL = "RECONCILE_EVALUATION_NULL"
        const val REASON_RECONCILE_COMMITTED = "RECONCILE_PROVENANCE_COMMITTED"
    }

    /**
     * Executes reconciliation production for [alarmId] at [nowEpochMs].
     */
    suspend fun produceReconcile(
        alarmId: Long,
        nowEpochMs: Long,
        expectedAuthorityFingerprint: ScheduleAuthorityFingerprint? = null,
        holidaySnapshotProvider: (LocalDate) -> HolidaySnapshot = { HolidaySnapshot() },
        holidayPolicyOverride: HolidayPolicy? = null,
        consumedOccurrenceKeys: Set<String> = emptySet(),
        evaluationZoneId: ZoneId = ZoneId.systemDefault(),
        allowHidePersonalRuntimeFeatures: Boolean = false
    ): DurableReconcileProducerResult {
        return database.withTransaction {
            val alarmDao = database.alarmDao()
            val authorityDao = database.scheduleAuthorityDao()
            val exceptionDao = database.scheduleExceptionDao()
            val durableDao = database.durableOperationIntentDao()

            // 1. Re-read Alarm definition
            val alarm = alarmDao.getById(alarmId)
            if (alarm == null || !alarm.isEnabled || alarm.isQuickAlarm) {
                return@withTransaction DurableReconcileProducerResult(
                    outcome = DurableReconcileProducerOutcome.UNSUPPORTED_SCHEDULE,
                    alarmId = alarmId,
                    reconcileDecision = null,
                    updatedAlarm = null,
                    issuedOccurrence = null,
                    durableIntent = null,
                    decisionReason = if (alarm == null) REASON_NOT_FOUND else REASON_DISABLED_OR_QUICK
                )
            }

            // 2. Modification A: Re-read ScheduleAuthority to bind authentic owner identity
            val storedAuthority = authorityDao.getAuthorityByAlarmId(alarmId)
            if (storedAuthority == null || storedAuthority.alarmId != alarmId || storedAuthority.instanceToken.isBlank()) {
                return@withTransaction DurableReconcileProducerResult(
                    outcome = DurableReconcileProducerOutcome.STALE_REJECTED,
                    alarmId = alarmId,
                    reconcileDecision = null,
                    updatedAlarm = null,
                    issuedOccurrence = null,
                    durableIntent = null,
                    decisionReason = REASON_MISSING_AUTHORITY
                )
            }

            // 3. Evaluate reconciliation decision
            val decision = reconcileVerifier.verifyAndPlanReconcile(
                alarmId = alarmId,
                nowEpochMs = nowEpochMs,
                holidaySnapshotProvider = holidaySnapshotProvider,
                holidayPolicyOverride = holidayPolicyOverride,
                consumedOccurrenceKeys = consumedOccurrenceKeys,
                evaluationZoneId = evaluationZoneId,
                allowHidePersonalRuntimeFeatures = allowHidePersonalRuntimeFeatures
            )

            if (decision.outcome == ReconcileOutcome.PROJECTION_UPDATED_UNVERIFIED_OS) {
                return@withTransaction DurableReconcileProducerResult(
                    outcome = DurableReconcileProducerOutcome.UNVERIFIED_OS_STATE_PENDING_RETRY,
                    alarmId = alarmId,
                    reconcileDecision = decision,
                    updatedAlarm = alarm,
                    issuedOccurrence = null,
                    durableIntent = null,
                    decisionReason = REASON_UNVERIFIED_OS
                )
            }

            if (decision.outcome != ReconcileOutcome.PROJECTION_DIFFERS_RECONCILE_REQUIRED || decision.evaluatedPostSkipNextTargetEpochMs == null) {
                val outcome = when (decision.outcome) {
                    ReconcileOutcome.STALE_GENERATION -> DurableReconcileProducerOutcome.STALE_REJECTED
                    ReconcileOutcome.MISSING_AUTHORITY -> DurableReconcileProducerOutcome.STALE_REJECTED
                    ReconcileOutcome.UNSUPPORTED_SCHEDULE -> DurableReconcileProducerOutcome.UNSUPPORTED_SCHEDULE
                    else -> DurableReconcileProducerOutcome.STALE_REJECTED
                }

                return@withTransaction DurableReconcileProducerResult(
                    outcome = outcome,
                    alarmId = alarmId,
                    reconcileDecision = decision,
                    updatedAlarm = null,
                    issuedOccurrence = null,
                    durableIntent = null,
                    decisionReason = decision.decisionReason
                )
            }

            val newPostSkipTargetMs = decision.evaluatedPostSkipNextTargetEpochMs

            // 4. Evaluate canonical occurrence BEFORE updating alarm stored projection
            val updatedAlarmCandidate = alarm.copy(nextTriggerTime = newPostSkipTargetMs)
            val exceptions = exceptionDao.getExceptionsForAlarmGeneration(alarmId, alarm.generation)
            val evaluatedOcc = occurrenceEvaluator.evaluateOccurrence(
                alarm = updatedAlarmCandidate,
                fromEpochMs = nowEpochMs - 1000L,
                exceptions = exceptions,
                holidaySnapshotProvider = holidaySnapshotProvider,
                holidayPolicyOverride = holidayPolicyOverride,
                consumedOccurrenceKeys = consumedOccurrenceKeys,
                evaluationZoneId = evaluationZoneId,
                allowHidePersonalRuntimeFeatures = allowHidePersonalRuntimeFeatures
            )

            // Require evaluatedOcc and evaluatedOcc.occurrenceId non-null BEFORE updating DB!
            if (evaluatedOcc == null || evaluatedOcc.occurrenceId == null) {
                return@withTransaction DurableReconcileProducerResult(
                    outcome = DurableReconcileProducerOutcome.UNSUPPORTED_SCHEDULE,
                    alarmId = alarmId,
                    reconcileDecision = decision,
                    updatedAlarm = null,
                    issuedOccurrence = null,
                    durableIntent = null,
                    decisionReason = REASON_EVALUATION_NULL
                )
            }

            val baseTriggerTimeEpochMs = evaluatedOcc.baseTriggerTimeEpochMs

            // 5. Update DB stored projection alarm.nextTriggerTime
            val updatedAlarm = updatedAlarmCandidate
            alarmDao.update(updatedAlarm)

            // 6. Modification A: Issue new occurrence with V35 provenance, propagating ALL 5 evaluation context parameters symmetrically!
            val issuanceResult = issuanceProducer.issueOccurrence(
                alarmId = alarmId,
                baseTriggerTimeEpochMs = baseTriggerTimeEpochMs,
                scheduledTargetEpochMs = newPostSkipTargetMs,
                nowEpochMs = nowEpochMs,
                expectedAuthorityFingerprint = expectedAuthorityFingerprint ?: ScheduleAuthorityFingerprint.fromAuthority(storedAuthority),
                evaluatedOccurrence = evaluatedOcc,
                holidaySnapshotProvider = holidaySnapshotProvider,
                holidayPolicyOverride = holidayPolicyOverride,
                consumedOccurrenceKeys = consumedOccurrenceKeys,
                evaluationZoneId = evaluationZoneId,
                allowHidePersonalRuntimeFeatures = allowHidePersonalRuntimeFeatures
            )

            if (issuanceResult.outcome != IssuanceProducerOutcome.ISSUED_COMMITTED || issuanceResult.issuedOccurrence == null) {
                throw IllegalStateException("Reconcile issuance producer failed: ${issuanceResult.decisionReason}")
            }

            // 7. Create DurableOperationIntent with authentic incarnationId = storedAuthority.instanceToken
            val durableIntent = DurableOperationIntent(
                operationToken = "reconcile_alarm_${alarmId}_gen_${alarm.generation}_rev_${alarm.revision}_${nowEpochMs}",
                alarmId = alarmId,
                operationEpoch = nowEpochMs,
                incarnationId = storedAuthority.instanceToken, // Bound to storedAuthority.instanceToken per RecoveryAdjudicator.kt:96,114
                operationKind = "RECONCILE_RESCHEDULE",
                expectedOwnerToken = storedAuthority.instanceToken,
                expectedScheduleRevision = storedAuthority.scheduleRevision,
                expectedRestoreEpoch = storedAuthority.restoreEpoch,
                phase = DurableOperationIntent.PHASE_PENDING,
                intentRevision = alarm.revision.toLong(),
                originalScheduledAt = newPostSkipTargetMs
            )

            durableDao.insertValidatedIntent(durableIntent)

            return@withTransaction DurableReconcileProducerResult(
                outcome = DurableReconcileProducerOutcome.RECONCILE_COMMITTED_PENDING_OS,
                alarmId = alarmId,
                reconcileDecision = decision,
                updatedAlarm = updatedAlarm,
                issuedOccurrence = issuanceResult.issuedOccurrence,
                durableIntent = durableIntent,
                decisionReason = REASON_RECONCILE_COMMITTED
            )
        }
    }
}
