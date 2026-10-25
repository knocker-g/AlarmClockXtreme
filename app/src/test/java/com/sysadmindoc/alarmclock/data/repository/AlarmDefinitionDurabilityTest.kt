package com.sysadmindoc.alarmclock.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.sysadmindoc.alarmclock.data.local.AlarmDatabase
import com.sysadmindoc.alarmclock.data.model.Alarm
import com.sysadmindoc.alarmclock.data.preferences.AppSettings
import com.sysadmindoc.alarmclock.data.preferences.PreferencesManager
import com.sysadmindoc.alarmclock.domain.AlarmScheduler
import com.sysadmindoc.alarmclock.domain.NextAlarmCalculator
import com.sysadmindoc.alarmclock.domain.ScheduleOutcome
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
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
class AlarmDefinitionDurabilityTest {

    private lateinit var context: Context
    private lateinit var db: AlarmDatabase
    private lateinit var alarmRepo: AlarmRepository
    private lateinit var incidentRepo: AlarmIncidentRepository
    private lateinit var scheduler: AlarmScheduler

    private val calculator: NextAlarmCalculator = mockk(relaxed = true)
    private val preferencesManager: PreferencesManager = mockk(relaxed = true)

    @Before
    fun setUp() {
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

        every { preferencesManager.getCachedSettings() } returns AppSettings()
        coEvery { preferencesManager.getCurrentSettings() } returns AppSettings()

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
        db.close()
    }

    @Test
    fun `save creates group and alarm atomically in a single transaction`() = runBlocking {
        val alarm = Alarm(
            hour = 7,
            minute = 0,
            group = "Work Alarms",
            isEnabled = true
        )

        val id = alarmRepo.save(alarm)
        assertTrue("Saved alarm ID must be positive", id > 0L)

        val groups = alarmRepo.getAllGroups()
        assertTrue("Group 'Work Alarms' must exist in DB", groups.contains("Work Alarms"))

        val savedAlarm = alarmRepo.getById(id)
        assertNotNull(savedAlarm)
        assertEquals("Work Alarms", savedAlarm?.group)
    }

    @Test
    fun `createGroupAndAlarm creates group and alarm in a single transaction`() = runBlocking {
        val alarm = Alarm(
            hour = 8,
            minute = 30,
            isEnabled = true
        )

        val id = alarmRepo.createGroupAndAlarm("Fitness", alarm)
        assertTrue("Created alarm ID must be positive", id > 0L)

        val groups = alarmRepo.getAllGroups()
        assertTrue("Group 'Fitness' must exist in DB", groups.contains("Fitness"))

        val savedAlarm = alarmRepo.getById(id)
        assertEquals("Fitness", savedAlarm?.group)
    }

    @Test
    fun `deleteWithCleanup removes alarm and deletes empty group`() = runBlocking {
        val alarm = Alarm(
            hour = 9,
            minute = 0,
            group = "Temporary",
            isEnabled = true
        )

        val id = alarmRepo.save(alarm)
        val savedAlarm = alarmRepo.getById(id)!!

        // Verify group exists
        assertTrue(alarmRepo.getAllGroups().contains("Temporary"))

        // Delete with cleanup
        alarmRepo.deleteWithCleanup(savedAlarm)

        // Verify alarm is deleted
        assertNull(alarmRepo.getById(id))

        // Verify empty group 'Temporary' was cleaned up
        assertFalse("Group 'Temporary' must be deleted when no alarms remain", alarmRepo.getAllGroups().contains("Temporary"))
    }

    @Test
    fun `concurrent default sortOrder allocations assign non-overlapping order values`() = runBlocking {
        val deferreds = List(5) { index ->
            async {
                alarmRepo.save(
                    Alarm(
                        hour = 7 + index,
                        minute = 0,
                        isEnabled = true,
                        sortOrder = 0
                    )
                )
            }
        }
        deferreds.awaitAll()
        val allAlarms = alarmRepo.getAll()

        assertEquals(5, allAlarms.size)
        val sortOrders = allAlarms.map { it.sortOrder }.toSet()
        assertEquals("All 5 sortOrder values MUST be unique", 5, sortOrders.size)
    }

    @Test
    fun `AlarmRepository save persists definition in DB regardless of scheduler outcome`() = runBlocking {
        val triggerTime = System.currentTimeMillis() + 3600_000L
        every { calculator.calculate(any()) } returns triggerTime
        every { calculator.calculate(any(), any()) } returns triggerTime

        val alarm = Alarm(
            hour = 7,
            minute = 0,
            isEnabled = true
        )

        // 1. Save in repository
        val id = alarmRepo.save(alarm)
        val savedAlarm = alarmRepo.getById(id)!!

        // 2. Schedule
        val outcome = scheduler.schedule(savedAlarm)
        assertTrue("Outcome must be Armed", outcome is ScheduleOutcome.Armed)

        // DB definition is persisted independently
        val finalDbAlarm = alarmRepo.getById(id)
        assertNotNull(finalDbAlarm)
        assertTrue(finalDbAlarm!!.isEnabled)
    }
}
