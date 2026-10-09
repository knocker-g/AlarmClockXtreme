package com.sysadmindoc.alarmclock.data.backup

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.squareup.moshi.Moshi
import com.sysadmindoc.alarmclock.data.local.AlarmDatabase
import com.sysadmindoc.alarmclock.data.local.entity.AlarmGroup
import com.sysadmindoc.alarmclock.data.model.Alarm
import com.sysadmindoc.alarmclock.data.preferences.AppSettings
import com.sysadmindoc.alarmclock.data.preferences.PreferencesManager
import com.sysadmindoc.alarmclock.domain.AlarmScheduler
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.unmockkAll
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.time.DayOfWeek

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
        preferencesManager = mockk(relaxed = true)
        alarmScheduler = mockk(relaxed = true)
        coEvery { alarmScheduler.rescheduleAllInBatches(any(), any()) } returns 0

        journalFile = File(context.filesDir, RestoreJournalCoordinator.JOURNAL_FILENAME)
        if (journalFile.exists()) journalFile.delete()

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

        coEvery { alarmScheduler.rescheduleAllInBatches(any(), any()) } returns 0
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

        coEvery { alarmScheduler.rescheduleAllInBatches(any(), any()) } returns 0
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
}
