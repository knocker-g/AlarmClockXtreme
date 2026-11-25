package com.sysadmindoc.alarmclock.worker

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.telephony.SmsManager
import android.util.Log
import androidx.annotation.VisibleForTesting
import androidx.core.content.ContextCompat
import androidx.hilt.work.HiltWorker
import androidx.sqlite.db.SimpleSQLiteQuery
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.sysadmindoc.alarmclock.R
import com.sysadmindoc.alarmclock.data.backup.JournalDiskState
import com.sysadmindoc.alarmclock.data.backup.RestoreJournalCoordinator
import com.sysadmindoc.alarmclock.data.local.entity.AlarmIncidentEvent
import com.sysadmindoc.alarmclock.data.preferences.PreferencesManager
import com.sysadmindoc.alarmclock.data.repository.AlarmIncidentRepository
import com.sysadmindoc.alarmclock.data.repository.AlarmRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale

/**
 * v1.2.0: Guardian Angel worker.
 *
 * CURRENT_IMPLEMENTATION: TEMPORARY_LEGACY_CONTAINMENT
 * FUTURE_OWNER: KNO-50 / KNO-172 / KNO-173
 * AT_MOST_ONCE: NOT_PROVEN_WITHOUT_KNO172
 * TOCTOU: NON_ATOMIC_TELEPHONY_IPC_BOUNDARY
 *
 * If the alarm was not dismissed within `guardianDelaySec`, escalates to the
 * emergency contact via SMS after fail-closed occurrence, restore, and
 * active-session verification. Direct SMS is used when SEND_SMS is granted.
 */
@HiltWorker
class GuardianWorker @AssistedInject constructor(
    @Assisted private val context: Context,
    @Assisted workerParams: WorkerParameters,
    private val alarmRepository: AlarmRepository,
    private val preferencesManager: PreferencesManager,
    private val alarmIncidentRepository: AlarmIncidentRepository
) : CoroutineWorker(context, workerParams) {

    @VisibleForTesting
    internal var smsSenderOverride: ((phone: String, message: String) -> Boolean)? = null

    override suspend fun doWork(): Result {
        val alarmId = inputData.getLong(KEY_ALARM_ID, -1L)
        val scheduledAt = inputData.getLong(KEY_SCHEDULED_AT, 0L)
        val fireId = inputData.getString(KEY_ALARM_FIRE_ID)?.trim().orEmpty()
        val expectedGeneration = inputData.getInt(KEY_EXPECTED_GENERATION, 0)
        val expectedRestoreEpoch = inputData.getLong(KEY_EXPECTED_RESTORE_EPOCH, 0L)
        val rawPhone = inputData.getString(KEY_GUARDIAN_PHONE)?.trim().orEmpty()
        val label = inputData.getString(KEY_ALARM_LABEL).orEmpty()

        if (isStopped) {
            recordDiagnostic(alarmId, scheduledAt, fireId, AlarmIncidentEvent.STATUS_SKIPPED, "GUARDIAN_WORKER_STOPPED")
            return Result.success()
        }

        // Fail closed on incomplete legacy or malformed WorkData.
        if (alarmId <= 0L || scheduledAt <= 0L || fireId.isBlank() || expectedRestoreEpoch <= 0L) {
            recordDiagnostic(alarmId, scheduledAt, fireId, AlarmIncidentEvent.STATUS_SKIPPED, "GUARDIAN_INCOMPLETE_WORK_DATA")
            return Result.success()
        }

        if (rawPhone.isBlank()) {
            recordDiagnostic(alarmId, scheduledAt, fireId, AlarmIncidentEvent.STATUS_SKIPPED, "GUARDIAN_INVALID_RECIPIENT")
            return Result.success()
        }
        val phone = GuardianEscalationPolicy.sanitisePhone(rawPhone) ?: run {
            recordDiagnostic(alarmId, scheduledAt, fireId, AlarmIncidentEvent.STATUS_SKIPPED, "GUARDIAN_INVALID_RECIPIENT")
            return Result.success()
        }
        if (!GuardianEscalationPolicy.canSendDirectSms(hasPermission(Manifest.permission.SEND_SMS))) {
            recordDiagnostic(alarmId, scheduledAt, fireId, AlarmIncidentEvent.STATUS_SKIPPED, "GUARDIAN_NO_SMS_PERMISSION")
            return Result.success()
        }

        val diskState = try {
            RestoreJournalCoordinator.readVerifiedDiskState(applicationContext)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Throwable) {
            null
        }
        val restoreClear = when (diskState) {
            is JournalDiskState.Absent -> true
            is JournalDiskState.Verified -> diskState.phase != RestoreJournalCoordinator.PHASE_PREPARED
            is JournalDiskState.InvalidOrBlocked, null -> false
        }
        if (!restoreClear) {
            recordDiagnostic(alarmId, scheduledAt, fireId, AlarmIncidentEvent.STATUS_SKIPPED, "GUARDIAN_RESTORE_PREPARED_OR_BLOCKED")
            return Result.success()
        }

        val alarmFetch = try {
            FetchOutcome.Value(alarmRepository.getById(alarmId)?.sanitized())
        } catch (e: CancellationException) {
            throw e
        } catch (_: Throwable) {
            FetchOutcome.Failed
        }
        if (alarmFetch is FetchOutcome.Failed) {
            recordDiagnostic(alarmId, scheduledAt, fireId, AlarmIncidentEvent.STATUS_SKIPPED, "GUARDIAN_DB_READ_FAILED")
            return Result.success()
        }
        val alarm = (alarmFetch as FetchOutcome.Value).value ?: run {
            recordDiagnostic(alarmId, scheduledAt, fireId, AlarmIncidentEvent.STATUS_SKIPPED, "GUARDIAN_ALARM_MISSING")
            return Result.success()
        }
        if (!alarm.isEnabled) {
            recordDiagnostic(alarmId, scheduledAt, fireId, AlarmIncidentEvent.STATUS_SKIPPED, "GUARDIAN_ALARM_DISABLED")
            return Result.success()
        }
        if (!alarm.guardianEnabled) {
            recordDiagnostic(alarmId, scheduledAt, fireId, AlarmIncidentEvent.STATUS_SKIPPED, "GUARDIAN_DISABLED")
            return Result.success()
        }

        val generationMatches = if (alarm.isQuickAlarm) {
            expectedGeneration <= 0 || alarm.generation == expectedGeneration
        } else {
            expectedGeneration > 0 && alarm.generation == expectedGeneration
        }
        if (!generationMatches) {
            recordDiagnostic(alarmId, scheduledAt, fireId, AlarmIncidentEvent.STATUS_SKIPPED, "GUARDIAN_GENERATION_MISMATCH")
            return Result.success()
        }

        val currentRecipient = GuardianEscalationPolicy.sanitisePhone(alarm.guardianPhone.trim())
        if (currentRecipient == null || currentRecipient != phone) {
            recordDiagnostic(alarmId, scheduledAt, fireId, AlarmIncidentEvent.STATUS_SKIPPED, "GUARDIAN_RECIPIENT_MISMATCH")
            return Result.success()
        }

        val metaEpoch = try {
            alarmRepository.database.scheduleAuthorityDao().getMetaById(1)?.restoreEpoch
        } catch (e: CancellationException) {
            throw e
        } catch (_: Throwable) {
            null
        }
        if (metaEpoch == null || metaEpoch <= 0L || metaEpoch != expectedRestoreEpoch) {
            recordDiagnostic(alarmId, scheduledAt, fireId, AlarmIncidentEvent.STATUS_SKIPPED, "GUARDIAN_RESTORE_EPOCH_MISMATCH")
            return Result.success()
        }

        val terminalCheck = try {
            val eventCount = alarmRepository.database.alarmEventDao()
                .countTerminalByOccurrence(alarmId, scheduledAt)
            val incidentCount = withContext(Dispatchers.IO) {
                countTerminalIncidentsByOccurrence(alarmId, scheduledAt, fireId)
            }
            FetchOutcome.Value(eventCount > 0 || incidentCount > 0)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Throwable) {
            FetchOutcome.Failed
        }
        if (terminalCheck is FetchOutcome.Failed) {
            recordDiagnostic(alarmId, scheduledAt, fireId, AlarmIncidentEvent.STATUS_SKIPPED, "GUARDIAN_DB_READ_FAILED")
            return Result.success()
        }
        if ((terminalCheck as FetchOutcome.Value).value == true) {
            recordDiagnostic(alarmId, scheduledAt, fireId, AlarmIncidentEvent.STATUS_SKIPPED, "GUARDIAN_OCCURRENCE_ALREADY_TERMINAL")
            return Result.success()
        }

        val settings = try {
            preferencesManager.getCurrentSettings()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Throwable) {
            null
        }
        if (settings == null) {
            recordDiagnostic(alarmId, scheduledAt, fireId, AlarmIncidentEvent.STATUS_SKIPPED, "GUARDIAN_SETTINGS_READ_FAILED")
            return Result.success()
        }
        val activeSessionMatches = settings.activeAlarmId == alarmId &&
            settings.activeAlarmScheduledAt == scheduledAt &&
            settings.activeAlarmFireId == fireId &&
            settings.activeAlarmState == ACTIVE_STATE_FIRING
        if (!activeSessionMatches) {
            recordDiagnostic(alarmId, scheduledAt, fireId, AlarmIncidentEvent.STATUS_SKIPPED, "GUARDIAN_SESSION_NOT_FIRING_MATCH")
            return Result.success()
        }

        val messageRes = GuardianEscalationPolicy.messageResFor(label)
        val message = if (messageRes == R.string.guardian_alert_message_with_label) {
            applicationContext.getString(messageRes, label)
        } else {
            applicationContext.getString(messageRes)
        }

        if (isStopped) {
            recordDiagnostic(alarmId, scheduledAt, fireId, AlarmIncidentEvent.STATUS_SKIPPED, "GUARDIAN_WORKER_STOPPED")
            return Result.success()
        }

        // Note: State checks above and Telephony IPC below are non-atomic
        // (TOCTOU: NON_ATOMIC_TELEPHONY_IPC_BOUNDARY). Post-IPC incident
        // recording is diagnostic only, not KNO-172 durable dispatch.
        val (sendStatus, sendReason) = sendDirectSmsOutcome(phone, message)
        recordDiagnostic(alarmId, scheduledAt, fireId, sendStatus, sendReason)

        return Result.success()
    }

    private fun sendDirectSmsOutcome(phone: String, message: String): Pair<String, String> =
        try {
            val sent = smsSenderOverride?.invoke(phone, message) ?: sendDirectSms(phone, message)
            if (sent) {
                AlarmIncidentEvent.STATUS_SUCCEEDED to "GUARDIAN_SMS_SENT"
            } else {
                AlarmIncidentEvent.STATUS_FAILED to "GUARDIAN_SMS_FAILED"
            }
        } catch (e: Exception) {
            Log.w(TAG, "Guardian SMS dispatch failed", e)
            AlarmIncidentEvent.STATUS_FAILED to
                "GUARDIAN_SMS_EXCEPTION_${e.javaClass.simpleName.uppercase(Locale.US)}"
        }

    private fun countTerminalIncidentsByOccurrence(
        alarmId: Long,
        scheduledAt: Long,
        fireId: String
    ): Int {
        val sql =
            "SELECT COUNT(*) FROM alarm_incident_events " +
                "WHERE alarmId = ? AND scheduledAt = ? AND fireId = ? " +
                "AND status = 'SUCCEEDED' " +
                "AND (reasonCode IN ('DISMISSED', 'MAX_SNOOZE_DISMISSED', 'SNOOZED', 'PREEMPTED_BY_OVERLAPPING_ALARM') " +
                "OR reasonCode LIKE 'AUTO_SILENCED_AFTER_%')"
        val query = SimpleSQLiteQuery(sql, arrayOf<Any>(alarmId, scheduledAt, fireId))
        return alarmRepository.database.query(query).use { cursor ->
            if (cursor.moveToFirst()) cursor.getInt(0) else 0
        }
    }

    private fun sendDirectSms(phone: String, message: String): Boolean =
        try {
            val smsManager = context.getSystemService(SmsManager::class.java)
            smsManager?.sendTextMessage(phone, null, message, null, null)
            smsManager != null
        } catch (e: Exception) {
            Log.w(TAG, "SmsManager.sendTextMessage threw exception", e)
            false
        }

    private suspend fun recordDiagnostic(
        alarmId: Long,
        scheduledAt: Long,
        fireId: String,
        status: String,
        reasonCode: String
    ) {
        try {
            val safeAlarmId = alarmId.coerceAtLeast(0L)
            val safeScheduledAt = scheduledAt.coerceAtLeast(0L)
            val resolvedFireId = fireId.ifBlank {
                AlarmIncidentEvent.fireIdFor(safeAlarmId, safeScheduledAt)
            }
            alarmIncidentRepository.record(
                alarmId = safeAlarmId,
                fireId = resolvedFireId,
                scheduledAt = safeScheduledAt,
                type = INCIDENT_TYPE_GUARDIAN,
                status = status,
                reasonCode = reasonCode,
                source = TAG
            )
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to record Guardian diagnostic incident ($reasonCode)", t)
        }
    }

    private fun hasPermission(name: String): Boolean =
        ContextCompat.checkSelfPermission(context, name) == PackageManager.PERMISSION_GRANTED

    private sealed interface FetchOutcome<out T> {
        data class Value<T>(val value: T) : FetchOutcome<T>
        data object Failed : FetchOutcome<Nothing>
    }

    companion object {
        private const val TAG = "GuardianWorker"
        private const val ACTIVE_STATE_FIRING = "FIRING"

        const val KEY_ALARM_ID = "alarm_id"
        const val KEY_SCHEDULED_AT = "scheduled_at"
        const val KEY_ALARM_FIRE_ID = "alarm_fire_id"
        const val KEY_EXPECTED_GENERATION = "expected_generation"
        const val KEY_EXPECTED_RESTORE_EPOCH = "expected_restore_epoch"
        const val KEY_GUARDIAN_PHONE = "guardian_phone"
        const val KEY_ALARM_LABEL = "alarm_label"

        const val INCIDENT_TYPE_GUARDIAN = "GUARDIAN"

        fun alarmTag(alarmId: Long): String {
            require(alarmId > 0L) { "alarmId must be positive, was: $alarmId" }
            return "guardian_$alarmId"
        }
    }
}
