package com.sysadmindoc.alarmclock.domain.session

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.squareup.moshi.Moshi
import com.sysadmindoc.alarmclock.data.local.AlarmDatabase
import com.sysadmindoc.alarmclock.data.local.entity.AlarmConfigurationSnapshot
import com.sysadmindoc.alarmclock.data.model.Alarm
import com.sysadmindoc.alarmclock.data.preferences.AppSettings
import com.sysadmindoc.alarmclock.data.repository.AlarmSessionRepository
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class NormalAlarmAdmissionFactoryTest {

    private lateinit var context: Context
    private lateinit var dbFile: File
    private lateinit var db: AlarmDatabase
    private lateinit var repository: AlarmSessionRepository
    private lateinit var engine: AlarmSessionTransitionEngine

    private val nowMs = 1793796000000L // Wed Nov 4 2026 06:00:00 AM EDT
    private val elapsedMs = 1000000L
    private val bootCount = 1L

    private val defaultAlarm = Alarm(
        id = 42L,
        scheduleKind = Alarm.SCHEDULE_KIND_REPEATING,
        kind = Alarm.KIND_NORMAL,
        generation = 3,
        revision = 2,
        snoozeDurationMinutes = 10,
        maxSnoozeCount = 3,
        wakeConfirmEnabled = false,
        wakeConfirmDelayMinutes = 10
    )

    private val defaultSettings = AppSettings(autoSilenceMinutes = 15)

    private fun createAdmitEvent(
        alarm: Alarm = defaultAlarm,
        settings: AppSettings = defaultSettings,
        effectiveAutoSilenceMinutes: Int? = 15,
        fireId: String = "fire_42_test",
        occurrenceKey: String = "occ_42_12345",
        canonicalBaseTriggerMs: Long = nowMs,
        scheduledTargetEpochMs: Long = nowMs,
        nowEpochMs: Long = nowMs,
        elapsedRealtimeMs: Long = elapsedMs,
        bootCount: Long = this.bootCount
    ): AlarmSessionEvent.AdmitSession {
        return AlarmSessionEvent.AdmitSession(
            alarm = alarm,
            settings = settings,
            effectiveNormalAutoSilenceMinutes = effectiveAutoSilenceMinutes,
            fireId = fireId,
            occurrenceKey = occurrenceKey,
            canonicalBaseTriggerMs = canonicalBaseTriggerMs,
            scheduledTargetEpochMs = scheduledTargetEpochMs,
            nowEpochMs = nowEpochMs,
            elapsedRealtimeMs = elapsedRealtimeMs,
            bootCount = bootCount
        )
    }

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        dbFile = context.getDatabasePath("test_factory_engine.db")
        if (dbFile.exists()) dbFile.delete()
        dbFile.parentFile?.mkdirs()

        db = Room.databaseBuilder(context, AlarmDatabase::class.java, dbFile.absolutePath)
            .allowMainThreadQueries()
            .setJournalMode(androidx.room.RoomDatabase.JournalMode.TRUNCATE)
            .addCallback(AlarmDatabase.DATABASE_CALLBACK)
            .build()

        repository = AlarmSessionRepository(db.alarmSessionDao())
        engine = AlarmSessionTransitionEngine(repository)
    }

    @After
    fun tearDown() {
        try { db.close() } catch (_: Exception) {}
        if (dbFile.exists()) dbFile.delete()
    }

    // F01: canonical occurrence/admission key
    @Test
    fun F01_canonicalOccurrenceAndAdmissionKey() {
        val factory = NormalAlarmAdmissionFactory()
        val event = createAdmitEvent()
        val prepared = factory.prepareAdmission(event)

        val expectedAdmissionKey = "admission_42_occ_42_12345"
        assertEquals(expectedAdmissionKey, prepared.tombstone.admissionKey)
        assertEquals(expectedAdmissionKey, prepared.session.admissionKey)
        assertEquals("occ_42_12345", prepared.tombstone.occurrenceKey)
        assertEquals("occ_42_12345", prepared.session.occurrenceKey)
        assertEquals(42L, prepared.tombstone.alarmId)
        assertEquals(42L, prepared.session.alarmId)
    }

    // F02: sessionId format and uniqueness
    @Test
    fun F02_sessionIdFormatAndUniqueness() {
        val factory = NormalAlarmAdmissionFactory()
        val event = createAdmitEvent()

        val prepared1 = factory.prepareAdmission(event)
        val prepared2 = factory.prepareAdmission(event)

        val sessionRegex = Regex("^session_42_occ_42_12345_[a-f0-9\\-]+$")
        assertTrue("SessionId must match expected pattern", sessionRegex.matches(prepared1.session.sessionId))
        assertTrue("SessionId must match expected pattern", sessionRegex.matches(prepared2.session.sessionId))
        assertNotEquals("Consecutive factory invocations must produce distinct sessionIds", prepared1.session.sessionId, prepared2.session.sessionId)
    }

    // F03: injected ID supplier behavior
    @Test
    fun F03_injectedIdSupplierBehavior() {
        val fixedUuid = "fixed-test-uuid-9999"
        val factory = NormalAlarmAdmissionFactory(uuidSupplier = { fixedUuid })
        val event = createAdmitEvent()
        val prepared = factory.prepareAdmission(event)

        val expectedSessionId = "session_42_occ_42_12345_fixed-test-uuid-9999"
        assertEquals(expectedSessionId, prepared.session.sessionId)
        assertEquals(expectedSessionId, prepared.tombstone.sessionId)
    }

    // F04: 5-hour absolute deadline
    @Test
    fun F04_fiveHourAbsoluteDeadline() {
        val factory = NormalAlarmAdmissionFactory()
        val testNow = 1_000_000_000L
        val event = createAdmitEvent(nowEpochMs = testNow)
        val prepared = factory.prepareAdmission(event)

        val expectedDeadline = testNow + 18_000_000L // exactly 5 hours
        assertEquals(expectedDeadline, prepared.session.absoluteDeadlineEpochMs)
        assertEquals(expectedDeadline, prepared.tombstone.horizonEpochMs)
    }

    // F05: JSON snapshot equivalence
    @Test
    fun F05_jsonSnapshotEquivalence() {
        val factory = NormalAlarmAdmissionFactory()
        val event = createAdmitEvent()
        val prepared = factory.prepareAdmission(event)

        val moshi = Moshi.Builder().build()
        val adapter = moshi.adapter(AlarmConfigurationSnapshot::class.java)
        val deserialized = adapter.fromJson(prepared.session.configurationSnapshotJson)
        assertNotNull(deserialized)

        val expectedSnapshot = AlarmConfigurationSnapshot.from(
            alarm = event.alarm,
            settings = event.settings,
            effectiveNormalAutoSilenceMinutes = event.effectiveNormalAutoSilenceMinutes
        )
        assertEquals(expectedSnapshot, deserialized)
    }

    // F06: session initial state/version
    @Test
    fun F06_sessionInitialStateAndVersion() {
        val factory = NormalAlarmAdmissionFactory()
        val event = createAdmitEvent()
        val session = factory.prepareAdmission(event).session

        assertEquals(AlarmSessionState.FIRING.name, session.sessionState)
        assertEquals(1L, session.sessionVersion)
        assertEquals(0, session.snoozeCount)
        assertEquals(0L, session.refireEpochMs)
        assertFalse(session.challengeUnlocked)
        assertNull(session.terminalReason)
        assertNull(session.completedAtEpochMs)
        assertEquals(defaultAlarm.scheduleKind, session.originScheduleKind)
        assertEquals("NORMAL", session.originKind)
        assertEquals(defaultAlarm.generation, session.generation)
        assertEquals(defaultAlarm.revision, session.revision)
    }

    // F07: boot/elapsed provenance
    @Test
    fun F07_bootAndElapsedProvenance() {
        val factory = NormalAlarmAdmissionFactory()
        val testBoot = 7L
        val testElapsed = 543210L
        val testNow = 2_000_000_000L
        val event = createAdmitEvent(
            bootCount = testBoot,
            elapsedRealtimeMs = testElapsed,
            nowEpochMs = testNow
        )
        val prepared = factory.prepareAdmission(event)

        assertEquals(testBoot, prepared.session.bootCount)
        assertEquals(testElapsed, prepared.session.createdElapsedRealtimeMs)
        assertEquals(testNow, prepared.session.createdAtEpochMs)
        assertEquals(testNow, prepared.session.updatedAtEpochMs)
        assertEquals(testNow, prepared.tombstone.createdAtEpochMs)
    }

    // F08: Tombstone points to Session
    @Test
    fun F08_tombstonePointsToSession() {
        val fixedUuid = "shared-uuid-1111"
        val factory = NormalAlarmAdmissionFactory(uuidSupplier = { fixedUuid })
        val event = createAdmitEvent()
        val prepared = factory.prepareAdmission(event)

        assertEquals(prepared.session.sessionId, prepared.tombstone.sessionId)
        assertEquals(prepared.session.admissionKey, prepared.tombstone.admissionKey)
        assertEquals(prepared.session.alarmId, prepared.tombstone.alarmId)
        assertEquals(prepared.session.occurrenceKey, prepared.tombstone.occurrenceKey)
        assertEquals(prepared.session.absoluteDeadlineEpochMs, prepared.tombstone.horizonEpochMs)
        assertEquals(prepared.session.createdAtEpochMs, prepared.tombstone.createdAtEpochMs)
    }

    // F09: sequence placeholder retained
    @Test
    fun F09_sequencePlaceholderRetained() {
        val factory = NormalAlarmAdmissionFactory()
        val event = createAdmitEvent()
        val session = factory.prepareAdmission(event).session

        assertEquals(0L, session.creationSequence)
    }

    // F10: malformed input failure behavior
    @Test(expected = IllegalArgumentException::class)
    fun F10_malformedInputFailureBehavior_nullAutoSilenceThrows() {
        val factory = NormalAlarmAdmissionFactory()
        val event = createAdmitEvent(effectiveAutoSilenceMinutes = null)
        factory.prepareAdmission(event)
    }

    @Test(expected = IllegalArgumentException::class)
    fun F10_malformedInputFailureBehavior_nonPositiveAutoSilenceThrows() {
        val factory = NormalAlarmAdmissionFactory()
        val event = createAdmitEvent(effectiveAutoSilenceMinutes = 0)
        factory.prepareAdmission(event)
    }

    // F11: existing Engine output unchanged
    @Test
    fun F11_existingEngineOutputUnchanged() = runBlocking {
        val fixedUuid = "engine-parity-uuid"
        val customFactory = NormalAlarmAdmissionFactory(uuidSupplier = { fixedUuid })
        val parityEngine = AlarmSessionTransitionEngine(
            repository = repository,
            admissionFactory = customFactory
        )

        val event = createAdmitEvent()
        val outcome = parityEngine.admitSession(event)

        assertTrue("Outcome must be Success", outcome is SessionTransitionOutcome.Success)
        val success = outcome as SessionTransitionOutcome.Success
        val session = success.session

        val expectedSessionId = "session_42_occ_42_12345_engine-parity-uuid"
        assertEquals(expectedSessionId, session.sessionId)
        assertEquals("admission_42_occ_42_12345", session.admissionKey)
        assertEquals(AlarmSessionState.FIRING.name, session.sessionState)
        assertEquals(1L, session.creationSequence) // Sequence allocated by DAO
        assertEquals(1L, session.sessionVersion)
        assertEquals(PostCommitIntent.None, success.postCommitIntent)
    }

    // F12: existing duplicate classification
    @Test
    fun F12_existingDuplicateClassification() = runBlocking {
        val event = createAdmitEvent()

        val firstOutcome = engine.admitSession(event)
        assertTrue("First admission must succeed", firstOutcome is SessionTransitionOutcome.Success)

        val secondOutcome = engine.admitSession(event)
        assertTrue("Second admission must be DuplicateAdmissionRejected", secondOutcome is SessionTransitionOutcome.DuplicateAdmissionRejected)
        val duplicate = secondOutcome as SessionTransitionOutcome.DuplicateAdmissionRejected
        assertEquals("admission_42_occ_42_12345", duplicate.admissionKey)
    }

    // F13: deadline overflow handling
    @Test(expected = ArithmeticException::class)
    fun F13_deadlineOverflowHandling() {
        val factory = NormalAlarmAdmissionFactory()
        val event = createAdmitEvent(nowEpochMs = Long.MAX_VALUE - 1000L)
        factory.prepareAdmission(event)
    }

    // F14: no Room/OS side effects in factory
    @Test
    fun F14_noRoomOrOsSideEffectsInFactory() = runBlocking {
        val factory = NormalAlarmAdmissionFactory()
        val event = createAdmitEvent()

        // Calling factory must not touch DB
        val prepared = factory.prepareAdmission(event)
        assertNotNull(prepared)

        val dbSessions = repository.getActiveSessions()
        assertTrue("Factory invocation must not insert any session into DB", dbSessions.isEmpty())

        val tombstone = repository.getTombstoneByAdmissionKey(prepared.tombstone.admissionKey)
        assertNull("Factory invocation must not insert any tombstone into DB", tombstone)
    }
}
