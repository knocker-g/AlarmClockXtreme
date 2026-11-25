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
import androidx.annotation.VisibleForTesting
import androidx.core.app.NotificationCompat
import androidx.core.os.UserManagerCompat
import com.sysadmindoc.alarmclock.BuildConfig
import com.sysadmindoc.alarmclock.R
import com.sysadmindoc.alarmclock.directboot.outputauthority.ActiveOutputHandle
import com.sysadmindoc.alarmclock.directboot.outputauthority.OutputAuthorizationResult
import com.sysadmindoc.alarmclock.directboot.outputauthority.OutputSinkType
import com.sysadmindoc.alarmclock.directboot.outputauthority.SharedOutputAuthorityGate
import com.sysadmindoc.alarmclock.service.AlarmAudioRouting
import com.sysadmindoc.alarmclock.service.AlarmService
import com.sysadmindoc.alarmclock.worker.AlarmHealthWorker

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
    private var currentActiveNonce: String? = null
    private var currentActiveOccurrenceKey: OutputOccurrenceKey? = null
    private var currentV2OwnerSeq: Long = 0L

    @VisibleForTesting
    internal var outputAuthorityGate: SharedOutputAuthorityGate? = null

    @VisibleForTesting
    internal var bootProvenanceProvider: ((Context) -> BootProvenance)? = null

    @VisibleForTesting
    internal var beforeV2OwnerPromoteHook: (() -> Unit)? = null

    @VisibleForTesting
    internal var v2DeliveryCapableProvider: () -> Boolean = { false }

    internal fun getGate(): SharedOutputAuthorityGate =
        outputAuthorityGate ?: SharedOutputAuthorityGate.getInstance(this)

    private fun isV2OutputCapableAndAuthorized(
        deContext: Context,
        candidate: IncomingStartCandidate,
        gate: SharedOutputAuthorityGate
    ): Boolean {
        if (v2DeliveryCapableProvider() && gate.isEnabled) return true
        return BuildConfig.DEBUG &&
            DirectBootAlarmCache.isD4OccurrenceAuthorizedForService(deContext, candidate)
    }

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
                } else {
                    if (!stopToken.isNullOrBlank() || stopEpoch > 0L || stopGen > 0) {
                        Log.w(
                            TAG,
                            "Cross-lane V2 ACTION_STOP rejected for active Legacy V1 alarm $currentAlarmId"
                        )
                        return START_NOT_STICKY
                    }
                    if (intent.hasExtra(DirectBootAlarmReceiver.EXTRA_TRIGGER_TIME) &&
                        currentTriggerTime > 0L &&
                        stopTrig != currentTriggerTime
                    ) {
                        Log.w(
                            TAG,
                            "Stale Legacy V1 ACTION_STOP rejected for alarm $currentAlarmId: stopTrig=$stopTrig != active=$currentTriggerTime"
                        )
                        return START_NOT_STICKY
                    }
                }

                completeGateTerminalIfOwned(LegacyTerminalOutcome.CONSUMED_DISMISSED)
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
            ServiceStartDecision.V2_DURABLE_OWNER_AUTHORIZED -> {
                val gate = getGate()
                val deContext = if (isDeviceProtectedStorage) this else createDeviceProtectedStorageContext()
                if (!isV2OutputCapableAndAuthorized(deContext, candidate, gate)) {
                    Log.w(
                        TAG,
                        "V2 durable-owner output blocked by security boundary: " +
                            "v2Capable=${v2DeliveryCapableProvider()}, gateEnabled=${gate.isEnabled}"
                    )
                    val ownerRead = DirectBootDurableOwnerStore.read(deContext)
                    val handoverOwner = (ownerRead as? OwnerReadResult.Success)?.owner
                    if (handoverOwner != null) {
                        releaseHandoverPendingIfMatching(deContext, handoverOwner, candidate)
                    }
                    if (!activeSnapshot.isActive) {
                        stopSelf()
                    }
                    return
                }
                if (decisionResult.lane != CandidateLane.V2_QUALIFIED ||
                    candidate.alarmId <= 0L ||
                    candidate.triggerTime <= 0L ||
                    candidate.generation < 1 ||
                    candidate.instanceToken.isBlank() ||
                    candidate.restoreEpoch <= 0L
                ) {
                    Log.e(
                        TAG,
                        "Fail-closed guard blocked malformed V2 durable-owner admission: " +
                            "lane=${decisionResult.lane}, candidateAlarmId=${candidate.alarmId}"
                    )
                    if (!activeSnapshot.isActive) {
                        stopSelf()
                    }
                    return
                }
                startV2AuthorizedAlarm(
                    intent = intent,
                    candidate = candidate,
                    activeSnapshot = activeSnapshot
                )
                return
            }
        }

        val timeLabel = try {
            intent.getStringExtra(DirectBootAlarmReceiver.EXTRA_TIME_LABEL).orEmpty()
        } catch (_: Exception) {
            ""
        }

        val gate = getGate()
        var claimedKey: OutputOccurrenceKey? = null
        var claimedNonce: String? = null
        if (gate.isEnabled) {
            val incomingNonce = try {
                intent.getStringExtra(AlarmService.EXTRA_CLAIM_NONCE)
            } catch (_: Exception) {
                null
            }
            if (incomingNonce.isNullOrBlank()) {
                Log.w(TAG, "DirectBoot ACTION_START blocked: claimNonce missing while SharedOutputAuthorityGate is enabled")
                if (!activeSnapshot.isActive) {
                    stopSelf()
                }
                return
            }
            val key = OutputOccurrenceKey(
                alarmId = candidate.alarmId,
                scheduledAt = candidate.triggerTime,
                refireCount = 0
            )
            val auth = gate.claimOutput(
                context = this,
                key = key,
                incomingNonce = incomingNonce,
                sink = OutputSinkType.DIRECT_BOOT_SERVICE_1011,
                directBootModeOnly = true,
                roomReceiptChecker = null,
                stopPreemptedOwner = { handlePreemptedOwner(it) }
            )
            if (auth !is OutputAuthorizationResult.Authorized) {
                Log.w(TAG, "DirectBoot ACTION_START blocked by authority gate claim: $auth")
                if (!activeSnapshot.isActive) {
                    stopSelf()
                }
                return
            }
            val obs = gate.observePhysicalOutput(
                context = this,
                key = key,
                activeNonce = auth.activeNonce,
                sink = OutputSinkType.DIRECT_BOOT_SERVICE_1011,
                roomReceiptChecker = null
            )
            if (obs !is OutputAuthorizationResult.Authorized) {
                Log.w(TAG, "DirectBoot ACTION_START blocked by authority gate observePhysicalOutput: $obs")
                gate.abortClaim(this, key, auth.activeNonce, LegacyTerminalOutcome.REJECTED_NO_OUTPUT)
                if (!activeSnapshot.isActive) {
                    stopSelf()
                }
                return
            }
            claimedKey = key
            claimedNonce = auth.activeNonce
        }

        // Legacy V1 Handover Sequence: Clean up resource claims of active V1 alarm before taking over
        completeGateTerminalIfOwned(LegacyTerminalOutcome.CONSUMED_PREEMPTED)
        stopDefaultAlarmSoundInternal()
        stopVibrationInternal()
        autoStopRunnable?.let { handler.removeCallbacks(it) }

        currentAlarmId = candidate.alarmId
        currentTriggerTime = candidate.triggerTime
        currentGeneration = candidate.generation
        currentInstanceToken = ""
        currentRestoreEpoch = 0L
        currentV2OwnerSeq = 0L
        currentActiveOccurrenceKey = claimedKey
        currentActiveNonce = claimedNonce

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
                completeGateTerminalIfOwned(LegacyTerminalOutcome.CONSUMED_AUTO_SILENCED)
                stopAlarm()
                stopSelf()
            } else {
                Log.w(TAG, "Stale AutoStop runnable ignored for alarm $targetId gen $targetGen")
            }
        }
        handler.postDelayed(autoStopRunnable!!, AUTO_STOP_MS)
    }

    private fun startV2AuthorizedAlarm(
        intent: Intent,
        candidate: IncomingStartCandidate,
        activeSnapshot: ActiveServiceSnapshot
    ) {
        val deContext = if (isDeviceProtectedStorage) this else createDeviceProtectedStorageContext()
        val currentBoot = currentBootProvenance()

        // 1. Re-verify HANDOVER_PENDING owner & same-boot provenance right before Arbiter & CAS promotion
        val ownerRead = DirectBootDurableOwnerStore.read(deContext)
        val handoverOwner = (ownerRead as? OwnerReadResult.Success)?.owner
        val claim = handoverOwner?.handoverClaim
        val claimMatches = handoverOwner != null &&
            handoverOwner.state == OwnerState.HANDOVER_PENDING &&
            DirectBootDurableOwnerStore.isSameBootProvenance(handoverOwner.bootProvenance, currentBoot) &&
            claim != null &&
            claim.alarmId == candidate.alarmId &&
            claim.triggerTime == candidate.triggerTime &&
            claim.generation == candidate.generation &&
            claim.instanceToken == candidate.instanceToken &&
            claim.restoreEpoch == candidate.restoreEpoch

        if (!claimMatches) {
            Log.w(
                TAG,
                "V2 ACTION_START failed-closed: HANDOVER_PENDING claim missing, non-matching, or stale boot provenance (alarmId=${candidate.alarmId})"
            )
            if (!activeSnapshot.isActive) {
                stopSelf()
            }
            return
        }

        // 2. OutputResourceArbiter acquisition (MANDATORY for V2 output under S4), strictly OUTSIDE DirectBootStorageLock
        val gate = getGate()
        if (!isV2OutputCapableAndAuthorized(deContext, candidate, gate)) {
            Log.w(
                TAG,
                "V2 ACTION_START blocked by security boundary: v2Capable=${v2DeliveryCapableProvider()}, gateEnabled=${gate.isEnabled}"
            )
            releaseHandoverPendingIfMatching(deContext, handoverOwner, candidate)
            if (!activeSnapshot.isActive) {
                stopSelf()
            }
            return
        }

        val wasV2Owned = currentV2OwnerSeq > 0L
        val key = OutputOccurrenceKey(
            alarmId = candidate.alarmId,
            scheduledAt = candidate.triggerTime,
            refireCount = 0
        )
        val nonce = gate.nextNonce("v2")
        val (acquired, preempted) = gate.arbiter.acquirePending(
            key = key,
            sink = OutputSinkType.DIRECT_BOOT_SERVICE_1011,
            activeNonce = nonce,
            stopPreemptedOwner = { handlePreemptedOwner(it) }
        )
        if (!acquired) {
            Log.w(TAG, "V2 ACTION_START blocked: OutputResourceArbiter failed to acquire or confirm stop of prior owner for $key")
            releaseHandoverPendingIfMatching(deContext, handoverOwner, candidate)
            if (!activeSnapshot.isActive) {
                stopSelf()
            }
            return
        }
        if (gate.isEnabled && preempted != null && preempted.key != key && !wasV2Owned) {
            gate.completeTerminal(this, preempted.key, preempted.activeNonce, LegacyTerminalOutcome.CONSUMED_PREEMPTED)
        }
        val v2ArbiterKey = key
        val v2ArbiterNonce = nonce

        // 3. CAS Promote HANDOVER_PENDING -> V2_ACTIVE
        beforeV2OwnerPromoteHook?.invoke()
        val now = System.currentTimeMillis()
        val activeOwnerPayload = DirectBootDurableOwner(
            schemaVersion = DirectBootDurableOwnerStore.CURRENT_SCHEMA_VERSION,
            state = OwnerState.V2_ACTIVE,
            ownerSeq = handoverOwner.ownerSeq,
            bootProvenance = currentBoot,
            handoverClaim = null,
            activeSession = ActiveOwnerSession(
                alarmId = candidate.alarmId,
                triggerTime = candidate.triggerTime,
                generation = candidate.generation,
                instanceToken = candidate.instanceToken,
                restoreEpoch = candidate.restoreEpoch
            ),
            updatedAtEpochMs = now
        )
        val promoteRes = DirectBootDurableOwnerStore.write(
            context = deContext,
            owner = activeOwnerPayload,
            expectedOwnerSeq = handoverOwner.ownerSeq
        )
        if (promoteRes !is OwnerWriteResult.Success) {
            Log.w(TAG, "V2 ACTION_START blocked: CAS promotion HANDOVER_PENDING -> V2_ACTIVE failed: $promoteRes")
            if (v2ArbiterKey != null && v2ArbiterNonce != null) {
                gate.arbiter.releaseIfMatches(v2ArbiterKey, v2ArbiterNonce)
            }
            if (!activeSnapshot.isActive) {
                stopSelf()
            }
            return
        }
        val promotedOwner = promoteRes.owner

        // 4. Stop any prior local Alarm 1 playback/autoStop now that Alarm 2 owns V2_ACTIVE
        autoStopRunnable?.let { handler.removeCallbacks(it) }
        autoStopRunnable = null
        stopDefaultAlarmSoundInternal()
        stopVibrationInternal()

        // 5. Reserve Fired Witness slot (OUTCOME_OUTPUT_STATUS_UNKNOWN) before starting foreground/audio
        val deliveryMode = when {
            candidate.playDefaultSound && candidate.vibrationEnabled -> DirectBootFiredWitnessRecord.MODE_SOUND_AND_VIBRATION
            candidate.playDefaultSound -> DirectBootFiredWitnessRecord.MODE_SOUND_ONLY
            candidate.vibrationEnabled -> DirectBootFiredWitnessRecord.MODE_VIBRATION_ONLY
            else -> DirectBootFiredWitnessRecord.MODE_SILENT_NOTIFICATION
        }
        val reservationRecord = DirectBootFiredWitnessRecord.createNormal(
            alarmId = candidate.alarmId,
            triggerTime = candidate.triggerTime,
            generation = candidate.generation,
            instanceToken = candidate.instanceToken,
            restoreEpoch = candidate.restoreEpoch,
            deliveryMode = deliveryMode,
            deliveryOutcome = DirectBootFiredWitnessRecord.OUTCOME_OUTPUT_STATUS_UNKNOWN,
            observedAtEpochMs = now
        )
        val reserveRes = DirectBootFiredWitnessLedger.reserveWitnessSlot(deContext, reservationRecord)
        val reserveOk = reserveRes is WitnessAppendResult.Appended || reserveRes is WitnessAppendResult.DuplicateIgnored
        if (!reserveOk) {
            Log.w(TAG, "V2 ACTION_START witness reservation failed ($reserveRes); rolling back V2_ACTIVE -> IDLE")
            releaseV2ActiveOwnerIfMatching(deContext, promotedOwner.ownerSeq, candidate)
            DirectBootMissedStore.recordMissed(
                deContext,
                DirectBootMissedRecord.createNormal(
                    alarmId = candidate.alarmId,
                    triggerTime = candidate.triggerTime,
                    missedAt = now,
                    generation = candidate.generation,
                    instanceToken = candidate.instanceToken,
                    classification = DirectBootMissedRecord.CLASSIFICATION_SERVICE_START_FAILED,
                    restoreEpoch = candidate.restoreEpoch
                )
            )
            if (v2ArbiterKey != null && v2ArbiterNonce != null) {
                gate.arbiter.releaseIfMatches(v2ArbiterKey, v2ArbiterNonce)
            }
            clearActiveFields()
            stopSelf()
            return
        }

        // 6. Activate V2 identity, foreground notification, sound, and vibration
        val timeLabel = try {
            intent.getStringExtra(DirectBootAlarmReceiver.EXTRA_TIME_LABEL).orEmpty()
        } catch (_: Exception) {
            ""
        }

        currentAlarmId = candidate.alarmId
        currentTriggerTime = candidate.triggerTime
        currentGeneration = candidate.generation
        currentInstanceToken = candidate.instanceToken
        currentRestoreEpoch = candidate.restoreEpoch
        currentV2OwnerSeq = promotedOwner.ownerSeq
        currentActiveOccurrenceKey = v2ArbiterKey
        currentActiveNonce = v2ArbiterNonce

        startForeground(NOTIFICATION_ID, buildNotification(timeLabel))

        val soundStarted = if (candidate.playDefaultSound) startDefaultAlarmSound() else false
        val vibStarted = if (candidate.vibrationEnabled) startVibration() else false

        val deliveryOutcome = when {
            !candidate.playDefaultSound && !candidate.vibrationEnabled ->
                DirectBootFiredWitnessRecord.OUTCOME_NO_OUTPUT_REQUESTED
            candidate.playDefaultSound && candidate.vibrationEnabled -> when {
                soundStarted && vibStarted -> DirectBootFiredWitnessRecord.OUTCOME_OUTPUT_START_ACCEPTED
                soundStarted || vibStarted -> DirectBootFiredWitnessRecord.OUTCOME_PARTIAL_OUTPUT_ACCEPTED
                else -> DirectBootFiredWitnessRecord.OUTCOME_OUTPUT_START_FAILED
            }
            candidate.playDefaultSound ->
                if (soundStarted) DirectBootFiredWitnessRecord.OUTCOME_OUTPUT_START_ACCEPTED
                else DirectBootFiredWitnessRecord.OUTCOME_OUTPUT_START_FAILED
            else ->
                if (vibStarted) DirectBootFiredWitnessRecord.OUTCOME_OUTPUT_START_ACCEPTED
                else DirectBootFiredWitnessRecord.OUTCOME_OUTPUT_START_FAILED
        }

        val updateRes = DirectBootFiredWitnessLedger.updateWitnessOutcome(
            context = deContext,
            recordId = reservationRecord.recordId,
            deliveryMode = deliveryMode,
            deliveryOutcome = deliveryOutcome
        )
        if (updateRes !is WitnessUpdateResult.Updated) {
            Log.w(TAG, "V2 Witness outcome update failed ($updateRes); preserving UNKNOWN witness reservation")
        } else {
            if (UserManagerCompat.isUserUnlocked(this) &&
                DirectBootUnlockReceiver.hasUnretiredDeRecords(deContext)
            ) {
                AlarmHealthWorker.enqueueImmediate(this)
            }
        }

        if (deliveryOutcome == DirectBootFiredWitnessRecord.OUTCOME_OUTPUT_START_FAILED) {
            Log.w(TAG, "V2 total output failure for alarmId=${candidate.alarmId}; releasing V2_ACTIVE -> IDLE and stopping")
            stopAlarm()
            stopSelf()
            return
        }

        if (!gate.arbiter.confirmObserved(v2ArbiterKey, v2ArbiterNonce)) {
            Log.w(TAG, "V2 Arbiter confirmObserved failed for $v2ArbiterKey; stopping V2 alarm")
            stopAlarm()
            stopSelf()
            return
        }

        // 7. Schedule AutoStop with 5-field active lineage fencing
        val targetId = currentAlarmId
        val targetTrig = currentTriggerTime
        val targetGen = currentGeneration
        val targetToken = currentInstanceToken
        val targetEpoch = currentRestoreEpoch
        autoStopRunnable = Runnable {
            if (currentAlarmId == targetId &&
                currentTriggerTime == targetTrig &&
                currentGeneration == targetGen &&
                currentInstanceToken == targetToken &&
                currentRestoreEpoch == targetEpoch
            ) {
                completeGateTerminalIfOwned(LegacyTerminalOutcome.CONSUMED_AUTO_SILENCED)
                stopAlarm()
                stopSelf()
            } else {
                Log.w(TAG, "Stale V2 AutoStop runnable ignored for alarm $targetId gen $targetGen")
            }
        }
        handler.postDelayed(autoStopRunnable!!, AUTO_STOP_MS)
    }

    private fun currentBootProvenance(): BootProvenance {
        bootProvenanceProvider?.let { return it(this) }
        val bootCount = try {
            android.provider.Settings.Global.getInt(
                contentResolver,
                android.provider.Settings.Global.BOOT_COUNT
            )
        } catch (_: Throwable) {
            null
        }
        val elapsed = android.os.SystemClock.elapsedRealtime().coerceAtLeast(0L)
        val bootEpoch = (System.currentTimeMillis() - elapsed).coerceAtLeast(0L)
        return BootProvenance(
            bootCount = bootCount,
            lastElapsedRealtime = elapsed,
            bootEpochEstimateMs = bootEpoch
        )
    }

    private fun releaseHandoverPendingIfMatching(
        deContext: Context,
        expectedOwner: DirectBootDurableOwner,
        candidate: IncomingStartCandidate
    ) {
        val claim = expectedOwner.handoverClaim ?: return
        if (expectedOwner.state != OwnerState.HANDOVER_PENDING ||
            claim.alarmId != candidate.alarmId ||
            claim.triggerTime != candidate.triggerTime ||
            claim.generation != candidate.generation ||
            claim.instanceToken != candidate.instanceToken ||
            claim.restoreEpoch != candidate.restoreEpoch
        ) {
            return
        }
        val idleOwner = DirectBootDurableOwner(
            schemaVersion = DirectBootDurableOwnerStore.CURRENT_SCHEMA_VERSION,
            state = OwnerState.IDLE,
            ownerSeq = expectedOwner.ownerSeq,
            bootProvenance = expectedOwner.bootProvenance,
            handoverClaim = null,
            activeSession = null,
            updatedAtEpochMs = System.currentTimeMillis()
        )
        val writeRes = DirectBootDurableOwnerStore.write(deContext, idleOwner, expectedOwnerSeq = expectedOwner.ownerSeq)
        if (writeRes is OwnerWriteResult.Success &&
            UserManagerCompat.isUserUnlocked(this) &&
            DirectBootUnlockReceiver.hasUnretiredDeRecords(deContext)
        ) {
            AlarmHealthWorker.enqueueImmediate(this)
        }
    }

    private fun releaseV2ActiveOwnerIfMatching(
        deContext: Context,
        expectedSeq: Long,
        candidate: IncomingStartCandidate
    ) {
        if (expectedSeq <= 0L) return
        val readRes = DirectBootDurableOwnerStore.read(deContext)
        val owner = (readRes as? OwnerReadResult.Success)?.owner ?: return
        val session = owner.activeSession ?: return
        if (owner.state == OwnerState.V2_ACTIVE &&
            owner.ownerSeq == expectedSeq &&
            session.alarmId == candidate.alarmId &&
            session.triggerTime == candidate.triggerTime &&
            session.generation == candidate.generation &&
            session.instanceToken == candidate.instanceToken &&
            session.restoreEpoch == candidate.restoreEpoch
        ) {
            val idleOwner = DirectBootDurableOwner(
                schemaVersion = DirectBootDurableOwnerStore.CURRENT_SCHEMA_VERSION,
                state = OwnerState.IDLE,
                ownerSeq = expectedSeq,
                bootProvenance = owner.bootProvenance,
                handoverClaim = null,
                activeSession = null,
                updatedAtEpochMs = System.currentTimeMillis()
            )
            val writeRes = DirectBootDurableOwnerStore.write(deContext, idleOwner, expectedOwnerSeq = expectedSeq)
            if (writeRes is OwnerWriteResult.Success &&
                UserManagerCompat.isUserUnlocked(this) &&
                DirectBootUnlockReceiver.hasUnretiredDeRecords(deContext)
            ) {
                AlarmHealthWorker.enqueueImmediate(this)
            }
        }
    }

    private fun clearActiveFields() {
        currentAlarmId = -1L
        currentTriggerTime = 0L
        currentGeneration = 0
        currentInstanceToken = ""
        currentRestoreEpoch = 0L
        currentV2OwnerSeq = 0L
    }

    private fun handlePreemptedOwner(handle: ActiveOutputHandle): Boolean {
        if (handle.activeNonce != currentActiveNonce ||
            (currentAlarmId > 0L && handle.key.alarmId != currentAlarmId)
        ) {
            return false
        }
        autoStopRunnable?.let { handler.removeCallbacks(it) }
        stopDefaultAlarmSoundInternal()
        stopVibrationInternal()
        currentActiveNonce = null
        currentActiveOccurrenceKey = null
        return true
    }

    private fun completeGateTerminalIfOwned(outcome: LegacyTerminalOutcome) {
        val key = currentActiveOccurrenceKey ?: return
        val nonce = currentActiveNonce ?: return
        currentActiveOccurrenceKey = null
        currentActiveNonce = null
        val gate = getGate()
        if (currentV2OwnerSeq > 0L) {
            gate.arbiter.releaseIfMatches(key, nonce)
        } else if (gate.isEnabled) {
            gate.completeTerminal(this, key, nonce, outcome)
        }
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

            val ownerRead = DirectBootDurableOwnerStore.read(deContext)
            val currentBoot = currentBootProvenance()
            val ownerStatus = when (ownerRead) {
                is OwnerReadResult.Absent -> DurableOwnerObservation.UNAVAILABLE_NO_DURABLE_OWNER
                is OwnerReadResult.Corrupt,
                is OwnerReadResult.UnsupportedSchema -> DurableOwnerObservation.OWNER_UNVERIFIABLE
                is OwnerReadResult.Success -> {
                    val owner = ownerRead.owner
                    when (owner.state) {
                        OwnerState.IDLE -> DurableOwnerObservation.UNAVAILABLE_NO_DURABLE_OWNER
                        OwnerState.HANDOVER_PENDING -> {
                            if (!DirectBootDurableOwnerStore.isSameBootProvenance(owner.bootProvenance, currentBoot)) {
                                DurableOwnerObservation.OWNER_UNVERIFIABLE
                            } else {
                                val claim = owner.handoverClaim
                                if (claim != null &&
                                    claim.alarmId == candidate.alarmId &&
                                    claim.triggerTime == candidate.triggerTime &&
                                    claim.generation == candidate.generation &&
                                    claim.instanceToken == candidate.instanceToken &&
                                    claim.restoreEpoch == candidate.restoreEpoch
                                ) {
                                    DurableOwnerObservation.VERIFIED_OWNER_MATCH
                                } else {
                                    DurableOwnerObservation.OWNER_CONFLICT
                                }
                            }
                        }
                        OwnerState.V2_ACTIVE -> {
                            if (!DirectBootDurableOwnerStore.isSameBootProvenance(owner.bootProvenance, currentBoot)) {
                                DurableOwnerObservation.OWNER_UNVERIFIABLE
                            } else {
                                val session = owner.activeSession
                                if (session != null &&
                                    session.alarmId == candidate.alarmId &&
                                    session.triggerTime == candidate.triggerTime &&
                                    session.generation == candidate.generation &&
                                    session.instanceToken == candidate.instanceToken &&
                                    session.restoreEpoch == candidate.restoreEpoch
                                ) {
                                    DurableOwnerObservation.VERIFIED_OWNER_MATCH
                                } else {
                                    DurableOwnerObservation.OWNER_CONFLICT
                                }
                            }
                        }
                    }
                }
            }

            StartStorageObservation(
                witnessStorageStatus = witnessStatus,
                witnessOutcome = witnessOutcome,
                deliveryLedgerStatus = ledgerStatus,
                queueStorageStatus = queueStatus,
                ownerStatus = ownerStatus
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
        completeGateTerminalIfOwned(LegacyTerminalOutcome.CONSUMED_AUTO_SILENCED)
        if (currentV2OwnerSeq > 0L && currentAlarmId > 0L) {
            val deContext = if (isDeviceProtectedStorage) this else createDeviceProtectedStorageContext()
            val activeCandidate = IncomingStartCandidate(
                alarmId = currentAlarmId,
                triggerTime = currentTriggerTime,
                generation = currentGeneration,
                instanceToken = currentInstanceToken,
                restoreEpoch = currentRestoreEpoch
            )
            releaseV2ActiveOwnerIfMatching(deContext, currentV2OwnerSeq, activeCandidate)
        }
        autoStopRunnable?.let { handler.removeCallbacks(it) }
        autoStopRunnable = null
        stopDefaultAlarmSoundInternal()
        stopVibrationInternal()
        clearActiveFields()
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
