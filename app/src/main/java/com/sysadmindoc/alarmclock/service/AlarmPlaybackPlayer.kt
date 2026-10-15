package com.sysadmindoc.alarmclock.service

import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import androidx.media3.exoplayer.ExoPlayer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

internal interface AlarmPlaybackPlayer {
    fun setVolume(left: Float, right: Float)
    fun durationMs(): Long
    fun seekTo(positionMs: Long)
    fun stopAndRelease()
    fun isReleased(): Boolean = false
}

internal fun randomRingtoneStartOffsetMs(durationMs: Long, randomUnit: Double): Long {
    if (durationMs < 30_000L) return 0L
    val latestStart = (durationMs - 15_000L).coerceAtLeast(0L)
    return (latestStart * randomUnit.coerceIn(0.0, 1.0)).toLong()
}

internal fun alarmPlaybackGain(
    callMuted: Boolean,
    challengeDuckingActive: Boolean,
    challengeDuckPercent: Int,
    rampGain: Float,
    alarmVolume: Int
): Float {
    if (callMuted) return 0f
    val duckGain = if (challengeDuckingActive) {
        challengeDuckPercent.coerceIn(10, 80) / 100f
    } else 1f
    val baseGain = (rampGain.coerceIn(0f, 1f) * duckGain).coerceIn(0f, 1f)
    return baseGain * (alarmVolume.coerceIn(0, 100) / 100f)
}

private object PlaybackMainThread {
    private val handler = Handler(Looper.getMainLooper())

    fun run(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            block()
        } else {
            handler.post(block)
        }
    }

    fun runBlocking(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            block()
            return
        }

        val result = AtomicReference<Result<Unit>>()
        val latch = CountDownLatch(1)
        handler.post {
            result.set(runCatching(block))
            latch.countDown()
        }
        latch.await()
        result.get().getOrThrow()
    }

    fun <T> callBlocking(block: () -> T): T {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            return block()
        }

        val result = AtomicReference<Result<T>>()
        val latch = CountDownLatch(1)
        handler.post {
            result.set(runCatching(block))
            latch.countDown()
        }
        latch.await()
        return result.get().getOrThrow()
    }
}

internal fun <T> callOnPlaybackMainThread(block: () -> T): T {
    return PlaybackMainThread.callBlocking(block)
}

internal class MediaPlayerAlarmPlaybackPlayer(
    private val player: MediaPlayer
) : AlarmPlaybackPlayer {
    private val released = AtomicBoolean(false)

    override fun setVolume(left: Float, right: Float) {
        if (released.get()) return
        try { player.setVolume(left, right) } catch (_: Exception) {}
    }

    override fun durationMs(): Long {
        if (released.get()) return 0L
        return runCatching { player.duration.toLong() }.getOrDefault(0L)
    }

    override fun seekTo(positionMs: Long) {
        if (released.get()) return
        try { player.seekTo(positionMs.coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()) } catch (_: Exception) {}
    }

    override fun stopAndRelease() {
        if (released.compareAndSet(false, true)) {
            try {
                if (player.isPlaying) {
                    player.stop()
                }
            } catch (_: Exception) {
                // Some platform players throw when queried after an async error.
            } finally {
                try {
                    player.release()
                } catch (_: Exception) {}
            }
        }
    }

    override fun isReleased(): Boolean = released.get()
}

internal class Media3AlarmPlaybackPlayer(
    private val player: ExoPlayer
) : AlarmPlaybackPlayer {
    private val released = AtomicBoolean(false)

    override fun setVolume(left: Float, right: Float) {
        if (released.get()) return
        PlaybackMainThread.run {
            try { player.volume = minOf(left, right).coerceIn(0f, 1f) } catch (_: Exception) {}
        }
    }

    override fun durationMs(): Long {
        if (released.get()) return 0L
        return callOnPlaybackMainThread { runCatching { player.duration.coerceAtLeast(0L) }.getOrDefault(0L) }
    }

    override fun seekTo(positionMs: Long) {
        if (released.get()) return
        PlaybackMainThread.run {
            try { player.seekTo(positionMs.coerceAtLeast(0L)) } catch (_: Exception) {}
        }
    }

    override fun stopAndRelease() {
        if (released.compareAndSet(false, true)) {
            PlaybackMainThread.runBlocking {
                try {
                    player.stop()
                } catch (_: Exception) {
                } finally {
                    try {
                        player.release()
                    } catch (_: Exception) {}
                }
            }
        }
    }

    override fun isReleased(): Boolean = released.get()
}
