package com.sysadmindoc.alarmclock.directboot

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import org.json.JSONArray

/**
 * Thread-safe, atomic DE storage ledger for V2 Direct Boot Fired Witness Evidence.
 *
 * Invariants:
 * - Persists unacknowledged firing evidence in Device Protected Storage.
 * - Bounded max capacity of 16 records. At capacity, fail-closed without evicting prior records.
 * - Synchronous commit under [DirectBootStorageLock.lock].
 * - Never returns false success on persistence failure.
 */
object DirectBootFiredWitnessLedger {

    private const val TAG = "DirectBootWitnessLedger"
    const val PREFS_NAME = "direct_boot_fired_witness_ledger"
    const val KEY_WITNESS_PAYLOAD = "witness_records_payload"
    const val MAX_CAPACITY = 16

    @get:org.jetbrains.annotations.VisibleForTesting
    internal var commitSeam: (SharedPreferences.Editor) -> Boolean = { editor -> editor.commit() }

    @get:org.jetbrains.annotations.VisibleForTesting
    internal var readbackSeam: (SharedPreferences) -> String? = { prefs -> prefs.getString(KEY_WITNESS_PAYLOAD, null) }

    fun validateRecord(record: DirectBootFiredWitnessRecord): WitnessValidationStatus {
        if (record.kind == DirectBootFiredWitnessRecord.KIND_QUICK) {
            return WitnessValidationStatus.UNAUTHORIZED_QUICK_RUNTIME
        }
        if (record.kind != DirectBootFiredWitnessRecord.KIND_NORMAL) {
            return WitnessValidationStatus.UNKNOWN_KIND
        }
        if (record.alarmId <= 0L) {
            return WitnessValidationStatus.INVALID_ALARM_ID
        }
        if (record.triggerTime <= 0L) {
            return WitnessValidationStatus.INVALID_TRIGGER_TIME
        }
        if (record.generation < 1) {
            return WitnessValidationStatus.INVALID_GENERATION
        }
        if (record.restoreEpoch <= 0L) {
            return WitnessValidationStatus.INVALID_RESTORE_EPOCH
        }
        if (record.instanceToken.isBlank()) {
            return WitnessValidationStatus.INVALID_INSTANCE_TOKEN
        }
        return WitnessValidationStatus.VALID
    }

    fun reserveWitnessSlot(context: Context, record: DirectBootFiredWitnessRecord): WitnessAppendResult {
        val reservationRecord = record.copy(
            deliveryOutcome = DirectBootFiredWitnessRecord.OUTCOME_OUTPUT_STATUS_UNKNOWN
        ).let { it.copy(checksum = it.computeChecksum()) }
        return appendWitness(context, reservationRecord)
    }

    fun updateWitnessOutcome(
        context: Context,
        recordId: String,
        deliveryMode: String,
        deliveryOutcome: String
    ): WitnessUpdateResult {
        if (recordId.isBlank()) return WitnessUpdateResult.NotFound(recordId)
        if (deliveryMode !in DirectBootFiredWitnessRecord.APPROVED_DELIVERY_MODES) {
            return WitnessUpdateResult.StorageCorrupt("Invalid deliveryMode: $deliveryMode")
        }
        if (deliveryOutcome !in DirectBootFiredWitnessRecord.APPROVED_DELIVERY_OUTCOMES) {
            return WitnessUpdateResult.StorageCorrupt("Invalid deliveryOutcome: $deliveryOutcome")
        }

        val deContext = if (context.isDeviceProtectedStorage) context else context.createDeviceProtectedStorageContext()

        synchronized(DirectBootStorageLock.lock) {
            val state = readWitnesses(deContext)
            if (state is WitnessLedgerState.Corrupt) return WitnessUpdateResult.StorageCorrupt(state.reason)
            if (state is WitnessLedgerState.UnsupportedSchema) return WitnessUpdateResult.StorageCorrupt("Unsupported schema")
            if (state !is WitnessLedgerState.ValidWithRecords) return WitnessUpdateResult.NotFound(recordId)

            val currentRecords = state.records.toMutableList()
            val index = currentRecords.indexOfFirst { it.recordId == recordId }
            if (index < 0) return WitnessUpdateResult.NotFound(recordId)

            val existing = currentRecords[index]
            if (existing.deliveryOutcome == deliveryOutcome && existing.deliveryMode == deliveryMode) {
                return WitnessUpdateResult.Updated(existing, currentRecords.size)
            }
            if (existing.deliveryMode != deliveryMode) {
                return WitnessUpdateResult.StorageCorrupt(
                    "ModeConflict: cannot change reserved deliveryMode from ${existing.deliveryMode} to $deliveryMode"
                )
            }
            if (existing.deliveryOutcome != DirectBootFiredWitnessRecord.OUTCOME_OUTPUT_STATUS_UNKNOWN) {
                return WitnessUpdateResult.StorageCorrupt(
                    "StateConflict: cannot transition from mode=${existing.deliveryMode}/outcome=${existing.deliveryOutcome} " +
                        "to mode=$deliveryMode/outcome=$deliveryOutcome"
                )
            }

            val updated = existing.copy(
                deliveryMode = deliveryMode,
                deliveryOutcome = deliveryOutcome
            ).let { it.copy(checksum = it.computeChecksum()) }

            currentRecords[index] = updated
            val json = serializeRecords(currentRecords)
            val prefs = deContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

            return if (commitSeam(prefs.edit().putString(KEY_WITNESS_PAYLOAD, json))) {
                WitnessUpdateResult.Updated(updated, currentRecords.size)
            } else {
                WitnessUpdateResult.PersistenceFailed("SharedPreferences commit returned false")
            }
        }
    }

    fun appendWitness(context: Context, record: DirectBootFiredWitnessRecord): WitnessAppendResult {
        val validation = validateRecord(record)
        if (validation != WitnessValidationStatus.VALID) {
            return WitnessAppendResult.RejectedIneligible(validation)
        }

        val deContext = if (context.isDeviceProtectedStorage) context else context.createDeviceProtectedStorageContext()
        synchronized(DirectBootStorageLock.lock) {
            val state = readWitnesses(deContext)
            if (state is WitnessLedgerState.Corrupt) {
                return WitnessAppendResult.StorageCorrupt(state.reason)
            }
            if (state is WitnessLedgerState.UnsupportedSchema) {
                return WitnessAppendResult.UnsupportedSchema(state.foundVersion, state.expectedVersion)
            }

            val currentRecords = when (state) {
                is WitnessLedgerState.ValidWithRecords -> state.records.toMutableList()
                else -> mutableListOf()
            }

            val existing = currentRecords.firstOrNull { it.recordId == record.recordId }
            if (existing != null) {
                return if (existing == record || existing.deliveryOutcome == DirectBootFiredWitnessRecord.OUTCOME_OUTPUT_STATUS_UNKNOWN) {
                    WitnessAppendResult.DuplicateIgnored(existing, currentRecords.size)
                } else {
                    WitnessAppendResult.ContentConflict(existing, record)
                }
            }

            if (currentRecords.size >= MAX_CAPACITY) {
                Log.w(TAG, "Witness ledger capacity exhausted (${currentRecords.size}/$MAX_CAPACITY)")
                return WitnessAppendResult.CapacityExhausted(currentRecords.size, MAX_CAPACITY)
            }

            currentRecords.add(record)
            val json = serializeRecords(currentRecords)
            val prefs = deContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

            return if (commitSeam(prefs.edit().putString(KEY_WITNESS_PAYLOAD, json))) {
                WitnessAppendResult.Appended(record, currentRecords.size)
            } else {
                WitnessAppendResult.PersistenceFailed("SharedPreferences commit returned false")
            }
        }
    }

    fun readWitnesses(context: Context): WitnessLedgerState {
        val deContext = if (context.isDeviceProtectedStorage) context else context.createDeviceProtectedStorageContext()
        synchronized(DirectBootStorageLock.lock) {
            val prefs = deContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val rawPayload = readbackSeam(prefs) ?: return WitnessLedgerState.ValidEmpty
            if (rawPayload.isBlank()) return WitnessLedgerState.ValidEmpty

            return try {
                val array = JSONArray(rawPayload)
                if (array.length() > MAX_CAPACITY) {
                    return WitnessLedgerState.Corrupt("Stored witness payload length (${array.length()}) exceeds MAX_CAPACITY ($MAX_CAPACITY)")
                }
                val records = mutableListOf<DirectBootFiredWitnessRecord>()
                for (i in 0 until array.length()) {
                    val obj = array.getJSONObject(i)
                    val rec = DirectBootFiredWitnessRecord.parse(obj)

                    val validation = validateRecord(rec)
                    if (validation != WitnessValidationStatus.VALID) {
                        return WitnessLedgerState.Corrupt("Record at index $i is ineligible: $validation")
                    }

                    if (records.any { it.recordId == rec.recordId }) {
                        return WitnessLedgerState.Corrupt("Duplicate recordId in storage: ${rec.recordId}")
                    }
                    records.add(rec)
                }
                if (records.isEmpty()) WitnessLedgerState.ValidEmpty else WitnessLedgerState.ValidWithRecords(records)
            } catch (e: UnsupportedSchemaException) {
                WitnessLedgerState.UnsupportedSchema(e.foundVersion, e.expectedVersion)
            } catch (e: Exception) {
                WitnessLedgerState.Corrupt("JSON parse error: ${e.message}")
            }
        }
    }

    fun hasWitness(
        context: Context,
        alarmId: Long,
        triggerTime: Long,
        generation: Int,
        instanceToken: String,
        kind: String = DirectBootFiredWitnessRecord.KIND_NORMAL
    ): Boolean {
        return when (val state = readWitnesses(context)) {
            is WitnessLedgerState.ValidWithRecords -> {
                state.records.any {
                    it.alarmId == alarmId &&
                        it.triggerTime == triggerTime &&
                        it.generation == generation &&
                        it.instanceToken == instanceToken &&
                        it.kind == kind
                }
            }
            else -> false
        }
    }

    fun acknowledgeWitness(context: Context, recordId: String): WitnessAckResult {
        if (recordId.isBlank()) return WitnessAckResult.NotFound(recordId)
        val deContext = if (context.isDeviceProtectedStorage) context else context.createDeviceProtectedStorageContext()

        synchronized(DirectBootStorageLock.lock) {
            val state = readWitnesses(deContext)
            if (state is WitnessLedgerState.Corrupt) return WitnessAckResult.StorageCorrupt(state.reason)
            if (state is WitnessLedgerState.UnsupportedSchema) return WitnessAckResult.UnsupportedSchema(state.foundVersion, state.expectedVersion)
            if (state !is WitnessLedgerState.ValidWithRecords) return WitnessAckResult.NotFound(recordId)

            val currentRecords = state.records.toMutableList()
            val removed = currentRecords.removeAll { it.recordId == recordId }
            if (!removed) return WitnessAckResult.NotFound(recordId)

            val json = serializeRecords(currentRecords)
            val prefs = deContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs.edit().putString(KEY_WITNESS_PAYLOAD, json).commit()
            return WitnessAckResult.Acknowledged(recordId, currentRecords.size)
        }
    }

    private fun serializeRecords(records: List<DirectBootFiredWitnessRecord>): String {
        val array = JSONArray()
        records.forEach { array.put(it.toJson()) }
        return array.toString()
    }

    @org.jetbrains.annotations.VisibleForTesting
    internal fun resetSeamsForTesting() {
        commitSeam = { editor -> editor.commit() }
        readbackSeam = { prefs -> prefs.getString(KEY_WITNESS_PAYLOAD, null) }
    }
}
