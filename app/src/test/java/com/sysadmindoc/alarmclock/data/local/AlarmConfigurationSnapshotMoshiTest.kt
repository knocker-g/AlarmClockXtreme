package com.sysadmindoc.alarmclock.data.local

import com.squareup.moshi.Moshi
import com.sysadmindoc.alarmclock.data.local.entity.AlarmConfigurationSnapshot
import com.sysadmindoc.alarmclock.data.model.Alarm
import com.sysadmindoc.alarmclock.data.preferences.AppSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class AlarmConfigurationSnapshotMoshiTest {

    private val moshi = Moshi.Builder().build()
    private val adapter = moshi.adapter(AlarmConfigurationSnapshot::class.java)

    @Test
    fun moshiSerializesAndDeserializesSnapshotAccurately() {
        val originalSnapshot = AlarmConfigurationSnapshot(
            snapshotVersion = 1,
            alarmId = 100L,
            kind = "NORMAL",
            label = "Morning Workout",
            group = "Fitness",
            ringtoneUri = "content://media/internal/audio/media/42",
            ringtonePool = "birds,ocean",
            vibrationEnabled = true,
            vibrationIntensity = 3,
            vibrationPattern = "sos",
            volume = 80,
            overrideSystemVolume = true,
            gradualVolumeSeconds = 30,
            snoozeEnabled = true,
            snoozeDurationMinutes = 5,
            maxSnoozeCount = 2,
            progressiveSnoozeEnabled = true,
            progressiveSnoozeDecreaseMinutes = 1,
            progressiveSnoozeMinMinutes = 1,
            wakeConfirmEnabled = true,
            wakeConfirmDelayMinutes = 10,
            autoSilenceMinutes = 15,
            quickAutoSilenceMinutes = 0,
            challengeType = "MATH_MEDIUM",
            challengeChain = "MATH_MEDIUM,SHAKE",
            walkStepsRequired = 30,
            requiredSquats = 10,
            holdToDismissEnabled = true,
            hardwareButtonAction = "SNOOZE",
            dismissAtRingtoneEnd = false
        )

        val json = adapter.toJson(originalSnapshot)
        assertNotNull(json)
        assertTrue(json.contains("\"alarmId\":100"))
        assertTrue(json.contains("\"label\":\"Morning Workout\""))

        val deserializedSnapshot = adapter.fromJson(json)
        assertNotNull(deserializedSnapshot)
        assertEquals(originalSnapshot, deserializedSnapshot)
    }

    @Test
    fun factoryCreatesSnapshotFromAlarmAndSettingsForNormalAlarm() {
        val alarm = Alarm(
            id = 101L,
            label = "Test Alarm",
            snoozeDurationMinutes = 8,
            maxSnoozeCount = 5,
            progressiveSnooze = true,
            wakeConfirmEnabled = true,
            wakeConfirmDelayMinutes = 15,
            challengeType = "SHAKE"
        )
        val settings = AppSettings(autoSilenceMinutes = 20)

        val snapshot = AlarmConfigurationSnapshot.from(
            alarm = alarm,
            settings = settings,
            effectiveNormalAutoSilenceMinutes = 15
        )
        assertEquals(101L, snapshot.alarmId)
        assertEquals("NORMAL", snapshot.kind)
        assertEquals("Test Alarm", snapshot.label)
        assertEquals(8, snapshot.snoozeDurationMinutes)
        assertEquals(5, snapshot.maxSnoozeCount)
        assertTrue(snapshot.progressiveSnoozeEnabled)
        assertEquals(1, snapshot.progressiveSnoozeDecreaseMinutes)
        assertEquals(1, snapshot.progressiveSnoozeMinMinutes)
        assertEquals(15, snapshot.autoSilenceMinutes)
        assertEquals(0, snapshot.quickAutoSilenceMinutes)
        assertTrue(snapshot.wakeConfirmEnabled)
        assertEquals(15, snapshot.wakeConfirmDelayMinutes)
    }

    @Test
    fun normalAndQuickDivergentAutoSilenceDurations() {
        val normalAlarm = Alarm(id = 101L, kind = "NORMAL")
        val quickAlarm = Alarm(id = 202L, kind = "QUICK", quickTriggerEpochMs = 1000L)
        val settings = AppSettings(autoSilenceMinutes = 10)

        val normalSnapshot = AlarmConfigurationSnapshot.from(
            alarm = normalAlarm,
            settings = settings,
            effectiveNormalAutoSilenceMinutes = 25
        )
        val quickSnapshot = AlarmConfigurationSnapshot.from(
            alarm = quickAlarm,
            settings = settings
        )

        assertEquals("NORMAL", normalSnapshot.kind)
        assertEquals(25, normalSnapshot.autoSilenceMinutes)
        assertEquals(0, normalSnapshot.quickAutoSilenceMinutes)

        assertEquals("QUICK", quickSnapshot.kind)
        assertEquals(0, quickSnapshot.autoSilenceMinutes)
        assertEquals(10, quickSnapshot.quickAutoSilenceMinutes)
    }

    @Test
    fun factoryRejectsMissingNormalAutoSilenceDuration() {
        val normalAlarm = Alarm(id = 101L, kind = "NORMAL")
        val settings = AppSettings(autoSilenceMinutes = 10)

        try {
            AlarmConfigurationSnapshot.from(
                alarm = normalAlarm,
                settings = settings,
                effectiveNormalAutoSilenceMinutes = null // Missing value must be rejected
            )
            fail("Expected IllegalArgumentException when Normal effective auto-silence duration is missing")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message?.contains("Normal Alarm requires an explicit valid effective auto-silence duration") == true)
        }
    }

    @Test
    fun factoryRejectsZeroNormalAutoSilenceDuration() {
        val normalAlarm = Alarm(id = 101L, kind = "NORMAL")
        val settings = AppSettings(autoSilenceMinutes = 10)

        try {
            AlarmConfigurationSnapshot.from(
                alarm = normalAlarm,
                settings = settings,
                effectiveNormalAutoSilenceMinutes = 0 // Zero value must be rejected
            )
            fail("Expected IllegalArgumentException when Normal effective auto-silence duration is 0")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message?.contains("Normal Alarm requires an explicit valid effective auto-silence duration") == true)
        }
    }

    @Test
    fun factoryRejectsNegativeNormalAutoSilenceDuration() {
        val normalAlarm = Alarm(id = 101L, kind = "NORMAL")
        val settings = AppSettings(autoSilenceMinutes = 10)

        try {
            AlarmConfigurationSnapshot.from(
                alarm = normalAlarm,
                settings = settings,
                effectiveNormalAutoSilenceMinutes = -5 // Negative value must be rejected
            )
            fail("Expected IllegalArgumentException when Normal effective auto-silence duration is negative")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message?.contains("Normal Alarm requires an explicit valid effective auto-silence duration") == true)
        }
    }

    @Test
    fun factoryEnforcesQuickAlarmDisabledSnoozeAndWakeCheckSemantics() {
        val quickAlarm = Alarm(
            id = 202L,
            kind = "QUICK",
            quickTriggerEpochMs = 1793872200000L,
            snoozeDurationMinutes = 10,
            maxSnoozeCount = 3,
            wakeConfirmEnabled = true,
            wakeConfirmDelayMinutes = 10,
            challengeType = "MATH_EASY"
        )
        val settings = AppSettings(autoSilenceMinutes = 15)

        val snapshot = AlarmConfigurationSnapshot.from(quickAlarm, settings)
        assertEquals(202L, snapshot.alarmId)
        assertEquals("QUICK", snapshot.kind)
        assertEquals(1793872200000L, snapshot.quickTriggerEpochMs)

        // Quick alarms MUST have snooze, wake check, and challenges disabled
        assertFalse(snapshot.snoozeEnabled)
        assertEquals(0, snapshot.snoozeDurationMinutes)
        assertEquals(-1, snapshot.maxSnoozeCount)
        assertFalse(snapshot.progressiveSnoozeEnabled)
        assertFalse(snapshot.wakeConfirmEnabled)
        assertEquals(0, snapshot.wakeConfirmDelayMinutes)
        assertEquals("NONE", snapshot.challengeType)
        assertEquals(0, snapshot.autoSilenceMinutes)
        assertEquals(15, snapshot.quickAutoSilenceMinutes)
    }

    @Test
    fun snapshotFreezesSettingsAtAdmissionTime() {
        val alarm = Alarm(id = 303L, snoozeDurationMinutes = 10, maxSnoozeCount = 3)
        var settings = AppSettings(autoSilenceMinutes = 10, defaultSnoozeDuration = 10)

        // Construct snapshot at admission time
        val snapshotAtAdmission = AlarmConfigurationSnapshot.from(
            alarm = alarm,
            settings = settings,
            effectiveNormalAutoSilenceMinutes = 10
        )
        assertEquals(10, snapshotAtAdmission.autoSilenceMinutes)
        assertEquals(10, snapshotAtAdmission.snoozeDurationMinutes)

        // Mutate settings post-admission
        settings = settings.copy(autoSilenceMinutes = 30, defaultSnoozeDuration = 20)

        // Captured snapshot remains immutable and unchanged
        assertEquals(10, snapshotAtAdmission.autoSilenceMinutes)
        assertEquals(10, snapshotAtAdmission.snoozeDurationMinutes)
    }

    @Test
    fun moshiIgnoresUnknownFieldsForForwardCompatibility() {
        val jsonWithUnknownFields = """
            {
                "snapshotVersion": 1,
                "alarmId": 200,
                "label": "Future Alarm",
                "futureNewField": "some_future_value",
                "futureNumericField": 999
            }
        """.trimIndent()

        val snapshot = adapter.fromJson(jsonWithUnknownFields)
        assertNotNull(snapshot)
        assertEquals(200L, snapshot?.alarmId)
        assertEquals("Future Alarm", snapshot?.label)
        assertEquals(1, snapshot?.snapshotVersion)
    }
}
