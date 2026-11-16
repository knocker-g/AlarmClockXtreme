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
 * 4. Preempted handle is returned to caller so the caller can synchronously stop audio/vibration outside arbiterLock.
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
     * Returns Pair(acquired: Boolean, preempted: ActiveOutputHandle?).
     */
    fun acquirePending(
        key: OutputOccurrenceKey,
        sink: OutputSinkType,
        activeNonce: String
    ): Pair<Boolean, ActiveOutputHandle?> = arbiterLock.withLock {
        if (activeNonce.isBlank()) return@withLock false to null

        val current = activeHandle
        if (current != null) {
            if (current.key == key) {
                // Same occurrence
                return@withLock if (current.activeNonce == activeNonce) {
                    true to null // Idempotent reentrancy
                } else {
                    false to null // Stale nonce for same occurrence
                }
            }
            // Preemption: previous handle is preempted
            preemptedHandles.add(current)
            activeHandle = null
        }

        val newHandle = ActiveOutputHandle(
            key = key,
            sink = sink,
            activeNonce = activeNonce
        )
        activeHandle = newHandle
        true to current
    }

    fun confirmObserved(key: OutputOccurrenceKey, activeNonce: String): Boolean = arbiterLock.withLock {
        val current = activeHandle ?: return@withLock false
        if (current.key != key || current.activeNonce != activeNonce) return@withLock false
        true
    }

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
}
