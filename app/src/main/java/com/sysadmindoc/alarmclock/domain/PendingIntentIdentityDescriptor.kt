package com.sysadmindoc.alarmclock.domain

/**
 * Pure Kotlin enumerations and data classes for S1-C2 Correction A3 OS Key and Lineage comparison.
 * Zero Android Context, AlarmManager, PendingIntent, Intent, Room, or WorkManager dependencies.
 */

enum class OsKeyComparison {
    EQUIVALENT,
    DISTINCT,
    INDETERMINATE
}

enum class PendingIntentOperationType {
    BROADCAST,
    SERVICE,
    ACTIVITY,
    UNKNOWN
}

data class PendingIntentIdentityDescriptor(
    val operationType: PendingIntentOperationType,
    val creatorPackage: String,
    val creatorUserScope: Int,
    val targetComponent: String,
    val intentAction: String?,
    val intentDataUri: String?,
    val intentMimeType: String? = null,
    val intentCategories: Set<String> = emptySet(),
    val requestCode: Int,
    val flags: Int = 0,
    val isLegacyV1Alias: Boolean = false
) {
    val isMalformed: Boolean
        get() = creatorPackage.isBlank() ||
            targetComponent.isBlank() ||
            operationType == PendingIntentOperationType.UNKNOWN ||
            intentDataUri == ""

    val normalizedDataUri: String?
        get() = intentDataUri?.takeIf { it.isNotBlank() }

    val computedIsLegacyV1Alias: Boolean
        get() = isLegacyV1Alias || (
            intentDataUri == null &&
            (intentAction == "com.sysadmindoc.alarmclock.ALARM_FIRE" || intentAction == null) &&
            targetComponent == "com.sysadmindoc.alarmclock.receiver.AlarmReceiver"
        )

    val isPositiveTokenizedV2: Boolean
        get() {
            val uri = intentDataUri ?: return false
            if (!uri.startsWith("alarmclock://alarm/issue?")) return false
            val params = PendingIntentIdentityComparator.parseAndValidateQueryParams(uri) ?: return false
            val alarmIdParam = params["alarmId"] ?: return false
            val tokenParam = params["token"] ?: return false
            return (alarmIdParam.toLongOrNull() ?: 0L) > 0L && tokenParam.isNotBlank()
        }
}

enum class OccurrenceComparison {
    SAME,
    DIFFERENT,
    UNKNOWN
}

data class OccurrenceLineageDescriptor(
    val alarmId: Long,
    val alarmKind: String = "NORMAL",
    val scheduledTriggerMillis: Long,
    val generation: Int,
    val revision: Int,
    val issueToken: String? = null,
    val instanceToken: String? = null,
    val restoreEpoch: Long? = null,
    val scheduleRevision: Long? = null,
    val definitionRevision: Long? = null
) {
    val isValid: Boolean
        get() = alarmId > 0L && scheduledTriggerMillis > 0L && generation > 0 && revision > 0

    val hasFullTokenLineage: Boolean
        get() = isValid && !issueToken.isNullOrBlank() && !instanceToken.isNullOrBlank() && restoreEpoch != null
}

sealed interface CrossIdentitySafety {
    val isPermitted: Boolean get() = false
    val allowsDestructiveCleanup: Boolean get() = false
    val isOsCancelCompleted: Boolean get() = false
    val executesCancellation: Boolean get() = false

    data class ExactCandidate(
        val osKeyComparison: OsKeyComparison,
        val occurrenceComparison: OccurrenceComparison,
        val detail: String
    ) : CrossIdentitySafety

    data class UnsafeAlias(
        val osKeyComparison: OsKeyComparison,
        val occurrenceComparison: OccurrenceComparison,
        val reason: CancellationRejectionReason,
        val detail: String
    ) : CrossIdentitySafety

    data class TargetMismatch(
        val osKeyComparison: OsKeyComparison,
        val occurrenceComparison: OccurrenceComparison,
        val reason: CancellationRejectionReason,
        val detail: String
    ) : CrossIdentitySafety

    data class EvidenceIncomplete(
        val missingWitness: String,
        val detail: String
    ) : CrossIdentitySafety
}

object PendingIntentIdentityComparator {

    const val FLAG_ONE_SHOT = 0x40000000
    const val FLAG_NO_CREATE = 0x20000000
    const val FLAG_CANCEL_CURRENT = 0x10000000
    const val FLAG_UPDATE_CURRENT = 0x08000000
    const val FLAG_IMMUTABLE = 0x04000000
    const val FLAG_MUTABLE = 0x02000000

    const val IDENTITY_FLAGS_MASK = FLAG_ONE_SHOT or FLAG_IMMUTABLE or FLAG_MUTABLE
    const val CREATION_FLAGS_MASK = FLAG_UPDATE_CURRENT or FLAG_CANCEL_CURRENT or FLAG_NO_CREATE
    const val KNOWN_FLAGS_MASK = IDENTITY_FLAGS_MASK or CREATION_FLAGS_MASK

    private val ALLOWED_V2_QUERY_PARAMS = setOf("alarmId", "token")

    private fun isUnreservedChar(c: Char): Boolean {
        return (c in 'a'..'z') || (c in 'A'..'Z') || (c in '0'..'9') ||
            c == '-' || c == '_' || c == '.' || c == '~' ||
            c == '!' || c == '\'' || c == '(' || c == ')' || c == '*'
    }

    fun encodePercentUtf8(s: String): String {
        val bytes = s.toByteArray(Charsets.UTF_8)
        val sb = StringBuilder()
        for (b in bytes) {
            val ch = b.toInt().toChar()
            if (b in 33..126 && isUnreservedChar(ch)) {
                sb.append(ch)
            } else {
                sb.append(String.format("%%%02X", b.toInt() and 0xFF))
            }
        }
        return sb.toString()
    }

    fun decodePercentUtf8(s: String): String? {
        val byteList = mutableListOf<Byte>()
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '%') {
                if (i + 2 >= s.length) return null
                val hex = s.substring(i + 1, i + 3)
                val byteVal = hex.toIntOrNull(16) ?: return null
                byteList.add(byteVal.toByte())
                i += 3
            } else {
                byteList.add(c.code.toByte())
                i++
            }
        }
        return try {
            String(byteList.toByteArray(), Charsets.UTF_8)
        } catch (e: Exception) {
            null
        }
    }

    fun isValidPercentEncoding(s: String): Boolean {
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '%') {
                if (i + 2 >= s.length) return false
                val hex1 = s[i + 1]
                val hex2 = s[i + 2]
                if (hex1.digitToIntOrNull(16) == null || hex2.digitToIntOrNull(16) == null) return false
                i += 3
            } else {
                i++
            }
        }
        return true
    }

    fun parseAndValidateQueryParams(uri: String): Map<String, String>? {
        val queryIndex = uri.indexOf('?')
        if (queryIndex == -1) return null
        val queryString = uri.substring(queryIndex + 1)
        if (queryString.isBlank()) return null

        val pairs = queryString.split('&')
        val map = mutableMapOf<String, String>()

        for (pair in pairs) {
            val parts = pair.split('=', limit = 2)
            if (parts.size != 2) return null
            val key = parts[0]
            val value = parts[1]

            if (key !in ALLOWED_V2_QUERY_PARAMS) return null
            if (map.containsKey(key)) return null

            if (!isValidPercentEncoding(value)) return null

            map[key] = value
        }

        if (map.size != 2 || !map.containsKey("alarmId") || !map.containsKey("token")) return null
        return map
    }

    fun extractQueryParam(uri: String, paramName: String): String? {
        val params = parseAndValidateQueryParams(uri) ?: return null
        return params[paramName]
    }

    fun compareOsKeys(
        a: PendingIntentIdentityDescriptor,
        b: PendingIntentIdentityDescriptor
    ): OsKeyComparison {
        if (a.isMalformed || b.isMalformed) {
            return OsKeyComparison.INDETERMINATE
        }

        val unknownFlagsA = a.flags and KNOWN_FLAGS_MASK.inv()
        val unknownFlagsB = b.flags and KNOWN_FLAGS_MASK.inv()
        if (unknownFlagsA != 0 || unknownFlagsB != 0) {
            return OsKeyComparison.INDETERMINATE
        }

        val flagAIdentity = a.flags and IDENTITY_FLAGS_MASK
        val flagBIdentity = b.flags and IDENTITY_FLAGS_MASK

        if (a.operationType != b.operationType ||
            a.creatorPackage != b.creatorPackage ||
            a.creatorUserScope != b.creatorUserScope ||
            a.targetComponent != b.targetComponent ||
            a.intentAction != b.intentAction ||
            a.normalizedDataUri != b.normalizedDataUri ||
            a.intentMimeType != b.intentMimeType ||
            a.intentCategories != b.intentCategories ||
            a.requestCode != b.requestCode ||
            flagAIdentity != flagBIdentity
        ) {
            return OsKeyComparison.DISTINCT
        }

        return OsKeyComparison.EQUIVALENT
    }

    fun compareLineages(
        a: OccurrenceLineageDescriptor,
        b: OccurrenceLineageDescriptor
    ): OccurrenceComparison {
        // Step 1: Check basic structural validity of inputs
        if (!a.isValid || !b.isValid) {
            return OccurrenceComparison.UNKNOWN
        }

        // Step 2: Check for known divergent attributes (including one-sided null tokens)
        if (a.alarmId != b.alarmId ||
            a.alarmKind != b.alarmKind ||
            a.scheduledTriggerMillis != b.scheduledTriggerMillis ||
            a.generation != b.generation ||
            a.revision != b.revision ||
            a.restoreEpoch != b.restoreEpoch ||
            a.scheduleRevision != b.scheduleRevision ||
            a.definitionRevision != b.definitionRevision ||
            a.issueToken != b.issueToken ||
            (a.instanceToken != null && b.instanceToken != null && a.instanceToken != b.instanceToken)
        ) {
            return OccurrenceComparison.DIFFERENT
        }

        // Step 3: Check for missing instance/issue token witnesses or missing restoreEpoch
        if (a.issueToken.isNullOrBlank() || b.issueToken.isNullOrBlank() ||
            a.instanceToken.isNullOrBlank() || b.instanceToken.isNullOrBlank() ||
            a.restoreEpoch == null || b.restoreEpoch == null
        ) {
            return OccurrenceComparison.UNKNOWN
        }

        return OccurrenceComparison.SAME
    }

    fun evaluateSafety(
        osKeyA: PendingIntentIdentityDescriptor,
        osKeyB: PendingIntentIdentityDescriptor,
        lineageA: OccurrenceLineageDescriptor,
        lineageB: OccurrenceLineageDescriptor
    ): CrossIdentitySafety {
        val osKeyResult = compareOsKeys(osKeyA, osKeyB)
        val lineageResult = compareLineages(lineageA, lineageB)

        if (osKeyResult == OsKeyComparison.INDETERMINATE) {
            val missingWitness = if (osKeyA.intentDataUri == "" || osKeyB.intentDataUri == "") "MALFORMED_URI" else "OS_STATE_UNCERTAIN"
            return CrossIdentitySafety.EvidenceIncomplete(
                missingWitness = missingWitness,
                detail = "OS Key is indeterminate due to missing or malformed OS parameters"
            )
        }

        if (osKeyResult == OsKeyComparison.DISTINCT) {
            return CrossIdentitySafety.TargetMismatch(
                osKeyComparison = osKeyResult,
                occurrenceComparison = lineageResult,
                reason = CancellationRejectionReason.TARGET_WITNESS_MISMATCH,
                detail = "OS Key parameters differ between descriptors"
            )
        }

        // osKeyResult == OsKeyComparison.EQUIVALENT
        if (lineageResult == OccurrenceComparison.UNKNOWN) {
            return CrossIdentitySafety.EvidenceIncomplete(
                missingWitness = "PERSISTENCE_UNCERTAIN",
                detail = "OS Key is equivalent but occurrence lineage is incomplete"
            )
        }

        // Positive MAIN Tokenized V2 Factory Proof check (requires FLAG_IMMUTABLE witness)
        val isV2FactoryCompliantA = osKeyA.isPositiveTokenizedV2 &&
            osKeyA.operationType == PendingIntentOperationType.BROADCAST &&
            osKeyA.creatorPackage == "com.sysadmindoc.alarmclock" &&
            osKeyA.creatorUserScope == 0 &&
            osKeyA.targetComponent == "com.sysadmindoc.alarmclock.receiver.AlarmReceiver" &&
            osKeyA.intentAction == "com.sysadmindoc.alarmclock.ALARM_FIRE" &&
            osKeyA.requestCode == 80001 &&
            (osKeyA.flags and FLAG_IMMUTABLE) != 0

        val isV2FactoryCompliantB = osKeyB.isPositiveTokenizedV2 &&
            osKeyB.operationType == PendingIntentOperationType.BROADCAST &&
            osKeyB.creatorPackage == "com.sysadmindoc.alarmclock" &&
            osKeyB.creatorUserScope == 0 &&
            osKeyB.targetComponent == "com.sysadmindoc.alarmclock.receiver.AlarmReceiver" &&
            osKeyB.intentAction == "com.sysadmindoc.alarmclock.ALARM_FIRE" &&
            osKeyB.requestCode == 80001 &&
            (osKeyB.flags and FLAG_IMMUTABLE) != 0

        val uriAlarmIdA = osKeyA.intentDataUri?.let { extractQueryParam(it, "alarmId")?.toLongOrNull() }
        val uriTokenA = osKeyA.intentDataUri?.let { extractQueryParam(it, "token") }

        val tokenMatchesCanonical = uriTokenA != null && lineageA.issueToken != null &&
            uriTokenA == encodePercentUtf8(lineageA.issueToken)

        if (!isV2FactoryCompliantA || !isV2FactoryCompliantB ||
            uriAlarmIdA != lineageA.alarmId ||
            !tokenMatchesCanonical ||
            osKeyA.computedIsLegacyV1Alias ||
            osKeyB.computedIsLegacyV1Alias
        ) {
            val (reason, detail) = if (lineageResult == OccurrenceComparison.DIFFERENT) {
                CancellationRejectionReason.TARGET_WITNESS_MISMATCH to "red04 Alias Collision: Intent lacks positive V2 tokenized identity proof"
            } else {
                CancellationRejectionReason.OS_STATE_UNCERTAIN to "Legacy or non-tokenized Intent cannot prove exact occurrence cancellation"
            }
            return CrossIdentitySafety.UnsafeAlias(
                osKeyComparison = osKeyResult,
                occurrenceComparison = lineageResult,
                reason = reason,
                detail = detail
            )
        }

        if (lineageResult == OccurrenceComparison.DIFFERENT) {
            return CrossIdentitySafety.UnsafeAlias(
                osKeyComparison = osKeyResult,
                occurrenceComparison = lineageResult,
                reason = CancellationRejectionReason.TARGET_WITNESS_MISMATCH,
                detail = "red04 Alias Collision: OS Key is equivalent but occurrence lineage differs"
            )
        }

        return CrossIdentitySafety.ExactCandidate(
            osKeyComparison = osKeyResult,
            occurrenceComparison = lineageResult,
            detail = "V2 tokenized PendingIntent slot and occurrence lineage match exactly"
        )
    }
}
