package com.sysadmindoc.alarmclock.ui.components

import com.sysadmindoc.alarmclock.ui.alarmedit.formatAlarmNumpadDigits
import com.sysadmindoc.alarmclock.ui.alarmedit.parseAlarmNumpadTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TimePickerContractTest {

    @Test
    fun `TimePickerValue handles 00 00 and 23 59 boundary values`() {
        val midnight = TimePickerValue(0, 0)
        assertEquals(0, midnight.hour)
        assertEquals(0, midnight.minute)
        assertFalse(midnight.isPm)

        val endOfDay = TimePickerValue(23, 59)
        assertEquals(23, endOfDay.hour)
        assertEquals(59, endOfDay.minute)
        assertTrue(endOfDay.isPm)
    }

    @Test
    fun `TimePickerValue handles 12 AM and 12 PM conversions accurately`() {
        // 12 AM (Midnight)
        val midnight = TimePickerValue.from12Hour(12, 0, isPm = false)
        assertEquals(0, midnight.hour)
        assertEquals(0, midnight.minute)
        assertFalse(midnight.isPm)

        // 12 PM (Noon)
        val noon = TimePickerValue.from12Hour(12, 0, isPm = true)
        assertEquals(12, noon.hour)
        assertEquals(0, noon.minute)
        assertTrue(noon.isPm)

        // 1 AM
        val am1 = TimePickerValue.from12Hour(1, 30, isPm = false)
        assertEquals(1, am1.hour)
        assertEquals(30, am1.minute)
        assertFalse(am1.isPm)

        // 11 PM
        val pm11 = TimePickerValue.from12Hour(11, 45, isPm = true)
        assertEquals(23, pm11.hour)
        assertEquals(45, pm11.minute)
        assertTrue(pm11.isPm)
    }

    @Test
    fun `TimePickerValue 12h and 24h display hour equivalence`() {
        val midnight = TimePickerValue(0, 15)
        assertEquals(0, midnight.displayHour(is24Hour = true))
        assertEquals(12, midnight.displayHour(is24Hour = false))

        val noon = TimePickerValue(12, 30)
        assertEquals(12, noon.displayHour(is24Hour = true))
        assertEquals(12, noon.displayHour(is24Hour = false))

        val lateEvening = TimePickerValue(23, 59)
        assertEquals(23, lateEvening.displayHour(is24Hour = true))
        assertEquals(11, lateEvening.displayHour(is24Hour = false))
    }

    @Test
    fun `invalid hour and minute inputs in numpad parser fail closed`() {
        // Invalid 24h hour
        assertNull(parseAlarmNumpadTime("2500", is24Hour = true, isPm = false))

        // Invalid minute (> 59)
        assertNull(parseAlarmNumpadTime("1260", is24Hour = true, isPm = false))

        // Invalid 12h hour (> 12)
        assertNull(parseAlarmNumpadTime("1300", is24Hour = false, isPm = false))

        // Incomplete digits (< 4)
        assertNull(parseAlarmNumpadTime("123", is24Hour = true, isPm = false))

        // Non-numeric digits
        assertNull(parseAlarmNumpadTime("12a0", is24Hour = true, isPm = false))
    }

    @Test
    fun `wheel and numpad state round-trip equivalence`() {
        val originalTime = TimePickerValue(14, 30)

        // Format to 12h numpad digits
        val formattedDigits = formatAlarmNumpadDigits(
            originalTime.hour,
            originalTime.minute,
            is24Hour = false
        )
        assertEquals("0230", formattedDigits)

        // Parse 12h numpad digits with isPm = true
        val parsed = parseAlarmNumpadTime(formattedDigits, is24Hour = false, isPm = originalTime.isPm)
        assertNotNull(parsed)
        assertEquals(originalTime.hour, parsed?.hour)
        assertEquals(originalTime.minute, parsed?.minute)
    }
}
