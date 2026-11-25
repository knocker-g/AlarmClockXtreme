package com.sysadmindoc.alarmclock.data.backup

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.squareup.moshi.Moshi
import com.sysadmindoc.alarmclock.data.holiday.FileManualHolidayOverrideStore
import com.sysadmindoc.alarmclock.data.holiday.ManualHolidayOverrideStore
import com.sysadmindoc.alarmclock.data.local.AlarmDatabase
import com.sysadmindoc.alarmclock.data.local.entity.AlarmGroup
import com.sysadmindoc.alarmclock.data.local.entity.ScheduleAuthorityMeta
import com.sysadmindoc.alarmclock.data.model.Alarm
import com.sysadmindoc.alarmclock.data.preferences.AppSettings
import com.sysadmindoc.alarmclock.data.preferences.PreferencesManager
import com.sysadmindoc.alarmclock.data.repository.HolidayReconcileCoordinator
import com.sysadmindoc.alarmclock.data.repository.HolidayReconcileResult
import com.sysadmindoc.alarmclock.data.repository.ManualHolidayOverride
import com.sysadmindoc.alarmclock.domain.AlarmScheduler
import com.sysadmindoc.alarmclock.domain.ScheduleOutcome
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.unmockkAll
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.security.MessageDigest
import java.time.DayOfWeek
import java.time.LocalDate
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class RestoreJournalSafetyClosureTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var context: Context
    private lateinit var database: AlarmDatabase
    private lateinit var preferencesManager: PreferencesManager
    private lateinit var alarmScheduler: AlarmScheduler
    private lateinit var coordinator: HolidayReconcileCoordinator
    private lateinit var overrideStore: ManualHolidayOverrideStore
    private lateinit var restoreCoordinator: RestoreJournalCoordinator
    private lateinit var journalFile: File

    private val d1 = LocalDate.of(2026, 12, 24)
    private val d2 = LocalDate.of(2026, 12, 25)

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        database = Room.inMemoryDatabaseBuilder(context, AlarmDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        runBlocking {
            database.scheduleAuthorityDao().insertMeta(
                ScheduleAuthorityMeta(id = 1, restoreEpoch = 1000L, bootstrapState = "BOOTSTRAPPED")
            )
        }
        preferencesManager = mockk(relaxed = true)
        alarmScheduler = mockk(relaxed = true)
        coordinator = mockk(relaxed = true)

        every { coordinator.clockMillis } returns { System.currentTimeMillis() }
        coEvery { preferencesManager.getCurrentSettings() } returns AppSettings()
        coEvery { alarmScheduler.rescheduleAllInBatches(any(), any()) } returns ScheduleOutcome.BatchResult(1, 0, 0, 0, emptyMap())
        coEvery { coordinator.reconcile(any(), any(), any(), any()) } returns HolidayReconcileResult.Success(reconciledCount = 0, isNoOp = true)

        journalFile = File(context.filesDir, RestoreJournalCoordinator.JOURNAL_FILENAME)
        if (journalFile.exists()) journalFile.delete()
        val bakFile = File(context.filesDir, "${RestoreJournalCoordinator.JOURNAL_FILENAME}.bak")
        if (bakFile.exists()) bakFile.delete()

        val overridesFile = File(tempFolder.root, "manual_holiday_overrides.json")
        overrideStore = FileManualHolidayOverrideStore(
            file = overridesFile,
            reconcileCoordinatorProvider = { coordinator }
        )

        restoreCoordinator = RestoreJournalCoordinator(
            context = context,
            database = database,
            preferencesManager = preferencesManager,
            alarmScheduler = alarmScheduler,
            moshi = Moshi.Builder().build(),
            manualHolidayOverrideStore = overrideStore,
            holidayReconcileCoordinator = coordinator
        )
    }

    @After
    fun tearDown() {
        database.close()
        if (journalFile.exists()) journalFile.delete()
        val bakFile = File(context.filesDir, "${RestoreJournalCoordinator.JOURNAL_FILENAME}.bak")
        if (bakFile.exists()) bakFile.delete()
        unmockkAll()
    }

    private fun writeRawJournal(phase: String, snapshotJson: String) {
        val formatVersion = RestoreJournalCoordinator.CURRENT_FORMAT_VERSION
        val payloadToHash = "$formatVersion:$phase:$snapshotJson"
        val md = MessageDigest.getInstance("SHA-256")
        val hashBytes = md.digest(payloadToHash.toByteArray(Charsets.UTF_8))
        val checksum = hashBytes.joinToString("") { "%02x".format(it) }

        val json = org.json.JSONObject().apply {
            put("formatVersion", formatVersion)
            put("transactionId", UUID.randomUUID().toString())
            put("phase", phase)
            put("createdAt", System.currentTimeMillis())
            put("snapshotJson", snapshotJson)
            put("checksum", checksum)
        }
        journalFile.writeText(json.toString())
    }

    @Test
    fun testA_invalid_journal_date_rejects_and_prevents_partial_restoration() = runTest {
        // Current truth: d1 -> FORCE_HOLIDAY
        overrideStore.setOverride(d1, ManualHolidayOverride.FORCE_HOLIDAY)

        val badSnapshot = """
            {
              "settings": {},
              "alarms": [],
              "groups": [],
              "manualOverrides": {
                "not-a-valid-date": "FORCE_WORKDAY"
              }
            }
        """.trimIndent()
        writeRawJournal(RestoreJournalCoordinator.PHASE_PREPARED, badSnapshot)

        try {
            restoreCoordinator.checkAndRecover()
            fail("Expected checkAndRecover to fail on invalid date")
        } catch (e: Exception) {
            assertTrue(e is IllegalStateException || e is SecurityException)
        }

        // Must retain journal on disk for forensics
        assertTrue("Journal must be retained", journalFile.exists())
        // Must NOT overwrite current on-device override
        assertEquals(ManualHolidayOverride.FORCE_HOLIDAY, overrideStore.overrideFor(d1))
    }

    @Test
    fun testB_invalid_enum_rejects_and_prevents_partial_restoration() = runTest {
        overrideStore.setOverride(d1, ManualHolidayOverride.FORCE_HOLIDAY)

        val badSnapshot = """
            {
              "settings": {},
              "alarms": [],
              "groups": [],
              "manualOverrides": {
                "2026-12-25": "VACATION_DAY"
              }
            }
        """.trimIndent()
        writeRawJournal(RestoreJournalCoordinator.PHASE_PREPARED, badSnapshot)

        try {
            restoreCoordinator.checkAndRecover()
            fail("Expected checkAndRecover to fail on invalid enum")
        } catch (e: Exception) {
            assertTrue(e is IllegalStateException || e is SecurityException)
        }

        assertTrue("Journal must be retained", journalFile.exists())
        assertEquals(ManualHolidayOverride.FORCE_HOLIDAY, overrideStore.overrideFor(d1))
    }

    @Test
    fun testC_valid_journal_performs_full_restoration() = runTest {
        overrideStore.setOverride(d1, ManualHolidayOverride.FORCE_WORKDAY)

        val validSnapshot = """
            {
              "settings": {},
              "alarms": [],
              "groups": [],
              "manualOverrides": {
                "2026-12-24": "FORCE_HOLIDAY",
                "2026-12-25": "FORCE_WORKDAY"
              }
            }
        """.trimIndent()
        writeRawJournal(RestoreJournalCoordinator.PHASE_PREPARED, validSnapshot)

        val outcome = restoreCoordinator.checkAndRecover()
        assertEquals(RecoveryOutcome.RESTORED_OLD, outcome)
        assertFalse("Journal must be deleted after full restoration", journalFile.exists())
        assertEquals(ManualHolidayOverride.FORCE_HOLIDAY, overrideStore.overrideFor(d1))
        assertEquals(ManualHolidayOverride.FORCE_WORKDAY, overrideStore.overrideFor(d2))
    }

    @Test
    fun testD_batch_failure_leaves_recovery_pending() = runTest {
        coEvery { alarmScheduler.rescheduleAllInBatches(any(), any()) } returns ScheduleOutcome.BatchResult(
            armedCount = 0,
            blockedCount = 0,
            failedCount = 1,
            noArmCount = 0,
            outcomes = mapOf(1L to ScheduleOutcome.RegistrationFailed(1L, null))
        )

        val snapshot = """
            {
              "settings": {},
              "alarms": [],
              "groups": [],
              "manualOverrides": {
                "2026-12-24": "FORCE_HOLIDAY"
              }
            }
        """.trimIndent()
        writeRawJournal(RestoreJournalCoordinator.PHASE_PREPARED, snapshot)

        val outcome = restoreCoordinator.checkAndRecover()
        assertEquals(RecoveryOutcome.PENDING_RETRY, outcome)
        assertTrue("Journal must be retained when batch registration fails", journalFile.exists())
    }

    @Test
    fun testE_pending_reconcile_retry_leaves_recovery_pending() = runTest {
        coEvery { coordinator.reconcile(any(), any(), any(), any()) } returns HolidayReconcileResult.PendingRetry(
            pendingCount = 1,
            failureCause = null
        )

        val snapshot = """
            {
              "settings": {},
              "alarms": [],
              "groups": [],
              "manualOverrides": {
                "2026-12-24": "FORCE_HOLIDAY"
              }
            }
        """.trimIndent()
        writeRawJournal(RestoreJournalCoordinator.PHASE_PREPARED, snapshot)

        val outcome = restoreCoordinator.checkAndRecover()
        assertEquals(RecoveryOutcome.PENDING_RETRY, outcome)
        assertTrue("Journal must be retained when reconcile returns PendingRetry", journalFile.exists())
    }

    @Test
    fun testF_missing_coordinator_causes_no_false_completion() = runTest {
        val coordinatorWithoutHolidayReconcile = RestoreJournalCoordinator(
            context = context,
            database = database,
            preferencesManager = preferencesManager,
            alarmScheduler = alarmScheduler,
            moshi = Moshi.Builder().build(),
            manualHolidayOverrideStore = overrideStore,
            holidayReconcileCoordinator = null
        )

        val snapshot = """
            {
              "settings": {},
              "alarms": [],
              "groups": [],
              "manualOverrides": {
                "2026-12-24": "FORCE_HOLIDAY"
              }
            }
        """.trimIndent()
        writeRawJournal(RestoreJournalCoordinator.PHASE_PREPARED, snapshot)

        val outcome = coordinatorWithoutHolidayReconcile.checkAndRecover()
        assertEquals(RecoveryOutcome.PENDING_RETRY, outcome)
        assertTrue("Journal must be retained when coordinator is missing", journalFile.exists())
    }

    @Test
    fun testG_all_operations_succeed_clears_journal() = runTest {
        val snapshot = """
            {
              "settings": {},
              "alarms": [],
              "groups": [],
              "manualOverrides": {
                "2026-12-24": "FORCE_HOLIDAY"
              }
            }
        """.trimIndent()
        writeRawJournal(RestoreJournalCoordinator.PHASE_PREPARED, snapshot)

        val outcome = restoreCoordinator.checkAndRecover()
        assertEquals(RecoveryOutcome.RESTORED_OLD, outcome)
        assertFalse("Journal must be cleared on success", journalFile.exists())
    }

    @Test
    fun testH_repeated_recovery_is_idempotent() = runTest {
        val snapshot = """
            {
              "settings": {},
              "alarms": [],
              "groups": [],
              "manualOverrides": {
                "2026-12-24": "FORCE_HOLIDAY"
              }
            }
        """.trimIndent()
        writeRawJournal(RestoreJournalCoordinator.PHASE_PREPARED, snapshot)

        // First run succeeds
        val outcome1 = restoreCoordinator.checkAndRecover()
        assertEquals(RecoveryOutcome.RESTORED_OLD, outcome1)
        assertFalse(journalFile.exists())

        // Second run has no journal
        val outcome2 = restoreCoordinator.checkAndRecover()
        assertEquals(RecoveryOutcome.NONE, outcome2)
    }

    @Test
    fun testI_process_death_after_partial_rollback_recovers_cleanly() = runTest {
        val nonDefaultAlarm = Alarm(
            id = 77L,
            hour = 7,
            minute = 15,
            label = "Morning",
            repeatDays = setOf(DayOfWeek.MONDAY)
        )
        val snapshot = """
            {
              "settings": {},
              "alarms": [
                {
                  "id": 77,
                  "hour": 7,
                  "minute": 15,
                  "label": "Morning",
                  "isEnabled": true,
                  "repeatDays": ["MONDAY"]
                }
              ],
              "groups": [],
              "manualOverrides": {
                "2026-12-24": "FORCE_HOLIDAY"
              }
            }
        """.trimIndent()
        writeRawJournal(RestoreJournalCoordinator.PHASE_PREPARED, snapshot)

        // Simulate that DB was partially updated or corrupted before recovery was completed
        database.alarmDao().deleteAll()

        // Re-execute recovery
        val outcome = restoreCoordinator.checkAndRecover()
        assertEquals(RecoveryOutcome.RESTORED_OLD, outcome)
        assertFalse(journalFile.exists())

        // Verify alarm was restored
        val alarms = database.alarmDao().getAll()
        assertEquals(1, alarms.size)
        assertEquals(77L, alarms[0].id)
        assertEquals(ManualHolidayOverride.FORCE_HOLIDAY, overrideStore.overrideFor(d1))
    }

    @Test
    fun testJ_existing_legacy_journal_remains_compatible() = runTest {
        // Legacy journal has NO manualOverrides field
        val legacySnapshot = """
            {
              "settings": {
                "is24HourFormat": true
              },
              "alarms": [],
              "groups": []
            }
        """.trimIndent()
        writeRawJournal(RestoreJournalCoordinator.PHASE_PREPARED, legacySnapshot)

        val outcome = restoreCoordinator.checkAndRecover()
        assertEquals(RecoveryOutcome.RESTORED_OLD, outcome)
        assertFalse(journalFile.exists())
    }

    @Test
    fun c2b1_preparedRollbackDbSuccessStillRetainsJournalWhenOsReconcileFails() = runTest {
        coEvery { alarmScheduler.rescheduleAllInBatches(any(), any()) } returns ScheduleOutcome.BatchResult(
            armedCount = 0,
            blockedCount = 0,
            failedCount = 1,
            noArmCount = 0,
            outcomes = mapOf(77L to ScheduleOutcome.RegistrationFailed(77L, null))
        )

        val snapshot = """
            {
              "settings": {},
              "alarms": [
                {
                  "id": 77,
                  "hour": 7,
                  "minute": 15,
                  "label": "Morning",
                  "isEnabled": true,
                  "repeatDays": ["MONDAY"],
                  "generation": 3,
                  "revision": 2,
                  "nextTriggerTime": 1800000000000
                }
              ],
              "groups": [],
              "manualOverrides": {
                "2026-12-24": "FORCE_HOLIDAY"
              }
            }
        """.trimIndent()
        writeRawJournal(RestoreJournalCoordinator.PHASE_PREPARED, snapshot)

        val outcome = restoreCoordinator.checkAndRecover()
        assertEquals(RecoveryOutcome.PENDING_RETRY, outcome)
        assertTrue("DB rollback alone must NOT delete journal when OS reconcile fails", journalFile.exists())

        val authorityDao = database.scheduleAuthorityDao()
        val issuedDao = database.issuedOccurrenceDao()
        assertEquals(1001L, authorityDao.getMetaById(1)?.restoreEpoch)

        val authority = authorityDao.getAuthorityByAlarmId(77L)
        assertTrue("Authority must be rebuilt as RECONCILING", authority != null && authority.reconcileState == "RECONCILING")
        assertEquals(1001L, authority!!.restoreEpoch)
        assertEquals(2L, authority.scheduleRevision)

        val slot = issuedDao.getSlot(77L)
        assertTrue("IssueSlot must be rebuilt with generationId=3 and null token", slot != null && slot.generationId == 3L && slot.currentIssueToken == null)
    }

    @Test
    fun c2b1_preparedRollbackRetryBumpsEpochAgainAndMintsFreshInstanceToken() = runTest {
        val snapshot = """
            {
              "settings": {},
              "alarms": [
                {
                  "id": 77,
                  "hour": 7,
                  "minute": 15,
                  "label": "Morning",
                  "isEnabled": true,
                  "repeatDays": ["MONDAY"],
                  "generation": 2,
                  "revision": 1,
                  "nextTriggerTime": 1800000000000
                }
              ],
              "groups": [],
              "manualOverrides": {
                "2026-12-24": "FORCE_HOLIDAY"
              }
            }
        """.trimIndent()

        coEvery { alarmScheduler.rescheduleAllInBatches(any(), any()) } throws RuntimeException("Transient scheduler failure")
        writeRawJournal(RestoreJournalCoordinator.PHASE_PREPARED, snapshot)

        val firstOutcome = restoreCoordinator.checkAndRecover()
        assertEquals(RecoveryOutcome.PENDING_RETRY, firstOutcome)
        assertTrue(journalFile.exists())

        val firstAuth = database.scheduleAuthorityDao().getAuthorityByAlarmId(77L)!!
        assertEquals(1001L, firstAuth.restoreEpoch)
        assertEquals("RECONCILING", firstAuth.reconcileState)

        coEvery { alarmScheduler.rescheduleAllInBatches(any(), any()) } returns ScheduleOutcome.BatchResult(1, 0, 0, 0, emptyMap())
        val secondOutcome = restoreCoordinator.checkAndRecover()
        assertEquals(RecoveryOutcome.RESTORED_OLD, secondOutcome)
        assertFalse(journalFile.exists())

        val secondAuth = database.scheduleAuthorityDao().getAuthorityByAlarmId(77L)!!
        assertEquals(1002L, secondAuth.restoreEpoch)
        assertEquals("RECONCILING", secondAuth.reconcileState)
        assertFalse("Retry rollback must mint a fresh instanceToken", secondAuth.instanceToken == firstAuth.instanceToken)
    }

    @Test
    fun `CE-B01-A - active PHASE_PREPARED journal blocks Normal schedulers and preserves DirectBootAlarmCache`() = runTest {
        org.robolectric.shadows.ShadowAlarmManager.setCanScheduleExactAlarms(true)
        val now = System.currentTimeMillis()
        val futureTrigger = now + 600_000L
        val alarm = Alarm(id = 701L, hour = 7, minute = 0, label = "Fenced Normal", isEnabled = true, nextTriggerTime = futureTrigger)
        database.alarmDao().insert(alarm)

        val alarmRepo = com.sysadmindoc.alarmclock.data.repository.AlarmRepository(database, database.alarmDao(), database.alarmGroupDao())
        val holidayRepo = mockk<com.sysadmindoc.alarmclock.data.repository.HolidayRepository>(relaxed = true)
        val incidentRepo = mockk<com.sysadmindoc.alarmclock.data.repository.AlarmIncidentRepository>(relaxed = true)
        val weatherRepo = mockk<com.sysadmindoc.alarmclock.data.repository.WeatherRepository>(relaxed = true)
        val realScheduler = AlarmScheduler(
            context = context,
            repository = alarmRepo,
            calculator = com.sysadmindoc.alarmclock.domain.NextAlarmCalculator(),
            preferencesManager = preferencesManager,
            holidayRepository = holidayRepo,
            alarmIncidentRepository = incidentRepo,
            weatherRepository = weatherRepo
        )

        com.sysadmindoc.alarmclock.directboot.DirectBootAlarmCache.saveIfEarlier(context, alarm, futureTrigger, now)
        assertNotNull(com.sysadmindoc.alarmclock.directboot.DirectBootAlarmCache.read(context))

        restoreCoordinator.prepareTransaction(AppSettings(), listOf(alarm), emptyList())

        val shadowAm = org.robolectric.Shadows.shadowOf(context.getSystemService(Context.ALARM_SERVICE) as android.app.AlarmManager)
        val initialScheduledCount = shadowAm.scheduledAlarms.size

        val batchResult = realScheduler.rescheduleAllInBatches()
        assertEquals(1, batchResult.blockedCount)
        assertEquals(0, batchResult.armedCount)
        assertNotNull("DirectBootAlarmCache must NOT be cleared while PHASE_PREPARED blocks rescheduleAllInBatches", com.sysadmindoc.alarmclock.directboot.DirectBootAlarmCache.read(context))

        val schedOutcome = realScheduler.schedule(alarm)
        assertTrue(schedOutcome is ScheduleOutcome.NoArm && schedOutcome.reason == "RESTORE_PREPARED_FENCE")
        val snoozeOutcome = realScheduler.scheduleSnooze(alarm, 5)
        assertTrue(snoozeOutcome is ScheduleOutcome.NoArm && snoozeOutcome.reason == "RESTORE_PREPARED_FENCE")
        val reconcileOutcome = realScheduler.reconcileRegistration(alarm, futureTrigger)
        assertTrue(reconcileOutcome is ScheduleOutcome.NoArm && reconcileOutcome.reason == "RESTORE_PREPARED_FENCE")
        val scheduleAtOutcome = realScheduler.scheduleAt(alarm, futureTrigger)
        assertTrue(scheduleAtOutcome is ScheduleOutcome.NoArm && scheduleAtOutcome.reason == "RESTORE_PREPARED_FENCE")

        assertEquals("Zero new OS AlarmManager registrations allowed during PHASE_PREPARED", initialScheduledCount, shadowAm.scheduledAlarms.size)
        com.sysadmindoc.alarmclock.directboot.DirectBootAlarmCache.clear(context)
    }

    @Test
    fun `CE-B01-TOCTOU - scheduleAt and prepareTransaction serialize on recoveryMutex without mid-registration PREPARED race`() = runBlocking {
        org.robolectric.shadows.ShadowAlarmManager.setCanScheduleExactAlarms(true)
        val now = System.currentTimeMillis()
        val futureTrigger = now + 600_000L
        val alarm = Alarm(id = 702L, hour = 8, minute = 0, label = "Race Alarm", isEnabled = true)
        database.alarmDao().insert(alarm)

        val alarmRepo = com.sysadmindoc.alarmclock.data.repository.AlarmRepository(database, database.alarmDao(), database.alarmGroupDao())
        val realScheduler = AlarmScheduler(
            context = context,
            repository = alarmRepo,
            calculator = com.sysadmindoc.alarmclock.domain.NextAlarmCalculator(),
            preferencesManager = preferencesManager,
            holidayRepository = mockk(relaxed = true),
            alarmIncidentRepository = mockk(relaxed = true),
            weatherRepository = mockk(relaxed = true)
        )

        val enteredBarrier = kotlinx.coroutines.CompletableDeferred<Unit>()
        val releaseBarrier = kotlinx.coroutines.CompletableDeferred<Unit>()
        realScheduler.testOnPreOsRegistrationBarrier = { _, _ ->
            enteredBarrier.complete(Unit)
            releaseBarrier.await()
        }

        kotlinx.coroutines.withTimeout(5_000L) {
            val scheduleJob = async(kotlinx.coroutines.Dispatchers.Default) {
                realScheduler.scheduleAt(alarm, futureTrigger)
            }
            enteredBarrier.await()

            val prepareJob = async(kotlinx.coroutines.Dispatchers.Default) {
                restoreCoordinator.prepareTransaction(AppSettings(), listOf(alarm), emptyList())
            }
            kotlinx.coroutines.delay(80L)
            assertFalse("prepareTransaction MUST wait on recoveryMutex until in-flight scheduleAt finishes", prepareJob.isCompleted)
            assertFalse("Journal MUST NOT enter PHASE_PREPARED mid-registration", journalFile.exists())

            releaseBarrier.complete(Unit)
            val outcome = scheduleJob.await()
            prepareJob.await()
            assertTrue(outcome is ScheduleOutcome.Armed)
            assertTrue(journalFile.exists())
        }
    }

    private fun assertNotNull(value: Any?) {
        org.junit.Assert.assertNotNull(value)
    }

    private fun assertNotNull(msg: String, value: Any?) {
        org.junit.Assert.assertNotNull(msg, value)
    }

    @Test
    fun `CE-B01-BAK-FENCE - bak-only PHASE_PREPARED journal blocks scheduleAt and cancel fails closed`() = runBlocking {
        org.robolectric.shadows.ShadowAlarmManager.setCanScheduleExactAlarms(true)
        val now = System.currentTimeMillis()
        val futureTrigger = now + 600_000L
        val alarm = Alarm(id = 703L, hour = 7, minute = 30, label = "Bak Fenced Alarm", isEnabled = true, nextTriggerTime = futureTrigger)
        database.alarmDao().insert(alarm)

        val alarmRepo = com.sysadmindoc.alarmclock.data.repository.AlarmRepository(database, database.alarmDao(), database.alarmGroupDao())
        val realScheduler = AlarmScheduler(
            context = context,
            repository = alarmRepo,
            calculator = com.sysadmindoc.alarmclock.domain.NextAlarmCalculator(),
            preferencesManager = preferencesManager,
            holidayRepository = mockk(relaxed = true),
            alarmIncidentRepository = mockk(relaxed = true),
            weatherRepository = mockk(relaxed = true)
        )

        // Arm an alarm + cache BEFORE restore starts
        assertTrue(realScheduler.scheduleAt(alarm, futureTrigger) is ScheduleOutcome.Armed)
        assertNotNull(com.sysadmindoc.alarmclock.directboot.DirectBootAlarmCache.read(context))

        // Prepare journal, then move main journal to .bak to simulate mid-startWrite() crash
        restoreCoordinator.prepareTransaction(AppSettings(), listOf(alarm), emptyList())
        val bakFile = File(context.filesDir, "${RestoreJournalCoordinator.JOURNAL_FILENAME}.bak")
        bakFile.writeText(journalFile.readText())
        assertTrue(journalFile.delete())

        val schedOutcome = realScheduler.scheduleAt(alarm, futureTrigger + 60_000L)
        assertTrue(
            "scheduleAt MUST fail closed when only .bak holds PHASE_PREPARED",
            schedOutcome is ScheduleOutcome.NoArm && schedOutcome.reason == "RESTORE_PREPARED_FENCE"
        )

        // Sync cancel and cancelScheduledEntriesWithResult MUST fail closed during PHASE_PREPARED (.bak)
        assertFalse(realScheduler.cancelScheduledEntriesWithResult(alarm.id))
        realScheduler.cancel(alarm.id)
        assertNotNull(
            "DirectBootAlarmCache MUST NOT be removed when cancel() is blocked by PHASE_PREPARED",
            com.sysadmindoc.alarmclock.directboot.DirectBootAlarmCache.read(context)
        )
        com.sysadmindoc.alarmclock.directboot.DirectBootAlarmCache.clear(context)
    }

    @Test
    fun `CE-B01-SINGLE-THREAD - sync cancel returns fail-closed without deadlock when recoveryMutex held on same thread`() = runBlocking {
        val singleThreadExecutor = java.util.concurrent.Executors.newSingleThreadExecutor()
        val singleDispatcher = singleThreadExecutor.asCoroutineDispatcher()
        try {
            val alarmRepo = com.sysadmindoc.alarmclock.data.repository.AlarmRepository(database, database.alarmDao(), database.alarmGroupDao())
            val realScheduler = AlarmScheduler(
                context = context,
                repository = alarmRepo,
                calculator = com.sysadmindoc.alarmclock.domain.NextAlarmCalculator(),
                preferencesManager = preferencesManager,
                holidayRepository = mockk(relaxed = true),
                alarmIncidentRepository = mockk(relaxed = true),
                weatherRepository = mockk(relaxed = true)
            )
            val mutex = RestoreJournalCoordinator.recoveryMutexFor(context)

            kotlinx.coroutines.withTimeout(2_000L) {
                kotlinx.coroutines.withContext(singleDispatcher) {
                    mutex.lock()
                    try {
                        // Must NOT deadlock on single thread when mutex is already locked
                        assertFalse(realScheduler.cancelScheduledEntriesWithResult(704L))
                        realScheduler.cancel(704L)
                    } finally {
                        mutex.unlock()
                    }
                }
            }
        } finally {
            singleThreadExecutor.shutdownNow()
        }
    }

    @Test
    fun `CE-B01-LOCK-ORDER - checkAndRecover with real AlarmScheduler completes cleanly under L1 to L2 lock order`() = runBlocking {
        org.robolectric.shadows.ShadowAlarmManager.setCanScheduleExactAlarms(true)
        io.mockk.mockkStatic(androidx.work.WorkManager::class)
        val mockWm = mockk<androidx.work.WorkManager>(relaxed = true)
        every { androidx.work.WorkManager.getInstance(any<Context>()) } returns mockWm
        try {
            val now = System.currentTimeMillis()
            val futureTrigger = now + 600_000L
            val oldAlarm = Alarm(id = 705L, hour = 6, minute = 15, label = "LockOrder Alarm", isEnabled = true, nextTriggerTime = futureTrigger)
            database.alarmDao().insert(oldAlarm)

            val alarmRepo = com.sysadmindoc.alarmclock.data.repository.AlarmRepository(database, database.alarmDao(), database.alarmGroupDao())
            val realScheduler = AlarmScheduler(
                context = context,
                repository = alarmRepo,
                calculator = com.sysadmindoc.alarmclock.domain.NextAlarmCalculator(),
                preferencesManager = preferencesManager,
                holidayRepository = mockk(relaxed = true),
                alarmIncidentRepository = mockk(relaxed = true),
                weatherRepository = mockk(relaxed = true)
            )
            val realRestoreCoordinator = RestoreJournalCoordinator(
                context = context,
                database = database,
                preferencesManager = preferencesManager,
                alarmScheduler = realScheduler,
                moshi = Moshi.Builder().build()
            )
            realRestoreCoordinator.prepareTransaction(AppSettings(), listOf(oldAlarm), emptyList())

            kotlinx.coroutines.withTimeout(5_000L) {
                val outcome = realRestoreCoordinator.checkAndRecover()
                assertEquals(RecoveryOutcome.RESTORED_OLD, outcome)
            }
            assertFalse(journalFile.exists())
            // Sync cancel succeeds normally after recovery completes and journal is deleted
            assertTrue(realScheduler.cancelScheduledEntriesWithResult(oldAlarm.id))
        } finally {
            io.mockk.unmockkStatic(androidx.work.WorkManager::class)
        }
    }

    @Test
    fun `T4 - weatherRepository getWeather network IO runs outside L1 recoveryMutex and revalidates epoch after IO`() = runBlocking {
        org.robolectric.shadows.ShadowAlarmManager.setCanScheduleExactAlarms(true)
        io.mockk.mockkStatic(androidx.work.WorkManager::class)
        val mockWm = mockk<androidx.work.WorkManager>(relaxed = true)
        every { androidx.work.WorkManager.getInstance(any<Context>()) } returns mockWm
        try {
            val now = System.currentTimeMillis()
            val weatherAlarm = Alarm(
                id = 706L,
                hour = 7,
                minute = 30,
                isEnabled = true,
                weatherEarlyMinutes = 15,
                generation = 1,
                revision = 1
            )
            database.alarmDao().insert(weatherAlarm)

            val weatherSettings = AppSettings(
                lastKnownLatitude = 35.68,
                lastKnownLongitude = 139.76,
                showAlarmClockIcon = true
            )
            coEvery { preferencesManager.getCurrentSettings() } returns weatherSettings
            every { preferencesManager.getCachedSettings() } returns weatherSettings

            val weatherEntered = kotlinx.coroutines.CompletableDeferred<Unit>()
            val weatherRelease = kotlinx.coroutines.CompletableDeferred<Unit>()
            val weatherRepo = mockk<com.sysadmindoc.alarmclock.data.repository.WeatherRepository>(relaxed = true)
            every { weatherRepo.getCachedWeather(any(), any()) } returns null
            coEvery { weatherRepo.getWeather(any(), any(), any(), any()) } coAnswers {
                weatherEntered.complete(Unit)
                weatherRelease.await()
                Result.failure(java.io.IOException("simulated slow network"))
            }

            val alarmRepo = com.sysadmindoc.alarmclock.data.repository.AlarmRepository(database, database.alarmDao(), database.alarmGroupDao())
            val realScheduler = AlarmScheduler(
                context = context,
                repository = alarmRepo,
                calculator = com.sysadmindoc.alarmclock.domain.NextAlarmCalculator(),
                preferencesManager = preferencesManager,
                holidayRepository = mockk(relaxed = true),
                alarmIncidentRepository = mockk(relaxed = true),
                weatherRepository = weatherRepo
            )

            val schedJob = launch(kotlinx.coroutines.Dispatchers.Default) {
                realScheduler.schedule(weatherAlarm, requestWidgetUpdate = false)
            }

            try {
                weatherEntered.await()
                val mutex = RestoreJournalCoordinator.recoveryMutexFor(context)
                assertFalse(
                    "T4: recoveryMutex L1 MUST NOT be held while weatherRepository.getWeather network IO is in flight",
                    mutex.isLocked
                )

                // Also verify Restore start can acquire L1 & transition to PHASE_PREPARED immediately,
                // and after weather IO finishes, schedule() fails closed under L1 without arming.
                restoreCoordinator.prepareTransaction(weatherSettings, listOf(weatherAlarm), emptyList())
            } finally {
                weatherRelease.complete(Unit)
                schedJob.join()
            }

            val persistedAfter = alarmRepo.getById(weatherAlarm.id)!!
            assertEquals(
                "T4: schedule() MUST fail closed on PHASE_PREPARED after weather IO without writing nextTriggerTime",
                0L,
                persistedAfter.nextTriggerTime
            )
        } finally {
            io.mockk.unmockkStatic(androidx.work.WorkManager::class)
        }
    }

    @Test
    fun `T1 - normal-state cross-thread L1 contention succeeds without drop for cancelSuspending and cancelScheduledEntriesSuspending`() = runBlocking {
        org.robolectric.shadows.ShadowAlarmManager.setCanScheduleExactAlarms(true)
        io.mockk.mockkStatic(androidx.work.WorkManager::class)
        val mockWm = mockk<androidx.work.WorkManager>(relaxed = true)
        every { androidx.work.WorkManager.getInstance(any<Context>()) } returns mockWm
        try {
            val now = System.currentTimeMillis()
            val futureTrigger = now + 600_000L
            val alarm1 = Alarm(id = 707L, hour = 7, minute = 0, isEnabled = true, nextTriggerTime = futureTrigger)
            val alarm2 = Alarm(id = 708L, hour = 8, minute = 0, isEnabled = true, nextTriggerTime = futureTrigger + 60_000L)
            database.alarmDao().insert(alarm1)
            database.alarmDao().insert(alarm2)

            val alarmRepo = com.sysadmindoc.alarmclock.data.repository.AlarmRepository(database, database.alarmDao(), database.alarmGroupDao())
            val realScheduler = AlarmScheduler(
                context = context,
                repository = alarmRepo,
                calculator = com.sysadmindoc.alarmclock.domain.NextAlarmCalculator(),
                preferencesManager = preferencesManager,
                holidayRepository = mockk(relaxed = true),
                alarmIncidentRepository = mockk(relaxed = true),
                weatherRepository = mockk(relaxed = true)
            )
            assertTrue(realScheduler.scheduleAt(alarm1, futureTrigger, requestWidgetUpdate = false).isArmed)
            assertTrue(realScheduler.scheduleAt(alarm2, futureTrigger + 60_000L, requestWidgetUpdate = false).isArmed)
            assertNotNull("DirectBootAlarmCache must exist before contended cancel", com.sysadmindoc.alarmclock.directboot.DirectBootAlarmCache.read(context))

            val mutex = RestoreJournalCoordinator.recoveryMutexFor(context)
            val lockHeld = kotlinx.coroutines.CompletableDeferred<Unit>()
            val holderJob = launch(kotlinx.coroutines.Dispatchers.Default) {
                mutex.lock()
                try {
                    lockHeld.complete(Unit)
                    kotlinx.coroutines.delay(100L)
                } finally {
                    mutex.unlock()
                }
            }
            lockHeld.await()

            val cancelled1 = realScheduler.cancelSuspending(alarm1.id)
            holderJob.join()
            assertTrue("T1: cancelSuspending MUST wait for normal-state L1 holder and return true", cancelled1)
            assertTrue(
                "T1: DirectBootAlarmCache for alarm1 MUST be cleared after cancelSuspending",
                com.sysadmindoc.alarmclock.directboot.DirectBootAlarmCache.read(context)?.alarmId != alarm1.id
            )

            // Contend L1 again for cancelScheduledEntriesSuspending
            val lockHeld2 = kotlinx.coroutines.CompletableDeferred<Unit>()
            val holderJob2 = launch(kotlinx.coroutines.Dispatchers.Default) {
                mutex.lock()
                try {
                    lockHeld2.complete(Unit)
                    kotlinx.coroutines.delay(100L)
                } finally {
                    mutex.unlock()
                }
            }
            lockHeld2.await()

            val cancelled2 = realScheduler.cancelScheduledEntriesSuspending(alarm2.id, includeFollowUpWorkers = true)
            holderJob2.join()
            assertTrue("T1: cancelScheduledEntriesSuspending MUST wait for normal-state L1 holder and return true", cancelled2)
            assertTrue(
                "T1: DirectBootAlarmCache for alarm2 MUST be cleared after cancelScheduledEntriesSuspending",
                com.sysadmindoc.alarmclock.directboot.DirectBootAlarmCache.read(context) == null
            )
        } finally {
            com.sysadmindoc.alarmclock.directboot.DirectBootAlarmCache.clear(context)
            io.mockk.unmockkStatic(androidx.work.WorkManager::class)
        }
    }

    @Test
    fun `T2 - PHASE_PREPARED and bak and tampered journals fail closed with zero OS cancel across suspending entrypoints`() = runBlocking {
        org.robolectric.shadows.ShadowAlarmManager.setCanScheduleExactAlarms(true)
        io.mockk.mockkStatic(androidx.work.WorkManager::class)
        val mockWm = mockk<androidx.work.WorkManager>(relaxed = true)
        every { androidx.work.WorkManager.getInstance(any<Context>()) } returns mockWm
        try {
            val now = System.currentTimeMillis()
            val futureTrigger = now + 600_000L
            val alarm = Alarm(id = 709L, hour = 7, minute = 45, isEnabled = true, nextTriggerTime = futureTrigger)
            database.alarmDao().insert(alarm)

            val alarmRepo = com.sysadmindoc.alarmclock.data.repository.AlarmRepository(database, database.alarmDao(), database.alarmGroupDao())
            val realScheduler = AlarmScheduler(
                context = context,
                repository = alarmRepo,
                calculator = com.sysadmindoc.alarmclock.domain.NextAlarmCalculator(),
                preferencesManager = preferencesManager,
                holidayRepository = mockk(relaxed = true),
                alarmIncidentRepository = mockk(relaxed = true),
                weatherRepository = mockk(relaxed = true)
            )
            assertTrue(realScheduler.scheduleAt(alarm, futureTrigger, requestWidgetUpdate = false).isArmed)
            assertNotNull("DirectBootAlarmCache must be populated", com.sysadmindoc.alarmclock.directboot.DirectBootAlarmCache.read(context))

            val spyAm = mockk<android.app.AlarmManager>(relaxed = true)
            realScheduler.testAlarmManager = spyAm

            // 1. Valid PHASE_PREPARED journal
            restoreCoordinator.prepareTransaction(AppSettings(), listOf(alarm), emptyList())
            assertFalse("T2(PREPARED): cancelSuspending MUST fail closed", realScheduler.cancelSuspending(alarm.id))
            assertFalse("T2(PREPARED): cancelScheduledEntriesSuspending MUST fail closed", realScheduler.cancelScheduledEntriesSuspending(alarm.id, includeFollowUpWorkers = true))

            // 2. .bak-only PHASE_PREPARED
            val bakFile = File(context.filesDir, "${RestoreJournalCoordinator.JOURNAL_FILENAME}.bak")
            bakFile.writeText(journalFile.readText())
            assertTrue(journalFile.delete())
            assertFalse("T2(.bak): cancelSuspending MUST fail closed", realScheduler.cancelSuspending(alarm.id))
            assertFalse("T2(.bak): cancelScheduledEntriesSuspending MUST fail closed", realScheduler.cancelScheduledEntriesSuspending(alarm.id))

            // 3. Tampered InvalidOrBlocked journal
            bakFile.delete()
            journalFile.writeText("""{"formatVersion":1,"phase":"PREPARED","snapshotJson":"{}","checksum":"bad"}""")
            assertFalse("T2(tampered): cancelSuspending MUST fail closed", realScheduler.cancelSuspending(alarm.id))
            assertFalse("T2(tampered): cancelScheduledEntriesSuspending MUST fail closed", realScheduler.cancelScheduledEntriesSuspending(alarm.id))

            io.mockk.verify(exactly = 0) { spyAm.cancel(any<android.app.PendingIntent>()) }
            assertNotNull("T2: DirectBootAlarmCache MUST stay intact", com.sysadmindoc.alarmclock.directboot.DirectBootAlarmCache.read(context))
        } finally {
            com.sysadmindoc.alarmclock.directboot.DirectBootAlarmCache.clear(context)
            io.mockk.unmockkStatic(androidx.work.WorkManager::class)
        }
    }

    @Test
    fun `T3 - restore prepareTransaction and cancelSuspending serialize strictly on L1 without mid-transition OS cancel`() = runBlocking {
        org.robolectric.shadows.ShadowAlarmManager.setCanScheduleExactAlarms(true)
        io.mockk.mockkStatic(androidx.work.WorkManager::class)
        val mockWm = mockk<androidx.work.WorkManager>(relaxed = true)
        every { androidx.work.WorkManager.getInstance(any<Context>()) } returns mockWm
        try {
            val now = System.currentTimeMillis()
            val futureTrigger = now + 600_000L
            val alarm = Alarm(id = 710L, hour = 8, minute = 15, isEnabled = true, nextTriggerTime = futureTrigger)
            database.alarmDao().insert(alarm)

            val alarmRepo = com.sysadmindoc.alarmclock.data.repository.AlarmRepository(database, database.alarmDao(), database.alarmGroupDao())
            val realScheduler = AlarmScheduler(
                context = context,
                repository = alarmRepo,
                calculator = com.sysadmindoc.alarmclock.domain.NextAlarmCalculator(),
                preferencesManager = preferencesManager,
                holidayRepository = mockk(relaxed = true),
                alarmIncidentRepository = mockk(relaxed = true),
                weatherRepository = mockk(relaxed = true)
            )
            assertTrue(realScheduler.scheduleAt(alarm, futureTrigger, requestWidgetUpdate = false).isArmed)

            val spyAm = mockk<android.app.AlarmManager>(relaxed = true)
            realScheduler.testAlarmManager = spyAm

            // Simulate prepareTransaction acquiring L1 and committing PHASE_PREPARED first:
            val mutex = RestoreJournalCoordinator.recoveryMutexFor(context)
            val prepareEntered = kotlinx.coroutines.CompletableDeferred<Unit>()
            val allowPrepareFinish = kotlinx.coroutines.CompletableDeferred<Unit>()
            val prepareJob = launch(kotlinx.coroutines.Dispatchers.Default) {
                mutex.lock()
                try {
                    prepareEntered.complete(Unit)
                    allowPrepareFinish.await()
                    writeRawJournal(RestoreJournalCoordinator.PHASE_PREPARED, """{"settings":{},"alarms":[],"groups":[]}""")
                } finally {
                    mutex.unlock()
                }
            }
            prepareEntered.await()

            val cancelJob = async(kotlinx.coroutines.Dispatchers.Default) {
                realScheduler.cancelSuspending(alarm.id)
            }
            kotlinx.coroutines.delay(50L)
            assertFalse("T3: cancelSuspending MUST remain suspended on L1 while restore start holds L1", cancelJob.isCompleted)

            allowPrepareFinish.complete(Unit)
            prepareJob.join()
            val cancelled = cancelJob.await()
            assertFalse("T3: once restore committed PHASE_PREPARED under L1, waiting cancelSuspending MUST fail closed", cancelled)
            io.mockk.verify(exactly = 0) { spyAm.cancel(any<android.app.PendingIntent>()) }
        } finally {
            io.mockk.unmockkStatic(androidx.work.WorkManager::class)
        }
    }

    @Test
    fun `T4b - rescheduleAllInBatches prefetches weatherRepository getWeather outside L1 recoveryMutex`() = runBlocking {
        org.robolectric.shadows.ShadowAlarmManager.setCanScheduleExactAlarms(true)
        io.mockk.mockkStatic(androidx.work.WorkManager::class)
        val mockWm = mockk<androidx.work.WorkManager>(relaxed = true)
        every { androidx.work.WorkManager.getInstance(any<Context>()) } returns mockWm
        try {
            val weatherAlarm = Alarm(
                id = 711L,
                hour = 7,
                minute = 10,
                isEnabled = true,
                weatherEarlyMinutes = 15,
                generation = 1,
                revision = 1
            )
            database.alarmDao().insert(weatherAlarm)

            val weatherSettings = AppSettings(
                lastKnownLatitude = 35.68,
                lastKnownLongitude = 139.76,
                showAlarmClockIcon = true
            )
            coEvery { preferencesManager.getCurrentSettings() } returns weatherSettings
            every { preferencesManager.getCachedSettings() } returns weatherSettings

            val weatherEntered = kotlinx.coroutines.CompletableDeferred<Unit>()
            val weatherRelease = kotlinx.coroutines.CompletableDeferred<Unit>()
            val weatherRepo = mockk<com.sysadmindoc.alarmclock.data.repository.WeatherRepository>(relaxed = true)
            every { weatherRepo.getCachedWeather(any(), any()) } returns null
            coEvery { weatherRepo.getWeather(any(), any(), any(), any()) } coAnswers {
                weatherEntered.complete(Unit)
                weatherRelease.await()
                Result.failure(java.io.IOException("simulated slow network"))
            }

            val alarmRepo = com.sysadmindoc.alarmclock.data.repository.AlarmRepository(database, database.alarmDao(), database.alarmGroupDao())
            val realScheduler = AlarmScheduler(
                context = context,
                repository = alarmRepo,
                calculator = com.sysadmindoc.alarmclock.domain.NextAlarmCalculator(),
                preferencesManager = preferencesManager,
                holidayRepository = mockk(relaxed = true),
                alarmIncidentRepository = mockk(relaxed = true),
                weatherRepository = weatherRepo
            )

            val batchJob = async(kotlinx.coroutines.Dispatchers.Default) {
                realScheduler.rescheduleAllInBatches(forceRecalculate = true)
            }

            val batchResult = try {
                weatherEntered.await()
                val mutex = RestoreJournalCoordinator.recoveryMutexFor(context)
                assertFalse(
                    "T4b: recoveryMutex L1 MUST NOT be held while rescheduleAllInBatches prefetches weather IO",
                    mutex.isLocked
                )
                restoreCoordinator.prepareTransaction(weatherSettings, listOf(weatherAlarm), emptyList())
                weatherRelease.complete(Unit)
                batchJob.await()
            } finally {
                if (!weatherRelease.isCompleted) weatherRelease.complete(Unit)
            }

            assertEquals("T4b: batch MUST fail closed after PHASE_PREPARED commit", 1, batchResult.blockedCount)
            assertEquals("T4b: zero alarms armed after PHASE_PREPARED", 0, batchResult.armedCount)
        } finally {
            io.mockk.unmockkStatic(androidx.work.WorkManager::class)
        }
    }

    private fun openFileDb(file: File): AlarmDatabase =
        Room.databaseBuilder(context, AlarmDatabase::class.java, file.absolutePath)
            .addCallback(AlarmDatabase.DATABASE_CALLBACK)
            .allowMainThreadQueries()
            .build()

    private fun newFileScheduler(db: AlarmDatabase, calc: com.sysadmindoc.alarmclock.domain.NextAlarmCalculator = com.sysadmindoc.alarmclock.domain.NextAlarmCalculator()): AlarmScheduler =
        AlarmScheduler(
            context = context,
            repository = com.sysadmindoc.alarmclock.data.repository.AlarmRepository(db, db.alarmDao(), db.alarmGroupDao()),
            calculator = calc,
            preferencesManager = preferencesManager,
            holidayRepository = mockk(relaxed = true),
            alarmIncidentRepository = mockk(relaxed = true),
            weatherRepository = mockk(relaxed = true)
        )

    private fun armOsPi(alarmId: Long, triggerTime: Long) {
        val realAm = context.getSystemService(Context.ALARM_SERVICE) as android.app.AlarmManager
        val fireIntent = android.content.Intent(context, com.sysadmindoc.alarmclock.receiver.AlarmReceiver::class.java).apply {
            action = "com.sysadmindoc.alarmclock.ALARM_FIRE"
            putExtra(AlarmScheduler.EXTRA_ALARM_ID, alarmId)
            putExtra(AlarmScheduler.EXTRA_SCHEDULED_AT, triggerTime)
        }
        val pi = android.app.PendingIntent.getBroadcast(
            context,
            alarmId.toInt(),
            fireIntent,
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
        )
        realAm.setAlarmClock(android.app.AlarmManager.AlarmClockInfo(triggerTime, pi), pi)
    }

    @Test
    fun `W3 - stale witness against enabled alarm aborts safely and reports failure when abort settlement fails`() = runBlocking {
        org.robolectric.shadows.ShadowAlarmManager.setCanScheduleExactAlarms(true)
        io.mockk.mockkStatic(androidx.work.WorkManager::class)
        val mockWm = mockk<androidx.work.WorkManager>(relaxed = true)
        every { androidx.work.WorkManager.getInstance(any<Context>()) } returns mockWm
        val dbFile = tempFolder.newFile("closure-w3.db")
        val realAm = context.getSystemService(Context.ALARM_SERVICE) as android.app.AlarmManager
        val shadowAm = org.robolectric.Shadows.shadowOf(realAm)
        val futureTrigger = System.currentTimeMillis() + 600_000L
        val alarmId = 720L
        val witnessToken = "${AlarmScheduler.LEGACY_V1_CANCEL_TOKEN_PREFIX}$alarmId:1:${UUID.randomUUID()}"
        try {
            val db1 = openFileDb(dbFile)
            try {
                db1.alarmDao().insert(
                    Alarm(id = alarmId, hour = 7, minute = 0, isEnabled = true, generation = 1, revision = 1, nextTriggerTime = futureTrigger)
                )
                val currentEpoch = db1.scheduleAuthorityDao().getMetaById(1)!!.restoreEpoch
                db1.durableOperationIntentDao().insertValidatedIntent(
                    com.sysadmindoc.alarmclock.data.local.entity.DurableOperationIntent(
                        operationToken = witnessToken,
                        alarmId = alarmId,
                        operationEpoch = 100L,
                        incarnationId = "legacy-cancel-$alarmId-1",
                        operationKind = "CANCEL",
                        expectedOwnerToken = "gen:1",
                        expectedScheduleRevision = 1L,
                        expectedRestoreEpoch = currentEpoch,
                        phase = com.sysadmindoc.alarmclock.data.local.entity.DurableOperationIntent.PHASE_PENDING,
                        intentRevision = 1L,
                        originalFireId = "legacy-cancel-$alarmId-1",
                        originalScheduledAt = futureTrigger
                    )
                )
                db1.openHelper.writableDatabase.execSQL(
                    "CREATE TRIGGER test_abort_w3 BEFORE UPDATE ON durable_operation_intent BEGIN SELECT RAISE(ABORT, 'W3 abort settlement failure'); END;"
                )
                val sched1 = newFileScheduler(db1)
                val failedBatch = sched1.rescheduleAllInBatches()
                assertTrue("W3: Failing to abort witness MUST increment failedCount", failedBatch.failedCount > 0)
                db1.openHelper.writableDatabase.execSQL("DROP TRIGGER IF EXISTS test_abort_w3;")
            } finally {
                db1.close()
            }

            // Fresh instance after restart settles witness to ABORTED and keeps enabled alarm armed
            val db2 = openFileDb(dbFile)
            try {
                val calc = mockk<com.sysadmindoc.alarmclock.domain.NextAlarmCalculator>(relaxed = true)
                every { calc.calculate(any()) } returns futureTrigger
                every { calc.calculate(any(), any()) } returns futureTrigger
                val sched2 = newFileScheduler(db2, calc)
                val okBatch = sched2.rescheduleAllInBatches()
                assertEquals(0, okBatch.failedCount)
                assertEquals(
                    com.sysadmindoc.alarmclock.data.local.entity.DurableOperationIntent.PHASE_ABORTED,
                    db2.durableOperationIntentDao().getIntentByToken(witnessToken)?.phase
                )
                assertEquals(1, shadowAm.scheduledAlarms.size)
            } finally {
                db2.close()
            }
        } finally {
            io.mockk.unmockkStatic(androidx.work.WorkManager::class)
        }
    }

    @Test
    fun `W7 - restoreEpoch change expires stale Legacy V1 cancel witness without cancelling restored OS PI and reports failure if expiry settlement fails`() = runBlocking {
        org.robolectric.shadows.ShadowAlarmManager.setCanScheduleExactAlarms(true)
        io.mockk.mockkStatic(androidx.work.WorkManager::class)
        val mockWm = mockk<androidx.work.WorkManager>(relaxed = true)
        every { androidx.work.WorkManager.getInstance(any<Context>()) } returns mockWm
        val dbFile = tempFolder.newFile("closure-w7.db")
        val realAm = context.getSystemService(Context.ALARM_SERVICE) as android.app.AlarmManager
        val shadowAm = org.robolectric.Shadows.shadowOf(realAm)
        val futureTrigger = System.currentTimeMillis() + 600_000L
        val alarmId = 721L
        val witnessToken = "${AlarmScheduler.LEGACY_V1_CANCEL_TOKEN_PREFIX}$alarmId:1:${UUID.randomUUID()}"
        try {
            val db1 = openFileDb(dbFile)
            try {
                // Witness recorded under restoreEpoch = 1, then replace-restore bumps restoreEpoch -> 2 and installs disabled/live restored alarm
                val repo1 = com.sysadmindoc.alarmclock.data.repository.AlarmRepository(db1, db1.alarmDao(), db1.alarmGroupDao())
                repo1.restoreAlarmsTransaction(
                    listOf(Alarm(id = alarmId, hour = 7, minute = 0, isEnabled = false, generation = 1, revision = 1, nextTriggerTime = 0L)),
                    isReplace = true
                )
                val restoredEpoch = db1.scheduleAuthorityDao().getMetaById(1)!!.restoreEpoch
                assertTrue(restoredEpoch > 1L)
                db1.durableOperationIntentDao().insertValidatedIntent(
                    com.sysadmindoc.alarmclock.data.local.entity.DurableOperationIntent(
                        operationToken = witnessToken,
                        alarmId = alarmId,
                        operationEpoch = 100L,
                        incarnationId = "legacy-cancel-$alarmId-1",
                        operationKind = "CANCEL",
                        expectedOwnerToken = "gen:1",
                        expectedScheduleRevision = 1L,
                        expectedRestoreEpoch = 1L,
                        phase = com.sysadmindoc.alarmclock.data.local.entity.DurableOperationIntent.PHASE_PENDING,
                        intentRevision = 1L,
                        originalFireId = "legacy-cancel-$alarmId-1",
                        originalScheduledAt = futureTrigger
                    )
                )
                armOsPi(alarmId, futureTrigger)
                db1.openHelper.writableDatabase.execSQL(
                    "CREATE TRIGGER test_abort_w7 BEFORE UPDATE ON durable_operation_intent BEGIN SELECT RAISE(ABORT, 'W7 expire settlement failure'); END;"
                )
                val sched1 = newFileScheduler(db1)
                val failedBatch = sched1.rescheduleAllInBatches()
                assertTrue("W7: Failing to mark witness EXPIRED MUST increment failedCount", failedBatch.failedCount > 0)
                db1.openHelper.writableDatabase.execSQL("DROP TRIGGER IF EXISTS test_abort_w7;")
            } finally {
                db1.close()
            }

            // Re-arm PI representing restored alarm in new epoch & run fresh instance recovery
            armOsPi(alarmId, futureTrigger)
            val db2 = openFileDb(dbFile)
            try {
                val sched2 = newFileScheduler(db2)
                val okBatch = sched2.rescheduleAllInBatches()
                assertEquals(0, okBatch.failedCount)
                assertEquals(
                    com.sysadmindoc.alarmclock.data.local.entity.DurableOperationIntent.PHASE_EXPIRED,
                    db2.durableOperationIntentDao().getIntentByToken(witnessToken)?.phase
                )
                assertEquals("W7: Restored new-epoch OS PI MUST NOT be cancelled by expired witness", 1, shadowAm.scheduledAlarms.size)
            } finally {
                db2.close()
            }
        } finally {
            io.mockk.unmockkStatic(androidx.work.WorkManager::class)
        }
    }

    @Test
    fun `W8 - missing DB row with persisted Legacy V1 cancel witness fails closed across fresh process instance without cancelling OS`() = runBlocking {
        org.robolectric.shadows.ShadowAlarmManager.setCanScheduleExactAlarms(true)
        io.mockk.mockkStatic(androidx.work.WorkManager::class)
        val mockWm = mockk<androidx.work.WorkManager>(relaxed = true)
        every { androidx.work.WorkManager.getInstance(any<Context>()) } returns mockWm
        val dbFile = tempFolder.newFile("closure-w8.db")
        val realAm = context.getSystemService(Context.ALARM_SERVICE) as android.app.AlarmManager
        val shadowAm = org.robolectric.Shadows.shadowOf(realAm)
        val futureTrigger = System.currentTimeMillis() + 600_000L
        val alarmId = 722L
        val witnessToken = "${AlarmScheduler.LEGACY_V1_CANCEL_TOKEN_PREFIX}$alarmId:1:${UUID.randomUUID()}"
        try {
            val db1 = openFileDb(dbFile)
            try {
                db1.durableOperationIntentDao().insertValidatedIntent(
                    com.sysadmindoc.alarmclock.data.local.entity.DurableOperationIntent(
                        operationToken = witnessToken,
                        alarmId = alarmId,
                        operationEpoch = 100L,
                        incarnationId = "legacy-cancel-$alarmId-1",
                        operationKind = "CANCEL",
                        expectedOwnerToken = "gen:1",
                        expectedScheduleRevision = 1L,
                        expectedRestoreEpoch = 1L,
                        phase = com.sysadmindoc.alarmclock.data.local.entity.DurableOperationIntent.PHASE_PENDING,
                        intentRevision = 1L,
                        originalFireId = "legacy-cancel-$alarmId-1",
                        originalScheduledAt = futureTrigger
                    )
                )
                armOsPi(alarmId, futureTrigger)
            } finally {
                db1.close()
            }

            val db2 = openFileDb(dbFile)
            try {
                val sched2 = newFileScheduler(db2)
                val batch = sched2.rescheduleAllInBatches()
                assertTrue("W8: Missing DB row MUST fail closed (failedCount > 0)", batch.failedCount > 0)
                assertEquals("W8: OS PI MUST remain armed when DB row is missing", 1, shadowAm.scheduledAlarms.size)
                assertEquals(
                    com.sysadmindoc.alarmclock.data.local.entity.DurableOperationIntent.PHASE_PENDING,
                    db2.durableOperationIntentDao().getIntentByToken(witnessToken)?.phase
                )
            } finally {
                db2.close()
            }
        } finally {
            io.mockk.unmockkStatic(androidx.work.WorkManager::class)
        }
    }

    @Test
    fun `CE-B03 - cleanupStalePreRestoreOccurrences IN_FLIGHT isolation and UNKNOWN PI hold journal without corrupting IN_FLIGHT`() = runBlocking {
        org.robolectric.shadows.ShadowAlarmManager.setCanScheduleExactAlarms(true)
        io.mockk.mockkStatic(androidx.work.WorkManager::class)
        val mockWm = mockk<androidx.work.WorkManager>(relaxed = true)
        every { androidx.work.WorkManager.getInstance(any<Context>()) } returns mockWm
        try {
            val owner = com.sysadmindoc.alarmclock.domain.ScheduleAuthorityOwner(
                authorityDao = database.scheduleAuthorityDao(),
                mainSlotApplier = com.sysadmindoc.alarmclock.domain.MainSlotApplier { true },
                exactPermissionChecker = { true }
            )
            val alarmRepo = com.sysadmindoc.alarmclock.data.repository.AlarmRepository(database, database.alarmDao(), database.alarmGroupDao())
            val realScheduler = AlarmScheduler(
                context = context,
                repository = alarmRepo,
                calculator = com.sysadmindoc.alarmclock.domain.NextAlarmCalculator(),
                preferencesManager = preferencesManager,
                holidayRepository = mockk(relaxed = true),
                alarmIncidentRepository = mockk(relaxed = true),
                weatherRepository = mockk(relaxed = true),
                scheduleAuthorityOwnerProvider = javax.inject.Provider<com.sysadmindoc.alarmclock.domain.ScheduleAuthorityOwner> { owner }
            )
            val realCoordinator = RestoreJournalCoordinator(
                context = context,
                database = database,
                preferencesManager = preferencesManager,
                alarmScheduler = realScheduler,
                moshi = Moshi.Builder().build(),
                manualHolidayOverrideStore = overrideStore,
                holidayReconcileCoordinator = coordinator
            )

            val futureAt = System.currentTimeMillis() + 600_000L
            val staleIssueToken = UUID.randomUUID().toString()
            database.issuedOccurrenceDao().insertOccurrence(
                com.sysadmindoc.alarmclock.data.local.entity.IssuedOccurrence(
                    issueToken = staleIssueToken,
                    alarmId = 730L,
                    scheduledAt = futureAt,
                    fireId = "fire-730",
                    generationId = 1L,
                    status = com.sysadmindoc.alarmclock.data.local.entity.IssuedOccurrence.STATUS_ISSUED,
                    restoreEpoch = 999L
                )
            )
            val inFlightRev2Intent = com.sysadmindoc.alarmclock.data.local.entity.DurableOperationIntent(
                operationToken = "rev2-inflight-730",
                alarmId = 730L,
                operationEpoch = 100L,
                incarnationId = "inc-730",
                operationKind = "CANCEL",
                expectedOwnerToken = "auth-730",
                expectedScheduleRevision = 1L,
                expectedRestoreEpoch = 999L,
                phase = com.sysadmindoc.alarmclock.data.local.entity.DurableOperationIntent.PHASE_IN_FLIGHT,
                intentRevision = 2L,
                originalFireId = "fire-730",
                originalScheduledAt = futureAt
            )
            database.durableOperationIntentDao().insertValidatedIntent(inFlightRev2Intent)

            realCoordinator.prepareTransaction(AppSettings(), emptyList(), emptyList())
            realCoordinator.markCommitted()

            // Case A: IN_FLIGHT Rev2 intent isolates stale occurrence -> MUST hold journal and NOT mutate IN_FLIGHT
            val isolatedOutcome = realCoordinator.checkAndRecover()
            assertEquals(RecoveryOutcome.PENDING_RETRY, isolatedOutcome)
            assertTrue("Journal MUST be retained when cleanupStalePreRestoreOccurrences isolates IN_FLIGHT Rev2", journalFile.exists())
            assertEquals(
                com.sysadmindoc.alarmclock.data.local.entity.DurableOperationIntent.PHASE_IN_FLIGHT,
                database.durableOperationIntentDao().getIntentByToken("rev2-inflight-730")?.phase
            )
            assertEquals(
                com.sysadmindoc.alarmclock.data.local.entity.IssuedOccurrence.STATUS_ISSUED,
                database.issuedOccurrenceDao().getOccurrence(staleIssueToken)?.status
            )

            // Case B: Clear IN_FLIGHT intent, but OS tokenized PI is missing (UNKNOWN -> preservedUnknownCount=1) -> MUST still hold journal!
            database.durableOperationIntentDao().updateIntentPhase(
                "rev2-inflight-730",
                com.sysadmindoc.alarmclock.data.local.entity.DurableOperationIntent.PHASE_COMPLETED
            )
            val unknownOutcome = realCoordinator.checkAndRecover()
            assertEquals(RecoveryOutcome.PENDING_RETRY, unknownOutcome)
            assertTrue("Journal MUST be retained when tokenized OS PI is UNKNOWN (preservedUnknownCount > 0)", journalFile.exists())

            // Case C: Register matching tokenized PI on OS -> cleanup cancels PI, revokes occurrence, and deletes journal!
            val tokenizedPi = realScheduler.createTokenizedPendingIntent(730L, futureAt, "fire-730", staleIssueToken, 999L)
            val realAm = context.getSystemService(Context.ALARM_SERVICE) as android.app.AlarmManager
            realAm.setAlarmClock(android.app.AlarmManager.AlarmClockInfo(futureAt, tokenizedPi), tokenizedPi)

            val okOutcome = realCoordinator.checkAndRecover()
            assertEquals(RecoveryOutcome.RECOVERED_NEW, okOutcome)
            assertFalse("Journal must be deleted once stale pre-restore occurrence is cancelled and revoked", journalFile.exists())
            assertEquals(
                com.sysadmindoc.alarmclock.data.local.entity.IssuedOccurrence.STATUS_REVOKED,
                database.issuedOccurrenceDao().getOccurrence(staleIssueToken)?.status
            )
            org.junit.Assert.assertNull("Tokenized OS PI must be cancelled", realScheduler.lookupTokenizedPendingIntentForCancel(730L, staleIssueToken))
        } finally {
            com.sysadmindoc.alarmclock.directboot.DirectBootAlarmCache.clear(context)
            io.mockk.unmockkStatic(androidx.work.WorkManager::class)
        }
    }

    @Test
    fun `CE-B02-B - PHASE_COMMITTED OLD cleanup cancels removed alarm but does not wipe NEW re-armed same-ID PI`() = runBlocking {
        org.robolectric.shadows.ShadowAlarmManager.setCanScheduleExactAlarms(true)
        io.mockk.mockkStatic(androidx.work.WorkManager::class)
        val mockWm = mockk<androidx.work.WorkManager>(relaxed = true)
        every { androidx.work.WorkManager.getInstance(any<Context>()) } returns mockWm
        try {
            val now = System.currentTimeMillis()
            val tOld = now + 600_000L
            val newBase = Alarm(id = 777L, hour = 9, minute = 30, label = "New 777", isEnabled = true, generation = 2, revision = 1)
            val tNew = com.sysadmindoc.alarmclock.domain.NextAlarmCalculator().calculate(newBase)
            val oldSameId = Alarm(id = 777L, hour = 7, minute = 0, label = "Old 777", isEnabled = true, nextTriggerTime = tOld)
            val oldRemoved = Alarm(id = 778L, hour = 8, minute = 0, label = "Old Removed 778", isEnabled = true, nextTriggerTime = tOld + 60_000L)
            val newSameId = newBase.copy(nextTriggerTime = tNew)

            val alarmRepo = com.sysadmindoc.alarmclock.data.repository.AlarmRepository(database, database.alarmDao(), database.alarmGroupDao())
            val realScheduler = AlarmScheduler(
                context = context,
                repository = alarmRepo,
                calculator = com.sysadmindoc.alarmclock.domain.NextAlarmCalculator(),
                preferencesManager = preferencesManager,
                holidayRepository = mockk(relaxed = true),
                alarmIncidentRepository = mockk(relaxed = true),
                weatherRepository = mockk(relaxed = true)
            )
            val spyScheduler = io.mockk.spyk(realScheduler)

            val realCoordinator = RestoreJournalCoordinator(
                context = context,
                database = database,
                preferencesManager = preferencesManager,
                alarmScheduler = spyScheduler,
                moshi = Moshi.Builder().build(),
                manualHolidayOverrideStore = overrideStore,
                holidayReconcileCoordinator = coordinator
            )

            // Snapshot captures OLD 777 (enabled) and OLD 778 (enabled)
            realCoordinator.prepareTransaction(AppSettings(), listOf(oldSameId, oldRemoved), emptyList())

            // PREPARED fence MUST remain active before markCommitted()
            assertTrue(
                "PREPARED fence must return NoArm for scheduleAt before markCommitted()",
                realScheduler.scheduleAt(newSameId, tNew, requestWidgetUpdate = false) is ScheduleOutcome.NoArm
            )

            // Simulate Replace commit keeping enabled NEW 777 (with tNew) and removing 778; 778 still has stale OS PI
            database.alarmDao().deleteAll()
            database.alarmDao().insert(newSameId)
            armOsPi(778L, tOld + 60_000L)
            realCoordinator.markCommitted()

            // Arm NEW 777 PI after markCommitted() (authorized PHASE_COMMITTED)
            assertTrue(realScheduler.scheduleAt(newSameId, tNew, requestWidgetUpdate = false).isArmed)

            val shadowAm = org.robolectric.Shadows.shadowOf(context.getSystemService(Context.ALARM_SERVICE) as android.app.AlarmManager)
            assertEquals(2, shadowAm.scheduledAlarms.size)

            val outcome = realCoordinator.checkAndRecover()
            assertEquals(RecoveryOutcome.RECOVERED_NEW, outcome)
            assertFalse(journalFile.exists())

            // OLD 778 MUST be cancelled; enabled NEW 777 MUST NOT be blindly cancelled before rescheduleAllInBatches
            io.mockk.coVerify(exactly = 1) {
                spyScheduler.cancelScheduledEntriesSuspending(
                    alarmId = 778L,
                    includeFollowUpWorkers = true,
                    expectedRestoreEpoch = 1000L
                )
            }
            io.mockk.coVerify(exactly = 0) {
                spyScheduler.cancelScheduledEntriesSuspending(
                    alarmId = 777L,
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                    any()
                )
            }
            assertEquals("Only NEW 777 PI must remain armed", 1, shadowAm.scheduledAlarms.size)
            val remaining = shadowAm.scheduledAlarms.single()
            assertEquals(777, org.robolectric.Shadows.shadowOf(remaining.operation).requestCode)
            assertEquals(tNew, remaining.triggerAtTime)
        } finally {
            com.sysadmindoc.alarmclock.directboot.DirectBootAlarmCache.clear(context)
            io.mockk.unmockkStatic(androidx.work.WorkManager::class)
        }
    }

    @Test
    fun `CE-B13-H - HolidayReconcileResult PendingRetry holds PHASE_COMMITTED journal until reconcile succeeds`() = runTest {
        coEvery { coordinator.reconcile(any(), any(), any(), any()) } returns HolidayReconcileResult.PendingRetry(
            pendingCount = 1,
            failureCause = null
        )

        val snapshot = """
            {
              "settings": {},
              "alarms": [],
              "groups": [],
              "manualOverrides": {
                "2026-12-24": "FORCE_HOLIDAY"
              }
            }
        """.trimIndent()
        writeRawJournal(RestoreJournalCoordinator.PHASE_COMMITTED, snapshot)

        val held = restoreCoordinator.checkAndRecover()
        assertEquals(RecoveryOutcome.PENDING_RETRY, held)
        assertTrue("PHASE_COMMITTED journal must be retained while Holiday reconcile returns PendingRetry", journalFile.exists())

        coEvery { coordinator.reconcile(any(), any(), any(), any()) } returns HolidayReconcileResult.Success(
            reconciledCount = 1,
            isNoOp = false
        )
        val ok = restoreCoordinator.checkAndRecover()
        assertEquals(RecoveryOutcome.RECOVERED_NEW, ok)
        assertFalse("Journal must be deleted after Holiday reconcile succeeds", journalFile.exists())
    }
}
