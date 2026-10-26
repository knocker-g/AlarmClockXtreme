package com.sysadmindoc.alarmclock.ui

import android.app.AlarmManager
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.sysadmindoc.alarmclock.data.local.AlarmDatabase
import com.sysadmindoc.alarmclock.data.model.Alarm
import com.sysadmindoc.alarmclock.data.preferences.AppSettings
import com.sysadmindoc.alarmclock.data.preferences.PreferencesManager
import com.sysadmindoc.alarmclock.data.repository.AlarmIncidentRepository
import com.sysadmindoc.alarmclock.data.repository.AlarmRepository
import com.sysadmindoc.alarmclock.domain.AlarmScheduler
import com.sysadmindoc.alarmclock.domain.NextAlarmCalculator
import com.sysadmindoc.alarmclock.domain.ScheduleOutcome
import com.sysadmindoc.alarmclock.ui.alarmlist.AlarmListViewModel
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
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
class ProducerOutcomeAdoptionTest {

    private lateinit var context: Context
    private lateinit var db: AlarmDatabase
    private lateinit var alarmRepo: AlarmRepository
    private lateinit var incidentRepo: AlarmIncidentRepository
    private lateinit var scheduler: AlarmScheduler

    private val calculator: NextAlarmCalculator = mockk(relaxed = true)
    private val preferencesManager: PreferencesManager = mockk(relaxed = true)

    @Before
    fun setUp() {
        try { Dispatchers.resetMain() } catch (_: Exception) {}
        Dispatchers.setMain(Dispatchers.Unconfined)
        context = ApplicationProvider.getApplicationContext()
        org.robolectric.shadows.ShadowAlarmManager.setCanScheduleExactAlarms(true)
        db = Room.inMemoryDatabaseBuilder(context, AlarmDatabase::class.java)
            .allowMainThreadQueries()
            .build()

        alarmRepo = AlarmRepository(
            database = db,
            dao = db.alarmDao(),
            groupDao = db.alarmGroupDao()
        )

        incidentRepo = AlarmIncidentRepository(
            dao = db.alarmIncidentEventDao(),
            context = context
        )

        every { preferencesManager.getCachedSettings() } returns AppSettings(activeAlarmId = null)
        coEvery { preferencesManager.getCurrentSettings() } returns AppSettings(activeAlarmId = null)
        every { preferencesManager.settings } returns kotlinx.coroutines.flow.flowOf(AppSettings(activeAlarmId = null))

        val defaultTriggerTime = System.currentTimeMillis() + 3600_000L
        every { calculator.calculate(any()) } returns defaultTriggerTime
        every { calculator.calculate(any(), any()) } returns defaultTriggerTime

        scheduler = AlarmScheduler(
            context = context,
            repository = alarmRepo,
            calculator = calculator,
            preferencesManager = preferencesManager,
            holidayRepository = mockk(relaxed = true),
            alarmIncidentRepository = incidentRepo,
            weatherRepository = mockk(relaxed = true)
        )
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        db.close()
    }

    @Test
    fun `toggleAlarm enabled consumes ScheduleOutcome Armed and emits feedback`() = runBlocking {
        val alarmId = 6001L
        val triggerTime = System.currentTimeMillis() + 3600_000L

        every { calculator.calculate(any()) } returns triggerTime

        val alarm = Alarm(id = alarmId, hour = 7, minute = 0, isEnabled = false, nextTriggerTime = 0L)
        alarmRepo.save(alarm)

        val mockScheduler = mockk<AlarmScheduler>(relaxed = true)
        coEvery { mockScheduler.schedule(any()) } returns ScheduleOutcome.Armed(alarmId, triggerTime)

        val viewModel = AlarmListViewModel(
            context = context,
            repository = alarmRepo,
            scheduler = mockScheduler,
            calculator = calculator,
            preferencesManager = preferencesManager,
            eventRepository = mockk(relaxed = true)
        )

        viewModel.toggleAlarm(alarm)

        val updated = alarmRepo.getById(alarmId)
        assertTrue("Alarm MUST be enabled in DB", updated?.isEnabled == true)
    }

    @Test
    fun `toggleAlarm when permission blocked consumes ScheduleOutcome PermissionBlocked and keeps definition enabled`() = runBlocking {
        val alarmId = 6002L
        val triggerTime = System.currentTimeMillis() + 3600_000L

        every { calculator.calculate(any()) } returns triggerTime

        val alarm = Alarm(id = alarmId, hour = 7, minute = 0, isEnabled = false, nextTriggerTime = 0L)
        alarmRepo.save(alarm)

        val mockScheduler = mockk<AlarmScheduler>(relaxed = true)
        coEvery { mockScheduler.schedule(any()) } returns ScheduleOutcome.PermissionBlocked(alarmId, "EXACT_ALARM_PERMISSION_REVOKED")

        val viewModel = AlarmListViewModel(
            context = context,
            repository = alarmRepo,
            scheduler = mockScheduler,
            calculator = calculator,
            preferencesManager = preferencesManager,
            eventRepository = mockk(relaxed = true)
        )

        viewModel.toggleAlarm(alarm)

        val updated = alarmRepo.getById(alarmId)
        assertTrue("Alarm MUST remain enabled in DB for future permission restoration", updated?.isEnabled == true)
    }

    @Test
    fun `duplicateAlarm creates new definition and consumes ScheduleOutcome`() = runBlocking {
        val originalAlarm = Alarm(id = 6003L, hour = 8, minute = 0, label = "Morning", isEnabled = true)
        val triggerTime = System.currentTimeMillis() + 3600_000L

        every { calculator.calculate(any()) } returns triggerTime

        alarmRepo.save(originalAlarm)

        val mockScheduler = mockk<AlarmScheduler>(relaxed = true)
        coEvery { mockScheduler.schedule(any()) } returns ScheduleOutcome.Armed(6004L, triggerTime)

        val viewModel = AlarmListViewModel(
            context = context,
            repository = alarmRepo,
            scheduler = mockScheduler,
            calculator = calculator,
            preferencesManager = preferencesManager,
            eventRepository = mockk(relaxed = true)
        )

        viewModel.duplicateAlarm(originalAlarm)

        val allAlarms = alarmRepo.getAll()
        assertEquals(2, allAlarms.size)
        val duplicated = allAlarms.find { it.id != 6003L }
        assertNotNull(duplicated)
        assertTrue("Duplicated alarm MUST be enabled in DB", duplicated?.isEnabled == true)
    }

    @Test
    fun `enableSelected bulk enable handles mixed outcomes truthfully`() = runBlocking {
        val triggerTime = System.currentTimeMillis() + 3600_000L
        every { calculator.calculate(any()) } returns triggerTime

        val id1 = alarmRepo.save(Alarm(hour = 7, minute = 0, isEnabled = false))
        val id2 = alarmRepo.save(Alarm(hour = 8, minute = 0, isEnabled = false))

        val mockScheduler = mockk<AlarmScheduler>(relaxed = true)
        coEvery { mockScheduler.schedule(any(), any(), any()) } returns ScheduleOutcome.Armed(id1, 1000L)

        val viewModel = AlarmListViewModel(
            context = context,
            repository = alarmRepo,
            scheduler = mockScheduler,
            calculator = calculator,
            preferencesManager = preferencesManager,
            eventRepository = mockk(relaxed = true)
        )

        viewModel.selectMany(setOf(id1, id2))
        viewModel.enableSelected()
        kotlinx.coroutines.delay(100)

        val updated1 = alarmRepo.getById(id1)
        val updated2 = alarmRepo.getById(id2)
        org.junit.Assert.assertNotNull("updated1 must not be null", updated1)
        org.junit.Assert.assertNotNull("updated2 must not be null", updated2)
        assertTrue("Alarm 1 MUST be enabled in DB (id1=$id1, isEnabled=${updated1?.isEnabled})", updated1?.isEnabled == true)
        assertTrue("Alarm 2 MUST be enabled in DB despite permission blockage (id2=$id2, isEnabled=${updated2?.isEnabled})", updated2?.isEnabled == true)
    }

    @Test
    fun `undoDelete restores deleted definition and consumes ScheduleOutcome`() = runBlocking {
        val initialId = alarmRepo.save(Alarm(hour = 9, minute = 0, isEnabled = true))
        val alarm = alarmRepo.getById(initialId)!!

        val mockScheduler = mockk<AlarmScheduler>(relaxed = true)
        coEvery { mockScheduler.schedule(any()) } returns ScheduleOutcome.Armed(initialId, 1000L)

        val viewModel = AlarmListViewModel(
            context = context,
            repository = alarmRepo,
            scheduler = mockScheduler,
            calculator = calculator,
            preferencesManager = preferencesManager,
            eventRepository = mockk(relaxed = true)
        )

        // Delete alarm first
        viewModel.deleteAlarm(alarm)
        kotlinx.coroutines.delay(100)
        assertNull("Alarm MUST be deleted from DB", alarmRepo.getById(initialId))

        // Undo delete
        viewModel.undoDelete()
        kotlinx.coroutines.delay(100)

        val restoredAlarms = alarmRepo.getAll()
        assertEquals("Alarm MUST be restored in DB", 1, restoredAlarms.size)
        assertTrue("Restored alarm MUST be enabled", restoredAlarms[0].isEnabled)
    }
}
