package com.sysadmindoc.alarmclock.directboot

import android.content.Context
import android.content.SharedPreferences
import androidx.test.core.app.ApplicationProvider
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Behavioral RED test suite for KNO-75 B1c-S1 Durable Owner Store (`DirectBootDurableOwnerStore`).
 *
 * Scope & Isolation (`C7-B1c-S1-R1`):
 * - Uses a test-local reflection contract adapter so the test target compiles cleanly alongside
 *   existing suites while `DirectBootDurableOwnerStore` is not yet implemented in `src/main`.
 * - Never fabricates production success: if `DirectBootDurableOwnerStore` does not exist yet,
 *   every test fails explicitly with `BLOCKED_NOT_EXECUTED` so it is never misclassified as passing
 *   or as a pure compile-only error.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class DirectBootDurableOwnerStoreTest {

    private lateinit var context: Context
    private lateinit var deContext: Context
    private lateinit var prefs: SharedPreferences

    private val baseTime = 1_700_000_000_000L

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        deContext = context.createDeviceProtectedStorageContext()
        prefs = deContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
    }

    @After
    fun tearDown() {
        prefs.edit().clear().commit()
    }

    private fun requireProductionStoreClass(): Class<*> {
        return try {
            Class.forName(STORE_FQN)
        } catch (e: ClassNotFoundException) {
            fail("BLOCKED_NOT_EXECUTED: $STORE_FQN is not implemented yet in production (S1-R1 TEST_ONLY_RED)")
            throw AssertionError("Unreachable")
        }
    }

    private fun invokeStoreMethod(
        methodName: String,
        parameterTypes: Array<Class<*>>,
        vararg args: Any?
    ): Any? {
        val storeClass = requireProductionStoreClass()
        val instance = try {
            storeClass.getField("INSTANCE").get(null)
        } catch (_: NoSuchFieldException) {
            null
        }
        val method = try {
            storeClass.getMethod(methodName, *parameterTypes)
        } catch (e: NoSuchMethodException) {
            fail("BLOCKED_NOT_EXECUTED: Expected S1 contract method $STORE_FQN#$methodName(${parameterTypes.joinToString { it.simpleName }}) missing: ${e.message}")
            throw AssertionError("Unreachable")
        }
        return method.invoke(instance, *args)
    }

    private fun sampleCanonicalOwnerDigest(
        schemaVersion: Int = 1,
        state: String = "IDLE",
        ownerSeq: Long = 1L,
        bootCount: Int? = 3,
        lastElapsedRealtime: Long? = 50_000L,
        bootEpochEstimateMs: Long? = baseTime - 50_000L,
        handoverId: String = "",
        claimAlarmId: Long = -1L,
        claimTriggerTime: Long = 0L,
        claimGeneration: Int = 0,
        claimInstanceToken: String = "",
        claimRestoreEpoch: Long = 0L,
        sessionAlarmId: Long = -1L,
        sessionTriggerTime: Long = 0L,
        sessionGeneration: Int = 0,
        sessionInstanceToken: String = "",
        sessionRestoreEpoch: Long = 0L,
        updatedAtEpochMs: Long = baseTime
    ): String {
        val raw = buildString {
            append("OWNER_V1\n")
            append("schemaVersion:").append(schemaVersion).append('\n')
            append("state:").append(state).append('\n')
            append("ownerSeq:").append(ownerSeq).append('\n')
            append("bootCount:").append(bootCount?.toString() ?: "<null>").append('\n')
            append("lastElapsedRealtime:").append(lastElapsedRealtime?.toString() ?: "<null>").append('\n')
            append("bootEpochEstimateMs:").append(bootEpochEstimateMs?.toString() ?: "<null>").append('\n')
            append("handoverId:").append(handoverId.toByteArray(StandardCharsets.UTF_8).size).append(':').append(handoverId).append('\n')
            append("claimAlarmId:").append(claimAlarmId).append('\n')
            append("claimTriggerTime:").append(claimTriggerTime).append('\n')
            append("claimGeneration:").append(claimGeneration).append('\n')
            append("claimInstanceToken:").append(claimInstanceToken.toByteArray(StandardCharsets.UTF_8).size).append(':').append(claimInstanceToken).append('\n')
            append("claimRestoreEpoch:").append(claimRestoreEpoch).append('\n')
            append("sessionAlarmId:").append(sessionAlarmId).append('\n')
            append("sessionTriggerTime:").append(sessionTriggerTime).append('\n')
            append("sessionGeneration:").append(sessionGeneration).append('\n')
            append("sessionInstanceToken:").append(sessionInstanceToken.toByteArray(StandardCharsets.UTF_8).size).append(':').append(sessionInstanceToken).append('\n')
            append("sessionRestoreEpoch:").append(sessionRestoreEpoch).append('\n')
            append("updatedAtEpochMs:").append(updatedAtEpochMs).append('\n')
        }
        val md = MessageDigest.getInstance("SHA-256")
        return md.digest(raw.toByteArray(StandardCharsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }

    private fun buildValidOwnerJson(
        schemaVersion: Int = 1,
        state: String = "IDLE",
        ownerSeq: Long = 1L,
        updatedAtEpochMs: Long = baseTime,
        bootCount: Int? = 3,
        lastElapsedRealtime: Long? = 50_000L,
        bootEpochEstimateMs: Long? = baseTime - 50_000L,
        handoverId: String? = null,
        claimToken: String? = null,
        sessionToken: String? = null
    ): JSONObject {
        val digest = sampleCanonicalOwnerDigest(
            schemaVersion = schemaVersion,
            state = state,
            ownerSeq = ownerSeq,
            bootCount = bootCount,
            lastElapsedRealtime = lastElapsedRealtime,
            bootEpochEstimateMs = bootEpochEstimateMs,
            handoverId = handoverId.orEmpty(),
            claimAlarmId = if (handoverId != null) 101L else -1L,
            claimTriggerTime = if (handoverId != null) baseTime + 10_000L else 0L,
            claimGeneration = if (handoverId != null) 1 else 0,
            claimInstanceToken = claimToken.orEmpty(),
            claimRestoreEpoch = if (handoverId != null) 1L else 0L,
            sessionAlarmId = if (sessionToken != null) 101L else -1L,
            sessionTriggerTime = if (sessionToken != null) baseTime + 10_000L else 0L,
            sessionGeneration = if (sessionToken != null) 1 else 0,
            sessionInstanceToken = sessionToken.orEmpty(),
            sessionRestoreEpoch = if (sessionToken != null) 1L else 0L,
            updatedAtEpochMs = updatedAtEpochMs
        )
        return JSONObject().apply {
            put("schemaVersion", schemaVersion)
            put("state", state)
            put("ownerSeq", ownerSeq)
            put("bootProvenance", JSONObject().apply {
                if (bootCount != null) put("bootCount", bootCount)
                if (lastElapsedRealtime != null) put("lastElapsedRealtime", lastElapsedRealtime)
                if (bootEpochEstimateMs != null) put("bootEpochEstimateMs", bootEpochEstimateMs)
            })
            if (handoverId != null) {
                put("handoverClaim", JSONObject().apply {
                    put("handoverId", handoverId)
                    put("alarmId", 101L)
                    put("triggerTime", baseTime + 10_000L)
                    put("generation", 1)
                    put("instanceToken", claimToken ?: "v2_tok_101")
                    put("restoreEpoch", 1L)
                })
            } else {
                put("handoverClaim", JSONObject.NULL)
            }
            if (sessionToken != null) {
                put("activeSession", JSONObject().apply {
                    put("alarmId", 101L)
                    put("triggerTime", baseTime + 10_000L)
                    put("generation", 1)
                    put("instanceToken", sessionToken)
                    put("restoreEpoch", 1L)
                })
            } else {
                put("activeSession", JSONObject.NULL)
            }
            put("updatedAtEpochMs", updatedAtEpochMs)
            put("payloadDigest", digest)
        }
    }

    // 1. Absent -> sequence 0
    @Test
    fun s1_o01_absentOwner_returnsAbsentWithSequenceZero() {
        val result = invokeStoreMethod("read", arrayOf(Context::class.java), deContext)
        assertNotNull(result)
        assertEquals("Absent", result!!.javaClass.simpleName)
        assertFalse(prefs.contains(KEY_DIRECT_BOOT_DURABLE_OWNER))
    }

    // 2. Initial CAS 0 -> 1
    @Test
    fun s1_o02_initialCasWrite_zeroToOneSucceeds() {
        val storeClass = requireProductionStoreClass()
        val initial = invokeStoreMethod(
            "createInitialIdle",
            arrayOf(BootProvenance::class.java, Long::class.javaPrimitiveType!!),
            BootProvenance(bootCount = 1, lastElapsedRealtime = 10_000L, bootEpochEstimateMs = baseTime - 10_000L),
            baseTime
        )
        assertNotNull(initial)
        val writeMethod = storeClass.getMethod(
            "write",
            Context::class.java,
            initial!!.javaClass,
            Long::class.javaObjectType
        )
        val instance = storeClass.getField("INSTANCE").get(null)
        val writeRes = writeMethod.invoke(instance, deContext, initial, 0L)
        assertEquals("Success", writeRes!!.javaClass.simpleName)

        val readAfter = invokeStoreMethod("read", arrayOf(Context::class.java), deContext)
        assertEquals("Success", readAfter!!.javaClass.simpleName)
        val owner = readAfter.javaClass.getMethod("getOwner").invoke(readAfter)
        val seq = owner!!.javaClass.getMethod("getOwnerSeq").invoke(owner) as Long
        assertEquals(1L, seq)
    }

    // 3. CAS mismatch rejected
    @Test
    fun s1_o03_casMismatch_rejectedAndPreservesExistingState() {
        val storeClass = requireProductionStoreClass()
        val initial = invokeStoreMethod(
            "createInitialIdle",
            arrayOf(BootProvenance::class.java, Long::class.javaPrimitiveType!!),
            BootProvenance(bootCount = 1, lastElapsedRealtime = 10_000L, bootEpochEstimateMs = baseTime - 10_000L),
            baseTime
        )!!
        val instance = storeClass.getField("INSTANCE").get(null)
        val writeMethod = storeClass.getMethod(
            "write",
            Context::class.java,
            initial.javaClass,
            Long::class.javaObjectType
        )
        assertEquals("Success", writeMethod.invoke(instance, deContext, initial, 0L)!!.javaClass.simpleName)

        val staleAttempt = writeMethod.invoke(instance, deContext, initial, 0L)
        assertEquals("SequenceMismatch", staleAttempt!!.javaClass.simpleName)
    }

    // 4. Long.MAX_VALUE overflow rejected
    @Test
    fun s1_o04_sequenceOverflow_longMaxValueRejected() {
        val maxPayload = buildValidOwnerJson(ownerSeq = Long.MAX_VALUE).toString()
        prefs.edit().putString(KEY_DIRECT_BOOT_DURABLE_OWNER, maxPayload).commit()

        val readMax = invokeStoreMethod("read", arrayOf(Context::class.java), deContext)
        assertEquals("Success", readMax!!.javaClass.simpleName)

        val storeClass = requireProductionStoreClass()
        val owner = readMax.javaClass.getMethod("getOwner").invoke(readMax)!!
        val writeMethod = storeClass.getMethod(
            "write",
            Context::class.java,
            owner.javaClass,
            Long::class.javaObjectType
        )
        val instance = storeClass.getField("INSTANCE").get(null)
        val overflowResult = writeMethod.invoke(instance, deContext, owner, Long.MAX_VALUE)
        assertEquals("SequenceOverflow", overflowResult!!.javaClass.simpleName)
        assertEquals(maxPayload, prefs.getString(KEY_DIRECT_BOOT_DURABLE_OWNER, null))
    }

    // 5. IDLE / HANDOVER_PENDING / V2_ACTIVE
    @Test
    fun s1_o05_ownerStates_idleHandoverPendingV2Active_roundTrip() {
        val payloads = listOf(
            "IDLE" to buildValidOwnerJson(state = "IDLE", ownerSeq = 1L),
            "HANDOVER_PENDING" to buildValidOwnerJson(
                state = "HANDOVER_PENDING",
                ownerSeq = 2L,
                handoverId = "ho_101_1",
                claimToken = "v2_tok_101"
            ),
            "V2_ACTIVE" to buildValidOwnerJson(
                state = "V2_ACTIVE",
                ownerSeq = 3L,
                sessionToken = "v2_tok_101"
            )
        )
        for ((expectedState, json) in payloads) {
            val readResult = invokeStoreMethod("parse", arrayOf(String::class.java), json.toString())
            assertEquals("Success", readResult!!.javaClass.simpleName)
            val owner = readResult.javaClass.getMethod("getOwner").invoke(readResult)!!
            val actualState = owner.javaClass.getMethod("getState").invoke(owner).toString()
            assertEquals(expectedState, actualState)
        }
    }

    // 6. Strict state invariants
    @Test
    fun s1_o06_strictStateInvariants_rejectsInvalidStateCombinations() {
        // IDLE must not carry handoverClaim or activeSession
        val idleWithClaim = buildValidOwnerJson(
            state = "IDLE",
            ownerSeq = 1L,
            handoverId = "ho_invalid",
            claimToken = "tok_invalid"
        )
        val res1 = invokeStoreMethod("parse", arrayOf(String::class.java), idleWithClaim.toString())
        assertEquals("Corrupt", res1!!.javaClass.simpleName)

        // HANDOVER_PENDING must have ownerSeq >= 1 and non-null handoverClaim
        val handoverZeroSeq = buildValidOwnerJson(
            state = "HANDOVER_PENDING",
            ownerSeq = 0L,
            handoverId = "ho_zero",
            claimToken = "tok_zero"
        )
        val res2 = invokeStoreMethod("parse", arrayOf(String::class.java), handoverZeroSeq.toString())
        assertEquals("Corrupt", res2!!.javaClass.simpleName)

        // V2_ACTIVE must have non-null activeSession and null handoverClaim
        val activeMissingSession = buildValidOwnerJson(
            state = "V2_ACTIVE",
            ownerSeq = 2L,
            sessionToken = null
        )
        val res3 = invokeStoreMethod("parse", arrayOf(String::class.java), activeMissingSession.toString())
        assertEquals("Corrupt", res3!!.javaClass.simpleName)
    }

    // 7. Canonical UTF-8 SHA256 digest
    @Test
    fun s1_o07_canonicalUtf8Sha256Digest_deterministicAndVerified() {
        val validJson = buildValidOwnerJson(
            state = "HANDOVER_PENDING",
            ownerSeq = 4L,
            handoverId = "ho_digest_check",
            claimToken = "tok_utf8_αβγ"
        )
        val parsed = invokeStoreMethod("parse", arrayOf(String::class.java), validJson.toString())
        assertEquals("Success", parsed!!.javaClass.simpleName)
        val owner = parsed.javaClass.getMethod("getOwner").invoke(parsed)!!
        val computed = owner.javaClass.getMethod("computeDigest").invoke(owner) as String
        assertEquals(64, computed.length)
        assertEquals(validJson.getString("payloadDigest"), computed)
    }

    // 8. Length-framed encoding
    @Test
    fun s1_o08_lengthFramedEncoding_preventsFieldInjectionCollisions() {
        requireProductionStoreClass()
        val firstDigest = sampleCanonicalOwnerDigest(
            state = "HANDOVER_PENDING",
            ownerSeq = 2L,
            handoverId = "ab",
            claimInstanceToken = "cd"
        )
        val secondDigest = sampleCanonicalOwnerDigest(
            state = "HANDOVER_PENDING",
            ownerSeq = 2L,
            handoverId = "a",
            claimInstanceToken = "bcd"
        )
        assertNotEquals(firstDigest, secondDigest)

        val unicodeJson = buildValidOwnerJson(
            state = "HANDOVER_PENDING",
            ownerSeq = 2L,
            handoverId = "ho:日本語\nCLAIM",
            claimToken = "tok:⏰:アラーム"
        )
        val parsed = invokeStoreMethod("parse", arrayOf(String::class.java), unicodeJson.toString())
        assertEquals("Success", parsed!!.javaClass.simpleName)
    }

    // 9. Tampered digest rejection
    @Test
    fun s1_o09_tamperedDigest_rejectedAsCorrupt() {
        val tampered = buildValidOwnerJson(state = "IDLE", ownerSeq = 1L).apply {
            put("updatedAtEpochMs", baseTime + 5_000L)
        }
        val parsed = invokeStoreMethod("parse", arrayOf(String::class.java), tampered.toString())
        assertEquals("Corrupt", parsed!!.javaClass.simpleName)
    }

    // 10. Malformed JSON rejection
    @Test
    fun s1_o10_malformedJsonAndBlankOrNull_rejectedAsCorrupt() {
        for (raw in listOf("", "   ", "{ malformed json", "[]", "{}")) {
            val parsed = invokeStoreMethod("parse", arrayOf(String::class.java), raw)
            assertEquals("Corrupt", parsed!!.javaClass.simpleName)
        }
    }

    // 11. Wrong primitive type rejection
    @Test
    fun s1_o11_wrongPrimitiveTypesAndFractionalNumbers_rejectedAsCorrupt() {
        val wrongCases = listOf(
            "schemaVersion" to "1",
            "schemaVersion" to 1.5,
            "schemaVersion" to Long.MAX_VALUE,
            "ownerSeq" to "1",
            "ownerSeq" to 1.25,
            "updatedAtEpochMs" to true,
            "state" to 123,
            "bootProvenance" to "bad_object"
        )
        for ((field, badVal) in wrongCases) {
            val badJson = buildValidOwnerJson().apply { put(field, badVal) }
            val parsed = invokeStoreMethod("parse", arrayOf(String::class.java), badJson.toString())
            assertEquals("Expected Corrupt for field=$field val=$badVal", "Corrupt", parsed!!.javaClass.simpleName)
        }

        // Non-string stored directly in SharedPreferences (ClassCastException guard)
        prefs.edit().putInt(KEY_DIRECT_BOOT_DURABLE_OWNER, 42).commit()
        val readNonString = invokeStoreMethod("read", arrayOf(Context::class.java), deContext)
        assertEquals("Corrupt", readNonString!!.javaClass.simpleName)
    }

    // 12. Unsupported schema preservation
    @Test
    fun s1_o12_unsupportedSchema_preservedUntouchedOnReadAndWrite() {
        val futurePayload = buildValidOwnerJson(schemaVersion = 99).toString()
        prefs.edit().putString(KEY_DIRECT_BOOT_DURABLE_OWNER, futurePayload).commit()

        val readRes = invokeStoreMethod("read", arrayOf(Context::class.java), deContext)
        assertEquals("UnsupportedSchema", readRes!!.javaClass.simpleName)
        assertEquals(futurePayload, prefs.getString(KEY_DIRECT_BOOT_DURABLE_OWNER, null))

        val storeClass = requireProductionStoreClass()
        val initial = invokeStoreMethod(
            "createInitialIdle",
            arrayOf(BootProvenance::class.java, Long::class.javaPrimitiveType!!),
            BootProvenance(),
            baseTime
        )!!
        val instance = storeClass.getField("INSTANCE").get(null)
        val writeMethod = storeClass.getMethod(
            "write",
            Context::class.java,
            initial.javaClass,
            Long::class.javaObjectType
        )
        val writeRes = writeMethod.invoke(instance, deContext, initial, 0L)
        assertEquals("UnsupportedSchema", writeRes!!.javaClass.simpleName)
        assertEquals(futurePayload, prefs.getString(KEY_DIRECT_BOOT_DURABLE_OWNER, null))
    }

    // 13. Corrupt raw payload preservation
    @Test
    fun s1_o13_corruptRawPayload_preservedUntouchedOnReadAndWrite() {
        val corruptRaw = "{corrupt_owner_bytes_must_remain"
        prefs.edit().putString(KEY_DIRECT_BOOT_DURABLE_OWNER, corruptRaw).commit()

        val readRes = invokeStoreMethod("read", arrayOf(Context::class.java), deContext)
        assertEquals("Corrupt", readRes!!.javaClass.simpleName)
        assertEquals(corruptRaw, prefs.getString(KEY_DIRECT_BOOT_DURABLE_OWNER, null))

        val storeClass = requireProductionStoreClass()
        val initial = invokeStoreMethod(
            "createInitialIdle",
            arrayOf(BootProvenance::class.java, Long::class.javaPrimitiveType!!),
            BootProvenance(),
            baseTime
        )!!
        val instance = storeClass.getField("INSTANCE").get(null)
        val writeMethod = storeClass.getMethod(
            "write",
            Context::class.java,
            initial.javaClass,
            Long::class.javaObjectType
        )
        val writeRes = writeMethod.invoke(instance, deContext, initial, 0L)
        assertEquals("StorageCorrupt", writeRes!!.javaClass.simpleName)
        assertEquals(corruptRaw, prefs.getString(KEY_DIRECT_BOOT_DURABLE_OWNER, null))
    }

    // 14. BootProvenance validation
    @Test
    fun s1_o14_bootProvenanceValidation_sameBootAccepted() {
        val persisted = BootProvenance(
            bootCount = 7,
            lastElapsedRealtime = 120_000L,
            bootEpochEstimateMs = baseTime - 120_000L
        )
        val current = BootProvenance(
            bootCount = 7,
            lastElapsedRealtime = 135_000L,
            bootEpochEstimateMs = baseTime - 119_500L
        )
        val sameBoot = invokeStoreMethod(
            "isSameBootProvenance",
            arrayOf(BootProvenance::class.java, BootProvenance::class.java),
            persisted,
            current
        ) as Boolean
        assertTrue(sameBoot)
    }

    // 15. Stale/rebooted Owner rejection
    @Test
    fun s1_o15_staleOrRebootedOwner_bootCountElapsedOrEpochDriftRejected() {
        val persisted = BootProvenance(
            bootCount = 7,
            lastElapsedRealtime = 120_000L,
            bootEpochEstimateMs = baseTime - 120_000L
        )
        val cases = listOf(
            // Boot count changed (reboot)
            BootProvenance(bootCount = 8, lastElapsedRealtime = 130_000L, bootEpochEstimateMs = baseTime - 120_000L),
            // Elapsed realtime regressed (reboot when bootCount null or fallback)
            BootProvenance(bootCount = 7, lastElapsedRealtime = 10_000L, bootEpochEstimateMs = baseTime - 120_000L),
            // Boot epoch drifted > 30s
            BootProvenance(bootCount = 7, lastElapsedRealtime = 130_000L, bootEpochEstimateMs = baseTime - 60_000L),
            // Unknown provenance
            BootProvenance()
        )
        for (candidate in cases) {
            val sameBoot = invokeStoreMethod(
                "isSameBootProvenance",
                arrayOf(BootProvenance::class.java, BootProvenance::class.java),
                persisted,
                candidate
            ) as Boolean
            assertFalse("Stale/rebooted provenance $candidate must be rejected", sameBoot)
        }
    }

    // 16. Single-editor atomic write preparation
    @Test
    fun s1_o16_singleEditorAtomicWritePreparation_commitsOrLeavesAllKeysUntouched() {
        val storeClass = requireProductionStoreClass()
        val initial = invokeStoreMethod(
            "createInitialIdle",
            arrayOf(BootProvenance::class.java, Long::class.javaPrimitiveType!!),
            BootProvenance(bootCount = 2, lastElapsedRealtime = 20_000L, bootEpochEstimateMs = baseTime - 20_000L),
            baseTime
        )!!
        val instance = storeClass.getField("INSTANCE").get(null)
        val prepareMethod = storeClass.getMethod(
            "prepareAtomicWritePayload",
            Context::class.java,
            initial.javaClass,
            Long::class.javaObjectType
        )
        val prepared = prepareMethod.invoke(instance, deContext, initial, 0L)
        assertEquals("Prepared", prepared!!.javaClass.simpleName)
        val serializedPayload = prepared.javaClass.getMethod("getPayloadJson").invoke(prepared) as String
        assertTrue(serializedPayload.contains("\"ownerSeq\":1"))

        // Verify prepareAtomicWritePayload itself does NOT mutate prefs before caller commits
        assertFalse(prefs.contains(KEY_DIRECT_BOOT_DURABLE_OWNER))

        // Commit together with Queue/Ledger in a single SharedPreferences.Editor
        val editor = prefs.edit()
            .putString(DirectBootAlarmCache.KEY_QUEUE_PAYLOAD, "{\"schemaVersion\":3,\"items\":[]}")
            .putString(KEY_DIRECT_BOOT_DURABLE_OWNER, serializedPayload)
        assertTrue(editor.commit())

        val readAfter = invokeStoreMethod("read", arrayOf(Context::class.java), deContext)
        assertEquals("Success", readAfter!!.javaClass.simpleName)
    }

    // 17. Concurrent CAS writers + DE key isolation
    @Test
    fun s1_o17_concurrentCasWriters_exactlyOneWinnerAndIsolatesQueueLedger() {
        val dummyQueue = "{\"schemaVersion\":3,\"items\":[]}"
        val dummyLedger = "{\"schemaVersion\":2,\"records\":[]}"
        prefs.edit()
            .putString(DirectBootAlarmCache.KEY_QUEUE_PAYLOAD, dummyQueue)
            .putString(DirectBootDeliveryLedger.KEY_DELIVERY_LEDGER_PAYLOAD, dummyLedger)
            .commit()

        val storeClass = requireProductionStoreClass()
        val instance = storeClass.getField("INSTANCE").get(null)
        val initial = invokeStoreMethod(
            "createInitialIdle",
            arrayOf(BootProvenance::class.java, Long::class.javaPrimitiveType!!),
            BootProvenance(bootCount = 2, lastElapsedRealtime = 20_000L, bootEpochEstimateMs = baseTime - 20_000L),
            baseTime
        )!!
        val writeMethod = storeClass.getMethod(
            "write",
            Context::class.java,
            initial.javaClass,
            Long::class.javaObjectType
        )

        val threadCount = 8
        val startLatch = CountDownLatch(1)
        val doneLatch = CountDownLatch(threadCount)
        val successCount = AtomicInteger(0)
        val mismatchCount = AtomicInteger(0)

        for (i in 0 until threadCount) {
            Thread {
                try {
                    startLatch.await()
                    val res = writeMethod.invoke(instance, deContext, initial, 0L)!!
                    when (res.javaClass.simpleName) {
                        "Success" -> successCount.incrementAndGet()
                        "SequenceMismatch" -> mismatchCount.incrementAndGet()
                    }
                } finally {
                    doneLatch.countDown()
                }
            }.start()
        }

        startLatch.countDown()
        assertTrue(doneLatch.await(5, TimeUnit.SECONDS))
        assertEquals(1, successCount.get())
        assertEquals(threadCount - 1, mismatchCount.get())
        assertEquals(dummyQueue, prefs.getString(DirectBootAlarmCache.KEY_QUEUE_PAYLOAD, null))
        assertEquals(dummyLedger, prefs.getString(DirectBootDeliveryLedger.KEY_DELIVERY_LEDGER_PAYLOAD, null))
    }

    companion object {
        const val PREFS_NAME = "direct_boot_next_alarm"
        const val KEY_DIRECT_BOOT_DURABLE_OWNER = "direct_boot_durable_owner"
        const val STORE_FQN = "com.sysadmindoc.alarmclock.directboot.DirectBootDurableOwnerStore"
    }
}
