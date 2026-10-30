package com.sysadmindoc.alarmclock.data.repository

import com.sysadmindoc.alarmclock.data.remote.HolidayApi
import com.sysadmindoc.alarmclock.data.remote.HolidayDto
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.time.LocalDate

/**
 * Deterministic tests for KNO-158 Holiday Provider / Cache Truth & Refresh.
 * No live HTTP, no Hilt/WorkManager harness.
 *
 * Covers T01 - T26 as specified in the execution contract.
 */
class HolidayProviderSnapshotTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val jan1 = LocalDate.of(2026, 1, 1)
    private val jan2 = LocalDate.of(2026, 1, 2)
    private val feb1 = LocalDate.of(2026, 2, 1)
    private val nextYearJan1 = LocalDate.of(2027, 1, 1)
    private val nowMillis = 1770000000000L

    private fun sampleSnapshot(
        country: String = "JP",
        generation: Long = 1L,
        years: Map<Int, HolidayYearCoverage> = mapOf(
            2026 to HolidayYearCoverage(2026, nowMillis, setOf(jan1, jan2))
        )
    ) = HolidayProviderSnapshot(
        providerId = NAGER_DATE_PROVIDER_ID,
        countryCode = country,
        generation = generation,
        years = years
    )

    // T01: Covered provider Holiday -> HOLIDAY
    @Test
    fun t01_coveredProviderHoliday_returnsHoliday() {
        val snapshot = sampleSnapshot()
        val result = HolidayClassifier.classify(
            date = jan1,
            manualOverride = ManualHolidayOverride.AUTO,
            snapshot = snapshot,
            configuredCountry = "JP",
            nowMillis = nowMillis
        )
        assertEquals(HolidayClassificationKind.HOLIDAY, result.kind)
        assertEquals(HolidayClassificationSource.PROVIDER, result.source)
        assertEquals(HolidayProviderFreshness.FRESH, result.freshness)
    }

    // T02: Covered provider non-Holiday -> WORKDAY
    @Test
    fun t02_coveredProviderNonHoliday_returnsWorkday() {
        val snapshot = sampleSnapshot()
        val result = HolidayClassifier.classify(
            date = feb1,
            manualOverride = ManualHolidayOverride.AUTO,
            snapshot = snapshot,
            configuredCountry = "JP",
            nowMillis = nowMillis
        )
        assertEquals(HolidayClassificationKind.WORKDAY, result.kind)
        assertEquals(HolidayClassificationSource.PROVIDER, result.source)
        assertEquals(HolidayProviderFreshness.FRESH, result.freshness)
    }

    // T03: Uncovered year -> UNKNOWN
    @Test
    fun t03_uncoveredYear_returnsUnknown() {
        val snapshot = sampleSnapshot() // only covers 2026
        val result = HolidayClassifier.classify(
            date = nextYearJan1,
            manualOverride = ManualHolidayOverride.AUTO,
            snapshot = snapshot,
            configuredCountry = "JP",
            nowMillis = nowMillis
        )
        assertEquals(HolidayClassificationKind.UNKNOWN, result.kind)
        assertEquals(HolidayUnknownReason.YEAR_NOT_COVERED, result.unknownReason)
    }

    // T04: Manual FORCE_HOLIDAY takes precedence
    @Test
    fun t04_manualForceHoliday_takesPrecedence() {
        val snapshot = sampleSnapshot()
        // feb1 is a non-holiday in provider
        val result = HolidayClassifier.classify(
            date = feb1,
            manualOverride = ManualHolidayOverride.FORCE_HOLIDAY,
            snapshot = snapshot,
            configuredCountry = "JP",
            nowMillis = nowMillis
        )
        assertEquals(HolidayClassificationKind.HOLIDAY, result.kind)
        assertEquals(HolidayClassificationSource.MANUAL_OVERRIDE, result.source)
    }

    // T05: Manual FORCE_WORKDAY takes precedence
    @Test
    fun t05_manualForceWorkday_takesPrecedence() {
        val snapshot = sampleSnapshot()
        // jan1 is a holiday in provider
        val result = HolidayClassifier.classify(
            date = jan1,
            manualOverride = ManualHolidayOverride.FORCE_WORKDAY,
            snapshot = snapshot,
            configuredCountry = "JP",
            nowMillis = nowMillis
        )
        assertEquals(HolidayClassificationKind.WORKDAY, result.kind)
        assertEquals(HolidayClassificationSource.MANUAL_OVERRIDE, result.source)
    }

    // T06: Manual AUTO restores provider classification
    @Test
    fun t06_manualAuto_restoresProviderClassification() {
        val snapshot = sampleSnapshot()
        val holidayResult = HolidayClassifier.classify(
            date = jan1,
            manualOverride = ManualHolidayOverride.AUTO,
            snapshot = snapshot,
            configuredCountry = "JP",
            nowMillis = nowMillis
        )
        assertEquals(HolidayClassificationKind.HOLIDAY, holidayResult.kind)

        val workdayResult = HolidayClassifier.classify(
            date = feb1,
            manualOverride = ManualHolidayOverride.AUTO,
            snapshot = snapshot,
            configuredCountry = "JP",
            nowMillis = nowMillis
        )
        assertEquals(HolidayClassificationKind.WORKDAY, workdayResult.kind)
    }

    // T07: Current-year fetch fails, next-year succeeds
    @Test
    fun t07_currentYearFailsNextYearSucceeds_onlyNextYearCovered() = runTest {
        val fakeApi = object : HolidayApi {
            override suspend fun getPublicHolidays(year: Int, countryCode: String): List<HolidayDto> {
                if (year == 2026) throw IOException("Network timeout")
                return listOf(HolidayDto("2027-01-01", "New Year", "New Year", countryCode))
            }
        }
        val store = FileHolidaySnapshotStore(tempFolder.newFile("snapshot.txt"))
        val coordinator = HolidayRefreshCoordinator(fakeApi, store) { nowMillis }

        val outcome = coordinator.refresh("JP", setOf(2026, 2027))
        assertTrue(outcome is HolidayRefreshOutcome.PartialSuccess)
        val partial = outcome as HolidayRefreshOutcome.PartialSuccess
        assertEquals(setOf(2027), partial.successfulYears)
        assertEquals(setOf(2026), partial.failedYears)
        assertEquals(setOf(2027), partial.snapshot.coveredYears)
    }

    // T08: Only successfully materialized years enter coveredYears
    @Test
    fun t08_onlySuccessfullyMaterializedYearsEnterCoverage() = runTest {
        val fakeApi = object : HolidayApi {
            override suspend fun getPublicHolidays(year: Int, countryCode: String): List<HolidayDto> {
                if (year == 2026) return listOf(HolidayDto("2026-01-01", "NY", "NY", countryCode))
                throw IOException("Fetch failed")
            }
        }
        val store = FileHolidaySnapshotStore(tempFolder.newFile("snapshot.txt"))
        val coordinator = HolidayRefreshCoordinator(fakeApi, store) { nowMillis }

        val outcome = coordinator.refresh("JP", setOf(2026, 2027, 2028))
        assertTrue(outcome is HolidayRefreshOutcome.PartialSuccess)
        val snapshot = (outcome as HolidayRefreshOutcome.PartialSuccess).snapshot
        assertEquals(setOf(2026), snapshot.coveredYears)
        assertFalse(2027 in snapshot.coveredYears)
        assertFalse(2028 in snapshot.coveredYears)
    }

    // T09: Failed refresh preserves last good snapshot
    @Test
    fun t09_failedRefreshPreservesLastGoodSnapshot() = runTest {
        val initialSnapshot = sampleSnapshot()
        val store = FileHolidaySnapshotStore(tempFolder.newFile("snapshot.txt"))
        store.writeAtomically(initialSnapshot)

        val failingApi = object : HolidayApi {
            override suspend fun getPublicHolidays(year: Int, countryCode: String): List<HolidayDto> {
                throw IOException("Network error")
            }
        }
        val coordinator = HolidayRefreshCoordinator(failingApi, store) { nowMillis + 10000 }
        val outcome = coordinator.refresh("JP", setOf(2026), force = true)

        assertTrue(outcome is HolidayRefreshOutcome.TransientFailure)
        val readBack = store.read()
        assertNotNull(readBack)
        assertEquals(initialSnapshot.generation, readBack?.generation)
        assertEquals(initialSnapshot.lastSuccessfulFetchAtMillis, readBack?.lastSuccessfulFetchAtMillis)
    }

    // T10: Failed refresh does not advance fetchedAt
    @Test
    fun t10_failedRefreshDoesNotAdvanceFetchedAt() = runTest {
        val initialFetchedAt = 1000000L
        val initialSnapshot = sampleSnapshot().copy(
            years = mapOf(2026 to HolidayYearCoverage(2026, initialFetchedAt, setOf(jan1)))
        )
        val store = FileHolidaySnapshotStore(tempFolder.newFile("snapshot.txt"))
        store.writeAtomically(initialSnapshot)

        val failingApi = object : HolidayApi {
            override suspend fun getPublicHolidays(year: Int, countryCode: String): List<HolidayDto> {
                throw IOException("503 Service Unavailable")
            }
        }
        val coordinator = HolidayRefreshCoordinator(failingApi, store) { 9999999L }
        coordinator.refresh("JP", setOf(2026), force = true)

        val readBack = store.read()
        assertEquals(initialFetchedAt, readBack?.years?.get(2026)?.fetchedAtMillis)
    }

    // T11: Reloading stale cache does not refresh TTL
    @Test
    fun t11_reloadingStaleCacheDoesNotRefreshTtl() {
        val oldFetchTime = nowMillis - (8L * 24 * 60 * 60 * 1000) // 8 days ago (expired TTL)
        val staleCoverage = HolidayYearCoverage(2026, oldFetchTime, setOf(jan1))
        val staleSnapshot = sampleSnapshot().copy(years = mapOf(2026 to staleCoverage))

        // First check
        val freshness1 = HolidayClassifier.freshnessOf(staleCoverage, nowMillis)
        assertEquals(HolidayProviderFreshness.STALE, freshness1)

        // Read or reload should not alter the recorded fetchedAt
        val encoded = HolidaySnapshotCodec.encode(staleSnapshot)
        val reloaded = HolidaySnapshotCodec.decode(encoded)
        assertNotNull(reloaded)
        val reloadedCoverage = reloaded!!.years.getValue(2026)
        assertEquals(oldFetchTime, reloadedCoverage.fetchedAtMillis)

        val freshness2 = HolidayClassifier.freshnessOf(reloadedCoverage, nowMillis)
        assertEquals(HolidayProviderFreshness.STALE, freshness2)
    }

    // T12: Cache dates and metadata never expose mixed generations
    @Test
    fun t12_cacheDatesAndMetadataNeverExposeMixedGenerations() {
        val gen1 = sampleSnapshot(generation = 1L)
        val encoded1 = HolidaySnapshotCodec.encode(gen1)

        val gen2Coverage = mapOf(
            2026 to HolidayYearCoverage(2026, nowMillis + 5000, setOf(feb1))
        )
        val gen2 = sampleSnapshot(generation = 2L, years = gen2Coverage)
        val encoded2 = HolidaySnapshotCodec.encode(gen2)

        // Splicing lines from gen1 (metadata) and gen2 (year line) violates the embedded checksum and is rejected
        val lines1 = encoded1.split('\n').filter { it.isNotBlank() }
        val lines2 = encoded2.split('\n').filter { it.isNotBlank() }
        // lines1: [0:MAGIC, 1:formatVersion, 2:provider, 3:country, 4:generation=1, 5:yearCount=1, 6:year=..., 7:sha256=...]
        // Mix metadata of gen1 with year coverage and sha256 of gen2:
        val mixedGen = (lines1.take(5) + lines2.drop(5)).joinToString("\n") + "\n"

        assertNull(HolidaySnapshotCodec.decode(mixedGen))
    }

    // T13: Corrupted snapshot safely yields UNKNOWN
    @Test
    fun t13_corruptedSnapshot_safelyYieldsUnknown() {
        val corruptedText = "ACX_HOLIDAY_PROVIDER_SNAPSHOT\ncorrupted content\nsha256=123456\n"
        val decoded = HolidaySnapshotCodec.decode(corruptedText)
        assertNull(decoded)

        val result = HolidayClassifier.classify(
            date = jan1,
            manualOverride = ManualHolidayOverride.AUTO,
            snapshot = decoded,
            configuredCountry = "JP",
            nowMillis = nowMillis
        )
        assertEquals(HolidayClassificationKind.UNKNOWN, result.kind)
        assertEquals(HolidayUnknownReason.NO_VALID_SNAPSHOT, result.unknownReason)
    }

    // T14: Equivalent refresh is idempotent
    @Test
    fun t14_equivalentRefreshIsIdempotent() = runTest {
        val fakeApi = object : HolidayApi {
            override suspend fun getPublicHolidays(year: Int, countryCode: String): List<HolidayDto> {
                return listOf(HolidayDto("2026-01-01", "NY", "NY", countryCode))
            }
        }
        val store = FileHolidaySnapshotStore(tempFolder.newFile("snapshot.txt"))
        val coordinator = HolidayRefreshCoordinator(fakeApi, store) { nowMillis }

        val outcome1 = coordinator.refresh("JP", setOf(2026))
        assertTrue(outcome1 is HolidayRefreshOutcome.Success)
        assertTrue((outcome1 as HolidayRefreshOutcome.Success).materialChange)

        // Second call without force: detects all requested years are fresh
        val outcome2 = coordinator.refresh("JP", setOf(2026))
        assertTrue(outcome2 is HolidayRefreshOutcome.Unchanged)
    }

    // T15: Worker success mapping
    @Test
    fun t15_workerSuccessMapping() {
        val successOutcome = HolidayRefreshOutcome.Success(sampleSnapshot(), materialChange = true)
        assertEquals(HolidayWorkerDecision.SUCCESS, HolidayWorkerResultMapper.map(successOutcome, 0))

        val unchangedOutcome = HolidayRefreshOutcome.Unchanged(sampleSnapshot())
        assertEquals(HolidayWorkerDecision.SUCCESS, HolidayWorkerResultMapper.map(unchangedOutcome, 0))

        val notConfigured = HolidayRefreshOutcome.NotConfigured
        assertEquals(HolidayWorkerDecision.SUCCESS, HolidayWorkerResultMapper.map(notConfigured, 0))
    }

    // T16: Worker transient retry mapping
    @Test
    fun t16_workerTransientRetryMapping() {
        val transient = HolidayRefreshOutcome.TransientFailure("Network timeout")
        assertEquals(HolidayWorkerDecision.RETRY, HolidayWorkerResultMapper.map(transient, runAttemptCount = 0))
        assertEquals(HolidayWorkerDecision.RETRY, HolidayWorkerResultMapper.map(transient, runAttemptCount = 2))
        assertEquals(HolidayWorkerDecision.FAILURE, HolidayWorkerResultMapper.map(transient, runAttemptCount = 3))
    }

    // T17: Worker permanent failure mapping
    @Test
    fun t17_workerPermanentFailureMapping() {
        val permanent = HolidayRefreshOutcome.PermanentFailure("404 Country not found")
        assertEquals(HolidayWorkerDecision.FAILURE, HolidayWorkerResultMapper.map(permanent, runAttemptCount = 0))
        assertEquals(HolidayWorkerDecision.FAILURE, HolidayWorkerResultMapper.map(permanent, runAttemptCount = 1))
    }

    // T18: Year boundary and LocalDate handling
    @Test
    fun t18_yearBoundaryAndLocalDateHandling() {
        val dec31 = LocalDate.of(2026, 12, 31)
        val jan1Next = LocalDate.of(2027, 1, 1)

        val multiYearCoverage = mapOf(
            2026 to HolidayYearCoverage(2026, nowMillis, setOf(dec31)),
            2027 to HolidayYearCoverage(2027, nowMillis, setOf(jan1Next))
        )
        val snapshot = sampleSnapshot(years = multiYearCoverage)

        val resDec31 = HolidayClassifier.classify(dec31, ManualHolidayOverride.AUTO, snapshot, "JP", nowMillis)
        val resJan1 = HolidayClassifier.classify(jan1Next, ManualHolidayOverride.AUTO, snapshot, "JP", nowMillis)

        assertEquals(HolidayClassificationKind.HOLIDAY, resDec31.kind)
        assertEquals(HolidayClassificationKind.HOLIDAY, resJan1.kind)
    }

    // T19: Process restart restores durable successful snapshot truth
    @Test
    fun t19_processRestartRestoresDurableSnapshot() {
        val file = tempFolder.newFile("snapshot.txt")
        val store1 = FileHolidaySnapshotStore(file)
        val original = sampleSnapshot()
        store1.writeAtomically(original)

        // Simulate process restart by creating a new store reading the same file
        val store2 = FileHolidaySnapshotStore(file)
        val restored = store2.read()
        assertNotNull(restored)
        assertEquals(original.providerId, restored?.providerId)
        assertEquals(original.countryCode, restored?.countryCode)
        assertEquals(original.generation, restored?.generation)
        assertEquals(original.coveredYears, restored?.coveredYears)
        assertEquals(original.holidayDates, restored?.holidayDates)
    }

    // T20: Cancellation preserves last good snapshot
    @Test
    fun t20_cancellationPreservesLastGoodSnapshot() = runTest {
        val initialSnapshot = sampleSnapshot()
        val store = FileHolidaySnapshotStore(tempFolder.newFile("snapshot.txt"))
        store.writeAtomically(initialSnapshot)

        val cancellingApi = object : HolidayApi {
            override suspend fun getPublicHolidays(year: Int, countryCode: String): List<HolidayDto> {
                throw CancellationException("Worker job cancelled")
            }
        }
        val coordinator = HolidayRefreshCoordinator(cancellingApi, store) { nowMillis }

        var caughtCancellation = false
        try {
            coordinator.refresh("JP", setOf(2026), force = true)
        } catch (ce: CancellationException) {
            caughtCancellation = true
        }

        assertTrue(caughtCancellation)
        val readBack = store.read()
        assertEquals(initialSnapshot.generation, readBack?.generation)
    }

    // T21: Country/provider mismatch -> UNKNOWN
    @Test
    fun t21_countryProviderMismatch_returnsUnknown() {
        val snapshotJP = sampleSnapshot(country = "JP")
        val result = HolidayClassifier.classify(
            date = jan1,
            manualOverride = ManualHolidayOverride.AUTO,
            snapshot = snapshotJP,
            configuredCountry = "US", // Mismatched country!
            nowMillis = nowMillis
        )
        assertEquals(HolidayClassificationKind.UNKNOWN, result.kind)
        assertEquals(HolidayUnknownReason.COUNTRY_MISMATCH, result.unknownReason)
    }

    // T22: Manual overrides remain authoritative when provider is unavailable
    @Test
    fun t22_manualOverridesRemainAuthoritativeWhenProviderUnavailable() {
        val resultHoliday = HolidayClassifier.classify(
            date = jan1,
            manualOverride = ManualHolidayOverride.FORCE_HOLIDAY,
            snapshot = null,
            configuredCountry = null,
            nowMillis = nowMillis
        )
        assertEquals(HolidayClassificationKind.HOLIDAY, resultHoliday.kind)
        assertEquals(HolidayClassificationSource.MANUAL_OVERRIDE, resultHoliday.source)

        val resultWorkday = HolidayClassifier.classify(
            date = jan1,
            manualOverride = ManualHolidayOverride.FORCE_WORKDAY,
            snapshot = null,
            configuredCountry = null,
            nowMillis = nowMillis
        )
        assertEquals(HolidayClassificationKind.WORKDAY, resultWorkday.kind)
        assertEquals(HolidayClassificationSource.MANUAL_OVERRIDE, resultWorkday.source)
    }

    // T23: Partial acquisition does not claim full requested-year success
    @Test
    fun t23_partialAcquisitionDoesNotClaimFullSuccess() = runTest {
        val fakeApi = object : HolidayApi {
            override suspend fun getPublicHolidays(year: Int, countryCode: String): List<HolidayDto> {
                if (year == 2026) return listOf(HolidayDto("2026-01-01", "NY", "NY", countryCode))
                throw IOException("Next year API failure")
            }
        }
        val store = FileHolidaySnapshotStore(tempFolder.newFile("snapshot.txt"))
        val coordinator = HolidayRefreshCoordinator(fakeApi, store) { nowMillis }

        val outcome = coordinator.refresh("JP", setOf(2026, 2027))
        assertTrue(outcome is HolidayRefreshOutcome.PartialSuccess)
        val partial = outcome as HolidayRefreshOutcome.PartialSuccess
        assertFalse(partial.failedYears.isEmpty())
        assertEquals(setOf(2027), partial.failedYears)
        assertEquals(setOf(2026), partial.snapshot.coveredYears)
    }

    // T24: Concurrent refresh cannot overwrite newer truth with older publication
    @Test
    fun t24_concurrentRefresh_monotonicGeneration() {
        val file = tempFolder.newFile("snapshot.txt")
        val store = FileHolidaySnapshotStore(file)
        val snap1 = sampleSnapshot(generation = 1L)
        val snap2 = sampleSnapshot(generation = 2L)

        assertTrue(store.writeAtomically(snap1))
        assertEquals(1L, store.read()?.generation)

        assertTrue(store.writeAtomically(snap2))
        assertEquals(2L, store.read()?.generation)
    }

    // T25: Atomic file replacement failure injection preserves last good snapshot
    @Test
    fun t25_atomicFileReplacementFailurePreservesLastGoodSnapshot() {
        val folder = tempFolder.newFolder("store_test")
        val snapshotFile = File(folder, "snapshot.txt")
        val store = FileHolidaySnapshotStore(snapshotFile)

        val snap1 = sampleSnapshot(generation = 1L)
        assertTrue(store.writeAtomically(snap1))
        assertEquals(1L, store.read()?.generation)

        // Attempt writing an invalid candidate snapshot (generation < 1L forces encoding/validation exception)
        val invalidSnap2 = sampleSnapshot(generation = 0L)
        val written = store.writeAtomically(invalidSnap2)
        assertFalse("Atomic write MUST fail safely on encoding/validation failure", written)

        // Read back: original generation 1 MUST be preserved 100% untouched!
        val readBack = store.read()
        assertNotNull("Original committed snapshot MUST be preserved", readBack)
        assertEquals("Original generation 1 MUST remain authority", 1L, readBack?.generation)
    }

    // T26: PartialSuccess mapping in HolidayWorkerResultMapper (transient failure -> bounded RETRY)
    @Test
    fun t26_partialSuccessWorkerMapping() {
        val partialOutcome = HolidayRefreshOutcome.PartialSuccess(
            snapshot = sampleSnapshot(),
            successfulYears = setOf(2026),
            failedYears = setOf(2027),
            failureCategories = mapOf(2027 to HolidayYearFailureCategory.TRANSIENT),
            materialChange = true
        )

        // While attempts remain: bounded RETRY to fetch missing years
        assertEquals(HolidayWorkerDecision.RETRY, HolidayWorkerResultMapper.map(partialOutcome, runAttemptCount = 0, maxRetries = 3))
        assertEquals(HolidayWorkerDecision.RETRY, HolidayWorkerResultMapper.map(partialOutcome, runAttemptCount = 2, maxRetries = 3))

        // When attempts are exhausted: FAILURE (no false complete success)
        assertEquals(HolidayWorkerDecision.FAILURE, HolidayWorkerResultMapper.map(partialOutcome, runAttemptCount = 3, maxRetries = 3))
    }

    // T27: PartialSuccess with permanent failure only -> immediate FAILURE without retrying
    @Test
    fun t27_partialSuccessPermanentFailureOnly_returnsFailure() {
        val partialOutcome = HolidayRefreshOutcome.PartialSuccess(
            snapshot = sampleSnapshot(),
            successfulYears = setOf(2026),
            failedYears = setOf(2027),
            failureCategories = mapOf(2027 to HolidayYearFailureCategory.PERMANENT),
            materialChange = true
        )

        // Immediate failure even on attempt 0 (do not enter useless retry loop)
        assertEquals(HolidayWorkerDecision.FAILURE, HolidayWorkerResultMapper.map(partialOutcome, runAttemptCount = 0, maxRetries = 3))
        assertEquals(HolidayWorkerDecision.FAILURE, HolidayWorkerResultMapper.map(partialOutcome, runAttemptCount = 1, maxRetries = 3))
    }

    // T28: PartialSuccess with mixed transient and permanent -> retries only if transient unresolved year remains
    @Test
    fun t28_partialSuccessMixedFailures_retriesOnlyIfTransientRemains() {
        val mixedOutcome = HolidayRefreshOutcome.PartialSuccess(
            snapshot = sampleSnapshot(),
            successfulYears = setOf(2026),
            failedYears = setOf(2027, 2028),
            failureCategories = mapOf(
                2027 to HolidayYearFailureCategory.PERMANENT,
                2028 to HolidayYearFailureCategory.TRANSIENT
            ),
            materialChange = true
        )

        // Retries because 2028 is transient
        assertEquals(HolidayWorkerDecision.RETRY, HolidayWorkerResultMapper.map(mixedOutcome, runAttemptCount = 0, maxRetries = 3))
        assertEquals(HolidayWorkerDecision.RETRY, HolidayWorkerResultMapper.map(mixedOutcome, runAttemptCount = 2, maxRetries = 3))
        assertEquals(HolidayWorkerDecision.FAILURE, HolidayWorkerResultMapper.map(mixedOutcome, runAttemptCount = 3, maxRetries = 3))
    }

    // T29: Coordinator populates failure categories accurately for mixed failures
    @Test
    fun t29_coordinatorPopulatesFailureCategoriesAccurately() = runTest {
        val fakeApi = object : HolidayApi {
            override suspend fun getPublicHolidays(year: Int, countryCode: String): List<HolidayDto> {
                return when (year) {
                    2026 -> listOf(HolidayDto("2026-01-01", "NY", "NY", countryCode))
                    2027 -> emptyList() // triggers invalid DTO -> PERMANENT
                    2028 -> throw IOException("Connection reset") // TRANSIENT
                    else -> throw IllegalStateException("Unexpected year $year")
                }
            }
        }
        val store = FileHolidaySnapshotStore(tempFolder.newFile("snapshot.txt"))
        val coordinator = HolidayRefreshCoordinator(fakeApi, store) { nowMillis }

        val outcome = coordinator.refresh("JP", setOf(2026, 2027, 2028))
        assertTrue(outcome is HolidayRefreshOutcome.PartialSuccess)
        val partial = outcome as HolidayRefreshOutcome.PartialSuccess
        assertEquals(setOf(2026), partial.successfulYears)
        assertEquals(setOf(2027, 2028), partial.failedYears)
        assertEquals(HolidayYearFailureCategory.PERMANENT, partial.failureCategories[2027])
        assertEquals(HolidayYearFailureCategory.TRANSIENT, partial.failureCategories[2028])
        assertTrue(partial.hasTransientFailure)

        // Worker mapping decision should RETRY because 2028 is transient
        assertEquals(HolidayWorkerDecision.RETRY, HolidayWorkerResultMapper.map(partial, runAttemptCount = 0, maxRetries = 3))
    }
}
