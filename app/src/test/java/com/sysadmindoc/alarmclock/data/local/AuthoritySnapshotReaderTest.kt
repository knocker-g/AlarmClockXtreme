package com.sysadmindoc.alarmclock.data.local

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.sysadmindoc.alarmclock.data.local.entity.ScheduleAuthority
import com.sysadmindoc.alarmclock.data.local.entity.ScheduleAuthorityMeta
import com.sysadmindoc.alarmclock.data.model.Alarm
import com.sysadmindoc.alarmclock.domain.CoverageReasonCode
import com.sysadmindoc.alarmclock.domain.CoverageResult
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class AuthoritySnapshotReaderTest {

    private lateinit var context: Context
    private lateinit var database: AlarmDatabase
    private lateinit var reader: AuthoritySnapshotReader

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        database = Room.inMemoryDatabaseBuilder(context, AlarmDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        reader = AuthoritySnapshotReader(database)
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun missingMetadataWithEmptyAuthorityProducesUninitialized() = runBlocking {
        val report = reader.readAndValidateSnapshot()
        assertEquals(CoverageResult.UNINITIALIZED, report.result)
        assertEquals(CoverageReasonCode.META_ABSENT_AUTHORITY_EMPTY, report.reasonCode)
    }

    @Test
    fun missingMetadataWithOrphanAuthorityProducesInvalid() = runBlocking {
        val authority = ScheduleAuthority(
            alarmId = 42L,
            instanceToken = "token-123",
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

        val report = reader.readAndValidateSnapshot()
        assertEquals(CoverageResult.INVALID, report.result)
        assertEquals(CoverageReasonCode.META_ABSENT_AUTHORITY_PRESENT, report.reasonCode)
    }

    @Test
    fun validSingletonMetadataProducesComplete() = runBlocking {
        val alarm = Alarm(id = 42L, hour = 7, minute = 0, isEnabled = true)
        database.alarmDao().insert(alarm)

        val meta = ScheduleAuthorityMeta(id = 1, restoreEpoch = 100L, bootstrapState = "ENFORCED")
        database.scheduleAuthorityDao().insertMeta(meta)

        val authority = ScheduleAuthority(
            alarmId = 42L,
            instanceToken = "token-123",
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

        val report = reader.readAndValidateSnapshot()
        assertEquals(CoverageResult.COMPLETE, report.result)
        assertEquals(CoverageReasonCode.COVERAGE_VALID_COMPLETE, report.reasonCode)
    }

    @Test
    fun invalidSingletonIdProducesInvalid() = runBlocking {
        val meta = ScheduleAuthorityMeta(id = 2, restoreEpoch = 100L, bootstrapState = "ENFORCED")
        database.scheduleAuthorityDao().insertMeta(meta)

        val report = reader.readAndValidateSnapshot()
        assertEquals(CoverageResult.INVALID, report.result)
        assertEquals(CoverageReasonCode.META_ID_INVALID, report.reasonCode)
    }

    @Test
    fun multipleMetadataRowsProducesInvalid() = runBlocking {
        val meta1 = ScheduleAuthorityMeta(id = 1, restoreEpoch = 100L, bootstrapState = "ENFORCED")
        val meta2 = ScheduleAuthorityMeta(id = 2, restoreEpoch = 100L, bootstrapState = "ENFORCED")
        database.scheduleAuthorityDao().insertMeta(meta1)
        database.scheduleAuthorityDao().insertMeta(meta2)

        val report = reader.readAndValidateSnapshot()
        assertEquals(CoverageResult.INVALID, report.result)
        assertEquals(CoverageReasonCode.META_ID_INVALID, report.reasonCode)
        assertTrue(report.details.contains("Multiple schedule_authority_meta rows detected"))
    }

    @Test
    fun missingAlarmOwnerProducesIncomplete() = runBlocking {
        val alarm = Alarm(id = 42L, hour = 7, minute = 0, isEnabled = true)
        database.alarmDao().insert(alarm)

        val meta = ScheduleAuthorityMeta(id = 1, restoreEpoch = 100L, bootstrapState = "ENFORCED")
        database.scheduleAuthorityDao().insertMeta(meta)

        val report = reader.readAndValidateSnapshot()
        assertEquals(CoverageResult.INCOMPLETE, report.result)
        assertEquals(CoverageReasonCode.UNCOVERED_ALARM, report.reasonCode)
    }

    @Test
    fun mismatchedOwnerEpochProducesInvalid() = runBlocking {
        val alarm = Alarm(id = 42L, hour = 7, minute = 0, isEnabled = true)
        database.alarmDao().insert(alarm)

        val meta = ScheduleAuthorityMeta(id = 1, restoreEpoch = 100L, bootstrapState = "ENFORCED")
        database.scheduleAuthorityDao().insertMeta(meta)

        val authority = ScheduleAuthority(
            alarmId = 42L,
            instanceToken = "token-123",
            definitionRevision = 1L,
            scheduleRevision = 1L,
            lifecycle = "LIVE",
            desiredOperation = "SET",
            desiredTriggerMillis = 1000L,
            reconcileState = "SETTLED",
            restoreEpoch = 200L, // Mismatched epoch (meta has 100L)
            actionKey = null
        )
        database.scheduleAuthorityDao().insertAuthority(authority)

        val report = reader.readAndValidateSnapshot()
        assertEquals(CoverageResult.INVALID, report.result)
        assertEquals(CoverageReasonCode.MISMATCHED_RESTORE_EPOCH, report.reasonCode)
    }

    @Test
    fun daoReadFailureProducesReadFailed() = runBlocking {
        val failingDb = io.mockk.mockk<AlarmDatabase>()
        io.mockk.coEvery { failingDb.alarmDao() } throws RuntimeException("SQLite read error")

        val failingReader = AuthoritySnapshotReader(failingDb)
        val report = failingReader.readAndValidateSnapshot()

        assertEquals(CoverageResult.READ_FAILED, report.result)
        assertEquals(CoverageReasonCode.NONE, report.reasonCode)
        assertTrue(report.details.contains("Failed to read authority snapshot from Room database"))
    }

    @Test
    fun deterministicResultMappingAcrossInvocations() = runBlocking {
        val alarm = Alarm(id = 42L, hour = 7, minute = 0, isEnabled = true)
        database.alarmDao().insert(alarm)

        val meta = ScheduleAuthorityMeta(id = 1, restoreEpoch = 100L, bootstrapState = "ENFORCED")
        database.scheduleAuthorityDao().insertMeta(meta)

        val authority = ScheduleAuthority(
            alarmId = 42L,
            instanceToken = "token-123",
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

        val report1 = reader.readAndValidateSnapshot()
        val report2 = reader.readAndValidateSnapshot()

        assertEquals(report1.result, report2.result)
        assertEquals(report1.reasonCode, report2.reasonCode)
        assertEquals(CoverageResult.COMPLETE, report1.result)
    }

    @Test
    fun noSchedulerOrRestoreSideEffectsOnRead() = runBlocking {
        val alarm = Alarm(id = 42L, hour = 7, minute = 0, isEnabled = true)
        database.alarmDao().insert(alarm)

        val meta = ScheduleAuthorityMeta(id = 1, restoreEpoch = 100L, bootstrapState = "ENFORCED")
        database.scheduleAuthorityDao().insertMeta(meta)

        val report = reader.readAndValidateSnapshot()

        // Verify snapshot read is read-only and leaves database and alarm state intact
        assertEquals(CoverageResult.INCOMPLETE, report.result)
        assertEquals(1, database.alarmDao().getAll().size)
        assertEquals(1, database.scheduleAuthorityDao().getAllMeta().size)
        assertEquals(0, database.scheduleAuthorityDao().getAllAuthorities().size)
    }
}
