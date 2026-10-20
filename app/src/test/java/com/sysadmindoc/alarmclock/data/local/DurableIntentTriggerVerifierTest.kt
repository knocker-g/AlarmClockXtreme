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
    fun `all correct triggers produce Valid verification result`() {
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
    fun `same-name altered trigger definition produces Invalid verification result`() {
        val db = database.openHelper.writableDatabase
        db.execSQL("DROP TRIGGER IF EXISTS `trigger_durable_intent_phase_insert`")
        // Recreate same-name trigger with bogus definition lacking RAISE(ABORT)
        db.execSQL("CREATE TRIGGER `trigger_durable_intent_phase_insert` BEFORE INSERT ON `durable_operation_intent` BEGIN SELECT 1; END;")

        val result = verifier.verifyTriggers(db)
        assertTrue(result is TriggerVerificationResult.Invalid)
        val invalid = result as TriggerVerificationResult.Invalid
        assertTrue(invalid.missingOrAlteredTriggers.any { it.contains("trigger_durable_intent_phase_insert") })
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
}
