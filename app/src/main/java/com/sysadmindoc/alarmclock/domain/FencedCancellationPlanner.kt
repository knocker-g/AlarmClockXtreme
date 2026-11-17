package com.sysadmindoc.alarmclock.domain

import com.sysadmindoc.alarmclock.data.local.entity.IssuedOccurrence
import com.sysadmindoc.alarmclock.data.model.Alarm

/**
 * Pure, deterministic, side-effect-free evaluator and planner for [CancellationFence].
 *
 * Design guarantees (KNO-75 C7-R2-S1-A Option B):
 * - Intentionally disconnected from production runtime paths (`AlarmScheduler`,
 *   `DirectBootAlarmCache`, `QuickAlarmScheduler`, Room DAOs, and `WorkManager`).
 * - Executes zero I/O, zero IPC, zero coroutine blocking, and zero persistence mutations.
 * - Fails closed on missing/invalid fence identity and never synthesizes newer Room/Authority
 *   state to legitimize a stale caller.
 * - Explicitly does NOT resolve `KNOWN_RED04_UNRESOLVED` on legacy V1 shared `PendingIntent`s.
 */
object FencedCancellationPlanner {

    private val ACTIVE_OCCURRENCE_STATUSES = setOf(
        IssuedOccurrence.STATUS_PREPARED,
        IssuedOccurrence.STATUS_ISSUED,
        IssuedOccurrence.STATUS_ADMITTED
    )

    fun evaluate(
        fence: CancellationFence,
        context: CancellationEvaluationContext,
        includeFollowUpWorkers: Boolean = true
    ): CancelOutcome {
        if (context.persistenceState != PersistenceProbeState.HEALTHY) {
            return CancelOutcome.PersistenceUncertain(
                alarmId = fence.alarmId,
                reason = CancellationRejectionReason.PERSISTENCE_UNCERTAIN,
                detail = "Persistence probe state is ${context.persistenceState}; refusing cancellation plan"
            )
        }
        if (context.osState == OsCancelProbeState.FAILED_UNKNOWN) {
            return CancelOutcome.OsStateUncertain(
                alarmId = fence.alarmId,
                reason = CancellationRejectionReason.OS_STATE_UNCERTAIN,
                detail = "OS cancel probe reported FAILED_UNKNOWN; preserving persistence and rejecting cleanup plan"
            )
        }

        return when (fence) {
            is CancellationFence.ExactOccurrence ->
                evaluateExactOccurrence(fence, context, includeFollowUpWorkers)
            is CancellationFence.DefinitionDeleteOrDisable ->
                evaluateDefinitionDeleteOrDisable(fence, context, includeFollowUpWorkers)
            is CancellationFence.OrphanToken ->
                evaluateOrphanToken(fence, context, includeFollowUpWorkers)
            is CancellationFence.QuickOccurrence ->
                evaluateQuickOccurrence(fence, context, includeFollowUpWorkers)
        }
    }

    fun shouldRemoveDirectBootSnapshot(
        fence: CancellationFence,
        snapshot: FencedDirectBootSnapshot?
    ): Boolean {
        if (snapshot == null || snapshot.alarmId != fence.alarmId) {
            return false
        }
        return when (fence) {
            is CancellationFence.QuickOccurrence -> false
            is CancellationFence.ExactOccurrence -> {
                if (fence.expectedTriggerTime <= 0L) {
                    false
                } else if (snapshot.triggerTime != fence.expectedTriggerTime) {
                    false
                } else if (snapshot.generation != null && snapshot.generation != fence.expectedGeneration) {
                    false
                } else if (snapshot.revision != null && snapshot.revision != fence.expectedRevision) {
                    false
                } else if (
                    snapshot.instanceToken != null &&
                    fence.expectedInstanceToken != null &&
                    snapshot.instanceToken != fence.expectedInstanceToken
                ) {
                    false
                } else if (
                    snapshot.restoreEpoch != null &&
                    fence.expectedRestoreEpoch != null &&
                    snapshot.restoreEpoch != fence.expectedRestoreEpoch
                ) {
                    false
                } else {
                    true
                }
            }
            is CancellationFence.DefinitionDeleteOrDisable -> {
                if (fence.expectedTriggerTime > 0L && snapshot.triggerTime != fence.expectedTriggerTime) {
                    false
                } else if (
                    snapshot.instanceToken != null &&
                    fence.expectedInstanceToken != null &&
                    snapshot.instanceToken != fence.expectedInstanceToken
                ) {
                    false
                } else if (
                    snapshot.restoreEpoch != null &&
                    fence.expectedRestoreEpoch != null &&
                    snapshot.restoreEpoch != fence.expectedRestoreEpoch
                ) {
                    false
                } else if (snapshot.generation != null && snapshot.generation > fence.expectedGeneration) {
                    false
                } else if (fence.expectedTriggerTime == 0L) {
                    // Fail closed on 0L trigger unless explicit matching additive lineage proves same occurrence.
                    snapshot.instanceToken != null &&
                        fence.expectedInstanceToken != null &&
                        snapshot.instanceToken == fence.expectedInstanceToken &&
                        snapshot.restoreEpoch != null &&
                        fence.expectedRestoreEpoch != null &&
                        snapshot.restoreEpoch == fence.expectedRestoreEpoch &&
                        snapshot.generation != null &&
                        snapshot.generation <= fence.expectedGeneration
                } else {
                    true
                }
            }
            is CancellationFence.OrphanToken -> {
                if (snapshot.triggerTime != fence.expectedScheduledAt) {
                    false
                } else if (
                    snapshot.instanceToken != null &&
                    fence.expectedInstanceToken != null &&
                    snapshot.instanceToken != fence.expectedInstanceToken
                ) {
                    false
                } else if (
                    snapshot.restoreEpoch != null &&
                    fence.expectedRestoreEpoch != null &&
                    snapshot.restoreEpoch != fence.expectedRestoreEpoch
                ) {
                    false
                } else {
                    true
                }
            }
        }
    }

    private fun evaluateExactOccurrence(
        fence: CancellationFence.ExactOccurrence,
        context: CancellationEvaluationContext,
        includeFollowUpWorkers: Boolean
    ): CancelOutcome {
        if (fence.alarmId <= 0L) {
            return CancelOutcome.InvalidFence(
                alarmId = fence.alarmId,
                reason = CancellationRejectionReason.MISSING_OR_INVALID_ALARM_ID,
                detail = "alarmId must be > 0"
            )
        }
        if (fence.kind.equals(Alarm.KIND_QUICK, ignoreCase = true)) {
            return CancelOutcome.KindMismatch(
                alarmId = fence.alarmId,
                detail = "ExactOccurrence fence cannot target KIND_QUICK"
            )
        }
        if (!fence.kind.equals(Alarm.KIND_NORMAL, ignoreCase = true)) {
            return CancelOutcome.InvalidFence(
                alarmId = fence.alarmId,
                reason = CancellationRejectionReason.INVALID_KIND,
                detail = "Unsupported alarm kind '${fence.kind}' for ExactOccurrence"
            )
        }
        if (fence.expectedGeneration < 1 || fence.expectedRevision < 1) {
            return CancelOutcome.InvalidFence(
                alarmId = fence.alarmId,
                reason = CancellationRejectionReason.MISSING_GENERATION_OR_REVISION,
                detail = "expectedGeneration and expectedRevision must both be >= 1"
            )
        }
        if (fence.expectedTriggerTime < 0L || (fence.expectedTriggerTime == 0L && !fence.allowZeroTriggerPreArm)) {
            return CancelOutcome.InvalidFence(
                alarmId = fence.alarmId,
                reason = CancellationRejectionReason.MISSING_TRIGGER_TIME,
                detail = "expectedTriggerTime must be > 0 unless allowZeroTriggerPreArm=true"
            )
        }
        if ((fence.targetScheduledAt != null && fence.targetScheduledAt <= 0L) ||
            (fence.targetFireId != null && fence.targetFireId.isBlank())
        ) {
            return CancelOutcome.InvalidFence(
                alarmId = fence.alarmId,
                reason = CancellationRejectionReason.TARGET_WITNESS_MISMATCH,
                detail = "Optional target witness fields must be valid when provided"
            )
        }
        if (fence.targetScheduledAt != null &&
            fence.expectedTriggerTime > 0L &&
            fence.targetScheduledAt != fence.expectedTriggerTime
        ) {
            return CancelOutcome.IdentityMismatch(
                alarmId = fence.alarmId,
                reason = CancellationRejectionReason.TRIGGER_TIME_MISMATCH,
                detail = "targetScheduledAt=${fence.targetScheduledAt} disagrees with expectedTriggerTime=${fence.expectedTriggerTime}"
            )
        }

        val currentAlarm = context.currentAlarm
            ?: return CancelOutcome.AuthorityUnavailable(
                alarmId = fence.alarmId,
                detail = "Current alarm snapshot unavailable for alarmId=${fence.alarmId}"
            )

        if (currentAlarm.id != fence.alarmId) {
            return CancelOutcome.IdentityMismatch(
                alarmId = fence.alarmId,
                reason = CancellationRejectionReason.MISSING_OR_INVALID_ALARM_ID,
                detail = "Snapshot alarmId=${currentAlarm.id} does not match fence alarmId=${fence.alarmId}"
            )
        }
        if (!currentAlarm.kind.equals(Alarm.KIND_NORMAL, ignoreCase = true)) {
            return CancelOutcome.KindMismatch(
                alarmId = fence.alarmId,
                detail = "ExactOccurrence cannot cancel alarm of kind '${currentAlarm.kind}'"
            )
        }

        verifyNormalAuthority(
            alarmId = fence.alarmId,
            currentAuthority = context.currentAuthority,
            authorityRequired = context.authorityRequired,
            expectedInstanceToken = fence.expectedInstanceToken,
            expectedRestoreEpoch = fence.expectedRestoreEpoch,
            expectedScheduleRevision = fence.expectedScheduleRevision,
            expectedDefinitionRevision = fence.expectedDefinitionRevision,
            requireDefinitionRevision = false
        )?.let { return it }

        if (currentAlarm.generation > fence.expectedGeneration) {
            return CancelOutcome.StaleRejected(
                alarmId = fence.alarmId,
                reason = CancellationRejectionReason.STALE_GENERATION,
                detail = "Current generation ${currentAlarm.generation} > expected ${fence.expectedGeneration}"
            )
        }
        if (currentAlarm.generation < fence.expectedGeneration) {
            return CancelOutcome.IdentityMismatch(
                alarmId = fence.alarmId,
                reason = CancellationRejectionReason.GENERATION_MISMATCH,
                detail = "Current generation ${currentAlarm.generation} < expected ${fence.expectedGeneration}"
            )
        }
        if (currentAlarm.revision > fence.expectedRevision) {
            return CancelOutcome.StaleRejected(
                alarmId = fence.alarmId,
                reason = CancellationRejectionReason.STALE_REVISION,
                detail = "Current revision ${currentAlarm.revision} > expected ${fence.expectedRevision}"
            )
        }
        if (currentAlarm.revision < fence.expectedRevision) {
            return CancelOutcome.IdentityMismatch(
                alarmId = fence.alarmId,
                reason = CancellationRejectionReason.REVISION_MISMATCH,
                detail = "Current revision ${currentAlarm.revision} < expected ${fence.expectedRevision}"
            )
        }

        if (fence.expectedTriggerTime > 0L) {
            if (currentAlarm.nextTriggerTime > 0L && currentAlarm.nextTriggerTime != fence.expectedTriggerTime) {
                return CancelOutcome.IdentityMismatch(
                    alarmId = fence.alarmId,
                    reason = CancellationRejectionReason.TRIGGER_TIME_MISMATCH,
                    detail = "Current trigger ${currentAlarm.nextTriggerTime} != expected ${fence.expectedTriggerTime}"
                )
            }
            if (currentAlarm.nextTriggerTime == 0L) {
                if (!fence.allowZeroedDisabledRoom) {
                    return CancelOutcome.IdentityMismatch(
                        alarmId = fence.alarmId,
                        reason = CancellationRejectionReason.TRIGGER_TIME_MISMATCH,
                        detail = "Current triggerTime is 0 and allowZeroedDisabledRoom=false"
                    )
                }
                if (currentAlarm.isEnabled) {
                    return CancelOutcome.StaleRejected(
                        alarmId = fence.alarmId,
                        reason = CancellationRejectionReason.ALARM_REENABLED,
                        detail = "Alarm is enabled with zeroed trigger while expecting disabled one-shot"
                    )
                }
            }
        } else {
            if (currentAlarm.nextTriggerTime > 0L) {
                return CancelOutcome.IdentityMismatch(
                    alarmId = fence.alarmId,
                    reason = CancellationRejectionReason.TRIGGER_TIME_MISMATCH,
                    detail = "Pre-arm zero-trigger fence cannot cancel active trigger ${currentAlarm.nextTriggerTime}"
                )
            }
        }

        if (fence.targetScheduledAt != null &&
            currentAlarm.nextTriggerTime > 0L &&
            currentAlarm.nextTriggerTime != fence.targetScheduledAt
        ) {
            return CancelOutcome.IdentityMismatch(
                alarmId = fence.alarmId,
                reason = CancellationRejectionReason.TARGET_WITNESS_MISMATCH,
                detail = "Current trigger ${currentAlarm.nextTriggerTime} != targetScheduledAt ${fence.targetScheduledAt}"
            )
        }
        if (fence.targetFireId != null &&
            context.expectedFireIdForCurrentTrigger != null &&
            fence.targetFireId != context.expectedFireIdForCurrentTrigger
        ) {
            return CancelOutcome.IdentityMismatch(
                alarmId = fence.alarmId,
                reason = CancellationRejectionReason.TARGET_WITNESS_MISMATCH,
                detail = "targetFireId does not match current occurrence witness"
            )
        }

        val removeDirectBoot = shouldRemoveDirectBootSnapshot(fence, context.currentDirectBootSnapshot)
        return CancelOutcome.Accepted(
            alarmId = fence.alarmId,
            plan = FencedCancellationPlan(
                alarmId = fence.alarmId,
                fenceKind = "EXACT_OCCURRENCE",
                shouldCancelMainOsSlot = context.osState != OsCancelProbeState.NO_EXISTING,
                shouldAttemptDirectBootRemoval = removeDirectBoot,
                directBootTargetTriggerTime = if (removeDirectBoot) context.currentDirectBootSnapshot?.triggerTime else null,
                shouldCancelFollowUpWork = includeFollowUpWorkers,
                revokeIssuedOccurrenceToken = null,
                callerRoomMutationPermittedAfterOsCancel = true,
                requiresOsVerificationBeforeDestructiveCleanup = true,
                solvesLegacySharedPendingIntentRed04 = false
            )
        )
    }

    private fun evaluateDefinitionDeleteOrDisable(
        fence: CancellationFence.DefinitionDeleteOrDisable,
        context: CancellationEvaluationContext,
        includeFollowUpWorkers: Boolean
    ): CancelOutcome {
        if (fence.alarmId <= 0L) {
            return CancelOutcome.InvalidFence(
                alarmId = fence.alarmId,
                reason = CancellationRejectionReason.MISSING_OR_INVALID_ALARM_ID,
                detail = "alarmId must be > 0"
            )
        }
        if (fence.kind.equals(Alarm.KIND_QUICK, ignoreCase = true)) {
            return CancelOutcome.KindMismatch(
                alarmId = fence.alarmId,
                detail = "DefinitionDeleteOrDisable fence cannot target KIND_QUICK"
            )
        }
        if (!fence.kind.equals(Alarm.KIND_NORMAL, ignoreCase = true)) {
            return CancelOutcome.InvalidFence(
                alarmId = fence.alarmId,
                reason = CancellationRejectionReason.INVALID_KIND,
                detail = "Unsupported alarm kind '${fence.kind}' for DefinitionDeleteOrDisable"
            )
        }
        if (fence.expectedGeneration < 1 || fence.expectedRevision < 1) {
            return CancelOutcome.InvalidFence(
                alarmId = fence.alarmId,
                reason = CancellationRejectionReason.MISSING_GENERATION_OR_REVISION,
                detail = "expectedGeneration and expectedRevision must both be >= 1"
            )
        }
        if (fence.expectedTriggerTime < 0L) {
            return CancelOutcome.InvalidFence(
                alarmId = fence.alarmId,
                reason = CancellationRejectionReason.MISSING_TRIGGER_TIME,
                detail = "expectedTriggerTime must be >= 0"
            )
        }
        if (fence.capturedAtMillis <= 0L) {
            return CancelOutcome.InvalidFence(
                alarmId = fence.alarmId,
                reason = CancellationRejectionReason.MISSING_CAPTURED_TIMESTAMP,
                detail = "capturedAtMillis must be > 0"
            )
        }

        val currentAlarm = context.currentAlarm
        if (currentAlarm != null && !currentAlarm.kind.equals(Alarm.KIND_NORMAL, ignoreCase = true)) {
            return CancelOutcome.KindMismatch(
                alarmId = fence.alarmId,
                detail = "DefinitionDeleteOrDisable cannot target alarm of kind '${currentAlarm.kind}'"
            )
        }
        if (currentAlarm == null) {
            return if (fence.roomMutationOrder == RoomMutationOrder.AFTER_CANCEL) {
                CancelOutcome.NoOpAbsent(
                    alarmId = fence.alarmId,
                    reason = CancellationRejectionReason.ALREADY_DELETED_OR_ABSENT,
                    detail = "Alarm row already absent before AFTER_CANCEL execution"
                )
            } else {
                CancelOutcome.AuthorityUnavailable(
                    alarmId = fence.alarmId,
                    detail = "Alarm row unavailable for BEFORE_CANCEL verification"
                )
            }
        }
        if (currentAlarm.id != fence.alarmId) {
            return CancelOutcome.IdentityMismatch(
                alarmId = fence.alarmId,
                reason = CancellationRejectionReason.MISSING_OR_INVALID_ALARM_ID,
                detail = "Snapshot alarmId=${currentAlarm.id} does not match fence alarmId=${fence.alarmId}"
            )
        }

        verifyNormalAuthority(
            alarmId = fence.alarmId,
            currentAuthority = context.currentAuthority,
            authorityRequired = context.authorityRequired,
            expectedInstanceToken = fence.expectedInstanceToken,
            expectedRestoreEpoch = fence.expectedRestoreEpoch,
            expectedScheduleRevision = fence.expectedScheduleRevision,
            expectedDefinitionRevision = fence.expectedDefinitionRevision,
            requireDefinitionRevision = true
        )?.let { return it }

        if (fence.roomMutationOrder == RoomMutationOrder.BEFORE_CANCEL) {
            if (fence.actionKind == DefinitionActionKind.DISABLE && currentAlarm.isEnabled) {
                return CancelOutcome.StaleRejected(
                    alarmId = fence.alarmId,
                    reason = CancellationRejectionReason.ALARM_REENABLED,
                    detail = "Concurrent re-enable detected before BEFORE_CANCEL cancel"
                )
            }
            val currentAuthority = context.currentAuthority
            if (currentAuthority != null &&
                (currentAuthority.lifecycle != "DISABLED" || currentAuthority.desiredOperation != "CANCEL")
            ) {
                return CancelOutcome.StaleRejected(
                    alarmId = fence.alarmId,
                    reason = CancellationRejectionReason.AUTHORITY_LIFECYCLE_OR_OPERATION_MISMATCH,
                    detail = "Expected authority lifecycle=DISABLED, desiredOperation=CANCEL but found ${currentAuthority.lifecycle}/${currentAuthority.desiredOperation}"
                )
            }
        }

        if (currentAlarm.generation > fence.expectedGeneration) {
            return CancelOutcome.StaleRejected(
                alarmId = fence.alarmId,
                reason = CancellationRejectionReason.STALE_GENERATION,
                detail = "Current generation ${currentAlarm.generation} > expected ${fence.expectedGeneration}"
            )
        }
        if (currentAlarm.generation < fence.expectedGeneration) {
            return CancelOutcome.IdentityMismatch(
                alarmId = fence.alarmId,
                reason = CancellationRejectionReason.GENERATION_MISMATCH,
                detail = "Current generation ${currentAlarm.generation} < expected ${fence.expectedGeneration}"
            )
        }

        if (fence.roomMutationOrder == RoomMutationOrder.AFTER_CANCEL) {
            if (currentAlarm.revision > fence.expectedRevision) {
                return CancelOutcome.StaleRejected(
                    alarmId = fence.alarmId,
                    reason = CancellationRejectionReason.STALE_REVISION,
                    detail = "Current revision ${currentAlarm.revision} > expected ${fence.expectedRevision}"
                )
            }
            if (currentAlarm.revision < fence.expectedRevision) {
                return CancelOutcome.IdentityMismatch(
                    alarmId = fence.alarmId,
                    reason = CancellationRejectionReason.REVISION_MISMATCH,
                    detail = "Current revision ${currentAlarm.revision} < expected ${fence.expectedRevision}"
                )
            }
            if (fence.expectedTriggerTime > 0L &&
                currentAlarm.nextTriggerTime > 0L &&
                currentAlarm.nextTriggerTime != fence.expectedTriggerTime
            ) {
                return CancelOutcome.IdentityMismatch(
                    alarmId = fence.alarmId,
                    reason = CancellationRejectionReason.TRIGGER_TIME_MISMATCH,
                    detail = "Current trigger ${currentAlarm.nextTriggerTime} != expected ${fence.expectedTriggerTime}"
                )
            }
        }

        val removeDirectBoot = shouldRemoveDirectBootSnapshot(fence, context.currentDirectBootSnapshot)
        return CancelOutcome.Accepted(
            alarmId = fence.alarmId,
            plan = FencedCancellationPlan(
                alarmId = fence.alarmId,
                fenceKind = "DEFINITION_${fence.actionKind.name}_${fence.roomMutationOrder.name}",
                shouldCancelMainOsSlot = context.osState != OsCancelProbeState.NO_EXISTING,
                shouldAttemptDirectBootRemoval = removeDirectBoot,
                directBootTargetTriggerTime = if (removeDirectBoot) context.currentDirectBootSnapshot?.triggerTime else null,
                shouldCancelFollowUpWork = includeFollowUpWorkers,
                revokeIssuedOccurrenceToken = null,
                callerRoomMutationPermittedAfterOsCancel = fence.roomMutationOrder == RoomMutationOrder.AFTER_CANCEL,
                requiresOsVerificationBeforeDestructiveCleanup = true,
                solvesLegacySharedPendingIntentRed04 = false
            )
        )
    }

    private fun evaluateOrphanToken(
        fence: CancellationFence.OrphanToken,
        context: CancellationEvaluationContext,
        includeFollowUpWorkers: Boolean
    ): CancelOutcome {
        if (fence.alarmId <= 0L) {
            return CancelOutcome.InvalidFence(
                alarmId = fence.alarmId,
                reason = CancellationRejectionReason.MISSING_OR_INVALID_ALARM_ID,
                detail = "alarmId must be > 0"
            )
        }
        if (fence.issueToken.isBlank() ||
            fence.expectedScheduledAt <= 0L ||
            fence.expectedFireId.isBlank() ||
            fence.expectedSlotGenerationId < 1L
        ) {
            return CancelOutcome.InvalidFence(
                alarmId = fence.alarmId,
                reason = CancellationRejectionReason.MISSING_ORPHAN_TOKEN_IDENTITY,
                detail = "OrphanToken requires non-blank issueToken/fireId, expectedScheduledAt > 0, expectedSlotGenerationId >= 1"
            )
        }

        val currentAlarm = context.currentAlarm
        if (currentAlarm != null) {
            if (!currentAlarm.kind.equals(Alarm.KIND_NORMAL, ignoreCase = true)) {
                return CancelOutcome.KindMismatch(
                    alarmId = fence.alarmId,
                    detail = "OrphanToken cannot target alarm of kind '${currentAlarm.kind}'"
                )
            }
            if (currentAlarm.isEnabled) {
                return CancelOutcome.StaleRejected(
                    alarmId = fence.alarmId,
                    reason = CancellationRejectionReason.ORPHAN_ALARM_ENABLED,
                    detail = "Alarm ${fence.alarmId} is enabled; refusing orphan token cancellation"
                )
            }
        }

        val currentAuthority = context.currentAuthority
        if (currentAuthority != null) {
            if (currentAuthority.lifecycle == "LIVE" && currentAuthority.desiredOperation == "SET") {
                return CancelOutcome.StaleRejected(
                    alarmId = fence.alarmId,
                    reason = CancellationRejectionReason.AUTHORITY_LIFECYCLE_OR_OPERATION_MISMATCH,
                    detail = "ScheduleAuthority is LIVE/SET; refusing orphan token cancellation"
                )
            }
            if (fence.expectedInstanceToken != null && currentAuthority.instanceToken != fence.expectedInstanceToken) {
                return CancelOutcome.IdentityMismatch(
                    alarmId = fence.alarmId,
                    reason = CancellationRejectionReason.INSTANCE_TOKEN_MISMATCH,
                    detail = "Authority instanceToken '${currentAuthority.instanceToken}' != expected '${fence.expectedInstanceToken}'"
                )
            }
            if (fence.expectedRestoreEpoch != null && currentAuthority.restoreEpoch != fence.expectedRestoreEpoch) {
                return CancelOutcome.IdentityMismatch(
                    alarmId = fence.alarmId,
                    reason = CancellationRejectionReason.RESTORE_EPOCH_MISMATCH,
                    detail = "Authority restoreEpoch ${currentAuthority.restoreEpoch} != expected ${fence.expectedRestoreEpoch}"
                )
            }
        }

        val currentSlot = context.currentSlot
        if (currentSlot != null &&
            currentSlot.generationId > fence.expectedSlotGenerationId &&
            currentSlot.currentIssueToken != fence.issueToken
        ) {
            return CancelOutcome.StaleRejected(
                alarmId = fence.alarmId,
                reason = CancellationRejectionReason.ORPHAN_SLOT_SUPERSEDED,
                detail = "IssueSlot generation ${currentSlot.generationId} superseded token '${fence.issueToken}'"
            )
        }

        val currentOccurrence = context.currentOccurrence
            ?: return CancelOutcome.NoOpAbsent(
                alarmId = fence.alarmId,
                reason = CancellationRejectionReason.ORPHAN_OCCURRENCE_ABSENT,
                detail = "IssuedOccurrence '${fence.issueToken}' absent"
            )

        if (currentOccurrence.issueToken != fence.issueToken ||
            currentOccurrence.alarmId != fence.alarmId ||
            currentOccurrence.scheduledAt != fence.expectedScheduledAt ||
            currentOccurrence.fireId != fence.expectedFireId
        ) {
            return CancelOutcome.IdentityMismatch(
                alarmId = fence.alarmId,
                reason = CancellationRejectionReason.ORPHAN_SLOT_SUPERSEDED,
                detail = "Current occurrence snapshot does not match OrphanToken identity"
            )
        }

        if (currentOccurrence.status !in ACTIVE_OCCURRENCE_STATUSES) {
            return CancelOutcome.NoOpAbsent(
                alarmId = fence.alarmId,
                reason = CancellationRejectionReason.ORPHAN_OCCURRENCE_TERMINAL,
                detail = "IssuedOccurrence '${fence.issueToken}' already in status '${currentOccurrence.status}'"
            )
        }

        val removeDirectBoot = shouldRemoveDirectBootSnapshot(fence, context.currentDirectBootSnapshot)
        return CancelOutcome.Accepted(
            alarmId = fence.alarmId,
            plan = FencedCancellationPlan(
                alarmId = fence.alarmId,
                fenceKind = "ORPHAN_TOKEN",
                shouldCancelMainOsSlot = context.osState != OsCancelProbeState.NO_EXISTING,
                shouldAttemptDirectBootRemoval = removeDirectBoot,
                directBootTargetTriggerTime = if (removeDirectBoot) context.currentDirectBootSnapshot?.triggerTime else null,
                shouldCancelFollowUpWork = includeFollowUpWorkers,
                revokeIssuedOccurrenceToken = fence.issueToken,
                callerRoomMutationPermittedAfterOsCancel = true,
                requiresOsVerificationBeforeDestructiveCleanup = true,
                solvesLegacySharedPendingIntentRed04 = false
            )
        )
    }

    private fun evaluateQuickOccurrence(
        fence: CancellationFence.QuickOccurrence,
        context: CancellationEvaluationContext,
        includeFollowUpWorkers: Boolean
    ): CancelOutcome {
        if (fence.alarmId <= 0L) {
            return CancelOutcome.InvalidFence(
                alarmId = fence.alarmId,
                reason = CancellationRejectionReason.MISSING_OR_INVALID_ALARM_ID,
                detail = "alarmId must be > 0"
            )
        }
        if (fence.kind.equals(Alarm.KIND_NORMAL, ignoreCase = true)) {
            return CancelOutcome.KindMismatch(
                alarmId = fence.alarmId,
                detail = "QuickOccurrence fence cannot target KIND_NORMAL"
            )
        }
        if (!fence.kind.equals(Alarm.KIND_QUICK, ignoreCase = true)) {
            return CancelOutcome.InvalidFence(
                alarmId = fence.alarmId,
                reason = CancellationRejectionReason.INVALID_KIND,
                detail = "Unsupported alarm kind '${fence.kind}' for QuickOccurrence"
            )
        }
        if (fence.quickTriggerEpochMs <= 0L ||
            fence.quickRelativeElapsedMs < 0L ||
            (fence.quickBootCount != -1L && fence.quickBootCount <= 0L) ||
            (fence.quickBootCount > 0L && fence.quickRelativeElapsedMs <= 0L)
        ) {
            return CancelOutcome.InvalidFence(
                alarmId = fence.alarmId,
                reason = CancellationRejectionReason.MISSING_QUICK_IDENTITY,
                detail = "QuickOccurrence requires valid quickTriggerEpochMs, quickRelativeElapsedMs, and quickBootCount"
            )
        }

        val currentAlarm = context.currentAlarm
            ?: return CancelOutcome.NoOpAbsent(
                alarmId = fence.alarmId,
                reason = CancellationRejectionReason.ALREADY_DELETED_OR_ABSENT,
                detail = "Quick alarm row ${fence.alarmId} already absent"
            )

        if (currentAlarm.id != fence.alarmId) {
            return CancelOutcome.IdentityMismatch(
                alarmId = fence.alarmId,
                reason = CancellationRejectionReason.MISSING_OR_INVALID_ALARM_ID,
                detail = "Snapshot alarmId=${currentAlarm.id} does not match QuickOccurrence alarmId=${fence.alarmId}"
            )
        }
        if (!currentAlarm.kind.equals(Alarm.KIND_QUICK, ignoreCase = true)) {
            return CancelOutcome.KindMismatch(
                alarmId = fence.alarmId,
                detail = "QuickOccurrence cannot cancel alarm of kind '${currentAlarm.kind}'"
            )
        }
        if (currentAlarm.quickTriggerEpochMs != fence.quickTriggerEpochMs ||
            currentAlarm.quickRelativeElapsedMs != fence.quickRelativeElapsedMs ||
            currentAlarm.quickBootCount != fence.quickBootCount
        ) {
            return CancelOutcome.IdentityMismatch(
                alarmId = fence.alarmId,
                reason = CancellationRejectionReason.QUICK_IDENTITY_MISMATCH,
                detail = "Quick alarm identity tuple mismatch"
            )
        }

        return CancelOutcome.Accepted(
            alarmId = fence.alarmId,
            plan = FencedCancellationPlan(
                alarmId = fence.alarmId,
                fenceKind = "QUICK_OCCURRENCE",
                shouldCancelMainOsSlot = context.osState != OsCancelProbeState.NO_EXISTING,
                shouldAttemptDirectBootRemoval = false,
                directBootTargetTriggerTime = null,
                shouldCancelFollowUpWork = includeFollowUpWorkers,
                revokeIssuedOccurrenceToken = null,
                callerRoomMutationPermittedAfterOsCancel = true,
                requiresOsVerificationBeforeDestructiveCleanup = true,
                solvesLegacySharedPendingIntentRed04 = false
            )
        )
    }

    private fun verifyNormalAuthority(
        alarmId: Long,
        currentAuthority: FencedAuthoritySnapshot?,
        authorityRequired: Boolean,
        expectedInstanceToken: String?,
        expectedRestoreEpoch: Long?,
        expectedScheduleRevision: Long?,
        expectedDefinitionRevision: Long?,
        requireDefinitionRevision: Boolean
    ): CancelOutcome? {
        if (currentAuthority == null) {
            return if (authorityRequired) {
                CancelOutcome.AuthorityUnavailable(
                    alarmId = alarmId,
                    detail = "Current ScheduleAuthority snapshot unavailable for alarmId=$alarmId"
                )
            } else {
                null
            }
        }

        if (expectedInstanceToken.isNullOrBlank() ||
            expectedRestoreEpoch == null ||
            expectedRestoreEpoch <= 0L ||
            expectedScheduleRevision == null ||
            expectedScheduleRevision < 0L ||
            (requireDefinitionRevision && (expectedDefinitionRevision == null || expectedDefinitionRevision < 0L))
        ) {
            return CancelOutcome.InvalidFence(
                alarmId = alarmId,
                reason = CancellationRejectionReason.MISSING_AUTHORITY_LINEAGE,
                detail = "Fence missing required authority lineage while ScheduleAuthority exists; refusing authority synthesis"
            )
        }

        if (currentAuthority.alarmId != alarmId) {
            return CancelOutcome.IdentityMismatch(
                alarmId = alarmId,
                reason = CancellationRejectionReason.AUTHORITY_UNAVAILABLE,
                detail = "Authority alarmId=${currentAuthority.alarmId} does not match $alarmId"
            )
        }
        if (currentAuthority.instanceToken != expectedInstanceToken) {
            return CancelOutcome.IdentityMismatch(
                alarmId = alarmId,
                reason = CancellationRejectionReason.INSTANCE_TOKEN_MISMATCH,
                detail = "Current instanceToken '${currentAuthority.instanceToken}' != expected '$expectedInstanceToken'"
            )
        }
        if (currentAuthority.restoreEpoch != expectedRestoreEpoch) {
            return CancelOutcome.IdentityMismatch(
                alarmId = alarmId,
                reason = CancellationRejectionReason.RESTORE_EPOCH_MISMATCH,
                detail = "Current restoreEpoch ${currentAuthority.restoreEpoch} != expected $expectedRestoreEpoch"
            )
        }
        if (expectedDefinitionRevision != null) {
            if (currentAuthority.definitionRevision > expectedDefinitionRevision) {
                return CancelOutcome.StaleRejected(
                    alarmId = alarmId,
                    reason = CancellationRejectionReason.STALE_DEFINITION_REVISION,
                    detail = "Current definitionRevision ${currentAuthority.definitionRevision} > expected $expectedDefinitionRevision"
                )
            }
            if (currentAuthority.definitionRevision < expectedDefinitionRevision) {
                return CancelOutcome.IdentityMismatch(
                    alarmId = alarmId,
                    reason = CancellationRejectionReason.DEFINITION_REVISION_MISMATCH,
                    detail = "Current definitionRevision ${currentAuthority.definitionRevision} < expected $expectedDefinitionRevision"
                )
            }
        }
        if (currentAuthority.scheduleRevision > expectedScheduleRevision) {
            return CancelOutcome.StaleRejected(
                alarmId = alarmId,
                reason = CancellationRejectionReason.STALE_SCHEDULE_REVISION,
                detail = "Current scheduleRevision ${currentAuthority.scheduleRevision} > expected $expectedScheduleRevision"
            )
        }
        if (currentAuthority.scheduleRevision < expectedScheduleRevision) {
            return CancelOutcome.IdentityMismatch(
                alarmId = alarmId,
                reason = CancellationRejectionReason.SCHEDULE_REVISION_MISMATCH,
                detail = "Current scheduleRevision ${currentAuthority.scheduleRevision} < expected $expectedScheduleRevision"
            )
        }
        return null
    }
}
