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

        val occ = dao.reserveIssuedOccurrence("token-active-1", alarmId, scheduledAt, "fire-200-10000")
        assertNotNull(occ)

        // Activate occurrence #1
        val activated = dao.activateIssuedOccurrence("token-active-1")
        assertTrue("Activation MUST succeed", activated)

        val activeOcc = dao.getOccurrence("token-active-1")
        assertEquals(IssuedOccurrence.STATUS_ISSUED, activeOcc!!.status)

        val slot = dao.getSlot(alarmId)
        assertEquals("token-active-1", slot!!.currentIssueToken)
    }

    @Test
    fun `Active A remains admissible while B is in PREPARED state`() = runBlocking {
        val alarmId = 150L

        // 1. Reserve and activate Occurrence A
        val occA = dao.reserveIssuedOccurrence("token-A", alarmId, 1000L, "fire-A")
        assertNotNull(occA)
        assertTrue(dao.activateIssuedOccurrence("token-A"))

        // Occurrence A is active in slot
        assertTrue("Occurrence A MUST be admissible", dao.evaluateActiveOccurrenceAdmission("token-A"))

        // 2. Reserve Occurrence B (status = PREPARED, generationId = 3L)
        val occB = dao.reserveIssuedOccurrence("token-B", alarmId, 2000L, "fire-B")
        assertNotNull(occB)
        assertEquals(IssuedOccurrence.STATUS_PREPARED, occB!!.status)

        // VERIFY: Occurrence A remains ADMISSIBLE while B is in PREPARED state!
        assertTrue("Active A MUST remain admissible while B is PREPARED", dao.evaluateActiveOccurrenceAdmission("token-A"))

        // VERIFY: Prepared B is NOT admissible via evaluateActiveOccurrenceAdmission
        assertFalse("Prepared B MUST NOT be admissible before activation", dao.evaluateActiveOccurrenceAdmission("token-B"))

        // 3. Activate Occurrence B -> atomically revokes A and updates slot token to token-B
        assertTrue("Activation of B MUST succeed", dao.activateIssuedOccurrence("token-B"))

        // VERIFY: B is now admissible, and A is no longer admissible
        assertTrue("Activated B MUST be admissible", dao.evaluateActiveOccurrenceAdmission("token-B"))
        assertFalse("Revoked A MUST NOT be admissible", dao.evaluateActiveOccurrenceAdmission("token-A"))
        assertEquals(IssuedOccurrence.STATUS_REVOKED, dao.getOccurrence("token-A")!!.status)
    }

    @Test
    fun `Active A in ADMITTED status preserves ADMITTED status when B is activated`() = runBlocking {
        val alarmId = 160L

        // 1. Reserve and activate Occurrence A, then promote to ADMITTED
        val occA = dao.reserveIssuedOccurrence("token-A-admitted", alarmId, 1000L, "fire-A")
        assertNotNull(occA)
        assertTrue(dao.activateIssuedOccurrence("token-A-admitted"))
        assertEquals(1, dao.casUpdateOccurrenceStatus("token-A-admitted", IssuedOccurrence.STATUS_ISSUED, IssuedOccurrence.STATUS_ADMITTED, 1005L))

        // Occurrence A is active in slot and ADMITTED
        assertEquals(IssuedOccurrence.STATUS_ADMITTED, dao.getOccurrence("token-A-admitted")!!.status)
        assertTrue("ADMITTED A MUST be admissible", dao.evaluateActiveOccurrenceAdmission("token-A-admitted"))

        // 2. Reserve Occurrence B
        val occB = dao.reserveIssuedOccurrence("token-B-new", alarmId, 2000L, "fire-B")
        assertNotNull(occB)

        // 3. Activate Occurrence B
        assertTrue("Activation of B MUST succeed", dao.activateIssuedOccurrence("token-B-new"))

        // VERIFY: Occurrence A's status remains ADMITTED (not revoked), preserving its firing proof!
        val finalA = dao.getOccurrence("token-A-admitted")
        assertNotNull(finalA)
        assertEquals("ADMITTED status MUST be preserved for delivered firing session", IssuedOccurrence.STATUS_ADMITTED, finalA!!.status)

        // VERIFY: Occurrence A is NO LONGER active in slot (slot points to token-B-new)
        assertFalse("A is no longer active in slot", dao.evaluateActiveOccurrenceAdmission("token-A-admitted"))
        assertTrue("B is active in slot", dao.evaluateActiveOccurrenceAdmission("token-B-new"))
    }

    @Test
    fun `activation fails if slot points to token whose occurrence record is missing`() = runBlocking {
        val alarmId = 170L

        // Manually create slot pointing to non-existent token
        dao.insertSlotInitial(IssueSlot(alarmId = alarmId, currentIssueToken = "token-missing", generationId = 1L))

        // Attempting to activate non-existent token MUST return false
        val activated = dao.activateIssuedOccurrence("token-missing")
        assertFalse("Activation of missing occurrence MUST return false", activated)
    }

    @Test
    fun `stale activation for outdated generation is rejected`() = runBlocking {
        val alarmId = 300L

        // Reserve occurrence #1 (gen 2)
        val occ1 = dao.reserveIssuedOccurrence("token-gen2", alarmId, 1000L, "fire-300-1000")
        assertNotNull(occ1)

        // Reserve occurrence #2 (gen 3 - supersedes occurrence #1)
        val occ2 = dao.reserveIssuedOccurrence("token-gen3", alarmId, 2000L, "fire-300-2000")
        assertNotNull(occ2)

        // Attempt to activate stale occurrence #1 (gen 2 while slot is at gen 3)
        val staleActivated = dao.activateIssuedOccurrence("token-gen2")
        assertFalse("Stale activation MUST be rejected", staleActivated)

        // Verify occurrence #1 remains PREPARED
        assertEquals(IssuedOccurrence.STATUS_PREPARED, dao.getOccurrence("token-gen2")!!.status)

        // Activate current occurrence #2 (gen 3)
        val currentActivated = dao.activateIssuedOccurrence("token-gen3")
        assertTrue("Current activation MUST succeed", currentActivated)
        assertEquals(IssuedOccurrence.STATUS_ISSUED, dao.getOccurrence("token-gen3")!!.status)
        assertEquals("token-gen3", dao.getSlot(alarmId)!!.currentIssueToken)
    }

    @Test
    fun `revokeIssuedOccurrenceTransaction invalidates active slot token and revokes active occurrence`() = runBlocking {
        val alarmId = 400L

        dao.reserveIssuedOccurrence("token-revoke-1", alarmId, 1000L, "fire-400-1")
        dao.activateIssuedOccurrence("token-revoke-1")

        assertEquals("token-revoke-1", dao.getSlot(alarmId)!!.currentIssueToken)

        // Revoke alarm (e.g. cancel/delete/disable)
        val revoked = dao.revokeIssuedOccurrence(alarmId)
        assertTrue("Revocation MUST succeed", revoked)

        val slot = dao.getSlot(alarmId)
        assertNotNull("Slot tombstone MUST be preserved across revocation", slot)
        assertNull("Current slot token MUST be null", slot!!.currentIssueToken)
        assertEquals(IssuedOccurrence.STATUS_REVOKED, dao.getOccurrence("token-revoke-1")!!.status)
    }

    @Test
    fun `reserveIssuedOccurrence helper returns null and rolls back row if duplicate issueToken inserted`() = runBlocking {
        val alarmId = 101L

        val occ1 = dao.reserveIssuedOccurrence("token-dup", alarmId, 1000L, "fire-101-1")
        assertNotNull(occ1)

        // Attempting to reserve with duplicate token-dup MUST roll back and return null
        val occ2 = dao.reserveIssuedOccurrence("token-dup", alarmId, 2000L, "fire-101-2")
        assertNull("Duplicate issueToken reservation MUST return null", occ2)

        // Slot generation remains at gen 2 (from first reservation) and occurrence remains unchanged
        val slot = dao.getSlot(alarmId)
        assertEquals(2L, slot!!.generationId)
        assertEquals("fire-101-1", dao.getOccurrence("token-dup")!!.fireId)
    }

    @Test
    fun `activateIssuedOccurrence helper rolls back and returns false if activation is stale or invalid`() = runBlocking {
        val alarmId = 201L

        val occ1 = dao.reserveIssuedOccurrence("token-stale", alarmId, 1000L, "fire-201-1")
        assertNotNull(occ1)

        // Intervening reservation bumps generation
        val occ2 = dao.reserveIssuedOccurrence("token-fresh", alarmId, 2000L, "fire-201-2")
        assertNotNull(occ2)

        // Attempting to activate stale occurrence #1 returns false
        val activated = dao.activateIssuedOccurrence("token-stale")
        assertFalse("Stale activation MUST return false", activated)

        // Verify status remained PREPARED and slot currentIssueToken was NOT updated
        assertEquals(IssuedOccurrence.STATUS_PREPARED, dao.getOccurrence("token-stale")!!.status)
        assertNull(dao.getSlot(alarmId)!!.currentIssueToken)
    }

    @Test
    fun `generationId overflow is rejected before mutation`() = runBlocking {
        val alarmId = 500L
        dao.insertSlotInitial(IssueSlot(alarmId = alarmId, currentIssueToken = null, generationId = Long.MAX_VALUE - 50L))

        val reserved = dao.reserveIssuedOccurrence("token-overflow", alarmId, 1000L, "fire-500")
        assertNull("Overflow reservation MUST return null", reserved)

        assertNull("Overflow occurrence MUST NOT be inserted in DB", dao.getOccurrence("token-overflow"))
    }

    @Test
    fun `revokeIssuedOccurrence revokes pending occurrence and preserves slot tombstone`() = runBlocking {
        val alarmId = 600L

        dao.reserveIssuedOccurrence("token-rev", alarmId, 1000L, "fire-600")
        dao.activateIssuedOccurrence("token-rev")

        val revoked = dao.revokeIssuedOccurrence(alarmId)
        assertTrue("Revocation MUST succeed", revoked)

        val slot = dao.getSlot(alarmId)
        assertNotNull("Slot tombstone MUST be preserved across revocation", slot)
        assertNull("Slot current token MUST be null", slot!!.currentIssueToken)
        assertEquals(IssuedOccurrence.STATUS_REVOKED, dao.getOccurrence("token-rev")!!.status)
    }

    @Test
    fun `revocation on non existent slot returns false`() = runBlocking {
        val revoked = dao.revokeIssuedOccurrence(alarmId = 99999L)
        assertFalse("Revocation on non-existent slot MUST return false", revoked)
    }

    @Test
    fun `second step slot CAS failure during reservation rolls back inserted PREPARED row`() = runBlocking {
        val alarmId = 700L

        // Execute transaction where step 1 insert succeeds but step 2 CAS fails via exception
        try {
            db.runInTransaction {
                runBlocking {
                    // Step 1: Insert occurrence
                    dao.insertOccurrence(
                        IssuedOccurrence(
                            issueToken = "token-cas-fail",
                            alarmId = alarmId,
                            scheduledAt = 1000L,
                            fireId = "fire-700",
                            generationId = 2L,
                            status = IssuedOccurrence.STATUS_PREPARED
                        )
                    )
                    // Step 2: Force CAS failure by supplying mismatched expectedGenId = 999L
                    val casRows = dao.casIncrementSlotGeneration(
                        alarmId = alarmId,
                        expectedGenId = 999L, // Mismatched!
                        expectedToken = null,
                        newToken = null
                    )
                    if (casRows != 1) {
                        throw IllegalStateException("Simulated second-step CAS failure")
                    }
                }
            }
            org.junit.Assert.fail("Transaction MUST throw exception on second-step CAS failure")
        } catch (_: Exception) {
            // Expected
        }

        // VERIFY: Step 1 inserted row MUST be completely ROLLED BACK from database
        val occ = dao.getOccurrence("token-cas-fail")
        assertNull("Occurrence row MUST be rolled back and absent from DB", occ)
    }

    @Test
    fun `second step slot CAS failure during activation rolls back status update`() = runBlocking {
        val alarmId = 800L
        dao.reserveIssuedOccurrence("token-act-fail", alarmId, 1000L, "fire-800")

        // Execute transaction where step 1 status update succeeds but step 2 slot CAS fails
        try {
            db.runInTransaction {
                runBlocking {
                    // Step 1: Update status PREPARED -> ISSUED
                    val statusCas = dao.casUpdateOccurrenceStatus(
                        issueToken = "token-act-fail",
                        expectedStatus = IssuedOccurrence.STATUS_PREPARED,
                        newStatus = IssuedOccurrence.STATUS_ISSUED,
                        updatedAt = 1005L
                    )
                    assertEquals(1, statusCas)

                    // Step 2: Force slot update CAS failure by supplying mismatched expectedGenId = 999L
                    val slotCas = dao.casUpdateSlotCurrentTokenOnly(
                        alarmId = alarmId,
                        expectedGenId = 999L, // Mismatched!
                        expectedToken = null,
                        newToken = "token-act-fail"
                    )
                    if (slotCas != 1) {
                        throw IllegalStateException("Simulated second-step slot CAS failure during activation")
                    }
                }
            }
            org.junit.Assert.fail("Activation transaction MUST throw exception on second-step CAS failure")
        } catch (_: Exception) {
            // Expected
        }

        // VERIFY: Status update MUST be completely ROLLED BACK to PREPARED
        val occ = dao.getOccurrence("token-act-fail")
        assertNotNull(occ)
        assertEquals(IssuedOccurrence.STATUS_PREPARED, occ!!.status)
        assertNull(dao.getSlot(alarmId)!!.currentIssueToken)
    }
}
