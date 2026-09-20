package com.sysadmindoc.alarmclock.domain

/** v1.15.35 (ALA-115): Position of the group-filtering tabs in the alarm list. */
enum class GroupTabPosition(val storageKey: String) {
    TOP("top"),
    BOTTOM("bottom");

    companion object {
        fun fromKey(key: String): GroupTabPosition =
            entries.firstOrNull { it.storageKey == key.trim().lowercase() } ?: TOP
    }
}
