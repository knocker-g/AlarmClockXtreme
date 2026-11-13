package com.sysadmindoc.alarmclock.directboot

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/**
 * Inactive V2 Normal PendingIntent Identity Codec for KNO-75 Phase D3-A / D3-A1.
 *
 * Responsibilities:
 * - Deterministic canonical representation of a Normal alarm occurrence identity.
 * - Cryptographic domain-separated SHA-256 digest derivation.
 * - Opaque Intent.data URI production without exposing raw instanceToken.
 * - filterEquals-distinct PendingIntent factory isolating V2 from legacy V1 namespace.
 * - 64-bit Long alarmId collision immunity (eliminating 32-bit toInt() collisions).
 * - Delimiter-safe length framing preventing field injection and collision attacks.
 * - Strict immutable PendingIntent factory contract: always creates with [PendingIntent.FLAG_UPDATE_CURRENT]
 *   combined with [PendingIntent.FLAG_IMMUTABLE]; lookup strictly uses [PendingIntent.FLAG_NO_CREATE]
 *   combined with [PendingIntent.FLAG_IMMUTABLE]. Arbitrary caller-supplied flags are prohibited.
 * - Canonical URI validation: strictly enforces byte-for-byte canonical textual equivalence, rejecting
 *   queries, fragments, trailing slashes, encoded aliases, and non-canonical numeric formatting.
 *
 * Validation Boundary:
 * - This codec is an identity, URI validation, and Intent token specification, NOT a delivery
 *   authorization system. Canonical URI validation does not constitute proof of authenticated
 *   OS intent receipt; authenticated OS intent dispatch is gated at the Runtime boundary.
 */
data class DirectBootV2NormalIntentIdentity(
    val alarmId: Long,
    val generation: Int,
    val instanceToken: String,
    val triggerTime: Long,
    val restoreEpoch: Long = 0L,
    val kind: String = KIND_NORMAL
) {
    init {
        require(kind == KIND_NORMAL) { "kind MUST be '$KIND_NORMAL', found '$kind'" }
        require(alarmId > 0L) { "alarmId MUST be positive, found $alarmId" }
        require(generation >= 1) { "generation MUST be >= 1 for Normal alarms, found $generation" }
        require(instanceToken.isNotBlank()) { "instanceToken MUST NOT be blank for Normal alarms" }
        require(triggerTime > 0L) { "triggerTime MUST be positive, found $triggerTime" }
    }

    /**
     * Canonical text representation with explicit field length framing.
     * Prevents delimiter collision or injection attacks across all fields.
     */
    fun canonicalString(): String = buildString {
        val tokenUtf8Len = instanceToken.toByteArray(StandardCharsets.UTF_8).size
        append("DOMAIN:$DOMAIN_TAG\n")
        append("KIND:${kind.length}:$kind\n")
        append("ALARM_ID:$alarmId\n")
        append("GENERATION:$generation\n")
        append("INSTANCE_TOKEN:$tokenUtf8Len:$instanceToken\n")
        if (restoreEpoch > 0L) {
            append("RESTORE_EPOCH:$restoreEpoch\n")
        }
        append("TRIGGER_TIME:$triggerTime\n")
    }

    /**
     * Computes the deterministic SHA-256 hex digest of the canonical byte representation.
     */
    fun computeDigest(): String {
        val bytes = canonicalString().toByteArray(StandardCharsets.UTF_8)
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        return digest.joinToString("") { "%02x".format(it) }
    }

    /**
     * Produces the canonical opaque V2 Data URI string.
     * Does NOT contain the raw instanceToken.
     * Format: acx-directboot://normal/alarm/{alarmId}/v2/{digest}
     */
    fun toUriString(): String =
        "$URI_SCHEME://$URI_AUTHORITY/alarm/$alarmId/v2/${computeDigest()}"

    /**
     * Produces the Android android.net.Uri representation.
     */
    fun toUri(): Uri = Uri.parse(toUriString())

    /**
     * Creates an explicit V2 Intent targeted at [DirectBootAlarmReceiver].
     * Includes the opaque data URI and structured extras for O(1) payload reading.
     */
    fun createIntent(context: Context, provenance: String = ""): Intent {
        return Intent(context, DirectBootAlarmReceiver::class.java).apply {
            action = ACTION_FIRE_V2_NORMAL_ALARM
            data = toUri()
            putExtra(EXTRA_KIND, kind)
            putExtra(EXTRA_ALARM_ID, alarmId)
            putExtra(EXTRA_TRIGGER_TIME, triggerTime)
            putExtra(EXTRA_GENERATION, generation)
            putExtra(EXTRA_INSTANCE_TOKEN, instanceToken)
            putExtra(EXTRA_RESTORE_EPOCH, restoreEpoch)
            putExtra(EXTRA_IDENTITY_DIGEST, computeDigest())
            if (provenance.isNotBlank()) {
                putExtra(EXTRA_PROVENANCE, provenance)
            }
        }
    }

    /**
     * Creates a lookup Intent for FLAG_NO_CREATE matching.
     * Intent.filterEquals() matches action, data URI, type, class, and categories.
     */
    fun createLookupIntent(context: Context): Intent {
        return Intent(context, DirectBootAlarmReceiver::class.java).apply {
            action = ACTION_FIRE_V2_NORMAL_ALARM
            data = toUri()
        }
    }

    /**
     * Creates or updates the V2 PendingIntent for AlarmManager registration.
     * Strictly enforces [PendingIntent.FLAG_UPDATE_CURRENT] or [PendingIntent.FLAG_IMMUTABLE].
     * Arbitrary caller-supplied flags are prohibited.
     */
    fun createPendingIntent(
        context: Context,
        provenance: String = ""
    ): PendingIntent {
        return PendingIntent.getBroadcast(
            context,
            REQUEST_CODE_V2_NORMAL,
            createIntent(context, provenance),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    /**
     * Performs a FLAG_NO_CREATE lookup for this occurrence's PendingIntent.
     * Strictly enforces [PendingIntent.FLAG_NO_CREATE] or [PendingIntent.FLAG_IMMUTABLE].
     * Returns null if not currently registered.
     */
    fun lookupPendingIntentForCancel(context: Context): PendingIntent? {
        return PendingIntent.getBroadcast(
            context,
            REQUEST_CODE_V2_NORMAL,
            createLookupIntent(context),
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
        )
    }

    companion object {
        const val KIND_NORMAL = DirectBootQueueItem.KIND_NORMAL // "NORMAL"
        const val DOMAIN_TAG = "ACX_DIRECT_BOOT_V2_NORMAL_PI_V1"
        const val ACTION_FIRE_V2_NORMAL_ALARM =
            "com.sysadmindoc.alarmclock.directboot.ACTION_FIRE_V2_NORMAL_ALARM"
        const val URI_SCHEME = "acx-directboot"
        const val URI_AUTHORITY = "normal"
        const val REQUEST_CODE_V2_NORMAL = 91_701

        const val EXTRA_KIND = "com.sysadmindoc.alarmclock.directboot.extra.KIND"
        const val EXTRA_ALARM_ID = "com.sysadmindoc.alarmclock.directboot.extra.ALARM_ID"
        const val EXTRA_TRIGGER_TIME = "com.sysadmindoc.alarmclock.directboot.extra.TRIGGER_TIME"
        const val EXTRA_GENERATION = "com.sysadmindoc.alarmclock.directboot.extra.GENERATION"
        const val EXTRA_INSTANCE_TOKEN = "com.sysadmindoc.alarmclock.directboot.extra.INSTANCE_TOKEN"
        const val EXTRA_RESTORE_EPOCH = "com.sysadmindoc.alarmclock.directboot.extra.RESTORE_EPOCH"
        const val EXTRA_IDENTITY_DIGEST = "com.sysadmindoc.alarmclock.directboot.extra.IDENTITY_DIGEST"
        const val EXTRA_PROVENANCE = "com.sysadmindoc.alarmclock.directboot.extra.PROVENANCE"

        /**
         * Creates an identity from an authoritative [DirectBootQueueItem].
         */
        fun fromQueueItem(item: DirectBootQueueItem): DirectBootV2NormalIntentIdentity {
            require(item.kind == KIND_NORMAL) {
                "Cannot create Normal identity from non-normal item kind '${item.kind}'"
            }
            return DirectBootV2NormalIntentIdentity(
                alarmId = item.alarmId,
                generation = item.generation,
                instanceToken = item.instanceToken,
                triggerTime = item.triggerTime,
                restoreEpoch = item.restoreEpoch,
                kind = item.kind
            )
        }

        /**
         * Validates an incoming URI against this codec's schema and an expected identity.
         * Enforces strict canonical representation matching [expectedIdentity.toUriString()].
         * Rejects:
         * - Query parameters (raw or encoded)
         * - Fragments (raw or encoded)
         * - Trailing slashes or redundant slashes
         * - Non-canonical or padded alarmId formatting (e.g. "+42", "042", "0x2a")
         * - Encoded path aliases (%2F, %20, etc.)
         * - Any alternate textual representation diverging from canonical form.
         */
        fun validateUri(uri: Uri?, expectedIdentity: DirectBootV2NormalIntentIdentity): UriValidationResult {
            if (uri == null) return UriValidationResult.Rejected("URI is null")
            if (uri.isOpaque) return UriValidationResult.Rejected("URI is opaque; expected hierarchical")

            // 1. Strict Query and Fragment rejection
            if (uri.query != null || uri.encodedQuery != null) {
                return UriValidationResult.Rejected("URI must not contain query parameters")
            }
            if (uri.fragment != null || uri.encodedFragment != null) {
                return UriValidationResult.Rejected("URI must not contain fragment")
            }

            // 2. Scheme & Authority check
            if (uri.scheme != URI_SCHEME) {
                return UriValidationResult.Rejected("Invalid scheme: '${uri.scheme}', expected '$URI_SCHEME'")
            }
            if (uri.authority != URI_AUTHORITY) {
                return UriValidationResult.Rejected("Invalid authority: '${uri.authority}', expected '$URI_AUTHORITY'")
            }

            // 3. Structural Path Validation
            val pathSegments = uri.pathSegments ?: return UriValidationResult.Rejected("Missing path segments")
            if (pathSegments.size != 4 || pathSegments[0] != "alarm" || pathSegments[2] != "v2") {
                return UriValidationResult.Rejected("Malformed path structure: ${uri.path}")
            }

            // 4. Canonical Alarm ID Formatting (exact decimal string, no sign, no leading zeros)
            val expectedAlarmIdStr = expectedIdentity.alarmId.toString()
            val rawPathAlarmId = pathSegments[1]
            if (rawPathAlarmId != expectedAlarmIdStr) {
                return UriValidationResult.Rejected("alarmId mismatch or non-canonical formatting: '$rawPathAlarmId' vs '$expectedAlarmIdStr'")
            }

            // 5. Canonical Digest Validation
            val pathDigest = pathSegments[3]
            if (pathDigest.length != 64 || !pathDigest.all { it in '0'..'9' || it in 'a'..'f' }) {
                return UriValidationResult.Rejected("Malformed digest format in path: '$pathDigest'")
            }
            val expectedDigest = expectedIdentity.computeDigest()
            if (pathDigest != expectedDigest) {
                return UriValidationResult.Rejected("Digest mismatch: URI has '$pathDigest', expected '$expectedDigest'")
            }

            // 6. Strict Exact Textual Canonical Equivalence
            // Catches trailing slashes (e.g. "/.../"), encoded characters, redundant delimiters, etc.
            val canonicalString = expectedIdentity.toUriString()
            val actualUriString = uri.toString()
            if (actualUriString != canonicalString) {
                return UriValidationResult.Rejected("URI diverges from canonical string representation: '$actualUriString' vs '$canonicalString'")
            }

            return UriValidationResult.Valid
        }

        /**
         * Checks whether an Intent conforms to V2 Normal action and URI namespace.
         */
        fun isV2NormalIntent(intent: Intent?): Boolean {
            if (intent == null) return false
            if (intent.action != ACTION_FIRE_V2_NORMAL_ALARM) return false
            val uri = intent.data ?: return false
            return uri.scheme == URI_SCHEME && uri.authority == URI_AUTHORITY
        }
    }
}

sealed class UriValidationResult {
    object Valid : UriValidationResult()
    data class Rejected(val reason: String) : UriValidationResult()

    val isValid: Boolean get() = this is Valid
}
