package com.sysadmindoc.alarmclock.directboot

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.sysadmindoc.alarmclock.data.model.Alarm
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.nio.charset.StandardCharsets

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class DirectBootV2NormalIntentIdentityTest {

    private lateinit var context: Context

    private val baseAlarmId = 42L
    private val baseGeneration = 3
    private val baseToken = "inst_authority_token_abc_123"
    private val baseTriggerTime = 1_770_000_000_000L

    private fun createBaseIdentity(
        alarmId: Long = baseAlarmId,
        generation: Int = baseGeneration,
        instanceToken: String = baseToken,
        triggerTime: Long = baseTriggerTime,
        kind: String = DirectBootV2NormalIntentIdentity.KIND_NORMAL
    ) = DirectBootV2NormalIntentIdentity(
        alarmId = alarmId,
        generation = generation,
        instanceToken = instanceToken,
        triggerTime = triggerTime,
        kind = kind
    )

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
    }

    // -------------------------------------------------------------------------
    // 1. Same identity yields same URI
    // -------------------------------------------------------------------------
    @Test
    fun test01_sameIdentityYieldsSameUri() {
        val identity1 = createBaseIdentity()
        val identity2 = createBaseIdentity()

        assertEquals(identity1.canonicalString(), identity2.canonicalString())
        assertEquals(identity1.computeDigest(), identity2.computeDigest())
        assertEquals(identity1.toUriString(), identity2.toUriString())
        assertEquals(identity1.toUri(), identity2.toUri())
    }

    // -------------------------------------------------------------------------
    // 2. Different alarmId yields different URI
    // -------------------------------------------------------------------------
    @Test
    fun test02_differentAlarmIdYieldsDifferentUri() {
        val identity1 = createBaseIdentity(alarmId = 42L)
        val identity2 = createBaseIdentity(alarmId = 43L)

        assertNotEquals(identity1.computeDigest(), identity2.computeDigest())
        assertNotEquals(identity1.toUriString(), identity2.toUriString())
        assertNotEquals(identity1.toUri(), identity2.toUri())
    }

    // -------------------------------------------------------------------------
    // 3. Same alarmId, different generation
    // -------------------------------------------------------------------------
    @Test
    fun test03_sameAlarmIdDifferentGenerationYieldsDifferentUri() {
        val identityGen1 = createBaseIdentity(generation = 1)
        val identityGen2 = createBaseIdentity(generation = 2)

        assertNotEquals(identityGen1.computeDigest(), identityGen2.computeDigest())
        assertNotEquals(identityGen1.toUriString(), identityGen2.toUriString())
        assertNotEquals(identityGen1.toUri(), identityGen2.toUri())
    }

    // -------------------------------------------------------------------------
    // 4. Same generation, different instanceToken
    // -------------------------------------------------------------------------
    @Test
    fun test04_sameGenerationDifferentInstanceTokenYieldsDifferentUri() {
        val identityA = createBaseIdentity(instanceToken = "token_alpha")
        val identityB = createBaseIdentity(instanceToken = "token_beta")

        assertNotEquals(identityA.computeDigest(), identityB.computeDigest())
        assertNotEquals(identityA.toUriString(), identityB.toUriString())
        assertNotEquals(identityA.toUri(), identityB.toUri())
    }

    // -------------------------------------------------------------------------
    // 5. Same token, different triggerTime
    // -------------------------------------------------------------------------
    @Test
    fun test05_sameTokenDifferentTriggerTimeYieldsDifferentUri() {
        val identityTime1 = createBaseIdentity(triggerTime = 1_770_000_000_000L)
        val identityTime2 = createBaseIdentity(triggerTime = 1_770_000_060_000L)

        assertNotEquals(identityTime1.computeDigest(), identityTime2.computeDigest())
        assertNotEquals(identityTime1.toUriString(), identityTime2.toUriString())
        assertNotEquals(identityTime1.toUri(), identityTime2.toUri())
    }

    // -------------------------------------------------------------------------
    // 6. Long alarmIds sharing the same lower 32 bits
    // -------------------------------------------------------------------------
    @Test
    fun test06_longAlarmIdsSharingLower32BitsDoNotCollide() {
        val lowId = 42L
        val highId = 42L + (1L shl 32) // 4294967338L

        // Verify that they collide under legacy 32-bit toInt()
        assertEquals(lowId.toInt(), highId.toInt())

        val identityLow = createBaseIdentity(alarmId = lowId)
        val identityHigh = createBaseIdentity(alarmId = highId)

        // Verify that V2 identity avoids 32-bit integer truncation collision
        assertNotEquals(identityLow.computeDigest(), identityHigh.computeDigest())
        assertNotEquals(identityLow.toUriString(), identityHigh.toUriString())
        assertNotEquals(identityLow.toUri(), identityHigh.toUri())

        // Verify that Intent.filterEquals() returns false
        val intentLow = identityLow.createIntent(context)
        val intentHigh = identityHigh.createIntent(context)
        assertFalse(intentLow.filterEquals(intentHigh))
    }

    // -------------------------------------------------------------------------
    // 7. Stable canonical UTF-8 serialization
    // -------------------------------------------------------------------------
    @Test
    fun test07_stableCanonicalUtf8Serialization() {
        val unicodeToken = "token_東京_✨_test"
        val identity = createBaseIdentity(instanceToken = unicodeToken)

        val canonical = identity.canonicalString()
        val tokenUtf8Len = unicodeToken.toByteArray(StandardCharsets.UTF_8).size

        val expected = "DOMAIN:ACX_DIRECT_BOOT_V2_NORMAL_PI_V1\n" +
            "KIND:6:NORMAL\n" +
            "ALARM_ID:$baseAlarmId\n" +
            "GENERATION:$baseGeneration\n" +
            "INSTANCE_TOKEN:$tokenUtf8Len:$unicodeToken\n" +
            "TRIGGER_TIME:$baseTriggerTime\n"

        assertEquals(expected, canonical)

        // Multiple digest evaluations produce identical SHA-256 output
        val digest1 = identity.computeDigest()
        val digest2 = identity.computeDigest()
        assertEquals(digest1, digest2)
        assertEquals(64, digest1.length)
        assertTrue(digest1.all { it in '0'..'9' || it in 'a'..'f' })
    }

    // -------------------------------------------------------------------------
    // 8. Ambiguous delimiter collision prevention
    // -------------------------------------------------------------------------
    @Test
    fun test08_ambiguousDelimiterCollisionPrevention() {
        // Tuple A: normal token
        val identityA = createBaseIdentity(
            generation = 12,
            instanceToken = "tok"
        )
        // Tuple B: token crafted to mimic delimiter/field injection
        val identityB = createBaseIdentity(
            generation = 1,
            instanceToken = "2\nINSTANCE_TOKEN:3:tok"
        )

        assertNotEquals(identityA.canonicalString(), identityB.canonicalString())
        assertNotEquals(identityA.computeDigest(), identityB.computeDigest())
        assertNotEquals(identityA.toUriString(), identityB.toUriString())

        // Tuple C with colon and newlines embedded in token
        val identityC = createBaseIdentity(
            instanceToken = "TRIGGER_TIME:999\nDOMAIN:FAKE"
        )
        val identityD = createBaseIdentity(
            triggerTime = 999L
        )
        assertNotEquals(identityC.canonicalString(), identityD.canonicalString())
        assertNotEquals(identityC.computeDigest(), identityD.computeDigest())
    }

    // -------------------------------------------------------------------------
    // 9. Empty or blank instanceToken rejected
    // -------------------------------------------------------------------------
    @Test(expected = IllegalArgumentException::class)
    fun test09a_emptyInstanceTokenRejected() {
        createBaseIdentity(instanceToken = "")
    }

    @Test(expected = IllegalArgumentException::class)
    fun test09b_blankSpacesInstanceTokenRejected() {
        createBaseIdentity(instanceToken = "   ")
    }

    @Test(expected = IllegalArgumentException::class)
    fun test09c_blankTabNewlineInstanceTokenRejected() {
        createBaseIdentity(instanceToken = "\t\n  ")
    }

    // -------------------------------------------------------------------------
    // 10. Invalid alarmId rejected
    // -------------------------------------------------------------------------
    @Test(expected = IllegalArgumentException::class)
    fun test10a_zeroAlarmIdRejected() {
        createBaseIdentity(alarmId = 0L)
    }

    @Test(expected = IllegalArgumentException::class)
    fun test10b_negativeAlarmIdRejected() {
        createBaseIdentity(alarmId = -1L)
    }

    // -------------------------------------------------------------------------
    // 11. Invalid generation rejected
    // -------------------------------------------------------------------------
    @Test(expected = IllegalArgumentException::class)
    fun test11a_zeroGenerationRejected() {
        createBaseIdentity(generation = 0)
    }

    @Test(expected = IllegalArgumentException::class)
    fun test11b_negativeGenerationRejected() {
        createBaseIdentity(generation = -5)
    }

    // -------------------------------------------------------------------------
    // 12. Invalid triggerTime rejected
    // -------------------------------------------------------------------------
    @Test(expected = IllegalArgumentException::class)
    fun test12a_zeroTriggerTimeRejected() {
        createBaseIdentity(triggerTime = 0L)
    }

    @Test(expected = IllegalArgumentException::class)
    fun test12b_negativeTriggerTimeRejected() {
        createBaseIdentity(triggerTime = -100L)
    }

    // -------------------------------------------------------------------------
    // 13. Raw instanceToken is absent from URI
    // -------------------------------------------------------------------------
    @Test
    fun test13_rawInstanceTokenIsAbsentFromUri() {
        val sensitiveToken = "super_secret_auth_token_xyz_987654321"
        val identity = createBaseIdentity(instanceToken = sensitiveToken)

        val uriString = identity.toUriString()
        val uri = identity.toUri()

        assertFalse("URI string must NOT contain raw instanceToken", uriString.contains(sensitiveToken))
        assertFalse("Uri.toString() must NOT contain raw instanceToken", uri.toString().contains(sensitiveToken))
        assertFalse("Uri path must NOT contain raw instanceToken", uri.path.orEmpty().contains(sensitiveToken))
        assertNull("Uri query must be null", uri.query)
    }

    // -------------------------------------------------------------------------
    // 14. V1 and V2 PendingIntent namespaces differ
    // -------------------------------------------------------------------------
    @Test
    fun test14_v1AndV2PendingIntentNamespacesDiffer() {
        val identity = createBaseIdentity()
        val v2Intent = identity.createIntent(context)

        // Construct legacy V1 Intent
        val v1Intent = Intent(context, DirectBootAlarmReceiver::class.java).apply {
            action = DirectBootAlarmReceiver.ACTION_FIRE_DIRECT_BOOT_ALARM
            putExtra(DirectBootAlarmReceiver.EXTRA_ALARM_ID, baseAlarmId)
            putExtra(DirectBootAlarmReceiver.EXTRA_TRIGGER_TIME, baseTriggerTime)
        }

        // Action separation
        assertNotEquals(DirectBootAlarmReceiver.ACTION_FIRE_DIRECT_BOOT_ALARM, DirectBootV2NormalIntentIdentity.ACTION_FIRE_V2_NORMAL_ALARM)
        assertEquals(DirectBootV2NormalIntentIdentity.ACTION_FIRE_V2_NORMAL_ALARM, v2Intent.action)
        assertEquals(DirectBootAlarmReceiver.ACTION_FIRE_DIRECT_BOOT_ALARM, v1Intent.action)

        // Data URI separation
        assertNull(v1Intent.data)
        assertNotNull(v2Intent.data)
        assertEquals(DirectBootV2NormalIntentIdentity.URI_SCHEME, v2Intent.data?.scheme)

        // Intent.filterEquals() must return false
        assertFalse("V1 and V2 intents must not match filterEquals", v1Intent.filterEquals(v2Intent))

        // Request codes differ
        assertNotEquals(baseAlarmId.toInt(), DirectBootV2NormalIntentIdentity.REQUEST_CODE_V2_NORMAL)
    }

    // -------------------------------------------------------------------------
    // 15. Repeated creation/lookup identity consistency & Immutable Factory Contract
    // -------------------------------------------------------------------------
    @Test
    fun test15_repeatedCreationAndLookupIdentityConsistency() {
        val identity = createBaseIdentity()

        // Before creation, lookup returns null
        val initialLookup = identity.lookupPendingIntentForCancel(context)
        assertNull(initialLookup)

        // Create PendingIntent (strictly enforces FLAG_UPDATE_CURRENT or FLAG_IMMUTABLE)
        val createdPi = identity.createPendingIntent(context)
        assertNotNull(createdPi)

        // Lookup matches created PendingIntent (strictly enforces FLAG_NO_CREATE or FLAG_IMMUTABLE)
        val lookedUpPi = identity.lookupPendingIntentForCancel(context)
        assertNotNull(lookedUpPi)
        assertEquals(createdPi, lookedUpPi)

        // Lookup with different occurrence identity (different generation) returns null
        val otherIdentity = createBaseIdentity(generation = baseGeneration + 1)
        val otherLookup = otherIdentity.lookupPendingIntentForCancel(context)
        assertNull("Lookup for non-registered generation must return null", otherLookup)

        // Lookup intent matches creation intent under filterEquals
        val creationIntent = identity.createIntent(context)
        val lookupIntent = identity.createLookupIntent(context)
        assertTrue(creationIntent.filterEquals(lookupIntent))

        // After cancelling, lookup returns null
        createdPi.cancel()
        val afterCancelLookup = identity.lookupPendingIntentForCancel(context)
        assertNull(afterCancelLookup)
    }

    // -------------------------------------------------------------------------
    // 16. Malformed or foreign URI rejection (Canonical URI Validation Closure)
    // -------------------------------------------------------------------------
    @Test
    fun test16a_validCanonicalUriAccepted() {
        val identity = createBaseIdentity()
        val validUri = identity.toUri()

        val validResult = DirectBootV2NormalIntentIdentity.validateUri(validUri, identity)
        assertTrue(validResult.isValid)
        assertEquals(UriValidationResult.Valid, validResult)
    }

    @Test
    fun test16b_nullAndOpaqueUriRejected() {
        val identity = createBaseIdentity()
        assertFalse(DirectBootV2NormalIntentIdentity.validateUri(null, identity).isValid)

        val opaqueUri = Uri.parse("mailto:user@example.com")
        assertFalse(DirectBootV2NormalIntentIdentity.validateUri(opaqueUri, identity).isValid)
    }

    @Test
    fun test16c_queryParametersRejected() {
        val identity = createBaseIdentity()
        val canonicalUriStr = identity.toUriString()

        val uriWithQuery = Uri.parse("$canonicalUriStr?foo=bar")
        assertFalse("URI with query parameter must be rejected", DirectBootV2NormalIntentIdentity.validateUri(uriWithQuery, identity).isValid)

        val uriWithEmptyQuery = Uri.parse("$canonicalUriStr?")
        assertFalse("URI with empty query must be rejected", DirectBootV2NormalIntentIdentity.validateUri(uriWithEmptyQuery, identity).isValid)
    }

    @Test
    fun test16d_fragmentsRejected() {
        val identity = createBaseIdentity()
        val canonicalUriStr = identity.toUriString()

        val uriWithFragment = Uri.parse("$canonicalUriStr#section1")
        assertFalse("URI with fragment must be rejected", DirectBootV2NormalIntentIdentity.validateUri(uriWithFragment, identity).isValid)

        val uriWithEmptyFragment = Uri.parse("$canonicalUriStr#")
        assertFalse("URI with empty fragment must be rejected", DirectBootV2NormalIntentIdentity.validateUri(uriWithEmptyFragment, identity).isValid)
    }

    @Test
    fun test16e_trailingSlashRejected() {
        val identity = createBaseIdentity()
        val canonicalUriStr = identity.toUriString()

        val uriWithTrailingSlash = Uri.parse("$canonicalUriStr/")
        assertFalse("URI with trailing slash must be rejected", DirectBootV2NormalIntentIdentity.validateUri(uriWithTrailingSlash, identity).isValid)
    }

    @Test
    fun test16f_nonCanonicalAlarmIdFormattingRejected() {
        val identity = createBaseIdentity(alarmId = 42L)
        val digest = identity.computeDigest()

        // Leading zeros: "042"
        val leadingZeroUri = Uri.parse("acx-directboot://normal/alarm/042/v2/$digest")
        assertFalse("AlarmId with leading zeros must be rejected", DirectBootV2NormalIntentIdentity.validateUri(leadingZeroUri, identity).isValid)

        // Explicit positive sign: "+42"
        val plusSignUri = Uri.parse("acx-directboot://normal/alarm/+42/v2/$digest")
        assertFalse("AlarmId with plus sign must be rejected", DirectBootV2NormalIntentIdentity.validateUri(plusSignUri, identity).isValid)

        // Hex formatting: "0x2a"
        val hexUri = Uri.parse("acx-directboot://normal/alarm/0x2a/v2/$digest")
        assertFalse("AlarmId in hex format must be rejected", DirectBootV2NormalIntentIdentity.validateUri(hexUri, identity).isValid)
    }

    @Test
    fun test16g_encodedPathAliasesRejected() {
        val identity = createBaseIdentity()
        val digest = identity.computeDigest()

        // Encoded "alarm" as "%61larm"
        val encodedAlarmUri = Uri.parse("acx-directboot://normal/%61larm/$baseAlarmId/v2/$digest")
        assertFalse("Encoded path alias %61larm must be rejected", DirectBootV2NormalIntentIdentity.validateUri(encodedAlarmUri, identity).isValid)

        // Encoded slash
        val encodedSlashUri = Uri.parse("acx-directboot://normal/alarm%2F$baseAlarmId/v2/$digest")
        assertFalse("Encoded slash must be rejected", DirectBootV2NormalIntentIdentity.validateUri(encodedSlashUri, identity).isValid)
    }

    @Test
    fun test16h_uppercaseDigestRejected() {
        val identity = createBaseIdentity()
        val lowerDigest = identity.computeDigest()
        val upperDigest = lowerDigest.uppercase()

        val upperDigestUri = Uri.parse("acx-directboot://normal/alarm/$baseAlarmId/v2/$upperDigest")
        assertFalse("Uppercase hex digest must be rejected", DirectBootV2NormalIntentIdentity.validateUri(upperDigestUri, identity).isValid)
    }

    @Test
    fun test16i_foreignSchemeOrAuthorityOrPathRejected() {
        val identity = createBaseIdentity()

        val foreignSchemeUri = Uri.parse("http://normal/alarm/$baseAlarmId/v2/${identity.computeDigest()}")
        assertFalse(DirectBootV2NormalIntentIdentity.validateUri(foreignSchemeUri, identity).isValid)

        val foreignAuthUri = Uri.parse("acx-directboot://quick/alarm/$baseAlarmId/v2/${identity.computeDigest()}")
        assertFalse(DirectBootV2NormalIntentIdentity.validateUri(foreignAuthUri, identity).isValid)

        val truncatedUri = Uri.parse("acx-directboot://normal/alarm/$baseAlarmId")
        assertFalse(DirectBootV2NormalIntentIdentity.validateUri(truncatedUri, identity).isValid)

        val wrongAlarmIdUri = Uri.parse("acx-directboot://normal/alarm/999/v2/${identity.computeDigest()}")
        assertFalse(DirectBootV2NormalIntentIdentity.validateUri(wrongAlarmIdUri, identity).isValid)

        val corruptDigestUri = Uri.parse("acx-directboot://normal/alarm/$baseAlarmId/v2/not_a_valid_hex_hash")
        assertFalse(DirectBootV2NormalIntentIdentity.validateUri(corruptDigestUri, identity).isValid)

        val otherIdentity = createBaseIdentity(generation = 99)
        val wrongDigestUri = Uri.parse("acx-directboot://normal/alarm/$baseAlarmId/v2/${otherIdentity.computeDigest()}")
        assertFalse(DirectBootV2NormalIntentIdentity.validateUri(wrongDigestUri, identity).isValid)
    }

    // -------------------------------------------------------------------------
    // 17. Determinism across new codec instances
    // -------------------------------------------------------------------------
    @Test
    fun test17_determinismAcrossNewCodecInstances() {
        val instances = (1..50).map {
            DirectBootV2NormalIntentIdentity(
                alarmId = baseAlarmId,
                generation = baseGeneration,
                instanceToken = baseToken,
                triggerTime = baseTriggerTime,
                kind = DirectBootV2NormalIntentIdentity.KIND_NORMAL
            )
        }

        val firstCanonical = instances.first().canonicalString()
        val firstDigest = instances.first().computeDigest()
        val firstUri = instances.first().toUriString()

        instances.forEach { instance ->
            assertEquals(firstCanonical, instance.canonicalString())
            assertEquals(firstDigest, instance.computeDigest())
            assertEquals(firstUri, instance.toUriString())
        }
    }

    // -------------------------------------------------------------------------
    // Additional tests: QueueItem conversion & Intent helper
    // -------------------------------------------------------------------------
    @Test
    fun test18_fromQueueItemValidNormal() {
        val queueItem = DirectBootQueueItem(
            kind = DirectBootQueueItem.KIND_NORMAL,
            alarmId = baseAlarmId,
            triggerTime = baseTriggerTime,
            generation = baseGeneration,
            instanceToken = baseToken
        )
        val identity = DirectBootV2NormalIntentIdentity.fromQueueItem(queueItem)
        assertEquals(baseAlarmId, identity.alarmId)
        assertEquals(baseGeneration, identity.generation)
        assertEquals(baseToken, identity.instanceToken)
        assertEquals(baseTriggerTime, identity.triggerTime)
        assertEquals(DirectBootV2NormalIntentIdentity.KIND_NORMAL, identity.kind)
    }

    @Test(expected = IllegalArgumentException::class)
    fun test19_fromQueueItemRejectsQuick() {
        val quickItem = DirectBootQueueItem(
            kind = DirectBootQueueItem.KIND_QUICK,
            alarmId = baseAlarmId,
            triggerTime = baseTriggerTime,
            generation = 0,
            instanceToken = ""
        )
        DirectBootV2NormalIntentIdentity.fromQueueItem(quickItem)
    }

    @Test
    fun test20_isV2NormalIntentVerification() {
        val identity = createBaseIdentity()
        val validIntent = identity.createIntent(context)
        assertTrue(DirectBootV2NormalIntentIdentity.isV2NormalIntent(validIntent))

        val nullIntent: Intent? = null
        assertFalse(DirectBootV2NormalIntentIdentity.isV2NormalIntent(nullIntent))

        val v1Intent = Intent(DirectBootAlarmReceiver.ACTION_FIRE_DIRECT_BOOT_ALARM)
        assertFalse(DirectBootV2NormalIntentIdentity.isV2NormalIntent(v1Intent))

        val wrongActionIntent = Intent("some.other.action").apply {
            data = identity.toUri()
        }
        assertFalse(DirectBootV2NormalIntentIdentity.isV2NormalIntent(wrongActionIntent))

        val noDataIntent = Intent(DirectBootV2NormalIntentIdentity.ACTION_FIRE_V2_NORMAL_ALARM)
        assertFalse(DirectBootV2NormalIntentIdentity.isV2NormalIntent(noDataIntent))
    }

    // -------------------------------------------------------------------------
    // 21. Golden representation equivalence verification
    // -------------------------------------------------------------------------
    @Test
    fun test21_goldenRepresentationEquivalence() {
        val testCases = listOf(
            // 1. Ordinary ASCII
            createBaseIdentity(alarmId = 1L, generation = 1, instanceToken = "tok_ascii_1", triggerTime = 1_000_000L),
            // 2. Unicode token
            createBaseIdentity(alarmId = 2L, generation = 2, instanceToken = "tok_日本語_🎉", triggerTime = 2_000_000L),
            // 3. Delimiters and newlines
            createBaseIdentity(alarmId = 3L, generation = 3, instanceToken = "tok\nDOMAIN:FAKE\nTRIGGER_TIME:0", triggerTime = 3_000_000L),
            // 4. Large 64-bit alarmId
            createBaseIdentity(alarmId = 9_876_543_210_123L, generation = 5, instanceToken = "tok_large_id", triggerTime = 4_000_000L),
            // 5. Distinct generation / triggerTime
            createBaseIdentity(alarmId = 100L, generation = 999, instanceToken = "tok_gen_999", triggerTime = 9_999_999_999L)
        )

        testCases.forEach { identity ->
            val tokenLen = identity.instanceToken.toByteArray(StandardCharsets.UTF_8).size
            val expectedManualCanonical = "DOMAIN:ACX_DIRECT_BOOT_V2_NORMAL_PI_V1\n" +
                "KIND:${identity.kind.length}:${identity.kind}\n" +
                "ALARM_ID:${identity.alarmId}\n" +
                "GENERATION:${identity.generation}\n" +
                "INSTANCE_TOKEN:$tokenLen:${identity.instanceToken}\n" +
                "TRIGGER_TIME:${identity.triggerTime}\n"

            // Exact canonical string equivalence
            assertEquals(expectedManualCanonical, identity.canonicalString())

            // Valid URI formation
            val expectedDigest = java.security.MessageDigest.getInstance("SHA-256")
                .digest(expectedManualCanonical.toByteArray(StandardCharsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
            assertEquals(expectedDigest, identity.computeDigest())

            val expectedUri = "acx-directboot://normal/alarm/${identity.alarmId}/v2/$expectedDigest"
            assertEquals(expectedUri, identity.toUriString())
            assertEquals(Uri.parse(expectedUri), identity.toUri())

            // Canonical validation passes
            assertTrue(DirectBootV2NormalIntentIdentity.validateUri(identity.toUri(), identity).isValid)
        }
    }

    // -------------------------------------------------------------------------
    // A0-01 / A0-04: Restore Epoch Framing & Intent Extra Propagation
    // -------------------------------------------------------------------------
    @Test
    fun a0_04_restoreEpochFramingAndDigestSeparation() {
        val epoch0 = DirectBootV2NormalIntentIdentity(
            alarmId = baseAlarmId,
            generation = baseGeneration,
            instanceToken = baseToken,
            triggerTime = baseTriggerTime,
            restoreEpoch = 0L
        )
        val epoch1 = DirectBootV2NormalIntentIdentity(
            alarmId = baseAlarmId,
            generation = baseGeneration,
            instanceToken = baseToken,
            triggerTime = baseTriggerTime,
            restoreEpoch = 1L
        )
        val epoch2 = DirectBootV2NormalIntentIdentity(
            alarmId = baseAlarmId,
            generation = baseGeneration,
            instanceToken = baseToken,
            triggerTime = baseTriggerTime,
            restoreEpoch = 2L
        )

        assertNotEquals(epoch0.computeDigest(), epoch1.computeDigest())
        assertNotEquals(epoch1.computeDigest(), epoch2.computeDigest())
        assertTrue(epoch1.canonicalString().contains("RESTORE_EPOCH:1"))
        assertTrue(epoch2.canonicalString().contains("RESTORE_EPOCH:2"))

        val intentEpoch2 = epoch2.createIntent(context)
        assertEquals(2L, intentEpoch2.getLongExtra(DirectBootV2NormalIntentIdentity.EXTRA_RESTORE_EPOCH, -1L))
        assertEquals(DirectBootV2NormalIntentIdentity.KIND_NORMAL, intentEpoch2.getStringExtra(DirectBootV2NormalIntentIdentity.EXTRA_KIND))
        assertTrue(DirectBootV2NormalIntentIdentity.validateUri(intentEpoch2.data, epoch2).isValid)
        assertFalse(DirectBootV2NormalIntentIdentity.validateUri(intentEpoch2.data, epoch1).isValid)
    }
}
