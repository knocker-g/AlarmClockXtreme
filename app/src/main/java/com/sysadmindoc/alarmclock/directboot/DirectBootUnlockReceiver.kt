package com.sysadmindoc.alarmclock.directboot

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.annotation.VisibleForTesting
import androidx.core.os.UserManagerCompat
import com.sysadmindoc.alarmclock.data.local.AlarmDatabase
import com.sysadmindoc.alarmclock.worker.AlarmHealthWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout

/**
 * Post-Unlock Receiver for Direct Boot takeover event consumption (KNO-75 Phase C7-I4 / P4).
 *
 * Listens for [Intent.ACTION_USER_UNLOCKED] with android:directBootAware="true".
 * Upon device unlock:
 * 1. Verifies CE unlock state and fail-closed health of DE/CE stores.
 * 2. Scans unacknowledged NORMAL records in [DirectBootDeliveryLedger] and [DirectBootMissedStore]
 *    and bridges them non-destructively into [DirectBootCeTakeoverStore] via [DirectBootCeHandoffCoordinator].
 * 3. Executes [DirectBootEventConsumerEngine.consumePendingTakeoverRecords] inside a background
 *    coroutine using [goAsync] and 8s timeout, safely committing Room 38 receipts and retiring
 *    DE Delivery/Missed, Witness, and CE active slots without blocking the main thread.
 */
class DirectBootUnlockReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_USER_UNLOCKED) return

        if (!UserManagerCompat.isUserUnlocked(context)) {
            Log.w(TAG, "Received USER_UNLOCKED but UserManager reports user is still locked")
            return
        }

        val pendingResult = goAsync()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        scope.launch {
            var terminalError: Throwable? = null
            try {
                withTimeout(timeoutMsSeam) {
                    val deContext = if (context.isDeviceProtectedStorage) {
                        context
                    } else {
                        context.createDeviceProtectedStorageContext()
                    }
                    val ceContext = context.applicationContext ?: context
                    val database = databaseSeam(ceContext)
                    val result = runUnlockedRecovery(
                        context = context,
                        deContext = deContext,
                        ceContext = ceContext,
                        database = database
                    )
                    if (result is DirectBootEventConsumerEngine.ConsumerExecutionResult.IncompleteRetirement ||
                        hasPendingTakeoverRecords(deContext, ceContext)
                    ) {
                        AlarmHealthWorker.enqueueImmediate(ceContext)
                    }
                }
            } catch (e: TimeoutCancellationException) {
                terminalError = e
                Log.e(TAG, "Direct Boot takeover consumption timed out after ${timeoutMsSeam}ms", e)
                val ceContext = context.applicationContext ?: context
                AlarmHealthWorker.enqueueImmediate(ceContext)
            } catch (t: Throwable) {
                terminalError = t
                Log.e(TAG, "Error consuming Direct Boot takeover records post-unlock", t)
            } finally {
                try {
                    pendingResult.finish()
                } finally {
                    onExecutionTerminatedSeam(terminalError)
                }
            }
        }
    }

    companion object {
        private const val TAG = "DirectBootUnlockReceiver"
        internal const val DEFAULT_UNLOCK_TIMEOUT_MS = 8_000L

        private val recoveryMutex = Mutex()

        @VisibleForTesting
        internal var timeoutMsSeam: Long = DEFAULT_UNLOCK_TIMEOUT_MS

        @VisibleForTesting
        internal var preConsumerHookSeam: suspend () -> Unit = {}

        @VisibleForTesting
        internal var databaseSeam: (Context) -> AlarmDatabase = { ctx ->
            androidx.room.Room.databaseBuilder(
                ctx,
                AlarmDatabase::class.java,
                "alarm_clock.db"
            ).addMigrations(*AlarmDatabase.ALL_MIGRATIONS).build()
        }

        @VisibleForTesting
        internal var onConsumerFinishedSeam: (DirectBootEventConsumerEngine.ConsumerExecutionResult) -> Unit = {}

        @VisibleForTesting
        internal var onExecutionTerminatedSeam: (Throwable?) -> Unit = {}

        private fun handoffBlockedReason(reason: String): String = reason

        internal fun performTargetedDeToCeHandoff(
        deContext: Context,
        ceContext: Context
    ): String? {
        val deliveredRecords = when (val ledgerState = DirectBootDeliveryLedger.readUnacknowledgedRecords(deContext)) {
            is LedgerStorageState.Absent,
            is LedgerStorageState.ValidEmpty -> emptyList()
            is LedgerStorageState.ValidWithRecords -> ledgerState.records
            is LedgerStorageState.Corrupt ->
                return handoffBlockedReason("DE DeliveryLedger is corrupt: ${ledgerState.reason}")
            is LedgerStorageState.UnsupportedSchema ->
                return handoffBlockedReason("DE DeliveryLedger unsupported schema version ${ledgerState.foundVersion}")
        }

        val missedRecords = when (val missedState = DirectBootMissedStore.readUnacknowledgedRecords(deContext)) {
            is MissedStoreStorageState.Absent,
            is MissedStoreStorageState.ValidEmpty -> emptyList()
            is MissedStoreStorageState.ValidWithRecords -> missedState.records
            is MissedStoreStorageState.Corrupt ->
                return handoffBlockedReason("DE MissedStore is corrupt: ${missedState.reason}")
            is MissedStoreStorageState.UnsupportedSchema ->
                return handoffBlockedReason("DE MissedStore unsupported schema version ${missedState.foundVersion}")
            is MissedStoreStorageState.PersistenceUncertain ->
                return handoffBlockedReason("DE MissedStore persistence uncertain: ${missedState.reason}")
        }

        when (val witnessState = DirectBootFiredWitnessLedger.readWitnesses(deContext)) {
            is WitnessLedgerState.Corrupt ->
                return handoffBlockedReason("DE FiredWitnessLedger is corrupt: ${witnessState.reason}")
            is WitnessLedgerState.UnsupportedSchema ->
                return handoffBlockedReason("DE FiredWitnessLedger unsupported schema version ${witnessState.foundVersion}")
            is WitnessLedgerState.Absent,
            is WitnessLedgerState.ValidEmpty,
            is WitnessLedgerState.ValidWithRecords -> Unit
        }

        val activeOwner = when (val ownerRead = DirectBootDurableOwnerStore.read(deContext)) {
            is OwnerReadResult.Absent -> null
            is OwnerReadResult.Success -> ownerRead.owner
            is OwnerReadResult.Corrupt ->
                return handoffBlockedReason("DE DurableOwnerStore is corrupt: ${ownerRead.reason}")
            is OwnerReadResult.UnsupportedSchema ->
                return handoffBlockedReason("DE DurableOwnerStore unsupported schema version ${ownerRead.foundVersion}")
        }

        val existingCeRecords = when (val ceState = DirectBootCeTakeoverStore.inspectStorageState(ceContext)) {
            is CeTakeoverStorageState.Absent,
            is CeTakeoverStorageState.ValidEmpty -> emptyList()
            is CeTakeoverStorageState.ValidWithRecords -> ceState.records
            is CeTakeoverStorageState.CeLocked ->
                return handoffBlockedReason("CE Storage is locked")
            is CeTakeoverStorageState.CeContextRejected ->
                return ceState.reason
            is CeTakeoverStorageState.Corrupt ->
                return handoffBlockedReason("CE Storage is corrupt: ${ceState.reason}")
            is CeTakeoverStorageState.UnsupportedSchema ->
                return handoffBlockedReason("CE Storage unsupported schema version ${ceState.foundVersion}")
        }

        if (deliveredRecords.isEmpty() && missedRecords.isEmpty()) {
            return null
        }

        val isOccurrenceBlockedByActiveOwner: (Long, Long) -> Boolean = { alarmId, triggerTime ->
            if (activeOwner == null || activeOwner.state == OwnerState.IDLE) {
                false
            } else {
                val claim = activeOwner.handoverClaim
                val session = activeOwner.activeSession
                (claim != null && claim.alarmId == alarmId && claim.triggerTime == triggerTime) ||
                    (session != null && session.alarmId == alarmId && session.triggerTime == triggerTime)
            }
        }

        val normalDelivered = deliveredRecords.filter { it.kind == DirectBootDeliveryRecord.KIND_NORMAL }
        for (delRecord in normalDelivered) {
            if (isOccurrenceBlockedByActiveOwner(delRecord.alarmId, delRecord.triggerTime)) {
                Log.i(TAG, "Deferring DE->CE handoff for active/handover-pending alarmId=${delRecord.alarmId}")
                continue
            }

            val existingCeMissed = existingCeRecords.any {
                it is CeTakeoverRecord.Missed &&
                    it.alarmId == delRecord.alarmId &&
                    it.triggerTime == delRecord.triggerTime &&
                    it.generation == delRecord.generation &&
                    it.instanceToken == delRecord.instanceToken
            }
            if (existingCeMissed) {
                continue
            }

            when (val handoffRes = DirectBootCeHandoffCoordinator.handoffDelivered(deContext, ceContext, delRecord)) {
                is CeHandoffResult.Confirmed -> Unit
                is CeHandoffResult.Rejected.CeLocked -> return handoffBlockedReason("CE Storage is locked")
                is CeHandoffResult.Rejected.CeContextInvalid -> return handoffRes.details
                is CeHandoffResult.Rejected.DeStorageCorrupt ->
                    return handoffBlockedReason("${handoffRes.storeDomain} is corrupt: ${handoffRes.reason}")
                is CeHandoffResult.Rejected.DeUnsupportedSchema ->
                    return handoffBlockedReason("${handoffRes.storeDomain} unsupported schema ${handoffRes.foundVersion}")
                is CeHandoffResult.Rejected.DeStorageUncertain ->
                    return handoffBlockedReason("${handoffRes.storeDomain} uncertain: ${handoffRes.reason}")
                is CeHandoffResult.Rejected.CeStorageCorrupt ->
                    return handoffBlockedReason("CE Storage is corrupt: ${handoffRes.reason}")
                is CeHandoffResult.Rejected.CeUnsupportedSchema ->
                    return handoffBlockedReason("CE Storage unsupported schema ${handoffRes.foundVersion}")
                is CeHandoffResult.Rejected,
                is CeHandoffResult.Uncertain,
                is CeHandoffResult.PostCommitFailure,
                is CeHandoffResult.CancellationAfterConfirm -> {
                    Log.w(TAG, "Per-record DE delivered handoff deferred/rejected for ${delRecord.recordId}: $handoffRes")
                }
            }
        }

        val normalMissed = missedRecords.filter { it.kind == DirectBootMissedRecord.KIND_NORMAL }
        for (missedRecord in normalMissed) {
            if (isOccurrenceBlockedByActiveOwner(missedRecord.alarmId, missedRecord.triggerTime)) {
                Log.i(TAG, "Deferring DE->CE missed handoff for active/handover-pending alarmId=${missedRecord.alarmId}")
                continue
            }

            val pairedWithSameEpochDelivery =
                missedRecord.classification == DirectBootMissedRecord.CLASSIFICATION_SERVICE_START_FAILED &&
                    missedRecord.restoreEpoch > 0L &&
                    normalDelivered.any {
                        it.alarmId == missedRecord.alarmId &&
                            it.triggerTime == missedRecord.triggerTime &&
                            it.generation == missedRecord.generation &&
                            it.instanceToken == missedRecord.instanceToken &&
                            it.restoreEpoch == missedRecord.restoreEpoch
                    }
            if (pairedWithSameEpochDelivery) {
                continue
            }

            when (val handoffRes = DirectBootCeHandoffCoordinator.handoffMissed(deContext, ceContext, missedRecord)) {
                is CeHandoffResult.Confirmed -> Unit
                is CeHandoffResult.Rejected.CeLocked -> return handoffBlockedReason("CE Storage is locked")
                is CeHandoffResult.Rejected.CeContextInvalid -> return handoffRes.details
                is CeHandoffResult.Rejected.DeStorageCorrupt ->
                    return handoffBlockedReason("${handoffRes.storeDomain} is corrupt: ${handoffRes.reason}")
                is CeHandoffResult.Rejected.DeUnsupportedSchema ->
                    return handoffBlockedReason("${handoffRes.storeDomain} unsupported schema ${handoffRes.foundVersion}")
                is CeHandoffResult.Rejected.DeStorageUncertain ->
                    return handoffBlockedReason("${handoffRes.storeDomain} uncertain: ${handoffRes.reason}")
                is CeHandoffResult.Rejected.CeStorageCorrupt ->
                    return handoffBlockedReason("CE Storage is corrupt: ${handoffRes.reason}")
                is CeHandoffResult.Rejected.CeUnsupportedSchema ->
                    return handoffBlockedReason("CE Storage unsupported schema ${handoffRes.foundVersion}")
                is CeHandoffResult.Rejected,
                is CeHandoffResult.Uncertain,
                is CeHandoffResult.PostCommitFailure,
                is CeHandoffResult.CancellationAfterConfirm -> {
                    Log.w(TAG, "Per-record DE missed handoff deferred/rejected for ${missedRecord.recordId}: $handoffRes")
                }
            }
        }

        return null
        }

        fun hasUnretiredDeRecords(deContext: Context): Boolean {
            val hasDel = when (val s = DirectBootDeliveryLedger.readUnacknowledgedRecords(deContext)) {
                is LedgerStorageState.ValidWithRecords -> s.records.any { it.kind == DirectBootDeliveryRecord.KIND_NORMAL }
                else -> false
            }
            val hasMissed = when (val s = DirectBootMissedStore.readUnacknowledgedRecords(deContext)) {
                is MissedStoreStorageState.ValidWithRecords -> s.records.any { it.kind == DirectBootMissedRecord.KIND_NORMAL }
                else -> false
            }
            return hasDel || hasMissed
        }

        fun hasPendingTakeoverRecords(deContext: Context, ceContext: Context): Boolean {
            if (hasUnretiredDeRecords(deContext)) return true
            return when (val s = DirectBootCeTakeoverStore.inspectStorageState(ceContext)) {
                is CeTakeoverStorageState.ValidWithRecords -> s.records.isNotEmpty()
                else -> false
            }
        }

        suspend fun runUnlockedRecovery(
            context: Context,
            deContext: Context = if (context.isDeviceProtectedStorage) context else context.createDeviceProtectedStorageContext(),
            ceContext: Context = context.applicationContext ?: context,
            database: AlarmDatabase = databaseSeam(ceContext)
        ): DirectBootEventConsumerEngine.ConsumerExecutionResult {
            if (!UserManagerCompat.isUserUnlocked(context)) {
                val lockedResult = DirectBootEventConsumerEngine.ConsumerExecutionResult.Rejected("User storage is locked")
                Log.w(TAG, "Direct Boot unlock recovery rejected fail-closed: $lockedResult")
                onConsumerFinishedSeam(lockedResult)
                return lockedResult
            }
            if (ceContext.isDeviceProtectedStorage || !DirectBootCeTakeoverStore.unlockGateSeam(ceContext)) {
                val lockedResult = DirectBootEventConsumerEngine.ConsumerExecutionResult.Rejected(
                    "User CE storage is locked or invalid CE context"
                )
                Log.w(TAG, "Direct Boot unlock recovery rejected fail-closed: $lockedResult")
                onConsumerFinishedSeam(lockedResult)
                return lockedResult
            }

            return recoveryMutex.withLock {
                val handoffBlockedReason = performTargetedDeToCeHandoff(deContext, ceContext)
                if (handoffBlockedReason != null) {
                    val rejectedResult = DirectBootEventConsumerEngine.ConsumerExecutionResult.Rejected(
                        handoffBlockedReason
                    )
                    Log.w(TAG, "Direct Boot DE->CE handoff fail-closed during recovery: $handoffBlockedReason")
                    onConsumerFinishedSeam(rejectedResult)
                    return@withLock rejectedResult
                }

                preConsumerHookSeam()

                val result = DirectBootEventConsumerEngine.consumePendingTakeoverRecords(
                    context = deContext,
                    ceContext = ceContext,
                    database = database
                )
                Log.i(TAG, "Direct Boot takeover recovery completed: $result")
                onConsumerFinishedSeam(result)
                result
            }
        }

        @VisibleForTesting
        internal fun resetSeamsForTesting() {
            timeoutMsSeam = DEFAULT_UNLOCK_TIMEOUT_MS
            preConsumerHookSeam = {}
            onConsumerFinishedSeam = {}
            onExecutionTerminatedSeam = {}
        }
    }
}
