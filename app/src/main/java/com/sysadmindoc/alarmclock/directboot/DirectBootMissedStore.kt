package com.sysadmindoc.alarmclock.directboot

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject

/**
 * Isolated Device-Protected Missed Record Store for KNO-75 (Phase D3-C2-P3B / D3-C2-P3B-C1).
 *
 * Responsibilities & Invariants:
 * - Persist unacknowledged missed records in Device Protected Storage.
 * - Key isolation: Stored under dedicated key [KEY_MISSED_STORE_PAYLOAD] in [PREFS_NAME].
 *   Completely isolated from Queue storage ([DirectBootQueueStorage]), Delivery Ledger ([DirectBootDeliveryLedger]),
 *   and V1 fired marker.
 * - Fail-closed corrupt / unsupported schema handling:
 *   Distinguishes ABSENT, VALID_EMPTY, VALID_WITH_RECORDS, CORRUPT, UNSUPPORTED_SCHEMA, and PERSISTENCE_UNCERTAIN.
 *   On CORRUPT or UNSUPPORTED_SCHEMA, both recordMissed and ACK operations fail closed, rejecting the operation
 *   and preserving the existing raw payload completely untouched without automatic clearing or silent migration.
 * - Stored payload integrity validation:
 *   Persisted payloads with > 16 records, duplicate [DirectBootMissedRecord.recordId] entries, or unknown classifications
 *   are strictly classified as CORRUPT and are non-writable.
 * - Fail-closed bounded capacity (16 records):
 *   At capacity (16 records), further appends of distinct records are rejected with [MissedRecordResult.CapacityExhausted]
 *   without evicting, dropping, or pruning prior unacknowledged records.
 * - Duplicate idempotency: Re-recording an existing [DirectBootMissedRecord.recordId] returns
 *   [MissedRecordResult.DuplicateIgnored] without modifying storage. Duplicate check strictly precedes capacity check.
 * - Non-Destructive ACK Invariant (P3-B-C1 Safety Correction):
 *   P3-D does not yet provide qualified Credential-Encrypted (CE) takeover evidence. Destructive deletion of durable
 *   records is strictly disabled in this slice. [acknowledgeRecord] always preserves all records intact and returns
 *   explicit [MissedAckResult.AckNotQualified]. No unprovable ownership tokens or booleans are accepted.
 * - Typed Persistence Uncertainty (P3-B-C1 Safety Correction):
 *   On commit false or commit exception, [recordMissed] never infers durable success (even if an in-memory readback
 *   observes the intended payload). It returns typed [MissedRecordResult.PersistenceUncertain] containing readback observation
 *   ([CommitReadbackObservation]), prior count, and raw payload after.
 * - P3-C Pure Serialization Compatibility:
 *   Exposes [serializeRecordsToPayload] and [serializeRecordsToJson] for future atomic Queue+Missed single-editor transactions.
 * - Concurrency & Single-Writer Contract:
 *   Synchronous [SharedPreferences.Editor.commit] with in-process [storageLock] ([DirectBootStorageLock.lock])
 *   provides serialized single-writer thread safety within the host process.
 * - Typed Read Boundary:
 *   [readUnacknowledgedRecords] returns a typed [MissedStoreStorageState], preventing ambiguous empty-list
 *   interpretations for corrupt or unsupported storage.
 * - P3-A Policy State Conversion:
 *   [toPolicyMissedStorageState] maps verified records to [MissedStorageState] defined in [DirectBootOverduePolicyEngine].
 */
object DirectBootMissedStore {
    private const val TAG = "DirectBootMissedStore"
    const val PREFS_NAME = "direct_boot_next_alarm"
    const val KEY_MISSED_STORE_PAYLOAD = "direct_boot_missed_store_payload"
    const val MAX_CAPACITY = 16
    const val CURRENT_SCHEMA_VERSION = 2

    internal val storageLock: Any get() = DirectBootStorageLock.lock

    /**
     * Testable persistence execution seam.
     * By default invokes synchronous [SharedPreferences.Editor.commit].
     */
    @androidx.annotation.VisibleForTesting
    internal var commitSeam: (SharedPreferences.Editor) -> CommitExecutionOutcome = { editor ->
        if (editor.commit()) CommitExecutionOutcome.Success else CommitExecutionOutcome.CommitFalse
    }

    @androidx.annotation.VisibleForTesting
    internal var readbackSeam: (SharedPreferences) -> String? = { prefs ->
        prefs.getString(KEY_MISSED_STORE_PAYLOAD, null)
    }

    @androidx.annotation.VisibleForTesting
    internal fun resetSeamsForTesting() {
        commitSeam = { editor ->
            if (editor.commit()) CommitExecutionOutcome.Success else CommitExecutionOutcome.CommitFalse
        }
        readbackSeam = { prefs ->
            prefs.getString(KEY_MISSED_STORE_PAYLOAD, null)
        }
    }

    /**
     * Inspects the current storage state of the Missed Record Store.
     */
    fun inspectStorageState(context: Context): MissedStoreStorageState {
        val rawPayload = readbackSeam(prefs(context))
        return parseStorageState(rawPayload)
    }

    /**
     * Exposes the explicit classification of the Missed Record Store storage.
     */
    fun classifyStorage(context: Context): MissedStoreClassification {
        return inspectStorageState(context).classification
    }

    /**
     * Reads all unacknowledged missed records as a typed [MissedStoreStorageState].
     * Callers must explicitly handle ABSENT, VALID_EMPTY, VALID_WITH_RECORDS, CORRUPT, UNSUPPORTED_SCHEMA, and PERSISTENCE_UNCERTAIN.
     */
    fun readUnacknowledgedRecords(context: Context): MissedStoreStorageState {
        return inspectStorageState(context)
    }

    /**
     * Records an unacknowledged missed occurrence to the DE Missed Store.
     *
     * Invariants:
     * - Validates record eligibility ([DirectBootMissedRecord.isValid]).
     * - Rejects record if storage is [MissedStoreClassification.CORRUPT] or [MissedStoreClassification.UNSUPPORTED_SCHEMA],
     *   leaving raw payload completely untouched.
     * - Returns [MissedRecordResult.DuplicateIgnored] if record with same recordId is already present (checked before capacity).
     * - Returns [MissedRecordResult.CapacityExhausted] if store already contains [MAX_CAPACITY] records without modifying storage.
     * - Persists via [commitSeam]. On commit false or exception, reads back storage state and returns typed
     *   [MissedRecordResult.PersistenceUncertain]. Never reports false success.
     */
    fun recordMissed(context: Context, record: DirectBootMissedRecord): MissedRecordResult {
        val validation = record.evaluateValidation()
        if (validation != MissedValidationStatus.VALID) {
            return MissedRecordResult.RejectedIneligible(validation)
        }

        synchronized(storageLock) {
            val state = inspectStorageState(context)
            val currentRecords: MutableList<DirectBootMissedRecord> = when (state) {
                is MissedStoreStorageState.Absent -> mutableListOf()
                is MissedStoreStorageState.ValidEmpty -> mutableListOf()
                is MissedStoreStorageState.ValidWithRecords -> state.records.toMutableList()
                is MissedStoreStorageState.Corrupt -> {
                    Log.w(TAG, "Record rejected: storage is corrupt (${state.reason})")
                    return MissedRecordResult.StorageCorrupt(state.reason)
                }
                is MissedStoreStorageState.UnsupportedSchema -> {
                    Log.w(TAG, "Record rejected: unsupported schema version ${state.foundVersion} (expected ${state.expectedVersion})")
                    return MissedRecordResult.UnsupportedSchema(state.foundVersion, state.expectedVersion)
                }
                is MissedStoreStorageState.PersistenceUncertain -> {
                    Log.w(TAG, "Record rejected: storage state uncertain (${state.reason})")
                    return MissedRecordResult.PersistenceUncertain(
                        reason = "Storage state uncertain: ${state.reason}",
                        observation = CommitReadbackObservation.UNAVAILABLE_OR_UNKNOWN,
                        priorCount = 0,
                        rawPayloadAfter = getRawPayload(context)
                    )
                }
            }

            // Idempotency: duplicate recordId with same payload is ignored without modifying storage (MUST precede capacity check)
            val existing = currentRecords.find { it.recordId == record.recordId }
            if (existing != null) {
                if (existing.restoreEpoch == record.restoreEpoch &&
                    existing.missedAt == record.missedAt &&
                    existing.classification == record.classification
                ) {
                    return MissedRecordResult.DuplicateIgnored(existing, currentRecords.size)
                } else {
                    Log.w(TAG, "Record rejected: record ${record.recordId} exists with conflicting payload")
                    return MissedRecordResult.ContentConflict(existing, record)
                }
            }

            // Capacity safety: at capacity, fail closed without evicting prior unacknowledged records
            if (currentRecords.size >= MAX_CAPACITY) {
                return MissedRecordResult.CapacityExhausted(currentRecords.size, MAX_CAPACITY)
            }

            val priorCount = currentRecords.size
            val priorRawPayload = getRawPayload(context)

            currentRecords.add(record)
            val serializedNewPayload = serializeRecordsToJson(currentRecords)

            val outcome = try {
                commitSeam(prefs(context).edit().putString(KEY_MISSED_STORE_PAYLOAD, serializedNewPayload))
            } catch (t: Throwable) {
                CommitExecutionOutcome.Threw(t)
            }

            return when (outcome) {
                is CommitExecutionOutcome.Success -> {
                    MissedRecordResult.Recorded(record, currentRecords.size)
                }
                is CommitExecutionOutcome.CommitFalse -> {
                    val observation = evaluateReadback(context, priorRawPayload, serializedNewPayload)
                    MissedRecordResult.PersistenceUncertain(
                        reason = "SharedPreferences commit returned false",
                        observation = observation,
                        priorCount = priorCount,
                        rawPayloadAfter = getRawPayload(context)
                    )
                }
                is CommitExecutionOutcome.Threw -> {
                    val observation = evaluateReadback(context, priorRawPayload, serializedNewPayload)
                    MissedRecordResult.PersistenceUncertain(
                        reason = "SharedPreferences commit threw exception: ${outcome.throwable.message}",
                        observation = observation,
                        priorCount = priorCount,
                        rawPayloadAfter = getRawPayload(context)
                    )
                }
            }
        }
    }

    /**
     * Non-destructive ACK evaluation for Direct Boot Missed Records (P3-B-C1 Safety Correction).
     *
     * Invariants:
     * - Destructive deletion is DISABLED in this slice because P3-D CE durable handover is not yet implemented.
     * - Preserves ALL existing unacknowledged records without mutation.
     * - Rejects ACK if storage is [MissedStoreClassification.CORRUPT] or [MissedStoreClassification.UNSUPPORTED_SCHEMA],
     *   leaving raw payload completely untouched.
     * - Returns explicit [MissedAckResult.AckNotQualified].
     * - Zero unprovable ownership tokens or synthetic success claims.
     */
    fun acknowledgeRecord(context: Context, recordId: String): MissedAckResult {
        if (recordId.isBlank()) {
            return MissedAckResult.RejectedInvalidId("RecordId must not be blank")
        }

        synchronized(storageLock) {
            val state = inspectStorageState(context)
            return when (state) {
                is MissedStoreStorageState.Absent -> {
                    MissedAckResult.AckNotQualified(
                        reason = "Record not eligible for ACK (storage absent)",
                        currentCount = 0
                    )
                }
                is MissedStoreStorageState.ValidEmpty -> {
                    MissedAckResult.AckNotQualified(
                        reason = "Record not eligible for ACK (storage empty)",
                        currentCount = 0
                    )
                }
                is MissedStoreStorageState.ValidWithRecords -> {
                    // Non-destructive: preserve all records intact
                    MissedAckResult.AckNotQualified(
                        reason = "CE durable ownership handover is not qualified in Direct Boot slice P3-B/P3-B-C1 (requires P3-D qualification)",
                        currentCount = state.records.size
                    )
                }
                is MissedStoreStorageState.Corrupt -> {
                    Log.w(TAG, "Acknowledge rejected: storage is corrupt (${state.reason})")
                    MissedAckResult.StorageCorrupt(state.reason)
                }
                is MissedStoreStorageState.UnsupportedSchema -> {
                    Log.w(TAG, "Acknowledge rejected: unsupported schema version ${state.foundVersion} (expected ${state.expectedVersion})")
                    MissedAckResult.UnsupportedSchema(state.foundVersion, state.expectedVersion)
                }
                is MissedStoreStorageState.PersistenceUncertain -> {
                    Log.w(TAG, "Acknowledge rejected: storage state uncertain (${state.reason})")
                    MissedAckResult.AckNotQualified(
                        reason = "Storage state uncertain: ${state.reason}",
                        currentCount = 0
                    )
                }
            }
        }
    }

    /**
     * Pure helper to construct JSON payload object for missed records.
     * Reusable for future atomic Queue+Missed single-editor transactions (P3-C).
     */
    fun serializeRecordsToPayload(records: List<DirectBootMissedRecord>): JSONObject {
        val root = JSONObject().apply {
            put("schemaVersion", CURRENT_SCHEMA_VERSION)
            val array = JSONArray()
            records.forEach { array.put(it.toJson()) }
            put("records", array)
        }
        return root
    }

    /**
     * Pure helper to serialize missed records to JSON string.
     * Reusable for future atomic Queue+Missed single-editor transactions (P3-C).
     */
    fun serializeRecordsToJson(records: List<DirectBootMissedRecord>): String {
        return serializeRecordsToPayload(records).toString()
    }

    /**
     * Evaluates readback observation after a failed or uncertain commit.
     */
    internal fun evaluateReadback(
        context: Context,
        priorRawPayload: String?,
        intendedRawPayload: String
    ): CommitReadbackObservation {
        return try {
            val rawAfter = getRawPayload(context)
            when {
                rawAfter == intendedRawPayload -> CommitReadbackObservation.INTENDED_PAYLOAD_OBSERVED
                rawAfter == priorRawPayload -> CommitReadbackObservation.PRIOR_PAYLOAD_OBSERVED
                rawAfter != null && parseStorageState(rawAfter) is MissedStoreStorageState.Corrupt ->
                    CommitReadbackObservation.CORRUPT_PAYLOAD_OBSERVED
                else -> CommitReadbackObservation.UNAVAILABLE_OR_UNKNOWN
            }
        } catch (t: Throwable) {
            CommitReadbackObservation.UNAVAILABLE_OR_UNKNOWN
        }
    }

    /**
     * Diagnostic helper: returns the raw serialized missed store payload string.
     */
    fun getRawPayload(context: Context): String? {
        return readbackSeam(prefs(context))
    }

    internal fun getDePrefs(context: Context): SharedPreferences = prefs(context)

    /**
     * Parses the raw string payload and classifies the storage state.
     * Enforces fail-closed rules:
     * - More than [MAX_CAPACITY] records is CORRUPT.
     * - Duplicate recordIds in storage is CORRUPT.
     * - Unknown classification is strictly CORRUPT (no defaulting).
     * - Any malformed record is CORRUPT.
     * - Unsupported schema version is UNSUPPORTED_SCHEMA.
     */
    fun parseStorageState(rawPayload: String?): MissedStoreStorageState {
        if (rawPayload == null) {
            return MissedStoreStorageState.Absent
        }
        if (rawPayload.isBlank()) {
            return MissedStoreStorageState.Corrupt("Blank payload string")
        }
        return try {
            val root = JSONObject(rawPayload)
            if (!root.has("schemaVersion")) {
                return MissedStoreStorageState.Corrupt("Missing schemaVersion field")
            }
            val schemaVersion = root.optInt("schemaVersion", -1)
            if (schemaVersion != CURRENT_SCHEMA_VERSION && schemaVersion != 1) {
                return MissedStoreStorageState.UnsupportedSchema(schemaVersion, CURRENT_SCHEMA_VERSION)
            }
            if (!root.has("records")) {
                return MissedStoreStorageState.Corrupt("Missing records array")
            }
            val recordsArray = root.optJSONArray("records")
                ?: return MissedStoreStorageState.Corrupt("records is not a JSON array")

            if (recordsArray.length() == 0) {
                return MissedStoreStorageState.ValidEmpty
            }

            // Hardening: Stored records exceeding MAX_CAPACITY is strictly CORRUPT
            if (recordsArray.length() > MAX_CAPACITY) {
                return MissedStoreStorageState.Corrupt(
                    "Stored records count (${recordsArray.length()}) exceeds MAX_CAPACITY ($MAX_CAPACITY)"
                )
            }

            val list = ArrayList<DirectBootMissedRecord>(recordsArray.length())
            val seenRecordIds = HashSet<String>(recordsArray.length())
            for (i in 0 until recordsArray.length()) {
                val recordObj = recordsArray.optJSONObject(i)
                    ?: return MissedStoreStorageState.Corrupt("Record at index $i is not a JSON object")
                val record = try {
                    DirectBootMissedRecord.fromJson(recordObj)
                } catch (e: Exception) {
                    return MissedStoreStorageState.Corrupt("Failed to parse record at index $i: ${e.message}")
                }
                val validation = record.evaluateValidation()
                if (validation != MissedValidationStatus.VALID) {
                    return MissedStoreStorageState.Corrupt("Invalid record at index $i: $validation")
                }
                // Hardening: Persisted duplicate recordIds is strictly CORRUPT
                if (!seenRecordIds.add(record.recordId)) {
                    return MissedStoreStorageState.Corrupt(
                        "Duplicate recordId encountered in storage: ${record.recordId}"
                    )
                }
                list.add(record)
            }
            MissedStoreStorageState.ValidWithRecords(list)
        } catch (e: Exception) {
            MissedStoreStorageState.Corrupt("JSON parsing failed: ${e.message}")
        }
    }

    /**
     * Converts the current store state to the accepted P3-A [MissedStorageState] hierarchy.
     */
    fun toPolicyState(context: Context): MissedStorageState {
        return inspectStorageState(context).toPolicyMissedStorageState()
    }

    private fun prefs(context: Context): SharedPreferences {
        val deCtx = if (context.isDeviceProtectedStorage) context else context.createDeviceProtectedStorageContext()
        return deCtx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }
}

internal sealed class CommitExecutionOutcome {
    object Success : CommitExecutionOutcome()
    object CommitFalse : CommitExecutionOutcome()
    data class Threw(val throwable: Throwable) : CommitExecutionOutcome()
}

enum class CommitReadbackObservation {
    INTENDED_PAYLOAD_OBSERVED,
    PRIOR_PAYLOAD_OBSERVED,
    CORRUPT_PAYLOAD_OBSERVED,
    UNAVAILABLE_OR_UNKNOWN
}

enum class MissedStoreClassification {
    ABSENT,
    VALID_EMPTY,
    VALID_WITH_RECORDS,
    CORRUPT,
    UNSUPPORTED_SCHEMA,
    PERSISTENCE_UNCERTAIN
}

sealed class MissedStoreStorageState {
    abstract val classification: MissedStoreClassification

    object Absent : MissedStoreStorageState() {
        override val classification = MissedStoreClassification.ABSENT
    }

    object ValidEmpty : MissedStoreStorageState() {
        override val classification = MissedStoreClassification.VALID_EMPTY
    }

    data class ValidWithRecords(val records: List<DirectBootMissedRecord>) : MissedStoreStorageState() {
        override val classification = MissedStoreClassification.VALID_WITH_RECORDS
    }

    data class Corrupt(val reason: String) : MissedStoreStorageState() {
        override val classification = MissedStoreClassification.CORRUPT
    }

    data class UnsupportedSchema(
        val foundVersion: Int,
        val expectedVersion: Int = DirectBootMissedStore.CURRENT_SCHEMA_VERSION
    ) : MissedStoreStorageState() {
        override val classification = MissedStoreClassification.UNSUPPORTED_SCHEMA
    }

    data class PersistenceUncertain(val reason: String) : MissedStoreStorageState() {
        override val classification = MissedStoreClassification.PERSISTENCE_UNCERTAIN
    }
}

/**
 * Mapping extension to P3-A [MissedStorageState] hierarchy.
 */
fun MissedStoreStorageState.toPolicyMissedStorageState(): MissedStorageState = when (this) {
    is MissedStoreStorageState.Absent -> MissedStorageState.VerifiedEmpty
    is MissedStoreStorageState.ValidEmpty -> MissedStorageState.VerifiedEmpty
    is MissedStoreStorageState.ValidWithRecords -> MissedStorageState.VerifiedRecords(records.map { it.recordId }.toSet())
    is MissedStoreStorageState.Corrupt -> MissedStorageState.Corrupt(reason)
    is MissedStoreStorageState.UnsupportedSchema -> MissedStorageState.UnsupportedSchema(foundVersion, expectedVersion)
    is MissedStoreStorageState.PersistenceUncertain -> MissedStorageState.UnknownUncertain(reason)
}

sealed class MissedRecordResult {
    data class Recorded(val record: DirectBootMissedRecord, val totalCount: Int) : MissedRecordResult()
    data class DuplicateIgnored(val existing: DirectBootMissedRecord, val totalCount: Int) : MissedRecordResult()
    data class ContentConflict(val existing: DirectBootMissedRecord, val candidate: DirectBootMissedRecord) : MissedRecordResult()
    data class CapacityExhausted(val currentCount: Int, val maxCapacity: Int) : MissedRecordResult()
    data class RejectedIneligible(val status: MissedValidationStatus) : MissedRecordResult()
    data class StorageCorrupt(val reason: String) : MissedRecordResult()
    data class UnsupportedSchema(val foundVersion: Int, val expectedVersion: Int) : MissedRecordResult()
    data class PersistenceUncertain(
        val reason: String,
        val observation: CommitReadbackObservation,
        val priorCount: Int,
        val rawPayloadAfter: String?
    ) : MissedRecordResult()
}

sealed class MissedAckResult {
    data class AckNotQualified(val reason: String, val currentCount: Int) : MissedAckResult()
    data class RejectedInvalidId(val reason: String) : MissedAckResult()
    data class StorageCorrupt(val reason: String) : MissedAckResult()
    data class UnsupportedSchema(val foundVersion: Int, val expectedVersion: Int) : MissedAckResult()
}
