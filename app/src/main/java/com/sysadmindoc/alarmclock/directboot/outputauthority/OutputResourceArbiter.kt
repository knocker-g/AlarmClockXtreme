package com.sysadmindoc.alarmclock.directboot.outputauthority

import com.sysadmindoc.alarmclock.directboot.OutputOccurrenceKey
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

enum class OutputSinkType {
    ALARM_SERVICE_1001,
    DIRECT_BOOT_SERVICE_1011,
    FULLSCREEN_FALLBACK_1001
}

data class ActiveOutputHandle(
    val key: OutputOccurrenceKey,
    val sink: OutputSinkType,
    val activeNonce: String,
    val acquiredAt: Long = System.currentTimeMillis()
)

/**
 * In-process thread-safe arbiter coordinating single-owner logical alarm output.
 *
 * Invariants:
 * 1. Single logical output owner at any instant.
 * 2. Zero locks held during external callbacks or OS binder invocations.
 * 3. Lock inversion avoidance: arbiterLock is never held while acquiring DE StorageLock or Room transactions.
 * 4. Preemption stop confirmation contract:
 *    - Preceding owner is identified.
 *    - Physical stopping operation is strictly executed outside arbiterLock.
 *    - Stale handle stopping requests are rejected fail-closed.
 *    - Until stopping of the preceding owner is confirmed, the new owner is never authorized.
 */
class OutputResourceArbiter {
    private val arbiterLock = ReentrantLock()
    var activeHandle: ActiveOutputHandle? = null
        private set
    val preemptedHandles = mutableListOf<ActiveOutputHandle>()

    fun clearForTesting() = arbiterLock.withLock {
        activeHandle = null
        preemptedHandles.clear()
    }

    /**
     * Attempts to acquire pending ownership.
     * If a preceding active owner exists for a different occurrence, preemption stopping
     * must be confirmed via [stopPreemptedOwner] outside [arbiterLock] before the new owner is authorized.
     *
     * Returns Pair(acquired: Boolean, preempted: ActiveOutputHandle?).
     */
    fun acquirePending(
        key: OutputOccurrenceKey,
        sink: OutputSinkType,
        activeNonce: String,
        stopPreemptedOwner: ((ActiveOutputHandle) -> Boolean)? = null
    ): Pair<Boolean, ActiveOutputHandle?> {
        if (activeNonce.isBlank()) return false to null

        val newHandle = ActiveOutputHandle(
            key = key,
            sink = sink,
            activeNonce = activeNonce
        )

        var priorToStop: ActiveOutputHandle? = null
        arbiterLock.withLock {
            val current = activeHandle
            if (current != null) {
                if (current.key == key) {
                    return if (current.activeNonce == activeNonce && current.sink == sink) {
                        true to null // Idempotent reentrancy
                    } else {
                        false to null // Stale nonce or cross-sink conflict for same occurrence
                    }
                }
                // Preemption required
                priorToStop = current
            } else {
                activeHandle = newHandle
                return true to null
            }
        }

        // Preemption stop must be performed OUTSIDE arbiterLock!
        val target = priorToStop ?: return false to null
        if (stopPreemptedOwner == null) {
            // Unconfirmed preemption stop is prohibited
            return false to null
        }

        val stopConfirmed = try {
            stopPreemptedOwner(target)
        } catch (_: Throwable) {
            false
        }

        if (!stopConfirmed) {
            // New owner must NEVER be authorized if preceding output stop is not confirmed
            return false to null
        }

        // Under lock, finalize preemption switch verifying handle wasn't modified concurrently
        return arbiterLock.withLock {
            val current = activeHandle
            if (current != null && (current.key != target.key || current.activeNonce != target.activeNonce)) {
                // Target handle became stale or was preempted by another caller during stop
                return@withLock false to null
            }
            if (current != null) {
                preemptedHandles.add(current)
            }
            activeHandle = newHandle
            true to target
        }
    }

    /**
     * Confirms that physical output has been observed for the given key and nonce.
     */
    fun confirmObserved(key: OutputOccurrenceKey, activeNonce: String): Boolean = arbiterLock.withLock {
        val current = activeHandle ?: return@withLock false
        if (current.key != key || current.activeNonce != activeNonce) return@withLock false
        true
    }

    /**
     * Releases active handle if key and nonce match.
     */
    fun releaseIfMatches(key: OutputOccurrenceKey, activeNonce: String): Boolean = arbiterLock.withLock {
        val current = activeHandle ?: return@withLock true
        if (current.key == key) {
            if (current.activeNonce != activeNonce) {
                return@withLock false
            }
            activeHandle = null
        }
        true
    }

    /**
     * Stops the given handle if it matches the current active handle.
     * Stale handle stop requests (key or activeNonce mismatch) are strictly rejected fail-closed.
     */
    fun requestStop(handle: ActiveOutputHandle): Boolean = arbiterLock.withLock {
        val current = activeHandle ?: return@withLock false
        if (current.key != handle.key || current.activeNonce != handle.activeNonce) {
            return@withLock false // Stale handle rejected
        }
        preemptedHandles.add(current)
        activeHandle = null
        true
    }
}
