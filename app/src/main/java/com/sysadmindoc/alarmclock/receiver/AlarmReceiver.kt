package com.sysadmindoc.alarmclock.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.sysadmindoc.alarmclock.data.local.entity.AlarmIncidentEvent
import com.sysadmindoc.alarmclock.domain.AlarmScheduler
import com.sysadmindoc.alarmclock.service.AlarmFireDismissContract
import com.sysadmindoc.alarmclock.service.AlarmService
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.launch

@org.jetbrains.annotations.VisibleForTesting
internal var testAlarmRepository: com.sysadmindoc.alarmclock.data.repository.AlarmRepository? = null

@org.jetbrains.annotations.VisibleForTesting
internal var testIssuedOccurrenceDao: com.sysadmindoc.alarmclock.data.local.IssuedOccurrenceDao? = null

@org.jetbrains.annotations.VisibleForTesting
internal var outputAuthorityGateSeam: com.sysadmindoc.alarmclock.directboot.outputauthority.SharedOutputAuthorityGate? = null

@org.jetbrains.annotations.VisibleForTesting
internal var roomReceiptCheckerSeam: ((Long, Long) -> Boolean)? = null

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

        // C7-I1 Legacy / DirectBoot Owner Safety Fence:
        // Reject legacy broadcast if DirectBoot has already delivered this occurrence.
        if (isOccurrenceDeliveredByDirectBoot(context, alarmId, scheduledAt)) {
            Log.w("AlarmReceiver", "Legacy broadcast for alarm $alarmId @ $scheduledAt rejected: already delivered by DirectBoot")
            return
        }

        // Synchronously commit durable legacy delivery claim to DE ledger BEFORE starting service
        if (alarmId > 0L && scheduledAt > 0L) {
            if (!claimLegacyDelivery(context, alarmId, scheduledAt)) {
                Log.w("AlarmReceiver", "Legacy delivery claim rejected for alarm $alarmId @ $scheduledAt: already claimed by DirectBoot or concurrent receiver")
                return
            }
        }

        val fireId = intent.getStringExtra(AlarmScheduler.EXTRA_ALARM_FIRE_ID)
            ?: AlarmIncidentEvent.fireIdFor(alarmId, scheduledAt)
        val incomingRestoreEpoch = intent.getLongExtra(AlarmScheduler.EXTRA_RESTORE_EPOCH, 0L)
        val incomingIsQuick: Boolean? = if (intent.hasExtra(AlarmScheduler.EXTRA_QUICK_ALARM)) {
            intent.getBooleanExtra(AlarmScheduler.EXTRA_QUICK_ALARM, false)
        } else {
            null
        }

        val gate = outputAuthorityGateSeam ?: com.sysadmindoc.alarmclock.directboot.outputauthority.SharedOutputAuthorityGate.getInstance(context)
        var claimNonce: String? = null
        val occKey = if (gate.isEnabled) {
            if (alarmId <= 0L || scheduledAt <= 0L) {
                Log.w("AlarmReceiver", "Authority reservation rejected: invalid occurrence identity (alarmId=$alarmId, scheduledAt=$scheduledAt)")
                return
            }
            val key = com.sysadmindoc.alarmclock.directboot.OutputOccurrenceKey(alarmId, scheduledAt, 0)
            val res = gate.reserveForServiceStart(context, key, com.sysadmindoc.alarmclock.directboot.LegacyClaimSource.ALARM_RECEIVER)
            if (res !is com.sysadmindoc.alarmclock.directboot.outputauthority.OutputAuthorizationResult.Authorized) {
                Log.w("AlarmReceiver", "Authority reservation rejected for alarm $alarmId @ $scheduledAt: $res")
                recordAlarmIncidentsAsync(context, listOf(
                    ReceiverAlarmIncident(
                        alarmId = alarmId,
                        fireId = fireId,
                        scheduledAt = scheduledAt,
                        type = AlarmIncidentEvent.TYPE_FOREGROUND_SERVICE,
                        status = AlarmIncidentEvent.STATUS_FAILED,
                        reasonCode = "AUTHORITY_RESERVATION_REJECTED_${res.javaClass.simpleName}",
                        source = "AlarmReceiver"
                    )
                ))
                return
            }
            claimNonce = res.activeNonce
            key
        } else {
            null
        }

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

        val serviceIntent = AlarmFireDismissContract.startServiceIntent(
            context = context,
            alarmId = alarmId,
            scheduledAt = scheduledAt,
            fireId = fireId,
            restoreEpoch = incomingRestoreEpoch,
            isQuickAlarm = incomingIsQuick
        ).apply {
            if (claimNonce != null) {
                putExtra(AlarmService.EXTRA_CLAIM_NONCE, claimNonce)
            }
        }
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
                var abortHandled = false
                var resolvedIncidentRepo: com.sysadmindoc.alarmclock.data.repository.AlarmIncidentRepository? = null
                try {
                    val entryPoint = try {
                        EntryPointAccessors.fromApplication(
                            context.applicationContext,
                            com.sysadmindoc.alarmclock.AlarmClockApp.AppEntryPoint::class.java
                        )
                    } catch (_: Throwable) { null }
                    val alarmRepo = testAlarmRepository ?: entryPoint?.alarmRepository()
                    val incidentRepo = testIncidentRepository ?: entryPoint?.alarmIncidentRepository()
                    resolvedIncidentRepo = incidentRepo

                    if (alarmRepo == null || incidentRepo == null) {
                        Log.w("AlarmReceiver", "Dependencies unavailable during fallback recovery for alarm $alarmId")
                        if (occKey != null && claimNonce != null) {
                            abortHandled = true
                            val aborted = gate.abortClaim(context, occKey, claimNonce, com.sysadmindoc.alarmclock.directboot.LegacyTerminalOutcome.REJECTED_NO_OUTPUT)
                            if (!aborted) {
                                Log.e("AlarmReceiver", "abortClaim failed while dependencies unavailable for alarm $alarmId")
                            }
                        }
                        return@launch
                    }

                    val diskState = com.sysadmindoc.alarmclock.data.backup.RestoreJournalCoordinator.readVerifiedDiskState(context)
                    val currentAlarm = alarmRepo.getById(alarmId)?.sanitized()
                    val metaEpoch = try {
                        alarmRepo.database.scheduleAuthorityDao().getMetaById(1)?.restoreEpoch ?: 0L
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (_: Throwable) {
                        0L
                    }
                    val staleFireReason = when {
                        diskState is com.sysadmindoc.alarmclock.data.backup.JournalDiskState.InvalidOrBlocked ||
                            (diskState is com.sysadmindoc.alarmclock.data.backup.JournalDiskState.Verified &&
                                diskState.phase == com.sysadmindoc.alarmclock.data.backup.RestoreJournalCoordinator.PHASE_PREPARED) ->
                            "RESTORE_PREPARED_FENCE"
                        currentAlarm != null && incomingIsQuick != null && incomingIsQuick != currentAlarm.isQuickAlarm ->
                            "ALARM_KIND_MISMATCH"
                        incomingRestoreEpoch > 0L && metaEpoch > 0L && incomingRestoreEpoch != metaEpoch ->
                            "STALE_RESTORE_EPOCH"
                        incomingRestoreEpoch <= 0L &&
                            currentAlarm != null &&
                            !currentAlarm.isQuickAlarm &&
                            diskState !is com.sysadmindoc.alarmclock.data.backup.JournalDiskState.Absent &&
                            currentAlarm.nextTriggerTime > 0L &&
                            scheduledAt > 0L &&
                            currentAlarm.nextTriggerTime != scheduledAt ->
                            "STALE_TRIGGER_DURING_PENDING_JOURNAL"
                        else -> null
                    }

                    if (staleFireReason != null) {
                        Log.w("AlarmReceiver", "Rejecting stale fallback for alarm $alarmId @ $scheduledAt: $staleFireReason")
                        if (occKey != null && claimNonce != null) {
                            abortHandled = true
                            val aborted = gate.abortClaim(
                                context,
                                occKey,
                                claimNonce,
                                com.sysadmindoc.alarmclock.directboot.LegacyTerminalOutcome.REJECTED_NO_OUTPUT
                            )
                            if (!aborted) {
                                Log.e("AlarmReceiver", "abortClaim failed after stale fire rejection for alarm $alarmId")
                                incidents += ReceiverAlarmIncident(
                                    alarmId = alarmId,
                                    fireId = fireId,
                                    scheduledAt = scheduledAt,
                                    type = AlarmIncidentEvent.TYPE_FOREGROUND_SERVICE,
                                    status = AlarmIncidentEvent.STATUS_FAILED,
                                    reasonCode = "AUTHORITY_ABORT_CLAIM_FAILED",
                                    source = "AlarmReceiver"
                                )
                            }
                        }
                        incidents += ReceiverAlarmIncident(
                            alarmId = alarmId,
                            fireId = fireId,
                            scheduledAt = scheduledAt,
                            type = AlarmIncidentEvent.TYPE_NOTIFICATION,
                            status = AlarmIncidentEvent.STATUS_FAILED,
                            reasonCode = "STALE_FIRE_REJECTED_$staleFireReason",
                            source = "AlarmReceiver"
                        )
                    } else {
                    val issueToken = intent.getStringExtra(AlarmScheduler.EXTRA_ISSUE_TOKEN).orEmpty()
                    val issuedDao = testIssuedOccurrenceDao ?: alarmRepo.issuedOccurrenceDao()
                    val hasActivatedTokenizedOccurrence = issuedDao.countActivatedOccurrencesForAlarm(alarmId) > 0

                    val isCurrentOccurrence = if (issueToken.isNotBlank()) {
                        issuedDao.evaluateActiveOccurrenceAdmission(
                            issueToken = issueToken,
                            expectedAlarmId = alarmId,
                            expectedScheduledAt = scheduledAt,
                            expectedFireId = fireId
                        )
                    } else {
                        // Legacy tokenless broadcast:
                        // Admissible ONLY IF alarm has NEVER activated a tokenized occurrence (!hasActivatedTokenizedOccurrence).
                        // Once hasActivatedTokenizedOccurrence == true, tokenless broadcasts are STRICTLY REJECTED!
                        if (!hasActivatedTokenizedOccurrence) {
                            val alarm = currentAlarm
                            val deliveryCount = incidentRepo.occurrenceDeliveryCount(alarmId, scheduledAt)
                            alarm != null && alarm.isEnabled && scheduledAt > 0L && deliveryCount == 0
                        } else {
                            Log.w("AlarmReceiver", "Rejecting legacy tokenless broadcast for alarm $alarmId: alarm migrated to tokenized authority")
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
                            val hideLabel = try {
                                entryPoint?.preferencesManager()?.shouldHideLabelsOnPublicSurfaces() ?: false
                            } catch (_: Throwable) { false }
                            val roomChecker = roomReceiptCheckerSeam ?: try {
                                val dao = alarmRepo.database.directBootOccurrenceReceiptDao()
                                val checker: (Long, Long) -> Boolean = { aId, sAt ->
                                    dao.findByAlarmId(aId).any { it.triggerTime == sAt }
                                }
                                checker
                            } catch (_: Throwable) { null }

                            val fallbackPosted = try {
                                com.sysadmindoc.alarmclock.service.AlarmFullScreenFallback.post(
                                    context = context,
                                    alarmId = alarmId,
                                    scheduledAt = scheduledAt,
                                    fireId = fireId,
                                    hideLabel = hideLabel,
                                    claimNonce = claimNonce,
                                    refireCount = 0,
                                    roomReceiptChecker = roomChecker,
                                    authorityGate = if (gate.isEnabled) gate else null
                                )
                            } catch (e: Exception) {
                                Log.e("AlarmReceiver", "AlarmFullScreenFallback.post threw exception for alarm $alarmId", e)
                                false
                            }

                            if (fallbackPosted) {
                                abortHandled = true
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
                                val abortSucceeded = if (occKey != null && claimNonce != null) {
                                    abortHandled = true
                                    gate.abortClaim(context, occKey, claimNonce, com.sysadmindoc.alarmclock.directboot.LegacyTerminalOutcome.REJECTED_NO_OUTPUT)
                                } else {
                                    abortHandled = true
                                    true
                                }

                                if (abortSucceeded) {
                                    if (issueToken.isNotBlank()) {
                                        issuedDao.releaseFallbackLeaseOnFailure(issueToken, leaseToken, System.currentTimeMillis())
                                    }
                                } else {
                                    Log.e("AlarmReceiver", "abortClaim failed after fallback failure for alarm $alarmId; preserving fail-closed state")
                                    incidents += ReceiverAlarmIncident(
                                        alarmId = alarmId,
                                        fireId = fireId,
                                        scheduledAt = scheduledAt,
                                        type = AlarmIncidentEvent.TYPE_FOREGROUND_SERVICE,
                                        status = AlarmIncidentEvent.STATUS_FAILED,
                                        reasonCode = "AUTHORITY_ABORT_CLAIM_FAILED",
                                        source = "AlarmReceiver"
                                    )
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
                            if (occKey != null && claimNonce != null) {
                                abortHandled = true
                                val aborted = gate.abortClaim(context, occKey, claimNonce, com.sysadmindoc.alarmclock.directboot.LegacyTerminalOutcome.REJECTED_NO_OUTPUT)
                                if (!aborted) {
                                    Log.e("AlarmReceiver", "abortClaim failed after lease rejection for alarm $alarmId; preserving fail-closed state")
                                    incidents += ReceiverAlarmIncident(
                                        alarmId = alarmId,
                                        fireId = fireId,
                                        scheduledAt = scheduledAt,
                                        type = AlarmIncidentEvent.TYPE_FOREGROUND_SERVICE,
                                        status = AlarmIncidentEvent.STATUS_FAILED,
                                        reasonCode = "AUTHORITY_ABORT_CLAIM_FAILED",
                                        source = "AlarmReceiver"
                                    )
                                }
                            }
                        }
                    } else {
                        Log.w("AlarmReceiver", "Skipping fallback for alarm $alarmId: stale, disabled or duplicate occurrence (token '$issueToken')")
                        if (occKey != null && claimNonce != null) {
                            abortHandled = true
                            val aborted = gate.abortClaim(context, occKey, claimNonce, com.sysadmindoc.alarmclock.directboot.LegacyTerminalOutcome.REJECTED_NO_OUTPUT)
                            if (!aborted) {
                                Log.e("AlarmReceiver", "abortClaim failed after occurrence admission rejection for alarm $alarmId; preserving fail-closed state")
                                incidents += ReceiverAlarmIncident(
                                    alarmId = alarmId,
                                    fireId = fireId,
                                    scheduledAt = scheduledAt,
                                    type = AlarmIncidentEvent.TYPE_FOREGROUND_SERVICE,
                                    status = AlarmIncidentEvent.STATUS_FAILED,
                                    reasonCode = "AUTHORITY_ABORT_CLAIM_FAILED",
                                    source = "AlarmReceiver"
                                )
                            }
                        }
                    }
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
                    if (!abortHandled && occKey != null && claimNonce != null) {
                        val aborted = gate.abortClaim(context, occKey, claimNonce, com.sysadmindoc.alarmclock.directboot.LegacyTerminalOutcome.REJECTED_NO_OUTPUT)
                        if (!aborted) {
                            Log.e("AlarmReceiver", "abortClaim failed in fallback error handler for alarm $alarmId; preserving fail-closed state")
                            incidents += ReceiverAlarmIncident(
                                alarmId = alarmId,
                                fireId = fireId,
                                scheduledAt = scheduledAt,
                                type = AlarmIncidentEvent.TYPE_FOREGROUND_SERVICE,
                                status = AlarmIncidentEvent.STATUS_FAILED,
                                reasonCode = "AUTHORITY_ABORT_CLAIM_FAILED",
                                source = "AlarmReceiver"
                            )
                        }
                    }
                    resolvedIncidentRepo?.let { repo ->
                        runCatching {
                            incidents.forEach { event ->
                                repo.record(
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
                            testOnIncidentRecordedCompletion?.invoke(incidents)
                        }
                    }
                } finally {
                    AlarmDeliveryWakeLock.release(deliveryWakeLock)
                    pending?.finish()
                }
            }
        } finally {
            if (!serviceStartSucceeded) {
                AlarmDeliveryWakeLock.release(deliveryWakeLock)
            }
        }
    }

    companion object {
        @org.jetbrains.annotations.VisibleForTesting
        internal fun claimLegacyDelivery(context: Context, alarmId: Long, scheduledAt: Long): Boolean {
            val deContext = if (context.isDeviceProtectedStorage) context else context.createDeviceProtectedStorageContext()
            return try {
                val record = com.sysadmindoc.alarmclock.directboot.DirectBootDeliveryRecord.createNormal(
                    alarmId = alarmId,
                    triggerTime = scheduledAt,
                    deliveredAt = System.currentTimeMillis(),
                    generation = 1,
                    instanceToken = "legacy_claim_$alarmId",
                    provenance = "LEGACY_DELIVERY",
                    restoreEpoch = 1L
                )
                val res = com.sysadmindoc.alarmclock.directboot.DirectBootDeliveryLedger.appendRecord(deContext, record)
                res is com.sysadmindoc.alarmclock.directboot.DeliveryAppendResult.Appended
            } catch (_: Exception) {
                false
            }
        }

        @org.jetbrains.annotations.VisibleForTesting
        internal fun isOccurrenceDeliveredByDirectBoot(context: Context, alarmId: Long, scheduledAt: Long): Boolean {
            val deContext = if (context.isDeviceProtectedStorage) context else context.createDeviceProtectedStorageContext()
            val ceContext = context.applicationContext
            return try {
                val ledgerState = com.sysadmindoc.alarmclock.directboot.DirectBootDeliveryLedger.readUnacknowledgedRecords(deContext)
                val inLedger = if (ledgerState is com.sysadmindoc.alarmclock.directboot.LedgerStorageState.ValidWithRecords) {
                    ledgerState.records.any { it.alarmId == alarmId && (scheduledAt <= 0L || it.triggerTime == scheduledAt) }
                } else false

                if (inLedger) return true

                val ceState = com.sysadmindoc.alarmclock.directboot.DirectBootCeTakeoverStore.inspectStorageState(ceContext)
                if (ceState is com.sysadmindoc.alarmclock.directboot.CeTakeoverStorageState.ValidWithRecords) {
                    ceState.records.any { it.alarmId == alarmId && (scheduledAt <= 0L || it.triggerTime == scheduledAt) }
                } else false
            } catch (_: Exception) {
                false
            }
        }
    }
}
