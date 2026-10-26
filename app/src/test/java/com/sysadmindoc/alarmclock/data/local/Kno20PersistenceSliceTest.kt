package com.sysadmindoc.alarmclock.data.local

import android.content.Context
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import com.sysadmindoc.alarmclock.data.backup.BackupManager
import com.sysadmindoc.alarmclock.data.backup.RestoreJournalCoordinator
import com.sysadmindoc.alarmclock.data.model.Alarm
import com.sysadmindoc.alarmclock.data.model.AlarmKind
import com.sysadmindoc.alarmclock.data.repository.AlarmRepository
import com.sysadmindoc.alarmclock.domain.AlarmScheduler
import com.sysadmindoc.alarmclock.data.preferences.PreferencesManager
import io.mockk.mockk
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
class Kno20PersistenceSliceTest {

    private lateinit var context: Context
    private lateinit var db: AlarmDatabase
    private lateinit var alarmDao: AlarmDao
    private lateinit var alarmRepo: AlarmRepository
    private lateinit var preferencesManager: PreferencesManager
    private lateinit var backupManager: BackupManager

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, AlarmDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        alarmDao = db.alarmDao()
        alarmRepo = AlarmRepository(db, alarmDao, db.alarmGroupDao())
        preferencesManager = PreferencesManager(context)
        val mockScheduler = mockk<AlarmScheduler>(relaxed = true)
        val mockJournalCoordinator = mockk<RestoreJournalCoordinator>(relaxed = true)
        backupManager = BackupManager(context, alarmRepo, preferencesManager, mockScheduler, mockJournalCoordinator, db)
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `MIGRATION_32_33 adds kind and quick fields to alarms table`() {
        val sqliteDb = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(null)
                .callback(object : SupportSQLiteOpenHelper.Callback(32) {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        db.execSQL(
                            "CREATE TABLE IF NOT EXISTS `alarms` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `hour` INTEGER NOT NULL, `minute` INTEGER NOT NULL, `label` TEXT NOT NULL, `isEnabled` INTEGER NOT NULL)"
                        )
                    }
                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {}
                })
                .build()
        ).writableDatabase

        sqliteDb.execSQL("INSERT INTO alarms (id, hour, minute, label, isEnabled) VALUES (101, 7, 30, 'Morning Alarm', 1)")

        AlarmDatabase.MIGRATION_32_33.migrate(sqliteDb)

        val cursor = sqliteDb.query("SELECT id, label, kind, quickTriggerEpochMs, quickRelativeElapsedMs, quickBootCount FROM alarms WHERE id = 101")
        assertTrue("Migrated alarm row MUST exist", cursor.moveToFirst())

        assertEquals(101L, cursor.getLong(0))
        assertEquals("Morning Alarm", cursor.getString(1))
        assertEquals("NORMAL", cursor.getString(2))
        assertEquals(0L, cursor.getLong(3))
        assertEquals(0L, cursor.getLong(4))
        assertEquals(-1L, cursor.getLong(5))

        cursor.close()
        sqliteDb.close()
    }

    @Test
    fun `Alarm entity persists and reloads explicit QUICK identity and trigger fields`() = runBlocking {
        val quickAlarm = Alarm(
            hour = 14,
            minute = 15,
            label = "Quick 15 min",
            isEnabled = true,
            kind = AlarmKind.QUICK.name,
            quickTriggerEpochMs = 1700000000000L,
            quickRelativeElapsedMs = 900000L,
            quickBootCount = 4
        )

        val id = alarmRepo.save(quickAlarm)
        assertTrue("Saved ID must be > 0", id > 0)

        val loaded = alarmRepo.getById(id)
        assertNotNull("Loaded quick alarm must not be null", loaded)
        assertEquals(AlarmKind.QUICK.name, loaded?.kind)
        assertEquals(1700000000000L, loaded?.quickTriggerEpochMs)
        assertEquals(900000L, loaded?.quickRelativeElapsedMs)
        assertEquals(4L, loaded?.quickBootCount)

        val pendingQuick = alarmRepo.getPendingQuickAlarms()
        assertEquals(1, pendingQuick.size)
        assertEquals(id, pendingQuick[0].id)
    }

    @Test
    fun `BackupManager export excludes QUICK alarms and exports NORMAL alarms only`() = runBlocking {
        val normalAlarm = Alarm(
            hour = 8,
            minute = 0,
            label = "Daily Work",
            isEnabled = true,
            kind = AlarmKind.NORMAL.name
        )

        val quickAlarm = Alarm(
            hour = 12,
            minute = 0,
            label = "Quick Nap",
            isEnabled = true,
            kind = AlarmKind.QUICK.name,
            quickTriggerEpochMs = 1700000000000L
        )

        alarmRepo.save(normalAlarm)
        alarmRepo.save(quickAlarm)

        val allInDb = alarmRepo.getAll()
        assertEquals("DB must contain 2 total alarms", 2, allInDb.size)

        val normalOnly = alarmRepo.getAllNormalAlarms()
        assertEquals("getAllNormalAlarms MUST return 1 normal alarm", 1, normalOnly.size)
        assertEquals("Daily Work", normalOnly[0].label)

        val exportJson = backupManager.export()
        assertTrue("Backup export MUST include normal alarm label", exportJson.contains("Daily Work"))
        assertFalse("Backup export MUST EXCLUDE quick alarm label", exportJson.contains("Quick Nap"))
    }

    @Test
    fun `malformed kind values in Alarm entity do not become NORMAL or QUICK`() {
        val malformedAlarm = Alarm(
            hour = 10,
            minute = 0,
            label = "Corrupt Kind Test",
            kind = "MALFORMED_KIND_123"
        )

        val sanitized = malformedAlarm.sanitized()
        assertEquals("Malformed kind string MUST be preserved as trimmed token", "MALFORMED_KIND_123", sanitized.kind)
        assertFalse("isNormalAlarm MUST be false for malformed kind", sanitized.isNormalAlarm)
        assertFalse("isQuickAlarm MUST be false for malformed kind", sanitized.isQuickAlarm)
    }

    @Test
    fun `getEnabledNormalAlarms excludes QUICK and malformed kind alarms`() = runBlocking {
        val normalAlarm = Alarm(hour = 7, minute = 0, isEnabled = true, kind = Alarm.KIND_NORMAL)
        val quickAlarm = Alarm(hour = 8, minute = 0, isEnabled = true, kind = Alarm.KIND_QUICK)
        val malformedAlarm = Alarm(hour = 9, minute = 0, isEnabled = true, kind = "INVALID_KIND")

        alarmRepo.save(normalAlarm)
        alarmRepo.save(quickAlarm)
        alarmRepo.save(malformedAlarm)

        val normalEnabled = alarmRepo.getEnabledNormalAlarms()
        assertEquals("getEnabledNormalAlarms MUST return only legitimate Normal alarms", 1, normalEnabled.size)
        assertEquals(normalAlarm.hour, normalEnabled[0].hour)
    }

    @Test
    fun `isNormalAlarm and isQuickAlarm helper properties accurately distinguish kinds`() {
        val normal = Alarm(kind = Alarm.KIND_NORMAL)
        val quick = Alarm(kind = Alarm.KIND_QUICK)
        val legacy = Alarm(kind = "")

        assertTrue(normal.isNormalAlarm)
        assertFalse(normal.isQuickAlarm)

        assertTrue(quick.isQuickAlarm)
        assertFalse(quick.isNormalAlarm)

        assertTrue(legacy.isNormalAlarm)
        assertFalse(legacy.isQuickAlarm)
    }

    @Test
    fun `BackupManager export excludes malformed kind alarms`() = runBlocking {
        val normalAlarm = Alarm(hour = 7, minute = 0, label = "Valid Normal", isEnabled = true, kind = Alarm.KIND_NORMAL)
        val malformedAlarm = Alarm(hour = 8, minute = 0, label = "Corrupt Kind Alarm", isEnabled = true, kind = "MALFORMED_KIND_999")

        alarmRepo.save(normalAlarm)
        alarmRepo.save(malformedAlarm)

        val exportJson = backupManager.export()
        assertTrue("Backup export MUST include normal alarm label", exportJson.contains("Valid Normal"))
        assertFalse("Backup export MUST EXCLUDE malformed kind alarm label", exportJson.contains("Corrupt Kind Alarm"))
    }
}
