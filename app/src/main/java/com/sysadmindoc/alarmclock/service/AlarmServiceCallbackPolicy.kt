package com.sysadmindoc.alarmclock.service

import android.os.Looper
import java.util.concurrent.atomic.AtomicLong

internal data class PlaybackAttemptToken(
    val generation: AlarmGeneration,
    val attemptSequence: Long,
    val authorityId: Long = 0L
)

internal class AlarmPlaybackAttemptAuthority {

    companion object {
        private val authorityIdGenerator = AtomicLong(0L)
    }

    val authorityId: Long = authorityIdGenerator.incrementAndGet()
    private var sequenceCounter: Long = 0L
    private var activeAttemptToken: PlaybackAttemptToken? = null
    private var activeFallbackClaimToken: PlaybackAttemptToken? = null
    private var activeRoutingAttemptToken: PlaybackAttemptToken? = null

    /**
     * Enforces strict execution on the Android Main Looper.
     * Rejects non-Main Looper threads, plain worker threads (looper == null),
     * and background HandlerThreads.
     */
    private fun checkMainThread() {
        val looper = Looper.myLooper()
        if (looper != Looper.getMainLooper()) {
            throw IllegalStateException(
                "AlarmPlaybackAttemptAuthority state mutation must be executed on Android Main Looper, but was on thread '${Thread.currentThread().name}' (looper=$looper)"
            )
        }
    }

    /**
     * Resets authority state for testing or instance initialization.
     */
    @Synchronized
    fun resetState() {
        checkMainThread()
        sequenceCounter = 0L
        activeAttemptToken = null
        activeFallbackClaimToken = null
        activeRoutingAttemptToken = null
    }

    /**
     * Mints the next attempt token and sets it as the currently active attempt.
     * Atomically clears any pending fallback claim and routing token.
     */
    @Synchronized
    fun mintNextToken(generation: AlarmGeneration): PlaybackAttemptToken {
        checkMainThread()
        sequenceCounter++
        val newToken = PlaybackAttemptToken(generation, sequenceCounter, authorityId)
        activeAttemptToken = newToken
        activeFallbackClaimToken = null
        activeRoutingAttemptToken = null
        return newToken
    }

    /**
     * Invalidates all attempt tokens (e.g. on Snooze, Dismiss, or Service teardown).
     */
    @Synchronized
    fun invalidateTokens() {
        checkMainThread()
        activeAttemptToken = null
        activeFallbackClaimToken = null
        activeRoutingAttemptToken = null
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
     * Binds speaker routing override to an attempt token.
     */
    @Synchronized
    fun setRoutingAttemptToken(token: PlaybackAttemptToken): Boolean {
        checkMainThread()
        if (activeAttemptToken != token) return false
        activeRoutingAttemptToken = token
        return true
    }

    /**
     * Checks if speaker routing override is active for a given attempt token.
     */
    @Synchronized
    fun isRoutingActive(token: PlaybackAttemptToken?): Boolean {
        return token != null && activeAttemptToken == token && activeRoutingAttemptToken == token
    }

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
     * Atomically checks active authority AND executes an action inside the synchronized block.
     */
    @Synchronized
    fun executeIfActive(
        currentGen: AlarmGeneration?,
        token: PlaybackAttemptToken,
        action: () -> Unit
    ): Boolean {
        if (currentGen == null || !currentGen.matches(token.generation)) {
            return false
        }
        if (activeAttemptToken != token) {
            return false
        }
        action()
        return true
    }

    /**
     * Atomically checks active authority AND publishes playback state inside the synchronized block.
     */
    @Synchronized
    fun publishPlaybackIfActive(
        currentGen: AlarmGeneration?,
        token: PlaybackAttemptToken,
        publishAction: () -> Unit
    ): Boolean {
        if (currentGen == null || !currentGen.matches(token.generation)) {
            return false
        }
        if (activeAttemptToken != token) {
            return false
        }
        publishAction()
        return true
    }

    /**
     * Atomically checks active authority AND cancels watchdog inside the synchronized block.
     */
    @Synchronized
    fun cancelWatchdogIfActive(
        currentGen: AlarmGeneration?,
        ownerToken: PlaybackAttemptToken?,
        cancelAction: () -> Unit
    ): Boolean {
        if (ownerToken != null) {
            if (currentGen == null || !currentGen.matches(ownerToken.generation)) {
                return false
            }
            if (activeAttemptToken != ownerToken) {
                return false
            }
        }
        cancelAction()
        return true
    }

    /**
     * Determines whether audio mutations (e.g. system volume overrides) are authorized for token.
     * Does NOT execute Android Binder IPC calls inside the monitor lock!
     */
    @Synchronized
    fun shouldMutateAudio(
        currentGen: AlarmGeneration?,
        token: PlaybackAttemptToken
    ): Boolean {
        if (currentGen == null || !currentGen.matches(token.generation)) {
            return false
        }
        return activeAttemptToken == token
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
        checkMainThread()
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
     * Atomically validates AND consumes a fallback handoff claim,
     * AND mints/activates the new fallback attempt token in a single atomic operation.
     *
     * Succeeds ONLY IF:
     * 1. currentGen matches handoffToken.generation 100%.
     * 2. activeFallbackClaimToken == handoffToken.
     *
     * Clears activeFallbackClaimToken = null and activates new fallback token upon consumption;
     * returns new fallback token if successful, or null if denied or superseded.
     */
    @Synchronized
    fun tryConsumeFallbackHandoffAndActivate(
        currentGen: AlarmGeneration?,
        handoffToken: PlaybackAttemptToken
    ): PlaybackAttemptToken? {
        checkMainThread()
        if (currentGen == null || !currentGen.matches(handoffToken.generation)) {
            return null
        }
        if (activeFallbackClaimToken != handoffToken) {
            return null
        }
        activeFallbackClaimToken = null
        sequenceCounter++
        val fallbackToken = PlaybackAttemptToken(handoffToken.generation, sequenceCounter, authorityId)
        activeAttemptToken = fallbackToken
        return fallbackToken
    }

    /**
     * Helper delegating to tryConsumeFallbackHandoffAndActivate for boolean checks.
     */
    @Synchronized
    fun tryConsumeFallbackHandoff(
        currentGen: AlarmGeneration?,
        handoffToken: PlaybackAttemptToken
    ): Boolean {
        return tryConsumeFallbackHandoffAndActivate(currentGen, handoffToken) != null
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

/**
 * Global decision helpers delegating to global default or instance-bound authority.
 */
internal object AlarmServiceCallbackPolicy {
    val defaultAuthority = AlarmPlaybackAttemptAuthority()

    fun resetState() = defaultAuthority.resetState()

    fun mintNextToken(generation: AlarmGeneration) = defaultAuthority.mintNextToken(generation)

    fun invalidateTokens() = defaultAuthority.invalidateTokens()

    fun activeAttemptToken() = defaultAuthority.activeAttemptToken()

    fun activeFallbackClaimToken() = defaultAuthority.activeFallbackClaimToken()

    fun shouldProcessCallback(
        currentGen: AlarmGeneration?,
        callbackToken: PlaybackAttemptToken,
        currentPlayback: AlarmPlaybackPlayer? = null,
        callbackPlayback: AlarmPlaybackPlayer? = null
    ) = defaultAuthority.shouldProcessCallback(currentGen, callbackToken, currentPlayback, callbackPlayback)

    fun executeIfActive(
        currentGen: AlarmGeneration?,
        token: PlaybackAttemptToken,
        action: () -> Unit
    ) = defaultAuthority.executeIfActive(currentGen, token, action)

    fun publishPlaybackIfActive(
        currentGen: AlarmGeneration?,
        token: PlaybackAttemptToken,
        publishAction: () -> Unit
    ) = defaultAuthority.publishPlaybackIfActive(currentGen, token, publishAction)

    fun cancelWatchdogIfActive(
        currentGen: AlarmGeneration?,
        ownerToken: PlaybackAttemptToken?,
        cancelAction: () -> Unit
    ) = defaultAuthority.cancelWatchdogIfActive(currentGen, ownerToken, cancelAction)

    fun shouldMutateAudio(
        currentGen: AlarmGeneration?,
        token: PlaybackAttemptToken
    ) = defaultAuthority.shouldMutateAudio(currentGen, token)

    fun tryClaimFallbackHandoff(
        currentGen: AlarmGeneration?,
        failingToken: PlaybackAttemptToken
    ) = defaultAuthority.tryClaimFallbackHandoff(currentGen, failingToken)

    fun tryConsumeFallbackHandoff(
        currentGen: AlarmGeneration?,
        handoffToken: PlaybackAttemptToken
    ) = defaultAuthority.tryConsumeFallbackHandoff(currentGen, handoffToken)

    fun tryConsumeFallbackHandoffAndActivate(
        currentGen: AlarmGeneration?,
        handoffToken: PlaybackAttemptToken
    ) = defaultAuthority.tryConsumeFallbackHandoffAndActivate(currentGen, handoffToken)

    fun shouldReleaseGlobalPlayback(
        currentGen: AlarmGeneration?,
        callbackToken: PlaybackAttemptToken,
        currentPlayback: AlarmPlaybackPlayer?,
        targetPlayback: AlarmPlaybackPlayer?
    ) = defaultAuthority.shouldReleaseGlobalPlayback(currentGen, callbackToken, currentPlayback, targetPlayback)
}
