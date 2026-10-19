package com.sysadmindoc.alarmclock.data.local

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.sysadmindoc.alarmclock.data.local.entity.ScheduleAuthority
import com.sysadmindoc.alarmclock.data.local.entity.ScheduleAuthorityMeta
import com.sysadmindoc.alarmclock.data.model.Alarm
import com.sysadmindoc.alarmclock.domain.CoverageResult
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class RoomAuthorityEvidenceReaderTest {

    private lateinit var context: Context
    private lateinit var database: AlarmDatabase
    private lateinit var snapshotReader: AuthoritySnapshotReader
    private lateinit var roomEvidenceReader: RoomAuthorityEvidenceReader

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        database = Room.inMemoryDatabaseBuilder(context, AlarmDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        snapshotReader = AuthoritySnapshotReader(database)
        roomEvidenceReader = RoomAuthorityEvidenceReader(snapshotReader)
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun `readRoomEvidence with empty database returns UNINITIALIZED coverage report`() = runBlocking {
        val result = roomEvidenceReader.readRoomEvidence()
        assertTrue(result is RoomEvidenceReadResult.Success)
        val success = result as RoomEvidenceReadResult.Success

        assertNull(success.meta)
        assertTrue(success.owners.isEmpty())
        assertEquals(CoverageResult.UNINITIALIZED, success.report.result)
    }

    @Test
    fun `readRoomEvidence with populated v28 database maps meta and owner descriptors`() = runBlocking {
        val alarm = Alarm(id = 50L, hour = 7, minute = 30, isEnabled = true)
        database.alarmDao().insert(alarm)

        val meta = ScheduleAuthorityMeta(id = 1, restoreEpoch = 100L, bootstrapState = "ENFORCED")
        database.scheduleAuthorityDao().insertMeta(meta)

        val authority = ScheduleAuthority(
            alarmId = 50L,
            instanceToken = "token-room-50",
            definitionRevision = 1L,
            scheduleRevision = 1L,
            lifecycle = "LIVE",
            desiredOperation = "SET",
            desiredTriggerMillis = 1000L,
            reconcileState = "SETTLED",
            restoreEpoch = 100L,
            actionKey = null
        )
        database.scheduleAuthorityDao().insertAuthority(authority)

        val result = roomEvidenceReader.readRoomEvidence()
        assertTrue(result is RoomEvidenceReadResult.Success)
        val success = result as RoomEvidenceReadResult.Success

        assertNotNull(success.meta)
        assertEquals(1, success.meta?.id)
        assertEquals(100L, success.meta?.restoreEpoch)
        assertEquals("ENFORCED", success.meta?.bootstrapState)

        assertEquals(1, success.owners.size)
        assertEquals(50L, success.owners[0].alarmId)
        assertEquals("token-room-50", success.owners[0].instanceToken)
        assertEquals(CoverageResult.COMPLETE, success.report.result)
    }

    @Test
    fun `readRoomEvidence on closed database fails closed to ReadFailure`() = runBlocking {
        val failingDb = io.mockk.mockk<AlarmDatabase>()
        io.mockk.coEvery { failingDb.alarmDao() } throws RuntimeException("SQLite read error")

        val failingSnapshotReader = AuthoritySnapshotReader(failingDb)
        val failingRoomReader = RoomAuthorityEvidenceReader(failingSnapshotReader)

        val result = failingRoomReader.readRoomEvidence()
        assertTrue(result is RoomEvidenceReadResult.ReadFailure)
        val failure = result as RoomEvidenceReadResult.ReadFailure
        assertTrue(failure.details.contains("Failed to read authority snapshot from Room database"))
    }
}
