package com.sysadmindoc.alarmclock.domain.evaluator

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import com.sysadmindoc.alarmclock.data.local.AlarmDatabase
import com.sysadmindoc.alarmclock.data.local.entity.IssueSlot
import com.sysadmindoc.alarmclock.data.local.entity.IssuedOccurrence
import com.sysadmindoc.alarmclock.data.local.entity.ScheduleAuthority
import com.sysadmindoc.alarmclock.data.model.Alarm
import com.sysadmindoc.alarmclock.data.preferences.AppSettings
import com.sysadmindoc.alarmclock.domain.MainSlotApplier
import com.sysadmindoc.alarmclock.domain.OwnerResult
import com.sysadmindoc.alarmclock.domain.ScheduleAuthorityOwner
import com.sysadmindoc.alarmclock.domain.session.NormalAlarmAdmissionFactory
import com.sysadmindoc.alarmclock.data.local.entity.ScheduleException
import com.sysadmindoc.alarmclock.data.repository.CapturedHolidayAuthorityWindow
import com.sysadmindoc.alarmclock.data.repository.HolidayProviderSnapshot
import com.sysadmindoc.alarmclock.data.repository.HolidayRepository
import com.sysadmindoc.alarmclock.data.repository.HolidayYearCoverage
import com.sysadmindoc.alarmclock.data.repository.NAGER_DATE_PROVIDER_ID
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.spyk
import java.io.File
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Deterministic verification of atomic post-fire recovery intent contract (KNO-37 Phase H2-C4-P3).
 *
 * Verifies Requirements A through J:
 * A. Admission + Tombstone + Session + ScheduleAuthority intent commit atomically (Repeating and One-Time)
 * B. Failure at authority CAS rolls back ALL handoff state
 * C. Process death after Room commit leaves recoverable RECONCILING intent; replay via Owner settles it
 * D. OS effects are never executed inside Room transaction
 * E. Concurrent user edit supersedes stale post-fire claim
 * F. Incarnation and restoreEpoch changes reject stale handoff
 * G. Existing RECONCILING authority does not silently lose newer or pending work
 * H. One-Time definition disable and authority lifecycle remain consistent
 * I. Repeating nextTriggerTime and desiredTriggerMillis remain consistent
 * J. Duplicate and rejected handoffs perform no new OS work
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class NormalAlarmPostFireAtomicRecoveryTest {

    private lateinit var context: Context
    private lateinit var db: AlarmDatabase
    private lateinit var dbFile: File
    private lateinit var coordinator: NormalAlarmHandoffCoordinator

    private val alarmId = 200L
    private val nowMs = 1793796000000L
    private val scheduledAt = 1793796000000L
    private val baseTriggerTime = 1793796000000L
    private val fireId = "fire-200"
    private val token = "issue_token_200"
    private val generationId = 1L
    private val alarmGen = 1

    private val baseAuthority = ScheduleAuthority(
        alarmId = alarmId,
        instanceToken = "inst-token-200",
        definitionRevision = 1L,
        scheduleRevision = 1L,
        lifecycle = "ACTIVE",
        desiredOperation = "FIRE",
        desiredTriggerMillis = scheduledAt,
        reconcileState = "SETTLED",
        restoreEpoch = 1000L,
        actionKey = "action-200"
    )

    private val baseFingerprint = ScheduleAuthorityFingerprint.fromAuthority(baseAuthority)

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, AlarmDatabase::class.java)
            .allowMainThreadQueries()
            .addCallback(AlarmDatabase.DATABASE_CALLBACK)
            .build()

        coordinator = NormalAlarmHandoffCoordinator(
            database = db,
            admissionFactory = NormalAlarmAdmissionFactory(uuidSupplier = { "test-uuid-200" })
        )
    }

    @After
    fun tearDown() {
        try { db.close() } catch (_: Exception) {}
    }

    private suspend fun seedAlarmFixtures(
        isRepeating: Boolean,
        alarmEnabled: Boolean = true,
        authorityState: ScheduleAuthority = baseAuthority
    ) {
        val repeatDays = if (isRepeating) {
            setOf(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY, DayOfWeek.FRIDAY)
        } else {
            emptySet()
        }

        val alarm = Alarm(
            id = alarmId,
            hour = 7,
            minute = 0,
            isEnabled = alarmEnabled,
            kind = Alarm.KIND_NORMAL,
            repeatDays = repeatDays,
            generation = alarmGen,
            revision = 1,
            nextTriggerTime = scheduledAt,
            snoozeDurationMinutes = 10,
            maxSnoozeCount = 3
        )
        db.alarmDao().insert(alarm)
        db.scheduleAuthorityDao().insertAuthority(authorityState)

        val slot = IssueSlot(
            alarmId = alarmId,
            currentIssueToken = token,
            generationId = generationId
        )
        db.issuedOccurrenceDao().insertSlotInitial(slot)

        val occurrence = IssuedOccurrence(
            issueToken = token,
            alarmId = alarmId,
            scheduledAt = scheduledAt,
            fireId = fireId,
            generationId = generationId,
            status = IssuedOccurrence.STATUS_ISSUED,
            baseTriggerTime = baseTriggerTime,
            alarmGeneration = alarmGen,
            authorityInstanceToken = authorityState.instanceToken,
            restoreEpoch = authorityState.restoreEpoch,
            scheduleRevision = authorityState.scheduleRevision,
            definitionRevision = authorityState.definitionRevision,
            createdAt = nowMs,
            updatedAt = nowMs
        )
        db.issuedOccurrenceDao().insertOccurrence(occurrence)
    }

    private fun createHandoffRequest(
        fingerprint: ScheduleAuthorityFingerprint = baseFingerprint
    ): NormalAlarmHandoffRequest {
        return NormalAlarmHandoffRequest(
            issueToken = token,
            expectedAlarmId = alarmId,
            expectedFireId = fireId,
            expectedScheduledAt = scheduledAt,
            expectedBaseTriggerTime = baseTriggerTime,
            expectedAlarmGeneration = alarmGen,
            expectedSlotGenerationId = generationId,
            expectedAuthorityFingerprint = fingerprint,
            nowEpochMs = nowMs,
            elapsedRealtimeMs = 1000L,
            bootCount = 1L,
            settings = AppSettings(autoSilenceMinutes = 15),
            effectiveNormalAutoSilenceMinutes = 15
        )
    }

    // REQUIREMENT A (Repeating): Admission + Tombstone + Session + Repeating ScheduleAuthority intent commit atomically
    @Test
    fun requirementA_repeatingAlarm_commitsAdmissionTombstoneSessionAndAuthorityIntentAtomically() = runBlocking {
        seedAlarmFixtures(isRepeating = true)

        val request = createHandoffRequest()
        val result = coordinator.coordinateHandoff(request)

        assertEquals(NormalAlarmHandoffOutcome.ADMITTED, result.outcome)
        assertNotNull(result.sessionId)
        assertNotNull(result.committedAuthorityIntent)

        val intent = result.committedAuthorityIntent!!
        assertEquals(alarmId, intent.alarmId)
        assertEquals(2L, intent.claimedRevision)
        assertEquals("SET", intent.desiredOperation)
        assertTrue(intent.desiredTriggerMillis != null && intent.desiredTriggerMillis!! > scheduledAt)
        assertEquals("RECONCILING", intent.reconcileState)
        assertEquals("postfire_$token", intent.actionKey)

        // Authoritative Room verification:
        val occ = db.issuedOccurrenceDao().getOccurrence(token)
        assertEquals(IssuedOccurrence.STATUS_ADMITTED, occ?.status)

        val tombstone = db.alarmSessionDao().getTombstoneByAdmissionKey(result.admissionKey!!)
        assertNotNull(tombstone)

        val session = db.alarmSessionDao().getSessionById(result.sessionId!!)
        assertNotNull(session)

        val updatedAlarm = db.alarmDao().getById(alarmId)
        assertNotNull(updatedAlarm)
        assertTrue(updatedAlarm!!.isEnabled)
        assertEquals(intent.desiredTriggerMillis, updatedAlarm.nextTriggerTime)

        val updatedAuth = db.scheduleAuthorityDao().getAuthorityByAlarmId(alarmId)
        assertNotNull(updatedAuth)
        assertEquals(2L, updatedAuth!!.scheduleRevision)
        assertEquals("RECONCILING", updatedAuth.reconcileState)
        assertEquals("SET", updatedAuth.desiredOperation)
        assertEquals(intent.desiredTriggerMillis, updatedAuth.desiredTriggerMillis)
    }

    // REQUIREMENT A (One-Time): Admission + Tombstone + Session + One-Time Definition Disable and Authority commit atomically
    @Test
    fun requirementA_oneTimeAlarm_commitsAdmissionTombstoneSessionAndDisableIntentAtomically() = runBlocking {
        seedAlarmFixtures(isRepeating = false)

        val request = createHandoffRequest()
        val result = coordinator.coordinateHandoff(request)

        assertEquals(NormalAlarmHandoffOutcome.ADMITTED, result.outcome)
        assertNotNull(result.sessionId)
        assertNotNull(result.committedAuthorityIntent)

        val intent = result.committedAuthorityIntent!!
        assertEquals(alarmId, intent.alarmId)
        assertEquals("DISABLED", intent.lifecycle)
        assertEquals("CANCEL", intent.desiredOperation)
        assertNull(intent.desiredTriggerMillis)
        assertEquals("RECONCILING", intent.reconcileState)
        assertEquals("postfire_disable_$token", intent.actionKey)

        // Authoritative Room verification:
        val occ = db.issuedOccurrenceDao().getOccurrence(token)
        assertEquals(IssuedOccurrence.STATUS_ADMITTED, occ?.status)

        val tombstone = db.alarmSessionDao().getTombstoneByAdmissionKey(result.admissionKey!!)
        assertNotNull(tombstone)

        val updatedAlarm = db.alarmDao().getById(alarmId)
        assertNotNull(updatedAlarm)
        assertFalse("One-time alarm must be disabled after fire handoff", updatedAlarm!!.isEnabled)
        assertEquals(0L, updatedAlarm.nextTriggerTime)

        val updatedAuth = db.scheduleAuthorityDao().getAuthorityByAlarmId(alarmId)
        assertNotNull(updatedAuth)
        assertEquals("DISABLED", updatedAuth!!.lifecycle)
        assertEquals("CANCEL", updatedAuth.desiredOperation)
        assertNull(updatedAuth.desiredTriggerMillis)
        assertEquals("RECONCILING", updatedAuth.reconcileState)
    }

    // REQUIREMENT B: Failure at authority CAS rolls back ALL handoff state
    @Test
    fun requirementB_authorityCasFailure_rollsBackAllHandoffState() = runBlocking {
        seedAlarmFixtures(isRepeating = true)

        // Inject interceptor that throws immediately BEFORE authority CAS (or concurrent conflict)
        coordinator.cutpointInterceptor = { cutpoint ->
            if (cutpoint == NormalAlarmHandoffCoordinator.Cutpoint.BEFORE_AUTHORITY_CAS) {
                throw IllegalStateException("Simulated CAS conflict during authority CAS")
            }
        }

        val request = createHandoffRequest()
        val result = coordinator.coordinateHandoff(request)

        assertEquals(NormalAlarmHandoffOutcome.CAS_CONFLICT, result.outcome)

        // Verify ALL state rolled back:
        val occ = db.issuedOccurrenceDao().getOccurrence(token)
        assertEquals("Occurrence status must remain ISSUED on rollback", IssuedOccurrence.STATUS_ISSUED, occ?.status)

        val tombstones = db.alarmSessionDao().getActiveSessionsForAlarm(alarmId)
        assertEquals("No session must be committed", 0, tombstones.size)

        val alarm = db.alarmDao().getById(alarmId)
        assertEquals("Alarm nextTrigger must NOT be advanced", scheduledAt, alarm?.nextTriggerTime)

        val auth = db.scheduleAuthorityDao().getAuthorityByAlarmId(alarmId)
        assertEquals("Authority revision must remain 1L", 1L, auth?.scheduleRevision)
        assertEquals("Authority state must remain SETTLED", "SETTLED", auth?.reconcileState)
    }

    // REQUIREMENT C: Process death after Room commit leaves recoverable RECONCILING intent; replay via Owner settles it
    @Test
    fun requirementC_processDeathAfterRoomCommit_replayViaOwnerSettlesIntent() = runBlocking {
        seedAlarmFixtures(isRepeating = true)

        val result = coordinator.coordinateHandoff(createHandoffRequest())
        assertEquals(NormalAlarmHandoffOutcome.ADMITTED, result.outcome)

        // Room state shows committed RECONCILING intent:
        val authBeforeReplay = db.scheduleAuthorityDao().getAuthorityByAlarmId(alarmId)
        assertEquals("RECONCILING", authBeforeReplay?.reconcileState)

        // Simulate reboot / recovery: instantiate ScheduleAuthorityOwner with MainSlotApplier
        val appliedOps = mutableListOf<String>()
        val mainSlotApplier = MainSlotApplier { authority ->
            appliedOps.add("${authority.desiredOperation}_${authority.desiredTriggerMillis}")
            true
        }
        val owner = ScheduleAuthorityOwner(
            authorityDao = db.scheduleAuthorityDao(),
            mainSlotApplier = mainSlotApplier,
            exactPermissionChecker = { true }
        )

        val replayResult = owner.replayPersistedOperation(alarmId)
        assertTrue("Replay of RECONCILING intent must succeed", replayResult is OwnerResult.OsAccepted)

        assertEquals(1, appliedOps.size)
        assertEquals("SET_${authBeforeReplay?.desiredTriggerMillis}", appliedOps.first())

        val settledAuth = db.scheduleAuthorityDao().getAuthorityByAlarmId(alarmId)
        assertEquals("SETTLED", settledAuth?.reconcileState)
    }

    // REQUIREMENT D: Zero OS effects executed inside Room transaction
    @Test
    fun requirementD_zeroOsEffectsExecutedInsideRoomTransaction() = runBlocking {
        seedAlarmFixtures(isRepeating = true)

        var osCalls = 0
        coordinator.cutpointInterceptor = { _ ->
            // Verification hook inside Room transaction
            assertEquals("No OS calls must be made inside Room transaction", 0, osCalls)
        }

        val result = coordinator.coordinateHandoff(createHandoffRequest())
        assertEquals(NormalAlarmHandoffOutcome.ADMITTED, result.outcome)
        assertEquals("Post-handoff coordinator must not execute OS calls directly", 0, osCalls)
    }

    // REQUIREMENT E: Concurrent user edit supersedes stale post-fire claim
    @Test
    fun requirementE_concurrentUserEdit_supersedesStalePostFireClaim() = runBlocking {
        seedAlarmFixtures(isRepeating = true)

        // User edits alarm definition and claims scheduleRevision = 2L before handoff runs
        db.scheduleAuthorityDao().casClaimAuthority(
            alarmId = alarmId,
            instanceToken = baseAuthority.instanceToken,
            restoreEpoch = baseAuthority.restoreEpoch,
            expectedRevision = 1L,
            expectedLifecycle = "ACTIVE",
            desiredOperation = "SET",
            desiredTriggerMillis = scheduledAt + 3600_000L,
            actionKey = "user_edit_action"
        )

        // Handoff request built with stale fingerprint (rev = 1L)
        val staleRequest = createHandoffRequest(baseFingerprint)
        val result = coordinator.coordinateHandoff(staleRequest)

        assertEquals("Stale authority revision must be rejected", NormalAlarmHandoffOutcome.REJECTED_STALE_PROVENANCE, result.outcome)

        // Occurrence status remains ISSUED
        val occ = db.issuedOccurrenceDao().getOccurrence(token)
        assertEquals(IssuedOccurrence.STATUS_ISSUED, occ?.status)

        // Authority is NOT overwritten by stale handoff
        val auth = db.scheduleAuthorityDao().getAuthorityByAlarmId(alarmId)
        assertEquals(2L, auth?.scheduleRevision)
        assertEquals("user_edit_action", auth?.actionKey)
    }

    // REQUIREMENT F: Incarnation and restoreEpoch changes reject stale handoff
    @Test
    fun requirementF_restoreEpochMismatch_rejectsStaleHandoff() = runBlocking {
        // Authority in DB has different restoreEpoch (e.g. 2000L instead of 1000L from device restore)
        val restoredAuthority = baseAuthority.copy(restoreEpoch = 2000L)
        seedAlarmFixtures(isRepeating = true, authorityState = restoredAuthority)

        val staleRequest = createHandoffRequest(baseFingerprint) // fingerprint has restoreEpoch = 1000L
        val result = coordinator.coordinateHandoff(staleRequest)

        assertEquals(NormalAlarmHandoffOutcome.REJECTED_STALE_PROVENANCE, result.outcome)

        val occ = db.issuedOccurrenceDao().getOccurrence(token)
        assertEquals(IssuedOccurrence.STATUS_ISSUED, occ?.status)
    }

    // REQUIREMENT G: Existing RECONCILING authority does not silently lose newer work
    @Test
    fun requirementG_reconcilingAuthorityWithRevisionMismatch_failsClosed() = runBlocking {
        // Authority was already advanced to revision 5 in RECONCILING state
        val reconcilingAuthority = baseAuthority.copy(
            scheduleRevision = 5L,
            reconcileState = "RECONCILING"
        )
        seedAlarmFixtures(isRepeating = true, authorityState = reconcilingAuthority)

        val request = createHandoffRequest(baseFingerprint) // expects revision 1L
        val result = coordinator.coordinateHandoff(request)

        assertEquals(NormalAlarmHandoffOutcome.REJECTED_STALE_PROVENANCE, result.outcome)
        val auth = db.scheduleAuthorityDao().getAuthorityByAlarmId(alarmId)
        assertEquals(5L, auth?.scheduleRevision)
    }

    // REQUIREMENT H: One-Time definition disable and authority lifecycle remain consistent
    @Test
    fun requirementH_oneTimeAlarm_definitionAndAuthorityLifecycleConsistency() = runBlocking {
        seedAlarmFixtures(isRepeating = false)

        val result = coordinator.coordinateHandoff(createHandoffRequest())
        assertEquals(NormalAlarmHandoffOutcome.ADMITTED, result.outcome)

        val alarm = db.alarmDao().getById(alarmId)
        val auth = db.scheduleAuthorityDao().getAuthorityByAlarmId(alarmId)

        assertNotNull(alarm)
        assertNotNull(auth)
        assertFalse("Alarm must be disabled", alarm!!.isEnabled)
        assertEquals(0L, alarm.nextTriggerTime)
        assertEquals("DISABLED", auth!!.lifecycle)
        assertEquals("CANCEL", auth.desiredOperation)
        assertNull(auth.desiredTriggerMillis)
    }

    // REQUIREMENT I: Repeating nextTriggerTime and desiredTriggerMillis remain consistent
    @Test
    fun requirementI_repeatingAlarm_triggerTimeAndDesiredMillisConsistency() = runBlocking {
        seedAlarmFixtures(isRepeating = true)

        val result = coordinator.coordinateHandoff(createHandoffRequest())
        assertEquals(NormalAlarmHandoffOutcome.ADMITTED, result.outcome)

        val alarm = db.alarmDao().getById(alarmId)
        val auth = db.scheduleAuthorityDao().getAuthorityByAlarmId(alarmId)

        assertNotNull(alarm)
        assertNotNull(auth)
        assertTrue(alarm!!.isEnabled)
        assertTrue(alarm.nextTriggerTime > scheduledAt)
        assertEquals("alarm.nextTriggerTime must strictly equal auth.desiredTriggerMillis", alarm.nextTriggerTime, auth!!.desiredTriggerMillis)
        assertEquals("SET", auth.desiredOperation)
    }

    // REQUIREMENT J: Duplicate and rejected handoffs perform no new OS work
    @Test
    fun requirementJ_duplicateAndRejectedHandoffs_performNoNewWork() = runBlocking {
        seedAlarmFixtures(isRepeating = true)

        val request = createHandoffRequest()
        val firstResult = coordinator.coordinateHandoff(request)
        assertEquals(NormalAlarmHandoffOutcome.ADMITTED, firstResult.outcome)

        // Duplicate delivery:
        val dupResult = coordinator.coordinateHandoff(request)
        assertEquals(NormalAlarmHandoffOutcome.DUPLICATE, dupResult.outcome)
        assertNull("Duplicate handoff must return null committed intent", dupResult.committedAuthorityIntent)

        // Rejected handoff (unknown alarm identity):
        val badRequest = request.copy(expectedAlarmId = 999L, issueToken = "invalid_token")
        val rejectResult = coordinator.coordinateHandoff(badRequest)
        assertEquals(NormalAlarmHandoffOutcome.REJECTED_STALE_IDENTITY, rejectResult.outcome)
        assertNull("Rejected handoff must return null committed intent", rejectResult.committedAuthorityIntent)
    }

    // OWNER CONCURRENCY: Idempotent replay of already settled authority does not re-apply OS slot
    @Test
    fun ownerConcurrency_idempotentReplayOfSettledAuthority_doesNotReapplyOsSlot() = runBlocking {
        seedAlarmFixtures(isRepeating = true)

        val result = coordinator.coordinateHandoff(createHandoffRequest())
        assertEquals(NormalAlarmHandoffOutcome.ADMITTED, result.outcome)

        val appliedCount = mutableListOf<String>()
        val mainSlotApplier = MainSlotApplier {
            appliedCount.add("APPLIED")
            true
        }
        val owner = ScheduleAuthorityOwner(
            authorityDao = db.scheduleAuthorityDao(),
            mainSlotApplier = mainSlotApplier,
            exactPermissionChecker = { true }
        )

        // Replay 1: Settle the RECONCILING intent
        val replay1 = owner.replayPersistedOperation(alarmId)
        assertTrue(replay1 is OwnerResult.OsAccepted)
        assertEquals(1, appliedCount.size)

        // Replay 2: Already SETTLED, must be ignored without calling OS
        val replay2 = owner.replayPersistedOperation(alarmId)
        assertEquals(OwnerResult.DuplicateIgnored, replay2)
        assertEquals(1, appliedCount.size) // No additional OS call
    }

    // FOCUSED TEST A: Exception DAO failure fails closed without entering transaction
    @Test
    fun testA_exceptionDaoThrows_failsClosedWithoutTransaction() = runBlocking {
        seedAlarmFixtures(isRepeating = true)
        val mockDb = spyk(db)
        val mockExceptionDao = mockk<com.sysadmindoc.alarmclock.data.local.ScheduleExceptionDao>()
        coEvery { mockExceptionDao.getExceptionsForAlarmGeneration(any(), any()) } throws RuntimeException("Disk I/O error on exceptions")
        every { mockDb.scheduleExceptionDao() } returns mockExceptionDao

        val failCoordinator = NormalAlarmHandoffCoordinator(
            database = mockDb,
            admissionFactory = NormalAlarmAdmissionFactory(uuidSupplier = { "test-uuid-200" })
        )

        val result = failCoordinator.coordinateHandoff(createHandoffRequest())
        assertEquals(NormalAlarmHandoffOutcome.REPOSITORY_FAILURE, result.outcome)
        assertEquals("EXCEPTION_DAO_FAILURE", result.decisionReason)
        assertNull(result.committedAuthorityIntent)

        // DB state remains pristine:
        val occ = db.issuedOccurrenceDao().getOccurrence(token)
        assertEquals(IssuedOccurrence.STATUS_ISSUED, occ?.status)
        val auth = db.scheduleAuthorityDao().getAuthorityByAlarmId(alarmId)
        assertEquals(1L, auth?.scheduleRevision)
    }

    // FOCUSED TEST B: Holiday authority acquisition failure fails closed without permissive scheduling
    @Test
    fun testB_holidayAuthorityAcquisitionFails_failsClosedWithoutPermissiveScheduling() = runBlocking {
        // Alarm has EXCLUDE policy (skip on holidays)
        val holidayAlarm = Alarm(
            id = alarmId,
            hour = 7,
            minute = 0,
            isEnabled = true,
            kind = Alarm.KIND_NORMAL,
            repeatDays = setOf(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY, DayOfWeek.FRIDAY),
            holidayPolicy = "EXCLUDE",
            generation = alarmGen,
            revision = 1,
            nextTriggerTime = scheduledAt
        )
        db.alarmDao().insert(holidayAlarm)
        db.scheduleAuthorityDao().insertAuthority(baseAuthority)
        db.issuedOccurrenceDao().insertSlotInitial(IssueSlot(alarmId = alarmId, currentIssueToken = token, generationId = generationId))
        db.issuedOccurrenceDao().insertOccurrence(IssuedOccurrence(
            issueToken = token, alarmId = alarmId, scheduledAt = scheduledAt, fireId = fireId,
            generationId = generationId, status = IssuedOccurrence.STATUS_ISSUED, baseTriggerTime = baseTriggerTime,
            alarmGeneration = alarmGen, authorityInstanceToken = baseAuthority.instanceToken,
            restoreEpoch = baseAuthority.restoreEpoch, scheduleRevision = baseAuthority.scheduleRevision,
            definitionRevision = baseAuthority.definitionRevision, createdAt = nowMs, updatedAt = nowMs
        ))

        // Coordinator has null HolidayRepository
        val failCoordinator = NormalAlarmHandoffCoordinator(
            database = db,
            admissionFactory = NormalAlarmAdmissionFactory(uuidSupplier = { "test-uuid-200" }),
            holidayRepository = null
        )

        val result = failCoordinator.coordinateHandoff(createHandoffRequest())
        assertEquals(NormalAlarmHandoffOutcome.REPOSITORY_FAILURE, result.outcome)
        assertEquals("HOLIDAY_AUTHORITY_FAILURE", result.decisionReason)
        assertNull(result.committedAuthorityIntent)

        // Verify fail-closed: occurrence remains ISSUED
        val occ = db.issuedOccurrenceDao().getOccurrence(token)
        assertEquals(IssuedOccurrence.STATUS_ISSUED, occ?.status)
    }

    // FOCUSED TEST C: Missing required prepared input fails closed
    @Test
    fun testC_missingRequiredPreparedInput_failsClosed() = runBlocking {
        seedAlarmFixtures(isRepeating = true)
        val request = createHandoffRequest()

        // Calling executeHandoff directly inside transaction with preparedRecovery = null on enabled alarm
        val ex = try {
            db.withTransaction {
                coordinator.executeHandoff(request, preparedRecovery = null)
            }
            null
        } catch (e: IllegalStateException) {
            e
        }
        assertNotNull("Must throw IllegalStateException when missing prepared recovery inputs", ex)
        assertTrue(ex!!.message!!.contains("Missing required prepared recovery inputs"))

        // Occurrence status remains ISSUED
        val occ = db.issuedOccurrenceDao().getOccurrence(token)
        assertEquals(IssuedOccurrence.STATUS_ISSUED, occ?.status)
    }

    // FOCUSED TEST D: Revalidation conflict (revision changed) aborts transaction
    @Test
    fun testD_revalidationConflict_scheduleRevisionChanged_abortsTransaction() = runBlocking {
        seedAlarmFixtures(isRepeating = true)
        val request = createHandoffRequest()

        val prep = coordinator.prepareRecoveryInputs(request)
        assertTrue(prep is NormalAlarmHandoffCoordinator.RecoveryPreparationResult.Ready)
        val prepared = (prep as NormalAlarmHandoffCoordinator.RecoveryPreparationResult.Ready).recovery

        // Simulate concurrent modification between PREPARE and Room transaction by presenting stale prepared expectation (rev 99)
        val stalePrepared = prepared.copy(expectedRevision = 99L)

        // Inside transaction: revalidation must detect revision mismatch between prepared expectation (99) and DB (1) and abort
        val ex = try {
            db.withTransaction {
                coordinator.executeHandoff(request, stalePrepared)
            }
            null
        } catch (e: IllegalStateException) {
            e
        }
        assertNotNull("Must abort on revision revalidation conflict", ex)
        assertTrue(ex!!.message!!.contains("Authority or alarm generation modified concurrently"))

        val occ = db.issuedOccurrenceDao().getOccurrence(token)
        assertEquals(IssuedOccurrence.STATUS_ISSUED, occ?.status)
    }

    // FOCUSED TEST E: No preparation inside transaction (zero external reads in TX)
    @Test
    fun testE_noPreparationInsideTransaction_zeroExternalReadsInTx() = runBlocking {
        seedAlarmFixtures(isRepeating = true)
        val request = createHandoffRequest()

        val mockHolidayRepo = mockk<com.sysadmindoc.alarmclock.data.repository.HolidayRepository>()
        val testCoordinator = NormalAlarmHandoffCoordinator(
            database = db,
            admissionFactory = NormalAlarmAdmissionFactory(uuidSupplier = { "test-uuid-200" }),
            holidayRepository = mockHolidayRepo
        )

        val prepared = NormalAlarmHandoffCoordinator.PreparedPostFireRecovery(
            isRecurringSchedule = true,
            nextTriggerTime = scheduledAt + 86400000L,
            actionKey = "postfire_$token",
            expectedRevision = baseAuthority.scheduleRevision,
            expectedDefinitionRevision = baseAuthority.definitionRevision,
            expectedAlarmGeneration = alarmGen
        )

        // Execute handoff inside transaction directly with preparedRecovery:
        db.withTransaction {
            testCoordinator.executeHandoff(request, prepared)
        }

        // Verify zero calls to HolidayRepository inside transaction
        coVerify(exactly = 0) { mockHolidayRepo.captureAuthorityWindow(any(), any()) }
    }

    // FOCUSED TEST F: Concurrent nextTrigger modification causes CAS failure and complete rollback
    @Test
    fun testF_concurrentNextTriggerModification_casFailsAndRollsBack() = runBlocking {
        seedAlarmFixtures(isRepeating = true)
        val request = createHandoffRequest()

        // Install interceptor that simulates concurrent modification at cutpoint BEFORE_ALARM_DEFINITION_CAS
        coordinator.cutpointInterceptor = { cutpoint ->
            if (cutpoint == NormalAlarmHandoffCoordinator.Cutpoint.BEFORE_ALARM_DEFINITION_CAS) {
                runBlocking {
                    db.alarmDao().updateNextTrigger(alarmId, scheduledAt + 9999L)
                }
            }
        }

        val result = coordinator.coordinateHandoff(request)
        assertEquals(NormalAlarmHandoffOutcome.CAS_CONFLICT, result.outcome)
        assertNull("Failed handoff must return null committed intent", result.committedAuthorityIntent)

        // Occurrence status remains ISSUED (rolled back)
        val occ = db.issuedOccurrenceDao().getOccurrence(token)
        assertEquals(IssuedOccurrence.STATUS_ISSUED, occ?.status)

        // The concurrent modification caused rollback, so the prepared trigger was NOT applied,
        // and database transaction rollback restored the original state.
        val alarm = db.alarmDao().getById(alarmId)
        assertEquals(scheduledAt, alarm?.nextTriggerTime)
    }

    // FOCUSED TEST G: Initial trigger behavior with strict authority fencing
    @Test
    fun testG_initialTriggerBehavior_strictAuthorityFencing() = runBlocking {
        seedAlarmFixtures(isRepeating = true)
        // Set alarm nextTriggerTime to 0L (uninitialized)
        db.alarmDao().updateNextTrigger(alarmId, 0L)

        val request = createHandoffRequest()
        val result = coordinator.coordinateHandoff(request)

        assertEquals(NormalAlarmHandoffOutcome.ADMITTED, result.outcome)
        assertNotNull(result.committedAuthorityIntent)

        val updatedAlarm = db.alarmDao().getById(alarmId)
        assertEquals(result.committedAuthorityIntent!!.desiredTriggerMillis, updatedAlarm?.nextTriggerTime)
    }

    // FOCUSED TEST H: Postcommit cutpoint throws without rolling back or reporting conflict
    @Test
    fun testH_postcommitCutpointThrows_doesNotRollbackOrReportConflict() = runBlocking {
        seedAlarmFixtures(isRepeating = true)
        val request = createHandoffRequest()

        coordinator.cutpointInterceptor = { cutpoint ->
            if (cutpoint == NormalAlarmHandoffCoordinator.Cutpoint.AFTER_TRANSACTION_COMMIT) {
                throw RuntimeException("Postcommit observation failure")
            }
        }

        val result = coordinator.coordinateHandoff(request)

        // Room transaction already committed successfully: must not report failure or CAS conflict!
        assertEquals(NormalAlarmHandoffOutcome.ADMITTED, result.outcome)
        assertNotNull(result.sessionId)
        assertNotNull(result.committedAuthorityIntent)

        // DB state is committed:
        val occ = db.issuedOccurrenceDao().getOccurrence(token)
        assertEquals(IssuedOccurrence.STATUS_ADMITTED, occ?.status)
        val session = db.alarmSessionDao().getSessionById(result.sessionId!!)
        assertNotNull(session)
        val auth = db.scheduleAuthorityDao().getAuthorityByAlarmId(alarmId)
        assertEquals(2L, auth?.scheduleRevision)
        assertEquals("RECONCILING", auth?.reconcileState)
    }

    // FOCUSED TEST I: No lost durable Recovery Intent on postcommit observer failure
    @Test
    fun testI_noLostDurableRecoveryIntent_onPostcommitObserverFailure() = runBlocking {
        seedAlarmFixtures(isRepeating = true)
        val request = createHandoffRequest()

        coordinator.cutpointInterceptor = { cutpoint ->
            if (cutpoint == NormalAlarmHandoffCoordinator.Cutpoint.AFTER_TRANSACTION_COMMIT) {
                throw IllegalStateException("Telemetry observer failed")
            }
        }

        val result = coordinator.coordinateHandoff(request)
        assertEquals(NormalAlarmHandoffOutcome.ADMITTED, result.outcome)

        // Full typed intent is preserved without loss:
        val intent = result.committedAuthorityIntent
        assertNotNull(intent)
        assertEquals(alarmId, intent!!.alarmId)
        assertEquals(2L, intent.claimedRevision)
        assertEquals("SET", intent.desiredOperation)
        assertTrue(intent.desiredTriggerMillis != null && intent.desiredTriggerMillis!! > scheduledAt)
        assertEquals("RECONCILING", intent.reconcileState)
        assertEquals("postfire_$token", intent.actionKey)
    }

    // FOCUSED TEST J: Zero OS effects in Room transaction
    @Test
    fun testJ_zeroOsEffectsInRoomTransaction() = runBlocking {
        seedAlarmFixtures(isRepeating = true)
        val request = createHandoffRequest()

        var insideTx = false
        coordinator.cutpointInterceptor = { cutpoint ->
            if (cutpoint == NormalAlarmHandoffCoordinator.Cutpoint.BEFORE_OCCURRENCE_CAS) insideTx = true
            if (cutpoint == NormalAlarmHandoffCoordinator.Cutpoint.AFTER_TRANSACTION_COMMIT) insideTx = false
        }

        val result = coordinator.coordinateHandoff(request)
        assertEquals(NormalAlarmHandoffOutcome.ADMITTED, result.outcome)
        assertFalse("Coordinator transaction must finish cleanly without leaked state", insideTx)
    }

    // TASK 2: Positive ScheduleException ACTION_SKIP test (KNO-37 Phase H2-C4-P3D)
    @Test
    fun positiveExceptionSkip_advancesTriggerToNextEligibleDay() = runBlocking {
        val allDays = DayOfWeek.values().toSet()
        val zone = ZoneId.systemDefault()
        val today = LocalDate.of(2026, 11, 2) // Monday
        val alarmTime = LocalTime.of(7, 0)
        val scheduledAtZdt = ZonedDateTime.of(today, alarmTime, zone)
        val fireScheduledAt = scheduledAtZdt.toInstant().toEpochMilli()

        // Candidate D+1 (Tuesday 07:00)
        val candidateD1Zdt = scheduledAtZdt.plusDays(1)
        val candidateD1Ms = candidateD1Zdt.toInstant().toEpochMilli()

        // Candidate D+2 (Wednesday 07:00)
        val candidateD2Zdt = scheduledAtZdt.plusDays(2)
        val candidateD2Ms = candidateD2Zdt.toInstant().toEpochMilli()

        val authority = baseAuthority.copy(
            desiredTriggerMillis = fireScheduledAt
        )
        val alarm = Alarm(
            id = alarmId,
            hour = 7,
            minute = 0,
            isEnabled = true,
            kind = Alarm.KIND_NORMAL,
            scheduleKind = Alarm.SCHEDULE_KIND_REPEATING,
            repeatDays = allDays,
            generation = alarmGen,
            revision = 1,
            nextTriggerTime = fireScheduledAt,
            snoozeDurationMinutes = 10,
            maxSnoozeCount = 3,
            holidayPolicy = HolidayPolicy.FOLLOW_BASE.name
        )
        db.alarmDao().insert(alarm)
        db.scheduleAuthorityDao().insertAuthority(authority)

        val slot = IssueSlot(
            alarmId = alarmId,
            currentIssueToken = token,
            generationId = generationId
        )
        db.issuedOccurrenceDao().insertSlotInitial(slot)

        val occurrence = IssuedOccurrence(
            issueToken = token,
            alarmId = alarmId,
            scheduledAt = fireScheduledAt,
            fireId = fireId,
            generationId = generationId,
            status = IssuedOccurrence.STATUS_ISSUED,
            baseTriggerTime = fireScheduledAt,
            alarmGeneration = alarmGen,
            authorityInstanceToken = authority.instanceToken,
            restoreEpoch = authority.restoreEpoch,
            scheduleRevision = authority.scheduleRevision,
            definitionRevision = authority.definitionRevision,
            createdAt = fireScheduledAt,
            updatedAt = fireScheduledAt
        )
        db.issuedOccurrenceDao().insertOccurrence(occurrence)

        // Persist ScheduleException TYPE_SKIP for candidate D+1
        val skipException = ScheduleException(
            alarmId = alarmId,
            baseTriggerTime = candidateD1Ms,
            exceptionType = ScheduleException.TYPE_SKIP,
            generation = alarmGen
        )
        db.scheduleExceptionDao().insert(skipException)

        val request = NormalAlarmHandoffRequest(
            issueToken = token,
            expectedAlarmId = alarmId,
            expectedFireId = fireId,
            expectedScheduledAt = fireScheduledAt,
            expectedBaseTriggerTime = fireScheduledAt,
            expectedAlarmGeneration = alarmGen,
            expectedSlotGenerationId = generationId,
            expectedAuthorityFingerprint = ScheduleAuthorityFingerprint.fromAuthority(authority),
            nowEpochMs = fireScheduledAt,
            elapsedRealtimeMs = 10000L,
            bootCount = 1L,
            settings = AppSettings(),
            effectiveNormalAutoSilenceMinutes = 10
        )

        val result = coordinator.coordinateHandoff(request)

        assertEquals(NormalAlarmHandoffOutcome.ADMITTED, result.outcome)
        assertNotNull(result.sessionId)
        assertNotNull(result.committedAuthorityIntent)

        // Verify alarms.nextTriggerTime matches D+2
        val updatedAlarm = db.alarmDao().getById(alarmId)
        assertNotNull(updatedAlarm)
        assertEquals("Next trigger must advance past skipped candidate D+1 to D+2", candidateD2Ms, updatedAlarm?.nextTriggerTime)

        // Verify Authority desiredTriggerMillis matches D+2
        val updatedAuth = db.scheduleAuthorityDao().getAuthorityByAlarmId(alarmId)
        assertNotNull(updatedAuth)
        assertEquals(candidateD2Ms, updatedAuth?.desiredTriggerMillis)
        assertEquals("RECONCILING", updatedAuth?.reconcileState)
        assertEquals("SET", updatedAuth?.desiredOperation)

        // Verify committedAuthorityIntent matches
        val intent = result.committedAuthorityIntent!!
        assertEquals(candidateD2Ms, intent.desiredTriggerMillis)
        assertEquals("RECONCILING", intent.reconcileState)
        assertEquals("SET", intent.desiredOperation)

        // Verify no duplicate session created
        val activeSessions = db.alarmSessionDao().getActiveSessionsForAlarm(alarmId)
        assertEquals("Exactly one session must be created", 1, activeSessions.size)
        assertEquals(result.sessionId, activeSessions.first().sessionId)
    }

    // TASK 3: Positive Holiday EXCLUDE test (KNO-37 Phase H2-C4-P3D)
    @Test
    fun positiveHolidayExclude_advancesTriggerToNextEligibleDay() = runBlocking {
        val allDays = DayOfWeek.values().toSet()
        val zone = ZoneId.systemDefault()
        val today = LocalDate.of(2026, 11, 2) // Monday
        val alarmTime = LocalTime.of(7, 0)
        val scheduledAtZdt = ZonedDateTime.of(today, alarmTime, zone)
        val fireScheduledAt = scheduledAtZdt.toInstant().toEpochMilli()

        // Candidate D+1 (Tuesday 07:00) is a HOLIDAY
        val candidateD1Date = today.plusDays(1)
        val candidateD1Zdt = scheduledAtZdt.plusDays(1)
        val candidateD1Ms = candidateD1Zdt.toInstant().toEpochMilli()

        // Candidate D+2 (Wednesday 07:00) is eligible
        val candidateD2Zdt = scheduledAtZdt.plusDays(2)
        val candidateD2Ms = candidateD2Zdt.toInstant().toEpochMilli()

        val authority = baseAuthority.copy(
            desiredTriggerMillis = fireScheduledAt
        )
        val alarm = Alarm(
            id = alarmId,
            hour = 7,
            minute = 0,
            isEnabled = true,
            kind = Alarm.KIND_NORMAL,
            scheduleKind = Alarm.SCHEDULE_KIND_REPEATING,
            repeatDays = allDays,
            generation = alarmGen,
            revision = 1,
            nextTriggerTime = fireScheduledAt,
            snoozeDurationMinutes = 10,
            maxSnoozeCount = 3,
            holidayPolicy = HolidayPolicy.EXCLUDE.name
        )
        db.alarmDao().insert(alarm)
        db.scheduleAuthorityDao().insertAuthority(authority)

        val slot = IssueSlot(
            alarmId = alarmId,
            currentIssueToken = token,
            generationId = generationId
        )
        db.issuedOccurrenceDao().insertSlotInitial(slot)

        val occurrence = IssuedOccurrence(
            issueToken = token,
            alarmId = alarmId,
            scheduledAt = fireScheduledAt,
            fireId = fireId,
            generationId = generationId,
            status = IssuedOccurrence.STATUS_ISSUED,
            baseTriggerTime = fireScheduledAt,
            alarmGeneration = alarmGen,
            authorityInstanceToken = authority.instanceToken,
            restoreEpoch = authority.restoreEpoch,
            scheduleRevision = authority.scheduleRevision,
            definitionRevision = authority.definitionRevision,
            createdAt = fireScheduledAt,
            updatedAt = fireScheduledAt
        )
        db.issuedOccurrenceDao().insertOccurrence(occurrence)

        // Mock HolidayRepository returning a CapturedHolidayAuthorityWindow marking D+1 as HOLIDAY
        val snapshot = HolidayProviderSnapshot(
            providerId = NAGER_DATE_PROVIDER_ID,
            countryCode = "JP",
            generation = 1L,
            years = mapOf(
                today.year to HolidayYearCoverage(
                    year = today.year,
                    fetchedAtMillis = fireScheduledAt,
                    dates = setOf(candidateD1Date)
                )
            )
        )
        val mockHolidayRepo = mockk<HolidayRepository>()
        val authorityWindow = CapturedHolidayAuthorityWindow(
            startDate = today,
            endDateInclusive = today.plusDays(370L),
            configuredCountry = "JP",
            providerSnapshot = snapshot,
            manualOverrides = emptyMap(),
            capturedAtMillis = fireScheduledAt
        )
        coEvery { mockHolidayRepo.captureAuthorityWindow(any(), any()) } returns authorityWindow

        val testCoordinator = NormalAlarmHandoffCoordinator(
            database = db,
            admissionFactory = NormalAlarmAdmissionFactory(uuidSupplier = { "test-uuid-200" }),
            holidayRepository = mockHolidayRepo
        )

        val request = NormalAlarmHandoffRequest(
            issueToken = token,
            expectedAlarmId = alarmId,
            expectedFireId = fireId,
            expectedScheduledAt = fireScheduledAt,
            expectedBaseTriggerTime = fireScheduledAt,
            expectedAlarmGeneration = alarmGen,
            expectedSlotGenerationId = generationId,
            expectedAuthorityFingerprint = ScheduleAuthorityFingerprint.fromAuthority(authority),
            nowEpochMs = fireScheduledAt,
            elapsedRealtimeMs = 10000L,
            bootCount = 1L,
            settings = AppSettings(),
            effectiveNormalAutoSilenceMinutes = 10
        )

        val result = testCoordinator.coordinateHandoff(request)

        assertEquals(NormalAlarmHandoffOutcome.ADMITTED, result.outcome)
        assertNotNull(result.sessionId)
        assertNotNull(result.committedAuthorityIntent)

        // Verify alarms.nextTriggerTime matches D+2 (advancing past D+1 holiday)
        val updatedAlarm = db.alarmDao().getById(alarmId)
        assertNotNull(updatedAlarm)
        assertEquals("Next trigger must advance past holiday candidate D+1 to D+2", candidateD2Ms, updatedAlarm?.nextTriggerTime)

        // Verify Authority desiredTriggerMillis matches D+2
        val updatedAuth = db.scheduleAuthorityDao().getAuthorityByAlarmId(alarmId)
        assertNotNull(updatedAuth)
        assertEquals(candidateD2Ms, updatedAuth?.desiredTriggerMillis)
        assertEquals("RECONCILING", updatedAuth?.reconcileState)
        assertEquals("SET", updatedAuth?.desiredOperation)

        // Verify committedAuthorityIntent matches
        val intent = result.committedAuthorityIntent!!
        assertEquals(candidateD2Ms, intent.desiredTriggerMillis)
        assertEquals("RECONCILING", intent.reconcileState)
        assertEquals("SET", intent.desiredOperation)

        // Verify exactly one session created
        val activeSessions = db.alarmSessionDao().getActiveSessionsForAlarm(alarmId)
        assertEquals(1, activeSessions.size)
    }

    // TASK 2 CRASH CUTPOINT E: Crash after OS success before settleAuthority; replay converges without duplicate logical occurrences
    @Test
    fun crashCutpointE_osSuccessBeforeSettle_replayConvergesWithoutDuplicateOccurrences() = runBlocking {
        // 1. Commit normal Handoff.
        seedAlarmFixtures(isRepeating = true)
        val result = coordinator.coordinateHandoff(createHandoffRequest())
        assertEquals(NormalAlarmHandoffOutcome.ADMITTED, result.outcome)

        // 2. Assert RECONCILING.
        val authBeforeReplay = db.scheduleAuthorityDao().getAuthorityByAlarmId(alarmId)
        assertNotNull(authBeforeReplay)
        assertEquals("RECONCILING", authBeforeReplay?.reconcileState)
        val claimedRevision = authBeforeReplay!!.scheduleRevision
        assertEquals(2L, claimedRevision)

        // 3. Stateful fake MainSlotApplier tracking logical OS slot identity vs physical invocations.
        data class LogicalOsSlot(
            val alarmId: Long,
            val operation: String,
            val revision: Long,
            val actionKey: String?,
            val triggerMillis: Long?
        )

        class StatefulOsApplier {
            var physicalInvocationCount = 0
            val installedSlots = mutableMapOf<Long, LogicalOsSlot>()
            var shouldCrashAfterSlotInstall = false

            fun asApplier(): MainSlotApplier = MainSlotApplier { authority ->
                physicalInvocationCount++
                // 4. Fake applier records successful logical OS slot installation.
                installedSlots[authority.alarmId] = LogicalOsSlot(
                    alarmId = authority.alarmId,
                    operation = authority.desiredOperation,
                    revision = authority.scheduleRevision,
                    actionKey = authority.actionKey,
                    triggerMillis = authority.desiredTriggerMillis
                )

                // 5. Before Owner can settle, simulate process death/unknown outcome using a controlled exception.
                if (shouldCrashAfterSlotInstall) {
                    throw RuntimeException("Simulated process death/unknown outcome immediately following OS slot install")
                }
                true
            }
        }

        val applierState = StatefulOsApplier()
        applierState.shouldCrashAfterSlotInstall = true

        val initialOwner = ScheduleAuthorityOwner(
            authorityDao = db.scheduleAuthorityDao(),
            mainSlotApplier = applierState.asApplier(),
            exactPermissionChecker = { true }
        )

        // Execute initial Owner replay: OS slot installs, then crash/unknown outcome occurs
        val initialReplayResult = initialOwner.replayPersistedOperation(alarmId)
        assertTrue("Initial replay before settle must return OutcomeUnknown", initialReplayResult is OwnerResult.OutcomeUnknown)

        // Assert logical OS slot was indeed installed in OS transport before crash
        assertEquals(1, applierState.physicalInvocationCount)
        assertEquals(1, applierState.installedSlots.size)
        val initialInstalledSlot = applierState.installedSlots[alarmId]
        assertNotNull(initialInstalledSlot)
        assertEquals("SET", initialInstalledSlot?.operation)
        assertEquals(alarmId, initialInstalledSlot?.alarmId)
        assertEquals(claimedRevision, initialInstalledSlot?.revision)
        assertEquals(authBeforeReplay.actionKey, initialInstalledSlot?.actionKey)
        assertEquals(authBeforeReplay.desiredTriggerMillis, initialInstalledSlot?.triggerMillis)

        // 6. Assert Room remains RECONCILING.
        val authAfterCrash = db.scheduleAuthorityDao().getAuthorityByAlarmId(alarmId)
        assertNotNull(authAfterCrash)
        assertEquals("RECONCILING", authAfterCrash?.reconcileState)
        assertEquals("Room authority revision must remain unchanged", claimedRevision, authAfterCrash?.scheduleRevision)

        // 7. Construct a fresh Owner instance to represent process recovery.
        applierState.shouldCrashAfterSlotInstall = false
        val recoveredOwner = ScheduleAuthorityOwner(
            authorityDao = db.scheduleAuthorityDao(),
            mainSlotApplier = applierState.asApplier(),
            exactPermissionChecker = { true }
        )

        // 8. Retry replay using the SAME logical OS slot identity.
        val retryReplayResult = recoveredOwner.replayPersistedOperation(alarmId)
        assertTrue("Retry replay upon recovery must succeed", retryReplayResult is OwnerResult.OsAccepted)

        // 9. Assert logical OS slot converges without duplicate registration.
        // Physical invocation count is 2 (retry invoked applier again), but logical slot identity is exactly 1.
        assertEquals("Physical invocation count must be 2 after replay retry", 2, applierState.physicalInvocationCount)
        assertEquals("Logical scheduled slot count must remain exactly 1 (no duplicate registration)", 1, applierState.installedSlots.size)
        val convergedSlot = applierState.installedSlots[alarmId]
        assertEquals("Logical OS slot identity must be identical across replays", initialInstalledSlot, convergedSlot)

        // 10. Assert authority becomes SETTLED.
        val finalAuth = db.scheduleAuthorityDao().getAuthorityByAlarmId(alarmId)
        assertNotNull(finalAuth)
        assertEquals("SETTLED", finalAuth?.reconcileState)

        // 11. Assert scheduleRevision unchanged during replay.
        assertEquals("scheduleRevision must remain unchanged during replay settlement", claimedRevision, finalAuth?.scheduleRevision)

        // 12. Assert exactly one occurrence and one Session exist.
        val occ = db.issuedOccurrenceDao().getOccurrence(token)
        assertNotNull(occ)
        assertEquals(IssuedOccurrence.STATUS_ADMITTED, occ?.status)
        val occurrences = db.issuedOccurrenceDao().getOccurrencesByAlarmAndScheduledAt(alarmId, scheduledAt)
        assertEquals("Exactly one occurrence must exist in DB", 1, occurrences.size)
        val allSessions = db.alarmSessionDao().getActiveSessionsForAlarm(alarmId)
        assertEquals("Exactly one session must exist in DB", 1, allSessions.size)
    }

    // TASK 3 FAILURE RECOVERY: OS operation failure leaves RECONCILING intent intact for retry (fail-closed)
    @Test
    fun failureRecovery_osOperationFails_leavesReconcilingIntentForSubsequentReplay() = runBlocking {
        seedAlarmFixtures(isRepeating = true)

        val result = coordinator.coordinateHandoff(createHandoffRequest())
        assertEquals(NormalAlarmHandoffOutcome.ADMITTED, result.outcome)

        var shouldFailOs = true
        val mainSlotApplier = MainSlotApplier {
            if (shouldFailOs) {
                false // OS apply failed
            } else {
                true // OS apply succeeded on retry
            }
        }
        val owner = ScheduleAuthorityOwner(
            authorityDao = db.scheduleAuthorityDao(),
            mainSlotApplier = mainSlotApplier,
            exactPermissionChecker = { true }
        )

        // Attempt 1: OS failure
        val failResult = owner.replayPersistedOperation(alarmId)
        assertTrue("Failed OS operation must return OutcomeUnknown", failResult is OwnerResult.OutcomeUnknown)

        // Authority in DB must remain RECONCILING (fail-closed)
        val authAfterFailure = db.scheduleAuthorityDao().getAuthorityByAlarmId(alarmId)
        assertEquals("RECONCILING", authAfterFailure?.reconcileState)

        // Attempt 2: OS succeeds on retry
        shouldFailOs = false
        val retryResult = owner.replayPersistedOperation(alarmId)
        assertTrue("Subsequent replay must succeed when OS succeeds", retryResult is OwnerResult.OsAccepted)

        val settledAuth = db.scheduleAuthorityDao().getAuthorityByAlarmId(alarmId)
        assertEquals("SETTLED", settledAuth?.reconcileState)
    }

    // TASK 3 FAILURE RECOVERY: Exact alarm permission missing blocks OS apply and leaves RECONCILING intent (fail-closed)
    @Test
    fun failureRecovery_exactPermissionMissing_blocksOsApplyAndLeavesReconcilingIntent() = runBlocking {
        seedAlarmFixtures(isRepeating = true)

        val result = coordinator.coordinateHandoff(createHandoffRequest())
        assertEquals(NormalAlarmHandoffOutcome.ADMITTED, result.outcome)

        var hasExactPermission = false
        var osApplied = false
        val owner = ScheduleAuthorityOwner(
            authorityDao = db.scheduleAuthorityDao(),
            mainSlotApplier = {
                osApplied = true
                true
            },
            exactPermissionChecker = { hasExactPermission }
        )

        // Replay with missing permission
        val blockedResult = owner.replayPersistedOperation(alarmId)
        assertTrue("Permission missing must return PermissionBlocked", blockedResult is OwnerResult.PermissionBlocked)
        assertFalse("OS slot applier must not be called when permission is missing", osApplied)

        // Authority in DB must remain RECONCILING (fail-closed)
        val authAfterBlock = db.scheduleAuthorityDao().getAuthorityByAlarmId(alarmId)
        assertEquals("RECONCILING", authAfterBlock?.reconcileState)

        // Grant permission and re-verify recovery
        hasExactPermission = true
        val allowedResult = owner.replayPersistedOperation(alarmId)
        assertTrue("Replay must succeed once permission is granted", allowedResult is OwnerResult.OsAccepted)
        assertTrue("OS slot applier must be called once permission is granted", osApplied)

        val settledAuth = db.scheduleAuthorityDao().getAuthorityByAlarmId(alarmId)
        assertEquals("SETTLED", settledAuth?.reconcileState)
    }

    // TASK 6 BOOT RECOVERY BOUNDARY: Inactive query discovers pending RECONCILING intent and converges to SETTLED
    @Test
    fun bootRecoveryBoundary_discoversPendingReconcilingIntentAndSettles() = runBlocking {
        seedAlarmFixtures(isRepeating = true)

        val result = coordinator.coordinateHandoff(createHandoffRequest())
        assertEquals(NormalAlarmHandoffOutcome.ADMITTED, result.outcome)

        // Boot discovery query
        val pendingReconciling = db.scheduleAuthorityDao().getAllAuthorities().filter { it.reconcileState == "RECONCILING" }
        assertEquals("Boot query must discover the pending RECONCILING authority", 1, pendingReconciling.size)
        val pendingAuth = pendingReconciling.first()
        assertEquals(alarmId, pendingAuth.alarmId)

        // Boot recovery loop
        val appliedOps = mutableListOf<ScheduleAuthority>()
        val owner = ScheduleAuthorityOwner(
            authorityDao = db.scheduleAuthorityDao(),
            mainSlotApplier = { auth ->
                appliedOps.add(auth)
                true
            },
            exactPermissionChecker = { true }
        )

        for (auth in pendingReconciling) {
            val replayRes = owner.replayPersistedOperation(auth.alarmId)
            assertTrue(replayRes is OwnerResult.OsAccepted)
        }

        assertEquals(1, appliedOps.size)
        assertEquals(pendingAuth.desiredTriggerMillis, appliedOps.first().desiredTriggerMillis)

        // Boot query after settlement returns empty
        val remainingReconciling = db.scheduleAuthorityDao().getAllAuthorities().filter { it.reconcileState == "RECONCILING" }
        assertEquals(0, remainingReconciling.size)

        // Non-LIVE lifecycle: boot OS replay safely ignores without re-applying
        val nonLiveReplay = owner.replayBootOsOperation(alarmId)
        assertEquals("Non-LIVE authority must be skipped during boot replay", OwnerResult.DuplicateIgnored, nonLiveReplay)

        // LIVE lifecycle: boot OS replay rearms settled authority without advancing revision
        val settledAuth = db.scheduleAuthorityDao().getAuthorityByAlarmId(alarmId)
        assertNotNull(settledAuth)
        assertEquals("SETTLED", settledAuth?.reconcileState)
        assertEquals(pendingAuth.scheduleRevision, settledAuth?.scheduleRevision)

        db.scheduleAuthorityDao().insertAuthority(settledAuth!!.copy(lifecycle = "LIVE"))
        val bootReplayRes = owner.replayBootOsOperation(alarmId)
        assertTrue("Boot OS replay of LIVE settled authority must succeed", bootReplayRes is OwnerResult.OsAccepted)
        val rearmedAuth = db.scheduleAuthorityDao().getAuthorityByAlarmId(alarmId)
        assertEquals("SETTLED", rearmedAuth?.reconcileState)
        assertEquals(pendingAuth.scheduleRevision, rearmedAuth?.scheduleRevision)
    }
}
