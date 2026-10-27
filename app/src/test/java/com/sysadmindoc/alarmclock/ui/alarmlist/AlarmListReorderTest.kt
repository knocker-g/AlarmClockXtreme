package com.sysadmindoc.alarmclock.ui.alarmlist

import com.sysadmindoc.alarmclock.data.model.Alarm
import org.junit.Assert.assertEquals
import org.junit.Test

class AlarmListReorderTest {

    @Test
    fun manualSortUsesPersistedOrderBeforeClockTime() {
        val alarms = listOf(
            Alarm(id = 1L, hour = 9, minute = 0, sortOrder = 3_000),
            Alarm(id = 2L, hour = 7, minute = 30, sortOrder = 1_000),
            Alarm(id = 3L, hour = 6, minute = 45, sortOrder = 2_000),
            Alarm(id = 4L, hour = 5, minute = 15, sortOrder = 2_000)
        )

        val sorted = sortAlarmsForList(alarms, AlarmSortOrder.MANUAL)

        assertEquals(listOf(2L, 4L, 3L, 1L), sorted.map { it.id })
    }

    @Test
    fun reorderAlarmIdsMovesDraggedIdBeforeTarget() {
        val visibleIds = listOf(10L, 20L, 30L, 40L)

        assertEquals(
            listOf(10L, 30L, 20L, 40L),
            reorderAlarmIds(visibleIds, movedId = 30L, targetId = 20L)
        )
        assertEquals(
            listOf(10L, 30L, 40L, 20L),
            reorderAlarmIds(visibleIds, movedId = 20L, targetId = 40L)
        )
    }

    @Test
    fun reorderAlarmIdsIgnoresMissingIdsAndNoOps() {
        val visibleIds = listOf(10L, 20L, 30L)

        assertEquals(visibleIds, reorderAlarmIds(visibleIds, movedId = 99L, targetId = 20L))
        assertEquals(visibleIds, reorderAlarmIds(visibleIds, movedId = 20L, targetId = 99L))
        assertEquals(visibleIds, reorderAlarmIds(visibleIds, movedId = 20L, targetId = 20L))
    }

    @Test
    fun mergeFilteredReorderPreservesHiddenAlarmSlotsInGlobalOrder() {
        // Global IDs: [A(1), X(100), B(2)]
        val globalIds = listOf(1L, 100L, 2L)
        val visibleIds = listOf(1L, 2L)

        // Move B(2) before A(1)
        val result = mergeFilteredReorder(globalIds, visibleIds, movedId = 2L, targetId = 1L)

        // Resulting global order: [B(2), X(100), A(1)]
        assertEquals(listOf(2L, 100L, 1L), result)
    }

    @Test
    fun mergeFilteredReorderWithMultipleHiddenAlarmsPreservesHiddenPositions() {
        // Global IDs: [A(1), X(100), Y(200), B(2), Z(300)]
        val globalIds = listOf(1L, 100L, 200L, 2L, 300L)
        val visibleIds = listOf(1L, 2L)

        // Move B(2) before A(1)
        val result = mergeFilteredReorder(globalIds, visibleIds, movedId = 2L, targetId = 1L)

        // Resulting global order: [B(2), X(100), Y(200), A(1), Z(300)]
        assertEquals(listOf(2L, 100L, 200L, 1L, 300L), result)
    }

    @Test
    fun mergeFilteredReorderSingleVisibleItemIsNoOp() {
        val globalIds = listOf(1L, 100L, 2L)
        val visibleIds = listOf(1L)

        val result = mergeFilteredReorder(globalIds, visibleIds, movedId = 1L, targetId = 1L)

        assertEquals(globalIds, result)
    }

    @Test
    fun mergeFilteredReorderDirectMapsFinalVisibleOrderDirectlyOntoInitialGlobalSlots() {
        // Global IDs: [A(1), X(100), B(2), C(3)]
        val globalIds = listOf(1L, 100L, 2L, 3L)
        val initialVisibleIds = listOf(1L, 2L, 3L)
        val finalVisibleIds = listOf(3L, 1L, 2L)

        val result = mergeFilteredReorderDirect(globalIds, finalVisibleIds, initialVisibleIds)

        // Resulting global order: slot 0=3, slot 1=100 (hidden), slot 2=1, slot 3=2 -> [3, 100, 1, 2]
        assertEquals(listOf(3L, 100L, 1L, 2L), result)
    }

    @Test
    fun mergeFilteredReorderDirectNoOpWhenFinalEqualsInitial() {
        val globalIds = listOf(1L, 100L, 2L)
        val initialVisibleIds = listOf(1L, 2L)

        val result = mergeFilteredReorderDirect(globalIds, initialVisibleIds, initialVisibleIds)

        assertEquals(globalIds, result)
    }
}
