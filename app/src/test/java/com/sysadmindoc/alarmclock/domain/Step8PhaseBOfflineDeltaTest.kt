package com.sysadmindoc.alarmclock.domain

import android.app.AlarmManager
import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import androidx.work.Operation
import androidx.work.WorkManager
import com.sysadmindoc.alarmclock.data.model.Alarm
import com.sysadmindoc.alarmclock.data.preferences.AppSettings
import com.sysadmindoc.alarmclock.data.preferences.PreferencesManager
import com.sysadmindoc.alarmclock.data.repository.AlarmIncidentRepository
import com.sysadmindoc.alarmclock.data.repository.AlarmRepository
import com.sysadmindoc.alarmclock.data.repository.HolidayRepository
import com.sysadmindoc.alarmclock.directboot.ActiveServiceSnapshot
import com.sysadmindoc.alarmclock.directboot.CandidateLane
import com.sysadmindoc.alarmclock.directboot.DirectBootAlarmCache
import com.sysadmindoc.alarmclock.directboot.DirectBootAlarmReceiver
import com.sysadmindoc.alarmclock.directboot.DirectBootAlarmService
import com.sysadmindoc.alarmclock.directboot.DirectBootDeliveryAuthorizer
import com.sysadmindoc.alarmclock.directboot.DirectBootDeliveryLedger
import com.sysadmindoc.alarmclock.directboot.DirectBootQueueItem
import com.sysadmindoc.alarmclock.directboot.DirectBootServiceDeliveryAdapter
import com.sysadmindoc.alarmclock.directboot.DirectBootServiceStartPolicy
import com.sysadmindoc.alarmclock.directboot.DirectBootV2NormalIntentIdentity
import com.sysadmindoc.alarmclock.directboot.StartStorageObservation
import com.sysadmindoc.alarmclock.directboot.TicketIssueResult
import com.sysadmindoc.alarmclock.receiver.BootReceiver
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
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
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
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class Step8PhaseBOfflineDeltaTest {

    private lateinit var appContext: Context
    private lateinit var deContext: Context
    private val baseTime = 1_700_000_000_000L

    private class RecordingServiceContext(
        base: Context,
        private val throwOnStartService: Boolean = false
    ) : ContextWrapper(base) {
        val startedServiceIntents = mutableListOf<Intent>()

        override fun getApplicationContext(): Context = this

        override fun startForegroundService(service: Intent?): android.content.ComponentName? {
            if (throwOnStartService) {
                throw IllegalStateException("Simulated FGS start failure")
            }
            if (service != null) {
                startedServiceIntents.add(service)
            }
            return null
        }

        override fun startService(service: Intent?): android.content.ComponentName? {
            if (throwOnStartService) {
                throw IllegalStateException("Simulated service start failure")
            }
            if (service != null) {
                startedServiceIntents.add(service)
            }
            return null
        }
    }

    @Before
    fun setUp() {
        appContext = ApplicationProvider.getApplicationContext()
        deContext = appContext.createDeviceProtectedStorageContext()
        DirectBootAlarmCache.clear(deContext)
        DirectBootAlarmCache.clearQueue(deContext)
        deContext.getSharedPreferences(DirectBootDeliveryLedger.PREFS_NAME, Context.MODE_PRIVATE).edit().clear().commit()
        DirectBootAlarmReceiver.resetSeamsForTesting()
        ShadowLog.clear()
    }

    @After
    fun tearDown() {
        DirectBootAlarmCache.clear(deContext)
        DirectBootAlarmCache.clearQueue(deContext)
        deContext.getSharedPreferences(DirectBootDeliveryLedger.PREFS_NAME, Context.MODE_PRIVATE).edit().clear().commit()
        DirectBootAlarmReceiver.resetSeamsForTesting()
        unmockkAll()
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun bootReceiver_lockedBoot_surfacesTypedFallbackOutcomeWhenV1FallbackFails() {
        val now = System.currentTimeMillis()
        val triggerTime = now + 600_000L
        DirectBootAlarmCache.saveIfEarlier(
            deContext,
            Alarm(id = 901L, hour = 7, minute = 0, isEnabled = true, nextTriggerTime = triggerTime),
            triggerTime,
            now
        )

        // Wrap context so AlarmManager.canScheduleExactAlarms() returns false -> PermissionDenied
        val deniedAm = mockk<AlarmManager>(relaxed = true) {
            every { canScheduleExactAlarms() } returns false
        }
        val deniedDeCtx = object : ContextWrapper(deContext) {
            override fun isDeviceProtectedStorage(): Boolean = true
            override fun createDeviceProtectedStorageContext(): Context = this
            override fun getSystemService(name: String): Any? =
                if (name == Context.ALARM_SERVICE) deniedAm else super.getSystemService(name)
        }
        val wrappedAppCtx = object : ContextWrapper(appContext) {
            override fun getApplicationContext(): Context = this
            override fun isDeviceProtectedStorage(): Boolean = false
            override fun createDeviceProtectedStorageContext(): Context = deniedDeCtx
            override fun getSystemService(name: String): Any? =
                if (name == Context.ALARM_SERVICE) deniedAm else super.getSystemService(name)
        }

        val receiver = BootReceiver().apply {
            testScope = TestScope(UnconfinedTestDispatcher())
        }
        ShadowLog.clear()

        receiver.onReceive(wrappedAppCtx, Intent(Intent.ACTION_LOCKED_BOOT_COMPLETED))

        val logs = ShadowLog.getLogsForTag("BootReceiver")
        assertTrue(
            "BootReceiver MUST log typed non-armed DirectBoot fallback result instead of discarding Boolean",
            logs.any { it.msg.contains("PermissionDenied") || it.msg.contains("DirectBoot fallback") }
        )
    }

    @Test
    fun directBootAlarmReceiver_v2Fire_forwardsCanonicalUriAndIdentityExtrasToServiceIntent() {
        val futureTrigger = System.currentTimeMillis() + 60_000L
        val item = DirectBootQueueItem(
            kind = DirectBootQueueItem.KIND_NORMAL,
            alarmId = 902L,
            triggerTime = futureTrigger,
            generation = 2,
            instanceToken = "tok-v2-fwd-902",
            restoreEpoch = 3L
        )
        assertTrue(DirectBootAlarmCache.writeQueue(deContext, listOf(item), now = futureTrigger - 60_000L))

        val authorizer = DirectBootDeliveryAuthorizer()
        val identity = DirectBootV2NormalIntentIdentity.fromQueueItem(item)
        val ticket = (authorizer.issueTicket(identity, nowEpochMs = futureTrigger) as TicketIssueResult.Success).ticket

        DirectBootAlarmReceiver.serviceAdapterSeam = { ctx ->
            DirectBootServiceDeliveryAdapter(ctx, authorizer, clock = { futureTrigger })
        }

        val v2Intent = identity.createIntent(appContext).apply {
            putExtra(DirectBootDeliveryAuthorizer.EXTRA_TICKET_ID, ticket.ticketId)
        }

        // Production default (gate.isEnabled = false, v2DeliveryCapableProvider = false) fails closed
        val flagOffCtx = RecordingServiceContext(appContext)
        DirectBootAlarmReceiver().onReceive(flagOffCtx, v2Intent)
        assertEquals("Flag OFF default MUST drop V2 delivery", 0, flagOffCtx.startedServiceIntents.size)

        // Explicit test V2 capability seams verify canonical URI & identity extras forwarding
        val testGate = com.sysadmindoc.alarmclock.directboot.outputauthority.SharedOutputAuthorityGate(
            bootCount = 10L,
            processSessionToken = "proc-v2-fwd"
        ).apply { isEnabled = true }
        DirectBootAlarmReceiver.outputAuthorityGateSeam = testGate
        DirectBootAlarmReceiver.v2DeliveryCapableProvider = { true }

        val recordingCtx = RecordingServiceContext(appContext)
        DirectBootAlarmReceiver().onReceive(recordingCtx, v2Intent)

        assertEquals(1, recordingCtx.startedServiceIntents.size)
        val serviceIntent = recordingCtx.startedServiceIntents.single()
        assertEquals(DirectBootAlarmService.ACTION_START, serviceIntent.action)
        assertEquals(v2Intent.data, serviceIntent.data)
        assertEquals(
            DirectBootV2NormalIntentIdentity.KIND_NORMAL,
            serviceIntent.getStringExtra(DirectBootV2NormalIntentIdentity.EXTRA_KIND)
        )
        assertEquals(
            identity.computeDigest(),
            serviceIntent.getStringExtra(DirectBootV2NormalIntentIdentity.EXTRA_IDENTITY_DIGEST)
        )
        assertEquals(
            ticket.ticketId,
            serviceIntent.getStringExtra(DirectBootDeliveryAuthorizer.EXTRA_TICKET_ID)
        )
    }

    @Test
    fun directBootAlarmReceiver_v1Fire_preservesLegacyV1CandidateWithoutV2Extras() {
        val v1Intent = Intent(appContext, DirectBootAlarmReceiver::class.java).apply {
            action = DirectBootAlarmReceiver.ACTION_FIRE_DIRECT_BOOT_ALARM
            putExtra(DirectBootAlarmReceiver.EXTRA_ALARM_ID, 903L)
            putExtra(DirectBootAlarmReceiver.EXTRA_TRIGGER_TIME, baseTime + 90_000L)
            putExtra(DirectBootAlarmReceiver.EXTRA_LABEL, "Legacy 903")
            putExtra(DirectBootAlarmReceiver.EXTRA_TIME_LABEL, "09:03")
        }
        val recordingCtx = RecordingServiceContext(appContext)
        DirectBootAlarmReceiver().onReceive(recordingCtx, v1Intent)

        assertEquals(1, recordingCtx.startedServiceIntents.size)
        val serviceIntent = recordingCtx.startedServiceIntents.single()
        val candidate = DirectBootServiceStartPolicy.extractCandidate(serviceIntent)
        val classification = DirectBootServiceStartPolicy.classify(
            com.sysadmindoc.alarmclock.directboot.ServiceStartClassificationInput(
                active = ActiveServiceSnapshot(),
                candidate = candidate,
                storage = StartStorageObservation()
            )
        )
        assertEquals(
            "Forwarded V1 serviceIntent MUST classify as LEGACY_V1 candidate",
            CandidateLane.LEGACY_V1,
            classification.lane
        )
        assertNull(serviceIntent.data)
        assertFalse(serviceIntent.hasExtra(DirectBootV2NormalIntentIdentity.EXTRA_KIND))
        assertFalse(serviceIntent.hasExtra(DirectBootV2NormalIntentIdentity.EXTRA_IDENTITY_DIGEST))
        assertFalse(serviceIntent.hasExtra(DirectBootDeliveryAuthorizer.EXTRA_TICKET_ID))
    }

    @Test
    fun alarmScheduler_batchReschedule_preservesEnabledV1FallbackAndV2QueueWhenOsSetFails() = runTest {
        val now = System.currentTimeMillis()
        val futureTrigger = now + 600_000L
        val alarm = Alarm(
            id = 910L,
            hour = 8,
            minute = 15,
            isEnabled = true,
            nextTriggerTime = futureTrigger,
            generation = 1
        )
        DirectBootAlarmCache.saveIfEarlier(deContext, alarm, futureTrigger, now)
        val v2Item = DirectBootQueueItem(
            kind = DirectBootQueueItem.KIND_NORMAL,
            alarmId = 910L,
            triggerTime = futureTrigger,
            generation = 1,
            instanceToken = "tok-910",
            restoreEpoch = 1L
        )
        assertTrue(DirectBootAlarmCache.writeQueue(deContext, listOf(v2Item), now = now))

        val repository = mockk<AlarmRepository>(relaxed = true)
        val calculator = mockk<NextAlarmCalculator>()
        val preferencesManager = mockk<PreferencesManager>()
        val holidayRepository = mockk<HolidayRepository>()
        val incidentRepository = mockk<AlarmIncidentRepository>(relaxed = true)
        val weatherRepository = mockk<com.sysadmindoc.alarmclock.data.repository.WeatherRepository>(relaxed = true)
        val workManager = mockk<WorkManager>(relaxed = true)

        mockkStatic(WorkManager::class)
        every { WorkManager.getInstance(any<Context>()) } returns workManager
        every { workManager.cancelUniqueWork(any()) } returns mockk<Operation>(relaxed = true)
        mockkObject(WidgetUpdater)
        every { WidgetUpdater.requestUpdate(any()) } just Runs
        mockkObject(BedtimeZenRuleManager)
        every { BedtimeZenRuleManager.syncRule(any(), any(), any()) } returns mockk(relaxed = true)

        coEvery { repository.getEnabledNormalAlarms() } returns listOf(alarm)
        coEvery { repository.getById(910L) } returns alarm
        coEvery { repository.getAuthorityByAlarmId(910L) } returns null
        coEvery { preferencesManager.getCurrentSettings() } returns AppSettings()
        every { preferencesManager.getCachedSettings() } returns AppSettings()
        coEvery { holidayRepository.isHoliday(any()) } returns false
        every { calculator.calculate(any<Alarm>()) } returns futureTrigger
        every { calculator.calculate(any<Alarm>(), any()) } returns futureTrigger

        val failingAm = mockk<AlarmManager>(relaxed = true) {
            every { canScheduleExactAlarms() } returns true
            every { setAlarmClock(any(), any()) } throws RuntimeException("Simulated OS failure")
            every { setExactAndAllowWhileIdle(any(), any(), any()) } throws RuntimeException("Simulated OS failure")
        }

        val scheduler = AlarmScheduler(
            context = deContext,
            repository = repository,
            calculator = calculator,
            preferencesManager = preferencesManager,
            holidayRepository = holidayRepository,
            alarmIncidentRepository = incidentRepository,
            weatherRepository = weatherRepository
        ).apply {
            testAlarmManager = failingAm
        }

        val batchResult = scheduler.rescheduleAllInBatches(forceRecalculate = false)
        assertEquals(1, batchResult.failedCount)
        assertEquals(0, batchResult.armedCount)

        val preservedSnapshot = DirectBootAlarmCache.read(deContext)
        assertNotNull(
            "Enabled alarm's DirectBoot V1 snapshot MUST NOT be wiped when batch OS registration fails",
            preservedSnapshot
        )
        assertEquals(910L, preservedSnapshot?.alarmId)
        assertEquals(1, DirectBootAlarmCache.readQueue(deContext, now = now).size)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun bootReceiver_lockedBoot_suppressesV1FallbackOnlyWhenV2CapableAndReconcilerArmed() {
        val fakeReconciler = mockk<com.sysadmindoc.alarmclock.directboot.DirectBootRegistrationReconciler>()
        val queueItem = DirectBootQueueItem(
            kind = DirectBootQueueItem.KIND_NORMAL,
            alarmId = 920L,
            triggerTime = baseTime + 120_000L,
            generation = 1,
            instanceToken = "tok-920",
            restoreEpoch = 1L
        )
        val v2Id = DirectBootV2NormalIntentIdentity.fromQueueItem(queueItem)
        val armedPlan = com.sysadmindoc.alarmclock.directboot.DirectBootRegistrationPlan.ArmNormal(
            newIdentity = v2Id,
            oldIdentityToCancel = null,
            queueHead = queueItem
        )
        every { fakeReconciler.reconcile(any()) } returns com.sysadmindoc.alarmclock.directboot.ReconcilerResult.StableSuccess(
            plan = armedPlan,
            cancellationResult = null,
            registrationResult = com.sysadmindoc.alarmclock.directboot.ExactRegistrationResult.Accepted(v2Id)
        )

        var fallbackCallsDefaultDeny = 0
        val receiverDefaultDeny = BootReceiver().apply {
            testScope = TestScope(UnconfinedTestDispatcher())
            reconcilerProvider = { fakeReconciler }
            // v2DeliveryCapableProvider defaults to { false }
            legacyFallbackScheduler = {
                fallbackCallsDefaultDeny++
                com.sysadmindoc.alarmclock.directboot.DirectBootFallbackScheduleResult.NoSchedulableTarget(
                    reason = com.sysadmindoc.alarmclock.directboot.NoTargetReason.SNAPSHOT_ABSENT
                )
            }
        }
        receiverDefaultDeny.onReceive(appContext, Intent(Intent.ACTION_LOCKED_BOOT_COMPLETED))
        assertEquals(
            "Under DEFAULT_DENY (v2DeliveryCapableProvider = false), BootReceiver MUST still execute V1 fallback",
            1,
            fallbackCallsDefaultDeny
        )

        var fallbackCallsV2Capable = 0
        val receiverV2Capable = BootReceiver().apply {
            testScope = TestScope(UnconfinedTestDispatcher())
            reconcilerProvider = { fakeReconciler }
            v2DeliveryCapableProvider = { true }
            legacyFallbackScheduler = {
                fallbackCallsV2Capable++
                com.sysadmindoc.alarmclock.directboot.DirectBootFallbackScheduleResult.NoSchedulableTarget(
                    reason = com.sysadmindoc.alarmclock.directboot.NoTargetReason.SNAPSHOT_ABSENT
                )
            }
        }
        receiverV2Capable.onReceive(appContext, Intent(Intent.ACTION_LOCKED_BOOT_COMPLETED))
        assertEquals(
            "When V2 is delivery-capable AND reconciler confirmed Armed, BootReceiver MUST suppress V1 fallback",
            0,
            fallbackCallsV2Capable
        )
    }

    @Test
    fun directBootAlarmReceiver_startServiceFailure_abortsGateClaimAndHandlesV1Cleanly() {
        val gate = com.sysadmindoc.alarmclock.directboot.outputauthority.SharedOutputAuthorityGate(
            bootCount = 10L,
            processSessionToken = "proc-db-recv"
        ).apply { isEnabled = true }
        DirectBootAlarmReceiver.outputAuthorityGateSeam = gate

        val throwingCtx = RecordingServiceContext(appContext, throwOnStartService = true)
        val v1Intent = Intent(appContext, DirectBootAlarmReceiver::class.java).apply {
            action = DirectBootAlarmReceiver.ACTION_FIRE_DIRECT_BOOT_ALARM
            putExtra(DirectBootAlarmReceiver.EXTRA_ALARM_ID, 930L)
            putExtra(DirectBootAlarmReceiver.EXTRA_TRIGGER_TIME, baseTime + 90_000L)
        }
        DirectBootAlarmReceiver().onReceive(throwingCtx, v1Intent)

        // Gate claim must be aborted cleanly (TERMINAL / ABORTED_PRE_IPC) with arbiter released
        assertNull(gate.arbiter.activeHandle)
        val claims = com.sysadmindoc.alarmclock.directboot.DirectBootLegacyClaimStore.inspectStorageState(deContext)
        assertTrue(claims is com.sysadmindoc.alarmclock.directboot.LegacyClaimStorageState.ValidWithRecords)
        val record = (claims as com.sysadmindoc.alarmclock.directboot.LegacyClaimStorageState.ValidWithRecords).records.single()
        assertEquals(com.sysadmindoc.alarmclock.directboot.LegacyClaimState.TERMINAL, record.state)
        assertEquals(com.sysadmindoc.alarmclock.directboot.LegacyTerminalOutcome.ABORTED_PRE_IPC, record.terminalOutcome)

        // Clean up DE claim prefs
        com.sysadmindoc.alarmclock.directboot.DirectBootLegacyClaimStore.prefs(deContext).edit().clear().commit()
    }

    @Test
    fun alarmScheduler_ownerActiveOriginAuthorityNull_invokesBootstrapWhenProvided_andLegacyNeverCallsBootstrap() = runTest {
        val futureTrigger = System.currentTimeMillis() + 600_000L
        val alarm = Alarm(id = 940L, hour = 7, minute = 30, isEnabled = true, generation = 1)
        val bootstrappedAuth = com.sysadmindoc.alarmclock.data.local.entity.ScheduleAuthority(
            alarmId = 940L,
            instanceToken = "tok-boot-940",
            definitionRevision = ScheduleAuthorityBootstrapAdapter.computeDefinitionRevision(alarm),
            scheduleRevision = 1L,
            lifecycle = "LIVE",
            desiredOperation = "SET",
            desiredTriggerMillis = futureTrigger,
            reconcileState = "SETTLED",
            restoreEpoch = 1L,
            actionKey = "act-boot-940"
        )

        val repository = mockk<AlarmRepository>(relaxed = true)
        val calculator = mockk<NextAlarmCalculator>()
        val preferencesManager = mockk<PreferencesManager>()
        val holidayRepository = mockk<HolidayRepository>()
        val incidentRepository = mockk<AlarmIncidentRepository>(relaxed = true)
        val weatherRepository = mockk<com.sysadmindoc.alarmclock.data.repository.WeatherRepository>(relaxed = true)
        val workManager = mockk<WorkManager>(relaxed = true)
        val bootstrapAdapter = mockk<ScheduleAuthorityBootstrapAdapter>()
        val localReader = mockk<com.sysadmindoc.alarmclock.data.local.LocalIncarnationEvidenceReader>()

        mockkStatic(WorkManager::class)
        every { WorkManager.getInstance(any<Context>()) } returns workManager
        every { workManager.cancelUniqueWork(any()) } returns mockk<Operation>(relaxed = true)
        mockkObject(WidgetUpdater)
        every { WidgetUpdater.requestUpdate(any()) } just Runs
        mockkObject(BedtimeZenRuleManager)
        every { BedtimeZenRuleManager.syncRule(any(), any(), any()) } returns mockk(relaxed = true)

        var bootstrapped = false
        var storedAlarm = alarm
        coEvery { repository.getAuthorityByAlarmId(940L) } answers { if (bootstrapped) bootstrappedAuth else null }
        coEvery { repository.getById(940L) } answers { storedAlarm }
        coEvery { repository.casUpdateNextTriggerInitialWithAuthority(any(), any(), any(), any(), any(), any()) } answers {
            storedAlarm = storedAlarm.copy(nextTriggerTime = secondArg())
            1
        }
        coEvery { repository.updateNextTrigger(940L, any()) } answers {
            storedAlarm = storedAlarm.copy(nextTriggerTime = secondArg())
        }
        coEvery { preferencesManager.getCurrentSettings() } returns AppSettings()
        every { preferencesManager.getCachedSettings() } returns AppSettings()
        coEvery { holidayRepository.isHoliday(any()) } returns false
        every { calculator.calculate(any<Alarm>()) } returns futureTrigger
        every { calculator.calculate(any<Alarm>(), any()) } returns futureTrigger
        every { localReader.readLocalEvidence() } returns com.sysadmindoc.alarmclock.data.local.LocalEvidenceReadResult.Success(
            localState = MarkerState.PRESENT_VALID,
            localId = "tok-boot-940",
            localCompletion = CompletionState.COMPLETE
        )
        coEvery { bootstrapAdapter.bootstrapAuthorityForAlarm(940L, "tok-boot-940") } answers {
            bootstrapped = true
            BootstrapDecision.RECONCILE
        }

        val am = mockk<AlarmManager>(relaxed = true) {
            every { canScheduleExactAlarms() } returns true
        }

        // Case 1: LEGACY_ONLY NEVER invokes bootstrapAdapter even when provider is non-null
        val legacyScheduler = AlarmScheduler(
            context = deContext,
            repository = repository,
            calculator = calculator,
            preferencesManager = preferencesManager,
            holidayRepository = holidayRepository,
            alarmIncidentRepository = incidentRepository,
            weatherRepository = weatherRepository
        ).apply {
            testAlarmManager = am
            testBootstrapAdapterProvider = { bootstrapAdapter }
            testLocalIncarnationEvidenceReaderProvider = { localReader }
        }
        legacyScheduler.schedule(alarm, requestWidgetUpdate = false)
        io.mockk.coVerify(exactly = 0) { bootstrapAdapter.bootstrapAuthorityForAlarm(any(), any()) }

        // Case 2: OWNER_EXCLUSIVE with originAuthority == null invokes bootstrapAdapter
        val ownerRoute = mockk<ProductionSchedulingRouteAuthority>()
        every { ownerRoute.isOwnerRouteActive() } returns true
        val mockOwner = mockk<ScheduleAuthorityOwner>()
        coEvery { mockOwner.executeOperation(eq(940L), eq("SET"), eq(futureTrigger), any(), any()) } returns OwnerResult.OsAccepted(1L, "act-940")

        val ownerScheduler = AlarmScheduler(
            context = deContext,
            repository = repository,
            calculator = calculator,
            preferencesManager = preferencesManager,
            holidayRepository = holidayRepository,
            alarmIncidentRepository = incidentRepository,
            weatherRepository = weatherRepository,
            scheduleAuthorityOwnerProvider = { mockOwner },
            routeAuthority = ownerRoute
        ).apply {
            testAlarmManager = am
            testBootstrapAdapterProvider = { bootstrapAdapter }
            testLocalIncarnationEvidenceReaderProvider = { localReader }
        }
        val outcome = ownerScheduler.schedule(alarm, requestWidgetUpdate = false)
        assertTrue("Expected Armed outcome after bootstrap, got $outcome", outcome is ScheduleOutcome.Armed)
        io.mockk.coVerify(exactly = 1) { bootstrapAdapter.bootstrapAuthorityForAlarm(940L, "tok-boot-940") }
    }
}
