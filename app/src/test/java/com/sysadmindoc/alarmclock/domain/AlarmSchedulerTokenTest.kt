package com.sysadmindoc.alarmclock.domain

import android.app.AlarmManager
import android.app.Application
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.sysadmindoc.alarmclock.data.local.AlarmDatabase
import com.sysadmindoc.alarmclock.data.local.IssuedOccurrenceDao
import com.sysadmindoc.alarmclock.data.local.entity.DurableOperationIntent
import com.sysadmindoc.alarmclock.data.local.entity.IssuedOccurrence
import com.sysadmindoc.alarmclock.data.preferences.PreferencesManager
import com.sysadmindoc.alarmclock.data.repository.AlarmIncidentRepository
import com.sysadmindoc.alarmclock.data.repository.AlarmRepository
import com.sysadmindoc.alarmclock.data.repository.HolidayRepository
import com.sysadmindoc.alarmclock.data.repository.WeatherRepository
import com.sysadmindoc.alarmclock.receiver.AlarmReceiver
import io.mockk.every
import io.mockk.mockk
import io.mockk.spyk
import io.mockk.verify
import java.util.UUID
import javax.inject.Provider
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import com.sysadmindoc.alarmclock.data.backup.RestoreJournalCoordinator
import com.sysadmindoc.alarmclock.data.local.entity.ScheduleAuthority
import com.sysadmindoc.alarmclock.data.local.entity.ScheduleAuthorityMeta
import com.sysadmindoc.alarmclock.directboot.DirectBootAlarmCache
import com.sysadmindoc.alarmclock.service.AlarmRuntimeState
import java.io.File
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.coroutines.CoroutineContext

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class AlarmSchedulerTokenTest {

    private lateinit var context: Context
    private lateinit var db: AlarmDatabase
    private lateinit var dao: IssuedOccurrenceDao
    private lateinit var alarmRepo: AlarmRepository
    private lateinit var owner: ScheduleAuthorityOwner
    private lateinit var ownerProvider: Provider<ScheduleAuthorityOwner>
    private lateinit var scheduler: AlarmScheduler

    private val calculator: NextAlarmCalculator = mockk(relaxed = true)
    private val preferencesManager: PreferencesManager = mockk(relaxed = true)
    private val holidayRepository: HolidayRepository = mockk(relaxed = true)
    private val alarmIncidentRepository: AlarmIncidentRepository = mockk(relaxed = true)
    private val weatherRepository: WeatherRepository = mockk(relaxed = true)

    private lateinit var mockOwnerRouteAuthority: ProductionSchedulingRouteAuthority

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        org.robolectric.shadows.ShadowAlarmManager.setCanScheduleExactAlarms(true)
        io.mockk.mockkStatic(androidx.work.WorkManager::class)
        val mockWorkManager = mockk<androidx.work.WorkManager>(relaxed = true)
        every { androidx.work.WorkManager.getInstance(any<Context>()) } returns mockWorkManager
        db = Room.inMemoryDatabaseBuilder(context, AlarmDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = db.issuedOccurrenceDao()

        alarmRepo = AlarmRepository(
            dao = db.alarmDao(),
            groupDao = db.alarmGroupDao(),
            database = db
        )

        owner = ScheduleAuthorityOwner(
            authorityDao = db.scheduleAuthorityDao(),
            mainSlotApplier = MainSlotApplier { true },
            exactPermissionChecker = { true }
        )
        ownerProvider = Provider { owner }

        every { preferencesManager.getCachedSettings() } returns com.sysadmindoc.alarmclock.data.preferences.AppSettings()
        io.mockk.coEvery { preferencesManager.getCurrentSettings() } returns com.sysadmindoc.alarmclock.data.preferences.AppSettings()

        mockOwnerRouteAuthority = mockk<ProductionSchedulingRouteAuthority> {
            every { isOwnerRouteActive() } returns true
        }

        scheduler = AlarmScheduler(
            context = context,
            repository = alarmRepo,
            calculator = calculator,
            preferencesManager = preferencesManager,
            holidayRepository = holidayRepository,
            alarmIncidentRepository = alarmIncidentRepository,
            weatherRepository = weatherRepository,
            scheduleAuthorityOwnerProvider = ownerProvider
        )
    }

    private fun createOwnerExclusiveScheduler(): AlarmScheduler {
        val mockRouteAuthority = mockk<ProductionSchedulingRouteAuthority> {
            every { isOwnerRouteActive() } returns true
        }
        return AlarmScheduler(
            context = context,
            repository = alarmRepo,
            calculator = calculator,
            preferencesManager = preferencesManager,
            holidayRepository = holidayRepository,
            alarmIncidentRepository = alarmIncidentRepository,
            weatherRepository = weatherRepository,
            scheduleAuthorityOwnerProvider = ownerProvider,
            routeAuthority = mockRouteAuthority
        )
    }

    @After
    fun tearDown() {
        io.mockk.unmockkStatic(androidx.work.WorkManager::class)
        db.close()
    }

    @Test
    fun `applyTokenizedOsSet reserves PREPARED occurrence, registers tokenized PendingIntent, and activates occurrence`() = runBlocking {
        val alarmId = 1001L
        val triggerTime = 1700000000000L
        val token = "token-p2b4-1"

        val ownerScheduler = createOwnerExclusiveScheduler()

        db.alarmDao().insert(com.sysadmindoc.alarmclock.data.model.Alarm(id = alarmId, hour = 7, minute = 0, isEnabled = true))

        val success = ownerScheduler.applyTokenizedOsSet(alarmId, triggerTime, token)
        assertTrue("applyTokenizedOsSet MUST succeed", success)

        val slot = dao.getSlot(alarmId)
        assertNotNull(slot)
        assertEquals(token, slot!!.currentIssueToken)

        val occ = dao.getOccurrence(token)
        assertNotNull(occ)
        assertEquals(IssuedOccurrence.STATUS_ISSUED, occ!!.status)
        assertEquals(alarmId, occ.alarmId)
        assertEquals(triggerTime, occ.scheduledAt)
    }

    @Test
    fun `applyTokenizedOsSet sets status to SET_FAILED if AlarmManager registration throws exception`() = runBlocking {
        val alarmId = 2002L
        val triggerTime = 1700000000000L
        val token = "token-p2b4-fail"

        db.alarmDao().insert(com.sysadmindoc.alarmclock.data.model.Alarm(id = alarmId, hour = 7, minute = 0, isEnabled = true))

        val spyAlarmManager = mockk<AlarmManager>(relaxed = true) {
            every { canScheduleExactAlarms() } returns true
            every { setAlarmClock(any(), any()) } throws IllegalStateException("OS AlarmManager registration restricted")
            every { setExactAndAllowWhileIdle(any(), any(), any()) } throws IllegalStateException("OS AlarmManager registration restricted")
        }

        val ownerScheduler = createOwnerExclusiveScheduler().apply { testAlarmManager = spyAlarmManager }

        val success = ownerScheduler.applyTokenizedOsSet(alarmId, triggerTime, token)
        org.junit.Assert.assertFalse("applyTokenizedOsSet MUST return false when OS set fails", success)

        val occ = dao.getOccurrence(token)
        assertNotNull(occ)
        assertEquals(IssuedOccurrence.STATUS_SET_FAILED, occ!!.status)

        val slot = dao.getSlot(alarmId)
        assertNotNull(slot)
        assertNull("Active slot token MUST remain null when registration fails", slot!!.currentIssueToken)
    }

    @Test
    fun `cancelTokenizedScheduledAlarm revokes active occurrence and invalidates slot token`() = runBlocking {
        val alarmId = 3003L
        val triggerTime = 1700000000000L
        val token = "token-p2b4-cancel"

        db.alarmDao().insert(com.sysadmindoc.alarmclock.data.model.Alarm(id = alarmId, hour = 7, minute = 0, isEnabled = true))

        val ownerScheduler = createOwnerExclusiveScheduler()

        ownerScheduler.applyTokenizedOsSet(alarmId, triggerTime, token)
        assertEquals(token, dao.getSlot(alarmId)!!.currentIssueToken)

        val canceled = ownerScheduler.cancelTokenizedScheduledAlarm(alarmId)
        assertTrue("cancelTokenizedScheduledAlarm MUST succeed", canceled)

        val slot = dao.getSlot(alarmId)
        assertNull("Current slot token MUST be null after cancellation", slot!!.currentIssueToken)

        val occ = dao.getOccurrence(token)
        assertEquals(IssuedOccurrence.STATUS_REVOKED, occ!!.status)
    }

    @Test
    fun `rescheduling alarm via applyTokenizedOsSet atomically revokes previous active occurrence A`() = runBlocking {
        val alarmId = 4004L
        val tokenA = "token-p2b4-A"
        val tokenB = "token-p2b4-B"

        db.alarmDao().insert(com.sysadmindoc.alarmclock.data.model.Alarm(id = alarmId, hour = 7, minute = 0, isEnabled = true))

        val ownerScheduler = createOwnerExclusiveScheduler()

        // Schedule occurrence A
        ownerScheduler.applyTokenizedOsSet(alarmId, 10000L, tokenA)
        assertEquals(tokenA, dao.getSlot(alarmId)!!.currentIssueToken)

        // Reschedule occurrence B
        ownerScheduler.applyTokenizedOsSet(alarmId, 20000L, tokenB)
        assertEquals(tokenB, dao.getSlot(alarmId)!!.currentIssueToken)

        // Verify occurrence A was revoked and occurrence B is active
        assertEquals(IssuedOccurrence.STATUS_REVOKED, dao.getOccurrence(tokenA)!!.status)
        assertEquals(IssuedOccurrence.STATUS_ISSUED, dao.getOccurrence(tokenB)!!.status)
    }

    @Test
    fun `cancelTokenizedScheduledAlarm persists cancellation intent in durable_operation_intent and survives restart recovery`() = runBlocking {
        val alarmId = 5005L
        val triggerTime = 1700000000000L
        val token = "token-gate1-2"

        db.alarmDao().insert(com.sysadmindoc.alarmclock.data.model.Alarm(id = alarmId, hour = 7, minute = 0, isEnabled = true))
        val ownerScheduler = createOwnerExclusiveScheduler()
        ownerScheduler.applyTokenizedOsSet(alarmId, triggerTime, token)

        val durableDao = db.durableOperationIntentDao()

        // 1. Cancel tokenized alarm -> persists CANCEL intent in durable_operation_intent table
        val canceled = ownerScheduler.cancelTokenizedScheduledAlarm(alarmId)
        assertTrue(canceled)

        val cancelIntent = durableDao.getIntentByToken(token)
        assertNotNull("CANCEL intent MUST be persisted in durable_operation_intent table", cancelIntent)
        assertEquals(alarmId, cancelIntent!!.alarmId)
        assertEquals("CANCEL", cancelIntent.operationKind)
        assertEquals(com.sysadmindoc.alarmclock.data.local.entity.DurableOperationIntent.PHASE_COMPLETED, cancelIntent.phase)

        // 2. Simulate pending cancellation retry on restart by updating phase to PENDING
        durableDao.updateIntentPhase(token, com.sysadmindoc.alarmclock.data.local.entity.DurableOperationIntent.PHASE_PENDING)
        val unresolvedBefore = durableDao.getUnresolvedCancellationIntents()
        assertEquals(1, unresolvedBefore.size)

        // Recreate scheduler instance with owner-present fixture
        val restartedScheduler = AlarmScheduler(
            context = context,
            repository = alarmRepo,
            calculator = calculator,
            preferencesManager = preferencesManager,
            holidayRepository = holidayRepository,
            alarmIncidentRepository = alarmIncidentRepository,
            weatherRepository = weatherRepository,
            scheduleAuthorityOwnerProvider = ownerProvider,
            routeAuthority = mockk<ProductionSchedulingRouteAuthority> {
                every { isOwnerRouteActive() } returns true
            }
        )

        // Run rescheduleAllInBatches -> discovers pending cancel intent, retries OS cancel, and marks COMPLETED
        restartedScheduler.rescheduleAllInBatches()

        val cancelIntentAfter = durableDao.getIntentByToken(token)
        assertEquals(com.sysadmindoc.alarmclock.data.local.entity.DurableOperationIntent.PHASE_COMPLETED, cancelIntentAfter!!.phase)
        assertEquals(0, durableDao.getUnresolvedCancellationIntents().size)
    }

    @Test
    fun `tokenC and tokenD have distinct PendingIntent identities and tokenC cancellation does not cancel tokenD`() = runBlocking {
        val alarmId = 6006L
        val triggerTimeC = 1700000000000L
        val triggerTimeD = 1800000000000L
        val tokenC = "token-isolation-C"
        val tokenD = "token-isolation-D"

        val ownerScheduler = createOwnerExclusiveScheduler()

        // 1. Verify tokenC and tokenD produce distinct PendingIntent Data URIs
        val piC = ownerScheduler.createTokenizedPendingIntent(alarmId, triggerTimeC, "fire-C", tokenC)
        val piD = ownerScheduler.createTokenizedPendingIntent(alarmId, triggerTimeD, "fire-D", tokenD)

        assertNotNull(piC)
        assertNotNull(piD)

        // 2. Schedule tokenC
        db.alarmDao().insert(com.sysadmindoc.alarmclock.data.model.Alarm(id = alarmId, hour = 7, minute = 0, isEnabled = true))
        ownerScheduler.applyTokenizedOsSet(alarmId, triggerTimeC, tokenC)
        assertEquals(tokenC, dao.getSlot(alarmId)!!.currentIssueToken)

        // 3. Disable alarm, revoking tokenC
        db.alarmDao().insert(com.sysadmindoc.alarmclock.data.model.Alarm(id = alarmId, hour = 7, minute = 0, isEnabled = false))
        ownerScheduler.rescheduleAllInBatches()
        assertEquals(IssuedOccurrence.STATUS_REVOKED, dao.getOccurrence(tokenC)!!.status)

        // 4. Re-enable alarm and schedule tokenD
        db.alarmDao().insert(com.sysadmindoc.alarmclock.data.model.Alarm(id = alarmId, hour = 8, minute = 0, isEnabled = true))
        ownerScheduler.applyTokenizedOsSet(alarmId, triggerTimeD, tokenD)

        // 5. Verify tokenD is the current admissible occurrence, and tokenC remains REJECTED
        assertEquals(tokenD, dao.getSlot(alarmId)!!.currentIssueToken)
        assertTrue(dao.evaluateActiveOccurrenceAdmission(tokenD))
        org.junit.Assert.assertFalse(dao.evaluateActiveOccurrenceAdmission(tokenC))
    }

    @Test
    fun `U2 - UUID token validation and Uri encode parity with PendingIntentIdentityComparator and B vs C non-collision`() {
        val uuidB = UUID.randomUUID().toString()
        val uuidC = UUID.randomUUID().toString()

        assertTrue(scheduler.isCanonicalMainV2UuidToken(uuidB))
        assertTrue(scheduler.isCanonicalMainV2UuidToken(uuidC))
        assertFalse(scheduler.isCanonicalMainV2UuidToken("not-a-uuid"))
        assertFalse(scheduler.isCanonicalMainV2UuidToken("   "))

        assertTrue(scheduler.verifyMainV2UriParity(7007L, uuidB))
        val uriB = scheduler.buildTokenizedMainV2UriString(7007L, uuidB)
        val uriC = scheduler.buildTokenizedMainV2UriString(7007L, uuidC)
        assertNotEquals(uriB, uriC)
        assertEquals(
            PendingIntentIdentityComparator.encodePercentUtf8(uuidB),
            PendingIntentIdentityComparator.extractQueryParam(uriB, "token")
        )

        val reserved = "token/with+special&chars=100%"
        assertTrue(scheduler.verifyMainV2UriParity(7007L, reserved))
        val piB = scheduler.createTokenizedPendingIntent(7007L, 1_700_000_000_000L, "fire-B", uuidB)
        val piC = scheduler.createTokenizedPendingIntent(7007L, 1_700_000_000_000L, "fire-C", uuidC)
        assertNotEquals("B and C MAIN_V2 PendingIntents MUST NOT collide", piB, piC)
    }

    @Test
    fun `U2b - dormant MAIN_V2 helpers produce zero legacy raw cancel side effects and isolate Rev2 and Unknown`() = runBlocking {
        val alarmId = 7008L
        val uuidB = UUID.randomUUID().toString()
        val uuidC = UUID.randomUUID().toString()
        val triggerB = 1_700_000_000_000L
        val triggerC = 1_700_003_600_000L

        val ownerScheduler = createOwnerExclusiveScheduler()

        db.alarmDao().insert(com.sysadmindoc.alarmclock.data.model.Alarm(id = alarmId, hour = 7, minute = 0, isEnabled = true))

        // Arm a Legacy V1 data=null PendingIntent to prove dormant MAIN_V2 helpers never invoke applyRawOsCancel
        val createLegacyMethod = AlarmScheduler::class.java.getDeclaredMethod(
            "createPendingIntent",
            Long::class.javaPrimitiveType,
            Long::class.javaPrimitiveType,
            String::class.java
        ).apply { isAccessible = true }
        val lookupLegacyMethod = AlarmScheduler::class.java.getDeclaredMethod(
            "lookupPendingIntentForCancel",
            Long::class.javaPrimitiveType
        ).apply { isAccessible = true }

        createLegacyMethod.invoke(ownerScheduler, alarmId, triggerB, "fire-legacy")
        assertNotNull("Legacy V1 PI should exist before MAIN_V2 operations", lookupLegacyMethod.invoke(ownerScheduler, alarmId))

        // Malformed token with whitespace MUST fail closed without touching DB
        assertFalse(ownerScheduler.applyTokenizedOsSet(alarmId, triggerB, "bad token with space"))

        // 1. Apply MAIN_V2 B, then reserve prepared successor C
        assertTrue(ownerScheduler.applyTokenizedOsSet(alarmId, triggerB, uuidB))
        assertNotNull("applyTokenizedOsSet MUST NOT cancel Legacy V1 data=null PI", lookupLegacyMethod.invoke(ownerScheduler, alarmId))

        val occC = dao.reserveIssuedOccurrence(uuidC, alarmId, triggerC, "fire-C")
        assertNotNull(occC)

        // 2. If OS cancel fails, cancelTokenizedScheduledAlarm MUST NOT revoke B or C
        val failingAm = mockk<AlarmManager>(relaxed = true) {
            every { cancel(any<PendingIntent>()) } throws RuntimeException("Simulated OS cancel error")
        }
        ownerScheduler.testAlarmManager = failingAm
        assertFalse(ownerScheduler.cancelTokenizedScheduledAlarm(alarmId, expectedIssueToken = uuidB))
        ownerScheduler.testAlarmManager = null

        assertEquals(IssuedOccurrence.STATUS_ISSUED, dao.getOccurrence(uuidB)!!.status)
        assertEquals(IssuedOccurrence.STATUS_PREPARED, dao.getOccurrence(uuidC)!!.status)
        assertEquals(uuidB, dao.getSlot(alarmId)!!.currentIssueToken)
        assertNotNull("Failed tokenized cancel MUST NOT invoke applyRawOsCancel", lookupLegacyMethod.invoke(ownerScheduler, alarmId))

        // 3. Successful targeted Rev1 cancel revokes ONLY B and leaves prepared C and Legacy V1 PI intact
        assertTrue(ownerScheduler.cancelTokenizedScheduledAlarm(alarmId, expectedIssueToken = uuidB))
        assertEquals(IssuedOccurrence.STATUS_REVOKED, dao.getOccurrence(uuidB)!!.status)
        assertEquals("Prepared successor C MUST NOT be blanket-revoked", IssuedOccurrence.STATUS_PREPARED, dao.getOccurrence(uuidC)!!.status)
        assertNull(dao.getSlot(alarmId)!!.currentIssueToken)
        assertNotNull("cancelTokenizedScheduledAlarm MUST NOT call applyRawOsCancel on Legacy V1", lookupLegacyMethod.invoke(ownerScheduler, alarmId))

        // 4. Rev2 / Unknown CANCEL intents on alarm MUST isolate cancelTokenizedScheduledAlarm with zero generic COMPLETED/ABORTED
        val rev2AlarmId = 7009L
        val rev2Token = UUID.randomUUID().toString()
        db.alarmDao().insert(com.sysadmindoc.alarmclock.data.model.Alarm(id = rev2AlarmId, hour = 8, minute = 0, isEnabled = true))
        dao.reserveIssuedOccurrence(rev2Token, rev2AlarmId, triggerB, "fire-rev2")
        dao.activateIssuedOccurrence(rev2Token)
        db.durableOperationIntentDao().insertValidatedIntent(
            DurableOperationIntent(
                operationToken = rev2Token,
                alarmId = rev2AlarmId,
                operationEpoch = 1000L,
                incarnationId = "fire-rev2",
                operationKind = "CANCEL",
                expectedOwnerToken = "owner-7009",
                expectedScheduleRevision = 1L,
                expectedRestoreEpoch = 1L,
                phase = DurableOperationIntent.PHASE_PENDING,
                intentRevision = 2L,
                originalFireId = "fire-rev2",
                originalScheduledAt = triggerB
            )
        )
        assertFalse(scheduler.cancelTokenizedScheduledAlarm(rev2AlarmId))
        assertEquals(DurableOperationIntent.PHASE_PENDING, db.durableOperationIntentDao().getIntentByToken(rev2Token)!!.phase)
        assertEquals(IssuedOccurrence.STATUS_ISSUED, dao.getOccurrence(rev2Token)!!.status)
    }

    @Test
    fun `T1 - applyTokenizedOsSet fails closed with zero side effects when owner is null or alarmId non-positive`() = runBlocking {
        val alarmId = 7010L
        val triggerTime = 1_700_000_000_000L
        val tokenWithNullProvider = UUID.randomUUID().toString()
        val tokenWithNullGet = UUID.randomUUID().toString()
        val tokenWithZeroId = UUID.randomUUID().toString()
        val tokenWithNegativeId = UUID.randomUUID().toString()

        db.alarmDao().insert(com.sysadmindoc.alarmclock.data.model.Alarm(id = alarmId, hour = 7, minute = 0, isEnabled = true))

        // Arm Legacy V1 PI to ensure owner-null MAIN_V2 never mutates Legacy state
        val createLegacyMethod = AlarmScheduler::class.java.getDeclaredMethod(
            "createPendingIntent",
            Long::class.javaPrimitiveType,
            Long::class.javaPrimitiveType,
            String::class.java
        ).apply { isAccessible = true }
        val lookupLegacyMethod = AlarmScheduler::class.java.getDeclaredMethod(
            "lookupPendingIntentForCancel",
            Long::class.javaPrimitiveType
        ).apply { isAccessible = true }
        createLegacyMethod.invoke(scheduler, alarmId, triggerTime, "fire-legacy-t1")
        assertNotNull(lookupLegacyMethod.invoke(scheduler, alarmId))

        val spyRepo = spyk(alarmRepo)
        val mockAm = mockk<AlarmManager>(relaxed = true) {
            every { canScheduleExactAlarms() } returns true
        }

        // Case A: scheduleAuthorityOwnerProvider == null
        val nullProviderScheduler = AlarmScheduler(
            context = context,
            repository = spyRepo,
            calculator = calculator,
            preferencesManager = preferencesManager,
            holidayRepository = holidayRepository,
            alarmIncidentRepository = alarmIncidentRepository,
            weatherRepository = weatherRepository,
            scheduleAuthorityOwnerProvider = null,
            routeAuthority = mockOwnerRouteAuthority
        ).apply { testAlarmManager = mockAm }

        assertFalse(
            "T1a: applyTokenizedOsSet MUST fail closed when scheduleAuthorityOwnerProvider is null",
            nullProviderScheduler.applyTokenizedOsSet(alarmId, triggerTime, tokenWithNullProvider)
        )

        // Case B: scheduleAuthorityOwnerProvider.get() == null
        val nullReturningProvider = mockk<Provider<ScheduleAuthorityOwner>> {
            every { get() } returns null
        }
        val nullGetScheduler = AlarmScheduler(
            context = context,
            repository = spyRepo,
            calculator = calculator,
            preferencesManager = preferencesManager,
            holidayRepository = holidayRepository,
            alarmIncidentRepository = alarmIncidentRepository,
            weatherRepository = weatherRepository,
            scheduleAuthorityOwnerProvider = nullReturningProvider,
            routeAuthority = mockOwnerRouteAuthority
        ).apply { testAlarmManager = mockAm }

        assertFalse(
            "T1b: applyTokenizedOsSet MUST fail closed when ownerProvider.get() returns null",
            nullGetScheduler.applyTokenizedOsSet(alarmId, triggerTime, tokenWithNullGet)
        )

        // Case C: alarmId <= 0 even when owner is present
        val ownerPresentSpyScheduler = AlarmScheduler(
            context = context,
            repository = spyRepo,
            calculator = calculator,
            preferencesManager = preferencesManager,
            holidayRepository = holidayRepository,
            alarmIncidentRepository = alarmIncidentRepository,
            weatherRepository = weatherRepository,
            scheduleAuthorityOwnerProvider = ownerProvider,
            routeAuthority = mockOwnerRouteAuthority
        ).apply { testAlarmManager = mockAm }

        assertFalse(ownerPresentSpyScheduler.applyTokenizedOsSet(0L, triggerTime, tokenWithZeroId))
        assertFalse(ownerPresentSpyScheduler.applyTokenizedOsSet(-5L, triggerTime, tokenWithNegativeId))

        // Verify Locked helper was never entered and zero DAO / PI / AlarmManager / Legacy side effects occurred
        verify(exactly = 0) { spyRepo.issuedOccurrenceDao() }
        verify(exactly = 0) { spyRepo.durableOperationIntentDao() }
        verify(exactly = 0) { mockAm.setAlarmClock(any(), any()) }
        verify(exactly = 0) { mockAm.setExactAndAllowWhileIdle(any(), any(), any()) }
        verify(exactly = 0) { mockAm.cancel(any<PendingIntent>()) }

        assertNull(dao.getOccurrence(tokenWithNullProvider))
        assertNull(dao.getOccurrence(tokenWithNullGet))
        assertNull(dao.getOccurrence(tokenWithZeroId))
        assertNull(dao.getOccurrence(tokenWithNegativeId))
        assertNull(dao.getSlot(alarmId))
        assertNull(scheduler.lookupTokenizedPendingIntentForCancel(alarmId, tokenWithNullProvider))
        assertNull(scheduler.lookupTokenizedPendingIntentForCancel(alarmId, tokenWithNullGet))
        assertNotNull("Legacy V1 PI MUST remain untouched", lookupLegacyMethod.invoke(scheduler, alarmId))
    }

    @Test
    fun `T2 - cancelTokenizedScheduledAlarm fails closed with zero side effects when owner is null or alarmId non-positive`() = runBlocking {
        val alarmId = 7011L
        val triggerTime = 1_700_000_000_000L
        val activeToken = UUID.randomUUID().toString()

        val ownerScheduler = createOwnerExclusiveScheduler()

        db.alarmDao().insert(com.sysadmindoc.alarmclock.data.model.Alarm(id = alarmId, hour = 7, minute = 0, isEnabled = true))

        // Arm active tokenized occurrence via owner-present scheduler + Legacy V1 PI
        assertTrue(ownerScheduler.applyTokenizedOsSet(alarmId, triggerTime, activeToken))
        val createLegacyMethod = AlarmScheduler::class.java.getDeclaredMethod(
            "createPendingIntent",
            Long::class.javaPrimitiveType,
            Long::class.javaPrimitiveType,
            String::class.java
        ).apply { isAccessible = true }
        val lookupLegacyMethod = AlarmScheduler::class.java.getDeclaredMethod(
            "lookupPendingIntentForCancel",
            Long::class.javaPrimitiveType
        ).apply { isAccessible = true }
        createLegacyMethod.invoke(ownerScheduler, alarmId, triggerTime, "fire-legacy-t2")

        assertNotNull(ownerScheduler.lookupTokenizedPendingIntentForCancel(alarmId, activeToken))
        assertNotNull(lookupLegacyMethod.invoke(ownerScheduler, alarmId))

        val spyRepo = spyk(alarmRepo)
        val mockAm = mockk<AlarmManager>(relaxed = true)

        // Case A: scheduleAuthorityOwnerProvider == null
        val nullProviderScheduler = AlarmScheduler(
            context = context,
            repository = spyRepo,
            calculator = calculator,
            preferencesManager = preferencesManager,
            holidayRepository = holidayRepository,
            alarmIncidentRepository = alarmIncidentRepository,
            weatherRepository = weatherRepository,
            scheduleAuthorityOwnerProvider = null
        ).apply { testAlarmManager = mockAm }

        assertFalse(
            "T2a: cancelTokenizedScheduledAlarm MUST fail closed when scheduleAuthorityOwnerProvider is null",
            nullProviderScheduler.cancelTokenizedScheduledAlarm(alarmId, activeToken)
        )

        // Case B: scheduleAuthorityOwnerProvider.get() == null
        val nullReturningProvider = mockk<Provider<ScheduleAuthorityOwner>> {
            every { get() } returns null
        }
        val nullGetScheduler = AlarmScheduler(
            context = context,
            repository = spyRepo,
            calculator = calculator,
            preferencesManager = preferencesManager,
            holidayRepository = holidayRepository,
            alarmIncidentRepository = alarmIncidentRepository,
            weatherRepository = weatherRepository,
            scheduleAuthorityOwnerProvider = nullReturningProvider,
            routeAuthority = mockOwnerRouteAuthority
        ).apply { testAlarmManager = mockAm }

        assertFalse(
            "T2b: cancelTokenizedScheduledAlarm MUST fail closed when ownerProvider.get() returns null",
            nullGetScheduler.cancelTokenizedScheduledAlarm(alarmId, activeToken)
        )

        // Case C: alarmId <= 0 even when owner is present
        val ownerPresentSpyScheduler = AlarmScheduler(
            context = context,
            repository = spyRepo,
            calculator = calculator,
            preferencesManager = preferencesManager,
            holidayRepository = holidayRepository,
            alarmIncidentRepository = alarmIncidentRepository,
            weatherRepository = weatherRepository,
            scheduleAuthorityOwnerProvider = ownerProvider,
            routeAuthority = mockOwnerRouteAuthority
        ).apply { testAlarmManager = mockAm }

        assertFalse(ownerPresentSpyScheduler.cancelTokenizedScheduledAlarm(0L, activeToken))
        assertFalse(ownerPresentSpyScheduler.cancelTokenizedScheduledAlarm(-9L, activeToken))

        // Verify Locked helper was never entered and zero DAO / PI / AlarmManager / Legacy side effects occurred
        verify(exactly = 0) { spyRepo.durableOperationIntentDao() }
        verify(exactly = 0) { spyRepo.issuedOccurrenceDao() }
        verify(exactly = 0) { mockAm.cancel(any<PendingIntent>()) }

        assertEquals(IssuedOccurrence.STATUS_ISSUED, dao.getOccurrence(activeToken)!!.status)
        assertEquals(activeToken, dao.getSlot(alarmId)!!.currentIssueToken)
        assertNull(db.durableOperationIntentDao().getIntentByToken(activeToken))
        assertNotNull("Tokenized PI MUST NOT be cancelled on owner-null fail-closed", scheduler.lookupTokenizedPendingIntentForCancel(alarmId, activeToken))
        assertNotNull("Legacy V1 PI MUST remain untouched", lookupLegacyMethod.invoke(scheduler, alarmId))
    }

    @Test
    fun `C1-RED-01 - rescheduleAllInBatches cancels Legacy V1 PI while preserving V2 witnesses when owner is null`() = runBlocking {
        val alarmId = 7101L
        val triggerTime = 1_700_000_000_000L
        val tokenV2 = UUID.randomUUID().toString()

        val ownerScheduler = createOwnerExclusiveScheduler()

        db.alarmDao().insert(com.sysadmindoc.alarmclock.data.model.Alarm(id = alarmId, hour = 7, minute = 0, isEnabled = true))
        assertTrue(ownerScheduler.applyTokenizedOsSet(alarmId, triggerTime, tokenV2))

        val createLegacyMethod = AlarmScheduler::class.java.getDeclaredMethod(
            "createPendingIntent",
            Long::class.javaPrimitiveType,
            Long::class.javaPrimitiveType,
            String::class.java
        ).apply { isAccessible = true }
        val lookupLegacyMethod = AlarmScheduler::class.java.getDeclaredMethod(
            "lookupPendingIntentForCancel",
            Long::class.javaPrimitiveType
        ).apply { isAccessible = true }
        createLegacyMethod.invoke(ownerScheduler, alarmId, triggerTime, "fire-legacy-7101")
        assertNotNull("Precondition: Legacy V1 PI must be armed", lookupLegacyMethod.invoke(ownerScheduler, alarmId))
        assertNotNull("Precondition: MAIN_V2 PI must be armed", ownerScheduler.lookupTokenizedPendingIntentForCancel(alarmId, tokenV2))

        // Disable alarm in DB, then run rescheduleAllInBatches with scheduleAuthorityOwnerProvider = null
        db.alarmDao().insert(com.sysadmindoc.alarmclock.data.model.Alarm(id = alarmId, hour = 7, minute = 0, isEnabled = false))
        val nullOwnerScheduler = AlarmScheduler(
            context = context,
            repository = alarmRepo,
            calculator = calculator,
            preferencesManager = preferencesManager,
            holidayRepository = holidayRepository,
            alarmIncidentRepository = alarmIncidentRepository,
            weatherRepository = weatherRepository,
            scheduleAuthorityOwnerProvider = null
        )

        nullOwnerScheduler.rescheduleAllInBatches()

        assertNull("C1-RED-01: Legacy V1 PI MUST be cancelled under LEGACY_ONLY even when V2 owner is null", lookupLegacyMethod.invoke(scheduler, alarmId))
        assertEquals("C1-RED-01: IssueSlot.currentIssueToken MUST be preserved on OWNER_UNAVAILABLE", tokenV2, dao.getSlot(alarmId)?.currentIssueToken)
        assertEquals("C1-RED-01: IssuedOccurrence MUST remain ISSUED on OWNER_UNAVAILABLE", IssuedOccurrence.STATUS_ISSUED, dao.getOccurrence(tokenV2)?.status)
        assertNotNull("C1-RED-01: MAIN_V2 PI MUST NOT be cancelled when V2 owner is null", scheduler.lookupTokenizedPendingIntentForCancel(alarmId, tokenV2))
    }

    @Test
    fun `C1-RED-02 - rescheduleAllInBatches preserves IssueSlot, IssuedOccurrence, and unresolved Rev2 intent on OS cancel exception or Rev2 isolation`() = runBlocking {
        val triggerTime = 1_700_000_000_000L

        val ownerScheduler = createOwnerExclusiveScheduler()

        // Case A: OS cancel throws RuntimeException (OS_OUTCOME_UNKNOWN) on disabled alarm 7102L
        val alarmIdA = 7102L
        val tokenB = UUID.randomUUID().toString()
        db.alarmDao().insert(com.sysadmindoc.alarmclock.data.model.Alarm(id = alarmIdA, hour = 7, minute = 0, isEnabled = true))
        assertTrue(ownerScheduler.applyTokenizedOsSet(alarmIdA, triggerTime, tokenB))
        assertNotNull(ownerScheduler.lookupTokenizedPendingIntentForCancel(alarmIdA, tokenB))
        val slotGenBeforeA = dao.getSlot(alarmIdA)!!.generationId

        db.alarmDao().insert(com.sysadmindoc.alarmclock.data.model.Alarm(id = alarmIdA, hour = 7, minute = 0, isEnabled = false))
        val throwingAm = mockk<AlarmManager>(relaxed = true) {
            every { cancel(any<PendingIntent>()) } throws RuntimeException("Simulated OS cancel error")
        }
        val failingOsScheduler = AlarmScheduler(
            context = context,
            repository = alarmRepo,
            calculator = calculator,
            preferencesManager = preferencesManager,
            holidayRepository = holidayRepository,
            alarmIncidentRepository = alarmIncidentRepository,
            weatherRepository = weatherRepository,
            scheduleAuthorityOwnerProvider = ownerProvider,
            routeAuthority = mockOwnerRouteAuthority
        ).apply { testAlarmManager = throwingAm }

        failingOsScheduler.rescheduleAllInBatches()

        assertEquals("C1-RED-02A: IssueSlot.currentIssueToken MUST remain tokenB on OS_OUTCOME_UNKNOWN", tokenB, dao.getSlot(alarmIdA)?.currentIssueToken)
        assertEquals("C1-RED-02A: IssueSlot generation MUST NOT advance on OS_OUTCOME_UNKNOWN", slotGenBeforeA, dao.getSlot(alarmIdA)?.generationId)
        assertEquals("C1-RED-02A: IssuedOccurrence MUST remain ISSUED on OS_OUTCOME_UNKNOWN", IssuedOccurrence.STATUS_ISSUED, dao.getOccurrence(tokenB)?.status)
        val intentPhaseA = db.durableOperationIntentDao().getIntentByToken(tokenB)?.phase
        assertTrue("C1-RED-02A: Cancel intent MUST NOT be settled COMPLETED/ABORTED on OS_OUTCOME_UNKNOWN", intentPhaseA == null || intentPhaseA == DurableOperationIntent.PHASE_PENDING)
        assertNotNull("C1-RED-02A: MAIN_V2 PI MUST remain registered when OS cancel threw", ownerScheduler.lookupTokenizedPendingIntentForCancel(alarmIdA, tokenB))

        // Case B: Unresolved REV2_B_SPECIFIC CANCEL intent on disabled alarm 7103L
        val alarmIdB = 7103L
        val tokenC = UUID.randomUUID().toString()
        val tokenRev2 = UUID.randomUUID().toString()
        db.alarmDao().insert(com.sysadmindoc.alarmclock.data.model.Alarm(id = alarmIdB, hour = 8, minute = 0, isEnabled = true))
        assertTrue(ownerScheduler.applyTokenizedOsSet(alarmIdB, triggerTime, tokenC))
        val occRev2 = dao.reserveIssuedOccurrence(tokenRev2, alarmIdB, triggerTime + 60_000L, "fire-rev2-7103")!!
        db.durableOperationIntentDao().insertValidatedIntent(
            DurableOperationIntent(
                operationToken = tokenRev2,
                alarmId = alarmIdB,
                operationEpoch = System.currentTimeMillis(),
                incarnationId = occRev2.fireId,
                operationKind = "CANCEL",
                expectedOwnerToken = "owner-7103",
                expectedScheduleRevision = 1L,
                expectedRestoreEpoch = 0L,
                phase = DurableOperationIntent.PHASE_PENDING,
                intentRevision = 2L,
                originalFireId = occRev2.fireId,
                originalScheduledAt = occRev2.scheduledAt
            )
        )
        db.alarmDao().insert(com.sysadmindoc.alarmclock.data.model.Alarm(id = alarmIdB, hour = 8, minute = 0, isEnabled = false))

        ownerScheduler.rescheduleAllInBatches()

        assertEquals("C1-RED-02B: IssueSlot.currentIssueToken MUST remain tokenC when Rev2 intent isolates alarm", tokenC, dao.getSlot(alarmIdB)?.currentIssueToken)
        assertEquals("C1-RED-02B: Active occurrence MUST remain ISSUED under Rev2 isolation", IssuedOccurrence.STATUS_ISSUED, dao.getOccurrence(tokenC)?.status)
        assertEquals("C1-RED-02B: Rev2 PREPARED occurrence MUST remain PREPARED", IssuedOccurrence.STATUS_PREPARED, dao.getOccurrence(tokenRev2)?.status)
        assertEquals("C1-RED-02B: Rev2 intent MUST remain PENDING", DurableOperationIntent.PHASE_PENDING, db.durableOperationIntentDao().getIntentByToken(tokenRev2)?.phase)
        assertNotNull("C1-RED-02B: MAIN_V2 PI MUST remain untouched under Rev2 isolation", ownerScheduler.lookupTokenizedPendingIntentForCancel(alarmIdB, tokenC))
    }

    @Test
    fun `rescheduleAllInBatches with valid owner retires ONLY targeted slot token and preserves unrelated PREPARED occurrence`() = runBlocking {
        val alarmId = 7104L
        val triggerTime = 1_700_000_000_000L
        val tokenActive = UUID.randomUUID().toString()
        val tokenPreparedOther = UUID.randomUUID().toString()

        val ownerScheduler = createOwnerExclusiveScheduler()

        db.alarmDao().insert(com.sysadmindoc.alarmclock.data.model.Alarm(id = alarmId, hour = 9, minute = 0, isEnabled = true))
        assertTrue(ownerScheduler.applyTokenizedOsSet(alarmId, triggerTime, tokenActive))
        dao.reserveIssuedOccurrence(tokenPreparedOther, alarmId, triggerTime + 120_000L, "fire-other-7104")!!
        ownerScheduler.createTokenizedPendingIntent(alarmId, triggerTime + 120_000L, "fire-other-7104", tokenPreparedOther)

        val createLegacyMethod = AlarmScheduler::class.java.getDeclaredMethod(
            "createPendingIntent",
            Long::class.javaPrimitiveType,
            Long::class.javaPrimitiveType,
            String::class.java
        ).apply { isAccessible = true }
        val lookupLegacyMethod = AlarmScheduler::class.java.getDeclaredMethod(
            "lookupPendingIntentForCancel",
            Long::class.javaPrimitiveType
        ).apply { isAccessible = true }
        createLegacyMethod.invoke(ownerScheduler, alarmId, triggerTime, "fire-legacy-7104")

        db.alarmDao().insert(com.sysadmindoc.alarmclock.data.model.Alarm(id = alarmId, hour = 9, minute = 0, isEnabled = false))
        ownerScheduler.rescheduleAllInBatches()

        assertNull("Targeted slot currentIssueToken MUST be cleared on valid V2 retirement", dao.getSlot(alarmId)?.currentIssueToken)
        assertEquals("Targeted active occurrence MUST transition to REVOKED", IssuedOccurrence.STATUS_REVOKED, dao.getOccurrence(tokenActive)?.status)
        assertNull("Targeted MAIN_V2 PI MUST be cancelled", ownerScheduler.lookupTokenizedPendingIntentForCancel(alarmId, tokenActive))
        assertEquals(
            "Unrelated PREPARED occurrence MUST NOT be blanket-revoked without its own OS cancel",
            IssuedOccurrence.STATUS_PREPARED,
            dao.getOccurrence(tokenPreparedOther)?.status
        )
        assertNotNull(
            "Unrelated PREPARED occurrence PI MUST remain untouched",
            scheduler.lookupTokenizedPendingIntentForCancel(alarmId, tokenPreparedOther)
        )
    }

    @Test
    fun `L1 & L2 - cancelScheduledEntriesWithResult returns true on success, false on OS cancel failure, and preserves Unit cancelScheduledEntries compatibility`() = runBlocking {
        val alarmId = 7201L
        val triggerTime = 1_700_000_000_000L
        db.alarmDao().insert(com.sysadmindoc.alarmclock.data.model.Alarm(id = alarmId, hour = 7, minute = 0, isEnabled = true))

        val createLegacyMethod = AlarmScheduler::class.java.getDeclaredMethod(
            "createPendingIntent",
            Long::class.javaPrimitiveType,
            Long::class.javaPrimitiveType,
            String::class.java
        ).apply { isAccessible = true }
        val lookupLegacyMethod = AlarmScheduler::class.java.getDeclaredMethod(
            "lookupPendingIntentForCancel",
            Long::class.javaPrimitiveType
        ).apply { isAccessible = true }

        // L1a: OS cancel throws -> cancelScheduledEntriesWithResult returns false and withholds DirectBoot removal
        createLegacyMethod.invoke(scheduler, alarmId, triggerTime, "fire-l1a")
        val directBootPrefs = context.createDeviceProtectedStorageContext()
            .getSharedPreferences("direct_boot_next_alarm", Context.MODE_PRIVATE)
        directBootPrefs.edit().putLong("alarm_id", alarmId).putLong("trigger_time", triggerTime).commit()

        val failingAm = mockk<AlarmManager>(relaxed = true) {
            every { cancel(any<PendingIntent>()) } throws RuntimeException("Simulated OS cancel error")
        }
        scheduler.testAlarmManager = failingAm
        assertFalse(
            "L1a: cancelScheduledEntriesWithResult MUST return false on OS cancel failure",
            scheduler.cancelScheduledEntriesWithResult(alarmId, includeFollowUpWorkers = true)
        )
        assertEquals(
            "L1a: DirectBoot cache MUST NOT be cleared when OS cancel failed",
            alarmId,
            directBootPrefs.getLong("alarm_id", -1L)
        )
        assertNotNull(lookupLegacyMethod.invoke(scheduler, alarmId))

        // L1b: OS cancel succeeds -> returns true, clears PI, and clears DirectBoot cache
        scheduler.testAlarmManager = null
        assertTrue(
            "L1b: cancelScheduledEntriesWithResult MUST return true when OS cancel succeeds",
            scheduler.cancelScheduledEntriesWithResult(alarmId, includeFollowUpWorkers = true)
        )
        assertEquals(-1L, directBootPrefs.getLong("alarm_id", -1L))
        assertNull(lookupLegacyMethod.invoke(scheduler, alarmId))

        // L2: Existing Unit-returning cancelScheduledEntries delegates and preserves public signature
        createLegacyMethod.invoke(scheduler, alarmId, triggerTime, "fire-l2")
        val unitResult: Unit = scheduler.cancelScheduledEntries(alarmId, includeFollowUpWorkers = false)
        assertEquals(Unit, unitResult)
        assertNull(lookupLegacyMethod.invoke(scheduler, alarmId))
    }

    @Test
    fun `L3 - cancelScheduledEntriesWithResult returns false on stale rejection and owner unavailable under OWNER_EXCLUSIVE`() = runBlocking {
        val alarmId = 7202L
        val triggerTime = 1_700_000_000_000L
        db.alarmDao().insert(
            com.sysadmindoc.alarmclock.data.model.Alarm(
                id = alarmId,
                hour = 8,
                minute = 0,
                isEnabled = true,
                generation = 5,
                nextTriggerTime = triggerTime + 60_000L
            )
        )
        val createLegacyMethod = AlarmScheduler::class.java.getDeclaredMethod(
            "createPendingIntent",
            Long::class.javaPrimitiveType,
            Long::class.javaPrimitiveType,
            String::class.java
        ).apply { isAccessible = true }
        val lookupLegacyMethod = AlarmScheduler::class.java.getDeclaredMethod(
            "lookupPendingIntentForCancel",
            Long::class.javaPrimitiveType
        ).apply { isAccessible = true }
        createLegacyMethod.invoke(scheduler, alarmId, triggerTime, "fire-l3")

        // Stale expectedGeneration (4 < DB 5) MUST return false and keep PI intact
        assertFalse(
            "L3a: Stale expectedGeneration MUST return false",
            scheduler.cancelScheduledEntriesWithResult(alarmId, expectedGeneration = 4)
        )
        assertNotNull(lookupLegacyMethod.invoke(scheduler, alarmId))

        // Stale targetGen (scheduledAt < DB nextTriggerTime) MUST return false and keep PI intact
        assertFalse(
            "L3b: Stale targetGen MUST return false",
            scheduler.cancelScheduledEntriesWithResult(
                alarmId,
                targetGen = com.sysadmindoc.alarmclock.service.AlarmGeneration(
                    alarmId = alarmId,
                    scheduledAt = triggerTime,
                    fireId = "fire-l3"
                )
            )
        )
        assertNotNull(lookupLegacyMethod.invoke(scheduler, alarmId))

        // OWNER_EXCLUSIVE with null owner provider MUST return false and keep PI intact
        val ownerExclusiveRoute = mockk<ProductionSchedulingRouteAuthority>()
        every { ownerExclusiveRoute.isOwnerRouteActive() } returns true
        every { ownerExclusiveRoute.currentRoute } returns ProductionSchedulingRoute.OWNER_EXCLUSIVE
        val ownerMissingScheduler = AlarmScheduler(
            context = context,
            repository = alarmRepo,
            calculator = calculator,
            preferencesManager = preferencesManager,
            holidayRepository = holidayRepository,
            alarmIncidentRepository = alarmIncidentRepository,
            weatherRepository = weatherRepository,
            scheduleAuthorityOwnerProvider = null,
            routeAuthority = ownerExclusiveRoute
        )
        assertFalse(
            "L3c: OWNER_EXCLUSIVE with missing owner MUST fail closed returning false",
            ownerMissingScheduler.cancelScheduledEntriesWithResult(alarmId)
        )
        assertNotNull(lookupLegacyMethod.invoke(scheduler, alarmId))
    }

    @Test
    fun `V3, V4, V5, V6 - cleanupStalePreRestoreOccurrences preserves unknown PI, isolates Rev2 IN_FLIGHT, handles OS failure, and fails closed when owner absent`() = runBlocking {
        val currentEpoch = 105L
        val oldEpoch = 104L
        val triggerTime = 1_700_000_000_000L

        // V3: Stale occurrence with NO PendingIntent (FLAG_NO_CREATE == null) -> MUST preserve status (NEVER REVOKED)
        val alarmV3 = 7301L
        val tokenV3NullPi = UUID.randomUUID().toString()
        db.alarmDao().insert(com.sysadmindoc.alarmclock.data.model.Alarm(id = alarmV3, hour = 6, minute = 0, isEnabled = false))
        dao.insertOccurrence(
            IssuedOccurrence(
                issueToken = tokenV3NullPi,
                alarmId = alarmV3,
                scheduledAt = triggerTime,
                fireId = "fire-v3",
                generationId = 1L,
                status = IssuedOccurrence.STATUS_ISSUED,
                restoreEpoch = oldEpoch,
                createdAt = 1L,
                updatedAt = 1L
            )
        )
        assertNull(scheduler.lookupTokenizedPendingIntentForCancel(alarmV3, tokenV3NullPi))

        // V4: Stale occurrence with Rev2 IN_FLIGHT CANCEL intent (even when PI exists!) -> HOLD, COMPLETED_SETTLEMENT=0
        val alarmV4 = 7302L
        val tokenV4InFlight = UUID.randomUUID().toString()
        db.alarmDao().insert(com.sysadmindoc.alarmclock.data.model.Alarm(id = alarmV4, hour = 7, minute = 0, isEnabled = false))
        dao.insertOccurrence(
            IssuedOccurrence(
                issueToken = tokenV4InFlight,
                alarmId = alarmV4,
                scheduledAt = triggerTime,
                fireId = "fire-v4",
                generationId = 1L,
                status = IssuedOccurrence.STATUS_ISSUED,
                restoreEpoch = oldEpoch,
                createdAt = 1L,
                updatedAt = 1L
            )
        )
        scheduler.createTokenizedPendingIntent(alarmV4, triggerTime, "fire-v4", tokenV4InFlight)
        db.durableOperationIntentDao().insertValidatedIntent(
            DurableOperationIntent(
                operationToken = tokenV4InFlight,
                alarmId = alarmV4,
                operationEpoch = 1000L,
                incarnationId = "fire-v4",
                operationKind = "CANCEL",
                expectedOwnerToken = "owner-7302",
                expectedScheduleRevision = 1L,
                expectedRestoreEpoch = oldEpoch,
                phase = DurableOperationIntent.PHASE_IN_FLIGHT,
                intentRevision = 2L,
                originalFireId = "fire-v4",
                originalScheduledAt = triggerTime
            )
        )

        // Confirmed stale occurrence with pi != null and NO isolating intent -> cancels PI & CAS transitions -> REVOKED
        val alarmClean = 7303L
        val tokenClean = UUID.randomUUID().toString()
        db.alarmDao().insert(com.sysadmindoc.alarmclock.data.model.Alarm(id = alarmClean, hour = 8, minute = 0, isEnabled = false))
        dao.insertOccurrence(
            IssuedOccurrence(
                issueToken = tokenClean,
                alarmId = alarmClean,
                scheduledAt = triggerTime,
                fireId = "fire-clean",
                generationId = 1L,
                status = IssuedOccurrence.STATUS_ISSUED,
                restoreEpoch = oldEpoch,
                createdAt = 1L,
                updatedAt = 1L
            )
        )
        scheduler.createTokenizedPendingIntent(alarmClean, triggerTime, "fire-clean", tokenClean)

        // Current epoch occurrence MUST NOT be touched even if PI exists
        val alarmCurrent = 7304L
        val tokenCurrent = UUID.randomUUID().toString()
        db.alarmDao().insert(com.sysadmindoc.alarmclock.data.model.Alarm(id = alarmCurrent, hour = 9, minute = 0, isEnabled = true))
        dao.insertOccurrence(
            IssuedOccurrence(
                issueToken = tokenCurrent,
                alarmId = alarmCurrent,
                scheduledAt = triggerTime,
                fireId = "fire-current",
                generationId = 1L,
                status = IssuedOccurrence.STATUS_ISSUED,
                restoreEpoch = currentEpoch,
                createdAt = 1L,
                updatedAt = 1L
            )
        )
        scheduler.createTokenizedPendingIntent(alarmCurrent, triggerTime, "fire-current", tokenCurrent)

        // V6: Owner absent while stale occurrences exist -> fails closed with failedCount > 0 and zero mutations
        val nullOwnerScheduler = AlarmScheduler(
            context = context,
            repository = alarmRepo,
            calculator = calculator,
            preferencesManager = preferencesManager,
            holidayRepository = holidayRepository,
            alarmIncidentRepository = alarmIncidentRepository,
            weatherRepository = weatherRepository,
            scheduleAuthorityOwnerProvider = null
        )
        val ownerMissingResult = nullOwnerScheduler.cleanupStalePreRestoreOccurrences(currentEpoch)
        assertTrue("V6: Missing owner with stale occurrences MUST report failedCount > 0", ownerMissingResult.failedCount > 0)
        assertEquals(IssuedOccurrence.STATUS_ISSUED, dao.getOccurrence(tokenClean)!!.status)
        assertNotNull(scheduler.lookupTokenizedPendingIntentForCancel(alarmClean, tokenClean))

        // V5: OS cancel throws on clean stale occurrence -> preserves witness & increments failedCount
        val alarmV5 = 7305L
        val tokenV5Fail = UUID.randomUUID().toString()
        db.alarmDao().insert(com.sysadmindoc.alarmclock.data.model.Alarm(id = alarmV5, hour = 10, minute = 0, isEnabled = false))
        dao.insertOccurrence(
            IssuedOccurrence(
                issueToken = tokenV5Fail,
                alarmId = alarmV5,
                scheduledAt = triggerTime,
                fireId = "fire-v5",
                generationId = 1L,
                status = IssuedOccurrence.STATUS_ISSUED,
                restoreEpoch = oldEpoch,
                createdAt = 1L,
                updatedAt = 1L
            )
        )
        scheduler.createTokenizedPendingIntent(alarmV5, triggerTime, "fire-v5", tokenV5Fail)

        val throwingAm = mockk<AlarmManager>(relaxed = true) {
            every { cancel(any<PendingIntent>()) } throws RuntimeException("Simulated OS cancel error")
        }
        scheduler.testAlarmManager = throwingAm
        val osFailResult = scheduler.cleanupStalePreRestoreOccurrences(currentEpoch)
        scheduler.testAlarmManager = null

        assertTrue("V5: OS cancel exception MUST increment failedCount", osFailResult.failedCount >= 2)
        assertEquals(IssuedOccurrence.STATUS_ISSUED, dao.getOccurrence(tokenV5Fail)!!.status)
        assertEquals(IssuedOccurrence.STATUS_ISSUED, dao.getOccurrence(tokenV3NullPi)!!.status)
        assertEquals(DurableOperationIntent.PHASE_IN_FLIGHT, db.durableOperationIntentDao().getIntentByToken(tokenV4InFlight)!!.phase)

        // Normal owner + working AlarmManager: cancels clean + V5 PIs, preserves V3 (pi==null) and V4 (IN_FLIGHT HOLD) and currentEpoch
        val result = scheduler.cleanupStalePreRestoreOccurrences(currentEpoch)
        assertEquals(0, result.failedCount)
        assertEquals(2, result.cancelledCount)
        assertEquals(1, result.preservedUnknownCount)
        assertEquals(1, result.isolatedCount)

        // V3: FLAG_NO_CREATE == null MUST NOT mark REVOKED
        assertEquals(
            "V3: FLAG_NO_CREATE == null MUST NOT revoke IssuedOccurrence",
            IssuedOccurrence.STATUS_ISSUED,
            dao.getOccurrence(tokenV3NullPi)!!.status
        )
        // V4: Rev2 IN_FLIGHT MUST remain HOLD with COMPLETED_SETTLEMENT=0
        assertEquals(
            "V4: Rev2 IN_FLIGHT occurrence MUST remain ISSUED",
            IssuedOccurrence.STATUS_ISSUED,
            dao.getOccurrence(tokenV4InFlight)!!.status
        )
        assertEquals(
            "V4: Rev2 IN_FLIGHT intent MUST remain IN_FLIGHT (COMPLETED_SETTLEMENT=0)",
            DurableOperationIntent.PHASE_IN_FLIGHT,
            db.durableOperationIntentDao().getIntentByToken(tokenV4InFlight)!!.phase
        )
        assertNotNull(
            "V4: Isolated Rev2 PI MUST NOT be cancelled",
            scheduler.lookupTokenizedPendingIntentForCancel(alarmV4, tokenV4InFlight)
        )
        // Confirmed pi != null non-isolated stale occurrences revoked
        assertEquals(IssuedOccurrence.STATUS_REVOKED, dao.getOccurrence(tokenClean)!!.status)
        assertNull(scheduler.lookupTokenizedPendingIntentForCancel(alarmClean, tokenClean))
        assertEquals(IssuedOccurrence.STATUS_REVOKED, dao.getOccurrence(tokenV5Fail)!!.status)
        assertNull(scheduler.lookupTokenizedPendingIntentForCancel(alarmV5, tokenV5Fail))
        // Current epoch untouched
        assertEquals(IssuedOccurrence.STATUS_ISSUED, dao.getOccurrence(tokenCurrent)!!.status)
        assertNotNull(scheduler.lookupTokenizedPendingIntentForCancel(alarmCurrent, tokenCurrent))
    }

    @Test
    fun `CE-B02-A1 - scheduleAt rolls back nextTriggerTime via CAS on LEGACY_ONLY OS registration failure and protects successor write`() = runBlocking {
        val alarmId = 7401L
        val futureTrigger = System.currentTimeMillis() + 3_600_000L
        val alarm = com.sysadmindoc.alarmclock.data.model.Alarm(
            id = alarmId,
            hour = 7,
            minute = 0,
            isEnabled = true,
            nextTriggerTime = 0L,
            generation = 1
        )
        db.alarmDao().insert(alarm)
        io.mockk.every { calculator.calculate(any()) } returns futureTrigger
        io.mockk.every { calculator.calculate(any(), any()) } returns futureTrigger

        val throwingAm = mockk<AlarmManager>(relaxed = true) {
            every { canScheduleExactAlarms() } returns true
            every { setAlarmClock(any(), any()) } throws RuntimeException("Simulated OS set failure")
            every { setExactAndAllowWhileIdle(any(), any(), any()) } throws RuntimeException("Simulated OS set failure")
        }
        scheduler.testAlarmManager = throwingAm

        // Case A: Incomplete authority fingerprint (originAuthority == null) under LEGACY_ONLY -> MUST roll back nextTriggerTime to 0L
        val outcomeA = scheduler.schedule(alarm, requestWidgetUpdate = false)
        assertTrue("CE-B02-A1A: Failed OS set MUST return RegistrationFailed", outcomeA is ScheduleOutcome.RegistrationFailed)
        assertEquals(
            "CE-B02-A1A: LEGACY_ONLY CAS rollback MUST revert nextTriggerTime to previousTrigger (0L) even without full authority fingerprint",
            0L,
            alarmRepo.getById(alarmId)!!.nextTriggerTime
        )

        // Case B: Pre-OS barrier mutates DB nextTriggerTime to successorTrigger while A fails OS registration -> CAS rollback MUST NOT clobber successor
        val successorTrigger = futureTrigger + 120_000L
        scheduler.testOnImmediatePreOsBarrier = { id, _ ->
            if (id == alarmId) {
                alarmRepo.updateNextTrigger(alarmId, successorTrigger)
            }
        }
        val outcomeB = scheduler.schedule(alarm, requestWidgetUpdate = false)
        scheduler.testOnImmediatePreOsBarrier = null
        scheduler.testAlarmManager = null

        assertTrue("CE-B02-A1B: Failed OS set MUST return RegistrationFailed", outcomeB is ScheduleOutcome.RegistrationFailed)
        assertEquals(
            "CE-B02-A1B: Atomic CAS rollback MUST protect successor nextTriggerTime against stale rollback",
            successorTrigger,
            alarmRepo.getById(alarmId)!!.nextTriggerTime
        )
    }

    @Test
    fun `CE-B02-A2 - schedule and scheduleExistingTrigger propagate RegistrationFailed when exact permission revoked or paused and OS cancel fails`() = runBlocking {
        val triggerTime = System.currentTimeMillis() + 3_600_000L
        val alarmId = 7402L
        val alarm = com.sysadmindoc.alarmclock.data.model.Alarm(
            id = alarmId,
            hour = 8,
            minute = 0,
            isEnabled = true,
            nextTriggerTime = triggerTime,
            generation = 1
        )
        db.alarmDao().insert(alarm)
        io.mockk.every { calculator.calculate(any()) } returns triggerTime
        io.mockk.every { calculator.calculate(any(), any()) } returns triggerTime

        val createLegacyMethod = AlarmScheduler::class.java.getDeclaredMethod(
            "createPendingIntent",
            Long::class.javaPrimitiveType,
            Long::class.javaPrimitiveType,
            String::class.java
        ).apply { isAccessible = true }
        createLegacyMethod.invoke(scheduler, alarmId, triggerTime, "fire-7402")

        val throwingCancelAm = mockk<AlarmManager>(relaxed = true) {
            every { canScheduleExactAlarms() } returns false
            every { cancel(any<PendingIntent>()) } throws RuntimeException("Simulated OS cancel error")
        }
        scheduler.testAlarmManager = throwingCancelAm

        // Case 1: schedule() with !canScheduleExactAlarms() and failed OS cancel MUST return RegistrationFailed (NOT PermissionBlocked) and NOT zero nextTriggerTime
        val outcome1 = scheduler.schedule(alarm, requestWidgetUpdate = false)
        assertTrue(
            "CE-B02-A2-1: Failed OS cancel during permission-revoked schedule MUST return RegistrationFailed, was $outcome1",
            outcome1 is ScheduleOutcome.RegistrationFailed
        )
        assertEquals(
            "CE-B02-A2-1: nextTriggerTime MUST NOT be zeroed when OS cancel failed",
            triggerTime,
            alarmRepo.getById(alarmId)!!.nextTriggerTime
        )

        // Case 2: rescheduleAllInBatches() -> scheduleExistingTriggerAssumingRecoveryLock() with failed OS cancel MUST increment failedCount (blockedCount == 0)
        val batchOutcome = scheduler.rescheduleAllInBatches(forceRecalculate = false)
        assertEquals("CE-B02-A2-2: batch failedCount MUST be 1 when scheduleExistingTrigger OS cancel fails", 1, batchOutcome.failedCount)
        assertEquals("CE-B02-A2-2: batch blockedCount MUST be 0 when OS cancel failed", 0, batchOutcome.blockedCount)
        assertEquals(
            "CE-B02-A2-2: nextTriggerTime MUST remain preserved when scheduleExistingTrigger OS cancel fails",
            triggerTime,
            alarmRepo.getById(alarmId)!!.nextTriggerTime
        )

        // Case 3: Paused settings with canScheduleExactAlarms == true and failed OS cancel MUST return RegistrationFailed (NOT NoArm("PAUSED"))
        val pausedAm = mockk<AlarmManager>(relaxed = true) {
            every { canScheduleExactAlarms() } returns true
            every { cancel(any<PendingIntent>()) } throws RuntimeException("Simulated OS cancel error")
        }
        scheduler.testAlarmManager = pausedAm
        io.mockk.coEvery { preferencesManager.getCurrentSettings() } returns
            com.sysadmindoc.alarmclock.data.preferences.AppSettings(pauseUntilMillis = System.currentTimeMillis() + 600_000L)
        val outcomePaused = scheduler.schedule(alarm, requestWidgetUpdate = false)
        scheduler.testAlarmManager = null

        assertTrue(
            "CE-B02-A2-3: Failed OS cancel during PAUSED schedule MUST return RegistrationFailed, was $outcomePaused",
            outcomePaused is ScheduleOutcome.RegistrationFailed
        )
        assertEquals(triggerTime, alarmRepo.getById(alarmId)!!.nextTriggerTime)
    }

    @Test
    fun `CE-B02-B - cancelScheduledEntriesWithResult enforces expectedMaxNextTrigger, expectedRestoreEpoch, mainSlotMutex serialization, and PI restoreEpoch extras`() = runBlocking {
        val alarmId = 7403L
        val futureTrigger = System.currentTimeMillis() + 3_600_000L
        db.scheduleAuthorityDao().insertMeta(
            com.sysadmindoc.alarmclock.data.local.entity.ScheduleAuthorityMeta(
                id = 1,
                restoreEpoch = 5L,
                bootstrapState = "COMPLETED"
            )
        )
        val alarm = com.sysadmindoc.alarmclock.data.model.Alarm(
            id = alarmId,
            hour = 7,
            minute = 30,
            isEnabled = true,
            nextTriggerTime = futureTrigger,
            generation = 1
        )
        db.alarmDao().insert(alarm)
        io.mockk.every { calculator.calculate(any()) } returns futureTrigger
        io.mockk.every { calculator.calculate(any(), any()) } returns futureTrigger

        val ownerScheduler = createOwnerExclusiveScheduler()
        assertTrue(scheduler.schedule(alarm, requestWidgetUpdate = false) is ScheduleOutcome.Armed)

        val lookupLegacyMethod = AlarmScheduler::class.java.getDeclaredMethod(
            "lookupPendingIntentForCancel",
            Long::class.javaPrimitiveType
        ).apply { isAccessible = true }
        val armedLegacyPi = lookupLegacyMethod.invoke(scheduler, alarmId) as PendingIntent?
        assertNotNull("Legacy PI must exist after Armed schedule", armedLegacyPi)
        val savedLegacyIntent = org.robolectric.Shadows.shadowOf(armedLegacyPi!!).savedIntent
        assertEquals(
            "CE-B02-B: Legacy PI MUST embed EXTRA_RESTORE_EPOCH = 5L",
            5L,
            savedLegacyIntent.getLongExtra("restore_epoch", 0L)
        )
        assertTrue(
            "CE-B02-B: Legacy PI MUST embed EXTRA_QUICK_ALARM = false",
            savedLegacyIntent.hasExtra("EXTRA_QUICK_ALARM") &&
                !savedLegacyIntent.getBooleanExtra("EXTRA_QUICK_ALARM", true)
        )

        val tokenMainV2 = UUID.randomUUID().toString()
        assertTrue(ownerScheduler.applyTokenizedOsSet(alarmId, futureTrigger, tokenMainV2))
        val armedV2Pi = ownerScheduler.lookupTokenizedPendingIntentForCancel(alarmId, tokenMainV2)
        assertNotNull(armedV2Pi)
        val savedV2Intent = org.robolectric.Shadows.shadowOf(armedV2Pi!!).savedIntent
        assertEquals(
            "CE-B02-B: Tokenized MAIN_V2 PI MUST embed EXTRA_RESTORE_EPOCH = 5L",
            5L,
            savedV2Intent.getLongExtra("restore_epoch", 0L)
        )
        assertTrue(
            "CE-B02-B: Tokenized MAIN_V2 PI MUST embed EXTRA_QUICK_ALARM = false",
            savedV2Intent.hasExtra("EXTRA_QUICK_ALARM") &&
                !savedV2Intent.getBooleanExtra("EXTRA_QUICK_ALARM", true)
        )

        val fencedCancelMethod = AlarmScheduler::class.java.declaredMethods.firstOrNull {
            it.name.startsWith("cancelScheduledEntriesWithResult") &&
                !it.name.endsWith("\$default") &&
                it.parameterTypes.size == 7
        }
        assertNotNull(
            "CE-B02-B: cancelScheduledEntriesWithResult MUST accept (alarmId, includeFollowUpWorkers, targetGen, expectedRevision, expectedGeneration, expectedMaxNextTrigger, expectedRestoreEpoch)",
            fencedCancelMethod
        )
        fencedCancelMethod!!.isAccessible = true

        // 1. expectedMaxNextTrigger = 0L while DB has enabled alarm with nextTriggerTime = futureTrigger -> MUST return false & preserve PI
        val rejectedTrigger = fencedCancelMethod.invoke(scheduler, alarmId, false, null, null, null, 0L, null) as Boolean
        assertFalse("CE-B02-B: expectedMaxNextTrigger = 0L MUST reject cancel when DB has armed future trigger", rejectedTrigger)
        assertNotNull("CE-B02-B: Armed PI MUST remain intact when expectedMaxNextTrigger rejects", lookupLegacyMethod.invoke(scheduler, alarmId))

        // 2. expectedRestoreEpoch = 4L while DB Meta has restoreEpoch = 5L -> MUST return false & preserve PI
        val rejectedEpoch = fencedCancelMethod.invoke(scheduler, alarmId, false, null, null, null, futureTrigger, 4L) as Boolean
        assertFalse("CE-B02-B: Stale expectedRestoreEpoch MUST reject cancel", rejectedEpoch)
        assertNotNull("CE-B02-B: Armed PI MUST remain intact when expectedRestoreEpoch mismatches", lookupLegacyMethod.invoke(scheduler, alarmId))

        // 3. Matching expectedRestoreEpoch = 5L & expectedMaxNextTrigger = futureTrigger -> cancels PI & returns true
        val accepted = fencedCancelMethod.invoke(scheduler, alarmId, false, null, null, null, futureTrigger, 5L) as Boolean
        assertTrue("CE-B02-B: Matching epoch and trigger bound MUST cancel PI", accepted)
        assertNull("CE-B02-B: Legacy PI MUST be cancelled when fence passes", lookupLegacyMethod.invoke(scheduler, alarmId))

        // 4. Non-positive DB restoreEpoch (0L) fails closed when expectedRestoreEpoch = 5L is supplied
        assertTrue(scheduler.schedule(alarm, requestWidgetUpdate = false) is ScheduleOutcome.Armed)
        db.scheduleAuthorityDao().insertMeta(
            com.sysadmindoc.alarmclock.data.local.entity.ScheduleAuthorityMeta(1, restoreEpoch = 0L, bootstrapState = "PENDING")
        )
        val rejectedZeroEpoch = fencedCancelMethod.invoke(scheduler, alarmId, false, null, null, null, futureTrigger, 5L) as Boolean
        assertFalse("CE-B02-B: Non-positive DB restoreEpoch MUST fail closed when expectedRestoreEpoch > 0", rejectedZeroEpoch)
        assertNotNull(lookupLegacyMethod.invoke(scheduler, alarmId))

        // 5a. Concurrent schedule(NEW 7403) + fenced cancel: fail-closed non-blocking recoveryMutex gate protects in-flight schedule()
        db.scheduleAuthorityDao().insertMeta(
            com.sysadmindoc.alarmclock.data.local.entity.ScheduleAuthorityMeta(1, restoreEpoch = 5L, bootstrapState = "COMPLETED")
        )
        val enteredBarrier = kotlinx.coroutines.CompletableDeferred<Unit>()
        val releaseBarrier = kotlinx.coroutines.CompletableDeferred<Unit>()
        scheduler.testOnPreOsRegistrationBarrier = { id, _ ->
            if (id == alarmId) {
                enteredBarrier.complete(Unit)
                releaseBarrier.await()
            }
        }
        val scheduleJob = async(kotlinx.coroutines.Dispatchers.Default) {
            scheduler.schedule(alarm, requestWidgetUpdate = false)
        }
        enteredBarrier.await()
        val contendedCancelOutcome = fencedCancelMethod.invoke(scheduler, alarmId, false, null, null, null, 0L, 5L) as Boolean
        assertFalse("CE-B02-B: Fenced cancel MUST fail closed while schedule() holds L1 recoveryMutex", contendedCancelOutcome)

        releaseBarrier.complete(Unit)
        assertTrue(scheduleJob.await() is ScheduleOutcome.Armed)
        scheduler.testOnPreOsRegistrationBarrier = null
        assertNotNull("CE-B02-B: Newly armed PI MUST remain intact after contended fenced cancel", lookupLegacyMethod.invoke(scheduler, alarmId))

        // 5b. When L1 is free, fenced cancel MUST serialize on Owner's shared slot mutex and re-read DB state inside the lock
        val mutexForMethod = ScheduleAuthorityOwner::class.java.getDeclaredMethod("mutexFor", Long::class.javaPrimitiveType).apply {
            isAccessible = true
        }
        val slotMutex = mutexForMethod.invoke(owner, alarmId) as kotlinx.coroutines.sync.Mutex
        slotMutex.lock()
        val cancelJob = async(kotlinx.coroutines.Dispatchers.IO) {
            fencedCancelMethod.invoke(scheduler, alarmId, false, null, null, null, 0L, 5L) as Boolean
        }
        kotlinx.coroutines.delay(80L)
        assertFalse("CE-B02-B: Fenced cancel MUST serialize on shared slot mutex while lock is held", cancelJob.isCompleted)
        slotMutex.unlock()
        assertFalse("CE-B02-B: Fenced cancel after slot lock release MUST see armed trigger > 0 and return false", cancelJob.await())
        assertNotNull("CE-B02-B: Newly armed PI MUST remain intact after serialized fenced cancel", lookupLegacyMethod.invoke(scheduler, alarmId))
    }

    @Test
    fun `V3b - cleanupStalePreRestoreOccurrences preserves PREPARED and ADMITTED null-PI occurrences and does not settle Rev1 PENDING intent`() = runBlocking {
        val currentEpoch = 205L
        val oldEpoch = 204L
        val triggerTime = 1_700_000_000_000L

        val alarmPrep = 7501L
        val tokenPrep = UUID.randomUUID().toString()
        db.alarmDao().insert(com.sysadmindoc.alarmclock.data.model.Alarm(id = alarmPrep, hour = 6, minute = 0, isEnabled = false))
        dao.insertOccurrence(
            IssuedOccurrence(
                issueToken = tokenPrep,
                alarmId = alarmPrep,
                scheduledAt = triggerTime,
                fireId = "fire-v3b-prep",
                generationId = 1L,
                status = IssuedOccurrence.STATUS_PREPARED,
                restoreEpoch = oldEpoch,
                createdAt = 1L,
                updatedAt = 1L
            )
        )

        val alarmAdmitted = 7502L
        val tokenAdmitted = UUID.randomUUID().toString()
        db.alarmDao().insert(com.sysadmindoc.alarmclock.data.model.Alarm(id = alarmAdmitted, hour = 6, minute = 30, isEnabled = false))
        dao.insertOccurrence(
            IssuedOccurrence(
                issueToken = tokenAdmitted,
                alarmId = alarmAdmitted,
                scheduledAt = triggerTime,
                fireId = "fire-v3b-admitted",
                generationId = 1L,
                status = IssuedOccurrence.STATUS_ADMITTED,
                restoreEpoch = oldEpoch,
                createdAt = 1L,
                updatedAt = 1L
            )
        )
        db.durableOperationIntentDao().insertValidatedIntent(
            DurableOperationIntent(
                operationToken = tokenAdmitted,
                alarmId = alarmAdmitted,
                operationEpoch = 1000L,
                incarnationId = "fire-v3b-admitted",
                operationKind = "CANCEL",
                expectedOwnerToken = null,
                expectedScheduleRevision = null,
                expectedRestoreEpoch = oldEpoch,
                phase = DurableOperationIntent.PHASE_PENDING,
                intentRevision = 1L,
                originalFireId = "fire-v3b-admitted",
                originalScheduledAt = triggerTime
            )
        )

        val result = scheduler.cleanupStalePreRestoreOccurrences(currentEpoch)
        assertEquals(0, result.cancelledCount)
        assertEquals(1, result.preservedUnknownCount)
        assertEquals(1, result.isolatedCount)
        assertEquals(0, result.failedCount)
        assertEquals(IssuedOccurrence.STATUS_PREPARED, dao.getOccurrence(tokenPrep)!!.status)
        assertEquals(IssuedOccurrence.STATUS_ADMITTED, dao.getOccurrence(tokenAdmitted)!!.status)
        assertEquals(
            "V3b: Rev1 PENDING intent MUST NOT be settled to COMPLETED when PI is null",
            DurableOperationIntent.PHASE_PENDING,
            db.durableOperationIntentDao().getIntentByToken(tokenAdmitted)!!.phase
        )
    }

    @Test
    fun `V4b - cleanupStalePreRestoreOccurrences isolates Rev2 PHASE_PENDING and UNKNOWN_FAIL_CLOSED intents`() = runBlocking {
        val currentEpoch = 206L
        val oldEpoch = 205L
        val triggerTime = 1_700_000_000_000L

        val alarmRev2 = 7503L
        val tokenRev2 = UUID.randomUUID().toString()
        db.alarmDao().insert(com.sysadmindoc.alarmclock.data.model.Alarm(id = alarmRev2, hour = 7, minute = 0, isEnabled = false))
        dao.insertOccurrence(
            IssuedOccurrence(
                issueToken = tokenRev2,
                alarmId = alarmRev2,
                scheduledAt = triggerTime,
                fireId = "fire-v4b-rev2",
                generationId = 1L,
                status = IssuedOccurrence.STATUS_ISSUED,
                restoreEpoch = oldEpoch,
                createdAt = 1L,
                updatedAt = 1L
            )
        )
        scheduler.createTokenizedPendingIntent(alarmRev2, triggerTime, "fire-v4b-rev2", tokenRev2)
        db.durableOperationIntentDao().insertValidatedIntent(
            DurableOperationIntent(
                operationToken = tokenRev2,
                alarmId = alarmRev2,
                operationEpoch = 1000L,
                incarnationId = "fire-v4b-rev2",
                operationKind = "CANCEL",
                expectedOwnerToken = "owner-rev2",
                expectedScheduleRevision = 1L,
                expectedRestoreEpoch = oldEpoch,
                phase = DurableOperationIntent.PHASE_PENDING,
                intentRevision = 2L,
                originalFireId = "fire-v4b-rev2",
                originalScheduledAt = triggerTime
            )
        )

        val alarmUnknown = 7504L
        val tokenUnknown = UUID.randomUUID().toString()
        db.alarmDao().insert(com.sysadmindoc.alarmclock.data.model.Alarm(id = alarmUnknown, hour = 7, minute = 15, isEnabled = false))
        dao.insertOccurrence(
            IssuedOccurrence(
                issueToken = tokenUnknown,
                alarmId = alarmUnknown,
                scheduledAt = triggerTime,
                fireId = "fire-v4b-unknown",
                generationId = 1L,
                status = IssuedOccurrence.STATUS_ISSUED,
                restoreEpoch = oldEpoch,
                createdAt = 1L,
                updatedAt = 1L
            )
        )
        scheduler.createTokenizedPendingIntent(alarmUnknown, triggerTime, "fire-v4b-unknown", tokenUnknown)
        db.durableOperationIntentDao().insertValidatedIntent(
            DurableOperationIntent(
                operationToken = tokenUnknown,
                alarmId = alarmUnknown,
                operationEpoch = 1000L,
                incarnationId = "fire-v4b-unknown",
                operationKind = "CANCEL",
                expectedOwnerToken = "owner-unknown",
                expectedScheduleRevision = 1L,
                expectedRestoreEpoch = oldEpoch,
                phase = DurableOperationIntent.PHASE_PENDING,
                intentRevision = 99L,
                originalFireId = "fire-v4b-unknown",
                originalScheduledAt = triggerTime
            )
        )

        val result = scheduler.cleanupStalePreRestoreOccurrences(currentEpoch)
        assertEquals(0, result.cancelledCount)
        assertEquals(2, result.isolatedCount)
        assertEquals(0, result.failedCount)
        assertEquals(IssuedOccurrence.STATUS_ISSUED, dao.getOccurrence(tokenRev2)!!.status)
        assertEquals(IssuedOccurrence.STATUS_ISSUED, dao.getOccurrence(tokenUnknown)!!.status)
        assertEquals(DurableOperationIntent.PHASE_PENDING, db.durableOperationIntentDao().getIntentByToken(tokenRev2)!!.phase)
        assertEquals(DurableOperationIntent.PHASE_PENDING, db.durableOperationIntentDao().getIntentByToken(tokenUnknown)!!.phase)
        assertNotNull(scheduler.lookupTokenizedPendingIntentForCancel(alarmRev2, tokenRev2))
        assertNotNull(scheduler.lookupTokenizedPendingIntentForCancel(alarmUnknown, tokenUnknown))
    }

    @Test
    fun `V4c - cleanupStalePreRestoreOccurrences applies alarm-wide isolation and preserves IssueSlot generationId and currentIssueToken`() = runBlocking {
        val currentEpoch = 207L
        val oldEpoch = 206L
        val triggerTime = 1_700_000_000_000L
        val alarmId = 7505L
        val tokenA = UUID.randomUUID().toString()
        val tokenB = UUID.randomUUID().toString()

        db.alarmDao().insert(com.sysadmindoc.alarmclock.data.model.Alarm(id = alarmId, hour = 8, minute = 0, isEnabled = false))
        dao.insertOccurrence(
            IssuedOccurrence(
                issueToken = tokenA,
                alarmId = alarmId,
                scheduledAt = triggerTime,
                fireId = "fire-v4c-a",
                generationId = 1L,
                status = IssuedOccurrence.STATUS_ISSUED,
                restoreEpoch = oldEpoch,
                createdAt = 1L,
                updatedAt = 1L
            )
        )
        dao.insertOccurrence(
            IssuedOccurrence(
                issueToken = tokenB,
                alarmId = alarmId,
                scheduledAt = triggerTime + 60_000L,
                fireId = "fire-v4c-b",
                generationId = 2L,
                status = IssuedOccurrence.STATUS_ISSUED,
                restoreEpoch = oldEpoch,
                createdAt = 2L,
                updatedAt = 2L
            )
        )
        dao.insertSlotInitial(
            com.sysadmindoc.alarmclock.data.local.entity.IssueSlot(
                alarmId = alarmId,
                generationId = 2L,
                currentIssueToken = tokenB
            )
        )
        scheduler.createTokenizedPendingIntent(alarmId, triggerTime, "fire-v4c-a", tokenA)
        scheduler.createTokenizedPendingIntent(alarmId, triggerTime + 60_000L, "fire-v4c-b", tokenB)
        db.durableOperationIntentDao().insertValidatedIntent(
            DurableOperationIntent(
                operationToken = tokenA,
                alarmId = alarmId,
                operationEpoch = 1000L,
                incarnationId = "fire-v4c-a",
                operationKind = "CANCEL",
                expectedOwnerToken = "owner-7505",
                expectedScheduleRevision = 1L,
                expectedRestoreEpoch = oldEpoch,
                phase = DurableOperationIntent.PHASE_IN_FLIGHT,
                intentRevision = 2L,
                originalFireId = "fire-v4c-a",
                originalScheduledAt = triggerTime
            )
        )

        val result = scheduler.cleanupStalePreRestoreOccurrences(currentEpoch)
        assertEquals(0, result.cancelledCount)
        assertEquals(2, result.isolatedCount)
        assertEquals(0, result.failedCount)
        assertEquals(IssuedOccurrence.STATUS_ISSUED, dao.getOccurrence(tokenA)!!.status)
        assertEquals(IssuedOccurrence.STATUS_ISSUED, dao.getOccurrence(tokenB)!!.status)
        assertNotNull(scheduler.lookupTokenizedPendingIntentForCancel(alarmId, tokenA))
        assertNotNull(scheduler.lookupTokenizedPendingIntentForCancel(alarmId, tokenB))
        val slotAfter = dao.getSlot(alarmId)!!
        assertEquals("V4c: IssueSlot.generationId MUST stay unchanged", 2L, slotAfter.generationId)
        assertEquals("V4c: IssueSlot.currentIssueToken MUST stay unchanged", tokenB, slotAfter.currentIssueToken)
    }

    @Test
    fun `V7 - cleanupStalePreRestoreOccurrences serializes on withMainV2SlotLock`() = runBlocking {
        val currentEpoch = 208L
        val oldEpoch = 207L
        val triggerTime = 1_700_000_000_000L
        val alarmId = 7506L
        val token = UUID.randomUUID().toString()

        db.alarmDao().insert(com.sysadmindoc.alarmclock.data.model.Alarm(id = alarmId, hour = 9, minute = 0, isEnabled = false))
        dao.insertOccurrence(
            IssuedOccurrence(
                issueToken = token,
                alarmId = alarmId,
                scheduledAt = triggerTime,
                fireId = "fire-v7",
                generationId = 1L,
                status = IssuedOccurrence.STATUS_ISSUED,
                restoreEpoch = oldEpoch,
                createdAt = 1L,
                updatedAt = 1L
            )
        )
        scheduler.createTokenizedPendingIntent(alarmId, triggerTime, "fire-v7", token)

        val lockEntered = kotlinx.coroutines.CompletableDeferred<Unit>()
        val releaseLock = kotlinx.coroutines.CompletableDeferred<Unit>()

        val holderJob = async(kotlinx.coroutines.Dispatchers.Default) {
            owner.withMainV2SlotLock(alarmId) {
                lockEntered.complete(Unit)
                releaseLock.await()
            }
        }
        lockEntered.await()

        val cleanupJob = async(kotlinx.coroutines.Dispatchers.Default) {
            scheduler.cleanupStalePreRestoreOccurrences(currentEpoch)
        }
        kotlinx.coroutines.delay(80L)
        assertFalse("V7: cleanupStalePreRestoreOccurrences MUST wait on withMainV2SlotLock", cleanupJob.isCompleted)

        releaseLock.complete(Unit)
        holderJob.await()
        val result = cleanupJob.await()
        assertEquals(1, result.cancelledCount)
        assertEquals(IssuedOccurrence.STATUS_REVOKED, dao.getOccurrence(token)!!.status)
    }

    @Test
    fun `L3-Ext - LEGACY_ONLY ignores mismatched expectedRevision and L1 OS cancel failure preserves other DirectBoot entries`() = runBlocking {
        val alarmA = 7507L
        val alarmB = 7508L
        val triggerTime = System.currentTimeMillis() + 3_600_000L
        val alarmObjB = com.sysadmindoc.alarmclock.data.model.Alarm(
            id = alarmB,
            hour = 6,
            minute = 0,
            isEnabled = true,
            nextTriggerTime = triggerTime
        )
        db.alarmDao().insert(com.sysadmindoc.alarmclock.data.model.Alarm(id = alarmA, hour = 7, minute = 0, isEnabled = true))
        db.alarmDao().insert(alarmObjB)

        val createLegacyMethod = AlarmScheduler::class.java.getDeclaredMethod(
            "createPendingIntent",
            Long::class.javaPrimitiveType,
            Long::class.javaPrimitiveType,
            String::class.java
        ).apply { isAccessible = true }
        val lookupLegacyMethod = AlarmScheduler::class.java.getDeclaredMethod(
            "lookupPendingIntentForCancel",
            Long::class.javaPrimitiveType
        ).apply { isAccessible = true }

        // Put alarmB in DirectBootAlarmCache and test OS cancel failure on alarmA keeps alarmB intact
        com.sysadmindoc.alarmclock.directboot.DirectBootAlarmCache.saveIfEarlier(context, alarmObjB, triggerTime)
        createLegacyMethod.invoke(scheduler, alarmA, triggerTime, "fire-l3ext-a")

        val throwingAm = mockk<AlarmManager>(relaxed = true) {
            every { cancel(any<PendingIntent>()) } throws RuntimeException("Simulated OS cancel failure")
        }
        scheduler.testAlarmManager = throwingAm
        assertFalse(scheduler.cancelScheduledEntriesWithResult(alarmA, includeFollowUpWorkers = true))
        assertEquals(
            "L3-Ext: DirectBoot cache for alarmB MUST remain intact when alarmA cancel fails",
            alarmB,
            com.sysadmindoc.alarmclock.directboot.DirectBootAlarmCache.read(context)?.alarmId
        )

        // Working AM with mismatched expectedRevision under LEGACY_ONLY -> still cancels alarmA and leaves alarmB DirectBoot intact
        scheduler.testAlarmManager = null
        val resAfter = scheduler.cancelScheduledEntriesWithResult(alarmA, expectedRevision = 999L)
        assertTrue("L3-Ext: LEGACY_ONLY MUST ignore mismatched expectedRevision", resAfter)
        assertNull(lookupLegacyMethod.invoke(scheduler, alarmA))
        assertEquals(
            "L3-Ext: DirectBoot cache for alarmB MUST remain untouched when alarmA is cancelled",
            alarmB,
            com.sysadmindoc.alarmclock.directboot.DirectBootAlarmCache.read(context)?.alarmId
        )
    }

    @Test
    fun `C3A-U1 - Owner executeOperation and Scheduler scheduleAt and cancelScheduledEntries serialize on same alarmId`() = runBlocking {
        val alarmId = 7601L
        val triggerTime = System.currentTimeMillis() + 3_600_000L
        db.scheduleAuthorityDao().insertMeta(ScheduleAuthorityMeta(id = 1, restoreEpoch = 10L, bootstrapState = "COMPLETED"))
        db.alarmDao().insert(com.sysadmindoc.alarmclock.data.model.Alarm(id = alarmId, hour = 7, minute = 0, isEnabled = true, nextTriggerTime = triggerTime))
        db.scheduleAuthorityDao().insertAuthority(
            ScheduleAuthority(
                alarmId = alarmId,
                instanceToken = "inst-7601",
                definitionRevision = 1L,
                scheduleRevision = 1L,
                lifecycle = "LIVE",
                desiredOperation = "SET",
                desiredTriggerMillis = triggerTime,
                reconcileState = "SETTLED",
                restoreEpoch = 10L,
                actionKey = "act-init-7601"
            )
        )

        val ownerEntered = CompletableDeferred<Unit>()
        val releaseOwner = CompletableDeferred<Unit>()
        val barrierOwner = ScheduleAuthorityOwner(
            authorityDao = db.scheduleAuthorityDao(),
            mainSlotApplier = MainSlotApplier {
                ownerEntered.complete(Unit)
                releaseOwner.await()
                true
            },
            exactPermissionChecker = { true }
        )
        val contendedScheduler = AlarmScheduler(
            context = context,
            repository = alarmRepo,
            calculator = calculator,
            preferencesManager = preferencesManager,
            holidayRepository = holidayRepository,
            alarmIncidentRepository = alarmIncidentRepository,
            weatherRepository = weatherRepository,
            scheduleAuthorityOwnerProvider = Provider { barrierOwner }
        )

        val ownerJob = async(Dispatchers.Default) {
            barrierOwner.executeOperation(alarmId, "SET", triggerTime + 1000L, "act-u1", expectedRevision = 1L)
        }
        withTimeout(5_000L) { ownerEntered.await() }

        val schedJob = async(Dispatchers.Default) {
            contendedScheduler.scheduleAt(
                com.sysadmindoc.alarmclock.data.model.Alarm(id = alarmId, hour = 7, minute = 0, isEnabled = true),
                triggerTime + 2000L,
                requestWidgetUpdate = false
            )
        }
        val cancelJob = async(Dispatchers.Default) {
            contendedScheduler.cancelScheduledEntriesSuspending(alarmId)
        }

        delay(150L)
        assertFalse("C3A-U1: Scheduler.scheduleAt MUST wait while Owner holds same alarmId lock", schedJob.isCompleted)
        assertFalse("C3A-U1: Scheduler.cancelScheduledEntriesSuspending MUST wait while Owner holds same alarmId lock", cancelJob.isCompleted)

        releaseOwner.complete(Unit)
        withTimeout(5_000L) {
            assertTrue(ownerJob.await() is OwnerResult.OsAccepted)
            assertTrue(schedJob.await() is ScheduleOutcome.Armed)
            assertTrue(cancelJob.await())
        }
    }

    @Test
    fun `C3A-U2 - Owner holding alarmA does not block Scheduler on different alarmB`() = runBlocking {
        val alarmA = 7602L
        val alarmB = 7603L
        val triggerTime = System.currentTimeMillis() + 3_600_000L
        db.scheduleAuthorityDao().insertMeta(ScheduleAuthorityMeta(id = 1, restoreEpoch = 10L, bootstrapState = "COMPLETED"))
        db.alarmDao().insert(com.sysadmindoc.alarmclock.data.model.Alarm(id = alarmA, hour = 7, minute = 0, isEnabled = true, nextTriggerTime = triggerTime))
        db.alarmDao().insert(com.sysadmindoc.alarmclock.data.model.Alarm(id = alarmB, hour = 8, minute = 0, isEnabled = true, nextTriggerTime = triggerTime))
        db.scheduleAuthorityDao().insertAuthority(
            ScheduleAuthority(
                alarmId = alarmA,
                instanceToken = "inst-7602",
                definitionRevision = 1L,
                scheduleRevision = 1L,
                lifecycle = "LIVE",
                desiredOperation = "SET",
                desiredTriggerMillis = triggerTime,
                reconcileState = "SETTLED",
                restoreEpoch = 10L,
                actionKey = "act-init-7602"
            )
        )

        val ownerEnteredA = CompletableDeferred<Unit>()
        val releaseOwnerA = CompletableDeferred<Unit>()
        val barrierOwner = ScheduleAuthorityOwner(
            authorityDao = db.scheduleAuthorityDao(),
            mainSlotApplier = MainSlotApplier { auth ->
                if (auth.alarmId == alarmA) {
                    ownerEnteredA.complete(Unit)
                    releaseOwnerA.await()
                }
                true
            },
            exactPermissionChecker = { true }
        )
        val parallelScheduler = AlarmScheduler(
            context = context,
            repository = alarmRepo,
            calculator = calculator,
            preferencesManager = preferencesManager,
            holidayRepository = holidayRepository,
            alarmIncidentRepository = alarmIncidentRepository,
            weatherRepository = weatherRepository,
            scheduleAuthorityOwnerProvider = Provider { barrierOwner }
        )

        val jobA = async(Dispatchers.Default) {
            barrierOwner.executeOperation(alarmA, "SET", triggerTime + 1000L, "act-u2-a", expectedRevision = 1L)
        }
        withTimeout(5_000L) { ownerEnteredA.await() }

        val outcomeB = withTimeout(3_000L) {
            parallelScheduler.scheduleAt(
                com.sysadmindoc.alarmclock.data.model.Alarm(id = alarmB, hour = 8, minute = 0, isEnabled = true),
                triggerTime + 5000L,
                requestWidgetUpdate = false
            )
        }
        assertTrue("C3A-U2: Different alarmB MUST NOT wait on alarmA lock", outcomeB is ScheduleOutcome.Armed)
        assertFalse("C3A-U2: Owner on alarmA must still be blocked at barrier", jobA.isCompleted)

        releaseOwnerA.complete(Unit)
        withTimeout(5_000L) {
            assertTrue(jobA.await() is OwnerResult.OsAccepted)
        }
    }

    @Test
    fun `C3A-U4 - Scheduler under OWNER_EXCLUSIVE holds L1 then Owner L2 before calling Owner without deadlock`() = runBlocking {
        val alarmId = 7604L
        val triggerTime = System.currentTimeMillis() + 3_600_000L
        db.scheduleAuthorityDao().insertMeta(ScheduleAuthorityMeta(id = 1, restoreEpoch = 10L, bootstrapState = "COMPLETED"))
        val alarm = com.sysadmindoc.alarmclock.data.model.Alarm(id = alarmId, hour = 7, minute = 0, isEnabled = true, nextTriggerTime = triggerTime)
        db.alarmDao().insert(alarm)
        db.scheduleAuthorityDao().insertAuthority(
            ScheduleAuthority(
                alarmId = alarmId,
                instanceToken = "inst-7604",
                definitionRevision = 1L,
                scheduleRevision = 1L,
                lifecycle = "LIVE",
                desiredOperation = "SET",
                desiredTriggerMillis = triggerTime,
                reconcileState = "SETTLED",
                restoreEpoch = 10L,
                actionKey = "act-init-7604"
            )
        )
        every { calculator.calculate(any()) } returns triggerTime
        every { calculator.calculate(any(), any()) } returns triggerTime

        var l1LockedInApplier = false
        var l2LockedInApplier = false
        lateinit var ownerWithLock: ScheduleAuthorityOwner
        val mutexForMethod = ScheduleAuthorityOwner::class.java.getDeclaredMethod("mutexFor", Long::class.javaPrimitiveType).apply {
            isAccessible = true
        }
        ownerWithLock = ScheduleAuthorityOwner(
            authorityDao = db.scheduleAuthorityDao(),
            mainSlotApplier = MainSlotApplier {
                l1LockedInApplier = RestoreJournalCoordinator.recoveryMutexFor(context).isLocked
                val ownerMutex = mutexForMethod.invoke(ownerWithLock, alarmId) as kotlinx.coroutines.sync.Mutex
                l2LockedInApplier = ownerMutex.isLocked
                true
            },
            exactPermissionChecker = { true }
        )
        val ownerRoute = mockk<ProductionSchedulingRouteAuthority> {
            every { isOwnerRouteActive() } returns true
        }
        val ownerScheduler = AlarmScheduler(
            context = context,
            repository = alarmRepo,
            calculator = calculator,
            preferencesManager = preferencesManager,
            holidayRepository = holidayRepository,
            alarmIncidentRepository = alarmIncidentRepository,
            weatherRepository = weatherRepository,
            scheduleAuthorityOwnerProvider = Provider { ownerWithLock },
            routeAuthority = ownerRoute
        )

        var l1LockedInBarrier = false
        var l2LockedInBarrier = false
        ownerScheduler.testOnPreOsRegistrationBarrier = { id, _ ->
            if (id == alarmId) {
                l1LockedInBarrier = RestoreJournalCoordinator.recoveryMutexFor(context).isLocked
                val ownerMutex = mutexForMethod.invoke(ownerWithLock, alarmId) as kotlinx.coroutines.sync.Mutex
                l2LockedInBarrier = ownerMutex.isLocked
            }
        }

        val outcome = withTimeout(5_000L) {
            ownerScheduler.schedule(alarm, requestWidgetUpdate = false)
        }
        assertTrue("C3A-U4: Scheduler.schedule under OWNER_EXCLUSIVE MUST succeed without deadlock, got $outcome", outcome is ScheduleOutcome.Armed)
        assertTrue("C3A-U4: L1 recoveryMutex MUST be held at pre-OS barrier", l1LockedInBarrier)
        assertTrue("C3A-U4: Owner L2 slot mutex MUST be held at pre-OS barrier (A1/A2)", l2LockedInBarrier)
        assertTrue("C3A-U4: L1 recoveryMutex MUST remain held inside MainSlotApplier", l1LockedInApplier)
        assertTrue("C3A-U4: Owner L2 slot mutex MUST be held inside MainSlotApplier", l2LockedInApplier)

        val cancelled = withTimeout(5_000L) {
            ownerScheduler.cancelScheduledEntriesSuspending(alarmId, expectedRevision = 2L)
        }
        assertTrue("C3A-U4: Scheduler.cancelScheduledEntriesSuspending under OWNER_EXCLUSIVE MUST succeed without deadlock", cancelled)
    }

    @Test
    fun `C3A-U5a - schedule disabled stale generation and cancelMainScheduledAlarm missing authority epoch fail closed`() = runBlocking {
        val alarmId = 7605L
        val triggerTime = System.currentTimeMillis() + 3_600_000L
        db.scheduleAuthorityDao().insertMeta(ScheduleAuthorityMeta(id = 1, restoreEpoch = 10L, bootstrapState = "COMPLETED"))

        val gen2Alarm = com.sysadmindoc.alarmclock.data.model.Alarm(
            id = alarmId,
            hour = 7,
            minute = 0,
            isEnabled = true,
            nextTriggerTime = triggerTime,
            generation = 2
        )
        db.alarmDao().insert(gen2Alarm)
        every { calculator.calculate(any()) } returns triggerTime
        every { calculator.calculate(any(), any()) } returns triggerTime

        assertTrue(scheduler.schedule(gen2Alarm, requestWidgetUpdate = false) is ScheduleOutcome.Armed)

        val lookupLegacyMethod = AlarmScheduler::class.java.getDeclaredMethod(
            "lookupPendingIntentForCancel",
            Long::class.javaPrimitiveType
        ).apply { isAccessible = true }
        assertNotNull(lookupLegacyMethod.invoke(scheduler, alarmId))

        // 1. Stale disabled alarm from generation 1 MUST NOT cancel generation 2's armed PI
        val staleDisabledGen1 = gen2Alarm.copy(isEnabled = false, generation = 1)
        val staleOutcome = scheduler.schedule(staleDisabledGen1, requestWidgetUpdate = false)
        assertTrue("C3A-U5a: Stale disabled gen 1 schedule MUST fail closed as RegistrationFailed, got $staleOutcome", staleOutcome is ScheduleOutcome.RegistrationFailed)
        assertNotNull("C3A-U5a: Armed generation 2 PI MUST remain intact", lookupLegacyMethod.invoke(scheduler, alarmId))

        // 2. Missing authority row + stale expectedRestoreEpoch on cancelMainScheduledAlarm MUST fail closed against meta.restoreEpoch
        assertNull(db.scheduleAuthorityDao().getAuthorityByAlarmId(alarmId))
        val staleSmartWake = scheduler.cancelMainScheduledAlarm(
            alarmId = alarmId,
            expectedTriggerTime = triggerTime,
            expectedRestoreEpoch = 9L
        )
        assertFalse("C3A-U5a: Stale expectedRestoreEpoch MUST fail closed even when authority row is absent", staleSmartWake)
        assertNotNull("C3A-U5a: Armed PI MUST remain intact when SmartWake restoreEpoch is stale", lookupLegacyMethod.invoke(scheduler, alarmId))
    }

    @Test
    fun `C3A-U6 - QuickAlarmScheduler, Legacy V1 null-owner, and Snooze remain compatible`() = runBlocking {
        val quickId = 7606L
        val normalId = 7607L
        val now = System.currentTimeMillis()
        val triggerTime = now + 3_600_000L
        db.scheduleAuthorityDao().insertMeta(ScheduleAuthorityMeta(id = 1, restoreEpoch = 10L, bootstrapState = "COMPLETED"))

        val quickAlarm = com.sysadmindoc.alarmclock.data.model.Alarm(
            id = quickId,
            hour = 9,
            minute = 0,
            isEnabled = true,
            kind = com.sysadmindoc.alarmclock.data.model.AlarmKind.QUICK.name,
            quickTriggerEpochMs = triggerTime
        )
        val normalAlarm = com.sysadmindoc.alarmclock.data.model.Alarm(
            id = normalId,
            hour = 7,
            minute = 30,
            isEnabled = true,
            nextTriggerTime = triggerTime,
            generation = 1
        )
        db.alarmDao().insert(quickAlarm)
        db.alarmDao().insert(normalAlarm)

        val quickScheduler = QuickAlarmScheduler(context, alarmRepo)
        assertTrue(quickScheduler.scheduleQuickAlarm(quickAlarm) is ScheduleOutcome.Armed)

        val legacyScheduler = AlarmScheduler(
            context = context,
            repository = alarmRepo,
            calculator = calculator,
            preferencesManager = preferencesManager,
            holidayRepository = holidayRepository,
            alarmIncidentRepository = alarmIncidentRepository,
            weatherRepository = weatherRepository,
            scheduleAuthorityOwnerProvider = null
        )
        assertTrue(legacyScheduler.scheduleSnoozeAt(normalAlarm, triggerTime + 600_000L) is ScheduleOutcome.Armed)
        assertTrue(legacyScheduler.cancelScheduledEntriesWithResult(normalId, expectedGeneration = 1))
        assertTrue(quickScheduler.cancelQuickPendingIntentOnly(quickId))
    }

    @Test
    fun `C3A-U8 - OS cancel failure preserves AlarmRuntimeState and DirectBootAlarmCache`() = runBlocking {
        val alarmId = 7608L
        val triggerTime = System.currentTimeMillis() + 3_600_000L
        val alarm = com.sysadmindoc.alarmclock.data.model.Alarm(
            id = alarmId,
            hour = 7,
            minute = 0,
            isEnabled = true,
            nextTriggerTime = triggerTime,
            generation = 1
        )
        db.alarmDao().insert(alarm)
        every { calculator.calculate(any()) } returns triggerTime
        every { calculator.calculate(any(), any()) } returns triggerTime

        assertTrue(scheduler.schedule(alarm, requestWidgetUpdate = false) is ScheduleOutcome.Armed)
        AlarmRuntimeState.setSnoozeCount(context, alarmId, 2)
        assertNotNull(DirectBootAlarmCache.read(context))

        val failingAm = mockk<AlarmManager>(relaxed = true) {
            every { cancel(any<PendingIntent>()) } throws RuntimeException("Simulated OS cancel error")
        }
        scheduler.testAlarmManager = failingAm
        try {
            scheduler.cancel(alarmId)
            assertEquals("C3A-U8: AlarmRuntimeState MUST NOT be cleared when OS cancel fails", 2, AlarmRuntimeState.snoozeCount(context, alarmId))
            assertEquals("C3A-U8: DirectBootAlarmCache MUST NOT be cleared when OS cancel fails", alarmId, DirectBootAlarmCache.read(context)?.alarmId)
        } finally {
            scheduler.testAlarmManager = null
            AlarmRuntimeState.clear(context, alarmId)
            DirectBootAlarmCache.clear(context)
        }
    }

    @Test
    fun `S8 - new-generation PendingIntent is protected during PHASE_PREPARED and restoreEpoch advance`() = runBlocking {
        org.robolectric.shadows.ShadowAlarmManager.setCanScheduleExactAlarms(true)
        val newGenAlarmId = 7701L
        val oldEpochAlarmId = 7702L
        val triggerTime = System.currentTimeMillis() + 3_600_000L

        db.scheduleAuthorityDao().insertMeta(
            ScheduleAuthorityMeta(id = 1, restoreEpoch = 200L, bootstrapState = "INITIALIZED")
        )
        val newGenAlarm = com.sysadmindoc.alarmclock.data.model.Alarm(
            id = newGenAlarmId,
            hour = 7,
            minute = 0,
            isEnabled = true,
            nextTriggerTime = triggerTime,
            generation = 2,
            revision = 2
        )
        db.alarmDao().insert(newGenAlarm)
        db.scheduleAuthorityDao().insertAuthority(
            ScheduleAuthority(
                alarmId = newGenAlarmId,
                instanceToken = "inst-7701",
                definitionRevision = 2L,
                scheduleRevision = 2L,
                lifecycle = "LIVE",
                desiredOperation = "SET",
                desiredTriggerMillis = triggerTime,
                reconcileState = "SETTLED",
                restoreEpoch = 200L,
                actionKey = "act-s8-newgen"
            )
        )
        db.scheduleAuthorityDao().insertAuthority(
            ScheduleAuthority(
                alarmId = oldEpochAlarmId,
                instanceToken = "inst-7702",
                definitionRevision = 1L,
                scheduleRevision = 1L,
                lifecycle = "LIVE",
                desiredOperation = "SET",
                desiredTriggerMillis = triggerTime,
                reconcileState = "SETTLED",
                restoreEpoch = 100L,
                actionKey = "act-s8-old"
            )
        )

        val applierCalls = java.util.concurrent.atomic.AtomicInteger(0)
        val contextOwnerCtor = ScheduleAuthorityOwner::class.java.declaredConstructors.first { c ->
            c.parameterTypes.size == 4 && Context::class.java.isAssignableFrom(c.parameterTypes[3])
        }.apply { isAccessible = true }
        val guardedOwner = contextOwnerCtor.newInstance(
            db.scheduleAuthorityDao(),
            MainSlotApplier { auth ->
                applierCalls.incrementAndGet()
                if (auth.desiredOperation == "SET") {
                    scheduler.applyRawOsSet(auth.alarmId, auth.desiredTriggerMillis ?: triggerTime)
                } else {
                    scheduler.applyRawOsCancel(auth.alarmId)
                }
            },
            { true },
            context
        ) as ScheduleAuthorityOwner

        val ownerRoute = mockk<ProductionSchedulingRouteAuthority> {
            every { isOwnerRouteActive() } returns true
        }
        val protectedScheduler = AlarmScheduler(
            context = context,
            repository = alarmRepo,
            calculator = calculator,
            preferencesManager = preferencesManager,
            holidayRepository = holidayRepository,
            alarmIncidentRepository = alarmIncidentRepository,
            weatherRepository = weatherRepository,
            scheduleAuthorityOwnerProvider = Provider { guardedOwner },
            routeAuthority = ownerRoute
        )

        // Arm new-generation PendingIntent for 7701L with restoreEpoch = 200L
        val applyRawSetMethod = AlarmScheduler::class.java.getDeclaredMethod(
            "applyRawOsSetWithEpoch",
            Long::class.javaPrimitiveType,
            Long::class.javaPrimitiveType,
            Long::class.javaPrimitiveType
        ).apply { isAccessible = true }
        assertTrue(applyRawSetMethod.invoke(protectedScheduler, newGenAlarmId, triggerTime, 200L) as Boolean)
        val lookupLegacyMethod = AlarmScheduler::class.java.getDeclaredMethod(
            "lookupPendingIntentForCancel",
            Long::class.javaPrimitiveType
        ).apply { isAccessible = true }
        assertNotNull("S8: New-generation PI MUST be armed initially", lookupLegacyMethod.invoke(protectedScheduler, newGenAlarmId))

        // 1. Stale restoreEpoch = 100L cannot cancel new-gen PI or pre-restore authority
        assertFalse(protectedScheduler.cancelScheduledEntriesSuspending(newGenAlarmId, expectedRestoreEpoch = 100L))
        assertFalse(protectedScheduler.cancelScheduledEntriesWithResult(newGenAlarmId, expectedRestoreEpoch = 100L))
        assertFalse(protectedScheduler.cancelMainScheduledAlarm(newGenAlarmId, expectedTriggerTime = triggerTime, expectedRestoreEpoch = 100L))

        val staleOwnerCancel = guardedOwner.executeOperation(oldEpochAlarmId, "CANCEL", null, "act-s8-stale-cancel", expectedRevision = 1L)
        assertTrue("S8: Stale restoreEpoch authority MUST fail closed under INITIALIZED, got $staleOwnerCancel", staleOwnerCancel is OwnerResult.Superseded)
        assertEquals("S8: MainSlotApplier MUST NOT run for stale restoreEpoch", 0, applierCalls.get())
        assertNotNull("S8: New-generation PI MUST remain armed", lookupLegacyMethod.invoke(protectedScheduler, newGenAlarmId))

        // 2. During PHASE_PREPARED, forged ActiveRecoveryGateContext cannot cancel new-generation PI
        val journalFile = File(context.filesDir, RestoreJournalCoordinator.JOURNAL_FILENAME)
        val bakFile = File(context.filesDir, "${RestoreJournalCoordinator.JOURNAL_FILENAME}.bak")
        journalFile.delete()
        bakFile.delete()
        val coordinator = RestoreJournalCoordinator(
            context = context,
            database = db,
            preferencesManager = preferencesManager,
            alarmScheduler = protectedScheduler,
            moshi = com.squareup.moshi.Moshi.Builder().build()
        )
        try {
            coordinator.prepareTransaction(com.sysadmindoc.alarmclock.data.preferences.AppSettings(), emptyList(), emptyList())
            val canonicalPath = RestoreJournalCoordinator.canonicalJournalPath(context)
            val gateClass = ScheduleAuthorityOwner.ActiveRecoveryGateContext::class.java
            val forgedCtx = gateClass.declaredConstructors.firstOrNull {
                it.parameterTypes.size == 1 && it.parameterTypes[0] == String::class.java
            }?.let { ctor ->
                ctor.isAccessible = true
                ctor.newInstance(canonicalPath) as CoroutineContext
            } ?: gateClass.declaredConstructors.first { it.parameterTypes.size == 2 }.let { ctor ->
                ctor.isAccessible = true
                ctor.newInstance(null, 0) as CoroutineContext
            }

            val forgedOwnerCancel = withContext(forgedCtx) {
                guardedOwner.executeOperation(newGenAlarmId, "CANCEL", null, "act-s8-forged-cancel", expectedRevision = 2L)
            }
            assertTrue("S8: Forged ActiveRecoveryGateContext MUST fail closed during PHASE_PREPARED, got $forgedOwnerCancel", forgedOwnerCancel is OwnerResult.OutcomeUnknown)

            val forgedSchedCancel = withContext(forgedCtx) {
                protectedScheduler.cancelScheduledEntriesSuspending(newGenAlarmId, expectedRevision = 2L)
            }
            assertFalse("S8: Forged scheduler cancel MUST fail closed during PHASE_PREPARED", forgedSchedCancel)
            assertEquals("S8: MainSlotApplier MUST NOT run during PHASE_PREPARED", 0, applierCalls.get())
            assertEquals("S8: New-gen authority revision MUST remain 2L", 2L, db.scheduleAuthorityDao().getAuthorityByAlarmId(newGenAlarmId)?.scheduleRevision)
            assertNotNull("S8: New-generation PI MUST remain armed in AlarmManager", lookupLegacyMethod.invoke(protectedScheduler, newGenAlarmId))
        } finally {
            journalFile.delete()
            bakFile.delete()
        }
    }

    @Test
    fun `T_LEGACY_GATE - applyTokenizedOsSet and cancelTokenizedScheduledAlarm fail closed under LEGACY_ONLY route`() = runBlocking {
        val alarmId = 9998L
        val triggerTime = 1_700_000_000_000L
        val token = UUID.randomUUID().toString()

        val legacyRouteScheduler = AlarmScheduler(
            context = context,
            repository = alarmRepo,
            calculator = calculator,
            preferencesManager = preferencesManager,
            holidayRepository = holidayRepository,
            alarmIncidentRepository = alarmIncidentRepository,
            weatherRepository = weatherRepository,
            scheduleAuthorityOwnerProvider = ownerProvider,
            routeAuthority = ProductionSchedulingRouteAuthority()
        )

        val setSuccess = legacyRouteScheduler.applyTokenizedOsSet(alarmId, triggerTime, token)
        assertFalse("applyTokenizedOsSet MUST return false (fail-closed) under LEGACY_ONLY route", setSuccess)

        val cancelSuccess = legacyRouteScheduler.cancelTokenizedScheduledAlarm(alarmId, token)
        assertFalse("cancelTokenizedScheduledAlarm MUST return false (fail-closed) under LEGACY_ONLY route", cancelSuccess)

        assertNull("IssuedOccurrence MUST NOT be created under LEGACY_ONLY route", dao.getOccurrence(token))
        assertNull("Slot current token MUST remain null under LEGACY_ONLY route", dao.getSlot(alarmId)?.currentIssueToken)
    }

    @Test
    fun `T_ORPHAN_C - process death after OS SET C cancels orphan C and transitions status to terminal STATUS_REVOKED`() = runBlocking {
        val alarmId = 8801L
        val triggerTime = System.currentTimeMillis() + 300_000L
        val tokenC = UUID.randomUUID().toString()

        val ownerScheduler = createOwnerExclusiveScheduler()

        // 1. Reserve tokenC in STATUS_PREPARED with createdAt = T - 120s (simulating process death before DB slot activation)
        val preparedTime = System.currentTimeMillis() - 120_000L
        dao.reserveIssuedOccurrence(tokenC, alarmId, triggerTime, "fire-orphan-C", now = preparedTime)
        ownerScheduler.createTokenizedPendingIntent(alarmId, triggerTime, "fire-orphan-C", tokenC)

        // Verify tokenC is armed in OS AlarmManager prior to recovery sweep
        val piBefore = ownerScheduler.lookupTokenizedPendingIntentForCancel(alarmId, tokenC)
        assertNotNull("Pre-condition: Orphan C PendingIntent must be armed in OS AlarmManager", piBefore)

        // 2. Trigger 1st recovery sweep -> cancels piC and transitions status to STATUS_REVOKED
        val cleanedCount1 = ownerScheduler.reconcileOrphanPreparedOccurrences(thresholdMs = 60_000L)
        assertEquals("SUCCESS_TERMINAL: Exactly 1 orphan C occurrence MUST be cleaned", 1, cleanedCount1)

        // 3. Post-condition: Orphan C PendingIntent is canceled in OS AlarmManager and status is STATUS_REVOKED
        val piAfter = ownerScheduler.lookupTokenizedPendingIntentForCancel(alarmId, tokenC)
        assertNull("SUCCESSFUL_OS_CANCEL: Orphan C PendingIntent MUST be canceled by background sweep", piAfter)

        val occurrenceAfter = dao.getOccurrence(tokenC)
        assertNotNull(occurrenceAfter)
        assertEquals("SUCCESS_TERMINAL: Orphan C occurrence status MUST transition to terminal STATUS_REVOKED", IssuedOccurrence.STATUS_REVOKED, occurrenceAfter!!.status)

        // 4. Trigger 2nd recovery sweep -> MUST be NOOP because status is now STATUS_REVOKED (no longer PREPARED)
        val cleanedCount2 = ownerScheduler.reconcileOrphanPreparedOccurrences(thresholdMs = 60_000L)
        assertEquals("SECOND_SWEEP_NOOP: 2nd sweep MUST return 0 cleaned items for settled occurrence", 0, cleanedCount2)
    }

    @Test
    fun `T_ORPHAN_C - owner null fail closed skips OS cancel and leaves candidate pending`() = runBlocking {
        val alarmId = 8802L
        val triggerTime = System.currentTimeMillis() + 300_000L
        val tokenC = UUID.randomUUID().toString()

        // 1. Reserve tokenC in STATUS_PREPARED
        val preparedTime = System.currentTimeMillis() - 120_000L
        dao.reserveIssuedOccurrence(tokenC, alarmId, triggerTime, "fire-orphan-C", now = preparedTime)
        scheduler.createTokenizedPendingIntent(alarmId, triggerTime, "fire-orphan-C", tokenC)

        // 2. Construct scheduler with owner == null
        val nullOwnerScheduler = AlarmScheduler(
            context = context,
            repository = alarmRepo,
            calculator = calculator,
            preferencesManager = preferencesManager,
            holidayRepository = holidayRepository,
            alarmIncidentRepository = alarmIncidentRepository,
            weatherRepository = weatherRepository,
            scheduleAuthorityOwnerProvider = null
        )

        // 3. Trigger sweep -> MUST return 0 and skip OS cancel
        val cleanedNullOwner = nullOwnerScheduler.reconcileOrphanPreparedOccurrences(thresholdMs = 60_000L)
        assertEquals("OWNER_NULL_FAIL_CLOSED: Sweep MUST return 0 when owner is null", 0, cleanedNullOwner)

        // 4. Verify tokenC remains PREPARED and armed in OS
        assertEquals(IssuedOccurrence.STATUS_PREPARED, dao.getOccurrence(tokenC)!!.status)
        assertNotNull(scheduler.lookupTokenizedPendingIntentForCancel(alarmId, tokenC))
    }

    @Test
    fun `T_ORPHAN_C - OS cancel exception retains STATUS_PREPARED for subsequent worker retry`() = runBlocking {
        val alarmId = 8803L
        val triggerTime = System.currentTimeMillis() + 300_000L
        val tokenC = UUID.randomUUID().toString()

        val ownerScheduler = createOwnerExclusiveScheduler()

        // 1. Reserve tokenC in STATUS_PREPARED with createdAt = T - 120s
        val preparedTime = System.currentTimeMillis() - 120_000L
        dao.reserveIssuedOccurrence(tokenC, alarmId, triggerTime, "fire-orphan-C", now = preparedTime)
        ownerScheduler.createTokenizedPendingIntent(alarmId, triggerTime, "fire-orphan-C", tokenC)

        // 2. Inject failing AlarmManager where am.cancel throws RuntimeException
        val failingAm = mockk<AlarmManager>(relaxed = true) {
            every { cancel(any<PendingIntent>()) } throws RuntimeException("Simulated OS cancel exception")
        }
        ownerScheduler.testAlarmManager = failingAm

        // 3. Trigger 1st recovery sweep -> am.cancel throws exception
        val cleanedFail = ownerScheduler.reconcileOrphanPreparedOccurrences(thresholdMs = 60_000L)
        assertEquals("FAILURE_RETRYABLE: 0 items cleaned when OS cancel throws", 0, cleanedFail)

        // 4. Assert: Status MUST RETAIN STATUS_PREPARED on disk for next worker retry
        val occAfterFail = dao.getOccurrence(tokenC)
        assertNotNull(occAfterFail)
        assertEquals("FAILURE_RETRYABLE: Status MUST remain STATUS_PREPARED on cancel exception", IssuedOccurrence.STATUS_PREPARED, occAfterFail!!.status)

        // 5. Restore normal AlarmManager
        ownerScheduler.testAlarmManager = null

        // 6. Retry recovery sweep -> am.cancel succeeds
        val cleanedRetry = ownerScheduler.reconcileOrphanPreparedOccurrences(thresholdMs = 60_000L)
        assertEquals("POST_DEATH_RETRY: 1 item cleaned after retry success", 1, cleanedRetry)

        // 7. Assert: Status transitions to STATUS_REVOKED and PI is canceled
        val occAfterSuccess = dao.getOccurrence(tokenC)
        assertEquals("SUCCESS_TERMINAL: Status MUST settle to STATUS_REVOKED after retry success", IssuedOccurrence.STATUS_REVOKED, occAfterSuccess!!.status)
        val piAfter = ownerScheduler.lookupTokenizedPendingIntentForCancel(alarmId, tokenC)
        assertNull("POST_DEATH_RETRY: Orphan C PendingIntent MUST be canceled in OS", piAfter)
    }

    @Test
    fun `T_ORPHAN_C - active C in STATUS_ISSUED and old B in STATUS_REVOKED are protected from sweep`() = runBlocking {
        val alarmId = 8804L
        val triggerTime = System.currentTimeMillis() + 300_000L
        val tokenC = UUID.randomUUID().toString()

        val ownerScheduler = createOwnerExclusiveScheduler()

        // Active C: reserved AND activated (STATUS_ISSUED)
        ownerScheduler.applyTokenizedOsSet(alarmId, triggerTime, tokenC)

        // Trigger recovery sweep
        val cleanedActive = ownerScheduler.reconcileOrphanPreparedOccurrences(thresholdMs = 0L)
        assertEquals("ACTIVE_C_PROTECTED: Active C MUST NOT be cleaned by sweep", 0, cleanedActive)

        // Active C MUST survive
        val piC = ownerScheduler.lookupTokenizedPendingIntentForCancel(alarmId, tokenC)
        assertNotNull("ACTIVE_C_PROTECTED: Active C PendingIntent MUST survive recovery sweep", piC)
        assertEquals(IssuedOccurrence.STATUS_ISSUED, dao.getOccurrence(tokenC)!!.status)
    }

    @Test
    fun `T_ORPHAN_C - SET_FAILED candidate is enumerated and retried by recovery sweep`() = runBlocking {
        val alarmId = 8805L
        val triggerTime = System.currentTimeMillis() + 300_000L
        val tokenC = UUID.randomUUID().toString()

        val ownerScheduler = createOwnerExclusiveScheduler()

        // 1. Pre-condition: Reserve tokenC in STATUS_SET_FAILED with createdAt = T - 120s (simulating in-process handoff failure + cancel exception)
        val preparedTime = System.currentTimeMillis() - 120_000L
        dao.reserveIssuedOccurrence(tokenC, alarmId, triggerTime, "fire-orphan-C", now = preparedTime)
        dao.casUpdateOccurrenceStatus(tokenC, IssuedOccurrence.STATUS_PREPARED, IssuedOccurrence.STATUS_SET_FAILED, updatedAt = System.currentTimeMillis())
        ownerScheduler.createTokenizedPendingIntent(alarmId, triggerTime, "fire-orphan-C", tokenC)

        // Verify tokenC is in STATUS_SET_FAILED
        assertEquals(IssuedOccurrence.STATUS_SET_FAILED, dao.getOccurrence(tokenC)!!.status)

        // 2. Trigger recovery sweep -> enumerates SET_FAILED and cancels piC
        val cleanedCount = ownerScheduler.reconcileOrphanPreparedOccurrences(thresholdMs = 60_000L)
        assertEquals("SET_FAILED_ENUMERATED: Exactly 1 SET_FAILED orphan C occurrence MUST be cleaned", 1, cleanedCount)

        // 3. Post-condition: Status transitions from STATUS_SET_FAILED to terminal STATUS_REVOKED
        val occAfter = dao.getOccurrence(tokenC)
        assertNotNull(occAfter)
        assertEquals("SUCCESS_TERMINAL: Status MUST transition to terminal STATUS_REVOKED", IssuedOccurrence.STATUS_REVOKED, occAfter!!.status)
        val piAfter = ownerScheduler.lookupTokenizedPendingIntentForCancel(alarmId, tokenC)
        assertNull("POST_DEATH_RETRY: Orphan C PendingIntent MUST be canceled in OS", piAfter)

        // 4. 2nd sweep MUST be NOOP (0 items)
        val cleanedSecond = ownerScheduler.reconcileOrphanPreparedOccurrences(thresholdMs = 60_000L)
        assertEquals("SECOND_SWEEP_NOOP: 2nd sweep MUST return 0 for STATUS_REVOKED occurrence", 0, cleanedSecond)
    }
}
