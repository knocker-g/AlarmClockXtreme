package com.sysadmindoc.alarmclock.domain

import android.app.AlarmManager
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.sysadmindoc.alarmclock.data.local.AlarmDatabase
import com.sysadmindoc.alarmclock.data.local.IssuedOccurrenceDao
import com.sysadmindoc.alarmclock.data.local.entity.IssuedOccurrence
import com.sysadmindoc.alarmclock.data.preferences.PreferencesManager
import com.sysadmindoc.alarmclock.data.repository.AlarmIncidentRepository
import com.sysadmindoc.alarmclock.data.repository.AlarmRepository
import com.sysadmindoc.alarmclock.data.repository.HolidayRepository
import com.sysadmindoc.alarmclock.data.repository.WeatherRepository
import io.mockk.every
import io.mockk.mockk
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

@RunWith(RobolectricTestRunner::class)
class AlarmSchedulerTokenTest {

    private lateinit var context: Context
    private lateinit var db: AlarmDatabase
    private lateinit var dao: IssuedOccurrenceDao
    private lateinit var alarmRepo: AlarmRepository
    private lateinit var scheduler: AlarmScheduler

    private val calculator: NextAlarmCalculator = mockk(relaxed = true)
    private val preferencesManager: PreferencesManager = mockk(relaxed = true)
    private val holidayRepository: HolidayRepository = mockk(relaxed = true)
    private val alarmIncidentRepository: AlarmIncidentRepository = mockk(relaxed = true)
    private val weatherRepository: WeatherRepository = mockk(relaxed = true)

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        org.robolectric.shadows.ShadowAlarmManager.setCanScheduleExactAlarms(true)
        db = Room.inMemoryDatabaseBuilder(context, AlarmDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = db.issuedOccurrenceDao()

        alarmRepo = AlarmRepository(
            dao = db.alarmDao(),
            groupDao = db.alarmGroupDao(),
            database = db
        )

        every { preferencesManager.getCachedSettings() } returns com.sysadmindoc.alarmclock.data.preferences.AppSettings()
        io.mockk.coEvery { preferencesManager.getCurrentSettings() } returns com.sysadmindoc.alarmclock.data.preferences.AppSettings()

        scheduler = AlarmScheduler(
            context = context,
            repository = alarmRepo,
            calculator = calculator,
            preferencesManager = preferencesManager,
            holidayRepository = holidayRepository,
            alarmIncidentRepository = alarmIncidentRepository,
            weatherRepository = weatherRepository
        )
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `applyTokenizedOsSet reserves PREPARED occurrence, registers tokenized PendingIntent, and activates occurrence`() = runBlocking {
        val alarmId = 1001L
        val triggerTime = 1700000000000L
        val token = "token-p2b4-1"

        db.alarmDao().insert(com.sysadmindoc.alarmclock.data.model.Alarm(id = alarmId, hour = 7, minute = 0, isEnabled = true))

        val success = scheduler.applyTokenizedOsSet(alarmId, triggerTime, token)
        assertTrue("applyTokenizedOsSet MUST succeed", success)

        val slot = dao.getSlot(alarmId)
        assertNotNull(slot)
        assertEquals(token, slot!!.currentIssueToken)

        val occ = dao.getOccurrence(token)
        assertNotNull(occ)
        assertEquals(IssuedOccurrence.STATUS_ISSUED, occ!!.status)
        assertEquals(alarmId, occ.alarmId)
        assertEquals(triggerTime, occ.scheduledAt)
    }

    @Test
    fun `applyTokenizedOsSet sets status to SET_FAILED if AlarmManager registration throws exception`() = runBlocking {
        val alarmId = 2002L
        val triggerTime = 1700000000000L
        val token = "token-p2b4-fail"

        db.alarmDao().insert(com.sysadmindoc.alarmclock.data.model.Alarm(id = alarmId, hour = 7, minute = 0, isEnabled = true))

        val spyAlarmManager = mockk<AlarmManager>(relaxed = true) {
            every { canScheduleExactAlarms() } returns true
            every { setAlarmClock(any(), any()) } throws IllegalStateException("OS AlarmManager registration restricted")
            every { setExactAndAllowWhileIdle(any(), any(), any()) } throws IllegalStateException("OS AlarmManager registration restricted")
        }

        scheduler.testAlarmManager = spyAlarmManager

        val success = scheduler.applyTokenizedOsSet(alarmId, triggerTime, token)
        org.junit.Assert.assertFalse("applyTokenizedOsSet MUST return false when OS set fails", success)

        val occ = dao.getOccurrence(token)
        assertNotNull(occ)
        assertEquals(IssuedOccurrence.STATUS_SET_FAILED, occ!!.status)

        val slot = dao.getSlot(alarmId)
        assertNotNull(slot)
        assertNull("Active slot token MUST remain null when registration fails", slot!!.currentIssueToken)

        scheduler.testAlarmManager = null
    }

    @Test
    fun `cancelTokenizedScheduledAlarm revokes active occurrence and invalidates slot token`() = runBlocking {
        val alarmId = 3003L
        val triggerTime = 1700000000000L
        val token = "token-p2b4-cancel"

        db.alarmDao().insert(com.sysadmindoc.alarmclock.data.model.Alarm(id = alarmId, hour = 7, minute = 0, isEnabled = true))

        scheduler.applyTokenizedOsSet(alarmId, triggerTime, token)
        assertEquals(token, dao.getSlot(alarmId)!!.currentIssueToken)

        val canceled = scheduler.cancelTokenizedScheduledAlarm(alarmId)
        assertTrue("cancelTokenizedScheduledAlarm MUST succeed", canceled)

        val slot = dao.getSlot(alarmId)
        assertNull("Current slot token MUST be null after cancellation", slot!!.currentIssueToken)

        val occ = dao.getOccurrence(token)
        assertEquals(IssuedOccurrence.STATUS_REVOKED, occ!!.status)
    }

    @Test
    fun `rescheduling alarm via applyTokenizedOsSet atomically revokes previous active occurrence A`() = runBlocking {
        val alarmId = 4004L
        val tokenA = "token-p2b4-A"
        val tokenB = "token-p2b4-B"

        db.alarmDao().insert(com.sysadmindoc.alarmclock.data.model.Alarm(id = alarmId, hour = 7, minute = 0, isEnabled = true))

        // Schedule occurrence A
        scheduler.applyTokenizedOsSet(alarmId, 10000L, tokenA)
        assertEquals(tokenA, dao.getSlot(alarmId)!!.currentIssueToken)

        // Reschedule occurrence B
        scheduler.applyTokenizedOsSet(alarmId, 20000L, tokenB)
        assertEquals(tokenB, dao.getSlot(alarmId)!!.currentIssueToken)

        // Verify occurrence A was revoked and occurrence B is active
        assertEquals(IssuedOccurrence.STATUS_REVOKED, dao.getOccurrence(tokenA)!!.status)
        assertEquals(IssuedOccurrence.STATUS_ISSUED, dao.getOccurrence(tokenB)!!.status)
    }
}
