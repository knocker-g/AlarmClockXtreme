package com.sysadmindoc.alarmclock.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.sysadmindoc.alarmclock.AlarmClockApp
import com.sysadmindoc.alarmclock.data.local.entity.AlarmEvent
import com.sysadmindoc.alarmclock.domain.AlarmScheduler
import com.sysadmindoc.alarmclock.service.WebhookEvent
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import java.time.Instant
import java.time.ZoneId
import java.util.Locale

/**
 * Handles the "Skip this alarm" action from the persistent next-alarm notification.
 *
 * Performs the work in [goAsync] using a Hilt EntryPoint rather than starting a
 * foreground service: the previous implementation routed through DismissReceiver,
 * which incorrectly triggered TTS, the morning briefing, the wake-confirmation
 * worker and the dismiss webhook — none of which apply to "skip".
 *
 * For repeating alarms the next occurrence is recomputed from one minute past the
 * trigger we just skipped (via NextAlarmCalculator). For one-shot alarms it
 * disables the alarm entirely.
 */
class SkipNextReceiver : BroadcastReceiver() {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    internal var testScope: CoroutineScope? = null
    internal var testEntryPoint: AlarmClockApp.AppEntryPoint? = null

    override fun onReceive(context: Context, intent: Intent) {
        val alarmId = intent.getLongExtra(AlarmScheduler.EXTRA_ALARM_ID, -1L)
        val expectedScheduledAt = intent.getLongExtra(AlarmScheduler.EXTRA_SCHEDULED_AT, -1L)
        if (alarmId == -1L) return

        val pending = goAsync()
        val activeScope = testScope ?: scope
        activeScope.launch {
            try {
                // Match BootReceiver / MissedAlarmUnlockReceiver: goAsync()'s
                // BroadcastReceiver window is ~10 s on most Android versions;
                // a corrupt DB or storage lock could otherwise pin the
                // PendingResult past that ceiling and ANR. 8 s leaves headroom.
                withTimeout(8_000L) {
                    val ep = testEntryPoint ?: EntryPointAccessors.fromApplication(
                        context.applicationContext,
                        AlarmClockApp.AppEntryPoint::class.java
                    )
                    val repo = ep.alarmRepository()
                    val scheduler = ep.alarmScheduler()
                    val eventRepo = ep.alarmEventRepository()
                    val webhookService = ep.webhookService()
                    val preferencesManager = ep.preferencesManager()

                    val alarm = repo.getById(alarmId) ?: return@withTimeout

                    // Validate occurrence identity: if expectedScheduledAt is provided (> 0) and differs
                    // from current alarm.nextTriggerTime, the skip action is stale/duplicate and MUST fail closed.
                    if (expectedScheduledAt > 0L && expectedScheduledAt != alarm.nextTriggerTime) {
                        Log.i("SkipNextReceiver", "Rejecting stale skip for alarm $alarmId: expected $expectedScheduledAt != current ${alarm.nextTriggerTime}")
                        return@withTimeout
                    }

                    // Reject skipping an alarm that is currently FIRING in active session.
                    val cachedSettings = preferencesManager.getCachedSettings()
                    if (alarm.id == cachedSettings.activeAlarmId && cachedSettings.activeAlarmState == "FIRING") {
                        Log.i("SkipNextReceiver", "Rejecting skip for active FIRING alarm $alarmId")
                        return@withTimeout
                    }

                    if (!alarm.isRecurringSchedule) {
                        repo.setEnabled(alarm.id, enabled = false, nextTrigger = 0)
                        scheduler.cancel(alarm.id)
                    } else {
                        val skipFloorMs = alarm.nextTriggerTime
                            .coerceAtLeast(System.currentTimeMillis()) + 60_000L
                        val outcome = scheduler.schedule(alarm, notBeforeMillis = skipFloorMs)
                        if (outcome is com.sysadmindoc.alarmclock.domain.ScheduleOutcome.RegistrationFailed) {
                            Log.w("SkipNextReceiver", "Replacement re-arm registration failed for skipped alarm $alarmId; pending background recovery")
                        }
                    }

                    runCatching {
                        eventRepo.record(
                            AlarmEvent(
                                alarmId = alarm.id,
                                alarmLabel = alarm.label,
                                scheduledTime = alarm.nextTriggerTime,
                                firedAt = alarm.nextTriggerTime,
                                action = AlarmEvent.ACTION_SKIPPED,
                                actionAt = System.currentTimeMillis(),
                                challengeType = alarm.challengeType,
                                dayOfWeek = Instant.ofEpochMilli(
                                    alarm.nextTriggerTime.coerceAtLeast(System.currentTimeMillis())
                                ).atZone(ZoneId.systemDefault()).dayOfWeek.value
                            )
                        )
                    }

                    webhookService.fireAsync(
                        event = WebhookEvent.AlarmSkipped,
                        alarmId = alarm.id,
                        label = alarm.label,
                        timeFormatted = "%02d:%02d".format(Locale.US, alarm.hour, alarm.minute),
                        scheduledForMillis = alarm.nextTriggerTime.takeIf { it > 0L },
                        fireId = null
                    )
                }
            } catch (e: TimeoutCancellationException) {
                Log.e("SkipNextReceiver", "Timed out skipping alarm $alarmId", e)
            } catch (e: Exception) {
                Log.e("SkipNextReceiver", "Failed to skip alarm $alarmId", e)
            } finally {
                pending?.finish()
            }
        }
    }
}
