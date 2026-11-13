package com.sysadmindoc.alarmclock.directboot

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.annotation.VisibleForTesting
import kotlinx.coroutines.CancellationException
import org.json.JSONArray
import org.json.JSONObject

/**
 * Inactive Normal-only missed transition engine for KNO-75 Slice D3-C2-P3C (with C1 & C3 safety corrections).
 *
 * Responsibilities & Invariants:
 * - Canonical Policy Authority ([DirectBootOverduePolicyEngine]):
 *   - [DirectBootOverduePolicyEngine.evaluate] is the SOLE authority for Missed transition qualification.
 *   - Raw classification strings (`"OVERDUE"`, `"COLD_BOOT"`) or caller-constructed [OverduePolicyOutcome] instances
 *     are NEVER trusted as transition authority.
 *   - Policy evaluation and DE storage commit execute atomically under the same [DirectBootStorageLock.lock] monitor.
 *   - Only [OverduePolicyOutcome.OverdueMissedCandidate] (`-> "OVERDUE"`) and
 *     [OverduePolicyOutcome.ColdBootMissedCandidate] (`-> "COLD_BOOT"`) authorize a transition.
 *   - All other [OverduePolicyOutcome] results fail closed with zero storage mutation.
 *   - Note: Reliable runtime generation/qualification of [OverdueQualifiedFacts] and [OverdueTriggerProvenance]
 *     remains outside this inactive slice (C7 BLOCKED). Callers must supply explicit facts/provenance;
 *     this engine never synthesizes `Verified` defaults, never guesses `rebootStatus`, `clockRollbackStatus`,
 *     or `cancellationStatus`, and never fabricates monotonic anchors.
 * - Mandatory In-Lock Execution Order:
 *   1. Acquire [DirectBootStorageLock.lock].
 *   2. Read current DE storage states: Queue ([DirectBootAlarmCache]), Delivery Ledger ([DirectBootDeliveryLedger]),
 *      and Missed Store ([DirectBootMissedStore]).
 *   3. Fail-closed on any CORRUPT, UNSUPPORTED_SCHEMA, or UNCERTAIN state across Queue, Ledger, or Missed Store.
 *   4. Match candidate occurrence identity against current storage states.
 *   5. Enforce idempotency and mutual exclusion between Queue, Delivery Ledger, and Missed Store:
 *      - Recorded in Missed Store + retired from Queue (and absent from Ledger): [MissedTransitionResult.AlreadyApplied].
 *      - Present in both Queue and Missed Store, or both Ledger and Missed Store: [MissedTransitionResult.Rejected.ConflictingState].
 *      - Recorded in Delivery Ledger: [MissedTransitionResult.Rejected.AlreadyDeliveredInLedger] (Missed recording prohibited).
 *      - Unrecorded and authoritative Queue Head: proceed to in-lock Policy evaluation.
 *   6. Execute [DirectBootOverduePolicyEngine.evaluate] under the same lock.
 *   7. Permit transition ONLY on [OverduePolicyOutcome.OverdueMissedCandidate] or [OverduePolicyOutcome.ColdBootMissedCandidate].
 *   8. Derive stored classification strictly from the Policy outcome (`OVERDUE` or `COLD_BOOT`).
 *   9. Reject all other Policy outcomes fail-closed with zero storage mutation.
 *   10. Re-verify capacity (max 16), non-duplication, and record validity, then persist Queue retirement and Missed record
 *       in a single [SharedPreferences.Editor.commit] call.
 * - Strict ExecutionStage & Post-Commit Result Semantics:
 *   - [ExecutionStage.PRE_COMMIT]: No persistence attempted; [CancellationException] is rethrown; other exceptions
 *     return [MissedTransitionResult.Rejected.PreCommitFailure].
 *   - [ExecutionStage.COMMIT_ATTEMPTED]: `commit() == false`, commit exception, or cancellation during commit returns
 *     typed [MissedTransitionResult.Uncertain] (`CommitFailed` or `CommitException`). Never returns `Confirmed`.
 *   - [ExecutionStage.COMMIT_CONFIRMED]: `commit() == true` completed synchronously; non-cancellation exceptions return
 *     [MissedTransitionResult.PostCommitFailure] preserving `consumedItem`, `missedRecord`, `remainingQueue`,
 *     `nextCandidateToArm`, and `cause` (never misclassified as `Uncertain.CommitException`); [CancellationException]
 *     returns [MissedTransitionResult.CancellationAfterCommit].
 */
object DirectBootMissedTransitionEngine {
    private const val TAG = "DirectBootMissedTransition"

    private enum class ExecutionStage {
        PRE_COMMIT,
        COMMIT_ATTEMPTED,
        COMMIT_CONFIRMED
    }

    private val defaultEditorCommitter: (SharedPreferences, String, String) -> Boolean =
        { prefs, queuePayload, missedPayload ->
            prefs.edit()
                .putString(DirectBootAlarmCache.KEY_QUEUE_PAYLOAD, queuePayload)
                .putString(DirectBootMissedStore.KEY_MISSED_STORE_PAYLOAD, missedPayload)
                .commit()
        }

    private fun computeDefaultLatenessOrNull(missedAt: Long, triggerTime: Long): Long? {
        return try {
            Math.subtractExact(missedAt, triggerTime)
        } catch (e: ArithmeticException) {
            null
        }
    }

    /**
     * Canonical Policy-qualified transition entrypoint for [FiredOccurrenceIdentity].
     *
     * Evaluates [DirectBootOverduePolicyEngine.evaluate] inside [DirectBootStorageLock.lock] against live
     * Queue, Delivery Ledger, and Missed Store states.
     */
    fun transitionMissed(
        context: Context,
        occurrenceIdentity: FiredOccurrenceIdentity,
        missedAt: Long = System.currentTimeMillis(),
        facts: OverdueQualifiedFacts,
        provenance: OverdueTriggerProvenance,
        currentElapsedRealtimeMs: Long? = null,
        expectedClassification: String? = null,
        latenessMs: Long? = computeDefaultLatenessOrNull(missedAt, occurrenceIdentity.triggerTime)
    ): MissedTransitionResult {
        return transitionMissedInternal(
            context = context,
            occurrenceIdentity = occurrenceIdentity,
            missedAt = missedAt,
            facts = facts,
            provenance = provenance,
            currentElapsedRealtimeMs = currentElapsedRealtimeMs,
            expectedClassification = expectedClassification,
            latenessMs = latenessMs,
            preCommitHook = {},
            postCommitHook = {},
            editorCommitter = null
        )
    }

    /**
     * Canonical Policy-qualified transition entrypoint for [DirectBootV2NormalIntentIdentity].
     */
    fun transitionMissed(
        context: Context,
        identity: DirectBootV2NormalIntentIdentity,
        missedAt: Long = System.currentTimeMillis(),
        facts: OverdueQualifiedFacts,
        provenance: OverdueTriggerProvenance,
        currentElapsedRealtimeMs: Long? = null,
        expectedClassification: String? = null,
        latenessMs: Long? = computeDefaultLatenessOrNull(missedAt, identity.triggerTime)
    ): MissedTransitionResult {
        return transitionMissed(
            context = context,
            occurrenceIdentity = FiredOccurrenceIdentity(
                kind = DirectBootDeliveryRecord.KIND_NORMAL,
                alarmId = identity.alarmId,
                triggerTime = identity.triggerTime,
                generation = identity.generation,
                instanceToken = identity.instanceToken
            ),
            missedAt = missedAt,
            facts = facts,
            provenance = provenance,
            currentElapsedRealtimeMs = currentElapsedRealtimeMs,
            expectedClassification = expectedClassification,
            latenessMs = latenessMs
        )
    }

    /**
     * Legacy raw-classification entrypoint: permanently sealed fail-closed.
     *
     * Raw classification strings without [OverdueQualifiedFacts] and [OverdueTriggerProvenance]
     * are prohibited from authorizing a Missed transition.
     */
    @Deprecated(
        message = "Raw classification cannot authorize Missed transition without Policy facts and provenance.",
        ReplaceWith("transitionMissed(context, occurrenceIdentity, missedAt, facts, provenance)")
    )
    fun transitionMissed(
        context: Context,
        occurrenceIdentity: FiredOccurrenceIdentity,
        missedAt: Long = System.currentTimeMillis(),
        classification: String = DirectBootMissedRecord.CLASSIFICATION_OVERDUE,
        latenessMs: Long? = computeDefaultLatenessOrNull(missedAt, occurrenceIdentity.triggerTime)
    ): MissedTransitionResult {
        return transitionMissedInternal(
            context = context,
            occurrenceIdentity = occurrenceIdentity,
            missedAt = missedAt,
            facts = null,
            provenance = null,
            currentElapsedRealtimeMs = null,
            expectedClassification = classification,
            latenessMs = latenessMs,
            preCommitHook = {},
            postCommitHook = {},
            editorCommitter = null
        )
    }

    /**
     * Legacy raw-classification entrypoint for [DirectBootV2NormalIntentIdentity]: permanently sealed fail-closed.
     */
    @Deprecated(
        message = "Raw classification cannot authorize Missed transition without Policy facts and provenance.",
        ReplaceWith("transitionMissed(context, identity, missedAt, facts, provenance)")
    )
    fun transitionMissed(
        context: Context,
        identity: DirectBootV2NormalIntentIdentity,
        missedAt: Long = System.currentTimeMillis(),
        classification: String = DirectBootMissedRecord.CLASSIFICATION_OVERDUE,
        latenessMs: Long? = computeDefaultLatenessOrNull(missedAt, identity.triggerTime)
    ): MissedTransitionResult {
        return transitionMissedInternal(
            context = context,
            occurrenceIdentity = FiredOccurrenceIdentity(
                kind = DirectBootDeliveryRecord.KIND_NORMAL,
                alarmId = identity.alarmId,
                triggerTime = identity.triggerTime,
                generation = identity.generation,
                instanceToken = identity.instanceToken
            ),
            missedAt = missedAt,
            facts = null,
            provenance = null,
            currentElapsedRealtimeMs = null,
            expectedClassification = classification,
            latenessMs = latenessMs,
            preCommitHook = {},
            postCommitHook = {},
            editorCommitter = null
        )
    }

    /**
     * Entrypoint rejecting caller-supplied / pre-evaluated [OverduePolicyOutcome] as standalone authority.
     *
     * A caller-constructed or stale [OverduePolicyOutcome] evaluated outside [DirectBootStorageLock.lock]
     * is never trusted to authorize Queue retirement or Missed persistence.
     */
    fun transitionMissed(
        context: Context,
        occurrenceIdentity: FiredOccurrenceIdentity,
        missedAt: Long = System.currentTimeMillis(),
        claimedPolicyOutcome: OverduePolicyOutcome
    ): MissedTransitionResult {
        return synchronized(DirectBootStorageLock.lock) {
            MissedTransitionResult.Rejected.UnverifiedPolicyOutcome(
                claimedOutcome = claimedPolicyOutcome,
                details = "Caller-supplied OverduePolicyOutcome (${claimedPolicyOutcome::class.simpleName}) cannot authorize transition without live in-lock Policy evaluation"
            )
        }
    }

    @VisibleForTesting
    internal fun transitionMissedForTesting(
        context: Context,
        occurrenceIdentity: FiredOccurrenceIdentity,
        missedAt: Long = System.currentTimeMillis(),
        facts: OverdueQualifiedFacts? = null,
        provenance: OverdueTriggerProvenance? = null,
        currentElapsedRealtimeMs: Long? = null,
        classification: String? = null,
        latenessMs: Long? = computeDefaultLatenessOrNull(missedAt, occurrenceIdentity.triggerTime),
        preCommitHook: () -> Unit = {},
        postCommitHook: () -> Unit = {},
        editorCommitter: ((SharedPreferences, String, String) -> Boolean)? = null
    ): MissedTransitionResult {
        return transitionMissedInternal(
            context = context,
            occurrenceIdentity = occurrenceIdentity,
            missedAt = missedAt,
            facts = facts,
            provenance = provenance,
            currentElapsedRealtimeMs = currentElapsedRealtimeMs,
            expectedClassification = classification,
            latenessMs = latenessMs,
            preCommitHook = preCommitHook,
            postCommitHook = postCommitHook,
            editorCommitter = editorCommitter
        )
    }

    private fun transitionMissedInternal(
        context: Context,
        occurrenceIdentity: FiredOccurrenceIdentity,
        missedAt: Long,
        facts: OverdueQualifiedFacts?,
        provenance: OverdueTriggerProvenance?,
        currentElapsedRealtimeMs: Long?,
        expectedClassification: String?,
        latenessMs: Long?,
        preCommitHook: () -> Unit,
        postCommitHook: () -> Unit,
        editorCommitter: ((SharedPreferences, String, String) -> Boolean)?
    ): MissedTransitionResult {
        // Step 1: Acquire shared DirectBootStorageLock.lock first
        return synchronized(DirectBootStorageLock.lock) {
            // Validate optional expectedClassification if supplied
            if (expectedClassification != null && expectedClassification !in DirectBootMissedRecord.APPROVED_CLASSIFICATIONS) {
                return@synchronized MissedTransitionResult.Rejected.InvalidClassification(expectedClassification)
            }

            // Validate Complete Normal Identity Fields
            if (occurrenceIdentity.kind != DirectBootDeliveryRecord.KIND_NORMAL) {
                return@synchronized MissedTransitionResult.Rejected.InvalidOccurrenceIdentity(
                    "Expected kind '${DirectBootDeliveryRecord.KIND_NORMAL}', found '${occurrenceIdentity.kind}'"
                )
            }
            if (occurrenceIdentity.alarmId <= 0L) {
                return@synchronized MissedTransitionResult.Rejected.InvalidOccurrenceIdentity(
                    "Invalid alarmId: ${occurrenceIdentity.alarmId}"
                )
            }
            if (occurrenceIdentity.triggerTime <= 0L) {
                return@synchronized MissedTransitionResult.Rejected.InvalidOccurrenceIdentity(
                    "Invalid triggerTime: ${occurrenceIdentity.triggerTime}"
                )
            }
            if (occurrenceIdentity.generation < 1) {
                return@synchronized MissedTransitionResult.Rejected.InvalidOccurrenceIdentity(
                    "Invalid generation for Normal alarm: ${occurrenceIdentity.generation} (must be >= 1)"
                )
            }
            if (occurrenceIdentity.instanceToken.isBlank()) {
                return@synchronized MissedTransitionResult.Rejected.InvalidOccurrenceIdentity(
                    "instanceToken must not be blank for Normal alarm"
                )
            }
            if (missedAt <= 0L) {
                return@synchronized MissedTransitionResult.Rejected.InvalidOccurrenceIdentity(
                    "Invalid missedAt timestamp: $missedAt"
                )
            }

            // Validate Clock Arithmetic, Premature Trigger, and Caller Lateness Consistency
            val calculatedLateness = try {
                Math.subtractExact(missedAt, occurrenceIdentity.triggerTime)
            } catch (e: ArithmeticException) {
                return@synchronized MissedTransitionResult.Rejected.ClockArithmeticOverflow(
                    "Arithmetic overflow computing lateness (missedAt=$missedAt, triggerTime=${occurrenceIdentity.triggerTime})"
                )
            }

            if (latenessMs == null) {
                return@synchronized MissedTransitionResult.Rejected.ClockArithmeticOverflow(
                    "Caller lateness computation overflowed or was null"
                )
            }

            if (calculatedLateness < 0L) {
                return@synchronized MissedTransitionResult.Rejected.PrematureTrigger(
                    missedAt = missedAt,
                    triggerTime = occurrenceIdentity.triggerTime,
                    details = "Future occurrence cannot transition to Missed: missedAt ($missedAt) < triggerTime (${occurrenceIdentity.triggerTime})"
                )
            }

            if (latenessMs < 0L || latenessMs != calculatedLateness) {
                return@synchronized MissedTransitionResult.Rejected.LatenessInconsistent(
                    suppliedLatenessMs = latenessMs,
                    calculatedLatenessMs = calculatedLateness,
                    details = "Caller-supplied latenessMs ($latenessMs) does not match missedAt - triggerTime ($calculatedLateness)"
                )
            }

            // Step 2: Read Current DE Storage States (Queue, Delivery Ledger, Missed Store)
            val queueState = DirectBootAlarmCache.inspectQueueStorageState(context)
            val ledgerState = DirectBootDeliveryLedger.inspectStorageState(context)
            val missedState = DirectBootMissedStore.inspectStorageState(context)

            // Step 3: Fail-Closed on CORRUPT / UNSUPPORTED_SCHEMA / UNKNOWN across all 3 DE Storage Domains
            when (missedState) {
                is MissedStoreStorageState.Corrupt -> {
                    Log.w(TAG, "transitionMissed rejected: Missed storage is corrupt (${missedState.reason})")
                    return@synchronized MissedTransitionResult.Rejected.MissedStorageCorrupt(missedState.reason)
                }
                is MissedStoreStorageState.UnsupportedSchema -> {
                    Log.w(TAG, "transitionMissed rejected: Missed unsupported schema version ${missedState.foundVersion}")
                    return@synchronized MissedTransitionResult.Rejected.UnsupportedSchema(
                        domain = "Missed",
                        found = missedState.foundVersion,
                        expected = missedState.expectedVersion
                    )
                }
                is MissedStoreStorageState.PersistenceUncertain -> {
                    Log.w(TAG, "transitionMissed rejected: Missed storage state is uncertain (${missedState.reason})")
                    return@synchronized MissedTransitionResult.Rejected.StorageUncertain(missedState.reason)
                }
                is MissedStoreStorageState.Absent,
                is MissedStoreStorageState.ValidEmpty,
                is MissedStoreStorageState.ValidWithRecords -> Unit
            }

            when (ledgerState) {
                is LedgerStorageState.Corrupt -> {
                    Log.w(TAG, "transitionMissed rejected: Delivery Ledger storage is corrupt (${ledgerState.reason})")
                    return@synchronized MissedTransitionResult.Rejected.LedgerStorageCorrupt(ledgerState.reason)
                }
                is LedgerStorageState.UnsupportedSchema -> {
                    Log.w(TAG, "transitionMissed rejected: Delivery Ledger unsupported schema version ${ledgerState.foundVersion}")
                    return@synchronized MissedTransitionResult.Rejected.UnsupportedSchema(
                        domain = "Ledger",
                        found = ledgerState.foundVersion,
                        expected = ledgerState.expectedVersion
                    )
                }
                is LedgerStorageState.Absent,
                is LedgerStorageState.ValidEmpty,
                is LedgerStorageState.ValidWithRecords -> Unit
            }

            when (queueState) {
                is QueueStorageState.Corrupt -> {
                    Log.w(TAG, "transitionMissed rejected: Queue storage is corrupt (${queueState.reason})")
                    return@synchronized MissedTransitionResult.Rejected.QueueStorageCorrupt(queueState.reason)
                }
                is QueueStorageState.UnsupportedSchema -> {
                    Log.w(TAG, "transitionMissed rejected: Queue unsupported schema version ${queueState.foundVersion}")
                    return@synchronized MissedTransitionResult.Rejected.UnsupportedSchema(
                        domain = "Queue",
                        found = queueState.foundVersion,
                        expected = queueState.expectedVersion
                    )
                }
                is QueueStorageState.Absent,
                is QueueStorageState.ValidEmpty,
                is QueueStorageState.ValidWithItems -> Unit
            }

            // Step 4: Match Candidate Occurrence Identity Against Current Storage States
            val targetMissedRecordId = DirectBootMissedRecord.computeRecordId(
                alarmId = occurrenceIdentity.alarmId,
                triggerTime = occurrenceIdentity.triggerTime,
                generation = occurrenceIdentity.generation,
                instanceToken = occurrenceIdentity.instanceToken
            )
            val targetDeliveryRecordId = DirectBootDeliveryRecord.computeRecordId(
                kind = DirectBootDeliveryRecord.KIND_NORMAL,
                alarmId = occurrenceIdentity.alarmId,
                triggerTime = occurrenceIdentity.triggerTime,
                generation = occurrenceIdentity.generation,
                instanceToken = occurrenceIdentity.instanceToken
            )

            val currentMissedRecords: List<DirectBootMissedRecord> = when (missedState) {
                is MissedStoreStorageState.Absent, is MissedStoreStorageState.ValidEmpty -> emptyList()
                is MissedStoreStorageState.ValidWithRecords -> missedState.records
                else -> emptyList()
            }
            val currentLedgerRecords: List<DirectBootDeliveryRecord> = when (ledgerState) {
                is LedgerStorageState.Absent, is LedgerStorageState.ValidEmpty -> emptyList()
                is LedgerStorageState.ValidWithRecords -> ledgerState.records
                else -> emptyList()
            }

            val existingMissed = currentMissedRecords.find { it.recordId == targetMissedRecordId }
            val existingDelivered = currentLedgerRecords.find { it.recordId == targetDeliveryRecordId }

            val queueItems: List<DirectBootQueueItem> = when (queueState) {
                is QueueStorageState.ValidWithItems -> queueState.items
                else -> emptyList()
            }
            val isPresentInQueue = queueItems.any {
                it.kind == occurrenceIdentity.kind &&
                    it.alarmId == occurrenceIdentity.alarmId &&
                    it.triggerTime == occurrenceIdentity.triggerTime &&
                    it.generation == occurrenceIdentity.generation &&
                    it.instanceToken == occurrenceIdentity.instanceToken
            }

            // Step 5: Evaluate Idempotency, Conflict Detection, and Mutual Exclusion (Ledger vs Missed vs Queue)
            if (existingMissed != null && existingDelivered != null) {
                Log.w(TAG, "transitionMissed rejected: Conflict - occurrence exists in both Delivery Ledger and Missed Store")
                return@synchronized MissedTransitionResult.Rejected.ConflictingState(
                    "Record $targetMissedRecordId exists in Missed Store while $targetDeliveryRecordId also exists in Delivery Ledger"
                )
            }

            if (existingDelivered != null) {
                Log.w(TAG, "transitionMissed rejected: Occurrence already delivered in Delivery Ledger ($targetDeliveryRecordId)")
                return@synchronized MissedTransitionResult.Rejected.AlreadyDeliveredInLedger(
                    deliveryRecordId = existingDelivered.recordId,
                    details = "Occurrence $targetDeliveryRecordId is already recorded as delivered in Delivery Ledger; Missed recording prohibited"
                )
            }

            if (existingMissed != null && isPresentInQueue) {
                Log.w(TAG, "transitionMissed rejected: Conflict detected - record $targetMissedRecordId exists in Missed Store but is still present in Queue")
                return@synchronized MissedTransitionResult.Rejected.ConflictingState(
                    "Record $targetMissedRecordId already exists in Missed Store but is still present in Queue"
                )
            }

            if (existingMissed != null && !isPresentInQueue) {
                if (facts == null || provenance == null) {
                    return@synchronized MissedTransitionResult.Rejected.MissingPolicyQualification(
                        "DirectBootOverduePolicyEngine qualification (OverdueQualifiedFacts and OverdueTriggerProvenance) is required"
                    )
                }
                if (expectedClassification != null && expectedClassification != existingMissed.classification) {
                    return@synchronized MissedTransitionResult.Rejected.ConflictingState(
                        "Retry expectedClassification '$expectedClassification' conflicts with recorded classification '${existingMissed.classification}'"
                    )
                }
                return@synchronized MissedTransitionResult.AlreadyApplied(
                    existingRecord = existingMissed,
                    remainingQueue = queueItems,
                    nextCandidateToArm = queueItems.firstOrNull()
                )
            }

            // existingMissed == null && existingDelivered == null: check Queue presence & Head match
            when (queueState) {
                is QueueStorageState.Absent -> {
                    return@synchronized MissedTransitionResult.Rejected.QueueAbsent()
                }
                is QueueStorageState.ValidEmpty -> {
                    return@synchronized MissedTransitionResult.Rejected.OccurrenceNotFound(
                        "Queue is empty; no occurrence to consume for alarmId ${occurrenceIdentity.alarmId}"
                    )
                }
                is QueueStorageState.ValidWithItems -> Unit
                else -> {
                    return@synchronized MissedTransitionResult.Rejected.QueueAbsent()
                }
            }

            val queueHead = queueItems.first()
            val matchesHead = queueHead.kind == occurrenceIdentity.kind &&
                queueHead.alarmId == occurrenceIdentity.alarmId &&
                queueHead.triggerTime == occurrenceIdentity.triggerTime &&
                queueHead.generation == occurrenceIdentity.generation &&
                queueHead.instanceToken == occurrenceIdentity.instanceToken

            if (!matchesHead) {
                if (isPresentInQueue) {
                    return@synchronized MissedTransitionResult.Rejected.NotEligibleQueueHead(
                        details = "Occurrence for alarmId ${occurrenceIdentity.alarmId} is at non-head queue position",
                        headCandidate = queueHead
                    )
                }
                if (queueHead.alarmId == occurrenceIdentity.alarmId) {
                    if (queueHead.generation != occurrenceIdentity.generation) {
                        return@synchronized MissedTransitionResult.Rejected.StaleGenerationMismatch(
                            foundGeneration = queueHead.generation,
                            targetGeneration = occurrenceIdentity.generation
                        )
                    }
                    return@synchronized MissedTransitionResult.Rejected.OccurrenceIdentityMismatch(
                        "Authoritative queue head has alarmId ${queueHead.alarmId}, but triggerTime (${queueHead.triggerTime} vs ${occurrenceIdentity.triggerTime}) or instanceToken differs"
                    )
                }
                return@synchronized MissedTransitionResult.Rejected.OccurrenceNotFound(
                    "No matching occurrence found for alarmId ${occurrenceIdentity.alarmId} (authoritative queue head is alarmId ${queueHead.alarmId})"
                )
            }

            // Step 6: Execute DirectBootOverduePolicyEngine.evaluate() inside the same DirectBootStorageLock.lock
            if (facts == null || provenance == null) {
                return@synchronized MissedTransitionResult.Rejected.MissingPolicyQualification(
                    "DirectBootOverduePolicyEngine qualification (OverdueQualifiedFacts and OverdueTriggerProvenance) is required; raw classification cannot authorize transition"
                )
            }

            val policyCandidate = DirectBootV2NormalIntentIdentity(
                alarmId = occurrenceIdentity.alarmId,
                triggerTime = occurrenceIdentity.triggerTime,
                generation = occurrenceIdentity.generation,
                instanceToken = occurrenceIdentity.instanceToken
            )

            val policyOutcome = DirectBootOverduePolicyEngine.evaluate(
                candidate = policyCandidate,
                queueState = queueState,
                ledgerState = ledgerState,
                missedState = missedState.toPolicyMissedStorageState(),
                facts = facts,
                provenance = provenance,
                nowEpochMs = missedAt,
                currentElapsedRealtimeMs = currentElapsedRealtimeMs
            )

            // Step 7, 8, 9: Authorize ONLY on OverdueMissedCandidate or ColdBootMissedCandidate;
            // derive stored classification strictly from the Policy outcome; fail-closed on all others.
            val determinedClassification: String = when (policyOutcome) {
                is OverduePolicyOutcome.OverdueMissedCandidate -> {
                    DirectBootMissedRecord.CLASSIFICATION_OVERDUE
                }
                is OverduePolicyOutcome.ColdBootMissedCandidate -> {
                    DirectBootMissedRecord.CLASSIFICATION_COLD_BOOT
                }
                is OverduePolicyOutcome.NormalDispatchCandidate,
                is OverduePolicyOutcome.RecoveryDeliveryCandidate -> {
                    if (expectedClassification != null && expectedClassification != DirectBootMissedRecord.CLASSIFICATION_OVERDUE) {
                        return@synchronized MissedTransitionResult.Rejected.PolicyRejected(
                            policyOutcome = policyOutcome,
                            details = "Caller requested '$expectedClassification' but Policy evaluated ${policyOutcome::class.simpleName}: ${policyOutcome.details}"
                        )
                    }
                    return@synchronized MissedTransitionResult.Rejected.InsufficientLateness(
                        latenessMs = calculatedLateness,
                        requiredCeilingMs = DirectBootOverduePolicyEngine.MAX_RECOVERY_WINDOW_MS,
                        details = policyOutcome.details
                    )
                }
                is OverduePolicyOutcome.PrematureBlock -> {
                    return@synchronized MissedTransitionResult.Rejected.PrematureTrigger(
                        missedAt = missedAt,
                        triggerTime = occurrenceIdentity.triggerTime,
                        details = policyOutcome.details
                    )
                }
                else -> {
                    return@synchronized MissedTransitionResult.Rejected.PolicyRejected(
                        policyOutcome = policyOutcome,
                        details = policyOutcome.details
                    )
                }
            }

            if (expectedClassification != null && expectedClassification != determinedClassification) {
                return@synchronized MissedTransitionResult.Rejected.PolicyClassificationMismatch(
                    expectedClassification = expectedClassification,
                    policyClassification = determinedClassification,
                    policyOutcome = policyOutcome,
                    details = "Caller expectedClassification '$expectedClassification' disagrees with Policy classification '$determinedClassification'"
                )
            }

            // Step 10: Re-verify Capacity, Non-Duplication, Identity, and Commit Atomically via Single Editor.commit()
            if (currentMissedRecords.size >= DirectBootMissedStore.MAX_CAPACITY) {
                Log.w(TAG, "transitionMissed rejected: Missed capacity exhausted (${currentMissedRecords.size}/${DirectBootMissedStore.MAX_CAPACITY})")
                return@synchronized MissedTransitionResult.Rejected.CapacityExhausted(
                    currentCount = currentMissedRecords.size,
                    maxCapacity = DirectBootMissedStore.MAX_CAPACITY
                )
            }

            val effectiveEpoch = if (occurrenceIdentity.restoreEpoch > 0L) {
                occurrenceIdentity.restoreEpoch
            } else {
                queueHead.restoreEpoch
            }
            val recordSchemaVersion = if (effectiveEpoch > 0L) 2 else 1

            val newMissedRecord = DirectBootMissedRecord.createNormal(
                alarmId = occurrenceIdentity.alarmId,
                triggerTime = occurrenceIdentity.triggerTime,
                missedAt = missedAt,
                generation = occurrenceIdentity.generation,
                instanceToken = occurrenceIdentity.instanceToken,
                classification = determinedClassification,
                restoreEpoch = effectiveEpoch,
                schemaVersion = recordSchemaVersion
            )
            if (!newMissedRecord.isValid || newMissedRecord.recordId != targetMissedRecordId) {
                return@synchronized MissedTransitionResult.Rejected.InvalidOccurrenceIdentity(
                    "Constructed DirectBootMissedRecord failed validation (${newMissedRecord.evaluateValidation()})"
                )
            }
            if (currentMissedRecords.any { it.recordId == newMissedRecord.recordId }) {
                return@synchronized MissedTransitionResult.Rejected.ConflictingState(
                    "Duplicate recordId ${newMissedRecord.recordId} detected prior to commit"
                )
            }

            val updatedQueue = queueItems.drop(1)
            val updatedMissed = currentMissedRecords + newMissedRecord

            val queueRoot = JSONObject().apply {
                put("schemaVersion", DirectBootQueueItem.CURRENT_SCHEMA_VERSION)
                val array = JSONArray()
                updatedQueue.forEach { array.put(it.toJson()) }
                put("items", array)
            }
            val missedPayloadString = DirectBootMissedStore.serializeRecordsToJson(updatedMissed)

            val prefs = prefs(context)
            var stage = ExecutionStage.PRE_COMMIT
            val committer = editorCommitter ?: defaultEditorCommitter

            try {
                preCommitHook()

                stage = ExecutionStage.COMMIT_ATTEMPTED
                val success = committer(prefs, queueRoot.toString(), missedPayloadString)

                if (success) {
                    stage = ExecutionStage.COMMIT_CONFIRMED
                    postCommitHook()
                    val nextCandidate = updatedQueue.firstOrNull()
                    MissedTransitionResult.Confirmed(
                        consumedItem = queueHead,
                        missedRecord = newMissedRecord,
                        remainingQueue = updatedQueue,
                        nextCandidateToArm = nextCandidate
                    )
                } else {
                    MissedTransitionResult.Uncertain.CommitFailed(
                        details = "SharedPreferences.Editor.commit() returned false"
                    )
                }
            } catch (cancellation: CancellationException) {
                when (stage) {
                    ExecutionStage.COMMIT_CONFIRMED -> {
                        MissedTransitionResult.CancellationAfterCommit(
                            consumedItem = queueHead,
                            missedRecord = newMissedRecord,
                            remainingQueue = updatedQueue,
                            nextCandidateToArm = updatedQueue.firstOrNull(),
                            cancellation = cancellation
                        )
                    }
                    ExecutionStage.COMMIT_ATTEMPTED -> {
                        MissedTransitionResult.Uncertain.CommitException(
                            details = "Cancellation during or immediately following Editor commit",
                            cause = cancellation
                        )
                    }
                    ExecutionStage.PRE_COMMIT -> {
                        throw cancellation
                    }
                }
            } catch (e: Exception) {
                when (stage) {
                    ExecutionStage.COMMIT_CONFIRMED -> {
                        MissedTransitionResult.PostCommitFailure(
                            consumedItem = queueHead,
                            missedRecord = newMissedRecord,
                            remainingQueue = updatedQueue,
                            nextCandidateToArm = updatedQueue.firstOrNull(),
                            cause = e
                        )
                    }
                    ExecutionStage.COMMIT_ATTEMPTED -> {
                        MissedTransitionResult.Uncertain.CommitException(
                            details = "Exception during Editor commit invocation: ${e.message}",
                            cause = e
                        )
                    }
                    ExecutionStage.PRE_COMMIT -> {
                        MissedTransitionResult.Rejected.PreCommitFailure(
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
            .getSharedPreferences(DirectBootMissedStore.PREFS_NAME, Context.MODE_PRIVATE)
    }
}

/**
 * Typed result hierarchy for [DirectBootMissedTransitionEngine.transitionMissed].
 */
sealed class MissedTransitionResult {
    abstract val isGuaranteedNoWrite: Boolean
    abstract val deWriteAttempted: Boolean

    /**
     * Successful atomic transition: occurrence removed from Queue and recorded in Missed Store.
     */
    data class Confirmed(
        val consumedItem: DirectBootQueueItem,
        val missedRecord: DirectBootMissedRecord,
        val remainingQueue: List<DirectBootQueueItem>,
        val nextCandidateToArm: DirectBootQueueItem?
    ) : MissedTransitionResult() {
        override val isGuaranteedNoWrite: Boolean get() = false
        override val deWriteAttempted: Boolean get() = true
    }

    /**
     * Occurrence was already transitioned and recorded in Missed Store.
     */
    data class AlreadyApplied(
        val existingRecord: DirectBootMissedRecord,
        val remainingQueue: List<DirectBootQueueItem>,
        val nextCandidateToArm: DirectBootQueueItem?
    ) : MissedTransitionResult() {
        override val isGuaranteedNoWrite: Boolean get() = true
        override val deWriteAttempted: Boolean get() = false
    }

    /**
     * Non-cancellation exception occurred after [SharedPreferences.Editor.commit] returned true.
     *
     * Preserves all confirmed transition artifacts (`consumedItem`, `missedRecord`, `remainingQueue`,
     * `nextCandidateToArm`) and `cause`. Never classified as [Uncertain.CommitException].
     * Does NOT initiate autonomous retries or AlarmManager registrations.
     */
    data class PostCommitFailure(
        val consumedItem: DirectBootQueueItem,
        val missedRecord: DirectBootMissedRecord,
        val remainingQueue: List<DirectBootQueueItem>,
        val nextCandidateToArm: DirectBootQueueItem?,
        val cause: Throwable
    ) : MissedTransitionResult() {
        override val isGuaranteedNoWrite: Boolean get() = false
        override val deWriteAttempted: Boolean get() = true
    }

    /**
     * Cancellation occurred immediately after confirmed persistence.
     */
    data class CancellationAfterCommit(
        val consumedItem: DirectBootQueueItem,
        val missedRecord: DirectBootMissedRecord,
        val remainingQueue: List<DirectBootQueueItem>,
        val nextCandidateToArm: DirectBootQueueItem?,
        val cancellation: CancellationException
    ) : MissedTransitionResult() {
        val cause: CancellationException get() = cancellation
        override val isGuaranteedNoWrite: Boolean get() = false
        override val deWriteAttempted: Boolean get() = true
    }

    /**
     * Transition was rejected prior to write. Guaranteed zero storage modifications.
     */
    sealed class Rejected : MissedTransitionResult() {
        override val isGuaranteedNoWrite: Boolean get() = true
        override val deWriteAttempted: Boolean get() = false

        data class InvalidClassification(val classification: String) : Rejected()
        data class InvalidOccurrenceIdentity(val details: String) : Rejected()
        data class MissingPolicyQualification(val details: String) : Rejected()
        data class UnverifiedPolicyOutcome(val claimedOutcome: OverduePolicyOutcome, val details: String) : Rejected()
        data class PolicyRejected(val policyOutcome: OverduePolicyOutcome, val details: String) : Rejected()
        data class PolicyClassificationMismatch(
            val expectedClassification: String,
            val policyClassification: String,
            val policyOutcome: OverduePolicyOutcome,
            val details: String
        ) : Rejected()
        data class PrematureTrigger(val missedAt: Long, val triggerTime: Long, val details: String) : Rejected()
        data class InsufficientLateness(val latenessMs: Long, val requiredCeilingMs: Long, val details: String) : Rejected()
        data class LatenessInconsistent(val suppliedLatenessMs: Long, val calculatedLatenessMs: Long, val details: String) : Rejected()
        data class ClockArithmeticOverflow(val details: String) : Rejected()
        data class CapacityExhausted(val currentCount: Int, val maxCapacity: Int) : Rejected()
        data class ConflictingState(val details: String) : Rejected()
        data class AlreadyDeliveredInLedger(val deliveryRecordId: String, val details: String) : Rejected()
        data class QueueAbsent(val details: String = "Queue payload is absent") : Rejected()
        data class OccurrenceNotFound(val details: String) : Rejected()
        data class NotEligibleQueueHead(val details: String, val headCandidate: DirectBootQueueItem) : Rejected()
        data class StaleGenerationMismatch(val foundGeneration: Int, val targetGeneration: Int) : Rejected()
        data class OccurrenceIdentityMismatch(val details: String) : Rejected()
        data class QueueStorageCorrupt(val reason: String) : Rejected()
        data class LedgerStorageCorrupt(val reason: String) : Rejected()
        data class MissedStorageCorrupt(val reason: String) : Rejected()
        data class StorageUncertain(val reason: String) : Rejected()
        data class UnsupportedSchema(val domain: String, val found: Int, val expected: Int) : Rejected()
        data class PreCommitFailure(val details: String, val cause: Throwable) : Rejected()
    }

    /**
     * Persistence was attempted (`COMMIT_ATTEMPTED`), and its durable outcome cannot be guaranteed.
     */
    sealed class Uncertain : MissedTransitionResult() {
        override val isGuaranteedNoWrite: Boolean get() = false
        override val deWriteAttempted: Boolean get() = true
        abstract val details: String

        data class CommitFailed(override val details: String) : Uncertain()
        data class CommitException(override val details: String, val cause: Throwable) : Uncertain()
    }
}
