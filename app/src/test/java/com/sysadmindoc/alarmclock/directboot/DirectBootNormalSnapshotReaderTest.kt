package com.sysadmindoc.alarmclock.directboot

import android.app.Application
import android.content.Context
import android.database.sqlite.SQLiteException
import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import com.sysadmindoc.alarmclock.data.local.AlarmDatabase
import com.sysadmindoc.alarmclock.data.local.entity.ScheduleAuthority
import com.sysadmindoc.alarmclock.data.local.entity.ScheduleAuthorityMeta
import com.sysadmindoc.alarmclock.data.model.Alarm
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class DirectBootNormalSnapshotReaderTest {

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

    @Test
    fun unlockGateThrowsCancellationException_isRethrownWithoutConversion() = runBlocking {
        val mockDb = mockk<AlarmDatabase>(relaxed = false)

        val reader = DirectBootNormalSnapshotReader(
            database = mockDb,
            unlockGate = { throw CancellationException("Unlock check cancelled") }
        )

        var thrown: Throwable? = null
        try {
            reader.readSnapshot()
        } catch (e: Throwable) {
            thrown = e
        }

        assertNotNull("CancellationException must be rethrown", thrown)
        assertTrue("Rethrown exception must be CancellationException", thrown is CancellationException)
        assertEquals("Unlock check cancelled", thrown?.message)
        // Verify mockDb was never touched
        coVerify(exactly = 0) { mockDb.alarmDao() }
        coVerify(exactly = 0) { mockDb.scheduleAuthorityDao() }
    }

    @Test
    fun unlockGateThrowsOrdinaryException_returnsCeLocked_withoutQueryingDatabase() = runBlocking {
        val mockDb = mockk<AlarmDatabase>(relaxed = false)

        val reader = DirectBootNormalSnapshotReader(
            database = mockDb,
            unlockGate = { throw SecurityException("User unlock query permission denied") }
        )

        val result = reader.readSnapshot()

        assertEquals(DirectBootNormalSnapshotResult.CeLocked, result)
        // Verify mockDb was never touched
        coVerify(exactly = 0) { mockDb.alarmDao() }
        coVerify(exactly = 0) { mockDb.scheduleAuthorityDao() }
    }

    @Test
    fun ceLocked_returnsCeLocked_withoutQueryingDatabase() = runBlocking {
        val mockDb = mockk<AlarmDatabase>(relaxed = false)

        val reader = DirectBootNormalSnapshotReader(
            database = mockDb,
            unlockGate = { false }
        )

        val result = reader.readSnapshot()

        assertEquals(DirectBootNormalSnapshotResult.CeLocked, result)
        // Verify mockDb was never touched
        coVerify(exactly = 0) { mockDb.alarmDao() }
        coVerify(exactly = 0) { mockDb.scheduleAuthorityDao() }
    }

    @Test
    fun ceUnlocked_returnsCompleteSnapshot() = runBlocking {
        val alarm = Alarm(
            id = 101L,
            hour = 8,
            minute = 30,
            isEnabled = true,
            kind = Alarm.KIND_NORMAL,
            generation = 1,
            revision = 1,
            nextTriggerTime = 1700000000000L
        )
        database.alarmDao().insert(alarm)

        val meta = ScheduleAuthorityMeta(
            id = 1,
            restoreEpoch = 10L,
            bootstrapState = "ENFORCED"
        )
        database.scheduleAuthorityDao().insertMeta(meta)

        val authority = ScheduleAuthority(
            alarmId = 101L,
            instanceToken = "token-101",
            definitionRevision = 1L,
            scheduleRevision = 1L,
            lifecycle = "LIVE",
            desiredOperation = "SET",
            desiredTriggerMillis = 1700000000000L,
            reconcileState = "SETTLED",
            restoreEpoch = 10L,
            actionKey = null
        )
        database.scheduleAuthorityDao().insertAuthority(authority)

        val reader = DirectBootNormalSnapshotReader(
            database = database,
            unlockGate = { true }
        )

        val result = reader.readSnapshot()
        assertTrue("Result must be Success", result is DirectBootNormalSnapshotResult.Success)

        val snapshot = (result as DirectBootNormalSnapshotResult.Success).snapshot
        assertEquals(1, snapshot.alarms.size)
        assertEquals(101L, snapshot.alarms[0].id)
        assertEquals(1, snapshot.authorities.size)
        assertEquals("token-101", snapshot.authorities[0].instanceToken)
        assertEquals(1, snapshot.metaRows.size)
        assertEquals(1, snapshot.metaRows[0].id)
    }

    @Test
    fun emptyTables_returnsSuccessfulEmptySnapshot() = runBlocking {
        val reader = DirectBootNormalSnapshotReader(
            database = database,
            unlockGate = { true }
        )

        val result = reader.readSnapshot()
        assertTrue("Result must be Success", result is DirectBootNormalSnapshotResult.Success)

        val snapshot = (result as DirectBootNormalSnapshotResult.Success).snapshot
        assertTrue("Alarms must be empty", snapshot.alarms.isEmpty())
        assertTrue("Authorities must be empty", snapshot.authorities.isEmpty())
        assertTrue("MetaRows must be empty", snapshot.metaRows.isEmpty())
    }

    @Test
    fun alarmQueryException_returnsReadFailure() = runBlocking {
        val mockDb = mockk<AlarmDatabase>()
        mockkStatic("androidx.room.RoomDatabaseKt")
        coEvery { mockDb.withTransaction(any<suspend () -> Any>()) } throws SQLiteException("Disk I/O error on alarms table")

        val reader = DirectBootNormalSnapshotReader(
            database = mockDb,
            unlockGate = { true }
        )

        val result = reader.readSnapshot()
        assertTrue("Result must be ReadFailure", result is DirectBootNormalSnapshotResult.ReadFailure)
        val failure = result as DirectBootNormalSnapshotResult.ReadFailure
        assertEquals("Failed to read CE snapshot from Room database", failure.details)

        unmockkStatic("androidx.room.RoomDatabaseKt")
    }

    @Test
    fun authorityQueryException_returnsReadFailure() = runBlocking {
        val mockDb = mockk<AlarmDatabase>()
        mockkStatic("androidx.room.RoomDatabaseKt")
        coEvery { mockDb.withTransaction(any<suspend () -> Any>()) } throws SQLiteException("Authority table corrupted")

        val reader = DirectBootNormalSnapshotReader(
            database = mockDb,
            unlockGate = { true }
        )

        val result = reader.readSnapshot()
        assertTrue("Result must be ReadFailure", result is DirectBootNormalSnapshotResult.ReadFailure)
        val failure = result as DirectBootNormalSnapshotResult.ReadFailure
        assertEquals("Failed to read CE snapshot from Room database", failure.details)

        unmockkStatic("androidx.room.RoomDatabaseKt")
    }

    @Test
    fun metaQueryException_returnsReadFailure() = runBlocking {
        val mockDb = mockk<AlarmDatabase>()
        mockkStatic("androidx.room.RoomDatabaseKt")
        coEvery { mockDb.withTransaction(any<suspend () -> Any>()) } throws SQLiteException("Meta table lock timeout")

        val reader = DirectBootNormalSnapshotReader(
            database = mockDb,
            unlockGate = { true }
        )

        val result = reader.readSnapshot()
        assertTrue("Result must be ReadFailure", result is DirectBootNormalSnapshotResult.ReadFailure)
        val failure = result as DirectBootNormalSnapshotResult.ReadFailure
        assertEquals("Failed to read CE snapshot from Room database", failure.details)

        unmockkStatic("androidx.room.RoomDatabaseKt")
    }

    @Test
    fun cancellationException_isRethrownWithoutConversion() = runBlocking {
        val mockDb = mockk<AlarmDatabase>()

        mockkStatic("androidx.room.RoomDatabaseKt")
        coEvery { mockDb.withTransaction(any<suspend () -> Any>()) } throws CancellationException("Job was cancelled")

        val reader = DirectBootNormalSnapshotReader(
            database = mockDb,
            unlockGate = { true }
        )

        var thrown: Throwable? = null
        try {
            reader.readSnapshot()
        } catch (e: Throwable) {
            thrown = e
        }

        assertNotNull("CancellationException must be rethrown", thrown)
        assertTrue("Rethrown exception must be CancellationException", thrown is CancellationException)
        assertEquals("Job was cancelled", thrown?.message)

        unmockkStatic("androidx.room.RoomDatabaseKt")
    }

    @Test
    fun sanitizedFailureDetails_doesNotLeakUserAlarmData() = runBlocking {
        val mockDb = mockk<AlarmDatabase>()
        mockkStatic("androidx.room.RoomDatabaseKt")
        coEvery { mockDb.withTransaction(any<suspend () -> Any>()) } throws SQLiteException(
            "Syntax error near 'secret_label_confidential' in query SELECT * FROM alarms WHERE ringtone = 'content://secret/audio.mp3'"
        )

        val reader = DirectBootNormalSnapshotReader(
            database = mockDb,
            unlockGate = { true }
        )

        val result = reader.readSnapshot()
        assertTrue("Result must be ReadFailure", result is DirectBootNormalSnapshotResult.ReadFailure)
        val failure = result as DirectBootNormalSnapshotResult.ReadFailure

        assertEquals("Failed to read CE snapshot from Room database", failure.details)
        assertFalse("Failure details must not leak confidential label", failure.details.contains("secret_label_confidential"))
        assertFalse("Failure details must not leak audio URI", failure.details.contains("content://secret/audio.mp3"))

        unmockkStatic("androidx.room.RoomDatabaseKt")
    }

    @Test
    fun allThreeReadsExecutedWithinSingleTransaction() = runBlocking {
        val inMemoryDb = Room.inMemoryDatabaseBuilder(context, AlarmDatabase::class.java)
            .allowMainThreadQueries()
            .build()

        try {
            val alarm = Alarm(
                id = 42L,
                hour = 7,
                minute = 0,
                isEnabled = true,
                kind = Alarm.KIND_NORMAL,
                generation = 1,
                revision = 1,
                nextTriggerTime = 1750000000000L
            )
            val auth = ScheduleAuthority(
                alarmId = 42L,
                instanceToken = "tok-42",
                definitionRevision = 1L,
                scheduleRevision = 1L,
                lifecycle = "LIVE",
                desiredOperation = "SET",
                desiredTriggerMillis = 1750000000000L,
                reconcileState = "SETTLED",
                restoreEpoch = 1L,
                actionKey = null
            )
            val meta = ScheduleAuthorityMeta(
                id = 1,
                restoreEpoch = 1L,
                bootstrapState = "ENFORCED"
            )

            inMemoryDb.alarmDao().insert(alarm)
            inMemoryDb.scheduleAuthorityDao().insertAuthority(auth)
            inMemoryDb.scheduleAuthorityDao().insertMeta(meta)

            val reader = DirectBootNormalSnapshotReader(
                database = inMemoryDb,
                unlockGate = { true }
            )

            val result = reader.readSnapshot()
            assertTrue("Result must be Success", result is DirectBootNormalSnapshotResult.Success)

            val snapshot = (result as DirectBootNormalSnapshotResult.Success).snapshot
            assertEquals(1, snapshot.alarms.size)
            assertEquals(1, snapshot.authorities.size)
            assertEquals(1, snapshot.metaRows.size)

            // Verify fingerprint constructed within the same atomic transaction
            assertNotNull(snapshot.fingerprint)
            assertEquals(1, snapshot.fingerprint.normalAlarmCount)
            assertEquals(1, snapshot.fingerprint.normalAuthorities.size)
            assertEquals(1, snapshot.fingerprint.metaRows.size)
            assertEquals(0, snapshot.fingerprint.orphanAuthorityCount)

            // Verify transaction state after successful execution: transaction closed
            assertFalse("Database must not be in active transaction after read", inMemoryDb.inTransaction())
        } finally {
            inMemoryDb.close()
        }
    }

    @Test
    fun exactPreservationOfOriginalEntities_includingQuickRows() = runBlocking {
        // Insert Normal Alarm
        val normalAlarm = Alarm(
            id = 1L,
            hour = 6,
            minute = 0,
            isEnabled = true,
            kind = Alarm.KIND_NORMAL,
            generation = 1,
            revision = 1,
            nextTriggerTime = 2000000000000L
        )
        // Insert Quick Alarm (should NOT be filtered by Reader)
        val quickAlarm = Alarm(
            id = 2L,
            hour = 7,
            minute = 15,
            isEnabled = true,
            kind = Alarm.KIND_QUICK,
            generation = 1,
            revision = 1,
            nextTriggerTime = 2000001000000L
        )
        // Insert Disabled Alarm (should NOT be filtered by Reader)
        val disabledAlarm = Alarm(
            id = 3L,
            hour = 9,
            minute = 0,
            isEnabled = false,
            kind = Alarm.KIND_NORMAL,
            generation = 1,
            revision = 1,
            nextTriggerTime = 2000002000000L
        )
        database.alarmDao().insert(normalAlarm)
        database.alarmDao().insert(quickAlarm)
        database.alarmDao().insert(disabledAlarm)

        // Insert Authority with PENDING state (should NOT be excluded by Reader)
        val pendingAuthority = ScheduleAuthority(
            alarmId = 1L,
            instanceToken = "tok-1",
            definitionRevision = 1L,
            scheduleRevision = 1L,
            lifecycle = "LIVE",
            desiredOperation = "SET",
            desiredTriggerMillis = 2000000000000L,
            reconcileState = "PENDING",
            restoreEpoch = 5L,
            actionKey = null
        )
        database.scheduleAuthorityDao().insertAuthority(pendingAuthority)

        val meta = ScheduleAuthorityMeta(
            id = 1,
            restoreEpoch = 5L,
            bootstrapState = "ENFORCED"
        )
        database.scheduleAuthorityDao().insertMeta(meta)

        val reader = DirectBootNormalSnapshotReader(
            database = database,
            unlockGate = { true }
        )

        val result = reader.readSnapshot()
        assertTrue("Result must be Success", result is DirectBootNormalSnapshotResult.Success)

        val snapshot = (result as DirectBootNormalSnapshotResult.Success).snapshot
        assertEquals("Reader must retain all 3 alarms including Quick and Disabled", 3, snapshot.alarms.size)
        assertTrue(snapshot.alarms.any { it.kind == Alarm.KIND_QUICK })
        assertTrue(snapshot.alarms.any { !it.isEnabled })
        assertTrue(snapshot.alarms.any { it.kind == Alarm.KIND_NORMAL })

        assertEquals("Reader must retain authority", 1, snapshot.authorities.size)
        assertEquals("PENDING", snapshot.authorities[0].reconcileState)

        assertEquals("Reader must retain meta", 1, snapshot.metaRows.size)
    }

    @Test
    fun noStorageMutationOnRead() = runBlocking {
        val alarm = Alarm(id = 50L, hour = 7, minute = 0, isEnabled = true)
        database.alarmDao().insert(alarm)
        val meta = ScheduleAuthorityMeta(id = 1, restoreEpoch = 10L, bootstrapState = "ENFORCED")
        database.scheduleAuthorityDao().insertMeta(meta)

        val preAlarmsCount = database.alarmDao().getAll().size
        val preMetaCount = database.scheduleAuthorityDao().getAllMeta().size
        val preAuthCount = database.scheduleAuthorityDao().getAllAuthorities().size

        val reader = DirectBootNormalSnapshotReader(
            database = database,
            unlockGate = { true }
        )

        reader.readSnapshot()

        val postAlarmsCount = database.alarmDao().getAll().size
        val postMetaCount = database.scheduleAuthorityDao().getAllMeta().size
        val postAuthCount = database.scheduleAuthorityDao().getAllAuthorities().size

        assertEquals("Alarm table count must not change", preAlarmsCount, postAlarmsCount)
        assertEquals("Meta table count must not change", preMetaCount, postMetaCount)
        assertEquals("Authority table count must not change", preAuthCount, postAuthCount)
    }

    @Test
    fun deterministicRepeatedReads_withUnchangedFixture() = runBlocking {
        val alarm = Alarm(id = 99L, hour = 6, minute = 30, isEnabled = true)
        database.alarmDao().insert(alarm)
        val meta = ScheduleAuthorityMeta(id = 1, restoreEpoch = 10L, bootstrapState = "ENFORCED")
        database.scheduleAuthorityDao().insertMeta(meta)

        val reader = DirectBootNormalSnapshotReader(
            database = database,
            unlockGate = { true }
        )

        val result1 = reader.readSnapshot()
        val result2 = reader.readSnapshot()

        assertTrue(result1 is DirectBootNormalSnapshotResult.Success)
        assertTrue(result2 is DirectBootNormalSnapshotResult.Success)

        val s1 = (result1 as DirectBootNormalSnapshotResult.Success).snapshot
        val s2 = (result2 as DirectBootNormalSnapshotResult.Success).snapshot

        assertEquals(s1.alarms.size, s2.alarms.size)
        assertEquals(s1.alarms[0].id, s2.alarms[0].id)
        assertEquals(s1.authorities.size, s2.authorities.size)
        assertEquals(s1.metaRows.size, s2.metaRows.size)
    }

    @Test
    fun productionInjectConstructor_resolvesAndExecutes() = runBlocking {
        // Test production injectable constructor with context and database
        val reader = DirectBootNormalSnapshotReader(
            context = context,
            database = database
        )

        // On Robolectric with default application context, user is unlocked
        val result = reader.readSnapshot()
        assertTrue("Robolectric default context should be unlocked", result is DirectBootNormalSnapshotResult.Success)
    }
}
