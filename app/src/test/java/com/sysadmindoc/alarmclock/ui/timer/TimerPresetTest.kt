package com.sysadmindoc.alarmclock.ui.timer

import org.junit.Assert.assertEquals
import org.junit.Test

class TimerPresetTest {

    @Test
    fun `defaultPresets contains exactly 6 presets in ascending order`() {
        assertEquals(6, defaultPresets.size)
        assertEquals(60L, defaultPresets[0].seconds)
        assertEquals(180L, defaultPresets[1].seconds)
        assertEquals(300L, defaultPresets[2].seconds)
        assertEquals(600L, defaultPresets[3].seconds)
        assertEquals(900L, defaultPresets[4].seconds)
        assertEquals(1800L, defaultPresets[5].seconds)
    }
}
