package com.sysadmindoc.alarmclock.directboot.outputauthority

import android.content.Context
import com.sysadmindoc.alarmclock.data.local.AlarmDatabase
import com.sysadmindoc.alarmclock.directboot.CeTakeoverRecord
import com.sysadmindoc.alarmclock.directboot.CeTakeoverStorageState
import com.sysadmindoc.alarmclock.directboot.DirectBootAlarmCache
import com.sysadmindoc.alarmclock.directboot.DirectBootCeTakeoverStore
import com.sysadmindoc.alarmclock.directboot.DirectBootDeliveryLedger
import com.sysadmindoc.alarmclock.directboot.DirectBootDurableOwnerStore
import com.sysadmindoc.alarmclock.directboot.DirectBootEventConsumerEngine
import com.sysadmindoc.alarmclock.directboot.DirectBootFiredWitnessLedger
import com.sysadmindoc.alarmclock.directboot.DirectBootLegacyClaimStore
import com.sysadmindoc.alarmclock.directboot.DirectBootMissedStore
import com.sysadmindoc.alarmclock.directboot.LedgerStorageState
import com.sysadmindoc.alarmclock.directboot.LegacyClaimSource
import com.sysadmindoc.alarmclock.directboot.LegacyClaimState
import com.sysadmindoc.alarmclock.directboot.LegacyClaimStorageState
import com.sysadmindoc.alarmclock.directboot.MissedStoreStorageState
import com.sysadmindoc.alarmclock.directboot.OutputOccurrenceKey
import com.sysadmindoc.alarmclock.directboot.OwnerReadResult
import com.sysadmindoc.alarmclock.directboot.QueueStorageState
import com.sysadmindoc.alarmclock.directboot.WitnessLedgerState

data class VerifiedRetirementEvidence(
    val key: OutputOccurrenceKey,
    val receiptId: String,
    val occurrenceKey: String,
    val recordType: String = "DELIVERED",
    val source: LegacyClaimSource = LegacyClaimSource.ALARM_RECEIVER,
    val payloadHash: String,
    val restoreEpoch: Long,
    val consumerOutcome: String,
    val verifiedAt: Long = System.currentTimeMillis()
)

sealed class RetirementVerificationResult {
    data class Verified(val evidence: VerifiedRetirementEvidence) : RetirementVerificationResult()
    data class Rejected(val reason: String) : RetirementVerificationResult()
}

/**
 * Authoritative verifier for Legacy Claim retirement.
 * Ensures claims are only retired upon cryptographic, durable proof from Room 38
 * using Canonical Payload Hash V2 (ACX_RECEIPT_V2) and verifiable absence from CE/DE V2 stores.
 * Raw boolean retirement without verified evidence is strictly prohibited.
 */
object LegacyRetirementProofVerifier {

    val VALID_CONSUMER_OUTCOMES: Set<String> = setOf("CONSUMED_DELIVERED", "CONSUMED_MISSED")

    @JvmOverloads
    fun verify(
        context: Context,
        database: AlarmDatabase,
        key: OutputOccurrenceKey,
        verifiedCeRecord: CeTakeoverRecord? = null,
        ceContext: Context? = if (context.isDeviceProtectedStorage) null else context
    ): RetirementVerificationResult {
        if (key.refireCount != 0) {
            return RetirementVerificationResult.Rejected(
                "Only primary occurrences (refireCount == 0) can retire via CE proof; found refireCount=${key.refireCount}"
            )
        }

        val deContext = if (context.isDeviceProtectedStorage) context else context.createDeviceProtectedStorageContext()

        // 0. If CE context is available, verify CE Takeover Slot is absent (already released) and store is healthy
        if (ceContext != null) {
            when (val ceState = DirectBootCeTakeoverStore.inspectStorageState(ceContext)) {
                is CeTakeoverStorageState.Absent,
                is CeTakeoverStorageState.ValidEmpty -> Unit
                is CeTakeoverStorageState.ValidWithRecords -> {
                    val stillPending = ceState.records.any {
                        it.alarmId == key.alarmId && it.triggerTime == key.scheduledAt
                    }
                    if (stillPending) {
                        return RetirementVerificationResult.Rejected(
                            "Occurrence is still pending in CE Takeover Store"
                        )
                    }
                }
                is CeTakeoverStorageState.CeLocked ->
                    return RetirementVerificationResult.Rejected("CE Takeover Store locked: ${ceState.reason}")
                is CeTakeoverStorageState.CeContextRejected ->
                    return RetirementVerificationResult.Rejected("CE Takeover context rejected: ${ceState.reason}")
                is CeTakeoverStorageState.Corrupt ->
                    return RetirementVerificationResult.Rejected("CE Takeover Store corrupt: ${ceState.reason}")
                is CeTakeoverStorageState.UnsupportedSchema ->
                    return RetirementVerificationResult.Rejected("CE Takeover Store unsupported schema: ${ceState.foundVersion}")
            }
        }

        // 1. Verify that Claim exists and is currently in TERMINAL state
        val claimState = DirectBootLegacyClaimStore.inspectStorageState(deContext)
        val claimRecord = when (claimState) {
            is LegacyClaimStorageState.ValidWithRecords -> claimState.records.firstOrNull { it.key == key }
            is LegacyClaimStorageState.Corrupt -> return RetirementVerificationResult.Rejected("Claim storage corrupt: ${claimState.reason}")
            is LegacyClaimStorageState.UnsupportedSchema -> return RetirementVerificationResult.Rejected("Unsupported schema: ${claimState.foundVersion}")
            is LegacyClaimStorageState.Absent,
            is LegacyClaimStorageState.ValidEmpty -> null
        } ?: return RetirementVerificationResult.Rejected("Claim record not found for $key")

        if (claimRecord.state != LegacyClaimState.TERMINAL) {
            return RetirementVerificationResult.Rejected("Claim record must be in TERMINAL state to retire; current state is ${claimRecord.state}")
        }

        // 2. Query Room 38 Receipt (exact occurrence/record identity when verifiedCeRecord is provided)
        val receiptDao = database.directBootOccurrenceReceiptDao()
        val triggerMatches = receiptDao.findByAlarmId(key.alarmId).filter { it.triggerTime == key.scheduledAt }
        if (triggerMatches.isEmpty()) {
            return RetirementVerificationResult.Rejected("No Room 38 receipt found for alarmId=${key.alarmId}, scheduledAt=${key.scheduledAt}")
        }
        val receipt = if (verifiedCeRecord != null) {
            val exactMatches = triggerMatches.filter {
                it.occurrenceKey == verifiedCeRecord.occurrenceKey &&
                    it.sourceRecordId == verifiedCeRecord.sourceRecordId &&
                    it.recordType == verifiedCeRecord.recordType &&
                    it.alarmId == verifiedCeRecord.alarmId &&
                    it.triggerTime == verifiedCeRecord.triggerTime &&
                    it.generation == verifiedCeRecord.generation &&
                    it.instanceToken == verifiedCeRecord.instanceToken &&
                    it.restoreEpoch == verifiedCeRecord.restoreEpoch
            }
            if (exactMatches.size != 1) {
                return RetirementVerificationResult.Rejected(
                    "Room 38 receipt identity mismatch or ambiguity for CE record ${verifiedCeRecord.occurrenceKey} (matches=${exactMatches.size})"
                )
            }
            exactMatches.single()
        } else {
            if (triggerMatches.size != 1) {
                return RetirementVerificationResult.Rejected(
                    "Ambiguous Room 38 receipts (${triggerMatches.size}) without verifiedCeRecord for $key"
                )
            }
            triggerMatches.single()
        }

        // 3. Verify Restore Epoch (> 0L)
        if (receipt.restoreEpoch <= 0L) {
            return RetirementVerificationResult.Rejected("Invalid restoreEpoch: ${receipt.restoreEpoch} (must be > 0L)")
        }

        // 4. Verify Consumer Outcome
        if (receipt.consumerOutcome !in VALID_CONSUMER_OUTCOMES) {
            return RetirementVerificationResult.Rejected("Ineligible consumer outcome '${receipt.consumerOutcome}'; allowed: $VALID_CONSUMER_OUTCOMES")
        }

        // 5. Verify Absence & Health of ALL DE V2 Stores (fail-closed on Corrupt/Unsupported/Uncertain)
        val cacheState = DirectBootAlarmCache.inspectQueueStorageState(deContext)
        when (cacheState) {
            is QueueStorageState.Absent,
            is QueueStorageState.ValidEmpty -> Unit
            is QueueStorageState.ValidWithItems -> {
                if (cacheState.items.any { it.alarmId == key.alarmId && it.triggerTime == key.scheduledAt }) {
                    return RetirementVerificationResult.Rejected("Occurrence is still present in V2 candidate queue")
                }
            }
            is QueueStorageState.Corrupt ->
                return RetirementVerificationResult.Rejected("V2 queue corrupt: ${cacheState.reason}")
            is QueueStorageState.UnsupportedSchema ->
                return RetirementVerificationResult.Rejected("V2 queue unsupported schema: ${cacheState.foundVersion}")
        }

        when (val ownerResult = DirectBootDurableOwnerStore.read(deContext)) {
            is OwnerReadResult.Absent -> Unit
            is OwnerReadResult.Success -> {
                val owner = ownerResult.owner
                val inClaim = owner.handoverClaim?.let { it.alarmId == key.alarmId && it.triggerTime == key.scheduledAt } ?: false
                val inSession = owner.activeSession?.let { it.alarmId == key.alarmId && it.triggerTime == key.scheduledAt } ?: false
                if (inClaim || inSession) {
                    return RetirementVerificationResult.Rejected("Occurrence is still active in V2 Durable Owner Store")
                }
            }
            is OwnerReadResult.Corrupt ->
                return RetirementVerificationResult.Rejected("Durable owner storage corrupt: ${ownerResult.reason}")
            is OwnerReadResult.UnsupportedSchema ->
                return RetirementVerificationResult.Rejected("Durable owner unsupported schema: ${ownerResult.foundVersion}")
        }

        val ledgerState = DirectBootDeliveryLedger.readUnacknowledgedRecords(deContext)
        when (ledgerState) {
            is LedgerStorageState.Absent,
            is LedgerStorageState.ValidEmpty -> Unit
            is LedgerStorageState.ValidWithRecords -> {
                if (ledgerState.records.any {
                        it.alarmId == key.alarmId && it.triggerTime == key.scheduledAt && it.provenance != "LEGACY_DELIVERY"
                    }
                ) {
                    return RetirementVerificationResult.Rejected("Occurrence still has unacknowledged V2 delivery record")
                }
            }
            is LedgerStorageState.Corrupt ->
                return RetirementVerificationResult.Rejected("V2 delivery ledger corrupt: ${ledgerState.reason}")
            is LedgerStorageState.UnsupportedSchema ->
                return RetirementVerificationResult.Rejected("V2 delivery ledger unsupported schema: ${ledgerState.foundVersion}")
        }

        val missedState = DirectBootMissedStore.readUnacknowledgedRecords(deContext)
        when (missedState) {
            is MissedStoreStorageState.Absent,
            is MissedStoreStorageState.ValidEmpty -> Unit
            is MissedStoreStorageState.ValidWithRecords -> {
                if (missedState.records.any { it.alarmId == key.alarmId && it.triggerTime == key.scheduledAt }) {
                    return RetirementVerificationResult.Rejected("Occurrence still has unacknowledged V2 missed record")
                }
            }
            is MissedStoreStorageState.Corrupt ->
                return RetirementVerificationResult.Rejected("V2 missed store corrupt: ${missedState.reason}")
            is MissedStoreStorageState.UnsupportedSchema ->
                return RetirementVerificationResult.Rejected("V2 missed store unsupported schema: ${missedState.foundVersion}")
            is MissedStoreStorageState.PersistenceUncertain ->
                return RetirementVerificationResult.Rejected("V2 missed store uncertain: ${missedState.reason}")
        }

        val witnessState = DirectBootFiredWitnessLedger.readWitnesses(deContext)
        when (witnessState) {
            is WitnessLedgerState.Absent,
            is WitnessLedgerState.ValidEmpty -> Unit
            is WitnessLedgerState.ValidWithRecords -> {
                if (witnessState.records.any { it.alarmId == key.alarmId && it.triggerTime == key.scheduledAt }) {
                    return RetirementVerificationResult.Rejected("Occurrence still has unacknowledged V2 witness record")
                }
            }
            is WitnessLedgerState.Corrupt ->
                return RetirementVerificationResult.Rejected("V2 witness ledger corrupt: ${witnessState.reason}")
            is WitnessLedgerState.UnsupportedSchema ->
                return RetirementVerificationResult.Rejected("V2 witness ledger unsupported schema: ${witnessState.foundVersion}")
        }

        // 6. Verify Length-Prefixed Canonical Payload Hash V2 (ACX_RECEIPT_V2)
        val metadataString = when {
            verifiedCeRecord is CeTakeoverRecord.Delivered -> verifiedCeRecord.provenance
            verifiedCeRecord is CeTakeoverRecord.Missed -> verifiedCeRecord.classification
            receipt.recordType == "DELIVERED" -> {
                (ledgerState as? LedgerStorageState.ValidWithRecords)
                    ?.records
                    ?.find { it.recordId == receipt.sourceRecordId }
                    ?.provenance
                    .orEmpty()
            }
            receipt.recordType == "MISSED" -> {
                (missedState as? MissedStoreStorageState.ValidWithRecords)
                    ?.records
                    ?.find { it.recordId == receipt.sourceRecordId }
                    ?.classification
                    .orEmpty()
            }
            else -> ""
        }

        val expectedHashV2 = DirectBootEventConsumerEngine.calculateCanonicalPayloadHashV2(
            sourceRecordId = receipt.sourceRecordId,
            recordType = receipt.recordType,
            alarmId = receipt.alarmId,
            triggerTime = receipt.triggerTime,
            eventTimestamp = receipt.completedAt,
            generation = receipt.generation,
            instanceToken = receipt.instanceToken,
            restoreEpoch = receipt.restoreEpoch,
            metadataString = metadataString
        )
        if (receipt.sourcePayloadHash != expectedHashV2) {
            return RetirementVerificationResult.Rejected(
                "Payload hash mismatch (Canonical Payload Hash V2 mismatch): found ${receipt.sourcePayloadHash}, expected $expectedHashV2"
            )
        }

        return RetirementVerificationResult.Verified(
            VerifiedRetirementEvidence(
                key = key,
                receiptId = receipt.sourceRecordId,
                occurrenceKey = receipt.occurrenceKey,
                recordType = receipt.recordType,
                source = claimRecord.source,
                payloadHash = receipt.sourcePayloadHash,
                restoreEpoch = receipt.restoreEpoch,
                consumerOutcome = receipt.consumerOutcome
            )
        )
    }
}
