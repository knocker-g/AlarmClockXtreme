package com.sysadmindoc.alarmclock.data.local

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.sysadmindoc.alarmclock.data.local.entity.AlarmEvent
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AlarmEventLatestTest {
    private lateinit var db: AlarmDatabase
    private lateinit var dao: AlarmEventDao

    @Before
    fun createDb() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AlarmDatabase::class.java).build()
        dao = db.alarmEventDao()
    }

    @After
    fun closeDb() {
        db.close()
    }

    @Test
    fun observeLatestEventsPerAlarmReturnsOnlyLatestForEveryAlarm() = runBlocking {
        val alarm1 = 1L
        val alarm2 = 2L

        val events = listOf(
            AlarmEvent(alarmId = alarm1, scheduledTime = 100, firedAt = 100, action = "DISMISSED"),
            AlarmEvent(alarmId = alarm1, scheduledTime = 200, firedAt = 200, action = "SNOOZED"), // Latest for alarm1
            AlarmEvent(alarmId = alarm2, scheduledTime = 150, firedAt = 150, action = "MISSED")  // Latest for alarm2
        )

        events.forEach { dao.insert(it) }

        val latest = dao.observeLatestEventsPerAlarm().first()

        assertEquals(2, latest.size)
        val latest1 = latest.find { it.alarmId == alarm1 }
        val latest2 = latest.find { it.alarmId == alarm2 }

        assertEquals(200L, latest1?.scheduledTime)
        assertEquals("SNOOZED", latest1?.action)
        assertEquals(150L, latest2?.scheduledTime)
        assertEquals("MISSED", latest2?.action)
    }
}
