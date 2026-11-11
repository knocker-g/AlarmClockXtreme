package com.sysadmindoc.alarmclock.directboot

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
class DirectBootDeliveryAuthorizerTest {

    private lateinit var context: Context
    private lateinit var authorizer: DirectBootDeliveryAuthorizer
    private lateinit var prefs: SharedPreferences

    private val testAlarmId = 101L
    private val testGeneration = 1
    private val testToken = "token_alpha"
    private val testTriggerTime = 1_700_000_000_000L

    private fun sampleIdentity(
        alarmId: Long = testAlarmId,
        generation: Int = testGeneration,
        instanceToken: String = testToken,
        triggerTime: Long = testTriggerTime
    ): DirectBootV2NormalIntentIdentity {
        return DirectBootV2NormalIntentIdentity(
            alarmId = alarmId,
            generation = generation,
            instanceToken = instanceToken,
            triggerTime = triggerTime
        )
    }

    private fun sampleQueueItem(
        alarmId: Long = testAlarmId,
        generation: Int = testGeneration,
        instanceToken: String = testToken,
        triggerTime: Long = testTriggerTime
    ): DirectBootQueueItem {
        return DirectBootQueueItem(
            kind = DirectBootQueueItem.KIND_NORMAL,
            alarmId = alarmId,
            triggerTime = triggerTime,
            generation = generation,
            instanceToken = instanceToken,
            timeLabel = "07:00",
            playDefaultSound = true,
            vibrationEnabled = true
        )
    }

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        authorizer = DirectBootDeliveryAuthorizer()
        prefs = context.createDeviceProtectedStorageContext()
            .getSharedPreferences(DirectBootDeliveryLedger.PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
    }

    @After
    fun tearDown() {
        prefs.edit().clear().commit()
    }

    // 1. Valid V2 identity -> Eligible
    @Test
    fun test01_validV2Identity_preQualifiesEligible() {
        val item = sampleQueueItem()
        assertTrue(DirectBootAlarmCache.writeQueue(context, listOf(item), now = 0L))

        val identity = sampleIdentity()
        val intent = identity.createIntent(context)

        val result = authorizer.preQualify(context, intent)
        assertTrue("Expected Eligible, got $result", result is DeliveryPrequalificationResult.Eligible)
        assertEquals(PrequalificationStatus.ELIGIBLE, result.status)

        val eligible = result as DeliveryPrequalificationResult.Eligible
        assertEquals(identity, eligible.identity)
        assertEquals(item.alarmId, eligible.headItem.alarmId)
        assertEquals(item.generation, eligible.headItem.generation)
        assertEquals(item.instanceToken, eligible.headItem.instanceToken)
    }

    // 2. Invalid action -> InvalidIdentity
    @Test
    fun test02_invalidAction_returnsInvalidIdentity() {
        val identity = sampleIdentity()
        val intent = identity.createIntent(context).apply {
            action = Intent.ACTION_BOOT_COMPLETED
        }

        val result = authorizer.preQualify(context, intent)
        assertTrue("Expected InvalidIdentity, got $result", result is DeliveryPrequalificationResult.InvalidIdentity)
        assertEquals(PrequalificationStatus.INVALID_IDENTITY, result.status)
        assertTrue((result as DeliveryPrequalificationResult.InvalidIdentity).reason.contains("Invalid Intent action"))
    }

    // 3. Invalid URI -> InvalidIdentity
    @Test
    fun test03_invalidUri_returnsInvalidIdentity() {
        val identity = sampleIdentity()
        val intent = identity.createIntent(context).apply {
            data = Uri.parse("https://malicious.example.com/alarm/101")
        }

        val result = authorizer.preQualify(context, intent)
        assertTrue("Expected InvalidIdentity, got $result", result is DeliveryPrequalificationResult.InvalidIdentity)
        assertEquals(PrequalificationStatus.INVALID_IDENTITY, result.status)
    }

    // 4. Tampered digest -> InvalidIdentity
    @Test
    fun test04_tamperedDigest_returnsInvalidIdentity() {
        val identity = sampleIdentity()
        val intent = identity.createIntent(context).apply {
            putExtra(DirectBootV2NormalIntentIdentity.EXTRA_IDENTITY_DIGEST, "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef")
        }

        val result = authorizer.preQualify(context, intent)
        assertTrue("Expected InvalidIdentity, got $result", result is DeliveryPrequalificationResult.InvalidIdentity)
        assertEquals(PrequalificationStatus.INVALID_IDENTITY, result.status)
        assertTrue((result as DeliveryPrequalificationResult.InvalidIdentity).reason.contains("does not match"))
    }

    // 5. Identity substitution -> InvalidIdentity / NotQueueHead
    @Test
    fun test05_identitySubstitution_returnsInvalidIdentityOrNotQueueHead() {
        val identity = sampleIdentity()
        // Tamper alarmId extra while keeping original URI with original alarmId
        val intent = identity.createIntent(context).apply {
            putExtra(DirectBootV2NormalIntentIdentity.EXTRA_ALARM_ID, 999L)
        }

        val result = authorizer.preQualify(context, intent)
        assertTrue("Expected InvalidIdentity due to URI/extra mismatch, got $result", result is DeliveryPrequalificationResult.InvalidIdentity)
        assertEquals(PrequalificationStatus.INVALID_IDENTITY, result.status)
    }

    // 6. Queue head mismatch -> NotQueueHead
    @Test
    fun test06_queueHeadMismatch_returnsNotQueueHead() {
        val queueItem = sampleQueueItem(alarmId = 200L, instanceToken = "token_head")
        assertTrue(DirectBootAlarmCache.writeQueue(context, listOf(queueItem), now = 0L))

        val intentIdentity = sampleIdentity(alarmId = 101L, instanceToken = "token_different")
        val intent = intentIdentity.createIntent(context)

        val result = authorizer.preQualify(context, intent)
        assertTrue("Expected NotQueueHead, got $result", result is DeliveryPrequalificationResult.NotQueueHead)
        assertEquals(PrequalificationStatus.NOT_QUEUE_HEAD, result.status)

        val notHead = result as DeliveryPrequalificationResult.NotQueueHead
        assertEquals(intentIdentity, notHead.identity)
        assertNotNull(notHead.headItem)
        assertEquals(200L, notHead.headItem?.alarmId)
    }

    // 7. Duplicate Ledger record -> DuplicateDelivery
    @Test
    fun test07_duplicateLedgerRecord_returnsDuplicateDelivery() {
        val item = sampleQueueItem()
        assertTrue(DirectBootAlarmCache.writeQueue(context, listOf(item), now = 0L))

        val existingRecord = DirectBootDeliveryRecord.createNormal(
            alarmId = testAlarmId,
            triggerTime = testTriggerTime,
            deliveredAt = testTriggerTime + 500,
            generation = testGeneration,
            instanceToken = testToken
        )
        val appendResult = DirectBootDeliveryLedger.appendRecord(context, existingRecord)
        assertTrue("Expected record appended, got $appendResult", appendResult is DeliveryAppendResult.Appended)

        val identity = sampleIdentity()
        val intent = identity.createIntent(context)

        val result = authorizer.preQualify(context, intent)
        assertTrue("Expected DuplicateDelivery, got $result", result is DeliveryPrequalificationResult.DuplicateDelivery)
        assertEquals(PrequalificationStatus.DUPLICATE_DELIVERY, result.status)
        assertEquals(existingRecord.recordId, (result as DeliveryPrequalificationResult.DuplicateDelivery).recordId)
    }

    // 8. Ledger at capacity (16) -> CapacityExhausted
    @Test
    fun test08_ledgerCapacityExhausted_returnsCapacityExhausted() {
        // Fill ledger to MAX_CAPACITY (16)
        for (i in 1..DirectBootDeliveryLedger.MAX_CAPACITY) {
            val record = DirectBootDeliveryRecord.createNormal(
                alarmId = 500L + i,
                triggerTime = testTriggerTime + (i * 1000),
                deliveredAt = testTriggerTime + (i * 1000) + 100,
                generation = 1,
                instanceToken = "token_$i"
            )
            val appendResult = DirectBootDeliveryLedger.appendRecord(context, record)
            assertTrue(appendResult is DeliveryAppendResult.Appended)
        }

        val item = sampleQueueItem(alarmId = testAlarmId)
        assertTrue(DirectBootAlarmCache.writeQueue(context, listOf(item), now = 0L))

        val identity = sampleIdentity(alarmId = testAlarmId)
        val intent = identity.createIntent(context)

        val result = authorizer.preQualify(context, intent)
        assertTrue("Expected CapacityExhausted, got $result", result is DeliveryPrequalificationResult.CapacityExhausted)
        assertEquals(PrequalificationStatus.CAPACITY_EXHAUSTED, result.status)
    }

    // 9. Corrupt Queue -> StorageUnverifiable
    @Test
    fun test09_corruptQueue_returnsStorageUnverifiable() {
        prefs.edit().putString(DirectBootAlarmCache.KEY_QUEUE_PAYLOAD, "{ malformed json").commit()

        val identity = sampleIdentity()
        val intent = identity.createIntent(context)

        val result = authorizer.preQualify(context, intent)
        assertTrue("Expected StorageUnverifiable, got $result", result is DeliveryPrequalificationResult.StorageUnverifiable)
        assertEquals(PrequalificationStatus.STORAGE_UNVERIFIABLE, result.status)
        assertTrue((result as DeliveryPrequalificationResult.StorageUnverifiable).reason.contains("Queue storage is corrupt"))
    }

    // 10. Unsupported Queue schema -> StorageUnverifiable
    @Test
    fun test10_unsupportedQueueSchema_returnsStorageUnverifiable() {
        prefs.edit().putString(
            DirectBootAlarmCache.KEY_QUEUE_PAYLOAD,
            """{"schemaVersion":999,"items":[]}"""
        ).commit()

        val identity = sampleIdentity()
        val intent = identity.createIntent(context)

        val result = authorizer.preQualify(context, intent)
        assertTrue("Expected StorageUnverifiable, got $result", result is DeliveryPrequalificationResult.StorageUnverifiable)
        assertEquals(PrequalificationStatus.STORAGE_UNVERIFIABLE, result.status)
        assertTrue((result as DeliveryPrequalificationResult.StorageUnverifiable).reason.contains("unsupported schema"))
    }

    // 11. Corrupt Ledger -> StorageUnverifiable
    @Test
    fun test11_corruptLedger_returnsStorageUnverifiable() {
        val item = sampleQueueItem()
        assertTrue(DirectBootAlarmCache.writeQueue(context, listOf(item), now = 0L))
        prefs.edit().putString(DirectBootDeliveryLedger.KEY_DELIVERY_LEDGER_PAYLOAD, "{ corrupt ledger").commit()

        val identity = sampleIdentity()
        val intent = identity.createIntent(context)

        val result = authorizer.preQualify(context, intent)
        assertTrue("Expected StorageUnverifiable, got $result", result is DeliveryPrequalificationResult.StorageUnverifiable)
        assertEquals(PrequalificationStatus.STORAGE_UNVERIFIABLE, result.status)
        assertTrue((result as DeliveryPrequalificationResult.StorageUnverifiable).reason.contains("Delivery ledger is corrupt"))
    }

    // 12. Read-only verification (proves zero SharedPreferences edits during preQualify)
    @Test
    fun test12_readOnlyVerification_provesZeroSharedPreferencesEdits() {
        val item = sampleQueueItem()
        assertTrue(DirectBootAlarmCache.writeQueue(context, listOf(item), now = 0L))

        val prefsBefore = prefs.all.toMap()
        val identity = sampleIdentity()
        val intent = identity.createIntent(context)

        val result = authorizer.preQualify(context, intent)
        assertTrue(result is DeliveryPrequalificationResult.Eligible)

        val prefsAfter = prefs.all.toMap()
        assertEquals("Prequalification must not mutate SharedPreferences", prefsBefore, prefsAfter)
    }

    // 13. Ticket issued and consumed once -> Valid
    @Test
    fun test13_ticketIssuedAndConsumedOnce_returnsValid() {
        val identity = sampleIdentity()
        val now = 1_000_000L

        val issueResult = authorizer.issueTicket(identity, nowEpochMs = now)
        assertTrue("Expected Success, got $issueResult", issueResult is TicketIssueResult.Success)

        val ticket = (issueResult as TicketIssueResult.Success).ticket
        assertEquals(identity, ticket.identity)
        assertEquals(now, ticket.issuedAtEpochMs)
        assertEquals(now + DirectBootDeliveryAuthorizer.DEFAULT_TICKET_TTL_MS, ticket.expiresAtEpochMs)

        val validationResult = authorizer.validateAndConsumeTicket(
            ticketId = ticket.ticketId,
            expectedIdentity = identity,
            nowEpochMs = now + 1_000L
        )
        assertTrue("Expected Valid, got $validationResult", validationResult is TicketValidationResult.Valid)
        assertEquals(ticket, (validationResult as TicketValidationResult.Valid).ticket)
    }

    // 14. Double consumption -> NotFound
    @Test
    fun test14_doubleConsumption_returnsNotFound() {
        val identity = sampleIdentity()
        val now = 1_000_000L

        val issueResult = authorizer.issueTicket(identity, nowEpochMs = now)
        val ticket = (issueResult as TicketIssueResult.Success).ticket

        val firstValidation = authorizer.validateAndConsumeTicket(ticket.ticketId, identity, nowEpochMs = now + 100)
        assertTrue(firstValidation is TicketValidationResult.Valid)

        val secondValidation = authorizer.validateAndConsumeTicket(ticket.ticketId, identity, nowEpochMs = now + 200)
        assertTrue("Expected NotFound on second consumption, got $secondValidation", secondValidation is TicketValidationResult.NotFound)
    }

    // 15. Concurrent consumption -> exactly one Valid, all others NotFound
    @Test
    fun test15_concurrentConsumption_exactlyOneValidAllOthersNotFound() {
        val identity = sampleIdentity()
        val now = 1_000_000L

        val issueResult = authorizer.issueTicket(identity, nowEpochMs = now)
        val ticket = (issueResult as TicketIssueResult.Success).ticket

        val threadCount = 10
        val executor = Executors.newFixedThreadPool(threadCount)
        val startLatch = CountDownLatch(1)
        val doneLatch = CountDownLatch(threadCount)

        val validCount = AtomicInteger(0)
        val notFoundCount = AtomicInteger(0)

        for (i in 0 until threadCount) {
            executor.submit {
                try {
                    startLatch.await()
                    val res = authorizer.validateAndConsumeTicket(ticket.ticketId, identity, nowEpochMs = now + 50)
                    if (res is TicketValidationResult.Valid) {
                        validCount.incrementAndGet()
                    } else if (res is TicketValidationResult.NotFound) {
                        notFoundCount.incrementAndGet()
                    }
                } finally {
                    doneLatch.countDown()
                }
            }
        }

        startLatch.countDown()
        doneLatch.await()
        executor.shutdown()

        assertEquals("Exactly one thread must consume ticket", 1, validCount.get())
        assertEquals("Remaining threads must receive NotFound", threadCount - 1, notFoundCount.get())
    }

    // 16. Ticket expiration -> Expired
    @Test
    fun test16_ticketExpiration_returnsExpired() {
        val identity = sampleIdentity()
        val now = 1_000_000L

        val issueResult = authorizer.issueTicket(identity, nowEpochMs = now)
        val ticket = (issueResult as TicketIssueResult.Success).ticket

        // Validate after TTL expired (now + 15_001ms)
        val expiredInstant = now + DirectBootDeliveryAuthorizer.DEFAULT_TICKET_TTL_MS + 1L
        val validationResult = authorizer.validateAndConsumeTicket(ticket.ticketId, identity, nowEpochMs = expiredInstant)

        assertTrue("Expected Expired, got $validationResult", validationResult is TicketValidationResult.Expired)
        val expired = validationResult as TicketValidationResult.Expired
        assertEquals(ticket, expired.ticket)

        // Ensure ticket was removed during expired evaluation and is not reusable
        val retry = authorizer.validateAndConsumeTicket(ticket.ticketId, identity, nowEpochMs = expiredInstant + 10)
        assertTrue("Subsequent check must return NotFound", retry is TicketValidationResult.NotFound)
    }

    // 17. Clock rollback on issue & consume -> ClockRollback
    @Test
    fun test17_clockRollback_onIssueAndConsume_returnsClockRollback() {
        val identity = sampleIdentity()

        // Monotonic instant 50_000ms
        val issueResult1 = authorizer.issueTicket(identity, nowEpochMs = 50_000L)
        assertTrue(issueResult1 is TicketIssueResult.Success)
        val ticket = (issueResult1 as TicketIssueResult.Success).ticket

        // Clock moves backward to 40_000ms during issue
        val issueResult2 = authorizer.issueTicket(identity, nowEpochMs = 40_000L)
        assertTrue("Expected ClockRollback on issue, got $issueResult2", issueResult2 is TicketIssueResult.ClockRollback)

        // Advance to 60_000ms, then validate at 55_000ms (rollback)
        val advanceIssue = authorizer.issueTicket(identity, nowEpochMs = 60_000L)
        assertTrue(advanceIssue is TicketIssueResult.Success)

        val validateRollback = authorizer.validateAndConsumeTicket(ticket.ticketId, identity, nowEpochMs = 55_000L)
        assertTrue("Expected ClockRollback on validation, got $validateRollback", validateRollback is TicketValidationResult.ClockRollback)
    }

    // 18. Ticket capacity exhaustion -> CapacityExhausted
    @Test
    fun test18_ticketCapacityExhaustion_returnsCapacityExhausted() {
        val smallAuthorizer = DirectBootDeliveryAuthorizer(maxActiveTickets = 2)
        val identity = sampleIdentity()
        val now = 10_000L

        val t1 = smallAuthorizer.issueTicket(identity, nowEpochMs = now)
        assertTrue(t1 is TicketIssueResult.Success)

        val t2 = smallAuthorizer.issueTicket(identity, nowEpochMs = now)
        assertTrue(t2 is TicketIssueResult.Success)

        val t3 = smallAuthorizer.issueTicket(identity, nowEpochMs = now)
        assertTrue("Expected CapacityExhausted when limit 2 reached, got $t3", t3 is TicketIssueResult.CapacityExhausted)
    }

    // 19. Process-owner replacement simulation -> fresh authorizer returns NotFound
    @Test
    fun test19_processOwnerReplacementSimulation_freshAuthorizerReturnsNotFound() {
        val initialProcessAuthorizer = DirectBootDeliveryAuthorizer()
        val identity = sampleIdentity()
        val now = 10_000L

        val issueResult = initialProcessAuthorizer.issueTicket(identity, nowEpochMs = now)
        val ticket = (issueResult as TicketIssueResult.Success).ticket

        // Simulate new process instance starting up
        val freshProcessAuthorizer = DirectBootDeliveryAuthorizer()
        val validationResult = freshProcessAuthorizer.validateAndConsumeTicket(
            ticketId = ticket.ticketId,
            expectedIdentity = identity,
            nowEpochMs = now + 500L
        )

        assertTrue("Fresh process authorizer must not retain previous in-memory tickets", validationResult is TicketValidationResult.NotFound)
    }

    // 20. Failure after ticket consumption -> ticket not restored
    @Test
    fun test20_failureAfterTicketConsumption_ticketNotRestored() {
        val identity = sampleIdentity()
        val now = 10_000L

        val issueResult = authorizer.issueTicket(identity, nowEpochMs = now)
        val ticket = (issueResult as TicketIssueResult.Success).ticket

        // Service consumes ticket
        val validResult = authorizer.validateAndConsumeTicket(ticket.ticketId, identity, nowEpochMs = now + 100L)
        assertTrue(validResult is TicketValidationResult.Valid)

        // Downstream step fails (simulated by caller exception / abort)
        // Ticket must remain consumed and not restored
        val retry = authorizer.validateAndConsumeTicket(ticket.ticketId, identity, nowEpochMs = now + 200L)
        assertTrue("Ticket must not be restored after consumption", retry is TicketValidationResult.NotFound)
    }

    // 21. Queue change between prequalification and Service commit -> ticket does not bypass fresh queue check
    @Test
    fun test21_queueChangeBetweenPrequalificationAndCommit_freshCheckRejects() {
        val item1 = sampleQueueItem(alarmId = 101L, instanceToken = "token_one", triggerTime = 1_000_000L)
        assertTrue(DirectBootAlarmCache.writeQueue(context, listOf(item1), now = 0L))

        val identity1 = sampleIdentity(alarmId = 101L, instanceToken = "token_one", triggerTime = 1_000_000L)
        val intent1 = identity1.createIntent(context)

        // Step 1: Receiver prequalification succeeds and ticket is issued
        val preQualifyResult = authorizer.preQualify(context, intent1)
        assertTrue(preQualifyResult is DeliveryPrequalificationResult.Eligible)

        val ticketResult = authorizer.issueTicket(identity1, nowEpochMs = 1_000_000L)
        assertTrue(ticketResult is TicketIssueResult.Success)
        val ticket = (ticketResult as TicketIssueResult.Success).ticket

        // Step 2: Before Service commits, Queue is updated with another alarm (or item1 cancelled)
        val item2 = sampleQueueItem(alarmId = 202L, instanceToken = "token_two", triggerTime = 2_000_000L)
        assertTrue(DirectBootAlarmCache.writeQueue(context, listOf(item2), now = 0L))

        // Step 3: Service validates ticket
        val ticketValidation = authorizer.validateAndConsumeTicket(ticket.ticketId, identity1, nowEpochMs = 1_000_500L)
        assertTrue(ticketValidation is TicketValidationResult.Valid)

        // Step 4: Critical safety check: Ticket alone does NOT bypass authoritative DE queue qualification
        val preCommitCheck = authorizer.preQualify(context, identity1, nowEpochMs = 1_000_500L)
        assertTrue("Queue changed so preCommitCheck must reject delivery", preCommitCheck is DeliveryPrequalificationResult.NotQueueHead)
        assertEquals(PrequalificationStatus.NOT_QUEUE_HEAD, preCommitCheck.status)
        assertEquals(202L, (preCommitCheck as DeliveryPrequalificationResult.NotQueueHead).headItem?.alarmId)

        // Step 5: Authoritative delivery transition must reject obsolete identity and preserve Queue head
        val firedIdentity1 = FiredOccurrenceIdentity(
            kind = identity1.kind,
            alarmId = identity1.alarmId,
            triggerTime = identity1.triggerTime,
            generation = identity1.generation,
            instanceToken = identity1.instanceToken
        )
        val transitionResult = DirectBootDeliveryTransitionEngine.transitionNormal(
            context = context,
            firingIdentity = firedIdentity1,
            deliveredAt = 1_000_500L
        )
        assertTrue("transitionNormal must reject obsolete identity", transitionResult is DeliveryTransitionResult.Rejected)

        // Assert no Ledger record was created for obsolete identity
        val ledgerState = DirectBootDeliveryLedger.readUnacknowledgedRecords(context)
        val targetRecordId = DirectBootDeliveryRecord.computeRecordId(
            kind = identity1.kind,
            alarmId = identity1.alarmId,
            triggerTime = identity1.triggerTime,
            generation = identity1.generation,
            instanceToken = identity1.instanceToken
        )
        if (ledgerState is LedgerStorageState.ValidWithRecords) {
            assertFalse(
                "Ledger must not contain record for obsolete identity",
                ledgerState.records.any { it.recordId == targetRecordId }
            )
        }

        // Assert Queue was not consumed and current head remains item2
        val queueState = DirectBootAlarmCache.inspectQueueStorageState(context)
        assertTrue(queueState is QueueStorageState.ValidWithItems)
        val currentHead = (queueState as QueueStorageState.ValidWithItems).items.first()
        assertEquals("Queue head must remain item2", 202L, currentHead.alarmId)
        assertEquals(1, queueState.items.size)
    }

    // 22. Required digest: Missing digest extra -> InvalidIdentity
    @Test
    fun test22_missingDigestExtra_returnsInvalidIdentity() {
        val item = sampleQueueItem()
        assertTrue(DirectBootAlarmCache.writeQueue(context, listOf(item), now = 0L))

        val identity = sampleIdentity()
        val intent = identity.createIntent(context).apply {
            removeExtra(DirectBootV2NormalIntentIdentity.EXTRA_IDENTITY_DIGEST)
        }

        val result = authorizer.preQualify(context, intent)
        assertTrue("Expected InvalidIdentity for missing digest extra, got $result", result is DeliveryPrequalificationResult.InvalidIdentity)
        assertEquals(PrequalificationStatus.INVALID_IDENTITY, result.status)
        assertTrue((result as DeliveryPrequalificationResult.InvalidIdentity).reason.contains("missing or blank"))
    }

    // 23. Required digest: Blank digest extra -> InvalidIdentity
    @Test
    fun test23_blankDigestExtra_returnsInvalidIdentity() {
        val item = sampleQueueItem()
        assertTrue(DirectBootAlarmCache.writeQueue(context, listOf(item), now = 0L))

        val identity = sampleIdentity()
        val intent = identity.createIntent(context).apply {
            putExtra(DirectBootV2NormalIntentIdentity.EXTRA_IDENTITY_DIGEST, "   ")
        }

        val result = authorizer.preQualify(context, intent)
        assertTrue("Expected InvalidIdentity for blank digest extra, got $result", result is DeliveryPrequalificationResult.InvalidIdentity)
        assertEquals(PrequalificationStatus.INVALID_IDENTITY, result.status)
        assertTrue((result as DeliveryPrequalificationResult.InvalidIdentity).reason.contains("missing or blank"))
    }

    // 24. Ticket bounds: Zero or negative TTL throws IllegalArgumentException
    @Test
    fun test24_zeroOrNegativeTtl_throwsIllegalArgumentException() {
        try {
            DirectBootDeliveryAuthorizer(ticketTtlMs = 0L)
            org.junit.Assert.fail("Expected IllegalArgumentException for ticketTtlMs = 0")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message?.contains("ticketTtlMs") == true)
        }

        try {
            DirectBootDeliveryAuthorizer(ticketTtlMs = -100L)
            org.junit.Assert.fail("Expected IllegalArgumentException for negative ticketTtlMs")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message?.contains("ticketTtlMs") == true)
        }
    }

    // 25. Ticket bounds: Zero or negative capacity throws IllegalArgumentException
    @Test
    fun test25_zeroOrNegativeCapacity_throwsIllegalArgumentException() {
        try {
            DirectBootDeliveryAuthorizer(maxActiveTickets = 0)
            org.junit.Assert.fail("Expected IllegalArgumentException for maxActiveTickets = 0")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message?.contains("maxActiveTickets") == true)
        }

        try {
            DirectBootDeliveryAuthorizer(maxActiveTickets = -1)
            org.junit.Assert.fail("Expected IllegalArgumentException for negative maxActiveTickets")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message?.contains("maxActiveTickets") == true)
        }
    }

    // 26. Ticket bounds: Safe expiry calculation / Long overflow fails closed
    @Test
    fun test26_ticketExpiryArithmeticOverflow_failsClosed() {
        val identity = sampleIdentity()
        // nowEpochMs near Long.MAX_VALUE would cause nowEpochMs + ticketTtlMs to overflow to negative Long
        val nearMaxEpoch = Long.MAX_VALUE - 500L
        val issueResult = authorizer.issueTicket(identity, nowEpochMs = nearMaxEpoch)

        assertTrue("Expected ArithmeticOverflow or fail-closed rejection, got $issueResult", issueResult is TicketIssueResult.ArithmeticOverflow)
    }

    // 27. Ticket bounds: Expiry boundary equality (at exact expiry instant, ticket is expired)
    @Test
    fun test27_expiryBoundaryEquality_atExactExpiryInstant_isExpired() {
        val identity = sampleIdentity()
        val now = 10_000L
        val issueResult = authorizer.issueTicket(identity, nowEpochMs = now)
        assertTrue(issueResult is TicketIssueResult.Success)
        val ticket = (issueResult as TicketIssueResult.Success).ticket

        // Exact expiry instant (now + 15_000ms)
        val exactExpiry = ticket.expiresAtEpochMs
        val validation = authorizer.validateAndConsumeTicket(ticket.ticketId, identity, nowEpochMs = exactExpiry)
        assertTrue("Ticket must be considered expired at exact boundary instant", validation is TicketValidationResult.Expired)
    }

    // 28. Ticket bounds: Capacity available after expired-ticket purge
    @Test
    fun test28_capacityAvailableAfterExpiredTicketsPurged() {
        val smallAuthorizer = DirectBootDeliveryAuthorizer(maxActiveTickets = 2)
        val identity = sampleIdentity()
        val now = 10_000L

        val t1 = smallAuthorizer.issueTicket(identity, nowEpochMs = now)
        assertTrue(t1 is TicketIssueResult.Success)

        val t2 = smallAuthorizer.issueTicket(identity, nowEpochMs = now)
        assertTrue(t2 is TicketIssueResult.Success)

        // At now + 16_000ms, t1 and t2 are expired
        val laterNow = now + DirectBootDeliveryAuthorizer.DEFAULT_TICKET_TTL_MS + 1_000L
        val t3 = smallAuthorizer.issueTicket(identity, nowEpochMs = laterNow)
        assertTrue("After purging expired tickets, capacity must be available", t3 is TicketIssueResult.Success)
    }
}
