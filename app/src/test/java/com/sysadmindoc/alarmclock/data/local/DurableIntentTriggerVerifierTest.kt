package com.sysadmindoc.alarmclock.data.local

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class DurableIntentTriggerVerifierTest {

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
    fun `all six expected triggers produce Valid verification result`() {
        val result = verifier.verifyTriggers(database.openHelper.writableDatabase)
        assertEquals(TriggerVerificationResult.Valid, result)
    }

    @Test
    fun `missing trigger produces Invalid verification result`() {
        val db = database.openHelper.writableDatabase
        db.execSQL("DROP TRIGGER IF EXISTS `trigger_durable_intent_phase_insert`")

        val result = verifier.verifyTriggers(db)
        assertTrue(result is TriggerVerificationResult.Invalid)
        val invalid = result as TriggerVerificationResult.Invalid
        assertTrue(invalid.missingOrAlteredTriggers.any { it.contains("trigger_durable_intent_phase_insert") })
    }

    @Test
    fun `same-name no-op trigger containing keywords in comments is rejected as Invalid`() {
        val db = database.openHelper.writableDatabase
        db.execSQL("DROP TRIGGER IF EXISTS `trigger_durable_intent_phase_insert`")
        // No-op trigger with keywords hidden inside comments
        db.execSQL(
            """
            CREATE TRIGGER `trigger_durable_intent_phase_insert`
            BEFORE INSERT ON `durable_operation_intent`
            BEGIN
                /* PENDING IN_FLIGHT COMPLETED RAISE(ABORT) */
                SELECT 1;
            END
            """.trimIndent()
        )

        val result = verifier.verifyTriggers(db)
        assertTrue(result is TriggerVerificationResult.Invalid)
        val invalid = result as TriggerVerificationResult.Invalid
        assertTrue(invalid.missingOrAlteredTriggers.any { it.contains("trigger_durable_intent_phase_insert") })
    }

    @Test
    fun `trigger with modified WHERE condition is rejected as Invalid`() {
        val db = database.openHelper.writableDatabase
        db.execSQL("DROP TRIGGER IF EXISTS `trigger_durable_intent_unresolved_unique_insert`")
        // Altered WHERE condition: missing IN_FLIGHT
        db.execSQL(
            """
            CREATE TRIGGER `trigger_durable_intent_unresolved_unique_insert`
            BEFORE INSERT ON `durable_operation_intent`
            FOR EACH ROW
            WHEN NEW.phase IN ('PENDING')
            BEGIN
                SELECT RAISE(ABORT, 'SQLite Constraint Error: An unresolved operation intent already exists for this alarmId')
                WHERE EXISTS (
                    SELECT 1 FROM `durable_operation_intent`
                    WHERE `alarmId` = NEW.alarmId
                      AND `phase` IN ('PENDING', 'IN_FLIGHT')
                      AND `operationToken` != NEW.operationToken
                );
            END
            """.trimIndent()
        )

        val result = verifier.verifyTriggers(db)
        assertTrue(result is TriggerVerificationResult.Invalid)
        val invalid = result as TriggerVerificationResult.Invalid
        assertTrue(invalid.missingOrAlteredTriggers.any { it.contains("trigger_durable_intent_unresolved_unique_insert") })
    }

    @Test
    fun `trigger with modified operator is rejected as Invalid`() {
        val db = database.openHelper.writableDatabase
        db.execSQL("DROP TRIGGER IF EXISTS `trigger_durable_intent_identity_insert`")
        // Altered operator: alarmId >= 0 instead of <= 0
        db.execSQL(
            """
            CREATE TRIGGER `trigger_durable_intent_identity_insert`
            BEFORE INSERT ON `durable_operation_intent`
            FOR EACH ROW
            WHEN length(trim(NEW.operationToken)) = 0
              OR length(trim(NEW.incarnationId)) = 0
              OR length(trim(NEW.operationKind)) = 0
              OR NEW.alarmId >= 0
              OR NEW.operationEpoch <= 0
              OR NEW.intentRevision <= 0
            BEGIN
                SELECT RAISE(ABORT, 'SQLite Constraint Error: Invalid identity or epoch invariants');
            END
            """.trimIndent()
        )

        val result = verifier.verifyTriggers(db)
        assertTrue(result is TriggerVerificationResult.Invalid)
        val invalid = result as TriggerVerificationResult.Invalid
        assertTrue(invalid.missingOrAlteredTriggers.any { it.contains("trigger_durable_intent_identity_insert") })
    }

    @Test
    fun `partial trigger set produces Invalid verification result`() {
        val db = database.openHelper.writableDatabase
        db.execSQL("DROP TRIGGER IF EXISTS `trigger_durable_intent_identity_insert`")
        db.execSQL("DROP TRIGGER IF EXISTS `trigger_durable_intent_identity_update`")

        val result = verifier.verifyTriggers(db)
        assertTrue(result is TriggerVerificationResult.Invalid)
        val invalid = result as TriggerVerificationResult.Invalid
        assertEquals(2, invalid.missingOrAlteredTriggers.size)
    }

    @Test
    fun `reopen with trigger drift is detected as Invalid`() {
        val dbFile = File(context.cacheDir, "drift_test.db")
        if (dbFile.exists()) dbFile.delete()

        var fileDb = Room.databaseBuilder(context, AlarmDatabase::class.java, dbFile.absolutePath)
            .allowMainThreadQueries()
            .addCallback(AlarmDatabase.DATABASE_CALLBACK)
            .build()

        val db = fileDb.openHelper.writableDatabase
        db.execSQL("DROP TRIGGER IF EXISTS `trigger_durable_intent_phase_update`")
        db.execSQL("CREATE TRIGGER `trigger_durable_intent_phase_update` BEFORE UPDATE ON `durable_operation_intent` BEGIN SELECT 1; END;")
        fileDb.close()

        // Reopen database file
        fileDb = Room.databaseBuilder(context, AlarmDatabase::class.java, dbFile.absolutePath)
            .allowMainThreadQueries()
            .addCallback(AlarmDatabase.DATABASE_CALLBACK)
            .build()

        val reopenedDb = fileDb.openHelper.writableDatabase
        val result = verifier.verifyTriggers(reopenedDb)

        fileDb.close()
        if (dbFile.exists()) dbFile.delete()

        assertTrue("Trigger drift MUST be detected on reopened DB", result is TriggerVerificationResult.Invalid)
    }
}
