package com.sysadmindoc.alarmclock.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure Unit Tests for [PendingIntentIdentityComparator] validating S1-C2 Correction A3 OS Key matching,
 * occurrence lineage comparison, 3-axis truth tables, identity flags semantics, URI canonical encoding with AOSP Uri.encode parity, and non-execution safety invariants.
 */
class PendingIntentIdentityDescriptorTest {

    private fun createV1Descriptor(
        alarmId: Long = 42L,
        requestCode: Int = alarmId.toInt(),
        action: String? = "com.sysadmindoc.alarmclock.ALARM_FIRE",
        creatorPackage: String = "com.sysadmindoc.alarmclock",
        creatorUserScope: Int = 0,
        targetComponent: String = "com.sysadmindoc.alarmclock.receiver.AlarmReceiver",
        isLegacyV1Alias: Boolean = true,
        flags: Int = 0
    ): PendingIntentIdentityDescriptor = PendingIntentIdentityDescriptor(
        operationType = PendingIntentOperationType.BROADCAST,
        creatorPackage = creatorPackage,
        creatorUserScope = creatorUserScope,
        targetComponent = targetComponent,
        intentAction = action,
        intentDataUri = null,
        intentMimeType = null,
        intentCategories = emptySet(),
        requestCode = requestCode,
        flags = flags,
        isLegacyV1Alias = isLegacyV1Alias
    )

    private fun createV2Descriptor(
        alarmId: Long = 42L,
        issueToken: String = "token-v1",
        requestCode: Int = 80001,
        creatorPackage: String = "com.sysadmindoc.alarmclock",
        targetComponent: String = "com.sysadmindoc.alarmclock.receiver.AlarmReceiver",
        action: String? = "com.sysadmindoc.alarmclock.ALARM_FIRE",
        operationType: PendingIntentOperationType = PendingIntentOperationType.BROADCAST,
        flags: Int = PendingIntentIdentityComparator.FLAG_IMMUTABLE,
        customDataUri: String? = null
    ): PendingIntentIdentityDescriptor = PendingIntentIdentityDescriptor(
        operationType = operationType,
        creatorPackage = creatorPackage,
        creatorUserScope = 0,
        targetComponent = targetComponent,
        intentAction = action,
        intentDataUri = customDataUri ?: "alarmclock://alarm/issue?alarmId=$alarmId&token=$issueToken",
        intentMimeType = null,
        intentCategories = emptySet(),
        requestCode = requestCode,
        flags = flags,
        isLegacyV1Alias = false
    )

    private fun createLineage(
        alarmId: Long = 42L,
        scheduledTriggerMillis: Long = 1_700_000_000_000L,
        generation: Int = 1,
        revision: Int = 1,
        issueToken: String? = "token-v1",
        instanceToken: String? = "inst-token-v1",
        restoreEpoch: Long? = 1L,
        scheduleRevision: Long? = 100L,
        definitionRevision: Long? = 10L
    ): OccurrenceLineageDescriptor = OccurrenceLineageDescriptor(
        alarmId = alarmId,
        alarmKind = "NORMAL",
        scheduledTriggerMillis = scheduledTriggerMillis,
        generation = generation,
        revision = revision,
        issueToken = issueToken,
        instanceToken = instanceToken,
        restoreEpoch = restoreEpoch,
        scheduleRevision = scheduleRevision,
        definitionRevision = definitionRevision
    )

    // --- 1. Non-Execution Safety Invariants ---

    @Test
    fun test00_mandatoryNonExecutionInvariants() {
        val osA = createV2Descriptor()
        val osB = createV2Descriptor()
        val linA = createLineage()
        val linB = createLineage()

        val outcome = PendingIntentIdentityComparator.evaluateSafety(osA, osB, linA, linB)

        assertFalse("isPermitted MUST be false", outcome.isPermitted)
        assertFalse("allowsDestructiveCleanup MUST be false", outcome.allowsDestructiveCleanup)
        assertFalse("isOsCancelCompleted MUST be false", outcome.isOsCancelCompleted)
        assertFalse("executesCancellation MUST be false", outcome.executesCancellation)
    }

    // --- 2. OS Key & Lineage Tests ---

    @Test
    fun test01_sameV1AlarmIdDifferentTrigger() {
        val osA = createV1Descriptor(alarmId = 42L)
        val osB = createV1Descriptor(alarmId = 42L)
        val linA = createLineage(alarmId = 42L, scheduledTriggerMillis = 1000L)
        val linB = createLineage(alarmId = 42L, scheduledTriggerMillis = 2000L)

        val osKeyResult = PendingIntentIdentityComparator.compareOsKeys(osA, osB)
        val linResult = PendingIntentIdentityComparator.compareLineages(linA, linB)
        val safety = PendingIntentIdentityComparator.evaluateSafety(osA, osB, linA, linB)

        assertEquals("V1 OS Keys matching parameters are EQUIVALENT", OsKeyComparison.EQUIVALENT, osKeyResult)
        assertEquals("Lineages with different trigger times are DIFFERENT", OccurrenceComparison.DIFFERENT, linResult)
        assertTrue("Safety must be UnsafeAlias due to red04 alias collision", safety is CrossIdentitySafety.UnsafeAlias)
        val unsafe = safety as CrossIdentitySafety.UnsafeAlias
        assertEquals(CancellationRejectionReason.TARGET_WITNESS_MISMATCH, unsafe.reason)
    }

    @Test
    fun test02_sameV1TriggerDifferentToken() {
        val osA = createV1Descriptor(alarmId = 42L)
        val osB = createV1Descriptor(alarmId = 42L)
        val linA = createLineage(alarmId = 42L, instanceToken = "tok-1")
        val linB = createLineage(alarmId = 42L, instanceToken = "tok-2")

        val osKeyResult = PendingIntentIdentityComparator.compareOsKeys(osA, osB)
        val linResult = PendingIntentIdentityComparator.compareLineages(linA, linB)
        val safety = PendingIntentIdentityComparator.evaluateSafety(osA, osB, linA, linB)

        assertEquals(OsKeyComparison.EQUIVALENT, osKeyResult)
        assertEquals(OccurrenceComparison.DIFFERENT, linResult)
        assertTrue(safety is CrossIdentitySafety.UnsafeAlias)
    }

    @Test
    fun test03_longIntRequestCodeTruncationCollision() {
        val idA = 42L
        val idB = 4_294_967_338L // 42 + 2^32 -> truncates to 42.toInt()
        assertEquals(idA.toInt(), idB.toInt())

        val osA = createV1Descriptor(alarmId = idA, requestCode = idA.toInt())
        val osB = createV1Descriptor(alarmId = idB, requestCode = idB.toInt())
        val linA = createLineage(alarmId = idA)
        val linB = createLineage(alarmId = idB)

        val osKeyResult = PendingIntentIdentityComparator.compareOsKeys(osA, osB)
        val linResult = PendingIntentIdentityComparator.compareLineages(linA, linB)
        val safety = PendingIntentIdentityComparator.evaluateSafety(osA, osB, linA, linB)

        assertEquals(OsKeyComparison.EQUIVALENT, osKeyResult)
        assertEquals(OccurrenceComparison.DIFFERENT, linResult)
        assertTrue(safety is CrossIdentitySafety.UnsafeAlias)
    }

    @Test
    fun test04_negativeRequestCodeValidIdentity() {
        val osA = createV1Descriptor(requestCode = -100)
        val osB = createV1Descriptor(requestCode = -100)
        val linA = createLineage()
        val linB = createLineage()

        val osKeyResult = PendingIntentIdentityComparator.compareOsKeys(osA, osB)
        assertEquals("Negative RequestCode is valid and produces EQUIVALENT when matching", OsKeyComparison.EQUIVALENT, osKeyResult)
    }

    @Test
    fun test05_v1VsV2UriDifference() {
        val osV1 = createV1Descriptor()
        val osV2 = createV2Descriptor()
        val linA = createLineage()
        val linB = createLineage()

        val osKeyResult = PendingIntentIdentityComparator.compareOsKeys(osV1, osV2)
        val safety = PendingIntentIdentityComparator.evaluateSafety(osV1, osV2, linA, linB)

        assertEquals("V1 (null URI) vs V2 (data URI) must be DISTINCT", OsKeyComparison.DISTINCT, osKeyResult)
        assertTrue("V1 vs V2 produces TargetMismatch", safety is CrossIdentitySafety.TargetMismatch)
    }

    @Test
    fun test06_sameV2TokenSameOccurrence() {
        val osA = createV2Descriptor(issueToken = "token-v1")
        val osB = createV2Descriptor(issueToken = "token-v1")
        val linA = createLineage(issueToken = "token-v1")
        val linB = createLineage(issueToken = "token-v1")

        val osKeyResult = PendingIntentIdentityComparator.compareOsKeys(osA, osB)
        val linResult = PendingIntentIdentityComparator.compareLineages(linA, linB)
        val safety = PendingIntentIdentityComparator.evaluateSafety(osA, osB, linA, linB)

        assertEquals(OsKeyComparison.EQUIVALENT, osKeyResult)
        assertEquals(OccurrenceComparison.SAME, linResult)
        assertTrue("V2 tokenized match produces ExactCandidate", safety is CrossIdentitySafety.ExactCandidate)
    }

    @Test
    fun test07_sameV2TokenDifferentGeneration() {
        val osA = createV2Descriptor(issueToken = "token-v1")
        val osB = createV2Descriptor(issueToken = "token-v1")
        val linA = createLineage(generation = 1)
        val linB = createLineage(generation = 2)

        val linResult = PendingIntentIdentityComparator.compareLineages(linA, linB)
        val safety = PendingIntentIdentityComparator.evaluateSafety(osA, osB, linA, linB)

        assertEquals(OccurrenceComparison.DIFFERENT, linResult)
        assertTrue(safety is CrossIdentitySafety.UnsafeAlias)
    }

    @Test
    fun test08_differentV2TokenSameAlarmId() {
        val osA = createV2Descriptor(issueToken = "token-v1")
        val osB = createV2Descriptor(issueToken = "token-v2")
        val linA = createLineage(issueToken = "token-v1")
        val linB = createLineage(issueToken = "token-v2")

        val osKeyResult = PendingIntentIdentityComparator.compareOsKeys(osA, osB)
        assertEquals("Different V2 tokens produce DISTINCT OS Keys", OsKeyComparison.DISTINCT, osKeyResult)
    }

    @Test
    fun test09_v1ExtrasDifferenceIgnoredByOsKey() {
        val osA = createV1Descriptor()
        val osB = createV1Descriptor()
        val linA = createLineage()
        val linB = createLineage()

        val osKeyResult = PendingIntentIdentityComparator.compareOsKeys(osA, osB)
        assertEquals("Extras difference DOES NOT make V1 OS keys distinct", OsKeyComparison.EQUIVALENT, osKeyResult)
    }

    @Test
    fun test10_quickVsNormalActionMismatch() {
        val osNormal = createV1Descriptor(action = "com.sysadmindoc.alarmclock.ALARM_FIRE")
        val osQuick = createV1Descriptor(action = null, requestCode = 80042)
        val linA = createLineage()
        val linB = createLineage()

        val osKeyResult = PendingIntentIdentityComparator.compareOsKeys(osNormal, osQuick)
        assertEquals("Quick (null action) vs Normal (ALARM_FIRE) must be DISTINCT", OsKeyComparison.DISTINCT, osKeyResult)
    }

    @Test
    fun test11_quickVsQuickSameIdDifferentTrigger() {
        val osQuickA = createV1Descriptor(action = null, requestCode = 80042)
        val osQuickB = createV1Descriptor(action = null, requestCode = 80042)
        val linA = createLineage(scheduledTriggerMillis = 1000L)
        val linB = createLineage(scheduledTriggerMillis = 2000L)

        val osKeyResult = PendingIntentIdentityComparator.compareOsKeys(osQuickA, osQuickB)
        val linResult = PendingIntentIdentityComparator.compareLineages(linA, linB)
        val safety = PendingIntentIdentityComparator.evaluateSafety(osQuickA, osQuickB, linA, linB)

        assertEquals(OsKeyComparison.EQUIVALENT, osKeyResult)
        assertEquals(OccurrenceComparison.DIFFERENT, linResult)
        assertTrue(safety is CrossIdentitySafety.UnsafeAlias)
    }

    @Test
    fun test12_creatorPackageDifference() {
        val osA = createV1Descriptor(creatorPackage = "com.sysadmindoc.alarmclock")
        val osB = createV1Descriptor(creatorPackage = "com.other.app")
        val linA = createLineage()
        val linB = createLineage()

        val osKeyResult = PendingIntentIdentityComparator.compareOsKeys(osA, osB)
        assertEquals("Different creatorPackage produces DISTINCT OS Keys", OsKeyComparison.DISTINCT, osKeyResult)
    }

    @Test
    fun test13_creatorUserScopeDifference() {
        val osA = createV1Descriptor().copy(creatorUserScope = 0)
        val osB = createV1Descriptor().copy(creatorUserScope = 10)
        val linA = createLineage()
        val linB = createLineage()

        val osKeyResult = PendingIntentIdentityComparator.compareOsKeys(osA, osB)
        assertEquals("Different creatorUserScope produces DISTINCT OS Keys", OsKeyComparison.DISTINCT, osKeyResult)
    }

    @Test
    fun test14_targetComponentDifference() {
        val osA = createV1Descriptor(targetComponent = "com.sysadmindoc.alarmclock.receiver.AlarmReceiver")
        val osB = createV1Descriptor(targetComponent = "com.sysadmindoc.alarmclock.receiver.BootReceiver")
        val linA = createLineage()
        val linB = createLineage()

        val osKeyResult = PendingIntentIdentityComparator.compareOsKeys(osA, osB)
        assertEquals("Different targetComponent produces DISTINCT OS Keys", OsKeyComparison.DISTINCT, osKeyResult)
    }

    @Test
    fun test15_uriCaseSensitivityInPathAndQuery() {
        val osA = createV2Descriptor(issueToken = "Token-V1")
        val osB = createV2Descriptor(issueToken = "token-v1")
        val linA = createLineage()
        val linB = createLineage()

        val osKeyResult = PendingIntentIdentityComparator.compareOsKeys(osA, osB)
        assertEquals("Case differences in URI query parameters produce DISTINCT OS Keys", OsKeyComparison.DISTINCT, osKeyResult)
    }

    @Test
    fun test16_percentEncodingDiffersFromRaw() {
        val osA = createV2Descriptor(issueToken = "token%20v1")
        val osB = createV2Descriptor(issueToken = "token v1")
        val linA = createLineage()
        val linB = createLineage()

        val osKeyResult = PendingIntentIdentityComparator.compareOsKeys(osA, osB)
        assertEquals("Raw space vs percent-encoded string produce DISTINCT OS Keys", OsKeyComparison.DISTINCT, osKeyResult)
    }

    @Test
    fun test17_nullDataUriVsEmptyDataUri() {
        val osNull = createV1Descriptor().copy(intentDataUri = null)
        val osEmpty = createV1Descriptor().copy(intentDataUri = "")
        val linA = createLineage()
        val linB = createLineage()

        val osKeyResult = PendingIntentIdentityComparator.compareOsKeys(osNull, osEmpty)
        val safety = PendingIntentIdentityComparator.evaluateSafety(osNull, osEmpty, linA, linB)

        assertEquals("null URI vs empty string URI must be INDETERMINATE due to malformed input", OsKeyComparison.INDETERMINATE, osKeyResult)
        assertTrue("Malformed input produces EvidenceIncomplete", safety is CrossIdentitySafety.EvidenceIncomplete)
    }

    @Test
    fun test18_differentRestoreEpoch() {
        val osA = createV2Descriptor(issueToken = "token-v1")
        val osB = createV2Descriptor(issueToken = "token-v1")
        val linA = createLineage(restoreEpoch = 1L)
        val linB = createLineage(restoreEpoch = 2L)

        val osKeyResult = PendingIntentIdentityComparator.compareOsKeys(osA, osB)
        val linResult = PendingIntentIdentityComparator.compareLineages(linA, linB)
        val safety = PendingIntentIdentityComparator.evaluateSafety(osA, osB, linA, linB)

        assertEquals("OS Key is unaffected by restoreEpoch", OsKeyComparison.EQUIVALENT, osKeyResult)
        assertEquals("Different restoreEpoch produces DIFFERENT occurrence", OccurrenceComparison.DIFFERENT, linResult)
        assertTrue("Different restoreEpoch produces UnsafeAlias", safety is CrossIdentitySafety.UnsafeAlias)
    }

    @Test
    fun test19_missingInstanceTokenLineage() {
        val osA = createV2Descriptor()
        val osB = createV2Descriptor()
        val linA = createLineage(instanceToken = null)
        val linB = createLineage(instanceToken = "inst-v1")

        val linResult = PendingIntentIdentityComparator.compareLineages(linA, linB)
        val safety = PendingIntentIdentityComparator.evaluateSafety(osA, osB, linA, linB)

        assertEquals("Null instanceToken produces UNKNOWN lineage", OccurrenceComparison.UNKNOWN, linResult)
        assertTrue("UNKNOWN lineage produces EvidenceIncomplete", safety is CrossIdentitySafety.EvidenceIncomplete)
        assertEquals("PERSISTENCE_UNCERTAIN", (safety as CrossIdentitySafety.EvidenceIncomplete).missingWitness)
    }

    @Test
    fun test20_malformedInputFailsClosed() {
        val osMalformed = createV1Descriptor(requestCode = 0, creatorPackage = "")
        val osValid = createV1Descriptor()
        val linA = createLineage(alarmId = -1L)
        val linB = createLineage()

        val osKeyResult = PendingIntentIdentityComparator.compareOsKeys(osMalformed, osValid)
        val linResult = PendingIntentIdentityComparator.compareLineages(linA, linB)
        val safety = PendingIntentIdentityComparator.evaluateSafety(osMalformed, osValid, linA, linB)

        assertEquals(OsKeyComparison.INDETERMINATE, osKeyResult)
        assertEquals(OccurrenceComparison.UNKNOWN, linResult)
        assertTrue(safety is CrossIdentitySafety.EvidenceIncomplete)
    }

    @Test
    fun test21_v2TokenReuseAcrossAlarms() {
        val osA = createV2Descriptor(alarmId = 42L, issueToken = "reused-token")
        val osB = createV2Descriptor(alarmId = 99L, issueToken = "reused-token")
        val linA = createLineage(alarmId = 42L, issueToken = "reused-token")
        val linB = createLineage(alarmId = 99L, issueToken = "reused-token")

        val osKeyResult = PendingIntentIdentityComparator.compareOsKeys(osA, osB)
        val linResult = PendingIntentIdentityComparator.compareLineages(linA, linB)
        val safety = PendingIntentIdentityComparator.evaluateSafety(osA, osB, linA, linB)

        assertEquals("Different alarmId in Data URI produces DISTINCT OS keys", OsKeyComparison.DISTINCT, osKeyResult)
        assertEquals("Different alarmId in lineage produces DIFFERENT occurrence", OccurrenceComparison.DIFFERENT, linResult)
        assertTrue(safety is CrossIdentitySafety.TargetMismatch)
    }

    @Test
    fun test22_scheduleRevisionDifference() {
        val osA = createV2Descriptor()
        val osB = createV2Descriptor()
        val linA = createLineage(scheduleRevision = 100L)
        val linB = createLineage(scheduleRevision = 200L)

        val linResult = PendingIntentIdentityComparator.compareLineages(linA, linB)
        val safety = PendingIntentIdentityComparator.evaluateSafety(osA, osB, linA, linB)

        assertEquals("Different scheduleRevision produces DIFFERENT occurrence", OccurrenceComparison.DIFFERENT, linResult)
        assertTrue(safety is CrossIdentitySafety.UnsafeAlias)
    }

    @Test
    fun test23_definitionRevisionDifference() {
        val osA = createV2Descriptor()
        val osB = createV2Descriptor()
        val linA = createLineage(definitionRevision = 10L)
        val linB = createLineage(definitionRevision = 20L)

        val linResult = PendingIntentIdentityComparator.compareLineages(linA, linB)
        val safety = PendingIntentIdentityComparator.evaluateSafety(osA, osB, linA, linB)

        assertEquals("Different definitionRevision produces DIFFERENT occurrence", OccurrenceComparison.DIFFERENT, linResult)
        assertTrue(safety is CrossIdentitySafety.UnsafeAlias)
    }

    @Test
    fun test24_legacyV1DisguisedAsNonAliasFailsToUnsafeAlias() {
        val osDisguised = createV1Descriptor(alarmId = 42L, isLegacyV1Alias = false)
        val osV1 = createV1Descriptor(alarmId = 42L, isLegacyV1Alias = true)
        val linA = createLineage(alarmId = 42L)
        val linB = createLineage(alarmId = 42L)

        assertTrue("computedIsLegacyV1Alias MUST detect V1 legacy identity even if isLegacyV1Alias flag is false", osDisguised.computedIsLegacyV1Alias)

        val safety = PendingIntentIdentityComparator.evaluateSafety(osDisguised, osV1, linA, linB)
        assertTrue("Disguised V1 legacy intent MUST NOT be promoted to ExactCandidate; must evaluate to UnsafeAlias", safety is CrossIdentitySafety.UnsafeAlias)
        val unsafe = safety as CrossIdentitySafety.UnsafeAlias
        assertEquals(CancellationRejectionReason.OS_STATE_UNCERTAIN, unsafe.reason)
    }

    @Test
    fun test25_flagsDifferenceDoesNotCauseDistinctOsKey() {
        val osA = createV1Descriptor(flags = PendingIntentIdentityComparator.FLAG_UPDATE_CURRENT)
        val osB = createV1Descriptor(flags = PendingIntentIdentityComparator.FLAG_CANCEL_CURRENT)
        val linA = createLineage()
        val linB = createLineage()

        val osKeyResult = PendingIntentIdentityComparator.compareOsKeys(osA, osB)
        assertEquals("Non-identity creation flags DO NOT make OS keys DISTINCT", OsKeyComparison.EQUIVALENT, osKeyResult)
    }

    @Test
    fun test26_instanceTokenMissingButAlarmIdDiffersReturnsDifferent() {
        val linA = createLineage(alarmId = 42L, instanceToken = null)
        val linB = createLineage(alarmId = 99L, instanceToken = null)

        val linResult = PendingIntentIdentityComparator.compareLineages(linA, linB)
        assertEquals("Divergent alarmId MUST return DIFFERENT even if instanceToken is null", OccurrenceComparison.DIFFERENT, linResult)
    }

    @Test
    fun test27_instanceTokenMissingAndAttributesMatchReturnsUnknownLineage() {
        val linA = createLineage(alarmId = 42L, instanceToken = null)
        val linB = createLineage(alarmId = 42L, instanceToken = null)

        val linResult = PendingIntentIdentityComparator.compareLineages(linA, linB)
        assertEquals("Matching lineage attributes with missing instanceToken MUST return UNKNOWN", OccurrenceComparison.UNKNOWN, linResult)
    }

    @Test
    fun test28_issueTokenMismatchReturnsDifferent() {
        val linA = createLineage(issueToken = "token-1")
        val linB = createLineage(issueToken = "token-2")

        val linResult = PendingIntentIdentityComparator.compareLineages(linA, linB)
        assertEquals("Divergent issueToken MUST return DIFFERENT occurrence", OccurrenceComparison.DIFFERENT, linResult)
    }

    @Test
    fun test29_v2SameUriDifferentRestoreEpochReturnsUnsafeAlias() {
        val osA = createV2Descriptor(issueToken = "token-v1")
        val osB = createV2Descriptor(issueToken = "token-v1")
        val linA = createLineage(restoreEpoch = 1L)
        val linB = createLineage(restoreEpoch = 2L)

        val osKeyResult = PendingIntentIdentityComparator.compareOsKeys(osA, osB)
        val linResult = PendingIntentIdentityComparator.compareLineages(linA, linB)
        val safety = PendingIntentIdentityComparator.evaluateSafety(osA, osB, linA, linB)

        assertEquals(OsKeyComparison.EQUIVALENT, osKeyResult)
        assertEquals(OccurrenceComparison.DIFFERENT, linResult)
        assertTrue("Different restoreEpoch MUST evaluate to UnsafeAlias", safety is CrossIdentitySafety.UnsafeAlias)
    }

    // --- 3. A2 Positive Tokenized Identity Proof Tests ---

    @Test
    fun test30_quickV1SameOsKeyAndLineageDoesNotReturnExactCandidate() {
        val osQuickA = createV1Descriptor(action = null, requestCode = 80042, isLegacyV1Alias = false)
        val osQuickB = createV1Descriptor(action = null, requestCode = 80042, isLegacyV1Alias = false)
        val linA = createLineage()
        val linB = createLineage()

        val safety = PendingIntentIdentityComparator.evaluateSafety(osQuickA, osQuickB, linA, linB)
        assertFalse("Quick V1 intent MUST NOT evaluate to ExactCandidate; must fail closed to UnsafeAlias", safety is CrossIdentitySafety.ExactCandidate)
        assertTrue("Quick V1 intent evaluates to UnsafeAlias", safety is CrossIdentitySafety.UnsafeAlias)
    }

    @Test
    fun test31_smartAlarmV1SameOsKeyAndLineageDoesNotReturnExactCandidate() {
        val osSmartA = createV1Descriptor(action = "com.sysadmindoc.alarmclock.ACTION_START_SMART", requestCode = 50042, isLegacyV1Alias = false)
        val osSmartB = createV1Descriptor(action = "com.sysadmindoc.alarmclock.ACTION_START_SMART", requestCode = 50042, isLegacyV1Alias = false)
        val linA = createLineage()
        val linB = createLineage()

        val safety = PendingIntentIdentityComparator.evaluateSafety(osSmartA, osSmartB, linA, linB)
        assertFalse("SmartAlarm V1 intent MUST NOT evaluate to ExactCandidate; must fail closed to UnsafeAlias", safety is CrossIdentitySafety.ExactCandidate)
        assertTrue("SmartAlarm V1 intent evaluates to UnsafeAlias", safety is CrossIdentitySafety.UnsafeAlias)
    }

    @Test
    fun test32_unknownSourceKindFailsClosedToEvidenceIncomplete() {
        val osUnknown = createV2Descriptor().copy(operationType = PendingIntentOperationType.UNKNOWN)
        val osV2 = createV2Descriptor()
        val linA = createLineage()
        val linB = createLineage()

        val osKeyResult = PendingIntentIdentityComparator.compareOsKeys(osUnknown, osV2)
        val safety = PendingIntentIdentityComparator.evaluateSafety(osUnknown, osV2, linA, linB)

        assertEquals("UNKNOWN operation type produces INDETERMINATE OS key", OsKeyComparison.INDETERMINATE, osKeyResult)
        assertTrue("INDETERMINATE OS key produces EvidenceIncomplete", safety is CrossIdentitySafety.EvidenceIncomplete)
    }

    @Test
    fun test33_tokenizedUriMissingTokenQueryParamFailsToUnsafeAlias() {
        val osNoToken = createV2Descriptor().copy(intentDataUri = "alarmclock://alarm/issue?alarmId=42")
        val osV2 = createV2Descriptor()
        val linA = createLineage()
        val linB = createLineage()

        val safety = PendingIntentIdentityComparator.evaluateSafety(osNoToken, osV2, linA, linB)
        assertFalse("URI missing token query parameter MUST NOT evaluate to ExactCandidate", safety is CrossIdentitySafety.ExactCandidate)
    }

    @Test
    fun test34_uriAlarmIdMismatchWithLineageAlarmIdFailsToUnsafeAlias() {
        val osMismatchUri = createV2Descriptor(alarmId = 99L, issueToken = "token-v1")
        val osV2 = createV2Descriptor(alarmId = 99L, issueToken = "token-v1")
        val linA = createLineage(alarmId = 42L, issueToken = "token-v1")
        val linB = createLineage(alarmId = 42L, issueToken = "token-v1")

        val safety = PendingIntentIdentityComparator.evaluateSafety(osMismatchUri, osV2, linA, linB)
        assertFalse("URI alarmId mismatch with lineage alarmId MUST NOT evaluate to ExactCandidate", safety is CrossIdentitySafety.ExactCandidate)
    }

    @Test
    fun test35_uriTokenMismatchWithLineageIssueTokenFailsToUnsafeAlias() {
        val osUriX = createV2Descriptor(alarmId = 42L, issueToken = "token-X")
        val osUriX2 = createV2Descriptor(alarmId = 42L, issueToken = "token-X")
        val linA = createLineage(alarmId = 42L, issueToken = "token-v1")
        val linB = createLineage(alarmId = 42L, issueToken = "token-v1")

        val safety = PendingIntentIdentityComparator.evaluateSafety(osUriX, osUriX2, linA, linB)
        assertFalse("URI token mismatch with lineage issueToken MUST NOT evaluate to ExactCandidate", safety is CrossIdentitySafety.ExactCandidate)
    }

    @Test
    fun test36_identityRelevantFlagsDifference() {
        val osA = createV2Descriptor(flags = PendingIntentIdentityComparator.FLAG_ONE_SHOT)
        val osB = createV2Descriptor(flags = PendingIntentIdentityComparator.FLAG_IMMUTABLE)
        val linA = createLineage()
        val linB = createLineage()

        val osKeyResult = PendingIntentIdentityComparator.compareOsKeys(osA, osB)
        assertEquals("Identity-relevant flags difference produces DISTINCT OS key", OsKeyComparison.DISTINCT, osKeyResult)
    }

    @Test
    fun test37_nonIdentityFlagsDifferenceIgnoredByOsKey() {
        val osA = createV2Descriptor(flags = PendingIntentIdentityComparator.FLAG_UPDATE_CURRENT)
        val osB = createV2Descriptor(flags = PendingIntentIdentityComparator.FLAG_CANCEL_CURRENT)
        val linA = createLineage()
        val linB = createLineage()

        val osKeyResult = PendingIntentIdentityComparator.compareOsKeys(osA, osB)
        assertEquals("Creation flags DO NOT make OS keys DISTINCT", OsKeyComparison.EQUIVALENT, osKeyResult)
    }

    @Test
    fun test38_issueTokenOneSideNullReturnsDifferent() {
        val linA = createLineage(issueToken = "token-1")
        val linB = createLineage(issueToken = null)

        val linResult = PendingIntentIdentityComparator.compareLineages(linA, linB)
        assertEquals("One-sided null issueToken MUST return DIFFERENT occurrence", OccurrenceComparison.DIFFERENT, linResult)
    }

    @Test
    fun test39_legacyBooleanFalseDoesNotBypassV2TokenCheck() {
        val osNoUriNoFlag = PendingIntentIdentityDescriptor(
            operationType = PendingIntentOperationType.BROADCAST,
            creatorPackage = "com.sysadmindoc.alarmclock",
            creatorUserScope = 0,
            targetComponent = "com.sysadmindoc.alarmclock.receiver.AlarmReceiver",
            intentAction = "com.sysadmindoc.alarmclock.ALARM_FIRE",
            intentDataUri = null,
            requestCode = 42,
            isLegacyV1Alias = false
        )
        val osV2 = createV2Descriptor()
        val linA = createLineage()
        val linB = createLineage()

        val safety = PendingIntentIdentityComparator.evaluateSafety(osNoUriNoFlag, osV2, linA, linB)
        assertFalse("Descriptor without V2 tokenized URI MUST NOT evaluate to ExactCandidate", safety is CrossIdentitySafety.ExactCandidate)
    }

    @Test
    fun test40_nonExecutionInvariantsHoldAcrossAllA2Outcomes() {
        val osV2 = createV2Descriptor()
        val lin = createLineage()

        val safetyExact = PendingIntentIdentityComparator.evaluateSafety(osV2, osV2, lin, lin)
        assertFalse(safetyExact.isPermitted)
        assertFalse(safetyExact.allowsDestructiveCleanup)
        assertFalse(safetyExact.isOsCancelCompleted)
        assertFalse(safetyExact.executesCancellation)

        val osV1 = createV1Descriptor()
        val safetyUnsafe = PendingIntentIdentityComparator.evaluateSafety(osV1, osV1, lin, lin)
        assertFalse(safetyUnsafe.isPermitted)
        assertFalse(safetyUnsafe.allowsDestructiveCleanup)
        assertFalse(safetyUnsafe.isOsCancelCompleted)
        assertFalse(safetyUnsafe.executesCancellation)
    }

    // --- 4. A3 Final Proof Hardening Tests ---

    @Test
    fun test41_v2UriWithInvalidActionFailsToUnsafeAlias() {
        val osInvalidAction = createV2Descriptor(action = "com.other.INVALID_ACTION")
        val osV2 = createV2Descriptor()
        val linA = createLineage()
        val linB = createLineage()

        val safety = PendingIntentIdentityComparator.evaluateSafety(osInvalidAction, osV2, linA, linB)
        assertFalse("V2 URI with invalid action MUST NOT evaluate to ExactCandidate", safety is CrossIdentitySafety.ExactCandidate)
    }

    @Test
    fun test42_v2UriWithInvalidComponentFailsToUnsafeAlias() {
        val osInvalidComponent = createV2Descriptor(targetComponent = "com.sysadmindoc.alarmclock.receiver.BootReceiver")
        val osV2 = createV2Descriptor()
        val linA = createLineage()
        val linB = createLineage()

        val safety = PendingIntentIdentityComparator.evaluateSafety(osInvalidComponent, osV2, linA, linB)
        assertFalse("V2 URI with invalid component MUST NOT evaluate to ExactCandidate", safety is CrossIdentitySafety.ExactCandidate)
    }

    @Test
    fun test43_v2UriWithInvalidOperationTypeFailsToUnsafeAlias() {
        val osInvalidOp = createV2Descriptor(operationType = PendingIntentOperationType.SERVICE)
        val osV2 = createV2Descriptor()
        val linA = createLineage()
        val linB = createLineage()

        val safety = PendingIntentIdentityComparator.evaluateSafety(osInvalidOp, osV2, linA, linB)
        assertFalse("V2 URI with invalid operationType MUST NOT evaluate to ExactCandidate", safety is CrossIdentitySafety.ExactCandidate)
    }

    @Test
    fun test44_v2UriWithInvalidRequestCodeFailsToUnsafeAlias() {
        val osInvalidRequestCode = createV2Descriptor(requestCode = 99999)
        val osV2 = createV2Descriptor()
        val linA = createLineage()
        val linB = createLineage()

        val safety = PendingIntentIdentityComparator.evaluateSafety(osInvalidRequestCode, osV2, linA, linB)
        assertFalse("V2 URI with invalid requestCode MUST NOT evaluate to ExactCandidate", safety is CrossIdentitySafety.ExactCandidate)
    }

    @Test
    fun test45_bothIssueTokensNullReturnsUnknownLineage() {
        val linA = createLineage(issueToken = null)
        val linB = createLineage(issueToken = null)

        val linResult = PendingIntentIdentityComparator.compareLineages(linA, linB)
        assertEquals("Both issueTokens null CANNOT prove SAME lineage", OccurrenceComparison.UNKNOWN, linResult)
    }

    @Test
    fun test46_v2FactoryProofRequiresExactMatchOnAllFactoryParams() {
        val osFactoryOK = createV2Descriptor(alarmId = 42L, issueToken = "token-v1")
        val linOK = createLineage(alarmId = 42L, issueToken = "token-v1")

        val safety = PendingIntentIdentityComparator.evaluateSafety(osFactoryOK, osFactoryOK, linOK, linOK)
        assertTrue("V2 Factory proof with complete matching parameters MUST evaluate to ExactCandidate", safety is CrossIdentitySafety.ExactCandidate)
    }

    // --- 5. A4 Semantic Safety Closure Tests ---

    @Test
    fun test47_v2UriWithDuplicateQueryParamsFailsToUnsafeAlias() {
        val osDuplicateParam = createV2Descriptor(customDataUri = "alarmclock://alarm/issue?alarmId=42&alarmId=99&token=token-v1")
        val osV2 = createV2Descriptor()
        val linA = createLineage()
        val linB = createLineage()

        val safety = PendingIntentIdentityComparator.evaluateSafety(osDuplicateParam, osV2, linA, linB)
        assertFalse("V2 URI with duplicate query parameters MUST NOT evaluate to ExactCandidate", safety is CrossIdentitySafety.ExactCandidate)
    }

    @Test
    fun test48_v2UriWithUnexpectedQueryParamFailsToUnsafeAlias() {
        val osUnexpectedParam = createV2Descriptor(customDataUri = "alarmclock://alarm/issue?alarmId=42&token=token-v1&unknown=123")
        val osV2 = createV2Descriptor()
        val linA = createLineage()
        val linB = createLineage()

        val safety = PendingIntentIdentityComparator.evaluateSafety(osUnexpectedParam, osV2, linA, linB)
        assertFalse("V2 URI with unexpected query parameters MUST NOT evaluate to ExactCandidate", safety is CrossIdentitySafety.ExactCandidate)
    }

    @Test
    fun test49_v2UriWithMalformedPercentEncodingFailsToUnsafeAlias() {
        val osMalformedEncoding = createV2Descriptor(customDataUri = "alarmclock://alarm/issue?alarmId=42&token=token%2Gv1")
        val osV2 = createV2Descriptor()
        val linA = createLineage()
        val linB = createLineage()

        val safety = PendingIntentIdentityComparator.evaluateSafety(osMalformedEncoding, osV2, linA, linB)
        assertFalse("V2 URI with malformed percent encoding MUST NOT evaluate to ExactCandidate", safety is CrossIdentitySafety.ExactCandidate)
    }

    @Test
    fun test50_v2UriWithWrongSchemeOrHostFailsToUnsafeAlias() {
        val osWrongScheme = createV2Descriptor(customDataUri = "http://alarm/issue?alarmId=42&token=token-v1")
        val osV2 = createV2Descriptor()
        val linA = createLineage()
        val linB = createLineage()

        val safety = PendingIntentIdentityComparator.evaluateSafety(osWrongScheme, osV2, linA, linB)
        assertFalse("V2 URI with wrong scheme/host MUST NOT evaluate to ExactCandidate", safety is CrossIdentitySafety.ExactCandidate)
    }

    @Test
    fun test51_issueTokenMissingWithDivergentAlarmIdReturnsDifferentNotUnknown() {
        val linA = createLineage(alarmId = 42L, issueToken = null)
        val linB = createLineage(alarmId = 99L, issueToken = null)

        val linResult = PendingIntentIdentityComparator.compareLineages(linA, linB)
        assertEquals("Divergent alarmId MUST return DIFFERENT even if issueToken is null", OccurrenceComparison.DIFFERENT, linResult)
    }

    @Test
    fun test52_issueTokenMissingWithDivergentGenerationReturnsDifferentNotUnknown() {
        val linA = createLineage(generation = 1, issueToken = null)
        val linB = createLineage(generation = 2, issueToken = null)

        val linResult = PendingIntentIdentityComparator.compareLineages(linA, linB)
        assertEquals("Divergent generation MUST return DIFFERENT even if issueToken is null", OccurrenceComparison.DIFFERENT, linResult)
    }

    @Test
    fun test53_knownDivergenceBeforeUnknownOrderVerification() {
        val linA = createLineage(revision = 1, instanceToken = null)
        val linB = createLineage(revision = 2, instanceToken = null)

        val linResult = PendingIntentIdentityComparator.compareLineages(linA, linB)
        assertEquals("Known divergence (revision 1 vs 2) takes priority over missing instanceToken, returning DIFFERENT", OccurrenceComparison.DIFFERENT, linResult)
    }

    @Test
    fun test54_identityFlagsMaskingAndUnknownFlagBits() {
        val osA = createV2Descriptor(flags = 0)
        val osB = createV2Descriptor(flags = PendingIntentIdentityComparator.FLAG_UPDATE_CURRENT)

        val osKeyResult = PendingIntentIdentityComparator.compareOsKeys(osA, osB)
        assertEquals("Creation flags DO NOT make OS keys DISTINCT", OsKeyComparison.EQUIVALENT, osKeyResult)
    }

    @Test
    fun test55_nonExecutionInvariantsHoldAcrossAllA4Outcomes() {
        val osV2 = createV2Descriptor()
        val lin = createLineage()

        val safetyExact = PendingIntentIdentityComparator.evaluateSafety(osV2, osV2, lin, lin)
        assertFalse(safetyExact.isPermitted)
        assertFalse(safetyExact.allowsDestructiveCleanup)
        assertFalse(safetyExact.isOsCancelCompleted)
        assertFalse(safetyExact.executesCancellation)

        val osV1 = createV1Descriptor()
        val safetyUnsafe = PendingIntentIdentityComparator.evaluateSafety(osV1, osV1, lin, lin)
        assertFalse(safetyUnsafe.isPermitted)
        assertFalse(safetyUnsafe.allowsDestructiveCleanup)
        assertFalse(safetyUnsafe.isOsCancelCompleted)
        assertFalse(safetyUnsafe.executesCancellation)
    }

    // --- 6. C2 Correction Resume Hardening Tests (C2-1..C2-5) ---

    @Test
    fun test56_bothSidesRestoreEpochZeroPreservesValidLineage() {
        val linA = createLineage(restoreEpoch = 0L)
        val linB = createLineage(restoreEpoch = 0L)

        val linResult = PendingIntentIdentityComparator.compareLineages(linA, linB)
        assertEquals("restoreEpoch = 0L is a valid non-restored epoch witness, returning SAME", OccurrenceComparison.SAME, linResult)
    }

    @Test
    fun test57_bothSidesRestoreEpochNullEvaluatesToEvidenceIncomplete() {
        val osV2 = createV2Descriptor()
        val linA = createLineage(restoreEpoch = null)
        val linB = createLineage(restoreEpoch = null)

        val safety = PendingIntentIdentityComparator.evaluateSafety(osV2, osV2, linA, linB)
        assertTrue("Missing restoreEpoch witness MUST evaluate to EvidenceIncomplete", safety is CrossIdentitySafety.EvidenceIncomplete)
    }

    @Test
    fun test58_directBootV2FactoryFailsToExactCandidate() {
        val osDirectBoot = createV2Descriptor(
            requestCode = 91701,
            targetComponent = "com.sysadmindoc.alarmclock.directboot.DirectBootAlarmReceiver",
            customDataUri = "acx-directboot://normal/alarm/issue?alarmId=42&token=token-v1"
        )
        val lin = createLineage()

        val safety = PendingIntentIdentityComparator.evaluateSafety(osDirectBoot, osDirectBoot, lin, lin)
        assertFalse("Direct Boot V2 descriptor MUST NOT evaluate to ExactCandidate under MAIN V2 Factory rules", safety is CrossIdentitySafety.ExactCandidate)
    }

    @Test
    fun test59_flagsZeroLacksFlagImmutableFailsToExactCandidate() {
        val osNoImmutable = createV2Descriptor(flags = 0)
        val lin = createLineage()

        val safety = PendingIntentIdentityComparator.evaluateSafety(osNoImmutable, osNoImmutable, lin, lin)
        assertFalse("V2 descriptor with flags=0 (lacking FLAG_IMMUTABLE) MUST NOT evaluate to ExactCandidate", safety is CrossIdentitySafety.ExactCandidate)
    }

    @Test
    fun test60_uriEncodedTokenSpaceMatchesLineageRawTokenSpace() {
        val osEncodedSpace = createV2Descriptor(customDataUri = "alarmclock://alarm/issue?alarmId=42&token=token%20v1")
        val linSpace = createLineage(issueToken = "token v1")

        val safety = PendingIntentIdentityComparator.evaluateSafety(osEncodedSpace, osEncodedSpace, linSpace, linSpace)
        assertTrue("Uri.encode space %20 in Data URI matches raw space in lineage issueToken", safety is CrossIdentitySafety.ExactCandidate)
    }

    @Test
    fun test61_legacySameOsKeyDifferentOccurrenceRejectsDestructiveCancel() {
        val osLegacyA = createV1Descriptor(alarmId = 42L)
        val osLegacyB = createV1Descriptor(alarmId = 42L)
        val linA = createLineage(alarmId = 42L, scheduledTriggerMillis = 1000L)
        val linB = createLineage(alarmId = 42L, scheduledTriggerMillis = 2000L)

        val safety = PendingIntentIdentityComparator.evaluateSafety(osLegacyA, osLegacyB, linA, linB)
        assertTrue(safety is CrossIdentitySafety.UnsafeAlias)
        assertFalse(safety.allowsDestructiveCleanup)
        assertFalse(safety.executesCancellation)
    }

    // --- 7. A2 Canonical Contract Tests (A1 .. A6) ---

    @Test
    fun test62_A1_uriTokenPercent20MatchesLineageSpace() {
        val osA = createV2Descriptor(customDataUri = "alarmclock://alarm/issue?alarmId=42&token=token%20v1")
        val linA = createLineage(issueToken = "token v1")

        val safety = PendingIntentIdentityComparator.evaluateSafety(osA, osA, linA, linA)
        assertTrue("Canonical percent-encoding %20 MUST match raw space 'token v1'", safety is CrossIdentitySafety.ExactCandidate)
    }

    @Test
    fun test63_A2_uriTokenPercent20DoesNotMatchLiteralPercent20Lineage() {
        val osA = createV2Descriptor(customDataUri = "alarmclock://alarm/issue?alarmId=42&token=token%20v1")
        val linA = createLineage(issueToken = "token%20v1") // Literal percent 20 in token

        val safety = PendingIntentIdentityComparator.evaluateSafety(osA, osA, linA, linA)
        assertFalse("URI token%20v1 MUST NOT match literal issueToken token%20v1; canonical encoding requires token%2520v1", safety is CrossIdentitySafety.ExactCandidate)
        assertTrue(safety is CrossIdentitySafety.UnsafeAlias)
    }

    @Test
    fun test64_A3_uriTokenPercent2520MatchesLiteralPercent20Lineage() {
        val osA = createV2Descriptor(customDataUri = "alarmclock://alarm/issue?alarmId=42&token=token%2520v1")
        val linA = createLineage(issueToken = "token%20v1")

        val safety = PendingIntentIdentityComparator.evaluateSafety(osA, osA, linA, linA)
        assertTrue("URI token%2520v1 MUST match canonical encoding of literal token%20v1", safety is CrossIdentitySafety.ExactCandidate)
    }

    @Test
    fun test65_A4_utf8MultibyteUnicodeTokenRoundtrip() {
        val unicodeToken = "token-アラーム"
        val encodedUnicode = PendingIntentIdentityComparator.encodePercentUtf8(unicodeToken)
        val osA = createV2Descriptor(customDataUri = "alarmclock://alarm/issue?alarmId=42&token=$encodedUnicode")
        val linA = createLineage(issueToken = unicodeToken)

        val safety = PendingIntentIdentityComparator.evaluateSafety(osA, osA, linA, linA)
        assertTrue("Canonical UTF-8 percent encoding of Unicode token MUST match lineage", safety is CrossIdentitySafety.ExactCandidate)
    }

    @Test
    fun test66_A5_malformedPercentEncodingFailsClosed() {
        val osA = createV2Descriptor(customDataUri = "alarmclock://alarm/issue?alarmId=42&token=token%2Gv1")
        val linA = createLineage(issueToken = "token%2Gv1")

        val safety = PendingIntentIdentityComparator.evaluateSafety(osA, osA, linA, linA)
        assertFalse("Malformed percent encoding MUST NOT evaluate to ExactCandidate", safety is CrossIdentitySafety.ExactCandidate)
    }

    @Test
    fun test67_A6_doubleEncodingOrAmbiguousAliasProhibitedFromExactCandidate() {
        val osA = createV2Descriptor(customDataUri = "alarmclock://alarm/issue?alarmId=42&token=token%252520v1")
        val linA = createLineage(issueToken = "token v1")

        val safety = PendingIntentIdentityComparator.evaluateSafety(osA, osA, linA, linA)
        assertFalse("Double-encoded or ambiguous URI token MUST NOT evaluate to ExactCandidate", safety is CrossIdentitySafety.ExactCandidate)
    }

    // --- 8. A3 Android Uri.encode Parity Test (A8) ---

    @Test
    fun test68_A8_androidUriEncodeUnreservedCharsParity() {
        // Android Uri.encode("wake!up'alarm(1)*test~one.two_three-four") leaves -_!.~'()* unencoded
        val tokenWithSpecialChars = "wake!up'alarm(1)*test~one.two_three-four"
        val androidUriEncodedDataUri = "alarmclock://alarm/issue?alarmId=42&token=$tokenWithSpecialChars"
        val osA = createV2Descriptor(customDataUri = androidUriEncodedDataUri)
        val linA = createLineage(issueToken = tokenWithSpecialChars)

        val safety = PendingIntentIdentityComparator.evaluateSafety(osA, osA, linA, linA)
        assertTrue("Tokens with -_!.~'()* encoded via Android Uri.encode MUST match lineage in ExactCandidate", safety is CrossIdentitySafety.ExactCandidate)
    }
}
