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

    @Test
    fun `TimerStore upsert returns false on commit failure`() {
        val mockContext = mockk<Context>(relaxed = true)
        val mockPrefs = mockk<android.content.SharedPreferences>(relaxed = true)
        val mockEditor = mockk<android.content.SharedPreferences.Editor>(relaxed = true)

        every { mockContext.getSharedPreferences(any(), any()) } returns mockPrefs
        every { mockPrefs.edit() } returns mockEditor
        every { mockEditor.putString(any(), any()) } returns mockEditor
        every { mockEditor.commit() } returns false

        val storeWithMock = TimerStore(mockContext)
        val record = PersistedTimerRecord(id = 1, label = "Test", totalSeconds = 60, remainingMillis = 60_000L, state = TimerState.RUNNING)

        val result = storeWithMock.upsert(record)
        assertFalse("upsert MUST return false when commit fails", result)
    }

    @Test
    fun `TimerStore markFinished returns null on commit failure`() {
        val mockContext = mockk<Context>(relaxed = true)
        val mockPrefs = mockk<android.content.SharedPreferences>(relaxed = true)
        val mockEditor = mockk<android.content.SharedPreferences.Editor>(relaxed = true)

        val json = "[{\"id\":1,\"label\":\"Test\",\"totalSeconds\":60,\"remainingMillis\":60000,\"state\":\"RUNNING\",\"endElapsedRealtime\":999999999}]"
        every { mockContext.getSharedPreferences(any(), any()) } returns mockPrefs
        every { mockPrefs.getString(any(), any()) } returns json
        every { mockPrefs.edit() } returns mockEditor
        every { mockEditor.putString(any(), any()) } returns mockEditor
        every { mockEditor.commit() } returns false

        val storeWithMock = TimerStore(mockContext)
        val result = storeWithMock.markFinished(1)
        assertEquals("markFinished MUST return null on commit failure", null, result)
    }

    @Test
    fun `TimerStore startOrReuse returns COMMIT_FAILED when disk commit fails`() {
        val mockContext = mockk<Context>(relaxed = true)
        val mockPrefs = mockk<android.content.SharedPreferences>(relaxed = true)
        val mockEditor = mockk<android.content.SharedPreferences.Editor>(relaxed = true)

        every { mockContext.getSharedPreferences(any(), any()) } returns mockPrefs
        every { mockPrefs.getString(any(), any()) } returns null
        every { mockPrefs.edit() } returns mockEditor
        every { mockEditor.putString(any(), any()) } returns mockEditor
        every { mockEditor.commit() } returns false

        val storeWithMock = TimerStore(mockContext)
        val result = storeWithMock.startOrReuse(60, "Tea")

        assertEquals("startOrReuse MUST return COMMIT_FAILED when commit fails", TimerStartStatus.COMMIT_FAILED, result.status)
        assertFalse("created MUST be false on COMMIT_FAILED", result.created)
    }

    @Test
    fun `TimerStore restartFinished returns null on commit failure`() {
        val mockContext = mockk<Context>(relaxed = true)
        val mockPrefs = mockk<android.content.SharedPreferences>(relaxed = true)
        val mockEditor = mockk<android.content.SharedPreferences.Editor>(relaxed = true)

        val json = "[{\"id\":1,\"label\":\"Test\",\"totalSeconds\":60,\"remainingMillis\":0,\"state\":\"FINISHED\",\"endElapsedRealtime\":0}]"
        every { mockContext.getSharedPreferences(any(), any()) } returns mockPrefs
        every { mockPrefs.getString(any(), any()) } returns json
        every { mockPrefs.edit() } returns mockEditor
        every { mockEditor.putString(any(), any()) } returns mockEditor
        every { mockEditor.commit() } returns false

        val storeWithMock = TimerStore(mockContext)
        val result = storeWithMock.restartFinished(1)
        assertEquals("restartFinished MUST return null on commit failure", null, result)
    }

    @Test
    fun `TimerStore restoreSnapshot newlyFinished is empty when disk commit fails`() {
        val mockContext = mockk<Context>(relaxed = true)
        val mockPrefs = mockk<android.content.SharedPreferences>(relaxed = true)
        val mockEditor = mockk<android.content.SharedPreferences.Editor>(relaxed = true)

        val json = "[{\"id\":1,\"label\":\"Overdue\",\"totalSeconds\":60,\"remainingMillis\":0,\"state\":\"RUNNING\",\"endElapsedRealtime\":1000}]"
        every { mockContext.getSharedPreferences(any(), any()) } returns mockPrefs
        every { mockPrefs.getString(any(), any()) } returns json
        every { mockPrefs.edit() } returns mockEditor
        every { mockEditor.putString(any(), any()) } returns mockEditor
        every { mockEditor.commit() } returns false

        val storeWithMock = TimerStore(mockContext)
        val snapshot = storeWithMock.restoreSnapshot(nowElapsed = 2000L)
        assertTrue("restoreSnapshot newlyFinished MUST be empty on commit failure", snapshot.newlyFinished.isEmpty())
    }

    @Test
    fun `TimerStore acknowledgeAlert clears alertPending flag on persisted record`() {
        val nowElapsed = SystemClock.elapsedRealtime()
        val overdue = PersistedTimerRecord(
            id = 5,
            label = "Pasta",
            totalSeconds = 180,
            remainingMillis = 0L,
            state = TimerState.RUNNING,
            endElapsedRealtime = nowElapsed - 1000L
        )
        store.upsert(overdue)

        val snapshot = store.restoreSnapshot(nowElapsed)
        assertEquals(1, snapshot.newlyFinished.size)
        assertTrue("Newly finished timer MUST have alertPending = true", snapshot.newlyFinished[0].alertPending)

        val acked = store.acknowledgeAlert(5)
        assertTrue("acknowledgeAlert MUST return true when alertPending was true", acked)

        val records = store.loadRecords(nowElapsed)
        assertFalse("alertPending MUST be false after acknowledgeAlert", records[0].alertPending)
    }
}
