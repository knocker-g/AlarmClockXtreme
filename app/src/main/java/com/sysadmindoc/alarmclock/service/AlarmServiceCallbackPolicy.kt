package com.sysadmindoc.alarmclock.service

internal object AlarmServiceCallbackPolicy {

    /**
     * Determines whether an asynchronous callback (Media3 onReady/onEnded/onError,
     * MediaPlayer onPrepared/onCompletion/onError, Backup Sound, Delayed Vibration)
     * is authorized to execute non-local side effects on current service state.
     *
     * Requires:
     * 1. Current firing generation matches callback generation 100%.
     * 2. If a playback player reference is associated with the callback,
     *    the current service alarmPlayback must match that exact player instance (or be null during fallback).
     */
    fun shouldProcessCallback(
        currentGen: AlarmGeneration?,
        callbackGen: AlarmGeneration,
        currentPlayback: AlarmPlaybackPlayer? = null,
        callbackPlayback: AlarmPlaybackPlayer? = null
    ): Boolean {
        if (currentGen == null || !currentGen.matches(callbackGen)) {
            return false
        }
        if (callbackPlayback != null && currentPlayback != null && currentPlayback !== callbackPlayback) {
            return false
        }
        return true
    }

    /**
     * Determines whether a stale callback or failure handler is authorized
     * to clean up or release the global `alarmPlayback` variable.
     *
     * A callback or exception handler may clear `alarmPlayback = null` ONLY IF
     * current generation still matches AND `alarmPlayback` holds that exact non-null player instance.
     * If targetPlayback is null (e.g. player creation threw before assignment),
     * global `alarmPlayback` must NOT be released.
     */
    fun shouldReleaseGlobalPlayback(
        currentGen: AlarmGeneration?,
        callbackGen: AlarmGeneration,
        currentPlayback: AlarmPlaybackPlayer?,
        targetPlayback: AlarmPlaybackPlayer?
    ): Boolean {
        if (currentGen == null || !currentGen.matches(callbackGen)) {
            return false
        }
        if (targetPlayback == null || currentPlayback !== targetPlayback) {
            return false
        }
        return true
    }

    /**
     * Determines whether a watchdog job or failure fallback is authorized
     * to cancel the shared playback watchdog or trigger default fallback audio.
     *
     * Requires:
     * 1. Current generation matches watchdog generation 100%.
     * 2. If a target player reference is provided and currentPlayback is non-null,
     *    currentPlayback must match that exact target player instance.
     */
    fun shouldCancelWatchdog(
        currentGen: AlarmGeneration?,
        watchdogGen: AlarmGeneration,
        currentPlayback: AlarmPlaybackPlayer? = null,
        targetPlayback: AlarmPlaybackPlayer? = null
    ): Boolean {
        if (currentGen == null || !currentGen.matches(watchdogGen)) {
            return false
        }
        if (targetPlayback != null && currentPlayback != null && currentPlayback !== targetPlayback) {
            return false
        }
        return true
    }
}
