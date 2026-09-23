package com.sysadmindoc.alarmclock.ui.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Article
import androidx.compose.material.icons.filled.Alarm
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.WbSunny
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SwipeNavigationTest {

    private val allTabs = listOf(
        BottomNavItem(Screen.Dashboard, "Today", Icons.Default.WbSunny),
        BottomNavItem(Screen.AlarmList, "Alarms", Icons.Default.Alarm),
        BottomNavItem(Screen.Timer, "Timer", Icons.Default.Timer),
        BottomNavItem(Screen.WorldClock, "World", Icons.Default.Language),
        BottomNavItem(Screen.News, "News", Icons.AutoMirrored.Filled.Article),
        BottomNavItem(Screen.Settings, "Settings", Icons.Default.Settings)
    )

    @Test
    fun `swipe left moves to next visible tab`() {
        val target = calculateSwipeTargetTab(allTabs, Screen.AlarmList.route, SwipeDirection.LEFT)
        assertEquals(Screen.Timer, target)
    }

    @Test
    fun `swipe right moves to previous visible tab`() {
        val target = calculateSwipeTargetTab(allTabs, Screen.AlarmList.route, SwipeDirection.RIGHT)
        assertEquals(Screen.Dashboard, target)
    }

    @Test
    fun `swipe left on last tab returns null`() {
        val target = calculateSwipeTargetTab(allTabs, Screen.Settings.route, SwipeDirection.LEFT)
        assertNull(target)
    }

    @Test
    fun `swipe right on first tab returns null`() {
        val target = calculateSwipeTargetTab(allTabs, Screen.Dashboard.route, SwipeDirection.RIGHT)
        assertNull(target)
    }

    @Test
    fun `swipe skips hidden tabs`() {
        val visibleTabs = listOf(
            BottomNavItem(Screen.AlarmList, "Alarms", Icons.Default.Alarm),
            BottomNavItem(Screen.News, "News", Icons.AutoMirrored.Filled.Article),
            BottomNavItem(Screen.Settings, "Settings", Icons.Default.Settings)
        )
        val targetLeft = calculateSwipeTargetTab(visibleTabs, Screen.AlarmList.route, SwipeDirection.LEFT)
        assertEquals(Screen.News, targetLeft)

        val targetRight = calculateSwipeTargetTab(visibleTabs, Screen.News.route, SwipeDirection.RIGHT)
        assertEquals(Screen.AlarmList, targetRight)
    }

    @Test
    fun `swipe on unmanaged or detail route returns null`() {
        val target = calculateSwipeTargetTab(allTabs, Screen.AlarmEdit.route, SwipeDirection.LEFT)
        assertNull(target)
    }

    @Test
    fun `tab transition direction is NEXT when target is after initial`() {
        val direction = calculateTabNavigationDirection(Screen.AlarmList.route, Screen.Timer.route, allTabs)
        assertEquals(TabNavigationDirection.NEXT, direction)
    }

    @Test
    fun `tab transition direction is PREVIOUS when target is before initial`() {
        val direction = calculateTabNavigationDirection(Screen.Timer.route, Screen.AlarmList.route, allTabs)
        assertEquals(TabNavigationDirection.PREVIOUS, direction)
    }

    @Test
    fun `tab transition direction across hidden tabs preserves sign`() {
        val directionNext = calculateTabNavigationDirection(Screen.AlarmList.route, Screen.News.route, allTabs)
        assertEquals(TabNavigationDirection.NEXT, directionNext)

        val directionPrev = calculateTabNavigationDirection(Screen.News.route, Screen.AlarmList.route, allTabs)
        assertEquals(TabNavigationDirection.PREVIOUS, directionPrev)
    }

    @Test
    fun `tab transition direction is NEUTRAL for same route`() {
        val direction = calculateTabNavigationDirection(Screen.AlarmList.route, Screen.AlarmList.route, allTabs)
        assertEquals(TabNavigationDirection.NEUTRAL, direction)
    }

    @Test
    fun `tab transition direction is NEUTRAL when either route is detail or unknown`() {
        val direction1 = calculateTabNavigationDirection(Screen.AlarmList.route, Screen.AlarmEdit.route, allTabs)
        assertEquals(TabNavigationDirection.NEUTRAL, direction1)

        val direction2 = calculateTabNavigationDirection(Screen.Onboarding.route, Screen.AlarmList.route, allTabs)
        assertEquals(TabNavigationDirection.NEUTRAL, direction2)

        val direction3 = calculateTabNavigationDirection("unknown_route", Screen.Timer.route, allTabs)
        assertEquals(TabNavigationDirection.NEUTRAL, direction3)
    }
}
