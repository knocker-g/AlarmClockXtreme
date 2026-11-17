package com.sysadmindoc.alarmclock.domain

import com.sysadmindoc.alarmclock.data.local.entity.ScheduleAuthority
import com.sysadmindoc.alarmclock.data.model.Alarm

/**
 * Caller-supplied intention for [CancellationFence.DefinitionDeleteOrDisable].
 */
enum class DefinitionActionKind {
    DELETE,
    DISABLE,
    REPLACE_BEFORE_SAVE
}

/**
 * Explicit ordering between the caller's Room mutation and the fenced cancellation step.
 */
enum class RoomMutationOrder {
    BEFORE_CANCEL,
    AFTER_CANCEL
}

/**
 * Typed reason codes for fail-closed [CancelOutcome] rejections, no-ops, and uncertainties.
 */
enum class CancellationRejectionReason {
    MISSING_OR_INVALID_ALARM_ID,
    INVALID_KIND,
    MISSING_GENERATION_OR_REVISION,
    MISSING_TRIGGER_TIME,
    MISSING_AUTHORITY_LINEAGE,
    MISSING_ORPHAN_TOKEN_IDENTITY,
    MISSING_QUICK_IDENTITY,
    MISSING_CAPTURED_TIMESTAMP,
    STALE_GENERATION,
    STALE_REVISION,
    STALE_SCHEDULE_REVISION,
    STALE_DEFINITION_REVISION,
    GENERATION_MISMATCH,
    REVISION_MISMATCH,
    SCHEDULE_REVISION_MISMATCH,
    DEFINITION_REVISION_MISMATCH,
    TRIGGER_TIME_MISMATCH,
    TARGET_WITNESS_MISMATCH,
    INSTANCE_TOKEN_MISMATCH,
    RESTORE_EPOCH_MISMATCH,
    AUTHORITY_LIFECYCLE_OR_OPERATION_MISMATCH,
    ALARM_REENABLED,
    ORPHAN_ALARM_ENABLED,
    ORPHAN_SLOT_SUPERSEDED,
    ORPHAN_OCCURRENCE_ABSENT,
    ORPHAN_OCCURRENCE_TERMINAL,
    ALREADY_DELETED_OR_ABSENT,
    QUICK_IDENTITY_MISMATCH,
    KIND_MISMATCH,
    AUTHORITY_UNAVAILABLE,
    OS_STATE_UNCERTAIN,
    PERSISTENCE_UNCERTAIN
}

/**
 * Immutable occurrence fence carried by a cancellation caller.
 *
 * Design invariants (KNO-75 C7-R2-S1-A Option B):
 * - All properties are immutable `val`s capturing the caller's exact target occurrence identity.
 * - Never synthesizes newer Room/Authority state to legitimize a stale caller.
 * - Does not alter legacy V1 `PendingIntent` identity; `KNOWN_RED04_UNRESOLVED` remains out of scope.
 */
sealed interface CancellationFence {
    val alarmId: Long

    data class ExactOccurrence(
        override val alarmId: Long,
        val kind: String = Alarm.KIND_NORMAL,
        val expectedGeneration: Int,
        val expectedRevision: Int,
        val expectedTriggerTime: Long,
        val expectedScheduleRevision: Long? = null,
        val expectedDefinitionRevision: Long? = null,
        val expectedInstanceToken: String? = null,
        val expectedRestoreEpoch: Long? = null,
        val targetScheduledAt: Long? = null,
        val targetFireId: String? = null,
        val allowZeroedDisabledRoom: Boolean = false,
        val allowZeroTriggerPreArm: Boolean = false
    ) : CancellationFence

    data class DefinitionDeleteOrDisable(
        override val alarmId: Long,
        val kind: String = Alarm.KIND_NORMAL,
        val actionKind: DefinitionActionKind,
        val roomMutationOrder: RoomMutationOrder,
        val expectedGeneration: Int,
        val expectedRevision: Int,
        val expectedTriggerTime: Long,
        val expectedInstanceToken: String? = null,
        val expectedRestoreEpoch: Long? = null,
        val expectedDefinitionRevision: Long? = null,
        val expectedScheduleRevision: Long? = null,
        val capturedAtMillis: Long
    ) : CancellationFence

    data class OrphanToken(
        override val alarmId: Long,
        val issueToken: String,
        val expectedScheduledAt: Long,
        val expectedFireId: String,
        val expectedSlotGenerationId: Long,
        val expectedInstanceToken: String? = null,
        val expectedRestoreEpoch: Long? = null
    ) : CancellationFence

    data class QuickOccurrence(
        override val alarmId: Long,
        val kind: String = Alarm.KIND_QUICK,
        val quickTriggerEpochMs: Long,
        val quickRelativeElapsedMs: Long,
        val quickBootCount: Long
    ) : CancellationFence
}

/**
 * Immutable read-only snapshot of an [Alarm] row supplied to pure fence evaluation.
 */
data class FencedAlarmSnapshot(
    val id: Long,
    val kind: String,
    val isEnabled: Boolean,
    val generation: Int,
    val revision: Int,
    val nextTriggerTime: Long,
    val quickTriggerEpochMs: Long = 0L,
    val quickRelativeElapsedMs: Long = 0L,
    val quickBootCount: Long = -1L
) {
    companion object {
        fun fromAlarm(alarm: Alarm): FencedAlarmSnapshot = FencedAlarmSnapshot(
            id = alarm.id,
            kind = alarm.kind,
            isEnabled = alarm.isEnabled,
            generation = alarm.generation,
            revision = alarm.revision,
            nextTriggerTime = alarm.nextTriggerTime,
            quickTriggerEpochMs = alarm.quickTriggerEpochMs,
            quickRelativeElapsedMs = alarm.quickRelativeElapsedMs,
            quickBootCount = alarm.quickBootCount
        )
    }
}

/**
 * Immutable read-only snapshot of a [ScheduleAuthority] row supplied to pure fence evaluation.
 */
data class FencedAuthoritySnapshot(
    val alarmId: Long,
    val instanceToken: String,
    val definitionRevision: Long,
    val scheduleRevision: Long,
    val lifecycle: String,
    val desiredOperation: String,
    val desiredTriggerMillis: Long?,
    val restoreEpoch: Long,
    val reconcileState: String
) {
    companion object {
        fun fromAuthority(authority: ScheduleAuthority): FencedAuthoritySnapshot = FencedAuthoritySnapshot(
            alarmId = authority.alarmId,
            instanceToken = authority.instanceToken,
            definitionRevision = authority.definitionRevision,
            scheduleRevision = authority.scheduleRevision,
            lifecycle = authority.lifecycle,
            desiredOperation = authority.desiredOperation,
            desiredTriggerMillis = authority.desiredTriggerMillis,
            restoreEpoch = authority.restoreEpoch,
            reconcileState = authority.reconcileState
        )
    }
}

/**
 * Immutable read-only snapshot of an `IssuedOccurrence` row for [CancellationFence.OrphanToken].
 */
data class FencedIssuedOccurrenceSnapshot(
    val issueToken: String,
    val alarmId: Long,
    val scheduledAt: Long,
    val fireId: String,
    val generationId: Long,
    val status: String
)

/**
 * Immutable read-only snapshot of an `IssueSlot` row for [CancellationFence.OrphanToken].
 */
data class FencedIssueSlotSnapshot(
    val alarmId: Long,
    val currentIssueToken: String?,
    val generationId: Long
)

/**
 * Immutable read-only snapshot of a Direct-Boot V1 snapshot with optional additive lineage.
 */
data class FencedDirectBootSnapshot(
    val alarmId: Long,
    val triggerTime: Long,
    val updatedAt: Long,
    val generation: Int? = null,
    val revision: Int? = null,
    val instanceToken: String? = null,
    val restoreEpoch: Long? = null
)

enum class OsCancelProbeState {
    NOT_ATTEMPTED,
    CANCELLED,
    NO_EXISTING,
    FAILED_UNKNOWN
}

enum class PersistenceProbeState {
    HEALTHY,
    UNAVAILABLE,
    UNCERTAIN
}

/**
 * Immutable input bundle passed into the pure [FencedCancellationPlanner].
 * Zero Android Context, AlarmManager, Room DAO, WorkManager, or DirectBoot storage references.
 */
data class CancellationEvaluationContext(
    val currentAlarm: FencedAlarmSnapshot?,
    val currentAuthority: FencedAuthoritySnapshot? = null,
    val authorityRequired: Boolean = true,
    val currentSlot: FencedIssueSlotSnapshot? = null,
    val currentOccurrence: FencedIssuedOccurrenceSnapshot? = null,
    val currentDirectBootSnapshot: FencedDirectBootSnapshot? = null,
    val expectedFireIdForCurrentTrigger: String? = null,
    val persistenceState: PersistenceProbeState = PersistenceProbeState.HEALTHY,
    val osState: OsCancelProbeState = OsCancelProbeState.NOT_ATTEMPTED
)

/**
 * Pure cancellation execution plan emitted only when [CancelOutcome.Accepted] is produced.
 *
 * Emitting a plan does NOT execute OS cancellation or mutate persistence; callers must still
 * execute and verify OS cancellation under the target runtime cutover before destructive cleanup.
 */
data class FencedCancellationPlan(
    val alarmId: Long,
    val fenceKind: String,
    val shouldCancelMainOsSlot: Boolean,
    val shouldAttemptDirectBootRemoval: Boolean,
    val directBootTargetTriggerTime: Long?,
    val shouldCancelFollowUpWork: Boolean,
    val revokeIssuedOccurrenceToken: String?,
    val callerRoomMutationPermittedAfterOsCancel: Boolean,
    val requiresOsVerificationBeforeDestructiveCleanup: Boolean = true,
    val solvesLegacySharedPendingIntentRed04: Boolean = false
)

/**
 * Typed cancellation evaluation/planning outcome contract.
 *
 * Explicitly separates plan admission ([Accepted]) from OS cancellation completion:
 * [isOsCancelCompleted] is always `false` in pure planning primitives.
 */
sealed interface CancelOutcome {
    val alarmId: Long

    val isAccepted: Boolean
        get() = this is Accepted

    val isCancellationPlanAllowed: Boolean
        get() = this is Accepted

    val allowsDestructiveCleanup: Boolean
        get() = this is Accepted

    val isOsCancelCompleted: Boolean
        get() = false

    val isNoOpAbsent: Boolean
        get() = this is NoOpAbsent

    data class Accepted(
        override val alarmId: Long,
        val plan: FencedCancellationPlan
    ) : CancelOutcome

    data class NoOpAbsent(
        override val alarmId: Long,
        val reason: CancellationRejectionReason,
        val detail: String
    ) : CancelOutcome

    data class StaleRejected(
        override val alarmId: Long,
        val reason: CancellationRejectionReason,
        val detail: String
    ) : CancelOutcome

    data class IdentityMismatch(
        override val alarmId: Long,
        val reason: CancellationRejectionReason,
        val detail: String
    ) : CancelOutcome

    data class AuthorityUnavailable(
        override val alarmId: Long,
        val reason: CancellationRejectionReason = CancellationRejectionReason.AUTHORITY_UNAVAILABLE,
        val detail: String
    ) : CancelOutcome

    data class OsStateUncertain(
        override val alarmId: Long,
        val reason: CancellationRejectionReason = CancellationRejectionReason.OS_STATE_UNCERTAIN,
        val detail: String
    ) : CancelOutcome

    data class PersistenceUncertain(
        override val alarmId: Long,
        val reason: CancellationRejectionReason = CancellationRejectionReason.PERSISTENCE_UNCERTAIN,
        val detail: String
    ) : CancelOutcome

    data class InvalidFence(
        override val alarmId: Long,
        val reason: CancellationRejectionReason,
        val detail: String
    ) : CancelOutcome

    data class KindMismatch(
        override val alarmId: Long,
        val reason: CancellationRejectionReason = CancellationRejectionReason.KIND_MISMATCH,
        val detail: String
    ) : CancelOutcome
}
