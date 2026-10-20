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
import com.sysadmindoc.alarmclock.data.repository.AlarmRepository
import com.sysadmindoc.alarmclock.service.AlarmService
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class ScheduleAuthorityPreflightAssessorTest {

    private lateinit var context: Context
    private lateinit var database: AlarmDatabase
    private lateinit var authorityDao: ScheduleAuthorityDao
    private lateinit var durableIntentDao: DurableOperationIntentDao
    private lateinit var alarmRepository: AlarmRepository
    private lateinit var localReader: LocalIncarnationEvidenceReader
    private lateinit var preferencesManager: PreferencesManager
    private lateinit var assessor: ScheduleAuthorityPreflightAssessor

    private val testAlarm = Alarm(
        id = 42L,
        hour = 7,
        minute = 30,
        label = "Preflight Test Alarm",
        isEnabled = true,
        nextTriggerTime = 1000L
    )

    private val testMeta = ScheduleAuthorityMeta(id = 1, restoreEpoch = 100L, bootstrapState = "ENFORCED")

    private val readyAuthority = ScheduleAuthority(
        alarmId = 42L,
        instanceToken = "token-verified-42",
        definitionRevision = ScheduleAuthorityBootstrapAdapter.computeDefinitionRevision(testAlarm),
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
        alarmRepository = mockk()
        localReader = mockk()
        preferencesManager = mockk()

        AlarmService.activeAlarm.set(null)

        coEvery { preferencesManager.getCurrentSettings() } returns AppSettings()
        coEvery { localReader.readLocalEvidence() } returns LocalEvidenceReadResult.Success(
            localState = MarkerState.PRESENT_VALID,
            localId = "token-verified-42",
            localCompletion = CompletionState.COMPLETE
        )

        assessor = ScheduleAuthorityPreflightAssessor(
            alarmRepository = alarmRepository,
            authorityDao = authorityDao,
            durableIntentDao = durableIntentDao,
            localReader = localReader,
            preferencesManager = preferencesManager,
            scheduleAuthorityOwnerProvider = null // Nullable provider represents legacy bypasses reachable
        )
    }

    @After
    fun tearDown() {
        AlarmService.activeAlarm.set(null)
        database.close()
    }

    @Test
    fun `one missing Authority is classified as UNKNOWN and blocks cutover`() = runBlocking {
        coEvery { alarmRepository.getAll() } returns listOf(testAlarm)
        authorityDao.insertMeta(testMeta)
        // No ScheduleAuthority inserted in authorityDao!

        val report = assessor.assessCutoverReadiness()

        assertEquals(1, report.enabledAlarmCount)
        assertEquals(1, report.unknownCount)
        assertEquals(AlarmCohortClassification.UNKNOWN, report.classifications[42L])
        assertFalse("Cutover MUST be false when an enabled alarm is UNKNOWN", report.cutoverReady)
    }

    @Test
    fun `one quarantined Authority is classified as QUARANTINED and blocks cutover`() = runBlocking {
        coEvery { alarmRepository.getAll() } returns listOf(testAlarm)
        authorityDao.insertMeta(testMeta)
        authorityDao.insertAuthority(readyAuthority.copy(reconcileState = "QUARANTINE"))

        val report = assessor.assessCutoverReadiness()

        assertEquals(1, report.quarantinedCount)
        assertEquals(AlarmCohortClassification.QUARANTINED, report.classifications[42L])
        assertFalse("Cutover MUST be false when an enabled alarm is QUARANTINED", report.cutoverReady)
    }

    @Test
    fun `persisted FIRING session is classified as ACTIVE_SESSION_OWNED and blocks cutover`() = runBlocking {
        coEvery { alarmRepository.getAll() } returns listOf(testAlarm)
        authorityDao.insertMeta(testMeta)
        authorityDao.insertAuthority(readyAuthority)

        val firingSettings = AppSettings(
            activeAlarmId = 42L,
            activeAlarmScheduledAt = 1000L,
            activeAlarmFireId = "fire-42-1000",
            activeAlarmFiredAt = System.currentTimeMillis(),
            activeAlarmState = "FIRING"
        )
        coEvery { preferencesManager.getCurrentSettings() } returns firingSettings

        val report = assessor.assessCutoverReadiness()

        assertEquals(1, report.activeSessionCount)
        assertEquals(AlarmCohortClassification.ACTIVE_SESSION_OWNED, report.classifications[42L])
        assertFalse("Cutover MUST be false when active FIRING session exists", report.cutoverReady)
    }

    @Test
    fun `persisted SNOOZED session is classified as ACTIVE_SESSION_OWNED and blocks cutover`() = runBlocking {
        coEvery { alarmRepository.getAll() } returns listOf(testAlarm)
        authorityDao.insertMeta(testMeta)
        authorityDao.insertAuthority(readyAuthority)

        val snoozedSettings = AppSettings(
            activeAlarmId = 42L,
            activeAlarmScheduledAt = 1000L,
            activeAlarmFireId = "fire-42-1000",
            activeAlarmFiredAt = System.currentTimeMillis(),
            activeAlarmState = "SNOOZED",
            activeAlarmRefireAt = System.currentTimeMillis() + 600_000L
        )
        coEvery { preferencesManager.getCurrentSettings() } returns snoozedSettings

        val report = assessor.assessCutoverReadiness()

        assertEquals(1, report.activeSessionCount)
        assertEquals(AlarmCohortClassification.ACTIVE_SESSION_OWNED, report.classifications[42L])
        assertFalse("Cutover MUST be false when active SNOOZED session exists", report.cutoverReady)
    }

    @Test
    fun `unresolved DurableOperationIntent causes QUARANTINED classification`() = runBlocking {
        coEvery { alarmRepository.getAll() } returns listOf(testAlarm)
        authorityDao.insertMeta(testMeta)
        authorityDao.insertAuthority(readyAuthority)

        val pendingIntent = DurableOperationIntent(
            operationToken = "intent-42-1",
            alarmId = 42L,
            operationEpoch = 100L,
            incarnationId = "token-verified-42",
            operationKind = "SET",
            expectedOwnerToken = "token-verified-42",
            expectedScheduleRevision = 1L,
            expectedRestoreEpoch = 100L,
            phase = "PENDING",
            intentRevision = 1L,
            originalFireId = "fire-42",
            originalScheduledAt = 2000L,
            candidateSnoozeAt = null
        )
        durableIntentDao.insertValidatedIntent(pendingIntent)

        val report = assessor.assessCutoverReadiness()

        assertEquals(1, report.quarantinedCount)
        assertEquals(AlarmCohortClassification.QUARANTINED, report.classifications[42L])
        assertFalse("Cutover MUST be false when unresolved DurableIntent exists", report.cutoverReady)
    }

    @Test
    fun `unsupported policy smartAlarmEnabled causes QUARANTINED classification`() = runBlocking {
        val smartAlarm = testAlarm.copy(smartAlarmEnabled = true)
        coEvery { alarmRepository.getAll() } returns listOf(smartAlarm)
        authorityDao.insertMeta(testMeta)
        authorityDao.insertAuthority(readyAuthority)

        val report = assessor.assessCutoverReadiness()

        assertEquals(1, report.quarantinedCount)
        assertEquals(AlarmCohortClassification.QUARANTINED, report.classifications[42L])
        assertFalse("Cutover MUST be false when alarm uses unsupported smartAlarm policy", report.cutoverReady)
    }

    @Test
    fun `legacyBypassesReachable is true when owner provider is null`() = runBlocking {
        coEvery { alarmRepository.getAll() } returns listOf(testAlarm)
        authorityDao.insertMeta(testMeta)
        authorityDao.insertAuthority(readyAuthority)

        val report = assessor.assessCutoverReadiness()

        assertEquals(1, report.ownerReadyCount)
        assertTrue("legacyBypassesReachable MUST be true when scheduleAuthorityOwnerProvider is null", report.legacyBypassesReachable)
        assertFalse("Cutover MUST be false while legacy bypasses remain reachable", report.cutoverReady)
    }
}
