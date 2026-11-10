package com.sysadmindoc.alarmclock.directboot

import android.content.Context
import android.content.SharedPreferences
import androidx.annotation.VisibleForTesting
import org.json.JSONException
import org.json.JSONObject
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/**
 * Main lifecycle states for Direct Boot V2 exact alarm registration.
 */
enum class RegistrationMainState {
    IDLE,
    IN_FLIGHT,
    FAILED_BLOCKED
}

/**
 * Fine-grained operational phases during registration execution.
 */
enum class RegistrationPhase {
    NONE,
    CANCELLING_OLD,
    REGISTERING_NEW,
    COMPENSATING
}

/**
 * Explicit failure reasons for blocked or failed registration states.
 */
enum class RegistrationFailureReason {
    NONE,
    PERMISSION_DENIED,
    SECURITY_EXCEPTION,
    IPC_EXCEPTION,
    OS_UNCERTAIN,
    POST_OP_STORAGE_UNVERIFIABLE,
    QUEUE_CORRUPT,
    QUEUE_UNSUPPORTED,
    STALE_LEASE_SUPERSEDED
}

/**
 * Boot provenance capturing device boot status without assuming elapsedRealtime is globally unique.
 *
 * @param bootCount Monotonically increasing boot counter from Settings.Global, or null if unknown/unsupported.
 * @param lastElapsedRealtime System uptime in milliseconds at last update, or null if unknown.
 * @param bootEpochEstimateMs Estimated wall-clock timestamp of boot epoch, or null if unavailable.
 */
data class BootProvenance(
    val bootCount: Int? = null,
    val lastElapsedRealtime: Long? = null,
    val bootEpochEstimateMs: Long? = null
) {
    init {
        bootCount?.let { require(it >= -1) { "bootCount must be >= -1 or null, found $it" } }
        lastElapsedRealtime?.let { require(it >= 0L) { "lastElapsedRealtime must be >= 0 or null, found $it" } }
        bootEpochEstimateMs?.let { require(it >= 0L) { "bootEpochEstimateMs must be >= 0 or null, found $it" } }
    }

    val isUnknown: Boolean
        get() = bootCount == null && lastElapsedRealtime == null && bootEpochEstimateMs == null

    /**
     * Unambiguous canonical framing of boot provenance fields for marker-wide checksum.
     */
    fun canonicalString(): String = buildString {
        append("bootCount=").append(bootCount?.toString() ?: "<null>").append(';')
        append("lastElapsedRealtime=").append(lastElapsedRealtime?.toString() ?: "<null>").append(';')
        append("bootEpochEstimateMs=").append(bootEpochEstimateMs?.toString() ?: "<null>")
    }

    fun toJson(): JSONObject {
        return JSONObject().apply {
            if (bootCount != null) put("bootCount", bootCount)
            if (lastElapsedRealtime != null) put("lastElapsedRealtime", lastElapsedRealtime)
            if (bootEpochEstimateMs != null) put("bootEpochEstimateMs", bootEpochEstimateMs)
        }
    }

    companion object {
        fun fromJson(json: JSONObject?): BootProvenance {
            if (json == null) return BootProvenance()
            val bootCount = if (json.has("bootCount") && !json.isNull("bootCount")) {
                DirectBootRegistrationMarker.extractExactInt(json.get("bootCount"), "bootCount")
            } else null
            val lastElapsed = if (json.has("lastElapsedRealtime") && !json.isNull("lastElapsedRealtime")) {
                DirectBootRegistrationMarker.extractExactLong(json.get("lastElapsedRealtime"), "lastElapsedRealtime")
            } else null
            val bootEpoch = if (json.has("bootEpochEstimateMs") && !json.isNull("bootEpochEstimateMs")) {
                DirectBootRegistrationMarker.extractExactLong(json.get("bootEpochEstimateMs"), "bootEpochEstimateMs")
            } else null
            return BootProvenance(
                bootCount = bootCount,
                lastElapsedRealtime = lastElapsed,
                bootEpochEstimateMs = bootEpoch
            )
        }
    }
}

/**
 * Result hierarchy for reading the durable V2 registration marker.
 */
sealed class MarkerReadResult {
    data class Success(val marker: DirectBootRegistrationMarker) : MarkerReadResult()
    object Absent : MarkerReadResult()
    data class Corrupt(val reason: String, val rawPayload: String?) : MarkerReadResult()
    data class UnsupportedSchema(
        val foundVersion: Int,
        val expectedVersion: Int,
        val rawPayload: String?
    ) : MarkerReadResult()

    val isSuccess: Boolean get() = this is Success
}

/**
 * Result hierarchy for writing the durable V2 registration marker.
 */
sealed class MarkerWriteResult {
    data class Success(val marker: DirectBootRegistrationMarker) : MarkerWriteResult()
    object CommitFailed : MarkerWriteResult()
    data class CommitException(val cause: Throwable) : MarkerWriteResult()
    data class InvalidTransition(val reason: String) : MarkerWriteResult()
    data class SequenceMismatch(val expectedSeq: Long, val actualSeq: Long) : MarkerWriteResult()
    data class SequenceOverflow(val currentSeq: Long) : MarkerWriteResult()
    data class StorageCorrupt(val reason: String) : MarkerWriteResult()
    data class UnsupportedSchema(val foundVersion: Int, val expectedVersion: Int) : MarkerWriteResult()

    val isSuccess: Boolean get() = this is Success
}

/**
 * Durable Direct Boot V2 Registration Marker for KNO-75 Phase D3-B2-P1 / D3-B2-P1B.
 *
 * Responsibilities & Invariants:
 * - Persisted under private key [KEY_V2_REGISTRATION_MARKER] in [PREFS_NAME] ("direct_boot_next_alarm").
 * - Isolated from Queue payload ([DirectBootAlarmCache.KEY_QUEUE_PAYLOAD]) and Delivery Ledger
 *   payload ([DirectBootDeliveryLedger.KEY_DELIVERY_LEDGER_PAYLOAD]).
 * - Synchronized via [DirectBootStorageLock.lock] across all read/write operations.
 * - Single source of truth for durable registration intent, in-flight operation fencing, retry state,
 *   and unresolved crash/uncertainty recovery targets.
 * - Strict canonical serialization with versioning ([CURRENT_SCHEMA_VERSION] = 1).
 * - Strict parser validation: fail-closed on corrupt payload, missing fields, wrong types, or unsupported schema.
 * - Exact integral JSON validation: rejects fractional numbers, floating-point representations, and integer overflows.
 * - Zero silent reset: malformed existing raw payload is preserved intact on disk.
 * - Immutable PendingIntent identity verification: validates active, cancelling, and registering identities
 *   independently, verifying SHA-256 occurrence digests to detect tampering or corruption.
 * - FAILED_BLOCKED recovery target preservation: retains unresolved cancelling/registering identities without
 *   silent deletion upon retry exhaustion, enabling later reconciler inspection.
 * - Marker-wide payload integrity checksum ([payloadDigest]): deterministic SHA-256 integrity checksum
 *   over all 13 semantic fields using explicit delimiters and null framing.
 *   Note: An unkeyed SHA-256 digest is an integrity checksum, NOT an authenticity guarantee against an
 *   attacker who can rewrite both payload and digest.
 * - Monotonic atomic operation sequencing: enforces next sequence = current + 1 under [DirectBootStorageLock.lock],
 *   rejecting stale, unchanged, regressed, or overflowing sequences.
 */
data class DirectBootRegistrationMarker(
    val schemaVersion: Int = CURRENT_SCHEMA_VERSION,
    val mainState: RegistrationMainState,
    val phase: RegistrationPhase,
    val failureReason: RegistrationFailureReason = RegistrationFailureReason.NONE,
    val retryCount: Int = 0,
    val opSeq: Long = 0L,
    val leaseOwner: String = "",
    val leaseEpochMs: Long = 0L,
    val bootProvenance: BootProvenance = BootProvenance(),
    val activeIdentity: DirectBootV2NormalIntentIdentity? = null,
    val cancellingTarget: DirectBootV2NormalIntentIdentity? = null,
    val registeringTarget: DirectBootV2NormalIntentIdentity? = null,
    val updatedAtEpochMs: Long,
    val payloadDigest: String = ""
) {

    /**
     * Computes deterministic SHA-256 checksum over all semantic fields excluding [payloadDigest].
     * Uses explicit UTF-8 length and delimiter framing to eliminate injection risks.
     */
    fun computeDigest(): String {
        val raw = buildString {
            append("MARKER_V1\n")
            append("schemaVersion:").append(schemaVersion).append('\n')
            append("mainState:").append(mainState.name).append('\n')
            append("phase:").append(phase.name).append('\n')
            append("failureReason:").append(failureReason.name).append('\n')
            append("retryCount:").append(retryCount).append('\n')
            append("opSeq:").append(opSeq).append('\n')
            val leaseOwnerBytes = leaseOwner.toByteArray(StandardCharsets.UTF_8).size
            append("leaseOwner:").append(leaseOwnerBytes).append(':').append(leaseOwner).append('\n')
            append("leaseEpochMs:").append(leaseEpochMs).append('\n')
            append("bootProvenance:").append(bootProvenance.canonicalString()).append('\n')
            append("activeIdentity:").append(activeIdentity?.canonicalString() ?: "<null>").append('\n')
            append("cancellingTarget:").append(cancellingTarget?.canonicalString() ?: "<null>").append('\n')
            append("registeringTarget:").append(registeringTarget?.canonicalString() ?: "<null>").append('\n')
            append("updatedAtEpochMs:").append(updatedAtEpochMs).append('\n')
        }
        val md = MessageDigest.getInstance("SHA-256")
        val digestBytes = md.digest(raw.toByteArray(StandardCharsets.UTF_8))
        return digestBytes.joinToString("") { "%02x".format(it) }
    }

    /**
     * Validates structural invariants and state/phase combinations.
     * Returns null if valid, or a descriptive error message if invalid.
     */
    fun validate(): String? {
        var validationError: String? = null
        if (schemaVersion != CURRENT_SCHEMA_VERSION) {
            val msg = "Unsupported schemaVersion $schemaVersion (expected $CURRENT_SCHEMA_VERSION)"
            validationError = msg
        } else if (retryCount !in 0..MAX_RETRIES) {
            val msg = "retryCount out of bounds: $retryCount (must be in 0..$MAX_RETRIES)"
            validationError = msg
        } else if (opSeq < 0L) {
            val msg = "opSeq must be non-negative: $opSeq"
            validationError = msg
        } else if (updatedAtEpochMs < 0L) {
            val msg = "updatedAtEpochMs must be non-negative: $updatedAtEpochMs"
            validationError = msg
        } else if (payloadDigest.isNotEmpty() && payloadDigest != computeDigest()) {
            val msg = "payloadDigest mismatch: expected ${computeDigest()}, found $payloadDigest"
            validationError = msg
        } else {
            when (mainState) {
                RegistrationMainState.IDLE -> {
                    if (phase != RegistrationPhase.NONE) {
                        val msg = "IDLE state requires phase NONE, found $phase"
                        validationError = msg
                    } else if (leaseOwner.isNotBlank()) {
                        val msg = "IDLE state requires blank leaseOwner, found '$leaseOwner'"
                        validationError = msg
                    } else if (leaseEpochMs != 0L) {
                        val msg = "IDLE state requires leaseEpochMs == 0, found $leaseEpochMs"
                        validationError = msg
                    } else if (cancellingTarget != null) {
                        val msg = "IDLE state requires null cancellingTarget"
                        validationError = msg
                    } else if (registeringTarget != null) {
                        val msg = "IDLE state requires null registeringTarget"
                        validationError = msg
                    }
                }
                RegistrationMainState.IN_FLIGHT -> {
                    if (phase == RegistrationPhase.NONE) {
                        val msg = "IN_FLIGHT state requires non-NONE phase"
                        validationError = msg
                    } else if (leaseOwner.isBlank()) {
                        val msg = "IN_FLIGHT state requires non-blank leaseOwner"
                        validationError = msg
                    } else if (leaseEpochMs <= 0L) {
                        val msg = "IN_FLIGHT state requires positive leaseEpochMs, found $leaseEpochMs"
                        validationError = msg
                    } else if (opSeq <= 0L) {
                        val msg = "IN_FLIGHT state requires positive opSeq, found $opSeq"
                        validationError = msg
                    } else {
                        when (phase) {
                            RegistrationPhase.REGISTERING_NEW -> {
                                if (registeringTarget == null) {
                                    val msg = "REGISTERING_NEW phase requires non-null registeringTarget"
                                    validationError = msg
                                }
                            }
                            RegistrationPhase.CANCELLING_OLD -> {
                                if (cancellingTarget == null) {
                                    val msg = "CANCELLING_OLD phase requires non-null cancellingTarget"
                                    validationError = msg
                                }
                            }
                            RegistrationPhase.COMPENSATING -> {
                                if (cancellingTarget == null && registeringTarget == null) {
                                    val msg = "COMPENSATING phase requires at least one non-null recovery target"
                                    validationError = msg
                                }
                            }
                            RegistrationPhase.NONE -> {
                                val msg = "IN_FLIGHT state cannot have phase NONE"
                                validationError = msg
                            }
                        }
                    }
                }
                RegistrationMainState.FAILED_BLOCKED -> {
                    if (failureReason == RegistrationFailureReason.NONE) {
                        val msg = "FAILED_BLOCKED state requires non-NONE failureReason"
                        validationError = msg
                    } else if (phase != RegistrationPhase.NONE) {
                        val msg = "FAILED_BLOCKED state requires phase NONE, found $phase"
                        validationError = msg
                    } else if (leaseOwner.isNotBlank()) {
                        val msg = "FAILED_BLOCKED state requires blank leaseOwner, found '$leaseOwner'"
                        validationError = msg
                    } else if (leaseEpochMs != 0L) {
                        val msg = "FAILED_BLOCKED state requires leaseEpochMs == 0, found $leaseEpochMs"
                        validationError = msg
                    } else if (cancellingTarget != null && registeringTarget != null && cancellingTarget == registeringTarget) {
                        val msg = "FAILED_BLOCKED state cannot have identical cancellingTarget and registeringTarget"
                        validationError = msg
                    }
                }
            }
        }
        return validationError
    }

    /**
     * Serializes this marker into a canonical JSON object.
     */
    fun toJson(): JSONObject {
        return JSONObject().apply {
            put("schemaVersion", schemaVersion)
            put("mainState", mainState.name)
            put("phase", phase.name)
            put("failureReason", failureReason.name)
            put("retryCount", retryCount)
            put("opSeq", opSeq)
            put("leaseOwner", leaseOwner)
            put("leaseEpochMs", leaseEpochMs)
            put("bootProvenance", bootProvenance.toJson())
            if (activeIdentity != null) {
                put("activeIdentity", identityToJson(activeIdentity))
            } else {
                put("activeIdentity", JSONObject.NULL)
            }
            if (cancellingTarget != null) {
                put("cancellingTarget", identityToJson(cancellingTarget))
            } else {
                put("cancellingTarget", JSONObject.NULL)
            }
            if (registeringTarget != null) {
                put("registeringTarget", identityToJson(registeringTarget))
            } else {
                put("registeringTarget", JSONObject.NULL)
            }
            put("updatedAtEpochMs", updatedAtEpochMs)
            put("payloadDigest", payloadDigest)
        }
    }

    /**
     * Returns serialized JSON string representation.
     */
    fun toJsonString(): String = toJson().toString()

    companion object {
        const val PREFS_NAME = "direct_boot_next_alarm"
        const val KEY_V2_REGISTRATION_MARKER = "v2_registration_marker"
        const val CURRENT_SCHEMA_VERSION = 1
        const val MAX_RETRIES = 3

        private val defaultEditorCommitter: (SharedPreferences, String) -> Boolean =
            { prefs, payload ->
                prefs.edit().putString(KEY_V2_REGISTRATION_MARKER, payload).commit()
            }

        /**
         * Increments [currentSeq] monotonically, detecting 64-bit signed Long overflow.
         */
        fun incrementOpSeq(currentSeq: Long): Long {
            require(currentSeq >= 0L) { "currentSeq must be non-negative, found $currentSeq" }
            if (currentSeq == Long.MAX_VALUE) {
                throw ArithmeticException("opSeq overflow: cannot increment Long.MAX_VALUE")
            }
            return currentSeq + 1L
        }

        /**
         * Creates an initial idle marker with computed payloadDigest.
         */
        fun createInitialIdle(
            activeIdentity: DirectBootV2NormalIntentIdentity? = null,
            bootProvenance: BootProvenance = BootProvenance(),
            nowEpochMs: Long = System.currentTimeMillis()
        ): DirectBootRegistrationMarker {
            val marker = DirectBootRegistrationMarker(
                schemaVersion = CURRENT_SCHEMA_VERSION,
                mainState = RegistrationMainState.IDLE,
                phase = RegistrationPhase.NONE,
                failureReason = RegistrationFailureReason.NONE,
                retryCount = 0,
                opSeq = 0L,
                leaseOwner = "",
                leaseEpochMs = 0L,
                bootProvenance = bootProvenance,
                activeIdentity = activeIdentity,
                cancellingTarget = null,
                registeringTarget = null,
                updatedAtEpochMs = nowEpochMs,
                payloadDigest = ""
            )
            return marker.copy(payloadDigest = marker.computeDigest())
        }

        /**
         * Serializes a single [DirectBootV2NormalIntentIdentity] with its SHA-256 occurrence digest.
         */
        fun identityToJson(identity: DirectBootV2NormalIntentIdentity): JSONObject {
            return JSONObject().apply {
                put("alarmId", identity.alarmId)
                put("generation", identity.generation)
                put("instanceToken", identity.instanceToken)
                put("triggerTime", identity.triggerTime)
                put("kind", identity.kind)
                put("occurrenceDigest", identity.computeDigest())
            }
        }

        /**
         * Parses and validates a [DirectBootV2NormalIntentIdentity] from a JSON object.
         * Enforces non-null fields, strict integral types without truncation, and recomputes the SHA-256 occurrence digest.
         */
        fun identityFromJson(json: JSONObject): DirectBootV2NormalIntentIdentity {
            val requiredFields = listOf("alarmId", "generation", "instanceToken", "triggerTime", "kind", "occurrenceDigest")
            for (f in requiredFields) {
                if (!json.has(f)) {
                    throw IllegalArgumentException("Missing required identity field '$f'")
                }
                if (json.isNull(f)) {
                    throw IllegalArgumentException("Identity field '$f' cannot be null")
                }
            }

            val alarmId = extractExactLong(json.get("alarmId"), "alarmId")
            val generation = extractExactInt(json.get("generation"), "generation")

            val tokenVal = json.get("instanceToken")
            if (tokenVal !is String) throw IllegalArgumentException("instanceToken must be a string, found ${tokenVal.javaClass.simpleName}")
            val instanceToken = tokenVal

            val triggerTime = extractExactLong(json.get("triggerTime"), "triggerTime")

            val kindVal = json.get("kind")
            if (kindVal !is String) throw IllegalArgumentException("kind must be a string, found ${kindVal.javaClass.simpleName}")
            val kind = kindVal

            val occVal = json.get("occurrenceDigest")
            if (occVal !is String) throw IllegalArgumentException("occurrenceDigest must be a string, found ${occVal.javaClass.simpleName}")
            val occurrenceDigest = occVal

            if (kind != DirectBootQueueItem.KIND_NORMAL) {
                throw IllegalArgumentException("kind MUST be '${DirectBootQueueItem.KIND_NORMAL}', found '$kind'")
            }
            if (alarmId <= 0L) throw IllegalArgumentException("alarmId MUST be positive, found $alarmId")
            if (generation < 1) throw IllegalArgumentException("generation MUST be >= 1, found $generation")
            if (instanceToken.isBlank()) throw IllegalArgumentException("instanceToken MUST NOT be blank")
            if (triggerTime <= 0L) throw IllegalArgumentException("triggerTime MUST be positive, found $triggerTime")

            val identity = DirectBootV2NormalIntentIdentity(
                alarmId = alarmId,
                generation = generation,
                instanceToken = instanceToken,
                triggerTime = triggerTime,
                kind = kind
            )

            val expectedDigest = identity.computeDigest()
            if (occurrenceDigest != expectedDigest) {
                throw IllegalArgumentException("occurrenceDigest mismatch: expected $expectedDigest, found $occurrenceDigest")
            }
            return identity
        }

        /**
         * Strictly parses raw string payload into [MarkerReadResult].
         * Distinguishes absent, corrupt, unsupported schema, and successful states.
         * Fails closed on any type error, fractional number, missing field, or integrity digest mismatch.
         */
        fun parse(rawPayload: String?): MarkerReadResult {
            if (rawPayload == null) return MarkerReadResult.Corrupt("Marker payload is null", null)
            if (rawPayload.isBlank()) return MarkerReadResult.Corrupt("Marker payload is blank or empty", rawPayload)

            try {
                val json = try {
                    JSONObject(rawPayload)
                } catch (e: JSONException) {
                    return MarkerReadResult.Corrupt("Malformed JSON: ${e.message}", rawPayload)
                }

                // Check required fields presence and non-null
                val requiredFields = listOf(
                    "schemaVersion", "mainState", "phase", "failureReason",
                    "retryCount", "opSeq", "leaseOwner", "leaseEpochMs",
                    "updatedAtEpochMs", "payloadDigest"
                )
                for (field in requiredFields) {
                    if (!json.has(field)) {
                        return MarkerReadResult.Corrupt("Missing required field '$field'", rawPayload)
                    }
                    if (json.isNull(field)) {
                        return MarkerReadResult.Corrupt("Mandatory field '$field' cannot be null", rawPayload)
                    }
                }

                val schemaVersion = extractExactInt(json.get("schemaVersion"), "schemaVersion")
                if (schemaVersion != CURRENT_SCHEMA_VERSION) {
                    return MarkerReadResult.UnsupportedSchema(
                        foundVersion = schemaVersion,
                        expectedVersion = CURRENT_SCHEMA_VERSION,
                        rawPayload = rawPayload
                    )
                }

                val mainStateRaw = json.get("mainState")
                if (mainStateRaw !is String) {
                    return MarkerReadResult.Corrupt("Field 'mainState' must be a string", rawPayload)
                }
                val mainState = try {
                    RegistrationMainState.valueOf(mainStateRaw)
                } catch (_: IllegalArgumentException) {
                    return MarkerReadResult.Corrupt("Invalid mainState enum value '$mainStateRaw'", rawPayload)
                }

                val phaseRaw = json.get("phase")
                if (phaseRaw !is String) {
                    return MarkerReadResult.Corrupt("Field 'phase' must be a string", rawPayload)
                }
                val phase = try {
                    RegistrationPhase.valueOf(phaseRaw)
                } catch (_: IllegalArgumentException) {
                    return MarkerReadResult.Corrupt("Invalid phase enum value '$phaseRaw'", rawPayload)
                }

                val failureReasonRaw = json.get("failureReason")
                if (failureReasonRaw !is String) {
                    return MarkerReadResult.Corrupt("Field 'failureReason' must be a string", rawPayload)
                }
                val failureReason = try {
                    RegistrationFailureReason.valueOf(failureReasonRaw)
                } catch (_: IllegalArgumentException) {
                    return MarkerReadResult.Corrupt("Invalid failureReason enum value '$failureReasonRaw'", rawPayload)
                }

                val retryCount = extractExactInt(json.get("retryCount"), "retryCount")
                if (retryCount !in 0..MAX_RETRIES) {
                    return MarkerReadResult.Corrupt("retryCount out of bounds: $retryCount", rawPayload)
                }

                val opSeq = extractExactLong(json.get("opSeq"), "opSeq")
                if (opSeq < 0L) {
                    return MarkerReadResult.Corrupt("opSeq must be non-negative: $opSeq", rawPayload)
                }

                val leaseOwnerRaw = json.get("leaseOwner")
                if (leaseOwnerRaw !is String) {
                    return MarkerReadResult.Corrupt("Field 'leaseOwner' must be a string", rawPayload)
                }
                val leaseOwner = leaseOwnerRaw

                val leaseEpochMs = extractExactLong(json.get("leaseEpochMs"), "leaseEpochMs")
                val updatedAtEpochMs = extractExactLong(json.get("updatedAtEpochMs"), "updatedAtEpochMs")

                val payloadDigestRaw = json.get("payloadDigest")
                if (payloadDigestRaw !is String) {
                    return MarkerReadResult.Corrupt("Field 'payloadDigest' must be a string", rawPayload)
                }
                val payloadDigest = payloadDigestRaw
                if (payloadDigest.isBlank()) {
                    return MarkerReadResult.Corrupt("Field 'payloadDigest' is blank", rawPayload)
                }

                val bootProvenance = if (json.has("bootProvenance") && !json.isNull("bootProvenance")) {
                    val bpObj = json.get("bootProvenance")
                    if (bpObj !is JSONObject) {
                        return MarkerReadResult.Corrupt("Field 'bootProvenance' must be a JSONObject", rawPayload)
                    }
                    try {
                        BootProvenance.fromJson(bpObj)
                    } catch (e: Exception) {
                        return MarkerReadResult.Corrupt("Invalid bootProvenance: ${e.message}", rawPayload)
                    }
                } else {
                    BootProvenance()
                }

                val activeIdentity = if (json.has("activeIdentity") && !json.isNull("activeIdentity")) {
                    val idObj = json.get("activeIdentity")
                    if (idObj !is JSONObject) {
                        return MarkerReadResult.Corrupt("Field 'activeIdentity' must be a JSONObject", rawPayload)
                    }
                    try {
                        identityFromJson(idObj)
                    } catch (e: Exception) {
                        return MarkerReadResult.Corrupt("Invalid activeIdentity: ${e.message}", rawPayload)
                    }
                } else null

                val cancellingTarget = if (json.has("cancellingTarget") && !json.isNull("cancellingTarget")) {
                    val idObj = json.get("cancellingTarget")
                    if (idObj !is JSONObject) {
                        return MarkerReadResult.Corrupt("Field 'cancellingTarget' must be a JSONObject", rawPayload)
                    }
                    try {
                        identityFromJson(idObj)
                    } catch (e: Exception) {
                        return MarkerReadResult.Corrupt("Invalid cancellingTarget: ${e.message}", rawPayload)
                    }
                } else null

                val registeringTarget = if (json.has("registeringTarget") && !json.isNull("registeringTarget")) {
                    val idObj = json.get("registeringTarget")
                    if (idObj !is JSONObject) {
                        return MarkerReadResult.Corrupt("Field 'registeringTarget' must be a JSONObject", rawPayload)
                    }
                    try {
                        identityFromJson(idObj)
                    } catch (e: Exception) {
                        return MarkerReadResult.Corrupt("Invalid registeringTarget: ${e.message}", rawPayload)
                    }
                } else null

                val marker = DirectBootRegistrationMarker(
                    schemaVersion = schemaVersion,
                    mainState = mainState,
                    phase = phase,
                    failureReason = failureReason,
                    retryCount = retryCount,
                    opSeq = opSeq,
                    leaseOwner = leaseOwner,
                    leaseEpochMs = leaseEpochMs,
                    bootProvenance = bootProvenance,
                    activeIdentity = activeIdentity,
                    cancellingTarget = cancellingTarget,
                    registeringTarget = registeringTarget,
                    updatedAtEpochMs = updatedAtEpochMs,
                    payloadDigest = payloadDigest
                )

                val expectedDigest = marker.computeDigest()
                if (payloadDigest != expectedDigest) {
                    return MarkerReadResult.Corrupt(
                        "Marker payloadDigest mismatch: expected $expectedDigest, found $payloadDigest",
                        rawPayload
                    )
                }

                val validationError = marker.validate()
                if (validationError != null) {
                    return MarkerReadResult.Corrupt("Marker validation failed: $validationError", rawPayload)
                }

                return MarkerReadResult.Success(marker)
            } catch (e: Exception) {
                return MarkerReadResult.Corrupt("Unexpected parsing error: ${e.message}", rawPayload)
            }
        }

        /**
         * Reads the durable marker from Device-Protected storage under [DirectBootStorageLock.lock].
         */
        fun read(context: Context): MarkerReadResult {
            return try {
                val prefs = context.createDeviceProtectedStorageContext()
                    .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

                val raw = synchronized(DirectBootStorageLock.lock) {
                    if (!prefs.contains(KEY_V2_REGISTRATION_MARKER)) {
                        return MarkerReadResult.Absent
                    }
                    try {
                        prefs.getString(KEY_V2_REGISTRATION_MARKER, null)
                    } catch (cce: ClassCastException) {
                        return MarkerReadResult.Corrupt("Stored value is not a String: ${cce.message}", null)
                    }
                }
                if (raw == null) {
                    return MarkerReadResult.Corrupt("Key '$KEY_V2_REGISTRATION_MARKER' present with null payload", null)
                }
                parse(raw)
            } catch (e: Exception) {
                MarkerReadResult.Corrupt("Exception reading marker: ${e.message}", null)
            }
        }

        /**
         * Writes the marker to Device-Protected storage under [DirectBootStorageLock.lock].
         *
         * Enforces strict CAS sequence advancement:
         * - Compares [expectedOpSeq] (or [marker.opSeq]) with actual stored sequence.
         * - Advances sequence atomically to current + 1.
         * - Rejects stale, unchanged, or regressed generations with [MarkerWriteResult.SequenceMismatch].
         * - Rejects [Long.MAX_VALUE] overflow with [MarkerWriteResult.SequenceOverflow].
         * - Attaches freshly computed marker-wide SHA-256 payloadDigest.
         * - Validates state/phase invariants before writing.
         *
         * @param context Application or component context.
         * @param marker The new marker state to write.
         * @param expectedOpSeq Optional operation sequence number expected currently on disk.
         */
        fun write(
            context: Context,
            marker: DirectBootRegistrationMarker,
            expectedOpSeq: Long? = null
        ): MarkerWriteResult {
            return writeInternal(
                context = context,
                marker = marker,
                expectedOpSeq = expectedOpSeq,
                editorCommitter = null
            )
        }

        @VisibleForTesting
        internal fun writeInternal(
            context: Context,
            marker: DirectBootRegistrationMarker,
            expectedOpSeq: Long? = null,
            editorCommitter: ((SharedPreferences, String) -> Boolean)? = null
        ): MarkerWriteResult {
            val prefs = context.createDeviceProtectedStorageContext()
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val committer = editorCommitter ?: defaultEditorCommitter

            synchronized(DirectBootStorageLock.lock) {
                val currentSeq: Long
                if (prefs.contains(KEY_V2_REGISTRATION_MARKER)) {
                    val currentRaw = try {
                        prefs.getString(KEY_V2_REGISTRATION_MARKER, null)
                    } catch (cce: ClassCastException) {
                        return MarkerWriteResult.StorageCorrupt("Stored value is not a String: ${cce.message}")
                    }
                    if (currentRaw == null) {
                        return MarkerWriteResult.StorageCorrupt("Key present with null payload")
                    }
                    when (val currentRead = parse(currentRaw)) {
                        is MarkerReadResult.Corrupt -> {
                            return MarkerWriteResult.StorageCorrupt(currentRead.reason)
                        }
                        is MarkerReadResult.UnsupportedSchema -> {
                            return MarkerWriteResult.UnsupportedSchema(
                                foundVersion = currentRead.foundVersion,
                                expectedVersion = currentRead.expectedVersion
                            )
                        }
                        is MarkerReadResult.Success -> {
                            currentSeq = currentRead.marker.opSeq
                        }
                        is MarkerReadResult.Absent -> {
                            currentSeq = 0L
                        }
                    }
                } else {
                    currentSeq = 0L
                }

                // CAS Sequence Check: expectedOpSeq or marker.opSeq must match currentSeq exactly
                val effectiveExpected = expectedOpSeq ?: marker.opSeq
                if (effectiveExpected != currentSeq) {
                    return MarkerWriteResult.SequenceMismatch(
                        expectedSeq = effectiveExpected,
                        actualSeq = currentSeq
                    )
                }

                // Sequence overflow check
                if (currentSeq == Long.MAX_VALUE) {
                    return MarkerWriteResult.SequenceOverflow(currentSeq)
                }

                val nextSeq = currentSeq + 1L

                // Construct committed marker with advanced sequence and updated payload digest
                val candidateMarker = marker.copy(opSeq = nextSeq)
                val committedMarker = candidateMarker.copy(payloadDigest = candidateMarker.computeDigest())

                val validationError = committedMarker.validate()
                if (validationError != null) {
                    return MarkerWriteResult.InvalidTransition(validationError)
                }

                val payload = committedMarker.toJsonString()

                return try {
                    val success = committer(prefs, payload)
                    if (success) {
                        MarkerWriteResult.Success(committedMarker)
                    } else {
                        MarkerWriteResult.CommitFailed
                    }
                } catch (e: Exception) {
                    MarkerWriteResult.CommitException(e)
                }
            }
        }

        /**
         * Safely extracts an exact integer without silent truncation of fractional or out-of-range numbers.
         */
        @VisibleForTesting
        internal fun extractExactInt(rawVal: Any, fieldName: String): Int {
            when (rawVal) {
                is Int -> return rawVal
                is Long -> {
                    if (rawVal !in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()) {
                        throw IllegalArgumentException("Field '$fieldName' out of Int range: $rawVal")
                    }
                    return rawVal.toInt()
                }
                is Short -> return rawVal.toInt()
                is Byte -> return rawVal.toInt()
                is Double, is Float -> {
                    throw IllegalArgumentException("Field '$fieldName' must be an integer, found fractional/floating-point: $rawVal")
                }
                is java.math.BigDecimal -> {
                    try {
                        return rawVal.intValueExact()
                    } catch (_: ArithmeticException) {
                        throw IllegalArgumentException("Field '$fieldName' is not an exact Int: $rawVal")
                    }
                }
                is java.math.BigInteger -> {
                    if (rawVal < java.math.BigInteger.valueOf(Int.MIN_VALUE.toLong()) ||
                        rawVal > java.math.BigInteger.valueOf(Int.MAX_VALUE.toLong())) {
                        throw IllegalArgumentException("Field '$fieldName' out of Int range: $rawVal")
                    }
                    return rawVal.toInt()
                }
                else -> throw IllegalArgumentException("Field '$fieldName' must be an integer, found ${rawVal.javaClass.simpleName}")
            }
        }

        /**
         * Safely extracts an exact Long without precision loss or silent truncation of fractional numbers.
         */
        @VisibleForTesting
        internal fun extractExactLong(rawVal: Any, fieldName: String): Long {
            when (rawVal) {
                is Long -> return rawVal
                is Int -> return rawVal.toLong()
                is Short -> return rawVal.toLong()
                is Byte -> return rawVal.toLong()
                is Double, is Float -> {
                    throw IllegalArgumentException("Field '$fieldName' must be an exact Long, found fractional/floating-point: $rawVal")
                }
                is java.math.BigDecimal -> {
                    try {
                        return rawVal.longValueExact()
                    } catch (_: ArithmeticException) {
                        throw IllegalArgumentException("Field '$fieldName' is not an exact Long: $rawVal")
                    }
                }
                is java.math.BigInteger -> {
                    if (rawVal < java.math.BigInteger.valueOf(Long.MIN_VALUE) ||
                        rawVal > java.math.BigInteger.valueOf(Long.MAX_VALUE)) {
                        throw IllegalArgumentException("Field '$fieldName' out of Long range: $rawVal")
                    }
                    return rawVal.toLong()
                }
                else -> throw IllegalArgumentException("Field '$fieldName' must be a Long, found ${rawVal.javaClass.simpleName}")
            }
        }
    }
}
