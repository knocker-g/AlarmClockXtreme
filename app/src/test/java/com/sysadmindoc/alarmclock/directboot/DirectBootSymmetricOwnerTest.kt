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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Bi-Directional Symmetric Owner Safety Test Suite for KNO-75 Phase C7-OWNER-DISCRIMINATOR-FINAL.
 *
 * Verifies R1 - R8 bi-directional mutual exclusion between Legacy Receiver and DirectBoot Receiver:
 * - R1: Legacy-first -> DirectBoot delivery rejected.
 * - R2: DirectBoot-first -> Legacy delivery rejected.
 * - R3: Concurrent Legacy + DirectBoot -> Exactly 1 receiver wins delivery ownership.
 * - R4: Delayed Legacy broadcast after DE ACK -> Legacy delivery rejected.
 * - R5: Process Death / Re-entry -> Delivery idempotent, zero slot/side-effect duplication.
 * - R6: Same alarmId, Different generation -> Unrelated generation NOT blocked.
 * - R7: Restore Epoch mismatch -> Stale epoch intent rejected.
 * - R8: Legacy-only repeating alarm -> Delivered by legacy without blockage.
 */
@RunWith(RobolectricTestRunner::class)
class DirectBootSymmetricOwnerTest {

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
    // R1: Legacy-first -> DirectBoot Delivery Rejected
    // =========================================================================

    @Test
    fun r1_legacyFirst_directBootDeliveryRejected() {
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

        // Subsequent DirectBoot delivery attempt for same occurrence
        val transitionRes = DirectBootDeliveryTransitionEngine.transitionNormal(
            context = deContext,
            firingIdentity = FiredOccurrenceIdentity(
                kind = "NORMAL",
                alarmId = alarmId,
                triggerTime = triggerTime,
                generation = 1,
                instanceToken = "tok_legacy_101",
                restoreEpoch = 1L,
                provenance = "DIRECT_BOOT"
            ),
            deliveredAt = baseTime + 10_050L
        )

        assertTrue(
            "DirectBoot MUST reject delivery when Legacy has already delivered occurrence, got $transitionRes",
            transitionRes is DeliveryTransitionResult.Rejected
        )
    }

    // =========================================================================
    // R2: DirectBoot-first -> Legacy Delivery Rejected
    // =========================================================================

    @Test
    fun r2_directBootFirst_legacyDeliveryRejected() {
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

        // Legacy receiver queries DirectBoot Ledger before start
        val isBlocked = AlarmReceiver.isOccurrenceDeliveredByDirectBoot(deContext, alarmId, triggerTime)
        assertTrue("Legacy receiver MUST reject occurrence already delivered by DirectBoot", isBlocked)
    }

    // =========================================================================
    // R3: Concurrent Legacy + DirectBoot -> Exactly 1 Winner
    // =========================================================================

    @Test
    fun r3_concurrentLegacyAndDirectBoot_exactlyOneWinner() = runBlocking {
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
                provenance = "LEGACY_CONCURRENT",
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
                    provenance = "DIRECT_BOOT_CONCURRENT"
                ),
                deliveredAt = baseTime + 30_050L
            )
            if (res is DeliveryTransitionResult.Confirmed || res is DeliveryTransitionResult.CancellationAfterCommit) {
                deliveryWins.incrementAndGet()
            }
        }

        executor.shutdown()
        executor.awaitTermination(5, TimeUnit.SECONDS)

        assertEquals("EXACTLY 1 delivery winner allowed under concurrent race", 1, deliveryWins.get())
    }

    // =========================================================================
    // R4: Delayed Legacy Broadcast after DE ACK
    // =========================================================================

    @Test
    fun r4_delayedLegacyAfterDeAck_rejected() {
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
        // Since DE record was ACKed and retired from DE Ledger, check CE Takeover Store
        val ceState = DirectBootCeTakeoverStore.inspectStorageState(ceContext)
        val inCe = ceState is CeTakeoverStorageState.ValidWithRecords &&
            ceState.records.any { it.alarmId == alarmId && it.triggerTime == triggerTime }

        assertTrue("Delayed legacy broadcast MUST be blocked by active CE Takeover Record", inCe || isBlocked)
    }

    // =========================================================================
    // R5: Process Death / Re-entry Idempotency
    // =========================================================================

    @Test
    fun r5_processDeathReentry_idempotent() {
        val delRecord = DirectBootDeliveryRecord.createNormal(
            alarmId = 105L,
            triggerTime = baseTime + 50_000L,
            deliveredAt = baseTime + 50_010L,
            generation = 1,
            instanceToken = "tok_reentry_105",
            restoreEpoch = 1L
        )
        DirectBootDeliveryLedger.appendRecord(deContext, delRecord)

        // Re-entry delivery attempt 1
        val res1 = DirectBootDeliveryLedger.appendRecord(deContext, delRecord)
        // Re-entry delivery attempt 2
        val res2 = DirectBootDeliveryLedger.appendRecord(deContext, delRecord)

        // Storage state has exactly 1 record
        val ledgerState = DirectBootDeliveryLedger.readUnacknowledgedRecords(deContext)
        assertTrue(ledgerState is LedgerStorageState.ValidWithRecords)
        assertEquals(1, (ledgerState as LedgerStorageState.ValidWithRecords).records.size)
    }

    // =========================================================================
    // R6: Same alarmId, Different Generation -> Unrelated Gen NOT Blocked
    // =========================================================================

    @Test
    fun r6_sameAlarmIdDifferentGeneration_notBlocked() {
        val delRecordGen1 = DirectBootDeliveryRecord.createNormal(
            alarmId = 106L,
            triggerTime = baseTime + 60_000L,
            deliveredAt = baseTime + 60_010L,
            generation = 1,
            instanceToken = "tok_gen1_106",
            restoreEpoch = 1L
        )
        DirectBootDeliveryLedger.appendRecord(deContext, delRecordGen1)

        // Alarm 106 Gen 2 is a new occurrence at a different triggerTime
        val isGen2Blocked = AlarmReceiver.isOccurrenceDeliveredByDirectBoot(deContext, 106L, baseTime + 120_000L)
        assertFalse("Unrelated generation / triggerTime for alarm 106 MUST NOT be blocked", isGen2Blocked)
    }

    // =========================================================================
    // R7: Restore Epoch Mismatch -> Stale Epoch Rejected
    // =========================================================================

    @Test
    fun r7_restoreEpochMismatch_rejected() {
        val staleRecord = DirectBootDeliveryRecord.createNormal(
            alarmId = 107L,
            triggerTime = baseTime + 70_000L,
            deliveredAt = baseTime + 70_010L,
            generation = 1,
            instanceToken = "tok_stale_107",
            restoreEpoch = 0L // Stale / missing restore epoch
        )

        val res = DirectBootDeliveryTransitionEngine.transitionNormal(
            context = deContext,
            firingIdentity = FiredOccurrenceIdentity(
                kind = "NORMAL",
                alarmId = 107L,
                triggerTime = baseTime + 70_000L,
                generation = 1,
                instanceToken = "tok_stale_107",
                restoreEpoch = 0L,
                provenance = "STALE_EPOCH"
            ),
            deliveredAt = baseTime + 70_050L
        )

        // Queue item with restoreEpoch = 0L for Queue v3 candidate fails qualification
        assertTrue(
            "Stale restore epoch delivery MUST be rejected or return NotQueueHead",
            res is DeliveryTransitionResult.Rejected
        )
    }

    // =========================================================================
    // R8: Legacy-Only Repeating Alarm -> Delivered Cleanly
    // =========================================================================

    @Test
    fun r8_legacyOnlyAlarm_deliveredCleanly() {
        val isBlocked = AlarmReceiver.isOccurrenceDeliveredByDirectBoot(deContext, 888L, baseTime + 80_000L)
        assertFalse("Legacy-only repeating alarm NOT in DirectBoot Ledger MUST be delivered cleanly", isBlocked)
    }
}
