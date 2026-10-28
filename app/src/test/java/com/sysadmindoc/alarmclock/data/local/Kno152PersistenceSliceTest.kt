package com.sysadmindoc.alarmclock.data.local

import android.content.Context
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import com.sysadmindoc.alarmclock.data.backup.AlarmBackup
import com.sysadmindoc.alarmclock.data.local.entity.ScheduleException
import com.sysadmindoc.alarmclock.data.model.Alarm
import com.sysadmindoc.alarmclock.data.model.AlarmOccurrenceId
import com.sysadmindoc.alarmclock.data.repository.AlarmRepository
import com.sysadmindoc.alarmclock.data.repository.ScheduleExceptionRepository
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

        // Repeating normal alarm with shiftPattern
        val shiftPatternNormal = Alarm(hour = 7, minute = 0, shiftPattern = "D/N/W")
        assertEquals(Alarm.SCHEDULE_KIND_REPEATING, shiftPatternNormal.deriveScheduleKind())

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
    }

    @Test
    fun `AlarmOccurrenceId provides stable key based on alarmId baseTriggerTime generation and revision`() {
        val alarm = Alarm(id = 42L, generation = 2, revision = 3)
        val baseTriggerTime = 1700000000000L

        val occurrenceId1 = AlarmOccurrenceId.fromAlarm(alarm, baseTriggerTime)
        val occurrenceId2 = AlarmOccurrenceId.fromAlarm(alarm, baseTriggerTime)

        assertEquals(occurrenceId1, occurrenceId2)
        assertEquals("alarm_42_gen_2_rev_3_base_1700000000000", occurrenceId1.key)

        // Identity changes if generation or revision changes, even with same baseTriggerTime
        val mutatedAlarm = alarm.copy(revision = 4)
        val mutatedOccurrenceId = AlarmOccurrenceId.fromAlarm(mutatedAlarm, baseTriggerTime)

        assertNotEquals(occurrenceId1, mutatedOccurrenceId)
        assertNotEquals(occurrenceId1.key, mutatedOccurrenceId.key)
    }

    @Test
    fun `ScheduleException CRUD operations persist and filter by generation and revision correctly`() = runBlocking {
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

        // Query by exact occurrence (alarmId, baseTime, generation, revision)
        val gen1Exact = exceptionRepo.getExceptionForOccurrence(alarmId, baseTime, 1, 1)
        assertNotNull(gen1Exact)
        assertEquals(ScheduleException.TYPE_SKIP, gen1Exact!!.exceptionType)

        val gen2Exact = exceptionRepo.getExceptionForOccurrence(alarmId, baseTime, 2, 1)
        assertNotNull(gen2Exact)
        assertEquals(ScheduleException.TYPE_TIME_OVERRIDE, gen2Exact!!.exceptionType)

        // Test deletion for specific occurrence
        exceptionRepo.deleteForOccurrence(alarmId, baseTime, 1, 1)
        val afterDeleteGen1 = exceptionRepo.getExceptionForOccurrence(alarmId, baseTime, 1, 1)
        assertEquals(null, afterDeleteGen1)

        val afterDeleteGen2 = exceptionRepo.getExceptionForOccurrence(alarmId, baseTime, 2, 1)
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

                    // Insert Quick Alarm into v33 database
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
                            2, 8, 0, 'Quick Timer', 1, '', '', 1,
                            2, 100, 1, 60,
                            10, 3, 1, 'NONE', '',
                            0, 'default', 1600000000000, 1700000900000, 0,
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
                            2, '', '', 'LOCAL', '',
                            'QUICK', 1700000900000, 900000, 42
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
        val cursor = sqliteDb.query("SELECT id, scheduleKind, generation, revision, kind, quickTriggerEpochMs, quickBootCount FROM alarms ORDER BY id ASC")
        assertTrue(cursor.moveToNext())

        // Alarm 1 (Repeating Normal)
        assertEquals(1L, cursor.getLong(0))
        assertEquals(Alarm.SCHEDULE_KIND_REPEATING, cursor.getString(1))
        assertEquals(1, cursor.getInt(2))
        assertEquals(1, cursor.getInt(3))
        assertEquals(Alarm.KIND_NORMAL, cursor.getString(4))

        // Alarm 2 (Quick Alarm)
        assertTrue(cursor.moveToNext())
        assertEquals(2L, cursor.getLong(0))
        assertEquals(Alarm.SCHEDULE_KIND_ONE_TIME, cursor.getString(1))
        assertEquals(1, cursor.getInt(2))
        assertEquals(1, cursor.getInt(3))
        assertEquals(Alarm.KIND_QUICK, cursor.getString(4))
        assertEquals(1700000900000L, cursor.getLong(5))
        assertEquals(42L, cursor.getLong(6))

        cursor.close()

        // Verify schedule_exceptions table exists with revision column
        val exceptionsCursor = sqliteDb.query("SELECT count(*), id, generation, revision FROM schedule_exceptions")
        assertTrue(exceptionsCursor.moveToNext())
        assertEquals(0, exceptionsCursor.getInt(0))
        exceptionsCursor.close()

        sqliteDb.close()
    }

    @Test
    fun `Portable backup export excludes runtime ScheduleExceptions`() {
        val alarmBackup = AlarmBackup(
            id = 10L,
            hour = 7,
            minute = 30,
            label = "Backup Test Alarm",
            isEnabled = true,
            repeatDays = listOf("MONDAY", "FRIDAY"),
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

        assertEquals(10L, alarmBackup.id)
        assertEquals(7, alarmBackup.hour)
        assertEquals(30, alarmBackup.minute)
        assertEquals("Backup Test Alarm", alarmBackup.label)

        // Verify AlarmBackup payload class does not contain ScheduleException fields
        val backupClassFields = alarmBackup.javaClass.declaredFields.map { it.name }
        assertFalse("AlarmBackup payload MUST NOT contain ScheduleException instances or tables", backupClassFields.contains("exceptions"))
        assertFalse("AlarmBackup payload MUST NOT contain ScheduleException instances or tables", backupClassFields.contains("scheduleExceptions"))
    }
}
