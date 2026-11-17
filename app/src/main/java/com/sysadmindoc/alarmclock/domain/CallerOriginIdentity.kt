package com.sysadmindoc.alarmclock.domain

import com.sysadmindoc.alarmclock.data.local.entity.ScheduleAuthority
import com.sysadmindoc.alarmclock.data.model.Alarm

/**
 * Typed caller operation classification at cancellation origin.
 */
enum class CallerOperationKind {
    EXACT_OCCURRENCE,
    DELETE,
    DISABLE,
    REPLACE_BEFORE_SAVE,
    QUICK_OCCURRENCE,
    LEGACY_ALARM_ID_ONLY
}

/**
 * Typed reasons why a [CallerOriginIdentity] cannot be promoted to a qualified
 * or planner-admissible cancellation candidate.
 */
enum class CallerOriginUnqualifiedReason {
    LEGACY_ALARM_ID_ONLY_CALLER,
    MISSING_ALARM_KIND,
    MISSING_GENERATION_OR_REVISION,
    MISSING_TRIGGER_TIME,
    MISSING_AUTHORITY_LINEAGE,
    MISSING_MUTATION_ORDER,
    MISSING_CAPTURED_TIMESTAMP,
    MISSING_QUICK_IDENTITY,
    AUTHORITY_UNAVAILABLE,
    OS_STATE_UNCERTAIN,
    PERSISTENCE_UNCERTAIN,
    ALREADY_ABSENT_NOOP
}

/**
 * Immutable capture of the occurrence identity held by a cancellation caller at origin time.
 *
 * Design invariants (KNO-75 C7-R2-S1-B1):
 * - Pure immutable `val` data carrier with zero Android `Context`, `AlarmManager`, Room DAO,
 *   `DirectBootAlarmCache`, or `WorkManager` references.
 * - Captures ONLY what the caller actually possessed prior to or at cancellation origin.
 * - Missing identity fields remain `null` and MUST NEVER be synthesized from newer Room/Authority state.
 */
data class CallerOriginIdentity(
    val alarmId: Long,
    val alarmKind: String? = null,
    val generation: Int? = null,
    val revision: Int? = null,
    val scheduledTriggerMillis: Long? = null,
    val scheduleAuthorityRevision: Long? = null,
    val definitionRevision: Long? = null,
    val instanceToken: String? = null,
    val restoreEpoch: Long? = null,
    val operationKind: CallerOperationKind,
    val mutationOrder: RoomMutationOrder? = null,
    val capturedAtMillis: Long? = null,
    val targetScheduledAt: Long? = null,
    val targetFireId: String? = null,
    val allowZeroedDisabledRoom: Boolean = false,
    val allowZeroTriggerPreArm: Boolean = false,
    val quickTriggerEpochMs: Long? = null,
    val quickRelativeElapsedMs: Long? = null,
    val quickBootCount: Long? = null,
    val sourceTag: String = "UNSPECIFIED"
) {
    companion object {
        /**
         * Captures exact-occurrence identity held by a Normal alarm caller before any mutation.
         * Leaves authority fields `null` if [authority] is unavailable or mismatched (`authority.alarmId != alarm.id`).
         *
         * Note: Validating `authority.alarmId == alarm.id` proves only that [authority] belongs to the same
         * alarm ID; it does NOT prove that authority and alarm snapshots were captured at the exact same point in time.
         */
        fun captureNormalExact(
            alarm: Alarm,
            authority: ScheduleAuthority?,
            capturedAtMillis: Long? = null,
            targetFireId: String? = null,
            allowZeroedDisabledRoom: Boolean = false,
            allowZeroTriggerPreArm: Boolean = false,
            sourceTag: String = "NORMAL_EXACT_ORIGIN"
        ): CallerOriginIdentity {
            val validAuthority = authority?.takeIf { it.alarmId == alarm.id }
            return CallerOriginIdentity(
                alarmId = alarm.id,
                alarmKind = alarm.kind,
                generation = alarm.generation,
                revision = alarm.revision,
                scheduledTriggerMillis = alarm.nextTriggerTime,
                scheduleAuthorityRevision = validAuthority?.scheduleRevision,
                definitionRevision = validAuthority?.definitionRevision,
                instanceToken = validAuthority?.instanceToken,
                restoreEpoch = validAuthority?.restoreEpoch,
                operationKind = CallerOperationKind.EXACT_OCCURRENCE,
                capturedAtMillis = capturedAtMillis,
                targetScheduledAt = alarm.nextTriggerTime.takeIf { it > 0L },
                targetFireId = targetFireId,
                allowZeroedDisabledRoom = allowZeroedDisabledRoom,
                allowZeroTriggerPreArm = allowZeroTriggerPreArm,
                sourceTag = sourceTag
            )
        }

        /**
         * Captures definition-level DELETE / DISABLE / REPLACE_BEFORE_SAVE identity held by a caller.
         * Preserves [mutationOrder] explicitly and leaves authority fields `null` if [authority] is absent or mismatched.
         */
        fun captureNormalDefinition(
            alarm: Alarm,
            authority: ScheduleAuthority?,
            actionKind: DefinitionActionKind,
            mutationOrder: RoomMutationOrder,
            capturedAtMillis: Long,
            sourceTag: String = "NORMAL_DEFINITION_ORIGIN"
        ): CallerOriginIdentity {
            val validAuthority = authority?.takeIf { it.alarmId == alarm.id }
            return CallerOriginIdentity(
                alarmId = alarm.id,
                alarmKind = alarm.kind,
                generation = alarm.generation,
                revision = alarm.revision,
                scheduledTriggerMillis = alarm.nextTriggerTime,
                scheduleAuthorityRevision = validAuthority?.scheduleRevision,
                definitionRevision = validAuthority?.definitionRevision,
                instanceToken = validAuthority?.instanceToken,
                restoreEpoch = validAuthority?.restoreEpoch,
                operationKind = when (actionKind) {
                    DefinitionActionKind.DELETE -> CallerOperationKind.DELETE
                    DefinitionActionKind.DISABLE -> CallerOperationKind.DISABLE
                    DefinitionActionKind.REPLACE_BEFORE_SAVE -> CallerOperationKind.REPLACE_BEFORE_SAVE
                },
                mutationOrder = mutationOrder,
                capturedAtMillis = capturedAtMillis,
                sourceTag = sourceTag
            )
        }

        /**
         * Captures Quick Alarm identity separately from Normal occurrence lineage.
         */
        fun captureQuick(
            alarm: Alarm,
            capturedAtMillis: Long? = null,
            sourceTag: String = "QUICK_ORIGIN"
        ): CallerOriginIdentity = CallerOriginIdentity(
            alarmId = alarm.id,
            alarmKind = alarm.kind,
            scheduledTriggerMillis = alarm.quickTriggerEpochMs,
            operationKind = CallerOperationKind.QUICK_OCCURRENCE,
            capturedAtMillis = capturedAtMillis,
            quickTriggerEpochMs = alarm.quickTriggerEpochMs,
            quickRelativeElapsedMs = alarm.quickRelativeElapsedMs,
            quickBootCount = alarm.quickBootCount,
            sourceTag = sourceTag
        )

        /**
         * Represents legacy `alarmId`-only callers (e.g., external `AlarmScheduler.cancel(alarmId)`).
         * Always fails closed as unqualified until S1-B2 captures pre-mutation origin identity.
         */
        fun unqualifiedAlarmIdOnly(
            alarmId: Long,
            sourceTag: String = "LEGACY_ALARM_ID_ONLY"
        ): CallerOriginIdentity = CallerOriginIdentity(
            alarmId = alarmId,
            operationKind = CallerOperationKind.LEGACY_ALARM_ID_ONLY,
            sourceTag = sourceTag
        )

        /**
         * Captures only the partial arguments currently accepted by legacy `cancelScheduledEntries(...)`
         * without fabricating missing occurrence or authority lineage.
         */
        fun fromLegacyCancelArgs(
            alarmId: Long,
            expectedGeneration: Int? = null,
            expectedRevision: Long? = null,
            sourceTag: String = "LEGACY_CANCEL_ARGS"
        ): CallerOriginIdentity = CallerOriginIdentity(
            alarmId = alarmId,
            alarmKind = if (expectedGeneration != null || expectedRevision != null) Alarm.KIND_NORMAL else null,
            generation = expectedGeneration,
            scheduleAuthorityRevision = expectedRevision,
            operationKind = if (expectedGeneration == null && expectedRevision == null) {
                CallerOperationKind.LEGACY_ALARM_ID_ONLY
            } else {
                CallerOperationKind.EXACT_OCCURRENCE
            },
            sourceTag = sourceTag
        )
    }
}

/**
 * Typed propagation and admissibility result produced from [CallerOriginIdentity].
 *
 * Explicitly does NOT execute OS cancellation, DirectBoot removal, Room mutations, or WorkManager
 * cancellation. Destructive cleanup and OS completion flags are invariant `false` in S1-B1.
 */
sealed interface CallerOriginPropagationResult {
    val alarmId: Long
    val originIdentity: CallerOriginIdentity

    val isQualifiedCandidate: Boolean
        get() = this is QualifiedCandidate || this is PlannedCandidate

    val isPlannerAccepted: Boolean
        get() = this is PlannedCandidate

    val allowsDestructiveCleanup: Boolean
        get() = false

    val isOsCancelCompleted: Boolean
        get() = false

    val executesCancellation: Boolean
        get() = false

    data class QualifiedCandidate(
        override val alarmId: Long,
        override val originIdentity: CallerOriginIdentity,
        val fence: CancellationFence
    ) : CallerOriginPropagationResult

    data class PlannedCandidate(
        override val alarmId: Long,
        override val originIdentity: CallerOriginIdentity,
        val fence: CancellationFence,
        val outcome: CancelOutcome.Accepted
    ) : CallerOriginPropagationResult

    data class Unqualified(
        override val alarmId: Long,
        override val originIdentity: CallerOriginIdentity,
        val unqualifiedReason: CallerOriginUnqualifiedReason,
        val plannerReason: CancellationRejectionReason? = null,
        val detail: String,
        val missingFields: Set<String> = emptySet(),
        val outcome: CancelOutcome? = null
    ) : CallerOriginPropagationResult

    data class Deferred(
        override val alarmId: Long,
        override val originIdentity: CallerOriginIdentity,
        val unqualifiedReason: CallerOriginUnqualifiedReason,
        val plannerReason: CancellationRejectionReason,
        val detail: String,
        val fence: CancellationFence,
        val outcome: CancelOutcome
    ) : CallerOriginPropagationResult

    data class Rejected(
        override val alarmId: Long,
        override val originIdentity: CallerOriginIdentity,
        val reason: CancellationRejectionReason,
        val detail: String,
        val fence: CancellationFence? = null,
        val outcome: CancelOutcome? = null
    ) : CallerOriginPropagationResult
}
