package com.sysadmindoc.alarmclock.directboot

import androidx.annotation.VisibleForTesting
import java.util.concurrent.atomic.AtomicInteger

/**
 * Request descriptor for Direct Boot V2 registration single-flight execution.
 *
 * @param requestId Unique identifier for the request (e.g. alarmId, queue event ID, or UUID).
 * @param identityDigest Optional D3-A occurrence digest or intent token for identity-aware deduplication.
 * @param reason Human-readable or typed reason triggering registration re-evaluation.
 * @param timestampEpochMs Timestamp when request was initiated.
 */
data class RegistrationRequest(
    val requestId: String,
    val identityDigest: String? = null,
    val reason: String = "",
    val timestampEpochMs: Long = System.currentTimeMillis()
) {
    init {
        require(requestId.isNotBlank()) { "requestId must not be blank" }
        require(timestampEpochMs >= 0L) { "timestampEpochMs must be non-negative: $timestampEpochMs" }
    }
}

/**
 * Context provided to each single-flight effect pass.
 *
 * @param passNumber 1-based sequential pass number within the current single-flight run.
 * @param initialRequest The request that initiated this single-flight execution.
 * @param coalescedRequests Bounded sample of requests that arrived while the previous pass was in flight.
 * @param totalCoalescedCount Total number of requests coalesced into this pass, including any exceeding sample capacity.
 */
data class EffectPassContext(
    val passNumber: Int,
    val initialRequest: RegistrationRequest,
    val coalescedRequests: List<RegistrationRequest>,
    val totalCoalescedCount: Int = coalescedRequests.size
) {
    init {
        require(passNumber >= 1) { "passNumber must be >= 1: $passNumber" }
        require(totalCoalescedCount >= 0) { "totalCoalescedCount must be non-negative: $totalCoalescedCount" }
    }
}

/**
 * Typed outcome of a single-flight execution attempt.
 */
sealed interface SingleFlightResult<out T> {

    /**
     * Caller acquired execution ownership and drained all pending rechecks within the pass budget.
     *
     * @param totalPasses Total number of effect passes completed in this run (1..maxPassesPerRun).
     * @param lastResult Result of the final effect pass.
     * @param allResults Results of all completed effect passes in chronological order.
     */
    data class Executed<T>(
        val totalPasses: Int,
        val lastResult: T,
        val allResults: List<T> = emptyList()
    ) : SingleFlightResult<T>

    /**
     * An execution owner was already active. The request was coalesced into the
     * pending recheck signal and will be processed in a subsequent pass or run.
     *
     * @param isReentrant True if the request was submitted reentrantly from the active owner thread.
     * @param activeOwnerThreadId Thread ID of the active owner, for diagnostics.
     */
    data class Coalesced(
        val isReentrant: Boolean,
        val activeOwnerThreadId: Long? = null
    ) : SingleFlightResult<Nothing>

    /**
     * An effect pass threw an unhandled exception. Ownership was safely released.
     * Any pending recheck signal survives or is reported so work is never silently lost.
     *
     * @param error The thrown exception.
     * @param passesCompleted Number of passes successfully executed before failure.
     * @param hasUnresolvedRecheck True if a recheck request was pending and remains unresolved.
     * @param pendingRecheckCount Number of pending requests waiting for a subsequent recheck pass.
     */
    data class ExecutionFailed(
        val error: Throwable,
        val passesCompleted: Int,
        val hasUnresolvedRecheck: Boolean,
        val pendingRecheckCount: Int
    ) : SingleFlightResult<Nothing>

    /**
     * The run completed the maximum allowed passes ([maxPassesPerRun]) but further rechecks
     * remain pending.
     * Ownership was released to avoid starvation/infinite looping, and the pending recheck
     * signal was preserved for subsequent executions.
     *
     * @param totalPasses Number of passes completed (equal to maxPassesPerRun).
     * @param lastResult Result of the final executed pass.
     * @param pendingRecheckPreserved True indicating pending rechecks were preserved.
     * @param pendingRecheckCount Number of pending requests waiting for next run.
     * @param allResults Results of all passes executed in this run.
     */
    data class PassBudgetExhausted<T>(
        val totalPasses: Int,
        val lastResult: T,
        val pendingRecheckPreserved: Boolean = true,
        val pendingRecheckCount: Int,
        val allResults: List<T> = emptyList()
    ) : SingleFlightResult<T>
}

/**
 * Process-local single-flight execution owner and request coalescing coordinator for KNO-75 Phase D3-B2-P2-0 / P2-0A.
 *
 * Guarantees & Invariants:
 * 1. Single Execution Owner: Exactly one process-local thread may execute effect passes at any time.
 * 2. Canonical Effect Runner: Fixed effect runner per instance prevents divergent caller expectation violations.
 * 3. Request Coalescing: Concurrent or overlapping requests coalesce into a pending recheck signal;
 *    they never initiate parallel effect executions.
 * 4. Fresh State Evaluation: When an in-flight execution coalesces additional requests, subsequent passes
 *    read fresh authoritative state rather than reusing a stale request snapshot.
 * 5. Reentrancy Immunity: Reentrant requests submitted from within the effect callback on the owner thread
 *    are recognized and coalesced immediately without recursion, stack growth, or deadlocks.
 * 6. Lock Segregation: [DirectBootStorageLock.lock] is NEVER held across the effect callback.
 *    Single-flight state synchronization uses an internal monitor lock exclusively.
 * 7. Fault Resilience & Request Survival: Exceptions during effect passes safely release owner execution
 *    without owner leakage. Any coalesced recheck requests survive the failure and are delivered to the
 *    next authorized execution. No automatic replay of an uncertain OS effect is attempted.
 * 8. Bounded Coalescing & Pass Budget: Memory is bounded by [maxPendingSampleCapacity]. Execution per run is
 *    bounded by [maxPassesPerRun]. Budget exhaustion preserves the recheck requirement and reports
 *    [SingleFlightResult.PassBudgetExhausted] without claiming stable completion.
 * 9. Non-Blocking Non-Owners: Non-owner requests return immediately with [SingleFlightResult.Coalesced]
 *    without spin-loops, sleeping, or blocking on the owner thread.
 *
 * Scope Boundary:
 * - Process-local coordination only. Does NOT claim cross-process exclusion or durable OS delivery guarantees.
 * - Inactive foundation: does not invoke AlarmManager or perform durable storage modifications.
 */
class DirectBootRegistrationSingleFlight<T>(
    private val effectRunner: (EffectPassContext) -> T,
    val maxPassesPerRun: Int = DEFAULT_MAX_PASSES_PER_RUN,
    val maxPendingSampleCapacity: Int = DEFAULT_MAX_PENDING_SAMPLE
) {
    companion object {
        const val DEFAULT_MAX_PASSES_PER_RUN = 5
        const val DEFAULT_MAX_PENDING_SAMPLE = 16
    }

    init {
        require(maxPassesPerRun >= 1) { "maxPassesPerRun must be >= 1: $maxPassesPerRun" }
        require(maxPendingSampleCapacity >= 1) { "maxPendingSampleCapacity must be >= 1: $maxPendingSampleCapacity" }
    }

    private val stateLock = Any()

    // State protected by stateLock:
    private var isOwnerActive = false
    private var activeOwnerThread: Thread? = null
    private var hasPendingRecheck = false
    private var pendingRecheckCounter = 0
    private val pendingRequests = mutableListOf<RegistrationRequest>()
    private var inFlightRequests: List<RegistrationRequest> = emptyList()
    private var inFlightRecheckCounter = 0

    // Observable metrics for deterministic verification:
    private val executionRunCount = AtomicInteger(0)
    private val totalPassesCount = AtomicInteger(0)
    private val coalescedCount = AtomicInteger(0)

    /**
     * Submits a registration request for single-flight execution.
     *
     * If no owner is currently active, the calling thread becomes the owner and executes
     * effect passes until all coalesced rechecks are drained or [maxPassesPerRun] is reached.
     *
     * If an owner is already active (concurrent or reentrant), the request is coalesced into
     * a pending recheck signal, returning [SingleFlightResult.Coalesced] immediately.
     *
     * @param request The registration request descriptor.
     * @return [SingleFlightResult] indicating execution outcome, coalescing, budget exhaustion, or failure.
     */
    fun execute(request: RegistrationRequest): SingleFlightResult<T> {
        val currentThread = Thread.currentThread()

        synchronized(stateLock) {
            if (isOwnerActive) {
                val isReentrant = (activeOwnerThread === currentThread)
                hasPendingRecheck = true
                pendingRecheckCounter++
                coalescedCount.incrementAndGet()

                if (pendingRequests.size < maxPendingSampleCapacity) {
                    pendingRequests.add(request)
                }

                @Suppress("DEPRECATION")
                val ownerThreadId = activeOwnerThread?.id
                return SingleFlightResult.Coalesced(
                    isReentrant = isReentrant,
                    activeOwnerThreadId = ownerThreadId
                )
            }

            // Acquire exclusive owner execution
            isOwnerActive = true
            activeOwnerThread = currentThread
            // Preserves any surviving pending rechecks from a prior failed run
        }

        executionRunCount.incrementAndGet()
        var passNumber = 0
        val results = mutableListOf<T>()
        var currentInitialRequest = request

        try {
            while (passNumber < maxPassesPerRun) {
                passNumber++
                totalPassesCount.incrementAndGet()

                // Stage requests that arrived before or between passes for this in-flight pass
                val coalescedForThisPass: List<RegistrationRequest>
                val coalescedCountForThisPass: Int
                synchronized(stateLock) {
                    inFlightRequests = pendingRequests.toList()
                    inFlightRecheckCounter = pendingRecheckCounter
                    coalescedForThisPass = inFlightRequests
                    coalescedCountForThisPass = inFlightRecheckCounter

                    pendingRequests.clear()
                    pendingRecheckCounter = 0
                    hasPendingRecheck = false
                }

                val context = EffectPassContext(
                    passNumber = passNumber,
                    initialRequest = currentInitialRequest,
                    coalescedRequests = coalescedForThisPass,
                    totalCoalescedCount = coalescedCountForThisPass
                )

                // Enforce lock segregation: DirectBootStorageLock must NEVER be held during effect
                check(!Thread.holdsLock(DirectBootStorageLock.lock)) {
                    "DirectBootStorageLock must NOT be held during single-flight effect execution"
                }

                val passResult = effectRunner(context)
                results.add(passResult)

                // Atomically commit in-flight recheck and inspect recheck signal under stateLock
                val shouldContinue = synchronized(stateLock) {
                    // Pass succeeded: commit staged in-flight recheck as settled
                    commitInFlightRecheckLocked()

                    if (hasPendingRecheck) {
                        if (passNumber >= maxPassesPerRun) {
                            // Budget exhausted: release ownership, but preserve hasPendingRecheck = true
                            isOwnerActive = false
                            activeOwnerThread = null
                            false
                        } else {
                            true
                        }
                    } else {
                        // All pending work drained; cleanly release owner and exit loop
                        isOwnerActive = false
                        activeOwnerThread = null
                        false
                    }
                }

                if (!shouldContinue) {
                    val (stillPending, pendingCount) = synchronized(stateLock) {
                        Pair(hasPendingRecheck, pendingRecheckCounter)
                    }
                    return if (stillPending) {
                        SingleFlightResult.PassBudgetExhausted(
                            totalPasses = passNumber,
                            lastResult = passResult,
                            pendingRecheckPreserved = true,
                            pendingRecheckCount = pendingCount,
                            allResults = results
                        )
                    } else {
                        SingleFlightResult.Executed(
                            totalPasses = passNumber,
                            lastResult = passResult,
                            allResults = results
                        )
                    }
                }
            }

            // Fallback for budget exhaustion at loop boundary
            val remaining = synchronized(stateLock) {
                commitInFlightRecheckLocked()
                isOwnerActive = false
                activeOwnerThread = null
                pendingRecheckCounter
            }
            return SingleFlightResult.PassBudgetExhausted(
                totalPasses = passNumber,
                lastResult = results.last(),
                pendingRecheckPreserved = true,
                pendingRecheckCount = remaining,
                allResults = results
            )
        } catch (t: Throwable) {
            // Exception during effect pass: release owner execution immediately.
            // Restore unsettled in-flight rechecks and merge with any requests that arrived during the pass.
            val (unresolved, pendingCount) = synchronized(stateLock) {
                rollbackInFlightRecheckLocked()
                isOwnerActive = false
                activeOwnerThread = null
                Pair(hasPendingRecheck, pendingRecheckCounter)
            }
            return SingleFlightResult.ExecutionFailed(
                error = t,
                passesCompleted = results.size,
                hasUnresolvedRecheck = unresolved,
                pendingRecheckCount = pendingCount
            )
        } finally {
            // Fail-safe cleanup to ensure owner is never leaked on unhandled exit
            synchronized(stateLock) {
                if (activeOwnerThread === currentThread) {
                    rollbackInFlightRecheckLocked()
                    isOwnerActive = false
                    activeOwnerThread = null
                }
            }
        }
    }

    private fun commitInFlightRecheckLocked() {
        inFlightRequests = emptyList()
        inFlightRecheckCounter = 0
    }

    private fun rollbackInFlightRecheckLocked() {
        if (inFlightRecheckCounter > 0 || inFlightRequests.isNotEmpty()) {
            val merged = ArrayList<RegistrationRequest>(inFlightRequests.size + pendingRequests.size)
            merged.addAll(inFlightRequests)
            merged.addAll(pendingRequests)

            pendingRequests.clear()
            if (merged.size > maxPendingSampleCapacity) {
                pendingRequests.addAll(merged.subList(0, maxPendingSampleCapacity))
            } else {
                pendingRequests.addAll(merged)
            }

            pendingRecheckCounter += inFlightRecheckCounter
            hasPendingRecheck = true

            inFlightRequests = emptyList()
            inFlightRecheckCounter = 0
        }
    }

    /**
     * Whether an owner is currently executing effect passes.
     */
    val isOwnerRunning: Boolean
        get() = synchronized(stateLock) { isOwnerActive }

    /**
     * Whether a recheck signal is currently pending for the active owner or next run.
     */
    val hasPendingRecheckSignal: Boolean
        get() = synchronized(stateLock) { hasPendingRecheck }

    /**
     * Number of requests currently buffered in sample list for the next pass.
     */
    val pendingRequestCount: Int
        get() = synchronized(stateLock) { pendingRequests.size }

    /**
     * Total number of coalesced requests waiting for recheck (including any beyond sample capacity).
     */
    val totalPendingRecheckCount: Int
        get() = synchronized(stateLock) { pendingRecheckCounter }

    /**
     * Number of requests currently staged in-flight by an active effect pass.
     */
    val inFlightRequestCount: Int
        get() = synchronized(stateLock) { inFlightRequests.size }

    /**
     * Total number of coalesced rechecks currently staged in-flight.
     */
    val totalInFlightRecheckCount: Int
        get() = synchronized(stateLock) { inFlightRecheckCounter }

    /**
     * Cumulative count of distinct owner execution sessions initiated.
     */
    val totalExecutionRuns: Int
        get() = executionRunCount.get()

    /**
     * Cumulative count of individual effect passes executed across all runs.
     */
    val totalPassesExecuted: Int
        get() = totalPassesCount.get()

    /**
     * Cumulative count of requests that coalesced into an active owner.
     */
    val totalRequestsCoalesced: Int
        get() = coalescedCount.get()
}
