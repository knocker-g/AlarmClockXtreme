package com.sysadmindoc.alarmclock.ui.quickalarm

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
import com.sysadmindoc.alarmclock.ui.alarmlist.AlarmListViewModel
import io.mockk.coEvery
import io.mockk.coVerify
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
class QuickLastUsedDefaultsTest {

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
            quickDefaultRingtoneUri = "content://media/custom_ringtone",
            quickDefaultVibrationEnabled = false,
            quickDefaultVolume = 80
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
    fun `Initial AppSettings loads product defaults for Quick delivery`() {
        val defaultSettings = AppSettings()
        assertEquals("", defaultSettings.quickDefaultRingtoneUri)
        assertTrue(defaultSettings.quickDefaultVibrationEnabled)
        assertEquals(100, defaultSettings.quickDefaultVolume)
    }

    @Test
    fun `Successful Quick creation applies and saves last used delivery defaults`() = runBlocking {
        val viewModel = AlarmListViewModel(
            context = context,
            repository = alarmRepo,
            scheduler = mockNormalScheduler,
            calculator = calculator,
            preferencesManager = preferencesManager,
            eventRepository = eventRepo,
            quickAlarmScheduler = quickScheduler
        )

        val result = viewModel.createQuickAlarm(15)
        assertTrue(result is QuickCreationResult.CreatedAndArmed)

        // Verify created Quick Alarm loaded the delivery defaults
        val createdId = (result as QuickCreationResult.CreatedAndArmed).alarmId
        val createdAlarm = alarmRepo.getById(createdId)
        assertNotNull("Created Quick Alarm MUST exist in DB", createdAlarm)
        assertEquals("content://media/custom_ringtone", createdAlarm!!.ringtoneUri)
        assertFalse(createdAlarm.vibrationEnabled)
        assertEquals(80, createdAlarm.volume)

        // Verify saveQuickDeliveryDefaults was called upon CreatedAndArmed
        coVerify(exactly = 1) {
            preferencesManager.saveQuickDeliveryDefaults("content://media/custom_ringtone", false, 80)
        }
    }

    @Test
    fun `User editing delivery choices passes selected values to createQuickAlarm`() = runBlocking {
        val viewModel = AlarmListViewModel(
            context = context,
            repository = alarmRepo,
            scheduler = mockNormalScheduler,
            calculator = calculator,
            preferencesManager = preferencesManager,
            eventRepository = eventRepo,
            quickAlarmScheduler = quickScheduler
        )

        // User edits delivery choices in QuickAlarmCreateDialog: sound, vibration, volume
        val result = viewModel.createQuickAlarm(
            minutesFromNow = 30,
            ringtoneUri = "content://media/user_selected_ringtone",
            vibrationEnabled = true,
            volume = 65
        )
        assertTrue(result is QuickCreationResult.CreatedAndArmed)

        // Verify created Quick Alarm entity received user-selected delivery values
        val createdId = (result as QuickCreationResult.CreatedAndArmed).alarmId
        val createdAlarm = alarmRepo.getById(createdId)
        assertNotNull("Created Quick Alarm MUST exist in DB", createdAlarm)
        assertEquals("content://media/user_selected_ringtone", createdAlarm!!.ringtoneUri)
        assertTrue(createdAlarm.vibrationEnabled)
        assertEquals(65, createdAlarm.volume)

        // Verify saveQuickDeliveryDefaults was called upon CreatedAndArmed with user-selected values
        coVerify(exactly = 1) {
            preferencesManager.saveQuickDeliveryDefaults("content://media/user_selected_ringtone", true, 65)
        }
    }

    @Test
    fun `Successful absolute Quick creation applies and saves selected delivery choices`() = runBlocking {
        val viewModel = AlarmListViewModel(
            context = context,
            repository = alarmRepo,
            scheduler = mockNormalScheduler,
            calculator = calculator,
            preferencesManager = preferencesManager,
            eventRepository = eventRepo,
            quickAlarmScheduler = quickScheduler
        )

        val result = viewModel.createAbsoluteQuickAlarm(
            hour = 14,
            minute = 30,
            ringtoneUri = "content://media/absolute_ringtone",
            vibrationEnabled = false,
            volume = 50
        )
        assertTrue(result is QuickCreationResult.CreatedAndArmed)

        val createdId = (result as QuickCreationResult.CreatedAndArmed).alarmId
        val createdAlarm = alarmRepo.getById(createdId)
        assertNotNull("Created Quick Alarm MUST exist in DB", createdAlarm)
        assertEquals("content://media/absolute_ringtone", createdAlarm!!.ringtoneUri)
        assertFalse(createdAlarm.vibrationEnabled)
        assertEquals(50, createdAlarm.volume)

        coVerify(exactly = 1) {
            preferencesManager.saveQuickDeliveryDefaults("content://media/absolute_ringtone", false, 50)
        }
    }

    @Test
    fun `Validation rejected creation does NOT update delivery defaults`() = runBlocking {
        val viewModel = AlarmListViewModel(
            context = context,
            repository = alarmRepo,
            scheduler = mockNormalScheduler,
            calculator = calculator,
            preferencesManager = preferencesManager,
            eventRepository = eventRepo,
            quickAlarmScheduler = quickScheduler
        )

        // Invalid duration 0 minutes -> ValidationRejected
        val result = viewModel.createQuickAlarm(0)
        assertTrue(result is QuickCreationResult.ValidationRejected)

        // Verify saveQuickDeliveryDefaults was NOT called
        coVerify(exactly = 0) {
            preferencesManager.saveQuickDeliveryDefaults(any(), any(), any())
        }
    }

    @Test
    fun `Permission blocked creation does NOT update delivery defaults`() = runBlocking {
        // Revoke exact alarm permission
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

        val result = viewModel.createQuickAlarm(15)
        assertTrue(result is QuickCreationResult.PermissionBlocked)

        // Verify saveQuickDeliveryDefaults was NOT called on permission block
        coVerify(exactly = 0) {
            preferencesManager.saveQuickDeliveryDefaults(any(), any(), any())
        }
    }

    @Test
    fun `Trigger time and duration are never persisted as reusable defaults`() {
        val settings = AppSettings()
        // AppSettings MUST NOT contain relative duration, mode, or HH:mm trigger prefill fields
        assertFalse("AppSettings MUST NOT contain relative duration prefill", AppSettings::class.java.declaredFields.any { it.name.contains("quickDefaultDuration") })
        assertFalse("AppSettings MUST NOT contain relative mode prefill", AppSettings::class.java.declaredFields.any { it.name.contains("quickDefaultMode") })
    }
}
