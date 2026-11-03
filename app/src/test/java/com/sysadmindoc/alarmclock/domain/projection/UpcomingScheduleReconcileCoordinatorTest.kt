package com.sysadmindoc.alarmclock.domain.projection

import com.sysadmindoc.alarmclock.data.local.AlarmDao
import com.sysadmindoc.alarmclock.data.local.ScheduleAuthorityDao
import com.sysadmindoc.alarmclock.data.local.ScheduleExceptionDao
import com.sysadmindoc.alarmclock.data.local.entity.ScheduleAuthority
import com.sysadmindoc.alarmclock.data.local.entity.ScheduleException
import com.sysadmindoc.alarmclock.data.model.Alarm
import com.sysadmindoc.alarmclock.domain.evaluator.HolidaySnapshot
import com.sysadmindoc.alarmclock.domain.evaluator.HolidayState
import com.sysadmindoc.alarmclock.domain.evaluator.LocalHolidayClassifier
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/**
 * Verification test matrix for UpcomingScheduleReconcileCoordinator (KNO-156 / KNO-34 Slice B).
 * Includes both baseline scenario tests (01-14) and deterministic concurrency tests (C01-C12).
 */
class UpcomingScheduleReconcileCoordinatorTest {

    private val projector = UpcomingScheduleProjector()
    private val zoneId = ZoneId.of("America/New_York")

    private val alarmDao = mockk<AlarmDao>(relaxed = true)
    private val scheduleExceptionDao = mockk<ScheduleExceptionDao>(relaxed = true)
    private val scheduleAuthorityDao = mockk<ScheduleAuthorityDao>(relaxed = true)

    private lateinit var coordinator: UpcomingScheduleReconcileCoordinator

    private val alarmsList = mutableListOf<Alarm>()
    private val exceptionsList = mutableListOf<ScheduleException>()
    private val authoritiesList = mutableListOf<ScheduleAuthority>()

    // Base test timestamp: Wednesday 2026-11-04 06:00:00 local time
    private val nowEpochMs = LocalDate.of(2026, 11, 4)
        .atTime(LocalTime.of(6, 0))
        .atZone(zoneId)
        .toInstant()
        .toEpochMilli()

    private fun localToEpoch(date: LocalDate, time: LocalTime, zone: ZoneId = zoneId): Long {
        return date.atTime(time).atZone(zone).toInstant().toEpochMilli()
    }

    @Before
    fun setup() {
        alarmsList.clear()
        exceptionsList.clear()
        authoritiesList.clear()

        coEvery { alarmDao.getAllNormalAlarms() } answers { alarmsList.toList() }
        every { alarmDao.observeAll() } answers { flowOf(alarmsList.toList()) }
        coEvery { scheduleExceptionDao.getAll() } answers { exceptionsList.toList() }
        every { scheduleExceptionDao.observeAll() } answers { flowOf(exceptionsList.toList()) }
        coEvery { scheduleAuthorityDao.getAllAuthorities() } answers { authoritiesList.toList() }

        coordinator = UpcomingScheduleReconcileCoordinator(
            projector = projector,
            alarmDao = alarmDao,
            scheduleExceptionDao = scheduleExceptionDao,
            scheduleAuthorityDao = scheduleAuthorityDao
        )
    }

    // =========================================================================
    // Scenario Tests (01 - 14)
    // =========================================================================

    @Test
    fun test01_alarmEditSave_invalidatesAndConverges() = runBlocking {
        val alarm = Alarm(
            id = 1L,
            hour = 7,
            minute = 0,
            isEnabled = true,
            label = "Alarm 1",
            scheduleKind = Alarm.SCHEDULE_KIND_REPEATING,
            repeatDays = setOf(DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY)
        )
        alarmsList.add(alarm)

        coordinator.invalidateAlarm(1L, InvalidationReason.ALARM_DEFINITION_CHANGED)
        assertTrue(coordinator.isAlarmReconciling(1L))

        val inFlightProj = coordinator.getCurrentProjection(nowEpochMs, evaluationZoneId = zoneId)
        assertEquals(UpcomingProjectionFreshness.RECONCILING, inFlightProj.projectionFreshness)

        alarmsList[0] = alarm.copy(hour = 8, minute = 30, revision = 2)
        val reconciled = coordinator.reconcile(1L, nowEpochMs, evaluationZoneId = zoneId)

        assertFalse(coordinator.isAlarmReconciling(1L))
        assertEquals(UpcomingProjectionFreshness.CURRENT, reconciled.projectionFreshness)
        assertEquals(LocalTime.of(8, 30), reconciled.nextNormalOccurrence?.localDisplayTime)
    }

    @Test
    fun test02_enableDisable_convergesToNewProjection() = runBlocking {
        val alarm = Alarm(
            id = 1L,
            hour = 7,
            minute = 0,
            isEnabled = true,
            label = "Alarm 1",
            scheduleKind = Alarm.SCHEDULE_KIND_REPEATING,
            repeatDays = setOf(DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY)
        )
        alarmsList.add(alarm)

        coordinator.invalidateAlarm(1L, InvalidationReason.ALARM_TOGGLED)
        alarmsList[0] = alarm.copy(isEnabled = false)
        val reconciled = coordinator.reconcile(1L, nowEpochMs, evaluationZoneId = zoneId)

        assertFalse(coordinator.isAlarmReconciling(1L))
        assertEquals(listOf(1L), reconciled.disabledAlarms)
        assertTrue(reconciled.allOccurrences.isEmpty())
        assertNull(reconciled.nextNormalOccurrence)
    }

    @Test
    fun test03_holidayTruthRefresh_reconcilesAffectedOccurrences() = runBlocking {
        val alarm = Alarm(
            id = 1L,
            hour = 7,
            minute = 0,
            isEnabled = true,
            label = "Alarm 1",
            scheduleKind = Alarm.SCHEDULE_KIND_REPEATING,
            repeatDays = setOf(DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY),
            holidayPolicy = Alarm.HOLIDAY_POLICY_EXCLUDE
        )
        alarmsList.add(alarm)

        val initialSnapshot: (LocalDate) -> HolidaySnapshot = { HolidaySnapshot(HolidayState.NOT_HOLIDAY) }
        val initialProj = coordinator.reconcile(1L, nowEpochMs, evaluationZoneId = zoneId, holidaySnapshotProvider = initialSnapshot)
        assertEquals(UpcomingOccurrenceReason.BASE_SCHEDULE, initialProj.nextNormalOccurrence?.reason)
        assertTrue(initialProj.nextNormalOccurrence?.willFire == true)

        coordinator.invalidateAll(InvalidationReason.HOLIDAY_TRUTH_CHANGED)
        assertTrue(coordinator.isGlobalReconciling())

        val holidaySnapshot: (LocalDate) -> HolidaySnapshot = { date ->
            if (date == LocalDate.of(2026, 11, 4)) HolidaySnapshot(HolidayState.IS_HOLIDAY)
            else HolidaySnapshot(HolidayState.NOT_HOLIDAY)
        }
        val updatedProj = coordinator.reconcileAll(nowEpochMs, evaluationZoneId = zoneId, holidaySnapshotProvider = holidaySnapshot)

        assertFalse(coordinator.isGlobalReconciling())
        val wednesdayOcc = updatedProj.allOccurrences.first { it.localDate == LocalDate.of(2026, 11, 4) }
        assertEquals(UpcomingOccurrenceReason.SKIPPED_BY_HOLIDAY, wednesdayOcc.reason)
        assertFalse(wednesdayOcc.willFire)
        assertEquals(LocalDate.of(2026, 11, 5), updatedProj.nextNormalOccurrence?.localDate)
    }

    @Test
    fun test04_skipOnceAndChangeOnce_invalidatesAndProjectsAccurately() = runBlocking {
        val alarm = Alarm(
            id = 1L,
            hour = 7,
            minute = 0,
            isEnabled = true,
            label = "Alarm 1",
            scheduleKind = Alarm.SCHEDULE_KIND_REPEATING,
            repeatDays = setOf(DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY)
        )
        alarmsList.add(alarm)
        val wednesdayBaseMs = localToEpoch(LocalDate.of(2026, 11, 4), LocalTime.of(7, 0))

        exceptionsList.add(ScheduleException(alarmId = 1L, baseTriggerTime = wednesdayBaseMs, exceptionType = ScheduleException.TYPE_SKIP))
        coordinator.invalidateAlarm(1L, InvalidationReason.OVERRIDE_CHANGED)
        val skipProj = coordinator.reconcile(1L, nowEpochMs, evaluationZoneId = zoneId)

        val skipOcc = skipProj.allOccurrences.first { it.localDate == LocalDate.of(2026, 11, 4) }
        assertEquals(UpcomingOccurrenceReason.SKIP_ONCE, skipOcc.reason)
        assertFalse(skipOcc.willFire)

        val overrideMs = localToEpoch(LocalDate.of(2026, 11, 4), LocalTime.of(9, 15))
        exceptionsList.clear()
        exceptionsList.add(ScheduleException(alarmId = 1L, baseTriggerTime = wednesdayBaseMs, overrideTriggerTime = overrideMs, exceptionType = ScheduleException.TYPE_TIME_OVERRIDE))
        coordinator.invalidateAlarm(1L, InvalidationReason.OVERRIDE_CHANGED)
        val changeProj = coordinator.reconcile(1L, nowEpochMs, evaluationZoneId = zoneId)

        val changeOcc = changeProj.allOccurrences.first { it.localDate == LocalDate.of(2026, 11, 4) }
        assertEquals(UpcomingOccurrenceReason.CHANGE_TIME_ONCE, changeOcc.reason)
        assertTrue(changeOcc.willFire)
        assertEquals(LocalTime.of(9, 15), changeOcc.localDisplayTime)
    }

    @Test
    fun test05_exceptionClear_revertsTruthfullyToBaseSchedule() = runBlocking {
        val alarm = Alarm(
            id = 1L,
            hour = 7,
            minute = 0,
            isEnabled = true,
            label = "Alarm 1",
            scheduleKind = Alarm.SCHEDULE_KIND_REPEATING,
            repeatDays = setOf(DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY)
        )
        alarmsList.add(alarm)
        val wednesdayBaseMs = localToEpoch(LocalDate.of(2026, 11, 4), LocalTime.of(7, 0))
        val overrideMs = localToEpoch(LocalDate.of(2026, 11, 4), LocalTime.of(9, 15))
        exceptionsList.add(ScheduleException(alarmId = 1L, baseTriggerTime = wednesdayBaseMs, overrideTriggerTime = overrideMs, exceptionType = ScheduleException.TYPE_TIME_OVERRIDE))

        exceptionsList.clear()
        coordinator.invalidateAlarm(1L, InvalidationReason.OVERRIDE_CHANGED)
        val reconciled = coordinator.reconcile(1L, nowEpochMs, evaluationZoneId = zoneId)

        val occ = reconciled.allOccurrences.first { it.localDate == LocalDate.of(2026, 11, 4) }
        assertEquals(UpcomingOccurrenceReason.BASE_SCHEDULE, occ.reason)
        assertTrue(occ.willFire)
        assertEquals(LocalTime.of(7, 0), occ.localDisplayTime)
    }

    @Test
    fun test06_timeAndZoneRecovery_reprojectsAccurately() = runBlocking {
        val alarm = Alarm(
            id = 1L,
            hour = 7,
            minute = 0,
            isEnabled = true,
            label = "Alarm 1",
            scheduleKind = Alarm.SCHEDULE_KIND_REPEATING,
            repeatDays = setOf(DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY)
        )
        alarmsList.add(alarm)

        coordinator.invalidateAll(InvalidationReason.SYSTEM_RECOVERY)
        assertTrue(coordinator.isGlobalReconciling())

        val utcZone = ZoneId.of("UTC")
        val utcNowEpochMs = LocalDate.of(2026, 11, 4)
            .atTime(LocalTime.of(6, 0))
            .atZone(utcZone)
            .toInstant()
            .toEpochMilli()

        val reconciled = coordinator.reconcileAll(utcNowEpochMs, evaluationZoneId = utcZone)
        assertFalse(coordinator.isGlobalReconciling())
        assertEquals(utcZone, reconciled.evaluationZoneId)
        assertEquals(UpcomingProjectionFreshness.CURRENT, reconciled.projectionFreshness)
        assertEquals(LocalTime.of(7, 0), reconciled.nextNormalOccurrence?.localDisplayTime)
    }

    @Test
    fun test07_processAndBootRecovery_reconstructsTruthFromPersistedState() = runBlocking {
        val alarm = Alarm(
            id = 1L,
            hour = 7,
            minute = 0,
            isEnabled = true,
            label = "Alarm 1",
            scheduleKind = Alarm.SCHEDULE_KIND_REPEATING,
            repeatDays = setOf(DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY)
        )
        alarmsList.add(alarm)
        authoritiesList.add(ScheduleAuthority(alarmId = 1L, instanceToken = "token", definitionRevision = 1L, scheduleRevision = 1L, lifecycle = "NORMAL", desiredOperation = "SCHEDULE", desiredTriggerMillis = 0L, reconcileState = "RECONCILING", restoreEpoch = 0L, actionKey = null))

        val newCoordinator = UpcomingScheduleReconcileCoordinator(projector, alarmDao, scheduleExceptionDao, scheduleAuthorityDao)
        val restoredProj = newCoordinator.getCurrentProjection(nowEpochMs, evaluationZoneId = zoneId)
        assertEquals(UpcomingProjectionFreshness.RECONCILING, restoredProj.projectionFreshness)

        authoritiesList[0] = authoritiesList[0].copy(reconcileState = "SETTLED")
        val settledProj = newCoordinator.getCurrentProjection(nowEpochMs, evaluationZoneId = zoneId)
        assertEquals(UpcomingProjectionFreshness.CURRENT, settledProj.projectionFreshness)
    }

    @Test
    fun test08_duplicateEvent_isIdempotent() = runBlocking {
        val alarm = Alarm(
            id = 1L,
            hour = 7,
            minute = 0,
            isEnabled = true,
            label = "Alarm 1",
            scheduleKind = Alarm.SCHEDULE_KIND_REPEATING,
            repeatDays = setOf(DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY)
        )
        alarmsList.add(alarm)

        repeat(5) {
            coordinator.invalidateAlarm(1L, InvalidationReason.ALARM_DEFINITION_CHANGED)
        }
        assertEquals(setOf(1L), coordinator.reconcilingAlarmIds.value)

        val proj1 = coordinator.reconcile(1L, nowEpochMs, evaluationZoneId = zoneId)
        val proj2 = coordinator.reconcile(1L, nowEpochMs, evaluationZoneId = zoneId)

        assertEquals(proj1.allOccurrences.size, proj2.allOccurrences.size)
        assertEquals(proj1.projectionFreshness, proj2.projectionFreshness)
        assertFalse(coordinator.isAlarmReconciling(1L))
    }

    @Test
    fun test09_failureAndRetryConvergence_preservesInvalidationIntent() = runBlocking {
        val alarm = Alarm(
            id = 1L,
            hour = 7,
            minute = 0,
            isEnabled = true,
            label = "Alarm 1",
            scheduleKind = Alarm.SCHEDULE_KIND_REPEATING,
            repeatDays = setOf(DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY)
        )
        alarmsList.add(alarm)

        coordinator.invalidateAlarm(1L, InvalidationReason.ALARM_DEFINITION_CHANGED)
        assertTrue(coordinator.isAlarmReconciling(1L))

        coEvery { alarmDao.getAllNormalAlarms() } throws RuntimeException("Transient DB Lock")
        try {
            coordinator.reconcile(1L, nowEpochMs, evaluationZoneId = zoneId)
        } catch (_: Exception) {}

        assertTrue(coordinator.isAlarmReconciling(1L))

        coEvery { alarmDao.getAllNormalAlarms() } answers { alarmsList.toList() }
        val retryProj = coordinator.reconcile(1L, nowEpochMs, evaluationZoneId = zoneId)

        assertFalse(coordinator.isAlarmReconciling(1L))
        assertEquals(UpcomingProjectionFreshness.CURRENT, retryProj.projectionFreshness)
    }

    @Test
    fun test10_staleNextTriggerSuppression_marksReconcilingBeforeCommit() = runBlocking {
        val alarm = Alarm(
            id = 1L,
            hour = 7,
            minute = 0,
            isEnabled = true,
            label = "Alarm 1",
            scheduleKind = Alarm.SCHEDULE_KIND_REPEATING,
            repeatDays = setOf(DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY),
            nextTriggerTime = 123456789L
        )
        alarmsList.add(alarm)

        coordinator.invalidateAlarm(1L, InvalidationReason.ALARM_DEFINITION_CHANGED)
        val inFlightProj = coordinator.getCurrentProjection(nowEpochMs, evaluationZoneId = zoneId)

        assertEquals(UpcomingProjectionFreshness.RECONCILING, inFlightProj.projectionFreshness)
        assertEquals(UpcomingProjectionFreshness.RECONCILING, inFlightProj.nextNormalOccurrence?.projectionFreshness)
    }

    @Test
    fun test11_noCrossAlarmInvalidationLeakage_isolatesUnaffectedAlarms() = runBlocking {
        val alarm1 = Alarm(
            id = 1L,
            hour = 7,
            minute = 0,
            isEnabled = true,
            label = "Alarm 1",
            scheduleKind = Alarm.SCHEDULE_KIND_REPEATING,
            repeatDays = setOf(DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY)
        )
        val alarm2 = Alarm(
            id = 2L,
            hour = 8,
            minute = 0,
            isEnabled = true,
            label = "Alarm 2",
            scheduleKind = Alarm.SCHEDULE_KIND_REPEATING,
            repeatDays = setOf(DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY)
        )
        alarmsList.addAll(listOf(alarm1, alarm2))

        coordinator.invalidateAlarm(1L, InvalidationReason.ALARM_DEFINITION_CHANGED)
        val proj = coordinator.getCurrentProjection(nowEpochMs, evaluationZoneId = zoneId)

        val alarm1Occs = proj.allOccurrences.filter { it.alarmId == 1L }
        val alarm2Occs = proj.allOccurrences.filter { it.alarmId == 2L }

        assertTrue(alarm1Occs.all { it.projectionFreshness == UpcomingProjectionFreshness.RECONCILING })
        assertTrue(alarm2Occs.all { it.projectionFreshness == UpcomingProjectionFreshness.CURRENT })
    }

    @Test
    fun test12_noDuplicateOsSchedulingAuthority_preservesSingleWriterInvariant() = runBlocking {
        val alarm = Alarm(
            id = 1L,
            hour = 7,
            minute = 0,
            isEnabled = true,
            label = "Alarm 1",
            scheduleKind = Alarm.SCHEDULE_KIND_REPEATING,
            repeatDays = setOf(DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY)
        )
        alarmsList.add(alarm)

        coordinator.invalidateAlarm(1L, InvalidationReason.ALARM_DEFINITION_CHANGED)
        val reconciled = coordinator.reconcile(1L, nowEpochMs, evaluationZoneId = zoneId)

        assertNotNull(reconciled)
        assertEquals(1, reconciled.allOccurrences.filter { it.localDate == LocalDate.of(2026, 11, 4) }.size)
    }

    @Test
    fun test13_stableProjectionIdentity_preservesBaseOccurrenceIdAcrossExceptions() = runBlocking {
        val alarm = Alarm(
            id = 1L,
            hour = 7,
            minute = 0,
            isEnabled = true,
            label = "Alarm 1",
            scheduleKind = Alarm.SCHEDULE_KIND_REPEATING,
            repeatDays = setOf(DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY)
        )
        alarmsList.add(alarm)
        val wednesdayBaseMs = localToEpoch(LocalDate.of(2026, 11, 4), LocalTime.of(7, 0))

        val baseProj = coordinator.reconcile(1L, nowEpochMs, evaluationZoneId = zoneId)
        val baseOcc = baseProj.allOccurrences.first { it.localDate == LocalDate.of(2026, 11, 4) }

        val overrideMs = localToEpoch(LocalDate.of(2026, 11, 4), LocalTime.of(9, 30))
        exceptionsList.add(ScheduleException(alarmId = 1L, baseTriggerTime = wednesdayBaseMs, overrideTriggerTime = overrideMs, exceptionType = ScheduleException.TYPE_TIME_OVERRIDE))
        coordinator.invalidateAlarm(1L, InvalidationReason.OVERRIDE_CHANGED)
        val overrideProj = coordinator.reconcile(1L, nowEpochMs, evaluationZoneId = zoneId)
        val overrideOcc = overrideProj.allOccurrences.first { it.localDate == LocalDate.of(2026, 11, 4) }

        assertEquals(baseOcc.occurrenceId, overrideOcc.occurrenceId)
        assertEquals(baseOcc.baseTriggerTimeEpochMs, overrideOcc.baseTriggerTimeEpochMs)
        assertEquals(overrideMs, overrideOcc.targetTriggerTimeEpochMs)
    }

    @Test
    fun test14_normalVersusQuickSessionIsolation_excludesQuickAndActiveSession() = runBlocking {
        val normalAlarm1 = Alarm(
            id = 1L,
            hour = 7,
            minute = 0,
            isEnabled = true,
            label = "Normal 1",
            scheduleKind = Alarm.SCHEDULE_KIND_REPEATING,
            repeatDays = setOf(DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY)
        )
        val quickAlarm = Alarm(
            id = 2L,
            hour = 7,
            minute = 15,
            isEnabled = true,
            label = "Quick",
            kind = Alarm.KIND_QUICK
        )
        val normalAlarm3 = Alarm(
            id = 3L,
            hour = 8,
            minute = 0,
            isEnabled = true,
            label = "Normal 3",
            scheduleKind = Alarm.SCHEDULE_KIND_REPEATING,
            repeatDays = setOf(DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY)
        )
        alarmsList.addAll(listOf(normalAlarm1, quickAlarm, normalAlarm3))

        val proj = coordinator.getCurrentProjection(
            nowEpochMs = nowEpochMs,
            evaluationZoneId = zoneId,
            activeFiringAlarmIds = setOf(1L)
        )

        assertTrue(proj.allOccurrences.none { it.alarmId == 2L })
        assertEquals(3L, proj.nextNormalOccurrence?.alarmId)
        assertEquals(LocalTime.of(8, 0), proj.nextNormalOccurrence?.localDisplayTime)
    }

    // =========================================================================
    // Deterministic Concurrency Tests (C01 - C12)
    // =========================================================================

    @Test
    fun testC01_sameAlarmInvalidationDuringSuspendedReconcile() = runBlocking {
        val alarm = Alarm(
            id = 1L,
            hour = 7,
            minute = 0,
            isEnabled = true,
            label = "Alarm 1",
            scheduleKind = Alarm.SCHEDULE_KIND_REPEATING,
            repeatDays = setOf(DayOfWeek.WEDNESDAY)
        )
        alarmsList.add(alarm)

        coordinator.invalidateAlarm(1L, InvalidationReason.ALARM_DEFINITION_CHANGED)
        assertTrue(coordinator.isAlarmReconciling(1L))

        val gate = CompletableDeferred<Unit>()
        val reached = CompletableDeferred<Unit>()

        coEvery { alarmDao.getAllNormalAlarms() } coAnswers {
            reached.complete(Unit)
            gate.await()
            alarmsList.toList()
        }

        val reconcileJob = launch {
            coordinator.reconcile(1L, nowEpochMs, evaluationZoneId = zoneId)
        }

        reached.await()

        // While reconcile is suspended querying DAOs, a newer invalidation arrives
        coordinator.invalidateAlarm(1L, InvalidationReason.ALARM_DEFINITION_CHANGED)

        gate.complete(Unit)
        reconcileJob.join()

        // Since newer invalidation superseded the captured generation, Alarm 1 MUST remain reconciling
        assertTrue(coordinator.isAlarmReconciling(1L))
        assertEquals(setOf(1L), coordinator.reconcilingAlarmIds.value)
    }

    @Test
    fun testC02_otherAlarmInvalidationDuringSuspendedReconcile() = runBlocking {
        val alarm1 = Alarm(id = 1L, hour = 7, minute = 0, isEnabled = true, label = "A1", scheduleKind = Alarm.SCHEDULE_KIND_REPEATING, repeatDays = setOf(DayOfWeek.WEDNESDAY))
        val alarm2 = Alarm(id = 2L, hour = 8, minute = 0, isEnabled = true, label = "A2", scheduleKind = Alarm.SCHEDULE_KIND_REPEATING, repeatDays = setOf(DayOfWeek.WEDNESDAY))
        alarmsList.addAll(listOf(alarm1, alarm2))

        coordinator.invalidateAlarm(1L, InvalidationReason.ALARM_DEFINITION_CHANGED)
        assertTrue(coordinator.isAlarmReconciling(1L))

        val gate = CompletableDeferred<Unit>()
        val reached = CompletableDeferred<Unit>()

        coEvery { alarmDao.getAllNormalAlarms() } coAnswers {
            reached.complete(Unit)
            gate.await()
            alarmsList.toList()
        }

        val reconcileJob = launch {
            coordinator.reconcile(1L, nowEpochMs, evaluationZoneId = zoneId)
        }

        reached.await()

        // While Alarm 1 reconcile is suspended, Alarm 2 is invalidated
        coordinator.invalidateAlarm(2L, InvalidationReason.ALARM_TOGGLED)

        gate.complete(Unit)
        reconcileJob.join()

        // Alarm 1 completion MUST NOT erase Alarm 2 invalidation
        assertFalse(coordinator.isAlarmReconciling(1L))
        assertTrue(coordinator.isAlarmReconciling(2L))
        assertEquals(setOf(2L), coordinator.reconcilingAlarmIds.value)
    }

    @Test
    fun testC03_globalInvalidationDuringPerAlarmReconcile() = runBlocking {
        val alarm = Alarm(id = 1L, hour = 7, minute = 0, isEnabled = true, label = "A1", scheduleKind = Alarm.SCHEDULE_KIND_REPEATING, repeatDays = setOf(DayOfWeek.WEDNESDAY))
        alarmsList.add(alarm)

        coordinator.invalidateAlarm(1L, InvalidationReason.ALARM_DEFINITION_CHANGED)

        val gate = CompletableDeferred<Unit>()
        val reached = CompletableDeferred<Unit>()

        coEvery { alarmDao.getAllNormalAlarms() } coAnswers {
            reached.complete(Unit)
            gate.await()
            alarmsList.toList()
        }

        val reconcileJob = launch {
            coordinator.reconcile(1L, nowEpochMs, evaluationZoneId = zoneId)
        }

        reached.await()

        // Global invalidation arrives while per-alarm reconcile is suspended
        coordinator.invalidateAll(InvalidationReason.HOLIDAY_TRUTH_CHANGED)

        gate.complete(Unit)
        reconcileJob.join()

        // Per-alarm reconcile completion MUST NOT acknowledge/clear global invalidation
        assertTrue(coordinator.isGlobalReconciling())
        assertTrue(coordinator.isAlarmReconciling(1L))
    }

    @Test
    fun testC04_perAlarmInvalidationDuringGlobalReconcile() = runBlocking {
        val alarm1 = Alarm(id = 1L, hour = 7, minute = 0, isEnabled = true, label = "A1", scheduleKind = Alarm.SCHEDULE_KIND_REPEATING, repeatDays = setOf(DayOfWeek.WEDNESDAY))
        val alarm2 = Alarm(id = 2L, hour = 8, minute = 0, isEnabled = true, label = "A2", scheduleKind = Alarm.SCHEDULE_KIND_REPEATING, repeatDays = setOf(DayOfWeek.WEDNESDAY))
        alarmsList.addAll(listOf(alarm1, alarm2))

        coordinator.invalidateAll(InvalidationReason.HOLIDAY_TRUTH_CHANGED)
        coordinator.invalidateAlarm(1L, InvalidationReason.ALARM_DEFINITION_CHANGED)

        val gate = CompletableDeferred<Unit>()
        val reached = CompletableDeferred<Unit>()

        coEvery { alarmDao.getAllNormalAlarms() } coAnswers {
            reached.complete(Unit)
            gate.await()
            alarmsList.toList()
        }

        val globalJob = launch {
            coordinator.reconcileAll(nowEpochMs, evaluationZoneId = zoneId)
        }

        reached.await()

        // While global reconcile is suspended, Alarm 2 is invalidated
        coordinator.invalidateAlarm(2L, InvalidationReason.ALARM_TOGGLED)

        gate.complete(Unit)
        globalJob.join()

        // Global reconcile clears global + pre-existing Alarm 1, but Alarm 2 MUST NOT be erased
        assertFalse(coordinator.isGlobalReconciling())
        assertFalse(coordinator.isAlarmReconciling(1L))
        assertTrue(coordinator.isAlarmReconciling(2L))
        assertEquals(setOf(2L), coordinator.reconcilingAlarmIds.value)
    }

    @Test
    fun testC05_overlappingReconcileCalls() = runBlocking {
        val alarm = Alarm(id = 1L, hour = 7, minute = 0, isEnabled = true, label = "A1", scheduleKind = Alarm.SCHEDULE_KIND_REPEATING, repeatDays = setOf(DayOfWeek.WEDNESDAY))
        alarmsList.add(alarm)

        coordinator.invalidateAlarm(1L, InvalidationReason.ALARM_DEFINITION_CHANGED)

        val gate1 = CompletableDeferred<Unit>()
        val reached1 = CompletableDeferred<Unit>()
        val gate2 = CompletableDeferred<Unit>()
        val reached2 = CompletableDeferred<Unit>()

        var callCount = 0
        coEvery { alarmDao.getAllNormalAlarms() } coAnswers {
            callCount++
            if (callCount == 1) {
                reached1.complete(Unit)
                gate1.await()
            } else {
                reached2.complete(Unit)
                gate2.await()
            }
            alarmsList.toList()
        }

        val job1 = launch { coordinator.reconcile(1L, nowEpochMs, evaluationZoneId = zoneId) }
        reached1.await()

        // Advance generation while job1 is in-flight
        coordinator.invalidateAlarm(1L, InvalidationReason.ALARM_DEFINITION_CHANGED)

        val job2 = launch { coordinator.reconcile(1L, nowEpochMs, evaluationZoneId = zoneId) }
        reached2.await()

        // Complete job 1 (older generation)
        gate1.complete(Unit)
        job1.join()

        // Older job 1 cannot clear newer generation
        assertTrue(coordinator.isAlarmReconciling(1L))

        // Complete job 2 (current generation)
        gate2.complete(Unit)
        job2.join()

        // Now cleared
        assertFalse(coordinator.isAlarmReconciling(1L))
    }

    @Test
    fun testC06_lateCompletionCannotClearNewerInvalidation() = runBlocking {
        val alarm = Alarm(id = 1L, hour = 7, minute = 0, isEnabled = true, label = "A1", scheduleKind = Alarm.SCHEDULE_KIND_REPEATING, repeatDays = setOf(DayOfWeek.WEDNESDAY))
        alarmsList.add(alarm)

        coordinator.invalidateAlarm(1L, InvalidationReason.ALARM_DEFINITION_CHANGED)

        val gate = CompletableDeferred<Unit>()
        val reached = CompletableDeferred<Unit>()

        coEvery { alarmDao.getAllNormalAlarms() } coAnswers {
            reached.complete(Unit)
            gate.await()
            alarmsList.toList()
        }

        val job = launch { coordinator.reconcile(1L, nowEpochMs, evaluationZoneId = zoneId) }
        reached.await()

        coordinator.invalidateAlarm(1L, InvalidationReason.OVERRIDE_CHANGED)
        assertEquals(InvalidationReason.OVERRIDE_CHANGED, coordinator.lastInvalidationReasons.value[1L])

        gate.complete(Unit)
        job.join()

        assertTrue(coordinator.isAlarmReconciling(1L))
        assertEquals(InvalidationReason.OVERRIDE_CHANGED, coordinator.lastInvalidationReasons.value[1L])
    }

    @Test
    fun testC07_daoExceptionRetainsPendingInvalidation() = runBlocking {
        val alarm = Alarm(id = 1L, hour = 7, minute = 0, isEnabled = true, label = "A1", scheduleKind = Alarm.SCHEDULE_KIND_REPEATING, repeatDays = setOf(DayOfWeek.WEDNESDAY))
        alarmsList.add(alarm)

        coordinator.invalidateAlarm(1L, InvalidationReason.ALARM_DEFINITION_CHANGED)
        assertTrue(coordinator.isAlarmReconciling(1L))

        coEvery { alarmDao.getAllNormalAlarms() } throws RuntimeException("Fatal DB Error")

        var caught = false
        try {
            coordinator.reconcile(1L, nowEpochMs, evaluationZoneId = zoneId)
        } catch (_: RuntimeException) {
            caught = true
        }

        assertTrue(caught)
        assertTrue(coordinator.isAlarmReconciling(1L))
        assertEquals(setOf(1L), coordinator.reconcilingAlarmIds.value)
    }

    @Test
    fun testC08_cancellationRetainsPendingInvalidation() = runBlocking {
        val alarm = Alarm(id = 1L, hour = 7, minute = 0, isEnabled = true, label = "A1", scheduleKind = Alarm.SCHEDULE_KIND_REPEATING, repeatDays = setOf(DayOfWeek.WEDNESDAY))
        alarmsList.add(alarm)

        coordinator.invalidateAlarm(1L, InvalidationReason.ALARM_DEFINITION_CHANGED)
        assertTrue(coordinator.isAlarmReconciling(1L))

        val gate = CompletableDeferred<Unit>()
        val reached = CompletableDeferred<Unit>()

        coEvery { alarmDao.getAllNormalAlarms() } coAnswers {
            reached.complete(Unit)
            gate.await()
            alarmsList.toList()
        }

        val job = launch { coordinator.reconcile(1L, nowEpochMs, evaluationZoneId = zoneId) }
        reached.await()

        job.cancelAndJoin()

        assertTrue(coordinator.isAlarmReconciling(1L))
        assertEquals(setOf(1L), coordinator.reconcilingAlarmIds.value)
    }

    @Test
    fun testC09_observeProjection_emitsForInvalidateAll() = runBlocking {
        val alarm = Alarm(id = 1L, hour = 7, minute = 0, isEnabled = true, label = "A1", scheduleKind = Alarm.SCHEDULE_KIND_REPEATING, repeatDays = setOf(DayOfWeek.WEDNESDAY))
        alarmsList.add(alarm)

        val projections = mutableListOf<UpcomingScheduleProjection>()
        val job = launch {
            coordinator.observeProjection(
                nowEpochMsProvider = { nowEpochMs },
                evaluationZoneIdProvider = { zoneId }
            ).collect {
                projections.add(it)
            }
        }

        while (projections.isEmpty()) {
            yield()
        }
        assertEquals(1, projections.size)
        assertEquals(UpcomingProjectionFreshness.CURRENT, projections[0].projectionFreshness)

        coordinator.invalidateAll(InvalidationReason.HOLIDAY_TRUTH_CHANGED)

        while (projections.size < 2) {
            yield()
        }
        assertEquals(2, projections.size)
        assertEquals(UpcomingProjectionFreshness.RECONCILING, projections[1].projectionFreshness)

        job.cancelAndJoin()
    }

    @Test
    fun testC10_reconcileCompletion_emitsUpdatedProjection() = runBlocking {
        val alarm = Alarm(id = 1L, hour = 7, minute = 0, isEnabled = true, label = "A1", scheduleKind = Alarm.SCHEDULE_KIND_REPEATING, repeatDays = setOf(DayOfWeek.WEDNESDAY))
        alarmsList.add(alarm)

        coordinator.invalidateAlarm(1L, InvalidationReason.ALARM_DEFINITION_CHANGED)

        val projections = mutableListOf<UpcomingScheduleProjection>()
        val job = launch {
            coordinator.observeProjection(
                nowEpochMsProvider = { nowEpochMs },
                evaluationZoneIdProvider = { zoneId }
            ).collect {
                projections.add(it)
            }
        }

        while (projections.isEmpty()) {
            yield()
        }
        assertEquals(UpcomingProjectionFreshness.RECONCILING, projections[0].projectionFreshness)

        coordinator.reconcile(1L, nowEpochMs, evaluationZoneId = zoneId)

        while (projections.size < 2) {
            yield()
        }
        assertEquals(UpcomingProjectionFreshness.CURRENT, projections.last().projectionFreshness)

        job.cancelAndJoin()
    }

    @Test
    fun testC11_restartReconstructsFromPersistedScheduleAuthority() = runBlocking {
        val alarm = Alarm(id = 1L, hour = 7, minute = 0, isEnabled = true, label = "A1", scheduleKind = Alarm.SCHEDULE_KIND_REPEATING, repeatDays = setOf(DayOfWeek.WEDNESDAY))
        alarmsList.add(alarm)

        authoritiesList.add(ScheduleAuthority(alarmId = 1L, instanceToken = "tok", definitionRevision = 1L, scheduleRevision = 1L, lifecycle = "NORMAL", desiredOperation = "SCHEDULE", desiredTriggerMillis = 0L, reconcileState = "RECONCILING", restoreEpoch = 0L, actionKey = null))

        val restartCoordinator = UpcomingScheduleReconcileCoordinator(projector, alarmDao, scheduleExceptionDao, scheduleAuthorityDao)

        val proj = restartCoordinator.getCurrentProjection(nowEpochMs, evaluationZoneId = zoneId)
        assertEquals(UpcomingProjectionFreshness.RECONCILING, proj.projectionFreshness)

        authoritiesList[0] = authoritiesList[0].copy(reconcileState = "SETTLED")
        val settledProj = restartCoordinator.getCurrentProjection(nowEpochMs, evaluationZoneId = zoneId)
        assertEquals(UpcomingProjectionFreshness.CURRENT, settledProj.projectionFreshness)
    }

    @Test
    fun testC12_duplicateNotificationsConvergeDeterministically() = runBlocking {
        val alarm = Alarm(id = 1L, hour = 7, minute = 0, isEnabled = true, label = "A1", scheduleKind = Alarm.SCHEDULE_KIND_REPEATING, repeatDays = setOf(DayOfWeek.WEDNESDAY))
        alarmsList.add(alarm)

        repeat(10) {
            coordinator.invalidateAlarm(1L, InvalidationReason.ALARM_DEFINITION_CHANGED)
        }
        assertEquals(setOf(1L), coordinator.reconcilingAlarmIds.value)

        val proj = coordinator.reconcile(1L, nowEpochMs, evaluationZoneId = zoneId)
        assertFalse(coordinator.isAlarmReconciling(1L))
        assertEquals(UpcomingProjectionFreshness.CURRENT, proj.projectionFreshness)

        val proj2 = coordinator.reconcile(1L, nowEpochMs, evaluationZoneId = zoneId)
        assertFalse(coordinator.isAlarmReconciling(1L))
        assertEquals(UpcomingProjectionFreshness.CURRENT, proj2.projectionFreshness)
    }
}
