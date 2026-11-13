package com.sysadmindoc.alarmclock.directboot

import android.content.Context
import android.util.Log
import androidx.room.withTransaction
import com.sysadmindoc.alarmclock.data.local.AlarmDatabase
import com.sysadmindoc.alarmclock.data.local.entity.DirectBootOccurrenceReceipt
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/**
 * Post-Unlock Direct Boot Event Consumer Engine & Room 38 Receipt Transaction Coordinator
 * for KNO-75 (Phase D3-C2-P3D-P3-BATCH-B / Slice E2).
 *
 * Invariants & Boundaries:
 * - Room 38 Schema: Uses accepted Room 38 [DirectBootOccurrenceReceipt] entity & DAO without schema modifications.
 * - Lock Isolation: Executes Room transactions under SQLite WAL transaction locks with ZERO DE or CE storage locks held.
 * - Missed Policy: Missed takeover records use RECEIPT-ONLY strategy. Inserts [DirectBootOccurrenceReceipt]
 *   with consumerOutcome = "CONSUMED_MISSED" without inserting [AlarmEvent] rows or creating sessions/tombstones.
 * - Legacy v1 Fence: Legacy v1 records (restoreEpoch = 0L) inherit active Room [ScheduleAuthority.restoreEpoch]
 *   IF AND ONLY IF instanceToken matches. If instanceToken mismatches, marks as RESTORE_EPOCH_CONFLICT
 *   and DOES NOT release the CE active slot.
 * - Independent Readback: Releases CE active slots in [DirectBootCeTakeoverStore] ONLY after independent
 *   Room receipt readback verification succeeds.
 */
object DirectBootEventConsumerEngine {

    private const val TAG = "DirectBootEventConsumerEngine"

    sealed class ConsumerExecutionResult {
        data class Success(val processedCount: Int, val receiptsCommitted: Int) : ConsumerExecutionResult()
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

        var processedCount = 0
        var receiptsCommitted = 0

        for (record in pendingRecords) {
            val outcome = processSingleRecord(ceContext, database, record)
            when (outcome) {
                is RecordConsumptionOutcome.ConsumedDelivered,
                is RecordConsumptionOutcome.ConsumedMissed,
                is RecordConsumptionOutcome.AlreadyConsumed -> {
                    receiptsCommitted++
                    val deContext = if (ceContext.isDeviceProtectedStorage) ceContext else ceContext.createDeviceProtectedStorageContext()
                    val witnessRecordId = DirectBootFiredWitnessRecord.computeRecordId(
                        record.kind,
                        record.alarmId,
                        record.triggerTime,
                        record.generation,
                        record.instanceToken,
                        record.restoreEpoch
                    )
                    DirectBootFiredWitnessLedger.acknowledgeWitness(deContext, witnessRecordId)
                    DirectBootDeliveryLedger.acknowledgeRecord(deContext, record.sourceRecordId)
                    DirectBootCeTakeoverStore.releaseSlot(ceContext, record.occurrenceKey)
                }
                is RecordConsumptionOutcome.RestoreEpochConflict,
                is RecordConsumptionOutcome.PayloadConflict,
                is RecordConsumptionOutcome.ReadbackFailed -> {
                    // Fail closed: preserve CE slot for quarantine / diagnostic review
                }
            }
            processedCount++
        }

        return ConsumerExecutionResult.Success(
            processedCount = processedCount,
            receiptsCommitted = receiptsCommitted
        )
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
        val authorityDao = database.scheduleAuthorityDao()

        val transactionOutcome = database.withTransaction {
            // 1. Idempotency Check: Pre-read existing receipt by occurrenceKey
            val existingReceipt = receiptDao.findByOccurrenceKey(record.occurrenceKey)
            if (existingReceipt != null) {
                if (existingReceipt.restoreEpoch != record.restoreEpoch) {
                    Log.w(TAG, "Restore epoch mismatch for existing receipt ${record.occurrenceKey}: existing=${existingReceipt.restoreEpoch}, candidate=${record.restoreEpoch}")
                    return@withTransaction RecordConsumptionOutcome.PayloadConflict(record.occurrenceKey)
                }
                val candidateHash = computeHashForRecord(record, record.restoreEpoch)
                if (existingReceipt.sourcePayloadHash == candidateHash) {
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
                if (readback != null) {
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
