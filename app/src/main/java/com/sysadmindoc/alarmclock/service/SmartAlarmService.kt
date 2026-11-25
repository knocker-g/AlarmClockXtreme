package com.sysadmindoc.alarmclock.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import java.util.concurrent.atomic.AtomicBoolean
import com.sysadmindoc.alarmclock.R
import com.sysadmindoc.alarmclock.data.actigraphy.ActigraphyEpoch
import com.sysadmindoc.alarmclock.data.actigraphy.ActigraphySleepClassifier
import com.sysadmindoc.alarmclock.data.actigraphy.SmartWakeDecision
import com.sysadmindoc.alarmclock.data.actigraphy.SmartWakeDecisionEngine
import com.sysadmindoc.alarmclock.data.actigraphy.SmartWakeDecisionResult
import com.sysadmindoc.alarmclock.data.repository.ActigraphyRepository
import com.sysadmindoc.alarmclock.domain.AlarmScheduler
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.sqrt
import javax.inject.Inject

/**
 * F7: Smart alarm window — monitors accelerometer overnight.
 * Started by AlarmScheduler [smartAlarmWindowMinutes] before the scheduled alarm time.
 * If scored phone-motion buckets indicate a conservative light-motion window, fires the alarm early.
 * If the scheduled time arrives without detection, the regular AlarmReceiver fires normally.
 */
@org.jetbrains.annotations.VisibleForTesting
internal var smartAlarmOutputAuthorityGateSeam: com.sysadmindoc.alarmclock.directboot.outputauthority.SharedOutputAuthorityGate? = null

@org.jetbrains.annotations.VisibleForTesting
internal var smartAlarmRoomReceiptCheckerSeam: ((Long, Long) -> Boolean)? = null

@AndroidEntryPoint
class SmartAlarmService : Service(), SensorEventListener {

    @androidx.annotation.VisibleForTesting
    internal fun callStartAlarmServiceForTesting() {
        startAlarmService()
    }

    @Inject lateinit var actigraphyRepository: ActigraphyRepository
    @Inject lateinit var alarmScheduler: AlarmScheduler
    @Inject lateinit var alarmRepository: com.sysadmindoc.alarmclock.data.repository.AlarmRepository
    @Inject lateinit var alarmIncidentRepository: com.sysadmindoc.alarmclock.data.repository.AlarmIncidentRepository
    @Inject lateinit var preferencesManager: com.sysadmindoc.alarmclock.data.preferences.PreferencesManager

    companion object {
        const val ACTION_START_SMART = "com.sysadmindoc.alarmclock.SMART_ALARM_START"
        const val EXTRA_ALARM_ID = "smart_alarm_id"
        const val EXTRA_TARGET_TIME = "smart_target_time"
        const val EXTRA_GENERATION = "smart_generation"
        const val EXTRA_SCHEDULE_REVISION = "smart_schedule_revision"
        const val CHANNEL_SMART = "smart_alarm_channel"
        const val NOTIF_ID_SMART = 2003
        private const val MAX_SMART_WAKELOCK_MS = 65 * 60 * 1000L
        private const val WAKELOCK_TEARDOWN_BUFFER_MS = 5 * 60 * 1000L
    }

    private var sensorManager: SensorManager? = null
    private var accelerometer: Sensor? = null
    private var wakeLock: PowerManager.WakeLock? = null

    private var alarmId: Long = -1L
    private var targetTimeMs: Long = 0L
    private var issueToken: String? = null
    private var expectedGeneration: Int = 0
    private var expectedRestoreEpoch: Long = 0L
    private var expectedScheduleRevision: Long = 0L
    private var windowMotionMax = 0f
    private var windowStartMs = 0L
    private var sessionStartMs = 0L
    private val sessionClosed = AtomicBoolean(false)
    private var latestReceivedStartId: Int = 0
    private var latestDecision: SmartWakeDecisionResult? = null
    private val WINDOW_MS = 30_000L  // 30-second motion sampling windows
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val halfMinuteMotion = mutableListOf<Float>()
    private val actigraphyEpochs = mutableListOf<ActigraphyEpoch>()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        // v1.5.4: Safe casts — stripped-down AOSP / managed-profile devices
        // have been seen to return null for SENSOR_SERVICE / POWER_SERVICE.
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as? SensorManager
        accelerometer = sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

        val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager
        wakeLock = pm?.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "AlarmClockXtreme::SmartAlarmWakeLock"
        )?.apply {
            // Reference counting means two overlapping smart windows acquire
            // twice while onDestroy releases once, leaving the CPU held.
            setReferenceCounted(false)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        latestReceivedStartId = startId

        if (intent?.action != ACTION_START_SMART) {
            terminateSafely(latestReceivedStartId)
            return START_NOT_STICKY
        }

        // KNO-75 Step 6 / KNO-76: Personal edition retires Smart early-fire runtime.
        // Satisfy Android O+ startForegroundService contract when invoked from a
        // pre-upgrade Smart PendingIntent, immediately drop foreground, perform
        // zero WakeLock / Sensor / monitoring / early-fire side effects, and
        // terminate via stopSelfResult(latestReceivedStartId).
        val notification = NotificationCompat.Builder(this, CHANNEL_SMART)
            .setSmallIcon(R.drawable.ic_alarm)
            .setContentTitle(getString(R.string.notif_smart_alarm_title))
            .setContentText(getString(R.string.notif_smart_alarm_text))
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        var foregroundStarted = false
        try {
            startForeground(NOTIF_ID_SMART, notification)
            foregroundStarted = true
        } catch (e: Exception) {
            android.util.Log.e(
                "SmartAlarmService",
                "SmartAlarmService retired ingress startForeground failed (startId=$startId)",
                e
            )
            val incomingAlarmId = intent.getLongExtra(EXTRA_ALARM_ID, -1L)
            val incomingTargetTime = intent.getLongExtra(EXTRA_TARGET_TIME, 0L)
            if (incomingAlarmId > 0L && incomingTargetTime > 0L && ::alarmIncidentRepository.isInitialized) {
                runCatching {
                    alarmIncidentRepository.recordAsync(
                        alarmId = incomingAlarmId,
                        fireId = com.sysadmindoc.alarmclock.data.local.entity.AlarmIncidentEvent.fireIdFor(
                            incomingAlarmId,
                            incomingTargetTime
                        ),
                        scheduledAt = incomingTargetTime,
                        type = com.sysadmindoc.alarmclock.data.local.entity.AlarmIncidentEvent.TYPE_FOREGROUND_SERVICE,
                        status = com.sysadmindoc.alarmclock.data.local.entity.AlarmIncidentEvent.STATUS_FAILED,
                        reasonCode = "SMART_PERSONAL_RETIRED_START_FOREGROUND_FAILED",
                        source = "SmartAlarmService"
                    )
                }
            }
        } finally {
            if (foregroundStarted) {
                runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
            }
            terminateSafely(latestReceivedStartId)
        }
        return START_NOT_STICKY
    }

    private fun terminateSafely(startId: Int) {
        if (startId > 0) {
            stopSelfResult(startId)
        } else {
            stopSelf()
        }
    }

    private suspend fun verifyOccurrenceFresh(
        id: Long = alarmId,
        expectedTargetMs: Long = targetTimeMs,
        expGen: Int = expectedGeneration,
        expEpoch: Long = expectedRestoreEpoch,
        expRev: Long = expectedScheduleRevision
    ): Boolean {
        if (id <= 0L || expectedTargetMs <= 0L) return false
        val alarm = try {
            alarmRepository.getById(id)
        } catch (_: Throwable) {
            return false
        } ?: return false
        if (!alarm.isEnabled) return false
        if (expGen > 0) {
            if (!alarm.smartAlarmEnabled) return false
            if (!alarm.isQuickAlarm && alarm.generation != expGen) return false
            if (alarm.nextTriggerTime != expectedTargetMs) return false
        } else if (alarm.nextTriggerTime > 0L && alarm.nextTriggerTime != expectedTargetMs) {
            return false
        }
        if (expEpoch > 0L) {
            val currentEpoch = try {
                alarmRepository.database.scheduleAuthorityDao().getMetaById(1)?.restoreEpoch
            } catch (_: Throwable) {
                null
            }
            if (currentEpoch == null || currentEpoch <= 0L || currentEpoch != expEpoch) {
                return false
            }
        }
        if (expRev > 0L) {
            val auth = try {
                alarmRepository.getAuthorityByAlarmId(id)
            } catch (_: Throwable) {
                null
            }
            if (auth != null && auth.scheduleRevision != expRev) {
                return false
            }
        }
        return true
    }

    override fun onSensorChanged(event: SensorEvent?) {
        event ?: return
        if (event.sensor.type != Sensor.TYPE_ACCELEROMETER) return

        val now = System.currentTimeMillis()

        // If we've passed the scheduled alarm time, stop — regular alarm will fire
        if (now >= targetTimeMs) {
            val scored = ActigraphySleepClassifier.classify(actigraphyEpochs)
            finishMonitoring(
                firedEarly = false,
                launchAlarm = false,
                decisionResult = SmartWakeDecisionEngine.reachedTarget(scored)
            )
            return
        }

        val x = event.values[0]; val y = event.values[1]; val z = event.values[2]
        val mag = sqrt(x * x + y * y + z * z)
        val delta = abs(mag - SensorManager.GRAVITY_EARTH)

        if (delta > windowMotionMax) windowMotionMax = delta

        // Check window boundary
        if (now - windowStartMs >= WINDOW_MS) {
            val appendedMinute = appendActigraphyWindow(windowStartMs, windowMotionMax)
            if (appendedMinute) {
                val decision = SmartWakeDecisionEngine.decide(
                    scoredEpochs = ActigraphySleepClassifier.classify(actigraphyEpochs),
                    sessionStartMs = sessionStartMs,
                    nowMs = now,
                    targetTimeMs = targetTimeMs
                )
                latestDecision = decision
                if (decision.decision == SmartWakeDecision.FIRE_EARLY) {
                    fireAlarmEarly(decision)
                    return
                }
            }
            windowMotionMax = 0f
            windowStartMs = now
        }
    }

    private fun fireAlarmEarly(decisionResult: SmartWakeDecisionResult) {
        finishMonitoring(firedEarly = true, launchAlarm = true, decisionResult = decisionResult)
    }

    private fun appendActigraphyWindow(startMillis: Long, motionMax: Float): Boolean {
        halfMinuteMotion.add(motionMax)
        if (halfMinuteMotion.size < 2) return false

        val minuteMotion = halfMinuteMotion.maxOrNull() ?: 0f
        val minuteStart = startMillis - WINDOW_MS
        actigraphyEpochs.add(
            ActigraphyEpoch(
                startMillis = minuteStart,
                activityCount = ActigraphySleepClassifier.phoneMotionToActivityCount(minuteMotion)
            )
        )
        halfMinuteMotion.clear()
        return true
    }

    private fun finishMonitoring(
        firedEarly: Boolean,
        launchAlarm: Boolean,
        decisionResult: SmartWakeDecisionResult? = null
    ) {
        if (!sessionClosed.compareAndSet(false, true)) return
        sensorManager?.unregisterListener(this)
        val endedAt = System.currentTimeMillis()
        val epochs = actigraphyEpochs.toList()
        if (epochs.isEmpty()) {
            if (launchAlarm) startAlarmService()
            stopSelf()
            return
        }
        val scored = ActigraphySleepClassifier.classify(epochs)
        val summary = ActigraphySleepClassifier.summarizeScored(scored)
        val finalDecision = decisionResult
            ?: latestDecision
            ?: SmartWakeDecisionEngine.reachedTarget(scored)
        serviceScope.launch {
            val isFresh = if (launchAlarm) verifyOccurrenceFresh() else true
            runCatching {
                actigraphyRepository.record(
                    alarmId = alarmId,
                    startedAt = sessionStartMs,
                    endedAt = endedAt,
                    targetTime = targetTimeMs,
                    firedEarly = firedEarly && isFresh,
                    summary = summary,
                    decisionReason = finalDecision.reasonCode,
                    observedMinutesBeforeDecision = finalDecision.observedMinutes,
                    smartWakeMode = finalDecision.mode
                )
            }
            withContext(Dispatchers.Main) {
                if (launchAlarm && isFresh) startAlarmService()
                stopSelf()
            }
        }
    }

    private fun startAlarmService() {
        val isFresh = try {
            kotlinx.coroutines.runBlocking { verifyOccurrenceFresh() }
        } catch (_: Throwable) {
            false
        }
        if (!isFresh) {
            android.util.Log.w("SmartAlarmService", "Rejecting stale/disabled SmartAlarm fire for alarm $alarmId @ $targetTimeMs")
            return
        }

        val gate = smartAlarmOutputAuthorityGateSeam ?: com.sysadmindoc.alarmclock.directboot.outputauthority.SharedOutputAuthorityGate.getInstance(this)
        var claimNonce: String? = null
        val occKey = if (gate.isEnabled) {
            if (alarmId <= 0L || targetTimeMs <= 0L) {
                android.util.Log.w("SmartAlarmService", "Authority reservation rejected: invalid occurrence identity (alarmId=$alarmId, targetTimeMs=$targetTimeMs)")
                return
            }
            val key = com.sysadmindoc.alarmclock.directboot.OutputOccurrenceKey(alarmId, targetTimeMs, 0)
            val res = gate.reserveForServiceStart(this, key, com.sysadmindoc.alarmclock.directboot.LegacyClaimSource.SMART_ALARM)
            if (res !is com.sysadmindoc.alarmclock.directboot.outputauthority.OutputAuthorizationResult.Authorized) {
                android.util.Log.w("SmartAlarmService", "Authority reservation rejected for early fire alarm $alarmId: $res")
                return
            }
            claimNonce = res.activeNonce
            key
        } else {
            null
        }

        val fireId = com.sysadmindoc.alarmclock.data.local.entity.AlarmIncidentEvent.fireIdFor(alarmId, targetTimeMs)
        val intent = Intent(this, AlarmService::class.java).apply {
            action = AlarmService.ACTION_START_ALARM
            putExtra(AlarmScheduler.EXTRA_ALARM_ID, alarmId)
            // Thread the original occurrence through so the post-dismiss
            // reschedule floors "next" past it. Without this a recurring
            // alarm dismissed after an early fire recomputes today's
            // still-future original minute and rings a second time.
            putExtra(AlarmScheduler.EXTRA_SCHEDULED_AT, targetTimeMs)
            putExtra(AlarmScheduler.EXTRA_ALARM_FIRE_ID, fireId)
            if (claimNonce != null) {
                putExtra(AlarmService.EXTRA_CLAIM_NONCE, claimNonce)
            }
        }

        try {
            startForegroundService(intent)
        } catch (e: Exception) {
            android.util.Log.e("SmartAlarmService", "startForegroundService failed for early fire alarm $alarmId", e)
            var fallbackPosted = false
            try {
                val settings = preferencesManager.getCachedSettings()
                val alarm = kotlinx.coroutines.runBlocking { alarmRepository.getById(alarmId) }
                val roomChecker = smartAlarmRoomReceiptCheckerSeam ?: try {
                    val dao = alarmRepository.database.directBootOccurrenceReceiptDao()
                    val checker: (Long, Long) -> Boolean = { aId, sAt ->
                        dao.findByAlarmId(aId).any { it.triggerTime == sAt }
                    }
                    checker
                } catch (_: Throwable) { null }

                if (alarm != null) {
                    fallbackPosted = AlarmFullScreenFallback.post(
                        context = this,
                        alarmId = alarm.id,
                        scheduledAt = targetTimeMs,
                        fireId = fireId,
                        hideLabel = settings.hideAlarmLabelsOnPublicSurfaces,
                        label = alarm.label,
                        claimNonce = claimNonce,
                        refireCount = 0,
                        roomReceiptChecker = roomChecker,
                        authorityGate = if (gate.isEnabled) gate else null
                    )
                }
            } catch (fallbackErr: Exception) {
                android.util.Log.e("SmartAlarmService", "Fallback notification post failed for early fire alarm $alarmId", fallbackErr)
            }
            if (!fallbackPosted && occKey != null && claimNonce != null) {
                val aborted = gate.abortClaim(this, occKey, claimNonce, com.sysadmindoc.alarmclock.directboot.LegacyTerminalOutcome.REJECTED_NO_OUTPUT)
                if (!aborted) {
                    android.util.Log.e("SmartAlarmService", "abortClaim failed for early fire alarm $alarmId; preserving fail-closed state")
                    alarmIncidentRepository.recordAsync(
                        alarmId = alarmId,
                        fireId = fireId,
                        scheduledAt = targetTimeMs,
                        type = com.sysadmindoc.alarmclock.data.local.entity.AlarmIncidentEvent.TYPE_FOREGROUND_SERVICE,
                        status = com.sysadmindoc.alarmclock.data.local.entity.AlarmIncidentEvent.STATUS_FAILED,
                        reasonCode = "SMART_ALARM_ABORT_CLAIM_FAILED",
                        source = "SmartAlarmService"
                    )
                }
            }
        }
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        val scored = ActigraphySleepClassifier.classify(actigraphyEpochs)
        finishMonitoring(
            firedEarly = false,
            launchAlarm = false,
            decisionResult = SmartWakeDecisionEngine.serviceTimeout(scored)
        )
        super.onTimeout(startId, fgsType)
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    override fun onDestroy() {
        sensorManager?.unregisterListener(this)
        wakeLock?.let { if (it.isHeld) it.release() }
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun createNotificationChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        val ch = NotificationChannel(
            CHANNEL_SMART,
            getString(R.string.notif_smart_alarm_channel_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(R.string.notif_smart_alarm_channel_description)
        }
        nm.createNotificationChannel(ch)
    }
}
