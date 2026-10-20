package com.sysadmindoc.alarmclock.domain

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.sysadmindoc.alarmclock.data.local.AlarmDatabase
import com.sysadmindoc.alarmclock.data.local.LocalEvidenceReadResult
import com.sysadmindoc.alarmclock.data.local.LocalIncarnationEvidenceReader
import com.sysadmindoc.alarmclock.data.local.ScheduleAuthorityDao
import com.sysadmindoc.alarmclock.data.local.entity.ScheduleAuthority
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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class ScheduleAuthorityReconcilerTest {

    private lateinit var context: Context
    private lateinit var database: AlarmDatabase
    private lateinit var authorityDao: ScheduleAuthorityDao
    private lateinit var reconciler: ScheduleAuthorityReconciler
    private lateinit var bootstrapAdapter: ScheduleAuthorityBootstrapAdapter
    private lateinit var alarmRepository: AlarmRepository
    private lateinit var calculator: NextAlarmCalculator
    private lateinit var localReader: LocalIncarnationEvidenceReader
    private lateinit var preferencesManager: PreferencesManager
    private lateinit var holidayRepository: HolidayRepository
    private val fakeOsSlot = mutableListOf<String>()

    private val initialUnreconciled = ScheduleAuthority(
        alarmId = 42L,
        instanceToken = "token-rec-42",
        definitionRevision = 1L,
        scheduleRevision = 1L,
        lifecycle = "LIVE",
        desiredOperation = "SET",
        desiredTriggerMillis = 2000L,
        reconcileState = "RECONCILING",
        restoreEpoch = 100L,
        actionKey = "act-rec"
    )

    private val initialSettled = ScheduleAuthority(
        alarmId = 43L,
        instanceToken = "token-rec-43",
        definitionRevision = 1L,
        scheduleRevision = 1L,
        lifecycle = "LIVE",
        desiredOperation = "SET",
        desiredTriggerMillis = System.currentTimeMillis() + 600_000L,
        reconcileState = "SETTLED",
        restoreEpoch = 100L,
        actionKey = "act-settled"
    )

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

        coEvery { preferencesManager.getCurrentSettings() } returns AppSettings()
        coEvery { localReader.readLocalEvidence() } returns LocalEvidenceReadResult.Success(
            localState = MarkerState.PRESENT_VALID,
            localId = "token-verified-99",
            localCompletion = CompletionState.COMPLETE
        )
        coEvery { holidayRepository.isHoliday(any()) } returns false
        coEvery { calculator.calculate(any(), any()) } returns 5000L

        fakeOsSlot.clear()
        val slotApplier = MainSlotApplier { authority ->
            fakeOsSlot.add("SET_${authority.desiredTriggerMillis}")
            true
        }

        val owner = ScheduleAuthorityOwner(
            authorityDao = authorityDao,
            mainSlotApplier = slotApplier,
            exactPermissionChecker = { true }
        )

        reconciler = ScheduleAuthorityReconciler(
            authorityDao = authorityDao,
            owner = owner
        )

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
    fun `reconcilePendingAuthorities discovers RECONCILING authority, applies OS operation, and transitions to SETTLED`() = runBlocking {
        authorityDao.insertAuthority(initialUnreconciled)

        val count = reconciler.reconcilePendingAuthorities()

        assertEquals(1, count)
        val settledAuth = authorityDao.getAuthorityByAlarmId(42L)
        assertNotNull(settledAuth)
        assertEquals("SETTLED", settledAuth?.reconcileState)
        assertEquals(listOf("SET_2000"), fakeOsSlot)
    }

    @Test
    fun `reconcilePendingAuthorities skips SETTLED authority during routine health check`() = runBlocking {
        authorityDao.insertAuthority(initialSettled)

        val count = reconciler.reconcilePendingAuthorities()

        assertEquals("Routine health check must NOT re-apply SETTLED authorities", 0, count)
        assertTrue("Fake OS slot must remain empty on routine health check for SETTLED authority", fakeOsSlot.isEmpty())
    }

    @Test
    fun `reconcileBootAuthorities re-evaluates SETTLED authority after boot and re-applies OS slot`() = runBlocking {
        val futureTarget = System.currentTimeMillis() + 600_000L
        val settledBootAuth = initialSettled.copy(desiredTriggerMillis = futureTarget)
        authorityDao.insertAuthority(settledBootAuth)

        val count = reconciler.reconcileBootAuthorities()

        assertEquals(1, count)
        assertEquals("Boot recovery MUST re-register SETTLED authority in fake OS slot", listOf("SET_$futureTarget"), fakeOsSlot)
    }

    @Test
    fun `bootstrapAdapter creates RECONCILING authority for enabled alarm lacking authority`() = runBlocking {
        val futureTarget = System.currentTimeMillis() + 600_000L
        val alarm = Alarm(id = 99L, hour = 8, minute = 0, label = "Bootstrap Alarm", isEnabled = true, nextTriggerTime = futureTarget)
        coEvery { alarmRepository.getById(99L) } returns alarm
        coEvery { calculator.calculate(any(), any()) } returns futureTarget
        authorityDao.insertMeta(ScheduleAuthorityMeta(id = 1, restoreEpoch = 100L, bootstrapState = "ENFORCED"))

        val decision = bootstrapAdapter.bootstrapAuthorityForAlarm(alarmId = 99L, instanceToken = "token-verified-99")

        assertEquals(BootstrapDecision.RECONCILE, decision)
        val authInDb = authorityDao.getAuthorityByAlarmId(99L)
        assertNotNull(authInDb)
        assertEquals("RECONCILING", authInDb?.reconcileState)
        assertEquals("SET", authInDb?.desiredOperation)
        assertEquals(futureTarget, authInDb?.desiredTriggerMillis)

        // Run reconciler to settle bootstrapped authority
        val reconciledCount = reconciler.reconcilePendingAuthorities()
        assertEquals(1, reconciledCount)
        assertEquals("SETTLED", authorityDao.getAuthorityByAlarmId(99L)?.reconcileState)
        assertEquals(listOf("SET_$futureTarget"), fakeOsSlot)
    }
}
