package com.sysadmindoc.alarmclock.domain.evaluator

import com.sysadmindoc.alarmclock.data.local.entity.ScheduleAuthority
import com.sysadmindoc.alarmclock.data.local.entity.ScheduleException
import com.sysadmindoc.alarmclock.data.model.Alarm
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

class NormalAlarmFireSafetyPlannerTest {

    private val planner = NormalAlarmFireSafetyPlanner()
    private val zoneId = ZoneId.of("America/New_York")
    private val wed0700Ms = LocalDate.of(2026, 10, 28)
        .atTime(LocalTime.of(7, 0))
        .atZone(zoneId)
        .toInstant()
        .toEpochMilli()

    private val alarm = Alarm(
        id = 1L,
        hour = 7,
        minute = 0,
        isEnabled = true,
        scheduleKind = Alarm.SCHEDULE_KIND_REPEATING,
        repeatDays = setOf(DayOfWeek.WEDNESDAY),
        generation = 1,
        revision = 1
    )

    private val validAuthority = ScheduleAuthority(
        alarmId = 1L,
        instanceToken = "auth-token-1",
        definitionRevision = 1L,
        scheduleRevision = 1L,
        lifecycle = "LIVE",
        desiredOperation = "SET",
        desiredTriggerMillis = wed0700Ms,
        reconcileState = "SETTLED",
        restoreEpoch = 100L,
        actionKey = null
    )

    private val validProof = FireIssuanceProof(
        issueToken = "issue-token-1",
        alarmId = 1L,
        scheduledAt = wed0700Ms,
        fireId = "fire-1",
        alarmGeneration = 1,
        authorityFingerprint = ScheduleAuthorityFingerprint.fromAuthority(validAuthority)
    )

    @Test
    fun `8 正時刻・同Generationでも発行証拠のないFire`() {
        val decision = planner.planFireSafety(
            alarm = alarm,
            firedTargetEpochMs = wed0700Ms,
            firedBaseTriggerTimeEpochMs = wed0700Ms,
            firedGeneration = 1,
            exceptions = emptyList(),
            issuanceProof = null // Missing issuance proof
        )

        assertEquals("Missing issuance proof MUST fail-closed as UNVERIFIED_INTENT_DATA", FireSafetyOutcome.UNVERIFIED_INTENT_DATA, decision.outcome)
        assertFalse(decision.isFireAdmissionConfirmed)
        assertFalse(decision.isFireSuppressionRequired)
    }

    @Test
    fun `9 BASEとTARGETの対応不一致`() {
        val overrideTargetMs = wed0700Ms + 3600000L // 08:00
        val skipException = ScheduleException(
            alarmId = 1L,
            baseTriggerTime = wed0700Ms,
            exceptionType = ScheduleException.TYPE_SKIP,
            generation = 1
        )

        val decision = planner.planFireSafety(
            alarm = alarm,
            firedTargetEpochMs = overrideTargetMs,
            firedBaseTriggerTimeEpochMs = wed0700Ms, // Matches on baseTriggerTime
            firedGeneration = 1,
            exceptions = listOf(skipException)
        )

        assertEquals("Skip exception matching baseTriggerTime MUST suppress fire", FireSafetyOutcome.SUPPRESS_CONFIRMED_SKIP, decision.outcome)
        assertTrue(decision.isFireSuppressionRequired)
        assertFalse(decision.isFireAdmissionConfirmed)
    }

    @Test
    fun `10 Authority token／restoreEpoch不一致のFire`() {
        val mismatchedProof = validProof.copy(alarmGeneration = 2) // Mismatched generation proof

        val decision = planner.planFireSafety(
            alarm = alarm,
            firedTargetEpochMs = wed0700Ms,
            firedBaseTriggerTimeEpochMs = wed0700Ms,
            firedGeneration = 1,
            exceptions = emptyList(),
            issuanceProof = mismatchedProof
        )

        assertEquals("Mismatched issuance proof generation MUST fail-closed as UNVERIFIED_INTENT_DATA", FireSafetyOutcome.UNVERIFIED_INTENT_DATA, decision.outcome)
        assertFalse(decision.isFireAdmissionConfirmed)
    }

    @Test
    fun `11 Disabled／Quick／HIDE-only Fire`() {
        val disabledAlarm = alarm.copy(isEnabled = false)
        val decisionDisabled = planner.planFireSafety(disabledAlarm, wed0700Ms, wed0700Ms, 1, emptyList(), validProof)
        assertEquals(FireSafetyOutcome.UNVERIFIED_INTENT_DATA, decisionDisabled.outcome)
        assertFalse(decisionDisabled.isFireAdmissionConfirmed)

        val quickAlarm = alarm.copy(kind = Alarm.KIND_QUICK)
        val decisionQuick = planner.planFireSafety(quickAlarm, wed0700Ms, wed0700Ms, 1, emptyList(), validProof)
        assertEquals(FireSafetyOutcome.UNVERIFIED_INTENT_DATA, decisionQuick.outcome)
        assertFalse(decisionQuick.isFireAdmissionConfirmed)

        val solarAlarm = alarm.copy(solarOffsetMinutes = 15)
        val decisionHide = planner.planFireSafety(solarAlarm, wed0700Ms, wed0700Ms, 1, emptyList(), validProof)
        assertEquals(FireSafetyOutcome.UNVERIFIED_INTENT_DATA, decisionHide.outcome)
        assertFalse(decisionHide.isFireAdmissionConfirmed)
    }

    @Test
    fun `12 発行証拠付きの正当なFire`() {
        val decision = planner.planFireSafety(
            alarm = alarm,
            firedTargetEpochMs = wed0700Ms,
            firedBaseTriggerTimeEpochMs = wed0700Ms,
            firedGeneration = 1,
            exceptions = emptyList(),
            issuanceProof = validProof
        )

        assertEquals(FireSafetyOutcome.ALLOW_FIRE_VERIFIED, decision.outcome)
        assertTrue(decision.isFireAdmissionConfirmed)
        assertFalse(decision.isFireSuppressionRequired)
    }

    @Test
    fun `13 確認済みSKIPの発火抑止`() {
        val skipException = ScheduleException(
            alarmId = 1L,
            baseTriggerTime = wed0700Ms,
            exceptionType = ScheduleException.TYPE_SKIP,
            generation = 1
        )

        val decision = planner.planFireSafety(
            alarm = alarm,
            firedTargetEpochMs = wed0700Ms,
            firedBaseTriggerTimeEpochMs = wed0700Ms,
            firedGeneration = 1,
            exceptions = listOf(skipException),
            issuanceProof = validProof
        )

        assertEquals(FireSafetyOutcome.SUPPRESS_CONFIRMED_SKIP, decision.outcome)
        assertTrue(decision.isFireSuppressionRequired)
        assertFalse(decision.isFireAdmissionConfirmed)
    }

    @Test
    fun `14 古いGenerationのFire`() {
        val alarmGen2 = alarm.copy(generation = 2)

        val decision = planner.planFireSafety(
            alarm = alarmGen2,
            firedTargetEpochMs = wed0700Ms,
            firedBaseTriggerTimeEpochMs = wed0700Ms,
            firedGeneration = 1, // Stale fired generation
            exceptions = emptyList()
        )

        assertEquals(FireSafetyOutcome.REJECT_STALE_GENERATION, decision.outcome)
        assertTrue(decision.isFireSuppressionRequired)
        assertFalse(decision.isFireAdmissionConfirmed)
    }
}
