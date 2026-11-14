package com.sysadmindoc.alarmclock.directboot

import android.content.Context
import android.content.SharedPreferences
import androidx.annotation.VisibleForTesting
import androidx.core.os.UserManagerCompat
import kotlinx.coroutines.CancellationException
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * Inactive Credential-Encrypted (CE) Takeover Evidence Store for KNO-75 (Phase D3-C2-P3D-P1).
 *
 * NOTE ON PROVISIONAL CAPACITY (PROVISIONAL_MAX_CAPACITY = 32):
 * This store is an unconsumed takeover evidence buffer, NOT a permanent alarm history database.
 * PROVISIONAL_MAX_CAPACITY = 32 is a provisional safety ceiling for Slice P3-D-P1 only.
 * Because automatic eviction and DE destructive ACK are strictly prohibited in P1, accumulating 32
 * unconsumed CE takeover records will cause subsequent intakes to halt with [CeTakeoverIntakeResult.Rejected.CapacityBlocked]
 * while preserving both existing CE evidence and DE source records. Downstream CE consumer processing proof,
 * retention window, re-intake suppression, and capacity drainage remain an explicit open specification item for P2+.
 */
object DirectBootCeTakeoverStore {
    const val PREFS_NAME = "direct_boot_ce_takeover_store"
    const val KEY_CE_TAKEOVER_PAYLOAD = "direct_boot_ce_takeover_payload"
    const val CURRENT_SCHEMA_VERSION = 2

    /**
     * Provisional safety ceiling for unconsumed takeover evidence in P3-D-P1.
     * Not a permanent historical capacity.
     */
    const val PROVISIONAL_MAX_CAPACITY = 32

    internal val ceStorageLock = Any()

    @VisibleForTesting
    internal var unlockGateSeam: (Context) -> Boolean = { ctx ->
        try {
            UserManagerCompat.isUserUnlocked(ctx)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            false
        }
    }

    @VisibleForTesting
    internal var commitSeam: (SharedPreferences, String) -> Boolean = { prefs, payload ->
        prefs.edit().putString(KEY_CE_TAKEOVER_PAYLOAD, payload).commit()
    }

    @VisibleForTesting
    internal var readbackSeam: (SharedPreferences) -> String? = { prefs ->
        prefs.getString(KEY_CE_TAKEOVER_PAYLOAD, null)
    }

    internal fun resetSeamsForTesting() {
        unlockGateSeam = { ctx ->
            try {
                UserManagerCompat.isUserUnlocked(ctx)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                false
            }
        }
        commitSeam = { prefs, payload ->
            prefs.edit().putString(KEY_CE_TAKEOVER_PAYLOAD, payload).commit()
        }
        readbackSeam = { prefs ->
            prefs.getString(KEY_CE_TAKEOVER_PAYLOAD, null)
        }
    }

    fun computeOccurrenceKey(
        alarmId: Long,
        triggerTime: Long,
        generation: Int,
        instanceToken: String
    ): String {
        return "ce_takeover_normal_${alarmId}_gen${generation}_${instanceToken}_trig${triggerTime}"
    }

    private fun validateCeContextAndUnlock(
        ceContext: Context,
        unlockGate: (Context) -> Boolean
    ): CeTakeoverStorageState? {
        if (ceContext.isDeviceProtectedStorage) {
            return CeTakeoverStorageState.CeContextRejected(
                "Context is device-protected storage (DE); CE Takeover Store strictly requires Credential-Encrypted (CE) context"
            )
        }
        val unlocked = try {
            unlockGate(ceContext)
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            return CeTakeoverStorageState.CeLocked(
                "Failed to verify user unlock state: ${t.javaClass.simpleName}: ${t.message}"
            )
        }
        if (!unlocked) {
            return CeTakeoverStorageState.CeLocked("User credential-encrypted (CE) storage is locked")
        }
        return null
    }

    private fun getCePrefs(ceContext: Context): SharedPreferences {
        // Strictly use CE context directly; NEVER call createDeviceProtectedStorageContext()
        return ceContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    fun inspectStorageState(ceContext: Context): CeTakeoverStorageState {
        return inspectStorageState(ceContext, unlockGateSeam)
    }

    @VisibleForTesting
    internal fun inspectStorageState(
        ceContext: Context,
        unlockGate: (Context) -> Boolean
    ): CeTakeoverStorageState {
        validateCeContextAndUnlock(ceContext, unlockGate)?.let { return it }
        synchronized(ceStorageLock) {
            val prefs = getCePrefs(ceContext)
            val raw = try {
                readbackSeam(prefs)
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                return CeTakeoverStorageState.Corrupt(
                    "Exception reading CE takeover payload: ${t.javaClass.simpleName}: ${t.message}"
                )
            }
            return parseStorageState(raw)
        }
    }

    fun parseStorageState(rawPayload: String?): CeTakeoverStorageState {
        if (rawPayload == null) return CeTakeoverStorageState.Absent
        if (rawPayload.isBlank()) {
            return CeTakeoverStorageState.Corrupt("CE takeover payload is blank")
        }
        return try {
            val root = JSONObject(rawPayload)
            if (!root.has("schemaVersion")) {
                return CeTakeoverStorageState.Corrupt("Missing 'schemaVersion' field in CE takeover payload")
            }
            val schemaVersion = root.optInt("schemaVersion", -1)
            if (schemaVersion <= 0) {
                return CeTakeoverStorageState.Corrupt("Invalid non-positive schemaVersion: $schemaVersion")
            }
            if (schemaVersion != CURRENT_SCHEMA_VERSION && schemaVersion != 1) {
                return CeTakeoverStorageState.UnsupportedSchema(
                    foundVersion = schemaVersion,
                    expectedVersion = CURRENT_SCHEMA_VERSION
                )
            }
            val recordsArray = root.optJSONArray("records")
                ?: return CeTakeoverStorageState.Corrupt("Missing 'records' array in CE takeover payload")

            if (recordsArray.length() > PROVISIONAL_MAX_CAPACITY) {
                return CeTakeoverStorageState.Corrupt(
                    "CE takeover record count ${recordsArray.length()} exceeds PROVISIONAL_MAX_CAPACITY ($PROVISIONAL_MAX_CAPACITY)"
                )
            }

            val parsedRecords = ArrayList<CeTakeoverRecord>(recordsArray.length())
            val seenOccurrenceKeys = HashSet<String>(recordsArray.length())
            val seenSourceRecordIds = HashSet<String>(recordsArray.length())

            for (i in 0 until recordsArray.length()) {
                val itemObj = recordsArray.optJSONObject(i)
                    ?: return CeTakeoverStorageState.Corrupt("Element at index $i is not a valid JSONObject")
                val record = CeTakeoverRecord.fromJson(itemObj)
                val validationError = validateParsedCeRecord(record)
                if (validationError != null) {
                    return CeTakeoverStorageState.Corrupt("Invalid CE takeover record at index $i: $validationError")
                }
                if (!seenOccurrenceKeys.add(record.occurrenceKey)) {
                    return CeTakeoverStorageState.Corrupt("Duplicate occurrenceKey '${record.occurrenceKey}' at index $i")
                }
                if (!seenSourceRecordIds.add(record.sourceRecordId)) {
                    return CeTakeoverStorageState.Corrupt("Duplicate sourceRecordId '${record.sourceRecordId}' at index $i")
                }
                parsedRecords.add(record)
            }

            if (parsedRecords.isEmpty()) {
                CeTakeoverStorageState.ValidEmpty
            } else {
                CeTakeoverStorageState.ValidWithRecords(parsedRecords)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            CeTakeoverStorageState.Corrupt("Malformed CE takeover JSON: ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    private fun validateParsedCeRecord(record: CeTakeoverRecord): String? {
        if (record.kind != DirectBootDeliveryRecord.KIND_NORMAL) {
            return "Unsupported kind '${record.kind}'"
        }
        if (record.alarmId <= 0L) return "Non-positive alarmId ${record.alarmId}"
        if (record.triggerTime <= 0L) return "Non-positive triggerTime ${record.triggerTime}"
        if (record.generation < 1) return "Invalid generation ${record.generation}"
        if (record.instanceToken.isBlank()) return "Blank instanceToken"
        if (record.recordedAtEpochMs <= 0L) return "Non-positive recordedAtEpochMs ${record.recordedAtEpochMs}"
        if (record.schemaVersion != CURRENT_SCHEMA_VERSION && record.schemaVersion != 1) {
            return "Record schemaVersion ${record.schemaVersion} != $CURRENT_SCHEMA_VERSION"
        }
        val expectedOccKey = computeOccurrenceKey(
            alarmId = record.alarmId,
            triggerTime = record.triggerTime,
            generation = record.generation,
            instanceToken = record.instanceToken
        )
        if (record.occurrenceKey != expectedOccKey) {
            return "Mismatched occurrenceKey: expected '$expectedOccKey', got '${record.occurrenceKey}'"
        }
        return when (record) {
            is CeTakeoverRecord.Delivered -> {
                if (record.deliveredAt <= 0L) return "Non-positive deliveredAt ${record.deliveredAt}"
                val expectedSourceId = DirectBootDeliveryRecord.computeRecordId(
                    kind = record.kind,
                    alarmId = record.alarmId,
                    triggerTime = record.triggerTime,
                    generation = record.generation,
                    instanceToken = record.instanceToken
                )
                if (record.sourceRecordId != expectedSourceId) {
                    return "Mismatched Delivered sourceRecordId: expected '$expectedSourceId', got '${record.sourceRecordId}'"
                }
                null
            }
            is CeTakeoverRecord.Missed -> {
                if (record.missedAt <= 0L) return "Non-positive missedAt ${record.missedAt}"
                if (record.classification !in DirectBootMissedRecord.APPROVED_CLASSIFICATIONS) {
                    return "Invalid classification '${record.classification}'"
                }
                val expectedSourceId = DirectBootMissedRecord.computeRecordId(
                    alarmId = record.alarmId,
                    triggerTime = record.triggerTime,
                    generation = record.generation,
                    instanceToken = record.instanceToken
                )
                if (record.sourceRecordId != expectedSourceId) {
                    return "Mismatched Missed sourceRecordId: expected '$expectedSourceId', got '${record.sourceRecordId}'"
                }
                null
            }
        }
    }

    fun recordDelivered(
        ceContext: Context,
        deliveryRecord: DirectBootDeliveryRecord,
        recordedAtEpochMs: Long
    ): CeTakeoverIntakeResult {
        return recordDelivered(
            ceContext = ceContext,
            deliveryRecord = deliveryRecord,
            recordedAtEpochMs = recordedAtEpochMs,
            unlockGate = unlockGateSeam,
            preCommitHook = {},
            postCommitHook = {}
        )
    }

    @VisibleForTesting
    internal fun recordDelivered(
        ceContext: Context,
        deliveryRecord: DirectBootDeliveryRecord,
        recordedAtEpochMs: Long,
        unlockGate: (Context) -> Boolean,
        preCommitHook: () -> Unit = {},
        postCommitHook: () -> Unit = {}
    ): CeTakeoverIntakeResult {
        when (val gateState = validateCeContextAndUnlock(ceContext, unlockGate)) {
            is CeTakeoverStorageState.CeContextRejected ->
                return CeTakeoverIntakeResult.Rejected.CeContextRejected(gateState.reason)
            is CeTakeoverStorageState.CeLocked ->
                return CeTakeoverIntakeResult.Rejected.CeLocked(gateState.reason)
            else -> Unit
        }

        if (deliveryRecord.kind == "QUICK") {
            return CeTakeoverIntakeResult.Rejected.QuickNotQualified(
                "QUICK delivery records are out of scope and not qualified for CE takeover store"
            )
        }
        if (deliveryRecord.kind != DirectBootDeliveryRecord.KIND_NORMAL) {
            return CeTakeoverIntakeResult.Rejected.InvalidRecord("Unsupported kind: '${deliveryRecord.kind}'")
        }
        if (deliveryRecord.alarmId <= 0L ||
            deliveryRecord.triggerTime <= 0L ||
            deliveryRecord.deliveredAt <= 0L ||
            deliveryRecord.generation < 1 ||
            deliveryRecord.instanceToken.isBlank() ||
            (deliveryRecord.schemaVersion != DirectBootDeliveryLedger.CURRENT_SCHEMA_VERSION && deliveryRecord.schemaVersion != 1) ||
            recordedAtEpochMs <= 0L
        ) {
            return CeTakeoverIntakeResult.Rejected.InvalidRecord(
                "Invalid DirectBootDeliveryRecord fields or recordedAtEpochMs: $deliveryRecord (recordedAt=$recordedAtEpochMs)"
            )
        }

        val expectedRecordId = DirectBootDeliveryRecord.computeRecordId(
            kind = deliveryRecord.kind,
            alarmId = deliveryRecord.alarmId,
            triggerTime = deliveryRecord.triggerTime,
            generation = deliveryRecord.generation,
            instanceToken = deliveryRecord.instanceToken
        )
        if (deliveryRecord.recordId != expectedRecordId) {
            return CeTakeoverIntakeResult.Rejected.InvalidRecord(
                "Forged or mismatched delivery recordId: expected '$expectedRecordId', got '${deliveryRecord.recordId}'"
            )
        }

        val incoming = CeTakeoverRecord.fromDeliveryRecord(deliveryRecord, recordedAtEpochMs)
        return recordInternal(ceContext, incoming, preCommitHook, postCommitHook)
    }

    fun recordMissed(
        ceContext: Context,
        missedRecord: DirectBootMissedRecord,
        recordedAtEpochMs: Long
    ): CeTakeoverIntakeResult {
        return recordMissed(
            ceContext = ceContext,
            missedRecord = missedRecord,
            recordedAtEpochMs = recordedAtEpochMs,
            unlockGate = unlockGateSeam,
            preCommitHook = {},
            postCommitHook = {}
        )
    }

    @VisibleForTesting
    internal fun recordMissed(
        ceContext: Context,
        missedRecord: DirectBootMissedRecord,
        recordedAtEpochMs: Long,
        unlockGate: (Context) -> Boolean,
        preCommitHook: () -> Unit = {},
        postCommitHook: () -> Unit = {}
    ): CeTakeoverIntakeResult {
        when (val gateState = validateCeContextAndUnlock(ceContext, unlockGate)) {
            is CeTakeoverStorageState.CeContextRejected ->
                return CeTakeoverIntakeResult.Rejected.CeContextRejected(gateState.reason)
            is CeTakeoverStorageState.CeLocked ->
                return CeTakeoverIntakeResult.Rejected.CeLocked(gateState.reason)
            else -> Unit
        }

        if (missedRecord.kind == "QUICK") {
            return CeTakeoverIntakeResult.Rejected.QuickNotQualified(
                "QUICK missed records are out of scope and not qualified for CE takeover store"
            )
        }
        if (missedRecord.kind != DirectBootMissedRecord.KIND_NORMAL) {
            return CeTakeoverIntakeResult.Rejected.InvalidRecord("Unsupported kind: '${missedRecord.kind}'")
        }
        if (missedRecord.alarmId <= 0L ||
            missedRecord.triggerTime <= 0L ||
            missedRecord.missedAt <= 0L ||
            missedRecord.generation < 1 ||
            missedRecord.instanceToken.isBlank() ||
            missedRecord.classification !in DirectBootMissedRecord.APPROVED_CLASSIFICATIONS ||
            (missedRecord.schemaVersion != DirectBootMissedStore.CURRENT_SCHEMA_VERSION && missedRecord.schemaVersion != 1) ||
            recordedAtEpochMs <= 0L
        ) {
            return CeTakeoverIntakeResult.Rejected.InvalidRecord(
                "Invalid DirectBootMissedRecord fields or recordedAtEpochMs: $missedRecord (recordedAt=$recordedAtEpochMs)"
            )
        }

        val expectedRecordId = DirectBootMissedRecord.computeRecordId(
            alarmId = missedRecord.alarmId,
            triggerTime = missedRecord.triggerTime,
            generation = missedRecord.generation,
            instanceToken = missedRecord.instanceToken
        )
        if (missedRecord.recordId != expectedRecordId) {
            return CeTakeoverIntakeResult.Rejected.InvalidRecord(
                "Forged or mismatched missed recordId: expected '$expectedRecordId', got '${missedRecord.recordId}'"
            )
        }

        val incoming = CeTakeoverRecord.fromMissedRecord(missedRecord, recordedAtEpochMs)
        return recordInternal(ceContext, incoming, preCommitHook, postCommitHook)
    }

    private fun recordInternal(
        ceContext: Context,
        incoming: CeTakeoverRecord,
        preCommitHook: () -> Unit,
        postCommitHook: () -> Unit
    ): CeTakeoverIntakeResult {
        synchronized(ceStorageLock) {
            val prefs = getCePrefs(ceContext)
            val rawBefore = try {
                readbackSeam(prefs)
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                return CeTakeoverIntakeResult.Rejected.StorageCorrupt(
                    "Exception reading existing CE takeover store: ${t.javaClass.simpleName}: ${t.message}"
                )
            }

            val existingRecords: List<CeTakeoverRecord> = when (val state = parseStorageState(rawBefore)) {
                is CeTakeoverStorageState.Absent,
                is CeTakeoverStorageState.ValidEmpty -> emptyList()
                is CeTakeoverStorageState.ValidWithRecords -> state.records
                is CeTakeoverStorageState.Corrupt ->
                    return CeTakeoverIntakeResult.Rejected.StorageCorrupt(state.reason)
                is CeTakeoverStorageState.UnsupportedSchema ->
                    return CeTakeoverIntakeResult.Rejected.UnsupportedSchema(
                        foundVersion = state.foundVersion,
                        expectedVersion = state.expectedVersion
                    )
                is CeTakeoverStorageState.CeLocked ->
                    return CeTakeoverIntakeResult.Rejected.CeLocked(state.reason)
                is CeTakeoverStorageState.CeContextRejected ->
                    return CeTakeoverIntakeResult.Rejected.CeContextRejected(state.reason)
            }

            val existingMatch = existingRecords.firstOrNull {
                it.occurrenceKey == incoming.occurrenceKey || it.sourceRecordId == incoming.sourceRecordId
            }

            if (existingMatch != null) {
                // Check Delivered vs Missed mutual exclusion
                if (existingMatch.recordType != incoming.recordType) {
                    return CeTakeoverIntakeResult.Rejected.DeliveredMissedConflict(
                        existingRecord = existingMatch,
                        incomingRecord = incoming,
                        details = "Mutual exclusion violation for occurrence '${incoming.occurrenceKey}': " +
                            "existing is ${existingMatch.recordType}, incoming is ${incoming.recordType}"
                    )
                }

                // Same recordType -> verify semantic content match
                val contentMatches = when {
                    existingMatch is CeTakeoverRecord.Delivered && incoming is CeTakeoverRecord.Delivered ->
                        existingMatch.matchesSemanticContent(incoming)
                    existingMatch is CeTakeoverRecord.Missed && incoming is CeTakeoverRecord.Missed ->
                        existingMatch.matchesSemanticContent(incoming)
                    else -> false
                }

                if (!contentMatches) {
                    return CeTakeoverIntakeResult.Rejected.ContentConflict(
                        existingRecord = existingMatch,
                        incomingRecord = incoming,
                        details = "Content conflict for occurrence '${incoming.occurrenceKey}'"
                    )
                }

                // Content matches -> verify independent CE readback before confirming idempotency
                return when (val verify = verifyRecordReadableInCeInternal(prefs, existingMatch)) {
                    is CeReadbackVerificationResult.Verified ->
                        CeTakeoverIntakeResult.AlreadyRecordedConfirmed(
                            existingRecord = verify.record,
                            totalRecords = verify.totalRecords
                        )
                    is CeReadbackVerificationResult.Unverified ->
                        CeTakeoverIntakeResult.Uncertain.ReadbackMismatch(
                            details = verify.reason,
                            observedState = verify.observedState
                        )
                }
            }

            if (existingRecords.size >= PROVISIONAL_MAX_CAPACITY) {
                return CeTakeoverIntakeResult.Rejected.CapacityBlocked(
                    currentCount = existingRecords.size,
                    provisionalMaxCapacity = PROVISIONAL_MAX_CAPACITY
                )
            }

            try {
                preCommitHook()
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                return CeTakeoverIntakeResult.Rejected.PreCommitFailure(
                    details = "preCommitHook failed prior to CE commit: ${t.javaClass.simpleName}: ${t.message}",
                    cause = t
                )
            }

            val updatedRecords = existingRecords + incoming
            val payloadJson = serializePayload(updatedRecords)

            val commitSucceeded = try {
                commitSeam(prefs, payloadJson)
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                return CeTakeoverIntakeResult.Uncertain.CommitException(
                    details = "Synchronous CE commit() threw ${t.javaClass.simpleName}: ${t.message}",
                    cause = t
                )
            }

            if (!commitSucceeded) {
                return CeTakeoverIntakeResult.Uncertain.CommitFailed(
                    details = "SharedPreferences.Editor.commit() returned false for CE takeover store"
                )
            }

            // Independent CE readback verification after commit() == true
            val readbackVerification = verifyRecordReadableInCeInternal(prefs, incoming)
            val confirmedRecord: CeTakeoverRecord
            val confirmedTotal: Int
            when (readbackVerification) {
                is CeReadbackVerificationResult.Verified -> {
                    confirmedRecord = readbackVerification.record
                    confirmedTotal = readbackVerification.totalRecords
                }
                is CeReadbackVerificationResult.Unverified -> {
                    return CeTakeoverIntakeResult.Uncertain.ReadbackMismatch(
                        details = readbackVerification.reason,
                        observedState = readbackVerification.observedState
                    )
                }
            }

            // Post-commit hook runs strictly AFTER commit + independent readback are confirmed
            try {
                postCommitHook()
            } catch (e: CancellationException) {
                return CeTakeoverIntakeResult.CancellationAfterConfirm(
                    confirmedRecord = confirmedRecord,
                    totalRecords = confirmedTotal,
                    cancellation = e
                )
            } catch (t: Throwable) {
                return CeTakeoverIntakeResult.PostCommitFailure(
                    confirmedRecord = confirmedRecord,
                    totalRecords = confirmedTotal,
                    cause = t
                )
            }

            return CeTakeoverIntakeResult.RecordedConfirmed(
                record = confirmedRecord,
                totalRecords = confirmedTotal,
                writePerformed = true
            )
        }
    }

    fun verifyRecordReadableInCe(
        ceContext: Context,
        expectedRecord: CeTakeoverRecord
    ): CeReadbackVerificationResult {
        return verifyRecordReadableInCe(ceContext, expectedRecord, unlockGateSeam)
    }

    @VisibleForTesting
    internal fun verifyRecordReadableInCe(
        ceContext: Context,
        expectedRecord: CeTakeoverRecord,
        unlockGate: (Context) -> Boolean
    ): CeReadbackVerificationResult {
        when (val gateState = validateCeContextAndUnlock(ceContext, unlockGate)) {
            is CeTakeoverStorageState.CeContextRejected ->
                return CeReadbackVerificationResult.Unverified(gateState.reason, gateState)
            is CeTakeoverStorageState.CeLocked ->
                return CeReadbackVerificationResult.Unverified(gateState.reason, gateState)
            else -> Unit
        }
        synchronized(ceStorageLock) {
            val prefs = getCePrefs(ceContext)
            return verifyRecordReadableInCeInternal(prefs, expectedRecord)
        }
    }

    private fun verifyRecordReadableInCeInternal(
        prefs: SharedPreferences,
        expectedRecord: CeTakeoverRecord
    ): CeReadbackVerificationResult {
        val rawAfter = try {
            readbackSeam(prefs)
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            return CeReadbackVerificationResult.Unverified(
                reason = "Exception during independent CE readback: ${t.javaClass.simpleName}: ${t.message}"
            )
        }

        val stateAfter = parseStorageState(rawAfter)
        if (stateAfter !is CeTakeoverStorageState.ValidWithRecords) {
            return CeReadbackVerificationResult.Unverified(
                reason = "Independent CE readback did not return ValidWithRecords: $stateAfter",
                observedState = stateAfter
            )
        }

        val found = stateAfter.records.firstOrNull { it.occurrenceKey == expectedRecord.occurrenceKey }
            ?: return CeReadbackVerificationResult.Unverified(
                reason = "Record '${expectedRecord.occurrenceKey}' missing in independent CE readback",
                observedState = stateAfter
            )

        val exactMatch = when {
            found is CeTakeoverRecord.Delivered && expectedRecord is CeTakeoverRecord.Delivered ->
                found.matchesSemanticContent(expectedRecord)
            found is CeTakeoverRecord.Missed && expectedRecord is CeTakeoverRecord.Missed ->
                found.matchesSemanticContent(expectedRecord)
            else -> false
        }

        if (!exactMatch) {
            return CeReadbackVerificationResult.Unverified(
                reason = "Independent CE readback content mismatch for '${expectedRecord.occurrenceKey}': expected=$expectedRecord, found=$found",
                observedState = stateAfter
            )
        }

        return CeReadbackVerificationResult.Verified(
            record = found,
            totalRecords = stateAfter.records.size
        )
    }

    fun clear(ceContext: Context): Boolean {
        validateCeContextAndUnlock(ceContext, unlockGateSeam)?.let { return false }
        synchronized(ceStorageLock) {
            val prefs = getCePrefs(ceContext)
            return try {
                prefs.edit().remove(KEY_CE_TAKEOVER_PAYLOAD).commit()
            } catch (_: Exception) {
                false
            }
        }
    }

    fun releaseSlot(ceContext: Context, occurrenceKey: String): Boolean {
        return releaseSlot(ceContext, occurrenceKey, unlockGateSeam)
    }

    @VisibleForTesting
    internal fun releaseSlot(
        ceContext: Context,
        occurrenceKey: String,
        unlockGate: (Context) -> Boolean
    ): Boolean {
        if (occurrenceKey.isBlank()) return false
        validateCeContextAndUnlock(ceContext, unlockGate)?.let { return false }
        synchronized(ceStorageLock) {
            val prefs = getCePrefs(ceContext)
            val raw = try {
                readbackSeam(prefs)
            } catch (_: Exception) {
                return false
            }
            val state = parseStorageState(raw)
            if (state !is CeTakeoverStorageState.ValidWithRecords) return false
            val remaining = state.records.filter { it.occurrenceKey != occurrenceKey }
            if (remaining.size == state.records.size) return false
            val newPayload = serializePayload(remaining)
            return try {
                commitSeam(prefs, newPayload)
            } catch (_: Exception) {
                false
            }
        }
    }

    private fun serializePayload(records: List<CeTakeoverRecord>): String {
        val arr = JSONArray()
        for (record in records) {
            arr.put(record.toJson())
        }
        return JSONObject().apply {
            put("schemaVersion", CURRENT_SCHEMA_VERSION)
            put("records", arr)
        }.toString()
    }
}

sealed class CeTakeoverRecord {
    abstract val occurrenceKey: String
    abstract val sourceRecordId: String
    abstract val recordType: String
    abstract val kind: String
    abstract val alarmId: Long
    abstract val triggerTime: Long
    abstract val generation: Int
    abstract val instanceToken: String
    abstract val restoreEpoch: Long
    abstract val recordedAtEpochMs: Long
    abstract val schemaVersion: Int

    abstract fun toJson(): JSONObject

    data class Delivered(
        override val occurrenceKey: String,
        override val sourceRecordId: String,
        override val kind: String = DirectBootDeliveryRecord.KIND_NORMAL,
        override val alarmId: Long,
        override val triggerTime: Long,
        val deliveredAt: Long,
        override val generation: Int,
        override val instanceToken: String,
        val provenance: String,
        override val restoreEpoch: Long = 0L,
        override val recordedAtEpochMs: Long,
        override val schemaVersion: Int = DirectBootCeTakeoverStore.CURRENT_SCHEMA_VERSION
    ) : CeTakeoverRecord() {
        override val recordType: String get() = TYPE_DELIVERED

        override fun toJson(): JSONObject = JSONObject().apply {
            put("recordType", TYPE_DELIVERED)
            put("occurrenceKey", occurrenceKey)
            put("sourceRecordId", sourceRecordId)
            put("kind", kind)
            put("alarmId", alarmId)
            put("triggerTime", triggerTime)
            put("deliveredAt", deliveredAt)
            put("generation", generation)
            put("instanceToken", instanceToken)
            put("provenance", provenance)
            put("restoreEpoch", restoreEpoch)
            put("recordedAtEpochMs", recordedAtEpochMs)
            put("schemaVersion", schemaVersion)
        }

        fun matchesSemanticContent(other: Delivered): Boolean {
            return occurrenceKey == other.occurrenceKey &&
                sourceRecordId == other.sourceRecordId &&
                kind == other.kind &&
                alarmId == other.alarmId &&
                triggerTime == other.triggerTime &&
                deliveredAt == other.deliveredAt &&
                generation == other.generation &&
                instanceToken == other.instanceToken &&
                provenance == other.provenance &&
                restoreEpoch == other.restoreEpoch &&
                schemaVersion == other.schemaVersion
        }
    }

    data class Missed(
        override val occurrenceKey: String,
        override val sourceRecordId: String,
        override val kind: String = DirectBootMissedRecord.KIND_NORMAL,
        override val alarmId: Long,
        override val triggerTime: Long,
        val missedAt: Long,
        override val generation: Int,
        override val instanceToken: String,
        val classification: String,
        override val restoreEpoch: Long = 0L,
        override val recordedAtEpochMs: Long,
        override val schemaVersion: Int = DirectBootCeTakeoverStore.CURRENT_SCHEMA_VERSION
    ) : CeTakeoverRecord() {
        override val recordType: String get() = TYPE_MISSED

        override fun toJson(): JSONObject = JSONObject().apply {
            put("recordType", TYPE_MISSED)
            put("occurrenceKey", occurrenceKey)
            put("sourceRecordId", sourceRecordId)
            put("kind", kind)
            put("alarmId", alarmId)
            put("triggerTime", triggerTime)
            put("missedAt", missedAt)
            put("generation", generation)
            put("instanceToken", instanceToken)
            put("classification", classification)
            put("restoreEpoch", restoreEpoch)
            put("recordedAtEpochMs", recordedAtEpochMs)
            put("schemaVersion", schemaVersion)
        }

        fun matchesSemanticContent(other: Missed): Boolean {
            return occurrenceKey == other.occurrenceKey &&
                sourceRecordId == other.sourceRecordId &&
                kind == other.kind &&
                alarmId == other.alarmId &&
                triggerTime == other.triggerTime &&
                missedAt == other.missedAt &&
                generation == other.generation &&
                instanceToken == other.instanceToken &&
                classification == other.classification &&
                restoreEpoch == other.restoreEpoch &&
                schemaVersion == other.schemaVersion
        }
    }

    companion object {
        const val TYPE_DELIVERED = "DELIVERED"
        const val TYPE_MISSED = "MISSED"

        fun fromDeliveryRecord(
            record: DirectBootDeliveryRecord,
            recordedAtEpochMs: Long,
            overrideRestoreEpoch: Long = 0L
        ): Delivered {
            val occKey = DirectBootCeTakeoverStore.computeOccurrenceKey(
                alarmId = record.alarmId,
                triggerTime = record.triggerTime,
                generation = record.generation,
                instanceToken = record.instanceToken
            )
            val effectiveEpoch = if (record.restoreEpoch > 0L) record.restoreEpoch else overrideRestoreEpoch
            return Delivered(
                occurrenceKey = occKey,
                sourceRecordId = record.recordId,
                kind = record.kind,
                alarmId = record.alarmId,
                triggerTime = record.triggerTime,
                deliveredAt = record.deliveredAt,
                generation = record.generation,
                instanceToken = record.instanceToken,
                provenance = record.provenance,
                restoreEpoch = effectiveEpoch,
                recordedAtEpochMs = recordedAtEpochMs,
                schemaVersion = record.schemaVersion
            )
        }

        fun fromMissedRecord(
            record: DirectBootMissedRecord,
            recordedAtEpochMs: Long,
            overrideRestoreEpoch: Long = 0L
        ): Missed {
            val occKey = DirectBootCeTakeoverStore.computeOccurrenceKey(
                alarmId = record.alarmId,
                triggerTime = record.triggerTime,
                generation = record.generation,
                instanceToken = record.instanceToken
            )
            val effectiveEpoch = if (record.restoreEpoch > 0L) record.restoreEpoch else overrideRestoreEpoch
            return Missed(
                occurrenceKey = occKey,
                sourceRecordId = record.recordId,
                kind = record.kind,
                alarmId = record.alarmId,
                triggerTime = record.triggerTime,
                missedAt = record.missedAt,
                generation = record.generation,
                instanceToken = record.instanceToken,
                classification = record.classification,
                restoreEpoch = effectiveEpoch,
                recordedAtEpochMs = recordedAtEpochMs,
                schemaVersion = record.schemaVersion
            )
        }

        @Throws(JSONException::class)
        fun fromJson(json: JSONObject): CeTakeoverRecord {
            val type = json.optString("recordType", "")
            val occKey = json.optString("occurrenceKey", "")
            val sourceId = json.optString("sourceRecordId", "")
            val kind = json.optString("kind", "")
            val alarmId = json.optLong("alarmId", -1L)
            val triggerTime = json.optLong("triggerTime", 0L)
            val generation = json.optInt("generation", -1)
            val instanceToken = json.optString("instanceToken", "")
            val restoreEpoch = json.optLong("restoreEpoch", 0L)
            val recordedAt = json.optLong("recordedAtEpochMs", 0L)
            val schemaVersion = json.optInt("schemaVersion", -1)

            return when (type) {
                TYPE_DELIVERED -> Delivered(
                    occurrenceKey = occKey,
                    sourceRecordId = sourceId,
                    kind = kind,
                    alarmId = alarmId,
                    triggerTime = triggerTime,
                    deliveredAt = json.optLong("deliveredAt", 0L),
                    generation = generation,
                    instanceToken = instanceToken,
                    provenance = json.optString("provenance", ""),
                    restoreEpoch = restoreEpoch,
                    recordedAtEpochMs = recordedAt,
                    schemaVersion = schemaVersion
                )
                TYPE_MISSED -> Missed(
                    occurrenceKey = occKey,
                    sourceRecordId = sourceId,
                    kind = kind,
                    alarmId = alarmId,
                    triggerTime = triggerTime,
                    missedAt = json.optLong("missedAt", 0L),
                    generation = generation,
                    instanceToken = instanceToken,
                    classification = json.optString("classification", ""),
                    restoreEpoch = restoreEpoch,
                    recordedAtEpochMs = recordedAt,
                    schemaVersion = schemaVersion
                )
                else -> throw JSONException("Unknown CeTakeoverRecord recordType: '$type'")
            }
        }
    }
}

sealed class CeTakeoverStorageState {
    data object Absent : CeTakeoverStorageState()
    data object ValidEmpty : CeTakeoverStorageState()
    data class ValidWithRecords(val records: List<CeTakeoverRecord>) : CeTakeoverStorageState()
    data class CeLocked(val reason: String) : CeTakeoverStorageState()
    data class CeContextRejected(val reason: String) : CeTakeoverStorageState()
    data class Corrupt(val reason: String) : CeTakeoverStorageState()
    data class UnsupportedSchema(
        val foundVersion: Int,
        val expectedVersion: Int = DirectBootCeTakeoverStore.CURRENT_SCHEMA_VERSION
    ) : CeTakeoverStorageState()
}

sealed class CeReadbackVerificationResult {
    data class Verified(val record: CeTakeoverRecord, val totalRecords: Int) : CeReadbackVerificationResult()
    data class Unverified(val reason: String, val observedState: CeTakeoverStorageState? = null) : CeReadbackVerificationResult()
}

sealed class CeTakeoverIntakeResult {
    abstract val isGuaranteedNoWrite: Boolean
    abstract val ceWriteAttempted: Boolean

    data class RecordedConfirmed(
        val record: CeTakeoverRecord,
        val totalRecords: Int,
        val writePerformed: Boolean = true
    ) : CeTakeoverIntakeResult() {
        override val isGuaranteedNoWrite: Boolean get() = false
        override val ceWriteAttempted: Boolean get() = true
    }

    data class AlreadyRecordedConfirmed(
        val existingRecord: CeTakeoverRecord,
        val totalRecords: Int
    ) : CeTakeoverIntakeResult() {
        override val isGuaranteedNoWrite: Boolean get() = true
        override val ceWriteAttempted: Boolean get() = false
    }

    data class PostCommitFailure(
        val confirmedRecord: CeTakeoverRecord,
        val totalRecords: Int,
        val cause: Throwable
    ) : CeTakeoverIntakeResult() {
        override val isGuaranteedNoWrite: Boolean get() = false
        override val ceWriteAttempted: Boolean get() = true
    }

    data class CancellationAfterConfirm(
        val confirmedRecord: CeTakeoverRecord,
        val totalRecords: Int,
        val cancellation: CancellationException
    ) : CeTakeoverIntakeResult() {
        override val isGuaranteedNoWrite: Boolean get() = false
        override val ceWriteAttempted: Boolean get() = true
    }

    sealed class Rejected : CeTakeoverIntakeResult() {
        override val isGuaranteedNoWrite: Boolean get() = true
        override val ceWriteAttempted: Boolean get() = false

        data class CeLocked(val details: String) : Rejected()
        data class CeContextRejected(val details: String) : Rejected()
        data class QuickNotQualified(val details: String) : Rejected()
        data class InvalidRecord(val details: String) : Rejected()
        data class ContentConflict(
            val existingRecord: CeTakeoverRecord,
            val incomingRecord: CeTakeoverRecord,
            val details: String
        ) : Rejected()
        data class DeliveredMissedConflict(
            val existingRecord: CeTakeoverRecord,
            val incomingRecord: CeTakeoverRecord,
            val details: String
        ) : Rejected()
        data class CapacityBlocked(
            val currentCount: Int,
            val provisionalMaxCapacity: Int = DirectBootCeTakeoverStore.PROVISIONAL_MAX_CAPACITY
        ) : Rejected()
        data class StorageCorrupt(val reason: String) : Rejected()
        data class UnsupportedSchema(val foundVersion: Int, val expectedVersion: Int) : Rejected()
        data class PreCommitFailure(val details: String, val cause: Throwable) : Rejected()
    }

    sealed class Uncertain : CeTakeoverIntakeResult() {
        override val isGuaranteedNoWrite: Boolean get() = false
        override val ceWriteAttempted: Boolean get() = true
        abstract val details: String

        data class CommitFailed(override val details: String) : Uncertain()
        data class CommitException(override val details: String, val cause: Throwable) : Uncertain()
        data class ReadbackMismatch(
            override val details: String,
            val observedState: CeTakeoverStorageState? = null
        ) : Uncertain()
    }
}
