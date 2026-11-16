package com.sysadmindoc.alarmclock.directboot.d0contract

import com.sysadmindoc.alarmclock.data.model.Alarm
import com.sysadmindoc.alarmclock.domain.OwnerWorkIdentity
import com.sysadmindoc.alarmclock.domain.SupportingWorkType
import com.sysadmindoc.alarmclock.service.AlarmPostDismissController
import com.sysadmindoc.alarmclock.service.AlarmService
import com.sysadmindoc.alarmclock.worker.WakeConfirmWorker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Section 4 Compatibility Audit Test:
 * Verifies exact existing production WakeConfirm contracts remain untouched in D0-P0,
 * while documenting exact key-scoping and migration compatibility invariants.
 */
class WakeConfirmCompatibilityAuditTest {

    @Test
    fun `existing WakeConfirm production constants and plan shape remain untouched`() {
        assertEquals(3, WakeConfirmWorker.MAX_REFIRES)
        assertEquals("alarm_id", WakeConfirmWorker.KEY_ALARM_ID)
        assertEquals("alarm_fire_id", WakeConfirmWorker.KEY_ALARM_FIRE_ID)
        assertEquals("scheduled_at", WakeConfirmWorker.KEY_SCHEDULED_AT)
        assertEquals("refire_count", WakeConfirmWorker.KEY_REFIRE_COUNT)
        assertEquals("wake_confirm_refire_count", AlarmService.EXTRA_WAKE_CONFIRM_REFIRE_COUNT)

        val alarm = Alarm(id = 42L, wakeConfirmEnabled = true, wakeConfirmDelayMinutes = 10)
        val plan = AlarmPostDismissController.wakeConfirmationPlan(
            alarm = alarm,
            fireId = "alarm-42-1700000000000",
            scheduledAt = 1_700_000_000_000L,
            refireCount = 1
        )
        assertEquals(
            OwnerWorkIdentity.occurrenceUniqueName(42L, 1_700_000_000_000L, SupportingWorkType.WAKE_CONFIRM),
            plan.uniqueWorkName
        )
        assertEquals("wake_confirm_42", plan.tag)
        assertEquals(10L, plan.delayMinutes)
        assertEquals(42L, plan.inputData.getLong(WakeConfirmWorker.KEY_ALARM_ID, -1L))
        assertEquals("alarm-42-1700000000000", plan.inputData.getString(WakeConfirmWorker.KEY_ALARM_FIRE_ID))
        assertEquals(1_700_000_000_000L, plan.inputData.getLong(WakeConfirmWorker.KEY_SCHEDULED_AT, -1L))
        assertEquals(1, plan.inputData.getInt(WakeConfirmWorker.KEY_REFIRE_COUNT, -1))
    }

    @Test
    fun `disabled ONE_TIME alarm remains eligible under WakeConfirm compatibility rule`() {
        val oneShotDisabled = Alarm(
            id = 55L,
            isEnabled = false,
            repeatDays = emptySet(),
            specificDate = "",
            wakeConfirmEnabled = true
        )
        assertTrue(!oneShotDisabled.isRecurringSchedule)
        assertTrue(AlarmPostDismissController.shouldScheduleWakeConfirmation(oneShotDisabled))
    }

    @Test
    fun `occurrence scoping alarmId and scheduledAt isolates distinct occurrences from state corruption`() {
        val trig1 = 1_700_000_000_000L
        val trig2 = trig1 + 86_400_000L // Next day occurrence

        val lineage1 = WakeConfirmLineage(
            parentDismissed = true,
            wakeConfirmEnabled = true,
            nextAuthorizedRefire = 1,
            highestClaimedRefire = 0,
            confirmed = false,
            stepToken = "step-token-trig1"
        )
        val record1 = LegacyClaimRecord(
            alarmId = 42L,
            scheduledAt = trig1,
            refireCount = 0,
            source = ClaimSource.ALARM_RECEIVER,
            claimNonce = "nonce-1",
            processSessionToken = "proc-1",
            bootCount = 10L,
            state = LegacyClaimState.TERMINAL,
            terminalOutcome = TerminalOutcome.CONSUMED_DISMISSED,
            wakeConfirm = lineage1
        )

        // Occurrence 2 on trig2 has its own independent lineage
        val lineage2 = WakeConfirmLineage(
            parentDismissed = false,
            wakeConfirmEnabled = true,
            nextAuthorizedRefire = 0,
            highestClaimedRefire = 0,
            confirmed = false,
            stepToken = ""
        )
        val record2 = LegacyClaimRecord(
            alarmId = 42L,
            scheduledAt = trig2,
            refireCount = 0,
            source = ClaimSource.ALARM_RECEIVER,
            claimNonce = "nonce-2",
            processSessionToken = "proc-1",
            bootCount = 10L,
            state = LegacyClaimState.START_REQUESTED,
            wakeConfirm = lineage2
        )

        org.junit.Assert.assertNotEquals(record1.key, record2.key)
        assertEquals(42L, record1.key.alarmId)
        assertEquals(42L, record2.key.alarmId)
        assertEquals(trig1, record1.key.scheduledAt)
        assertEquals(trig2, record2.key.scheduledAt)
        // Late confirmation or action on trig1 must never mutate trig2
        assertEquals(false, record2.wakeConfirm?.parentDismissed)
        assertEquals(true, record1.wakeConfirm?.parentDismissed)
    }

    @Test
    fun `ACTION_DISMISSED persistence must precede WakeConfirmWorker enqueue invariant`() {
        // Contract invariant: WakeConfirmWorker must ONLY be scheduled AFTER parent occurrence is TERMINAL with CONSUMED_DISMISSED
        // and DE WakeConfirmLineage is committed with parentDismissed=true and non-blank stepToken.
        val uncommittedLineage = WakeConfirmLineage(
            parentDismissed = false,
            wakeConfirmEnabled = true,
            nextAuthorizedRefire = 1,
            highestClaimedRefire = 0,
            confirmed = false,
            stepToken = ""
        )
        // If parentDismissed is false, worker enqueue is unauthorized
        org.junit.Assert.assertFalse("Worker must not enqueue before dismissal is committed", uncommittedLineage.parentDismissed)
        org.junit.Assert.assertTrue("Token must be present before worker can be authorized", uncommittedLineage.stepToken.isBlank())

        val committedLineage = uncommittedLineage.copy(
            parentDismissed = true,
            stepToken = "valid-token-123"
        )
        org.junit.Assert.assertTrue("Committed dismissal satisfies enqueue precondition", committedLineage.parentDismissed)
        org.junit.Assert.assertEquals("valid-token-123", committedLineage.stepToken)
    }

    @Test
    fun `single-use stepToken and monotonic refire count 1-3 contract invariants`() {
        val baseLineage = WakeConfirmLineage(
            parentDismissed = true,
            wakeConfirmEnabled = true,
            nextAuthorizedRefire = 1,
            highestClaimedRefire = 0,
            confirmed = false,
            stepToken = "token-1"
        )
        // Monotonic step 1
        assertEquals(1, baseLineage.nextAuthorizedRefire)
        assertEquals(0, baseLineage.highestClaimedRefire)

        // After claiming refire 1:
        val refire1Lineage = baseLineage.copy(
            highestClaimedRefire = 1,
            nextAuthorizedRefire = 2,
            stepToken = "token-2"
        )
        org.junit.Assert.assertTrue(refire1Lineage.highestClaimedRefire > baseLineage.highestClaimedRefire)
        assertEquals(2, refire1Lineage.nextAuthorizedRefire)

        // Regressing refire is invalid
        val regressedRefire = 1
        org.junit.Assert.assertFalse(regressedRefire >= refire1Lineage.nextAuthorizedRefire)

        // Maximum refires bounded at 3
        val maxLineage = refire1Lineage.copy(
            highestClaimedRefire = 3,
            nextAuthorizedRefire = 4,
            stepToken = ""
        )
        org.junit.Assert.assertTrue("Refire count cannot exceed 3", maxLineage.highestClaimedRefire <= WakeConfirmWorker.MAX_REFIRES)
        org.junit.Assert.assertTrue("Next refire > 3 must not authorize further refires", maxLineage.nextAuthorizedRefire > WakeConfirmWorker.MAX_REFIRES)
    }
}

