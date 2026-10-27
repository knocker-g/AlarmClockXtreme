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
import com.sysadmindoc.alarmclock.domain.QuickCreationResult
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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class FabCreationConsolidationTest {

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
        coEvery { preferencesManager.getCurrentSettings() } returns AppSettings(
            quickDefaultRingtoneUri = "content://media/retained_ringtone",
            quickDefaultVibrationEnabled = true,
            quickDefaultVolume = 90
        )
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
    fun `Consolidated Quick creation route consumes KNO-21 delivery defaults`() = runBlocking {
        val viewModel = AlarmListViewModel(
            context = context,
            repository = alarmRepo,
            scheduler = mockNormalScheduler,
            calculator = calculator,
            preferencesManager = preferencesManager,
            eventRepository = eventRepo,
            quickAlarmScheduler = quickScheduler
        )

        // FAB Quick Alarm route triggers createQuickAlarm
        val result = viewModel.createQuickAlarm(minutesFromNow = 20)
        assertTrue(result is QuickCreationResult.CreatedAndArmed)

        val createdId = (result as QuickCreationResult.CreatedAndArmed).alarmId
        val createdAlarm = alarmRepo.getById(createdId)

        assertNotNull("Created Quick Alarm MUST exist in DB", createdAlarm)
        assertTrue("Created Alarm MUST be identified as Quick Alarm", createdAlarm!!.isQuickAlarm)
        assertEquals("content://media/retained_ringtone", createdAlarm.ringtoneUri)
        assertTrue(createdAlarm.vibrationEnabled)
        assertEquals(90, createdAlarm.volume)
    }

    @Test
    fun `Consolidated absolute Quick route applies custom delivery selections`() = runBlocking {
        val viewModel = AlarmListViewModel(
            context = context,
            repository = alarmRepo,
            scheduler = mockNormalScheduler,
            calculator = calculator,
            preferencesManager = preferencesManager,
            eventRepository = eventRepo,
            quickAlarmScheduler = quickScheduler
        )

        // FAB Quick Alarm route triggers createAbsoluteQuickAlarm with user-selected delivery choices
        val result = viewModel.createAbsoluteQuickAlarm(
            hour = 16,
            minute = 45,
            ringtoneUri = "content://media/fab_selected_tone",
            vibrationEnabled = false,
            volume = 70
        )
        assertTrue(result is QuickCreationResult.CreatedAndArmed)

        val createdId = (result as QuickCreationResult.CreatedAndArmed).alarmId
        val createdAlarm = alarmRepo.getById(createdId)

        assertNotNull("Created Quick Alarm MUST exist in DB", createdAlarm)
        assertTrue("Created Alarm MUST be identified as Quick Alarm", createdAlarm!!.isQuickAlarm)
        assertEquals("content://media/fab_selected_tone", createdAlarm.ringtoneUri)
        assertFalse(createdAlarm.vibrationEnabled)
        assertEquals(70, createdAlarm.volume)
    }

    @Test
    fun `Normal alarm creation route produces Normal Alarm without Quick kind`() = runBlocking {
        val normalAlarm = Alarm(
            hour = 8,
            minute = 0,
            label = "Normal FAB Alarm",
            isEnabled = true,
            kind = Alarm.KIND_NORMAL
        )

        val id = alarmRepo.save(normalAlarm)
        val saved = alarmRepo.getById(id)

        assertNotNull("Saved Normal Alarm MUST exist", saved)
        assertFalse("Normal Alarm MUST NOT return isQuickAlarm == true", saved!!.isQuickAlarm)
        assertEquals(Alarm.KIND_NORMAL, saved.kind)
    }
}
