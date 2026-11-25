package com.sysadmindoc.alarmclock.domain.evaluator

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.sysadmindoc.alarmclock.data.local.AlarmDao
import com.sysadmindoc.alarmclock.data.local.AlarmDatabase
import com.sysadmindoc.alarmclock.data.local.DurableOperationIntentDao
import com.sysadmindoc.alarmclock.data.local.IssuedOccurrenceDao
import com.sysadmindoc.alarmclock.data.local.ScheduleAuthorityDao
import com.sysadmindoc.alarmclock.data.local.ScheduleExceptionDao
import com.sysadmindoc.alarmclock.data.local.entity.DurableOperationIntent
import com.sysadmindoc.alarmclock.data.local.entity.IssueSlot
import com.sysadmindoc.alarmclock.data.local.entity.IssuedOccurrence
import com.sysadmindoc.alarmclock.data.local.entity.ScheduleAuthority
import com.sysadmindoc.alarmclock.data.local.entity.ScheduleAuthorityMeta
import com.sysadmindoc.alarmclock.data.model.Alarm
import com.sysadmindoc.alarmclock.data.repository.AlarmRepository
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/**
 * Integration Test Suite for KNO-30 P3 Backup Restore & External Intent Authority Integration.
 * Verifies that Backup Restore and External Intents (SET_ALARM / DISMISS_ALARM) synchronize V35 [ScheduleAuthority]
 * and [IssueSlot] in Room DB with strict fail-closed Meta checks and RECONCILING state.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class RestoreExternalIntentIntegrationTest {

    private lateinit var context: Context
    private lateinit var db: AlarmDatabase
    private lateinit var alarmDao: AlarmDao
    private lateinit var exceptionDao: ScheduleExceptionDao
    private lateinit var authorityDao: ScheduleAuthorityDao
    private lateinit var issuedDao: IssuedOccurrenceDao
    private lateinit var intentDao: DurableOperationIntentDao
    private lateinit var alarmRepo: AlarmRepository

    private lateinit var adapter: DurableSkipClaimAdapter

    private val zoneId = ZoneId.of("America/New_York")
    private val wed0700Ms = LocalDate.of(2026, 10, 28)
        .atTime(LocalTime.of(7, 0))
        .atZone(zoneId)
        .toInstant()
        .toEpochMilli()

    private val wed0800Ms = LocalDate.of(2026, 10, 28)
        .atTime(LocalTime.of(8, 0))
        .atZone(zoneId)
        .toInstant()
        .toEpochMilli()

    @Before
    fun setUp() = runBlocking {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, AlarmDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        alarmDao = db.alarmDao()
        exceptionDao = db.scheduleExceptionDao()
        authorityDao = db.scheduleAuthorityDao()
        issuedDao = db.issuedOccurrenceDao()
        intentDao = db.durableOperationIntentDao()

        // Seed verified ScheduleAuthorityMeta (restoreEpoch = 100)
        authorityDao.insertMeta(ScheduleAuthorityMeta(id = 1, restoreEpoch = 100L, bootstrapState = "INITIALIZED"))

        alarmRepo = AlarmRepository(
            database = db,
            dao = alarmDao,
            groupDao = db.alarmGroupDao()
        )

        adapter = DurableSkipClaimAdapter(database = db)
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `1 Backup Restore creates ScheduleAuthority and IssueSlot for restored Normal Alarms`() = runBlocking {
        val restoredAlarm1 = Alarm(
            hour = 7,
            minute = 0,
            isEnabled = true,
            scheduleKind = Alarm.SCHEDULE_KIND_REPEATING,
            repeatDays = setOf(DayOfWeek.WEDNESDAY),
            nextTriggerTime = wed0700Ms
        )
        val restoredAlarm2 = Alarm(
            hour = 8,
            minute = 0,
            isEnabled = true,
            scheduleKind = Alarm.SCHEDULE_KIND_REPEATING,
            repeatDays = setOf(DayOfWeek.WEDNESDAY),
            nextTriggerTime = wed0800Ms
        )

        val restoredIds = alarmRepo.restoreAlarmsTransaction(listOf(restoredAlarm1, restoredAlarm2), isReplace = true)

        assertEquals(2, restoredIds.size)
        val id1 = restoredIds[0]
        val id2 = restoredIds[1]

        val auth1 = authorityDao.getAuthorityByAlarmId(id1)
        val auth2 = authorityDao.getAuthorityByAlarmId(id2)

        assertNotNull("ScheduleAuthority MUST be created for restored Alarm 1", auth1)
        assertNotNull("ScheduleAuthority MUST be created for restored Alarm 2", auth2)

        assertEquals(101L, authorityDao.getMetaById(1)?.restoreEpoch)
        assertEquals(101L, auth1!!.restoreEpoch)
        assertEquals(101L, auth2!!.restoreEpoch)
        assertEquals("RECONCILING", auth1.reconcileState)
        assertEquals("RECONCILING", auth2.reconcileState)
        assertTrue("Authority instanceToken MUST be non-blank", auth1.instanceToken.isNotBlank())

        val slot1 = issuedDao.getSlot(id1)
        val slot2 = issuedDao.getSlot(id2)
        assertNotNull("IssueSlot MUST be created for restored Alarm 1", slot1)
        assertNotNull("IssueSlot MUST be created for restored Alarm 2", slot2)
    }

    @Test
    fun `2 Backup Restore skips Quick Alarms and admits Authority only for Normal Alarms`() = runBlocking {
        val normalAlarm = Alarm(
            hour = 7,
            minute = 0,
            isEnabled = true,
            scheduleKind = Alarm.SCHEDULE_KIND_REPEATING,
            repeatDays = setOf(DayOfWeek.WEDNESDAY),
            nextTriggerTime = wed0700Ms
        )
        val quickAlarm = Alarm(
            kind = Alarm.KIND_QUICK,
            isEnabled = true,
            scheduleKind = Alarm.SCHEDULE_KIND_ONE_TIME,
            nextTriggerTime = wed0800Ms,
            quickTriggerEpochMs = wed0800Ms
        )

        val restoredIds = alarmRepo.restoreAlarmsTransaction(listOf(normalAlarm, quickAlarm), isReplace = true)

        val normalId = restoredIds[0]
        val quickId = restoredIds[1]

        val normalAuth = authorityDao.getAuthorityByAlarmId(normalId)
        val quickAuth = authorityDao.getAuthorityByAlarmId(quickId)

        assertNotNull("ScheduleAuthority MUST be created for restored Normal Alarm", normalAuth)
        assertNull("ScheduleAuthority MUST NOT be created for Quick Alarm", quickAuth)
    }

    @Test
    fun `3 importDisabledAtomically admits ScheduleAuthority with DISABLED lifecycle and CANCEL operation`() = runBlocking {
        val disabledAlarm = Alarm(
            hour = 7,
            minute = 0,
            isEnabled = true,
            scheduleKind = Alarm.SCHEDULE_KIND_REPEATING,
            repeatDays = setOf(DayOfWeek.WEDNESDAY),
            nextTriggerTime = wed0700Ms
        )

        val importedIds = alarmRepo.importDisabledAtomically(listOf(disabledAlarm))

        assertEquals(1, importedIds.size)
        val importedId = importedIds[0]

        val auth = authorityDao.getAuthorityByAlarmId(importedId)
        assertNotNull("ScheduleAuthority MUST be created for imported disabled Alarm", auth)
        assertEquals("DISABLED", auth!!.lifecycle)
        assertEquals("CANCEL", auth.desiredOperation)
        assertEquals("RECONCILING", auth.reconcileState)
        assertNull(auth.desiredTriggerMillis)
    }

    @Test
    fun `4 Restore when ScheduleAuthorityMeta is null throws IllegalStateException fail closed`() = runBlocking {
        db.compileStatement("DELETE FROM schedule_authority_meta").executeUpdateDelete()

        val restoredAlarm = Alarm(
            hour = 7,
            minute = 0,
            isEnabled = true,
            scheduleKind = Alarm.SCHEDULE_KIND_REPEATING,
            repeatDays = setOf(DayOfWeek.WEDNESDAY),
            nextTriggerTime = wed0700Ms
        )

        var thrown = false
        try {
            alarmRepo.restoreAlarmsTransaction(listOf(restoredAlarm), isReplace = true)
        } catch (e: IllegalStateException) {
            thrown = true
        }

        assertTrue("Restore when ScheduleAuthorityMeta is missing MUST throw IllegalStateException to trigger rollback", thrown)

        val allAlarms = alarmDao.getAll()
        assertTrue("Alarms table MUST remain completely empty after restore transaction rollback", allAlarms.isEmpty())

        val authorities = authorityDao.getAllAuthorities()
        assertTrue("ScheduleAuthority table MUST remain completely empty after restore transaction rollback", authorities.isEmpty())
    }

    @Test
    fun `5 Restored ScheduleAuthority carries reconcileState RECONCILING pending OS alignment`() = runBlocking {
        val restoredAlarm = Alarm(
            hour = 7,
            minute = 0,
            isEnabled = true,
            scheduleKind = Alarm.SCHEDULE_KIND_REPEATING,
            repeatDays = setOf(DayOfWeek.WEDNESDAY),
            nextTriggerTime = wed0700Ms
        )

        val restoredIds = alarmRepo.restoreAlarmsTransaction(listOf(restoredAlarm), isReplace = true)
        val auth = authorityDao.getAuthorityByAlarmId(restoredIds[0])!!

        assertEquals("RECONCILING", auth.reconcileState)
    }

    @Test
    fun `6 Restore REPLACE same alarmId creates clean IssueSlot with null currentIssueToken`() = runBlocking {
        // Pre-insert stale IssueSlot for alarmId = 42L
        issuedDao.insertSlotInitial(IssueSlot(alarmId = 42L, currentIssueToken = "stale-issue-token-42", generationId = 5L))

        val restoredAlarm = Alarm(
            id = 42L,
            hour = 7,
            minute = 0,
            isEnabled = true,
            scheduleKind = Alarm.SCHEDULE_KIND_REPEATING,
            repeatDays = setOf(DayOfWeek.WEDNESDAY),
            nextTriggerTime = wed0700Ms
        )

        val restoredIds = alarmRepo.restoreAlarmsTransaction(listOf(restoredAlarm), isReplace = true)
        assertEquals(1, restoredIds.size)

        val slot = issuedDao.getSlot(42L)
        assertNotNull("IssueSlot MUST exist for restored alarm 42L", slot)
        assertNull("Stale currentIssueToken MUST be cleared on restore REPLACE", slot!!.currentIssueToken)
        assertEquals(1L, slot.generationId)
    }

    @Test
    fun `7 C2-RED-01 Replace bumps restoreEpoch 100 to 101 and Append preserves existing alarm epoch`() = runBlocking {
        val initialAlarm = Alarm(
            id = 21L,
            hour = 7,
            minute = 0,
            isEnabled = true,
            scheduleKind = Alarm.SCHEDULE_KIND_REPEATING,
            repeatDays = setOf(DayOfWeek.WEDNESDAY),
            nextTriggerTime = wed0700Ms
        )
        val replaceIds = alarmRepo.restoreAlarmsTransaction(listOf(initialAlarm), isReplace = true)
        assertEquals(listOf(21L), replaceIds)
        assertEquals(101L, authorityDao.getMetaById(1)?.restoreEpoch)

        val existingAuthAfterReplace = authorityDao.getAuthorityByAlarmId(21L)!!
        assertEquals(101L, existingAuthAfterReplace.restoreEpoch)

        val appendedAlarm = Alarm(
            id = 21L,
            hour = 8,
            minute = 0,
            isEnabled = true,
            scheduleKind = Alarm.SCHEDULE_KIND_REPEATING,
            repeatDays = setOf(DayOfWeek.WEDNESDAY),
            nextTriggerTime = wed0800Ms
        )
        val appendIds = alarmRepo.restoreAlarmsTransaction(listOf(appendedAlarm), isReplace = false)
        assertEquals(1, appendIds.size)
        val appendedId = appendIds[0]
        assertNotEquals(21L, appendedId)

        assertEquals(101L, authorityDao.getMetaById(1)?.restoreEpoch)
        val existingAuthAfterAppend = authorityDao.getAuthorityByAlarmId(21L)!!
        assertEquals(existingAuthAfterReplace, existingAuthAfterAppend)

        val appendedAuth = authorityDao.getAuthorityByAlarmId(appendedId)!!
        assertEquals(101L, appendedAuth.restoreEpoch)
    }

    @Test
    fun `8 Replace fails closed when restoreEpoch is non-positive or Long MAX_VALUE`() = runBlocking {
        val seedAlarm = Alarm(
            id = 31L,
            hour = 6,
            minute = 30,
            isEnabled = true,
            scheduleKind = Alarm.SCHEDULE_KIND_REPEATING,
            repeatDays = setOf(DayOfWeek.WEDNESDAY),
            nextTriggerTime = wed0700Ms
        )
        alarmRepo.restoreAlarmsTransaction(listOf(seedAlarm), isReplace = true)
        val seededAuth = authorityDao.getAuthorityByAlarmId(31L)!!

        authorityDao.insertMeta(ScheduleAuthorityMeta(id = 1, restoreEpoch = 0L, bootstrapState = "INITIALIZED"))
        var zeroThrown = false
        try {
            alarmRepo.restoreAlarmsTransaction(listOf(seedAlarm.copy(id = 32L)), isReplace = true)
        } catch (_: IllegalStateException) {
            zeroThrown = true
        }
        assertTrue("Non-positive restoreEpoch = 0 MUST fail closed with IllegalStateException", zeroThrown)
        assertEquals(0L, authorityDao.getMetaById(1)?.restoreEpoch)
        assertNotNull(alarmDao.getById(31L))
        assertEquals(seededAuth, authorityDao.getAuthorityByAlarmId(31L))

        authorityDao.insertMeta(ScheduleAuthorityMeta(id = 1, restoreEpoch = Long.MAX_VALUE, bootstrapState = "INITIALIZED"))
        var maxThrown = false
        try {
            alarmRepo.restoreAlarmsTransaction(listOf(seedAlarm.copy(id = 33L)), isReplace = true)
        } catch (_: IllegalStateException) {
            maxThrown = true
        }
        assertTrue("Overflow restoreEpoch = Long.MAX_VALUE MUST fail closed with IllegalStateException", maxThrown)
        assertEquals(Long.MAX_VALUE, authorityDao.getMetaById(1)?.restoreEpoch)
        assertNotNull(alarmDao.getById(31L))
        assertEquals(seededAuth, authorityDao.getAuthorityByAlarmId(31L))
    }

    @Test
    fun `9 Exception during Replace rolls back meta restoreEpoch alarms authorities and slots atomically`() = runBlocking {
        val originalAlarm = Alarm(
            id = 10L,
            hour = 7,
            minute = 0,
            isEnabled = true,
            scheduleKind = Alarm.SCHEDULE_KIND_REPEATING,
            repeatDays = setOf(DayOfWeek.WEDNESDAY),
            nextTriggerTime = wed0700Ms
        )
        alarmDao.insert(originalAlarm)
        val originalAuth = ScheduleAuthority(
            alarmId = 10L,
            instanceToken = "orig-instance-10",
            definitionRevision = 1L,
            scheduleRevision = 2L,
            lifecycle = "LIVE",
            desiredOperation = "SET",
            desiredTriggerMillis = wed0700Ms,
            reconcileState = "SETTLED",
            restoreEpoch = 100L,
            actionKey = "orig-key"
        )
        authorityDao.insertAuthority(originalAuth)
        val originalSlot = IssueSlot(alarmId = 10L, currentIssueToken = "orig-issue-10", generationId = 3L)
        issuedDao.insertSlotInitial(originalSlot)

        val writableDb = db.openHelper.writableDatabase
        writableDb.execSQL(
            """
            CREATE TRIGGER test_fail_alarm_insert
            BEFORE INSERT ON alarms
            BEGIN
                SELECT RAISE(ABORT, 'simulated mid-replace failure');
            END
            """.trimIndent()
        )

        var threw = false
        try {
            alarmRepo.restoreAlarmsTransaction(listOf(originalAlarm.copy(id = 99L)), isReplace = true)
        } catch (_: Exception) {
            threw = true
        } finally {
            writableDb.execSQL("DROP TRIGGER IF EXISTS test_fail_alarm_insert")
        }

        assertTrue("Mid-replace DB failure MUST throw", threw)
        assertEquals("Meta restoreEpoch MUST roll back to 100L", 100L, authorityDao.getMetaById(1)?.restoreEpoch)
        assertNotNull("Original alarm MUST roll back intact", alarmDao.getById(10L))
        assertEquals("Original ScheduleAuthority MUST roll back intact", originalAuth, authorityDao.getAuthorityByAlarmId(10L))
        assertEquals("Original IssueSlot MUST roll back intact", originalSlot, issuedDao.getSlot(10L))
    }

    @Test
    fun `10 Replace preserves IN_FLIGHT Rev2 intent and IssuedOccurrence while issuing fresh instanceToken and keeping importDisabledAtomically epoch unchanged`() = runBlocking {
        val preAlarm = Alarm(
            id = 42L,
            hour = 7,
            minute = 0,
            isEnabled = true,
            generation = 1,
            revision = 1,
            scheduleKind = Alarm.SCHEDULE_KIND_REPEATING,
            repeatDays = setOf(DayOfWeek.WEDNESDAY),
            nextTriggerTime = wed0700Ms
        )
        alarmDao.insert(preAlarm)
        authorityDao.insertAuthority(
            ScheduleAuthority(
                alarmId = 42L,
                instanceToken = "old-instance-42",
                definitionRevision = 1L,
                scheduleRevision = 1L,
                lifecycle = "LIVE",
                desiredOperation = "SET",
                desiredTriggerMillis = wed0700Ms,
                reconcileState = "SETTLED",
                restoreEpoch = 100L,
                actionKey = "old-action"
            )
        )
        issuedDao.insertSlotInitial(IssueSlot(alarmId = 42L, currentIssueToken = "tok-active-old", generationId = 4L))
        issuedDao.insertOccurrence(
            IssuedOccurrence(
                issueToken = "tok-rev2-42",
                alarmId = 42L,
                scheduledAt = wed0700Ms,
                fireId = "fire-rev2-42",
                generationId = 4L,
                status = IssuedOccurrence.STATUS_PREPARED,
                baseTriggerTime = wed0700Ms,
                alarmGeneration = 1,
                authorityInstanceToken = "old-instance-42",
                restoreEpoch = 100L,
                scheduleRevision = 1L,
                definitionRevision = 1L
            )
        )
        intentDao.insertValidatedIntent(
            DurableOperationIntent(
                operationToken = "tok-rev2-42",
                alarmId = 42L,
                operationEpoch = 5000L,
                incarnationId = "fire-rev2-42",
                operationKind = "CANCEL",
                expectedOwnerToken = "old-instance-42",
                expectedScheduleRevision = 1L,
                expectedRestoreEpoch = 100L,
                phase = DurableOperationIntent.PHASE_IN_FLIGHT,
                intentRevision = 2L,
                originalFireId = "fire-rev2-42",
                originalScheduledAt = wed0700Ms
            )
        )

        val restored42 = preAlarm.copy(generation = 2, revision = 1, nextTriggerTime = wed0800Ms)
        val restoredIds = alarmRepo.restoreAlarmsTransaction(listOf(restored42), isReplace = true)
        assertEquals(listOf(42L), restoredIds)

        assertEquals(101L, authorityDao.getMetaById(1)?.restoreEpoch)
        val newAuth = authorityDao.getAuthorityByAlarmId(42L)!!
        assertEquals(101L, newAuth.restoreEpoch)
        assertTrue(newAuth.instanceToken.isNotBlank())
        assertNotEquals("old-instance-42", newAuth.instanceToken)
        assertEquals("RECONCILING", newAuth.reconcileState)

        val newSlot = issuedDao.getSlot(42L)!!
        assertEquals(2L, newSlot.generationId)
        assertNull(newSlot.currentIssueToken)

        val preservedIntent = intentDao.getIntentByToken("tok-rev2-42")!!
        assertEquals(DurableOperationIntent.PHASE_IN_FLIGHT, preservedIntent.phase)
        assertEquals(2L, preservedIntent.intentRevision)
        assertEquals(100L, preservedIntent.expectedRestoreEpoch)

        val preservedOcc = issuedDao.getOccurrence("tok-rev2-42")!!
        assertEquals(IssuedOccurrence.STATUS_PREPARED, preservedOcc.status)
        assertEquals(100L, preservedOcc.restoreEpoch)

        val staleCasRows = authorityDao.casClaimAuthority(
            alarmId = 42L,
            instanceToken = newAuth.instanceToken,
            restoreEpoch = 100L,
            expectedRevision = newAuth.scheduleRevision,
            expectedLifecycle = "LIVE",
            desiredOperation = "SNOOZE",
            desiredTriggerMillis = wed0800Ms + 60_000L,
            actionKey = "stale-epoch-claim"
        )
        assertEquals(0, staleCasRows)

        val disabledIds = alarmRepo.importDisabledAtomically(listOf(preAlarm.copy(id = 0L)))
        assertEquals(1, disabledIds.size)
        assertEquals(101L, authorityDao.getMetaById(1)?.restoreEpoch)
        assertEquals(101L, authorityDao.getAuthorityByAlarmId(disabledIds[0])?.restoreEpoch)
    }
}
