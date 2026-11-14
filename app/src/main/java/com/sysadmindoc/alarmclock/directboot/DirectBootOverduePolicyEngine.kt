package com.sysadmindoc.alarmclock.directboot

/**
 * Trigger provenance classifications for evaluating alarm occurrence execution context.
 */
sealed interface OverdueTriggerProvenance {
    data object NormalOsDispatch : OverdueTriggerProvenance
    data class QualifiedRecovery(val occurrenceScheduledRealtimeMs: Long) : OverdueTriggerProvenance
    data object UnanchoredRecovery : OverdueTriggerProvenance
    data object ColdBoot : OverdueTriggerProvenance
    data object CredentialUnlock : OverdueTriggerProvenance
    data object TimeMutation : OverdueTriggerProvenance
    data object Unknown : OverdueTriggerProvenance
}

/**
 * Typed outcome hierarchy for [DirectBootOverduePolicyEngine.evaluate].
 *
 * Invariant: [audioAuthorized] is strictly false for ALL outcomes.
 * The policy engine is a pure classifier; delivery and audio authorization
 * remain strictly gated by the delivery adapter and transition engine.
 */
sealed interface OverduePolicyOutcome {
    val audioAuthorized: Boolean get() = false
    val details: String

    data class NormalDispatchCandidate(
        val identity: DirectBootV2NormalIntentIdentity,
        val latenessMs: Long,
        override val details: String = "Normal OS dispatch within bounded window"
    ) : OverduePolicyOutcome

    data class RecoveryDeliveryCandidate(
        val identity: DirectBootV2NormalIntentIdentity,
        val latenessMs: Long,
        val monotonicAgeMs: Long,
        override val details: String = "Qualified recovery within bounded 300,000ms window"
    ) : OverduePolicyOutcome

    data class OverdueMissedCandidate(
        val identity: DirectBootV2NormalIntentIdentity,
        val latenessMs: Long,
        override val details: String = "Occurrence lateness exceeds 300,000ms ceiling"
    ) : OverduePolicyOutcome

    data class ColdBootMissedCandidate(
        val identity: DirectBootV2NormalIntentIdentity,
        val latenessMs: Long,
        override val details: String = "Occurrence overdue on cold boot"
    ) : OverduePolicyOutcome

    data class SupersededReject(
        val reason: String,
        override val details: String = reason
    ) : OverduePolicyOutcome

    data class DuplicateReject(
        val recordId: String,
        override val details: String = "Occurrence recordId '$recordId' already recorded in durable state"
    ) : OverduePolicyOutcome

    data class CorruptBlock(
        val reason: String,
        override val details: String = reason
    ) : OverduePolicyOutcome

    data class UnknownProvenanceBlock(
        val provenance: OverdueTriggerProvenance,
        override val details: String = "Provenance $provenance is untrusted or unverified for delivery"
    ) : OverduePolicyOutcome

    data class UnknownClockBlock(
        val reason: String,
        override val details: String = reason
    ) : OverduePolicyOutcome

    data class PrematureBlock(
        val nowEpochMs: Long,
        val triggerTimeMs: Long,
        override val details: String = "Premature evaluation: nowEpochMs $nowEpochMs < triggerTime $triggerTimeMs"
    ) : OverduePolicyOutcome

    data class StorageUncertainBlock(
        val reason: String,
        override val details: String = reason
    ) : OverduePolicyOutcome
}

/**
 * Typed Missed Storage State hierarchy.
 *
 * DirectBoot overdue evaluation requires explicit verification of missed records.
 * Unknown or unverified missed state fails closed with [OverduePolicyOutcome.StorageUncertainBlock]
 * or [OverduePolicyOutcome.CorruptBlock].
 */
sealed interface MissedStorageState {
    data object VerifiedEmpty : MissedStorageState
    data class VerifiedRecords(val recordIds: Set<String>) : MissedStorageState
    data class Corrupt(val reason: String) : MissedStorageState
    data class UnsupportedSchema(val foundVersion: Int, val expectedVersion: Int = 1) : MissedStorageState
    data class UnknownUncertain(val reason: String) : MissedStorageState
}

/**
 * Qualified fact wrapper for execution context observations.
 *
 * Callers must explicitly provide verified observations. Missing or uncertain facts
 * fail closed rather than defaulting to optimistic negative assumptions.
 */
sealed interface QualifiedFact<out T> {
    data class Verified<T>(val value: T) : QualifiedFact<T>
    data class Uncertain(val reason: String) : QualifiedFact<Nothing>
}

/**
 * Execution context facts required for overdue evaluation.
 *
 * Prohibits unverified optimistic defaults (`= false`).
 */
data class OverdueQualifiedFacts(
    val rebootStatus: QualifiedFact<Boolean>,
    val clockRollbackStatus: QualifiedFact<Boolean>,
    val cancellationStatus: QualifiedFact<Boolean>
) {
    companion object {
        fun of(
            rebootDetected: Boolean,
            clockRollbackDetected: Boolean,
            isCancelled: Boolean
        ): OverdueQualifiedFacts = OverdueQualifiedFacts(
            rebootStatus = QualifiedFact.Verified(rebootDetected),
            clockRollbackStatus = QualifiedFact.Verified(clockRollbackDetected),
            cancellationStatus = QualifiedFact.Verified(isCancelled)
        )
    }
}

/**
 * Pure, side-effect-free overdue policy classifier for KNO-75 Phase D3-C2-P3A.
 *
 * Implements canonical overdue evaluation according to owner-approved P3 policy:
 * - Pure classification only: zero delivery, zero storage mutation, zero AlarmManager calls.
 * - Strict 300,000ms ceiling for normal and recovery delivery candidates.
 * - Clock safety: fail-closed on rollback, missing monotonic anchor, or contradictory age.
 * - Cold boot: overdue (now >= T) -> ColdBootMissedCandidate; future (now < T) -> UnknownProvenanceBlock.
 * - Audio authorization: strictly false across all outcomes.
 */
object DirectBootOverduePolicyEngine {
    const val MAX_RECOVERY_WINDOW_MS = 300_000L

    fun computeMissedRecordId(
        alarmId: Long,
        triggerTime: Long,
        generation: Int,
        instanceToken: String
    ): String = "missed_normal_${alarmId}_${generation}_${instanceToken}_$triggerTime"

    fun evaluate(
        candidate: DirectBootV2NormalIntentIdentity,
        queueState: QueueStorageState,
        ledgerState: LedgerStorageState,
        missedState: MissedStorageState,
        facts: OverdueQualifiedFacts,
        provenance: OverdueTriggerProvenance,
        nowEpochMs: Long,
        currentElapsedRealtimeMs: Long? = null
    ): OverduePolicyOutcome {
        // 1. Candidate identity & Epoch validation
        if (candidate.triggerTime <= 0L) {
            return OverduePolicyOutcome.CorruptBlock("Invalid candidate triggerTime: ${candidate.triggerTime}")
        }
        if (nowEpochMs <= 0L) {
            return OverduePolicyOutcome.UnknownClockBlock("Invalid non-positive nowEpochMs: $nowEpochMs")
        }

        // 2. Storage integrity: fail-closed on corrupt or unsupported schema
        val queueItems: List<DirectBootQueueItem> = when (queueState) {
            is QueueStorageState.Absent -> {
                return OverduePolicyOutcome.StorageUncertainBlock("Queue storage is absent")
            }
            is QueueStorageState.ValidEmpty -> {
                return OverduePolicyOutcome.SupersededReject("Queue is empty, candidate has no active queue item")
            }
            is QueueStorageState.ValidWithItems -> queueState.items
            is QueueStorageState.Corrupt -> {
                return OverduePolicyOutcome.CorruptBlock("Queue storage corrupt: ${queueState.reason}")
            }
            is QueueStorageState.UnsupportedSchema -> {
                return OverduePolicyOutcome.CorruptBlock(
                    "Queue storage unsupported schema: found ${queueState.foundVersion}, expected ${queueState.expectedVersion}"
                )
            }
        }

        val ledgerRecords: List<DirectBootDeliveryRecord> = when (ledgerState) {
            is LedgerStorageState.Absent -> emptyList()
            is LedgerStorageState.ValidEmpty -> emptyList()
            is LedgerStorageState.ValidWithRecords -> ledgerState.records
            is LedgerStorageState.Corrupt -> {
                return OverduePolicyOutcome.CorruptBlock("Ledger storage corrupt: ${ledgerState.reason}")
            }
            is LedgerStorageState.UnsupportedSchema -> {
                return OverduePolicyOutcome.CorruptBlock(
                    "Ledger storage unsupported schema: found ${ledgerState.foundVersion}, expected ${ledgerState.expectedVersion}"
                )
            }
        }

        val missedRecordIds: Set<String> = when (missedState) {
            is MissedStorageState.VerifiedEmpty -> emptySet()
            is MissedStorageState.VerifiedRecords -> missedState.recordIds
            is MissedStorageState.Corrupt -> {
                return OverduePolicyOutcome.CorruptBlock("Missed storage corrupt: ${missedState.reason}")
            }
            is MissedStorageState.UnsupportedSchema -> {
                return OverduePolicyOutcome.CorruptBlock(
                    "Missed storage unsupported schema: found ${missedState.foundVersion}, expected ${missedState.expectedVersion}"
                )
            }
            is MissedStorageState.UnknownUncertain -> {
                return OverduePolicyOutcome.StorageUncertainBlock("Missed storage uncertain: ${missedState.reason}")
            }
        }

        // 3. Cancellation verification: fail closed on uncertainty
        val isCancelled = when (val c = facts.cancellationStatus) {
            is QualifiedFact.Verified -> c.value
            is QualifiedFact.Uncertain -> {
                return OverduePolicyOutcome.StorageUncertainBlock("Cancellation status is uncertain: ${c.reason}")
            }
        }
        if (isCancelled) {
            return OverduePolicyOutcome.SupersededReject("Alarm occurrence is cancelled")
        }

        // 4. Queue Head matching, Kind validation, and Lineage supersedence check
        val head = queueItems.firstOrNull()
            ?: return OverduePolicyOutcome.SupersededReject("Queue is empty, candidate has no active queue item")

        if (head.triggerTime <= 0L) {
            return OverduePolicyOutcome.CorruptBlock("Corrupt queue item triggerTime: ${head.triggerTime}")
        }
        if (head.kind != DirectBootQueueItem.KIND_NORMAL) {
            return OverduePolicyOutcome.SupersededReject("Queue head kind '${head.kind}' is not KIND_NORMAL")
        }
        if (candidate.alarmId != head.alarmId) {
            return OverduePolicyOutcome.SupersededReject(
                "Candidate alarmId ${candidate.alarmId} does not match queue head alarmId ${head.alarmId}"
            )
        }
        if (candidate.generation < head.generation) {
            return OverduePolicyOutcome.SupersededReject(
                "Candidate generation ${candidate.generation} is superseded by queue head generation ${head.generation}"
            )
        }
        if (candidate.generation > head.generation) {
            return OverduePolicyOutcome.SupersededReject(
                "Candidate generation ${candidate.generation} is ahead of queue head generation ${head.generation}"
            )
        }
        if (candidate.instanceToken != head.instanceToken) {
            return OverduePolicyOutcome.SupersededReject(
                "Candidate instanceToken ${candidate.instanceToken} does not match queue head instanceToken ${head.instanceToken}"
            )
        }
        if (candidate.triggerTime != head.triggerTime) {
            return OverduePolicyOutcome.SupersededReject(
                "Candidate triggerTime ${candidate.triggerTime} does not match queue head triggerTime ${head.triggerTime}"
            )
        }

        // 5. Exact Duplicate checks (Ledger / Missed) - No substring / contains() matching!
        val expectedDeliveryRecordId = DirectBootDeliveryRecord.computeRecordId(
            kind = DirectBootDeliveryRecord.KIND_NORMAL,
            alarmId = candidate.alarmId,
            triggerTime = candidate.triggerTime,
            generation = candidate.generation,
            instanceToken = candidate.instanceToken
        )
        if (ledgerRecords.any { it.recordId == expectedDeliveryRecordId }) {
            return OverduePolicyOutcome.DuplicateReject(expectedDeliveryRecordId)
        }

        val expectedMissedRecordId = computeMissedRecordId(
            alarmId = candidate.alarmId,
            triggerTime = candidate.triggerTime,
            generation = candidate.generation,
            instanceToken = candidate.instanceToken
        )
        val matchingMissedId = missedRecordIds.firstOrNull {
            it == expectedMissedRecordId || it == expectedDeliveryRecordId
        }
        if (matchingMissedId != null) {
            return OverduePolicyOutcome.DuplicateReject(matchingMissedId)
        }

        // 6. Clock Rollback & Boot status verification: fail closed on uncertainty
        val clockRollbackDetected = when (val cr = facts.clockRollbackStatus) {
            is QualifiedFact.Verified -> cr.value
            is QualifiedFact.Uncertain -> {
                return OverduePolicyOutcome.UnknownClockBlock("Clock rollback status is uncertain: ${cr.reason}")
            }
        }
        if (clockRollbackDetected) {
            return OverduePolicyOutcome.UnknownClockBlock("Clock rollback detected")
        }

        val rebootDetected = when (val r = facts.rebootStatus) {
            is QualifiedFact.Verified -> r.value
            is QualifiedFact.Uncertain -> {
                return OverduePolicyOutcome.UnknownProvenanceBlock(
                    provenance,
                    "Reboot status is uncertain: ${r.reason}"
                )
            }
        }

        // 7. Contradictory boot & provenance safety
        // QualifiedRecovery is caller-supplied evidence, not proof of actual boot
        // identity or legitimate scheduled monotonic anchor.
        if (rebootDetected && provenance is OverdueTriggerProvenance.QualifiedRecovery) {
            return OverduePolicyOutcome.UnknownProvenanceBlock(
                provenance,
                "Cross-boot QualifiedRecovery rejected: monotonic anchor from prior boot cannot qualify same-boot recovery"
            )
        }
        if (rebootDetected && provenance is OverdueTriggerProvenance.NormalOsDispatch) {
            return OverduePolicyOutcome.UnknownProvenanceBlock(
                provenance,
                "NormalOsDispatch intent received after reboot was detected"
            )
        }
        if (!rebootDetected && provenance is OverdueTriggerProvenance.ColdBoot) {
            return OverduePolicyOutcome.UnknownProvenanceBlock(
                provenance,
                "ColdBoot provenance claimed but reboot was not detected"
            )
        }
        if (provenance is OverdueTriggerProvenance.TimeMutation ||
            provenance is OverdueTriggerProvenance.CredentialUnlock ||
            provenance is OverdueTriggerProvenance.Unknown) {
            return OverduePolicyOutcome.UnknownProvenanceBlock(provenance)
        }

        // 8. Monotonic clock safety for Recovery
        if (currentElapsedRealtimeMs != null && currentElapsedRealtimeMs < 0L) {
            return OverduePolicyOutcome.UnknownClockBlock(
                "Invalid negative currentElapsedRealtimeMs: $currentElapsedRealtimeMs"
            )
        }
        if (provenance is OverdueTriggerProvenance.UnanchoredRecovery) {
            return OverduePolicyOutcome.UnknownClockBlock("Unanchored recovery: monotonic anchor is absent")
        }

        val monotonicAgeMs: Long? = if (provenance is OverdueTriggerProvenance.QualifiedRecovery) {
            if (provenance.occurrenceScheduledRealtimeMs < 0L) {
                return OverduePolicyOutcome.UnknownClockBlock(
                    "Invalid negative scheduledRealtime: ${provenance.occurrenceScheduledRealtimeMs}"
                )
            }
            if (currentElapsedRealtimeMs == null) {
                return OverduePolicyOutcome.UnknownClockBlock("Qualified recovery missing currentElapsedRealtimeMs")
            }
            if (currentElapsedRealtimeMs < provenance.occurrenceScheduledRealtimeMs) {
                return OverduePolicyOutcome.UnknownClockBlock(
                    "Contradictory monotonic time: currentElapsedRealtimeMs ($currentElapsedRealtimeMs) < scheduled (${provenance.occurrenceScheduledRealtimeMs})"
                )
            }
            try {
                Math.subtractExact(currentElapsedRealtimeMs, provenance.occurrenceScheduledRealtimeMs)
            } catch (e: ArithmeticException) {
                return OverduePolicyOutcome.UnknownClockBlock("Monotonic age subtraction overflow")
            }
        } else {
            null
        }

        // 9. Cold boot handling
        if (provenance is OverdueTriggerProvenance.ColdBoot) {
            if (nowEpochMs < candidate.triggerTime) {
                return OverduePolicyOutcome.UnknownProvenanceBlock(
                    provenance,
                    "Cold boot future occurrence requires registration reconciliation, not immediate delivery"
                )
            }
            val latenessMs = try {
                Math.subtractExact(nowEpochMs, candidate.triggerTime)
            } catch (e: ArithmeticException) {
                return OverduePolicyOutcome.UnknownClockBlock("Lateness subtraction overflow")
            }
            return OverduePolicyOutcome.ColdBootMissedCandidate(candidate, latenessMs)
        }

        // 10. Premature check
        if (nowEpochMs < candidate.triggerTime) {
            return OverduePolicyOutcome.PrematureBlock(
                nowEpochMs = nowEpochMs,
                triggerTimeMs = candidate.triggerTime
            )
        }

        // 11. Bounded lateness window evaluation with overflow-checked arithmetic
        val latenessMs = try {
            Math.subtractExact(nowEpochMs, candidate.triggerTime)
        } catch (e: ArithmeticException) {
            return OverduePolicyOutcome.UnknownClockBlock("Lateness subtraction overflow")
        }

        return when (provenance) {
            is OverdueTriggerProvenance.NormalOsDispatch -> {
                if (latenessMs <= MAX_RECOVERY_WINDOW_MS) {
                    OverduePolicyOutcome.NormalDispatchCandidate(candidate, latenessMs)
                } else {
                    OverduePolicyOutcome.OverdueMissedCandidate(candidate, latenessMs)
                }
            }
            is OverdueTriggerProvenance.QualifiedRecovery -> {
                val age = monotonicAgeMs!!
                if (latenessMs <= MAX_RECOVERY_WINDOW_MS && age <= MAX_RECOVERY_WINDOW_MS) {
                    OverduePolicyOutcome.RecoveryDeliveryCandidate(candidate, latenessMs, age)
                } else {
                    OverduePolicyOutcome.OverdueMissedCandidate(candidate, latenessMs)
                }
            }
            else -> {
                OverduePolicyOutcome.UnknownProvenanceBlock(provenance)
            }
        }
    }
}
