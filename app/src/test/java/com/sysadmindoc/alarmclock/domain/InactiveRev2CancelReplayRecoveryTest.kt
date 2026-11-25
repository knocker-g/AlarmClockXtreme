package com.sysadmindoc.alarmclock.domain

import android.app.AlarmManager
import android.app.Application
import android.app.PendingIntent
import android.content.Context
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
import com.sysadmindoc.alarmclock.domain.evaluator.DurableDispatchOutcome
import com.sysadmindoc.alarmclock.domain.evaluator.DurableReconcileDispatchAdmissionCoordinator
import com.sysadmindoc.alarmclock.domain.evaluator.DurableReconcileRecoveryCoordinator
import com.sysadmindoc.alarmclock.domain.evaluator.DurableRecoveryOutcome
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
import kotlinx.coroutines.runBlocking
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
class InactiveRev2CancelReplayRecoveryTest {

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
        alarmId: Long,
        ownerToken: String = "owner-$alarmId-v1",
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
        reconcileState: String = "SETTLED"
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
                    scheduleKind = Alarm.SCHEDULE_KIND_ONE_TIME,
                    repeatDays = emptySet(),
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
                    reconcileState = reconcileState,
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
            issuedDao.reserveIssuedOccurrenceWithAuthority(
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

    private suspend fun issueRev2Intent(
        scenario: SeededScenario,
        now: Long = 1_700_000_050_000L,
        transitionToInFlight: Boolean = false
    ): DurableOperationIntent {
        return database.withTransaction {
            val issued = intentDao.issueBSpecificCancelIntentTransaction(
                issuedDao = issuedDao,
                scheduleAuthorityDao = database.scheduleAuthorityDao(),
                alarmDao = database.alarmDao(),
                targetIssueToken = scenario.tokenB,
                now = now
            )
            if (transitionToInFlight) {
                val casRows = intentDao.casTransitionBSpecificCancelIntentToInFlight(
                    operationToken = scenario.tokenB,
                    alarmId = scenario.alarmId,
                    expectedSlotGenId = scenario.slotGenIdB,
                    expectedSlotOwnerToken = scenario.tokenA,
                    expectedOwnerToken = scenario.ownerToken
                )
                assertEquals(1, casRows)
                intentDao.getIntentByToken(scenario.tokenB)!!
            } else {
                issued
            }
        }
    }

    private class FakeReadOnlySlotGateway(
        private val onCancelFailIfCalled: Boolean = true
    ) : BSpecificOsCancelGateway {
        val slots = linkedMapOf<String, FakeOsLookupObservation.Found>()
        var lookupCount = 0
        var cancelCount = 0

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
            if (onCancelFailIfCalled) {
                throw AssertionError("Replay recovery MUST NEVER invoke cancelMainSlot")
            }
            return FakeOsCancelOutcome.Cancelled
        }
    }

    private fun mainV2FoundFor(scenario: SeededScenario): Pair<PendingIntentIdentityDescriptor, OccurrenceLineageDescriptor> {
        val descriptor = InactiveBSpecificCancelIntegration.expectedMainV2Descriptor(
            alarmId = scenario.alarmId,
            targetIssueToken = scenario.tokenB
        )
        val lineage = OccurrenceLineageDescriptor(
            alarmId = scenario.alarmId,
            alarmKind = Alarm.KIND_NORMAL,
            scheduledTriggerMillis = scenario.scheduledAtB,
            generation = scenario.alarmGeneration,
            revision = scenario.alarmRevision,
            issueToken = scenario.tokenB,
            instanceToken = scenario.ownerToken,
            restoreEpoch = scenario.restoreEpoch,
            scheduleRevision = scenario.scheduleRevision,
            definitionRevision = scenario.definitionRevision
        )
        return descriptor to lineage
    }

    private fun inspectionContextFor(
        scenario: SeededScenario,
        now: Long = 1_700_000_200_000L,
        cutpointHint: Rev2ReplayCrashCutpoint? = null,
        priorFakeOsOutcome: FakeOsCancelOutcome? = null
    ) = Rev2ReplayInspectionContext(
        alarmId = scenario.alarmId,
        targetIssueToken = scenario.tokenB,
        evaluationTimeMillis = now,
        expectedAlarmGeneration = scenario.alarmGeneration,
        expectedAlarmRevision = scenario.alarmRevision,
        expectedSlotGenId = scenario.slotGenIdB,
        expectedSlotOwnerToken = scenario.tokenA,
        expectedOwnerToken = scenario.ownerToken,
        expectedRestoreEpoch = scenario.restoreEpoch,
        expectedScheduleRevision = scenario.scheduleRevision,
        expectedDefinitionRevision = scenario.definitionRevision,
        crashCutpointHint = cutpointHint,
        priorFakeOsOutcome = priorFakeOsOutcome
    )

    private fun assertAllEightEffectsBlockedAndNoSettlement(result: Rev2ReplayRecoveryResult) {
        assertEquals(0, result.realOsCalls)
        assertFalse(result.fakeOsCancelInvoked)
        assertEquals(0, result.completedSettlementCount)
        assertEquals(0, result.abortedTransitionCount)
        assertFalse(result.asyncCleanupScheduled)
        assertTrue(result.successorCProtected)
        assertEquals(RealOsEligibility.BLOCKED, result.realOsEligibility)
        assertEquals(8, result.boundaryReport.decisions.size)
        assertTrue(result.boundaryReport.isAllNonExecutable)
        SideEffectTarget.values().forEach { target ->
            assertTrue(
                "Expected Blocked for $target on outcome ${result.outcome}",
                result.boundaryReport.decisions[target] is PureEffectDecision.Blocked
            )
        }
    }

    private data class CutpointFixture(
        val cutpoint: Rev2ReplayCrashCutpoint,
        val issueIntent: Boolean,
        val inFlight: Boolean,
        val attachGateway: Boolean,
        val priorFakeOutcome: FakeOsCancelOutcome?,
        val expectedOutcome: Rev2ReplayRecoveryOutcome,
        val expectedPhase: String?,
        val expectedLookupInvoked: Boolean
    )

    @Test
    fun `parameterized_crashCutpoints_c0ThroughC6_safelyClassifiedWithoutOsCancelOrCompleted`() = runBlocking {
        val fixtures = listOf(
            CutpointFixture(
                cutpoint = Rev2ReplayCrashCutpoint.C0_PRE_INTENT_ISSUE,
                issueIntent = false,
                inFlight = false,
                attachGateway = false,
                priorFakeOutcome = null,
                expectedOutcome = Rev2ReplayRecoveryOutcome.C0_PRE_INTENT_ABSENT,
                expectedPhase = null,
                expectedLookupInvoked = false
            ),
            CutpointFixture(
                cutpoint = Rev2ReplayCrashCutpoint.C1_POST_PENDING_PERSISTED,
                issueIntent = true,
                inFlight = false,
                attachGateway = false,
                priorFakeOutcome = null,
                expectedOutcome = Rev2ReplayRecoveryOutcome.PENDING_SAFE_B_CANDIDATE_HOLD,
                expectedPhase = DurableOperationIntent.PHASE_PENDING,
                expectedLookupInvoked = false
            ),
            CutpointFixture(
                cutpoint = Rev2ReplayCrashCutpoint.C2_AROUND_IN_FLIGHT_CAS,
                issueIntent = true,
                inFlight = true,
                attachGateway = false,
                priorFakeOutcome = null,
                expectedOutcome = Rev2ReplayRecoveryOutcome.IN_FLIGHT_CRASH_EVIDENCE_MISSING_HOLD,
                expectedPhase = DurableOperationIntent.PHASE_IN_FLIGHT,
                expectedLookupInvoked = false
            ),
            CutpointFixture(
                cutpoint = Rev2ReplayCrashCutpoint.C3_AROUND_OS_LOOKUP,
                issueIntent = true,
                inFlight = true,
                attachGateway = true,
                priorFakeOutcome = null,
                expectedOutcome = Rev2ReplayRecoveryOutcome.IN_FLIGHT_CRASH_EVIDENCE_MISSING_HOLD,
                expectedPhase = DurableOperationIntent.PHASE_IN_FLIGHT,
                expectedLookupInvoked = true
            ),
            CutpointFixture(
                cutpoint = Rev2ReplayCrashCutpoint.C4_AROUND_FAKE_CANCEL_REQUEST,
                issueIntent = true,
                inFlight = true,
                attachGateway = false,
                priorFakeOutcome = null,
                expectedOutcome = Rev2ReplayRecoveryOutcome.IN_FLIGHT_CRASH_EVIDENCE_MISSING_HOLD,
                expectedPhase = DurableOperationIntent.PHASE_IN_FLIGHT,
                expectedLookupInvoked = false
            ),
            CutpointFixture(
                cutpoint = Rev2ReplayCrashCutpoint.C5_AROUND_FAKE_RESULT_RECEIPT,
                issueIntent = true,
                inFlight = true,
                attachGateway = false,
                priorFakeOutcome = FakeOsCancelOutcome.Cancelled,
                expectedOutcome = Rev2ReplayRecoveryOutcome.IN_FLIGHT_FAKE_RESULT_NOT_REAL_PROOF_HOLD,
                expectedPhase = DurableOperationIntent.PHASE_IN_FLIGHT,
                expectedLookupInvoked = false
            ),
            CutpointFixture(
                cutpoint = Rev2ReplayCrashCutpoint.C6_REBOOT_WITH_UNKNOWN_OS_OUTCOME,
                issueIntent = true,
                inFlight = true,
                attachGateway = false,
                priorFakeOutcome = null,
                expectedOutcome = Rev2ReplayRecoveryOutcome.IN_FLIGHT_UNKNOWN_OS_RESTART_HOLD,
                expectedPhase = DurableOperationIntent.PHASE_IN_FLIGHT,
                expectedLookupInvoked = false
            )
        )

        fixtures.forEachIndexed { idx, fixture ->
            val alarmId = 300L + idx
            val scenario = seedActiveAAndPreparedB(alarmId = alarmId)
            if (fixture.issueIntent) {
                issueRev2Intent(scenario = scenario, transitionToInFlight = fixture.inFlight)
            }
            val gateway = if (fixture.attachGateway) {
                FakeReadOnlySlotGateway().apply {
                    val (descB, linB) = mainV2FoundFor(scenario)
                    putSlot(scenario.tokenB, descB, linB)
                }
            } else {
                null
            }

            // Simulate process restart with fresh integration instance reading Room state
            val restartedIntegration = InactiveBSpecificCancelIntegration(database)
            val result = restartedIntegration.replaySingleRev2CancelIntent(
                context = inspectionContextFor(
                    scenario = scenario,
                    cutpointHint = fixture.cutpoint,
                    priorFakeOsOutcome = fixture.priorFakeOutcome
                ),
                gateway = gateway
            )

            assertEquals("Cutpoint ${fixture.cutpoint} route", Rev2ReplayRoute.REV2_INACTIVE_REPLAY, result.route)
            assertEquals("Cutpoint ${fixture.cutpoint} outcome", fixture.expectedOutcome, result.outcome)
            assertEquals("Cutpoint ${fixture.cutpoint} cutpoint", fixture.cutpoint, result.crashCutpoint)
            assertEquals("Cutpoint ${fixture.cutpoint} phaseBefore", fixture.expectedPhase, result.intentPhaseBefore)
            assertEquals("Cutpoint ${fixture.cutpoint} phaseAfter", fixture.expectedPhase, result.intentPhaseAfter)
            assertEquals("Cutpoint ${fixture.cutpoint} lookupInvoked", fixture.expectedLookupInvoked, result.fakeOsLookupInvoked)
            assertEquals(fixture.expectedPhase, intentDao.getIntentByToken(scenario.tokenB)?.phase)
            if (gateway != null) {
                assertEquals(0, gateway.cancelCount)
            }
            assertAllEightEffectsBlockedAndNoSettlement(result)
        }
    }

    @Test
    fun `pendingAuthenticB_verifiedAcrossDaosAndReadOnlyLookup_heldAsSafeCandidateWithoutOsCancel`() = runBlocking {
        val scenario = seedActiveAAndPreparedB(alarmId = 310L)
        issueRev2Intent(scenario = scenario, transitionToInFlight = false)

        val gateway = FakeReadOnlySlotGateway().apply {
            val (descB, linB) = mainV2FoundFor(scenario)
            putSlot(scenario.tokenB, descB, linB)
        }

        val result = integration.replaySingleRev2CancelIntent(
            context = inspectionContextFor(
                scenario = scenario,
                cutpointHint = Rev2ReplayCrashCutpoint.C1_POST_PENDING_PERSISTED
            ),
            gateway = gateway
        )

        assertEquals(Rev2ReplayRoute.REV2_INACTIVE_REPLAY, result.route)
        assertEquals(Rev2ReplayRecoveryOutcome.PENDING_SAFE_B_CANDIDATE_HOLD, result.outcome)
        assertTrue(result.identitySafety is CrossIdentitySafety.ExactCandidate)
        assertEquals(RecoveryDecisionOutcome.NO_ACTION, result.adjudicatorDecision?.outcome)
        assertTrue(result.fakeOsLookupInvoked)
        assertEquals(1, gateway.lookupCount)
        assertEquals(0, gateway.cancelCount)
        assertTrue(gateway.slots.containsKey(scenario.tokenB))
        assertEquals(DurableOperationIntent.PHASE_PENDING, intentDao.getIntentByToken(scenario.tokenB)?.phase)
        assertAllEightEffectsBlockedAndNoSettlement(result)
    }

    private data class StaleWitnessMutation(
        val label: String,
        val issueStandardIntent: Boolean = true,
        val mutateAndBuildContext: suspend (SeededScenario) -> Rev2ReplayInspectionContext
    )

    @Test
    fun `parameterized_pendingStaleB_witnessMismatches_failClosedIsolatedWithoutOsTouch`() = runBlocking {
        val mutations = listOf(
            StaleWitnessMutation("ownerTokenMismatch") { s ->
                database.withTransaction {
                    val sa = database.scheduleAuthorityDao().getAuthorityByAlarmId(s.alarmId)!!
                    database.scheduleAuthorityDao().insertAuthority(sa.copy(instanceToken = "owner-newer"))
                }
                inspectionContextFor(s)
            },
            StaleWitnessMutation("restoreEpochMismatch") { s ->
                database.withTransaction {
                    val sa = database.scheduleAuthorityDao().getAuthorityByAlarmId(s.alarmId)!!
                    database.scheduleAuthorityDao().insertAuthority(sa.copy(restoreEpoch = s.restoreEpoch + 1L))
                }
                inspectionContextFor(s)
            },
            StaleWitnessMutation("scheduleRevisionMismatch") { s ->
                database.withTransaction {
                    val sa = database.scheduleAuthorityDao().getAuthorityByAlarmId(s.alarmId)!!
                    database.scheduleAuthorityDao().insertAuthority(sa.copy(scheduleRevision = s.scheduleRevision + 1L))
                }
                inspectionContextFor(s)
            },
            StaleWitnessMutation("definitionRevisionMismatch") { s ->
                database.withTransaction {
                    val sa = database.scheduleAuthorityDao().getAuthorityByAlarmId(s.alarmId)!!
                    database.scheduleAuthorityDao().insertAuthority(sa.copy(definitionRevision = s.definitionRevision + 1L))
                }
                inspectionContextFor(s)
            },
            StaleWitnessMutation("alarmGenerationMismatch") { s ->
                database.withTransaction {
                    val alarm = database.alarmDao().getById(s.alarmId)!!
                    database.alarmDao().update(alarm.copy(generation = s.alarmGeneration + 1))
                }
                inspectionContextFor(s)
            },
            StaleWitnessMutation("alarmRevisionMismatch") { s ->
                database.withTransaction {
                    val alarm = database.alarmDao().getById(s.alarmId)!!
                    database.alarmDao().update(alarm.copy(revision = s.alarmRevision + 1))
                }
                inspectionContextFor(s)
            },
            StaleWitnessMutation("authorityQuarantined") { s ->
                database.withTransaction {
                    val sa = database.scheduleAuthorityDao().getAuthorityByAlarmId(s.alarmId)!!
                    database.scheduleAuthorityDao().insertAuthority(sa.copy(reconcileState = "QUARANTINE"))
                }
                inspectionContextFor(s)
            },
            StaleWitnessMutation("alarmDefinitionMissing") { s ->
                database.withTransaction {
                    val alarm = database.alarmDao().getById(s.alarmId)!!
                    database.alarmDao().delete(alarm)
                }
                inspectionContextFor(s)
            },
            StaleWitnessMutation("fireIdMismatchWithOccurrence", issueStandardIntent = false) { s ->
                database.withTransaction {
                    intentDao.insertValidatedIntent(
                        DurableOperationIntent(
                            operationToken = s.tokenB,
                            alarmId = s.alarmId,
                            operationEpoch = 1_700_000_050_000L,
                            incarnationId = "fire-stale-other",
                            operationKind = "CANCEL",
                            expectedOwnerToken = s.ownerToken,
                            expectedScheduleRevision = s.scheduleRevision,
                            expectedRestoreEpoch = s.restoreEpoch,
                            phase = DurableOperationIntent.PHASE_PENDING,
                            intentRevision = 2L,
                            originalFireId = "fire-stale-other",
                            originalScheduledAt = s.scheduledAtB
                        )
                    )
                }
                inspectionContextFor(s)
            }
        )

        mutations.forEachIndexed { idx, mutation ->
            val label = mutation.label
            val alarmId = 320L + idx
            val scenario = seedActiveAAndPreparedB(alarmId = alarmId)
            if (mutation.issueStandardIntent) {
                issueRev2Intent(scenario = scenario, transitionToInFlight = false)
            }
            val ctx = mutation.mutateAndBuildContext(scenario)
            val gateway = FakeReadOnlySlotGateway().apply {
                val (descB, linB) = mainV2FoundFor(scenario)
                putSlot(scenario.tokenB, descB, linB)
            }

            val result = integration.replaySingleRev2CancelIntent(context = ctx, gateway = gateway)

            assertEquals("$label outcome", Rev2ReplayRecoveryOutcome.PENDING_STALE_OR_CONFLICT_ISOLATED, result.outcome)
            assertEquals("$label phase preserved", DurableOperationIntent.PHASE_PENDING, result.intentPhaseAfter)
            assertFalse("$label lookup skipped on fail-closed DB", result.fakeOsLookupInvoked)
            assertEquals(0, gateway.lookupCount)
            assertEquals(0, gateway.cancelCount)
            assertAllEightEffectsBlockedAndNoSettlement(result)
        }
    }

    @Test
    fun `inFlightPostCasCrash_andRestart_preservesInFlightAndDoesNotTriggerLegacyIncarnationFalseQuarantine`() = runBlocking {
        val scenario = seedActiveAAndPreparedB(alarmId = 340L)
        val issuedIntent = issueRev2Intent(scenario = scenario, transitionToInFlight = true)

        // Confirm Rev2 structural property that caused Legacy adjudicator mismatch if unguarded:
        // Rev2 intent stores fireId in incarnationId and ownerToken in expectedOwnerToken.
        assertEquals(scenario.fireIdB, issuedIntent.incarnationId)
        assertEquals(scenario.ownerToken, issuedIntent.expectedOwnerToken)
        assertTrue(issuedIntent.incarnationId != scenario.ownerToken)

        val restartedIntegration = InactiveBSpecificCancelIntegration(database)
        val batchReport = restartedIntegration.replayUnresolvedCancelIntents(
            evaluationTimeMillis = 1_700_000_250_000L
        )

        assertEquals(1, batchReport.totalUnresolvedScanned)
        assertEquals(1, batchReport.rev2ClassifiedCount)
        assertEquals(0, batchReport.completedSettlementCount)
        assertEquals(0, batchReport.abortedTransitionCount)
        assertEquals(0, batchReport.realOsCalls)

        val result = batchReport.results.single()
        assertEquals(Rev2ReplayRecoveryOutcome.IN_FLIGHT_CRASH_EVIDENCE_MISSING_HOLD, result.outcome)
        assertEquals(Rev2ReplayCrashCutpoint.C2_AROUND_IN_FLIGHT_CAS, result.crashCutpoint)
        assertEquals(DurableOperationIntent.PHASE_IN_FLIGHT, result.intentPhaseAfter)
        // Crucial: RecoveryAdjudicator must NOT falsely quarantine valid Rev2 intent as STALE_INCARNATION
        assertEquals(RecoveryDecisionOutcome.NO_ACTION, result.adjudicatorDecision?.outcome)
        assertAllEightEffectsBlockedAndNoSettlement(result)
        assertEquals(DurableOperationIntent.PHASE_IN_FLIGHT, intentDao.getIntentByToken(scenario.tokenB)?.phase)
    }

    @Test
    fun `piAbsentDuringReplay_lookupAbsentAndPriorNotFound_heldWithoutDestructiveCleanup`() = runBlocking {
        // Case 1: PENDING/IN_FLIGHT replay lookup sees Fake OS slot Absent
        val scenarioLookupAbsent = seedActiveAAndPreparedB(alarmId = 350L)
        issueRev2Intent(scenario = scenarioLookupAbsent, transitionToInFlight = true)
        val emptyGateway = FakeReadOnlySlotGateway()

        val absentResult = integration.replaySingleRev2CancelIntent(
            context = inspectionContextFor(
                scenario = scenarioLookupAbsent,
                cutpointHint = Rev2ReplayCrashCutpoint.C3_AROUND_OS_LOOKUP
            ),
            gateway = emptyGateway
        )
        assertEquals(Rev2ReplayRecoveryOutcome.PI_ABSENT_HOLD, absentResult.outcome)
        assertTrue(absentResult.fakeOsLookupInvoked)
        assertEquals(1, emptyGateway.lookupCount)
        assertEquals(0, emptyGateway.cancelCount)
        assertEquals(DurableOperationIntent.PHASE_IN_FLIGHT, absentResult.intentPhaseAfter)
        assertAllEightEffectsBlockedAndNoSettlement(absentResult)

        // Case 2: C5 IN_FLIGHT replay observes prior FakeOsCancelOutcome.NotFound
        val scenarioPriorNotFound = seedActiveAAndPreparedB(alarmId = 351L)
        issueRev2Intent(scenario = scenarioPriorNotFound, transitionToInFlight = true)
        val notFoundResult = integration.replaySingleRev2CancelIntent(
            context = inspectionContextFor(
                scenario = scenarioPriorNotFound,
                cutpointHint = Rev2ReplayCrashCutpoint.C5_AROUND_FAKE_RESULT_RECEIPT,
                priorFakeOsOutcome = FakeOsCancelOutcome.NotFound
            ),
            gateway = emptyGateway
        )
        assertEquals(Rev2ReplayRecoveryOutcome.PI_ABSENT_HOLD, notFoundResult.outcome)
        assertEquals(DurableOperationIntent.PHASE_IN_FLIGHT, notFoundResult.intentPhaseAfter)
        assertAllEightEffectsBlockedAndNoSettlement(notFoundResult)
    }

    @Test
    fun `piAliasDuringReplay_legacyV1AndDirectBootAliases_rejectedFailClosed`() = runBlocking {
        val aliasConfigs = listOf(
            "legacyV1" to { alarmId: Long ->
                PendingIntentIdentityDescriptor(
                    operationType = PendingIntentOperationType.BROADCAST,
                    creatorPackage = "com.sysadmindoc.alarmclock",
                    creatorUserScope = 0,
                    targetComponent = "com.sysadmindoc.alarmclock.receiver.AlarmReceiver",
                    intentAction = "com.sysadmindoc.alarmclock.ALARM_FIRE",
                    intentDataUri = null,
                    requestCode = alarmId.toInt(),
                    flags = PendingIntentIdentityComparator.FLAG_IMMUTABLE,
                    isLegacyV1Alias = true
                )
            },
            "directBootV1" to { alarmId: Long ->
                PendingIntentIdentityDescriptor(
                    operationType = PendingIntentOperationType.BROADCAST,
                    creatorPackage = "com.sysadmindoc.alarmclock",
                    creatorUserScope = 0,
                    targetComponent = "com.sysadmindoc.alarmclock.directboot.DirectBootAlarmReceiver",
                    intentAction = "com.sysadmindoc.alarmclock.ACTION_FIRE_DIRECT_BOOT_ALARM",
                    intentDataUri = null,
                    requestCode = alarmId.toInt(),
                    flags = PendingIntentIdentityComparator.FLAG_IMMUTABLE,
                    isLegacyV1Alias = false
                )
            },
            "directBootV2" to { alarmId: Long ->
                PendingIntentIdentityDescriptor(
                    operationType = PendingIntentOperationType.BROADCAST,
                    creatorPackage = "com.sysadmindoc.alarmclock",
                    creatorUserScope = 0,
                    targetComponent = "com.sysadmindoc.alarmclock.directboot.DirectBootAlarmReceiver",
                    intentAction = "com.sysadmindoc.alarmclock.ACTION_FIRE_V2_NORMAL_ALARM",
                    intentDataUri = "acx-directboot://normal/alarm/$alarmId/v2/deadbeef",
                    requestCode = 80001,
                    flags = PendingIntentIdentityComparator.FLAG_IMMUTABLE,
                    isLegacyV1Alias = false
                )
            }
        )

        aliasConfigs.forEachIndexed { idx, (label, buildDescriptor) ->
            val alarmId = 360L + idx
            val scenario = seedActiveAAndPreparedB(alarmId = alarmId)
            issueRev2Intent(scenario = scenario, transitionToInFlight = false)
            val (_, linB) = mainV2FoundFor(scenario)
            val gateway = FakeReadOnlySlotGateway().apply {
                putSlot(scenario.tokenB, buildDescriptor(alarmId), linB)
            }

            val result = integration.replaySingleRev2CancelIntent(
                context = inspectionContextFor(scenario),
                gateway = gateway
            )
            assertEquals("$label outcome", Rev2ReplayRecoveryOutcome.PI_IDENTITY_REJECTED_ISOLATED, result.outcome)
            assertTrue("$label mismatch", result.identitySafety is CrossIdentitySafety.TargetMismatch)
            assertEquals(DurableOperationIntent.PHASE_PENDING, result.intentPhaseAfter)
            assertEquals(0, gateway.cancelCount)
            assertAllEightEffectsBlockedAndNoSettlement(result)
        }
    }

    @Test
    fun `parameterized_unknownOsOutcomeExceptionAndReboot_failClosedAndKeepInFlight`() = runBlocking {
        val unknownInputs: List<Pair<String, FakeOsCancelOutcome?>> = listOf(
            "osOutcomeUnknown" to FakeOsCancelOutcome.Unknown("IPC_RESULT_UNKNOWN"),
            "osOutcomeException" to FakeOsCancelOutcome.ExceptionThrown(RuntimeException("Simulated AlarmManager Binder failure")),
            "c6RebootUnknown" to null
        )

        unknownInputs.forEachIndexed { idx, (label, priorOutcome) ->
            val alarmId = 370L + idx
            val scenario = seedActiveAAndPreparedB(alarmId = alarmId)
            issueRev2Intent(scenario = scenario, transitionToInFlight = true)

            val result = integration.replaySingleRev2CancelIntent(
                context = inspectionContextFor(
                    scenario = scenario,
                    cutpointHint = Rev2ReplayCrashCutpoint.C6_REBOOT_WITH_UNKNOWN_OS_OUTCOME,
                    priorFakeOsOutcome = priorOutcome
                )
            )

            assertEquals("$label outcome", Rev2ReplayRecoveryOutcome.IN_FLIGHT_UNKNOWN_OS_RESTART_HOLD, result.outcome)
            assertEquals("$label adjudicator", RecoveryDecisionOutcome.QUARANTINE, result.adjudicatorDecision?.outcome)
            assertEquals("$label adjudicatorReason", "OS_OUTCOME_UNKNOWN", result.adjudicatorDecision?.reasonCode)
            assertEquals(DurableOperationIntent.PHASE_IN_FLIGHT, result.intentPhaseAfter)
            assertEquals(DurableOperationIntent.PHASE_IN_FLIGHT, intentDao.getIntentByToken(scenario.tokenB)?.phase)
            assertAllEightEffectsBlockedAndNoSettlement(result)
        }
    }

    @Test
    fun `bToCRaceDuringReplay_bothPendingAndInFlight_isolatesAndProtectsSuccessorC`() = runBlocking {
        val phases = listOf(false, true) // PENDING then IN_FLIGHT

        phases.forEachIndexed { idx, inFlight ->
            val alarmId = 380L + idx
            val scenario = seedActiveAAndPreparedB(alarmId = alarmId)
            issueRev2Intent(scenario = scenario, transitionToInFlight = inFlight)

            val tokenC = "issue-C-$alarmId"
            database.withTransaction {
                issuedDao.reserveIssuedOccurrenceWithAuthority(
                    issueToken = tokenC,
                    alarmId = alarmId,
                    scheduledAt = scenario.scheduledAtB + 3_600_000L,
                    fireId = "fire-C-$alarmId",
                    authorityInstanceToken = scenario.ownerToken,
                    restoreEpoch = scenario.restoreEpoch,
                    scheduleRevision = scenario.scheduleRevision,
                    definitionRevision = scenario.definitionRevision,
                    alarmGeneration = scenario.alarmGeneration
                )
                issuedDao.activateIssuedOccurrence(tokenC)
            }

            val gateway = FakeReadOnlySlotGateway().apply {
                val (descB, linB) = mainV2FoundFor(scenario)
                putSlot(scenario.tokenB, descB, linB)
            }

            val result = integration.replaySingleRev2CancelIntent(
                context = inspectionContextFor(scenario),
                gateway = gateway
            )

            assertEquals(Rev2ReplayRecoveryOutcome.B_TO_C_RACE_ISOLATED, result.outcome)
            assertFalse(result.fakeOsLookupInvoked)
            assertEquals(0, gateway.lookupCount)
            assertEquals(0, gateway.cancelCount)
            assertAllEightEffectsBlockedAndNoSettlement(result)

            // Verify successor C remains ISSUED & active slot owner untouched
            assertEquals(tokenC, issuedDao.getSlot(alarmId)?.currentIssueToken)
            assertEquals(IssuedOccurrence.STATUS_ISSUED, issuedDao.getOccurrence(tokenC)?.status)
        }
    }

    @Test
    fun `duplicateReplay_acrossMultiplePasses_isDeterministicWithZeroExtraMutations`() = runBlocking {
        val scenarioPending = seedActiveAAndPreparedB(alarmId = 390L)
        issueRev2Intent(scenario = scenarioPending, transitionToInFlight = false)

        val scenarioInFlight = seedActiveAAndPreparedB(alarmId = 391L)
        issueRev2Intent(scenario = scenarioInFlight, transitionToInFlight = true)

        val gateway = FakeReadOnlySlotGateway().apply {
            val (descP, linP) = mainV2FoundFor(scenarioPending)
            val (descF, linF) = mainV2FoundFor(scenarioInFlight)
            putSlot(scenarioPending.tokenB, descP, linP)
            putSlot(scenarioInFlight.tokenB, descF, linF)
        }

        val reports = (1..3).map { pass ->
            integration.replayUnresolvedCancelIntents(
                evaluationTimeMillis = 1_700_000_300_000L + pass,
                gateway = gateway
            )
        }

        reports.forEach { report ->
            assertEquals(2, report.totalUnresolvedScanned)
            assertEquals(2, report.rev2ClassifiedCount)
            assertEquals(0, report.completedSettlementCount)
            assertEquals(0, report.abortedTransitionCount)
            assertEquals(0, report.realOsCalls)
            val byToken = report.results.associateBy { it.targetIssueToken }
            assertEquals(
                Rev2ReplayRecoveryOutcome.PENDING_SAFE_B_CANDIDATE_HOLD,
                byToken[scenarioPending.tokenB]?.outcome
            )
            assertEquals(
                Rev2ReplayRecoveryOutcome.IN_FLIGHT_CRASH_EVIDENCE_MISSING_HOLD,
                byToken[scenarioInFlight.tokenB]?.outcome
            )
        }
        assertEquals(0, gateway.cancelCount)
        assertEquals(6, gateway.lookupCount) // 2 read-only lookups per pass x 3 passes
        assertEquals(DurableOperationIntent.PHASE_PENDING, intentDao.getIntentByToken(scenarioPending.tokenB)?.phase)
        assertEquals(DurableOperationIntent.PHASE_IN_FLIGHT, intentDao.getIntentByToken(scenarioInFlight.tokenB)?.phase)
    }

    @Test
    fun `revisionRouting_isolatesRev1Rev2AndUnknownFailClosed_withoutGenericCompletedOrAbortedOnRev2`() = runBlocking {
        val now = 1_700_000_400_000L
        val scenarioRev2 = seedActiveAAndPreparedB(alarmId = 400L)
        issueRev2Intent(scenario = scenarioRev2, now = now, transitionToInFlight = false)

        val rev1AlarmId = 401L
        val rev1Token = "rev1-cancel-$rev1AlarmId"
        database.alarmDao().insert(Alarm(id = rev1AlarmId, hour = 8, minute = 0, isEnabled = false))
        intentDao.insertValidatedIntent(
            DurableOperationIntent(
                operationToken = rev1Token,
                alarmId = rev1AlarmId,
                operationEpoch = now,
                incarnationId = "fire-rev1-$rev1AlarmId",
                operationKind = "CANCEL",
                expectedOwnerToken = null,
                expectedScheduleRevision = null,
                expectedRestoreEpoch = null,
                phase = DurableOperationIntent.PHASE_PENDING,
                intentRevision = 1L,
                originalFireId = "fire-rev1-$rev1AlarmId",
                originalScheduledAt = 1_700_000_000_000L
            )
        )

        val unknownRevisions = listOf(3L, 4L, 99L)
        unknownRevisions.forEachIndexed { i, rev ->
            val aid = 402L + i
            database.alarmDao().insert(Alarm(id = aid, hour = 9, minute = i, isEnabled = false))
            intentDao.insertValidatedIntent(
                DurableOperationIntent(
                    operationToken = "unknown-rev$rev-$aid",
                    alarmId = aid,
                    operationEpoch = now,
                    incarnationId = "fire-unk-$aid",
                    operationKind = "CANCEL",
                    expectedOwnerToken = "owner-$aid",
                    expectedScheduleRevision = 1L,
                    expectedRestoreEpoch = 1L,
                    phase = DurableOperationIntent.PHASE_PENDING,
                    intentRevision = rev,
                    originalFireId = "fire-unk-$aid",
                    originalScheduledAt = 1_700_000_000_000L
                )
            )
        }

        val batchReport = integration.replayUnresolvedCancelIntents(evaluationTimeMillis = now + 1000L)
        assertEquals(5, batchReport.totalUnresolvedScanned)
        assertEquals(1, batchReport.legacyRev1DelegatedCount)
        assertEquals(1, batchReport.rev2ClassifiedCount)
        assertEquals(3, batchReport.unknownFailClosedCount)
        assertEquals(0, batchReport.completedSettlementCount)
        assertEquals(0, batchReport.abortedTransitionCount)
        assertEquals(0, batchReport.realOsCalls)

        val byToken = batchReport.results.associateBy { it.targetIssueToken }
        assertEquals(Rev2ReplayRecoveryOutcome.LEGACY_REV1_PRESERVED, byToken[rev1Token]?.outcome)
        assertEquals(Rev2ReplayRecoveryOutcome.PENDING_SAFE_B_CANDIDATE_HOLD, byToken[scenarioRev2.tokenB]?.outcome)
        unknownRevisions.forEachIndexed { i, rev ->
            val aid = 402L + i
            assertEquals(
                Rev2ReplayRecoveryOutcome.UNKNOWN_REVISION_FAIL_CLOSED,
                byToken["unknown-rev$rev-$aid"]?.outcome
            )
        }

        // Legacy recovery & scheduler isolate Rev2/unknown while settling Rev1 normally
        val recoveryCoordinator = DurableReconcileRecoveryCoordinator(database)
        assertEquals(
            DurableRecoveryOutcome.NEEDS_EVIDENCE_DEFERRED,
            recoveryCoordinator.recoverUnresolvedIntent(scenarioRev2.tokenB, now + 2000L).outcome
        )
        val dispatchCoordinator = DurableReconcileDispatchAdmissionCoordinator(database)
        assertEquals(
            DurableDispatchOutcome.ADMISSION_REJECTED_QUARANTINED,
            dispatchCoordinator.evaluateDispatchAdmission("unknown-rev3-402", now + 2000L).outcome
        )
        assertEquals(
            DurableDispatchOutcome.ADMISSION_REJECTED_QUARANTINED,
            dispatchCoordinator.evaluateDispatchAdmission(scenarioRev2.tokenB, now + 2000L).outcome
        )

        val schedulerForPi = newScheduler()
        schedulerForPi.createTokenizedPendingIntent(rev1AlarmId, 1_700_000_000_000L, "fire-rev1-$rev1AlarmId", rev1Token)
        schedulerForPi.createTokenizedPendingIntent(scenarioRev2.alarmId, scenarioRev2.scheduledAtB, scenarioRev2.fireIdB, scenarioRev2.tokenB)

        val spyAm = mockk<AlarmManager>(relaxed = true)
        newScheduler(spyAlarmManager = spyAm).rescheduleAllInBatches()

        verify(exactly = 1) { spyAm.cancel(any<PendingIntent>()) } // Only Rev1
        assertEquals(DurableOperationIntent.PHASE_COMPLETED, intentDao.getIntentByToken(rev1Token)?.phase)
        assertEquals(DurableOperationIntent.PHASE_PENDING, intentDao.getIntentByToken(scenarioRev2.tokenB)?.phase)
        assertEquals(DurableOperationIntent.PHASE_PENDING, intentDao.getIntentByToken("unknown-rev3-402")?.phase)
    }

    @Test
    fun `bACInvariants_andFakeSlots_remainIntactForAAndCSuccessorDuringRev2Replay`() = runBlocking {
        val scenario = seedActiveAAndPreparedB(alarmId = 410L)
        issueRev2Intent(scenario = scenario, transitionToInFlight = true)

        val gateway = FakeReadOnlySlotGateway().apply {
            val (descA, linA) = InactiveBSpecificCancelIntegration.expectedMainV2Descriptor(scenario.alarmId, scenario.tokenA) to
                OccurrenceLineageDescriptor(
                    alarmId = scenario.alarmId,
                    alarmKind = Alarm.KIND_NORMAL,
                    scheduledTriggerMillis = scenario.scheduledAtA,
                    generation = scenario.alarmGeneration,
                    revision = scenario.alarmRevision,
                    issueToken = scenario.tokenA,
                    instanceToken = scenario.ownerToken,
                    restoreEpoch = scenario.restoreEpoch,
                    scheduleRevision = scenario.scheduleRevision,
                    definitionRevision = scenario.definitionRevision
                )
            val (descB, linB) = mainV2FoundFor(scenario)
            putSlot(scenario.tokenA, descA, linA)
            putSlot(scenario.tokenB, descB, linB)
        }

        val replayBeforeC = integration.replaySingleRev2CancelIntent(
            context = inspectionContextFor(scenario),
            gateway = gateway
        )
        assertEquals(Rev2ReplayRecoveryOutcome.IN_FLIGHT_CRASH_EVIDENCE_MISSING_HOLD, replayBeforeC.outcome)
        assertEquals(IssuedOccurrence.STATUS_ISSUED, issuedDao.getOccurrence(scenario.tokenA)?.status)
        assertEquals(IssuedOccurrence.STATUS_PREPARED, issuedDao.getOccurrence(scenario.tokenB)?.status)
        assertEquals(scenario.tokenA, issuedDao.getSlot(scenario.alarmId)?.currentIssueToken)
        assertTrue(gateway.slots.containsKey(scenario.tokenA))
        assertTrue(gateway.slots.containsKey(scenario.tokenB))
        assertEquals(0, gateway.cancelCount)

        // Now successor C arrives in DB and Fake OS; replay MUST isolate B->C and keep both A and C safe
        val tokenC = "issue-C-410"
        database.withTransaction {
            issuedDao.reserveIssuedOccurrenceWithAuthority(
                issueToken = tokenC,
                alarmId = scenario.alarmId,
                scheduledAt = scenario.scheduledAtB + 3_600_000L,
                fireId = "fire-C-410",
                authorityInstanceToken = scenario.ownerToken,
                restoreEpoch = scenario.restoreEpoch,
                scheduleRevision = scenario.scheduleRevision,
                definitionRevision = scenario.definitionRevision,
                alarmGeneration = scenario.alarmGeneration
            )
        }
        val descC = InactiveBSpecificCancelIntegration.expectedMainV2Descriptor(scenario.alarmId, tokenC)
        val linC = OccurrenceLineageDescriptor(
            alarmId = scenario.alarmId,
            alarmKind = Alarm.KIND_NORMAL,
            scheduledTriggerMillis = scenario.scheduledAtB + 3_600_000L,
            generation = scenario.alarmGeneration,
            revision = scenario.alarmRevision,
            issueToken = tokenC,
            instanceToken = scenario.ownerToken,
            restoreEpoch = scenario.restoreEpoch,
            scheduleRevision = scenario.scheduleRevision,
            definitionRevision = scenario.definitionRevision
        )
        gateway.putSlot(tokenC, descC, linC)

        val replayAfterC = integration.replaySingleRev2CancelIntent(
            context = inspectionContextFor(scenario),
            gateway = gateway
        )
        assertEquals(Rev2ReplayRecoveryOutcome.B_TO_C_RACE_ISOLATED, replayAfterC.outcome)
        assertTrue(gateway.slots.containsKey(scenario.tokenA))
        assertTrue(gateway.slots.containsKey(scenario.tokenB))
        assertTrue(gateway.slots.containsKey(tokenC))
        assertEquals(IssuedOccurrence.STATUS_ISSUED, issuedDao.getOccurrence(scenario.tokenA)?.status)
        assertEquals(IssuedOccurrence.STATUS_PREPARED, issuedDao.getOccurrence(tokenC)?.status)
        assertAllEightEffectsBlockedAndNoSettlement(replayAfterC)
    }

    @Test
    fun `b3_schema38BootCountClassification_holdsOnMissingUnavailableMismatchInconsistentUnknownAbaAliasAndMatchedLookupNull`() = runBlocking {
        val scenario = seedActiveAAndPreparedB(alarmId = 420L)
        val persistedIntent = issueRev2Intent(scenario = scenario, transitionToInFlight = true)
        val alarm = database.alarmDao().getById(scenario.alarmId)!!
        val sa = database.scheduleAuthorityDao().getAuthorityByAlarmId(scenario.alarmId)!!
        val occB = issuedDao.getOccurrence(scenario.tokenB)!!
        val slot = issuedDao.getSlot(scenario.alarmId)!!

        fun baseInput(
            sidecar: OptionalBootCountSidecarEvidence?,
            currentBoot: CurrentBootCountEvidence = CurrentBootCountEvidence(isAvailable = true, currentBootCount = 5L),
            lookupStatus: PendingIntentLookupStatus = PendingIntentLookupStatus.NOT_ATTEMPTED,
            osOutcome: FakeOsCancelOutcome? = null,
            piDescriptor: PendingIntentIdentityDescriptor? = null,
            priorRestoreEpoch: Long? = null
        ) = Schema38BootCountClassificationInput(
            persistedIntent = persistedIntent,
            sidecarEvidence = sidecar,
            currentBootCount = currentBoot,
            currentAlarm = alarm,
            currentAuthority = sa,
            targetOccurrence = occB,
            currentSlot = slot,
            expectedAlarmGeneration = scenario.alarmGeneration,
            expectedAlarmRevision = scenario.alarmRevision,
            expectedOwnerToken = scenario.ownerToken,
            expectedRestoreEpoch = scenario.restoreEpoch,
            expectedScheduleRevision = scenario.scheduleRevision,
            expectedDefinitionRevision = scenario.definitionRevision,
            observedOsLookupStatus = lookupStatus,
            observedOsCancelOutcome = osOutcome,
            observedPiDescriptor = piDescriptor,
            priorExpectedRestoreEpoch = priorRestoreEpoch
        )

        fun assertStrictHold(
            result: Schema38BootCountClassificationResult,
            expectedReason: Schema38BootCountClassificationReason
        ) {
            assertEquals(BootCountDisposition.HOLD, result.disposition)
            assertEquals(expectedReason, result.reason)
            assertFalse(result.provesOsCancelSuccess)
            assertEquals(0, result.completedSettlementCount)
            assertEquals(0, result.abortedSettlementFromOsCount)
            assertEquals(RealOsEligibility.BLOCKED, result.realOsEligibility)
            assertTrue(result.flagNoCreateAloneInsufficient)
        }

        // 1. MISSING_SIDECAR -> HOLD
        assertStrictHold(
            InactiveBSpecificCancelIntegration.classifySchema38BootCountRecovery(
                baseInput(sidecar = null)
            ),
            Schema38BootCountClassificationReason.MISSING_SIDECAR
        )
        assertStrictHold(
            InactiveBSpecificCancelIntegration.classifySchema38BootCountRecovery(
                baseInput(sidecar = OptionalBootCountSidecarEvidence(PersistedSidecarBootCountStatus.MISSING_SIDECAR))
            ),
            Schema38BootCountClassificationReason.MISSING_SIDECAR
        )

        // 2. BOOT_COUNT_UNAVAILABLE -> HOLD
        val validSidecar5 = OptionalBootCountSidecarEvidence(
            status = PersistedSidecarBootCountStatus.PRESENT,
            recordedOperationToken = scenario.tokenB,
            recordedAlarmId = scenario.alarmId,
            recordedBootCount = 5L,
            recordedRestoreEpoch = scenario.restoreEpoch,
            recordedOwnerToken = scenario.ownerToken
        )
        assertStrictHold(
            InactiveBSpecificCancelIntegration.classifySchema38BootCountRecovery(
                baseInput(
                    sidecar = validSidecar5,
                    currentBoot = CurrentBootCountEvidence(isAvailable = false, currentBootCount = null)
                )
            ),
            Schema38BootCountClassificationReason.BOOT_COUNT_UNAVAILABLE
        )
        assertStrictHold(
            InactiveBSpecificCancelIntegration.classifySchema38BootCountRecovery(
                baseInput(
                    sidecar = validSidecar5,
                    currentBoot = CurrentBootCountEvidence(isAvailable = true, currentBootCount = 0L)
                )
            ),
            Schema38BootCountClassificationReason.BOOT_COUNT_UNAVAILABLE
        )

        // 3. BOOT_COUNT_MISMATCH (cold reboot 5 -> 6) -> HOLD
        assertStrictHold(
            InactiveBSpecificCancelIntegration.classifySchema38BootCountRecovery(
                baseInput(
                    sidecar = validSidecar5,
                    currentBoot = CurrentBootCountEvidence(isAvailable = true, currentBootCount = 6L),
                    lookupStatus = PendingIntentLookupStatus.LOOKUP_NULL
                )
            ),
            Schema38BootCountClassificationReason.BOOT_COUNT_MISMATCH
        )

        // 4. DB/sidecar inconsistency -> HOLD
        assertStrictHold(
            InactiveBSpecificCancelIntegration.classifySchema38BootCountRecovery(
                baseInput(
                    sidecar = validSidecar5.copy(recordedOperationToken = "different-token")
                )
            ),
            Schema38BootCountClassificationReason.DB_SIDECAR_INCONSISTENCY
        )
        assertStrictHold(
            InactiveBSpecificCancelIntegration.classifySchema38BootCountRecovery(
                baseInput(
                    sidecar = OptionalBootCountSidecarEvidence(PersistedSidecarBootCountStatus.CORRUPTED)
                )
            ),
            Schema38BootCountClassificationReason.DB_SIDECAR_INCONSISTENCY
        )

        // 5. OS result UNKNOWN -> HOLD
        assertStrictHold(
            InactiveBSpecificCancelIntegration.classifySchema38BootCountRecovery(
                baseInput(
                    sidecar = validSidecar5,
                    osOutcome = FakeOsCancelOutcome.Unknown("BINDER_INDETERMINATE")
                )
            ),
            Schema38BootCountClassificationReason.OS_RESULT_UNKNOWN
        )

        // 6. Cross-restore ABA suspicion -> HOLD
        assertStrictHold(
            InactiveBSpecificCancelIntegration.classifySchema38BootCountRecovery(
                baseInput(
                    sidecar = validSidecar5,
                    priorRestoreEpoch = scenario.restoreEpoch + 1L
                )
            ),
            Schema38BootCountClassificationReason.CROSS_RESTORE_ABA_SUSPECTED
        )

        // 7. Legacy PI alias suspicion -> HOLD
        val legacyAliasPi = PendingIntentIdentityDescriptor(
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
        assertStrictHold(
            InactiveBSpecificCancelIntegration.classifySchema38BootCountRecovery(
                baseInput(
                    sidecar = validSidecar5,
                    piDescriptor = legacyAliasPi
                )
            ),
            Schema38BootCountClassificationReason.LEGACY_PI_ALIAS_SUSPECTED
        )

        // 8. Even matched bootCount (5 == 5) + FLAG_NO_CREATE == null MUST stay HOLD (never COMPLETED!)
        assertStrictHold(
            InactiveBSpecificCancelIntegration.classifySchema38BootCountRecovery(
                baseInput(
                    sidecar = validSidecar5,
                    currentBoot = CurrentBootCountEvidence(isAvailable = true, currentBootCount = 5L),
                    lookupStatus = PendingIntentLookupStatus.LOOKUP_NULL,
                    piDescriptor = InactiveBSpecificCancelIntegration.expectedMainV2Descriptor(scenario.alarmId, scenario.tokenB)
                )
            ),
            Schema38BootCountClassificationReason.MATCHED_WARM_REBOOT_NOT_OS_COMPLETION_PROOF
        )
        assertEquals(DurableOperationIntent.PHASE_IN_FLIGHT, intentDao.getIntentByToken(scenario.tokenB)!!.phase)
    }
}
