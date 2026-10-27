package com.sysadmindoc.alarmclock.domain

import java.time.Instant
import java.time.ZoneId

sealed interface QuickCreationResult {
    data class CreatedAndArmed(val alarmId: Long, val triggerEpochMs: Long) : QuickCreationResult
    data class PermissionBlocked(val alarmId: Long, val reason: String) : QuickCreationResult
    data class RegistrationFailed(val alarmId: Long, val cause: Throwable?) : QuickCreationResult
    object ValidationRejected : QuickCreationResult
}

object QuickTimeResolver {
    fun resolveRelative(minutes: Int, nowEpochMs: Long = System.currentTimeMillis()): Long? {
        if (minutes !in 1..180) return null
        return nowEpochMs + (minutes * 60 * 1000L)
    }

    fun resolveAbsolute(
        hour: Int,
        minute: Int,
        nowEpochMs: Long = System.currentTimeMillis(),
        zoneId: ZoneId = ZoneId.systemDefault()
    ): Long {
        val nowLocal = Instant.ofEpochMilli(nowEpochMs).atZone(zoneId)
        var target = nowLocal
            .withHour(hour.coerceIn(0, 23))
            .withMinute(minute.coerceIn(0, 59))
            .withSecond(0)
            .withNano(0)

        // Same-minute or non-future => resolves to next day same HH:mm (< 24h)
        if (!target.isAfter(nowLocal)) {
            target = target.plusDays(1)
        }
        return target.toInstant().toEpochMilli()
    }
}
