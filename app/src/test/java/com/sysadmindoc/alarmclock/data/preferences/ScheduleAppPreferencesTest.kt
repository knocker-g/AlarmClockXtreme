package com.sysadmindoc.alarmclock.data.preferences

import org.junit.Assert.assertEquals
import org.junit.Test

class ScheduleAppPreferencesTest {

    @Test
    fun `default scheduleAppPackage is empty string denoting System Default`() {
        val defaultSettings = AppSettings()
        assertEquals("", defaultSettings.scheduleAppPackage)
    }

    @Test
    fun `scheduleAppPackage can be updated to specific package`() {
        val settings = AppSettings().copy(scheduleAppPackage = "com.google.android.calendar")
        assertEquals("com.google.android.calendar", settings.scheduleAppPackage)
    }
}
