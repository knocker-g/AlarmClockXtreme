package com.sysadmindoc.alarmclock.data.backup

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.sysadmindoc.alarmclock.data.local.AlarmDatabase
import com.sysadmindoc.alarmclock.data.local.entity.IssueSlot
import com.sysadmindoc.alarmclock.data.local.entity.ScheduleAuthority
import com.sysadmindoc.alarmclock.data.local.entity.ScheduleAuthorityMeta
import com.sysadmindoc.alarmclock.data.model.Alarm
import com.sysadmindoc.alarmclock.data.preferences.AppSettings
import com.sysadmindoc.alarmclock.data.preferences.PreferencesManager
import com.sysadmindoc.alarmclock.data.repository.AlarmRepository
import com.sysadmindoc.alarmclock.domain.AlarmScheduler
import com.sysadmindoc.alarmclock.domain.ScheduleAuthorityBootstrapAdapter
import com.squareup.moshi.Moshi
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.mockk
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
        runBlocking {
            database.scheduleAuthorityDao().insertMeta(
                ScheduleAuthorityMeta(
                    id = 1,
                    restoreEpoch = 100L,
                    bootstrapState = "BOOTSTRAPPED"
                )
            )
        }
        repository = mockk(relaxed = true)
        preferencesManager = mockk(relaxed = true)
        scheduler = mockk(relaxed = true)
        restoreJournalCoordinator = mockk(relaxed = true)

        coEvery { preferencesManager.getCurrentSettings() } returns AppSettings()
        coEvery { repository.getAll() } returns emptyList()

        val jFile = java.io.File(context.filesDir, RestoreJournalCoordinator.JOURNAL_FILENAME)
        if (jFile.exists()) jFile.delete()
        val bFile = java.io.File(context.filesDir, "${RestoreJournalCoordinator.JOURNAL_FILENAME}.bak")
        if (bFile.exists()) bFile.delete()

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
        val jFile = java.io.File(context.filesDir, RestoreJournalCoordinator.JOURNAL_FILENAME)
        if (jFile.exists()) jFile.delete()
        val bFile = java.io.File(context.filesDir, "${RestoreJournalCoordinator.JOURNAL_FILENAME}.bak")
        if (bFile.exists()) bFile.delete()
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
        val sourceSettings = AppSettings(newsFeedUrl = "https://source.example/rss.xml")
        val destSettings = AppSettings(newsFeedUrl = "https://destination.example/rss.xml")

        coEvery { preferencesManager.getCurrentSettings() } returns sourceSettings
        val sourceJson = backupManager.export()

        coEvery { preferencesManager.getCurrentSettings() } returns destSettings

        var appliedSettings: AppSettings? = null
        coEvery { preferencesManager.update(any()) } coAnswers {
            val transform = firstArg<(AppSettings) -> AppSettings>()
            appliedSettings = transform(destSettings)
        }

        val result = backupManager.importFromUriStringForTest(
            sourceJson,
            BackupImportOptions(importSettings = true, keepIntegrationsAndContacts = false)
        )
        assertTrue("Import must succeed", result.isSuccess)
        assertEquals(
            "Sanitized import must retain destination's custom newsFeedUrl",
            "https://destination.example/rss.xml",
            appliedSettings?.newsFeedUrl
        )
    }

    @Test
    fun trustedImportRestoresSourceNewsFeedUrl() = runTest {
        val sourceSettings = AppSettings(newsFeedUrl = "https://source.example/rss.xml")
        val destSettings = AppSettings(newsFeedUrl = "https://destination.example/rss.xml")

        coEvery { preferencesManager.getCurrentSettings() } returns sourceSettings
        val sourceJson = backupManager.export()

        coEvery { preferencesManager.getCurrentSettings() } returns destSettings

        var appliedSettings: AppSettings? = null
        coEvery { preferencesManager.update(any()) } coAnswers {
            val transform = firstArg<(AppSettings) -> AppSettings>()
            appliedSettings = transform(destSettings)
        }

        val result = backupManager.importFromUriStringForTest(
            sourceJson,
            BackupImportOptions(importSettings = true, keepIntegrationsAndContacts = true)
        )
        assertTrue("Import must succeed", result.isSuccess)
        assertEquals(
            "Trusted import must restore source's custom newsFeedUrl",
            "https://source.example/rss.xml",
            appliedSettings?.newsFeedUrl
        )
    }

    @Test
    fun exportExcludesDeviceLocalFields() = runTest {
        val sourceSettings = AppSettings(
            pauseUntilMillis = 999_999L,
            lastKnownLatitude = 35.68,
            lastKnownLongitude = 139.76,
            locationName = "Dallas, Texas",
            useManualLocation = true
        )
        coEvery { preferencesManager.getCurrentSettings() } returns sourceSettings

        val json = backupManager.export()

        val moshi = Moshi.Builder().build()
        val adapter = moshi.adapter(BackupData::class.java)
        val backupData = adapter.fromJson(json)

        assertNotNull("Exported BackupData must parse successfully", backupData)
        val settingsBackup = backupData?.settings
        assertNotNull("SettingsBackup must be present in export", settingsBackup)

        assertEquals("pauseUntilMillis must be excluded (set to 0L)", 0L, settingsBackup?.pauseUntilMillis)
        assertEquals("lastKnownLatitude must be excluded (set to 0.0)", 0.0, settingsBackup?.lastKnownLatitude ?: -1.0, 0.001)
        assertEquals("lastKnownLongitude must be excluded (set to 0.0)", 0.0, settingsBackup?.lastKnownLongitude ?: -1.0, 0.001)

        assertEquals("locationName must be preserved", "Dallas, Texas", settingsBackup?.locationName)
        assertEquals("useManualLocation must be preserved", true, settingsBackup?.useManualLocation)
    }

    @Test
    fun preCommittedSettingsWriteFailureTriggersSynchronousOldTruthRollback() = runTest {
        coEvery { preferencesManager.update(any()) } throws RuntimeException("Settings write failed")

        val exportJson = backupManager.export()

        val result = backupManager.importFromUriStringForTest(exportJson, BackupImportOptions(importSettings = true))
        assertTrue("Restore must fail when pre-COMMITTED settings write fails", result.isFailure)

        coVerifyOrder {
            restoreJournalCoordinator.prepareTransaction(any(), any(), any())
            repository.restoreAlarmsTransaction(any(), any())
            restoreJournalCoordinator.checkAndRecover()
        }
        coVerify(exactly = 0) { restoreJournalCoordinator.markCommitted() }
    }

    @Test
    fun realRestoreJournalCoordinatorSynchronousOldTruthRollbackTest() = runTest {
        val realCoordinator = RestoreJournalCoordinator(
            context = context,
            database = database,
            preferencesManager = preferencesManager,
            alarmScheduler = scheduler,
            moshi = Moshi.Builder().build()
        )

        val realBackupManager = BackupManager(
            context = context,
            repository = repository,
            preferencesManager = preferencesManager,
            scheduler = scheduler,
            restoreJournalCoordinator = realCoordinator,
            database = database
        )

        val oldAlarm = Alarm(id = 123L, hour = 8, minute = 30, label = "OLD Alarm", group = "Work")
        val oldGroup1 = com.sysadmindoc.alarmclock.data.local.entity.AlarmGroup(name = "Work")
        val oldGroup2 = com.sysadmindoc.alarmclock.data.local.entity.AlarmGroup(name = "Gym") // Standalone/orphan group
        val oldSettings = AppSettings(newsFeedUrl = "https://destination.example/rss.xml")

        database.alarmDao().insert(oldAlarm)
        database.alarmGroupDao().insert(oldGroup1)
        database.alarmGroupDao().insert(oldGroup2)

        var currentPersistedSettings = oldSettings
        coEvery { preferencesManager.getCurrentSettings() } coAnswers { currentPersistedSettings }
        coEvery { repository.getAll() } coAnswers { database.alarmDao().getAll() }

        // Simulate a failure during NEW settings write (after Room NEW mutation)
        var settingsUpdateCount = 0
        coEvery { preferencesManager.update(any()) } coAnswers {
            settingsUpdateCount++
            val transform = firstArg<(AppSettings) -> AppSettings>()
            if (settingsUpdateCount == 1) {
                // Apply NEW settings to mutable test state, then throw pre-COMMITTED error
                currentPersistedSettings = transform(currentPersistedSettings)
                throw RuntimeException("Settings write failed pre-COMMITTED")
            } else {
                // Rollback update (restore OLD settings)
                currentPersistedSettings = transform(currentPersistedSettings)
            }
        }

        // Mock repository.restoreAlarmsTransaction to perform actual DB replace
        coEvery { repository.restoreAlarmsTransaction(any(), eq(true)) } coAnswers {
            val staged = firstArg<List<Alarm>>()
            database.alarmDao().deleteAll()
            if (staged.isNotEmpty()) database.alarmDao().insertAll(staged)
            staged.map { it.id }
        }

        // Verify scheduler sees ONLY OLD Alarm truth during rollback reschedule (Gap 4)
        coEvery { scheduler.rescheduleAllInBatches(any(), any()) } coAnswers {
            val currentAlarmsInDb = database.alarmDao().getAll()
            assertEquals("Scheduler during rollback must see exact 1 OLD Alarm", 1, currentAlarmsInDb.size)
            assertEquals("OLD Alarm ID must match exact OLD id", 123L, currentAlarmsInDb[0].id)
            assertEquals("OLD Alarm label must match exact OLD label", "OLD Alarm", currentAlarmsInDb[0].label)
            assertTrue("Scheduler during rollback must NOT see transient NEW Alarm", currentAlarmsInDb.none { it.id == 999L || it.label == "NEW Transient Alarm" })
            com.sysadmindoc.alarmclock.domain.ScheduleOutcome.BatchResult(0, 0, 0, 0, emptyMap())
        }

        val newJson = """
            {
              "version": 19,
              "alarms": [
                {
                  "id": 999,
                  "hour": 9,
                  "minute": 0,
                  "label": "NEW Transient Alarm",
                  "isEnabled": true,
                  "repeatDays": [],
                  "ringtoneUri": "",
                  "vibrationEnabled": true,
                  "vibrationIntensity": 1,
                  "volume": 80,
                  "overrideSystemVolume": false,
                  "gradualVolumeSeconds": 30,
                  "snoozeDurationMinutes": 5,
                  "maxSnoozeCount": 3,
                  "showOnLockScreen": true,
                  "challengeType": "NONE"
                }
              ],
              "settings": {
                "is24HourFormat": true,
                "newsFeedUrl": "https://source.example/rss.xml"
              }
            }
        """.trimIndent()

        val result = realBackupManager.importFromUriStringForTest(newJson, BackupImportOptions(importSettings = true))
        assertTrue("Restore must fail on pre-COMMITTED settings write error", result.isFailure)

        // Assert persisted OLD Alarm state exactly
        val restoredAlarms = database.alarmDao().getAll()
        assertEquals(1, restoredAlarms.size)
        assertEquals(123L, restoredAlarms[0].id)
        assertEquals("OLD Alarm", restoredAlarms[0].label)

        // Assert persisted OLD AlarmGroup state exactly (including standalone orphan group)
        val restoredGroups = database.alarmGroupDao().getAll()
        assertEquals(2, restoredGroups.size)
        assertTrue("Standalone orphan group 'Gym' must be preserved", restoredGroups.any { it.name == "Gym" })
        assertTrue("Group 'Work' must be preserved", restoredGroups.any { it.name == "Work" })

        // Assert persisted OLD AppSettings state exactly (Gap 1)
        assertEquals("AppSettings must be restored to exact OLD truth", "https://destination.example/rss.xml", currentPersistedSettings.newsFeedUrl)
        org.junit.Assert.assertNotEquals("AppSettings must NOT remain as NEW settings", "https://source.example/rss.xml", currentPersistedSettings.newsFeedUrl)
    }

    @Test
    fun durableCommittedMarkBoundaryRecoveryReturnsRecoveredNewSuccess() = runTest {
        val realCoordinator = RestoreJournalCoordinator(
            context = context,
            database = database,
            preferencesManager = preferencesManager,
            alarmScheduler = scheduler,
            moshi = Moshi.Builder().build()
        )

        // Inject hook to throw immediately after durable COMMITTED write
        realCoordinator.onPostDurableCommittedHookForTest = {
            throw RuntimeException("Simulated exception boundary right after durable markCommitted write")
        }

        val realBackupManager = BackupManager(
            context = context,
            repository = repository,
            preferencesManager = preferencesManager,
            scheduler = scheduler,
            restoreJournalCoordinator = realCoordinator,
            database = database
        )

        // Seed an OLD Alarm into DB before import to prove OLD rollback does not execute
        val oldAlarm = Alarm(id = 123L, hour = 7, minute = 0, label = "OLD Alarm")
        database.alarmDao().insert(oldAlarm)

        coEvery { preferencesManager.getCurrentSettings() } returns AppSettings()
        coEvery { repository.getAll() } coAnswers { database.alarmDao().getAll() }
        coEvery {
            scheduler.cancelScheduledEntriesSuspending(any(), any(), any(), any(), any(), any(), any())
        } returns true

        coEvery { repository.restoreAlarmsTransaction(any(), eq(true)) } coAnswers {
            val staged = firstArg<List<Alarm>>()
            database.alarmDao().deleteAll()
            if (staged.isNotEmpty()) database.alarmDao().insertAll(staged)
            staged.map { it.id }
        }

        val json = """
            {
              "version": 19,
              "alarms": [
                {
                  "id": 888,
                  "hour": 8,
                  "minute": 0,
                  "label": "NEW Committed Alarm",
                  "isEnabled": true,
                  "repeatDays": [],
                  "ringtoneUri": "",
                  "vibrationEnabled": true,
                  "vibrationIntensity": 1,
                  "volume": 80,
                  "overrideSystemVolume": false,
                  "gradualVolumeSeconds": 30,
                  "snoozeDurationMinutes": 5,
                  "maxSnoozeCount": 3,
                  "showOnLockScreen": true,
                  "challengeType": "NONE"
                }
              ]
            }
        """.trimIndent()

        val result = realBackupManager.importFromUriStringForTest(json, BackupImportOptions(mode = BackupImportMode.Replace))
        assertTrue("Durable COMMITTED boundary exception must still return success", result.isSuccess)
        assertEquals("Returned success count must match actual staged Alarm count (N = 1 > 0)", 1, result.getOrNull())

        // Verify NEW data remains in DB and OLD rollback did NOT execute
        val alarmsInDb = database.alarmDao().getAll()
        assertEquals("DB must contain exact 1 committed NEW alarm", 1, alarmsInDb.size)
        assertEquals(888L, alarmsInDb[0].id)
        assertEquals("NEW Committed Alarm", alarmsInDb[0].label)
        assertTrue("OLD Alarm 123 must not be restored after COMMITTED boundary", alarmsInDb.none { it.id == 123L || it.label == "OLD Alarm" })
    }

    @Test
    fun realJournalRetentionOnRollbackFailureTest() = runTest {
        val realCoordinator = RestoreJournalCoordinator(
            context = context,
            database = database,
            preferencesManager = preferencesManager,
            alarmScheduler = scheduler,
            moshi = Moshi.Builder().build()
        )

        val realBackupManager = BackupManager(
            context = context,
            repository = repository,
            preferencesManager = preferencesManager,
            scheduler = scheduler,
            restoreJournalCoordinator = realCoordinator,
            database = database
        )

        coEvery { preferencesManager.getCurrentSettings() } returns AppSettings()
        coEvery { repository.getAll() } returns emptyList()

        // 1. Fail initial Room restore transaction
        coEvery { repository.restoreAlarmsTransaction(any(), any()) } throws RuntimeException("Room DB restore failed")

        // 2. Also fail preferences update during rollback in checkAndRecover
        coEvery { preferencesManager.update(any()) } throws RuntimeException("Preferences update failed during rollback")

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

        val result = realBackupManager.importFromUriStringForTest(json)
        assertTrue("Restore must fail if both restore and rollback fail", result.isFailure)

        val journalFile = java.io.File(context.filesDir, RestoreJournalCoordinator.JOURNAL_FILENAME)
        assertTrue("Journal file must be retained on rollback failure for safe recovery", journalFile.exists())
    }

    @Test
    fun c2b1_sameAlarmIdPreparedRollbackRebuildsAuthorityWithBumpedEpochAndClearsStaleSlot() = runTest {
        val realCoordinator = RestoreJournalCoordinator(
            context = context,
            database = database,
            preferencesManager = preferencesManager,
            alarmScheduler = scheduler,
            moshi = Moshi.Builder().build()
        )

        val realBackupManager = BackupManager(
            context = context,
            repository = repository,
            preferencesManager = preferencesManager,
            scheduler = scheduler,
            restoreJournalCoordinator = realCoordinator,
            database = database
        )

        val oldAlarm = Alarm(
            id = 123L,
            hour = 7,
            minute = 15,
            label = "OLD SameId Alarm",
            isEnabled = true,
            generation = 3,
            revision = 2,
            nextTriggerTime = 1_800_000_000_000L
        )
        database.alarmDao().insert(oldAlarm)
        database.scheduleAuthorityDao().insertAuthority(
            ScheduleAuthority(
                alarmId = 123L,
                instanceToken = "old-inst-123",
                definitionRevision = ScheduleAuthorityBootstrapAdapter.computeDefinitionRevision(oldAlarm),
                scheduleRevision = 2L,
                lifecycle = "LIVE",
                desiredOperation = "SET",
                desiredTriggerMillis = 1_800_000_000_000L,
                reconcileState = "SETTLED",
                restoreEpoch = 100L,
                actionKey = null
            )
        )
        database.issuedOccurrenceDao().insertSlotInitial(
            IssueSlot(alarmId = 123L, currentIssueToken = "old-issue-123", generationId = 3L)
        )

        coEvery { preferencesManager.getCurrentSettings() } returns AppSettings()
        coEvery { repository.getAll() } coAnswers { database.alarmDao().getAll() }
        coEvery { preferencesManager.update(any()) } throws RuntimeException("Settings write failed pre-COMMITTED")

        // Overwrite same alarmId=123 with transient NEW state and stale authority/slot
        coEvery { repository.restoreAlarmsTransaction(any(), eq(true)) } coAnswers {
            val staged = firstArg<List<Alarm>>()
            database.alarmDao().deleteAll()
            database.alarmDao().insertAll(staged)
            database.scheduleAuthorityDao().insertAuthority(
                ScheduleAuthority(
                    alarmId = 123L,
                    instanceToken = "new-stale-inst-123",
                    definitionRevision = 999L,
                    scheduleRevision = 7L,
                    lifecycle = "DISABLED",
                    desiredOperation = "CANCEL",
                    desiredTriggerMillis = null,
                    reconcileState = "SETTLED",
                    restoreEpoch = 100L,
                    actionKey = "new-key"
                )
            )
            database.issuedOccurrenceDao().deleteAllSlots()
            database.issuedOccurrenceDao().insertSlotInitial(
                IssueSlot(alarmId = 123L, currentIssueToken = "new-stale-issue-123", generationId = 9L)
            )
            staged.map { it.id }
        }

        val newJson = """
            {
              "version": 19,
              "alarms": [
                {
                  "id": 123,
                  "hour": 9,
                  "minute": 45,
                  "label": "NEW SameId Alarm",
                  "isEnabled": false,
                  "repeatDays": [],
                  "ringtoneUri": "",
                  "vibrationEnabled": true,
                  "vibrationIntensity": 1,
                  "volume": 80,
                  "overrideSystemVolume": false,
                  "gradualVolumeSeconds": 30,
                  "snoozeDurationMinutes": 5,
                  "maxSnoozeCount": 3,
                  "showOnLockScreen": true,
                  "challengeType": "NONE"
                }
              ],
              "settings": {
                "is24HourFormat": true,
                "defaultSnoozeDuration": 10,
                "defaultGradualVolume": 60,
                "usePhoneSpeakers": false,
                "vacationModeEnabled": false,
                "vacationStartMillis": 0,
                "vacationEndMillis": 0,
                "showWeatherOnDashboard": true,
                "showCalendarOnDashboard": true
              }
            }
        """.trimIndent()

        val result = realBackupManager.importFromUriStringForTest(
            newJson,
            BackupImportOptions(mode = BackupImportMode.Replace, importSettings = true)
        )
        assertTrue("Restore must fail on pre-COMMITTED settings failure", result.isFailure)
        coVerify(exactly = 1) { repository.restoreAlarmsTransaction(any(), eq(true)) }

        assertEquals(101L, database.scheduleAuthorityDao().getMetaById(1)?.restoreEpoch)

        val restoredAlarms = database.alarmDao().getAll()
        assertEquals(1, restoredAlarms.size)
        assertEquals(123L, restoredAlarms[0].id)
        assertEquals("OLD SameId Alarm", restoredAlarms[0].label)

        val rebuiltAuth = database.scheduleAuthorityDao().getAuthorityByAlarmId(123L)!!
        assertEquals(101L, rebuiltAuth.restoreEpoch)
        assertEquals("RECONCILING", rebuiltAuth.reconcileState)
        assertNotEquals("old-inst-123", rebuiltAuth.instanceToken)
        assertNotEquals("new-stale-inst-123", rebuiltAuth.instanceToken)
        assertEquals(2L, rebuiltAuth.scheduleRevision)
        assertEquals(ScheduleAuthorityBootstrapAdapter.computeDefinitionRevision(oldAlarm), rebuiltAuth.definitionRevision)
        assertEquals("LIVE", rebuiltAuth.lifecycle)
        assertEquals("SET", rebuiltAuth.desiredOperation)
        assertEquals(1_800_000_000_000L, rebuiltAuth.desiredTriggerMillis)
        assertNull(rebuiltAuth.actionKey)

        val rebuiltSlot = database.issuedOccurrenceDao().getSlot(123L)!!
        assertEquals(3L, rebuiltSlot.generationId)
        assertNull("Stale currentIssueToken must be cleared on rollback", rebuiltSlot.currentIssueToken)
    }

    @Test
    fun `CE-B01-B1 - transientNormalIds and transientQuickIds survive alarmDao deleteAll and PENDING_RETRY restart`() = runTest {
        val realCoordinator = RestoreJournalCoordinator(
            context = context,
            database = database,
            preferencesManager = preferencesManager,
            alarmScheduler = scheduler,
            moshi = Moshi.Builder().build()
        )
        val realBackupManager = BackupManager(
            context = context,
            repository = repository,
            preferencesManager = preferencesManager,
            scheduler = scheduler,
            restoreJournalCoordinator = realCoordinator,
            database = database
        )

        val oldAlarm = Alarm(id = 42L, hour = 6, minute = 30, label = "OLD Normal 42")
        database.alarmDao().insert(oldAlarm)

        coEvery { preferencesManager.getCurrentSettings() } returns AppSettings()
        coEvery { repository.getAll() } coAnswers { database.alarmDao().getAll() }
        coEvery { preferencesManager.update(any()) } coAnswers {
            val count = database.alarmDao().getAll().size
            if (count > 1) throw RuntimeException("Simulated settings failure after NEW insert")
        }
        coEvery { repository.restoreAlarmsTransaction(any(), eq(false)) } coAnswers {
            val staged = firstArg<List<Alarm>>()
            val normalNew = staged[0].copy(id = 901L, kind = Alarm.KIND_NORMAL)
            val quickNew = staged[1].copy(
                id = 902L,
                kind = Alarm.KIND_QUICK,
                quickTriggerEpochMs = System.currentTimeMillis() + 600_000L
            )
            database.alarmDao().insert(normalNew)
            database.alarmDao().insert(quickNew)
            listOf(901L, 902L)
        }
        coEvery { scheduler.rescheduleAllInBatches(any(), any()) } returns com.sysadmindoc.alarmclock.domain.ScheduleOutcome.BatchResult(
            armedCount = 0,
            blockedCount = 0,
            failedCount = 1,
            noArmCount = 0,
            outcomes = mapOf(42L to com.sysadmindoc.alarmclock.domain.ScheduleOutcome.RegistrationFailed(42L, null))
        )

        val importJson = """
            {
              "version": 23,
              "alarms": [
                {
                  "id": 0,
                  "hour": 8,
                  "minute": 0,
                  "label": "NEW Normal 901",
                  "isEnabled": true,
                  "repeatDays": [],
                  "ringtoneUri": "",
                  "vibrationEnabled": true,
                  "vibrationIntensity": 1,
                  "volume": 80,
                  "overrideSystemVolume": false,
                  "gradualVolumeSeconds": 30,
                  "snoozeDurationMinutes": 5,
                  "maxSnoozeCount": 3,
                  "showOnLockScreen": true,
                  "challengeType": "NONE",
                  "scheduleKind": "ONE_TIME"
                },
                {
                  "id": 0,
                  "hour": 9,
                  "minute": 15,
                  "label": "NEW Quick 902",
                  "isEnabled": true,
                  "repeatDays": [],
                  "ringtoneUri": "",
                  "vibrationEnabled": true,
                  "vibrationIntensity": 1,
                  "volume": 80,
                  "overrideSystemVolume": false,
                  "gradualVolumeSeconds": 30,
                  "snoozeDurationMinutes": 5,
                  "maxSnoozeCount": 3,
                  "showOnLockScreen": true,
                  "challengeType": "NONE",
                  "scheduleKind": "ONE_TIME"
                }
              ],
              "settings": {
                "is24HourFormat": true,
                "defaultSnoozeDuration": 10,
                "defaultGradualVolume": 60,
                "usePhoneSpeakers": false,
                "vacationModeEnabled": false,
                "vacationStartMillis": 0,
                "vacationEndMillis": 0,
                "showWeatherOnDashboard": true,
                "showCalendarOnDashboard": true
              }
            }
        """.trimIndent()

        val result = realBackupManager.importFromUriStringForTest(importJson, BackupImportOptions(mode = BackupImportMode.Append))
        assertTrue(result.isFailure)

        // Room now holds ONLY OLD 42L (901L & 902L wiped by alarmDao.deleteAll())
        assertEquals(listOf(42L), database.alarmDao().getAll().map { it.id })

        val journalFile = java.io.File(context.filesDir, RestoreJournalCoordinator.JOURNAL_FILENAME)
        assertTrue("Journal MUST be retained on PENDING_RETRY", journalFile.exists())

        val freshCoordinator = RestoreJournalCoordinator(
            context = context,
            database = database,
            preferencesManager = preferencesManager,
            alarmScheduler = scheduler,
            moshi = Moshi.Builder().build()
        )
        assertEquals(
            "Double-crash fresh coordinator MUST restore both transientNormalIds and transientQuickIds",
            listOf(901L) to listOf(902L),
            freshCoordinator.readPreparedTransientTargets()
        )
    }

    @Test
    fun `CE-B01-C3 - crash right after Step 4 before Post-Step 4 still captures live DB Normal and Quick IDs before deleteAll`() = runTest {
        val oldAlarm = Alarm(id = 42L, hour = 6, minute = 30, label = "OLD Normal 42")
        database.alarmDao().insert(oldAlarm)

        val coordinator = RestoreJournalCoordinator(
            context = context,
            database = database,
            preferencesManager = preferencesManager,
            alarmScheduler = scheduler,
            moshi = Moshi.Builder().build()
        )
        coordinator.prepareTransaction(AppSettings(), listOf(oldAlarm), emptyList())

        // Simulate crash immediately after Step 4 (DB holds live 901 Normal and 902 Quick, but recordPreparedTransientTargets never ran)
        database.alarmDao().insert(Alarm(id = 901L, hour = 8, minute = 0, label = "Live NEW Normal 901", kind = Alarm.KIND_NORMAL))
        database.alarmDao().insert(Alarm(id = 902L, hour = 9, minute = 0, label = "Live NEW Quick 902", kind = Alarm.KIND_QUICK, quickTriggerEpochMs = System.currentTimeMillis() + 600_000L))

        val freshCoordinator = RestoreJournalCoordinator(
            context = context,
            database = database,
            preferencesManager = preferencesManager,
            alarmScheduler = scheduler,
            moshi = Moshi.Builder().build()
        )
        val outcome = freshCoordinator.checkAndRecover()
        assertEquals(RecoveryOutcome.PENDING_RETRY, outcome)
        assertEquals(listOf(42L), database.alarmDao().getAll().map { it.id })
        assertEquals(
            "Cutpoint C3 checkAndRecover MUST capture live DB IDs before deleteAll()",
            listOf(901L) to listOf(902L),
            freshCoordinator.readPreparedTransientTargets()
        )
    }

    @Test
    fun `B2C-GATE-01 - unresolved journal on disk blocks new importFromUriStringForTest without mutating DB or journal`() = runTest {
        val realCoordinator = RestoreJournalCoordinator(
            context = context,
            database = database,
            preferencesManager = preferencesManager,
            alarmScheduler = scheduler,
            moshi = Moshi.Builder().build()
        )
        val realBackupManager = BackupManager(
            context = context,
            repository = repository,
            preferencesManager = preferencesManager,
            scheduler = scheduler,
            restoreJournalCoordinator = realCoordinator,
            database = database
        )

        val oldAlarm = Alarm(id = 42L, hour = 6, minute = 0, label = "Existing Old 42")
        realCoordinator.prepareTransaction(AppSettings(), listOf(oldAlarm), emptyList())
        val jFile = java.io.File(context.filesDir, RestoreJournalCoordinator.JOURNAL_FILENAME)
        val originalJournalContent = jFile.readText()

        val newJson = """
            {
              "version": 23,
              "alarms": [
                {
                  "id": 999,
                  "hour": 9,
                  "minute": 0,
                  "label": "Blocked New 999",
                  "isEnabled": true,
                  "repeatDays": [],
                  "ringtoneUri": "",
                  "vibrationEnabled": true,
                  "vibrationIntensity": 1,
                  "volume": 80,
                  "overrideSystemVolume": false,
                  "gradualVolumeSeconds": 30,
                  "snoozeDurationMinutes": 5,
                  "maxSnoozeCount": 3,
                  "showOnLockScreen": true,
                  "challengeType": "NONE"
                }
              ]
            }
        """.trimIndent()

        val blockedResult = realBackupManager.importFromUriStringForTest(newJson)
        assertTrue("Unresolved journal on disk MUST block new restore", blockedResult.isFailure)
        assertEquals("Existing journal must remain intact", originalJournalContent, jFile.readText())
        coVerify(exactly = 0) { repository.restoreAlarmsTransaction(any(), any()) }
        coVerify(exactly = 0) { preferencesManager.update(any()) }

        // Also block when only .bak exists
        val bFile = java.io.File(context.filesDir, "${RestoreJournalCoordinator.JOURNAL_FILENAME}.bak")
        bFile.writeText(originalJournalContent)
        assertTrue(jFile.delete())

        val bakBlockedResult = realBackupManager.importFromUriStringForTest(newJson)
        assertTrue("Unresolved .bak journal MUST block new restore", bakBlockedResult.isFailure)
        assertTrue(".bak journal must remain intact", bFile.exists())
        coVerify(exactly = 0) { repository.restoreAlarmsTransaction(any(), any()) }
    }

    @Test
    fun `B2C-STEP7-01 - Step 7 delegates to checkAndRecover cleans up OLD OS PI and never returns recovery exception as success`() = runBlocking {
        org.robolectric.shadows.ShadowAlarmManager.setCanScheduleExactAlarms(true)
        io.mockk.mockkStatic(androidx.work.WorkManager::class)
        val mockWm = mockk<androidx.work.WorkManager>(relaxed = true)
        io.mockk.every { androidx.work.WorkManager.getInstance(any<Context>()) } returns mockWm
        try {
            val realRepo = AlarmRepository(database, database.alarmDao(), database.alarmGroupDao())
            val realScheduler = AlarmScheduler(
                context = context,
                repository = realRepo,
                calculator = com.sysadmindoc.alarmclock.domain.NextAlarmCalculator(),
                preferencesManager = preferencesManager,
                holidayRepository = mockk(relaxed = true),
                alarmIncidentRepository = mockk(relaxed = true),
                weatherRepository = mockk(relaxed = true)
            )
            val spyScheduler = io.mockk.spyk(realScheduler)
            val realCoordinator = RestoreJournalCoordinator(
                context = context,
                database = database,
                preferencesManager = preferencesManager,
                alarmScheduler = spyScheduler,
                moshi = Moshi.Builder().build()
            )
            val realBackupManager = BackupManager(
                context = context,
                repository = realRepo,
                preferencesManager = preferencesManager,
                scheduler = spyScheduler,
                restoreJournalCoordinator = realCoordinator,
                database = database
            )

            val futureOld = System.currentTimeMillis() + 600_000L
            val oldAlarm = Alarm(id = 123L, hour = 7, minute = 0, label = "Old 123", isEnabled = true, nextTriggerTime = futureOld)
            realRepo.save(oldAlarm)
            assertTrue(realScheduler.scheduleAt(oldAlarm, futureOld, requestWidgetUpdate = false).isArmed)

            val shadowAm = org.robolectric.Shadows.shadowOf(context.getSystemService(Context.ALARM_SERVICE) as android.app.AlarmManager)
            assertEquals(1, shadowAm.scheduledAlarms.size)

            val newJson = """
                {
                  "version": 23,
                  "alarms": [
                    {
                      "id": 888,
                      "hour": 8,
                      "minute": 30,
                      "label": "New 888",
                      "isEnabled": true,
                      "repeatDays": [],
                      "ringtoneUri": "",
                      "vibrationEnabled": true,
                      "vibrationIntensity": 1,
                      "volume": 80,
                      "overrideSystemVolume": false,
                      "gradualVolumeSeconds": 30,
                      "snoozeDurationMinutes": 5,
                      "maxSnoozeCount": 3,
                      "showOnLockScreen": true,
                      "challengeType": "NONE",
                      "scheduleKind": "ONE_TIME"
                    }
                  ]
                }
            """.trimIndent()

            val result = realBackupManager.importFromUriStringForTest(newJson, BackupImportOptions(mode = BackupImportMode.Replace))
            assertTrue("Import must succeed", result.isSuccess)
            val jFile = java.io.File(context.filesDir, RestoreJournalCoordinator.JOURNAL_FILENAME)
            assertFalse("Step 7 must delegate to checkAndRecover() and delete journal upon RECOVERED_NEW", jFile.exists())
            coVerify(atLeast = 1) {
                spyScheduler.cancelScheduledEntriesSuspending(
                    alarmId = 123L,
                    includeFollowUpWorkers = true,
                    expectedRestoreEpoch = any()
                )
            }
            assertEquals("Only NEW 888 PI must remain armed on OS", 1, shadowAm.scheduledAlarms.size)
            assertEquals(888, org.robolectric.Shadows.shadowOf(shadowAm.scheduledAlarms.single().operation).requestCode)

            // Part B: Recovery exception in Step 7 MUST NOT be returned as Result.success
            val throwingCoordinator = io.mockk.spyk(realCoordinator)
            coEvery { throwingCoordinator.checkAndRecover() } throws SecurityException("Simulated journal tamper during Step 7")
            val failBackupManager = BackupManager(
                context = context,
                repository = realRepo,
                preferencesManager = preferencesManager,
                scheduler = spyScheduler,
                restoreJournalCoordinator = throwingCoordinator,
                database = database
            )
            val failResult = failBackupManager.importFromUriStringForTest(newJson, BackupImportOptions(mode = BackupImportMode.Replace))
            assertTrue("Step 7 recovery failure MUST NOT be returned as Result.success", failResult.isFailure)
        } finally {
            com.sysadmindoc.alarmclock.directboot.DirectBootAlarmCache.clear(context)
            io.mockk.unmockkStatic(androidx.work.WorkManager::class)
        }
    }
}
