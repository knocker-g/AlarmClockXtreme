package com.sysadmindoc.alarmclock.directboot

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.sysadmindoc.alarmclock.data.local.AlarmDatabase
import com.sysadmindoc.alarmclock.data.local.entity.ScheduleAuthority
import com.sysadmindoc.alarmclock.data.local.entity.ScheduleAuthorityMeta
import com.sysadmindoc.alarmclock.data.model.Alarm
import com.sysadmindoc.alarmclock.domain.CoverageReasonCode
import com.sysadmindoc.alarmclock.domain.CoverageResult
import com.sysadmindoc.alarmclock.domain.ScheduleAuthorityBootstrapAdapter
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class DirectBootNormalProjectionCoordinatorTest {

    private lateinit var context: Context
    private lateinit var database: AlarmDatabase
    private lateinit var reader: DirectBootNormalSnapshotReader
    private lateinit var coordinator: DirectBootNormalProjectionCoordinator

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        database = Room.inMemoryDatabaseBuilder(context, AlarmDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        reader = DirectBootNormalSnapshotReader(
            database = database,
            unlockGate = { true }
        )
        coordinator = DirectBootNormalProjectionCoordinator(snapshotReader = reader)
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun ceLocked_mapsToCeLocked() = runBlocking {
        val lockedReader = mockk<DirectBootNormalSnapshotReader>()
        coEvery { lockedReader.readSnapshot() } returns DirectBootNormalSnapshotResult.CeLocked

        val lockedCoordinator = DirectBootNormalProjectionCoordinator(snapshotReader = lockedReader)
        val result = lockedCoordinator.coordinate(now = 1000L)

        assertEquals(DirectBootNormalCoordinationResult.CeLocked, result)
    }

    @Test
    fun ceReadFailure_mapsToCeReadFailure_withoutBecomingReadyEmpty() = runBlocking {
        val failingReader = mockk<DirectBootNormalSnapshotReader>()
        coEvery { failingReader.readSnapshot() } returns DirectBootNormalSnapshotResult.ReadFailure(
            "Failed to read CE snapshot from Room database"
        )

        val failingCoordinator = DirectBootNormalProjectionCoordinator(snapshotReader = failingReader)
        val result = failingCoordinator.coordinate(now = 1000L)

        assertTrue(result is DirectBootNormalCoordinationResult.CeReadFailure)
        val failure = result as DirectBootNormalCoordinationResult.CeReadFailure
        assertEquals("Failed to read CE snapshot from Room database", failure.details)
    }

    @Test
    fun validNormalSnapshot_returnsReadyWithCandidates() = runBlocking {
        val alarm = Alarm(
            id = 10L,
            hour = 7,
            minute = 0,
            isEnabled = true,
            kind = Alarm.KIND_NORMAL,
            generation = 1,
            revision = 1,
            nextTriggerTime = 5000L
        )
        val meta = ScheduleAuthorityMeta(id = 1, restoreEpoch = 1L, bootstrapState = "ENFORCED")
        val auth = ScheduleAuthority(
            alarmId = 10L,
            instanceToken = "tok-10",
            definitionRevision = ScheduleAuthorityBootstrapAdapter.computeDefinitionRevision(alarm),
            scheduleRevision = 1L,
            lifecycle = "LIVE",
            desiredOperation = "SET",
            desiredTriggerMillis = 5000L,
            reconcileState = "SETTLED",
            restoreEpoch = 1L,
            actionKey = null
        )

        database.alarmDao().insert(alarm)
        database.scheduleAuthorityDao().insertMeta(meta)
        database.scheduleAuthorityDao().insertAuthority(auth)

        val result = coordinator.coordinate(now = 1000L)

        assertTrue("Expected ReadyWithCandidates, got: $result", result is DirectBootNormalCoordinationResult.ReadyWithCandidates)
        val ready = result as DirectBootNormalCoordinationResult.ReadyWithCandidates
        assertEquals(1, ready.candidates.size)
        assertEquals(10L, ready.candidates[0].alarmId)
        assertEquals(5000L, ready.candidates[0].triggerTime)
        assertEquals("tok-10", ready.candidates[0].instanceToken)
    }

    @Test
    fun legitimatelyEmptyNormalPlan_returnsReadyEmpty() = runBlocking {
        val alarm = Alarm(
            id = 10L,
            hour = 7,
            minute = 0,
            isEnabled = false, // Disabled
            kind = Alarm.KIND_NORMAL,
            generation = 1,
            revision = 1,
            nextTriggerTime = 5000L
        )
        val meta = ScheduleAuthorityMeta(id = 1, restoreEpoch = 1L, bootstrapState = "ENFORCED")
        val auth = ScheduleAuthority(
            alarmId = 10L,
            instanceToken = "tok-10",
            definitionRevision = ScheduleAuthorityBootstrapAdapter.computeDefinitionRevision(alarm),
            scheduleRevision = 1L,
            lifecycle = "LIVE",
            desiredOperation = "CANCEL",
            desiredTriggerMillis = null,
            reconcileState = "SETTLED",
            restoreEpoch = 1L,
            actionKey = null
        )

        database.alarmDao().insert(alarm)
        database.scheduleAuthorityDao().insertMeta(meta)
        database.scheduleAuthorityDao().insertAuthority(auth)

        val result = coordinator.coordinate(now = 1000L)

        assertTrue("Expected ReadyEmpty, got: $result", result is DirectBootNormalCoordinationResult.ReadyEmpty)
        assertNotNull((result as DirectBootNormalCoordinationResult.ReadyEmpty).fingerprint)
    }

    @Test
    fun unresolvedAuthority_blocksFullPlan_returnsBlockedUnresolvedAuthority() = runBlocking {
        val alarm = Alarm(
            id = 20L,
            hour = 8,
            minute = 0,
            isEnabled = true,
            kind = Alarm.KIND_NORMAL,
            generation = 1,
            revision = 1,
            nextTriggerTime = 10000L
        )
        val meta = ScheduleAuthorityMeta(id = 1, restoreEpoch = 1L, bootstrapState = "ENFORCED")
        val auth = ScheduleAuthority(
            alarmId = 20L,
            instanceToken = "tok-20",
            definitionRevision = ScheduleAuthorityBootstrapAdapter.computeDefinitionRevision(alarm),
            scheduleRevision = 1L,
            lifecycle = "LIVE",
            desiredOperation = "SET",
            desiredTriggerMillis = 10000L,
            reconcileState = "PENDING", // Unresolved!
            restoreEpoch = 1L,
            actionKey = null
        )

        database.alarmDao().insert(alarm)
        database.scheduleAuthorityDao().insertMeta(meta)
        database.scheduleAuthorityDao().insertAuthority(auth)

        val result = coordinator.coordinate(now = 1000L)

        assertTrue(result is DirectBootNormalCoordinationResult.BlockedUnresolvedAuthority)
        val blocked = result as DirectBootNormalCoordinationResult.BlockedUnresolvedAuthority
        assertEquals(20L, blocked.alarmId)
        assertEquals("PENDING", blocked.reconcileState)
    }

    @Test
    fun invalidCoverage_mapsToBlockedInvalidCoverage() = runBlocking {
        val alarm = Alarm(
            id = 30L,
            hour = 9,
            minute = 0,
            isEnabled = true,
            kind = Alarm.KIND_NORMAL,
            generation = 1,
            revision = 1,
            nextTriggerTime = 10000L
        )
        val meta = ScheduleAuthorityMeta(id = 1, restoreEpoch = 1L, bootstrapState = "ENFORCED")
        // No authority inserted for active alarm 30L!

        database.alarmDao().insert(alarm)
        database.scheduleAuthorityDao().insertMeta(meta)

        val result = coordinator.coordinate(now = 1000L)

        assertTrue(result is DirectBootNormalCoordinationResult.BlockedInvalidCoverage)
        val blocked = result as DirectBootNormalCoordinationResult.BlockedInvalidCoverage
        assertEquals(CoverageResult.INCOMPLETE, blocked.result)
        assertEquals(CoverageReasonCode.UNCOVERED_ALARM, blocked.reasonCode)
    }

    @Test
    fun invalidEpochOrIdentity_mapsToBlockedInvalidEpochOrIdentity() = runBlocking {
        val alarm = Alarm(
            id = 40L,
            hour = 6,
            minute = 30,
            isEnabled = true,
            kind = Alarm.KIND_NORMAL,
            generation = 1,
            revision = 1,
            nextTriggerTime = 10000L
        )
        val meta = ScheduleAuthorityMeta(id = 1, restoreEpoch = 10L, bootstrapState = "ENFORCED")
        val auth = ScheduleAuthority(
            alarmId = 40L,
            instanceToken = "tok-40",
            definitionRevision = ScheduleAuthorityBootstrapAdapter.computeDefinitionRevision(alarm),
            scheduleRevision = 1L,
            lifecycle = "LIVE",
            desiredOperation = "SET",
            desiredTriggerMillis = 10000L,
            reconcileState = "SETTLED",
            restoreEpoch = 99L, // Epoch mismatch!
            actionKey = null
        )

        database.alarmDao().insert(alarm)
        database.scheduleAuthorityDao().insertMeta(meta)
        database.scheduleAuthorityDao().insertAuthority(auth)

        val result = coordinator.coordinate(now = 1000L)

        assertTrue(result is DirectBootNormalCoordinationResult.BlockedInvalidEpochOrIdentity)
    }

    @Test
    fun inconsistentSnapshot_mapsToBlockedInconsistentSnapshot() = runBlocking {
        val alarm = Alarm(
            id = 50L,
            hour = 6,
            minute = 30,
            isEnabled = true,
            kind = Alarm.KIND_NORMAL,
            generation = 1,
            revision = 1,
            nextTriggerTime = 10000L
        )
        val meta = ScheduleAuthorityMeta(id = 1, restoreEpoch = 1L, bootstrapState = "ENFORCED")
        val auth = ScheduleAuthority(
            alarmId = 50L,
            instanceToken = "tok-50",
            definitionRevision = ScheduleAuthorityBootstrapAdapter.computeDefinitionRevision(alarm),
            scheduleRevision = 1L,
            lifecycle = "LIVE",
            desiredOperation = "SET",
            desiredTriggerMillis = 99999L, // Trigger drift!
            reconcileState = "SETTLED",
            restoreEpoch = 1L,
            actionKey = null
        )

        database.alarmDao().insert(alarm)
        database.scheduleAuthorityDao().insertMeta(meta)
        database.scheduleAuthorityDao().insertAuthority(auth)

        val result = coordinator.coordinate(now = 1000L)

        assertTrue(result is DirectBootNormalCoordinationResult.BlockedInconsistentSnapshot)
    }

    @Test
    fun missingMeta_failsClosed_returnsBlockedInvalidCoverage() = runBlocking {
        val alarm = Alarm(
            id = 60L,
            hour = 7,
            minute = 0,
            isEnabled = true,
            kind = Alarm.KIND_NORMAL,
            generation = 1,
            revision = 1,
            nextTriggerTime = 10000L
        )
        database.alarmDao().insert(alarm)
        // Meta table is completely empty (0 rows)

        val result = coordinator.coordinate(now = 1000L)

        assertTrue(result is DirectBootNormalCoordinationResult.BlockedInvalidCoverage)
        val blocked = result as DirectBootNormalCoordinationResult.BlockedInvalidCoverage
        assertEquals(CoverageResult.UNINITIALIZED, blocked.result)
        assertEquals(CoverageReasonCode.META_ABSENT_AUTHORITY_EMPTY, blocked.reasonCode)
    }

    @Test
    fun duplicateMetaRows_failsClosed_returnsBlockedInvalidCoverage() = runBlocking {
        val mockReader = mockk<DirectBootNormalSnapshotReader>()
        val meta1 = ScheduleAuthorityMeta(id = 1, restoreEpoch = 1L, bootstrapState = "ENFORCED")
        val meta2 = ScheduleAuthorityMeta(id = 2, restoreEpoch = 1L, bootstrapState = "ENFORCED")

        coEvery { mockReader.readSnapshot() } returns DirectBootNormalSnapshotResult.Success(
            DirectBootNormalCeSnapshot(
                alarms = emptyList(),
                authorities = emptyList(),
                metaRows = listOf(meta1, meta2) // Duplicate meta rows!
            )
        )

        val dupCoordinator = DirectBootNormalProjectionCoordinator(snapshotReader = mockReader)
        val result = dupCoordinator.coordinate(now = 1000L)

        assertTrue(result is DirectBootNormalCoordinationResult.BlockedInvalidCoverage)
        val blocked = result as DirectBootNormalCoordinationResult.BlockedInvalidCoverage
        assertEquals(CoverageResult.INVALID, blocked.result)
        assertEquals(CoverageReasonCode.META_ID_INVALID, blocked.reasonCode)
        assertEquals("Multiple schedule_authority_meta rows detected: 2", blocked.details)
    }

    @Test
    fun quickRows_doNotBecomeCandidates() = runBlocking {
        // Quick alarm should never become a Normal candidate
        val quickAlarm = Alarm(
            id = 70L,
            hour = 8,
            minute = 15,
            isEnabled = true,
            kind = Alarm.KIND_QUICK,
            generation = 1,
            revision = 1,
            nextTriggerTime = 10000L
        )
        val meta = ScheduleAuthorityMeta(id = 1, restoreEpoch = 1L, bootstrapState = "ENFORCED")

        database.alarmDao().insert(quickAlarm)
        database.scheduleAuthorityDao().insertMeta(meta)

        val result = coordinator.coordinate(now = 1000L)

        // Only Quick alarm exists, so zero Normal alarms -> ReadyEmpty
        assertTrue("Expected ReadyEmpty, got: $result", result is DirectBootNormalCoordinationResult.ReadyEmpty)
        assertNotNull((result as DirectBootNormalCoordinationResult.ReadyEmpty).fingerprint)

        // Now add a Normal alarm
        val normalAlarm = Alarm(
            id = 71L,
            hour = 9,
            minute = 0,
            isEnabled = true,
            kind = Alarm.KIND_NORMAL,
            generation = 1,
            revision = 1,
            nextTriggerTime = 12000L
        )
        val normalAuth = ScheduleAuthority(
            alarmId = 71L,
            instanceToken = "tok-71",
            definitionRevision = ScheduleAuthorityBootstrapAdapter.computeDefinitionRevision(normalAlarm),
            scheduleRevision = 1L,
            lifecycle = "LIVE",
            desiredOperation = "SET",
            desiredTriggerMillis = 12000L,
            reconcileState = "SETTLED",
            restoreEpoch = 1L,
            actionKey = null
        )
        database.alarmDao().insert(normalAlarm)
        database.scheduleAuthorityDao().insertAuthority(normalAuth)

        val resultWithBoth = coordinator.coordinate(now = 1000L)
        assertTrue(resultWithBoth is DirectBootNormalCoordinationResult.ReadyWithCandidates)
        val ready = resultWithBoth as DirectBootNormalCoordinationResult.ReadyWithCandidates
        assertEquals(1, ready.candidates.size)
        assertEquals(71L, ready.candidates[0].alarmId)
        assertEquals(DirectBootQueueItem.KIND_NORMAL, ready.candidates[0].kind)
    }

    @Test
    fun explicitNow_isHonored() = runBlocking {
        val alarm = Alarm(
            id = 80L,
            hour = 10,
            minute = 0,
            isEnabled = true,
            kind = Alarm.KIND_NORMAL,
            generation = 1,
            revision = 1,
            nextTriggerTime = 5000L
        )
        val meta = ScheduleAuthorityMeta(id = 1, restoreEpoch = 1L, bootstrapState = "ENFORCED")
        val auth = ScheduleAuthority(
            alarmId = 80L,
            instanceToken = "tok-80",
            definitionRevision = ScheduleAuthorityBootstrapAdapter.computeDefinitionRevision(alarm),
            scheduleRevision = 1L,
            lifecycle = "LIVE",
            desiredOperation = "SET",
            desiredTriggerMillis = 5000L,
            reconcileState = "SETTLED",
            restoreEpoch = 1L,
            actionKey = null
        )

        database.alarmDao().insert(alarm)
        database.scheduleAuthorityDao().insertMeta(meta)
        database.scheduleAuthorityDao().insertAuthority(auth)

        // When now is before trigger: eligible -> ReadyWithCandidates
        val resultBefore = coordinator.coordinate(now = 4000L)
        assertTrue(resultBefore is DirectBootNormalCoordinationResult.ReadyWithCandidates)

        // When now is at or after trigger: expired -> ReadyEmpty
        val resultAfter = coordinator.coordinate(now = 5000L)
        assertTrue("Expected ReadyEmpty, got: $resultAfter", resultAfter is DirectBootNormalCoordinationResult.ReadyEmpty)
        assertNotNull((resultAfter as DirectBootNormalCoordinationResult.ReadyEmpty).fingerprint)
    }

    @Test
    fun implicitNow_isCapturedOnce() = runBlocking {
        val futureTime = System.currentTimeMillis() + 100000L
        val alarm = Alarm(
            id = 90L,
            hour = 11,
            minute = 0,
            isEnabled = true,
            kind = Alarm.KIND_NORMAL,
            generation = 1,
            revision = 1,
            nextTriggerTime = futureTime
        )
        val meta = ScheduleAuthorityMeta(id = 1, restoreEpoch = 1L, bootstrapState = "ENFORCED")
        val auth = ScheduleAuthority(
            alarmId = 90L,
            instanceToken = "tok-90",
            definitionRevision = ScheduleAuthorityBootstrapAdapter.computeDefinitionRevision(alarm),
            scheduleRevision = 1L,
            lifecycle = "LIVE",
            desiredOperation = "SET",
            desiredTriggerMillis = futureTime,
            reconcileState = "SETTLED",
            restoreEpoch = 1L,
            actionKey = null
        )

        database.alarmDao().insert(alarm)
        database.scheduleAuthorityDao().insertMeta(meta)
        database.scheduleAuthorityDao().insertAuthority(auth)

        // Implicit now calls coordinate() with System.currentTimeMillis()
        val result = coordinator.coordinate()
        assertTrue("Expected ReadyWithCandidates for future alarm, got: $result", result is DirectBootNormalCoordinationResult.ReadyWithCandidates)
    }

    @Test
    fun cancellationException_propagatesUnchanged() = runBlocking {
        val mockReader = mockk<DirectBootNormalSnapshotReader>()
        coEvery { mockReader.readSnapshot() } throws CancellationException("Coordinator job cancelled")

        val cancellingCoordinator = DirectBootNormalProjectionCoordinator(snapshotReader = mockReader)

        var thrown: Throwable? = null
        try {
            cancellingCoordinator.coordinate(now = 1000L)
        } catch (e: Throwable) {
            thrown = e
        }

        assertNotNull("CancellationException must be propagated", thrown)
        assertTrue(thrown is CancellationException)
        assertEquals("Coordinator job cancelled", thrown?.message)
    }

    @Test
    fun noStorageMutationOccurs() = runBlocking {
        val alarm = Alarm(id = 95L, hour = 7, minute = 0, isEnabled = true)
        val meta = ScheduleAuthorityMeta(id = 1, restoreEpoch = 1L, bootstrapState = "ENFORCED")
        database.alarmDao().insert(alarm)
        database.scheduleAuthorityDao().insertMeta(meta)

        val preAlarms = database.alarmDao().getAll().size
        val preMeta = database.scheduleAuthorityDao().getAllMeta().size
        val preAuth = database.scheduleAuthorityDao().getAllAuthorities().size

        coordinator.coordinate(now = 1000L)

        val postAlarms = database.alarmDao().getAll().size
        val postMeta = database.scheduleAuthorityDao().getAllMeta().size
        val postAuth = database.scheduleAuthorityDao().getAllAuthorities().size

        assertEquals(preAlarms, postAlarms)
        assertEquals(preMeta, postMeta)
        assertEquals(preAuth, postAuth)
    }

    @Test
    fun repeatedSameInputExecution_isDeterministic() = runBlocking {
        val alarm = Alarm(
            id = 99L,
            hour = 8,
            minute = 0,
            isEnabled = true,
            kind = Alarm.KIND_NORMAL,
            generation = 1,
            revision = 1,
            nextTriggerTime = 10000L
        )
        val meta = ScheduleAuthorityMeta(id = 1, restoreEpoch = 1L, bootstrapState = "ENFORCED")
        val auth = ScheduleAuthority(
            alarmId = 99L,
            instanceToken = "tok-99",
            definitionRevision = ScheduleAuthorityBootstrapAdapter.computeDefinitionRevision(alarm),
            scheduleRevision = 1L,
            lifecycle = "LIVE",
            desiredOperation = "SET",
            desiredTriggerMillis = 10000L,
            reconcileState = "SETTLED",
            restoreEpoch = 1L,
            actionKey = null
        )

        database.alarmDao().insert(alarm)
        database.scheduleAuthorityDao().insertMeta(meta)
        database.scheduleAuthorityDao().insertAuthority(auth)

        val result1 = coordinator.coordinate(now = 1000L)
        val result2 = coordinator.coordinate(now = 1000L)

        assertTrue(result1 is DirectBootNormalCoordinationResult.ReadyWithCandidates)
        assertTrue(result2 is DirectBootNormalCoordinationResult.ReadyWithCandidates)

        val c1 = (result1 as DirectBootNormalCoordinationResult.ReadyWithCandidates).candidates
        val c2 = (result2 as DirectBootNormalCoordinationResult.ReadyWithCandidates).candidates

        assertEquals(c1.size, c2.size)
        assertEquals(c1[0].alarmId, c2[0].alarmId)
        assertEquals(c1[0].instanceToken, c2[0].instanceToken)
        assertEquals(c1[0].triggerTime, c2[0].triggerTime)
    }

    @Test
    fun productionInjectConstructor_resolvesAndExecutes() = runBlocking {
        val prodReader = DirectBootNormalSnapshotReader(
            context = context,
            database = database
        )
        val prodCoordinator = DirectBootNormalProjectionCoordinator(snapshotReader = prodReader)

        val result = prodCoordinator.coordinate(now = 1000L)
        // With empty DB and unlocked context, missing meta -> BlockedInvalidCoverage
        assertTrue(result is DirectBootNormalCoordinationResult.BlockedInvalidCoverage)
    }
}
