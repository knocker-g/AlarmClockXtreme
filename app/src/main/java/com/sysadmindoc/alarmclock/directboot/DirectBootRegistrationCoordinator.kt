package com.sysadmindoc.alarmclock.directboot

import android.content.Context
import android.util.Log

/**
 * Replace and concurrency coordinator for KNO-75 Phase D3-B1 / D3-B1A.
 *
 * Implements the explicit 6-step lifecycle contract:
 * 1. Read authoritative Queue state from DE storage under [DirectBootStorageLock.lock].
 * 2. Calculate desired head and action via [DirectBootRegistrationPlanner.plan].
 * 3. Determine old and new exact identities.
 * 4. Apply bounded OS operation through the injectable [DirectBootExactAlarmManagerBoundary]
 *    OUTSIDE the storage lock.
 *    - Cancellation-before-registration safety: when replacing an alarm, the old exact identity
 *      is cancelled first. The new identity is registered ONLY AFTER cancellation is confirmed
 *      successful or absent ([ExactCancellationResult.isSuccessfulOrAbsent]). If cancellation
 *      fails or is uncertain, new registration is ABORTED, and a typed failed/uncertain result is returned.
 * 5. Re-read authoritative Queue state from DE storage under [DirectBootStorageLock.lock].
 * 6. Classify the execution outcome considering the complete typed storage state:
 *    - Corrupt or UnsupportedSchema post-op storage strictly returns [RegistrationCoordinatorResult.PostOpStorageUnverifiable]
 *      and never returns [RegistrationCoordinatorResult.StableSuccess].
 *    - Head identity drift is detected and returned as [RegistrationCoordinatorResult.HeadChangedDuringOperation].
 *    - Zero destructive cleanups on unverifiable storage.
 *
 * Concurrency & Atomicity Boundaries:
 * - Atomicity across SharedPreferences and AlarmManager is NOT claimed.
 * - Storage lock is NEVER held across OS IPC calls ([registerExactAlarm], [cancelExactAlarm]).
 * - Legacy V1 scalar keys and request codes (alarmId.toInt()) are never cancelled or modified.
 */
object DirectBootRegistrationCoordinator {
    private const val TAG = "DirectBootRegCoord"

    /**
     * Executes the 6-step registration lifecycle against Device-Protected storage.
     */
    fun coordinateWithStorage(
        context: Context,
        nowEpochMs: Long,
        currentlyArmedIdentity: DirectBootV2NormalIntentIdentity?,
        osBoundary: DirectBootExactAlarmManagerBoundary
    ): RegistrationCoordinatorResult {
        // Step 1: Read authoritative Queue state under lock
        val initialState = synchronized(DirectBootStorageLock.lock) {
            DirectBootAlarmCache.inspectQueueStorageState(context)
        }

        // Steps 2 - 6
        return coordinate(
            initialQueueState = initialState,
            nowEpochMs = nowEpochMs,
            currentlyArmedIdentity = currentlyArmedIdentity,
            osBoundary = osBoundary,
            reReadQueueState = {
                synchronized(DirectBootStorageLock.lock) {
                    DirectBootAlarmCache.inspectQueueStorageState(context)
                }
            }
        )
    }

    /**
     * Executes the 6-step registration lifecycle with injectable re-read provider.
     * Enables deterministic testing of concurrency interleavings, OS failures, and queue changes.
     */
    fun coordinate(
        initialQueueState: QueueStorageState,
        nowEpochMs: Long,
        currentlyArmedIdentity: DirectBootV2NormalIntentIdentity?,
        osBoundary: DirectBootExactAlarmManagerBoundary,
        reReadQueueState: () -> QueueStorageState
    ): RegistrationCoordinatorResult {
        // Step 2: Calculate desired head and plan
        val plan = DirectBootRegistrationPlanner.plan(
            queueState = initialQueueState,
            nowEpochMs = nowEpochMs,
            currentlyArmedIdentity = currentlyArmedIdentity
        )

        // Step 3: Determine old/new exact identities
        val oldIdentityToCancel: DirectBootV2NormalIntentIdentity? = when (plan) {
            is DirectBootRegistrationPlan.ArmNormal -> plan.oldIdentityToCancel
            is DirectBootRegistrationPlan.CancelCurrent -> plan.oldIdentityToCancel
            is DirectBootRegistrationPlan.BlockedByQuickHead -> plan.oldIdentityToCancel
            is DirectBootRegistrationPlan.BlockedOverdueNeedsCanonicalPolicy -> plan.oldIdentityToCancel
            is DirectBootRegistrationPlan.InvalidNormalHead -> plan.oldIdentityToCancel
            is DirectBootRegistrationPlan.AlreadyArmed -> null
            is DirectBootRegistrationPlan.NoOp -> null
            is DirectBootRegistrationPlan.StorageCorrupt -> null
            is DirectBootRegistrationPlan.UnsupportedSchema -> null
        }

        val newIdentityToArm: DirectBootV2NormalIntentIdentity? = when (plan) {
            is DirectBootRegistrationPlan.ArmNormal -> plan.newIdentity
            else -> null
        }

        // If plan requires no OS mutation (e.g. AlreadyArmed, StorageCorrupt, NoOp), halt early
        if (oldIdentityToCancel == null && newIdentityToArm == null) {
            return RegistrationCoordinatorResult.PlanningHalted(
                plan = plan,
                finalQueueState = initialQueueState
            )
        }

        // Step 4: Apply bounded OS operations OUTSIDE storage lock
        var cancellationResult: ExactCancellationResult? = null
        if (oldIdentityToCancel != null) {
            cancellationResult = try {
                osBoundary.cancelExactAlarm(oldIdentityToCancel)
            } catch (e: Exception) {
                Log.e(TAG, "Unexpected exception during cancelExactAlarm for ${oldIdentityToCancel.alarmId}", e)
                val msg = "Exception during cancel: ${e.message}"
                ExactCancellationResult.CancellationFailure(msg, e)
            }

            // CRITICAL CANCELLATION-BEFORE-REGISTRATION SAFETY GATE:
            // Register new identity ONLY after cancellation is confirmed successful or absent.
            // If cancellation failed or is uncertain, do NOT register the new alarm!
            if (!cancellationResult.isSuccessfulOrAbsent) {
                val postOpState = reReadQueueState()
                return if (cancellationResult is ExactCancellationResult.StateUncertain) {
                    RegistrationCoordinatorResult.OsStateUncertain(
                        plan = plan,
                        finalQueueState = postOpState,
                        cancellationResult = cancellationResult,
                        registrationResult = null
                    )
                } else {
                    RegistrationCoordinatorResult.OsOperationFailed(
                        plan = plan,
                        finalQueueState = postOpState,
                        cancellationResult = cancellationResult,
                        registrationResult = null
                    )
                }
            }
        }

        var registrationResult: ExactRegistrationResult? = null
        if (newIdentityToArm != null) {
            registrationResult = try {
                osBoundary.registerExactAlarm(newIdentityToArm)
            } catch (e: Exception) {
                Log.e(TAG, "Unexpected exception during registerExactAlarm for ${newIdentityToArm.alarmId}", e)
                val msg = "Exception during register: ${e.message}"
                ExactRegistrationResult.RegistrationException(msg, e)
            }
        }

        // Step 5: Re-read authoritative Queue state
        val postOpState = reReadQueueState()

        // Step 6: Classify outcome
        // 6a: Check for OS registration failure or uncertainty
        if (registrationResult != null && !registrationResult.isAccepted) {
            return if (registrationResult is ExactRegistrationResult.StateUncertain) {
                RegistrationCoordinatorResult.OsStateUncertain(
                    plan = plan,
                    finalQueueState = postOpState,
                    cancellationResult = cancellationResult,
                    registrationResult = registrationResult
                )
            } else {
                RegistrationCoordinatorResult.OsOperationFailed(
                    plan = plan,
                    finalQueueState = postOpState,
                    cancellationResult = cancellationResult,
                    registrationResult = registrationResult
                )
            }
        }

        // 6b: Check post-operation storage verifiability.
        // Corrupt or UnsupportedSchema post-operation state MUST NOT return StableSuccess.
        when (postOpState) {
            is QueueStorageState.Corrupt -> {
                return RegistrationCoordinatorResult.PostOpStorageUnverifiable.StorageCorrupt(
                    plan = plan,
                    reason = postOpState.reason,
                    finalQueueState = postOpState,
                    cancellationResult = cancellationResult,
                    registrationResult = registrationResult
                )
            }
            is QueueStorageState.UnsupportedSchema -> {
                return RegistrationCoordinatorResult.PostOpStorageUnverifiable.UnsupportedSchema(
                    plan = plan,
                    foundVersion = postOpState.foundVersion,
                    expectedVersion = postOpState.expectedVersion,
                    finalQueueState = postOpState,
                    cancellationResult = cancellationResult,
                    registrationResult = registrationResult
                )
            }
            else -> { /* ValidEmpty, Absent, ValidWithItems */ }
        }

        // 6c: Check if authoritative queue head changed during OS operation
        val initialHead = extractQueueHead(initialQueueState)
        val postOpHead = extractQueueHead(postOpState)

        val headChanged = initialHead != postOpHead
        return if (headChanged) {
            RegistrationCoordinatorResult.HeadChangedDuringOperation(
                plan = plan,
                initialQueueHead = initialHead,
                postOpQueueHead = postOpHead,
                finalQueueState = postOpState,
                cancellationResult = cancellationResult,
                registrationResult = registrationResult
            )
        } else {
            RegistrationCoordinatorResult.StableSuccess(
                plan = plan,
                finalQueueState = postOpState,
                cancellationResult = cancellationResult,
                registrationResult = registrationResult
            )
        }
    }

    private fun extractQueueHead(state: QueueStorageState): DirectBootQueueItem? {
        return when (state) {
            is QueueStorageState.ValidWithItems -> state.items.firstOrNull()
            else -> null
        }
    }
}

/**
 * Result hierarchy emitted by [DirectBootRegistrationCoordinator].
 */
sealed class RegistrationCoordinatorResult {
    abstract val plan: DirectBootRegistrationPlan
    abstract val finalQueueState: QueueStorageState

    /**
     * Plan was successfully executed with the OS, the authoritative queue remained stable,
     * and final storage state is verified.
     */
    data class StableSuccess(
        override val plan: DirectBootRegistrationPlan,
        override val finalQueueState: QueueStorageState,
        val cancellationResult: ExactCancellationResult?,
        val registrationResult: ExactRegistrationResult?
    ) : RegistrationCoordinatorResult()

    /**
     * The OS operation completed, but the authoritative queue head changed concurrently
     * while the OS operation was being executed.
     */
    data class HeadChangedDuringOperation(
        override val plan: DirectBootRegistrationPlan,
        val initialQueueHead: DirectBootQueueItem?,
        val postOpQueueHead: DirectBootQueueItem?,
        override val finalQueueState: QueueStorageState,
        val cancellationResult: ExactCancellationResult?,
        val registrationResult: ExactRegistrationResult?
    ) : RegistrationCoordinatorResult()

    /**
     * An OS operation (registration or cancellation) failed deterministically.
     */
    data class OsOperationFailed(
        override val plan: DirectBootRegistrationPlan,
        override val finalQueueState: QueueStorageState,
        val cancellationResult: ExactCancellationResult?,
        val registrationResult: ExactRegistrationResult?
    ) : RegistrationCoordinatorResult()

    /**
     * OS state is uncertain following execution (e.g. timeout or IPC abort).
     */
    data class OsStateUncertain(
        override val plan: DirectBootRegistrationPlan,
        override val finalQueueState: QueueStorageState,
        val cancellationResult: ExactCancellationResult?,
        val registrationResult: ExactRegistrationResult?
    ) : RegistrationCoordinatorResult()

    /**
     * Planning concluded that zero OS mutations were warranted (e.g. AlreadyArmed, StorageCorrupt, NoOp).
     */
    data class PlanningHalted(
        override val plan: DirectBootRegistrationPlan,
        override val finalQueueState: QueueStorageState
    ) : RegistrationCoordinatorResult()

    /**
     * Post-operation queue storage could not be verified (Corrupt or UnsupportedSchema).
     * Strictly prevents returning StableSuccess.
     */
    sealed class PostOpStorageUnverifiable : RegistrationCoordinatorResult() {
        abstract val cancellationResult: ExactCancellationResult?
        abstract val registrationResult: ExactRegistrationResult?

        data class StorageCorrupt(
            override val plan: DirectBootRegistrationPlan,
            val reason: String,
            override val finalQueueState: QueueStorageState,
            override val cancellationResult: ExactCancellationResult?,
            override val registrationResult: ExactRegistrationResult?
        ) : PostOpStorageUnverifiable()

        data class UnsupportedSchema(
            override val plan: DirectBootRegistrationPlan,
            val foundVersion: Int,
            val expectedVersion: Int,
            override val finalQueueState: QueueStorageState,
            override val cancellationResult: ExactCancellationResult?,
            override val registrationResult: ExactRegistrationResult?
        ) : PostOpStorageUnverifiable()
    }
}
