package com.sysadmindoc.alarmclock.ui.alarmlist

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.sysadmindoc.alarmclock.data.local.AlarmDao
import com.sysadmindoc.alarmclock.data.local.AlarmDatabase
import com.sysadmindoc.alarmclock.data.model.Alarm
import com.sysadmindoc.alarmclock.data.preferences.AppSettings
import com.sysadmindoc.alarmclock.data.preferences.PreferencesManager
import com.sysadmindoc.alarmclock.data.repository.AlarmEventRepository
import com.sysadmindoc.alarmclock.data.repository.AlarmRepository
import com.sysadmindoc.alarmclock.domain.AlarmScheduler
import com.sysadmindoc.alarmclock.domain.NextAlarmCalculator
import com.sysadmindoc.alarmclock.domain.QuickAlarmScheduler
import com.sysadmindoc.alarmclock.domain.ScheduleOutcome
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
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

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class QuickListProjectionTest {

    private lateinit var context: Context
    private lateinit var db: AlarmDatabase
    private lateinit var alarmDao: AlarmDao
    private lateinit var alarmRepo: AlarmRepository
    private lateinit var eventRepo: AlarmEventRepository
    private lateinit var preferencesManager: PreferencesManager
    private lateinit var quickScheduler: QuickAlarmScheduler
    private lateinit var mockNormalScheduler: AlarmScheduler
    private val calculator = NextAlarmCalculator()

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
        eventRepo = AlarmEventRepository(db.alarmEventDao())

        preferencesManager = mockk(relaxed = true)
        coEvery { preferencesManager.getCurrentSettings() } returns AppSettings()
        coEvery { preferencesManager.settings } returns flowOf(AppSettings())

        mockNormalScheduler = mockk(relaxed = true)
        coEvery { mockNormalScheduler.schedule(any()) } returns ScheduleOutcome.Armed(1L, System.currentTimeMillis() + 600_000L)

        quickScheduler = QuickAlarmScheduler(context, alarmRepo)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        db.close()
    }

    @Test
    fun `Quick and Normal alarms have distinct isQuickAlarm identity`() {
        val quick = Alarm(
            hour = 10,
            minute = 0,
            label = "Quick 10m",
            isEnabled = true,
            kind = Alarm.KIND_QUICK,
            quickTriggerEpochMs = System.currentTimeMillis() + 600_000L
        )
        val normal = Alarm(
            hour = 8,
            minute = 30,
            label = "Morning",
            isEnabled = true,
            kind = Alarm.KIND_NORMAL
        )

        assertTrue("Quick Alarm MUST return isQuickAlarm == true", quick.isQuickAlarm)
        assertFalse("Normal Alarm MUST return isQuickAlarm == false", normal.isQuickAlarm)
    }

    @Test
    fun `Disabled completed Quick Alarms are excluded from list projection`() = runBlocking {
        val activeQuick = Alarm(
            hour = 10,
            minute = 0,
            label = "Active Quick",
            isEnabled = true,
            kind = Alarm.KIND_QUICK,
            quickTriggerEpochMs = System.currentTimeMillis() + 600_000L
        )
        val disabledQuick = Alarm(
            hour = 11,
            minute = 0,
            label = "Completed Quick",
            isEnabled = false,
            kind = Alarm.KIND_QUICK,
            quickTriggerEpochMs = System.currentTimeMillis() - 600_000L
        )
        val normalAlarm = Alarm(
            hour = 8,
            minute = 30,
            label = "Normal OFF",
            isEnabled = false,
            kind = Alarm.KIND_NORMAL
        )

        alarmRepo.save(activeQuick)
        alarmRepo.save(disabledQuick)
        alarmRepo.save(normalAlarm)

        val viewModel = AlarmListViewModel(
            context = context,
            repository = alarmRepo,
            scheduler = mockNormalScheduler,
            calculator = calculator,
            preferencesManager = preferencesManager,
            eventRepository = eventRepo,
            quickAlarmScheduler = quickScheduler
        )

        var state: AlarmListUiState? = null
        val job = launch {
            state = viewModel.uiState.first { (it as AlarmListUiState).alarms.isNotEmpty() }
        }
        job.join()

        assertNotNull("UiState MUST be non-null", state)
        val projected = state!!.alarms

        // Normal OFF alarm is projected; disabled Quick alarm is NOT projected
        assertEquals("List projection MUST include active Quick and normal OFF alarms, excluding disabled Quick", 2, projected.size)
        assertTrue("Projected list MUST contain active Quick", projected.any { it.label == "Active Quick" })
        assertTrue("Projected list MUST contain normal OFF alarm", projected.any { it.label == "Normal OFF" })
        assertFalse("Projected list MUST NOT expose disabled OFF Quick alarm", projected.any { it.label == "Completed Quick" })
    }

    @Test
    fun `Quick Alarms are excluded from Group chip row calculations`() = runBlocking {
        val normalGroupAlarm = Alarm(
            hour = 8,
            minute = 0,
            label = "Group Alarm",
            group = "Work",
            isEnabled = true,
            kind = Alarm.KIND_NORMAL
        )
        val quickWithGroup = Alarm(
            hour = 10,
            minute = 0,
            label = "Quick Alarm",
            group = "Home",
            isEnabled = true,
            kind = Alarm.KIND_QUICK,
            quickTriggerEpochMs = System.currentTimeMillis() + 600_000L
        )

        alarmRepo.save(normalGroupAlarm)
        alarmRepo.save(quickWithGroup)

        val viewModel = AlarmListViewModel(
            context = context,
            repository = alarmRepo,
            scheduler = mockNormalScheduler,
            calculator = calculator,
            preferencesManager = preferencesManager,
            eventRepository = eventRepo,
            quickAlarmScheduler = quickScheduler
        )

        var state: AlarmListUiState? = null
        val job = launch {
            state = viewModel.uiState.first { (it as AlarmListUiState).groups.isNotEmpty() }
        }
        job.join()

        assertNotNull("UiState MUST be non-null", state)
        val groups = state!!.groups

        assertEquals("Groups list MUST include Normal group 'Work' only", listOf("Work"), groups)
        assertFalse("Groups list MUST NOT include Quick alarm group 'Home'", groups.contains("Home"))
    }

    @Test
    fun `getNextAlarm compares valid Normal and Quick triggers and returns earliest`() = runBlocking {
        val now = System.currentTimeMillis()
        val quickTrigger = now + 300_000L // 5 min in future
        val normalTrigger = now + 3600_000L // 60 min in future

        val normal = Alarm(
            id = 1L,
            hour = 11,
            minute = 0,
            label = "Normal 1h",
            isEnabled = true,
            kind = Alarm.KIND_NORMAL,
            nextTriggerTime = normalTrigger
        )
        val quick = Alarm(
            id = 2L,
            hour = 10,
            minute = 5,
            label = "Quick 5m",
            isEnabled = true,
            kind = Alarm.KIND_QUICK,
            quickTriggerEpochMs = quickTrigger,
            nextTriggerTime = quickTrigger
        )

        alarmRepo.save(normal)
        alarmRepo.updateNextTrigger(1L, normalTrigger)

        alarmRepo.save(quick)
        alarmRepo.updateNextTrigger(2L, quickTrigger)

        val enabled = alarmRepo.getEnabled()
        val earliest = enabled.filter { it.nextTriggerTime > 0 }.minByOrNull { it.nextTriggerTime }

        assertNotNull("Earliest next alarm MUST be non-null", earliest)
        assertTrue("Earliest next trigger MUST be Quick Alarm", earliest!!.isQuickAlarm)
        assertEquals("Quick 5m", earliest.label)
    }

    @Test
    fun `Stale enabled alarm with past trigger is ignored and valid future alarm wins`() = runBlocking {
        val now = System.currentTimeMillis()
        val staleTrigger = now - 600_000L // 10 min in PAST
        val validFutureTrigger = now + 600_000L // 10 min in FUTURE

        val staleAlarm = Alarm(
            id = 1L,
            hour = 7,
            minute = 0,
            label = "Stale Past Alarm",
            isEnabled = true,
            kind = Alarm.KIND_NORMAL,
            nextTriggerTime = staleTrigger
        )
        val validFutureAlarm = Alarm(
            id = 2L,
            hour = 10,
            minute = 0,
            label = "Valid Future Alarm",
            isEnabled = true,
            kind = Alarm.KIND_QUICK,
            quickTriggerEpochMs = validFutureTrigger,
            nextTriggerTime = validFutureTrigger
        )

        alarmRepo.save(staleAlarm)
        alarmRepo.save(validFutureAlarm)

        // 1. Repository level getNextAlarm
        val repoNext = alarmRepo.getNextAlarm(now)
        assertNotNull("Next alarm MUST NOT be null when a valid future alarm exists", repoNext)
        assertEquals("Valid future alarm MUST win over stale past alarm", "Valid Future Alarm", repoNext!!.label)

        // 2. ViewModel uiState level nextAlarm projection
        val viewModel = AlarmListViewModel(
            context = context,
            repository = alarmRepo,
            scheduler = mockNormalScheduler,
            calculator = calculator,
            preferencesManager = preferencesManager,
            eventRepository = eventRepo,
            quickAlarmScheduler = quickScheduler
        )

        var state: AlarmListUiState? = null
        val job = launch {
            state = viewModel.uiState.first { (it as AlarmListUiState).nextAlarm != null }
        }
        job.join()

        assertNotNull("UiState nextAlarm MUST NOT be null when valid future alarm exists", state?.nextAlarm)
        assertEquals("ViewModel nextAlarm MUST select valid future alarm over stale past alarm", "Valid Future Alarm", state!!.nextAlarm!!.label)
    }

    @Test
    fun `All candidates stale returns null next alarm`() = runBlocking {
        val now = System.currentTimeMillis()
        val staleTrigger = now - 600_000L // 10 min in PAST

        val staleAlarm = Alarm(
            id = 1L,
            hour = 7,
            minute = 0,
            label = "Stale Past Alarm",
            isEnabled = true,
            kind = Alarm.KIND_NORMAL,
            nextTriggerTime = staleTrigger
        )

        alarmRepo.save(staleAlarm)

        val repoNext = alarmRepo.getNextAlarm(now)
        assertNull("getNextAlarm MUST return null when all enabled candidates are in the past", repoNext)
    }

    @Test
    fun `observeNextAlarm advances clock threshold as time passes without DB write`() = runBlocking {
        var simulatedClockTime = 1_000_000L

        val alarmA = Alarm(
            id = 1L,
            hour = 10,
            minute = 1,
            label = "Alarm A",
            isEnabled = true,
            kind = Alarm.KIND_NORMAL,
            nextTriggerTime = 1_060_000L // 10:01
        )
        val alarmB = Alarm(
            id = 2L,
            hour = 10,
            minute = 30,
            label = "Alarm B",
            isEnabled = true,
            kind = Alarm.KIND_QUICK,
            quickTriggerEpochMs = 1_800_000L,
            nextTriggerTime = 1_800_000L // 10:30
        )

        alarmRepo.save(alarmA)
        alarmRepo.save(alarmB)

        // 1. At 10:00 (1_000_000L), observeNextAlarm selects Alarm A
        val initialNext = alarmRepo.observeNextAlarm(
            clock = { simulatedClockTime },
            refreshIntervalMs = 100L
        ).first()

        assertNotNull("Initial next alarm at 10:00 MUST be non-null", initialNext)
        assertEquals("Initial next alarm at 10:00 MUST be Alarm A", "Alarm A", initialNext!!.label)

        // 2. Advance simulated clock to 10:02 (1_120_000L) without DB write
        simulatedClockTime = 1_120_000L

        // Await next emission from flow without DB write
        val updatedNext = alarmRepo.observeNextAlarm(
            clock = { simulatedClockTime },
            refreshIntervalMs = 100L
        ).first()

        assertNotNull("Updated next alarm at 10:02 MUST be non-null", updatedNext)
        assertEquals("After clock advances to 10:02 without DB write, next alarm MUST become Alarm B", "Alarm B", updatedNext!!.label)
    }

    @Test
    fun `observeNextAlarm flow cancellation stops refresh work cleanly`() = runBlocking {
        var clockCallCount = 0
        val clockSupplier = {
            clockCallCount++
            System.currentTimeMillis()
        }

        val flow = alarmRepo.observeNextAlarm(
            clock = clockSupplier,
            refreshIntervalMs = 50L
        )

        val job = launch {
            flow.collect { }
        }

        kotlinx.coroutines.delay(120L)
        job.cancel()

        val countAtCancel = clockCallCount
        kotlinx.coroutines.delay(150L)

        assertEquals("Clock calls MUST NOT continue after flow job cancellation", countAtCancel, clockCallCount)
    }

    @Test
    fun `Inserting an earlier valid future alarm triggers immediate DB emission`() = runBlocking {
        val now = 1_000_000L

        val alarmB = Alarm(
            id = 2L,
            hour = 10,
            minute = 30,
            label = "Alarm B",
            isEnabled = true,
            kind = Alarm.KIND_NORMAL,
            nextTriggerTime = 1_800_000L // 10:30
        )
        alarmRepo.save(alarmB)

        // Initial next alarm is B (10:30)
        val initialNext = alarmRepo.observeNextAlarm(clock = { now }, refreshIntervalMs = 60_000L).first()
        assertEquals("Alarm B", initialNext?.label)

        // Insert Alarm C (10:05 = 1_300_000L), which is earlier than B (10:30)
        val alarmC = Alarm(
            id = 3L,
            hour = 10,
            minute = 5,
            label = "Alarm C",
            isEnabled = true,
            kind = Alarm.KIND_QUICK,
            quickTriggerEpochMs = 1_300_000L,
            nextTriggerTime = 1_300_000L
        )
        alarmRepo.save(alarmC)

        // Database insert triggers immediate emission of earlier Alarm C
        val updatedNext = alarmRepo.observeNextAlarm(clock = { now }, refreshIntervalMs = 60_000L).first()
        assertEquals("Newly inserted earlier Alarm C MUST immediately become next alarm", "Alarm C", updatedNext?.label)
    }

    @Test
    fun `Disabled alarms cannot become selected next alarm`() = runBlocking {
        val now = 1_000_000L

        val disabledAlarm = Alarm(
            id = 1L,
            hour = 10,
            minute = 5,
            label = "Disabled Alarm",
            isEnabled = false,
            kind = Alarm.KIND_NORMAL,
            nextTriggerTime = 1_300_000L // earlier future trigger
        )
        val enabledAlarm = Alarm(
            id = 2L,
            hour = 10,
            minute = 30,
            label = "Enabled Alarm",
            isEnabled = true,
            kind = Alarm.KIND_NORMAL,
            nextTriggerTime = 1_800_000L // later future trigger
        )

        alarmRepo.save(disabledAlarm)
        alarmRepo.save(enabledAlarm)

        val nextAlarm = alarmRepo.observeNextAlarm(clock = { now }).first()
        assertNotNull("Next alarm MUST be non-null", nextAlarm)
        assertEquals("Disabled alarm MUST be excluded; enabled alarm MUST win", "Enabled Alarm", nextAlarm?.label)
    }
}
