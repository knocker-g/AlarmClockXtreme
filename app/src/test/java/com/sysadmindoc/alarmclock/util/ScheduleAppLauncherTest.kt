package com.sysadmindoc.alarmclock.util

import android.content.Intent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ScheduleAppLauncherTest {

    @Test
    fun `createCalendarIntent with empty package creates generic calendar intent`() {
        val intent = ScheduleAppLauncher.createCalendarIntent("")
        assertEquals(Intent.ACTION_MAIN, intent.action)
        assertNull(intent.`package`)
    }

    @Test
    fun `createCalendarIntent with package constrains intent to package`() {
        val targetPkg = "com.google.android.calendar"
        val intent = ScheduleAppLauncher.createCalendarIntent(targetPkg)
        assertEquals(Intent.ACTION_MAIN, intent.action)
        assertEquals(targetPkg, intent.`package`)
    }
}
