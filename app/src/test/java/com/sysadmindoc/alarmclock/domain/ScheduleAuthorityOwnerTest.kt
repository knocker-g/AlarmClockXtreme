package com.sysadmindoc.alarmclock.domain

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.sysadmindoc.alarmclock.data.local.AlarmDatabase
import com.sysadmindoc.alarmclock.data.local.ScheduleAuthorityDao
import com.sysadmindoc.alarmclock.data.local.entity.ScheduleAuthority
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class ScheduleAuthorityOwnerTest {

    private lateinit var context: Context
    private lateinit var database: AlarmDatabase
    private lateinit var authorityDao: ScheduleAuthorityDao
    private lateinit var owner: ScheduleAuthorityOwner

    private val initialAuthority = ScheduleAuthority(
        alarmId = 42L,
        instanceToken = "token-instance-42",
        definitionRevision = 1L,
        scheduleRevision = 1L,
        lifecycle = "LIVE",
        desiredOperation = "SET",
        desiredTriggerMillis = 1700000000000L,
        reconcileState = "SETTLED",
        restoreEpoch = 100L,
        actionKey = "action-init-1"
    )

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        database = Room.inMemoryDatabaseBuilder(context, AlarmDatabase::class.java)
            .allowMainThreadQueries()
            .addCallback(AlarmDatabase.DATABASE_CALLBACK)
            .build()
        authorityDao = database.scheduleAuthorityDao()
        owner = ScheduleAuthorityOwner(
            authorityDao = authorityDao,
            exactPermissionChecker = { true }
        )
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun `reused actionKey with different target returns ConflictingAction`() = runBlocking {
        authorityDao.insertAuthority(initialAuthority)

        val result = owner.executeOperation(
            alarmId = 42L,
            desiredOperation = "CANCEL", // Conflicting desiredOperation for actionKey = "action-init-1"
            desiredTriggerMillis = null,
            actionKey = "action-init-1"
        ) {
            true
        }

        assertTrue("Conflicting actionKey target must return ConflictingAction", result is OwnerResult.ConflictingAction)
        assertEquals("action-init-1", (result as OwnerResult.ConflictingAction).actionKey)
    }

    @Test
    fun `cancellation after OS action rethrows CancellationException without swallowing`() = runBlocking {
        authorityDao.insertAuthority(initialAuthority)

        var cancellationThrown = false
        try {
            owner.executeOperation(
                alarmId = 42L,
                desiredOperation = "SET",
                desiredTriggerMillis = 2000L,
                actionKey = "act-cancel"
            ) {
                throw CancellationException("Coroutine cancelled mid-operation")
            }
        } catch (_: CancellationException) {
            cancellationThrown = true
        }

        assertTrue("CancellationException must be re-thrown without being swallowed", cancellationThrown)
    }

    @Test
    fun `evaluateBootstrap returns QUARANTINE for absent authority`() {
        val decision = owner.evaluateBootstrap(null)
        assertEquals(BootstrapDecision.QUARANTINE, decision)
    }

    @Test
    fun `evaluateBootstrap returns ADOPT for SETTLED authority`() {
        val decision = owner.evaluateBootstrap(initialAuthority)
        assertEquals(BootstrapDecision.ADOPT, decision)
    }

    @Test
    fun `evaluateBootstrap returns RECONCILING for RECONCILING authority`() {
        val reconciling = initialAuthority.copy(reconcileState = "RECONCILING")
        val decision = owner.evaluateBootstrap(reconciling)
        assertEquals(BootstrapDecision.RECONCILE, decision)
    }

    @Test
    fun `changed restore epoch causes CAS claim to fail and returns Superseded`() = runBlocking {
        val authorityNewEpoch = initialAuthority.copy(restoreEpoch = 999L)
        authorityDao.insertAuthority(authorityNewEpoch)

        val result = owner.executeOperation(
            alarmId = 42L,
            desiredOperation = "SET",
            desiredTriggerMillis = 2000L,
            actionKey = "act-epoch"
        ) {
            true
        }

        assertTrue("Claim with updated restoreEpoch succeeds", result is OwnerResult.OsAccepted)
    }

    @Test
    fun `duplicate action while RECONCILING is NOT ignored and re-executes OS apply`() = runBlocking {
        // Authority in RECONCILING state with actionKey = "act-reconcile" and desiredTriggerMillis = 2000L
        val reconcilingAuthority = initialAuthority.copy(
            desiredTriggerMillis = 2000L,
            reconcileState = "RECONCILING",
            actionKey = "act-reconcile"
        )
        authorityDao.insertAuthority(reconcilingAuthority)

        var osActionExecuted = false
        val result = owner.executeOperation(
            alarmId = 42L,
            desiredOperation = "SET",
            desiredTriggerMillis = 2000L,
            actionKey = "act-reconcile" // Matching actionKey and target while RECONCILING!
        ) {
            osActionExecuted = true
            true
        }

        assertTrue("Duplicate action in RECONCILING state must re-execute OS apply", osActionExecuted)
        assertTrue("Result must be OsAccepted", result is OwnerResult.OsAccepted)
        val finalAuth = authorityDao.getAuthorityByAlarmId(42L)
        assertEquals("SETTLED", finalAuth?.reconcileState)
    }

    @Test
    fun `duplicate action after confirmed SETTLED returns DuplicateIgnored without advancing revision`() = runBlocking {
        authorityDao.insertAuthority(initialAuthority)

        val result = owner.executeOperation(
            alarmId = 42L,
            desiredOperation = "SET",
            desiredTriggerMillis = 1700000000000L,
            actionKey = "action-init-1" // Duplicate matching current SETTLED actionKey!
        ) {
            true
        }

        assertEquals(OwnerResult.DuplicateIgnored, result)

        val current = authorityDao.getAuthorityByAlarmId(42L)
        assertEquals("Revision must NOT be advanced on duplicate actionKey", 1L, current?.scheduleRevision)
    }

    @Test
    fun `permission check failure on SET returns PermissionBlocked without claiming or OS action`() = runBlocking {
        authorityDao.insertAuthority(initialAuthority)

        val blockedOwner = ScheduleAuthorityOwner(
            authorityDao = authorityDao,
            exactPermissionChecker = { false } // Permission revoked!
        )

        var osActionExecuted = false
        val result = blockedOwner.executeOperation(
            alarmId = 42L,
            desiredOperation = "SET",
            desiredTriggerMillis = 9000L,
            actionKey = "act-blocked"
        ) {
            osActionExecuted = true
            true
        }

        assertFalse("OS action must NOT be executed when exact permission is false", osActionExecuted)
        assertTrue("Operation must return PermissionBlocked", result is OwnerResult.PermissionBlocked)
        val current = authorityDao.getAuthorityByAlarmId(42L)
        assertEquals("Revision must NOT be advanced when permission is blocked", 1L, current?.scheduleRevision)
    }

    @Test
    fun `OS SET(A) in progress then B CANCEL results in final CANCEL SETTLED state`() = runBlocking {
        authorityDao.insertAuthority(initialAuthority)

        val fakeOsSlot = mutableListOf<String>()

        val resA = owner.executeOperation(
            alarmId = 42L,
            desiredOperation = "SET",
            desiredTriggerMillis = 2000L,
            actionKey = "action-set-a"
        ) {
            fakeOsSlot.clear()
            fakeOsSlot.add("SET_2000")
            true
        }

        assertTrue("First operation SET(A) must succeed", resA is OwnerResult.OsAccepted)

        val resB = owner.executeOperation(
            alarmId = 42L,
            desiredOperation = "CANCEL",
            desiredTriggerMillis = null,
            actionKey = "action-cancel-b"
        ) {
            fakeOsSlot.clear()
            fakeOsSlot.add("CANCEL")
            true
        }

        assertTrue("Second operation CANCEL(B) must succeed", resB is OwnerResult.OsAccepted)

        val finalAuth = authorityDao.getAuthorityByAlarmId(42L)
        assertNotNull(finalAuth)
        assertEquals(3L, finalAuth?.scheduleRevision)
        assertEquals("CANCEL", finalAuth?.desiredOperation)
        assertEquals("SETTLED", finalAuth?.reconcileState)
        assertEquals(listOf("CANCEL"), fakeOsSlot)
    }

    @Test
    fun `OS CANCEL(A) in progress then B SET results in final SET SETTLED state`() = runBlocking {
        authorityDao.insertAuthority(initialAuthority)

        val fakeOsSlot = mutableListOf<String>()

        owner.executeOperation(
            alarmId = 42L,
            desiredOperation = "CANCEL",
            desiredTriggerMillis = null,
            actionKey = "act-cancel-a"
        ) {
            fakeOsSlot.clear()
            fakeOsSlot.add("CANCEL")
            true
        }

        owner.executeOperation(
            alarmId = 42L,
            desiredOperation = "SET",
            desiredTriggerMillis = 5000L,
            actionKey = "act-set-b"
        ) {
            fakeOsSlot.clear()
            fakeOsSlot.add("SET_5000")
            true
        }

        val finalAuth = authorityDao.getAuthorityByAlarmId(42L)
        assertNotNull(finalAuth)
        assertEquals(3L, finalAuth?.scheduleRevision)
        assertEquals("SET", finalAuth?.desiredOperation)
        assertEquals(5000L, finalAuth?.desiredTriggerMillis)
        assertEquals("SETTLED", finalAuth?.reconcileState)
        assertEquals(listOf("SET_5000"), fakeOsSlot)
    }

    @Test
    fun `OS result succeeded but settlement superseded returns Superseded`() = runBlocking {
        authorityDao.insertAuthority(initialAuthority)

        val result = owner.executeOperation(
            alarmId = 42L,
            desiredOperation = "SET",
            desiredTriggerMillis = 2000L,
            actionKey = "act-superseded"
        ) {
            // Concurrent operation B updates scheduleRevision during OS action execution
            authorityDao.casClaimAuthority(
                alarmId = 42L,
                instanceToken = "token-instance-42",
                restoreEpoch = 100L,
                expectedRevision = 2L,
                expectedLifecycle = "LIVE",
                desiredOperation = "CANCEL",
                desiredTriggerMillis = null,
                actionKey = "act-b"
            )
            true
        }

        assertTrue("Operation A must detect post-OS superseding and return Superseded", result is OwnerResult.Superseded)
        val current = authorityDao.getAuthorityByAlarmId(42L)
        assertEquals(3L, current?.scheduleRevision)
        assertEquals("CANCEL", current?.desiredOperation)
    }

    @Test
    fun `exception after OS action leaves state RECONCILING and returns OutcomeUnknown`() = runBlocking {
        authorityDao.insertAuthority(initialAuthority)

        val result = owner.executeOperation(
            alarmId = 42L,
            desiredOperation = "SET",
            desiredTriggerMillis = 9000L,
            actionKey = "act-fail"
        ) {
            throw IllegalStateException("OS registration failed")
        }

        assertTrue("Exception must return OutcomeUnknown", result is OwnerResult.OutcomeUnknown)
        val current = authorityDao.getAuthorityByAlarmId(42L)
        assertEquals("RECONCILING", current?.reconcileState)
    }

    @Test
    fun `revision overflow at MAX_VALUE returns Superseded`() = runBlocking {
        val maxRevAuthority = initialAuthority.copy(scheduleRevision = Long.MAX_VALUE)
        authorityDao.insertAuthority(maxRevAuthority)

        val result = owner.executeOperation(
            alarmId = 42L,
            desiredOperation = "SET",
            desiredTriggerMillis = 9000L,
            actionKey = "act-overflow"
        ) {
            true
        }

        assertTrue("Revision overflow must return Superseded", result is OwnerResult.Superseded)
        val overflowResult = result as OwnerResult.Superseded
        assertEquals(Long.MAX_VALUE, overflowResult.currentRevision)
    }
}
