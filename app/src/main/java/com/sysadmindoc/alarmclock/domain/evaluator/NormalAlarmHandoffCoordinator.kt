package com.sysadmindoc.alarmclock.domain.evaluator

import androidx.room.withTransaction
import com.sysadmindoc.alarmclock.data.local.AlarmDatabase
import com.sysadmindoc.alarmclock.data.local.entity.AlarmAdmissionTombstoneEntity
import com.sysadmindoc.alarmclock.data.local.entity.AlarmSessionEntity
import com.sysadmindoc.alarmclock.data.local.entity.IssuedOccurrence
import com.sysadmindoc.alarmclock.data.model.Alarm
import com.sysadmindoc.alarmclock.data.model.AlarmOccurrenceId
import com.sysadmindoc.alarmclock.data.local.entity.ScheduleException
import com.sysadmindoc.alarmclock.data.preferences.AppSettings
import com.sysadmindoc.alarmclock.data.repository.HolidayRepository
import com.sysadmindoc.alarmclock.domain.NextAlarmCalculator
import com.sysadmindoc.alarmclock.domain.session.AlarmSessionEvent
import com.sysadmindoc.alarmclock.domain.session.NormalAlarmAdmissionFactory
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.CancellationException

/**
 * Explicit, typed outcomes for atomic normal alarm handoff coordination (KNO-37 Phase H2-C2-B).
 */
enum class NormalAlarmHandoffOutcome {
    ADMITTED,
    SUPPRESSED,
    DUPLICATE,
    ALREADY_SUPPRESSED,
    REJECTED_STALE_IDENTITY,
    REJECTED_STALE_PROVENANCE,
    REJECTED_STALE_GENERATION,
    REJECTED_STALE_ISSUE_SLOT,
    REJECTED_UNSUPPORTED_STATUS,
    REJECTED_UNSUPPORTED_SCHEDULE,
    CORRUPTED_STATE,
    CAS_CONFLICT,
    REPOSITORY_FAILURE
}

/**
 * Immutable request contract for atomic normal alarm handoff coordination.
 * All fields are validated against authoritative Room records inside a single transaction.
 * Defaults are strictly removed to force explicit caller provenance (KNO-37 H2-C2-B1).
 */
data class NormalAlarmHandoffRequest(
    val issueToken: String,
    val expectedAlarmId: Long,
    val expectedFireId: String,
    val expectedScheduledAt: Long,
    val expectedBaseTriggerTime: Long,
    val expectedAlarmGeneration: Int,
    val expectedSlotGenerationId: Long,
    val expectedAuthorityFingerprint: ScheduleAuthorityFingerprint,
    val nowEpochMs: Long,
    val elapsedRealtimeMs: Long,
    val bootCount: Long,
    val settings: AppSettings,
    val effectiveNormalAutoSilenceMinutes: Int?
)

/**
 * Immutable typed record of committed post-fire schedule recovery intent (KNO-37 Phase H2-C4-P3).
 * Represents durable facts committed atomically in Room within the same transaction as Occurrence and Session.
 */
data class CommittedAuthorityRecoveryIntent(
    val alarmId: Long,
    val claimedRevision: Long,
    val lifecycle: String,
    val desiredOperation: String,
    val desiredTriggerMillis: Long?,
    val actionKey: String,
    val reconcileState: String = "RECONCILING"
)

/**
 * Result of an atomic normal alarm handoff operation.
 */
data class NormalAlarmHandoffResult(
    val outcome: NormalAlarmHandoffOutcome,
    val diagnosticDetail: String? = null,
    val sessionId: String? = null,
    val admissionKey: String? = null,
    val actionTaken: NormalAlarmHandoffAction? = null,
    val decisionReason: String? = null,
    val committedAuthorityIntent: CommittedAuthorityRecoveryIntent? = null
)

/**
 * Inactive, fail-closed coordinator executing atomic Normal Alarm handoff decisions
 * and database mutations inside a single Room transaction boundary (KNO-37 Phase H2-C2-B / H2-C4-P3).
 */
class NormalAlarmHandoffCoordinator(
    private val database: AlarmDatabase,
    private val admissionFactory: NormalAlarmAdmissionFactory = NormalAlarmAdmissionFactory(),
    private val planner: NormalAlarmHandoffPlanner = NormalAlarmHandoffPlanner(),
    private val suppressionCoordinator: AtomicOccurrenceSuppressionCoordinator = AtomicOccurrenceSuppressionCoordinator(database),
    private val postFirePlanner: NormalAlarmPostFirePlanner = NormalAlarmPostFirePlanner(),
    private val nextAlarmCalculator: NextAlarmCalculator = NextAlarmCalculator(),
    private val occurrenceEvaluator: ScheduleOccurrenceEvaluator = ScheduleOccurrenceEvaluator(),
    private val holidayRepository: HolidayRepository? = null
) {

    /**
     * Testing cutpoints for crash and rollback verification (KNO-37 Phase H2-C2-C / H2-C4-P3).
     */
    internal var cutpointInterceptor: ((Cutpoint) -> Unit)? = null

    enum class Cutpoint {
        BEFORE_OCCURRENCE_CAS,
        AFTER_OCCURRENCE_CAS,
        AFTER_TOMBSTONE_INSERT,
        AFTER_SEQUENCE_ALLOCATION,
        BEFORE_ALARM_DEFINITION_CAS,
        AFTER_ALARM_DEFINITION_CAS,
        BEFORE_AUTHORITY_CAS,
        AFTER_AUTHORITY_CAS,
        BEFORE_TRANSACTION_COMMIT,
        AFTER_TRANSACTION_COMMIT
    }

    /**
     * Immutable prepared post-fire recovery plan payload evaluated outside the Room transaction.
     */
    data class PreparedPostFireRecovery(
        val isRecurringSchedule: Boolean,
        val nextTriggerTime: Long,
        val actionKey: String,
        val expectedRevision: Long,
        val expectedDefinitionRevision: Long,
        val expectedAlarmGeneration: Int,
        val preparedExceptions: List<ScheduleException> = emptyList()
    )

    /**
     * Typed result of post-fire recovery preparation evaluated outside Room transaction (KNO-37 Phase H2-C4-P3B).
     */
    sealed interface RecoveryPreparationResult {
        data class Ready(val recovery: PreparedPostFireRecovery) : RecoveryPreparationResult
        object NotApplicable : RecoveryPreparationResult
        data class Failed(
            val outcome: NormalAlarmHandoffOutcome,
            val diagnosticDetail: String,
            val decisionReason: String
        ) : RecoveryPreparationResult
    }

    /**
     * Evaluates authoritative schedule inputs and computes next occurrence trigger outside Room transaction.
     * Never performs network I/O and never touches OS services.
     */
    internal suspend fun prepareRecoveryInputs(request: NormalAlarmHandoffRequest): RecoveryPreparationResult {
        if (request.expectedScheduledAt <= 0L || request.expectedScheduledAt > Long.MAX_VALUE - 60_000L) {
            return RecoveryPreparationResult.Failed(
                outcome = NormalAlarmHandoffOutcome.REJECTED_STALE_IDENTITY,
                diagnosticDetail = "Invalid or overflowing scheduledAt: ${request.expectedScheduledAt}",
                decisionReason = NormalAlarmHandoffPlanner.REASON_REJECT_INVALID_IDENTITY
            )
        }

        val alarm = database.alarmDao().getById(request.expectedAlarmId)
            ?: return RecoveryPreparationResult.NotApplicable

        if (alarm.isQuickAlarm || alarm.kind != Alarm.KIND_NORMAL || !alarm.isEnabled) {
            return RecoveryPreparationResult.NotApplicable
        }

        val authority = database.scheduleAuthorityDao().getAuthorityByAlarmId(request.expectedAlarmId)
            ?: return RecoveryPreparationResult.Failed(
                outcome = NormalAlarmHandoffOutcome.REJECTED_STALE_PROVENANCE,
                diagnosticDetail = "Missing required schedule authority for alarm ${request.expectedAlarmId}",
                decisionReason = "REJECT_MISSING_AUTHORITY"
            )

        return if (alarm.isRecurringSchedule) {
            val notBeforeMillis = request.expectedScheduledAt + 60_000L
            val schedulingZone = alarm.schedulingZone(ZoneId.systemDefault())
            val exceptions = try {
                database.scheduleExceptionDao().getExceptionsForAlarmGeneration(alarm.id, alarm.generation)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                return RecoveryPreparationResult.Failed(
                    outcome = NormalAlarmHandoffOutcome.REPOSITORY_FAILURE,
                    diagnosticDetail = "Exception DAO read failed: ${e.message}",
                    decisionReason = "EXCEPTION_DAO_FAILURE"
                )
            }
            val requiresHolidayOrException = (alarm.canonicalHolidayPolicy() != HolidayPolicy.FOLLOW_BASE || exceptions.isNotEmpty())
            val nextTrigger = if (requiresHolidayOrException) {
                if (alarm.canonicalHolidayPolicy() != HolidayPolicy.FOLLOW_BASE) {
                    val repo = holidayRepository ?: return RecoveryPreparationResult.Failed(
                        outcome = NormalAlarmHandoffOutcome.REPOSITORY_FAILURE,
                        diagnosticDetail = "Holiday authority acquisition failed: HolidayRepository is null for non-FOLLOW_BASE alarm",
                        decisionReason = "HOLIDAY_AUTHORITY_FAILURE"
                    )
                    val notBeforeDate = Instant.ofEpochMilli(notBeforeMillis).atZone(schedulingZone).toLocalDate()
                    val authorityWindow = try {
                        repo.captureAuthorityWindow(
                            startDate = notBeforeDate,
                            endDateInclusive = notBeforeDate.plusDays(ScheduleOccurrenceEvaluator.MAX_CANDIDATE_SEARCH_STEPS.toLong() - 1L)
                        )
                    } catch (e: Exception) {
                        if (e is CancellationException) throw e
                        return RecoveryPreparationResult.Failed(
                            outcome = NormalAlarmHandoffOutcome.REPOSITORY_FAILURE,
                            diagnosticDetail = "Holiday authority acquisition failed: ${e.message}",
                            decisionReason = "HOLIDAY_AUTHORITY_FAILURE"
                        )
                    } ?: return RecoveryPreparationResult.Failed(
                        outcome = NormalAlarmHandoffOutcome.REPOSITORY_FAILURE,
                        diagnosticDetail = "Holiday authority window capture returned null",
                        decisionReason = "HOLIDAY_AUTHORITY_FAILURE"
                    )
                    val classifier = authorityWindow.asLocalHolidayClassifier()
                    val evaluated = occurrenceEvaluator.evaluateWithHolidayClassifier(
                        alarm = alarm,
                        fromEpochMs = notBeforeMillis,
                        holidayClassifier = classifier,
                        exceptions = exceptions,
                        evaluationZoneId = schedulingZone,
                        allowHidePersonalRuntimeFeatures = true
                    )
                    if (evaluated != null && (evaluated.decision == OccurrenceDecision.NORMAL_TRIGGER || evaluated.decision == OccurrenceDecision.TIME_OVERRIDDEN)) {
                        evaluated.targetTriggerTimeEpochMs
                    } else {
                        0L
                    }
                } else {
                    // FOLLOW_BASE with exceptions
                    val classifier = LocalHolidayClassifier.ALL_UNKNOWN
                    val evaluated = occurrenceEvaluator.evaluateWithHolidayClassifier(
                        alarm = alarm,
                        fromEpochMs = notBeforeMillis,
                        holidayClassifier = classifier,
                        exceptions = exceptions,
                        evaluationZoneId = schedulingZone,
                        allowHidePersonalRuntimeFeatures = true
                    )
                    if (evaluated != null && (evaluated.decision == OccurrenceDecision.NORMAL_TRIGGER || evaluated.decision == OccurrenceDecision.TIME_OVERRIDDEN)) {
                        evaluated.targetTriggerTimeEpochMs
                    } else {
                        0L
                    }
                }
            } else {
                nextAlarmCalculator.calculate(
                    alarm,
                    Instant.ofEpochMilli(notBeforeMillis).atZone(schedulingZone)
                )
            }

            if (nextTrigger <= 0L) {
                return RecoveryPreparationResult.Failed(
                    outcome = NormalAlarmHandoffOutcome.REJECTED_UNSUPPORTED_SCHEDULE,
                    diagnosticDetail = "Evaluated next trigger is non-positive: $nextTrigger",
                    decisionReason = "INVALID_NEXT_TRIGGER"
                )
            }

            RecoveryPreparationResult.Ready(
                PreparedPostFireRecovery(
                    isRecurringSchedule = true,
                    nextTriggerTime = nextTrigger,
                    actionKey = "postfire_${request.issueToken}",
                    expectedRevision = authority.scheduleRevision,
                    expectedDefinitionRevision = authority.definitionRevision,
                    expectedAlarmGeneration = alarm.generation,
                    preparedExceptions = exceptions
                )
            )
        } else {
            RecoveryPreparationResult.Ready(
                PreparedPostFireRecovery(
                    isRecurringSchedule = false,
                    nextTriggerTime = 0L,
                    actionKey = "postfire_disable_${request.issueToken}",
                    expectedRevision = authority.scheduleRevision,
                    expectedDefinitionRevision = authority.definitionRevision,
                    expectedAlarmGeneration = alarm.generation
                )
            )
        }
    }

    /**
     * Executes atomic normal alarm handoff inside a single Room database transaction.
     */
    suspend fun coordinateHandoff(request: NormalAlarmHandoffRequest): NormalAlarmHandoffResult {
        // 0. Pre-validate timestamp preconditions
        if (request.expectedScheduledAt <= 0L || request.expectedScheduledAt > Long.MAX_VALUE - 60_000L) {
            return NormalAlarmHandoffResult(
                outcome = NormalAlarmHandoffOutcome.REJECTED_STALE_IDENTITY,
                diagnosticDetail = "Invalid or overflowing scheduledAt: ${request.expectedScheduledAt}",
                actionTaken = NormalAlarmHandoffAction.REJECT,
                decisionReason = NormalAlarmHandoffPlanner.REASON_REJECT_INVALID_IDENTITY
            )
        }

        // 1. Prepare post-fire recovery inputs outside Room transaction
        val preparation = prepareRecoveryInputs(request)
        if (preparation is RecoveryPreparationResult.Failed) {
            return NormalAlarmHandoffResult(
                outcome = preparation.outcome,
                diagnosticDetail = preparation.diagnosticDetail,
                actionTaken = NormalAlarmHandoffAction.REJECT,
                decisionReason = preparation.decisionReason
            )
        }
        val preparedRecovery = (preparation as? RecoveryPreparationResult.Ready)?.recovery

        // 2. Execute within Room transaction
        val result = try {
            database.withTransaction {
                executeHandoff(request, preparedRecovery)
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            val isConstraint = e is android.database.sqlite.SQLiteConstraintException
            val isCasConflict = isConstraint || (e is IllegalStateException && e.message?.contains("CAS") == true)
            return NormalAlarmHandoffResult(
                outcome = if (isCasConflict) NormalAlarmHandoffOutcome.CAS_CONFLICT else NormalAlarmHandoffOutcome.REPOSITORY_FAILURE,
                diagnosticDetail = "Transaction failed: ${e.message}",
                actionTaken = NormalAlarmHandoffAction.REJECT,
                decisionReason = "TRANSACTION_FAILURE"
            )
        }

        // 3. Post-commit observation cutpoint (failures here do not roll back committed transaction)
        try {
            cutpointInterceptor?.invoke(Cutpoint.AFTER_TRANSACTION_COMMIT)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            android.util.Log.w("NormalAlarmHandoffCoordinator", "Postcommit cutpoint observer threw exception", e)
        }
        return result
    }

    /**
     * Executes authoritative fact gathering, structural consistency verification,
     * pure decision planning, and safe mutation within the active transaction boundary.
     */
    internal suspend fun executeHandoff(
        request: NormalAlarmHandoffRequest,
        preparedRecovery: PreparedPostFireRecovery? = null
    ): NormalAlarmHandoffResult {
        // 0. Validate explicit runtime and identity preconditions (Fail Closed)
        if (request.nowEpochMs <= 0L || request.expectedScheduledAt <= 0L || request.expectedBaseTriggerTime <= 0L) {
            return NormalAlarmHandoffResult(
                outcome = NormalAlarmHandoffOutcome.REJECTED_STALE_IDENTITY,
                diagnosticDetail = "Invalid timestamp: nowEpochMs=${request.nowEpochMs}, scheduledAt=${request.expectedScheduledAt}, baseTrigger=${request.expectedBaseTriggerTime}",
                actionTaken = NormalAlarmHandoffAction.REJECT,
                decisionReason = NormalAlarmHandoffPlanner.REASON_REJECT_INVALID_IDENTITY
            )
        }

        if (request.bootCount <= 0L) {
            return NormalAlarmHandoffResult(
                outcome = NormalAlarmHandoffOutcome.REJECTED_STALE_IDENTITY,
                diagnosticDetail = "Invalid boot count: ${request.bootCount}",
                actionTaken = NormalAlarmHandoffAction.REJECT,
                decisionReason = NormalAlarmHandoffPlanner.REASON_REJECT_INVALID_IDENTITY
            )
        }

        if (request.elapsedRealtimeMs <= 0L) {
            return NormalAlarmHandoffResult(
                outcome = NormalAlarmHandoffOutcome.REJECTED_STALE_IDENTITY,
                diagnosticDetail = "Invalid elapsed realtime: ${request.elapsedRealtimeMs}",
                actionTaken = NormalAlarmHandoffAction.REJECT,
                decisionReason = NormalAlarmHandoffPlanner.REASON_REJECT_INVALID_IDENTITY
            )
        }

        if (request.effectiveNormalAutoSilenceMinutes == null) {
            return NormalAlarmHandoffResult(
                outcome = NormalAlarmHandoffOutcome.REJECTED_UNSUPPORTED_STATUS,
                diagnosticDetail = "Missing explicit effective normal auto-silence duration",
                actionTaken = NormalAlarmHandoffAction.REJECT,
                decisionReason = NormalAlarmHandoffPlanner.REASON_REJECT_UNSUPPORTED_STATUS
            )
        }

        if (request.effectiveNormalAutoSilenceMinutes <= 0) {
            return NormalAlarmHandoffResult(
                outcome = NormalAlarmHandoffOutcome.REJECTED_UNSUPPORTED_STATUS,
                diagnosticDetail = "Invalid effective normal auto-silence duration: ${request.effectiveNormalAutoSilenceMinutes}",
                actionTaken = NormalAlarmHandoffAction.REJECT,
                decisionReason = NormalAlarmHandoffPlanner.REASON_REJECT_UNSUPPORTED_STATUS
            )
        }

        val issuedDao = database.issuedOccurrenceDao()
        val slotDao = database.issuedOccurrenceDao()
        val alarmDao = database.alarmDao()
        val authorityDao = database.scheduleAuthorityDao()
        val sessionDao = database.alarmSessionDao()

        // 1. Authoritative Room reads
        val occurrence = issuedDao.getOccurrence(request.issueToken)
        val slot = slotDao.getSlot(request.expectedAlarmId)
        val alarm = alarmDao.getById(request.expectedAlarmId)
        val authority = authorityDao.getAuthorityByAlarmId(request.expectedAlarmId)
        val activeSessions = sessionDao.getActiveSessionsForAlarm(request.expectedAlarmId)

        // 2. Normal-only eligibility guard
        if (alarm != null) {
            if (alarm.isQuickAlarm || alarm.kind != Alarm.KIND_NORMAL) {
                return NormalAlarmHandoffResult(
                    outcome = NormalAlarmHandoffOutcome.REJECTED_UNSUPPORTED_SCHEDULE,
                    diagnosticDetail = "Quick Alarm cannot use Normal Alarm Handoff",
                    actionTaken = NormalAlarmHandoffAction.REJECT,
                    decisionReason = NormalAlarmHandoffPlanner.REASON_REJECT_UNSUPPORTED_STATUS
                )
            }
            if (!alarm.isEnabled && occurrence?.status != IssuedOccurrence.STATUS_ADMITTED && activeSessions.isEmpty()) {
                return NormalAlarmHandoffResult(
                    outcome = NormalAlarmHandoffOutcome.REJECTED_UNSUPPORTED_SCHEDULE,
                    diagnosticDetail = "Alarm is disabled",
                    actionTaken = NormalAlarmHandoffAction.REJECT,
                    decisionReason = NormalAlarmHandoffPlanner.REASON_REJECT_UNSUPPORTED_STATUS
                )
            }
        }

        // 3. Resolve canonical identities
        val baseTriggerTime = occurrence?.baseTriggerTime ?: request.expectedBaseTriggerTime
        val gen = occurrence?.alarmGeneration ?: request.expectedAlarmGeneration
        val occurrenceId = AlarmOccurrenceId(
            alarmId = request.expectedAlarmId,
            baseTriggerTime = baseTriggerTime,
            generation = gen
        )
        val canonicalAdmissionKey = "admission_${request.expectedAlarmId}_${occurrenceId.key}"

        // 4. Verify Authoritative Facts
        val isIdentityValid = occurrence != null &&
                alarm != null &&
                occurrence.alarmId == request.expectedAlarmId &&
                occurrence.fireId == request.expectedFireId &&
                occurrence.scheduledAt == request.expectedScheduledAt &&
                occurrence.baseTriggerTime == request.expectedBaseTriggerTime &&
                alarm.id == request.expectedAlarmId &&
                request.expectedAlarmId > 0L &&
                baseTriggerTime > 0L &&
                gen > 0 &&
                request.expectedFireId.isNotBlank() &&
                request.issueToken.isNotBlank() &&
                request.expectedScheduledAt > 0L &&
                request.nowEpochMs > 0L &&
                request.elapsedRealtimeMs > 0L &&
                request.bootCount > 0L

        val isGenerationValid = isIdentityValid &&
                alarm.generation == request.expectedAlarmGeneration &&
                occurrence.alarmGeneration == request.expectedAlarmGeneration

        val isIssueSlotValid = slot != null &&
                occurrence != null &&
                slot.generationId == request.expectedSlotGenerationId &&
                occurrence.generationId == request.expectedSlotGenerationId &&
                slot.currentIssueToken == request.issueToken

        // 5. Structural Consistency Verification
        val tombstone = sessionDao.getTombstoneByAdmissionKey(canonicalAdmissionKey)
        val occurrenceStatus = occurrence?.status ?: ""

        val structuralState = verifyStructuralConsistency(
            tombstone = tombstone,
            occurrenceStatus = occurrenceStatus,
            expectedAlarmId = request.expectedAlarmId,
            expectedOccurrenceKey = occurrenceId.key,
            canonicalAdmissionKey = canonicalAdmissionKey,
            sessionDao = sessionDao
        )

        if (structuralState is StructuralAdmissionState.Corrupted) {
            return NormalAlarmHandoffResult(
                outcome = NormalAlarmHandoffOutcome.CORRUPTED_STATE,
                diagnosticDetail = structuralState.reason,
                admissionKey = canonicalAdmissionKey,
                actionTaken = NormalAlarmHandoffAction.REJECT,
                decisionReason = "CORRUPTED_ADMISSION_STATE"
            )
        }

        val isAlreadyAdmitted = structuralState is StructuralAdmissionState.ValidDuplicate

        val isProvenanceValid = if (isAlreadyAdmitted) {
            authority != null &&
                    isIdentityValid &&
                    authority.alarmId == request.expectedAlarmId &&
                    !occurrence.authorityInstanceToken.isNullOrBlank() &&
                    occurrence.authorityInstanceToken == authority.instanceToken &&
                    occurrence.restoreEpoch == authority.restoreEpoch &&
                    request.expectedAuthorityFingerprint.alarmId == occurrence.alarmId &&
                    request.expectedAuthorityFingerprint.instanceToken == occurrence.authorityInstanceToken &&
                    request.expectedAuthorityFingerprint.restoreEpoch == occurrence.restoreEpoch &&
                    request.expectedAuthorityFingerprint.scheduleRevision == occurrence.scheduleRevision &&
                    request.expectedAuthorityFingerprint.definitionRevision == occurrence.definitionRevision
        } else {
            authority != null &&
                    isIdentityValid &&
                    authority.alarmId == request.expectedAlarmId &&
                    ScheduleAuthorityFingerprint.fromAuthority(authority) == request.expectedAuthorityFingerprint &&
                    !occurrence.authorityInstanceToken.isNullOrBlank() &&
                    occurrence.authorityInstanceToken == authority.instanceToken &&
                    occurrence.restoreEpoch == authority.restoreEpoch &&
                    occurrence.scheduleRevision == authority.scheduleRevision &&
                    occurrence.definitionRevision == authority.definitionRevision
        }

        val allowedActiveStates = AtomicOccurrenceSuppressionCoordinator.ALLOWED_ACTIVE_SESSION_STATES
        val hasActiveSession = activeSessions.any {
            it.alarmId == request.expectedAlarmId &&
                    it.sessionState in allowedActiveStates
        }

        // 6. Evaluate pure handoff plan
        val facts = NormalAlarmHandoffFacts(
            occurrenceId = occurrenceId,
            fireId = request.expectedFireId,
            scheduledAt = request.expectedScheduledAt,
            issueToken = request.issueToken,
            occurrenceStatus = occurrenceStatus,
            isIdentityValid = isIdentityValid,
            isProvenanceValid = isProvenanceValid,
            isGenerationValid = isGenerationValid,
            isIssueSlotValid = isIssueSlotValid,
            isAlreadyAdmitted = isAlreadyAdmitted,
            hasActiveSession = hasActiveSession
        )

        val plan = planner.plan(facts)

        // 7. Execute decision branch
        return when (plan.action) {
            NormalAlarmHandoffAction.REJECT -> {
                val outcome = when (plan.decisionReason) {
                    NormalAlarmHandoffPlanner.REASON_REJECT_INVALID_IDENTITY -> NormalAlarmHandoffOutcome.REJECTED_STALE_IDENTITY
                    NormalAlarmHandoffPlanner.REASON_REJECT_INVALID_PROVENANCE -> NormalAlarmHandoffOutcome.REJECTED_STALE_PROVENANCE
                    NormalAlarmHandoffPlanner.REASON_REJECT_STALE_GENERATION -> NormalAlarmHandoffOutcome.REJECTED_STALE_GENERATION
                    NormalAlarmHandoffPlanner.REASON_REJECT_STALE_ISSUE_SLOT -> NormalAlarmHandoffOutcome.REJECTED_STALE_ISSUE_SLOT
                    NormalAlarmHandoffPlanner.REASON_REJECT_UNSUPPORTED_STATUS -> NormalAlarmHandoffOutcome.REJECTED_UNSUPPORTED_STATUS
                    else -> NormalAlarmHandoffOutcome.REJECTED_STALE_IDENTITY
                }
                NormalAlarmHandoffResult(
                    outcome = outcome,
                    diagnosticDetail = plan.decisionReason,
                    admissionKey = canonicalAdmissionKey,
                    actionTaken = plan.action,
                    decisionReason = plan.decisionReason
                )
            }

            NormalAlarmHandoffAction.ALREADY_SUPPRESSED -> {
                NormalAlarmHandoffResult(
                    outcome = NormalAlarmHandoffOutcome.ALREADY_SUPPRESSED,
                    diagnosticDetail = "Occurrence is already SUPPRESSED",
                    admissionKey = canonicalAdmissionKey,
                    actionTaken = plan.action,
                    decisionReason = plan.decisionReason
                )
            }

            NormalAlarmHandoffAction.DUPLICATE -> {
                val duplicateSessionId = (structuralState as? StructuralAdmissionState.ValidDuplicate)?.tombstone?.sessionId
                NormalAlarmHandoffResult(
                    outcome = NormalAlarmHandoffOutcome.DUPLICATE,
                    diagnosticDetail = "Occurrence was already admitted with valid session",
                    sessionId = duplicateSessionId,
                    admissionKey = canonicalAdmissionKey,
                    actionTaken = plan.action,
                    decisionReason = plan.decisionReason
                )
            }

            NormalAlarmHandoffAction.SUPPRESS -> {
                val suppressionRequest = AtomicSuppressionRequest(
                    issueToken = request.issueToken,
                    expectedAlarmId = request.expectedAlarmId,
                    expectedFireId = request.expectedFireId,
                    expectedScheduledAt = request.expectedScheduledAt,
                    expectedBaseTriggerTime = request.expectedBaseTriggerTime,
                    expectedAlarmGeneration = request.expectedAlarmGeneration,
                    expectedSlotGenerationId = request.expectedSlotGenerationId,
                    expectedAuthorityFingerprint = request.expectedAuthorityFingerprint,
                    nowEpochMs = request.nowEpochMs
                )
                val suppressionResult = suppressionCoordinator.executeSuppression(suppressionRequest)
                when (suppressionResult.outcome) {
                    AtomicSuppressionOutcome.SUPPRESSED -> {
                        val effectiveRecovery = preparedRecovery
                        val committedIntent = if (effectiveRecovery != null && effectiveRecovery.isRecurringSchedule) {
                            val resolvedAlarm = alarm ?: throw IllegalStateException("Alarm cannot be null on SUPPRESSED")
                            val resolvedAuthority = authority ?: throw IllegalStateException("Authority cannot be null on SUPPRESSED")

                            // Revalidate preparation dependencies inside Room transaction
                            if (resolvedAlarm.generation != effectiveRecovery.expectedAlarmGeneration ||
                                resolvedAuthority.scheduleRevision != effectiveRecovery.expectedRevision ||
                                resolvedAuthority.definitionRevision != effectiveRecovery.expectedDefinitionRevision
                            ) {
                                throw IllegalStateException("Authority or alarm generation modified concurrently between PREPARE and Room transaction on SUPPRESSED (expected rev=${effectiveRecovery.expectedRevision}, current rev=${resolvedAuthority.scheduleRevision})")
                            }

                            // 1. Update Alarm definition nextTrigger using pre-claim revision fence
                            cutpointInterceptor?.invoke(Cutpoint.BEFORE_ALARM_DEFINITION_CAS)
                            val alarmUpdated = if (resolvedAlarm.nextTriggerTime == 0L) {
                                val initialStrict = alarmDao.casUpdateNextTriggerWithStrictAuthority(
                                    id = resolvedAlarm.id,
                                    expectedTrigger = 0L,
                                    newTrigger = effectiveRecovery.nextTriggerTime,
                                    expectedRevision = resolvedAuthority.scheduleRevision,
                                    instanceToken = resolvedAuthority.instanceToken,
                                    restoreEpoch = resolvedAuthority.restoreEpoch,
                                    definitionRevision = resolvedAuthority.definitionRevision
                                )
                                if (initialStrict == 0) {
                                    alarmDao.casUpdateNextTriggerInitialWithAuthority(
                                        id = resolvedAlarm.id,
                                        newTrigger = effectiveRecovery.nextTriggerTime,
                                        expectedRevision = resolvedAuthority.scheduleRevision,
                                        instanceToken = resolvedAuthority.instanceToken,
                                        restoreEpoch = resolvedAuthority.restoreEpoch,
                                        definitionRevision = resolvedAuthority.definitionRevision
                                    )
                                } else {
                                    initialStrict
                                }
                            } else {
                                alarmDao.casUpdateNextTriggerWithStrictAuthority(
                                    id = resolvedAlarm.id,
                                    expectedTrigger = resolvedAlarm.nextTriggerTime,
                                    newTrigger = effectiveRecovery.nextTriggerTime,
                                    expectedRevision = resolvedAuthority.scheduleRevision,
                                    instanceToken = resolvedAuthority.instanceToken,
                                    restoreEpoch = resolvedAuthority.restoreEpoch,
                                    definitionRevision = resolvedAuthority.definitionRevision
                                )
                            }
                            if (alarmUpdated != 1) {
                                throw IllegalStateException("Alarm nextTrigger CAS update failed on SUPPRESSED (expected 1 row, got $alarmUpdated; expectedTrigger=${resolvedAlarm.nextTriggerTime})")
                            }
                            cutpointInterceptor?.invoke(Cutpoint.AFTER_ALARM_DEFINITION_CAS)

                            // 2. Claim ScheduleAuthority intent
                            cutpointInterceptor?.invoke(Cutpoint.BEFORE_AUTHORITY_CAS)
                            val claimed = authorityDao.casClaimAuthority(
                                alarmId = resolvedAlarm.id,
                                instanceToken = resolvedAuthority.instanceToken,
                                restoreEpoch = resolvedAuthority.restoreEpoch,
                                expectedRevision = resolvedAuthority.scheduleRevision,
                                expectedLifecycle = resolvedAuthority.lifecycle,
                                desiredOperation = "SET",
                                desiredTriggerMillis = effectiveRecovery.nextTriggerTime,
                                actionKey = effectiveRecovery.actionKey
                            )
                            if (claimed != 1) {
                                throw IllegalStateException("ScheduleAuthority CAS claim failed on SUPPRESSED")
                            }
                            cutpointInterceptor?.invoke(Cutpoint.AFTER_AUTHORITY_CAS)

                            CommittedAuthorityRecoveryIntent(
                                alarmId = resolvedAlarm.id,
                                claimedRevision = resolvedAuthority.scheduleRevision + 1L,
                                lifecycle = resolvedAuthority.lifecycle,
                                desiredOperation = "SET",
                                desiredTriggerMillis = effectiveRecovery.nextTriggerTime,
                                actionKey = effectiveRecovery.actionKey,
                                reconcileState = "RECONCILING"
                            )
                        } else null

                        NormalAlarmHandoffResult(
                            outcome = NormalAlarmHandoffOutcome.SUPPRESSED,
                            diagnosticDetail = suppressionResult.diagnosticDetail,
                            admissionKey = canonicalAdmissionKey,
                            actionTaken = plan.action,
                            decisionReason = plan.decisionReason,
                            committedAuthorityIntent = committedIntent
                        )
                    }
                    AtomicSuppressionOutcome.ALREADY_SUPPRESSED -> {
                        NormalAlarmHandoffResult(
                            outcome = NormalAlarmHandoffOutcome.ALREADY_SUPPRESSED,
                            diagnosticDetail = suppressionResult.diagnosticDetail,
                            admissionKey = canonicalAdmissionKey,
                            actionTaken = NormalAlarmHandoffAction.ALREADY_SUPPRESSED,
                            decisionReason = "ALREADY_SUPPRESSED"
                        )
                    }
                    AtomicSuppressionOutcome.STALE_IDENTITY -> {
                        NormalAlarmHandoffResult(
                            outcome = NormalAlarmHandoffOutcome.REJECTED_STALE_IDENTITY,
                            diagnosticDetail = suppressionResult.diagnosticDetail,
                            admissionKey = canonicalAdmissionKey,
                            actionTaken = NormalAlarmHandoffAction.REJECT,
                            decisionReason = suppressionResult.outcome.name
                        )
                    }
                    AtomicSuppressionOutcome.STALE_AUTHORITY -> {
                        NormalAlarmHandoffResult(
                            outcome = NormalAlarmHandoffOutcome.REJECTED_STALE_PROVENANCE,
                            diagnosticDetail = suppressionResult.diagnosticDetail,
                            admissionKey = canonicalAdmissionKey,
                            actionTaken = NormalAlarmHandoffAction.REJECT,
                            decisionReason = suppressionResult.outcome.name
                        )
                    }
                    AtomicSuppressionOutcome.STALE_GENERATION -> {
                        NormalAlarmHandoffResult(
                            outcome = NormalAlarmHandoffOutcome.REJECTED_STALE_GENERATION,
                            diagnosticDetail = suppressionResult.diagnosticDetail,
                            admissionKey = canonicalAdmissionKey,
                            actionTaken = NormalAlarmHandoffAction.REJECT,
                            decisionReason = suppressionResult.outcome.name
                        )
                    }
                    AtomicSuppressionOutcome.NOT_CURRENT_SLOT -> {
                        NormalAlarmHandoffResult(
                            outcome = NormalAlarmHandoffOutcome.REJECTED_STALE_ISSUE_SLOT,
                            diagnosticDetail = suppressionResult.diagnosticDetail,
                            admissionKey = canonicalAdmissionKey,
                            actionTaken = NormalAlarmHandoffAction.REJECT,
                            decisionReason = suppressionResult.outcome.name
                        )
                    }
                    AtomicSuppressionOutcome.INVALID_STATUS,
                    AtomicSuppressionOutcome.NO_ACTIVE_SESSION -> {
                        NormalAlarmHandoffResult(
                            outcome = NormalAlarmHandoffOutcome.REJECTED_UNSUPPORTED_STATUS,
                            diagnosticDetail = suppressionResult.diagnosticDetail,
                            admissionKey = canonicalAdmissionKey,
                            actionTaken = NormalAlarmHandoffAction.REJECT,
                            decisionReason = suppressionResult.outcome.name
                        )
                    }
                    AtomicSuppressionOutcome.REPOSITORY_FAILURE -> {
                        NormalAlarmHandoffResult(
                            outcome = NormalAlarmHandoffOutcome.REPOSITORY_FAILURE,
                            diagnosticDetail = suppressionResult.diagnosticDetail,
                            admissionKey = canonicalAdmissionKey,
                            actionTaken = NormalAlarmHandoffAction.REJECT,
                            decisionReason = suppressionResult.outcome.name
                        )
                    }
                    AtomicSuppressionOutcome.CAS_CONFLICT -> {
                        throw IllegalStateException("Suppression CAS conflict: ${suppressionResult.diagnosticDetail}")
                    }
                }
            }

            NormalAlarmHandoffAction.ADMIT -> {
                val resolvedAlarm = alarm ?: throw IllegalStateException("Alarm cannot be null on ADMIT")
                val resolvedOccurrence = occurrence ?: throw IllegalStateException("Occurrence cannot be null on ADMIT")
                val resolvedAuthority = authority ?: throw IllegalStateException("Authority cannot be null on ADMIT")

                val resolvedAutoSilence = request.effectiveNormalAutoSilenceMinutes
                    ?: throw IllegalStateException("effectiveNormalAutoSilenceMinutes cannot be null on ADMIT")

                val admitEvent = AlarmSessionEvent.AdmitSession(
                    alarm = resolvedAlarm,
                    settings = request.settings,
                    effectiveNormalAutoSilenceMinutes = resolvedAutoSilence,
                    fireId = request.expectedFireId,
                    occurrenceKey = occurrenceId.key,
                    canonicalBaseTriggerMs = resolvedOccurrence.baseTriggerTime,
                    scheduledTargetEpochMs = resolvedOccurrence.scheduledAt,
                    nowEpochMs = request.nowEpochMs,
                    elapsedRealtimeMs = request.elapsedRealtimeMs,
                    bootCount = request.bootCount
                )

                val prepared = admissionFactory.prepareAdmission(admitEvent)

                // Verify prepared entities match verified occurrence and canonical admission identity
                if (prepared.session.alarmId != request.expectedAlarmId ||
                    prepared.session.fireId != request.expectedFireId ||
                    prepared.session.occurrenceKey != occurrenceId.key ||
                    prepared.session.canonicalBaseTriggerMs != resolvedOccurrence.baseTriggerTime ||
                    prepared.session.scheduledTargetEpochMs != resolvedOccurrence.scheduledAt ||
                    prepared.tombstone.admissionKey != canonicalAdmissionKey ||
                    prepared.tombstone.occurrenceKey != occurrenceId.key ||
                    prepared.tombstone.sessionId != prepared.session.sessionId
                ) {
                    throw IllegalStateException("Prepared admission entity mismatch with verified occurrence identity")
                }

                // Guarded CAS: ISSUED -> ADMITTED
                cutpointInterceptor?.invoke(Cutpoint.BEFORE_OCCURRENCE_CAS)
                val casUpdated = issuedDao.casUpdateOccurrenceStatus(
                    issueToken = request.issueToken,
                    expectedStatus = IssuedOccurrence.STATUS_ISSUED,
                    newStatus = IssuedOccurrence.STATUS_ADMITTED,
                    updatedAt = request.nowEpochMs
                )
                if (casUpdated != 1) {
                    throw IllegalStateException("Occurrence status update CAS failed (expected 1 row, got $casUpdated)")
                }
                cutpointInterceptor?.invoke(Cutpoint.AFTER_OCCURRENCE_CAS)

                // Insert Tombstone and Session with monotonic sequence allocation
                val interceptor = cutpointInterceptor
                if (interceptor != null) {
                    sessionDao.insertAdmissionTombstone(prepared.tombstone)
                    interceptor.invoke(Cutpoint.AFTER_TOMBSTONE_INSERT)

                    val currentSeq = sessionDao.getLastSequence() ?: 0L
                    if (currentSeq == Long.MAX_VALUE) {
                        throw com.sysadmindoc.alarmclock.data.local.SequenceOverflowException("Session sequence counter overflow")
                    }
                    val nextSeq = currentSeq + 1L
                    sessionDao.updateSequenceGenerator(com.sysadmindoc.alarmclock.data.local.entity.SessionSequenceGeneratorEntity(1, nextSeq))
                    interceptor.invoke(Cutpoint.AFTER_SEQUENCE_ALLOCATION)

                    val sequencedSession = prepared.session.copy(creationSequence = nextSeq)
                    sessionDao.insertSession(sequencedSession)
                } else {
                    sessionDao.allocateSequenceAndInsertSession(
                        session = prepared.session,
                        tombstone = prepared.tombstone
                    )
                }

                // Inactive Post-Fire Atomic Recovery Intent Persistence (KNO-37 Phase H2-C4-P3B)
                val effectiveRecovery = if (resolvedAlarm.isEnabled) {
                    preparedRecovery ?: throw IllegalStateException("Missing required prepared recovery inputs for state-changing handoff")
                } else null

                val committedIntent = if (effectiveRecovery != null) {
                    // Revalidate preparation dependencies inside Room transaction
                    if (resolvedAlarm.generation != effectiveRecovery.expectedAlarmGeneration ||
                        resolvedAuthority.scheduleRevision != effectiveRecovery.expectedRevision ||
                        resolvedAuthority.definitionRevision != effectiveRecovery.expectedDefinitionRevision
                    ) {
                        throw IllegalStateException("Authority or alarm generation modified concurrently between PREPARE and Room transaction (expected rev=${effectiveRecovery.expectedRevision}, current rev=${resolvedAuthority.scheduleRevision})")
                    }

                    if (effectiveRecovery.isRecurringSchedule) {
                        // Repeating Alarm:
                        // 1. Update Alarm definition nextTrigger using pre-claim revision fence
                        cutpointInterceptor?.invoke(Cutpoint.BEFORE_ALARM_DEFINITION_CAS)
                        val alarmUpdated = if (resolvedAlarm.nextTriggerTime == 0L) {
                            val initialStrict = alarmDao.casUpdateNextTriggerWithStrictAuthority(
                                id = resolvedAlarm.id,
                                expectedTrigger = 0L,
                                newTrigger = effectiveRecovery.nextTriggerTime,
                                expectedRevision = resolvedAuthority.scheduleRevision,
                                instanceToken = resolvedAuthority.instanceToken,
                                restoreEpoch = resolvedAuthority.restoreEpoch,
                                definitionRevision = resolvedAuthority.definitionRevision
                            )
                            if (initialStrict == 0) {
                                alarmDao.casUpdateNextTriggerInitialWithAuthority(
                                    id = resolvedAlarm.id,
                                    newTrigger = effectiveRecovery.nextTriggerTime,
                                    expectedRevision = resolvedAuthority.scheduleRevision,
                                    instanceToken = resolvedAuthority.instanceToken,
                                    restoreEpoch = resolvedAuthority.restoreEpoch,
                                    definitionRevision = resolvedAuthority.definitionRevision
                                )
                            } else {
                                initialStrict
                            }
                        } else {
                            alarmDao.casUpdateNextTriggerWithStrictAuthority(
                                id = resolvedAlarm.id,
                                expectedTrigger = resolvedAlarm.nextTriggerTime,
                                newTrigger = effectiveRecovery.nextTriggerTime,
                                expectedRevision = resolvedAuthority.scheduleRevision,
                                instanceToken = resolvedAuthority.instanceToken,
                                restoreEpoch = resolvedAuthority.restoreEpoch,
                                definitionRevision = resolvedAuthority.definitionRevision
                            )
                        }
                        if (alarmUpdated != 1) {
                            throw IllegalStateException("Alarm nextTrigger CAS update failed (expected 1 row, got $alarmUpdated; expectedTrigger=${resolvedAlarm.nextTriggerTime})")
                        }
                        cutpointInterceptor?.invoke(Cutpoint.AFTER_ALARM_DEFINITION_CAS)

                        // 2. Claim ScheduleAuthority intent
                        cutpointInterceptor?.invoke(Cutpoint.BEFORE_AUTHORITY_CAS)
                        val claimed = authorityDao.casClaimAuthority(
                            alarmId = resolvedAlarm.id,
                            instanceToken = resolvedAuthority.instanceToken,
                            restoreEpoch = resolvedAuthority.restoreEpoch,
                            expectedRevision = resolvedAuthority.scheduleRevision,
                            expectedLifecycle = resolvedAuthority.lifecycle,
                            desiredOperation = "SET",
                            desiredTriggerMillis = effectiveRecovery.nextTriggerTime,
                            actionKey = effectiveRecovery.actionKey
                        )
                        if (claimed != 1) {
                            throw IllegalStateException("ScheduleAuthority CAS claim failed (expected 1 row, got $claimed)")
                        }
                        cutpointInterceptor?.invoke(Cutpoint.AFTER_AUTHORITY_CAS)

                        CommittedAuthorityRecoveryIntent(
                            alarmId = resolvedAlarm.id,
                            claimedRevision = resolvedAuthority.scheduleRevision + 1L,
                            lifecycle = resolvedAuthority.lifecycle,
                            desiredOperation = "SET",
                            desiredTriggerMillis = effectiveRecovery.nextTriggerTime,
                            actionKey = effectiveRecovery.actionKey,
                            reconcileState = "RECONCILING"
                        )
                    } else {
                        // One-Time Alarm:
                        // 1. Disable Alarm definition using pre-claim revision fence
                        cutpointInterceptor?.invoke(Cutpoint.BEFORE_ALARM_DEFINITION_CAS)
                        val alarmDisabled = alarmDao.casSetEnabledWithStrictAuthority(
                            id = resolvedAlarm.id,
                            enabled = false,
                            nextTrigger = 0L,
                            expectedRevision = resolvedAuthority.scheduleRevision,
                            instanceToken = resolvedAuthority.instanceToken,
                            restoreEpoch = resolvedAuthority.restoreEpoch,
                            definitionRevision = resolvedAuthority.definitionRevision
                        )
                        if (alarmDisabled != 1) {
                            throw IllegalStateException("Alarm definition disable CAS failed (expected 1, got $alarmDisabled)")
                        }
                        cutpointInterceptor?.invoke(Cutpoint.AFTER_ALARM_DEFINITION_CAS)

                        // 2. Update ScheduleAuthority lifecycle to DISABLED / CANCEL
                        cutpointInterceptor?.invoke(Cutpoint.BEFORE_AUTHORITY_CAS)
                        val claimed = authorityDao.casUpdateLifecycle(
                            alarmId = resolvedAlarm.id,
                            instanceToken = resolvedAuthority.instanceToken,
                            restoreEpoch = resolvedAuthority.restoreEpoch,
                            expectedRevision = resolvedAuthority.scheduleRevision,
                            expectedLifecycle = resolvedAuthority.lifecycle,
                            newLifecycle = "DISABLED",
                            desiredOperation = "CANCEL",
                            desiredTriggerMillis = null,
                            actionKey = effectiveRecovery.actionKey
                        )
                        if (claimed != 1) {
                            throw IllegalStateException("ScheduleAuthority lifecycle CAS failed (expected 1 row, got $claimed)")
                        }
                        cutpointInterceptor?.invoke(Cutpoint.AFTER_AUTHORITY_CAS)

                        CommittedAuthorityRecoveryIntent(
                            alarmId = resolvedAlarm.id,
                            claimedRevision = resolvedAuthority.scheduleRevision + 1L,
                            lifecycle = "DISABLED",
                            desiredOperation = "CANCEL",
                            desiredTriggerMillis = null,
                            actionKey = effectiveRecovery.actionKey,
                            reconcileState = "RECONCILING"
                        )
                    }
                } else null

                cutpointInterceptor?.invoke(Cutpoint.BEFORE_TRANSACTION_COMMIT)

                NormalAlarmHandoffResult(
                    outcome = NormalAlarmHandoffOutcome.ADMITTED,
                    diagnosticDetail = "Session successfully admitted",
                    sessionId = prepared.session.sessionId,
                    admissionKey = canonicalAdmissionKey,
                    actionTaken = plan.action,
                    decisionReason = plan.decisionReason,
                    committedAuthorityIntent = committedIntent
                )
            }
        }
    }

    private suspend fun verifyStructuralConsistency(
        tombstone: AlarmAdmissionTombstoneEntity?,
        occurrenceStatus: String,
        expectedAlarmId: Long,
        expectedOccurrenceKey: String,
        canonicalAdmissionKey: String,
        sessionDao: com.sysadmindoc.alarmclock.data.local.AlarmSessionDao
    ): StructuralAdmissionState {
        if (occurrenceStatus == IssuedOccurrence.STATUS_ADMITTED && tombstone == null) {
            return StructuralAdmissionState.Corrupted(
                "Occurrence status is ADMITTED but admission tombstone is missing for key $canonicalAdmissionKey"
            )
        }

        if (occurrenceStatus == IssuedOccurrence.STATUS_SUPPRESSED && tombstone != null) {
            return StructuralAdmissionState.Corrupted(
                "Occurrence status is SUPPRESSED but admission tombstone exists for key $canonicalAdmissionKey"
            )
        }

        if (tombstone != null) {
            val session = sessionDao.getSessionById(tombstone.sessionId)
                ?: return StructuralAdmissionState.Corrupted(
                    "Admission tombstone exists ($canonicalAdmissionKey) but referenced session (${tombstone.sessionId}) is missing in DB"
                )

            val matches = tombstone.alarmId == expectedAlarmId &&
                    tombstone.occurrenceKey == expectedOccurrenceKey &&
                    session.alarmId == expectedAlarmId &&
                    session.occurrenceKey == expectedOccurrenceKey &&
                    session.admissionKey == tombstone.admissionKey &&
                    session.sessionId == tombstone.sessionId

            if (!matches) {
                return StructuralAdmissionState.Corrupted(
                    "Tombstone or Session identity mismatch with canonical occurrence"
                )
            }

            if (occurrenceStatus != IssuedOccurrence.STATUS_ADMITTED) {
                return StructuralAdmissionState.Corrupted(
                    "Tombstone and session exist for key $canonicalAdmissionKey but occurrence status is $occurrenceStatus (expected ADMITTED)"
                )
            }

            return StructuralAdmissionState.ValidDuplicate(tombstone, session)
        }

        return StructuralAdmissionState.Unadmitted
    }
}

/**
 * Internal taxonomy for structural admission validation.
 */
sealed class StructuralAdmissionState {
    object Unadmitted : StructuralAdmissionState()
    data class ValidDuplicate(
        val tombstone: AlarmAdmissionTombstoneEntity,
        val session: AlarmSessionEntity
    ) : StructuralAdmissionState()
    data class Corrupted(val reason: String) : StructuralAdmissionState()
}
