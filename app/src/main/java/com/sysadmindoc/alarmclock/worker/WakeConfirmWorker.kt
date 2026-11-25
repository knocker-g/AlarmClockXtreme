package com.sysadmindoc.alarmclock.worker

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.annotation.VisibleForTesting
import androidx.core.app.NotificationCompat
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.sysadmindoc.alarmclock.R
import com.sysadmindoc.alarmclock.data.local.entity.AlarmIncidentEvent
import com.sysadmindoc.alarmclock.data.preferences.PreferencesManager
import com.sysadmindoc.alarmclock.data.repository.AlarmIncidentRepository
import com.sysadmindoc.alarmclock.data.repository.AlarmRepository
import com.sysadmindoc.alarmclock.directboot.LegacyTerminalOutcome
import com.sysadmindoc.alarmclock.directboot.OutputOccurrenceKey
import com.sysadmindoc.alarmclock.directboot.outputauthority.OutputAuthorizationResult
import com.sysadmindoc.alarmclock.directboot.outputauthority.SharedOutputAuthorityGate
import com.sysadmindoc.alarmclock.domain.AlarmScheduler
import com.sysadmindoc.alarmclock.service.AlarmFullScreenFallback
import com.sysadmindoc.alarmclock.service.AlarmService
import com.sysadmindoc.alarmclock.ui.alarmfiring.WakeConfirmActivity
import com.sysadmindoc.alarmclock.util.AlarmPublicText
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.delay

/**
 * F5: Post-alarm wake confirmation worker.
 *
 * Fires `wakeConfirmDelayMinutes` after alarm dismiss. Posts a high-priority
 * notification with a full-screen intent that opens [WakeConfirmActivity].
 * Waits up to [CONFIRM_WAIT_MS] for the user to confirm via the activity
 * (which writes occurrence-scoped `confirmed_<id>_<scheduledAt>=true`).
 * If still unconfirmed when the wait expires, re-fires the alarm (1..3).
 */
@HiltWorker
class WakeConfirmWorker @AssistedInject constructor(
    @Assisted private val context: Context,
    @Assisted workerParams: WorkerParameters,
    private val alarmRepository: AlarmRepository,
    private val preferencesManager: PreferencesManager,
    private val alarmIncidentRepository: AlarmIncidentRepository
) : CoroutineWorker(context, workerParams) {

    @VisibleForTesting
    internal var confirmWaitMsOverride: Long? = null

    @VisibleForTesting
    internal var pollIntervalMsOverride: Long? = null

    @VisibleForTesting
    internal var authorityGateOverride: SharedOutputAuthorityGate? = null

    companion object {
        const val KEY_ALARM_ID = "alarm_id"
        const val KEY_ALARM_FIRE_ID = "alarm_fire_id"
        const val KEY_SCHEDULED_AT = "scheduled_at"
        const val KEY_REFIRE_COUNT = "refire_count"
        const val KEY_STEP_TOKEN = "step_token"
        const val CHANNEL_WAKE_CONFIRM = "wake_confirm_channel"
        // Far above the other per-id notification ranges (TimerFinished is
        // 7000 + timerId, also unbounded) so a long-lived install with alarm
        // ids past 2000 can't collide wake-confirm and timer notifications.
        const val NOTIF_ID_BASE = 500_000

        /**
         * Width of the wake-confirm band. Without it the id grew with the alarm
         * id and eventually reached the Hue sunrise band at 800000.
         */
        private const val NOTIF_ID_RANGE = 100_000L

        internal fun notificationId(alarmId: Long, scheduledAt: Long = 0L): Int {
            if (scheduledAt <= 0L) {
                val mod = ((alarmId % NOTIF_ID_RANGE) + NOTIF_ID_RANGE) % NOTIF_ID_RANGE
                return (NOTIF_ID_BASE + mod).toInt()
            }
            val hash = (alarmId * 31L) xor scheduledAt xor (scheduledAt ushr 32)
            val offset = (hash and 0x7FFF_FFFFL) % NOTIF_ID_RANGE
            return (NOTIF_ID_BASE + offset).toInt()
        }

        internal fun notificationTag(alarmId: Long, scheduledAt: Long): String? {
            if (scheduledAt <= 0L) return null
            return buildString {
                append("wake_confirm_")
                append(alarmId)
                append('_')
                append(scheduledAt)
            }
        }

        // Time the user has to tap "I'm awake" before the alarm re-fires.
        const val CONFIRM_WAIT_SECONDS = 60
        private const val CONFIRM_WAIT_MS = CONFIRM_WAIT_SECONDS * 1_000L
        private const val POLL_INTERVAL_MS = 1_000L
        const val MAX_REFIRES = 3
    }

    override suspend fun doWork(): Result {
        val alarmId = inputData.getLong(KEY_ALARM_ID, -1L)
        if (alarmId <= 0L) {
            return Result.success()
        }

        val rawScheduledAt = inputData.getLong(KEY_SCHEDULED_AT, 0L)
        val rawFireId = inputData.getString(KEY_ALARM_FIRE_ID).orEmpty().trim()
        val stepToken = inputData.getString(KEY_STEP_TOKEN).orEmpty()
        val wasLegacyMissingScheduledAt = rawScheduledAt <= 0L

        val scheduledAt: Long
        val fireId: String
        if (wasLegacyMissingScheduledAt) {
            val db = alarmRepository.database
            val resolution = WakeConfirmOccurrenceStore.resolveLegacyMissingScheduledAt(
                context = context,
                alarmId = alarmId,
                workerFireId = rawFireId,
                workerStepToken = stepToken,
                roomDismissedTimesFetcher = { aid ->
                    (db.alarmEventDao().findRecentDismissedScheduledTimes(aid) +
                        db.alarmIncidentEventDao().findRecentDismissedScheduledTimes(aid))
                        .filter { it > 0L }
                        .distinct()
                }
            )
            if (resolution !is WakeConfirmOccurrenceStore.LegacyOccurrenceResolution.Resolved) {
                recordIncident(
                    alarmId = alarmId,
                    fireId = rawFireId.ifBlank { AlarmIncidentEvent.fireIdFor(alarmId, 0L) },
                    scheduledAt = 0L,
                    status = AlarmIncidentEvent.STATUS_SKIPPED,
                    reasonCode = "WAKE_CONFIRM_INVALID_OCCURRENCE"
                )
                return Result.success()
            }
            scheduledAt = resolution.scheduledAt
            fireId = resolution.fireId
        } else {
            scheduledAt = rawScheduledAt
            fireId = rawFireId.takeIf { it.isNotBlank() }
                ?: AlarmIncidentEvent.fireIdFor(alarmId, scheduledAt)
        }

        val refireCount = inputData.getInt(KEY_REFIRE_COUNT, 0)
        if (refireCount !in 0..MAX_REFIRES) {
            recordIncident(
                alarmId = alarmId,
                fireId = fireId,
                scheduledAt = scheduledAt,
                status = AlarmIncidentEvent.STATUS_SKIPPED,
                reasonCode = "WAKE_CONFIRM_REFIRE_OUT_OF_BOUNDS"
            )
            return Result.success()
        }

        val alarm = alarmRepository.getById(alarmId) ?: run {
            recordIncident(
                alarmId = alarmId,
                fireId = fireId,
                scheduledAt = scheduledAt,
                status = AlarmIncidentEvent.STATUS_SKIPPED,
                reasonCode = "WAKE_CONFIRM_ALARM_ROW_MISSING"
            )
            return Result.success()
        }
        // Skip only if a repeating alarm was manually disabled by the user.
        // One-shot alarms are auto-disabled by handleAlarmFired() before
        // this worker runs, so checking isEnabled on them would always
        // skip — defeating wake confirmation for the exact use case where
        // users need it most.
        val isOneShot = !alarm.isRecurringSchedule
        if (!alarm.isEnabled && !isOneShot) {
            recordIncident(
                alarmId = alarmId,
                fireId = fireId,
                scheduledAt = scheduledAt,
                status = AlarmIncidentEvent.STATUS_SKIPPED,
                reasonCode = "WAKE_CONFIRM_ALARM_DISABLED"
            )
            return Result.success()
        }

        val roomDismissChecker: suspend () -> Boolean = {
            val db = alarmRepository.database
            val eventCount = db.alarmEventDao().countDismissedByOccurrence(alarmId, scheduledAt)
            val incidentCount = db.alarmIncidentEventDao().countDismissedIncidentsByOccurrence(alarmId, scheduledAt)
            eventCount > 0 || incidentCount > 0
        }

        // Fail-closed authorization against DE WakeConfirm lineage (or Room dismiss proof for pre-S5-D workers)
        val authResult = WakeConfirmOccurrenceStore.authorizeWorkerExecution(
            context = context,
            alarmId = alarmId,
            scheduledAt = scheduledAt,
            refireCount = refireCount,
            workerStepToken = stepToken,
            roomDismissChecker = roomDismissChecker
        )
        if (authResult !is WakeConfirmOccurrenceStore.WorkerAuthResult.Authorized) {
            recordIncident(
                alarmId = alarmId,
                fireId = fireId,
                scheduledAt = scheduledAt,
                status = AlarmIncidentEvent.STATUS_SKIPPED,
                reasonCode = "WAKE_CONFIRM_UNAUTHORIZED"
            )
            return Result.success()
        }

        // Check confirmation (including 4-proof legacy migration) BEFORE posting prompt
        when (
            WakeConfirmOccurrenceStore.checkAndMigrateConfirmation(
                context = context,
                alarmId = alarmId,
                scheduledAt = scheduledAt,
                roomDismissChecker = roomDismissChecker
            )
        ) {
            is WakeConfirmOccurrenceStore.ConfirmationStatus.Confirmed -> {
                recordIncident(
                    alarmId = alarmId,
                    fireId = fireId,
                    scheduledAt = scheduledAt,
                    status = AlarmIncidentEvent.STATUS_SUCCEEDED,
                    reasonCode = "WAKE_CONFIRM_CONFIRMED"
                )
                return Result.success()
            }
            is WakeConfirmOccurrenceStore.ConfirmationStatus.StorageUnverifiable -> {
                recordIncident(
                    alarmId = alarmId,
                    fireId = fireId,
                    scheduledAt = scheduledAt,
                    status = AlarmIncidentEvent.STATUS_FAILED,
                    reasonCode = "WAKE_CONFIRM_STORAGE_UNVERIFIABLE"
                )
                return Result.failure()
            }
            is WakeConfirmOccurrenceStore.ConfirmationStatus.Unconfirmed -> Unit
        }

        ensureChannel()
        recordIncident(
            alarmId = alarmId,
            fireId = fireId,
            scheduledAt = scheduledAt,
            status = AlarmIncidentEvent.STATUS_REQUESTED,
            reasonCode = "WAKE_CONFIRM_PROMPT_REQUESTED"
        )
        // One deadline, shared with the screen. Without it the countdown
        // restarted whenever the activity happened to open, so a user who
        // tapped the notification 40 seconds in was told they had a full minute
        // while the alarm was 20 seconds from re-firing.
        val waitMs = confirmWaitMsOverride ?: CONFIRM_WAIT_MS
        val pollMs = (pollIntervalMsOverride ?: POLL_INTERVAL_MS).coerceAtLeast(10L)
        val deadline = System.currentTimeMillis() + waitMs
        val promptPosted = postPrompt(
            alarmId = alarmId,
            label = alarm.label,
            hideLabel = preferencesManager.getCurrentSettings().hideAlarmLabelsOnPublicSurfaces,
            fireId = fireId,
            scheduledAt = scheduledAt,
            refireCount = refireCount,
            deadlineMillis = deadline
        )
        recordIncident(
            alarmId = alarmId,
            fireId = fireId,
            scheduledAt = scheduledAt,
            status = if (promptPosted) AlarmIncidentEvent.STATUS_SUCCEEDED else AlarmIncidentEvent.STATUS_FAILED,
            reasonCode = if (promptPosted) "WAKE_CONFIRM_PROMPT_POSTED" else "WAKE_CONFIRM_PROMPT_NO_NOTIFICATION_MANAGER"
        )

        // Poll for confirmation up to the shared deadline.
        while (System.currentTimeMillis() < deadline) {
            if (isStopped) {
                cancelPrompt(alarmId, scheduledAt, wasLegacyMissingScheduledAt)
                recordIncident(
                    alarmId = alarmId,
                    fireId = fireId,
                    scheduledAt = scheduledAt,
                    status = AlarmIncidentEvent.STATUS_FAILED,
                    reasonCode = "WAKE_CONFIRM_WORKER_CANCELLED"
                )
                return Result.failure()
            }
            when (
                WakeConfirmOccurrenceStore.checkAndMigrateConfirmation(
                    context = context,
                    alarmId = alarmId,
                    scheduledAt = scheduledAt,
                    roomDismissChecker = roomDismissChecker
                )
            ) {
                is WakeConfirmOccurrenceStore.ConfirmationStatus.Confirmed -> {
                    cancelPrompt(alarmId, scheduledAt, wasLegacyMissingScheduledAt)
                    recordIncident(
                        alarmId = alarmId,
                        fireId = fireId,
                        scheduledAt = scheduledAt,
                        status = AlarmIncidentEvent.STATUS_SUCCEEDED,
                        reasonCode = "WAKE_CONFIRM_CONFIRMED"
                    )
                    return Result.success()
                }
                is WakeConfirmOccurrenceStore.ConfirmationStatus.StorageUnverifiable -> {
                    cancelPrompt(alarmId, scheduledAt, wasLegacyMissingScheduledAt)
                    recordIncident(
                        alarmId = alarmId,
                        fireId = fireId,
                        scheduledAt = scheduledAt,
                        status = AlarmIncidentEvent.STATUS_FAILED,
                        reasonCode = "WAKE_CONFIRM_STORAGE_UNVERIFIABLE"
                    )
                    return Result.failure()
                }
                is WakeConfirmOccurrenceStore.ConfirmationStatus.Unconfirmed -> Unit
            }

            val latestRec = WakeConfirmOccurrenceStore.findRecord(context, alarmId, scheduledAt)
            if (latestRec?.cancelled == true) {
                cancelPrompt(alarmId, scheduledAt, wasLegacyMissingScheduledAt)
                recordIncident(
                    alarmId = alarmId,
                    fireId = fireId,
                    scheduledAt = scheduledAt,
                    status = AlarmIncidentEvent.STATUS_SKIPPED,
                    reasonCode = "WAKE_CONFIRM_CANCELLED_BY_OCCURRENCE_STATE"
                )
                return Result.success()
            }

            delay(pollMs)
        }

        // Final confirmation check right on the deadline before timing out
        when (
            WakeConfirmOccurrenceStore.checkAndMigrateConfirmation(
                context = context,
                alarmId = alarmId,
                scheduledAt = scheduledAt,
                roomDismissChecker = roomDismissChecker
            )
        ) {
            is WakeConfirmOccurrenceStore.ConfirmationStatus.Confirmed -> {
                cancelPrompt(alarmId, scheduledAt, wasLegacyMissingScheduledAt)
                recordIncident(
                    alarmId = alarmId,
                    fireId = fireId,
                    scheduledAt = scheduledAt,
                    status = AlarmIncidentEvent.STATUS_SUCCEEDED,
                    reasonCode = "WAKE_CONFIRM_CONFIRMED"
                )
                return Result.success()
            }
            is WakeConfirmOccurrenceStore.ConfirmationStatus.StorageUnverifiable -> {
                cancelPrompt(alarmId, scheduledAt, wasLegacyMissingScheduledAt)
                recordIncident(
                    alarmId = alarmId,
                    fireId = fireId,
                    scheduledAt = scheduledAt,
                    status = AlarmIncidentEvent.STATUS_FAILED,
                    reasonCode = "WAKE_CONFIRM_STORAGE_UNVERIFIABLE"
                )
                return Result.failure()
            }
            is WakeConfirmOccurrenceStore.ConfirmationStatus.Unconfirmed -> Unit
        }

        // No confirmation in time: clear the prompt and re-fire the alarm
        // unless the repeat cap has been reached.
        cancelPrompt(alarmId, scheduledAt, wasLegacyMissingScheduledAt)
        recordIncident(
            alarmId = alarmId,
            fireId = fireId,
            scheduledAt = scheduledAt,
            status = AlarmIncidentEvent.STATUS_FAILED,
            reasonCode = "WAKE_CONFIRM_TIMED_OUT"
        )

        if (refireCount >= MAX_REFIRES) {
            recordIncident(
                alarmId = alarmId,
                fireId = fireId,
                scheduledAt = scheduledAt,
                status = AlarmIncidentEvent.STATUS_SKIPPED,
                reasonCode = "WAKE_CONFIRM_REFIRE_CAP_REACHED"
            )
            return Result.success()
        }

        val latestAlarmBeforeRefire = alarmRepository.getById(alarmId)
        val stillEligible = latestAlarmBeforeRefire != null &&
            (latestAlarmBeforeRefire.isEnabled || !latestAlarmBeforeRefire.isRecurringSchedule)
        if (!stillEligible) {
            recordIncident(
                alarmId = alarmId,
                fireId = fireId,
                scheduledAt = scheduledAt,
                status = AlarmIncidentEvent.STATUS_SKIPPED,
                reasonCode = if (latestAlarmBeforeRefire == null) {
                    "WAKE_CONFIRM_ALARM_ROW_MISSING"
                } else {
                    "WAKE_CONFIRM_ALARM_DISABLED"
                }
            )
            return Result.success()
        }

        val consumeResult = WakeConfirmOccurrenceStore.consumeRefireStep(
            context = context,
            alarmId = alarmId,
            scheduledAt = scheduledAt,
            currentRefireCount = refireCount,
            workerStepToken = stepToken
        )
        if (consumeResult !is WakeConfirmOccurrenceStore.RefireConsumeResult.Consumed) {
            recordIncident(
                alarmId = alarmId,
                fireId = fireId,
                scheduledAt = scheduledAt,
                status = AlarmIncidentEvent.STATUS_SKIPPED,
                reasonCode = "WAKE_CONFIRM_REFIRE_STEP_REJECTED"
            )
            return Result.success()
        }
        val nextRefire = consumeResult.nextRefireCount

        val gate = authorityGateOverride ?: SharedOutputAuthorityGate.getInstance(context)
        val occKey = OutputOccurrenceKey(alarmId, scheduledAt, nextRefire)
        var claimNonceForRefire: String? = null
        if (gate.isEnabled) {
            val gateAuth = gate.reserveWakeConfirmRefire(context, occKey)
            if (gateAuth !is OutputAuthorizationResult.Authorized) {
                recordIncident(
                    alarmId = alarmId,
                    fireId = fireId,
                    scheduledAt = scheduledAt,
                    type = AlarmIncidentEvent.TYPE_FOREGROUND_SERVICE,
                    status = AlarmIncidentEvent.STATUS_FAILED,
                    reasonCode = "WAKE_CONFIRM_REFIRE_GATE_DENIED"
                )
                return Result.success()
            }
            claimNonceForRefire = gateAuth.activeNonce
        }

        val startIntent = Intent(context, AlarmService::class.java).apply {
            action = AlarmService.ACTION_START_ALARM
            putExtra(AlarmScheduler.EXTRA_ALARM_ID, alarmId)
            putExtra(AlarmScheduler.EXTRA_SCHEDULED_AT, scheduledAt)
            putExtra(AlarmScheduler.EXTRA_ALARM_FIRE_ID, fireId)
            putExtra(AlarmService.EXTRA_WAKE_CONFIRM_REFIRE_COUNT, nextRefire)
            claimNonceForRefire?.let { putExtra(AlarmService.EXTRA_CLAIM_NONCE, it) }
        }
        try {
            context.startForegroundService(startIntent)
            recordIncident(
                alarmId = alarmId,
                fireId = fireId,
                scheduledAt = scheduledAt,
                type = AlarmIncidentEvent.TYPE_FOREGROUND_SERVICE,
                status = AlarmIncidentEvent.STATUS_REQUESTED,
                reasonCode = "WAKE_CONFIRM_REFIRE_REQUESTED"
            )
        } catch (_: Exception) {
            // Same background foreground-service restriction as the fire
            // watchdog: fall back to a full-screen intent so a user who never
            // confirmed still gets woken.
            val latestAlarm = alarmRepository.getById(alarmId)
            val fallbackPosted = latestAlarm != null && AlarmFullScreenFallback.post(
                context = context,
                alarmId = latestAlarm.id,
                scheduledAt = scheduledAt,
                fireId = fireId,
                hideLabel = preferencesManager.getCurrentSettings()
                    .hideAlarmLabelsOnPublicSurfaces,
                label = latestAlarm.label,
                claimNonce = claimNonceForRefire,
                refireCount = nextRefire,
                roomReceiptChecker = if (gate.isEnabled) {
                    { aid, sched ->
                        alarmRepository.database.directBootOccurrenceReceiptDao()
                            .findByAlarmId(aid)
                            .any { it.triggerTime == sched }
                    }
                } else null,
                authorityGate = gate
            )
            if (gate.isEnabled && !fallbackPosted && claimNonceForRefire != null) {
                gate.abortClaim(context, occKey, claimNonceForRefire, LegacyTerminalOutcome.REJECTED_NO_OUTPUT)
            }
            recordIncident(
                alarmId = alarmId,
                fireId = fireId,
                scheduledAt = scheduledAt,
                type = AlarmIncidentEvent.TYPE_FOREGROUND_SERVICE,
                status = if (fallbackPosted) AlarmIncidentEvent.STATUS_SUCCEEDED
                else AlarmIncidentEvent.STATUS_FAILED,
                reasonCode = if (fallbackPosted) "WAKE_CONFIRM_FSI_FALLBACK"
                else "WAKE_CONFIRM_REFIRE_START_FAILED"
            )
        }
        return Result.success()
    }

    private fun ensureChannel() {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        if (nm.getNotificationChannel(CHANNEL_WAKE_CONFIRM) == null) {
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_WAKE_CONFIRM,
                    context.getString(R.string.notif_wake_confirm_channel_name),
                    NotificationManager.IMPORTANCE_HIGH
                ).apply {
                    description = context.getString(R.string.notif_wake_confirm_channel_description)
                    enableVibration(true)
                    setBypassDnd(true)
                }
            )
        }
    }

    private fun postPrompt(
        alarmId: Long,
        label: String,
        hideLabel: Boolean,
        fireId: String,
        scheduledAt: Long,
        refireCount: Int,
        deadlineMillis: Long
    ): Boolean {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return false
        val notifId = notificationId(alarmId, scheduledAt)
        val notifTag = notificationTag(alarmId, scheduledAt)
        val activityIntent = Intent(context, WakeConfirmActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            data = Uri.parse("acx://wake-confirm/$alarmId/$scheduledAt")
            putExtra(WakeConfirmActivity.EXTRA_ALARM_ID, alarmId)
            putExtra(WakeConfirmActivity.EXTRA_ALARM_FIRE_ID, fireId)
            putExtra(WakeConfirmActivity.EXTRA_SCHEDULED_AT, scheduledAt)
            putExtra(WakeConfirmActivity.EXTRA_COUNTDOWN_SECONDS, CONFIRM_WAIT_SECONDS)
            putExtra(WakeConfirmActivity.EXTRA_DEADLINE_MILLIS, deadlineMillis)
            putExtra(WakeConfirmActivity.EXTRA_REFIRE_COUNT, refireCount)
        }
        val fullScreenPi = PendingIntent.getActivity(
            context,
            notifId,
            activityIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val title = AlarmPublicText.wakeConfirmTitle(label, hideLabel)
        val remainingAttempts = MAX_REFIRES - refireCount
        val contentText = if (remainingAttempts > 0) {
            "Confirm within ${CONFIRM_WAIT_SECONDS}s or the alarm rings again ($remainingAttempts left)."
        } else {
            "Final check — confirm within ${CONFIRM_WAIT_SECONDS}s."
        }
        val notification = NotificationCompat.Builder(context, CHANNEL_WAKE_CONFIRM)
            .setSmallIcon(R.drawable.ic_alarm)
            .setContentTitle(title)
            .setContentText(contentText)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setFullScreenIntent(fullScreenPi, true)
            .setContentIntent(fullScreenPi)
            .setAutoCancel(true)
            .setOngoing(true)
            .build()

        nm.notify(notifTag, notifId, notification)
        return true
    }

    private fun cancelPrompt(alarmId: Long, scheduledAt: Long, cancelLegacyUntagged: Boolean = false) {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        nm.cancel(notificationTag(alarmId, scheduledAt), notificationId(alarmId, scheduledAt))
        if (cancelLegacyUntagged) {
            nm.cancel(notificationId(alarmId, 0L))
        }
    }

    private suspend fun recordIncident(
        alarmId: Long,
        fireId: String,
        scheduledAt: Long,
        status: String,
        reasonCode: String,
        type: String = AlarmIncidentEvent.TYPE_WAKE_CONFIRM
    ) {
        alarmIncidentRepository.record(
            alarmId = alarmId,
            fireId = fireId,
            scheduledAt = scheduledAt,
            type = type,
            status = status,
            reasonCode = reasonCode,
            source = "WakeConfirmWorker"
        )
    }
}
