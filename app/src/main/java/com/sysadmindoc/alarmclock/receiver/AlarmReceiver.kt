package com.sysadmindoc.alarmclock.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.sysadmindoc.alarmclock.data.local.entity.AlarmIncidentEvent
import com.sysadmindoc.alarmclock.domain.AlarmScheduler
import com.sysadmindoc.alarmclock.service.AlarmFireDismissContract
import dagger.hilt.android.EntryPointAccessors

@org.jetbrains.annotations.VisibleForTesting
internal var testAlarmRepository: com.sysadmindoc.alarmclock.data.repository.AlarmRepository? = null

/**
 * Receives the alarm broadcast from AlarmManager and starts the foreground
 * AlarmService, which handles audio playback, vibration, and launching the
 * full-screen dismiss/snooze UI.
 */
class AlarmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val alarmId = intent.getLongExtra(AlarmScheduler.EXTRA_ALARM_ID, -1)
        if (alarmId == -1L) return
        val scheduledAt = intent.getLongExtra(AlarmScheduler.EXTRA_SCHEDULED_AT, 0L)
        val fireId = intent.getStringExtra(AlarmScheduler.EXTRA_ALARM_FIRE_ID)
            ?: AlarmIncidentEvent.fireIdFor(alarmId, scheduledAt)
        val incidents = mutableListOf(
            ReceiverAlarmIncident(
                alarmId = alarmId,
                fireId = fireId,
                scheduledAt = scheduledAt,
                type = AlarmIncidentEvent.TYPE_BROADCAST,
                status = AlarmIncidentEvent.STATUS_RECEIVED,
                reasonCode = "ALARM_MANAGER_DELIVERED",
                source = "AlarmReceiver"
            )
        )

        val serviceIntent = AlarmFireDismissContract.startServiceIntent(context, alarmId, scheduledAt, fireId)
        val deliveryWakeLock = AlarmDeliveryWakeLock.acquire(context)
        var serviceStartSucceeded = false
        try {
            context.startForegroundService(serviceIntent)
            serviceStartSucceeded = true
            incidents += ReceiverAlarmIncident(
                alarmId = alarmId,
                fireId = fireId,
                scheduledAt = scheduledAt,
                type = AlarmIncidentEvent.TYPE_FOREGROUND_SERVICE,
                status = AlarmIncidentEvent.STATUS_REQUESTED,
                reasonCode = "START_FOREGROUND_SERVICE",
                source = "AlarmReceiver"
            )
        } catch (e: Exception) {
            // ForegroundServiceStartNotAllowedException (API 31+) when the app is
            // background-restricted at the exact moment AlarmManager wakes it.
            // Log for diagnostics and attempt bounded full-screen fallback for same occurrence.
            Log.e("AlarmReceiver", "startForegroundService failed for alarm $alarmId", e)
            incidents += ReceiverAlarmIncident(
                alarmId = alarmId,
                fireId = fireId,
                scheduledAt = scheduledAt,
                type = AlarmIncidentEvent.TYPE_FOREGROUND_SERVICE,
                status = AlarmIncidentEvent.STATUS_FAILED,
                reasonCode = "START_FOREGROUND_SERVICE_FAILED_${e.javaClass.simpleName}",
                source = "AlarmReceiver"
            )

            // Attempt bounded full-screen fallback for same occurrence using fail-closed privacy authority
            try {
                val entryPoint = EntryPointAccessors.fromApplication(
                    context.applicationContext,
                    com.sysadmindoc.alarmclock.AlarmClockApp.AppEntryPoint::class.java
                )
                val repository = testAlarmRepository ?: entryPoint.alarmRepository()
                val alarm = runCatching {
                    kotlinx.coroutines.runBlocking<com.sysadmindoc.alarmclock.data.model.Alarm?> {
                        repository.getById(alarmId)
                    }
                }.getOrNull()

                val deliveryCount = runCatching {
                    kotlinx.coroutines.runBlocking {
                        entryPoint.alarmIncidentRepository().occurrenceDeliveryCount(alarmId, scheduledAt)
                    }
                }.getOrDefault(0)

                val isCurrentOccurrence = alarm != null &&
                    alarm.isEnabled &&
                    alarm.nextTriggerTime == scheduledAt &&
                    deliveryCount == 0

                if (isCurrentOccurrence) {
                    val hideLabel = entryPoint.preferencesManager().shouldHideLabelsOnPublicSurfaces()
                    val fallbackPosted = com.sysadmindoc.alarmclock.service.AlarmFullScreenFallback.post(
                        context = context,
                        alarmId = alarmId,
                        scheduledAt = scheduledAt,
                        fireId = fireId,
                        hideLabel = hideLabel
                    )
                    if (fallbackPosted) {
                        incidents += ReceiverAlarmIncident(
                            alarmId = alarmId,
                            fireId = fireId,
                            scheduledAt = scheduledAt,
                            type = AlarmIncidentEvent.TYPE_NOTIFICATION,
                            status = AlarmIncidentEvent.STATUS_SUCCEEDED,
                            reasonCode = "ALARM_NOTIFICATION_POSTED",
                            source = "AlarmReceiver"
                        )
                    } else {
                        incidents += ReceiverAlarmIncident(
                            alarmId = alarmId,
                            fireId = fireId,
                            scheduledAt = scheduledAt,
                            type = AlarmIncidentEvent.TYPE_NOTIFICATION,
                            status = AlarmIncidentEvent.STATUS_FAILED,
                            reasonCode = "FALLBACK_NOTIFICATION_FAILED",
                            source = "AlarmReceiver"
                        )
                    }
                } else {
                    Log.w("AlarmReceiver", "Skipping fallback for alarm $alarmId: stale or duplicate occurrence (dbTrigger ${alarm?.nextTriggerTime}, deliveryCount $deliveryCount)")
                }
            } catch (fallbackError: Exception) {
                Log.e("AlarmReceiver", "Full-screen fallback failed for alarm $alarmId", fallbackError)
            }
        } finally {
            if (!serviceStartSucceeded) AlarmDeliveryWakeLock.release(deliveryWakeLock)
            recordAlarmIncidentsAsync(context, incidents)
        }
    }
}
