package com.sysadmindoc.alarmclock.domain.evaluator

import android.content.Context
import com.sysadmindoc.alarmclock.data.local.entity.ScheduleAuthority
import com.sysadmindoc.alarmclock.data.local.entity.ScheduleException
import com.sysadmindoc.alarmclock.data.model.Alarm
import com.sysadmindoc.alarmclock.data.model.AlarmOccurrenceId
import com.sysadmindoc.alarmclock.data.preferences.AppSettings
import com.sysadmindoc.alarmclock.data.preferences.PreferencesManager
import com.sysadmindoc.alarmclock.data.remote.HolidayApi
import com.sysadmindoc.alarmclock.data.remote.HolidayDto
import com.sysadmindoc.alarmclock.data.repository.FileHolidaySnapshotStore
import com.sysadmindoc.alarmclock.data.repository.HolidayClassificationKind
import com.sysadmindoc.alarmclock.data.repository.HolidayClassificationSource
import com.sysadmindoc.alarmclock.data.repository.HolidayClassifier
import com.sysadmindoc.alarmclock.data.repository.HolidayProviderFreshness
import com.sysadmindoc.alarmclock.data.repository.HolidayProviderSnapshot
import com.sysadmindoc.alarmclock.data.repository.HolidayRepository
import com.sysadmindoc.alarmclock.data.repository.HolidayUnknownReason
import com.sysadmindoc.alarmclock.data.repository.HolidayRefreshOutcome
import com.sysadmindoc.alarmclock.data.repository.HolidayYearCoverage
import com.sysadmindoc.alarmclock.data.repository.ManualHolidayOverride
import com.sysadmindoc.alarmclock.data.repository.ManualHolidayOverrideSource
import com.sysadmindoc.alarmclock.data.repository.NAGER_DATE_PROVIDER_ID
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * Deterministic unit tests for KNO-159 P1 Holiday Occurrence Foundation.
 *
 * Covers T01 - T25 from the execution contract:
 * - Zero live HTTP (forbidden HolidayApi throws AssertionError if ever invoked).
 * - Fixed LocalDate / ZoneId / epoch instants.
 * - Canonical KNO-158 typed classification (`HolidayClassifier` + `HolidayRepository.classify`).
 */
class Kno159HolidayOccurrenceFoundationTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val evaluator = ScheduleOccurrenceEvaluator()
    private val materialDetector = HolidayMaterialChangeDetector(evaluator)
    private val tokyoZone = ZoneId.of("Asia/Tokyo")
    private val nyZone = ZoneId.of("America/New_York")

    // Fixed reference dates in 2026:
    // 2026-01-01 = Thursday (New Year's Day)
    // 2026-01-02 = Friday
    // 2026-01-12 = Monday (Coming of Age Day in JP)
    // 2026-01-19 = Monday (Workday)
    // 2026-02-11 = Wednesday (National Foundation Day in JP)
    // 2026-02-14 = Saturday
    // 2026-02-23 = Monday (Emperor's Birthday in JP)
    private val thuJan1 = LocalDate.of(2026, 1, 1)
    private val friJan2 = LocalDate.of(2026, 1, 2)
    private val monJan12 = LocalDate.of(2026, 1, 12)
    private val monJan19 = LocalDate.of(2026, 1, 19)
    private val wedFeb11 = LocalDate.of(2026, 2, 11)
    private val satFeb14 = LocalDate.of(2026, 2, 14)
    private val monFeb23 = LocalDate.of(2026, 2, 23)

    private val fetchedFreshEpochMs = epochMillis(LocalDate.of(2026, 1, 1), LocalTime.of(0, 0), tokyoZone)
    private val nowJan1At0600Ms = epochMillis(thuJan1, LocalTime.of(6, 0), tokyoZone)

    private fun epochMillis(date: LocalDate, time: LocalTime, zone: ZoneId): Long =
        date.atTime(time).atZone(zone).toInstant().toEpochMilli()

    private fun providerSnapshot(
        country: String = "JP",
        generation: Long = 1L,
        fetchedAtMillis: Long = fetchedFreshEpochMs,
        year: Int = 2026,
        holidayDates: Set<LocalDate> = setOf(thuJan1, monJan12, wedFeb11, monFeb23)
    ): HolidayProviderSnapshot = HolidayProviderSnapshot(
        providerId = NAGER_DATE_PROVIDER_ID,
        countryCode = country,
        generation = generation,
        years = mapOf(year to HolidayYearCoverage(year, fetchedAtMillis, holidayDates))
    )

    private fun repeatingAlarm(
        id: Long = 100L,
        hour: Int = 7,
        minute: Int = 30,
        repeatDays: Set<DayOfWeek>,
        skipOnHolidays: Boolean = false,
        generation: Int = 2,
        revision: Int = 1,
        nextTriggerTime: Long = 0L
    ): Alarm = Alarm(
        id = id,
        hour = hour,
        minute = minute,
        isEnabled = true,
        scheduleKind = Alarm.SCHEDULE_KIND_REPEATING,
        repeatDays = repeatDays,
        skipOnHolidays = skipOnHolidays,
        generation = generation,
        revision = revision,
        nextTriggerTime = nextTriggerTime
    )

    private fun createOfflineRepository(
        filesDir: File,
        settingsProvider: () -> AppSettings,
        manualOverrideSource: ManualHolidayOverrideSource = ManualHolidayOverrideSource.NONE,
        clockMillis: () -> Long = { nowJan1At0600Ms }
    ): HolidayRepository {
        val context = mockk<Context>()
        every { context.filesDir } returns filesDir
        val preferencesManager = mockk<PreferencesManager>()
        coEvery { preferencesManager.getCurrentSettings() } answers { settingsProvider() }
        val forbiddenNetworkApi = object : HolidayApi {
            override suspend fun getPublicHolidays(year: Int, countryCode: String): List<HolidayDto> {
                throw AssertionError("Network I/O is strictly forbidden during schedule evaluation")
            }
        }
        return HolidayRepository(
            context = context,
            holidayApi = forbiddenNetworkApi,
            preferencesManager = preferencesManager,
            manualOverrideSource = manualOverrideSource,
            clockMillis = clockMillis
        )
    }

    // =========================================================================
    // T01 - T03: FOLLOW_BASE Policy Matrix (Base YES & NO x HOLIDAY/WORKDAY/UNKNOWN)
    // =========================================================================

    @Test
    fun t01_followBase_withHoliday_triggersOnlyOnBaseWeekday() {
        val classifier = LocalHolidayClassifier.fromSnapshot(
            snapshot = providerSnapshot(holidayDates = setOf(thuJan1)),
            configuredCountry = "JP",
            nowMillis = nowJan1At0600Ms
        )

        // Row 1: FOLLOW_BASE + Base=YES (Thursday) + HOLIDAY -> YES
        val baseThuAlarm = repeatingAlarm(repeatDays = setOf(DayOfWeek.THURSDAY))
        val yesResult = evaluator.evaluateWithHolidayClassifier(
            alarm = baseThuAlarm,
            fromEpochMs = nowJan1At0600Ms,
            holidayClassifier = classifier,
            holidayPolicyOverride = HolidayPolicy.FOLLOW_BASE,
            evaluationZoneId = tokyoZone
        )
        assertNotNull(yesResult)
        assertEquals(OccurrenceDecision.NORMAL_TRIGGER, yesResult!!.decision)
        assertEquals(epochMillis(thuJan1, LocalTime.of(7, 30), tokyoZone), yesResult.targetTriggerTimeEpochMs)
        assertEquals(HolidayState.IS_HOLIDAY, yesResult.holidayState)
        assertEquals(HolidayPolicy.FOLLOW_BASE, yesResult.holidayPolicy)

        // Row 4: FOLLOW_BASE + Base=NO (Friday-only alarm on Thursday Holiday) -> NO on Thu, advances to Fri
        val baseFriAlarm = repeatingAlarm(repeatDays = setOf(DayOfWeek.FRIDAY))
        val noOnThuResult = evaluator.evaluateWithHolidayClassifier(
            alarm = baseFriAlarm,
            fromEpochMs = nowJan1At0600Ms,
            holidayClassifier = classifier,
            holidayPolicyOverride = HolidayPolicy.FOLLOW_BASE,
            evaluationZoneId = tokyoZone
        )
        assertNotNull(noOnThuResult)
        assertEquals(epochMillis(friJan2, LocalTime.of(7, 30), tokyoZone), noOnThuResult!!.targetTriggerTimeEpochMs)
    }

    @Test
    fun t02_followBase_withWorkday_triggersOnlyOnBaseWeekday() {
        // friJan2 is WORKDAY in covered 2026 JP snapshot
        val classifier = LocalHolidayClassifier.fromSnapshot(
            snapshot = providerSnapshot(holidayDates = setOf(thuJan1)),
            configuredCountry = "JP",
            nowMillis = nowJan1At0600Ms
        )
        val fromFri0600Ms = epochMillis(friJan2, LocalTime.of(6, 0), tokyoZone)

        // Row 2: FOLLOW_BASE + Base=YES (Friday) + WORKDAY -> YES
        val baseFriAlarm = repeatingAlarm(repeatDays = setOf(DayOfWeek.FRIDAY))
        val yesResult = evaluator.evaluateWithHolidayClassifier(
            alarm = baseFriAlarm,
            fromEpochMs = fromFri0600Ms,
            holidayClassifier = classifier,
            holidayPolicyOverride = HolidayPolicy.FOLLOW_BASE,
            evaluationZoneId = tokyoZone
        )
        assertNotNull(yesResult)
        assertEquals(OccurrenceDecision.NORMAL_TRIGGER, yesResult!!.decision)
        assertEquals(epochMillis(friJan2, LocalTime.of(7, 30), tokyoZone), yesResult.targetTriggerTimeEpochMs)
        assertEquals(HolidayState.NOT_HOLIDAY, yesResult.holidayState)

        // Row 5: FOLLOW_BASE + Base=NO (Monday-only alarm on Friday Workday) -> NO on Fri, advances to Mon Jan 5
        val baseMonAlarm = repeatingAlarm(repeatDays = setOf(DayOfWeek.MONDAY))
        val noOnFriResult = evaluator.evaluateWithHolidayClassifier(
            alarm = baseMonAlarm,
            fromEpochMs = fromFri0600Ms,
            holidayClassifier = classifier,
            holidayPolicyOverride = HolidayPolicy.FOLLOW_BASE,
            evaluationZoneId = tokyoZone
        )
        assertNotNull(noOnFriResult)
        assertEquals(
            epochMillis(LocalDate.of(2026, 1, 5), LocalTime.of(7, 30), tokyoZone),
            noOnFriResult!!.targetTriggerTimeEpochMs
        )
    }

    @Test
    fun t03_followBase_withUnknown_triggersOnlyOnBaseWeekday() {
        val classifier = LocalHolidayClassifier.ALL_UNKNOWN

        // Row 3: FOLLOW_BASE + Base=YES + UNKNOWN -> YES
        val baseThuAlarm = repeatingAlarm(repeatDays = setOf(DayOfWeek.THURSDAY))
        val yesResult = evaluator.evaluateWithHolidayClassifier(
            alarm = baseThuAlarm,
            fromEpochMs = nowJan1At0600Ms,
            holidayClassifier = classifier,
            holidayPolicyOverride = HolidayPolicy.FOLLOW_BASE,
            evaluationZoneId = tokyoZone
        )
        assertNotNull(yesResult)
        assertEquals(OccurrenceDecision.NORMAL_TRIGGER, yesResult!!.decision)
        assertEquals(epochMillis(thuJan1, LocalTime.of(7, 30), tokyoZone), yesResult.targetTriggerTimeEpochMs)
        assertEquals(HolidayState.UNKNOWN, yesResult.holidayState)

        // Row 6: FOLLOW_BASE + Base=NO + UNKNOWN -> NO on Thu, advances to Fri
        val baseFriAlarm = repeatingAlarm(repeatDays = setOf(DayOfWeek.FRIDAY))
        val noOnThuResult = evaluator.evaluateWithHolidayClassifier(
            alarm = baseFriAlarm,
            fromEpochMs = nowJan1At0600Ms,
            holidayClassifier = classifier,
            holidayPolicyOverride = HolidayPolicy.FOLLOW_BASE,
            evaluationZoneId = tokyoZone
        )
        assertNotNull(noOnThuResult)
        assertEquals(epochMillis(friJan2, LocalTime.of(7, 30), tokyoZone), noOnThuResult!!.targetTriggerTimeEpochMs)
    }

    // =========================================================================
    // T04 - T06: EXCLUDE Policy Matrix (Base YES & NO x HOLIDAY/WORKDAY/UNKNOWN)
    // =========================================================================

    @Test
    fun t04_exclude_withHoliday_suppressesBaseWeekdayAndAdvances() {
        val classifier = LocalHolidayClassifier.fromSnapshot(
            snapshot = providerSnapshot(holidayDates = setOf(thuJan1)),
            configuredCountry = "JP",
            nowMillis = nowJan1At0600Ms
        )

        // Row 7: EXCLUDE + Base=YES (Thu & Fri) + Thu=HOLIDAY -> suppresses Thu Jan 1, triggers Fri Jan 2
        val thuFriAlarm = repeatingAlarm(
            repeatDays = setOf(DayOfWeek.THURSDAY, DayOfWeek.FRIDAY),
            skipOnHolidays = true
        )
        val result = evaluator.evaluateWithHolidayClassifier(
            alarm = thuFriAlarm,
            fromEpochMs = nowJan1At0600Ms,
            holidayClassifier = classifier,
            evaluationZoneId = tokyoZone
        )
        assertNotNull(result)
        assertEquals(OccurrenceDecision.NORMAL_TRIGGER, result!!.decision)
        assertEquals(epochMillis(friJan2, LocalTime.of(7, 30), tokyoZone), result.targetTriggerTimeEpochMs)
        assertEquals(HolidayPolicy.EXCLUDE, result.holidayPolicy)

        // Row 10: EXCLUDE + Base=NO (Fri-only) + Thu=HOLIDAY -> NO on Thu, triggers Fri Jan 2
        val friOnlyAlarm = repeatingAlarm(repeatDays = setOf(DayOfWeek.FRIDAY), skipOnHolidays = true)
        val nonBaseResult = evaluator.evaluateWithHolidayClassifier(
            alarm = friOnlyAlarm,
            fromEpochMs = nowJan1At0600Ms,
            holidayClassifier = classifier,
            evaluationZoneId = tokyoZone
        )
        assertNotNull(nonBaseResult)
        assertEquals(epochMillis(friJan2, LocalTime.of(7, 30), tokyoZone), nonBaseResult!!.targetTriggerTimeEpochMs)
    }

    @Test
    fun t05_exclude_withWorkday_preservesBaseWeekdayOnly() {
        val classifier = LocalHolidayClassifier.fromSnapshot(
            snapshot = providerSnapshot(holidayDates = setOf(thuJan1)),
            configuredCountry = "JP",
            nowMillis = nowJan1At0600Ms
        )
        val fromFri0600Ms = epochMillis(friJan2, LocalTime.of(6, 0), tokyoZone)

        // Row 8: EXCLUDE + Base=YES (Fri) + Fri=WORKDAY -> YES
        val friAlarm = repeatingAlarm(repeatDays = setOf(DayOfWeek.FRIDAY), skipOnHolidays = true)
        val yesResult = evaluator.evaluateWithHolidayClassifier(
            alarm = friAlarm,
            fromEpochMs = fromFri0600Ms,
            holidayClassifier = classifier,
            evaluationZoneId = tokyoZone
        )
        assertNotNull(yesResult)
        assertEquals(OccurrenceDecision.NORMAL_TRIGGER, yesResult!!.decision)
        assertEquals(epochMillis(friJan2, LocalTime.of(7, 30), tokyoZone), yesResult.targetTriggerTimeEpochMs)
        assertEquals(HolidayState.NOT_HOLIDAY, yesResult.holidayState)

        // Row 11: EXCLUDE + Base=NO (Sat) + Fri=WORKDAY -> NO on Fri, advances to Sat Jan 3
        val satAlarm = repeatingAlarm(repeatDays = setOf(DayOfWeek.SATURDAY), skipOnHolidays = true)
        val noOnFriResult = evaluator.evaluateWithHolidayClassifier(
            alarm = satAlarm,
            fromEpochMs = fromFri0600Ms,
            holidayClassifier = classifier,
            evaluationZoneId = tokyoZone
        )
        assertNotNull(noOnFriResult)
        assertEquals(
            epochMillis(LocalDate.of(2026, 1, 3), LocalTime.of(7, 30), tokyoZone),
            noOnFriResult!!.targetTriggerTimeEpochMs
        )
    }

    @Test
    fun t06_exclude_withUnknown_preservesBaseWeekdaySafeFallback() {
        val classifier = LocalHolidayClassifier.ALL_UNKNOWN

        // Row 9: EXCLUDE + Base=YES (Thu) + UNKNOWN -> YES (safe fallback preserves Base!)
        val thuAlarm = repeatingAlarm(repeatDays = setOf(DayOfWeek.THURSDAY), skipOnHolidays = true)
        val yesResult = evaluator.evaluateWithHolidayClassifier(
            alarm = thuAlarm,
            fromEpochMs = nowJan1At0600Ms,
            holidayClassifier = classifier,
            evaluationZoneId = tokyoZone
        )
        assertNotNull(yesResult)
        assertEquals(OccurrenceDecision.NORMAL_TRIGGER, yesResult!!.decision)
        assertEquals(epochMillis(thuJan1, LocalTime.of(7, 30), tokyoZone), yesResult.targetTriggerTimeEpochMs)
        assertEquals(HolidayState.UNKNOWN, yesResult.holidayState)

        // Row 12: EXCLUDE + Base=NO (Fri) + Thu=UNKNOWN -> NO on Thu, triggers Fri Jan 2
        val friAlarm = repeatingAlarm(repeatDays = setOf(DayOfWeek.FRIDAY), skipOnHolidays = true)
        val noOnThuResult = evaluator.evaluateWithHolidayClassifier(
            alarm = friAlarm,
            fromEpochMs = nowJan1At0600Ms,
            holidayClassifier = classifier,
            evaluationZoneId = tokyoZone
        )
        assertNotNull(noOnThuResult)
        assertEquals(epochMillis(friJan2, LocalTime.of(7, 30), tokyoZone), noOnThuResult!!.targetTriggerTimeEpochMs)
    }

    // =========================================================================
    // T07 - T12: INCLUDE Policy Matrix (Base YES & NO x HOLIDAY/WORKDAY/UNKNOWN)
    // =========================================================================

    @Test
    fun t07_include_baseWeekdayAndHoliday_triggersOccurrence() {
        // Row 13: INCLUDE + Base=YES (Monday) + monJan12=HOLIDAY -> YES
        val classifier = LocalHolidayClassifier.fromSnapshot(
            snapshot = providerSnapshot(holidayDates = setOf(monJan12)),
            configuredCountry = "JP",
            nowMillis = nowJan1At0600Ms
        )
        val fromMon0600Ms = epochMillis(monJan12, LocalTime.of(6, 0), tokyoZone)
        val monAlarm = repeatingAlarm(repeatDays = setOf(DayOfWeek.MONDAY))

        val result = evaluator.evaluateWithHolidayClassifier(
            alarm = monAlarm,
            fromEpochMs = fromMon0600Ms,
            holidayClassifier = classifier,
            holidayPolicyOverride = HolidayPolicy.INCLUDE,
            evaluationZoneId = tokyoZone
        )
        assertNotNull(result)
        assertEquals(OccurrenceDecision.NORMAL_TRIGGER, result!!.decision)
        assertEquals(epochMillis(monJan12, LocalTime.of(7, 30), tokyoZone), result.targetTriggerTimeEpochMs)
        assertEquals(HolidayState.IS_HOLIDAY, result.holidayState)
        assertEquals(HolidayPolicy.INCLUDE, result.holidayPolicy)
    }

    @Test
    fun t08_include_baseWeekdayAndWorkday_triggersOccurrence() {
        // Row 14: INCLUDE + Base=YES (Monday) + monJan19=WORKDAY -> YES
        val classifier = LocalHolidayClassifier.fromSnapshot(
            snapshot = providerSnapshot(holidayDates = setOf(monJan12)),
            configuredCountry = "JP",
            nowMillis = nowJan1At0600Ms
        )
        val fromMon19At0600Ms = epochMillis(monJan19, LocalTime.of(6, 0), tokyoZone)
        val monAlarm = repeatingAlarm(repeatDays = setOf(DayOfWeek.MONDAY))

        val result = evaluator.evaluateWithHolidayClassifier(
            alarm = monAlarm,
            fromEpochMs = fromMon19At0600Ms,
            holidayClassifier = classifier,
            holidayPolicyOverride = HolidayPolicy.INCLUDE,
            evaluationZoneId = tokyoZone
        )
        assertNotNull(result)
        assertEquals(OccurrenceDecision.NORMAL_TRIGGER, result!!.decision)
        assertEquals(epochMillis(monJan19, LocalTime.of(7, 30), tokyoZone), result.targetTriggerTimeEpochMs)
        assertEquals(HolidayState.NOT_HOLIDAY, result.holidayState)
    }

    @Test
    fun t09_include_baseWeekdayAndUnknown_preservesBaseWeekday() {
        // Row 15: INCLUDE + Base=YES (Monday) + UNKNOWN -> YES
        val fromMon19At0600Ms = epochMillis(monJan19, LocalTime.of(6, 0), tokyoZone)
        val monAlarm = repeatingAlarm(repeatDays = setOf(DayOfWeek.MONDAY))

        val result = evaluator.evaluateWithHolidayClassifier(
            alarm = monAlarm,
            fromEpochMs = fromMon19At0600Ms,
            holidayClassifier = LocalHolidayClassifier.ALL_UNKNOWN,
            holidayPolicyOverride = HolidayPolicy.INCLUDE,
            evaluationZoneId = tokyoZone
        )
        assertNotNull(result)
        assertEquals(OccurrenceDecision.NORMAL_TRIGGER, result!!.decision)
        assertEquals(epochMillis(monJan19, LocalTime.of(7, 30), tokyoZone), result.targetTriggerTimeEpochMs)
        assertEquals(HolidayState.UNKNOWN, result.holidayState)
    }

    @Test
    fun t10_include_nonBaseHoliday_synthesizesHolidayOccurrence() {
        // Row 16: INCLUDE + Base=NO (Sat/Sun weekend alarm) + monJan12=HOLIDAY -> YES on monJan12
        val classifier = LocalHolidayClassifier.fromSnapshot(
            snapshot = providerSnapshot(holidayDates = setOf(monJan12)),
            configuredCountry = "JP",
            nowMillis = nowJan1At0600Ms
        )
        // Evaluate on Sunday Jan 11 at 10:00 (after Sunday 07:30 has passed)
        val fromSun1000Ms = epochMillis(LocalDate.of(2026, 1, 11), LocalTime.of(10, 0), tokyoZone)
        val weekendAlarm = repeatingAlarm(repeatDays = setOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY))

        val result = evaluator.evaluateWithHolidayClassifier(
            alarm = weekendAlarm,
            fromEpochMs = fromSun1000Ms,
            holidayClassifier = classifier,
            holidayPolicyOverride = HolidayPolicy.INCLUDE,
            evaluationZoneId = tokyoZone
        )
        assertNotNull(result)
        assertEquals(OccurrenceDecision.NORMAL_TRIGGER, result!!.decision)
        assertEquals(epochMillis(monJan12, LocalTime.of(7, 30), tokyoZone), result.targetTriggerTimeEpochMs)
        assertEquals(HolidayState.IS_HOLIDAY, result.holidayState)
    }

    @Test
    fun t11_include_nonBaseWorkday_doesNotSynthesizeOccurrence() {
        // Row 17: INCLUDE + Base=NO (Sat/Sun) + monJan19..friJan23=WORKDAY -> advances to Sat Jan 24
        val classifier = LocalHolidayClassifier.fromSnapshot(
            snapshot = providerSnapshot(holidayDates = setOf(thuJan1)),
            configuredCountry = "JP",
            nowMillis = nowJan1At0600Ms
        )
        val fromMon19At0600Ms = epochMillis(monJan19, LocalTime.of(6, 0), tokyoZone)
        val weekendAlarm = repeatingAlarm(repeatDays = setOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY))

        val result = evaluator.evaluateWithHolidayClassifier(
            alarm = weekendAlarm,
            fromEpochMs = fromMon19At0600Ms,
            holidayClassifier = classifier,
            holidayPolicyOverride = HolidayPolicy.INCLUDE,
            evaluationZoneId = tokyoZone
        )
        assertNotNull(result)
        assertEquals(OccurrenceDecision.NORMAL_TRIGGER, result!!.decision)
        assertEquals(
            epochMillis(LocalDate.of(2026, 1, 24), LocalTime.of(7, 30), tokyoZone),
            result.targetTriggerTimeEpochMs
        )
    }

    @Test
    fun t12_include_nonBaseUnknown_doesNotSynthesizeOccurrence() {
        // Row 18: INCLUDE + Base=NO (Sat/Sun) + UNKNOWN -> does NOT synthesize weekday; advances to Sat Jan 24
        val fromMon19At0600Ms = epochMillis(monJan19, LocalTime.of(6, 0), tokyoZone)
        val weekendAlarm = repeatingAlarm(repeatDays = setOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY))

        val result = evaluator.evaluateWithHolidayClassifier(
            alarm = weekendAlarm,
            fromEpochMs = fromMon19At0600Ms,
            holidayClassifier = LocalHolidayClassifier.ALL_UNKNOWN,
            holidayPolicyOverride = HolidayPolicy.INCLUDE,
            evaluationZoneId = tokyoZone
        )
        assertNotNull(result)
        assertEquals(OccurrenceDecision.NORMAL_TRIGGER, result!!.decision)
        assertEquals(
            epochMillis(LocalDate.of(2026, 1, 24), LocalTime.of(7, 30), tokyoZone),
            result.targetTriggerTimeEpochMs
        )
        assertEquals(HolidayState.UNKNOWN, result.holidayState)
    }

    // =========================================================================
    // T13: Repeating Zero Weekdays + INCLUDE (Holiday-Only Repeating)
    // =========================================================================

    @Test
    fun t13_holidayOnlyRepeating_zeroWeekdaysWithInclude_validScheduleAndBoundedHorizon() {
        val holidayOnlyAlarm = repeatingAlarm(
            id = 113L,
            repeatDays = emptySet()
        )

        // 1. Confirmed Holiday in horizon -> generates one Normal Occurrence on monJan12
        val fromFriJan2At0800Ms = epochMillis(friJan2, LocalTime.of(8, 0), tokyoZone)
        val withHolidayClassifier = LocalHolidayClassifier.fromSnapshot(
            snapshot = providerSnapshot(holidayDates = setOf(thuJan1, monJan12)),
            configuredCountry = "JP",
            nowMillis = fromFriJan2At0800Ms
        )
        val hitResult = evaluator.evaluateWithHolidayClassifier(
            alarm = holidayOnlyAlarm,
            fromEpochMs = fromFriJan2At0800Ms,
            holidayClassifier = withHolidayClassifier,
            holidayPolicyOverride = HolidayPolicy.INCLUDE,
            evaluationZoneId = tokyoZone
        )
        assertNotNull(hitResult)
        assertEquals(OccurrenceDecision.NORMAL_TRIGGER, hitResult!!.decision)
        assertEquals(epochMillis(monJan12, LocalTime.of(7, 30), tokyoZone), hitResult.targetTriggerTimeEpochMs)
        assertTrue("Alarm must remain enabled", holidayOnlyAlarm.isEnabled)

        // 2. UNKNOWN across horizon -> VALID_NO_CURRENT_OCCURRENCE (never INVALID_SCHEDULE_NEEDS_REPAIR)
        val unknownResult = evaluator.evaluateWithHolidayClassifier(
            alarm = holidayOnlyAlarm,
            fromEpochMs = fromFriJan2At0800Ms,
            holidayClassifier = LocalHolidayClassifier.ALL_UNKNOWN,
            holidayPolicyOverride = HolidayPolicy.INCLUDE,
            evaluationZoneId = tokyoZone
        )
        assertNotNull(unknownResult)
        assertEquals(OccurrenceDecision.VALID_NO_CURRENT_OCCURRENCE, unknownResult!!.decision)
        assertEquals(HolidayState.UNKNOWN, unknownResult.holidayState)
        assertNull(unknownResult.occurrenceId)
        assertTrue("Alarm must remain enabled when UNKNOWN", holidayOnlyAlarm.isEnabled)

        // 3. Confirmed Holiday ONLY outside the 100-day bounded horizon (offset +105 days) ->
        //    must stop at MAX_CANDIDATE_SEARCH_STEPS (100) and return VALID_NO_CURRENT_OCCURRENCE
        var queriedSteps = 0
        val beyondHorizonDate = friJan2.plusDays(105)
        val beyondHorizonSnap = providerSnapshot(holidayDates = setOf(beyondHorizonDate))
        val countingClassifier = LocalHolidayClassifier { date ->
            queriedSteps++
            LocalHolidayClassifier.fromSnapshot(beyondHorizonSnap, "JP", nowMillis = fromFriJan2At0800Ms).classify(date)
        }
        val beyondResult = evaluator.evaluateWithHolidayClassifier(
            alarm = holidayOnlyAlarm,
            fromEpochMs = fromFriJan2At0800Ms,
            holidayClassifier = countingClassifier,
            holidayPolicyOverride = HolidayPolicy.INCLUDE,
            evaluationZoneId = tokyoZone
        )
        assertNotNull(beyondResult)
        assertEquals(OccurrenceDecision.VALID_NO_CURRENT_OCCURRENCE, beyondResult!!.decision)
        assertEquals(
            "Evaluator must respect bounded 100-day horizon without unbounded scanning",
            ScheduleOccurrenceEvaluator.MAX_CANDIDATE_SEARCH_STEPS,
            queriedSteps
        )

        // 4. Zero weekdays WITHOUT INCLUDE remains INVALID_SCHEDULE_NEEDS_REPAIR
        val invalidFollowBase = evaluator.evaluateWithHolidayClassifier(
            alarm = holidayOnlyAlarm,
            fromEpochMs = fromFriJan2At0800Ms,
            holidayClassifier = withHolidayClassifier,
            holidayPolicyOverride = HolidayPolicy.FOLLOW_BASE,
            evaluationZoneId = tokyoZone
        )
        assertNotNull(invalidFollowBase)
        assertEquals(OccurrenceDecision.INVALID_SCHEDULE_NEEDS_REPAIR, invalidFollowBase!!.decision)
    }

    // =========================================================================
    // T14: Duplicate Prevention on Base Weekday + Confirmed Holiday Overlap
    // =========================================================================

    @Test
    fun t14_include_baseWeekdayAndHolidayOverlap_generatesSingleOccurrenceOnly() {
        // monJan12 is BOTH a Base Monday AND a confirmed Holiday
        val overlapAlarm = repeatingAlarm(
            id = 42L,
            generation = 3,
            revision = 1,
            repeatDays = setOf(DayOfWeek.MONDAY)
        )
        val fromMon0600Ms = epochMillis(monJan12, LocalTime.of(6, 0), tokyoZone)
        val expectedMon12TargetMs = epochMillis(monJan12, LocalTime.of(7, 30), tokyoZone)
        val expectedMon19TargetMs = epochMillis(monJan19, LocalTime.of(7, 30), tokyoZone)

        val classifierGen1 = LocalHolidayClassifier.fromSnapshot(
            snapshot = providerSnapshot(generation = 1L, fetchedAtMillis = fetchedFreshEpochMs, holidayDates = setOf(monJan12)),
            configuredCountry = "JP",
            nowMillis = fromMon0600Ms
        )
        val classifierGen9 = LocalHolidayClassifier.fromSnapshot(
            snapshot = providerSnapshot(generation = 9L, fetchedAtMillis = fetchedFreshEpochMs + 60_000L, holidayDates = setOf(monJan12)),
            configuredCountry = "JP",
            nowMillis = fromMon0600Ms + 60_000L
        )

        val firstEval = evaluator.evaluateWithHolidayClassifier(
            alarm = overlapAlarm,
            fromEpochMs = fromMon0600Ms,
            holidayClassifier = classifierGen1,
            holidayPolicyOverride = HolidayPolicy.INCLUDE,
            evaluationZoneId = tokyoZone
        )
        val refreshedEval = evaluator.evaluateWithHolidayClassifier(
            alarm = overlapAlarm,
            fromEpochMs = fromMon0600Ms,
            holidayClassifier = classifierGen9,
            holidayPolicyOverride = HolidayPolicy.INCLUDE,
            evaluationZoneId = tokyoZone
        )

        assertNotNull(firstEval)
        assertNotNull(refreshedEval)
        assertEquals(expectedMon12TargetMs, firstEval!!.targetTriggerTimeEpochMs)
        assertEquals(
            "Provider generation and fetchedAt must not alter canonical occurrenceId",
            firstEval.occurrenceId,
            refreshedEval!!.occurrenceId
        )

        // Consuming that single occurrenceId must advance directly to next Monday (Jan 19),
        // proving no second "Holiday copy" exists on Jan 12!
        val afterConsumed = evaluator.evaluateWithHolidayClassifier(
            alarm = overlapAlarm,
            fromEpochMs = fromMon0600Ms,
            holidayClassifier = classifierGen1,
            holidayPolicyOverride = HolidayPolicy.INCLUDE,
            consumedOccurrenceKeys = setOf(firstEval.occurrenceId!!.key),
            evaluationZoneId = tokyoZone
        )
        assertNotNull(afterConsumed)
        assertEquals(
            "Consuming the overlap occurrence must advance to Jan 19 with no duplicate Jan 12 trigger",
            expectedMon19TargetMs,
            afterConsumed!!.targetTriggerTimeEpochMs
        )

        // Skipping that single baseTriggerTime via TYPE_SKIP also skips Jan 12 completely
        val skipException = ScheduleException(
            alarmId = overlapAlarm.id,
            baseTriggerTime = expectedMon12TargetMs,
            exceptionType = ScheduleException.TYPE_SKIP,
            generation = overlapAlarm.generation,
            revision = overlapAlarm.revision
        )
        val afterSkip = evaluator.evaluateWithHolidayClassifier(
            alarm = overlapAlarm,
            fromEpochMs = fromMon0600Ms,
            holidayClassifier = classifierGen1,
            exceptions = listOf(skipException),
            holidayPolicyOverride = HolidayPolicy.INCLUDE,
            evaluationZoneId = tokyoZone
        )
        assertNotNull(afterSkip)
        assertEquals(expectedMon19TargetMs, afterSkip!!.targetTriggerTimeEpochMs)
    }

    // =========================================================================
    // T15 & T16: Manual Override Precedence (via Real HolidayRepository.classify)
    // =========================================================================

    @Test
    fun t15_manualHolidayPrecedence_overridesProviderWorkdayAndUnconfiguredUnknown() = runTest {
        // Case A: JP snapshot marks friJan2 as WORKDAY, but Manual FORCE_HOLIDAY overrides it
        val dirA = tempFolder.newFolder("kno159_t15_a")
        FileHolidaySnapshotStore(File(dirA, "holiday_snapshot_v1.txt")).writeAtomically(
            providerSnapshot(country = "JP", holidayDates = setOf(thuJan1))
        )
        val repoWithSnapshot = createOfflineRepository(
            filesDir = dirA,
            settingsProvider = { AppSettings(holidayAutoSkipEnabled = false, holidayCountryCode = "JP") },
            manualOverrideSource = ManualHolidayOverrideSource { date ->
                if (date == friJan2) ManualHolidayOverride.FORCE_HOLIDAY else ManualHolidayOverride.AUTO
            }
        )
        val classifierA = repoWithSnapshot.captureWindowClassifier(startDate = friJan2, horizonDays = 10)
        assertEquals(HolidayClassificationSource.MANUAL_OVERRIDE, classifierA.classify(friJan2).source)

        val fromFri0600Ms = epochMillis(friJan2, LocalTime.of(6, 0), tokyoZone)
        val holidayOnlyAlarm = repeatingAlarm(repeatDays = emptySet())
        val includeEval = evaluator.evaluateWithHolidayClassifier(
            alarm = holidayOnlyAlarm,
            fromEpochMs = fromFri0600Ms,
            holidayClassifier = classifierA,
            holidayPolicyOverride = HolidayPolicy.INCLUDE,
            evaluationZoneId = tokyoZone
        )
        assertNotNull(includeEval)
        assertEquals(epochMillis(friJan2, LocalTime.of(7, 30), tokyoZone), includeEval!!.targetTriggerTimeEpochMs)

        // Case B: Country unconfigured ("") -> UNKNOWN, except Manual FORCE_HOLIDAY still wins!
        val dirB = tempFolder.newFolder("kno159_t15_b")
        val repoUnconfigured = createOfflineRepository(
            filesDir = dirB,
            settingsProvider = { AppSettings(holidayAutoSkipEnabled = false, holidayCountryCode = "") },
            manualOverrideSource = ManualHolidayOverrideSource { date ->
                if (date == friJan2) ManualHolidayOverride.FORCE_HOLIDAY else ManualHolidayOverride.AUTO
            }
        )
        val classifierB = repoUnconfigured.captureWindowClassifier(startDate = friJan2, horizonDays = 10)
        val excludeFriSatAlarm = repeatingAlarm(
            repeatDays = setOf(DayOfWeek.FRIDAY, DayOfWeek.SATURDAY),
            skipOnHolidays = true
        )
        val excludeEval = evaluator.evaluateWithHolidayClassifier(
            alarm = excludeFriSatAlarm,
            fromEpochMs = fromFri0600Ms,
            holidayClassifier = classifierB,
            evaluationZoneId = tokyoZone
        )
        assertNotNull(excludeEval)
        // Friday Jan 2 is suppressed by Manual FORCE_HOLIDAY; Saturday Jan 3 (UNKNOWN) is preserved!
        assertEquals(
            epochMillis(LocalDate.of(2026, 1, 3), LocalTime.of(7, 30), tokyoZone),
            excludeEval!!.targetTriggerTimeEpochMs
        )
    }

    @Test
    fun t16_manualWorkdayPrecedence_overridesProviderHoliday() = runTest {
        val dir = tempFolder.newFolder("kno159_t16")
        FileHolidaySnapshotStore(File(dir, "holiday_snapshot_v1.txt")).writeAtomically(
            providerSnapshot(country = "JP", holidayDates = setOf(thuJan1, monJan12))
        )
        // Override thuJan1 (Provider HOLIDAY) to Manual FORCE_WORKDAY
        val repo = createOfflineRepository(
            filesDir = dir,
            settingsProvider = { AppSettings(holidayAutoSkipEnabled = true, holidayCountryCode = "JP") },
            manualOverrideSource = ManualHolidayOverrideSource { date ->
                if (date == thuJan1) ManualHolidayOverride.FORCE_WORKDAY else ManualHolidayOverride.AUTO
            }
        )
        val classifier = repo.captureWindowClassifier(startDate = thuJan1, horizonDays = 20)
        assertEquals(HolidayClassificationKind.WORKDAY, classifier.classify(thuJan1).kind)
        assertEquals(HolidayClassificationSource.MANUAL_OVERRIDE, classifier.classify(thuJan1).source)

        // 1. EXCLUDE alarm on Thursday fires on thuJan1 because Manual FORCE_WORKDAY overrode provider HOLIDAY
        val excludeThuAlarm = repeatingAlarm(repeatDays = setOf(DayOfWeek.THURSDAY), skipOnHolidays = true)
        val excludeEval = evaluator.evaluateWithHolidayClassifier(
            alarm = excludeThuAlarm,
            fromEpochMs = nowJan1At0600Ms,
            holidayClassifier = classifier,
            evaluationZoneId = tokyoZone
        )
        assertNotNull(excludeEval)
        assertEquals(epochMillis(thuJan1, LocalTime.of(7, 30), tokyoZone), excludeEval!!.targetTriggerTimeEpochMs)

        // 2. Holiday-only INCLUDE alarm skips thuJan1 (now WORKDAY) and triggers on monJan12 (provider HOLIDAY)
        val holidayOnlyAlarm = repeatingAlarm(repeatDays = emptySet())
        val includeEval = evaluator.evaluateWithHolidayClassifier(
            alarm = holidayOnlyAlarm,
            fromEpochMs = nowJan1At0600Ms,
            holidayClassifier = classifier,
            holidayPolicyOverride = HolidayPolicy.INCLUDE,
            evaluationZoneId = tokyoZone
        )
        assertNotNull(includeEval)
        assertEquals(epochMillis(monJan12, LocalTime.of(7, 30), tokyoZone), includeEval!!.targetTriggerTimeEpochMs)
    }

    // =========================================================================
    // T17: Provider UNKNOWN Preserves Base + Uncovered Year + Stale Reload Truth
    // =========================================================================

    @Test
    fun t17_providerUnknown_preservesBaseAndUncoveredYearNeverInventsWorkday() {
        // Snapshot covers 2026 ONLY; evaluating in 2027 yields YEAR_NOT_COVERED -> UNKNOWN (never WORKDAY)
        val snap2026Only = providerSnapshot(year = 2026, holidayDates = setOf(thuJan1))
        val fri2027Jan1 = LocalDate.of(2027, 1, 1)
        val from2027Jan1At0600Ms = epochMillis(fri2027Jan1, LocalTime.of(6, 0), tokyoZone)

        val classifier2027 = LocalHolidayClassifier.fromSnapshot(
            snapshot = snap2026Only,
            configuredCountry = "JP",
            nowMillis = from2027Jan1At0600Ms
        )
        val jan1Classification = classifier2027.classify(fri2027Jan1)
        assertEquals(HolidayClassificationKind.UNKNOWN, jan1Classification.kind)
        assertEquals(HolidayUnknownReason.YEAR_NOT_COVERED, jan1Classification.unknownReason)

        // EXCLUDE preserves Base Friday on 2027-01-01 when 2027 is uncovered (UNKNOWN)
        val excludeFriAlarm = repeatingAlarm(repeatDays = setOf(DayOfWeek.FRIDAY), skipOnHolidays = true)
        val preservedBase = evaluator.evaluateWithHolidayClassifier(
            alarm = excludeFriAlarm,
            fromEpochMs = from2027Jan1At0600Ms,
            holidayClassifier = classifier2027,
            evaluationZoneId = tokyoZone
        )
        assertNotNull(preservedBase)
        assertEquals(OccurrenceDecision.NORMAL_TRIGGER, preservedBase!!.decision)
        assertEquals(epochMillis(fri2027Jan1, LocalTime.of(7, 30), tokyoZone), preservedBase.targetTriggerTimeEpochMs)
        assertEquals(HolidayState.UNKNOWN, preservedBase.holidayState)

        // Reloading a stale 2026 snapshot from disk preserves STALE freshness (never manufactures FRESH)
        val staleNowMs = fetchedFreshEpochMs + HolidayClassifier.PROVIDER_FRESHNESS_TTL_MILLIS + 10_000L
        val snapshotFile = File(tempFolder.newFolder("kno159_t17"), "holiday_snapshot_v1.txt")
        val store = FileHolidaySnapshotStore(snapshotFile)
        assertTrue(store.writeAtomically(snap2026Only))
        val reloaded = store.read()
        assertNotNull(reloaded)
        val staleClassifier = LocalHolidayClassifier.fromSnapshot(reloaded, "JP", nowMillis = staleNowMs)
        val reloadedClassification = staleClassifier.classify(thuJan1)
        assertEquals(HolidayClassificationKind.HOLIDAY, reloadedClassification.kind)
        assertEquals(HolidayProviderFreshness.STALE, reloadedClassification.freshness)
    }

    // =========================================================================
    // T18: ONE_TIME and Quick Alarm Immunity to Holiday Policy
    // =========================================================================

    @Test
    fun t18_oneTimeAndQuickAlarms_immuneToHolidayPolicies() {
        val classifier = LocalHolidayClassifier.fromSnapshot(
            snapshot = providerSnapshot(holidayDates = setOf(thuJan1, monJan12)),
            configuredCountry = "JP",
            nowMillis = nowJan1At0600Ms
        )

        // 1. ONE_TIME on confirmed Holiday (2026-01-01) with skipOnHolidays=true and EXCLUDE override still fires!
        val oneTimeOnHoliday = Alarm(
            id = 118L,
            hour = 7,
            minute = 30,
            isEnabled = true,
            scheduleKind = Alarm.SCHEDULE_KIND_ONE_TIME,
            specificDate = "2026-01-01",
            skipOnHolidays = true
        )
        val evalExclude = evaluator.evaluateWithHolidayClassifier(
            alarm = oneTimeOnHoliday,
            fromEpochMs = nowJan1At0600Ms,
            holidayClassifier = classifier,
            holidayPolicyOverride = HolidayPolicy.EXCLUDE,
            evaluationZoneId = tokyoZone
        )
        assertNotNull(evalExclude)
        assertEquals(OccurrenceDecision.NORMAL_TRIGGER, evalExclude!!.decision)
        assertEquals(epochMillis(thuJan1, LocalTime.of(7, 30), tokyoZone), evalExclude.targetTriggerTimeEpochMs)
        assertEquals(HolidayPolicy.FOLLOW_BASE, evalExclude.holidayPolicy)

        // 2. Expired ONE_TIME with INCLUDE override must NOT synthesize future Holiday monJan12
        val fromFri0600Ms = epochMillis(friJan2, LocalTime.of(6, 0), tokyoZone)
        val evalExpiredInclude = evaluator.evaluateWithHolidayClassifier(
            alarm = oneTimeOnHoliday,
            fromEpochMs = fromFri0600Ms,
            holidayClassifier = classifier,
            holidayPolicyOverride = HolidayPolicy.INCLUDE,
            evaluationZoneId = tokyoZone
        )
        assertNotNull(evalExpiredInclude)
        assertEquals(OccurrenceDecision.VALID_NO_CURRENT_OCCURRENCE, evalExpiredInclude!!.decision)

        // 3. Quick Alarm returns null regardless of Holiday classification or policy
        val quickAlarm = Alarm(
            id = 119L,
            hour = 7,
            minute = 30,
            isEnabled = true,
            kind = Alarm.KIND_QUICK,
            skipOnHolidays = true
        )
        assertNull(
            evaluator.evaluateWithHolidayClassifier(
                alarm = quickAlarm,
                fromEpochMs = nowJan1At0600Ms,
                holidayClassifier = classifier,
                holidayPolicyOverride = HolidayPolicy.INCLUDE,
                evaluationZoneId = tokyoZone
            )
        )
    }

    // =========================================================================
    // T19: Holiday-Derived Occurrence Uses Canonical Normal Identity & Planners
    // =========================================================================

    @Test
    fun t19_holidayDerivedOccurrence_usesNormalIdentityAndSupportsSkipReconcile() {
        // Weekend alarm + INCLUDE; Wednesday Feb 11 is a non-Base confirmed Holiday
        val fromTueFeb10At2000Ms = epochMillis(LocalDate.of(2026, 2, 10), LocalTime.of(20, 0), tokyoZone)
        val wedFeb11BaseMs = epochMillis(wedFeb11, LocalTime.of(9, 0), tokyoZone)
        val satFeb14BaseMs = epochMillis(satFeb14, LocalTime.of(9, 0), tokyoZone)

        val classifier = LocalHolidayClassifier.fromSnapshot(
            snapshot = providerSnapshot(holidayDates = setOf(wedFeb11, monFeb23)),
            configuredCountry = "JP",
            nowMillis = fromTueFeb10At2000Ms
        )

        val alarmRev1 = repeatingAlarm(
            id = 77L,
            hour = 9,
            minute = 0,
            repeatDays = setOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY),
            generation = 4,
            revision = 1,
            nextTriggerTime = wedFeb11BaseMs
        )
        val evaluated = evaluator.evaluateWithHolidayClassifier(
            alarm = alarmRev1,
            fromEpochMs = fromTueFeb10At2000Ms,
            holidayClassifier = classifier,
            holidayPolicyOverride = HolidayPolicy.INCLUDE,
            evaluationZoneId = tokyoZone
        )
        assertNotNull(evaluated)
        assertEquals(
            AlarmOccurrenceId(alarmId = 77L, baseTriggerTime = wedFeb11BaseMs, generation = 4),
            evaluated!!.occurrenceId
        )
        assertEquals("alarm_77_gen_4_base_$wedFeb11BaseMs", evaluated.occurrenceId!!.key)

        // Revision change preserves Holiday-derived occurrence identity; generation change updates it
        val evalRev2 = evaluator.evaluateWithHolidayClassifier(
            alarm = alarmRev1.copy(revision = 2, label = "Updated"),
            fromEpochMs = fromTueFeb10At2000Ms,
            holidayClassifier = classifier,
            holidayPolicyOverride = HolidayPolicy.INCLUDE,
            evaluationZoneId = tokyoZone
        )
        val evalGen5 = evaluator.evaluateWithHolidayClassifier(
            alarm = alarmRev1.copy(generation = 5),
            fromEpochMs = fromTueFeb10At2000Ms,
            holidayClassifier = classifier,
            holidayPolicyOverride = HolidayPolicy.INCLUDE,
            evaluationZoneId = tokyoZone
        )
        assertEquals(evaluated.occurrenceId!!.key, evalRev2!!.occurrenceId!!.key)
        assertNotEquals(evaluated.occurrenceId!!.key, evalGen5!!.occurrenceId!!.key)

        // CurrentNextDecisionPlanner validates Skip eligibility for the Holiday-derived occurrence
        val authority = ScheduleAuthority(
            alarmId = 77L,
            instanceToken = "token_77",
            definitionRevision = 10L,
            scheduleRevision = 1L,
            lifecycle = "LIVE",
            desiredOperation = "SET",
            desiredTriggerMillis = wedFeb11BaseMs,
            reconcileState = "SETTLED",
            restoreEpoch = 1L,
            actionKey = null
        )
        val fingerprint = ScheduleAuthorityFingerprint.fromAuthority(authority)
        val currentNextPlanner = CurrentNextDecisionPlanner(evaluator)
        val currentDecision = currentNextPlanner.planCurrentNext(
            alarm = alarmRev1,
            actionSnapshot = CurrentNextActionSnapshot(
                alarmId = 77L,
                expectedScheduledTargetEpochMs = wedFeb11BaseMs,
                expectedBaseTriggerTimeEpochMs = wedFeb11BaseMs,
                expectedGeneration = 4,
                authorityFingerprint = fingerprint
            ),
            nowEpochMs = fromTueFeb10At2000Ms,
            storedAuthority = authority,
            holidaySnapshotProvider = classifier.asHolidaySnapshotProvider(),
            holidayPolicyOverride = HolidayPolicy.INCLUDE,
            evaluationZoneId = tokyoZone
        )
        assertEquals(CurrentNextDecisionOutcome.CURRENT_NEXT_VALID, currentDecision.outcome)
        assertTrue(currentDecision.isSkipActionEligible)

        // NormalScheduleReconcilePlanner advances past skipped Holiday-derived occurrence to Sat Feb 14
        val reconcilePlanner = NormalScheduleReconcilePlanner(evaluator)
        val skipException = ScheduleException(
            alarmId = 77L,
            baseTriggerTime = wedFeb11BaseMs,
            exceptionType = ScheduleException.TYPE_SKIP,
            generation = 4,
            revision = 1
        )
        val reconcileDecision = reconcilePlanner.planReconcile(
            alarm = alarmRev1,
            storedAuthority = authority,
            exceptions = listOf(skipException),
            nowEpochMs = fromTueFeb10At2000Ms,
            expectedAuthorityFingerprint = fingerprint,
            holidaySnapshotProvider = classifier.asHolidaySnapshotProvider(),
            holidayPolicyOverride = HolidayPolicy.INCLUDE,
            evaluationZoneId = tokyoZone
        )
        assertEquals(ReconcileOutcome.PROJECTION_DIFFERS_RECONCILE_REQUIRED, reconcileDecision.outcome)
        assertEquals(satFeb14BaseMs, reconcileDecision.evaluatedPostSkipNextTargetEpochMs)
    }

    // =========================================================================
    // T20: Exception Does Not Rebind or Resurrect After Holiday Truth Invalidation
    // =========================================================================

    @Test
    fun t20_exceptionDoesNotRebindOrResurrect_whenHolidayTruthChanges() {
        val fromTueFeb10At2000Ms = epochMillis(LocalDate.of(2026, 2, 10), LocalTime.of(20, 0), tokyoZone)
        val wedFeb11Base0900Ms = epochMillis(wedFeb11, LocalTime.of(9, 0), tokyoZone)
        val wedFeb11Override1030Ms = epochMillis(wedFeb11, LocalTime.of(10, 30), tokyoZone)
        val satFeb14Base0900Ms = epochMillis(satFeb14, LocalTime.of(9, 0), tokyoZone)
        val monFeb23Base0900Ms = epochMillis(monFeb23, LocalTime.of(9, 0), tokyoZone)

        // Case A: Holiday-only INCLUDE alarm had TYPE_TIME_OVERRIDE (10:30) on Wed Feb 11 Holiday
        val holidayOnlyAlarm = repeatingAlarm(id = 120L, hour = 9, minute = 0, repeatDays = emptySet())
        val feb11Override = ScheduleException(
            alarmId = 120L,
            baseTriggerTime = wedFeb11Base0900Ms,
            exceptionType = ScheduleException.TYPE_TIME_OVERRIDE,
            overrideTriggerTime = wedFeb11Override1030Ms,
            generation = holidayOnlyAlarm.generation,
            revision = holidayOnlyAlarm.revision
        )

        val beforeInvalidation = LocalHolidayClassifier.fromSnapshot(
            snapshot = providerSnapshot(holidayDates = setOf(wedFeb11, monFeb23)),
            configuredCountry = "JP",
            nowMillis = fromTueFeb10At2000Ms
        )
        val evalBefore = evaluator.evaluateWithHolidayClassifier(
            alarm = holidayOnlyAlarm,
            fromEpochMs = fromTueFeb10At2000Ms,
            holidayClassifier = beforeInvalidation,
            exceptions = listOf(feb11Override),
            holidayPolicyOverride = HolidayPolicy.INCLUDE,
            evaluationZoneId = tokyoZone
        )
        assertEquals(OccurrenceDecision.TIME_OVERRIDDEN, evalBefore!!.decision)
        assertEquals(wedFeb11Override1030Ms, evalBefore.targetTriggerTimeEpochMs)

        // Now Feb 11 is invalidated (becomes WORKDAY via Manual FORCE_WORKDAY); next Holiday is Mon Feb 23
        val afterInvalidation = LocalHolidayClassifier.fromSnapshot(
            snapshot = providerSnapshot(holidayDates = setOf(wedFeb11, monFeb23)),
            configuredCountry = "JP",
            manualOverrideSource = ManualHolidayOverrideSource { date ->
                if (date == wedFeb11) ManualHolidayOverride.FORCE_WORKDAY else ManualHolidayOverride.AUTO
            },
            nowMillis = fromTueFeb10At2000Ms
        )
        val evalAfter = evaluator.evaluateWithHolidayClassifier(
            alarm = holidayOnlyAlarm,
            fromEpochMs = fromTueFeb10At2000Ms,
            holidayClassifier = afterInvalidation,
            exceptions = listOf(feb11Override),
            holidayPolicyOverride = HolidayPolicy.INCLUDE,
            evaluationZoneId = tokyoZone
        )
        assertNotNull(evalAfter)
        assertEquals(
            "Stale Feb 11 override must NOT fire on Feb 11 and must NOT rebind to Feb 23",
            OccurrenceDecision.NORMAL_TRIGGER,
            evalAfter!!.decision
        )
        assertFalse(evalAfter.isExceptionApplied)
        assertEquals(monFeb23Base0900Ms, evalAfter.baseTriggerTimeEpochMs)
        assertEquals(monFeb23Base0900Ms, evalAfter.targetTriggerTimeEpochMs)

        // Case B: EXCLUDE weekday alarm had TYPE_TIME_OVERRIDE on Wed Feb 11 when UNKNOWN,
        //         then provider reveals Wed Feb 11 is HOLIDAY -> Layer 1 suppresses Feb 11 before Layer 2,
        //         and override does NOT rebind to Saturday Feb 14!
        val wedSatExcludeAlarm = repeatingAlarm(
            id = 121L,
            hour = 9,
            minute = 0,
            repeatDays = setOf(DayOfWeek.WEDNESDAY, DayOfWeek.SATURDAY),
            skipOnHolidays = true
        )
        val feb11ExcludeOverride = feb11Override.copy(alarmId = 121L)
        val evalExcluded = evaluator.evaluateWithHolidayClassifier(
            alarm = wedSatExcludeAlarm,
            fromEpochMs = fromTueFeb10At2000Ms,
            holidayClassifier = beforeInvalidation,
            exceptions = listOf(feb11ExcludeOverride),
            evaluationZoneId = tokyoZone
        )
        assertNotNull(evalExcluded)
        assertEquals(OccurrenceDecision.NORMAL_TRIGGER, evalExcluded!!.decision)
        assertFalse(evalExcluded.isExceptionApplied)
        assertEquals(satFeb14Base0900Ms, evalExcluded.targetTriggerTimeEpochMs)
    }

    // =========================================================================
    // T21 - T23: Pure Material Holiday Truth Delta & Idempotency Contract
    // =========================================================================

    @Test
    fun t21_identicalProviderTruth_causesNoMaterialChangeSignal() {
        val staleFetchMs = nowJan1At0600Ms - HolidayClassifier.PROVIDER_FRESHNESS_TTL_MILLIS - 60_000L
        val freshFetchMs = nowJan1At0600Ms - 1_000L

        // Snapshot 1 (gen=1, STALE) vs Snapshot 2 (gen=2, FRESH) with identical effective Holiday dates
        val snapGen1Stale = providerSnapshot(generation = 1L, fetchedAtMillis = staleFetchMs)
        val snapGen2Fresh = providerSnapshot(generation = 2L, fetchedAtMillis = freshFetchMs)

        val prevClassifier = LocalHolidayClassifier.fromSnapshot(snapGen1Stale, "JP", nowMillis = nowJan1At0600Ms)
        val currClassifier = LocalHolidayClassifier.fromSnapshot(snapGen2Fresh, "JP", nowMillis = nowJan1At0600Ms)
        assertEquals(HolidayProviderFreshness.STALE, prevClassifier.classify(thuJan1).freshness)
        assertEquals(HolidayProviderFreshness.FRESH, currClassifier.classify(thuJan1).freshness)

        val excludeAlarm = repeatingAlarm(id = 201L, repeatDays = setOf(DayOfWeek.THURSDAY, DayOfWeek.FRIDAY), skipOnHolidays = true)
        val includeAlarm = repeatingAlarm(id = 202L, repeatDays = emptySet())

        val decision = materialDetector.detectMaterialChange(
            previousClassifier = prevClassifier,
            currentClassifier = currClassifier,
            evaluationEpochMs = nowJan1At0600Ms,
            evaluationZoneId = tokyoZone,
            alarms = listOf(
                AlarmHolidayEvaluationInput(alarm = excludeAlarm),
                AlarmHolidayEvaluationInput(alarm = includeAlarm, holidayPolicyOverride = HolidayPolicy.INCLUDE)
            )
        )

        assertEquals(HolidayTruthChangeOutcome.NO_MATERIAL_CHANGE, decision.outcome)
        assertFalse(decision.hasMaterialHolidayChange)
        assertFalse(decision.shouldDispatchReconcile)
        assertEquals(decision.previousSignature, decision.currentSignature)
        assertTrue(decision.holidayBoundaryChangedDates.isEmpty())
        assertTrue(decision.affectedAlarms.isEmpty())
    }

    @Test
    fun t22_actualClassificationChange_causesMaterialChangeAndScopesAffectedAlarms() {
        // Initially UNKNOWN (no snapshot); provider snapshot arrives marking thuJan1 & monJan12 as HOLIDAY
        val prevClassifier = LocalHolidayClassifier.ALL_UNKNOWN
        val currClassifier = LocalHolidayClassifier.fromSnapshot(
            snapshot = providerSnapshot(holidayDates = setOf(thuJan1, monJan12)),
            configuredCountry = "JP",
            nowMillis = nowJan1At0600Ms
        )

        val thuTargetMs = epochMillis(thuJan1, LocalTime.of(7, 30), tokyoZone)
        val friTargetMs = epochMillis(friJan2, LocalTime.of(7, 30), tokyoZone)

        // 1. EXCLUDE alarm (stored nextTriggerTime was thuJan1 under UNKNOWN; must move to friJan2!)
        val excludeAlarm = repeatingAlarm(
            id = 301L,
            repeatDays = setOf(DayOfWeek.THURSDAY, DayOfWeek.FRIDAY),
            skipOnHolidays = true,
            nextTriggerTime = thuTargetMs
        )
        // 2. Holiday-only INCLUDE alarm (stored nextTriggerTime was 0 under UNKNOWN; must arm thuJan1!)
        val holidayOnlyInclude = repeatingAlarm(
            id = 302L,
            repeatDays = emptySet(),
            nextTriggerTime = 0L
        )
        // 3. FOLLOW_BASE alarm (immune to Holiday changes!)
        val followBaseAlarm = repeatingAlarm(
            id = 303L,
            repeatDays = setOf(DayOfWeek.THURSDAY),
            skipOnHolidays = false,
            nextTriggerTime = thuTargetMs
        )
        // 4. ONE_TIME alarm (immune to Holiday changes!)
        val oneTimeAlarm = Alarm(
            id = 304L,
            hour = 7,
            minute = 30,
            isEnabled = true,
            scheduleKind = Alarm.SCHEDULE_KIND_ONE_TIME,
            specificDate = "2026-01-01",
            skipOnHolidays = true,
            nextTriggerTime = thuTargetMs
        )

        val decision = materialDetector.detectMaterialChange(
            previousClassifier = prevClassifier,
            currentClassifier = currClassifier,
            evaluationEpochMs = nowJan1At0600Ms,
            evaluationZoneId = tokyoZone,
            alarms = listOf(
                AlarmHolidayEvaluationInput(alarm = excludeAlarm),
                AlarmHolidayEvaluationInput(alarm = holidayOnlyInclude, holidayPolicyOverride = HolidayPolicy.INCLUDE),
                AlarmHolidayEvaluationInput(alarm = followBaseAlarm),
                AlarmHolidayEvaluationInput(alarm = oneTimeAlarm)
            )
        )

        assertEquals(HolidayTruthChangeOutcome.MATERIAL_CHANGE_DETECTED, decision.outcome)
        assertTrue(decision.hasMaterialHolidayChange)
        assertTrue(decision.shouldDispatchReconcile)
        assertTrue(thuJan1 in decision.holidayBoundaryChangedDates)
        assertTrue(monJan12 in decision.holidayBoundaryChangedDates)

        // Only EXCLUDE (301) and INCLUDE (302) are affected; FOLLOW_BASE (303) and ONE_TIME (304) are untouched
        assertEquals(listOf(301L, 302L), decision.affectedAlarms.map { it.alarmId })

        val delta301 = decision.affectedAlarms.first { it.alarmId == 301L }
        assertTrue(delta301.nextTargetChanged)
        assertTrue(delta301.storedProjectionNeedsReconcile)
        assertEquals(
            AlarmOccurrenceId(301L, thuTargetMs, excludeAlarm.generation),
            delta301.staleOccurrenceIdToInvalidate
        )
        assertEquals(friTargetMs, delta301.currentEvaluation!!.targetTriggerTimeEpochMs)

        val delta302 = decision.affectedAlarms.first { it.alarmId == 302L }
        assertTrue(delta302.nextTargetChanged)
        assertTrue(delta302.storedProjectionNeedsReconcile)
        assertNull(delta302.staleOccurrenceIdToInvalidate)
        assertEquals(thuTargetMs, delta302.currentEvaluation!!.targetTriggerTimeEpochMs)
    }

    @Test
    fun t23_repeatedEquivalentChange_isIdempotent() {
        val initialClassifier = LocalHolidayClassifier.ALL_UNKNOWN
        val snapGen1 = providerSnapshot(generation = 1L, fetchedAtMillis = fetchedFreshEpochMs, holidayDates = setOf(thuJan1))
        val snapGen2Equivalent = providerSnapshot(generation = 2L, fetchedAtMillis = fetchedFreshEpochMs + 50_000L, holidayDates = setOf(thuJan1))

        val classifierGen1 = LocalHolidayClassifier.fromSnapshot(snapGen1, "JP", nowMillis = nowJan1At0600Ms)
        val classifierGen2 = LocalHolidayClassifier.fromSnapshot(snapGen2Equivalent, "JP", nowMillis = nowJan1At0600Ms + 50_000L)

        val holidayOnlyInclude = AlarmHolidayEvaluationInput(
            alarm = repeatingAlarm(id = 401L, repeatDays = emptySet()),
            holidayPolicyOverride = HolidayPolicy.INCLUDE
        )

        // First transition observation: material reconcile required
        val firstDecision = materialDetector.detectMaterialChange(
            previousClassifier = initialClassifier,
            currentClassifier = classifierGen1,
            evaluationEpochMs = nowJan1At0600Ms,
            evaluationZoneId = tokyoZone,
            alarms = listOf(holidayOnlyInclude),
            lastObservedSignature = null
        )
        assertEquals(HolidayTruthChangeOutcome.MATERIAL_CHANGE_DETECTED, firstDecision.outcome)
        assertTrue(firstDecision.shouldDispatchReconcile)

        // Duplicate delivery of the exact same transition (or equivalent gen=2 refresh) with lastObservedSignature
        val duplicateReplay = materialDetector.detectMaterialChange(
            previousClassifier = initialClassifier,
            currentClassifier = classifierGen2,
            evaluationEpochMs = nowJan1At0600Ms,
            evaluationZoneId = tokyoZone,
            alarms = listOf(holidayOnlyInclude),
            lastObservedSignature = firstDecision.currentSignature
        )
        assertEquals(HolidayTruthChangeOutcome.IDEMPOTENT_ALREADY_OBSERVED, duplicateReplay.outcome)
        assertFalse(duplicateReplay.shouldDispatchReconcile)
        assertTrue(duplicateReplay.affectedAlarms.isEmpty())
    }

    // =========================================================================
    // T24: Country Mismatch -> UNKNOWN Fail-Safe (Never Reuses Old Country Truth)
    // =========================================================================

    @Test
    fun t24_countryMismatch_failsSafeToUnknownAndSignalsMaterialInvalidation() = runTest {
        // Disk holds a valid JP snapshot where thuJan1 and monJan12 are Holidays,
        // but user changed configured country to "US" before US snapshot is fetched.
        val dir = tempFolder.newFolder("kno159_t24")
        val jpSnap = providerSnapshot(country = "JP", holidayDates = setOf(thuJan1, monJan12))
        FileHolidaySnapshotStore(File(dir, "holiday_snapshot_v1.txt")).writeAtomically(jpSnap)

        val repoSwitchedToUs = createOfflineRepository(
            filesDir = dir,
            settingsProvider = { AppSettings(holidayAutoSkipEnabled = true, holidayCountryCode = "US") }
        )
        val usMismatchClassifier = repoSwitchedToUs.captureWindowClassifier(startDate = thuJan1, horizonDays = 20)
        val mismatchClassification = usMismatchClassifier.classify(thuJan1)
        assertEquals(HolidayClassificationKind.UNKNOWN, mismatchClassification.kind)
        assertEquals(HolidayUnknownReason.COUNTRY_MISMATCH, mismatchClassification.unknownReason)

        // 1. EXCLUDE alarm on Thursday Jan 1 MUST preserve Base Thursday (not skip using stale JP holiday!)
        val excludeThuAlarm = repeatingAlarm(repeatDays = setOf(DayOfWeek.THURSDAY), skipOnHolidays = true)
        val excludeEval = evaluator.evaluateWithHolidayClassifier(
            alarm = excludeThuAlarm,
            fromEpochMs = nowJan1At0600Ms,
            holidayClassifier = usMismatchClassifier,
            evaluationZoneId = tokyoZone
        )
        assertNotNull(excludeEval)
        assertEquals(OccurrenceDecision.NORMAL_TRIGGER, excludeEval!!.decision)
        assertEquals(epochMillis(thuJan1, LocalTime.of(7, 30), tokyoZone), excludeEval.targetTriggerTimeEpochMs)
        assertEquals(HolidayState.UNKNOWN, excludeEval.holidayState)

        // 2. Holiday-only INCLUDE alarm MUST NOT synthesize JP holidays under US mismatch
        val holidayOnlyAlarm = repeatingAlarm(repeatDays = emptySet())
        val includeEval = evaluator.evaluateWithHolidayClassifier(
            alarm = holidayOnlyAlarm,
            fromEpochMs = nowJan1At0600Ms,
            holidayClassifier = LocalHolidayClassifier.fromSnapshot(jpSnap, "US", nowMillis = nowJan1At0600Ms),
            holidayPolicyOverride = HolidayPolicy.INCLUDE,
            evaluationZoneId = tokyoZone
        )
        assertNotNull(includeEval)
        assertEquals(OccurrenceDecision.VALID_NO_CURRENT_OCCURRENCE, includeEval!!.decision)
        assertEquals(HolidayState.UNKNOWN, includeEval.holidayState)

        // 3. Material change detector flags JP -> US country switch as MATERIAL_CHANGE_DETECTED (HOLIDAY -> UNKNOWN)
        val jpClassifier = LocalHolidayClassifier.fromSnapshot(jpSnap, "JP", nowMillis = nowJan1At0600Ms)
        val usClassifier = LocalHolidayClassifier.fromSnapshot(jpSnap, "US", nowMillis = nowJan1At0600Ms)
        val delta = materialDetector.detectMaterialChange(
            previousClassifier = jpClassifier,
            currentClassifier = usClassifier,
            evaluationEpochMs = nowJan1At0600Ms,
            evaluationZoneId = tokyoZone,
            alarms = listOf(AlarmHolidayEvaluationInput(alarm = excludeThuAlarm))
        )
        assertEquals(HolidayTruthChangeOutcome.MATERIAL_CHANGE_DETECTED, delta.outcome)
        assertTrue(thuJan1 in delta.holidayBoundaryChangedDates)
    }

    // =========================================================================
    // T25: Date / Timezone / Midnight / DST Boundary Stability
    // =========================================================================

    @Test
    fun t25_dateAndZoneBoundaries_tokyoVsNewYorkMidnightAndDstHolidayTransitions() {
        // 1. Same UTC instant: 2026-01-01T16:00:00Z
        //    - In America/New_York (UTC-5): 2026-01-01 11:00 -> 12:00 Holiday-only alarm triggers on 2026-01-01!
        //    - In Asia/Tokyo (UTC+9):       2026-01-02 01:00 -> 2026-01-01 already passed -> VALID_NO_CURRENT_OCCURRENCE!
        val sameUtcEpochMs = Instant.parse("2026-01-01T16:00:00Z").toEpochMilli()
        val noonHolidayOnlyAlarm = repeatingAlarm(id = 501L, hour = 12, minute = 0, repeatDays = emptySet())
        val jan1OnlyClassifier = LocalHolidayClassifier.fromSnapshot(
            snapshot = providerSnapshot(country = "US", holidayDates = setOf(thuJan1)),
            configuredCountry = "US",
            nowMillis = sameUtcEpochMs
        )

        val nyEval = evaluator.evaluateWithHolidayClassifier(
            alarm = noonHolidayOnlyAlarm,
            fromEpochMs = sameUtcEpochMs,
            holidayClassifier = jan1OnlyClassifier,
            holidayPolicyOverride = HolidayPolicy.INCLUDE,
            evaluationZoneId = nyZone
        )
        assertNotNull(nyEval)
        assertEquals(OccurrenceDecision.NORMAL_TRIGGER, nyEval!!.decision)
        assertEquals(epochMillis(thuJan1, LocalTime.of(12, 0), nyZone), nyEval.targetTriggerTimeEpochMs)

        val tokyoEval = evaluator.evaluateWithHolidayClassifier(
            alarm = noonHolidayOnlyAlarm,
            fromEpochMs = sameUtcEpochMs,
            holidayClassifier = jan1OnlyClassifier,
            holidayPolicyOverride = HolidayPolicy.INCLUDE,
            evaluationZoneId = tokyoZone
        )
        assertNotNull(tokyoEval)
        assertEquals(OccurrenceDecision.VALID_NO_CURRENT_OCCURRENCE, tokyoEval!!.decision)

        // 2. Local midnight boundary: 2026-01-01 23:59:59.999 JST rolls cleanly to 2026-01-02 07:30 Holiday
        val preMidnightMs = friJan2.atStartOfDay(tokyoZone).toInstant().toEpochMilli() - 1L
        val jan2Classifier = LocalHolidayClassifier.fromSnapshot(
            snapshot = providerSnapshot(country = "JP", holidayDates = setOf(friJan2)),
            configuredCountry = "JP",
            nowMillis = preMidnightMs
        )
        val midnightRollEval = evaluator.evaluateWithHolidayClassifier(
            alarm = repeatingAlarm(id = 502L, hour = 7, minute = 30, repeatDays = emptySet()),
            fromEpochMs = preMidnightMs,
            holidayClassifier = jan2Classifier,
            holidayPolicyOverride = HolidayPolicy.INCLUDE,
            evaluationZoneId = tokyoZone
        )
        assertNotNull(midnightRollEval)
        assertEquals(epochMillis(friJan2, LocalTime.of(7, 30), tokyoZone), midnightRollEval!!.targetTriggerTimeEpochMs)

        // 3. DST Spring-Forward Gap (2026-03-08 02:30 -> 03:00 EDT) & Fall-Back Overlap (2026-11-01 01:30 EDT once)
        val dstSpringDate = LocalDate.of(2026, 3, 8)
        val dstFallDate = LocalDate.of(2026, 11, 1)
        val dstClassifier = LocalHolidayClassifier.fromSnapshot(
            snapshot = providerSnapshot(country = "US", holidayDates = setOf(dstSpringDate, dstFallDate)),
            configuredCountry = "US",
            nowMillis = sameUtcEpochMs
        )

        val springEval = evaluator.evaluateWithHolidayClassifier(
            alarm = repeatingAlarm(id = 503L, hour = 2, minute = 30, repeatDays = emptySet()),
            fromEpochMs = epochMillis(dstSpringDate, LocalTime.of(1, 0), nyZone),
            holidayClassifier = dstClassifier,
            holidayPolicyOverride = HolidayPolicy.INCLUDE,
            evaluationZoneId = nyZone
        )
        assertNotNull(springEval)
        assertEquals(
            epochMillis(dstSpringDate, LocalTime.of(3, 0), nyZone),
            springEval!!.targetTriggerTimeEpochMs
        )

        val fallEval = evaluator.evaluateWithHolidayClassifier(
            alarm = repeatingAlarm(id = 504L, hour = 1, minute = 30, repeatDays = emptySet()),
            fromEpochMs = epochMillis(dstFallDate, LocalTime.of(0, 0), nyZone),
            holidayClassifier = dstClassifier,
            holidayPolicyOverride = HolidayPolicy.INCLUDE,
            evaluationZoneId = nyZone
        )
        val expectedFallEarlierOffsetMs = LocalDateTime.of(2026, 11, 1, 1, 30)
            .atZone(nyZone)
            .withEarlierOffsetAtOverlap()
            .toInstant()
            .toEpochMilli()
        assertNotNull(fallEval)
        assertEquals(expectedFallEarlierOffsetMs, fallEval!!.targetTriggerTimeEpochMs)
    }

    // =========================================================================
    // T26 - T32: Bounded Window Snapshot Consistency & Material Delta Regressions
    // =========================================================================

    @Test
    fun t26_concurrentProviderRefreshDuringWindowCapture_neverMixesGenerationsAndUpdatesMonotonically() = runTest {
        val activeYear = LocalDate.now().year
        val jan1Active = LocalDate.of(activeYear, 1, 1)
        val jan12Active = LocalDate.of(activeYear, 1, 12)
        val feb11Active = LocalDate.of(activeYear, 2, 11)
        val activeStartMs = epochMillis(jan1Active, LocalTime.of(6, 0), tokyoZone)

        // Subcase A: Existing gen=1 snapshot has ONLY jan1Active.
        // Mid-evaluation refresh publishes gen=2 adding jan12Active and feb11Active.
        // Previously captured window MUST stay coherently on gen=1 across all 100 dates.
        val dirA = tempFolder.newFolder("kno159_t26_a")
        FileHolidaySnapshotStore(File(dirA, "holiday_snapshot_v1.txt")).writeAtomically(
            providerSnapshot(
                country = "JP",
                generation = 1L,
                fetchedAtMillis = activeStartMs - 5_000L,
                year = activeYear,
                holidayDates = setOf(jan1Active)
            )
        )

        val settingsReadsA = AtomicInteger(0)
        val fakeInMemoryApiCalls = AtomicInteger(0)
        val contextA = mockk<Context>()
        every { contextA.filesDir } returns dirA
        val prefsA = mockk<PreferencesManager>()
        coEvery { prefsA.getCurrentSettings() } answers {
            settingsReadsA.incrementAndGet()
            AppSettings(holidayAutoSkipEnabled = true, holidayCountryCode = "JP")
        }
        val fakeInMemoryApi = object : HolidayApi {
            override suspend fun getPublicHolidays(year: Int, countryCode: String): List<HolidayDto> {
                fakeInMemoryApiCalls.incrementAndGet()
                return listOf(
                    HolidayDto("$year-01-01", "New Year", "New Year's Day", countryCode),
                    HolidayDto("$year-01-12", "Coming of Age", "Coming of Age Day", countryCode),
                    HolidayDto("$year-02-11", "Foundation", "National Foundation Day", countryCode)
                )
            }
        }
        val repoA = HolidayRepository(
            context = contextA,
            holidayApi = fakeInMemoryApi,
            preferencesManager = prefsA,
            manualOverrideSource = ManualHolidayOverrideSource.NONE,
            clockMillis = { activeStartMs }
        )

        val windowGen1 = repoA.captureAuthorityWindow(jan1Active, jan1Active.plusDays(99))
        assertEquals("Window capture must read settings once", 1, settingsReadsA.get())
        assertEquals("Window capture must never invoke HolidayApi", 0, fakeInMemoryApiCalls.get())
        assertEquals(1L, windowGen1.snapshotGeneration)

        val classifierGen1 = windowGen1.asLocalHolidayClassifier()
        // Classify first 5 dates before refresh
        assertEquals(HolidayClassificationKind.HOLIDAY, classifierGen1.classify(jan1Active).kind)
        assertEquals(HolidayClassificationKind.WORKDAY, classifierGen1.classify(jan1Active.plusDays(1)).kind)

        // Concurrent/interleaved refresh publishes generation 2 while classifierGen1 is still in use
        val refreshOutcome = repoA.refreshWithOutcome(force = true)
        assertTrue(refreshOutcome is HolidayRefreshOutcome.Success)
        assertEquals(2L, (refreshOutcome as HolidayRefreshOutcome.Success).snapshot.generation)

        // Remaining dates in classifierGen1 MUST still observe gen=1 (WORKDAY on Jan 12 & Feb 11, never mixed!)
        assertEquals(HolidayClassificationKind.WORKDAY, classifierGen1.classify(jan12Active).kind)
        assertEquals(HolidayClassificationKind.WORKDAY, classifierGen1.classify(feb11Active).kind)

        val holidayOnlyAlarm = repeatingAlarm(id = 601L, repeatDays = emptySet())
        val evalAfterJan1OnGen1 = evaluator.evaluateWithHolidayClassifier(
            alarm = holidayOnlyAlarm,
            fromEpochMs = epochMillis(jan1Active, LocalTime.of(9, 0), tokyoZone),
            holidayClassifier = classifierGen1,
            holidayPolicyOverride = HolidayPolicy.INCLUDE,
            evaluationZoneId = tokyoZone
        )
        assertEquals(OccurrenceDecision.VALID_NO_CURRENT_OCCURRENCE, evalAfterJan1OnGen1!!.decision)

        // Next captured window coherently observes generation 2 across all 100 dates
        val windowGen2 = repoA.captureAuthorityWindow(jan1Active, jan1Active.plusDays(99))
        assertEquals(2L, windowGen2.snapshotGeneration)
        assertEquals(HolidayClassificationKind.HOLIDAY, windowGen2.classify(jan12Active).kind)
        assertEquals(HolidayClassificationKind.HOLIDAY, windowGen2.classify(feb11Active).kind)

        // Subcase B: Cold-cache repository interleaved at settings suspension point with refreshWithOutcome
        val dirB = tempFolder.newFolder("kno159_t26_b")
        val contextB = mockk<Context>()
        every { contextB.filesDir } returns dirB
        val captureSuspendedInSettings = CompletableDeferred<Unit>()
        val allowCaptureResume = CompletableDeferred<Unit>()
        val prefsCallIndex = AtomicInteger(0)
        val prefsB = mockk<PreferencesManager>()
        coEvery { prefsB.getCurrentSettings() } coAnswers {
            if (prefsCallIndex.incrementAndGet() == 1) {
                captureSuspendedInSettings.complete(Unit)
                allowCaptureResume.await()
            }
            AppSettings(holidayAutoSkipEnabled = true, holidayCountryCode = "JP")
        }
        val repoB = HolidayRepository(
            context = contextB,
            holidayApi = fakeInMemoryApi,
            preferencesManager = prefsB,
            manualOverrideSource = ManualHolidayOverrideSource.NONE,
            clockMillis = { activeStartMs }
        )

        val deferredWindow = async {
            repoB.captureAuthorityWindow(jan1Active, jan1Active.plusDays(99))
        }
        captureSuspendedInSettings.await()

        // While captureAuthorityWindow is suspended at getCurrentSettings(), refresh publishes gen=1
        val interleavedOutcome = repoB.refreshWithOutcome(force = true)
        assertTrue(interleavedOutcome is HolidayRefreshOutcome.Success)
        allowCaptureResume.complete(Unit)

        val resumedWindow = deferredWindow.await()
        assertEquals(1L, resumedWindow.snapshotGeneration)
        for (offset in 0L until 100L) {
            val d = jan1Active.plusDays(offset)
            val expectedKind = if (d in setOf(jan1Active, jan12Active, feb11Active)) {
                HolidayClassificationKind.HOLIDAY
            } else {
                HolidayClassificationKind.WORKDAY
            }
            assertEquals("Coherent gen=1 expected at offset $offset ($d)", expectedKind, resumedWindow.classify(d).kind)
        }
    }

    @Test
    fun t27_concurrentCountryChangeDuringWindowCapture_keepsCountryIdentityStableAcrossWindow() = runTest {
        val dir = tempFolder.newFolder("kno159_t27")
        FileHolidaySnapshotStore(File(dir, "holiday_snapshot_v1.txt")).writeAtomically(
            providerSnapshot(country = "JP", holidayDates = setOf(thuJan1, monJan12, wedFeb11, monFeb23))
        )

        val activeCountry = AtomicReference("JP")
        val settingsReadCount = AtomicInteger(0)
        val repo = createOfflineRepository(
            filesDir = dir,
            settingsProvider = {
                val readCountry = activeCountry.get()
                if (settingsReadCount.incrementAndGet() == 1) {
                    // Simulate concurrent user country change immediately after capture's single read!
                    activeCountry.set("US")
                }
                AppSettings(holidayAutoSkipEnabled = true, holidayCountryCode = readCountry)
            }
        )

        val capturedClassifier = repo.captureWindowClassifier(startDate = thuJan1, horizonDays = 100)
        assertEquals(
            "captureWindowClassifier must perform exactly 1 settings read for the entire 100-day window",
            1,
            settingsReadCount.get()
        )
        assertEquals("US", activeCountry.get())

        // Mutate country again to blank mid-iteration over the 100-day window
        for (offset in 0L until 100L) {
            if (offset == 15L) {
                activeCountry.set("")
            }
            val date = thuJan1.plusDays(offset)
            val classification = capturedClassifier.classify(date)
            assertEquals(
                "Country must remain pinned to JP across all 100 dates (never COUNTRY_MISMATCH or PROVIDER_NOT_CONFIGURED)",
                HolidayClassificationSource.PROVIDER,
                classification.source
            )
            val expected = if (date in setOf(thuJan1, monJan12, wedFeb11, monFeb23)) {
                HolidayClassificationKind.HOLIDAY
            } else {
                HolidayClassificationKind.WORKDAY
            }
            assertEquals(expected, classification.kind)
        }

        // Subsequent window capture with blank country uniformly sees null country and UNKNOWN across all 100 dates
        val unconfiguredWindow = repo.captureAuthorityWindow(thuJan1, thuJan1.plusDays(99))
        assertNull(unconfiguredWindow.configuredCountry)
        assertNull(unconfiguredWindow.providerSnapshot)
        for (offset in 0L until 100L) {
            val c = unconfiguredWindow.classify(thuJan1.plusDays(offset))
            assertEquals(HolidayClassificationKind.UNKNOWN, c.kind)
            assertEquals(HolidayUnknownReason.PROVIDER_NOT_CONFIGURED, c.unknownReason)
        }
    }

    @Test
    fun t28_countrySwitchAndUncoveredYearBoundary_failsSafeToUnknownWithoutOldCountryLeak() = runTest {
        val dir = tempFolder.newFolder("kno159_t28")
        // Snapshot covers 2026 JP ONLY
        FileHolidaySnapshotStore(File(dir, "holiday_snapshot_v1.txt")).writeAtomically(
            providerSnapshot(country = "JP", year = 2026, holidayDates = setOf(thuJan1, monJan12))
        )

        val activeCountry = AtomicReference("US")
        val repo = createOfflineRepository(
            filesDir = dir,
            settingsProvider = { AppSettings(holidayAutoSkipEnabled = true, holidayCountryCode = activeCountry.get()) }
        )

        // 1. Country switched to US while JP snapshot remains cached -> uniform COUNTRY_MISMATCH across full evaluation window
        val excludeThuAlarm = repeatingAlarm(id = 628L, repeatDays = setOf(DayOfWeek.THURSDAY), skipOnHolidays = true)
        val holidayOnlyAlarm = repeatingAlarm(id = 629L, repeatDays = emptySet())
        val usWindow = repo.captureAuthorityWindowForEvaluation(
            evaluationEpochMs = nowJan1At0600Ms,
            evaluationZoneId = tokyoZone,
            alarms = listOf(excludeThuAlarm, holidayOnlyAlarm)
        )
        assertEquals("US", usWindow.configuredCountry)
        for (offset in 0L until 100L) {
            val c = usWindow.classify(thuJan1.plusDays(offset))
            assertEquals(HolidayClassificationKind.UNKNOWN, c.kind)
            assertEquals(HolidayUnknownReason.COUNTRY_MISMATCH, c.unknownReason)
        }
        val excludeUnderMismatch = evaluator.evaluateWithHolidayClassifier(
            alarm = excludeThuAlarm,
            fromEpochMs = nowJan1At0600Ms,
            holidayClassifier = usWindow.asLocalHolidayClassifier(),
            evaluationZoneId = tokyoZone
        )
        assertEquals(epochMillis(thuJan1, LocalTime.of(7, 30), tokyoZone), excludeUnderMismatch!!.targetTriggerTimeEpochMs)

        val includeUnderMismatch = evaluator.evaluateWithHolidayClassifier(
            alarm = holidayOnlyAlarm,
            fromEpochMs = nowJan1At0600Ms,
            holidayClassifier = usWindow.asLocalHolidayClassifier(),
            holidayPolicyOverride = HolidayPolicy.INCLUDE,
            evaluationZoneId = tokyoZone
        )
        assertEquals(OccurrenceDecision.VALID_NO_CURRENT_OCCURRENCE, includeUnderMismatch!!.decision)

        // 2. Year rollover window (2026-12-28 .. 2027-01-15) with JP configured and 2026-only snapshot:
        //    2026 dates are WORKDAY (PROVIDER); 2027 dates are UNKNOWN (YEAR_NOT_COVERED)
        activeCountry.set("JP")
        val dec28 = LocalDate.of(2026, 12, 28)
        val jan1NextYear = LocalDate.of(2027, 1, 1) // Friday
        val rolloverWindow = repo.captureAuthorityWindow(dec28, LocalDate.of(2027, 1, 15))
        assertEquals(HolidayClassificationKind.WORKDAY, rolloverWindow.classify(dec28).kind)
        assertEquals(HolidayClassificationKind.UNKNOWN, rolloverWindow.classify(jan1NextYear).kind)
        assertEquals(HolidayUnknownReason.YEAR_NOT_COVERED, rolloverWindow.classify(jan1NextYear).unknownReason)

        // Dates outside captured window bounds also fail safe to UNKNOWN
        assertEquals(HolidayClassificationKind.UNKNOWN, rolloverWindow.classify(dec28.minusDays(1)).kind)
        assertEquals(HolidayClassificationKind.UNKNOWN, rolloverWindow.classify(LocalDate.of(2027, 1, 16)).kind)
    }

    @Test
    fun t29_manualOverridePrecedenceAndInterfaceFreeze_underConcurrentMapMutation() = runTest {
        assertTrue(ManualHolidayOverrideSource.NONE.snapshotOverrides(thuJan1, thuJan1.plusDays(99)).isEmpty())

        val dir = tempFolder.newFolder("kno159_t29")
        FileHolidaySnapshotStore(File(dir, "holiday_snapshot_v1.txt")).writeAtomically(
            providerSnapshot(country = "JP", holidayDates = setOf(thuJan1, monJan12))
        )

        val backingOverrides = mutableMapOf(
            thuJan1 to ManualHolidayOverride.FORCE_WORKDAY,
            friJan2 to ManualHolidayOverride.FORCE_HOLIDAY
        )
        val activeCountry = AtomicReference("JP")
        val repo = createOfflineRepository(
            filesDir = dir,
            settingsProvider = { AppSettings(holidayAutoSkipEnabled = false, holidayCountryCode = activeCountry.get()) },
            manualOverrideSource = ManualHolidayOverrideSource { date ->
                backingOverrides[date] ?: ManualHolidayOverride.AUTO
            }
        )

        val capturedWindow = repo.captureAuthorityWindow(thuJan1, thuJan1.plusDays(30))

        // Mutate backing overrides and country AFTER window capture
        backingOverrides[thuJan1] = ManualHolidayOverride.AUTO
        backingOverrides[friJan2] = ManualHolidayOverride.FORCE_WORKDAY
        backingOverrides[monJan12] = ManualHolidayOverride.FORCE_WORKDAY
        backingOverrides[satFeb14] = ManualHolidayOverride.FORCE_HOLIDAY
        activeCountry.set("")

        // Frozen window preserves exact captured manual precedence and provider truth
        assertEquals(HolidayClassificationKind.WORKDAY, capturedWindow.classify(thuJan1).kind)
        assertEquals(HolidayClassificationSource.MANUAL_OVERRIDE, capturedWindow.classify(thuJan1).source)

        assertEquals(HolidayClassificationKind.HOLIDAY, capturedWindow.classify(friJan2).kind)
        assertEquals(HolidayClassificationSource.MANUAL_OVERRIDE, capturedWindow.classify(friJan2).source)

        assertEquals(HolidayClassificationKind.HOLIDAY, capturedWindow.classify(monJan12).kind)
        assertEquals(HolidayClassificationSource.PROVIDER, capturedWindow.classify(monJan12).source)

        // satFeb14 is outside [thuJan1, thuJan1+30] -> fails safe to UNKNOWN
        assertEquals(HolidayClassificationKind.UNKNOWN, capturedWindow.classify(satFeb14).kind)
    }

    @Test
    fun t30_clockAdvancingAcrossFreshnessTtlDuringEvaluation_preservesUniformWindowFreshness() = runTest {
        val dir = tempFolder.newFolder("kno159_t30")
        FileHolidaySnapshotStore(File(dir, "holiday_snapshot_v1.txt")).writeAtomically(
            providerSnapshot(
                country = "JP",
                fetchedAtMillis = fetchedFreshEpochMs,
                holidayDates = setOf(thuJan1, monFeb23)
            )
        )

        // Start 1ms before TTL expiration; advance by +10s on every clockMillis() call
        val clockCursor = AtomicLong(fetchedFreshEpochMs + HolidayClassifier.PROVIDER_FRESHNESS_TTL_MILLIS - 1L)
        val clockSamples = AtomicInteger(0)
        val repo = createOfflineRepository(
            filesDir = dir,
            settingsProvider = { AppSettings(holidayAutoSkipEnabled = true, holidayCountryCode = "JP") },
            clockMillis = {
                clockSamples.incrementAndGet()
                clockCursor.getAndAdd(10_000L)
            }
        )

        val window = repo.captureAuthorityWindow(thuJan1, thuJan1.plusDays(99))
        assertEquals("Clock must be sampled once per captured window", 1, clockSamples.get())

        val firstDay = window.classify(thuJan1)
        val day53 = window.classify(monFeb23)
        val day99 = window.classify(thuJan1.plusDays(99))
        assertEquals(HolidayProviderFreshness.FRESH, firstDay.freshness)
        assertEquals(HolidayProviderFreshness.FRESH, day53.freshness)
        assertEquals(HolidayProviderFreshness.FRESH, day99.freshness)
    }

    @Test
    fun t31_materialDeltaWindowAlignment_coversFixedTimezoneAlarmsAcrossUtcDateBoundary() = runTest {
        // 2026-01-01T16:00:00Z = 2026-01-02 01:00 Asia/Tokyo, 2026-01-01 11:00 America/New_York
        val crossDayUtcEpochMs = Instant.parse("2026-01-01T16:00:00Z").toEpochMilli()

        val fixedNyExcludeAlarm = Alarm(
            id = 631L,
            hour = 12,
            minute = 0,
            isEnabled = true,
            scheduleKind = Alarm.SCHEDULE_KIND_REPEATING,
            repeatDays = setOf(DayOfWeek.THURSDAY, DayOfWeek.FRIDAY),
            skipOnHolidays = true,
            timezonePolicy = Alarm.TIMEZONE_POLICY_FIXED,
            fixedTimezoneId = "America/New_York",
            generation = 3,
            revision = 1,
            nextTriggerTime = epochMillis(thuJan1, LocalTime.of(12, 0), nyZone)
        )

        val dir = tempFolder.newFolder("kno159_t31")
        FileHolidaySnapshotStore(File(dir, "holiday_snapshot_v1.txt")).writeAtomically(
            providerSnapshot(country = "US", holidayDates = setOf(thuJan1))
        )
        val repo = createOfflineRepository(
            filesDir = dir,
            settingsProvider = { AppSettings(holidayAutoSkipEnabled = true, holidayCountryCode = "US") },
            clockMillis = { crossDayUtcEpochMs }
        )

        // Evaluation zone is Asia/Tokyo (already 2026-01-02!), while fixedNyExcludeAlarm is still on 2026-01-01!
        val capturedWindow = repo.captureAuthorityWindowForEvaluation(
            evaluationEpochMs = crossDayUtcEpochMs,
            evaluationZoneId = tokyoZone,
            alarms = listOf(fixedNyExcludeAlarm)
        )
        assertEquals(thuJan1, capturedWindow.startDate)
        assertEquals(HolidayClassificationKind.HOLIDAY, capturedWindow.classify(thuJan1).kind)

        val decision = materialDetector.detectMaterialChange(
            previousClassifier = LocalHolidayClassifier.ALL_UNKNOWN,
            currentClassifier = capturedWindow.asLocalHolidayClassifier(),
            evaluationEpochMs = crossDayUtcEpochMs,
            evaluationZoneId = tokyoZone,
            alarms = listOf(
                AlarmHolidayEvaluationInput(
                    alarm = fixedNyExcludeAlarm,
                    allowHidePersonalRuntimeFeatures = true
                )
            )
        )

        assertEquals(HolidayTruthChangeOutcome.MATERIAL_CHANGE_DETECTED, decision.outcome)
        assertTrue(decision.shouldDispatchReconcile)
        assertNotEquals(decision.previousSignature, decision.currentSignature)
        assertTrue(thuJan1 in decision.holidayBoundaryChangedDates)
        assertEquals(listOf(631L), decision.affectedAlarms.map { it.alarmId })
        assertEquals(
            epochMillis(friJan2, LocalTime.of(12, 0), nyZone),
            decision.affectedAlarms.single().currentEvaluation!!.targetTriggerTimeEpochMs
        )
    }

    @Test
    fun t32_materialDeltaPrecision_unaffectedAlarmsElapsedSameDayAndOverrideInvalidation() {
        val sunJan4 = LocalDate.of(2026, 1, 4)
        val sunHolidayClassifier = LocalHolidayClassifier.fromSnapshot(
            snapshot = providerSnapshot(country = "JP", holidayDates = setOf(sunJan4)),
            configuredCountry = "JP",
            nowMillis = nowJan1At0600Ms
        )

        // Subcase A: Sunday becomes HOLIDAY, but alarms are Mon-Fri EXCLUDE (701) and Mon-Sun FOLLOW_BASE (702)
        val monFriExclude = repeatingAlarm(
            id = 701L,
            repeatDays = setOf(
                DayOfWeek.MONDAY,
                DayOfWeek.TUESDAY,
                DayOfWeek.WEDNESDAY,
                DayOfWeek.THURSDAY,
                DayOfWeek.FRIDAY
            ),
            skipOnHolidays = true,
            nextTriggerTime = epochMillis(thuJan1, LocalTime.of(7, 30), tokyoZone)
        )
        val allDaysFollowBase = repeatingAlarm(
            id = 702L,
            repeatDays = DayOfWeek.entries.toSet(),
            skipOnHolidays = false,
            nextTriggerTime = epochMillis(thuJan1, LocalTime.of(7, 30), tokyoZone)
        )

        val unaffectedDecision = materialDetector.detectMaterialChange(
            previousClassifier = LocalHolidayClassifier.ALL_UNKNOWN,
            currentClassifier = sunHolidayClassifier,
            evaluationEpochMs = nowJan1At0600Ms,
            evaluationZoneId = tokyoZone,
            alarms = listOf(
                AlarmHolidayEvaluationInput(alarm = monFriExclude),
                AlarmHolidayEvaluationInput(alarm = allDaysFollowBase)
            )
        )
        assertEquals(HolidayTruthChangeOutcome.MATERIAL_CHANGE_DETECTED, unaffectedDecision.outcome)
        assertTrue(unaffectedDecision.hasMaterialHolidayChange)
        assertTrue(unaffectedDecision.affectedAlarms.isEmpty())
        assertFalse(
            "shouldDispatchReconcile must be false when zero evaluated alarms are impacted",
            unaffectedDecision.shouldDispatchReconcile
        )

        // Subcase B: Evaluation at 2026-01-01 09:00 JST (after 07:30 Base time)
        // - Alarm 703 (07:30 Thu/Fri EXCLUDE, no override): 2026-01-01 07:30 already elapsed -> unaffected!
        // - Alarm 704 (07:30 Thu/Fri EXCLUDE, same-day TYPE_TIME_OVERRIDE to 11:00 JST): affected & invalidated!
        val evalJan1At0900Ms = epochMillis(thuJan1, LocalTime.of(9, 0), tokyoZone)
        val thu0730BaseMs = epochMillis(thuJan1, LocalTime.of(7, 30), tokyoZone)
        val thu1100OverrideMs = epochMillis(thuJan1, LocalTime.of(11, 0), tokyoZone)
        val fri0730BaseMs = epochMillis(friJan2, LocalTime.of(7, 30), tokyoZone)

        val elapsedAlarm703 = repeatingAlarm(
            id = 703L,
            repeatDays = setOf(DayOfWeek.THURSDAY, DayOfWeek.FRIDAY),
            skipOnHolidays = true,
            nextTriggerTime = fri0730BaseMs
        )
        val overriddenAlarm704 = repeatingAlarm(
            id = 704L,
            repeatDays = setOf(DayOfWeek.THURSDAY, DayOfWeek.FRIDAY),
            skipOnHolidays = true,
            nextTriggerTime = thu1100OverrideMs
        )
        val override704 = ScheduleException(
            alarmId = 704L,
            baseTriggerTime = thu0730BaseMs,
            exceptionType = ScheduleException.TYPE_TIME_OVERRIDE,
            overrideTriggerTime = thu1100OverrideMs,
            generation = overriddenAlarm704.generation,
            revision = overriddenAlarm704.revision
        )

        val jan1HolidayClassifier = LocalHolidayClassifier.fromSnapshot(
            snapshot = providerSnapshot(country = "JP", holidayDates = setOf(thuJan1)),
            configuredCountry = "JP",
            nowMillis = evalJan1At0900Ms
        )

        val sameDayDecision = materialDetector.detectMaterialChange(
            previousClassifier = LocalHolidayClassifier.ALL_UNKNOWN,
            currentClassifier = jan1HolidayClassifier,
            evaluationEpochMs = evalJan1At0900Ms,
            evaluationZoneId = tokyoZone,
            alarms = listOf(
                AlarmHolidayEvaluationInput(alarm = elapsedAlarm703),
                AlarmHolidayEvaluationInput(alarm = overriddenAlarm704, exceptions = listOf(override704))
            )
        )

        assertEquals(HolidayTruthChangeOutcome.MATERIAL_CHANGE_DETECTED, sameDayDecision.outcome)
        assertTrue(sameDayDecision.shouldDispatchReconcile)
        assertEquals(listOf(704L), sameDayDecision.affectedAlarms.map { it.alarmId })

        val delta704 = sameDayDecision.affectedAlarms.single()
        assertEquals(
            AlarmOccurrenceId(704L, thu0730BaseMs, overriddenAlarm704.generation),
            delta704.staleOccurrenceIdToInvalidate
        )
        assertEquals(OccurrenceDecision.NORMAL_TRIGGER, delta704.currentEvaluation!!.decision)
        assertFalse(delta704.currentEvaluation!!.isExceptionApplied)
        assertEquals(fri0730BaseMs, delta704.currentEvaluation!!.targetTriggerTimeEpochMs)
    }
}
