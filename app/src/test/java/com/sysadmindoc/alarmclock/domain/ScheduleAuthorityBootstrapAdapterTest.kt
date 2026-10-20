package com.sysadmindoc.alarmclock.domain

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.sysadmindoc.alarmclock.data.local.AlarmDatabase
import com.sysadmindoc.alarmclock.data.local.LocalEvidenceReadResult
import com.sysadmindoc.alarmclock.data.local.LocalIncarnationEvidenceReader
import com.sysadmindoc.alarmclock.data.local.ScheduleAuthorityDao
import com.sysadmindoc.alarmclock.data.local.entity.ScheduleAuthorityMeta
import com.sysadmindoc.alarmclock.data.model.Alarm
import com.sysadmindoc.alarmclock.data.preferences.AppSettings
import com.sysadmindoc.alarmclock.data.preferences.PreferencesManager
import com.sysadmindoc.alarmclock.data.repository.AlarmRepository
import com.sysadmindoc.alarmclock.data.repository.HolidayRepository
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
import java.time.LocalDate

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class ScheduleAuthorityBootstrapAdapterTest {

    private lateinit var context: Context
    private lateinit var database: AlarmDatabase
    private lateinit var authorityDao: ScheduleAuthorityDao
    private lateinit var alarmRepository: AlarmRepository
    private lateinit var calculator: NextAlarmCalculator
    private lateinit var localReader: LocalIncarnationEvidenceReader
    private lateinit var preferencesManager: PreferencesManager
    private lateinit var holidayRepository: HolidayRepository
    private lateinit var bootstrapAdapter: ScheduleAuthorityBootstrapAdapter

    private val testAlarm = Alarm(
        id = 42L,
        hour = 7,
        minute = 30,
        label = "Test Bootstrap Alarm",
        isEnabled = true,
        nextTriggerTime = 1000L
    )

    private val defaultSettings = AppSettings()

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        database = Room.inMemoryDatabaseBuilder(context, AlarmDatabase::class.java)
            .allowMainThreadQueries()
            .addCallback(AlarmDatabase.DATABASE_CALLBACK)
            .build()
        authorityDao = database.scheduleAuthorityDao()
        alarmRepository = mockk()
        calculator = mockk()
        localReader = mockk()
        preferencesManager = mockk()
        holidayRepository = mockk()

        coEvery { preferencesManager.getCurrentSettings() } returns defaultSettings
        coEvery { localReader.readLocalEvidence() } returns LocalEvidenceReadResult.Success(
            localState = MarkerState.PRESENT_VALID,
            localId = "token-verified-42",
            localCompletion = CompletionState.COMPLETE
        )
        coEvery { holidayRepository.isHoliday(any()) } returns false

        bootstrapAdapter = ScheduleAuthorityBootstrapAdapter(
            authorityDao = authorityDao,
            alarmRepository = alarmRepository,
            calculator = calculator,
            localReader = localReader,
            preferencesManager = preferencesManager,
            holidayRepository = holidayRepository
        )
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun `missing incarnation evidence returns QUARANTINE without fabricating instanceToken`() = runBlocking {
        coEvery { alarmRepository.getById(42L) } returns testAlarm
        authorityDao.insertMeta(ScheduleAuthorityMeta(id = 1, restoreEpoch = 100L, bootstrapState = "ENFORCED"))

        val result = bootstrapAdapter.bootstrapAuthorityForAlarm(alarmId = 42L, instanceToken = null)

        assertEquals(BootstrapDecision.QUARANTINE, result)
        assertNull("No authority row must be created when instanceToken is missing", authorityDao.getAuthorityByAlarmId(42L))
    }

    @Test
    fun `stale or fabricated instanceToken mismatches local marker evidence and returns QUARANTINE`() = runBlocking {
        coEvery { alarmRepository.getById(42L) } returns testAlarm
        authorityDao.insertMeta(ScheduleAuthorityMeta(id = 1, restoreEpoch = 100L, bootstrapState = "ENFORCED"))

        // Caller passes fabricated instanceToken that mismatches "token-verified-42"
        val result = bootstrapAdapter.bootstrapAuthorityForAlarm(alarmId = 42L, instanceToken = "token-FABRICATED-99")

        assertEquals(BootstrapDecision.QUARANTINE, result)
        assertNull("Fabricated instanceToken must be quarantined without creating DB authority", authorityDao.getAuthorityByAlarmId(42L))
    }

    @Test
    fun `missing restore epoch meta returns QUARANTINE without fabricating restoreEpoch`() = runBlocking {
        coEvery { alarmRepository.getById(42L) } returns testAlarm

        val result = bootstrapAdapter.bootstrapAuthorityForAlarm(alarmId = 42L, instanceToken = "token-verified-42")

        assertEquals(BootstrapDecision.QUARANTINE, result)
        assertNull("No authority row must be created when restoreEpoch meta is missing", authorityDao.getAuthorityByAlarmId(42L))
    }

    @Test
    fun `pause active sets desiredOperation to CANCEL during bootstrap`() = runBlocking {
        coEvery { alarmRepository.getById(42L) } returns testAlarm
        authorityDao.insertMeta(ScheduleAuthorityMeta(id = 1, restoreEpoch = 100L, bootstrapState = "ENFORCED"))
        coEvery { preferencesManager.getCurrentSettings() } returns AppSettings(pauseUntilMillis = System.currentTimeMillis() + 3600_000L)

        val result = bootstrapAdapter.bootstrapAuthorityForAlarm(alarmId = 42L, instanceToken = "token-verified-42")

        assertEquals(BootstrapDecision.RECONCILE, result)
        val created = authorityDao.getAuthorityByAlarmId(42L)
        assertNotNull(created)
        assertEquals("CANCEL", created?.desiredOperation)
        assertNull(created?.desiredTriggerMillis)
    }

    @Test
    fun `holiday skip active sets desiredOperation to CANCEL on holiday date`() = runBlocking {
        val holidayAlarm = testAlarm.copy(skipOnHolidays = true)
        coEvery { alarmRepository.getById(42L) } returns holidayAlarm
        authorityDao.insertMeta(ScheduleAuthorityMeta(id = 1, restoreEpoch = 100L, bootstrapState = "ENFORCED"))

        val targetTime = System.currentTimeMillis() + 600_000L
        coEvery { calculator.calculate(any(), any()) } returns targetTime
        coEvery { holidayRepository.isHoliday(any<LocalDate>()) } returns true // Holiday date!

        val result = bootstrapAdapter.bootstrapAuthorityForAlarm(alarmId = 42L, instanceToken = "token-verified-42")

        assertEquals(BootstrapDecision.RECONCILE, result)
        val created = authorityDao.getAuthorityByAlarmId(42L)
        assertNotNull(created)
        assertEquals("CANCEL", created?.desiredOperation)
        assertNull(created?.desiredTriggerMillis)
    }

    @Test
    fun `valid evidence recalculates trigger time from calculator instead of trusting nextTriggerTime`() = runBlocking {
        coEvery { alarmRepository.getById(42L) } returns testAlarm
        authorityDao.insertMeta(ScheduleAuthorityMeta(id = 1, restoreEpoch = 100L, bootstrapState = "ENFORCED"))

        val recalculatedTarget = System.currentTimeMillis() + 600_000L
        coEvery { calculator.calculate(any(), any()) } returns recalculatedTarget

        val result = bootstrapAdapter.bootstrapAuthorityForAlarm(alarmId = 42L, instanceToken = "token-verified-42")

        assertEquals(BootstrapDecision.RECONCILE, result)
        val created = authorityDao.getAuthorityByAlarmId(42L)
        assertNotNull(created)
        assertEquals("RECONCILING", created?.reconcileState)
        assertEquals("token-verified-42", created?.instanceToken)
        assertEquals(100L, created?.restoreEpoch)
        assertEquals("Calculated trigger target must be used instead of legacy 1000L", recalculatedTarget, created?.desiredTriggerMillis)
    }
}
