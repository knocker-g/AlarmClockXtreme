package com.sysadmindoc.alarmclock.directboot

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.sysadmindoc.alarmclock.data.local.AlarmDatabase
import com.sysadmindoc.alarmclock.data.local.entity.ScheduleAuthority
import com.sysadmindoc.alarmclock.data.local.entity.ScheduleAuthorityMeta
import com.sysadmindoc.alarmclock.data.model.Alarm
import com.sysadmindoc.alarmclock.domain.ScheduleAuthorityBootstrapAdapter
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Comprehensive focused test suite for [CeSnapshotFingerprint] and its integration
 * across [DirectBootNormalSnapshotReader] and [DirectBootNormalProjectionCoordinator].
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class CeSnapshotFingerprintTest {

    private lateinit var context: Context
    private lateinit var database: AlarmDatabase

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        database = Room.inMemoryDatabaseBuilder(context, AlarmDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() {
        database.close()
    }

    private fun createBaseNormalAlarm(
        id: Long = 1L,
        isEnabled: Boolean = true,
        triggerTime: Long = 10000L,
        generation: Int = 1,
        revision: Int = 1
    ): Alarm {
        return Alarm(
            id = id,
            hour = 7,
            minute = 0,
            isEnabled = isEnabled,
            nextTriggerTime = triggerTime,
            generation = generation,
            revision = revision,
            kind = Alarm.KIND_NORMAL,
            scheduleKind = Alarm.SCHEDULE_KIND_ONE_TIME,
            ringtoneUri = "", // Device default -> playDefaultSound = true
            vibrationEnabled = true,
            timezonePolicy = Alarm.TIMEZONE_POLICY_LOCAL,
            fixedTimezoneId = ""
        )
    }

    private fun createBaseAuthority(
        alarmId: Long = 1L,
        instanceToken: String = "token_1",
        definitionRevision: Long = 1L,
        scheduleRevision: Long = 1L,
        lifecycle: String = "LIVE",
        desiredOperation: String = "SET",
        desiredTriggerMillis: Long? = 10000L,
        reconcileState: String = "SETTLED",
        restoreEpoch: Long = 1L
    ): ScheduleAuthority {
        return ScheduleAuthority(
            alarmId = alarmId,
            instanceToken = instanceToken,
            definitionRevision = definitionRevision,
            scheduleRevision = scheduleRevision,
            lifecycle = lifecycle,
            desiredOperation = desiredOperation,
            desiredTriggerMillis = desiredTriggerMillis,
            reconcileState = reconcileState,
            restoreEpoch = restoreEpoch,
            actionKey = null
        )
    }

    private fun createBaseMeta(
        id: Int = 1,
        restoreEpoch: Long = 1L,
        bootstrapState: String = "ENFORCED"
    ): ScheduleAuthorityMeta {
        return ScheduleAuthorityMeta(
            id = id,
            restoreEpoch = restoreEpoch,
            bootstrapState = bootstrapState
        )
    }

    // 1. Identical snapshot gives identical fingerprint
    @Test
    fun scenario1_identicalSnapshotGivesIdenticalFingerprint() {
        val alarm = createBaseNormalAlarm()
        val auth = createBaseAuthority()
        val meta = createBaseMeta()

        val fp1 = CeSnapshotFingerprint.create(listOf(alarm), listOf(auth), listOf(meta))
        val fp2 = CeSnapshotFingerprint.create(listOf(alarm), listOf(auth), listOf(meta))

        assertEquals("Identical inputs must yield identical fingerprints", fp1, fp2)
        assertEquals("HashCodes must be identical", fp1.hashCode(), fp2.hashCode())
    }

    // 2. Alarm creation changes fingerprint
    @Test
    fun scenario2_alarmCreationChangesFingerprint() {
        val alarm1 = createBaseNormalAlarm(id = 1L)
        val auth1 = createBaseAuthority(alarmId = 1L)
        val meta = createBaseMeta()

        val fpBefore = CeSnapshotFingerprint.create(listOf(alarm1), listOf(auth1), listOf(meta))

        val alarm2 = createBaseNormalAlarm(id = 2L, triggerTime = 12000L)
        val auth2 = createBaseAuthority(alarmId = 2L, desiredTriggerMillis = 12000L)

        val fpAfter = CeSnapshotFingerprint.create(listOf(alarm1, alarm2), listOf(auth1, auth2), listOf(meta))

        assertNotEquals("Alarm creation must change fingerprint", fpBefore, fpAfter)
        assertEquals(1, fpBefore.normalAlarmCount)
        assertEquals(2, fpAfter.normalAlarmCount)
    }

    // 3. Alarm deletion changes fingerprint
    @Test
    fun scenario3_alarmDeletionChangesFingerprint() {
        val alarm1 = createBaseNormalAlarm(id = 1L)
        val auth1 = createBaseAuthority(alarmId = 1L)
        val alarm2 = createBaseNormalAlarm(id = 2L)
        val auth2 = createBaseAuthority(alarmId = 2L)
        val meta = createBaseMeta()

        val fpBefore = CeSnapshotFingerprint.create(listOf(alarm1, alarm2), listOf(auth1, auth2), listOf(meta))
        val fpAfter = CeSnapshotFingerprint.create(listOf(alarm1), listOf(auth1), listOf(meta))

        assertNotEquals("Alarm deletion must change fingerprint", fpBefore, fpAfter)
        assertEquals(2, fpBefore.normalAlarmCount)
        assertEquals(1, fpAfter.normalAlarmCount)
    }

    // 4. Alarm disable changes fingerprint
    @Test
    fun scenario4_alarmDisableChangesFingerprint() {
        val alarmEnabled = createBaseNormalAlarm(isEnabled = true)
        val auth = createBaseAuthority()
        val meta = createBaseMeta()

        val fpEnabled = CeSnapshotFingerprint.create(listOf(alarmEnabled), listOf(auth), listOf(meta))

        val alarmDisabled = alarmEnabled.copy(isEnabled = false)
        val fpDisabled = CeSnapshotFingerprint.create(listOf(alarmDisabled), listOf(auth), listOf(meta))

        assertNotEquals("Alarm toggle to disabled must change fingerprint", fpEnabled, fpDisabled)
        assertEquals(1, fpEnabled.enabledNormalAlarmCount)
        assertEquals(0, fpDisabled.enabledNormalAlarmCount)
    }

    // 5. Same-count Alarm identity replacement changes fingerprint
    @Test
    fun scenario5_sameCountAlarmIdentityReplacementChangesFingerprint() {
        val alarm1 = createBaseNormalAlarm(id = 10L)
        val auth1 = createBaseAuthority(alarmId = 10L)
        val meta = createBaseMeta()

        val fp1 = CeSnapshotFingerprint.create(listOf(alarm1), listOf(auth1), listOf(meta))

        // Replace alarm 10 with alarm 20 (both count = 1)
        val alarm2 = createBaseNormalAlarm(id = 20L)
        val auth2 = createBaseAuthority(alarmId = 20L)

        val fp2 = CeSnapshotFingerprint.create(listOf(alarm2), listOf(auth2), listOf(meta))

        assertNotEquals("Same-count alarm replacement must change fingerprint", fp1, fp2)
        assertEquals(1, fp1.normalAlarmCount)
        assertEquals(1, fp2.normalAlarmCount)
        assertEquals(10L, fp1.normalAlarms[0].alarmId)
        assertEquals(20L, fp2.normalAlarms[0].alarmId)
    }

    // 6. Same-generation schedule revision change
    @Test
    fun scenario6_sameGenerationScheduleRevisionChange() {
        val alarm = createBaseNormalAlarm(generation = 1)
        val authRev1 = createBaseAuthority(scheduleRevision = 1L)
        val meta = createBaseMeta()

        val fp1 = CeSnapshotFingerprint.create(listOf(alarm), listOf(authRev1), listOf(meta))

        // Schedule revision increments without generation change
        val authRev2 = authRev1.copy(scheduleRevision = 2L)
        val fp2 = CeSnapshotFingerprint.create(listOf(alarm), listOf(authRev2), listOf(meta))

        assertNotEquals("scheduleRevision change within same generation must change fingerprint", fp1, fp2)
        assertEquals(1L, fp1.normalAuthorities[0].scheduleRevision)
        assertEquals(2L, fp2.normalAuthorities[0].scheduleRevision)
    }

    // 7. Definition revision change
    @Test
    fun scenario7_definitionRevisionChange() {
        val alarm = createBaseNormalAlarm()
        val authDef1 = createBaseAuthority(definitionRevision = 5L)
        val meta = createBaseMeta()

        val fp1 = CeSnapshotFingerprint.create(listOf(alarm), listOf(authDef1), listOf(meta))

        val authDef2 = authDef1.copy(definitionRevision = 6L)
        val fp2 = CeSnapshotFingerprint.create(listOf(alarm), listOf(authDef2), listOf(meta))

        assertNotEquals("definitionRevision change must change fingerprint", fp1, fp2)
    }

    // 8. Trigger-time change
    @Test
    fun scenario8_triggerTimeChange() {
        val alarm1 = createBaseNormalAlarm(triggerTime = 5000L)
        val auth1 = createBaseAuthority(desiredTriggerMillis = 5000L)
        val meta = createBaseMeta()

        val fp1 = CeSnapshotFingerprint.create(listOf(alarm1), listOf(auth1), listOf(meta))

        val alarm2 = alarm1.copy(nextTriggerTime = 6000L)
        val auth2 = auth1.copy(desiredTriggerMillis = 6000L)
        val fp2 = CeSnapshotFingerprint.create(listOf(alarm2), listOf(auth2), listOf(meta))

        assertNotEquals("triggerTime change must change fingerprint", fp1, fp2)
    }

    // 9. Restore epoch change
    @Test
    fun scenario9_restoreEpochChange() {
        val alarm = createBaseNormalAlarm()
        val authEpoch1 = createBaseAuthority(restoreEpoch = 1L)
        val metaEpoch1 = createBaseMeta(restoreEpoch = 1L)

        val fp1 = CeSnapshotFingerprint.create(listOf(alarm), listOf(authEpoch1), listOf(metaEpoch1))

        val authEpoch2 = authEpoch1.copy(restoreEpoch = 2L)
        val metaEpoch2 = metaEpoch1.copy(restoreEpoch = 2L)
        val fp2 = CeSnapshotFingerprint.create(listOf(alarm), listOf(authEpoch2), listOf(metaEpoch2))

        assertNotEquals("restoreEpoch change must change fingerprint", fp1, fp2)
        assertEquals(1L, fp1.restoreEpoch)
        assertEquals(2L, fp2.restoreEpoch)
    }

    // 10. Authority instance token change
    @Test
    fun scenario10_authorityInstanceTokenChange() {
        val alarm = createBaseNormalAlarm()
        val authTok1 = createBaseAuthority(instanceToken = "token_alpha")
        val meta = createBaseMeta()

        val fp1 = CeSnapshotFingerprint.create(listOf(alarm), listOf(authTok1), listOf(meta))

        val authTok2 = authTok1.copy(instanceToken = "token_beta")
        val fp2 = CeSnapshotFingerprint.create(listOf(alarm), listOf(authTok2), listOf(meta))

        assertNotEquals("instanceToken change must change fingerprint", fp1, fp2)
    }

    // 11. Lifecycle/reconcile-state change
    @Test
    fun scenario11_lifecycleAndReconcileStateChange() {
        val alarm = createBaseNormalAlarm()
        val authSettled = createBaseAuthority(reconcileState = "SETTLED", lifecycle = "LIVE")
        val meta = createBaseMeta()

        val fp1 = CeSnapshotFingerprint.create(listOf(alarm), listOf(authSettled), listOf(meta))

        val authReconciling = authSettled.copy(reconcileState = "RECONCILING")
        val fp2 = CeSnapshotFingerprint.create(listOf(alarm), listOf(authReconciling), listOf(meta))

        assertNotEquals("reconcileState transition must change fingerprint", fp1, fp2)

        val authDisabled = authSettled.copy(lifecycle = "DISABLED")
        val fp3 = CeSnapshotFingerprint.create(listOf(alarm), listOf(authDisabled), listOf(meta))

        assertNotEquals("lifecycle transition must change fingerprint", fp1, fp3)
    }

    // 12. ReadyEmpty fingerprint
    @Test
    fun scenario12_readyEmptyFingerprint() {
        val disabledAlarm = createBaseNormalAlarm(isEnabled = false)
        val auth = createBaseAuthority(desiredOperation = "CANCEL", desiredTriggerMillis = null)
        val meta = createBaseMeta()

        val fp = CeSnapshotFingerprint.create(listOf(disabledAlarm), listOf(auth), listOf(meta))

        assertEquals(1, fp.normalAlarmCount)
        assertEquals(0, fp.enabledNormalAlarmCount)
        assertFalse(fp.normalAlarms[0].isEnabled)
    }

    // 13. More than five Normal alarms
    @Test
    fun scenario13_moreThanFiveNormalAlarms_fullCoverageInFingerprint() {
        val alarms = (1L..8L).map { id ->
            createBaseNormalAlarm(id = id, triggerTime = id * 10000L)
        }
        val authorities = (1L..8L).map { id ->
            createBaseAuthority(alarmId = id, desiredTriggerMillis = id * 10000L)
        }
        val meta = createBaseMeta()

        val fp = CeSnapshotFingerprint.create(alarms, authorities, listOf(meta))

        assertEquals("Fingerprint must contain all 8 Normal alarms, exceeding 5 capacity", 8, fp.normalAlarmCount)
        assertEquals(8, fp.enabledNormalAlarmCount)
        assertEquals(8, fp.normalAuthorities.size)

        // Verify sorted ordering by alarmId
        for (i in 0 until 8) {
            assertEquals((i + 1).toLong(), fp.normalAlarms[i].alarmId)
            assertEquals((i + 1).toLong(), fp.normalAuthorities[i].alarmId)
        }
    }

    // 14. Quick rows excluded from Normal projection
    @Test
    fun scenario14_quickRowsExcludedFromNormalFingerprint() {
        val normalAlarm = createBaseNormalAlarm(id = 1L)
        val normalAuth = createBaseAuthority(alarmId = 1L)

        val quickAlarm = Alarm(
            id = 99L,
            hour = 8,
            minute = 30,
            isEnabled = true,
            kind = Alarm.KIND_QUICK,
            quickTriggerEpochMs = 15000L
        )
        val meta = createBaseMeta()

        val fp = CeSnapshotFingerprint.create(listOf(normalAlarm, quickAlarm), listOf(normalAuth), listOf(meta))

        assertEquals("Quick alarm must be excluded from Normal fingerprint", 1, fp.normalAlarmCount)
        assertEquals(1L, fp.normalAlarms[0].alarmId)
    }

    // 15. Metadata missing/duplicated remains blocked in Coordinator
    @Test
    fun scenario15_metadataMissingOrDuplicatedRemainsBlockedInCoordinator() = runBlocking {
        val alarm = createBaseNormalAlarm()
        val auth = createBaseAuthority()

        database.alarmDao().insert(alarm)
        database.scheduleAuthorityDao().insertAuthority(auth)
        // Meta omitted -> 0 meta rows

        val reader = DirectBootNormalSnapshotReader(
            context = context,
            database = database
        )
        val coordinator = DirectBootNormalProjectionCoordinator(reader)

        val result0Meta = coordinator.coordinate(now = 1000L)
        assertTrue(
            "Missing meta must remain blocked as InvalidCoverage",
            result0Meta is DirectBootNormalCoordinationResult.BlockedInvalidCoverage
        )

        // Insert duplicate meta rows
        database.scheduleAuthorityDao().insertMeta(createBaseMeta(id = 1))
        database.scheduleAuthorityDao().insertMeta(createBaseMeta(id = 2))

        val result2Meta = coordinator.coordinate(now = 1000L)
        assertTrue(
            "Duplicated meta must remain blocked as BlockedInvalidCoverage",
            result2Meta is DirectBootNormalCoordinationResult.BlockedInvalidCoverage
        )
    }

    // 16. Reader CE locked/failure behavior unchanged
    @Test
    fun scenario16_readerCeLockedAndFailureBehaviorUnchanged() = runBlocking {
        val readerLocked = DirectBootNormalSnapshotReader(
            database = database,
            unlockGate = { false }
        )
        val resultLocked = readerLocked.readSnapshot()
        assertEquals(DirectBootNormalSnapshotResult.CeLocked, resultLocked)

        val mockDb = mockk<AlarmDatabase>(relaxed = false)
        val readerFailure = DirectBootNormalSnapshotReader(
            database = mockDb,
            unlockGate = { true }
        )
        val resultFailure = readerFailure.readSnapshot()
        assertTrue(resultFailure is DirectBootNormalSnapshotResult.ReadFailure)
    }

    // 17. Cancellation propagation
    @Test
    fun scenario17_cancellationPropagation() = runBlocking {
        val reader = DirectBootNormalSnapshotReader(
            database = database,
            unlockGate = { throw CancellationException("Test cancellation") }
        )

        var thrown: Throwable? = null
        try {
            reader.readSnapshot()
        } catch (e: Throwable) {
            thrown = e
        }

        assertNotNull(thrown)
        assertTrue("CancellationException must propagate unchanged", thrown is CancellationException)
        assertEquals("Test cancellation", thrown?.message)
    }

    // 18. Deterministic Coordinator output
    @Test
    fun scenario18_deterministicCoordinatorOutput() = runBlocking {
        val alarm = createBaseNormalAlarm(id = 10L, triggerTime = 50000L)
        val defRev = ScheduleAuthorityBootstrapAdapter.computeDefinitionRevision(alarm)
        val auth = createBaseAuthority(
            alarmId = 10L,
            definitionRevision = defRev,
            desiredTriggerMillis = 50000L
        )
        val meta = createBaseMeta()

        database.alarmDao().insert(alarm)
        database.scheduleAuthorityDao().insertAuthority(auth)
        database.scheduleAuthorityDao().insertMeta(meta)

        val reader = DirectBootNormalSnapshotReader(context, database)
        val coordinator = DirectBootNormalProjectionCoordinator(reader)

        val result = coordinator.coordinate(now = 1000L)
        assertTrue(result is DirectBootNormalCoordinationResult.ReadyWithCandidates)

        val ready = result as DirectBootNormalCoordinationResult.ReadyWithCandidates
        assertEquals(1, ready.candidates.size)
        assertEquals(10L, ready.candidates[0].alarmId)

        // Verify fingerprint is attached to coordination outcome
        assertNotNull(ready.fingerprint)
        assertEquals(1, ready.fingerprint.normalAlarmCount)
        assertEquals(1, ready.fingerprint.enabledNormalAlarmCount)
        assertEquals(10L, ready.fingerprint.normalAlarms[0].alarmId)
        assertEquals(1L, ready.fingerprint.restoreEpoch)
        assertEquals("ENFORCED", ready.fingerprint.bootstrapState)

        // Repeat coordination and assert deterministic equality
        val resultRepeat = coordinator.coordinate(now = 1000L)
        assertTrue(resultRepeat is DirectBootNormalCoordinationResult.ReadyWithCandidates)
        val readyRepeat = resultRepeat as DirectBootNormalCoordinationResult.ReadyWithCandidates
        assertEquals("Subsequent coordination passes must yield identical fingerprint", ready.fingerprint, readyRepeat.fingerprint)
    }

    // 19. Mutation resistance: caller modification & unmodifiable lists
    @Test
    fun scenario19_mutationResistance_callerModificationsDoNotAffectFingerprint() {
        val alarm = createBaseNormalAlarm(id = 1L)
        val auth = createBaseAuthority(alarmId = 1L)
        val meta = createBaseMeta()

        val mutableAlarms = mutableListOf(alarm)
        val mutableAuths = mutableListOf(auth)
        val mutableMeta = mutableListOf(meta)

        val fp = CeSnapshotFingerprint.create(mutableAlarms, mutableAuths, mutableMeta)
        assertEquals(1, fp.normalAlarmCount)
        assertEquals(1, fp.normalAuthorities.size)
        assertEquals(1, fp.metaRows.size)

        // Mutate caller collections
        mutableAlarms.add(createBaseNormalAlarm(id = 2L))
        mutableAuths.add(createBaseAuthority(alarmId = 2L))
        mutableMeta.clear()

        // Verify fingerprint internal state is unchanged
        assertEquals("Fingerprint must not reflect caller's list additions", 1, fp.normalAlarmCount)
        assertEquals("Fingerprint must not reflect caller's list additions", 1, fp.normalAuthorities.size)
        assertEquals("Fingerprint must not reflect caller's list clear", 1, fp.metaRows.size)

        // Verify attempts to mutate fingerprint collections throw UnsupportedOperationException
        try {
            @Suppress("UNCHECKED_CAST")
            (fp.normalAlarms as MutableList<CeSnapshotFingerprint.NormalAlarmFact>).clear()
            org.junit.Assert.fail("Mutating normalAlarms must throw UnsupportedOperationException")
        } catch (_: UnsupportedOperationException) {
            // expected
        }

        try {
            @Suppress("UNCHECKED_CAST")
            (fp.normalAuthorities as MutableList<CeSnapshotFingerprint.NormalAuthorityFact>).clear()
            org.junit.Assert.fail("Mutating normalAuthorities must throw UnsupportedOperationException")
        } catch (_: UnsupportedOperationException) {
            // expected
        }

        try {
            @Suppress("UNCHECKED_CAST")
            (fp.orphanAuthorities as MutableList<CeSnapshotFingerprint.NormalAuthorityFact>).clear()
            org.junit.Assert.fail("Mutating orphanAuthorities must throw UnsupportedOperationException")
        } catch (_: UnsupportedOperationException) {
            // expected
        }

        try {
            @Suppress("UNCHECKED_CAST")
            (fp.metaRows as MutableList<CeSnapshotFingerprint.MetaFact>).clear()
            org.junit.Assert.fail("Mutating metaRows must throw UnsupportedOperationException")
        } catch (_: UnsupportedOperationException) {
            // expected
        }
    }

    // 20. Authority classification: orphan authorities are preserved and alter fingerprint
    @Test
    fun scenario20_orphanAuthorityWithUnknownOwnership_isNotSilentlyDiscarded_altersFingerprint() {
        val normalAlarm = createBaseNormalAlarm(id = 1L)
        val normalAuth = createBaseAuthority(alarmId = 1L)
        val meta = createBaseMeta()

        val fpBase = CeSnapshotFingerprint.create(
            listOf(normalAlarm),
            listOf(normalAuth),
            listOf(meta)
        )
        assertEquals(1, fpBase.normalAuthorities.size)
        assertEquals(0, fpBase.orphanAuthorityCount)

        // Snapshot with orphan authority whose alarmId matches no alarm
        val orphanAuth = createBaseAuthority(alarmId = 999L)
        val fpWithOrphan = CeSnapshotFingerprint.create(
            listOf(normalAlarm),
            listOf(normalAuth, orphanAuth),
            listOf(meta)
        )

        assertEquals("Normal authorities must only contain mapped Normal alarms", 1, fpWithOrphan.normalAuthorities.size)
        assertEquals("Orphan authority must be captured in orphanAuthorities", 1, fpWithOrphan.orphanAuthorityCount)
        assertEquals(999L, fpWithOrphan.orphanAuthorities[0].alarmId)
        assertNotEquals("Orphan authority must alter fingerprint; cannot be silently discarded", fpBase, fpWithOrphan)

        // Snapshot with Quick alarm and associated Quick authority
        val quickAlarm = Alarm(
            id = 55L,
            hour = 8,
            minute = 0,
            isEnabled = true,
            kind = Alarm.KIND_QUICK
        )
        val quickAuth = createBaseAuthority(alarmId = 55L)

        val fpWithQuick = CeSnapshotFingerprint.create(
            listOf(normalAlarm, quickAlarm),
            listOf(normalAuth, quickAuth),
            listOf(meta)
        )

        // Quick alarm and its authority must not be in Normal or orphan facts
        assertEquals("Normal alarms must not include Quick alarm", 1, fpWithQuick.normalAlarmCount)
        assertEquals("Normal authorities must not include Quick authority", 1, fpWithQuick.normalAuthorities.size)
        assertEquals("Orphan authorities must not include Quick authority", 0, fpWithQuick.orphanAuthorityCount)
        assertEquals("Quick alarm/authority presence must not alter Normal fingerprint", fpBase, fpWithQuick)
    }

    // 21. No public copy() method exists to bypass encapsulation
    @Test
    fun scenario21_noPublicCopyMethod_existsOnFingerprintClass() {
        val publicMethods = CeSnapshotFingerprint::class.java.methods
        val declaredMethods = CeSnapshotFingerprint::class.java.declaredMethods

        val publicCopy = publicMethods.filter { it.name == "copy" }
        val declaredCopy = declaredMethods.filter { it.name == "copy" }

        assertTrue("CeSnapshotFingerprint must not expose public copy() method", publicCopy.isEmpty())
        assertTrue("CeSnapshotFingerprint must not have synthetic copy() method", declaredCopy.isEmpty())
    }

    // 22. Structural equals, hashCode, and toString privacy invariants
    @Test
    fun scenario22_structuralEqualsAndHashCodeAndPrivacy() {
        val alarm1 = createBaseNormalAlarm(id = 1L)
        val auth1 = createBaseAuthority(alarmId = 1L)
        val meta1 = createBaseMeta(id = 1)

        val fpA = CeSnapshotFingerprint.create(listOf(alarm1), listOf(auth1), listOf(meta1))
        val fpB = CeSnapshotFingerprint.create(listOf(alarm1), listOf(auth1), listOf(meta1))

        // Reflexive, symmetric, transitive
        assertEquals(fpA, fpA)
        assertEquals(fpA, fpB)
        assertEquals(fpB, fpA)
        assertEquals(fpA.hashCode(), fpB.hashCode())

        // Inequality across different facts
        val alarm2 = createBaseNormalAlarm(id = 2L)
        val fpDiffAlarm = CeSnapshotFingerprint.create(listOf(alarm2), listOf(auth1), listOf(meta1))
        assertNotEquals(fpA, fpDiffAlarm)

        // Null and different class
        assertFalse(fpA.equals(null))
        assertFalse(fpA.equals("some string"))

        // toString privacy: contains architectural metadata counts but no sensitive labels, URIs, alarm IDs, or instance tokens
        val str = fpA.toString()
        assertTrue(str.startsWith("CeSnapshotFingerprint("))
        assertTrue(str.contains("restoreEpoch=1"))
        assertTrue(str.contains("bootstrapState=ENFORCED"))
        assertTrue(str.contains("normalAlarmCount=1"))
        assertTrue(str.contains("normalAuthorityCount=1"))
        assertFalse("toString must not leak alarm IDs", str.contains("alarmId=1"))
        assertFalse("toString must not leak instance tokens", str.contains("tok-1"))
        assertFalse("toString must not leak facts", str.contains("NormalAlarmFact"))
        assertFalse("toString must not leak facts", str.contains("NormalAuthorityFact"))
        assertFalse("toString must not leak secrets", str.contains("secret"))
        assertFalse("toString must not leak URIs", str.contains("content://"))
    }

    // 23. Fingerprint remains stable after caller source mutations
    @Test
    fun scenario23_fingerprintRemainsStableAfterCallerSourceMutations() {
        val alarm = createBaseNormalAlarm(id = 1L, isEnabled = true, triggerTime = 10000L)
        val auth = createBaseAuthority(alarmId = 1L, instanceToken = "tok-1")
        val meta = createBaseMeta(id = 1, restoreEpoch = 1L)

        val mutableAlarms = mutableListOf(alarm)
        val mutableAuths = mutableListOf(auth)
        val mutableMeta = mutableListOf(meta)

        val fp = CeSnapshotFingerprint.create(mutableAlarms, mutableAuths, mutableMeta)
        val originalHashCode = fp.hashCode()

        // Mutate caller lists
        mutableAlarms.clear()
        mutableAuths.clear()
        mutableMeta.clear()

        // Fingerprint must remain completely stable
        assertEquals(1, fp.normalAlarmCount)
        assertEquals(1, fp.normalAuthorities.size)
        assertEquals(1, fp.metaRows.size)
        assertEquals(0, fp.orphanAuthorityCount)
        assertEquals(originalHashCode, fp.hashCode())
        assertEquals(10000L, fp.normalAlarms[0].triggerTime)
        assertTrue(fp.normalAlarms[0].isEnabled)
    }
}
