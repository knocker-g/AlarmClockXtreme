package com.sysadmindoc.alarmclock.directboot.outputauthority

import android.content.Context
import com.sysadmindoc.alarmclock.data.local.AlarmDatabase
import com.sysadmindoc.alarmclock.data.local.entity.DirectBootOccurrenceReceipt
import com.sysadmindoc.alarmclock.directboot.DirectBootAlarmCache
import com.sysadmindoc.alarmclock.directboot.DirectBootDurableOwnerStore
import com.sysadmindoc.alarmclock.directboot.DirectBootLegacyClaimStore
import com.sysadmindoc.alarmclock.directboot.LegacyClaimState
import com.sysadmindoc.alarmclock.directboot.LegacyClaimStorageState
import com.sysadmindoc.alarmclock.directboot.OutputOccurrenceKey
import com.sysadmindoc.alarmclock.directboot.OwnerReadResult
import com.sysadmindoc.alarmclock.directboot.QueueStorageState

data class VerifiedRetirementEvidence(
    val key: OutputOccurrenceKey,
    val receiptId: String,
    val occurrenceKey: String,
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
 * and verifiable absence from DE V2 queues/owners.
 * Raw boolean retirement without verified evidence is strictly prohibited.
 */
object LegacyRetirementProofVerifier {

    val VALID_CONSUMER_OUTCOMES: Set<String> = setOf("CONSUMED_DELIVERED", "CONSUMED_MISSED")

    fun verify(
        context: Context,
        database: AlarmDatabase,
        key: OutputOccurrenceKey
    ): RetirementVerificationResult {
        val deContext = if (context.isDeviceProtectedStorage) context else context.createDeviceProtectedStorageContext()

        // 1. Verify that Claim exists and is currently in TERMINAL state
        val claimState = DirectBootLegacyClaimStore.inspectStorageState(deContext)
        val claimRecord = when (claimState) {
            is LegacyClaimStorageState.ValidWithRecords -> claimState.records.firstOrNull { it.key == key }
            is LegacyClaimStorageState.Corrupt -> return RetirementVerificationResult.Rejected("Claim storage corrupt: ${claimState.reason}")
            is LegacyClaimStorageState.UnsupportedSchema -> return RetirementVerificationResult.Rejected("Unsupported schema: ${claimState.foundVersion}")
            else -> null
        } ?: return RetirementVerificationResult.Rejected("Claim record not found for $key")

        if (claimRecord.state != LegacyClaimState.TERMINAL) {
            return RetirementVerificationResult.Rejected("Claim record must be in TERMINAL state to retire; current state is ${claimRecord.state}")
        }

        // 2. Query Room 38 Receipt
        val receiptDao = database.directBootOccurrenceReceiptDao()
        val matchingReceipts = receiptDao.findByAlarmId(key.alarmId).filter { it.triggerTime == key.scheduledAt }
        if (matchingReceipts.isEmpty()) {
            return RetirementVerificationResult.Rejected("No Room 38 receipt found for alarmId=${key.alarmId}, scheduledAt=${key.scheduledAt}")
        }
        val receipt = matchingReceipts.first()

        // 3. Verify Restore Epoch (> 0L)
        if (receipt.restoreEpoch <= 0L) {
            return RetirementVerificationResult.Rejected("Invalid restoreEpoch: ${receipt.restoreEpoch} (must be > 0L)")
        }

        // 4. Verify Consumer Outcome
        if (receipt.consumerOutcome !in VALID_CONSUMER_OUTCOMES) {
            return RetirementVerificationResult.Rejected("Ineligible consumer outcome '${receipt.consumerOutcome}'; allowed: $VALID_CONSUMER_OUTCOMES")
        }

        // 5. Verify Canonical Payload Hash
        val expectedHash = DirectBootOccurrenceReceipt.calculateCanonicalPayloadHash(
            sourceRecordId = receipt.sourceRecordId,
            recordType = receipt.recordType,
            alarmId = receipt.alarmId,
            triggerTime = receipt.triggerTime,
            generation = receipt.generation,
            instanceToken = receipt.instanceToken,
            restoreEpoch = receipt.restoreEpoch
        )
        if (receipt.sourcePayloadHash != expectedHash) {
            return RetirementVerificationResult.Rejected("Payload hash mismatch: found ${receipt.sourcePayloadHash}, expected $expectedHash")
        }

        // 6. Verify Absence from V2 Queue (DirectBootAlarmCache)
        val cacheState = DirectBootAlarmCache.inspectQueueStorageState(deContext)
        val inCache = when (cacheState) {
            is QueueStorageState.ValidWithItems ->
                cacheState.items.any { it.alarmId == key.alarmId && it.triggerTime == key.scheduledAt }
            is QueueStorageState.Corrupt ->
                return RetirementVerificationResult.Rejected("V2 queue corrupt: ${cacheState.reason}")
            else -> false
        }
        if (inCache) {
            return RetirementVerificationResult.Rejected("Occurrence is still present in V2 candidate queue")
        }

        // 7. Verify Absence from V2 Durable Owner (DirectBootDurableOwnerStore)
        val ownerResult = DirectBootDurableOwnerStore.read(deContext)
        val inOwner = when (ownerResult) {
            is OwnerReadResult.Success -> {
                val owner = ownerResult.owner
                val inClaim = owner.handoverClaim?.let { it.alarmId == key.alarmId && it.triggerTime == key.scheduledAt } ?: false
                val inSession = owner.activeSession?.let { it.alarmId == key.alarmId && it.triggerTime == key.scheduledAt } ?: false
                inClaim || inSession
            }
            is OwnerReadResult.Corrupt ->
                return RetirementVerificationResult.Rejected("Durable owner storage corrupt: ${ownerResult.reason}")
            is OwnerReadResult.UnsupportedSchema ->
                return RetirementVerificationResult.Rejected("Durable owner unsupported schema: ${ownerResult.foundVersion}")
            else -> false
        }
        if (inOwner) {
            return RetirementVerificationResult.Rejected("Occurrence is still active in V2 Durable Owner Store")
        }

        return RetirementVerificationResult.Verified(
            VerifiedRetirementEvidence(
                key = key,
                receiptId = receipt.sourceRecordId,
                occurrenceKey = receipt.occurrenceKey,
                payloadHash = receipt.sourcePayloadHash,
                restoreEpoch = receipt.restoreEpoch,
                consumerOutcome = receipt.consumerOutcome
            )
        )
    }
}
