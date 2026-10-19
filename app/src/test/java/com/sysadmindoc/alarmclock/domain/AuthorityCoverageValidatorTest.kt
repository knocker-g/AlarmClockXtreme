package com.sysadmindoc.alarmclock.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AuthorityCoverageValidatorTest {

    private val validMeta = MetaDescriptor(
        id = 1,
        restoreEpoch = 100L,
        bootstrapState = "ENFORCED"
    )

    private val validAlarm = AlarmDescriptor(
        id = 42L,
        enabled = true
    )

    private val validOwner = OwnerDescriptor(
        alarmId = 42L,
        instanceToken = "token-abc-123",
        definitionRevision = 1L,
        scheduleRevision = 1L,
        lifecycle = "LIVE",
        desiredOperation = "SET",
        desiredTriggerMillis = 1_000_000L,
        reconcileState = "SETTLED",
        restoreEpoch = 100L
    )

    @Test
    fun `uninitialized upgraded database snapshot produces UNINITIALIZED`() {
        val report = AuthorityCoverageValidator.validate(
            alarms = listOf(validAlarm),
            owners = emptyList(),
            meta = null
        )
        assertEquals(CoverageResult.UNINITIALIZED, report.result)
        assertEquals(CoverageReasonCode.META_ABSENT_AUTHORITY_EMPTY, report.reasonCode)
    }

    @Test
    fun `stray owner without metadata produces INVALID`() {
        val report = AuthorityCoverageValidator.validate(
            alarms = listOf(validAlarm),
            owners = listOf(validOwner),
            meta = null
        )
        assertEquals(CoverageResult.INVALID, report.result)
        assertEquals(CoverageReasonCode.META_ABSENT_AUTHORITY_PRESENT, report.reasonCode)
    }

    @Test
    fun `empty ENFORCED snapshot produces COMPLETE`() {
        val report = AuthorityCoverageValidator.validate(
            alarms = emptyList(),
            owners = emptyList(),
            meta = validMeta
        )
        assertEquals(CoverageResult.COMPLETE, report.result)
        assertEquals(CoverageReasonCode.COVERAGE_VALID_COMPLETE, report.reasonCode)
    }

    @Test
    fun `PREPARING bootstrap state produces INCOMPLETE`() {
        val metaPreparing = validMeta.copy(bootstrapState = "PREPARING")
        val report = AuthorityCoverageValidator.validate(
            alarms = listOf(validAlarm),
            owners = listOf(validOwner),
            meta = metaPreparing
        )
        assertEquals(CoverageResult.INCOMPLETE, report.result)
        assertEquals(CoverageReasonCode.BOOTSTRAP_PREPARING, report.reasonCode)
    }

    @Test
    fun `enabled Alarm with LIVE owner and SET produces COMPLETE`() {
        val report = AuthorityCoverageValidator.validate(
            alarms = listOf(validAlarm),
            owners = listOf(validOwner),
            meta = validMeta
        )
        assertEquals(CoverageResult.COMPLETE, report.result)
        assertEquals(CoverageReasonCode.COVERAGE_VALID_COMPLETE, report.reasonCode)
    }

    @Test
    fun `enabled Alarm with LIVE owner and CANCEL produces COMPLETE`() {
        val cancelOwner = validOwner.copy(
            desiredOperation = "CANCEL",
            desiredTriggerMillis = null
        )
        val report = AuthorityCoverageValidator.validate(
            alarms = listOf(validAlarm),
            owners = listOf(cancelOwner),
            meta = validMeta
        )
        assertEquals(CoverageResult.COMPLETE, report.result)
        assertEquals(CoverageReasonCode.COVERAGE_VALID_COMPLETE, report.reasonCode)
    }

    @Test
    fun `disabled Alarm with LIVE owner and CANCEL produces COMPLETE`() {
        val disabledAlarm = validAlarm.copy(enabled = false)
        val cancelOwner = validOwner.copy(
            desiredOperation = "CANCEL",
            desiredTriggerMillis = null
        )
        val report = AuthorityCoverageValidator.validate(
            alarms = listOf(disabledAlarm),
            owners = listOf(cancelOwner),
            meta = validMeta
        )
        assertEquals(CoverageResult.COMPLETE, report.result)
        assertEquals(CoverageReasonCode.COVERAGE_VALID_COMPLETE, report.reasonCode)
    }

    @Test
    fun `disabled Alarm with LIVE owner and SET produces INVALID`() {
        val disabledAlarm = validAlarm.copy(enabled = false)
        val report = AuthorityCoverageValidator.validate(
            alarms = listOf(disabledAlarm),
            owners = listOf(validOwner),
            meta = validMeta
        )
        assertEquals(CoverageResult.INVALID, report.result)
        assertEquals(CoverageReasonCode.DISABLED_ALARM_DESIRED_SET, report.reasonCode)
    }

    @Test
    fun `missing owner for existing Alarm produces INCOMPLETE`() {
        val report = AuthorityCoverageValidator.validate(
            alarms = listOf(validAlarm),
            owners = emptyList(),
            meta = validMeta
        )
        assertEquals(CoverageResult.INCOMPLETE, report.result)
        assertEquals(CoverageReasonCode.UNCOVERED_ALARM, report.reasonCode)
    }

    @Test
    fun `orphan LIVE owner without Alarm definition produces INCOMPLETE`() {
        val report = AuthorityCoverageValidator.validate(
            alarms = emptyList(),
            owners = listOf(validOwner),
            meta = validMeta
        )
        assertEquals(CoverageResult.INCOMPLETE, report.result)
        assertEquals(CoverageReasonCode.ORPHAN_LIVE_OWNER, report.reasonCode)
    }

    @Test
    fun `valid TOMBSTONE owner without Alarm definition produces COMPLETE`() {
        val tombstoneOwner = OwnerDescriptor(
            alarmId = 99L,
            instanceToken = "token-tombstone-99",
            definitionRevision = 2L,
            scheduleRevision = 2L,
            lifecycle = "TOMBSTONE",
            desiredOperation = "CANCEL",
            desiredTriggerMillis = null,
            reconcileState = "SETTLED",
            restoreEpoch = 100L
        )
        val report = AuthorityCoverageValidator.validate(
            alarms = emptyList(),
            owners = listOf(tombstoneOwner),
            meta = validMeta
        )
        assertEquals(CoverageResult.COMPLETE, report.result)
        assertEquals(CoverageReasonCode.COVERAGE_VALID_COMPLETE, report.reasonCode)
    }

    @Test
    fun `invalid TOMBSTONE owner colliding with existing Alarm produces INVALID`() {
        val tombstoneOwner = validOwner.copy(
            lifecycle = "TOMBSTONE",
            desiredOperation = "CANCEL",
            desiredTriggerMillis = null
        )
        val report = AuthorityCoverageValidator.validate(
            alarms = listOf(validAlarm),
            owners = listOf(tombstoneOwner),
            meta = validMeta
        )
        assertEquals(CoverageResult.INVALID, report.result)
        assertEquals(CoverageReasonCode.TOMBSTONE_ALARM_COLLISION, report.reasonCode)
    }

    @Test
    fun `duplicate Alarm IDs or Owner IDs produce INVALID`() {
        // Duplicate Alarm IDs
        val duplicateAlarmsReport = AuthorityCoverageValidator.validate(
            alarms = listOf(validAlarm, validAlarm),
            owners = listOf(validOwner),
            meta = validMeta
        )
        assertEquals(CoverageResult.INVALID, duplicateAlarmsReport.result)
        assertEquals(CoverageReasonCode.DUPLICATE_ALARM_ID, duplicateAlarmsReport.reasonCode)

        // Duplicate Owner IDs
        val duplicateOwnersReport = AuthorityCoverageValidator.validate(
            alarms = listOf(validAlarm),
            owners = listOf(validOwner, validOwner),
            meta = validMeta
        )
        assertEquals(CoverageResult.INVALID, duplicateOwnersReport.result)
        assertEquals(CoverageReasonCode.DUPLICATE_OWNER_ID, duplicateOwnersReport.reasonCode)
    }

    @Test
    fun `malformed owner fields or mismatched restoreEpoch produce INVALID`() {
        // Malformed owner identifier
        val malformedIdReport = AuthorityCoverageValidator.validate(
            alarms = listOf(validAlarm),
            owners = listOf(validOwner.copy(alarmId = 0L)),
            meta = validMeta
        )
        assertEquals(CoverageResult.INVALID, malformedIdReport.result)
        assertEquals(CoverageReasonCode.MALFORMED_OWNER_IDENTIFIER, malformedIdReport.reasonCode)

        // Blank instanceToken
        val blankTokenReport = AuthorityCoverageValidator.validate(
            alarms = listOf(validAlarm),
            owners = listOf(validOwner.copy(instanceToken = "   ")),
            meta = validMeta
        )
        assertEquals(CoverageResult.INVALID, blankTokenReport.result)
        assertEquals(CoverageReasonCode.MALFORMED_OWNER_IDENTIFIER, blankTokenReport.reasonCode)

        // Nonpositive definition revision
        val nonpositiveDefRevReport = AuthorityCoverageValidator.validate(
            alarms = listOf(validAlarm),
            owners = listOf(validOwner.copy(definitionRevision = 0L)),
            meta = validMeta
        )
        assertEquals(CoverageResult.INVALID, nonpositiveDefRevReport.result)
        assertEquals(CoverageReasonCode.NONPOSITIVE_REVISION, nonpositiveDefRevReport.reasonCode)

        // Nonpositive schedule revision
        val nonpositiveSchRevReport = AuthorityCoverageValidator.validate(
            alarms = listOf(validAlarm),
            owners = listOf(validOwner.copy(scheduleRevision = -1L)),
            meta = validMeta
        )
        assertEquals(CoverageResult.INVALID, nonpositiveSchRevReport.result)
        assertEquals(CoverageReasonCode.NONPOSITIVE_REVISION, nonpositiveSchRevReport.reasonCode)

        // Mismatched restoreEpoch
        val mismatchedEpochReport = AuthorityCoverageValidator.validate(
            alarms = listOf(validAlarm),
            owners = listOf(validOwner.copy(restoreEpoch = 999L)),
            meta = validMeta
        )
        assertEquals(CoverageResult.INVALID, mismatchedEpochReport.result)
        assertEquals(CoverageReasonCode.MISMATCHED_RESTORE_EPOCH, mismatchedEpochReport.reasonCode)

        // Meta ID != 1
        val invalidMetaIdReport = AuthorityCoverageValidator.validate(
            alarms = listOf(validAlarm),
            owners = listOf(validOwner),
            meta = validMeta.copy(id = 2)
        )
        assertEquals(CoverageResult.INVALID, invalidMetaIdReport.result)
        assertEquals(CoverageReasonCode.META_ID_INVALID, invalidMetaIdReport.reasonCode)

        // Meta restoreEpoch negative
        val negativeMetaEpochReport = AuthorityCoverageValidator.validate(
            alarms = listOf(validAlarm),
            owners = listOf(validOwner),
            meta = validMeta.copy(restoreEpoch = -1L)
        )
        assertEquals(CoverageResult.INVALID, negativeMetaEpochReport.result)
        assertEquals(CoverageReasonCode.META_EPOCH_NEGATIVE, negativeMetaEpochReport.reasonCode)
    }

    @Test
    fun `invalid state vocabulary produces INVALID`() {
        // Invalid bootstrapState
        val invalidMetaStateReport = AuthorityCoverageValidator.validate(
            alarms = listOf(validAlarm),
            owners = listOf(validOwner),
            meta = validMeta.copy(bootstrapState = "UNKNOWN_STATE")
        )
        assertEquals(CoverageResult.INVALID, invalidMetaStateReport.result)
        assertEquals(CoverageReasonCode.META_BOOTSTRAP_STATE_INVALID, invalidMetaStateReport.reasonCode)

        // Invalid lifecycle
        val invalidLifecycleReport = AuthorityCoverageValidator.validate(
            alarms = listOf(validAlarm),
            owners = listOf(validOwner.copy(lifecycle = "ACTIVE")),
            meta = validMeta
        )
        assertEquals(CoverageResult.INVALID, invalidLifecycleReport.result)
        assertEquals(CoverageReasonCode.INVALID_LIFECYCLE, invalidLifecycleReport.reasonCode)

        // Invalid desiredOperation
        val invalidOperationReport = AuthorityCoverageValidator.validate(
            alarms = listOf(validAlarm),
            owners = listOf(validOwner.copy(desiredOperation = "SNOOZE")),
            meta = validMeta
        )
        assertEquals(CoverageResult.INVALID, invalidOperationReport.result)
        assertEquals(CoverageReasonCode.INVALID_OPERATION, invalidOperationReport.reasonCode)

        // Invalid reconcileState
        val invalidReconcileReport = AuthorityCoverageValidator.validate(
            alarms = listOf(validAlarm),
            owners = listOf(validOwner.copy(reconcileState = "DONE")),
            meta = validMeta
        )
        assertEquals(CoverageResult.INVALID, invalidReconcileReport.result)
        assertEquals(CoverageReasonCode.INVALID_RECONCILE_STATE, invalidReconcileReport.reasonCode)
    }

    @Test
    fun `invalid SET or CANCEL trigger timestamps produce INVALID`() {
        // SET with null trigger
        val setNullTriggerReport = AuthorityCoverageValidator.validate(
            alarms = listOf(validAlarm),
            owners = listOf(validOwner.copy(desiredOperation = "SET", desiredTriggerMillis = null)),
            meta = validMeta
        )
        assertEquals(CoverageResult.INVALID, setNullTriggerReport.result)
        assertEquals(CoverageReasonCode.INVALID_TRIGGER_FOR_OPERATION, setNullTriggerReport.reasonCode)

        // SET with nonpositive trigger
        val setZeroTriggerReport = AuthorityCoverageValidator.validate(
            alarms = listOf(validAlarm),
            owners = listOf(validOwner.copy(desiredOperation = "SET", desiredTriggerMillis = 0L)),
            meta = validMeta
        )
        assertEquals(CoverageResult.INVALID, setZeroTriggerReport.result)
        assertEquals(CoverageReasonCode.INVALID_TRIGGER_FOR_OPERATION, setZeroTriggerReport.reasonCode)

        // CANCEL with non-null trigger
        val cancelNonNullTriggerReport = AuthorityCoverageValidator.validate(
            alarms = listOf(validAlarm),
            owners = listOf(validOwner.copy(desiredOperation = "CANCEL", desiredTriggerMillis = 500L)),
            meta = validMeta
        )
        assertEquals(CoverageResult.INVALID, cancelNonNullTriggerReport.result)
        assertEquals(CoverageReasonCode.INVALID_TRIGGER_FOR_OPERATION, cancelNonNullTriggerReport.reasonCode)
    }

    @Test
    fun `validation reports contain deterministic diagnostics without personal data`() {
        val report = AuthorityCoverageValidator.validate(
            alarms = listOf(validAlarm),
            owners = listOf(validOwner),
            meta = validMeta
        )
        assertEquals(CoverageResult.COMPLETE, report.result)
        assertEquals(CoverageReasonCode.COVERAGE_VALID_COMPLETE, report.reasonCode)
        assertTrue("Diagnostic details must be non-blank", report.details.isNotBlank())
        assertNotEquals("Diagnostic details must not contain personal data", "my_alarm_label", report.details)
    }

    @Test
    fun `permuted list order produces equivalent validation result`() {
        val alarm1 = AlarmDescriptor(id = 10L, enabled = true)
        val alarm2 = AlarmDescriptor(id = 20L, enabled = false)
        val owner1 = OwnerDescriptor(
            alarmId = 10L,
            instanceToken = "tok-10",
            definitionRevision = 1L,
            scheduleRevision = 1L,
            lifecycle = "LIVE",
            desiredOperation = "SET",
            desiredTriggerMillis = 1000L,
            reconcileState = "SETTLED",
            restoreEpoch = 100L
        )
        val owner2 = OwnerDescriptor(
            alarmId = 20L,
            instanceToken = "tok-20",
            definitionRevision = 1L,
            scheduleRevision = 1L,
            lifecycle = "LIVE",
            desiredOperation = "CANCEL",
            desiredTriggerMillis = null,
            reconcileState = "SETTLED",
            restoreEpoch = 100L
        )

        val reportOrder1 = AuthorityCoverageValidator.validate(
            alarms = listOf(alarm1, alarm2),
            owners = listOf(owner1, owner2),
            meta = validMeta
        )
        val reportOrder2 = AuthorityCoverageValidator.validate(
            alarms = listOf(alarm2, alarm1),
            owners = listOf(owner2, owner1),
            meta = validMeta
        )

        assertEquals(reportOrder1.result, reportOrder2.result)
        assertEquals(reportOrder1.reasonCode, reportOrder2.reasonCode)
        assertEquals(CoverageResult.COMPLETE, reportOrder1.result)
    }
}
