package com.sysadmindoc.alarmclock.data.repository

import java.security.MessageDigest
import java.time.LocalDate

/**
 * KNO-158: Holiday provider snapshot truth and local classification.
 *
 * Pure Kotlin (no Android / network types) so cache truth, freshness and
 * classification are deterministic and unit-testable.
 *
 * The provider snapshot is derived, device-local cache data (KNO-31). It is
 * NOT Backup authority and NOT manual Holiday configuration.
 */

/** The single provider supported by ACX (KNO-31: reuse existing Nager.Date acquisition). */
const val NAGER_DATE_PROVIDER_ID = "nager.date/v3"

/**
 * One successfully materialized provider year.
 *
 * [fetchedAtMillis] is the wall-clock time at which this year's provider
 * response was successfully received and validated. It is never advanced by
 * cache reads, reloads, process restarts or failed refresh attempts.
 */
data class HolidayYearCoverage(
    val year: Int,
    val fetchedAtMillis: Long,
    val dates: Set<LocalDate>
)

/**
 * One logical, immutable provider snapshot generation.
 *
 * Dates, country, provider identity, coverage and per-year successful
 * fetchedAt are stored together and published together; readers can never
 * combine dates from one generation with metadata from another.
 */
data class HolidayProviderSnapshot(
    val providerId: String,
    val countryCode: String,
    val generation: Long,
    val years: Map<Int, HolidayYearCoverage>
) {
    /** Years for which provider data was successfully materialized. */
    val coveredYears: Set<Int> get() = years.keys

    /** All provider Holiday dates across covered years. */
    val holidayDates: Set<LocalDate> get() = years.values.flatMapTo(sortedSetOf()) { it.dates }

    /** Most recent durable successful fetch time across covered years (0 when none). */
    val lastSuccessfulFetchAtMillis: Long get() = years.values.maxOfOrNull { it.fetchedAtMillis } ?: 0L

    /** Material provider truth (excludes fetchedAt/generation) used for change detection. */
    val materialTruth: Pair<String, Map<Int, Set<LocalDate>>>
        get() = countryCode to years.mapValues { it.value.dates }

    fun isValid(): Boolean = validationError() == null

    /** Returns a description of the first integrity violation, or null when valid. */
    fun validationError(): String? {
        if (providerId != NAGER_DATE_PROVIDER_ID) return "unsupported provider '$providerId'"
        if (!COUNTRY_CODE_REGEX.matches(countryCode)) return "invalid country '$countryCode'"
        if (generation < 1L) return "invalid generation $generation"
        if (years.isEmpty()) return "no covered years"
        for ((key, coverage) in years) {
            if (key != coverage.year) return "year key $key does not match coverage ${coverage.year}"
            if (key !in MIN_YEAR..MAX_YEAR) return "year $key out of range"
            if (coverage.fetchedAtMillis <= 0L) return "year $key has no successful fetchedAt"
            if (coverage.dates.isEmpty()) return "year $key has no provider dates"
            coverage.dates.firstOrNull { it.year != key }?.let { return "date $it outside covered year $key" }
        }
        return null
    }

    companion object {
        const val FORMAT_VERSION = 1
        const val MIN_YEAR = 1900
        const val MAX_YEAR = 9999
        internal val COUNTRY_CODE_REGEX = Regex("^[A-Z]{2}$")

        /** Normalizes a configured country to provider identity, or null when unusable. */
        fun normalizeCountry(raw: String?): String? {
            val normalized = raw?.trim()?.uppercase(java.util.Locale.US) ?: return null
            return normalized.takeIf { COUNTRY_CODE_REGEX.matches(it) }
        }
    }
}

/**
 * Single-file serialization of [HolidayProviderSnapshot] with an embedded
 * SHA-256 integrity line. Decoding is strict: any malformed, truncated,
 * reordered, checksum-mismatched or semantically invalid content yields null.
 *
 * Format (UTF-8, '\n' separated):
 * ```
 * ACX_HOLIDAY_PROVIDER_SNAPSHOT
 * formatVersion=1
 * provider=nager.date/v3
 * country=JP
 * generation=3
 * yearCount=2
 * year=2026;fetchedAt=1790000000000;dates=2026-01-01,2026-01-12
 * year=2027;fetchedAt=1790000000000;dates=2027-01-01
 * sha256=<hex of all preceding bytes, including trailing '\n'>
 * ```
 */
object HolidaySnapshotCodec {
    private const val MAGIC = "ACX_HOLIDAY_PROVIDER_SNAPSHOT"
    private const val CHECKSUM_PREFIX = "sha256="

    fun encode(snapshot: HolidayProviderSnapshot): String {
        val error = snapshot.validationError()
        require(error == null) { "Refusing to encode invalid Holiday snapshot: $error" }
        val body = buildString {
            append(MAGIC).append('\n')
            append("formatVersion=").append(HolidayProviderSnapshot.FORMAT_VERSION).append('\n')
            append("provider=").append(snapshot.providerId).append('\n')
            append("country=").append(snapshot.countryCode).append('\n')
            append("generation=").append(snapshot.generation).append('\n')
            append("yearCount=").append(snapshot.years.size).append('\n')
            snapshot.years.keys.sorted().forEach { year ->
                val coverage = snapshot.years.getValue(year)
                append("year=").append(year)
                append(";fetchedAt=").append(coverage.fetchedAtMillis)
                append(";dates=").append(coverage.dates.sorted().joinToString(","))
                append('\n')
            }
        }
        return body + CHECKSUM_PREFIX + sha256Hex(body) + "\n"
    }

    fun decode(text: String): HolidayProviderSnapshot? = runCatching { decodeOrThrow(text) }.getOrNull()

    private fun decodeOrThrow(text: String): HolidayProviderSnapshot? {
        if (!text.endsWith("\n")) return null
        val checksumStart = text.lastIndexOf("\n$CHECKSUM_PREFIX")
        if (checksumStart < 0) return null
        val body = text.substring(0, checksumStart + 1)
        val checksumLine = text.substring(checksumStart + 1).removeSuffix("\n")
        if (checksumLine.contains('\n')) return null
        if (checksumLine.removePrefix(CHECKSUM_PREFIX) != sha256Hex(body)) return null

        val lines = body.removeSuffix("\n").split('\n')
        if (lines.size < 7) return null
        if (lines[0] != MAGIC) return null
        if (value(lines[1], "formatVersion")?.toIntOrNull() != HolidayProviderSnapshot.FORMAT_VERSION) return null
        val provider = value(lines[2], "provider") ?: return null
        val country = value(lines[3], "country") ?: return null
        val generation = value(lines[4], "generation")?.toLongOrNull() ?: return null
        val yearCount = value(lines[5], "yearCount")?.toIntOrNull() ?: return null
        val yearLines = lines.drop(6)
        if (yearCount < 1 || yearLines.size != yearCount) return null

        val years = LinkedHashMap<Int, HolidayYearCoverage>()
        for (line in yearLines) {
            val parts = line.split(';')
            if (parts.size != 3) return null
            val year = value(parts[0], "year")?.toIntOrNull() ?: return null
            val fetchedAt = value(parts[1], "fetchedAt")?.toLongOrNull() ?: return null
            val rawDates = value(parts[2], "dates") ?: return null
            val dateTokens = if (rawDates.isEmpty()) emptyList() else rawDates.split(',')
            val dates = dateTokens.map { LocalDate.parse(it) }.toSet()
            if (dates.size != dateTokens.size) return null
            if (years.put(year, HolidayYearCoverage(year, fetchedAt, dates)) != null) return null
        }
        val snapshot = HolidayProviderSnapshot(
            providerId = provider,
            countryCode = country,
            generation = generation,
            years = years
        )
        return snapshot.takeIf { it.isValid() }
    }

    private fun value(line: String, key: String): String? {
        val prefix = "$key="
        return if (line.startsWith(prefix)) line.substring(prefix.length) else null
    }

    private fun sha256Hex(text: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(text.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
}

/** Final Holiday classification vocabulary (KNO-31 / KNO-158). */
enum class HolidayClassificationKind { HOLIDAY, WORKDAY, UNKNOWN }

/** Which authority produced a [HolidayClassification]. */
enum class HolidayClassificationSource { MANUAL_OVERRIDE, PROVIDER, NONE }

/**
 * Freshness of the provider year used for classification. Derived only from
 * the durable successful per-year fetchedAt; distinct from "uncovered".
 */
enum class HolidayProviderFreshness { FRESH, STALE }

/** Why a classification is UNKNOWN. */
enum class HolidayUnknownReason {
    PROVIDER_NOT_CONFIGURED,
    NO_VALID_SNAPSHOT,
    PROVIDER_MISMATCH,
    COUNTRY_MISMATCH,
    YEAR_NOT_COVERED
}

data class HolidayClassification(
    val kind: HolidayClassificationKind,
    val source: HolidayClassificationSource,
    val freshness: HolidayProviderFreshness? = null,
    val unknownReason: HolidayUnknownReason? = null
)

/**
 * Per-date manual Holiday override state (KNO-31 Holiday Override State LOCK:
 * DEFAULT/HOLIDAY/WORKDAY). [AUTO] returns authority to the provider; it is
 * never interpreted as WORKDAY.
 */
enum class ManualHolidayOverride { AUTO, FORCE_HOLIDAY, FORCE_WORKDAY }

/**
 * Read seam for manual Holiday overrides. KNO-158 owns precedence at the
 * classification layer only; the durable manual store/UI is owned by the
 * KNO-31 parent residual scope and is not created here.
 */
fun interface ManualHolidayOverrideSource {
    fun overrideFor(date: LocalDate): ManualHolidayOverride

    companion object {
        /** No manual overrides recorded: every date defers to the provider. */
        val NONE: ManualHolidayOverrideSource = ManualHolidayOverrideSource { ManualHolidayOverride.AUTO }
    }
}

/**
 * Deterministic, local-only Holiday classification. Never performs I/O.
 *
 * Resolution priority:
 * 1. manual FORCE_HOLIDAY -> HOLIDAY
 * 2. manual FORCE_WORKDAY -> WORKDAY
 * 3. valid provider snapshot for the configured country covering the year
 *    -> HOLIDAY when the date is a provider date, WORKDAY otherwise
 * 4. UNKNOWN
 *
 * Stale-but-covered provider data is still used (KNO-31 Architecture LOCK:
 * preserve last valid local provider data, expose freshness separately) and is
 * reported with [HolidayProviderFreshness.STALE].
 */
object HolidayClassifier {
    /** Existing canonical provider TTL (legacy HolidayRepository: 7 days). */
    const val PROVIDER_FRESHNESS_TTL_MILLIS: Long = 7L * 24 * 60 * 60 * 1000

    fun classify(
        date: LocalDate,
        manualOverride: ManualHolidayOverride,
        snapshot: HolidayProviderSnapshot?,
        configuredCountry: String?,
        nowMillis: Long
    ): HolidayClassification {
        when (manualOverride) {
            ManualHolidayOverride.FORCE_HOLIDAY -> return HolidayClassification(
                HolidayClassificationKind.HOLIDAY, HolidayClassificationSource.MANUAL_OVERRIDE
            )
            ManualHolidayOverride.FORCE_WORKDAY -> return HolidayClassification(
                HolidayClassificationKind.WORKDAY, HolidayClassificationSource.MANUAL_OVERRIDE
            )
            ManualHolidayOverride.AUTO -> Unit
        }
        val country = HolidayProviderSnapshot.normalizeCountry(configuredCountry)
            ?: return unknown(HolidayUnknownReason.PROVIDER_NOT_CONFIGURED)
        if (snapshot == null || !snapshot.isValid()) return unknown(HolidayUnknownReason.NO_VALID_SNAPSHOT)
        if (snapshot.providerId != NAGER_DATE_PROVIDER_ID) return unknown(HolidayUnknownReason.PROVIDER_MISMATCH)
        if (snapshot.countryCode != country) return unknown(HolidayUnknownReason.COUNTRY_MISMATCH)
        val coverage = snapshot.years[date.year] ?: return unknown(HolidayUnknownReason.YEAR_NOT_COVERED)
        val kind = if (date in coverage.dates) HolidayClassificationKind.HOLIDAY else HolidayClassificationKind.WORKDAY
        return HolidayClassification(
            kind = kind,
            source = HolidayClassificationSource.PROVIDER,
            freshness = freshnessOf(coverage, nowMillis)
        )
    }

    /**
     * FRESH only when the durable successful fetch is within the TTL. A fetch
     * time in the future (clock moved backwards) cannot prove freshness.
     */
    fun freshnessOf(coverage: HolidayYearCoverage, nowMillis: Long): HolidayProviderFreshness {
        val age = nowMillis - coverage.fetchedAtMillis
        return if (age in 0..PROVIDER_FRESHNESS_TTL_MILLIS) HolidayProviderFreshness.FRESH
        else HolidayProviderFreshness.STALE
    }

    private fun unknown(reason: HolidayUnknownReason) = HolidayClassification(
        kind = HolidayClassificationKind.UNKNOWN,
        source = HolidayClassificationSource.NONE,
        unknownReason = reason
    )
}
