package com.sysadmindoc.alarmclock.receiver

import kotlinx.coroutines.sync.withLock

/**
 * v1.5.2: Pure decision function for whether a missed alarm should be
 * replayed when the user returns to the phone. Extracted out of
 * [MissedAlarmUnlockReceiver] so the logic can be unit-tested without
 * needing a real BroadcastReceiver / Hilt / DataStore wiring.
 *
 * The receiver deliberately holds no other policy — it just dispatches
 * side effects based on the result of this function.
 */
object MissedAlarmReplayPolicy {

    const val ACTION_USER_PRESENT = "android.intent.action.USER_PRESENT"
    const val ACTION_POWER_DISCONNECTED = "android.intent.action.ACTION_POWER_DISCONNECTED"
    private const val SOURCE_UNLOCK = "MissedAlarmUnlockReceiver"
    private const val SOURCE_POWER_DISCONNECTED = "MissedAlarmPowerDisconnectReceiver"

    /** Hard ceiling on how recently the miss must have happened for us to
     *  bother replaying it. Past this point the user has likely moved on. */
    const val REPLAY_WINDOW_MS: Long = 10 * 60 * 1000L

    fun isReplayTrigger(action: String?): Boolean =
        action == ACTION_USER_PRESENT || action == ACTION_POWER_DISCONNECTED

    fun sourceForTrigger(action: String?): String = when (action) {
        ACTION_POWER_DISCONNECTED -> SOURCE_POWER_DISCONNECTED
        else -> SOURCE_UNLOCK
    }

    /**
     * @return true iff the caller should fire the replay.
     *
     * Accepts primitive arguments so tests don't need to build a full
     * [android.content.SharedPreferences] state. [nowMs] is injected so
     * tests control the wall clock.
     */
    fun shouldReplay(
        repeatMissedEnabled: Boolean,
        lastMissedAtMs: Long,
        lastMissedId: Long,
        alarmCurrentlyFiringId: Long,
        nowMs: Long
    ): Decision {
        if (!repeatMissedEnabled) return Decision.DROP_DISABLED
        if (lastMissedId <= 0L) return Decision.DROP_NO_RECORD
        if (lastMissedAtMs <= 0L) return Decision.DROP_NO_RECORD

        val age = nowMs - lastMissedAtMs
        // Half-open so the exact-boundary case doesn't straddle two alarms.
        if (age < 0 || age >= REPLAY_WINDOW_MS) return Decision.DROP_EXPIRED

        // Don't stack a replay on top of a live alarm — the foreground
        // service would fight itself for audio focus.
        if (alarmCurrentlyFiringId != -1L) return Decision.DROP_ALARM_LIVE

        return Decision.REPLAY
    }

    private val stateMutex = kotlinx.coroutines.sync.Mutex()

    /**
     * Atomically records a missed alarm marker in SharedPreferences under stateMutex lock,
     * ensuring consistent ordering across concurrent producers and consumers.
     */
    @android.annotation.SuppressLint("ApplySharedPref")
    suspend fun recordMissedAlarmMarker(
        store: android.content.SharedPreferences,
        alarmId: Long,
        missedAtMs: Long,
        fireId: String = ""
    ) = stateMutex.withLock {
        store.edit()
            .putLong("last_missed_at", missedAtMs)
            .putLong("last_missed_id", alarmId)
            .putString("last_missed_fire_id", fireId)
            .commit()
    }

    /**
     * Atomically clears the missed alarm marker if and only if the currently stored
     * last_missed_id and last_missed_at match the expected values.
     * Prevents a stale claimant A from erasing a newly recorded marker B.
     */
    @android.annotation.SuppressLint("ApplySharedPref")
    suspend fun clearMissedAlarmMarkerIfMatching(
        store: android.content.SharedPreferences,
        expectedId: Long,
        expectedAt: Long
    ): Boolean = stateMutex.withLock {
        val currentId = store.getLong("last_missed_id", -1L)
        val currentAt = store.getLong("last_missed_at", 0L)
        if (currentId == expectedId && currentAt == expectedAt) {
            store.edit().clear().commit()
            true
        } else {
            false
        }
    }

    /**
     * Atomically clears the missed alarm marker unconditionally under stateMutex lock.
     * Used on boot or package replace when pre-reboot missed state must be invalidated.
     */
    @android.annotation.SuppressLint("ApplySharedPref")
    suspend fun clearMissedAlarmMarkerUnconditional(
        store: android.content.SharedPreferences
    ) = stateMutex.withLock {
        store.edit().clear().commit()
    }

    /**
     * Atomically records the dispatch attempt timestamp for an in-flight replay.
     */
    @android.annotation.SuppressLint("ApplySharedPref")
    suspend fun recordDispatchTimestamp(
        store: android.content.SharedPreferences,
        dispatchedAtMs: Long = System.currentTimeMillis()
    ) = stateMutex.withLock {
        store.edit().putLong("last_missed_dispatched_at", dispatchedAtMs).commit()
    }

    private val claimMutex = kotlinx.coroutines.sync.Mutex()
    private var activeClaimToken: String? = null
    private var activeClaimExpiresAt: Long = 0L

    /**
     * Attempts to acquire an atomic claim lease for a missed alarm replay attempt.
     * Prevents concurrent receivers (e.g. USER_PRESENT and POWER_DISCONNECTED arriving at the same time)
     * from double-dispatching the same missed occurrence.
     *
     * @return a unique non-null claim token if the lease was acquired, or null if an active lease is held.
     */
    suspend fun tryAcquireClaim(
        nowMs: Long = System.currentTimeMillis(),
        leaseDurationMs: Long = 15_000L
    ): String? = claimMutex.withLock {
        if (activeClaimToken != null && nowMs < activeClaimExpiresAt) {
            return@withLock null
        }
        val newToken = java.util.UUID.randomUUID().toString()
        activeClaimToken = newToken
        activeClaimExpiresAt = nowMs + leaseDurationMs
        newToken
    }

    suspend fun releaseClaim(claimToken: String) = claimMutex.withLock {
        if (activeClaimToken == claimToken) {
            activeClaimToken = null
            activeClaimExpiresAt = 0L
        }
    }

    enum class Decision(val shouldReplay: Boolean, val shouldClearState: Boolean) {
        REPLAY(shouldReplay = true, shouldClearState = false),
        DROP_DISABLED(shouldReplay = false, shouldClearState = true),
        DROP_NO_RECORD(shouldReplay = false, shouldClearState = true),
        DROP_EXPIRED(shouldReplay = false, shouldClearState = true),
        DROP_ALARM_LIVE(shouldReplay = false, shouldClearState = true)
    }
}
