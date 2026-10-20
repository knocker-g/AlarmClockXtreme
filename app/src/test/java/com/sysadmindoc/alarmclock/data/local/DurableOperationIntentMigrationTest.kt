package com.sysadmindoc.alarmclock.data.local

import android.app.Application
import android.content.Context
import android.database.sqlite.SQLiteConstraintException
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.sysadmindoc.alarmclock.data.local.entity.ScheduleAuthority
import com.sysadmindoc.alarmclock.data.local.entity.ScheduleAuthorityMeta
import com.sysadmindoc.alarmclock.data.model.Alarm
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class DurableOperationIntentMigrationTest {

    private lateinit var context: Context
    private lateinit var database: AlarmDatabase
    private val verifier = DurableIntentTriggerVerifier()

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        database = Room.inMemoryDatabaseBuilder(context, AlarmDatabase::class.java)
            .allowMainThreadQueries()
            .addCallback(AlarmDatabase.DATABASE_CALLBACK)
            .build()
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun `v28 database migrates to v29 seamlessly and preserves existing Alarms Authority and Meta`() = runBlocking {
        val db = database.openHelper.writableDatabase

        // Seed v28 data into database
        val alarm = Alarm(id = 42L, hour = 7, minute = 30, label = "v28 Migration Alarm", isEnabled = true)
        database.alarmDao().insert(alarm)

        val meta = ScheduleAuthorityMeta(id = 1, restoreEpoch = 100L, bootstrapState = "ENFORCED")
        database.scheduleAuthorityDao().insertMeta(meta)

        val authority = ScheduleAuthority(
            alarmId = 42L,
            instanceToken = "token-v28-42",
            definitionRevision = 1L,
            scheduleRevision = 1L,
            lifecycle = "LIVE",
            desiredOperation = "SET",
            desiredTriggerMillis = 1700000000000L,
            reconcileState = "SETTLED",
            restoreEpoch = 100L,
            actionKey = null
        )
        database.scheduleAuthorityDao().insertAuthority(authority)

        // Execute MIGRATION_28_29 DDL on real SQLite database
        AlarmDatabase.MIGRATION_28_29.migrate(db)

        // 1. Verify Room opened and remains open cleanly
        assertTrue(db.isOpen)

        // 2. Verify v28 data is preserved intact
        val alarmCount = database.alarmDao().getAll().size
        assertEquals(1, alarmCount)
        assertEquals("v28 Migration Alarm", database.alarmDao().getAll().first().label)

        val retrievedAuthority = database.scheduleAuthorityDao().getAuthorityByAlarmId(42L)
        assertNotNull(retrievedAuthority)
        assertEquals("token-v28-42", retrievedAuthority?.instanceToken)

        val retrievedMeta = database.scheduleAuthorityDao().getMetaById(1)
        assertNotNull(retrievedMeta)
        assertEquals("ENFORCED", retrievedMeta?.bootstrapState)

        // 3. Verify triggers installed on migrated DB
        assertEquals(TriggerVerificationResult.Valid, verifier.verifyTriggers(db))

        // 4. Verify raw SQL duplicate unresolved insert is rejected by trigger on migrated DB
        var unresolvedInsertRejected = false
        try {
            db.execSQL(
                "INSERT INTO durable_operation_intent (operationToken, alarmId, operationEpoch, incarnationId, operationKind, phase, intentRevision) " +
                    "VALUES ('tok-mig-1', 42, 1, 'inc-1', 'SET', 'PENDING', 1)"
            )
            db.execSQL(
                "INSERT INTO durable_operation_intent (operationToken, alarmId, operationEpoch, incarnationId, operationKind, phase, intentRevision) " +
                    "VALUES ('tok-mig-2', 42, 2, 'inc-2', 'SET', 'IN_FLIGHT', 1)"
            )
        } catch (_: SQLiteConstraintException) {
            unresolvedInsertRejected = true
        }
        assertTrue("Migrated DB must reject second unresolved intent via SQLite trigger", unresolvedInsertRejected)

        // 5. Verify invalid phase is rejected on migrated DB
        var invalidPhaseRejected = false
        try {
            db.execSQL(
                "INSERT INTO durable_operation_intent (operationToken, alarmId, operationEpoch, incarnationId, operationKind, phase, intentRevision) " +
                    "VALUES ('tok-bad-phase', 99, 1, 'inc-99', 'SET', 'INVALID_PHASE_STRING', 1)"
            )
        } catch (_: SQLiteConstraintException) {
            invalidPhaseRejected = true
        }
        assertTrue("Migrated DB must reject invalid phase via SQLite trigger", invalidPhaseRejected)
    }
}
