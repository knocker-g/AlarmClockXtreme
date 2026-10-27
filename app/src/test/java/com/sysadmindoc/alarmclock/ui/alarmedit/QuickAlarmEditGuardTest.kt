package com.sysadmindoc.alarmclock.ui.alarmedit

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.sysadmindoc.alarmclock.data.local.AlarmDao
import com.sysadmindoc.alarmclock.data.local.AlarmDatabase
import com.sysadmindoc.alarmclock.data.model.Alarm
import com.sysadmindoc.alarmclock.data.preferences.AppSettings
import com.sysadmindoc.alarmclock.data.preferences.PreferencesManager
import com.sysadmindoc.alarmclock.data.repository.AlarmRepository
import com.sysadmindoc.alarmclock.domain.AlarmScheduler
import com.sysadmindoc.alarmclock.domain.NextAlarmCalculator
import com.sysadmindoc.alarmclock.domain.ScheduleOutcome
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
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
class QuickAlarmEditGuardTest {

    private lateinit var context: Context
    private lateinit var db: AlarmDatabase
    private lateinit var alarmDao: AlarmDao
    private lateinit var alarmRepo: AlarmRepository
    private lateinit var preferencesManager: PreferencesManager
    private val calculator = NextAlarmCalculator()
    private val mockScheduler: AlarmScheduler = mockk(relaxed = true)

    @Before
    fun setUp() {
        try { Dispatchers.resetMain() } catch (_: Exception) {}
        Dispatchers.setMain(UnconfinedTestDispatcher())

        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, AlarmDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        alarmDao = db.alarmDao()
        alarmRepo = AlarmRepository(db, alarmDao, db.alarmGroupDao())

        preferencesManager = mockk(relaxed = true)
        coEvery { preferencesManager.getCurrentSettings() } returns AppSettings()
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        db.close()
    }

    @Test
    fun `AlarmEditViewModel sets notFound for Quick Alarm ID`() = runBlocking {
        // 1. Save a Quick alarm with kind = KIND_QUICK
        val quickAlarm = Alarm(
            hour = 10,
            minute = 0,
            label = "Quick 10m",
            isEnabled = true,
            kind = Alarm.KIND_QUICK,
            quickTriggerEpochMs = System.currentTimeMillis() + 600_000L
        )
        val id = alarmRepo.save(quickAlarm)

        // 2. Open AlarmEditViewModel targeting quickAlarm.id
        val handle = SavedStateHandle(mapOf("alarmId" to id))
        val viewModel = AlarmEditViewModel(
            savedStateHandle = handle,
            context = context,
            repository = alarmRepo,
            scheduler = mockScheduler,
            calculator = calculator,
            preferencesManager = preferencesManager
        )

        // 3. Await state collection
        val state = viewModel.uiState.first { it.notFound }
        assertTrue("Quick Alarm ID MUST be rejected by AlarmEditViewModel (notFound = true)", state.notFound)
    }

    @Test
    fun `AlarmEditViewModel accepts Normal Alarm ID`() = runBlocking {
        // 1. Save a Normal alarm with kind = KIND_NORMAL
        val normalAlarm = Alarm(
            hour = 8,
            minute = 30,
            label = "Morning Alarm",
            isEnabled = true,
            kind = Alarm.KIND_NORMAL
        )
        val id = alarmRepo.save(normalAlarm)

        // 2. Open AlarmEditViewModel targeting normalAlarm.id
        val handle = SavedStateHandle(mapOf("alarmId" to id))
        val viewModel = AlarmEditViewModel(
            savedStateHandle = handle,
            context = context,
            repository = alarmRepo,
            scheduler = mockScheduler,
            calculator = calculator,
            preferencesManager = preferencesManager
        )

        // 3. Await state collection
        val state = viewModel.uiState.first { it.hour == 8 }
        assertFalse("Normal Alarm ID MUST be loaded successfully (notFound = false)", state.notFound)
        assertEquals(8, state.hour)
        assertEquals(30, state.minute)
    }
}
