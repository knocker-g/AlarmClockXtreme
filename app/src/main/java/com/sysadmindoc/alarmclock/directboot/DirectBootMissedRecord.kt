package com.sysadmindoc.alarmclock.directboot

import com.sysadmindoc.alarmclock.data.model.Alarm
import org.json.JSONException
import org.json.JSONObject

/**
 * Inactive versioned Direct Boot Missed Record representation for KNO-75 (Phase D3-C2-P3B / D3-C2-P3B-C1).
 *
 * Responsibilities & Invariants:
 * - Domain: Strictly [Alarm.KIND_NORMAL] ("NORMAL").
 * - Scheduled concrete trigger epoch instant [triggerTime] (> 0L).
 * - Wall-clock instant when missed record was recorded [missedAt] (> 0L).
 * - Normal generation / lineage: strictly requires caller-supplied authoritative [generation] (>= 1)
 *   and non-blank [instanceToken]. Fabricated or synthesized defaults are prohibited.
 * - Exact canonical record identity:
 *     recordId = "missed_normal_${alarmId}_${generation}_${instanceToken}_$triggerTime"
 *   Matches [DirectBootOverduePolicyEngine.computeMissedRecordId].
 * - Missed classification [classification]: strictly restricted to approved domain:
 *   [CLASSIFICATION_OVERDUE] ("OVERDUE") or [CLASSIFICATION_COLD_BOOT] ("COLD_BOOT").
 *   Unknown classifications fail closed on append and storage parse; conversion to defaults is prohibited.
 * - Schema version [schemaVersion] (strictly [CURRENT_SCHEMA_VERSION] = 1).
 * - Privacy invariant: Zero user-authored labels, contacts, URIs, or secrets.
 * - Distinct from [DirectBootDeliveryRecord]: Missed != Delivered.
 */
data class DirectBootMissedRecord(
    val recordId: String,                  // Canonical missed record ID
    val kind: String = KIND_NORMAL,        // Strictly "NORMAL"
    val alarmId: Long,                     // Database primary key (> 0L)
    val triggerTime: Long,                 // Scheduled concrete trigger epoch instant in millis (> 0L)
    val missedAt: Long,                    // Wall-clock record instant in millis (> 0L)
    val generation: Int,                   // Schedule generation (Normal: >= 1)
    val instanceToken: String,             // ScheduleAuthority lineage token (required non-blank)
    val classification: String,            // Policy classification string (strictly "OVERDUE" or "COLD_BOOT")
    val restoreEpoch: Long = 0L,           // Restore epoch provenance (> 0L for DE v2)
    val schemaVersion: Int = CURRENT_SCHEMA_VERSION
) {
    /**
     * Evaluates record validity according to KNO-75 invariants.
     */
    fun evaluateValidation(): MissedValidationStatus {
        if (schemaVersion != CURRENT_SCHEMA_VERSION && schemaVersion != 1) {
            return MissedValidationStatus.INVALID_SCHEMA_VERSION
        }
        if (alarmId <= 0L) return MissedValidationStatus.INVALID_ALARM_ID
        if (triggerTime <= 0L) return MissedValidationStatus.INVALID_TRIGGER_TIME
        if (missedAt <= 0L) return MissedValidationStatus.INVALID_MISSED_TIME
        if (kind != KIND_NORMAL) return MissedValidationStatus.UNKNOWN_KIND
        if (generation < 1) return MissedValidationStatus.MISSING_NORMAL_GENERATION
        if (instanceToken.isBlank()) return MissedValidationStatus.MISSING_NORMAL_INSTANCE_TOKEN
        if (schemaVersion >= 2 && restoreEpoch <= 0L) return MissedValidationStatus.INVALID_RESTORE_EPOCH
        if (classification !in APPROVED_CLASSIFICATIONS) {
            return MissedValidationStatus.INVALID_CLASSIFICATION
        }

        val expectedRecordId = computeRecordId(alarmId, triggerTime, generation, instanceToken)
        if (recordId != expectedRecordId) {
            return MissedValidationStatus.RECORD_ID_MISMATCH
        }

        return MissedValidationStatus.VALID
    }

    val isValid: Boolean get() = evaluateValidation() == MissedValidationStatus.VALID

    /**
     * Serializes missed record into JSON for Device Protected Storage.
     * Guaranteed free of user-authored labels, contacts, URIs, or secrets.
     */
    fun toJson(): JSONObject = JSONObject().apply {
        put("recordId", recordId)
        put("kind", kind)
        put("alarmId", alarmId)
        put("triggerTime", triggerTime)
        put("missedAt", missedAt)
        put("generation", generation)
        put("instanceToken", instanceToken)
        put("classification", classification)
        put("restoreEpoch", restoreEpoch)
        put("schemaVersion", schemaVersion)
    }

    companion object {
        const val CURRENT_SCHEMA_VERSION = 2
        const val KIND_NORMAL = Alarm.KIND_NORMAL

        const val CLASSIFICATION_OVERDUE = "OVERDUE"
        const val CLASSIFICATION_COLD_BOOT = "COLD_BOOT"
        const val CLASSIFICATION_SERVICE_START_FAILED = "SERVICE_START_FAILED"
        val APPROVED_CLASSIFICATIONS: Set<String> = setOf(CLASSIFICATION_OVERDUE, CLASSIFICATION_COLD_BOOT, CLASSIFICATION_SERVICE_START_FAILED)

        /**
         * Canonical Missed Record ID formula matching DirectBootOverduePolicyEngine.
         */
        fun computeRecordId(
            alarmId: Long,
            triggerTime: Long,
            generation: Int,
            instanceToken: String
        ): String {
            return "missed_normal_${alarmId}_${generation}_${instanceToken}_$triggerTime"
        }

        /**
         * Factory for Normal missed records.
         * Callers MUST supply authoritative [generation] and [instanceToken]; defaults are forbidden.
         */
        fun createNormal(
            alarmId: Long,
            triggerTime: Long,
            missedAt: Long,
            generation: Int,
            instanceToken: String,
            classification: String = CLASSIFICATION_OVERDUE,
            restoreEpoch: Long = 1L,
            schemaVersion: Int = CURRENT_SCHEMA_VERSION
        ): DirectBootMissedRecord {
            return DirectBootMissedRecord(
                recordId = computeRecordId(alarmId, triggerTime, generation, instanceToken),
                kind = KIND_NORMAL,
                alarmId = alarmId,
                triggerTime = triggerTime,
                missedAt = missedAt,
                generation = generation,
                instanceToken = instanceToken,
                classification = classification,
                restoreEpoch = restoreEpoch,
                schemaVersion = schemaVersion
            )
        }

        /**
         * Deserializes a DirectBootMissedRecord from JSON.
         * Throws [JSONException] or [IllegalArgumentException] on missing or invalid types.
         */
        @Throws(JSONException::class)
        fun fromJson(json: JSONObject): DirectBootMissedRecord {
            return DirectBootMissedRecord(
                recordId = json.getString("recordId"),
                kind = json.optString("kind", KIND_NORMAL),
                alarmId = json.getLong("alarmId"),
                triggerTime = json.getLong("triggerTime"),
                missedAt = json.getLong("missedAt"),
                generation = json.getInt("generation"),
                instanceToken = json.getString("instanceToken"),
                classification = json.getString("classification"),
                restoreEpoch = json.optLong("restoreEpoch", 0L),
                schemaVersion = json.optInt("schemaVersion", -1)
            )
        }
    }
}

enum class MissedValidationStatus {
    VALID,
    INVALID_SCHEMA_VERSION,
    INVALID_ALARM_ID,
    INVALID_TRIGGER_TIME,
    INVALID_MISSED_TIME,
    UNKNOWN_KIND,
    MISSING_NORMAL_GENERATION,
    MISSING_NORMAL_INSTANCE_TOKEN,
    INVALID_RESTORE_EPOCH,
    INVALID_CLASSIFICATION,
    RECORD_ID_MISMATCH
}
