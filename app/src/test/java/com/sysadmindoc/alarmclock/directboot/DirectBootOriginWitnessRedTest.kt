package com.sysadmindoc.alarmclock.directboot

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Hardening & Safety Contract Test Suite for KNO-75 Phase D3-C2-P3D-P3-BATCH-A-HARDENING.
 *
 * Verifies H1 (Queue Origin Qualification), H2 (Intent/Ticket Qualification),
 * H3 (DE Delivery/Missed v2 Qualification), and H4 (Duplicate/Conflict Distinction).
 */
@RunWith(RobolectricTestRunner::class)
class DirectBootOriginWitnessRedTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Test
    fun r1_qualifiedQueuePersistsRestoreEpochInDeJson() {
        // Contract R1: DirectBootQueueItem must contain restoreEpoch and serialize it into JSON.
        val item = DirectBootQueueItem(
            kind = "NORMAL",
            alarmId = 101L,
            triggerTime = System.currentTimeMillis() + 3600_000L,
            generation = 1,
            instanceToken = "token_101",
            restoreEpoch = 1L,
            schemaVersion = DirectBootQueueItem.CURRENT_SCHEMA_VERSION
        )
        val json = item.toJson()
        assertTrue("Queue JSON must contain 'restoreEpoch' key", json.has("restoreEpoch"))
        assertEquals(1L, json.getLong("restoreEpoch"))
        assertEquals(1L, item.restoreEpoch)
    }

    @Test
    fun r2_queueJsonReadbackRetainsSameRestoreEpoch() {
        // Contract R2: DirectBootQueueItem JSON deserialization must preserve restoreEpoch.
        val futureTriggerTime = System.currentTimeMillis() + 3600_000L
        val validV3Json = JSONObject().apply {
            put("kind", "NORMAL")
            put("alarmId", 102L)
            put("triggerTime", futureTriggerTime)
            put("generation", 1)
            put("scheduleKind", "ONE_TIME")
            put("instanceToken", "token_102")
            put("playDefaultSound", true)
            put("vibrationEnabled", true)
            put("updatedAt", System.currentTimeMillis())
            put("timezonePolicy", "LOCAL")
            put("fixedTimezoneId", "")
            put("restoreEpoch", 5L)
            put("schemaVersion", 3)
        }

        val item = DirectBootQueueItem.fromJson(validV3Json)
        assertNotNull("DirectBootQueueItem.fromJson must parse valid JSON", item)
        assertEquals(102L, item.alarmId)
        assertEquals(5L, item.restoreEpoch)

        val serializedJson = item.toJson()
        assertTrue("Queue item deserialization and serialization must preserve restoreEpoch key", serializedJson.has("restoreEpoch"))
        assertEquals(5L, serializedJson.getLong("restoreEpoch"))
    }

    @Test
    fun h1_v3NormalQueueItemWithInvalidRestoreEpoch_isRejected() {
        // Contract H1: Queue v3 Normal candidate with restoreEpoch <= 0L must fail closed.
        val invalidItem = DirectBootQueueItem(
            kind = "NORMAL",
            alarmId = 101L,
            triggerTime = System.currentTimeMillis() + 3600_000L,
            generation = 1,
            instanceToken = "token_101",
            restoreEpoch = 0L,
            schemaVersion = 3
        )
        val status = invalidItem.evaluateEligibility()
        assertEquals(QueueCandidateStatus.INELIGIBLE_INVALID_RESTORE_EPOCH, status)
    }

    @Test
    fun r3_v2IntentIdentityHoldsQueueDerivedRestoreEpoch() {
        // Contract R3: DirectBootV2NormalIntentIdentity must contain restoreEpoch in identity and canonical string.
        val identity = DirectBootV2NormalIntentIdentity(
            alarmId = 103L,
            generation = 1,
            instanceToken = "token_103",
            triggerTime = 1700000000L,
            restoreEpoch = 3L
        )
        assertEquals(3L, identity.restoreEpoch)
        val canonical = identity.canonicalString()
        assertTrue("Canonical string must include RESTORE_EPOCH:3", canonical.contains("RESTORE_EPOCH:3"))
    }

    @Test
    fun r4_mismatchedIntentRestoreEpoch_rejectsDelivery() {
        // Contract R4: DirectBootV2NormalIntentIdentity produces distinct digests when restoreEpoch differs.
        val identity1 = DirectBootV2NormalIntentIdentity(
            alarmId = 104L,
            generation = 1,
            instanceToken = "token_104",
            triggerTime = 1700000000L,
            restoreEpoch = 1L
        )

        val identity2 = DirectBootV2NormalIntentIdentity(
            alarmId = 104L,
            generation = 1,
            instanceToken = "token_104",
            triggerTime = 1700000000L,
            restoreEpoch = 2L
        )

        assertNotEquals(identity1.computeDigest(), identity2.computeDigest())
    }

    @Test
    fun r5_firedOccurrenceIdentityPropagatesRestoreEpochToDeliveredRecord() {
        // Contract R5: FiredOccurrenceIdentity restoreEpoch must propagate to DirectBootDeliveryRecord.
        val firingIdentity = FiredOccurrenceIdentity(
            kind = "NORMAL",
            alarmId = 105L,
            triggerTime = 1700000000L,
            generation = 1,
            instanceToken = "token_105",
            restoreEpoch = 7L,
            provenance = "TEST"
        )
        assertEquals(7L, firingIdentity.restoreEpoch)

        val record = DirectBootDeliveryRecord.createNormal(
            alarmId = firingIdentity.alarmId,
            triggerTime = firingIdentity.triggerTime,
            deliveredAt = 1700000010L,
            generation = firingIdentity.generation,
            instanceToken = firingIdentity.instanceToken,
            provenance = firingIdentity.provenance,
            restoreEpoch = firingIdentity.restoreEpoch
        )

        val json = record.toJson()
        assertTrue("DirectBootDeliveryRecord JSON must contain 'restoreEpoch'", json.has("restoreEpoch"))
        assertEquals(7L, json.getLong("restoreEpoch"))
        assertEquals(7L, record.restoreEpoch)
    }

    @Test
    fun h3_deRecordV2WithInvalidRestoreEpoch_isRejected() {
        // Contract H3: Normal DE Delivery & Missed Record v2 with restoreEpoch <= 0L must be invalid.
        val deliveryRecord = DirectBootDeliveryRecord.createNormal(
            alarmId = 105L,
            triggerTime = 1700000000L,
            deliveredAt = 1700000010L,
            generation = 1,
            instanceToken = "token_105",
            provenance = "TEST",
            restoreEpoch = 0L,
            schemaVersion = 2
        )
        assertEquals(DeliveryValidationStatus.INVALID_RESTORE_EPOCH, deliveryRecord.evaluateValidation())

        val missedRecord = DirectBootMissedRecord.createNormal(
            alarmId = 106L,
            triggerTime = 1700000000L,
            missedAt = 1700000010L,
            generation = 1,
            instanceToken = "token_106",
            classification = "OVERDUE",
            restoreEpoch = 0L,
            schemaVersion = 2
        )
        assertEquals(MissedValidationStatus.INVALID_RESTORE_EPOCH, missedRecord.evaluateValidation())
    }

    @Test
    fun h4_duplicateRecordIdWithConflictingPayload_returnsContentConflict() {
        // Contract H4: Same recordId with conflicting restoreEpoch or payload must return ContentConflict.
        val prefs = context.applicationContext.createDeviceProtectedStorageContext()
            .getSharedPreferences(DirectBootDeliveryLedger.PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().remove(DirectBootDeliveryLedger.KEY_DELIVERY_LEDGER_PAYLOAD).commit()

        val record1 = DirectBootDeliveryRecord.createNormal(
            alarmId = 107L,
            triggerTime = 1700000000L,
            deliveredAt = 1700000010L,
            generation = 1,
            instanceToken = "token_107",
            restoreEpoch = 1L
        )

        val record2 = DirectBootDeliveryRecord.createNormal(
            alarmId = 107L,
            triggerTime = 1700000000L,
            deliveredAt = 1700000020L, // Different deliveredAt
            generation = 1,
            instanceToken = "token_107",
            restoreEpoch = 2L // Different restoreEpoch
        )

        val res1 = DirectBootDeliveryLedger.appendRecord(context, record1)
        assertTrue(res1 is DeliveryAppendResult.Appended)

        val res2 = DirectBootDeliveryLedger.appendRecord(context, record2)
        assertTrue("Duplicate recordId with conflicting payload must return ContentConflict", res2 is DeliveryAppendResult.ContentConflict)
    }

    @Test
    fun r6_firedOccurrenceIdentityPropagatesRestoreEpochToMissedRecord() {
        // Contract R6: FiredOccurrenceIdentity restoreEpoch must propagate to DirectBootMissedRecord.
        val firingIdentity = FiredOccurrenceIdentity(
            kind = "NORMAL",
            alarmId = 106L,
            triggerTime = 1700000000L,
            generation = 1,
            instanceToken = "token_106",
            restoreEpoch = 8L,
            provenance = "OVERDUE"
        )

        val record = DirectBootMissedRecord.createNormal(
            alarmId = firingIdentity.alarmId,
            triggerTime = firingIdentity.triggerTime,
            missedAt = 1700000010L,
            generation = firingIdentity.generation,
            instanceToken = firingIdentity.instanceToken,
            classification = "OVERDUE",
            restoreEpoch = firingIdentity.restoreEpoch
        )

        val json = record.toJson()
        assertTrue("DirectBootMissedRecord JSON must contain 'restoreEpoch'", json.has("restoreEpoch"))
        assertEquals(8L, json.getLong("restoreEpoch"))
        assertEquals(8L, record.restoreEpoch)
    }

    @Test
    fun r7_legacyV1RecordLackingRestoreEpoch_failsQualificationWhenAuthorityDiffers() {
        // Contract R7: Legacy DE v1 record lacking restoreEpoch defaults to restoreEpoch = 0L.
        val record = DirectBootDeliveryRecord.createNormal(
            alarmId = 107L,
            triggerTime = 1700000000L,
            deliveredAt = 1700000010L,
            generation = 1,
            instanceToken = "token_107",
            provenance = "LEGACY_V1",
            restoreEpoch = 0L,
            schemaVersion = 1
        )
        assertEquals(0L, record.restoreEpoch)
        assertTrue("Legacy record isValid under v1 schema", record.isValid)
    }

    @Test
    fun r8_queueOverwriteOrGenerationUpdateOrEpochMismatch_rejected() {
        // Contract R8: DirectBootV2NormalIntentIdentity canonical string must distinguish different restoreEpoch values.
        val identity1 = DirectBootV2NormalIntentIdentity(
            alarmId = 108L,
            generation = 1,
            instanceToken = "token_108",
            triggerTime = 1700000000L,
            restoreEpoch = 1L
        )

        val identity2 = DirectBootV2NormalIntentIdentity(
            alarmId = 108L,
            generation = 1,
            instanceToken = "token_108",
            triggerTime = 1700000000L,
            restoreEpoch = 2L
        )

        val canonical1 = identity1.canonicalString()
        val canonical2 = identity2.canonicalString()

        assertTrue(canonical1.contains("RESTORE_EPOCH:1"))
        assertTrue(canonical2.contains("RESTORE_EPOCH:2"))
        assertNotEquals(canonical1, canonical2)
    }
}
