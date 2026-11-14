package com.sysadmindoc.alarmclock.directboot

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger

/**
 * Focused unit tests for [DirectBootCeTakeoverStore] (KNO-75 Phase D3-C2-P3D-P1).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class DirectBootCeTakeoverStoreTest {

    private lateinit var ceContext: Context
    private lateinit var deContext: Context
    private val baseTime = 1_700_000_000_000L

    @Before
    fun setUp() {
        val appContext = ApplicationProvider.getApplicationContext<Context>()
        ceContext = appContext
        deContext = appContext.createDeviceProtectedStorageContext()
        DirectBootCeTakeoverStore.resetSeamsForTesting()
        ceContext.getSharedPreferences(DirectBootCeTakeoverStore.PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .remove(DirectBootCeTakeoverStore.KEY_CE_TAKEOVER_PAYLOAD)
            .commit()
        deContext.getSharedPreferences(DirectBootCeTakeoverStore.PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .remove(DirectBootCeTakeoverStore.KEY_CE_TAKEOVER_PAYLOAD)
            .commit()
    }

    @After
    fun tearDown() {
        DirectBootCeTakeoverStore.resetSeamsForTesting()
    }

    private fun sampleDelivered(
        alarmId: Long = 10L,
        triggerTime: Long = baseTime + 60_000L,
        deliveredAt: Long = baseTime + 61_000L,
        generation: Int = 1,
        instanceToken: String = "token_$alarmId",
        provenance: String = "NORMAL_DISPATCH"
    ): DirectBootDeliveryRecord = DirectBootDeliveryRecord.createNormal(
        alarmId = alarmId,
        triggerTime = triggerTime,
        deliveredAt = deliveredAt,
        generation = generation,
        instanceToken = instanceToken,
        provenance = provenance
    )

    private fun sampleMissed(
        alarmId: Long = 20L,
        triggerTime: Long = baseTime + 120_000L,
        missedAt: Long = baseTime + 900_000L,
        generation: Int = 1,
        instanceToken: String = "token_$alarmId",
        classification: String = DirectBootMissedRecord.CLASSIFICATION_OVERDUE
    ): DirectBootMissedRecord = DirectBootMissedRecord.createNormal(
        alarmId = alarmId,
        triggerTime = triggerTime,
        missedAt = missedAt,
        generation = generation,
        instanceToken = instanceToken,
        classification = classification
    )

    @Test
    fun s01_ceLocked_rejectsInspectAndIntake() {
        val state = DirectBootCeTakeoverStore.inspectStorageState(ceContext, unlockGate = { false })
        assertTrue("Expected CeLocked state but got $state", state is CeTakeoverStorageState.CeLocked)

        val result = DirectBootCeTakeoverStore.recordDelivered(
            ceContext = ceContext,
            deliveryRecord = sampleDelivered(),
            recordedAtEpochMs = baseTime + 70_000L,
            unlockGate = { false }
        )
        assertTrue("Expected Rejected.CeLocked but got $result", result is CeTakeoverIntakeResult.Rejected.CeLocked)
        assertTrue(result.isGuaranteedNoWrite)
        assertFalse(result.ceWriteAttempted)
    }

    @Test
    fun s02_deContextMisuse_rejectedFailClosed() {
        val state = DirectBootCeTakeoverStore.inspectStorageState(deContext, unlockGate = { true })
        assertTrue("Expected CeContextRejected state but got $state", state is CeTakeoverStorageState.CeContextRejected)

        val result = DirectBootCeTakeoverStore.recordMissed(
            ceContext = deContext,
            missedRecord = sampleMissed(),
            recordedAtEpochMs = baseTime + 910_000L,
            unlockGate = { true }
        )
        assertTrue("Expected Rejected.CeContextRejected but got $result", result is CeTakeoverIntakeResult.Rejected.CeContextRejected)
        assertTrue(result.isGuaranteedNoWrite)
    }

    @Test
    fun s03_validDeliveredAndMissedIntake_persistsAndReadsBack() {
        val delResult = DirectBootCeTakeoverStore.recordDelivered(
            ceContext = ceContext,
            deliveryRecord = sampleDelivered(alarmId = 1L),
            recordedAtEpochMs = baseTime + 70_000L,
            unlockGate = { true }
        )
        assertTrue("Expected RecordedConfirmed for Delivered but got $delResult", delResult is CeTakeoverIntakeResult.RecordedConfirmed)

        val misResult = DirectBootCeTakeoverStore.recordMissed(
            ceContext = ceContext,
            missedRecord = sampleMissed(alarmId = 2L),
            recordedAtEpochMs = baseTime + 910_000L,
            unlockGate = { true }
        )
        assertTrue("Expected RecordedConfirmed for Missed but got $misResult", misResult is CeTakeoverIntakeResult.RecordedConfirmed)

        val state = DirectBootCeTakeoverStore.inspectStorageState(ceContext, unlockGate = { true })
        assertTrue("Expected ValidWithRecords but got $state", state is CeTakeoverStorageState.ValidWithRecords)
        val records = (state as CeTakeoverStorageState.ValidWithRecords).records
        assertEquals(2, records.size)
        assertTrue(records[0] is CeTakeoverRecord.Delivered)
        assertTrue(records[1] is CeTakeoverRecord.Missed)
    }

    @Test
    fun s04_idempotentRetry_doesNotReCommit() {
        val commitCounter = AtomicInteger(0)
        DirectBootCeTakeoverStore.commitSeam = { prefs, payload ->
            commitCounter.incrementAndGet()
            prefs.edit().putString(DirectBootCeTakeoverStore.KEY_CE_TAKEOVER_PAYLOAD, payload).commit()
        }

        val rec = sampleDelivered(alarmId = 5L)
        val first = DirectBootCeTakeoverStore.recordDelivered(
            ceContext = ceContext,
            deliveryRecord = rec,
            recordedAtEpochMs = baseTime + 70_000L,
            unlockGate = { true }
        )
        assertTrue(first is CeTakeoverIntakeResult.RecordedConfirmed)
        assertEquals(1, commitCounter.get())

        val second = DirectBootCeTakeoverStore.recordDelivered(
            ceContext = ceContext,
            deliveryRecord = rec,
            recordedAtEpochMs = baseTime + 80_000L,
            unlockGate = { true }
        )
        assertTrue("Expected AlreadyRecordedConfirmed on retry but got $second", second is CeTakeoverIntakeResult.AlreadyRecordedConfirmed)
        assertEquals("Idempotent retry must not invoke commitSeam again", 1, commitCounter.get())
    }

    @Test
    fun s05_contentConflictAndDeliveredMissedMutualExclusion() {
        val rec = sampleDelivered(alarmId = 7L, triggerTime = baseTime + 60_000L, deliveredAt = baseTime + 61_000L)
        val first = DirectBootCeTakeoverStore.recordDelivered(
            ceContext = ceContext,
            deliveryRecord = rec,
            recordedAtEpochMs = baseTime + 70_000L,
            unlockGate = { true }
        )
        assertTrue(first is CeTakeoverIntakeResult.RecordedConfirmed)

        // Same occurrence identity, different deliveredAt
        val alteredDelivered = rec.copy(deliveredAt = baseTime + 62_000L)
        val conflict1 = DirectBootCeTakeoverStore.recordDelivered(
            ceContext = ceContext,
            deliveryRecord = alteredDelivered,
            recordedAtEpochMs = baseTime + 75_000L,
            unlockGate = { true }
        )
        assertTrue("Expected ContentConflict but got $conflict1", conflict1 is CeTakeoverIntakeResult.Rejected.ContentConflict)

        // Same occurrence identity, attempted as Missed
        val conflictingMissed = DirectBootMissedRecord.createNormal(
            alarmId = 7L,
            triggerTime = baseTime + 60_000L,
            missedAt = baseTime + 900_000L,
            generation = rec.generation,
            instanceToken = rec.instanceToken,
            classification = DirectBootMissedRecord.CLASSIFICATION_OVERDUE
        )
        val conflict2 = DirectBootCeTakeoverStore.recordMissed(
            ceContext = ceContext,
            missedRecord = conflictingMissed,
            recordedAtEpochMs = baseTime + 910_000L,
            unlockGate = { true }
        )
        assertTrue("Expected DeliveredMissedConflict but got $conflict2", conflict2 is CeTakeoverIntakeResult.Rejected.DeliveredMissedConflict)
    }

    @Test
    fun s06_corruptAndUnsupportedSchema_failClosed() {
        val prefs = ceContext.getSharedPreferences(DirectBootCeTakeoverStore.PREFS_NAME, Context.MODE_PRIVATE)
        val rawCorrupt = "{not_valid_json"
        prefs.edit().putString(DirectBootCeTakeoverStore.KEY_CE_TAKEOVER_PAYLOAD, rawCorrupt).commit()

        val corruptResult = DirectBootCeTakeoverStore.recordDelivered(
            ceContext = ceContext,
            deliveryRecord = sampleDelivered(),
            recordedAtEpochMs = baseTime + 70_000L,
            unlockGate = { true }
        )
        assertTrue("Expected StorageCorrupt but got $corruptResult", corruptResult is CeTakeoverIntakeResult.Rejected.StorageCorrupt)
        assertEquals(rawCorrupt, prefs.getString(DirectBootCeTakeoverStore.KEY_CE_TAKEOVER_PAYLOAD, null))

        val rawUnsupported = JSONObject().apply {
            put("schemaVersion", 99)
            put("records", JSONArray())
        }.toString()
        prefs.edit().putString(DirectBootCeTakeoverStore.KEY_CE_TAKEOVER_PAYLOAD, rawUnsupported).commit()

        val unsupportedResult = DirectBootCeTakeoverStore.recordDelivered(
            ceContext = ceContext,
            deliveryRecord = sampleDelivered(),
            recordedAtEpochMs = baseTime + 70_000L,
            unlockGate = { true }
        )
        assertTrue("Expected UnsupportedSchema but got $unsupportedResult", unsupportedResult is CeTakeoverIntakeResult.Rejected.UnsupportedSchema)
        assertEquals(rawUnsupported, prefs.getString(DirectBootCeTakeoverStore.KEY_CE_TAKEOVER_PAYLOAD, null))
    }

    @Test
    fun s07_commitFalseExceptionAndReadbackMismatch_neverConfirm() {
        DirectBootCeTakeoverStore.commitSeam = { _, _ -> false }
        val rFalse = DirectBootCeTakeoverStore.recordDelivered(
            ceContext = ceContext,
            deliveryRecord = sampleDelivered(alarmId = 11L),
            recordedAtEpochMs = baseTime + 70_000L,
            unlockGate = { true }
        )
        assertTrue("Expected CommitFailed", rFalse is CeTakeoverIntakeResult.Uncertain.CommitFailed)

        DirectBootCeTakeoverStore.commitSeam = { _, _ -> throw IOException("disk full") }
        val rEx = DirectBootCeTakeoverStore.recordDelivered(
            ceContext = ceContext,
            deliveryRecord = sampleDelivered(alarmId = 11L),
            recordedAtEpochMs = baseTime + 70_000L,
            unlockGate = { true }
        )
        assertTrue("Expected CommitException", rEx is CeTakeoverIntakeResult.Uncertain.CommitException)

        // Commit returns true, but readback returns null (record disappeared)
        DirectBootCeTakeoverStore.resetSeamsForTesting()
        DirectBootCeTakeoverStore.readbackSeam = { null }
        val rReadback = DirectBootCeTakeoverStore.recordDelivered(
            ceContext = ceContext,
            deliveryRecord = sampleDelivered(alarmId = 11L),
            recordedAtEpochMs = baseTime + 70_000L,
            unlockGate = { true }
        )
        assertTrue("Expected ReadbackMismatch but got $rReadback", rReadback is CeTakeoverIntakeResult.Uncertain.ReadbackMismatch)
    }

    @Test
    fun s08_postCommitHookException_returnsPostCommitFailureNotUncertain() {
        val result = DirectBootCeTakeoverStore.recordDelivered(
            ceContext = ceContext,
            deliveryRecord = sampleDelivered(alarmId = 12L),
            recordedAtEpochMs = baseTime + 70_000L,
            unlockGate = { true },
            postCommitHook = { throw IllegalStateException("post-commit listener failed") }
        )
        assertTrue("Expected PostCommitFailure but got $result", result is CeTakeoverIntakeResult.PostCommitFailure)
        val postFail = result as CeTakeoverIntakeResult.PostCommitFailure
        assertEquals(12L, postFail.confirmedRecord.alarmId)
        assertEquals(1, postFail.totalRecords)
    }
}
