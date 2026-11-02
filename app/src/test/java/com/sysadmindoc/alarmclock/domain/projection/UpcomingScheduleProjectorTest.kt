package com.sysadmindoc.alarmclock.domain.projection

import com.sysadmindoc.alarmclock.data.local.entity.ScheduleAuthority
import com.sysadmindoc.alarmclock.data.local.entity.ScheduleException
import com.sysadmindoc.alarmclock.data.model.Alarm
import com.sysadmindoc.alarmclock.domain.evaluator.HolidayPolicy
import com.sysadmindoc.alarmclock.domain.evaluator.HolidaySnapshot
import com.sysadmindoc.alarmclock.domain.evaluator.HolidayState
import com.sysadmindoc.alarmclock.domain.evaluator.LocalHolidayClassifier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * Verification test matrix P01-P14 for UpcomingScheduleProjector (KNO-155 / KNO-34 Slice A).
 */
class UpcomingScheduleProjectorTest {

    private val projector = UpcomingScheduleProjector()
    private val zoneId = ZoneId.of("America/New_York")

    // Base test timestamp: Wednesday 2026-11-04 06:00:00 local time
    private val nowEpochMs = LocalDate.of(2026, 11, 4)
        .atTime(LocalTime.of(6, 0))
        .atZone(zoneId)
        .toInstant()
        .toEpochMilli()

    private fun localToEpoch(date: LocalDate, time: LocalTime, zone: ZoneId = zoneId): Long {
        return date.atTime(time).atZone(zone).toInstant().toEpochMilli()
    }

    @Test
    fun `P01_multipleNormalAlarmsChronologicalOrder`() {
        val alarm1 = Alarm(
            id = 1L,
            hour = 8,
            minute = 30,
            isEnabled = true,
            label = "Alarm 1",
            scheduleKind = Alarm.SCHEDULE_KIND_REPEATING,
            repeatDays = setOf(DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY)
        )
        val alarm2 = Alarm(
            id = 2L,
            hour = 7,
            minute = 0,
            isEnabled = true,
            label = "Alarm 2",
            scheduleKind = Alarm.SCHEDULE_KIND_REPEATING,
            repeatDays = setOf(DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY)
        )
        val alarm3 = Alarm(
            id = 3L,
            hour = 6,
            minute = 30,
            isEnabled = true,
            label = "Alarm 3",
            scheduleKind = Alarm.SCHEDULE_KIND_ONE_TIME,
            specificDate = "2026-11-05" // Thursday
        )

        val projection = projector.projectUpcomingSchedule(
            alarms = listOf(alarm1, alarm2, alarm3),
            nowEpochMs = nowEpochMs,
            horizonDays = 7,
            evaluationZoneId = zoneId
        )

        val occurrences = projection.allOccurrences.filter { it.willFire }
        assertTrue("Occurrences should not be empty", occurrences.isNotEmpty())

        // Verify strictly chronological ordering
        for (i in 0 until occurrences.size - 1) {
            assertTrue(
                "Must be chronological: ${occurrences[i].targetTriggerTimeEpochMs} <= ${occurrences[i + 1].targetTriggerTimeEpochMs}",
                occurrences[i].targetTriggerTimeEpochMs <= occurrences[i + 1].targetTriggerTimeEpochMs
            )
        }

        // Earliest future normal should be alarm2 at 07:00 on Wednesday Nov 4
        assertNotNull(projection.nextNormalOccurrence)
        assertEquals(2L, projection.nextNormalOccurrence?.alarmId)
        assertEquals(localToEpoch(LocalDate.of(2026, 11, 4), LocalTime.of(7, 0)), projection.nextNormalOccurrence?.targetTriggerTimeEpochMs)
    }

    @Test
    fun `P02_dateGroupingByLocalDate`() {
        val alarm = Alarm(
            id = 10L,
            hour = 7,
            minute = 0,
            isEnabled = true,
            scheduleKind = Alarm.SCHEDULE_KIND_REPEATING,
            repeatDays = setOf(DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY, DayOfWeek.FRIDAY)
        )

        val projection = projector.projectUpcomingSchedule(
            alarms = listOf(alarm),
            nowEpochMs = nowEpochMs,
            horizonDays = 5,
            evaluationZoneId = zoneId
        )

        val dayGroups = projection.dayGroups
        assertTrue("Should have day groups across horizon", dayGroups.size >= 3)

        // Day groups must match their entries' localDate
        for (group in dayGroups) {
            for (entry in group.entries) {
                assertEquals(group.date, entry.localDate)
            }
        }

        assertEquals(LocalDate.of(2026, 11, 4), dayGroups[0].date)
        assertEquals(LocalDate.of(2026, 11, 5), dayGroups[1].date)
        assertEquals(LocalDate.of(2026, 11, 6), dayGroups[2].date)
    }

    @Test
    fun `P03_deterministicTieBreakingSameEffectiveTime`() {
        val alarmA = Alarm(
            id = 100L,
            hour = 8,
            minute = 0,
            isEnabled = true,
            label = "Alarm A",
            scheduleKind = Alarm.SCHEDULE_KIND_ONE_TIME,
            specificDate = "2026-11-04"
        )
        val alarmB = Alarm(
            id = 50L,
            hour = 8,
            minute = 0,
            isEnabled = true,
            label = "Alarm B",
            scheduleKind = Alarm.SCHEDULE_KIND_ONE_TIME,
            specificDate = "2026-11-04"
        )

        val projection = projector.projectUpcomingSchedule(
            alarms = listOf(alarmA, alarmB),
            nowEpochMs = nowEpochMs,
            horizonDays = 3,
            evaluationZoneId = zoneId
        )

        val occurrences = projection.allOccurrences
        assertEquals(2, occurrences.size)
        assertEquals(occurrences[0].targetTriggerTimeEpochMs, occurrences[1].targetTriggerTimeEpochMs)
        // Deterministic tie-breaking: lower alarmId first
        assertEquals(50L, occurrences[0].alarmId)
        assertEquals(100L, occurrences[1].alarmId)
    }

    @Test
    fun `P04_holidayExcludeAdvancesNextAndMarksSkipped`() {
        val alarm = Alarm(
            id = 20L,
            hour = 7,
            minute = 0,
            isEnabled = true,
            scheduleKind = Alarm.SCHEDULE_KIND_REPEATING,
            repeatDays = setOf(DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY),
            holidayPolicy = Alarm.HOLIDAY_POLICY_EXCLUDE
        )

        // Wednesday Nov 4 is a holiday; Thursday Nov 5 is not
        val holidaySnapshotProvider: (LocalDate) -> HolidaySnapshot = { date ->
            if (date == LocalDate.of(2026, 11, 4)) {
                HolidaySnapshot(HolidayState.IS_HOLIDAY, "Election Day")
            } else {
                HolidaySnapshot(HolidayState.NOT_HOLIDAY, "")
            }
        }

        val projection = projector.projectUpcomingSchedule(
            alarms = listOf(alarm),
            nowEpochMs = nowEpochMs,
            horizonDays = 5,
            evaluationZoneId = zoneId,
            holidaySnapshotProvider = holidaySnapshotProvider
        )

        val occurrences = projection.allOccurrences
        val wednesdayOcc = occurrences.firstOrNull { it.localDate == LocalDate.of(2026, 11, 4) }
        assertNotNull(wednesdayOcc)
        assertFalse(wednesdayOcc!!.willFire)
        assertEquals(UpcomingOccurrenceReason.SKIPPED_BY_HOLIDAY, wednesdayOcc.reason)
        assertFalse(wednesdayOcc.isNextForAlarm)

        // nextNormalOccurrence advances to Thursday Nov 5
        assertNotNull(projection.nextNormalOccurrence)
        assertEquals(LocalDate.of(2026, 11, 5), projection.nextNormalOccurrence?.localDate)
        assertTrue(projection.nextNormalOccurrence!!.willFire)
        assertEquals(UpcomingOccurrenceReason.BASE_SCHEDULE, projection.nextNormalOccurrence?.reason)
    }

    @Test
    fun `P05_holidayIncludeGeneratesOccurrenceWithoutDoubleCount`() {
        val alarm = Alarm(
            id = 30L,
            hour = 9,
            minute = 0,
            isEnabled = true,
            scheduleKind = Alarm.SCHEDULE_KIND_REPEATING,
            repeatDays = setOf(DayOfWeek.THURSDAY), // Only Thursday
            holidayPolicy = Alarm.HOLIDAY_POLICY_INCLUDE
        )

        // Wednesday Nov 4 is a holiday; Thursday Nov 5 is also a holiday
        val holidaySnapshotProvider: (LocalDate) -> HolidaySnapshot = { date ->
            if (date == LocalDate.of(2026, 11, 4) || date == LocalDate.of(2026, 11, 5)) {
                HolidaySnapshot(HolidayState.IS_HOLIDAY, "Special Holiday")
            } else {
                HolidaySnapshot(HolidayState.NOT_HOLIDAY, "")
            }
        }

        val projection = projector.projectUpcomingSchedule(
            alarms = listOf(alarm),
            nowEpochMs = nowEpochMs,
            horizonDays = 5,
            evaluationZoneId = zoneId,
            holidaySnapshotProvider = holidaySnapshotProvider
        )

        val wedOccurrences = projection.allOccurrences.filter { it.localDate == LocalDate.of(2026, 11, 4) }
        assertEquals(1, wedOccurrences.size)
        assertEquals(UpcomingOccurrenceReason.HOLIDAY_INCLUDE, wedOccurrences[0].reason)
        assertTrue(wedOccurrences[0].willFire)

        // Thursday Nov 5 is BOTH repeatDay and holiday -> single occurrence, BASE_SCHEDULE (no double counting)
        val thuOccurrences = projection.allOccurrences.filter { it.localDate == LocalDate.of(2026, 11, 5) }
        assertEquals(1, thuOccurrences.size)
        assertEquals(UpcomingOccurrenceReason.BASE_SCHEDULE, thuOccurrences[0].reason)
        assertTrue(thuOccurrences[0].willFire)
    }

    @Test
    fun `P06_followBaseFiresOnHoliday`() {
        val alarm = Alarm(
            id = 40L,
            hour = 7,
            minute = 0,
            isEnabled = true,
            scheduleKind = Alarm.SCHEDULE_KIND_REPEATING,
            repeatDays = setOf(DayOfWeek.WEDNESDAY),
            holidayPolicy = Alarm.HOLIDAY_POLICY_FOLLOW_BASE
        )

        val holidaySnapshotProvider: (LocalDate) -> HolidaySnapshot = { date ->
            HolidaySnapshot(HolidayState.IS_HOLIDAY, "Holiday")
        }

        val projection = projector.projectUpcomingSchedule(
            alarms = listOf(alarm),
            nowEpochMs = nowEpochMs,
            horizonDays = 5,
            evaluationZoneId = zoneId,
            holidaySnapshotProvider = holidaySnapshotProvider
        )

        val wedOcc = projection.allOccurrences.firstOrNull { it.localDate == LocalDate.of(2026, 11, 4) }
        assertNotNull(wedOcc)
        assertTrue(wedOcc!!.willFire)
        assertEquals(UpcomingOccurrenceReason.BASE_SCHEDULE, wedOcc.reason)
    }

    @Test
    fun `P07_skipOnceSuppressesNextCandidate`() {
        val alarm = Alarm(
            id = 50L,
            hour = 7,
            minute = 0,
            isEnabled = true,
            generation = 1,
            scheduleKind = Alarm.SCHEDULE_KIND_REPEATING,
            repeatDays = setOf(DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY)
        )

        val wedBaseEpochMs = localToEpoch(LocalDate.of(2026, 11, 4), LocalTime.of(7, 0))
        val skipException = ScheduleException(
            id = 1L,
            alarmId = 50L,
            baseTriggerTime = wedBaseEpochMs,
            exceptionType = ScheduleException.TYPE_SKIP,
            generation = 1
        )

        val projection = projector.projectUpcomingSchedule(
            alarms = listOf(alarm),
            nowEpochMs = nowEpochMs,
            horizonDays = 5,
            evaluationZoneId = zoneId,
            exceptions = listOf(skipException)
        )

        val wedOcc = projection.allOccurrences.firstOrNull { it.localDate == LocalDate.of(2026, 11, 4) }
        assertNotNull(wedOcc)
        assertFalse(wedOcc!!.willFire)
        assertEquals(UpcomingOccurrenceReason.SKIP_ONCE, wedOcc.reason)
        assertFalse(wedOcc.isNextForAlarm)

        // Next firing advances to Thursday Nov 5
        assertEquals(LocalDate.of(2026, 11, 5), projection.nextNormalOccurrence?.localDate)
        assertEquals(50L, projection.nextNormalOccurrence?.alarmId)
        assertTrue(projection.nextNormalOccurrence!!.willFire)
    }

    @Test
    fun `P08_changeTimeOnceOverridesEffectiveTimePreservingIdentity`() {
        val alarm = Alarm(
            id = 60L,
            hour = 7,
            minute = 0,
            isEnabled = true,
            generation = 1,
            scheduleKind = Alarm.SCHEDULE_KIND_REPEATING,
            repeatDays = setOf(DayOfWeek.WEDNESDAY)
        )

        val baseEpochMs = localToEpoch(LocalDate.of(2026, 11, 4), LocalTime.of(7, 0))
        val overrideEpochMs = localToEpoch(LocalDate.of(2026, 11, 4), LocalTime.of(9, 30))

        val timeOverrideException = ScheduleException(
            id = 2L,
            alarmId = 60L,
            baseTriggerTime = baseEpochMs,
            overrideTriggerTime = overrideEpochMs,
            exceptionType = ScheduleException.TYPE_TIME_OVERRIDE,
            generation = 1
        )

        val projection = projector.projectUpcomingSchedule(
            alarms = listOf(alarm),
            nowEpochMs = nowEpochMs,
            horizonDays = 5,
            evaluationZoneId = zoneId,
            exceptions = listOf(timeOverrideException)
        )

        val next = projection.nextNormalOccurrence
        assertNotNull(next)
        assertEquals(overrideEpochMs, next?.targetTriggerTimeEpochMs)
        assertEquals(baseEpochMs, next?.baseTriggerTimeEpochMs)
        assertEquals(UpcomingOccurrenceReason.CHANGE_TIME_ONCE, next?.reason)
        assertTrue(next!!.isOverridden)
        // Base occurrence identity is preserved
        assertEquals(baseEpochMs, next.occurrenceId?.baseTriggerTime)
        assertEquals(LocalTime.of(9, 30), next.localDisplayTime)
    }

    @Test
    fun `P09_noCurrentOccurrenceDistinctFromDisabled`() {
        // Disabled alarm
        val disabledAlarm = Alarm(
            id = 71L,
            hour = 7,
            minute = 0,
            isEnabled = false,
            scheduleKind = Alarm.SCHEDULE_KIND_REPEATING,
            repeatDays = setOf(DayOfWeek.WEDNESDAY)
        )
        // Enabled ONE_TIME alarm in the past
        val pastOneTimeAlarm = Alarm(
            id = 72L,
            hour = 5,
            minute = 0,
            isEnabled = true,
            scheduleKind = Alarm.SCHEDULE_KIND_ONE_TIME,
            specificDate = "2026-11-03" // Yesterday
        )

        val projection = projector.projectUpcomingSchedule(
            alarms = listOf(disabledAlarm, pastOneTimeAlarm),
            nowEpochMs = nowEpochMs,
            horizonDays = 5,
            evaluationZoneId = zoneId
        )

        assertTrue(projection.disabledAlarms.contains(71L))
        assertFalse(projection.noCurrentOccurrenceAlarms.contains(71L))

        assertTrue(projection.noCurrentOccurrenceAlarms.contains(72L))
        assertFalse(projection.disabledAlarms.contains(72L))
    }

    @Test
    fun `P10_stalePastPositiveTriggerCannotMaskFutureAlarm`() {
        // Alarm with a stale past nextTriggerTime in Alarm table
        val stalePastAlarm = Alarm(
            id = 81L,
            hour = 8,
            minute = 0,
            isEnabled = true,
            nextTriggerTime = nowEpochMs - 3600_000L, // 1 hour ago
            scheduleKind = Alarm.SCHEDULE_KIND_REPEATING,
            repeatDays = setOf(DayOfWeek.WEDNESDAY)
        )
        val normalFutureAlarm = Alarm(
            id = 82L,
            hour = 7,
            minute = 0,
            isEnabled = true,
            scheduleKind = Alarm.SCHEDULE_KIND_REPEATING,
            repeatDays = setOf(DayOfWeek.WEDNESDAY)
        )

        val projection = projector.projectUpcomingSchedule(
            alarms = listOf(stalePastAlarm, normalFutureAlarm),
            nowEpochMs = nowEpochMs,
            horizonDays = 3,
            evaluationZoneId = zoneId
        )

        // Projection evaluates future candidate for 81L (today at 08:00) and 82L (today at 07:00)
        // 82L is earlier than 81L
        assertEquals(82L, projection.nextNormalOccurrence?.alarmId)
        val alarm81Occ = projection.allOccurrences.firstOrNull { it.alarmId == 81L }
        assertNotNull(alarm81Occ)
        assertEquals(localToEpoch(LocalDate.of(2026, 11, 4), LocalTime.of(8, 0)), alarm81Occ?.targetTriggerTimeEpochMs)
    }

    @Test
    fun `P11_reconcilingAndStaleFreshnessClassification`() {
        val alarm = Alarm(
            id = 91L,
            hour = 7,
            minute = 0,
            isEnabled = true,
            revision = 2,
            scheduleKind = Alarm.SCHEDULE_KIND_REPEATING,
            repeatDays = setOf(DayOfWeek.WEDNESDAY)
        )

        // Authority reconciling
        val reconcilingAuthority = ScheduleAuthority(
            alarmId = 91L,
            instanceToken = "token",
            definitionRevision = 2L,
            scheduleRevision = 1L,
            lifecycle = "NORMAL",
            desiredOperation = "SCHEDULE",
            desiredTriggerMillis = 0L,
            reconcileState = "RECONCILING",
            restoreEpoch = 0L,
            actionKey = null
        )

        val projectionReconciling = projector.projectUpcomingSchedule(
            alarms = listOf(alarm),
            nowEpochMs = nowEpochMs,
            horizonDays = 3,
            evaluationZoneId = zoneId,
            authorities = listOf(reconcilingAuthority)
        )
        assertEquals(UpcomingProjectionFreshness.RECONCILING, projectionReconciling.projectionFreshness)

        // Authority quarantined
        val quarantineAuthority = reconcilingAuthority.copy(reconcileState = "QUARANTINE")
        val projectionStale = projector.projectUpcomingSchedule(
            alarms = listOf(alarm),
            nowEpochMs = nowEpochMs,
            horizonDays = 3,
            evaluationZoneId = zoneId,
            authorities = listOf(quarantineAuthority)
        )
        assertEquals(UpcomingProjectionFreshness.STALE, projectionStale.projectionFreshness)
    }

    @Test
    fun `P12_quickAlarmAndActiveSessionExclusion`() {
        val quickAlarm = Alarm(
            id = 101L,
            hour = 7,
            minute = 0,
            isEnabled = true,
            kind = Alarm.KIND_QUICK,
            scheduleKind = Alarm.SCHEDULE_KIND_ONE_TIME,
            specificDate = "2026-11-04"
        )
        val activeFiringAlarm = Alarm(
            id = 102L,
            hour = 6,
            minute = 30,
            isEnabled = true,
            scheduleKind = Alarm.SCHEDULE_KIND_REPEATING,
            repeatDays = setOf(DayOfWeek.WEDNESDAY)
        )
        val normalAlarm = Alarm(
            id = 103L,
            hour = 8,
            minute = 0,
            isEnabled = true,
            scheduleKind = Alarm.SCHEDULE_KIND_REPEATING,
            repeatDays = setOf(DayOfWeek.WEDNESDAY)
        )

        val projection = projector.projectUpcomingSchedule(
            alarms = listOf(quickAlarm, activeFiringAlarm, normalAlarm),
            nowEpochMs = nowEpochMs,
            horizonDays = 3,
            evaluationZoneId = zoneId,
            activeFiringAlarmIds = setOf(102L)
        )

        // Quick alarm is completely excluded
        assertFalse(projection.allOccurrences.any { it.alarmId == 101L })
        // Active firing alarm is excluded from nextNormalOccurrence
        assertEquals(103L, projection.nextNormalOccurrence?.alarmId)
    }

    @Test
    fun `P13_idempotentReadZeroSideEffects`() {
        val alarm = Alarm(
            id = 111L,
            hour = 7,
            minute = 0,
            isEnabled = true,
            scheduleKind = Alarm.SCHEDULE_KIND_REPEATING,
            repeatDays = setOf(DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY)
        )

        val run1 = projector.projectUpcomingSchedule(
            alarms = listOf(alarm),
            nowEpochMs = nowEpochMs,
            horizonDays = 7,
            evaluationZoneId = zoneId
        )

        val run2 = projector.projectUpcomingSchedule(
            alarms = listOf(alarm),
            nowEpochMs = nowEpochMs,
            horizonDays = 7,
            evaluationZoneId = zoneId
        )

        assertEquals(run1.allOccurrences.size, run2.allOccurrences.size)
        assertEquals(run1.nextNormalOccurrence?.targetTriggerTimeEpochMs, run2.nextNormalOccurrence?.targetTriggerTimeEpochMs)
        assertEquals(run1.dayGroups.size, run2.dayGroups.size)
        assertEquals(run1.projectionFreshness, run2.projectionFreshness)
    }

    @Test
    fun `P14_timezoneAndDstBoundaryHandling`() {
        // Fall back DST transition in America/New_York (Nov 1 2026 02:00 -> 01:00)
        val dstZone = ZoneId.of("America/New_York")
        val dstAlarm = Alarm(
            id = 120L,
            hour = 1,
            minute = 30,
            isEnabled = true,
            scheduleKind = Alarm.SCHEDULE_KIND_ONE_TIME,
            specificDate = "2026-11-01"
        )

        val preDstNow = LocalDate.of(2026, 10, 31)
            .atTime(LocalTime.of(12, 0))
            .atZone(dstZone)
            .toInstant()
            .toEpochMilli()

        val projection = projector.projectUpcomingSchedule(
            alarms = listOf(dstAlarm),
            nowEpochMs = preDstNow,
            horizonDays = 5,
            evaluationZoneId = dstZone
        )

        val occ = projection.nextNormalOccurrence
        assertNotNull(occ)
        assertEquals(LocalDate.of(2026, 11, 1), occ?.localDate)
        assertEquals(LocalTime.of(1, 30), occ?.localDisplayTime)
    }
}
