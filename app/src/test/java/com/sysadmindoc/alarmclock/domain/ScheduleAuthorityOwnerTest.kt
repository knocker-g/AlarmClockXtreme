package com.sysadmindoc.alarmclock.domain

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.sysadmindoc.alarmclock.data.local.AlarmDatabase
import com.sysadmindoc.alarmclock.data.local.ScheduleAuthorityDao
import com.sysadmindoc.alarmclock.data.local.entity.ScheduleAuthority
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import java.util.Collections
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import com.sysadmindoc.alarmclock.data.backup.PreparedRestoreRecoveryContext
import com.sysadmindoc.alarmclock.data.backup.RestoreJournalCoordinator
import com.sysadmindoc.alarmclock.data.local.entity.ScheduleAuthorityMeta
import com.sysadmindoc.alarmclock.data.model.Alarm
import com.sysadmindoc.alarmclock.data.preferences.AppSettings
import com.sysadmindoc.alarmclock.data.preferences.PreferencesManager
import com.sysadmindoc.alarmclock.data.repository.AlarmIncidentRepository
import com.sysadmindoc.alarmclock.data.repository.AlarmRepository
import com.sysadmindoc.alarmclock.data.repository.HolidayRepository
import com.sysadmindoc.alarmclock.data.repository.WeatherRepository
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.coroutineContext

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
        runBlocking {
            authorityDao.insertMeta(ScheduleAuthorityMeta(id = 1, restoreEpoch = 100L, bootstrapState = "INITIALIZED"))
        }
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

    @Test
    fun `U1 - withMainV2SlotLock serializes B cancel and C set on same alarmId without blocking different alarmId`() = runBlocking {
        authorityDao.insertAuthority(initialAuthority)

        val events = Collections.synchronizedList(mutableListOf<String>())
        val bEntered = CompletableDeferred<Unit>()
        val allowBExit = CompletableDeferred<Unit>()
        val otherAlarmDone = CompletableDeferred<Unit>()

        val jobB = async(Dispatchers.Default) {
            owner.withMainV2SlotLock(42L) {
                events.add("B_ENTER_42")
                bEntered.complete(Unit)
                allowBExit.await()
                events.add("B_EXIT_42")
            }
        }

        withTimeout(5_000L) { bEntered.await() }

        // Same alarmId C-Set must wait until B-Cancel releases alarmId=42 lock
        val jobC = async(Dispatchers.Default) {
            owner.withMainV2SlotLock(42L) {
                events.add("C_ENTER_42")
                events.add("C_EXIT_42")
            }
        }

        // Different alarmId=99 MUST NOT be blocked while alarmId=42 holds lock
        val jobOther = async(Dispatchers.Default) {
            owner.withMainV2SlotLock(99L) {
                events.add("OTHER_99")
                otherAlarmDone.complete(Unit)
            }
        }

        withTimeout(5_000L) { otherAlarmDone.await() }
        jobOther.await()
        assertFalse("C-Set on same alarmId=42 MUST still be blocked while B-Cancel holds lock", jobC.isCompleted)

        allowBExit.complete(Unit)
        withTimeout(5_000L) {
            jobB.await()
            jobC.await()
        }

        val order42 = events.filter { it.endsWith("_42") }
        assertEquals(listOf("B_ENTER_42", "B_EXIT_42", "C_ENTER_42", "C_EXIT_42"), order42)
        assertTrue("Different alarmId=99 must execute without waiting on alarmId=42", events.contains("OTHER_99"))
    }

    @Test
    fun `U1b - withMainV2SlotLock unlocks on cancellation and exception and preserves legacy executeOperation`() = runBlocking {
        authorityDao.insertAuthority(initialAuthority)

        val cancelled = try {
            owner.withMainV2SlotLock(42L) {
                throw CancellationException("Simulated cancellation inside MAIN_V2 lock")
            }
            false
        } catch (_: CancellationException) {
            true
        }
        assertTrue(cancelled)

        val threw = try {
            owner.withMainV2SlotLock(42L) {
                throw IllegalStateException("Simulated failure inside MAIN_V2 lock")
            }
            false
        } catch (_: IllegalStateException) {
            true
        }
        assertTrue(threw)

        // Lock must be cleanly released so both withMainV2SlotLock and legacy executeOperation proceed without deadlock
        val reacquired = withTimeout(5_000L) {
            owner.withMainV2SlotLock(42L) { "LOCK_OK" }
        }
        assertEquals("LOCK_OK", reacquired)

        val legacyRes = withTimeout(5_000L) {
            owner.executeOperation(
                alarmId = 42L,
                desiredOperation = "SET",
                desiredTriggerMillis = 1700000005000L,
                actionKey = "act-after-u1b",
                expectedRevision = 1L
            )
        }
        assertTrue("Legacy executeOperation MUST remain functional and unblocked", legacyRes is OwnerResult.OsAccepted)
    }

    private fun createOwnerWithContextIfAvailable(
        applier: MainSlotApplier,
        permissionChecker: () -> Boolean = { true }
    ): ScheduleAuthorityOwner? {
        val ctor = ScheduleAuthorityOwner::class.java.declaredConstructors.firstOrNull { c ->
            c.parameterTypes.size == 4 && Context::class.java.isAssignableFrom(c.parameterTypes[3])
        } ?: return null
        ctor.isAccessible = true
        return ctor.newInstance(authorityDao, applier, permissionChecker, context) as ScheduleAuthorityOwner
    }

    @Test
    fun `C3A-U3 - Owner to Scheduler reentrant cancel and scheduleAt do not deadlock on L1 or L2`() = runBlocking {
        org.robolectric.shadows.ShadowAlarmManager.setCanScheduleExactAlarms(true)
        authorityDao.insertMeta(ScheduleAuthorityMeta(id = 1, restoreEpoch = 100L, bootstrapState = "COMPLETED"))
        val alarmRepo = AlarmRepository(dao = database.alarmDao(), groupDao = database.alarmGroupDao(), database = database)
        val trigger = System.currentTimeMillis() + 3_600_000L
        val alarm = Alarm(id = 42L, hour = 7, minute = 0, isEnabled = true, nextTriggerTime = trigger, generation = 1, revision = 1)
        database.alarmDao().insert(alarm)
        authorityDao.insertAuthority(initialAuthority.copy(desiredTriggerMillis = trigger))

        val prefs = mockk<PreferencesManager>(relaxed = true) {
            every { getCachedSettings() } returns AppSettings()
            coEvery { getCurrentSettings() } returns AppSettings()
        }
        lateinit var reentrantScheduler: AlarmScheduler
        var reentrantCancelOk: Boolean? = null
        var reentrantScheduleArmed: Boolean? = null

        val reentrantApplier = MainSlotApplier { auth ->
            val rawOk = reentrantScheduler.applyRawOsSet(auth.alarmId, auth.desiredTriggerMillis ?: trigger)
            reentrantCancelOk = reentrantScheduler.cancelMainScheduledAlarm(auth.alarmId, expectedTriggerTime = trigger)
            val reentrantOutcome = reentrantScheduler.scheduleAt(
                alarm.copy(revision = 2),
                trigger + 60_000L,
                requestWidgetUpdate = false
            )
            reentrantScheduleArmed = reentrantOutcome is ScheduleOutcome.Armed
            rawOk
        }

        val reentrantOwner = createOwnerWithContextIfAvailable(reentrantApplier)
            ?: ScheduleAuthorityOwner(authorityDao, reentrantApplier, { true })

        reentrantScheduler = AlarmScheduler(
            context = context,
            repository = alarmRepo,
            calculator = mockk(relaxed = true),
            preferencesManager = prefs,
            holidayRepository = mockk(relaxed = true),
            alarmIncidentRepository = mockk(relaxed = true),
            weatherRepository = mockk(relaxed = true),
            scheduleAuthorityOwnerProvider = { reentrantOwner }
        )

        val ownerRes = withTimeout(5_000L) {
            reentrantOwner.executeOperation(
                alarmId = 42L,
                desiredOperation = "SET",
                desiredTriggerMillis = trigger,
                actionKey = "act-u3-reentrant",
                expectedRevision = 1L
            )
        }

        assertTrue("C3A-U3: Reentrant executeOperation MUST succeed without L1/L2 deadlock, got $ownerRes", ownerRes is OwnerResult.OsAccepted)
        assertEquals("C3A-U3: Reentrant cancelMainScheduledAlarm MUST complete without deadlock", true, reentrantCancelOk)
        assertEquals("C3A-U3: Reentrant scheduleAt MUST complete without deadlock", true, reentrantScheduleArmed)
        assertNotNull(createOwnerWithContextIfAvailable(reentrantApplier))
    }

    @Test
    fun `C3A-U5b - RealMainSlotApplier rejects QUARANTINE, non-positive restoreEpoch, non-positive alarmId, and DISABLED SET`() = runBlocking {
        val mockScheduler = mockk<AlarmScheduler>(relaxed = true) {
            every { applyRawOsSet(any(), any()) } returns true
            every { applyRawOsCancel(any()) } returns true
        }
        val applier = RealMainSlotApplier(mockScheduler)

        assertFalse(
            "QUARANTINE must be rejected",
            applier.applySlotOperation(initialAuthority.copy(reconcileState = "QUARANTINE"))
        )
        assertFalse(
            "Non-positive restoreEpoch must be rejected",
            applier.applySlotOperation(initialAuthority.copy(restoreEpoch = 0L))
        )
        assertFalse(
            "Non-positive alarmId must be rejected",
            applier.applySlotOperation(initialAuthority.copy(alarmId = 0L))
        )
        assertFalse(
            "DISABLED lifecycle on SET must be rejected",
            applier.applySlotOperation(initialAuthority.copy(lifecycle = "DISABLED", desiredOperation = "SET"))
        )
        assertFalse(
            "Null trigger on SET must be rejected",
            applier.applySlotOperation(initialAuthority.copy(desiredOperation = "SET", desiredTriggerMillis = null))
        )
        verify(exactly = 0) { mockScheduler.applyRawOsSet(any(), any()) }
        verify(exactly = 0) { mockScheduler.applyRawOsCancel(any()) }

        assertTrue(
            "ACTIVE lifecycle (post-fire atomic recovery compatibility) MUST remain accepted",
            applier.applySlotOperation(initialAuthority.copy(lifecycle = "ACTIVE"))
        )
        verify(exactly = 1) { mockScheduler.applyRawOsSet(42L, 1700000000000L) }
    }

    @Test
    fun `C3A-U5c - ScheduleAuthorityOwner rejects stale restoreEpoch mismatch against ScheduleAuthorityMeta`() = runBlocking {
        authorityDao.insertMeta(ScheduleAuthorityMeta(id = 1, restoreEpoch = 200L, bootstrapState = "COMPLETED"))
        authorityDao.insertAuthority(initialAuthority.copy(restoreEpoch = 100L, reconcileState = "RECONCILING"))

        var osCalled = false
        val guardedOwner = ScheduleAuthorityOwner(
            authorityDao = authorityDao,
            mainSlotApplier = { osCalled = true; true },
            exactPermissionChecker = { true }
        )

        val execRes = guardedOwner.executeOperation(42L, "SET", 1700000005000L, "act-stale-epoch", expectedRevision = 1L)
        assertTrue("executeOperation MUST reject stale restoreEpoch as Superseded", execRes is OwnerResult.Superseded)

        val bootRes = guardedOwner.replayBootOsOperation(42L)
        assertTrue("replayBootOsOperation MUST reject stale restoreEpoch as Superseded", bootRes is OwnerResult.Superseded)

        val replayRes = guardedOwner.replayPersistedOperation(42L)
        assertTrue("replayPersistedOperation MUST reject stale restoreEpoch as Superseded", replayRes is OwnerResult.Superseded)

        assertFalse("MainSlotApplier MUST NOT be invoked on stale restoreEpoch", osCalled)
    }

    @Test
    fun `C3A-U7 - ScheduleAuthorityOwner obeys L1 Recovery Gate in PHASE_PREPARED and PHASE_COMMITTED`() = runBlocking {
        authorityDao.insertMeta(ScheduleAuthorityMeta(id = 1, restoreEpoch = 100L, bootstrapState = "COMPLETED"))
        authorityDao.insertAuthority(initialAuthority)

        var applierCalls = 0
        val contextOwner = createOwnerWithContextIfAvailable(
            applier = MainSlotApplier {
                applierCalls++
                true
            }
        )
        assertNotNull("C3A-U7: ScheduleAuthorityOwner MUST expose Context-aware constructor for L1 Recovery Gate", contextOwner)

        val journalFile = File(context.filesDir, RestoreJournalCoordinator.JOURNAL_FILENAME)
        val bakFile = File(context.filesDir, "${RestoreJournalCoordinator.JOURNAL_FILENAME}.bak")
        journalFile.delete()
        bakFile.delete()

        val prefs = mockk<PreferencesManager>(relaxed = true) {
            every { getCachedSettings() } returns AppSettings()
            coEvery { getCurrentSettings() } returns AppSettings()
        }
        val alarmRepo = AlarmRepository(dao = database.alarmDao(), groupDao = database.alarmGroupDao(), database = database)
        val scheduler = AlarmScheduler(
            context = context,
            repository = alarmRepo,
            calculator = mockk(relaxed = true),
            preferencesManager = prefs,
            holidayRepository = mockk(relaxed = true),
            alarmIncidentRepository = mockk(relaxed = true),
            weatherRepository = mockk(relaxed = true)
        )
        val coordinator = RestoreJournalCoordinator(
            context = context,
            database = database,
            preferencesManager = prefs,
            alarmScheduler = scheduler,
            moshi = com.squareup.moshi.Moshi.Builder().build()
        )

        try {
            coordinator.prepareTransaction(AppSettings(), emptyList(), emptyList())
            val blockedRes = contextOwner!!.executeOperation(
                alarmId = 42L,
                desiredOperation = "SET",
                desiredTriggerMillis = 1700000010000L,
                actionKey = "act-u7-prepared",
                expectedRevision = 1L
            )
            assertTrue("C3A-U7: PHASE_PREPARED MUST fail closed with OutcomeUnknown, got $blockedRes", blockedRes is OwnerResult.OutcomeUnknown)
            assertEquals("C3A-U7: MainSlotApplier MUST NOT run during PHASE_PREPARED", 0, applierCalls)
            assertEquals("C3A-U7: Revision MUST NOT advance during PHASE_PREPARED", 1L, authorityDao.getAuthorityByAlarmId(42L)?.scheduleRevision)

            coordinator.markCommitted()
            val committedRes = contextOwner.executeOperation(
                alarmId = 42L,
                desiredOperation = "SET",
                desiredTriggerMillis = 1700000010000L,
                actionKey = "act-u7-committed",
                expectedRevision = 1L
            )
            assertTrue("C3A-U7: PHASE_COMMITTED MUST permit Owner execution, got $committedRes", committedRes is OwnerResult.OsAccepted)
            assertEquals(1, applierCalls)
        } finally {
            journalFile.delete()
            bakFile.delete()
        }
    }

    private fun createForgedGateContext(canonicalPath: String, useNullLeaseWhenClosed: Boolean = false): CoroutineContext? {
        val clazz = ScheduleAuthorityOwner.ActiveRecoveryGateContext::class.java
        clazz.declaredConstructors.firstOrNull { c ->
            c.parameterTypes.size == 1 && c.parameterTypes[0] == String::class.java
        }?.let { legacyCtor ->
            legacyCtor.isAccessible = true
            return legacyCtor.newInstance(canonicalPath) as CoroutineContext
        }
        val closedCtor = clazz.declaredConstructors.firstOrNull { c ->
            c.parameterTypes.size == 2
        } ?: return null
        closedCtor.isAccessible = true
        if (useNullLeaseWhenClosed) {
            return closedCtor.newInstance(null, 0) as CoroutineContext
        }
        val leaseClass = ScheduleAuthorityOwner::class.java.declaredClasses.firstOrNull {
            it.simpleName == "RecoveryGateLease"
        }
        val forgedLease = if (leaseClass != null) {
            val leaseCtor = leaseClass.declaredConstructors.firstOrNull { it.parameterTypes.size == 4 }
            if (leaseCtor != null) {
                leaseCtor.isAccessible = true
                leaseCtor.newInstance(
                    UUID.randomUUID().toString(),
                    canonicalPath,
                    AtomicBoolean(true),
                    null
                )
            } else null
        } else null
        return closedCtor.newInstance(forgedLease, 0) as CoroutineContext
    }

    private fun createHijackedLeaseContextIfClosed(capturedCtx: CoroutineContext): CoroutineContext? {
        val activeGate = capturedCtx[ScheduleAuthorityOwner.ActiveRecoveryGateContext] ?: return null
        val clazz = ScheduleAuthorityOwner.ActiveRecoveryGateContext::class.java
        val closedCtor = clazz.declaredConstructors.firstOrNull { it.parameterTypes.size == 2 } ?: return null
        val leaseField = clazz.declaredFields.firstOrNull { it.name == "lease" } ?: return null
        leaseField.isAccessible = true
        val liveLease = leaseField.get(activeGate) ?: return null
        closedCtor.isAccessible = true
        return closedCtor.newInstance(liveLease, 0) as CoroutineContext
    }

    private fun buildRestoreJournalCoordinatorForTest(
        alarmRepo: AlarmRepository,
        prefs: PreferencesManager,
        scheduler: AlarmScheduler
    ): RestoreJournalCoordinator = RestoreJournalCoordinator(
        context = context,
        database = database,
        preferencesManager = prefs,
        alarmScheduler = scheduler,
        moshi = com.squareup.moshi.Moshi.Builder().build()
    )

    private fun buildMockPrefs(): PreferencesManager = mockk<PreferencesManager>(relaxed = true) {
        every { getCachedSettings() } returns AppSettings()
        coEvery { getCurrentSettings() } returns AppSettings()
    }

    private fun cleanJournalFiles() {
        File(context.filesDir, RestoreJournalCoordinator.JOURNAL_FILENAME).delete()
        File(context.filesDir, "${RestoreJournalCoordinator.JOURNAL_FILENAME}.bak").delete()
    }

    @Test
    fun `S1 - forged ActiveRecoveryGateContext is rejected during PHASE_PREPARED with zero DB and OS mutations`() = runBlocking {
        authorityDao.insertMeta(ScheduleAuthorityMeta(id = 1, restoreEpoch = 100L, bootstrapState = "COMPLETED"))
        authorityDao.insertAuthority(initialAuthority)
        cleanJournalFiles()

        var applierCalls = 0
        val contextOwner = createOwnerWithContextIfAvailable(
            applier = MainSlotApplier {
                applierCalls++
                true
            }
        )!!
        val prefs = buildMockPrefs()
        val alarmRepo = AlarmRepository(dao = database.alarmDao(), groupDao = database.alarmGroupDao(), database = database)
        val scheduler = AlarmScheduler(
            context = context,
            repository = alarmRepo,
            calculator = mockk(relaxed = true),
            preferencesManager = prefs,
            holidayRepository = mockk(relaxed = true),
            alarmIncidentRepository = mockk(relaxed = true),
            weatherRepository = mockk(relaxed = true)
        )
        val coordinator = buildRestoreJournalCoordinatorForTest(alarmRepo, prefs, scheduler)
        val canonicalPath = RestoreJournalCoordinator.canonicalJournalPath(context)

        try {
            coordinator.prepareTransaction(AppSettings(), emptyList(), emptyList())
            val forgedCtx = createForgedGateContext(canonicalPath, useNullLeaseWhenClosed = false)
            assertNotNull("Test must be able to construct/probe ActiveRecoveryGateContext", forgedCtx)

            val res = withContext(forgedCtx!!) {
                contextOwner.executeOperation(
                    alarmId = 42L,
                    desiredOperation = "SET",
                    desiredTriggerMillis = 1700000010000L,
                    actionKey = "act-s1-forged",
                    expectedRevision = 1L
                )
            }
            assertTrue("S1: Forged ActiveRecoveryGateContext MUST be rejected during PHASE_PREPARED, got $res", res is OwnerResult.OutcomeUnknown)
            assertEquals("RESTORE_PREPARED_FENCE", (res as OwnerResult.OutcomeUnknown).cause.message)
            assertEquals("S1: MainSlotApplier MUST NOT run", 0, applierCalls)
            assertEquals("S1: DB revision MUST remain 1L", 1L, authorityDao.getAuthorityByAlarmId(42L)?.scheduleRevision)

            val nullLeaseCtx = createForgedGateContext(canonicalPath, useNullLeaseWhenClosed = true)
            if (nullLeaseCtx != null) {
                val resNull = withContext(nullLeaseCtx) {
                    contextOwner.executeOperation(
                        alarmId = 42L,
                        desiredOperation = "SET",
                        desiredTriggerMillis = 1700000010000L,
                        actionKey = "act-s1-null-lease",
                        expectedRevision = 1L
                    )
                }
                assertTrue("S1: Null-lease ActiveRecoveryGateContext MUST be rejected, got $resNull", resNull is OwnerResult.OutcomeUnknown)
                assertEquals(0, applierCalls)
                assertEquals(1L, authorityDao.getAuthorityByAlarmId(42L)?.scheduleRevision)
            }
        } finally {
            cleanJournalFiles()
        }
    }

    @Test
    fun `S2 - unauthorized Context during PHASE_PREPARED fails closed across Owner and Scheduler`() = runBlocking {
        org.robolectric.shadows.ShadowAlarmManager.setCanScheduleExactAlarms(true)
        authorityDao.insertMeta(ScheduleAuthorityMeta(id = 1, restoreEpoch = 100L, bootstrapState = "COMPLETED"))
        val trigger = System.currentTimeMillis() + 3_600_000L
        val alarm = Alarm(id = 42L, hour = 7, minute = 0, isEnabled = true, nextTriggerTime = trigger, generation = 1, revision = 1)
        database.alarmDao().insert(alarm)
        authorityDao.insertAuthority(initialAuthority.copy(desiredTriggerMillis = trigger))
        cleanJournalFiles()

        var applierCalls = 0
        val contextOwner = createOwnerWithContextIfAvailable(
            applier = MainSlotApplier {
                applierCalls++
                true
            }
        )!!
        val prefs = buildMockPrefs()
        val alarmRepo = AlarmRepository(dao = database.alarmDao(), groupDao = database.alarmGroupDao(), database = database)
        val ownerRoute = mockk<ProductionSchedulingRouteAuthority> {
            every { isOwnerRouteActive() } returns true
        }
        val scheduler = AlarmScheduler(
            context = context,
            repository = alarmRepo,
            calculator = mockk(relaxed = true),
            preferencesManager = prefs,
            holidayRepository = mockk(relaxed = true),
            alarmIncidentRepository = mockk(relaxed = true),
            weatherRepository = mockk(relaxed = true),
            scheduleAuthorityOwnerProvider = { contextOwner },
            routeAuthority = ownerRoute
        )
        val coordinator = buildRestoreJournalCoordinatorForTest(alarmRepo, prefs, scheduler)
        val canonicalPath = RestoreJournalCoordinator.canonicalJournalPath(context)

        try {
            coordinator.prepareTransaction(AppSettings(), emptyList(), emptyList())
            val forgedPreparedBypass = PreparedRestoreRecoveryContext.createForRecovery(
                "forged-tx-s2",
                RestoreJournalCoordinator.PHASE_PREPARED,
                canonicalPath
            )
            val ownerRes = withContext(forgedPreparedBypass) {
                contextOwner.executeOperation(42L, "SET", trigger + 1000L, "act-s2-owner", expectedRevision = 1L)
            }
            assertTrue("S2: Forged PreparedRestoreRecoveryContext MUST fail closed, got $ownerRes", ownerRes is OwnerResult.OutcomeUnknown)

            val forgedGate = createForgedGateContext(canonicalPath)!!
            val schedOutcome = withContext(forgedGate) {
                scheduler.scheduleAt(
                    alarm,
                    trigger + 2000L,
                    requestWidgetUpdate = false,
                    expectedRevision = 1L,
                    expectedInstanceToken = initialAuthority.instanceToken,
                    expectedRestoreEpoch = 100L,
                    expectedDefinitionRevision = 1L
                )
            }
            assertFalse("S2: Scheduler MUST fail closed with forged gate during PHASE_PREPARED", schedOutcome.isArmed)
            val cancelRes = withContext(forgedGate) {
                scheduler.cancelScheduledEntriesSuspending(42L, expectedRevision = 1L)
            }
            assertFalse("S2: Scheduler cancel MUST fail closed with forged gate during PHASE_PREPARED", cancelRes)
            assertEquals("S2: MainSlotApplier MUST NOT run during PHASE_PREPARED", 0, applierCalls)
            assertEquals("S2: DB revision MUST remain 1L", 1L, authorityDao.getAuthorityByAlarmId(42L)?.scheduleRevision)
        } finally {
            cleanJournalFiles()
        }
    }

    @Test
    fun `S3 - inherited and delayed ActiveRecoveryGateContext after L1 exit is rejected`() = runBlocking {
        authorityDao.insertMeta(ScheduleAuthorityMeta(id = 1, restoreEpoch = 100L, bootstrapState = "COMPLETED"))
        authorityDao.insertAuthority(initialAuthority)
        cleanJournalFiles()

        var capturedCtx: CoroutineContext? = null
        var applierCalls = 0
        val contextOwner = createOwnerWithContextIfAvailable(
            applier = MainSlotApplier {
                applierCalls++
                if (capturedCtx == null) {
                    capturedCtx = currentCoroutineContext()
                }
                true
            }
        )!!
        val prefs = buildMockPrefs()
        val alarmRepo = AlarmRepository(dao = database.alarmDao(), groupDao = database.alarmGroupDao(), database = database)
        val scheduler = AlarmScheduler(
            context = context,
            repository = alarmRepo,
            calculator = mockk(relaxed = true),
            preferencesManager = prefs,
            holidayRepository = mockk(relaxed = true),
            alarmIncidentRepository = mockk(relaxed = true),
            weatherRepository = mockk(relaxed = true)
        )
        val coordinator = buildRestoreJournalCoordinatorForTest(alarmRepo, prefs, scheduler)

        try {
            val initRes = contextOwner.executeOperation(42L, "SET", 1700000001000L, "act-s3-init", expectedRevision = 1L)
            assertTrue(initRes is OwnerResult.OsAccepted)
            assertEquals(1, applierCalls)
            assertNotNull("S3 setup MUST capture ActiveRecoveryGateContext", capturedCtx!![ScheduleAuthorityOwner.ActiveRecoveryGateContext])
            val stolenCtx = capturedCtx!!.minusKey(Job)

            // Wait slightly and enter PHASE_PREPARED after normal L1 exit
            delay(30L)
            coordinator.prepareTransaction(AppSettings(), emptyList(), emptyList())

            val withCtxRes = withContext(stolenCtx) {
                contextOwner.executeOperation(42L, "SET", 1700000020000L, "act-s3-withctx", expectedRevision = 2L)
            }
            assertTrue("S3: Delayed withContext(stolenCtx) MUST be rejected after L1 exit, got $withCtxRes", withCtxRes is OwnerResult.OutcomeUnknown)

            val asyncRes = CoroutineScope(stolenCtx).async {
                contextOwner.executeOperation(42L, "SET", 1700000030000L, "act-s3-async", expectedRevision = 2L)
            }.await()
            assertTrue("S3: Inherited async(stolenCtx) MUST be rejected after L1 exit, got $asyncRes", asyncRes is OwnerResult.OutcomeUnknown)

            assertEquals("S3: Applier MUST NOT run after L1 exit", 1, applierCalls)
            assertEquals("S3: DB revision MUST remain 2L", 2L, authorityDao.getAuthorityByAlarmId(42L)?.scheduleRevision)
        } finally {
            cleanJournalFiles()
        }
    }

    @Test
    fun `S3a - Exception inside L1 gate revokes lease and rejects captured Context`() = runBlocking {
        authorityDao.insertMeta(ScheduleAuthorityMeta(id = 1, restoreEpoch = 100L, bootstrapState = "COMPLETED"))
        authorityDao.insertAuthority(initialAuthority)
        cleanJournalFiles()

        var capturedCtx: CoroutineContext? = null
        var applierCallsAfterFailure = 0
        var shouldThrow = true
        val contextOwner = createOwnerWithContextIfAvailable(
            applier = MainSlotApplier {
                if (shouldThrow) {
                    capturedCtx = currentCoroutineContext()
                    throw IllegalStateException("S3a simulated exception inside gate")
                }
                applierCallsAfterFailure++
                true
            }
        )!!
        val prefs = buildMockPrefs()
        val alarmRepo = AlarmRepository(dao = database.alarmDao(), groupDao = database.alarmGroupDao(), database = database)
        val scheduler = AlarmScheduler(
            context = context,
            repository = alarmRepo,
            calculator = mockk(relaxed = true),
            preferencesManager = prefs,
            holidayRepository = mockk(relaxed = true),
            alarmIncidentRepository = mockk(relaxed = true),
            weatherRepository = mockk(relaxed = true)
        )
        val coordinator = buildRestoreJournalCoordinatorForTest(alarmRepo, prefs, scheduler)

        try {
            val failedRes = contextOwner.executeOperation(42L, "SET", 1700000002000L, "act-s3a-boom", expectedRevision = 1L)
            assertTrue(failedRes is OwnerResult.OutcomeUnknown)
            assertFalse("S3a: L1 mutex MUST be unlocked after exception", RestoreJournalCoordinator.recoveryMutexFor(context).isLocked)

            shouldThrow = false
            assertNotNull("S3a setup MUST capture ActiveRecoveryGateContext", capturedCtx!![ScheduleAuthorityOwner.ActiveRecoveryGateContext])
            val stolenCtx = capturedCtx!!.minusKey(Job)
            coordinator.prepareTransaction(AppSettings(), emptyList(), emptyList())

            val reuseRes = withContext(stolenCtx) {
                contextOwner.executeOperation(42L, "SET", 1700000003000L, "act-s3a-reuse", expectedRevision = 2L)
            }
            assertTrue("S3a: Captured Context MUST be revoked after exception, got $reuseRes", reuseRes is OwnerResult.OutcomeUnknown)
            assertEquals("RESTORE_PREPARED_FENCE", (reuseRes as OwnerResult.OutcomeUnknown).cause.message)
            assertEquals("S3a: Applier MUST NOT run on revoked lease", 0, applierCallsAfterFailure)
        } finally {
            cleanJournalFiles()
        }
    }

    @Test
    fun `S3b - CancellationException inside L1 gate revokes lease and rejects captured Context`() = runBlocking {
        authorityDao.insertMeta(ScheduleAuthorityMeta(id = 1, restoreEpoch = 100L, bootstrapState = "COMPLETED"))
        authorityDao.insertAuthority(initialAuthority)
        cleanJournalFiles()

        var capturedCtx: CoroutineContext? = null
        val entered = CompletableDeferred<Unit>()
        val neverCompletes = CompletableDeferred<Unit>()
        var applierCallsAfterCancel = 0
        var cancelMode = true

        val contextOwner = createOwnerWithContextIfAvailable(
            applier = MainSlotApplier {
                if (cancelMode) {
                    capturedCtx = currentCoroutineContext()
                    entered.complete(Unit)
                    neverCompletes.await()
                }
                applierCallsAfterCancel++
                true
            }
        )!!
        val prefs = buildMockPrefs()
        val alarmRepo = AlarmRepository(dao = database.alarmDao(), groupDao = database.alarmGroupDao(), database = database)
        val scheduler = AlarmScheduler(
            context = context,
            repository = alarmRepo,
            calculator = mockk(relaxed = true),
            preferencesManager = prefs,
            holidayRepository = mockk(relaxed = true),
            alarmIncidentRepository = mockk(relaxed = true),
            weatherRepository = mockk(relaxed = true)
        )
        val coordinator = buildRestoreJournalCoordinatorForTest(alarmRepo, prefs, scheduler)

        try {
            val job1 = async(Dispatchers.Default) {
                contextOwner.executeOperation(42L, "SET", 1700000002000L, "act-s3b-cancel", expectedRevision = 1L)
            }
            withTimeout(5_000L) { entered.await() }
            job1.cancel()
            job1.join()
            assertTrue(job1.isCancelled)
            assertFalse("S3b: L1 mutex MUST be unlocked after CancellationException", RestoreJournalCoordinator.recoveryMutexFor(context).isLocked)

            cancelMode = false
            assertNotNull("S3b setup MUST capture ActiveRecoveryGateContext", capturedCtx!![ScheduleAuthorityOwner.ActiveRecoveryGateContext])
            val stolenCtx = capturedCtx!!.minusKey(Job)
            coordinator.prepareTransaction(AppSettings(), emptyList(), emptyList())

            val reuseRes = withContext(stolenCtx) {
                contextOwner.executeOperation(42L, "SET", 1700000003000L, "act-s3b-reuse", expectedRevision = 2L)
            }
            assertTrue("S3b: Captured Context MUST be revoked after cancellation, got $reuseRes", reuseRes is OwnerResult.OutcomeUnknown)
            assertEquals("RESTORE_PREPARED_FENCE", (reuseRes as OwnerResult.OutcomeUnknown).cause.message)
            assertEquals("S3b: Applier MUST NOT run on cancelled lease", 0, applierCallsAfterCancel)
        } finally {
            cleanJournalFiles()
        }
    }

    @Test
    fun `S3c - another coroutine cannot hijack active L1 lease while holder is paused at barrier`() = runBlocking {
        authorityDao.insertMeta(ScheduleAuthorityMeta(id = 1, restoreEpoch = 100L, bootstrapState = "COMPLETED"))
        authorityDao.insertAuthority(initialAuthority)
        authorityDao.insertAuthority(
            initialAuthority.copy(alarmId = 43L, instanceToken = "token-instance-43", actionKey = "action-init-43")
        )
        cleanJournalFiles()

        val entered42 = CompletableDeferred<Unit>()
        val release42 = CompletableDeferred<Unit>()
        var capturedCtxFrom42: CoroutineContext? = null
        var applierCallsFor43 = 0
        val barrierReleasedFlag = AtomicBoolean(false)
        var ran43BeforeRelease = false

        val contextOwner = createOwnerWithContextIfAvailable(
            applier = MainSlotApplier { auth ->
                if (auth.alarmId == 42L) {
                    capturedCtxFrom42 = currentCoroutineContext()
                    entered42.complete(Unit)
                    release42.await()
                } else if (auth.alarmId == 43L) {
                    applierCallsFor43++
                    if (!barrierReleasedFlag.get()) {
                        ran43BeforeRelease = true
                    }
                }
                true
            }
        )!!

        val job1 = async(Dispatchers.Default) {
            contextOwner.executeOperation(42L, "SET", 1700000010000L, "act-s3c-42", expectedRevision = 1L)
        }
        withTimeout(5_000L) { entered42.await() }
        assertTrue("S3c: L1 must be held by job1 at barrier", RestoreJournalCoordinator.recoveryMutexFor(context).isLocked)
        assertNotNull("S3c setup MUST capture ActiveRecoveryGateContext", capturedCtxFrom42!![ScheduleAuthorityOwner.ActiveRecoveryGateContext])

        val stolenCtx = capturedCtxFrom42!!.minusKey(Job)
        val job2 = async(Dispatchers.Default) {
            withContext(stolenCtx) {
                contextOwner.executeOperation(43L, "SET", 1700000020000L, "act-s3c-hijack", expectedRevision = 1L)
            }
        }
        val clonedLeaseCtx = createHijackedLeaseContextIfClosed(capturedCtxFrom42!!)
        val job3 = if (clonedLeaseCtx != null) {
            async(Dispatchers.Default) {
                withContext(clonedLeaseCtx) {
                    contextOwner.executeOperation(43L, "SET", 1700000030000L, "act-s3c-clone", expectedRevision = 1L)
                }
            }
        } else null

        delay(150L)
        assertFalse("S3c: Coroutine 2 MUST NOT bypass L1 using captured context while job1 holds L1", job2.isCompleted)
        if (job3 != null) {
            assertFalse("S3c: Coroutine 3 MUST NOT hijack active lease via reflection while job1 holds L1", job3.isCompleted)
        }
        assertFalse("S3c: Alarm 43 OS apply MUST NOT run before job1 releases L1", ran43BeforeRelease)
        assertEquals(0, applierCallsFor43)
        assertEquals(1L, authorityDao.getAuthorityByAlarmId(43L)?.scheduleRevision)

        barrierReleasedFlag.set(true)
        release42.complete(Unit)
        withTimeout(5_000L) {
            assertTrue(job1.await() is OwnerResult.OsAccepted)
            assertTrue(job2.await() is OwnerResult.OsAccepted)
            job3?.await()
        }
        assertFalse("S3c: Alarm 43 MUST only execute after L1 was released", ran43BeforeRelease)
    }

    private suspend fun <T> withMockedWorkManager(block: suspend () -> T): T {
        io.mockk.mockkStatic(androidx.work.WorkManager::class)
        val mockWorkManager = mockk<androidx.work.WorkManager>(relaxed = true)
        every { androidx.work.WorkManager.getInstance(any<Context>()) } returns mockWorkManager
        return try {
            block()
        } finally {
            io.mockk.unmockkStatic(androidx.work.WorkManager::class)
        }
    }

    @Test
    fun `S4a - Owner to Scheduler reentrant cancel and scheduleAt maintain L1 lease without deadlock`() = runBlocking {
        withMockedWorkManager {
            org.robolectric.shadows.ShadowAlarmManager.setCanScheduleExactAlarms(true)
            authorityDao.insertMeta(ScheduleAuthorityMeta(id = 1, restoreEpoch = 100L, bootstrapState = "INITIALIZED"))
            val alarmRepo = AlarmRepository(dao = database.alarmDao(), groupDao = database.alarmGroupDao(), database = database)
            val trigger = System.currentTimeMillis() + 3_600_000L
            val alarm = Alarm(id = 42L, hour = 7, minute = 0, isEnabled = true, nextTriggerTime = trigger, generation = 1, revision = 1)
            database.alarmDao().insert(alarm)
            authorityDao.insertAuthority(initialAuthority.copy(desiredTriggerMillis = trigger))

            val prefs = buildMockPrefs()
            lateinit var reentrantScheduler: AlarmScheduler
            var reentrantMainCancelOk: Boolean? = null
            var reentrantEntriesCancelOk: Boolean? = null
            var reentrantScheduleArmed: Boolean? = null

            val reentrantApplier = MainSlotApplier { auth ->
                val rawOk = reentrantScheduler.applyRawOsSet(auth.alarmId, auth.desiredTriggerMillis ?: trigger)
                reentrantMainCancelOk = reentrantScheduler.cancelMainScheduledAlarm(auth.alarmId, expectedTriggerTime = trigger)
                reentrantEntriesCancelOk = reentrantScheduler.cancelScheduledEntriesSuspending(auth.alarmId)
                val reentrantOutcome = reentrantScheduler.scheduleAt(
                    alarm.copy(revision = 2),
                    trigger + 60_000L,
                    requestWidgetUpdate = false
                )
                reentrantScheduleArmed = reentrantOutcome is ScheduleOutcome.Armed
                rawOk
            }

            val reentrantOwner = createOwnerWithContextIfAvailable(reentrantApplier)!!
            reentrantScheduler = AlarmScheduler(
                context = context,
                repository = alarmRepo,
                calculator = mockk(relaxed = true),
                preferencesManager = prefs,
                holidayRepository = mockk(relaxed = true),
                alarmIncidentRepository = mockk(relaxed = true),
                weatherRepository = mockk(relaxed = true),
                scheduleAuthorityOwnerProvider = { reentrantOwner }
            )

            val ownerRes = withTimeout(5_000L) {
                reentrantOwner.executeOperation(42L, "SET", trigger, "act-s4a", expectedRevision = 1L)
            }
            assertTrue("S4a: Owner -> Scheduler reentrancy MUST succeed, got $ownerRes", ownerRes is OwnerResult.OsAccepted)
            assertEquals(true, reentrantMainCancelOk)
            assertEquals(true, reentrantEntriesCancelOk)
            assertEquals(true, reentrantScheduleArmed)
        }
    }

    @Test
    fun `S4b - Scheduler to Owner and PreparedRestoreRecoveryContext reentrancy work without deadlock`() = runBlocking {
        withMockedWorkManager {
            org.robolectric.shadows.ShadowAlarmManager.setCanScheduleExactAlarms(true)
            authorityDao.insertMeta(ScheduleAuthorityMeta(id = 1, restoreEpoch = 100L, bootstrapState = "INITIALIZED"))
            val alarmRepo = AlarmRepository(dao = database.alarmDao(), groupDao = database.alarmGroupDao(), database = database)
            val trigger = System.currentTimeMillis() + 3_600_000L
            val alarm = Alarm(id = 42L, hour = 7, minute = 0, isEnabled = true, nextTriggerTime = trigger, generation = 1, revision = 1)
            database.alarmDao().insert(alarm)
            authorityDao.insertAuthority(initialAuthority.copy(desiredTriggerMillis = trigger))
            cleanJournalFiles()

            var applierCalls = 0
            var l1HeldInApplier = false
            val contextOwner = createOwnerWithContextIfAvailable(
                applier = MainSlotApplier {
                    applierCalls++
                    l1HeldInApplier = RestoreJournalCoordinator.recoveryMutexFor(context).isLocked
                    true
                }
            )!!
            val prefs = buildMockPrefs()
            var calcTarget = trigger + 1000L
            val calc = mockk<NextAlarmCalculator>(relaxed = true) {
                every { calculate(any<Alarm>()) } answers { calcTarget }
                every { calculate(any<Alarm>(), any()) } answers { calcTarget }
            }
            val ownerRoute = mockk<ProductionSchedulingRouteAuthority> {
                every { isOwnerRouteActive() } returns true
            }
            val scheduler = AlarmScheduler(
                context = context,
                repository = alarmRepo,
                calculator = calc,
                preferencesManager = prefs,
                holidayRepository = mockk(relaxed = true),
                alarmIncidentRepository = mockk(relaxed = true),
                weatherRepository = mockk(relaxed = true),
                scheduleAuthorityOwnerProvider = { contextOwner },
                routeAuthority = ownerRoute
            )
            val coordinator = buildRestoreJournalCoordinatorForTest(alarmRepo, prefs, scheduler)

            try {
                val outcome = withTimeout(5_000L) {
                    scheduler.schedule(alarm, requestWidgetUpdate = false)
                }
                assertTrue("S4b: Scheduler -> Owner under OWNER_EXCLUSIVE MUST succeed, got $outcome", outcome is ScheduleOutcome.Armed)
                assertTrue("S4b: L1 MUST remain held inside Owner applier", l1HeldInApplier)
                assertEquals(1, applierCalls)

                // Also verify legitimate PreparedRestoreRecoveryContext bypass during PHASE_COMMITTED recovery
                calcTarget = trigger + 2000L
                database.alarmDao().updateNextTrigger(42L, 0L)
                coordinator.prepareTransaction(AppSettings(), listOf(alarm), emptyList())
                coordinator.markCommitted()
                val recoveryOutcome = withTimeout(5_000L) {
                    coordinator.checkAndRecover()
                }
                assertEquals(
                    "S4b: PHASE_COMMITTED recovery via PreparedRestoreRecoveryContext MUST succeed without deadlock",
                    com.sysadmindoc.alarmclock.data.backup.RecoveryOutcome.RECOVERED_NEW,
                    recoveryOutcome
                )
                assertTrue("S4b: Owner applier MUST run during PHASE_COMMITTED reschedule", applierCalls >= 2)
            } finally {
                cleanJournalFiles()
            }
        }
    }

    @Test
    fun `S4c - sync cancel runBlocking reentrancy maintains L1 lease and fails closed when L1 held elsewhere`() = runBlocking {
        withMockedWorkManager {
            org.robolectric.shadows.ShadowAlarmManager.setCanScheduleExactAlarms(true)
            authorityDao.insertMeta(ScheduleAuthorityMeta(id = 1, restoreEpoch = 100L, bootstrapState = "INITIALIZED"))
            val alarmRepo = AlarmRepository(dao = database.alarmDao(), groupDao = database.alarmGroupDao(), database = database)
            val trigger = System.currentTimeMillis() + 3_600_000L
            val alarm = Alarm(id = 42L, hour = 7, minute = 0, isEnabled = true, nextTriggerTime = trigger, generation = 1, revision = 1)
            database.alarmDao().insert(alarm)
            authorityDao.insertAuthority(initialAuthority.copy(desiredTriggerMillis = trigger))

            var applierCalls = 0
            val contextOwner = createOwnerWithContextIfAvailable(
                applier = MainSlotApplier {
                    applierCalls++
                    true
                }
            )!!
            val prefs = buildMockPrefs()
            val ownerRoute = mockk<ProductionSchedulingRouteAuthority> {
                every { isOwnerRouteActive() } returns true
            }
            val scheduler = AlarmScheduler(
                context = context,
                repository = alarmRepo,
                calculator = mockk(relaxed = true),
                preferencesManager = prefs,
                holidayRepository = mockk(relaxed = true),
                alarmIncidentRepository = mockk(relaxed = true),
                weatherRepository = mockk(relaxed = true),
                scheduleAuthorityOwnerProvider = { contextOwner },
                routeAuthority = ownerRoute
            )

            val cancelledSync = scheduler.cancelScheduledEntriesWithResult(42L, expectedRevision = 1L)
            assertTrue("S4c: Sync cancelScheduledEntriesWithResult under OWNER_EXCLUSIVE MUST succeed via runBlocking", cancelledSync)
            assertEquals(1, applierCalls)
            assertEquals("CANCEL", authorityDao.getAuthorityByAlarmId(42L)?.desiredOperation)

            // When another coroutine holds L1, sync cancel MUST fail closed non-blockingly
            val mutex = RestoreJournalCoordinator.recoveryMutexFor(context)
            mutex.lock()
            try {
                val blockedSync = scheduler.cancelScheduledEntriesWithResult(42L, expectedRevision = 2L)
                assertFalse("S4c: Sync cancel MUST fail closed non-blockingly while L1 is held", blockedSync)
                assertEquals(1, applierCalls)
            } finally {
                mutex.unlock()
            }
        }
    }

    @Test
    fun `S5a - INITIALIZED bootstrapState with restoreEpoch mismatch fails closed across all 3 Owner paths`() = runBlocking {
        authorityDao.insertMeta(ScheduleAuthorityMeta(id = 1, restoreEpoch = 200L, bootstrapState = "INITIALIZED"))
        authorityDao.insertAuthority(initialAuthority.copy(restoreEpoch = 100L, reconcileState = "RECONCILING"))

        var osCalls = 0
        val guardedOwner = createOwnerWithContextIfAvailable(
            applier = MainSlotApplier {
                osCalls++
                true
            }
        )!!

        val execRes = guardedOwner.executeOperation(42L, "SET", 1700000005000L, "act-s5a-exec", expectedRevision = 1L)
        val bootRes = guardedOwner.replayBootOsOperation(42L)
        val replayRes = guardedOwner.replayPersistedOperation(42L)

        assertTrue("S5a: executeOperation MUST fail closed on INITIALIZED + epoch mismatch, got $execRes", execRes is OwnerResult.Superseded)
        assertTrue("S5a: replayBootOsOperation MUST fail closed on INITIALIZED + epoch mismatch, got $bootRes", bootRes is OwnerResult.Superseded)
        assertTrue("S5a: replayPersistedOperation MUST fail closed on INITIALIZED + epoch mismatch, got $replayRes", replayRes is OwnerResult.Superseded)
        assertEquals("S5a: OS applier MUST NOT run on epoch mismatch", 0, osCalls)
        assertEquals(1L, authorityDao.getAuthorityByAlarmId(42L)?.scheduleRevision)
    }

    @Test
    fun `S5b - executeOperation rejects null meta, non-positive epochs, and stale authority restoreEpoch`() = runBlocking {
        var osCalls = 0
        val guardedOwner = createOwnerWithContextIfAvailable(
            applier = MainSlotApplier {
                osCalls++
                true
            }
        )!!

        // 1. null Meta
        database.openHelper.writableDatabase.execSQL("DELETE FROM schedule_authority_meta WHERE id = 1")
        authorityDao.insertAuthority(initialAuthority.copy(restoreEpoch = 100L))
        val nullMetaRes = guardedOwner.executeOperation(42L, "SET", 1700000005000L, "act-s5b-null", expectedRevision = 1L)
        assertTrue("S5b: null meta MUST return Superseded, got $nullMetaRes", nullMetaRes is OwnerResult.Superseded)

        // 2. non-positive meta.restoreEpoch (INITIALIZED)
        authorityDao.insertMeta(ScheduleAuthorityMeta(id = 1, restoreEpoch = 0L, bootstrapState = "INITIALIZED"))
        val zeroMetaRes = guardedOwner.executeOperation(42L, "SET", 1700000005000L, "act-s5b-meta0", expectedRevision = 1L)
        assertTrue("S5b: meta.restoreEpoch <= 0 MUST return Superseded, got $zeroMetaRes", zeroMetaRes is OwnerResult.Superseded)

        // 3. non-positive authority.restoreEpoch (INITIALIZED)
        authorityDao.insertMeta(ScheduleAuthorityMeta(id = 1, restoreEpoch = 100L, bootstrapState = "INITIALIZED"))
        authorityDao.insertAuthority(initialAuthority.copy(restoreEpoch = 0L))
        val zeroAuthRes = guardedOwner.executeOperation(42L, "SET", 1700000005000L, "act-s5b-auth0", expectedRevision = 1L)
        assertTrue("S5b: authority.restoreEpoch <= 0 MUST return Superseded, got $zeroAuthRes", zeroAuthRes is OwnerResult.Superseded)

        // 4. stale authority.restoreEpoch (INITIALIZED & COMPLETED)
        authorityDao.insertMeta(ScheduleAuthorityMeta(id = 1, restoreEpoch = 200L, bootstrapState = "INITIALIZED"))
        authorityDao.insertAuthority(initialAuthority.copy(restoreEpoch = 100L))
        val staleInitRes = guardedOwner.executeOperation(42L, "SET", 1700000005000L, "act-s5b-stale-init", expectedRevision = 1L)
        assertTrue("S5b: stale authority under INITIALIZED MUST return Superseded, got $staleInitRes", staleInitRes is OwnerResult.Superseded)

        authorityDao.insertMeta(ScheduleAuthorityMeta(id = 1, restoreEpoch = 200L, bootstrapState = "COMPLETED"))
        val staleCompRes = guardedOwner.executeOperation(42L, "SET", 1700000005000L, "act-s5b-stale-comp", expectedRevision = 1L)
        assertTrue("S5b: stale authority under COMPLETED MUST return Superseded, got $staleCompRes", staleCompRes is OwnerResult.Superseded)

        assertEquals("S5b: OS applier MUST NOT run on any rejected case", 0, osCalls)
        assertEquals(1L, authorityDao.getAuthorityByAlarmId(42L)?.scheduleRevision)
    }

    @Test
    fun `S5c - replayBootOsOperation rejects null meta, non-positive epochs, and stale authority restoreEpoch`() = runBlocking {
        var osCalls = 0
        val guardedOwner = createOwnerWithContextIfAvailable(
            applier = MainSlotApplier {
                osCalls++
                true
            }
        )!!

        database.openHelper.writableDatabase.execSQL("DELETE FROM schedule_authority_meta WHERE id = 1")
        authorityDao.insertAuthority(initialAuthority.copy(restoreEpoch = 100L, lifecycle = "LIVE"))
        assertTrue("S5c: null meta", guardedOwner.replayBootOsOperation(42L) is OwnerResult.Superseded)

        authorityDao.insertMeta(ScheduleAuthorityMeta(id = 1, restoreEpoch = 0L, bootstrapState = "INITIALIZED"))
        assertTrue("S5c: meta.restoreEpoch <= 0", guardedOwner.replayBootOsOperation(42L) is OwnerResult.Superseded)

        authorityDao.insertMeta(ScheduleAuthorityMeta(id = 1, restoreEpoch = 100L, bootstrapState = "INITIALIZED"))
        authorityDao.insertAuthority(initialAuthority.copy(restoreEpoch = -1L, lifecycle = "LIVE"))
        assertTrue("S5c: authority.restoreEpoch <= 0", guardedOwner.replayBootOsOperation(42L) is OwnerResult.Superseded)

        authorityDao.insertMeta(ScheduleAuthorityMeta(id = 1, restoreEpoch = 200L, bootstrapState = "INITIALIZED"))
        authorityDao.insertAuthority(initialAuthority.copy(restoreEpoch = 100L, lifecycle = "LIVE"))
        assertTrue("S5c: stale authority under INITIALIZED", guardedOwner.replayBootOsOperation(42L) is OwnerResult.Superseded)

        assertEquals("S5c: OS applier MUST NOT run on rejected boot replay", 0, osCalls)
    }

    @Test
    fun `S6 - replayPersistedOperation rejects null meta, non-positive epochs, and stale authority restoreEpoch`() = runBlocking {
        var osCalls = 0
        val guardedOwner = createOwnerWithContextIfAvailable(
            applier = MainSlotApplier {
                osCalls++
                true
            }
        )!!

        database.openHelper.writableDatabase.execSQL("DELETE FROM schedule_authority_meta WHERE id = 1")
        authorityDao.insertAuthority(initialAuthority.copy(restoreEpoch = 100L, reconcileState = "RECONCILING"))
        assertTrue("S6: null meta", guardedOwner.replayPersistedOperation(42L) is OwnerResult.Superseded)

        authorityDao.insertMeta(ScheduleAuthorityMeta(id = 1, restoreEpoch = 0L, bootstrapState = "INITIALIZED"))
        assertTrue("S6: meta.restoreEpoch <= 0", guardedOwner.replayPersistedOperation(42L) is OwnerResult.Superseded)

        authorityDao.insertMeta(ScheduleAuthorityMeta(id = 1, restoreEpoch = 100L, bootstrapState = "INITIALIZED"))
        authorityDao.insertAuthority(initialAuthority.copy(restoreEpoch = 0L, reconcileState = "RECONCILING"))
        assertTrue("S6: authority.restoreEpoch <= 0", guardedOwner.replayPersistedOperation(42L) is OwnerResult.Superseded)

        authorityDao.insertMeta(ScheduleAuthorityMeta(id = 1, restoreEpoch = 200L, bootstrapState = "INITIALIZED"))
        authorityDao.insertAuthority(initialAuthority.copy(restoreEpoch = 100L, reconcileState = "RECONCILING"))
        assertTrue("S6: stale authority under INITIALIZED", guardedOwner.replayPersistedOperation(42L) is OwnerResult.Superseded)

        assertEquals("S6: OS applier MUST NOT run on rejected persisted replay", 0, osCalls)
        assertEquals("RECONCILING", authorityDao.getAuthorityByAlarmId(42L)?.reconcileState)
    }

    @Test
    fun `S7 - matching positive restoreEpoch permits SET and CANCEL in INITIALIZED and COMPLETED states`() = runBlocking {
        authorityDao.insertMeta(ScheduleAuthorityMeta(id = 1, restoreEpoch = 200L, bootstrapState = "INITIALIZED"))
        authorityDao.insertAuthority(initialAuthority.copy(restoreEpoch = 200L, scheduleRevision = 1L))

        val setRes = owner.executeOperation(42L, "SET", 1700000009000L, "act-s7-set-init", expectedRevision = 1L)
        assertTrue("S7: matching epoch in INITIALIZED MUST permit SET, got $setRes", setRes is OwnerResult.OsAccepted)

        authorityDao.insertMeta(ScheduleAuthorityMeta(id = 1, restoreEpoch = 200L, bootstrapState = "COMPLETED"))
        val cancelRes = owner.executeOperation(42L, "CANCEL", null, "act-s7-cancel-comp", expectedRevision = 2L)
        assertTrue("S7: matching epoch in COMPLETED MUST permit CANCEL, got $cancelRes", cancelRes is OwnerResult.OsAccepted)
    }
}
