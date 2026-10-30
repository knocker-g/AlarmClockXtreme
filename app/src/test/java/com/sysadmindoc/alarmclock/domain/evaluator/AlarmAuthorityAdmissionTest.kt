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
import com.sysadmindoc.alarmclock.data.local.entity.ScheduleAuthority
import com.sysadmindoc.alarmclock.data.local.entity.ScheduleAuthorityMeta
import com.sysadmindoc.alarmclock.data.model.Alarm
import com.sysadmindoc.alarmclock.data.repository.AlarmRepository
import com.sysadmindoc.alarmclock.data.repository.StaleAlarmException
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
 * Safety Corrected Test Suite for KNO-30 ScheduleAuthority Admission & Transaction Truthfulness Boundaries.
 * Verifies that new Alarms saved via [AlarmRepository.save] are admitted with a valid V35 [ScheduleAuthority]
 * and [IssueSlot] in an atomic Room transaction that rolls back completely if metadata is missing.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class AlarmAuthorityAdmissionTest {

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

    private val nov040700Ms = LocalDate.of(2026, 11, 4)
        .atTime(LocalTime.of(7, 0))
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
    fun `1 New Normal Alarm creation via AlarmRepository save admits valid Authority and enables Skip Next`() = runBlocking {
        // Save new alarm through current production AlarmRepository.save() API
        val newAlarm = Alarm(
            hour = 7,
            minute = 0,
            isEnabled = true,
            scheduleKind = Alarm.SCHEDULE_KIND_REPEATING,
            repeatDays = setOf(DayOfWeek.WEDNESDAY),
            nextTriggerTime = wed0700Ms
        )
        val savedId = alarmRepo.save(newAlarm)

        // Verify alarm row exists in DB
        val savedAlarm = alarmDao.getById(savedId)
        assertNotNull("Saved alarm MUST exist in alarms table", savedAlarm)

        // Verify ScheduleAuthority is admitted and initialized in Room DB
        val authority = authorityDao.getAuthorityByAlarmId(savedId)
        assertNotNull("ScheduleAuthority MUST be created when a new Alarm is saved via AlarmRepository.save()", authority)
        assertEquals(savedId, authority!!.alarmId)
        assertTrue("Authority instanceToken MUST be non-blank", authority.instanceToken.isNotBlank())
        assertEquals(1L, authority.scheduleRevision)
        assertEquals("LIVE", authority.lifecycle)

        // Verify IssueSlot is initialized in Room DB
        val slot = issuedDao.getSlot(savedId)
        assertNotNull("IssueSlot MUST be initialized for new Alarm", slot)

        // Attempt Skip Next claim via DurableSkipClaimAdapter
        val skipResult = adapter.executeSkipClaimAndReconcile(
            alarmId = savedId,
            expectedScheduledAt = wed0700Ms,
            nowEpochMs = nowEpochMs,
            evaluationZoneId = zoneId
        )

        // Verify Skip Next succeeds cleanly on the newly admitted Alarm!
        assertEquals("Skip Next MUST succeed for newly saved Alarm with valid Authority",
            DurableSkipClaimAdapterOutcome.SUCCESS_COMMITTED, skipResult.outcome)
        assertNotNull("Updated alarm MUST exist after Skip claim", skipResult.updatedAlarm)
        assertEquals("Next trigger MUST advance after Skip claim", nov040700Ms, skipResult.updatedAlarm!!.nextTriggerTime)
    }

    @Test
    fun `2 Missing Authority Rejection`() = runBlocking {
        val alarm = Alarm(
            id = 2L,
            hour = 7,
            minute = 0,
            isEnabled = true,
            scheduleKind = Alarm.SCHEDULE_KIND_REPEATING,
            repeatDays = setOf(DayOfWeek.WEDNESDAY),
            nextTriggerTime = wed0700Ms
        )
        alarmDao.insert(alarm)

        val result = adapter.executeSkipClaimAndReconcile(
            alarmId = 2L,
            expectedScheduledAt = wed0700Ms,
            nowEpochMs = nowEpochMs,
            evaluationZoneId = zoneId
        )

        assertEquals(DurableSkipClaimAdapterOutcome.UNSUPPORTED_SCHEDULE, result.outcome)
        assertNull(result.updatedAlarm)

        val exceptions = exceptionDao.getExceptionsForAlarmGeneration(2L, alarm.generation)
        assertTrue("No exceptions MUST be created when authority is missing", exceptions.isEmpty())
    }

    @Test
    fun `3 Blank or Corrupt Authority Rejection`() = runBlocking {
        val alarm = Alarm(
            id = 3L,
            hour = 7,
            minute = 0,
            isEnabled = true,
            scheduleKind = Alarm.SCHEDULE_KIND_REPEATING,
            repeatDays = setOf(DayOfWeek.WEDNESDAY),
            nextTriggerTime = wed0700Ms
        )
        alarmDao.insert(alarm)

        // Insert corrupt ScheduleAuthority with blank instanceToken
        val corruptAuthority = ScheduleAuthority(
            alarmId = 3L,
            instanceToken = "", // Blank token!
            definitionRevision = 1L,
            scheduleRevision = 1L,
            lifecycle = "LIVE",
            desiredOperation = "SET",
            desiredTriggerMillis = wed0700Ms,
            reconcileState = "SETTLED",
            restoreEpoch = 100L,
            actionKey = null
        )
        authorityDao.insertAuthority(corruptAuthority)

        val result = adapter.executeSkipClaimAndReconcile(
            alarmId = 3L,
            expectedScheduledAt = wed0700Ms,
            nowEpochMs = nowEpochMs,
            evaluationZoneId = zoneId
        )

        assertEquals("Blank instanceToken authority MUST fail closed",
            DurableSkipClaimAdapterOutcome.UNSUPPORTED_SCHEDULE, result.outcome)
        assertNull(result.updatedAlarm)
    }

    @Test
    fun `4 Stale Generation or Revision Rejection`() = runBlocking {
        val alarm = Alarm(
            id = 4L,
            hour = 7,
            minute = 0,
            isEnabled = true,
            scheduleKind = Alarm.SCHEDULE_KIND_REPEATING,
            repeatDays = setOf(DayOfWeek.WEDNESDAY),
            generation = 2, // Alarm generation = 2
            nextTriggerTime = nov040700Ms
        )
        alarmDao.insert(alarm)

        val authority = ScheduleAuthority(
            alarmId = 4L,
            instanceToken = "auth-token-4",
            definitionRevision = 1L,
            scheduleRevision = 1L,
            lifecycle = "LIVE",
            desiredOperation = "SET",
            desiredTriggerMillis = nov040700Ms,
            reconcileState = "SETTLED",
            restoreEpoch = 100L,
            actionKey = null
        )
        authorityDao.insertAuthority(authority)

        // Attempt skip targeting old generation 1 trigger time
        val result = adapter.executeSkipClaimAndReconcile(
            alarmId = 4L,
            expectedScheduledAt = wed0700Ms, // Old generation 1 trigger time!
            nowEpochMs = nowEpochMs,
            evaluationZoneId = zoneId
        )

        assertEquals("Stale generation/occurrence skip MUST be rejected",
            DurableSkipClaimAdapterOutcome.STALE_OCCURRENCE, result.outcome)
    }

    @Test
    fun `5 Old Instance Token Rejection`() = runBlocking {
        val alarm = Alarm(
            id = 5L,
            hour = 7,
            minute = 0,
            isEnabled = true,
            scheduleKind = Alarm.SCHEDULE_KIND_REPEATING,
            repeatDays = setOf(DayOfWeek.WEDNESDAY),
            generation = 1,
            nextTriggerTime = wed0700Ms
        )
        alarmDao.insert(alarm)

        // DB has authority with instanceToken = "auth-token-new"
        val authority = ScheduleAuthority(
            alarmId = 5L,
            instanceToken = "auth-token-new",
            definitionRevision = 1L,
            scheduleRevision = 1L,
            lifecycle = "LIVE",
            desiredOperation = "SET",
            desiredTriggerMillis = wed0700Ms,
            reconcileState = "SETTLED",
            restoreEpoch = 100L,
            actionKey = null
        )
        authorityDao.insertAuthority(authority)
        issuedDao.insertSlotInitial(IssueSlot(alarmId = 5L, generationId = 1L, currentIssueToken = null))

        // Action snapshot carries mismatched old instanceToken = "auth-token-old"
        val oldFingerprint = ScheduleAuthorityFingerprint(
            alarmId = 5L,
            instanceToken = "auth-token-old", // Mismatched old token!
            restoreEpoch = 100L,
            scheduleRevision = 1L,
            definitionRevision = 1L
        )

        val coordinator = DurableSkipClaimCoordinator(database = db)
        val actionSnapshot = CurrentNextActionSnapshot(
            alarmId = 5L,
            expectedScheduledTargetEpochMs = wed0700Ms,
            expectedBaseTriggerTimeEpochMs = wed0700Ms,
            expectedGeneration = 1,
            authorityFingerprint = oldFingerprint
        )

        val claimResult = coordinator.claimDurableSkip(
            actionSnapshot = actionSnapshot,
            nowEpochMs = nowEpochMs,
            evaluationZoneId = zoneId
        )

        assertEquals("Mismatched instanceToken MUST be rejected as STALE_OCCURRENCE",
            DurableSkipClaimOutcome.STALE_OCCURRENCE, claimResult.outcome)
    }

    @Test
    fun `6 ID reuse for deleted alarm throws StaleAlarmException and rejects authority creation`() = runBlocking {
        // Attempt updating an alarm with explicit non-zero ID (id = 99L) that does NOT exist in DB
        val reuseAlarm = Alarm(
            id = 99L,
            hour = 7,
            minute = 0,
            isEnabled = true,
            scheduleKind = Alarm.SCHEDULE_KIND_REPEATING,
            repeatDays = setOf(DayOfWeek.WEDNESDAY),
            nextTriggerTime = wed0700Ms
        )

        var thrown = false
        try {
            alarmRepo.update(reuseAlarm)
        } catch (e: StaleAlarmException) {
            thrown = true
        }

        assertTrue("Update on deleted non-zero ID MUST throw StaleAlarmException", thrown)

        // Verify ScheduleAuthority was NOT created for non-existent ID
        val authority = authorityDao.getAuthorityByAlarmId(99L)
        assertNull("ScheduleAuthority MUST NOT be fabricated for non-existent alarm ID", authority)
    }

    @Test
    fun `7 Uninitialized ScheduleAuthorityMeta causes complete atomic rollback on new alarm save`() = runBlocking {
        // Delete ScheduleAuthorityMeta (id = 1) from DB
        db.compileStatement("DELETE FROM schedule_authority_meta").executeUpdateDelete()

        val newAlarm = Alarm(
            hour = 7,
            minute = 0,
            isEnabled = true,
            scheduleKind = Alarm.SCHEDULE_KIND_REPEATING,
            repeatDays = setOf(DayOfWeek.WEDNESDAY),
            nextTriggerTime = wed0700Ms
        )

        var thrown = false
        try {
            alarmRepo.save(newAlarm)
        } catch (e: IllegalStateException) {
            thrown = true
        }

        assertTrue("Save when ScheduleAuthorityMeta is null MUST throw IllegalStateException to trigger rollback", thrown)

        // Verify COMPLETE ATOMIC ROLLBACK: Alarm, ScheduleAuthority, and IssueSlot MUST ALL be null / empty in DB!
        val allAlarms = alarmDao.getAll()
        assertTrue("Alarms table MUST remain completely empty after transaction rollback", allAlarms.isEmpty())

        val authorities = authorityDao.getAllAuthorities()
        assertTrue("ScheduleAuthority table MUST remain completely empty after transaction rollback", authorities.isEmpty())

        val slot = issuedDao.getSlot(1L)
        assertNull("IssueSlot MUST be null after transaction rollback", slot)

        val meta = authorityDao.getMetaById(1)
        assertNull("ScheduleAuthorityMeta (id=1) MUST NOT be auto-generated", meta)
    }

    @Test
    fun `8 Existing alarm update with missing authority does NOT fabricate authority`() = runBlocking {
        // Insert an alarm directly into alarmDao WITHOUT ScheduleAuthority
        val alarm = Alarm(
            id = 8L,
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

        // Update alarm via AlarmRepository.update()
        alarmRepo.update(alarm.copy(label = "Updated Label"))

        // Verify ScheduleAuthority remains NULL (no illegal fabrication on update)
        val authority = authorityDao.getAuthorityByAlarmId(8L)
        assertNull("Existing alarm update MUST NOT fabricate missing ScheduleAuthority", authority)
    }

    @Test
    fun `9 In-DB ScheduleAuthority reconcileState truthfulness under LEGACY_ONLY`() = runBlocking {
        val newAlarm = Alarm(
            hour = 7,
            minute = 0,
            isEnabled = true,
            scheduleKind = Alarm.SCHEDULE_KIND_REPEATING,
            repeatDays = setOf(DayOfWeek.WEDNESDAY),
            nextTriggerTime = wed0700Ms
        )
        val savedId = alarmRepo.save(newAlarm)

        val authority = authorityDao.getAuthorityByAlarmId(savedId)
        assertNotNull(authority)
        assertEquals("LIVE", authority!!.lifecycle)
        assertEquals("SET", authority.desiredOperation)
        assertEquals(wed0700Ms, authority.desiredTriggerMillis)
        assertEquals("SETTLED", authority.reconcileState)
    }
}
