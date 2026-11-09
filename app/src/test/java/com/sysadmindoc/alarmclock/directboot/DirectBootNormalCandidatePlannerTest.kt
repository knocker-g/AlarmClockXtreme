package com.sysadmindoc.alarmclock.directboot

import android.app.Application
import com.sysadmindoc.alarmclock.data.local.entity.ScheduleAuthority
import com.sysadmindoc.alarmclock.data.local.entity.ScheduleAuthorityMeta
import com.sysadmindoc.alarmclock.data.model.Alarm
import com.sysadmindoc.alarmclock.domain.CoverageReasonCode
import com.sysadmindoc.alarmclock.domain.CoverageResult
import com.sysadmindoc.alarmclock.domain.CoverageValidationReport
import com.sysadmindoc.alarmclock.domain.ScheduleAuthorityBootstrapAdapter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Deterministic unit tests for KNO-75 Phase B1B0C Normal Planner Source-Truth Correction.
 *
 * Requirements covered:
 * - P01 Valid Normal ONE_TIME alarm returns ValidWithCandidates
 * - P02 Valid Normal REPEATING alarm returns ValidWithCandidates
 * - P03 Strict exclusion of Quick alarms
 * - P04 Exclusion of disabled alarms
 * - P05 Exclusion of expired past triggers
 * - P06 Rejection of snapshot with missing authority for enabled normal alarm
 * - P07 Rejection of snapshot when authority instanceToken is blank
 * - P08 Rejection of snapshot when alarm generation is non-positive
 * - P09 Positive canonical Definition Revision and rejection of genuinely stale authority values
 * - P10 Rejection of snapshot when authority scheduleRevision is non-positive
 * - P11 Rejection of snapshot when authority restoreEpoch mismatches meta restoreEpoch
 * - P12 Rejection of snapshot when meta restoreEpoch is negative
 * - P13 Rejection of snapshot when authority trigger mismatches alarm nextTriggerTime
 * - P14 Rejection of snapshot when authority desiredOperation is not SET for enabled alarm
 * - P15 Rejection of snapshot when authority lifecycle is not LIVE
 * - P16 Unresolved PENDING authority returns explicit UnresolvedAuthority blocked result
 * - P17 Unresolved QUARANTINE authority returns explicit UnresolvedAuthority blocked result
 * - P18 Unresolved RETRY_REQUIRED authority returns explicit UnresolvedAuthority blocked result
 * - P19 Mixed SETTLED and PENDING authorities prohibits partial authoritative replacement
 * - P20 Coverage recomputation rejects inconsistent supplied COMPLETE report on missing owner
 * - P21 Missing Meta fails closed regardless of supplied COMPLETE coverage report
 * - P22 Invalid Meta bootstrapState fails closed regardless of supplied COMPLETE report
 * - P23 Deterministic chronological ordering and nearest-five truncation for 6+ alarms
 * - P24 Stable tie-breaking by alarmId when trigger times are identical
 * - P25 ValidEmpty returned when snapshot is coherent but zero eligible candidates
 * - P26 Input collections immutability preserved
 * - P27 Determinism and idempotence on repeated planning
 * - P28 Rejection of snapshot with duplicate alarm IDs
 * - P29 Rejection of snapshot with duplicate authority alarm IDs
 * - P30 Rejection of snapshot with multiple meta rows
 * - P31 Rejection of snapshot with non-positive alarm ID
 * - P32 Safe timeLabel generation for LOCAL and FIXED timezone policies
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class DirectBootNormalCandidatePlannerTest {

    private val baseNow = 1_000_000L
    private val futureTrigger = 2_000_000L
    private val baseEpoch = 42L

    private fun createValidNormalAlarm(
        id: Long = 1L,
        hour: Int = 9,
        minute: Int = 0,
        nextTriggerTime: Long = futureTrigger,
        isEnabled: Boolean = true,
        kind: String = Alarm.KIND_NORMAL,
        scheduleKind: String = Alarm.SCHEDULE_KIND_ONE_TIME,
        generation: Int = 1,
        revision: Int = 1,
        timezonePolicy: String = Alarm.TIMEZONE_POLICY_LOCAL,
        fixedTimezoneId: String = ""
    ): Alarm {
        return Alarm(
            id = id,
            hour = hour,
            minute = minute,
            isEnabled = isEnabled,
            nextTriggerTime = nextTriggerTime,
            kind = kind,
            scheduleKind = scheduleKind,
            generation = generation,
            revision = revision,
            timezonePolicy = timezonePolicy,
            fixedTimezoneId = fixedTimezoneId
        )
    }

    private fun createValidAuthority(
        alarm: Alarm,
        instanceToken: String = "token-alarm-${alarm.id}",
        definitionRevision: Long = ScheduleAuthorityBootstrapAdapter.computeDefinitionRevision(alarm),
        scheduleRevision: Long = 1L,
        lifecycle: String = "LIVE",
        desiredOperation: String = if (alarm.isEnabled) "SET" else "CANCEL",
        desiredTriggerMillis: Long? = if (alarm.isEnabled) alarm.nextTriggerTime else null,
        reconcileState: String = "SETTLED",
        restoreEpoch: Long = baseEpoch,
        actionKey: String = "action-${alarm.id}"
    ): ScheduleAuthority {
        return ScheduleAuthority(
            alarmId = alarm.id,
            instanceToken = instanceToken,
            definitionRevision = definitionRevision,
            scheduleRevision = scheduleRevision,
            lifecycle = lifecycle,
            desiredOperation = desiredOperation,
            desiredTriggerMillis = desiredTriggerMillis,
            reconcileState = reconcileState,
            restoreEpoch = restoreEpoch,
            actionKey = actionKey
        )
    }

    private fun createValidMeta(
        id: Int = 1,
        restoreEpoch: Long = baseEpoch,
        bootstrapState: String = "ENFORCED"
    ): ScheduleAuthorityMeta {
        return ScheduleAuthorityMeta(
            id = id,
            restoreEpoch = restoreEpoch,
            bootstrapState = bootstrapState
        )
    }

    @Test
    fun p01_validNormalOneTimeAlarmReturnsValidWithCandidates() {
        val alarm = createValidNormalAlarm(id = 10L, scheduleKind = Alarm.SCHEDULE_KIND_ONE_TIME)
        val authority = createValidAuthority(alarm)
        val meta = createValidMeta()

        val result = DirectBootNormalCandidatePlanner.planCandidates(
            alarms = listOf(alarm),
            authorities = listOf(authority),
            meta = meta,
            now = baseNow
        )

        assertTrue(result is NormalCandidatePlanResult.ValidWithCandidates)
        val candidates = (result as NormalCandidatePlanResult.ValidWithCandidates).candidates
        assertEquals(1, candidates.size)

        val item = candidates[0]
        assertEquals(DirectBootQueueItem.KIND_NORMAL, item.kind)
        assertEquals(10L, item.alarmId)
        assertEquals(futureTrigger, item.triggerTime)
        assertEquals(1, item.generation)
        assertEquals(Alarm.SCHEDULE_KIND_ONE_TIME, item.scheduleKind)
        assertEquals("token-alarm-10", item.instanceToken)
        assertFalse(item.timeLabel.isBlank())
        assertEquals(baseNow, item.updatedAt)
    }

    @Test
    fun p02_validNormalRepeatingAlarmReturnsValidWithCandidates() {
        val alarm = createValidNormalAlarm(id = 20L, scheduleKind = Alarm.SCHEDULE_KIND_REPEATING)
        val authority = createValidAuthority(alarm)
        val meta = createValidMeta()

        val result = DirectBootNormalCandidatePlanner.planCandidates(
            alarms = listOf(alarm),
            authorities = listOf(authority),
            meta = meta,
            now = baseNow
        )

        assertTrue(result is NormalCandidatePlanResult.ValidWithCandidates)
        val candidates = (result as NormalCandidatePlanResult.ValidWithCandidates).candidates
        assertEquals(1, candidates.size)
        assertEquals(Alarm.SCHEDULE_KIND_REPEATING, candidates[0].scheduleKind)
    }

    @Test
    fun p03_strictExclusionOfQuickAlarms() {
        val normalAlarm = createValidNormalAlarm(id = 1L)
        val normalAuth = createValidAuthority(normalAlarm)

        val quickAlarm = createValidNormalAlarm(id = 2L, kind = Alarm.KIND_QUICK)
        val meta = createValidMeta()

        val result = DirectBootNormalCandidatePlanner.planCandidates(
            alarms = listOf(normalAlarm, quickAlarm),
            authorities = listOf(normalAuth),
            meta = meta,
            now = baseNow
        )

        assertTrue(result is NormalCandidatePlanResult.ValidWithCandidates)
        val candidates = (result as NormalCandidatePlanResult.ValidWithCandidates).candidates
        assertEquals(1, candidates.size)
        assertEquals(1L, candidates[0].alarmId)

        // Only quick alarms yields ValidEmpty
        val quickOnlyResult = DirectBootNormalCandidatePlanner.planCandidates(
            alarms = listOf(quickAlarm),
            authorities = emptyList(),
            meta = meta,
            now = baseNow
        )
        assertEquals(NormalCandidatePlanResult.ValidEmpty, quickOnlyResult)
    }

    @Test
    fun p04_exclusionOfDisabledAlarms() {
        val disabledAlarm = createValidNormalAlarm(id = 1L, isEnabled = false)
        val auth = createValidAuthority(
            alarm = disabledAlarm,
            desiredOperation = "CANCEL",
            desiredTriggerMillis = null
        )
        val meta = createValidMeta()

        val result = DirectBootNormalCandidatePlanner.planCandidates(
            alarms = listOf(disabledAlarm),
            authorities = listOf(auth),
            meta = meta,
            now = baseNow
        )

        assertEquals(NormalCandidatePlanResult.ValidEmpty, result)
    }

    @Test
    fun p05_exclusionOfExpiredPastTriggers() {
        val pastAlarm = createValidNormalAlarm(id = 1L, nextTriggerTime = baseNow - 5000L)
        val auth = createValidAuthority(pastAlarm)
        val meta = createValidMeta()

        val result = DirectBootNormalCandidatePlanner.planCandidates(
            alarms = listOf(pastAlarm),
            authorities = listOf(auth),
            meta = meta,
            now = baseNow
        )

        assertEquals(NormalCandidatePlanResult.ValidEmpty, result)
    }

    @Test
    fun p06_rejectionOfSnapshotWithMissingAuthorityForEnabledNormalAlarm() {
        val alarm = createValidNormalAlarm(id = 1L)
        val meta = createValidMeta()

        val result = DirectBootNormalCandidatePlanner.planCandidates(
            alarms = listOf(alarm),
            authorities = emptyList(),
            meta = meta,
            now = baseNow
        )

        assertTrue(result is NormalCandidatePlanResult.InvalidCoverage)
        val invalidCoverage = result as NormalCandidatePlanResult.InvalidCoverage
        assertEquals(CoverageReasonCode.UNCOVERED_ALARM, invalidCoverage.reasonCode)
    }

    @Test
    fun p07_rejectionOfSnapshotWhenAuthorityInstanceTokenIsBlank() {
        val alarm = createValidNormalAlarm(id = 1L)
        val auth = createValidAuthority(alarm, instanceToken = "   ")
        val meta = createValidMeta()

        val result = DirectBootNormalCandidatePlanner.planCandidates(
            alarms = listOf(alarm),
            authorities = listOf(auth),
            meta = meta,
            now = baseNow
        )

        assertTrue(
            result is NormalCandidatePlanResult.InvalidCoverage ||
            result is NormalCandidatePlanResult.InvalidEpochOrIdentity
        )
    }

    @Test
    fun p08_rejectionOfSnapshotWhenAlarmGenerationIsNonPositive() {
        val alarm = createValidNormalAlarm(id = 1L, generation = 0)
        val auth = createValidAuthority(alarm)
        val meta = createValidMeta()

        val result = DirectBootNormalCandidatePlanner.planCandidates(
            alarms = listOf(alarm),
            authorities = listOf(auth),
            meta = meta,
            now = baseNow
        )

        assertTrue(result is NormalCandidatePlanResult.InconsistentSnapshot)
    }

    @Test
    fun p09_positiveCanonicalDefinitionRevisionAndRejectionOfGenuinelyStaleAuthorityValues() {
        val alarm = createValidNormalAlarm(id = 1L, hour = 8, minute = 30)
        val canonicalDefRev = ScheduleAuthorityBootstrapAdapter.computeDefinitionRevision(alarm)

        // Positive fixture: exactly matches canonical production calculation
        val validAuth = createValidAuthority(alarm, definitionRevision = canonicalDefRev)
        val meta = createValidMeta()

        val validResult = DirectBootNormalCandidatePlanner.planCandidates(
            alarms = listOf(alarm),
            authorities = listOf(validAuth),
            meta = meta,
            now = baseNow
        )
        assertTrue(validResult is NormalCandidatePlanResult.ValidWithCandidates)

        // Negative fixture 1: stale authority computed from older alarm definition (hour = 9 instead of 8)
        val olderAlarm = alarm.copy(hour = 9)
        val staleDefRev = ScheduleAuthorityBootstrapAdapter.computeDefinitionRevision(olderAlarm)
        val staleAuth1 = createValidAuthority(alarm, definitionRevision = staleDefRev)

        val staleResult1 = DirectBootNormalCandidatePlanner.planCandidates(
            alarms = listOf(alarm),
            authorities = listOf(staleAuth1),
            meta = meta,
            now = baseNow
        )
        assertTrue(staleResult1 is NormalCandidatePlanResult.InconsistentSnapshot)

        // Negative fixture 2: arbitrary non-matching revision value (e.g. legacy revision 1)
        val staleAuth2 = createValidAuthority(alarm, definitionRevision = 999_999L)
        val staleResult2 = DirectBootNormalCandidatePlanner.planCandidates(
            alarms = listOf(alarm),
            authorities = listOf(staleAuth2),
            meta = meta,
            now = baseNow
        )
        assertTrue(staleResult2 is NormalCandidatePlanResult.InconsistentSnapshot)
    }

    @Test
    fun p10_rejectionOfSnapshotWhenAuthorityScheduleRevisionIsNonPositive() {
        val alarm = createValidNormalAlarm(id = 1L)
        val auth = createValidAuthority(alarm, scheduleRevision = 0L)
        val meta = createValidMeta()

        val result = DirectBootNormalCandidatePlanner.planCandidates(
            alarms = listOf(alarm),
            authorities = listOf(auth),
            meta = meta,
            now = baseNow
        )

        assertTrue(
            result is NormalCandidatePlanResult.InvalidCoverage ||
            result is NormalCandidatePlanResult.InconsistentSnapshot
        )
    }

    @Test
    fun p11_rejectionOfSnapshotWhenAuthorityRestoreEpochMismatchesMetaRestoreEpoch() {
        val alarm = createValidNormalAlarm(id = 1L)
        val auth = createValidAuthority(alarm, restoreEpoch = 100L)
        val meta = createValidMeta(restoreEpoch = 200L)

        val result = DirectBootNormalCandidatePlanner.planCandidates(
            alarms = listOf(alarm),
            authorities = listOf(auth),
            meta = meta,
            now = baseNow
        )

        assertTrue(
            result is NormalCandidatePlanResult.InvalidCoverage ||
            result is NormalCandidatePlanResult.InvalidEpochOrIdentity
        )
    }

    @Test
    fun p12_rejectionOfSnapshotWhenMetaRestoreEpochIsNegative() {
        val alarm = createValidNormalAlarm(id = 1L)
        val auth = createValidAuthority(alarm, restoreEpoch = -1L)
        val meta = createValidMeta(restoreEpoch = -1L)

        val result = DirectBootNormalCandidatePlanner.planCandidates(
            alarms = listOf(alarm),
            authorities = listOf(auth),
            meta = meta,
            now = baseNow
        )

        assertTrue(
            result is NormalCandidatePlanResult.InvalidCoverage ||
            result is NormalCandidatePlanResult.InvalidEpochOrIdentity
        )
    }

    @Test
    fun p13_rejectionOfSnapshotWhenAuthorityTriggerMismatchesAlarmNextTriggerTime() {
        val alarm = createValidNormalAlarm(id = 1L, nextTriggerTime = 2_000_000L)
        val auth = createValidAuthority(alarm, desiredTriggerMillis = 2_000_500L)
        val meta = createValidMeta()

        val result = DirectBootNormalCandidatePlanner.planCandidates(
            alarms = listOf(alarm),
            authorities = listOf(auth),
            meta = meta,
            now = baseNow
        )

        assertTrue(result is NormalCandidatePlanResult.InconsistentSnapshot)
    }

    @Test
    fun p14_rejectionOfSnapshotWhenAuthorityDesiredOperationIsNotSetForEnabledAlarm() {
        val alarm = createValidNormalAlarm(id = 1L)
        val auth = createValidAuthority(alarm, desiredOperation = "CANCEL", desiredTriggerMillis = null)
        val meta = createValidMeta()

        val result = DirectBootNormalCandidatePlanner.planCandidates(
            alarms = listOf(alarm),
            authorities = listOf(auth),
            meta = meta,
            now = baseNow
        )

        assertTrue(
            result is NormalCandidatePlanResult.InvalidCoverage ||
            result is NormalCandidatePlanResult.InconsistentSnapshot
        )
    }

    @Test
    fun p15_rejectionOfSnapshotWhenAuthorityLifecycleIsNotLive() {
        val alarm = createValidNormalAlarm(id = 1L)
        val auth = createValidAuthority(alarm, lifecycle = "TOMBSTONE")
        val meta = createValidMeta()

        val result = DirectBootNormalCandidatePlanner.planCandidates(
            alarms = listOf(alarm),
            authorities = listOf(auth),
            meta = meta,
            now = baseNow
        )

        assertTrue(
            result is NormalCandidatePlanResult.InvalidCoverage ||
            result is NormalCandidatePlanResult.InconsistentSnapshot
        )
    }

    @Test
    fun p16_unresolvedPendingAuthorityReturnsBlockedResult() {
        val alarm = createValidNormalAlarm(id = 1L)
        val auth = createValidAuthority(alarm, reconcileState = "PENDING")
        val meta = createValidMeta()

        val result = DirectBootNormalCandidatePlanner.planCandidates(
            alarms = listOf(alarm),
            authorities = listOf(auth),
            meta = meta,
            now = baseNow
        )

        assertTrue(result is NormalCandidatePlanResult.UnresolvedAuthority)
        val blocked = result as NormalCandidatePlanResult.UnresolvedAuthority
        assertEquals(1L, blocked.alarmId)
        assertEquals("PENDING", blocked.reconcileState)
    }

    @Test
    fun p17_unresolvedQuarantineAuthorityReturnsBlockedResult() {
        val alarm = createValidNormalAlarm(id = 2L)
        val auth = createValidAuthority(alarm, reconcileState = "QUARANTINE")
        val meta = createValidMeta()

        val result = DirectBootNormalCandidatePlanner.planCandidates(
            alarms = listOf(alarm),
            authorities = listOf(auth),
            meta = meta,
            now = baseNow
        )

        assertTrue(result is NormalCandidatePlanResult.UnresolvedAuthority)
        val blocked = result as NormalCandidatePlanResult.UnresolvedAuthority
        assertEquals(2L, blocked.alarmId)
        assertEquals("QUARANTINE", blocked.reconcileState)
    }

    @Test
    fun p18_unresolvedRetryRequiredAuthorityReturnsBlockedResult() {
        val alarm = createValidNormalAlarm(id = 3L)
        val auth = createValidAuthority(alarm, reconcileState = "RETRY_REQUIRED")
        val meta = createValidMeta()

        val result = DirectBootNormalCandidatePlanner.planCandidates(
            alarms = listOf(alarm),
            authorities = listOf(auth),
            meta = meta,
            now = baseNow
        )

        assertTrue(result is NormalCandidatePlanResult.UnresolvedAuthority)
        val blocked = result as NormalCandidatePlanResult.UnresolvedAuthority
        assertEquals(3L, blocked.alarmId)
        assertEquals("RETRY_REQUIRED", blocked.reconcileState)
    }

    @Test
    fun p19_mixedSettledAndUnresolvedAuthoritiesProhibitsPartialReplacement() {
        val alarm1 = createValidNormalAlarm(id = 1L, nextTriggerTime = futureTrigger)
        val auth1Settled = createValidAuthority(alarm1, reconcileState = "SETTLED")

        val alarm2 = createValidNormalAlarm(id = 2L, nextTriggerTime = futureTrigger + 5000L)
        val auth2Pending = createValidAuthority(alarm2, reconcileState = "PENDING")

        val meta = createValidMeta()

        val result = DirectBootNormalCandidatePlanner.planCandidates(
            alarms = listOf(alarm1, alarm2),
            authorities = listOf(auth1Settled, auth2Pending),
            meta = meta,
            now = baseNow
        )

        // Strict invariant: Prohibits partial authoritative replacement with only alarm 1
        assertTrue(result is NormalCandidatePlanResult.UnresolvedAuthority)
        val blocked = result as NormalCandidatePlanResult.UnresolvedAuthority
        assertEquals(2L, blocked.alarmId)
        assertEquals("PENDING", blocked.reconcileState)
    }

    @Test
    fun p20_coverageRecomputationRejectsInconsistentSuppliedCompleteReport() {
        val alarm = createValidNormalAlarm(id = 1L)
        val meta = createValidMeta()

        // Caller passes COMPLETE report, but snapshot is missing authority for alarm 1
        val falseCompleteReport = CoverageValidationReport(
            result = CoverageResult.COMPLETE,
            reasonCode = CoverageReasonCode.COVERAGE_VALID_COMPLETE,
            details = "Fabricated complete report"
        )

        val result = DirectBootNormalCandidatePlanner.planCandidates(
            alarms = listOf(alarm),
            authorities = emptyList(),
            meta = meta,
            coverageReport = falseCompleteReport,
            now = baseNow
        )

        // Must recompute coverage from snapshot entities and fail closed
        assertTrue(result is NormalCandidatePlanResult.InvalidCoverage)
        val invalidCoverage = result as NormalCandidatePlanResult.InvalidCoverage
        assertEquals(CoverageReasonCode.UNCOVERED_ALARM, invalidCoverage.reasonCode)
    }

    @Test
    fun p21_missingMetaFailsClosedRegardlessOfSuppliedCompleteReport() {
        val alarm = createValidNormalAlarm(id = 1L)
        val auth = createValidAuthority(alarm)

        val falseCompleteReport = CoverageValidationReport(
            result = CoverageResult.COMPLETE,
            reasonCode = CoverageReasonCode.COVERAGE_VALID_COMPLETE
        )

        val result = DirectBootNormalCandidatePlanner.planCandidates(
            alarms = listOf(alarm),
            authorities = listOf(auth),
            meta = null,
            coverageReport = falseCompleteReport,
            now = baseNow
        )

        // Missing meta must fail closed without manufacturing restore epoch
        assertTrue(result is NormalCandidatePlanResult.InvalidCoverage)
        val invalidCoverage = result as NormalCandidatePlanResult.InvalidCoverage
        assertEquals(CoverageResult.UNINITIALIZED, invalidCoverage.result)
        assertEquals(CoverageReasonCode.META_ABSENT_AUTHORITY_EMPTY, invalidCoverage.reasonCode)
    }

    @Test
    fun p22_invalidMetaBootstrapStatePreparingFailsClosedRegardlessOfSuppliedCompleteReport() {
        val alarm = createValidNormalAlarm(id = 1L)
        val auth = createValidAuthority(alarm)
        val preparingMeta = createValidMeta(bootstrapState = "PREPARING")

        val falseCompleteReport = CoverageValidationReport(
            result = CoverageResult.COMPLETE,
            reasonCode = CoverageReasonCode.COVERAGE_VALID_COMPLETE
        )

        val result = DirectBootNormalCandidatePlanner.planCandidates(
            alarms = listOf(alarm),
            authorities = listOf(auth),
            meta = preparingMeta,
            coverageReport = falseCompleteReport,
            now = baseNow
        )

        assertTrue(result is NormalCandidatePlanResult.InvalidCoverage)
        val invalidCoverage = result as NormalCandidatePlanResult.InvalidCoverage
        assertEquals(CoverageReasonCode.BOOTSTRAP_PREPARING, invalidCoverage.reasonCode)
    }

    @Test
    fun p23_deterministicChronologicalOrderingAndNearestFiveTruncationForSixAlarms() {
        val alarms = (1L..6L).map { id ->
            createValidNormalAlarm(id = id, nextTriggerTime = futureTrigger + (7L - id) * 10_000L)
        }
        val authorities = alarms.map { createValidAuthority(it) }
        val meta = createValidMeta()

        val result = DirectBootNormalCandidatePlanner.planCandidates(
            alarms = alarms,
            authorities = authorities,
            meta = meta,
            now = baseNow
        )

        assertTrue(result is NormalCandidatePlanResult.ValidWithCandidates)
        val candidates = (result as NormalCandidatePlanResult.ValidWithCandidates).candidates
        assertEquals(5, candidates.size)

        // Chronologically ordered: id 6 (smallest trigger) to id 2. id 1 (latest) is truncated.
        assertEquals(6L, candidates[0].alarmId)
        assertEquals(5L, candidates[1].alarmId)
        assertEquals(4L, candidates[2].alarmId)
        assertEquals(3L, candidates[3].alarmId)
        assertEquals(2L, candidates[4].alarmId)
    }

    @Test
    fun p24_stableTieBreakingByAlarmIdWhenTriggerTimesAreIdentical() {
        val alarm10 = createValidNormalAlarm(id = 10L, nextTriggerTime = futureTrigger)
        val alarm5 = createValidNormalAlarm(id = 5L, nextTriggerTime = futureTrigger)

        val auth10 = createValidAuthority(alarm10)
        val auth5 = createValidAuthority(alarm5)

        val meta = createValidMeta()

        val result = DirectBootNormalCandidatePlanner.planCandidates(
            alarms = listOf(alarm10, alarm5),
            authorities = listOf(auth10, auth5),
            meta = meta,
            now = baseNow
        )

        assertTrue(result is NormalCandidatePlanResult.ValidWithCandidates)
        val candidates = (result as NormalCandidatePlanResult.ValidWithCandidates).candidates
        assertEquals(2, candidates.size)
        // Tie-break by alarmId ascending
        assertEquals(5L, candidates[0].alarmId)
        assertEquals(10L, candidates[1].alarmId)
    }

    @Test
    fun p25_validEmptyReturnedWhenSnapshotIsCoherentButZeroEligibleCandidates() {
        val disabledAlarm = createValidNormalAlarm(id = 1L, isEnabled = false)
        val auth = createValidAuthority(
            alarm = disabledAlarm,
            desiredOperation = "CANCEL",
            desiredTriggerMillis = null
        )
        val meta = createValidMeta()

        val result = DirectBootNormalCandidatePlanner.planCandidates(
            alarms = listOf(disabledAlarm),
            authorities = listOf(auth),
            meta = meta,
            now = baseNow
        )

        assertEquals(NormalCandidatePlanResult.ValidEmpty, result)
    }

    @Test
    fun p26_inputCollectionsImmutabilityPreserved() {
        val originalAlarm = createValidNormalAlarm(id = 1L)
        val originalAuth = createValidAuthority(originalAlarm)
        val meta = createValidMeta()

        val alarmsCopy = ArrayList(listOf(originalAlarm))
        val authsCopy = ArrayList(listOf(originalAuth))

        DirectBootNormalCandidatePlanner.planCandidates(
            alarms = alarmsCopy,
            authorities = authsCopy,
            meta = meta,
            now = baseNow
        )

        assertEquals(listOf(originalAlarm), alarmsCopy)
        assertEquals(listOf(originalAuth), authsCopy)
    }

    @Test
    fun p27_determinismAndIdempotenceOnRepeatedPlanning() {
        val alarms = (1L..3L).map {
            createValidNormalAlarm(id = it, nextTriggerTime = futureTrigger + it * 1000L)
        }
        val authorities = alarms.map { createValidAuthority(it) }
        val meta = createValidMeta()

        val result1 = DirectBootNormalCandidatePlanner.planCandidates(alarms, authorities, meta, now = baseNow)
        val result2 = DirectBootNormalCandidatePlanner.planCandidates(alarms, authorities, meta, now = baseNow)

        assertEquals(result1, result2)
    }

    @Test
    fun p28_rejectionOfSnapshotWithDuplicateAlarmIds() {
        val alarm1 = createValidNormalAlarm(id = 1L)
        val alarm2 = createValidNormalAlarm(id = 1L)
        val auth = createValidAuthority(alarm1)
        val meta = createValidMeta()

        val result = DirectBootNormalCandidatePlanner.planCandidates(
            alarms = listOf(alarm1, alarm2),
            authorities = listOf(auth),
            meta = meta,
            now = baseNow
        )

        assertTrue(result is NormalCandidatePlanResult.InconsistentSnapshot)
    }

    @Test
    fun p29_rejectionOfSnapshotWithDuplicateAuthorityAlarmIds() {
        val alarm = createValidNormalAlarm(id = 1L)
        val auth1 = createValidAuthority(alarm)
        val auth2 = createValidAuthority(alarm)
        val meta = createValidMeta()

        val result = DirectBootNormalCandidatePlanner.planCandidates(
            alarms = listOf(alarm),
            authorities = listOf(auth1, auth2),
            meta = meta,
            now = baseNow
        )

        assertTrue(result is NormalCandidatePlanResult.InconsistentSnapshot)
    }

    @Test
    fun p30_rejectionOfSnapshotWithMultipleMetaRows() {
        val alarm = createValidNormalAlarm(id = 1L)
        val auth = createValidAuthority(alarm)
        val metaList = listOf(
            createValidMeta(id = 1),
            createValidMeta(id = 2)
        )

        val result = DirectBootNormalCandidatePlanner.planCandidates(
            alarms = listOf(alarm),
            authorities = listOf(auth),
            metaRows = metaList,
            now = baseNow
        )

        assertTrue(result is NormalCandidatePlanResult.InvalidCoverage)
        val invalidCoverage = result as NormalCandidatePlanResult.InvalidCoverage
        assertEquals(CoverageReasonCode.META_ID_INVALID, invalidCoverage.reasonCode)
    }

    @Test
    fun p31_rejectionOfSnapshotWithNonPositiveAlarmId() {
        val invalidAlarm = createValidNormalAlarm(id = 0L)
        val auth = createValidAuthority(invalidAlarm)
        val meta = createValidMeta()

        val result = DirectBootNormalCandidatePlanner.planCandidates(
            alarms = listOf(invalidAlarm),
            authorities = listOf(auth),
            meta = meta,
            now = baseNow
        )

        assertTrue(result is NormalCandidatePlanResult.InconsistentSnapshot)
    }

    @Test
    fun p32_safeTimeLabelGenerationForLocalAndFixedTimezonePolicies() {
        val localAlarm = createValidNormalAlarm(
            id = 1L,
            timezonePolicy = Alarm.TIMEZONE_POLICY_LOCAL
        )
        val fixedAlarm = createValidNormalAlarm(
            id = 2L,
            timezonePolicy = Alarm.TIMEZONE_POLICY_FIXED,
            fixedTimezoneId = "America/New_York"
        )
        val auth1 = createValidAuthority(localAlarm)
        val auth2 = createValidAuthority(fixedAlarm)
        val meta = createValidMeta()

        val result = DirectBootNormalCandidatePlanner.planCandidates(
            alarms = listOf(localAlarm, fixedAlarm),
            authorities = listOf(auth1, auth2),
            meta = meta,
            now = baseNow
        )

        assertTrue(result is NormalCandidatePlanResult.ValidWithCandidates)
        val candidates = (result as NormalCandidatePlanResult.ValidWithCandidates).candidates
        assertEquals(2, candidates.size)
        assertFalse(candidates[0].timeLabel.isBlank())
        assertFalse(candidates[1].timeLabel.isBlank())
    }
}
