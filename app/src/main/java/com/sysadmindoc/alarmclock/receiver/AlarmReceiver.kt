package com.sysadmindoc.alarmclock.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.sysadmindoc.alarmclock.data.local.entity.AlarmIncidentEvent
import com.sysadmindoc.alarmclock.domain.AlarmScheduler
import com.sysadmindoc.alarmclock.service.AlarmFireDismissContract
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.launch

@org.jetbrains.annotations.VisibleForTesting
internal var testAlarmRepository: com.sysadmindoc.alarmclock.data.repository.AlarmRepository? = null

@org.jetbrains.annotations.VisibleForTesting
internal var testIssuedOccurrenceDao: com.sysadmindoc.alarmclock.data.local.IssuedOccurrenceDao? = null

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
            recordAlarmIncidentsAsync(context, incidents)
        } catch (e: Exception) {
            // ForegroundServiceStartNotAllowedException (API 31+) when the app is
            // background-restricted at the exact moment AlarmManager wakes it.
            // Log for diagnostics and perform non-blocking async recovery on Dispatchers.IO.
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

            // Asynchronous recovery using goAsync() on Dispatchers.IO (NO runBlocking!)
            val pending = try { goAsync() } catch (_: Exception) { null }
            kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO).launch {
                try {
                    val entryPoint = EntryPointAccessors.fromApplication(
                        context.applicationContext,
                        com.sysadmindoc.alarmclock.AlarmClockApp.AppEntryPoint::class.java
                    )
                    val alarmRepo = testAlarmRepository ?: entryPoint.alarmRepository()
                    val incidentRepo = testIncidentRepository ?: entryPoint.alarmIncidentRepository()

                    val issueToken = intent.getStringExtra(AlarmScheduler.EXTRA_ISSUE_TOKEN).orEmpty()
                    val issuedDao = testIssuedOccurrenceDao ?: alarmRepo.issuedOccurrenceDao()
                    val currentSlot = issuedDao.getSlot(alarmId)

                    val isCurrentOccurrence = if (issueToken.isNotBlank()) {
                        issuedDao.evaluateActiveOccurrenceAdmission(
                            issueToken = issueToken,
                            expectedAlarmId = alarmId,
                            expectedScheduledAt = scheduledAt,
                            expectedFireId = fireId
                        )
                    } else {
                        // Legacy tokenless broadcast:
                        // Admissible ONLY IF alarm has NEVER entered Tokenized authority (currentSlot == null).
                        // Once currentSlot != null, tokenless broadcasts are STRICTLY REJECTED!
                        if (currentSlot == null) {
                            val alarm = alarmRepo.getById(alarmId)
                            val deliveryCount = incidentRepo.occurrenceDeliveryCount(alarmId, scheduledAt)
                            alarm != null && alarm.isEnabled && scheduledAt > 0L && deliveryCount == 0
                        } else {
                            Log.w("AlarmReceiver", "Rejecting legacy tokenless broadcast for alarm $alarmId: alarm migrated to tokenized slot")
                            false
                        }
                    }

                    if (isCurrentOccurrence) {
                        val leaseToken = java.util.UUID.randomUUID().toString()
                        val now = System.currentTimeMillis()
                        val leaseDurationMs = 15000L
                        val leased = if (issueToken.isNotBlank()) {
                            issuedDao.claimFallbackLease(
                                issueToken = issueToken,
                                alarmId = alarmId,
                                scheduledAt = scheduledAt,
                                fireId = fireId,
                                leaseToken = leaseToken,
                                expiresAt = now + leaseDurationMs,
                                now = now
                            ) == 1
                        } else {
                            true
                        }

                        if (leased) {
                            val hideLabel = entryPoint.preferencesManager().shouldHideLabelsOnPublicSurfaces()
                            val fallbackPosted = try {
                                com.sysadmindoc.alarmclock.service.AlarmFullScreenFallback.post(
                                    context = context,
                                    alarmId = alarmId,
                                    scheduledAt = scheduledAt,
                                    fireId = fireId,
                                    hideLabel = hideLabel
                                )
                            } catch (e: Exception) {
                                Log.e("AlarmReceiver", "AlarmFullScreenFallback.post threw exception for alarm $alarmId", e)
                                false
                            }

                            if (fallbackPosted) {
                                val ackSuccess = if (issueToken.isNotBlank()) {
                                    issuedDao.acknowledgeFallbackPosted(issueToken, leaseToken, System.currentTimeMillis()) == 1
                                } else {
                                    true
                                }

                                if (ackSuccess) {
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
                                    Log.w("AlarmReceiver", "Fallback ACK rejected for alarm $alarmId (token '$issueToken')")
                                    incidents += ReceiverAlarmIncident(
                                        alarmId = alarmId,
                                        fireId = fireId,
                                        scheduledAt = scheduledAt,
                                        type = AlarmIncidentEvent.TYPE_NOTIFICATION,
                                        status = AlarmIncidentEvent.STATUS_FAILED,
                                        reasonCode = "FALLBACK_ACK_REJECTED",
                                        source = "AlarmReceiver"
                                    )
                                }
                            } else {
                                if (issueToken.isNotBlank()) {
                                    issuedDao.releaseFallbackLeaseOnFailure(issueToken, leaseToken, System.currentTimeMillis())
                                }
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
                            Log.w("AlarmReceiver", "Skipping fallback for alarm $alarmId: fallback lease claim rejected (token '$issueToken')")
                        }
                    } else {
                        Log.w("AlarmReceiver", "Skipping fallback for alarm $alarmId: stale, disabled or duplicate occurrence (token '$issueToken')")
                    }

                    // Record all incidents directly using suspend repository API before pending.finish()
                    incidents.forEach { event ->
                        incidentRepo.record(
                            alarmId = event.alarmId,
                            fireId = event.fireId.ifBlank {
                                AlarmIncidentEvent.fireIdFor(event.alarmId, event.scheduledAt)
                            },
                            scheduledAt = event.scheduledAt,
                            type = event.type,
                            status = event.status,
                            reasonCode = event.reasonCode,
                            source = event.source
                        )
                    }
                    runCatching { testOnIncidentRecordedCompletion?.invoke(incidents) }
                } catch (fallbackError: Exception) {
                    Log.e("AlarmReceiver", "Asynchronous fallback recovery failed for alarm $alarmId", fallbackError)
                } finally {
                    AlarmDeliveryWakeLock.release(deliveryWakeLock)
                    pending?.finish()
                }
            }
        } finally {
            if (serviceStartSucceeded) {
                // FGS start succeeded
            }
        }
    }
}
