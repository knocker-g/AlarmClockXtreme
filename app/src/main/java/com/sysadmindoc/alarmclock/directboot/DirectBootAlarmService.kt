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
        val activeSnapshot = captureActiveSnapshot()
        val candidate = DirectBootServiceStartPolicy.extractCandidate(intent)

        // Stage A: Classify with neutral NOT_QUERIED storage to determine lane safely without reading V2-only DE stores.
        val stageAResult = DirectBootServiceStartPolicy.classify(
            ServiceStartClassificationInput(
                active = activeSnapshot,
                candidate = candidate,
                storage = StartStorageObservation()
            )
        )

        // Stage B: Never authorize V2 output from Stage A. Only genuine LEGACY_V1 avoids V2-only DE storage coupling.
        val decisionResult = if (stageAResult.lane == CandidateLane.LEGACY_V1) {
            stageAResult
        } else {
            val deContext = if (isDeviceProtectedStorage) this else createDeviceProtectedStorageContext()
            val storageObservation = observeStorageForCandidate(deContext, candidate)
            DirectBootServiceStartPolicy.classify(
                ServiceStartClassificationInput(
                    active = activeSnapshot,
                    candidate = candidate,
                    storage = storageObservation
                )
            )
        }

        when (decisionResult.decision) {
            ServiceStartDecision.REJECT_KEEP_OLD,
            ServiceStartDecision.UNKNOWN_KEEP_OLD,
            ServiceStartDecision.PREPARED_NEEDS_OWNER -> {
                Log.w(
                    TAG,
                    "DirectBoot ACTION_START blocked: decision=${decisionResult.decision}, " +
                        "reason=${decisionResult.reason}, lane=${decisionResult.lane}, " +
                        "candidateAlarmId=${candidate.alarmId}, activeAlarmId=${activeSnapshot.alarmId}"
                )
                if (!activeSnapshot.isActive) {
                    stopSelf()
                }
                return
            }
            ServiceStartDecision.DUPLICATE_KEEP_OLD -> {
                Log.i(
                    TAG,
                    "DirectBoot ACTION_START duplicate ignored: reason=${decisionResult.reason}, " +
                        "lane=${decisionResult.lane}, candidateAlarmId=${candidate.alarmId}, " +
                        "activeAlarmId=${activeSnapshot.alarmId}"
                )
                if (!activeSnapshot.isActive) {
                    stopSelf()
                }
                return
            }
            ServiceStartDecision.LEGACY_COMPATIBILITY_PENDING -> {
                // Defense-in-depth: B1b runtime NEVER admits V2 candidates or non-V1 lanes.
                if (decisionResult.lane != CandidateLane.LEGACY_V1 ||
                    candidate.instanceToken.isNotBlank() ||
                    candidate.restoreEpoch != 0L
                ) {
                    Log.e(
                        TAG,
                        "Fail-closed guard blocked unexpected non-legacy admission: " +
                            "lane=${decisionResult.lane}, candidateAlarmId=${candidate.alarmId}"
                    )
                    if (!activeSnapshot.isActive) {
                        stopSelf()
                    }
                    return
                }
            }
        }

        val timeLabel = try {
            intent.getStringExtra(DirectBootAlarmReceiver.EXTRA_TIME_LABEL).orEmpty()
        } catch (_: Exception) {
            ""
        }

        // Legacy V1 Handover Sequence: Clean up resource claims of active V1 alarm before taking over
        stopDefaultAlarmSoundInternal()
        stopVibrationInternal()
        autoStopRunnable?.let { handler.removeCallbacks(it) }

        currentAlarmId = candidate.alarmId
        currentTriggerTime = candidate.triggerTime
        currentGeneration = candidate.generation
        currentInstanceToken = ""
        currentRestoreEpoch = 0L

        startForeground(NOTIFICATION_ID, buildNotification(timeLabel))
        DirectBootAlarmCache.recordFired(
            context = this,
            alarmId = candidate.alarmId,
            triggerTime = candidate.triggerTime,
            firedAt = System.currentTimeMillis()
        )

        if (candidate.playDefaultSound) startDefaultAlarmSound()
        if (candidate.vibrationEnabled) startVibration()

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

    private fun captureActiveSnapshot(): ActiveServiceSnapshot {
        val active = currentAlarmId > 0L
        val playing = try {
            mediaPlayer?.isPlaying == true
        } catch (_: Exception) {
            mediaPlayer != null
        }
        val vibrating = vibrator != null
        return ActiveServiceSnapshot(
            isActive = active,
            alarmId = currentAlarmId,
            triggerTime = currentTriggerTime,
            generation = currentGeneration,
            instanceToken = currentInstanceToken,
            restoreEpoch = currentRestoreEpoch,
            isV2Managed = active && currentGeneration >= 1 && currentInstanceToken.isNotBlank(),
            isAudibleOrVibrating = active && (playing || mediaPlayer != null || vibrating),
            autoStopArmed = active && autoStopRunnable != null
        )
    }

    private fun observeStorageForCandidate(
        deContext: Context,
        candidate: IncomingStartCandidate
    ): StartStorageObservation {
        return try {
            val witnessState = DirectBootFiredWitnessLedger.readWitnesses(deContext)
            var witnessOutcome = WitnessOutcomeObservation.NOT_APPLICABLE
            val witnessStatus = when (witnessState) {
                is WitnessLedgerState.Absent,
                is WitnessLedgerState.ValidEmpty -> WitnessObservationStatus.ABSENT_OR_EMPTY
                is WitnessLedgerState.Corrupt -> WitnessObservationStatus.CORRUPT
                is WitnessLedgerState.UnsupportedSchema -> WitnessObservationStatus.UNSUPPORTED_SCHEMA
                is WitnessLedgerState.ValidWithRecords -> {
                    if (candidate.alarmId > 0L &&
                        candidate.generation >= 1 &&
                        candidate.instanceToken.isNotBlank() &&
                        candidate.restoreEpoch > 0L
                    ) {
                        val expectedRecordId = DirectBootFiredWitnessRecord.computeRecordId(
                            kind = DirectBootFiredWitnessRecord.KIND_NORMAL,
                            alarmId = candidate.alarmId,
                            triggerTime = candidate.triggerTime,
                            generation = candidate.generation,
                            instanceToken = candidate.instanceToken,
                            restoreEpoch = candidate.restoreEpoch
                        )
                        val exact = witnessState.records.firstOrNull { it.recordId == expectedRecordId }
                        if (exact != null) {
                            val fieldsMatch = exact.kind == DirectBootFiredWitnessRecord.KIND_NORMAL &&
                                exact.alarmId == candidate.alarmId &&
                                exact.triggerTime == candidate.triggerTime &&
                                exact.generation == candidate.generation &&
                                exact.instanceToken == candidate.instanceToken &&
                                exact.restoreEpoch == candidate.restoreEpoch
                            if (fieldsMatch) {
                                witnessOutcome = mapWitnessOutcome(exact.deliveryOutcome)
                                WitnessObservationStatus.PRESENT_MATCHING_EXACT
                            } else {
                                WitnessObservationStatus.PRESENT_CONFLICTING
                            }
                        } else {
                            val conflicting = witnessState.records.any {
                                it.kind == DirectBootFiredWitnessRecord.KIND_NORMAL &&
                                    it.alarmId == candidate.alarmId &&
                                    it.generation == candidate.generation &&
                                    it.instanceToken == candidate.instanceToken &&
                                    (it.restoreEpoch != candidate.restoreEpoch || it.triggerTime != candidate.triggerTime)
                            }
                            if (conflicting) WitnessObservationStatus.PRESENT_CONFLICTING
                            else WitnessObservationStatus.ABSENT_OR_EMPTY
                        }
                    } else {
                        WitnessObservationStatus.ABSENT_OR_EMPTY
                    }
                }
            }

            val ledgerState = DirectBootDeliveryLedger.readUnacknowledgedRecords(deContext)
            val ledgerStatus = when (ledgerState) {
                is LedgerStorageState.Absent,
                is LedgerStorageState.ValidEmpty -> LedgerObservationStatus.ABSENT_OR_EMPTY
                is LedgerStorageState.Corrupt -> LedgerObservationStatus.CORRUPT
                is LedgerStorageState.UnsupportedSchema -> LedgerObservationStatus.UNSUPPORTED_SCHEMA
                is LedgerStorageState.ValidWithRecords -> {
                    if (candidate.alarmId > 0L && candidate.generation >= 1 && candidate.instanceToken.isNotBlank()) {
                        val expectedRecordId = DirectBootDeliveryRecord.computeRecordId(
                            kind = DirectBootDeliveryRecord.KIND_NORMAL,
                            alarmId = candidate.alarmId,
                            triggerTime = candidate.triggerTime,
                            generation = candidate.generation,
                            instanceToken = candidate.instanceToken
                        )
                        val matched = ledgerState.records.any {
                            it.recordId == expectedRecordId && it.restoreEpoch == candidate.restoreEpoch
                        }
                        when {
                            matched -> LedgerObservationStatus.PRESENT_RECORDED
                            ledgerState.records.size >= DirectBootDeliveryLedger.MAX_CAPACITY -> LedgerObservationStatus.CAPACITY_EXHAUSTED
                            else -> LedgerObservationStatus.ABSENT_OR_EMPTY
                        }
                    } else {
                        LedgerObservationStatus.ABSENT_OR_EMPTY
                    }
                }
            }

            val queueState = DirectBootAlarmCache.inspectQueueStorageState(deContext)
            val queueStatus = when (queueState) {
                is QueueStorageState.Absent,
                is QueueStorageState.ValidEmpty -> QueueObservationStatus.ABSENT_OR_EMPTY
                is QueueStorageState.Corrupt -> QueueObservationStatus.CORRUPT
                is QueueStorageState.UnsupportedSchema -> QueueObservationStatus.UNSUPPORTED_SCHEMA
                is QueueStorageState.ValidWithItems -> {
                    val head = queueState.items.firstOrNull()
                    if (head != null &&
                        head.kind == DirectBootQueueItem.KIND_NORMAL &&
                        head.alarmId == candidate.alarmId &&
                        head.triggerTime == candidate.triggerTime &&
                        head.generation == candidate.generation &&
                        head.instanceToken == candidate.instanceToken &&
                        head.restoreEpoch == candidate.restoreEpoch
                    ) {
                        QueueObservationStatus.MATCHES_HEAD
                    } else {
                        QueueObservationStatus.NOT_QUEUE_HEAD
                    }
                }
            }

            StartStorageObservation(
                witnessStorageStatus = witnessStatus,
                witnessOutcome = witnessOutcome,
                deliveryLedgerStatus = ledgerStatus,
                queueStorageStatus = queueStatus,
                ownerStatus = DurableOwnerObservation.UNAVAILABLE_NO_DURABLE_OWNER
            )
        } catch (_: Exception) {
            StartStorageObservation()
        }
    }

    private fun mapWitnessOutcome(deliveryOutcome: String): WitnessOutcomeObservation {
        return when (deliveryOutcome) {
            DirectBootFiredWitnessRecord.OUTCOME_OUTPUT_STATUS_UNKNOWN -> WitnessOutcomeObservation.OUTPUT_STATUS_UNKNOWN
            DirectBootFiredWitnessRecord.OUTCOME_OUTPUT_START_ACCEPTED -> WitnessOutcomeObservation.OUTPUT_START_ACCEPTED
            DirectBootFiredWitnessRecord.OUTCOME_PARTIAL_OUTPUT_ACCEPTED -> WitnessOutcomeObservation.PARTIAL_OUTPUT_ACCEPTED
            DirectBootFiredWitnessRecord.OUTCOME_OUTPUT_START_FAILED -> WitnessOutcomeObservation.OUTPUT_START_FAILED
            DirectBootFiredWitnessRecord.OUTCOME_NO_OUTPUT_REQUESTED -> WitnessOutcomeObservation.NO_OUTPUT_REQUESTED
            else -> WitnessOutcomeObservation.NOT_APPLICABLE
        }
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
