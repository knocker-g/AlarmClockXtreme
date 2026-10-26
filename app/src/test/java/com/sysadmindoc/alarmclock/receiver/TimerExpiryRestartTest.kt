package com.sysadmindoc.alarmclock.receiver

import android.content.Context
import android.content.Intent
import android.os.SystemClock
import androidx.test.core.app.ApplicationProvider
import com.sysadmindoc.alarmclock.ui.timer.PersistedTimerRecord
import com.sysadmindoc.alarmclock.ui.timer.TimerAlarmScheduler
import com.sysadmindoc.alarmclock.ui.timer.TimerExpiryReceiver
import com.sysadmindoc.alarmclock.ui.timer.TimerScheduleOutcome
import com.sysadmindoc.alarmclock.ui.timer.TimerState
import com.sysadmindoc.alarmclock.ui.timer.TimerStore
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class TimerExpiryRestartTest {

    private lateinit var context: Context
    private lateinit var store: TimerStore

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        org.robolectric.shadows.ShadowAlarmManager.setCanScheduleExactAlarms(true)
        store = TimerStore(context)
        store.replace(emptyList())
    }

    @After
    fun tearDown() {
        store.replace(emptyList())
    }

    @Test
    fun `TimerExpiryReceiver claims finished transition and ignores duplicate expiry broadcast`() {
        val nowElapsed = SystemClock.elapsedRealtime()
        val record = PersistedTimerRecord(
            id = 1,
            label = "Tea",
            totalSeconds = 60,
            remainingMillis = 0L,
            state = TimerState.RUNNING,
            endElapsedRealtime = nowElapsed - 1000L
        )
        store.upsert(record)

        val intent = Intent(context, TimerExpiryReceiver::class.java).apply {
            action = TimerAlarmScheduler.ACTION_TIMER_EXPIRED
            putExtra(TimerAlarmScheduler.EXTRA_TIMER_ID, 1)
        }

        val receiver = TimerExpiryReceiver()
        receiver.onReceive(context, intent)

        val loadedFirst = store.loadRecords(nowElapsed)
        assertEquals(1, loadedFirst.size)
        assertEquals(TimerState.FINISHED, loadedFirst[0].state)

        // Duplicate broadcast for same timer ID
        receiver.onReceive(context, intent)

        val loadedSecond = store.loadRecords(nowElapsed)
        assertEquals(1, loadedSecond.size)
        assertEquals(TimerState.FINISHED, loadedSecond[0].state)
    }

    @Test
    fun `restoreSnapshot claims overdue running timers atomically`() {
        val nowElapsed = SystemClock.elapsedRealtime()
        val overdue = PersistedTimerRecord(
            id = 2,
            label = "Pizza",
            totalSeconds = 120,
            remainingMillis = 0L,
            state = TimerState.RUNNING,
            endElapsedRealtime = nowElapsed - 5000L
        )
        store.upsert(overdue)

        val snapshot = store.restoreSnapshot(nowElapsed)
        assertEquals("Overdue timer MUST be claimed as newly finished", 1, snapshot.newlyFinished.size)
        assertEquals(2, snapshot.newlyFinished[0].id)

        val records = store.loadRecords(nowElapsed)
        assertEquals(TimerState.FINISHED, records[0].state)
    }

    @Test
    fun `MY_PACKAGE_REPLACED retains running timers while BOOT_COMPLETED cleans up pre-reboot timers`() = runBlocking {
        val nowElapsed = SystemClock.elapsedRealtime()
        val running = PersistedTimerRecord(
            id = 3,
            label = "Focus",
            totalSeconds = 600,
            remainingMillis = 600_000L,
            state = TimerState.RUNNING,
            endElapsedRealtime = nowElapsed + 600_000L
        )
        store.upsert(running)

        // Simulate MY_PACKAGE_REPLACED broadcast
        val packageReplacedIntent = Intent(Intent.ACTION_MY_PACKAGE_REPLACED)
        val receiver1 = BootReceiver().apply { testScope = this@runBlocking }
        receiver1.onReceive(context, packageReplacedIntent)
        kotlinx.coroutines.yield()

        val recordsAfterPackageReplace = store.loadRecords(nowElapsed)
        assertEquals("Running timer MUST be retained on MY_PACKAGE_REPLACED", 1, recordsAfterPackageReplace.size)
        assertEquals(TimerState.RUNNING, recordsAfterPackageReplace[0].state)

        // Simulate BOOT_COMPLETED broadcast
        val bootCompletedIntent = Intent(Intent.ACTION_BOOT_COMPLETED)
        val receiver2 = BootReceiver().apply { testScope = this@runBlocking }
        receiver2.onReceive(context, bootCompletedIntent)
        kotlinx.coroutines.yield()

        val recordsAfterBoot = store.loadRecords(nowElapsed)
        assertTrue("Running timers MUST be cleared on BOOT_COMPLETED", recordsAfterBoot.isEmpty())
    }
}
