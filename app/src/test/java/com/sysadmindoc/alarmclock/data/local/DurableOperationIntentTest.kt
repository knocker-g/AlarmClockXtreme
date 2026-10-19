package com.sysadmindoc.alarmclock.data.local

import android.app.Application
import android.content.Context
import android.database.sqlite.SQLiteConstraintException
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.sysadmindoc.alarmclock.data.local.entity.DurableOperationIntent
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
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class DurableOperationIntentTest {

    private lateinit var context: Context
    private lateinit var database: AlarmDatabase
    private lateinit var intentDao: DurableOperationIntentDao

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
            .addCallback(object : androidx.room.RoomDatabase.Callback() {
                override fun onCreate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                    db.execSQL("DROP TABLE IF EXISTS `durable_operation_intent`")
                    db.execSQL(
                        """
                        CREATE TABLE IF NOT EXISTS `durable_operation_intent` (
                            `operationToken` TEXT NOT NULL,
                            `alarmId` INTEGER NOT NULL,
                            `operationEpoch` INTEGER NOT NULL,
                            `incarnationId` TEXT NOT NULL,
                            `operationKind` TEXT NOT NULL,
                            `expectedOwnerToken` TEXT,
                            `expectedScheduleRevision` INTEGER,
                            `expectedRestoreEpoch` INTEGER,
                            `phase` TEXT NOT NULL,
                            `intentRevision` INTEGER NOT NULL,
                            PRIMARY KEY(`operationToken`),
                            CHECK(length(trim(`operationToken`)) > 0),
                            CHECK(length(trim(`incarnationId`)) > 0),
                            CHECK(length(trim(`operationKind`)) > 0),
                            CHECK(`alarmId` > 0),
                            CHECK(`operationEpoch` > 0),
                            CHECK(`intentRevision` > 0),
                            CHECK(`phase` IN ('PENDING', 'IN_FLIGHT', 'COMPLETED', 'ABORTED', 'EXPIRED'))
                        )
                        """.trimIndent()
                    )
                    db.execSQL(
                        """
                        CREATE UNIQUE INDEX IF NOT EXISTS `index_durable_operation_intent_unresolved_alarm`
                        ON `durable_operation_intent` (`alarmId`)
                        WHERE `phase` IN ('PENDING', 'IN_FLIGHT')
                        """.trimIndent()
                    )
                }
            })
            .build()
        intentDao = database.durableOperationIntentDao()
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun `G0 - insertRawIntent and getByToken stores and reads durable operation intent correctly`() = runBlocking {
        intentDao.insertRawIntent(validIntent)

        val retrieved = intentDao.getIntentByToken("intent-token-100")
        assertNotNull(retrieved)
        assertEquals(validIntent, retrieved)
    }

    @Test(expected = SQLiteConstraintException::class)
    fun `G1 - SQLite partial UNIQUE index rejects raw insert of second unresolved intent for same alarmId`(): Unit = runBlocking {
        val unresolved1 = validIntent.copy(operationToken = "tok-1", phase = DurableOperationIntent.PHASE_PENDING)
        val unresolved2 = validIntent.copy(operationToken = "tok-2", phase = DurableOperationIntent.PHASE_IN_FLIGHT)

        intentDao.insertRawIntent(unresolved1)
        // SQLite's partial UNIQUE index (index_durable_operation_intent_unresolved_alarm) rejects second unresolved insert
        intentDao.insertRawIntent(unresolved2)
    }

    @Test(expected = SQLiteConstraintException::class)
    fun `G2 - SQLite CHECK constraint rejects raw insert of unknown phase`(): Unit = runBlocking {
        val invalidPhaseIntent = validIntent.copy(phase = "UNKNOWN_PHASE_STRING")
        // SQLite's CHECK(phase IN ('PENDING', 'IN_FLIGHT', 'COMPLETED', 'ABORTED', 'EXPIRED')) constraint rejects raw insert
        intentDao.insertRawIntent(invalidPhaseIntent)
    }

    @Test(expected = SQLiteConstraintException::class)
    fun `G3 - SQLite CHECK constraint rejects raw insert of blank operationToken`(): Unit = runBlocking {
        val blankTokenIntent = validIntent.copy(operationToken = "   ")
        // SQLite's CHECK(length(trim(operationToken)) > 0) constraint rejects raw insert
        intentDao.insertRawIntent(blankTokenIntent)
    }

    @Test(expected = SQLiteConstraintException::class)
    fun `G3 - SQLite CHECK constraint rejects raw insert of nonpositive alarmId`(): Unit = runBlocking {
        val zeroAlarmIdIntent = validIntent.copy(alarmId = 0L)
        // SQLite's CHECK(alarmId > 0) constraint rejects raw insert
        intentDao.insertRawIntent(zeroAlarmIdIntent)
    }

    @Test(expected = SQLiteConstraintException::class)
    fun `G3 - SQLite CHECK constraint rejects raw insert of nonpositive operationEpoch`(): Unit = runBlocking {
        val zeroEpochIntent = validIntent.copy(operationEpoch = 0L)
        // SQLite's CHECK(operationEpoch > 0) constraint rejects raw insert
        intentDao.insertRawIntent(zeroEpochIntent)
    }

    @Test
    fun `G4 - Two different alarms with unresolved operations succeeds at SQLite layer`() = runBlocking {
        val alarm42Intent = validIntent.copy(alarmId = 42L, operationToken = "tok-42", phase = DurableOperationIntent.PHASE_PENDING)
        val alarm99Intent = validIntent.copy(alarmId = 99L, operationToken = "tok-99", phase = DurableOperationIntent.PHASE_PENDING)

        intentDao.insertRawIntent(alarm42Intent)
        intentDao.insertRawIntent(alarm99Intent)

        assertEquals(1, intentDao.getUnresolvedIntentsByAlarmId(42L).size)
        assertEquals(1, intentDao.getUnresolvedIntentsByAlarmId(99L).size)
    }

    @Test
    fun `G5 - Historical terminal intents are retained for replay audit`() = runBlocking {
        val completed = validIntent.copy(operationToken = "tok-comp", phase = DurableOperationIntent.PHASE_COMPLETED)
        val expired = validIntent.copy(operationToken = "tok-exp", phase = DurableOperationIntent.PHASE_EXPIRED)

        intentDao.insertRawIntent(completed)
        intentDao.insertRawIntent(expired)

        val all = intentDao.getAllIntents()
        assertEquals(2, all.size)
    }

    @Test
    fun `G6 - Collision on operationToken cannot erase previous row due to ABORT strategy`() = runBlocking {
        intentDao.insertRawIntent(validIntent)

        var thrown = false
        try {
            val duplicate = validIntent.copy(operationEpoch = 2L, phase = DurableOperationIntent.PHASE_COMPLETED)
            intentDao.insertRawIntent(duplicate)
        } catch (_: SQLiteConstraintException) {
            thrown = true
        }

        assertTrue("SQLiteConstraintException must be thrown on duplicate token", thrown)
        val existing = intentDao.getIntentByToken("intent-token-100")
        assertEquals(validIntent, existing)
    }

    @Test
    fun `G7 - Valid phase vocabulary helper functions correctly`() {
        assertTrue(DurableOperationIntent.isValidPhase("PENDING"))
        assertTrue(DurableOperationIntent.isValidPhase("IN_FLIGHT"))
        assertTrue(DurableOperationIntent.isValidPhase("COMPLETED"))
        assertTrue(DurableOperationIntent.isValidPhase("ABORTED"))
        assertTrue(DurableOperationIntent.isValidPhase("EXPIRED"))

        assertFalse(DurableOperationIntent.isValidPhase("INVALID_PHASE"))
        assertFalse(DurableOperationIntent.isValidPhase(""))
    }
}
