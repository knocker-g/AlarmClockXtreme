package com.sysadmindoc.alarmclock.data.backup

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.squareup.moshi.Moshi
import com.sysadmindoc.alarmclock.data.local.AlarmDatabase
import com.sysadmindoc.alarmclock.data.local.entity.AlarmGroup
import com.sysadmindoc.alarmclock.data.model.Alarm
import com.sysadmindoc.alarmclock.data.preferences.AppSettings
import com.sysadmindoc.alarmclock.data.preferences.PreferencesManager
import com.sysadmindoc.alarmclock.domain.AlarmScheduler
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.unmockkAll
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class RestoreJournalCoordinatorTest {
    private lateinit var context: Context
    private lateinit var database: AlarmDatabase
    private lateinit var preferencesManager: PreferencesManager
    private lateinit var alarmScheduler: AlarmScheduler
    private lateinit var coordinator: RestoreJournalCoordinator
    private lateinit var journalFile: File

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        database = Room.inMemoryDatabaseBuilder(context, AlarmDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        preferencesManager = mockk(relaxed = true)
        alarmScheduler = mockk(relaxed = true)
        coEvery { alarmScheduler.rescheduleAllInBatches(any(), any()) } returns 0

        journalFile = File(context.filesDir, RestoreJournalCoordinator.JOURNAL_FILENAME)
        if (journalFile.exists()) journalFile.delete()

        coordinator = RestoreJournalCoordinator(
            context = context,
            database = database,
            preferencesManager = preferencesManager,
            alarmScheduler = alarmScheduler,
            moshi = Moshi.Builder().build()
        )
    }

    @After
    fun tearDown() {
        database.close()
        if (journalFile.exists()) journalFile.delete()
        unmockkAll()
    }

    @Test
    fun prepareTransactionCreatesDurableJournal() = runTest {
        coordinator.prepareTransaction(AppSettings(), emptyList(), emptyList())

        assertTrue("Journal file must exist", journalFile.exists())
    }

    @Test
    fun checkAndRecoverWithPreparedPhasePerformsReconcile() = runTest {
        val oldAlarm = Alarm(id = 10L, hour = 7, minute = 30, label = "Old Alarm", isEnabled = true)
        val oldGroup = AlarmGroup(name = "OldGroup")
        val oldSettings = AppSettings(defaultSnoozeDuration = 15)

        coordinator.prepareTransaction(oldSettings, listOf(oldAlarm), listOf(oldGroup))

        coordinator.checkAndRecover()

        val restoredAlarms = database.alarmDao().getAll()
        assertTrue("Old alarm must be restored", restoredAlarms.any { it.label == "Old Alarm" })
        coVerify { preferencesManager.update(any()) }
        coVerify { alarmScheduler.rescheduleAllInBatches(any(), any()) }
        assertFalse("Journal file must be cleaned up after recovery", journalFile.exists())
    }

    @Test
    fun checkAndRecoverWithCommittedPhaseReconciles() = runTest {
        coordinator.prepareTransaction(AppSettings(), emptyList(), emptyList())
        coordinator.markCommitted()

        coordinator.checkAndRecover()

        coVerify { alarmScheduler.rescheduleAllInBatches(any(), any()) }
        assertFalse("Journal file must be cleaned up after committed recovery", journalFile.exists())
    }

    @Test(expected = Exception::class)
    fun checkAndRecoverWithCorruptJournalFailsClosed() = runTest {
        journalFile.writeText("corrupt json payload")
        coordinator.checkAndRecover()
    }

    @Test(expected = Exception::class)
    fun checkAndRecoverWithUnsupportedVersionFailsClosed() = runTest {
        journalFile.writeText("""{"formatVersion": 99, "phase": "PREPARED", "backupJson": "{}", "groupsJson": "[]", "checksum": "abc"}""")
        coordinator.checkAndRecover()
    }

    @Test(expected = Exception::class)
    fun checkAndRecoverWithChecksumMismatchFailsClosed() = runTest {
        coordinator.prepareTransaction(AppSettings(), emptyList(), emptyList())
        // Tamper journal file content
        val content = journalFile.readText()
        journalFile.writeText(content.replace("PREPARED", "COMMITTED_TAMPERED"))
        coordinator.checkAndRecover()
    }
}
