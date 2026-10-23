package com.sysadmindoc.alarmclock.data.local

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.sysadmindoc.alarmclock.data.local.entity.IssueSlot
import com.sysadmindoc.alarmclock.data.local.entity.IssuedOccurrence
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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
    fun `insert and retrieve issue slot`() = runBlocking {
        val slot = IssueSlot(
            alarmId = 42L,
            currentIssueToken = "token-123",
            generationId = 1L
        )

        dao.insertSlotInitial(slot)

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
        dao.insertSlotInitial(slot)

        // CAS increment slot generation with expectedGenId = 1L and expectedToken = "token-1"
        val successRows = dao.casIncrementSlotGeneration(
            alarmId = 44L,
            expectedGenId = 1L,
            expectedToken = "token-1",
            newToken = "token-2"
        )
        assertEquals(1, successRows)

        // CAS increment slot generation with stale expectedGenId = 1L (current is 2L)
        val staleRows = dao.casIncrementSlotGeneration(
            alarmId = 44L,
            expectedGenId = 1L,
            expectedToken = "token-2",
            newToken = "token-3"
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

    @Test
    fun `reserveIssuedOccurrenceTransaction creates PREPARED occurrence and increments generationId strictly monotonically`() = runBlocking {
        val alarmId = 100L
        val scheduledAt = 5000L

        // 1. Reserve occurrence #1
        val occ1 = dao.reserveIssuedOccurrenceTransaction(
            issueToken = "token-p1",
            alarmId = alarmId,
            scheduledAt = scheduledAt,
            fireId = "fire-100-5000-1"
        )

        assertNotNull(occ1)
        assertEquals("token-p1", occ1!!.issueToken)
        assertEquals(IssuedOccurrence.STATUS_PREPARED, occ1.status)
        assertEquals(2L, occ1.generationId) // Initial slot 1L -> incremented to 2L

        // Active slot token remains null until activation succeeds
        val slot1 = dao.getSlot(alarmId)
        assertNotNull(slot1)
        assertEquals(2L, slot1!!.generationId)
        assertNull(slot1.currentIssueToken)

        // 2. Reserve occurrence #2 for same alarm (e.g. reschedule or edit)
        val occ2 = dao.reserveIssuedOccurrenceTransaction(
            issueToken = "token-p2",
            alarmId = alarmId,
            scheduledAt = scheduledAt + 1000L,
            fireId = "fire-100-6000-2"
        )

        assertNotNull(occ2)
        assertEquals("token-p2", occ2!!.issueToken)
        assertEquals(3L, occ2.generationId) // Incremented to 3L

        val slot2 = dao.getSlot(alarmId)
        assertEquals(3L, slot2!!.generationId)
    }

    @Test
    fun `activateIssuedOccurrenceTransaction promotes PREPARED to ISSUED and updates active slot currentIssueToken`() = runBlocking {
        val alarmId = 200L
        val scheduledAt = 10000L

        val occ = dao.reserveIssuedOccurrenceTransaction(
            issueToken = "token-active-1",
            alarmId = alarmId,
            scheduledAt = scheduledAt,
            fireId = "fire-200-10000"
        )
        assertNotNull(occ)

        // Activate occurrence #1
        val activated = dao.activateIssuedOccurrenceTransaction("token-active-1")
        assertTrue("Activation MUST succeed", activated)

        val activeOcc = dao.getOccurrence("token-active-1")
        assertEquals(IssuedOccurrence.STATUS_ISSUED, activeOcc!!.status)

        val slot = dao.getSlot(alarmId)
        assertEquals("token-active-1", slot!!.currentIssueToken)
    }

    @Test
    fun `stale activation for outdated generation is rejected`() = runBlocking {
        val alarmId = 300L

        // Reserve occurrence #1 (gen 2)
        val occ1 = dao.reserveIssuedOccurrenceTransaction(
            issueToken = "token-gen2",
            alarmId = alarmId,
            scheduledAt = 1000L,
            fireId = "fire-300-1000"
        )
        assertNotNull(occ1)

        // Reserve occurrence #2 (gen 3 - supersedes occurrence #1)
        val occ2 = dao.reserveIssuedOccurrenceTransaction(
            issueToken = "token-gen3",
            alarmId = alarmId,
            scheduledAt = 2000L,
            fireId = "fire-300-2000"
        )
        assertNotNull(occ2)

        // Attempt to activate stale occurrence #1 (gen 2 while slot is at gen 3)
        val staleActivated = dao.activateIssuedOccurrenceTransaction("token-gen2")
        assertFalse("Stale activation MUST be rejected", staleActivated)

        // Verify occurrence #1 remains PREPARED
        assertEquals(IssuedOccurrence.STATUS_PREPARED, dao.getOccurrence("token-gen2")!!.status)

        // Activate current occurrence #2 (gen 3)
        val currentActivated = dao.activateIssuedOccurrenceTransaction("token-gen3")
        assertTrue("Current activation MUST succeed", currentActivated)
        assertEquals(IssuedOccurrence.STATUS_ISSUED, dao.getOccurrence("token-gen3")!!.status)
        assertEquals("token-gen3", dao.getSlot(alarmId)!!.currentIssueToken)
    }

    @Test
    fun `revokeIssuedOccurrenceTransaction invalidates active slot token and revokes active occurrence`() = runBlocking {
        val alarmId = 400L

        dao.reserveIssuedOccurrenceTransaction("token-revoke-1", alarmId, 1000L, "fire-400-1")
        dao.activateIssuedOccurrenceTransaction("token-revoke-1")

        assertEquals("token-revoke-1", dao.getSlot(alarmId)!!.currentIssueToken)

        // Revoke alarm (e.g. cancel/delete/disable)
        val revokedRows = dao.revokeIssuedOccurrenceTransaction(alarmId)
        assertEquals(1, revokedRows)

        val slot = dao.getSlot(alarmId)
        assertNull("Current slot token MUST be invalidated (null)", slot!!.currentIssueToken)
        assertEquals(3L, slot.generationId) // Incremented generationId

        val occ = dao.getOccurrence("token-revoke-1")
        assertEquals(IssuedOccurrence.STATUS_REVOKED, occ!!.status)
    }
}
