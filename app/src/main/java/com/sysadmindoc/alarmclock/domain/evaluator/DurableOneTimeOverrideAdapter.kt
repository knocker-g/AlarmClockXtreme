package com.sysadmindoc.alarmclock.domain.evaluator

import androidx.room.withTransaction
import com.sysadmindoc.alarmclock.data.local.AlarmDatabase
import com.sysadmindoc.alarmclock.data.local.entity.DurableOperationIntent
import com.sysadmindoc.alarmclock.data.local.entity.IssuedOccurrence
import com.sysadmindoc.alarmclock.data.local.entity.ScheduleException
import com.sysadmindoc.alarmclock.data.model.Alarm
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

enum class OneTimeOverrideOutcome {
    SUCCESS_COMMITTED,
    ALREADY_COMMITTED,
    CONFLICT_REJECTED,
    STALE_OCCURRENCE,
    INVALID_TARGET_TIME,
    ACTIVE_SESSION,
    UNSUPPORTED_SCHEDULE,
    RECONCILE_FAILED
}

data class OneTimeOverrideResult(
    val outcome: OneTimeOverrideOutcome,
    val alarmId: Long,
    val originalScheduledAt: Long,
    val updatedAlarm: Alarm?,
    val issuedOccurrence: IssuedOccurrence?,
    val durableIntent: DurableOperationIntent?,
    val decisionReason: String = ""
)

/**
 * Unified Application-level Facade and In-Transaction DB Adapter for One-time Overrides (KNO-32 P1).
 *
 * Responsibilities:
 * - Unifies "Change time once" and "Skip once" under a single entrypoint.
 * - Reuses existing [DurableSkipClaimAdapter] for Skip Once without duplicating Skip storage,
 *   precedence, generation guards, or OS scheduling.
 * - Coordinates [DurableTimeOverrideClaimCoordinator] and [DurableReconcileProducer] in a single
 *   atomic Room DB transaction for Change Time Once.
 * - Enforces invalidation contracts: pending overrides are invalidated upon alarm disable, deletion,
 *   schedule-affecting edit, or when target ceases to be the current-next occurrence.
 */
class DurableOneTimeOverrideAdapter(
    private val database: AlarmDatabase,
    private val timeOverrideCoordinator: DurableTimeOverrideClaimCoordinator = DurableTimeOverrideClaimCoordinator(database),
    private val skipClaimAdapter: DurableSkipClaimAdapter = DurableSkipClaimAdapter(database),
    private val reconcileProducer: DurableReconcileProducer = DurableReconcileProducer(database),
    private val issuanceProducer: OccurrenceIssuanceProducer = OccurrenceIssuanceProducer(database),
    private val evaluator: ScheduleOccurrenceEvaluator = ScheduleOccurrenceEvaluator()
) {
    companion object {
        const val REASON_SUCCESS = "ONE_TIME_OVERRIDE_COMMITTED"
        const val REASON_ALREADY_COMMITTED = "ONE_TIME_OVERRIDE_ALREADY_COMMITTED"
        const val REASON_CONFLICT_EXISTS = "CONFLICTING_OVERRIDE_EXISTS"
        const val REASON_UNSUPPORTED_SCHEDULE = "UNSUPPORTED_SCHEDULE_OR_DISABLED"
        const val REASON_STALE_OCCURRENCE = "STALE_OCCURRENCE_IDENTITY_MISMATCH"
        const val REASON_INVALID_TARGET_TIME = "INVALID_TARGET_TIME"
        const val REASON_ACTIVE_SESSION = "ACTIVE_FIRING_SESSION_BLOCKED"
        const val REASON_RECONCILE_FAILED = "RECONCILE_PRODUCER_FAILED"
        const val REASON_NO_OVERRIDE_TO_CANCEL = "NO_OVERRIDE_FOUND_TO_CANCEL"
    }

    /**
     * Executes Change-Time-Once for the current next occurrence of a repeating normal alarm.
     */
    suspend fun executeChangeTimeOnce(
        alarmId: Long,
        expectedScheduledAt: Long,
        newTime: LocalTime,
        nowEpochMs: Long = System.currentTimeMillis(),
        expectedBaseTriggerTimeEpochMs: Long? = null,
        holidaySnapshotProvider: (LocalDate) -> HolidaySnapshot = { HolidaySnapshot() },
        holidayPolicyOverride: HolidayPolicy? = null,
        consumedOccurrenceKeys: Set<String> = emptySet(),
        activeFiringAlarmId: Long? = null,
        evaluationZoneId: ZoneId = ZoneId.systemDefault(),
        allowHidePersonalRuntimeFeatures: Boolean = false
    ): OneTimeOverrideResult {
        return database.withTransaction {
            val alarmDao = database.alarmDao()
            val authorityDao = database.scheduleAuthorityDao()
            val exceptionDao = database.scheduleExceptionDao()
            val issuedDao = database.issuedOccurrenceDao()
            val durableDao = database.durableOperationIntentDao()

            // 1. Re-read Alarm definition
            val alarm = alarmDao.getById(alarmId)
            if (alarm == null || !alarm.isEnabled || alarm.isQuickAlarm || alarm.scheduleKind != Alarm.SCHEDULE_KIND_REPEATING) {
                return@withTransaction OneTimeOverrideResult(
                    outcome = OneTimeOverrideOutcome.UNSUPPORTED_SCHEDULE,
                    alarmId = alarmId,
                    originalScheduledAt = expectedScheduledAt,
                    updatedAlarm = null,
                    issuedOccurrence = null,
                    durableIntent = null,
                    decisionReason = REASON_UNSUPPORTED_SCHEDULE
                )
            }

            // 2. Pre-check if an existing ScheduleException is already present on this occurrence
            val baseCandidate = expectedBaseTriggerTimeEpochMs ?: expectedScheduledAt
            val existingException = exceptionDao.getExceptionForOccurrence(alarmId, baseCandidate, alarm.generation)
                ?: exceptionDao.getExceptionsForAlarmGeneration(alarmId, alarm.generation)
                    .firstOrNull { it.overrideTriggerTime == expectedScheduledAt }

            if (existingException != null) {
                val schedulingZone = alarm.schedulingZone(evaluationZoneId)
                val targetDate = java.time.Instant.ofEpochMilli(existingException.baseTriggerTime).atZone(schedulingZone).toLocalDate()
                val requestedOverrideEpoch = resolveZoned(targetDate, newTime, schedulingZone).toInstant().toEpochMilli()

                if (existingException.exceptionType == ScheduleException.TYPE_TIME_OVERRIDE &&
                    existingException.overrideTriggerTime == requestedOverrideEpoch
                ) {
                    val updatedAlarm = alarmDao.getById(alarmId) ?: alarm
                    val targetMs = updatedAlarm.nextTriggerTime
                    val occurrences = issuedDao.getOccurrencesByAlarmAndScheduledAt(alarmId, targetMs)
                    val intents = durableDao.getIntentsByAlarmId(alarmId)
                    val latestIntent = intents.lastOrNull { it.originalScheduledAt == targetMs }

                    return@withTransaction OneTimeOverrideResult(
                        outcome = OneTimeOverrideOutcome.ALREADY_COMMITTED,
                        alarmId = alarmId,
                        originalScheduledAt = expectedScheduledAt,
                        updatedAlarm = updatedAlarm,
                        issuedOccurrence = occurrences.firstOrNull(),
                        durableIntent = latestIntent,
                        decisionReason = REASON_ALREADY_COMMITTED
                    )
                } else {
                    return@withTransaction OneTimeOverrideResult(
                        outcome = OneTimeOverrideOutcome.CONFLICT_REJECTED,
                        alarmId = alarmId,
                        originalScheduledAt = expectedScheduledAt,
                        updatedAlarm = null,
                        issuedOccurrence = null,
                        durableIntent = null,
                        decisionReason = "CONFLICTING_OVERRIDE_EXISTS"
                    )
                }
            }

            // 3. Re-read ScheduleAuthority — fail closed if authority record is missing or blank
            val storedAuthority = authorityDao.getAuthorityByAlarmId(alarmId)
            if (storedAuthority == null || storedAuthority.alarmId != alarmId || storedAuthority.instanceToken.isBlank()) {
                return@withTransaction OneTimeOverrideResult(
                    outcome = OneTimeOverrideOutcome.UNSUPPORTED_SCHEDULE,
                    alarmId = alarmId,
                    originalScheduledAt = expectedScheduledAt,
                    updatedAlarm = null,
                    issuedOccurrence = null,
                    durableIntent = null,
                    decisionReason = REASON_UNSUPPORTED_SCHEDULE
                )
            }

            val fingerprint = ScheduleAuthorityFingerprint.fromAuthority(storedAuthority)

            // 4. Construct CurrentNextActionSnapshot
            val actionSnapshot = CurrentNextActionSnapshot(
                alarmId = alarmId,
                expectedScheduledTargetEpochMs = expectedScheduledAt,
                expectedBaseTriggerTimeEpochMs = expectedBaseTriggerTimeEpochMs ?: expectedScheduledAt,
                expectedGeneration = alarm.generation,
                authorityFingerprint = fingerprint
            )

            // 4. Claim Durable Time Override
            val claimResult = timeOverrideCoordinator.claimDurableTimeOverride(
                actionSnapshot = actionSnapshot,
                newTime = newTime,
                nowEpochMs = nowEpochMs,
                holidaySnapshotProvider = holidaySnapshotProvider,
                holidayPolicyOverride = holidayPolicyOverride,
                consumedOccurrenceKeys = consumedOccurrenceKeys,
                activeFiringAlarmId = activeFiringAlarmId,
                evaluationZoneId = evaluationZoneId,
                allowHidePersonalRuntimeFeatures = allowHidePersonalRuntimeFeatures
            )

            if (claimResult.outcome == DurableTimeOverrideClaimOutcome.ALREADY_COMMITTED) {
                val updatedAlarm = alarmDao.getById(alarmId)
                val targetMs = updatedAlarm?.nextTriggerTime ?: expectedScheduledAt
                val occurrences = issuedDao.getOccurrencesByAlarmAndScheduledAt(alarmId, targetMs)
                val latestOcc = occurrences.firstOrNull()
                val intents = durableDao.getIntentsByAlarmId(alarmId)
                val latestIntent = intents.lastOrNull { it.originalScheduledAt == targetMs }

                return@withTransaction OneTimeOverrideResult(
                    outcome = OneTimeOverrideOutcome.ALREADY_COMMITTED,
                    alarmId = alarmId,
                    originalScheduledAt = expectedScheduledAt,
                    updatedAlarm = updatedAlarm,
                    issuedOccurrence = latestOcc,
                    durableIntent = latestIntent,
                    decisionReason = REASON_ALREADY_COMMITTED
                )
            }

            if (claimResult.outcome != DurableTimeOverrideClaimOutcome.COMMITTED_PENDING_RECONCILE || claimResult.committedTimeOverrideException == null) {
                val mappedOutcome = when (claimResult.outcome) {
                    DurableTimeOverrideClaimOutcome.ACTIVE_SESSION -> OneTimeOverrideOutcome.ACTIVE_SESSION
                    DurableTimeOverrideClaimOutcome.INVALID_TARGET_TIME -> OneTimeOverrideOutcome.INVALID_TARGET_TIME
                    DurableTimeOverrideClaimOutcome.CONFLICT_OVERRIDE_EXISTS -> OneTimeOverrideOutcome.CONFLICT_REJECTED
                    DurableTimeOverrideClaimOutcome.UNSUPPORTED_SCHEDULE -> OneTimeOverrideOutcome.UNSUPPORTED_SCHEDULE
                    else -> OneTimeOverrideOutcome.STALE_OCCURRENCE
                }

                return@withTransaction OneTimeOverrideResult(
                    outcome = mappedOutcome,
                    alarmId = alarmId,
                    originalScheduledAt = expectedScheduledAt,
                    updatedAlarm = null,
                    issuedOccurrence = null,
                    durableIntent = null,
                    decisionReason = claimResult.decisionReason
                )
            }

            // Abort any prior unresolved intent for this alarm before producing new reconcile
            val unresolved = durableDao.getUnresolvedIntentsByAlarmId(alarmId)
            for (pending in unresolved) {
                durableDao.updateIntentPhase(pending.operationToken, DurableOperationIntent.PHASE_ABORTED)
            }

            // 5. Produce Reconcile: updates alarm.nextTriggerTime, issues occurrence, records durable intent
            val reconcileResult = reconcileProducer.produceReconcile(
                alarmId = alarmId,
                nowEpochMs = nowEpochMs,
                expectedAuthorityFingerprint = fingerprint,
                holidaySnapshotProvider = holidaySnapshotProvider,
                holidayPolicyOverride = holidayPolicyOverride,
                consumedOccurrenceKeys = consumedOccurrenceKeys,
                evaluationZoneId = evaluationZoneId,
                allowHidePersonalRuntimeFeatures = allowHidePersonalRuntimeFeatures
            )

            if (reconcileResult.outcome != DurableReconcileProducerOutcome.RECONCILE_COMMITTED_PENDING_OS || reconcileResult.updatedAlarm == null) {
                throw IllegalStateException("Reconcile producer failed inside time override transaction: ${reconcileResult.decisionReason}")
            }

            return@withTransaction OneTimeOverrideResult(
                outcome = OneTimeOverrideOutcome.SUCCESS_COMMITTED,
                alarmId = alarmId,
                originalScheduledAt = expectedScheduledAt,
                updatedAlarm = reconcileResult.updatedAlarm,
                issuedOccurrence = reconcileResult.issuedOccurrence,
                durableIntent = reconcileResult.durableIntent,
                decisionReason = REASON_SUCCESS
            )
        }
    }

    /**
     * Executes Skip-Once for the current next occurrence of a repeating normal alarm.
     * Reuses KNO-154 [DurableSkipClaimAdapter] without duplicating any skip logic.
     */
    suspend fun executeSkipOnce(
        alarmId: Long,
        expectedScheduledAt: Long,
        nowEpochMs: Long = System.currentTimeMillis(),
        holidaySnapshotProvider: (LocalDate) -> HolidaySnapshot = { HolidaySnapshot() },
        holidayPolicyOverride: HolidayPolicy? = null,
        consumedOccurrenceKeys: Set<String> = emptySet(),
        activeFiringAlarmId: Long? = null,
        evaluationZoneId: ZoneId = ZoneId.systemDefault(),
        allowHidePersonalRuntimeFeatures: Boolean = false
    ): OneTimeOverrideResult {
        // Re-read alarm to ensure it is repeating normal alarm per KNO-32 eligibility
        val alarm = database.alarmDao().getById(alarmId)
        if (alarm == null || !alarm.isEnabled || alarm.isQuickAlarm || alarm.scheduleKind != Alarm.SCHEDULE_KIND_REPEATING) {
            return OneTimeOverrideResult(
                outcome = OneTimeOverrideOutcome.UNSUPPORTED_SCHEDULE,
                alarmId = alarmId,
                originalScheduledAt = expectedScheduledAt,
                updatedAlarm = null,
                issuedOccurrence = null,
                durableIntent = null,
                decisionReason = REASON_UNSUPPORTED_SCHEDULE
            )
        }

        val skipResult = skipClaimAdapter.executeSkipClaimAndReconcile(
            alarmId = alarmId,
            expectedScheduledAt = expectedScheduledAt,
            nowEpochMs = nowEpochMs,
            holidaySnapshotProvider = holidaySnapshotProvider,
            holidayPolicyOverride = holidayPolicyOverride,
            consumedOccurrenceKeys = consumedOccurrenceKeys,
            activeFiringAlarmId = activeFiringAlarmId,
            evaluationZoneId = evaluationZoneId,
            allowHidePersonalRuntimeFeatures = allowHidePersonalRuntimeFeatures
        )

        val mappedOutcome = when (skipResult.outcome) {
            DurableSkipClaimAdapterOutcome.SUCCESS_COMMITTED -> OneTimeOverrideOutcome.SUCCESS_COMMITTED
            DurableSkipClaimAdapterOutcome.ALREADY_COMMITTED -> OneTimeOverrideOutcome.ALREADY_COMMITTED
            DurableSkipClaimAdapterOutcome.ACTIVE_SESSION -> OneTimeOverrideOutcome.ACTIVE_SESSION
            DurableSkipClaimAdapterOutcome.UNSUPPORTED_SCHEDULE -> OneTimeOverrideOutcome.UNSUPPORTED_SCHEDULE
            DurableSkipClaimAdapterOutcome.STALE_OCCURRENCE -> OneTimeOverrideOutcome.STALE_OCCURRENCE
            DurableSkipClaimAdapterOutcome.RECONCILE_FAILED -> OneTimeOverrideOutcome.RECONCILE_FAILED
        }

        return OneTimeOverrideResult(
            outcome = mappedOutcome,
            alarmId = alarmId,
            originalScheduledAt = expectedScheduledAt,
            updatedAlarm = skipResult.updatedAlarm,
            issuedOccurrence = skipResult.issuedOccurrence,
            durableIntent = skipResult.durableIntent,
            decisionReason = skipResult.decisionReason
        )
    }

    /**
     * Cancels an active One-time Override (Time Override or Skip) on the current occurrence,
     * reverting the alarm to its canonical base repeating schedule.
     */
    suspend fun cancelOverride(
        alarmId: Long,
        expectedScheduledAt: Long,
        nowEpochMs: Long = System.currentTimeMillis(),
        holidaySnapshotProvider: (LocalDate) -> HolidaySnapshot = { HolidaySnapshot() },
        holidayPolicyOverride: HolidayPolicy? = null,
        consumedOccurrenceKeys: Set<String> = emptySet(),
        evaluationZoneId: ZoneId = ZoneId.systemDefault(),
        allowHidePersonalRuntimeFeatures: Boolean = false
    ): OneTimeOverrideResult {
        return database.withTransaction {
            val alarmDao = database.alarmDao()
            val authorityDao = database.scheduleAuthorityDao()
            val exceptionDao = database.scheduleExceptionDao()
            val durableDao = database.durableOperationIntentDao()

            val alarm = alarmDao.getById(alarmId)
            if (alarm == null || !alarm.isEnabled || alarm.isQuickAlarm || alarm.scheduleKind != Alarm.SCHEDULE_KIND_REPEATING) {
                return@withTransaction OneTimeOverrideResult(
                    outcome = OneTimeOverrideOutcome.UNSUPPORTED_SCHEDULE,
                    alarmId = alarmId,
                    originalScheduledAt = expectedScheduledAt,
                    updatedAlarm = null,
                    issuedOccurrence = null,
                    durableIntent = null,
                    decisionReason = REASON_UNSUPPORTED_SCHEDULE
                )
            }

            val storedAuthority = authorityDao.getAuthorityByAlarmId(alarmId)
            if (storedAuthority == null || storedAuthority.alarmId != alarmId || storedAuthority.instanceToken.isBlank()) {
                return@withTransaction OneTimeOverrideResult(
                    outcome = OneTimeOverrideOutcome.UNSUPPORTED_SCHEDULE,
                    alarmId = alarmId,
                    originalScheduledAt = expectedScheduledAt,
                    updatedAlarm = null,
                    issuedOccurrence = null,
                    durableIntent = null,
                    decisionReason = REASON_UNSUPPORTED_SCHEDULE
                )
            }

            // Find any active exception for this alarm's current generation
            val exceptions = exceptionDao.getExceptionsForAlarmGeneration(alarmId, alarm.generation)
            if (exceptions.isEmpty()) {
                return@withTransaction OneTimeOverrideResult(
                    outcome = OneTimeOverrideOutcome.SUCCESS_COMMITTED,
                    alarmId = alarmId,
                    originalScheduledAt = expectedScheduledAt,
                    updatedAlarm = alarm,
                    issuedOccurrence = null,
                    durableIntent = null,
                    decisionReason = REASON_NO_OVERRIDE_TO_CANCEL
                )
            }

            // Delete exceptions for this alarm generation
            for (ex in exceptions) {
                exceptionDao.deleteForOccurrence(alarmId, ex.baseTriggerTime, alarm.generation)
            }

            // Restore base schedule projection
            val baseEvaluated = evaluator.evaluateOccurrence(
                alarm = alarm,
                fromEpochMs = nowEpochMs - 1000L,
                exceptions = emptyList(),
                holidaySnapshotProvider = holidaySnapshotProvider,
                holidayPolicyOverride = holidayPolicyOverride,
                consumedOccurrenceKeys = consumedOccurrenceKeys,
                evaluationZoneId = evaluationZoneId,
                allowHidePersonalRuntimeFeatures = allowHidePersonalRuntimeFeatures
            )

            val baseTargetMs = baseEvaluated?.targetTriggerTimeEpochMs ?: alarm.nextTriggerTime
            val baseTriggerMs = baseEvaluated?.baseTriggerTimeEpochMs ?: alarm.nextTriggerTime

            val updatedAlarm = alarm.copy(nextTriggerTime = baseTargetMs)
            alarmDao.update(updatedAlarm)

            val fingerprint = ScheduleAuthorityFingerprint.fromAuthority(storedAuthority)
            val issuanceResult = if (baseEvaluated != null) {
                issuanceProducer.issueOccurrence(
                    alarmId = alarmId,
                    baseTriggerTimeEpochMs = baseTriggerMs,
                    scheduledTargetEpochMs = baseTargetMs,
                    nowEpochMs = nowEpochMs,
                    expectedAuthorityFingerprint = fingerprint,
                    evaluatedOccurrence = baseEvaluated,
                    holidaySnapshotProvider = holidaySnapshotProvider,
                    holidayPolicyOverride = holidayPolicyOverride,
                    consumedOccurrenceKeys = consumedOccurrenceKeys,
                    evaluationZoneId = evaluationZoneId,
                    allowHidePersonalRuntimeFeatures = allowHidePersonalRuntimeFeatures
                )
            } else null

            // Abort any prior unresolved intent for this alarm before inserting the cancel intent
            val unresolved = durableDao.getUnresolvedIntentsByAlarmId(alarmId)
            for (pending in unresolved) {
                durableDao.updateIntentPhase(pending.operationToken, DurableOperationIntent.PHASE_ABORTED)
            }

            val durableIntent = DurableOperationIntent(
                operationToken = "cancel_override_alarm_${alarmId}_gen_${alarm.generation}_${nowEpochMs}",
                alarmId = alarmId,
                operationEpoch = nowEpochMs,
                incarnationId = storedAuthority.instanceToken,
                operationKind = "RECONCILE_RESCHEDULE",
                expectedOwnerToken = storedAuthority.instanceToken,
                expectedScheduleRevision = storedAuthority.scheduleRevision,
                expectedRestoreEpoch = storedAuthority.restoreEpoch,
                phase = DurableOperationIntent.PHASE_PENDING,
                intentRevision = alarm.revision.toLong(),
                originalScheduledAt = baseTargetMs
            )
            durableDao.insertValidatedIntent(durableIntent)

            return@withTransaction OneTimeOverrideResult(
                outcome = OneTimeOverrideOutcome.SUCCESS_COMMITTED,
                alarmId = alarmId,
                originalScheduledAt = expectedScheduledAt,
                updatedAlarm = updatedAlarm,
                issuedOccurrence = issuanceResult?.issuedOccurrence,
                durableIntent = durableIntent,
                decisionReason = "OVERRIDE_CANCELLED_REVERTED_TO_BASE"
            )
        }
    }

    /**
     * Permanently invalidates any pending exceptions for [alarmId].
     * Called on alarm disable or alarm deletion.
     */
    suspend fun invalidatePendingOverridesForAlarm(alarmId: Long) {
        database.withTransaction {
            database.scheduleExceptionDao().deleteForAlarm(alarmId)
        }
    }

    /**
     * Checks if active exceptions for [alarmId] are still bound to the live current next occurrence.
     * If the live current next occurrence has changed (e.g. an earlier occurrence became eligible,
     * or holiday policy excluded target), the obsolete exception is permanently purged.
     */
    suspend fun invalidateOverridesIfTargetChanged(
        alarmId: Long,
        nowEpochMs: Long = System.currentTimeMillis(),
        holidaySnapshotProvider: (LocalDate) -> HolidaySnapshot = { HolidaySnapshot() },
        holidayPolicyOverride: HolidayPolicy? = null,
        consumedOccurrenceKeys: Set<String> = emptySet(),
        evaluationZoneId: ZoneId = ZoneId.systemDefault(),
        allowHidePersonalRuntimeFeatures: Boolean = false
    ): Boolean {
        return database.withTransaction {
            val alarmDao = database.alarmDao()
            val exceptionDao = database.scheduleExceptionDao()

            val alarm = alarmDao.getById(alarmId) ?: return@withTransaction false
            val exceptions = exceptionDao.getExceptionsForAlarmGeneration(alarmId, alarm.generation)
            if (exceptions.isEmpty()) return@withTransaction false

            // Evaluate next occurrence on the pure baseline (without exceptions)
            val currentNextBaseline = evaluator.evaluateOccurrence(
                alarm = alarm,
                fromEpochMs = nowEpochMs,
                exceptions = emptyList(),
                holidaySnapshotProvider = holidaySnapshotProvider,
                holidayPolicyOverride = holidayPolicyOverride,
                consumedOccurrenceKeys = consumedOccurrenceKeys,
                evaluationZoneId = evaluationZoneId,
                allowHidePersonalRuntimeFeatures = allowHidePersonalRuntimeFeatures
            )

            var invalidated = false
            for (ex in exceptions) {
                // Target is valid only if currentNextBaseline exists and has exact same baseTriggerTime
                val isStillCurrentNext = currentNextBaseline != null &&
                        currentNextBaseline.baseTriggerTimeEpochMs == ex.baseTriggerTime &&
                        (currentNextBaseline.decision == OccurrenceDecision.NORMAL_TRIGGER ||
                                currentNextBaseline.decision == OccurrenceDecision.TIME_OVERRIDDEN)

                if (!isStillCurrentNext) {
                    exceptionDao.deleteForOccurrence(alarmId, ex.baseTriggerTime, ex.generation)
                    invalidated = true
                }
            }
            invalidated
        }
    }

    private fun resolveZoned(date: LocalDate, time: LocalTime, zone: ZoneId): ZonedDateTime {
        val local = LocalDateTime.of(date, time)
        val transition = zone.rules.getTransition(local)
        return if (transition != null && transition.isGap) {
            transition.instant.atZone(zone)
        } else {
            ZonedDateTime.of(date, time, zone)
        }
    }
}
