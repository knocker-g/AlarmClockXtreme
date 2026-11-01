package com.sysadmindoc.alarmclock.data.backup

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.squareup.moshi.Moshi
import com.sysadmindoc.alarmclock.data.holiday.FileManualHolidayOverrideStore
import com.sysadmindoc.alarmclock.data.holiday.ManualHolidayOverrideStore
import com.sysadmindoc.alarmclock.data.local.AlarmDatabase
import com.sysadmindoc.alarmclock.data.model.Alarm
import com.sysadmindoc.alarmclock.data.preferences.AppSettings
import com.sysadmindoc.alarmclock.data.preferences.PreferencesManager
import com.sysadmindoc.alarmclock.data.repository.AlarmRepository
import com.sysadmindoc.alarmclock.data.local.entity.ScheduleAuthorityMeta
import com.sysadmindoc.alarmclock.data.repository.HolidayReconcileCoordinator
import com.sysadmindoc.alarmclock.data.repository.HolidayReconcileResult
import com.sysadmindoc.alarmclock.data.repository.ManualHolidayOverride
import com.sysadmindoc.alarmclock.domain.AlarmScheduler
import io.mockk.every
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.unmockkAll
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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
import java.time.LocalDate

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class ManualHolidayBackupRestoreIntegrationTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var context: Context
    private lateinit var database: AlarmDatabase
    private lateinit var repository: AlarmRepository
    private lateinit var preferencesManager: PreferencesManager
    private lateinit var scheduler: AlarmScheduler
    private lateinit var coordinator: HolidayReconcileCoordinator
    private lateinit var overrideStore: ManualHolidayOverrideStore
    private lateinit var restoreJournalCoordinator: RestoreJournalCoordinator
    private lateinit var backupManager: BackupManager

    private val d1 = LocalDate.of(2026, 12, 24)
    private val d2 = LocalDate.of(2026, 12, 25)
    private val d3 = LocalDate.of(2026, 12, 26)

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        database = Room.inMemoryDatabaseBuilder(context, AlarmDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        kotlinx.coroutines.runBlocking {
            database.scheduleAuthorityDao().insertMeta(
                ScheduleAuthorityMeta(
                    id = 1,
                    restoreEpoch = 1000L,
                    bootstrapState = "BOOTSTRAPPED"
                )
            )
        }
        repository = AlarmRepository(database, database.alarmDao(), database.alarmGroupDao())
        preferencesManager = mockk(relaxed = true)
        scheduler = mockk(relaxed = true)
        coordinator = mockk(relaxed = true)

        every { coordinator.clockMillis } returns { System.currentTimeMillis() }
        coEvery { preferencesManager.getCurrentSettings() } returns AppSettings()
        coEvery { scheduler.rescheduleAllInBatches(any(), any()) } returns com.sysadmindoc.alarmclock.domain.ScheduleOutcome.BatchResult(0, 0, 0, 0, emptyMap())
        coEvery { coordinator.reconcile(any(), any(), any(), any()) } returns HolidayReconcileResult.Success(reconciledCount = 0, isNoOp = true)

        val overridesFile = File(tempFolder.root, "manual_holiday_overrides.json")
        overrideStore = FileManualHolidayOverrideStore(
            file = overridesFile,
            reconcileCoordinatorProvider = { coordinator }
        )

        restoreJournalCoordinator = RestoreJournalCoordinator(
            context = context,
            database = database,
            preferencesManager = preferencesManager,
            alarmScheduler = scheduler,
            moshi = Moshi.Builder().build(),
            manualHolidayOverrideStore = overrideStore,
            holidayReconcileCoordinator = coordinator
        )

        backupManager = BackupManager(
            context = context,
            repository = repository,
            preferencesManager = preferencesManager,
            scheduler = scheduler,
            restoreJournalCoordinator = restoreJournalCoordinator,
            database = database,
            manualHolidayOverrideStore = overrideStore,
            holidayReconcileCoordinator = coordinator
        )
    }

    @After
    fun tearDown() {
        database.close()
        unmockkAll()
    }

    @Test
    fun test1_v23_export_includes_manual_overrides_and_omits_AUTO() = runTest {
        overrideStore.setOverride(d1, ManualHolidayOverride.FORCE_HOLIDAY)
        overrideStore.setOverride(d2, ManualHolidayOverride.FORCE_WORKDAY)
        overrideStore.setOverride(d3, ManualHolidayOverride.AUTO)

        val json = backupManager.export()
        val adapter = Moshi.Builder().build().adapter(BackupData::class.java)
        val backup = adapter.fromJson(json)

        assertNotNull("Backup must parse cleanly", backup)
        assertEquals(23, backup!!.version)
        assertNotNull("manualHolidayOverrides must be present in v23 export", backup.manualHolidayOverrides)
        assertEquals(2, backup.manualHolidayOverrides!!.size)
        assertEquals("FORCE_HOLIDAY", backup.manualHolidayOverrides!![d1.toString()])
        assertEquals("FORCE_WORKDAY", backup.manualHolidayOverrides!![d2.toString()])
        assertNull("AUTO override must be omitted from export", backup.manualHolidayOverrides!![d3.toString()])
    }

    @Test
    fun test2_v23_import_roundtrip_with_replace_mode() = runTest {
        overrideStore.setOverride(d1, ManualHolidayOverride.FORCE_HOLIDAY)
        overrideStore.setOverride(d2, ManualHolidayOverride.FORCE_WORKDAY)

        val json = backupManager.export()

        // Clear store
        overrideStore.restoreOverrides(emptyMap(), triggerReconcile = false)
        assertEquals(ManualHolidayOverride.AUTO, overrideStore.overrideFor(d1))

        // Import with Replace mode
        val result = backupManager.importFromUriStringForTest(
            json,
            BackupImportOptions(mode = BackupImportMode.Replace, importSettings = false)
        )
        assertTrue("Import must succeed", result.isSuccess)
        assertEquals(ManualHolidayOverride.FORCE_HOLIDAY, overrideStore.overrideFor(d1))
        assertEquals(ManualHolidayOverride.FORCE_WORKDAY, overrideStore.overrideFor(d2))
    }

    @Test
    fun test3_v23_import_append_mode_merges_and_preserves_on_device_conflicts() = runTest {
        // On-device has d1 -> FORCE_WORKDAY
        overrideStore.setOverride(d1, ManualHolidayOverride.FORCE_WORKDAY)

        // Backup has d1 -> FORCE_HOLIDAY, d2 -> FORCE_HOLIDAY
        val backupData = BackupData(
            version = 23,
            alarms = emptyList(),
            settings = null,
            manualHolidayOverrides = mapOf(
                d1.toString() to "FORCE_HOLIDAY",
                d2.toString() to "FORCE_HOLIDAY"
            )
        )
        val json = Moshi.Builder().build().adapter(BackupData::class.java).toJson(backupData)

        val result = backupManager.importFromUriStringForTest(
            json,
            BackupImportOptions(mode = BackupImportMode.Append, importSettings = false)
        )
        assertTrue("Import must succeed", result.isSuccess)

        // d1 conflict preserved: on-device FORCE_WORKDAY wins
        assertEquals(ManualHolidayOverride.FORCE_WORKDAY, overrideStore.overrideFor(d1))
        // d2 non-conflicting added
        assertEquals(ManualHolidayOverride.FORCE_HOLIDAY, overrideStore.overrideFor(d2))
    }

    @Test
    fun test4_v1_to_v22_import_preserves_existing_on_device_overrides() = runTest {
        // On-device has d1 -> FORCE_HOLIDAY
        overrideStore.setOverride(d1, ManualHolidayOverride.FORCE_HOLIDAY)

        // Older v22 backup without manualHolidayOverrides field
        val v22Backup = BackupData(
            version = 22,
            alarms = emptyList(),
            settings = null,
            manualHolidayOverrides = null
        )
        val json = Moshi.Builder().build().adapter(BackupData::class.java).toJson(v22Backup)

        val result = backupManager.importFromUriStringForTest(
            json,
            BackupImportOptions(mode = BackupImportMode.Replace, importSettings = false)
        )
        assertTrue("v22 import must succeed", result.isSuccess)

        // Existing on-device override must NOT be wiped
        assertEquals(ManualHolidayOverride.FORCE_HOLIDAY, overrideStore.overrideFor(d1))
    }

    @Test
    fun test5_malformed_iso_date_fails_before_mutation() = runTest {
        overrideStore.setOverride(d1, ManualHolidayOverride.FORCE_HOLIDAY)

        val badJson = """
            {
              "version": 23,
              "appVersion": "1.0",
              "exportedAt": 1000,
              "alarms": [],
              "manualHolidayOverrides": {
                "not-a-date": "FORCE_HOLIDAY"
              }
            }
        """.trimIndent()

        val result = backupManager.importFromUriStringForTest(
            badJson,
            BackupImportOptions(mode = BackupImportMode.Replace, importSettings = false)
        )
        assertTrue("Malformed date must fail import", result.isFailure)
        assertTrue(result.exceptionOrNull() is IllegalArgumentException)

        // On-device truth untouched
        assertEquals(ManualHolidayOverride.FORCE_HOLIDAY, overrideStore.overrideFor(d1))
    }

    @Test
    fun test6_unsupported_override_value_fails_before_mutation() = runTest {
        overrideStore.setOverride(d1, ManualHolidayOverride.FORCE_HOLIDAY)

        val badJson = """
            {
              "version": 23,
              "appVersion": "1.0",
              "exportedAt": 1000,
              "alarms": [],
              "manualHolidayOverrides": {
                "2026-12-25": "VACATION_DAY"
              }
            }
        """.trimIndent()

        val result = backupManager.importFromUriStringForTest(
            badJson,
            BackupImportOptions(mode = BackupImportMode.Replace, importSettings = false)
        )
        assertTrue("Unsupported override enum must fail import", result.isFailure)
        assertTrue(result.exceptionOrNull() is IllegalArgumentException)

        // On-device truth untouched
        assertEquals(ManualHolidayOverride.FORCE_HOLIDAY, overrideStore.overrideFor(d1))
    }

    @Test
    fun test7_payload_exceeding_cardinality_limit_fails_before_mutation() = runTest {
        overrideStore.setOverride(d1, ManualHolidayOverride.FORCE_HOLIDAY)

        val hugeMap = (0..2005).associate { i ->
            LocalDate.of(2026, 1, 1).plusDays(i.toLong()).toString() to "FORCE_HOLIDAY"
        }
        val hugeBackup = BackupData(
            version = 23,
            alarms = emptyList(),
            settings = null,
            manualHolidayOverrides = hugeMap
        )
        val json = Moshi.Builder().build().adapter(BackupData::class.java).toJson(hugeBackup)

        val result = backupManager.importFromUriStringForTest(
            json,
            BackupImportOptions(mode = BackupImportMode.Replace, importSettings = false)
        )
        assertTrue("Exceeding cardinality limit must fail import", result.isFailure)
        assertTrue(result.exceptionOrNull() is IllegalArgumentException)

        // On-device truth untouched
        assertEquals(ManualHolidayOverride.FORCE_HOLIDAY, overrideStore.overrideFor(d1))
    }

    @Test
    fun test8_future_version_fails_closed_and_preserves_overrides() = runTest {
        overrideStore.setOverride(d1, ManualHolidayOverride.FORCE_HOLIDAY)

        val futureBackup = """
            {
              "version": 24,
              "appVersion": "1.0",
              "exportedAt": 1000,
              "alarms": [],
              "manualHolidayOverrides": {
                "2026-12-25": "FORCE_WORKDAY"
              }
            }
        """.trimIndent()

        val result = backupManager.importFromUriStringForTest(
            futureBackup,
            BackupImportOptions(mode = BackupImportMode.Replace, importSettings = false)
        )
        assertTrue("Future backup version > 23 must fail", result.isFailure)

        // On-device truth untouched
        assertEquals(ManualHolidayOverride.FORCE_HOLIDAY, overrideStore.overrideFor(d1))
    }

    @Test
    fun test9_restore_transaction_journal_snapshots_manual_overrides_before_mutation() = runTest {
        val journalFile = File(context.filesDir, RestoreJournalCoordinator.JOURNAL_FILENAME)
        if (journalFile.exists()) journalFile.delete()

        overrideStore.setOverride(d1, ManualHolidayOverride.FORCE_HOLIDAY)
        val oldOverrides = overrideStore.getAllOverrides()
            .mapKeys { it.key.toString() }
            .mapValues { it.value.name }

        restoreJournalCoordinator.prepareTransaction(
            settings = AppSettings(),
            alarms = emptyList(),
            groups = emptyList(),
            manualOverrides = oldOverrides
        )

        assertTrue(journalFile.exists())
        val journalJson = org.json.JSONObject(journalFile.readText())
        assertEquals(RestoreJournalCoordinator.PHASE_PREPARED, journalJson.getString("phase"))

        val snapshotJson = journalJson.getString("snapshotJson")
        val snapshot = Moshi.Builder().build().adapter(RestoreSnapshot::class.java).fromJson(snapshotJson)
        assertNotNull(snapshot)
        assertNotNull(snapshot!!.manualOverrides)
        assertEquals("FORCE_HOLIDAY", snapshot.manualOverrides!![d1.toString()])
    }

    @Test
    fun test10_prepared_recovery_restores_old_manual_overrides_and_reconciles_old_truth() = runTest {
        val journalFile = File(context.filesDir, RestoreJournalCoordinator.JOURNAL_FILENAME)
        if (journalFile.exists()) journalFile.delete()

        // 1. Initial truth: d1 -> FORCE_HOLIDAY
        overrideStore.setOverride(d1, ManualHolidayOverride.FORCE_HOLIDAY)
        val oldOverrides = overrideStore.getAllOverrides()
            .mapKeys { it.key.toString() }
            .mapValues { it.value.name }

        // 2. Prepare transaction with old snapshot
        restoreJournalCoordinator.prepareTransaction(
            settings = AppSettings(),
            alarms = emptyList(),
            groups = emptyList(),
            manualOverrides = oldOverrides
        )

        // 3. Mutate store (simulating partially applied new truth during restore)
        overrideStore.setOverride(d1, ManualHolidayOverride.FORCE_WORKDAY)
        assertEquals(ManualHolidayOverride.FORCE_WORKDAY, overrideStore.overrideFor(d1))

        // 4. Recovery from PREPARED phase
        val outcome = restoreJournalCoordinator.checkAndRecover()
        assertEquals(RecoveryOutcome.RESTORED_OLD, outcome)

        // 5. Old truth must be restored
        assertEquals(ManualHolidayOverride.FORCE_HOLIDAY, overrideStore.overrideFor(d1))

        // 6. Old truth reconciled
        coVerify(atLeast = 1) {
            coordinator.reconcile(isMaterialChange = eq(true), any(), any(), any())
        }
        assertFalse("Journal file must be cleaned up after successful recovery", journalFile.exists())
    }

    @Test
    fun test11_committed_recovery_preserves_new_truth_and_reconciles_new_state() = runTest {
        val journalFile = File(context.filesDir, RestoreJournalCoordinator.JOURNAL_FILENAME)
        if (journalFile.exists()) journalFile.delete()

        // 1. Prepare transaction
        restoreJournalCoordinator.prepareTransaction(
            settings = AppSettings(),
            alarms = emptyList(),
            groups = emptyList(),
            manualOverrides = mapOf(d1.toString() to "FORCE_HOLIDAY")
        )

        // 2. Apply new truth
        overrideStore.setOverride(d1, ManualHolidayOverride.FORCE_WORKDAY)

        // 3. Mark COMMITTED
        restoreJournalCoordinator.markCommitted()

        // 4. Crash recovery in COMMITTED phase
        val outcome = restoreJournalCoordinator.checkAndRecover()
        assertEquals(RecoveryOutcome.RECOVERED_NEW, outcome)

        // 5. New truth preserved
        assertEquals(ManualHolidayOverride.FORCE_WORKDAY, overrideStore.overrideFor(d1))

        // 6. New state reconciled
        coVerify(atLeast = 1) {
            coordinator.reconcile(isMaterialChange = eq(true), any(), any(), any())
        }
        assertFalse("Journal file must be cleaned up after successful recovery", journalFile.exists())
    }

    @Test
    fun test12_tampered_journal_fails_closed_and_retains_journal() = runTest {
        val journalFile = File(context.filesDir, RestoreJournalCoordinator.JOURNAL_FILENAME)
        if (journalFile.exists()) journalFile.delete()

        restoreJournalCoordinator.prepareTransaction(
            settings = AppSettings(),
            alarms = emptyList(),
            groups = emptyList(),
            manualOverrides = mapOf(d1.toString() to "FORCE_HOLIDAY")
        )

        // Tamper checksum in journal
        val json = org.json.JSONObject(journalFile.readText())
        json.put("checksum", "tampered_bad_checksum_hash")
        journalFile.writeText(json.toString())

        try {
            restoreJournalCoordinator.checkAndRecover()
            fail("Tampered journal must throw SecurityException")
        } catch (e: SecurityException) {
            assertTrue(e.message!!.contains("checksum mismatch", ignoreCase = true))
        }

        assertTrue("Tampered journal must be retained on disk", journalFile.exists())
    }
}
