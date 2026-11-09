package com.sysadmindoc.alarmclock.directboot

import com.sysadmindoc.alarmclock.data.model.Alarm
import org.json.JSONException
import org.json.JSONObject

/**
 * Inactive versioned Direct Boot Delivery Ledger record representation for KNO-75.
 *
 * Invariants & Identity Boundaries:
 * - Domain separation between [Alarm.KIND_NORMAL] ("NORMAL") and [Alarm.KIND_QUICK] ("QUICK").
 * - Concrete occurrence trigger epoch instant [triggerTime].
 * - Actual delivery receipt wall-clock instant [deliveredAt].
 * - Normal generation / lineage: strictly requires caller-supplied authoritative [generation] (>= 1)
 *   and non-blank [instanceToken]. Fabricated or synthesized defaults are prohibited.
 *   Derived Normal recordId: "delivery_NORMAL_${alarmId}_gen${generation}_${instanceToken}_trig${triggerTime}"
 * - Quick persisted trigger identity: derived recordId: "delivery_QUICK_${alarmId}_trig${triggerTime}".
 *   PRODUCER QUALIFICATION REQUIREMENT: Quick alarm lifecycle does not maintain authority generation or instanceToken.
 *   While (alarmId, triggerTime) uniquely identifies a Quick alarm in a single active schedule session,
 *   uniqueness across database restores, ID reuse, or re-seeding cannot be formally proven without producer qualification
 *   (e.g., boot count, instance UUID, or producer monotonic counter). Therefore, Quick identity is designated as
 *   requiring producer qualification in a later slice. No synthetic lineage fields or unprovable exactly-once delivery
 *   claims are fabricated here.
 * - Restore/boot provenance metadata [provenance].
 * - Schema version [schemaVersion] (strictly [CURRENT_SCHEMA_VERSION]).
 * - Privacy invariant: Zero user-authored labels, contact data, Guardian credentials, or audio URIs are accepted or serialized.
 */
data class DirectBootDeliveryRecord(
    val recordId: String,                  // Unique canonical ledger record ID
    val kind: String,                      // "NORMAL" or "QUICK"
    val alarmId: Long,                     // Database primary key (> 0L)
    val triggerTime: Long,                 // Scheduled concrete trigger epoch instant in millis (> 0L)
    val deliveredAt: Long,                 // Wall-clock receipt instant in millis (> 0L)
    val generation: Int,                   // Schedule generation (Normal: >= 1; Quick: 0)
    val instanceToken: String,             // ScheduleAuthority lineage token (required non-blank for Normal; empty for Quick)
    val provenance: String = "",           // Optional provenance metadata (e.g., "BOOT", "RESTORE", or "")
    val schemaVersion: Int = CURRENT_SCHEMA_VERSION
) {
    /**
     * Evaluates record validity according to KNO-75 invariants.
     */
    fun evaluateValidation(): DeliveryValidationStatus {
        if (schemaVersion != CURRENT_SCHEMA_VERSION) {
            return DeliveryValidationStatus.INVALID_SCHEMA_VERSION
        }
        if (alarmId <= 0L) return DeliveryValidationStatus.INVALID_ALARM_ID
        if (triggerTime <= 0L) return DeliveryValidationStatus.INVALID_TRIGGER_TIME
        if (deliveredAt <= 0L) return DeliveryValidationStatus.INVALID_DELIVERY_TIME
        if (kind != KIND_NORMAL && kind != KIND_QUICK) return DeliveryValidationStatus.UNKNOWN_KIND

        if (kind == KIND_NORMAL) {
            if (generation < 1) return DeliveryValidationStatus.MISSING_NORMAL_GENERATION
            if (instanceToken.isBlank()) return DeliveryValidationStatus.MISSING_NORMAL_INSTANCE_TOKEN
        }

        // Validate recordId matches expected canonical lineage
        val expectedRecordId = computeRecordId(kind, alarmId, triggerTime, generation, instanceToken)
        if (recordId != expectedRecordId) {
            return DeliveryValidationStatus.RECORD_ID_MISMATCH
        }

        return DeliveryValidationStatus.VALID
    }

    val isValid: Boolean get() = evaluateValidation() == DeliveryValidationStatus.VALID

    /**
     * Serializes delivery record into JSON for Device Protected Storage.
     * Guaranteed free of user-authored labels, contacts, URIs, or secrets.
     */
    fun toJson(): JSONObject = JSONObject().apply {
        put("recordId", recordId)
        put("kind", kind)
        put("alarmId", alarmId)
        put("triggerTime", triggerTime)
        put("deliveredAt", deliveredAt)
        put("generation", generation)
        put("instanceToken", instanceToken)
        put("provenance", provenance)
        put("schemaVersion", schemaVersion)
    }

    companion object {
        const val CURRENT_SCHEMA_VERSION = 1
        const val KIND_NORMAL = Alarm.KIND_NORMAL
        const val KIND_QUICK = Alarm.KIND_QUICK

        fun computeRecordId(
            kind: String,
            alarmId: Long,
            triggerTime: Long,
            generation: Int,
            instanceToken: String
        ): String {
            return if (kind == KIND_NORMAL) {
                "delivery_NORMAL_${alarmId}_gen${generation}_${instanceToken}_trig${triggerTime}"
            } else {
                "delivery_QUICK_${alarmId}_trig${triggerTime}"
            }
        }

        /**
         * Creates a Normal delivery record.
         * Callers MUST supply authoritative [generation] and [instanceToken]; defaults are forbidden.
         */
        fun createNormal(
            alarmId: Long,
            triggerTime: Long,
            deliveredAt: Long,
            generation: Int,
            instanceToken: String,
            provenance: String = "",
            schemaVersion: Int = CURRENT_SCHEMA_VERSION
        ): DirectBootDeliveryRecord {
            return DirectBootDeliveryRecord(
                recordId = computeRecordId(KIND_NORMAL, alarmId, triggerTime, generation, instanceToken),
                kind = KIND_NORMAL,
                alarmId = alarmId,
                triggerTime = triggerTime,
                deliveredAt = deliveredAt,
                generation = generation,
                instanceToken = instanceToken,
                provenance = provenance,
                schemaVersion = schemaVersion
            )
        }

        /**
         * Creates a Quick delivery record.
         * Generates recordId based on kind, alarmId, and triggerTime.
         */
        fun createQuick(
            alarmId: Long,
            triggerTime: Long,
            deliveredAt: Long,
            provenance: String = "",
            schemaVersion: Int = CURRENT_SCHEMA_VERSION
        ): DirectBootDeliveryRecord {
            return DirectBootDeliveryRecord(
                recordId = computeRecordId(KIND_QUICK, alarmId, triggerTime, 0, ""),
                kind = KIND_QUICK,
                alarmId = alarmId,
                triggerTime = triggerTime,
                deliveredAt = deliveredAt,
                generation = 0,
                instanceToken = "",
                provenance = provenance,
                schemaVersion = schemaVersion
            )
        }

        @Throws(JSONException::class)
        fun fromJson(json: JSONObject): DirectBootDeliveryRecord {
            return DirectBootDeliveryRecord(
                recordId = json.optString("recordId", ""),
                kind = json.optString("kind", ""),
                alarmId = json.optLong("alarmId", -1L),
                triggerTime = json.optLong("triggerTime", 0L),
                deliveredAt = json.optLong("deliveredAt", 0L),
                generation = json.optInt("generation", -1),
                instanceToken = json.optString("instanceToken", ""),
                provenance = json.optString("provenance", ""),
                schemaVersion = json.optInt("schemaVersion", -1)
            )
        }
    }
}

enum class DeliveryValidationStatus {
    VALID,
    INVALID_ALARM_ID,
    INVALID_TRIGGER_TIME,
    INVALID_DELIVERY_TIME,
    UNKNOWN_KIND,
    MISSING_NORMAL_GENERATION,
    MISSING_NORMAL_INSTANCE_TOKEN,
    INVALID_SCHEMA_VERSION,
    RECORD_ID_MISMATCH
}
