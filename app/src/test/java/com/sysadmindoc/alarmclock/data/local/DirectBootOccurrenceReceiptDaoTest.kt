package com.sysadmindoc.alarmclock.data.local

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.sysadmindoc.alarmclock.data.local.entity.DirectBootOccurrenceReceipt
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.nio.charset.StandardCharsets

@RunWith(AndroidJUnit4::class)
class DirectBootOccurrenceReceiptDaoTest {

    private lateinit var database: AlarmDatabase
    private lateinit var dao: DirectBootOccurrenceReceiptDao

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        database = Room.inMemoryDatabaseBuilder(context, AlarmDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = database.directBootOccurrenceReceiptDao()
    }

    @After
    fun tearDown() {
        if (::database.isInitialized) {
            database.close()
        }
    }

    @Test
    fun testInsertAndFindByOccurrenceKey() {
        val hash = DirectBootOccurrenceReceipt.calculateCanonicalPayloadHash(
            sourceRecordId = "normal_101_1700000000_1_token_abc",
            recordType = "DELIVERED",
            alarmId = 101L,
            triggerTime = 1700000000L,
            generation = 1,
            instanceToken = "token_abc",
            restoreEpoch = 0L
        )

        val receipt = DirectBootOccurrenceReceipt(
            occurrenceKey = "ce_takeover_normal_101_gen1_token_abc_trig1700000000",
            sourceRecordId = "normal_101_1700000000_1_token_abc",
            recordType = "DELIVERED",
            alarmId = 101L,
            triggerTime = 1700000000L,
            generation = 1,
            instanceToken = "token_abc",
            restoreEpoch = 0L,
            sourcePayloadHash = hash,
            consumerOutcome = "CONSUMED_DELIVERED",
            completionSequence = 1L,
            completedAt = 1700000050L
        )

        val rowId = dao.insert(receipt)
        assertTrue(rowId >= 0)

        val retrieved = dao.findByOccurrenceKey(receipt.occurrenceKey)
        assertNotNull(retrieved)
        assertEquals(receipt.occurrenceKey, retrieved?.occurrenceKey)
        assertEquals("CONSUMED_DELIVERED", retrieved?.consumerOutcome)
        assertEquals(hash, retrieved?.sourcePayloadHash)
    }

    @Test
    fun testSameIdentityReadback() {
        val hash = DirectBootOccurrenceReceipt.calculateCanonicalPayloadHash(
            sourceRecordId = "missed_normal_102_1700001000_2_token_xyz",
            recordType = "MISSED",
            alarmId = 102L,
            triggerTime = 1700001000L,
            generation = 2,
            instanceToken = "token_xyz",
            restoreEpoch = 0L
        )

        val receipt = DirectBootOccurrenceReceipt(
            occurrenceKey = "ce_takeover_normal_102_gen2_token_xyz_trig1700001000",
            sourceRecordId = "missed_normal_102_1700001000_2_token_xyz",
            recordType = "MISSED",
            alarmId = 102L,
            triggerTime = 1700001000L,
            generation = 2,
            instanceToken = "token_xyz",
            restoreEpoch = 0L,
            sourcePayloadHash = hash,
            consumerOutcome = "CONSUMED_MISSED",
            completionSequence = 2L,
            completedAt = 1700001050L
        )

        dao.insert(receipt)
        val retrieved = dao.findByOccurrenceKey(receipt.occurrenceKey)
        assertNotNull(retrieved)
        assertEquals(receipt.occurrenceKey, retrieved?.occurrenceKey)
        assertEquals(receipt.sourceRecordId, retrieved?.sourceRecordId)
        assertEquals(receipt.recordType, retrieved?.recordType)
        assertEquals(receipt.alarmId, retrieved?.alarmId)
        assertEquals(receipt.triggerTime, retrieved?.triggerTime)
        assertEquals(receipt.generation, retrieved?.generation)
        assertEquals(receipt.instanceToken, retrieved?.instanceToken)
        assertEquals(receipt.restoreEpoch, retrieved?.restoreEpoch)
        assertEquals(receipt.sourcePayloadHash, retrieved?.sourcePayloadHash)
        assertEquals(receipt.consumerOutcome, retrieved?.consumerOutcome)
    }

    @Test
    fun testDuplicatePk_throwsSQLiteConstraintException() {
        val key = "ce_takeover_normal_103_gen1_token_dup_trig1700002000"
        val hash = DirectBootOccurrenceReceipt.calculateCanonicalPayloadHash(
            sourceRecordId = "rec_103",
            recordType = "DELIVERED",
            alarmId = 103L,
            triggerTime = 1700002000L,
            generation = 1,
            instanceToken = "token_dup",
            restoreEpoch = 0L
        )

        val receipt1 = DirectBootOccurrenceReceipt(
            occurrenceKey = key,
            sourceRecordId = "rec_103",
            recordType = "DELIVERED",
            alarmId = 103L,
            triggerTime = 1700002000L,
            generation = 1,
            instanceToken = "token_dup",
            restoreEpoch = 0L,
            sourcePayloadHash = hash,
            consumerOutcome = "CONSUMED_DELIVERED",
            completionSequence = 1L,
            completedAt = 1700002010L
        )

        val receipt2 = receipt1.copy(completedAt = 1700002090L)

        dao.insert(receipt1)
        try {
            dao.insert(receipt2)
            fail("Expected OnConflictStrategy.ABORT to throw SQLiteConstraintException on duplicate PK")
        } catch (e: Exception) {
            assertTrue("Expected SQLiteConstraintException or Room exception", e is android.database.sqlite.SQLiteConstraintException || e.cause is android.database.sqlite.SQLiteConstraintException)
        }

        // Verify original receipt is preserved untouched
        val retrieved = dao.findByOccurrenceKey(key)
        assertEquals(1700002010L, retrieved?.completedAt)
    }

    @Test
    fun testSamePkDifferentPayload_abortsAndPreservesExisting() {
        val key = "ce_takeover_normal_104_gen1_token_diff_trig1700003000"
        val hash1 = DirectBootOccurrenceReceipt.calculateCanonicalPayloadHash(
            sourceRecordId = "rec_104_orig",
            recordType = "DELIVERED",
            alarmId = 104L,
            triggerTime = 1700003000L,
            generation = 1,
            instanceToken = "token_diff",
            restoreEpoch = 0L
        )

        val hash2 = DirectBootOccurrenceReceipt.calculateCanonicalPayloadHash(
            sourceRecordId = "rec_104_tampered",
            recordType = "MISSED",
            alarmId = 104L,
            triggerTime = 1700003000L,
            generation = 1,
            instanceToken = "token_diff",
            restoreEpoch = 0L
        )

        val receiptOriginal = DirectBootOccurrenceReceipt(
            occurrenceKey = key,
            sourceRecordId = "rec_104_orig",
            recordType = "DELIVERED",
            alarmId = 104L,
            triggerTime = 1700003000L,
            generation = 1,
            instanceToken = "token_diff",
            restoreEpoch = 0L,
            sourcePayloadHash = hash1,
            consumerOutcome = "CONSUMED_DELIVERED",
            completionSequence = 1L,
            completedAt = 1700003010L
        )

        val receiptTampered = DirectBootOccurrenceReceipt(
            occurrenceKey = key,
            sourceRecordId = "rec_104_tampered",
            recordType = "MISSED",
            alarmId = 104L,
            triggerTime = 1700003000L,
            generation = 1,
            instanceToken = "token_diff",
            restoreEpoch = 0L,
            sourcePayloadHash = hash2,
            consumerOutcome = "CONSUMED_MISSED",
            completionSequence = 2L,
            completedAt = 1700003020L
        )

        dao.insert(receiptOriginal)
        try {
            dao.insert(receiptTampered)
            fail("Expected duplicate PK insert to be aborted")
        } catch (expected: Exception) {
            // Success: OnConflictStrategy.ABORT rejected tampered duplicate
        }

        val retrieved = dao.findByOccurrenceKey(key)
        assertEquals("rec_104_orig", retrieved?.sourceRecordId)
        assertEquals(hash1, retrieved?.sourcePayloadHash)
        assertEquals("CONSUMED_DELIVERED", retrieved?.consumerOutcome)
    }

    @Test
    fun testDeliveredAndMissedTerminalOutcomes() {
        val receiptDelivered = DirectBootOccurrenceReceipt(
            occurrenceKey = "key_del",
            sourceRecordId = "rec_del",
            recordType = "DELIVERED",
            alarmId = 201L,
            triggerTime = 1700004000L,
            generation = 1,
            instanceToken = "tok",
            restoreEpoch = 0L,
            sourcePayloadHash = "hash_del",
            consumerOutcome = "CONSUMED_DELIVERED",
            completionSequence = 1L,
            completedAt = 1700004010L
        )

        val receiptMissed = DirectBootOccurrenceReceipt(
            occurrenceKey = "key_mis",
            sourceRecordId = "rec_mis",
            recordType = "MISSED",
            alarmId = 202L,
            triggerTime = 1700004000L,
            generation = 1,
            instanceToken = "tok",
            restoreEpoch = 0L,
            sourcePayloadHash = "hash_mis",
            consumerOutcome = "CONSUMED_MISSED",
            completionSequence = 2L,
            completedAt = 1700004020L
        )

        dao.insert(receiptDelivered)
        dao.insert(receiptMissed)

        assertEquals("CONSUMED_DELIVERED", dao.findByOccurrenceKey("key_del")?.consumerOutcome)
        assertEquals("CONSUMED_MISSED", dao.findByOccurrenceKey("key_mis")?.consumerOutcome)
    }

    @Test
    fun testCanonicalPayloadHashVerification_asciiAndJapanese() {
        val hashAscii = DirectBootOccurrenceReceipt.calculateCanonicalPayloadHash(
            sourceRecordId = "rec_ascii",
            recordType = "DELIVERED",
            alarmId = 301L,
            triggerTime = 1700005000L,
            generation = 1,
            instanceToken = "token_ascii",
            restoreEpoch = 0L
        )

        val hashJapanese = DirectBootOccurrenceReceipt.calculateCanonicalPayloadHash(
            sourceRecordId = "rec_テスト_日本語",
            recordType = "DELIVERED",
            alarmId = 301L,
            triggerTime = 1700005000L,
            generation = 1,
            instanceToken = "token_トークン",
            restoreEpoch = 0L
        )

        assertNotNull(hashAscii)
        assertNotNull(hashJapanese)
        assertEquals(64, hashAscii.length)
        assertEquals(64, hashJapanese.length)
        assertTrue(hashAscii != hashJapanese)
    }

    @Test
    fun testCanonicalPayloadHashLengthFraming() {
        // Verify that length framing prevents delimiter injection collisions
        val hash1 = DirectBootOccurrenceReceipt.calculateCanonicalPayloadHash(
            sourceRecordId = "rec;TYPE=MISSED",
            recordType = "DELIVERED",
            alarmId = 401L,
            triggerTime = 1700006000L,
            generation = 1,
            instanceToken = "token1",
            restoreEpoch = 0L
        )

        val hash2 = DirectBootOccurrenceReceipt.calculateCanonicalPayloadHash(
            sourceRecordId = "rec",
            recordType = "TYPE=MISSED;DELIVERED",
            alarmId = 401L,
            triggerTime = 1700006000L,
            generation = 1,
            instanceToken = "token1",
            restoreEpoch = 0L
        )

        assertTrue("Length framing must produce distinct hashes for injected delimiters", hash1 != hash2)
    }

    @Test
    fun testPermanentRetentionNoAutoPrune() {
        for (i in 1..20) {
            val receipt = DirectBootOccurrenceReceipt(
                occurrenceKey = "retention_key_$i",
                sourceRecordId = "rec_$i",
                recordType = "DELIVERED",
                alarmId = 500L + i,
                triggerTime = 1700000000L + i,
                generation = 1,
                instanceToken = "token_$i",
                restoreEpoch = 0L,
                sourcePayloadHash = "hash_$i",
                consumerOutcome = "CONSUMED_DELIVERED",
                completionSequence = i.toLong(),
                completedAt = 1700000000L + i
            )
            dao.insert(receipt)
        }

        assertEquals(20L, dao.count())
        val alarmList = dao.findByAlarmId(505L)
        assertEquals(1, alarmList.size)
        assertEquals("retention_key_5", alarmList[0].occurrenceKey)
    }

    @Test
    fun testDaoCount() {
        assertEquals(0L, dao.count())
        val receipt = DirectBootOccurrenceReceipt(
            occurrenceKey = "count_key_1",
            sourceRecordId = "rec_1",
            recordType = "DELIVERED",
            alarmId = 601L,
            triggerTime = 1700007000L,
            generation = 1,
            instanceToken = "tok",
            restoreEpoch = 0L,
            sourcePayloadHash = "hash",
            consumerOutcome = "CONSUMED_DELIVERED",
            completionSequence = 1L,
            completedAt = 1700007010L
        )
        dao.insert(receipt)
        assertEquals(1L, dao.count())
    }

    @Test
    fun testFindByAlarmId() {
        val alarmId = 701L
        for (i in 1..3) {
            dao.insert(
                DirectBootOccurrenceReceipt(
                    occurrenceKey = "by_alarm_key_$i",
                    sourceRecordId = "rec_$i",
                    recordType = "DELIVERED",
                    alarmId = alarmId,
                    triggerTime = 1700008000L + i * 10,
                    generation = 1,
                    instanceToken = "tok",
                    restoreEpoch = 0L,
                    sourcePayloadHash = "hash_$i",
                    consumerOutcome = "CONSUMED_DELIVERED",
                    completionSequence = i.toLong(),
                    completedAt = 1700008000L + i * 10
                )
            )
        }

        val receipts = dao.findByAlarmId(alarmId)
        assertEquals(3, receipts.size)
        assertEquals("by_alarm_key_1", receipts[0].occurrenceKey)
        assertEquals("by_alarm_key_2", receipts[1].occurrenceKey)
        assertEquals("by_alarm_key_3", receipts[2].occurrenceKey)
    }

    @Test
    fun testNonExistentOccurrenceKey_returnsNull() {
        assertNull(dao.findByOccurrenceKey("non_existent_key_999"))
    }
}
