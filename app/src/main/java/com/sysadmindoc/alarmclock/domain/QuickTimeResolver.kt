package com.sysadmindoc.alarmclock.domain

import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
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
    ): Long? {
        if (hour !in 0..23 || minute !in 0..59) return null

        val nowInstant = Instant.ofEpochMilli(nowEpochMs)
        val nowLocal = nowInstant.atZone(zoneId)
        val todayDate = nowLocal.toLocalDate()

        // Try today first
        var targetInstant = resolveZoneDateTime(todayDate, hour, minute, zoneId)
        if (targetInstant == null || !targetInstant.isAfter(nowInstant)) {
            // Try tomorrow
            targetInstant = resolveZoneDateTime(todayDate.plusDays(1), hour, minute, zoneId)
        }

        if (targetInstant == null) return null

        val triggerMs = targetInstant.toEpochMilli()
        val diffMs = triggerMs - nowEpochMs
        if (diffMs <= 0 || diffMs >= 24 * 3600_000L) return null

        return triggerMs
    }

    private fun resolveZoneDateTime(
        date: LocalDate,
        hour: Int,
        minute: Int,
        zoneId: ZoneId
    ): Instant? {
        val localTime = LocalTime.of(hour, minute)
        val ldt = LocalDateTime.of(date, localTime)
        val zdt = ldt.atZone(zoneId)

        // DST Gap check: if clock jumped forward, LocalTime is invalid
        if (zdt.toLocalTime() != localTime) {
            return null
        }
        return zdt.toInstant()
    }
}
