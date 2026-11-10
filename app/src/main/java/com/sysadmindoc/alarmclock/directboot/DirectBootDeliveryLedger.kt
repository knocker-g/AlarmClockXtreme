package com.sysadmindoc.alarmclock.directboot

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject

/**
 * Isolated Device-Encrypted (DE) Delivery Ledger storage for KNO-75.
 *
 * Responsibilities & Invariants:
 * - Persist unacknowledged delivery evidence in Device Protected Storage.
 * - Fail-closed corrupt/unsupported schema handling:
 *   Distinguishes ABSENT, VALID_EMPTY, VALID_WITH_RECORDS, CORRUPT, and UNSUPPORTED_SCHEMA.
 *   On CORRUPT or UNSUPPORTED_SCHEMA, both append and ACK operations fail closed, rejecting the operation
 *   and preserving the existing raw payload completely untouched without automatic clearing or silent migration.
 * - Stored payload integrity validation:
 *   Persisted payloads with > 16 records or containing duplicate [DirectBootDeliveryRecord.recordId] entries
 *   are strictly classified as CORRUPT and are non-writable.
 * - Fail-closed capacity boundary (16 records):
 *   At capacity (16 records), further appends are rejected with [DeliveryAppendResult.CapacityExhausted]
 *   without evicting, dropping, or pruning prior unacknowledged records.
 * - Duplicate idempotency: Re-appending an existing [DirectBootDeliveryRecord.recordId] returns
 *   [DeliveryAppendResult.DuplicateIgnored] without modifying storage.
 * - Selective single-record acknowledgment: [acknowledgeRecord] removes ONLY the exact addressed record.
 *   Zero bulk clear or reset APIs are exposed to production code.
 * - Concurrency & Single-Writer Contract:
 *   Synchronous [SharedPreferences.Editor.commit] with in-process [synchronized] monitor provides
 *   serialized single-writer thread safety within the host process.
 *   RUNTIME ACTIVATION GATE: Cross-process atomicity, system server coordination, and crash/AlarmManager
 *   recovery synchronization are explicitly deferred to the runtime activation slice (B0 / P3).
 * - Typed Read Boundary:
 *   [readUnacknowledgedRecords] returns a typed [LedgerStorageState], preventing ambiguous empty-list
 *   interpretations for corrupt or unsupported storage.
 * - Key isolation: Stored in dedicated key [KEY_DELIVERY_LEDGER_PAYLOAD] in [PREFS_NAME].
 *   Completely isolated from V1 fired marker and V2 queue storage.
 */
object DirectBootDeliveryLedger {
    private const val TAG = "DirectBootDeliveryLedger"
    const val PREFS_NAME = "direct_boot_next_alarm"
    const val KEY_DELIVERY_LEDGER_PAYLOAD = "direct_boot_delivery_ledger_payload"
    const val MAX_CAPACITY = 16
    const val CURRENT_SCHEMA_VERSION = 1

    internal val storageLock: Any get() = DirectBootStorageLock.lock

    /**
     * Inspects the current storage state of the Delivery Ledger.
     */
    fun inspectStorageState(context: Context): LedgerStorageState {
        val rawPayload = prefs(context).getString(KEY_DELIVERY_LEDGER_PAYLOAD, null)
        return parseStorageState(rawPayload)
    }

    /**
     * Exposes the explicit classification of the Delivery Ledger storage.
     */
    fun classifyStorage(context: Context): LedgerStorageClassification {
        return inspectStorageState(context).classification
    }

    /**
     * Reads all unacknowledged delivery records as a typed [LedgerStorageState].
     * Callers must explicitly handle ABSENT, VALID_EMPTY, VALID_WITH_RECORDS, CORRUPT, and UNSUPPORTED_SCHEMA.
     */
    fun readUnacknowledgedRecords(context: Context): LedgerStorageState {
        return inspectStorageState(context)
    }

    /**
     * Appends an unacknowledged delivery record to the DE ledger.
     *
     * Invariants:
     * - Validates record eligibility ([DirectBootDeliveryRecord.isValid]).
     * - Rejects append if storage is [LedgerStorageClassification.CORRUPT] or [LedgerStorageClassification.UNSUPPORTED_SCHEMA],
     *   leaving raw payload completely untouched.
     * - Returns [DeliveryAppendResult.DuplicateIgnored] if record with same recordId is already present.
     * - Returns [DeliveryAppendResult.CapacityExhausted] if ledger already contains [MAX_CAPACITY] records without modifying storage.
     * - Serialized via in-process [storageLock] and persists synchronously via [SharedPreferences.Editor.commit].
     */
    fun appendRecord(context: Context, record: DirectBootDeliveryRecord): DeliveryAppendResult {
        val validation = record.evaluateValidation()
        if (validation != DeliveryValidationStatus.VALID) {
            return DeliveryAppendResult.RejectedIneligible(validation)
        }

        synchronized(storageLock) {
            val state = inspectStorageState(context)
            val currentRecords: MutableList<DirectBootDeliveryRecord> = when (state) {
                is LedgerStorageState.Absent -> mutableListOf()
                is LedgerStorageState.ValidEmpty -> mutableListOf()
                is LedgerStorageState.ValidWithRecords -> state.records.toMutableList()
                is LedgerStorageState.Corrupt -> {
                    Log.w(TAG, "Append rejected: storage is corrupt (${state.reason})")
                    return DeliveryAppendResult.StorageCorrupt(state.reason)
                }
                is LedgerStorageState.UnsupportedSchema -> {
                    Log.w(TAG, "Append rejected: unsupported schema version ${state.foundVersion} (expected ${state.expectedVersion})")
                    return DeliveryAppendResult.UnsupportedSchema(state.foundVersion, state.expectedVersion)
                }
            }

            // Idempotency: duplicate recordId is ignored without modifying storage
            val existing = currentRecords.find { it.recordId == record.recordId }
            if (existing != null) {
                return DeliveryAppendResult.DuplicateIgnored(existing, currentRecords.size)
            }

            // Capacity safety: at capacity, fail closed without evicting prior unacknowledged records
            if (currentRecords.size >= MAX_CAPACITY) {
                return DeliveryAppendResult.CapacityExhausted(currentRecords.size, MAX_CAPACITY)
            }

            currentRecords.add(record)

            val root = JSONObject().apply {
                put("schemaVersion", CURRENT_SCHEMA_VERSION)
                val array = JSONArray()
                currentRecords.forEach { array.put(it.toJson()) }
                put("records", array)
            }

            return try {
                val success = prefs(context).edit()
                    .putString(KEY_DELIVERY_LEDGER_PAYLOAD, root.toString())
                    .commit()

                if (success) {
                    DeliveryAppendResult.Appended(record, currentRecords.size)
                } else {
                    DeliveryAppendResult.PersistenceFailed("SharedPreferences commit returned false")
                }
            } catch (e: Exception) {
                DeliveryAppendResult.PersistenceFailed("SharedPreferences commit threw exception: ${e.message}")
            }
        }
    }

    /**
     * Acknowledges and removes an exact delivery record by [recordId].
     *
     * Invariants:
     * - Removes ONLY the targeted record.
     * - Preserves all unrelated records.
     * - Rejects ACK if storage is [LedgerStorageClassification.CORRUPT] or [LedgerStorageClassification.UNSUPPORTED_SCHEMA],
     *   leaving raw payload completely untouched.
     * - Returns [DeliveryAckResult.NotFound] if recordId is not present.
     * - Serialized via in-process [storageLock] and persists synchronously via [SharedPreferences.Editor.commit].
     */
    fun acknowledgeRecord(context: Context, recordId: String): DeliveryAckResult {
        if (recordId.isBlank()) {
            return DeliveryAckResult.RejectedInvalidId("RecordId must not be blank")
        }

        synchronized(storageLock) {
            val state = inspectStorageState(context)
            val currentRecords: MutableList<DirectBootDeliveryRecord> = when (state) {
                is LedgerStorageState.Absent -> return DeliveryAckResult.NotFound(recordId, 0)
                is LedgerStorageState.ValidEmpty -> return DeliveryAckResult.NotFound(recordId, 0)
                is LedgerStorageState.ValidWithRecords -> state.records.toMutableList()
                is LedgerStorageState.Corrupt -> {
                    Log.w(TAG, "Acknowledge rejected: storage is corrupt (${state.reason})")
                    return DeliveryAckResult.StorageCorrupt(state.reason)
                }
                is LedgerStorageState.UnsupportedSchema -> {
                    Log.w(TAG, "Acknowledge rejected: unsupported schema version ${state.foundVersion} (expected ${state.expectedVersion})")
                    return DeliveryAckResult.UnsupportedSchema(state.foundVersion, state.expectedVersion)
                }
            }

            val removed = currentRecords.removeAll { it.recordId == recordId }
            if (!removed) {
                return DeliveryAckResult.NotFound(recordId, currentRecords.size)
            }

            val root = JSONObject().apply {
                put("schemaVersion", CURRENT_SCHEMA_VERSION)
                val array = JSONArray()
                currentRecords.forEach { array.put(it.toJson()) }
                put("records", array)
            }

            return try {
                val success = prefs(context).edit()
                    .putString(KEY_DELIVERY_LEDGER_PAYLOAD, root.toString())
                    .commit()

                if (success) {
                    DeliveryAckResult.Acknowledged(recordId, currentRecords.size)
                } else {
                    DeliveryAckResult.PersistenceFailed("SharedPreferences commit returned false")
                }
            } catch (e: Exception) {
                DeliveryAckResult.PersistenceFailed("SharedPreferences commit threw exception: ${e.message}")
            }
        }
    }

    /**
     * Diagnostic helper: returns the raw serialized ledger payload string.
     */
    fun getRawPayload(context: Context): String? {
        return prefs(context).getString(KEY_DELIVERY_LEDGER_PAYLOAD, null)
    }

    /**
     * Parses the raw string payload and classifies the storage state.
     * Enforces fail-closed rules:
     * - More than [MAX_CAPACITY] records is CORRUPT.
     * - Duplicate recordIds in storage is CORRUPT.
     * - Any malformed record is CORRUPT.
     * - Unsupported schema version is UNSUPPORTED_SCHEMA.
     */
    fun parseStorageState(rawPayload: String?): LedgerStorageState {
        if (rawPayload == null) {
            return LedgerStorageState.Absent
        }
        if (rawPayload.isBlank()) {
            return LedgerStorageState.Corrupt("Blank payload string")
        }
        return try {
            val root = JSONObject(rawPayload)
            if (!root.has("schemaVersion")) {
                return LedgerStorageState.Corrupt("Missing schemaVersion field")
            }
            val schemaVersion = root.optInt("schemaVersion", -1)
            if (schemaVersion != CURRENT_SCHEMA_VERSION) {
                return LedgerStorageState.UnsupportedSchema(schemaVersion, CURRENT_SCHEMA_VERSION)
            }
            if (!root.has("records")) {
                return LedgerStorageState.Corrupt("Missing records array")
            }
            val recordsArray = root.optJSONArray("records")
                ?: return LedgerStorageState.Corrupt("records is not a JSON array")

            if (recordsArray.length() == 0) {
                return LedgerStorageState.ValidEmpty
            }

            // Hardening: Stored records exceeding MAX_CAPACITY is strictly CORRUPT
            if (recordsArray.length() > MAX_CAPACITY) {
                return LedgerStorageState.Corrupt(
                    "Stored records count (${recordsArray.length()}) exceeds MAX_CAPACITY ($MAX_CAPACITY)"
                )
            }

            val list = ArrayList<DirectBootDeliveryRecord>(recordsArray.length())
            val seenRecordIds = HashSet<String>(recordsArray.length())

            for (i in 0 until recordsArray.length()) {
                val recordObj = recordsArray.optJSONObject(i)
                    ?: return LedgerStorageState.Corrupt("Record at index $i is not a JSON object")
                val record = try {
                    DirectBootDeliveryRecord.fromJson(recordObj)
                } catch (e: Exception) {
                    return LedgerStorageState.Corrupt("Failed to parse record at index $i: ${e.message}")
                }
                val validation = record.evaluateValidation()
                if (validation != DeliveryValidationStatus.VALID) {
                    return LedgerStorageState.Corrupt("Invalid record at index $i: $validation")
                }
                // Hardening: Persisted duplicate recordIds is strictly CORRUPT
                if (!seenRecordIds.add(record.recordId)) {
                    return LedgerStorageState.Corrupt(
                        "Duplicate recordId encountered in storage: ${record.recordId}"
                    )
                }
                list.add(record)
            }
            LedgerStorageState.ValidWithRecords(list)
        } catch (e: Exception) {
            LedgerStorageState.Corrupt("JSON parsing failed: ${e.message}")
        }
    }

    private fun prefs(context: Context): SharedPreferences {
        return context.applicationContext.createDeviceProtectedStorageContext()
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }
}

enum class LedgerStorageClassification {
    ABSENT,
    VALID_EMPTY,
    VALID_WITH_RECORDS,
    CORRUPT,
    UNSUPPORTED_SCHEMA
}

sealed class LedgerStorageState {
    abstract val classification: LedgerStorageClassification

    object Absent : LedgerStorageState() {
        override val classification = LedgerStorageClassification.ABSENT
    }

    object ValidEmpty : LedgerStorageState() {
        override val classification = LedgerStorageClassification.VALID_EMPTY
    }

    data class ValidWithRecords(val records: List<DirectBootDeliveryRecord>) : LedgerStorageState() {
        override val classification = LedgerStorageClassification.VALID_WITH_RECORDS
    }

    data class Corrupt(val reason: String) : LedgerStorageState() {
        override val classification = LedgerStorageClassification.CORRUPT
    }

    data class UnsupportedSchema(val foundVersion: Int, val expectedVersion: Int = DirectBootDeliveryLedger.CURRENT_SCHEMA_VERSION) : LedgerStorageState() {
        override val classification = LedgerStorageClassification.UNSUPPORTED_SCHEMA
    }
}

sealed class DeliveryAppendResult {
    data class Appended(val record: DirectBootDeliveryRecord, val unacknowledgedCount: Int) : DeliveryAppendResult()
    data class DuplicateIgnored(val existing: DirectBootDeliveryRecord, val unacknowledgedCount: Int) : DeliveryAppendResult()
    data class CapacityExhausted(val currentCount: Int, val maxCapacity: Int) : DeliveryAppendResult()
    data class RejectedIneligible(val status: DeliveryValidationStatus) : DeliveryAppendResult()
    data class PersistenceFailed(val reason: String) : DeliveryAppendResult()
    data class StorageCorrupt(val reason: String) : DeliveryAppendResult()
    data class UnsupportedSchema(val foundVersion: Int, val expectedVersion: Int) : DeliveryAppendResult()
}

sealed class DeliveryAckResult {
    data class Acknowledged(val recordId: String, val remainingCount: Int) : DeliveryAckResult()
    data class NotFound(val recordId: String, val remainingCount: Int) : DeliveryAckResult()
    data class RejectedInvalidId(val reason: String) : DeliveryAckResult()
    data class PersistenceFailed(val reason: String) : DeliveryAckResult()
    data class StorageCorrupt(val reason: String) : DeliveryAckResult()
    data class UnsupportedSchema(val foundVersion: Int, val expectedVersion: Int) : DeliveryAckResult()
}
