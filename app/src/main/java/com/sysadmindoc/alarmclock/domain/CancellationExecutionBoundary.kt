package com.sysadmindoc.alarmclock.domain

/**
 * Enumeration of all 8 cancellation side-effect targets in AlarmClockXtreme.
 */
enum class SideEffectTarget {
    MAIN_OS_PENDING_INTENT,
    DIRECT_BOOT_SNAPSHOT,
    SMART_ALARM_PENDING_INTENT,
    HUE_SUNRISE_WORKER,
    FIRE_WATCHDOG_WORKER,
    GUARDIAN_WORKER,
    WAKE_CONFIRM_WORKER_AND_STORE,
    ISSUED_OCCURRENCE_TOKEN
}

/**
 * Observed status of OS PendingIntent lookup.
 */
enum class PendingIntentLookupStatus {
    NOT_ATTEMPTED,
    LOOKUP_FOUND,
    LOOKUP_NULL
}

/**
 * Observed status of OS AlarmManager cancel invocation.
 */
enum class OsCancelInvocationStatus {
    NOT_ATTEMPTED,
    INVOCATION_COMPLETED,
    INVOCATION_FAILED
}

/**
 * Proven cancellation status of the target OS alarm registration.
 */
enum class OsCancelProofStatus {
    UNCERTAIN,
    UNPROVEN_IDENTITY,
    PROVEN_CANCELLED,
    PROVEN_NO_EXISTING
}

/**
 * Verified lineage status of DirectBoot or persistence storage.
 */
enum class DeLineageStatus {
    UNCERTAIN,
    LINEAGE_MISSING,
    LINEAGE_MISMATCH,
    VERIFIED_MATCH
}

/**
 * Scope and validity of successor occurrence exclusion.
 */
enum class SuccessorExclusionStatus {
    UNKNOWN,
    SUCCESSOR_EXISTS,
    EXPIRED_OUTSIDE_MUTEX,
    EXCLUDED_IN_MUTEX
}

/**
 * Immutable 4-way classification outcome for a specific side effect.
 *
 * MANDATORY INVARIANTS:
 * - [isPermitted] is ALWAYS `false`.
 * - [allowsDestructiveCleanup] is ALWAYS `false`.
 * - [isOsCancelCompleted] is ALWAYS `false`.
 * - [executesCancellation] is ALWAYS `false`.
 *
 * This pure model classifies evidence and prerequisites; it NEVER issues execution permits.
 */
sealed interface PureEffectDecision {
    val target: SideEffectTarget

    val isPermitted: Boolean
        get() = false

    val allowsDestructiveCleanup: Boolean
        get() = false

    val isOsCancelCompleted: Boolean
        get() = false

    val executesCancellation: Boolean
        get() = false

    data class Blocked(
        override val target: SideEffectTarget,
        val reason: CancellationRejectionReason,
        val detail: String
    ) : PureEffectDecision

    data class Deferred(
        override val target: SideEffectTarget,
        val reason: CancellationRejectionReason,
        val detail: String
    ) : PureEffectDecision

    data class EvidenceIncomplete(
        override val target: SideEffectTarget,
        val missingWitness: String,
        val detail: String
    ) : PureEffectDecision

    data class CandidateForFutureVerification(
        override val target: SideEffectTarget,
        val verifiedWitnesses: List<String>
    ) : PureEffectDecision
}

/**
 * Input context carrying all observed evidence for pure boundary evaluation.
 */
data class BoundaryEvaluationContext(
    val callerIdentity: CallerOriginIdentity? = null,
    val cancellationFence: CancellationFence? = null,
    val plannerOutcome: CancelOutcome? = null,
    val pendingIntentLookup: PendingIntentLookupStatus = PendingIntentLookupStatus.NOT_ATTEMPTED,
    val osCancelInvocation: OsCancelInvocationStatus = OsCancelInvocationStatus.NOT_ATTEMPTED,
    val osCancelProof: OsCancelProofStatus = OsCancelProofStatus.UNCERTAIN,
    val deLineageStatus: DeLineageStatus = DeLineageStatus.UNCERTAIN,
    val successorExclusionStatus: SuccessorExclusionStatus = SuccessorExclusionStatus.UNKNOWN,
    val isLegacyRed04Unresolved: Boolean = true,
    val includeFollowUpWorkers: Boolean = false
)

/**
 * Evaluation report containing decisions for all 8 side-effect targets.
 */
data class BoundaryEvaluationReport(
    val alarmId: Long,
    val decisions: Map<SideEffectTarget, PureEffectDecision>
) {
    val isAllNonExecutable: Boolean
        get() = decisions.values.all {
            !it.isPermitted && !it.allowsDestructiveCleanup && !it.isOsCancelCompleted && !it.executesCancellation
        }
}

/**
 * Pure, deterministic, side-effect-free decision boundary for KNO-75 R2-S1-B2-A.
 */
object CancellationExecutionBoundary {

    fun evaluate(context: BoundaryEvaluationContext): BoundaryEvaluationReport {
        val alarmId = context.cancellationFence?.alarmId ?: context.callerIdentity?.alarmId ?: -1L

        // 1. Pre-check: Caller Origin, Fence, and Planner Outcome must be present
        if (context.callerIdentity == null || context.cancellationFence == null || context.plannerOutcome == null) {
            val incompleteMap = SideEffectTarget.values().associateWith { target ->
                PureEffectDecision.EvidenceIncomplete(
                    target = target,
                    missingWitness = "PLANNER_OUTCOME_MISSING",
                    detail = "CallerOriginIdentity, CancellationFence, or CancelOutcome is missing"
                )
            }
            return BoundaryEvaluationReport(alarmId = alarmId, decisions = incompleteMap)
        }

        // 2. Check Planner Outcome: If not Accepted, fail-closed all targets with exact rejection reason
        if (context.plannerOutcome !is CancelOutcome.Accepted) {
            val (reason, detail) = when (val outcome = context.plannerOutcome) {
                is CancelOutcome.NoOpAbsent -> outcome.reason to outcome.detail
                is CancelOutcome.StaleRejected -> outcome.reason to outcome.detail
                is CancelOutcome.IdentityMismatch -> outcome.reason to outcome.detail
                is CancelOutcome.AuthorityUnavailable -> outcome.reason to outcome.detail
                is CancelOutcome.OsStateUncertain -> outcome.reason to outcome.detail
                is CancelOutcome.PersistenceUncertain -> outcome.reason to outcome.detail
                else -> CancellationRejectionReason.AUTHORITY_UNAVAILABLE to "Planner rejected cancellation"
            }
            val blockedMap = SideEffectTarget.values().associateWith { target ->
                PureEffectDecision.Blocked(
                    target = target,
                    reason = reason,
                    detail = detail
                )
            }
            return BoundaryEvaluationReport(alarmId = alarmId, decisions = blockedMap)
        }

        // 3. Evaluate each of the 8 side effects independently
        val decisions = SideEffectTarget.values().associateWith { target ->
            evaluateTarget(target, context)
        }

        return BoundaryEvaluationReport(alarmId = alarmId, decisions = decisions)
    }

    private fun evaluateTarget(
        target: SideEffectTarget,
        context: BoundaryEvaluationContext
    ): PureEffectDecision {
        // Universal check: Successor Exclusion
        if (context.successorExclusionStatus == SuccessorExclusionStatus.SUCCESSOR_EXISTS) {
            return PureEffectDecision.Blocked(
                target = target,
                reason = CancellationRejectionReason.STALE_GENERATION,
                detail = "Successor occurrence exists in storage"
            )
        }

        if (context.successorExclusionStatus == SuccessorExclusionStatus.EXPIRED_OUTSIDE_MUTEX) {
            return PureEffectDecision.Deferred(
                target = target,
                reason = CancellationRejectionReason.OS_STATE_UNCERTAIN,
                detail = "Slot Mutex lock expired outside lock scope"
            )
        }

        return when (target) {
            SideEffectTarget.MAIN_OS_PENDING_INTENT -> {
                if (context.isLegacyRed04Unresolved) {
                    PureEffectDecision.Blocked(
                        target = target,
                        reason = CancellationRejectionReason.OS_STATE_UNCERTAIN,
                        detail = "Legacy V1 PendingIntent identity cannot prove exact occurrence cancellation under red04"
                    )
                } else if (context.pendingIntentLookup == PendingIntentLookupStatus.LOOKUP_NULL) {
                    PureEffectDecision.EvidenceIncomplete(
                        target = target,
                        missingWitness = "PENDING_INTENT_LOOKUP_NULL",
                        detail = "PendingIntent lookup returned null"
                    )
                } else if (context.osCancelProof == OsCancelProofStatus.UNPROVEN_IDENTITY) {
                    PureEffectDecision.Blocked(
                        target = target,
                        reason = CancellationRejectionReason.TARGET_WITNESS_MISMATCH,
                        detail = "OS cancellation succeeded but target identity was unproven"
                    )
                } else if (context.osCancelInvocation == OsCancelInvocationStatus.INVOCATION_FAILED ||
                    context.osCancelProof == OsCancelProofStatus.UNCERTAIN
                ) {
                    PureEffectDecision.EvidenceIncomplete(
                        target = target,
                        missingWitness = "OS_STATE_UNKNOWN",
                        detail = "OS cancellation invocation failed or state is uncertain"
                    )
                } else {
                    PureEffectDecision.CandidateForFutureVerification(
                        target = target,
                        verifiedWitnesses = listOf("QUALIFIED_CALLER", "ACCEPTED_PLAN", "PROVEN_OS_IDENTITY")
                    )
                }
            }

            SideEffectTarget.DIRECT_BOOT_SNAPSHOT -> {
                if (context.deLineageStatus == DeLineageStatus.LINEAGE_MISSING ||
                    context.deLineageStatus == DeLineageStatus.LINEAGE_MISMATCH ||
                    context.deLineageStatus == DeLineageStatus.UNCERTAIN
                ) {
                    PureEffectDecision.Blocked(
                        target = target,
                        reason = CancellationRejectionReason.PERSISTENCE_UNCERTAIN,
                        detail = "DirectBoot DE snapshot lacks instanceToken or lineage match"
                    )
                } else if (context.osCancelInvocation == OsCancelInvocationStatus.INVOCATION_FAILED ||
                    context.osCancelProof == OsCancelProofStatus.UNCERTAIN
                ) {
                    PureEffectDecision.EvidenceIncomplete(
                        target = target,
                        missingWitness = "OS_STATE_UNKNOWN",
                        detail = "OS cancellation invocation failed or state is uncertain"
                    )
                } else if (context.osCancelProof == OsCancelProofStatus.PROVEN_CANCELLED ||
                    context.osCancelProof == OsCancelProofStatus.PROVEN_NO_EXISTING
                ) {
                    PureEffectDecision.CandidateForFutureVerification(
                        target = target,
                        verifiedWitnesses = listOf("QUALIFIED_CALLER", "ACCEPTED_PLAN", "DE_LINEAGE_MATCH", "MUTEX_EXCLUSION")
                    )
                } else {
                    PureEffectDecision.EvidenceIncomplete(
                        target = target,
                        missingWitness = "DE_LINEAGE_OR_OS_PROOF",
                        detail = "DE snapshot evidence incomplete"
                    )
                }
            }

            SideEffectTarget.SMART_ALARM_PENDING_INTENT,
            SideEffectTarget.HUE_SUNRISE_WORKER,
            SideEffectTarget.FIRE_WATCHDOG_WORKER -> {
                PureEffectDecision.CandidateForFutureVerification(
                    target = target,
                    verifiedWitnesses = listOf("QUALIFIED_CALLER", "ACCEPTED_PLAN")
                )
            }

            SideEffectTarget.GUARDIAN_WORKER,
            SideEffectTarget.WAKE_CONFIRM_WORKER_AND_STORE -> {
                if (!context.includeFollowUpWorkers) {
                    PureEffectDecision.Blocked(
                        target = target,
                        reason = CancellationRejectionReason.KIND_MISMATCH,
                        detail = "Follow-up workers not included in cancellation scope"
                    )
                } else {
                    PureEffectDecision.CandidateForFutureVerification(
                        target = target,
                        verifiedWitnesses = listOf("QUALIFIED_CALLER", "ACCEPTED_PLAN", "FOLLOW_UP_INCLUDED")
                    )
                }
            }

            SideEffectTarget.ISSUED_OCCURRENCE_TOKEN -> {
                PureEffectDecision.CandidateForFutureVerification(
                    target = target,
                    verifiedWitnesses = listOf("QUALIFIED_CALLER", "ACCEPTED_PLAN", "TOKEN_MATCH")
                )
            }
        }
    }
}
