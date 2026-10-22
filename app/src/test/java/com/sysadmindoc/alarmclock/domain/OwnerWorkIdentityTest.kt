package com.sysadmindoc.alarmclock.domain

import com.sysadmindoc.alarmclock.data.local.entity.ScheduleAuthority
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class OwnerWorkIdentityTest {

    @Test
    fun `same time different revision produces distinct owner unique names`() {
        val identityRev1 = OwnerWorkIdentity(
            alarmId = 42L,
            instanceToken = "inst-42",
            restoreEpoch = 100L,
            scheduleRevision = 1L,
            workType = SupportingWorkType.HUE_SUNRISE
        )
        val identityRev2 = identityRev1.copy(scheduleRevision = 2L)

        assertNotEquals(identityRev1.ownerUniqueName(), identityRev2.ownerUniqueName())
        assertEquals("acx_owner_hue_sunrise_42_inst-42_100_rev1", identityRev1.ownerUniqueName())
        assertEquals("acx_owner_hue_sunrise_42_inst-42_100_rev2", identityRev2.ownerUniqueName())
    }

    @Test
    fun `same revision different incarnation produces distinct owner unique names`() {
        val identityTokenA = OwnerWorkIdentity(
            alarmId = 42L,
            instanceToken = "inst-A",
            restoreEpoch = 100L,
            scheduleRevision = 1L,
            workType = SupportingWorkType.FIRE_WATCHDOG
        )
        val identityTokenB = identityTokenA.copy(instanceToken = "inst-B")

        assertNotEquals(identityTokenA.ownerUniqueName(), identityTokenB.ownerUniqueName())
        assertEquals("acx_owner_fire_watchdog_42_inst-A_100_rev1", identityTokenA.ownerUniqueName())
        assertEquals("acx_owner_fire_watchdog_42_inst-B_100_rev1", identityTokenB.ownerUniqueName())
    }

    @Test
    fun `different restore epoch produces distinct owner unique names`() {
        val identityEpoch100 = OwnerWorkIdentity(
            alarmId = 42L,
            instanceToken = "inst-42",
            restoreEpoch = 100L,
            scheduleRevision = 1L,
            workType = SupportingWorkType.GUARDIAN
        )
        val identityEpoch101 = identityEpoch100.copy(restoreEpoch = 101L)

        assertNotEquals(identityEpoch100.ownerUniqueName(), identityEpoch101.ownerUniqueName())
        assertEquals("acx_owner_guardian_42_inst-42_100_rev1", identityEpoch100.ownerUniqueName())
        assertEquals("acx_owner_guardian_42_inst-42_101_rev1", identityEpoch101.ownerUniqueName())
    }

    @Test
    fun `different alarmId produces distinct owner unique names`() {
        val identityAlarm42 = OwnerWorkIdentity(
            alarmId = 42L,
            instanceToken = "inst-shared",
            restoreEpoch = 100L,
            scheduleRevision = 1L,
            workType = SupportingWorkType.WAKE_CONFIRM
        )
        val identityAlarm99 = identityAlarm42.copy(alarmId = 99L)

        assertNotEquals(identityAlarm42.ownerUniqueName(), identityAlarm99.ownerUniqueName())
        assertEquals("acx_owner_wake_confirm_42_inst-shared_100_rev1", identityAlarm42.ownerUniqueName())
        assertEquals("acx_owner_wake_confirm_99_inst-shared_100_rev1", identityAlarm99.ownerUniqueName())
    }

    @Test
    fun `different work type produces distinct prefixes and unique names`() {
        val baseIdentity = OwnerWorkIdentity(
            alarmId = 42L,
            instanceToken = "inst-42",
            restoreEpoch = 100L,
            scheduleRevision = 1L,
            workType = SupportingWorkType.HUE_SUNRISE
        )

        val hue = baseIdentity.copy(workType = SupportingWorkType.HUE_SUNRISE)
        val watchdog = baseIdentity.copy(workType = SupportingWorkType.FIRE_WATCHDOG)
        val guardian = baseIdentity.copy(workType = SupportingWorkType.GUARDIAN)
        val wakeConfirm = baseIdentity.copy(workType = SupportingWorkType.WAKE_CONFIRM)

        assertEquals("acx_owner_hue_sunrise_42_inst-42_100_rev1", hue.ownerUniqueName())
        assertEquals("acx_owner_fire_watchdog_42_inst-42_100_rev1", watchdog.ownerUniqueName())
        assertEquals("acx_owner_guardian_42_inst-42_100_rev1", guardian.ownerUniqueName())
        assertEquals("acx_owner_wake_confirm_42_inst-42_100_rev1", wakeConfirm.ownerUniqueName())
    }

    @Test(expected = IllegalArgumentException::class)
    fun `nonpositive alarmId is rejected`() {
        OwnerWorkIdentity(
            alarmId = 0L,
            instanceToken = "inst-42",
            restoreEpoch = 100L,
            scheduleRevision = 1L,
            workType = SupportingWorkType.HUE_SUNRISE
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun `blank instanceToken is rejected`() {
        OwnerWorkIdentity(
            alarmId = 42L,
            instanceToken = "   ",
            restoreEpoch = 100L,
            scheduleRevision = 1L,
            workType = SupportingWorkType.HUE_SUNRISE
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun `negative restoreEpoch is rejected`() {
        OwnerWorkIdentity(
            alarmId = 42L,
            instanceToken = "inst-42",
            restoreEpoch = -1L,
            scheduleRevision = 1L,
            workType = SupportingWorkType.HUE_SUNRISE
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun `nonpositive scheduleRevision is rejected`() {
        OwnerWorkIdentity(
            alarmId = 42L,
            instanceToken = "inst-42",
            restoreEpoch = 100L,
            scheduleRevision = 0L,
            workType = SupportingWorkType.HUE_SUNRISE
        )
    }

    @Test
    fun `fromAuthority maps ScheduleAuthority entity fields accurately`() {
        val authority = ScheduleAuthority(
            alarmId = 77L,
            instanceToken = "inst-77",
            definitionRevision = 3L,
            scheduleRevision = 5L,
            lifecycle = "LIVE",
            desiredOperation = "SET",
            desiredTriggerMillis = 1700000000000L,
            reconcileState = "SETTLED",
            restoreEpoch = 200L,
            actionKey = "act-77"
        )

        val identity = OwnerWorkIdentity.fromAuthority(authority, SupportingWorkType.FIRE_WATCHDOG)

        assertEquals(77L, identity.alarmId)
        assertEquals("inst-77", identity.instanceToken)
        assertEquals(200L, identity.restoreEpoch)
        assertEquals(5L, identity.scheduleRevision)
        assertEquals(SupportingWorkType.FIRE_WATCHDOG, identity.workType)
        assertEquals("acx_owner_fire_watchdog_77_inst-77_200_rev5", identity.ownerUniqueName())
    }

    @Test
    fun `occurrenceUniqueName formats occurrence-bound names deterministically`() {
        val occName = OwnerWorkIdentity.occurrenceUniqueName(
            alarmId = 42L,
            scheduledAt = 1700000000000L,
            workType = SupportingWorkType.HUE_SUNRISE
        )
        assertEquals("acx_occ_hue_sunrise_42_1700000000000", occName)
    }

    @Test
    fun `legacyUniqueName matches main codebase exact legacy names`() {
        assertEquals("hue_sunrise_42", OwnerWorkIdentity.legacyUniqueName(42L, SupportingWorkType.HUE_SUNRISE))
        assertEquals("fire_watchdog_42", OwnerWorkIdentity.legacyUniqueName(42L, SupportingWorkType.FIRE_WATCHDOG))
        assertEquals("guardian_42", OwnerWorkIdentity.legacyUniqueName(42L, SupportingWorkType.GUARDIAN))
        assertEquals("wake_confirm_42", OwnerWorkIdentity.legacyUniqueName(42L, SupportingWorkType.WAKE_CONFIRM))
    }
}
