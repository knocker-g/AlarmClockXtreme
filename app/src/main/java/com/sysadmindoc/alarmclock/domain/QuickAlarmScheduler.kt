package com.sysadmindoc.alarmclock.domain

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import com.sysadmindoc.alarmclock.data.model.Alarm
import com.sysadmindoc.alarmclock.data.repository.AlarmRepository
import com.sysadmindoc.alarmclock.receiver.AlarmReceiver
import dagger.hilt.android.qualifiers.ApplicationContext
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
    val expiredAlarms: List<Alarm> = emptyList()
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

    suspend fun scheduleQuickAlarm(alarm: Alarm): ScheduleOutcome {
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

        val pendingIntent = createQuickPendingIntent(context, alarm)

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
    ): QuickReconcileReport = reconcileMutex.withLock {
        val pendingQuickList = repository.getPendingQuickAlarms()
        if (pendingQuickList.isEmpty()) return QuickReconcileReport()

        val armedFuture = mutableListOf<Alarm>()
        val boundedLate = mutableListOf<Alarm>()
        val expired = mutableListOf<Alarm>()

        val nowBoot = currentBootCount()

        pendingQuickList.forEach { alarm ->
            val isSameBoot = alarm.quickBootCount > 0 && alarm.quickBootCount == nowBoot && alarm.quickRelativeElapsedMs > 0
            val isFuture = if (isSameBoot) {
                alarm.quickRelativeElapsedMs > nowElapsedMs
            } else {
                alarm.quickTriggerEpochMs > nowEpochMs
            }

            if (isFuture) {
                scheduleQuickAlarm(alarm)
                armedFuture.add(alarm)
            } else {
                val latenessMs = if (isSameBoot) {
                    nowElapsedMs - alarm.quickRelativeElapsedMs
                } else {
                    nowEpochMs - alarm.quickTriggerEpochMs
                }

                if (latenessMs <= BOUNDED_LATE_RECOVERY_WINDOW_MS && entrySource == ReconcileEntrySource.SYSTEM_RECOVERY) {
                    boundedLate.add(alarm)
                } else {
                    repository.delete(alarm)
                    cancelQuickPendingIntent(context, alarm)
                    expired.add(alarm)
                }
            }
        }

        return QuickReconcileReport(
            armedFutureAlarms = armedFuture,
            boundedLateRecoveries = boundedLate,
            expiredAlarms = expired
        )
    }

    suspend fun deleteQuickAlarm(alarm: Alarm) {
        repository.delete(alarm)
        cancelQuickPendingIntent(context, alarm)
    }

    private fun createQuickPendingIntent(context: Context, alarm: Alarm): PendingIntent {
        val intent = Intent(context, AlarmReceiver::class.java).apply {
            putExtra(AlarmScheduler.EXTRA_ALARM_ID, alarm.id)
            putExtra(AlarmScheduler.EXTRA_SCHEDULED_AT, alarm.quickTriggerEpochMs)
            putExtra("EXTRA_QUICK_ALARM", true)
        }
        return PendingIntent.getBroadcast(
            context,
            (REQUEST_CODE_QUICK_BASE + alarm.id).toInt(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun cancelQuickPendingIntent(context: Context, alarm: Alarm) {
        val am = alarmManager ?: return
        val pendingIntent = createQuickPendingIntent(context, alarm)
        am.cancel(pendingIntent)
    }
}
