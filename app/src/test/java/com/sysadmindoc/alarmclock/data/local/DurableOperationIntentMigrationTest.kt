package com.sysadmindoc.alarmclock.data.local

import android.app.Application
import android.content.Context
import android.database.sqlite.SQLiteConstraintException
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.room.RoomDatabase
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
class DurableOperationIntentMigrationTest {

    private lateinit var context: Context
    private lateinit var dbFile: File
    private val verifier = DurableIntentTriggerVerifier()

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        dbFile = context.getDatabasePath("alarm_clock.db")
        if (dbFile.exists()) dbFile.delete()
        dbFile.parentFile?.mkdirs()

        // 1. Build a genuine file-backed v28 SQLite database using exact 28.json DDL schema
        val sqliteDb = SQLiteDatabase.openOrCreateDatabase(dbFile, null)

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

        // Seed v28 data
        sqliteDb.execSQL(
            "INSERT INTO alarms (id, hour, minute, label, isEnabled, repeatDays, ringtoneUri, vibrationEnabled, vibrationIntensity, volume, overrideSystemVolume, gradualVolumeSeconds, snoozeDurationMinutes, maxSnoozeCount, showOnLockScreen, challengeType, `group`, flashWake, vibrationPattern, createdAt, nextTriggerTime, ttsEnabled, walkStepsRequired, wakeConfirmEnabled, wakeConfirmDelayMinutes, smartAlarmEnabled, smartAlarmWindowMinutes, skipOnHolidays, nfcTagId, barcodeValue, spotifyUri, hueEnabled, huePreWakeMinutes, photoMatchUri, challengeChain, progressiveSnooze, backupSoundEnabled, backupSoundDelaySec, sunriseSimulation, sunriseMinutes, specificDate, profileName, earlyDismissMinutes, guardianEnabled, guardianPhone, guardianDelaySec, locationDismissEnabled, locationDismissLat, locationDismissLng, locationDismissRadius, wifiDismissSsid, internetRadioUrl, flashlightStrobe, morningRoutine, hardwareButtonAction, dismissAtRingtoneEnd, holdToDismissEnabled, ringtonePool, solarOffsetMinutes, solarAnchor, vibrationDelaySeconds, weatherEarlyMinutes, requiredSquats, dismissActionType, dismissActionPayload, firingBackgroundImageEnabled, firingBackgroundImageUri, firingBackgroundBlurEnabled, sortOrder, shiftPattern, shiftPatternStartDate, timezonePolicy, fixedTimezoneId) " +
                "VALUES (42, 7, 30, 'v28 Migration Alarm', 1, 'NONE', '', 1, 1, 80, 0, 0, 10, 3, 1, 'NONE', 'Personal', 0, '', 0, 1700000000000, 0, 0, 0, 0, 0, 0, 0, '', '', '', 0, 0, '', '', 0, 0, 0, 0, 0, '', '', 0, 0, '', 0, 0, 0.0, 0.0, 0, '', '', 0, '', 'NONE', 0, 0, '', 0, '', 0, 0, 0, 'NONE', '', 0, '', 0, 0, 'NONE', '', 'SYSTEM', '')"
        )
        sqliteDb.execSQL(
            "INSERT INTO schedule_authority (alarmId, instanceToken, definitionRevision, scheduleRevision, lifecycle, desiredOperation, desiredTriggerMillis, reconcileState, restoreEpoch) " +
                "VALUES (42, 'token-v28-42', 1, 1, 'LIVE', 'SET', 1700000000000, 'SETTLED', 100)"
        )
        sqliteDb.execSQL(
            "INSERT INTO schedule_authority_meta (id, restoreEpoch, bootstrapState) VALUES (1, 100, 'ENFORCED')"
        )

        sqliteDb.execSQL("CREATE TABLE IF NOT EXISTS room_master_table (id INTEGER PRIMARY KEY,identity_hash TEXT)")
        sqliteDb.execSQL("INSERT OR REPLACE INTO room_master_table (id,identity_hash) VALUES(42, '39a3f9b911f262bf7506d88448e50cda')")

        sqliteDb.version = 28
        sqliteDb.close()
    }

    @After
    fun tearDown() {
        if (dbFile.exists()) dbFile.delete()
    }

    @Test
    fun `genuine v28 database migrates to v29 via Room and enforces all trigger constraints`() = runBlocking {
        // 2. Confirm the database file is v28 before opening with Room v29
        val checkDb = SQLiteDatabase.openDatabase(dbFile.absolutePath, null, SQLiteDatabase.OPEN_READONLY)
        assertEquals(28, checkDb.version)
        checkDb.close()

        // 3. Open using Room v29 with ALL_MIGRATIONS and DATABASE_CALLBACK
        var database = Room.databaseBuilder(context, AlarmDatabase::class.java, "alarm_clock.db")
            .allowMainThreadQueries()
            .setJournalMode(RoomDatabase.JournalMode.TRUNCATE)
            .addMigrations(*AlarmDatabase.ALL_MIGRATIONS)
            .addCallback(AlarmDatabase.DATABASE_CALLBACK)
            .build()

        val db = database.openHelper.writableDatabase

        // 4. Confirm actual Room migration occurred and version transitioned to 29
        assertEquals(29, db.version)
        assertTrue("Room database must open cleanly without schema validation errors", db.isOpen)

        // 5. Confirm preserved v28 rows
        val alarmCount = database.alarmDao().getAll().size
        assertEquals(1, alarmCount)
        assertEquals("v28 Migration Alarm", database.alarmDao().getAll().first().label)

        val authority = database.scheduleAuthorityDao().getAuthorityByAlarmId(42L)
        assertNotNull(authority)
        assertEquals("token-v28-42", authority?.instanceToken)

        val meta = database.scheduleAuthorityDao().getMetaById(1)
        assertNotNull(meta)
        assertEquals("ENFORCED", meta?.bootstrapState)

        // 6. Verify all 6 triggers exist and are strictly valid on migrated DB
        assertEquals(TriggerVerificationResult.Valid, verifier.verifyTriggers(db))

        // 7. Verify raw SQL duplicate unresolved insert is rejected by trigger on migrated DB
        var unresolvedInsertRejected = false
        try {
            db.execSQL(
                "INSERT INTO durable_operation_intent (operationToken, alarmId, operationEpoch, incarnationId, operationKind, phase, intentRevision) " +
                    "VALUES ('tok-mig-1', 42, 1, 'inc-1', 'SET', 'PENDING', 1)"
            )
            db.execSQL(
                "INSERT INTO durable_operation_intent (operationToken, alarmId, operationEpoch, incarnationId, operationKind, phase, intentRevision) " +
                    "VALUES ('tok-mig-2', 42, 2, 'inc-2', 'SET', 'IN_FLIGHT', 1)"
            )
        } catch (_: SQLiteConstraintException) {
            unresolvedInsertRejected = true
        }
        assertTrue("Migrated DB must reject second unresolved intent via SQLite trigger", unresolvedInsertRejected)

        // 8. Verify invalid phase is rejected on migrated DB
        var invalidPhaseRejected = false
        try {
            db.execSQL(
                "INSERT INTO durable_operation_intent (operationToken, alarmId, operationEpoch, incarnationId, operationKind, phase, intentRevision) " +
                    "VALUES ('tok-bad-phase', 99, 1, 'inc-99', 'SET', 'INVALID_PHASE_STRING', 1)"
            )
        } catch (_: SQLiteConstraintException) {
            invalidPhaseRejected = true
        }
        assertTrue("Migrated DB must reject invalid phase via SQLite trigger", invalidPhaseRejected)

        database.close()

        // 9. Close and reopen migrated database file, confirming constraints remain intact
        database = Room.databaseBuilder(context, AlarmDatabase::class.java, "alarm_clock.db")
            .allowMainThreadQueries()
            .setJournalMode(RoomDatabase.JournalMode.TRUNCATE)
            .addMigrations(*AlarmDatabase.ALL_MIGRATIONS)
            .addCallback(AlarmDatabase.DATABASE_CALLBACK)
            .build()

        val reopenedDb = database.openHelper.writableDatabase
        assertEquals(29, reopenedDb.version)
        assertEquals(TriggerVerificationResult.Valid, verifier.verifyTriggers(reopenedDb))
        database.close()
    }
}
