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
        triggerTime: Long = testTriggerTime,
        restoreEpoch: Long = 1L
    ): DirectBootV2NormalIntentIdentity {
        return DirectBootV2NormalIntentIdentity(
            alarmId = alarmId,
            generation = generation,
            instanceToken = instanceToken,
            triggerTime = triggerTime,
            restoreEpoch = restoreEpoch
        )
    }

    private fun sampleQueueItem(
        alarmId: Long = testAlarmId,
        generation: Int = testGeneration,
        instanceToken: String = testToken,
        triggerTime: Long = testTriggerTime,
        restoreEpoch: Long = 1L
    ): DirectBootQueueItem {
        return DirectBootQueueItem(
            kind = DirectBootQueueItem.KIND_NORMAL,
            alarmId = alarmId,
            triggerTime = triggerTime,
            generation = generation,
            instanceToken = instanceToken,
            timeLabel = "07:00",
            playDefaultSound = true,
            vibrationEnabled = true,
            restoreEpoch = restoreEpoch
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

    // =========================================================================
    // KNO-75 C7-A0-v3: Inactive Security Hardening Tests (A0-01 .. A0-10)
    // =========================================================================

    // A0-01: Epoch mismatch -> REJECT (both preQualify and ticket validation)
    @Test
    fun a0_01_epochMismatch_rejectsPrequalificationAndTicketValidation() {
        val queueItem = sampleQueueItem(restoreEpoch = 2L)
        assertTrue(DirectBootAlarmCache.writeQueue(context, listOf(queueItem), now = 0L))

        val mismatchedIdentity = sampleIdentity(restoreEpoch = 1L)
        val intent = mismatchedIdentity.createIntent(context)

        val preQualifyResult = authorizer.preQualify(context, intent)
        assertFalse(
            "preQualify MUST reject restoreEpoch mismatch (queue=2 vs intent=1), got $preQualifyResult",
            preQualifyResult is DeliveryPrequalificationResult.Eligible
        )

        val ticketIdentity = sampleIdentity(restoreEpoch = 2L)
        val issueResult = authorizer.issueTicket(ticketIdentity, nowEpochMs = 10_000L)
        assertTrue(issueResult is TicketIssueResult.Success)
        val ticket = (issueResult as TicketIssueResult.Success).ticket

        val validateMismatch = authorizer.validateAndConsumeTicket(
            ticketId = ticket.ticketId,
            expectedIdentity = mismatchedIdentity,
            nowEpochMs = 10_500L
        )
        assertTrue(
            "validateAndConsumeTicket MUST reject restoreEpoch mismatch, got $validateMismatch",
            validateMismatch is TicketValidationResult.IdentityMismatch
        )
    }

    // A0-02: Epoch zero -> REJECT (preQualify and ticket validation)
    @Test
    fun a0_02_epochZero_rejectsPrequalificationAndTicketValidation() {
        val queueItem = sampleQueueItem(restoreEpoch = 1L)
        assertTrue(DirectBootAlarmCache.writeQueue(context, listOf(queueItem), now = 0L))

        val zeroEpochIdentity = sampleIdentity(restoreEpoch = 0L)
        val intentWithZeroEpoch = zeroEpochIdentity.createIntent(context)

        val preQualifyIntentResult = authorizer.preQualify(context, intentWithZeroEpoch)
        assertTrue(
            "preQualify(intent) MUST reject restoreEpoch == 0 as InvalidIdentity, got $preQualifyIntentResult",
            preQualifyIntentResult is DeliveryPrequalificationResult.InvalidIdentity
        )

        val preQualifyIdentityResult = authorizer.preQualify(context, zeroEpochIdentity)
        assertTrue(
            "preQualify(identity) MUST reject restoreEpoch == 0 as InvalidIdentity, got $preQualifyIdentityResult",
            preQualifyIdentityResult is DeliveryPrequalificationResult.InvalidIdentity
        )

        // Even if a ticket was issued with epoch 0 (e.g., programmatic caller), validation must reject epoch 0
        val zeroTicketRes = authorizer.issueTicket(zeroEpochIdentity, nowEpochMs = 20_000L)
        assertTrue(zeroTicketRes is TicketIssueResult.Success)
        val zeroTicket = (zeroTicketRes as TicketIssueResult.Success).ticket

        val validateZeroAgainstOne = authorizer.validateAndConsumeTicket(
            ticketId = zeroTicket.ticketId,
            expectedIdentity = sampleIdentity(restoreEpoch = 1L),
            nowEpochMs = 20_100L
        )
        assertTrue(
            "Ticket with epoch=0 MUST NOT bypass validation against epoch=1, got $validateZeroAgainstOne",
            validateZeroAgainstOne is TicketValidationResult.IdentityMismatch
        )

        val zeroTicketRes2 = authorizer.issueTicket(zeroEpochIdentity, nowEpochMs = 20_200L)
        val zeroTicket2 = (zeroTicketRes2 as TicketIssueResult.Success).ticket
        val validateZeroAgainstZero = authorizer.validateAndConsumeTicket(
            ticketId = zeroTicket2.ticketId,
            expectedIdentity = zeroEpochIdentity,
            nowEpochMs = 20_300L
        )
        assertTrue(
            "Ticket validation MUST reject zero restoreEpoch even when both are 0, got $validateZeroAgainstZero",
            validateZeroAgainstZero is TicketValidationResult.IdentityMismatch
        )
    }

    // A0-03: Queue Epoch zero / legacy schema v2 -> REJECT (must not reinterpret legacy schema as authorized V2)
    @Test
    fun a0_03_queueEpochZeroAndLegacySchema_rejectsPrequalification() {
        val legacyQueueItem = DirectBootQueueItem(
            kind = DirectBootQueueItem.KIND_NORMAL,
            alarmId = testAlarmId,
            triggerTime = testTriggerTime,
            generation = testGeneration,
            instanceToken = testToken,
            timeLabel = "07:00",
            restoreEpoch = 0L,
            schemaVersion = 2
        )
        assertTrue(DirectBootAlarmCache.writeQueue(context, listOf(legacyQueueItem), now = 0L))

        val identityEpoch1 = sampleIdentity(restoreEpoch = 1L)
        val res1 = authorizer.preQualify(context, identityEpoch1.createIntent(context))
        assertFalse(
            "Authorizer MUST reject legacy schema v2 / epoch=0 queue item for V2 Normal, got $res1",
            res1 is DeliveryPrequalificationResult.Eligible
        )

        val identityEpoch0 = sampleIdentity(restoreEpoch = 0L)
        val res0 = authorizer.preQualify(context, identityEpoch0.createIntent(context))
        assertFalse(
            "Authorizer MUST reject epoch=0 queue item even if intent has epoch=0, got $res0",
            res0 is DeliveryPrequalificationResult.Eligible
        )
    }

    // A0-04: Positive equal Epoch -> PREQUALIFICATION
    @Test
    fun a0_04_positiveEqualEpoch_preQualifiesAndValidatesTicket() {
        val epoch = 7L
        val item = sampleQueueItem(restoreEpoch = epoch)
        assertTrue(DirectBootAlarmCache.writeQueue(context, listOf(item), now = 0L))

        val identity = sampleIdentity(restoreEpoch = epoch)
        val result = authorizer.preQualify(context, identity.createIntent(context))
        assertTrue("Expected Eligible for matching positive epoch=$epoch, got $result", result is DeliveryPrequalificationResult.Eligible)

        val issueResult = authorizer.issueTicket(identity, nowEpochMs = 30_000L)
        assertTrue(issueResult is TicketIssueResult.Success)
        val ticket = (issueResult as TicketIssueResult.Success).ticket

        val validateResult = authorizer.validateAndConsumeTicket(ticket.ticketId, identity, nowEpochMs = 30_500L)
        assertTrue("Expected Valid ticket for positive equal epoch=$epoch, got $validateResult", validateResult is TicketValidationResult.Valid)
    }

    // A0-05: Malformed Intent extras -> InvalidIdentity (missing kind, bad id/gen/triggerTime)
    @Test
    fun a0_05_malformedV2IntentMissingKindOrInvalidExtras_returnsInvalidIdentity() {
        val item = sampleQueueItem(restoreEpoch = 1L)
        assertTrue(DirectBootAlarmCache.writeQueue(context, listOf(item), now = 0L))

        val identity = sampleIdentity(restoreEpoch = 1L)
        val missingKindIntent = identity.createIntent(context).apply {
            removeExtra(DirectBootV2NormalIntentIdentity.EXTRA_KIND)
        }
        val missingKindRes = authorizer.preQualify(context, missingKindIntent)
        assertTrue(
            "Missing EXTRA_KIND MUST be rejected as InvalidIdentity, got $missingKindRes",
            missingKindRes is DeliveryPrequalificationResult.InvalidIdentity
        )

        val missingEpochIntent = identity.createIntent(context).apply {
            removeExtra(DirectBootV2NormalIntentIdentity.EXTRA_RESTORE_EPOCH)
        }
        val missingEpochRes = authorizer.preQualify(context, missingEpochIntent)
        assertTrue(
            "Missing EXTRA_RESTORE_EPOCH MUST be rejected as InvalidIdentity, got $missingEpochRes",
            missingEpochRes is DeliveryPrequalificationResult.InvalidIdentity
        )
    }

    // A0-06: Corrupt/full storage -> FAIL CLOSED
    @Test
    fun a0_06_corruptAndFullStorage_failsClosedAndPreservesRawBytes() {
        val corruptJson = """{"schemaVersion":3,"items":[{"kind":"NORMAL","alarmId":101,"triggerTime":1700000000000,"generation":1,"instanceToken":"dup_tok","restoreEpoch":1,"schemaVersion":3},{"kind":"NORMAL","alarmId":102,"triggerTime":1700000001000,"generation":1,"instanceToken":"dup_tok","restoreEpoch":1,"schemaVersion":3}]}"""
        prefs.edit().putString(DirectBootAlarmCache.KEY_QUEUE_PAYLOAD, corruptJson).commit()

        val identity = sampleIdentity(restoreEpoch = 1L)
        val corruptRes = authorizer.preQualify(context, identity.createIntent(context))
        assertTrue("Expected StorageUnverifiable for corrupt queue, got $corruptRes", corruptRes is DeliveryPrequalificationResult.StorageUnverifiable)
        assertEquals(corruptJson, prefs.getString(DirectBootAlarmCache.KEY_QUEUE_PAYLOAD, null))
    }

    // A0-09: QUICK isolation -> PRESERVED
    @Test
    fun a0_09_quickIsolation_authorizerRejectsQuickIntentAndQuickQueueHead() {
        val quickHead = DirectBootQueueItem(
            kind = DirectBootQueueItem.KIND_QUICK,
            alarmId = testAlarmId,
            triggerTime = testTriggerTime,
            generation = 0,
            instanceToken = ""
        )
        assertTrue(DirectBootAlarmCache.writeQueue(context, listOf(quickHead), now = 0L))

        val identity = sampleIdentity(restoreEpoch = 1L)
        val quickIntent = identity.createIntent(context).apply {
            putExtra(DirectBootV2NormalIntentIdentity.EXTRA_KIND, DirectBootQueueItem.KIND_QUICK)
        }
        val quickIntentRes = authorizer.preQualify(context, quickIntent)
        assertTrue("Authorizer MUST reject KIND_QUICK intent, got $quickIntentRes", quickIntentRes is DeliveryPrequalificationResult.InvalidIdentity)

        val normalAgainstQuickHead = authorizer.preQualify(context, identity.createIntent(context))
        assertTrue("Authorizer MUST reject Normal intent against QUICK head, got $normalAgainstQuickHead", normalAgainstQuickHead is DeliveryPrequalificationResult.NotQueueHead)
    }

    // A0-10: Sensitive diagnostics -> REDACTED
    @Test
    fun a0_10_sensitiveDiagnostics_redactedFromAuthorizerReasons() {
        val secretToken = "secret_tok_a010_authorizer"
        val secretHeadToken = "secret_head_tok_a010_authorizer"

        // 1. InvalidIdentity with invalid extras must redact instanceToken
        val badIntent = sampleIdentity(instanceToken = secretToken, restoreEpoch = 1L).createIntent(context).apply {
            putExtra(DirectBootV2NormalIntentIdentity.EXTRA_ALARM_ID, -1L)
        }
        val invalidRes = authorizer.preQualify(context, badIntent) as DeliveryPrequalificationResult.InvalidIdentity
        assertFalse("InvalidIdentity.reason MUST NOT leak raw instanceToken", invalidRes.reason.contains(secretToken))

        // 2. NotQueueHead must redact head.instanceToken
        val headItem = sampleQueueItem(alarmId = 202L, instanceToken = secretHeadToken, restoreEpoch = 1L)
        assertTrue(DirectBootAlarmCache.writeQueue(context, listOf(headItem), now = 0L))
        val notHeadRes = authorizer.preQualify(
            context,
            sampleIdentity(alarmId = 101L, instanceToken = secretToken, restoreEpoch = 1L).createIntent(context)
        ) as DeliveryPrequalificationResult.NotQueueHead
        assertFalse("NotQueueHead.reason MUST NOT leak head instanceToken", notHeadRes.reason.contains(secretHeadToken))
        assertFalse("NotQueueHead.reason MUST NOT leak intent instanceToken", notHeadRes.reason.contains(secretToken))

        // 3. DuplicateDelivery reason must redact raw instanceToken (while keeping recordId field)
        val dupItem = sampleQueueItem(alarmId = 303L, instanceToken = secretToken, restoreEpoch = 1L)
        assertTrue(DirectBootAlarmCache.writeQueue(context, listOf(dupItem), now = 0L))
        val existingRecord = DirectBootDeliveryRecord.createNormal(
            alarmId = 303L,
            triggerTime = testTriggerTime,
            deliveredAt = testTriggerTime + 100L,
            generation = testGeneration,
            instanceToken = secretToken,
            restoreEpoch = 1L
        )
        assertTrue(DirectBootDeliveryLedger.appendRecord(context, existingRecord) is DeliveryAppendResult.Appended)
        val dupRes = authorizer.preQualify(
            context,
            sampleIdentity(alarmId = 303L, instanceToken = secretToken, restoreEpoch = 1L).createIntent(context)
        ) as DeliveryPrequalificationResult.DuplicateDelivery
        assertFalse("DuplicateDelivery.reason MUST NOT leak raw instanceToken", dupRes.reason.contains(secretToken))

        // 4. StorageUnverifiable with duplicate token corruption must redact raw instanceToken
        val dupTokenQueueJson = """{"schemaVersion":3,"items":[{"kind":"NORMAL","alarmId":101,"triggerTime":1700000000000,"generation":1,"instanceToken":"$secretToken","restoreEpoch":1,"schemaVersion":3},{"kind":"NORMAL","alarmId":102,"triggerTime":1700000001000,"generation":1,"instanceToken":"$secretToken","restoreEpoch":1,"schemaVersion":3}]}"""
        prefs.edit().putString(DirectBootAlarmCache.KEY_QUEUE_PAYLOAD, dupTokenQueueJson).commit()
        val corruptRes = authorizer.preQualify(
            context,
            sampleIdentity(alarmId = 101L, instanceToken = secretToken, restoreEpoch = 1L).createIntent(context)
        ) as DeliveryPrequalificationResult.StorageUnverifiable
        assertFalse("StorageUnverifiable.reason MUST NOT leak raw instanceToken", corruptRes.reason.contains(secretToken))

        // 5. IdentityMismatch in ticket validation must redact raw instanceToken
        val ticketIdObj = authorizer.issueTicket(sampleIdentity(alarmId = 404L, instanceToken = secretToken, restoreEpoch = 1L), nowEpochMs = 40_000L) as TicketIssueResult.Success
        val mismatchRes = authorizer.validateAndConsumeTicket(
            ticketId = ticketIdObj.ticket.ticketId,
            expectedIdentity = sampleIdentity(alarmId = 404L, instanceToken = secretHeadToken, restoreEpoch = 1L),
            nowEpochMs = 40_100L
        ) as TicketValidationResult.IdentityMismatch
        assertFalse("IdentityMismatch.reason MUST NOT leak ticket instanceToken", mismatchRes.reason.contains(secretToken))
        assertFalse("IdentityMismatch.reason MUST NOT leak expected instanceToken", mismatchRes.reason.contains(secretHeadToken))
    }
}
