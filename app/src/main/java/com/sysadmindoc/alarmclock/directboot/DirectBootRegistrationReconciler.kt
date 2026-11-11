package com.sysadmindoc.alarmclock.directboot

import android.content.Context
import android.content.SharedPreferences
import android.os.Process
import androidx.annotation.VisibleForTesting

/**
 * Result hierarchy for [DirectBootRegistrationReconciler].
 */
sealed interface ReconcilerResult {

    val isSuccess: Boolean get() = this is StableSuccess

    /**
     * Plan was successfully executed against the OS, the authoritative queue remained stable,
     * and the terminal IDLE marker was committed to disk.
     */
    data class StableSuccess(
        val plan: DirectBootRegistrationPlan,
        val cancellationResult: ExactCancellationResult?,
        val registrationResult: ExactRegistrationResult?
    ) : ReconcilerResult

    /**
     * Reconciler evaluated the authoritative Queue, but no OS operation was needed
     * (e.g. [DirectBootRegistrationPlan.NoOp], [DirectBootRegistrationPlan.BlockedByQuickHead]).
     */
    data class PlanningHalted(
        val plan: DirectBootRegistrationPlan
    ) : ReconcilerResult

    /**
     * An OS operation completed or was aborted because the authoritative Queue head drifted
     * concurrently during execution.
     */
    data class HeadChangedDuringOperation(
        val plan: DirectBootRegistrationPlan,
        val initialHead: DirectBootQueueItem?,
        val postOpHead: DirectBootQueueItem?
    ) : ReconcilerResult

    /**
     * An OS operation (cancellation or registration) failed or remained in an uncertain state.
     * The durable Marker was committed to FAILED_BLOCKED with recovery targets preserved.
     */
    data class OsOperationFailed(
        val plan: DirectBootRegistrationPlan,
        val cancellationResult: ExactCancellationResult?,
        val registrationResult: ExactRegistrationResult?,
        val reason: String
    ) : ReconcilerResult

    /**
     * The Queue or Marker storage was found to be Corrupt or UnsupportedSchema.
     * Failed closed without touching OS AlarmManager.
     */
    data class StorageUnverifiable(
        val reason: String
    ) : ReconcilerResult

    /**
     * Existing durable Marker was found in an unrecovered state requiring recovery,
     * or recovery preconditions could not be safely satisfied.
     */
    data class RecoveryRequired(
        val existingMarker: DirectBootRegistrationMarker,
        val reason: String,
        val plan: DirectBootRegistrationPlan? = null
    ) : ReconcilerResult

    /**
     * Recovery entry gate blocked recovery execution because exclusive ownership could not be proven,
     * an active lease belongs to another process, clock rollback occurred, or retry budget is exhausted.
     */
    data class RecoveryBlocked(
        val reason: String,
        val existingMarker: DirectBootRegistrationMarker? = null
    ) : ReconcilerResult

    /**
     * Pre-OS IN_FLIGHT intent commit failed (e.g. CAS mismatch or storage write failure).
     * Zero OS operations were performed.
     */
    data class IntentPersistenceFailed(
        val writeResult: MarkerWriteResult
    ) : ReconcilerResult

    /**
     * Terminal marker commit or failure marker commit failed to persist to disk.
     * State is strictly unknown; success is never reported.
     */
    data class Unknown(
        val reason: String,
        val cause: Throwable? = null
    ) : ReconcilerResult

    /**
     * Request coalesced into an active in-flight single-flight execution owner.
     */
    data class Coalesced(
        val isReentrant: Boolean,
        val activeOwnerThreadId: Long?
    ) : ReconcilerResult

    /**
     * Single-flight execution failed with an unhandled exception during effect execution.
     */
    data class SingleFlightFailure(
        val error: Throwable,
        val hasUnresolvedRecheck: Boolean,
        val pendingRecheckCount: Int
    ) : ReconcilerResult

    /**
     * Single-flight execution reached pass budget limit without settling all rechecks.
     */
    data class PassBudgetExhausted(
        val totalPasses: Int,
        val pendingRecheckCount: Int
    ) : ReconcilerResult
}

/**
 * Marker-Backed Registration Reconciler with Crash Recovery and Bounded Compensation
 * for KNO-75 Phase D3-B2-P2-1A2 / P2-1A2D.
 *
 * Responsibilities & Safety Guarantees:
 * - Single process-local execution owner backed by [DirectBootRegistrationSingleFlight].
 * - Deterministic, bounded crash recovery across cutpoints C0 through C6.
 * - Recovery Entry Gate: evaluates clock rollback, retry budget, active lease, and provable exclusive ownership.
 * - Boot provenance tracking: distinguishes same boot from reboot and unverified provenance.
 * - Read/plan/intent write share a single atomic DE critical section under [DirectBootStorageLock.lock].
 * - Strict lock segregation: [DirectBootStorageLock.lock] is NEVER held across OS AlarmManager calls.
 * - Cancellation-before-registration invariant: old exact alarms are cancelled before registering new.
 * - Settles OS state uncertainty during recovery by cancelling unresolved targets before re-planning.
 * - Queue drift detection after cancellation, after registration, and during recovery.
 * - Sequential opSeq CAS chaining: never pre-increments opSeq; chains off returned committed markers.
 * - Preserves exact recovery targets in FAILED_BLOCKED on retry exhaustion or uncertain failure.
 * - Zero legacy V1 identity or request-code mutation; operates strictly on [DirectBootV2NormalIntentIdentity].
 */
class DirectBootRegistrationReconciler internal constructor(
    private val context: Context,
    private val osBoundary: DirectBootExactAlarmManagerBoundary,
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val bootProvenanceProvider: (() -> BootProvenance)? = null,
    maxPassesPerRun: Int = DirectBootRegistrationSingleFlight.DEFAULT_MAX_PASSES_PER_RUN,
    maxPendingSampleCapacity: Int = DirectBootRegistrationSingleFlight.DEFAULT_MAX_PENDING_SAMPLE,
    private val editorCommitter: ((SharedPreferences, String) -> Boolean)? = null,
    private val onInitialStorageRead: (() -> Unit)? = null
) {
    companion object {
        const val DEFAULT_LEASE_DURATION_MS = 15_000L

        @Volatile
        private var defaultInstance: DirectBootRegistrationReconciler? = null
        private val initLock = Any()

        /**
         * Returns the canonical, process-wide reconciler instance.
         */
        fun getInstance(context: Context): DirectBootRegistrationReconciler {
            return defaultInstance ?: synchronized(initLock) {
                defaultInstance ?: DirectBootRegistrationReconciler(
                    context = context.applicationContext,
                    osBoundary = AndroidDirectBootExactAlarmManager(context.applicationContext)
                ).also { defaultInstance = it }
            }
        }

        /**
         * Factory for testing with isolated test contexts, mock OS boundaries, injectable clocks,
         * and injectable boot provenance.
         */
        @VisibleForTesting
        fun createForTesting(
            context: Context,
            osBoundary: DirectBootExactAlarmManagerBoundary,
            clock: () -> Long = { System.currentTimeMillis() },
            bootProvenanceProvider: (() -> BootProvenance)? = null,
            maxPassesPerRun: Int = DirectBootRegistrationSingleFlight.DEFAULT_MAX_PASSES_PER_RUN,
            maxPendingSampleCapacity: Int = DirectBootRegistrationSingleFlight.DEFAULT_MAX_PENDING_SAMPLE,
            editorCommitter: ((SharedPreferences, String) -> Boolean)? = null,
            onInitialStorageRead: (() -> Unit)? = null
        ): DirectBootRegistrationReconciler {
            return DirectBootRegistrationReconciler(
                context = context,
                osBoundary = osBoundary,
                clock = clock,
                bootProvenanceProvider = bootProvenanceProvider,
                maxPassesPerRun = maxPassesPerRun,
                maxPendingSampleCapacity = maxPendingSampleCapacity,
                editorCommitter = editorCommitter,
                onInitialStorageRead = onInitialStorageRead
            )
        }
    }

    private val singleFlight = DirectBootRegistrationSingleFlight<ReconcilerResult>(
        effectRunner = { passContext -> executeReconciliationPass(passContext) },
        maxPassesPerRun = maxPassesPerRun,
        maxPendingSampleCapacity = maxPendingSampleCapacity
    )

    /**
     * Submits a registration request for single-flight reconciliation.
     */
    fun reconcile(
        request: RegistrationRequest = RegistrationRequest("RECONCILE_MANUAL", timestampEpochMs = clock())
    ): ReconcilerResult {
        return when (val sfResult = singleFlight.execute(request)) {
            is SingleFlightResult.Executed -> sfResult.lastResult
            is SingleFlightResult.Coalesced -> ReconcilerResult.Coalesced(
                isReentrant = sfResult.isReentrant,
                activeOwnerThreadId = sfResult.activeOwnerThreadId
            )
            is SingleFlightResult.ExecutionFailed -> ReconcilerResult.SingleFlightFailure(
                error = sfResult.error,
                hasUnresolvedRecheck = sfResult.hasUnresolvedRecheck,
                pendingRecheckCount = sfResult.pendingRecheckCount
            )
            is SingleFlightResult.PassBudgetExhausted -> ReconcilerResult.PassBudgetExhausted(
                totalPasses = sfResult.totalPasses,
                pendingRecheckCount = sfResult.pendingRecheckCount
            )
        }
    }

    private fun executeReconciliationPass(passContext: EffectPassContext): ReconcilerResult {
        val now = clock()
        val currentBoot = currentBootProvenance()

        val initialQueueState: QueueStorageState
        val initialMarker: DirectBootRegistrationMarker

        // Step 1: Initial Read under DE lock
        synchronized(DirectBootStorageLock.lock) {
            initialQueueState = DirectBootAlarmCache.inspectQueueStorageState(context)
            if (initialQueueState is QueueStorageState.Corrupt) {
                return ReconcilerResult.StorageUnverifiable("Queue storage is corrupt: ${initialQueueState.reason}")
            }
            if (initialQueueState is QueueStorageState.UnsupportedSchema) {
                return ReconcilerResult.StorageUnverifiable(
                    "Queue storage has unsupported schema: ${initialQueueState.foundVersion}"
                )
            }

            val markerResult = DirectBootRegistrationMarker.read(context)
            initialMarker = when (markerResult) {
                is MarkerReadResult.Success -> markerResult.marker
                is MarkerReadResult.Absent -> DirectBootRegistrationMarker.createInitialIdle(
                    nowEpochMs = now,
                    bootProvenance = currentBoot
                )
                is MarkerReadResult.Corrupt -> return ReconcilerResult.StorageUnverifiable(
                    "Marker storage is corrupt: ${markerResult.reason}"
                )
                is MarkerReadResult.UnsupportedSchema -> return ReconcilerResult.StorageUnverifiable(
                    "Marker storage has unsupported schema: ${markerResult.foundVersion}"
                )
            }
        } // Step 1 Lock RELEASED!

        onInitialStorageRead?.invoke()

        // Step 2: Recovery Entry Gate Checks
        val rebootDetected = isBootProvenanceUnverified(initialMarker.bootProvenance, currentBoot)
        val isUnverifiedIdle = initialMarker.mainState == RegistrationMainState.IDLE &&
            initialMarker.activeIdentity != null &&
            rebootDetected // Reboot or unverified boot erased AlarmManager state!

        val needsRecovery = initialMarker.mainState == RegistrationMainState.IN_FLIGHT ||
            initialMarker.mainState == RegistrationMainState.FAILED_BLOCKED ||
            isUnverifiedIdle

        if (needsRecovery) {
            // Guard A: Clock rollback check
            if (now < initialMarker.updatedAtEpochMs) {
                return ReconcilerResult.RecoveryBlocked(
                    reason = "Clock rollback detected: current time $now is before marker updatedAtEpochMs ${initialMarker.updatedAtEpochMs}",
                    existingMarker = initialMarker
                )
            }

            // Guard B: Retry budget check
            if (initialMarker.retryCount >= DirectBootRegistrationMarker.MAX_RETRIES) {
                return ReconcilerResult.RecoveryBlocked(
                    reason = "Retry budget exhausted: retryCount=${initialMarker.retryCount} >= MAX_RETRIES=${DirectBootRegistrationMarker.MAX_RETRIES}",
                    existingMarker = initialMarker
                )
            }

            // Guard C: Exclusive ownership check
            val myPidTag = "pid:${Process.myPid()}:"
            val isLeaseActive = !rebootDetected && initialMarker.leaseEpochMs > 0L && now < initialMarker.leaseEpochMs

            if (isLeaseActive) {
                if (!initialMarker.leaseOwner.startsWith(myPidTag)) {
                    return ReconcilerResult.RecoveryBlocked(
                        reason = "Active lease owned by another process on same boot: ${initialMarker.leaseOwner}",
                        existingMarker = initialMarker
                    )
                }
            } else if (!rebootDetected && initialMarker.leaseOwner.isNotBlank() && !initialMarker.leaseOwner.startsWith(myPidTag)) {
                return ReconcilerResult.RecoveryBlocked(
                    reason = "Cannot prove exclusive ownership: lease expired but owned by another process on same boot (${initialMarker.leaseOwner})",
                    existingMarker = initialMarker
                )
            }

            return if (isUnverifiedIdle) {
                recoverUnverifiedIdle(
                    currentMarker = initialMarker,
                    currentBoot = currentBoot,
                    now = now
                )
            } else {
                executeRecoveryPass(
                    currentMarker = initialMarker,
                    currentBoot = currentBoot,
                    now = now
                )
            }
        }

        // Step 3: Normal execution: Atomic DE section to re-read fresh Queue, verify freshness, derive plan, and persist intent
        var plan: DirectBootRegistrationPlan? = null
        var oldToCancel: DirectBootV2NormalIntentIdentity? = null
        var newToArm: DirectBootV2NormalIntentIdentity? = null
        var committedMarker: DirectBootRegistrationMarker? = null
        val freshQueueState: QueueStorageState

        synchronized(DirectBootStorageLock.lock) {
            freshQueueState = DirectBootAlarmCache.inspectQueueStorageState(context)
            if (freshQueueState is QueueStorageState.Corrupt) {
                return ReconcilerResult.StorageUnverifiable("Queue storage is corrupt: ${freshQueueState.reason}")
            }
            if (freshQueueState is QueueStorageState.UnsupportedSchema) {
                return ReconcilerResult.StorageUnverifiable(
                    "Queue storage has unsupported schema: ${freshQueueState.foundVersion}"
                )
            }

            val latestMarkerResult = DirectBootRegistrationMarker.read(context)
            val latestMarker = when (latestMarkerResult) {
                is MarkerReadResult.Success -> latestMarkerResult.marker
                is MarkerReadResult.Absent -> DirectBootRegistrationMarker.createInitialIdle(
                    nowEpochMs = now,
                    bootProvenance = currentBoot
                )
                is MarkerReadResult.Corrupt -> return ReconcilerResult.StorageUnverifiable(
                    "Marker storage is corrupt: ${latestMarkerResult.reason}"
                )
                is MarkerReadResult.UnsupportedSchema -> return ReconcilerResult.StorageUnverifiable(
                    "Marker storage has unsupported schema: ${latestMarkerResult.foundVersion}"
                )
            }

            if (latestMarker.opSeq != initialMarker.opSeq) {
                return ReconcilerResult.IntentPersistenceFailed(
                    MarkerWriteResult.SequenceMismatch(expectedSeq = initialMarker.opSeq, actualSeq = latestMarker.opSeq)
                )
            }

            val derivedPlan = DirectBootRegistrationPlanner.plan(
                queueState = freshQueueState,
                nowEpochMs = now,
                currentlyArmedIdentity = latestMarker.activeIdentity
            )

            // Discriminator C: AlreadyArmed on same boot without scheduled-state evidence surfaces RecoveryRequired
            if (derivedPlan is DirectBootRegistrationPlan.AlreadyArmed) {
                return ReconcilerResult.RecoveryRequired(
                    existingMarker = latestMarker,
                    reason = "Unverified AlreadyArmed: Marker activeIdentity matches Queue head, but OS AlarmManager currentness cannot be proven without external scheduled-state verification",
                    plan = derivedPlan
                )
            }

            val derivedOld = extractOldIdentity(derivedPlan)
            val derivedNew = extractNewIdentity(derivedPlan)

            if (derivedOld == null && derivedNew == null) {
                return ReconcilerResult.PlanningHalted(derivedPlan)
            }

            plan = derivedPlan
            oldToCancel = derivedOld
            newToArm = derivedNew

            val initialPhase = if (derivedOld != null) {
                RegistrationPhase.CANCELLING_OLD
            } else {
                RegistrationPhase.REGISTERING_NEW
            }

            val candidateIntent = latestMarker.copy(
                mainState = RegistrationMainState.IN_FLIGHT,
                phase = initialPhase,
                failureReason = RegistrationFailureReason.NONE,
                leaseOwner = "pid:${Process.myPid()}:reconciler",
                leaseEpochMs = now + DEFAULT_LEASE_DURATION_MS,
                bootProvenance = currentBoot,
                cancellingTarget = derivedOld,
                registeringTarget = derivedNew,
                updatedAtEpochMs = now
            )

            val intentWriteResult = DirectBootRegistrationMarker.writeInternal(
                context = context,
                marker = candidateIntent,
                expectedOpSeq = latestMarker.opSeq,
                editorCommitter = editorCommitter
            )

            committedMarker = when (intentWriteResult) {
                is MarkerWriteResult.Success -> intentWriteResult.marker
                else -> return ReconcilerResult.IntentPersistenceFailed(intentWriteResult)
            }
        } // Step 3 Lock RELEASED!

        // Proceed with normal OS operations OUTSIDE lock
        return executeNormalOsOperations(
            committedMarker = checkNotNull(committedMarker),
            oldToCancel = oldToCancel,
            newToArm = newToArm,
            plan = checkNotNull(plan),
            initialQueueState = freshQueueState,
            currentBoot = currentBoot
        )
    }

    private fun executeNormalOsOperations(
        committedMarker: DirectBootRegistrationMarker,
        oldToCancel: DirectBootV2NormalIntentIdentity?,
        newToArm: DirectBootV2NormalIntentIdentity?,
        plan: DirectBootRegistrationPlan,
        initialQueueState: QueueStorageState,
        currentBoot: BootProvenance
    ): ReconcilerResult {
        var currentCommittedMarker = committedMarker

        check(!Thread.holdsLock(DirectBootStorageLock.lock)) {
            "CRITICAL: DirectBootStorageLock must NOT be held during OS operations"
        }

        // Cancel old identity outside lock when needed
        var cancelResult: ExactCancellationResult? = null
        if (oldToCancel != null) {
            cancelResult = osBoundary.cancelExactAlarm(oldToCancel)
            if (!cancelResult.isSuccessfulOrAbsent) {
                val failureReason = mapCancellationFailureReason(cancelResult)
                val commitFailed = commitFailureMarker(
                    committedMarker = currentCommittedMarker,
                    cancellingTarget = oldToCancel,
                    registeringTarget = newToArm,
                    reason = failureReason,
                    retryCount = currentCommittedMarker.retryCount,
                    bootProvenance = currentBoot
                )
                return if (commitFailed) {
                    ReconcilerResult.Unknown("Terminal failure marker commit failed after cancellation failure")
                } else {
                    ReconcilerResult.OsOperationFailed(
                        plan = plan,
                        cancellationResult = cancelResult,
                        registrationResult = null,
                        reason = "Cancellation failed or uncertain: $cancelResult"
                    )
                }
            }
        }

        // Re-read Queue, check drift, and register new identity outside lock
        if (newToArm != null) {
            val shouldProceedWithRegister = synchronized(DirectBootStorageLock.lock) {
                val postCancelQueueState = DirectBootAlarmCache.inspectQueueStorageState(context)
                if (postCancelQueueState is QueueStorageState.Corrupt ||
                    postCancelQueueState is QueueStorageState.UnsupportedSchema) {
                    val failureReason = if (postCancelQueueState is QueueStorageState.UnsupportedSchema) {
                        RegistrationFailureReason.QUEUE_UNSUPPORTED
                    } else {
                        RegistrationFailureReason.QUEUE_CORRUPT
                    }
                    val commitFailed = commitFailureMarker(
                        committedMarker = currentCommittedMarker,
                        cancellingTarget = oldToCancel,
                        registeringTarget = newToArm,
                        reason = failureReason,
                        retryCount = currentCommittedMarker.retryCount,
                        bootProvenance = currentBoot
                    )
                    return if (commitFailed) {
                        ReconcilerResult.Unknown("Terminal failure marker commit failed after post-cancellation queue corruption")
                    } else {
                        ReconcilerResult.StorageUnverifiable("Queue storage became unverifiable after cancellation")
                    }
                }

                val postCancelHead = extractQueueHead(postCancelQueueState)
                val initialHead = extractQueueHead(initialQueueState)

                if (postCancelHead != initialHead) {
                    val commitFailed = commitFailureMarker(
                        committedMarker = currentCommittedMarker,
                        cancellingTarget = oldToCancel,
                        registeringTarget = newToArm,
                        reason = RegistrationFailureReason.OS_UNCERTAIN,
                        retryCount = currentCommittedMarker.retryCount,
                        bootProvenance = currentBoot
                    )
                    return if (commitFailed) {
                        ReconcilerResult.Unknown("Terminal failure marker commit failed after post-cancellation queue drift")
                    } else {
                        ReconcilerResult.HeadChangedDuringOperation(
                            plan = plan,
                            initialHead = initialHead,
                            postOpHead = postCancelHead
                        )
                    }
                }

                if (currentCommittedMarker.phase != RegistrationPhase.REGISTERING_NEW) {
                    val candidateRegisterPhase = currentCommittedMarker.copy(
                        phase = RegistrationPhase.REGISTERING_NEW,
                        cancellingTarget = null,
                        updatedAtEpochMs = clock()
                    )
                    val phaseWriteResult = DirectBootRegistrationMarker.writeInternal(
                        context = context,
                        marker = candidateRegisterPhase,
                        expectedOpSeq = currentCommittedMarker.opSeq,
                        editorCommitter = editorCommitter
                    )
                    currentCommittedMarker = when (phaseWriteResult) {
                        is MarkerWriteResult.Success -> phaseWriteResult.marker
                        else -> return ReconcilerResult.IntentPersistenceFailed(phaseWriteResult)
                    }
                }
                true
            }

            if (!shouldProceedWithRegister) {
                return ReconcilerResult.Unknown("Unexpected abort during registration phase transition")
            }

            return proceedToRegisterAndCommit(
                committedMarker = currentCommittedMarker,
                newToArm = newToArm,
                cancelResult = cancelResult,
                plan = plan,
                initialQueueState = initialQueueState,
                currentBoot = currentBoot
            )
        } else {
            // Cancel-only plan: commit terminal IDLE with activeIdentity = null
            return synchronized(DirectBootStorageLock.lock) {
                val terminalCandidate = currentCommittedMarker.copy(
                    mainState = RegistrationMainState.IDLE,
                    phase = RegistrationPhase.NONE,
                    failureReason = RegistrationFailureReason.NONE,
                    retryCount = 0,
                    leaseOwner = "",
                    leaseEpochMs = 0L,
                    bootProvenance = currentBoot,
                    activeIdentity = null,
                    cancellingTarget = null,
                    registeringTarget = null,
                    updatedAtEpochMs = clock()
                )
                val terminalWriteResult = DirectBootRegistrationMarker.writeInternal(
                    context = context,
                    marker = terminalCandidate,
                    expectedOpSeq = currentCommittedMarker.opSeq,
                    editorCommitter = editorCommitter
                )
                if (terminalWriteResult !is MarkerWriteResult.Success) {
                    ReconcilerResult.Unknown("Terminal IDLE marker commit failed: $terminalWriteResult")
                } else {
                    ReconcilerResult.StableSuccess(
                        plan = plan,
                        cancellationResult = cancelResult,
                        registrationResult = null
                    )
                }
            }
        }
    }

    private fun recoverUnverifiedIdle(
        currentMarker: DirectBootRegistrationMarker,
        currentBoot: BootProvenance,
        now: Long
    ): ReconcilerResult {
        val activeIdentity = currentMarker.activeIdentity
            ?: return proceedToPlanFresh(currentMarker, currentBoot, now)

        var committedMarker: DirectBootRegistrationMarker? = null
        val freshQueueState: QueueStorageState
        val queueHead: DirectBootQueueItem?
        val shouldReArm: Boolean

        synchronized(DirectBootStorageLock.lock) {
            freshQueueState = DirectBootAlarmCache.inspectQueueStorageState(context)
            if (freshQueueState is QueueStorageState.Corrupt || freshQueueState is QueueStorageState.UnsupportedSchema) {
                return ReconcilerResult.StorageUnverifiable("Queue storage became unverifiable during idle recovery: $freshQueueState")
            }
            queueHead = extractQueueHead(freshQueueState)

            if (queueHead != null && matchesIdentity(queueHead, activeIdentity)) {
                shouldReArm = true
                val candidateIntent = currentMarker.copy(
                    mainState = RegistrationMainState.IN_FLIGHT,
                    phase = RegistrationPhase.REGISTERING_NEW,
                    failureReason = RegistrationFailureReason.NONE,
                    leaseOwner = "pid:${Process.myPid()}:reconciler",
                    leaseEpochMs = now + DEFAULT_LEASE_DURATION_MS,
                    bootProvenance = currentBoot,
                    cancellingTarget = null,
                    registeringTarget = activeIdentity,
                    updatedAtEpochMs = now
                )
                val intentWriteResult = DirectBootRegistrationMarker.writeInternal(
                    context = context,
                    marker = candidateIntent,
                    expectedOpSeq = currentMarker.opSeq,
                    editorCommitter = editorCommitter
                )
                committedMarker = when (intentWriteResult) {
                    is MarkerWriteResult.Success -> intentWriteResult.marker
                    else -> return ReconcilerResult.IntentPersistenceFailed(intentWriteResult)
                }
            } else {
                shouldReArm = false
            }
        } // Lock RELEASED!

        return if (shouldReArm) {
            proceedToRegisterAndCommit(
                committedMarker = checkNotNull(committedMarker),
                newToArm = activeIdentity,
                cancelResult = null,
                plan = DirectBootRegistrationPlan.ArmNormal(
                    newIdentity = activeIdentity,
                    oldIdentityToCancel = null,
                    queueHead = checkNotNull(queueHead)
                ),
                initialQueueState = freshQueueState,
                currentBoot = currentBoot
            )
        } else {
            executeRecoveryPass(
                currentMarker = currentMarker.copy(
                    cancellingTarget = activeIdentity,
                    registeringTarget = null
                ),
                currentBoot = currentBoot,
                now = now
            )
        }
    }

    private fun executeRecoveryPass(
        currentMarker: DirectBootRegistrationMarker,
        currentBoot: BootProvenance,
        now: Long
    ): ReconcilerResult {
        val oldToCancel = currentMarker.cancellingTarget
        val oldToArm = currentMarker.registeringTarget
        var committedMarker: DirectBootRegistrationMarker? = null
        val needsCompensation: Boolean

        // 1. Commit pre-OS compensation intent under lock
        synchronized(DirectBootStorageLock.lock) {
            val nextRetryCount = currentMarker.retryCount + 1
            if (nextRetryCount > DirectBootRegistrationMarker.MAX_RETRIES) {
                return ReconcilerResult.RecoveryBlocked(
                    reason = "Retry budget exhausted during recovery: $nextRetryCount > ${DirectBootRegistrationMarker.MAX_RETRIES}",
                    existingMarker = currentMarker
                )
            }

            // Discriminator A Fix: If both targets are null, there are no OS targets to compensate;
            // do NOT call proceedToPlanFresh inside the lock!
            if (oldToCancel == null && oldToArm == null) {
                needsCompensation = false
            } else {
                needsCompensation = true
                val candidateCompensating = currentMarker.copy(
                    mainState = RegistrationMainState.IN_FLIGHT,
                    phase = RegistrationPhase.COMPENSATING,
                    failureReason = RegistrationFailureReason.NONE,
                    retryCount = nextRetryCount,
                    leaseOwner = "pid:${Process.myPid()}:reconciler",
                    leaseEpochMs = now + DEFAULT_LEASE_DURATION_MS,
                    bootProvenance = currentBoot,
                    cancellingTarget = oldToCancel,
                    registeringTarget = oldToArm,
                    updatedAtEpochMs = now
                )

                val writeResult = DirectBootRegistrationMarker.writeInternal(
                    context = context,
                    marker = candidateCompensating,
                    expectedOpSeq = currentMarker.opSeq,
                    editorCommitter = editorCommitter
                )
                committedMarker = when (writeResult) {
                    is MarkerWriteResult.Success -> writeResult.marker
                    else -> return ReconcilerResult.IntentPersistenceFailed(writeResult)
                }
            }
        } // Lock RELEASED!

        if (!needsCompensation) {
            // OUTSIDE lock: proceedToPlanFresh without holding DirectBootStorageLock
            return proceedToPlanFresh(currentMarker, currentBoot, now)
        }

        val targetCommittedMarker = checkNotNull(committedMarker)

        check(!Thread.holdsLock(DirectBootStorageLock.lock)) {
            "CRITICAL: DirectBootStorageLock must NOT be held during OS operations"
        }

        // 2. Settle cancellingTarget outside lock
        var cancelOldResult: ExactCancellationResult? = null
        if (oldToCancel != null) {
            cancelOldResult = osBoundary.cancelExactAlarm(oldToCancel)
            if (!cancelOldResult.isSuccessfulOrAbsent) {
                val failureReason = mapCancellationFailureReason(cancelOldResult)
                val commitFailed = commitFailureMarker(
                    committedMarker = targetCommittedMarker,
                    cancellingTarget = oldToCancel,
                    registeringTarget = oldToArm,
                    reason = failureReason,
                    retryCount = targetCommittedMarker.retryCount,
                    bootProvenance = currentBoot
                )
                return if (commitFailed) {
                    ReconcilerResult.Unknown("Terminal failure marker commit failed after recovery cancellation failure")
                } else {
                    ReconcilerResult.OsOperationFailed(
                        plan = DirectBootRegistrationPlan.CancelCurrent(oldToCancel, DirectBootCancelReason.QUEUE_EMPTY),
                        cancellationResult = cancelOldResult,
                        registrationResult = null,
                        reason = "Recovery cancellation failed or uncertain: $cancelOldResult"
                    )
                }
            }
        }

        // 3. Settle registeringTarget outside lock (cancelling it to guarantee clean slate)
        if (oldToArm != null && oldToArm != oldToCancel) {
            val cancelArmResult = osBoundary.cancelExactAlarm(oldToArm)
            if (!cancelArmResult.isSuccessfulOrAbsent) {
                val failureReason = mapCancellationFailureReason(cancelArmResult)
                val commitFailed = commitFailureMarker(
                    committedMarker = targetCommittedMarker,
                    cancellingTarget = null,
                    registeringTarget = oldToArm,
                    reason = failureReason,
                    retryCount = targetCommittedMarker.retryCount,
                    bootProvenance = currentBoot
                )
                return if (commitFailed) {
                    ReconcilerResult.Unknown("Terminal failure marker commit failed after recovery registration target cancellation failure")
                } else {
                    ReconcilerResult.OsOperationFailed(
                        plan = DirectBootRegistrationPlan.CancelCurrent(oldToArm, DirectBootCancelReason.QUEUE_EMPTY),
                        cancellationResult = cancelArmResult,
                        registrationResult = null,
                        reason = "Recovery cancellation of registering target failed or uncertain: $cancelArmResult"
                    )
                }
            }
        }

        // 4. Both targets settled in AlarmManager. Proceed to re-read Queue and plan fresh!
        return proceedToPlanFresh(targetCommittedMarker, currentBoot, now, cancelOldResult)
    }

    private fun proceedToPlanFresh(
        committedMarker: DirectBootRegistrationMarker,
        currentBoot: BootProvenance,
        now: Long,
        settledCancelResult: ExactCancellationResult? = null
    ): ReconcilerResult {
        var intermediateMarker = committedMarker
        val freshPlan: DirectBootRegistrationPlan
        val freshNewToArm: DirectBootV2NormalIntentIdentity?
        val freshQueueState: QueueStorageState

        synchronized(DirectBootStorageLock.lock) {
            freshQueueState = DirectBootAlarmCache.inspectQueueStorageState(context)
            if (freshQueueState is QueueStorageState.Corrupt || freshQueueState is QueueStorageState.UnsupportedSchema) {
                val failureReason = if (freshQueueState is QueueStorageState.UnsupportedSchema) {
                    RegistrationFailureReason.QUEUE_UNSUPPORTED
                } else {
                    RegistrationFailureReason.QUEUE_CORRUPT
                }
                val commitFailed = commitFailureMarker(
                    committedMarker = intermediateMarker,
                    cancellingTarget = null,
                    registeringTarget = null,
                    reason = failureReason,
                    retryCount = intermediateMarker.retryCount,
                    bootProvenance = currentBoot
                )
                return if (commitFailed) {
                    ReconcilerResult.Unknown("Terminal failure marker commit failed after recovery queue corruption")
                } else {
                    ReconcilerResult.StorageUnverifiable("Queue storage became unverifiable during recovery: $freshQueueState")
                }
            }

            // Both targets settled in OS; currentlyArmedIdentity is strictly null
            freshPlan = DirectBootRegistrationPlanner.plan(
                queueState = freshQueueState,
                nowEpochMs = now,
                currentlyArmedIdentity = null
            )

            freshNewToArm = extractNewIdentity(freshPlan)

            if (freshNewToArm == null) {
                // Queue is empty or NoOp -> commit IDLE with activeIdentity = null
                val terminalCandidate = intermediateMarker.copy(
                    mainState = RegistrationMainState.IDLE,
                    phase = RegistrationPhase.NONE,
                    failureReason = RegistrationFailureReason.NONE,
                    retryCount = 0,
                    leaseOwner = "",
                    leaseEpochMs = 0L,
                    bootProvenance = currentBoot,
                    activeIdentity = null,
                    cancellingTarget = null,
                    registeringTarget = null,
                    updatedAtEpochMs = clock()
                )
                val terminalWriteResult = DirectBootRegistrationMarker.writeInternal(
                    context = context,
                    marker = terminalCandidate,
                    expectedOpSeq = intermediateMarker.opSeq,
                    editorCommitter = editorCommitter
                )
                return if (terminalWriteResult !is MarkerWriteResult.Success) {
                    ReconcilerResult.Unknown("Terminal IDLE marker commit failed during recovery: $terminalWriteResult")
                } else {
                    ReconcilerResult.StableSuccess(
                        plan = freshPlan,
                        cancellationResult = settledCancelResult,
                        registrationResult = null
                    )
                }
            }

            // We have a fresh alarm to register! Persist REGISTERING_NEW checkpoint under lock
            val candidateRegisterPhase = intermediateMarker.copy(
                mainState = RegistrationMainState.IN_FLIGHT,
                phase = RegistrationPhase.REGISTERING_NEW,
                failureReason = RegistrationFailureReason.NONE,
                leaseOwner = "pid:${Process.myPid()}:reconciler",
                leaseEpochMs = now + DEFAULT_LEASE_DURATION_MS,
                cancellingTarget = null,
                registeringTarget = freshNewToArm,
                updatedAtEpochMs = clock()
            )
            val phaseWriteResult = DirectBootRegistrationMarker.writeInternal(
                context = context,
                marker = candidateRegisterPhase,
                expectedOpSeq = intermediateMarker.opSeq,
                editorCommitter = editorCommitter
            )
            intermediateMarker = when (phaseWriteResult) {
                is MarkerWriteResult.Success -> phaseWriteResult.marker
                else -> return ReconcilerResult.IntentPersistenceFailed(phaseWriteResult)
            }
        } // Lock RELEASED!

        // Register outside lock and commit terminal IDLE
        return proceedToRegisterAndCommit(
            committedMarker = intermediateMarker,
            newToArm = checkNotNull(freshNewToArm),
            cancelResult = settledCancelResult,
            plan = freshPlan,
            initialQueueState = freshQueueState,
            currentBoot = currentBoot
        )
    }

    private fun proceedToRegisterAndCommit(
        committedMarker: DirectBootRegistrationMarker,
        newToArm: DirectBootV2NormalIntentIdentity,
        cancelResult: ExactCancellationResult?,
        plan: DirectBootRegistrationPlan,
        initialQueueState: QueueStorageState,
        currentBoot: BootProvenance
    ): ReconcilerResult {
        check(!Thread.holdsLock(DirectBootStorageLock.lock)) {
            "CRITICAL: DirectBootStorageLock must NOT be held during OS operations"
        }

        val registerResult = osBoundary.registerExactAlarm(newToArm)
        if (!registerResult.isAccepted) {
            val failureReason = mapRegistrationFailureReason(registerResult)
            val commitFailed = commitFailureMarker(
                committedMarker = committedMarker,
                cancellingTarget = null,
                registeringTarget = newToArm,
                reason = failureReason,
                retryCount = committedMarker.retryCount,
                bootProvenance = currentBoot
            )
            return if (commitFailed) {
                ReconcilerResult.Unknown("Terminal failure marker commit failed after registration failure")
            } else {
                ReconcilerResult.OsOperationFailed(
                    plan = plan,
                    cancellationResult = cancelResult,
                    registrationResult = registerResult,
                    reason = "Registration failed or uncertain: $registerResult"
                )
            }
        }

        return synchronized(DirectBootStorageLock.lock) {
            val finalQueueState = DirectBootAlarmCache.inspectQueueStorageState(context)
            if (finalQueueState is QueueStorageState.Corrupt || finalQueueState is QueueStorageState.UnsupportedSchema) {
                val failureReason = if (finalQueueState is QueueStorageState.UnsupportedSchema) {
                    RegistrationFailureReason.QUEUE_UNSUPPORTED
                } else {
                    RegistrationFailureReason.QUEUE_CORRUPT
                }
                val commitFailed = commitFailureMarker(
                    committedMarker = committedMarker,
                    cancellingTarget = null,
                    registeringTarget = newToArm,
                    reason = failureReason,
                    retryCount = committedMarker.retryCount,
                    bootProvenance = currentBoot
                )
                return if (commitFailed) {
                    ReconcilerResult.Unknown("Terminal failure marker commit failed after post-registration queue corruption")
                } else {
                    ReconcilerResult.StorageUnverifiable("Queue storage became unverifiable after registration: $finalQueueState")
                }
            }

            val finalHead = extractQueueHead(finalQueueState)
            val initialHead = extractQueueHead(initialQueueState)

            if (finalHead != initialHead) {
                // Queue drifted during registration: do NOT commit IDLE; preserve target in FAILED_BLOCKED!
                val commitFailed = commitFailureMarker(
                    committedMarker = committedMarker,
                    cancellingTarget = null,
                    registeringTarget = newToArm,
                    reason = RegistrationFailureReason.OS_UNCERTAIN,
                    retryCount = committedMarker.retryCount,
                    bootProvenance = currentBoot
                )
                return if (commitFailed) {
                    ReconcilerResult.Unknown("Terminal failure marker commit failed after post-registration queue drift")
                } else {
                    ReconcilerResult.HeadChangedDuringOperation(
                        plan = plan,
                        initialHead = initialHead,
                        postOpHead = finalHead
                    )
                }
            }

            val terminalCandidate = committedMarker.copy(
                mainState = RegistrationMainState.IDLE,
                phase = RegistrationPhase.NONE,
                failureReason = RegistrationFailureReason.NONE,
                retryCount = 0,
                leaseOwner = "",
                leaseEpochMs = 0L,
                bootProvenance = currentBoot,
                activeIdentity = newToArm,
                cancellingTarget = null,
                registeringTarget = null,
                updatedAtEpochMs = clock()
            )

            val terminalWriteResult = DirectBootRegistrationMarker.writeInternal(
                context = context,
                marker = terminalCandidate,
                expectedOpSeq = committedMarker.opSeq,
                editorCommitter = editorCommitter
            )

            if (terminalWriteResult !is MarkerWriteResult.Success) {
                ReconcilerResult.Unknown("Terminal IDLE marker commit failed: $terminalWriteResult")
            } else {
                ReconcilerResult.StableSuccess(
                    plan = plan,
                    cancellationResult = cancelResult,
                    registrationResult = registerResult
                )
            }
        }
    }

    private fun commitFailureMarker(
        committedMarker: DirectBootRegistrationMarker,
        cancellingTarget: DirectBootV2NormalIntentIdentity?,
        registeringTarget: DirectBootV2NormalIntentIdentity?,
        reason: RegistrationFailureReason,
        retryCount: Int = committedMarker.retryCount,
        bootProvenance: BootProvenance = committedMarker.bootProvenance
    ): Boolean {
        return synchronized(DirectBootStorageLock.lock) {
            val safeCancelling = if (cancellingTarget != null && registeringTarget != null && cancellingTarget == registeringTarget) {
                null
            } else {
                cancellingTarget
            }

            val failureCandidate = committedMarker.copy(
                mainState = RegistrationMainState.FAILED_BLOCKED,
                phase = RegistrationPhase.NONE,
                failureReason = reason,
                retryCount = retryCount,
                leaseOwner = "",
                leaseEpochMs = 0L,
                bootProvenance = bootProvenance,
                activeIdentity = null,
                cancellingTarget = safeCancelling,
                registeringTarget = registeringTarget,
                updatedAtEpochMs = clock()
            )

            val writeResult = DirectBootRegistrationMarker.writeInternal(
                context = context,
                marker = failureCandidate,
                expectedOpSeq = committedMarker.opSeq,
                editorCommitter = editorCommitter
            )
            writeResult !is MarkerWriteResult.Success
        }
    }

    private fun currentBootProvenance(): BootProvenance {
        return bootProvenanceProvider?.invoke() ?: defaultBootProvenance()
    }

    private fun defaultBootProvenance(): BootProvenance {
        val bootCount = try {
            android.provider.Settings.Global.getInt(
                context.contentResolver,
                android.provider.Settings.Global.BOOT_COUNT
            )
        } catch (_: Throwable) {
            null
        }
        val elapsed = android.os.SystemClock.elapsedRealtime()
        val bootEpoch = System.currentTimeMillis() - elapsed
        return BootProvenance(
            bootCount = bootCount,
            lastElapsedRealtime = elapsed,
            bootEpochEstimateMs = bootEpoch
        )
    }

    private fun isRebootDetected(markerBoot: BootProvenance, currentBoot: BootProvenance): Boolean {
        if (markerBoot.bootCount != null && currentBoot.bootCount != null) {
            if (currentBoot.bootCount > markerBoot.bootCount) return true
            if (currentBoot.bootCount < markerBoot.bootCount) return true
        }
        if (markerBoot.lastElapsedRealtime != null && currentBoot.lastElapsedRealtime != null) {
            if (currentBoot.lastElapsedRealtime < markerBoot.lastElapsedRealtime) return true
        }
        if (markerBoot.bootEpochEstimateMs != null && currentBoot.bootEpochEstimateMs != null) {
            val diff = Math.abs(currentBoot.bootEpochEstimateMs - markerBoot.bootEpochEstimateMs)
            if (diff > 30_000L) return true
        }
        return false
    }

    private fun isBootProvenanceUnverified(markerBoot: BootProvenance, currentBoot: BootProvenance): Boolean {
        if (markerBoot.isUnknown || currentBoot.isUnknown) return true
        return isRebootDetected(markerBoot, currentBoot)
    }

    private fun matchesIdentity(item: DirectBootQueueItem, identity: DirectBootV2NormalIntentIdentity): Boolean {
        return item.alarmId == identity.alarmId &&
            item.generation == identity.generation &&
            item.instanceToken == identity.instanceToken &&
            item.triggerTime == identity.triggerTime
    }

    private fun extractOldIdentity(plan: DirectBootRegistrationPlan): DirectBootV2NormalIntentIdentity? {
        return when (plan) {
            is DirectBootRegistrationPlan.ArmNormal -> plan.oldIdentityToCancel
            is DirectBootRegistrationPlan.CancelCurrent -> plan.oldIdentityToCancel
            is DirectBootRegistrationPlan.BlockedByQuickHead -> plan.oldIdentityToCancel
            is DirectBootRegistrationPlan.BlockedOverdueNeedsCanonicalPolicy -> plan.oldIdentityToCancel
            is DirectBootRegistrationPlan.InvalidNormalHead -> plan.oldIdentityToCancel
            else -> null
        }
    }

    private fun extractNewIdentity(plan: DirectBootRegistrationPlan): DirectBootV2NormalIntentIdentity? {
        return when (plan) {
            is DirectBootRegistrationPlan.ArmNormal -> plan.newIdentity
            else -> null
        }
    }

    private fun extractQueueHead(state: QueueStorageState): DirectBootQueueItem? {
        return when (state) {
            is QueueStorageState.ValidWithItems -> state.items.firstOrNull()
            else -> null
        }
    }

    private fun mapCancellationFailureReason(result: ExactCancellationResult): RegistrationFailureReason {
        return when (result) {
            is ExactCancellationResult.StateUncertain -> RegistrationFailureReason.OS_UNCERTAIN
            is ExactCancellationResult.CancellationFailure -> RegistrationFailureReason.IPC_EXCEPTION
            else -> RegistrationFailureReason.OS_UNCERTAIN
        }
    }

    private fun mapRegistrationFailureReason(result: ExactRegistrationResult): RegistrationFailureReason {
        return when (result) {
            is ExactRegistrationResult.PermissionUnavailable -> RegistrationFailureReason.PERMISSION_DENIED
            is ExactRegistrationResult.SecurityError -> RegistrationFailureReason.SECURITY_EXCEPTION
            is ExactRegistrationResult.RegistrationException -> RegistrationFailureReason.IPC_EXCEPTION
            is ExactRegistrationResult.StateUncertain -> RegistrationFailureReason.OS_UNCERTAIN
            else -> RegistrationFailureReason.OS_UNCERTAIN
        }
    }

    /**
     * Whether an owner is currently executing reconciliation passes.
     */
    val isOwnerRunning: Boolean get() = singleFlight.isOwnerRunning

    /**
     * Whether a recheck signal is currently pending for the active owner or next run.
     */
    val hasPendingRecheckSignal: Boolean get() = singleFlight.hasPendingRecheckSignal

    /**
     * Cumulative count of distinct owner execution sessions initiated.
     */
    val totalExecutionRuns: Int get() = singleFlight.totalExecutionRuns

    /**
     * Cumulative count of individual effect passes executed across all runs.
     */
    val totalPassesExecuted: Int get() = singleFlight.totalPassesExecuted
}
