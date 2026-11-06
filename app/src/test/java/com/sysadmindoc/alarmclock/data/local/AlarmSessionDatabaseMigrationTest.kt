package com.sysadmindoc.alarmclock.data.local

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AlarmSessionDatabaseMigrationTest {

    @get:Rule
    val helper: MigrationTestHelper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AlarmDatabase::class.java.canonicalName,
        FrameworkSQLiteOpenHelperFactory()
    )

    @Test
    fun migrate36To37PreservesExistingDataAndCreatesNewTables() {
        // Create DB at version 36
        var db = helper.createDatabase(TEST_DB, 36).apply {
            execSQL("INSERT INTO alarms (id, hour, minute, label, isEnabled, repeatDays, ringtoneUri, vibrationEnabled, vibrationIntensity, volume, overrideSystemVolume, gradualVolumeSeconds, snoozeDurationMinutes, maxSnoozeCount, showOnLockScreen, challengeType, `group`, flashWake, vibrationPattern, createdAt, nextTriggerTime, ttsEnabled, walkStepsRequired, wakeConfirmEnabled, wakeConfirmDelayMinutes, smartAlarmEnabled, smartAlarmWindowMinutes, skipOnHolidays, nfcTagId, barcodeValue, spotifyUri, hueEnabled, huePreWakeMinutes, photoMatchUri, challengeChain, progressiveSnooze, backupSoundEnabled, backupSoundDelaySec, sunriseSimulation, sunriseMinutes, specificDate, profileName, earlyDismissMinutes, guardianEnabled, guardianPhone, guardianDelaySec, locationDismissEnabled, locationDismissLat, locationDismissLng, locationDismissRadius, wifiDismissSsid, internetRadioUrl, flashlightStrobe, morningRoutine, hardwareButtonAction, dismissAtRingtoneEnd, holdToDismissEnabled, ringtonePool, solarOffsetMinutes, solarAnchor, vibrationDelaySeconds, weatherEarlyMinutes, requiredSquats, dismissActionType, dismissActionPayload, firingBackgroundImageEnabled, firingBackgroundImageUri, firingBackgroundBlurEnabled, sortOrder, shiftPattern, shiftPatternStartDate, timezonePolicy, fixedTimezoneId, kind, quickTriggerEpochMs, quickRelativeElapsedMs, quickBootCount, scheduleKind, generation, revision, holidayPolicy) VALUES (1, 6, 0, 'Test Alarm', 1, '', '', 1, 2, 100, 1, 60, 10, 3, 1, 'NONE', '', 0, 'default', 1000, 2000, 0, 0, 0, 0, 0, 0, 0, '', '', '', 0, 0, '', '', 0, 0, 0, 0, 0, '', '', 0, 0, '', 0, 0, 0.0, 0.0, 0, '', '', 0, '', 'NONE', 0, 0, '', 0, 'NONE', 0, 0, 0, 'DISMISS_CARD', '', 0, '', 0, 0, 'NONE', '', 'FLOATING', '', 'NORMAL', 0, 0, -1, 'ONE_TIME', 1, 1, 'FOLLOW_BASE')")
            close()
        }

        // Migrate to version 37
        db = helper.runMigrationsAndValidate(TEST_DB, 37, true, AlarmDatabase.MIGRATION_36_37)

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

        val seqCursor = db.query("SELECT COUNT(*) FROM session_sequence_generator")
        assertTrue(seqCursor.moveToFirst())
        assertEquals(0L, seqCursor.getLong(0))
        seqCursor.close()
    }

    companion object {
        private const val TEST_DB = "migration-test-36-37.db"
    }
}
