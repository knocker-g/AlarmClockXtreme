package com.sysadmindoc.alarmclock.data.local

import android.app.Application
import android.content.Context
import android.database.sqlite.SQLiteConstraintException
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class V30TriggerMigrationTest {

    private lateinit var context: Context
    private lateinit var dbFile: File
    private val verifier = DurableIntentTriggerVerifier()

    // Old pre-P3B-L trigger definitions (default ASCII trim(X) without unicode char parameter)
    private val oldIdentityTriggerInsert = """
        CREATE TRIGGER IF NOT EXISTS `trigger_durable_intent_identity_insert`
        BEFORE INSERT ON `durable_operation_intent`
        FOR EACH ROW
        WHEN length(trim(NEW.operationToken)) = 0
          OR length(trim(NEW.incarnationId)) = 0
          OR length(trim(NEW.operationKind)) = 0
          OR NEW.alarmId <= 0
          OR NEW.operationEpoch <= 0
          OR NEW.intentRevision <= 0
        BEGIN
            SELECT RAISE(ABORT, 'SQLite Constraint Error: Invalid identity or epoch invariants');
        END
    """.trimIndent()

    private val oldIdentityTriggerUpdate = """
        CREATE TRIGGER IF NOT EXISTS `trigger_durable_intent_identity_update`
        BEFORE UPDATE ON `durable_operation_intent`
        FOR EACH ROW
        WHEN length(trim(NEW.operationToken)) = 0
          OR length(trim(NEW.incarnationId)) = 0
          OR length(trim(NEW.operationKind)) = 0
          OR NEW.alarmId <= 0
          OR NEW.operationEpoch <= 0
          OR NEW.intentRevision <= 0
        BEGIN
            SELECT RAISE(ABORT, 'SQLite Constraint Error: Invalid identity or epoch invariants');
        END
    """.trimIndent()

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        dbFile = context.getDatabasePath("v30_migration_test.db")
        if (dbFile.exists()) dbFile.delete()
        dbFile.parentFile?.mkdirs()
    }

    @After
    fun tearDown() {
        if (dbFile.exists()) dbFile.delete()
    }

    private fun populateFull28Schema(sqliteDb: SQLiteDatabase) {
        sqliteDb.execSQL(
            "CREATE TABLE IF NOT EXISTS `alarms` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `hour` INTEGER NOT NULL, `minute` INTEGER NOT NULL, `label` TEXT NOT NULL, `isEnabled` INTEGER NOT NULL, `repeatDays` TEXT NOT NULL, `ringtoneUri` TEXT NOT NULL, `vibrationEnabled` INTEGER NOT NULL, `vibrationIntensity` INTEGER NOT NULL, `volume` INTEGER NOT NULL, `overrideSystemVolume` INTEGER NOT NULL, `gradualVolumeSeconds` INTEGER NOT NULL, `snoozeDurationMinutes` INTEGER NOT NULL, `maxSnoozeCount` INTEGER NOT NULL, `showOnLockScreen` INTEGER NOT NULL, `challengeType` TEXT NOT NULL, `group` TEXT NOT NULL, `flashWake` INTEGER NOT NULL, `vibrationPattern` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, `nextTriggerTime` INTEGER NOT NULL, `ttsEnabled` INTEGER NOT NULL, `walkStepsRequired` INTEGER NOT NULL, `wakeConfirmEnabled` INTEGER NOT NULL, `wakeConfirmDelayMinutes` INTEGER NOT NULL, `smartAlarmEnabled` INTEGER NOT NULL, `smartAlarmWindowMinutes` INTEGER NOT NULL, `skipOnHolidays` INTEGER NOT NULL, `nfcTagId` TEXT NOT NULL, `barcodeValue` TEXT NOT NULL, `spotifyUri` TEXT NOT NULL, `hueEnabled` INTEGER NOT NULL, `huePreWakeMinutes` INTEGER NOT NULL, `photoMatchUri` TEXT NOT NULL, `challengeChain` TEXT NOT NULL, `progressiveSnooze` INTEGER NOT NULL, `backupSoundEnabled` INTEGER NOT NULL, `backupSoundDelaySec` INTEGER NOT NULL, `sunriseSimulation` INTEGER NOT NULL, `sunriseMinutes` INTEGER NOT NULL, `specificDate` TEXT NOT NULL, `profileName` TEXT NOT NULL, `earlyDismissMinutes` INTEGER NOT NULL, `guardianEnabled` INTEGER NOT NULL, `guardianPhone` TEXT NOT NULL, `guardianDelaySec` INTEGER NOT NULL, `locationDismissEnabled` INTEGER NOT NULL, `locationDismissLat` REAL NOT NULL, `locationDismissLng` REAL NOT NULL, `locationDismissRadius` INTEGER NOT NULL, `wifiDismissSsid` TEXT NOT NULL, `internetRadioUrl` TEXT NOT NULL, `flashlightStrobe` INTEGER NOT NULL, `morningRoutine` TEXT NOT NULL, `hardwareButtonAction` TEXT NOT NULL, `dismissAtRingtoneEnd` INTEGER NOT NULL, `holdToDismissEnabled` INTEGER NOT NULL, `ringtonePool` TEXT NOT NULL, `solarOffsetMinutes` INTEGER NOT NULL, `solarAnchor` TEXT NOT NULL, `vibrationDelaySeconds` INTEGER NOT NULL, `weatherEarlyMinutes` INTEGER NOT NULL, `requiredSquats` INTEGER NOT NULL, `dismissActionType` TEXT NOT NULL, `dismissActionPayload` TEXT NOT NULL, `firingBackgroundImageEnabled` INTEGER NOT NULL, `firingBackgroundImageUri` TEXT NOT NULL, `firingBackgroundBlurEnabled` INTEGER NOT NULL, `sortOrder` INTEGER NOT NULL, `shiftPattern` TEXT NOT NULL, `shiftPatternStartDate` TEXT NOT NULL, `timezonePolicy` TEXT NOT NULL, `fixedTimezoneId` TEXT NOT NULL)"
        )
        sqliteDb.execSQL(
            "CREATE TABLE IF NOT EXISTS `alarm_events` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `alarmId` INTEGER NOT NULL, `alarmLabel` TEXT NOT NULL, `scheduledTime` INTEGER NOT NULL, `firedAt` INTEGER NOT NULL, `action` TEXT NOT NULL, `actionAt` INTEGER NOT NULL, `challengeType` TEXT NOT NULL, `challengeSolveTimeMs` INTEGER NOT NULL, `challengeRetryCount` INTEGER NOT NULL, `snoozeCount` INTEGER NOT NULL, `dayOfWeek` INTEGER NOT NULL)"
        )
        sqliteDb.execSQL("CREATE INDEX IF NOT EXISTS `index_alarm_events_action` ON `alarm_events` (`action`)")
        sqliteDb.execSQL("CREATE INDEX IF NOT EXISTS `index_alarm_events_firedAt` ON `alarm_events` (`firedAt`)")
        sqliteDb.execSQL("CREATE INDEX IF NOT EXISTS `index_alarm_events_alarmId_firedAt` ON `alarm_events` (`alarmId`, `firedAt`)")

        sqliteDb.execSQL(
            "CREATE TABLE IF NOT EXISTS `actigraphy_sessions` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `alarmId` INTEGER NOT NULL, `startedAt` INTEGER NOT NULL, `endedAt` INTEGER NOT NULL, `targetTime` INTEGER NOT NULL, `totalMinutes` INTEGER NOT NULL, `awakeMinutes` INTEGER NOT NULL, `lightMinutes` INTEGER NOT NULL, `deepMinutes` INTEGER NOT NULL, `averageSleepIndex` REAL NOT NULL, `firedEarly` INTEGER NOT NULL, `algorithm` TEXT NOT NULL, `decisionReason` TEXT NOT NULL, `observedMinutesBeforeDecision` INTEGER NOT NULL, `smartWakeMode` TEXT NOT NULL)"
        )

        sqliteDb.execSQL(
            "CREATE TABLE IF NOT EXISTS `alarm_incident_events` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `fireId` TEXT NOT NULL, `alarmId` INTEGER NOT NULL, `scheduledAt` INTEGER NOT NULL, `eventAt` INTEGER NOT NULL, `elapsedMs` INTEGER NOT NULL, `type` TEXT NOT NULL, `status` TEXT NOT NULL, `reasonCode` TEXT NOT NULL, `source` TEXT NOT NULL, `sdkInt` INTEGER NOT NULL, `standbyBucket` TEXT NOT NULL, `exactAlarmAllowed` TEXT NOT NULL, `notificationPermissionGranted` TEXT NOT NULL, `fullScreenIntentAllowed` TEXT NOT NULL, `batteryOptimizationsIgnored` TEXT NOT NULL, `algorithmVersion` TEXT NOT NULL)"
        )
        sqliteDb.execSQL("CREATE INDEX IF NOT EXISTS `index_alarm_incident_events_alarmId` ON `alarm_incident_events` (`alarmId`)")
        sqliteDb.execSQL("CREATE INDEX IF NOT EXISTS `index_alarm_incident_events_fireId` ON `alarm_incident_events` (`fireId`)")
        sqliteDb.execSQL("CREATE INDEX IF NOT EXISTS `index_alarm_incident_events_eventAt` ON `alarm_incident_events` (`eventAt`)")

        sqliteDb.execSQL(
            "CREATE TABLE IF NOT EXISTS `snore_events` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `sessionStartedAt` INTEGER NOT NULL, `startedAt` INTEGER NOT NULL, `endedAt` INTEGER NOT NULL, `durationMillis` INTEGER NOT NULL, `peakDb` REAL NOT NULL, `averageDb` REAL NOT NULL, `windowCount` INTEGER NOT NULL, `source` TEXT NOT NULL)"
        )
        sqliteDb.execSQL("CREATE INDEX IF NOT EXISTS `index_snore_events_sessionStartedAt` ON `snore_events` (`sessionStartedAt`)")
        sqliteDb.execSQL("CREATE INDEX IF NOT EXISTS `index_snore_events_startedAt` ON `snore_events` (`startedAt`)")

        sqliteDb.execSQL(
            "CREATE TABLE IF NOT EXISTS `pre_sleep_tag_entries` (`localDate` TEXT NOT NULL, `tagKey` TEXT NOT NULL, `loggedAt` INTEGER NOT NULL, PRIMARY KEY(`localDate`, `tagKey`))"
        )
        sqliteDb.execSQL("CREATE INDEX IF NOT EXISTS `index_pre_sleep_tag_entries_localDate` ON `pre_sleep_tag_entries` (`localDate`)")
        sqliteDb.execSQL("CREATE INDEX IF NOT EXISTS `index_pre_sleep_tag_entries_tagKey` ON `pre_sleep_tag_entries` (`tagKey`)")

        sqliteDb.execSQL("CREATE TABLE IF NOT EXISTS `alarm_groups` (`name` TEXT NOT NULL, PRIMARY KEY(`name`))")
        sqliteDb.execSQL("CREATE TABLE IF NOT EXISTS `news_sources` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `name` TEXT NOT NULL, `feedUrl` TEXT NOT NULL, `sortOrder` INTEGER NOT NULL)")

        sqliteDb.execSQL(
            "CREATE TABLE IF NOT EXISTS `schedule_authority` (`alarmId` INTEGER NOT NULL, `instanceToken` TEXT NOT NULL, `definitionRevision` INTEGER NOT NULL, `scheduleRevision` INTEGER NOT NULL, `lifecycle` TEXT NOT NULL, `desiredOperation` TEXT NOT NULL, `desiredTriggerMillis` INTEGER, `reconcileState` TEXT NOT NULL, `restoreEpoch` INTEGER NOT NULL, `actionKey` TEXT, PRIMARY KEY(`alarmId`))"
        )
        sqliteDb.execSQL(
            "CREATE TABLE IF NOT EXISTS `schedule_authority_meta` (`id` INTEGER NOT NULL, `restoreEpoch` INTEGER NOT NULL, `bootstrapState` TEXT NOT NULL, PRIMARY KEY(`id`))"
        )
    }

    private fun createOldV29Database() {
        val sqliteDb = SQLiteDatabase.openOrCreateDatabase(dbFile, null)
        populateFull28Schema(sqliteDb)

        sqliteDb.execSQL(
            "CREATE TABLE IF NOT EXISTS `durable_operation_intent` (`operationToken` TEXT NOT NULL, `alarmId` INTEGER NOT NULL, `operationEpoch` INTEGER NOT NULL, `incarnationId` TEXT NOT NULL, `operationKind` TEXT NOT NULL, `expectedOwnerToken` TEXT, `expectedScheduleRevision` INTEGER, `expectedRestoreEpoch` INTEGER, `phase` TEXT NOT NULL, `intentRevision` INTEGER NOT NULL, PRIMARY KEY(`operationToken`))"
        )
        sqliteDb.execSQL("CREATE INDEX IF NOT EXISTS `index_durable_operation_intent_alarmId_phase` ON `durable_operation_intent` (`alarmId`, `phase`)")
        sqliteDb.execSQL("CREATE INDEX IF NOT EXISTS `index_durable_operation_intent_alarmId` ON `durable_operation_intent` (`alarmId`)")

        // Install OLD trigger definitions
        sqliteDb.execSQL(DurableIntentTriggers.CANONICAL_TRIGGERS["trigger_durable_intent_unresolved_unique_insert"]!!)
        sqliteDb.execSQL(DurableIntentTriggers.CANONICAL_TRIGGERS["trigger_durable_intent_unresolved_unique_update"]!!)
        sqliteDb.execSQL(DurableIntentTriggers.CANONICAL_TRIGGERS["trigger_durable_intent_phase_insert"]!!)
        sqliteDb.execSQL(DurableIntentTriggers.CANONICAL_TRIGGERS["trigger_durable_intent_phase_update"]!!)
        sqliteDb.execSQL(oldIdentityTriggerInsert)
        sqliteDb.execSQL(oldIdentityTriggerUpdate)

        // Seed v29 data
        sqliteDb.execSQL(
            "INSERT INTO alarms (id, hour, minute, label, isEnabled, repeatDays, ringtoneUri, vibrationEnabled, vibrationIntensity, volume, overrideSystemVolume, gradualVolumeSeconds, snoozeDurationMinutes, maxSnoozeCount, showOnLockScreen, challengeType, `group`, flashWake, vibrationPattern, createdAt, nextTriggerTime, ttsEnabled, walkStepsRequired, wakeConfirmEnabled, wakeConfirmDelayMinutes, smartAlarmEnabled, smartAlarmWindowMinutes, skipOnHolidays, nfcTagId, barcodeValue, spotifyUri, hueEnabled, huePreWakeMinutes, photoMatchUri, challengeChain, progressiveSnooze, backupSoundEnabled, backupSoundDelaySec, sunriseSimulation, sunriseMinutes, specificDate, profileName, earlyDismissMinutes, guardianEnabled, guardianPhone, guardianDelaySec, locationDismissEnabled, locationDismissLat, locationDismissLng, locationDismissRadius, wifiDismissSsid, internetRadioUrl, flashlightStrobe, morningRoutine, hardwareButtonAction, dismissAtRingtoneEnd, holdToDismissEnabled, ringtonePool, solarOffsetMinutes, solarAnchor, vibrationDelaySeconds, weatherEarlyMinutes, requiredSquats, dismissActionType, dismissActionPayload, firingBackgroundImageEnabled, firingBackgroundImageUri, firingBackgroundBlurEnabled, sortOrder, shiftPattern, shiftPatternStartDate, timezonePolicy, fixedTimezoneId) " +
                "VALUES (42, 7, 30, 'v29 Fixture Alarm', 1, 'NONE', '', 1, 1, 80, 0, 0, 10, 3, 1, 'NONE', 'Personal', 0, '', 0, 1700000000000, 0, 0, 0, 0, 0, 0, 0, '', '', '', 0, 0, '', '', 0, 0, 0, 0, 0, '', '', 0, 0, '', 0, 0, 0.0, 0.0, 0, '', '', 0, '', 'NONE', 0, 0, '', 0, '', 0, 0, 0, 'NONE', '', 0, '', 0, 0, 'NONE', '', 'SYSTEM', '')"
        )
        sqliteDb.execSQL(
            "INSERT INTO schedule_authority (alarmId, instanceToken, definitionRevision, scheduleRevision, lifecycle, desiredOperation, desiredTriggerMillis, reconcileState, restoreEpoch) " +
                "VALUES (42, 'token-v29-42', 1, 1, 'LIVE', 'SET', 1700000000000, 'SETTLED', 100)"
        )
        sqliteDb.execSQL(
            "INSERT INTO schedule_authority_meta (id, restoreEpoch, bootstrapState) VALUES (1, 100, 'ENFORCED')"
        )
        sqliteDb.execSQL(
            "INSERT INTO durable_operation_intent (operationToken, alarmId, operationEpoch, incarnationId, operationKind, phase, intentRevision) " +
                "VALUES ('intent-v29-old', 42, 1, 'inc-old', 'SET', 'PENDING', 1)"
        )

        sqliteDb.execSQL("CREATE TABLE IF NOT EXISTS room_master_table (id INTEGER PRIMARY KEY,identity_hash TEXT)")
        sqliteDb.execSQL("INSERT OR REPLACE INTO room_master_table (id,identity_hash) VALUES(42, '8a4786184f5cbf61d17fb42d541c182c')")

        sqliteDb.version = 29
        sqliteDb.close()
    }

    @Test
    fun `known-old v29 database upgrades to v30, preserves data, and enforces corrected Unicode triggers`() = runBlocking {
        createOldV29Database()

        // 1. Confirm database is version 29 before migration
        val checkDb = SQLiteDatabase.openDatabase(dbFile.absolutePath, null, SQLiteDatabase.OPEN_READONLY)
        assertEquals(29, checkDb.version)
        checkDb.close()

        // 2. Migrate from v29 to v30 via Room
        var database = Room.databaseBuilder(context, AlarmDatabase::class.java, "v30_migration_test.db")
            .allowMainThreadQueries()
            .setJournalMode(RoomDatabase.JournalMode.TRUNCATE)
            .addMigrations(*AlarmDatabase.ALL_MIGRATIONS)
            .addCallback(AlarmDatabase.DATABASE_CALLBACK)
            .build()

        val db = database.openHelper.writableDatabase

        // 3. Confirm Room opened cleanly and version transitioned to target VERSION
        assertEquals(AlarmDatabase.VERSION, db.version)
        assertTrue("Room database must open cleanly", db.isOpen)

        // 4. Confirm preserved v29 data rows
        val alarm = database.alarmDao().getAll().first()
        assertEquals("v29 Fixture Alarm", alarm.label)
        val authority = database.scheduleAuthorityDao().getAuthorityByAlarmId(42L)
        assertEquals("token-v29-42", authority?.instanceToken)
        val intent = database.durableOperationIntentDao().getIntentByToken("intent-v29-old")
        assertNotNull(intent)

        // 5. Confirm ALL 6 triggers were replaced with current canonical definitions
        assertEquals(TriggerVerificationResult.Valid, verifier.verifyTriggers(db))

        // 6. Confirm ideographic space \u3000 is now REJECTED by corrected trigger
        var ideographicSpaceRejected = false
        try {
            db.execSQL(
                "INSERT INTO durable_operation_intent (operationToken, alarmId, operationEpoch, incarnationId, operationKind, phase, intentRevision) " +
                    "VALUES ('\u3000', 99, 1, 'inc-99', 'SET', 'PENDING', 1)"
            )
        } catch (_: SQLiteConstraintException) {
            ideographicSpaceRejected = true
        }
        assertTrue("Migrated v30 DB must reject ideographic space via updated SQLite trigger", ideographicSpaceRejected)

        database.close()

        // 7. Reopen migrated v30 database and confirm triggers and version remain intact
        database = Room.databaseBuilder(context, AlarmDatabase::class.java, "v30_migration_test.db")
            .allowMainThreadQueries()
            .setJournalMode(RoomDatabase.JournalMode.TRUNCATE)
            .addMigrations(*AlarmDatabase.ALL_MIGRATIONS)
            .addCallback(AlarmDatabase.DATABASE_CALLBACK)
            .build()

        val reopenedDb = database.openHelper.writableDatabase
        assertEquals(AlarmDatabase.VERSION, reopenedDb.version)
        assertEquals(TriggerVerificationResult.Valid, verifier.verifyTriggers(reopenedDb))
        database.close()
    }

    @Test
    fun `unknown or modified trigger in v29 database causes MIGRATION_29_30 to abort safely and rollback`() = runBlocking {
        val sqliteDb = SQLiteDatabase.openOrCreateDatabase(dbFile, null)
        populateFull28Schema(sqliteDb)
        sqliteDb.execSQL(
            "CREATE TABLE IF NOT EXISTS `durable_operation_intent` (`operationToken` TEXT NOT NULL, `alarmId` INTEGER NOT NULL, `operationEpoch` INTEGER NOT NULL, `incarnationId` TEXT NOT NULL, `operationKind` TEXT NOT NULL, `expectedOwnerToken` TEXT, `expectedScheduleRevision` INTEGER, `expectedRestoreEpoch` INTEGER, `phase` TEXT NOT NULL, `intentRevision` INTEGER NOT NULL, PRIMARY KEY(`operationToken`))"
        )
        // Install a modified, non-standard trigger
        sqliteDb.execSQL("CREATE TRIGGER `trigger_durable_intent_phase_insert` BEFORE INSERT ON `durable_operation_intent` BEGIN SELECT 1; END;")
        sqliteDb.version = 29
        sqliteDb.close()

        val openResult = runCatching {
            Room.databaseBuilder(context, AlarmDatabase::class.java, "v30_migration_test.db")
                .allowMainThreadQueries()
                .setJournalMode(RoomDatabase.JournalMode.TRUNCATE)
                .addMigrations(*AlarmDatabase.ALL_MIGRATIONS)
                .addCallback(AlarmDatabase.DATABASE_CALLBACK)
                .build()
                .openHelper.writableDatabase
        }

        assertTrue("Migration must abort on unknown/modified trigger set", openResult.isFailure)
        val cause = openResult.exceptionOrNull()
        assertNotNull(cause)
        assertTrue("Exception must state migration 29->30 aborted: ${cause?.message}", cause?.message?.contains("Migration 29->30 aborted") == true)

        // Verify database file was NOT destructively wiped or corrupted and remains version 29
        val checkDb = SQLiteDatabase.openDatabase(dbFile.absolutePath, null, SQLiteDatabase.OPEN_READONLY)
        assertEquals(29, checkDb.version)
        checkDb.close()
    }

    @Test
    fun `invalid persisted intent row in v29 database causes MIGRATION_29_30 to abort safely and rollback`() = runBlocking {
        val sqliteDb = SQLiteDatabase.openOrCreateDatabase(dbFile, null)
        populateFull28Schema(sqliteDb)
        sqliteDb.execSQL(
            "CREATE TABLE IF NOT EXISTS `durable_operation_intent` (`operationToken` TEXT NOT NULL, `alarmId` INTEGER NOT NULL, `operationEpoch` INTEGER NOT NULL, `incarnationId` TEXT NOT NULL, `operationKind` TEXT NOT NULL, `expectedOwnerToken` TEXT, `expectedScheduleRevision` INTEGER, `expectedRestoreEpoch` INTEGER, `phase` TEXT NOT NULL, `intentRevision` INTEGER NOT NULL, PRIMARY KEY(`operationToken`))"
        )
        // Insert invalid intent row (blank token / invalid phase)
        sqliteDb.execSQL(
            "INSERT INTO durable_operation_intent (operationToken, alarmId, operationEpoch, incarnationId, operationKind, phase, intentRevision) " +
                "VALUES ('   ', 42, 1, 'inc-bad', 'SET', 'INVALID_PHASE', 1)"
        )
        sqliteDb.version = 29
        sqliteDb.close()

        val openResult = runCatching {
            Room.databaseBuilder(context, AlarmDatabase::class.java, "v30_migration_test.db")
                .allowMainThreadQueries()
                .setJournalMode(RoomDatabase.JournalMode.TRUNCATE)
                .addMigrations(*AlarmDatabase.ALL_MIGRATIONS)
                .addCallback(AlarmDatabase.DATABASE_CALLBACK)
                .build()
                .openHelper.writableDatabase
        }

        assertTrue("Migration must abort on invalid persisted intent row", openResult.isFailure)
        val cause = openResult.exceptionOrNull()
        assertNotNull(cause)
        assertTrue("Exception must cite row validation failure: ${cause?.message}", cause?.message?.contains("Persisted row has") == true)

        // Verify database file was NOT destructively wiped or corrupted and remains version 29
        val checkDb = SQLiteDatabase.openDatabase(dbFile.absolutePath, null, SQLiteDatabase.OPEN_READONLY)
        assertEquals(29, checkDb.version)
        checkDb.close()
    }

    @Test
    fun `duplicate unresolved intents in v29 database causes MIGRATION_29_30 to abort safely and rollback`() = runBlocking {
        val sqliteDb = SQLiteDatabase.openOrCreateDatabase(dbFile, null)
        populateFull28Schema(sqliteDb)
        sqliteDb.execSQL(
            "CREATE TABLE IF NOT EXISTS `durable_operation_intent` (`operationToken` TEXT NOT NULL, `alarmId` INTEGER NOT NULL, `operationEpoch` INTEGER NOT NULL, `incarnationId` TEXT NOT NULL, `operationKind` TEXT NOT NULL, `expectedOwnerToken` TEXT, `expectedScheduleRevision` INTEGER, `expectedRestoreEpoch` INTEGER, `phase` TEXT NOT NULL, `intentRevision` INTEGER NOT NULL, PRIMARY KEY(`operationToken`))"
        )
        // Insert two unresolved intents for the same alarmId = 42
        sqliteDb.execSQL(
            "INSERT INTO durable_operation_intent (operationToken, alarmId, operationEpoch, incarnationId, operationKind, phase, intentRevision) " +
                "VALUES ('tok-dup-1', 42, 1, 'inc-1', 'SET', 'PENDING', 1)"
        )
        sqliteDb.execSQL(
            "INSERT INTO durable_operation_intent (operationToken, alarmId, operationEpoch, incarnationId, operationKind, phase, intentRevision) " +
                "VALUES ('tok-dup-2', 42, 2, 'inc-2', 'SET', 'IN_FLIGHT', 1)"
        )
        sqliteDb.version = 29
        sqliteDb.close()

        val openResult = runCatching {
            Room.databaseBuilder(context, AlarmDatabase::class.java, "v30_migration_test.db")
                .allowMainThreadQueries()
                .setJournalMode(RoomDatabase.JournalMode.TRUNCATE)
                .addMigrations(*AlarmDatabase.ALL_MIGRATIONS)
                .addCallback(AlarmDatabase.DATABASE_CALLBACK)
                .build()
                .openHelper.writableDatabase
        }

        assertTrue("Migration must abort on duplicate unresolved intents", openResult.isFailure)
        val cause = openResult.exceptionOrNull()
        assertNotNull(cause)
        assertTrue("Exception must cite duplicate unresolved intent error: ${cause?.message}", cause?.message?.contains("Duplicate unresolved operation intent") == true)

        // Verify database file was NOT destructively wiped or corrupted and remains version 29
        val checkDb = SQLiteDatabase.openDatabase(dbFile.absolutePath, null, SQLiteDatabase.OPEN_READONLY)
        assertEquals(29, checkDb.version)
        checkDb.close()
    }

    @Test
    fun `v28 database migrates sequentially through v29 to v30`() = runBlocking {
        // Build a v28 database
        val v28File = context.getDatabasePath("v28_to_v30_test.db")
        if (v28File.exists()) v28File.delete()
        v28File.parentFile?.mkdirs()

        val sqliteDb = SQLiteDatabase.openOrCreateDatabase(v28File, null)
        populateFull28Schema(sqliteDb)

        sqliteDb.execSQL(
            "INSERT INTO alarms (id, hour, minute, label, isEnabled, repeatDays, ringtoneUri, vibrationEnabled, vibrationIntensity, volume, overrideSystemVolume, gradualVolumeSeconds, snoozeDurationMinutes, maxSnoozeCount, showOnLockScreen, challengeType, `group`, flashWake, vibrationPattern, createdAt, nextTriggerTime, ttsEnabled, walkStepsRequired, wakeConfirmEnabled, wakeConfirmDelayMinutes, smartAlarmEnabled, smartAlarmWindowMinutes, skipOnHolidays, nfcTagId, barcodeValue, spotifyUri, hueEnabled, huePreWakeMinutes, photoMatchUri, challengeChain, progressiveSnooze, backupSoundEnabled, backupSoundDelaySec, sunriseSimulation, sunriseMinutes, specificDate, profileName, earlyDismissMinutes, guardianEnabled, guardianPhone, guardianDelaySec, locationDismissEnabled, locationDismissLat, locationDismissLng, locationDismissRadius, wifiDismissSsid, internetRadioUrl, flashlightStrobe, morningRoutine, hardwareButtonAction, dismissAtRingtoneEnd, holdToDismissEnabled, ringtonePool, solarOffsetMinutes, solarAnchor, vibrationDelaySeconds, weatherEarlyMinutes, requiredSquats, dismissActionType, dismissActionPayload, firingBackgroundImageEnabled, firingBackgroundImageUri, firingBackgroundBlurEnabled, sortOrder, shiftPattern, shiftPatternStartDate, timezonePolicy, fixedTimezoneId) " +
                "VALUES (42, 7, 30, 'v28 Sequential Alarm', 1, 'NONE', '', 1, 1, 80, 0, 0, 10, 3, 1, 'NONE', 'Personal', 0, '', 0, 1700000000000, 0, 0, 0, 0, 0, 0, 0, '', '', '', 0, 0, '', '', 0, 0, 0, 0, 0, '', '', 0, 0, '', 0, 0, 0.0, 0.0, 0, '', '', 0, '', 'NONE', 0, 0, '', 0, '', 0, 0, 0, 'NONE', '', 0, '', 0, 0, 'NONE', '', 'SYSTEM', '')"
        )
        sqliteDb.execSQL(
            "INSERT INTO schedule_authority (alarmId, instanceToken, definitionRevision, scheduleRevision, lifecycle, desiredOperation, desiredTriggerMillis, reconcileState, restoreEpoch) " +
                "VALUES (42, 'token-v28-seq-42', 1, 1, 'LIVE', 'SET', 1700000000000, 'SETTLED', 100)"
        )
        sqliteDb.execSQL(
            "INSERT INTO schedule_authority_meta (id, restoreEpoch, bootstrapState) VALUES (1, 100, 'ENFORCED')"
        )
        sqliteDb.version = 28
        sqliteDb.close()

        val database = Room.databaseBuilder(context, AlarmDatabase::class.java, "v28_to_v30_test.db")
            .allowMainThreadQueries()
            .setJournalMode(RoomDatabase.JournalMode.TRUNCATE)
            .addMigrations(*AlarmDatabase.ALL_MIGRATIONS)
            .addCallback(AlarmDatabase.DATABASE_CALLBACK)
            .build()

        val db = database.openHelper.writableDatabase
        assertEquals(AlarmDatabase.VERSION, db.version)
        assertEquals(TriggerVerificationResult.Valid, verifier.verifyTriggers(db))

        database.close()
        if (v28File.exists()) v28File.delete()
    }

    @Test
    fun `fresh v30 database creates canonical tables and triggers`() = runBlocking {
        val database = Room.inMemoryDatabaseBuilder(context, AlarmDatabase::class.java)
            .allowMainThreadQueries()
            .addCallback(AlarmDatabase.DATABASE_CALLBACK)
            .build()

        val db = database.openHelper.writableDatabase
        assertEquals(AlarmDatabase.VERSION, db.version)
        assertEquals(TriggerVerificationResult.Valid, verifier.verifyTriggers(db))

        database.close()
    }
}
