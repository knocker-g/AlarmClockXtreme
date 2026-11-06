package com.sysadmindoc.alarmclock.domain

import com.sysadmindoc.alarmclock.data.local.entity.AlarmSessionEntity

sealed class DeadlineEvaluationResult {
    object ValidActive : DeadlineEvaluationResult()
    data class ExpiredTimeout(val reason: String) : DeadlineEvaluationResult()
}

object SessionDeadlineEvaluator {

    private const val FIVE_HOURS_MS = 5 * 3600 * 1000L

    fun evaluate(
        session: AlarmSessionEntity,
        currentWallClockMs: Long,
        currentElapsedRealtimeMs: Long,
        currentBootCount: Long
    ): DeadlineEvaluationResult {
        // 1. Strict Boot Identity Rule (Both MUST be valid >0L and identical)
        if (session.bootCount <= 0L || currentBootCount <= 0L || session.bootCount != currentBootCount) {
            return DeadlineEvaluationResult.ExpiredTimeout("REBOOT_BOOT_COUNT_INVALID_OR_MISMATCHED")
        }

        // 2. Wall-Clock Absolute Cap
        if (currentWallClockMs >= session.absoluteDeadlineEpochMs) {
            return DeadlineEvaluationResult.ExpiredTimeout("ABSOLUTE_DEADLINE_EXCEEDED")
        }

        // 3. Wall-Clock Backward Discontinuity Detection
        if (currentWallClockMs < session.createdAtEpochMs) {
            return DeadlineEvaluationResult.ExpiredTimeout("WALL_CLOCK_BACKWARD_JUMP")
        }

        // 4. Same-Boot Monotonic Elapsed Check
        val elapsedMs = currentElapsedRealtimeMs - session.createdElapsedRealtimeMs
        if (elapsedMs < 0L) {
            return DeadlineEvaluationResult.ExpiredTimeout("MONOTONIC_CLOCK_GLITCH")
        }
        if (elapsedMs >= FIVE_HOURS_MS) {
            return DeadlineEvaluationResult.ExpiredTimeout("MONOTONIC_TIMEOUT")
        }

        return DeadlineEvaluationResult.ValidActive
    }
}
