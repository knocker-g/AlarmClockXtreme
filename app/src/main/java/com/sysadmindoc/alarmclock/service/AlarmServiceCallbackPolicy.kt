package com.sysadmindoc.alarmclock.service

internal data class PlaybackAttemptToken(
    val generation: AlarmGeneration,
    val attemptSequence: Long
)

internal object AlarmServiceCallbackPolicy {

    /**
     * Determines whether an asynchronous callback (Media3 onReady/onEnded/onError,
     * MediaPlayer onPrepared/onCompletion/onError, Backup Sound, Delayed Vibration)
     * is authorized to execute non-local side effects on current service state.
     *
     * Requires:
     * 1. Current firing generation matches callback token generation 100%.
     * 2. activeToken matches callback attempt token.
     * 3. If a callbackPlayback reference is provided, currentPlayback must match
     *    that exact player instance.
     */
    fun shouldProcessCallback(
        currentGen: AlarmGeneration?,
        activeToken: PlaybackAttemptToken?,
        callbackToken: PlaybackAttemptToken,
        currentPlayback: AlarmPlaybackPlayer? = null,
        callbackPlayback: AlarmPlaybackPlayer? = null
    ): Boolean {
        if (currentGen == null || !currentGen.matches(callbackToken.generation)) {
            return false
        }
        if (activeToken != callbackToken) {
            return false
        }
        if (callbackPlayback != null && currentPlayback !== callbackPlayback) {
            return false
        }
        return true
    }

    /**
     * Claims fallback handoff authority upon player failure.
     * Succeeds ONLY IF current generation matches failingToken.generation AND activeToken == failingToken.
     * Returns the one-shot claim token if successful, or null if denied.
     */
    fun claimFallbackHandoff(
        currentGen: AlarmGeneration?,
        activeToken: PlaybackAttemptToken?,
        currentClaimToken: PlaybackAttemptToken?,
        failingToken: PlaybackAttemptToken
    ): PlaybackAttemptToken? {
        if (currentGen == null || !currentGen.matches(failingToken.generation)) {
            return null
        }
        if (activeToken != failingToken) {
            return null
        }
        if (currentClaimToken != null) {
            return null // Already claimed
        }
        return failingToken
    }

    /**
     * Validates whether a fallback execution is authorized to proceed.
     * Requires:
     * 1. Current generation matches handoffToken.generation.
     * 2. activeClaimToken matches handoffToken.
     */
    fun shouldExecuteFallback(
        currentGen: AlarmGeneration?,
        activeClaimToken: PlaybackAttemptToken?,
        handoffToken: PlaybackAttemptToken
    ): Boolean {
        if (currentGen == null || !currentGen.matches(handoffToken.generation)) {
            return false
        }
        if (activeClaimToken != handoffToken) {
            return false
        }
        return true
    }

    /**
     * Determines whether a stale callback or failure handler is authorized
     * to clean up or release the global `alarmPlayback` variable.
     */
    fun shouldReleaseGlobalPlayback(
        currentGen: AlarmGeneration?,
        callbackToken: PlaybackAttemptToken,
        currentPlayback: AlarmPlaybackPlayer?,
        targetPlayback: AlarmPlaybackPlayer?
    ): Boolean {
        if (currentGen == null || !currentGen.matches(callbackToken.generation)) {
            return false
        }
        if (targetPlayback == null || currentPlayback !== targetPlayback) {
            return false
        }
        return true
    }
}
