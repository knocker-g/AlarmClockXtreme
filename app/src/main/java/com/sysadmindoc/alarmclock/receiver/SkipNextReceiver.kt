package com.sysadmindoc.alarmclock.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.sysadmindoc.alarmclock.AlarmClockApp
import com.sysadmindoc.alarmclock.data.local.entity.AlarmEvent
import com.sysadmindoc.alarmclock.domain.AlarmScheduler
import com.sysadmindoc.alarmclock.domain.evaluator.DurableSkipClaimAdapter
import com.sysadmindoc.alarmclock.domain.evaluator.DurableSkipClaimAdapterOutcome
import com.sysadmindoc.alarmclock.service.WebhookEvent
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.time.Instant
import java.time.ZoneId
import java.util.Locale

/**
 * Handles the "Skip this alarm" action from the persistent next-alarm notification.
 *
 * Performs the work in [goAsync] using a Hilt EntryPoint rather than starting a
 * foreground service. Uses [DurableSkipClaimAdapter] for single-transaction DB skip claim,
 * occurrence identity revalidation, ScheduleException persistence, V35 IssuedOccurrence generation,
 * and DurableOperationIntent creation before legacy OS AlarmManager re-arm registration.
 */
class SkipNextReceiver : BroadcastReceiver() {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    internal var testScope: CoroutineScope? = null
    internal var testEntryPoint: AlarmClockApp.AppEntryPoint? = null

    override fun onReceive(context: Context, intent: Intent) {
        val alarmId = intent.getLongExtra(AlarmScheduler.EXTRA_ALARM_ID, -1L)
        val expectedScheduledAt = intent.getLongExtra(AlarmScheduler.EXTRA_SCHEDULED_AT, -1L)
        if (alarmId <= 0L || expectedScheduledAt <= 0L) {
            Log.w("SkipNextReceiver", "Rejecting skip for alarm $alarmId: missing or non-positive occurrence identity ($expectedScheduledAt)")
            return
        }

        val pending = goAsync()
        val isTestExecution = testScope != null

        val executeSkipLogic: suspend () -> Unit = {
            val ep = testEntryPoint ?: EntryPointAccessors.fromApplication(
                context.applicationContext,
                AlarmClockApp.AppEntryPoint::class.java
            )
            val repo = ep.alarmRepository()
            val scheduler = ep.alarmScheduler()
            val eventRepo = ep.alarmEventRepository()
            val webhookService = ep.webhookService()
            val preferencesManager = ep.preferencesManager()

            val alarm = repo.getById(alarmId)
            if (alarm != null && alarm.isEnabled && alarm.nextTriggerTime > 0L) {
                // Validate occurrence identity: expectedScheduledAt MUST match current alarm.nextTriggerTime
                if (expectedScheduledAt == alarm.nextTriggerTime) {
                    // Reject skipping an alarm that is currently FIRING in active session.
                    val currentSettings = preferencesManager.getCurrentSettings()
                    if (alarm.id != currentSettings.activeAlarmId || currentSettings.activeAlarmState != "FIRING") {
                        val originalScheduledTime = alarm.nextTriggerTime
                        val outcome: com.sysadmindoc.alarmclock.domain.ScheduleOutcome

                        if (!alarm.isRecurringSchedule) {
                            // Atomic Room CAS disable for one-shot alarm
                            val rowsUpdated = repo.casDisableOneShot(alarm.id, expectedTrigger = expectedScheduledAt)
                            if (rowsUpdated > 0) {
                                scheduler.cancel(alarm.id)
                                outcome = com.sysadmindoc.alarmclock.domain.ScheduleOutcome.NoArm(alarm.id, "ONE_SHOT_DISABLED")
                            } else {
                                Log.i("SkipNextReceiver", "Stale or concurrent duplicate one-shot skip rejected for alarm $alarmId at expected $expectedScheduledAt")
                            }
                        } else {
                            // Durable Skip Claim Adapter: Single-transaction DB skip claim + V35 occurrence + Durable intent
                            val adapter = DurableSkipClaimAdapter(database = repo.database)
                            val adapterResult = adapter.executeSkipClaimAndReconcile(
                                alarmId = alarm.id,
                                expectedScheduledAt = expectedScheduledAt,
                                nowEpochMs = System.currentTimeMillis()
                            )
                            println("DEBUG_RECEIVER: outcome=${adapterResult.outcome}, reason=${adapterResult.decisionReason}")

                            if (adapterResult.outcome == DurableSkipClaimAdapterOutcome.SUCCESS_COMMITTED && adapterResult.updatedAlarm != null) {
                                val updatedAlarm = adapterResult.updatedAlarm
                                outcome = scheduler.scheduleAt(updatedAlarm, updatedAlarm.nextTriggerTime)
                                if (outcome is com.sysadmindoc.alarmclock.domain.ScheduleOutcome.RegistrationFailed) {
                                    Log.w("SkipNextReceiver", "Replacement re-arm registration failed for skipped alarm $alarmId; enqueuing background recovery")
                                    try {
                                        com.sysadmindoc.alarmclock.worker.BootRescheduleWorker.enqueue(
                                            context = context,
                                            sourceAction = "SKIP_REGISTRATION_FAILED",
                                            forceRecalculate = false
                                        )
                                    } catch (e: Exception) {
                                        Log.e("SkipNextReceiver", "Failed to enqueue BootRescheduleWorker after skip registration failure for alarm $alarmId", e)
                                    }
                                }

                                runCatching {
                                    eventRepo.record(
                                        AlarmEvent(
                                            alarmId = alarm.id,
                                            alarmLabel = alarm.label,
                                            scheduledTime = originalScheduledTime,
                                            firedAt = originalScheduledTime,
                                            action = AlarmEvent.ACTION_SKIPPED,
                                            actionAt = System.currentTimeMillis(),
                                            challengeType = alarm.challengeType,
                                            dayOfWeek = Instant.ofEpochMilli(
                                                originalScheduledTime.coerceAtLeast(System.currentTimeMillis())
                                            ).atZone(ZoneId.systemDefault()).dayOfWeek.value
                                        )
                                    )
                                }

                                runCatching {
                                    webhookService.fireAsync(
                                        event = WebhookEvent.AlarmSkipped,
                                        alarmId = alarm.id,
                                        label = alarm.label,
                                        timeFormatted = "%02d:%02d".format(Locale.US, alarm.hour, alarm.minute),
                                        scheduledForMillis = originalScheduledTime.takeIf { it > 0L },
                                        fireId = null
                                    )
                                }
                            } else {
                                Log.i("SkipNextReceiver", "Durable skip claim rejected or already committed for alarm $alarmId: ${adapterResult.decisionReason}")
                            }
                        }
                    } else {
                        Log.i("SkipNextReceiver", "Rejecting skip for active FIRING alarm $alarmId")
                    }
                } else {
                    Log.i("SkipNextReceiver", "Rejecting stale skip for alarm $alarmId: expected $expectedScheduledAt != current ${alarm.nextTriggerTime}")
                }
            } else {
                Log.i("SkipNextReceiver", "Rejecting skip for disabled/unarmed alarm $alarmId")
            }
        }

        if (isTestExecution) {
            runBlocking {
                try {
                    executeSkipLogic()
                } catch (e: Exception) {
                    Log.e("SkipNextReceiver", "Failed to skip alarm $alarmId in test execution", e)
                } finally {
                    pending?.finish()
                }
            }
        } else {
            scope.launch {
                try {
                    withTimeout(8_000L) {
                        executeSkipLogic()
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
}
