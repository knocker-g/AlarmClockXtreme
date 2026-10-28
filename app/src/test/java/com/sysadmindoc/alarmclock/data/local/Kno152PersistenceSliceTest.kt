package com.sysadmindoc.alarmclock.data.local

import android.content.Context
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import com.sysadmindoc.alarmclock.R
import com.sysadmindoc.alarmclock.data.backup.AlarmBackup
import com.sysadmindoc.alarmclock.data.backup.toAlarmOrNull
import com.sysadmindoc.alarmclock.data.local.entity.ScheduleException
import com.sysadmindoc.alarmclock.data.model.Alarm
import com.sysadmindoc.alarmclock.data.model.AlarmOccurrenceId
import com.sysadmindoc.alarmclock.data.repository.AlarmRepository
import com.sysadmindoc.alarmclock.data.repository.ScheduleExceptionRepository
import com.sysadmindoc.alarmclock.data.repository.StaleAlarmException
import com.sysadmindoc.alarmclock.domain.NextAlarmCalculator
import com.sysadmindoc.alarmclock.ui.alarmedit.AlarmEditViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.DayOfWeek

@RunWith(RobolectricTestRunner::class)
class Kno152PersistenceSliceTest {

    private lateinit var context: Context
    private lateinit var db: AlarmDatabase
    private lateinit var alarmDao: AlarmDao
    private lateinit var alarmRepo: AlarmRepository
    private lateinit var exceptionDao: ScheduleExceptionDao
    private lateinit var exceptionRepo: ScheduleExceptionRepository

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, AlarmDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        alarmDao = db.alarmDao()
        alarmRepo = AlarmRepository(db, alarmDao, db.alarmGroupDao())
        exceptionDao = db.scheduleExceptionDao()
        exceptionRepo = ScheduleExceptionRepository(exceptionDao)
    }

    @After
    fun tearDown() {
        try { io.mockk.clearAllMocks() } catch (_: Exception) {}
        db.close()
    }

    @Test
    fun `Normal Alarm defaults scheduleKind to ONE_TIME and generation-revision to 1`() {
        val defaultAlarm = Alarm(hour = 8, minute = 30)
        assertEquals(Alarm.SCHEDULE_KIND_ONE_TIME, defaultAlarm.scheduleKind)
        assertEquals(1, defaultAlarm.generation)
        assertEquals(1, defaultAlarm.revision)
        assertEquals(Alarm.KIND_NORMAL, defaultAlarm.kind)
    }

    @Test
    fun `deriveScheduleKind explicitly identifies REPEATING vs ONE_TIME for Normal and Quick alarms`() {
        // One-time normal alarm without repeatDays or shiftPattern
        val oneTimeNormal = Alarm(hour = 7, minute = 0)
        assertEquals(Alarm.SCHEDULE_KIND_ONE_TIME, oneTimeNormal.deriveScheduleKind())

        // Repeating normal alarm with repeatDays
        val repeatingDaysNormal = Alarm(hour = 7, minute = 0, repeatDays = setOf(DayOfWeek.MONDAY))
        assertEquals(Alarm.SCHEDULE_KIND_REPEATING, repeatingDaysNormal.deriveScheduleKind())

        // Repeating normal alarm with valid shiftPattern and start date
        val shiftPatternNormal = Alarm(hour = 7, minute = 0, shiftPattern = "D/N/W", shiftPatternStartDate = "2026-01-01")
        assertEquals(Alarm.SCHEDULE_KIND_REPEATING, shiftPatternNormal.deriveScheduleKind())

        // Invalid shiftPattern without start date derives ONE_TIME
        val invalidShiftNormal = Alarm(hour = 7, minute = 0, shiftPattern = "INVALID_KEY", shiftPatternStartDate = "")
        assertEquals(Alarm.SCHEDULE_KIND_ONE_TIME, invalidShiftNormal.deriveScheduleKind())

        // skipOnHolidays alone with empty repeatDays derives ONE_TIME (skipOnHolidays is exclusion rule, not schedule generator)
        val holidayExclusionNormal = Alarm(hour = 7, minute = 0, skipOnHolidays = true)
        assertEquals(Alarm.SCHEDULE_KIND_ONE_TIME, holidayExclusionNormal.deriveScheduleKind())

        // Explicit REPEATING classification is preserved
        val explicitRepeating = Alarm(hour = 7, minute = 0, scheduleKind = Alarm.SCHEDULE_KIND_REPEATING)
        assertEquals(Alarm.SCHEDULE_KIND_REPEATING, explicitRepeating.withDerivedScheduleKind().scheduleKind)

        // Quick Alarm is always ONE_TIME
        val quickAlarm = Alarm(hour = 7, minute = 0, kind = Alarm.KIND_QUICK, repeatDays = setOf(DayOfWeek.MONDAY))
        assertEquals(Alarm.SCHEDULE_KIND_ONE_TIME, quickAlarm.deriveScheduleKind())
    }

    @Test
    fun `AlarmRepository automatically manages generation and revision evolution on save and update`() = runBlocking {
        // 1. Save new alarm
        val initialAlarm = Alarm(hour = 7, minute = 0, repeatDays = setOf(DayOfWeek.MONDAY))
        val id = alarmRepo.save(initialAlarm)
        val saved1 = alarmRepo.getById(id)!!

        assertEquals(1, saved1.generation)
        assertEquals(1, saved1.revision)
        assertEquals(Alarm.SCHEDULE_KIND_REPEATING, saved1.scheduleKind)

        // 2. Secondary setting update (label) -> increments revision, generation remains 1
        alarmRepo.update(saved1.copy(label = "Updated Morning Label"))
        val saved2 = alarmRepo.getById(id)!!
        assertEquals(1, saved2.generation)
        assertEquals(2, saved2.revision)
        assertEquals("Updated Morning Label", saved2.label)

        // 3. Core schedule definition update (hour changed to 8) -> increments generation, resets revision to 1
        alarmRepo.update(saved2.copy(hour = 8))
        val saved3 = alarmRepo.getById(id)!!
        assertEquals(2, saved3.generation)
        assertEquals(1, saved3.revision)
        assertEquals(8, saved3.hour)

        // 4. Intentional change from REPEATING to ONE_TIME by clearing repeatDays -> updates scheduleKind to ONE_TIME and increments generation
        alarmRepo.update(saved3.copy(repeatDays = emptySet(), scheduleKind = Alarm.SCHEDULE_KIND_ONE_TIME))
        val saved4 = alarmRepo.getById(id)!!
        assertEquals(3, saved4.generation)
        assertEquals(1, saved4.revision)
        assertEquals(Alarm.SCHEDULE_KIND_ONE_TIME, saved4.scheduleKind)
    }

    @Test
    fun `Optimistic Concurrency Control - Outdated draft displays distinct stale error message and deleted alarm protection`() = runBlocking {
        val initial = Alarm(hour = 7, minute = 0, label = "Original")
        val id = alarmRepo.save(initial)
        val currentDb = alarmRepo.getById(id)!!

        // Save a newer edit first (e.g. User changed hour to 8)
        alarmRepo.update(currentDb.copy(hour = 8))
        val updatedDb = alarmRepo.getById(id)!!
        assertEquals(2, updatedDb.generation)

        // Attempting to save an outdated draft snapshot holding generation = 1 throws StaleAlarmException
        val staleDraft = currentDb.copy(label = "Stale Edit from Old Draft")
        var staleRejected = false
        try {
            alarmRepo.update(staleDraft)
        } catch (e: StaleAlarmException) {
            staleRejected = true
        }

        assertTrue("Stale draft update MUST be rejected by AlarmRepository", staleRejected)
        // Verify DB entity remains at generation = 2
        assertEquals(2, alarmRepo.getById(id)!!.generation)
        assertEquals(8, alarmRepo.getById(id)!!.hour)

        // Test deleted alarm protection
        val tempAlarm = Alarm(hour = 9, minute = 0, label = "Temp Alarm")
        val tempId = alarmRepo.save(tempAlarm)
        val tempSaved = alarmRepo.getById(tempId)!!
        alarmRepo.delete(tempSaved)

        var deletedRejected = false
        try {
            alarmRepo.update(tempSaved.copy(label = "Update Deleted"))
        } catch (e: StaleAlarmException) {
            deletedRejected = true
        }
        assertTrue("Update on deleted alarm MUST be rejected with StaleAlarmException", deletedRejected)
        assertEquals("Deleted alarm MUST NOT be re-created in Room DB", null, alarmRepo.getById(tempId))
    }

    @Test
    fun `KNO-32 Alignment - Occurrence identity depends on generation so revision changes keep occurrence identity stable`() {
        val baseTime = 1700000000000L
        val alarmRev1 = Alarm(id = 42L, generation = 2, revision = 1, label = "Morning")
        val alarmRev2 = alarmRev1.copy(revision = 2, label = "Morning - Edited Volume")

        val occurrenceRev1 = AlarmOccurrenceId.fromAlarm(alarmRev1, baseTime)
        val occurrenceRev2 = AlarmOccurrenceId.fromAlarm(alarmRev2, baseTime)

        // KNO-32 invariant: Occurrence identity is stable across revision edits
        assertEquals(occurrenceRev1, occurrenceRev2)
        assertEquals("alarm_42_gen_2_base_1700000000000", occurrenceRev1.key)
        assertEquals("alarm_42_gen_2_base_1700000000000", occurrenceRev2.key)

        // Identity changes ONLY when schedule generation increments
        val alarmGen3 = alarmRev2.copy(generation = 3, revision = 1, hour = 8)
        val occurrenceGen3 = AlarmOccurrenceId.fromAlarm(alarmGen3, baseTime)

        assertNotEquals(occurrenceRev1, occurrenceGen3)
        assertEquals("alarm_42_gen_3_base_1700000000000", occurrenceGen3.key)
    }

    @Test
    fun `ScheduleException CRUD operations persist and filter by generation correctly`() = runBlocking {
        val alarmId = 100L
        val baseTime = 1700000000000L

        val gen1Exception = ScheduleException(
            alarmId = alarmId,
            baseTriggerTime = baseTime,
            exceptionType = ScheduleException.TYPE_SKIP,
            generation = 1,
            revision = 1
        )

        val gen2Exception = ScheduleException(
            alarmId = alarmId,
            baseTriggerTime = baseTime,
            exceptionType = ScheduleException.TYPE_TIME_OVERRIDE,
            overrideTriggerTime = baseTime + 90000000L,
            generation = 2,
            revision = 1
        )

        exceptionRepo.addException(gen1Exception)
        exceptionRepo.addException(gen2Exception)

        val gen1Exceptions = exceptionRepo.getExceptionsForAlarmGeneration(alarmId, 1)
        assertEquals(1, gen1Exceptions.size)
        assertEquals(ScheduleException.TYPE_SKIP, gen1Exceptions[0].exceptionType)

        val gen2Exceptions = exceptionRepo.getExceptionsForAlarmGeneration(alarmId, 2)
        assertEquals(1, gen2Exceptions.size)
        assertEquals(ScheduleException.TYPE_TIME_OVERRIDE, gen2Exceptions[0].exceptionType)

        // Query by exact occurrence (alarmId, baseTime, generation)
        val gen1Exact = exceptionRepo.getExceptionForOccurrence(alarmId, baseTime, 1)
        assertNotNull(gen1Exact)
        assertEquals(ScheduleException.TYPE_SKIP, gen1Exact!!.exceptionType)

        val gen2Exact = exceptionRepo.getExceptionForOccurrence(alarmId, baseTime, 2)
        assertNotNull(gen2Exact)
        assertEquals(ScheduleException.TYPE_TIME_OVERRIDE, gen2Exact!!.exceptionType)

        // Test deletion for specific occurrence
        exceptionRepo.deleteForOccurrence(alarmId, baseTime, 1)
        val afterDeleteGen1 = exceptionRepo.getExceptionForOccurrence(alarmId, baseTime, 1)
        assertEquals(null, afterDeleteGen1)

        val afterDeleteGen2 = exceptionRepo.getExceptionForOccurrence(alarmId, baseTime, 2)
        assertNotNull(afterDeleteGen2)
    }

    @Test
    fun `ScheduleException cleanup purges expired exceptions prior to threshold`() = runBlocking {
        val alarmId = 101L
        val oldTime = 100000L
        val newTime = 200000L

        exceptionRepo.addException(ScheduleException(alarmId = alarmId, baseTriggerTime = oldTime, exceptionType = ScheduleException.TYPE_SKIP))
        exceptionRepo.addException(ScheduleException(alarmId = alarmId, baseTriggerTime = newTime, exceptionType = ScheduleException.TYPE_TIME_OVERRIDE, overrideTriggerTime = newTime + 3600000L))

        exceptionRepo.deleteExpiredExceptions(150000L)

        val remaining = exceptionRepo.getAll()
        assertEquals(1, remaining.size)
        assertEquals(newTime, remaining[0].baseTriggerTime)
    }

    @Test
    fun `Migration 33 to 34 migrates alarms and populates scheduleKind generation revision and schedule_exceptions table`() {
        val factory = FrameworkSQLiteOpenHelperFactory()
        val config = SupportSQLiteOpenHelper.Configuration.builder(context)
            .name(null)
            .callback(object : SupportSQLiteOpenHelper.Callback(33) {
                override fun onCreate(sqliteDb: SupportSQLiteDatabase) {
                    sqliteDb.execSQL("""
                        CREATE TABLE IF NOT EXISTS `alarms` (
                            `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                            `hour` INTEGER NOT NULL,
                            `minute` INTEGER NOT NULL,
                            `label` TEXT NOT NULL,
                            `isEnabled` INTEGER NOT NULL,
                            `repeatDays` TEXT NOT NULL,
                            `ringtoneUri` TEXT NOT NULL,
                            `vibrationEnabled` INTEGER NOT NULL,
                            `vibrationIntensity` INTEGER NOT NULL,
                            `volume` INTEGER NOT NULL,
                            `overrideSystemVolume` INTEGER NOT NULL,
                            `gradualVolumeSeconds` INTEGER NOT NULL,
                            `snoozeDurationMinutes` INTEGER NOT NULL,
                            `maxSnoozeCount` INTEGER NOT NULL,
                            `showOnLockScreen` INTEGER NOT NULL,
                            `challengeType` TEXT NOT NULL,
                            `group` TEXT NOT NULL,
                            `flashWake` INTEGER NOT NULL,
                            `vibrationPattern` TEXT NOT NULL,
                            `createdAt` INTEGER NOT NULL,
                            `nextTriggerTime` INTEGER NOT NULL,
                            `ttsEnabled` INTEGER NOT NULL,
                            `walkStepsRequired` INTEGER NOT NULL,
                            `wakeConfirmEnabled` INTEGER NOT NULL,
                            `wakeConfirmDelayMinutes` INTEGER NOT NULL,
                            `smartAlarmEnabled` INTEGER NOT NULL,
                            `smartAlarmWindowMinutes` INTEGER NOT NULL,
                            `skipOnHolidays` INTEGER NOT NULL,
                            `nfcTagId` TEXT NOT NULL,
                            `barcodeValue` TEXT NOT NULL,
                            `spotifyUri` TEXT NOT NULL,
                            `hueEnabled` INTEGER NOT NULL,
                            `huePreWakeMinutes` INTEGER NOT NULL,
                            `photoMatchUri` TEXT NOT NULL,
                            `challengeChain` TEXT NOT NULL,
                            `progressiveSnooze` INTEGER NOT NULL,
                            `backupSoundEnabled` INTEGER NOT NULL,
                            `backupSoundDelaySec` INTEGER NOT NULL,
                            `sunriseSimulation` INTEGER NOT NULL,
                            `sunriseMinutes` INTEGER NOT NULL,
                            `specificDate` TEXT NOT NULL,
                            `profileName` TEXT NOT NULL,
                            `earlyDismissMinutes` INTEGER NOT NULL,
                            `guardianEnabled` INTEGER NOT NULL,
                            `guardianPhone` TEXT NOT NULL,
                            `guardianDelaySec` INTEGER NOT NULL,
                            `locationDismissEnabled` INTEGER NOT NULL,
                            `locationDismissLat` REAL NOT NULL,
                            `locationDismissLng` REAL NOT NULL,
                            `locationDismissRadius` INTEGER NOT NULL,
                            `wifiDismissSsid` TEXT NOT NULL,
                            `internetRadioUrl` TEXT NOT NULL,
                            `flashlightStrobe` INTEGER NOT NULL,
                            `morningRoutine` TEXT NOT NULL,
                            `hardwareButtonAction` TEXT NOT NULL,
                            `dismissAtRingtoneEnd` INTEGER NOT NULL,
                            `holdToDismissEnabled` INTEGER NOT NULL,
                            `ringtonePool` TEXT NOT NULL,
                            `solarOffsetMinutes` INTEGER NOT NULL,
                            `solarAnchor` TEXT NOT NULL,
                            `vibrationDelaySeconds` INTEGER NOT NULL,
                            `weatherEarlyMinutes` INTEGER NOT NULL,
                            `requiredSquats` INTEGER NOT NULL,
                            `dismissActionType` TEXT NOT NULL,
                            `dismissActionPayload` TEXT NOT NULL,
                            `firingBackgroundImageEnabled` INTEGER NOT NULL,
                            `firingBackgroundImageUri` TEXT NOT NULL,
                            `firingBackgroundBlurEnabled` INTEGER NOT NULL,
                            `sortOrder` INTEGER NOT NULL,
                            `shiftPattern` TEXT NOT NULL,
                            `shiftPatternStartDate` TEXT NOT NULL,
                            `timezonePolicy` TEXT NOT NULL,
                            `fixedTimezoneId` TEXT NOT NULL,
                            `kind` TEXT NOT NULL DEFAULT 'NORMAL',
                            `quickTriggerEpochMs` INTEGER NOT NULL DEFAULT 0,
                            `quickRelativeElapsedMs` INTEGER NOT NULL DEFAULT 0,
                            `quickBootCount` INTEGER NOT NULL DEFAULT -1
                        )
                    """.trimIndent())

                    // Insert repeating Normal alarm into v33 database
                    sqliteDb.execSQL("""
                        INSERT INTO alarms (
                            id, hour, minute, label, isEnabled, repeatDays, ringtoneUri, vibrationEnabled,
                            vibrationIntensity, volume, overrideSystemVolume, gradualVolumeSeconds,
                            snoozeDurationMinutes, maxSnoozeCount, showOnLockScreen, challengeType, `group`,
                            flashWake, vibrationPattern, createdAt, nextTriggerTime, ttsEnabled,
                            walkStepsRequired, wakeConfirmEnabled, wakeConfirmDelayMinutes, smartAlarmEnabled,
                            smartAlarmWindowMinutes, skipOnHolidays, nfcTagId, barcodeValue, spotifyUri,
                            hueEnabled, huePreWakeMinutes, photoMatchUri, challengeChain, progressiveSnooze,
                            backupSoundEnabled, backupSoundDelaySec, sunriseSimulation, sunriseMinutes,
                            specificDate, profileName, earlyDismissMinutes, guardianEnabled, guardianPhone,
                            guardianDelaySec, locationDismissEnabled, locationDismissLat, locationDismissLng,
                            locationDismissRadius, wifiDismissSsid, internetRadioUrl, flashlightStrobe,
                            morningRoutine, hardwareButtonAction, dismissAtRingtoneEnd, holdToDismissEnabled,
                            ringtonePool, solarOffsetMinutes, solarAnchor, vibrationDelaySeconds,
                            weatherEarlyMinutes, requiredSquats, dismissActionType, dismissActionPayload,
                            firingBackgroundImageEnabled, firingBackgroundImageUri, firingBackgroundBlurEnabled,
                            sortOrder, shiftPattern, shiftPatternStartDate, timezonePolicy, fixedTimezoneId,
                            kind, quickTriggerEpochMs, quickRelativeElapsedMs, quickBootCount
                        ) VALUES (
                            1, 7, 0, 'Morning Work', 1, 'MONDAY,TUESDAY', '', 1,
                            2, 100, 1, 60,
                            10, 3, 1, 'NONE', 'Work',
                            0, 'default', 1600000000000, 1700000000000, 0,
                            30, 0, 10, 0,
                            30, 0, '', '', '',
                            0, 30, '', '', 0,
                            0, 40, 0, 15,
                            '', '', 0, 0, '',
                            300, 0, 0.0, 0.0,
                            100, '', '', 0,
                            '', 'NONE', 0, 0,
                            '', 0, 'SUNRISE', 0,
                            0, 10, 'NONE', '',
                            0, '', 1,
                            1, 'D/N/W', '2026-01-01', 'LOCAL', '',
                            'NORMAL', 0, 0, -1
                        )
                    """.trimIndent())

                    // Insert ONE_TIME alarm with skipOnHolidays = 1 in v33 database
                    sqliteDb.execSQL("""
                        INSERT INTO alarms (
                            id, hour, minute, label, isEnabled, repeatDays, ringtoneUri, vibrationEnabled,
                            vibrationIntensity, volume, overrideSystemVolume, gradualVolumeSeconds,
                            snoozeDurationMinutes, maxSnoozeCount, showOnLockScreen, challengeType, `group`,
                            flashWake, vibrationPattern, createdAt, nextTriggerTime, ttsEnabled,
                            walkStepsRequired, wakeConfirmEnabled, wakeConfirmDelayMinutes, smartAlarmEnabled,
                            smartAlarmWindowMinutes, skipOnHolidays, nfcTagId, barcodeValue, spotifyUri,
                            hueEnabled, huePreWakeMinutes, photoMatchUri, challengeChain, progressiveSnooze,
                            backupSoundEnabled, backupSoundDelaySec, sunriseSimulation, sunriseMinutes,
                            specificDate, profileName, earlyDismissMinutes, guardianEnabled, guardianPhone,
                            guardianDelaySec, locationDismissEnabled, locationDismissLat, locationDismissLng,
                            locationDismissRadius, wifiDismissSsid, internetRadioUrl, flashlightStrobe,
                            morningRoutine, hardwareButtonAction, dismissAtRingtoneEnd, holdToDismissEnabled,
                            ringtonePool, solarOffsetMinutes, solarAnchor, vibrationDelaySeconds,
                            weatherEarlyMinutes, requiredSquats, dismissActionType, dismissActionPayload,
                            firingBackgroundImageEnabled, firingBackgroundImageUri, firingBackgroundBlurEnabled,
                            sortOrder, shiftPattern, shiftPatternStartDate, timezonePolicy, fixedTimezoneId,
                            kind, quickTriggerEpochMs, quickRelativeElapsedMs, quickBootCount
                        ) VALUES (
                            2, 8, 0, 'One Time Holiday', 1, '', '', 1,
                            2, 100, 1, 60,
                            10, 3, 1, 'NONE', '',
                            0, 'default', 1600000000000, 1700000900000, 0,
                            30, 0, 10, 0,
                            30, 1, '', '', '',
                            0, 30, '', '', 0,
                            0, 40, 0, 15,
                            '', '', 0, 0, '',
                            300, 0, 0.0, 0.0,
                            100, '', '', 0,
                            '', 'NONE', 0, 0,
                            '', 0, 'SUNRISE', 0,
                            0, 10, 'NONE', '',
                            0, '', 1,
                            2, '', '', 'LOCAL', '',
                            'NORMAL', 0, 0, -1
                        )
                    """.trimIndent())
                }

                override fun onUpgrade(sqliteDb: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {}
            })
            .build()

        val openHelper = factory.create(config)
        val sqliteDb = openHelper.writableDatabase

        // Execute Room MIGRATION_33_34
        AlarmDatabase.MIGRATION_33_34.migrate(sqliteDb)

        // Verify migrated columns in alarms table
        val cursor = sqliteDb.query("SELECT id, scheduleKind, generation, revision, kind FROM alarms ORDER BY id ASC")
        assertTrue(cursor.moveToNext())

        // Alarm 1 (Repeating Normal)
        assertEquals(1L, cursor.getLong(0))
        assertEquals(Alarm.SCHEDULE_KIND_REPEATING, cursor.getString(1))
        assertEquals(1, cursor.getInt(2))
        assertEquals(1, cursor.getInt(3))

        // Alarm 2 (One-time Normal with skipOnHolidays = 1) -> MUST remain ONE_TIME
        assertTrue(cursor.moveToNext())
        assertEquals(2L, cursor.getLong(0))
        assertEquals(Alarm.SCHEDULE_KIND_ONE_TIME, cursor.getString(1))
        assertEquals(1, cursor.getInt(2))
        assertEquals(1, cursor.getInt(3))

        cursor.close()

        // Verify schedule_exceptions table exists
        val exceptionsCursor = sqliteDb.query("SELECT count(*) FROM schedule_exceptions")
        assertTrue(exceptionsCursor.moveToNext())
        assertEquals(0, exceptionsCursor.getInt(0))
        exceptionsCursor.close()

        sqliteDb.close()
    }

    @Test
    fun `Portable backup export and import handles legacy and modern scheduleKind compatibility correctly`() {
        // Modern backup with explicit scheduleKind
        val modernBackup = AlarmBackup(
            id = 10L,
            hour = 7,
            minute = 30,
            label = "Modern One Time",
            isEnabled = true,
            repeatDays = emptyList(),
            scheduleKind = Alarm.SCHEDULE_KIND_ONE_TIME,
            ringtoneUri = "",
            vibrationEnabled = true,
            vibrationIntensity = 2,
            volume = 100,
            overrideSystemVolume = true,
            gradualVolumeSeconds = 60,
            snoozeDurationMinutes = 10,
            maxSnoozeCount = 3,
            showOnLockScreen = true,
            challengeType = "NONE"
        )

        val restoredModern = modernBackup.toAlarmOrNull()
        assertNotNull(restoredModern)
        assertEquals(Alarm.SCHEDULE_KIND_ONE_TIME, restoredModern!!.scheduleKind)

        // Legacy backup where scheduleKind is null
        val legacyRepeatingBackup = AlarmBackup(
            id = 11L,
            hour = 8,
            minute = 0,
            label = "Legacy Repeating",
            isEnabled = true,
            repeatDays = listOf("MONDAY", "TUESDAY"),
            scheduleKind = null,
            ringtoneUri = "",
            vibrationEnabled = true,
            vibrationIntensity = 2,
            volume = 100,
            overrideSystemVolume = true,
            gradualVolumeSeconds = 60,
            snoozeDurationMinutes = 10,
            maxSnoozeCount = 3,
            showOnLockScreen = true,
            challengeType = "NONE"
        )

        val restoredLegacy = legacyRepeatingBackup.toAlarmOrNull()
        assertNotNull(restoredLegacy)
        assertEquals(Alarm.SCHEDULE_KIND_REPEATING, restoredLegacy!!.scheduleKind)

        // Verify AlarmBackup payload class does not contain ScheduleException fields
        val backupClassFields = modernBackup.javaClass.declaredFields.map { it.name }
        assertFalse("AlarmBackup payload MUST NOT contain ScheduleException instances or tables", backupClassFields.contains("exceptions"))
        assertFalse("AlarmBackup payload MUST NOT contain ScheduleException instances or tables", backupClassFields.contains("scheduleExceptions"))
    }
}
