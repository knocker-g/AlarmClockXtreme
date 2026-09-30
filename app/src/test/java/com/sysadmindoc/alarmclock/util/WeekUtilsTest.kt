package com.sysadmindoc.alarmclock.util

import com.sysadmindoc.alarmclock.data.local.Converters
import java.time.DayOfWeek
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WeekUtilsTest {

    @Test
    fun `getOrderedDaysOfWeek starting on Monday returns Monday to Sunday`() {
        val days = WeekUtils.getOrderedDaysOfWeek(DayOfWeek.MONDAY)
        assertEquals(7, days.size)
        assertEquals(DayOfWeek.MONDAY, days[0])
        assertEquals(DayOfWeek.TUESDAY, days[1])
        assertEquals(DayOfWeek.WEDNESDAY, days[2])
        assertEquals(DayOfWeek.THURSDAY, days[3])
        assertEquals(DayOfWeek.FRIDAY, days[4])
        assertEquals(DayOfWeek.SATURDAY, days[5])
        assertEquals(DayOfWeek.SUNDAY, days[6])
    }

    @Test
    fun `getOrderedDaysOfWeek starting on Sunday returns Sunday to Saturday`() {
        val days = WeekUtils.getOrderedDaysOfWeek(DayOfWeek.SUNDAY)
        assertEquals(7, days.size)
        assertEquals(DayOfWeek.SUNDAY, days[0])
        assertEquals(DayOfWeek.MONDAY, days[1])
        assertEquals(DayOfWeek.TUESDAY, days[2])
        assertEquals(DayOfWeek.WEDNESDAY, days[3])
        assertEquals(DayOfWeek.THURSDAY, days[4])
        assertEquals(DayOfWeek.FRIDAY, days[5])
        assertEquals(DayOfWeek.SATURDAY, days[6])
    }

    @Test
    fun `getOrderedDaysOfWeek starting on Saturday returns Saturday to Friday`() {
        val days = WeekUtils.getOrderedDaysOfWeek(DayOfWeek.SATURDAY)
        assertEquals(7, days.size)
        assertEquals(DayOfWeek.SATURDAY, days[0])
        assertEquals(DayOfWeek.SUNDAY, days[1])
        assertEquals(DayOfWeek.MONDAY, days[2])
        assertEquals(DayOfWeek.TUESDAY, days[3])
        assertEquals(DayOfWeek.WEDNESDAY, days[4])
        assertEquals(DayOfWeek.THURSDAY, days[5])
        assertEquals(DayOfWeek.FRIDAY, days[6])
    }

    @Test
    fun `getOrderedDaysOfWeek contains all 7 unique DayOfWeek values for every start day`() {
        DayOfWeek.entries.forEach { startDay ->
            val ordered = WeekUtils.getOrderedDaysOfWeek(startDay)
            assertEquals(7, ordered.size)
            assertEquals(7, ordered.toSet().size)
            assertTrue(ordered.containsAll(DayOfWeek.entries))
            assertEquals(startDay, ordered.first())
        }
    }

    @Test
    fun `persistence converters remain invariant regardless of UI ordering`() {
        val converter = Converters()
        val originalSet = setOf(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY, DayOfWeek.SUNDAY)
        val serialized = converter.fromDayOfWeekSet(originalSet)
        val deserialized = converter.toDayOfWeekSet(serialized)

        assertEquals("1,3,7", serialized)
        assertEquals(originalSet, deserialized)
    }
}
