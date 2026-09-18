package com.sysadmindoc.alarmclock.worker

import com.sysadmindoc.alarmclock.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GuardianEscalationPolicyTest {
    @Test
    fun withPermissionCanSendDirectSms() {
        assertTrue(
            GuardianEscalationPolicy.canSendDirectSms(
                hasSendSmsPermission = true
            )
        )
    }

    @Test
    fun missingPermissionCannotSendDirectSms() {
        assertFalse(
            GuardianEscalationPolicy.canSendDirectSms(
                hasSendSmsPermission = false
            )
        )
    }

    @Test
    fun readinessMarksMissingSmsAsActionable() {
        val readiness = GuardianEscalationPolicy.readiness(
            enabledAlarmCount = 2,
            hasSendSmsPermission = false
        )

        assertEquals(2, readiness.enabledAlarmCount)
        assertEquals(GuardianSmsPath.NEEDS_SEND_SMS_PERMISSION, readiness.smsPath)
        assertTrue(readiness.needsSmsPermission)
        assertTrue(readiness.needsUserAction)
    }

    @Test
    fun readinessIsInactiveWithoutGuardianAlarms() {
        val readiness = GuardianEscalationPolicy.readiness(
            enabledAlarmCount = 0,
            hasSendSmsPermission = true
        )

        assertEquals(GuardianSmsPath.INACTIVE, readiness.smsPath)
        assertFalse(readiness.hasEnabledAlarms)
        assertFalse(readiness.needsUserAction)
    }

    @Test
    fun readinessPassesWhenHasSmsPermission() {
        val readiness = GuardianEscalationPolicy.readiness(
            enabledAlarmCount = 1,
            hasSendSmsPermission = true
        )
        assertEquals(GuardianSmsPath.DIRECT_SMS, readiness.smsPath)
        assertFalse(readiness.needsSmsPermission)
        assertFalse(readiness.needsUserAction)
    }

    @Test
    fun picksCorrectMessageResource() {
        assertEquals(
            R.string.guardian_alert_message_with_label,
            GuardianEscalationPolicy.messageResFor("Work")
        )
        assertEquals(
            R.string.guardian_alert_message_without_label,
            GuardianEscalationPolicy.messageResFor("")
        )
        assertEquals(
            R.string.guardian_alert_message_without_label,
            GuardianEscalationPolicy.messageResFor("   ")
        )
    }

    @Test
    fun sanitisePhoneStripsUssdControlChars() {
        assertEquals(
            "+1555-123",
            GuardianEscalationPolicy.sanitisePhone("+1 (555) abc-*123#")
        )
    }

    @Test
    fun sanitisePhoneRejectsUnusableInput() {
        assertNull(GuardianEscalationPolicy.sanitisePhone("call me"))
        assertNull(GuardianEscalationPolicy.sanitisePhone("12"))
    }
}
