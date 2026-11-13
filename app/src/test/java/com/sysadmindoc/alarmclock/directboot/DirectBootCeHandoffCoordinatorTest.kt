package com.sysadmindoc.alarmclock.directboot

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.sysadmindoc.alarmclock.data.local.AlarmDatabase
import com.sysadmindoc.alarmclock.data.model.Alarm
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException
import java.lang.reflect.Modifier
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Comprehensive RED/GREEN specification suite for [DirectBootCeHandoffCoordinator]
 * and [DirectBootCeTakeoverStore] (KNO-75 Phase D3-C2-P3D-P1 & P3D-P2B: C01-C26, A01-A30, X01-X10).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class DirectBootCeHandoffCoordinatorTest {

    private lateinit var ceContext: Context
    private lateinit var deContext: Context
    private lateinit var database: AlarmDatabase
    private val baseTime = 1_700_000_000_000L

    @Before
    fun setUp() {
        val appContext = ApplicationProvider.getApplicationContext<Context>()
        ceContext = appContext
        deContext = appContext.createDeviceProtectedStorageContext()
        DirectBootCeTakeoverStore.resetSeamsForTesting()
        DirectBootDeliveryLedger.resetSeamsForTesting()
        DirectBootMissedStore.resetSeamsForTesting()

        // Clean CE Takeover Store
        ceContext.getSharedPreferences(DirectBootCeTakeoverStore.PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .remove(DirectBootCeTakeoverStore.KEY_CE_TAKEOVER_PAYLOAD)
            .commit()

        // Clean DE Delivery Ledger & Missed Store & Queue
        deContext.getSharedPreferences(DirectBootDeliveryLedger.PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .remove(DirectBootDeliveryLedger.KEY_DELIVERY_LEDGER_PAYLOAD)
            .remove(DirectBootMissedStore.KEY_MISSED_STORE_PAYLOAD)
            .remove(DirectBootAlarmCache.KEY_QUEUE_PAYLOAD)
            .commit()

        database = Room.inMemoryDatabaseBuilder(appContext, AlarmDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() {
        DirectBootCeTakeoverStore.resetSeamsForTesting()
        DirectBootDeliveryLedger.resetSeamsForTesting()
        DirectBootMissedStore.resetSeamsForTesting()
        database.close()
    }

    private fun seedDeDelivered(
        alarmId: Long = 100L,
        triggerTime: Long = baseTime + 60_000L,
        deliveredAt: Long = baseTime + 60_500L,
        generation: Int = 1,
        instanceToken: String = "inst_$alarmId",
        provenance: String = "NORMAL_DISPATCH"
    ): DirectBootDeliveryRecord {
        val record = DirectBootDeliveryRecord.createNormal(
            alarmId = alarmId,
            triggerTime = triggerTime,
            deliveredAt = deliveredAt,
            generation = generation,
            instanceToken = instanceToken,
            provenance = provenance
        )
        val res = DirectBootDeliveryLedger.appendRecord(deContext, record)
        assertTrue("Precondition failed: could not seed DE Delivery Ledger: $res", res is DeliveryAppendResult.Appended)
        return record
    }

    private fun seedDeMissed(
        alarmId: Long = 200L,
        triggerTime: Long = baseTime + 120_000L,
        missedAt: Long = baseTime + 900_000L,
        generation: Int = 1,
        instanceToken: String = "inst_$alarmId",
        classification: String = DirectBootMissedRecord.CLASSIFICATION_OVERDUE
    ): DirectBootMissedRecord {
        val record = DirectBootMissedRecord.createNormal(
            alarmId = alarmId,
            triggerTime = triggerTime,
            missedAt = missedAt,
            generation = generation,
            instanceToken = instanceToken,
            classification = classification
        )
        val res = DirectBootMissedStore.recordMissed(deContext, record)
        assertTrue("Precondition failed: could not seed DE Missed Store: $res", res is MissedRecordResult.Recorded)
        return record
    }

    // C01: CE Locked拒否
    @Test
    fun c01_ceLocked_rejectedWithoutWritingCeOrMutatingDe() {
        val deDelivered = seedDeDelivered(alarmId = 1L)
        val result = DirectBootCeHandoffCoordinator.handoffDelivered(
            deContext = deContext,
            ceContext = ceContext,
            candidateRecord = deDelivered,
            recordedAtEpochMs = baseTime + 70_000L,
            unlockGate = { false }
        )
        assertTrue("Expected Rejected.CeLocked but got $result", result is CeHandoffResult.Rejected.CeLocked)
        assertTrue(result.isGuaranteedNoCeWrite)
        assertFalse(result.ceWriteAttempted)
        assertTrue(result.deRecordPreserved)
        assertFalse(result.deAckEligible)

        val ceRaw = ceContext.getSharedPreferences(DirectBootCeTakeoverStore.PREFS_NAME, Context.MODE_PRIVATE)
            .getString(DirectBootCeTakeoverStore.KEY_CE_TAKEOVER_PAYLOAD, null)
        assertNull("CE store must remain untouched when locked", ceRaw)

        val deState = DirectBootDeliveryLedger.inspectStorageState(deContext)
        assertTrue(deState is LedgerStorageState.ValidWithRecords)
        assertEquals(1, (deState as LedgerStorageState.ValidWithRecords).records.size)
    }

    // C02: DE Context誤使用拒否
    @Test
    fun c02_deContextPassedAsCeContext_rejectedFailClosed() {
        val deMissed = seedDeMissed(alarmId = 2L)
        val result = DirectBootCeHandoffCoordinator.handoffMissed(
            deContext = deContext,
            ceContext = deContext, // Intentional misuse: passing DE context as CE context
            candidateRecord = deMissed,
            recordedAtEpochMs = baseTime + 910_000L,
            unlockGate = { true }
        )
        assertTrue("Expected Rejected.CeContextInvalid but got $result", result is CeHandoffResult.Rejected.CeContextInvalid)
        assertTrue(result.isGuaranteedNoCeWrite)
        assertFalse(result.ceWriteAttempted)
    }

    // C03: Valid Delivered intake
    @Test
    fun c03_validDeliveredIntake_persistsToCeAndVerifiesReadback() {
        val deDelivered = seedDeDelivered(alarmId = 3L, generation = 2, instanceToken = "tok_3")
        val result = DirectBootCeHandoffCoordinator.handoffDelivered(
            deContext = deContext,
            ceContext = ceContext,
            candidateRecord = deDelivered,
            recordedAtEpochMs = baseTime + 70_000L,
            unlockGate = { true }
        )
        assertTrue("Expected Confirmed but got $result", result is CeHandoffResult.Confirmed)
        val confirmed = result as CeHandoffResult.Confirmed
        assertFalse(confirmed.alreadyRecordedInCe)
        assertTrue(confirmed.writePerformed)
        assertTrue(confirmed.deRecordPreserved)
        assertFalse(confirmed.deAckEligible)
        assertTrue(confirmed.ceRecord is CeTakeoverRecord.Delivered)
        val ceDel = confirmed.ceRecord as CeTakeoverRecord.Delivered
        assertEquals(3L, ceDel.alarmId)
        assertEquals(2, ceDel.generation)
        assertEquals("tok_3", ceDel.instanceToken)
        assertEquals(deDelivered.recordId, ceDel.sourceRecordId)
    }

    // C04: Valid Missed intake
    @Test
    fun c04_validMissedIntake_persistsToCeAndVerifiesReadback() {
        val deMissed = seedDeMissed(
            alarmId = 4L,
            generation = 3,
            instanceToken = "tok_4",
            classification = DirectBootMissedRecord.CLASSIFICATION_COLD_BOOT
        )
        val result = DirectBootCeHandoffCoordinator.handoffMissed(
            deContext = deContext,
            ceContext = ceContext,
            candidateRecord = deMissed,
            recordedAtEpochMs = baseTime + 910_000L,
            unlockGate = { true }
        )
        assertTrue("Expected Confirmed but got $result", result is CeHandoffResult.Confirmed)
        val confirmed = result as CeHandoffResult.Confirmed
        assertFalse(confirmed.alreadyRecordedInCe)
        assertTrue(confirmed.writePerformed)
        assertTrue(confirmed.deRecordPreserved)
        assertFalse(confirmed.deAckEligible)
        assertTrue(confirmed.ceRecord is CeTakeoverRecord.Missed)
        val ceMis = confirmed.ceRecord as CeTakeoverRecord.Missed
        assertEquals(4L, ceMis.alarmId)
        assertEquals(3, ceMis.generation)
        assertEquals("tok_4", ceMis.instanceToken)
        assertEquals(DirectBootMissedRecord.CLASSIFICATION_COLD_BOOT, ceMis.classification)
        assertEquals(deMissed.recordId, ceMis.sourceRecordId)
    }

    // C05: 同一Identity再実行は冪等
    @Test
    fun c05_sameIdentityReExecution_isIdempotent() {
        val deMissed = seedDeMissed(alarmId = 5L)
        val first = DirectBootCeHandoffCoordinator.handoffMissed(
            deContext = deContext,
            ceContext = ceContext,
            candidateRecord = deMissed,
            recordedAtEpochMs = baseTime + 910_000L,
            unlockGate = { true }
        )
        assertTrue(first is CeHandoffResult.Confirmed)

        val second = DirectBootCeHandoffCoordinator.handoffMissed(
            deContext = deContext,
            ceContext = ceContext,
            candidateRecord = deMissed,
            recordedAtEpochMs = baseTime + 920_000L,
            unlockGate = { true }
        )
        assertTrue("Expected Confirmed on idempotent retry but got $second", second is CeHandoffResult.Confirmed)
        val confirmed2 = second as CeHandoffResult.Confirmed
        assertTrue(confirmed2.alreadyRecordedInCe)
        assertFalse(confirmed2.writePerformed)
        assertEquals(1, confirmed2.totalCeRecords)
    }

    // C06: 同一Identity内容不一致
    @Test
    fun c06_sameIdentityContentMismatch_rejectedWithConflict() {
        val deDelivered = seedDeDelivered(alarmId = 6L, deliveredAt = baseTime + 60_500L, provenance = "NORMAL_DISPATCH")
        val first = DirectBootCeHandoffCoordinator.handoffDelivered(
            deContext = deContext,
            ceContext = ceContext,
            candidateRecord = deDelivered,
            recordedAtEpochMs = baseTime + 70_000L,
            unlockGate = { true }
        )
        assertTrue(first is CeHandoffResult.Confirmed)

        // Mutate DE ledger to hold a different deliveredAt for the same occurrence identity
        deContext.getSharedPreferences(DirectBootDeliveryLedger.PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .remove(DirectBootDeliveryLedger.KEY_DELIVERY_LEDGER_PAYLOAD)
            .commit()
        val conflictingDeDelivered = seedDeDelivered(
            alarmId = 6L,
            deliveredAt = baseTime + 69_999L,
            provenance = "QUALIFIED_RECOVERY"
        )

        val second = DirectBootCeHandoffCoordinator.handoffDelivered(
            deContext = deContext,
            ceContext = ceContext,
            candidateRecord = conflictingDeDelivered,
            recordedAtEpochMs = baseTime + 80_000L,
            unlockGate = { true }
        )
        assertTrue("Expected Rejected.CeContentConflict but got $second", second is CeHandoffResult.Rejected.CeContentConflict)
        assertTrue(second.isGuaranteedNoCeWrite)
    }

    // C07: Delivered/Missed競合
    @Test
    fun c07_deliveredAndMissedMutualExclusion_rejectedOnDeOrCeConflict() {
        // Part A: Same occurrence present in BOTH DE Ledger and DE Missed Store -> DeCrossStoreConflict
        val deDel = seedDeDelivered(alarmId = 7L, triggerTime = baseTime + 60_000L, generation = 1, instanceToken = "inst_7")
        val deMis = seedDeMissed(alarmId = 7L, triggerTime = baseTime + 60_000L, generation = 1, instanceToken = "inst_7")

        val crossDeResult = DirectBootCeHandoffCoordinator.handoffDelivered(
            deContext = deContext,
            ceContext = ceContext,
            candidateRecord = deDel,
            recordedAtEpochMs = baseTime + 70_000L,
            unlockGate = { true }
        )
        assertTrue("Expected DeCrossStoreConflict but got $crossDeResult", crossDeResult is CeHandoffResult.Rejected.DeCrossStoreConflict)

        // Part B: Delivered already in CE, then DE only has Missed for same occurrence -> CeDeliveredMissedConflict
        deContext.getSharedPreferences(DirectBootDeliveryLedger.PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .remove(DirectBootMissedStore.KEY_MISSED_STORE_PAYLOAD)
            .commit()
        val delSuccess = DirectBootCeHandoffCoordinator.handoffDelivered(
            deContext = deContext,
            ceContext = ceContext,
            candidateRecord = deDel,
            recordedAtEpochMs = baseTime + 70_000L,
            unlockGate = { true }
        )
        assertTrue(delSuccess is CeHandoffResult.Confirmed)

        // Replace DE Ledger with DE Missed Store for the same occurrence
        deContext.getSharedPreferences(DirectBootDeliveryLedger.PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .remove(DirectBootDeliveryLedger.KEY_DELIVERY_LEDGER_PAYLOAD)
            .commit()
        seedDeMissed(alarmId = 7L, triggerTime = baseTime + 60_000L, generation = 1, instanceToken = "inst_7")

        val ceConflict = DirectBootCeHandoffCoordinator.handoffMissed(
            deContext = deContext,
            ceContext = ceContext,
            candidateRecord = deMis,
            recordedAtEpochMs = baseTime + 910_000L,
            unlockGate = { true }
        )
        assertTrue("Expected CeDeliveredMissedConflict but got $ceConflict", ceConflict is CeHandoffResult.Rejected.CeDeliveredMissedConflict)
    }

    // C08: Corrupt CE
    @Test
    fun c08_corruptCeStorage_failsClosedAndPreservesRawPayloads() {
        val deDelivered = seedDeDelivered(alarmId = 8L)
        val cePrefs = ceContext.getSharedPreferences(DirectBootCeTakeoverStore.PREFS_NAME, Context.MODE_PRIVATE)
        val corruptJson = "{\"schemaVersion\":1,\"records\":[\"not_an_object\"]}"
        cePrefs.edit().putString(DirectBootCeTakeoverStore.KEY_CE_TAKEOVER_PAYLOAD, corruptJson).commit()

        val result = DirectBootCeHandoffCoordinator.handoffDelivered(
            deContext = deContext,
            ceContext = ceContext,
            candidateRecord = deDelivered,
            recordedAtEpochMs = baseTime + 70_000L,
            unlockGate = { true }
        )
        assertTrue("Expected CeStorageCorrupt but got $result", result is CeHandoffResult.Rejected.CeStorageCorrupt)
        assertEquals(corruptJson, cePrefs.getString(DirectBootCeTakeoverStore.KEY_CE_TAKEOVER_PAYLOAD, null))
    }

    // C09: Unsupported Schema
    @Test
    fun c09_unsupportedCeSchema_failsClosedAndPreservesRawPayloads() {
        val deMissed = seedDeMissed(alarmId = 9L)
        val cePrefs = ceContext.getSharedPreferences(DirectBootCeTakeoverStore.PREFS_NAME, Context.MODE_PRIVATE)
        val unsupportedJson = JSONObject().apply {
            put("schemaVersion", 999)
            put("records", JSONArray())
        }.toString()
        cePrefs.edit().putString(DirectBootCeTakeoverStore.KEY_CE_TAKEOVER_PAYLOAD, unsupportedJson).commit()

        val result = DirectBootCeHandoffCoordinator.handoffMissed(
            deContext = deContext,
            ceContext = ceContext,
            candidateRecord = deMissed,
            recordedAtEpochMs = baseTime + 910_000L,
            unlockGate = { true }
        )
        assertTrue("Expected CeUnsupportedSchema but got $result", result is CeHandoffResult.Rejected.CeUnsupportedSchema)
        assertEquals(unsupportedJson, cePrefs.getString(DirectBootCeTakeoverStore.KEY_CE_TAKEOVER_PAYLOAD, null))
    }

    // C10: Commit false
    @Test
    fun c10_ceCommitFalse_returnsUncertainAndPreservesDe() {
        val deDelivered = seedDeDelivered(alarmId = 10L)
        var commitCalls = 0
        DirectBootCeTakeoverStore.commitSeam = { _, _ ->
            commitCalls++
            false
        }

        val result = DirectBootCeHandoffCoordinator.handoffDelivered(
            deContext = deContext,
            ceContext = ceContext,
            candidateRecord = deDelivered,
            recordedAtEpochMs = baseTime + 70_000L,
            unlockGate = { true }
        )
        assertEquals("commitSeam must have been invoked", 1, commitCalls)
        assertTrue("Expected Uncertain.CeCommitFailed but got $result", result is CeHandoffResult.Uncertain.CeCommitFailed)
        assertTrue(result.deRecordPreserved)
        assertFalse(result.deAckEligible)

        val deState = DirectBootDeliveryLedger.inspectStorageState(deContext)
        assertEquals(1, (deState as LedgerStorageState.ValidWithRecords).records.size)
    }

    // C11: Commit exception
    @Test
    fun c11_ceCommitException_returnsUncertainAndPreservesDe() {
        val deMissed = seedDeMissed(alarmId = 11L)
        DirectBootCeTakeoverStore.commitSeam = { _, _ -> throw IOException("simulated I/O failure") }

        val result = DirectBootCeHandoffCoordinator.handoffMissed(
            deContext = deContext,
            ceContext = ceContext,
            candidateRecord = deMissed,
            recordedAtEpochMs = baseTime + 910_000L,
            unlockGate = { true }
        )
        assertTrue("Expected Uncertain.CeCommitException but got $result", result is CeHandoffResult.Uncertain.CeCommitException)
        assertTrue(result.deRecordPreserved)

        val deState = DirectBootMissedStore.inspectStorageState(deContext)
        assertEquals(1, (deState as MissedStoreStorageState.ValidWithRecords).records.size)
    }

    // C12: Commit後読戻し不一致
    @Test
    fun c12_postCommitReadbackMismatch_neverReturnsConfirmed() {
        val deDelivered = seedDeDelivered(alarmId = 12L)
        // Commit reports true, but tampered post-commit storage has a different deliveredAt
        val tampered = CeTakeoverRecord.fromDeliveryRecord(
            deDelivered.copy(deliveredAt = deDelivered.deliveredAt + 5_000L),
            recordedAtEpochMs = baseTime + 70_000L
        )
        val tamperedPayload = JSONObject().apply {
            put("schemaVersion", DirectBootCeTakeoverStore.CURRENT_SCHEMA_VERSION)
            put("records", JSONArray().put(tampered.toJson()))
        }.toString()
        DirectBootCeTakeoverStore.commitSeam = { prefs, _ ->
            prefs.edit().putString(DirectBootCeTakeoverStore.KEY_CE_TAKEOVER_PAYLOAD, tamperedPayload).commit()
        }

        val result = DirectBootCeHandoffCoordinator.handoffDelivered(
            deContext = deContext,
            ceContext = ceContext,
            candidateRecord = deDelivered,
            recordedAtEpochMs = baseTime + 70_000L,
            unlockGate = { true }
        )
        assertTrue("Expected Uncertain.CeReadbackFailed but got $result", result is CeHandoffResult.Uncertain.CeReadbackFailed)
        assertFalse(result is CeHandoffResult.Confirmed)
    }

    // C13: Commit成功後例外
    @Test
    fun c13_exceptionAfterConfirmedReadback_returnsPostCommitFailureNotUncertain() {
        val deMissed = seedDeMissed(alarmId = 13L)
        val result = DirectBootCeHandoffCoordinator.handoffMissed(
            deContext = deContext,
            ceContext = ceContext,
            candidateRecord = deMissed,
            recordedAtEpochMs = baseTime + 910_000L,
            unlockGate = { true },
            postCommitHook = { throw IllegalStateException("post-commit telemetry error") }
        )
        assertTrue("Expected PostCommitFailure but got $result", result is CeHandoffResult.PostCommitFailure)
        val postFail = result as CeHandoffResult.PostCommitFailure
        assertEquals(13L, postFail.confirmedCeRecord.alarmId)
        assertEquals(1, postFail.totalCeRecords)
    }

    // C14: CE容量上限 (32 records provisional cap)
    @Test
    fun c14_ceCapacityLimit32_blocks33rdRecordWithoutEvictingExisting() {
        // Seed 16 Delivered + 16 Missed into CE Takeover Store (= 32 total)
        for (i in 1..16) {
            val del = seedDeDelivered(alarmId = i.toLong(), triggerTime = baseTime + i * 10_000L)
            val rDel = DirectBootCeHandoffCoordinator.handoffDelivered(
                deContext = deContext,
                ceContext = ceContext,
                candidateRecord = del,
                recordedAtEpochMs = baseTime + 500_000L,
                unlockGate = { true }
            )
            assertTrue("Expected Confirmed at Delivered $i but got $rDel", rDel is CeHandoffResult.Confirmed)
        }
        for (i in 17..32) {
            val mis = seedDeMissed(alarmId = i.toLong(), triggerTime = baseTime + i * 10_000L)
            val rMis = DirectBootCeHandoffCoordinator.handoffMissed(
                deContext = deContext,
                ceContext = ceContext,
                candidateRecord = mis,
                recordedAtEpochMs = baseTime + 900_000L,
                unlockGate = { true }
            )
            assertTrue("Expected Confirmed at Missed $i but got $rMis", rMis is CeHandoffResult.Confirmed)
        }

        // Clear DE stores so we can add a 33rd record in DE
        deContext.getSharedPreferences(DirectBootDeliveryLedger.PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .remove(DirectBootDeliveryLedger.KEY_DELIVERY_LEDGER_PAYLOAD)
            .remove(DirectBootMissedStore.KEY_MISSED_STORE_PAYLOAD)
            .commit()

        val record33 = seedDeMissed(alarmId = 33L, triggerTime = baseTime + 330_000L)
        val res33 = DirectBootCeHandoffCoordinator.handoffMissed(
            deContext = deContext,
            ceContext = ceContext,
            candidateRecord = record33,
            recordedAtEpochMs = baseTime + 950_000L,
            unlockGate = { true }
        )
        assertTrue("Expected CeCapacityBlocked at 33rd record but got $res33", res33 is CeHandoffResult.Rejected.CeCapacityBlocked)
        val blocked = res33 as CeHandoffResult.Rejected.CeCapacityBlocked
        assertEquals(32, blocked.currentCount)
        assertEquals(32, blocked.provisionalMaxCapacity)

        val ceState = DirectBootCeTakeoverStore.inspectStorageState(ceContext, unlockGate = { true })
        assertTrue(ceState is CeTakeoverStorageState.ValidWithRecords)
        val ceRecords = (ceState as CeTakeoverStorageState.ValidWithRecords).records
        assertEquals(32, ceRecords.size)
        assertFalse(ceRecords.any { it.alarmId == 33L })
    }

    // C15: 容量超過時DE保持
    @Test
    fun c15_whenCeCapacityBlocked_deRecordRemainsPreserved() {
        for (i in 1..16) {
            val del = seedDeDelivered(alarmId = i.toLong(), triggerTime = baseTime + i * 10_000L)
            DirectBootCeHandoffCoordinator.handoffDelivered(deContext, ceContext, del, baseTime + 500_000L, { true })
        }
        for (i in 17..32) {
            val mis = seedDeMissed(alarmId = i.toLong(), triggerTime = baseTime + i * 10_000L)
            DirectBootCeHandoffCoordinator.handoffMissed(deContext, ceContext, mis, baseTime + 900_000L, { true })
        }

        deContext.getSharedPreferences(DirectBootDeliveryLedger.PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .remove(DirectBootMissedStore.KEY_MISSED_STORE_PAYLOAD)
            .commit()

        val record33 = seedDeMissed(alarmId = 99L, triggerTime = baseTime + 990_000L)
        val res33 = DirectBootCeHandoffCoordinator.handoffMissed(
            deContext = deContext,
            ceContext = ceContext,
            candidateRecord = record33,
            recordedAtEpochMs = baseTime + 995_000L,
            unlockGate = { true }
        )
        assertTrue(res33 is CeHandoffResult.Rejected.CeCapacityBlocked)
        assertTrue(res33.deRecordPreserved)

        val deState = DirectBootMissedStore.inspectStorageState(deContext)
        assertTrue(deState is MissedStoreStorageState.ValidWithRecords)
        val deRecords = (deState as MissedStoreStorageState.ValidWithRecords).records
        assertEquals(1, deRecords.size)
        assertEquals(record33, deRecords.first())
    }

    // C16: 異なるRecordの分離
    @Test
    fun c16_distinctOccurrencesByAlarmGenTokenOrTrigger_storedIndependently() {
        val r1 = seedDeDelivered(alarmId = 16L, triggerTime = baseTime + 10_000L, generation = 1, instanceToken = "tokA")
        val r2 = seedDeDelivered(alarmId = 16L, triggerTime = baseTime + 10_000L, generation = 2, instanceToken = "tokA")
        val r3 = seedDeDelivered(alarmId = 16L, triggerTime = baseTime + 10_000L, generation = 2, instanceToken = "tokB")
        val r4 = seedDeDelivered(alarmId = 16L, triggerTime = baseTime + 20_000L, generation = 2, instanceToken = "tokB")

        for (r in listOf(r1, r2, r3, r4)) {
            val res = DirectBootCeHandoffCoordinator.handoffDelivered(deContext, ceContext, r, baseTime + 50_000L, { true })
            assertTrue("Expected Confirmed for $r but got $res", res is CeHandoffResult.Confirmed)
        }

        val ceState = DirectBootCeTakeoverStore.inspectStorageState(ceContext, unlockGate = { true })
        assertTrue(ceState is CeTakeoverStorageState.ValidWithRecords)
        assertEquals(4, (ceState as CeTakeoverStorageState.ValidWithRecords).records.size)
    }

    // C17: 不正RecordId拒否
    @Test
    fun c17_forgedOrMismatchedRecordId_rejectedFailClosed() {
        val validDe = seedDeDelivered(alarmId = 17L, triggerTime = baseTime + 60_000L, generation = 1, instanceToken = "tok_17")
        val forged = validDe.copy(recordId = "delivery_NORMAL_999_gen1_tok_17_trig${baseTime + 60_000L}")

        val res = DirectBootCeHandoffCoordinator.handoffDelivered(
            deContext = deContext,
            ceContext = ceContext,
            candidateRecord = forged,
            recordedAtEpochMs = baseTime + 70_000L,
            unlockGate = { true }
        )
        assertTrue("Expected InvalidRecordOrIdentity but got $res", res is CeHandoffResult.Rejected.InvalidRecordOrIdentity)
        assertTrue(res.isGuaranteedNoCeWrite)
    }

    // C18: 不正generation/token
    @Test
    fun c18_invalidGenerationOrBlankInstanceToken_rejectedFailClosed() {
        val badGen = DirectBootMissedRecord(
            recordId = "missed_normal_18_0_tok18_${baseTime + 60_000L}",
            kind = DirectBootMissedRecord.KIND_NORMAL,
            alarmId = 18L,
            triggerTime = baseTime + 60_000L,
            missedAt = baseTime + 900_000L,
            generation = 0,
            instanceToken = "tok18",
            classification = DirectBootMissedRecord.CLASSIFICATION_OVERDUE
        )
        val resGen = DirectBootCeHandoffCoordinator.handoffMissed(
            deContext = deContext,
            ceContext = ceContext,
            candidateRecord = badGen,
            recordedAtEpochMs = baseTime + 910_000L,
            unlockGate = { true }
        )
        assertTrue("Expected InvalidRecordOrIdentity for generation=0 but got $resGen", resGen is CeHandoffResult.Rejected.InvalidRecordOrIdentity)

        val badToken = DirectBootDeliveryRecord(
            recordId = "delivery_NORMAL_18_gen1__trig${baseTime + 60_000L}",
            kind = DirectBootDeliveryRecord.KIND_NORMAL,
            alarmId = 18L,
            triggerTime = baseTime + 60_000L,
            deliveredAt = baseTime + 61_000L,
            generation = 1,
            instanceToken = "   "
        )
        val resTok = DirectBootCeHandoffCoordinator.handoffDelivered(
            deContext = deContext,
            ceContext = ceContext,
            candidateRecord = badToken,
            recordedAtEpochMs = baseTime + 70_000L,
            unlockGate = { true }
        )
        assertTrue("Expected InvalidRecordOrIdentity for blank token but got $resTok", resTok is CeHandoffResult.Rejected.InvalidRecordOrIdentity)
    }

    // C19: 再実行で重複書込み無し
    @Test
    fun c19_reExecutionProducesZeroAdditionalStorageCommits() {
        val commitCounter = AtomicInteger(0)
        DirectBootCeTakeoverStore.commitSeam = { prefs, payload ->
            commitCounter.incrementAndGet()
            prefs.edit().putString(DirectBootCeTakeoverStore.KEY_CE_TAKEOVER_PAYLOAD, payload).commit()
        }

        val deDelivered = seedDeDelivered(alarmId = 19L)
        val r1 = DirectBootCeHandoffCoordinator.handoffDelivered(deContext, ceContext, deDelivered, baseTime + 70_000L, { true })
        assertTrue(r1 is CeHandoffResult.Confirmed)
        assertEquals(1, commitCounter.get())

        val r2 = DirectBootCeHandoffCoordinator.handoffDelivered(deContext, ceContext, deDelivered, baseTime + 80_000L, { true })
        assertTrue(r2 is CeHandoffResult.Confirmed)
        assertEquals("Re-execution must perform 0 additional commits", 1, commitCounter.get())
    }

    // C20: DE ACKが発生しない
    @Test
    fun c20_deAckNeverOccursInP1_andMissedStoreRemainsAckNotQualified() {
        val deDelivered = seedDeDelivered(alarmId = 20L)
        val deMissed = seedDeMissed(alarmId = 21L)

        val rDel = DirectBootCeHandoffCoordinator.handoffDelivered(deContext, ceContext, deDelivered, baseTime + 70_000L, { true })
        val rMis = DirectBootCeHandoffCoordinator.handoffMissed(deContext, ceContext, deMissed, baseTime + 910_000L, { true })
        assertTrue(rDel is CeHandoffResult.Confirmed)
        assertTrue(rMis is CeHandoffResult.Confirmed)
        assertFalse(rDel.deAckEligible)
        assertFalse(rMis.deAckEligible)

        val ledgerState = DirectBootDeliveryLedger.inspectStorageState(deContext)
        assertEquals(1, (ledgerState as LedgerStorageState.ValidWithRecords).records.size)

        val missedState = DirectBootMissedStore.inspectStorageState(deContext)
        assertEquals(1, (missedState as MissedStoreStorageState.ValidWithRecords).records.size)

        val ackAttempt = DirectBootMissedStore.acknowledgeRecord(deContext, deMissed.recordId)
        assertTrue("DirectBootMissedStore ACK must remain AckNotQualified", ackAttempt is MissedAckResult.AckNotQualified)
    }

    // C21: Room Session生成無し
    @Test
    fun c21_noRoomSessionOrTombstoneCreated() = runBlocking {
        val deDelivered = seedDeDelivered(alarmId = 21L)
        val res = DirectBootCeHandoffCoordinator.handoffDelivered(deContext, ceContext, deDelivered, baseTime + 70_000L, { true })
        assertTrue(res is CeHandoffResult.Confirmed)

        val activeSessions = database.alarmSessionDao().getActiveSessionsForAlarm(21L)
        assertTrue("No AlarmSessionEntity may be created in P1", activeSessions.isEmpty())
        val occKey = "alarm_21_gen_1_base_${deDelivered.triggerTime}"
        val tombstone = database.alarmSessionDao().getTombstoneByAdmissionKey("admission_21_$occKey")
        assertNull("No AlarmAdmissionTombstoneEntity may be created in P1", tombstone)
    }

    // C22: AlarmEvent挿入無し
    @Test
    fun c22_noAlarmEventInserted() = runBlocking {
        val deMissed = seedDeMissed(alarmId = 22L)
        val res = DirectBootCeHandoffCoordinator.handoffMissed(deContext, ceContext, deMissed, baseTime + 910_000L, { true })
        assertTrue(res is CeHandoffResult.Confirmed)

        val totalEvents = database.alarmEventDao().count()
        assertEquals("Zero AlarmEvent rows may be inserted in P1", 0, totalEvents)
    }

    // C23: ONE_TIME Alarmの有効状態不変
    @Test
    fun c23_oneTimeAlarmEnabledStateRemainsInvariant() = runBlocking {
        val oneTimeAlarm = Alarm(
            id = 23L,
            hour = 7,
            minute = 0,
            isEnabled = true,
            kind = Alarm.KIND_NORMAL,
            scheduleKind = Alarm.SCHEDULE_KIND_ONE_TIME,
            generation = 1,
            revision = 1,
            nextTriggerTime = baseTime + 60_000L
        )
        database.alarmDao().insert(oneTimeAlarm)

        val deDelivered = seedDeDelivered(alarmId = 23L, triggerTime = baseTime + 60_000L, generation = 1, instanceToken = "inst_23")
        val res = DirectBootCeHandoffCoordinator.handoffDelivered(deContext, ceContext, deDelivered, baseTime + 70_000L, { true })
        assertTrue(res is CeHandoffResult.Confirmed)

        val persistedAlarm = database.alarmDao().getById(23L)
        assertTrue("ONE_TIME alarm must remain enabled (Delivery != Session COMPLETED)", persistedAlarm?.isEnabled == true)
        assertEquals(baseTime + 60_000L, persistedAlarm?.nextTriggerTime)
    }

    // C24: QUICKを未資格として拒否
    @Test
    fun c24_quickRecordRejectedAsNotQualified() {
        val quickRecord = DirectBootDeliveryRecord.createQuick(
            alarmId = 24L,
            triggerTime = baseTime + 60_000L,
            deliveredAt = baseTime + 61_000L
        )
        DirectBootDeliveryLedger.appendRecord(deContext, quickRecord)

        val res = DirectBootCeHandoffCoordinator.handoffDelivered(
            deContext = deContext,
            ceContext = ceContext,
            candidateRecord = quickRecord,
            recordedAtEpochMs = baseTime + 70_000L,
            unlockGate = { true }
        )
        assertTrue("Expected Rejected.QuickNotQualified but got $res", res is CeHandoffResult.Rejected.QuickNotQualified)
        assertTrue(res.isGuaranteedNoCeWrite)

        val quickIdentity = FiredOccurrenceIdentity(
            kind = DirectBootDeliveryRecord.KIND_QUICK,
            alarmId = 24L,
            triggerTime = baseTime + 60_000L,
            generation = 0,
            instanceToken = ""
        )
        val resById = DirectBootCeHandoffCoordinator.handoffByOccurrenceIdentity(
            deContext = deContext,
            ceContext = ceContext,
            identity = quickIdentity,
            recordedAtEpochMs = baseTime + 70_000L,
            unlockGate = { true }
        )
        assertTrue(resById is CeHandoffResult.Rejected.QuickNotQualified)
    }

    // C25: Process Death再試行
    @Test
    fun c25_processDeathAfterCommitBeforeAck_convergesOnRetry() {
        val deMissed = seedDeMissed(alarmId = 25L)

        // Simulate process death right after CE commit succeeds (inside postCommitHook)
        val firstAttempt = DirectBootCeHandoffCoordinator.handoffMissed(
            deContext = deContext,
            ceContext = ceContext,
            candidateRecord = deMissed,
            recordedAtEpochMs = baseTime + 910_000L,
            unlockGate = { true },
            postCommitHook = { throw RuntimeException("process killed right after commit") }
        )
        assertTrue(firstAttempt is CeHandoffResult.PostCommitFailure)

        // Next process startup retries the same DE Missed record
        val retryAttempt = DirectBootCeHandoffCoordinator.handoffMissed(
            deContext = deContext,
            ceContext = ceContext,
            candidateRecord = deMissed,
            recordedAtEpochMs = baseTime + 920_000L,
            unlockGate = { true }
        )
        assertTrue("Expected Confirmed on retry after process death but got $retryAttempt", retryAttempt is CeHandoffResult.Confirmed)
        val confirmed = retryAttempt as CeHandoffResult.Confirmed
        assertTrue(confirmed.alreadyRecordedInCe)
        assertFalse(confirmed.writePerformed)
        assertEquals(1, confirmed.totalCeRecords)
    }

    // C26: CE Record削除/消失後の読戻しはConfirmed禁止
    @Test
    fun c26_ceRecordDeletedOrMissingOnReadback_prohibitsConfirmed() {
        val deDelivered = seedDeDelivered(alarmId = 26L)

        // Simulate external wipe/disappearance of CE record during readback
        val emptyValidPayload = JSONObject().apply {
            put("schemaVersion", DirectBootCeTakeoverStore.CURRENT_SCHEMA_VERSION)
            put("records", JSONArray())
        }.toString()
        DirectBootCeTakeoverStore.readbackSeam = { emptyValidPayload }

        val res = DirectBootCeHandoffCoordinator.handoffDelivered(
            deContext = deContext,
            ceContext = ceContext,
            candidateRecord = deDelivered,
            recordedAtEpochMs = baseTime + 70_000L,
            unlockGate = { true }
        )
        assertTrue("Expected Uncertain.CeReadbackFailed when record disappears on readback, got $res", res is CeHandoffResult.Uncertain.CeReadbackFailed)
        assertFalse(res is CeHandoffResult.Confirmed)

        // Also verify direct verifyRecordReadableInCe fails when CE record is deleted
        DirectBootCeTakeoverStore.resetSeamsForTesting()
        val okRes = DirectBootCeHandoffCoordinator.handoffDelivered(deContext, ceContext, deDelivered, baseTime + 70_000L, { true })
        assertTrue(okRes is CeHandoffResult.Confirmed)
        val confirmedRecord = (okRes as CeHandoffResult.Confirmed).ceRecord

        // Now delete the CE payload externally
        ceContext.getSharedPreferences(DirectBootCeTakeoverStore.PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .remove(DirectBootCeTakeoverStore.KEY_CE_TAKEOVER_PAYLOAD)
            .commit()

        val verifyAfterDelete = DirectBootCeTakeoverStore.verifyRecordReadableInCe(
            ceContext = ceContext,
            expectedRecord = confirmedRecord,
            unlockGate = { true }
        )
        assertTrue("Verification after external CE deletion must be Unverified", verifyAfterDelete is CeReadbackVerificationResult.Unverified)
    }

    // X01: Raw Delivery ACKは削除不能 (CE証明なしまたはCE証明後でもraw acknowledgeRecordは削除不能)
    @Test
    fun x01_rawDeliveryAckCannotDeleteRecord() {
        val deDelivered = seedDeDelivered(alarmId = 101L)
        val rawBefore = DirectBootDeliveryLedger.getRawPayload(deContext)

        // Attempt raw ACK before CE handoff
        val rawAck1 = DirectBootDeliveryLedger.acknowledgeRecord(deContext, deDelivered.recordId)
        assertTrue("Raw Delivery ACK must return AckNotQualified, got $rawAck1", rawAck1 is DeliveryAckResult.AckNotQualified)
        assertEquals(rawBefore, DirectBootDeliveryLedger.getRawPayload(deContext))

        // Attempt raw ACK even after CE handoff
        val handoff = DirectBootCeHandoffCoordinator.handoffDelivered(deContext, ceContext, deDelivered, baseTime + 70_000L, { true })
        assertTrue(handoff is CeHandoffResult.Confirmed)
        val rawAck2 = DirectBootDeliveryLedger.acknowledgeRecord(deContext, deDelivered.recordId)
        assertTrue("Raw Delivery ACK must still return AckNotQualified, got $rawAck2", rawAck2 is DeliveryAckResult.AckNotQualified)
        assertEquals(rawBefore, DirectBootDeliveryLedger.getRawPayload(deContext))
    }

    // X02: Raw Missed ACKは削除不能
    @Test
    fun x02_rawMissedAckCannotDeleteRecord() {
        val deMissed = seedDeMissed(alarmId = 102L)
        val rawBefore = DirectBootMissedStore.getRawPayload(deContext)

        val rawAck1 = DirectBootMissedStore.acknowledgeRecord(deContext, deMissed.recordId)
        assertTrue("Raw Missed ACK must return AckNotQualified, got $rawAck1", rawAck1 is MissedAckResult.AckNotQualified)
        assertEquals(rawBefore, DirectBootMissedStore.getRawPayload(deContext))

        val handoff = DirectBootCeHandoffCoordinator.handoffMissed(deContext, ceContext, deMissed, baseTime + 910_000L, { true })
        assertTrue(handoff is CeHandoffResult.Confirmed)
        val rawAck2 = DirectBootMissedStore.acknowledgeRecord(deContext, deMissed.recordId)
        assertTrue("Raw Missed ACK must still return AckNotQualified, got $rawAck2", rawAck2 is MissedAckResult.AckNotQualified)
        assertEquals(rawBefore, DirectBootMissedStore.getRawPayload(deContext))
    }

    // X03: Coordinatorが正しいDE Lock (DirectBootStorageLock.lock) を取得
    @Test
    fun x03_coordinatorAcquiresCanonicalDirectBootStorageLockMonitor() {
        val deDelivered = seedDeDelivered(alarmId = 103L)
        val deMissed = seedDeMissed(alarmId = 104L)

        var deliveredHoldsCanonicalDeLock = false
        var deliveredHoldsCeLock = false
        val rDel = DirectBootCeHandoffCoordinator.handoffDelivered(
            deContext = deContext,
            ceContext = ceContext,
            candidateRecord = deDelivered,
            recordedAtEpochMs = baseTime + 70_000L,
            unlockGate = { true },
            preCommitHook = {
                deliveredHoldsCanonicalDeLock = Thread.holdsLock(DirectBootStorageLock.lock)
                deliveredHoldsCeLock = Thread.holdsLock(DirectBootCeTakeoverStore.ceStorageLock)
            }
        )
        assertTrue(rDel is CeHandoffResult.Confirmed)
        assertTrue("handoffDelivered MUST hold DirectBootStorageLock.lock", deliveredHoldsCanonicalDeLock)
        assertTrue("handoffDelivered MUST hold DirectBootCeTakeoverStore.ceStorageLock inside DE lock", deliveredHoldsCeLock)

        var missedHoldsCanonicalDeLock = false
        val rMis = DirectBootCeHandoffCoordinator.handoffMissed(
            deContext = deContext,
            ceContext = ceContext,
            candidateRecord = deMissed,
            recordedAtEpochMs = baseTime + 910_000L,
            unlockGate = { true },
            preCommitHook = {
                missedHoldsCanonicalDeLock = Thread.holdsLock(DirectBootStorageLock.lock)
            }
        )
        assertTrue(rMis is CeHandoffResult.Confirmed)
        assertTrue("handoffMissed MUST hold DirectBootStorageLock.lock", missedHoldsCanonicalDeLock)

        val deDelivered2 = seedDeDelivered(alarmId = 105L)
        var byIdentityHoldsCanonicalDeLock = false
        val rId = DirectBootCeHandoffCoordinator.handoffByOccurrenceIdentity(
            deContext = deContext,
            ceContext = ceContext,
            identity = FiredOccurrenceIdentity(
                kind = DirectBootDeliveryRecord.KIND_NORMAL,
                alarmId = deDelivered2.alarmId,
                triggerTime = deDelivered2.triggerTime,
                generation = deDelivered2.generation,
                instanceToken = deDelivered2.instanceToken
            ),
            recordedAtEpochMs = baseTime + 70_000L,
            unlockGate = { true },
            preCommitHook = {
                byIdentityHoldsCanonicalDeLock = Thread.holdsLock(DirectBootStorageLock.lock)
            }
        )
        assertTrue(rId is CeHandoffResult.Confirmed)
        assertTrue("handoffByOccurrenceIdentity MUST hold DirectBootStorageLock.lock", byIdentityHoldsCanonicalDeLock)
    }

    // =========================================================================
    // P3-D-P2B: Qualified ACK & Surgical Retirement Test Matrix (A01-A30, X04-X10)
    // =========================================================================

    // A01: Valid Delivered ACK
    @Test
    fun a01_validDeliveredAck_retiresOnlyTargetDeRecordAndPreservesCe() {
        val deDel = seedDeDelivered(alarmId = 201L)
        val handoff = DirectBootCeHandoffCoordinator.handoffDelivered(deContext, ceContext, deDel, baseTime + 70_000L, { true })
        assertTrue(handoff is CeHandoffResult.Confirmed)
        val receipt = handoff as CeHandoffResult.Confirmed

        val ack = DirectBootCeHandoffCoordinator.acknowledgeDeliveredWithCeProof(
            deContext = deContext,
            ceContext = ceContext,
            candidateRecord = deDel,
            confirmedReceipt = receipt,
            unlockGate = { true }
        )
        assertTrue("Expected AcknowledgedConfirmed but got $ack", ack is CeQualifiedAckResult.AcknowledgedConfirmed)
        val confirmedAck = ack as CeQualifiedAckResult.AcknowledgedConfirmed
        assertEquals(deDel.recordId, confirmedAck.retiredDeRecordId)
        assertEquals(0, confirmedAck.remainingDeCount)
        assertTrue(confirmedAck.writePerformed)
        assertTrue(confirmedAck.deRecordRetired)

        val deState = DirectBootDeliveryLedger.inspectStorageState(deContext)
        assertTrue("Expected ValidEmpty after last record ACK, got $deState", deState is LedgerStorageState.ValidEmpty)

        val ceState = DirectBootCeTakeoverStore.inspectStorageState(ceContext, unlockGate = { true })
        assertTrue(ceState is CeTakeoverStorageState.ValidWithRecords)
        assertEquals(1, (ceState as CeTakeoverStorageState.ValidWithRecords).records.size)
    }

    // A02: Valid Missed ACK
    @Test
    fun a02_validMissedAck_retiresOnlyTargetDeRecordAndPreservesCe() {
        val deMis = seedDeMissed(alarmId = 202L, classification = DirectBootMissedRecord.CLASSIFICATION_COLD_BOOT)
        val handoff = DirectBootCeHandoffCoordinator.handoffMissed(deContext, ceContext, deMis, baseTime + 910_000L, { true })
        assertTrue(handoff is CeHandoffResult.Confirmed)
        val receipt = handoff as CeHandoffResult.Confirmed

        val ack = DirectBootCeHandoffCoordinator.acknowledgeMissedWithCeProof(
            deContext = deContext,
            ceContext = ceContext,
            candidateRecord = deMis,
            confirmedReceipt = receipt,
            unlockGate = { true }
        )
        assertTrue("Expected AcknowledgedConfirmed but got $ack", ack is CeQualifiedAckResult.AcknowledgedConfirmed)
        val confirmedAck = ack as CeQualifiedAckResult.AcknowledgedConfirmed
        assertEquals(deMis.recordId, confirmedAck.retiredDeRecordId)
        assertEquals(0, confirmedAck.remainingDeCount)
        assertTrue(confirmedAck.writePerformed)

        val deState = DirectBootMissedStore.inspectStorageState(deContext)
        assertTrue("Expected ValidEmpty after last missed record ACK, got $deState", deState is MissedStoreStorageState.ValidEmpty)

        val ceState = DirectBootCeTakeoverStore.inspectStorageState(ceContext, unlockGate = { true })
        assertTrue(ceState is CeTakeoverStorageState.ValidWithRecords)
        assertEquals(1, (ceState as CeTakeoverStorageState.ValidWithRecords).records.size)
    }

    // A03: CE record missing
    @Test
    fun a03_ceRecordMissing_rejectedAndPreservesDeRecord() {
        val deDel = seedDeDelivered(alarmId = 203L)
        val rawBefore = DirectBootDeliveryLedger.getRawPayload(deContext)

        val ack = DirectBootCeHandoffCoordinator.acknowledgeDeliveredWithCeProof(
            deContext = deContext,
            ceContext = ceContext,
            candidateRecord = deDel,
            confirmedReceipt = null,
            unlockGate = { true }
        )
        assertTrue("Expected Rejected.CeEvidenceMissing but got $ack", ack is CeQualifiedAckResult.Rejected.CeEvidenceMissing)
        assertTrue(ack.isGuaranteedNoDeWrite)
        assertFalse(ack.deWriteAttempted)
        assertEquals(rawBefore, DirectBootDeliveryLedger.getRawPayload(deContext))
    }

    // A04: CE record payload mismatch
    @Test
    fun a04_ceRecordPayloadMismatch_rejectedAndPreservesDeRecord() {
        val deDel = seedDeDelivered(alarmId = 204L, deliveredAt = baseTime + 60_500L)
        // Seed CE with a different deliveredAt for the same occurrence
        val mismatchedDel = deDel.copy(deliveredAt = baseTime + 69_000L)
        val ceRes = DirectBootCeTakeoverStore.recordDelivered(ceContext, mismatchedDel, baseTime + 70_000L, { true })
        assertTrue(ceRes is CeTakeoverIntakeResult.RecordedConfirmed)

        val rawDeBefore = DirectBootDeliveryLedger.getRawPayload(deContext)
        val ack = DirectBootCeHandoffCoordinator.acknowledgeDeliveredWithCeProof(
            deContext = deContext,
            ceContext = ceContext,
            candidateRecord = deDel,
            confirmedReceipt = null,
            unlockGate = { true }
        )
        assertTrue("Expected Rejected.CePayloadMismatch but got $ack", ack is CeQualifiedAckResult.Rejected.CePayloadMismatch)
        assertEquals(rawDeBefore, DirectBootDeliveryLedger.getRawPayload(deContext))
    }

    // A05: CE corrupt
    @Test
    fun a05_ceCorrupt_rejectedFailClosedAndPreservesDeAndRawCe() {
        val deMis = seedDeMissed(alarmId = 205L)
        val cePrefs = ceContext.getSharedPreferences(DirectBootCeTakeoverStore.PREFS_NAME, Context.MODE_PRIVATE)
        val corruptCe = "{\"schemaVersion\":1,\"records\":[123]}"
        cePrefs.edit().putString(DirectBootCeTakeoverStore.KEY_CE_TAKEOVER_PAYLOAD, corruptCe).commit()
        val rawDeBefore = DirectBootMissedStore.getRawPayload(deContext)

        val ack = DirectBootCeHandoffCoordinator.acknowledgeMissedWithCeProof(
            deContext = deContext,
            ceContext = ceContext,
            candidateRecord = deMis,
            confirmedReceipt = null,
            unlockGate = { true }
        )
        assertTrue("Expected Rejected.CeStorageCorrupt but got $ack", ack is CeQualifiedAckResult.Rejected.CeStorageCorrupt)
        assertEquals(rawDeBefore, DirectBootMissedStore.getRawPayload(deContext))
        assertEquals(corruptCe, cePrefs.getString(DirectBootCeTakeoverStore.KEY_CE_TAKEOVER_PAYLOAD, null))
    }

    // A06: CE unsupported schema
    @Test
    fun a06_ceUnsupportedSchema_rejectedFailClosedAndPreservesDe() {
        val deDel = seedDeDelivered(alarmId = 206L)
        val cePrefs = ceContext.getSharedPreferences(DirectBootCeTakeoverStore.PREFS_NAME, Context.MODE_PRIVATE)
        val unsupportedCe = JSONObject().apply {
            put("schemaVersion", 99)
            put("records", JSONArray())
        }.toString()
        cePrefs.edit().putString(DirectBootCeTakeoverStore.KEY_CE_TAKEOVER_PAYLOAD, unsupportedCe).commit()
        val rawDeBefore = DirectBootDeliveryLedger.getRawPayload(deContext)

        val ack = DirectBootCeHandoffCoordinator.acknowledgeDeliveredWithCeProof(
            deContext = deContext,
            ceContext = ceContext,
            candidateRecord = deDel,
            confirmedReceipt = null,
            unlockGate = { true }
        )
        assertTrue("Expected Rejected.CeUnsupportedSchema but got $ack", ack is CeQualifiedAckResult.Rejected.CeUnsupportedSchema)
        assertEquals(rawDeBefore, DirectBootDeliveryLedger.getRawPayload(deContext))
    }

    // A07: CE locked
    @Test
    fun a07_ceLocked_rejectedAndPreservesDeRecord() {
        val deMis = seedDeMissed(alarmId = 207L)
        val handoff = DirectBootCeHandoffCoordinator.handoffMissed(deContext, ceContext, deMis, baseTime + 910_000L, { true })
        assertTrue(handoff is CeHandoffResult.Confirmed)
        val rawDeBefore = DirectBootMissedStore.getRawPayload(deContext)

        val ack = DirectBootCeHandoffCoordinator.acknowledgeMissedWithCeProof(
            deContext = deContext,
            ceContext = ceContext,
            candidateRecord = deMis,
            confirmedReceipt = handoff as CeHandoffResult.Confirmed,
            unlockGate = { false }
        )
        assertTrue("Expected Rejected.CeLocked but got $ack", ack is CeQualifiedAckResult.Rejected.CeLocked)
        assertEquals(rawDeBefore, DirectBootMissedStore.getRawPayload(deContext))
    }

    // A08: DE corrupt
    @Test
    fun a08_deCorrupt_rejectedFailClosedAndPreservesRawDe() {
        val deDel = seedDeDelivered(alarmId = 208L)
        val handoff = DirectBootCeHandoffCoordinator.handoffDelivered(deContext, ceContext, deDel, baseTime + 70_000L, { true })
        assertTrue(handoff is CeHandoffResult.Confirmed)

        val corruptDe = "{corrupt_de_json"
        deContext.getSharedPreferences(DirectBootDeliveryLedger.PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(DirectBootDeliveryLedger.KEY_DELIVERY_LEDGER_PAYLOAD, corruptDe)
            .commit()

        val ack = DirectBootCeHandoffCoordinator.acknowledgeDeliveredWithCeProof(
            deContext = deContext,
            ceContext = ceContext,
            candidateRecord = deDel,
            confirmedReceipt = handoff as CeHandoffResult.Confirmed,
            unlockGate = { true }
        )
        assertTrue("Expected Rejected.DeStorageCorrupt but got $ack", ack is CeQualifiedAckResult.Rejected.DeStorageCorrupt)
        assertEquals(corruptDe, DirectBootDeliveryLedger.getRawPayload(deContext))
    }

    // A09: DE unsupported schema
    @Test
    fun a09_deUnsupportedSchema_rejectedFailClosedAndPreservesRawDe() {
        val deMis = seedDeMissed(alarmId = 209L)
        val handoff = DirectBootCeHandoffCoordinator.handoffMissed(deContext, ceContext, deMis, baseTime + 910_000L, { true })
        assertTrue(handoff is CeHandoffResult.Confirmed)

        val unsupportedDe = JSONObject().apply {
            put("schemaVersion", 99)
            put("records", JSONArray())
        }.toString()
        deContext.getSharedPreferences(DirectBootDeliveryLedger.PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(DirectBootMissedStore.KEY_MISSED_STORE_PAYLOAD, unsupportedDe)
            .commit()

        val ack = DirectBootCeHandoffCoordinator.acknowledgeMissedWithCeProof(
            deContext = deContext,
            ceContext = ceContext,
            candidateRecord = deMis,
            confirmedReceipt = handoff as CeHandoffResult.Confirmed,
            unlockGate = { true }
        )
        assertTrue("Expected Rejected.DeUnsupportedSchema but got $ack", ack is CeQualifiedAckResult.Rejected.DeUnsupportedSchema)
        assertEquals(unsupportedDe, DirectBootMissedStore.getRawPayload(deContext))
    }

    // A10: Stale proof receipt
    @Test
    fun a10_staleProofReceipt_rejectedWhenCeRecordedAtDiffers() {
        val deDel = seedDeDelivered(alarmId = 210L)
        val handoff1 = DirectBootCeHandoffCoordinator.handoffDelivered(
            deContext = deContext,
            ceContext = ceContext,
            candidateRecord = deDel,
            recordedAtEpochMs = baseTime + 70_000L,
            unlockGate = { true }
        )
        assertTrue(handoff1 is CeHandoffResult.Confirmed)
        val staleReceipt = handoff1 as CeHandoffResult.Confirmed

        // Simulate CE wipe and re-handoff at a later recordedAtEpochMs (T2 != T1)
        ceContext.getSharedPreferences(DirectBootCeTakeoverStore.PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .remove(DirectBootCeTakeoverStore.KEY_CE_TAKEOVER_PAYLOAD)
            .commit()
        val handoff2 = DirectBootCeHandoffCoordinator.handoffDelivered(
            deContext = deContext,
            ceContext = ceContext,
            candidateRecord = deDel,
            recordedAtEpochMs = baseTime + 99_000L,
            unlockGate = { true }
        )
        assertTrue(handoff2 is CeHandoffResult.Confirmed)

        val rawDeBefore = DirectBootDeliveryLedger.getRawPayload(deContext)
        val ackWithStale = DirectBootCeHandoffCoordinator.acknowledgeDeliveredWithCeProof(
            deContext = deContext,
            ceContext = ceContext,
            candidateRecord = deDel,
            confirmedReceipt = staleReceipt,
            unlockGate = { true }
        )
        assertTrue("Expected Rejected.StaleProofRejected but got $ackWithStale", ackWithStale is CeQualifiedAckResult.Rejected.StaleProofRejected)
        assertEquals(rawDeBefore, DirectBootDeliveryLedger.getRawPayload(deContext))
    }

    // A11: Replay proof receipt
    @Test
    fun a11_replayProofReceipt_returnsAlreadyAcknowledgedWithZeroDeWrites() {
        val deDel = seedDeDelivered(alarmId = 211L)
        val handoff = DirectBootCeHandoffCoordinator.handoffDelivered(deContext, ceContext, deDel, baseTime + 70_000L, { true })
        val receipt = handoff as CeHandoffResult.Confirmed

        val deCommitCounter = AtomicInteger(0)
        DirectBootDeliveryLedger.commitSeam = { prefs, payload ->
            deCommitCounter.incrementAndGet()
            prefs.edit().putString(DirectBootDeliveryLedger.KEY_DELIVERY_LEDGER_PAYLOAD, payload).commit()
        }

        val ack1 = DirectBootCeHandoffCoordinator.acknowledgeDeliveredWithCeProof(
            deContext = deContext,
            ceContext = ceContext,
            candidateRecord = deDel,
            confirmedReceipt = receipt,
            unlockGate = { true }
        )
        assertTrue("Expected AcknowledgedConfirmed on 1st ACK, got $ack1", ack1 is CeQualifiedAckResult.AcknowledgedConfirmed)
        assertEquals(1, deCommitCounter.get())

        val ack2 = DirectBootCeHandoffCoordinator.acknowledgeDeliveredWithCeProof(
            deContext = deContext,
            ceContext = ceContext,
            candidateRecord = deDel,
            confirmedReceipt = receipt,
            unlockGate = { true }
        )
        assertTrue("Expected AlreadyAcknowledgedConfirmed on replay ACK, got $ack2", ack2 is CeQualifiedAckResult.AlreadyAcknowledgedConfirmed)
        assertEquals("Replay ACK must perform 0 additional DE commits", 1, deCommitCounter.get())
    }

    // A12: Cross-record proof receipt
    @Test
    fun a12_crossRecordProofReceipt_rejectedAndPreservesTargetDeRecord() {
        val deDel1 = seedDeDelivered(alarmId = 212L, instanceToken = "tok_212_a")
        val deDel2 = seedDeDelivered(alarmId = 213L, instanceToken = "tok_213_b")

        val handoff1 = DirectBootCeHandoffCoordinator.handoffDelivered(deContext, ceContext, deDel1, baseTime + 70_000L, { true })
        val handoff2 = DirectBootCeHandoffCoordinator.handoffDelivered(deContext, ceContext, deDel2, baseTime + 70_000L, { true })
        assertTrue(handoff1 is CeHandoffResult.Confirmed)
        assertTrue(handoff2 is CeHandoffResult.Confirmed)

        // Present receipt for deDel1 while requesting ACK for deDel2
        val crossAck = DirectBootCeHandoffCoordinator.acknowledgeDeliveredWithCeProof(
            deContext = deContext,
            ceContext = ceContext,
            candidateRecord = deDel2,
            confirmedReceipt = handoff1 as CeHandoffResult.Confirmed,
            unlockGate = { true }
        )
        assertTrue("Expected Rejected.ProofRecordMismatch but got $crossAck", crossAck is CeQualifiedAckResult.Rejected.ProofRecordMismatch)

        val deState = DirectBootDeliveryLedger.inspectStorageState(deContext) as LedgerStorageState.ValidWithRecords
        assertEquals(2, deState.records.size)
    }

    // A13: Cross-type proof
    @Test
    fun a13_crossTypeProof_rejectedWhenDeIsDeliveredAndCeIsMissed() {
        val deDel = seedDeDelivered(alarmId = 214L, triggerTime = baseTime + 60_000L, generation = 1, instanceToken = "tok_214")
        val matchingMis = DirectBootMissedRecord.createNormal(
            alarmId = 214L,
            triggerTime = baseTime + 60_000L,
            missedAt = baseTime + 900_000L,
            generation = 1,
            instanceToken = "tok_214",
            classification = DirectBootMissedRecord.CLASSIFICATION_OVERDUE
        )
        // Seed CE with Missed record for the same occurrence
        val ceRes = DirectBootCeTakeoverStore.recordMissed(ceContext, matchingMis, baseTime + 910_000L, { true })
        assertTrue(ceRes is CeTakeoverIntakeResult.RecordedConfirmed)

        val ack = DirectBootCeHandoffCoordinator.acknowledgeDeliveredWithCeProof(
            deContext = deContext,
            ceContext = ceContext,
            candidateRecord = deDel,
            confirmedReceipt = null,
            unlockGate = { true }
        )
        assertTrue("Expected Rejected.CrossTypeConflict but got $ack", ack is CeQualifiedAckResult.Rejected.CrossTypeConflict)

        val deState = DirectBootDeliveryLedger.inspectStorageState(deContext) as LedgerStorageState.ValidWithRecords
        assertEquals(1, deState.records.size)
    }

    // A14: DE record changed
    @Test
    fun a14_deRecordModifiedSinceHandoff_rejectedAndPreservesDeRecord() {
        val deDel = seedDeDelivered(alarmId = 215L, deliveredAt = baseTime + 60_500L)
        val handoff = DirectBootCeHandoffCoordinator.handoffDelivered(deContext, ceContext, deDel, baseTime + 70_000L, { true })
        assertTrue(handoff is CeHandoffResult.Confirmed)

        // Mutate persisted DE record in place to have a different deliveredAt
        val modifiedDe = deDel.copy(deliveredAt = baseTime + 65_000L)
        deContext.getSharedPreferences(DirectBootDeliveryLedger.PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(
                DirectBootDeliveryLedger.KEY_DELIVERY_LEDGER_PAYLOAD,
                DirectBootDeliveryLedger.serializeRecordsToJson(listOf(modifiedDe))
            )
            .commit()

        val ack = DirectBootCeHandoffCoordinator.acknowledgeDeliveredWithCeProof(
            deContext = deContext,
            ceContext = ceContext,
            candidateRecord = deDel,
            confirmedReceipt = handoff as CeHandoffResult.Confirmed,
            unlockGate = { true }
        )
        assertTrue("Expected Rejected.DeRecordModifiedConflict but got $ack", ack is CeQualifiedAckResult.Rejected.DeRecordModifiedConflict)
    }

    // A15: DE record absent without prior takeover
    @Test
    fun a15_deRecordAbsentWithoutPriorTakeover_rejectedWithDeRecordNotFound() {
        val unseededDel = DirectBootDeliveryRecord.createNormal(
            alarmId = 216L,
            triggerTime = baseTime + 60_000L,
            deliveredAt = baseTime + 60_500L,
            generation = 1,
            instanceToken = "tok_216"
        )
        // Combined handoff+ACK when record is neither in DE nor in CE
        val combinedRes = DirectBootCeHandoffCoordinator.handoffAndAcknowledgeDelivered(
            deContext = deContext,
            ceContext = ceContext,
            candidateRecord = unseededDel,
            recordedAtEpochMs = baseTime + 70_000L,
            unlockGate = { true }
        )
        assertTrue("Expected Rejected.DeRecordNotFound but got $combinedRes", combinedRes is CeQualifiedAckResult.Rejected.DeRecordNotFound)

        // Also seed CE directly without DE record and call acknowledgeDeliveredWithCeProof with confirmedReceipt = null
        DirectBootCeTakeoverStore.recordDelivered(ceContext, unseededDel, baseTime + 70_000L, { true })
        val standaloneRes = DirectBootCeHandoffCoordinator.acknowledgeDeliveredWithCeProof(
            deContext = deContext,
            ceContext = ceContext,
            candidateRecord = unseededDel,
            confirmedReceipt = null,
            unlockGate = { true }
        )
        assertTrue("Expected Rejected.DeRecordNotFound when DE absent and no receipt, got $standaloneRes", standaloneRes is CeQualifiedAckResult.Rejected.DeRecordNotFound)
    }

    // A16: DE commit false
    @Test
    fun a16_deCommitFalse_returnsUncertainDeCommitFailedWithReadbackObservation() {
        val deDel = seedDeDelivered(alarmId = 217L)
        val handoff = DirectBootCeHandoffCoordinator.handoffDelivered(deContext, ceContext, deDel, baseTime + 70_000L, { true })
        assertTrue(handoff is CeHandoffResult.Confirmed)

        DirectBootDeliveryLedger.commitSeam = { _, _ -> false }

        val ack = DirectBootCeHandoffCoordinator.acknowledgeDeliveredWithCeProof(
            deContext = deContext,
            ceContext = ceContext,
            candidateRecord = deDel,
            confirmedReceipt = handoff as CeHandoffResult.Confirmed,
            unlockGate = { true }
        )
        assertTrue("Expected Uncertain.DeCommitFailed but got $ack", ack is CeQualifiedAckResult.Uncertain.DeCommitFailed)
        val failed = ack as CeQualifiedAckResult.Uncertain.DeCommitFailed
        assertTrue(failed.deWriteAttempted)
        assertFalse(failed.deRecordRetired)
        assertEquals(1, failed.priorDeCount)
        assertEquals(CommitReadbackObservation.PRIOR_PAYLOAD_OBSERVED, failed.observation)
    }

    // A17: DE commit exception
    @Test
    fun a17_deCommitException_returnsUncertainDeCommitException() {
        val deMis = seedDeMissed(alarmId = 218L)
        val handoff = DirectBootCeHandoffCoordinator.handoffMissed(deContext, ceContext, deMis, baseTime + 910_000L, { true })
        assertTrue(handoff is CeHandoffResult.Confirmed)

        DirectBootMissedStore.commitSeam = { CommitExecutionOutcome.Threw(IOException("DE disk write error")) }

        val ack = DirectBootCeHandoffCoordinator.acknowledgeMissedWithCeProof(
            deContext = deContext,
            ceContext = ceContext,
            candidateRecord = deMis,
            confirmedReceipt = handoff as CeHandoffResult.Confirmed,
            unlockGate = { true }
        )
        assertTrue("Expected Uncertain.DeCommitException but got $ack", ack is CeQualifiedAckResult.Uncertain.DeCommitException)
        val exRes = ack as CeQualifiedAckResult.Uncertain.DeCommitException
        assertEquals(1, exRes.priorDeCount)
        assertEquals(CommitReadbackObservation.PRIOR_PAYLOAD_OBSERVED, exRes.observation)
    }

    // A18: Post-ACK exception
    @Test
    fun a18_postAckException_returnsPostAckFailureNotUncertain() {
        val deDel = seedDeDelivered(alarmId = 219L)
        val handoff = DirectBootCeHandoffCoordinator.handoffDelivered(deContext, ceContext, deDel, baseTime + 70_000L, { true })
        assertTrue(handoff is CeHandoffResult.Confirmed)

        val ack = DirectBootCeHandoffCoordinator.acknowledgeDeliveredWithCeProof(
            deContext = deContext,
            ceContext = ceContext,
            candidateRecord = deDel,
            confirmedReceipt = handoff as CeHandoffResult.Confirmed,
            unlockGate = { true },
            postAckCommitHook = { throw IllegalStateException("post-ACK listener failed") }
        )
        assertTrue("Expected PostAckFailure but got $ack", ack is CeQualifiedAckResult.PostAckFailure)
        val postFail = ack as CeQualifiedAckResult.PostAckFailure
        assertEquals(deDel.recordId, postFail.retiredDeRecordId)
        assertEquals(0, postFail.remainingDeCount)
        assertTrue(postFail.deRecordRetired)

        val deState = DirectBootDeliveryLedger.inspectStorageState(deContext)
        assertTrue(deState is LedgerStorageState.ValidEmpty)
    }

    // A19: Cancellation during DE commit
    @Test
    fun a19_cancellationDuringDeCommit_returnsUncertainDeCommitException() {
        val deDel = seedDeDelivered(alarmId = 220L)
        val handoff = DirectBootCeHandoffCoordinator.handoffDelivered(deContext, ceContext, deDel, baseTime + 70_000L, { true })
        assertTrue(handoff is CeHandoffResult.Confirmed)

        DirectBootDeliveryLedger.commitSeam = { _, _ -> throw CancellationException("cancelled mid-commit") }

        val ack = DirectBootCeHandoffCoordinator.acknowledgeDeliveredWithCeProof(
            deContext = deContext,
            ceContext = ceContext,
            candidateRecord = deDel,
            confirmedReceipt = handoff as CeHandoffResult.Confirmed,
            unlockGate = { true }
        )
        assertTrue("Expected Uncertain.DeCommitException on mid-commit cancellation, got $ack", ack is CeQualifiedAckResult.Uncertain.DeCommitException)
        val exRes = ack as CeQualifiedAckResult.Uncertain.DeCommitException
        assertTrue(exRes.cause is CancellationException)
    }

    // A20: Cancellation after DE commit confirmed
    @Test
    fun a20_cancellationAfterDeCommitConfirmed_returnsCancellationAfterAck() {
        val deMis = seedDeMissed(alarmId = 221L)
        val handoff = DirectBootCeHandoffCoordinator.handoffMissed(deContext, ceContext, deMis, baseTime + 910_000L, { true })
        assertTrue(handoff is CeHandoffResult.Confirmed)

        val ack = DirectBootCeHandoffCoordinator.acknowledgeMissedWithCeProof(
            deContext = deContext,
            ceContext = ceContext,
            candidateRecord = deMis,
            confirmedReceipt = handoff as CeHandoffResult.Confirmed,
            unlockGate = { true },
            postAckCommitHook = { throw CancellationException("cancelled after DE ACK readback") }
        )
        assertTrue("Expected CancellationAfterAck but got $ack", ack is CeQualifiedAckResult.CancellationAfterAck)
        val cancelAfter = ack as CeQualifiedAckResult.CancellationAfterAck
        assertEquals(deMis.recordId, cancelAfter.retiredDeRecordId)
        assertEquals(0, cancelAfter.remainingDeCount)
        assertTrue(cancelAfter.deRecordRetired)
    }

    // A21: Concurrent ACK
    @Test
    fun a21_concurrentAckOnSameAndSiblingRecords_serializesWithoutLostUpdates() {
        val r1 = seedDeDelivered(alarmId = 222L, instanceToken = "tok_222")
        val r2 = seedDeDelivered(alarmId = 223L, instanceToken = "tok_223")
        val h1 = DirectBootCeHandoffCoordinator.handoffDelivered(deContext, ceContext, r1, baseTime + 70_000L, { true }) as CeHandoffResult.Confirmed
        val h2 = DirectBootCeHandoffCoordinator.handoffDelivered(deContext, ceContext, r2, baseTime + 70_000L, { true }) as CeHandoffResult.Confirmed

        val startLatch = CountDownLatch(1)
        val doneLatch = CountDownLatch(3)
        val results = Collections.synchronizedList(mutableListOf<CeQualifiedAckResult>())
        val pool = Executors.newFixedThreadPool(3)

        try {
            // Two threads concurrently ACK r1, one thread concurrently ACKs r2
            pool.submit {
                startLatch.await(5, TimeUnit.SECONDS)
                results.add(DirectBootCeHandoffCoordinator.acknowledgeDeliveredWithCeProof(deContext, ceContext, r1, h1, { true }))
                doneLatch.countDown()
            }
            pool.submit {
                startLatch.await(5, TimeUnit.SECONDS)
                results.add(DirectBootCeHandoffCoordinator.acknowledgeDeliveredWithCeProof(deContext, ceContext, r1, h1, { true }))
                doneLatch.countDown()
            }
            pool.submit {
                startLatch.await(5, TimeUnit.SECONDS)
                results.add(DirectBootCeHandoffCoordinator.acknowledgeDeliveredWithCeProof(deContext, ceContext, r2, h2, { true }))
                doneLatch.countDown()
            }

            startLatch.countDown()
            assertTrue("Concurrent ACK threads timed out", doneLatch.await(10, TimeUnit.SECONDS))
        } finally {
            pool.shutdownNow()
        }

        assertEquals(3, results.size)
        val confirmedWrites = results.count { it is CeQualifiedAckResult.AcknowledgedConfirmed }
        val alreadyConfirmed = results.count { it is CeQualifiedAckResult.AlreadyAcknowledgedConfirmed }
        assertEquals("Exactly 2 distinct records should perform DE writes", 2, confirmedWrites)
        assertEquals("Exactly 1 duplicate concurrent ACK should return AlreadyAcknowledgedConfirmed", 1, alreadyConfirmed)

        val deState = DirectBootDeliveryLedger.inspectStorageState(deContext)
        assertTrue("Both r1 and r2 must be retired cleanly without lost updates, got $deState", deState is LedgerStorageState.ValidEmpty)
    }

    // A22: Concurrent CE update during DE ACK
    @Test
    fun a22_concurrentCeUpdateDuringDeAck_serializesCleanly() {
        val deDel = seedDeDelivered(alarmId = 224L)
        val receipt = DirectBootCeHandoffCoordinator.handoffDelivered(deContext, ceContext, deDel, baseTime + 70_000L, { true }) as CeHandoffResult.Confirmed
        val otherMis = DirectBootMissedRecord.createNormal(
            alarmId = 225L,
            triggerTime = baseTime + 120_000L,
            missedAt = baseTime + 900_000L,
            generation = 1,
            instanceToken = "tok_225",
            classification = DirectBootMissedRecord.CLASSIFICATION_OVERDUE
        )

        val startLatch = CountDownLatch(1)
        val doneLatch = CountDownLatch(2)
        var ackRes: CeQualifiedAckResult? = null
        var ceRes: CeTakeoverIntakeResult? = null
        val pool = Executors.newFixedThreadPool(2)

        try {
            pool.submit {
                startLatch.await(5, TimeUnit.SECONDS)
                ackRes = DirectBootCeHandoffCoordinator.acknowledgeDeliveredWithCeProof(deContext, ceContext, deDel, receipt, { true })
                doneLatch.countDown()
            }
            pool.submit {
                startLatch.await(5, TimeUnit.SECONDS)
                ceRes = DirectBootCeTakeoverStore.recordMissed(ceContext, otherMis, baseTime + 910_000L, { true })
                doneLatch.countDown()
            }
            startLatch.countDown()
            assertTrue(doneLatch.await(10, TimeUnit.SECONDS))
        } finally {
            pool.shutdownNow()
        }

        assertTrue("Expected AcknowledgedConfirmed but got $ackRes", ackRes is CeQualifiedAckResult.AcknowledgedConfirmed)
        assertTrue("Expected RecordedConfirmed but got $ceRes", ceRes is CeTakeoverIntakeResult.RecordedConfirmed)

        val ceState = DirectBootCeTakeoverStore.inspectStorageState(ceContext, unlockGate = { true }) as CeTakeoverStorageState.ValidWithRecords
        assertEquals(2, ceState.records.size)
    }

    // A23: Process death before DE ACK
    @Test
    fun a23_processDeathBeforeDeAck_convergesOnRetryWithSingleCeAndSingleDeWrite() {
        val deMis = seedDeMissed(alarmId = 226L)
        val ceCommits = AtomicInteger(0)
        val deCommits = AtomicInteger(0)

        DirectBootCeTakeoverStore.commitSeam = { prefs, payload ->
            ceCommits.incrementAndGet()
            prefs.edit().putString(DirectBootCeTakeoverStore.KEY_CE_TAKEOVER_PAYLOAD, payload).commit()
        }
        DirectBootMissedStore.commitSeam = { editor ->
            deCommits.incrementAndGet()
            if (editor.commit()) CommitExecutionOutcome.Success else CommitExecutionOutcome.CommitFalse
        }

        val attempt1 = DirectBootCeHandoffCoordinator.handoffAndAcknowledgeMissed(
            deContext = deContext,
            ceContext = ceContext,
            candidateRecord = deMis,
            recordedAtEpochMs = baseTime + 910_000L,
            unlockGate = { true },
            preAckCommitHook = { throw RuntimeException("process died after CE commit before DE ACK") }
        )
        assertTrue("Expected Rejected.PreAckCommitFailure but got $attempt1", attempt1 is CeQualifiedAckResult.Rejected.PreAckCommitFailure)
        assertEquals(1, ceCommits.get())
        assertEquals(0, deCommits.get())

        // Retry converges without re-writing CE
        val attempt2 = DirectBootCeHandoffCoordinator.handoffAndAcknowledgeMissed(
            deContext = deContext,
            ceContext = ceContext,
            candidateRecord = deMis,
            recordedAtEpochMs = baseTime + 920_000L,
            unlockGate = { true }
        )
        assertTrue("Expected AcknowledgedConfirmed on retry, got $attempt2", attempt2 is CeQualifiedAckResult.AcknowledgedConfirmed)
        assertEquals("CE must not be committed a second time", 1, ceCommits.get())
        assertEquals("DE must be committed exactly once", 1, deCommits.get())
    }

    // A24: Process death after DE ACK
    @Test
    fun a24_processDeathAfterDeAck_convergesToAlreadyAcknowledgedOnRetry() {
        val deDel = seedDeDelivered(alarmId = 227L)
        val ceCommits = AtomicInteger(0)
        val deCommits = AtomicInteger(0)

        DirectBootCeTakeoverStore.commitSeam = { prefs, payload ->
            ceCommits.incrementAndGet()
            prefs.edit().putString(DirectBootCeTakeoverStore.KEY_CE_TAKEOVER_PAYLOAD, payload).commit()
        }
        DirectBootDeliveryLedger.commitSeam = { prefs, payload ->
            deCommits.incrementAndGet()
            prefs.edit().putString(DirectBootDeliveryLedger.KEY_DELIVERY_LEDGER_PAYLOAD, payload).commit()
        }

        val attempt1 = DirectBootCeHandoffCoordinator.handoffAndAcknowledgeDelivered(
            deContext = deContext,
            ceContext = ceContext,
            candidateRecord = deDel,
            recordedAtEpochMs = baseTime + 70_000L,
            unlockGate = { true },
            postAckCommitHook = { throw RuntimeException("process killed right after DE ACK commit") }
        )
        assertTrue("Expected PostAckFailure on attempt1, got $attempt1", attempt1 is CeQualifiedAckResult.PostAckFailure)
        assertEquals(1, ceCommits.get())
        assertEquals(1, deCommits.get())

        val attempt2 = DirectBootCeHandoffCoordinator.handoffAndAcknowledgeDelivered(
            deContext = deContext,
            ceContext = ceContext,
            candidateRecord = deDel,
            recordedAtEpochMs = baseTime + 80_000L,
            unlockGate = { true }
        )
        assertTrue("Expected AlreadyAcknowledgedConfirmed on retry, got $attempt2", attempt2 is CeQualifiedAckResult.AlreadyAcknowledgedConfirmed)
        assertEquals(1, ceCommits.get())
        assertEquals(1, deCommits.get())
    }

    // A25: Last-record ACK writes valid empty versioned envelope
    @Test
    fun a25_lastRecordAck_writesValidEmptyVersionedEnvelopeNotNull() {
        val deDel = seedDeDelivered(alarmId = 228L)
        val deMis = seedDeMissed(alarmId = 229L)

        val ackDel = DirectBootCeHandoffCoordinator.handoffAndAcknowledgeDelivered(
            deContext = deContext,
            ceContext = ceContext,
            candidateRecord = deDel,
            recordedAtEpochMs = baseTime + 70_000L,
            unlockGate = { true }
        )
        assertTrue(ackDel is CeQualifiedAckResult.AcknowledgedConfirmed)
        val rawLedgerAfter = DirectBootDeliveryLedger.getRawPayload(deContext)
        assertEquals("{\"schemaVersion\":2,\"records\":[]}", rawLedgerAfter)
        assertTrue(DirectBootDeliveryLedger.inspectStorageState(deContext) is LedgerStorageState.ValidEmpty)

        val ackMis = DirectBootCeHandoffCoordinator.handoffAndAcknowledgeMissed(
            deContext = deContext,
            ceContext = ceContext,
            candidateRecord = deMis,
            recordedAtEpochMs = baseTime + 910_000L,
            unlockGate = { true }
        )
        assertTrue(ackMis is CeQualifiedAckResult.AcknowledgedConfirmed)
        val rawMissedAfter = DirectBootMissedStore.getRawPayload(deContext)
        assertEquals("{\"schemaVersion\":2,\"records\":[]}", rawMissedAfter)
        assertTrue(DirectBootMissedStore.inspectStorageState(deContext) is MissedStoreStorageState.ValidEmpty)
    }

    // A26: Full-capacity 16 ACK releases slot for 17th record
    @Test
    fun a26_fullCapacity16Ack_releasesSlotFor17thRecord() {
        val seeded = (1..16).map { i ->
            seedDeMissed(alarmId = (300 + i).toLong(), triggerTime = baseTime + i * 10_000L)
        }
        val extra17 = DirectBootMissedRecord.createNormal(
            alarmId = 399L,
            triggerTime = baseTime + 990_000L,
            missedAt = baseTime + 1_900_000L,
            generation = 1,
            instanceToken = "inst_399",
            classification = DirectBootMissedRecord.CLASSIFICATION_OVERDUE
        )
        assertTrue(DirectBootMissedStore.recordMissed(deContext, extra17) is MissedRecordResult.CapacityExhausted)

        // Handoff and ACK 1 record (seeded[5])
        val ackRes = DirectBootCeHandoffCoordinator.handoffAndAcknowledgeMissed(
            deContext = deContext,
            ceContext = ceContext,
            candidateRecord = seeded[5],
            recordedAtEpochMs = baseTime + 950_000L,
            unlockGate = { true }
        )
        assertTrue("Expected AcknowledgedConfirmed but got $ackRes", ackRes is CeQualifiedAckResult.AcknowledgedConfirmed)
        assertEquals(15, (ackRes as CeQualifiedAckResult.AcknowledgedConfirmed).remainingDeCount)

        // Now recording the 17th record succeeds
        val append17 = DirectBootMissedStore.recordMissed(deContext, extra17)
        assertTrue("Expected Recorded after freeing 1 slot, got $append17", append17 is MissedRecordResult.Recorded)
        assertEquals(16, (append17 as MissedRecordResult.Recorded).totalCount)
    }

    // A27: Sibling-record preservation
    @Test
    fun a27_siblingRecordPreservation_retiresTargetOnlyAndPreservesOrderAndContent() {
        val r1 = seedDeDelivered(alarmId = 271L, triggerTime = baseTime + 10_000L, instanceToken = "tok_271")
        val r2 = seedDeDelivered(alarmId = 272L, triggerTime = baseTime + 20_000L, instanceToken = "tok_272")
        val r3 = seedDeDelivered(alarmId = 273L, triggerTime = baseTime + 30_000L, instanceToken = "tok_273")

        val ackR2 = DirectBootCeHandoffCoordinator.handoffAndAcknowledgeDelivered(
            deContext = deContext,
            ceContext = ceContext,
            candidateRecord = r2,
            recordedAtEpochMs = baseTime + 70_000L,
            unlockGate = { true }
        )
        assertTrue("Expected AcknowledgedConfirmed for r2, got $ackR2", ackR2 is CeQualifiedAckResult.AcknowledgedConfirmed)
        assertEquals(2, (ackR2 as CeQualifiedAckResult.AcknowledgedConfirmed).remainingDeCount)

        val deState = DirectBootDeliveryLedger.inspectStorageState(deContext) as LedgerStorageState.ValidWithRecords
        assertEquals(listOf(r1, r3), deState.records)
    }

    // A28: Lock-order inversion check
    @Test
    fun a28_lockOrderInversionCheck_multiThreadedStressZeroDeadlocks() {
        val r1 = seedDeDelivered(alarmId = 281L)
        val r2 = seedDeDelivered(alarmId = 282L)
        val startLatch = CountDownLatch(1)
        val doneLatch = CountDownLatch(3)
        val ackResults = Collections.synchronizedList(mutableListOf<CeQualifiedAckResult>())
        val pool = Executors.newFixedThreadPool(3)

        try {
            // Thread 1: DE -> CE via handoffAndAcknowledgeDelivered
            pool.submit {
                startLatch.await(5, TimeUnit.SECONDS)
                ackResults.add(
                    DirectBootCeHandoffCoordinator.handoffAndAcknowledgeDelivered(
                        deContext, ceContext, r1, baseTime + 70_000L, { true }
                    )
                )
                ackResults.add(
                    DirectBootCeHandoffCoordinator.handoffAndAcknowledgeDelivered(
                        deContext, ceContext, r2, baseTime + 70_000L, { true }
                    )
                )
                doneLatch.countDown()
            }
            // Thread 2: Pure CE operations under ceStorageLock
            pool.submit {
                startLatch.await(5, TimeUnit.SECONDS)
                repeat(5) { idx ->
                    val mis = DirectBootMissedRecord.createNormal(
                        alarmId = (400 + idx).toLong(),
                        triggerTime = baseTime + 100_000L,
                        missedAt = baseTime + 900_000L,
                        generation = 1,
                        instanceToken = "ce_only_$idx",
                        classification = DirectBootMissedRecord.CLASSIFICATION_OVERDUE
                    )
                    DirectBootCeTakeoverStore.recordMissed(ceContext, mis, baseTime + 910_000L, { true })
                }
                doneLatch.countDown()
            }
            // Thread 3: Pure DE operations under DirectBootStorageLock.lock
            pool.submit {
                startLatch.await(5, TimeUnit.SECONDS)
                repeat(5) { idx ->
                    val mis = DirectBootMissedRecord.createNormal(
                        alarmId = (500 + idx).toLong(),
                        triggerTime = baseTime + 100_000L,
                        missedAt = baseTime + 900_000L,
                        generation = 1,
                        instanceToken = "de_only_$idx",
                        classification = DirectBootMissedRecord.CLASSIFICATION_OVERDUE
                    )
                    DirectBootMissedStore.recordMissed(deContext, mis)
                }
                doneLatch.countDown()
            }

            startLatch.countDown()
            assertTrue("Deadlock detected: threads did not complete within 10s", doneLatch.await(10, TimeUnit.SECONDS))
        } finally {
            pool.shutdownNow()
        }

        assertEquals(2, ackResults.size)
        assertTrue(ackResults.all { it is CeQualifiedAckResult.AcknowledgedConfirmed })
    }

    // A29: CE rollback / disappearance after handoff blocks DE ACK
    @Test
    fun a29_ceRollbackOrDisappearanceAfterHandoff_blocksDeAckAndPreservesDe() {
        val deDel = seedDeDelivered(alarmId = 290L)
        val handoff = DirectBootCeHandoffCoordinator.handoffDelivered(deContext, ceContext, deDel, baseTime + 70_000L, { true })
        assertTrue(handoff is CeHandoffResult.Confirmed)

        // Simulate filesystem rollback wiping CE store after handoff returned Confirmed
        ceContext.getSharedPreferences(DirectBootCeTakeoverStore.PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .remove(DirectBootCeTakeoverStore.KEY_CE_TAKEOVER_PAYLOAD)
            .commit()

        val ack = DirectBootCeHandoffCoordinator.acknowledgeDeliveredWithCeProof(
            deContext = deContext,
            ceContext = ceContext,
            candidateRecord = deDel,
            confirmedReceipt = handoff as CeHandoffResult.Confirmed,
            unlockGate = { true }
        )
        assertTrue("Expected Rejected.CeEvidenceMissing when CE rolled back, got $ack", ack is CeQualifiedAckResult.Rejected.CeEvidenceMissing)

        val deState = DirectBootDeliveryLedger.inspectStorageState(deContext) as LedgerStorageState.ValidWithRecords
        assertEquals(1, deState.records.size)
    }

    // A30: Repeated unlock / reconcile is idempotent
    @Test
    fun a30_repeatedUnlockReconcile_isIdempotentWithSingleCeAndSingleDeCommit() {
        val deMis = seedDeMissed(alarmId = 300L)
        val ceCommits = AtomicInteger(0)
        val deCommits = AtomicInteger(0)

        DirectBootCeTakeoverStore.commitSeam = { prefs, payload ->
            ceCommits.incrementAndGet()
            prefs.edit().putString(DirectBootCeTakeoverStore.KEY_CE_TAKEOVER_PAYLOAD, payload).commit()
        }
        DirectBootMissedStore.commitSeam = { editor ->
            deCommits.incrementAndGet()
            if (editor.commit()) CommitExecutionOutcome.Success else CommitExecutionOutcome.CommitFalse
        }

        val r1 = DirectBootCeHandoffCoordinator.handoffAndAcknowledgeMissed(deContext, ceContext, deMis, baseTime + 910_000L, { true })
        val r2 = DirectBootCeHandoffCoordinator.handoffAndAcknowledgeMissed(deContext, ceContext, deMis, baseTime + 920_000L, { true })
        val r3 = DirectBootCeHandoffCoordinator.handoffAndAcknowledgeMissed(deContext, ceContext, deMis, baseTime + 930_000L, { true })

        assertTrue("Expected AcknowledgedConfirmed on 1st reconcile, got $r1", r1 is CeQualifiedAckResult.AcknowledgedConfirmed)
        assertTrue("Expected AlreadyAcknowledgedConfirmed on 2nd reconcile, got $r2", r2 is CeQualifiedAckResult.AlreadyAcknowledgedConfirmed)
        assertTrue("Expected AlreadyAcknowledgedConfirmed on 3rd reconcile, got $r3", r3 is CeQualifiedAckResult.AlreadyAcknowledgedConfirmed)
        assertEquals(1, ceCommits.get())
        assertEquals(1, deCommits.get())
    }

    // X04: DE WriterとCoordinatorの並行実行
    @Test
    fun x04_concurrentDeWriterAndCoordinator_serializedByCanonicalStorageLock() {
        val deDel = seedDeDelivered(alarmId = 404L)
        val receipt = DirectBootCeHandoffCoordinator.handoffDelivered(deContext, ceContext, deDel, baseTime + 70_000L, { true }) as CeHandoffResult.Confirmed

        val deLockAcquiredLatch = CountDownLatch(1)
        val releaseDeLockLatch = CountDownLatch(1)
        val coordinatorEnteredHookLatch = CountDownLatch(1)
        val doneLatch = CountDownLatch(2)
        var ackResult: CeQualifiedAckResult? = null
        val pool = Executors.newFixedThreadPool(2)

        try {
            // Thread 1 holds DirectBootStorageLock.lock (simulating an active DE writer)
            pool.submit {
                synchronized(DirectBootStorageLock.lock) {
                    deLockAcquiredLatch.countDown()
                    releaseDeLockLatch.await(5, TimeUnit.SECONDS)
                }
                doneLatch.countDown()
            }
            assertTrue(deLockAcquiredLatch.await(5, TimeUnit.SECONDS))

            // Thread 2 invokes acknowledgeDeliveredWithCeProof; must block on DirectBootStorageLock.lock
            pool.submit {
                ackResult = DirectBootCeHandoffCoordinator.acknowledgeDeliveredWithCeProof(
                    deContext = deContext,
                    ceContext = ceContext,
                    candidateRecord = deDel,
                    confirmedReceipt = receipt,
                    unlockGate = { true },
                    preAckCommitHook = {
                        coordinatorEnteredHookLatch.countDown()
                    }
                )
                doneLatch.countDown()
            }

            // Coordinator must NOT enter preAckCommitHook while Thread 1 holds DirectBootStorageLock.lock
            assertFalse(
                "Coordinator must block while DE writer holds DirectBootStorageLock.lock",
                coordinatorEnteredHookLatch.await(150, TimeUnit.MILLISECONDS)
            )

            releaseDeLockLatch.countDown()
            assertTrue(doneLatch.await(5, TimeUnit.SECONDS))
        } finally {
            pool.shutdownNow()
        }

        assertTrue("Expected AcknowledgedConfirmed after DE lock release, got $ackResult", ackResult is CeQualifiedAckResult.AcknowledgedConfirmed)
    }

    // X05: CE StoreとのLock順序 (DirectBootStorageLock.lock -> ceStorageLock)
    @Test
    fun x05_coordinatorLockOrderingIsStrictlyDeLockThenCeLock() {
        val deDel = seedDeDelivered(alarmId = 405L)
        val deMis = seedDeMissed(alarmId = 406L)
        val orderEvents = mutableListOf<String>()

        val resDel = DirectBootCeHandoffCoordinator.handoffAndAcknowledgeDelivered(
            deContext = deContext,
            ceContext = ceContext,
            candidateRecord = deDel,
            recordedAtEpochMs = baseTime + 70_000L,
            unlockGate = { true },
            preCeCommitHook = {
                if (Thread.holdsLock(DirectBootStorageLock.lock) && Thread.holdsLock(DirectBootCeTakeoverStore.ceStorageLock)) {
                    orderEvents.add("CE_COMMIT_HOLDS_DE_AND_CE")
                }
            },
            preAckCommitHook = {
                if (Thread.holdsLock(DirectBootStorageLock.lock) && Thread.holdsLock(DirectBootCeTakeoverStore.ceStorageLock)) {
                    orderEvents.add("DE_ACK_HOLDS_DE_AND_CE")
                }
            }
        )
        assertTrue("Expected AcknowledgedConfirmed, got $resDel", resDel is CeQualifiedAckResult.AcknowledgedConfirmed)
        assertEquals(listOf("CE_COMMIT_HOLDS_DE_AND_CE", "DE_ACK_HOLDS_DE_AND_CE"), orderEvents)

        var standaloneHoldsBothLocks = false
        val receiptMis = DirectBootCeHandoffCoordinator.handoffMissed(deContext, ceContext, deMis, baseTime + 910_000L, { true }) as CeHandoffResult.Confirmed
        val resMis = DirectBootCeHandoffCoordinator.acknowledgeMissedWithCeProof(
            deContext = deContext,
            ceContext = ceContext,
            candidateRecord = deMis,
            confirmedReceipt = receiptMis,
            unlockGate = { true },
            preAckCommitHook = {
                standaloneHoldsBothLocks =
                    Thread.holdsLock(DirectBootStorageLock.lock) && Thread.holdsLock(DirectBootCeTakeoverStore.ceStorageLock)
            }
        )
        assertTrue(resMis is CeQualifiedAckResult.AcknowledgedConfirmed)
        assertTrue("acknowledgeMissedWithCeProof must hold both DE and CE locks in canonical order", standaloneHoldsBothLocks)
    }

    // X06: 逆順Lock取得経路なし (CE Store単体操作はDE Lockを取得しない)
    @Test
    fun x06_noReverseCeToDeLockAcquisitionPathExists() {
        val deLockHeldByOtherThread = CountDownLatch(1)
        val releaseDeLock = CountDownLatch(1)
        val ceOpCompletedWhileDeLockHeld = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(2)

        try {
            // Thread 1 holds DirectBootStorageLock.lock
            pool.submit {
                synchronized(DirectBootStorageLock.lock) {
                    deLockHeldByOtherThread.countDown()
                    releaseDeLock.await(5, TimeUnit.SECONDS)
                }
            }
            assertTrue(deLockHeldByOtherThread.await(5, TimeUnit.SECONDS))

            // Thread 2 performs DirectBootCeTakeoverStore operation while Thread 1 holds DE lock.
            // Because CE Store NEVER acquires DE lock (zero CE->DE edge), Thread 2 completes immediately!
            pool.submit {
                val sample = DirectBootDeliveryRecord.createNormal(
                    alarmId = 407L,
                    triggerTime = baseTime + 60_000L,
                    deliveredAt = baseTime + 60_500L,
                    generation = 1,
                    instanceToken = "tok_407"
                )
                var ceOpAttemptedDeLock = false
                val res = DirectBootCeTakeoverStore.recordDelivered(
                    ceContext = ceContext,
                    deliveryRecord = sample,
                    recordedAtEpochMs = baseTime + 70_000L,
                    unlockGate = { true },
                    preCommitHook = {
                        ceOpAttemptedDeLock = Thread.holdsLock(DirectBootStorageLock.lock)
                    }
                )
                assertTrue(res is CeTakeoverIntakeResult.RecordedConfirmed)
                assertFalse("CE store must never acquire DirectBootStorageLock.lock", ceOpAttemptedDeLock)
                ceOpCompletedWhileDeLockHeld.countDown()
            }

            assertTrue(
                "DirectBootCeTakeoverStore must complete without waiting on DirectBootStorageLock.lock",
                ceOpCompletedWhileDeLockHeld.await(3, TimeUnit.SECONDS)
            )
            releaseDeLock.countDown()
        } finally {
            pool.shutdownNow()
        }

        // Also verify full handoffAndAcknowledgeDelivered succeeds once DE lock is free
        val deDel = seedDeDelivered(alarmId = 408L)
        val ack = DirectBootCeHandoffCoordinator.handoffAndAcknowledgeDelivered(
            deContext = deContext,
            ceContext = ceContext,
            candidateRecord = deDel,
            recordedAtEpochMs = baseTime + 70_000L,
            unlockGate = { true }
        )
        assertTrue("Expected AcknowledgedConfirmed, got $ack", ack is CeQualifiedAckResult.AcknowledgedConfirmed)
    }

    // X07: 無資格Callerによる削除試行 & 本番public APIにテストInjectionが露出していないことの証明
    @Test
    fun x07_unqualifiedCallerCannotDeleteViaRawApiOrPublicTestHookExposure() {
        val deDel = seedDeDelivered(alarmId = 409L)
        val deMis = seedDeMissed(alarmId = 410L)

        // 1. Raw store APIs cannot delete
        assertTrue(DirectBootDeliveryLedger.acknowledgeRecord(deContext, deDel.recordId) is DeliveryAckResult.AckNotQualified)
        assertTrue(DirectBootMissedStore.acknowledgeRecord(deContext, deMis.recordId) is MissedAckResult.AckNotQualified)

        // 2. Calling coordinator ACK without CE record cannot delete
        val unverifiedDelAck = DirectBootCeHandoffCoordinator.acknowledgeDeliveredWithCeProof(deContext, ceContext, deDel, null, { true })
        val unverifiedMisAck = DirectBootCeHandoffCoordinator.acknowledgeMissedWithCeProof(deContext, ceContext, deMis, null, { true })
        assertTrue(unverifiedDelAck is CeQualifiedAckResult.Rejected.CeEvidenceMissing)
        assertTrue(unverifiedMisAck is CeQualifiedAckResult.Rejected.CeEvidenceMissing)

        // 3. Verify via reflection that public production methods (not $app_debug internal methods and not $default synthetic bridges)
        // on DirectBootCeHandoffCoordinator and DirectBootCeTakeoverStore do NOT expose Function0/Function1 test hooks.
        val coordinatorPublicProdMethods = DirectBootCeHandoffCoordinator::class.java.declaredMethods.filter { m ->
            Modifier.isPublic(m.modifiers) && !m.isSynthetic && !m.name.contains('$')
        }
        assertTrue("Expected public production methods on DirectBootCeHandoffCoordinator", coordinatorPublicProdMethods.isNotEmpty())
        for (method in coordinatorPublicProdMethods) {
            val hasFunctionParam = method.parameterTypes.any { paramType ->
                paramType.name.startsWith("kotlin.jvm.functions.Function")
            }
            assertFalse(
                "Public production method '${method.name}' must NOT expose unrestricted test hook/lambda parameters: ${method.parameterTypes.map { it.simpleName }}",
                hasFunctionParam
            )
        }

        val storePublicProdMethods = DirectBootCeTakeoverStore::class.java.declaredMethods.filter { m ->
            Modifier.isPublic(m.modifiers) && !m.isSynthetic && !m.name.contains('$')
        }
        assertTrue("Expected public production methods on DirectBootCeTakeoverStore", storePublicProdMethods.isNotEmpty())
        for (method in storePublicProdMethods) {
            val hasFunctionParam = method.parameterTypes.any { paramType ->
                paramType.name.startsWith("kotlin.jvm.functions.Function")
            }
            assertFalse(
                "Public production method '${method.name}' on DirectBootCeTakeoverStore must NOT expose unrestricted test hook/lambda parameters",
                hasFunctionParam
            )
        }
    }

    // X08: CE Record消失後のACK拒否
    @Test
    fun x08_ceRecordDisappearanceAfterConfirm_rejectsAckAndPreservesDe() {
        val deDel1 = seedDeDelivered(alarmId = 411L, instanceToken = "tok_411")
        val deDel2 = seedDeDelivered(alarmId = 412L, instanceToken = "tok_412")

        val h1 = DirectBootCeHandoffCoordinator.handoffDelivered(deContext, ceContext, deDel1, baseTime + 70_000L, { true }) as CeHandoffResult.Confirmed
        val h2 = DirectBootCeHandoffCoordinator.handoffDelivered(deContext, ceContext, deDel2, baseTime + 70_000L, { true }) as CeHandoffResult.Confirmed

        // Externally remove deDel1's CE record while keeping deDel2's CE record in CE store
        val onlyRecord2Json = JSONObject().apply {
            put("schemaVersion", DirectBootCeTakeoverStore.CURRENT_SCHEMA_VERSION)
            put("records", JSONArray().put(h2.ceRecord.toJson()))
        }.toString()
        ceContext.getSharedPreferences(DirectBootCeTakeoverStore.PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(DirectBootCeTakeoverStore.KEY_CE_TAKEOVER_PAYLOAD, onlyRecord2Json)
            .commit()

        val ack1 = DirectBootCeHandoffCoordinator.acknowledgeDeliveredWithCeProof(deContext, ceContext, deDel1, h1, { true })
        assertTrue("Expected Rejected.CeEvidenceMissing when target CE record disappeared, got $ack1", ack1 is CeQualifiedAckResult.Rejected.CeEvidenceMissing)

        // deDel2 (still in CE) can be acknowledged cleanly while deDel1 remains preserved in DE
        val ack2 = DirectBootCeHandoffCoordinator.acknowledgeDeliveredWithCeProof(deContext, ceContext, deDel2, h2, { true })
        assertTrue("Expected AcknowledgedConfirmed for deDel2, got $ack2", ack2 is CeQualifiedAckResult.AcknowledgedConfirmed)

        val remainingDe = (DirectBootDeliveryLedger.inspectStorageState(deContext) as LedgerStorageState.ValidWithRecords).records
        assertEquals(listOf(deDel1), remainingDe)
    }

    // X09: CE Commit後・DE ACK前の再試行
    @Test
    fun x09_crashAfterCeCommitBeforeDeAck_retryCompletesDeAckWithoutRewritingCe() {
        val deDel = seedDeDelivered(alarmId = 413L)
        val ceCommits = AtomicInteger(0)
        val deCommits = AtomicInteger(0)

        DirectBootCeTakeoverStore.commitSeam = { prefs, payload ->
            ceCommits.incrementAndGet()
            prefs.edit().putString(DirectBootCeTakeoverStore.KEY_CE_TAKEOVER_PAYLOAD, payload).commit()
        }
        DirectBootDeliveryLedger.commitSeam = { prefs, payload ->
            deCommits.incrementAndGet()
            prefs.edit().putString(DirectBootDeliveryLedger.KEY_DELIVERY_LEDGER_PAYLOAD, payload).commit()
        }

        val firstTry = DirectBootCeHandoffCoordinator.handoffAndAcknowledgeDelivered(
            deContext = deContext,
            ceContext = ceContext,
            candidateRecord = deDel,
            recordedAtEpochMs = baseTime + 70_000L,
            unlockGate = { true },
            postCeCommitHook = { throw RuntimeException("crash right after CE commit before DE ACK") }
        )
        assertTrue("Expected Rejected.PreAckCommitFailure, got $firstTry", firstTry is CeQualifiedAckResult.Rejected.PreAckCommitFailure)
        assertEquals(1, ceCommits.get())
        assertEquals(0, deCommits.get())
        assertEquals(1, (DirectBootDeliveryLedger.inspectStorageState(deContext) as LedgerStorageState.ValidWithRecords).records.size)

        val retry = DirectBootCeHandoffCoordinator.handoffAndAcknowledgeDelivered(
            deContext = deContext,
            ceContext = ceContext,
            candidateRecord = deDel,
            recordedAtEpochMs = baseTime + 80_000L,
            unlockGate = { true }
        )
        assertTrue("Expected AcknowledgedConfirmed on retry, got $retry", retry is CeQualifiedAckResult.AcknowledgedConfirmed)
        assertEquals(1, ceCommits.get())
        assertEquals(1, deCommits.get())
        assertTrue(DirectBootDeliveryLedger.inspectStorageState(deContext) is LedgerStorageState.ValidEmpty)
    }

    // X10: DE ACK後の再読込時の冪等性
    @Test
    fun x10_reloadAfterDeAck_isIdempotentAndPreservesCeEvidence() {
        val deDel = seedDeDelivered(alarmId = 414L)
        val handoff = DirectBootCeHandoffCoordinator.handoffDelivered(deContext, ceContext, deDel, baseTime + 70_000L, { true }) as CeHandoffResult.Confirmed

        val firstAck = DirectBootCeHandoffCoordinator.acknowledgeDeliveredWithCeProof(deContext, ceContext, deDel, handoff, { true })
        assertTrue("Expected AcknowledgedConfirmed, got $firstAck", firstAck is CeQualifiedAckResult.AcknowledgedConfirmed)

        // Reload DE and CE storage states
        assertTrue(DirectBootDeliveryLedger.inspectStorageState(deContext) is LedgerStorageState.ValidEmpty)
        val ceReloaded = DirectBootCeTakeoverStore.inspectStorageState(ceContext, unlockGate = { true })
        assertTrue(ceReloaded is CeTakeoverStorageState.ValidWithRecords)
        assertEquals(1, (ceReloaded as CeTakeoverStorageState.ValidWithRecords).records.size)

        val deCommits = AtomicInteger(0)
        DirectBootDeliveryLedger.commitSeam = { prefs, payload ->
            deCommits.incrementAndGet()
            prefs.edit().putString(DirectBootDeliveryLedger.KEY_DELIVERY_LEDGER_PAYLOAD, payload).commit()
        }

        val secondAck = DirectBootCeHandoffCoordinator.acknowledgeDeliveredWithCeProof(deContext, ceContext, deDel, handoff, { true })
        assertTrue("Expected AlreadyAcknowledgedConfirmed on reload ACK, got $secondAck", secondAck is CeQualifiedAckResult.AlreadyAcknowledgedConfirmed)

        val combinedReload = DirectBootCeHandoffCoordinator.handoffAndAcknowledgeDelivered(deContext, ceContext, deDel, baseTime + 90_000L, { true })
        assertTrue("Expected AlreadyAcknowledgedConfirmed on combined reload, got $combinedReload", combinedReload is CeQualifiedAckResult.AlreadyAcknowledgedConfirmed)
        assertEquals("Reload idempotent calls must perform 0 DE commits", 0, deCommits.get())
    }
}
