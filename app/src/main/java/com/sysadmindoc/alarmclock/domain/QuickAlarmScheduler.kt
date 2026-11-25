package com.sysadmindoc.alarmclock.domain

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import com.sysadmindoc.alarmclock.data.backup.RestoreJournalCoordinator
import com.sysadmindoc.alarmclock.data.model.Alarm
import com.sysadmindoc.alarmclock.data.repository.AlarmRepository
import com.sysadmindoc.alarmclock.receiver.AlarmReceiver
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

enum class ReconcileEntrySource {
    SYSTEM_RECOVERY,
    MANUAL_APP_LAUNCH
}

data class QuickReconcileReport(
    val armedFutureAlarms: List<Alarm> = emptyList(),
    val boundedLateRecoveries: List<Alarm> = emptyList(),
    val expiredAlarms: List<Alarm> = emptyList(),
    val failedAlarms: List<Alarm> = emptyList()
)

@Singleton
open class QuickAlarmScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repository: AlarmRepository
) {
    companion object {
        private const val TAG = "QuickAlarmScheduler"
        const val BOUNDED_LATE_RECOVERY_WINDOW_MS = 10L * 60 * 1000 // 10 minutes
        const val REQUEST_CODE_QUICK_BASE = 80_000
    }

    private val reconcileMutex = Mutex()

    private val alarmManager: AlarmManager? by lazy {
        context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager
    }

    open fun currentBootCount(): Long = runCatching {
        Settings.Global.getLong(context.contentResolver, Settings.Global.BOOT_COUNT)
    }.getOrDefault(-1L)

    suspend fun scheduleQuickAlarm(alarm: Alarm): ScheduleOutcome =
        RestoreJournalCoordinator.withRecoveryGate(
            context = context,
            onBlocked = { ScheduleOutcome.NoArm(alarm.id, "RESTORE_PREPARED_FENCE") }
        ) {
            reconcileMutex.withLock {
                scheduleQuickAlarmLocked(alarm)
            }
        }

    private suspend fun scheduleQuickAlarmLocked(alarm: Alarm): ScheduleOutcome {
        if (!alarm.isQuickAlarm) {
            return ScheduleOutcome.NoArm(alarm.id, "NOT_QUICK_ALARM")
        }

        val am = alarmManager ?: return ScheduleOutcome.RegistrationFailed(
            alarm.id,
            IllegalStateException("AlarmManager unavailable")
        )

        if (Build.VERSION.SDK_INT >= 31 && !am.canScheduleExactAlarms()) {
            Log.w(TAG, "Exact alarm permission revoked for Quick Alarm ${alarm.id}")
            return ScheduleOutcome.PermissionBlocked(
                alarm.id,
                "EXACT_ALARM_PERMISSION_REVOKED"
            )
        }

        val nowBoot = currentBootCount()
        val isSameBoot = alarm.quickBootCount > 0 && alarm.quickBootCount == nowBoot && alarm.quickRelativeElapsedMs > 0

        val restoreEpoch = try {
            repository.database.scheduleAuthorityDao().getMetaById(1)?.restoreEpoch ?: 0L
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            Log.w(TAG, "Failed reading ScheduleAuthorityMeta restoreEpoch for Quick Alarm ${alarm.id}", e)
            0L
        }
        val pendingIntent = createQuickPendingIntent(context, alarm, restoreEpoch)

        return try {
            if (isSameBoot) {
                am.setExactAndAllowWhileIdle(
                    AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    alarm.quickRelativeElapsedMs,
                    pendingIntent
                )
                ScheduleOutcome.Armed(alarm.id, alarm.quickRelativeElapsedMs)
            } else {
                am.setExactAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    alarm.quickTriggerEpochMs,
                    pendingIntent
                )
                ScheduleOutcome.Armed(alarm.id, alarm.quickTriggerEpochMs)
            }
        } catch (se: SecurityException) {
            Log.w(TAG, "SecurityException scheduling exact Quick Alarm ${alarm.id}", se)
            ScheduleOutcome.PermissionBlocked(alarm.id, "EXACT_ALARM_PERMISSION_REVOKED")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to schedule exact Quick Alarm ${alarm.id}", e)
            ScheduleOutcome.RegistrationFailed(alarm.id, e)
        }
    }

    suspend fun reconcileQuickAlarms(
        entrySource: ReconcileEntrySource,
        nowEpochMs: Long = System.currentTimeMillis(),
        nowElapsedMs: Long = SystemClock.elapsedRealtime()
    ): QuickReconcileReport = RestoreJournalCoordinator.withRecoveryGate(
        context = context,
        onBlocked = { QuickReconcileReport() }
    ) {
        reconcileMutex.withLock {
            val pendingQuickList = repository.getPendingQuickAlarms()
            if (pendingQuickList.isEmpty()) return@withLock QuickReconcileReport()

            val armedFuture = mutableListOf<Alarm>()
            val boundedLate = mutableListOf<Alarm>()
            val expired = mutableListOf<Alarm>()
            val failed = mutableListOf<Alarm>()

            val nowBoot = currentBootCount()

            pendingQuickList.forEach { alarm ->
                val isSameBoot = alarm.quickBootCount > 0 && alarm.quickBootCount == nowBoot && alarm.quickRelativeElapsedMs > 0
                val isFuture = if (isSameBoot) {
                    alarm.quickRelativeElapsedMs > nowElapsedMs
                } else {
                    alarm.quickTriggerEpochMs > nowEpochMs
                }

                if (isFuture) {
                    when (scheduleQuickAlarmLocked(alarm)) {
                        is ScheduleOutcome.Armed -> armedFuture.add(alarm)
                        is ScheduleOutcome.RegistrationFailed,
                        is ScheduleOutcome.PermissionBlocked -> failed.add(alarm)
                        is ScheduleOutcome.NoArm,
                        is ScheduleOutcome.BatchResult -> Unit
                    }
                } else {
                    val latenessMs = if (isSameBoot) {
                        nowElapsedMs - alarm.quickRelativeElapsedMs
                    } else {
                        nowEpochMs - alarm.quickTriggerEpochMs
                    }

                    if (latenessMs <= BOUNDED_LATE_RECOVERY_WINDOW_MS && entrySource == ReconcileEntrySource.SYSTEM_RECOVERY) {
                        boundedLate.add(alarm)
                    } else {
                        val osCancelled = cancelQuickPendingIntentLocked(context, alarm)
                        if (osCancelled) {
                            repository.delete(alarm)
                            expired.add(alarm)
                        } else {
                            Log.w(TAG, "Preserving expired Quick Alarm ${alarm.id} in DB because OS cancel failed")
                            failed.add(alarm)
                        }
                    }
                }
            }

            QuickReconcileReport(
                armedFutureAlarms = armedFuture,
                boundedLateRecoveries = boundedLate,
                expiredAlarms = expired,
                failedAlarms = failed
            )
        }
    }

    internal suspend fun cancelQuickPendingIntentOnly(alarmId: Long): Boolean {
        if (alarmId <= 0L) return false
        return reconcileMutex.withLock {
            val am = alarmManager ?: return@withLock false
            val pendingIntent = lookupQuickPendingIntentForCancel(context, alarmId) ?: return@withLock true
            try {
                am.cancel(pendingIntent)
                pendingIntent.cancel()
                true
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Failed OS-only cancel for Quick Alarm $alarmId", e)
                false
            }
        }
    }

    suspend fun deleteQuickAlarm(alarm: Alarm): Boolean = reconcileMutex.withLock {
        val osCancelled = cancelQuickPendingIntentLocked(context, alarm)
        if (!osCancelled) {
            Log.w(TAG, "Preserving Quick Alarm ${alarm.id} in DB because OS cancel failed")
            return@withLock false
        }
        repository.delete(alarm)
        true
    }

    private fun createQuickPendingIntent(
        context: Context,
        alarm: Alarm,
        restoreEpoch: Long = 0L
    ): PendingIntent {
        val intent = Intent(context, AlarmReceiver::class.java).apply {
            putExtra(AlarmScheduler.EXTRA_ALARM_ID, alarm.id)
            putExtra(AlarmScheduler.EXTRA_SCHEDULED_AT, alarm.quickTriggerEpochMs)
            putExtra(AlarmScheduler.EXTRA_QUICK_ALARM, true)
            if (restoreEpoch > 0L) {
                putExtra(AlarmScheduler.EXTRA_RESTORE_EPOCH, restoreEpoch)
            }
        }
        return PendingIntent.getBroadcast(
            context,
            (REQUEST_CODE_QUICK_BASE + alarm.id).toInt(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun lookupQuickPendingIntentForCancel(context: Context, alarmId: Long): PendingIntent? {
        val intent = Intent(context, AlarmReceiver::class.java)
        return PendingIntent.getBroadcast(
            context,
            (REQUEST_CODE_QUICK_BASE + alarmId).toInt(),
            intent,
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun cancelQuickPendingIntentLocked(context: Context, alarm: Alarm): Boolean {
        val am = alarmManager ?: return false
        val pendingIntent = lookupQuickPendingIntentForCancel(context, alarm.id) ?: return true
        return try {
            am.cancel(pendingIntent)
            pendingIntent.cancel()
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Failed to cancel Quick Alarm ${alarm.id}", e)
            false
        }
    }
}
