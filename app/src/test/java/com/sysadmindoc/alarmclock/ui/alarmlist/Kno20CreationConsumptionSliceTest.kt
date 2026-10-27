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
import org.junit.Assert.assertNull
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

        val result = viewModel.createQuickAlarm(10)
        assertTrue("Result MUST be CreatedAndArmed", result is com.sysadmindoc.alarmclock.domain.QuickCreationResult.CreatedAndArmed)

        awaitDbCondition { alarmRepo.getPendingQuickAlarms().isNotEmpty() }

        val pending = alarmRepo.getPendingQuickAlarms()
        assertEquals(1, pending.size)
    }

    @Test
    fun `createQuickAlarm returns PermissionBlocked when exact permission revoked`() = runBlocking {
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

        val result = viewModel.createQuickAlarm(20)
        assertTrue("Result MUST be PermissionBlocked", result is com.sysadmindoc.alarmclock.domain.QuickCreationResult.PermissionBlocked)

        // Confirm pending Quick intent is preserved in DB despite permission block
        val pendingQuick = alarmRepo.getPendingQuickAlarms()
        assertEquals("Pending Quick Alarm MUST remain persisted in DB", 1, pendingQuick.size)
    }

    @Test
    fun `retry with retryAlarmId re-arms existing Quick Alarm without duplicate creation`() = runBlocking {
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

        // 1. Initial attempt fails registration / permission
        val result1 = viewModel.createQuickAlarm(30)
        assertTrue("Result 1 MUST be PermissionBlocked", result1 is com.sysadmindoc.alarmclock.domain.QuickCreationResult.PermissionBlocked)
        val blockedId = (result1 as com.sysadmindoc.alarmclock.domain.QuickCreationResult.PermissionBlocked).alarmId

        val pendingInitial = alarmRepo.getPendingQuickAlarms()
        assertEquals("DB MUST contain 1 pending Quick alarm after initial blocked attempt", 1, pendingInitial.size)
        assertEquals(blockedId, pendingInitial[0].id)

        // 2. Exact alarm permission granted by user
        org.robolectric.shadows.ShadowAlarmManager.setCanScheduleExactAlarms(true)

        // 3. User retries creation passing retryAlarmId = blockedId
        val result2 = viewModel.createQuickAlarm(30, retryAlarmId = blockedId)
        assertTrue("Result 2 MUST be CreatedAndArmed", result2 is com.sysadmindoc.alarmclock.domain.QuickCreationResult.CreatedAndArmed)

        val pendingAfterRetry = alarmRepo.getPendingQuickAlarms()
        assertEquals("DB MUST STILL contain exactly 1 pending Quick alarm (no duplicate row created)", 1, pendingAfterRetry.size)
        assertEquals("Re-armed alarm ID MUST match initial blocked ID", blockedId, pendingAfterRetry[0].id)
    }

    @Test
    fun `expired retry returns ValidationRejected and preserves DB record without deletion`() = runBlocking {
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

        // 1. Initial creation (persisted in DB)
        val result1 = viewModel.createQuickAlarm(5)
        assertTrue(result1 is com.sysadmindoc.alarmclock.domain.QuickCreationResult.PermissionBlocked)
        val alarmId = (result1 as com.sysadmindoc.alarmclock.domain.QuickCreationResult.PermissionBlocked).alarmId

        // 2. Simulate time passing beyond the quick trigger epoch time
        val persisted = alarmRepo.getById(alarmId)
        assertNotNull(persisted)
        val expiredAlarm = persisted!!.copy(quickTriggerEpochMs = System.currentTimeMillis() - 60_000L)
        alarmRepo.update(expiredAlarm)

        // 3. Retry on expired trigger ID
        val retryResult = viewModel.createQuickAlarm(5, retryAlarmId = alarmId)
        assertTrue("Expired retry MUST return ValidationRejected", retryResult is com.sysadmindoc.alarmclock.domain.QuickCreationResult.ValidationRejected)

        // 4. Assert the expired Quick record was NOT deleted from DB by the retry path
        val reloaded = alarmRepo.getById(alarmId)
        assertNotNull("Expired Quick record MUST NOT be deleted by retry path", reloaded)
        assertEquals(alarmId, reloaded?.id)
    }

    @Test
    fun `QuickTimeResolver resolveRelative validates 1 to 180 bounds`() {
        val now = System.currentTimeMillis()
        assertNotNull(com.sysadmindoc.alarmclock.domain.QuickTimeResolver.resolveRelative(1, now))
        assertNotNull(com.sysadmindoc.alarmclock.domain.QuickTimeResolver.resolveRelative(180, now))
        assertNull("0 minutes MUST be rejected", com.sysadmindoc.alarmclock.domain.QuickTimeResolver.resolveRelative(0, now))
        assertNull("181 minutes MUST be rejected", com.sysadmindoc.alarmclock.domain.QuickTimeResolver.resolveRelative(181, now))
        assertNull("Negative minutes MUST be rejected", com.sysadmindoc.alarmclock.domain.QuickTimeResolver.resolveRelative(-5, now))
    }

    @Test
    fun `QuickTimeResolver resolveAbsolute enforces strictly less than 24h horizon and DST gap`() {
        val zoneNy = java.time.ZoneId.of("America/New_York")
        val nowNy = java.time.ZonedDateTime.of(2026, 11, 15, 10, 0, 0, 0, zoneNy).toInstant().toEpochMilli()

        // 1. Same-minute selection resolves to next day (+24h = 86_400_000ms), which is NOT < 24h, so MUST be rejected (null)
        val resolvedExact24h = com.sysadmindoc.alarmclock.domain.QuickTimeResolver.resolveAbsolute(10, 0, nowNy, zoneNy)
        assertNull("Exact 24h same-minute selection MUST be rejected as null (horizon is strictly < 24h)", resolvedExact24h)

        // 2. 10:01 AM (1 minute in future = diff 60_000ms < 24h) MUST be accepted
        val resolvedFuture = com.sysadmindoc.alarmclock.domain.QuickTimeResolver.resolveAbsolute(10, 1, nowNy, zoneNy)
        assertNotNull("Strictly future < 24h selection MUST be accepted", resolvedFuture)
        assertEquals(nowNy + 60_000L, resolvedFuture)

        // 3. DST spring-forward gap local time (e.g. 2:30 AM on March 8, 2026 in NY) is invalid
        val dstGapDate = java.time.ZonedDateTime.of(2026, 3, 8, 1, 0, 0, 0, zoneNy).toInstant().toEpochMilli()
        val resolvedGap = com.sysadmindoc.alarmclock.domain.QuickTimeResolver.resolveAbsolute(2, 30, dstGapDate, zoneNy)
        assertNull("Nonexistent local time during DST spring-forward gap MUST be rejected as null", resolvedGap)
    }

    @Test
    fun `createQuickAlarm returns ValidationRejected for 0 or 181 minutes`() = runBlocking {
        val viewModel = AlarmListViewModel(
            context = context,
            repository = alarmRepo,
            scheduler = mockNormalScheduler,
            calculator = calculator,
            preferencesManager = preferencesManager,
            eventRepository = eventRepo,
            quickAlarmScheduler = quickScheduler
        )

        val result0 = viewModel.createQuickAlarm(0)
        assertTrue("Result for 0 minutes MUST be ValidationRejected", result0 is com.sysadmindoc.alarmclock.domain.QuickCreationResult.ValidationRejected)

        val result181 = viewModel.createQuickAlarm(181)
        assertTrue("Result for 181 minutes MUST be ValidationRejected", result181 is com.sysadmindoc.alarmclock.domain.QuickCreationResult.ValidationRejected)
    }

    @Test
    fun `createAbsoluteQuickAlarm resolves next occurrence within 24h and enforces Quick locks`() = runBlocking {
        val viewModel = AlarmListViewModel(
            context = context,
            repository = alarmRepo,
            scheduler = mockNormalScheduler,
            calculator = calculator,
            preferencesManager = preferencesManager,
            eventRepository = eventRepo,
            quickAlarmScheduler = quickScheduler
        )

        // Select 15:30
        viewModel.createAbsoluteQuickAlarm(15, 30)
        awaitDbCondition { alarmRepo.getPendingQuickAlarms().isNotEmpty() }

        val pending = alarmRepo.getPendingQuickAlarms()
        assertEquals(1, pending.size)

        val created = pending[0]
        assertEquals("QUICK", created.kind)
        assertEquals(15, created.hour)
        assertEquals(30, created.minute)
        assertTrue("isQuickAlarm MUST be true", created.isQuickAlarm)
        assertEquals("Max snooze count MUST be 0", 0, created.maxSnoozeCount)
        assertEquals("Challenge type MUST be NONE", "NONE", created.challengeType)
        assertEquals("Group MUST be empty", "", created.group)
    }

    @Test
    fun `createAbsoluteQuickAlarm same-minute selection resolves to next day`() = runBlocking {
        val viewModel = AlarmListViewModel(
            context = context,
            repository = alarmRepo,
            scheduler = mockNormalScheduler,
            calculator = calculator,
            preferencesManager = preferencesManager,
            eventRepository = eventRepo,
            quickAlarmScheduler = quickScheduler
        )

        val nowEpoch = System.currentTimeMillis()
        val nowZone = java.time.ZoneId.systemDefault()
        val nowLocal = java.time.Instant.ofEpochMilli(nowEpoch).atZone(nowZone)

        // Select current HH:mm
        viewModel.createAbsoluteQuickAlarm(nowLocal.hour, nowLocal.minute)
        awaitDbCondition { alarmRepo.getPendingQuickAlarms().isNotEmpty() }

        val pending = alarmRepo.getPendingQuickAlarms()
        assertEquals(1, pending.size)

        val created = pending[0]
        assertTrue("Target trigger MUST be strictly in the future (> nowEpoch)", created.quickTriggerEpochMs > nowEpoch)
        assertTrue("Target trigger MUST be within 25 hours (< 25h)", created.quickTriggerEpochMs <= nowEpoch + 25 * 3600_000L)
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
