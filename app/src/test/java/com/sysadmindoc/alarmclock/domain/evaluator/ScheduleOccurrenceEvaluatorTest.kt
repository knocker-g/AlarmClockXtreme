package com.sysadmindoc.alarmclock.domain.evaluator

import com.sysadmindoc.alarmclock.data.local.entity.ScheduleException
import com.sysadmindoc.alarmclock.data.model.Alarm
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

class ScheduleOccurrenceEvaluatorTest {

    private val evaluator = ScheduleOccurrenceEvaluator()
    private val zoneId = ZoneId.of("America/New_York")
    // Reference evaluation timestamp: Wednesday 2026-10-28 06:00:00 local time
    private val fromTimeMs = LocalDate.of(2026, 10, 28)
        .atTime(LocalTime.of(6, 0))
        .atZone(zoneId)
        .toInstant()
        .toEpochMilli()

    @Test
    fun `1 ONE_TIME specificDate missing, past, and future evaluation`() {
        // Future ONE_TIME
        val futureOneTime = Alarm(
            id = 1L,
            hour = 7,
            minute = 0,
            isEnabled = true,
            scheduleKind = Alarm.SCHEDULE_KIND_ONE_TIME,
            specificDate = "2026-10-28"
        )
        val evaluatedFuture = evaluator.evaluateOccurrence(futureOneTime, fromTimeMs, evaluationZoneId = zoneId)
        assertNotNull(evaluatedFuture)
        assertEquals(OccurrenceDecision.NORMAL_TRIGGER, evaluatedFuture!!.decision)

        // Past ONE_TIME (expired specificDate 2026-10-25 relative to 2026-10-28)
        val pastOneTime = Alarm(
            id = 2L,
            hour = 7,
            minute = 0,
            isEnabled = true,
            scheduleKind = Alarm.SCHEDULE_KIND_ONE_TIME,
            specificDate = "2026-10-25"
        )
        val evaluatedPast = evaluator.evaluateOccurrence(pastOneTime, fromTimeMs, evaluationZoneId = zoneId)
        assertNotNull(evaluatedPast)
        assertEquals("Past ONE_TIME MUST NOT rollover to tomorrow", OccurrenceDecision.VALID_NO_CURRENT_OCCURRENCE, evaluatedPast!!.decision)

        // Missing specificDate on ONE_TIME
        val missingDateOneTime = Alarm(
            id = 3L,
            hour = 7,
            minute = 0,
            isEnabled = true,
            scheduleKind = Alarm.SCHEDULE_KIND_ONE_TIME,
            specificDate = ""
        )
        val evaluatedMissing = evaluator.evaluateOccurrence(missingDateOneTime, fromTimeMs, evaluationZoneId = zoneId)
        assertNotNull(evaluatedMissing)
        assertEquals(OccurrenceDecision.INVALID_SCHEDULE_NEEDS_REPAIR, evaluatedMissing!!.decision)
    }

    @Test
    fun `2 Explicit REPEATING vs legacy inferred scheduleKind distinction`() {
        val explicitRepeating = Alarm(
            id = 4L,
            hour = 8,
            minute = 0,
            isEnabled = true,
            scheduleKind = Alarm.SCHEDULE_KIND_REPEATING,
            repeatDays = setOf(DayOfWeek.WEDNESDAY)
        )
        val evaluated = evaluator.evaluateOccurrence(explicitRepeating, fromTimeMs, evaluationZoneId = zoneId)
        assertNotNull(evaluated)
        assertEquals(OccurrenceDecision.NORMAL_TRIGGER, evaluated!!.decision)
        assertEquals(Alarm.SCHEDULE_KIND_REPEATING, explicitRepeating.scheduleKind)
    }

    @Test
    fun `3 Holiday-only INCLUDE generates confirmed Holiday candidate and returns VALID_NO_CURRENT_OCCURRENCE on NOT_HOLIDAY`() {
        val holidayOnlyAlarm = Alarm(
            id = 5L,
            hour = 7,
            minute = 0,
            isEnabled = true,
            scheduleKind = Alarm.SCHEDULE_KIND_REPEATING,
            repeatDays = emptySet()
        )

        // Wednesday Oct 28 is IS_HOLIDAY -> generates candidate
        val evaluatedHoliday = evaluator.evaluateOccurrence(
            holidayOnlyAlarm,
            fromTimeMs,
            holidaySnapshotProvider = { date ->
                if (date == LocalDate.of(2026, 10, 28)) HolidaySnapshot(state = HolidayState.IS_HOLIDAY, holidayName = "Thanksgiving") else HolidaySnapshot(state = HolidayState.NOT_HOLIDAY)
            },
            holidayPolicyOverride = HolidayPolicy.INCLUDE,
            evaluationZoneId = zoneId
        )

        assertNotNull(evaluatedHoliday)
        assertEquals(OccurrenceDecision.NORMAL_TRIGGER, evaluatedHoliday!!.decision)
        assertEquals(HolidayState.IS_HOLIDAY, evaluatedHoliday.holidayState)

        // Wednesday Oct 28 is NOT_HOLIDAY -> MUST NOT generate occurrence, MUST return VALID_NO_CURRENT_OCCURRENCE
        val evaluatedNotHoliday = evaluator.evaluateOccurrence(
            holidayOnlyAlarm,
            fromTimeMs,
            holidaySnapshotProvider = { HolidaySnapshot(state = HolidayState.NOT_HOLIDAY) },
            holidayPolicyOverride = HolidayPolicy.INCLUDE,
            evaluationZoneId = zoneId
        )

        assertNotNull(evaluatedNotHoliday)
        assertEquals("Holiday-only on NOT_HOLIDAY MUST NOT generate trigger candidate", OccurrenceDecision.VALID_NO_CURRENT_OCCURRENCE, evaluatedNotHoliday!!.decision)
    }

    @Test
    fun `4 Holiday INCLUDE policy generates non-Base day Holiday occurrence on Monday IS_HOLIDAY`() {
        // Base schedule: Saturday & Sunday at 09:00
        val weekendAlarm = Alarm(
            id = 6L,
            hour = 9,
            minute = 0,
            isEnabled = true,
            scheduleKind = Alarm.SCHEDULE_KIND_REPEATING,
            repeatDays = setOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY)
        )

        // Evaluation time: Sunday Oct 25 10:00:00 (after Sunday 09:00 has passed)
        val sun1000Ms = LocalDate.of(2026, 10, 25)
            .atTime(LocalTime.of(10, 0))
            .atZone(zoneId)
            .toInstant()
            .toEpochMilli()

        // Monday Oct 26 is IS_HOLIDAY ("Columbus Day")
        val evaluated = evaluator.evaluateOccurrence(
            weekendAlarm,
            sun1000Ms,
            holidaySnapshotProvider = { date ->
                if (date == LocalDate.of(2026, 10, 26)) HolidaySnapshot(state = HolidayState.IS_HOLIDAY, holidayName = "Columbus Day") else HolidaySnapshot(state = HolidayState.NOT_HOLIDAY)
            },
            holidayPolicyOverride = HolidayPolicy.INCLUDE,
            evaluationZoneId = zoneId
        )

        assertNotNull(evaluated)
        assertEquals(OccurrenceDecision.NORMAL_TRIGGER, evaluated!!.decision)
        val expectedMon0900Ms = LocalDate.of(2026, 10, 26)
            .atTime(LocalTime.of(9, 0))
            .atZone(zoneId)
            .toInstant()
            .toEpochMilli()
        assertEquals("Holiday INCLUDE on Monday IS_HOLIDAY MUST generate Monday candidate", expectedMon0900Ms, evaluated.baseTriggerTimeEpochMs)
    }

    @Test
    fun `5 Holiday EXCLUDE advances to next valid future occurrence when base occurrence is excluded`() {
        val repeatingAlarm = Alarm(
            id = 7L,
            hour = 7,
            minute = 0,
            isEnabled = true,
            scheduleKind = Alarm.SCHEDULE_KIND_REPEATING,
            repeatDays = setOf(DayOfWeek.WEDNESDAY),
            skipOnHolidays = true
        )

        // Wednesday Oct 28 is IS_HOLIDAY, Wednesday Nov 4 is NOT_HOLIDAY
        val evaluated = evaluator.evaluateOccurrence(
            repeatingAlarm,
            fromTimeMs,
            holidaySnapshotProvider = { date ->
                if (date == LocalDate.of(2026, 10, 28)) HolidaySnapshot(state = HolidayState.IS_HOLIDAY) else HolidaySnapshot(state = HolidayState.NOT_HOLIDAY)
            },
            evaluationZoneId = zoneId
        )

        assertNotNull(evaluated)
        assertEquals(OccurrenceDecision.NORMAL_TRIGGER, evaluated!!.decision)
        // Verify target trigger time advanced to NEXT Wednesday (Nov 4)
        val expectedNov4Ms = LocalDate.of(2026, 11, 4)
            .atTime(LocalTime.of(7, 0))
            .atZone(zoneId)
            .toInstant()
            .toEpochMilli()
        assertEquals(expectedNov4Ms, evaluated.baseTriggerTimeEpochMs)
    }

    @Test
    fun `6 Time Override on Wednesday 07_00 base occurrence is maintained at Wednesday 08_00 for Wednesday 09_30 target`() {
        val repeatingAlarm = Alarm(
            id = 8L,
            hour = 7,
            minute = 0,
            isEnabled = true,
            scheduleKind = Alarm.SCHEDULE_KIND_REPEATING,
            repeatDays = setOf(DayOfWeek.WEDNESDAY),
            generation = 1,
            revision = 1
        )

        val wedBase0700Ms = LocalDate.of(2026, 10, 28)
            .atTime(LocalTime.of(7, 0))
            .atZone(zoneId)
            .toInstant()
            .toEpochMilli()

        val wedOverride0930Ms = LocalDate.of(2026, 10, 28)
            .atTime(LocalTime.of(9, 30))
            .atZone(zoneId)
            .toInstant()
            .toEpochMilli()

        val wed0800FromMs = LocalDate.of(2026, 10, 28)
            .atTime(LocalTime.of(8, 0))
            .atZone(zoneId)
            .toInstant()
            .toEpochMilli()

        val overrideException = ScheduleException(
            alarmId = repeatingAlarm.id,
            baseTriggerTime = wedBase0700Ms,
            exceptionType = ScheduleException.TYPE_TIME_OVERRIDE,
            overrideTriggerTime = wedOverride0930Ms,
            generation = 1,
            revision = 1
        )

        // Evaluate at 08:00 (after 07:00 base, but before 09:30 override)
        val evaluated = evaluator.evaluateOccurrence(
            repeatingAlarm,
            wed0800FromMs,
            exceptions = listOf(overrideException),
            evaluationZoneId = zoneId
        )

        assertNotNull(evaluated)
        assertEquals("At 08:00, evaluator MUST return Wednesday 09:30 TIME_OVERRIDDEN candidate", OccurrenceDecision.TIME_OVERRIDDEN, evaluated!!.decision)
        assertEquals(wedBase0700Ms, evaluated.baseTriggerTimeEpochMs)
        assertEquals(wedOverride0930Ms, evaluated.targetTriggerTimeEpochMs)
    }

    @Test
    fun `7 Expired earlier Time Override on Wednesday 09_00 base occurrence prevents base resurrection and advances to next week`() {
        val repeatingAlarm = Alarm(
            id = 9L,
            hour = 9,
            minute = 0,
            isEnabled = true,
            scheduleKind = Alarm.SCHEDULE_KIND_REPEATING,
            repeatDays = setOf(DayOfWeek.WEDNESDAY),
            generation = 1,
            revision = 1
        )

        val wedBase0900Ms = LocalDate.of(2026, 10, 28)
            .atTime(LocalTime.of(9, 0))
            .atZone(zoneId)
            .toInstant()
            .toEpochMilli()

        // Override time: Wednesday 07:00 (earlier than 09:00 base)
        val wedOverride0700Ms = LocalDate.of(2026, 10, 28)
            .atTime(LocalTime.of(7, 0))
            .atZone(zoneId)
            .toInstant()
            .toEpochMilli()

        // Evaluated at Wednesday 08:00 (after 07:00 override, before 09:00 base)
        val wed0800FromMs = LocalDate.of(2026, 10, 28)
            .atTime(LocalTime.of(8, 0))
            .atZone(zoneId)
            .toInstant()
            .toEpochMilli()

        val expiredOverrideException = ScheduleException(
            alarmId = repeatingAlarm.id,
            baseTriggerTime = wedBase0900Ms,
            exceptionType = ScheduleException.TYPE_TIME_OVERRIDE,
            overrideTriggerTime = wedOverride0700Ms,
            generation = 1,
            revision = 1
        )

        val evaluated = evaluator.evaluateOccurrence(
            repeatingAlarm,
            wed0800FromMs,
            exceptions = listOf(expiredOverrideException),
            evaluationZoneId = zoneId
        )

        assertNotNull(evaluated)
        // Wednesday occurrence was overridden to 07:00 and HAS EXPIRED. Evaluator MUST NOT resurrect 09:00 base occurrence!
        val expectedNov4Ms = LocalDate.of(2026, 11, 4)
            .atTime(LocalTime.of(9, 0))
            .atZone(zoneId)
            .toInstant()
            .toEpochMilli()

        assertEquals("Expired Time Override MUST NOT resurrect base occurrence; MUST advance to Next Wednesday", expectedNov4Ms, evaluated!!.baseTriggerTimeEpochMs)
        assertEquals(OccurrenceDecision.NORMAL_TRIGGER, evaluated.decision)
    }

    @Test
    fun `8 Revision changes preserve Occurrence Identity key`() {
        val baseTimeMs = 1700000000000L
        val alarmRev1 = Alarm(id = 42L, generation = 2, revision = 1, label = "Original")
        val alarmRev5 = alarmRev1.copy(revision = 5, label = "Edited Volume")

        val occurrence1 = com.sysadmindoc.alarmclock.data.model.AlarmOccurrenceId.fromAlarm(alarmRev1, baseTimeMs)
        val occurrence5 = com.sysadmindoc.alarmclock.data.model.AlarmOccurrenceId.fromAlarm(alarmRev5, baseTimeMs)

        assertEquals("Revision changes MUST keep occurrence key identical", occurrence1.key, occurrence5.key)
        assertEquals("alarm_42_gen_2_base_1700000000000", occurrence1.key)
    }

    @Test
    fun `9 Consumed occurrence is skipped on clock rollback`() {
        val alarm = Alarm(
            id = 10L,
            hour = 7,
            minute = 0,
            isEnabled = true,
            scheduleKind = Alarm.SCHEDULE_KIND_REPEATING,
            repeatDays = setOf(DayOfWeek.WEDNESDAY),
            generation = 1,
            revision = 1
        )

        val wedBase0700Ms = LocalDate.of(2026, 10, 28)
            .atTime(LocalTime.of(7, 0))
            .atZone(zoneId)
            .toInstant()
            .toEpochMilli()

        val consumedKey = "alarm_10_gen_1_base_$wedBase0700Ms"

        // Evaluate on clock rollback (evaluated at 06:30 after wedBase0700Ms was consumed)
        val evaluated = evaluator.evaluateOccurrence(
            alarm,
            fromTimeMs,
            consumedOccurrenceKeys = setOf(consumedKey),
            evaluationZoneId = zoneId
        )

        assertNotNull(evaluated)
        // Evaluator MUST advance to next Wednesday (Nov 4) because Oct 28 occurrence was already consumed
        val expectedNov4Ms = LocalDate.of(2026, 11, 4)
            .atTime(LocalTime.of(7, 0))
            .atZone(zoneId)
            .toInstant()
            .toEpochMilli()
        assertEquals(expectedNov4Ms, evaluated!!.baseTriggerTimeEpochMs)
        assertEquals(OccurrenceDecision.NORMAL_TRIGGER, evaluated.decision)
    }

    @Test
    fun `10 ONE_TIME DST gap in America_New_York resolves to gap end at 03_00`() {
        val nyZone = ZoneId.of("America/New_York")

        // 2026 Spring Forward DST Gap in NY: 2026-03-08 02:00 -> 03:00 (02:30 wall clock does NOT exist)
        val dstGapAlarm = Alarm(
            id = 11L,
            hour = 2,
            minute = 30,
            isEnabled = true,
            scheduleKind = Alarm.SCHEDULE_KIND_ONE_TIME,
            specificDate = "2026-03-08"
        )

        val fromSpringMs = LocalDate.of(2026, 3, 8)
            .atTime(LocalTime.of(1, 0))
            .atZone(nyZone)
            .toInstant()
            .toEpochMilli()

        val evaluatedDst = evaluator.evaluateOccurrence(dstGapAlarm, fromSpringMs, evaluationZoneId = nyZone)

        assertNotNull(evaluatedDst)
        assertEquals(OccurrenceDecision.NORMAL_TRIGGER, evaluatedDst!!.decision)

        // Canonical DST Gap resolution: 02:30 during Spring Forward gap resolves to 03:00 (gap end instant)
        val expected0300Ms = LocalDate.of(2026, 3, 8)
            .atTime(LocalTime.of(3, 0))
            .atZone(nyZone)
            .toInstant()
            .toEpochMilli()

        assertEquals("DST Gap 02:30 MUST resolve to 03:00 (gap end instant)", expected0300Ms, evaluatedDst.targetTriggerTimeEpochMs)
    }

    @Test
    fun `11 Invalid shiftPattern key returns INVALID_SCHEDULE_NEEDS_REPAIR`() {
        val invalidShiftAlarm = Alarm(
            id = 12L,
            hour = 7,
            minute = 0,
            isEnabled = true,
            scheduleKind = Alarm.SCHEDULE_KIND_REPEATING,
            shiftPattern = "2D2N2O_INVALID",
            shiftPatternStartDate = "2026-01-01"
        )

        val evaluated = evaluator.evaluateOccurrence(
            invalidShiftAlarm,
            fromTimeMs,
            evaluationZoneId = zoneId,
            allowHidePersonalRuntimeFeatures = true
        )

        assertNotNull(evaluated)
        assertEquals(OccurrenceDecision.INVALID_SCHEDULE_NEEDS_REPAIR, evaluated!!.decision)
    }

    @Test
    fun `12 Real ScheduleException TYPE_CANCEL and UNKNOWN_TYPE both return UNSUPPORTED_EXCEPTION_TYPE`() {
        val alarm = Alarm(
            id = 13L,
            hour = 7,
            minute = 0,
            repeatDays = setOf(DayOfWeek.WEDNESDAY),
            scheduleKind = Alarm.SCHEDULE_KIND_REPEATING,
            isEnabled = true,
            generation = 1,
            revision = 1
        )
        val baseOccurrence = evaluator.evaluateOccurrence(alarm, fromTimeMs, evaluationZoneId = zoneId)!!

        // 1. Real ScheduleException.TYPE_CANCEL ("CANCEL")
        val cancelException = ScheduleException(
            alarmId = alarm.id,
            baseTriggerTime = baseOccurrence.baseTriggerTimeEpochMs,
            exceptionType = ScheduleException.TYPE_CANCEL,
            generation = 1,
            revision = 1
        )

        val evaluatedCancel = evaluator.evaluateOccurrence(alarm, fromTimeMs, exceptions = listOf(cancelException), evaluationZoneId = zoneId)
        assertNotNull(evaluatedCancel)
        assertEquals("TYPE_CANCEL MUST return UNSUPPORTED_EXCEPTION_TYPE", OccurrenceDecision.UNSUPPORTED_EXCEPTION_TYPE, evaluatedCancel!!.decision)
        assertTrue(evaluatedCancel.isExceptionApplied)
        assertEquals(ScheduleException.TYPE_CANCEL, evaluatedCancel.appliedExceptionType)

        // 2. Unknown exception type ("UNKNOWN_TYPE")
        val unknownException = ScheduleException(
            alarmId = alarm.id,
            baseTriggerTime = baseOccurrence.baseTriggerTimeEpochMs,
            exceptionType = "UNKNOWN_TYPE",
            generation = 1,
            revision = 1
        )

        val evaluatedUnknown = evaluator.evaluateOccurrence(alarm, fromTimeMs, exceptions = listOf(unknownException), evaluationZoneId = zoneId)
        assertNotNull(evaluatedUnknown)
        assertEquals("Unknown exception type MUST return UNSUPPORTED_EXCEPTION_TYPE", OccurrenceDecision.UNSUPPORTED_EXCEPTION_TYPE, evaluatedUnknown!!.decision)
        assertTrue(evaluatedUnknown.isExceptionApplied)
        assertEquals("UNKNOWN_TYPE", evaluatedUnknown.appliedExceptionType)
    }

    @Test
    fun `13 Quick Alarm and Disabled Alarm are ignored`() {
        val quickAlarm = Alarm(id = 14L, hour = 7, minute = 0, kind = Alarm.KIND_QUICK, isEnabled = true)
        assertNull(evaluator.evaluateOccurrence(quickAlarm, fromTimeMs))

        val disabledAlarm = Alarm(id = 15L, hour = 7, minute = 0, isEnabled = false)
        assertNull(evaluator.evaluateOccurrence(disabledAlarm, fromTimeMs))
    }

    @Test
    fun `14 Unknown ScheduleKind returns INVALID_SCHEDULE_NEEDS_REPAIR`() {
        val unknownKindAlarm = Alarm(
            id = 16L,
            hour = 7,
            minute = 0,
            isEnabled = true,
            scheduleKind = "UNKNOWN_KIND_UNSUPPORTED"
        )

        val evaluated = evaluator.evaluateOccurrence(unknownKindAlarm, fromTimeMs, evaluationZoneId = zoneId)

        assertNotNull(evaluated)
        assertEquals(OccurrenceDecision.INVALID_SCHEDULE_NEEDS_REPAIR, evaluated!!.decision)
    }

    @Test
    fun `15 DST overlap in America_New_York resolves to earlier offset`() {
        val nyZone = ZoneId.of("America/New_York")

        // 2026 Fall Back DST Overlap in NY: 2026-11-01 01:30 repeats (EDT -04:00 vs EST -05:00)
        val dstOverlapAlarm = Alarm(
            id = 17L,
            hour = 1,
            minute = 30,
            isEnabled = true,
            scheduleKind = Alarm.SCHEDULE_KIND_ONE_TIME,
            specificDate = "2026-11-01"
        )

        val fromFallMs = LocalDate.of(2026, 11, 1)
            .atTime(LocalTime.of(0, 0))
            .atZone(nyZone)
            .toInstant()
            .toEpochMilli()

        val evaluatedOverlap = evaluator.evaluateOccurrence(dstOverlapAlarm, fromFallMs, evaluationZoneId = nyZone)

        assertNotNull(evaluatedOverlap)
        assertEquals(OccurrenceDecision.NORMAL_TRIGGER, evaluatedOverlap!!.decision)

        // Earlier offset EDT -04:00: 2026-11-01T01:30-04:00[America/New_York]
        val expectedEarlierOffsetMs = LocalDateTime.of(2026, 11, 1, 1, 30)
            .atZone(nyZone)
            .withEarlierOffsetAtOverlap()
            .toInstant()
            .toEpochMilli()

        assertEquals("DST Overlap MUST resolve to earlier offset (EDT -04:00)", expectedEarlierOffsetMs, evaluatedOverlap.targetTriggerTimeEpochMs)
    }

    @Test
    fun `16 Personal HIDE runtime boundary flags shiftPattern and FIXED timezone as INVALID_SCHEDULE_NEEDS_REPAIR`() {
        // Shift pattern alarm evaluated under Personal default mode (allowHidePersonalRuntimeFeatures = false)
        val shiftAlarm = Alarm(
            id = 18L,
            hour = 7,
            minute = 0,
            isEnabled = true,
            scheduleKind = Alarm.SCHEDULE_KIND_REPEATING,
            shiftPattern = "DDNNO",
            shiftPatternStartDate = "2026-01-01"
        )

        val evaluatedShift = evaluator.evaluateOccurrence(shiftAlarm, fromTimeMs, evaluationZoneId = zoneId)
        assertNotNull(evaluatedShift)
        assertEquals("Personal runtime mode MUST flag HIDE shiftPattern as INVALID_SCHEDULE_NEEDS_REPAIR", OccurrenceDecision.INVALID_SCHEDULE_NEEDS_REPAIR, evaluatedShift!!.decision)

        // Fixed timezone alarm evaluated under Personal default mode (allowHidePersonalRuntimeFeatures = false)
        val fixedZoneAlarm = Alarm(
            id = 19L,
            hour = 7,
            minute = 0,
            isEnabled = true,
            scheduleKind = Alarm.SCHEDULE_KIND_REPEATING,
            repeatDays = setOf(DayOfWeek.WEDNESDAY),
            timezonePolicy = Alarm.TIMEZONE_POLICY_FIXED,
            fixedTimezoneId = "Europe/London"
        )

        val evaluatedFixed = evaluator.evaluateOccurrence(fixedZoneAlarm, fromTimeMs, evaluationZoneId = zoneId)
        assertNotNull(evaluatedFixed)
        assertEquals("Personal runtime mode MUST flag HIDE FIXED timezone as INVALID_SCHEDULE_NEEDS_REPAIR", OccurrenceDecision.INVALID_SCHEDULE_NEEDS_REPAIR, evaluatedFixed!!.decision)
    }

    @Test
    fun `17 Contradictory REPEATING with specificDate under Personal mode returns INVALID_SCHEDULE_NEEDS_REPAIR`() {
        val mixedAlarm = Alarm(
            id = 20L,
            hour = 7,
            minute = 0,
            isEnabled = true,
            scheduleKind = Alarm.SCHEDULE_KIND_REPEATING,
            repeatDays = setOf(DayOfWeek.WEDNESDAY),
            specificDate = "2026-10-28"
        )

        val evaluated = evaluator.evaluateOccurrence(mixedAlarm, fromTimeMs, evaluationZoneId = zoneId)
        assertNotNull(evaluated)
        assertEquals("Contradictory REPEATING with specificDate in Personal mode MUST return INVALID_SCHEDULE_NEEDS_REPAIR", OccurrenceDecision.INVALID_SCHEDULE_NEEDS_REPAIR, evaluated!!.decision)
    }

    @Test
    fun `18 REPEATING with specificDate in compatibility mode passes through Exception pipeline`() {
        val mixedAlarm = Alarm(
            id = 21L,
            hour = 7,
            minute = 0,
            isEnabled = true,
            scheduleKind = Alarm.SCHEDULE_KIND_REPEATING,
            repeatDays = setOf(DayOfWeek.WEDNESDAY),
            specificDate = "2026-10-28",
            generation = 1,
            revision = 1
        )

        val wedBase0700Ms = LocalDate.of(2026, 10, 28)
            .atTime(LocalTime.of(7, 0))
            .atZone(zoneId)
            .toInstant()
            .toEpochMilli()

        val skipException = ScheduleException(
            alarmId = mixedAlarm.id,
            baseTriggerTime = wedBase0700Ms,
            exceptionType = ScheduleException.TYPE_SKIP,
            generation = 1,
            revision = 1
        )

        val evaluated = evaluator.evaluateOccurrence(
            mixedAlarm,
            fromTimeMs,
            exceptions = listOf(skipException),
            evaluationZoneId = zoneId,
            allowHidePersonalRuntimeFeatures = true
        )

        assertNotNull(evaluated)
        // When Oct 28 candidate is skipped, the resolver advances to the next valid candidate on Nov 4
        val expectedNov4Ms = LocalDate.of(2026, 11, 4)
            .atTime(LocalTime.of(7, 0))
            .atZone(zoneId)
            .toInstant()
            .toEpochMilli()
        assertEquals("Skipping Oct 28 candidate MUST advance resolver to Nov 4 candidate", expectedNov4Ms, evaluated!!.baseTriggerTimeEpochMs)
        assertEquals(OccurrenceDecision.NORMAL_TRIGGER, evaluated.decision)
    }

    @Test
    fun `19 Personal HIDE timing fields solarOffset, smartAlarm, and weatherEarly return INVALID_SCHEDULE_NEEDS_REPAIR`() {
        val solarAlarm = Alarm(id = 22L, hour = 7, minute = 0, isEnabled = true, scheduleKind = Alarm.SCHEDULE_KIND_REPEATING, repeatDays = setOf(DayOfWeek.WEDNESDAY), solarOffsetMinutes = 15)
        val evaluatedSolar = evaluator.evaluateOccurrence(solarAlarm, fromTimeMs, evaluationZoneId = zoneId)
        assertNotNull(evaluatedSolar)
        assertEquals(OccurrenceDecision.INVALID_SCHEDULE_NEEDS_REPAIR, evaluatedSolar!!.decision)

        val smartAlarm = Alarm(id = 23L, hour = 7, minute = 0, isEnabled = true, scheduleKind = Alarm.SCHEDULE_KIND_REPEATING, repeatDays = setOf(DayOfWeek.WEDNESDAY), smartAlarmEnabled = true)
        val evaluatedSmart = evaluator.evaluateOccurrence(smartAlarm, fromTimeMs, evaluationZoneId = zoneId)
        assertNotNull(evaluatedSmart)
        assertEquals(OccurrenceDecision.INVALID_SCHEDULE_NEEDS_REPAIR, evaluatedSmart!!.decision)

        val weatherAlarm = Alarm(id = 24L, hour = 7, minute = 0, isEnabled = true, scheduleKind = Alarm.SCHEDULE_KIND_REPEATING, repeatDays = setOf(DayOfWeek.WEDNESDAY), weatherEarlyMinutes = 10)
        val evaluatedWeather = evaluator.evaluateOccurrence(weatherAlarm, fromTimeMs, evaluationZoneId = zoneId)
        assertNotNull(evaluatedWeather)
        assertEquals(OccurrenceDecision.INVALID_SCHEDULE_NEEDS_REPAIR, evaluatedWeather!!.decision)
    }

    @Test
    fun `20 Default HolidaySnapshot state is UNKNOWN`() {
        val defaultSnapshot = HolidaySnapshot()
        assertEquals(HolidayState.UNKNOWN, defaultSnapshot.state)
        assertEquals(0L, defaultSnapshot.checkedAtEpochMs)
    }
}
