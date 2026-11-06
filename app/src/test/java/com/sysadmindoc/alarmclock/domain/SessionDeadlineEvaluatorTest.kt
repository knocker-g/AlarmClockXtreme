package com.sysadmindoc.alarmclock.domain

import com.sysadmindoc.alarmclock.data.local.entity.AlarmSessionEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionDeadlineEvaluatorTest {

    private fun createTestSession(
        createdAtMs: Long = 1000L,
        createdElapsedMs: Long = 500L,
        bootCount: Long = 1L,
        maxTimeoutMs: Long = 18_000_000L
    ): AlarmSessionEntity {
        return AlarmSessionEntity(
            sessionId = "sess_deadline_test",
            admissionKey = "v1:admission_test",
            alarmId = 100L,
            originKind = "NORMAL",
            originScheduleKind = "REPEATING",
            generation = 1,
            revision = 1,
            fireId = "fire_test",
            occurrenceKey = "occ_test",
            canonicalBaseTriggerMs = createdAtMs,
            scheduledTargetEpochMs = createdAtMs,
            sessionState = "FIRING",
            createdAtEpochMs = createdAtMs,
            createdElapsedRealtimeMs = createdElapsedMs,
            bootCount = bootCount,
            updatedAtEpochMs = createdAtMs,
            absoluteDeadlineEpochMs = createdAtMs + maxTimeoutMs,
            configurationSnapshotJson = "{}"
        )
    }

    @Test
    fun evaluateReturnsValidActiveForNormalSessionWithinDeadline() {
        val session = createTestSession(createdAtMs = 1000L, createdElapsedMs = 500L, bootCount = 1L)
        val result = SessionDeadlineEvaluator.evaluate(
            session = session,
            currentWallClockMs = 2000L,
            currentElapsedRealtimeMs = 1500L,
            currentBootCount = 1L
        )
        assertEquals(DeadlineEvaluationResult.ValidActive, result)
    }

    @Test
    fun evaluateReturnsExpiredTimeoutForBootCountMismatchOrInvalid() {
        val session = createTestSession(bootCount = 1L)

        // 1. BootCount mismatch
        val resultMismatch = SessionDeadlineEvaluator.evaluate(
            session = session,
            currentWallClockMs = 2000L,
            currentElapsedRealtimeMs = 1500L,
            currentBootCount = 2L // Mismatched boot
        )
        assertTrue(resultMismatch is DeadlineEvaluationResult.ExpiredTimeout)
        assertEquals("REBOOT_BOOT_COUNT_INVALID_OR_MISMATCHED", (resultMismatch as DeadlineEvaluationResult.ExpiredTimeout).reason)

        // 2. Saved bootCount invalid (-1L)
        val invalidSession = session.copy(bootCount = -1L)
        val resultInvalidSaved = SessionDeadlineEvaluator.evaluate(
            session = invalidSession,
            currentWallClockMs = 2000L,
            currentElapsedRealtimeMs = 1500L,
            currentBootCount = 1L
        )
        assertTrue(resultInvalidSaved is DeadlineEvaluationResult.ExpiredTimeout)
        assertEquals("REBOOT_BOOT_COUNT_INVALID_OR_MISMATCHED", (resultInvalidSaved as DeadlineEvaluationResult.ExpiredTimeout).reason)

        // 3. Current bootCount invalid (-1L)
        val resultInvalidCurrent = SessionDeadlineEvaluator.evaluate(
            session = session,
            currentWallClockMs = 2000L,
            currentElapsedRealtimeMs = 1500L,
            currentBootCount = -1L
        )
        assertTrue(resultInvalidCurrent is DeadlineEvaluationResult.ExpiredTimeout)
        assertEquals("REBOOT_BOOT_COUNT_INVALID_OR_MISMATCHED", (resultInvalidCurrent as DeadlineEvaluationResult.ExpiredTimeout).reason)
    }

    @Test
    fun evaluateReturnsExpiredTimeoutForWallClockBackwardJump() {
        val session = createTestSession(createdAtMs = 10_000L, createdElapsedMs = 5000L, bootCount = 1L)
        val resultBackward = SessionDeadlineEvaluator.evaluate(
            session = session,
            currentWallClockMs = 5000L, // Wall-clock jump backwards
            currentElapsedRealtimeMs = 6000L,
            currentBootCount = 1L
        )
        assertTrue(resultBackward is DeadlineEvaluationResult.ExpiredTimeout)
        assertEquals("WALL_CLOCK_BACKWARD_JUMP", (resultBackward as DeadlineEvaluationResult.ExpiredTimeout).reason)
    }

    @Test
    fun evaluateReturnsExpiredTimeoutWhenAbsoluteDeadlineExceeded() {
        val session = createTestSession(createdAtMs = 1000L, maxTimeoutMs = 1000L) // Deadline = 2000L
        val resultExceeded = SessionDeadlineEvaluator.evaluate(
            session = session,
            currentWallClockMs = 2001L, // Exceeds absoluteDeadlineEpochMs
            currentElapsedRealtimeMs = 1500L,
            currentBootCount = 1L
        )
        assertTrue(resultExceeded is DeadlineEvaluationResult.ExpiredTimeout)
        assertEquals("ABSOLUTE_DEADLINE_EXCEEDED", (resultExceeded as DeadlineEvaluationResult.ExpiredTimeout).reason)
    }

    @Test
    fun evaluateReturnsExpiredTimeoutWhenFiveHourMonotonicLimitExceeded() {
        val session = createTestSession(createdAtMs = 1000L, createdElapsedMs = 500L, bootCount = 1L)
        val fiveHoursAndOneMs = 5 * 3600 * 1000L + 1L
        val resultMonotonicLimit = SessionDeadlineEvaluator.evaluate(
            session = session,
            currentWallClockMs = 2000L,
            currentElapsedRealtimeMs = 500L + fiveHoursAndOneMs, // Exceeds 5-hour monotonic runtime cap
            currentBootCount = 1L
        )
        assertTrue(resultMonotonicLimit is DeadlineEvaluationResult.ExpiredTimeout)
        assertEquals("MONOTONIC_TIMEOUT", (resultMonotonicLimit as DeadlineEvaluationResult.ExpiredTimeout).reason)
    }
}
