package com.sysadmindoc.alarmclock.data.local

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.sysadmindoc.alarmclock.data.local.entity.IssueSlot
import com.sysadmindoc.alarmclock.data.local.entity.IssuedOccurrence
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class IssuedOccurrenceDaoTest {

    private lateinit var db: AlarmDatabase
    private lateinit var dao: IssuedOccurrenceDao

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AlarmDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = db.issuedOccurrenceDao()
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `insert and retrieve issued occurrence`() = runBlocking {
        val occurrence = IssuedOccurrence(
            issueToken = "token-123",
            alarmId = 42L,
            scheduledAt = 1000L,
            fireId = "fire-42-1000",
            generationId = 1L,
            status = IssuedOccurrence.STATUS_PREPARED,
            createdAt = 1000L,
            updatedAt = 1000L
        )

        dao.insertOccurrence(occurrence)

        val retrieved = dao.getOccurrence("token-123")
        assertNotNull(retrieved)
        assertEquals("token-123", retrieved!!.issueToken)
        assertEquals(42L, retrieved.alarmId)
        assertEquals(1000L, retrieved.scheduledAt)
        assertEquals("PREPARED", retrieved.status)
        assertEquals("PENDING", retrieved.outcome)
    }

    @Test
    fun `upsert and retrieve issue slot`() = runBlocking {
        val slot = IssueSlot(
            alarmId = 42L,
            currentIssueToken = "token-123",
            generationId = 1L
        )

        dao.upsertSlot(slot)

        val retrieved = dao.getSlot(42L)
        assertNotNull(retrieved)
        assertEquals(42L, retrieved!!.alarmId)
        assertEquals("token-123", retrieved.currentIssueToken)
        assertEquals(1L, retrieved.generationId)
    }

    @Test
    fun `guarded status transition via CAS update`() = runBlocking {
        val occurrence = IssuedOccurrence(
            issueToken = "token-456",
            alarmId = 43L,
            scheduledAt = 2000L,
            fireId = "fire-43-2000",
            generationId = 1L,
            status = IssuedOccurrence.STATUS_PREPARED,
            createdAt = 2000L,
            updatedAt = 2000L
        )
        dao.insertOccurrence(occurrence)

        // CAS update PREPARED -> ISSUED
        val rowsUpdated = dao.casUpdateOccurrenceStatus(
            issueToken = "token-456",
            expectedStatus = IssuedOccurrence.STATUS_PREPARED,
            newStatus = IssuedOccurrence.STATUS_ISSUED,
            updatedAt = 2005L
        )

        assertEquals(1, rowsUpdated)
        val updated = dao.getOccurrence("token-456")
        assertEquals(IssuedOccurrence.STATUS_ISSUED, updated!!.status)

        // CAS update PREPARED -> ISSUED again (should fail because current status is ISSUED)
        val staleRowsUpdated = dao.casUpdateOccurrenceStatus(
            issueToken = "token-456",
            expectedStatus = IssuedOccurrence.STATUS_PREPARED,
            newStatus = IssuedOccurrence.STATUS_ADMITTED,
            updatedAt = 2010L
        )

        assertEquals(0, staleRowsUpdated)
        assertEquals(IssuedOccurrence.STATUS_ISSUED, dao.getOccurrence("token-456")!!.status)
    }

    @Test
    fun `stale slot CAS update is rejected`() = runBlocking {
        val slot = IssueSlot(
            alarmId = 44L,
            currentIssueToken = "token-1",
            generationId = 1L
        )
        dao.upsertSlot(slot)

        // CAS update slot with expectedGenId = 1L
        val successRows = dao.casUpdateSlotToken(
            alarmId = 44L,
            expectedGenId = 1L,
            newToken = "token-2",
            newGenId = 2L
        )
        assertEquals(1, successRows)

        // CAS update slot with stale expectedGenId = 1L (current is 2L)
        val staleRows = dao.casUpdateSlotToken(
            alarmId = 44L,
            expectedGenId = 1L,
            newToken = "token-3",
            newGenId = 3L
        )
        assertEquals(0, staleRows)

        val currentSlot = dao.getSlot(44L)
        assertEquals("token-2", currentSlot!!.currentIssueToken)
        assertEquals(2L, currentSlot.generationId)
    }

    @Test
    fun `primary key uniqueness on issueToken is enforced`() = runBlocking {
        val occ1 = IssuedOccurrence(
            issueToken = "duplicate-token",
            alarmId = 10L,
            scheduledAt = 1000L,
            fireId = "fire-10",
            generationId = 1L,
            status = IssuedOccurrence.STATUS_PREPARED,
            createdAt = 1000L,
            updatedAt = 1000L
        )
        dao.insertOccurrence(occ1)

        val occ2 = IssuedOccurrence(
            issueToken = "duplicate-token",
            alarmId = 20L,
            scheduledAt = 2000L,
            fireId = "fire-20",
            generationId = 2L,
            status = IssuedOccurrence.STATUS_ISSUED,
            createdAt = 2000L,
            updatedAt = 2000L
        )

        try {
            dao.insertOccurrence(occ2)
            org.junit.Assert.fail("Inserting duplicate issueToken MUST throw SQLiteConstraintException")
        } catch (e: Exception) {
            assertTrue("Expected SQLiteConstraintException", e is android.database.sqlite.SQLiteConstraintException)
        }
    }
}
