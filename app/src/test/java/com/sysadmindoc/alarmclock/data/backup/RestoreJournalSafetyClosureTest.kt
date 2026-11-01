package com.sysadmindoc.alarmclock.data.backup

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.squareup.moshi.Moshi
import com.sysadmindoc.alarmclock.data.holiday.FileManualHolidayOverrideStore
import com.sysadmindoc.alarmclock.data.holiday.ManualHolidayOverrideStore
import com.sysadmindoc.alarmclock.data.local.AlarmDatabase
import com.sysadmindoc.alarmclock.data.local.entity.AlarmGroup
import com.sysadmindoc.alarmclock.data.local.entity.ScheduleAuthorityMeta
import com.sysadmindoc.alarmclock.data.model.Alarm
import com.sysadmindoc.alarmclock.data.preferences.AppSettings
import com.sysadmindoc.alarmclock.data.preferences.PreferencesManager
import com.sysadmindoc.alarmclock.data.repository.HolidayReconcileCoordinator
import com.sysadmindoc.alarmclock.data.repository.HolidayReconcileResult
import com.sysadmindoc.alarmclock.data.repository.ManualHolidayOverride
import com.sysadmindoc.alarmclock.domain.AlarmScheduler
import com.sysadmindoc.alarmclock.domain.ScheduleOutcome
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.unmockkAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.security.MessageDigest
import java.time.DayOfWeek
import java.time.LocalDate
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class RestoreJournalSafetyClosureTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var context: Context
    private lateinit var database: AlarmDatabase
    private lateinit var preferencesManager: PreferencesManager
    private lateinit var alarmScheduler: AlarmScheduler
    private lateinit var coordinator: HolidayReconcileCoordinator
    private lateinit var overrideStore: ManualHolidayOverrideStore
    private lateinit var restoreCoordinator: RestoreJournalCoordinator
    private lateinit var journalFile: File

    private val d1 = LocalDate.of(2026, 12, 24)
    private val d2 = LocalDate.of(2026, 12, 25)

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        database = Room.inMemoryDatabaseBuilder(context, AlarmDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        runBlocking {
            database.scheduleAuthorityDao().insertMeta(
                ScheduleAuthorityMeta(id = 1, restoreEpoch = 1000L, bootstrapState = "BOOTSTRAPPED")
            )
        }
        preferencesManager = mockk(relaxed = true)
        alarmScheduler = mockk(relaxed = true)
        coordinator = mockk(relaxed = true)

        every { coordinator.clockMillis } returns { System.currentTimeMillis() }
        coEvery { preferencesManager.getCurrentSettings() } returns AppSettings()
        coEvery { alarmScheduler.rescheduleAllInBatches(any(), any()) } returns ScheduleOutcome.BatchResult(1, 0, 0, 0, emptyMap())
        coEvery { coordinator.reconcile(any(), any(), any(), any()) } returns HolidayReconcileResult.Success(reconciledCount = 0, isNoOp = true)

        journalFile = File(context.filesDir, RestoreJournalCoordinator.JOURNAL_FILENAME)
        if (journalFile.exists()) journalFile.delete()

        val overridesFile = File(tempFolder.root, "manual_holiday_overrides.json")
        overrideStore = FileManualHolidayOverrideStore(
            file = overridesFile,
            reconcileCoordinatorProvider = { coordinator }
        )

        restoreCoordinator = RestoreJournalCoordinator(
            context = context,
            database = database,
            preferencesManager = preferencesManager,
            alarmScheduler = alarmScheduler,
            moshi = Moshi.Builder().build(),
            manualHolidayOverrideStore = overrideStore,
            holidayReconcileCoordinator = coordinator
        )
    }

    @After
    fun tearDown() {
        database.close()
        if (journalFile.exists()) journalFile.delete()
        unmockkAll()
    }

    private fun writeRawJournal(phase: String, snapshotJson: String) {
        val formatVersion = RestoreJournalCoordinator.CURRENT_FORMAT_VERSION
        val payloadToHash = "$formatVersion:$phase:$snapshotJson"
        val md = MessageDigest.getInstance("SHA-256")
        val hashBytes = md.digest(payloadToHash.toByteArray(Charsets.UTF_8))
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
    fun testA_invalid_journal_date_rejects_and_prevents_partial_restoration() = runTest {
        // Current truth: d1 -> FORCE_HOLIDAY
        overrideStore.setOverride(d1, ManualHolidayOverride.FORCE_HOLIDAY)

        val badSnapshot = """
            {
              "settings": {},
              "alarms": [],
              "groups": [],
              "manualOverrides": {
                "not-a-valid-date": "FORCE_WORKDAY"
              }
            }
        """.trimIndent()
        writeRawJournal(RestoreJournalCoordinator.PHASE_PREPARED, badSnapshot)

        try {
            restoreCoordinator.checkAndRecover()
            fail("Expected checkAndRecover to fail on invalid date")
        } catch (e: Exception) {
            assertTrue(e is IllegalStateException || e is SecurityException)
        }

        // Must retain journal on disk for forensics
        assertTrue("Journal must be retained", journalFile.exists())
        // Must NOT overwrite current on-device override
        assertEquals(ManualHolidayOverride.FORCE_HOLIDAY, overrideStore.overrideFor(d1))
    }

    @Test
    fun testB_invalid_enum_rejects_and_prevents_partial_restoration() = runTest {
        overrideStore.setOverride(d1, ManualHolidayOverride.FORCE_HOLIDAY)

        val badSnapshot = """
            {
              "settings": {},
              "alarms": [],
              "groups": [],
              "manualOverrides": {
                "2026-12-25": "VACATION_DAY"
              }
            }
        """.trimIndent()
        writeRawJournal(RestoreJournalCoordinator.PHASE_PREPARED, badSnapshot)

        try {
            restoreCoordinator.checkAndRecover()
            fail("Expected checkAndRecover to fail on invalid enum")
        } catch (e: Exception) {
            assertTrue(e is IllegalStateException || e is SecurityException)
        }

        assertTrue("Journal must be retained", journalFile.exists())
        assertEquals(ManualHolidayOverride.FORCE_HOLIDAY, overrideStore.overrideFor(d1))
    }

    @Test
    fun testC_valid_journal_performs_full_restoration() = runTest {
        overrideStore.setOverride(d1, ManualHolidayOverride.FORCE_WORKDAY)

        val validSnapshot = """
            {
              "settings": {},
              "alarms": [],
              "groups": [],
              "manualOverrides": {
                "2026-12-24": "FORCE_HOLIDAY",
                "2026-12-25": "FORCE_WORKDAY"
              }
            }
        """.trimIndent()
        writeRawJournal(RestoreJournalCoordinator.PHASE_PREPARED, validSnapshot)

        val outcome = restoreCoordinator.checkAndRecover()
        assertEquals(RecoveryOutcome.RESTORED_OLD, outcome)
        assertFalse("Journal must be deleted after full restoration", journalFile.exists())
        assertEquals(ManualHolidayOverride.FORCE_HOLIDAY, overrideStore.overrideFor(d1))
        assertEquals(ManualHolidayOverride.FORCE_WORKDAY, overrideStore.overrideFor(d2))
    }

    @Test
    fun testD_batch_failure_leaves_recovery_pending() = runTest {
        coEvery { alarmScheduler.rescheduleAllInBatches(any(), any()) } returns ScheduleOutcome.BatchResult(
            armedCount = 0,
            blockedCount = 0,
            failedCount = 1,
            noArmCount = 0,
            outcomes = mapOf(1L to ScheduleOutcome.RegistrationFailed(1L, null))
        )

        val snapshot = """
            {
              "settings": {},
              "alarms": [],
              "groups": [],
              "manualOverrides": {
                "2026-12-24": "FORCE_HOLIDAY"
              }
            }
        """.trimIndent()
        writeRawJournal(RestoreJournalCoordinator.PHASE_PREPARED, snapshot)

        val outcome = restoreCoordinator.checkAndRecover()
        assertEquals(RecoveryOutcome.PENDING_RETRY, outcome)
        assertTrue("Journal must be retained when batch registration fails", journalFile.exists())
    }

    @Test
    fun testE_pending_reconcile_retry_leaves_recovery_pending() = runTest {
        coEvery { coordinator.reconcile(any(), any(), any(), any()) } returns HolidayReconcileResult.PendingRetry(
            pendingCount = 1,
            failureCause = null
        )

        val snapshot = """
            {
              "settings": {},
              "alarms": [],
              "groups": [],
              "manualOverrides": {
                "2026-12-24": "FORCE_HOLIDAY"
              }
            }
        """.trimIndent()
        writeRawJournal(RestoreJournalCoordinator.PHASE_PREPARED, snapshot)

        val outcome = restoreCoordinator.checkAndRecover()
        assertEquals(RecoveryOutcome.PENDING_RETRY, outcome)
        assertTrue("Journal must be retained when reconcile returns PendingRetry", journalFile.exists())
    }

    @Test
    fun testF_missing_coordinator_causes_no_false_completion() = runTest {
        val coordinatorWithoutHolidayReconcile = RestoreJournalCoordinator(
            context = context,
            database = database,
            preferencesManager = preferencesManager,
            alarmScheduler = alarmScheduler,
            moshi = Moshi.Builder().build(),
            manualHolidayOverrideStore = overrideStore,
            holidayReconcileCoordinator = null
        )

        val snapshot = """
            {
              "settings": {},
              "alarms": [],
              "groups": [],
              "manualOverrides": {
                "2026-12-24": "FORCE_HOLIDAY"
              }
            }
        """.trimIndent()
        writeRawJournal(RestoreJournalCoordinator.PHASE_PREPARED, snapshot)

        val outcome = coordinatorWithoutHolidayReconcile.checkAndRecover()
        assertEquals(RecoveryOutcome.PENDING_RETRY, outcome)
        assertTrue("Journal must be retained when coordinator is missing", journalFile.exists())
    }

    @Test
    fun testG_all_operations_succeed_clears_journal() = runTest {
        val snapshot = """
            {
              "settings": {},
              "alarms": [],
              "groups": [],
              "manualOverrides": {
                "2026-12-24": "FORCE_HOLIDAY"
              }
            }
        """.trimIndent()
        writeRawJournal(RestoreJournalCoordinator.PHASE_PREPARED, snapshot)

        val outcome = restoreCoordinator.checkAndRecover()
        assertEquals(RecoveryOutcome.RESTORED_OLD, outcome)
        assertFalse("Journal must be cleared on success", journalFile.exists())
    }

    @Test
    fun testH_repeated_recovery_is_idempotent() = runTest {
        val snapshot = """
            {
              "settings": {},
              "alarms": [],
              "groups": [],
              "manualOverrides": {
                "2026-12-24": "FORCE_HOLIDAY"
              }
            }
        """.trimIndent()
        writeRawJournal(RestoreJournalCoordinator.PHASE_PREPARED, snapshot)

        // First run succeeds
        val outcome1 = restoreCoordinator.checkAndRecover()
        assertEquals(RecoveryOutcome.RESTORED_OLD, outcome1)
        assertFalse(journalFile.exists())

        // Second run has no journal
        val outcome2 = restoreCoordinator.checkAndRecover()
        assertEquals(RecoveryOutcome.NONE, outcome2)
    }

    @Test
    fun testI_process_death_after_partial_rollback_recovers_cleanly() = runTest {
        val nonDefaultAlarm = Alarm(
            id = 77L,
            hour = 7,
            minute = 15,
            label = "Morning",
            repeatDays = setOf(DayOfWeek.MONDAY)
        )
        val snapshot = """
            {
              "settings": {},
              "alarms": [
                {
                  "id": 77,
                  "hour": 7,
                  "minute": 15,
                  "label": "Morning",
                  "isEnabled": true,
                  "repeatDays": ["MONDAY"]
                }
              ],
              "groups": [],
              "manualOverrides": {
                "2026-12-24": "FORCE_HOLIDAY"
              }
            }
        """.trimIndent()
        writeRawJournal(RestoreJournalCoordinator.PHASE_PREPARED, snapshot)

        // Simulate that DB was partially updated or corrupted before recovery was completed
        database.alarmDao().deleteAll()

        // Re-execute recovery
        val outcome = restoreCoordinator.checkAndRecover()
        assertEquals(RecoveryOutcome.RESTORED_OLD, outcome)
        assertFalse(journalFile.exists())

        // Verify alarm was restored
        val alarms = database.alarmDao().getAll()
        assertEquals(1, alarms.size)
        assertEquals(77L, alarms[0].id)
        assertEquals(ManualHolidayOverride.FORCE_HOLIDAY, overrideStore.overrideFor(d1))
    }

    @Test
    fun testJ_existing_legacy_journal_remains_compatible() = runTest {
        // Legacy journal has NO manualOverrides field
        val legacySnapshot = """
            {
              "settings": {
                "is24HourFormat": true
              },
              "alarms": [],
              "groups": []
            }
        """.trimIndent()
        writeRawJournal(RestoreJournalCoordinator.PHASE_PREPARED, legacySnapshot)

        val outcome = restoreCoordinator.checkAndRecover()
        assertEquals(RecoveryOutcome.RESTORED_OLD, outcome)
        assertFalse(journalFile.exists())
    }
}
