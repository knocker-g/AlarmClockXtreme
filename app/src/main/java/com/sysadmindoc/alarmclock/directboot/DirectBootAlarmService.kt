package com.sysadmindoc.alarmclock.directboot

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import com.sysadmindoc.alarmclock.R
import com.sysadmindoc.alarmclock.service.AlarmAudioRouting

class DirectBootAlarmService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private var mediaPlayer: MediaPlayer? = null
    private var vibrator: Vibrator? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var currentAlarmId: Long = -1L
    private var currentTriggerTime: Long = 0L
    private var currentGeneration: Int = 0
    private var currentInstanceToken: String = ""
    private var currentRestoreEpoch: Long = 0L
    private var autoStopRunnable: Runnable? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        acquireWakeLock()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                val stopAlarmId = intent.getLongExtra(DirectBootAlarmReceiver.EXTRA_ALARM_ID, -1L)
                val stopTrig = intent.getLongExtra(DirectBootAlarmReceiver.EXTRA_TRIGGER_TIME, 0L)
                val stopGen = intent.getIntExtra(DirectBootV2NormalIntentIdentity.EXTRA_GENERATION, -1)
                val stopToken = intent.getStringExtra(DirectBootV2NormalIntentIdentity.EXTRA_INSTANCE_TOKEN)
                val stopEpoch = intent.getLongExtra(DirectBootV2NormalIntentIdentity.EXTRA_RESTORE_EPOCH, -1L)

                if (currentAlarmId <= 0L) {
                    stopSelf()
                    return START_NOT_STICKY
                }

                if (stopAlarmId != currentAlarmId) {
                    Log.w(TAG, "Stale ACTION_STOP for alarm $stopAlarmId rejected: active alarm is $currentAlarmId")
                    return START_NOT_STICKY
                }

                // Fail-closed Lineage Fencing: If active alarm is V2 managed, require ALL canonical fields to match
                val isV2Active = currentGeneration >= 1 && currentInstanceToken.isNotBlank()
                if (isV2Active) {
                    val isValidV2Stop = stopGen == currentGeneration &&
                        stopEpoch == currentRestoreEpoch &&
                        stopTrig == currentTriggerTime &&
                        !stopToken.isNullOrBlank() &&
                        stopToken == currentInstanceToken

                    if (!isValidV2Stop) {
                        Log.w(
                            TAG,
                            "Un-qualified or partial ACTION_STOP rejected for V2 active alarm $currentAlarmId " +
                                "(active gen=$currentGeneration, epoch=$currentRestoreEpoch, trig=$currentTriggerTime; " +
                                "stop gen=$stopGen, epoch=$stopEpoch, trig=$stopTrig, tokenPresent=${!stopToken.isNullOrBlank()})"
                        )
                        return START_NOT_STICKY
                    }
                }

                stopAlarm()
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_START -> startDirectBootAlarm(intent)
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        stopAlarm()
        super.onDestroy()
    }

    private fun startDirectBootAlarm(intent: Intent) {
        val alarmId = intent.getLongExtra(DirectBootAlarmReceiver.EXTRA_ALARM_ID, -1L)
        if (alarmId <= 0L) {
            stopSelf()
            return
        }

        // C7-I3 5-Step Handover Sequence: Clean up resource claims of active alarm before taking over
        stopDefaultAlarmSoundInternal()
        stopVibrationInternal()
        autoStopRunnable?.let { handler.removeCallbacks(it) }

        currentAlarmId = alarmId
        currentTriggerTime = intent.getLongExtra(DirectBootAlarmReceiver.EXTRA_TRIGGER_TIME, 0L)
        currentGeneration = intent.getIntExtra(DirectBootV2NormalIntentIdentity.EXTRA_GENERATION, 1)
        currentInstanceToken = intent.getStringExtra(DirectBootV2NormalIntentIdentity.EXTRA_INSTANCE_TOKEN).orEmpty()
        currentRestoreEpoch = intent.getLongExtra(DirectBootV2NormalIntentIdentity.EXTRA_RESTORE_EPOCH, 0L)

        val label = intent.getStringExtra(DirectBootAlarmReceiver.EXTRA_LABEL).orEmpty()
        val timeLabel = intent.getStringExtra(DirectBootAlarmReceiver.EXTRA_TIME_LABEL).orEmpty()
        val playDefaultSound = intent.getBooleanExtra(
            DirectBootAlarmReceiver.EXTRA_PLAY_DEFAULT_SOUND,
            true
        )
        val vibrationEnabled = intent.getBooleanExtra(
            DirectBootAlarmReceiver.EXTRA_VIBRATION_ENABLED,
            true
        )

        val isV2Alarm = currentGeneration >= 1 && currentInstanceToken.isNotBlank()
        val deContext = if (isDeviceProtectedStorage) this else createDeviceProtectedStorageContext()
        var reservationRecord: DirectBootFiredWitnessRecord? = null

        if (isV2Alarm) {
            val mode = when {
                playDefaultSound && vibrationEnabled -> DirectBootFiredWitnessRecord.MODE_SOUND_AND_VIBRATION
                playDefaultSound -> DirectBootFiredWitnessRecord.MODE_SOUND_ONLY
                vibrationEnabled -> DirectBootFiredWitnessRecord.MODE_VIBRATION_ONLY
                else -> DirectBootFiredWitnessRecord.MODE_SILENT_NOTIFICATION
            }
            reservationRecord = DirectBootFiredWitnessRecord.createNormal(
                alarmId = alarmId,
                triggerTime = currentTriggerTime,
                generation = currentGeneration,
                instanceToken = currentInstanceToken,
                restoreEpoch = currentRestoreEpoch,
                deliveryMode = mode,
                deliveryOutcome = DirectBootFiredWitnessRecord.OUTCOME_OUTPUT_STATUS_UNKNOWN
            )

            val reserveResult = DirectBootFiredWitnessLedger.reserveWitnessSlot(deContext, reservationRecord)
            if (reserveResult is WitnessAppendResult.DuplicateIgnored) {
                Log.i(TAG, "Duplicate V2 START intent received for active occurrence $alarmId; extra output aborted")
                return
            }
            if (reserveResult !is WitnessAppendResult.Appended) {
                Log.w(TAG, "Pre-output gate rejected V2 alarm $alarmId: reserveResult $reserveResult; output aborted")
                stopSelf()
                return
            }
        }

        startForeground(NOTIFICATION_ID, buildNotification(timeLabel))
        DirectBootAlarmCache.recordFired(
            context = this,
            alarmId = alarmId,
            triggerTime = currentTriggerTime,
            firedAt = System.currentTimeMillis()
        )

        var soundOk = false
        var vibrationOk = false

        if (playDefaultSound) soundOk = startDefaultAlarmSound()
        if (vibrationEnabled) vibrationOk = startVibration()

        if (isV2Alarm && reservationRecord != null) {
            val outcome = when {
                playDefaultSound && vibrationEnabled -> {
                    if (soundOk && vibrationOk) DirectBootFiredWitnessRecord.OUTCOME_OUTPUT_START_ACCEPTED
                    else if (soundOk || vibrationOk) DirectBootFiredWitnessRecord.OUTCOME_PARTIAL_OUTPUT_ACCEPTED
                    else DirectBootFiredWitnessRecord.OUTCOME_OUTPUT_START_FAILED
                }
                playDefaultSound -> {
                    if (soundOk) DirectBootFiredWitnessRecord.OUTCOME_OUTPUT_START_ACCEPTED
                    else DirectBootFiredWitnessRecord.OUTCOME_OUTPUT_START_FAILED
                }
                vibrationEnabled -> {
                    if (vibrationOk) DirectBootFiredWitnessRecord.OUTCOME_OUTPUT_START_ACCEPTED
                    else DirectBootFiredWitnessRecord.OUTCOME_OUTPUT_START_FAILED
                }
                else -> DirectBootFiredWitnessRecord.OUTCOME_NO_OUTPUT_REQUESTED
            }

            val updateRes = DirectBootFiredWitnessLedger.updateWitnessOutcome(
                context = deContext,
                recordId = reservationRecord.recordId,
                deliveryMode = reservationRecord.deliveryMode,
                deliveryOutcome = outcome
            )
            if (updateRes is WitnessUpdateResult.PersistenceFailed || updateRes is WitnessUpdateResult.StorageCorrupt) {
                Log.w(TAG, "Witness outcome update commit failed ($updateRes); UNKNOWN reservation retained in DE storage for post-unlock takeover")
            }
        }

        // Schedule AutoStop with active lineage fencing
        val targetId = currentAlarmId
        val targetGen = currentGeneration
        val targetToken = currentInstanceToken
        val targetEpoch = currentRestoreEpoch
        autoStopRunnable = Runnable {
            if (currentAlarmId == targetId && currentGeneration == targetGen && currentInstanceToken == targetToken && currentRestoreEpoch == targetEpoch) {
                stopAlarm()
                stopSelf()
            } else {
                Log.w(TAG, "Stale AutoStop runnable ignored for alarm $targetId gen $targetGen")
            }
        }
        handler.postDelayed(autoStopRunnable!!, AUTO_STOP_MS)
    }

    private fun buildNotification(timeLabel: String): Notification {
        val stopUri = android.net.Uri.parse("acx-directboot-stop://alarm/$currentAlarmId/gen/$currentGeneration/epoch/$currentRestoreEpoch/trig/$currentTriggerTime/tok/$currentInstanceToken")
        val stopIntent = Intent(this, DirectBootAlarmService::class.java).apply {
            action = ACTION_STOP
            data = stopUri
            putExtra(DirectBootAlarmReceiver.EXTRA_ALARM_ID, currentAlarmId)
            putExtra(DirectBootAlarmReceiver.EXTRA_TRIGGER_TIME, currentTriggerTime)
            putExtra(DirectBootV2NormalIntentIdentity.EXTRA_GENERATION, currentGeneration)
            putExtra(DirectBootV2NormalIntentIdentity.EXTRA_INSTANCE_TOKEN, currentInstanceToken)
            putExtra(DirectBootV2NormalIntentIdentity.EXTRA_RESTORE_EPOCH, currentRestoreEpoch)
        }
        val requestCode = (stopUri.toString().hashCode() and 0x7FFFFFFF)
        val stopPendingIntent = PendingIntent.getService(
            this,
            requestCode,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val text = when {
            timeLabel.isNotBlank() -> timeLabel
            else -> getString(R.string.direct_boot_alarm_title)
        }

        val fullScreenIntent = DirectBootAlarmActivity.fullScreenIntent(
            context = this,
            alarmId = currentAlarmId,
            timeLabel = timeLabel,
            generation = currentGeneration,
            instanceToken = currentInstanceToken,
            restoreEpoch = currentRestoreEpoch,
            triggerTime = currentTriggerTime
        )
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_alarm)
            .setContentTitle(getString(R.string.direct_boot_alarm_title))
            .setContentText(text)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .setAutoCancel(false)
            .setContentIntent(fullScreenIntent)
            .setFullScreenIntent(fullScreenIntent, true)
            .addAction(R.drawable.ic_alarm, "Stop", stopPendingIntent)
        return builder.build()
    }

    private fun startDefaultAlarmSound(): Boolean {
        val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            ?: return false
        return try {
            mediaPlayer = MediaPlayer().apply {
                setAudioAttributes(AlarmAudioRouting.alarmSonificationAttributes())
                setDataSource(applicationContext, uri)
                isLooping = true
                prepare()
                start()
            }
            true
        } catch (e: Exception) {
            Log.w(TAG, "Failed to play default direct-boot alarm sound", e)
            try {
                mediaPlayer?.release()
            } catch (_: Exception) {
            }
            mediaPlayer = null
            false
        }
    }

    private fun startVibration(): Boolean {
        vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)
                ?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }
        if (vibrator?.hasVibrator() != true) return false

        return try {
            val effect = VibrationEffect.createWaveform(
                longArrayOf(0, 500, 500, 500, 500),
                intArrayOf(0, 255, 0, 255, 0),
                0
            )
            val attributes = AlarmAudioRouting.alarmSonificationAttributes()
            @Suppress("DEPRECATION")
            vibrator?.vibrate(effect, attributes)
            true
        } catch (e: Exception) {
            Log.w(TAG, "Failed to start vibration", e)
            false
        }
    }

    private fun stopDefaultAlarmSoundInternal() {
        try {
            mediaPlayer?.stop()
        } catch (_: Exception) {
        }
        try {
            mediaPlayer?.release()
        } catch (_: Exception) {
        }
        mediaPlayer = null
    }

    private fun stopVibrationInternal() {
        try {
            vibrator?.cancel()
        } catch (_: Exception) {
        }
        vibrator = null
    }

    private fun stopAlarm() {
        autoStopRunnable?.let { handler.removeCallbacks(it) }
        stopDefaultAlarmSoundInternal()
        stopVibrationInternal()
        try {
            wakeLock?.takeIf { it.isHeld }?.release()
        } catch (_: Exception) {
        }
        wakeLock = null
        stopForeground(STOP_FOREGROUND_REMOVE)
    }

    private fun acquireWakeLock() {
        val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager
        try {
            wakeLock = pm?.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "AlarmClockXtreme::DirectBootAlarmWakeLock"
            )?.apply {
                acquire(AUTO_STOP_MS)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Direct-boot wake lock acquisition failed", e)
        }
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notif_direct_boot_channel_name),
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = getString(R.string.notif_direct_boot_channel_description)
            setBypassDnd(true)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            setSound(null, null)
            enableVibration(false)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    companion object {
        const val ACTION_START = "com.sysadmindoc.alarmclock.directboot.START_ALARM"
        const val ACTION_STOP = "com.sysadmindoc.alarmclock.directboot.STOP_ALARM"

        private const val TAG = "DirectBootAlarmService"
        private const val CHANNEL_ID = "direct_boot_alarm_channel"
        private const val NOTIFICATION_ID = 1011
        private const val AUTO_STOP_MS = 10 * 60 * 1000L
    }
}
