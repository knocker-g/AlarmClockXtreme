package com.sysadmindoc.alarmclock.service

internal data class PlaybackAttemptToken(
    val generation: AlarmGeneration,
    val attemptSequence: Long
)

internal object AlarmServiceCallbackPolicy {

    private var sequenceCounter: Long = 0L
    private var activeAttemptToken: PlaybackAttemptToken? = null
    private var activeFallbackClaimToken: PlaybackAttemptToken? = null

    /**
     * Resets policy state for unit testing or service reset.
     */
    @Synchronized
    fun resetState() {
        sequenceCounter = 0L
        activeAttemptToken = null
        activeFallbackClaimToken = null
    }

    /**
     * Mints the next attempt token and sets it as the currently active attempt.
     * Atomically clears any pending fallback claim.
     */
    @Synchronized
    fun mintNextToken(generation: AlarmGeneration): PlaybackAttemptToken {
        sequenceCounter++
        val newToken = PlaybackAttemptToken(generation, sequenceCounter)
        activeAttemptToken = newToken
        activeFallbackClaimToken = null
        return newToken
    }

    /**
     * Invalidates all attempt tokens (e.g. on Snooze, Dismiss, or Service teardown).
     */
    @Synchronized
    fun invalidateTokens() {
        activeAttemptToken = null
        activeFallbackClaimToken = null
    }

    /**
     * Returns the currently active playback attempt token.
     */
    @Synchronized
    fun activeAttemptToken(): PlaybackAttemptToken? = activeAttemptToken

    /**
     * Returns the currently active fallback claim token.
     */
    @Synchronized
    fun activeFallbackClaimToken(): PlaybackAttemptToken? = activeFallbackClaimToken

    /**
     * Determines whether an asynchronous callback (Media3 onReady/onEnded/onError,
     * MediaPlayer onPrepared/onCompletion/onError, Backup Sound, Delayed Vibration)
     * is authorized to execute non-local side effects on current service state.
     *
     * Requires:
     * 1. Current firing generation matches callback token generation 100%.
     * 2. activeAttemptToken matches callback attempt token.
     * 3. If a callbackPlayback reference is provided, currentPlayback must match
     *    that exact player instance.
     */
    @Synchronized
    fun shouldProcessCallback(
        currentGen: AlarmGeneration?,
        callbackToken: PlaybackAttemptToken,
        currentPlayback: AlarmPlaybackPlayer? = null,
        callbackPlayback: AlarmPlaybackPlayer? = null
    ): Boolean {
        if (currentGen == null || !currentGen.matches(callbackToken.generation)) {
            return false
        }
        if (activeAttemptToken != callbackToken) {
            return false
        }
        if (callbackPlayback != null && currentPlayback !== callbackPlayback) {
            return false
        }
        return true
    }

    /**
     * Atomically claims fallback handoff authority upon player or watchdog failure.
     * Succeeds ONLY IF:
     * 1. currentGen matches failingToken.generation 100%.
     * 2. activeAttemptToken == failingToken.
     * 3. activeFallbackClaimToken == null (one-shot claim).
     *
     * Sets activeFallbackClaimToken = failingToken and returns failingToken if successful;
     * returns null if denied.
     */
    @Synchronized
    fun tryClaimFallbackHandoff(
        currentGen: AlarmGeneration?,
        failingToken: PlaybackAttemptToken
    ): PlaybackAttemptToken? {
        if (currentGen == null || !currentGen.matches(failingToken.generation)) {
            return null
        }
        if (activeAttemptToken != failingToken) {
            return null
        }
        if (activeFallbackClaimToken != null) {
            return null // Already claimed
        }
        activeFallbackClaimToken = failingToken
        return failingToken
    }

    /**
     * Atomically validates AND consumes a fallback handoff claim.
     * Succeeds ONLY IF:
     * 1. currentGen matches handoffToken.generation 100%.
     * 2. activeFallbackClaimToken == handoffToken.
     *
     * Clears activeFallbackClaimToken = null upon consumption and returns true;
     * returns false if denied or superseded.
     */
    @Synchronized
    fun tryConsumeFallbackHandoff(
        currentGen: AlarmGeneration?,
        handoffToken: PlaybackAttemptToken
    ): Boolean {
        if (currentGen == null || !currentGen.matches(handoffToken.generation)) {
            return false
        }
        if (activeFallbackClaimToken != handoffToken) {
            return false
        }
        activeFallbackClaimToken = null
        return true
    }

    /**
     * Determines whether a callback or exception handler is authorized
     * to clean up or release the global `alarmPlayback` variable.
     *
     * Requires:
     * 1. currentGen matches callbackToken.generation 100%.
     * 2. activeAttemptToken == callbackToken.
     * 3. targetPlayback != null && currentPlayback === targetPlayback.
     */
    @Synchronized
    fun shouldReleaseGlobalPlayback(
        currentGen: AlarmGeneration?,
        callbackToken: PlaybackAttemptToken,
        currentPlayback: AlarmPlaybackPlayer?,
        targetPlayback: AlarmPlaybackPlayer?
    ): Boolean {
        if (currentGen == null || !currentGen.matches(callbackToken.generation)) {
            return false
        }
        if (activeAttemptToken != callbackToken) {
            return false
        }
        if (targetPlayback == null || currentPlayback !== targetPlayback) {
            return false
        }
        return true
    }
}
