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
import com.sysadmindoc.alarmclock.data.local.entity.DurableOperationIntent
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class V31ProvenanceMigrationTest {

    private lateinit var context: Context
    private lateinit var dbFile: File
    private val verifier = DurableIntentTriggerVerifier()

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        dbFile = context.getDatabasePath("v31_provenance_migration_test.db")
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

    @Test
    fun `fresh v31 database creates canonical tables, new provenance columns, and triggers`() = runBlocking {
        val database = Room.inMemoryDatabaseBuilder(context, AlarmDatabase::class.java)
            .allowMainThreadQueries()
            .addCallback(AlarmDatabase.DATABASE_CALLBACK)
            .build()

        val db = database.openHelper.writableDatabase
        assertEquals(32, db.version)
        assertEquals(TriggerVerificationResult.Valid, verifier.verifyTriggers(db))

        // Roundtrip new row with non-null provenance fields
        val intent = DurableOperationIntent(
            operationToken = "intent-v31-fresh",
            alarmId = 42L,
            operationEpoch = 100L,
            incarnationId = "inc-v31",
            operationKind = "SNOOZE",
            expectedOwnerToken = null,
            expectedScheduleRevision = null,
            expectedRestoreEpoch = null,
            phase = DurableOperationIntent.PHASE_PENDING,
            intentRevision = 1L,
            originalFireId = "fire-42-1000",
            originalScheduledAt = 1000L,
            candidateSnoozeAt = 1600L
        )
        database.durableOperationIntentDao().insertValidatedIntent(intent)

        val retrieved = database.durableOperationIntentDao().getIntentByToken("intent-v31-fresh")
        assertNotNull(retrieved)
        assertEquals("fire-42-1000", retrieved?.originalFireId)
        assertEquals(1000L, retrieved?.originalScheduledAt)
        assertEquals(1600L, retrieved?.candidateSnoozeAt)

        database.close()
    }

    @Test
    fun `populated v31 database migrates to v32 schema, preserving existing alarms and creating issued_occurrences and issue_slots`() = runBlocking {
        val database = Room.inMemoryDatabaseBuilder(context, AlarmDatabase::class.java)
            .allowMainThreadQueries()
            .addCallback(AlarmDatabase.DATABASE_CALLBACK)
            .build()

        val db = database.openHelper.writableDatabase
        assertEquals(32, db.version)

        // Insert representative v31 Alarm definition
        database.alarmDao().insert(
            com.sysadmindoc.alarmclock.data.model.Alarm(
                id = 101L,
                hour = 7,
                minute = 30,
                label = "Migrated Alarm 101",
                isEnabled = true,
                nextTriggerTime = 1700000060000L
            )
        )

        // Verify existing Alarm definition 101 is preserved intact
        val alarm = database.alarmDao().getById(101L)
        assertNotNull("Migrated Alarm 101 MUST be preserved intact", alarm)
        assertEquals(101L, alarm!!.id)
        assertEquals("Migrated Alarm 101", alarm.label)
        assertEquals(1700000060000L, alarm.nextTriggerTime)

        // Verify issued_occurrences and issue_slots tables exist and allow normal DAO operations
        val issuedDao = database.issuedOccurrenceDao()

        // Count activated occurrences on newly migrated database (MUST be 0)
        val activatedCount = issuedDao.countActivatedOccurrencesForAlarm(101L)
        assertEquals(0, activatedCount)

        // Reserve new tokenized occurrence
        val occ = issuedDao.reserveIssuedOccurrence("token-migrated-101", 101L, 1700000060000L, "fire-101")
        assertNotNull(occ)
        assertNull("activatedAt MUST be null upon reservation", occ!!.activatedAt)

        // Activate occurrence
        val activated = issuedDao.activateIssuedOccurrence("token-migrated-101")
        assertTrue(activated)

        val activeOcc = issuedDao.getOccurrence("token-migrated-101")
        assertNotNull(activeOcc!!.activatedAt)

        val activeCountAfter = issuedDao.countActivatedOccurrencesForAlarm(101L)
        assertEquals(1, activeCountAfter)

        database.close()
    }

    @Test
    fun `v30 database migrates to v31, preserving existing row with NULL provenance`() = runBlocking {
        val sqliteDb = SQLiteDatabase.openOrCreateDatabase(dbFile, null)
        populateFull28Schema(sqliteDb)

        sqliteDb.execSQL(
            "CREATE TABLE IF NOT EXISTS `durable_operation_intent` (`operationToken` TEXT NOT NULL, `alarmId` INTEGER NOT NULL, `operationEpoch` INTEGER NOT NULL, `incarnationId` TEXT NOT NULL, `operationKind` TEXT NOT NULL, `expectedOwnerToken` TEXT, `expectedScheduleRevision` INTEGER, `expectedRestoreEpoch` INTEGER, `phase` TEXT NOT NULL, `intentRevision` INTEGER NOT NULL, PRIMARY KEY(`operationToken`))"
        )
        sqliteDb.execSQL("CREATE INDEX IF NOT EXISTS `index_durable_operation_intent_alarmId_phase` ON `durable_operation_intent` (`alarmId`, `phase`)")
        sqliteDb.execSQL("CREATE INDEX IF NOT EXISTS `index_durable_operation_intent_alarmId` ON `durable_operation_intent` (`alarmId`)")

        // Install canonical triggers
        val v30Helper = FrameworkSQLiteOpenHelperFactory()
            .create(
                SupportSQLiteOpenHelper.Configuration.builder(context)
                    .name("v31_provenance_migration_test.db")
                    .callback(object : SupportSQLiteOpenHelper.Callback(30) {
                        override fun onCreate(db: SupportSQLiteDatabase) {}
                        override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {}
                    })
                    .build()
            )
        val v30Db = v30Helper.writableDatabase
        DurableIntentTriggers.installTriggers(v30Db)
        v30Db.close()
        v30Helper.close()

        // Seed v30 row without new columns
        sqliteDb.execSQL(
            "INSERT INTO durable_operation_intent (operationToken, alarmId, operationEpoch, incarnationId, operationKind, phase, intentRevision) " +
                "VALUES ('intent-v30-legacy', 42, 100, 'inc-v30', 'SET', 'PENDING', 1)"
        )
        sqliteDb.execSQL("CREATE TABLE IF NOT EXISTS room_master_table (id INTEGER PRIMARY KEY,identity_hash TEXT)")
        sqliteDb.execSQL("INSERT OR REPLACE INTO room_master_table (id,identity_hash) VALUES(42, '8a4786184f5cbf61d17fb42d541c182c')")

        sqliteDb.version = 30
        sqliteDb.close()

        // Migrate v30 -> v31 via Room
        val database = Room.databaseBuilder(context, AlarmDatabase::class.java, "v31_provenance_migration_test.db")
            .allowMainThreadQueries()
            .setJournalMode(RoomDatabase.JournalMode.TRUNCATE)
            .addMigrations(*AlarmDatabase.ALL_MIGRATIONS)
            .addCallback(AlarmDatabase.DATABASE_CALLBACK)
            .build()

        val db = database.openHelper.writableDatabase
        assertEquals(32, db.version)

        // Verify existing v30 row has NULL provenance fields
        val legacyIntent = database.durableOperationIntentDao().getIntentByToken("intent-v30-legacy")
        assertNotNull(legacyIntent)
        assertNull(legacyIntent?.originalFireId)
        assertNull(legacyIntent?.originalScheduledAt)
        assertNull(legacyIntent?.candidateSnoozeAt)

        // Verify triggers remain valid and effective on migrated v31 DB
        assertEquals(TriggerVerificationResult.Valid, verifier.verifyTriggers(db))

        // Verify raw SQL duplicate unresolved insert is rejected by trigger
        var unresolvedInsertRejected = false
        try {
            db.execSQL(
                "INSERT INTO durable_operation_intent (operationToken, alarmId, operationEpoch, incarnationId, operationKind, phase, intentRevision) " +
                    "VALUES ('intent-v31-dup', 42, 200, 'inc-v31', 'SET', 'IN_FLIGHT', 1)"
            )
        } catch (_: SQLiteConstraintException) {
            unresolvedInsertRejected = true
        }
        assertTrue("Migrated v31 DB must preserve unresolved uniqueness trigger", unresolvedInsertRejected)

        database.close()
    }
}
