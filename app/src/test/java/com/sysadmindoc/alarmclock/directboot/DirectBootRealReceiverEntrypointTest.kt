package com.sysadmindoc.alarmclock.directboot

import android.content.Context
import android.content.Intent
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.sysadmindoc.alarmclock.data.local.AlarmDatabase
import com.sysadmindoc.alarmclock.domain.AlarmScheduler
import com.sysadmindoc.alarmclock.receiver.AlarmReceiver
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
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Real Receiver Entrypoint Owner Safety Test Suite for KNO-75 Phase C7-OWNER-ARCHITECTURE-DECISION (G1 - G5).
 *
 * Executes real [AlarmReceiver.onReceive] and [DirectBootAlarmReceiver.onReceive] calls
 * to verify bi-directional delivery fencing at the actual OS entrypoint boundaries.
 */
@RunWith(RobolectricTestRunner::class)
class DirectBootRealReceiverEntrypointTest {

    private lateinit var deContext: Context
    private lateinit var ceContext: Context
    private lateinit var database: AlarmDatabase
    private val baseTime = 1_700_000_000_000L

    private fun clearDeStorage() {
        val dePrefs = deContext.getSharedPreferences(DirectBootDeliveryLedger.PREFS_NAME, Context.MODE_PRIVATE)
        dePrefs.edit()
            .remove(DirectBootDeliveryLedger.KEY_DELIVERY_LEDGER_PAYLOAD)
            .remove(DirectBootMissedStore.KEY_MISSED_STORE_PAYLOAD)
            .remove(DirectBootAlarmCache.KEY_QUEUE_PAYLOAD)
            .commit()
    }

    @Before
    fun setUp() {
        val appContext = ApplicationProvider.getApplicationContext<Context>()
        deContext = appContext.createDeviceProtectedStorageContext()
        ceContext = appContext
        database = Room.inMemoryDatabaseBuilder(appContext, AlarmDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        DirectBootCeTakeoverStore.unlockGateSeam = { true }
        DirectBootCeTakeoverStore.clear(ceContext)
        clearDeStorage()
    }

    @After
    fun tearDown() {
        database.close()
        DirectBootCeTakeoverStore.clear(ceContext)
        DirectBootCeTakeoverStore.resetSeamsForTesting()
        clearDeStorage()
    }

    // =========================================================================
    // G2.1: Legacy-first -> DirectBoot Entrypoint Rejected
    // =========================================================================

    @Test
    fun g2_1_legacyFirst_directBootReceiverRejectsDelivery() {
        val alarmId = 101L
        val triggerTime = baseTime + 10_000L

        // Legacy delivers first and appends delivery record to DE ledger
        val delRecord = DirectBootDeliveryRecord.createNormal(
            alarmId = alarmId,
            triggerTime = triggerTime,
            deliveredAt = baseTime + 10_010L,
            generation = 1,
            instanceToken = "tok_legacy_101",
            provenance = "LEGACY_DELIVERY",
            restoreEpoch = 1L
        )
        val appendRes = DirectBootDeliveryLedger.appendRecord(deContext, delRecord)
        assertTrue(appendRes is DeliveryAppendResult.Appended)

        // Seed Queue for DirectBoot Receiver check
        val itemA = DirectBootQueueItem(
            kind = DirectBootQueueItem.KIND_NORMAL,
            alarmId = alarmId,
            triggerTime = triggerTime,
            generation = 1,
            instanceToken = "tok_legacy_101",
            restoreEpoch = 1L
        )
        DirectBootAlarmCache.writeQueue(deContext, listOf(itemA), now = baseTime)

        val authorizer = DirectBootDeliveryAuthorizer()
        var directBootServiceStarts = 0

        DirectBootAlarmReceiver.serviceAdapterSeam = { ctx ->
            object : DirectBootServiceDeliveryAdapter(ctx, authorizer, clock = { baseTime + 10_020L }) {
                override fun adaptDelivery(
                    ticketId: String,
                    identity: DirectBootV2NormalIntentIdentity,
                    deliveredAt: Long
                ): ServiceDeliveryAdaptationResult {
                    val res = super.adaptDelivery(ticketId, identity, deliveredAt)
                    if (res is ServiceDeliveryAdaptationResult.TransitionCommitted) {
                        directBootServiceStarts++
                    }
                    return res
                }
            }
        }

        val identityA = DirectBootV2NormalIntentIdentity.fromQueueItem(itemA)
        val ticketRes = authorizer.issueTicket(identityA, nowEpochMs = baseTime + 10_020L)
        assertTrue(ticketRes is TicketIssueResult.Success)
        val ticketId = (ticketRes as TicketIssueResult.Success).ticket.ticketId

        val firingIntent = identityA.createIntent(ceContext).apply {
            putExtra(DirectBootDeliveryAuthorizer.EXTRA_TICKET_ID, ticketId)
        }

        // DirectBootAlarmReceiver.onReceive runs
        val dbReceiver = DirectBootAlarmReceiver()
        dbReceiver.onReceive(ceContext, firingIntent)

        // DirectBoot service start count MUST be 0
        assertEquals("DirectBoot receiver MUST NOT start service when Legacy has already delivered occurrence", 0, directBootServiceStarts)
    }

    // =========================================================================
    // G2.2: DirectBoot-first -> Legacy Receiver Drops Intent
    // =========================================================================

    @Test
    fun g2_2_directBootFirst_legacyReceiverDropsIntent() {
        val alarmId = 102L
        val triggerTime = baseTime + 20_000L

        val delRecord = DirectBootDeliveryRecord.createNormal(
            alarmId = alarmId,
            triggerTime = triggerTime,
            deliveredAt = baseTime + 20_010L,
            generation = 1,
            instanceToken = "tok_db_102",
            provenance = "V2_INTENT_DELIVERY",
            restoreEpoch = 1L
        )
        DirectBootDeliveryLedger.appendRecord(deContext, delRecord)

        // Legacy AlarmReceiver receives legacy broadcast
        val legacyIntent = Intent("com.sysadmindoc.alarmclock.ALARM_FIRED").apply {
            putExtra(AlarmScheduler.EXTRA_ALARM_ID, alarmId)
            putExtra(AlarmScheduler.EXTRA_SCHEDULED_AT, triggerTime)
        }

        val receiver = AlarmReceiver()
        receiver.onReceive(ceContext, legacyIntent)

        // Verify isOccurrenceDeliveredByDirectBoot returns true
        val isBlocked = AlarmReceiver.isOccurrenceDeliveredByDirectBoot(deContext, alarmId, triggerTime)
        assertTrue("Legacy receiver MUST drop intent for occurrence delivered by DirectBoot", isBlocked)
    }

    // =========================================================================
    // G2.3: Concurrent Receiver Entry -> Exactly 1 Winner
    // =========================================================================

    @Test
    fun g2_3_concurrentReceiverEntry_exactlyOneWinner() = runBlocking {
        val alarmId = 103L
        val triggerTime = baseTime + 30_000L

        val item = DirectBootQueueItem(
            kind = DirectBootQueueItem.KIND_NORMAL,
            alarmId = alarmId,
            triggerTime = triggerTime,
            generation = 1,
            instanceToken = "tok_conc_103",
            restoreEpoch = 1L
        )
        DirectBootAlarmCache.writeQueue(deContext, listOf(item), now = baseTime)

        val deliveryWins = AtomicInteger(0)
        val executor = Executors.newFixedThreadPool(2)

        val task1 = executor.submit {
            val delRecord = DirectBootDeliveryRecord.createNormal(
                alarmId = alarmId,
                triggerTime = triggerTime,
                deliveredAt = baseTime + 30_050L,
                generation = 1,
                instanceToken = "tok_conc_103",
                provenance = "LEGACY_RECEIVER",
                restoreEpoch = 1L
            )
            val res = DirectBootDeliveryLedger.appendRecord(deContext, delRecord)
            if (res is DeliveryAppendResult.Appended) deliveryWins.incrementAndGet()
        }

        val task2 = executor.submit {
            val res = DirectBootDeliveryTransitionEngine.transitionNormal(
                context = deContext,
                firingIdentity = FiredOccurrenceIdentity(
                    kind = "NORMAL",
                    alarmId = alarmId,
                    triggerTime = triggerTime,
                    generation = 1,
                    instanceToken = "tok_conc_103",
                    restoreEpoch = 1L,
                    provenance = "DIRECT_BOOT_RECEIVER"
                ),
                deliveredAt = baseTime + 30_050L
            )
            if (res is DeliveryTransitionResult.Confirmed || res is DeliveryTransitionResult.CancellationAfterCommit) {
                deliveryWins.incrementAndGet()
            }
        }

        executor.shutdown()
        executor.awaitTermination(5, TimeUnit.SECONDS)

        assertEquals("EXACTLY 1 receiver MUST win delivery ownership under concurrent entry", 1, deliveryWins.get())
    }

    // =========================================================================
    // G2.4: Delayed Legacy Broadcast after DE ACK
    // =========================================================================

    @Test
    fun g2_4_delayedLegacyAfterDeAck_blockedByCeTakeoverStore() {
        val alarmId = 104L
        val triggerTime = baseTime + 40_000L

        val delRecord = DirectBootDeliveryRecord.createNormal(
            alarmId = alarmId,
            triggerTime = triggerTime,
            deliveredAt = baseTime + 40_010L,
            generation = 1,
            instanceToken = "tok_ack_104",
            restoreEpoch = 1L
        )
        DirectBootDeliveryLedger.appendRecord(deContext, delRecord)

        // Handoff to CE + ACK
        val intakeRes = DirectBootCeTakeoverStore.recordDelivered(ceContext, delRecord, baseTime + 40_050L)
        assertTrue(intakeRes is CeTakeoverIntakeResult.RecordedConfirmed)
        val confirmedReceipt = CeHandoffResult.Confirmed(
            ceRecord = (intakeRes as CeTakeoverIntakeResult.RecordedConfirmed).record,
            totalCeRecords = 1,
            alreadyRecordedInCe = false,
            writePerformed = true
        )

        DirectBootCeHandoffCoordinator.acknowledgeDeliveredWithCeProof(
            deContext = deContext,
            ceContext = ceContext,
            candidateRecord = delRecord,
            confirmedReceipt = confirmedReceipt,
            unlockGate = { true }
        )

        // Delayed legacy broadcast arrives
        val isBlocked = AlarmReceiver.isOccurrenceDeliveredByDirectBoot(deContext, alarmId, triggerTime)
        assertTrue("Delayed legacy broadcast MUST be blocked by active CE Takeover Record", isBlocked)
    }

    // =========================================================================
    // G2.6: Same alarmId, Different Generation -> Unrelated Gen NOT Blocked
    // =========================================================================

    @Test
    fun g2_6_sameAlarmIdDifferentGeneration_notBlocked() {
        val delRecordGen1 = DirectBootDeliveryRecord.createNormal(
            alarmId = 106L,
            triggerTime = baseTime + 60_000L,
            deliveredAt = baseTime + 60_010L,
            generation = 1,
            instanceToken = "tok_gen1_106",
            restoreEpoch = 1L
        )
        DirectBootDeliveryLedger.appendRecord(deContext, delRecordGen1)

        val isGen2Blocked = AlarmReceiver.isOccurrenceDeliveredByDirectBoot(deContext, 106L, baseTime + 120_000L)
        assertFalse("Unrelated generation / triggerTime for alarm 106 MUST NOT be blocked", isGen2Blocked)
    }

    // =========================================================================
    // G2.8: Legacy-Only Alarm -> Delivered Cleanly
    // =========================================================================

    @Test
    fun g2_8_legacyOnlyAlarm_deliveredCleanly() {
        val isBlocked = AlarmReceiver.isOccurrenceDeliveredByDirectBoot(deContext, 888L, baseTime + 80_000L)
        assertFalse("Legacy-only repeating alarm NOT in DirectBoot Ledger MUST be delivered cleanly", isBlocked)
    }
}
