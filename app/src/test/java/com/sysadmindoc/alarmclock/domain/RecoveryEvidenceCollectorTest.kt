package com.sysadmindoc.alarmclock.domain

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.sysadmindoc.alarmclock.data.local.AlarmDatabase
import com.sysadmindoc.alarmclock.data.local.DurableOperationIntentDao
import com.sysadmindoc.alarmclock.data.local.LocalEvidenceReadResult
import com.sysadmindoc.alarmclock.data.local.LocalIncarnationEvidenceReader
import com.sysadmindoc.alarmclock.data.local.ScheduleAuthorityDao
import com.sysadmindoc.alarmclock.data.local.entity.DurableOperationIntent
import com.sysadmindoc.alarmclock.data.local.entity.ScheduleAuthority
import com.sysadmindoc.alarmclock.data.local.entity.ScheduleAuthorityMeta
import com.sysadmindoc.alarmclock.data.model.Alarm
import com.sysadmindoc.alarmclock.data.preferences.AppSettings
import com.sysadmindoc.alarmclock.data.preferences.PreferencesManager
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class RecoveryEvidenceCollectorTest {

    private lateinit var context: Context
    private lateinit var database: AlarmDatabase
    private lateinit var authorityDao: ScheduleAuthorityDao
    private lateinit var durableIntentDao: DurableOperationIntentDao
    private lateinit var localReader: LocalIncarnationEvidenceReader
    private lateinit var preferencesManager: PreferencesManager
    private lateinit var collector: RecoveryEvidenceCollector

    private val testAlarm = Alarm(
        id = 42L,
        hour = 7,
        minute = 30,
        label = "Collector Test Alarm",
        isEnabled = true,
        nextTriggerTime = 1000L
    )

    private val testMeta = ScheduleAuthorityMeta(id = 1, restoreEpoch = 100L, bootstrapState = "ENFORCED")

    private val readyAuthority = ScheduleAuthority(
        alarmId = 42L,
        instanceToken = "token-verified-42",
        definitionRevision = 1L,
        scheduleRevision = 1L,
        lifecycle = "LIVE",
        desiredOperation = "SET",
        desiredTriggerMillis = 2000L,
        reconcileState = "SETTLED",
        restoreEpoch = 100L,
        actionKey = "act-42"
    )

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        database = Room.inMemoryDatabaseBuilder(context, AlarmDatabase::class.java)
            .allowMainThreadQueries()
            .addCallback(AlarmDatabase.DATABASE_CALLBACK)
            .build()
        authorityDao = database.scheduleAuthorityDao()
        durableIntentDao = database.durableOperationIntentDao()
        localReader = mockk()
        preferencesManager = mockk()

        coEvery { preferencesManager.getCurrentSettings() } returns AppSettings()
        coEvery { localReader.readLocalEvidence() } returns LocalEvidenceReadResult.Success(
            localState = MarkerState.PRESENT_VALID,
            localId = "token-verified-42",
            localCompletion = CompletionState.COMPLETE
        )

        collector = RecoveryEvidenceCollector(
            database = database,
            localReader = localReader,
            preferencesManager = preferencesManager
        )
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun `valid matching evidence returns PROVEN_PRESENT`() = runBlocking {
        database.alarmDao().insert(testAlarm)
        authorityDao.insertMeta(testMeta)
        authorityDao.insertAuthority(readyAuthority)

        val report = collector.collectEvidence(42L)

        assertEquals(42L, report.alarmId)
        assertEquals(EvidenceReadStatus.PROVEN_PRESENT, report.overallStatus)
        assertEquals(EvidenceReadStatus.PROVEN_PRESENT, report.roomEvidence.status)
        assertNotNull(report.roomEvidence.alarm)
        assertEquals(100L, report.roomEvidence.restoreEpoch)
        assertEquals(EvidenceReadStatus.PROVEN_PRESENT, report.localMarkerEvidence.status)
        assertEquals("token-verified-42", report.localMarkerEvidence.localId)
        assertEquals(EvidenceReadStatus.PROVEN_ABSENT, report.sessionEvidence.status)
    }

    @Test
    fun `alarm definition absent returns PROVEN_ABSENT for room evidence`() = runBlocking {
        authorityDao.insertMeta(testMeta)
        // Alarm 42L NOT inserted into database!

        val report = collector.collectEvidence(42L)

        assertEquals(EvidenceReadStatus.PROVEN_ABSENT, report.roomEvidence.status)
        assertNull(report.roomEvidence.alarm)
    }

    @Test
    fun `multiple durable intents preserved without discarding`() = runBlocking {
        database.alarmDao().insert(testAlarm)
        authorityDao.insertMeta(testMeta)
        authorityDao.insertAuthority(readyAuthority)

        val intent1 = DurableOperationIntent(
            operationToken = "intent-1",
            alarmId = 42L,
            operationEpoch = 100L,
            incarnationId = "token-verified-42",
            operationKind = "SET",
            expectedOwnerToken = "token-verified-42",
            expectedScheduleRevision = 1L,
            expectedRestoreEpoch = 100L,
            phase = "COMPLETED", // Historical resolved intent
            intentRevision = 1L,
            originalFireId = "fire-1",
            originalScheduledAt = 2000L,
            candidateSnoozeAt = null
        )

        val intent2 = DurableOperationIntent(
            operationToken = "intent-2",
            alarmId = 42L,
            operationEpoch = 101L,
            incarnationId = "token-verified-42",
            operationKind = "SNOOZE",
            expectedOwnerToken = "token-verified-42",
            expectedScheduleRevision = 1L,
            expectedRestoreEpoch = 100L,
            phase = "IN_FLIGHT", // Current unresolved intent
            intentRevision = 1L,
            originalFireId = "fire-1",
            originalScheduledAt = 2000L,
            candidateSnoozeAt = 2600L
        )

        durableIntentDao.insertValidatedIntent(intent1)
        durableIntentDao.insertValidatedIntent(intent2)

        val report = collector.collectEvidence(42L)

        assertEquals(2, report.roomEvidence.intents.size)
        assertEquals("intent-1", report.roomEvidence.intents[0].operationToken)
        assertEquals("intent-2", report.roomEvidence.intents[1].operationToken)
    }

    @Test
    fun `partial or invalid persisted session returns CORRUPT_DATA`() = runBlocking {
        database.alarmDao().insert(testAlarm)
        authorityDao.insertMeta(testMeta)

        val corruptSettings = AppSettings(
            activeAlarmId = 42L,
            activeAlarmScheduledAt = 0L, // Invalid scheduledAt!
            activeAlarmFireId = "", // Invalid fireId!
            activeAlarmState = "INVALID_STATE"
        )
        coEvery { preferencesManager.getCurrentSettings() } returns corruptSettings

        val report = collector.collectEvidence(42L)

        assertEquals(EvidenceReadStatus.CORRUPT_DATA, report.sessionEvidence.status)
        assertEquals(EvidenceReadStatus.CORRUPT_DATA, report.overallStatus)
    }

    @Test
    fun `storage unavailable returns STORAGE_UNAVAILABLE`() = runBlocking {
        database.alarmDao().insert(testAlarm)
        coEvery { localReader.readLocalEvidence() } returns LocalEvidenceReadResult.StorageUnavailable

        val report = collector.collectEvidence(42L)

        assertEquals(EvidenceReadStatus.STORAGE_UNAVAILABLE, report.localMarkerEvidence.status)
        assertEquals(EvidenceReadStatus.STORAGE_UNAVAILABLE, report.overallStatus)
    }

    @Test
    fun `cross-store local marker mismatch between pre and post read returns UNPROVEN_CROSS_STORE_MISMATCH`() = runBlocking {
        database.alarmDao().insert(testAlarm)
        authorityDao.insertMeta(testMeta)

        // Pre-read returns Success token A, Post-read returns Success token B
        coEvery { localReader.readLocalEvidence() } returnsMany listOf(
            LocalEvidenceReadResult.Success(
                localState = MarkerState.PRESENT_VALID,
                localId = "token-A",
                localCompletion = CompletionState.COMPLETE
            ),
            LocalEvidenceReadResult.Success(
                localState = MarkerState.PRESENT_VALID,
                localId = "token-B", // Changed during read!
                localCompletion = CompletionState.COMPLETE
            )
        )

        val report = collector.collectEvidence(42L)

        assertEquals(EvidenceReadStatus.UNPROVEN_CROSS_STORE_MISMATCH, report.overallStatus)
    }
}
