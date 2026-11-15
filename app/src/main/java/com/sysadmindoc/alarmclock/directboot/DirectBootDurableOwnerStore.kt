package com.sysadmindoc.alarmclock.directboot

import android.content.Context
import android.content.SharedPreferences
import androidx.annotation.VisibleForTesting
import org.json.JSONException
import org.json.JSONObject
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import kotlin.math.abs

/**
 * Durable DE Owner lifecycle state for KNO-75 C7-B1c.
 */
enum class OwnerState {
    IDLE,
    HANDOVER_PENDING,
    V2_ACTIVE
}

/**
 * Handover claim carried while [OwnerState.HANDOVER_PENDING] awaits Service activation.
 */
data class HandoverClaim(
    val handoverId: String,
    val alarmId: Long,
    val triggerTime: Long,
    val generation: Int,
    val instanceToken: String,
    val restoreEpoch: Long
) {
    fun validate(): String? {
        return when {
            handoverId.isBlank() -> "handoverClaim.handoverId must not be blank"
            alarmId <= 0L -> "handoverClaim.alarmId must be positive, found $alarmId"
            triggerTime <= 0L -> "handoverClaim.triggerTime must be positive, found $triggerTime"
            generation < 1 -> "handoverClaim.generation must be >= 1, found $generation"
            instanceToken.isBlank() -> "handoverClaim.instanceToken must not be blank"
            restoreEpoch <= 0L -> "handoverClaim.restoreEpoch must be positive, found $restoreEpoch"
            else -> null
        }
    }

    fun toJson(): JSONObject = JSONObject().apply {
        put("handoverId", handoverId)
        put("alarmId", alarmId)
        put("triggerTime", triggerTime)
        put("generation", generation)
        put("instanceToken", instanceToken)
        put("restoreEpoch", restoreEpoch)
    }

    companion object {
        fun fromJson(json: JSONObject): HandoverClaim {
            val required = listOf(
                "handoverId", "alarmId", "triggerTime",
                "generation", "instanceToken", "restoreEpoch"
            )
            for (field in required) {
                if (!json.has(field) || json.isNull(field)) {
                    throw IllegalArgumentException("Missing or null handoverClaim.$field")
                }
            }
            val handoverIdRaw = json.get("handoverId")
            if (handoverIdRaw !is String) {
                throw IllegalArgumentException("handoverClaim.handoverId must be a String")
            }
            val alarmId = DirectBootRegistrationMarker.extractExactLong(json.get("alarmId"), "handoverClaim.alarmId")
            val triggerTime = DirectBootRegistrationMarker.extractExactLong(json.get("triggerTime"), "handoverClaim.triggerTime")
            val generation = DirectBootRegistrationMarker.extractExactInt(json.get("generation"), "handoverClaim.generation")
            val tokenRaw = json.get("instanceToken")
            if (tokenRaw !is String) {
                throw IllegalArgumentException("handoverClaim.instanceToken must be a String")
            }
            val restoreEpoch = DirectBootRegistrationMarker.extractExactLong(json.get("restoreEpoch"), "handoverClaim.restoreEpoch")

            val claim = HandoverClaim(
                handoverId = handoverIdRaw,
                alarmId = alarmId,
                triggerTime = triggerTime,
                generation = generation,
                instanceToken = tokenRaw,
                restoreEpoch = restoreEpoch
            )
            claim.validate()?.let { throw IllegalArgumentException(it) }
            return claim
        }
    }
}

/**
 * Active V2 playback session record carried while [OwnerState.V2_ACTIVE] holds ownership.
 */
data class ActiveOwnerSession(
    val alarmId: Long,
    val triggerTime: Long,
    val generation: Int,
    val instanceToken: String,
    val restoreEpoch: Long
) {
    fun validate(): String? {
        return when {
            alarmId <= 0L -> "activeSession.alarmId must be positive, found $alarmId"
            triggerTime <= 0L -> "activeSession.triggerTime must be positive, found $triggerTime"
            generation < 1 -> "activeSession.generation must be >= 1, found $generation"
            instanceToken.isBlank() -> "activeSession.instanceToken must not be blank"
            restoreEpoch <= 0L -> "activeSession.restoreEpoch must be positive, found $restoreEpoch"
            else -> null
        }
    }

    fun toJson(): JSONObject = JSONObject().apply {
        put("alarmId", alarmId)
        put("triggerTime", triggerTime)
        put("generation", generation)
        put("instanceToken", instanceToken)
        put("restoreEpoch", restoreEpoch)
    }

    companion object {
        fun fromJson(json: JSONObject): ActiveOwnerSession {
            val required = listOf(
                "alarmId", "triggerTime", "generation", "instanceToken", "restoreEpoch"
            )
            for (field in required) {
                if (!json.has(field) || json.isNull(field)) {
                    throw IllegalArgumentException("Missing or null activeSession.$field")
                }
            }
            val alarmId = DirectBootRegistrationMarker.extractExactLong(json.get("alarmId"), "activeSession.alarmId")
            val triggerTime = DirectBootRegistrationMarker.extractExactLong(json.get("triggerTime"), "activeSession.triggerTime")
            val generation = DirectBootRegistrationMarker.extractExactInt(json.get("generation"), "activeSession.generation")
            val tokenRaw = json.get("instanceToken")
            if (tokenRaw !is String) {
                throw IllegalArgumentException("activeSession.instanceToken must be a String")
            }
            val restoreEpoch = DirectBootRegistrationMarker.extractExactLong(json.get("restoreEpoch"), "activeSession.restoreEpoch")

            val session = ActiveOwnerSession(
                alarmId = alarmId,
                triggerTime = triggerTime,
                generation = generation,
                instanceToken = tokenRaw,
                restoreEpoch = restoreEpoch
            )
            session.validate()?.let { throw IllegalArgumentException(it) }
            return session
        }
    }
}

/**
 * Durable DE Owner payload stored under [DirectBootDurableOwnerStore.KEY_DIRECT_BOOT_DURABLE_OWNER].
 */
data class DirectBootDurableOwner(
    val schemaVersion: Int = DirectBootDurableOwnerStore.CURRENT_SCHEMA_VERSION,
    val state: OwnerState,
    val ownerSeq: Long = 0L,
    val bootProvenance: BootProvenance = BootProvenance(),
    val handoverClaim: HandoverClaim? = null,
    val activeSession: ActiveOwnerSession? = null,
    val updatedAtEpochMs: Long,
    val payloadDigest: String = ""
) {
    /**
     * Computes deterministic canonical UTF-8 SHA-256 integrity digest over all semantic fields.
     */
    fun computeDigest(): String {
        val hid = handoverClaim?.handoverId.orEmpty()
        val ctok = handoverClaim?.instanceToken.orEmpty()
        val stok = activeSession?.instanceToken.orEmpty()

        val raw = buildString {
            append("OWNER_V1\n")
            append("schemaVersion:").append(schemaVersion).append('\n')
            append("state:").append(state.name).append('\n')
            append("ownerSeq:").append(ownerSeq).append('\n')
            append("bootCount:").append(bootProvenance.bootCount?.toString() ?: "<null>").append('\n')
            append("lastElapsedRealtime:").append(bootProvenance.lastElapsedRealtime?.toString() ?: "<null>").append('\n')
            append("bootEpochEstimateMs:").append(bootProvenance.bootEpochEstimateMs?.toString() ?: "<null>").append('\n')
            append("handoverId:").append(hid.toByteArray(StandardCharsets.UTF_8).size).append(':').append(hid).append('\n')
            append("claimAlarmId:").append(handoverClaim?.alarmId ?: -1L).append('\n')
            append("claimTriggerTime:").append(handoverClaim?.triggerTime ?: 0L).append('\n')
            append("claimGeneration:").append(handoverClaim?.generation ?: 0).append('\n')
            append("claimInstanceToken:").append(ctok.toByteArray(StandardCharsets.UTF_8).size).append(':').append(ctok).append('\n')
            append("claimRestoreEpoch:").append(handoverClaim?.restoreEpoch ?: 0L).append('\n')
            append("sessionAlarmId:").append(activeSession?.alarmId ?: -1L).append('\n')
            append("sessionTriggerTime:").append(activeSession?.triggerTime ?: 0L).append('\n')
            append("sessionGeneration:").append(activeSession?.generation ?: 0).append('\n')
            append("sessionInstanceToken:").append(stok.toByteArray(StandardCharsets.UTF_8).size).append(':').append(stok).append('\n')
            append("sessionRestoreEpoch:").append(activeSession?.restoreEpoch ?: 0L).append('\n')
            append("updatedAtEpochMs:").append(updatedAtEpochMs).append('\n')
        }
        val md = MessageDigest.getInstance("SHA-256")
        val digestBytes = md.digest(raw.toByteArray(StandardCharsets.UTF_8))
        return digestBytes.joinToString("") { "%02x".format(it) }
    }

    /**
     * Validates structural and state-transition invariants. Returns null if valid.
     */
    fun validate(): String? {
        if (schemaVersion != DirectBootDurableOwnerStore.CURRENT_SCHEMA_VERSION) {
            return "Unsupported schemaVersion $schemaVersion (expected ${DirectBootDurableOwnerStore.CURRENT_SCHEMA_VERSION})"
        }
        if (ownerSeq < 0L) {
            return "ownerSeq must be non-negative, found $ownerSeq"
        }
        if (updatedAtEpochMs < 0L) {
            return "updatedAtEpochMs must be non-negative, found $updatedAtEpochMs"
        }
        if (payloadDigest.isNotEmpty() && payloadDigest != computeDigest()) {
            return "payloadDigest mismatch: expected ${computeDigest()}, found $payloadDigest"
        }

        return when (state) {
            OwnerState.IDLE -> when {
                handoverClaim != null -> "IDLE state requires null handoverClaim"
                activeSession != null -> "IDLE state requires null activeSession"
                else -> null
            }
            OwnerState.HANDOVER_PENDING -> when {
                ownerSeq < 1L -> "HANDOVER_PENDING state requires ownerSeq >= 1, found $ownerSeq"
                handoverClaim == null -> "HANDOVER_PENDING state requires non-null handoverClaim"
                activeSession != null -> "HANDOVER_PENDING state requires null activeSession"
                else -> handoverClaim.validate()
            }
            OwnerState.V2_ACTIVE -> when {
                ownerSeq < 1L -> "V2_ACTIVE state requires ownerSeq >= 1, found $ownerSeq"
                handoverClaim != null -> "V2_ACTIVE state requires null handoverClaim"
                activeSession == null -> "V2_ACTIVE state requires non-null activeSession"
                else -> activeSession.validate()
            }
        }
    }

    fun toJson(): JSONObject = JSONObject().apply {
        put("schemaVersion", schemaVersion)
        put("state", state.name)
        put("ownerSeq", ownerSeq)
        put("bootProvenance", bootProvenance.toJson())
        put("handoverClaim", handoverClaim?.toJson() ?: JSONObject.NULL)
        put("activeSession", activeSession?.toJson() ?: JSONObject.NULL)
        put("updatedAtEpochMs", updatedAtEpochMs)
        put("payloadDigest", payloadDigest)
    }

    fun toJsonString(): String = toJson().toString()
}

sealed class OwnerReadResult {
    object Absent : OwnerReadResult()
    data class Success(val owner: DirectBootDurableOwner) : OwnerReadResult()
    data class Corrupt(val reason: String, val rawPayload: String?) : OwnerReadResult()
    data class UnsupportedSchema(
        val foundVersion: Int,
        val expectedVersion: Int,
        val rawPayload: String?
    ) : OwnerReadResult()

    val isSuccess: Boolean get() = this is Success
}

sealed class OwnerWriteResult {
    data class Success(val owner: DirectBootDurableOwner) : OwnerWriteResult()
    data class SequenceMismatch(val expectedSeq: Long, val actualSeq: Long) : OwnerWriteResult()
    data class SequenceOverflow(val currentSeq: Long) : OwnerWriteResult()
    data class StorageCorrupt(val reason: String) : OwnerWriteResult()
    data class UnsupportedSchema(val foundVersion: Int, val expectedVersion: Int) : OwnerWriteResult()
    data class InvalidTransition(val reason: String) : OwnerWriteResult()
    object CommitFailed : OwnerWriteResult()
    data class CommitException(val cause: Throwable) : OwnerWriteResult()

    val isSuccess: Boolean get() = this is Success
}

sealed class OwnerAtomicWritePrepResult {
    data class Prepared(
        val owner: DirectBootDurableOwner,
        val payloadJson: String
    ) : OwnerAtomicWritePrepResult()

    data class SequenceMismatch(val expectedSeq: Long, val actualSeq: Long) : OwnerAtomicWritePrepResult()
    data class SequenceOverflow(val currentSeq: Long) : OwnerAtomicWritePrepResult()
    data class StorageCorrupt(val reason: String) : OwnerAtomicWritePrepResult()
    data class UnsupportedSchema(val foundVersion: Int, val expectedVersion: Int) : OwnerAtomicWritePrepResult()
    data class InvalidTransition(val reason: String) : OwnerAtomicWritePrepResult()
}

/**
 * Isolated Device-Encrypted (DE) Durable Owner Store for KNO-75 C7-B1c S1.
 *
 * Invariants:
 * - Co-located in [PREFS_NAME] ("direct_boot_next_alarm") under private key [KEY_DIRECT_BOOT_DURABLE_OWNER]
 *   so Queue, Delivery Ledger, and Durable Owner can be committed in a single synchronous [SharedPreferences.Editor.commit].
 * - Protected exclusively by [DirectBootStorageLock.lock].
 * - Fail-closed on corrupt/unsupported payloads with zero silent clearing or auto-migration: raw bytes on disk
 *   are preserved completely untouched.
 * - Strict CAS monotonic [DirectBootDurableOwner.ownerSeq] advancement starting from absent = 0L, rejecting
 *   stale expected sequences and [Long.MAX_VALUE] overflow.
 */
object DirectBootDurableOwnerStore {
    const val PREFS_NAME = "direct_boot_next_alarm"
    const val KEY_DIRECT_BOOT_DURABLE_OWNER = "direct_boot_durable_owner"
    const val CURRENT_SCHEMA_VERSION = 1
    const val MAX_BOOT_EPOCH_DRIFT_MS = 30_000L

    private val defaultEditorCommitter: (SharedPreferences, String) -> Boolean =
        { prefs, payload ->
            prefs.edit().putString(KEY_DIRECT_BOOT_DURABLE_OWNER, payload).commit()
        }

    /**
     * Creates an initial [OwnerState.IDLE] owner record with sequence 0 and computed digest.
     */
    @JvmOverloads
    fun createInitialIdle(
        bootProvenance: BootProvenance = BootProvenance(),
        nowEpochMs: Long = System.currentTimeMillis()
    ): DirectBootDurableOwner {
        val owner = DirectBootDurableOwner(
            schemaVersion = CURRENT_SCHEMA_VERSION,
            state = OwnerState.IDLE,
            ownerSeq = 0L,
            bootProvenance = bootProvenance,
            handoverClaim = null,
            activeSession = null,
            updatedAtEpochMs = nowEpochMs,
            payloadDigest = ""
        )
        return owner.copy(payloadDigest = owner.computeDigest())
    }

    /**
     * Checks whether [current] boot provenance belongs to the same boot session as [persisted].
     * Fails closed (returns false) if either provenance is unknown, bootCount differs, elapsedRealtime regressed,
     * bootEpochEstimateMs drifted beyond [MAX_BOOT_EPOCH_DRIFT_MS], or provenance fields are asymmetrically absent.
     */
    fun isSameBootProvenance(
        persisted: BootProvenance,
        current: BootProvenance
    ): Boolean {
        if (persisted.isUnknown || current.isUnknown) {
            return false
        }
        if ((persisted.bootCount == null) != (current.bootCount == null)) {
            return false
        }
        if (persisted.bootCount != null && persisted.bootCount != current.bootCount) {
            return false
        }
        if ((persisted.lastElapsedRealtime == null) != (current.lastElapsedRealtime == null)) {
            return false
        }
        if (persisted.lastElapsedRealtime != null && current.lastElapsedRealtime != null &&
            current.lastElapsedRealtime < persisted.lastElapsedRealtime
        ) {
            return false
        }
        if ((persisted.bootEpochEstimateMs == null) != (current.bootEpochEstimateMs == null)) {
            return false
        }
        if (persisted.bootEpochEstimateMs != null && current.bootEpochEstimateMs != null &&
            abs(current.bootEpochEstimateMs - persisted.bootEpochEstimateMs) > MAX_BOOT_EPOCH_DRIFT_MS
        ) {
            return false
        }
        if (persisted.bootCount == null && persisted.lastElapsedRealtime == null) {
            return false
        }
        return true
    }

    /**
     * Strictly parses a raw payload string into [OwnerReadResult].
     * Never clears or mutates any storage.
     */
    fun parse(rawPayload: String?): OwnerReadResult {
        if (rawPayload == null) {
            return OwnerReadResult.Corrupt("Owner payload is null", null)
        }
        if (rawPayload.isBlank()) {
            return OwnerReadResult.Corrupt("Owner payload is blank or empty", rawPayload)
        }

        return try {
            val json = try {
                JSONObject(rawPayload)
            } catch (e: JSONException) {
                return OwnerReadResult.Corrupt("Malformed JSON: ${e.message}", rawPayload)
            }

            val requiredFields = listOf(
                "schemaVersion", "state", "ownerSeq", "updatedAtEpochMs", "payloadDigest"
            )
            for (field in requiredFields) {
                if (!json.has(field)) {
                    return OwnerReadResult.Corrupt("Missing required field '$field'", rawPayload)
                }
                if (json.isNull(field)) {
                    return OwnerReadResult.Corrupt("Mandatory field '$field' cannot be null", rawPayload)
                }
            }

            val schemaVersion = DirectBootRegistrationMarker.extractExactInt(
                json.get("schemaVersion"),
                "schemaVersion"
            )
            if (schemaVersion != CURRENT_SCHEMA_VERSION) {
                return OwnerReadResult.UnsupportedSchema(
                    foundVersion = schemaVersion,
                    expectedVersion = CURRENT_SCHEMA_VERSION,
                    rawPayload = rawPayload
                )
            }

            val stateRaw = json.get("state")
            if (stateRaw !is String) {
                return OwnerReadResult.Corrupt("Field 'state' must be a string", rawPayload)
            }
            val state = try {
                OwnerState.valueOf(stateRaw)
            } catch (_: IllegalArgumentException) {
                return OwnerReadResult.Corrupt("Invalid state enum value '$stateRaw'", rawPayload)
            }

            val ownerSeq = DirectBootRegistrationMarker.extractExactLong(
                json.get("ownerSeq"),
                "ownerSeq"
            )
            if (ownerSeq < 0L) {
                return OwnerReadResult.Corrupt("ownerSeq must be non-negative: $ownerSeq", rawPayload)
            }

            val updatedAtEpochMs = DirectBootRegistrationMarker.extractExactLong(
                json.get("updatedAtEpochMs"),
                "updatedAtEpochMs"
            )
            if (updatedAtEpochMs < 0L) {
                return OwnerReadResult.Corrupt("updatedAtEpochMs must be non-negative: $updatedAtEpochMs", rawPayload)
            }

            val payloadDigestRaw = json.get("payloadDigest")
            if (payloadDigestRaw !is String || payloadDigestRaw.isBlank()) {
                return OwnerReadResult.Corrupt("Field 'payloadDigest' must be a non-blank string", rawPayload)
            }

            val bootProvenance = if (json.has("bootProvenance") && !json.isNull("bootProvenance")) {
                val bpObj = json.get("bootProvenance")
                if (bpObj !is JSONObject) {
                    return OwnerReadResult.Corrupt("Field 'bootProvenance' must be a JSONObject", rawPayload)
                }
                try {
                    BootProvenance.fromJson(bpObj)
                } catch (e: Exception) {
                    return OwnerReadResult.Corrupt("Invalid bootProvenance: ${e.message}", rawPayload)
                }
            } else {
                BootProvenance()
            }

            val handoverClaim = if (json.has("handoverClaim") && !json.isNull("handoverClaim")) {
                val claimObj = json.get("handoverClaim")
                if (claimObj !is JSONObject) {
                    return OwnerReadResult.Corrupt("Field 'handoverClaim' must be a JSONObject", rawPayload)
                }
                try {
                    HandoverClaim.fromJson(claimObj)
                } catch (e: Exception) {
                    return OwnerReadResult.Corrupt("Invalid handoverClaim: ${e.message}", rawPayload)
                }
            } else {
                null
            }

            val activeSession = if (json.has("activeSession") && !json.isNull("activeSession")) {
                val sessObj = json.get("activeSession")
                if (sessObj !is JSONObject) {
                    return OwnerReadResult.Corrupt("Field 'activeSession' must be a JSONObject", rawPayload)
                }
                try {
                    ActiveOwnerSession.fromJson(sessObj)
                } catch (e: Exception) {
                    return OwnerReadResult.Corrupt("Invalid activeSession: ${e.message}", rawPayload)
                }
            } else {
                null
            }

            val owner = DirectBootDurableOwner(
                schemaVersion = schemaVersion,
                state = state,
                ownerSeq = ownerSeq,
                bootProvenance = bootProvenance,
                handoverClaim = handoverClaim,
                activeSession = activeSession,
                updatedAtEpochMs = updatedAtEpochMs,
                payloadDigest = payloadDigestRaw
            )

            val expectedDigest = owner.computeDigest()
            if (payloadDigestRaw != expectedDigest) {
                return OwnerReadResult.Corrupt(
                    "Owner payloadDigest mismatch: expected $expectedDigest, found $payloadDigestRaw",
                    rawPayload
                )
            }

            val validationError = owner.validate()
            if (validationError != null) {
                return OwnerReadResult.Corrupt("Owner validation failed: $validationError", rawPayload)
            }

            OwnerReadResult.Success(owner)
        } catch (e: Exception) {
            OwnerReadResult.Corrupt("Unexpected parsing error: ${e.message}", rawPayload)
        }
    }

    /**
     * Reads the durable owner from Device-Protected storage under [DirectBootStorageLock.lock].
     */
    fun read(context: Context): OwnerReadResult {
        return try {
            val prefs = dePrefs(context)
            val raw = synchronized(DirectBootStorageLock.lock) {
                if (!prefs.contains(KEY_DIRECT_BOOT_DURABLE_OWNER)) {
                    return OwnerReadResult.Absent
                }
                try {
                    prefs.getString(KEY_DIRECT_BOOT_DURABLE_OWNER, null)
                } catch (cce: ClassCastException) {
                    return OwnerReadResult.Corrupt(
                        "Stored value is not a String: ${cce.message}",
                        null
                    )
                }
            }
            if (raw == null) {
                return OwnerReadResult.Corrupt(
                    "Key '$KEY_DIRECT_BOOT_DURABLE_OWNER' present with null payload",
                    null
                )
            }
            parse(raw)
        } catch (e: Exception) {
            OwnerReadResult.Corrupt("Exception reading durable owner: ${e.message}", null)
        }
    }

    /**
     * Validates CAS sequence, constructs the next monotonic owner state with fresh SHA-256 digest,
     * and returns the serialized JSON payload WITHOUT mutating storage so callers can combine it
     * with Queue/DeliveryLedger updates in a single [SharedPreferences.Editor.commit].
     */
    @JvmOverloads
    fun prepareAtomicWritePayload(
        context: Context,
        owner: DirectBootDurableOwner,
        expectedOwnerSeq: Long? = null
    ): OwnerAtomicWritePrepResult {
        val prefs = dePrefs(context)
        synchronized(DirectBootStorageLock.lock) {
            return prepareLocked(prefs, owner, expectedOwnerSeq)
        }
    }

    /**
     * Writes the durable owner to Device-Protected storage under [DirectBootStorageLock.lock]
     * using strict CAS sequence advancement (`currentSeq -> currentSeq + 1`).
     */
    @JvmOverloads
    fun write(
        context: Context,
        owner: DirectBootDurableOwner,
        expectedOwnerSeq: Long? = null
    ): OwnerWriteResult {
        return writeInternal(
            context = context,
            owner = owner,
            expectedOwnerSeq = expectedOwnerSeq,
            editorCommitter = null
        )
    }

    @VisibleForTesting
    internal fun writeInternal(
        context: Context,
        owner: DirectBootDurableOwner,
        expectedOwnerSeq: Long? = null,
        editorCommitter: ((SharedPreferences, String) -> Boolean)? = null
    ): OwnerWriteResult {
        val prefs = dePrefs(context)
        val committer = editorCommitter ?: defaultEditorCommitter

        synchronized(DirectBootStorageLock.lock) {
            return when (val prep = prepareLocked(prefs, owner, expectedOwnerSeq)) {
                is OwnerAtomicWritePrepResult.StorageCorrupt ->
                    OwnerWriteResult.StorageCorrupt(prep.reason)
                is OwnerAtomicWritePrepResult.UnsupportedSchema ->
                    OwnerWriteResult.UnsupportedSchema(prep.foundVersion, prep.expectedVersion)
                is OwnerAtomicWritePrepResult.SequenceMismatch ->
                    OwnerWriteResult.SequenceMismatch(prep.expectedSeq, prep.actualSeq)
                is OwnerAtomicWritePrepResult.SequenceOverflow ->
                    OwnerWriteResult.SequenceOverflow(prep.currentSeq)
                is OwnerAtomicWritePrepResult.InvalidTransition ->
                    OwnerWriteResult.InvalidTransition(prep.reason)
                is OwnerAtomicWritePrepResult.Prepared -> {
                    try {
                        val committed = committer(prefs, prep.payloadJson)
                        if (committed) {
                            OwnerWriteResult.Success(prep.owner)
                        } else {
                            OwnerWriteResult.CommitFailed
                        }
                    } catch (e: Exception) {
                        OwnerWriteResult.CommitException(e)
                    }
                }
            }
        }
    }

    private fun prepareLocked(
        prefs: SharedPreferences,
        owner: DirectBootDurableOwner,
        expectedOwnerSeq: Long?
    ): OwnerAtomicWritePrepResult {
        val currentSeq: Long
        if (prefs.contains(KEY_DIRECT_BOOT_DURABLE_OWNER)) {
            val currentRaw = try {
                prefs.getString(KEY_DIRECT_BOOT_DURABLE_OWNER, null)
            } catch (cce: ClassCastException) {
                return OwnerAtomicWritePrepResult.StorageCorrupt(
                    "Stored value is not a String: ${cce.message}"
                )
            }
            if (currentRaw == null) {
                return OwnerAtomicWritePrepResult.StorageCorrupt("Key present with null payload")
            }
            when (val currentRead = parse(currentRaw)) {
                is OwnerReadResult.Corrupt ->
                    return OwnerAtomicWritePrepResult.StorageCorrupt(currentRead.reason)
                is OwnerReadResult.UnsupportedSchema ->
                    return OwnerAtomicWritePrepResult.UnsupportedSchema(
                        foundVersion = currentRead.foundVersion,
                        expectedVersion = currentRead.expectedVersion
                    )
                is OwnerReadResult.Success ->
                    currentSeq = currentRead.owner.ownerSeq
                is OwnerReadResult.Absent ->
                    currentSeq = 0L
            }
        } else {
            currentSeq = 0L
        }

        val effectiveExpected = expectedOwnerSeq ?: owner.ownerSeq
        if (effectiveExpected != currentSeq) {
            return OwnerAtomicWritePrepResult.SequenceMismatch(
                expectedSeq = effectiveExpected,
                actualSeq = currentSeq
            )
        }

        if (currentSeq == Long.MAX_VALUE) {
            return OwnerAtomicWritePrepResult.SequenceOverflow(currentSeq)
        }

        val nextSeq = currentSeq + 1L
        val candidate = owner.copy(ownerSeq = nextSeq, payloadDigest = "")
        val committed = candidate.copy(payloadDigest = candidate.computeDigest())

        val validationError = committed.validate()
        if (validationError != null) {
            return OwnerAtomicWritePrepResult.InvalidTransition(validationError)
        }

        return OwnerAtomicWritePrepResult.Prepared(
            owner = committed,
            payloadJson = committed.toJsonString()
        )
    }

    private fun dePrefs(context: Context): SharedPreferences {
        val deCtx = if (context.isDeviceProtectedStorage) {
            context
        } else {
            context.createDeviceProtectedStorageContext()
        }
        return deCtx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }
}
