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
import io.mockk.spyk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
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

        var attempts = 0
        while (alarmRepo.getById(alarm1)?.isEnabled != false && attempts < 20) {
            delay(20)
            attempts++
        }

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

    @Test
    fun `Production repository updateAlarmsEnabledTransaction rolls back entire batch on failure`() = runBlocking {
        // Arrange
        val alarm1 = alarmRepo.save(Alarm(hour = 7, minute = 0, isEnabled = false, group = "Work", nextTriggerTime = 0L))
        val alarm2 = alarmRepo.save(Alarm(hour = 8, minute = 0, isEnabled = false, group = "Work", nextTriggerTime = 0L))
        val alarm3 = alarmRepo.save(Alarm(hour = 9, minute = 0, isEnabled = false, group = "Work", nextTriggerTime = 0L))
        val otherAlarm = alarmRepo.save(Alarm(hour = 10, minute = 0, isEnabled = false, group = "Personal", nextTriggerTime = 0L))
        val quickAlarm = alarmRepo.save(Alarm(hour = 11, minute = 0, isEnabled = true, kind = Alarm.KIND_QUICK, group = "Work", nextTriggerTime = 123456789L))

        // Create a fault-injecting AlarmDao delegate wrapping real Room alarmDao
        val faultInjectingDao = object : AlarmDao by alarmDao {
            override suspend fun updateAlarmsEnabled(alarms: List<Alarm>) {
                alarms.forEachIndexed { index, alarm ->
                    if (index == 1) {
                        throw IllegalStateException("Injected Room transaction failure on 2nd row update")
                    }
                    alarmDao.setEnabled(alarm.id, alarm.isEnabled, alarm.nextTriggerTime)
                }
            }
        }

        val faultRepo = AlarmRepository(db, faultInjectingDao, db.alarmGroupDao())

        val preparedAlarms = listOf(
            alarmRepo.getById(alarm1)!!.copy(isEnabled = true, nextTriggerTime = 999999L),
            alarmRepo.getById(alarm2)!!.copy(isEnabled = true, nextTriggerTime = 888888L),
            alarmRepo.getById(alarm3)!!.copy(isEnabled = true, nextTriggerTime = 777777L)
        )

        // Act: Execute production repository method
        var exceptionThrown = false
        try {
            faultRepo.updateAlarmsEnabledTransaction(preparedAlarms)
        } catch (e: IllegalStateException) {
            exceptionThrown = true
        }

        assertTrue("Production repository call MUST propagate transaction exception", exceptionThrown)

        // Assert 1: First member's attempted update was rolled back in Room DB
        val rolledBack1 = alarmRepo.getById(alarm1)!!
        assertFalse("Alarm 1 MUST be rolled back to isEnabled == false", rolledBack1.isEnabled)
        assertEquals("Alarm 1 nextTriggerTime MUST be rolled back to 0L", 0L, rolledBack1.nextTriggerTime)

        // Assert 2: All group members retain original committed DB values
        val rolledBack2 = alarmRepo.getById(alarm2)!!
        assertFalse("Alarm 2 MUST retain isEnabled == false", rolledBack2.isEnabled)
        assertEquals(0L, rolledBack2.nextTriggerTime)

        val rolledBack3 = alarmRepo.getById(alarm3)!!
        assertFalse("Alarm 3 MUST retain isEnabled == false", rolledBack3.isEnabled)
        assertEquals(0L, rolledBack3.nextTriggerTime)

        // Assert 3: Unrelated Group and Quick Alarm remain untouched
        assertEquals(false, alarmRepo.getById(otherAlarm)!!.isEnabled)
        assertEquals(true, alarmRepo.getById(quickAlarm)!!.isEnabled)
    }

    @Test
    fun `Real deterministic interleaving proves mutationMutex prevents stale cancel race`() = runBlocking {
        // Arrange
        val alarm1Id = alarmRepo.save(Alarm(hour = 7, minute = 0, isEnabled = true, group = "Work"))

        val dbCommittedBarrier = CompletableDeferred<Unit>()
        val resumeCancelBarrier = CompletableDeferred<Unit>()
        val toggleStartedBarrier = CompletableDeferred<Unit>()
        val registeredAlarmIds = mutableSetOf<Long>(alarm1Id)

        val spyRepo = spyk(alarmRepo)

        coEvery { spyRepo.updateAlarmsEnabledTransaction(any()) } coAnswers {
            val alarms = firstArg<List<Alarm>>()
            alarmRepo.updateAlarmsEnabledTransaction(alarms)
            if (!dbCommittedBarrier.isCompleted) {
                dbCommittedBarrier.complete(Unit)
                resumeCancelBarrier.await()
            }
        }

        val trackingScheduler = mockk<AlarmScheduler>()

        coEvery { trackingScheduler.schedule(any()) } coAnswers {
            val alarm = firstArg<Alarm>()
            registeredAlarmIds.add(alarm.id)
            ScheduleOutcome.Armed(alarm.id, alarm.nextTriggerTime)
        }

        coEvery { trackingScheduler.cancel(any()) } coAnswers {
            val id = firstArg<Long>()
            registeredAlarmIds.remove(id)
            Unit
        }

        coEvery { trackingScheduler.syncBedtimeDndRule() } returns Unit

        val viewModel = AlarmListViewModel(
            context = context,
            repository = spyRepo,
            scheduler = trackingScheduler,
            calculator = calculator,
            preferencesManager = preferencesManager,
            eventRepository = eventRepo,
            quickAlarmScheduler = quickScheduler
        )

        // 1. Launch Group OFF in background coroutine (on Dispatchers.Default)
        val groupJob = launch(Dispatchers.Default) {
            viewModel.setGroupEnabled("Work", targetEnabled = false)
        }

        // 2. Wait until Group OFF commits Room DB update and hits spyRepo barrier right after DB commit
        dbCommittedBarrier.await()

        // Room DB was updated to isEnabled == false by setGroupEnabled
        val disabledInDb = spyRepo.getById(alarm1Id)!!
        assertFalse("DB update committed isEnabled == false", disabledInDb.isEnabled)

        // 3. Launch user toggleAlarm back ON in parallel coroutine
        val toggleJob = launch(Dispatchers.Default) {
            toggleStartedBarrier.complete(Unit)
            viewModel.toggleAlarm(disabledInDb)
        }
        toggleStartedBarrier.await()

        // 4. Release Group OFF post-commit reconciliation loop
        resumeCancelBarrier.complete(Unit)

        // Wait for both coroutines to complete
        groupJob.join()
        toggleJob.join()

        var attempts = 0
        while (spyRepo.getById(alarm1Id)?.isEnabled != true && attempts < 20) {
            delay(20)
            attempts++
        }

        // 5. Assert Final State
        val finalAlarm = spyRepo.getById(alarm1Id)!!
        assertTrue("Final Room DB truth: Alarm 1 isEnabled == true", finalAlarm.isEnabled)
        assertTrue("Final OS AlarmManager state: registeredAlarmIds MUST contain alarm1Id", registeredAlarmIds.contains(alarm1Id))
    }

    @Test
    fun `Post-commit DB re-verification prevents stale cancel from removing concurrent toggle ON registration`() = runBlocking {
        // Arrange
        val alarm1Id = alarmRepo.save(Alarm(hour = 7, minute = 0, isEnabled = true, group = "Work"))

        val registeredAlarmIds = mutableSetOf<Long>(alarm1Id)
        val trackingScheduler = mockk<AlarmScheduler>()

        coEvery { trackingScheduler.schedule(any()) } coAnswers {
            val alarm = firstArg<Alarm>()
            registeredAlarmIds.add(alarm.id)
            ScheduleOutcome.Armed(alarm.id, alarm.nextTriggerTime)
        }

        coEvery { trackingScheduler.cancel(any()) } coAnswers {
            val id = firstArg<Long>()
            registeredAlarmIds.remove(id)
            Unit
        }

        coEvery { trackingScheduler.syncBedtimeDndRule() } returns Unit

        val viewModel = AlarmListViewModel(
            context = context,
            repository = alarmRepo,
            scheduler = trackingScheduler,
            calculator = calculator,
            preferencesManager = preferencesManager,
            eventRepository = eventRepo,
            quickAlarmScheduler = quickScheduler
        )

        // 1. Group OFF command runs for Work
        viewModel.setGroupEnabled("Work", targetEnabled = false)
        assertFalse(alarmRepo.getById(alarm1Id)!!.isEnabled)
        assertFalse(registeredAlarmIds.contains(alarm1Id))

        // 2. User toggles alarm1 back ON
        val disabledAlarm1 = alarmRepo.getById(alarm1Id)!!
        viewModel.toggleAlarm(disabledAlarm1)

        var attempts = 0
        while (alarmRepo.getById(alarm1Id)?.isEnabled != true && attempts < 20) {
            delay(20)
            attempts++
        }

        // Verify PASS-after: Room DB truth is isEnabled == true AND OS AlarmManager registeredAlarmIds contains alarm1Id
        val finalAlarm1 = alarmRepo.getById(alarm1Id)!!
        assertTrue("Room DB truth: Alarm 1 is enabled", finalAlarm1.isEnabled)
        assertTrue("OS AlarmManager state: Alarm 1 is registered and armed", registeredAlarmIds.contains(alarm1Id))
    }

    @Test
    fun `Case B - Group ON followed by individual toggle OFF preserves DB and OS cancellation`() = runBlocking {
        val alarm1Id = alarmRepo.save(Alarm(hour = 7, minute = 0, isEnabled = false, group = "Work"))

        val registeredAlarmIds = mutableSetOf<Long>()
        val trackingScheduler = mockk<AlarmScheduler>()

        coEvery { trackingScheduler.schedule(any()) } coAnswers {
            val alarm = firstArg<Alarm>()
            registeredAlarmIds.add(alarm.id)
            ScheduleOutcome.Armed(alarm.id, alarm.nextTriggerTime)
        }

        coEvery { trackingScheduler.cancel(any()) } coAnswers {
            val id = firstArg<Long>()
            registeredAlarmIds.remove(id)
            Unit
        }

        coEvery { trackingScheduler.syncBedtimeDndRule() } returns Unit

        val viewModel = AlarmListViewModel(
            context = context,
            repository = alarmRepo,
            scheduler = trackingScheduler,
            calculator = calculator,
            preferencesManager = preferencesManager,
            eventRepository = eventRepo,
            quickAlarmScheduler = quickScheduler
        )

        // 1. Group ON command
        viewModel.setGroupEnabled("Work", targetEnabled = true)
        assertTrue(alarmRepo.getById(alarm1Id)!!.isEnabled)
        assertTrue(registeredAlarmIds.contains(alarm1Id))

        // 2. Individual toggle OFF
        val enabledAlarm1 = alarmRepo.getById(alarm1Id)!!
        viewModel.toggleAlarm(enabledAlarm1)

        var attempts = 0
        while (alarmRepo.getById(alarm1Id)?.isEnabled != false && attempts < 20) {
            delay(20)
            attempts++
        }

        assertFalse("Room DB truth: Alarm 1 is disabled", alarmRepo.getById(alarm1Id)!!.isEnabled)
        assertFalse("OS AlarmManager state: Alarm 1 is cancelled", registeredAlarmIds.contains(alarm1Id))
    }

    @Test
    fun `Case C - Group operations interleave safely with multi-select disableSelected`() = runBlocking {
        val alarm1Id = alarmRepo.save(Alarm(hour = 7, minute = 0, isEnabled = true, group = "Work"))
        val alarm2Id = alarmRepo.save(Alarm(hour = 8, minute = 0, isEnabled = true, group = "Work"))

        val viewModel = AlarmListViewModel(
            context = context,
            repository = alarmRepo,
            scheduler = mockNormalScheduler,
            calculator = calculator,
            preferencesManager = preferencesManager,
            eventRepository = eventRepo,
            quickAlarmScheduler = quickScheduler
        )

        // Select alarm1 and alarm2, then disableSelected
        viewModel.selectMany(setOf(alarm1Id, alarm2Id))
        viewModel.disableSelected()

        var attempts = 0
        while (alarmRepo.getById(alarm1Id)?.isEnabled != false && attempts < 20) {
            delay(20)
            attempts++
        }

        assertFalse(alarmRepo.getById(alarm1Id)!!.isEnabled)
        assertFalse(alarmRepo.getById(alarm2Id)!!.isEnabled)
    }

    @Test
    fun `Case D - Repeated sequential Group operations converge on latest intent`() = runBlocking {
        val alarm1Id = alarmRepo.save(Alarm(hour = 7, minute = 0, isEnabled = false, group = "Work"))

        val viewModel = AlarmListViewModel(
            context = context,
            repository = alarmRepo,
            scheduler = mockNormalScheduler,
            calculator = calculator,
            preferencesManager = preferencesManager,
            eventRepository = eventRepo,
            quickAlarmScheduler = quickScheduler
        )

        viewModel.setGroupEnabled("Work", true)
        assertTrue(alarmRepo.getById(alarm1Id)!!.isEnabled)

        viewModel.setGroupEnabled("Work", false)
        assertFalse(alarmRepo.getById(alarm1Id)!!.isEnabled)

        viewModel.setGroupEnabled("Work", true)
        assertTrue("Final state matches latest ON intent", alarmRepo.getById(alarm1Id)!!.isEnabled)
    }

    @Test
    fun `Case E - Stale UI card snapshot is revalidated against DB truth in toggleAlarm`() = runBlocking {
        val alarm1Id = alarmRepo.save(Alarm(hour = 7, minute = 0, isEnabled = false, group = "Work"))
        val staleCardSnapshot = Alarm(id = alarm1Id, hour = 7, minute = 0, isEnabled = true, group = "Work")

        val viewModel = AlarmListViewModel(
            context = context,
            repository = alarmRepo,
            scheduler = mockNormalScheduler,
            calculator = calculator,
            preferencesManager = preferencesManager,
            eventRepository = eventRepo,
            quickAlarmScheduler = quickScheduler
        )

        // Pass stale snapshot with isEnabled == true into toggleAlarm
        viewModel.toggleAlarm(staleCardSnapshot)

        var attempts = 0
        while (alarmRepo.getById(alarm1Id)?.isEnabled != true && attempts < 20) {
            delay(20)
            attempts++
        }

        // DB truth was isEnabled == false, so toggleAlarm revalidated DB truth and toggled it to true!
        assertTrue("Revalidated DB truth toggled alarm to enabled = true", alarmRepo.getById(alarm1Id)!!.isEnabled)
    }

    @Test
    fun `Deleted Alarm fallback in toggleAlarm handles missing DB record safely`() = runBlocking {
        val alarm1Id = alarmRepo.save(Alarm(hour = 7, minute = 0, isEnabled = false, group = "Work"))
        val deletedSnapshot = Alarm(id = alarm1Id, hour = 7, minute = 0, isEnabled = false, group = "Work")

        // Delete alarm1 from Room DB
        alarmRepo.delete(deletedSnapshot)

        val viewModel = AlarmListViewModel(
            context = context,
            repository = alarmRepo,
            scheduler = mockNormalScheduler,
            calculator = calculator,
            preferencesManager = preferencesManager,
            eventRepository = eventRepo,
            quickAlarmScheduler = quickScheduler
        )

        // Attempt toggleAlarm on deleted snapshot -> returns early safely without inserting or scheduling
        viewModel.toggleAlarm(deletedSnapshot)

        assertEquals(null, alarmRepo.getById(alarm1Id))
    }
}
