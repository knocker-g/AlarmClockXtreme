package com.sysadmindoc.alarmclock.data.backup

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.squareup.moshi.Moshi
import com.sysadmindoc.alarmclock.data.local.AlarmDatabase
import com.sysadmindoc.alarmclock.data.local.entity.AlarmGroup
import com.sysadmindoc.alarmclock.data.local.entity.DurableOperationIntent
import com.sysadmindoc.alarmclock.data.local.entity.IssueSlot
import com.sysadmindoc.alarmclock.data.local.entity.IssuedOccurrence
import com.sysadmindoc.alarmclock.data.local.entity.ScheduleAuthority
import com.sysadmindoc.alarmclock.data.local.entity.ScheduleAuthorityMeta
import com.sysadmindoc.alarmclock.data.model.Alarm
import com.sysadmindoc.alarmclock.data.preferences.AppSettings
import com.sysadmindoc.alarmclock.data.preferences.PreferencesManager
import com.sysadmindoc.alarmclock.domain.AlarmScheduler
import com.sysadmindoc.alarmclock.domain.ScheduleAuthorityBootstrapAdapter
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.spyk
import io.mockk.unmockkAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
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
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.DayOfWeek
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class RestoreJournalCoordinatorTest {
    private lateinit var context: Context
    private lateinit var database: AlarmDatabase
    private lateinit var preferencesManager: PreferencesManager
    private lateinit var alarmScheduler: AlarmScheduler
    private lateinit var coordinator: RestoreJournalCoordinator
    private lateinit var journalFile: File

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        database = Room.inMemoryDatabaseBuilder(context, AlarmDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        runBlocking {
            database.scheduleAuthorityDao().insertMeta(
                ScheduleAuthorityMeta(
                    id = 1,
                    restoreEpoch = 100L,
                    bootstrapState = "BOOTSTRAPPED"
                )
            )
        }
        preferencesManager = mockk(relaxed = true)
        alarmScheduler = mockk(relaxed = true)
        org.robolectric.shadows.ShadowAlarmManager.setCanScheduleExactAlarms(true)
        coEvery { alarmScheduler.rescheduleAllInBatches(any(), any()) } returns com.sysadmindoc.alarmclock.domain.ScheduleOutcome.BatchResult(0, 0, 0, 0, emptyMap())
        coEvery {
            alarmScheduler.cancelScheduledEntriesSuspending(any(), any(), any(), any(), any(), any(), any())
        } returns true

        journalFile = File(context.filesDir, RestoreJournalCoordinator.JOURNAL_FILENAME)
        if (journalFile.exists()) journalFile.delete()
        val bakFile = File(context.filesDir, "${RestoreJournalCoordinator.JOURNAL_FILENAME}.bak")
        if (bakFile.exists()) bakFile.delete()

        coordinator = RestoreJournalCoordinator(
            context = context,
            database = database,
            preferencesManager = preferencesManager,
            alarmScheduler = alarmScheduler,
            moshi = Moshi.Builder().build()
        )
    }

    @After
    fun tearDown() {
        database.close()
        if (journalFile.exists()) journalFile.delete()
        val bakFile = File(context.filesDir, "${RestoreJournalCoordinator.JOURNAL_FILENAME}.bak")
        if (bakFile.exists()) bakFile.delete()
        unmockkAll()
    }

    @Test
    fun prepareTransactionCreatesDurableJournal() = runTest {
        coordinator.prepareTransaction(AppSettings(), emptyList(), emptyList())
        assertTrue("Journal file must exist", journalFile.exists())
        val json = org.json.JSONObject(journalFile.readText())
        assertEquals(
            "prepareTransaction must write CURRENT_FORMAT_VERSION",
            RestoreJournalCoordinator.CURRENT_FORMAT_VERSION,
            json.getInt("formatVersion")
        )
    }

    @Test
    fun losslessFullObjectRoundTripCoverage() = runTest {
        val nonDefaultAlarm = Alarm(
            id = 42L,
            hour = 23,
            minute = 45,
            label = "Non-Default Alarm",
            isEnabled = true,
            repeatDays = setOf(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY),
            ringtoneUri = "content://media/internal/audio/media/123",
            vibrationEnabled = false,
            vibrationIntensity = 1,
            volume = 80,
            overrideSystemVolume = false,
            gradualVolumeSeconds = 30,
            snoozeDurationMinutes = 5,
            maxSnoozeCount = 2,
            showOnLockScreen = false,
            challengeType = "MATH",
            group = "MorningGroup",
            flashWake = true,
            vibrationPattern = "sos",
            ttsEnabled = true,
            walkStepsRequired = 50,
            wakeConfirmEnabled = true,
            wakeConfirmDelayMinutes = 15,
            smartAlarmEnabled = true,
            smartAlarmWindowMinutes = 20,
            skipOnHolidays = true,
            nfcTagId = "nfc_123",
            barcodeValue = "bar_456"
        )
        val nonDefaultGroup = AlarmGroup(name = "MorningGroup")
        val nonDefaultSettings = AppSettings(
            is24HourFormat = true,
            defaultSnoozeDuration = 12,
            temperatureUnit = "celsius",
            locationName = "Tokyo, Japan",
            holidayAutoSkipEnabled = true
        )

        coordinator.prepareTransaction(nonDefaultSettings, listOf(nonDefaultAlarm), listOf(nonDefaultGroup))
        coordinator.checkAndRecover()

        val restoredAlarms = database.alarmDao().getAll()
        assertEquals(1, restoredAlarms.size)
        val restored = restoredAlarms[0]
        assertEquals(nonDefaultAlarm.id, restored.id)
        assertEquals(nonDefaultAlarm.hour, restored.hour)
        assertEquals(nonDefaultAlarm.minute, restored.minute)
        assertEquals(nonDefaultAlarm.label, restored.label)
        assertEquals(nonDefaultAlarm.repeatDays, restored.repeatDays)
        assertEquals(nonDefaultAlarm.challengeType, restored.challengeType)
        assertEquals(nonDefaultAlarm.walkStepsRequired, restored.walkStepsRequired)

        val restoredGroups = database.alarmGroupDao().getAll()
        assertEquals(1, restoredGroups.size)
        assertEquals("MorningGroup", restoredGroups[0].name)
    }

    @Test
    fun tamperedPhaseWithoutChecksumUpdateFailsClosedAndRetainsJournal() = runTest {
        coordinator.prepareTransaction(AppSettings(), emptyList(), emptyList())
        val content = journalFile.readText()
        // Tamper PREPARED to COMMITTED without recomputing checksum
        journalFile.writeText(content.replace("PREPARED", "COMMITTED"))

        val result = runCatching { coordinator.checkAndRecover() }
        assertTrue("Checksum mismatch must fail closed", result.isFailure)
        val ex = result.exceptionOrNull()
        assertTrue("Exception must be SecurityException", ex is SecurityException)
        assertTrue("Message must mention checksum: ${ex?.message}", ex?.message?.contains("checksum", ignoreCase = true) == true)
        assertTrue("Journal must be retained on integrity failure", journalFile.exists())
    }

    @Test
    fun markCommittedWithTamperedPreparedFailsClosed() = runTest {
        coordinator.prepareTransaction(AppSettings(), emptyList(), emptyList())
        val content = journalFile.readText()
        // Tamper payload inside journal without updating checksum
        journalFile.writeText(content.replace("snapshotJson", "tamperedKey"))

        val result = runCatching { coordinator.markCommitted() }
        assertTrue("markCommitted must reject tampered PREPARED journal", result.isFailure)
        val ex = result.exceptionOrNull()
        assertTrue("Exception must be SecurityException", ex is SecurityException)
        assertTrue("Message must mention missing or tampered snapshotJson/checksum", ex?.message?.contains("Missing snapshotJson", ignoreCase = true) == true || ex?.message?.contains("checksum", ignoreCase = true) == true)
        assertTrue("Journal must be retained", journalFile.exists())
    }

    @Test
    fun markCommittedWithNonPreparedPhaseFails() = runTest {
        coordinator.prepareTransaction(AppSettings(), emptyList(), emptyList())
        coordinator.markCommitted() // Now it is COMMITTED

        val result = runCatching { coordinator.markCommitted() } // Try committing again from COMMITTED phase
        assertTrue("markCommitted must reject non-PREPARED phase", result.isFailure)
        val ex = result.exceptionOrNull()
        assertTrue("Exception must be SecurityException", ex is SecurityException)
        assertTrue("Message must mention invalid phase: ${ex?.message}", ex?.message?.contains("invalid phase", ignoreCase = true) == true || ex?.message?.contains("PREPARED", ignoreCase = true) == true)
    }

    @Test
    fun markCommittedWithUnsupportedVersionFailsClosed() = runTest {
        val unsupportedVersion = RestoreJournalCoordinator.CURRENT_FORMAT_VERSION + 1
        journalFile.writeText("""{"formatVersion": $unsupportedVersion, "phase": "PREPARED", "snapshotJson": "{}", "checksum": "abc"}""")
        val result = runCatching { coordinator.markCommitted() }
        assertTrue("markCommitted must reject unsupported formatVersion", result.isFailure)
        val ex = result.exceptionOrNull()
        assertTrue("Exception must be SecurityException", ex is SecurityException)
        assertTrue("Message must mention formatVersion: ${ex?.message}", ex?.message?.contains("formatVersion", ignoreCase = true) == true || ex?.message?.contains("version", ignoreCase = true) == true)
    }

    @Test
    fun validPreparedToCommittedSucceeds() = runTest {
        coordinator.prepareTransaction(AppSettings(), emptyList(), emptyList())
        coordinator.markCommitted()

        coordinator.checkAndRecover()
        assertFalse("Journal must be cleaned up after committed recovery", journalFile.exists())
    }

    @Test
    fun preparedReconcileFailureRetainsJournalAndSucceedsOnRetry() = runTest {
        coEvery { alarmScheduler.rescheduleAllInBatches(any(), any()) } throws RuntimeException("Transient error")

        coordinator.prepareTransaction(AppSettings(), emptyList(), emptyList())
        runCatching { coordinator.checkAndRecover() }
        assertTrue("Journal must remain when reconcile fails", journalFile.exists())

        coEvery { alarmScheduler.rescheduleAllInBatches(any(), any()) } returns com.sysadmindoc.alarmclock.domain.ScheduleOutcome.BatchResult(0, 0, 0, 0, emptyMap())
        coordinator.checkAndRecover()
        assertFalse("Journal must be cleaned up on successful retry", journalFile.exists())
    }

    @Test
    fun committedReconcileFailureRetainsJournalAndSucceedsOnRetry() = runTest {
        coEvery { alarmScheduler.rescheduleAllInBatches(any(), any()) } throws RuntimeException("Transient error")

        coordinator.prepareTransaction(AppSettings(), emptyList(), emptyList())
        coordinator.markCommitted()
        runCatching { coordinator.checkAndRecover() }
        assertTrue("Journal must remain when committed reconcile fails", journalFile.exists())

        coEvery { alarmScheduler.rescheduleAllInBatches(any(), any()) } returns com.sysadmindoc.alarmclock.domain.ScheduleOutcome.BatchResult(0, 0, 0, 0, emptyMap())
        coordinator.checkAndRecover()
        assertFalse("Journal must be cleaned up on successful retry", journalFile.exists())
    }

    @Test
    fun corruptUnsupportedUnknownFailuresRetainJournal() = runTest {
        val currentVersion = RestoreJournalCoordinator.CURRENT_FORMAT_VERSION

        // Corrupt json
        journalFile.writeText("{ malformed json }")
        val corruptResult = runCatching { coordinator.checkAndRecover() }
        assertTrue("Corrupt JSON must fail", corruptResult.isFailure)
        assertTrue(journalFile.exists())

        // Unsupported version (using currentVersion + 1)
        val unsupportedVersion = currentVersion + 1
        journalFile.writeText("""{"formatVersion": $unsupportedVersion, "phase": "PREPARED", "snapshotJson": "{}", "checksum": "abc"}""")
        val unsupportedResult = runCatching { coordinator.checkAndRecover() }
        assertTrue("Unsupported version must fail", unsupportedResult.isFailure)
        assertTrue("Exception must be SecurityException for unsupported version", unsupportedResult.exceptionOrNull() is SecurityException)
        assertTrue("Message must mention format version: ${unsupportedResult.exceptionOrNull()?.message}", unsupportedResult.exceptionOrNull()?.message?.contains("version", ignoreCase = true) == true || unsupportedResult.exceptionOrNull()?.message?.contains("format", ignoreCase = true) == true)
        assertTrue(journalFile.exists())

        // Unknown phase with CURRENT format version
        journalFile.writeText("""{"formatVersion": $currentVersion, "phase": "UNKNOWN_PHASE", "snapshotJson": "{}", "checksum": "abc"}""")
        val unknownPhaseResult = runCatching { coordinator.checkAndRecover() }
        assertTrue("Unknown phase must fail", unknownPhaseResult.isFailure)
        assertTrue("Exception must be SecurityException for unknown phase", unknownPhaseResult.exceptionOrNull() is SecurityException)
        assertTrue("Message must mention unknown phase: ${unknownPhaseResult.exceptionOrNull()?.message}", unknownPhaseResult.exceptionOrNull()?.message?.contains("phase", ignoreCase = true) == true)
        assertTrue(journalFile.exists())
    }

    @Test
    fun standaloneOrphanGroupsSurviveRollbackExactly() = runTest {
        val orphanGroup1 = AlarmGroup(name = "Gym")
        val orphanGroup2 = AlarmGroup(name = "Medication")
        val settings = AppSettings()

        coordinator.prepareTransaction(settings, emptyList(), listOf(orphanGroup1, orphanGroup2))

        // Mutate DB to simulate partial/failed restore
        database.alarmGroupDao().insert(AlarmGroup(name = "TemporaryNewGroup"))

        // Perform PREPARED recovery (rollback)
        coordinator.checkAndRecover()

        val restoredGroups = database.alarmGroupDao().getAll()
        assertEquals(2, restoredGroups.size)
        assertTrue(restoredGroups.any { it.name == "Gym" })
        assertTrue(restoredGroups.any { it.name == "Medication" })
        assertFalse(restoredGroups.any { it.name == "TemporaryNewGroup" })
    }

    @Test
    fun c2b1_red01_preparedRollbackRestoresOldAlarmsRebuildsNormalAuthorityAndClearsStaleSlots() = runTest {
        val oldNormalAlarm = Alarm(
            id = 42L,
            hour = 6,
            minute = 45,
            label = "Old Normal",
            isEnabled = true,
            repeatDays = setOf(DayOfWeek.MONDAY, DayOfWeek.THURSDAY),
            scheduleKind = Alarm.SCHEDULE_KIND_REPEATING,
            generation = 4,
            revision = 3,
            nextTriggerTime = 1_800_000_000_000L
        )
        val oldGroup = AlarmGroup(name = "MorningGroup")

        coordinator.prepareTransaction(AppSettings(), listOf(oldNormalAlarm), listOf(oldGroup))

        val authorityDao = database.scheduleAuthorityDao()
        val issuedDao = database.issuedOccurrenceDao()

        // Simulate partial/stale DB state before crash
        database.alarmDao().insert(Alarm(id = 999L, hour = 9, minute = 0, label = "Transient New"))
        authorityDao.insertAuthority(
            ScheduleAuthority(
                alarmId = 42L,
                instanceToken = "stale-instance-42",
                definitionRevision = 999L,
                scheduleRevision = 99L,
                lifecycle = "DISABLED",
                desiredOperation = "CANCEL",
                desiredTriggerMillis = null,
                reconcileState = "SETTLED",
                restoreEpoch = 100L,
                actionKey = "stale-action"
            )
        )
        authorityDao.insertAuthority(
            ScheduleAuthority(
                alarmId = 999L,
                instanceToken = "transient-instance-999",
                definitionRevision = 1L,
                scheduleRevision = 1L,
                lifecycle = "LIVE",
                desiredOperation = "SET",
                desiredTriggerMillis = 1_800_000_100_000L,
                reconcileState = "SETTLED",
                restoreEpoch = 100L,
                actionKey = null
            )
        )
        issuedDao.insertSlotInitial(IssueSlot(alarmId = 42L, currentIssueToken = "stale-issue-42", generationId = 99L))
        issuedDao.insertSlotInitial(IssueSlot(alarmId = 999L, currentIssueToken = "transient-issue-999", generationId = 5L))

        val outcome = coordinator.checkAndRecover()
        assertEquals(RecoveryOutcome.RESTORED_OLD, outcome)
        assertFalse("B2-C must delete PHASE_PREPARED journal once transient Normal 999L OS cleanup and rollback succeed", journalFile.exists())
        coVerify(exactly = 1) {
            alarmScheduler.cancelScheduledEntriesSuspending(
                alarmId = 999L,
                includeFollowUpWorkers = true,
                expectedRestoreEpoch = 101L
            )
        }

        val alarms = database.alarmDao().getAll()
        assertEquals(1, alarms.size)
        assertEquals(42L, alarms[0].id)
        assertEquals("Old Normal", alarms[0].label)

        assertNull("Stale transient authority for 999L must be removed", authorityDao.getAuthorityByAlarmId(999L))
        assertNull("Stale transient slot for 999L must be removed", issuedDao.getSlot(999L))

        val rebuiltAuth = authorityDao.getAuthorityByAlarmId(42L)
        assertNotNull("ScheduleAuthority must be rebuilt for OLD Normal alarm 42L", rebuiltAuth)
        assertEquals(101L, rebuiltAuth!!.restoreEpoch)
        assertEquals("RECONCILING", rebuiltAuth.reconcileState)
        assertNotEquals("stale-instance-42", rebuiltAuth.instanceToken)
        assertTrue(rebuiltAuth.instanceToken.isNotBlank())
        assertEquals(ScheduleAuthorityBootstrapAdapter.computeDefinitionRevision(oldNormalAlarm), rebuiltAuth.definitionRevision)
        assertEquals(3L, rebuiltAuth.scheduleRevision)
        assertEquals("LIVE", rebuiltAuth.lifecycle)
        assertEquals("SET", rebuiltAuth.desiredOperation)
        assertEquals(1_800_000_000_000L, rebuiltAuth.desiredTriggerMillis)
        assertNull(rebuiltAuth.actionKey)

        val rebuiltSlot = issuedDao.getSlot(42L)
        assertNotNull("IssueSlot must be rebuilt for OLD Normal alarm 42L", rebuiltSlot)
        assertEquals(4L, rebuiltSlot!!.generationId)
        assertNull("IssueSlot currentIssueToken must be cleared on rollback", rebuiltSlot.currentIssueToken)
    }

    @Test
    fun c2b1_red02_preparedRollbackBumpsRestoreEpochAndFencesStaleCas() = runTest {
        val oldAlarm = Alarm(
            id = 21L,
            hour = 7,
            minute = 30,
            label = "Epoch Test",
            isEnabled = true,
            generation = 2,
            revision = 1,
            nextTriggerTime = 1_800_000_000_000L
        )
        coordinator.prepareTransaction(AppSettings(), listOf(oldAlarm), emptyList())

        val outcome1 = coordinator.checkAndRecover()
        assertEquals(RecoveryOutcome.RESTORED_OLD, outcome1)

        val authorityDao = database.scheduleAuthorityDao()
        assertEquals(101L, authorityDao.getMetaById(1)?.restoreEpoch)
        val authAfterFirst = authorityDao.getAuthorityByAlarmId(21L)!!
        assertEquals(101L, authAfterFirst.restoreEpoch)

        // Stale CAS at old restoreEpoch=100 must fail (0 rows)
        val staleCas = authorityDao.casClaimAuthority(
            alarmId = 21L,
            instanceToken = authAfterFirst.instanceToken,
            restoreEpoch = 100L,
            expectedRevision = authAfterFirst.scheduleRevision,
            expectedLifecycle = authAfterFirst.lifecycle,
            desiredOperation = "CANCEL",
            desiredTriggerMillis = null,
            actionKey = "stale-epoch-claim"
        )
        assertEquals(0, staleCas)

        // Valid CAS at bumped restoreEpoch=101 succeeds (1 row)
        val validCas = authorityDao.casClaimAuthority(
            alarmId = 21L,
            instanceToken = authAfterFirst.instanceToken,
            restoreEpoch = 101L,
            expectedRevision = authAfterFirst.scheduleRevision,
            expectedLifecycle = authAfterFirst.lifecycle,
            desiredOperation = "CANCEL",
            desiredTriggerMillis = null,
            actionKey = "valid-epoch-claim"
        )
        assertEquals(1, validCas)

        // Subsequent PREPARED rollback bumps restoreEpoch again (101 -> 102) and mints fresh instanceToken
        coordinator.prepareTransaction(AppSettings(), listOf(oldAlarm), emptyList())
        val outcome2 = coordinator.checkAndRecover()
        assertEquals(RecoveryOutcome.RESTORED_OLD, outcome2)
        assertEquals(102L, authorityDao.getMetaById(1)?.restoreEpoch)
        val authAfterSecond = authorityDao.getAuthorityByAlarmId(21L)!!
        assertEquals(102L, authAfterSecond.restoreEpoch)
        assertNotEquals(authAfterFirst.instanceToken, authAfterSecond.instanceToken)
    }

    @Test
    fun c2b1_red03_preparedRollbackFailsClosedWhenMetaMissingOrInvalidWithNoPartialWrite() = runTest {
        val oldAlarm = Alarm(id = 10L, hour = 6, minute = 0, label = "Old Truth")
        val transientAlarm = Alarm(id = 888L, hour = 9, minute = 0, label = "Pre-Rollback State")
        val authorityDao = database.scheduleAuthorityDao()
        val issuedDao = database.issuedOccurrenceDao()

        val staleAuth = ScheduleAuthority(
            alarmId = 888L,
            instanceToken = "pre-rollback-inst",
            definitionRevision = 1L,
            scheduleRevision = 1L,
            lifecycle = "LIVE",
            desiredOperation = "SET",
            desiredTriggerMillis = 1_800_000_000_000L,
            reconcileState = "SETTLED",
            restoreEpoch = 100L,
            actionKey = null
        )
        val staleSlot = IssueSlot(alarmId = 888L, currentIssueToken = "pre-rollback-token", generationId = 3L)

        // Case A: Meta missing
        database.alarmDao().insert(transientAlarm)
        authorityDao.insertAuthority(staleAuth)
        issuedDao.insertSlotInitial(staleSlot)
        database.compileStatement("DELETE FROM schedule_authority_meta").executeUpdateDelete()

        coordinator.prepareTransaction(AppSettings(is24HourFormat = true), listOf(oldAlarm), listOf(AlarmGroup(name = "OldGroup")))
        val missingMetaResult = runCatching { coordinator.checkAndRecover() }
        assertTrue("Missing ScheduleAuthorityMeta must fail closed", missingMetaResult.isFailure)
        assertTrue(missingMetaResult.exceptionOrNull() is IllegalStateException)
        assertTrue("Journal must be retained on missing meta", journalFile.exists())
        assertEquals(listOf(888L), database.alarmDao().getAll().map { it.id })
        assertEquals(staleAuth, authorityDao.getAuthorityByAlarmId(888L))
        assertEquals(staleSlot, issuedDao.getSlot(888L))
        coVerify(exactly = 0) { preferencesManager.update(any()) }

        // Case B: Non-positive restoreEpoch (0L)
        authorityDao.insertMeta(ScheduleAuthorityMeta(id = 1, restoreEpoch = 0L, bootstrapState = "BOOTSTRAPPED"))
        val zeroEpochResult = runCatching { coordinator.checkAndRecover() }
        assertTrue("Non-positive restoreEpoch=0 must fail closed", zeroEpochResult.isFailure)
        assertTrue(zeroEpochResult.exceptionOrNull() is IllegalStateException)
        assertTrue(journalFile.exists())
        assertEquals(0L, authorityDao.getMetaById(1)?.restoreEpoch)
        assertEquals(listOf(888L), database.alarmDao().getAll().map { it.id })
        coVerify(exactly = 0) { preferencesManager.update(any()) }

        // Case C: Overflow restoreEpoch (Long.MAX_VALUE)
        authorityDao.insertMeta(ScheduleAuthorityMeta(id = 1, restoreEpoch = Long.MAX_VALUE, bootstrapState = "BOOTSTRAPPED"))
        val maxEpochResult = runCatching { coordinator.checkAndRecover() }
        assertTrue("Overflow restoreEpoch=Long.MAX_VALUE must fail closed", maxEpochResult.isFailure)
        assertTrue(maxEpochResult.exceptionOrNull() is IllegalStateException)
        assertTrue(journalFile.exists())
        assertEquals(Long.MAX_VALUE, authorityDao.getMetaById(1)?.restoreEpoch)
        assertEquals(listOf(888L), database.alarmDao().getAll().map { it.id })
        coVerify(exactly = 0) { preferencesManager.update(any()) }
    }

    @Test
    fun c2b1_red04_preparedRollbackMaintainsV1JournalCompatibilityAndRebuildsAuthority() = runTest {
        // Legacy formatVersion=1 journal JSON without manualOverrides and without newer optional fields
        val snapshotJson = """
            {
              "settings": {
                "is24HourFormat": true
              },
              "alarms": [
                {
                  "id": 55,
                  "hour": 6,
                  "minute": 15,
                  "label": "Disabled V1 Alarm",
                  "isEnabled": false,
                  "repeatDays": ["MONDAY"],
                  "generation": 2,
                  "revision": 5
                },
                {
                  "id": 56,
                  "hour": 7,
                  "minute": 45,
                  "label": "Enabled V1 Alarm",
                  "isEnabled": true,
                  "repeatDays": [],
                  "nextTriggerTime": 1800000050000
                }
              ],
              "groups": []
            }
        """.trimIndent()
        writeRawV1Journal(RestoreJournalCoordinator.PHASE_PREPARED, snapshotJson)

        val outcome = coordinator.checkAndRecover()
        assertEquals(RecoveryOutcome.RESTORED_OLD, outcome)
        assertFalse(journalFile.exists())

        val authorityDao = database.scheduleAuthorityDao()
        val issuedDao = database.issuedOccurrenceDao()
        assertEquals(101L, authorityDao.getMetaById(1)?.restoreEpoch)

        val auth55 = authorityDao.getAuthorityByAlarmId(55L)!!
        assertEquals("DISABLED", auth55.lifecycle)
        assertEquals("CANCEL", auth55.desiredOperation)
        assertNull(auth55.desiredTriggerMillis)
        assertEquals("RECONCILING", auth55.reconcileState)
        assertEquals(5L, auth55.scheduleRevision)
        assertEquals(101L, auth55.restoreEpoch)
        val slot55 = issuedDao.getSlot(55L)!!
        assertEquals(2L, slot55.generationId)
        assertNull(slot55.currentIssueToken)

        val auth56 = authorityDao.getAuthorityByAlarmId(56L)!!
        assertEquals("LIVE", auth56.lifecycle)
        assertEquals("SET", auth56.desiredOperation)
        assertEquals(1_800_000_050_000L, auth56.desiredTriggerMillis)
        assertEquals("RECONCILING", auth56.reconcileState)
        val slot56 = issuedDao.getSlot(56L)!!
        assertEquals(1L, slot56.generationId)
        assertNull(slot56.currentIssueToken)
    }

    @Test
    fun c2b1_red05_preparedRollbackPreservesInFlightRev2UnknownIntentsPendingLegacyAndIssuedOccurrences() = runTest {
        val oldAlarm = Alarm(id = 42L, hour = 7, minute = 0, label = "Old 42", isEnabled = true, nextTriggerTime = 1_800_000_000_000L)
        coordinator.prepareTransaction(AppSettings(), listOf(oldAlarm), emptyList())

        val intentDao = database.durableOperationIntentDao()
        val issuedDao = database.issuedOccurrenceDao()

        val inFlightRev2Intent = DurableOperationIntent(
            operationToken = "rev2-inflight-tok",
            alarmId = 42L,
            operationEpoch = 5L,
            incarnationId = "inc-1",
            operationKind = "CANCEL",
            expectedOwnerToken = "old-owner-42",
            expectedScheduleRevision = 2L,
            expectedRestoreEpoch = 100L,
            phase = DurableOperationIntent.PHASE_IN_FLIGHT,
            intentRevision = 2L,
            originalFireId = "fire-42",
            originalScheduledAt = 1_800_000_000_000L
        )
        val pendingLegacyIntent = DurableOperationIntent(
            operationToken = "legacy-pending-tok",
            alarmId = 42L,
            operationEpoch = 6L,
            incarnationId = "inc-1",
            operationKind = "CANCEL",
            expectedOwnerToken = null,
            expectedScheduleRevision = null,
            expectedRestoreEpoch = null,
            phase = DurableOperationIntent.PHASE_PENDING,
            intentRevision = 1L
        )
        intentDao.insertValidatedIntent(inFlightRev2Intent)
        intentDao.insertValidatedIntent(pendingLegacyIntent)

        val occIssued = IssuedOccurrence(
            issueToken = "occ-issued-42",
            alarmId = 42L,
            scheduledAt = 1_800_000_000_000L,
            fireId = "fire-42",
            generationId = 2L,
            status = IssuedOccurrence.STATUS_ISSUED,
            activatedAt = 1_799_999_000_000L,
            authorityInstanceToken = "old-owner-42",
            restoreEpoch = 100L,
            scheduleRevision = 2L,
            definitionRevision = 11L
        )
        val occPrepared = IssuedOccurrence(
            issueToken = "occ-prepared-99",
            alarmId = 99L,
            scheduledAt = 1_800_000_060_000L,
            fireId = "fire-99",
            generationId = 3L,
            status = IssuedOccurrence.STATUS_PREPARED
        )
        issuedDao.insertOccurrence(occIssued)
        issuedDao.insertOccurrence(occPrepared)

        val outcome = coordinator.checkAndRecover()
        assertEquals(RecoveryOutcome.RESTORED_OLD, outcome)

        // All durable intents (Rev2 IN_FLIGHT and Legacy PENDING) and IssuedOccurrences must remain untouched
        assertEquals(inFlightRev2Intent, intentDao.getIntentByToken("rev2-inflight-tok"))
        assertEquals(pendingLegacyIntent, intentDao.getIntentByToken("legacy-pending-tok"))
        assertEquals(occIssued, issuedDao.getOccurrence("occ-issued-42"))
        assertEquals(occPrepared, issuedDao.getOccurrence("occ-prepared-99"))
    }

    @Test
    fun c2b1_preparedRollbackIsolatesQuickAlarmWithoutCreatingNormalAuthorityOrSlot() = runTest {
        val normalAlarm = Alarm(
            id = 60L,
            hour = 7,
            minute = 0,
            label = "Normal 60",
            isEnabled = true,
            kind = Alarm.KIND_NORMAL,
            nextTriggerTime = 1_800_000_000_000L
        )
        val quickAlarm = Alarm(
            id = 61L,
            hour = 12,
            minute = 30,
            label = "Quick 61",
            isEnabled = true,
            kind = Alarm.KIND_QUICK,
            quickTriggerEpochMs = 1_800_000_060_000L
        )
        coordinator.prepareTransaction(AppSettings(), listOf(normalAlarm, quickAlarm), emptyList())

        // Pre-seed stale authority and slot even on quick alarm ID to ensure cleanup
        database.scheduleAuthorityDao().insertAuthority(
            ScheduleAuthority(
                alarmId = 61L,
                instanceToken = "stale-quick-auth",
                definitionRevision = 1L,
                scheduleRevision = 1L,
                lifecycle = "LIVE",
                desiredOperation = "SET",
                desiredTriggerMillis = 1_800_000_060_000L,
                reconcileState = "SETTLED",
                restoreEpoch = 100L,
                actionKey = null
            )
        )
        database.issuedOccurrenceDao().insertSlotInitial(
            IssueSlot(alarmId = 61L, currentIssueToken = "stale-quick-slot", generationId = 2L)
        )

        val outcome = coordinator.checkAndRecover()
        assertEquals(RecoveryOutcome.RESTORED_OLD, outcome)

        val restoredAlarms = database.alarmDao().getAll().associateBy { it.id }
        assertEquals(2, restoredAlarms.size)
        assertEquals(Alarm.KIND_NORMAL, restoredAlarms[60L]?.kind)
        assertEquals(Alarm.KIND_QUICK, restoredAlarms[61L]?.kind)

        assertNotNull("Normal alarm must have ScheduleAuthority", database.scheduleAuthorityDao().getAuthorityByAlarmId(60L))
        assertNotNull("Normal alarm must have IssueSlot", database.issuedOccurrenceDao().getSlot(60L))
        assertNull("Quick alarm must NOT have ScheduleAuthority", database.scheduleAuthorityDao().getAuthorityByAlarmId(61L))
        assertNull("Quick alarm must NOT have IssueSlot", database.issuedOccurrenceDao().getSlot(61L))
    }

    @Test
    fun c2b1_preparedRollbackSqlFailureRollsBackMetaAlarmsAuthorityAndSlotsAtomically() = runTest {
        val preRollbackAlarm = Alarm(id = 70L, hour = 8, minute = 0, label = "Pre-Rollback Transient")
        val oldSnapshotAlarm = Alarm(id = 71L, hour = 6, minute = 30, label = "Old Snapshot Alarm")
        val authorityDao = database.scheduleAuthorityDao()
        val issuedDao = database.issuedOccurrenceDao()

        val preRollbackAuth = ScheduleAuthority(
            alarmId = 70L,
            instanceToken = "pre-auth-70",
            definitionRevision = 1L,
            scheduleRevision = 1L,
            lifecycle = "LIVE",
            desiredOperation = "SET",
            desiredTriggerMillis = 1_800_000_000_000L,
            reconcileState = "SETTLED",
            restoreEpoch = 100L,
            actionKey = null
        )
        val preRollbackSlot = IssueSlot(alarmId = 70L, currentIssueToken = "pre-slot-70", generationId = 2L)

        database.alarmDao().insert(preRollbackAlarm)
        database.alarmGroupDao().insert(AlarmGroup(name = "PreGroup"))
        authorityDao.insertAuthority(preRollbackAuth)
        issuedDao.insertSlotInitial(preRollbackSlot)

        coordinator.prepareTransaction(AppSettings(is24HourFormat = true), listOf(oldSnapshotAlarm), listOf(AlarmGroup(name = "OldGroup")))

        val writableDb = database.openHelper.writableDatabase
        writableDb.execSQL(
            """
            CREATE TRIGGER test_fail_authority_insert_on_rollback
            BEFORE INSERT ON schedule_authority
            BEGIN
                SELECT RAISE(ABORT, 'simulated authority insert failure during rollback');
            END
            """.trimIndent()
        )

        val result = runCatching { coordinator.checkAndRecover() }
        assertTrue("Rollback DB failure must propagate exception", result.isFailure)
        assertTrue("Journal must be retained on rollback failure", journalFile.exists())

        assertEquals(100L, authorityDao.getMetaById(1)?.restoreEpoch)
        assertEquals(listOf(70L), database.alarmDao().getAll().map { it.id })
        assertEquals(listOf("PreGroup"), database.alarmGroupDao().getAll().map { it.name })
        assertEquals(preRollbackAuth, authorityDao.getAuthorityByAlarmId(70L))
        assertEquals(preRollbackSlot, issuedDao.getSlot(70L))
        assertNull(authorityDao.getAuthorityByAlarmId(71L))
        assertNull(issuedDao.getSlot(71L))
        coVerify(exactly = 0) { preferencesManager.update(any()) }

        writableDb.execSQL("DROP TRIGGER IF EXISTS test_fail_authority_insert_on_rollback")
    }

    private fun writeRawV1Journal(phase: String, snapshotJson: String) {
        val formatVersion = RestoreJournalCoordinator.CURRENT_FORMAT_VERSION
        val payloadToHash = "$formatVersion:$phase:$snapshotJson"
        val hashBytes = MessageDigest.getInstance("SHA-256").digest(payloadToHash.toByteArray(StandardCharsets.UTF_8))
        val checksum = hashBytes.joinToString("") { "%02x".format(it) }

        val json = org.json.JSONObject().apply {
            put("formatVersion", formatVersion)
            put("transactionId", UUID.randomUUID().toString())
            put("phase", phase)
            put("createdAt", System.currentTimeMillis())
            put("snapshotJson", snapshotJson)
            put("checksum", checksum)
        }
        journalFile.writeText(json.toString())
    }

    @Test
    fun `CE-B01-V1-BAK - atomicFile bak recovers PHASE_PREPARED when main journal is missing after mid-startWrite crash`() = runTest {
        val oldAlarm = Alarm(id = 42L, hour = 6, minute = 45, label = "Old From Bak", isEnabled = true, nextTriggerTime = 1_800_000_000_000L)
        coordinator.prepareTransaction(AppSettings(), listOf(oldAlarm), emptyList())

        // Simulate crash during AtomicFile.startWrite() where main journal was moved/unlinked and .bak holds valid PREPARED
        val bakFile = File(context.filesDir, "${RestoreJournalCoordinator.JOURNAL_FILENAME}.bak")
        bakFile.writeText(journalFile.readText())
        assertTrue(journalFile.delete())
        assertFalse(journalFile.exists())
        assertTrue(bakFile.exists())

        val freshCoordinator = RestoreJournalCoordinator(
            context = context,
            database = database,
            preferencesManager = preferencesManager,
            alarmScheduler = alarmScheduler,
            moshi = Moshi.Builder().build()
        )
        val outcome = freshCoordinator.checkAndRecover()
        assertEquals("Must recover PHASE_PREPARED from .bak rather than returning NONE", RecoveryOutcome.RESTORED_OLD, outcome)
        assertEquals(listOf(42L), database.alarmDao().getAll().map { it.id })
        assertFalse(journalFile.exists())
        assertFalse(bakFile.exists())
    }

    @Test
    fun `CE-B01-BYPASS-AUTH - checkAndRecover passes 4-factor bypass and rejects leaked or forged recovery contexts`() = runTest {
        org.robolectric.shadows.ShadowAlarmManager.setCanScheduleExactAlarms(true)
        val now = System.currentTimeMillis()
        val futureTrigger = now + 600_000L
        val oldAlarm = Alarm(
            id = 42L,
            hour = 6,
            minute = 45,
            label = "Bypass Auth Alarm",
            isEnabled = true,
            nextTriggerTime = futureTrigger
        )
        database.alarmDao().insert(oldAlarm)

        val alarmRepo = com.sysadmindoc.alarmclock.data.repository.AlarmRepository(database, database.alarmDao(), database.alarmGroupDao())
        val realScheduler = AlarmScheduler(
            context = context,
            repository = alarmRepo,
            calculator = com.sysadmindoc.alarmclock.domain.NextAlarmCalculator(),
            preferencesManager = preferencesManager,
            holidayRepository = mockk(relaxed = true),
            alarmIncidentRepository = mockk(relaxed = true),
            weatherRepository = mockk(relaxed = true)
        )

        val coordinatorWithRealScheduler = RestoreJournalCoordinator(
            context = context,
            database = database,
            preferencesManager = preferencesManager,
            alarmScheduler = realScheduler,
            moshi = Moshi.Builder().build()
        )
        coordinatorWithRealScheduler.prepareTransaction(AppSettings(), listOf(oldAlarm), emptyList())

        // Spy on realScheduler solely to capture the in-flight PreparedRestoreRecoveryContext while delegating to real implementation
        var capturedBypassContext: PreparedRestoreRecoveryContext? = null
        val spyScheduler = spyk(realScheduler)
        coEvery { spyScheduler.rescheduleAllInBatches(any(), any()) } coAnswers {
            capturedBypassContext = kotlin.coroutines.coroutineContext[PreparedRestoreRecoveryContext]
            realScheduler.rescheduleAllInBatches(firstArg(), secondArg())
        }
        val capturingCoordinator = RestoreJournalCoordinator(
            context = context,
            database = database,
            preferencesManager = preferencesManager,
            alarmScheduler = spyScheduler,
            moshi = Moshi.Builder().build()
        )

        val outcome = capturingCoordinator.checkAndRecover()
        assertEquals(RecoveryOutcome.RESTORED_OLD, outcome)
        assertNotNull("checkAndRecover must attach PreparedRestoreRecoveryContext to rescheduleAllInBatches", capturedBypassContext)

        // Prepare a fresh PHASE_PREPARED journal on disk so Fence is active again
        coordinatorWithRealScheduler.prepareTransaction(AppSettings(), listOf(oldAlarm), emptyList())

        // 1. Stale/leaked PreparedRestoreRecoveryContext replayed after checkAndRecover exited MUST fail closed
        val leakedOutcome = kotlinx.coroutines.withContext(capturedBypassContext!!) {
            realScheduler.scheduleAt(oldAlarm, futureTrigger)
        }
        assertTrue(
            "Leaked recovery context MUST fail closed once checkAndRecover exits",
            leakedOutcome is com.sysadmindoc.alarmclock.domain.ScheduleOutcome.NoArm &&
                leakedOutcome.reason == "RESTORE_PREPARED_FENCE"
        )

        // 2. Forged wrong transactionId while journal is PHASE_PREPARED MUST fail closed
        val diskJson = org.json.JSONObject(journalFile.readText())
        val realTxId = diskJson.getString("transactionId")
        val canonicalPath = RestoreJournalCoordinator.canonicalJournalPath(context)
        val wrongTxCtx = PreparedRestoreRecoveryContext.createForRecovery(
            transactionId = "forged-$realTxId",
            expectedPhase = RestoreJournalCoordinator.PHASE_PREPARED,
            journalCanonicalPath = canonicalPath
        )
        val wrongTxOutcome = kotlinx.coroutines.withContext(wrongTxCtx) {
            realScheduler.scheduleAt(oldAlarm, futureTrigger)
        }
        assertTrue(
            "Forged transactionId bypass MUST fail closed",
            wrongTxOutcome is com.sysadmindoc.alarmclock.domain.ScheduleOutcome.NoArm &&
                wrongTxOutcome.reason == "RESTORE_PREPARED_FENCE"
        )

        // 3. Forged wrong expectedPhase (COMMITTED while disk is PREPARED) MUST fail closed
        val wrongPhaseCtx = PreparedRestoreRecoveryContext.createForRecovery(
            transactionId = realTxId,
            expectedPhase = RestoreJournalCoordinator.PHASE_COMMITTED,
            journalCanonicalPath = canonicalPath
        )
        val wrongPhaseOutcome = kotlinx.coroutines.withContext(wrongPhaseCtx) {
            realScheduler.scheduleAt(oldAlarm, futureTrigger)
        }
        assertTrue(
            "Forged phase bypass MUST fail closed",
            wrongPhaseOutcome is com.sysadmindoc.alarmclock.domain.ScheduleOutcome.NoArm &&
                wrongPhaseOutcome.reason == "RESTORE_PREPARED_FENCE"
        )
    }

    @Test
    fun `CE-B01-B1 - double crash restores transientNormalIds and transientQuickIds from fresh coordinator after alarmDao deleteAll`() = runTest {
        val oldAlarm = Alarm(id = 42L, hour = 6, minute = 0, label = "Old 42", isEnabled = true)
        database.alarmDao().insert(oldAlarm)

        coordinator.prepareTransaction(AppSettings(), listOf(oldAlarm), emptyList())
        coordinator.recordPreparedTransientTargets(normalIds = listOf(901L), quickIds = listOf(902L))

        coEvery { alarmScheduler.rescheduleAllInBatches(any(), any()) } throws RuntimeException("Simulated second crash during OS reschedule")
        val firstOutcome = coordinator.checkAndRecover()
        assertEquals(RecoveryOutcome.PENDING_RETRY, firstOutcome)
        assertTrue("Journal must remain on PENDING_RETRY", journalFile.exists())
        assertEquals(listOf(42L), database.alarmDao().getAll().map { it.id })

        // Simulate cold process restart with brand-new coordinator instance and zero in-memory state
        val freshCoordinator = RestoreJournalCoordinator(
            context = context,
            database = database,
            preferencesManager = preferencesManager,
            alarmScheduler = alarmScheduler,
            moshi = Moshi.Builder().build()
        )
        val (restoredNormalIds, restoredQuickIds) = freshCoordinator.readPreparedTransientTargets()
        assertEquals(listOf(901L), restoredNormalIds)
        assertEquals(listOf(902L), restoredQuickIds)
    }

    @Test
    fun `CE-B01-B2 - double crash cleans up transient Normal and Quick OS PIs on retry from AtomicFile bak`() = runBlocking {
        org.robolectric.shadows.ShadowAlarmManager.setCanScheduleExactAlarms(true)
        io.mockk.mockkStatic(androidx.work.WorkManager::class)
        val mockWm = mockk<androidx.work.WorkManager>(relaxed = true)
        io.mockk.every { androidx.work.WorkManager.getInstance(any<Context>()) } returns mockWm
        try {
            val now = System.currentTimeMillis()
            val futureOld = now + 600_000L
            val futureNewNormal = now + 900_000L
            val futureNewQuick = now + 1_200_000L
            val oldAlarm = Alarm(id = 42L, hour = 6, minute = 0, label = "Old 42", isEnabled = true, nextTriggerTime = futureOld)
            val newNormal = Alarm(id = 901L, hour = 8, minute = 0, label = "New Normal 901", isEnabled = true, kind = Alarm.KIND_NORMAL, nextTriggerTime = futureNewNormal)
            val newQuick = Alarm(id = 902L, hour = 9, minute = 0, label = "New Quick 902", isEnabled = true, kind = Alarm.KIND_QUICK, quickTriggerEpochMs = futureNewQuick)

            database.alarmDao().insert(oldAlarm)
            val alarmRepo = com.sysadmindoc.alarmclock.data.repository.AlarmRepository(database, database.alarmDao(), database.alarmGroupDao())
            val realScheduler = AlarmScheduler(
                context = context,
                repository = alarmRepo,
                calculator = com.sysadmindoc.alarmclock.domain.NextAlarmCalculator(),
                preferencesManager = preferencesManager,
                holidayRepository = mockk(relaxed = true),
                alarmIncidentRepository = mockk(relaxed = true),
                weatherRepository = mockk(relaxed = true)
            )
            val realQuickScheduler = com.sysadmindoc.alarmclock.domain.QuickAlarmScheduler(context, alarmRepo)

            assertTrue(realScheduler.scheduleAt(oldAlarm, futureOld, requestWidgetUpdate = false).isArmed)
            database.alarmDao().insert(newNormal)
            database.alarmDao().insert(newQuick)
            assertTrue(realScheduler.scheduleAt(newNormal, futureNewNormal, requestWidgetUpdate = false).isArmed)
            assertTrue(realQuickScheduler.scheduleQuickAlarm(newQuick).isArmed)

            val shadowAm = org.robolectric.Shadows.shadowOf(context.getSystemService(Context.ALARM_SERVICE) as android.app.AlarmManager)
            assertEquals("All 3 OS alarms must be armed before crash", 3, shadowAm.scheduledAlarms.size)

            // Prepare journal for OLD state, then move journal -> .bak to simulate crash after Room NEW mutation
            coordinator.prepareTransaction(AppSettings(), listOf(oldAlarm), emptyList())
            val bakFile = File(context.filesDir, "${RestoreJournalCoordinator.JOURNAL_FILENAME}.bak")
            bakFile.writeText(journalFile.readText())
            assertTrue(journalFile.delete())

            // First recovery attempt fails during OS cleanup of transient Normal 901L (double crash)
            val failingSpyScheduler = spyk(realScheduler)
            coEvery {
                failingSpyScheduler.cancelScheduledEntriesSuspending(eq(901L), any(), any(), any(), any(), any(), any())
            } returns false

            val firstCoordinator = RestoreJournalCoordinator(
                context = context,
                database = database,
                preferencesManager = preferencesManager,
                alarmScheduler = failingSpyScheduler,
                moshi = Moshi.Builder().build()
            )
            val firstOutcome = firstCoordinator.checkAndRecover()
            assertEquals(RecoveryOutcome.PENDING_RETRY, firstOutcome)
            assertTrue("Journal must be retained on PENDING_RETRY", journalFile.exists() || bakFile.exists())
            assertEquals(listOf(42L), database.alarmDao().getAll().map { it.id })

            // Cold restart with fresh coordinator & real scheduler retries cleanup
            val retryCoordinator = RestoreJournalCoordinator(
                context = context,
                database = database,
                preferencesManager = preferencesManager,
                alarmScheduler = realScheduler,
                moshi = Moshi.Builder().build()
            )
            assertEquals(listOf(901L) to listOf(902L), retryCoordinator.readPreparedTransientTargets())

            val retryOutcome = retryCoordinator.checkAndRecover()
            assertEquals(RecoveryOutcome.RESTORED_OLD, retryOutcome)
            assertFalse("Main journal must be deleted on RESTORED_OLD", journalFile.exists())
            assertFalse(".bak journal must be deleted on RESTORED_OLD", bakFile.exists())

            assertEquals("Only OLD Normal 42L must remain armed on OS", 1, shadowAm.scheduledAlarms.size)
            val remainingRequestCode = org.robolectric.Shadows.shadowOf(shadowAm.scheduledAlarms.single().operation).requestCode
            assertEquals(42, remainingRequestCode)
        } finally {
            com.sysadmindoc.alarmclock.directboot.DirectBootAlarmCache.clear(context)
            io.mockk.unmockkStatic(androidx.work.WorkManager::class)
        }
    }

    @Test
    fun `CE-B02-A2 - PermissionBlocked or Quick OS cancel failure retains journal and retries cleanly`() = runBlocking {
        val now = System.currentTimeMillis()
        val futureQuick = now + 600_000L
        val oldQuick = Alarm(
            id = 61L,
            hour = 12,
            minute = 30,
            label = "Old Quick 61",
            isEnabled = true,
            kind = Alarm.KIND_QUICK,
            quickTriggerEpochMs = futureQuick
        )
        coordinator.prepareTransaction(AppSettings(), listOf(oldQuick), emptyList())

        // Revoke exact alarm permission before recovery -> Quick reconcile must fail closed and hold journal
        org.robolectric.shadows.ShadowAlarmManager.setCanScheduleExactAlarms(false)
        val blockedOutcome = coordinator.checkAndRecover()
        assertEquals(RecoveryOutcome.PENDING_RETRY, blockedOutcome)
        assertTrue("Journal must be retained when exact alarm permission is blocked during Quick re-arm", journalFile.exists())

        // Grant exact alarm permission and retry -> succeeds and deletes journal
        org.robolectric.shadows.ShadowAlarmManager.setCanScheduleExactAlarms(true)
        val retryOutcome = coordinator.checkAndRecover()
        assertEquals(RecoveryOutcome.RESTORED_OLD, retryOutcome)
        assertFalse("Journal must be deleted once retry succeeds", journalFile.exists())
    }

    @Test
    fun `CE-B02-A3 - unresolved LEGACY_V1_CANCEL_TOKEN_PREFIX witness or settlement failure holds journal`() = runBlocking {
        val oldAlarm = Alarm(id = 42L, hour = 7, minute = 0, label = "Old 42", isEnabled = false, generation = 1)
        coordinator.prepareTransaction(AppSettings(), listOf(oldAlarm), emptyList())

        val witnessToken = "${AlarmScheduler.LEGACY_V1_CANCEL_TOKEN_PREFIX}42:1:${UUID.randomUUID()}"
        database.durableOperationIntentDao().insertValidatedIntent(
            DurableOperationIntent(
                operationToken = witnessToken,
                alarmId = 42L,
                operationEpoch = 100L,
                incarnationId = "legacy-cancel-42-1",
                operationKind = "CANCEL",
                expectedOwnerToken = "gen:1",
                expectedScheduleRevision = 1L,
                expectedRestoreEpoch = 101L,
                phase = DurableOperationIntent.PHASE_PENDING,
                intentRevision = 1L,
                originalFireId = "legacy-cancel-42-1",
                originalScheduledAt = 1_800_000_000_000L
            )
        )

        // With mocked alarmScheduler returning BatchResult(0,0,0,0), unresolved witness remains PHASE_PENDING in DB -> MUST hold journal!
        val heldOutcome = coordinator.checkAndRecover()
        assertEquals(RecoveryOutcome.PENDING_RETRY, heldOutcome)
        assertTrue("Journal must NOT be deleted while LEGACY_V1_CANCEL_TOKEN_PREFIX witness is PHASE_PENDING", journalFile.exists())

        // Once witness settles cleanly, recovery completes and deletes journal
        database.durableOperationIntentDao().updateIntentPhase(witnessToken, DurableOperationIntent.PHASE_COMPLETED)
        val okOutcome = coordinator.checkAndRecover()
        assertEquals(RecoveryOutcome.RESTORED_OLD, okOutcome)
        assertFalse("Journal must be deleted after LEGACY_V1_CANCEL_TOKEN_PREFIX witness settles", journalFile.exists())
    }

    @Test
    fun `CE-B02-QUICK-NORMAL-SAME-ID - KIND_QUICK and KIND_NORMAL transitions preserve active kind PI and cancel obsolete kind PI`() = runBlocking {
        org.robolectric.shadows.ShadowAlarmManager.setCanScheduleExactAlarms(true)
        io.mockk.mockkStatic(androidx.work.WorkManager::class)
        val mockWm = mockk<androidx.work.WorkManager>(relaxed = true)
        io.mockk.every { androidx.work.WorkManager.getInstance(any<Context>()) } returns mockWm
        try {
            val now = System.currentTimeMillis()
            val futureOld = now + 600_000L
            val futureNew = now + 900_000L
            val oldNormal = Alarm(id = 50L, hour = 6, minute = 0, label = "Old Normal 50", isEnabled = true, kind = Alarm.KIND_NORMAL, nextTriggerTime = futureOld)
            val transientQuickSameId = Alarm(id = 50L, hour = 11, minute = 0, label = "New Quick 50", isEnabled = true, kind = Alarm.KIND_QUICK, quickTriggerEpochMs = futureNew)

            database.alarmDao().insert(oldNormal)
            val alarmRepo = com.sysadmindoc.alarmclock.data.repository.AlarmRepository(database, database.alarmDao(), database.alarmGroupDao())
            val realScheduler = AlarmScheduler(
                context = context,
                repository = alarmRepo,
                calculator = com.sysadmindoc.alarmclock.domain.NextAlarmCalculator(),
                preferencesManager = preferencesManager,
                holidayRepository = mockk(relaxed = true),
                alarmIncidentRepository = mockk(relaxed = true),
                weatherRepository = mockk(relaxed = true)
            )
            val realQuickScheduler = com.sysadmindoc.alarmclock.domain.QuickAlarmScheduler(context, alarmRepo)

            val shadowAm = org.robolectric.Shadows.shadowOf(context.getSystemService(Context.ALARM_SERVICE) as android.app.AlarmManager)
            assertTrue(realScheduler.scheduleAt(oldNormal, futureOld, requestWidgetUpdate = false).isArmed)
            coordinator = RestoreJournalCoordinator(context, database, preferencesManager, realScheduler, Moshi.Builder().build())
            coordinator.prepareTransaction(AppSettings(), listOf(oldNormal), emptyList())

            // PREPARED fence: normal & quick schedulers MUST return NoArm during active PHASE_PREPARED
            assertTrue(
                "Normal scheduleAt must return NoArm during PHASE_PREPARED",
                realScheduler.scheduleAt(oldNormal, futureNew, requestWidgetUpdate = false) is com.sysadmindoc.alarmclock.domain.ScheduleOutcome.NoArm
            )
            assertTrue(
                "Quick scheduleQuickAlarm must return NoArm during PHASE_PREPARED",
                realQuickScheduler.scheduleQuickAlarm(transientQuickSameId) is com.sysadmindoc.alarmclock.domain.ScheduleOutcome.NoArm
            )

            // Construct crash-residue DB + OS state: replace Normal 50 with Quick 50 and register Quick PI directly on OS AlarmManager
            database.alarmDao().deleteAll()
            database.alarmDao().insert(transientQuickSameId)
            val quickIntent = android.content.Intent(context, com.sysadmindoc.alarmclock.receiver.AlarmReceiver::class.java).apply {
                putExtra(AlarmScheduler.EXTRA_ALARM_ID, transientQuickSameId.id)
                putExtra(AlarmScheduler.EXTRA_SCHEDULED_AT, futureNew)
                putExtra(AlarmScheduler.EXTRA_QUICK_ALARM, true)
            }
            val quickPi = android.app.PendingIntent.getBroadcast(
                context,
                (com.sysadmindoc.alarmclock.domain.QuickAlarmScheduler.REQUEST_CODE_QUICK_BASE + transientQuickSameId.id).toInt(),
                quickIntent,
                android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
            )
            val realAm = context.getSystemService(Context.ALARM_SERVICE) as android.app.AlarmManager
            realAm.setExactAndAllowWhileIdle(android.app.AlarmManager.RTC_WAKEUP, futureNew, quickPi)

            assertEquals("Both Normal 50 and Quick 50 PIs exist before rollback", 2, shadowAm.scheduledAlarms.size)

            val outcome = coordinator.checkAndRecover()
            assertEquals(RecoveryOutcome.RESTORED_OLD, outcome)
            assertFalse(journalFile.exists())

            assertEquals("Only OLD Normal 50 PI must remain armed; Quick 50 PI must be cancelled", 1, shadowAm.scheduledAlarms.size)
            val remainingRc = org.robolectric.Shadows.shadowOf(shadowAm.scheduledAlarms.single().operation).requestCode
            assertEquals(50, remainingRc)
        } finally {
            com.sysadmindoc.alarmclock.directboot.DirectBootAlarmCache.clear(context)
            io.mockk.unmockkStatic(androidx.work.WorkManager::class)
        }
    }

    private fun writeJournalWithExplicitTransactionId(
        phase: String,
        snapshotJson: String,
        transactionId: String?
    ) {
        val formatVersion = RestoreJournalCoordinator.CURRENT_FORMAT_VERSION
        val checksum = MessageDigest.getInstance("SHA-256")
            .digest("$formatVersion:$phase:$snapshotJson".toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        val json = org.json.JSONObject().apply {
            put("formatVersion", formatVersion)
            if (transactionId != null) {
                put("transactionId", transactionId)
            }
            put("phase", phase)
            put("createdAt", System.currentTimeMillis())
            put("snapshotJson", snapshotJson)
            put("checksum", checksum)
        }
        journalFile.writeText(json.toString())
    }

    @Test
    fun `T1 - PREPARED with blank transactionId fails closed without touching DB OS or schedulers`() = runTest {
        val preAlarm = Alarm(id = 301L, hour = 8, minute = 0, label = "Live 301", isEnabled = true)
        database.alarmDao().insert(preAlarm)
        val mockQuick = mockk<com.sysadmindoc.alarmclock.domain.QuickAlarmScheduler>(relaxed = true)
        val guardedCoordinator = RestoreJournalCoordinator(
            context = context,
            database = database,
            preferencesManager = preferencesManager,
            alarmScheduler = alarmScheduler,
            moshi = Moshi.Builder().build(),
            quickAlarmScheduler = mockQuick
        )
        writeJournalWithExplicitTransactionId(
            phase = RestoreJournalCoordinator.PHASE_PREPARED,
            snapshotJson = """{"settings":{},"alarms":[],"groups":[]}""",
            transactionId = ""
        )

        val res = runCatching { guardedCoordinator.checkAndRecover() }
        assertTrue("T1: blank transactionId in PREPARED must fail closed", res.isFailure)
        assertTrue(res.exceptionOrNull() is SecurityException)
        assertTrue("T1: journal must be retained", journalFile.exists())
        assertEquals(listOf(301L), database.alarmDao().getAll().map { it.id })
        assertEquals(100L, database.scheduleAuthorityDao().getMetaById(1)?.restoreEpoch)
        coVerify(exactly = 0) { alarmScheduler.rescheduleAllInBatches(any(), any()) }
        coVerify(exactly = 0) { alarmScheduler.cancelScheduledEntriesSuspending(any(), any(), any(), any(), any(), any(), any()) }
        coVerify(exactly = 0) { mockQuick.cancelQuickPendingIntentOnly(any()) }
        coVerify(exactly = 0) { mockQuick.reconcileQuickAlarms(any(), any(), any()) }
    }

    @Test
    fun `T2 - COMMITTED with blank transactionId fails closed without touching DB OS or schedulers`() = runTest {
        val preAlarm = Alarm(id = 302L, hour = 8, minute = 0, label = "Live 302", isEnabled = true)
        database.alarmDao().insert(preAlarm)
        val mockQuick = mockk<com.sysadmindoc.alarmclock.domain.QuickAlarmScheduler>(relaxed = true)
        val guardedCoordinator = RestoreJournalCoordinator(
            context = context,
            database = database,
            preferencesManager = preferencesManager,
            alarmScheduler = alarmScheduler,
            moshi = Moshi.Builder().build(),
            quickAlarmScheduler = mockQuick
        )
        writeJournalWithExplicitTransactionId(
            phase = RestoreJournalCoordinator.PHASE_COMMITTED,
            snapshotJson = """{"settings":{},"alarms":[{"id":999,"hour":7,"minute":0,"isEnabled":true}],"groups":[]}""",
            transactionId = ""
        )

        val res = runCatching { guardedCoordinator.checkAndRecover() }
        assertTrue("T2: blank transactionId in COMMITTED must fail closed", res.isFailure)
        assertTrue(res.exceptionOrNull() is SecurityException)
        assertTrue("T2: journal must be retained", journalFile.exists())
        assertEquals(listOf(302L), database.alarmDao().getAll().map { it.id })
        assertEquals(100L, database.scheduleAuthorityDao().getMetaById(1)?.restoreEpoch)
        coVerify(exactly = 0) { alarmScheduler.rescheduleAllInBatches(any(), any()) }
        coVerify(exactly = 0) { alarmScheduler.cancelScheduledEntriesSuspending(any(), any(), any(), any(), any(), any(), any()) }
        coVerify(exactly = 0) { mockQuick.cancelQuickPendingIntentOnly(any()) }
        coVerify(exactly = 0) { mockQuick.reconcileQuickAlarms(any(), any(), any()) }
    }

    @Test
    fun `T3 - missing transactionId key fails closed in both PREPARED and COMMITTED`() = runTest {
        val mockQuick = mockk<com.sysadmindoc.alarmclock.domain.QuickAlarmScheduler>(relaxed = true)
        val guardedCoordinator = RestoreJournalCoordinator(
            context = context,
            database = database,
            preferencesManager = preferencesManager,
            alarmScheduler = alarmScheduler,
            moshi = Moshi.Builder().build(),
            quickAlarmScheduler = mockQuick
        )

        for (phase in listOf(RestoreJournalCoordinator.PHASE_PREPARED, RestoreJournalCoordinator.PHASE_COMMITTED)) {
            writeJournalWithExplicitTransactionId(
                phase = phase,
                snapshotJson = """{"settings":{},"alarms":[],"groups":[]}""",
                transactionId = null
            )
            val res = runCatching { guardedCoordinator.checkAndRecover() }
            assertTrue("T3 ($phase): missing transactionId key must fail closed", res.isFailure)
            assertTrue(res.exceptionOrNull() is SecurityException)
            assertTrue("T3 ($phase): journal must be retained", journalFile.exists())
        }
        assertEquals(100L, database.scheduleAuthorityDao().getMetaById(1)?.restoreEpoch)
        coVerify(exactly = 0) { alarmScheduler.rescheduleAllInBatches(any(), any()) }
        coVerify(exactly = 0) { mockQuick.reconcileQuickAlarms(any(), any(), any()) }
    }

    @Test
    fun `T4 - whitespace transactionId fails closed in PREPARED and COMMITTED`() = runTest {
        val mockQuick = mockk<com.sysadmindoc.alarmclock.domain.QuickAlarmScheduler>(relaxed = true)
        val guardedCoordinator = RestoreJournalCoordinator(
            context = context,
            database = database,
            preferencesManager = preferencesManager,
            alarmScheduler = alarmScheduler,
            moshi = Moshi.Builder().build(),
            quickAlarmScheduler = mockQuick
        )

        for (phase in listOf(RestoreJournalCoordinator.PHASE_PREPARED, RestoreJournalCoordinator.PHASE_COMMITTED)) {
            writeJournalWithExplicitTransactionId(
                phase = phase,
                snapshotJson = """{"settings":{},"alarms":[],"groups":[]}""",
                transactionId = "   \t\n  "
            )
            val res = runCatching { guardedCoordinator.checkAndRecover() }
            assertTrue("T4 ($phase): whitespace transactionId must fail closed", res.isFailure)
            assertTrue(res.exceptionOrNull() is SecurityException)
            assertTrue("T4 ($phase): journal must be retained", journalFile.exists())
        }
        assertEquals(100L, database.scheduleAuthorityDao().getMetaById(1)?.restoreEpoch)
        coVerify(exactly = 0) { alarmScheduler.rescheduleAllInBatches(any(), any()) }
        coVerify(exactly = 0) { mockQuick.reconcileQuickAlarms(any(), any(), any()) }
    }

    @Test
    fun `T5 - valid transactionId recovers PREPARED and COMMITTED normally`() = runBlocking {
        org.robolectric.shadows.ShadowAlarmManager.setCanScheduleExactAlarms(true)
        io.mockk.mockkStatic(androidx.work.WorkManager::class)
        val mockWm = mockk<androidx.work.WorkManager>(relaxed = true)
        io.mockk.every { androidx.work.WorkManager.getInstance(any<Context>()) } returns mockWm
        try {
            val alarmRepo = com.sysadmindoc.alarmclock.data.repository.AlarmRepository(database, database.alarmDao(), database.alarmGroupDao())
            val realScheduler = AlarmScheduler(
                context = context,
                repository = alarmRepo,
                calculator = com.sysadmindoc.alarmclock.domain.NextAlarmCalculator(),
                preferencesManager = preferencesManager,
                holidayRepository = mockk(relaxed = true),
                alarmIncidentRepository = mockk(relaxed = true),
                weatherRepository = mockk(relaxed = true)
            )
            val realQuick = com.sysadmindoc.alarmclock.domain.QuickAlarmScheduler(context, alarmRepo)
            val realCoord = RestoreJournalCoordinator(
                context = context,
                database = database,
                preferencesManager = preferencesManager,
                alarmScheduler = realScheduler,
                moshi = Moshi.Builder().build(),
                quickAlarmScheduler = realQuick
            )

            // 1. PREPARED with valid UUID transactionId
            val oldAlarm = Alarm(id = 305L, hour = 7, minute = 0, label = "Old 305", isEnabled = true, nextTriggerTime = System.currentTimeMillis() + 600_000L)
            realCoord.prepareTransaction(AppSettings(), listOf(oldAlarm), emptyList())
            assertEquals(RecoveryOutcome.RESTORED_OLD, realCoord.checkAndRecover())
            assertFalse(journalFile.exists())

            // 2. COMMITTED with valid UUID transactionId
            realCoord.prepareTransaction(AppSettings(), listOf(oldAlarm), emptyList())
            realCoord.markCommitted()
            assertEquals(RecoveryOutcome.RECOVERED_NEW, realCoord.checkAndRecover())
            assertFalse(journalFile.exists())
        } finally {
            com.sysadmindoc.alarmclock.directboot.DirectBootAlarmCache.clear(context)
            io.mockk.unmockkStatic(androidx.work.WorkManager::class)
        }
    }

    @Test
    fun `T6 - checkAndRecover with real schedulers uses 4-factor bypass with no nested L1 acquisition`() = runBlocking {
        org.robolectric.shadows.ShadowAlarmManager.setCanScheduleExactAlarms(true)
        io.mockk.mockkStatic(androidx.work.WorkManager::class)
        val mockWm = mockk<androidx.work.WorkManager>(relaxed = true)
        io.mockk.every { androidx.work.WorkManager.getInstance(any<Context>()) } returns mockWm
        try {
            val future = System.currentTimeMillis() + 600_000L
            val alarm = Alarm(id = 306L, hour = 8, minute = 30, label = "No Nest 306", isEnabled = true, nextTriggerTime = future)
            database.alarmDao().insert(alarm)
            val alarmRepo = com.sysadmindoc.alarmclock.data.repository.AlarmRepository(database, database.alarmDao(), database.alarmGroupDao())
            val realScheduler = AlarmScheduler(
                context = context,
                repository = alarmRepo,
                calculator = com.sysadmindoc.alarmclock.domain.NextAlarmCalculator(),
                preferencesManager = preferencesManager,
                holidayRepository = mockk(relaxed = true),
                alarmIncidentRepository = mockk(relaxed = true),
                weatherRepository = mockk(relaxed = true)
            )
            val realQuick = com.sysadmindoc.alarmclock.domain.QuickAlarmScheduler(context, alarmRepo)
            val spyScheduler = spyk(realScheduler)
            val spyQuick = spyk(realQuick)

            var l1LockedDuringReschedule = false
            coEvery { spyScheduler.rescheduleAllInBatches(any(), any()) } coAnswers {
                l1LockedDuringReschedule = RestoreJournalCoordinator.recoveryMutexFor(context).isLocked
                callOriginal()
            }

            val realCoord = RestoreJournalCoordinator(
                context = context,
                database = database,
                preferencesManager = preferencesManager,
                alarmScheduler = spyScheduler,
                moshi = Moshi.Builder().build(),
                quickAlarmScheduler = spyQuick
            )

            realCoord.prepareTransaction(AppSettings(), listOf(alarm), emptyList())
            realCoord.markCommitted()

            val outcome = kotlinx.coroutines.withTimeout(5_000L) {
                realCoord.checkAndRecover()
            }
            assertEquals(RecoveryOutcome.RECOVERED_NEW, outcome)
            assertTrue("T6: L1 recoveryMutex must remain held by checkAndRecover during rescheduleAllInBatches", l1LockedDuringReschedule)
            assertFalse("T6: L1 recoveryMutex must be released after checkAndRecover finishes", RestoreJournalCoordinator.recoveryMutexFor(context).isLocked)
            assertFalse(journalFile.exists())
        } finally {
            com.sysadmindoc.alarmclock.directboot.DirectBootAlarmCache.clear(context)
            io.mockk.unmockkStatic(androidx.work.WorkManager::class)
        }
    }

    @Test
    fun `T7 - invalid blank transactionId journal keeps Room DB and OS PendingIntents completely unchanged`() = runBlocking {
        org.robolectric.shadows.ShadowAlarmManager.setCanScheduleExactAlarms(true)
        io.mockk.mockkStatic(androidx.work.WorkManager::class)
        val mockWm = mockk<androidx.work.WorkManager>(relaxed = true)
        io.mockk.every { androidx.work.WorkManager.getInstance(any<Context>()) } returns mockWm
        try {
            val future = System.currentTimeMillis() + 600_000L
            val existingAlarm = Alarm(id = 307L, hour = 9, minute = 0, label = "Existing 307", isEnabled = true, nextTriggerTime = future)
            database.alarmDao().insert(existingAlarm)
            val alarmRepo = com.sysadmindoc.alarmclock.data.repository.AlarmRepository(database, database.alarmDao(), database.alarmGroupDao())
            val realScheduler = AlarmScheduler(
                context = context,
                repository = alarmRepo,
                calculator = com.sysadmindoc.alarmclock.domain.NextAlarmCalculator(),
                preferencesManager = preferencesManager,
                holidayRepository = mockk(relaxed = true),
                alarmIncidentRepository = mockk(relaxed = true),
                weatherRepository = mockk(relaxed = true)
            )
            assertTrue(realScheduler.scheduleAt(existingAlarm, future, requestWidgetUpdate = false).isArmed)
            val shadowAm = org.robolectric.Shadows.shadowOf(context.getSystemService(Context.ALARM_SERVICE) as android.app.AlarmManager)
            assertEquals(1, shadowAm.scheduledAlarms.size)

            val realCoord = RestoreJournalCoordinator(
                context = context,
                database = database,
                preferencesManager = preferencesManager,
                alarmScheduler = realScheduler,
                moshi = Moshi.Builder().build()
            )
            writeJournalWithExplicitTransactionId(
                phase = RestoreJournalCoordinator.PHASE_PREPARED,
                snapshotJson = """{"settings":{},"alarms":[],"groups":[]}""",
                transactionId = ""
            )

            val res = runCatching { realCoord.checkAndRecover() }
            assertTrue("T7: blank transactionId must fail closed", res.isFailure)
            assertTrue(res.exceptionOrNull() is SecurityException)
            assertTrue("T7: invalid journal must stay on disk", journalFile.exists())
            assertEquals(1, shadowAm.scheduledAlarms.size)
            assertEquals(listOf(307L), database.alarmDao().getAll().map { it.id })
            assertEquals(100L, database.scheduleAuthorityDao().getMetaById(1)?.restoreEpoch)
        } finally {
            com.sysadmindoc.alarmclock.directboot.DirectBootAlarmCache.clear(context)
            io.mockk.unmockkStatic(androidx.work.WorkManager::class)
        }
    }

    @Test
    fun `T8 - PREPARED scheduling and cancel remain NoArm and false under valid and invalid journals`() = runBlocking {
        org.robolectric.shadows.ShadowAlarmManager.setCanScheduleExactAlarms(true)
        io.mockk.mockkStatic(androidx.work.WorkManager::class)
        val mockWm = mockk<androidx.work.WorkManager>(relaxed = true)
        io.mockk.every { androidx.work.WorkManager.getInstance(any<Context>()) } returns mockWm
        try {
            val future = System.currentTimeMillis() + 600_000L
            val alarm = Alarm(id = 308L, hour = 10, minute = 0, label = "Fenced 308", isEnabled = true, nextTriggerTime = future)
            val quickAlarm = Alarm(id = 309L, hour = 10, minute = 5, label = "Fenced Quick 309", isEnabled = true, kind = Alarm.KIND_QUICK, quickTriggerEpochMs = future)
            database.alarmDao().insert(alarm)
            val alarmRepo = com.sysadmindoc.alarmclock.data.repository.AlarmRepository(database, database.alarmDao(), database.alarmGroupDao())
            val realScheduler = AlarmScheduler(
                context = context,
                repository = alarmRepo,
                calculator = com.sysadmindoc.alarmclock.domain.NextAlarmCalculator(),
                preferencesManager = preferencesManager,
                holidayRepository = mockk(relaxed = true),
                alarmIncidentRepository = mockk(relaxed = true),
                weatherRepository = mockk(relaxed = true)
            )
            val realQuick = com.sysadmindoc.alarmclock.domain.QuickAlarmScheduler(context, alarmRepo)

            // 1. Valid PHASE_PREPARED fence
            coordinator = RestoreJournalCoordinator(context, database, preferencesManager, realScheduler, Moshi.Builder().build())
            coordinator.prepareTransaction(AppSettings(), listOf(alarm), emptyList())
            assertTrue(realScheduler.scheduleAt(alarm, future, requestWidgetUpdate = false) is com.sysadmindoc.alarmclock.domain.ScheduleOutcome.NoArm)
            assertTrue(realQuick.scheduleQuickAlarm(quickAlarm) is com.sysadmindoc.alarmclock.domain.ScheduleOutcome.NoArm)
            assertFalse(realScheduler.cancelSuspending(alarm.id))

            // 2. Invalid blank-transactionId COMMITTED journal must also fail closed for external scheduling
            writeJournalWithExplicitTransactionId(
                phase = RestoreJournalCoordinator.PHASE_COMMITTED,
                snapshotJson = """{"settings":{},"alarms":[],"groups":[]}""",
                transactionId = ""
            )
            assertTrue(realScheduler.scheduleAt(alarm, future, requestWidgetUpdate = false) is com.sysadmindoc.alarmclock.domain.ScheduleOutcome.NoArm)
            assertTrue(realQuick.scheduleQuickAlarm(quickAlarm) is com.sysadmindoc.alarmclock.domain.ScheduleOutcome.NoArm)
            assertFalse(realScheduler.cancelSuspending(alarm.id))
        } finally {
            com.sysadmindoc.alarmclock.directboot.DirectBootAlarmCache.clear(context)
            io.mockk.unmockkStatic(androidx.work.WorkManager::class)
        }
    }
}
