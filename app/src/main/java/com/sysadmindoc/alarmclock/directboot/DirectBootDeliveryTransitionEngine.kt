package com.sysadmindoc.alarmclock.directboot

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.annotation.VisibleForTesting
import kotlinx.coroutines.CancellationException
import org.json.JSONArray
import org.json.JSONObject

/**
 * Inactive Normal-only delivery transition engine for KNO-75 Slice D2.
 *
 * Responsibilities & Invariants:
 * - Atomic transition of a firing Normal alarm occurrence from the DE Queue ([DirectBootAlarmCache])
 *   to the durable DE Delivery Ledger ([DirectBootDeliveryLedger]).
 * - Single SharedPreferences Editor commit: Both updated Queue payload ([DirectBootAlarmCache.KEY_QUEUE_PAYLOAD])
 *   and updated Delivery Ledger payload ([DirectBootDeliveryLedger.KEY_DELIVERY_LEDGER_PAYLOAD]) are persisted
 *   in a single [SharedPreferences.Editor.commit] call to avoid partial-write split-brain between Queue and Ledger.
 * - Single Storage Owner: Serialized via [DirectBootStorageLock.lock] across all Queue and Ledger operations.
 * - Authoritative Queue Head Qualification:
 *   - The occurrence to be consumed MUST be the authoritative eligible head of the DE Queue ([currentQueueItems.first()]).
 *   - Fired events matching non-head queue occurrences are rejected ([DeliveryTransitionResult.Rejected.NotEligibleQueueHead])
 *     without storage mutation.
 *   - [deliveredAt] is validated against [triggerTime]: premature deliveries ([deliveredAt < triggerTime]) are rejected
 *     ([DeliveryTransitionResult.Rejected.PrematureTrigger]) without storage mutation.
 *   - OS Receipt Separation: Caller-supplied [deliveredAt] is validated for temporal qualification only and does NOT
 *     constitute proof of authenticated OS intent receipt; authenticated OS intent dispatch is gated at the Runtime boundary.
 * - Exact Normal Occurrence Identity: Strictly requires [kind == NORMAL], [alarmId > 0], [triggerTime > 0],
 *   [generation >= 1], and non-blank [instanceToken]. Rejects stale generations or altered tokens without mutation.
 * - Quick Alarm Boundary: Quick delivery transitions are NOT authorized in Slice D2. Quick transition attempts
 *   are rejected with [DeliveryTransitionResult.Rejected.QuickNotQualified]. Any existing Quick entries in the
 *   Queue are preserved strictly intact during Normal occurrence consumption.
 * - Fail-Closed Storage Health: If either Queue or Ledger storage is CORRUPT or UNSUPPORTED_SCHEMA,
 *   the transition is rejected immediately, leaving both raw payloads completely untouched.
 * - Capacity Boundary (16 records): Rejects with [DeliveryTransitionResult.Rejected.CapacityExhausted] if the Ledger
 *   already contains [DirectBootDeliveryLedger.MAX_CAPACITY] unacknowledged records, without dropping unrecorded deliveries.
 * - Pure & Inactive: Computes and returns remaining queue items and the next candidate to arm as data only.
 *   Zero AlarmManager, sound, notification, or session mutations.
 *
 * Atomicity & Persistence Limitations:
 * - One Editor, Two Keys, One In-Process Lock: Persistence combines two key updates in a single [SharedPreferences.Editor].
 * - Filesystem Boundaries: SharedPreferences persists via an atomic file replacement (writing to a temporary file and renaming).
 *   While this protects against app-level crashes during the write, it is NOT crash-proof against sudden hardware power-loss,
 *   kernel crashes, or underlying filesystem corruption.
 * - Commit Semantics: [commit()] returns false or throws on I/O error or storage exhaustion. Such failures leave the
 *   resulting in-memory and on-disk state uncertain, classified as [DeliveryTransitionResult.Uncertain].
 * - OS Registration Limitations: Confirmed persistence in DE storage does NOT guarantee that the OS AlarmManager has
 *   successfully scheduled the subsequent alarm ([nextCandidateToArm]). Power-loss immediately after commit will leave
 *   the delivery recorded in DE while the next alarm registration remains pending.
 *
 * Contract Gap & Lifecycle Invariant:
 * - Unacknowledged deliveries recorded in the Delivery Ledger remain unrecorded in CE Room storage until CE unlocks and reconciles.
 * - If [DirectBootNormalProjector] executes before CE authority reconciles and settles the consumed occurrence, the Projector
 *   may re-project the consumed occurrence from CE authority unless filtered.
 * - Resolving this gap is explicitly classified as a subsequent Ledger-aware projection/recovery gate (KNO-75 Phase 5);
 *   no Projector or CE mutation is performed in this phase.
 */
object DirectBootDeliveryTransitionEngine {
    private const val TAG = "DirectBootDeliveryTransition"

    private enum class ExecutionStage {
        BEFORE_COMMIT,
        COMMIT_INVOKED,
        COMMIT_CONFIRMED
    }

    private val defaultEditorCommitter: (SharedPreferences, String, String) -> Boolean =
        { prefs, queuePayload, ledgerPayload ->
            prefs.edit()
                .putString(DirectBootAlarmCache.KEY_QUEUE_PAYLOAD, queuePayload)
                .putString(DirectBootDeliveryLedger.KEY_DELIVERY_LEDGER_PAYLOAD, ledgerPayload)
                .commit()
        }

    /**
     * Production entrypoint for executing an atomic delivery transition for a firing Normal alarm occurrence.
     * Always commits directly to device-protected SharedPreferences ([DirectBootDeliveryLedger.PREFS_NAME])
     * via a single [SharedPreferences.Editor.commit] call updating both Queue and Ledger payloads.
     * Does not expose committer or test hooks to production callers.
     */
    fun transitionNormal(
        context: Context,
        firingIdentity: FiredOccurrenceIdentity,
        deliveredAt: Long = System.currentTimeMillis()
    ): DeliveryTransitionResult {
        return transitionNormalInternal(
            context = context,
            firingIdentity = firingIdentity,
            deliveredAt = deliveredAt,
            preCommitHook = {},
            postCommitHook = {},
            editorCommitter = null
        )
    }

    /**
     * Testing seam for deterministic fault injection (cancellation, commit failure/exception).
     * Strictly [VisibleForTesting] and internal to test fixtures.
     */
    @VisibleForTesting
    internal fun transitionNormalForTesting(
        context: Context,
        firingIdentity: FiredOccurrenceIdentity,
        deliveredAt: Long = System.currentTimeMillis(),
        preCommitHook: () -> Unit = {},
        postCommitHook: () -> Unit = {},
        editorCommitter: ((SharedPreferences, String, String) -> Boolean)? = null
    ): DeliveryTransitionResult {
        return transitionNormalInternal(
            context = context,
            firingIdentity = firingIdentity,
            deliveredAt = deliveredAt,
            preCommitHook = preCommitHook,
            postCommitHook = postCommitHook,
            editorCommitter = editorCommitter
        )
    }

    private fun transitionNormalInternal(
        context: Context,
        firingIdentity: FiredOccurrenceIdentity,
        deliveredAt: Long,
        preCommitHook: () -> Unit,
        postCommitHook: () -> Unit,
        editorCommitter: ((SharedPreferences, String, String) -> Boolean)?
    ): DeliveryTransitionResult {
        // Step 1: Validate Kind & Authorization
        if (firingIdentity.kind == DirectBootDeliveryRecord.KIND_QUICK) {
            return DeliveryTransitionResult.Rejected.QuickNotQualified(
                "Quick delivery transitions are not authorized in Slice D2; identity redesign deferred"
            )
        }
        if (firingIdentity.kind != DirectBootDeliveryRecord.KIND_NORMAL) {
            return DeliveryTransitionResult.Rejected.InvalidOccurrenceIdentity(
                "Expected kind '${DirectBootDeliveryRecord.KIND_NORMAL}', found '${firingIdentity.kind}'"
            )
        }

        // Step 2: Validate Complete Normal Identity Fields
        if (firingIdentity.alarmId <= 0L) {
            return DeliveryTransitionResult.Rejected.InvalidOccurrenceIdentity(
                "Invalid alarmId: ${firingIdentity.alarmId}"
            )
        }
        if (firingIdentity.triggerTime <= 0L) {
            return DeliveryTransitionResult.Rejected.InvalidOccurrenceIdentity(
                "Invalid triggerTime: ${firingIdentity.triggerTime}"
            )
        }
        if (firingIdentity.generation < 1) {
            return DeliveryTransitionResult.Rejected.InvalidOccurrenceIdentity(
                "Invalid generation for Normal alarm: ${firingIdentity.generation} (must be >= 1)"
            )
        }
        if (firingIdentity.instanceToken.isBlank()) {
            return DeliveryTransitionResult.Rejected.InvalidOccurrenceIdentity(
                "instanceToken must not be blank for Normal alarm"
            )
        }
        if (deliveredAt <= 0L) {
            return DeliveryTransitionResult.Rejected.InvalidOccurrenceIdentity(
                "Invalid deliveredAt timestamp: $deliveredAt"
            )
        }

        synchronized(DirectBootStorageLock.lock) {
            // Step 3: Inspect Both Storage Domains Under Shared Lock
            val queueState = DirectBootAlarmCache.inspectQueueStorageState(context)
            val ledgerState = DirectBootDeliveryLedger.inspectStorageState(context)

            // Step 4: Validate Queue Storage Health
            val currentQueueItems: List<DirectBootQueueItem> = when (queueState) {
                is QueueStorageState.Absent -> {
                    return DeliveryTransitionResult.Rejected.QueueAbsent()
                }
                is QueueStorageState.ValidEmpty -> {
                    return DeliveryTransitionResult.Rejected.OccurrenceNotFound(
                        "Queue is empty; no occurrence to consume for alarmId ${firingIdentity.alarmId}"
                    )
                }
                is QueueStorageState.ValidWithItems -> queueState.items
                is QueueStorageState.Corrupt -> {
                    Log.w(TAG, "transitionNormal rejected: Queue storage is corrupt (${queueState.reason})")
                    return DeliveryTransitionResult.Rejected.QueueStorageCorrupt(queueState.reason)
                }
                is QueueStorageState.UnsupportedSchema -> {
                    Log.w(TAG, "transitionNormal rejected: Queue unsupported schema version ${queueState.foundVersion}")
                    return DeliveryTransitionResult.Rejected.UnsupportedSchema(
                        domain = "Queue",
                        found = queueState.foundVersion,
                        expected = queueState.expectedVersion
                    )
                }
            }

            // Step 5: Validate Ledger Storage Health & Read Unacknowledged Records
            val currentLedgerRecords: List<DirectBootDeliveryRecord> = when (ledgerState) {
                is LedgerStorageState.Absent -> emptyList()
                is LedgerStorageState.ValidEmpty -> emptyList()
                is LedgerStorageState.ValidWithRecords -> ledgerState.records
                is LedgerStorageState.Corrupt -> {
                    Log.w(TAG, "transitionNormal rejected: Ledger storage is corrupt (${ledgerState.reason})")
                    return DeliveryTransitionResult.Rejected.LedgerStorageCorrupt(ledgerState.reason)
                }
                is LedgerStorageState.UnsupportedSchema -> {
                    Log.w(TAG, "transitionNormal rejected: Ledger unsupported schema version ${ledgerState.foundVersion}")
                    return DeliveryTransitionResult.Rejected.UnsupportedSchema(
                        domain = "Ledger",
                        found = ledgerState.foundVersion,
                        expected = ledgerState.expectedVersion
                    )
                }
            }

            // Step 6: Enforce Ledger Capacity Boundary (Max 16)
            if (currentLedgerRecords.size >= DirectBootDeliveryLedger.MAX_CAPACITY) {
                Log.w(TAG, "transitionNormal rejected: Ledger capacity exhausted (${currentLedgerRecords.size}/${DirectBootDeliveryLedger.MAX_CAPACITY})")
                return DeliveryTransitionResult.Rejected.CapacityExhausted(
                    currentCount = currentLedgerRecords.size,
                    maxCapacity = DirectBootDeliveryLedger.MAX_CAPACITY
                )
            }

            // Step 7: Check Duplicate Delivery Record in Ledger
            val targetRecordId = DirectBootDeliveryRecord.computeRecordId(
                kind = firingIdentity.kind,
                alarmId = firingIdentity.alarmId,
                triggerTime = firingIdentity.triggerTime,
                generation = firingIdentity.generation,
                instanceToken = firingIdentity.instanceToken
            )
            val existingRecord = currentLedgerRecords.find { it.recordId == targetRecordId }
            if (existingRecord != null) {
                Log.w(TAG, "transitionNormal rejected: Record $targetRecordId already recorded in Ledger")
                return DeliveryTransitionResult.Rejected.DuplicateDeliveryAlreadyRecorded(
                    existing = existingRecord,
                    ledgerCount = currentLedgerRecords.size
                )
            }

            // Step 8: Validate Authoritative Queue Head Qualification & Identity Matching
            val queueHead = currentQueueItems.first()
            val matchesHead = queueHead.kind == firingIdentity.kind &&
                queueHead.alarmId == firingIdentity.alarmId &&
                queueHead.generation == firingIdentity.generation &&
                queueHead.instanceToken == firingIdentity.instanceToken &&
                queueHead.triggerTime == firingIdentity.triggerTime

            if (!matchesHead) {
                // Diagnose exact mismatch reason
                val matchingIndex = currentQueueItems.indexOfFirst {
                    it.kind == firingIdentity.kind &&
                        it.alarmId == firingIdentity.alarmId &&
                        it.generation == firingIdentity.generation &&
                        it.instanceToken == firingIdentity.instanceToken &&
                        it.triggerTime == firingIdentity.triggerTime
                }
                if (matchingIndex > 0) {
                    return DeliveryTransitionResult.Rejected.NotEligibleQueueHead(
                        details = "Occurrence for alarmId ${firingIdentity.alarmId} is at queue position $matchingIndex, but authoritative queue head is alarmId ${queueHead.alarmId} (triggerTime ${queueHead.triggerTime})",
                        headCandidate = queueHead
                    )
                }
                if (queueHead.alarmId == firingIdentity.alarmId) {
                    if (queueHead.generation != firingIdentity.generation) {
                        return DeliveryTransitionResult.Rejected.StaleGenerationMismatch(
                            foundGeneration = queueHead.generation,
                            targetGeneration = firingIdentity.generation
                        )
                    }
                    return DeliveryTransitionResult.Rejected.OccurrenceIdentityMismatch(
                        "Authoritative queue head has alarmId ${queueHead.alarmId}, but triggerTime (${queueHead.triggerTime} vs ${firingIdentity.triggerTime}) or instanceToken differs"
                    )
                }
                val otherWithAlarmId = currentQueueItems.firstOrNull { it.alarmId == firingIdentity.alarmId }
                if (otherWithAlarmId != null) {
                    return DeliveryTransitionResult.Rejected.NotEligibleQueueHead(
                        details = "Occurrence for alarmId ${firingIdentity.alarmId} is not at queue head (authoritative head is alarmId ${queueHead.alarmId})",
                        headCandidate = queueHead
                    )
                }
                return DeliveryTransitionResult.Rejected.OccurrenceNotFound(
                    "No matching occurrence found for alarmId ${firingIdentity.alarmId} (authoritative queue head is alarmId ${queueHead.alarmId})"
                )
            }

            // Step 9: Validate Firing Qualification (Temporal Boundary: deliveredAt >= triggerTime)
            if (deliveredAt < queueHead.triggerTime) {
                return DeliveryTransitionResult.Rejected.PrematureTrigger(
                    deliveredAt = deliveredAt,
                    triggerTime = queueHead.triggerTime,
                    details = "Delivered timestamp $deliveredAt is earlier than scheduled triggerTime ${queueHead.triggerTime}"
                )
            }

            // Step 10: Construct Updated Queue and Ledger Payloads
            val updatedQueue = currentQueueItems.drop(1)

            val newDeliveryRecord = DirectBootDeliveryRecord.createNormal(
                alarmId = firingIdentity.alarmId,
                triggerTime = firingIdentity.triggerTime,
                deliveredAt = deliveredAt,
                generation = firingIdentity.generation,
                instanceToken = firingIdentity.instanceToken,
                provenance = firingIdentity.provenance
            )
            val updatedLedger = currentLedgerRecords + newDeliveryRecord

            val queueRoot = JSONObject().apply {
                put("schemaVersion", DirectBootQueueItem.CURRENT_SCHEMA_VERSION)
                val array = JSONArray()
                updatedQueue.forEach { array.put(it.toJson()) }
                put("items", array)
            }

            val ledgerRoot = JSONObject().apply {
                put("schemaVersion", DirectBootDeliveryLedger.CURRENT_SCHEMA_VERSION)
                val array = JSONArray()
                updatedLedger.forEach { array.put(it.toJson()) }
                put("records", array)
            }

            val prefs = prefs(context)
            var stage = ExecutionStage.BEFORE_COMMIT
            val committer = editorCommitter ?: defaultEditorCommitter

            return try {
                preCommitHook()

                stage = ExecutionStage.COMMIT_INVOKED
                val success = committer(prefs, queueRoot.toString(), ledgerRoot.toString())

                if (success) {
                    stage = ExecutionStage.COMMIT_CONFIRMED
                    postCommitHook()
                    val nextCandidate = updatedQueue.firstOrNull()
                    DeliveryTransitionResult.Confirmed(
                        consumedItem = queueHead,
                        deliveryRecord = newDeliveryRecord,
                        remainingQueue = updatedQueue,
                        nextCandidateToArm = nextCandidate
                    )
                } else {
                    DeliveryTransitionResult.Uncertain.CommitFailed(
                        details = "SharedPreferences.Editor.commit() returned false"
                    )
                }
            } catch (cancellation: CancellationException) {
                when (stage) {
                    ExecutionStage.COMMIT_CONFIRMED -> {
                        DeliveryTransitionResult.CancellationAfterCommit(
                            consumedItem = queueHead,
                            deliveryRecord = newDeliveryRecord,
                            remainingQueue = updatedQueue,
                            nextCandidateToArm = updatedQueue.firstOrNull(),
                            cancellation = cancellation
                        )
                    }
                    ExecutionStage.COMMIT_INVOKED -> {
                        DeliveryTransitionResult.Uncertain.CommitException(
                            details = "Cancellation during or immediately following Editor commit",
                            cause = cancellation
                        )
                    }
                    ExecutionStage.BEFORE_COMMIT -> {
                        throw cancellation
                    }
                }
            } catch (e: Exception) {
                when (stage) {
                    ExecutionStage.COMMIT_CONFIRMED -> {
                        DeliveryTransitionResult.Uncertain.CommitException(
                            details = "Exception after confirmed commit: ${e.message}",
                            cause = e
                        )
                    }
                    ExecutionStage.COMMIT_INVOKED -> {
                        DeliveryTransitionResult.Uncertain.CommitException(
                            details = "Exception during Editor commit invocation: ${e.message}",
                            cause = e
                        )
                    }
                    ExecutionStage.BEFORE_COMMIT -> {
                        DeliveryTransitionResult.Rejected.PreCommitFailure(
                            details = "Pre-commit failure: ${e.message}",
                            cause = e
                        )
                    }
                }
            }
        }
    }

    private fun prefs(context: Context): SharedPreferences {
        return context.applicationContext.createDeviceProtectedStorageContext()
            .getSharedPreferences(DirectBootDeliveryLedger.PREFS_NAME, Context.MODE_PRIVATE)
    }
}

/**
 * Concrete occurrence identity presented by the firing alarm execution boundary.
 */
data class FiredOccurrenceIdentity(
    val kind: String,                      // Strictly "NORMAL" in Slice D2
    val alarmId: Long,                     // Primary Key (> 0L)
    val triggerTime: Long,                 // Scheduled epoch millis (> 0L)
    val generation: Int,                   // Schedule generation (Normal: >= 1)
    val instanceToken: String,             // Authority lineage token (non-blank for Normal)
    val provenance: String = ""            // Optional provenance metadata
) {
    fun isValidNormal(): Boolean {
        return kind == DirectBootDeliveryRecord.KIND_NORMAL &&
            alarmId > 0L &&
            triggerTime > 0L &&
            generation >= 1 &&
            instanceToken.isNotBlank()
    }
}

/**
 * Typed result hierarchy for [DirectBootDeliveryTransitionEngine.transitionNormal].
 */
sealed class DeliveryTransitionResult {
    abstract val isGuaranteedNoWrite: Boolean
    abstract val deWriteAttempted: Boolean

    /**
     * Successful atomic transition: occurrence removed from Queue and recorded in Delivery Ledger.
     */
    data class Confirmed(
        val consumedItem: DirectBootQueueItem,
        val deliveryRecord: DirectBootDeliveryRecord,
        val remainingQueue: List<DirectBootQueueItem>,
        val nextCandidateToArm: DirectBootQueueItem?
    ) : DeliveryTransitionResult() {
        override val isGuaranteedNoWrite: Boolean get() = false
        override val deWriteAttempted: Boolean get() = true
    }

    /**
     * Cancellation occurred immediately after confirmed persistence.
     */
    data class CancellationAfterCommit(
        val consumedItem: DirectBootQueueItem,
        val deliveryRecord: DirectBootDeliveryRecord,
        val remainingQueue: List<DirectBootQueueItem>,
        val nextCandidateToArm: DirectBootQueueItem?,
        val cancellation: CancellationException
    ) : DeliveryTransitionResult() {
        override val isGuaranteedNoWrite: Boolean get() = false
        override val deWriteAttempted: Boolean get() = true
    }

    /**
     * Transition was rejected prior to write. Guaranteed zero storage modifications.
     */
    sealed class Rejected : DeliveryTransitionResult() {
        override val isGuaranteedNoWrite: Boolean get() = true
        override val deWriteAttempted: Boolean get() = false

        data class QuickNotQualified(val details: String) : Rejected()
        data class InvalidOccurrenceIdentity(val details: String) : Rejected()
        data class CapacityExhausted(val currentCount: Int, val maxCapacity: Int) : Rejected()
        data class DuplicateDeliveryAlreadyRecorded(val existing: DirectBootDeliveryRecord, val ledgerCount: Int) : Rejected()
        data class QueueAbsent(val details: String = "Queue payload is absent") : Rejected()
        data class OccurrenceNotFound(val details: String) : Rejected()
        data class NotEligibleQueueHead(val details: String, val headCandidate: DirectBootQueueItem) : Rejected()
        data class PrematureTrigger(val deliveredAt: Long, val triggerTime: Long, val details: String) : Rejected()
        data class StaleGenerationMismatch(val foundGeneration: Int, val targetGeneration: Int) : Rejected()
        data class OccurrenceIdentityMismatch(val details: String) : Rejected()
        data class QueueStorageCorrupt(val reason: String) : Rejected()
        data class LedgerStorageCorrupt(val reason: String) : Rejected()
        data class UnsupportedSchema(val domain: String, val found: Int, val expected: Int) : Rejected()
        data class PreCommitFailure(val details: String, val cause: Throwable) : Rejected()
    }

    /**
     * Persistence was attempted but its outcome cannot be guaranteed.
     */
    sealed class Uncertain : DeliveryTransitionResult() {
        override val isGuaranteedNoWrite: Boolean get() = false
        override val deWriteAttempted: Boolean get() = true
        abstract val details: String

        data class CommitFailed(override val details: String) : Uncertain()
        data class CommitException(override val details: String, val cause: Throwable) : Uncertain()
    }
}
