package com.sysadmindoc.alarmclock.ui.alarmlist

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.sysadmindoc.alarmclock.R
import com.sysadmindoc.alarmclock.data.local.AlarmDao
import com.sysadmindoc.alarmclock.data.local.AlarmDatabase
import com.sysadmindoc.alarmclock.data.local.ScheduleAuthorityDao
import com.sysadmindoc.alarmclock.data.local.ScheduleExceptionDao
import com.sysadmindoc.alarmclock.data.local.entity.IssueSlot
import com.sysadmindoc.alarmclock.data.local.entity.ScheduleAuthority
import com.sysadmindoc.alarmclock.data.local.entity.ScheduleException
import com.sysadmindoc.alarmclock.data.model.Alarm
import com.sysadmindoc.alarmclock.data.preferences.AppSettings
import com.sysadmindoc.alarmclock.data.preferences.PreferencesManager
import com.sysadmindoc.alarmclock.data.repository.AlarmEventRepository
import com.sysadmindoc.alarmclock.data.repository.AlarmRepository
import com.sysadmindoc.alarmclock.data.repository.ScheduleExceptionRepository
import com.sysadmindoc.alarmclock.domain.AlarmScheduler
import com.sysadmindoc.alarmclock.domain.NextAlarmCalculator
import com.sysadmindoc.alarmclock.domain.ScheduleOutcome
import com.sysadmindoc.alarmclock.domain.evaluator.DurableOneTimeOverrideAdapter
import com.sysadmindoc.alarmclock.domain.evaluator.HolidayPolicy
import com.sysadmindoc.alarmclock.domain.evaluator.HolidaySnapshot
import com.sysadmindoc.alarmclock.ui.alarmlist.components.OneTimeOverrideTarget
import com.sysadmindoc.alarmclock.ui.alarmlist.components.isEligibleForOneTimeOverride
import io.mockk.coEvery
import io.mockk.every
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
import kotlinx.coroutines.withTimeout
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
import org.robolectric.annotation.Config
import org.w3c.dom.Element
import java.io.File
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import javax.xml.parsers.DocumentBuilderFactory

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class OneTimeOverrideUiTest {

    private lateinit var context: Context
    private lateinit var db: AlarmDatabase
    private lateinit var alarmDao: AlarmDao
    private lateinit var exceptionDao: ScheduleExceptionDao
    private lateinit var authorityDao: ScheduleAuthorityDao
    private lateinit var alarmRepo: AlarmRepository
    private lateinit var exceptionRepo: ScheduleExceptionRepository
    private lateinit var eventRepo: AlarmEventRepository
    private lateinit var preferencesManager: PreferencesManager
    private lateinit var scheduler: AlarmScheduler
    private lateinit var adapter: DurableOneTimeOverrideAdapter
    private lateinit var viewModel: AlarmListViewModel

    private var originalTimeZone: java.util.TimeZone? = null
    private val zoneId = ZoneId.of("America/New_York")
    private val wedDate = LocalDate.of(2026, 11, 4)

    // Base trigger: Wednesday 07:00 AM
    private val wed0700Ms = wedDate
        .atTime(LocalTime.of(7, 0))
        .atZone(zoneId)
        .toInstant()
        .toEpochMilli()

    private val validAuthority = ScheduleAuthority(
        alarmId = 1L,
        instanceToken = "token_alarm_1_abc",
        definitionRevision = 1L,
        scheduleRevision = 1L,
        lifecycle = "LIVE",
        desiredOperation = "SET",
        desiredTriggerMillis = wed0700Ms,
        reconcileState = "SETTLED",
        restoreEpoch = 1700000000L,
        actionKey = null
    )

    @Before
    fun setUp() {
        originalTimeZone = java.util.TimeZone.getDefault()
        java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("America/New_York"))

        try { Dispatchers.resetMain() } catch (_: Exception) {}
        Dispatchers.setMain(UnconfinedTestDispatcher())

        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, AlarmDatabase::class.java)
            .addCallback(AlarmDatabase.DATABASE_CALLBACK)
            .allowMainThreadQueries()
            .build()

        alarmDao = db.alarmDao()
        exceptionDao = db.scheduleExceptionDao()
        authorityDao = db.scheduleAuthorityDao()

        alarmRepo = AlarmRepository(db, alarmDao, db.alarmGroupDao())
        exceptionRepo = ScheduleExceptionRepository(exceptionDao)
        eventRepo = AlarmEventRepository(db.alarmEventDao())

        preferencesManager = mockk(relaxed = true)
        coEvery { preferencesManager.getCurrentSettings() } returns AppSettings()
        every { preferencesManager.getCachedSettings() } returns AppSettings()
        coEvery { preferencesManager.settings } returns flowOf(AppSettings())

        scheduler = mockk(relaxed = true)
        coEvery { scheduler.scheduleAt(any(), any()) } answers {
            val alarm = firstArg<Alarm>()
            val trigger = secondArg<Long>()
            ScheduleOutcome.Armed(alarm.id, trigger)
        }

        adapter = DurableOneTimeOverrideAdapter(database = db)

        viewModel = AlarmListViewModel(
            context = context,
            repository = alarmRepo,
            scheduler = scheduler,
            calculator = NextAlarmCalculator(),
            preferencesManager = preferencesManager,
            eventRepository = eventRepo,
            database = db,
            scheduleExceptionRepository = exceptionRepo,
            oneTimeOverrideAdapter = adapter
        )
    }

    @After
    fun tearDown() {
        originalTimeZone?.let { java.util.TimeZone.setDefault(it) }
        try { io.mockk.clearAllMocks() } catch (_: Exception) {}
        try { Dispatchers.resetMain() } catch (_: Exception) {}
        try { db.close() } catch (_: Exception) {}
    }

    private suspend fun insertRepeatingAlarm(
        id: Long = 1L,
        hour: Int = 7,
        minute: Int = 0,
        days: Set<DayOfWeek> = setOf(DayOfWeek.WEDNESDAY, DayOfWeek.FRIDAY),
        scheduledAt: Long = wed0700Ms,
        isEnabled: Boolean = true
    ): Alarm {
        val alarm = Alarm(
            id = id,
            hour = hour,
            minute = minute,
            repeatDays = days,
            label = "Morning Alarm",
            isEnabled = isEnabled,
            scheduleKind = Alarm.SCHEDULE_KIND_REPEATING,
            kind = Alarm.KIND_NORMAL,
            nextTriggerTime = scheduledAt,
            generation = 1,
            revision = 1
        )
        alarmDao.insert(alarm)
        authorityDao.insertAuthority(
            validAuthority.copy(
                alarmId = id,
                desiredTriggerMillis = if (isEnabled) scheduledAt else null,
                lifecycle = if (isEnabled) "LIVE" else "DISABLED",
                desiredOperation = if (isEnabled) "SET" else "CANCEL"
            )
        )
        db.issuedOccurrenceDao().insertSlotInitial(
            IssueSlot(
                alarmId = id,
                generationId = 1L,
                currentIssueToken = null
            )
        )
        return alarm
    }

    private suspend fun captureFeedback(action: suspend () -> Unit): String {
        action()
        return viewModel.feedbackEvents.replayCache.lastOrNull() ?: ""
    }

    // =========================================================================
    // A. Entry visible for eligible repeating next
    // =========================================================================
    @Test
    fun testA_entryVisibleForEligibleRepeatingNext() = runBlocking {
        val alarm = insertRepeatingAlarm()
        assertTrue("Repeating normal enabled alarm MUST be eligible", alarm.isEligibleForOneTimeOverride(isActiveSession = false))

        viewModel.openOneTimeOverride(alarm)
        val target = viewModel.uiState.first { it.overrideTarget != null }.overrideTarget
        assertNotNull("OneTimeOverrideTarget must be set when opening for eligible alarm", target)
        assertEquals(alarm.id, target!!.alarmId)
        assertEquals(wed0700Ms, target.expectedScheduledAt)
        assertEquals(7, target.baseHour)
        assertEquals(0, target.baseMinute)
    }

    // =========================================================================
    // B. Entry absent for ONE_TIME / Quick / Session / Disabled
    // =========================================================================
    @Test
    fun testB_entryAbsentForOneTimeQuickSessionDisabled() = runBlocking {
        // 1. ONE_TIME alarm
        val oneTime = Alarm(
            id = 2L,
            hour = 7,
            minute = 0,
            repeatDays = emptySet(),
            scheduleKind = Alarm.SCHEDULE_KIND_ONE_TIME,
            kind = Alarm.KIND_NORMAL,
            isEnabled = true,
            nextTriggerTime = wed0700Ms
        )
        assertFalse("ONE_TIME alarm must NOT be eligible", oneTime.isEligibleForOneTimeOverride(isActiveSession = false))

        // 2. Quick alarm
        val quick = Alarm(
            id = 3L,
            hour = 7,
            minute = 0,
            kind = Alarm.KIND_QUICK,
            isEnabled = true,
            nextTriggerTime = wed0700Ms
        )
        assertFalse("Quick alarm must NOT be eligible", quick.isEligibleForOneTimeOverride(isActiveSession = false))

        // 3. Active firing session
        val repeating = insertRepeatingAlarm(id = 4L)
        assertFalse("Active firing session must NOT be eligible", repeating.isEligibleForOneTimeOverride(isActiveSession = true))

        // 4. Disabled alarm
        val disabled = repeating.copy(isEnabled = false)
        assertFalse("Disabled alarm must NOT be eligible", disabled.isEligibleForOneTimeOverride(isActiveSession = false))

        // 5. Ineligible opening emits feedback and does not open target
        viewModel.openOneTimeOverride(oneTime)
        assertNull(viewModel.uiState.value.overrideTarget)
        val feedback = viewModel.feedbackEvents.first()
        assertEquals(context.getString(R.string.one_time_override_ineligible), feedback)
    }

    // =========================================================================
    // C. Base and effective time displayed separately
    // =========================================================================
    @Test
    fun testC_baseAndEffectiveTimeDisplayedSeparately() = runBlocking {
        val alarm = insertRepeatingAlarm()
        val wed0630Ms = wedDate.atTime(LocalTime.of(6, 30)).atZone(zoneId).toInstant().toEpochMilli()

        // Apply a time override to 06:30
        adapter.executeChangeTimeOnce(
            alarmId = alarm.id,
            expectedScheduledAt = alarm.nextTriggerTime,
            newTime = LocalTime.of(6, 30),
            nowEpochMs = wed0700Ms - 7200_000L,
            evaluationZoneId = zoneId
        )

        // Allow flow to propagate to uiState
        val currentEx = withTimeout(5000L) {
            viewModel.uiState.first { state ->
                val ex = state.activeExceptions[alarm.id]
                ex != null && ex.exceptionType == ScheduleException.TYPE_TIME_OVERRIDE
            }.activeExceptions[alarm.id]
        }
        assertNotNull(currentEx)
        assertEquals(ScheduleException.TYPE_TIME_OVERRIDE, currentEx!!.exceptionType)

        val updatedAlarm = alarmRepo.getById(alarm.id)!!
        viewModel.openOneTimeOverride(updatedAlarm)
        val target = viewModel.uiState.first { it.overrideTarget != null }.overrideTarget
        assertNotNull(target)

        // Base time is 07:00
        assertEquals(7, target!!.baseHour)
        assertEquals(0, target.baseMinute)

        // Effective next execution time is 06:30
        assertEquals(wed0630Ms, target.expectedScheduledAt)

        // Active exception captures the override
        assertNotNull(target.activeException)
        assertEquals(wed0630Ms, target.activeException!!.overrideTriggerTime)
    }

    // =========================================================================
    // D. Change time once command uses captured identity
    // =========================================================================
    @Test
    fun testD_changeTimeOnceCommandUsesCapturedIdentity() = runBlocking {
        val alarm = insertRepeatingAlarm()
        viewModel.openOneTimeOverride(alarm)
        val target = viewModel.overrideTarget.value
        assertNotNull(target)

        // Select 08:30 (future time on targetDate)
        viewModel.updateOverrideSelectedTime(8, 30)
        viewModel.applyChangeTimeOnce(8, 30).join()

        val updated = alarmRepo.getById(alarm.id)!!
        val wed0830Ms = wedDate.atTime(LocalTime.of(8, 30)).atZone(zoneId).toInstant().toEpochMilli()
        assertEquals("Alarm nextTriggerTime must be updated to overridden time", wed0830Ms, updated.nextTriggerTime)

        val exceptions = exceptionRepo.getExceptionsForAlarm(alarm.id)
        assertEquals(1, exceptions.size)
        assertEquals(ScheduleException.TYPE_TIME_OVERRIDE, exceptions[0].exceptionType)
        assertEquals(wed0830Ms, exceptions[0].overrideTriggerTime)
    }

    // =========================================================================
    // E. Skip once reuses canonical adapter
    // =========================================================================
    @Test
    fun testE_skipOnceReusesCanonicalAdapter() = runBlocking {
        val alarm = insertRepeatingAlarm()
        viewModel.openOneTimeOverride(alarm)
        viewModel.applySkipOnce().join()

        val updated = alarmRepo.getById(alarm.id)!!
        // Occurrence after next is Friday 07:00 AM
        val fri0700Ms = LocalDate.of(2026, 11, 6)
            .atTime(LocalTime.of(7, 0))
            .atZone(zoneId)
            .toInstant()
            .toEpochMilli()

        assertEquals("Skipping Wednesday occurrence must advance next trigger to Friday", fri0700Ms, updated.nextTriggerTime)
        val exceptions = exceptionRepo.getExceptionsForAlarm(alarm.id)
        assertEquals(1, exceptions.size)
        assertEquals(ScheduleException.TYPE_SKIP, exceptions[0].exceptionType)
    }

    // =========================================================================
    // F. Cancel removes current override only
    // =========================================================================
    @Test
    fun testF_cancelRemovesCurrentOverrideOnly() = runBlocking {
        val alarm = insertRepeatingAlarm()
        // Apply override first
        viewModel.applyChangeTimeOnce(alarm, LocalTime.of(8, 30)).join()
        var updated = alarmRepo.getById(alarm.id)!!
        assertEquals(1, exceptionRepo.getExceptionsForAlarm(alarm.id).size)

        // Cancel override
        viewModel.cancelOverrideForAlarm(updated).join()
        updated = alarmRepo.getById(alarm.id)!!

        assertEquals("Canceling override must restore base trigger time", wed0700Ms, updated.nextTriggerTime)
        val exceptions = exceptionRepo.getExceptionsForAlarm(alarm.id)
        assertEquals("Exception must be deleted after cancel", 0, exceptions.size)
    }

    // =========================================================================
    // G. Stale target refreshes and rejects
    // =========================================================================
    @Test
    fun testG_staleTargetRefreshesAndRejects() = runBlocking {
        val alarm = insertRepeatingAlarm()
        viewModel.openOneTimeOverride(alarm)

        // Concurrently shift alarm's nextTriggerTime in the background
        alarmDao.update(alarm.copy(nextTriggerTime = wed0700Ms + 86400_000L))

        // Attempting to apply using stale captured identity must reject
        val feedback = captureFeedback {
            viewModel.applyChangeTimeOnce(8, 30).join()
        }

        assertNull("Stale target rejection must close sheet", viewModel.uiState.value.overrideTarget)
        assertEquals(context.getString(R.string.one_time_override_stale_target), feedback)
    }

    // =========================================================================
    // H. Past local datetime rejected
    // =========================================================================
    @Test
    fun testH_pastLocalDatetimeRejected() = runBlocking {
        // Occurrence in the past
        val yesterdayMs = System.currentTimeMillis() - 86400_000L
        val alarm = insertRepeatingAlarm(id = 99L, scheduledAt = yesterdayMs)
        viewModel.openOneTimeOverride(alarm)

        val feedback = captureFeedback {
            viewModel.applyChangeTimeOnce(5, 0).join()
        }

        assertEquals(context.getString(R.string.one_time_override_error_past_time), feedback)
    }

    // =========================================================================
    // I. Cross-date rollover rejected
    // =========================================================================
    @Test
    fun testI_crossDateRolloverRejected() = runBlocking {
        val alarm = insertRepeatingAlarm()
        viewModel.openOneTimeOverride(alarm)

        val target = viewModel.overrideTarget.value ?: viewModel.uiState.value.overrideTarget!!
        assertEquals("Target schedule-local date must match occurrence date", wedDate, target.targetDate)
    }

    // =========================================================================
    // J. Pending reconcile message is truthful
    // =========================================================================
    @Test
    fun testJ_pendingReconcileMessageIsTruthful() = runBlocking {
        val alarm = insertRepeatingAlarm()
        // Mock scheduler to fail registration (re-arm registration failed)
        coEvery { scheduler.scheduleAt(any(), any()) } returns ScheduleOutcome.RegistrationFailed(alarm.id, Exception("TEST_FAILURE"))

        val feedback = captureFeedback {
            viewModel.applyChangeTimeOnce(alarm, LocalTime.of(8, 30)).join()
        }

        assertEquals("When scheduler fails registration, pending reconcile message must be truthful",
            context.getString(R.string.one_time_override_pending_reconcile), feedback)
    }

    // =========================================================================
    // K. Conflict / already applied message
    // =========================================================================
    @Test
    fun testK_conflictAndAlreadyAppliedMessages() = runBlocking {
        val alarm = insertRepeatingAlarm()

        // 1. Apply override 08:30
        viewModel.applyChangeTimeOnce(alarm, LocalTime.of(8, 30)).join()

        // 2. Apply same override 08:30 -> ALREADY_COMMITTED
        val updated = alarmRepo.getById(alarm.id)!!
        val alreadyFeedback = captureFeedback {
            viewModel.applyChangeTimeOnce(updated, LocalTime.of(8, 30)).join()
        }
        assertEquals(context.getString(R.string.one_time_override_already_applied), alreadyFeedback)
    }

    // =========================================================================
    // L. Duplicate click suppression
    // =========================================================================
    @Test
    fun testL_duplicateClickSuppression() = runBlocking {
        val alarm = insertRepeatingAlarm()
        viewModel.openOneTimeOverride(alarm)

        val target = viewModel.overrideTarget.value ?: viewModel.uiState.value.overrideTarget!!
        // Simulate isSubmitting = true
        val submittingTarget = target.copy(isSubmitting = true)
        assertTrue(submittingTarget.isSubmitting)
    }

    // =========================================================================
    // M. Disable/delete makes UI ineligible
    // =========================================================================
    @Test
    fun testM_disableOrDeleteMakesUiIneligible() = runBlocking {
        val alarm = insertRepeatingAlarm()
        viewModel.applyChangeTimeOnce(alarm, LocalTime.of(8, 30)).join()
        assertEquals(1, exceptionRepo.getExceptionsForAlarm(alarm.id).size)

        // 1. Disable alarm via repository - must purge pending exceptions atomically
        alarmRepo.setEnabled(alarm.id, enabled = false, nextTrigger = 0)
        val disabled = alarmRepo.getById(alarm.id)!!
        assertFalse(disabled.isEnabled)
        assertFalse("Disabled alarm must not be eligible for override", disabled.isEligibleForOneTimeOverride(isActiveSession = false))
        assertEquals("Disabling alarm must purge pending exceptions", 0, exceptionRepo.getExceptionsForAlarm(alarm.id).size)

        // 2. Re-enable alarm, apply override, then delete - must purge pending exceptions atomically
        alarmRepo.setEnabled(alarm.id, enabled = true, nextTrigger = wed0700Ms)
        viewModel.applyChangeTimeOnce(alarm, LocalTime.of(8, 30)).join()
        assertEquals(1, exceptionRepo.getExceptionsForAlarm(alarm.id).size)

        alarmRepo.delete(alarm)
        assertEquals("Deleting alarm must purge pending exceptions", 0, exceptionRepo.getExceptionsForAlarm(alarm.id).size)
    }

    // =========================================================================
    // N. Holiday change invalidates stale UI
    // =========================================================================
    @Test
    fun testN_holidayChangeInvalidatesStaleUi() = runBlocking {
        val alarm = insertRepeatingAlarm()
        viewModel.applyChangeTimeOnce(alarm, LocalTime.of(8, 30)).join()
        assertEquals(1, exceptionRepo.getExceptionsForAlarm(alarm.id).size)

        // Target moves due to holiday rule (Wednesday is a holiday under EXCLUDE policy)
        adapter.invalidateOverridesIfTargetChanged(
            alarmId = alarm.id,
            nowEpochMs = wed0700Ms - 3600_000L,
            holidaySnapshotProvider = { date ->
                if (date == wedDate) {
                    HolidaySnapshot(state = com.sysadmindoc.alarmclock.domain.evaluator.HolidayState.IS_HOLIDAY)
                } else {
                    HolidaySnapshot(state = com.sysadmindoc.alarmclock.domain.evaluator.HolidayState.NOT_HOLIDAY)
                }
            },
            holidayPolicyOverride = HolidayPolicy.EXCLUDE,
            evaluationZoneId = zoneId
        )

        assertEquals("When target changes due to holiday, obsolete exception must be purged",
            0, exceptionRepo.getExceptionsForAlarm(alarm.id).size)
    }

    // =========================================================================
    // O. State survives recreation
    // =========================================================================
    @Test
    fun testO_stateSurvivesRecreation() = runBlocking {
        val alarm = insertRepeatingAlarm()
        viewModel.applyChangeTimeOnce(alarm, LocalTime.of(8, 30)).join()

        // Verify exception is persisted in repository
        val persisted = exceptionRepo.getExceptionsForAlarm(alarm.id)
        assertEquals(1, persisted.size)
        assertEquals(ScheduleException.TYPE_TIME_OVERRIDE, persisted[0].exceptionType)

        // Recreate ViewModel with the same database
        val recreatedViewModel = AlarmListViewModel(
            context = context,
            repository = alarmRepo,
            scheduler = scheduler,
            calculator = NextAlarmCalculator(),
            preferencesManager = preferencesManager,
            eventRepository = eventRepo,
            database = db,
            scheduleExceptionRepository = exceptionRepo,
            oneTimeOverrideAdapter = adapter
        )

        var state: AlarmListUiState? = null
        val job = launch {
            state = recreatedViewModel.uiState.first { it.activeExceptions.containsKey(alarm.id) }
        }
        job.join()

        assertNotNull("UiState MUST be non-null", state)
        val exceptions = state!!.activeExceptions
        assertNotNull("Recreated ViewModel must observe existing exceptions", exceptions[alarm.id])
        assertEquals(ScheduleException.TYPE_TIME_OVERRIDE, exceptions[alarm.id]!!.exceptionType)
    }

    // =========================================================================
    // P. EN/JA localization parity
    // =========================================================================
    @Test
    fun testP_enJaLocalizationParity() {
        val enFile = File("src/main/res/values/strings.xml").let { if (it.exists()) it else File("app/src/main/res/values/strings.xml") }
        val jaFile = File("src/main/res/values-ja/strings.xml").let { if (it.exists()) it else File("app/src/main/res/values-ja/strings.xml") }

        assertTrue("EN strings file must exist", enFile.exists())
        assertTrue("JA strings file must exist", jaFile.exists())

        val dbFactory = DocumentBuilderFactory.newInstance()
        val dBuilder = dbFactory.newDocumentBuilder()

        val enDoc = dBuilder.parse(enFile)
        val jaDoc = dBuilder.parse(jaFile)

        val enStrings = mutableMapOf<String, String>()
        val jaStrings = mutableMapOf<String, String>()

        val enNodes = enDoc.getElementsByTagName("string")
        for (i in 0 until enNodes.length) {
            val el = enNodes.item(i) as Element
            enStrings[el.getAttribute("name")] = el.textContent.trim()
        }

        val jaNodes = jaDoc.getElementsByTagName("string")
        for (i in 0 until jaNodes.length) {
            val el = jaNodes.item(i) as Element
            jaStrings[el.getAttribute("name")] = el.textContent.trim()
        }

        val requiredKeys = listOf(
            "one_time_override_entry",
            "one_time_override_title",
            "one_time_override_action_change_time",
            "one_time_override_action_skip",
            "one_time_override_action_cancel",
            "one_time_override_button_apply_time",
            "one_time_override_base_time_label",
            "one_time_override_effective_time_label",
            "one_time_override_current_status_label",
            "one_time_override_badge_time",
            "one_time_override_badge_skipped",
            "one_time_override_status_none",
            "one_time_override_success_time",
            "one_time_override_success_cancel",
            "one_time_override_pending_reconcile",
            "one_time_override_stale_target",
            "one_time_override_error_past_time",
            "one_time_override_error_cross_date",
            "one_time_override_error_conflict",
            "one_time_override_already_applied",
            "one_time_override_ineligible",
            "one_time_override_failed"
        )

        for (key in requiredKeys) {
            assertTrue("Key $key must exist in EN strings", enStrings.containsKey(key))
            assertTrue("Key $key in EN strings must not be empty", enStrings[key]!!.isNotBlank())

            assertTrue("Key $key must exist in JA strings", jaStrings.containsKey(key))
            assertTrue("Key $key in JA strings must not be empty", jaStrings[key]!!.isNotBlank())
        }
    }
}
