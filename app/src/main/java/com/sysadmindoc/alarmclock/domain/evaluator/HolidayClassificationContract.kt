package com.sysadmindoc.alarmclock.domain.evaluator

import com.sysadmindoc.alarmclock.data.model.Alarm
import com.sysadmindoc.alarmclock.data.repository.CapturedHolidayAuthorityWindow
import com.sysadmindoc.alarmclock.data.repository.HolidayClassification
import com.sysadmindoc.alarmclock.data.repository.HolidayClassificationKind
import com.sysadmindoc.alarmclock.data.repository.HolidayClassificationSource
import com.sysadmindoc.alarmclock.data.repository.HolidayClassifier
import com.sysadmindoc.alarmclock.data.repository.HolidayProviderSnapshot
import com.sysadmindoc.alarmclock.data.repository.HolidayRepository
import com.sysadmindoc.alarmclock.data.repository.HolidayUnknownReason
import com.sysadmindoc.alarmclock.data.repository.ManualHolidayOverrideSource
import java.time.LocalDate
import java.time.ZoneId

/**
 * Maximum bounded evaluation span (`MAX_CANDIDATE_SEARCH_STEPS` plus maximum UTC-offset
 * calendar-date spread across `UTC-12` .. `UTC+14` for multi-timezone alarm sets).
 */
const val MAX_HOLIDAY_EVALUATION_WINDOW_DAYS: Int =
    ScheduleOccurrenceEvaluator.MAX_CANDIDATE_SEARCH_STEPS + 4

/**
 * Pure, local-only typed Holiday classification contract for Normal Occurrence evaluation
 * (KNO-159 P1 / KNO-31 / KNO-158).
 *
 * Bridges KNO-158's canonical [HolidayClassification] (`HOLIDAY | WORKDAY | UNKNOWN`,
 * manual-override precedence, provider snapshot coverage, and explicit unknown reasons)
 * into deterministic domain occurrence evaluation without inline network I/O and without
 * relying on the legacy boolean `HolidayRepository.isHoliday(date)` adapter.
 */
fun interface LocalHolidayClassifier {
    fun classify(date: LocalDate): HolidayClassification

    companion object {
        /** Fail-safe classifier returning [HolidayClassificationKind.UNKNOWN] for every date. */
        val ALL_UNKNOWN: LocalHolidayClassifier = LocalHolidayClassifier {
            HolidayClassification(
                kind = HolidayClassificationKind.UNKNOWN,
                source = HolidayClassificationSource.NONE,
                unknownReason = HolidayUnknownReason.NO_VALID_SNAPSHOT
            )
        }

        /**
         * Creates a deterministic, zero-I/O [LocalHolidayClassifier] backed by [HolidayClassifier]
         * using a pinned local [HolidayProviderSnapshot], normalized country code, and
         * [ManualHolidayOverrideSource].
         *
         * Preserves KNO-31 / KNO-158 invariants:
         * - Manual override (`FORCE_HOLIDAY` / `FORCE_WORKDAY`) outranks provider and blank country.
         * - Unconfigured/blank country with `ManualHolidayOverride.AUTO` yields `UNKNOWN` (`PROVIDER_NOT_CONFIGURED`).
         * - Country mismatch or uncovered year yields `UNKNOWN` (never `WORKDAY`).
         * - Stale-but-covered snapshot remains usable with `STALE` freshness; reload never manufactures `FRESH`.
         */
        fun fromSnapshot(
            snapshot: HolidayProviderSnapshot?,
            configuredCountry: String?,
            manualOverrideSource: ManualHolidayOverrideSource = ManualHolidayOverrideSource.NONE,
            nowMillis: Long
        ): LocalHolidayClassifier {
            val pinnedCountry = HolidayProviderSnapshot.normalizeCountry(configuredCountry)
            val pinnedSnapshot = if (pinnedCountry == null) null else snapshot
            return LocalHolidayClassifier { date ->
                val manual = manualOverrideSource.overrideFor(date)
                HolidayClassifier.classify(
                    date = date,
                    manualOverride = manual,
                    snapshot = pinnedSnapshot,
                    configuredCountry = pinnedCountry,
                    nowMillis = nowMillis
                )
            }
        }
    }
}

/** Adapts a coherent [CapturedHolidayAuthorityWindow] directly to [LocalHolidayClassifier]. */
fun CapturedHolidayAuthorityWindow.asLocalHolidayClassifier(): LocalHolidayClassifier =
    LocalHolidayClassifier { date -> classify(date) }

/** Maps canonical KNO-158 [HolidayClassificationKind] to evaluator [HolidayState]. */
fun HolidayClassificationKind.toHolidayState(): HolidayState = when (this) {
    HolidayClassificationKind.HOLIDAY -> HolidayState.IS_HOLIDAY
    HolidayClassificationKind.WORKDAY -> HolidayState.NOT_HOLIDAY
    HolidayClassificationKind.UNKNOWN -> HolidayState.UNKNOWN
}

/** Maps canonical KNO-158 [HolidayClassification] to evaluator [HolidayState]. */
fun HolidayClassification.toHolidayState(): HolidayState = kind.toHolidayState()

/**
 * Converts a canonical [HolidayClassification] into a domain [HolidaySnapshot] without
 * manufacturing synthetic holiday names or fake cache freshness timestamps.
 */
fun HolidayClassification.toHolidaySnapshot(checkedAtEpochMs: Long = 0L): HolidaySnapshot =
    HolidaySnapshot(
        state = toHolidayState(),
        holidayName = "",
        checkedAtEpochMs = checkedAtEpochMs
    )

/** Adapts a typed [LocalHolidayClassifier] to [ScheduleOccurrenceEvaluator]'s snapshot provider seam. */
fun LocalHolidayClassifier.asHolidaySnapshotProvider(): (LocalDate) -> HolidaySnapshot = { date ->
    classify(date).toHolidaySnapshot()
}

/**
 * Captures a coherent, immutable [LocalHolidayClassifier] window for
 * `[startDate, startDate + boundedDays - 1]` via a single [HolidayRepository.captureAuthorityWindow]
 * read (one settings read, one immutable provider snapshot read, one clock sample, and one
 * synchronous manual-override pass; never `HolidayRepository.isHoliday` and never network I/O).
 *
 * Concurrent provider refreshes or country setting changes cannot mix snapshot generations or
 * country identities inside the captured window. Dates outside the window fail safe to `UNKNOWN`.
 */
suspend fun HolidayRepository.captureWindowClassifier(
    startDate: LocalDate,
    horizonDays: Int = ScheduleOccurrenceEvaluator.MAX_CANDIDATE_SEARCH_STEPS
): LocalHolidayClassifier {
    val boundedDays = horizonDays.coerceIn(1, MAX_HOLIDAY_EVALUATION_WINDOW_DAYS)
    val endDateInclusive = startDate.plusDays(boundedDays - 1L)
    return captureAuthorityWindow(startDate, endDateInclusive).asLocalHolidayClassifier()
}

/**
 * Captures a coherent [CapturedHolidayAuthorityWindow] aligned to the exact date/zone union
 * inspected by [ScheduleOccurrenceEvaluator] and [HolidayMaterialChangeDetector] for [alarms]
 * at [evaluationEpochMs] in [evaluationZoneId].
 */
suspend fun HolidayRepository.captureAuthorityWindowForEvaluation(
    evaluationEpochMs: Long,
    evaluationZoneId: ZoneId,
    alarms: List<Alarm> = emptyList(),
    horizonDays: Int = ScheduleOccurrenceEvaluator.MAX_CANDIDATE_SEARCH_STEPS
): CapturedHolidayAuthorityWindow {
    val range = HolidayMaterialChangeDetector.resolveEvaluationDateRange(
        evaluationEpochMs = evaluationEpochMs,
        evaluationZoneId = evaluationZoneId,
        alarms = alarms,
        horizonDays = horizonDays
    )
    return captureAuthorityWindow(range.startDate, range.endDateInclusive)
}
