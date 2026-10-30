package com.sysadmindoc.alarmclock.data.repository

import android.content.Context
import com.sysadmindoc.alarmclock.data.preferences.PreferencesManager
import com.sysadmindoc.alarmclock.data.remote.HolidayApi
import com.sysadmindoc.alarmclock.domain.evaluator.HolidaySnapshot
import com.sysadmindoc.alarmclock.domain.evaluator.HolidayState
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

/**
 * KNO-158 / KNO-31: Holiday truth and provider cache repository.
 *
 * Responsibilities:
 * - Durable, atomic provider snapshot persistence via [HolidaySnapshotStore].
 * - Deterministic, local-only Holiday classification without inline network I/O.
 * - Multi-year refresh coordinator with explicit [HolidayRefreshOutcome].
 * - Manual override integration point with strict priority over provider truth.
 * - Fail-safe legacy boolean adapter [isHoliday] that preserves safe Base schedule.
 */
@Singleton
class HolidayRepository internal constructor(
    private val context: Context,
    private val holidayApi: HolidayApi,
    private val preferencesManager: PreferencesManager,
    private val manualOverrideSource: ManualHolidayOverrideSource
) {
    /** Main Hilt injection constructor defaulting to [ManualHolidayOverrideSource.NONE]. */
    @Inject
    constructor(
        @ApplicationContext context: Context,
        holidayApi: HolidayApi,
        preferencesManager: PreferencesManager
    ) : this(context, holidayApi, preferencesManager, ManualHolidayOverrideSource.NONE)

    // Durable atomic snapshot store
    private val snapshotFile get() = File(context.filesDir, "holiday_snapshot_v1.txt")
    private val store: HolidaySnapshotStore by lazy { FileHolidaySnapshotStore(snapshotFile) }
    private val coordinator: HolidayRefreshCoordinator by lazy { HolidayRefreshCoordinator(holidayApi, store) }
    private val mutex = Mutex()

    // In-memory cache snapshot guarded by volatile reference
    @Volatile private var memorySnapshot: HolidayProviderSnapshot? = null

    init {
        // Safe preload of durable snapshot
        memorySnapshot = try {
            store.read()
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Canonical Holiday classification (KNO-31 / KNO-158).
     *
     * Pure local evaluation; NEVER accesses network inline.
     * Evaluates manual override first, then durable provider snapshot.
     */
    suspend fun classify(date: LocalDate): HolidayClassification {
        val settings = preferencesManager.getCurrentSettings()
        if (!settings.holidayAutoSkipEnabled || settings.holidayCountryCode.isBlank()) {
            // Even if provider is disabled, manual override remains authoritative
            val manual = manualOverrideSource.overrideFor(date)
            return HolidayClassifier.classify(
                date = date,
                manualOverride = manual,
                snapshot = null,
                configuredCountry = null,
                nowMillis = System.currentTimeMillis()
            )
        }

        val manual = manualOverrideSource.overrideFor(date)
        val snapshot = getOrLoadSnapshot()
        return HolidayClassifier.classify(
            date = date,
            manualOverride = manual,
            snapshot = snapshot,
            configuredCountry = settings.holidayCountryCode,
            nowMillis = System.currentTimeMillis()
        )
    }

    /**
     * Bridges classification to [HolidaySnapshot] for domain evaluators.
     */
    suspend fun getHolidaySnapshot(date: LocalDate): HolidaySnapshot {
        val classification = classify(date)
        val state = when (classification.kind) {
            HolidayClassificationKind.HOLIDAY -> HolidayState.IS_HOLIDAY
            HolidayClassificationKind.WORKDAY -> HolidayState.NOT_HOLIDAY
            HolidayClassificationKind.UNKNOWN -> HolidayState.UNKNOWN
        }
        return HolidaySnapshot(
            state = state,
            holidayName = if (state == HolidayState.IS_HOLIDAY) "Holiday" else "",
            checkedAtEpochMs = System.currentTimeMillis()
        )
    }

    /**
     * Legacy boolean query used by existing AlarmScheduler / ScheduleAuthorityBootstrapAdapter.
     *
     * INVARIANT (KNO-31): UNKNOWN must never be interpreted as true.
     * Only confirmed HOLIDAY returns true.
     */
    suspend fun isHoliday(date: LocalDate): Boolean {
        val classification = classify(date)
        return classification.kind == HolidayClassificationKind.HOLIDAY
    }

    /**
     * Refreshes holiday data for the current year (and next year in December).
     * Returns explicit [HolidayRefreshOutcome] and never swallows [CancellationException].
     */
    suspend fun refreshWithOutcome(force: Boolean = false): HolidayRefreshOutcome {
        val settings = preferencesManager.getCurrentSettings()
        val country = settings.holidayCountryCode
        if (country.isBlank()) return HolidayRefreshOutcome.NotConfigured

        val currentYear = LocalDate.now().year
        val requestedYears = if (LocalDate.now().monthValue == 12) {
            setOf(currentYear, currentYear + 1)
        } else {
            setOf(currentYear)
        }

        return mutex.withLock {
            val outcome = coordinator.refresh(country, requestedYears, force)
            when (outcome) {
                is HolidayRefreshOutcome.Success -> memorySnapshot = outcome.snapshot
                is HolidayRefreshOutcome.PartialSuccess -> memorySnapshot = outcome.snapshot
                is HolidayRefreshOutcome.Unchanged -> memorySnapshot = outcome.snapshot
                else -> Unit
            }
            outcome
        }
    }

    /** Legacy refresh method called by HolidaySyncWorker. */
    suspend fun refresh() {
        when (val outcome = refreshWithOutcome()) {
            is HolidayRefreshOutcome.Success,
            is HolidayRefreshOutcome.PartialSuccess,
            is HolidayRefreshOutcome.Unchanged,
            is HolidayRefreshOutcome.NotConfigured -> Unit
            is HolidayRefreshOutcome.TransientFailure -> throw java.io.IOException(outcome.message, outcome.cause)
            is HolidayRefreshOutcome.PermanentFailure -> throw IllegalArgumentException(outcome.message, outcome.cause)
            is HolidayRefreshOutcome.StorageFailure -> throw java.io.IOException(outcome.message, outcome.cause)
        }
    }

    private fun getOrLoadSnapshot(): HolidayProviderSnapshot? {
        val current = memorySnapshot
        if (current != null && current.isValid()) return current
        val loaded = store.read()
        memorySnapshot = loaded
        return loaded
    }
}
