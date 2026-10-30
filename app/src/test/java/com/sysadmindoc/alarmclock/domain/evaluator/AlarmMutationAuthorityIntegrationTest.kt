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
import com.sysadmindoc.alarmclock.data.local.entity.ScheduleAuthorityMeta
import com.sysadmindoc.alarmclock.data.model.Alarm
import com.sysadmindoc.alarmclock.data.repository.AlarmRepository
import com.sysadmindoc.alarmclock.data.repository.StaleAlarmException
import com.sysadmindoc.alarmclock.domain.NextAlarmCalculator
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
 * Safety Corrected Integration Test Suite for KNO-30 P2 Normal Alarm Mutation & CAS Fenced Authority Integration.
 * Verifies that alarm edits, toggles, and group bulk operations use CAS fenced queries, transition reconcileState to RECONCILING, and roll back on stale tokens/revisions.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class AlarmMutationAuthorityIntegrationTest {

    private lateinit var context: Context
    private lateinit var db: AlarmDatabase
    private lateinit var alarmDao: AlarmDao
    private lateinit var exceptionDao: ScheduleExceptionDao
    private lateinit var authorityDao: ScheduleAuthorityDao
    private lateinit var issuedDao: IssuedOccurrenceDao
    private lateinit var alarmRepo: AlarmRepository

    private lateinit var adapter: DurableSkipClaimAdapter

    private val zoneId = ZoneId.of("America/New_York")
    private val nowEpochMs = LocalDate.of(2026, 10, 28)
        .atTime(LocalTime.of(6, 0))
        .atZone(zoneId)
        .toInstant()
        .toEpochMilli()

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

    private val nov040700Ms = LocalDate.of(2026, 11, 4)
        .atTime(LocalTime.of(7, 0))
        .atZone(zoneId)
        .toInstant()
        .toEpochMilli()

    private val nov040800Ms = LocalDate.of(2026, 11, 4)
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
    fun `1 Existing Normal Alarm schedule edit advances ScheduleAuthority scheduleRevision and sets RECONCILING`() = runBlocking {
        val initialAlarm = Alarm(
            hour = 7,
            minute = 0,
            isEnabled = true,
            scheduleKind = Alarm.SCHEDULE_KIND_REPEATING,
            repeatDays = setOf(DayOfWeek.WEDNESDAY),
            nextTriggerTime = wed0700Ms
        )
        val savedId = alarmRepo.save(initialAlarm)

        val initialAuth = authorityDao.getAuthorityByAlarmId(savedId)
        assertNotNull(initialAuth)
        assertEquals(1L, initialAuth!!.scheduleRevision)
        assertEquals("SETTLED", initialAuth.reconcileState)

        // Edit alarm schedule (07:00 AM -> 08:00 AM)
        val savedAlarm = alarmDao.getById(savedId)!!
        val editedAlarm = savedAlarm.copy(hour = 8, nextTriggerTime = wed0800Ms)
        alarmRepo.update(editedAlarm)

        val updatedAlarm = alarmDao.getById(savedId)!!
        assertEquals(2, updatedAlarm.generation)

        val updatedAuth = authorityDao.getAuthorityByAlarmId(savedId)!!
        assertEquals("scheduleRevision MUST increment on schedule-affecting edit", 2L, updatedAuth.scheduleRevision)
        assertEquals(wed0800Ms, updatedAuth.desiredTriggerMillis)
        assertEquals("reconcileState MUST transition to RECONCILING on schedule edit", "RECONCILING", updatedAuth.reconcileState)

        // Verify Skip Next succeeds on the new 08:00 AM target time
        val skipResult = adapter.executeSkipClaimAndReconcile(
            alarmId = savedId,
            expectedScheduledAt = wed0800Ms,
            nowEpochMs = nowEpochMs,
            evaluationZoneId = zoneId
        )

        assertEquals(DurableSkipClaimAdapterOutcome.SUCCESS_COMMITTED, skipResult.outcome)
        assertEquals(nov040800Ms, skipResult.updatedAlarm!!.nextTriggerTime)
    }

    @Test
    fun `2 Existing Normal Alarm non-schedule edit updates definitionRevision while preserving scheduleRevision and reconcileState`() = runBlocking {
        val initialAlarm = Alarm(
            hour = 7,
            minute = 0,
            isEnabled = true,
            scheduleKind = Alarm.SCHEDULE_KIND_REPEATING,
            repeatDays = setOf(DayOfWeek.WEDNESDAY),
            nextTriggerTime = wed0700Ms
        )
        val savedId = alarmRepo.save(initialAlarm)
        val initialAuth = authorityDao.getAuthorityByAlarmId(savedId)!!

        // Non-schedule secondary edit (label change)
        val savedAlarm = alarmDao.getById(savedId)!!
        alarmRepo.update(savedAlarm.copy(label = "Updated Label"))

        val updatedAuth = authorityDao.getAuthorityByAlarmId(savedId)!!
        assertEquals("scheduleRevision MUST be preserved on secondary non-schedule edit", initialAuth.scheduleRevision, updatedAuth.scheduleRevision)
        assertNotEquals("definitionRevision MUST update on label change", initialAuth.definitionRevision, updatedAuth.definitionRevision)
        assertEquals("reconcileState MUST be preserved on non-schedule edit", "SETTLED", updatedAuth.reconcileState)
    }

    @Test
    fun `3 Alarm toggle OFF updates ScheduleAuthority lifecycle to DISABLED, desiredOperation to CANCEL, and reconcileState to RECONCILING`() = runBlocking {
        val initialAlarm = Alarm(
            hour = 7,
            minute = 0,
            isEnabled = true,
            scheduleKind = Alarm.SCHEDULE_KIND_REPEATING,
            repeatDays = setOf(DayOfWeek.WEDNESDAY),
            nextTriggerTime = wed0700Ms
        )
        val savedId = alarmRepo.save(initialAlarm)

        // Toggle OFF
        alarmRepo.setEnabled(savedId, enabled = false, nextTrigger = 0L)

        val disabledAuth = authorityDao.getAuthorityByAlarmId(savedId)!!
        assertEquals("DISABLED", disabledAuth.lifecycle)
        assertEquals("CANCEL", disabledAuth.desiredOperation)
        assertNull(disabledAuth.desiredTriggerMillis)
        assertEquals(2L, disabledAuth.scheduleRevision)
        assertEquals("RECONCILING", disabledAuth.reconcileState)
    }

    @Test
    fun `4 Alarm toggle ON updates ScheduleAuthority lifecycle to LIVE, desiredOperation to SET, and reconcileState to RECONCILING`() = runBlocking {
        val initialAlarm = Alarm(
            hour = 7,
            minute = 0,
            isEnabled = false,
            scheduleKind = Alarm.SCHEDULE_KIND_REPEATING,
            repeatDays = setOf(DayOfWeek.WEDNESDAY),
            nextTriggerTime = 0L
        )
        val savedId = alarmRepo.save(initialAlarm)

        // Toggle ON
        alarmRepo.setEnabled(savedId, enabled = true, nextTrigger = wed0700Ms)

        val enabledAuth = authorityDao.getAuthorityByAlarmId(savedId)!!
        assertEquals("LIVE", enabledAuth.lifecycle)
        assertEquals("SET", enabledAuth.desiredOperation)
        assertEquals(wed0700Ms, enabledAuth.desiredTriggerMillis)
        assertEquals(2L, enabledAuth.scheduleRevision)
        assertEquals("RECONCILING", enabledAuth.reconcileState)
    }

    @Test
    fun `5 Group bulk toggle updates all member ScheduleAuthority rows to RECONCILING in a single atomic transaction`() = runBlocking {
        val alarm1 = Alarm(hour = 7, minute = 0, isEnabled = true, group = "Work", scheduleKind = Alarm.SCHEDULE_KIND_REPEATING, repeatDays = setOf(DayOfWeek.WEDNESDAY), nextTriggerTime = wed0700Ms)
        val alarm2 = Alarm(hour = 8, minute = 0, isEnabled = true, group = "Work", scheduleKind = Alarm.SCHEDULE_KIND_REPEATING, repeatDays = setOf(DayOfWeek.WEDNESDAY), nextTriggerTime = wed0800Ms)

        val id1 = alarmRepo.save(alarm1)
        val id2 = alarmRepo.save(alarm2)

        // Group Bulk Toggle OFF
        alarmRepo.updateAlarmsEnabledTransaction(listOf(id1, id2), enabled = false) { 0L }

        val auth1 = authorityDao.getAuthorityByAlarmId(id1)!!
        val auth2 = authorityDao.getAuthorityByAlarmId(id2)!!

        assertEquals("DISABLED", auth1.lifecycle)
        assertEquals("CANCEL", auth1.desiredOperation)
        assertEquals("RECONCILING", auth1.reconcileState)
        assertEquals("DISABLED", auth2.lifecycle)
        assertEquals("CANCEL", auth2.desiredOperation)
        assertEquals("RECONCILING", auth2.reconcileState)
        assertEquals(2L, auth1.scheduleRevision)
        assertEquals(2L, auth2.scheduleRevision)
    }

    @Test
    fun `6 Existing alarm update without Authority does NOT fabricate missing Authority`() = runBlocking {
        val alarm = Alarm(
            id = 6L,
            hour = 7,
            minute = 0,
            isEnabled = true,
            scheduleKind = Alarm.SCHEDULE_KIND_REPEATING,
            repeatDays = setOf(DayOfWeek.WEDNESDAY),
            generation = 1,
            revision = 1,
            nextTriggerTime = wed0700Ms
        )
        alarmDao.insert(alarm)

        // Update existing alarm without ScheduleAuthority in DB
        alarmRepo.update(alarm.copy(label = "Updated Label"))

        val authority = authorityDao.getAuthorityByAlarmId(6L)
        assertNull("ScheduleAuthority MUST NOT be fabricated for existing alarms lacking authority", authority)
    }

    @Test
    fun `7 Mismatched scheduleRevision or instanceToken CAS failure returns zero affected rows`() = runBlocking {
        val initialAlarm = Alarm(
            hour = 7,
            minute = 0,
            isEnabled = true,
            scheduleKind = Alarm.SCHEDULE_KIND_REPEATING,
            repeatDays = setOf(DayOfWeek.WEDNESDAY),
            nextTriggerTime = wed0700Ms
        )
        val savedId = alarmRepo.save(initialAlarm)
        val initialAuth = authorityDao.getAuthorityByAlarmId(savedId)!!

        // Attempt CAS update passing mismatched expectedRevision = 999L
        val rows = authorityDao.casUpdateScheduleAuthority(
            alarmId = savedId,
            instanceToken = initialAuth.instanceToken,
            restoreEpoch = initialAuth.restoreEpoch,
            expectedRevision = 999L, // Mismatched expected revision!
            newDefinitionRevision = 2L,
            newLifecycle = "LIVE",
            desiredOperation = "SET",
            desiredTriggerMillis = wed0800Ms
        )

        assertEquals("CAS update with mismatched expectedRevision MUST return 0 affected rows", 0, rows)
    }

    @Test
    fun `8 Holiday policy edit triggers scheduleRevision advancement and CAS update`() = runBlocking {
        val initialAlarm = Alarm(
            hour = 7,
            minute = 0,
            isEnabled = true,
            scheduleKind = Alarm.SCHEDULE_KIND_REPEATING,
            repeatDays = setOf(DayOfWeek.WEDNESDAY),
            skipOnHolidays = false,
            nextTriggerTime = wed0700Ms
        )
        val savedId = alarmRepo.save(initialAlarm)
        val initialAuth = authorityDao.getAuthorityByAlarmId(savedId)!!

        // Toggle skipOnHolidays = true (schedule-affecting edit!)
        val savedAlarm = alarmDao.getById(savedId)!!
        alarmRepo.update(savedAlarm.copy(skipOnHolidays = true))

        val updatedAuth = authorityDao.getAuthorityByAlarmId(savedId)!!
        assertEquals("scheduleRevision MUST increment when skipOnHolidays policy is changed", initialAuth.scheduleRevision + 1L, updatedAuth.scheduleRevision)
        assertEquals("RECONCILING", updatedAuth.reconcileState)
    }

    @Test
    fun `9 Toggle with stale expectedRevision returns zero affected rows`() = runBlocking {
        val initialAlarm = Alarm(
            hour = 7,
            minute = 0,
            isEnabled = true,
            scheduleKind = Alarm.SCHEDULE_KIND_REPEATING,
            repeatDays = setOf(DayOfWeek.WEDNESDAY),
            nextTriggerTime = wed0700Ms
        )
        val savedId = alarmRepo.save(initialAlarm)
        val initialAuth = authorityDao.getAuthorityByAlarmId(savedId)!!

        // Attempt CAS toggle passing stale expectedRevision = 999L
        val rows = authorityDao.casUpdateToggleAuthority(
            alarmId = savedId,
            instanceToken = initialAuth.instanceToken,
            restoreEpoch = initialAuth.restoreEpoch,
            expectedRevision = 999L, // Stale expected revision!
            newLifecycle = "DISABLED",
            desiredOperation = "CANCEL",
            desiredTriggerMillis = null
        )

        assertEquals("CAS toggle with stale expectedRevision MUST return 0 affected rows", 0, rows)
    }

    @Test
    fun `10 Sequential toggles increment scheduleRevision monotonically`() = runBlocking {
        val initialAlarm = Alarm(
            hour = 7,
            minute = 0,
            isEnabled = true,
            scheduleKind = Alarm.SCHEDULE_KIND_REPEATING,
            repeatDays = setOf(DayOfWeek.WEDNESDAY),
            nextTriggerTime = wed0700Ms
        )
        val savedId = alarmRepo.save(initialAlarm)

        // Toggle OFF (rev 1 -> 2)
        alarmRepo.setEnabled(savedId, enabled = false, nextTrigger = 0L)
        val auth1 = authorityDao.getAuthorityByAlarmId(savedId)!!
        assertEquals(2L, auth1.scheduleRevision)
        assertEquals("RECONCILING", auth1.reconcileState)

        // Toggle ON (rev 2 -> 3)
        alarmRepo.setEnabled(savedId, enabled = true, nextTrigger = wed0700Ms)
        val auth2 = authorityDao.getAuthorityByAlarmId(savedId)!!
        assertEquals(3L, auth2.scheduleRevision)
        assertEquals("RECONCILING", auth2.reconcileState)
    }
}
