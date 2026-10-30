package com.sysadmindoc.alarmclock.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.sysadmindoc.alarmclock.data.local.AlarmDao
import com.sysadmindoc.alarmclock.data.local.AlarmDatabase
import com.sysadmindoc.alarmclock.data.local.IssuedOccurrenceDao
import com.sysadmindoc.alarmclock.data.local.ScheduleAuthorityDao
import com.sysadmindoc.alarmclock.data.model.Alarm
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

@RunWith(RobolectricTestRunner::class)
class AlarmRepositoryImportTest {

    private lateinit var context: Context
    private lateinit var db: AlarmDatabase
    private lateinit var alarmDao: AlarmDao
    private lateinit var authorityDao: ScheduleAuthorityDao
    private lateinit var issuedDao: IssuedOccurrenceDao
    private lateinit var repository: AlarmRepository

    @Before
    fun setUp() = runBlocking {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, AlarmDatabase::class.java)
            .addCallback(AlarmDatabase.DATABASE_CALLBACK)
            .allowMainThreadQueries()
            .build()
        alarmDao = db.alarmDao()
        authorityDao = db.scheduleAuthorityDao()
        issuedDao = db.issuedOccurrenceDao()

        repository = AlarmRepository(db, alarmDao, db.alarmGroupDao())
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `migration batch is sanitized disabled and sent through one transaction`() = runBlocking {
        val ids = repository.importDisabledAtomically(
            listOf(
                Alarm(id = 99, hour = 6, isEnabled = true, nextTriggerTime = 123L),
                Alarm(id = 100, hour = 7, isEnabled = true, nextTriggerTime = 456L)
            )
        )

        assertEquals(2, ids.size)
        val alarm1 = alarmDao.getById(ids[0])
        val alarm2 = alarmDao.getById(ids[1])

        assertNotNull("Imported alarm 1 MUST exist in DB", alarm1)
        assertNotNull("Imported alarm 2 MUST exist in DB", alarm2)

        assertFalse("Imported alarm 1 MUST be disabled", alarm1!!.isEnabled)
        assertFalse("Imported alarm 2 MUST be disabled", alarm2!!.isEnabled)
        assertEquals(0L, alarm1.nextTriggerTime)
        assertEquals(0L, alarm2.nextTriggerTime)

        val auth1 = authorityDao.getAuthorityByAlarmId(ids[0])
        val auth2 = authorityDao.getAuthorityByAlarmId(ids[1])

        assertNotNull("ScheduleAuthority MUST be created for imported alarm 1", auth1)
        assertNotNull("ScheduleAuthority MUST be created for imported alarm 2", auth2)
        assertEquals("DISABLED", auth1!!.lifecycle)
        assertEquals("CANCEL", auth1.desiredOperation)
        assertEquals("RECONCILING", auth1.reconcileState)
    }

    @Test
    fun `transaction failure is surfaced without falling back to partial row inserts`() = runBlocking {
        // Delete ScheduleAuthorityMeta (id = 1) from DB to force transaction failure
        db.compileStatement("DELETE FROM schedule_authority_meta").executeUpdateDelete()

        var failed = false
        try {
            repository.importDisabledAtomically(listOf(Alarm(hour = 7, isEnabled = true)))
        } catch (_: IllegalStateException) {
            failed = true
        }

        assertTrue("Import when ScheduleAuthorityMeta is missing MUST throw IllegalStateException to trigger rollback", failed)

        val allAlarms = alarmDao.getAll()
        assertTrue("Alarms table MUST remain completely empty after import failure rollback", allAlarms.isEmpty())
    }
}
