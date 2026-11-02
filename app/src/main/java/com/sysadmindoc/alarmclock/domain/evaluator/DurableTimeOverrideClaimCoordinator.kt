package com.sysadmindoc.alarmclock.domain.evaluator

import androidx.room.withTransaction
import com.sysadmindoc.alarmclock.data.local.AlarmDatabase
import com.sysadmindoc.alarmclock.data.local.entity.ScheduleException
import com.sysadmindoc.alarmclock.data.model.Alarm
import com.sysadmindoc.alarmclock.data.model.AlarmOccurrenceId
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

enum class DurableTimeOverrideClaimOutcome {
    COMMITTED_PENDING_RECONCILE,
    ALREADY_COMMITTED,
    CONFLICT_OVERRIDE_EXISTS,
    STALE_OCCURRENCE,
    INVALID_TARGET_TIME,
    ACTIVE_SESSION,
    UNSUPPORTED_SCHEDULE
}

data class DurableTimeOverrideClaimResult(
    val outcome: DurableTimeOverrideClaimOutcome,
    val alarmId: Long,
    val committedTimeOverrideException: ScheduleException?,
    val canonicalOccurrenceId: AlarmOccurrenceId?,
    val targetTriggerTimeEpochMs: Long?,
    val decision: CurrentNextDecision?,
    val decisionReason: String = ""
)

/**
 * In-Transaction Durable Time Override Claim Coordinator for Normal Alarms (KNO-32 P1).
 * Executes current-next action validation on REPEATING Normal Alarms with [CurrentNextDecisionPlanner]
 * inside a single Room database transaction and persists `TYPE_TIME_OVERRIDE` `ScheduleException` into DB.
 *
 * Enforces:
 * - Target eligibility: REPEATING Normal Alarms only (ONE_TIME and Quick alarms rejected).
 * - Schedule date preservation: Target schedule-local date is preserved; implicit midnight/day rollover rejected.
 * - Past datetime rejection: Mutated datetime at commit time must be in the future.
 * - Idempotency: Repeated identical time-override requests return ALREADY_COMMITTED.
 * - Conflict detection: Conflicting existing overrides (Skip or different time) return CONFLICT_OVERRIDE_EXISTS.
 */
class DurableTimeOverrideClaimCoordinator(
    private val database: AlarmDatabase,
    private val planner: CurrentNextDecisionPlanner = CurrentNextDecisionPlanner(),
    private val evaluator: ScheduleOccurrenceEvaluator = ScheduleOccurrenceEvaluator()
) {
    companion object {
        const val REASON_UNSUPPORTED_SCHEDULE = "ONLY_REPEATING_NORMAL_ALARMS_ELIGIBLE"
        const val REASON_ACTIVE_SESSION = "ACTIVE_FIRING_SESSION_BLOCKED"
        const val REASON_INVALID_DATE_ROLLOVER = "TARGET_LOCAL_DATE_ROLLOVER_REJECTED"
        const val REASON_PAST_TARGET_TIME = "TARGET_TIME_IN_PAST_AT_COMMIT"
        const val REASON_ALREADY_COMMITTED = "TIME_OVERRIDE_ALREADY_COMMITTED"
        const val REASON_CONFLICT_EXISTS = "CONFLICTING_SCHEDULE_EXCEPTION_EXISTS"
        const val REASON_COMMITTED = "TIME_OVERRIDE_COMMITTED_PENDING_RECONCILE"
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

    suspend fun claimDurableTimeOverride(
        actionSnapshot: CurrentNextActionSnapshot,
        newTime: LocalTime,
        nowEpochMs: Long,
        holidaySnapshotProvider: (LocalDate) -> HolidaySnapshot = { HolidaySnapshot() },
        holidayPolicyOverride: HolidayPolicy? = null,
        consumedOccurrenceKeys: Set<String> = emptySet(),
        activeFiringAlarmId: Long? = null,
        evaluationZoneId: ZoneId = ZoneId.systemDefault(),
        allowHidePersonalRuntimeFeatures: Boolean = false
    ): DurableTimeOverrideClaimResult {
        return database.withTransaction {
            val alarmDao = database.alarmDao()
            val exceptionDao = database.scheduleExceptionDao()
            val authorityDao = database.scheduleAuthorityDao()

            val alarmId = actionSnapshot.alarmId

            // 1. Re-read latest Alarm definition from DB
            val latestAlarm = alarmDao.getById(alarmId)
            if (latestAlarm == null || !latestAlarm.isEnabled || latestAlarm.isQuickAlarm) {
                return@withTransaction DurableTimeOverrideClaimResult(
                    outcome = DurableTimeOverrideClaimOutcome.UNSUPPORTED_SCHEDULE,
                    alarmId = alarmId,
                    committedTimeOverrideException = null,
                    canonicalOccurrenceId = null,
                    targetTriggerTimeEpochMs = null,
                    decision = null,
                    decisionReason = REASON_UNSUPPORTED_SCHEDULE
                )
            }

            // 2. Strict KNO-32 Lock: Only REPEATING Normal Alarms are eligible for One-time Override
            if (latestAlarm.scheduleKind != Alarm.SCHEDULE_KIND_REPEATING) {
                return@withTransaction DurableTimeOverrideClaimResult(
                    outcome = DurableTimeOverrideClaimOutcome.UNSUPPORTED_SCHEDULE,
                    alarmId = alarmId,
                    committedTimeOverrideException = null,
                    canonicalOccurrenceId = null,
                    targetTriggerTimeEpochMs = null,
                    decision = null,
                    decisionReason = REASON_UNSUPPORTED_SCHEDULE
                )
            }

            // 3. Guard against active FIRING session
            if (activeFiringAlarmId != null && activeFiringAlarmId == alarmId) {
                return@withTransaction DurableTimeOverrideClaimResult(
                    outcome = DurableTimeOverrideClaimOutcome.ACTIVE_SESSION,
                    alarmId = alarmId,
                    committedTimeOverrideException = null,
                    canonicalOccurrenceId = null,
                    targetTriggerTimeEpochMs = null,
                    decision = null,
                    decisionReason = REASON_ACTIVE_SESSION
                )
            }

            // 4. Re-read latest ScheduleAuthority and ScheduleExceptions for active generation from DB
            val storedAuthority = authorityDao.getAuthorityByAlarmId(alarmId)
            val exceptionsUnfiltered = exceptionDao.getExceptionsForAlarmGeneration(alarmId, latestAlarm.generation)

            // Filter out any existing TYPE_TIME_OVERRIDE on actionSnapshot.expectedBaseTriggerTimeEpochMs
            // to evaluate baseline current-next candidate without self-invalidation
            val expectedBaseTime = actionSnapshot.expectedBaseTriggerTimeEpochMs
            val exceptionsForValidation = if (expectedBaseTime != null) {
                exceptionsUnfiltered.filterNot {
                    it.baseTriggerTime == expectedBaseTime && it.exceptionType == ScheduleException.TYPE_TIME_OVERRIDE
                }
            } else {
                exceptionsUnfiltered
            }

            // 5. Re-evaluate action validation using CurrentNextDecisionPlanner inside transaction
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

            if (decision.outcome != CurrentNextDecisionOutcome.CURRENT_NEXT_VALID || decision.evaluatedOccurrence == null) {
                val mappedOutcome = when (decision.outcome) {
                    CurrentNextDecisionOutcome.ACTIVE_SESSION -> DurableTimeOverrideClaimOutcome.ACTIVE_SESSION
                    CurrentNextDecisionOutcome.UNSUPPORTED_SCHEDULE -> DurableTimeOverrideClaimOutcome.UNSUPPORTED_SCHEDULE
                    else -> DurableTimeOverrideClaimOutcome.STALE_OCCURRENCE
                }

                return@withTransaction DurableTimeOverrideClaimResult(
                    outcome = mappedOutcome,
                    alarmId = alarmId,
                    committedTimeOverrideException = null,
                    canonicalOccurrenceId = decision.canonicalOccurrenceId,
                    targetTriggerTimeEpochMs = null,
                    decision = decision,
                    decisionReason = decision.decisionReason
                )
            }

            // 6. Identify Canonical BASE Trigger Time & local schedule date
            val canonicalBaseTime = decision.evaluatedOccurrence.baseTriggerTimeEpochMs
            val canonicalOccId = decision.canonicalOccurrenceId
                ?: AlarmOccurrenceId.fromAlarm(latestAlarm, canonicalBaseTime)

            val schedulingZone = latestAlarm.schedulingZone(evaluationZoneId)
            val targetZdt = Instant.ofEpochMilli(canonicalBaseTime).atZone(schedulingZone)
            val targetLocalDate = targetZdt.toLocalDate()

            // 7. Resolve target ZonedDateTime with canonical DST Gap / Overlap policy
            val overrideZdt = resolveZoned(targetLocalDate, newTime, schedulingZone)
            val overrideEpochMs = overrideZdt.toInstant().toEpochMilli()

            // 8. Strict Date Boundary Guard: reject implicit next-day or previous-day rollover
            if (overrideZdt.toLocalDate() != targetLocalDate) {
                return@withTransaction DurableTimeOverrideClaimResult(
                    outcome = DurableTimeOverrideClaimOutcome.INVALID_TARGET_TIME,
                    alarmId = alarmId,
                    committedTimeOverrideException = null,
                    canonicalOccurrenceId = canonicalOccId,
                    targetTriggerTimeEpochMs = null,
                    decision = decision,
                    decisionReason = REASON_INVALID_DATE_ROLLOVER
                )
            }

            // 9. Past Datetime Guard: reject if resulting datetime is in the past at commit
            if (overrideEpochMs <= nowEpochMs) {
                return@withTransaction DurableTimeOverrideClaimResult(
                    outcome = DurableTimeOverrideClaimOutcome.INVALID_TARGET_TIME,
                    alarmId = alarmId,
                    committedTimeOverrideException = null,
                    canonicalOccurrenceId = canonicalOccId,
                    targetTriggerTimeEpochMs = null,
                    decision = decision,
                    decisionReason = REASON_PAST_TARGET_TIME
                )
            }

            // 10. Check existing exception on canonicalBaseTime
            val existingException = exceptionDao.getExceptionForOccurrence(alarmId, canonicalBaseTime, latestAlarm.generation)
            if (existingException != null) {
                if (existingException.exceptionType == ScheduleException.TYPE_TIME_OVERRIDE &&
                    existingException.overrideTriggerTime == overrideEpochMs
                ) {
                    return@withTransaction DurableTimeOverrideClaimResult(
                        outcome = DurableTimeOverrideClaimOutcome.ALREADY_COMMITTED,
                        alarmId = alarmId,
                        committedTimeOverrideException = existingException,
                        canonicalOccurrenceId = canonicalOccId,
                        targetTriggerTimeEpochMs = overrideEpochMs,
                        decision = decision,
                        decisionReason = REASON_ALREADY_COMMITTED
                    )
                } else {
                    return@withTransaction DurableTimeOverrideClaimResult(
                        outcome = DurableTimeOverrideClaimOutcome.CONFLICT_OVERRIDE_EXISTS,
                        alarmId = alarmId,
                        committedTimeOverrideException = null,
                        canonicalOccurrenceId = canonicalOccId,
                        targetTriggerTimeEpochMs = null,
                        decision = decision,
                        decisionReason = REASON_CONFLICT_EXISTS
                    )
                }
            }

            // 11. Insert new TYPE_TIME_OVERRIDE ScheduleException
            val timeOverrideException = ScheduleException(
                alarmId = alarmId,
                baseTriggerTime = canonicalBaseTime,
                exceptionType = ScheduleException.TYPE_TIME_OVERRIDE,
                overrideTriggerTime = overrideEpochMs,
                generation = latestAlarm.generation,
                revision = latestAlarm.revision,
                createdAt = nowEpochMs
            )

            val rowId = exceptionDao.insert(timeOverrideException)
            if (rowId <= 0L) {
                val reRead = exceptionDao.getExceptionForOccurrence(alarmId, canonicalBaseTime, latestAlarm.generation)
                if (reRead != null &&
                    reRead.exceptionType == ScheduleException.TYPE_TIME_OVERRIDE &&
                    reRead.overrideTriggerTime == overrideEpochMs
                ) {
                    return@withTransaction DurableTimeOverrideClaimResult(
                        outcome = DurableTimeOverrideClaimOutcome.ALREADY_COMMITTED,
                        alarmId = alarmId,
                        committedTimeOverrideException = reRead,
                        canonicalOccurrenceId = canonicalOccId,
                        targetTriggerTimeEpochMs = overrideEpochMs,
                        decision = decision,
                        decisionReason = REASON_ALREADY_COMMITTED
                    )
                } else {
                    return@withTransaction DurableTimeOverrideClaimResult(
                        outcome = DurableTimeOverrideClaimOutcome.CONFLICT_OVERRIDE_EXISTS,
                        alarmId = alarmId,
                        committedTimeOverrideException = null,
                        canonicalOccurrenceId = canonicalOccId,
                        targetTriggerTimeEpochMs = null,
                        decision = decision,
                        decisionReason = REASON_CONFLICT_EXISTS
                    )
                }
            }

            val insertedException = timeOverrideException.copy(id = rowId)

            return@withTransaction DurableTimeOverrideClaimResult(
                outcome = DurableTimeOverrideClaimOutcome.COMMITTED_PENDING_RECONCILE,
                alarmId = alarmId,
                committedTimeOverrideException = insertedException,
                canonicalOccurrenceId = canonicalOccId,
                targetTriggerTimeEpochMs = overrideEpochMs,
                decision = decision,
                decisionReason = REASON_COMMITTED
            )
        }
    }
}
