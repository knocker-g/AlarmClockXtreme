package com.sysadmindoc.alarmclock.domain.evaluator

import androidx.room.withTransaction
import com.sysadmindoc.alarmclock.data.local.AlarmDatabase
import com.sysadmindoc.alarmclock.data.local.entity.ScheduleAuthority
import com.sysadmindoc.alarmclock.data.local.entity.ScheduleException
import com.sysadmindoc.alarmclock.data.model.Alarm
import com.sysadmindoc.alarmclock.data.model.AlarmOccurrenceId
import java.time.LocalDate
import java.time.ZoneId

/**
 * In-Transaction Durable Skip Claim Coordinator for Normal Alarms (KNO-154 Phase 0B).
 * Executes current-next action validation with [CurrentNextDecisionPlanner] inside a single
 * Room database transaction and persists `TYPE_SKIP` `ScheduleException` into DB.
 */
class DurableSkipClaimCoordinator(
    private val database: AlarmDatabase,
    private val planner: CurrentNextDecisionPlanner = CurrentNextDecisionPlanner(),
    private val evaluator: ScheduleOccurrenceEvaluator = ScheduleOccurrenceEvaluator()
) {
    /**
     * Attempts to durably claim a Skip-Once exception in a single Room Database transaction.
     */
    suspend fun claimDurableSkip(
        actionSnapshot: CurrentNextActionSnapshot,
        nowEpochMs: Long,
        holidaySnapshotProvider: (LocalDate) -> HolidaySnapshot = { HolidaySnapshot() },
        holidayPolicyOverride: HolidayPolicy? = null,
        consumedOccurrenceKeys: Set<String> = emptySet(),
        activeFiringAlarmId: Long? = null,
        evaluationZoneId: ZoneId = ZoneId.systemDefault(),
        allowHidePersonalRuntimeFeatures: Boolean = false
    ): DurableSkipClaimResult {
        return database.withTransaction {
            val alarmDao = database.alarmDao()
            val exceptionDao = database.scheduleExceptionDao()
            val authorityDao = database.scheduleAuthorityDao()

            val alarmId = actionSnapshot.alarmId

            // 1. Re-read latest Alarm definition from DB
            val latestAlarm = alarmDao.getById(alarmId)
            if (latestAlarm == null) {
                val dummyDecision = CurrentNextDecision(
                    outcome = CurrentNextDecisionOutcome.UNSUPPORTED_SCHEDULE,
                    alarmId = alarmId,
                    expectedTargetEpochMs = actionSnapshot.expectedScheduledTargetEpochMs,
                    evaluatedOccurrence = null,
                    canonicalOccurrenceId = null,
                    activeGeneration = 0,
                    activeRevision = 0,
                    isSkipActionEligible = false,
                    decisionReason = "Alarm not found in database"
                )
                return@withTransaction DurableSkipClaimResult(
                    outcome = DurableSkipClaimOutcome.UNSUPPORTED_SCHEDULE,
                    alarmId = alarmId,
                    committedSkipException = null,
                    canonicalOccurrenceId = null,
                    nextTargetTriggerTimeEpochMs = null,
                    decision = dummyDecision
                )
            }

            // 2. Re-read latest ScheduleAuthority and ScheduleExceptions for active generation from DB
            val storedAuthority = authorityDao.getAuthorityByAlarmId(alarmId)
            val exceptionsUnfiltered = exceptionDao.getExceptionsForAlarmGeneration(alarmId, latestAlarm.generation)

            // To validate an action snapshot when a TYPE_SKIP exception might ALREADY exist in DB:
            // Filter out existing TYPE_SKIP exception on actionSnapshot.expectedBaseTriggerTimeEpochMs in-memory
            // to evaluate action eligibility via CurrentNextDecisionPlanner without artificial bypass.
            val expectedBaseTime = actionSnapshot.expectedBaseTriggerTimeEpochMs
            val exceptionsForValidation = if (expectedBaseTime != null) {
                exceptionsUnfiltered.filterNot {
                    it.baseTriggerTime == expectedBaseTime && it.exceptionType == ScheduleException.TYPE_SKIP
                }
            } else {
                exceptionsUnfiltered
            }

            // 3. Re-evaluate action validation using CurrentNextDecisionPlanner inside transaction
            val decision = planner.planCurrentNext(
                alarm = latestAlarm,
                actionSnapshot = actionSnapshot,
                nowEpochMs = nowEpochMs,
                exceptions = exceptionsForValidation,
                storedAuthority = storedAuthority,
                holidaySnapshotProvider = holidaySnapshotProvider,
                holidayPolicyOverride = holidayPolicyOverride,
                consumedOccurrenceKeys = consumedOccurrenceKeys,
                activeFiringAlarmId = activeFiringAlarmId,
                evaluationZoneId = evaluationZoneId,
                allowHidePersonalRuntimeFeatures = allowHidePersonalRuntimeFeatures
            )

            // 4. Fail-Closed Check: If validation failed, reject immediately!
            if (decision.outcome != CurrentNextDecisionOutcome.CURRENT_NEXT_VALID || !decision.isSkipActionEligible || decision.evaluatedOccurrence == null) {
                val mappedOutcome = when (decision.outcome) {
                    CurrentNextDecisionOutcome.ACTIVE_SESSION -> DurableSkipClaimOutcome.ACTIVE_SESSION
                    CurrentNextDecisionOutcome.ALREADY_CONSUMED -> DurableSkipClaimOutcome.ALREADY_CONSUMED
                    CurrentNextDecisionOutcome.UNSUPPORTED_SCHEDULE -> DurableSkipClaimOutcome.UNSUPPORTED_SCHEDULE
                    CurrentNextDecisionOutcome.NO_CURRENT_OCCURRENCE -> DurableSkipClaimOutcome.NO_CURRENT_OCCURRENCE
                    CurrentNextDecisionOutcome.STALE_OCCURRENCE -> DurableSkipClaimOutcome.STALE_OCCURRENCE
                    CurrentNextDecisionOutcome.CURRENT_NEXT_VALID -> DurableSkipClaimOutcome.STALE_OCCURRENCE
                }

                return@withTransaction DurableSkipClaimResult(
                    outcome = mappedOutcome,
                    alarmId = alarmId,
                    committedSkipException = null,
                    canonicalOccurrenceId = decision.canonicalOccurrenceId,
                    nextTargetTriggerTimeEpochMs = null,
                    decision = decision
                )
            }

            // 5. Identify Canonical BASE Trigger Time
            val canonicalBaseTime = decision.evaluatedOccurrence.baseTriggerTimeEpochMs

            // 6. Check existing exceptions on canonicalBaseTime
            val existingException = exceptionDao.getExceptionForOccurrence(alarmId, canonicalBaseTime, latestAlarm.generation)
            if (existingException != null) {
                if (existingException.exceptionType == ScheduleException.TYPE_SKIP) {
                    return@withTransaction DurableSkipClaimResult(
                        outcome = DurableSkipClaimOutcome.ALREADY_COMMITTED,
                        alarmId = alarmId,
                        committedSkipException = existingException,
                        canonicalOccurrenceId = decision.canonicalOccurrenceId,
                        nextTargetTriggerTimeEpochMs = decision.evaluatedOccurrence.targetTriggerTimeEpochMs,
                        decision = decision
                    )
                } else {
                    return@withTransaction DurableSkipClaimResult(
                        outcome = DurableSkipClaimOutcome.CONFLICT_OVERRIDE_EXISTS,
                        alarmId = alarmId,
                        committedSkipException = null,
                        canonicalOccurrenceId = decision.canonicalOccurrenceId,
                        nextTargetTriggerTimeEpochMs = null,
                        decision = decision
                    )
                }
            }

            // 7. Insert new TYPE_SKIP ScheduleException
            val skipException = ScheduleException(
                alarmId = alarmId,
                baseTriggerTime = canonicalBaseTime,
                exceptionType = ScheduleException.TYPE_SKIP,
                overrideTriggerTime = 0L,
                generation = latestAlarm.generation,
                revision = latestAlarm.revision,
                createdAt = nowEpochMs
            )

            val rowId = exceptionDao.insert(skipException)
            if (rowId <= 0L) {
                val reRead = exceptionDao.getExceptionForOccurrence(alarmId, canonicalBaseTime, latestAlarm.generation)
                if (reRead != null && reRead.exceptionType == ScheduleException.TYPE_SKIP) {
                    return@withTransaction DurableSkipClaimResult(
                        outcome = DurableSkipClaimOutcome.ALREADY_COMMITTED,
                        alarmId = alarmId,
                        committedSkipException = reRead,
                        canonicalOccurrenceId = decision.canonicalOccurrenceId,
                        nextTargetTriggerTimeEpochMs = decision.evaluatedOccurrence.targetTriggerTimeEpochMs,
                        decision = decision
                    )
                } else {
                    return@withTransaction DurableSkipClaimResult(
                        outcome = DurableSkipClaimOutcome.CONFLICT_OVERRIDE_EXISTS,
                        alarmId = alarmId,
                        committedSkipException = null,
                        canonicalOccurrenceId = decision.canonicalOccurrenceId,
                        nextTargetTriggerTimeEpochMs = null,
                        decision = decision
                    )
                }
            }

            val insertedException = skipException.copy(id = rowId)

            // 8. Calculate NEXT target trigger time after skip is applied
            val updatedExceptions = exceptionsUnfiltered + insertedException
            val nextEvaluated = evaluator.evaluateOccurrence(
                alarm = latestAlarm,
                fromEpochMs = nowEpochMs,
                exceptions = updatedExceptions,
                holidaySnapshotProvider = holidaySnapshotProvider,
                holidayPolicyOverride = holidayPolicyOverride,
                consumedOccurrenceKeys = consumedOccurrenceKeys,
                evaluationZoneId = evaluationZoneId,
                allowHidePersonalRuntimeFeatures = allowHidePersonalRuntimeFeatures
            )

            val nextTargetMs = nextEvaluated?.targetTriggerTimeEpochMs

            return@withTransaction DurableSkipClaimResult(
                outcome = DurableSkipClaimOutcome.COMMITTED_PENDING_RECONCILE,
                alarmId = alarmId,
                committedSkipException = insertedException,
                canonicalOccurrenceId = decision.canonicalOccurrenceId,
                nextTargetTriggerTimeEpochMs = nextTargetMs,
                decision = decision
            )
        }
    }
}
