package com.sysadmindoc.alarmclock.directboot

import android.content.Context
import android.util.Log
import androidx.room.withTransaction
import com.sysadmindoc.alarmclock.data.local.AlarmDatabase
import com.sysadmindoc.alarmclock.data.local.entity.DirectBootOccurrenceReceipt
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/**
 * Post-Unlock Direct Boot Event Consumer Engine & Room 38 Receipt Transaction Coordinator
 * for KNO-75 (Phase D3-C2-P3D-P3-BATCH-B / Slice E2 & B1c-S4).
 *
 * Invariants & Boundaries:
 * - Room 38 Schema: Uses accepted Room 38 [DirectBootOccurrenceReceipt] entity & DAO without schema modifications.
 * - Lock Isolation: Executes Room transactions under SQLite WAL transaction locks with ZERO DE or CE storage locks held.
 * - Missed Policy: Missed takeover records use RECEIPT-ONLY strategy. Inserts [DirectBootOccurrenceReceipt]
 *   with consumerOutcome = "CONSUMED_MISSED" without inserting [AlarmEvent] rows or creating sessions/tombstones.
 * - Legacy v1 Fence: Legacy v1 records (restoreEpoch = 0L) are marked RESTORE_EPOCH_CONFLICT and NEVER enter retirement.
 * - Strict Consumer Retirement Order:
 *   1. Room 38 Receipt Commit & Independent Readback -> [VerifiedRoomReceiptProof]
 *   2. Qualified DE ACK via [DirectBootCeHandoffCoordinator]
 *   3. Witness ACK via [DirectBootFiredWitnessLedger.acknowledgeWitness]
 *   4. CE Slot Release via [DirectBootCeTakeoverStore.releaseSlotWithResult]
 *   Any failure in Steps 2-4 aborts subsequent retirement steps for that record and returns [ConsumerExecutionResult.IncompleteRetirement].
 */
object DirectBootEventConsumerEngine {

    private const val TAG = "DirectBootEventConsumerEngine"
    private val consumerMutex = Mutex()
    private val VALID_CONSUMED_OUTCOMES: Set<String> = setOf("CONSUMED_DELIVERED", "CONSUMED_MISSED")

    data class RecordRetirementFailure(
        val occurrenceKey: String,
        val stage: String,
        val details: String
    )

    sealed class ConsumerExecutionResult {
        data class Success(val processedCount: Int, val receiptsCommitted: Int) : ConsumerExecutionResult()
        data class IncompleteRetirement(
            val processedCount: Int,
            val receiptsCommitted: Int,
            val deAcknowledgedCount: Int,
            val witnessAcknowledgedCount: Int,
            val ceSlotsReleasedCount: Int,
            val failures: List<RecordRetirementFailure>
        ) : ConsumerExecutionResult()
        data class Rejected(val reason: String) : ConsumerExecutionResult()
        object NoPendingRecords : ConsumerExecutionResult()
    }

    sealed class RecordConsumptionOutcome {
        data class ConsumedDelivered(val receipt: DirectBootOccurrenceReceipt) : RecordConsumptionOutcome()
        data class ConsumedMissed(val receipt: DirectBootOccurrenceReceipt) : RecordConsumptionOutcome()
        data class AlreadyConsumed(val existingReceipt: DirectBootOccurrenceReceipt) : RecordConsumptionOutcome()
        data class RestoreEpochConflict(val alarmId: Long, val instanceToken: String) : RecordConsumptionOutcome()
        data class PayloadConflict(val occurrenceKey: String) : RecordConsumptionOutcome()
        data class ReadbackFailed(val occurrenceKey: String) : RecordConsumptionOutcome()
    }

    private data class VerifiedRoomReceiptProof(
        val ceRecord: CeTakeoverRecord,
        val receipt: DirectBootOccurrenceReceipt
    )

    /**
     * Calculates Length-Prefixed Canonical Payload Hash V2 (ACX_RECEIPT_V2) for Room 38 receipts.
     */
    fun calculateCanonicalPayloadHashV2(
        sourceRecordId: String,
        recordType: String,
        alarmId: Long,
        triggerTime: Long,
        eventTimestamp: Long,
        generation: Int,
        instanceToken: String,
        restoreEpoch: Long,
        metadataString: String
    ): String {
        fun byteLen(s: String): Int = s.toByteArray(StandardCharsets.UTF_8).size

        val canonicalString = "ACX_RECEIPT_V2;" +
            "R_ID=${byteLen(sourceRecordId)}:$sourceRecordId;" +
            "TYPE=${byteLen(recordType)}:$recordType;" +
            "ALARM_ID=$alarmId;" +
            "TRIG=$triggerTime;" +
            "EVT_TS=$eventTimestamp;" +
            "GEN=$generation;" +
            "TOKEN=${byteLen(instanceToken)}:$instanceToken;" +
            "EPOCH=$restoreEpoch;" +
            "META=${byteLen(metadataString)}:$metadataString"

        val digest = MessageDigest.getInstance("SHA-256")
            .digest(canonicalString.toByteArray(StandardCharsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    /**
     * Main entrypoint to process all unconsumed CE takeover records after device unlock.
     */
    suspend fun consumePendingTakeoverRecords(
        context: Context,
        ceContext: Context,
        database: AlarmDatabase
    ): ConsumerExecutionResult {
        return consumerMutex.withLock {
            consumePendingTakeoverRecordsInternal(ceContext, database)
        }
    }

    private suspend fun consumePendingTakeoverRecordsInternal(
        ceContext: Context,
        database: AlarmDatabase
    ): ConsumerExecutionResult {
        val storageState = DirectBootCeTakeoverStore.inspectStorageState(ceContext)
        val pendingRecords = when (storageState) {
            is CeTakeoverStorageState.Absent,
            is CeTakeoverStorageState.ValidEmpty -> return ConsumerExecutionResult.NoPendingRecords
            is CeTakeoverStorageState.ValidWithRecords -> storageState.records
            is CeTakeoverStorageState.CeLocked -> return ConsumerExecutionResult.Rejected("CE Storage is locked")
            is CeTakeoverStorageState.CeContextRejected -> return ConsumerExecutionResult.Rejected(storageState.reason)
            is CeTakeoverStorageState.Corrupt -> return ConsumerExecutionResult.Rejected("CE Storage is corrupt: ${storageState.reason}")
            is CeTakeoverStorageState.UnsupportedSchema -> return ConsumerExecutionResult.Rejected("CE Storage unsupported schema version ${storageState.foundVersion}")
        }

        if (pendingRecords.isEmpty()) {
            return ConsumerExecutionResult.NoPendingRecords
        }

        val deContext = if (ceContext.isDeviceProtectedStorage) ceContext else ceContext.createDeviceProtectedStorageContext()

        var processedCount = 0
        var receiptsCommitted = 0
        var deAcknowledgedCount = 0
        var witnessAcknowledgedCount = 0
        var ceSlotsReleasedCount = 0
        val failures = mutableListOf<RecordRetirementFailure>()

        for (record in pendingRecords) {
            val outcome = processSingleRecord(ceContext, database, record)
            when (outcome) {
                is RecordConsumptionOutcome.ConsumedDelivered,
                is RecordConsumptionOutcome.ConsumedMissed,
                is RecordConsumptionOutcome.AlreadyConsumed -> {
                    val proof = verifyAndBuildRoomReceiptProof(database, record, outcome)
                    if (proof == null) {
                        failures.add(
                            RecordRetirementFailure(
                                occurrenceKey = record.occurrenceKey,
                                stage = "ROOM_RECEIPT_PROOF",
                                details = "Room 38 receipt proof verification failed for ${record.occurrenceKey}"
                            )
                        )
                        processedCount++
                        continue
                    }

                    receiptsCommitted++

                    // Step 2: Qualified DE ACK (Room receipt verified -> authorized to use CeHandoffResult.Confirmed carrier)
                    val confirmedProofForDeAck = CeHandoffResult.Confirmed(
                        ceRecord = proof.ceRecord,
                        totalCeRecords = pendingRecords.size,
                        alreadyRecordedInCe = true,
                        writePerformed = false
                    )
                    val deAckResult = when (record) {
                        is CeTakeoverRecord.Delivered -> {
                            val candidate = DirectBootDeliveryRecord(
                                recordId = record.sourceRecordId,
                                kind = record.kind,
                                alarmId = record.alarmId,
                                triggerTime = record.triggerTime,
                                deliveredAt = record.deliveredAt,
                                generation = record.generation,
                                instanceToken = record.instanceToken,
                                provenance = record.provenance,
                                restoreEpoch = record.restoreEpoch,
                                schemaVersion = record.schemaVersion
                            )
                            DirectBootCeHandoffCoordinator.acknowledgeDeliveredWithCeProof(
                                deContext = deContext,
                                ceContext = ceContext,
                                candidateRecord = candidate,
                                confirmedReceipt = confirmedProofForDeAck,
                                allowServiceStartFailedCoRetirement = (proof.receipt.consumerOutcome == "CONSUMED_MISSED")
                            )
                        }
                        is CeTakeoverRecord.Missed -> {
                            val candidate = DirectBootMissedRecord(
                                recordId = record.sourceRecordId,
                                kind = record.kind,
                                alarmId = record.alarmId,
                                triggerTime = record.triggerTime,
                                missedAt = record.missedAt,
                                generation = record.generation,
                                instanceToken = record.instanceToken,
                                classification = record.classification,
                                restoreEpoch = record.restoreEpoch,
                                schemaVersion = record.schemaVersion
                            )
                            DirectBootCeHandoffCoordinator.acknowledgeMissedWithCeProof(
                                deContext = deContext,
                                ceContext = ceContext,
                                candidateRecord = candidate,
                                confirmedReceipt = confirmedProofForDeAck
                            )
                        }
                    }

                    if (!deAckResult.deRecordRetired) {
                        failures.add(
                            RecordRetirementFailure(
                                occurrenceKey = record.occurrenceKey,
                                stage = "DE_ACK",
                                details = "Qualified DE ACK rejected/uncertain: $deAckResult"
                            )
                        )
                        processedCount++
                        continue
                    }
                    deAcknowledgedCount++

                    // Step 3: Witness ACK
                    val witnessRecordId = DirectBootFiredWitnessRecord.computeRecordId(
                        kind = record.kind,
                        alarmId = record.alarmId,
                        triggerTime = record.triggerTime,
                        generation = record.generation,
                        instanceToken = record.instanceToken,
                        restoreEpoch = record.restoreEpoch
                    )
                    val witnessAckResult = DirectBootFiredWitnessLedger.acknowledgeWitness(deContext, witnessRecordId)
                    when (witnessAckResult) {
                        is WitnessAckResult.Acknowledged -> {
                            witnessAcknowledgedCount++
                        }
                        is WitnessAckResult.NotFound -> {
                            // Valid when record is Missed / CONSUMED_MISSED / post-Witness crash retry and Witness store parsed cleanly
                        }
                        is WitnessAckResult.PersistenceFailed,
                        is WitnessAckResult.ReadbackFailed,
                        is WitnessAckResult.StorageCorrupt,
                        is WitnessAckResult.UnsupportedSchema -> {
                            failures.add(
                                RecordRetirementFailure(
                                    occurrenceKey = record.occurrenceKey,
                                    stage = "WITNESS_ACK",
                                    details = "Witness ACK failed: $witnessAckResult"
                                )
                            )
                            processedCount++
                            continue
                        }
                    }

                    // Step 4: CE Slot Release
                    when (val ceReleaseResult = DirectBootCeTakeoverStore.releaseSlotWithResult(ceContext, record.occurrenceKey)) {
                        is CeSlotReleaseResult.Released,
                        is CeSlotReleaseResult.AlreadyAbsent -> {
                            ceSlotsReleasedCount++
                        }
                        else -> {
                            failures.add(
                                RecordRetirementFailure(
                                    occurrenceKey = record.occurrenceKey,
                                    stage = "CE_RELEASE",
                                    details = "CE slot release failed: $ceReleaseResult"
                                )
                            )
                        }
                    }
                }
                is RecordConsumptionOutcome.RestoreEpochConflict,
                is RecordConsumptionOutcome.PayloadConflict,
                is RecordConsumptionOutcome.ReadbackFailed -> {
                    // Fail closed: preserve CE slot for quarantine / diagnostic review
                }
            }
            processedCount++
        }

        return if (failures.isEmpty()) {
            ConsumerExecutionResult.Success(
                processedCount = processedCount,
                receiptsCommitted = receiptsCommitted
            )
        } else {
            ConsumerExecutionResult.IncompleteRetirement(
                processedCount = processedCount,
                receiptsCommitted = receiptsCommitted,
                deAcknowledgedCount = deAcknowledgedCount,
                witnessAcknowledgedCount = witnessAcknowledgedCount,
                ceSlotsReleasedCount = ceSlotsReleasedCount,
                failures = failures.toList()
            )
        }
    }

    private suspend fun verifyAndBuildRoomReceiptProof(
        database: AlarmDatabase,
        record: CeTakeoverRecord,
        outcome: RecordConsumptionOutcome
    ): VerifiedRoomReceiptProof? {
        val candidateReceipt = when (outcome) {
            is RecordConsumptionOutcome.ConsumedDelivered -> outcome.receipt
            is RecordConsumptionOutcome.ConsumedMissed -> outcome.receipt
            is RecordConsumptionOutcome.AlreadyConsumed -> outcome.existingReceipt
            else -> return null
        }

        if (record.restoreEpoch <= 0L) return null
        if (candidateReceipt.consumerOutcome !in VALID_CONSUMED_OUTCOMES) return null
        if (candidateReceipt.occurrenceKey != record.occurrenceKey ||
            candidateReceipt.sourceRecordId != record.sourceRecordId ||
            candidateReceipt.recordType != record.recordType ||
            candidateReceipt.alarmId != record.alarmId ||
            candidateReceipt.triggerTime != record.triggerTime ||
            candidateReceipt.generation != record.generation ||
            candidateReceipt.instanceToken != record.instanceToken ||
            candidateReceipt.restoreEpoch <= 0L ||
            candidateReceipt.restoreEpoch != record.restoreEpoch
        ) {
            return null
        }

        val expectedHash = computeHashForRecord(record, record.restoreEpoch)
        if (candidateReceipt.sourcePayloadHash != expectedHash) return null

        val readback = try {
            database.directBootOccurrenceReceiptDao().findByOccurrenceKey(record.occurrenceKey)
        } catch (e: Exception) {
            Log.w(TAG, "Room 38 receipt readback exception for ${record.occurrenceKey}: ${e.message}")
            return null
        }

        if (readback == null || readback != candidateReceipt) {
            return null
        }

        return VerifiedRoomReceiptProof(ceRecord = record, receipt = readback)
    }

    /**
     * Consumes a single CE takeover record inside an isolated Room transaction.
     */
    suspend fun processSingleRecord(
        ceContext: Context,
        database: AlarmDatabase,
        record: CeTakeoverRecord
    ): RecordConsumptionOutcome {
        val receiptDao = database.directBootOccurrenceReceiptDao()

        val transactionOutcome = database.withTransaction {
            // 1. Idempotency Check: Pre-read existing receipt by occurrenceKey
            val existingReceipt = receiptDao.findByOccurrenceKey(record.occurrenceKey)
            if (existingReceipt != null) {
                if (existingReceipt.consumerOutcome == "RESTORE_EPOCH_CONFLICT") {
                    return@withTransaction RecordConsumptionOutcome.RestoreEpochConflict(record.alarmId, record.instanceToken)
                }
                if (existingReceipt.consumerOutcome !in VALID_CONSUMED_OUTCOMES) {
                    return@withTransaction RecordConsumptionOutcome.PayloadConflict(record.occurrenceKey)
                }
                if (existingReceipt.restoreEpoch != record.restoreEpoch || existingReceipt.restoreEpoch <= 0L) {
                    Log.w(TAG, "Restore epoch mismatch for existing receipt ${record.occurrenceKey}: existing=${existingReceipt.restoreEpoch}, candidate=${record.restoreEpoch}")
                    return@withTransaction RecordConsumptionOutcome.PayloadConflict(record.occurrenceKey)
                }
                val candidateHash = computeHashForRecord(record, record.restoreEpoch)
                if (existingReceipt.sourcePayloadHash == candidateHash &&
                    existingReceipt.sourceRecordId == record.sourceRecordId &&
                    existingReceipt.recordType == record.recordType &&
                    existingReceipt.alarmId == record.alarmId &&
                    existingReceipt.triggerTime == record.triggerTime &&
                    existingReceipt.generation == record.generation &&
                    existingReceipt.instanceToken == record.instanceToken
                ) {
                    return@withTransaction RecordConsumptionOutcome.AlreadyConsumed(existingReceipt)
                } else {
                    return@withTransaction RecordConsumptionOutcome.PayloadConflict(record.occurrenceKey)
                }
            }

            // 2. Resolve Restore Epoch Provenance & Legacy v1 Fence (Fail-Closed)
            if (record.restoreEpoch <= 0L) {
                // Legacy v1 record (restoreEpoch == 0L) or invalid epoch: UNQUALIFIED.
                // Never inherit or fabricate current Room authority restoreEpoch for past records!
                val conflictReceipt = DirectBootOccurrenceReceipt(
                    occurrenceKey = record.occurrenceKey,
                    sourceRecordId = record.sourceRecordId,
                    recordType = record.recordType,
                    alarmId = record.alarmId,
                    triggerTime = record.triggerTime,
                    generation = record.generation,
                    instanceToken = record.instanceToken,
                    restoreEpoch = 0L,
                    sourcePayloadHash = computeHashForRecord(record, 0L),
                    consumerOutcome = "RESTORE_EPOCH_CONFLICT",
                    completionSequence = 0L,
                    completedAt = record.recordedAtEpochMs
                )
                receiptDao.insert(conflictReceipt)
                return@withTransaction RecordConsumptionOutcome.RestoreEpochConflict(record.alarmId, record.instanceToken)
            }

            val resolvedEpoch = record.restoreEpoch

            // 3. Construct and insert Room 38 Receipt
            val deContext = if (ceContext.isDeviceProtectedStorage) ceContext else ceContext.createDeviceProtectedStorageContext()
            val sourceHash = computeHashForRecord(record, resolvedEpoch)
            val outcomeString = when (record) {
                is CeTakeoverRecord.Delivered -> {
                    val hasStartFailed = hasServiceStartFailed(ceContext, record.alarmId, record.triggerTime, record.generation, record.instanceToken, resolvedEpoch)
                    if (hasStartFailed) {
                        "CONSUMED_MISSED"
                    } else {
                        val witnessState = DirectBootFiredWitnessLedger.readWitnesses(deContext)
                        if (witnessState is WitnessLedgerState.Corrupt || witnessState is WitnessLedgerState.UnsupportedSchema) {
                            Log.w(TAG, "Witness ledger fail closed during takeover: $witnessState")
                            return@withTransaction RecordConsumptionOutcome.ReadbackFailed(record.occurrenceKey)
                        }

                        val witnessRecords = (witnessState as? WitnessLedgerState.ValidWithRecords)?.records.orEmpty()
                        val witnessRecord = witnessRecords.firstOrNull {
                            it.alarmId == record.alarmId &&
                                it.triggerTime == record.triggerTime &&
                                it.generation == record.generation &&
                                it.instanceToken == record.instanceToken &&
                                it.restoreEpoch == resolvedEpoch &&
                                it.kind == record.kind
                        }

                        if (witnessRecord == null || witnessRecord.deliveryOutcome == DirectBootFiredWitnessRecord.OUTCOME_OUTPUT_STATUS_UNKNOWN) {
                            // SAFE_DEFER_AND_RETAIN: Defer receipt insertion and skip DE ACK for unconfirmed claims
                            return@withTransaction RecordConsumptionOutcome.ReadbackFailed(record.occurrenceKey)
                        }

                        when (witnessRecord.deliveryOutcome) {
                            DirectBootFiredWitnessRecord.OUTCOME_OUTPUT_START_ACCEPTED,
                            DirectBootFiredWitnessRecord.OUTCOME_PARTIAL_OUTPUT_ACCEPTED,
                            DirectBootFiredWitnessRecord.OUTCOME_NO_OUTPUT_REQUESTED -> "CONSUMED_DELIVERED"
                            DirectBootFiredWitnessRecord.OUTCOME_OUTPUT_START_FAILED -> "CONSUMED_MISSED"
                            else -> {
                                Log.w(TAG, "Unrecognized deliveryOutcome '${witnessRecord.deliveryOutcome}' for record ${record.occurrenceKey}; fail-closed deferral")
                                return@withTransaction RecordConsumptionOutcome.ReadbackFailed(record.occurrenceKey)
                            }
                        }
                    }
                }
                is CeTakeoverRecord.Missed -> "CONSUMED_MISSED"
            }

            val newReceipt = DirectBootOccurrenceReceipt(
                occurrenceKey = record.occurrenceKey,
                sourceRecordId = record.sourceRecordId,
                recordType = record.recordType,
                alarmId = record.alarmId,
                triggerTime = record.triggerTime,
                generation = record.generation,
                instanceToken = record.instanceToken,
                restoreEpoch = resolvedEpoch,
                sourcePayloadHash = sourceHash,
                consumerOutcome = outcomeString,
                completionSequence = 0L,
                completedAt = when (record) {
                    is CeTakeoverRecord.Delivered -> record.deliveredAt
                    is CeTakeoverRecord.Missed -> record.missedAt
                }
            )

            receiptDao.insert(newReceipt)

            when (record) {
                is CeTakeoverRecord.Delivered -> RecordConsumptionOutcome.ConsumedDelivered(newReceipt)
                is CeTakeoverRecord.Missed -> RecordConsumptionOutcome.ConsumedMissed(newReceipt) // RECEIPT-ONLY
            }
        }

        // 4. Independent Readback Verification outside transaction
        return when (transactionOutcome) {
            is RecordConsumptionOutcome.ConsumedDelivered,
            is RecordConsumptionOutcome.ConsumedMissed -> {
                val readback = receiptDao.findByOccurrenceKey(record.occurrenceKey)
                val expectedReceipt = when (transactionOutcome) {
                    is RecordConsumptionOutcome.ConsumedDelivered -> transactionOutcome.receipt
                    is RecordConsumptionOutcome.ConsumedMissed -> transactionOutcome.receipt
                    else -> null
                }
                if (readback != null && readback == expectedReceipt) {
                    transactionOutcome
                } else {
                    RecordConsumptionOutcome.ReadbackFailed(record.occurrenceKey)
                }
            }
            else -> transactionOutcome
        }
    }

    private fun computeHashForRecord(record: CeTakeoverRecord, restoreEpoch: Long): String {
        return when (record) {
            is CeTakeoverRecord.Delivered -> calculateCanonicalPayloadHashV2(
                sourceRecordId = record.sourceRecordId,
                recordType = record.recordType,
                alarmId = record.alarmId,
                triggerTime = record.triggerTime,
                eventTimestamp = record.deliveredAt,
                generation = record.generation,
                instanceToken = record.instanceToken,
                restoreEpoch = restoreEpoch,
                metadataString = record.provenance
            )
            is CeTakeoverRecord.Missed -> calculateCanonicalPayloadHashV2(
                sourceRecordId = record.sourceRecordId,
                recordType = record.recordType,
                alarmId = record.alarmId,
                triggerTime = record.triggerTime,
                eventTimestamp = record.missedAt,
                generation = record.generation,
                instanceToken = record.instanceToken,
                restoreEpoch = restoreEpoch,
                metadataString = record.classification
            )
        }
    }

    private fun hasServiceStartFailed(context: Context, alarmId: Long, triggerTime: Long, generation: Int, instanceToken: String, restoreEpoch: Long): Boolean {
        val deContext = if (context.isDeviceProtectedStorage) context else context.createDeviceProtectedStorageContext()
        return try {
            val missedState = DirectBootMissedStore.readUnacknowledgedRecords(deContext)
            if (missedState is MissedStoreStorageState.ValidWithRecords) {
                missedState.records.any {
                    it.alarmId == alarmId &&
                        it.triggerTime == triggerTime &&
                        it.generation == generation &&
                        it.instanceToken == instanceToken &&
                        it.restoreEpoch == restoreEpoch &&
                        it.classification == DirectBootMissedRecord.CLASSIFICATION_SERVICE_START_FAILED
                }
            } else false
        } catch (_: Exception) {
            false
        }
    }
}
