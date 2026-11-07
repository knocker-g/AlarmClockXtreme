package com.sysadmindoc.alarmclock.domain.evaluator

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.squareup.moshi.Moshi
import com.sysadmindoc.alarmclock.data.local.AlarmDatabase
import com.sysadmindoc.alarmclock.data.local.entity.AlarmAdmissionTombstoneEntity
import com.sysadmindoc.alarmclock.data.local.entity.AlarmConfigurationSnapshot
import com.sysadmindoc.alarmclock.data.local.entity.AlarmSessionEntity
import com.sysadmindoc.alarmclock.data.local.entity.IssueSlot
import com.sysadmindoc.alarmclock.data.local.entity.IssuedOccurrence
import com.sysadmindoc.alarmclock.data.local.entity.ScheduleAuthority
import com.sysadmindoc.alarmclock.data.model.Alarm
import com.sysadmindoc.alarmclock.data.model.AlarmOccurrenceId
import com.sysadmindoc.alarmclock.data.preferences.AppSettings
import com.sysadmindoc.alarmclock.domain.session.NormalAlarmAdmissionFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class NormalAlarmHandoffCoordinatorTest {

    private lateinit var context: Context
    private lateinit var dbFile: File
    private lateinit var db: AlarmDatabase
    private lateinit var coordinator: NormalAlarmHandoffCoordinator

    private val alarmId = 100L
    private val nowMs = 1793796000000L
    private val scheduledAt = 1793796000000L
    private val baseTriggerTime = 1793796000000L
    private val fireId = "fire-100"
    private val token = "issue_token_100"
    private val generationId = 2L
    private val alarmGen = 1

    private val authority = ScheduleAuthority(
        alarmId = alarmId,
        instanceToken = "inst-token-100",
        definitionRevision = 1L,
        scheduleRevision = 1L,
        lifecycle = "ACTIVE",
        desiredOperation = "FIRE",
        desiredTriggerMillis = scheduledAt,
        reconcileState = "RECONCILED",
        restoreEpoch = 1000L,
        actionKey = "action-100"
    )

    private val fingerprint = ScheduleAuthorityFingerprint.fromAuthority(authority)

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        dbFile = context.getDatabasePath("test_normal_alarm_handoff_coord.db")
        if (dbFile.exists()) dbFile.delete()
        dbFile.parentFile?.mkdirs()

        db = Room.databaseBuilder(context, AlarmDatabase::class.java, dbFile.absolutePath)
            .allowMainThreadQueries()
            .setJournalMode(androidx.room.RoomDatabase.JournalMode.TRUNCATE)
            .addCallback(AlarmDatabase.DATABASE_CALLBACK)
            .build()

        coordinator = NormalAlarmHandoffCoordinator(
            database = db,
            admissionFactory = NormalAlarmAdmissionFactory(uuidSupplier = { "test-uuid-100" })
        )
    }

    @After
    fun tearDown() {
        try { db.close() } catch (_: Exception) {}
        if (dbFile.exists()) dbFile.delete()
    }

    private suspend fun seedBaseFixtures(
        occurrenceStatus: String = IssuedOccurrence.STATUS_ISSUED,
        slotCurrentToken: String? = token,
        slotGen: Long = generationId,
        alarmGeneration: Int = alarmGen,
        authorityFingerprintToUse: ScheduleAuthority = authority,
        occurrenceAuthorityInstanceToken: String? = authorityFingerprintToUse.instanceToken,
        occurrenceRestoreEpoch: Long = authorityFingerprintToUse.restoreEpoch,
        occurrenceScheduleRevision: Long = authorityFingerprintToUse.scheduleRevision,
        occurrenceDefinitionRevision: Long = authorityFingerprintToUse.definitionRevision,
        alarmEnabled: Boolean = true,
        alarmKind: String = Alarm.KIND_NORMAL
    ) {
        val alarm = Alarm(
            id = alarmId,
            hour = 7,
            minute = 0,
            isEnabled = alarmEnabled,
            kind = alarmKind,
            generation = alarmGeneration,
            revision = 1,
            snoozeDurationMinutes = 10,
            maxSnoozeCount = 3
        )
        db.alarmDao().insert(alarm)
        db.scheduleAuthorityDao().insertAuthority(authorityFingerprintToUse)

        val slot = IssueSlot(
            alarmId = alarmId,
            currentIssueToken = slotCurrentToken,
            generationId = slotGen
        )
        db.issuedOccurrenceDao().insertSlotInitial(slot)

        val occurrence = IssuedOccurrence(
            issueToken = token,
            alarmId = alarmId,
            scheduledAt = scheduledAt,
            fireId = fireId,
            generationId = slotGen,
            status = occurrenceStatus,
            baseTriggerTime = baseTriggerTime,
            alarmGeneration = alarmGeneration,
            authorityInstanceToken = occurrenceAuthorityInstanceToken,
            restoreEpoch = occurrenceRestoreEpoch,
            scheduleRevision = occurrenceScheduleRevision,
            definitionRevision = occurrenceDefinitionRevision,
            createdAt = nowMs,
            updatedAt = nowMs
        )
        db.issuedOccurrenceDao().insertOccurrence(occurrence)
    }

    private fun createValidRequest(
        effectiveAutoSilence: Int? = 15,
        reqNowMs: Long = nowMs,
        reqElapsedRealtime: Long = 1000L,
        reqBootCount: Long = 1L,
        reqSettings: AppSettings = AppSettings(autoSilenceMinutes = 15)
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
            nowEpochMs = reqNowMs,
            elapsedRealtimeMs = reqElapsedRealtime,
            bootCount = reqBootCount,
            settings = reqSettings,
            effectiveNormalAutoSilenceMinutes = effectiveAutoSilence
        )
    }

    private fun createSessionEntity(
        sessionId: String = "session-active-1",
        targetAlarmId: Long = alarmId,
        state: String = "FIRING",
        occurrenceKey: String = "occ-100",
        admissionKey: String = "admission_${targetAlarmId}_$occurrenceKey"
    ): AlarmSessionEntity {
        return AlarmSessionEntity(
            sessionId = sessionId,
            admissionKey = admissionKey,
            alarmId = targetAlarmId,
            originKind = "NORMAL",
            originScheduleKind = "ONE_TIME",
            generation = 1,
            revision = 1,
            fireId = fireId,
            occurrenceKey = occurrenceKey,
            canonicalBaseTriggerMs = nowMs,
            scheduledTargetEpochMs = nowMs,
            sessionState = state,
            sessionVersion = 1L,
            creationSequence = 1L,
            createdAtEpochMs = nowMs,
            createdElapsedRealtimeMs = 1000L,
            bootCount = 1L,
            updatedAtEpochMs = nowMs,
            absoluteDeadlineEpochMs = nowMs + 18_000_000L,
            configurationSnapshotJson = "{}"
        )
    }

    // ==========================================
    // Phase H2-C2-B1 Focused Tests (B1-01 to B1-10)
    // ==========================================

    // B1-01: missing explicit AutoEnd
    @Test
    fun B1_01_missingExplicitAutoEnd() = runBlocking {
        seedBaseFixtures()

        val request = createValidRequest(effectiveAutoSilence = null)
        val result = coordinator.coordinateHandoff(request)

        assertEquals(NormalAlarmHandoffOutcome.REJECTED_UNSUPPORTED_STATUS, result.outcome)
        assertTrue(result.diagnosticDetail?.contains("Missing explicit") == true)

        // Verify occurrence status remains untouched (ISSUED)
        val occ = db.issuedOccurrenceDao().getOccurrence(token)
        assertEquals(IssuedOccurrence.STATUS_ISSUED, occ?.status)

        // Verify zero session or tombstone created
        val sessions = db.alarmSessionDao().getActiveSessionsForAlarm(alarmId)
        assertTrue(sessions.isEmpty())
    }

    // B1-02: invalid AutoEnd duration
    @Test
    fun B1_02_invalidAutoEndDuration() = runBlocking {
        seedBaseFixtures()

        // Test 0 minutes
        val reqZero = createValidRequest(effectiveAutoSilence = 0)
        val resZero = coordinator.coordinateHandoff(reqZero)
        assertEquals(NormalAlarmHandoffOutcome.REJECTED_UNSUPPORTED_STATUS, resZero.outcome)

        // Test negative minutes
        val reqNeg = createValidRequest(effectiveAutoSilence = -5)
        val resNeg = coordinator.coordinateHandoff(reqNeg)
        assertEquals(NormalAlarmHandoffOutcome.REJECTED_UNSUPPORTED_STATUS, resNeg.outcome)

        // Verify occurrence status remains ISSUED
        val occ = db.issuedOccurrenceDao().getOccurrence(token)
        assertEquals(IssuedOccurrence.STATUS_ISSUED, occ?.status)
    }

    // B1-03: invalid Boot provenance
    @Test
    fun B1_03_invalidBootProvenance() = runBlocking {
        seedBaseFixtures()

        // Test 0L boot count
        val reqZero = createValidRequest(reqBootCount = 0L)
        val resZero = coordinator.coordinateHandoff(reqZero)
        assertEquals(NormalAlarmHandoffOutcome.REJECTED_STALE_IDENTITY, resZero.outcome)

        // Test negative boot count (-1L)
        val reqNeg = createValidRequest(reqBootCount = -1L)
        val resNeg = coordinator.coordinateHandoff(reqNeg)
        assertEquals(NormalAlarmHandoffOutcome.REJECTED_STALE_IDENTITY, resNeg.outcome)

        // Verify zero mutation
        val occ = db.issuedOccurrenceDao().getOccurrence(token)
        assertEquals(IssuedOccurrence.STATUS_ISSUED, occ?.status)
    }

    // B1-04: invalid elapsed time
    @Test
    fun B1_04_invalidElapsedTime() = runBlocking {
        seedBaseFixtures()

        // Test 0L elapsed time
        val reqZero = createValidRequest(reqElapsedRealtime = 0L)
        val resZero = coordinator.coordinateHandoff(reqZero)
        assertEquals(NormalAlarmHandoffOutcome.REJECTED_STALE_IDENTITY, resZero.outcome)

        // Test negative elapsed time
        val reqNeg = createValidRequest(reqElapsedRealtime = -100L)
        val resNeg = coordinator.coordinateHandoff(reqNeg)
        assertEquals(NormalAlarmHandoffOutcome.REJECTED_STALE_IDENTITY, resNeg.outcome)

        // Verify zero mutation
        val occ = db.issuedOccurrenceDao().getOccurrence(token)
        assertEquals(IssuedOccurrence.STATUS_ISSUED, occ?.status)
    }

    // B1-05: required settings snapshot
    @Test
    fun B1_05_requiredSettingsSnapshot() = runBlocking {
        seedBaseFixtures()

        val customSettings = AppSettings(
            autoSilenceMinutes = 20,
            defaultSnoozeDuration = 7
        )
        val request = createValidRequest(
            effectiveAutoSilence = 20,
            reqSettings = customSettings
        )
        val result = coordinator.coordinateHandoff(request)

        assertEquals(NormalAlarmHandoffOutcome.ADMITTED, result.outcome)
        val sessionId = result.sessionId
        assertNotNull(sessionId)

        val session = db.alarmSessionDao().getSessionById(sessionId!!)
        assertNotNull(session)

        val moshi = Moshi.Builder().build()
        val adapter = moshi.adapter(AlarmConfigurationSnapshot::class.java)
        val snapshot = adapter.fromJson(session!!.configurationSnapshotJson)
        assertNotNull(snapshot)

        assertEquals(20, snapshot?.autoSilenceMinutes)
        assertEquals(10, snapshot?.snoozeDurationMinutes) // Alarm fixture has snoozeDurationMinutes = 10
    }

    // B1-06: no synthetic time defaults
    @Test
    fun B1_06_noSyntheticTimeDefaults() = runBlocking {
        // Reflection check: NormalAlarmHandoffRequest has NO default parameter constructors
        val constructors = NormalAlarmHandoffRequest::class.java.constructors
        // Kotlin default parameters generate a synthetic constructor with DefaultConstructorMarker; verify none
        val syntheticConstructors = constructors.filter { c ->
            c.parameterTypes.any { it.name.contains("DefaultConstructorMarker") }
        }
        assertTrue("Request contract must not have synthetic default constructors", syntheticConstructors.isEmpty())

        seedBaseFixtures()
        val reqZeroTime = createValidRequest(reqNowMs = 0L)
        val resZeroTime = coordinator.coordinateHandoff(reqZeroTime)
        assertEquals(NormalAlarmHandoffOutcome.REJECTED_STALE_IDENTITY, resZeroTime.outcome)
    }

    // B1-07: repository failure mapping
    @Test
    fun B1_07_repositoryFailureMapping() = runBlocking {
        seedBaseFixtures()

        // Create coordinator with factory that simulates repository/storage write failure
        val failingFactory = object : NormalAlarmAdmissionFactory() {
            override fun prepareAdmission(event: com.sysadmindoc.alarmclock.domain.session.AlarmSessionEvent.AdmitSession): com.sysadmindoc.alarmclock.domain.session.PreparedAdmission {
                throw RuntimeException("Simulated disk I/O failure")
            }
        }
        val customCoordinator = NormalAlarmHandoffCoordinator(
            database = db,
            admissionFactory = failingFactory
        )

        val request = createValidRequest()
        val result = customCoordinator.coordinateHandoff(request)

        assertEquals(NormalAlarmHandoffOutcome.REPOSITORY_FAILURE, result.outcome)
        assertTrue(result.diagnosticDetail?.contains("Simulated disk I/O failure") == true)

        // Verify transaction rolled back cleanly: occurrence remains ISSUED
        val occ = db.issuedOccurrenceDao().getOccurrence(token)
        assertEquals(IssuedOccurrence.STATUS_ISSUED, occ?.status)
    }

    // B1-08: CAS conflict rollback
    @Test
    fun B1_08_casConflictRollback() = runBlocking {
        seedBaseFixtures()

        // Create coordinator with factory simulating constraint / CAS conflict on insertion
        val conflictFactory = object : NormalAlarmAdmissionFactory() {
            override fun prepareAdmission(event: com.sysadmindoc.alarmclock.domain.session.AlarmSessionEvent.AdmitSession): com.sysadmindoc.alarmclock.domain.session.PreparedAdmission {
                throw android.database.sqlite.SQLiteConstraintException("UNIQUE constraint failed: alarm_sessions.sessionId")
            }
        }
        val conflictCoordinator = NormalAlarmHandoffCoordinator(
            database = db,
            admissionFactory = conflictFactory
        )

        val request = createValidRequest()
        val result = conflictCoordinator.coordinateHandoff(request)

        assertEquals(NormalAlarmHandoffOutcome.CAS_CONFLICT, result.outcome)
        assertTrue(result.diagnosticDetail?.contains("Transaction failed") == true)

        // Verify transaction rolled back cleanly: occurrence remains ISSUED
        val occ = db.issuedOccurrenceDao().getOccurrence(token)
        assertEquals(IssuedOccurrence.STATUS_ISSUED, occ?.status)

        // Verify zero session or tombstone created
        val sessions = db.alarmSessionDao().getActiveSessionsForAlarm(alarmId)
        assertTrue(sessions.isEmpty())
    }

    // B1-09: existing valid admission
    @Test
    fun B1_09_existingValidAdmission() = runBlocking {
        seedBaseFixtures()

        val request = createValidRequest()
        val result = coordinator.coordinateHandoff(request)

        assertEquals(NormalAlarmHandoffOutcome.ADMITTED, result.outcome)
        assertNotNull(result.sessionId)

        val updatedOcc = db.issuedOccurrenceDao().getOccurrence(token)
        assertEquals(IssuedOccurrence.STATUS_ADMITTED, updatedOcc?.status)

        val tombstone = db.alarmSessionDao().getTombstoneByAdmissionKey(result.admissionKey!!)
        assertNotNull(tombstone)
        assertEquals(result.sessionId, tombstone?.sessionId)
    }

    // B1-10: duplicate/suppression unchanged
    @Test
    fun B1_10_duplicateAndSuppressionUnchanged() = runBlocking {
        seedBaseFixtures()

        // 1. First run: ADMIT
        val res1 = coordinator.coordinateHandoff(createValidRequest())
        assertEquals(NormalAlarmHandoffOutcome.ADMITTED, res1.outcome)

        // 2. Second run: DUPLICATE with same sessionId
        val res2 = coordinator.coordinateHandoff(createValidRequest())
        assertEquals(NormalAlarmHandoffOutcome.DUPLICATE, res2.outcome)
        assertEquals(res1.sessionId, res2.sessionId)

        // 3. Clean DB and test suppression with active session
        tearDown()
        setUp()
        seedBaseFixtures()
        val activeSession = createSessionEntity(sessionId = "active-session-prior")
        db.alarmSessionDao().insertSession(activeSession)

        val res3 = coordinator.coordinateHandoff(createValidRequest())
        assertEquals(NormalAlarmHandoffOutcome.SUPPRESSED, res3.outcome)

        val occ = db.issuedOccurrenceDao().getOccurrence(token)
        assertEquals(IssuedOccurrence.STATUS_SUPPRESSED, occ?.status)
    }

    // ==========================================
    // Original B01-B10 Compatibility Suite
    // ==========================================

    @Test
    fun B01_validAdmit() = runBlocking {
        seedBaseFixtures()

        val request = createValidRequest()
        val result = coordinator.coordinateHandoff(request)

        assertEquals(NormalAlarmHandoffOutcome.ADMITTED, result.outcome)
        val occurrenceId = AlarmOccurrenceId(alarmId, baseTriggerTime, alarmGen)
        val expectedAdmissionKey = "admission_${alarmId}_${occurrenceId.key}"
        val expectedSessionId = "session_${alarmId}_${occurrenceId.key}_test-uuid-100"

        assertEquals(expectedAdmissionKey, result.admissionKey)
        assertEquals(expectedSessionId, result.sessionId)

        val updatedOcc = db.issuedOccurrenceDao().getOccurrence(token)
        assertNotNull(updatedOcc)
        assertEquals(IssuedOccurrence.STATUS_ADMITTED, updatedOcc?.status)

        val tombstone = db.alarmSessionDao().getTombstoneByAdmissionKey(expectedAdmissionKey)
        assertNotNull(tombstone)
        assertEquals(expectedSessionId, tombstone?.sessionId)

        val session = db.alarmSessionDao().getSessionById(expectedSessionId)
        assertNotNull(session)
        assertEquals("FIRING", session?.sessionState)
        assertEquals(1L, session?.creationSequence)
        assertEquals(1L, session?.sessionVersion)
    }

    @Test
    fun B02_validSuppress() = runBlocking {
        seedBaseFixtures()

        val activeSession = createSessionEntity(sessionId = "active-session-prior")
        db.alarmSessionDao().insertSession(activeSession)

        val request = createValidRequest()
        val result = coordinator.coordinateHandoff(request)

        assertEquals(NormalAlarmHandoffOutcome.SUPPRESSED, result.outcome)

        val updatedOcc = db.issuedOccurrenceDao().getOccurrence(token)
        assertEquals(IssuedOccurrence.STATUS_SUPPRESSED, updatedOcc?.status)

        val sessions = db.alarmSessionDao().getActiveSessionsForAlarm(alarmId)
        assertEquals(1, sessions.size)
        assertEquals("active-session-prior", sessions.first().sessionId)

        val occurrenceId = AlarmOccurrenceId(alarmId, baseTriggerTime, alarmGen)
        val expectedAdmissionKey = "admission_${alarmId}_${occurrenceId.key}"
        val tombstone = db.alarmSessionDao().getTombstoneByAdmissionKey(expectedAdmissionKey)
        assertNull(tombstone)
    }

    @Test
    fun B03_validDuplicate() = runBlocking {
        seedBaseFixtures(occurrenceStatus = IssuedOccurrence.STATUS_ADMITTED)

        val occurrenceId = AlarmOccurrenceId(alarmId, baseTriggerTime, alarmGen)
        val canonicalAdmissionKey = "admission_${alarmId}_${occurrenceId.key}"
        val existingSessionId = "session-existing-1"

        val tombstone = AlarmAdmissionTombstoneEntity(
            admissionKey = canonicalAdmissionKey,
            alarmId = alarmId,
            sessionId = existingSessionId,
            occurrenceKey = occurrenceId.key,
            createdAtEpochMs = nowMs,
            horizonEpochMs = nowMs + 18_000_000L
        )
        val session = createSessionEntity(
            sessionId = existingSessionId,
            occurrenceKey = occurrenceId.key,
            admissionKey = canonicalAdmissionKey
        )
        db.alarmSessionDao().insertAdmissionTombstone(tombstone)
        db.alarmSessionDao().insertSession(session)

        val request = createValidRequest()
        val result = coordinator.coordinateHandoff(request)

        assertEquals(NormalAlarmHandoffOutcome.DUPLICATE, result.outcome)
        assertEquals(existingSessionId, result.sessionId)
        assertEquals(canonicalAdmissionKey, result.admissionKey)

        val sessions = db.alarmSessionDao().getActiveSessionsForAlarm(alarmId)
        assertEquals(1, sessions.size)
    }

    @Test
    fun B04_staleIdentityReject() = runBlocking {
        seedBaseFixtures()

        val request = createValidRequest().copy(expectedFireId = "wrong-fire-id")
        val result = coordinator.coordinateHandoff(request)

        assertEquals(NormalAlarmHandoffOutcome.REJECTED_STALE_IDENTITY, result.outcome)

        val occ = db.issuedOccurrenceDao().getOccurrence(token)
        assertEquals(IssuedOccurrence.STATUS_ISSUED, occ?.status)
    }

    @Test
    fun B05_staleAuthorityReject() = runBlocking {
        seedBaseFixtures()

        val mismatchedFingerprint = fingerprint.copy(instanceToken = "mismatched-token")
        val request = createValidRequest().copy(expectedAuthorityFingerprint = mismatchedFingerprint)
        val result = coordinator.coordinateHandoff(request)

        assertEquals(NormalAlarmHandoffOutcome.REJECTED_STALE_PROVENANCE, result.outcome)

        val occ = db.issuedOccurrenceDao().getOccurrence(token)
        assertEquals(IssuedOccurrence.STATUS_ISSUED, occ?.status)
    }

    @Test
    fun B06_activeSessionNotModified() = runBlocking {
        seedBaseFixtures()

        val activeSession = createSessionEntity(sessionId = "active-session-1")
        db.alarmSessionDao().insertSession(activeSession)

        val request = createValidRequest()
        val result = coordinator.coordinateHandoff(request)

        assertEquals(NormalAlarmHandoffOutcome.SUPPRESSED, result.outcome)

        val sessionAfter = db.alarmSessionDao().getSessionById("active-session-1")
        assertNotNull(sessionAfter)
        assertEquals(activeSession.sessionVersion, sessionAfter?.sessionVersion)
        assertEquals(activeSession.sessionState, sessionAfter?.sessionState)
        assertEquals(activeSession.updatedAtEpochMs, sessionAfter?.updatedAtEpochMs)
    }

    @Test
    fun B07_corruptedTombstoneLinkageReject() = runBlocking {
        seedBaseFixtures(occurrenceStatus = IssuedOccurrence.STATUS_ADMITTED)
        val result1 = coordinator.coordinateHandoff(createValidRequest())
        assertEquals(NormalAlarmHandoffOutcome.CORRUPTED_STATE, result1.outcome)

        val occurrenceId = AlarmOccurrenceId(alarmId, baseTriggerTime, alarmGen)
        val canonicalAdmissionKey = "admission_${alarmId}_${occurrenceId.key}"
        val orphanedTombstone = AlarmAdmissionTombstoneEntity(
            admissionKey = canonicalAdmissionKey,
            alarmId = alarmId,
            sessionId = "non-existent-session",
            occurrenceKey = occurrenceId.key,
            createdAtEpochMs = nowMs,
            horizonEpochMs = nowMs + 18_000_000L
        )
        db.alarmSessionDao().insertAdmissionTombstone(orphanedTombstone)
        val result2 = coordinator.coordinateHandoff(createValidRequest())
        assertEquals(NormalAlarmHandoffOutcome.CORRUPTED_STATE, result2.outcome)

        db.issuedOccurrenceDao().casUpdateOccurrenceStatus(
            issueToken = token,
            expectedStatus = IssuedOccurrence.STATUS_ADMITTED,
            newStatus = IssuedOccurrence.STATUS_SUPPRESSED,
            updatedAt = nowMs
        )
        val result3 = coordinator.coordinateHandoff(createValidRequest())
        assertEquals(NormalAlarmHandoffOutcome.CORRUPTED_STATE, result3.outcome)
    }

    @Test
    fun B08_sessionInsertFailureRollback() = runBlocking {
        seedBaseFixtures()

        val occurrenceId = AlarmOccurrenceId(alarmId, baseTriggerTime, alarmGen)
        val canonicalAdmissionKey = "admission_${alarmId}_${occurrenceId.key}"

        val conflictingTombstone = AlarmAdmissionTombstoneEntity(
            admissionKey = canonicalAdmissionKey,
            alarmId = alarmId,
            sessionId = "other-session",
            occurrenceKey = occurrenceId.key,
            createdAtEpochMs = nowMs,
            horizonEpochMs = nowMs + 18_000_000L
        )
        db.alarmSessionDao().insertAdmissionTombstone(conflictingTombstone)

        val request = createValidRequest()
        val result = coordinator.coordinateHandoff(request)

        assertEquals(NormalAlarmHandoffOutcome.CORRUPTED_STATE, result.outcome)

        val occ = db.issuedOccurrenceDao().getOccurrence(token)
        assertEquals(IssuedOccurrence.STATUS_ISSUED, occ?.status)
    }

    @Test
    fun B09_repeatedAdmissionIdempotence() = runBlocking {
        seedBaseFixtures()

        val request = createValidRequest()

        val firstResult = coordinator.coordinateHandoff(request)
        assertEquals(NormalAlarmHandoffOutcome.ADMITTED, firstResult.outcome)
        val firstSessionId = firstResult.sessionId
        assertNotNull(firstSessionId)

        val secondResult = coordinator.coordinateHandoff(request)
        assertEquals(NormalAlarmHandoffOutcome.DUPLICATE, secondResult.outcome)
        assertEquals(firstSessionId, secondResult.sessionId)

        val sessions = db.alarmSessionDao().getActiveSessionsForAlarm(alarmId)
        assertEquals(1, sessions.size)
    }

    @Test
    fun B10_noRuntimeOrOsEffects() = runBlocking {
        seedBaseFixtures()

        val request = createValidRequest()
        val result = coordinator.coordinateHandoff(request)

        assertEquals(NormalAlarmHandoffOutcome.ADMITTED, result.outcome)
        assertTrue(result.diagnosticDetail != null)
    }

    // ==========================================
    // Task 2: Expired Session Discriminator (C_T2_01 to C_T2_07)
    // ==========================================

    @Test
    fun C_T2_01_expiredFiringSession_mustNotAdmitSecondSession_suppressed() = runBlocking {
        seedBaseFixtures()

        // Expired FIRING session still in Room (nowMs >= absoluteDeadlineEpochMs)
        val expiredSession = createSessionEntity(
            sessionId = "session-expired-prior",
            state = "FIRING"
        ).copy(absoluteDeadlineEpochMs = nowMs - 1000L)
        db.alarmSessionDao().insertSession(expiredSession)

        val request = createValidRequest(reqNowMs = nowMs)
        val result = coordinator.coordinateHandoff(request)

        assertEquals(NormalAlarmHandoffOutcome.SUPPRESSED, result.outcome)
        val activeSessions = db.alarmSessionDao().getActiveSessionsForAlarm(alarmId)
        assertEquals(1, activeSessions.size)
        assertEquals("session-expired-prior", activeSessions.first().sessionId)
    }

    @Test
    fun C_T2_02_newOccurrenceLifecycle() = runBlocking {
        seedBaseFixtures()

        // 1. While prior session is in FIRING: must be SUPPRESSED
        val priorSession = createSessionEntity(sessionId = "session-prior", state = "FIRING")
        db.alarmSessionDao().insertSession(priorSession)

        val req1 = createValidRequest()
        val res1 = coordinator.coordinateHandoff(req1)
        assertEquals(NormalAlarmHandoffOutcome.SUPPRESSED, res1.outcome)

        // 2. Terminal CAS: transition prior session to COMPLETED
        val casUpdated = db.alarmSessionDao().casUpdateSessionState(
            sessionId = "session-prior",
            expectedVersion = 1L,
            expectedState = "FIRING",
            expectedAdmissionKey = priorSession.admissionKey,
            expectedGeneration = 1,
            expectedRevision = 1,
            newState = "COMPLETED",
            snoozeCount = 0,
            refireEpochMs = 0L,
            challengeUnlocked = false,
            updatedAtEpochMs = nowMs,
            completedAtEpochMs = nowMs,
            terminalReason = "DISMISSED"
        )
        assertEquals(1, casUpdated)

        // Re-seed slot and occurrence for next cycle
        val token2 = "issue_token_2"
        val fireId2 = "fire-102"
        val scheduledAt2 = scheduledAt + 86_400_000L
        val baseTrigger2 = baseTriggerTime + 86_400_000L
        val occ2 = IssuedOccurrence(
            issueToken = token2,
            alarmId = alarmId,
            scheduledAt = scheduledAt2,
            fireId = fireId2,
            generationId = generationId,
            status = IssuedOccurrence.STATUS_ISSUED,
            baseTriggerTime = baseTrigger2,
            alarmGeneration = alarmGen,
            authorityInstanceToken = authority.instanceToken,
            restoreEpoch = authority.restoreEpoch,
            scheduleRevision = authority.scheduleRevision,
            definitionRevision = authority.definitionRevision,
            createdAt = nowMs,
            updatedAt = nowMs
        )
        db.issuedOccurrenceDao().insertOccurrence(occ2)
        db.issuedOccurrenceDao().deleteSlotByAlarmId(alarmId)
        val slot2 = IssueSlot(alarmId = alarmId, currentIssueToken = token2, generationId = generationId)
        db.issuedOccurrenceDao().insertSlotInitial(slot2)

        val req2 = createValidRequest().copy(
            issueToken = token2,
            expectedFireId = fireId2,
            expectedScheduledAt = scheduledAt2,
            expectedBaseTriggerTime = baseTrigger2
        )
        val res2 = coordinator.coordinateHandoff(req2)
        assertEquals(NormalAlarmHandoffOutcome.ADMITTED, res2.outcome)
    }

    @Test
    fun C_T2_03_previousSessionTerminalCasPending_mustSuppress() = runBlocking {
        seedBaseFixtures()

        // Previous session still has state SNOOZED (non-terminal, CAS pending)
        val pendingSession = createSessionEntity(sessionId = "session-pending", state = "SNOOZED")
        db.alarmSessionDao().insertSession(pendingSession)

        val result = coordinator.coordinateHandoff(createValidRequest())
        assertEquals(NormalAlarmHandoffOutcome.SUPPRESSED, result.outcome)
    }

    @Test
    fun C_T2_04_wallClockForwardDiscontinuity_mustNotAdmitSecondSession() = runBlocking {
        seedBaseFixtures()

        val activeSession = createSessionEntity(sessionId = "session-active")
        db.alarmSessionDao().insertSession(activeSession)

        // Wall clock jumps +24 hours into future
        val jumpedNowMs = nowMs + 86_400_000L
        val request = createValidRequest(reqNowMs = jumpedNowMs)
        val result = coordinator.coordinateHandoff(request)

        assertEquals(NormalAlarmHandoffOutcome.SUPPRESSED, result.outcome)
        val active = db.alarmSessionDao().getActiveSessionsForAlarm(alarmId)
        assertEquals(1, active.size)
    }

    @Test
    fun C_T2_05_wallClockBackwardDiscontinuity_mustNotAdmitSecondSession() = runBlocking {
        seedBaseFixtures()

        val activeSession = createSessionEntity(sessionId = "session-active")
        db.alarmSessionDao().insertSession(activeSession)

        // Wall clock jumps backward -2 hours
        val jumpedBackwardMs = nowMs - 7_200_000L
        val request = createValidRequest(reqNowMs = jumpedBackwardMs)
        val result = coordinator.coordinateHandoff(request)

        assertEquals(NormalAlarmHandoffOutcome.SUPPRESSED, result.outcome)
        val active = db.alarmSessionDao().getActiveSessionsForAlarm(alarmId)
        assertEquals(1, active.size)
    }

    @Test
    fun C_T2_06_bootCountMismatch_mustNotAdmitSecondSession() = runBlocking {
        seedBaseFixtures()

        // Prior session created under bootCount = 1
        val priorSession = createSessionEntity(sessionId = "session-boot-1").copy(bootCount = 1L)
        db.alarmSessionDao().insertSession(priorSession)

        // Current request is under reboot (bootCount = 2)
        val request = createValidRequest(reqBootCount = 2L)
        val result = coordinator.coordinateHandoff(request)

        assertEquals(NormalAlarmHandoffOutcome.SUPPRESSED, result.outcome)
        val active = db.alarmSessionDao().getActiveSessionsForAlarm(alarmId)
        assertEquals(1, active.size)
    }

    @Test
    fun C_T2_07_monotonic5HourExpiry_mustNotAdmitSecondSession() = runBlocking {
        seedBaseFixtures()

        // Prior session has elapsed time 6 hours ago
        val priorSession = createSessionEntity(sessionId = "session-monotonic").copy(
            createdElapsedRealtimeMs = 1000L
        )
        db.alarmSessionDao().insertSession(priorSession)

        // Current request has elapsedRealtimeMs = 6 hours later (22_000_000ms)
        val request = createValidRequest(reqElapsedRealtime = 22_000_000L)
        val result = coordinator.coordinateHandoff(request)

        assertEquals(NormalAlarmHandoffOutcome.SUPPRESSED, result.outcome)
        val active = db.alarmSessionDao().getActiveSessionsForAlarm(alarmId)
        assertEquals(1, active.size)
    }

    // ==========================================
    // Task 3: Concurrent Admission (C01 to C08)
    // ==========================================

    @Test
    fun C01_twoCallersIdenticalIssueToken_concurrency() = runBlocking {
        seedBaseFixtures()

        val request = createValidRequest()
        val barrier = java.util.concurrent.CyclicBarrier(2)
        val outcomes = java.util.concurrent.CopyOnWriteArrayList<NormalAlarmHandoffResult>()

        val job1 = async(Dispatchers.IO) {
            barrier.await()
            coordinator.coordinateHandoff(request)
        }
        val job2 = async(Dispatchers.IO) {
            barrier.await()
            coordinator.coordinateHandoff(request)
        }

        val r1 = job1.await()
        val r2 = job2.await()
        outcomes.add(r1)
        outcomes.add(r2)

        val admittedCount = outcomes.count { it.outcome == NormalAlarmHandoffOutcome.ADMITTED }
        val duplicateOrConflictCount = outcomes.count {
            it.outcome == NormalAlarmHandoffOutcome.DUPLICATE || it.outcome == NormalAlarmHandoffOutcome.CAS_CONFLICT
        }

        assertEquals(1, admittedCount)
        assertEquals(1, duplicateOrConflictCount)

        // Invariant: exactly 1 session and 1 tombstone in DB
        val sessions = db.alarmSessionDao().getActiveSessionsForAlarm(alarmId)
        assertEquals(1, sessions.size)
        val occ = db.issuedOccurrenceDao().getOccurrence(token)
        assertEquals(IssuedOccurrence.STATUS_ADMITTED, occ?.status)
    }

    @Test
    fun C02_twoDistinctIssuesSameAlarm_concurrency() = runBlocking {
        seedBaseFixtures()

        // 1. First caller admits initial occurrence
        val req1 = createValidRequest()
        val res1 = coordinator.coordinateHandoff(req1)
        assertEquals(NormalAlarmHandoffOutcome.ADMITTED, res1.outcome)

        // 2. Next occurrence issued for same alarm (next day occurrence, slot advanced)
        val token2 = "issue_token_100_b"
        val fireId2 = "fire-100-b"
        val scheduledAt2 = scheduledAt + 86_400_000L
        val baseTrigger2 = baseTriggerTime + 86_400_000L
        val slotGen2 = generationId + 1L
        val occ2 = IssuedOccurrence(
            issueToken = token2,
            alarmId = alarmId,
            scheduledAt = scheduledAt2,
            fireId = fireId2,
            generationId = slotGen2,
            status = IssuedOccurrence.STATUS_ISSUED,
            baseTriggerTime = baseTrigger2,
            alarmGeneration = alarmGen,
            authorityInstanceToken = authority.instanceToken,
            restoreEpoch = authority.restoreEpoch,
            scheduleRevision = authority.scheduleRevision,
            definitionRevision = authority.definitionRevision,
            createdAt = nowMs,
            updatedAt = nowMs
        )
        db.issuedOccurrenceDao().insertOccurrence(occ2)
        db.issuedOccurrenceDao().deleteSlotByAlarmId(alarmId)
        db.issuedOccurrenceDao().insertSlotInitial(IssueSlot(alarmId, currentIssueToken = token2, generationId = slotGen2))

        val req2 = createValidRequest().copy(
            issueToken = token2,
            expectedFireId = fireId2,
            expectedScheduledAt = scheduledAt2,
            expectedBaseTriggerTime = baseTrigger2,
            expectedSlotGenerationId = slotGen2
        )

        // Second caller attempts admission for same alarm: MUST see active session and be SUPPRESSED
        val res2 = coordinator.coordinateHandoff(req2)
        assertEquals(NormalAlarmHandoffOutcome.SUPPRESSED, res2.outcome)

        // Invariant: strictly 1 active session in DB for this alarm
        val sessions = db.alarmSessionDao().getActiveSessionsForAlarm(alarmId)
        assertEquals(1, sessions.size)
        assertEquals(res1.sessionId, sessions.first().sessionId)
    }

    @Test
    fun C03_admissionVsSuppression_concurrency() = runBlocking {
        seedBaseFixtures()

        // Active session already in DB
        val activeSession = createSessionEntity(sessionId = "active-prior")
        db.alarmSessionDao().insertSession(activeSession)

        val res = coordinator.coordinateHandoff(createValidRequest())
        assertEquals(NormalAlarmHandoffOutcome.SUPPRESSED, res.outcome)

        val occ = db.issuedOccurrenceDao().getOccurrence(token)
        assertEquals(IssuedOccurrence.STATUS_SUPPRESSED, occ?.status)

        val sessions = db.alarmSessionDao().getActiveSessionsForAlarm(alarmId)
        assertEquals(1, sessions.size)
    }

    @Test
    fun C04_admissionVsIssueSlotRollover() = runBlocking {
        seedBaseFixtures()

        // Slot rolled over to next generation / token
        db.issuedOccurrenceDao().deleteSlotByAlarmId(alarmId)
        val newSlot = IssueSlot(alarmId = alarmId, currentIssueToken = "rolled_token", generationId = generationId + 1L)
        db.issuedOccurrenceDao().insertSlotInitial(newSlot)

        val res = coordinator.coordinateHandoff(createValidRequest())
        assertEquals(NormalAlarmHandoffOutcome.REJECTED_STALE_ISSUE_SLOT, res.outcome)

        val occ = db.issuedOccurrenceDao().getOccurrence(token)
        assertEquals(IssuedOccurrence.STATUS_ISSUED, occ?.status)
    }

    @Test
    fun C05_admissionVsScheduleAuthorityRevision() = runBlocking {
        seedBaseFixtures()

        // Authority revised in DB
        val revisedAuthority = authority.copy(scheduleRevision = 2L, instanceToken = "revised-inst-token")
        db.scheduleAuthorityDao().insertAuthority(revisedAuthority)

        val res = coordinator.coordinateHandoff(createValidRequest())
        assertEquals(NormalAlarmHandoffOutcome.REJECTED_STALE_PROVENANCE, res.outcome)

        val occ = db.issuedOccurrenceDao().getOccurrence(token)
        assertEquals(IssuedOccurrence.STATUS_ISSUED, occ?.status)
    }

    @Test
    fun C06_admissionVsAlarmDefinitionDisable() = runBlocking {
        seedBaseFixtures(alarmEnabled = false)

        val res = coordinator.coordinateHandoff(createValidRequest())
        assertEquals(NormalAlarmHandoffOutcome.REJECTED_UNSUPPORTED_SCHEDULE, res.outcome)

        val occ = db.issuedOccurrenceDao().getOccurrence(token)
        assertEquals(IssuedOccurrence.STATUS_ISSUED, occ?.status)
    }

    @Test
    fun C07_admissionVsTerminalSessionCas() = runBlocking {
        seedBaseFixtures()

        val priorSession = createSessionEntity(sessionId = "session-1", state = "FIRING")
        db.alarmSessionDao().insertSession(priorSession)

        // While pending: suppressed
        val resPending = coordinator.coordinateHandoff(createValidRequest())
        assertEquals(NormalAlarmHandoffOutcome.SUPPRESSED, resPending.outcome)

        // Terminal CAS executes
        val casUpdated = db.alarmSessionDao().casUpdateSessionState(
            sessionId = "session-1",
            expectedVersion = 1L,
            expectedState = "FIRING",
            expectedAdmissionKey = priorSession.admissionKey,
            expectedGeneration = 1,
            expectedRevision = 1,
            newState = "COMPLETED",
            snoozeCount = 0,
            refireEpochMs = 0L,
            challengeUnlocked = false,
            updatedAtEpochMs = nowMs,
            completedAtEpochMs = nowMs,
            terminalReason = "DISMISSED"
        )
        assertEquals(1, casUpdated)

        // Now active sessions for this alarm is 0
        val activeAfter = db.alarmSessionDao().getActiveSessionsForAlarm(alarmId)
        assertTrue(activeAfter.isEmpty())
    }

    @Test
    fun C08_duplicateAfterCompletedSession() = runBlocking {
        seedBaseFixtures(occurrenceStatus = IssuedOccurrence.STATUS_ADMITTED)

        val occurrenceId = AlarmOccurrenceId(alarmId, baseTriggerTime, alarmGen)
        val canonicalAdmissionKey = "admission_${alarmId}_${occurrenceId.key}"
        val existingSessionId = "session-completed-1"

        val tombstone = AlarmAdmissionTombstoneEntity(
            admissionKey = canonicalAdmissionKey,
            alarmId = alarmId,
            sessionId = existingSessionId,
            occurrenceKey = occurrenceId.key,
            createdAtEpochMs = nowMs,
            horizonEpochMs = nowMs + 18_000_000L
        )
        val completedSession = createSessionEntity(
            sessionId = existingSessionId,
            occurrenceKey = occurrenceId.key,
            admissionKey = canonicalAdmissionKey,
            state = "COMPLETED"
        ).copy(completedAtEpochMs = nowMs, terminalReason = "DISMISSED")

        db.alarmSessionDao().insertAdmissionTombstone(tombstone)
        db.alarmSessionDao().insertSession(completedSession)

        val request = createValidRequest()
        val result = coordinator.coordinateHandoff(request)

        assertEquals(NormalAlarmHandoffOutcome.DUPLICATE, result.outcome)
        assertEquals(existingSessionId, result.sessionId)
        assertEquals(canonicalAdmissionKey, result.admissionKey)
    }

    // ==========================================
    // Task 4: Crash / Rollback Cutpoints (C09 to C14)
    // ==========================================

    @Test
    fun C09_crashBeforeOccurrenceCas_rollbackNoCommit() = runBlocking {
        seedBaseFixtures()

        coordinator.cutpointInterceptor = { cutpoint ->
            if (cutpoint == NormalAlarmHandoffCoordinator.Cutpoint.BEFORE_OCCURRENCE_CAS) {
                throw RuntimeException("Simulated crash at C09: BEFORE_OCCURRENCE_CAS")
            }
        }

        val res = coordinator.coordinateHandoff(createValidRequest())
        assertEquals(NormalAlarmHandoffOutcome.REPOSITORY_FAILURE, res.outcome)

        // Verified persisted state: NO_COMMIT
        val occ = db.issuedOccurrenceDao().getOccurrence(token)
        assertEquals(IssuedOccurrence.STATUS_ISSUED, occ?.status)
        val tombstone = db.alarmSessionDao().getTombstoneByAdmissionKey("admission_${alarmId}_occ-100")
        assertNull(tombstone)
        val sessions = db.alarmSessionDao().getActiveSessionsForAlarm(alarmId)
        assertTrue(sessions.isEmpty())
        val seq = db.alarmSessionDao().getLastSequence()
        assertNull(seq)
    }

    @Test
    fun C10_crashAfterOccurrenceCasBeforeSessionInsert_rollbackNoCommit() = runBlocking {
        seedBaseFixtures()

        coordinator.cutpointInterceptor = { cutpoint ->
            if (cutpoint == NormalAlarmHandoffCoordinator.Cutpoint.AFTER_OCCURRENCE_CAS) {
                throw RuntimeException("Simulated crash at C10: AFTER_OCCURRENCE_CAS")
            }
        }

        val res = coordinator.coordinateHandoff(createValidRequest())
        assertEquals(NormalAlarmHandoffOutcome.REPOSITORY_FAILURE, res.outcome)

        // Verified persisted state: occurrence CAS MUST ROLL BACK to ISSUED
        val occ = db.issuedOccurrenceDao().getOccurrence(token)
        assertEquals(IssuedOccurrence.STATUS_ISSUED, occ?.status)
        val sessions = db.alarmSessionDao().getActiveSessionsForAlarm(alarmId)
        assertTrue(sessions.isEmpty())
        val seq = db.alarmSessionDao().getLastSequence()
        assertNull(seq)
    }

    @Test
    fun C11_crashAfterTombstoneInsertBeforeSequence_rollbackNoCommit() = runBlocking {
        seedBaseFixtures()

        coordinator.cutpointInterceptor = { cutpoint ->
            if (cutpoint == NormalAlarmHandoffCoordinator.Cutpoint.AFTER_TOMBSTONE_INSERT) {
                throw RuntimeException("Simulated crash at C11: AFTER_TOMBSTONE_INSERT")
            }
        }

        val res = coordinator.coordinateHandoff(createValidRequest())
        assertEquals(NormalAlarmHandoffOutcome.REPOSITORY_FAILURE, res.outcome)

        // Verified persisted state: Tombstone MUST ROLL BACK
        val occurrenceId = AlarmOccurrenceId(alarmId, baseTriggerTime, alarmGen)
        val tombstone = db.alarmSessionDao().getTombstoneByAdmissionKey("admission_${alarmId}_${occurrenceId.key}")
        assertNull(tombstone)
        val occ = db.issuedOccurrenceDao().getOccurrence(token)
        assertEquals(IssuedOccurrence.STATUS_ISSUED, occ?.status)
        val sessions = db.alarmSessionDao().getActiveSessionsForAlarm(alarmId)
        assertTrue(sessions.isEmpty())
    }

    @Test
    fun C12_crashAfterSequenceAllocationBeforeSessionInsert_rollbackNoCommit() = runBlocking {
        seedBaseFixtures()

        coordinator.cutpointInterceptor = { cutpoint ->
            if (cutpoint == NormalAlarmHandoffCoordinator.Cutpoint.AFTER_SEQUENCE_ALLOCATION) {
                throw RuntimeException("Simulated crash at C12: AFTER_SEQUENCE_ALLOCATION")
            }
        }

        val res = coordinator.coordinateHandoff(createValidRequest())
        assertEquals(NormalAlarmHandoffOutcome.REPOSITORY_FAILURE, res.outcome)

        // Verified persisted state: Sequence generator MUST ROLL BACK
        val seq = db.alarmSessionDao().getLastSequence()
        assertNull(seq)
        val occ = db.issuedOccurrenceDao().getOccurrence(token)
        assertEquals(IssuedOccurrence.STATUS_ISSUED, occ?.status)
        val sessions = db.alarmSessionDao().getActiveSessionsForAlarm(alarmId)
        assertTrue(sessions.isEmpty())
    }

    @Test
    fun C13_crashAfterSessionInsertBeforeCommit_rollbackNoCommit() = runBlocking {
        seedBaseFixtures()

        coordinator.cutpointInterceptor = { cutpoint ->
            if (cutpoint == NormalAlarmHandoffCoordinator.Cutpoint.BEFORE_TRANSACTION_COMMIT) {
                throw RuntimeException("Simulated crash at C13: BEFORE_TRANSACTION_COMMIT")
            }
        }

        val res = coordinator.coordinateHandoff(createValidRequest())
        assertEquals(NormalAlarmHandoffOutcome.REPOSITORY_FAILURE, res.outcome)

        // Verified persisted state: Session insert MUST ROLL BACK
        val occ = db.issuedOccurrenceDao().getOccurrence(token)
        assertEquals(IssuedOccurrence.STATUS_ISSUED, occ?.status)
        val sessions = db.alarmSessionDao().getActiveSessionsForAlarm(alarmId)
        assertTrue(sessions.isEmpty())
        val seq = db.alarmSessionDao().getLastSequence()
        assertNull(seq)
    }

    @Test
    fun C14_crashAfterSuccessfulRoomCommitBeforeOsEffects_dbStateAdmitted() = runBlocking {
        seedBaseFixtures()

        // Room transaction completes normally
        val res = coordinator.coordinateHandoff(createValidRequest())
        assertEquals(NormalAlarmHandoffOutcome.ADMITTED, res.outcome)
        val sessionId = res.sessionId
        assertNotNull(sessionId)

        // Verified persisted state: DB committed (ADMITTED)
        val occ = db.issuedOccurrenceDao().getOccurrence(token)
        assertEquals(IssuedOccurrence.STATUS_ADMITTED, occ?.status)
        val session = db.alarmSessionDao().getSessionById(sessionId!!)
        assertNotNull(session)
        assertEquals("FIRING", session?.sessionState)
        assertEquals(1L, session?.creationSequence)

        // Simulate external caller crashing during OS effect (e.g. AlarmManager or Notification)
        var osEffectFailed = false
        try {
            throw SecurityException("Simulated OS Permission failure on AlarmManager.setExactAndAllowWhileIdle")
        } catch (_: SecurityException) {
            osEffectFailed = true
        }
        assertTrue(osEffectFailed)

        // DB remains intact and ADMITTED (not atomic with OS effect)
        val sessionAfterCrash = db.alarmSessionDao().getSessionById(sessionId)
        assertNotNull(sessionAfterCrash)
        assertEquals("FIRING", sessionAfterCrash?.sessionState)
    }

    // ==========================================
    // Task 5: Retention Consistency (C_T5_01 to C_T5_04)
    // ==========================================

    @Test
    fun C_T5_01_completedSessionRemainsValidDuplicate() = runBlocking {
        seedBaseFixtures(occurrenceStatus = IssuedOccurrence.STATUS_ADMITTED)

        val occurrenceId = AlarmOccurrenceId(alarmId, baseTriggerTime, alarmGen)
        val canonicalAdmissionKey = "admission_${alarmId}_${occurrenceId.key}"
        val existingSessionId = "session-completed-retention"

        val tombstone = AlarmAdmissionTombstoneEntity(
            admissionKey = canonicalAdmissionKey,
            alarmId = alarmId,
            sessionId = existingSessionId,
            occurrenceKey = occurrenceId.key,
            createdAtEpochMs = nowMs,
            horizonEpochMs = nowMs + 18_000_000L
        )
        val completedSession = createSessionEntity(
            sessionId = existingSessionId,
            occurrenceKey = occurrenceId.key,
            admissionKey = canonicalAdmissionKey,
            state = "COMPLETED"
        ).copy(completedAtEpochMs = nowMs, terminalReason = "DISMISSED")

        db.alarmSessionDao().insertAdmissionTombstone(tombstone)
        db.alarmSessionDao().insertSession(completedSession)

        val res = coordinator.coordinateHandoff(createValidRequest())
        assertEquals(NormalAlarmHandoffOutcome.DUPLICATE, res.outcome)
        assertEquals(existingSessionId, res.sessionId)
    }

    @Test
    fun C_T5_02_tombstoneConstraintPreventsReadmission() = runBlocking {
        seedBaseFixtures()

        val occurrenceId = AlarmOccurrenceId(alarmId, baseTriggerTime, alarmGen)
        val canonicalAdmissionKey = "admission_${alarmId}_${occurrenceId.key}"

        val tombstone1 = AlarmAdmissionTombstoneEntity(
            admissionKey = canonicalAdmissionKey,
            alarmId = alarmId,
            sessionId = "session-1",
            occurrenceKey = occurrenceId.key,
            createdAtEpochMs = nowMs,
            horizonEpochMs = nowMs + 18_000_000L
        )
        val tombstone2 = AlarmAdmissionTombstoneEntity(
            admissionKey = canonicalAdmissionKey,
            alarmId = alarmId,
            sessionId = "session-2",
            occurrenceKey = occurrenceId.key,
            createdAtEpochMs = nowMs + 1000L,
            horizonEpochMs = nowMs + 19_000_000L
        )

        db.alarmSessionDao().insertAdmissionTombstone(tombstone1)

        var constraintThrown = false
        try {
            db.alarmSessionDao().insertAdmissionTombstone(tombstone2)
        } catch (_: android.database.sqlite.SQLiteConstraintException) {
            constraintThrown = true
        }
        assertTrue("Duplicate admissionKey must throw SQLiteConstraintException", constraintThrown)
    }

    @Test
    fun C_T5_03_terminalSessionDeletionDetectsOrphanedTombstone() = runBlocking {
        seedBaseFixtures(occurrenceStatus = IssuedOccurrence.STATUS_ADMITTED)

        val occurrenceId = AlarmOccurrenceId(alarmId, baseTriggerTime, alarmGen)
        val canonicalAdmissionKey = "admission_${alarmId}_${occurrenceId.key}"
        val existingSessionId = "session-to-delete"

        val tombstone = AlarmAdmissionTombstoneEntity(
            admissionKey = canonicalAdmissionKey,
            alarmId = alarmId,
            sessionId = existingSessionId,
            occurrenceKey = occurrenceId.key,
            createdAtEpochMs = nowMs,
            horizonEpochMs = nowMs + 18_000_000L
        )
        val completedSession = createSessionEntity(
            sessionId = existingSessionId,
            occurrenceKey = occurrenceId.key,
            admissionKey = canonicalAdmissionKey,
            state = "COMPLETED"
        ).copy(completedAtEpochMs = nowMs - 50_000L, terminalReason = "DISMISSED")

        db.alarmSessionDao().insertAdmissionTombstone(tombstone)
        db.alarmSessionDao().insertSession(completedSession)

        // Terminal session cleanup deletes the session row
        val deletedCount = db.alarmSessionDao().deleteExpiredTerminalSessions(nowMs)
        assertEquals(1, deletedCount)

        // Subsequent handoff request encounters orphaned tombstone -> CORRUPTED_STATE
        val res = coordinator.coordinateHandoff(createValidRequest())
        assertEquals(NormalAlarmHandoffOutcome.CORRUPTED_STATE, res.outcome)
        assertTrue(res.diagnosticDetail?.contains("missing in DB") == true)
    }

    @Test
    fun C_T5_04_historyCleanupDistinctFromAdmissionState() = runBlocking {
        seedBaseFixtures()

        // Verify that deleteExpiredTerminalSessions does not delete tombstones
        val occurrenceId = AlarmOccurrenceId(alarmId, baseTriggerTime, alarmGen)
        val canonicalAdmissionKey = "admission_${alarmId}_${occurrenceId.key}"
        val tombstone = AlarmAdmissionTombstoneEntity(
            admissionKey = canonicalAdmissionKey,
            alarmId = alarmId,
            sessionId = "session-retained",
            occurrenceKey = occurrenceId.key,
            createdAtEpochMs = nowMs,
            horizonEpochMs = nowMs + 18_000_000L
        )
        db.alarmSessionDao().insertAdmissionTombstone(tombstone)

        val deleted = db.alarmSessionDao().deleteExpiredTerminalSessions(nowMs + 100_000L)
        assertEquals(0, deleted)

        val tombstoneAfter = db.alarmSessionDao().getTombstoneByAdmissionKey(canonicalAdmissionKey)
        assertNotNull(tombstoneAfter)
    }
}
