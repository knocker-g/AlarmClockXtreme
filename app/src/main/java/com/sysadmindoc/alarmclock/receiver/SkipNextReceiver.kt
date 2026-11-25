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
    internal var clock: java.time.Clock = java.time.Clock.systemDefaultZone()
    @androidx.annotation.VisibleForTesting
    internal var lastOutcome: com.sysadmindoc.alarmclock.domain.ScheduleOutcome? = null

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
                        val originEpochFence = runCatching {
                            repo.database.scheduleAuthorityDao().getMetaById(1)?.restoreEpoch
                        }.getOrNull()?.takeIf { it > 0L }
                        val originAuthority = if (!alarm.isQuickAlarm) repo.getAuthorityByAlarmId(alarm.id) else null
                        val outcome: com.sysadmindoc.alarmclock.domain.ScheduleOutcome

                        if (!alarm.isRecurringSchedule) {
                            // Single-transaction Room CAS disable + Legacy V1 cancel witness persistence
                            val persistResult = try {
                                repo.casDisableOneShotWithLegacyV1CancelWitness(
                                    id = alarm.id,
                                    expectedTrigger = expectedScheduledAt
                                )
                            } catch (e: Exception) {
                                Log.w(
                                    "SkipNextReceiver",
                                    "Atomic one-shot CAS + witness persistence failed for alarm $alarmId; keeping DB/OS unchanged",
                                    e
                                )
                                lastOutcome = com.sysadmindoc.alarmclock.domain.ScheduleOutcome.RegistrationFailed(
                                    alarm.id,
                                    e
                                )
                                null
                            }
                            when (persistResult) {
                                is com.sysadmindoc.alarmclock.data.repository.AlarmRepository.LegacyV1CancelWitnessPersistResult.Committed -> {
                                    val cancelled = scheduler.cancelScheduledEntriesWithResult(
                                        alarmId = alarm.id,
                                        includeFollowUpWorkers = true,
                                        expectedRevision = originAuthority?.scheduleRevision,
                                        expectedGeneration = alarm.generation,
                                        expectedRestoreEpoch = originEpochFence
                                    )
                                    if (cancelled) {
                                        com.sysadmindoc.alarmclock.service.AlarmRuntimeState.clear(context, alarm.id)
                                        com.sysadmindoc.alarmclock.widget.WidgetUpdater.requestUpdate(context)
                                    }
                                    outcome = if (cancelled) {
                                        val settled = AlarmScheduler.completePendingLegacyV1CancelWitness(
                                            repo,
                                            persistResult.witnessToken
                                        )
                                        if (!settled) {
                                            Log.w(
                                                "SkipNextReceiver",
                                                "One-shot post-CAS witness settlement failed for alarm $alarmId (${persistResult.witnessToken}); enqueuing BootRescheduleWorker"
                                            )
                                            runCatching {
                                                com.sysadmindoc.alarmclock.worker.BootRescheduleWorker.enqueue(
                                                    context = context,
                                                    sourceAction = "SKIP_WITNESS_SETTLE_FAILED",
                                                    forceRecalculate = false
                                                )
                                            }
                                        }
                                        com.sysadmindoc.alarmclock.domain.ScheduleOutcome.NoArm(alarm.id, "ONE_SHOT_DISABLED")
                                    } else {
                                        Log.w("SkipNextReceiver", "One-shot post-CAS OS cancel failed for alarm $alarmId; preserving disabled DB state and enqueuing recovery")
                                        runCatching {
                                            com.sysadmindoc.alarmclock.worker.BootRescheduleWorker.enqueue(
                                                context = context,
                                                sourceAction = "SKIP_ONE_SHOT_CANCEL_FAILED",
                                                forceRecalculate = false
                                            )
                                        }
                                        com.sysadmindoc.alarmclock.domain.ScheduleOutcome.RegistrationFailed(
                                            alarm.id,
                                            IllegalStateException("One-shot post-CAS OS cancel failed")
                                        )
                                    }
                                    lastOutcome = outcome
                                }
                                is com.sysadmindoc.alarmclock.data.repository.AlarmRepository.LegacyV1CancelWitnessPersistResult.RejectedByExistingNonLegacyIntent -> {
                                    Log.w(
                                        "SkipNextReceiver",
                                        "Rejecting one-shot skip for alarm $alarmId due to unresolved non-legacy intent(s): ${persistResult.existingTokens}"
                                    )
                                    lastOutcome = com.sysadmindoc.alarmclock.domain.ScheduleOutcome.RegistrationFailed(
                                        alarm.id,
                                        IllegalStateException("Unresolved non-legacy intent blocks one-shot skip: ${persistResult.existingTokens}")
                                    )
                                }
                                com.sysadmindoc.alarmclock.data.repository.AlarmRepository.LegacyV1CancelWitnessPersistResult.CasRejected -> {
                                    Log.i("SkipNextReceiver", "Stale or concurrent duplicate one-shot skip rejected for alarm $alarmId at expected $expectedScheduledAt")
                                }
                                null -> {
                                    // Failure already logged and lastOutcome recorded above
                                }
                            }
                        } else {
                            // Durable Skip Claim Adapter: Single-transaction DB skip claim + V35 occurrence + Durable intent
                            val nowMs = clock.millis()
                            val adapter = DurableSkipClaimAdapter(database = repo.database)
                            val adapterResult = adapter.executeSkipClaimAndReconcile(
                                alarmId = alarm.id,
                                expectedScheduledAt = expectedScheduledAt,
                                nowEpochMs = nowMs
                            )
                            println("DEBUG_RECEIVER: outcome=${adapterResult.outcome}, reason=${adapterResult.decisionReason}")

                            if (adapterResult.outcome == DurableSkipClaimAdapterOutcome.SUCCESS_COMMITTED && adapterResult.updatedAlarm != null) {
                                val updatedAlarm = adapterResult.updatedAlarm
                                val expectedRev = adapterResult.durableIntent?.expectedScheduleRevision
                                    ?: originAuthority?.scheduleRevision
                                val expectedToken = adapterResult.durableIntent?.expectedOwnerToken
                                    ?: originAuthority?.instanceToken
                                val expectedEpoch = originEpochFence
                                val expectedDefRev = adapterResult.issuedOccurrence?.definitionRevision
                                    ?: originAuthority?.definitionRevision
                                outcome = scheduler.scheduleAt(
                                    alarm = updatedAlarm,
                                    triggerTime = updatedAlarm.nextTriggerTime,
                                    expectedRevision = expectedRev,
                                    expectedInstanceToken = expectedToken,
                                    expectedRestoreEpoch = expectedEpoch,
                                    expectedDefinitionRevision = expectedDefRev
                                )
                                lastOutcome = outcome
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
                                            actionAt = nowMs,
                                            challengeType = alarm.challengeType,
                                            dayOfWeek = Instant.ofEpochMilli(
                                                originalScheduledTime.coerceAtLeast(nowMs)
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
