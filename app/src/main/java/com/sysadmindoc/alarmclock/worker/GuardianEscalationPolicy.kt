package com.sysadmindoc.alarmclock.worker

import com.sysadmindoc.alarmclock.R

enum class GuardianSmsPath {
    INACTIVE,
    DIRECT_SMS,
    NEEDS_SEND_SMS_PERMISSION,
    SMS_COMPOSER
}

data class GuardianReadiness(
    val enabledAlarmCount: Int,
    val smsPath: GuardianSmsPath,
    val hasSendSmsPermission: Boolean
) {
    val hasEnabledAlarms: Boolean
        get() = enabledAlarmCount > 0

    val needsSmsPermission: Boolean
        get() = smsPath == GuardianSmsPath.NEEDS_SEND_SMS_PERMISSION

    val needsUserAction: Boolean
        get() = needsSmsPermission
}

internal object GuardianEscalationPolicy {

    fun canSendDirectSms(hasSendSmsPermission: Boolean): Boolean =
        hasSendSmsPermission

    fun readiness(
        enabledAlarmCount: Int,
        hasSendSmsPermission: Boolean
    ): GuardianReadiness {
        val count = enabledAlarmCount.coerceAtLeast(0)
        val smsPath = when {
            count == 0 -> GuardianSmsPath.INACTIVE
            canSendDirectSms(hasSendSmsPermission) -> GuardianSmsPath.DIRECT_SMS
            else -> GuardianSmsPath.NEEDS_SEND_SMS_PERMISSION
        }
        return GuardianReadiness(
            enabledAlarmCount = count,
            smsPath = smsPath,
            hasSendSmsPermission = hasSendSmsPermission
        )
    }

    /**
     * @return the resource ID for the alert message based on whether [label] is blank.
     */
    fun messageResFor(label: String): Int =
        if (label.isBlank()) {
            R.string.guardian_alert_message_without_label
        } else {
            R.string.guardian_alert_message_with_label
        }

    /**
     * Keep only characters that are safe in tel:/smsto: targets. Returns null
     * when fewer than three digits remain, so the worker never opens garbage.
     */
    fun sanitisePhone(raw: String): String? {
        val cleaned = buildString {
            for (c in raw) {
                if (c.isDigit() || c == '+' || c == '-') append(c)
            }
        }
        return if (cleaned.count { it.isDigit() } >= 3) cleaned else null
    }
}
