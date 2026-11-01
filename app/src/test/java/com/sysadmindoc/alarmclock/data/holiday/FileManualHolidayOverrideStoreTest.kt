package com.sysadmindoc.alarmclock.data.holiday

import android.content.Context
import com.sysadmindoc.alarmclock.data.preferences.AppSettings
import com.sysadmindoc.alarmclock.data.preferences.PreferencesManager
import com.sysadmindoc.alarmclock.data.remote.HolidayApi
import com.sysadmindoc.alarmclock.data.repository.HolidayClassificationKind
import com.sysadmindoc.alarmclock.data.repository.HolidayClassificationSource
import com.sysadmindoc.alarmclock.data.repository.HolidayProviderSnapshot
import com.sysadmindoc.alarmclock.data.repository.HolidayRepository
import com.sysadmindoc.alarmclock.data.repository.ManualHolidayOverride
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.time.LocalDate

class FileManualHolidayOverrideStoreTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val d1 = LocalDate.of(2026, 12, 23)
    private val d2 = LocalDate.of(2026, 12, 24)
    private val d3 = LocalDate.of(2026, 12, 25)

    @Test
    fun testA_FORCE_HOLIDAY_persists_and_reloads() = runTest {
        val file = File(tempFolder.root, "overrides_a.json")
        val store1 = FileManualHolidayOverrideStore(file)

        store1.setOverride(d3, ManualHolidayOverride.FORCE_HOLIDAY)
        assertEquals(ManualHolidayOverride.FORCE_HOLIDAY, store1.overrideFor(d3))
        assertEquals(mapOf(d3 to ManualHolidayOverride.FORCE_HOLIDAY), store1.getAllOverrides())

        // Reload fresh instance pointing to same file
        val store2 = FileManualHolidayOverrideStore(file)
        assertEquals(ManualHolidayOverride.FORCE_HOLIDAY, store2.overrideFor(d3))
        assertEquals(mapOf(d3 to ManualHolidayOverride.FORCE_HOLIDAY), store2.getAllOverrides())
    }

    @Test
    fun testB_FORCE_WORKDAY_persists_and_reloads() = runTest {
        val file = File(tempFolder.root, "overrides_b.json")
        val store1 = FileManualHolidayOverrideStore(file)

        store1.setOverride(d2, ManualHolidayOverride.FORCE_WORKDAY)
        assertEquals(ManualHolidayOverride.FORCE_WORKDAY, store1.overrideFor(d2))
        assertEquals(mapOf(d2 to ManualHolidayOverride.FORCE_WORKDAY), store1.getAllOverrides())

        val store2 = FileManualHolidayOverrideStore(file)
        assertEquals(ManualHolidayOverride.FORCE_WORKDAY, store2.overrideFor(d2))
        assertEquals(mapOf(d2 to ManualHolidayOverride.FORCE_WORKDAY), store2.getAllOverrides())
    }

    @Test
    fun testC_AUTO_removes_override() = runTest {
        val file = File(tempFolder.root, "overrides_c.json")
        val store = FileManualHolidayOverrideStore(file)

        store.setOverride(d3, ManualHolidayOverride.FORCE_HOLIDAY)
        assertEquals(ManualHolidayOverride.FORCE_HOLIDAY, store.overrideFor(d3))

        store.setOverride(d3, ManualHolidayOverride.AUTO)
        assertEquals(ManualHolidayOverride.AUTO, store.overrideFor(d3))
        assertTrue(store.getAllOverrides().isEmpty())

        val reloaded = FileManualHolidayOverrideStore(file)
        assertEquals(ManualHolidayOverride.AUTO, reloaded.overrideFor(d3))
        assertTrue(reloaded.getAllOverrides().isEmpty())
    }

    @Test
    fun testD_date_range_mutation_is_atomic() = runTest {
        val file = File(tempFolder.root, "overrides_d.json")
        val store = FileManualHolidayOverrideStore(file)

        val result = store.setRange(
            startDate = d1,
            endDateInclusive = d3,
            override = ManualHolidayOverride.FORCE_HOLIDAY,
            overwriteExisting = false
        )

        assertTrue(result is RangeMutationResult.Applied)
        assertEquals(3, (result as RangeMutationResult.Applied).affectedDatesCount)

        val snapshot = store.snapshotOverrides(d1, d3)
        assertEquals(3, snapshot.size)
        assertEquals(ManualHolidayOverride.FORCE_HOLIDAY, snapshot[d1])
        assertEquals(ManualHolidayOverride.FORCE_HOLIDAY, snapshot[d2])
        assertEquals(ManualHolidayOverride.FORCE_HOLIDAY, snapshot[d3])

        // Verify reloaded from disk matches
        val reloaded = FileManualHolidayOverrideStore(file)
        assertEquals(3, reloaded.getAllOverrides().size)
    }

    @Test
    fun testE_range_conflict_prevents_partial_changes() = runTest {
        val file = File(tempFolder.root, "overrides_e.json")
        val store = FileManualHolidayOverrideStore(file)

        // Set d2 to FORCE_WORKDAY
        store.setOverride(d2, ManualHolidayOverride.FORCE_WORKDAY)

        // Attempt to set range d1..d3 to FORCE_HOLIDAY without overwriteExisting
        val result = store.setRange(
            startDate = d1,
            endDateInclusive = d3,
            override = ManualHolidayOverride.FORCE_HOLIDAY,
            overwriteExisting = false
        )

        assertTrue(result is RangeMutationResult.Conflict)
        assertEquals(listOf(d2), (result as RangeMutationResult.Conflict).conflictingDates)

        // Verify zero changes occurred to d1, d2, d3
        assertEquals(ManualHolidayOverride.AUTO, store.overrideFor(d1))
        assertEquals(ManualHolidayOverride.FORCE_WORKDAY, store.overrideFor(d2))
        assertEquals(ManualHolidayOverride.AUTO, store.overrideFor(d3))

        val reloaded = FileManualHolidayOverrideStore(file)
        assertEquals(ManualHolidayOverride.AUTO, reloaded.overrideFor(d1))
        assertEquals(ManualHolidayOverride.FORCE_WORKDAY, reloaded.overrideFor(d2))
        assertEquals(ManualHolidayOverride.AUTO, reloaded.overrideFor(d3))
    }

    @Test
    fun testF_explicit_overwrite_applies_all_selected_dates() = runTest {
        val file = File(tempFolder.root, "overrides_f.json")
        val store = FileManualHolidayOverrideStore(file)

        store.setOverride(d2, ManualHolidayOverride.FORCE_WORKDAY)

        val result = store.setRange(
            startDate = d1,
            endDateInclusive = d3,
            override = ManualHolidayOverride.FORCE_HOLIDAY,
            overwriteExisting = true
        )

        assertTrue(result is RangeMutationResult.Applied)
        assertEquals(3, (result as RangeMutationResult.Applied).affectedDatesCount)

        assertEquals(ManualHolidayOverride.FORCE_HOLIDAY, store.overrideFor(d1))
        assertEquals(ManualHolidayOverride.FORCE_HOLIDAY, store.overrideFor(d2))
        assertEquals(ManualHolidayOverride.FORCE_HOLIDAY, store.overrideFor(d3))

        val reloaded = FileManualHolidayOverrideStore(file)
        assertEquals(ManualHolidayOverride.FORCE_HOLIDAY, reloaded.overrideFor(d2))
    }

    @Test
    fun testG_concurrent_mutations_do_not_lose_updates() = runTest {
        val file = File(tempFolder.root, "overrides_g.json")
        val store = FileManualHolidayOverrideStore(file)

        val count = 20
        val baseDate = LocalDate.of(2026, 1, 1)

        val deferreds = (0 until count).map { i ->
            async(kotlinx.coroutines.Dispatchers.Default) {
                val date = baseDate.plusDays(i.toLong())
                val override = if (i % 2 == 0) ManualHolidayOverride.FORCE_HOLIDAY else ManualHolidayOverride.FORCE_WORKDAY
                store.setOverride(date, override)
            }
        }
        deferreds.awaitAll()

        val all = store.getAllOverrides()
        assertEquals(count, all.size)

        val reloaded = FileManualHolidayOverrideStore(file)
        assertEquals(count, reloaded.getAllOverrides().size)
        for (i in 0 until count) {
            val date = baseDate.plusDays(i.toLong())
            val expected = if (i % 2 == 0) ManualHolidayOverride.FORCE_HOLIDAY else ManualHolidayOverride.FORCE_WORKDAY
            assertEquals(expected, reloaded.overrideFor(date))
        }
    }

    @Test
    fun testH_failed_file_write_preserves_previous_truth() = runTest {
        val file = File(tempFolder.root, "overrides_h.json")
        val store = FileManualHolidayOverrideStore(file)

        store.setOverride(d1, ManualHolidayOverride.FORCE_HOLIDAY)
        assertEquals(ManualHolidayOverride.FORCE_HOLIDAY, store.overrideFor(d1))

        // Create a directory at file.new to cause atomicFile.startWrite() to fail
        val newFileDir = File(file.path + ".new")
        assertTrue(newFileDir.mkdir())

        try {
            assertThrows(Exception::class.java) {
                kotlinx.coroutines.runBlocking {
                    store.setOverride(d2, ManualHolidayOverride.FORCE_WORKDAY)
                }
            }

            // Previous truth is completely preserved
            assertEquals(ManualHolidayOverride.FORCE_HOLIDAY, store.overrideFor(d1))
            assertEquals(ManualHolidayOverride.AUTO, store.overrideFor(d2))
            assertEquals(mapOf(d1 to ManualHolidayOverride.FORCE_HOLIDAY), store.getAllOverrides())
        } finally {
            newFileDir.delete()
        }
    }

    @Test
    fun testI_corrupted_JSON_fails_closed() = runTest {
        val file = tempFolder.newFile("corrupted.json")
        file.writeText("{ this is definitely not valid json !!!")

        val store = FileManualHolidayOverrideStore(file)

        assertTrue(store.isCorrupted)
        assertNotNull(store.corruptionCause)

        // Verify forensic backup copy was created
        val backups = file.parentFile?.listFiles { f -> f.name.startsWith("${file.name}.corrupt.") }
        assertNotNull(backups)
        assertTrue(backups!!.isNotEmpty())

        // Verify fails closed
        assertThrows(CorruptedManualHolidayAuthorityException::class.java) {
            store.overrideFor(d1)
        }
        assertThrows(CorruptedManualHolidayAuthorityException::class.java) {
            store.snapshotOverrides(d1, d3)
        }
        assertThrows(CorruptedManualHolidayAuthorityException::class.java) {
            kotlinx.coroutines.runBlocking { store.getAllOverrides() }
        }
        assertThrows(CorruptedManualHolidayAuthorityException::class.java) {
            kotlinx.coroutines.runBlocking { store.setOverride(d1, ManualHolidayOverride.FORCE_HOLIDAY) }
        }
        assertThrows(CorruptedManualHolidayAuthorityException::class.java) {
            kotlinx.coroutines.runBlocking { store.setRange(d1, d3, ManualHolidayOverride.FORCE_HOLIDAY, false) }
        }

        // Verify recovery clears corruption state
        store.recoverFromCorruption(
            clearCorruptedFile = true,
            initialOverrides = mapOf(d1 to ManualHolidayOverride.FORCE_HOLIDAY)
        )
        assertFalse(store.isCorrupted)
        assertEquals(ManualHolidayOverride.FORCE_HOLIDAY, store.overrideFor(d1))
    }

    @Test
    fun testJ_invalid_date_or_enum_rejects_entire_payload() {
        // Test invalid enum
        val file1 = tempFolder.newFile("invalid_enum.json")
        file1.writeText("""
            {
              "formatVersion": 1,
              "updatedAtMillis": 1000,
              "overrides": {
                "2026-12-25": "INVALID_ENUM_NAME"
              }
            }
        """.trimIndent())

        val store1 = FileManualHolidayOverrideStore(file1)
        assertTrue(store1.isCorrupted)
        assertThrows(CorruptedManualHolidayAuthorityException::class.java) {
            store1.overrideFor(d3)
        }

        // Test invalid date format
        val file2 = tempFolder.newFile("invalid_date.json")
        file2.writeText("""
            {
              "formatVersion": 1,
              "updatedAtMillis": 1000,
              "overrides": {
                "not-a-real-date": "FORCE_HOLIDAY"
              }
            }
        """.trimIndent())

        val store2 = FileManualHolidayOverrideStore(file2)
        assertTrue(store2.isCorrupted)
        assertThrows(CorruptedManualHolidayAuthorityException::class.java) {
            store2.overrideFor(d3)
        }

        // Test missing formatVersion
        val file3 = tempFolder.newFile("no_version.json")
        file3.writeText("""
            {
              "overrides": {
                "2026-12-25": "FORCE_HOLIDAY"
              }
            }
        """.trimIndent())

        val store3 = FileManualHolidayOverrideStore(file3)
        assertTrue(store3.isCorrupted)
    }

    @Test
    fun testK_production_HolidayRepository_uses_actual_manual_authority_not_NONE() = runTest {
        val file = File(tempFolder.root, "overrides_k.json")
        val store = FileManualHolidayOverrideStore(file)
        store.setOverride(d3, ManualHolidayOverride.FORCE_HOLIDAY)

        val mockContext = mockk<Context>(relaxed = true)
        val mockFilesDir = tempFolder.newFolder("files_dir")
        every { mockContext.filesDir } returns mockFilesDir

        val mockApi = mockk<HolidayApi>()
        val mockPrefs = mockk<PreferencesManager>()
        coEvery { mockPrefs.getCurrentSettings() } returns AppSettings(holidayCountryCode = "JP")

        val repo = HolidayRepository(
            context = mockContext,
            holidayApi = mockApi,
            preferencesManager = mockPrefs,
            manualOverrideSource = store
        )

        val window = repo.captureAuthorityWindow(d1, d3)
        assertEquals(ManualHolidayOverride.FORCE_HOLIDAY, window.manualOverrides[d3])
        assertEquals(ManualHolidayOverride.FORCE_HOLIDAY, window.classify(d3).let {
            assertEquals(HolidayClassificationKind.HOLIDAY, it.kind)
            assertEquals(HolidayClassificationSource.MANUAL_OVERRIDE, it.source)
            ManualHolidayOverride.FORCE_HOLIDAY
        })
    }

    @Test
    fun testL_manual_change_alters_captured_classification() = runTest {
        val file = File(tempFolder.root, "overrides_l.json")
        val store = FileManualHolidayOverrideStore(file)

        val mockContext = mockk<Context>(relaxed = true)
        val mockFilesDir = tempFolder.newFolder("files_dir_l")
        every { mockContext.filesDir } returns mockFilesDir

        val mockApi = mockk<HolidayApi>()
        val mockPrefs = mockk<PreferencesManager>()
        coEvery { mockPrefs.getCurrentSettings() } returns AppSettings(holidayCountryCode = "JP")

        val repo = HolidayRepository(
            context = mockContext,
            holidayApi = mockApi,
            preferencesManager = mockPrefs,
            manualOverrideSource = store
        )

        // Initially no overrides; country JP without snapshot yields UNKNOWN (NO_VALID_SNAPSHOT)
        val initialClassify = repo.classify(d3)
        assertEquals(HolidayClassificationKind.UNKNOWN, initialClassify.kind)

        // Set manual override to FORCE_HOLIDAY
        store.setOverride(d3, ManualHolidayOverride.FORCE_HOLIDAY)
        val holidayClassify = repo.classify(d3)
        assertEquals(HolidayClassificationKind.HOLIDAY, holidayClassify.kind)
        assertEquals(HolidayClassificationSource.MANUAL_OVERRIDE, holidayClassify.source)

        // Set manual override to FORCE_WORKDAY
        store.setOverride(d3, ManualHolidayOverride.FORCE_WORKDAY)
        val workdayClassify = repo.classify(d3)
        assertEquals(HolidayClassificationKind.WORKDAY, workdayClassify.kind)
        assertEquals(HolidayClassificationSource.MANUAL_OVERRIDE, workdayClassify.source)
    }

    @Test
    fun testM_process_restart_reloads_durable_overrides() = runTest {
        val file = File(tempFolder.root, "overrides_restart.json")
        val store1 = FileManualHolidayOverrideStore(file)

        store1.setOverride(d1, ManualHolidayOverride.FORCE_HOLIDAY)
        store1.setOverride(d2, ManualHolidayOverride.FORCE_WORKDAY)

        // Simulate new process instance with fresh memory state
        val store2 = FileManualHolidayOverrideStore(file)
        assertEquals(ManualHolidayOverride.FORCE_HOLIDAY, store2.overrideFor(d1))
        assertEquals(ManualHolidayOverride.FORCE_WORKDAY, store2.overrideFor(d2))
        assertEquals(ManualHolidayOverride.AUTO, store2.overrideFor(d3))

        val map = store2.getAllOverrides()
        assertEquals(2, map.size)
        assertEquals(ManualHolidayOverride.FORCE_HOLIDAY, map[d1])
        assertEquals(ManualHolidayOverride.FORCE_WORKDAY, map[d2])
    }

    @Test
    fun testN_corrupted_authority_never_silently_becomes_provider_WORKDAY_or_AUTO() = runTest {
        val file = tempFolder.newFile("corrupt_n.json")
        file.writeText("{ corrupted json payload")

        val store = FileManualHolidayOverrideStore(file)
        assertTrue(store.isCorrupted)

        val mockContext = mockk<Context>(relaxed = true)
        val mockFilesDir = tempFolder.newFolder("files_dir_n")
        every { mockContext.filesDir } returns mockFilesDir

        val mockApi = mockk<HolidayApi>()
        val mockPrefs = mockk<PreferencesManager>()
        coEvery { mockPrefs.getCurrentSettings() } returns AppSettings(holidayCountryCode = "JP")

        val repo = HolidayRepository(
            context = mockContext,
            holidayApi = mockApi,
            preferencesManager = mockPrefs,
            manualOverrideSource = store
        )

        // Verifies that unreadable manual authority fails closed and NEVER returns AUTO or WORKDAY
        assertThrows(CorruptedManualHolidayAuthorityException::class.java) {
            kotlinx.coroutines.runBlocking { repo.classify(d1) }
        }
        assertThrows(CorruptedManualHolidayAuthorityException::class.java) {
            kotlinx.coroutines.runBlocking { repo.captureAuthorityWindow(d1, d3) }
        }
    }

    @Test
    fun testO_zero_byte_file_fails_closed() = runTest {
        val file = tempFolder.newFile("zero_byte.json")
        assertEquals(0L, file.length())
        assertTrue(file.exists())

        val store = FileManualHolidayOverrideStore(file)
        assertTrue("Store must be marked corrupted for existing 0-byte file", store.isCorrupted)
        assertNotNull("Corruption cause must be captured", store.corruptionCause)
        assertTrue(store.corruptionCause is CorruptedManualHolidayAuthorityException)

        // Verify forensic backup copy was created
        val backups = file.parentFile?.listFiles { f -> f.name.startsWith("${file.name}.corrupt.") }
        assertNotNull(backups)
        assertTrue(backups!!.isNotEmpty())

        // Verify all read and write operations fail closed
        assertThrows(CorruptedManualHolidayAuthorityException::class.java) {
            store.overrideFor(d1)
        }
        assertThrows(CorruptedManualHolidayAuthorityException::class.java) {
            store.snapshotOverrides(d1, d3)
        }
        assertThrows(CorruptedManualHolidayAuthorityException::class.java) {
            kotlinx.coroutines.runBlocking { store.getAllOverrides() }
        }
        assertThrows(CorruptedManualHolidayAuthorityException::class.java) {
            kotlinx.coroutines.runBlocking { store.setOverride(d1, ManualHolidayOverride.FORCE_HOLIDAY) }
        }
        assertThrows(CorruptedManualHolidayAuthorityException::class.java) {
            kotlinx.coroutines.runBlocking { store.setRange(d1, d3, ManualHolidayOverride.FORCE_HOLIDAY, false) }
        }
    }

    @Test
    fun testP_missing_file_is_clean_empty_state() = runTest {
        val file = File(tempFolder.root, "clean_missing.json")
        assertFalse("File must not exist initially", file.exists())

        val store = FileManualHolidayOverrideStore(file)
        assertFalse("Store must not be corrupted for missing file", store.isCorrupted)
        assertNull("Corruption cause must be null for missing file", store.corruptionCause)
        assertEquals(ManualHolidayOverride.AUTO, store.overrideFor(d1))
        assertTrue(store.getAllOverrides().isEmpty())
    }
}
