package com.sysadmindoc.alarmclock.service

import android.content.Intent
import com.sysadmindoc.alarmclock.data.local.entity.AlarmIncidentEvent
import com.sysadmindoc.alarmclock.domain.AlarmScheduler
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class AlarmGenerationTest {

    @Test
    fun fromIntentExtractsFullGeneration() {
        val intent = Intent().apply {
            putExtra(AlarmScheduler.EXTRA_ALARM_ID, 42L)
            putExtra(AlarmScheduler.EXTRA_SCHEDULED_AT, 1000L)
            putExtra(AlarmScheduler.EXTRA_ALARM_FIRE_ID, "fire-42")
        }

        val gen = AlarmGeneration.fromIntent(intent)
        assertNotNull(gen)
        assertEquals(42L, gen?.alarmId)
        assertEquals(1000L, gen?.scheduledAt)
        assertEquals("fire-42", gen?.fireId)
        assertTrue(gen?.isValid == true)
    }

    @Test
    fun fromIntentComputesDefaultFireIdWhenMissing() {
        val intent = Intent().apply {
            putExtra(AlarmScheduler.EXTRA_ALARM_ID, 42L)
            putExtra(AlarmScheduler.EXTRA_SCHEDULED_AT, 1000L)
        }

        val gen = AlarmGeneration.fromIntent(intent)
        assertNotNull(gen)
        assertEquals(42L, gen?.alarmId)
        assertEquals(1000L, gen?.scheduledAt)
        assertEquals(AlarmIncidentEvent.fireIdFor(42L, 1000L), gen?.fireId)
    }

    @Test
    fun fromIntentReturnsNullWhenIdOrScheduledAtMissing() {
        val invalidIdIntent = Intent().apply {
            putExtra(AlarmScheduler.EXTRA_SCHEDULED_AT, 1000L)
        }
        assertNull(AlarmGeneration.fromIntent(invalidIdIntent))

        val invalidScheduledAtIntent = Intent().apply {
            putExtra(AlarmScheduler.EXTRA_ALARM_ID, 42L)
        }
        assertNull(AlarmGeneration.fromIntent(invalidScheduledAtIntent))
    }

    @Test
    fun matchesIdentifiesExactSameGeneration() {
        val gen1 = AlarmGeneration(42L, 1000L, "fire-42")
        val gen2 = AlarmGeneration(42L, 1000L, "fire-42")
        val genDifferentScheduledAt = AlarmGeneration(42L, 2000L, "fire-42")
        val genDifferentFireId = AlarmGeneration(42L, 1000L, "fire-42-b")
        val genDifferentAlarmId = AlarmGeneration(99L, 1000L, "fire-42")

        assertTrue(gen1.matches(gen2))
        assertFalse(gen1.matches(genDifferentScheduledAt))
        assertFalse(gen1.matches(genDifferentFireId))
        assertFalse(gen1.matches(genDifferentAlarmId))
        assertFalse(gen1.matches(null))
    }

    @Test
    fun staleStartLogicRejectsOlderScheduledAtForSameAlarm() {
        val activeGen = AlarmGeneration(42L, 2000L, "fire-42-new")
        val incomingStaleGen = AlarmGeneration(42L, 1000L, "fire-42-old")

        assertEquals(activeGen.alarmId, incomingStaleGen.alarmId)
        assertTrue(incomingStaleGen.scheduledAt < activeGen.scheduledAt)
        assertFalse(incomingStaleGen.matches(activeGen))
    }

    @Test
    fun serviceRestartActionAdoptionRequiresActiveEvidence() {
        val activeSnapshot = AlarmService.Companion.ActiveAlarmSnapshot(
            alarmId = 42L,
            scheduledAt = 1000L,
            fireId = "fire-42",
            firedAt = 500L
        )

        // Matching restart action -> accepted
        val matchingGen = AlarmGeneration(42L, 1000L, "fire-42")
        val matchesActive = activeSnapshot.alarmId == matchingGen.alarmId &&
            activeSnapshot.scheduledAt == matchingGen.scheduledAt &&
            activeSnapshot.fireId == matchingGen.fireId
        assertTrue(matchesActive)

        // Mismatched restart action -> rejected
        val mismatchedGen = AlarmGeneration(42L, 9999L, "fire-42-stale")
        val matchesMismatched = activeSnapshot.alarmId == mismatchedGen.alarmId &&
            activeSnapshot.scheduledAt == mismatchedGen.scheduledAt &&
            activeSnapshot.fireId == mismatchedGen.fireId
        assertFalse(matchesMismatched)
    }
}
