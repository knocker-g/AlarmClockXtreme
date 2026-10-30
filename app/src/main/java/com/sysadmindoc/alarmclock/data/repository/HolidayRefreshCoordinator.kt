package com.sysadmindoc.alarmclock.data.repository

import com.sysadmindoc.alarmclock.data.remote.HolidayApi
import com.sysadmindoc.alarmclock.data.remote.HolidayDto
import kotlinx.coroutines.CancellationException
import java.io.File
import java.io.IOException
import java.time.LocalDate

/**
 * KNO-158: Pure refresh, staging, atomic publication and Worker outcome mapping.
 *
 * Responsibilities:
 * - Validate provider DTOs into [HolidayYearCoverage] per requested year.
 * - Accumulate coverage only for successfully fetched and validated years.
 * - Atomic publication: candidate -> validate -> stage (.tmp) -> atomic rename.
 * - Preserve previous valid year data when country and provider match.
 * - Report typed [HolidayRefreshOutcome].
 * - Map outcome to [HolidayWorkerDecision] for WorkManager.
 */

/** Failure category for an unsuccessfully fetched provider year. */
enum class HolidayYearFailureCategory {
    TRANSIENT,
    PERMANENT
}

/** Typed outcomes of a provider refresh attempt. */
sealed class HolidayRefreshOutcome {
    /** All requested years successfully acquired, validated and published. */
    data class Success(val snapshot: HolidayProviderSnapshot, val materialChange: Boolean) : HolidayRefreshOutcome()

    /** Some (but not all) requested years were acquired; partial snapshot published. */
    data class PartialSuccess(
        val snapshot: HolidayProviderSnapshot,
        val successfulYears: Set<Int>,
        val failedYears: Set<Int>,
        val failureCategories: Map<Int, HolidayYearFailureCategory> = emptyMap(),
        val materialChange: Boolean
    ) : HolidayRefreshOutcome() {
        /** True if any unresolved year failed with a transient error. */
        val hasTransientFailure: Boolean
            get() = failureCategories.values.any { it == HolidayYearFailureCategory.TRANSIENT }
                    || (failureCategories.isEmpty() && failedYears.isNotEmpty())
    }

    /** Country not configured or invalid: no network attempt made. */
    object NotConfigured : HolidayRefreshOutcome()

    /** Network/HTTP transient failure where previous snapshot is preserved. */
    data class TransientFailure(val message: String, val cause: Throwable? = null) : HolidayRefreshOutcome()

    /** Permanent failure (e.g. 404 country unsupported, invalid provider payload). */
    data class PermanentFailure(val message: String, val cause: Throwable? = null) : HolidayRefreshOutcome()

    /** Failure staging or publishing the snapshot to durable storage. */
    data class StorageFailure(val message: String, val cause: Throwable? = null) : HolidayRefreshOutcome()

    /** No network fetch attempted because the current snapshot is fresh for all requested years. */
    data class Unchanged(val snapshot: HolidayProviderSnapshot) : HolidayRefreshOutcome()
}

/** Pure WorkManager outcome decision. */
enum class HolidayWorkerDecision {
    SUCCESS,
    RETRY,
    FAILURE
}

object HolidayWorkerResultMapper {
    /**
     * Maps a [HolidayRefreshOutcome] to a WorkManager result decision.
     *
     * Rules:
     * - [HolidayRefreshOutcome.Success] / [HolidayRefreshOutcome.Unchanged] -> SUCCESS.
     * - [HolidayRefreshOutcome.NotConfigured] -> SUCCESS (terminal configuration state; no-op).
     * - [HolidayRefreshOutcome.PartialSuccess] -> RETRY if transient unresolved years remain and
     *   [runAttemptCount] < [maxRetries], else FAILURE (permanent/invalid failures or retry exhausted).
     *   Never claims complete SUCCESS for partial coverage.
     * - [HolidayRefreshOutcome.TransientFailure] / [HolidayRefreshOutcome.StorageFailure] -> RETRY
     *   if [runAttemptCount] < [maxRetries], else FAILURE.
     * - [HolidayRefreshOutcome.PermanentFailure] -> FAILURE.
     */
    fun map(
        outcome: HolidayRefreshOutcome,
        runAttemptCount: Int,
        maxRetries: Int = 3
    ): HolidayWorkerDecision {
        return when (outcome) {
            is HolidayRefreshOutcome.Success,
            is HolidayRefreshOutcome.Unchanged,
            is HolidayRefreshOutcome.NotConfigured -> HolidayWorkerDecision.SUCCESS

            is HolidayRefreshOutcome.PartialSuccess -> {
                if (outcome.hasTransientFailure) {
                    if (runAttemptCount < maxRetries) HolidayWorkerDecision.RETRY else HolidayWorkerDecision.FAILURE
                } else {
                    HolidayWorkerDecision.FAILURE
                }
            }

            is HolidayRefreshOutcome.TransientFailure,
            is HolidayRefreshOutcome.StorageFailure -> {
                if (runAttemptCount < maxRetries) HolidayWorkerDecision.RETRY else HolidayWorkerDecision.FAILURE
            }

            is HolidayRefreshOutcome.PermanentFailure -> HolidayWorkerDecision.FAILURE
        }
    }
}

/**
 * Storage seam for provider snapshots.
 * Uses atomic file replacement (stage to `.tmp`, then atomic rename).
 */
interface HolidaySnapshotStore {
    fun read(): HolidayProviderSnapshot?
    fun writeAtomically(snapshot: HolidayProviderSnapshot): Boolean
    fun clear()
}

class FileHolidaySnapshotStore(private val file: File) : HolidaySnapshotStore {
    private val atomicFile = androidx.core.util.AtomicFile(file)

    override fun read(): HolidayProviderSnapshot? {
        return try {
            val backupFile = File(file.parentFile, "${file.name}.bak")
            if (!file.exists() && !backupFile.exists()) null
            else {
                val bytes = atomicFile.readFully()
                HolidaySnapshotCodec.decode(String(bytes, Charsets.UTF_8))
            }
        } catch (_: Exception) {
            null
        }
    }

    override fun writeAtomically(snapshot: HolidayProviderSnapshot): Boolean {
        var stream: java.io.FileOutputStream? = null
        return try {
            val encoded = HolidaySnapshotCodec.encode(snapshot)
            val parent = file.parentFile ?: return false
            if (!parent.exists() && !parent.mkdirs()) return false
            
            stream = atomicFile.startWrite()
            stream.write(encoded.toByteArray(Charsets.UTF_8))
            atomicFile.finishWrite(stream)
            true
        } catch (_: Exception) {
            if (stream != null) {
                atomicFile.failWrite(stream)
            }
            false
        }
    }

    override fun clear() {
        runCatching { atomicFile.delete() }
    }
}

/** Pure refresh orchestrator with injectable clock, API and store. */
class HolidayRefreshCoordinator(
    private val holidayApi: HolidayApi,
    private val store: HolidaySnapshotStore,
    private val clockMillis: () -> Long = { System.currentTimeMillis() }
) {
    /**
     * Executes a refresh attempt for [requestedYears] and [rawCountryCode].
     *
     * Invariants:
     * - Only successfully fetched and validated years enter coverage.
     * - Previous years for the same country are preserved unless replaced by
     *   a new successful fetch.
     * - If country changes, previous country data is NEVER retained.
     * - Partial success publishes a valid snapshot describing ONLY successfully
     *   materialized years.
     * - Publication is all-or-nothing via [HolidaySnapshotStore.writeAtomically].
     * - Never swallows [CancellationException].
     */
    suspend fun refresh(
        rawCountryCode: String?,
        requestedYears: Set<Int>,
        force: Boolean = false
    ): HolidayRefreshOutcome {
        val country = HolidayProviderSnapshot.normalizeCountry(rawCountryCode)
            ?: return HolidayRefreshOutcome.NotConfigured
        if (requestedYears.isEmpty()) return HolidayRefreshOutcome.NotConfigured

        val currentSnapshot = store.read()
        val now = clockMillis()

        // Check if all requested years are already fresh in the current snapshot
        if (!force && currentSnapshot != null &&
            currentSnapshot.countryCode == country &&
            currentSnapshot.providerId == NAGER_DATE_PROVIDER_ID
        ) {
            val allFresh = requestedYears.all { year ->
                val coverage = currentSnapshot.years[year]
                coverage != null && HolidayClassifier.freshnessOf(coverage, now) == HolidayProviderFreshness.FRESH
            }
            if (allFresh) return HolidayRefreshOutcome.Unchanged(currentSnapshot)
        }

        // Fetch each requested year separately
        val successfulCoverages = LinkedHashMap<Int, HolidayYearCoverage>()
        val failedYears = mutableSetOf<Int>()
        val failureCategories = LinkedHashMap<Int, HolidayYearFailureCategory>()
        var lastTransientError: String? = null
        var lastPermanentError: String? = null

        for (year in requestedYears.sorted()) {
            try {
                val dtos = holidayApi.getPublicHolidays(year, country)
                val coverage = parseAndValidateYear(year, country, dtos, now)
                if (coverage != null) {
                    successfulCoverages[year] = coverage
                } else {
                    failedYears.add(year)
                    failureCategories[year] = HolidayYearFailureCategory.PERMANENT
                    lastPermanentError = "Invalid holiday DTOs for $country/$year"
                }
            } catch (ce: CancellationException) {
                throw ce
            } catch (ioe: IOException) {
                failedYears.add(year)
                failureCategories[year] = HolidayYearFailureCategory.TRANSIENT
                lastTransientError = ioe.message ?: "Network error fetching $country/$year"
            } catch (e: Exception) {
                // HTTP 4xx or parsing error
                failedYears.add(year)
                val msg = e.message ?: e.javaClass.simpleName
                if (msg.contains("404") || msg.contains("400") || msg.contains("Moshi")) {
                    failureCategories[year] = HolidayYearFailureCategory.PERMANENT
                    lastPermanentError = "Permanent provider error for $country/$year: $msg"
                } else {
                    failureCategories[year] = HolidayYearFailureCategory.TRANSIENT
                    lastTransientError = "Provider error for $country/$year: $msg"
                }
            }
        }

        if (successfulCoverages.isEmpty()) {
            return if (lastPermanentError != null && lastTransientError == null) {
                HolidayRefreshOutcome.PermanentFailure(lastPermanentError)
            } else {
                HolidayRefreshOutcome.TransientFailure(lastTransientError ?: "All requested years failed")
            }
        }

        // Merge with existing coverage if country matches
        val baseYears: Map<Int, HolidayYearCoverage> =
            if (currentSnapshot != null &&
                currentSnapshot.countryCode == country &&
                currentSnapshot.providerId == NAGER_DATE_PROVIDER_ID
            ) {
                currentSnapshot.years
            } else {
                emptyMap()
            }

        val mergedYears = LinkedHashMap<Int, HolidayYearCoverage>(baseYears)
        mergedYears.putAll(successfulCoverages)

        val nextGeneration = (currentSnapshot?.generation ?: 0L) + 1L
        val candidate = HolidayProviderSnapshot(
            providerId = NAGER_DATE_PROVIDER_ID,
            countryCode = country,
            generation = nextGeneration,
            years = mergedYears
        )

        val validationErr = candidate.validationError()
        if (validationErr != null) {
            return HolidayRefreshOutcome.PermanentFailure("Candidate snapshot failed validation: $validationErr")
        }

        val written = store.writeAtomically(candidate)
        if (!written) {
            return HolidayRefreshOutcome.StorageFailure("Failed to atomically write candidate snapshot")
        }

        val materialChange = currentSnapshot == null || currentSnapshot.materialTruth != candidate.materialTruth

        return if (failedYears.isEmpty()) {
            HolidayRefreshOutcome.Success(candidate, materialChange)
        } else {
            HolidayRefreshOutcome.PartialSuccess(
                snapshot = candidate,
                successfulYears = successfulCoverages.keys,
                failedYears = failedYears,
                failureCategories = failureCategories,
                materialChange = materialChange
            )
        }
    }

    private fun parseAndValidateYear(
        year: Int,
        expectedCountry: String,
        dtos: List<HolidayDto>?,
        fetchedAt: Long
    ): HolidayYearCoverage? {
        if (dtos == null) return null
        val dates = mutableSetOf<LocalDate>()
        for (dto in dtos) {
            if (dto.countryCode.isNotBlank() &&
                !dto.countryCode.equals(expectedCountry, ignoreCase = true)
            ) {
                // Reject DTO from conflicting country
                return null
            }
            val parsed = runCatching { LocalDate.parse(dto.date) }.getOrNull() ?: return null
            if (parsed.year != year) {
                // Reject DTO with year mismatch
                return null
            }
            dates.add(parsed)
        }
        if (dates.isEmpty()) return null // Provider contract: public holidays exist for populated countries
        return HolidayYearCoverage(year, fetchedAt, dates)
    }
}
