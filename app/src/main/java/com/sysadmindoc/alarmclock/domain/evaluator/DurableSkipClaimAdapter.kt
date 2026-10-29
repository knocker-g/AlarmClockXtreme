package com.sysadmindoc.alarmclock.domain.evaluator

import androidx.room.withTransaction
import com.sysadmindoc.alarmclock.data.local.AlarmDatabase
import com.sysadmindoc.alarmclock.data.local.entity.DurableOperationIntent
import com.sysadmindoc.alarmclock.data.local.entity.IssuedOccurrence
import com.sysadmindoc.alarmclock.data.local.entity.ScheduleAuthority
import com.sysadmindoc.alarmclock.data.local.entity.ScheduleException
import com.sysadmindoc.alarmclock.data.model.Alarm
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

enum class DurableSkipClaimAdapterOutcome {
    SUCCESS_COMMITTED,
    ALREADY_COMMITTED,
    STALE_OCCURRENCE,
    ACTIVE_SESSION,
    UNSUPPORTED_SCHEDULE,
    RECONCILE_FAILED
}

data class DurableSkipClaimAdapterResult(
    val outcome: DurableSkipClaimAdapterOutcome,
    val alarmId: Long,
    val expectedScheduledAt: Long,
    val updatedAlarm: Alarm?,
    val issuedOccurrence: IssuedOccurrence?,
    val durableIntent: DurableOperationIntent?,
    val decisionReason: String = ""
)

/**
 * Single In-Transaction DB Writer Adapter for Skip-Next operations (KNO-154 Phase 0C-C2).
 * Encapsulates occurrence revalidation, [ScheduleException] insertion, [Alarm.nextTriggerTime] projection update,
 * V35 [IssuedOccurrence] generation, and [DurableOperationIntent] creation into a single atomic Room DB transaction.
 *
 * Prevents double DB writes, competing CAS queries, and stale occurrence identity updates between
 * receivers, viewmodels, and the domain scheduling pipeline.
 */
class DurableSkipClaimAdapter(
    private val database: AlarmDatabase,
    private val skipClaimCoordinator: DurableSkipClaimCoordinator = DurableSkipClaimCoordinator(database),
    private val reconcileProducer: DurableReconcileProducer = DurableReconcileProducer(database)
) {
    companion object {
        const val REASON_SUCCESS = "SKIP_CLAIM_AND_RECONCILE_COMMITTED"
        const val REASON_ALREADY_COMMITTED = "SKIP_EXCEPTION_ALREADY_COMMITTED"
        const val REASON_STALE_OCCURRENCE = "STALE_OCCURRENCE_IDENTITY_MISMATCH"
        const val REASON_ACTIVE_SESSION = "ACTIVE_FIRING_SESSION_BLOCKED"
        const val REASON_UNSUPPORTED_SCHEDULE = "UNSUPPORTED_SCHEDULE_OR_DISABLED"
        const val REASON_RECONCILE_FAILED = "RECONCILE_PRODUCER_FAILED"
    }

    /**
     * Atomically executes Skip-Next claim and reconciliation in a single Room DB transaction.
     */
    suspend fun executeSkipClaimAndReconcile(
        alarmId: Long,
        expectedScheduledAt: Long,
        nowEpochMs: Long,
        holidaySnapshotProvider: (LocalDate) -> HolidaySnapshot = { HolidaySnapshot() },
        holidayPolicyOverride: HolidayPolicy? = null,
        consumedOccurrenceKeys: Set<String> = emptySet(),
        activeFiringAlarmId: Long? = null,
        evaluationZoneId: ZoneId = ZoneId.systemDefault(),
        allowHidePersonalRuntimeFeatures: Boolean = false
    ): DurableSkipClaimAdapterResult {
        return database.withTransaction {
            val alarmDao = database.alarmDao()
            val authorityDao = database.scheduleAuthorityDao()
            val exceptionDao = database.scheduleExceptionDao()
            val issuedDao = database.issuedOccurrenceDao()
            val durableDao = database.durableOperationIntentDao()

            // 1. Re-read Alarm definition
            val alarm = alarmDao.getById(alarmId)
            if (alarm == null || !alarm.isEnabled || alarm.isQuickAlarm) {
                return@withTransaction DurableSkipClaimAdapterResult(
                    outcome = DurableSkipClaimAdapterOutcome.UNSUPPORTED_SCHEDULE,
                    alarmId = alarmId,
                    expectedScheduledAt = expectedScheduledAt,
                    updatedAlarm = null,
                    issuedOccurrence = null,
                    durableIntent = null,
                    decisionReason = REASON_UNSUPPORTED_SCHEDULE
                )
            }

            // 2. Pre-check if a TYPE_SKIP ScheduleException is ALREADY committed for this occurrence identity
            val existingException = exceptionDao.getExceptionForOccurrence(alarmId, expectedScheduledAt, alarm.generation)
            if (existingException != null && existingException.exceptionType == ScheduleException.TYPE_SKIP) {
                val occurrences = issuedDao.getOccurrencesByAlarmAndScheduledAt(alarmId, alarm.nextTriggerTime)
                val latestOcc = occurrences.firstOrNull()
                val intents = durableDao.getIntentsByAlarmId(alarmId)
                val latestIntent = intents.lastOrNull { it.originalScheduledAt == alarm.nextTriggerTime }

                return@withTransaction DurableSkipClaimAdapterResult(
                    outcome = DurableSkipClaimAdapterOutcome.ALREADY_COMMITTED,
                    alarmId = alarmId,
                    expectedScheduledAt = expectedScheduledAt,
                    updatedAlarm = alarm,
                    issuedOccurrence = latestOcc,
                    durableIntent = latestIntent,
                    decisionReason = REASON_ALREADY_COMMITTED
                )
            }

            // 3. Re-read ScheduleAuthority & auto-bootstrap if missing for legacy alarm
            var storedAuthority = authorityDao.getAuthorityByAlarmId(alarmId)
            if (storedAuthority == null || storedAuthority.alarmId != alarmId || storedAuthority.instanceToken.isBlank()) {
                val autoAuth = ScheduleAuthority(
                    alarmId = alarmId,
                    instanceToken = "auth_${alarmId}_gen_${alarm.generation}_${UUID.randomUUID()}",
                    definitionRevision = alarm.revision.toLong(),
                    scheduleRevision = alarm.revision.toLong(),
                    lifecycle = if (alarm.isEnabled) "LIVE" else "DISABLED",
                    desiredOperation = "SET",
                    desiredTriggerMillis = alarm.nextTriggerTime,
                    reconcileState = "SETTLED",
                    restoreEpoch = nowEpochMs,
                    actionKey = null
                )
                authorityDao.insertAuthority(autoAuth)
                storedAuthority = autoAuth
            }

            val fingerprint = ScheduleAuthorityFingerprint.fromAuthority(storedAuthority)

            // Clamp evaluation time horizon to ensure expectedScheduledAt is evaluated as active current occurrence
            val evalNowMs = if (nowEpochMs > expectedScheduledAt) expectedScheduledAt - 1000L else nowEpochMs

            // 4. Construct CurrentNextActionSnapshot for Skip-Once action
            val actionSnapshot = CurrentNextActionSnapshot(
                alarmId = alarmId,
                expectedScheduledTargetEpochMs = expectedScheduledAt,
                expectedBaseTriggerTimeEpochMs = expectedScheduledAt,
                expectedGeneration = alarm.generation,
                authorityFingerprint = fingerprint
            )

            // 5. Claim Durable Skip in Coordinator
            val claimResult = skipClaimCoordinator.claimDurableSkip(
                actionSnapshot = actionSnapshot,
                nowEpochMs = evalNowMs,
                holidaySnapshotProvider = holidaySnapshotProvider,
                holidayPolicyOverride = holidayPolicyOverride,
                consumedOccurrenceKeys = consumedOccurrenceKeys,
                activeFiringAlarmId = activeFiringAlarmId,
                evaluationZoneId = evaluationZoneId,
                allowHidePersonalRuntimeFeatures = allowHidePersonalRuntimeFeatures
            )

            if (claimResult.outcome == DurableSkipClaimOutcome.ALREADY_COMMITTED) {
                val updatedAlarm = alarmDao.getById(alarmId)
                val targetMs = updatedAlarm?.nextTriggerTime ?: expectedScheduledAt
                val occurrences = issuedDao.getOccurrencesByAlarmAndScheduledAt(alarmId, targetMs)
                val latestOcc = occurrences.firstOrNull()
                val intents = durableDao.getIntentsByAlarmId(alarmId)
                val latestIntent = intents.lastOrNull { it.originalScheduledAt == targetMs }

                return@withTransaction DurableSkipClaimAdapterResult(
                    outcome = DurableSkipClaimAdapterOutcome.ALREADY_COMMITTED,
                    alarmId = alarmId,
                    expectedScheduledAt = expectedScheduledAt,
                    updatedAlarm = updatedAlarm,
                    issuedOccurrence = latestOcc,
                    durableIntent = latestIntent,
                    decisionReason = REASON_ALREADY_COMMITTED
                )
            }

            if (claimResult.outcome != DurableSkipClaimOutcome.COMMITTED_PENDING_RECONCILE || claimResult.committedSkipException == null) {
                val mappedOutcome = when (claimResult.outcome) {
                    DurableSkipClaimOutcome.ACTIVE_SESSION -> DurableSkipClaimAdapterOutcome.ACTIVE_SESSION
                    DurableSkipClaimOutcome.ALREADY_CONSUMED -> DurableSkipClaimAdapterOutcome.STALE_OCCURRENCE
                    DurableSkipClaimOutcome.STALE_OCCURRENCE -> DurableSkipClaimAdapterOutcome.STALE_OCCURRENCE
                    DurableSkipClaimOutcome.NO_CURRENT_OCCURRENCE -> DurableSkipClaimAdapterOutcome.STALE_OCCURRENCE
                    else -> DurableSkipClaimAdapterOutcome.UNSUPPORTED_SCHEDULE
                }

                return@withTransaction DurableSkipClaimAdapterResult(
                    outcome = mappedOutcome,
                    alarmId = alarmId,
                    expectedScheduledAt = expectedScheduledAt,
                    updatedAlarm = null,
                    issuedOccurrence = null,
                    durableIntent = null,
                    decisionReason = claimResult.decision.decisionReason
                )
            }

            // 6. Produce Reconcile
            val reconcileResult = reconcileProducer.produceReconcile(
                alarmId = alarmId,
                nowEpochMs = evalNowMs,
                expectedAuthorityFingerprint = fingerprint,
                holidaySnapshotProvider = holidaySnapshotProvider,
                holidayPolicyOverride = holidayPolicyOverride,
                consumedOccurrenceKeys = consumedOccurrenceKeys,
                evaluationZoneId = evaluationZoneId,
                allowHidePersonalRuntimeFeatures = allowHidePersonalRuntimeFeatures
            )

            if (reconcileResult.outcome != DurableReconcileProducerOutcome.RECONCILE_COMMITTED_PENDING_OS || reconcileResult.updatedAlarm == null) {
                android.util.Log.e("DEBUG_RECONCILE", "reconcileResult.outcome=${reconcileResult.outcome}, reason=${reconcileResult.decisionReason}")
                throw IllegalStateException("Reconcile producer failed inside skip claim transaction: ${reconcileResult.decisionReason}")
            }

            return@withTransaction DurableSkipClaimAdapterResult(
                outcome = DurableSkipClaimAdapterOutcome.SUCCESS_COMMITTED,
                alarmId = alarmId,
                expectedScheduledAt = expectedScheduledAt,
                updatedAlarm = reconcileResult.updatedAlarm,
                issuedOccurrence = reconcileResult.issuedOccurrence,
                durableIntent = reconcileResult.durableIntent,
                decisionReason = REASON_SUCCESS
            )
        }
    }
}
