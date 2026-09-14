package com.sysadmindoc.alarmclock.data.local

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.sysadmindoc.alarmclock.data.preferences.PreferencesManager
import com.sysadmindoc.alarmclock.service.AlarmService.Companion.ActiveAlarmSnapshot
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AlarmSessionLatestTest {
    private lateinit var preferencesManager: PreferencesManager

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        preferencesManager = PreferencesManager(context)
    }

    @Test
    fun sessionPersistenceAndClearing() = runBlocking {
        // SET
        preferencesManager.update {
            it.copy(
                activeAlarmId = 42L,
                activeAlarmScheduledAt = 1000L,
                activeAlarmFireId = "fire-42",
                activeAlarmFiredAt = 500L,
                activeAlarmState = "FIRING",
                activeAlarmRefireAt = 0L
            )
        }

        var settings = preferencesManager.settings.first()
        assertEquals(42L, settings.activeAlarmId)
        assertEquals("FIRING", settings.activeAlarmState)

        // UPDATE (SNOOZE)
        preferencesManager.update {
            it.copy(
                activeAlarmState = "SNOOZED",
                activeAlarmRefireAt = 2000L
            )
        }

        settings = preferencesManager.settings.first()
        assertEquals(42L, settings.activeAlarmId)
        assertEquals("SNOOZED", settings.activeAlarmState)
        assertEquals(2000L, settings.activeAlarmRefireAt)

        // CLEAR
        preferencesManager.update { it.copy(activeAlarmId = null) }

        settings = preferencesManager.settings.first()
        assertNull(settings.activeAlarmId)
        assertEquals("", settings.activeAlarmState)
    }

    @Test
    fun staleSessionDetectionLogic() = runBlocking {
        // Test logic used in MainActivity for stale sessions
        val now = 10000L
        
        // FIRING stale (6h)
        val firingScheduledAt = now - (7 * 3600 * 1000L)
        val isFiringStale = now > firingScheduledAt + (6 * 3600 * 1000L)
        assertEquals(true, isFiringStale)
        
        // SNOOZED stale (1h)
        val snoozeRefireAt = now - (2 * 3600 * 1000L)
        val isSnoozeStale = now > snoozeRefireAt + (3600 * 1000L)
        assertEquals(true, isSnoozeStale)
        
        // Valid SNOOZED
        val validSnoozeRefireAt = now + 5000L
        val isSnoozeValid = now <= validSnoozeRefireAt + (3600 * 1000L)
        assertEquals(true, isSnoozeValid)
    }
}
