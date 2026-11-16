package com.sysadmindoc.alarmclock.receiver

import android.Manifest
import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.sysadmindoc.alarmclock.AlarmClockApp
import com.sysadmindoc.alarmclock.R
import com.sysadmindoc.alarmclock.data.local.entity.AlarmIncidentEvent
import com.sysadmindoc.alarmclock.data.model.Alarm
import com.sysadmindoc.alarmclock.service.AlarmFireDismissContract
import com.sysadmindoc.alarmclock.service.AlarmFullScreenFallback
import com.sysadmindoc.alarmclock.service.AlarmService
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

/**
 * v1.4.0: Safety net for auto-silenced alarms.
 *
 * If the most recent alarm auto-silenced within the last 10 minutes and the
 * user has the repeat-missed setting enabled, re-fire that alarm once when
 * the user unlocks the device or unplugs it from power. Clears the persisted
 * state on every fire so a single missed alarm can only re-trigger once.
 */
@org.jetbrains.annotations.VisibleForTesting
internal var missedAlarmOutputAuthorityGateSeam: com.sysadmindoc.alarmclock.directboot.outputauthority.SharedOutputAuthorityGate? = null

@org.jetbrains.annotations.VisibleForTesting
internal var missedAlarmRoomReceiptCheckerSeam: ((Long, Long) -> Boolean)? = null

@org.jetbrains.annotations.VisibleForTesting
internal var missedAlarmTestAlarmRepository: com.sysadmindoc.alarmclock.data.repository.AlarmRepository? = null

@org.jetbrains.annotations.VisibleForTesting
internal var missedAlarmTestIncidentRepository: com.sysadmindoc.alarmclock.data.repository.AlarmIncidentRepository? = null

@org.jetbrains.annotations.VisibleForTesting
internal var missedAlarmTestPreferencesManager: com.sysadmindoc.alarmclock.data.preferences.PreferencesManager? = null

class MissedAlarmUnlockReceiver : BroadcastReceiver() {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (!MissedAlarmReplayPolicy.isReplayTrigger(action)) return
        val incidentSource = MissedAlarmReplayPolicy.sourceForTrigger(action)

        val pending = goAsync()
        scope.launch {
            val claimToken = MissedAlarmReplayPolicy.tryAcquireClaim()
                ?: run {
                    pending?.finish()
                    return@launch
                }
            try {
                withTimeout(8_000L) {
                    val ep = try {
                        EntryPointAccessors.fromApplication(
                            context.applicationContext,
                            AlarmClockApp.AppEntryPoint::class.java
                        )
                    } catch (_: Throwable) { null }

                    val prefsMgr = missedAlarmTestPreferencesManager ?: try {
                        EntryPointAccessors.fromApplication(
                            context.applicationContext,
                            PreferencesEntryPoint::class.java
                        ).preferencesManager()
                    } catch (_: Throwable) { null }

                    val alarmRepo = missedAlarmTestAlarmRepository ?: ep?.alarmRepository()
                    val incidentRepo = missedAlarmTestIncidentRepository ?: ep?.alarmIncidentRepository()

                    if (prefsMgr == null || alarmRepo == null || incidentRepo == null) {
                        Log.w("MissedAlarmUnlockReceiver", "Dependencies unavailable for missed alarm replay")
                        return@withTimeout
                    }

                    val settings = prefsMgr.getCurrentSettings()

                    val store = context.applicationContext
                        .getSharedPreferences("missed_alarm_state", Context.MODE_PRIVATE)
                    val at = store.getLong("last_missed_at", 0L)
                    val id = store.getLong("last_missed_id", -1L)

                    val decision = MissedAlarmReplayPolicy.shouldReplay(
                        repeatMissedEnabled = settings.repeatMissedAlarms,
                        lastMissedAtMs = at,
                        lastMissedId = id,
                        alarmCurrentlyFiringId = AlarmService.activeAlarmId,
                        nowMs = System.currentTimeMillis()
                    )
                    if (decision.shouldClearState) {
                        clearMissedAlarmMarkerIfMatching(store, id, at)
                        return@withTimeout
                    }
                    if (!decision.shouldReplay) return@withTimeout

                    val alarm = alarmRepo.getById(id)
                        ?.takeIf { it.isEnabled }
                        ?: run {
                            clearMissedAlarmMarkerIfMatching(store, id, at)
                            return@withTimeout
                        }

                    val fireId = AlarmFireDismissContract.fireId(alarm.id, at)

                    // Check 1: If positive delivery proof already exists for this occurrence, consume and exit
                    if (incidentRepo.occurrenceDeliveryCount(alarm.id, at) > 0) {
                        clearMissedAlarmMarkerIfMatching(store, id, at)
                        return@withTimeout
                    }

                    // Check 2: If a previous dispatch was initiated recently, wait for late positive proof before re-dispatching
                    val lastDispatchedAt = store.getLong("last_missed_dispatched_at", 0L)
                    val nowMs = System.currentTimeMillis()
                    if (lastDispatchedAt > 0L && (nowMs - lastDispatchedAt) < 15_000L) {
                        val lateProofFound = awaitPositiveDeliveryProof(
                            repo = incidentRepo,
                            alarmId = alarm.id,
                            scheduledAt = at,
                            timeoutMs = 2_500L
                        )
                        if (lateProofFound) {
                            clearMissedAlarmMarkerIfMatching(store, id, at)
                            return@withTimeout
                        }
                    }

                    // Record dispatch attempt timestamp under lock
                    MissedAlarmReplayPolicy.recordDispatchTimestamp(store, System.currentTimeMillis())

                    val gate = missedAlarmOutputAuthorityGateSeam ?: com.sysadmindoc.alarmclock.directboot.outputauthority.SharedOutputAuthorityGate.getInstance(context)
                    var claimNonce: String? = null
                    val occKey = if (gate.isEnabled) {
                        if (alarm.id <= 0L || at <= 0L) {
                            Log.w("MissedAlarmUnlockReceiver", "Authority reservation rejected: invalid occurrence identity (alarmId=${alarm.id}, at=$at)")
                            clearMissedAlarmMarkerIfMatching(store, id, at)
                            return@withTimeout
                        }
                        val roomChecker = missedAlarmRoomReceiptCheckerSeam ?: try {
                            val dao = alarmRepo.database.directBootOccurrenceReceiptDao()
                            val checker: (Long, Long) -> Boolean = { aId, sAt ->
                                dao.findByAlarmId(aId).any { it.triggerTime == sAt }
                            }
                            checker
                        } catch (_: Throwable) { null }

                        if (roomChecker == null) {
                            Log.w("MissedAlarmUnlockReceiver", "Authority reservation rejected: roomReceiptChecker unavailable when gate is enabled")
                            return@withTimeout
                        }

                        val receiptExists = try { roomChecker(alarm.id, at) } catch (_: Throwable) { true }
                        if (receiptExists) {
                            Log.w("MissedAlarmUnlockReceiver", "Authority reservation rejected: occurrence receipt exists in Room for ${alarm.id} @ $at")
                            clearMissedAlarmMarkerIfMatching(store, id, at)
                            return@withTimeout
                        }

                        val key = com.sysadmindoc.alarmclock.directboot.OutputOccurrenceKey(alarm.id, at, 0)
                        val res = gate.reserveForServiceStart(context, key, com.sysadmindoc.alarmclock.directboot.LegacyClaimSource.MISSED_UNLOCK)
                        if (res !is com.sysadmindoc.alarmclock.directboot.outputauthority.OutputAuthorizationResult.Authorized) {
                            Log.w("MissedAlarmUnlockReceiver", "Authority reservation rejected for replay alarm ${alarm.id}: $res")
                            return@withTimeout
                        }
                        claimNonce = res.activeNonce
                        key
                    } else {
                        null
                    }

                    val fireIntent = AlarmFireDismissContract.startServiceIntent(context, alarm.id, at, fireId).apply {
                        if (claimNonce != null) {
                            putExtra(AlarmService.EXTRA_CLAIM_NONCE, claimNonce)
                        }
                    }
                    var deliverySucceeded = false
                    try {
                        context.startForegroundService(fireIntent)
                        incidentRepo.record(
                            alarmId = alarm.id,
                            fireId = fireId,
                            scheduledAt = at,
                            type = AlarmIncidentEvent.TYPE_BROADCAST,
                            status = AlarmIncidentEvent.STATUS_REQUESTED,
                            reasonCode = "MISSED_REPLAY_REQUESTED",
                            source = incidentSource
                        )
                        deliverySucceeded = awaitPositiveDeliveryProof(
                            repo = incidentRepo,
                            alarmId = alarm.id,
                            scheduledAt = at,
                            timeoutMs = 1_500L
                        )
                    } catch (e: Exception) {
                        Log.e("MissedAlarmUnlockReceiver",
                            "startForegroundService failed for replay alarm ${alarm.id}", e)
                        incidentRepo.record(
                            alarmId = alarm.id,
                            fireId = fireId,
                            scheduledAt = at,
                            type = AlarmIncidentEvent.TYPE_FOREGROUND_SERVICE,
                            status = AlarmIncidentEvent.STATUS_FAILED,
                            reasonCode = "MISSED_REPLAY_START_FAILED_${e.javaClass.simpleName}",
                            source = incidentSource
                        )
                        val roomChecker = missedAlarmRoomReceiptCheckerSeam ?: try {
                            val dao = alarmRepo.database.directBootOccurrenceReceiptDao()
                            val checker: (Long, Long) -> Boolean = { aId, sAt ->
                                dao.findByAlarmId(aId).any { it.triggerTime == sAt }
                            }
                            checker
                        } catch (_: Throwable) { null }

                        val fallbackPosted = try {
                            AlarmFullScreenFallback.post(
                                context = context,
                                alarmId = alarm.id,
                                scheduledAt = at,
                                fireId = fireId,
                                hideLabel = settings.hideAlarmLabelsOnPublicSurfaces,
                                label = alarm.label,
                                claimNonce = claimNonce,
                                refireCount = 0,
                                roomReceiptChecker = roomChecker,
                                authorityGate = if (gate.isEnabled) gate else null
                            )
                        } catch (fallbackErr: Exception) {
                            Log.e("MissedAlarmUnlockReceiver", "Fallback notification post failed for replay alarm ${alarm.id}", fallbackErr)
                            false
                        }
                        deliverySucceeded = fallbackPosted
                        val abortSucceeded = if (!fallbackPosted && occKey != null && claimNonce != null) {
                            gate.abortClaim(context, occKey, claimNonce, com.sysadmindoc.alarmclock.directboot.LegacyTerminalOutcome.REJECTED_NO_OUTPUT)
                        } else {
                            true
                        }
                        if (!abortSucceeded) {
                            Log.e("MissedAlarmUnlockReceiver", "abortClaim failed for replay alarm ${alarm.id}; preserving fail-closed state")
                            incidentRepo.record(
                                alarmId = alarm.id,
                                fireId = fireId,
                                scheduledAt = at,
                                type = AlarmIncidentEvent.TYPE_FOREGROUND_SERVICE,
                                status = AlarmIncidentEvent.STATUS_FAILED,
                                reasonCode = "MISSED_REPLAY_ABORT_CLAIM_FAILED",
                                source = incidentSource
                            )
                        }
                        incidentRepo.record(
                            alarmId = alarm.id,
                            fireId = fireId,
                            scheduledAt = at,
                            type = AlarmIncidentEvent.TYPE_BROADCAST,
                            status = if (fallbackPosted) AlarmIncidentEvent.STATUS_SUCCEEDED
                                     else AlarmIncidentEvent.STATUS_FAILED,
                            reasonCode = if (fallbackPosted) "MISSED_REPLAY_FSI_FALLBACK"
                                         else "MISSED_REPLAY_FSI_FALLBACK_FAILED",
                            source = incidentSource
                        )
                    }

                    // Consume marker ONLY upon verified positive delivery proof
                    if (deliverySucceeded) {
                        clearMissedAlarmMarkerIfMatching(store, id, at)
                    }
                }
            } catch (e: TimeoutCancellationException) {
                Log.e("MissedAlarmUnlockReceiver", "Timed out evaluating missed alarm replay", e)
            } catch (e: Exception) {
                Log.e("MissedAlarmUnlockReceiver", "Unexpected error in missed alarm replay", e)
            } finally {
                MissedAlarmReplayPolicy.releaseClaim(claimToken)
                pending?.finish()
            }
        }
    }

    private suspend fun awaitPositiveDeliveryProof(
        repo: com.sysadmindoc.alarmclock.data.repository.AlarmIncidentRepository,
        alarmId: Long,
        scheduledAt: Long,
        timeoutMs: Long = 1_500L
    ): Boolean {
        val startTime = System.currentTimeMillis()
        while (System.currentTimeMillis() - startTime <= timeoutMs) {
            val proofCount = repo.occurrenceDeliveryCount(alarmId, scheduledAt)
            if (proofCount > 0) return true
            kotlinx.coroutines.delay(200L)
        }
        return false
    }

    /**
     * Conditional marker consume: clears marker ONLY if stored last_missed_id
     * and last_missed_at match the expected values under stateMutex lock,
     * preventing a stale claimant A from deleting a newer missed occurrence marker B.
     */
    private suspend fun clearMissedAlarmMarkerIfMatching(
        store: android.content.SharedPreferences,
        expectedId: Long,
        expectedAt: Long
    ): Boolean {
        return MissedAlarmReplayPolicy.clearMissedAlarmMarkerIfMatching(
            store = store,
            expectedId = expectedId,
            expectedAt = expectedAt
        )
    }

    @dagger.hilt.EntryPoint
    @dagger.hilt.InstallIn(dagger.hilt.components.SingletonComponent::class)
    interface PreferencesEntryPoint {
        fun preferencesManager(): com.sysadmindoc.alarmclock.data.preferences.PreferencesManager
    }
}
