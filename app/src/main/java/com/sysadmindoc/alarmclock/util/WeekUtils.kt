package com.sysadmindoc.alarmclock.util

import java.time.DayOfWeek
import java.time.temporal.WeekFields
import java.util.Locale

object WeekUtils {

    /**
     * Resolves the first day of the week for the current default locale/configuration
     * using public [WeekFields] platform API.
     */
    fun systemFirstDayOfWeek(): DayOfWeek {
        return WeekFields.of(Locale.getDefault()).firstDayOfWeek
    }

    /**
     * Returns all seven [DayOfWeek] values in cyclic order starting from [firstDay].
     */
    fun getOrderedDaysOfWeek(firstDay: DayOfWeek = systemFirstDayOfWeek()): List<DayOfWeek> {
        val entries = DayOfWeek.entries
        val startIndex = entries.indexOf(firstDay)
        if (startIndex < 0) return entries
        return entries.subList(startIndex, entries.size) + entries.subList(0, startIndex)
    }
}
