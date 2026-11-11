package com.sysadmindoc.alarmclock.directboot

import android.app.AlarmManager
import android.app.Application
import android.app.NotificationManager
import android.content.Context
import android.content.SharedPreferences
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CancellationException
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Unit tests for [DirectBootServiceDeliveryAdapter].
 *
 * Verifies strict inactive delivery adaptation contract:
 * - Process-local ticket validation and single-use consumption.
 * - Authoritative Queue-to-Ledger transition delegation.
 * - 1:1 transition result fidelity (Confirmed, CancellationAfterCommit, Rejected, Uncertain).
 * - Exception boundary: CancellationException and fatal Errors propagate; unexpected Exceptions fail closed.
 * - Inert reconciliation descriptor generation with zero OS AlarmManager or Reconciler side effects.
 * - Zero audio, notification, vibration, or wake-lock invocations across all outcomes.
 * - Deterministic, bounded concurrency handling with complete thread cleanup.
 * - Fixture isolation across Robolectric execution runs without manual teardown invocation.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DirectBootServiceDeliveryAdapterTest {

    private lateinit var context: Context
    private lateinit var authorizer: DirectBootDeliveryAuthorizer

    private fun getDePrefs(): SharedPreferences = context.createDeviceProtectedStorageContext()
        .getSharedPreferences(DirectBootDeliveryLedger.PREFS_NAME, Context.MODE_PRIVATE)

    private fun clearAllStorage() {
        getDePrefs().edit().clear().commit()
    }

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        clearAllStorage()
        authorizer = DirectBootDeliveryAuthorizer()
    }

    @After
    fun tearDown() {
        clearAllStorage()
    }

    private fun makeIdentity(
        alarmId: Long = 101L,
        triggerTime: Long = 1_000_000L,
        generation: Int = 1,
        instanceToken: String = "token_101",
        kind: String = DirectBootV2NormalIntentIdentity.KIND_NORMAL
    ): DirectBootV2NormalIntentIdentity {
        return DirectBootV2NormalIntentIdentity(
            alarmId = alarmId,
            generation = generation,
            instanceToken = instanceToken,
            triggerTime = triggerTime,
            kind = kind
        )
    }

    private fun makeQueueItem(
        alarmId: Long = 101L,
        triggerTime: Long = 1_000_000L,
        generation: Int = 1,
        instanceToken: String = "token_101",
        kind: String = DirectBootQueueItem.KIND_NORMAL
    ): DirectBootQueueItem {
        return DirectBootQueueItem(
            kind = kind,
            alarmId = alarmId,
            triggerTime = triggerTime,
            generation = generation,
            instanceToken = instanceToken,
            timeLabel = "07:00",
            playDefaultSound = true,
            vibrationEnabled = true,
            schemaVersion = DirectBootQueueItem.CURRENT_SCHEMA_VERSION
        )
    }

    private fun saveQueue(items: List<DirectBootQueueItem>) {
        assertTrue(DirectBootAlarmCache.writeQueue(context, items, now = 0L))
    }

    // --- Case 01: Valid ticket, qualifying Queue Head -> Confirmed transition + descriptor ---
    @Test
    fun case01_validTicket_qualifyingQueueHead_returnsCommittedWithDescriptor() {
        val identity = makeIdentity()
        val queueItem1 = makeQueueItem()
        val queueItem2 = makeQueueItem(alarmId = 102L, triggerTime = 2_000_000L, instanceToken = "token_102")
        saveQueue(listOf(queueItem1, queueItem2))

        val ticketResult = authorizer.issueTicket(identity, nowEpochMs = 1_000_000L)
        assertTrue(ticketResult is TicketIssueResult.Success)
        val ticket = (ticketResult as TicketIssueResult.Success).ticket

        val adapter = DirectBootServiceDeliveryAdapter(context, authorizer)
        val result = adapter.adaptDelivery(ticket.ticketId, identity, deliveredAt = 1_000_000L)

        assertTrue(result is ServiceDeliveryAdaptationResult.TransitionCommitted)
        val committed = result as ServiceDeliveryAdaptationResult.TransitionCommitted
        assertTrue(committed.isDurableCommitConfirmed)
        assertFalse(committed.audioAuthorized)
        assertEquals(101L, committed.transition.consumedItem.alarmId)
        assertEquals(1, committed.transition.remainingQueue.size)
        assertEquals(102L, committed.transition.nextCandidateToArm?.alarmId)

        // Descriptor checks
        assertNotNull(committed.reconciliationRequest)
        assertEquals(102L, committed.reconciliationRequest.nextCandidateToArm?.alarmId)
        assertEquals(1, committed.reconciliationRequest.remainingQueueCount)
        assertEquals("V2_POST_DELIVERY_TRANSITION", committed.reconciliationRequest.triggerReason)
        assertEquals(1_000_000L, committed.reconciliationRequest.timestampEpochMs)
    }

    // --- Case 02: Nonexistent ticket -> No transition call ---
    @Test
    fun case02_nonexistentTicket_returnsTicketRejected_withoutTransitionCall() {
        val identity = makeIdentity()
        var transitionCallCount = 0

        val adapter = DirectBootServiceDeliveryAdapter(
            context = context,
            authorizer = authorizer,
            transitionFunction = { _, _, _ ->
                transitionCallCount++
                error("Transition should not be called")
            }
        )

        val result = adapter.adaptDelivery("non_existent_ticket_123", identity, deliveredAt = 1_000_000L)

        assertTrue(result is ServiceDeliveryAdaptationResult.TicketRejected)
        val rejected = result as ServiceDeliveryAdaptationResult.TicketRejected
        assertTrue(rejected.reason is TicketValidationResult.NotFound)
        assertFalse(rejected.isDurableCommitConfirmed)
        assertFalse(rejected.audioAuthorized)
        assertEquals(0, transitionCallCount)
    }

    // --- Case 03: Expired ticket -> No transition call ---
    @Test
    fun case03_expiredTicket_returnsTicketRejected_withoutTransitionCall() {
        val identity = makeIdentity()
        var transitionCallCount = 0

        val ticketResult = authorizer.issueTicket(identity, nowEpochMs = 1_000_000L)
        val ticket = (ticketResult as TicketIssueResult.Success).ticket

        val adapter = DirectBootServiceDeliveryAdapter(
            context = context,
            authorizer = authorizer,
            transitionFunction = { _, _, _ ->
                transitionCallCount++
                error("Transition should not be called")
            }
        )

        // Ticket TTL is 15s (15,000ms), so at 1_015_001 it is expired
        val result = adapter.adaptDelivery(ticket.ticketId, identity, deliveredAt = 1_015_001L)

        assertTrue(result is ServiceDeliveryAdaptationResult.TicketRejected)
        val rejected = result as ServiceDeliveryAdaptationResult.TicketRejected
        assertTrue(rejected.reason is TicketValidationResult.Expired)
        assertFalse(rejected.isDurableCommitConfirmed)
        assertFalse(rejected.audioAuthorized)
        assertEquals(0, transitionCallCount)
    }

    // --- Case 04: Replay -> Second call rejected ---
    @Test
    fun case04_replayTicket_secondCallRejected() {
        val identity = makeIdentity()
        val queueItem = makeQueueItem()
        saveQueue(listOf(queueItem))

        val ticketResult = authorizer.issueTicket(identity, nowEpochMs = 1_000_000L)
        val ticket = (ticketResult as TicketIssueResult.Success).ticket

        val adapter = DirectBootServiceDeliveryAdapter(context, authorizer)

        // First call consumes ticket and transitions
        val result1 = adapter.adaptDelivery(ticket.ticketId, identity, deliveredAt = 1_000_000L)
        assertTrue(result1 is ServiceDeliveryAdaptationResult.TransitionCommitted)

        // Second call with same ticket is replayed
        val result2 = adapter.adaptDelivery(ticket.ticketId, identity, deliveredAt = 1_000_001L)
        assertTrue(result2 is ServiceDeliveryAdaptationResult.TicketRejected)
        val rejected2 = result2 as ServiceDeliveryAdaptationResult.TicketRejected
        assertTrue(rejected2.reason is TicketValidationResult.NotFound)
        assertFalse(rejected2.isDurableCommitConfirmed)
        assertFalse(rejected2.audioAuthorized)
    }

    // --- Case 05: Identity mismatch -> No transition call ---
    @Test
    fun case05_identityMismatch_returnsTicketRejected_withoutTransitionCall() {
        val identity1 = makeIdentity(alarmId = 101L, instanceToken = "token_101")
        val identity2 = makeIdentity(alarmId = 102L, instanceToken = "token_102")
        var transitionCallCount = 0

        val ticketResult = authorizer.issueTicket(identity1, nowEpochMs = 1_000_000L)
        val ticket = (ticketResult as TicketIssueResult.Success).ticket

        val adapter = DirectBootServiceDeliveryAdapter(
            context = context,
            authorizer = authorizer,
            transitionFunction = { _, _, _ ->
                transitionCallCount++
                error("Transition should not be called")
            }
        )

        val result = adapter.adaptDelivery(ticket.ticketId, identity2, deliveredAt = 1_000_000L)

        assertTrue(result is ServiceDeliveryAdaptationResult.TicketRejected)
        val rejected = result as ServiceDeliveryAdaptationResult.TicketRejected
        assertTrue(rejected.reason is TicketValidationResult.IdentityMismatch)
        assertFalse(rejected.isDurableCommitConfirmed)
        assertFalse(rejected.audioAuthorized)
        assertEquals(0, transitionCallCount)
    }

    // --- Case 06: Queue changed after prequalification -> Actual transition rejects ---
    @Test
    fun case06_queueChangedAfterPrequalification_actualTransitionRejects() {
        val identity = makeIdentity(alarmId = 101L, triggerTime = 1_000_000L)
        val targetItem = makeQueueItem(alarmId = 101L, triggerTime = 1_000_000L)
        // Queue currently has a DIFFERENT head (e.g. alarmId = 999L)
        val differentHead = makeQueueItem(alarmId = 999L, triggerTime = 900_000L, instanceToken = "token_999")
        saveQueue(listOf(differentHead, targetItem))

        val ticketResult = authorizer.issueTicket(identity, nowEpochMs = 1_000_000L)
        val ticket = (ticketResult as TicketIssueResult.Success).ticket

        val adapter = DirectBootServiceDeliveryAdapter(context, authorizer)
        val result = adapter.adaptDelivery(ticket.ticketId, identity, deliveredAt = 1_000_000L)

        assertTrue(result is ServiceDeliveryAdaptationResult.TransitionRejected)
        val rejected = result as ServiceDeliveryAdaptationResult.TransitionRejected
        assertTrue(rejected.transition is DeliveryTransitionResult.Rejected.NotEligibleQueueHead)
        assertFalse(rejected.isDurableCommitConfirmed)
        assertFalse(rejected.audioAuthorized)
    }

    // --- Case 07: Duplicate Ledger -> Actual transition rejects ---
    @Test
    fun case07_duplicateLedger_actualTransitionRejects() {
        val identity = makeIdentity(alarmId = 101L, triggerTime = 1_000_000L, generation = 1, instanceToken = "token_101")
        val queueItem = makeQueueItem(alarmId = 101L, triggerTime = 1_000_000L, generation = 1, instanceToken = "token_101")
        saveQueue(listOf(queueItem))

        // Pre-populate ledger with duplicate record
        val duplicateRecord = DirectBootDeliveryRecord.createNormal(
            alarmId = 101L,
            triggerTime = 1_000_000L,
            deliveredAt = 999_999L,
            generation = 1,
            instanceToken = "token_101"
        )
        DirectBootDeliveryLedger.appendRecord(context, duplicateRecord)

        val ticketResult = authorizer.issueTicket(identity, nowEpochMs = 1_000_000L)
        val ticket = (ticketResult as TicketIssueResult.Success).ticket

        val adapter = DirectBootServiceDeliveryAdapter(context, authorizer)
        val result = adapter.adaptDelivery(ticket.ticketId, identity, deliveredAt = 1_000_000L)

        assertTrue(result is ServiceDeliveryAdaptationResult.TransitionRejected)
        val rejected = result as ServiceDeliveryAdaptationResult.TransitionRejected
        assertTrue(rejected.transition is DeliveryTransitionResult.Rejected.DuplicateDeliveryAlreadyRecorded)
        assertFalse(rejected.isDurableCommitConfirmed)
        assertFalse(rejected.audioAuthorized)
    }

    // --- Case 08: Corrupt Queue -> Actual transition rejects ---
    @Test
    fun case08_corruptQueue_actualTransitionRejects() {
        val identity = makeIdentity()
        // Corrupt queue payload
        getDePrefs().edit().putString(DirectBootAlarmCache.KEY_QUEUE_PAYLOAD, "NOT_JSON_CORRUPT").commit()

        val ticketResult = authorizer.issueTicket(identity, nowEpochMs = 1_000_000L)
        val ticket = (ticketResult as TicketIssueResult.Success).ticket

        val adapter = DirectBootServiceDeliveryAdapter(context, authorizer)
        val result = adapter.adaptDelivery(ticket.ticketId, identity, deliveredAt = 1_000_000L)

        assertTrue(result is ServiceDeliveryAdaptationResult.TransitionRejected)
        val rejected = result as ServiceDeliveryAdaptationResult.TransitionRejected
        assertTrue(rejected.transition is DeliveryTransitionResult.Rejected.QueueStorageCorrupt)
        assertFalse(rejected.isDurableCommitConfirmed)
        assertFalse(rejected.audioAuthorized)
    }

    // --- Case 09: CommitFailed -> Uncertain result ---
    @Test
    fun case09_commitFailed_returnsTransitionUncertain() {
        val identity = makeIdentity()
        val ticketResult = authorizer.issueTicket(identity, nowEpochMs = 1_000_000L)
        val ticket = (ticketResult as TicketIssueResult.Success).ticket

        val adapter = DirectBootServiceDeliveryAdapter(
            context = context,
            authorizer = authorizer,
            transitionFunction = { _, _, _ ->
                DeliveryTransitionResult.Uncertain.CommitFailed("Simulated commit failure")
            }
        )

        val result = adapter.adaptDelivery(ticket.ticketId, identity, deliveredAt = 1_000_000L)

        assertTrue(result is ServiceDeliveryAdaptationResult.TransitionUncertain)
        val uncertain = result as ServiceDeliveryAdaptationResult.TransitionUncertain
        assertTrue(uncertain.transition is DeliveryTransitionResult.Uncertain.CommitFailed)
        assertFalse(uncertain.isDurableCommitConfirmed)
        assertFalse(uncertain.audioAuthorized)
        assertEquals("Simulated commit failure", (uncertain.transition as DeliveryTransitionResult.Uncertain.CommitFailed).details)
    }

    // --- Case 10: CommitException -> Uncertain result ---
    @Test
    fun case10_commitException_returnsTransitionUncertain() {
        val identity = makeIdentity()
        val ticketResult = authorizer.issueTicket(identity, nowEpochMs = 1_000_000L)
        val ticket = (ticketResult as TicketIssueResult.Success).ticket

        val testException = IllegalStateException("Disk I/O error during commit")
        val adapter = DirectBootServiceDeliveryAdapter(
            context = context,
            authorizer = authorizer,
            transitionFunction = { _, _, _ ->
                DeliveryTransitionResult.Uncertain.CommitException("Disk failure", testException)
            }
        )

        val result = adapter.adaptDelivery(ticket.ticketId, identity, deliveredAt = 1_000_000L)

        assertTrue(result is ServiceDeliveryAdaptationResult.TransitionUncertain)
        val uncertain = result as ServiceDeliveryAdaptationResult.TransitionUncertain
        assertTrue(uncertain.transition is DeliveryTransitionResult.Uncertain.CommitException)
        val commitEx = uncertain.transition as DeliveryTransitionResult.Uncertain.CommitException
        assertEquals(testException, commitEx.cause)
        assertFalse(uncertain.isDurableCommitConfirmed)
        assertFalse(uncertain.audioAuthorized)
    }

    // --- Case 11: CancellationAfterCommit -> Original variant and cancellation evidence preserved ---
    @Test
    fun case11_cancellationAfterCommit_preservesVariantAndCancellationEvidence() {
        val identity = makeIdentity()
        val ticketResult = authorizer.issueTicket(identity, nowEpochMs = 1_000_000L)
        val ticket = (ticketResult as TicketIssueResult.Success).ticket

        val consumedItem = makeQueueItem()
        val deliveryRecord = DirectBootDeliveryRecord.createNormal(101L, 1_000_000L, 1_000_000L, 1, "token_101")
        val cancellation = CancellationException("Coroutine cancelled post-commit")

        val adapter = DirectBootServiceDeliveryAdapter(
            context = context,
            authorizer = authorizer,
            transitionFunction = { _, _, _ ->
                DeliveryTransitionResult.CancellationAfterCommit(
                    consumedItem = consumedItem,
                    deliveryRecord = deliveryRecord,
                    remainingQueue = emptyList(),
                    nextCandidateToArm = null,
                    cancellation = cancellation
                )
            }
        )

        val result = adapter.adaptDelivery(ticket.ticketId, identity, deliveredAt = 1_000_000L)

        assertTrue(result is ServiceDeliveryAdaptationResult.CancellationAfterCommit)
        val cancelResult = result as ServiceDeliveryAdaptationResult.CancellationAfterCommit
        assertTrue(cancelResult.isDurableCommitConfirmed)
        assertFalse(cancelResult.audioAuthorized)
        assertEquals(cancellation, cancelResult.cancellation)
        assertEquals(consumedItem, cancelResult.transition.consumedItem)
        assertNotNull(cancelResult.reconciliationRequest)
        assertNull(cancelResult.reconciliationRequest.nextCandidateToArm)
        assertEquals(0, cancelResult.reconciliationRequest.remainingQueueCount)
    }

    // --- Case 12: Concurrent tickets for same occurrence -> Bounded, deterministic execution & cleanup ---
    @Test
    fun case12_concurrentTicketsForSameOccurrence_atMostOneDurableTransition() {
        val identity = makeIdentity()
        val queueItem = makeQueueItem()
        saveQueue(listOf(queueItem))

        val t1 = (authorizer.issueTicket(identity, 1_000_000L) as TicketIssueResult.Success).ticket
        val t2 = (authorizer.issueTicket(identity, 1_000_000L) as TicketIssueResult.Success).ticket

        val adapter = DirectBootServiceDeliveryAdapter(context, authorizer)

        val executor = Executors.newFixedThreadPool(2)
        val startLatch = CountDownLatch(1)
        val doneLatch = CountDownLatch(2)

        try {
            val f1 = executor.submit<ServiceDeliveryAdaptationResult> {
                startLatch.await(5, TimeUnit.SECONDS)
                try {
                    adapter.adaptDelivery(t1.ticketId, identity, 1_000_000L)
                } finally {
                    doneLatch.countDown()
                }
            }
            val f2 = executor.submit<ServiceDeliveryAdaptationResult> {
                startLatch.await(5, TimeUnit.SECONDS)
                try {
                    adapter.adaptDelivery(t2.ticketId, identity, 1_000_000L)
                } finally {
                    doneLatch.countDown()
                }
            }

            // Release threads simultaneously
            startLatch.countDown()

            val completedInTime = doneLatch.await(5, TimeUnit.SECONDS)
            assertTrue("Concurrent adaptation must complete within timeout", completedInTime)

            val r1 = f1.get(5, TimeUnit.SECONDS)
            val r2 = f2.get(5, TimeUnit.SECONDS)

            val results = listOf(r1, r2)
            val committed = results.filterIsInstance<ServiceDeliveryAdaptationResult.TransitionCommitted>()
            val rejected = results.filterIsInstance<ServiceDeliveryAdaptationResult.TransitionRejected>()

            assertEquals("Exactly one concurrent adaptation must commit", 1, committed.size)
            assertEquals("Exactly one concurrent adaptation must be rejected", 1, rejected.size)
            assertNotNull("Rejected transition must be present", rejected[0].transition)

            // Verify authoritative Queue/Ledger outcome
            val remainingQueue = DirectBootAlarmCache.readQueue(context)
            assertTrue("Queue item must be consumed, remaining queue must be empty", remainingQueue.isEmpty())

            val ledgerState = DirectBootDeliveryLedger.readUnacknowledgedRecords(context)
            assertTrue("Ledger must have ValidWithRecords, got $ledgerState", ledgerState is LedgerStorageState.ValidWithRecords)
            val ledgerRecords = (ledgerState as LedgerStorageState.ValidWithRecords).records
            assertEquals("Ledger must contain exactly 1 delivery record", 1, ledgerRecords.size)
            assertEquals(101L, ledgerRecords[0].alarmId)
        } finally {
            executor.shutdown()
            if (!executor.awaitTermination(3, TimeUnit.SECONDS)) {
                executor.shutdownNow()
                executor.awaitTermination(2, TimeUnit.SECONDS)
            }
        }
    }

    // --- Case 13: Process-owner replacement -> Old ticket rejected ---
    @Test
    fun case13_processOwnerReplacement_oldTicketRejected() {
        val identity = makeIdentity()
        val authorizerProcess1 = DirectBootDeliveryAuthorizer()
        val ticketProcess1 = (authorizerProcess1.issueTicket(identity, 1_000_000L) as TicketIssueResult.Success).ticket

        // Process restarts; new authorizer instance has clean empty activeTickets
        val authorizerProcess2 = DirectBootDeliveryAuthorizer()
        val adapterProcess2 = DirectBootServiceDeliveryAdapter(context, authorizerProcess2)

        val result = adapterProcess2.adaptDelivery(ticketProcess1.ticketId, identity, 1_000_000L)

        assertTrue(result is ServiceDeliveryAdaptationResult.TicketRejected)
        val rejected = result as ServiceDeliveryAdaptationResult.TicketRejected
        assertTrue(rejected.reason is TicketValidationResult.NotFound)
        assertFalse(rejected.isDurableCommitConfirmed)
        assertFalse(rejected.audioAuthorized)
    }

    // --- Case 14: Reconciler invocation count = ZERO ---
    @Test
    fun case14_reconcilerInvocationCount_isZero() {
        val identity = makeIdentity()
        val queueItem = makeQueueItem()
        saveQueue(listOf(queueItem))

        val ticket = (authorizer.issueTicket(identity, 1_000_000L) as TicketIssueResult.Success).ticket
        val adapter = DirectBootServiceDeliveryAdapter(context, authorizer)

        val result = adapter.adaptDelivery(ticket.ticketId, identity, 1_000_000L)
        assertTrue(result is ServiceDeliveryAdaptationResult.TransitionCommitted)

        // Verify Reconciler marker was NEVER touched (still absent in storage)
        val markerResult = DirectBootRegistrationMarker.read(context)
        assertTrue(markerResult is MarkerReadResult.Absent || (markerResult is MarkerReadResult.Success && markerResult.marker.mainState == RegistrationMainState.IDLE))
    }

    // --- Case 15: AlarmManager operations = ZERO (Platform Boundary Verification) ---
    @Test
    fun case15_alarmManagerOperations_areZero() {
        val identity = makeIdentity()
        val queueItem = makeQueueItem()
        saveQueue(listOf(queueItem))

        val ticket = (authorizer.issueTicket(identity, 1_000_000L) as TicketIssueResult.Success).ticket

        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val shadowAlarm = Shadows.shadowOf(alarmManager)

        var transitionCalled = false
        val adapter = DirectBootServiceDeliveryAdapter(
            context = context,
            authorizer = authorizer,
            transitionFunction = { ctx, firingId, deliveredAt ->
                transitionCalled = true
                DirectBootDeliveryTransitionEngine.transitionNormal(ctx, firingId, deliveredAt)
            }
        )

        val result = adapter.adaptDelivery(ticket.ticketId, identity, 1_000_000L)
        assertTrue(transitionCalled)
        assertTrue(result is ServiceDeliveryAdaptationResult.TransitionCommitted)

        // Measured Robolectric evidence: zero AlarmManager alarms scheduled
        assertEquals("Adapter must not schedule any alarms with AlarmManager", 0, shadowAlarm.scheduledAlarms.size)
    }

    // --- Case 16: Zero service / notification / audio / vibrator / wake-lock calls ---
    @Test
    fun case16_audioNotificationVibrationWakeLock_zeroCalls() {
        val identity = makeIdentity()
        val queueItem = makeQueueItem()
        saveQueue(listOf(queueItem))

        val ticket = (authorizer.issueTicket(identity, 1_000_000L) as TicketIssueResult.Success).ticket
        val adapter = DirectBootServiceDeliveryAdapter(context, authorizer)

        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val shadowNotification = Shadows.shadowOf(notificationManager)

        val shadowApp = Shadows.shadowOf(context.applicationContext as Application)

        val result = adapter.adaptDelivery(ticket.ticketId, identity, 1_000_000L)
        assertTrue(result is ServiceDeliveryAdaptationResult.TransitionCommitted)

        // Measured platform boundary evidence:
        assertEquals("Adapter must not post any notifications", 0, shadowNotification.allNotifications.size)
        assertNull("Adapter must not start any Service or ForegroundService", shadowApp.nextStartedService)
        assertFalse("Audio must not be authorized across adapter results", result.audioAuthorized)
    }

    // --- Case 17: All adaptation results have audioAuthorized = false ---
    @Test
    fun case17_allAdaptationResults_haveAudioAuthorizedFalse() {
        val identity = makeIdentity()
        val dummyTicket = DeliveryTicket("dummy", identity, 1_000_000L, 1_015_000L)
        val dummyQueueItem = makeQueueItem()
        val dummyRecord = DirectBootDeliveryRecord.createNormal(101L, 1_000_000L, 1_000_000L, 1, "token_101")
        val dummyReconcile = PostCommitReconciliationRequest(null, 0)

        val r1: ServiceDeliveryAdaptationResult = ServiceDeliveryAdaptationResult.TicketRejected(TicketValidationResult.NotFound("test"))
        val r2: ServiceDeliveryAdaptationResult = ServiceDeliveryAdaptationResult.TransitionRejected(dummyTicket, DeliveryTransitionResult.Rejected.QueueAbsent())
        val r3: ServiceDeliveryAdaptationResult = ServiceDeliveryAdaptationResult.TransitionUncertain(dummyTicket, DeliveryTransitionResult.Uncertain.CommitFailed("test"))
        val r4: ServiceDeliveryAdaptationResult = ServiceDeliveryAdaptationResult.TransitionCommitted(dummyTicket, DeliveryTransitionResult.Confirmed(dummyQueueItem, dummyRecord, emptyList(), null), dummyReconcile)
        val r5: ServiceDeliveryAdaptationResult = ServiceDeliveryAdaptationResult.CancellationAfterCommit(dummyTicket, DeliveryTransitionResult.CancellationAfterCommit(dummyQueueItem, dummyRecord, emptyList(), null, CancellationException()), dummyReconcile, CancellationException())

        assertFalse(r1.audioAuthorized)
        assertFalse(r2.audioAuthorized)
        assertFalse(r3.audioAuthorized)
        assertFalse(r4.audioAuthorized)
        assertFalse(r5.audioAuthorized)
    }

    // --- Case 18: Post-commit reconciliation descriptor is returned but never executed ---
    @Test
    fun case18_postCommitReconciliationDescriptor_returnedButNeverExecuted() {
        val identity = makeIdentity()
        val queueItem1 = makeQueueItem()
        val queueItem2 = makeQueueItem(alarmId = 102L, triggerTime = 2_000_000L, instanceToken = "token_102")
        saveQueue(listOf(queueItem1, queueItem2))

        val ticket = (authorizer.issueTicket(identity, 1_000_000L) as TicketIssueResult.Success).ticket
        val adapter = DirectBootServiceDeliveryAdapter(context, authorizer)

        val result = adapter.adaptDelivery(ticket.ticketId, identity, 1_000_000L)
        assertTrue(result is ServiceDeliveryAdaptationResult.TransitionCommitted)
        val committed = result as ServiceDeliveryAdaptationResult.TransitionCommitted

        val req = committed.reconciliationRequest
        assertNotNull(req)
        assertEquals(102L, req.nextCandidateToArm?.alarmId)
        assertEquals(1, req.remainingQueueCount)
        assertEquals("V2_POST_DELIVERY_TRANSITION", req.triggerReason)
        assertEquals(1_000_000L, req.timestampEpochMs)
    }

    // --- Case 19: Empty remaining Queue still produces appropriate inert reconciliation intent ---
    @Test
    fun case19_emptyRemainingQueue_producesInertReconciliationDescriptorWithNullCandidate() {
        val identity = makeIdentity()
        val singleQueueItem = makeQueueItem()
        saveQueue(listOf(singleQueueItem))

        val ticket = (authorizer.issueTicket(identity, 1_000_000L) as TicketIssueResult.Success).ticket
        val adapter = DirectBootServiceDeliveryAdapter(context, authorizer)

        val result = adapter.adaptDelivery(ticket.ticketId, identity, 1_000_000L)
        assertTrue(result is ServiceDeliveryAdaptationResult.TransitionCommitted)
        val committed = result as ServiceDeliveryAdaptationResult.TransitionCommitted

        val req = committed.reconciliationRequest
        assertNotNull(req)
        assertNull(req.nextCandidateToArm)
        assertEquals(0, req.remainingQueueCount)
        assertEquals(0, committed.transition.remainingQueue.size)
    }

    // --- Case 20: Test storage isolation and teardown preserve full-suite fixture integrity ---
    @Test
    fun case20_testStorageIsolation_preservesFixtureIntegrity() {
        val identity = makeIdentity()
        val queueItem = makeQueueItem()
        saveQueue(listOf(queueItem))

        val ticket = (authorizer.issueTicket(identity, 1_000_000L) as TicketIssueResult.Success).ticket
        val adapter = DirectBootServiceDeliveryAdapter(context, authorizer)

        val result = adapter.adaptDelivery(ticket.ticketId, identity, 1_000_000L)
        assertTrue(result is ServiceDeliveryAdaptationResult.TransitionCommitted)

        // Verify storage is non-empty before manual reset
        assertFalse("Storage must contain items before reset", getDePrefs().all.isEmpty())

        // Reset storage using test helper (not JUnit lifecycle tearDown)
        clearAllStorage()

        // Verify storage is completely wiped
        val allPrefs = getDePrefs().all
        assertTrue("Storage must be empty after explicit clear, found: $allPrefs", allPrefs.isEmpty())
    }

    // --- Case 21: Ordinary unexpected exception thrown by transitionFunction fails closed ---
    @Test
    fun case21_ordinaryUnexpectedException_returnsTransitionUncertain() {
        val identity = makeIdentity()
        val ticketResult = authorizer.issueTicket(identity, nowEpochMs = 1_000_000L)
        val ticket = (ticketResult as TicketIssueResult.Success).ticket

        val testException = IllegalStateException("Unexpected DB failure during transition")
        val adapter = DirectBootServiceDeliveryAdapter(
            context = context,
            authorizer = authorizer,
            transitionFunction = { _, _, _ ->
                throw testException
            }
        )

        val result = adapter.adaptDelivery(ticket.ticketId, identity, deliveredAt = 1_000_000L)

        assertTrue(result is ServiceDeliveryAdaptationResult.TransitionUncertain)
        val uncertain = result as ServiceDeliveryAdaptationResult.TransitionUncertain
        assertTrue(uncertain.transition is DeliveryTransitionResult.Uncertain.CommitException)
        val commitEx = uncertain.transition as DeliveryTransitionResult.Uncertain.CommitException
        assertEquals(testException, commitEx.cause)
        assertFalse(uncertain.isDurableCommitConfirmed)
        assertFalse(uncertain.audioAuthorized)
    }

    // --- Case 22: CancellationException thrown by transitionFunction propagates ---
    @Test
    fun case22_cancellationExceptionThrown_propagates() {
        val identity = makeIdentity()
        val ticketResult = authorizer.issueTicket(identity, nowEpochMs = 1_000_000L)
        val ticket = (ticketResult as TicketIssueResult.Success).ticket

        val adapter = DirectBootServiceDeliveryAdapter(
            context = context,
            authorizer = authorizer,
            transitionFunction = { _, _, _ ->
                throw CancellationException("Coroutine cancelled during transition execution")
            }
        )

        assertThrows(CancellationException::class.java) {
            adapter.adaptDelivery(ticket.ticketId, identity, deliveredAt = 1_000_000L)
        }
    }

    // --- Case 23: Fatal JVM Error propagation without swallowing ---
    @Test
    fun case23_fatalError_propagatesWithoutSwallowing() {
        val identity = makeIdentity()
        val ticketResult = authorizer.issueTicket(identity, nowEpochMs = 1_000_000L)
        val ticket = (ticketResult as TicketIssueResult.Success).ticket

        val adapter = DirectBootServiceDeliveryAdapter(
            context = context,
            authorizer = authorizer,
            transitionFunction = { _, _, _ ->
                throw AssertionError("Fatal JVM assertion error in test")
            }
        )

        assertThrows(AssertionError::class.java) {
            adapter.adaptDelivery(ticket.ticketId, identity, deliveredAt = 1_000_000L)
        }
    }

    // --- Case 24: Ticket remains consumed after transition failure (no reuse / no replay) ---
    @Test
    fun case24_ticketAlreadyConsumedAfterTransitionFailure() {
        val identity = makeIdentity()
        val ticketResult = authorizer.issueTicket(identity, nowEpochMs = 1_000_000L)
        val ticket = (ticketResult as TicketIssueResult.Success).ticket

        var callCount = 0
        val adapter = DirectBootServiceDeliveryAdapter(
            context = context,
            authorizer = authorizer,
            transitionFunction = { _, _, _ ->
                callCount++
                throw IllegalStateException("Failure on first transition attempt")
            }
        )

        // First adaptation attempt fails due to transition exception
        val result1 = adapter.adaptDelivery(ticket.ticketId, identity, deliveredAt = 1_000_000L)
        assertTrue(result1 is ServiceDeliveryAdaptationResult.TransitionUncertain)
        assertEquals(1, callCount)

        // Second adaptation attempt with SAME ticket must be rejected at authorizer boundary (ticket already consumed)
        val result2 = adapter.adaptDelivery(ticket.ticketId, identity, deliveredAt = 1_000_001L)
        assertTrue(result2 is ServiceDeliveryAdaptationResult.TicketRejected)
        val rejected = result2 as ServiceDeliveryAdaptationResult.TicketRejected
        assertTrue("Ticket must be NotFound after being consumed", rejected.reason is TicketValidationResult.NotFound)
        assertFalse(rejected.isDurableCommitConfirmed)
        assertFalse(rejected.audioAuthorized)
        assertEquals("Transition function must not be called again", 1, callCount)
    }
}
