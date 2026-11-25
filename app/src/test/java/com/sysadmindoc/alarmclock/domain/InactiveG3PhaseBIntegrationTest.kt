package com.sysadmindoc.alarmclock.domain

import android.app.AlarmManager
import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import androidx.work.Operation
import androidx.work.WorkManager
import com.sysadmindoc.alarmclock.data.local.AlarmDatabase
import com.sysadmindoc.alarmclock.data.local.DurableOperationIntentDao
import com.sysadmindoc.alarmclock.data.local.IssuedOccurrenceDao
import com.sysadmindoc.alarmclock.data.local.entity.DirectBootOccurrenceReceipt
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
import com.sysadmindoc.alarmclock.directboot.BootProvenance
import com.sysadmindoc.alarmclock.directboot.CeHandoffResult
import com.sysadmindoc.alarmclock.directboot.CeTakeoverStorageState
import com.sysadmindoc.alarmclock.directboot.DeliveryAppendResult
import com.sysadmindoc.alarmclock.directboot.DirectBootAlarmCache
import com.sysadmindoc.alarmclock.directboot.DirectBootAlarmReceiver
import com.sysadmindoc.alarmclock.directboot.DirectBootAlarmService
import com.sysadmindoc.alarmclock.directboot.DirectBootCeHandoffCoordinator
import com.sysadmindoc.alarmclock.directboot.DirectBootCeTakeoverStore
import com.sysadmindoc.alarmclock.directboot.DirectBootDeliveryAuthorizer
import com.sysadmindoc.alarmclock.directboot.DirectBootDeliveryLedger
import com.sysadmindoc.alarmclock.directboot.DirectBootDeliveryRecord
import com.sysadmindoc.alarmclock.directboot.DirectBootDurableOwner
import com.sysadmindoc.alarmclock.directboot.DirectBootDurableOwnerStore
import com.sysadmindoc.alarmclock.directboot.DirectBootEventConsumerEngine
import com.sysadmindoc.alarmclock.directboot.DirectBootExactAlarmManagerBoundary
import com.sysadmindoc.alarmclock.directboot.DirectBootFiredWitnessLedger
import com.sysadmindoc.alarmclock.directboot.DirectBootFiredWitnessRecord
import com.sysadmindoc.alarmclock.directboot.DirectBootLegacyClaimStore
import com.sysadmindoc.alarmclock.directboot.DirectBootMissedRecord
import com.sysadmindoc.alarmclock.directboot.DirectBootMissedStore
import com.sysadmindoc.alarmclock.directboot.DirectBootQueueItem
import com.sysadmindoc.alarmclock.directboot.DirectBootRegistrationMarker
import com.sysadmindoc.alarmclock.directboot.DirectBootRegistrationPlan
import com.sysadmindoc.alarmclock.directboot.DirectBootRegistrationReconciler
import com.sysadmindoc.alarmclock.directboot.DirectBootServiceDeliveryAdapter
import com.sysadmindoc.alarmclock.directboot.DirectBootServiceTestFixture
import com.sysadmindoc.alarmclock.directboot.DirectBootUnlockReceiver
import com.sysadmindoc.alarmclock.directboot.DirectBootV2NormalIntentIdentity
import com.sysadmindoc.alarmclock.directboot.ExactCancellationResult
import com.sysadmindoc.alarmclock.directboot.ExactRegistrationResult
import com.sysadmindoc.alarmclock.directboot.HandoverClaim
import com.sysadmindoc.alarmclock.directboot.LedgerStorageState
import com.sysadmindoc.alarmclock.directboot.LegacyClaimSource
import com.sysadmindoc.alarmclock.directboot.OutputOccurrenceKey
import com.sysadmindoc.alarmclock.directboot.OwnerState
import com.sysadmindoc.alarmclock.directboot.ReconcilerResult
import com.sysadmindoc.alarmclock.directboot.ServiceDeliveryAdaptationResult
import com.sysadmindoc.alarmclock.directboot.TicketIssueResult
import com.sysadmindoc.alarmclock.directboot.WitnessAppendResult
import com.sysadmindoc.alarmclock.directboot.outputauthority.ActiveOutputHandle
import com.sysadmindoc.alarmclock.directboot.outputauthority.OutputAuthorizationResult
import com.sysadmindoc.alarmclock.directboot.outputauthority.OutputSinkType
import com.sysadmindoc.alarmclock.directboot.outputauthority.SharedOutputAuthorityGate
import com.sysadmindoc.alarmclock.receiver.AlarmReceiver
import com.sysadmindoc.alarmclock.receiver.BootReceiver
import com.sysadmindoc.alarmclock.receiver.outputAuthorityGateSeam
import com.sysadmindoc.alarmclock.service.AlarmFullScreenFallback
import com.sysadmindoc.alarmclock.service.AlarmService
import com.sysadmindoc.alarmclock.service.BedtimeZenRuleManager
import com.sysadmindoc.alarmclock.widget.WidgetUpdater
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.spyk
import io.mockk.unmockkAll
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
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
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * KNO-75 G3 Inactive Phase-B Integration Test Suite (Fake-Only).
 *
 * Verifies cross-boundary invariants between dormant G1/G2 Rev2 cancellation & replay
 * and Phase B production boundaries (Boot, Receiver, Service, Fallback, Unlock DE->CE)
 * without activating V2 output, real OS cancellation, or Feature flags.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class InactiveG3PhaseBIntegrationTest {

    private lateinit var ceContext: Context
    private lateinit var deContext: Context
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

    private val baseTime = 1_700_000_000_000L

    private data class SeededHierarchy(
        val alarmId: Long,
        val tokenA: String,
        val tokenB: String,
        val tokenC: String,
        val schedC: Long,
        val fireIdC: String,
        val fireIdB: String,
        val ownerToken: String,
        val slotGenIdB: Long,
        val rev1Token: String,
        val unkToken: String
    )

    @Before
    fun setUp() {
        ceContext = ApplicationProvider.getApplicationContext()
        deContext = ceContext.createDeviceProtectedStorageContext()
        shadowOf(ceContext as Application).grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
        org.robolectric.shadows.ShadowAlarmManager.setCanScheduleExactAlarms(true)

        database = Room.inMemoryDatabaseBuilder(ceContext, AlarmDatabase::class.java)
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

        clearAllStores()
        DirectBootServiceTestFixture.configureAudioAndVibrationEnvironment(ceContext)
        DirectBootCeTakeoverStore.unlockGateSeam = { true }
    }

    @After
    fun tearDown() {
        clearAllStores()
        DirectBootCeTakeoverStore.resetSeamsForTesting()
        DirectBootAlarmReceiver.resetSeamsForTesting()
        DirectBootFiredWitnessLedger.resetSeamsForTesting()
        DirectBootDeliveryLedger.resetSeamsForTesting()
        DirectBootMissedStore.resetSeamsForTesting()
        DirectBootUnlockReceiver.onConsumerFinishedSeam = {}
        outputAuthorityGateSeam = null
        AlarmFullScreenFallback.clearForTesting()
        unmockkAll()
        database.close()
    }

    private fun clearAllStores() {
        DirectBootServiceTestFixture.clearAllDirectBootPrefs(deContext)
        DirectBootAlarmCache.clear(deContext)
        DirectBootAlarmCache.writeQueue(deContext, emptyList(), now = baseTime)
        DirectBootLegacyClaimStore.prefs(deContext).edit().clear().commit()
        deContext.getSharedPreferences(DirectBootMissedStore.PREFS_NAME, Context.MODE_PRIVATE).edit().clear().commit()
        deContext.getSharedPreferences(DirectBootRegistrationMarker.PREFS_NAME, Context.MODE_PRIVATE).edit().clear().commit()
        DirectBootCeTakeoverStore.clear(ceContext)
        deContext.getSharedPreferences(DirectBootDeliveryLedger.PREFS_NAME, Context.MODE_PRIVATE)
            .edit().clear().commit()
    }

    private fun newScheduler(alarmManager: AlarmManager? = null): AlarmScheduler =
        AlarmScheduler(
            context = ceContext,
            repository = alarmRepository,
            calculator = calculator,
            preferencesManager = preferencesManager,
            holidayRepository = holidayRepository,
            alarmIncidentRepository = alarmIncidentRepository,
            weatherRepository = weatherRepository
        ).apply {
            testAlarmManager = alarmManager
        }

    private class RecordingServiceContext(base: Context) : ContextWrapper(base) {
        val startedServiceIntents = mutableListOf<Intent>()

        override fun startForegroundService(service: Intent?): android.content.ComponentName {
            if (service != null) {
                startedServiceIntents.add(service)
            }
            return android.content.ComponentName(
                packageName,
                service?.component?.className ?: DirectBootAlarmService::class.java.name
            )
        }
    }

    private class FakeExactAlarmBoundary : DirectBootExactAlarmManagerBoundary {
        val registerCalls = mutableListOf<DirectBootV2NormalIntentIdentity>()
        val cancelCalls = mutableListOf<DirectBootV2NormalIntentIdentity>()

        override fun canScheduleExactAlarms(): Boolean = true

        override fun registerExactAlarm(identity: DirectBootV2NormalIntentIdentity): ExactRegistrationResult {
            registerCalls.add(identity)
            return ExactRegistrationResult.Accepted(identity)
        }

        override fun cancelExactAlarm(identity: DirectBootV2NormalIntentIdentity): ExactCancellationResult {
            cancelCalls.add(identity)
            return ExactCancellationResult.Accepted(identity)
        }
    }

    private suspend fun seedABCWithRev2AndLegacyAndUnknownIntents(
        alarmId: Long,
        activateSuccessorC: Boolean = false
    ): SeededHierarchy {
        val now = baseTime
        val tokenA = "tok-a-$alarmId"
        val tokenB = "tok-b-$alarmId"
        val tokenC = "tok-c-$alarmId"
        val fireIdA = "fire-a-$alarmId"
        val fireIdB = "fire-b-$alarmId"
        val fireIdC = "fire-c-$alarmId"
        val schedA = now + 60_000L
        val schedB = now + 120_000L
        val schedC = now + 180_000L
        val ownerToken = "owner-$alarmId"
        val rev1AlarmId = alarmId + 9000L
        val rev1Token = "rev1-cancel-$rev1AlarmId"
        val unkAlarmId = alarmId + 9002L
        val unkToken = "unk-cancel-$unkAlarmId"

        var slotGenIdB = 0L

        database.withTransaction {
            database.alarmDao().insert(
                Alarm(
                    id = alarmId,
                    hour = 7,
                    minute = 0,
                    isEnabled = true,
                    generation = 3,
                    revision = 5,
                    scheduleKind = Alarm.SCHEDULE_KIND_ONE_TIME,
                    repeatDays = emptySet(),
                    nextTriggerTime = if (activateSuccessorC) schedC else schedA
                )
            )
            database.scheduleAuthorityDao().insertAuthority(
                ScheduleAuthority(
                    alarmId = alarmId,
                    instanceToken = ownerToken,
                    definitionRevision = 4L,
                    scheduleRevision = 12L,
                    lifecycle = "LIVE",
                    desiredOperation = "SET",
                    desiredTriggerMillis = if (activateSuccessorC) schedC else schedA,
                    reconcileState = "SETTLED",
                    restoreEpoch = 2L,
                    actionKey = "act-$alarmId"
                )
            )
            // Current Active A, Prepared B (slot.generationId == occB.generationId)
            issuedDao.reserveIssuedOccurrenceWithAuthority(
                issueToken = tokenA,
                alarmId = alarmId,
                scheduledAt = schedA,
                fireId = fireIdA,
                authorityInstanceToken = ownerToken,
                restoreEpoch = 2L,
                scheduleRevision = 12L,
                definitionRevision = 4L,
                alarmGeneration = 3
            )
            issuedDao.activateIssuedOccurrence(tokenA)

            issuedDao.reserveIssuedOccurrenceWithAuthority(
                issueToken = tokenB,
                alarmId = alarmId,
                scheduledAt = schedB,
                fireId = fireIdB,
                authorityInstanceToken = ownerToken,
                restoreEpoch = 2L,
                scheduleRevision = 12L,
                definitionRevision = 4L,
                alarmGeneration = 3
            )
            slotGenIdB = issuedDao.getSlot(alarmId)!!.generationId

            // Rev2 B-specific pending cancel on alarmId targeting B via P3C DAO API
            intentDao.issueBSpecificCancelIntentTransaction(
                issuedDao = issuedDao,
                scheduleAuthorityDao = database.scheduleAuthorityDao(),
                alarmDao = database.alarmDao(),
                targetIssueToken = tokenB,
                now = now - 400L
            )

            if (activateSuccessorC) {
                issuedDao.reserveIssuedOccurrenceWithAuthority(
                    issueToken = tokenC,
                    alarmId = alarmId,
                    scheduledAt = schedC,
                    fireId = fireIdC,
                    authorityInstanceToken = ownerToken,
                    restoreEpoch = 2L,
                    scheduleRevision = 12L,
                    definitionRevision = 4L,
                    alarmGeneration = 3
                )
                issuedDao.activateIssuedOccurrence(tokenC)
            }

            // Rev1 legacy pending cancel on a separate alarm
            database.alarmDao().insert(
                Alarm(
                    id = rev1AlarmId,
                    hour = 8,
                    minute = 0,
                    isEnabled = false,
                    generation = 1,
                    revision = 1,
                    scheduleKind = Alarm.SCHEDULE_KIND_ONE_TIME,
                    repeatDays = emptySet(),
                    nextTriggerTime = now + 240_000L
                )
            )
            intentDao.insertValidatedIntent(
                DurableOperationIntent(
                    operationToken = rev1Token,
                    alarmId = rev1AlarmId,
                    operationEpoch = now - 500L,
                    incarnationId = "inc-rev1-$rev1AlarmId",
                    operationKind = "CANCEL",
                    expectedOwnerToken = null,
                    expectedScheduleRevision = null,
                    expectedRestoreEpoch = null,
                    phase = DurableOperationIntent.PHASE_PENDING,
                    intentRevision = 1L,
                    originalFireId = "fire-rev1-$rev1AlarmId",
                    originalScheduledAt = now + 240_000L
                )
            )

            // Unknown revision cancel intent on separate alarm
            database.alarmDao().insert(
                Alarm(
                    id = unkAlarmId,
                    hour = 9,
                    minute = 0,
                    isEnabled = false,
                    generation = 1,
                    revision = 1,
                    scheduleKind = Alarm.SCHEDULE_KIND_ONE_TIME,
                    repeatDays = emptySet(),
                    nextTriggerTime = now + 300_000L
                )
            )
            intentDao.insertValidatedIntent(
                DurableOperationIntent(
                    operationToken = unkToken,
                    alarmId = unkAlarmId,
                    operationEpoch = now - 300L,
                    incarnationId = "inc-unk-$unkAlarmId",
                    operationKind = "CANCEL",
                    expectedOwnerToken = "owner-unk-$unkAlarmId",
                    expectedScheduleRevision = 1L,
                    expectedRestoreEpoch = 1L,
                    phase = DurableOperationIntent.PHASE_PENDING,
                    intentRevision = 99L,
                    originalFireId = "fire-unk-$unkAlarmId",
                    originalScheduledAt = now + 300_000L
                )
            )
        }

        return SeededHierarchy(
            alarmId = alarmId,
            tokenA = tokenA,
            tokenB = tokenB,
            tokenC = tokenC,
            schedC = schedC,
            fireIdC = fireIdC,
            fireIdB = fireIdB,
            ownerToken = ownerToken,
            slotGenIdB = slotGenIdB,
            rev1Token = rev1Token,
            unkToken = unkToken
        )
    }

    // =========================================================================
    // 1. Step 3: Boot Path Isolation & LEGACY_ONLY / V2_OUTPUT = DEFAULT_DENY
    // =========================================================================

    @Test
    fun bootPath_lockedBootAndPostBootReschedule_keepLegacyOnly_noV2Activation_andRev2Isolated() = runBlocking {
        val alarmId = 101L
        val seeded = seedABCWithRev2AndLegacyAndUnknownIntents(alarmId, activateSuccessorC = true)

        // Seed Legacy DirectBoot snapshot and V2 queue
        val futureSnapTime = System.currentTimeMillis() + 300_000L
        DirectBootAlarmCache.saveIfEarlier(
            context = deContext,
            alarm = Alarm(id = alarmId, hour = 7, minute = 0, isEnabled = true, nextTriggerTime = futureSnapTime),
            triggerTime = futureSnapTime,
            now = System.currentTimeMillis()
        )
        val itemA = DirectBootQueueItem(
            kind = DirectBootQueueItem.KIND_NORMAL,
            alarmId = alarmId,
            triggerTime = futureSnapTime + 10_000L,
            generation = 3,
            instanceToken = "tok-queue-$alarmId",
            restoreEpoch = 2L
        )
        DirectBootAlarmCache.writeQueue(deContext, listOf(itemA), now = System.currentTimeMillis())

        // Execute BootReceiver LOCKED_BOOT_COMPLETED with deterministic Unconfined scope
        val bootReceiver = BootReceiver().apply {
            testScope = CoroutineScope(Dispatchers.Unconfined)
        }
        bootReceiver.onReceive(ceContext, Intent(Intent.ACTION_LOCKED_BOOT_COMPLETED))

        // Verify DirectBootAlarmCache remains V2_OUTPUT = DEFAULT_DENY
        val fallbackScheduleRes = DirectBootAlarmCache.scheduleCachedAlarmWithResult(deContext, now = System.currentTimeMillis())
        assertTrue(fallbackScheduleRes.isArmed)

        val deAlarmManager = deContext.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val ceAlarmManager = ceContext.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val allScheduledActions = (shadowOf(deAlarmManager).scheduledAlarms + shadowOf(ceAlarmManager).scheduledAlarms)
            .mapNotNull { it.operation?.let { op -> shadowOf(op).savedIntent?.action } }
        assertTrue(
            "LOCKED_BOOT_COMPLETED fallback must arm legacy V1 receiver action (observed=$allScheduledActions)",
            allScheduledActions.contains(DirectBootAlarmReceiver.ACTION_FIRE_DIRECT_BOOT_ALARM)
        )

        // Verify fake Reconciler prevents duplicate OS registration on repeated calls in same boot
        val fakeBoundary = FakeExactAlarmBoundary()
        val sameBoot = BootProvenance(bootCount = 7, lastElapsedRealtime = 10_000L)
        val reconciler = DirectBootRegistrationReconciler.createForTesting(
            context = deContext,
            osBoundary = fakeBoundary,
            clock = { System.currentTimeMillis() },
            bootProvenanceProvider = { sameBoot }
        )
        deContext.getSharedPreferences(DirectBootRegistrationMarker.PREFS_NAME, Context.MODE_PRIVATE)
            .edit().remove(DirectBootRegistrationMarker.KEY_V2_REGISTRATION_MARKER).commit()
        val rec1 = reconciler.reconcile()
        val rec2 = reconciler.reconcile()
        assertTrue("Expected StableSuccess on first reconcile, got $rec1", rec1 is ReconcilerResult.StableSuccess)
        assertTrue(
            "Expected PlanningHalted or AlreadyArmed RecoveryRequired on duplicate same-boot reconcile, got $rec2",
            rec2 is ReconcilerResult.PlanningHalted || (rec2 is ReconcilerResult.RecoveryRequired && rec2.plan is DirectBootRegistrationPlan.AlreadyArmed)
        )
        assertEquals("Duplicate OS registration must NOT occur in same boot", 1, fakeBoundary.registerCalls.size)
        assertEquals(0, fakeBoundary.cancelCalls.size)

        // Verify RouteAuthority stays LEGACY_ONLY and ScheduleAuthorityReconciler stays dormant
        val routeAuthority = ProductionSchedulingRouteAuthority()
        assertEquals(ProductionSchedulingRoute.LEGACY_ONLY, routeAuthority.currentRoute)
        assertFalse(routeAuthority.isOwnerRouteActive())
        val owner = mockk<ScheduleAuthorityOwner>(relaxed = true)
        val saReconciler = ScheduleAuthorityReconciler(database.scheduleAuthorityDao(), owner, routeAuthority)
        assertEquals(0, saReconciler.reconcileBootAuthorities())
        assertEquals(0, saReconciler.reconcilePendingAuthorities())

        // Post-boot AlarmScheduler.rescheduleAllInBatches settles ONLY Rev1, isolates Rev2 & Unknown
        newScheduler().rescheduleAllInBatches()

        assertEquals(DurableOperationIntent.PHASE_COMPLETED, intentDao.getIntentByToken(seeded.rev1Token)?.phase)
        assertEquals("Rev2 CANCEL must stay untouched in PHASE_PENDING after reschedule", DurableOperationIntent.PHASE_PENDING, intentDao.getIntentByToken(seeded.tokenB)?.phase)
        assertEquals("Unknown revision CANCEL must remain untouched fail-closed", DurableOperationIntent.PHASE_PENDING, intentDao.getIntentByToken(seeded.unkToken)?.phase)
        assertEquals(IssuedOccurrence.STATUS_ISSUED, issuedDao.getOccurrence(seeded.tokenC)?.status)
    }

    // =========================================================================
    // 2. Step 4: Receiver Admission -> Service V2 Default Deny & Duplicate/Stale Rejection
    // =========================================================================

    @Test
    fun receiverAndService_v2AdapterCommitDoesNotGrantServiceOutput_andPreservesLegacyAndBlocksDuplicates() {
        val alarmId = 201L
        val triggerTime = baseTime + 20_000L
        val itemA = DirectBootQueueItem(
            kind = DirectBootQueueItem.KIND_NORMAL,
            alarmId = alarmId,
            triggerTime = triggerTime,
            generation = 1,
            instanceToken = "tok-rec-201",
            restoreEpoch = 1L
        )
        DirectBootAlarmCache.writeQueue(deContext, listOf(itemA), now = baseTime)

        val authorizer = DirectBootDeliveryAuthorizer()
        val identityA = DirectBootV2NormalIntentIdentity.fromQueueItem(itemA)
        val ticket1 = (authorizer.issueTicket(identityA, nowEpochMs = baseTime + 10_000L) as TicketIssueResult.Success).ticket

        var observedAdaptResult: ServiceDeliveryAdaptationResult? = null
        DirectBootAlarmReceiver.serviceAdapterSeam = { ctx ->
            object : DirectBootServiceDeliveryAdapter(ctx, authorizer, clock = { baseTime + 20_000L }) {
                override fun adaptDelivery(
                    ticketId: String,
                    identity: DirectBootV2NormalIntentIdentity,
                    deliveredAt: Long
                ): ServiceDeliveryAdaptationResult {
                    val res = super.adaptDelivery(ticketId, identity, deliveredAt)
                    observedAdaptResult = res
                    return res
                }
            }
        }
        val fakeBoundary = FakeExactAlarmBoundary()
        val bootProv = BootProvenance(bootCount = 1, lastElapsedRealtime = 5_000L)
        DirectBootAlarmReceiver.reconcilerSeam = { ctx ->
            DirectBootRegistrationReconciler.createForTesting(
                context = ctx,
                osBoundary = fakeBoundary,
                clock = { baseTime + 20_010L },
                bootProvenanceProvider = { bootProv }
            )
        }

        // Unauthenticated direct V2 Service ACTION_START (no HANDOVER_PENDING / DeliveryLedger) MUST remain DEFAULT_DENY
        val unauthServiceIntent = identityA.createIntent(ceContext).apply {
            setClass(ceContext, DirectBootAlarmService::class.java)
            action = DirectBootAlarmService.ACTION_START
        }
        val unauthController = Robolectric.buildService(DirectBootAlarmService::class.java, unauthServiceIntent)
        val unauthService = unauthController.create().startCommand(0, 0).get()
        try {
            val unauthEffects = DirectBootServiceTestFixture.captureEffects(unauthService)
            assertFalse("Unauthenticated direct V2 Service start MUST deny V2 audio", unauthEffects.mediaPlayerPlaying)
            assertNull("Unauthenticated direct V2 Service start MUST NOT allocate MediaPlayer", unauthEffects.mediaPlayer)
            assertFalse("Unauthenticated direct V2 Service start MUST deny V2 vibration", unauthEffects.vibrating)
            assertNull("Unauthenticated direct V2 Service start MUST NOT post foreground notification", unauthEffects.foregroundNotification)
            assertTrue("Idle Service rejecting unauthenticated V2 MUST stopSelf()", unauthEffects.stoppedBySelf)
        } finally {
            unauthController.destroy()
        }

        val v2Intent = identityA.createIntent(ceContext).apply {
            putExtra(DirectBootDeliveryAuthorizer.EXTRA_TICKET_ID, ticket1.ticketId)
        }

        // Default Flag OFF (gate.isEnabled = false, v2DeliveryCapableProvider = false) MUST drop V2 fail-closed
        val flagOffCtx = RecordingServiceContext(ceContext)
        DirectBootAlarmReceiver().onReceive(flagOffCtx, v2Intent)
        assertEquals("Flag OFF default MUST drop V2 delivery", 0, flagOffCtx.startedServiceIntents.size)
        assertNull("Flag OFF default MUST NOT invoke V2 service adapter", observedAdaptResult)

        // Explicit test V2 capability seams enabled for Receiver & Service handover contract proof
        val testGate = SharedOutputAuthorityGate(
            processSessionToken = "proc-g3-v2",
            bootCount = 1L
        ).apply { isEnabled = true }
        DirectBootAlarmReceiver.outputAuthorityGateSeam = testGate
        DirectBootAlarmReceiver.v2DeliveryCapableProvider = { true }

        val recordingCtx = RecordingServiceContext(ceContext)
        DirectBootAlarmReceiver().onReceive(recordingCtx, v2Intent)

        // Adapter succeeded at DE transition, but audioAuthorized from Adapter itself MUST remain false
        assertTrue(observedAdaptResult is ServiceDeliveryAdaptationResult.TransitionCommitted)
        assertFalse("Adapter success must NEVER grant audioAuthorized", observedAdaptResult!!.audioAuthorized)
        assertEquals(1, recordingCtx.startedServiceIntents.size)

        // Forward that EXACT Service intent to real DirectBootAlarmService: P3 verifies HANDOVER_PENDING -> V2_ACTIVE + Witness!
        val forwardedServiceIntent = recordingCtx.startedServiceIntents.single()
        val svcController = Robolectric.buildService(DirectBootAlarmService::class.java)
        val service = svcController.create().get().apply {
            outputAuthorityGate = testGate
            v2DeliveryCapableProvider = { true }
        }
        service.onStartCommand(forwardedServiceIntent, 0, 1)
        try {
            val effects = DirectBootServiceTestFixture.captureEffects(service)
            assertEquals(alarmId, effects.alarmId)
            assertNotNull("Authorized P3 V2 service MUST post foreground notification", effects.foregroundNotification)
            assertTrue("Authorized P3 V2 service MUST play V2 audio", effects.mediaPlayerPlaying)
            assertFalse("Authorized P3 V2 service MUST stay running", effects.stoppedBySelf)
            assertTrue(
                "Authorized P3 V2 service MUST record Witness",
                DirectBootFiredWitnessLedger.hasWitness(deContext, alarmId, triggerTime, 1, "tok-rec-201")
            )

            // Stop active V2 service cleanly to release V2_ACTIVE -> IDLE
            val stopIntent = identityA.createIntent(ceContext).apply {
                setClass(ceContext, DirectBootAlarmService::class.java)
                action = DirectBootAlarmService.ACTION_STOP
            }
            service.onStartCommand(stopIntent, 0, 2)
        } finally {
            svcController.destroy()
        }

        // Duplicate delivery with a second ticket MUST be rejected without starting Service
        val ticket2 = (authorizer.issueTicket(identityA, nowEpochMs = baseTime + 20_500L) as TicketIssueResult.Success).ticket
        val dupCtx = RecordingServiceContext(ceContext)
        DirectBootAlarmReceiver().onReceive(
            dupCtx,
            identityA.createIntent(ceContext).apply {
                putExtra(DirectBootDeliveryAuthorizer.EXTRA_TICKET_ID, ticket2.ticketId)
            }
        )
        assertEquals("Duplicate V2 delivery must not start service", 0, dupCtx.startedServiceIntents.size)

        // Stale / conflicting DurableOwner blocks new V2 adapter delivery
        val itemB = itemA.copy(alarmId = 202L, triggerTime = baseTime + 30_000L, instanceToken = "tok-rec-202")
        DirectBootAlarmCache.writeQueue(deContext, listOf(itemB), now = baseTime)
        val currentOwnerSeq = (DirectBootDurableOwnerStore.read(deContext) as? com.sysadmindoc.alarmclock.directboot.OwnerReadResult.Success)?.owner?.ownerSeq ?: 0L
        DirectBootDurableOwnerStore.write(
            context = deContext,
            owner = DirectBootDurableOwner(
                state = OwnerState.HANDOVER_PENDING,
                ownerSeq = currentOwnerSeq,
                bootProvenance = bootProv,
                handoverClaim = HandoverClaim("h-owner", 202L, baseTime + 30_000L, 1, "tok-other", 1L),
                updatedAtEpochMs = baseTime
            ),
            expectedOwnerSeq = currentOwnerSeq
        )
        val idB = DirectBootV2NormalIntentIdentity.fromQueueItem(itemB)
        val ticketB = (authorizer.issueTicket(idB, nowEpochMs = baseTime + 21_000L) as TicketIssueResult.Success).ticket
        val ownerConflictCtx = RecordingServiceContext(ceContext)
        DirectBootAlarmReceiver().onReceive(
            ownerConflictCtx,
            idB.createIntent(ceContext).apply {
                putExtra(DirectBootDeliveryAuthorizer.EXTRA_TICKET_ID, ticketB.ticketId)
            }
        )
        assertEquals("Conflicting DurableOwner must block V2 admission", 0, ownerConflictCtx.startedServiceIntents.size)

        // Restore Flag OFF default before Legacy V1 check
        DirectBootAlarmReceiver.outputAuthorityGateSeam = null
        DirectBootAlarmReceiver.v2DeliveryCapableProvider = { false }
        testGate.arbiter.clearForTesting()

        // Legacy V1 receiver + service keeps working cleanly
        val v1Intent = Intent(ceContext, DirectBootAlarmReceiver::class.java).apply {
            action = DirectBootAlarmReceiver.ACTION_FIRE_DIRECT_BOOT_ALARM
            putExtra(DirectBootAlarmReceiver.EXTRA_ALARM_ID, 203L)
            putExtra(DirectBootAlarmReceiver.EXTRA_TRIGGER_TIME, baseTime + 40_000L)
            putExtra(DirectBootAlarmReceiver.EXTRA_TIME_LABEL, "08:00")
            putExtra(DirectBootAlarmReceiver.EXTRA_PLAY_DEFAULT_SOUND, true)
            putExtra(DirectBootAlarmReceiver.EXTRA_VIBRATION_ENABLED, false)
        }
        val legacyCtx = RecordingServiceContext(ceContext)
        DirectBootAlarmReceiver().onReceive(legacyCtx, v1Intent)
        assertEquals(1, legacyCtx.startedServiceIntents.size)
        val v1SvcController = Robolectric.buildService(DirectBootAlarmService::class.java, legacyCtx.startedServiceIntents.single())
        val v1Service = v1SvcController.create().startCommand(0, 0).get()
        try {
            val v1Effects = DirectBootServiceTestFixture.captureEffects(v1Service)
            assertEquals(203L, v1Effects.alarmId)
            assertNotNull("Legacy V1 foreground notification must still be posted", v1Effects.foregroundNotification)
            assertFalse(v1Effects.stoppedBySelf)
        } finally {
            v1SvcController.destroy()
        }
    }

    // =========================================================================
    // 3. Step 4 (Legacy AlarmReceiver): DirectBoot Fence & Real AlarmService Route Preserved
    // =========================================================================

    @Test
    fun receiverBoundary_legacyAlarmReceiver_dropsDirectBootDelivered_andLeavesRealAlarmServiceRouteUntouched() {
        // Occurrence already delivered by DirectBoot -> AlarmReceiver drops intent
        val deliveredAlarmId = 301L
        val deliveredTime = baseTime + 50_000L
        val appendRes = DirectBootDeliveryLedger.appendRecord(
            deContext,
            DirectBootDeliveryRecord.createNormal(
                alarmId = deliveredAlarmId,
                triggerTime = deliveredTime,
                deliveredAt = deliveredTime + 10L,
                generation = 1,
                instanceToken = "tok-db-301",
                restoreEpoch = 1L
            )
        )
        assertTrue(appendRes is DeliveryAppendResult.Appended)

        val blockedCtx = RecordingServiceContext(ceContext)
        AlarmReceiver().onReceive(
            blockedCtx,
            Intent("com.sysadmindoc.alarmclock.ALARM_FIRED").apply {
                putExtra(AlarmScheduler.EXTRA_ALARM_ID, deliveredAlarmId)
                putExtra(AlarmScheduler.EXTRA_SCHEDULED_AT, deliveredTime)
            }
        )
        assertEquals("Legacy AlarmReceiver must drop occurrence delivered by DirectBoot", 0, blockedCtx.startedServiceIntents.size)

        // Legacy-only alarm with Gate OFF -> real AlarmService launch route unchanged
        val gate = SharedOutputAuthorityGate(processSessionToken = "proc-g3-off", bootCount = 1L).apply {
            isEnabled = false
        }
        outputAuthorityGateSeam = gate
        val legacyCtx = RecordingServiceContext(ceContext)
        AlarmReceiver().onReceive(
            legacyCtx,
            Intent("com.sysadmindoc.alarmclock.ALARM_FIRED").apply {
                putExtra(AlarmScheduler.EXTRA_ALARM_ID, 302L)
                putExtra(AlarmScheduler.EXTRA_SCHEDULED_AT, baseTime + 60_000L)
            }
        )
        assertEquals(1, legacyCtx.startedServiceIntents.size)
        assertEquals(AlarmService::class.java.name, legacyCtx.startedServiceIntents.single().component?.className)

        // With Gate ON and V2 DurableOwner owning occurrence -> AlarmReceiver rejects without AlarmService start
        val v2Item = DirectBootQueueItem(
            kind = DirectBootQueueItem.KIND_NORMAL,
            alarmId = 303L,
            triggerTime = baseTime + 70_000L,
            generation = 1,
            instanceToken = "tok-v2-303",
            restoreEpoch = 1L
        )
        DirectBootAlarmCache.writeQueue(deContext, listOf(v2Item), now = baseTime)
        DirectBootDurableOwnerStore.write(
            context = deContext,
            owner = DirectBootDurableOwner(
                state = OwnerState.HANDOVER_PENDING,
                ownerSeq = 1L,
                bootProvenance = BootProvenance(bootCount = 1),
                handoverClaim = HandoverClaim("h-303", 303L, baseTime + 70_000L, 1, "tok-v2-303", 1L),
                updatedAtEpochMs = baseTime
            ),
            expectedOwnerSeq = 0L
        )
        gate.isEnabled = true
        val v2OwnedCtx = RecordingServiceContext(ceContext)
        AlarmReceiver().onReceive(
            v2OwnedCtx,
            Intent("com.sysadmindoc.alarmclock.ALARM_FIRED").apply {
                putExtra(AlarmScheduler.EXTRA_ALARM_ID, 303L)
                putExtra(AlarmScheduler.EXTRA_SCHEDULED_AT, baseTime + 70_000L)
            }
        )
        assertEquals("Gate ON must block legacy service start for V2-owned occurrence", 0, v2OwnedCtx.startedServiceIntents.size)
    }

    // =========================================================================
    // 4. Step 5: Fallback Authority & Stale Owner / Dual-Ownership Prevention
    // =========================================================================

    @Test
    fun fallbackBoundary_flagOffLegacy_flagOnPreventsDuplicateDualOwnershipAndStaleStop() {
        val gate = SharedOutputAuthorityGate(processSessionToken = "proc-g3-fb", bootCount = 5L)

        // Flag OFF preserves legacy fallback
        gate.isEnabled = false
        assertTrue(
            AlarmFullScreenFallback.post(
                context = ceContext,
                alarmId = 401L,
                scheduledAt = baseTime + 10_000L,
                fireId = "fire-fb-401",
                hideLabel = false,
                label = "Legacy Fallback",
                authorityGate = gate
            )
        )

        // Flag ON: reserve + single post succeeds, duplicate post with same nonce rejected
        gate.isEnabled = true
        val key = OutputOccurrenceKey(402L, baseTime + 20_000L, 0)
        val reserve = gate.reserveForServiceStart(deContext, key, LegacyClaimSource.ALARM_RECEIVER)
        assertTrue(reserve is OutputAuthorizationResult.Authorized)
        val nonce = (reserve as OutputAuthorizationResult.Authorized).activeNonce

        assertTrue(
            AlarmFullScreenFallback.post(
                context = ceContext,
                alarmId = key.alarmId,
                scheduledAt = key.scheduledAt,
                fireId = "fire-fb-402",
                hideLabel = false,
                claimNonce = nonce,
                roomReceiptChecker = { _, _ -> false },
                authorityGate = gate
            )
        )
        assertFalse(
            "Duplicate fallback post with same claimNonce must fail closed",
            AlarmFullScreenFallback.post(
                context = ceContext,
                alarmId = key.alarmId,
                scheduledAt = key.scheduledAt,
                fireId = "fire-fb-402",
                hideLabel = false,
                claimNonce = nonce,
                roomReceiptChecker = { _, _ -> false },
                authorityGate = gate
            )
        )

        // Stale handle stop rejected by OutputResourceArbiter while preserving active handle
        val currentHandle = gate.arbiter.activeHandle
        assertNotNull(currentHandle)
        val staleHandle = ActiveOutputHandle(
            key = key,
            sink = OutputSinkType.FULLSCREEN_FALLBACK_1001,
            activeNonce = "stale-nonce-999"
        )
        assertFalse("Stale owner handle stop MUST be rejected", gate.arbiter.requestStop(staleHandle))
        assertEquals(currentHandle, gate.arbiter.activeHandle)

        // Dual output ownership prevention: ALARM_SERVICE_1001 holds occurrence -> Fallback claim blocked
        val serviceKey = OutputOccurrenceKey(403L, baseTime + 30_000L, 0)
        val svcReserve = gate.reserveForServiceStart(deContext, serviceKey, LegacyClaimSource.ALARM_RECEIVER)
        assertTrue(svcReserve is OutputAuthorizationResult.Authorized)
        val svcNonce = (svcReserve as OutputAuthorizationResult.Authorized).activeNonce
        val svcClaim = gate.claimOutput(
            context = deContext,
            key = serviceKey,
            incomingNonce = svcNonce,
            sink = OutputSinkType.ALARM_SERVICE_1001,
            roomReceiptChecker = { _, _ -> false },
            stopPreemptedOwner = { true }
        )
        assertTrue(svcClaim is OutputAuthorizationResult.Authorized)
        assertFalse(
            "Fallback must NOT double-own occurrence actively claimed by ALARM_SERVICE_1001",
            AlarmFullScreenFallback.post(
                context = ceContext,
                alarmId = serviceKey.alarmId,
                scheduledAt = serviceKey.scheduledAt,
                fireId = "fire-fb-403",
                hideLabel = false,
                claimNonce = svcNonce,
                roomReceiptChecker = { _, _ -> false },
                authorityGate = gate
            )
        )

        // V2-owned occurrence cannot receive fallback or legacy service reservation
        val v2Item = DirectBootQueueItem(
            kind = DirectBootQueueItem.KIND_NORMAL,
            alarmId = 404L,
            triggerTime = baseTime + 40_000L,
            generation = 1,
            instanceToken = "tok-v2-404",
            restoreEpoch = 1L
        )
        DirectBootAlarmCache.writeQueue(deContext, listOf(v2Item), now = baseTime)
        DirectBootDurableOwnerStore.write(
            context = deContext,
            owner = DirectBootDurableOwner(
                state = OwnerState.HANDOVER_PENDING,
                ownerSeq = 1L,
                bootProvenance = BootProvenance(bootCount = 5),
                handoverClaim = HandoverClaim("h-404", 404L, baseTime + 40_000L, 1, "tok-v2-404", 1L),
                updatedAtEpochMs = baseTime
            ),
            expectedOwnerSeq = 0L
        )
        val v2Reserve = gate.reserveForServiceStart(
            deContext,
            OutputOccurrenceKey(404L, baseTime + 40_000L, 0),
            LegacyClaimSource.ALARM_RECEIVER
        )
        assertTrue("V2-owned occurrence must be blocked from legacy/fallback authority", v2Reserve is OutputAuthorizationResult.V2LaneOwnedRejected)
    }

    // =========================================================================
    // 5. Step 6: Unlock / DE->CE Handoff, Fail-Closed Guards, & Zero Rev2 Proof Abuse
    // =========================================================================

    @Test
    fun unlockDeCeHandoff_receiptAckRetirement_duplicateAndCorruptFailClosed_andNeverProvesRev2Cancel() = runBlocking {
        val alarmId = 501L
        val seeded = seedABCWithRev2AndLegacyAndUnknownIntents(alarmId)
        val deliveredTime = baseTime + 120_000L

        // Missing DE record -> handoffDelivered fails closed
        val missingRecord = DirectBootDeliveryRecord.createNormal(
            alarmId = 599L,
            triggerTime = deliveredTime,
            deliveredAt = deliveredTime + 10L,
            generation = 1,
            instanceToken = "tok-missing",
            restoreEpoch = 2L
        )
        assertTrue(
            DirectBootCeHandoffCoordinator.handoffDelivered(deContext, ceContext, missingRecord, baseTime) is CeHandoffResult.Rejected.DeRecordNotFound
        )

        // Seed valid DE delivery + witness for alarmId
        val delRecord = DirectBootDeliveryRecord.createNormal(
            alarmId = alarmId,
            triggerTime = deliveredTime,
            deliveredAt = deliveredTime + 25L,
            generation = 3,
            instanceToken = "tok-de-501",
            restoreEpoch = 2L
        )
        assertTrue(DirectBootDeliveryLedger.appendRecord(deContext, delRecord) is DeliveryAppendResult.Appended)
        val witness = DirectBootFiredWitnessRecord.createNormal(
            alarmId = alarmId,
            triggerTime = deliveredTime,
            generation = 3,
            instanceToken = "tok-de-501",
            restoreEpoch = 2L,
            deliveryMode = DirectBootFiredWitnessRecord.MODE_SOUND_AND_VIBRATION,
            deliveryOutcome = DirectBootFiredWitnessRecord.OUTCOME_OUTPUT_START_ACCEPTED,
            observedAtEpochMs = deliveredTime + 20L
        )
        assertTrue(DirectBootFiredWitnessLedger.appendWitness(deContext, witness) is WitnessAppendResult.Appended)

        // First handoff -> Confirmed; Duplicate handoff -> idempotent (no duplicate write)
        val handoff1 = DirectBootCeHandoffCoordinator.handoffDelivered(deContext, ceContext, delRecord, baseTime + 50L)
        assertTrue(handoff1 is CeHandoffResult.Confirmed)
        assertTrue((handoff1 as CeHandoffResult.Confirmed).writePerformed)

        val handoff2 = DirectBootCeHandoffCoordinator.handoffDelivered(deContext, ceContext, delRecord, baseTime + 60L)
        assertTrue(handoff2 is CeHandoffResult.Confirmed)
        assertTrue((handoff2 as CeHandoffResult.Confirmed).alreadyRecordedInCe)
        assertFalse(handoff2.writePerformed)

        // Execute real DirectBootUnlockReceiver -> DirectBootEventConsumerEngine -> Room 38 receipt + DE/Witness/CE retirement
        DirectBootUnlockReceiver.databaseSeam = { database }
        val latch = CountDownLatch(1)
        var consumerRes: DirectBootEventConsumerEngine.ConsumerExecutionResult? = null
        DirectBootUnlockReceiver.onConsumerFinishedSeam = { res ->
            consumerRes = res
            latch.countDown()
        }
        val unlockReceiver = spyk(DirectBootUnlockReceiver()).also {
            every { it.goAsync() } returns mockk(relaxed = true)
        }
        unlockReceiver.onReceive(ceContext, Intent(Intent.ACTION_USER_UNLOCKED))
        assertTrue("DirectBootUnlockReceiver consumer did not finish in time", latch.await(5, TimeUnit.SECONDS))
        assertTrue(consumerRes is DirectBootEventConsumerEngine.ConsumerExecutionResult.Success)

        val receiptDao = database.directBootOccurrenceReceiptDao()
        assertEquals(1L, receiptDao.count())
        val occKey = DirectBootCeTakeoverStore.computeOccurrenceKey(alarmId, deliveredTime, 3, "tok-de-501")
        assertEquals("CONSUMED_DELIVERED", receiptDao.findByOccurrenceKey(occKey)?.consumerOutcome)
        assertTrue(DirectBootCeTakeoverStore.inspectStorageState(ceContext) is CeTakeoverStorageState.ValidEmpty)
        assertTrue(DirectBootDeliveryLedger.readUnacknowledgedRecords(deContext) is LedgerStorageState.ValidEmpty)

        // Corrupt CE storage fails closed cleanly without writing additional Room 38 receipts
        ceContext.getSharedPreferences(DirectBootCeTakeoverStore.PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putString(DirectBootCeTakeoverStore.KEY_CE_TAKEOVER_PAYLOAD, "{corrupt_ce_json").commit()
        val corruptConsumerRes = DirectBootEventConsumerEngine.consumePendingTakeoverRecords(deContext, ceContext, database)
        assertTrue(corruptConsumerRes is DirectBootEventConsumerEngine.ConsumerExecutionResult.Rejected)
        assertEquals(1L, receiptDao.count())

        // Legacy V1 record (restoreEpoch = 0L) quarantined fail-closed without CE release or CONSUMED receipt
        DirectBootCeTakeoverStore.clear(ceContext)
        val v1Trigger = baseTime + 200_000L
        val v1Missed = DirectBootMissedRecord.createNormal(
            alarmId = 502L,
            triggerTime = v1Trigger,
            missedAt = baseTime + 200_500L,
            generation = 1,
            instanceToken = "tok-v1-epoch0",
            classification = DirectBootMissedRecord.CLASSIFICATION_OVERDUE,
            restoreEpoch = 0L
        )
        DirectBootCeTakeoverStore.recordMissed(ceContext, v1Missed, baseTime + 200_600L)
        val v1ConsumerRes = DirectBootEventConsumerEngine.consumePendingTakeoverRecords(deContext, ceContext, database)
        assertTrue(v1ConsumerRes is DirectBootEventConsumerEngine.ConsumerExecutionResult.Success)
        assertEquals(0, (v1ConsumerRes as DirectBootEventConsumerEngine.ConsumerExecutionResult.Success).receiptsCommitted)
        val v1OccKey = DirectBootCeTakeoverStore.computeOccurrenceKey(502L, v1Trigger, 1, "tok-v1-epoch0")
        assertEquals("RESTORE_EPOCH_CONFLICT", receiptDao.findByOccurrenceKey(v1OccKey)?.consumerOutcome)
        assertTrue(DirectBootCeTakeoverStore.inspectStorageState(ceContext) is CeTakeoverStorageState.ValidWithRecords)

        // CRITICAL G3 INVARIANT: DE->CE Room 38 receipt for alarmId NEVER acts as OS-cancel proof for Rev2 CANCEL!
        val replayBatch = integration.replayUnresolvedCancelIntents(
            evaluationTimeMillis = baseTime + 300_000L,
            gateway = null,
            perTokenContext = mapOf(
                seeded.tokenB to Rev2ReplayInspectionContext(
                    alarmId = alarmId,
                    targetIssueToken = seeded.tokenB,
                    evaluationTimeMillis = baseTime + 300_000L,
                    crashCutpointHint = Rev2ReplayCrashCutpoint.C1_POST_PENDING_PERSISTED,
                    deLineageStatus = DeLineageStatus.LINEAGE_MISSING
                )
            )
        )
        val rev2Summary = replayBatch.results.single { it.targetIssueToken == seeded.tokenB }
        assertEquals(Rev2ReplayRoute.REV2_INACTIVE_REPLAY, rev2Summary.route)
        assertEquals(Rev2ReplayRecoveryOutcome.PENDING_SAFE_B_CANDIDATE_HOLD, rev2Summary.outcome)
        assertEquals(0, replayBatch.completedSettlementCount)
        assertEquals(0, replayBatch.abortedTransitionCount)
        assertEquals(RealOsEligibility.BLOCKED, rev2Summary.realOsEligibility)
        assertEquals(0, replayBatch.realOsCalls)
        assertEquals(DurableOperationIntent.PHASE_PENDING, intentDao.getIntentByToken(seeded.tokenB)?.phase)
        assertEquals(IssuedOccurrence.STATUS_ISSUED, issuedDao.getOccurrence(seeded.tokenA)?.status)
        assertEquals(IssuedOccurrence.STATUS_PREPARED, issuedDao.getOccurrence(seeded.tokenB)?.status)
    }

    // =========================================================================
    // 6. Step 7: Combined G1/G2 & Phase B Invariants (Rev1/Rev2/Unknown + A/B/C)
    // =========================================================================

    @Test
    fun g1g2Boundary_phaseBStateActiveWithMixedRevisions_preservesABCAndBlockedRealOsEligibility() = runBlocking {
        val alarmId = 601L
        val seeded = seedABCWithRev2AndLegacyAndUnknownIntents(alarmId, activateSuccessorC = false)

        // Move Rev2 CANCEL into IN_FLIGHT via P3C CAS API (simulating C2/C6 crash)
        val casRows = intentDao.casTransitionBSpecificCancelIntentToInFlight(
            operationToken = seeded.tokenB,
            alarmId = alarmId,
            expectedSlotGenId = seeded.slotGenIdB,
            expectedSlotOwnerToken = seeded.tokenA,
            expectedOwnerToken = seeded.ownerToken
        )
        assertEquals(1, casRows)

        // Also populate Phase B DE queue & Room 38 receipt concurrently
        DirectBootAlarmCache.writeQueue(
            deContext,
            listOf(
                DirectBootQueueItem(
                    kind = DirectBootQueueItem.KIND_NORMAL,
                    alarmId = alarmId,
                    triggerTime = baseTime + 180_000L,
                    generation = 3,
                    instanceToken = seeded.ownerToken,
                    restoreEpoch = 2L
                )
            ),
            now = baseTime
        )
        database.directBootOccurrenceReceiptDao().insert(
            DirectBootOccurrenceReceipt(
                occurrenceKey = "occ-key-601",
                sourceRecordId = "src-601",
                recordType = "DELIVERED",
                alarmId = alarmId,
                triggerTime = baseTime + 60_000L,
                generation = 3,
                instanceToken = seeded.tokenA,
                restoreEpoch = 2L,
                sourcePayloadHash = "hash-601",
                consumerOutcome = "CONSUMED_DELIVERED",
                completedAt = baseTime + 65_000L
            )
        )

        val batch = integration.replayUnresolvedCancelIntents(
            evaluationTimeMillis = baseTime + 70_000L,
            gateway = null,
            perTokenContext = mapOf(
                seeded.tokenB to Rev2ReplayInspectionContext(
                    alarmId = alarmId,
                    targetIssueToken = seeded.tokenB,
                    evaluationTimeMillis = baseTime + 70_000L,
                    crashCutpointHint = Rev2ReplayCrashCutpoint.C6_REBOOT_WITH_UNKNOWN_OS_OUTCOME,
                    deLineageStatus = DeLineageStatus.UNCERTAIN
                )
            )
        )

        assertEquals(3, batch.results.size)
        assertEquals(Rev2ReplayRoute.DELEGATED_TO_LEGACY_REV1, batch.results.single { it.targetIssueToken == seeded.rev1Token }.route)
        val rev2Res = batch.results.single { it.targetIssueToken == seeded.tokenB }
        assertEquals(Rev2ReplayRoute.REV2_INACTIVE_REPLAY, rev2Res.route)
        assertEquals(Rev2ReplayRecoveryOutcome.IN_FLIGHT_UNKNOWN_OS_RESTART_HOLD, rev2Res.outcome)
        assertEquals(DurableOperationIntent.PHASE_IN_FLIGHT, intentDao.getIntentByToken(seeded.tokenB)?.phase)
        assertEquals(Rev2ReplayRoute.UNKNOWN_REVISION_FAIL_CLOSED, batch.results.single { it.targetIssueToken == seeded.unkToken }.route)

        // All 8 SideEffectTarget decisions must remain Blocked
        assertTrue(rev2Res.boundaryReport.isAllNonExecutable)
        SideEffectTarget.entries.forEach { target ->
            assertTrue(
                "Effect $target must remain Blocked during G3 Rev2 replay",
                rev2Res.boundaryReport.decisions[target] is PureEffectDecision.Blocked
            )
        }

        // Global G3 invariants before C activation
        assertEquals(0, batch.completedSettlementCount)
        assertEquals(0, batch.abortedTransitionCount)
        assertEquals(0, batch.realOsCalls)
        assertEquals(RealOsEligibility.BLOCKED, rev2Res.realOsEligibility)
        assertEquals(IssuedOccurrence.STATUS_ISSUED, issuedDao.getOccurrence(seeded.tokenA)?.status)
        assertEquals(IssuedOccurrence.STATUS_PREPARED, issuedDao.getOccurrence(seeded.tokenB)?.status)

        // Now reserve & activate successor C after IN_FLIGHT CAS -> B->C race isolated, C preserved!
        database.withTransaction {
            issuedDao.reserveIssuedOccurrenceWithAuthority(
                issueToken = seeded.tokenC,
                alarmId = alarmId,
                scheduledAt = seeded.schedC,
                fireId = seeded.fireIdC,
                authorityInstanceToken = seeded.ownerToken,
                restoreEpoch = 2L,
                scheduleRevision = 12L,
                definitionRevision = 4L,
                alarmGeneration = 3
            )
            issuedDao.activateIssuedOccurrence(seeded.tokenC)
        }
        val bcBatch = integration.replayUnresolvedCancelIntents(
            evaluationTimeMillis = baseTime + 75_000L,
            gateway = null,
            perTokenContext = mapOf(
                seeded.tokenB to Rev2ReplayInspectionContext(
                    alarmId = alarmId,
                    targetIssueToken = seeded.tokenB,
                    evaluationTimeMillis = baseTime + 75_000L,
                    crashCutpointHint = Rev2ReplayCrashCutpoint.C6_REBOOT_WITH_UNKNOWN_OS_OUTCOME,
                    deLineageStatus = DeLineageStatus.UNCERTAIN
                )
            )
        )
        val bcRes = bcBatch.results.single { it.targetIssueToken == seeded.tokenB }
        assertEquals(Rev2ReplayRecoveryOutcome.B_TO_C_RACE_ISOLATED, bcRes.outcome)
        assertEquals(0, bcBatch.completedSettlementCount)
        assertEquals(0, bcBatch.abortedTransitionCount)
        assertEquals(0, bcBatch.realOsCalls)
        assertEquals(RealOsEligibility.BLOCKED, bcRes.realOsEligibility)
        assertEquals(IssuedOccurrence.STATUS_REVOKED, issuedDao.getOccurrence(seeded.tokenA)?.status)
        assertEquals(IssuedOccurrence.STATUS_PREPARED, issuedDao.getOccurrence(seeded.tokenB)?.status)
        assertEquals(IssuedOccurrence.STATUS_ISSUED, issuedDao.getOccurrence(seeded.tokenC)?.status)
        assertEquals(seeded.tokenC, issuedDao.getSlot(alarmId)?.currentIssueToken)
    }
}
