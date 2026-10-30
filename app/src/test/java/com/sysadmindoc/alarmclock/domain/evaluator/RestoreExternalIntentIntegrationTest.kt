package com.sysadmindoc.alarmclock.domain.evaluator

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.sysadmindoc.alarmclock.data.local.AlarmDao
import com.sysadmindoc.alarmclock.data.local.AlarmDatabase
import com.sysadmindoc.alarmclock.data.local.IssuedOccurrenceDao
import com.sysadmindoc.alarmclock.data.local.ScheduleAuthorityDao
import com.sysadmindoc.alarmclock.data.local.ScheduleExceptionDao
import com.sysadmindoc.alarmclock.data.local.entity.IssueSlot
import com.sysadmindoc.alarmclock.data.local.entity.ScheduleAuthorityMeta
import com.sysadmindoc.alarmclock.data.model.Alarm
import com.sysadmindoc.alarmclock.data.repository.AlarmRepository
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
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

        assertEquals(100L, auth1!!.restoreEpoch)
        assertEquals(100L, auth2!!.restoreEpoch)
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
}
