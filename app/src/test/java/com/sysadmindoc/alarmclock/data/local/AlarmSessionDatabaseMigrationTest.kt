package com.sysadmindoc.alarmclock.data.local

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class AlarmSessionDatabaseMigrationTest {

    private lateinit var context: Context
    private lateinit var dbFile: File

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        dbFile = context.getDatabasePath("test_migration_36_37.db")
        if (dbFile.exists()) dbFile.delete()
        dbFile.parentFile?.mkdirs()
    }

    @After
    fun tearDown() {
        if (dbFile.exists()) dbFile.delete()
    }

    private fun populateFull36Schema(sqliteDb: SQLiteDatabase) {
        sqliteDb.execSQL("CREATE TABLE IF NOT EXISTS `alarms` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `hour` INTEGER NOT NULL, `minute` INTEGER NOT NULL, `label` TEXT NOT NULL, `isEnabled` INTEGER NOT NULL, `repeatDays` TEXT NOT NULL, `ringtoneUri` TEXT NOT NULL, `vibrationEnabled` INTEGER NOT NULL, `vibrationIntensity` INTEGER NOT NULL, `volume` INTEGER NOT NULL, `overrideSystemVolume` INTEGER NOT NULL, `gradualVolumeSeconds` INTEGER NOT NULL, `snoozeDurationMinutes` INTEGER NOT NULL, `maxSnoozeCount` INTEGER NOT NULL, `showOnLockScreen` INTEGER NOT NULL, `challengeType` TEXT NOT NULL, `group` TEXT NOT NULL, `flashWake` INTEGER NOT NULL, `vibrationPattern` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, `nextTriggerTime` INTEGER NOT NULL, `ttsEnabled` INTEGER NOT NULL, `walkStepsRequired` INTEGER NOT NULL, `wakeConfirmEnabled` INTEGER NOT NULL, `wakeConfirmDelayMinutes` INTEGER NOT NULL, `smartAlarmEnabled` INTEGER NOT NULL, `smartAlarmWindowMinutes` INTEGER NOT NULL, `skipOnHolidays` INTEGER NOT NULL, `nfcTagId` TEXT NOT NULL, `barcodeValue` TEXT NOT NULL, `spotifyUri` TEXT NOT NULL, `hueEnabled` INTEGER NOT NULL, `huePreWakeMinutes` INTEGER NOT NULL, `photoMatchUri` TEXT NOT NULL, `challengeChain` TEXT NOT NULL, `progressiveSnooze` INTEGER NOT NULL, `backupSoundEnabled` INTEGER NOT NULL, `backupSoundDelaySec` INTEGER NOT NULL, `sunriseSimulation` INTEGER NOT NULL, `sunriseMinutes` INTEGER NOT NULL, `specificDate` TEXT NOT NULL, `profileName` TEXT NOT NULL, `earlyDismissMinutes` INTEGER NOT NULL, `guardianEnabled` INTEGER NOT NULL, `guardianPhone` TEXT NOT NULL, `guardianDelaySec` INTEGER NOT NULL, `locationDismissEnabled` INTEGER NOT NULL, `locationDismissLat` REAL NOT NULL, `locationDismissLng` REAL NOT NULL, `locationDismissRadius` INTEGER NOT NULL, `wifiDismissSsid` TEXT NOT NULL, `internetRadioUrl` TEXT NOT NULL, `flashlightStrobe` INTEGER NOT NULL, `morningRoutine` TEXT NOT NULL, `hardwareButtonAction` TEXT NOT NULL, `dismissAtRingtoneEnd` INTEGER NOT NULL, `holdToDismissEnabled` INTEGER NOT NULL, `ringtonePool` TEXT NOT NULL, `solarOffsetMinutes` INTEGER NOT NULL, `solarAnchor` TEXT NOT NULL, `vibrationDelaySeconds` INTEGER NOT NULL, `weatherEarlyMinutes` INTEGER NOT NULL, `requiredSquats` INTEGER NOT NULL, `dismissActionType` TEXT NOT NULL, `dismissActionPayload` TEXT NOT NULL, `firingBackgroundImageEnabled` INTEGER NOT NULL, `firingBackgroundImageUri` TEXT NOT NULL, `firingBackgroundBlurEnabled` INTEGER NOT NULL, `sortOrder` INTEGER NOT NULL, `shiftPattern` TEXT NOT NULL, `shiftPatternStartDate` TEXT NOT NULL, `timezonePolicy` TEXT NOT NULL, `fixedTimezoneId` TEXT NOT NULL, `kind` TEXT NOT NULL, `quickTriggerEpochMs` INTEGER NOT NULL, `quickRelativeElapsedMs` INTEGER NOT NULL, `quickBootCount` INTEGER NOT NULL, `scheduleKind` TEXT NOT NULL DEFAULT 'ONE_TIME', `generation` INTEGER NOT NULL DEFAULT 1, `revision` INTEGER NOT NULL DEFAULT 1, `holidayPolicy` TEXT NOT NULL DEFAULT 'FOLLOW_BASE')")
        sqliteDb.execSQL("CREATE TABLE IF NOT EXISTS `alarm_events` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `alarmId` INTEGER NOT NULL, `alarmLabel` TEXT NOT NULL, `scheduledTime` INTEGER NOT NULL, `firedAt` INTEGER NOT NULL, `action` TEXT NOT NULL, `actionAt` INTEGER NOT NULL, `challengeType` TEXT NOT NULL, `challengeSolveTimeMs` INTEGER NOT NULL, `challengeRetryCount` INTEGER NOT NULL, `snoozeCount` INTEGER NOT NULL, `dayOfWeek` INTEGER NOT NULL)")
        sqliteDb.execSQL("CREATE INDEX IF NOT EXISTS `index_alarm_events_action` ON `alarm_events` (`action`)")
        sqliteDb.execSQL("CREATE INDEX IF NOT EXISTS `index_alarm_events_firedAt` ON `alarm_events` (`firedAt`)")
        sqliteDb.execSQL("CREATE INDEX IF NOT EXISTS `index_alarm_events_alarmId_firedAt` ON `alarm_events` (`alarmId`, `firedAt`)")
        sqliteDb.execSQL("CREATE TABLE IF NOT EXISTS `actigraphy_sessions` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `alarmId` INTEGER NOT NULL, `startedAt` INTEGER NOT NULL, `endedAt` INTEGER NOT NULL, `targetTime` INTEGER NOT NULL, `totalMinutes` INTEGER NOT NULL, `awakeMinutes` INTEGER NOT NULL, `lightMinutes` INTEGER NOT NULL, `deepMinutes` INTEGER NOT NULL, `averageSleepIndex` REAL NOT NULL, `firedEarly` INTEGER NOT NULL, `algorithm` TEXT NOT NULL, `decisionReason` TEXT NOT NULL, `observedMinutesBeforeDecision` INTEGER NOT NULL, `smartWakeMode` TEXT NOT NULL)")
        sqliteDb.execSQL("CREATE TABLE IF NOT EXISTS `alarm_incident_events` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `fireId` TEXT NOT NULL, `alarmId` INTEGER NOT NULL, `scheduledAt` INTEGER NOT NULL, `eventAt` INTEGER NOT NULL, `elapsedMs` INTEGER NOT NULL, `type` TEXT NOT NULL, `status` TEXT NOT NULL, `reasonCode` TEXT NOT NULL, `source` TEXT NOT NULL, `sdkInt` INTEGER NOT NULL, `standbyBucket` TEXT NOT NULL, `exactAlarmAllowed` TEXT NOT NULL, `notificationPermissionGranted` TEXT NOT NULL, `fullScreenIntentAllowed` TEXT NOT NULL, `batteryOptimizationsIgnored` TEXT NOT NULL, `algorithmVersion` TEXT NOT NULL)")
        sqliteDb.execSQL("CREATE INDEX IF NOT EXISTS `index_alarm_incident_events_alarmId` ON `alarm_incident_events` (`alarmId`)")
        sqliteDb.execSQL("CREATE INDEX IF NOT EXISTS `index_alarm_incident_events_fireId` ON `alarm_incident_events` (`fireId`)")
        sqliteDb.execSQL("CREATE INDEX IF NOT EXISTS `index_alarm_incident_events_eventAt` ON `alarm_incident_events` (`eventAt`)")
        sqliteDb.execSQL("CREATE TABLE IF NOT EXISTS `snore_events` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `sessionStartedAt` INTEGER NOT NULL, `startedAt` INTEGER NOT NULL, `endedAt` INTEGER NOT NULL, `durationMillis` INTEGER NOT NULL, `peakDb` REAL NOT NULL, `averageDb` REAL NOT NULL, `windowCount` INTEGER NOT NULL, `source` TEXT NOT NULL)")
        sqliteDb.execSQL("CREATE INDEX IF NOT EXISTS `index_snore_events_sessionStartedAt` ON `snore_events` (`sessionStartedAt`)")
        sqliteDb.execSQL("CREATE INDEX IF NOT EXISTS `index_snore_events_startedAt` ON `snore_events` (`startedAt`)")
        sqliteDb.execSQL("CREATE TABLE IF NOT EXISTS `pre_sleep_tag_entries` (`localDate` TEXT NOT NULL, `tagKey` TEXT NOT NULL, `loggedAt` INTEGER NOT NULL, PRIMARY KEY(`localDate`, `tagKey`))")
        sqliteDb.execSQL("CREATE INDEX IF NOT EXISTS `index_pre_sleep_tag_entries_localDate` ON `pre_sleep_tag_entries` (`localDate`)")
        sqliteDb.execSQL("CREATE INDEX IF NOT EXISTS `index_pre_sleep_tag_entries_tagKey` ON `pre_sleep_tag_entries` (`tagKey`)")
        sqliteDb.execSQL("CREATE TABLE IF NOT EXISTS `alarm_groups` (`name` TEXT NOT NULL, PRIMARY KEY(`name`))")
        sqliteDb.execSQL("CREATE TABLE IF NOT EXISTS `news_sources` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `name` TEXT NOT NULL, `feedUrl` TEXT NOT NULL, `sortOrder` INTEGER NOT NULL)")
        sqliteDb.execSQL("CREATE TABLE IF NOT EXISTS `schedule_authority` (`alarmId` INTEGER NOT NULL, `instanceToken` TEXT NOT NULL, `definitionRevision` INTEGER NOT NULL, `scheduleRevision` INTEGER NOT NULL, `lifecycle` TEXT NOT NULL, `desiredOperation` TEXT NOT NULL, `desiredTriggerMillis` INTEGER, `reconcileState` TEXT NOT NULL, `restoreEpoch` INTEGER NOT NULL, `actionKey` TEXT, PRIMARY KEY(`alarmId`))")
        sqliteDb.execSQL("CREATE TABLE IF NOT EXISTS `schedule_authority_meta` (`id` INTEGER NOT NULL, `restoreEpoch` INTEGER NOT NULL, `bootstrapState` TEXT NOT NULL, PRIMARY KEY(`id`))")
        sqliteDb.execSQL("CREATE TABLE IF NOT EXISTS `durable_operation_intent` (`operationToken` TEXT NOT NULL, `alarmId` INTEGER NOT NULL, `operationEpoch` INTEGER NOT NULL, `incarnationId` TEXT NOT NULL, `operationKind` TEXT NOT NULL, `expectedOwnerToken` TEXT, `expectedScheduleRevision` INTEGER, `expectedRestoreEpoch` INTEGER, `phase` TEXT NOT NULL, `intentRevision` INTEGER NOT NULL, `originalFireId` TEXT, `originalScheduledAt` INTEGER, `candidateSnoozeAt` INTEGER, PRIMARY KEY(`operationToken`))")
        sqliteDb.execSQL("CREATE INDEX IF NOT EXISTS `index_durable_operation_intent_alarmId_phase` ON `durable_operation_intent` (`alarmId`, `phase`)")
        sqliteDb.execSQL("CREATE INDEX IF NOT EXISTS `index_durable_operation_intent_alarmId` ON `durable_operation_intent` (`alarmId`)")
        sqliteDb.execSQL("CREATE TABLE IF NOT EXISTS `issued_occurrences` (`issueToken` TEXT NOT NULL, `alarmId` INTEGER NOT NULL, `scheduledAt` INTEGER NOT NULL, `fireId` TEXT NOT NULL, `generationId` INTEGER NOT NULL, `status` TEXT NOT NULL, `activatedAt` INTEGER, `recoveryLeaseToken` TEXT, `recoveryLeaseExpiresAt` INTEGER, `outcome` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, `baseTriggerTime` INTEGER NOT NULL, `alarmGeneration` INTEGER NOT NULL, `authorityInstanceToken` TEXT, `restoreEpoch` INTEGER NOT NULL, `scheduleRevision` INTEGER NOT NULL, `definitionRevision` INTEGER NOT NULL, PRIMARY KEY(`issueToken`))")
        sqliteDb.execSQL("CREATE INDEX IF NOT EXISTS `index_issued_occurrences_alarmId_scheduledAt` ON `issued_occurrences` (`alarmId`, `scheduledAt`)")
        sqliteDb.execSQL("CREATE INDEX IF NOT EXISTS `index_issued_occurrences_status` ON `issued_occurrences` (`status`)")
        sqliteDb.execSQL("CREATE INDEX IF NOT EXISTS `index_issued_occurrences_alarmId_baseTriggerTime` ON `issued_occurrences` (`alarmId`, `baseTriggerTime`)")
        sqliteDb.execSQL("CREATE TABLE IF NOT EXISTS `issue_slots` (`alarmId` INTEGER NOT NULL, `currentIssueToken` TEXT, `generationId` INTEGER NOT NULL, PRIMARY KEY(`alarmId`))")
        sqliteDb.execSQL("CREATE TABLE IF NOT EXISTS `schedule_exceptions` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `alarmId` INTEGER NOT NULL, `baseTriggerTime` INTEGER NOT NULL, `exceptionType` TEXT NOT NULL, `overrideTriggerTime` INTEGER NOT NULL, `generation` INTEGER NOT NULL, `revision` INTEGER NOT NULL, `createdAt` INTEGER NOT NULL)")
        sqliteDb.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_schedule_exceptions_alarmId_baseTriggerTime_generation` ON `schedule_exceptions` (`alarmId`, `baseTriggerTime`, `generation`)")
        sqliteDb.execSQL("CREATE INDEX IF NOT EXISTS `index_schedule_exceptions_alarmId` ON `schedule_exceptions` (`alarmId`)")
        sqliteDb.execSQL("CREATE TABLE IF NOT EXISTS room_master_table (id INTEGER PRIMARY KEY,identity_hash TEXT)")
        sqliteDb.execSQL("INSERT OR REPLACE INTO room_master_table (id,identity_hash) VALUES(42, '887b28ec17ac5164ecb64d894095207a')")
    }

    @Test
    fun migrate36To37PreservesExistingDataAndCreatesNewTables() = runBlocking {
        // Create SQLite DB at full version 36 schema
        val sqliteDb = SQLiteDatabase.openOrCreateDatabase(dbFile, null)
        populateFull36Schema(sqliteDb)

        // Insert representative v36 alarm row
        sqliteDb.execSQL("INSERT INTO alarms (id, hour, minute, label, isEnabled, repeatDays, ringtoneUri, vibrationEnabled, vibrationIntensity, volume, overrideSystemVolume, gradualVolumeSeconds, snoozeDurationMinutes, maxSnoozeCount, showOnLockScreen, challengeType, `group`, flashWake, vibrationPattern, createdAt, nextTriggerTime, ttsEnabled, walkStepsRequired, wakeConfirmEnabled, wakeConfirmDelayMinutes, smartAlarmEnabled, smartAlarmWindowMinutes, skipOnHolidays, nfcTagId, barcodeValue, spotifyUri, hueEnabled, huePreWakeMinutes, photoMatchUri, challengeChain, progressiveSnooze, backupSoundEnabled, backupSoundDelaySec, sunriseSimulation, sunriseMinutes, specificDate, profileName, earlyDismissMinutes, guardianEnabled, guardianPhone, guardianDelaySec, locationDismissEnabled, locationDismissLat, locationDismissLng, locationDismissRadius, wifiDismissSsid, internetRadioUrl, flashlightStrobe, morningRoutine, hardwareButtonAction, dismissAtRingtoneEnd, holdToDismissEnabled, ringtonePool, solarOffsetMinutes, solarAnchor, vibrationDelaySeconds, weatherEarlyMinutes, requiredSquats, dismissActionType, dismissActionPayload, firingBackgroundImageEnabled, firingBackgroundImageUri, firingBackgroundBlurEnabled, sortOrder, shiftPattern, shiftPatternStartDate, timezonePolicy, fixedTimezoneId, kind, quickTriggerEpochMs, quickRelativeElapsedMs, quickBootCount, scheduleKind, generation, revision, holidayPolicy) VALUES (1, 6, 0, 'Test Alarm', 1, '', '', 1, 2, 100, 1, 60, 10, 3, 1, 'NONE', '', 0, 'default', 1000, 2000, 0, 0, 0, 0, 0, 0, 0, '', '', '', 0, 0, '', '', 0, 0, 0, 0, 0, '', '', 0, 0, '', 0, 0, 0.0, 0.0, 0, '', '', 0, '', 'NONE', 0, 0, '', 0, 'NONE', 0, 0, 0, 'DISMISS_CARD', '', 0, '', 0, 0, 'NONE', '', 'FLOATING', '', 'NORMAL', 0, 0, -1, 'ONE_TIME', 1, 1, 'FOLLOW_BASE')")
        sqliteDb.version = 36
        sqliteDb.close()

        // Migrate 36 -> 37 -> 38 via Room
        val migratedRoomDb = Room.databaseBuilder(context, AlarmDatabase::class.java, dbFile.absolutePath)
            .allowMainThreadQueries()
            .setJournalMode(RoomDatabase.JournalMode.TRUNCATE)
            .addMigrations(AlarmDatabase.MIGRATION_36_37, AlarmDatabase.MIGRATION_37_38)
            .build()

        val db = migratedRoomDb.openHelper.writableDatabase
        assertEquals(38, db.version)

        // Verify existing alarm row survives
        val cursor = db.query("SELECT id, label FROM alarms WHERE id = 1")
        assertTrue(cursor.moveToFirst())
        assertEquals(1L, cursor.getLong(0))
        assertEquals("Test Alarm", cursor.getString(1))
        cursor.close()

        // Verify new tables exist
        val sessionCursor = db.query("SELECT COUNT(*) FROM alarm_sessions")
        assertTrue(sessionCursor.moveToFirst())
        assertEquals(0L, sessionCursor.getLong(0))
        sessionCursor.close()

        val tombstoneCursor = db.query("SELECT COUNT(*) FROM alarm_admission_tombstones")
        assertTrue(tombstoneCursor.moveToFirst())
        assertEquals(0L, tombstoneCursor.getLong(0))
        tombstoneCursor.close()

        val receiptCursor = db.query("SELECT COUNT(*) FROM direct_boot_occurrence_receipts")
        assertTrue(receiptCursor.moveToFirst())
        assertEquals(0L, receiptCursor.getLong(0))
        receiptCursor.close()

        val seqCursor = db.query("SELECT COUNT(*) FROM session_sequence_generator")
        assertTrue(seqCursor.moveToFirst())
        assertEquals(0L, seqCursor.getLong(0))
        seqCursor.close()

        migratedRoomDb.close()
    }
}
