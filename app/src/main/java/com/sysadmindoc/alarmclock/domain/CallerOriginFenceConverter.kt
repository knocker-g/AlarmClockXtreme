package com.sysadmindoc.alarmclock.domain

import com.sysadmindoc.alarmclock.data.model.Alarm

/**
 * Pure, deterministic, side-effect-free propagation boundary from [CallerOriginIdentity]
 * into S1-A [CancellationFence] and [FencedCancellationPlanner].
 *
 * Design guarantees (KNO-75 C7-R2-S1-B1):
 * - Reuses S1-A [CancellationFence] and [FencedCancellationPlanner] without duplicating
 *   cancellation evaluation logic.
 * - Never synthesizes or backfills missing caller-origin fields from newer Room or
 *   `ScheduleAuthority` snapshots.
 * - Executes zero OS AlarmManager cancellation, DirectBoot mutation, Room I/O, or
 *   WorkManager side effects.
 * - Always preserves `allowsDestructiveCleanup == false` and `isOsCancelCompleted == false`.
 */
object CallerOriginFenceConverter {

    /**
     * Validates [origin] strictly against its own captured fields and converts complete,
     * well-typed origin identities into a [CallerOriginPropagationResult.QualifiedCandidate]
     * carrying an S1-A [CancellationFence].
     *
     * Because this method takes NO Room or Authority context, it is structurally impossible
     * to synthesize missing identity fields from newer Room state.
     */
    fun toFenceCandidate(origin: CallerOriginIdentity): CallerOriginPropagationResult {
        if (origin.alarmId <= 0L) {
            return CallerOriginPropagationResult.Rejected(
                alarmId = origin.alarmId,
                originIdentity = origin,
                reason = CancellationRejectionReason.MISSING_OR_INVALID_ALARM_ID,
                detail = "alarmId must be > 0"
            )
        }

        if (origin.operationKind == CallerOperationKind.LEGACY_ALARM_ID_ONLY) {
            return CallerOriginPropagationResult.Unqualified(
                alarmId = origin.alarmId,
                originIdentity = origin,
                unqualifiedReason = CallerOriginUnqualifiedReason.LEGACY_ALARM_ID_ONLY_CALLER,
                plannerReason = CancellationRejectionReason.MISSING_AUTHORITY_LINEAGE,
                detail = "Legacy alarmId-only caller (${origin.sourceTag}) lacks verifiable occurrence identity",
                missingFields = setOf(
                    "alarmKind",
                    "generation",
                    "revision",
                    "scheduledTriggerMillis",
                    "scheduleAuthorityRevision",
                    "definitionRevision",
                    "instanceToken",
                    "restoreEpoch"
                )
            )
        }

        val kind = origin.alarmKind
        if (kind.isNullOrBlank()) {
            return CallerOriginPropagationResult.Unqualified(
                alarmId = origin.alarmId,
                originIdentity = origin,
                unqualifiedReason = CallerOriginUnqualifiedReason.MISSING_ALARM_KIND,
                plannerReason = CancellationRejectionReason.INVALID_KIND,
                detail = "alarmKind is missing",
                missingFields = setOf("alarmKind")
            )
        }

        val isQuick = kind.equals(Alarm.KIND_QUICK, ignoreCase = true)
        val isNormal = kind.equals(Alarm.KIND_NORMAL, ignoreCase = true)
        if (!isQuick && !isNormal) {
            return CallerOriginPropagationResult.Rejected(
                alarmId = origin.alarmId,
                originIdentity = origin,
                reason = CancellationRejectionReason.INVALID_KIND,
                detail = "Unsupported alarmKind '$kind'"
            )
        }

        if (isQuick) {
            return convertQuickOrigin(origin)
        }
        return convertNormalOrigin(origin)
    }

    /**
     * Propagates [origin] into [FencedCancellationPlanner.evaluate] ONLY when [origin] itself
     * passes [toFenceCandidate]. Unqualified or rejected origins fail closed immediately without
     * consulting [context], preventing newer Room/Authority snapshots from backfilling A's gaps.
     */
    fun evaluateWithPlanner(
        origin: CallerOriginIdentity,
        context: CancellationEvaluationContext,
        includeFollowUpWorkers: Boolean = true
    ): CallerOriginPropagationResult {
        val candidate = toFenceCandidate(origin)
        if (candidate !is CallerOriginPropagationResult.QualifiedCandidate) {
            return candidate
        }

        val outcome = FencedCancellationPlanner.evaluate(
            fence = candidate.fence,
            context = context,
            includeFollowUpWorkers = includeFollowUpWorkers
        )

        return when (outcome) {
            is CancelOutcome.Accepted -> CallerOriginPropagationResult.PlannedCandidate(
                alarmId = origin.alarmId,
                originIdentity = origin,
                fence = candidate.fence,
                outcome = outcome
            )
            is CancelOutcome.StaleRejected -> CallerOriginPropagationResult.Rejected(
                alarmId = origin.alarmId,
                originIdentity = origin,
                reason = outcome.reason,
                detail = outcome.detail,
                fence = candidate.fence,
                outcome = outcome
            )
            is CancelOutcome.IdentityMismatch -> CallerOriginPropagationResult.Rejected(
                alarmId = origin.alarmId,
                originIdentity = origin,
                reason = outcome.reason,
                detail = outcome.detail,
                fence = candidate.fence,
                outcome = outcome
            )
            is CancelOutcome.KindMismatch -> CallerOriginPropagationResult.Rejected(
                alarmId = origin.alarmId,
                originIdentity = origin,
                reason = outcome.reason,
                detail = outcome.detail,
                fence = candidate.fence,
                outcome = outcome
            )
            is CancelOutcome.InvalidFence -> CallerOriginPropagationResult.Rejected(
                alarmId = origin.alarmId,
                originIdentity = origin,
                reason = outcome.reason,
                detail = outcome.detail,
                fence = candidate.fence,
                outcome = outcome
            )
            is CancelOutcome.AuthorityUnavailable -> CallerOriginPropagationResult.Deferred(
                alarmId = origin.alarmId,
                originIdentity = origin,
                unqualifiedReason = CallerOriginUnqualifiedReason.AUTHORITY_UNAVAILABLE,
                plannerReason = outcome.reason,
                detail = outcome.detail,
                fence = candidate.fence,
                outcome = outcome
            )
            is CancelOutcome.OsStateUncertain -> CallerOriginPropagationResult.Deferred(
                alarmId = origin.alarmId,
                originIdentity = origin,
                unqualifiedReason = CallerOriginUnqualifiedReason.OS_STATE_UNCERTAIN,
                plannerReason = outcome.reason,
                detail = outcome.detail,
                fence = candidate.fence,
                outcome = outcome
            )
            is CancelOutcome.PersistenceUncertain -> CallerOriginPropagationResult.Deferred(
                alarmId = origin.alarmId,
                originIdentity = origin,
                unqualifiedReason = CallerOriginUnqualifiedReason.PERSISTENCE_UNCERTAIN,
                plannerReason = outcome.reason,
                detail = outcome.detail,
                fence = candidate.fence,
                outcome = outcome
            )
            is CancelOutcome.NoOpAbsent -> CallerOriginPropagationResult.Deferred(
                alarmId = origin.alarmId,
                originIdentity = origin,
                unqualifiedReason = CallerOriginUnqualifiedReason.ALREADY_ABSENT_NOOP,
                plannerReason = outcome.reason,
                detail = outcome.detail,
                fence = candidate.fence,
                outcome = outcome
            )
        }
    }

    private fun convertQuickOrigin(origin: CallerOriginIdentity): CallerOriginPropagationResult {
        if (origin.operationKind != CallerOperationKind.QUICK_OCCURRENCE) {
            return CallerOriginPropagationResult.Rejected(
                alarmId = origin.alarmId,
                originIdentity = origin,
                reason = CancellationRejectionReason.KIND_MISMATCH,
                detail = "Quick alarm origin cannot use Normal operation ${origin.operationKind}"
            )
        }

        val missingFields = linkedSetOf<String>()
        if (origin.quickTriggerEpochMs == null) missingFields.add("quickTriggerEpochMs")
        if (origin.quickRelativeElapsedMs == null) missingFields.add("quickRelativeElapsedMs")
        if (origin.quickBootCount == null) missingFields.add("quickBootCount")

        if (missingFields.isNotEmpty()) {
            return CallerOriginPropagationResult.Unqualified(
                alarmId = origin.alarmId,
                originIdentity = origin,
                unqualifiedReason = CallerOriginUnqualifiedReason.MISSING_QUICK_IDENTITY,
                plannerReason = CancellationRejectionReason.MISSING_QUICK_IDENTITY,
                detail = "Quick alarm origin missing required identity fields: $missingFields",
                missingFields = missingFields
            )
        }

        val triggerEpochMs = origin.quickTriggerEpochMs!!
        val relativeElapsedMs = origin.quickRelativeElapsedMs!!
        val bootCount = origin.quickBootCount!!
        if (triggerEpochMs <= 0L ||
            relativeElapsedMs < 0L ||
            (bootCount != -1L && bootCount <= 0L) ||
            (bootCount > 0L && relativeElapsedMs <= 0L)
        ) {
            return CallerOriginPropagationResult.Rejected(
                alarmId = origin.alarmId,
                originIdentity = origin,
                reason = CancellationRejectionReason.MISSING_QUICK_IDENTITY,
                detail = "Quick alarm identity tuple contains invalid values"
            )
        }

        return CallerOriginPropagationResult.QualifiedCandidate(
            alarmId = origin.alarmId,
            originIdentity = origin,
            fence = CancellationFence.QuickOccurrence(
                alarmId = origin.alarmId,
                kind = Alarm.KIND_QUICK,
                quickTriggerEpochMs = triggerEpochMs,
                quickRelativeElapsedMs = relativeElapsedMs,
                quickBootCount = bootCount
            )
        )
    }

    private fun convertNormalOrigin(origin: CallerOriginIdentity): CallerOriginPropagationResult {
        if (origin.operationKind == CallerOperationKind.QUICK_OCCURRENCE) {
            return CallerOriginPropagationResult.Rejected(
                alarmId = origin.alarmId,
                originIdentity = origin,
                reason = CancellationRejectionReason.KIND_MISMATCH,
                detail = "Normal alarm origin cannot use QUICK_OCCURRENCE operation"
            )
        }

        val missingGenRev = linkedSetOf<String>()
        if (origin.generation == null) missingGenRev.add("generation")
        if (origin.revision == null) missingGenRev.add("revision")
        if (missingGenRev.isNotEmpty()) {
            return CallerOriginPropagationResult.Unqualified(
                alarmId = origin.alarmId,
                originIdentity = origin,
                unqualifiedReason = CallerOriginUnqualifiedReason.MISSING_GENERATION_OR_REVISION,
                plannerReason = CancellationRejectionReason.MISSING_GENERATION_OR_REVISION,
                detail = "Normal alarm origin missing required fields: $missingGenRev",
                missingFields = missingGenRev
            )
        }

        val generation = origin.generation!!
        val revision = origin.revision!!
        if (generation < 1 || revision < 1) {
            return CallerOriginPropagationResult.Rejected(
                alarmId = origin.alarmId,
                originIdentity = origin,
                reason = CancellationRejectionReason.MISSING_GENERATION_OR_REVISION,
                detail = "generation ($generation) and revision ($revision) must both be >= 1"
            )
        }

        val triggerMillis = origin.scheduledTriggerMillis
        if (triggerMillis == null) {
            return CallerOriginPropagationResult.Unqualified(
                alarmId = origin.alarmId,
                originIdentity = origin,
                unqualifiedReason = CallerOriginUnqualifiedReason.MISSING_TRIGGER_TIME,
                plannerReason = CancellationRejectionReason.MISSING_TRIGGER_TIME,
                detail = "Normal alarm origin missing scheduledTriggerMillis",
                missingFields = setOf("scheduledTriggerMillis")
            )
        }
        if (triggerMillis < 0L) {
            return CallerOriginPropagationResult.Rejected(
                alarmId = origin.alarmId,
                originIdentity = origin,
                reason = CancellationRejectionReason.MISSING_TRIGGER_TIME,
                detail = "scheduledTriggerMillis ($triggerMillis) must be >= 0"
            )
        }
        if (origin.operationKind == CallerOperationKind.EXACT_OCCURRENCE &&
            triggerMillis == 0L &&
            !origin.allowZeroTriggerPreArm
        ) {
            return CallerOriginPropagationResult.Unqualified(
                alarmId = origin.alarmId,
                originIdentity = origin,
                unqualifiedReason = CallerOriginUnqualifiedReason.MISSING_TRIGGER_TIME,
                plannerReason = CancellationRejectionReason.MISSING_TRIGGER_TIME,
                detail = "ExactOccurrence origin requires scheduledTriggerMillis > 0 unless allowZeroTriggerPreArm=true",
                missingFields = setOf("scheduledTriggerMillis")
            )
        }

        val missingAuthorityFields = linkedSetOf<String>()
        if (origin.instanceToken.isNullOrBlank()) missingAuthorityFields.add("instanceToken")
        if (origin.restoreEpoch == null) missingAuthorityFields.add("restoreEpoch")
        if (origin.scheduleAuthorityRevision == null) missingAuthorityFields.add("scheduleAuthorityRevision")
        if (origin.definitionRevision == null) missingAuthorityFields.add("definitionRevision")
        if (missingAuthorityFields.isNotEmpty()) {
            return CallerOriginPropagationResult.Unqualified(
                alarmId = origin.alarmId,
                originIdentity = origin,
                unqualifiedReason = CallerOriginUnqualifiedReason.MISSING_AUTHORITY_LINEAGE,
                plannerReason = CancellationRejectionReason.MISSING_AUTHORITY_LINEAGE,
                detail = "Normal alarm origin missing required authority lineage: $missingAuthorityFields; refusing authority synthesis",
                missingFields = missingAuthorityFields
            )
        }

        val instanceToken = origin.instanceToken!!
        val restoreEpoch = origin.restoreEpoch!!
        val scheduleRevision = origin.scheduleAuthorityRevision!!
        val definitionRevision = origin.definitionRevision!!
        if (restoreEpoch <= 0L || scheduleRevision < 0L || definitionRevision < 0L) {
            return CallerOriginPropagationResult.Rejected(
                alarmId = origin.alarmId,
                originIdentity = origin,
                reason = CancellationRejectionReason.MISSING_AUTHORITY_LINEAGE,
                detail = "Normal alarm origin authority lineage contains invalid values"
            )
        }

        return when (origin.operationKind) {
            CallerOperationKind.EXACT_OCCURRENCE -> {
                if ((origin.targetScheduledAt != null && origin.targetScheduledAt <= 0L) ||
                    (origin.targetFireId != null && origin.targetFireId.isBlank())
                ) {
                    return CallerOriginPropagationResult.Rejected(
                        alarmId = origin.alarmId,
                        originIdentity = origin,
                        reason = CancellationRejectionReason.TARGET_WITNESS_MISMATCH,
                        detail = "Optional target witness fields must be valid when provided"
                    )
                }
                if (origin.targetScheduledAt != null &&
                    triggerMillis > 0L &&
                    origin.targetScheduledAt != triggerMillis
                ) {
                    return CallerOriginPropagationResult.Rejected(
                        alarmId = origin.alarmId,
                        originIdentity = origin,
                        reason = CancellationRejectionReason.TRIGGER_TIME_MISMATCH,
                        detail = "targetScheduledAt=${origin.targetScheduledAt} disagrees with scheduledTriggerMillis=$triggerMillis"
                    )
                }
                CallerOriginPropagationResult.QualifiedCandidate(
                    alarmId = origin.alarmId,
                    originIdentity = origin,
                    fence = CancellationFence.ExactOccurrence(
                        alarmId = origin.alarmId,
                        kind = Alarm.KIND_NORMAL,
                        expectedGeneration = generation,
                        expectedRevision = revision,
                        expectedTriggerTime = triggerMillis,
                        expectedScheduleRevision = scheduleRevision,
                        expectedDefinitionRevision = definitionRevision,
                        expectedInstanceToken = instanceToken,
                        expectedRestoreEpoch = restoreEpoch,
                        targetScheduledAt = origin.targetScheduledAt,
                        targetFireId = origin.targetFireId,
                        allowZeroedDisabledRoom = origin.allowZeroedDisabledRoom,
                        allowZeroTriggerPreArm = origin.allowZeroTriggerPreArm
                    )
                )
            }
            CallerOperationKind.DELETE,
            CallerOperationKind.DISABLE,
            CallerOperationKind.REPLACE_BEFORE_SAVE -> {
                val mutationOrder = origin.mutationOrder
                    ?: return CallerOriginPropagationResult.Unqualified(
                        alarmId = origin.alarmId,
                        originIdentity = origin,
                        unqualifiedReason = CallerOriginUnqualifiedReason.MISSING_MUTATION_ORDER,
                        plannerReason = null,
                        detail = "Definition operation ${origin.operationKind} requires explicit RoomMutationOrder",
                        missingFields = setOf("mutationOrder")
                    )
                val capturedAtMillis = origin.capturedAtMillis
                    ?: return CallerOriginPropagationResult.Unqualified(
                        alarmId = origin.alarmId,
                        originIdentity = origin,
                        unqualifiedReason = CallerOriginUnqualifiedReason.MISSING_CAPTURED_TIMESTAMP,
                        plannerReason = CancellationRejectionReason.MISSING_CAPTURED_TIMESTAMP,
                        detail = "Definition operation ${origin.operationKind} requires capturedAtMillis",
                        missingFields = setOf("capturedAtMillis")
                    )
                if (capturedAtMillis <= 0L) {
                    return CallerOriginPropagationResult.Rejected(
                        alarmId = origin.alarmId,
                        originIdentity = origin,
                        reason = CancellationRejectionReason.MISSING_CAPTURED_TIMESTAMP,
                        detail = "capturedAtMillis ($capturedAtMillis) must be > 0"
                    )
                }

                val actionKind = when (origin.operationKind) {
                    CallerOperationKind.DELETE -> DefinitionActionKind.DELETE
                    CallerOperationKind.DISABLE -> DefinitionActionKind.DISABLE
                    CallerOperationKind.REPLACE_BEFORE_SAVE -> DefinitionActionKind.REPLACE_BEFORE_SAVE
                    else -> error("Unreachable operationKind ${origin.operationKind}")
                }
                CallerOriginPropagationResult.QualifiedCandidate(
                    alarmId = origin.alarmId,
                    originIdentity = origin,
                    fence = CancellationFence.DefinitionDeleteOrDisable(
                        alarmId = origin.alarmId,
                        kind = Alarm.KIND_NORMAL,
                        actionKind = actionKind,
                        roomMutationOrder = mutationOrder,
                        expectedGeneration = generation,
                        expectedRevision = revision,
                        expectedTriggerTime = triggerMillis,
                        expectedInstanceToken = instanceToken,
                        expectedRestoreEpoch = restoreEpoch,
                        expectedDefinitionRevision = definitionRevision,
                        expectedScheduleRevision = scheduleRevision,
                        capturedAtMillis = capturedAtMillis
                    )
                )
            }
            CallerOperationKind.QUICK_OCCURRENCE,
            CallerOperationKind.LEGACY_ALARM_ID_ONLY -> error("Handled prior to convertNormalOrigin")
        }
    }
}
