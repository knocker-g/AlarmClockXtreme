package com.sysadmindoc.alarmclock.directboot

import android.app.Notification
import android.content.Context
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.os.Build
import android.os.Handler
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import com.sysadmindoc.alarmclock.service.AlarmAudioRouting
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowMediaPlayer
import org.robolectric.shadows.ShadowVibrator
import org.robolectric.shadows.util.DataSource
import java.lang.reflect.Field

/**
 * Strictly test-only fixture & reflection observer for [DirectBootAlarmService] (KNO-75 B1b-R1).
 *
 * Scope & invariants:
 * - Lives exclusively under src/test. Zero production injection or @VisibleForTesting seams added.
 * - Seeds synthetic active state on private Service fields via reflection so STOP/lifecycle tests
 *   do not depend on calling unauthenticated V2 ACTION_START.
 * - Does NOT authorize V2 runtime delivery or reserve production witnesses unless a test explicitly
 *   writes test storage.
 */
internal object DirectBootServiceTestFixture {

    private const val NOTIFICATION_ID = 1011
    const val AUTO_STOP_MS = 10 * 60 * 1000L

    private fun field(name: String): Field =
        DirectBootAlarmService::class.java.getDeclaredField(name).apply {
            isAccessible = true
        }

    fun currentAlarmId(service: DirectBootAlarmService): Long =
        field("currentAlarmId").getLong(service)

    fun currentTriggerTime(service: DirectBootAlarmService): Long =
        field("currentTriggerTime").getLong(service)

    fun currentGeneration(service: DirectBootAlarmService): Int =
        field("currentGeneration").getInt(service)

    fun currentInstanceToken(service: DirectBootAlarmService): String =
        field("currentInstanceToken").get(service) as String

    fun currentRestoreEpoch(service: DirectBootAlarmService): Long =
        field("currentRestoreEpoch").getLong(service)

    fun mediaPlayer(service: DirectBootAlarmService): MediaPlayer? =
        field("mediaPlayer").get(service) as MediaPlayer?

    fun vibrator(service: DirectBootAlarmService): Vibrator? =
        field("vibrator").get(service) as Vibrator?

    fun autoStopRunnable(service: DirectBootAlarmService): Runnable? =
        field("autoStopRunnable").get(service) as Runnable?

    fun handler(service: DirectBootAlarmService): Handler =
        field("handler").get(service) as Handler

    fun invokeBuildNotification(service: DirectBootAlarmService, timeLabel: String): Notification {
        val method = DirectBootAlarmService::class.java.getDeclaredMethod(
            "buildNotification",
            String::class.java
        ).apply {
            isAccessible = true
        }
        return method.invoke(service, timeLabel) as Notification
    }

    /**
     * Configures Robolectric [ShadowMediaPlayer] and [ShadowVibrator] so real [MediaPlayer] and
     * [Vibrator] start paths in [DirectBootAlarmService] succeed in JVM tests.
     */
    fun configureAudioAndVibrationEnvironment(context: Context) {
        val mediaInfo = ShadowMediaPlayer.MediaInfo(60_000, 0)
        listOf(
            RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM),
            RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
        ).filterNotNull().forEach { uri ->
            ShadowMediaPlayer.addMediaInfo(
                DataSource.toDataSource(context.applicationContext, uri),
                mediaInfo
            )
        }

        val vib = obtainDefaultVibrator(context)
        if (vib != null) {
            shadowOf(vib).setHasVibrator(true)
        }
    }

    fun obtainDefaultVibrator(context: Context): Vibrator? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)
                ?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }
    }

    /**
     * Seeds synthetic active V2 (or V1) identity and active Robolectric media/vibrator/AutoStop/foreground
     * effects via test-side reflection only. Never invokes production ACTION_START or reserves a witness.
     */
    fun seedActiveState(
        service: DirectBootAlarmService,
        context: Context,
        alarmId: Long,
        triggerTime: Long,
        generation: Int,
        instanceToken: String,
        restoreEpoch: Long,
        timeLabel: String = "7:15 AM",
        withAudio: Boolean = true,
        withVibration: Boolean = true,
        withAutoStop: Boolean = true,
        postForegroundNotification: Boolean = true
    ) {
        configureAudioAndVibrationEnvironment(context)

        // Clean up any previously seeded callbacks/players cleanly before replacing synthetic state
        autoStopRunnable(service)?.let { handler(service).removeCallbacks(it) }
        mediaPlayer(service)?.let {
            try { it.stop() } catch (_: Exception) {}
            try { it.release() } catch (_: Exception) {}
        }
        vibrator(service)?.let {
            try { it.cancel() } catch (_: Exception) {}
        }

        field("currentAlarmId").setLong(service, alarmId)
        field("currentTriggerTime").setLong(service, triggerTime)
        field("currentGeneration").setInt(service, generation)
        field("currentInstanceToken").set(service, instanceToken)
        field("currentRestoreEpoch").setLong(service, restoreEpoch)

        if (withAudio) {
            val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
                ?: error("Robolectric default alarm URI unavailable")
            val player = MediaPlayer().apply {
                setAudioAttributes(AlarmAudioRouting.alarmSonificationAttributes())
                setDataSource(context.applicationContext, uri)
                isLooping = true
                prepare()
                start()
            }
            field("mediaPlayer").set(service, player)
        } else {
            field("mediaPlayer").set(service, null)
        }

        if (withVibration) {
            val vib = obtainDefaultVibrator(context)
                ?: error("Robolectric default Vibrator unavailable")
            shadowOf(vib).setHasVibrator(true)
            val effect = VibrationEffect.createWaveform(
                longArrayOf(0, 500, 500, 500, 500),
                intArrayOf(0, 255, 0, 255, 0),
                0
            )
            @Suppress("DEPRECATION")
            vib.vibrate(effect, AlarmAudioRouting.alarmSonificationAttributes())
            field("vibrator").set(service, vib)
        } else {
            field("vibrator").set(service, null)
        }

        if (withAutoStop) {
            val stopAlarmMethod = DirectBootAlarmService::class.java.getDeclaredMethod("stopAlarm").apply {
                isAccessible = true
            }
            val runnable = Runnable {
                if (currentAlarmId(service) == alarmId &&
                    currentGeneration(service) == generation &&
                    currentInstanceToken(service) == instanceToken &&
                    currentRestoreEpoch(service) == restoreEpoch
                ) {
                    stopAlarmMethod.invoke(service)
                    service.stopSelf()
                }
            }
            field("autoStopRunnable").set(service, runnable)
            handler(service).postDelayed(runnable, AUTO_STOP_MS)
        } else {
            field("autoStopRunnable").set(service, null)
        }

        if (postForegroundNotification) {
            val notification = invokeBuildNotification(service, timeLabel)
            service.startForeground(NOTIFICATION_ID, notification)
        }
    }

    data class EffectSnapshot(
        val alarmId: Long,
        val triggerTime: Long,
        val generation: Int,
        val instanceToken: String,
        val restoreEpoch: Long,
        val mediaPlayer: MediaPlayer?,
        val mediaPlayerPlaying: Boolean,
        val vibrator: Vibrator?,
        val vibrating: Boolean,
        val vibratorCancelled: Boolean,
        val autoStopRunnable: Runnable?,
        val autoStopScheduled: Boolean,
        val stoppedBySelf: Boolean,
        val foregroundNotification: Notification?
    )

    fun captureEffects(service: DirectBootAlarmService): EffectSnapshot {
        val mp = mediaPlayer(service)
        val mpPlaying = try {
            mp?.isPlaying == true
        } catch (_: Exception) {
            false
        }
        val vib = vibrator(service)
        val vibShadow: ShadowVibrator? = vib?.let { shadowOf(it) }
        val cb = autoStopRunnable(service)
        val cbScheduled = cb != null && handler(service).hasCallbacks(cb)
        val shadowSvc = shadowOf(service)
        return EffectSnapshot(
            alarmId = currentAlarmId(service),
            triggerTime = currentTriggerTime(service),
            generation = currentGeneration(service),
            instanceToken = currentInstanceToken(service),
            restoreEpoch = currentRestoreEpoch(service),
            mediaPlayer = mp,
            mediaPlayerPlaying = mpPlaying,
            vibrator = vib,
            vibrating = vibShadow?.isVibrating == true,
            vibratorCancelled = vibShadow?.isCancelled == true,
            autoStopRunnable = cb,
            autoStopScheduled = cbScheduled,
            stoppedBySelf = shadowSvc.isStoppedBySelf,
            foregroundNotification = shadowSvc.lastForegroundNotification
        )
    }

    fun clearAllDirectBootPrefs(deContext: Context) {
        deContext.getSharedPreferences(DirectBootFiredWitnessLedger.PREFS_NAME, Context.MODE_PRIVATE)
            .edit().clear().commit()
        deContext.getSharedPreferences(DirectBootDeliveryLedger.PREFS_NAME, Context.MODE_PRIVATE)
            .edit().clear().commit()
    }
}
