package com.sysadmindoc.alarmclock.data.local

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.sysadmindoc.alarmclock.data.local.entity.IssuedOccurrence
import com.sysadmindoc.alarmclock.data.local.entity.ScheduleException
import com.sysadmindoc.alarmclock.domain.evaluator.FireSafetyOutcome
import com.sysadmindoc.alarmclock.domain.evaluator.OccurrenceIssuanceProvenanceVerifier
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class V35ProvenanceMigrationTest {

    private lateinit var context: Context

    @get:Rule
    val helper: MigrationTestHelper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AlarmDatabase::class.java
    )

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
    }

    @After
    fun tearDown() {
        context.getDatabasePath("test_authentic_v34_to_v35.db").delete()
        context.getDatabasePath("test_authentic_v33_to_v35.db").delete()
        context.getDatabasePath("test_authentic_preservation.db").delete()
        context.getDatabasePath("test_authentic_index.db").delete()
    }

    @Test
    fun `1 Authentic V34 to V35 Upgrade`() {
        val dbName = "test_authentic_v34_to_v35.db"

        // Create authentic V34 DB directly from exported 34.json schema
        val dbV34 = helper.createDatabase(dbName, 34)
        dbV34.close()

        // Migrate from V34 to V35 using MIGRATION_34_35 and validate against exported 35.json
        val dbV35 = helper.runMigrationsAndValidate(
            dbName,
            35,
            true,
            AlarmDatabase.MIGRATION_34_35
        )

        // Verify V35 columns added to issued_occurrences
        val cursor = dbV35.query("PRAGMA table_info(`issued_occurrences`)")
        val columns = mutableSetOf<String>()
        while (cursor.moveToNext()) {
            columns.add(cursor.getString(cursor.getColumnIndexOrThrow("name")))
        }
        cursor.close()

        assertTrue(columns.contains("baseTriggerTime"))
        assertTrue(columns.contains("alarmGeneration"))
        assertTrue(columns.contains("authorityInstanceToken"))
        assertTrue(columns.contains("restoreEpoch"))
        assertTrue(columns.contains("scheduleRevision"))
        assertTrue(columns.contains("definitionRevision"))

        dbV35.close()
    }

    @Test
    fun `2 Authentic V33 to V34 to V35 Chained Upgrade`() {
        val dbName = "test_authentic_v33_to_v35.db"

        // Create authentic V33 DB directly from exported 33.json schema
        val dbV33 = helper.createDatabase(dbName, 33)
        dbV33.close()

        // Migrate from V33 -> V34 -> V35 using MIGRATION_33_34 and MIGRATION_34_35 and validate against 35.json
        val dbV35 = helper.runMigrationsAndValidate(
            dbName,
            35,
            true,
            AlarmDatabase.MIGRATION_33_34,
            AlarmDatabase.MIGRATION_34_35
        )

        // Verify V35 Room database opens successfully
        val cursor = dbV35.query("SELECT COUNT(*) FROM `issued_occurrences`")
        assertTrue(cursor.moveToFirst())
        cursor.close()

        dbV35.close()
    }

    @Test
    fun `3 Legacy IssuedOccurrence Row Value Preservation`() = runBlocking {
        val dbName = "test_authentic_preservation.db"

        // 1. Create authentic V34 DB from 34.json and insert legacy row
        val dbV34 = helper.createDatabase(dbName, 34)
        dbV34.execSQL("""
            INSERT INTO `issued_occurrences` (
                `issueToken`, `alarmId`, `scheduledAt`, `fireId`, `generationId`, `status`, `outcome`, `createdAt`, `updatedAt`
            ) VALUES (
                'legacy-authentic-token-101', 101, 1700000000000, 'fire-101', 1, 'ISSUED', 'PENDING', 1690000000000, 1690000000000
            )
        """.trimIndent())
        dbV34.close()

        // 2. Migrate to V35
        helper.runMigrationsAndValidate(
            dbName,
            35,
            true,
            AlarmDatabase.MIGRATION_34_35
        ).close()

        // 3. Open real Room database on migrated DB and query via Room DAO
        val roomDb = Room.databaseBuilder(context, AlarmDatabase::class.java, dbName)
            .addMigrations(AlarmDatabase.MIGRATION_34_35)
            .allowMainThreadQueries()
            .build()

        val retrieved = roomDb.issuedOccurrenceDao().getOccurrence("legacy-authentic-token-101")
        assertNotNull("Legacy IssuedOccurrence row MUST be preserved after V35 migration", retrieved)
        assertEquals("legacy-authentic-token-101", retrieved!!.issueToken)
        assertEquals(101L, retrieved.alarmId)
        assertEquals(1700000000000L, retrieved.scheduledAt)
        assertEquals("fire-101", retrieved.fireId)
        assertEquals(1L, retrieved.generationId)
        assertEquals("ISSUED", retrieved.status)

        roomDb.close()
    }

    @Test
    fun `4 New 6 Provenance Fields Default Values`() = runBlocking {
        val dbName = "test_authentic_defaults.db"

        val dbV34 = helper.createDatabase(dbName, 34)
        dbV34.execSQL("""
            INSERT INTO `issued_occurrences` (
                `issueToken`, `alarmId`, `scheduledAt`, `fireId`, `generationId`, `status`, `outcome`, `createdAt`, `updatedAt`
            ) VALUES (
                'legacy-defaults-token-102', 102, 1700000000000, 'fire-102', 1, 'ISSUED', 'PENDING', 1690000000000, 1690000000000
            )
        """.trimIndent())
        dbV34.close()

        helper.runMigrationsAndValidate(
            dbName,
            35,
            true,
            AlarmDatabase.MIGRATION_34_35
        ).close()

        val roomDb = Room.databaseBuilder(context, AlarmDatabase::class.java, dbName)
            .addMigrations(AlarmDatabase.MIGRATION_34_35)
            .allowMainThreadQueries()
            .build()

        val retrieved = roomDb.issuedOccurrenceDao().getOccurrence("legacy-defaults-token-102")
        assertNotNull(retrieved)
        assertEquals("New baseTriggerTime column MUST default to 0L for legacy rows", 0L, retrieved!!.baseTriggerTime)
        assertEquals("New alarmGeneration column MUST default to 0 for legacy rows", 0, retrieved.alarmGeneration)
        assertNull("New authorityInstanceToken column MUST default to null for legacy rows", retrieved.authorityInstanceToken)
        assertEquals("New restoreEpoch column MUST default to 0L for legacy rows", 0L, retrieved.restoreEpoch)
        assertEquals("New scheduleRevision column MUST default to 0L for legacy rows", 0L, retrieved.scheduleRevision)
        assertEquals("New definitionRevision column MUST default to 0L for legacy rows", 0L, retrieved.definitionRevision)

        roomDb.close()
    }

    @Test
    fun `5 Complete Identity V35 Round-Trip`() = runBlocking {
        val roomDb = Room.inMemoryDatabaseBuilder(context, AlarmDatabase::class.java)
            .allowMainThreadQueries()
            .build()

        val issuedDao = roomDb.issuedOccurrenceDao()

        val newOccurrence = IssuedOccurrence(
            issueToken = "v35-authentic-token-999",
            alarmId = 42L,
            scheduledAt = 1700000000000L,
            fireId = "fire-999",
            generationId = 1L,
            status = IssuedOccurrence.STATUS_ISSUED,
            baseTriggerTime = 1700000000000L,
            alarmGeneration = 3,
            authorityInstanceToken = "auth-token-42",
            restoreEpoch = 100L,
            scheduleRevision = 2L,
            definitionRevision = 2L
        )

        issuedDao.insertOccurrence(newOccurrence)

        val retrieved = issuedDao.getOccurrence("v35-authentic-token-999")
        assertNotNull(retrieved)
        assertEquals("v35-authentic-token-999", retrieved!!.issueToken)
        assertEquals(42L, retrieved.alarmId)
        assertEquals(1700000000000L, retrieved.baseTriggerTime)
        assertEquals(3, retrieved.alarmGeneration)
        assertEquals("auth-token-42", retrieved.authorityInstanceToken)
        assertEquals(100L, retrieved.restoreEpoch)
        assertEquals(2L, retrieved.scheduleRevision)
        assertEquals(2L, retrieved.definitionRevision)

        roomDb.close()
    }

    @Test
    fun `6 Index Existence and Non-Uniqueness Confirmation`() {
        val dbName = "test_authentic_index.db"

        val dbV34 = helper.createDatabase(dbName, 34)
        dbV34.close()

        val dbV35 = helper.runMigrationsAndValidate(
            dbName,
            35,
            true,
            AlarmDatabase.MIGRATION_34_35
        )

        val cursor = dbV35.query("PRAGMA index_list(`issued_occurrences`)")
        var foundIndex = false
        var isUnique = false

        while (cursor.moveToNext()) {
            val name = cursor.getString(cursor.getColumnIndexOrThrow("name"))
            if (name == "index_issued_occurrences_alarmId_baseTriggerTime") {
                foundIndex = true
                isUnique = cursor.getInt(cursor.getColumnIndexOrThrow("unique")) == 1
            }
        }
        cursor.close()

        assertTrue("index_issued_occurrences_alarmId_baseTriggerTime index MUST exist in V35 schema", foundIndex)
        assertFalse("index_issued_occurrences_alarmId_baseTriggerTime index MUST be NON-UNIQUE in V35 contract", isUnique)

        dbV35.close()
    }

    @Test
    fun `7 Legacy Record Safe Handling without Synthetic Backfill`() = runBlocking {
        val roomDb = Room.inMemoryDatabaseBuilder(context, AlarmDatabase::class.java)
            .allowMainThreadQueries()
            .build()

        val verifier = OccurrenceIssuanceProvenanceVerifier(database = roomDb)

        // Insert legacy record with default 0/null values
        val legacyRecord = IssuedOccurrence(
            issueToken = "legacy-0-token",
            alarmId = 1L,
            scheduledAt = 1700000000000L,
            fireId = "fire-1",
            generationId = 1L,
            status = IssuedOccurrence.STATUS_ISSUED,
            baseTriggerTime = 0L, // Legacy default 0
            alarmGeneration = 0   // Legacy default 0
        )
        roomDb.issuedOccurrenceDao().insertOccurrence(legacyRecord)

        // Verify verifier treats legacy record as UNVERIFIED without backfilling
        val decision = verifier.verifyAndPlanFireSafety(
            alarmId = 1L,
            firedTargetEpochMs = 1700000000000L,
            firedBaseTriggerTimeEpochMs = 1700000000000L,
            firedGeneration = 1,
            issueToken = "legacy-0-token"
        )

        assertEquals("Legacy record with 0 generation MUST fail-safe as UNVERIFIED_INTENT_DATA", FireSafetyOutcome.UNVERIFIED_INTENT_DATA, decision.outcome)
        assertFalse(decision.isFireAdmissionConfirmed)

        roomDb.close()
    }

    @Test
    fun `8 Existing Alarm and ScheduleException Preservation`() = runBlocking {
        val dbName = "test_authentic_preservation_tables.db"

        val dbV34 = helper.createDatabase(dbName, 34)
        dbV34.execSQL("""
            INSERT INTO `alarms` (
                `id`, `hour`, `minute`, `label`, `isEnabled`, `repeatDays`, `ringtoneUri`, `vibrationEnabled`,
                `vibrationIntensity`, `volume`, `overrideSystemVolume`, `gradualVolumeSeconds`, `snoozeDurationMinutes`,
                `maxSnoozeCount`, `showOnLockScreen`, `challengeType`, `group`, `flashWake`, `vibrationPattern`, `createdAt`,
                `nextTriggerTime`, `ttsEnabled`, `walkStepsRequired`, `wakeConfirmEnabled`, `wakeConfirmDelayMinutes`,
                `smartAlarmEnabled`, `smartAlarmWindowMinutes`, `skipOnHolidays`, `nfcTagId`, `barcodeValue`, `spotifyUri`,
                `hueEnabled`, `huePreWakeMinutes`, `photoMatchUri`, `challengeChain`, `progressiveSnooze`, `backupSoundEnabled`,
                `backupSoundDelayMinutes`, `mathDifficulty`, `mathProblemCount`, `timeFormat`, `solarOffsetMinutes`,
                `weatherEarlyMinutes`, `sortOrder`, `kind`, `quickDurationMinutes`, `quickLastUsedAt`, `groupRole`,
                `staggerOrder`, `staggerIntervalMinutes`, `groupCascadeMode`, `shiftPattern`, `shiftPatternStartDate`,
                `timezonePolicy`, `fixedTimezoneId`, `specificDate`, `scheduleKind`, `generation`, `revision`
            ) VALUES (
                55, 7, 30, 'Preserved Alarm', 1, 'WED', 'content://ringtone', 1, 1, 100, 0, 0, 10, 3, 1, 'NONE', '', 0,
                'default', 1690000000000, 1700000000000, 0, 30, 0, 10, 0, 30, 0, '', '', '', 0, 30, '', '', 0, 0, 0,
                'EASY', 3, '24H', 0, 0, 1, 'NORMAL', 0, 0, 'NONE', 0, 0, 'NONE', '', '', 'SYSTEM', '', '', 'REPEATING', 2, 1
            )
        """.trimIndent())

        dbV34.execSQL("""
            INSERT INTO `schedule_exceptions` (
                `id`, `alarmId`, `baseTriggerTime`, `exceptionType`, `overrideTriggerTime`, `generation`, `revision`, `createdAt`
            ) VALUES (
                10, 55, 1700000000000, 'SKIP', 0, 2, 1, 1690000000000
            )
        """.trimIndent())
        dbV34.close()

        helper.runMigrationsAndValidate(
            dbName,
            35,
            true,
            AlarmDatabase.MIGRATION_34_35
        ).close()

        val roomDb = Room.databaseBuilder(context, AlarmDatabase::class.java, dbName)
            .addMigrations(AlarmDatabase.MIGRATION_34_35)
            .allowMainThreadQueries()
            .build()

        val alarm = roomDb.alarmDao().getById(55L)
        assertNotNull(alarm)
        assertEquals("Preserved Alarm", alarm!!.label)
        assertEquals(2, alarm.generation)

        val exceptions = roomDb.scheduleExceptionDao().getExceptionsForAlarm(55L)
        assertEquals(1, exceptions.size)
        assertEquals("SKIP", exceptions[0].exceptionType)

        roomDb.close()
    }
}
