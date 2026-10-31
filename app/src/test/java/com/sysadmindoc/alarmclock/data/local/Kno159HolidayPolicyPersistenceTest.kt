package com.sysadmindoc.alarmclock.data.local

import android.app.Application
import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.test.core.app.ApplicationProvider
import com.squareup.moshi.Moshi
import com.sysadmindoc.alarmclock.data.backup.AlarmBackup
import com.sysadmindoc.alarmclock.data.backup.BackupData
import com.sysadmindoc.alarmclock.data.backup.BackupImportMode
import com.sysadmindoc.alarmclock.data.backup.BackupImportOptions
import com.sysadmindoc.alarmclock.data.backup.BackupManager
import com.sysadmindoc.alarmclock.data.backup.RecoveryOutcome
import com.sysadmindoc.alarmclock.data.backup.RestoreJournalCoordinator
import com.sysadmindoc.alarmclock.data.backup.toAlarmBackup
import com.sysadmindoc.alarmclock.data.backup.toAlarmOrNull
import com.sysadmindoc.alarmclock.data.local.entity.IssuedOccurrence
import com.sysadmindoc.alarmclock.data.local.entity.ScheduleException
import com.sysadmindoc.alarmclock.data.model.Alarm
import com.sysadmindoc.alarmclock.data.model.AlarmOccurrenceId
import com.sysadmindoc.alarmclock.data.preferences.AppSettings
import com.sysadmindoc.alarmclock.data.preferences.PreferencesManager
import com.sysadmindoc.alarmclock.data.repository.AlarmRepository
import com.sysadmindoc.alarmclock.data.repository.HolidayProviderSnapshot
import com.sysadmindoc.alarmclock.data.repository.HolidayYearCoverage
import com.sysadmindoc.alarmclock.data.repository.NAGER_DATE_PROVIDER_ID
import com.sysadmindoc.alarmclock.data.repository.ScheduleExceptionRepository
import com.sysadmindoc.alarmclock.data.share.AlarmShareCodec
import com.sysadmindoc.alarmclock.domain.AlarmScheduler
import com.sysadmindoc.alarmclock.domain.ProductionSchedulingRoute
import com.sysadmindoc.alarmclock.domain.ProductionSchedulingRouteAuthority
import com.sysadmindoc.alarmclock.domain.ScheduleAuthorityBootstrapAdapter
import com.sysadmindoc.alarmclock.domain.ScheduleOutcome
import com.sysadmindoc.alarmclock.domain.evaluator.AlarmHolidayEvaluationInput
import com.sysadmindoc.alarmclock.domain.evaluator.HolidayMaterialChangeDetector
import com.sysadmindoc.alarmclock.domain.evaluator.HolidayPolicy
import com.sysadmindoc.alarmclock.domain.evaluator.HolidayTruthChangeOutcome
import com.sysadmindoc.alarmclock.domain.evaluator.LocalHolidayClassifier
import com.sysadmindoc.alarmclock.domain.evaluator.OccurrenceDecision
import com.sysadmindoc.alarmclock.domain.evaluator.ScheduleOccurrenceEvaluator
import io.mockk.coEvery
import io.mockk.mockk
import io.mockk.unmockkAll
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
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
import java.io.File
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.util.Base64

/**
 * Deterministic safety test suite for KNO-159 P2 Durable Alarm-Level HolidayPolicy
 * (Room V36 + Backup v22 + Generation Fencing + Restore Atomicity + Share Codec).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class Kno159HolidayPolicyPersistenceTest {

    private lateinit var context: Context
    private lateinit var db: AlarmDatabase
    private lateinit var alarmDao: AlarmDao
    private lateinit var alarmRepo: AlarmRepository
    private lateinit var exceptionRepo: ScheduleExceptionRepository
    private lateinit var preferencesManager: PreferencesManager
    private lateinit var alarmScheduler: AlarmScheduler
    private lateinit var restoreJournalCoordinator: RestoreJournalCoordinator
    private lateinit var backupManager: BackupManager
    private lateinit var journalFile: File

    private val moshi = Moshi.Builder().build()
    private val backupAdapter = moshi.adapter(BackupData::class.java)
    private val evaluator = ScheduleOccurrenceEvaluator()
    private val materialDetector = HolidayMaterialChangeDetector(evaluator)
    private val tokyoZone = ZoneId.of("Asia/Tokyo")

    // Fixed reference dates in 2026:
    // 2026-01-01 = Thursday, 2026-01-02 = Friday, 2026-01-05 = Monday
    private val thuJan1 = LocalDate.of(2026, 1, 1)
    private val friJan2 = LocalDate.of(2026, 1, 2)
    private val monJan5 = LocalDate.of(2026, 1, 5)
    private val nowJan1At0600Ms = epochMillis(thuJan1, LocalTime.of(6, 0), tokyoZone)

    private val createdDbFiles = mutableListOf<File>()

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, AlarmDatabase::class.java)
            .addCallback(AlarmDatabase.DATABASE_CALLBACK)
            .allowMainThreadQueries()
            .build()
        alarmDao = db.alarmDao()
        alarmRepo = AlarmRepository(db, alarmDao, db.alarmGroupDao())
        exceptionRepo = ScheduleExceptionRepository(db.scheduleExceptionDao())

        preferencesManager = mockk(relaxed = true)
        coEvery { preferencesManager.getCurrentSettings() } returns AppSettings()

        alarmScheduler = mockk(relaxed = true)
        coEvery { alarmScheduler.rescheduleAllInBatches(any(), any()) } returns
            ScheduleOutcome.BatchResult(0, 0, 0, 0, emptyMap())

        journalFile = File(context.filesDir, RestoreJournalCoordinator.JOURNAL_FILENAME)
        if (journalFile.exists()) journalFile.delete()

        restoreJournalCoordinator = RestoreJournalCoordinator(
            context = context,
            database = db,
            preferencesManager = preferencesManager,
            alarmScheduler = alarmScheduler,
            moshi = moshi
        )

        backupManager = BackupManager(
            context = context,
            repository = alarmRepo,
            preferencesManager = preferencesManager,
            scheduler = alarmScheduler,
            restoreJournalCoordinator = restoreJournalCoordinator,
            database = db
        )
    }

    @After
    fun tearDown() {
        db.close()
        if (journalFile.exists()) journalFile.delete()
        createdDbFiles.forEach { file ->
            if (file.exists()) file.delete()
            File("${file.absolutePath}-wal").takeIf { it.exists() }?.delete()
            File("${file.absolutePath}-shm").takeIf { it.exists() }?.delete()
            File("${file.absolutePath}-journal").takeIf { it.exists() }?.delete()
        }
        createdDbFiles.clear()
        unmockkAll()
    }

    private fun epochMillis(date: LocalDate, time: LocalTime, zone: ZoneId): Long =
        date.atTime(time).atZone(zone).toInstant().toEpochMilli()

    private fun registerDbFile(name: String): File {
        val file = context.getDatabasePath(name)
        if (file.exists()) file.delete()
        file.parentFile?.mkdirs()
        createdDbFiles.add(file)
        return file
    }

    private fun locateCommittedSchemaFile(version: Int): File {
        val candidates = listOf(
            File("schemas/com.sysadmindoc.alarmclock.data.local.AlarmDatabase/$version.json"),
            File("app/schemas/com.sysadmindoc.alarmclock.data.local.AlarmDatabase/$version.json")
        )
        return candidates.firstOrNull { it.exists() }
            ?: error("Could not locate committed Room schema $version.json from user.dir=${System.getProperty("user.dir")}")
    }

    private fun createV35DatabaseFromCommittedSchema(dbFile: File): SQLiteDatabase {
        val schemaJson = JSONObject(locateCommittedSchemaFile(35).readText())
        val databaseObj = schemaJson.getJSONObject("database")
        assertEquals(35, databaseObj.getInt("version"))

        val sqliteDb = SQLiteDatabase.openOrCreateDatabase(dbFile, null)
        val entities = databaseObj.getJSONArray("entities")
        for (i in 0 until entities.length()) {
            val entity = entities.getJSONObject(i)
            val tableName = entity.getString("tableName")
            val createSql = entity.getString("createSql").replace("\${TABLE_NAME}", tableName)
            sqliteDb.execSQL(createSql)

            val indices = entity.getJSONArray("indices")
            for (j in 0 until indices.length()) {
                val indexSql = indices.getJSONObject(j)
                    .getString("createSql")
                    .replace("\${TABLE_NAME}", tableName)
                sqliteDb.execSQL(indexSql)
            }
        }

        val setupQueries = databaseObj.getJSONArray("setupQueries")
        for (i in 0 until setupQueries.length()) {
            sqliteDb.execSQL(setupQueries.getString(i))
        }

        sqliteDb.execSQL(
            "INSERT OR IGNORE INTO schedule_authority_meta (id, restoreEpoch, bootstrapState) " +
                "VALUES (1, 1700000000000, 'INITIALIZED')"
        )
        sqliteDb.version = 35
        return sqliteDb
    }

    private fun insertLegacyV35AlarmRow(
        sqliteDb: SQLiteDatabase,
        id: Long,
        label: String,
        repeatDays: String,
        skipOnHolidays: Int,
        kind: String = "NORMAL",
        scheduleKind: String = "REPEATING"
    ) {
        val cv = ContentValues().apply {
            put("id", id)
            put("hour", 7)
            put("minute", 30)
            put("label", label)
            put("isEnabled", 1)
            put("repeatDays", repeatDays)
            put("ringtoneUri", "")
            put("vibrationEnabled", 1)
            put("vibrationIntensity", 2)
            put("volume", 100)
            put("overrideSystemVolume", 1)
            put("gradualVolumeSeconds", 60)
            put("snoozeDurationMinutes", 10)
            put("maxSnoozeCount", 3)
            put("showOnLockScreen", 1)
            put("challengeType", "NONE")
            put("group", "")
            put("flashWake", 0)
            put("vibrationPattern", "default")
            put("createdAt", 1700000000000L)
            put("nextTriggerTime", 1767220200000L)
            put("ttsEnabled", 0)
            put("walkStepsRequired", 30)
            put("wakeConfirmEnabled", 0)
            put("wakeConfirmDelayMinutes", 10)
            put("smartAlarmEnabled", 0)
            put("smartAlarmWindowMinutes", 30)
            put("skipOnHolidays", skipOnHolidays)
            put("nfcTagId", "")
            put("barcodeValue", "")
            put("spotifyUri", "")
            put("hueEnabled", 0)
            put("huePreWakeMinutes", 30)
            put("photoMatchUri", "")
            put("challengeChain", "")
            put("progressiveSnooze", 0)
            put("backupSoundEnabled", 0)
            put("backupSoundDelaySec", 40)
            put("sunriseSimulation", 0)
            put("sunriseMinutes", 15)
            put("specificDate", "")
            put("profileName", "")
            put("earlyDismissMinutes", 0)
            put("guardianEnabled", 0)
            put("guardianPhone", "")
            put("guardianDelaySec", 300)
            put("locationDismissEnabled", 0)
            put("locationDismissLat", 0.0)
            put("locationDismissLng", 0.0)
            put("locationDismissRadius", 100)
            put("wifiDismissSsid", "")
            put("internetRadioUrl", "")
            put("flashlightStrobe", 0)
            put("morningRoutine", "")
            put("hardwareButtonAction", "NONE")
            put("dismissAtRingtoneEnd", 0)
            put("holdToDismissEnabled", 0)
            put("ringtonePool", "")
            put("solarOffsetMinutes", 0)
            put("solarAnchor", "SUNRISE")
            put("vibrationDelaySeconds", 0)
            put("weatherEarlyMinutes", 0)
            put("requiredSquats", 10)
            put("dismissActionType", "NONE")
            put("dismissActionPayload", "")
            put("firingBackgroundImageEnabled", 0)
            put("firingBackgroundImageUri", "")
            put("firingBackgroundBlurEnabled", 1)
            put("sortOrder", (id * 1000L).toInt())
            put("shiftPattern", "")
            put("shiftPatternStartDate", "")
            put("timezonePolicy", "LOCAL")
            put("fixedTimezoneId", "")
            put("kind", kind)
            put("quickTriggerEpochMs", 0L)
            put("quickRelativeElapsedMs", 0L)
            put("quickBootCount", -1L)
            put("scheduleKind", scheduleKind)
            put("generation", 1)
            put("revision", 1)
        }
        val keys = cv.keySet().toList()
        val columns = keys.joinToString(", ") { if (it == "group") "`group`" else it }
        val placeholders = keys.joinToString(", ") { "?" }
        val bindArgs = keys.map { cv.get(it) }.toTypedArray()
        sqliteDb.execSQL("INSERT INTO alarms ($columns) VALUES ($placeholders)", bindArgs)
    }

    private fun classifierForHolidays(holidays: Set<LocalDate>): LocalHolidayClassifier {
        val snapshot = HolidayProviderSnapshot(
            providerId = NAGER_DATE_PROVIDER_ID,
            countryCode = "JP",
            generation = 1L,
            years = mapOf(
                2026 to HolidayYearCoverage(
                    year = 2026,
                    fetchedAtMillis = nowJan1At0600Ms,
                    dates = holidays
                )
            )
        )
        return LocalHolidayClassifier.fromSnapshot(
            snapshot = snapshot,
            configuredCountry = "JP",
            nowMillis = nowJan1At0600Ms
        )
    }

    // =========================================================================
    // Test A: V35 -> V36 Real Room SQLite Migration
    // =========================================================================

    @Test
    fun testA_v35ToV36RoomMigrationMapsLegacyBooleanAndPreservesQuickAndOtherRows() = runBlocking {
        val dbFile = registerDbFile("kno159_v35_to_v36_migration.db")
        val sqliteDb = createV35DatabaseFromCommittedSchema(dbFile)

        // Legacy false -> FOLLOW_BASE
        insertLegacyV35AlarmRow(
            sqliteDb = sqliteDb,
            id = 10L,
            label = "Legacy Follow Base",
            repeatDays = "1,3,5",
            skipOnHolidays = 0,
            kind = "NORMAL",
            scheduleKind = "REPEATING"
        )
        // Legacy true -> EXCLUDE
        insertLegacyV35AlarmRow(
            sqliteDb = sqliteDb,
            id = 20L,
            label = "Legacy Holiday Skip",
            repeatDays = "1,2,3,4,5",
            skipOnHolidays = 1,
            kind = "NORMAL",
            scheduleKind = "REPEATING"
        )
        // Quick alarm with stale skipOnHolidays=1 -> remains FOLLOW_BASE
        insertLegacyV35AlarmRow(
            sqliteDb = sqliteDb,
            id = 30L,
            label = "Quick Nap",
            repeatDays = "",
            skipOnHolidays = 1,
            kind = "QUICK",
            scheduleKind = "ONE_TIME"
        )
        sqliteDb.close()

        val migratedRoomDb = Room.databaseBuilder(context, AlarmDatabase::class.java, dbFile.absolutePath)
            .allowMainThreadQueries()
            .setJournalMode(RoomDatabase.JournalMode.TRUNCATE)
            .addMigrations(*AlarmDatabase.ALL_MIGRATIONS)
            .addCallback(AlarmDatabase.DATABASE_CALLBACK)
            .build()

        try {
            val writable = migratedRoomDb.openHelper.writableDatabase
            assertEquals(36, AlarmDatabase.VERSION)
            assertEquals(36, writable.version)

            val alarm10 = migratedRoomDb.alarmDao().getById(10L)!!
            assertEquals(Alarm.HOLIDAY_POLICY_FOLLOW_BASE, alarm10.holidayPolicy)
            assertEquals(HolidayPolicy.FOLLOW_BASE, alarm10.canonicalHolidayPolicy())
            assertEquals(Alarm.SCHEDULE_KIND_REPEATING, alarm10.scheduleKind)
            assertEquals(1, alarm10.generation)
            assertEquals(1, alarm10.revision)

            val alarm20 = migratedRoomDb.alarmDao().getById(20L)!!
            assertEquals(Alarm.HOLIDAY_POLICY_EXCLUDE, alarm20.holidayPolicy)
            assertEquals(HolidayPolicy.EXCLUDE, alarm20.canonicalHolidayPolicy())
            assertEquals(Alarm.SCHEDULE_KIND_REPEATING, alarm20.scheduleKind)
            assertEquals(1, alarm20.generation)
            assertEquals(1, alarm20.revision)

            val quick30 = migratedRoomDb.alarmDao().getById(30L)!!
            assertEquals(Alarm.HOLIDAY_POLICY_FOLLOW_BASE, quick30.holidayPolicy)
            assertEquals(HolidayPolicy.FOLLOW_BASE, quick30.canonicalHolidayPolicy())
            assertEquals(Alarm.SCHEDULE_KIND_ONE_TIME, quick30.scheduleKind)

            // Verify new INCLUDE row can be saved cleanly into the migrated V36 DB
            val migratedRepo = AlarmRepository(
                migratedRoomDb,
                migratedRoomDb.alarmDao(),
                migratedRoomDb.alarmGroupDao()
            )
            val includeId = migratedRepo.save(
                Alarm(
                    hour = 9,
                    minute = 0,
                    repeatDays = emptySet(),
                    holidayPolicy = Alarm.HOLIDAY_POLICY_INCLUDE
                )
            )
            val savedInclude = migratedRepo.getById(includeId)!!
            assertEquals(Alarm.HOLIDAY_POLICY_INCLUDE, savedInclude.holidayPolicy)
            assertEquals(Alarm.SCHEDULE_KIND_REPEATING, savedInclude.scheduleKind)
        } finally {
            migratedRoomDb.close()
        }
    }

    // =========================================================================
    // Test B: Explicit FOLLOW_BASE with legacy skipOnHolidays=true
    // =========================================================================

    @Test
    fun testB_explicitFollowBaseIsNeverOverriddenByStaleSkipOnHolidaysTrue() = runBlocking {
        // 1. Direct construction with explicit FOLLOW_BASE and stale skipOnHolidays=true
        val explicitFollowBase = Alarm(
            hour = 7,
            minute = 30,
            scheduleKind = Alarm.SCHEDULE_KIND_REPEATING,
            repeatDays = setOf(DayOfWeek.THURSDAY, DayOfWeek.FRIDAY),
            skipOnHolidays = true,
            holidayPolicy = Alarm.HOLIDAY_POLICY_FOLLOW_BASE
        )
        assertEquals(Alarm.HOLIDAY_POLICY_FOLLOW_BASE, explicitFollowBase.holidayPolicy)
        assertEquals(HolidayPolicy.FOLLOW_BASE, explicitFollowBase.canonicalHolidayPolicy())

        // 2. Sanitization preserves explicit FOLLOW_BASE
        val sanitized = explicitFollowBase.sanitized()
        assertEquals(Alarm.HOLIDAY_POLICY_FOLLOW_BASE, sanitized.holidayPolicy)
        assertEquals(HolidayPolicy.FOLLOW_BASE, sanitized.canonicalHolidayPolicy())

        // 3. Persist & reopen across on-disk Room DB
        val dbFile = registerDbFile("kno159_explicit_follow_base.db")
        val diskDb1 = Room.databaseBuilder(context, AlarmDatabase::class.java, dbFile.absolutePath)
            .allowMainThreadQueries()
            .setJournalMode(RoomDatabase.JournalMode.TRUNCATE)
            .addCallback(AlarmDatabase.DATABASE_CALLBACK)
            .build()
        val savedId: Long
        try {
            val repo1 = AlarmRepository(diskDb1, diskDb1.alarmDao(), diskDb1.alarmGroupDao())
            savedId = repo1.save(explicitFollowBase)
        } finally {
            diskDb1.close()
        }

        val diskDb2 = Room.databaseBuilder(context, AlarmDatabase::class.java, dbFile.absolutePath)
            .allowMainThreadQueries()
            .setJournalMode(RoomDatabase.JournalMode.TRUNCATE)
            .addCallback(AlarmDatabase.DATABASE_CALLBACK)
            .build()
        try {
            val repo2 = AlarmRepository(diskDb2, diskDb2.alarmDao(), diskDb2.alarmGroupDao())
            val reopened = repo2.getById(savedId)!!
            assertEquals(Alarm.HOLIDAY_POLICY_FOLLOW_BASE, reopened.holidayPolicy)
            assertEquals(HolidayPolicy.FOLLOW_BASE, ScheduleOccurrenceEvaluator.resolveEffectiveHolidayPolicy(reopened))

            // Confirm evaluator fires on confirmed Holiday thuJan1 (not suppressed by stale skipOnHolidays=true)
            val eval = evaluator.evaluateWithHolidayClassifier(
                alarm = reopened,
                fromEpochMs = nowJan1At0600Ms,
                holidayClassifier = classifierForHolidays(setOf(thuJan1)),
                evaluationZoneId = tokyoZone
            )
            assertNotNull(eval)
            assertEquals(OccurrenceDecision.NORMAL_TRIGGER, eval!!.decision)
            assertEquals(HolidayPolicy.FOLLOW_BASE, eval.holidayPolicy)
            assertEquals(epochMillis(thuJan1, LocalTime.of(7, 30), tokyoZone), eval.targetTriggerTimeEpochMs)

            // 4. Updating an EXCLUDE (skipOnHolidays=true) row to explicit FOLLOW_BASE via copy(holidayPolicy = ...)
            val excludeId = repo2.save(
                Alarm(
                    hour = 7,
                    minute = 30,
                    repeatDays = setOf(DayOfWeek.THURSDAY),
                    skipOnHolidays = true
                )
            )
            val savedExclude = repo2.getById(excludeId)!!
            assertEquals(Alarm.HOLIDAY_POLICY_EXCLUDE, savedExclude.holidayPolicy)
            assertTrue(savedExclude.skipOnHolidays)

            repo2.update(savedExclude.copy(holidayPolicy = Alarm.HOLIDAY_POLICY_FOLLOW_BASE))
            val updatedToFollow = repo2.getById(excludeId)!!
            assertEquals(Alarm.HOLIDAY_POLICY_FOLLOW_BASE, updatedToFollow.holidayPolicy)
            assertEquals(HolidayPolicy.FOLLOW_BASE, ScheduleOccurrenceEvaluator.resolveEffectiveHolidayPolicy(updatedToFollow))
        } finally {
            diskDb2.close()
        }

        // 5. Backup v22 mapper with explicit holidayPolicy="FOLLOW_BASE" and skipOnHolidays=true
        val backupDto = explicitFollowBase.toAlarmBackup().copy(
            skipOnHolidays = true,
            holidayPolicy = Alarm.HOLIDAY_POLICY_FOLLOW_BASE
        )
        val fromBackup = backupDto.toAlarmOrNull()!!
        assertEquals(Alarm.HOLIDAY_POLICY_FOLLOW_BASE, fromBackup.holidayPolicy)
        assertEquals(HolidayPolicy.FOLLOW_BASE, fromBackup.canonicalHolidayPolicy())
    }

    // =========================================================================
    // Test C: INCLUDE with empty weekdays remains REPEATING across DB reopen
    // =========================================================================

    @Test
    fun testC_holidayOnlyDbReopen() = runBlocking {
        val holidayOnly = Alarm(
            hour = 8,
            minute = 15,
            label = "Holiday Sleep-In",
            repeatDays = emptySet(),
            holidayPolicy = Alarm.HOLIDAY_POLICY_INCLUDE
        )

        assertEquals(Alarm.SCHEDULE_KIND_REPEATING, holidayOnly.deriveScheduleKind())
        val sanitized = holidayOnly.sanitized()
        assertEquals(Alarm.SCHEDULE_KIND_REPEATING, sanitized.scheduleKind)
        assertEquals(Alarm.HOLIDAY_POLICY_INCLUDE, sanitized.holidayPolicy)
        assertTrue(sanitized.repeatDays.isEmpty())

        val dbName = "kno159_include_reopen.db"
        context.deleteDatabase(dbName)
        val dbPath = context.getDatabasePath(dbName)
        check(dbPath.parentFile?.mkdirs() == true || dbPath.parentFile?.isDirectory == true)
        val diskDb1 = Room.databaseBuilder(context, AlarmDatabase::class.java, dbName)
            .allowMainThreadQueries()
            .setJournalMode(RoomDatabase.JournalMode.TRUNCATE)
            .addCallback(AlarmDatabase.DATABASE_CALLBACK)
            .build()
        val savedId: Long
        try {
            val repo1 = AlarmRepository(diskDb1, diskDb1.alarmDao(), diskDb1.alarmGroupDao())
            savedId = repo1.save(holidayOnly)
            val saved = repo1.getById(savedId)!!
            assertEquals(Alarm.SCHEDULE_KIND_REPEATING, saved.scheduleKind)
            assertEquals(Alarm.HOLIDAY_POLICY_INCLUDE, saved.holidayPolicy)
            assertTrue(saved.repeatDays.isEmpty())

            // Secondary edit must also keep scheduleKind = REPEATING
            repo1.update(saved.copy(label = "Holiday Sleep-In Edited"))
        } finally {
            diskDb1.close()
        }

        val diskDb2 = Room.databaseBuilder(context, AlarmDatabase::class.java, dbName)
            .allowMainThreadQueries()
            .setJournalMode(RoomDatabase.JournalMode.TRUNCATE)
            .addCallback(AlarmDatabase.DATABASE_CALLBACK)
            .build()
        try {
            val repo2 = AlarmRepository(diskDb2, diskDb2.alarmDao(), diskDb2.alarmGroupDao())
            val reopened = repo2.getById(savedId)!!
            assertEquals(Alarm.SCHEDULE_KIND_REPEATING, reopened.scheduleKind)
            assertEquals(Alarm.HOLIDAY_POLICY_INCLUDE, reopened.holidayPolicy)
            assertTrue(reopened.repeatDays.isEmpty())
            assertEquals(1, reopened.generation)
            assertEquals(2, reopened.revision)

            // Evaluator must synthesize occurrence on confirmed holiday (friJan2) and NOT return INVALID_SCHEDULE_NEEDS_REPAIR
            val eval = evaluator.evaluateWithHolidayClassifier(
                alarm = reopened,
                fromEpochMs = nowJan1At0600Ms,
                holidayClassifier = classifierForHolidays(setOf(friJan2)),
                evaluationZoneId = tokyoZone
            )
            assertNotNull(eval)
            assertEquals(OccurrenceDecision.NORMAL_TRIGGER, eval!!.decision)
            assertEquals(HolidayPolicy.INCLUDE, eval.holidayPolicy)
            assertEquals(epochMillis(friJan2, LocalTime.of(8, 15), tokyoZone), eval.targetTriggerTimeEpochMs)
        } finally {
            diskDb2.close()
            context.deleteDatabase(dbName)
        }

        // ONE_TIME and QUICK alarms must ignore HolidayPolicy
        val oneTime = Alarm(
            id = 501L,
            hour = 8,
            minute = 0,
            scheduleKind = Alarm.SCHEDULE_KIND_ONE_TIME,
            specificDate = "2026-01-01",
            holidayPolicy = Alarm.HOLIDAY_POLICY_EXCLUDE
        )
        val quick = Alarm(
            id = 502L,
            hour = 8,
            minute = 0,
            kind = Alarm.KIND_QUICK,
            holidayPolicy = Alarm.HOLIDAY_POLICY_INCLUDE
        )
        assertEquals(HolidayPolicy.FOLLOW_BASE, ScheduleOccurrenceEvaluator.resolveEffectiveHolidayPolicy(oneTime))
        assertEquals(HolidayPolicy.FOLLOW_BASE, ScheduleOccurrenceEvaluator.resolveEffectiveHolidayPolicy(quick))
    }

    // =========================================================================
    // Test D: HolidayPolicy edit -> generation & authority fencing
    // =========================================================================

    @Test
    fun testD_holidayPolicyEditIncrementsGenerationAndFencesExceptionsOccurrencesAndAuthority() = runBlocking {
        val initialTargetMs = epochMillis(thuJan1, LocalTime.of(7, 30), tokyoZone)
        val savedId = alarmRepo.save(
            Alarm(
                hour = 7,
                minute = 30,
                isEnabled = true,
                scheduleKind = Alarm.SCHEDULE_KIND_REPEATING,
                repeatDays = setOf(DayOfWeek.THURSDAY, DayOfWeek.FRIDAY),
                holidayPolicy = Alarm.HOLIDAY_POLICY_FOLLOW_BASE,
                nextTriggerTime = initialTargetMs
            )
        )

        val gen1Alarm = alarmRepo.getById(savedId)!!
        val gen1Auth = alarmRepo.getAuthorityByAlarmId(savedId)!!
        assertEquals(1, gen1Alarm.generation)
        assertEquals(1, gen1Alarm.revision)
        assertEquals(1L, gen1Auth.scheduleRevision)

        // Attach a generation-1 ScheduleException (SKIP on thuJan1) and an active IssuedOccurrence
        exceptionRepo.addException(
            ScheduleException(
                alarmId = savedId,
                baseTriggerTime = initialTargetMs,
                exceptionType = ScheduleException.TYPE_SKIP,
                generation = 1,
                revision = 1
            )
        )
        val issuedDao = alarmRepo.issuedOccurrenceDao()
        val reserved = issuedDao.reserveIssuedOccurrence(
            issueToken = "tok-gen1-$savedId",
            alarmId = savedId,
            scheduledAt = initialTargetMs,
            fireId = "fire-gen1"
        )
        assertNotNull(reserved)
        assertTrue(issuedDao.activateIssuedOccurrence("tok-gen1-$savedId"))

        // 1. FOLLOW_BASE -> EXCLUDE increments generation (1 -> 2), resets revision to 1, fences authority & occurrences
        alarmRepo.update(
            gen1Alarm.copy(
                label = "Changed With Policy",
                holidayPolicy = Alarm.HOLIDAY_POLICY_EXCLUDE
            )
        )
        val gen2Alarm = alarmRepo.getById(savedId)!!
        val gen2Auth = alarmRepo.getAuthorityByAlarmId(savedId)!!
        assertEquals(2, gen2Alarm.generation)
        assertEquals(1, gen2Alarm.revision)
        assertEquals(Alarm.HOLIDAY_POLICY_EXCLUDE, gen2Alarm.holidayPolicy)
        assertEquals(2L, gen2Auth.scheduleRevision)
        assertEquals("RECONCILING", gen2Auth.reconcileState)
        assertNotEquals(gen1Auth.definitionRevision, gen2Auth.definitionRevision)
        assertEquals(
            ScheduleAuthorityBootstrapAdapter.computeDefinitionRevision(gen2Alarm),
            gen2Auth.definitionRevision
        )

        // Pending/active IssuedOccurrence from generation 1 must be REVOKED
        val revokedOcc = issuedDao.getOccurrence("tok-gen1-$savedId")!!
        assertEquals(IssuedOccurrence.STATUS_REVOKED, revokedOcc.status)

        // Occurrence identity changes and generation-1 ScheduleException no longer suppresses thuJan1 on WORKDAY
        assertNotEquals(
            AlarmOccurrenceId.fromAlarm(gen1Alarm, initialTargetMs),
            AlarmOccurrenceId.fromAlarm(gen2Alarm, initialTargetMs)
        )
        val allExceptions = db.scheduleExceptionDao().getExceptionsForAlarm(savedId)
        val evalOnWorkday = evaluator.evaluateWithHolidayClassifier(
            alarm = gen2Alarm,
            fromEpochMs = nowJan1At0600Ms,
            holidayClassifier = classifierForHolidays(emptySet()),
            exceptions = allExceptions,
            evaluationZoneId = tokyoZone
        )
        assertNotNull(evalOnWorkday)
        assertEquals(initialTargetMs, evalOnWorkday!!.targetTriggerTimeEpochMs)
        assertEquals(2, evalOnWorkday.generation)

        // 2. EXCLUDE -> INCLUDE increments generation (2 -> 3)
        alarmRepo.update(gen2Alarm.copy(holidayPolicy = Alarm.HOLIDAY_POLICY_INCLUDE))
        val gen3Alarm = alarmRepo.getById(savedId)!!
        val gen3Auth = alarmRepo.getAuthorityByAlarmId(savedId)!!
        assertEquals(3, gen3Alarm.generation)
        assertEquals(1, gen3Alarm.revision)
        assertEquals(Alarm.HOLIDAY_POLICY_INCLUDE, gen3Alarm.holidayPolicy)
        assertEquals(3L, gen3Auth.scheduleRevision)

        // 3. INCLUDE -> FOLLOW_BASE increments generation (3 -> 4)
        alarmRepo.update(gen3Alarm.copy(holidayPolicy = Alarm.HOLIDAY_POLICY_FOLLOW_BASE))
        val gen4Alarm = alarmRepo.getById(savedId)!!
        val gen4Auth = alarmRepo.getAuthorityByAlarmId(savedId)!!
        assertEquals(4, gen4Alarm.generation)
        assertEquals(1, gen4Alarm.revision)
        assertEquals(Alarm.HOLIDAY_POLICY_FOLLOW_BASE, gen4Alarm.holidayPolicy)
        assertEquals(4L, gen4Auth.scheduleRevision)
    }

    // =========================================================================
    // Test E: Unchanged policy -> no unnecessary generation increment
    // =========================================================================

    @Test
    fun testE_unchangedHolidayPolicyDoesNotIncrementGenerationAndProviderTruthLeavesGenerationIntact() = runBlocking {
        val savedId = alarmRepo.save(
            Alarm(
                hour = 8,
                minute = 0,
                label = "Holiday Only",
                repeatDays = emptySet(),
                holidayPolicy = Alarm.HOLIDAY_POLICY_INCLUDE
            )
        )
        val initial = alarmRepo.getById(savedId)!!
        assertEquals(1, initial.generation)
        assertEquals(1, initial.revision)

        // No-op update with identical policy (even with whitespace/lowercase before sanitization)
        alarmRepo.update(initial.copy(holidayPolicy = " include "))
        val afterNoOp = alarmRepo.getById(savedId)!!
        assertEquals(1, afterNoOp.generation)
        assertEquals(1, afterNoOp.revision)
        assertEquals(Alarm.HOLIDAY_POLICY_INCLUDE, afterNoOp.holidayPolicy)

        // Secondary field change with unchanged policy -> revision increments, generation stays 1
        alarmRepo.update(afterNoOp.copy(label = "Holiday Sleep-In Updated"))
        val afterSecondary = alarmRepo.getById(savedId)!!
        assertEquals(1, afterSecondary.generation)
        assertEquals(2, afterSecondary.revision)

        // Provider Holiday truth transition must NOT increment Alarm.generation
        val delta = materialDetector.detectMaterialChange(
            previousClassifier = classifierForHolidays(emptySet()),
            currentClassifier = classifierForHolidays(setOf(thuJan1)),
            evaluationEpochMs = nowJan1At0600Ms,
            evaluationZoneId = tokyoZone,
            alarms = listOf(AlarmHolidayEvaluationInput(alarm = afterSecondary))
        )
        assertEquals(HolidayTruthChangeOutcome.MATERIAL_CHANGE_DETECTED, delta.outcome)
        assertEquals(1, delta.affectedAlarms.single().generation)
        assertEquals(1, alarmRepo.getById(savedId)!!.generation)
    }

    // =========================================================================
    // Test F: Backup v21 import -> legacy boolean values migrate correctly
    // =========================================================================

    @Test
    fun testF_backupV21LegacyImportMapsBothSkipOnHolidaysValuesCorrectly() = runBlocking {
        val v21Json = """
            {
              "version": 21,
              "appVersion": "1.16.0",
              "exportedAt": 1700000000000,
              "alarms": [
                {
                  "id": 101,
                  "hour": 6,
                  "minute": 30,
                  "label": "V21 Follow Base",
                  "isEnabled": true,
                  "repeatDays": ["MONDAY", "WEDNESDAY"],
                  "scheduleKind": "REPEATING",
                  "skipOnHolidays": false,
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
                },
                {
                  "id": 102,
                  "hour": 7,
                  "minute": 0,
                  "label": "V21 Exclude",
                  "isEnabled": true,
                  "repeatDays": ["TUESDAY", "THURSDAY"],
                  "scheduleKind": "REPEATING",
                  "skipOnHolidays": true,
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
              ],
              "settings": null
            }
        """.trimIndent()

        val result = backupManager.importFromUriStringForTest(
            v21Json,
            BackupImportOptions(mode = BackupImportMode.Replace, importSettings = false)
        )
        assertTrue("v21 import must succeed: ${result.exceptionOrNull()}", result.isSuccess)
        assertEquals(2, result.getOrThrow())

        val followAlarm = alarmRepo.getById(101L)!!
        assertEquals(Alarm.HOLIDAY_POLICY_FOLLOW_BASE, followAlarm.holidayPolicy)
        assertEquals(HolidayPolicy.FOLLOW_BASE, followAlarm.canonicalHolidayPolicy())
        assertEquals(Alarm.SCHEDULE_KIND_REPEATING, followAlarm.scheduleKind)

        val excludeAlarm = alarmRepo.getById(102L)!!
        assertEquals(Alarm.HOLIDAY_POLICY_EXCLUDE, excludeAlarm.holidayPolicy)
        assertEquals(HolidayPolicy.EXCLUDE, excludeAlarm.canonicalHolidayPolicy())
        assertEquals(Alarm.SCHEDULE_KIND_REPEATING, excludeAlarm.scheduleKind)
    }

    // =========================================================================
    // Test G: Backup v22 -> all three policies round-trip (Export / Replace / Append)
    // =========================================================================

    @Test
    fun testG_backupV22ExportsAndRoundTripsAllThreeHolidayPoliciesAcrossReplaceAndAppend() = runBlocking {
        val followId = alarmRepo.save(
            Alarm(
                hour = 6,
                minute = 0,
                label = "Follow",
                repeatDays = setOf(DayOfWeek.MONDAY),
                holidayPolicy = Alarm.HOLIDAY_POLICY_FOLLOW_BASE
            )
        )
        val excludeId = alarmRepo.save(
            Alarm(
                hour = 7,
                minute = 0,
                label = "Exclude",
                repeatDays = setOf(DayOfWeek.TUESDAY),
                holidayPolicy = Alarm.HOLIDAY_POLICY_EXCLUDE
            )
        )
        val includeId = alarmRepo.save(
            Alarm(
                hour = 8,
                minute = 30,
                label = "Include Holiday Only",
                repeatDays = emptySet(),
                holidayPolicy = Alarm.HOLIDAY_POLICY_INCLUDE
            )
        )

        val exportedJson = backupManager.export()
        val parsed = backupAdapter.fromJson(exportedJson)!!
        assertEquals(22, BackupManager.MAX_SUPPORTED_BACKUP_VERSION)
        assertEquals(22, parsed.version)
        assertEquals(3, parsed.alarms.size)

        val byLabelExport = parsed.alarms.associateBy { it.label }
        assertEquals(Alarm.HOLIDAY_POLICY_FOLLOW_BASE, byLabelExport.getValue("Follow").holidayPolicy)
        assertEquals(Alarm.HOLIDAY_POLICY_EXCLUDE, byLabelExport.getValue("Exclude").holidayPolicy)
        assertEquals(Alarm.HOLIDAY_POLICY_INCLUDE, byLabelExport.getValue("Include Holiday Only").holidayPolicy)
        assertEquals(Alarm.SCHEDULE_KIND_REPEATING, byLabelExport.getValue("Include Holiday Only").scheduleKind)

        // Wipe DB and Replace-import
        alarmDao.deleteAll()
        assertTrue(alarmRepo.getAll().isEmpty())

        val replaceResult = backupManager.importFromUriStringForTest(
            exportedJson,
            BackupImportOptions(mode = BackupImportMode.Replace, importSettings = false)
        )
        assertTrue(replaceResult.isSuccess)
        assertEquals(3, replaceResult.getOrThrow())

        val replacedFollow = alarmRepo.getById(followId)!!
        val replacedExclude = alarmRepo.getById(excludeId)!!
        val replacedInclude = alarmRepo.getById(includeId)!!
        assertEquals(Alarm.HOLIDAY_POLICY_FOLLOW_BASE, replacedFollow.holidayPolicy)
        assertEquals(Alarm.HOLIDAY_POLICY_EXCLUDE, replacedExclude.holidayPolicy)
        assertEquals(Alarm.HOLIDAY_POLICY_INCLUDE, replacedInclude.holidayPolicy)
        assertEquals(Alarm.SCHEDULE_KIND_REPEATING, replacedInclude.scheduleKind)
        assertTrue(replacedInclude.repeatDays.isEmpty())

        // Append-import adds 3 more rows with preserved canonical policies & scheduleKind
        val appendResult = backupManager.importFromUriStringForTest(
            exportedJson,
            BackupImportOptions(mode = BackupImportMode.Append, importSettings = false)
        )
        assertTrue(appendResult.isSuccess)
        val allAfterAppend = alarmRepo.getAll()
        assertEquals(6, allAfterAppend.size)
        val includeRows = allAfterAppend.filter { it.label == "Include Holiday Only" }
        assertEquals(2, includeRows.size)
        includeRows.forEach { row ->
            assertEquals(Alarm.HOLIDAY_POLICY_INCLUDE, row.holidayPolicy)
            assertEquals(Alarm.SCHEDULE_KIND_REPEATING, row.scheduleKind)
            assertTrue(row.repeatDays.isEmpty())
        }
    }

    // =========================================================================
    // Test H: Unsupported future Backup version -> fail closed
    // =========================================================================

    @Test
    fun testH_unsupportedFutureBackupVersionFailsClosedWithoutMutatingDb() = runBlocking {
        val sentinelId = alarmRepo.save(
            Alarm(
                hour = 9,
                minute = 0,
                label = "Sentinel Include",
                repeatDays = emptySet(),
                holidayPolicy = Alarm.HOLIDAY_POLICY_INCLUDE
            )
        )

        val futureBackupJson = backupManager.export().replace("\"version\": 22", "\"version\": 23")
        val importResult = backupManager.importFromUriStringForTest(
            futureBackupJson,
            BackupImportOptions(mode = BackupImportMode.Replace, importSettings = false)
        )
        assertTrue("Future backup version > 22 must fail closed", importResult.isFailure)

        // Existing DB truth must remain completely untouched
        val remaining = alarmRepo.getAll()
        assertEquals(1, remaining.size)
        assertEquals(sentinelId, remaining.single().id)
        assertEquals(Alarm.HOLIDAY_POLICY_INCLUDE, remaining.single().holidayPolicy)
    }

    // =========================================================================
    // Test I: Invalid policy value -> reject safely (fail closed)
    // =========================================================================

    @Test
    fun testI_invalidHolidayPolicyRejectedFailClosedAcrossBackupShareAndRepository() = runBlocking {
        val existingId = alarmRepo.save(
            Alarm(
                hour = 7,
                minute = 0,
                label = "Existing Exclude",
                repeatDays = setOf(DayOfWeek.MONDAY),
                holidayPolicy = Alarm.HOLIDAY_POLICY_EXCLUDE
            )
        )

        // 1. Direct mapper rejects unknown future policy and blank explicit policy
        val bogusDto: AlarmBackup = alarmRepo.getById(existingId)!!.toAlarmBackup()
            .copy(holidayPolicy = "OBSERVED_SUBSTITUTE_ONLY")
        assertNull(bogusDto.toAlarmOrNull())

        val blankExplicitDto: AlarmBackup = alarmRepo.getById(existingId)!!.toAlarmBackup()
            .copy(holidayPolicy = "")
        assertNull(blankExplicitDto.toAlarmOrNull())

        // 2. BackupManager import fails closed before journal/DB commit when any row has invalid holidayPolicy
        val invalidBackupJson = backupManager.export()
            .replace("\"holidayPolicy\": \"EXCLUDE\"", "\"holidayPolicy\": \"FUTURE_MODE_V99\"")
        val importResult = backupManager.importFromUriStringForTest(
            invalidBackupJson,
            BackupImportOptions(mode = BackupImportMode.Replace, importSettings = false)
        )
        assertTrue("Invalid holidayPolicy in backup JSON must fail closed", importResult.isFailure)
        assertFalse("Journal must not be left behind on staging validation failure", journalFile.exists())

        val untouched = alarmRepo.getAll()
        assertEquals(1, untouched.size)
        assertEquals(existingId, untouched.single().id)
        assertEquals(Alarm.HOLIDAY_POLICY_EXCLUDE, untouched.single().holidayPolicy)

        // 3. Direct AlarmRepository writes reject invalid holidayPolicy fail-closed
        var saveRejected = false
        try {
            alarmRepo.save(
                Alarm(
                    hour = 6,
                    minute = 0,
                    repeatDays = setOf(DayOfWeek.FRIDAY),
                    holidayPolicy = "NOT_A_VALID_POLICY"
                )
            )
        } catch (_: IllegalArgumentException) {
            saveRejected = true
        }
        assertTrue("AlarmRepository.save must reject invalid holidayPolicy", saveRejected)

        var updateRejected = false
        try {
            alarmRepo.update(untouched.single().copy(holidayPolicy = "NOT_A_VALID_POLICY"))
        } catch (_: IllegalArgumentException) {
            updateRejected = true
        }
        assertTrue("AlarmRepository.update must reject invalid holidayPolicy", updateRejected)
    }

    // =========================================================================
    // Test J: Restore -> policy preserved atomically & journal rollback recovery
    // =========================================================================

    @Test
    fun testJ_restorePreservesHolidayPolicyAtomicallyAndRecoversPreparedJournalOnFailure() = runBlocking {
        // Seed old truth with all 3 policies (including Holiday-only INCLUDE)
        val oldIncludeId = alarmRepo.save(
            Alarm(
                hour = 8,
                minute = 45,
                label = "Old Include",
                repeatDays = emptySet(),
                holidayPolicy = Alarm.HOLIDAY_POLICY_INCLUDE
            )
        )
        val oldExcludeId = alarmRepo.save(
            Alarm(
                hour = 7,
                minute = 15,
                label = "Old Exclude",
                repeatDays = setOf(DayOfWeek.MONDAY),
                holidayPolicy = Alarm.HOLIDAY_POLICY_EXCLUDE
            )
        )
        val oldFollowId = alarmRepo.save(
            Alarm(
                hour = 6,
                minute = 30,
                label = "Old Follow",
                repeatDays = setOf(DayOfWeek.FRIDAY),
                skipOnHolidays = true,
                holidayPolicy = Alarm.HOLIDAY_POLICY_FOLLOW_BASE
            )
        )

        val oldSnapshotAlarms = alarmRepo.getAll()
        val oldGroups = db.alarmGroupDao().getAll()

        // Simulate crash in PREPARED phase after DB wipe before markCommitted()
        restoreJournalCoordinator.prepareTransaction(AppSettings(), oldSnapshotAlarms, oldGroups)
        assertTrue(journalFile.exists())
        alarmDao.deleteAll()
        assertTrue(alarmRepo.getAll().isEmpty())

        val recoveryOutcome = restoreJournalCoordinator.checkAndRecover()
        assertEquals(RecoveryOutcome.RESTORED_OLD, recoveryOutcome)
        assertFalse(journalFile.exists())

        val recoveredInclude = alarmRepo.getById(oldIncludeId)!!
        val recoveredExclude = alarmRepo.getById(oldExcludeId)!!
        val recoveredFollow = alarmRepo.getById(oldFollowId)!!
        assertEquals(Alarm.HOLIDAY_POLICY_INCLUDE, recoveredInclude.holidayPolicy)
        assertEquals(Alarm.SCHEDULE_KIND_REPEATING, recoveredInclude.scheduleKind)
        assertTrue(recoveredInclude.repeatDays.isEmpty())
        assertEquals(Alarm.HOLIDAY_POLICY_EXCLUDE, recoveredExclude.holidayPolicy)
        assertEquals(Alarm.HOLIDAY_POLICY_FOLLOW_BASE, recoveredFollow.holidayPolicy)

        // Verify atomic Replace restore commits both Alarm.holidayPolicy and ScheduleAuthority in one transaction
        val exportJson = backupManager.export()
        alarmDao.deleteAll()
        val commitResult = backupManager.importFromUriStringForTest(
            exportJson,
            BackupImportOptions(mode = BackupImportMode.Replace, importSettings = false)
        )
        assertTrue(commitResult.isSuccess)
        assertTrue(journalFile.exists())
        val outcome = restoreJournalCoordinator.checkAndRecover()
        assertEquals(RecoveryOutcome.RECOVERED_NEW, outcome)
        assertFalse(journalFile.exists())

        val restoredInclude = alarmRepo.getById(oldIncludeId)!!
        val restoredIncludeAuth = alarmRepo.getAuthorityByAlarmId(oldIncludeId)!!
        assertEquals(Alarm.HOLIDAY_POLICY_INCLUDE, restoredInclude.holidayPolicy)
        assertEquals(Alarm.SCHEDULE_KIND_REPEATING, restoredInclude.scheduleKind)
        assertEquals(
            ScheduleAuthorityBootstrapAdapter.computeDefinitionRevision(restoredInclude),
            restoredIncludeAuth.definitionRevision
        )
    }

    // =========================================================================
    // Test K: AlarmShareCodec -> policy preserved without silent downgrade
    // =========================================================================

    @Test
    fun testK_alarmShareCodecPreservesAllHolidayPoliciesWithoutDowngradeAndRejectsInvalidPolicy() {
        val cases = listOf(
            Alarm(
                id = 1L,
                hour = 6,
                minute = 30,
                label = "Share Follow Explicit",
                repeatDays = setOf(DayOfWeek.MONDAY),
                skipOnHolidays = true,
                holidayPolicy = Alarm.HOLIDAY_POLICY_FOLLOW_BASE
            ) to Alarm.HOLIDAY_POLICY_FOLLOW_BASE,
            Alarm(
                id = 2L,
                hour = 7,
                minute = 0,
                label = "Share Exclude",
                repeatDays = setOf(DayOfWeek.TUESDAY),
                holidayPolicy = Alarm.HOLIDAY_POLICY_EXCLUDE
            ) to Alarm.HOLIDAY_POLICY_EXCLUDE,
            Alarm(
                id = 3L,
                hour = 9,
                minute = 0,
                label = "Share Holiday Only Include",
                repeatDays = emptySet(),
                holidayPolicy = Alarm.HOLIDAY_POLICY_INCLUDE
            ) to Alarm.HOLIDAY_POLICY_INCLUDE
        )

        for ((sourceAlarm, expectedPolicy) in cases) {
            val token = AlarmShareCodec.encodeToken(sourceAlarm)
            val decoded = AlarmShareCodec.decodeToken(token).getOrThrow()
            val prepared = AlarmShareCodec.prepareImportedAlarm(decoded, defaultLabel = "Shared")
            val stripped = AlarmShareCodec.stripRiskyImportedFields(prepared)

            assertEquals(expectedPolicy, decoded.holidayPolicy)
            assertEquals(expectedPolicy, prepared.holidayPolicy)
            assertEquals(expectedPolicy, stripped.holidayPolicy)
            assertEquals(Alarm.SCHEDULE_KIND_REPEATING, stripped.scheduleKind)
        }

        // Tampered share token with unsupported holidayPolicy must fail closed
        val validToken = AlarmShareCodec.encodeToken(cases.first().first)
        val rawJson = String(Base64.getUrlDecoder().decode(validToken), Charsets.UTF_8)
        val tamperedJson = rawJson.replace("\"FOLLOW_BASE\"", "\"FUTURE_UNSUPPORTED_POLICY\"")
        val tamperedToken = Base64.getUrlEncoder()
            .withoutPadding()
            .encodeToString(tamperedJson.toByteArray(Charsets.UTF_8))

        val tamperedDecode = AlarmShareCodec.decodeToken(tamperedToken)
        assertTrue("Tampered share token with invalid holidayPolicy must fail closed", tamperedDecode.isFailure)
    }

    // =========================================================================
    // Test L: Persisted HolidayPolicy drives Evaluator & MaterialChangeDetector
    // =========================================================================

    @Test
    fun testL_evaluatorAndMaterialChangeDetectorHonorPersistedPoliciesWithoutOverride() = runBlocking {
        val followId = alarmRepo.save(
            Alarm(
                hour = 7,
                minute = 30,
                label = "Persisted Follow",
                repeatDays = setOf(DayOfWeek.THURSDAY, DayOfWeek.FRIDAY),
                holidayPolicy = Alarm.HOLIDAY_POLICY_FOLLOW_BASE
            )
        )
        val excludeId = alarmRepo.save(
            Alarm(
                hour = 7,
                minute = 30,
                label = "Persisted Exclude",
                repeatDays = setOf(DayOfWeek.THURSDAY, DayOfWeek.FRIDAY),
                holidayPolicy = Alarm.HOLIDAY_POLICY_EXCLUDE
            )
        )
        val includeId = alarmRepo.save(
            Alarm(
                hour = 8,
                minute = 30,
                label = "Persisted Include",
                repeatDays = emptySet(),
                holidayPolicy = Alarm.HOLIDAY_POLICY_INCLUDE
            )
        )

        val persistedFollow = alarmRepo.getById(followId)!!
        val persistedExclude = alarmRepo.getById(excludeId)!!
        val persistedInclude = alarmRepo.getById(includeId)!!

        val jan1HolidayClassifier = classifierForHolidays(setOf(thuJan1))

        // Evaluate with holidayPolicyOverride = null (uses persisted Alarm.holidayPolicy!)
        val followEval = evaluator.evaluateWithHolidayClassifier(
            alarm = persistedFollow,
            fromEpochMs = nowJan1At0600Ms,
            holidayClassifier = jan1HolidayClassifier,
            evaluationZoneId = tokyoZone
        )!!
        assertEquals(HolidayPolicy.FOLLOW_BASE, followEval.holidayPolicy)
        assertEquals(epochMillis(thuJan1, LocalTime.of(7, 30), tokyoZone), followEval.targetTriggerTimeEpochMs)

        val excludeEval = evaluator.evaluateWithHolidayClassifier(
            alarm = persistedExclude,
            fromEpochMs = nowJan1At0600Ms,
            holidayClassifier = jan1HolidayClassifier,
            evaluationZoneId = tokyoZone
        )!!
        assertEquals(HolidayPolicy.EXCLUDE, excludeEval.holidayPolicy)
        assertEquals(epochMillis(friJan2, LocalTime.of(7, 30), tokyoZone), excludeEval.targetTriggerTimeEpochMs)

        val includeEval = evaluator.evaluateWithHolidayClassifier(
            alarm = persistedInclude,
            fromEpochMs = nowJan1At0600Ms,
            holidayClassifier = jan1HolidayClassifier,
            evaluationZoneId = tokyoZone
        )!!
        assertEquals(HolidayPolicy.INCLUDE, includeEval.holidayPolicy)
        assertEquals(epochMillis(thuJan1, LocalTime.of(8, 30), tokyoZone), includeEval.targetTriggerTimeEpochMs)

        // Material change detector honors persisted policies without override:
        // WORKDAY -> HOLIDAY on thuJan1 affects EXCLUDE and INCLUDE, never FOLLOW_BASE.
        val change = materialDetector.detectMaterialChange(
            previousClassifier = classifierForHolidays(emptySet()),
            currentClassifier = jan1HolidayClassifier,
            evaluationEpochMs = nowJan1At0600Ms,
            evaluationZoneId = tokyoZone,
            alarms = listOf(
                AlarmHolidayEvaluationInput(alarm = persistedFollow),
                AlarmHolidayEvaluationInput(alarm = persistedExclude),
                AlarmHolidayEvaluationInput(alarm = persistedInclude)
            )
        )
        assertEquals(HolidayTruthChangeOutcome.MATERIAL_CHANGE_DETECTED, change.outcome)
        assertEquals(setOf(excludeId, includeId), change.affectedAlarms.map { it.alarmId }.toSet())
    }

    // =========================================================================
    // Test M: ProductionSchedulingRoute remains LEGACY_ONLY
    // =========================================================================

    @Test
    fun testM_productionSchedulingRouteRemainsLegacyOnly() {
        val routeAuthority = ProductionSchedulingRouteAuthority()
        assertEquals(ProductionSchedulingRoute.LEGACY_ONLY, routeAuthority.currentRoute)
        assertFalse(routeAuthority.isOwnerRouteActive())
    }
}
