package com.sysadmindoc.alarmclock.data.local

import android.app.Application
import android.content.Context
import android.database.sqlite.SQLiteConstraintException
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.test.core.app.ApplicationProvider
import com.sysadmindoc.alarmclock.data.local.entity.DurableOperationIntent
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
class V29CompatibilityDiagnosticTest {

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
        dbFile = context.getDatabasePath("alarm_clock.db")
        if (dbFile.exists()) dbFile.delete()
        dbFile.parentFile?.mkdirs()

        // Construct a real file-backed v29 SQLite database representing pre-P3B-L release state
        val sqliteDb = SQLiteDatabase.openOrCreateDatabase(dbFile, null)

        // Create full v29 tables
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

        sqliteDb.execSQL(
            "CREATE TABLE IF NOT EXISTS `alarm_groups` (`name` TEXT NOT NULL, PRIMARY KEY(`name`))"
        )
        sqliteDb.execSQL(
            "CREATE TABLE IF NOT EXISTS `news_sources` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `name` TEXT NOT NULL, `feedUrl` TEXT NOT NULL, `sortOrder` INTEGER NOT NULL)"
        )
        sqliteDb.execSQL(
            "CREATE TABLE IF NOT EXISTS `schedule_authority` (`alarmId` INTEGER NOT NULL, `instanceToken` TEXT NOT NULL, `definitionRevision` INTEGER NOT NULL, `scheduleRevision` INTEGER NOT NULL, `lifecycle` TEXT NOT NULL, `desiredOperation` TEXT NOT NULL, `desiredTriggerMillis` INTEGER, `reconcileState` TEXT NOT NULL, `restoreEpoch` INTEGER NOT NULL, `actionKey` TEXT, PRIMARY KEY(`alarmId`))"
        )
        sqliteDb.execSQL(
            "CREATE TABLE IF NOT EXISTS `schedule_authority_meta` (`id` INTEGER NOT NULL, `restoreEpoch` INTEGER NOT NULL, `bootstrapState` TEXT NOT NULL, PRIMARY KEY(`id`))"
        )
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

    @After
    fun tearDown() {
        if (dbFile.exists()) dbFile.delete()
    }

    @Test
    fun `diagnostic - reopening existing v29 database reveals trigger drift and replacement behavior`() = runBlocking {
        // 1. Confirm initial conditions before reopen
        val initialDb = SQLiteDatabase.openDatabase(dbFile.absolutePath, null, SQLiteDatabase.OPEN_READONLY)
        assertEquals(29, initialDb.version)

        // Read initial trigger SQL from sqlite_master
        val cursor = initialDb.rawQuery("SELECT sql FROM sqlite_master WHERE name = 'trigger_durable_intent_identity_insert'", null)
        assertTrue(cursor.moveToFirst())
        val oldTriggerSql = cursor.getString(0)
        cursor.close()
        initialDb.close()

        assertFalse("Initial v29 trigger MUST NOT contain Unicode char 0x3000 parameter", oldTriggerSql.contains("0x3000"))

        // 2. Reopen using current Room v29 builder with DATABASE_CALLBACK
        val database = Room.databaseBuilder(context, AlarmDatabase::class.java, "alarm_clock.db")
            .allowMainThreadQueries()
            .setJournalMode(RoomDatabase.JournalMode.TRUNCATE)
            .addMigrations(
                AlarmDatabase.MIGRATION_29_30,
                AlarmDatabase.MIGRATION_30_31,
                AlarmDatabase.MIGRATION_31_32
            )
            .addCallback(AlarmDatabase.DATABASE_CALLBACK)
            .build()

        val reopenedDb = database.openHelper.writableDatabase
        assertTrue("Room must open existing v29 DB without schema error", reopenedDb.isOpen)
        assertEquals(32, reopenedDb.version)

        // 3. VERIFY: Does CREATE TRIGGER IF NOT EXISTS replace the existing trigger definition?
        val reopenedCursor = reopenedDb.query("SELECT sql FROM sqlite_master WHERE name = 'trigger_durable_intent_identity_insert'")
        assertTrue(reopenedCursor.moveToFirst())
        val currentTriggerSql = reopenedCursor.getString(0)
        reopenedCursor.close()

        // VERIFY: MIGRATION_29_30 replaced legacy triggers with canonical triggers containing 0x3000
        val triggerWasReplaced = currentTriggerSql.contains("0x3000")
        assertTrue("Canonical trigger installed by MIGRATION_29_30 MUST contain 0x3000", triggerWasReplaced)

        // 4. VERIFY: Does DurableIntentTriggerVerifier confirm valid canonical triggers post-migration?
        val verifierResult = verifier.verifyTriggers(reopenedDb)
        assertTrue("Verifier MUST confirm valid canonical triggers post-migration", verifierResult is TriggerVerificationResult.Valid)

        // 5. VERIFY: Ideographic space \u3000 is REJECTED by canonical triggers post-migration
        var ideographicSpaceBypassedTrigger = false
        try {
            reopenedDb.execSQL(
                "INSERT INTO durable_operation_intent (operationToken, alarmId, operationEpoch, incarnationId, operationKind, phase, intentRevision) " +
                    "VALUES ('\u3000', 99, 1, 'inc-99', 'SET', 'PENDING', 1)"
            )
            ideographicSpaceBypassedTrigger = true
        } catch (_: SQLiteConstraintException) {
            ideographicSpaceBypassedTrigger = false
        }
        assertFalse("Canonical trigger MUST reject ideographic space \u3000", ideographicSpaceBypassedTrigger)

        // 6. VERIFY: Existing records are preserved 100%
        val alarm = database.alarmDao().getAll().first()
        assertEquals("v29 Fixture Alarm", alarm.label)
        val intent = database.durableOperationIntentDao().getIntentByToken("intent-v29-old")
        assertNotNull(intent)

        database.close()
    }
}
