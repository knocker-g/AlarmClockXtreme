package com.sysadmindoc.alarmclock.directboot

import com.sysadmindoc.alarmclock.data.model.Alarm
import org.json.JSONObject
import java.security.MessageDigest

/**
 * Immutable domain model for a V2 Direct Boot Fired Witness Record persisted in Device Protected Storage.
 *
 * Lineage Invariants:
 * - Kind: "NORMAL" or "QUICK".
 * - Identity: Contains full lineage fields (alarmId, triggerTime, generation, instanceToken, restoreEpoch).
 * - Delivery Outcome: Explicitly supplied "OUTPUT_START_ACCEPTED", "OUTPUT_START_FAILED", "NO_OUTPUT_REQUESTED", "OUTPUT_STATUS_UNKNOWN".
 * - Integrity: SHA-256 canonical length-prefixed digest. Missing, blank, or tampered checksum fails closed.
 * - Schema: Mandatory schemaVersion field. Missing or unsupported schema version fails closed.
 * - Privacy: Zero PII or CE database secrets persisted in DE storage.
 */
data class DirectBootFiredWitnessRecord(
    val recordId: String,
    val kind: String,
    val alarmId: Long,
    val triggerTime: Long,
    val generation: Int,
    val instanceToken: String,
    val restoreEpoch: Long,
    val deliveryMode: String,
    val deliveryOutcome: String,
    val observedAtEpochMs: Long,
    val checksum: String = "",
    val schemaVersion: Int = CURRENT_SCHEMA_VERSION
) {
    fun computeChecksum(): String {
        val canonicalRaw = buildString {
            append("recordId:").append(recordId.length).append(":").append(recordId).append(";")
            append("kind:").append(kind.length).append(":").append(kind).append(";")
            append("alarmId:").append(alarmId).append(";")
            append("triggerTime:").append(triggerTime).append(";")
            append("generation:").append(generation).append(";")
            append("instanceToken:").append(instanceToken.length).append(":").append(instanceToken).append(";")
            append("restoreEpoch:").append(restoreEpoch).append(";")
            append("deliveryMode:").append(deliveryMode.length).append(":").append(deliveryMode).append(";")
            append("deliveryOutcome:").append(deliveryOutcome.length).append(":").append(deliveryOutcome).append(";")
            append("observedAtEpochMs:").append(observedAtEpochMs).append(";")
            append("schemaVersion:").append(schemaVersion)
        }
        val md = MessageDigest.getInstance("SHA-256")
        val digest = md.digest(canonicalRaw.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    fun toJson(): JSONObject {
        val calculatedChecksum = if (checksum.isBlank()) computeChecksum() else checksum
        return JSONObject().apply {
            put("recordId", recordId)
            put("kind", kind)
            put("alarmId", alarmId)
            put("triggerTime", triggerTime)
            put("generation", generation)
            put("instanceToken", instanceToken)
            put("restoreEpoch", restoreEpoch)
            put("deliveryMode", deliveryMode)
            put("deliveryOutcome", deliveryOutcome)
            put("observedAtEpochMs", observedAtEpochMs)
            put("checksum", calculatedChecksum)
            put("schemaVersion", schemaVersion)
        }
    }

    companion object {
        const val CURRENT_SCHEMA_VERSION = 1
        const val KIND_NORMAL = Alarm.KIND_NORMAL
        const val KIND_QUICK = Alarm.KIND_QUICK

        const val MODE_SOUND_AND_VIBRATION = "SOUND_AND_VIBRATION"
        const val MODE_SOUND_ONLY = "SOUND_ONLY"
        const val MODE_VIBRATION_ONLY = "VIBRATION_ONLY"
        const val MODE_SILENT_NOTIFICATION = "SILENT_NOTIFICATION"
        val APPROVED_DELIVERY_MODES: Set<String> = setOf(
            MODE_SOUND_AND_VIBRATION, MODE_SOUND_ONLY, MODE_VIBRATION_ONLY, MODE_SILENT_NOTIFICATION
        )

        const val OUTCOME_OUTPUT_START_ACCEPTED = "OUTPUT_START_ACCEPTED"
        const val OUTCOME_PARTIAL_OUTPUT_ACCEPTED = "PARTIAL_OUTPUT_ACCEPTED"
        const val OUTCOME_OUTPUT_START_FAILED = "OUTPUT_START_FAILED"
        const val OUTCOME_NO_OUTPUT_REQUESTED = "NO_OUTPUT_REQUESTED"
        const val OUTCOME_OUTPUT_STATUS_UNKNOWN = "OUTPUT_STATUS_UNKNOWN"
        val APPROVED_DELIVERY_OUTCOMES: Set<String> = setOf(
            OUTCOME_OUTPUT_START_ACCEPTED, OUTCOME_PARTIAL_OUTPUT_ACCEPTED, OUTCOME_OUTPUT_START_FAILED, OUTCOME_NO_OUTPUT_REQUESTED, OUTCOME_OUTPUT_STATUS_UNKNOWN
        )

        fun computeRecordId(
            kind: String,
            alarmId: Long,
            triggerTime: Long,
            generation: Int,
            instanceToken: String,
            restoreEpoch: Long
        ): String {
            val tokenKey = if (kind == KIND_NORMAL && instanceToken.isNotBlank()) {
                instanceToken
            } else if (kind == KIND_QUICK) {
                instanceToken.ifBlank { "quick_${alarmId}_$triggerTime" }
            } else {
                "invalid_token"
            }
            return "witness_${kind}_${alarmId}_gen${generation}_${tokenKey}_epoch${restoreEpoch}_trig$triggerTime"
        }

        fun createNormal(
            alarmId: Long,
            triggerTime: Long,
            generation: Int,
            instanceToken: String,
            restoreEpoch: Long,
            deliveryMode: String,
            deliveryOutcome: String,
            observedAtEpochMs: Long = System.currentTimeMillis()
        ): DirectBootFiredWitnessRecord {
            val rec = DirectBootFiredWitnessRecord(
                recordId = computeRecordId(KIND_NORMAL, alarmId, triggerTime, generation, instanceToken, restoreEpoch),
                kind = KIND_NORMAL,
                alarmId = alarmId,
                triggerTime = triggerTime,
                generation = generation,
                instanceToken = instanceToken,
                restoreEpoch = restoreEpoch,
                deliveryMode = deliveryMode,
                deliveryOutcome = deliveryOutcome,
                observedAtEpochMs = observedAtEpochMs
            )
            return rec.copy(checksum = rec.computeChecksum())
        }

        fun parse(json: JSONObject): DirectBootFiredWitnessRecord {
            if (!json.has("schemaVersion")) {
                throw IllegalArgumentException("Missing schemaVersion field")
            }
            val version = json.getInt("schemaVersion")
            if (version > CURRENT_SCHEMA_VERSION || version <= 0) {
                throw UnsupportedSchemaException(version, CURRENT_SCHEMA_VERSION)
            }

            if (!json.has("checksum") || json.getString("checksum").isBlank()) {
                throw IllegalArgumentException("Missing or blank checksum field")
            }
            val storedChecksum = json.getString("checksum")

            val parsedRecord = DirectBootFiredWitnessRecord(
                recordId = json.getString("recordId"),
                kind = json.getString("kind"),
                alarmId = json.getLong("alarmId"),
                triggerTime = json.getLong("triggerTime"),
                generation = json.getInt("generation"),
                instanceToken = json.getString("instanceToken"),
                restoreEpoch = json.getLong("restoreEpoch"),
                deliveryMode = json.getString("deliveryMode"),
                deliveryOutcome = json.getString("deliveryOutcome"),
                observedAtEpochMs = json.getLong("observedAtEpochMs"),
                checksum = storedChecksum,
                schemaVersion = version
            )

            if (parsedRecord.deliveryMode !in APPROVED_DELIVERY_MODES) {
                throw IllegalArgumentException("Invalid deliveryMode: ${parsedRecord.deliveryMode}")
            }
            if (parsedRecord.deliveryOutcome !in APPROVED_DELIVERY_OUTCOMES) {
                throw IllegalArgumentException("Invalid deliveryOutcome: ${parsedRecord.deliveryOutcome}")
            }

            val expectedRecordId = computeRecordId(
                parsedRecord.kind,
                parsedRecord.alarmId,
                parsedRecord.triggerTime,
                parsedRecord.generation,
                parsedRecord.instanceToken,
                parsedRecord.restoreEpoch
            )
            if (parsedRecord.recordId != expectedRecordId) {
                throw IllegalArgumentException("recordId mismatch for record ${parsedRecord.recordId}: expected $expectedRecordId")
            }

            val expectedChecksum = parsedRecord.computeChecksum()
            if (storedChecksum != expectedChecksum) {
                throw IllegalArgumentException("Checksum mismatch for record ${parsedRecord.recordId}: expected $expectedChecksum, got $storedChecksum")
            }
            return parsedRecord
        }
    }
}

class UnsupportedSchemaException(val foundVersion: Int, val expectedVersion: Int) :
    Exception("Unsupported schema version $foundVersion (expected $expectedVersion)")

enum class WitnessValidationStatus {
    VALID,
    INVALID_ALARM_ID,
    INVALID_TRIGGER_TIME,
    INVALID_GENERATION,
    INVALID_RESTORE_EPOCH,
    INVALID_INSTANCE_TOKEN,
    UNKNOWN_KIND,
    UNAUTHORIZED_QUICK_RUNTIME
}

sealed class WitnessLedgerState {
    object Absent : WitnessLedgerState()
    object ValidEmpty : WitnessLedgerState()
    data class ValidWithRecords(val records: List<DirectBootFiredWitnessRecord>) : WitnessLedgerState()
    data class Corrupt(val reason: String) : WitnessLedgerState()
    data class UnsupportedSchema(val foundVersion: Int, val expectedVersion: Int) : WitnessLedgerState()
}

sealed class WitnessAppendResult {
    data class Appended(val record: DirectBootFiredWitnessRecord, val totalCount: Int) : WitnessAppendResult()
    data class DuplicateIgnored(val existing: DirectBootFiredWitnessRecord, val totalCount: Int) : WitnessAppendResult()
    data class ContentConflict(val existing: DirectBootFiredWitnessRecord, val candidate: DirectBootFiredWitnessRecord) : WitnessAppendResult()
    data class CapacityExhausted(val currentCount: Int, val maxCapacity: Int) : WitnessAppendResult()
    data class RejectedIneligible(val status: WitnessValidationStatus) : WitnessAppendResult()
    data class PersistenceFailed(val reason: String) : WitnessAppendResult()
    data class StorageCorrupt(val reason: String) : WitnessAppendResult()
    data class UnsupportedSchema(val foundVersion: Int, val expectedVersion: Int) : WitnessAppendResult()
}

sealed class WitnessAckResult {
    data class Acknowledged(val recordId: String, val remainingCount: Int) : WitnessAckResult()
    data class NotFound(val recordId: String) : WitnessAckResult()
    data class StorageCorrupt(val reason: String) : WitnessAckResult()
    data class UnsupportedSchema(val foundVersion: Int, val expectedVersion: Int) : WitnessAckResult()
}

sealed class WitnessUpdateResult {
    data class Updated(val record: DirectBootFiredWitnessRecord, val totalCount: Int) : WitnessUpdateResult()
    data class NotFound(val recordId: String) : WitnessUpdateResult()
    data class PersistenceFailed(val reason: String) : WitnessUpdateResult()
    data class StorageCorrupt(val reason: String) : WitnessUpdateResult()
}
