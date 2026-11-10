package com.sysadmindoc.alarmclock.directboot

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Deterministic unit tests for Direct Boot V2 Registration Marker (KNO-75 Phase D3-B2-P1B).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class DirectBootRegistrationMarkerTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext<Context>()
        val prefs = context.createDeviceProtectedStorageContext()
            .getSharedPreferences(DirectBootRegistrationMarker.PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
    }

    private fun sampleNormalIdentity(
        alarmId: Long = 101L,
        generation: Int = 1,
        instanceToken: String = "token_abc_123",
        triggerTime: Long = 1_770_000_000_000L
    ): DirectBootV2NormalIntentIdentity {
        return DirectBootV2NormalIntentIdentity(
            alarmId = alarmId,
            generation = generation,
            instanceToken = instanceToken,
            triggerTime = triggerTime,
            kind = DirectBootV2NormalIntentIdentity.KIND_NORMAL
        )
    }

    @Test
    fun test01_absentMarker_returnsAbsent() {
        val result = DirectBootRegistrationMarker.read(context)
        assertTrue(result is MarkerReadResult.Absent)
    }

    @Test
    fun test02_keyPresentWithNullOrEmpty_returnsCorrupt() {
        // Direct parse with null or empty/blank payload
        val resultNull = DirectBootRegistrationMarker.parse(null)
        assertTrue("parse(null) must return Corrupt", resultNull is MarkerReadResult.Corrupt)

        val resultEmpty = DirectBootRegistrationMarker.parse("")
        assertTrue("parse(\"\") must return Corrupt", resultEmpty is MarkerReadResult.Corrupt)

        val resultBlank = DirectBootRegistrationMarker.parse("   ")
        assertTrue("parse(\"   \") must return Corrupt", resultBlank is MarkerReadResult.Corrupt)

        // Storage with empty or blank string
        val prefs = context.createDeviceProtectedStorageContext()
            .getSharedPreferences(DirectBootRegistrationMarker.PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(DirectBootRegistrationMarker.KEY_V2_REGISTRATION_MARKER, "").commit()
        assertTrue(DirectBootRegistrationMarker.read(context) is MarkerReadResult.Corrupt)

        prefs.edit().putString(DirectBootRegistrationMarker.KEY_V2_REGISTRATION_MARKER, "   ").commit()
        assertTrue(DirectBootRegistrationMarker.read(context) is MarkerReadResult.Corrupt)

        // Storage containing non-string value (e.g. integer) which fails type retrieval
        prefs.edit().putInt(DirectBootRegistrationMarker.KEY_V2_REGISTRATION_MARKER, 12345).commit()
        assertTrue(prefs.contains(DirectBootRegistrationMarker.KEY_V2_REGISTRATION_MARKER))
        val readNonString = DirectBootRegistrationMarker.read(context)
        assertTrue(readNonString is MarkerReadResult.Corrupt)
    }

    @Test
    fun test03_sequenceMonotonicityThroughActualWrite() {
        // Start absent: stored sequence is 0
        val base = DirectBootRegistrationMarker.createInitialIdle(activeIdentity = sampleNormalIdentity())
        assertEquals(0L, base.opSeq)

        // Write 1: initial write advances sequence from 0 to 1
        val write1 = DirectBootRegistrationMarker.write(context, base)
        assertTrue("Write 1 must succeed", write1 is MarkerWriteResult.Success)
        val m1 = (write1 as MarkerWriteResult.Success).marker
        assertEquals(1L, m1.opSeq)

        val read1 = DirectBootRegistrationMarker.read(context)
        assertTrue(read1 is MarkerReadResult.Success)
        assertEquals(1L, (read1 as MarkerReadResult.Success).marker.opSeq)

        // Write 2: transition to IN_FLIGHT using m1 (which has opSeq = 1)
        val candidate2 = m1.copy(
            mainState = RegistrationMainState.IN_FLIGHT,
            phase = RegistrationPhase.REGISTERING_NEW,
            leaseOwner = "lease-worker-1",
            leaseEpochMs = 1000L,
            registeringTarget = sampleNormalIdentity(alarmId = 102L)
        )
        val write2 = DirectBootRegistrationMarker.write(context, candidate2)
        assertTrue("Write 2 must succeed", write2 is MarkerWriteResult.Success)
        val m2 = (write2 as MarkerWriteResult.Success).marker
        assertEquals(2L, m2.opSeq)

        // Write 3: transition back to IDLE using m2 (opSeq = 2)
        val candidate3 = m2.copy(
            mainState = RegistrationMainState.IDLE,
            phase = RegistrationPhase.NONE,
            leaseOwner = "",
            leaseEpochMs = 0L,
            activeIdentity = candidate2.registeringTarget,
            registeringTarget = null
        )
        val write3 = DirectBootRegistrationMarker.write(context, candidate3)
        assertTrue("Write 3 must succeed", write3 is MarkerWriteResult.Success)
        val m3 = (write3 as MarkerWriteResult.Success).marker
        assertEquals(3L, m3.opSeq)

        val finalRead = DirectBootRegistrationMarker.read(context)
        assertTrue(finalRead is MarkerReadResult.Success)
        assertEquals(3L, (finalRead as MarkerReadResult.Success).marker.opSeq)
    }

    @Test
    fun test04_casStaleWriteRejection() {
        val base = DirectBootRegistrationMarker.createInitialIdle()
        val write1 = DirectBootRegistrationMarker.write(context, base)
        assertTrue(write1 is MarkerWriteResult.Success)
        val m1 = (write1 as MarkerWriteResult.Success).marker
        assertEquals(1L, m1.opSeq)

        // Advance to sequence 2
        val next = m1.copy(
            mainState = RegistrationMainState.IN_FLIGHT,
            phase = RegistrationPhase.REGISTERING_NEW,
            leaseOwner = "lease-2",
            leaseEpochMs = 2000L,
            registeringTarget = sampleNormalIdentity()
        )
        val write2 = DirectBootRegistrationMarker.write(context, next)
        assertTrue(write2 is MarkerWriteResult.Success)
        assertEquals(2L, (write2 as MarkerWriteResult.Success).marker.opSeq)

        // Now attempt stale write with expectedOpSeq = 1L (while disk is at 2L)
        val staleAttempt = DirectBootRegistrationMarker.write(context, next, expectedOpSeq = 1L)
        assertTrue("Expected SequenceMismatch on stale expectedOpSeq", staleAttempt is MarkerWriteResult.SequenceMismatch)
        val mismatch = staleAttempt as MarkerWriteResult.SequenceMismatch
        assertEquals(1L, mismatch.expectedSeq)
        assertEquals(2L, mismatch.actualSeq)

        // Also attempt with marker whose opSeq is 1L without explicit expectedOpSeq
        val staleMarkerAttempt = DirectBootRegistrationMarker.write(context, m1)
        assertTrue("Expected SequenceMismatch on stale marker.opSeq", staleMarkerAttempt is MarkerWriteResult.SequenceMismatch)
        val mismatchMarker = staleMarkerAttempt as MarkerWriteResult.SequenceMismatch
        assertEquals(1L, mismatchMarker.expectedSeq)
        assertEquals(2L, mismatchMarker.actualSeq)
    }

    @Test
    fun test05_sameSequenceOverwriteRejection() {
        val base = DirectBootRegistrationMarker.createInitialIdle()
        val write1 = DirectBootRegistrationMarker.write(context, base)
        val m1 = (write1 as MarkerWriteResult.Success).marker
        assertEquals(1L, m1.opSeq)

        // First transition with expectedOpSeq = 1L succeeds and advances disk to 2L
        val next = m1.copy(
            mainState = RegistrationMainState.IN_FLIGHT,
            phase = RegistrationPhase.REGISTERING_NEW,
            leaseOwner = "lease-same",
            leaseEpochMs = 3000L,
            registeringTarget = sampleNormalIdentity()
        )
        val writeSuccess = DirectBootRegistrationMarker.write(context, next, expectedOpSeq = 1L)
        assertTrue(writeSuccess is MarkerWriteResult.Success)
        assertEquals(2L, (writeSuccess as MarkerWriteResult.Success).marker.opSeq)

        // Repeating the same write with expectedOpSeq = 1L must be rejected
        val writeRepeat = DirectBootRegistrationMarker.write(context, next, expectedOpSeq = 1L)
        assertTrue("Expected SequenceMismatch on duplicate expectedOpSeq", writeRepeat is MarkerWriteResult.SequenceMismatch)
        val mismatch = writeRepeat as MarkerWriteResult.SequenceMismatch
        assertEquals(1L, mismatch.expectedSeq)
        assertEquals(2L, mismatch.actualSeq)
    }

    @Test
    fun test06_sequenceOverflowThroughActualWrite() {
        assertEquals(1L, DirectBootRegistrationMarker.incrementOpSeq(0L))
        assertEquals(Long.MAX_VALUE, DirectBootRegistrationMarker.incrementOpSeq(Long.MAX_VALUE - 1L))

        try {
            DirectBootRegistrationMarker.incrementOpSeq(Long.MAX_VALUE)
            fail("Expected ArithmeticException on incrementing Long.MAX_VALUE")
        } catch (e: ArithmeticException) {
            assertTrue(e.message?.contains("overflow") == true)
        }

        // Put a marker with opSeq = Long.MAX_VALUE directly on disk
        val maxSeqMarker = DirectBootRegistrationMarker.createInitialIdle().copy(opSeq = Long.MAX_VALUE)
        val maxSeqWithDigest = maxSeqMarker.copy(payloadDigest = maxSeqMarker.computeDigest())
        val prefs = context.createDeviceProtectedStorageContext()
            .getSharedPreferences(DirectBootRegistrationMarker.PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(DirectBootRegistrationMarker.KEY_V2_REGISTRATION_MARKER, maxSeqWithDigest.toJsonString()).commit()

        // Attempting to write on storage with Long.MAX_VALUE must fail with SequenceOverflow
        val attempt = DirectBootRegistrationMarker.write(context, maxSeqWithDigest, expectedOpSeq = Long.MAX_VALUE)
        assertTrue("Expected SequenceOverflow result", attempt is MarkerWriteResult.SequenceOverflow)
        assertEquals(Long.MAX_VALUE, (attempt as MarkerWriteResult.SequenceOverflow).currentSeq)
    }

    @Test
    fun test07_concurrentWritersSerializedUnderLock() {
        val base = DirectBootRegistrationMarker.createInitialIdle()
        DirectBootRegistrationMarker.write(context, base)

        val threadCount = 8
        val latch = CountDownLatch(threadCount)
        val successCount = AtomicInteger(0)
        val mismatchCount = AtomicInteger(0)

        // All threads attempt to advance sequence from 1L concurrently
        for (i in 0 until threadCount) {
            Thread {
                val candidate = DirectBootRegistrationMarker(
                    schemaVersion = 1,
                    mainState = RegistrationMainState.IN_FLIGHT,
                    phase = RegistrationPhase.REGISTERING_NEW,
                    failureReason = RegistrationFailureReason.NONE,
                    retryCount = 0,
                    opSeq = 1L,
                    leaseOwner = "thread-$i",
                    leaseEpochMs = 1000L + i,
                    bootProvenance = BootProvenance(),
                    activeIdentity = null,
                    cancellingTarget = null,
                    registeringTarget = sampleNormalIdentity(alarmId = 200L + i),
                    updatedAtEpochMs = 1000L + i
                )
                val result = DirectBootRegistrationMarker.write(context, candidate, expectedOpSeq = 1L)
                if (result is MarkerWriteResult.Success) {
                    successCount.incrementAndGet()
                } else if (result is MarkerWriteResult.SequenceMismatch) {
                    mismatchCount.incrementAndGet()
                }
                latch.countDown()
            }.start()
        }

        assertTrue(latch.await(5, TimeUnit.SECONDS))
        // Exactly one concurrent writer must succeed at expectedOpSeq = 1L
        assertEquals(1, successCount.get())
        assertEquals(threadCount - 1, mismatchCount.get())

        val read = DirectBootRegistrationMarker.read(context)
        assertTrue(read is MarkerReadResult.Success)
        assertEquals(2L, (read as MarkerReadResult.Success).marker.opSeq)
    }

    @Test
    fun test08_markerWidePayloadModification_detected() {
        val base = DirectBootRegistrationMarker.createInitialIdle(activeIdentity = sampleNormalIdentity())
        val json = base.toJson()

        // Modify updatedAtEpochMs without updating payloadDigest
        json.put("updatedAtEpochMs", base.updatedAtEpochMs + 9999L)

        val result = DirectBootRegistrationMarker.parse(json.toString())
        assertTrue("Tampered payload must return Corrupt", result is MarkerReadResult.Corrupt)
        val corrupt = result as MarkerReadResult.Corrupt
        assertTrue("Reason must mention payloadDigest mismatch", corrupt.reason.contains("payloadDigest mismatch"))
    }

    @Test
    fun test09_nestedIdentityDigestModification_detected() {
        val identity = sampleNormalIdentity()
        val base = DirectBootRegistrationMarker.createInitialIdle(activeIdentity = identity)
        val json = base.toJson()

        // Modify occurrenceDigest inside activeIdentity
        val idJson = json.getJSONObject("activeIdentity")
        idJson.put("occurrenceDigest", "0000000000000000000000000000000000000000000000000000000000000000")
        json.put("activeIdentity", idJson)

        val result = DirectBootRegistrationMarker.parse(json.toString())
        assertTrue("Tampered identity digest must return Corrupt", result is MarkerReadResult.Corrupt)
        val corrupt = result as MarkerReadResult.Corrupt
        assertTrue("Reason must mention occurrenceDigest mismatch", corrupt.reason.contains("occurrenceDigest mismatch"))
    }

    @Test
    fun test10_missingOrBlankDigest_returnsCorrupt() {
        val base = DirectBootRegistrationMarker.createInitialIdle()

        // Missing payloadDigest
        val jsonNoDigest = base.toJson()
        jsonNoDigest.remove("payloadDigest")
        val resultNoDigest = DirectBootRegistrationMarker.parse(jsonNoDigest.toString())
        assertTrue(resultNoDigest is MarkerReadResult.Corrupt)
        assertTrue((resultNoDigest as MarkerReadResult.Corrupt).reason.contains("payloadDigest"))

        // Blank payloadDigest
        val jsonBlankDigest = base.toJson()
        jsonBlankDigest.put("payloadDigest", "   ")
        val resultBlank = DirectBootRegistrationMarker.parse(jsonBlankDigest.toString())
        assertTrue(resultBlank is MarkerReadResult.Corrupt)
    }

    @Test
    fun test11_wrongJsonTypes_returnsCorruptWithoutExceptions() {
        val wrongTypes = listOf(
            "schemaVersion" to "one",
            "retryCount" to "zero",
            "opSeq" to "invalid_number",
            "leaseEpochMs" to "not_a_long",
            "updatedAtEpochMs" to true,
            "mainState" to 123,
            "phase" to false,
            "failureReason" to 0,
            "leaseOwner" to 999,
            "payloadDigest" to JSONObject(),
            "bootProvenance" to "not_a_json_object",
            "activeIdentity" to "not_a_json_object"
        )

        for ((field, badValue) in wrongTypes) {
            val base = DirectBootRegistrationMarker.createInitialIdle(activeIdentity = sampleNormalIdentity())
            val json = base.toJson()
            json.put(field, badValue)

            val result = DirectBootRegistrationMarker.parse(json.toString())
            assertTrue("Expected Corrupt when field '$field' has bad type $badValue", result is MarkerReadResult.Corrupt)
        }
    }

    @Test
    fun test12_strictIntegralValidation_rejectsFractionalAndOverflowValues() {
        val base = DirectBootRegistrationMarker.createInitialIdle(activeIdentity = sampleNormalIdentity())

        // 1. Fractional values rejected for every numeric field
        val fractionalCases = listOf(
            "schemaVersion" to 1.5,
            "retryCount" to 1.25,
            "opSeq" to 5.5,
            "leaseEpochMs" to 1000.75,
            "updatedAtEpochMs" to 2000.5
        )
        for ((field, fracVal) in fractionalCases) {
            val json = base.toJson()
            json.put(field, fracVal)
            val result = DirectBootRegistrationMarker.parse(json.toString())
            assertTrue("Expected Corrupt for fractional $field: $fracVal", result is MarkerReadResult.Corrupt)
        }

        // 2. Fractional values rejected in BootProvenance
        val bpJson1 = JSONObject().apply { put("bootCount", 1.5) }
        val jsonBp1 = base.toJson().apply { put("bootProvenance", bpJson1) }
        assertTrue(DirectBootRegistrationMarker.parse(jsonBp1.toString()) is MarkerReadResult.Corrupt)

        val bpJson2 = JSONObject().apply { put("lastElapsedRealtime", 500.5) }
        val jsonBp2 = base.toJson().apply { put("bootProvenance", bpJson2) }
        assertTrue(DirectBootRegistrationMarker.parse(jsonBp2.toString()) is MarkerReadResult.Corrupt)

        val bpJson3 = JSONObject().apply { put("bootEpochEstimateMs", 1000.5) }
        val jsonBp3 = base.toJson().apply { put("bootProvenance", bpJson3) }
        assertTrue(DirectBootRegistrationMarker.parse(jsonBp3.toString()) is MarkerReadResult.Corrupt)

        // 3. Fractional values rejected in nested Identity
        val id = sampleNormalIdentity()
        val idJson1 = DirectBootRegistrationMarker.identityToJson(id).apply { put("alarmId", 101.5) }
        val jsonId1 = base.toJson().apply { put("activeIdentity", idJson1) }
        assertTrue(DirectBootRegistrationMarker.parse(jsonId1.toString()) is MarkerReadResult.Corrupt)

        val idJson2 = DirectBootRegistrationMarker.identityToJson(id).apply { put("generation", 1.5) }
        val jsonId2 = base.toJson().apply { put("activeIdentity", idJson2) }
        assertTrue(DirectBootRegistrationMarker.parse(jsonId2.toString()) is MarkerReadResult.Corrupt)

        val idJson3 = DirectBootRegistrationMarker.identityToJson(id).apply { put("triggerTime", 1000.5) }
        val jsonId3 = base.toJson().apply { put("activeIdentity", idJson3) }
        assertTrue(DirectBootRegistrationMarker.parse(jsonId3.toString()) is MarkerReadResult.Corrupt)

        // 4. Integer overflow rejected for Int fields
        val overflowIntCases = listOf(
            "schemaVersion" to Long.MAX_VALUE,
            "retryCount" to Long.MAX_VALUE
        )
        for ((field, overVal) in overflowIntCases) {
            val json = base.toJson()
            json.put(field, overVal)
            val result = DirectBootRegistrationMarker.parse(json.toString())
            assertTrue("Expected Corrupt for Int overflow in $field", result is MarkerReadResult.Corrupt)
        }

        // 5. Direct helper extraction unit checks
        assertEquals(42, DirectBootRegistrationMarker.extractExactInt(42, "testInt"))
        assertEquals(42L, DirectBootRegistrationMarker.extractExactLong(42L, "testLong"))
        assertEquals(Long.MAX_VALUE, DirectBootRegistrationMarker.extractExactLong(Long.MAX_VALUE, "maxLong"))

        try {
            DirectBootRegistrationMarker.extractExactInt(1.5, "frac")
            fail("Expected IllegalArgumentException on fractional Int")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message?.contains("fractional") == true)
        }

        try {
            DirectBootRegistrationMarker.extractExactLong(1.5, "fracLong")
            fail("Expected IllegalArgumentException on fractional Long")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message?.contains("fractional") == true)
        }

        try {
            DirectBootRegistrationMarker.extractExactInt(Long.MAX_VALUE, "overflowInt")
            fail("Expected IllegalArgumentException on Int overflow")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message?.contains("out of Int range") == true)
        }
    }

    @Test
    fun test13_legitimateLongPrecisionPreserved() {
        val largeAlarmId = 9_000_000_000_000_000_000L
        val largeTriggerTime = 1_770_000_000_000L
        val largeOpSeq = Long.MAX_VALUE - 1L
        val largeLeaseEpoch = 1_770_000_050_000L
        val largeUpdatedAt = 1_770_000_100_000L

        val identity = sampleNormalIdentity(alarmId = largeAlarmId, triggerTime = largeTriggerTime)
        val m = DirectBootRegistrationMarker(
            schemaVersion = 1,
            mainState = RegistrationMainState.IN_FLIGHT,
            phase = RegistrationPhase.REGISTERING_NEW,
            failureReason = RegistrationFailureReason.NONE,
            retryCount = 0,
            opSeq = largeOpSeq,
            leaseOwner = "lease-precision",
            leaseEpochMs = largeLeaseEpoch,
            bootProvenance = BootProvenance(lastElapsedRealtime = 999_999_999L, bootEpochEstimateMs = largeTriggerTime),
            activeIdentity = null,
            cancellingTarget = null,
            registeringTarget = identity,
            updatedAtEpochMs = largeUpdatedAt
        )
        val marker = m.copy(payloadDigest = m.computeDigest())

        val json = marker.toJsonString()
        val parsed = DirectBootRegistrationMarker.parse(json)
        assertTrue(parsed is MarkerReadResult.Success)
        val loaded = (parsed as MarkerReadResult.Success).marker

        assertEquals(largeOpSeq, loaded.opSeq)
        assertEquals(largeLeaseEpoch, loaded.leaseEpochMs)
        assertEquals(largeUpdatedAt, loaded.updatedAtEpochMs)
        assertEquals(largeAlarmId, loaded.registeringTarget?.alarmId)
        assertEquals(largeTriggerTime, loaded.registeringTarget?.triggerTime)
        assertEquals(999_999_999L, loaded.bootProvenance.lastElapsedRealtime)
        assertEquals(largeTriggerTime, loaded.bootProvenance.bootEpochEstimateMs)
    }

    @Test
    fun test14_failedBlockedRecoveryTargetPreservation() {
        val oldTarget = sampleNormalIdentity(alarmId = 101L, generation = 1)
        val newTarget = sampleNormalIdentity(alarmId = 102L, generation = 1)

        // 1. FAILED_BLOCKED retaining cancellingTarget only (e.g. cancellation failed during replacement)
        val mCancel = DirectBootRegistrationMarker(
            schemaVersion = 1,
            mainState = RegistrationMainState.FAILED_BLOCKED,
            phase = RegistrationPhase.NONE,
            failureReason = RegistrationFailureReason.IPC_EXCEPTION,
            retryCount = 3,
            opSeq = 5L,
            leaseOwner = "",
            leaseEpochMs = 0L,
            bootProvenance = BootProvenance(),
            activeIdentity = oldTarget,
            cancellingTarget = oldTarget,
            registeringTarget = null,
            updatedAtEpochMs = 2000L
        ).run { copy(payloadDigest = computeDigest()) }

        assertEquals(null, mCancel.validate())
        val parsedCancel = DirectBootRegistrationMarker.parse(mCancel.toJsonString())
        assertTrue(parsedCancel is MarkerReadResult.Success)
        val loadedCancel = (parsedCancel as MarkerReadResult.Success).marker
        assertEquals(oldTarget, loadedCancel.cancellingTarget)
        assertNull(loadedCancel.registeringTarget)

        // 2. FAILED_BLOCKED retaining registeringTarget only (e.g. registration uncertain)
        val mReg = DirectBootRegistrationMarker(
            schemaVersion = 1,
            mainState = RegistrationMainState.FAILED_BLOCKED,
            phase = RegistrationPhase.NONE,
            failureReason = RegistrationFailureReason.OS_UNCERTAIN,
            retryCount = 3,
            opSeq = 6L,
            leaseOwner = "",
            leaseEpochMs = 0L,
            bootProvenance = BootProvenance(),
            activeIdentity = null,
            cancellingTarget = null,
            registeringTarget = newTarget,
            updatedAtEpochMs = 2000L
        ).run { copy(payloadDigest = computeDigest()) }

        assertEquals(null, mReg.validate())
        val parsedReg = DirectBootRegistrationMarker.parse(mReg.toJsonString())
        assertTrue(parsedReg is MarkerReadResult.Success)
        val loadedReg = (parsedReg as MarkerReadResult.Success).marker
        assertNull(loadedReg.cancellingTarget)
        assertEquals(newTarget, loadedReg.registeringTarget)

        // 3. FAILED_BLOCKED retaining BOTH recovery targets (e.g. replace operation uncertain after cancel)
        val mBoth = DirectBootRegistrationMarker(
            schemaVersion = 1,
            mainState = RegistrationMainState.FAILED_BLOCKED,
            phase = RegistrationPhase.NONE,
            failureReason = RegistrationFailureReason.OS_UNCERTAIN,
            retryCount = 3,
            opSeq = 7L,
            leaseOwner = "",
            leaseEpochMs = 0L,
            bootProvenance = BootProvenance(),
            activeIdentity = oldTarget,
            cancellingTarget = oldTarget,
            registeringTarget = newTarget,
            updatedAtEpochMs = 2000L
        ).run { copy(payloadDigest = computeDigest()) }

        assertEquals(null, mBoth.validate())
        val parsedBoth = DirectBootRegistrationMarker.parse(mBoth.toJsonString())
        assertTrue(parsedBoth is MarkerReadResult.Success)
        val loadedBoth = (parsedBoth as MarkerReadResult.Success).marker
        assertEquals(oldTarget, loadedBoth.cancellingTarget)
        assertEquals(newTarget, loadedBoth.registeringTarget)

        // 4. FAILED_BLOCKED with identical cancellingTarget and registeringTarget is invalid
        val mIdentical = mBoth.copy(
            cancellingTarget = newTarget,
            registeringTarget = newTarget
        ).run { copy(payloadDigest = computeDigest()) }
        assertNotNull(mIdentical.validate())
        assertTrue(mIdentical.validate()!!.contains("identical cancellingTarget and registeringTarget"))

        // 5. FAILED_BLOCKED with active lease or non-NONE phase must still be rejected
        val mLease = mBoth.copy(leaseOwner = "leak-owner").run { copy(payloadDigest = computeDigest()) }
        assertNotNull(mLease.validate())
        assertTrue(mLease.validate()!!.contains("blank leaseOwner"))

        val mPhase = mBoth.copy(phase = RegistrationPhase.REGISTERING_NEW).run { copy(payloadDigest = computeDigest()) }
        assertNotNull(mPhase.validate())
        assertTrue(mPhase.validate()!!.contains("phase NONE"))

        val mNoneReason = mBoth.copy(failureReason = RegistrationFailureReason.NONE).run { copy(payloadDigest = computeDigest()) }
        assertNotNull(mNoneReason.validate())
        assertTrue(mNoneReason.validate()!!.contains("failureReason"))
    }

    @Test
    fun test15_missingRequiredFields_returnsCorrupt() {
        val requiredFields = listOf(
            "schemaVersion", "mainState", "phase", "failureReason",
            "retryCount", "opSeq", "leaseOwner", "leaseEpochMs",
            "updatedAtEpochMs", "payloadDigest"
        )

        for (field in requiredFields) {
            val base = DirectBootRegistrationMarker.createInitialIdle()
            val json = base.toJson()
            json.remove(field)

            val result = DirectBootRegistrationMarker.parse(json.toString())
            assertTrue("Expected Corrupt when missing field '$field'", result is MarkerReadResult.Corrupt)
            val corrupt = result as MarkerReadResult.Corrupt
            assertTrue(corrupt.reason.contains(field))
        }
    }

    @Test
    fun test16_invalidPhaseSpecificTargets_rejected() {
        val target = sampleNormalIdentity()

        // 1. REGISTERING_NEW requires registeringTarget != null
        val noRegistering = DirectBootRegistrationMarker(
            schemaVersion = 1,
            mainState = RegistrationMainState.IN_FLIGHT,
            phase = RegistrationPhase.REGISTERING_NEW,
            failureReason = RegistrationFailureReason.NONE,
            retryCount = 0,
            opSeq = 1L,
            leaseOwner = "lease-1",
            leaseEpochMs = 1000L,
            bootProvenance = BootProvenance(),
            activeIdentity = null,
            cancellingTarget = null,
            registeringTarget = null,
            updatedAtEpochMs = 1000L
        )
        assertNotNull(noRegistering.validate())
        assertTrue(noRegistering.validate()!!.contains("REGISTERING_NEW"))

        // 2. CANCELLING_OLD requires cancellingTarget != null
        val noCancelling = DirectBootRegistrationMarker(
            schemaVersion = 1,
            mainState = RegistrationMainState.IN_FLIGHT,
            phase = RegistrationPhase.CANCELLING_OLD,
            failureReason = RegistrationFailureReason.NONE,
            retryCount = 0,
            opSeq = 1L,
            leaseOwner = "lease-1",
            leaseEpochMs = 1000L,
            bootProvenance = BootProvenance(),
            activeIdentity = null,
            cancellingTarget = null,
            registeringTarget = target,
            updatedAtEpochMs = 1000L
        )
        assertNotNull(noCancelling.validate())
        assertTrue(noCancelling.validate()!!.contains("CANCELLING_OLD"))

        // 3. COMPENSATING requires at least one recovery target
        val noCompTargets = DirectBootRegistrationMarker(
            schemaVersion = 1,
            mainState = RegistrationMainState.IN_FLIGHT,
            phase = RegistrationPhase.COMPENSATING,
            failureReason = RegistrationFailureReason.NONE,
            retryCount = 1,
            opSeq = 1L,
            leaseOwner = "lease-1",
            leaseEpochMs = 1000L,
            bootProvenance = BootProvenance(),
            activeIdentity = null,
            cancellingTarget = null,
            registeringTarget = null,
            updatedAtEpochMs = 1000L
        )
        assertNotNull(noCompTargets.validate())
        assertTrue(noCompTargets.validate()!!.contains("COMPENSATING"))

        // 4. IDLE must not carry in-flight targets
        val idleWithTarget = DirectBootRegistrationMarker(
            schemaVersion = 1,
            mainState = RegistrationMainState.IDLE,
            phase = RegistrationPhase.NONE,
            failureReason = RegistrationFailureReason.NONE,
            retryCount = 0,
            opSeq = 0L,
            leaseOwner = "",
            leaseEpochMs = 0L,
            bootProvenance = BootProvenance(),
            activeIdentity = null,
            cancellingTarget = target,
            registeringTarget = null,
            updatedAtEpochMs = 1000L
        )
        assertNotNull(idleWithTarget.validate())
        assertTrue(idleWithTarget.validate()!!.contains("cancellingTarget"))
    }

    @Test
    fun test17_commitFalseAndException_handledSafely() {
        val base = DirectBootRegistrationMarker.createInitialIdle()

        // Committer returns false
        val resultFalse = DirectBootRegistrationMarker.writeInternal(
            context = context,
            marker = base,
            editorCommitter = { _, _ -> false }
        )
        assertTrue(resultFalse is MarkerWriteResult.CommitFailed)

        // Committer throws IOException
        val ex = IOException("Simulated disk I/O error")
        val resultEx = DirectBootRegistrationMarker.writeInternal(
            context = context,
            marker = base,
            editorCommitter = { _, _ -> throw ex }
        )
        assertTrue(resultEx is MarkerWriteResult.CommitException)
        assertEquals(ex, (resultEx as MarkerWriteResult.CommitException).cause)
    }

    @Test
    fun test18_corruptPayloadPreservation_refusesOverwrite() {
        val prefs = context.createDeviceProtectedStorageContext()
            .getSharedPreferences(DirectBootRegistrationMarker.PREFS_NAME, Context.MODE_PRIVATE)
        val corruptPayload = "{ malformed json content that cannot be parsed "
        prefs.edit().putString(DirectBootRegistrationMarker.KEY_V2_REGISTRATION_MARKER, corruptPayload).commit()

        val base = DirectBootRegistrationMarker.createInitialIdle()
        val writeResult = DirectBootRegistrationMarker.write(context, base)

        assertTrue(writeResult is MarkerWriteResult.StorageCorrupt)

        // Verify corrupted raw string was preserved intact
        val rawAfter = prefs.getString(DirectBootRegistrationMarker.KEY_V2_REGISTRATION_MARKER, null)
        assertEquals(corruptPayload, rawAfter)
    }

    @Test
    fun test19_unsupportedSchemaPreservation_refusesOverwrite() {
        val prefs = context.createDeviceProtectedStorageContext()
            .getSharedPreferences(DirectBootRegistrationMarker.PREFS_NAME, Context.MODE_PRIVATE)
        val base = DirectBootRegistrationMarker.createInitialIdle()
        val json = base.toJson()
        json.put("schemaVersion", 999)
        val payload999 = json.toString()
        prefs.edit().putString(DirectBootRegistrationMarker.KEY_V2_REGISTRATION_MARKER, payload999).commit()

        val readResult = DirectBootRegistrationMarker.read(context)
        assertTrue(readResult is MarkerReadResult.UnsupportedSchema)
        assertEquals(999, (readResult as MarkerReadResult.UnsupportedSchema).foundVersion)

        val writeResult = DirectBootRegistrationMarker.write(context, base)
        assertTrue(writeResult is MarkerWriteResult.UnsupportedSchema)
        assertEquals(999, (writeResult as MarkerWriteResult.UnsupportedSchema).foundVersion)

        // Verify version 999 payload remains intact
        assertEquals(payload999, prefs.getString(DirectBootRegistrationMarker.KEY_V2_REGISTRATION_MARKER, null))
    }

    @Test
    fun test20_queueAndLedgerPayloadsUnchanged() {
        val prefs = context.createDeviceProtectedStorageContext()
            .getSharedPreferences(DirectBootRegistrationMarker.PREFS_NAME, Context.MODE_PRIVATE)

        val dummyQueuePayload = "{\"schemaVersion\":1,\"items\":[]}"
        val dummyLedgerPayload = "{\"schemaVersion\":1,\"records\":[]}"

        prefs.edit()
            .putString(DirectBootAlarmCache.KEY_QUEUE_PAYLOAD, dummyQueuePayload)
            .putString(DirectBootDeliveryLedger.KEY_DELIVERY_LEDGER_PAYLOAD, dummyLedgerPayload)
            .commit()

        val marker = DirectBootRegistrationMarker.createInitialIdle(activeIdentity = sampleNormalIdentity())
        DirectBootRegistrationMarker.write(context, marker)
        DirectBootRegistrationMarker.read(context)

        // Verify queue and ledger payloads remained untouched
        assertEquals(dummyQueuePayload, prefs.getString(DirectBootAlarmCache.KEY_QUEUE_PAYLOAD, null))
        assertEquals(dummyLedgerPayload, prefs.getString(DirectBootDeliveryLedger.KEY_DELIVERY_LEDGER_PAYLOAD, null))
    }

    @Test
    fun test21_retryCountBoundaries() {
        val base = DirectBootRegistrationMarker.createInitialIdle()

        // Valid boundaries: 0 and 3 (without payloadDigest set to test structural validator)
        assertEquals(null, base.copy(retryCount = 0, payloadDigest = "").validate())
        assertEquals(null, base.copy(retryCount = 3, payloadDigest = "").validate())

        // Invalid: -1 and 4
        assertNotNull(base.copy(retryCount = -1, payloadDigest = "").validate())
        assertNotNull(base.copy(retryCount = 4, payloadDigest = "").validate())

        // Also verify when payloadDigest is computed
        val valid0 = base.copy(retryCount = 0).run { copy(payloadDigest = computeDigest()) }
        assertEquals(null, valid0.validate())
        val valid3 = base.copy(retryCount = 3).run { copy(payloadDigest = computeDigest()) }
        assertEquals(null, valid3.validate())

        // Verify parser rejects retryCount out of bounds in JSON
        val json = base.toJson()
        json.put("retryCount", 4)
        assertTrue(DirectBootRegistrationMarker.parse(json.toString()) is MarkerReadResult.Corrupt)

        json.put("retryCount", -1)
        assertTrue(DirectBootRegistrationMarker.parse(json.toString()) is MarkerReadResult.Corrupt)
    }

    @Test
    fun test22_unicodeInstanceTokenPreserved() {
        val unicodeToken = "token-⏰-アラーム-テスト-12345-★"
        val identity = sampleNormalIdentity(instanceToken = unicodeToken)
        val marker = DirectBootRegistrationMarker.createInitialIdle(activeIdentity = identity)

        val parsed = DirectBootRegistrationMarker.parse(marker.toJsonString())
        assertTrue(parsed is MarkerReadResult.Success)
        val loaded = (parsed as MarkerReadResult.Success).marker
        assertEquals(unicodeToken, loaded.activeIdentity?.instanceToken)
        assertEquals(identity.computeDigest(), loaded.activeIdentity?.computeDigest())
    }

    @Test
    fun test23_distinctNormalGenerations() {
        for (gen in listOf(1, 2, 5, 100)) {
            val identity = sampleNormalIdentity(alarmId = 50L, generation = gen)
            val marker = DirectBootRegistrationMarker.createInitialIdle(activeIdentity = identity)
            val parsed = DirectBootRegistrationMarker.parse(marker.toJsonString())
            assertTrue(parsed is MarkerReadResult.Success)
            assertEquals(gen, (parsed as MarkerReadResult.Success).marker.activeIdentity?.generation)
        }
    }

    @Test
    fun test24_allMainStates_roundtrip() {
        val identity = sampleNormalIdentity()
        for (state in RegistrationMainState.values()) {
            val marker = when (state) {
                RegistrationMainState.IDLE -> DirectBootRegistrationMarker.createInitialIdle(activeIdentity = identity)
                RegistrationMainState.IN_FLIGHT -> {
                    val m = DirectBootRegistrationMarker(
                        schemaVersion = 1,
                        mainState = RegistrationMainState.IN_FLIGHT,
                        phase = RegistrationPhase.REGISTERING_NEW,
                        failureReason = RegistrationFailureReason.NONE,
                        retryCount = 0,
                        opSeq = 1L,
                        leaseOwner = "lease-uuid-1",
                        leaseEpochMs = 1000L,
                        bootProvenance = BootProvenance(),
                        activeIdentity = null,
                        cancellingTarget = null,
                        registeringTarget = identity,
                        updatedAtEpochMs = 1000L
                    )
                    m.copy(payloadDigest = m.computeDigest())
                }
                RegistrationMainState.FAILED_BLOCKED -> {
                    val m = DirectBootRegistrationMarker(
                        schemaVersion = 1,
                        mainState = RegistrationMainState.FAILED_BLOCKED,
                        phase = RegistrationPhase.NONE,
                        failureReason = RegistrationFailureReason.PERMISSION_DENIED,
                        retryCount = 3,
                        opSeq = 2L,
                        leaseOwner = "",
                        leaseEpochMs = 0L,
                        bootProvenance = BootProvenance(),
                        activeIdentity = null,
                        cancellingTarget = null,
                        registeringTarget = null,
                        updatedAtEpochMs = 2000L
                    )
                    m.copy(payloadDigest = m.computeDigest())
                }
            }

            val json = marker.toJsonString()
            val parsed = DirectBootRegistrationMarker.parse(json)
            assertTrue("Expected success for state $state", parsed is MarkerReadResult.Success)
            assertEquals(state, (parsed as MarkerReadResult.Success).marker.mainState)
        }
    }

    @Test
    fun test25_allOperationPhases_roundtrip() {
        val identity = sampleNormalIdentity()
        for (phase in RegistrationPhase.values()) {
            val marker = if (phase == RegistrationPhase.NONE) {
                DirectBootRegistrationMarker.createInitialIdle(activeIdentity = identity)
            } else {
                val m = DirectBootRegistrationMarker(
                    schemaVersion = 1,
                    mainState = RegistrationMainState.IN_FLIGHT,
                    phase = phase,
                    failureReason = RegistrationFailureReason.NONE,
                    retryCount = 0,
                    opSeq = 1L,
                    leaseOwner = "lease-owner-phase",
                    leaseEpochMs = 1000L,
                    bootProvenance = BootProvenance(),
                    activeIdentity = null,
                    cancellingTarget = if (phase != RegistrationPhase.REGISTERING_NEW) identity else null,
                    registeringTarget = if (phase != RegistrationPhase.CANCELLING_OLD) identity else null,
                    updatedAtEpochMs = 1000L
                )
                m.copy(payloadDigest = m.computeDigest())
            }

            val parsed = DirectBootRegistrationMarker.parse(marker.toJsonString())
            assertTrue("Expected success for phase $phase", parsed is MarkerReadResult.Success)
            assertEquals(phase, (parsed as MarkerReadResult.Success).marker.phase)
        }
    }

    @Test
    fun test26_allFailureReasons_roundtrip() {
        for (reason in RegistrationFailureReason.values()) {
            val marker = if (reason == RegistrationFailureReason.NONE) {
                DirectBootRegistrationMarker.createInitialIdle()
            } else {
                val m = DirectBootRegistrationMarker(
                    schemaVersion = 1,
                    mainState = RegistrationMainState.FAILED_BLOCKED,
                    phase = RegistrationPhase.NONE,
                    failureReason = reason,
                    retryCount = 3,
                    opSeq = 1L,
                    leaseOwner = "",
                    leaseEpochMs = 0L,
                    bootProvenance = BootProvenance(),
                    activeIdentity = null,
                    cancellingTarget = null,
                    registeringTarget = null,
                    updatedAtEpochMs = 1000L
                )
                m.copy(payloadDigest = m.computeDigest())
            }

            val parsed = DirectBootRegistrationMarker.parse(marker.toJsonString())
            assertTrue("Expected success for reason $reason", parsed is MarkerReadResult.Success)
            assertEquals(reason, (parsed as MarkerReadResult.Success).marker.failureReason)
        }
    }

    @Test
    fun test27_threeIdentityFields_simultaneouslyHandled() {
        val active = sampleNormalIdentity(alarmId = 101L, generation = 1)
        val cancelling = sampleNormalIdentity(alarmId = 101L, generation = 2)
        val registering = sampleNormalIdentity(alarmId = 102L, generation = 1)

        val m = DirectBootRegistrationMarker(
            schemaVersion = 1,
            mainState = RegistrationMainState.IN_FLIGHT,
            phase = RegistrationPhase.COMPENSATING,
            failureReason = RegistrationFailureReason.NONE,
            retryCount = 1,
            opSeq = 12L,
            leaseOwner = "uuid-comp",
            leaseEpochMs = 5000L,
            bootProvenance = BootProvenance(bootCount = 4),
            activeIdentity = active,
            cancellingTarget = cancelling,
            registeringTarget = registering,
            updatedAtEpochMs = 5000L
        )
        val marker = m.copy(payloadDigest = m.computeDigest())

        val parsed = DirectBootRegistrationMarker.parse(marker.toJsonString())
        assertTrue(parsed is MarkerReadResult.Success)
        val loaded = (parsed as MarkerReadResult.Success).marker
        assertEquals(active, loaded.activeIdentity)
        assertEquals(cancelling, loaded.cancellingTarget)
        assertEquals(registering, loaded.registeringTarget)
    }
}
