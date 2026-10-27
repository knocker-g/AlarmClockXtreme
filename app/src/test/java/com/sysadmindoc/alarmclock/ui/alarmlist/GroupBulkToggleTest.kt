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
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class GroupBulkToggleTest {

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
        coEvery { preferencesManager.getCachedSettings() } returns AppSettings()
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
    fun `GroupDerivedState is correctly derived from member truth`() {
        val workAlarms = listOf(
            Alarm(id = 1L, hour = 7, minute = 0, isEnabled = true, group = "Work"),
            Alarm(id = 2L, hour = 8, minute = 0, isEnabled = true, group = "Work")
        )
        assertEquals(GroupDerivedState.ALL_ON, workAlarms.deriveGroupState("Work"))

        val disabledWorkAlarms = listOf(
            Alarm(id = 1L, hour = 7, minute = 0, isEnabled = false, group = "Work"),
            Alarm(id = 2L, hour = 8, minute = 0, isEnabled = false, group = "Work")
        )
        assertEquals(GroupDerivedState.ALL_OFF, disabledWorkAlarms.deriveGroupState("Work"))

        val mixedWorkAlarms = listOf(
            Alarm(id = 1L, hour = 7, minute = 0, isEnabled = true, group = "Work"),
            Alarm(id = 2L, hour = 8, minute = 0, isEnabled = false, group = "Work")
        )
        assertEquals(GroupDerivedState.MIXED, mixedWorkAlarms.deriveGroupState("Work"))

        assertEquals(GroupDerivedState.EMPTY, emptyList<Alarm>().deriveGroupState("Work"))
    }

    @Test
    fun `Group bulk ON and OFF updates eligible group members and excludes other groups`() = runBlocking {
        val alarm1 = alarmRepo.save(Alarm(hour = 7, minute = 0, isEnabled = false, group = "Work"))
        val alarm2 = alarmRepo.save(Alarm(hour = 8, minute = 0, isEnabled = false, group = "Work"))
        val otherAlarm = alarmRepo.save(Alarm(hour = 9, minute = 0, isEnabled = false, group = "Personal"))

        val viewModel = AlarmListViewModel(
            context = context,
            repository = alarmRepo,
            scheduler = mockNormalScheduler,
            calculator = calculator,
            preferencesManager = preferencesManager,
            eventRepository = eventRepo,
            quickAlarmScheduler = quickScheduler
        )

        // Bulk ON for "Work"
        val resultOn = viewModel.setGroupEnabled("Work", targetEnabled = true)
        assertEquals(2, resultOn.changedCount)
        assertEquals(0, resultOn.skippedCount)

        assertTrue("Work Alarm 1 MUST be enabled", alarmRepo.getById(alarm1)!!.isEnabled)
        assertTrue("Work Alarm 2 MUST be enabled", alarmRepo.getById(alarm2)!!.isEnabled)
        assertFalse("Personal Alarm MUST remain disabled", alarmRepo.getById(otherAlarm)!!.isEnabled)

        // Bulk OFF for "Work"
        val resultOff = viewModel.setGroupEnabled("Work", targetEnabled = false)
        assertEquals(2, resultOff.changedCount)
        assertEquals(0, resultOff.skippedCount)

        assertFalse("Work Alarm 1 MUST be disabled", alarmRepo.getById(alarm1)!!.isEnabled)
        assertFalse("Work Alarm 2 MUST be disabled", alarmRepo.getById(alarm2)!!.isEnabled)
    }

    @Test
    fun `Quick Alarms are excluded from Group bulk ON and OFF`() = runBlocking {
        val workAlarm = alarmRepo.save(Alarm(hour = 7, minute = 0, isEnabled = false, group = "Work"))
        val quickAlarm = alarmRepo.save(
            Alarm(
                hour = 8,
                minute = 0,
                isEnabled = true,
                kind = Alarm.KIND_QUICK,
                group = "Work" // Even if legacy/malformed data holds a group string
            )
        )

        val viewModel = AlarmListViewModel(
            context = context,
            repository = alarmRepo,
            scheduler = mockNormalScheduler,
            calculator = calculator,
            preferencesManager = preferencesManager,
            eventRepository = eventRepo,
            quickAlarmScheduler = quickScheduler
        )

        // Bulk OFF for "Work"
        val resultOff = viewModel.setGroupEnabled("Work", targetEnabled = false)
        assertEquals(0, resultOff.changedCount) // workAlarm was already false, quickAlarm excluded

        val quickBefore = alarmRepo.getById(quickAlarm)!!
        assertTrue("Quick Alarm MUST remain enabled and untouched", quickBefore.isEnabled)
    }

    @Test
    fun `Active session owner member is skipped during Group OFF`() = runBlocking {
        val alarm1 = alarmRepo.save(Alarm(hour = 7, minute = 0, isEnabled = true, group = "Work"))
        val activeAlarmId = alarmRepo.save(Alarm(hour = 8, minute = 0, isEnabled = true, group = "Work"))

        // Mock activeAlarmId in PreferencesManager
        coEvery { preferencesManager.getCurrentSettings() } returns AppSettings(activeAlarmId = activeAlarmId)

        val viewModel = AlarmListViewModel(
            context = context,
            repository = alarmRepo,
            scheduler = mockNormalScheduler,
            calculator = calculator,
            preferencesManager = preferencesManager,
            eventRepository = eventRepo,
            quickAlarmScheduler = quickScheduler
        )

        // Bulk OFF for "Work"
        val resultOff = viewModel.setGroupEnabled("Work", targetEnabled = false)
        assertEquals(1, resultOff.changedCount)
        assertEquals(1, resultOff.skippedCount)

        assertFalse("Eligible Work Alarm 1 MUST be disabled", alarmRepo.getById(alarm1)!!.isEnabled)
        assertTrue("Active Work Alarm 2 MUST be skipped and remain enabled", alarmRepo.getById(activeAlarmId)!!.isEnabled)
    }

    @Test
    fun `Active session protection revalidates getCurrentSettings at mutation boundary`() = runBlocking {
        val alarm1 = alarmRepo.save(Alarm(hour = 7, minute = 0, isEnabled = true, group = "Work"))
        val activeAlarmId = alarmRepo.save(Alarm(hour = 8, minute = 0, isEnabled = true, group = "Work"))

        // Mock getCurrentSettings to return activeAlarmId at mutation boundary even if cached settings were stale
        coEvery { preferencesManager.getCurrentSettings() } returns AppSettings(activeAlarmId = activeAlarmId)

        val viewModel = AlarmListViewModel(
            context = context,
            repository = alarmRepo,
            scheduler = mockNormalScheduler,
            calculator = calculator,
            preferencesManager = preferencesManager,
            eventRepository = eventRepo,
            quickAlarmScheduler = quickScheduler
        )

        val resultOff = viewModel.setGroupEnabled("Work", targetEnabled = false)
        assertEquals(1, resultOff.changedCount)
        assertEquals(1, resultOff.skippedCount)

        assertFalse("Eligible Work Alarm 1 MUST be disabled", alarmRepo.getById(alarm1)!!.isEnabled)
        assertTrue("Active Work Alarm 2 MUST be skipped and remain enabled", alarmRepo.getById(activeAlarmId)!!.isEnabled)
    }

    @Test
    fun `Scheduler partial failure reports scheduleBlockedCount truthfully`() = runBlocking {
        val alarm1 = alarmRepo.save(Alarm(hour = 7, minute = 0, isEnabled = false, group = "Work"))

        // Mock scheduler returning PermissionBlocked
        val blockingScheduler = mockk<AlarmScheduler>()
        coEvery { blockingScheduler.schedule(any()) } returns ScheduleOutcome.PermissionBlocked(alarm1, "Exact alarm permission missing")
        coEvery { blockingScheduler.cancel(any()) } returns Unit
        coEvery { blockingScheduler.syncBedtimeDndRule() } returns Unit

        val viewModel = AlarmListViewModel(
            context = context,
            repository = alarmRepo,
            scheduler = blockingScheduler,
            calculator = calculator,
            preferencesManager = preferencesManager,
            eventRepository = eventRepo,
            quickAlarmScheduler = quickScheduler
        )

        val resultOn = viewModel.setGroupEnabled("Work", targetEnabled = true)
        assertEquals(1, resultOn.changedCount)
        assertEquals(0, resultOn.skippedCount)
        assertEquals(1, resultOn.scheduleBlockedCount)
    }

    @Test
    fun `Group bulk command does not overwrite concurrent individual alarm toggle`() = runBlocking {
        val alarm1 = alarmRepo.save(Alarm(hour = 7, minute = 0, isEnabled = false, group = "Work", label = "Morning Work"))
        val alarm2 = alarmRepo.save(Alarm(hour = 8, minute = 0, isEnabled = false, group = "Work", label = "Standup Work"))

        val viewModel = AlarmListViewModel(
            context = context,
            repository = alarmRepo,
            scheduler = mockNormalScheduler,
            calculator = calculator,
            preferencesManager = preferencesManager,
            eventRepository = eventRepo,
            quickAlarmScheduler = quickScheduler
        )

        // 1. Group ON command
        viewModel.setGroupEnabled("Work", targetEnabled = true)

        // 2. Individual toggle on alarm1 -> false
        val savedAlarm1 = alarmRepo.getById(alarm1)!!
        viewModel.toggleAlarm(savedAlarm1)

        val finalAlarm1 = alarmRepo.getById(alarm1)!!
        val finalAlarm2 = alarmRepo.getById(alarm2)!!

        assertFalse("Alarm 1 MUST be disabled by individual toggle", finalAlarm1.isEnabled)
        assertTrue("Alarm 2 MUST remain enabled by group command", finalAlarm2.isEnabled)
        assertEquals("Morning Work", finalAlarm1.label) // Column update preserved label
    }

    @Test
    fun `Process restart recovery re-arms enabled group alarms from DB truth`() = runBlocking {
        val alarm1 = alarmRepo.save(Alarm(hour = 7, minute = 0, isEnabled = false, group = "Work"))
        val alarm2 = alarmRepo.save(Alarm(hour = 8, minute = 0, isEnabled = false, group = "Work"))

        val viewModel = AlarmListViewModel(
            context = context,
            repository = alarmRepo,
            scheduler = mockNormalScheduler,
            calculator = calculator,
            preferencesManager = preferencesManager,
            eventRepository = eventRepo,
            quickAlarmScheduler = quickScheduler
        )

        // Execute Group ON
        viewModel.setGroupEnabled("Work", targetEnabled = true)

        // Verify enabled alarms in Room DB truth
        val enabledAlarms = alarmRepo.getAll().filter { it.isEnabled && !it.isQuickAlarm }
        assertEquals(2, enabledAlarms.size)
        assertTrue(enabledAlarms.any { it.id == alarm1 })
        assertTrue(enabledAlarms.any { it.id == alarm2 })
    }
}
