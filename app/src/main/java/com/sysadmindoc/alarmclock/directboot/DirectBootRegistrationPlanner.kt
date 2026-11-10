package com.sysadmindoc.alarmclock.directboot

import android.content.Context

/**
 * Inactive, pure registration decision planner for KNO-75 Phase D3-B1 / D3-B1A.
 *
 * Responsibilities:
 * - Consumes a typed DE Queue storage state ([QueueStorageState]) and an explicit clock instant ([nowEpochMs]).
 * - Produces a pure, typed registration plan ([DirectBootRegistrationPlan]) without performing OS side-effects.
 * - Enforces fail-closed queue inspection:
 *   - Absent or valid empty queue plans cancellation of any currently armed identity.
 *   - Quick head candidate explicitly blocks scheduling ([DirectBootRegistrationPlan.BlockedByQuickHead]);
 *     the planner never skips an unqualified Quick head to arm a later Normal candidate.
 *   - Corrupt queue storage fail-closed: preserves raw storage, plans no arming or clearing.
 *   - Unsupported schema fail-closed: preserves raw storage, plans no arming or clearing.
 *   - Invalid Normal head identity plans rejection ([DirectBootRegistrationPlan.InvalidNormalHead]).
 *   - Overdue triggers ([triggerTime <= nowEpochMs]) are separated from structural validity:
 *     overdue candidates must NOT be automatically scheduled with AlarmManager, producing
 *     [DirectBootRegistrationPlan.BlockedOverdueNeedsCanonicalPolicy] with zero new registration calls.
 *     Overdue heads are never classified as storage corruption, and no queue deletion occurs.
 *   - Identical registered identity produces [DirectBootRegistrationPlan.AlreadyArmed] (no-op).
 *   - Changed registered identity produces [DirectBootRegistrationPlan.ArmNormal] with the prior
 *     identity designated in [DirectBootRegistrationPlan.ArmNormal.oldIdentityToCancel].
 * - Invariant: Zero storage mutations, zero deletions, and zero AlarmManager calls.
 */
object DirectBootRegistrationPlanner {

    /**
     * Evaluates the typed DE Queue storage state against the current clock and armed state.
     *
     * @param queueState authoritative state from [DirectBootAlarmCache.inspectQueueStorageState]
     * @param nowEpochMs concrete wall-clock instant in milliseconds
     * @param currentlyArmedIdentity the identity currently armed in OS AlarmManager, if known
     * @return a typed [DirectBootRegistrationPlan] representing the required action
     */
    fun plan(
        queueState: QueueStorageState,
        nowEpochMs: Long,
        currentlyArmedIdentity: DirectBootV2NormalIntentIdentity? = null
    ): DirectBootRegistrationPlan {
        return when (queueState) {
            is QueueStorageState.Absent -> {
                if (currentlyArmedIdentity != null) {
                    DirectBootRegistrationPlan.CancelCurrent(
                        oldIdentityToCancel = currentlyArmedIdentity,
                        reason = DirectBootCancelReason.QUEUE_ABSENT
                    )
                } else {
                    val msg = "Queue storage is absent; no alarm to arm"
                    DirectBootRegistrationPlan.NoOp(msg)
                }
            }

            is QueueStorageState.ValidEmpty -> {
                if (currentlyArmedIdentity != null) {
                    DirectBootRegistrationPlan.CancelCurrent(
                        oldIdentityToCancel = currentlyArmedIdentity,
                        reason = DirectBootCancelReason.QUEUE_EMPTY
                    )
                } else {
                    val msg = "Queue is valid and empty; no alarm to arm"
                    DirectBootRegistrationPlan.NoOp(msg)
                }
            }

            is QueueStorageState.Corrupt -> {
                DirectBootRegistrationPlan.StorageCorrupt(queueState.reason)
            }

            is QueueStorageState.UnsupportedSchema -> {
                DirectBootRegistrationPlan.UnsupportedSchema(
                    foundVersion = queueState.foundVersion,
                    expectedVersion = queueState.expectedVersion
                )
            }

            is QueueStorageState.ValidWithItems -> {
                planForValidItems(
                    items = queueState.items,
                    nowEpochMs = nowEpochMs,
                    currentlyArmedIdentity = currentlyArmedIdentity
                )
            }
        }
    }

    /**
     * Reads the authoritative DE storage state from [context] under [DirectBootStorageLock.lock]
     * and plans registration against the provided [nowEpochMs].
     */
    fun planFromStorage(
        context: Context,
        nowEpochMs: Long,
        currentlyArmedIdentity: DirectBootV2NormalIntentIdentity? = null
    ): DirectBootRegistrationPlan {
        val state = synchronized(DirectBootStorageLock.lock) {
            DirectBootAlarmCache.inspectQueueStorageState(context)
        }
        return plan(state, nowEpochMs, currentlyArmedIdentity)
    }

    private fun planForValidItems(
        items: List<DirectBootQueueItem>,
        nowEpochMs: Long,
        currentlyArmedIdentity: DirectBootV2NormalIntentIdentity?
    ): DirectBootRegistrationPlan {
        val head = items.firstOrNull()
            ?: return if (currentlyArmedIdentity != null) {
                DirectBootRegistrationPlan.CancelCurrent(
                    oldIdentityToCancel = currentlyArmedIdentity,
                    reason = DirectBootCancelReason.QUEUE_EMPTY
                )
            } else {
                val msg = "Queue items list is empty; no alarm to arm"
                DirectBootRegistrationPlan.NoOp(msg)
            }

        // Rule 1: Quick Head must explicitly block scheduling.
        // We MUST NOT skip an unqualified Quick head to arm a later Normal item.
        if (head.kind == DirectBootQueueItem.KIND_QUICK) {
            return DirectBootRegistrationPlan.BlockedByQuickHead(
                quickHead = head,
                oldIdentityToCancel = currentlyArmedIdentity
            )
        }

        // Rule 2: Non-Normal, Non-Quick kinds are structurally unknown.
        if (head.kind != DirectBootQueueItem.KIND_NORMAL) {
            return DirectBootRegistrationPlan.InvalidNormalHead(
                invalidHead = head,
                status = QueueCandidateStatus.INELIGIBLE_UNKNOWN_KIND,
                oldIdentityToCancel = currentlyArmedIdentity
            )
        }

        // Rule 3: Structural eligibility evaluation (now = 0L evaluates structural fields
        // without disqualifying past/overdue trigger timestamps).
        val structuralStatus = head.evaluateEligibility(now = 0L, allowLegacyV1 = false)
        if (structuralStatus != QueueCandidateStatus.ELIGIBLE) {
            return DirectBootRegistrationPlan.InvalidNormalHead(
                invalidHead = head,
                status = structuralStatus,
                oldIdentityToCancel = currentlyArmedIdentity
            )
        }

        // Rule 4: Overdue evaluation.
        // Overdue triggers are structurally valid, but must NOT be automatically re-armed in AlarmManager.
        // They are deferred to canonical policy without Queue deletion, fabricated delivery markers,
        // or automatic late delivery.
        if (nowEpochMs > 0L && head.triggerTime <= nowEpochMs) {
            return DirectBootRegistrationPlan.BlockedOverdueNeedsCanonicalPolicy(
                overdueHead = head,
                oldIdentityToCancel = currentlyArmedIdentity
            )
        }

        // Rule 5: Derive canonical V2 Normal Intent identity.
        val desiredIdentity = DirectBootV2NormalIntentIdentity.fromQueueItem(head)

        // Rule 6: Identity comparison against currently armed OS alarm.
        return if (currentlyArmedIdentity != null && currentlyArmedIdentity == desiredIdentity) {
            DirectBootRegistrationPlan.AlreadyArmed(
                currentIdentity = desiredIdentity,
                queueHead = head
            )
        } else {
            DirectBootRegistrationPlan.ArmNormal(
                newIdentity = desiredIdentity,
                oldIdentityToCancel = currentlyArmedIdentity,
                queueHead = head
            )
        }
    }
}

/**
 * Concrete cancellation reason for [DirectBootRegistrationPlan.CancelCurrent].
 */
enum class DirectBootCancelReason {
    QUEUE_ABSENT,
    QUEUE_EMPTY,
    HEAD_INVALID,
    HEAD_BLOCKED_BY_QUICK,
    HEAD_BLOCKED_OVERDUE,
    MANUAL_UNSET
}

/**
 * Typed registration decision emitted by [DirectBootRegistrationPlanner].
 */
sealed class DirectBootRegistrationPlan {

    /**
     * Authoritative head requires scheduling with OS AlarmManager.
     *
     * @param newIdentity The canonical V2 identity to arm.
     * @param oldIdentityToCancel A prior identity currently registered that must be cancelled, if any.
     * @param queueHead The authoritative queue head candidate.
     */
    data class ArmNormal(
        val newIdentity: DirectBootV2NormalIntentIdentity,
        val oldIdentityToCancel: DirectBootV2NormalIntentIdentity?,
        val queueHead: DirectBootQueueItem
    ) : DirectBootRegistrationPlan()

    /**
     * Authoritative head matches the currently registered OS alarm.
     * No registration side-effect required.
     */
    data class AlreadyArmed(
        val currentIdentity: DirectBootV2NormalIntentIdentity,
        val queueHead: DirectBootQueueItem
    ) : DirectBootRegistrationPlan()

    /**
     * Queue is absent or empty, but an OS alarm is currently registered and must be cancelled.
     */
    data class CancelCurrent(
        val oldIdentityToCancel: DirectBootV2NormalIntentIdentity,
        val reason: DirectBootCancelReason
    ) : DirectBootRegistrationPlan()

    /**
     * No registration action is required (e.g. queue empty and nothing armed).
     */
    data class NoOp(val reason: String) : DirectBootRegistrationPlan()

    /**
     * The earliest queue candidate is a Quick alarm.
     * V2 Quick registration is deferred, and the planner strictly refuses to skip
     * the Quick head to arm a subsequent Normal alarm.
     */
    data class BlockedByQuickHead(
        val quickHead: DirectBootQueueItem,
        val oldIdentityToCancel: DirectBootV2NormalIntentIdentity?
    ) : DirectBootRegistrationPlan()

    /**
     * Authoritative queue head triggerTime is past or equal to current wall clock.
     * Structurally valid, but deferred to canonical overdue policy without automatic re-arm,
     * queue deletion, or fabricated delivery markers.
     */
    data class BlockedOverdueNeedsCanonicalPolicy(
        val overdueHead: DirectBootQueueItem,
        val oldIdentityToCancel: DirectBootV2NormalIntentIdentity?
    ) : DirectBootRegistrationPlan()

    /**
     * Authoritative queue head failed structural eligibility.
     */
    data class InvalidNormalHead(
        val invalidHead: DirectBootQueueItem,
        val status: QueueCandidateStatus,
        val oldIdentityToCancel: DirectBootV2NormalIntentIdentity?
    ) : DirectBootRegistrationPlan()

    /**
     * Queue storage is corrupt. Fail-closed: preserves raw storage, plans no arming or cancellation.
     */
    data class StorageCorrupt(val reason: String) : DirectBootRegistrationPlan()

    /**
     * Queue storage schema is unsupported. Fail-closed: preserves raw storage, plans no arming or cancellation.
     */
    data class UnsupportedSchema(
        val foundVersion: Int,
        val expectedVersion: Int
    ) : DirectBootRegistrationPlan()
}
