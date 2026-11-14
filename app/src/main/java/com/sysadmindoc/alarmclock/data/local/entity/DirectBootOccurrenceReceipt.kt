package com.sysadmindoc.alarmclock.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

@Entity(
    tableName = "direct_boot_occurrence_receipts",
    indices = [
        Index(value = ["alarmId"]),
        Index(value = ["completedAt"]),
        Index(value = ["consumerOutcome"])
    ]
)
data class DirectBootOccurrenceReceipt(
    @PrimaryKey
    val occurrenceKey: String,
    val sourceRecordId: String,
    val recordType: String,
    val alarmId: Long,
    val triggerTime: Long,
    val generation: Int,
    val instanceToken: String,
    val restoreEpoch: Long,
    val sourcePayloadHash: String,
    val consumerOutcome: String,
    val completionSequence: Long = 0,
    val completedAt: Long
) {
    companion object {
        fun calculateCanonicalPayloadHash(
            sourceRecordId: String,
            recordType: String,
            alarmId: Long,
            triggerTime: Long,
            generation: Int,
            instanceToken: String,
            restoreEpoch: Long
        ): String {
            fun byteLen(s: String): Int = s.toByteArray(StandardCharsets.UTF_8).size

            val canonicalString = "ACX_RECEIPT_V1;" +
                "R_ID=${byteLen(sourceRecordId)}:$sourceRecordId;" +
                "TYPE=${byteLen(recordType)}:$recordType;" +
                "ALARM_ID=$alarmId;" +
                "TRIG=$triggerTime;" +
                "GEN=$generation;" +
                "TOKEN=${byteLen(instanceToken)}:$instanceToken;" +
                "EPOCH=$restoreEpoch"

            val digest = MessageDigest.getInstance("SHA-256")
                .digest(canonicalString.toByteArray(StandardCharsets.UTF_8))
            return digest.joinToString("") { "%02x".format(it) }
        }
    }
}
