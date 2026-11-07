package com.sysadmindoc.alarmclock.domain.evaluator

import androidx.room.withTransaction
import com.sysadmindoc.alarmclock.data.local.AlarmDatabase
import com.sysadmindoc.alarmclock.data.local.entity.AlarmAdmissionTombstoneEntity
import com.sysadmindoc.alarmclock.data.local.entity.AlarmSessionEntity
import com.sysadmindoc.alarmclock.data.local.entity.IssuedOccurrence
import com.sysadmindoc.alarmclock.data.model.Alarm
import com.sysadmindoc.alarmclock.data.model.AlarmOccurrenceId
import com.sysadmindoc.alarmclock.data.preferences.AppSettings
import com.sysadmindoc.alarmclock.domain.session.AlarmSessionEvent
import com.sysadmindoc.alarmclock.domain.session.NormalAlarmAdmissionFactory
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
 * Result of an atomic normal alarm handoff operation.
 */
data class NormalAlarmHandoffResult(
    val outcome: NormalAlarmHandoffOutcome,
    val diagnosticDetail: String? = null,
    val sessionId: String? = null,
    val admissionKey: String? = null,
    val actionTaken: NormalAlarmHandoffAction? = null,
    val decisionReason: String? = null
)

/**
 * Inactive, fail-closed coordinator executing atomic Normal Alarm handoff decisions
 * and database mutations inside a single Room transaction boundary (KNO-37 Phase H2-C2-B / H2-C2-B1).
 */
class NormalAlarmHandoffCoordinator(
    private val database: AlarmDatabase,
    private val admissionFactory: NormalAlarmAdmissionFactory = NormalAlarmAdmissionFactory(),
    private val planner: NormalAlarmHandoffPlanner = NormalAlarmHandoffPlanner(),
    private val suppressionCoordinator: AtomicOccurrenceSuppressionCoordinator = AtomicOccurrenceSuppressionCoordinator(database)
) {

    /**
     * Testing cutpoints for crash and rollback verification (KNO-37 Phase H2-C2-C).
     */
    internal var cutpointInterceptor: ((Cutpoint) -> Unit)? = null

    enum class Cutpoint {
        BEFORE_OCCURRENCE_CAS,
        AFTER_OCCURRENCE_CAS,
        AFTER_TOMBSTONE_INSERT,
        AFTER_SEQUENCE_ALLOCATION,
        BEFORE_TRANSACTION_COMMIT
    }

    /**
     * Executes atomic normal alarm handoff inside a single Room database transaction.
     */
    suspend fun coordinateHandoff(request: NormalAlarmHandoffRequest): NormalAlarmHandoffResult {
        return try {
            database.withTransaction {
                executeHandoff(request)
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            val isConstraint = e is android.database.sqlite.SQLiteConstraintException
            val isCasConflict = isConstraint || (e is IllegalStateException && e.message?.contains("CAS") == true)
            NormalAlarmHandoffResult(
                outcome = if (isCasConflict) NormalAlarmHandoffOutcome.CAS_CONFLICT else NormalAlarmHandoffOutcome.REPOSITORY_FAILURE,
                diagnosticDetail = "Transaction failed: ${e.message}",
                actionTaken = NormalAlarmHandoffAction.REJECT,
                decisionReason = "TRANSACTION_FAILURE"
            )
        }
    }

    /**
     * Executes authoritative fact gathering, structural consistency verification,
     * pure decision planning, and safe mutation within the active transaction boundary.
     */
    internal suspend fun executeHandoff(request: NormalAlarmHandoffRequest): NormalAlarmHandoffResult {
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
            if (!alarm.isEnabled) {
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

        val isProvenanceValid = authority != null &&
                isIdentityValid &&
                authority.alarmId == request.expectedAlarmId &&
                ScheduleAuthorityFingerprint.fromAuthority(authority) == request.expectedAuthorityFingerprint &&
                !occurrence.authorityInstanceToken.isNullOrBlank() &&
                occurrence.authorityInstanceToken == authority.instanceToken &&
                occurrence.restoreEpoch == authority.restoreEpoch &&
                occurrence.scheduleRevision == authority.scheduleRevision &&
                occurrence.definitionRevision == authority.definitionRevision

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
                        NormalAlarmHandoffResult(
                            outcome = NormalAlarmHandoffOutcome.SUPPRESSED,
                            diagnosticDetail = suppressionResult.diagnosticDetail,
                            admissionKey = canonicalAdmissionKey,
                            actionTaken = plan.action,
                            decisionReason = plan.decisionReason
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
                cutpointInterceptor?.invoke(Cutpoint.BEFORE_TRANSACTION_COMMIT)

                NormalAlarmHandoffResult(
                    outcome = NormalAlarmHandoffOutcome.ADMITTED,
                    diagnosticDetail = "Session successfully admitted",
                    sessionId = prepared.session.sessionId,
                    admissionKey = canonicalAdmissionKey,
                    actionTaken = plan.action,
                    decisionReason = plan.decisionReason
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
