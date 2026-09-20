package com.sysadmindoc.alarmclock.domain

import org.junit.Assert.assertEquals
import org.junit.Test

class GroupTabPositionTest {

    @Test
    fun fromKey_mapping() {
        assertEquals(GroupTabPosition.TOP, GroupTabPosition.fromKey("top"))
        assertEquals(GroupTabPosition.BOTTOM, GroupTabPosition.fromKey("bottom"))
        assertEquals(GroupTabPosition.TOP, GroupTabPosition.fromKey("TOP"))
        assertEquals(GroupTabPosition.BOTTOM, GroupTabPosition.fromKey("  bottom  "))
        assertEquals(GroupTabPosition.TOP, GroupTabPosition.fromKey("invalid"))
        assertEquals(GroupTabPosition.TOP, GroupTabPosition.fromKey(""))
    }

    @Test
    fun storageKey_consistency() {
        assertEquals("top", GroupTabPosition.TOP.storageKey)
        assertEquals("bottom", GroupTabPosition.BOTTOM.storageKey)
    }
}
