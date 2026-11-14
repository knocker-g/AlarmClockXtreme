package com.sysadmindoc.alarmclock.directboot

import com.sysadmindoc.alarmclock.data.model.Alarm
import org.json.JSONException
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Inactive versioned Direct Boot Queue candidate representation for KNO-75.
 *
 * Invariants:
 * - Domain distinction between [Alarm.KIND_NORMAL] ("NORMAL") and [Alarm.KIND_QUICK] ("QUICK").
 * - Concrete epoch trigger instant [triggerTime] in milliseconds.
 * - No user-identifiable labels, contact data, audio URIs, Guardian credentials, or secrets.
 * - Serialization strictly uses safe generated time representation ([generateSafeTimeLabel]).
 * - Deterministic chronological ordering via [QUEUE_COMPARATOR] with stable tie-breaker.
 * - Missing authority instanceToken (for Normal) is explicitly ineligible.
 * - V1 fallback snapshot never fabricates generation or authority lineage.
 * - Unknown or invalid item schema versions fail closed.
 */
data class DirectBootQueueItem(
    val kind: String,                      // "NORMAL" or "QUICK"
    val alarmId: Long,                     // Database primary key
    val triggerTime: Long,                 // Concrete epoch trigger instant in millis
    val generation: Int = 1,               // Schedule generation (Normal: >= 1; Quick: 0 or 1; Legacy V1: 0)
    val scheduleKind: String = Alarm.SCHEDULE_KIND_ONE_TIME, // "ONE_TIME" or "REPEATING"
    val instanceToken: String = "",        // ScheduleAuthority lineage token (Normal only; blank if missing)
    val timeLabel: String = "",            // Bounded formatted trigger time ("7:00 AM", max 32 chars)
    val playDefaultSound: Boolean = true,  // Privacy-safe boolean flag (no audio URIs)
    val vibrationEnabled: Boolean = true,  // Vibration toggle
    val updatedAt: Long = 0L,              // Wall-clock instant when snapshot was created
    val timezonePolicy: String = Alarm.TIMEZONE_POLICY_LOCAL,
    val fixedTimezoneId: String = "",
    val restoreEpoch: Long = 0L,
    val schemaVersion: Int = if (restoreEpoch > 0L) CURRENT_SCHEMA_VERSION else 2
) {
    val logicalId: QueueLogicalId get() = QueueLogicalId(kind, alarmId)

    val occurrenceId: String get() = if (kind == KIND_NORMAL) {
        "${kind}_${alarmId}_gen${generation}_${instanceToken}"
    } else {
        "${kind}_${alarmId}_${triggerTime}"
    }

    /**
     * Evaluates candidate eligibility according to KNO-75 invariants.
     * Missing authority instanceToken is flagged explicitly as ineligible.
     * Unknown/invalid schema versions fail closed.
     */
    fun evaluateEligibility(now: Long = 0L, allowLegacyV1: Boolean = false): QueueCandidateStatus {
        if (schemaVersion != CURRENT_SCHEMA_VERSION && schemaVersion != 2 && !(allowLegacyV1 && schemaVersion == 1)) {
            return QueueCandidateStatus.INELIGIBLE_UNSUPPORTED_SCHEMA_VERSION
        }
        if (alarmId <= 0L) return QueueCandidateStatus.INELIGIBLE_INVALID_ID
        if (triggerTime <= 0L || (now > 0L && triggerTime <= now)) {
            return QueueCandidateStatus.INELIGIBLE_INVALID_TRIGGER_TIME
        }
        if (kind != KIND_NORMAL && kind != KIND_QUICK) return QueueCandidateStatus.INELIGIBLE_UNKNOWN_KIND

        if (kind == KIND_NORMAL) {
            if (generation < 1 && !(allowLegacyV1 && generation == 0)) {
                return QueueCandidateStatus.INELIGIBLE_INVALID_GENERATION
            }
            if (instanceToken.isBlank() && !allowLegacyV1) {
                return QueueCandidateStatus.INELIGIBLE_MISSING_INSTANCE_TOKEN
            }
            if (schemaVersion >= 3 && restoreEpoch <= 0L) {
                return QueueCandidateStatus.INELIGIBLE_INVALID_RESTORE_EPOCH
            }
        } else if (kind == KIND_QUICK) {
            // For Quick alarms, triggerTime must be a concrete valid epoch instant (> now).
            // Lineage verification across boots is owned by the CE producer in Slice B.
            if (triggerTime <= 0L || (now > 0L && triggerTime <= now)) {
                return QueueCandidateStatus.INELIGIBLE_INVALID_TRIGGER_TIME
            }
        }

        return QueueCandidateStatus.ELIGIBLE
    }

    fun isEligible(now: Long = 0L, allowLegacyV1: Boolean = false): Boolean =
        evaluateEligibility(now, allowLegacyV1) == QueueCandidateStatus.ELIGIBLE

    /**
     * Serializes candidate into JSON for Device Protected Storage.
     * Privacy invariant: [timeLabel] is strictly generated from [triggerTime] to prevent
     * persisting user-authored labels, secrets, or contact information.
     */
    fun toJson(): JSONObject = JSONObject().apply {
        put("kind", kind)
        put("alarmId", alarmId)
        put("triggerTime", triggerTime)
        put("generation", generation)
        put("scheduleKind", scheduleKind)
        put("instanceToken", instanceToken)
        put("timeLabel", generateSafeTimeLabel(triggerTime, timezonePolicy, fixedTimezoneId))
        put("playDefaultSound", playDefaultSound)
        put("vibrationEnabled", vibrationEnabled)
        put("updatedAt", updatedAt)
        put("timezonePolicy", timezonePolicy)
        put("fixedTimezoneId", fixedTimezoneId)
        put("restoreEpoch", restoreEpoch)
        put("schemaVersion", schemaVersion)
    }

    fun toSnapshot(): DirectBootAlarmSnapshot {
        return DirectBootAlarmSnapshot(
            alarmId = alarmId,
            triggerTime = triggerTime,
            label = "",
            timeLabel = generateSafeTimeLabel(triggerTime, timezonePolicy, fixedTimezoneId),
            playDefaultSound = playDefaultSound,
            vibrationEnabled = vibrationEnabled,
            updatedAt = updatedAt,
            timezonePolicy = timezonePolicy,
            fixedTimezoneId = fixedTimezoneId,
            schemaVersion = DirectBootAlarmSnapshot.CURRENT_SCHEMA_VERSION
        )
    }

    companion object {
        const val CURRENT_SCHEMA_VERSION = 3
        const val KIND_NORMAL = Alarm.KIND_NORMAL
        const val KIND_QUICK = Alarm.KIND_QUICK

        val QUEUE_COMPARATOR = Comparator<DirectBootQueueItem> { a, b ->
            val timeCmp = a.triggerTime.compareTo(b.triggerTime)
            if (timeCmp != 0) return@Comparator timeCmp
            val kindCmp = a.kind.compareTo(b.kind)
            if (kindCmp != 0) return@Comparator kindCmp
            val idCmp = a.alarmId.compareTo(b.alarmId)
            if (idCmp != 0) return@Comparator idCmp
            val genCmp = a.generation.compareTo(b.generation)
            if (genCmp != 0) return@Comparator genCmp
            a.restoreEpoch.compareTo(b.restoreEpoch)
        }

        /**
         * Formats a privacy-safe generic time representation from trigger epoch millis.
         * Guarantees that user-authored labels, contacts, or secrets are never exposed in DE storage.
         */
        fun generateSafeTimeLabel(
            triggerTime: Long,
            timezonePolicy: String = Alarm.TIMEZONE_POLICY_LOCAL,
            fixedTimezoneId: String = ""
        ): String {
            if (triggerTime <= 0L) return ""
            val zone = if (timezonePolicy == Alarm.TIMEZONE_POLICY_FIXED && fixedTimezoneId.isNotBlank()) {
                try {
                    ZoneId.of(fixedTimezoneId)
                } catch (_: Exception) {
                    ZoneId.systemDefault()
                }
            } else {
                ZoneId.systemDefault()
            }
            return try {
                Instant.ofEpochMilli(triggerTime)
                    .atZone(zone)
                    .format(DateTimeFormatter.ofPattern("h:mm a", Locale.US))
            } catch (_: Exception) {
                ""
            }
        }

        @Throws(JSONException::class)
        fun fromJson(json: JSONObject): DirectBootQueueItem {
            val triggerTime = json.optLong("triggerTime", 0L)
            val timezonePolicy = json.optString("timezonePolicy", Alarm.TIMEZONE_POLICY_LOCAL)
            val fixedTimezoneId = json.optString("fixedTimezoneId", "")
            return DirectBootQueueItem(
                kind = json.optString("kind", ""),
                alarmId = json.optLong("alarmId", -1L),
                triggerTime = triggerTime,
                generation = json.optInt("generation", -1),
                scheduleKind = json.optString("scheduleKind", Alarm.SCHEDULE_KIND_ONE_TIME),
                instanceToken = json.optString("instanceToken", ""),
                timeLabel = generateSafeTimeLabel(triggerTime, timezonePolicy, fixedTimezoneId),
                playDefaultSound = json.optBoolean("playDefaultSound", true),
                vibrationEnabled = json.optBoolean("vibrationEnabled", true),
                updatedAt = json.optLong("updatedAt", 0L),
                timezonePolicy = timezonePolicy,
                fixedTimezoneId = fixedTimezoneId,
                restoreEpoch = json.optLong("restoreEpoch", 0L),
                schemaVersion = json.optInt("schemaVersion", -1)
            )
        }

        fun fromV1Snapshot(snapshot: DirectBootAlarmSnapshot): DirectBootQueueItem {
            return DirectBootQueueItem(
                kind = KIND_NORMAL,
                alarmId = snapshot.alarmId,
                triggerTime = snapshot.triggerTime,
                generation = 0, // Explicitly 0: does not fabricate generation
                scheduleKind = Alarm.SCHEDULE_KIND_ONE_TIME,
                instanceToken = "", // Blank: does not fabricate authority lineage
                timeLabel = generateSafeTimeLabel(snapshot.triggerTime, snapshot.timezonePolicy, snapshot.fixedTimezoneId),
                playDefaultSound = snapshot.playDefaultSound,
                vibrationEnabled = snapshot.vibrationEnabled,
                updatedAt = snapshot.updatedAt,
                timezonePolicy = snapshot.timezonePolicy,
                fixedTimezoneId = snapshot.fixedTimezoneId,
                schemaVersion = 1 // Explicitly marks as legacy schema v1
            )
        }
    }
}

data class QueueLogicalId(
    val kind: String,
    val alarmId: Long
) {
    override fun toString(): String = "${kind}_${alarmId}"
}

enum class QueueCandidateStatus {
    ELIGIBLE,
    INELIGIBLE_INVALID_ID,
    INELIGIBLE_INVALID_TRIGGER_TIME,
    INELIGIBLE_UNKNOWN_KIND,
    INELIGIBLE_MISSING_INSTANCE_TOKEN,
    INELIGIBLE_INVALID_GENERATION,
    INELIGIBLE_INVALID_RESTORE_EPOCH,
    INELIGIBLE_UNSUPPORTED_SCHEMA_VERSION
}

sealed class QueueEnqueueResult {
    data class Enqueued(val head: DirectBootQueueItem, val queueSize: Int) : QueueEnqueueResult()
    data class DroppedByCapacity(val queueSize: Int) : QueueEnqueueResult()
    data class RejectedStaleGeneration(val currentGeneration: Int) : QueueEnqueueResult()
    data class RejectedIneligible(val status: QueueCandidateStatus) : QueueEnqueueResult()
    data class StorageCorrupt(val reason: String) : QueueEnqueueResult()
    data class UnsupportedSchema(val foundVersion: Int, val expectedVersion: Int) : QueueEnqueueResult()
    data class PersistenceFailed(val reason: String) : QueueEnqueueResult()
}
