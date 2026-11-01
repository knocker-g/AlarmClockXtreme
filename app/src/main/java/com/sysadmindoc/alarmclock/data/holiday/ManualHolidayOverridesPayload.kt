package com.sysadmindoc.alarmclock.data.holiday

data class ManualHolidayOverridesPayload(
    val formatVersion: Int,
    val updatedAtMillis: Long,
    val overrides: Map<String, String>
)
