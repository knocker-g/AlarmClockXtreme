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
                    pending.finish()
                    return@launch
                }
            try {
                withTimeout(8_000L) {
                    val ep = EntryPointAccessors.fromApplication(
                        context.applicationContext,
                        AlarmClockApp.AppEntryPoint::class.java
                    )
                    val prefsMgr = EntryPointAccessors.fromApplication(
                        context.applicationContext,
                        PreferencesEntryPoint::class.java
                    ).preferencesManager()
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

                    val alarm = ep.alarmRepository().getById(id)
                        ?.takeIf { it.isEnabled }
                        ?: run {
                            clearMissedAlarmMarkerIfMatching(store, id, at)
                            return@withTimeout
                        }

                    val fireId = AlarmFireDismissContract.fireId(alarm.id, at)
                    val fireIntent = AlarmFireDismissContract.startServiceIntent(context, alarm.id, at, fireId)
                    var deliverySucceeded = false
                    try {
                        context.startForegroundService(fireIntent)
                        deliverySucceeded = true
                        ep.alarmIncidentRepository().record(
                            alarmId = alarm.id,
                            fireId = fireId,
                            scheduledAt = at,
                            type = AlarmIncidentEvent.TYPE_BROADCAST,
                            status = AlarmIncidentEvent.STATUS_REQUESTED,
                            reasonCode = "MISSED_REPLAY_REQUESTED",
                            source = incidentSource
                        )
                    } catch (e: Exception) {
                        Log.e("MissedAlarmUnlockReceiver",
                            "startForegroundService failed for replay alarm ${alarm.id}", e)
                        ep.alarmIncidentRepository().record(
                            alarmId = alarm.id,
                            fireId = fireId,
                            scheduledAt = at,
                            type = AlarmIncidentEvent.TYPE_FOREGROUND_SERVICE,
                            status = AlarmIncidentEvent.STATUS_FAILED,
                            reasonCode = "MISSED_REPLAY_START_FAILED_${e.javaClass.simpleName}",
                            source = incidentSource
                        )
                        val fallbackPosted = AlarmFullScreenFallback.post(
                            context = context,
                            alarm = alarm,
                            scheduledAt = at,
                            fireId = fireId,
                            hideLabel = settings.hideAlarmLabelsOnPublicSurfaces
                        )
                        deliverySucceeded = fallbackPosted
                        ep.alarmIncidentRepository().record(
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

                    // Consume marker ONLY upon positive delivery proof
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
                pending.finish()
            }
        }
    }

    /**
     * Conditional marker consume: clears marker ONLY if stored last_missed_id
     * and last_missed_at match the expected values, preventing a stale claimant
     * from deleting a newer missed occurrence marker.
     */
    @SuppressLint("ApplySharedPref")
    private fun clearMissedAlarmMarkerIfMatching(
        store: android.content.SharedPreferences,
        expectedId: Long,
        expectedAt: Long
    ) {
        val currentId = store.getLong("last_missed_id", -1L)
        val currentAt = store.getLong("last_missed_at", 0L)
        if (currentId == expectedId && currentAt == expectedAt) {
            store.edit().clear().commit()
        }
    }

    @dagger.hilt.EntryPoint
    @dagger.hilt.InstallIn(dagger.hilt.components.SingletonComponent::class)
    interface PreferencesEntryPoint {
        fun preferencesManager(): com.sysadmindoc.alarmclock.data.preferences.PreferencesManager
    }
}
