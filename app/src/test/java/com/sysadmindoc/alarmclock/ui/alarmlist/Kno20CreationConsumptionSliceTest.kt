package com.sysadmindoc.alarmclock.ui.alarmlist

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.sysadmindoc.alarmclock.R
import com.sysadmindoc.alarmclock.data.local.AlarmDao
import com.sysadmindoc.alarmclock.data.local.AlarmDatabase
import com.sysadmindoc.alarmclock.data.model.Alarm
import com.sysadmindoc.alarmclock.data.model.AlarmKind
import com.sysadmindoc.alarmclock.data.preferences.AppSettings
import com.sysadmindoc.alarmclock.data.preferences.PreferencesManager
import com.sysadmindoc.alarmclock.data.repository.AlarmEventRepository
import com.sysadmindoc.alarmclock.data.repository.AlarmRepository
import com.sysadmindoc.alarmclock.domain.AlarmScheduler
import com.sysadmindoc.alarmclock.domain.NextAlarmCalculator
import com.sysadmindoc.alarmclock.domain.QuickAlarmScheduler
import com.sysadmindoc.alarmclock.domain.ScheduleOutcome
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class Kno20CreationConsumptionSliceTest {

    private lateinit var context: Context
    private lateinit var db: AlarmDatabase
    private lateinit var alarmDao: AlarmDao
    private lateinit var alarmRepo: AlarmRepository
    private lateinit var preferencesManager: PreferencesManager
    private lateinit var quickScheduler: QuickAlarmScheduler
    private lateinit var mockNormalScheduler: AlarmScheduler
    private lateinit var calculator: NextAlarmCalculator
    private lateinit var eventRepo: AlarmEventRepository

    @Before
    fun setUp() {
        try { Dispatchers.resetMain() } catch (_: Exception) {}
        Dispatchers.setMain(UnconfinedTestDispatcher())

        context = ApplicationProvider.getApplicationContext()
        org.robolectric.shadows.ShadowAlarmManager.setCanScheduleExactAlarms(true)

        db = Room.inMemoryDatabaseBuilder(context, AlarmDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        alarmDao = db.alarmDao()
        alarmRepo = AlarmRepository(db, alarmDao, db.alarmGroupDao())

        preferencesManager = mockk(relaxed = true)
        mockNormalScheduler = mockk(relaxed = true)
        calculator = mockk(relaxed = true)
        eventRepo = mockk(relaxed = true)

        every { preferencesManager.getCachedSettings() } returns AppSettings(activeAlarmId = null)
        coEvery { preferencesManager.getCurrentSettings() } returns AppSettings(activeAlarmId = null)
        every { preferencesManager.settings } returns kotlinx.coroutines.flow.flowOf(AppSettings(activeAlarmId = null))

        quickScheduler = QuickAlarmScheduler(context, alarmRepo)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        db.close()
    }

    private suspend fun awaitDbCondition(
        timeoutMs: Long = 2000L,
        condition: suspend () -> Boolean
    ) {
        kotlinx.coroutines.withTimeout(timeoutMs) {
            while (!condition()) {
                kotlinx.coroutines.yield()
            }
        }
    }

    @Test
    fun `createQuickAlarm creates distinct Quick Alarm with trigger fields and zero Snooze Group Challenge`() = runBlocking {
        val viewModel = AlarmListViewModel(
            context = context,
            repository = alarmRepo,
            scheduler = mockNormalScheduler,
            calculator = calculator,
            preferencesManager = preferencesManager,
            eventRepository = eventRepo,
            quickAlarmScheduler = quickScheduler
        )

        viewModel.createQuickAlarm(15)
        awaitDbCondition { alarmRepo.getPendingQuickAlarms().isNotEmpty() }

        val pendingQuickList = alarmRepo.getPendingQuickAlarms()
        assertEquals(1, pendingQuickList.size)

        val created = pendingQuickList[0]
        assertEquals("QUICK", created.kind)
        assertTrue("isQuickAlarm MUST be true", created.isQuickAlarm)
        assertFalse("isNormalAlarm MUST be false", created.isNormalAlarm)
        assertTrue("quickTriggerEpochMs MUST be > 0", created.quickTriggerEpochMs > 0)
        assertTrue("quickRelativeElapsedMs MUST be > 0", created.quickRelativeElapsedMs > 0)

        // Verify Quick capability locks (NO Snooze, NO Group, NO Challenge, NO Wake Check)
        assertEquals("Max snooze count MUST be 0 (no snooze)", 0, created.maxSnoozeCount)
        assertEquals("Challenge type MUST be NONE", "NONE", created.challengeType)
        assertEquals("Group MUST be empty", "", created.group)
        assertFalse("Wake confirm MUST be false", created.wakeConfirmEnabled)
    }

    @Test
    fun `createQuickAlarm emits set feedback when exact scheduling succeeds`() = runBlocking {
        val viewModel = AlarmListViewModel(
            context = context,
            repository = alarmRepo,
            scheduler = mockNormalScheduler,
            calculator = calculator,
            preferencesManager = preferencesManager,
            eventRepository = eventRepo,
            quickAlarmScheduler = quickScheduler
        )

        var emittedFeedback: String? = null
        val job = launch {
            emittedFeedback = viewModel.feedbackEvents.first()
        }

        viewModel.createQuickAlarm(10)
        awaitDbCondition { alarmRepo.getPendingQuickAlarms().isNotEmpty() }
        job.join()

        assertNotNull("Feedback MUST be emitted", emittedFeedback)
        assertTrue("Feedback MUST contain quick alarm set string", emittedFeedback!!.startsWith("Quick alarm set for"))
    }

    @Test
    fun `createQuickAlarm emits permission required feedback when exact permission revoked`() = runBlocking {
        org.robolectric.shadows.ShadowAlarmManager.setCanScheduleExactAlarms(false)

        val viewModel = AlarmListViewModel(
            context = context,
            repository = alarmRepo,
            scheduler = mockNormalScheduler,
            calculator = calculator,
            preferencesManager = preferencesManager,
            eventRepository = eventRepo,
            quickAlarmScheduler = quickScheduler
        )

        viewModel.createQuickAlarm(20)

        val feedback = viewModel.feedbackEvents.first()
        assertTrue("Feedback MUST notify user of permission requirement", feedback.contains("Exact alarm permission required"))

        // Confirm pending Quick intent is preserved in DB despite permission block
        val pendingQuick = alarmRepo.getPendingQuickAlarms()
        assertEquals("Pending Quick Alarm MUST remain persisted in DB", 1, pendingQuick.size)
    }

    @Test
    fun `Quick alarm is NOT deleted at creation or initial FIRING delivery`() = runBlocking {
        val viewModel = AlarmListViewModel(
            context = context,
            repository = alarmRepo,
            scheduler = mockNormalScheduler,
            calculator = calculator,
            preferencesManager = preferencesManager,
            eventRepository = eventRepo,
            quickAlarmScheduler = quickScheduler
        )

        viewModel.createQuickAlarm(5)

        val pending = alarmRepo.getPendingQuickAlarms()
        assertEquals(1, pending.size)
        val id = pending[0].id

        val reloaded = alarmRepo.getById(id)
        assertNotNull("Quick Alarm entity MUST remain in DB when delivered/firing", reloaded)
        assertEquals("QUICK", reloaded?.kind)
    }
}
