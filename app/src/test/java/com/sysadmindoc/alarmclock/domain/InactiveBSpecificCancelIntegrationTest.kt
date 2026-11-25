package com.sysadmindoc.alarmclock.domain

import android.app.AlarmManager
import android.app.Application
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import androidx.work.Operation
import androidx.work.WorkManager
import com.sysadmindoc.alarmclock.data.local.AlarmDatabase
import com.sysadmindoc.alarmclock.data.local.DurableOperationIntentDao
import com.sysadmindoc.alarmclock.data.local.IssuedOccurrenceDao
import com.sysadmindoc.alarmclock.data.local.entity.DurableOperationIntent
import com.sysadmindoc.alarmclock.data.local.entity.IssuedOccurrence
import com.sysadmindoc.alarmclock.data.local.entity.ScheduleAuthority
import com.sysadmindoc.alarmclock.data.model.Alarm
import com.sysadmindoc.alarmclock.data.preferences.AppSettings
import com.sysadmindoc.alarmclock.data.preferences.PreferencesManager
import com.sysadmindoc.alarmclock.data.repository.AlarmIncidentRepository
import com.sysadmindoc.alarmclock.data.repository.AlarmRepository
import com.sysadmindoc.alarmclock.data.repository.HolidayRepository
import com.sysadmindoc.alarmclock.data.repository.WeatherRepository
import com.sysadmindoc.alarmclock.domain.evaluator.DurableCancelIntentValidator
import com.sysadmindoc.alarmclock.domain.evaluator.DurableDispatchOutcome
import com.sysadmindoc.alarmclock.domain.evaluator.DurableOneTimeOverrideAdapter
import com.sysadmindoc.alarmclock.domain.evaluator.DurableReconcileDispatchAdmissionCoordinator
import com.sysadmindoc.alarmclock.domain.evaluator.DurableReconcileRecoveryCoordinator
import com.sysadmindoc.alarmclock.domain.evaluator.DurableRecoveryOutcome
import com.sysadmindoc.alarmclock.domain.evaluator.DurableSkipClaimAdapter
import com.sysadmindoc.alarmclock.service.BedtimeZenRuleManager
import com.sysadmindoc.alarmclock.widget.WidgetUpdater
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import io.mockk.verify
import java.time.DayOfWeek
import java.util.Collections
import java.util.UUID
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = Application::class)
class InactiveBSpecificCancelIntegrationTest {

    private lateinit var context: Context
    private lateinit var database: AlarmDatabase
    private lateinit var issuedDao: IssuedOccurrenceDao
    private lateinit var intentDao: DurableOperationIntentDao
    private lateinit var alarmRepository: AlarmRepository
    private lateinit var integration: InactiveBSpecificCancelIntegration

    private val calculator: NextAlarmCalculator = mockk(relaxed = true)
    private val preferencesManager: PreferencesManager = mockk(relaxed = true)
    private val holidayRepository: HolidayRepository = mockk(relaxed = true)
    private val alarmIncidentRepository: AlarmIncidentRepository = mockk(relaxed = true)
    private val weatherRepository: WeatherRepository = mockk(relaxed = true)

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        org.robolectric.shadows.ShadowAlarmManager.setCanScheduleExactAlarms(true)

        database = Room.inMemoryDatabaseBuilder(context, AlarmDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        issuedDao = database.issuedOccurrenceDao()
        intentDao = database.durableOperationIntentDao()
        alarmRepository = AlarmRepository(
            dao = database.alarmDao(),
            groupDao = database.alarmGroupDao(),
            database = database
        )
        integration = InactiveBSpecificCancelIntegration(database)

        mockkObject(WidgetUpdater)
        every { WidgetUpdater.requestUpdate(any()) } just Runs
        mockkObject(BedtimeZenRuleManager)
        every { BedtimeZenRuleManager.syncRule(any(), any(), any()) } returns mockk(relaxed = true)
        val workManager: WorkManager = mockk(relaxed = true)
        mockkStatic(WorkManager::class)
        every { WorkManager.getInstance(any<Context>()) } returns workManager
        every { workManager.cancelUniqueWork(any()) } returns mockk<Operation>(relaxed = true)

        every { preferencesManager.getCachedSettings() } returns AppSettings()
        coEvery { preferencesManager.getCurrentSettings() } returns AppSettings()
        coEvery { holidayRepository.isHoliday(any()) } returns false
    }

    @After
    fun tearDown() {
        unmockkAll()
        database.close()
    }

    private fun newScheduler(spyAlarmManager: AlarmManager? = null): AlarmScheduler {
        return AlarmScheduler(
            context = context,
            repository = alarmRepository,
            calculator = calculator,
            preferencesManager = preferencesManager,
            holidayRepository = holidayRepository,
            alarmIncidentRepository = alarmIncidentRepository,
            weatherRepository = weatherRepository
        ).apply {
            testAlarmManager = spyAlarmManager
        }
    }

    private data class SeededScenario(
        val alarmId: Long,
        val tokenA: String,
        val tokenB: String,
        val fireIdB: String,
        val scheduledAtA: Long,
        val scheduledAtB: Long,
        val ownerToken: String,
        val restoreEpoch: Long,
        val scheduleRevision: Long,
        val definitionRevision: Long,
        val alarmGeneration: Int,
        val alarmRevision: Int,
        val slotGenIdB: Long
    )

    private suspend fun seedActiveAAndPreparedB(
        alarmId: Long = 42L,
        ownerToken: String = "owner-42-v1",
        restoreEpoch: Long = 1L,
        scheduleRevision: Long = 100L,
        definitionRevision: Long = 10L,
        alarmGeneration: Int = 1,
        alarmRevision: Int = 1,
        scheduledAtA: Long = 1_700_000_000_000L,
        scheduledAtB: Long = 1_700_003_600_000L,
        tokenA: String = "issue-A-$alarmId",
        tokenB: String = "issue-B-$alarmId",
        fireIdB: String = "fire-B-$alarmId",
        isRepeating: Boolean = false
    ): SeededScenario {
        database.withTransaction {
            database.alarmDao().insert(
                Alarm(
                    id = alarmId,
                    hour = 7,
                    minute = 30,
                    isEnabled = true,
                    generation = alarmGeneration,
                    revision = alarmRevision,
                    scheduleKind = if (isRepeating) Alarm.SCHEDULE_KIND_REPEATING else Alarm.SCHEDULE_KIND_ONE_TIME,
                    repeatDays = if (isRepeating) setOf(DayOfWeek.MONDAY) else emptySet(),
                    nextTriggerTime = scheduledAtA
                )
            )
            database.scheduleAuthorityDao().insertAuthority(
                ScheduleAuthority(
                    alarmId = alarmId,
                    instanceToken = ownerToken,
                    definitionRevision = definitionRevision,
                    scheduleRevision = scheduleRevision,
                    lifecycle = "LIVE",
                    desiredOperation = "SET",
                    desiredTriggerMillis = scheduledAtA,
                    reconcileState = "SETTLED",
                    restoreEpoch = restoreEpoch,
                    actionKey = "act-$alarmId"
                )
            )
            issuedDao.reserveIssuedOccurrenceWithAuthority(
                issueToken = tokenA,
                alarmId = alarmId,
                scheduledAt = scheduledAtA,
                fireId = "fire-A-$alarmId",
                authorityInstanceToken = ownerToken,
                restoreEpoch = restoreEpoch,
                scheduleRevision = scheduleRevision,
                definitionRevision = definitionRevision,
                alarmGeneration = alarmGeneration
            )
            issuedDao.activateIssuedOccurrence(tokenA)
            val occB = issuedDao.reserveIssuedOccurrenceWithAuthority(
                issueToken = tokenB,
                alarmId = alarmId,
                scheduledAt = scheduledAtB,
                fireId = fireIdB,
                authorityInstanceToken = ownerToken,
                restoreEpoch = restoreEpoch,
                scheduleRevision = scheduleRevision,
                definitionRevision = definitionRevision,
                alarmGeneration = alarmGeneration
            )!!
            assertEquals(3L, occB.generationId)
        }
        val slot = issuedDao.getSlot(alarmId)!!
        return SeededScenario(
            alarmId = alarmId,
            tokenA = tokenA,
            tokenB = tokenB,
            fireIdB = fireIdB,
            scheduledAtA = scheduledAtA,
            scheduledAtB = scheduledAtB,
            ownerToken = ownerToken,
            restoreEpoch = restoreEpoch,
            scheduleRevision = scheduleRevision,
            definitionRevision = definitionRevision,
            alarmGeneration = alarmGeneration,
            alarmRevision = alarmRevision,
            slotGenIdB = slot.generationId
        )
    }

    private fun requestFor(
        scenario: SeededScenario,
        nowEpochMs: Long = 1_700_000_100_000L,
        deLineageStatus: DeLineageStatus = DeLineageStatus.VERIFIED_MATCH,
        includeFollowUpWorkers: Boolean = false
    ): BSpecificCancelRequest = BSpecificCancelRequest(
        alarmId = scenario.alarmId,
        targetIssueToken = scenario.tokenB,
        targetFireId = scenario.fireIdB,
        targetScheduledAt = scenario.scheduledAtB,
        expectedAlarmGeneration = scenario.alarmGeneration,
        expectedAlarmRevision = scenario.alarmRevision,
        expectedSlotGenId = scenario.slotGenIdB,
        expectedSlotOwnerToken = scenario.tokenA,
        expectedOwnerToken = scenario.ownerToken,
        expectedRestoreEpoch = scenario.restoreEpoch,
        expectedScheduleRevision = scenario.scheduleRevision,
        expectedDefinitionRevision = scenario.definitionRevision,
        nowEpochMs = nowEpochMs,
        deLineageStatus = deLineageStatus,
        includeFollowUpWorkers = includeFollowUpWorkers
    )

    private class FakeOsSlotGateway(
        private val onCancelBehavior: ((String) -> FakeOsCancelOutcome)? = null
    ) : BSpecificOsCancelGateway {
        val slots = linkedMapOf<String, FakeOsLookupObservation.Found>()
        var lookupCount = 0
        var cancelCount = 0
        val cancelledTokens = mutableListOf<String>()

        fun putSlot(
            key: String,
            descriptor: PendingIntentIdentityDescriptor,
            lineage: OccurrenceLineageDescriptor
        ) {
            slots[key] = FakeOsLookupObservation.Found(descriptor, lineage)
        }

        override fun lookupMainSlot(
            alarmId: Long,
            targetIssueToken: String,
            expectedDescriptor: PendingIntentIdentityDescriptor
        ): FakeOsLookupObservation {
            lookupCount++
            return slots[targetIssueToken] ?: FakeOsLookupObservation.Absent
        }

        override fun cancelMainSlot(
            alarmId: Long,
            targetIssueToken: String,
            verifiedDescriptor: PendingIntentIdentityDescriptor
        ): FakeOsCancelOutcome {
            cancelCount++
            onCancelBehavior?.invoke(targetIssueToken)?.let { outcome ->
                if (outcome is FakeOsCancelOutcome.Cancelled) {
                    slots.remove(targetIssueToken)
                    cancelledTokens.add(targetIssueToken)
                }
                return outcome
            }
            val removed = slots.remove(targetIssueToken)
            return if (removed != null) {
                cancelledTokens.add(targetIssueToken)
                FakeOsCancelOutcome.Cancelled
            } else {
                FakeOsCancelOutcome.NotFound
            }
        }
    }

    private fun mainV2FoundFor(
        alarmId: Long,
        token: String,
        scheduledAt: Long,
        ownerToken: String,
        restoreEpoch: Long = 1L,
        scheduleRevision: Long = 100L,
        definitionRevision: Long = 10L,
        generation: Int = 1,
        revision: Int = 1
    ): Pair<PendingIntentIdentityDescriptor, OccurrenceLineageDescriptor> {
        val descriptor = InactiveBSpecificCancelIntegration.expectedMainV2Descriptor(alarmId, token)
        val lineage = OccurrenceLineageDescriptor(
            alarmId = alarmId,
            alarmKind = Alarm.KIND_NORMAL,
            scheduledTriggerMillis = scheduledAt,
            generation = generation,
            revision = revision,
            issueToken = token,
            instanceToken = ownerToken,
            restoreEpoch = restoreEpoch,
            scheduleRevision = scheduleRevision,
            definitionRevision = definitionRevision
        )
        return descriptor to lineage
    }

    // --- Step 2: Legacy / Rev2 Isolation Tests ---

    @Test
    fun `legacyRev1_rescheduleAllInBatches_preservesLookupOsCancelAndCompleted`() = runBlocking {
        val alarmId = 101L
        val triggerTime = 1_700_000_000_000L
        val rev1Token = "legacy-rev1-token-101"

        database.alarmDao().insert(Alarm(id = alarmId, hour = 7, minute = 0, isEnabled = false))
        intentDao.insertValidatedIntent(
            DurableOperationIntent(
                operationToken = rev1Token,
                alarmId = alarmId,
                operationEpoch = 1000L,
                incarnationId = "fire-rev1",
                operationKind = "CANCEL",
                expectedOwnerToken = null,
                expectedScheduleRevision = null,
                expectedRestoreEpoch = null,
                phase = DurableOperationIntent.PHASE_PENDING,
                intentRevision = 1L,
                originalFireId = "fire-rev1",
                originalScheduledAt = triggerTime
            )
        )

        val schedulerForPi = newScheduler()
        schedulerForPi.createTokenizedPendingIntent(alarmId, triggerTime, "fire-rev1", rev1Token)
        assertNotNull(schedulerForPi.lookupTokenizedPendingIntentForCancel(alarmId, rev1Token))

        val spyAm = mockk<AlarmManager>(relaxed = true)
        val scheduler = newScheduler(spyAlarmManager = spyAm)
        scheduler.rescheduleAllInBatches()

        verify(exactly = 1) { spyAm.cancel(any<PendingIntent>()) }
        assertNull("Rev1 PendingIntent MUST be cancelled by legacy recovery", scheduler.lookupTokenizedPendingIntentForCancel(alarmId, rev1Token))
        val updated = intentDao.getIntentByToken(rev1Token)!!
        assertEquals(DurableOperationIntent.PHASE_COMPLETED, updated.phase)
    }

    @Test
    fun `rev2BSpecificCancel_rescheduleAllInBatches_isolatedFromLookupOsCancelCompletedAndAborted`() = runBlocking {
        val scenario = seedActiveAAndPreparedB(alarmId = 102L)
        database.withTransaction {
            intentDao.issueBSpecificCancelIntentTransaction(
                issuedDao = issuedDao,
                scheduleAuthorityDao = database.scheduleAuthorityDao(),
                alarmDao = database.alarmDao(),
                targetIssueToken = scenario.tokenB,
                now = 1_700_000_000_500L
            )
        }

        val schedulerForPi = newScheduler()
        schedulerForPi.createTokenizedPendingIntent(
            scenario.alarmId,
            scenario.scheduledAtB,
            scenario.fireIdB,
            scenario.tokenB
        )
        assertNotNull(schedulerForPi.lookupTokenizedPendingIntentForCancel(scenario.alarmId, scenario.tokenB))

        val spyAm = mockk<AlarmManager>(relaxed = true)
        val scheduler = newScheduler(spyAlarmManager = spyAm)
        scheduler.rescheduleAllInBatches()

        verify(exactly = 0) { spyAm.cancel(any<PendingIntent>()) }
        assertNotNull(
            "Rev2 B PendingIntent MUST NOT be cancelled by legacy rescheduleAllInBatches",
            scheduler.lookupTokenizedPendingIntentForCancel(scenario.alarmId, scenario.tokenB)
        )
        val intentAfter = intentDao.getIntentByToken(scenario.tokenB)!!
        assertEquals(
            "Rev2 CANCEL intent MUST stay PHASE_PENDING and NOT reach generic COMPLETED/ABORTED",
            DurableOperationIntent.PHASE_PENDING,
            intentAfter.phase
        )
    }

    @Test
    fun `unknownRevisionCancel_rescheduleAllInBatches_failsClosedWithoutLookupOsCancelOrPhaseChange`() = runBlocking {
        val alarmId = 103L
        val unknownToken = "unknown-rev3-cancel-token"
        database.alarmDao().insert(Alarm(id = alarmId, hour = 7, minute = 0, isEnabled = false))
        intentDao.insertValidatedIntent(
            DurableOperationIntent(
                operationToken = unknownToken,
                alarmId = alarmId,
                operationEpoch = 1000L,
                incarnationId = "fire-rev3",
                operationKind = "CANCEL",
                expectedOwnerToken = "owner-103",
                expectedScheduleRevision = 1L,
                expectedRestoreEpoch = 1L,
                phase = DurableOperationIntent.PHASE_PENDING,
                intentRevision = 3L,
                originalFireId = "fire-rev3",
                originalScheduledAt = 1_700_000_500_000L
            )
        )

        val schedulerForPi = newScheduler()
        schedulerForPi.createTokenizedPendingIntent(alarmId, 1_700_000_500_000L, "fire-rev3", unknownToken)

        val spyAm = mockk<AlarmManager>(relaxed = true)
        val scheduler = newScheduler(spyAlarmManager = spyAm)
        scheduler.rescheduleAllInBatches()

        verify(exactly = 0) { spyAm.cancel(any<PendingIntent>()) }
        assertNotNull(scheduler.lookupTokenizedPendingIntentForCancel(alarmId, unknownToken))
        assertEquals(DurableOperationIntent.PHASE_PENDING, intentDao.getIntentByToken(unknownToken)!!.phase)
    }

    @Test
    fun `rev2AndUnknownCancel_isolatedFromGenericAbortedInAdaptersAndCoordinators`() = runBlocking {
        val scenario = seedActiveAAndPreparedB(alarmId = 104L, isRepeating = true)
        val now = 1_700_000_100_000L
        val futureTarget = scenario.scheduledAtA + 86_400_000L

        database.withTransaction {
            intentDao.issueBSpecificCancelIntentTransaction(
                issuedDao = issuedDao,
                scheduleAuthorityDao = database.scheduleAuthorityDao(),
                alarmDao = database.alarmDao(),
                targetIssueToken = scenario.tokenB,
                now = now
            )
            intentDao.insertValidatedIntent(
                DurableOperationIntent(
                    operationToken = "unknown-rev99-cancel-104",
                    alarmId = scenario.alarmId,
                    operationEpoch = now + 1L,
                    incarnationId = "fire-rev99",
                    operationKind = "CANCEL",
                    expectedOwnerToken = scenario.ownerToken,
                    expectedScheduleRevision = scenario.scheduleRevision,
                    expectedRestoreEpoch = scenario.restoreEpoch,
                    phase = DurableOperationIntent.PHASE_PENDING,
                    intentRevision = 99L,
                    originalFireId = "fire-rev99",
                    originalScheduledAt = futureTarget
                )
            )
        }

        // Verify coordinators fail-closed/isolate without generic PHASE_ABORTED
        val recoveryCoordinator = DurableReconcileRecoveryCoordinator(database)
        val recRes = recoveryCoordinator.recoverUnresolvedIntent(scenario.tokenB, now)
        assertEquals(DurableRecoveryOutcome.NEEDS_EVIDENCE_DEFERRED, recRes.outcome)
        assertEquals(DurableOperationIntent.PHASE_PENDING, intentDao.getIntentByToken(scenario.tokenB)!!.phase)

        val dispatchCoordinator = DurableReconcileDispatchAdmissionCoordinator(database)
        val dispRes = dispatchCoordinator.evaluateDispatchAdmission("unknown-rev99-cancel-104", now)
        assertEquals(DurableDispatchOutcome.ADMISSION_REJECTED_QUARANTINED, dispRes.outcome)
        assertEquals(DurableOperationIntent.PHASE_PENDING, intentDao.getIntentByToken("unknown-rev99-cancel-104")!!.phase)

        // Verify skip/override adapters do NOT generically abort Rev2 or unknown CANCEL intents
        val skipAdapter = DurableSkipClaimAdapter(database)
        skipAdapter.executeSkipClaimAndReconcile(
            alarmId = scenario.alarmId,
            expectedScheduledAt = scenario.scheduledAtA,
            nowEpochMs = now
        )
        assertEquals(
            "Rev2 CANCEL intent MUST NOT be transitioned to generic PHASE_ABORTED by DurableSkipClaimAdapter",
            DurableOperationIntent.PHASE_PENDING,
            intentDao.getIntentByToken(scenario.tokenB)!!.phase
        )
        assertEquals(
            "Unknown CANCEL intent MUST NOT be transitioned to generic PHASE_ABORTED by DurableSkipClaimAdapter",
            DurableOperationIntent.PHASE_PENDING,
            intentDao.getIntentByToken("unknown-rev99-cancel-104")!!.phase
        )

        val overrideAdapter = DurableOneTimeOverrideAdapter(database)
        overrideAdapter.cancelOverride(
            alarmId = scenario.alarmId,
            expectedScheduledAt = scenario.scheduledAtA,
            nowEpochMs = now + 5000L
        )
        assertEquals(
            "Rev2 CANCEL intent MUST NOT be transitioned to generic PHASE_ABORTED by DurableOneTimeOverrideAdapter",
            DurableOperationIntent.PHASE_PENDING,
            intentDao.getIntentByToken(scenario.tokenB)!!.phase
        )
        assertEquals(
            "Unknown CANCEL intent MUST NOT be transitioned to generic PHASE_ABORTED by DurableOneTimeOverrideAdapter",
            DurableOperationIntent.PHASE_PENDING,
            intentDao.getIntentByToken("unknown-rev99-cancel-104")!!.phase
        )
    }

    // --- Step 3 & Step 4: P3C Admission + Fake OS Boundary + MAIN PI Identity + B->C Race + 8 Effects ---

    @Test
    fun `fake01_exactB_admitsViaP3C_verifiesMainIdentity_cancelsOnlyFakeB_protectsAandC_keepsRealOsBlocked`() = runBlocking {
        val scenario = seedActiveAAndPreparedB(alarmId = 201L)
        val tokenC = "issue-C-201"
        val gateway = FakeOsSlotGateway()

        val (descA, linA) = mainV2FoundFor(scenario.alarmId, scenario.tokenA, 1_700_000_000_000L, scenario.ownerToken)
        val (descB, linB) = mainV2FoundFor(scenario.alarmId, scenario.tokenB, scenario.scheduledAtB, scenario.ownerToken)
        val (descC, linC) = mainV2FoundFor(scenario.alarmId, tokenC, 1_700_007_200_000L, scenario.ownerToken)
        gateway.putSlot(scenario.tokenA, descA, linA)
        gateway.putSlot(scenario.tokenB, descB, linB)
        gateway.putSlot(tokenC, descC, linC)

        val result = integration.executeFakeBoundaryCancel(requestFor(scenario), gateway)

        assertEquals(BSpecificCancelIntegrationOutcome.FAKE_OS_CANCEL_OBSERVED_NOT_REAL_PROOF, result.outcome)
        assertTrue(result.p3cIntentIssued)
        assertEquals(1, result.p3cCasRowsUpdated)
        assertEquals(DurableOperationIntent.PHASE_IN_FLIGHT, result.durableIntentPhaseAfter)
        assertTrue(result.identitySafety is CrossIdentitySafety.ExactCandidate)
        assertFalse(result.bToCRaceDetected)
        assertTrue(result.fakeOsLookupInvoked)
        assertTrue(result.fakeOsCancelInvoked)
        assertEquals(1, gateway.lookupCount)
        assertEquals(1, gateway.cancelCount)
        assertEquals(listOf(scenario.tokenB), gateway.cancelledTokens)

        // A and C remain untouched in Fake OS and Room
        assertTrue(gateway.slots.containsKey(scenario.tokenA))
        assertFalse(gateway.slots.containsKey(scenario.tokenB))
        assertTrue(gateway.slots.containsKey(tokenC))
        assertEquals(scenario.tokenA, issuedDao.getSlot(scenario.alarmId)!!.currentIssueToken)
        assertEquals(IssuedOccurrence.STATUS_ISSUED, issuedDao.getOccurrence(scenario.tokenA)!!.status)
        assertEquals(IssuedOccurrence.STATUS_PREPARED, issuedDao.getOccurrence(scenario.tokenB)!!.status)

        // 8-effect CancellationExecutionBoundary invariants & non-real-OS guarantees
        assertEquals(8, result.boundaryReport.decisions.size)
        assertTrue(result.boundaryReport.isAllNonExecutable)
        assertTrue(result.boundaryReport.decisions[SideEffectTarget.MAIN_OS_PENDING_INTENT] is PureEffectDecision.CandidateForFutureVerification)
        assertEquals(RealOsEligibility.BLOCKED, result.realOsEligibility)
        assertEquals(0, result.realOsCalls)
    }

    @Test
    fun `fake02_staleB_slotSupersededByC_rejectsAtP3CWithoutInvokingFakeOs`() = runBlocking {
        val scenario = seedActiveAAndPreparedB(alarmId = 202L)
        // Advance slot generation to 4 by reserving C before B cancel request runs
        database.withTransaction {
            issuedDao.reserveIssuedOccurrenceWithAuthority(
                issueToken = "issue-C-202",
                alarmId = scenario.alarmId,
                scheduledAt = scenario.scheduledAtB + 3_600_000L,
                fireId = "fire-C-202",
                authorityInstanceToken = scenario.ownerToken,
                restoreEpoch = scenario.restoreEpoch,
                scheduleRevision = scenario.scheduleRevision,
                definitionRevision = scenario.definitionRevision,
                alarmGeneration = scenario.alarmGeneration
            )
        }

        val gateway = FakeOsSlotGateway()
        val (descB, linB) = mainV2FoundFor(scenario.alarmId, scenario.tokenB, scenario.scheduledAtB, scenario.ownerToken)
        gateway.putSlot(scenario.tokenB, descB, linB)

        val result = integration.executeFakeBoundaryCancel(requestFor(scenario), gateway)

        assertEquals(BSpecificCancelIntegrationOutcome.ADMISSION_REJECTED_STALE_OR_MISMATCH, result.outcome)
        assertFalse(result.p3cIntentIssued)
        assertEquals(0, result.p3cCasRowsUpdated)
        assertNull("Rollback MUST leave no durable intent for stale B", intentDao.getIntentByToken(scenario.tokenB))
        assertFalse(result.fakeOsLookupInvoked)
        assertFalse(result.fakeOsCancelInvoked)
        assertEquals(0, gateway.lookupCount)
        assertEquals(0, gateway.cancelCount)
        assertTrue(result.boundaryReport.isAllNonExecutable)
        SideEffectTarget.values().forEach { target ->
            assertTrue(result.boundaryReport.decisions[target] is PureEffectDecision.Blocked)
        }
        assertEquals(RealOsEligibility.BLOCKED, result.realOsEligibility)
    }

    @Test
    fun `fake03_bToCRace_interleavedAfterP3CCasBeforePreBinderCheck_abortsAndBlocksAllEightEffects`() = runBlocking {
        val scenario = seedActiveAAndPreparedB(alarmId = 203L)
        val gateway = FakeOsSlotGateway()
        val (descB, linB) = mainV2FoundFor(scenario.alarmId, scenario.tokenB, scenario.scheduledAtB, scenario.ownerToken)
        gateway.putSlot(scenario.tokenB, descB, linB)

        val result = integration.executeFakeBoundaryCancel(
            request = requestFor(scenario),
            gateway = gateway,
            onAfterCasBeforePreBinderCheck = {
                // Concurrent Successor C arrives right after P3C DB CAS transitioned B to IN_FLIGHT
                database.withTransaction {
                    issuedDao.reserveIssuedOccurrenceWithAuthority(
                        issueToken = "issue-C-203",
                        alarmId = scenario.alarmId,
                        scheduledAt = scenario.scheduledAtB + 3_600_000L,
                        fireId = "fire-C-203",
                        authorityInstanceToken = scenario.ownerToken,
                        restoreEpoch = scenario.restoreEpoch,
                        scheduleRevision = scenario.scheduleRevision,
                        definitionRevision = scenario.definitionRevision,
                        alarmGeneration = scenario.alarmGeneration
                    )
                }
            }
        )

        assertEquals(BSpecificCancelIntegrationOutcome.B_TO_C_RACE_ABORTED, result.outcome)
        assertTrue(result.bToCRaceDetected)
        assertEquals(1, result.p3cCasRowsUpdated)
        assertEquals(DurableOperationIntent.PHASE_IN_FLIGHT, result.durableIntentPhaseAfter)
        assertFalse(result.fakeOsCancelInvoked)
        assertEquals(0, gateway.cancelCount)
        SideEffectTarget.values().forEach { target ->
            val decision = result.boundaryReport.decisions[target]
            assertTrue("Expected Blocked for $target", decision is PureEffectDecision.Blocked)
            assertEquals(CancellationRejectionReason.STALE_GENERATION, (decision as PureEffectDecision.Blocked).reason)
        }
        assertEquals(RealOsEligibility.BLOCKED, result.realOsEligibility)
    }

    @Test
    fun `fake03b_bToCRace_interleavedAfterPreCheckBeforeBinderCancel_detectsToctouAndKeepsRealOsBlocked`() = runBlocking {
        val scenario = seedActiveAAndPreparedB(alarmId = 204L)
        val gateway = FakeOsSlotGateway()
        val (descB, linB) = mainV2FoundFor(scenario.alarmId, scenario.tokenB, scenario.scheduledAtB, scenario.ownerToken)
        gateway.putSlot(scenario.tokenB, descB, linB)

        val result = integration.executeFakeBoundaryCancel(
            request = requestFor(scenario),
            gateway = gateway,
            onBeforeFakeCancelInvocation = {
                // Concurrent Successor C arrives in the Binder TOCTOU gap after pre-check passed
                database.withTransaction {
                    issuedDao.reserveIssuedOccurrenceWithAuthority(
                        issueToken = "issue-C-204",
                        alarmId = scenario.alarmId,
                        scheduledAt = scenario.scheduledAtB + 3_600_000L,
                        fireId = "fire-C-204",
                        authorityInstanceToken = scenario.ownerToken,
                        restoreEpoch = scenario.restoreEpoch,
                        scheduleRevision = scenario.scheduleRevision,
                        definitionRevision = scenario.definitionRevision,
                        alarmGeneration = scenario.alarmGeneration
                    )
                }
            }
        )

        assertEquals(BSpecificCancelIntegrationOutcome.B_TO_C_RACE_ABORTED, result.outcome)
        assertTrue(result.bToCRaceDetected)
        SideEffectTarget.values().forEach { target ->
            val decision = result.boundaryReport.decisions[target]
            assertTrue(decision is PureEffectDecision.Blocked)
            assertEquals(CancellationRejectionReason.STALE_GENERATION, (decision as PureEffectDecision.Blocked).reason)
        }
        assertEquals(RealOsEligibility.BLOCKED, result.realOsEligibility)
    }

    @Test
    fun `fake04_legacyPiAlias_rejectedByMainIdentityComparator_zeroFakeOsCancel`() = runBlocking {
        val scenario = seedActiveAAndPreparedB(alarmId = 205L)
        val gateway = FakeOsSlotGateway()
        val legacyDescriptor = PendingIntentIdentityDescriptor(
            operationType = PendingIntentOperationType.BROADCAST,
            creatorPackage = "com.sysadmindoc.alarmclock",
            creatorUserScope = 0,
            targetComponent = "com.sysadmindoc.alarmclock.receiver.AlarmReceiver",
            intentAction = "com.sysadmindoc.alarmclock.ALARM_FIRE",
            intentDataUri = null,
            requestCode = scenario.alarmId.toInt(),
            flags = PendingIntentIdentityComparator.FLAG_IMMUTABLE,
            isLegacyV1Alias = true
        )
        val (_, linB) = mainV2FoundFor(scenario.alarmId, scenario.tokenB, scenario.scheduledAtB, scenario.ownerToken)
        gateway.putSlot(scenario.tokenB, legacyDescriptor, linB)

        val result = integration.executeFakeBoundaryCancel(requestFor(scenario), gateway)

        assertEquals(BSpecificCancelIntegrationOutcome.PI_IDENTITY_REJECTED, result.outcome)
        assertTrue(result.identitySafety is CrossIdentitySafety.TargetMismatch)
        assertTrue(result.fakeOsLookupInvoked)
        assertFalse(result.fakeOsCancelInvoked)
        assertEquals(0, gateway.cancelCount)
        val mainDecision = result.boundaryReport.decisions[SideEffectTarget.MAIN_OS_PENDING_INTENT]
        assertTrue(mainDecision is PureEffectDecision.Blocked)
        assertEquals(CancellationRejectionReason.OS_STATE_UNCERTAIN, (mainDecision as PureEffectDecision.Blocked).reason)
        assertEquals(RealOsEligibility.BLOCKED, result.realOsEligibility)
    }

    @Test
    fun `fake05_directBootPiAlias_v1AndV2_rejectedByMainIdentityComparator_zeroFakeOsCancel`() = runBlocking {
        val scenarioV1 = seedActiveAAndPreparedB(alarmId = 206L)
        val gatewayV1 = FakeOsSlotGateway()
        val directBootV1Descriptor = PendingIntentIdentityDescriptor(
            operationType = PendingIntentOperationType.BROADCAST,
            creatorPackage = "com.sysadmindoc.alarmclock",
            creatorUserScope = 0,
            targetComponent = "com.sysadmindoc.alarmclock.directboot.DirectBootAlarmReceiver",
            intentAction = "com.sysadmindoc.alarmclock.ACTION_FIRE_DIRECT_BOOT_ALARM",
            intentDataUri = null,
            requestCode = scenarioV1.alarmId.toInt(),
            flags = PendingIntentIdentityComparator.FLAG_IMMUTABLE,
            isLegacyV1Alias = false
        )
        val (_, lin1) = mainV2FoundFor(scenarioV1.alarmId, scenarioV1.tokenB, scenarioV1.scheduledAtB, scenarioV1.ownerToken)
        gatewayV1.putSlot(scenarioV1.tokenB, directBootV1Descriptor, lin1)

        val resultV1 = integration.executeFakeBoundaryCancel(requestFor(scenarioV1), gatewayV1)
        assertEquals(BSpecificCancelIntegrationOutcome.PI_IDENTITY_REJECTED, resultV1.outcome)
        assertTrue(resultV1.identitySafety is CrossIdentitySafety.TargetMismatch)
        assertFalse(resultV1.fakeOsCancelInvoked)
        assertEquals(0, gatewayV1.cancelCount)
        assertTrue(resultV1.boundaryReport.decisions[SideEffectTarget.MAIN_OS_PENDING_INTENT] is PureEffectDecision.Blocked)

        val scenarioV2 = seedActiveAAndPreparedB(alarmId = 207L)
        val gatewayV2 = FakeOsSlotGateway()
        val directBootV2Descriptor = PendingIntentIdentityDescriptor(
            operationType = PendingIntentOperationType.BROADCAST,
            creatorPackage = "com.sysadmindoc.alarmclock",
            creatorUserScope = 0,
            targetComponent = "com.sysadmindoc.alarmclock.directboot.DirectBootAlarmReceiver",
            intentAction = "com.sysadmindoc.alarmclock.ACTION_FIRE_V2_NORMAL_ALARM",
            intentDataUri = "acx-directboot://normal/alarm/${scenarioV2.alarmId}/v2/0123456789abcdef",
            requestCode = 80001,
            flags = PendingIntentIdentityComparator.FLAG_IMMUTABLE,
            isLegacyV1Alias = false
        )
        val (_, lin2) = mainV2FoundFor(scenarioV2.alarmId, scenarioV2.tokenB, scenarioV2.scheduledAtB, scenarioV2.ownerToken)
        gatewayV2.putSlot(scenarioV2.tokenB, directBootV2Descriptor, lin2)

        val resultV2 = integration.executeFakeBoundaryCancel(requestFor(scenarioV2), gatewayV2)
        assertEquals(BSpecificCancelIntegrationOutcome.PI_IDENTITY_REJECTED, resultV2.outcome)
        assertTrue(resultV2.identitySafety is CrossIdentitySafety.TargetMismatch)
        assertFalse(resultV2.fakeOsCancelInvoked)
        assertEquals(0, gatewayV2.cancelCount)
    }

    @Test
    fun `fake06_noPendingIntent_lookupNull_preservesIntentAndMarksEvidenceIncomplete`() = runBlocking {
        val scenario = seedActiveAAndPreparedB(alarmId = 208L)
        val gateway = FakeOsSlotGateway() // empty Fake OS slots

        val result = integration.executeFakeBoundaryCancel(requestFor(scenario), gateway)

        assertEquals(BSpecificCancelIntegrationOutcome.PI_ABSENT_NO_DESTRUCTIVE_CLEANUP, result.outcome)
        assertTrue(result.fakeOsLookupInvoked)
        assertFalse(result.fakeOsCancelInvoked)
        assertEquals(DurableOperationIntent.PHASE_IN_FLIGHT, result.durableIntentPhaseAfter)
        val mainDecision = result.boundaryReport.decisions[SideEffectTarget.MAIN_OS_PENDING_INTENT]
        assertTrue(mainDecision is PureEffectDecision.EvidenceIncomplete)
        assertEquals("PENDING_INTENT_LOOKUP_NULL", (mainDecision as PureEffectDecision.EvidenceIncomplete).missingWitness)
        assertEquals(RealOsEligibility.BLOCKED, result.realOsEligibility)
    }

    @Test
    fun `fake07_osResultUnknown_preservesIntentAndMarksEvidenceIncomplete`() = runBlocking {
        val scenario = seedActiveAAndPreparedB(alarmId = 209L)
        val gateway = FakeOsSlotGateway { FakeOsCancelOutcome.Unknown("INDETERMINATE_OS_IPC") }
        val (descB, linB) = mainV2FoundFor(scenario.alarmId, scenario.tokenB, scenario.scheduledAtB, scenario.ownerToken)
        gateway.putSlot(scenario.tokenB, descB, linB)

        val result = integration.executeFakeBoundaryCancel(requestFor(scenario), gateway)

        assertEquals(BSpecificCancelIntegrationOutcome.OS_OUTCOME_UNKNOWN_PRESERVED, result.outcome)
        assertEquals(DurableOperationIntent.PHASE_IN_FLIGHT, result.durableIntentPhaseAfter)
        val mainDecision = result.boundaryReport.decisions[SideEffectTarget.MAIN_OS_PENDING_INTENT]
        val deDecision = result.boundaryReport.decisions[SideEffectTarget.DIRECT_BOOT_SNAPSHOT]
        assertTrue(mainDecision is PureEffectDecision.EvidenceIncomplete)
        assertEquals("OS_STATE_UNKNOWN", (mainDecision as PureEffectDecision.EvidenceIncomplete).missingWitness)
        assertTrue(deDecision is PureEffectDecision.EvidenceIncomplete)
        assertEquals("OS_STATE_UNKNOWN", (deDecision as PureEffectDecision.EvidenceIncomplete).missingWitness)
        assertEquals(RealOsEligibility.BLOCKED, result.realOsEligibility)
    }

    @Test
    fun `fake08_osException_preservesIntentAndMarksEvidenceIncomplete`() = runBlocking {
        val scenario = seedActiveAAndPreparedB(alarmId = 210L)
        val gateway = FakeOsSlotGateway { throw RuntimeException("Simulated AlarmManager Binder exception") }
        val (descB, linB) = mainV2FoundFor(scenario.alarmId, scenario.tokenB, scenario.scheduledAtB, scenario.ownerToken)
        gateway.putSlot(scenario.tokenB, descB, linB)

        val result = integration.executeFakeBoundaryCancel(requestFor(scenario), gateway)

        assertEquals(BSpecificCancelIntegrationOutcome.OS_EXCEPTION_PRESERVED, result.outcome)
        assertEquals(DurableOperationIntent.PHASE_IN_FLIGHT, result.durableIntentPhaseAfter)
        val mainDecision = result.boundaryReport.decisions[SideEffectTarget.MAIN_OS_PENDING_INTENT]
        val deDecision = result.boundaryReport.decisions[SideEffectTarget.DIRECT_BOOT_SNAPSHOT]
        assertTrue(mainDecision is PureEffectDecision.EvidenceIncomplete)
        assertEquals("OS_STATE_UNKNOWN", (mainDecision as PureEffectDecision.EvidenceIncomplete).missingWitness)
        assertTrue(deDecision is PureEffectDecision.EvidenceIncomplete)
        assertEquals(RealOsEligibility.BLOCKED, result.realOsEligibility)
    }

    @Test
    fun `fake09_duplicateRequest_rejectedByP3CCas_zeroSecondFakeOsCancel`() = runBlocking {
        val scenario = seedActiveAAndPreparedB(alarmId = 211L)
        val gateway = FakeOsSlotGateway()
        val (descB, linB) = mainV2FoundFor(scenario.alarmId, scenario.tokenB, scenario.scheduledAtB, scenario.ownerToken)
        gateway.putSlot(scenario.tokenB, descB, linB)

        val first = integration.executeFakeBoundaryCancel(requestFor(scenario), gateway)
        assertEquals(BSpecificCancelIntegrationOutcome.FAKE_OS_CANCEL_OBSERVED_NOT_REAL_PROOF, first.outcome)
        assertEquals(1, gateway.cancelCount)

        val second = integration.executeFakeBoundaryCancel(requestFor(scenario), gateway)
        assertEquals(BSpecificCancelIntegrationOutcome.DUPLICATE_REQUEST_REJECTED, second.outcome)
        assertFalse(second.p3cIntentIssued)
        assertEquals(0, second.p3cCasRowsUpdated)
        assertEquals(DurableOperationIntent.PHASE_IN_FLIGHT, second.durableIntentPhaseAfter)
        assertFalse(second.fakeOsLookupInvoked)
        assertFalse(second.fakeOsCancelInvoked)
        assertEquals("Duplicate request MUST NOT invoke Fake OS cancel a second time", 1, gateway.cancelCount)
        assertEquals(RealOsEligibility.BLOCKED, second.realOsEligibility)
    }

    @Test
    fun `u1_sharedOwnerMutex_serializesInactiveMainV2BCancelAndCSet_withoutDeadlockOrCrosstalk`() = runBlocking {
        val sharedOwner = ScheduleAuthorityOwner(
            authorityDao = database.scheduleAuthorityDao(),
            mainSlotApplier = MainSlotApplier { true },
            exactPermissionChecker = { true }
        )
        val mutexIntegration = InactiveBSpecificCancelIntegration(
            database = database,
            scheduleAuthorityOwner = sharedOwner
        )

        val scenario1 = seedActiveAAndPreparedB(
            alarmId = 301L,
            tokenA = UUID.randomUUID().toString(),
            tokenB = UUID.randomUUID().toString()
        )
        val scenario2 = seedActiveAAndPreparedB(
            alarmId = 302L,
            tokenA = UUID.randomUUID().toString(),
            tokenB = UUID.randomUUID().toString()
        )

        val fakeCancelGateway = FakeOsSlotGateway()
        val (descB, linB) = mainV2FoundFor(scenario1.alarmId, scenario1.tokenB, scenario1.scheduledAtB, scenario1.ownerToken)
        fakeCancelGateway.putSlot(scenario1.tokenB, descB, linB)

        val fakeSetSlots = Collections.synchronizedMap(linkedMapOf<String, Long>())
        val fakeSetGateway = object : MainV2FakeOsSetGateway {
            override fun setMainSlot(
                alarmId: Long,
                issueToken: String,
                scheduledAt: Long,
                descriptor: PendingIntentIdentityDescriptor,
                lineage: OccurrenceLineageDescriptor
            ): Boolean {
                fakeSetSlots[issueToken] = scheduledAt
                return true
            }
        }

        val events = Collections.synchronizedList(mutableListOf<String>())
        val bInsideLock = CompletableDeferred<Unit>()
        val allowBProceed = CompletableDeferred<Unit>()
        val otherAlarmCompleted = CompletableDeferred<Unit>()

        val bCancelJob = async(Dispatchers.Default) {
            val res = mutexIntegration.executeFakeBoundaryCancel(
                request = requestFor(scenario1),
                gateway = fakeCancelGateway,
                onAfterCasBeforePreBinderCheck = {
                    events.add("B_INSIDE_301")
                    bInsideLock.complete(Unit)
                    allowBProceed.await()
                    events.add("B_RELEASE_301")
                }
            )
            events.add("B_DONE_301")
            res
        }

        withTimeout(5_000L) { bInsideLock.await() }

        val tokenC1 = UUID.randomUUID().toString()
        val cSetSameAlarmJob = async(Dispatchers.Default) {
            val res = mutexIntegration.executeFakeMainV2Set(
                request = MainV2FakeSetRequest(
                    alarmId = scenario1.alarmId,
                    issueToken = tokenC1,
                    fireId = "fire-C-301",
                    scheduledAt = scenario1.scheduledAtB + 3_600_000L,
                    expectedAlarmGeneration = scenario1.alarmGeneration,
                    expectedOwnerToken = scenario1.ownerToken,
                    expectedRestoreEpoch = scenario1.restoreEpoch,
                    expectedScheduleRevision = scenario1.scheduleRevision,
                    expectedDefinitionRevision = scenario1.definitionRevision,
                    nowEpochMs = 1_700_000_200_000L
                ),
                gateway = fakeSetGateway,
                onInsideLockBeforeFakeSet = {
                    events.add("C_INSIDE_301")
                }
            )
            events.add("C_DONE_301")
            res
        }

        val tokenC2 = UUID.randomUUID().toString()
        val cSetOtherAlarmJob = async(Dispatchers.Default) {
            val res = mutexIntegration.executeFakeMainV2Set(
                request = MainV2FakeSetRequest(
                    alarmId = scenario2.alarmId,
                    issueToken = tokenC2,
                    fireId = "fire-C-302",
                    scheduledAt = scenario2.scheduledAtB + 3_600_000L,
                    expectedAlarmGeneration = scenario2.alarmGeneration,
                    expectedOwnerToken = scenario2.ownerToken,
                    expectedRestoreEpoch = scenario2.restoreEpoch,
                    expectedScheduleRevision = scenario2.scheduleRevision,
                    expectedDefinitionRevision = scenario2.definitionRevision,
                    nowEpochMs = 1_700_000_200_000L
                ),
                gateway = fakeSetGateway,
                onInsideLockBeforeFakeSet = {
                    events.add("C_INSIDE_302")
                }
            )
            events.add("C_DONE_302")
            otherAlarmCompleted.complete(Unit)
            res
        }

        withTimeout(5_000L) { otherAlarmCompleted.await() }
        val otherRes = cSetOtherAlarmJob.await()
        assertEquals(MainV2FakeSetOutcome.FAKE_OS_SET_ACTIVATED_INACTIVE, otherRes.outcome)
        assertFalse("Same alarmId=301 C-Set MUST wait while B-Cancel holds ScheduleAuthorityOwner mutex", cSetSameAlarmJob.isCompleted)

        allowBProceed.complete(Unit)
        val (bRes, cRes) = withTimeout(5_000L) { bCancelJob.await() to cSetSameAlarmJob.await() }

        assertEquals(BSpecificCancelIntegrationOutcome.FAKE_OS_CANCEL_OBSERVED_NOT_REAL_PROOF, bRes.outcome)
        assertEquals(MainV2FakeSetOutcome.FAKE_OS_SET_ACTIVATED_INACTIVE, cRes.outcome)
        assertEquals(tokenC1, cRes.currentSlotTokenAfter)
        assertEquals(tokenC1, issuedDao.getSlotCurrentToken(scenario1.alarmId))
        assertEquals(IssuedOccurrence.STATUS_ISSUED, issuedDao.getOccurrence(tokenC1)!!.status)
        assertTrue(fakeSetSlots.containsKey(tokenC1))
        assertEquals(0, bRes.realOsCalls)
        assertEquals(0, cRes.realOsCalls)

        val order301 = events.filter { it.endsWith("_301") }
        assertEquals(
            listOf("B_INSIDE_301", "B_RELEASE_301", "B_DONE_301", "C_INSIDE_301", "C_DONE_301"),
            order301
        )
    }

    @Test
    fun `u1b_sharedOwnerMutex_cancellationAndException_unlocksAndKeepsSuccessorIntact`() = runBlocking {
        val sharedOwner = ScheduleAuthorityOwner(
            authorityDao = database.scheduleAuthorityDao(),
            mainSlotApplier = MainSlotApplier { true },
            exactPermissionChecker = { true }
        )
        val mutexIntegration = InactiveBSpecificCancelIntegration(
            database = database,
            scheduleAuthorityOwner = sharedOwner
        )

        val scenario = seedActiveAAndPreparedB(
            alarmId = 303L,
            tokenA = UUID.randomUUID().toString(),
            tokenB = UUID.randomUUID().toString()
        )
        val fakeCancelGateway = FakeOsSlotGateway()
        val (descB, linB) = mainV2FoundFor(scenario.alarmId, scenario.tokenB, scenario.scheduledAtB, scenario.ownerToken)
        fakeCancelGateway.putSlot(scenario.tokenB, descB, linB)

        val cancelled = try {
            mutexIntegration.executeFakeBoundaryCancel(
                request = requestFor(scenario),
                gateway = fakeCancelGateway,
                onBeforeFakeCancelInvocation = {
                    throw CancellationException("Simulated coroutine cancel under lock")
                }
            )
            false
        } catch (_: CancellationException) {
            true
        }
        assertTrue(cancelled)

        // Lock must be released so subsequent C-Set succeeds and activates C
        val tokenC = UUID.randomUUID().toString()
        val fakeSetGateway = object : MainV2FakeOsSetGateway {
            override fun setMainSlot(
                alarmId: Long,
                issueToken: String,
                scheduledAt: Long,
                descriptor: PendingIntentIdentityDescriptor,
                lineage: OccurrenceLineageDescriptor
            ): Boolean = true
        }
        val cRes = withTimeout(5_000L) {
            mutexIntegration.executeFakeMainV2Set(
                request = MainV2FakeSetRequest(
                    alarmId = scenario.alarmId,
                    issueToken = tokenC,
                    fireId = "fire-C-303",
                    scheduledAt = scenario.scheduledAtB + 3_600_000L,
                    expectedAlarmGeneration = scenario.alarmGeneration,
                    expectedOwnerToken = scenario.ownerToken,
                    expectedRestoreEpoch = scenario.restoreEpoch,
                    expectedScheduleRevision = scenario.scheduleRevision,
                    expectedDefinitionRevision = scenario.definitionRevision,
                    nowEpochMs = 1_700_000_250_000L
                ),
                gateway = fakeSetGateway
            )
        }
        assertEquals(MainV2FakeSetOutcome.FAKE_OS_SET_ACTIVATED_INACTIVE, cRes.outcome)
        assertEquals(tokenC, issuedDao.getSlotCurrentToken(scenario.alarmId))
    }
}
