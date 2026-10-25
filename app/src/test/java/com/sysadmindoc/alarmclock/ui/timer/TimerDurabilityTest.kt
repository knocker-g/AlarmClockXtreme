package com.sysadmindoc.alarmclock.ui.timer

import android.content.Context
import android.os.SystemClock
import androidx.test.core.app.ApplicationProvider
import com.sysadmindoc.alarmclock.platform.AlarmClockCommand
import com.sysadmindoc.alarmclock.platform.AlarmClockIntentHandler
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class TimerDurabilityTest {

    private lateinit var context: Context
    private lateinit var store: TimerStore

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        org.robolectric.shadows.ShadowAlarmManager.setCanScheduleExactAlarms(true)
        store = TimerStore(context)
        // Clear stored timers
        store.replace(emptyList())
    }

    @Test
    fun `TimerStore upsert synchronously commits record to disk`() {
        val nowElapsed = SystemClock.elapsedRealtime()
        val record = PersistedTimerRecord(
            id = 1,
            label = "Eggs",
            totalSeconds = 300,
            remainingMillis = 300_000L,
            state = TimerState.RUNNING,
            endElapsedRealtime = nowElapsed + 300_000L
        )

        val committed = store.upsert(record)
        assertTrue("TimerStore upsert MUST commit synchronously and return true", committed)

        val loaded = store.loadRecords(nowElapsed)
        assertEquals(1, loaded.size)
        assertEquals("Eggs", loaded[0].label)
        assertEquals(TimerState.RUNNING, loaded[0].state)
    }

    @Test
    fun `TimerStore startOrReuse deduplicates duplicate SET_TIMER intents within window`() {
        val nowElapsed = SystemClock.elapsedRealtime()
        val firstResult = store.startOrReuse(300, "Cook", nowElapsed)
        assertTrue("First startOrReuse MUST create a new timer", firstResult.created)

        val secondResult = store.startOrReuse(300, "Cook", nowElapsed + 1000L)
        assertFalse("Second startOrReuse within duplicate window MUST reuse existing timer", secondResult.created)
        assertEquals(firstResult.record.id, secondResult.record.id)
    }

    @Test
    fun `TimerAlarmScheduler returns ArmedExact when exact alarms allowed`() {
        val nowElapsed = SystemClock.elapsedRealtime()
        val endElapsed = nowElapsed + 60_000L

        val outcome = TimerAlarmScheduler.schedule(context, 1, endElapsed)
        assertTrue("Timer schedule MUST return ArmedExact when exact alarms are allowed", outcome is TimerScheduleOutcome.ArmedExact)
        val armed = outcome as TimerScheduleOutcome.ArmedExact
        assertEquals(1, armed.timerId)
        assertEquals(endElapsed, armed.endElapsedRealtime)
    }

    @Test
    fun `TimerAlarmScheduler returns ArmedInexact or PermissionBlocked when exact alarms revoked`() {
        org.robolectric.shadows.ShadowAlarmManager.setCanScheduleExactAlarms(false)

        val nowElapsed = SystemClock.elapsedRealtime()
        val endElapsed = nowElapsed + 60_000L

        val outcome = TimerAlarmScheduler.schedule(context, 2, endElapsed)
        assertTrue(
            "Timer schedule MUST return ArmedInexact or PermissionBlocked when exact alarms are revoked",
            outcome is TimerScheduleOutcome.ArmedInexact || outcome is TimerScheduleOutcome.PermissionBlocked
        )
    }

    @Test
    fun `TimerAlarmScheduler returns RegistrationFailed on invalid timer ID`() {
        val outcome = TimerAlarmScheduler.schedule(context, -1, SystemClock.elapsedRealtime() + 60_000L)
        assertTrue("Timer schedule MUST return NoArm on invalid timer ID", outcome is TimerScheduleOutcome.NoArm)
    }
}
