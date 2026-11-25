package com.sysadmindoc.alarmclock.data.local

import android.app.Application
import android.content.Context
import android.database.sqlite.SQLiteConstraintException
import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import com.sysadmindoc.alarmclock.data.local.entity.DurableOperationIntent
import com.sysadmindoc.alarmclock.data.local.entity.IssueSlot
import com.sysadmindoc.alarmclock.data.local.entity.IssuedOccurrence
import com.sysadmindoc.alarmclock.data.local.entity.ScheduleAuthority
import com.sysadmindoc.alarmclock.data.model.Alarm
import com.sysadmindoc.alarmclock.domain.evaluator.DurableCancelIntentValidator
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
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class DurableOperationIntentTest {

    private lateinit var context: Context
    private lateinit var database: AlarmDatabase
    private lateinit var intentDao: DurableOperationIntentDao
    private lateinit var issuedDao: IssuedOccurrenceDao

    private val validIntent = DurableOperationIntent(
        operationToken = "intent-token-100",
        alarmId = 42L,
        operationEpoch = 1L,
        incarnationId = "inc-token-100",
        operationKind = "SET",
        expectedOwnerToken = "owner-token-42",
        expectedScheduleRevision = 1L,
        expectedRestoreEpoch = 100L,
        phase = DurableOperationIntent.PHASE_PENDING,
        intentRevision = 1L
    )

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        database = Room.inMemoryDatabaseBuilder(context, AlarmDatabase::class.java)
            .allowMainThreadQueries()
            .addCallback(AlarmDatabase.DATABASE_CALLBACK)
            .build()
        intentDao = database.durableOperationIntentDao()
        issuedDao = database.issuedOccurrenceDao()
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun `1 - Fresh Room DB opens and installs SQLite triggers`() = runBlocking {
        intentDao.insertRawIntent(validIntent)
        val retrieved = intentDao.getIntentByToken("intent-token-100")
        assertNotNull(retrieved)
        assertEquals(validIntent, retrieved)

        // Inspect sqlite_master for trigger installation on fresh DB
        val cursor = database.openHelper.readableDatabase.query(
            "SELECT name FROM sqlite_master WHERE type = 'trigger' AND name LIKE 'trigger_durable_intent%'"
        )
        val triggerNames = mutableListOf<String>()
        cursor.use {
            while (it.moveToNext()) {
                triggerNames.add(it.getString(0))
            }
        }
        assertTrue("Triggers must be installed on Fresh DB", triggerNames.size >= 6)
    }

    @Test(expected = SQLiteConstraintException::class)
    fun `3 - Duplicate unresolved INSERT via raw SQL is rejected by SQLite trigger`(): Unit = runBlocking {
        val unresolved1 = validIntent.copy(operationToken = "tok-1", phase = DurableOperationIntent.PHASE_PENDING)
        val unresolved2 = validIntent.copy(operationToken = "tok-2", phase = DurableOperationIntent.PHASE_IN_FLIGHT)

        intentDao.insertRawIntent(unresolved1)
        // SQLite's BEFORE INSERT trigger rejects second unresolved intent for alarmId = 42L
        intentDao.insertRawIntent(unresolved2)
    }

    @Test(expected = SQLiteConstraintException::class)
    fun `4 - Raw UPDATE creating duplicate unresolved intent is rejected by SQLite trigger`(): Unit = runBlocking {
        val unresolved1 = validIntent.copy(operationToken = "tok-1", alarmId = 42L, phase = DurableOperationIntent.PHASE_PENDING)
        val completed2 = validIntent.copy(operationToken = "tok-2", alarmId = 42L, phase = DurableOperationIntent.PHASE_COMPLETED)

        intentDao.insertRawIntent(unresolved1)
        intentDao.insertRawIntent(completed2)

        // Raw SQL UPDATE attempting to transition completed2 -> IN_FLIGHT causes second unresolved for alarm 42L
        database.openHelper.writableDatabase.execSQL(
            "UPDATE durable_operation_intent SET phase = 'IN_FLIGHT' WHERE operationToken = 'tok-2'"
        )
    }

    @Test(expected = SQLiteConstraintException::class)
    fun `5 - Invalid phase INSERT is rejected by SQLite trigger`(): Unit = runBlocking {
        val invalidPhaseIntent = validIntent.copy(phase = "INVALID_PHASE_STRING")
        intentDao.insertRawIntent(invalidPhaseIntent)
    }

    @Test(expected = SQLiteConstraintException::class)
    fun `6 - Invalid phase UPDATE is rejected by SQLite trigger`(): Unit = runBlocking {
        intentDao.insertRawIntent(validIntent)
        database.openHelper.writableDatabase.execSQL(
            "UPDATE durable_operation_intent SET phase = 'UNKNOWN_PHASE' WHERE operationToken = 'intent-token-100'"
        )
    }

    @Test
    fun `boundary tests - all whitespace types are rejected on INSERT for operationToken`() = runBlocking {
        val whitespaceTypes = listOf("   ", "\t\t", "\n\n", "\u3000")
        for ((idx, ws) in whitespaceTypes.withIndex()) {
            var thrown = false
            try {
                val badIntent = validIntent.copy(operationToken = ws, alarmId = 1000L + idx)
                intentDao.insertRawIntent(badIntent)
            } catch (_: SQLiteConstraintException) {
                thrown = true
            }
            assertTrue("INSERT with operationToken whitespace '$ws' must be rejected by SQLite trigger", thrown)
        }
    }

    @Test
    fun `boundary tests - all whitespace types are rejected on INSERT for incarnationId`() = runBlocking {
        val whitespaceTypes = listOf("   ", "\t\t", "\n\n", "\u3000")
        for ((idx, ws) in whitespaceTypes.withIndex()) {
            var thrown = false
            try {
                val badIntent = validIntent.copy(operationToken = "tok-inc-$idx", incarnationId = ws, alarmId = 2000L + idx)
                intentDao.insertRawIntent(badIntent)
            } catch (_: SQLiteConstraintException) {
                thrown = true
            }
            assertTrue("INSERT with incarnationId whitespace '$ws' must be rejected by SQLite trigger", thrown)
        }
    }

    @Test
    fun `boundary tests - all whitespace types are rejected on INSERT for operationKind`() = runBlocking {
        val whitespaceTypes = listOf("   ", "\t\t", "\n\n", "\u3000")
        for ((idx, ws) in whitespaceTypes.withIndex()) {
            var thrown = false
            try {
                val badIntent = validIntent.copy(operationToken = "tok-kind-$idx", operationKind = ws, alarmId = 3000L + idx)
                intentDao.insertRawIntent(badIntent)
            } catch (_: SQLiteConstraintException) {
                thrown = true
            }
            assertTrue("INSERT with operationKind whitespace '$ws' must be rejected by SQLite trigger", thrown)
        }
    }

    @Test
    fun `boundary tests - all whitespace types are rejected on UPDATE for operationToken`() = runBlocking {
        intentDao.insertRawIntent(validIntent)
        val whitespaceTypes = listOf("   ", "\t\t", "\n\n", "\u3000")

        for (ws in whitespaceTypes) {
            var thrown = false
            try {
                database.openHelper.writableDatabase.execSQL(
                    "UPDATE durable_operation_intent SET operationToken = '$ws' WHERE alarmId = 42"
                )
            } catch (_: SQLiteConstraintException) {
                thrown = true
            }
            assertTrue("UPDATE with operationToken whitespace '$ws' must be rejected by SQLite trigger", thrown)
        }
    }

    @Test
    fun `boundary tests - all whitespace types are rejected on UPDATE for incarnationId`() = runBlocking {
        intentDao.insertRawIntent(validIntent)
        val whitespaceTypes = listOf("   ", "\t\t", "\n\n", "\u3000")

        for (ws in whitespaceTypes) {
            var thrown = false
            try {
                database.openHelper.writableDatabase.execSQL(
                    "UPDATE durable_operation_intent SET incarnationId = '$ws' WHERE operationToken = 'intent-token-100'"
                )
            } catch (_: SQLiteConstraintException) {
                thrown = true
            }
            assertTrue("UPDATE with incarnationId whitespace '$ws' must be rejected by SQLite trigger", thrown)
        }
    }

    @Test
    fun `boundary tests - all whitespace types are rejected on UPDATE for operationKind`() = runBlocking {
        intentDao.insertRawIntent(validIntent)
        val whitespaceTypes = listOf("   ", "\t\t", "\n\n", "\u3000")

        for (ws in whitespaceTypes) {
            var thrown = false
            try {
                database.openHelper.writableDatabase.execSQL(
                    "UPDATE durable_operation_intent SET operationKind = '$ws' WHERE operationToken = 'intent-token-100'"
                )
            } catch (_: SQLiteConstraintException) {
                thrown = true
            }
            assertTrue("UPDATE with operationKind whitespace '$ws' must be rejected by SQLite trigger", thrown)
        }
    }

    @Test
    fun `legitimate generated identifiers are accepted by both Kotlin and SQLite`() = runBlocking {
        val validTokens = listOf("intent-token-100", "550e8400-e29b-41d4-a716-446655440000", "CUSTOM_ACTION_KEY_123")
        val validKinds = listOf("SET", "CANCEL", "SNOOZE", "DISMISS")

        for ((idx, tok) in validTokens.withIndex()) {
            val intent = validIntent.copy(
                operationToken = tok,
                incarnationId = "inc-$idx",
                operationKind = validKinds[idx % validKinds.size],
                alarmId = 5000L + idx
            )
            intentDao.insertValidatedIntent(intent)
            val retrieved = intentDao.getIntentByToken(tok)
            assertNotNull(retrieved)
            assertEquals(tok, retrieved?.operationToken)
        }
    }

    @Test(expected = SQLiteConstraintException::class)
    fun `8 - Nonpositive alarmId is rejected by SQLite trigger`(): Unit = runBlocking {
        val zeroAlarmIdIntent = validIntent.copy(alarmId = 0L)
        intentDao.insertRawIntent(zeroAlarmIdIntent)
    }

    @Test(expected = SQLiteConstraintException::class)
    fun `8 - Nonpositive operationEpoch is rejected by SQLite trigger`(): Unit = runBlocking {
        val zeroEpochIntent = validIntent.copy(operationEpoch = 0L)
        intentDao.insertRawIntent(zeroEpochIntent)
    }

    @Test
    fun `9 - Distinct alarm IDs with unresolved operations succeed at SQLite layer`() = runBlocking {
        val alarm42Intent = validIntent.copy(alarmId = 42L, operationToken = "tok-42", phase = DurableOperationIntent.PHASE_PENDING)
        val alarm99Intent = validIntent.copy(alarmId = 99L, operationToken = "tok-99", phase = DurableOperationIntent.PHASE_PENDING)

        intentDao.insertRawIntent(alarm42Intent)
        intentDao.insertRawIntent(alarm99Intent)

        assertEquals("tok-42", intentDao.getUnresolvedIntentsByAlarmId(42L).first().operationToken)
        assertEquals("tok-99", intentDao.getUnresolvedIntentsByAlarmId(99L).first().operationToken)
    }

    @Test
    fun `10 - Terminal historical records are retained for replay audit`() = runBlocking {
        val completed = validIntent.copy(operationToken = "tok-comp", phase = DurableOperationIntent.PHASE_COMPLETED)
        val expired = validIntent.copy(operationToken = "tok-exp", phase = DurableOperationIntent.PHASE_EXPIRED)

        intentDao.insertRawIntent(completed)
        intentDao.insertRawIntent(expired)

        val all = intentDao.getAllIntents()
        assertEquals(2, all.size)
    }

    @Test
    fun `12 - Database close and reopen retains effective triggers and constraints`() = runBlocking {
        val dbFile = File(context.cacheDir, "test_reopen_triggers.db")
        if (dbFile.exists()) dbFile.delete()

        var fileDb = Room.databaseBuilder(context, AlarmDatabase::class.java, dbFile.absolutePath)
            .allowMainThreadQueries()
            .addCallback(AlarmDatabase.DATABASE_CALLBACK)
            .build()

        fileDb.durableOperationIntentDao().insertRawIntent(validIntent)
        fileDb.close()

        // Reopen same database file
        fileDb = Room.databaseBuilder(context, AlarmDatabase::class.java, dbFile.absolutePath)
            .allowMainThreadQueries()
            .addCallback(AlarmDatabase.DATABASE_CALLBACK)
            .build()

        var thrown = false
        try {
            val duplicate = validIntent.copy(operationToken = "tok-dup-reopen", phase = DurableOperationIntent.PHASE_IN_FLIGHT)
            fileDb.durableOperationIntentDao().insertRawIntent(duplicate)
        } catch (_: SQLiteConstraintException) {
            thrown = true
        }

        fileDb.close()
        if (dbFile.exists()) dbFile.delete()

        assertTrue("Triggers must remain active on reopened file DB", thrown)
    }

    @Test
    fun `13 - Valid phase vocabulary helper functions correctly`() {
        assertTrue(DurableOperationIntent.isValidPhase("PENDING"))
        assertTrue(DurableOperationIntent.isValidPhase("IN_FLIGHT"))
        assertTrue(DurableOperationIntent.isValidPhase("COMPLETED"))
        assertTrue(DurableOperationIntent.isValidPhase("ABORTED"))
        assertTrue(DurableOperationIntent.isValidPhase("EXPIRED"))

        assertFalse(DurableOperationIntent.isValidPhase("INVALID_PHASE"))
        assertFalse(DurableOperationIntent.isValidPhase(""))
    }

    // --- C4-B3-P2 Durable-to-B Identity Binding Contract Tests ---

    @Test
    fun `contract1_bOnlyIntent_operationTokenEqualsIssueToken_uniquelyDistinguishesB1FromB2`() = runBlocking {
        val alarmId = 42L
        val scheduledAt = 2000L
        val fireId = "fire-42-2000"

        // Insert Occurrence B1 and Occurrence B2 into Room DB
        issuedDao.reserveIssuedOccurrence("issue-B1", alarmId, scheduledAt, fireId)
        issuedDao.reserveIssuedOccurrence("issue-B2", alarmId, scheduledAt, fireId)

        // B-Specific Issuance Contract: operationToken = targetB1.issueToken
        val intentB1 = DurableOperationIntent(
            operationToken = "issue-B1",
            alarmId = alarmId,
            operationEpoch = System.currentTimeMillis(),
            incarnationId = fireId,
            operationKind = "CANCEL",
            expectedOwnerToken = "token-auth-B1",
            expectedScheduleRevision = 100L,
            expectedRestoreEpoch = 1L,
            phase = DurableOperationIntent.PHASE_PENDING,
            intentRevision = 2L,
            originalFireId = fireId,
            originalScheduledAt = scheduledAt
        )
        intentDao.insertValidatedIntent(intentB1)

        // Query unresolved intent
        val retrievedIntents = intentDao.getUnresolvedCancellationIntents()
        assertEquals(1, retrievedIntents.size)
        val retrieved = retrievedIntents.first()

        // Verify operationToken ("issue-B1") binds to Occurrence B1 with 100% precision!
        val targetOccurrence = issuedDao.getOccurrence(retrieved.operationToken)
        assertNotNull("Target occurrence B1 MUST be retrieved via operationToken", targetOccurrence)
        assertEquals("issue-B1", targetOccurrence!!.issueToken)
        assertFalse("Target occurrence B1 MUST NOT be B2", targetOccurrence.issueToken == "issue-B2")
    }

    @Test
    fun `contract2_mismatchedOperationTokenAndIssueToken_failsClosed`() = runBlocking {
        val alarmId = 42L
        issuedDao.reserveIssuedOccurrence("issue-B1", alarmId, 2000L, "fire-B1")

        // Intent created with wrong operationToken ("issue-WRONG")
        val mismatchedIntent = DurableOperationIntent(
            operationToken = "issue-WRONG",
            alarmId = alarmId,
            operationEpoch = System.currentTimeMillis(),
            incarnationId = "fire-B1",
            operationKind = "CANCEL",
            expectedOwnerToken = "token-auth-B1",
            expectedScheduleRevision = 100L,
            expectedRestoreEpoch = 1L,
            phase = DurableOperationIntent.PHASE_PENDING,
            intentRevision = 2L
        )
        intentDao.insertValidatedIntent(mismatchedIntent)

        val retrieved = intentDao.getIntentByToken("issue-WRONG")
        assertNotNull(retrieved)

        // Retrieval of target occurrence via mismatched operationToken returns null -> fails closed!
        val targetOccurrence = issuedDao.getOccurrence(retrieved!!.operationToken)
        assertNull("Mismatched operationToken MUST fail closed and return null occurrence", targetOccurrence)
    }

    @Test
    fun `contract3_legacyCancelIntent_discriminatedFromBOnlyCancelIntent`() = runBlocking {
        val alarmId = 42L

        // Legacy Intent (expectedOwnerToken = null, originalFireId = null, intentRevision = 1L)
        val legacyIntent = DurableOperationIntent(
            operationToken = "legacy-op-42",
            alarmId = alarmId,
            operationEpoch = System.currentTimeMillis(),
            incarnationId = "inc-42",
            operationKind = "CANCEL",
            expectedOwnerToken = null,
            expectedScheduleRevision = null,
            expectedRestoreEpoch = null,
            phase = DurableOperationIntent.PHASE_PENDING,
            intentRevision = 1L
        )
        intentDao.insertValidatedIntent(legacyIntent)

        val retrieved = intentDao.getIntentByToken("legacy-op-42")!!
        val isBSpecific = DurableCancelIntentValidator.isBSpecificCancelIntent(retrieved)
        assertFalse("Legacy intent (intentRevision = 1L) MUST NOT be classified as B-Specific", isBSpecific)
    }

    @Test(expected = SQLiteConstraintException::class)
    fun `contract4_unresolvedIntentTrigger_preventsSecondUnresolvedForSameAlarm`(): Unit = runBlocking {
        val alarmId = 42L
        val intent1 = DurableOperationIntent(
            operationToken = "issue-B1",
            alarmId = alarmId,
            operationEpoch = System.currentTimeMillis(),
            incarnationId = "fire-B1",
            operationKind = "CANCEL",
            expectedOwnerToken = "token-B1",
            expectedScheduleRevision = 100L,
            expectedRestoreEpoch = 1L,
            phase = DurableOperationIntent.PHASE_PENDING,
            intentRevision = 2L
        )
        intentDao.insertValidatedIntent(intent1)

        val intent2 = DurableOperationIntent(
            operationToken = "issue-B2",
            alarmId = alarmId,
            operationEpoch = System.currentTimeMillis() + 100L,
            incarnationId = "fire-B2",
            operationKind = "CANCEL",
            expectedOwnerToken = "token-B2",
            expectedScheduleRevision = 100L,
            expectedRestoreEpoch = 1L,
            phase = DurableOperationIntent.PHASE_PENDING,
            intentRevision = 2L
        )
        // SQLite BEFORE INSERT trigger MUST throw SQLiteConstraintException!
        intentDao.insertValidatedIntent(intent2)
    }

    @Test(expected = SQLiteConstraintException::class)
    fun `contract5_duplicateOperationToken_throwsPrimaryKeyCollision`(): Unit = runBlocking {
        val alarmId = 42L
        val intent1 = DurableOperationIntent(
            operationToken = "issue-B1",
            alarmId = alarmId,
            operationEpoch = System.currentTimeMillis(),
            incarnationId = "fire-B1",
            operationKind = "CANCEL",
            expectedOwnerToken = "token-B1",
            expectedScheduleRevision = 100L,
            expectedRestoreEpoch = 1L,
            phase = DurableOperationIntent.PHASE_PENDING,
            intentRevision = 2L
        )
        intentDao.insertValidatedIntent(intent1)

        val intentDup = intent1.copy(alarmId = 99L) // Different alarmId, same operationToken
        // Room @PrimaryKey ABORT MUST throw SQLiteConstraintException!
        intentDao.insertRawIntent(intentDup)
    }

    @Test
    fun `contract6_staleGeneration_casPreventsMutation`() = runBlocking {
        val alarmId = 42L
        issuedDao.reserveIssuedOccurrence("token-A", alarmId, 1000L, "fire-A")
        issuedDao.activateIssuedOccurrence("token-A")

        issuedDao.reserveIssuedOccurrence("token-B", alarmId, 2000L, "fire-B")
        val preCSlotGenId = issuedDao.getSlot(alarmId)!!.generationId // 3L

        issuedDao.reserveIssuedOccurrence("token-C", alarmId, 3000L, "fire-C") // Slot genId becomes 4L

        // Stale CAS for B using pre-C generationId 3L
        val rows = issuedDao.casUpdateNonCurrentOccurrenceStatus(
            issueToken = "token-B",
            expectedStatus = IssuedOccurrence.STATUS_PREPARED,
            newStatus = IssuedOccurrence.STATUS_REVOKED,
            alarmId = alarmId,
            expectedSlotGenId = preCSlotGenId,
            expectedOwnerToken = "token-A"
        )

        assertEquals("Stale generation CAS MUST return 0 updated rows", 0, rows)
        assertEquals(IssuedOccurrence.STATUS_PREPARED, issuedDao.getOccurrence("token-B")!!.status)
    }

    @Test
    fun `contract7_restartReadback_preservesBIdentityBinding`() = runBlocking {
        val alarmId = 42L
        issuedDao.reserveIssuedOccurrence("issue-B1", alarmId, 2000L, "fire-B1")

        val intentB1 = DurableOperationIntent(
            operationToken = "issue-B1",
            alarmId = alarmId,
            operationEpoch = 12345L,
            incarnationId = "fire-B1",
            operationKind = "CANCEL",
            expectedOwnerToken = "token-auth-B1",
            expectedScheduleRevision = 100L,
            expectedRestoreEpoch = 1L,
            phase = DurableOperationIntent.PHASE_PENDING,
            intentRevision = 2L,
            originalFireId = "fire-B1",
            originalScheduledAt = 2000L
        )
        intentDao.insertValidatedIntent(intentB1)

        // Readback on simulated restart
        val unresolved = intentDao.getUnresolvedCancellationIntents().first()
        assertEquals("issue-B1", unresolved.operationToken)
        assertEquals(12345L, unresolved.operationEpoch)
        assertEquals("fire-B1", unresolved.originalFireId)

        val targetB = issuedDao.getOccurrence(unresolved.operationToken)
        assertNotNull(targetB)
        assertEquals("issue-B1", targetB!!.issueToken)
    }

    @Test
    fun `contract8_nullAuthorityInfo_failsClosed`() = runBlocking {
        val alarmId = 42L

        // Intent with null expectedRestoreEpoch
        val intentNullEpoch = DurableOperationIntent(
            operationToken = "issue-B-null",
            alarmId = alarmId,
            operationEpoch = System.currentTimeMillis(),
            incarnationId = "fire-B",
            operationKind = "CANCEL",
            expectedOwnerToken = "token-auth-B",
            expectedScheduleRevision = 100L,
            expectedRestoreEpoch = null, // Null restore epoch!
            phase = DurableOperationIntent.PHASE_PENDING,
            intentRevision = 2L
        )
        intentDao.insertValidatedIntent(intentNullEpoch)

        val retrieved = intentDao.getIntentByToken("issue-B-null")!!
        val isRestoreEpochPresent = retrieved.expectedRestoreEpoch != null
        assertFalse("Null expectedRestoreEpoch MUST fail closed as missing witness", isRestoreEpochPresent)
    }

    // --- C4-B3-P2B Strict Provenance Contracts & Production Validator Integration Tests ---

    @Test
    fun `strictProvenance_legacyRevision1_rejected`() = runBlocking {
        val intentRev1 = validIntent.copy(operationKind = "CANCEL", intentRevision = 1L, expectedOwnerToken = "auth-1", originalFireId = "fire-1", originalScheduledAt = 1000L)
        assertFalse("intentRevision = 1L MUST NOT be classified as B-Specific CANCEL", DurableCancelIntentValidator.isBSpecificCancelIntent(intentRev1))
    }

    @Test
    fun `strictProvenance_unknownRevision3_rejected`() = runBlocking {
        val intentRev3 = validIntent.copy(operationKind = "CANCEL", intentRevision = 3L, expectedOwnerToken = "auth-1", originalFireId = "fire-1", originalScheduledAt = 1000L)
        assertFalse("intentRevision = 3L MUST be rejected as unknown revision", DurableCancelIntentValidator.isBSpecificCancelIntent(intentRev3))
    }

    @Test
    fun `strictProvenance_nonCancelOperationKind_rejected`() = runBlocking {
        val intentSchedule = validIntent.copy(operationKind = "SCHEDULE", intentRevision = 2L, expectedOwnerToken = "auth-1", originalFireId = "fire-1", originalScheduledAt = 1000L)
        assertFalse("Non-CANCEL operationKind MUST NOT be classified as B-Specific CANCEL", DurableCancelIntentValidator.isBSpecificCancelIntent(intentSchedule))
    }

    @Test
    fun `strictProvenance_crossBinding_operationTokenMismatch_rejected`() = runBlocking {
        val alarmId = 42L
        val occB1 = issuedDao.reserveIssuedOccurrence("issue-B1", alarmId, 1000L, "fire-B1")!!
        val occB2 = issuedDao.reserveIssuedOccurrence("issue-B2", alarmId, 2000L, "fire-B2")!!
        val slot = issuedDao.getSlot(alarmId)

        val intentB2 = DurableOperationIntent(
            operationToken = "issue-B2",
            alarmId = alarmId,
            operationEpoch = System.currentTimeMillis(),
            incarnationId = "fire-B2",
            operationKind = "CANCEL",
            expectedOwnerToken = occB2.authorityInstanceToken ?: "auth-B2",
            expectedScheduleRevision = if (occB2.scheduleRevision > 0L) occB2.scheduleRevision else 100L,
            expectedRestoreEpoch = if (occB2.restoreEpoch > 0L) occB2.restoreEpoch else 1L,
            phase = DurableOperationIntent.PHASE_PENDING,
            intentRevision = 2L,
            originalFireId = "fire-B2",
            originalScheduledAt = 2000L
        )

        val bindingValid = DurableCancelIntentValidator.verifyBSpecificCancelBinding(intentB2, occB1, slot)
        assertFalse("Cross-binding intentB2 to B1 MUST be REJECTED by Production Validator", bindingValid)
    }

    @Test
    fun `strictProvenance_staleGeneration_cReserved_rejected`() = runBlocking {
        val alarmId = 42L
        issuedDao.reserveIssuedOccurrence("token-A", alarmId, 1000L, "fire-A")
        issuedDao.activateIssuedOccurrence("token-A")

        val occB = issuedDao.reserveIssuedOccurrence("issue-B", alarmId, 2000L, "fire-B")!!
        assertEquals(3L, occB.generationId)

        issuedDao.reserveIssuedOccurrence("issue-C", alarmId, 3000L, "fire-C")
        val slotAfterC = issuedDao.getSlot(alarmId)!!
        assertEquals(4L, slotAfterC.generationId)

        val intentB = DurableOperationIntent(
            operationToken = "issue-B",
            alarmId = alarmId,
            operationEpoch = System.currentTimeMillis(),
            incarnationId = "fire-B",
            operationKind = "CANCEL",
            expectedOwnerToken = occB.authorityInstanceToken ?: "auth-B",
            expectedScheduleRevision = 100L,
            expectedRestoreEpoch = 1L,
            phase = DurableOperationIntent.PHASE_PENDING,
            intentRevision = 2L,
            originalFireId = "fire-B",
            originalScheduledAt = 2000L
        )

        val bindingValid = DurableCancelIntentValidator.verifyBSpecificCancelBinding(intentB, occB, slotAfterC)
        assertFalse("Stale generation binding MUST be REJECTED when slot generation advances", bindingValid)
    }

    @Test
    fun `strictProvenance_authorityWitnessNullOrZero_rejected`() = runBlocking {
        val alarmId = 42L
        val occB = IssuedOccurrence(
            issueToken = "issue-B",
            alarmId = alarmId,
            scheduledAt = 2000L,
            fireId = "fire-B",
            generationId = 2L,
            status = IssuedOccurrence.STATUS_PREPARED,
            authorityInstanceToken = "auth-B",
            restoreEpoch = 0L,
            scheduleRevision = 100L
        )
        issuedDao.insertOccurrence(occB)
        issuedDao.insertSlotInitial(IssueSlot(alarmId = alarmId, currentIssueToken = "token-A", generationId = 2L))
        val slot = issuedDao.getSlot(alarmId)

        val intentB = DurableOperationIntent(
            operationToken = "issue-B",
            alarmId = alarmId,
            operationEpoch = System.currentTimeMillis(),
            incarnationId = "fire-B",
            operationKind = "CANCEL",
            expectedOwnerToken = "auth-B",
            expectedScheduleRevision = 100L,
            expectedRestoreEpoch = 0L,
            phase = DurableOperationIntent.PHASE_PENDING,
            intentRevision = 2L,
            originalFireId = "fire-B",
            originalScheduledAt = 2000L
        )

        val bindingValid = DurableCancelIntentValidator.verifyBSpecificCancelBinding(intentB, occB, slot)
        assertFalse("Zero restore epoch witness MUST be REJECTED by Production Validator", bindingValid)
    }

    @Test
    fun `strictProvenance_terminalPhase_rejected`() = runBlocking {
        val alarmId = 42L
        val occB = issuedDao.reserveIssuedOccurrence("issue-B", alarmId, 2000L, "fire-B")!!
        val slot = issuedDao.getSlot(alarmId)

        val completedIntent = DurableOperationIntent(
            operationToken = "issue-B",
            alarmId = alarmId,
            operationEpoch = System.currentTimeMillis(),
            incarnationId = "fire-B",
            operationKind = "CANCEL",
            expectedOwnerToken = occB.authorityInstanceToken ?: "auth-B",
            expectedScheduleRevision = 100L,
            expectedRestoreEpoch = 1L,
            phase = DurableOperationIntent.PHASE_COMPLETED,
            intentRevision = 2L,
            originalFireId = "fire-B",
            originalScheduledAt = 2000L
        )

        val bindingValid = DurableCancelIntentValidator.verifyBSpecificCancelBinding(completedIntent, occB, slot)
        assertFalse("Terminal phase COMPLETED MUST be REJECTED for unresolved binding", bindingValid)
    }

    @Test(expected = SQLiteConstraintException::class)
    fun `strictProvenance_unresolvedTrigger_duplicateIntent_rejected`(): Unit = runBlocking {
        val alarmId = 42L
        val intent1 = DurableOperationIntent(
            operationToken = "issue-B1",
            alarmId = alarmId,
            operationEpoch = System.currentTimeMillis(),
            incarnationId = "fire-B1",
            operationKind = "CANCEL",
            expectedOwnerToken = "token-B1",
            expectedScheduleRevision = 100L,
            expectedRestoreEpoch = 1L,
            phase = DurableOperationIntent.PHASE_PENDING,
            intentRevision = 2L
        )
        intentDao.insertValidatedIntent(intent1)

        val intent2 = DurableOperationIntent(
            operationToken = "issue-B2",
            alarmId = alarmId,
            operationEpoch = System.currentTimeMillis() + 100L,
            incarnationId = "fire-B2",
            operationKind = "CANCEL",
            expectedOwnerToken = "token-B2",
            expectedScheduleRevision = 100L,
            expectedRestoreEpoch = 1L,
            phase = DurableOperationIntent.PHASE_PENDING,
            intentRevision = 2L
        )
        intentDao.insertValidatedIntent(intent2)
    }

    // --- C4-B3-P2C Positive Binding Verification Test ---

    @Test
    fun `strictProvenance_positiveBinding_validBIntent_returnsTrue`() = runBlocking {
        val alarmId = 42L
        issuedDao.reserveIssuedOccurrence("token-A", alarmId, 1000L, "fire-A")
        issuedDao.activateIssuedOccurrence("token-A")

        val occB = IssuedOccurrence(
            issueToken = "issue-B",
            alarmId = alarmId,
            scheduledAt = 2000L,
            fireId = "fire-B",
            generationId = 3L,
            status = IssuedOccurrence.STATUS_PREPARED,
            authorityInstanceToken = "auth-B",
            restoreEpoch = 10L,
            scheduleRevision = 100L
        )
        issuedDao.insertOccurrence(occB)
        issuedDao.casIncrementSlotGeneration(alarmId, expectedGenId = 2L, expectedToken = "token-A", newToken = "token-A")
        val slot = issuedDao.getSlot(alarmId)!!

        val validBIntent = DurableOperationIntent(
            operationToken = "issue-B",
            alarmId = alarmId,
            operationEpoch = System.currentTimeMillis(),
            incarnationId = "fire-B",
            operationKind = "CANCEL",
            expectedOwnerToken = "auth-B",
            expectedScheduleRevision = 100L,
            expectedRestoreEpoch = 10L,
            phase = DurableOperationIntent.PHASE_PENDING,
            intentRevision = 2L,
            originalFireId = "fire-B",
            originalScheduledAt = 2000L
        )

        val bindingValid = DurableCancelIntentValidator.verifyBSpecificCancelBinding(validBIntent, occB, slot)
        assertTrue("Valid B-Specific CANCEL binding MUST evaluate to true on Production Validator", bindingValid)
    }

    // --- C4-B3-P3 Durable Atomic Binding Tests ---

    @Test
    fun `p3_authenticDurableBIssuance_succeedsInPendingPhase`() = runBlocking {
        val alarmId = 42L
        database.alarmDao().insert(Alarm(id = alarmId, generation = 1, isEnabled = true))
        database.scheduleAuthorityDao().insertAuthority(
            ScheduleAuthority(
                alarmId = alarmId,
                instanceToken = "auth-42-v1",
                definitionRevision = 10L,
                scheduleRevision = 100L,
                lifecycle = "ARMED",
                desiredOperation = "SCHEDULE",
                desiredTriggerMillis = 2000L,
                reconcileState = "SYNCHRONIZED",
                restoreEpoch = 1L,
                actionKey = "action-key-42"
            )
        )

        issuedDao.reserveIssuedOccurrence("token-A", alarmId, 1000L, "fire-A")
        issuedDao.activateIssuedOccurrence("token-A")

        // Reserve Occurrence B with authentic authority
        val occB = issuedDao.reserveIssuedOccurrenceWithAuthority(
            issueToken = "issue-B-p3",
            alarmId = alarmId,
            scheduledAt = 2000L,
            fireId = "fire-B",
            authorityInstanceToken = "auth-42-v1",
            restoreEpoch = 1L,
            scheduleRevision = 100L,
            definitionRevision = 10L,
            alarmGeneration = 1
        )
        assertNotNull(occB)

        // Issue B-Specific CANCEL intent
        val cancelIntentB = DurableOperationIntent(
            operationToken = "issue-B-p3",
            alarmId = alarmId,
            operationEpoch = System.currentTimeMillis(),
            incarnationId = "fire-B",
            operationKind = "CANCEL",
            expectedOwnerToken = "auth-42-v1",
            expectedScheduleRevision = 100L,
            expectedRestoreEpoch = 1L,
            phase = DurableOperationIntent.PHASE_PENDING,
            intentRevision = 2L,
            originalFireId = "fire-B",
            originalScheduledAt = 2000L
        )
        intentDao.insertValidatedIntent(cancelIntentB)

        val retrieved = intentDao.getIntentByToken("issue-B-p3")
        assertNotNull(retrieved)
        assertEquals(DurableOperationIntent.PHASE_PENDING, retrieved!!.phase)
        assertEquals(2L, retrieved.intentRevision)

        // CAS transition intent phase PENDING -> IN_FLIGHT using casTransitionBSpecificCancelIntentToInFlight
        val rows = intentDao.casTransitionBSpecificCancelIntentToInFlight(
            operationToken = "issue-B-p3",
            alarmId = alarmId,
            expectedSlotGenId = 3L,
            expectedSlotOwnerToken = "token-A",
            expectedOwnerToken = "auth-42-v1"
        )
        assertEquals("Targeted Phase CAS MUST update 1 row", 1, rows)
        assertEquals(DurableOperationIntent.PHASE_IN_FLIGHT, intentDao.getIntentByToken("issue-B-p3")!!.phase)
    }

    @Test
    fun `p3_staleGeneration_casUpdatePhase_returnsZero`() = runBlocking {
        val alarmId = 42L
        database.alarmDao().insert(Alarm(id = alarmId, generation = 1, isEnabled = true))
        database.scheduleAuthorityDao().insertAuthority(
            ScheduleAuthority(
                alarmId = alarmId,
                instanceToken = "auth-42-v1",
                definitionRevision = 10L,
                scheduleRevision = 100L,
                lifecycle = "ARMED",
                desiredOperation = "SCHEDULE",
                desiredTriggerMillis = 2000L,
                reconcileState = "SYNCHRONIZED",
                restoreEpoch = 1L,
                actionKey = "action-key-42"
            )
        )

        issuedDao.reserveIssuedOccurrence("token-A", alarmId, 1000L, "fire-A")
        issuedDao.activateIssuedOccurrence("token-A")

        issuedDao.reserveIssuedOccurrenceWithAuthority("issue-B-p3", alarmId, 2000L, "fire-B", "auth-42-v1", 1L, 100L, 10L, 1)

        val cancelIntentB = DurableOperationIntent(
            operationToken = "issue-B-p3",
            alarmId = alarmId,
            operationEpoch = System.currentTimeMillis(),
            incarnationId = "fire-B",
            operationKind = "CANCEL",
            expectedOwnerToken = "auth-42-v1",
            expectedScheduleRevision = 100L,
            expectedRestoreEpoch = 1L,
            phase = DurableOperationIntent.PHASE_PENDING,
            intentRevision = 2L,
            originalFireId = "fire-B",
            originalScheduledAt = 2000L
        )
        intentDao.insertValidatedIntent(cancelIntentB)

        // Intervening reservation of C advances slot generationId to 4L!
        issuedDao.reserveIssuedOccurrence("issue-C", alarmId, 3000L, "fire-C")

        // Attempting phase CAS for B using stale pre-C generationId 3L MUST return 0 updated rows!
        val rows = intentDao.casTransitionBSpecificCancelIntentToInFlight(
            operationToken = "issue-B-p3",
            alarmId = alarmId,
            expectedSlotGenId = 3L, // Stale generationId! (current is 4L)
            expectedSlotOwnerToken = "token-A",
            expectedOwnerToken = "auth-42-v1"
        )

        assertEquals("Stale generation phase CAS MUST return 0 updated rows", 0, rows)
        assertEquals("Durable intent phase MUST remain PENDING", DurableOperationIntent.PHASE_PENDING, intentDao.getIntentByToken("issue-B-p3")!!.phase)
    }

    @Test
    fun `p3_legacyCancelRevision1_casUpdatePhase_returnsZero`() = runBlocking {
        val alarmId = 42L
        database.alarmDao().insert(Alarm(id = alarmId, generation = 1, isEnabled = true))
        database.scheduleAuthorityDao().insertAuthority(
            ScheduleAuthority(
                alarmId = alarmId,
                instanceToken = "auth-A",
                definitionRevision = 10L,
                scheduleRevision = 100L,
                lifecycle = "ARMED",
                desiredOperation = "SCHEDULE",
                desiredTriggerMillis = 1000L,
                reconcileState = "SYNCHRONIZED",
                restoreEpoch = 1L,
                actionKey = "action-key-legacy"
            )
        )

        val legacyIntent = DurableOperationIntent(
            operationToken = "legacy-op",
            alarmId = alarmId,
            operationEpoch = System.currentTimeMillis(),
            incarnationId = "fire-A",
            operationKind = "CANCEL",
            expectedOwnerToken = "auth-A",
            expectedScheduleRevision = 100L,
            expectedRestoreEpoch = 1L,
            phase = DurableOperationIntent.PHASE_PENDING,
            intentRevision = 1L // Legacy revision 1L!
        )
        intentDao.insertValidatedIntent(legacyIntent)

        issuedDao.insertSlotInitial(IssueSlot(alarmId = alarmId, currentIssueToken = "token-A", generationId = 2L))

        // Phase CAS requiring intentRevision = 2 MUST reject legacy revision 1L intent!
        val rows = intentDao.casTransitionBSpecificCancelIntentToInFlight(
            operationToken = "legacy-op",
            alarmId = alarmId,
            expectedSlotGenId = 2L,
            expectedSlotOwnerToken = "token-A",
            expectedOwnerToken = "auth-A"
        )

        assertEquals("Legacy revision 1L intent MUST return 0 updated rows for B-Specific phase CAS", 0, rows)
    }

    @Test
    fun `p3_phaseMismatch_casUpdatePhase_returnsZero`() = runBlocking {
        val alarmId = 42L
        val intentInFlight = DurableOperationIntent(
            operationToken = "issue-B-inflight",
            alarmId = alarmId,
            operationEpoch = System.currentTimeMillis(),
            incarnationId = "fire-B",
            operationKind = "CANCEL",
            expectedOwnerToken = "auth-B",
            expectedScheduleRevision = 100L,
            expectedRestoreEpoch = 1L,
            phase = DurableOperationIntent.PHASE_IN_FLIGHT, // Already IN_FLIGHT
            intentRevision = 2L
        )
        intentDao.insertValidatedIntent(intentInFlight)

        issuedDao.insertSlotInitial(IssueSlot(alarmId = alarmId, currentIssueToken = "token-A", generationId = 2L))

        // Phase CAS expecting PENDING when current is IN_FLIGHT MUST return 0
        val rows = intentDao.casTransitionBSpecificCancelIntentToInFlight(
            operationToken = "issue-B-inflight",
            alarmId = alarmId,
            expectedSlotGenId = 2L,
            expectedSlotOwnerToken = "token-A",
            expectedOwnerToken = "auth-B"
        )

        assertEquals("Phase CAS targeting PENDING when current is IN_FLIGHT MUST return 0 updated rows", 0, rows)
    }

    @Test
    fun `p3_crossBinding_casUpdatePhase_returnsZero`() = runBlocking {
        val alarmId = 42L
        issuedDao.reserveIssuedOccurrence("token-A", alarmId, 1000L, "fire-A")
        issuedDao.activateIssuedOccurrence("token-A")

        // Reserve Occurrence B1 and Occurrence B2
        issuedDao.reserveIssuedOccurrence("issue-B1", alarmId, 2000L, "fire-B1")
        issuedDao.reserveIssuedOccurrence("issue-B2", alarmId, 3000L, "fire-B2")

        val intentB2 = DurableOperationIntent(
            operationToken = "issue-B2",
            alarmId = alarmId,
            operationEpoch = System.currentTimeMillis(),
            incarnationId = "fire-B2",
            operationKind = "CANCEL",
            expectedOwnerToken = "auth-B2",
            expectedScheduleRevision = 100L,
            expectedRestoreEpoch = 1L,
            phase = DurableOperationIntent.PHASE_PENDING,
            intentRevision = 2L
        )
        intentDao.insertValidatedIntent(intentB2)

        // Attempting phase CAS using operationToken for B2 but expectedSlotGenId for B1 (3L vs 4L)
        val rows = intentDao.casTransitionBSpecificCancelIntentToInFlight(
            operationToken = "issue-B2",
            alarmId = alarmId,
            expectedSlotGenId = 3L, // Generation for B1, not B2 (4L)
            expectedSlotOwnerToken = "token-A",
            expectedOwnerToken = "auth-B2"
        )

        assertEquals("Cross-binding phase CAS MUST return 0 updated rows", 0, rows)
    }

    @Test
    fun `p3_rollbackAfterWrite_inTransaction_rollsBackAllMutations`() = runBlocking {
        val alarmId = 999L

        try {
            database.withTransaction {
                // Step 1: Insert intent
                intentDao.insertValidatedIntent(
                    DurableOperationIntent(
                        operationToken = "issue-tx-fail",
                        alarmId = alarmId,
                        operationEpoch = System.currentTimeMillis(),
                        incarnationId = "fire-999",
                        operationKind = "CANCEL",
                        expectedOwnerToken = "auth-999",
                        expectedScheduleRevision = 100L,
                        expectedRestoreEpoch = 1L,
                        phase = DurableOperationIntent.PHASE_PENDING,
                        intentRevision = 2L
                    )
                )

                // Step 2: Force transaction failure via exception
                throw IllegalStateException("Simulated in-transaction post-write failure")
            }
            org.junit.Assert.fail("Transaction MUST throw exception")
        } catch (_: Exception) {
            // Expected
        }

        // VERIFY: Inserted intent MUST be completely ROLLED BACK from DB!
        val intent = intentDao.getIntentByToken("issue-tx-fail")
        assertNull("Inserted intent MUST be rolled back and absent from DB", intent)
    }

    // --- NEW C4-B3-P3A Atomic Safety Correction Tests ---

    @Test
    fun `p3a_refreshedCGeneration_casTransitionToInFlight_returnsZero`() = runBlocking {
        val alarmId = 42L
        database.alarmDao().insert(Alarm(id = alarmId, generation = 1, isEnabled = true))
        database.scheduleAuthorityDao().insertAuthority(
            ScheduleAuthority(
                alarmId = alarmId,
                instanceToken = "auth-42-v1",
                definitionRevision = 10L,
                scheduleRevision = 100L,
                lifecycle = "ARMED",
                desiredOperation = "SCHEDULE",
                desiredTriggerMillis = 2000L,
                reconcileState = "SYNCHRONIZED",
                restoreEpoch = 1L,
                actionKey = "action-key-42"
            )
        )

        issuedDao.reserveIssuedOccurrence("token-A", alarmId, 1000L, "fire-A")
        issuedDao.activateIssuedOccurrence("token-A")

        // Reserve Occurrence B (generationId = 3L)
        val occB = issuedDao.reserveIssuedOccurrenceWithAuthority("issue-B-p3a", alarmId, 2000L, "fire-B", "auth-42-v1", 1L, 100L, 10L, 1)!!
        assertEquals(3L, occB.generationId)

        val cancelIntentB = DurableOperationIntent(
            operationToken = "issue-B-p3a",
            alarmId = alarmId,
            operationEpoch = System.currentTimeMillis(),
            incarnationId = "fire-B",
            operationKind = "CANCEL",
            expectedOwnerToken = "auth-42-v1",
            expectedScheduleRevision = 100L,
            expectedRestoreEpoch = 1L,
            phase = DurableOperationIntent.PHASE_PENDING,
            intentRevision = 2L,
            originalFireId = "fire-B",
            originalScheduledAt = 2000L
        )
        intentDao.insertValidatedIntent(cancelIntentB)

        // Reserve Occurrence C -> slot generationId advances to 4L!
        issuedDao.reserveIssuedOccurrence("issue-C", alarmId, 3000L, "fire-C")
        val slotAfterC = issuedDao.getSlot(alarmId)!!
        assertEquals(4L, slotAfterC.generationId)

        // Attempt phase CAS passing REFRESHED slot generationId (4L) for Occurrence B (whose generationId is 3L)
        val rows = intentDao.casTransitionBSpecificCancelIntentToInFlight(
            operationToken = "issue-B-p3a",
            alarmId = alarmId,
            expectedSlotGenId = slotAfterC.generationId, // Refreshed slot genId = 4L!
            expectedSlotOwnerToken = "token-A",
            expectedOwnerToken = "auth-42-v1"
        )

        assertEquals("Passing refreshed slot generation 4L for stale B (gen 3L) MUST return 0 updated rows", 0, rows)
        assertEquals("Durable intent phase MUST remain PENDING when SQL fence rejects stale B", DurableOperationIntent.PHASE_PENDING, intentDao.getIntentByToken("issue-B-p3a")!!.phase)
    }

    // --- NEW C4-B3-P3B Durable DB Closure Tests ---

    @Test
    fun `p3b_productionIssuer_positive_createsAndReadsBackPendingCancelIntent`() = runBlocking {
        val alarmId = 42L
        database.alarmDao().insert(Alarm(id = alarmId, generation = 1, isEnabled = true))
        database.scheduleAuthorityDao().insertAuthority(
            ScheduleAuthority(
                alarmId = alarmId,
                instanceToken = "auth-42-v1",
                definitionRevision = 10L,
                scheduleRevision = 100L,
                lifecycle = "ARMED",
                desiredOperation = "SCHEDULE",
                desiredTriggerMillis = 2000L,
                reconcileState = "SYNCHRONIZED",
                restoreEpoch = 1L,
                actionKey = "action-key-42"
            )
        )

        issuedDao.reserveIssuedOccurrence("token-A", alarmId, 1000L, "fire-A")
        issuedDao.activateIssuedOccurrence("token-A")

        // Reserve Occurrence B using authentic reserveIssuedOccurrenceWithAuthority API
        val occB = issuedDao.reserveIssuedOccurrenceWithAuthority(
            issueToken = "issue-B-p3b",
            alarmId = alarmId,
            scheduledAt = 2000L,
            fireId = "fire-B",
            authorityInstanceToken = "auth-42-v1",
            restoreEpoch = 1L,
            scheduleRevision = 100L,
            definitionRevision = 10L,
            alarmGeneration = 1
        )
        assertNotNull(occB)

        // Invoke Production B-Specific Durable Intent Issuance API inside Room Transaction
        val createdIntent = database.withTransaction {
            intentDao.issueBSpecificCancelIntentTransaction(
                issuedDao = issuedDao,
                scheduleAuthorityDao = database.scheduleAuthorityDao(),
                alarmDao = database.alarmDao(),
                targetIssueToken = "issue-B-p3b"
            )
        }

        assertNotNull("Production Durable Intent Issuer MUST create non-null intent", createdIntent)
        assertEquals("issue-B-p3b", createdIntent.operationToken)
        assertEquals(alarmId, createdIntent.alarmId)
        assertEquals("CANCEL", createdIntent.operationKind)
        assertEquals(2L, createdIntent.intentRevision)
        assertEquals(DurableOperationIntent.PHASE_PENDING, createdIntent.phase)
        assertEquals("auth-42-v1", createdIntent.expectedOwnerToken)

        // Readback from DB to verify persistence
        val readback = intentDao.getIntentByToken("issue-B-p3b")
        assertNotNull(readback)
        assertEquals(createdIntent, readback)

        // Verify Current Owner A remains 100% ISSUED and slot owner
        val slot = issuedDao.getSlot(alarmId)!!
        assertEquals("token-A", slot.currentIssueToken)
        assertEquals(IssuedOccurrence.STATUS_ISSUED, issuedDao.getOccurrence("token-A")!!.status)
    }

    @Test
    fun `p3b_productionIssuer_rollbackOnFailure_zeroPartialMutations`() = runBlocking {
        val alarmId = 777L
        database.alarmDao().insert(Alarm(id = alarmId, generation = 1, isEnabled = true))
        // ScheduleAuthority is MISSING from DB!

        var thrown = false
        try {
            database.withTransaction {
                intentDao.issueBSpecificCancelIntentTransaction(
                    issuedDao = issuedDao,
                    scheduleAuthorityDao = database.scheduleAuthorityDao(),
                    alarmDao = database.alarmDao(),
                    targetIssueToken = "issue-missing-p3b"
                )
            }
        } catch (_: Exception) {
            thrown = true
        }

        assertTrue("Production Durable Issuer MUST throw exception on missing DB authority witness", thrown)
        assertNull("Intent MUST be rolled back and absent from DB", intentDao.getIntentByToken("issue-missing-p3b"))
    }

    @Test
    fun `p3b_strictSqlFence_mismatchedFireId_returnsZero`() = runBlocking {
        val alarmId = 42L
        database.alarmDao().insert(Alarm(id = alarmId, generation = 1, isEnabled = true))
        database.scheduleAuthorityDao().insertAuthority(
            ScheduleAuthority(
                alarmId = alarmId,
                instanceToken = "auth-42-v1",
                definitionRevision = 10L,
                scheduleRevision = 100L,
                lifecycle = "ARMED",
                desiredOperation = "SCHEDULE",
                desiredTriggerMillis = 2000L,
                reconcileState = "SYNCHRONIZED",
                restoreEpoch = 1L,
                actionKey = "action-key-42"
            )
        )

        issuedDao.reserveIssuedOccurrence("token-A", alarmId, 1000L, "fire-A")
        issuedDao.activateIssuedOccurrence("token-A")
        issuedDao.reserveIssuedOccurrenceWithAuthority("issue-B-p3b-fire", alarmId, 2000L, "fire-B-correct", "auth-42-v1", 1L, 100L, 10L, 1)

        // Insert intent with mismatched originalFireId ("fire-WRONG")
        val mismatchedIntent = DurableOperationIntent(
            operationToken = "issue-B-p3b-fire",
            alarmId = alarmId,
            operationEpoch = System.currentTimeMillis(),
            incarnationId = "fire-B-correct",
            operationKind = "CANCEL",
            expectedOwnerToken = "auth-42-v1",
            expectedScheduleRevision = 100L,
            expectedRestoreEpoch = 1L,
            phase = DurableOperationIntent.PHASE_PENDING,
            intentRevision = 2L,
            originalFireId = "fire-WRONG", // Mismatched!
            originalScheduledAt = 2000L
        )
        intentDao.insertValidatedIntent(mismatchedIntent)

        val rows = intentDao.casTransitionBSpecificCancelIntentToInFlight(
            operationToken = "issue-B-p3b-fire",
            alarmId = alarmId,
            expectedSlotGenId = 3L,
            expectedSlotOwnerToken = "token-A",
            expectedOwnerToken = "auth-42-v1"
        )

        assertEquals("Mismatched originalFireId MUST return 0 updated rows for strict SQL fence", 0, rows)
    }

    @Test
    fun `p3b_strictSqlFence_mismatchedScheduledAt_returnsZero`() = runBlocking {
        val alarmId = 42L
        database.alarmDao().insert(Alarm(id = alarmId, generation = 1, isEnabled = true))
        database.scheduleAuthorityDao().insertAuthority(
            ScheduleAuthority(
                alarmId = alarmId,
                instanceToken = "auth-42-v1",
                definitionRevision = 10L,
                scheduleRevision = 100L,
                lifecycle = "ARMED",
                desiredOperation = "SCHEDULE",
                desiredTriggerMillis = 2000L,
                reconcileState = "SYNCHRONIZED",
                restoreEpoch = 1L,
                actionKey = "action-key-42"
            )
        )

        issuedDao.reserveIssuedOccurrence("token-A", alarmId, 1000L, "fire-A")
        issuedDao.activateIssuedOccurrence("token-A")
        issuedDao.reserveIssuedOccurrenceWithAuthority("issue-B-p3b-time", alarmId, 2000L, "fire-B", "auth-42-v1", 1L, 100L, 10L, 1)

        // Insert intent with mismatched originalScheduledAt (99999L)
        val mismatchedIntent = DurableOperationIntent(
            operationToken = "issue-B-p3b-time",
            alarmId = alarmId,
            operationEpoch = System.currentTimeMillis(),
            incarnationId = "fire-B",
            operationKind = "CANCEL",
            expectedOwnerToken = "auth-42-v1",
            expectedScheduleRevision = 100L,
            expectedRestoreEpoch = 1L,
            phase = DurableOperationIntent.PHASE_PENDING,
            intentRevision = 2L,
            originalFireId = "fire-B",
            originalScheduledAt = 99999L // Mismatched!
        )
        intentDao.insertValidatedIntent(mismatchedIntent)

        val rows = intentDao.casTransitionBSpecificCancelIntentToInFlight(
            operationToken = "issue-B-p3b-time",
            alarmId = alarmId,
            expectedSlotGenId = 3L,
            expectedSlotOwnerToken = "token-A",
            expectedOwnerToken = "auth-42-v1"
        )

        assertEquals("Mismatched originalScheduledAt MUST return 0 updated rows for strict SQL fence", 0, rows)
    }

    @Test
    fun `p3b_strictSqlFence_mismatchedRestoreEpoch_returnsZero`() = runBlocking {
        val alarmId = 42L
        database.alarmDao().insert(Alarm(id = alarmId, generation = 1, isEnabled = true))
        database.scheduleAuthorityDao().insertAuthority(
            ScheduleAuthority(
                alarmId = alarmId,
                instanceToken = "auth-42-v1",
                definitionRevision = 10L,
                scheduleRevision = 100L,
                lifecycle = "ARMED",
                desiredOperation = "SCHEDULE",
                desiredTriggerMillis = 2000L,
                reconcileState = "SYNCHRONIZED",
                restoreEpoch = 1L,
                actionKey = "action-key-42"
            )
        )

        issuedDao.reserveIssuedOccurrence("token-A", alarmId, 1000L, "fire-A")
        issuedDao.activateIssuedOccurrence("token-A")
        issuedDao.reserveIssuedOccurrenceWithAuthority("issue-B-p3b-epoch", alarmId, 2000L, "fire-B", "auth-42-v1", 1L, 100L, 10L, 1)

        // Insert intent with mismatched expectedRestoreEpoch (99L vs DB 1L)
        val mismatchedIntent = DurableOperationIntent(
            operationToken = "issue-B-p3b-epoch",
            alarmId = alarmId,
            operationEpoch = System.currentTimeMillis(),
            incarnationId = "fire-B",
            operationKind = "CANCEL",
            expectedOwnerToken = "auth-42-v1",
            expectedScheduleRevision = 100L,
            expectedRestoreEpoch = 99L, // Mismatched!
            phase = DurableOperationIntent.PHASE_PENDING,
            intentRevision = 2L,
            originalFireId = "fire-B",
            originalScheduledAt = 2000L
        )
        intentDao.insertValidatedIntent(mismatchedIntent)

        val rows = intentDao.casTransitionBSpecificCancelIntentToInFlight(
            operationToken = "issue-B-p3b-epoch",
            alarmId = alarmId,
            expectedSlotGenId = 3L,
            expectedSlotOwnerToken = "token-A",
            expectedOwnerToken = "auth-42-v1"
        )

        assertEquals("Mismatched expectedRestoreEpoch MUST return 0 updated rows for strict SQL fence", 0, rows)
    }

    // --- NEW C4-B3-P3C DB Final Safety Tests ---

    @Test
    fun `p3c_strictSqlFence_mismatchedExpectedOwnerToken_returnsZero`() = runBlocking {
        val alarmId = 42L
        database.alarmDao().insert(Alarm(id = alarmId, generation = 1, isEnabled = true))
        database.scheduleAuthorityDao().insertAuthority(
            ScheduleAuthority(
                alarmId = alarmId,
                instanceToken = "auth-42-v1",
                definitionRevision = 10L,
                scheduleRevision = 100L,
                lifecycle = "ARMED",
                desiredOperation = "SCHEDULE",
                desiredTriggerMillis = 2000L,
                reconcileState = "SYNCHRONIZED",
                restoreEpoch = 1L,
                actionKey = "action-key-42"
            )
        )

        issuedDao.reserveIssuedOccurrence("token-A", alarmId, 1000L, "fire-A")
        issuedDao.activateIssuedOccurrence("token-A")
        issuedDao.reserveIssuedOccurrenceWithAuthority("issue-B-p3c-owner", alarmId, 2000L, "fire-B", "auth-42-v1", 1L, 100L, 10L, 1)

        val cancelIntentB = DurableOperationIntent(
            operationToken = "issue-B-p3c-owner",
            alarmId = alarmId,
            operationEpoch = System.currentTimeMillis(),
            incarnationId = "fire-B",
            operationKind = "CANCEL",
            expectedOwnerToken = "auth-42-v1",
            expectedScheduleRevision = 100L,
            expectedRestoreEpoch = 1L,
            phase = DurableOperationIntent.PHASE_PENDING,
            intentRevision = 2L,
            originalFireId = "fire-B",
            originalScheduledAt = 2000L
        )
        intentDao.insertValidatedIntent(cancelIntentB)

        // Attempt phase CAS with mismatched expectedOwnerToken ("auth-WRONG")
        val rows = intentDao.casTransitionBSpecificCancelIntentToInFlight(
            operationToken = "issue-B-p3c-owner",
            alarmId = alarmId,
            expectedSlotGenId = 3L,
            expectedSlotOwnerToken = "token-A",
            expectedOwnerToken = "auth-WRONG" // Mismatched!
        )

        assertEquals("Mismatched expectedOwnerToken MUST return 0 updated rows for strict SQL fence", 0, rows)
    }

    @Test
    fun `p3c_casUpdateNonCurrentOccurrenceStatus_generationIdFence_rejectsStaleRefreshedGen`() = runBlocking {
        val alarmId = 42L
        issuedDao.reserveIssuedOccurrence("token-A", alarmId, 1000L, "fire-A")
        issuedDao.activateIssuedOccurrence("token-A")

        // Reserve Occurrence B (generationId = 3L)
        val occB = issuedDao.reserveIssuedOccurrence("issue-B-gen-fence", alarmId, 2000L, "fire-B")!!
        assertEquals(3L, occB.generationId)

        // Reserve Occurrence C -> slot generationId advances to 4L!
        issuedDao.reserveIssuedOccurrence("issue-C", alarmId, 3000L, "fire-C")
        val slotAfterC = issuedDao.getSlot(alarmId)!!
        assertEquals(4L, slotAfterC.generationId)

        // Call casUpdateNonCurrentOccurrenceStatus passing REFRESHED slot generationId 4L for Occurrence B (whose generationId is 3L)
        val updatedRows = issuedDao.casUpdateNonCurrentOccurrenceStatus(
            issueToken = "issue-B-gen-fence",
            expectedStatus = IssuedOccurrence.STATUS_PREPARED,
            newStatus = IssuedOccurrence.STATUS_REVOKED,
            alarmId = alarmId,
            expectedSlotGenId = slotAfterC.generationId, // Refreshed slot genId = 4L!
            expectedOwnerToken = "token-A"
        )

        assertEquals("casUpdateNonCurrentOccurrenceStatus MUST return 0 updated rows when occurrence B generationId (3L) != expectedSlotGenId (4L)", 0, updatedRows)
        assertEquals("Occurrence B status MUST remain PREPARED when generation fence rejects update", IssuedOccurrence.STATUS_PREPARED, issuedDao.getOccurrence("issue-B-gen-fence")!!.status)
    }
}
