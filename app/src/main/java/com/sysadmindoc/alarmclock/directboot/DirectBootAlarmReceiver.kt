package com.sysadmindoc.alarmclock.directboot

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.os.UserManagerCompat
import com.sysadmindoc.alarmclock.BuildConfig
import com.sysadmindoc.alarmclock.receiver.AlarmDeliveryWakeLock
import com.sysadmindoc.alarmclock.worker.AlarmHealthWorker

class DirectBootAlarmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action != ACTION_FIRE_DIRECT_BOOT_ALARM && action != DirectBootV2NormalIntentIdentity.ACTION_FIRE_V2_NORMAL_ALARM) return

        val gate = outputAuthorityGateSeam
            ?: com.sysadmindoc.alarmclock.directboot.outputauthority.SharedOutputAuthorityGate.getInstance(context)
        var effectiveV2TicketId: String? = null

        if (action == DirectBootV2NormalIntentIdentity.ACTION_FIRE_V2_NORMAL_ALARM) {
            val v2Capable = v2DeliveryCapableProvider()
            val d4PreQualified = BuildConfig.DEBUG &&
                DirectBootAlarmCache.hasValidD4CapabilityForBoot(context)
            if ((!v2Capable || !gate.isEnabled) && !d4PreQualified) {
                Log.w(
                    TAG,
                    "V2 DirectBoot delivery rejected: V2 delivery not capable or gate disabled (v2Capable=$v2Capable, gateEnabled=${gate.isEnabled}, d4PreQualified=$d4PreQualified)"
                )
                return
            }
            val v2Kind: String
            val v2AlarmId: Long
            val v2TriggerTime: Long
            val v2Generation: Int
            val v2InstanceToken: String
            val v2RestoreEpoch: Long
            val hasTicketExtra: Boolean
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
                hasTicketExtra = intent.hasExtra(DirectBootDeliveryAuthorizer.EXTRA_TICKET_ID)
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
            val d4Admissible = BuildConfig.DEBUG &&
                DirectBootAlarmCache.isD4OccurrenceAdmissible(deContext, identity)
            if ((!v2Capable || !gate.isEnabled) && !d4Admissible) {
                Log.w(
                    TAG,
                    "V2 DirectBoot delivery rejected: global V2 disabled and D4 occurrence not admissible (alarmId=$v2AlarmId)"
                )
                return
            }

            // Symmetric Cross-Lane Fence: block V2 if LegacyClaimStore owns or cannot verify same occurrence
            if (gate.isEnabled || d4Admissible) {
                val legacyBlocked = synchronized(DirectBootStorageLock.lock) {
                    when (val claimState = DirectBootLegacyClaimStore.inspectStorageState(deContext)) {
                        is LegacyClaimStorageState.Corrupt,
                        is LegacyClaimStorageState.UnsupportedSchema -> true
                        is LegacyClaimStorageState.ValidWithRecords -> claimState.records.any {
                            it.alarmId == v2AlarmId &&
                                it.scheduledAt == v2TriggerTime &&
                                it.state != LegacyClaimState.TERMINAL
                        }
                        is LegacyClaimStorageState.Absent,
                        is LegacyClaimStorageState.ValidEmpty -> false
                    }
                }
                if (legacyBlocked) {
                    Log.w(
                        TAG,
                        "V2 DirectBoot delivery rejected: symmetric cross-lane LegacyClaim fence (alarmId=$v2AlarmId, triggerTime=$v2TriggerTime)"
                    )
                    return
                }
            }

            val ticketIdForAdapt: String
            val adapter: DirectBootServiceDeliveryAdapter
            if (!hasTicketExtra) {
                // OS AlarmManager PendingIntent delivery (post-reboot durable path):
                // preQualify -> Eligible -> issue single-use same-process ticket -> consume via shared authorizer.
                val now = System.currentTimeMillis()
                val sharedAuthorizer = authorizerSeam()
                val preQual = sharedAuthorizer.preQualify(deContext, intent, nowEpochMs = now)
                if (preQual !is DeliveryPrequalificationResult.Eligible) {
                    Log.w(
                        TAG,
                        "V2 DirectBoot OS delivery rejected at preQualify: ${preQual::class.simpleName} (alarmId=$v2AlarmId)"
                    )
                    return
                }
                val issued = sharedAuthorizer.issueTicket(identity, nowEpochMs = now)
                if (issued !is TicketIssueResult.Success) {
                    Log.w(
                        TAG,
                        "V2 DirectBoot OS delivery ticket issue rejected: ${issued::class.simpleName} (alarmId=$v2AlarmId)"
                    )
                    return
                }
                ticketIdForAdapt = issued.ticket.ticketId
                adapter = if (serviceAdapterSeam === defaultServiceAdapterSeam) {
                    DirectBootServiceDeliveryAdapter(deContext, sharedAuthorizer)
                } else {
                    serviceAdapterSeam(deContext)
                }
            } else {
                // Explicit EXTRA_TICKET_ID present: NEVER mint a new ticket; pass through to validateAndConsumeTicket
                // so blank, tampered, expired, or replayed tickets reject fail-closed.
                ticketIdForAdapt = v2TicketId
                adapter = serviceAdapterSeam(deContext)
            }

            val adaptResult = adapter.adaptDelivery(ticketId = ticketIdForAdapt, identity = identity)

            if (adaptResult !is ServiceDeliveryAdaptationResult.TransitionCommitted &&
                adaptResult !is ServiceDeliveryAdaptationResult.CancellationAfterCommit
            ) {
                Log.w(
                    TAG,
                    "V2 DirectBoot delivery rejected: ${adaptResult::class.simpleName} (alarmId=$v2AlarmId, gen=$v2Generation, epoch=$v2RestoreEpoch)"
                )
                return
            }
            effectiveV2TicketId = ticketIdForAdapt

            val req = when (adaptResult) {
                is ServiceDeliveryAdaptationResult.TransitionCommitted -> adaptResult.reconciliationRequest
                is ServiceDeliveryAdaptationResult.CancellationAfterCommit -> adaptResult.reconciliationRequest
                else -> null
            }

            if (req?.nextCandidateToArm != null) {
                val canReconcileNext = (v2DeliveryCapableProvider() && gate.isEnabled) ||
                    (BuildConfig.DEBUG && DirectBootAlarmCache.hasValidD4CapabilityForBoot(deContext))
                if (canReconcileNext) {
                    try {
                        val recRes = reconcilerSeam(deContext).reconcile()
                        Log.i(TAG, "Reconciler result after post-commit transition: ${recRes::class.simpleName}")
                    } catch (e: Exception) {
                        Log.e(TAG, "Reconciler invocation failed after post-commit transition", e)
                    }
                } else {
                    Log.i(TAG, "Skipping post-commit V2 reconciler: no active V2 or D4 capability for next candidate")
                }
            }
        }

        val isV2Action = action == DirectBootV2NormalIntentIdentity.ACTION_FIRE_V2_NORMAL_ALARM
        val alarmId = try {
            intent.getLongExtra(EXTRA_ALARM_ID, intent.getLongExtra(DirectBootV2NormalIntentIdentity.EXTRA_ALARM_ID, -1L))
        } catch (_: Exception) {
            -1L
        }
        val triggerTime = try {
            intent.getLongExtra(EXTRA_TRIGGER_TIME, intent.getLongExtra(DirectBootV2NormalIntentIdentity.EXTRA_TRIGGER_TIME, 0L))
        } catch (_: Exception) {
            0L
        }
        val gen = try {
            intent.getIntExtra(DirectBootV2NormalIntentIdentity.EXTRA_GENERATION, if (isV2Action) 1 else 0)
        } catch (_: Exception) {
            0
        }
        val token = try {
            intent.getStringExtra(DirectBootV2NormalIntentIdentity.EXTRA_INSTANCE_TOKEN).orEmpty()
        } catch (_: Exception) {
            ""
        }
        val epoch = try {
            intent.getLongExtra(DirectBootV2NormalIntentIdentity.EXTRA_RESTORE_EPOCH, 0L)
        } catch (_: Exception) {
            0L
        }
        val v2KindExtra = try {
            if (intent.hasExtra(DirectBootV2NormalIntentIdentity.EXTRA_KIND)) {
                intent.getStringExtra(DirectBootV2NormalIntentIdentity.EXTRA_KIND)
            } else {
                null
            }
        } catch (_: Exception) {
            null
        }
        val v2DigestExtra = try {
            if (intent.hasExtra(DirectBootV2NormalIntentIdentity.EXTRA_IDENTITY_DIGEST)) {
                intent.getStringExtra(DirectBootV2NormalIntentIdentity.EXTRA_IDENTITY_DIGEST)
            } else {
                null
            }
        } catch (_: Exception) {
            null
        }
        val v2TicketExtra = try {
            if (intent.hasExtra(DirectBootDeliveryAuthorizer.EXTRA_TICKET_ID)) {
                intent.getStringExtra(DirectBootDeliveryAuthorizer.EXTRA_TICKET_ID)
            } else {
                null
            }
        } catch (_: Exception) {
            null
        }

        var claimNonce: String? = null
        var occKey: OutputOccurrenceKey? = null
        if (!isV2Action && gate.isEnabled) {
            if (alarmId <= 0L || triggerTime <= 0L) {
                Log.w(TAG, "DirectBoot V1 authority reservation rejected: invalid identity (alarmId=$alarmId, triggerTime=$triggerTime)")
                return
            }
            val key = OutputOccurrenceKey(alarmId = alarmId, scheduledAt = triggerTime, refireCount = 0)
            val res = gate.reserveForServiceStart(
                context = context,
                key = key,
                source = LegacyClaimSource.DIRECT_BOOT_RECEIVER,
                directBootModeOnly = true
            )
            if (res !is com.sysadmindoc.alarmclock.directboot.outputauthority.OutputAuthorizationResult.Authorized) {
                Log.w(TAG, "DirectBoot V1 authority reservation rejected for alarm $alarmId: $res")
                return
            }
            claimNonce = res.activeNonce
            occKey = key
        }

        val serviceIntent = Intent(context, DirectBootAlarmService::class.java).apply {
            this.action = DirectBootAlarmService.ACTION_START
            if (isV2Action || intent.data != null) {
                data = intent.data
            }
            putExtra(EXTRA_ALARM_ID, alarmId)
            putExtra(EXTRA_TRIGGER_TIME, triggerTime)
            putExtra(DirectBootV2NormalIntentIdentity.EXTRA_GENERATION, gen)
            putExtra(DirectBootV2NormalIntentIdentity.EXTRA_INSTANCE_TOKEN, token)
            putExtra(DirectBootV2NormalIntentIdentity.EXTRA_RESTORE_EPOCH, epoch)
            if (isV2Action || v2KindExtra != null) {
                putExtra(DirectBootV2NormalIntentIdentity.EXTRA_KIND, v2KindExtra.orEmpty())
            }
            if (isV2Action || v2DigestExtra != null) {
                putExtra(DirectBootV2NormalIntentIdentity.EXTRA_IDENTITY_DIGEST, v2DigestExtra.orEmpty())
            }
            val forwardedTicket = effectiveV2TicketId ?: v2TicketExtra
            if (forwardedTicket != null) {
                putExtra(DirectBootDeliveryAuthorizer.EXTRA_TICKET_ID, forwardedTicket)
            }
            if (claimNonce != null) {
                putExtra(com.sysadmindoc.alarmclock.service.AlarmService.EXTRA_CLAIM_NONCE, claimNonce)
            }
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
            if (claimNonce != null && occKey != null) {
                gate.abortClaim(context, occKey, claimNonce, LegacyTerminalOutcome.ABORTED_PRE_IPC)
            }
            if (isV2Action && alarmId > 0L && triggerTime > 0L && gen >= 1 && token.isNotBlank() && epoch > 0L) {
                val deContext = if (context.isDeviceProtectedStorage) context else context.createDeviceProtectedStorageContext()
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

                // STEP 2: Report missed-store status cleanly; DE delivery ledger record stays intact until CE proof
                if (missedRes is MissedRecordResult.Recorded || missedRes is MissedRecordResult.DuplicateIgnored) {
                    val delRecord = DirectBootDeliveryRecord.createNormal(
                        alarmId = alarmId,
                        triggerTime = triggerTime,
                        deliveredAt = System.currentTimeMillis(),
                        generation = gen,
                        instanceToken = token,
                        restoreEpoch = epoch
                    )
                    val ackRes = DirectBootDeliveryLedger.acknowledgeRecord(deContext, delRecord.recordId)
                    if (ackRes is DeliveryAckResult.AckNotQualified) {
                        Log.i(TAG, "Retaining DE delivery record until CE proof after missed record commit ($alarmId)")
                    }
                } else {
                    Log.w(TAG, "Missed record commit failed (${missedRes::class.simpleName}); retaining DE delivery record for post-unlock recovery")
                }

                // STEP 3 (M5): CAS-release HANDOVER_PENDING -> IDLE if still matching this failed start
                when (val ownerRead = DirectBootDurableOwnerStore.read(deContext)) {
                    is OwnerReadResult.Success -> {
                        val owner = ownerRead.owner
                        val claim = owner.handoverClaim
                        if (owner.state == OwnerState.HANDOVER_PENDING &&
                            claim != null &&
                            claim.alarmId == alarmId &&
                            claim.triggerTime == triggerTime &&
                            claim.generation == gen &&
                            claim.instanceToken == token &&
                            claim.restoreEpoch == epoch
                        ) {
                            val idleOwner = DirectBootDurableOwner(
                                schemaVersion = DirectBootDurableOwnerStore.CURRENT_SCHEMA_VERSION,
                                state = OwnerState.IDLE,
                                ownerSeq = owner.ownerSeq,
                                bootProvenance = owner.bootProvenance,
                                handoverClaim = null,
                                activeSession = null,
                                updatedAtEpochMs = System.currentTimeMillis()
                            )
                            val releaseRes = DirectBootDurableOwnerStore.write(
                                context = deContext,
                                owner = idleOwner,
                                expectedOwnerSeq = owner.ownerSeq
                            )
                            if (releaseRes !is OwnerWriteResult.Success) {
                                Log.w(TAG, "Failed to release HANDOVER_PENDING owner after startForegroundService failure: $releaseRes")
                            }
                        }
                    }
                    else -> {
                        Log.w(TAG, "Skipping HANDOVER_PENDING owner release on non-success read: ${ownerRead::class.simpleName}")
                    }
                }

                if (UserManagerCompat.isUserUnlocked(context) &&
                    DirectBootUnlockReceiver.hasUnretiredDeRecords(deContext)
                ) {
                    val ceContext = context.applicationContext ?: context
                    AlarmHealthWorker.enqueueImmediate(ceContext)
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

        private val defaultServiceAdapterSeam: (Context) -> DirectBootServiceDeliveryAdapter = { ctx ->
            DirectBootServiceDeliveryAdapter(ctx, DirectBootDeliveryAuthorizer())
        }

        @androidx.annotation.VisibleForTesting
        internal var authorizerSeam: () -> DirectBootDeliveryAuthorizer = {
            DirectBootDeliveryAuthorizer()
        }

        @androidx.annotation.VisibleForTesting
        internal var serviceAdapterSeam: (Context) -> DirectBootServiceDeliveryAdapter =
            defaultServiceAdapterSeam

        @androidx.annotation.VisibleForTesting
        internal var reconcilerSeam: (Context) -> DirectBootRegistrationReconciler = { ctx ->
            DirectBootRegistrationReconciler.getInstance(ctx)
        }

        @androidx.annotation.VisibleForTesting
        internal var outputAuthorityGateSeam: com.sysadmindoc.alarmclock.directboot.outputauthority.SharedOutputAuthorityGate? = null

        @androidx.annotation.VisibleForTesting
        internal var v2DeliveryCapableProvider: () -> Boolean = { false }

        @androidx.annotation.VisibleForTesting
        internal fun resetSeamsForTesting() {
            authorizerSeam = { DirectBootDeliveryAuthorizer() }
            serviceAdapterSeam = defaultServiceAdapterSeam
            reconcilerSeam = { ctx ->
                DirectBootRegistrationReconciler.getInstance(ctx)
            }
            outputAuthorityGateSeam = null
            v2DeliveryCapableProvider = { false }
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
