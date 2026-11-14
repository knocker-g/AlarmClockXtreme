package com.sysadmindoc.alarmclock.directboot

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.sysadmindoc.alarmclock.receiver.AlarmDeliveryWakeLock

class DirectBootAlarmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action != ACTION_FIRE_DIRECT_BOOT_ALARM && action != DirectBootV2NormalIntentIdentity.ACTION_FIRE_V2_NORMAL_ALARM) return

        if (action == DirectBootV2NormalIntentIdentity.ACTION_FIRE_V2_NORMAL_ALARM) {
            val v2Kind: String
            val v2AlarmId: Long
            val v2TriggerTime: Long
            val v2Generation: Int
            val v2InstanceToken: String
            val v2RestoreEpoch: Long
            val v2TicketId: String
            val v2Uri: android.net.Uri?
            val v2Digest: String?
            try {
                v2Kind = intent.getStringExtra(DirectBootV2NormalIntentIdentity.EXTRA_KIND).orEmpty()
                v2AlarmId = intent.getLongExtra(DirectBootV2NormalIntentIdentity.EXTRA_ALARM_ID, -1L)
                v2TriggerTime = intent.getLongExtra(DirectBootV2NormalIntentIdentity.EXTRA_TRIGGER_TIME, 0L)
                v2Generation = intent.getIntExtra(DirectBootV2NormalIntentIdentity.EXTRA_GENERATION, 0)
                v2InstanceToken = intent.getStringExtra(DirectBootV2NormalIntentIdentity.EXTRA_INSTANCE_TOKEN).orEmpty()
                v2RestoreEpoch = intent.getLongExtra(DirectBootV2NormalIntentIdentity.EXTRA_RESTORE_EPOCH, 0L)
                v2TicketId = intent.getStringExtra(DirectBootDeliveryAuthorizer.EXTRA_TICKET_ID).orEmpty()
                v2Uri = intent.data
                v2Digest = intent.getStringExtra(DirectBootV2NormalIntentIdentity.EXTRA_IDENTITY_DIGEST)
            } catch (_: Exception) {
                Log.w(TAG, "V2 DirectBoot intent rejected: malformed intent extras")
                return
            }

            if (v2Kind != DirectBootV2NormalIntentIdentity.KIND_NORMAL ||
                v2AlarmId <= 0L ||
                v2Generation < 1 ||
                v2InstanceToken.isBlank() ||
                v2TriggerTime <= 0L ||
                v2RestoreEpoch <= 0L
            ) {
                Log.w(
                    TAG,
                    "V2 DirectBoot intent rejected: invalid identity extras (alarmId=$v2AlarmId, gen=$v2Generation, epoch=$v2RestoreEpoch, kind='$v2Kind')"
                )
                return
            }

            val identity = try {
                DirectBootV2NormalIntentIdentity(
                    alarmId = v2AlarmId,
                    generation = v2Generation,
                    instanceToken = v2InstanceToken,
                    triggerTime = v2TriggerTime,
                    restoreEpoch = v2RestoreEpoch,
                    kind = v2Kind
                )
            } catch (_: Exception) {
                Log.w(TAG, "V2 DirectBoot intent rejected: identity construction failed (alarmId=$v2AlarmId)")
                return
            }

            val uriValidation = DirectBootV2NormalIntentIdentity.validateUri(v2Uri, identity)
            if (uriValidation is UriValidationResult.Rejected) {
                Log.w(TAG, "V2 DirectBoot intent rejected: invalid URI (${uriValidation.reason})")
                return
            }

            if (v2Digest.isNullOrBlank() || v2Digest != identity.computeDigest()) {
                Log.w(TAG, "V2 DirectBoot intent rejected: missing or mismatched digest (alarmId=$v2AlarmId)")
                return
            }

            val deContext = if (context.isDeviceProtectedStorage) context else context.createDeviceProtectedStorageContext()
            val adapter = serviceAdapterSeam(deContext)
            val adaptResult = adapter.adaptDelivery(ticketId = v2TicketId, identity = identity)

            if (adaptResult !is ServiceDeliveryAdaptationResult.TransitionCommitted &&
                adaptResult !is ServiceDeliveryAdaptationResult.CancellationAfterCommit
            ) {
                Log.w(
                    TAG,
                    "V2 DirectBoot delivery rejected: ${adaptResult::class.simpleName} (alarmId=$v2AlarmId, gen=$v2Generation, epoch=$v2RestoreEpoch)"
                )
                return
            }

            val req = when (adaptResult) {
                is ServiceDeliveryAdaptationResult.TransitionCommitted -> adaptResult.reconciliationRequest
                is ServiceDeliveryAdaptationResult.CancellationAfterCommit -> adaptResult.reconciliationRequest
                else -> null
            }

            if (req?.nextCandidateToArm != null) {
                try {
                    val recRes = reconcilerSeam(deContext).reconcile()
                    Log.i(TAG, "Reconciler result after post-commit transition: ${recRes::class.simpleName}")
                } catch (e: Exception) {
                    Log.e(TAG, "Reconciler invocation failed after post-commit transition", e)
                }
            }
        }

        val serviceIntent = Intent(context, DirectBootAlarmService::class.java).apply {
            this.action = DirectBootAlarmService.ACTION_START
            val alarmId = intent.getLongExtra(EXTRA_ALARM_ID, intent.getLongExtra(DirectBootV2NormalIntentIdentity.EXTRA_ALARM_ID, -1L))
            val triggerTime = intent.getLongExtra(EXTRA_TRIGGER_TIME, intent.getLongExtra(DirectBootV2NormalIntentIdentity.EXTRA_TRIGGER_TIME, 0L))
            val gen = intent.getIntExtra(DirectBootV2NormalIntentIdentity.EXTRA_GENERATION, 1)
            val token = intent.getStringExtra(DirectBootV2NormalIntentIdentity.EXTRA_INSTANCE_TOKEN).orEmpty()
            val epoch = intent.getLongExtra(DirectBootV2NormalIntentIdentity.EXTRA_RESTORE_EPOCH, 0L)

            putExtra(EXTRA_ALARM_ID, alarmId)
            putExtra(EXTRA_TRIGGER_TIME, triggerTime)
            putExtra(DirectBootV2NormalIntentIdentity.EXTRA_GENERATION, gen)
            putExtra(DirectBootV2NormalIntentIdentity.EXTRA_INSTANCE_TOKEN, token)
            putExtra(DirectBootV2NormalIntentIdentity.EXTRA_RESTORE_EPOCH, epoch)
            putExtra(EXTRA_LABEL, intent.getStringExtra(EXTRA_LABEL).orEmpty())
            putExtra(EXTRA_TIME_LABEL, intent.getStringExtra(EXTRA_TIME_LABEL).orEmpty())
            putExtra(EXTRA_PLAY_DEFAULT_SOUND, intent.getBooleanExtra(EXTRA_PLAY_DEFAULT_SOUND, true))
            putExtra(EXTRA_VIBRATION_ENABLED, intent.getBooleanExtra(EXTRA_VIBRATION_ENABLED, true))
        }

        val deliveryWakeLock = AlarmDeliveryWakeLock.acquire(context)
        var serviceStartSucceeded = false
        try {
            context.startForegroundService(serviceIntent)
            serviceStartSucceeded = true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start direct-boot alarm service", e)
            val deContext = if (context.isDeviceProtectedStorage) context else context.createDeviceProtectedStorageContext()
            val alarmId = intent.getLongExtra(EXTRA_ALARM_ID, intent.getLongExtra(DirectBootV2NormalIntentIdentity.EXTRA_ALARM_ID, -1L))
            val triggerTime = intent.getLongExtra(EXTRA_TRIGGER_TIME, intent.getLongExtra(DirectBootV2NormalIntentIdentity.EXTRA_TRIGGER_TIME, 0L))
            val gen = intent.getIntExtra(DirectBootV2NormalIntentIdentity.EXTRA_GENERATION, 1)
            val token = intent.getStringExtra(DirectBootV2NormalIntentIdentity.EXTRA_INSTANCE_TOKEN).orEmpty()
            val epoch = intent.getLongExtra(DirectBootV2NormalIntentIdentity.EXTRA_RESTORE_EPOCH, 0L)

            if (alarmId > 0L && triggerTime > 0L) {
                val missedRecord = DirectBootMissedRecord.createNormal(
                    alarmId = alarmId,
                    triggerTime = triggerTime,
                    missedAt = System.currentTimeMillis(),
                    generation = gen,
                    instanceToken = token,
                    classification = DirectBootMissedRecord.CLASSIFICATION_SERVICE_START_FAILED,
                    restoreEpoch = epoch
                )
                // STEP 1: Commit replacement recovery evidence to DirectBootMissedStore FIRST
                val missedRes = DirectBootMissedStore.recordMissed(deContext, missedRecord)

                // STEP 2: Retire DE delivery claim ONLY IF missed record commit succeeded
                if (missedRes is MissedRecordResult.Recorded || missedRes is MissedRecordResult.DuplicateIgnored) {
                    val delRecord = DirectBootDeliveryRecord.createNormal(
                        alarmId = alarmId,
                        triggerTime = triggerTime,
                        deliveredAt = System.currentTimeMillis(),
                        generation = gen,
                        instanceToken = token,
                        restoreEpoch = epoch
                    )
                    DirectBootDeliveryLedger.acknowledgeRecord(deContext, delRecord.recordId)
                } else {
                    Log.w(TAG, "Missed record commit failed (${missedRes::class.simpleName}); retaining DE delivery record for post-unlock recovery")
                }
            }
        } finally {
            if (!serviceStartSucceeded && deliveryWakeLock != null) AlarmDeliveryWakeLock.release(deliveryWakeLock)
        }
    }

    companion object {
        const val ACTION_FIRE_DIRECT_BOOT_ALARM =
            "com.sysadmindoc.alarmclock.directboot.FIRE_ALARM"
        const val EXTRA_ALARM_ID = "direct_boot_alarm_id"
        const val EXTRA_TRIGGER_TIME = "direct_boot_trigger_time"
        const val EXTRA_LABEL = "direct_boot_label"
        const val EXTRA_TIME_LABEL = "direct_boot_time_label"
        const val EXTRA_PLAY_DEFAULT_SOUND = "direct_boot_play_default_sound"
        const val EXTRA_VIBRATION_ENABLED = "direct_boot_vibration_enabled"

        private const val TAG = "DirectBootAlarmReceiver"

        @androidx.annotation.VisibleForTesting
        internal var serviceAdapterSeam: (Context) -> DirectBootServiceDeliveryAdapter = { ctx ->
            DirectBootServiceDeliveryAdapter(ctx, DirectBootDeliveryAuthorizer())
        }

        @androidx.annotation.VisibleForTesting
        internal var reconcilerSeam: (Context) -> DirectBootRegistrationReconciler = { ctx ->
            DirectBootRegistrationReconciler.getInstance(ctx)
        }

        @androidx.annotation.VisibleForTesting
        internal fun resetSeamsForTesting() {
            serviceAdapterSeam = { ctx ->
                DirectBootServiceDeliveryAdapter(ctx, DirectBootDeliveryAuthorizer())
            }
            reconcilerSeam = { ctx ->
                DirectBootRegistrationReconciler.getInstance(ctx)
            }
        }

        fun pendingIntent(
            context: Context,
            snapshot: DirectBootAlarmSnapshot,
            flags: Int = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        ): PendingIntent? {
            val intent = Intent(context, DirectBootAlarmReceiver::class.java).apply {
                action = ACTION_FIRE_DIRECT_BOOT_ALARM
                putExtra(EXTRA_ALARM_ID, snapshot.alarmId)
                putExtra(EXTRA_TRIGGER_TIME, snapshot.triggerTime)
                putExtra(EXTRA_LABEL, snapshot.label)
                putExtra(EXTRA_TIME_LABEL, snapshot.timeLabel)
                putExtra(EXTRA_PLAY_DEFAULT_SOUND, snapshot.playDefaultSound)
                putExtra(EXTRA_VIBRATION_ENABLED, snapshot.vibrationEnabled)
            }
            return PendingIntent.getBroadcast(
                context,
                snapshot.alarmId.toInt(),
                intent,
                flags
            )
        }
    }
}
