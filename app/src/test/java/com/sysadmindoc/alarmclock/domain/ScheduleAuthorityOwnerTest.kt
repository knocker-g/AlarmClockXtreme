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
    private val fakeOsSlot = mutableListOf<String>()

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
        fakeOsSlot.clear()

        val mainSlotApplier = MainSlotApplier { authority ->
            fakeOsSlot.clear()
            if (authority.desiredOperation == "SET") {
                fakeOsSlot.add("SET_${authority.desiredTriggerMillis}")
            } else if (authority.desiredOperation == "CANCEL") {
                fakeOsSlot.add("CANCEL")
            }
            true
        }

        owner = ScheduleAuthorityOwner(
            authorityDao = authorityDao,
            mainSlotApplier = mainSlotApplier,
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
        )

        assertTrue("Conflicting actionKey target must return ConflictingAction", result is OwnerResult.ConflictingAction)
        assertEquals("action-init-1", (result as OwnerResult.ConflictingAction).actionKey)
    }

    @Test
    fun `unknown or unsupported desiredOperation is rejected with OutcomeUnknown`() = runBlocking {
        authorityDao.insertAuthority(initialAuthority)

        val result = owner.executeOperation(
            alarmId = 42L,
            desiredOperation = "SNOOZE", // Unsupported OS operation!
            desiredTriggerMillis = 2000L,
            actionKey = "act-unsupported",
            expectedRevision = 1L
        )

        assertTrue("Unsupported operation must return OutcomeUnknown", result is OwnerResult.OutcomeUnknown)
        val cause = (result as OwnerResult.OutcomeUnknown).cause
        assertTrue("Cause must cite unsupported desiredOperation: ${cause.message}", cause is IllegalArgumentException)
    }

    @Test
    fun `changed instanceToken incarnation during convergence causes Superseded`() = runBlocking {
        authorityDao.insertAuthority(initialAuthority)

        val changingOwner = ScheduleAuthorityOwner(
            authorityDao = authorityDao,
            mainSlotApplier = { authority ->
                // Advance revision with a changed instanceToken in DB during OS apply
                authorityDao.deleteAuthorityByAlarmId(42L)
                authorityDao.insertAuthority(initialAuthority.copy(instanceToken = "token-changed-incarnation", scheduleRevision = 2L))
                true
            },
            exactPermissionChecker = { true }
        )

        val result = changingOwner.executeOperation(
            alarmId = 42L,
            desiredOperation = "SET",
            desiredTriggerMillis = 2000L,
            actionKey = "act-inc-change",
            expectedRevision = 1L
        )

        assertTrue("Changed incarnation during convergence must return Superseded", result is OwnerResult.Superseded)
    }

    @Test
    fun `changed restore epoch during convergence causes Superseded`() = runBlocking {
        authorityDao.insertAuthority(initialAuthority)

        val changingEpochOwner = ScheduleAuthorityOwner(
            authorityDao = authorityDao,
            mainSlotApplier = { authority ->
                // Advance revision with a changed restoreEpoch in DB during OS apply
                authorityDao.deleteAuthorityByAlarmId(42L)
                authorityDao.insertAuthority(initialAuthority.copy(restoreEpoch = 999L, scheduleRevision = 2L))
                true
            },
            exactPermissionChecker = { true }
        )

        val result = changingEpochOwner.executeOperation(
            alarmId = 42L,
            desiredOperation = "SET",
            desiredTriggerMillis = 2000L,
            actionKey = "act-epoch-change",
            expectedRevision = 1L
        )

        assertTrue("Changed restoreEpoch during convergence must return Superseded", result is OwnerResult.Superseded)
    }

    @Test
    fun `bounded retry reaches limit without falsely reporting success`() = runBlocking {
        authorityDao.insertAuthority(initialAuthority)

        val neverSettlingOwner = ScheduleAuthorityOwner(
            authorityDao = authorityDao,
            mainSlotApplier = { authority ->
                // Mutate scheduleRevision continuously during OS apply to prevent settlement
                authorityDao.casClaimAuthority(
                    alarmId = authority.alarmId,
                    instanceToken = authority.instanceToken,
                    restoreEpoch = authority.restoreEpoch,
                    expectedRevision = authority.scheduleRevision,
                    expectedLifecycle = authority.lifecycle,
                    desiredOperation = authority.desiredOperation,
                    desiredTriggerMillis = authority.desiredTriggerMillis,
                    actionKey = authority.actionKey
                )
                true
            },
            exactPermissionChecker = { true }
        )

        val result = neverSettlingOwner.executeOperation(
            alarmId = 42L,
            desiredOperation = "SET",
            desiredTriggerMillis = 2000L,
            actionKey = "act-retry-limit",
            expectedRevision = 1L
        )

        assertTrue("Unboundedly changing revisions must reach retry limit and return OutcomeUnknown", result is OwnerResult.OutcomeUnknown)
        val cause = (result as OwnerResult.OutcomeUnknown).cause
        assertTrue("Cause must cite bounded convergence limit reached", cause.message?.contains("limit reached") == true)
    }

    @Test
    fun `cancellation after OS action rethrows CancellationException without swallowing`() = runBlocking {
        authorityDao.insertAuthority(initialAuthority)

        val cancellingOwner = ScheduleAuthorityOwner(
            authorityDao = authorityDao,
            mainSlotApplier = {
                throw CancellationException("Coroutine cancelled mid-operation")
            },
            exactPermissionChecker = { true }
        )

        var cancellationThrown = false
        try {
            cancellingOwner.executeOperation(
                alarmId = 42L,
                desiredOperation = "SET",
                desiredTriggerMillis = 2000L,
                actionKey = "act-cancel",
                expectedRevision = 1L
            )
        } catch (_: CancellationException) {
            cancellationThrown = true
        }

        assertTrue("CancellationException must be re-thrown without being swallowed", cancellationThrown)
    }

    @Test
    fun `quarantined authority row receives Owner SET operation is rejected as Superseded`() = runBlocking {
        val quarantinedAuthority = initialAuthority.copy(reconcileState = "QUARANTINE")
        authorityDao.insertAuthority(quarantinedAuthority)

        val result = owner.executeOperation(
            alarmId = 42L,
            desiredOperation = "SET",
            desiredTriggerMillis = 2000L,
            actionKey = "act-try-quarantine",
            expectedRevision = 1L
        )

        assertTrue("Owner operation on QUARANTINE authority must be rejected as Superseded", result is OwnerResult.Superseded)
        val current = authorityDao.getAuthorityByAlarmId(42L)
        assertEquals("DB state MUST remain QUARANTINE", "QUARANTINE", current?.reconcileState)
    }

    @Test
    fun `evaluateBootstrap returns QUARANTINE for absent authority or missing definition`() {
        val decisionNoAuth = owner.evaluateBootstrap(null, definitionExists = true, sessionValid = true)
        assertEquals(BootstrapDecision.QUARANTINE, decisionNoAuth)

        val decisionNoDef = owner.evaluateBootstrap(initialAuthority, definitionExists = false, sessionValid = true)
        assertEquals(BootstrapDecision.QUARANTINE, decisionNoDef)

        val decisionInvalidSession = owner.evaluateBootstrap(initialAuthority, definitionExists = true, sessionValid = false)
        assertEquals(BootstrapDecision.QUARANTINE, decisionInvalidSession)
    }

    @Test
    fun `evaluateBootstrap returns ADOPT for SETTLED authority with valid definition and session`() {
        val decision = owner.evaluateBootstrap(initialAuthority, definitionExists = true, sessionValid = true)
        assertEquals(BootstrapDecision.ADOPT, decision)
    }

    @Test
    fun `evaluateBootstrap returns RECONCILING for RECONCILING authority with valid definition`() {
        val reconciling = initialAuthority.copy(reconcileState = "RECONCILING")
        val decision = owner.evaluateBootstrap(reconciling, definitionExists = true, sessionValid = false)
        assertEquals(BootstrapDecision.RECONCILE, decision)
    }

    @Test
    fun `duplicate action while RECONCILING is NOT ignored and re-executes OS apply`() = runBlocking {
        val reconcilingAuthority = initialAuthority.copy(
            desiredTriggerMillis = 2000L,
            reconcileState = "RECONCILING",
            actionKey = "act-reconcile"
        )
        authorityDao.insertAuthority(reconcilingAuthority)

        var osActionExecuted = false
        val reconcilingOwner = ScheduleAuthorityOwner(
            authorityDao = authorityDao,
            mainSlotApplier = {
                osActionExecuted = true
                true
            },
            exactPermissionChecker = { true }
        )

        val result = reconcilingOwner.executeOperation(
            alarmId = 42L,
            desiredOperation = "SET",
            desiredTriggerMillis = 2000L,
            actionKey = "act-reconcile"
        )

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
        )

        assertEquals(OwnerResult.DuplicateIgnored, result)

        val current = authorityDao.getAuthorityByAlarmId(42L)
        assertEquals("Revision must NOT be advanced on duplicate actionKey", 1L, current?.scheduleRevision)
    }

    @Test
    fun `permission check failure on SET returns PermissionBlocked without claiming or OS action`() = runBlocking {
        authorityDao.insertAuthority(initialAuthority)

        val blockedOwner = ScheduleAuthorityOwner(
            authorityDao = authorityDao,
            mainSlotApplier = { true },
            exactPermissionChecker = { false } // Permission revoked!
        )

        val result = blockedOwner.executeOperation(
            alarmId = 42L,
            desiredOperation = "SET",
            desiredTriggerMillis = 9000L,
            actionKey = "act-blocked",
            expectedRevision = 1L
        )

        assertTrue("Operation must return PermissionBlocked", result is OwnerResult.PermissionBlocked)
        val current = authorityDao.getAuthorityByAlarmId(42L)
        assertEquals("Revision must NOT be advanced when permission is blocked", 1L, current?.scheduleRevision)
        assertTrue("OS slot must remain empty on permission block", fakeOsSlot.isEmpty())
    }

    @Test
    fun `OS sequence SET(A), CANCEL(B), SET(C) converges to final OS slot SET_3000`() = runBlocking {
        authorityDao.insertAuthority(initialAuthority)

        owner.executeOperation(alarmId = 42L, desiredOperation = "SET", desiredTriggerMillis = 2000L, actionKey = "act-a", expectedRevision = 1L)
        owner.executeOperation(alarmId = 42L, desiredOperation = "CANCEL", desiredTriggerMillis = null, actionKey = "act-b", expectedRevision = 2L)
        val resC = owner.executeOperation(alarmId = 42L, desiredOperation = "SET", desiredTriggerMillis = 3000L, actionKey = "act-c", expectedRevision = 3L)

        assertTrue("Final operation SET(C) must succeed", resC is OwnerResult.OsAccepted)

        val finalAuth = authorityDao.getAuthorityByAlarmId(42L)
        assertNotNull(finalAuth)
        assertEquals(4L, finalAuth?.scheduleRevision)
        assertEquals("SET", finalAuth?.desiredOperation)
        assertEquals(3000L, finalAuth?.desiredTriggerMillis)
        assertEquals("SETTLED", finalAuth?.reconcileState)
        assertEquals("Final fake OS slot MUST contain SET_3000", listOf("SET_3000"), fakeOsSlot)
    }

    @Test
    fun `B SET fails during convergence leaves state RECONCILING without false SETTLED`() = runBlocking {
        authorityDao.insertAuthority(initialAuthority)

        val failingOwner = ScheduleAuthorityOwner(
            authorityDao = authorityDao,
            mainSlotApplier = { false }, // OS apply fails!
            exactPermissionChecker = { true }
        )

        val result = failingOwner.executeOperation(
            alarmId = 42L,
            desiredOperation = "SET",
            desiredTriggerMillis = 9000L,
            actionKey = "act-fail",
            expectedRevision = 1L
        )

        assertTrue("Failing OS applier must return OutcomeUnknown", result is OwnerResult.OutcomeUnknown)
        val current = authorityDao.getAuthorityByAlarmId(42L)
        assertEquals("DB state MUST remain RECONCILING (no false SETTLED)", "RECONCILING", current?.reconcileState)
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
        )

        assertTrue("Revision overflow must return Superseded", result is OwnerResult.Superseded)
        val overflowResult = result as OwnerResult.Superseded
        assertEquals(Long.MAX_VALUE, overflowResult.currentRevision)
    }

    @Test
    fun `Discriminator A - stale A with new actionKey is rejected after B becomes current`() = runBlocking {
        authorityDao.insertAuthority(initialAuthority) // DB at revision 1L

        // B executes operation SET(B) -> advances scheduleRevision to 2L
        val resB = owner.executeOperation(
            alarmId = 42L,
            desiredOperation = "SET",
            desiredTriggerMillis = 9000L,
            actionKey = "act-B",
            expectedRevision = 1L
        )
        assertTrue("Operation B must be accepted", resB is OwnerResult.OsAccepted)

        // Stale A (who held expectedRevision 1L before B ran) attempts executeOperation using new actionKey and expectedRevision = 1L
        val resStaleA = owner.executeOperation(
            alarmId = 42L,
            desiredOperation = "SET",
            desiredTriggerMillis = 2000L,
            actionKey = "act-stale-A",
            expectedRevision = 1L // Stale expected revision!
        )

        assertTrue("Stale operation A MUST return Superseded", resStaleA is OwnerResult.Superseded)
        val current = authorityDao.getAuthorityByAlarmId(42L)
        assertEquals("DB scheduleRevision MUST remain 2L", 2L, current?.scheduleRevision)
        assertEquals("DB trigger MUST remain B (9000L)", 9000L, current?.desiredTriggerMillis)
        assertEquals("Fake OS slot MUST remain B (SET_9000)", listOf("SET_9000"), fakeOsSlot)
    }

    @Test
    fun `Phase 3 Test B - stale CANCEL A with expectedRevision 1L is rejected when B is current`() = runBlocking {
        authorityDao.insertAuthority(initialAuthority) // DB at revision 1L

        // B executes operation SET(B) -> advances scheduleRevision to 2L
        val resB = owner.executeOperation(
            alarmId = 42L,
            desiredOperation = "SET",
            desiredTriggerMillis = 9000L,
            actionKey = "act-B",
            expectedRevision = 1L
        )
        assertTrue("Operation B must be accepted", resB is OwnerResult.OsAccepted)

        // Stale A attempts CANCEL with expectedRevision = 1L
        val resCancelA = owner.executeOperation(
            alarmId = 42L,
            desiredOperation = "CANCEL",
            desiredTriggerMillis = null,
            actionKey = "act-cancel-A",
            expectedRevision = 1L
        )

        assertTrue("Stale CANCEL A MUST return Superseded", resCancelA is OwnerResult.Superseded)
        val current = authorityDao.getAuthorityByAlarmId(42L)
        assertEquals("DB trigger MUST remain B (9000L)", 9000L, current?.desiredTriggerMillis)
        assertEquals("Fake OS slot MUST remain B (SET_9000)", listOf("SET_9000"), fakeOsSlot)
    }

    @Test
    fun `Phase 3 Test D - valid current caller SET succeeds when expectedRevision matches DB`() = runBlocking {
        authorityDao.insertAuthority(initialAuthority) // DB at revision 1L

        val resCurrent = owner.executeOperation(
            alarmId = 42L,
            desiredOperation = "SET",
            desiredTriggerMillis = 2000L,
            actionKey = "act-valid",
            expectedRevision = 1L
        )

        assertTrue("Valid operation with matching expectedRevision MUST succeed", resCurrent is OwnerResult.OsAccepted)
        val current = authorityDao.getAuthorityByAlarmId(42L)
        assertEquals("DB scheduleRevision MUST advance to 2L", 2L, current?.scheduleRevision)
    }

    @Test
    fun `Phase 4 Test A - Missing caller revision expectedRevision == null is Fail-Closed rejected`() = runBlocking {
        authorityDao.insertAuthority(initialAuthority) // DB at revision 1L

        val resNullRevision = owner.executeOperation(
            alarmId = 42L,
            desiredOperation = "SET",
            desiredTriggerMillis = 2000L,
            actionKey = "act-missing-revision",
            expectedRevision = null // Missing expectedRevision on live operation!
        )

        assertTrue("Missing caller expectedRevision MUST be Fail-Closed rejected as Superseded", resNullRevision is OwnerResult.Superseded)
        val current = authorityDao.getAuthorityByAlarmId(42L)
        assertEquals("DB scheduleRevision MUST remain 1L", 1L, current?.scheduleRevision)
    }

    @Test
    fun `Phase 4 Test F - Stale replay cannot replace current B when DB holds SETTLED B`() = runBlocking {
        authorityDao.insertAuthority(initialAuthority) // DB at revision 1L

        // B executes operation SET(B) -> advances scheduleRevision to 2L
        val resB = owner.executeOperation(
            alarmId = 42L,
            desiredOperation = "SET",
            desiredTriggerMillis = 9000L,
            actionKey = "act-B",
            expectedRevision = 1L
        )
        assertTrue("Operation B must be accepted", resB is OwnerResult.OsAccepted)

        // Stale replay of A arrives for a SETTLED authority row that holds B
        val resReplay = owner.replayPersistedOperation(42L)

        assertEquals("Replay of SETTLED authority MUST return DuplicateIgnored without overwriting OS slot", OwnerResult.DuplicateIgnored, resReplay)
        assertEquals("Fake OS slot MUST remain B (SET_9000)", listOf("SET_9000"), fakeOsSlot)
    }

    @Test
    fun `Phase 4 Test G - Authorized recovery replay succeeds for pending RECONCILING authority`() = runBlocking {
        val reconcilingAuthority = initialAuthority.copy(
            desiredTriggerMillis = 4000L,
            reconcileState = "RECONCILING",
            actionKey = "act-reconcile-recovery"
        )
        authorityDao.insertAuthority(reconcilingAuthority)

        val resReplay = owner.replayPersistedOperation(42L)

        assertTrue("Authorized recovery replay MUST return OsAccepted", resReplay is OwnerResult.OsAccepted)
        val current = authorityDao.getAuthorityByAlarmId(42L)
        assertEquals("DB state MUST become SETTLED", "SETTLED", current?.reconcileState)
        assertEquals("Fake OS slot MUST contain SET_4000", listOf("SET_4000"), fakeOsSlot)
    }

    @Test
    fun `settleAuthority CAS failure during replay returns Superseded`() = runBlocking {
        val reconcilingAuth = initialAuthority.copy(
            desiredTriggerMillis = 5000L,
            reconcileState = "RECONCILING",
            actionKey = "act-reconcile-cas-fail"
        )
        authorityDao.insertAuthority(reconcilingAuth)

        val casFailingOwner = ScheduleAuthorityOwner(
            authorityDao = object : com.sysadmindoc.alarmclock.data.local.ScheduleAuthorityDao by authorityDao {
                override suspend fun settleAuthority(
                    alarmId: Long,
                    instanceToken: String,
                    restoreEpoch: Long,
                    claimedRevision: Long,
                    expectedActionKey: String?
                ): Int {
                    return 0 // Simulate settleAuthority CAS returning 0 due to concurrent DB update!
                }
            },
            mainSlotApplier = { fakeOsSlot.add("SET_${it.desiredTriggerMillis}"); true },
            exactPermissionChecker = { true }
        )

        val resReplay = casFailingOwner.replayPersistedOperation(42L)

        assertTrue("settleAuthority returning 0 during replay MUST return Superseded", resReplay is OwnerResult.Superseded)
    }
}
