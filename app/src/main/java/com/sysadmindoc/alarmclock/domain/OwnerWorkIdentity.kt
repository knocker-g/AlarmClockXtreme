package com.sysadmindoc.alarmclock.domain

import com.sysadmindoc.alarmclock.data.local.entity.ScheduleAuthority

/**
 * Supported supporting Work types for alarm schedule lifecycle.
 */
enum class SupportingWorkType(val id: String, val legacyPrefix: String) {
    HUE_SUNRISE("hue_sunrise", "hue_sunrise_"),
    FIRE_WATCHDOG("fire_watchdog", "fire_watchdog_"),
    GUARDIAN("guardian", "guardian_"),
    WAKE_CONFIRM("wake_confirm", "wake_confirm_");

    fun legacyUniqueName(alarmId: Long): String = "$legacyPrefix$alarmId"
}

/**
 * Immutable value object representing a generation-qualified Work identity
 * under OWNER_EXCLUSIVE scheduling route.
 *
 * Guarantees:
 * - Fail-closed validation for nonpositive alarmId, blank instanceToken, negative restoreEpoch, nonpositive scheduleRevision.
 * - Deterministic, collision-safe unique work name serialization.
 * - Pure model without external side effects or dependencies.
 */
data class OwnerWorkIdentity(
    val alarmId: Long,
    val instanceToken: String,
    val restoreEpoch: Long,
    val scheduleRevision: Long,
    val workType: SupportingWorkType
) {
    init {
        require(alarmId > 0L) { "alarmId must be positive, was: $alarmId" }
        require(instanceToken.isNotBlank()) { "instanceToken must not be blank" }
        require(restoreEpoch >= 0L) { "restoreEpoch must be non-negative, was: $restoreEpoch" }
        require(scheduleRevision > 0L) { "scheduleRevision must be positive, was: $scheduleRevision" }
    }

    /**
     * Serialized unique work name under OWNER_EXCLUSIVE route.
     * Format: "acx_owner_${workType.id}_${alarmId}_${instanceToken}_${restoreEpoch}_rev${scheduleRevision}"
     */
    fun ownerUniqueName(): String =
        "acx_owner_${workType.id}_${alarmId}_${instanceToken}_${restoreEpoch}_rev${scheduleRevision}"

    companion object {
        fun fromAuthority(authority: ScheduleAuthority, workType: SupportingWorkType): OwnerWorkIdentity {
            return OwnerWorkIdentity(
                alarmId = authority.alarmId,
                instanceToken = authority.instanceToken,
                restoreEpoch = authority.restoreEpoch,
                scheduleRevision = authority.scheduleRevision,
                workType = workType
            )
        }

        /**
         * Serialized unique work name under LEGACY_ONLY route (occurrence-bound).
         * Format: "acx_occ_${workType.id}_${alarmId}_${scheduledAt}"
         */
        fun occurrenceUniqueName(alarmId: Long, scheduledAt: Long, workType: SupportingWorkType): String {
            require(alarmId > 0L) { "alarmId must be positive, was: $alarmId" }
            require(scheduledAt > 0L) { "scheduledAt must be positive, was: $scheduledAt" }
            return "acx_occ_${workType.id}_${alarmId}_${scheduledAt}"
        }

        /**
         * Legacy unique work name matching main codebase 100%.
         */
        fun legacyUniqueName(alarmId: Long, workType: SupportingWorkType): String {
            require(alarmId > 0L) { "alarmId must be positive, was: $alarmId" }
            return workType.legacyUniqueName(alarmId)
        }
    }
}
