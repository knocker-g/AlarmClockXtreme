package com.sysadmindoc.alarmclock.data.backup

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.sysadmindoc.alarmclock.data.local.AlarmDatabase
import com.sysadmindoc.alarmclock.data.preferences.AppSettings
import com.sysadmindoc.alarmclock.data.preferences.PreferencesManager
import com.sysadmindoc.alarmclock.data.repository.AlarmRepository
import com.sysadmindoc.alarmclock.domain.AlarmScheduler
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class BackupManagerHardeningTest {
    private lateinit var context: Context
    private lateinit var repository: AlarmRepository
    private lateinit var preferencesManager: PreferencesManager
    private lateinit var scheduler: AlarmScheduler
    private lateinit var restoreJournalCoordinator: RestoreJournalCoordinator
    private lateinit var database: AlarmDatabase
    private lateinit var backupManager: BackupManager

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        database = Room.inMemoryDatabaseBuilder(context, AlarmDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repository = mockk(relaxed = true)
        preferencesManager = mockk(relaxed = true)
        scheduler = mockk(relaxed = true)
        restoreJournalCoordinator = mockk(relaxed = true)

        coEvery { preferencesManager.getCurrentSettings() } returns AppSettings()
        coEvery { repository.getAll() } returns emptyList()

        backupManager = BackupManager(
            context = context,
            repository = repository,
            preferencesManager = preferencesManager,
            scheduler = scheduler,
            restoreJournalCoordinator = restoreJournalCoordinator,
            database = database
        )
    }

    @After
    fun tearDown() {
        database.close()
        unmockkAll()
    }

    @Test
    fun activeSessionBlocksRestoreWithNoDataMutation() = runTest {
        coEvery { preferencesManager.getCurrentSettings() } returns AppSettings(
            activeAlarmId = 100L,
            activeAlarmState = "FIRING"
        )

        val json = """
            {
              "version": 19,
              "alarms": [
                {
                  "id": 1,
                  "hour": 7,
                  "minute": 0,
                  "label": "Test",
                  "isEnabled": true,
                  "repeatDays": [],
                  "ringtoneUri": "",
                  "vibrationEnabled": true,
                  "vibrationIntensity": 2,
                  "volume": 100,
                  "overrideSystemVolume": true,
                  "gradualVolumeSeconds": 60,
                  "snoozeDurationMinutes": 10,
                  "maxSnoozeCount": 3,
                  "showOnLockScreen": true,
                  "challengeType": "NONE"
                }
              ]
            }
        """.trimIndent()

        val result = backupManager.importFromUriStringForTest(json)
        assertTrue("Restore must fail during active session", result.isFailure)
        coVerify(exactly = 0) { restoreJournalCoordinator.prepareTransaction(any(), any(), any()) }
        coVerify(exactly = 0) { repository.restoreAlarmsTransaction(any(), any()) }
        coVerify(exactly = 0) { preferencesManager.update(any()) }
    }

    @Test
    fun fullStagingValidationFailsBeforeMutationOnInvalidRow() = runTest {
        val corruptJson = """
            {
              "version": 19,
              "alarms": [
                {
                  "id": 1,
                  "hour": "invalid_type",
                  "minute": 0,
                  "label": "Invalid hour type",
                  "isEnabled": true
                }
              ]
            }
        """.trimIndent()

        val result = backupManager.importFromUriStringForTest(corruptJson)
        assertTrue("Restore must fail on unparseable/invalid alarm row", result.isFailure)
        coVerify(exactly = 0) { restoreJournalCoordinator.prepareTransaction(any(), any(), any()) }
        coVerify(exactly = 0) { repository.restoreAlarmsTransaction(any(), any()) }
        coVerify(exactly = 0) { preferencesManager.update(any()) }
    }

    @Test
    fun prepareTransactionCalledBeforeMutationsAndCommittedCalledAfter() = runTest {
        val json = """
            {
              "version": 19,
              "alarms": [
                {
                  "id": 1,
                  "hour": 7,
                  "minute": 0,
                  "label": "Test",
                  "isEnabled": true,
                  "repeatDays": [],
                  "ringtoneUri": "",
                  "vibrationEnabled": true,
                  "vibrationIntensity": 2,
                  "volume": 100,
                  "overrideSystemVolume": true,
                  "gradualVolumeSeconds": 60,
                  "snoozeDurationMinutes": 10,
                  "maxSnoozeCount": 3,
                  "showOnLockScreen": true,
                  "challengeType": "NONE"
                }
              ]
            }
        """.trimIndent()

        val result = backupManager.importFromUriStringForTest(json)
        assertTrue("Import must succeed", result.isSuccess)

        coVerifyOrder {
            restoreJournalCoordinator.prepareTransaction(any(), any(), any())
            repository.restoreAlarmsTransaction(any(), any())
            restoreJournalCoordinator.markCommitted()
        }
    }

    @Test
    fun unportableMediaReferencesArePreservedLosslessly() = runTest {
        val unportableUri = "content://com.external.app.provider/audio/123"
        val json = """
            {
              "version": 19,
              "alarms": [
                {
                  "id": 1,
                  "hour": 7,
                  "minute": 0,
                  "label": "Test Media",
                  "isEnabled": true,
                  "repeatDays": [],
                  "ringtoneUri": "$unportableUri",
                  "vibrationEnabled": true,
                  "vibrationIntensity": 2,
                  "volume": 100,
                  "overrideSystemVolume": true,
                  "gradualVolumeSeconds": 60,
                  "snoozeDurationMinutes": 10,
                  "maxSnoozeCount": 3,
                  "showOnLockScreen": true,
                  "challengeType": "NONE"
                }
              ]
            }
        """.trimIndent()

        val result = backupManager.importFromUriStringForTest(json)
        assertTrue("Import must succeed", result.isSuccess)

        coVerify {
            repository.restoreAlarmsTransaction(
                match { list -> list.any { it.ringtoneUri == unportableUri } },
                any()
            )
        }
    }

    @Test
    fun sanitizedImportClearsInternetRadioUrl() = runTest {
        val json = """
            {
              "version": 19,
              "alarms": [
                {
                  "id": 1,
                  "hour": 7,
                  "minute": 0,
                  "label": "Radio Alarm",
                  "isEnabled": true,
                  "repeatDays": [],
                  "ringtoneUri": "",
                  "vibrationEnabled": true,
                  "vibrationIntensity": 2,
                  "volume": 100,
                  "overrideSystemVolume": true,
                  "gradualVolumeSeconds": 60,
                  "snoozeDurationMinutes": 10,
                  "maxSnoozeCount": 3,
                  "showOnLockScreen": true,
                  "challengeType": "NONE",
                  "internetRadioUrl": "https://stream.example.org/radio.mp3"
                }
              ]
            }
        """.trimIndent()

        val result = backupManager.importFromUriStringForTest(json, BackupImportOptions(keepIntegrationsAndContacts = false))
        assertTrue("Import must succeed", result.isSuccess)

        coVerify {
            repository.restoreAlarmsTransaction(
                match { list -> list.all { it.internetRadioUrl.isEmpty() } },
                any()
            )
        }
    }

    @Test
    fun reconcileFailureAfterCommittedDoesNotFailRestore() = runTest {
        coEvery { scheduler.rescheduleAllInBatches(any(), any()) } throws RuntimeException("Scheduler error")

        val json = """
            {
              "version": 19,
              "alarms": []
            }
        """.trimIndent()

        val result = backupManager.importFromUriStringForTest(json)
        assertTrue("Restore must remain successful even if post-commit reconcile fails", result.isSuccess)
        coVerify { restoreJournalCoordinator.markCommitted() }
    }

    @Test
    fun sanitizedImportPreservesDestinationCustomNewsFeedUrl() = runTest {
        val destSettings = AppSettings(newsFeedUrl = "https://destination.example/rss.xml")
        coEvery { preferencesManager.getCurrentSettings() } returns destSettings

        val exportJson = backupManager.export()

        var appliedSettings: AppSettings? = null
        coEvery { preferencesManager.update(any()) } coAnswers {
            val transform = firstArg<(AppSettings) -> AppSettings>()
            appliedSettings = transform(destSettings)
        }

        val result = backupManager.importFromUriStringForTest(
            exportJson,
            BackupImportOptions(importSettings = true, keepIntegrationsAndContacts = false)
        )
        assertTrue("Import must succeed", result.isSuccess)
        assertEquals("https://destination.example/rss.xml", appliedSettings?.newsFeedUrl)
    }

    @Test
    fun trustedImportRestoresSourceNewsFeedUrl() = runTest {
        val destSettings = AppSettings(newsFeedUrl = "https://destination.example/rss.xml")
        coEvery { preferencesManager.getCurrentSettings() } returns destSettings

        val exportJson = backupManager.export()

        var appliedSettings: AppSettings? = null
        coEvery { preferencesManager.update(any()) } coAnswers {
            val transform = firstArg<(AppSettings) -> AppSettings>()
            appliedSettings = transform(destSettings)
        }

        val result = backupManager.importFromUriStringForTest(
            exportJson,
            BackupImportOptions(importSettings = true, keepIntegrationsAndContacts = true)
        )
        assertTrue("Import must succeed", result.isSuccess)
        assertEquals("https://destination.example/rss.xml", appliedSettings?.newsFeedUrl)
    }

    @Test
    fun exportExcludesDeviceLocalFields() = runTest {
        val sourceSettings = AppSettings(
            pauseUntilMillis = 999_999L,
            lastKnownLatitude = 35.68,
            lastKnownLongitude = 139.76
        )
        coEvery { preferencesManager.getCurrentSettings() } returns sourceSettings

        val json = backupManager.export()
        assertFalse("Exported JSON must not leak pauseUntilMillis", json.contains("\"pauseUntilMillis\":999999"))
        assertFalse("Exported JSON must not leak lastKnownLatitude", json.contains("\"lastKnownLatitude\":35.68"))
        assertFalse("Exported JSON must not leak lastKnownLongitude", json.contains("\"lastKnownLongitude\":139.76"))
    }
}
