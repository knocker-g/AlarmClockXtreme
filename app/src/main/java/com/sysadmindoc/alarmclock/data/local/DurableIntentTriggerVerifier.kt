package com.sysadmindoc.alarmclock.data.local

import androidx.sqlite.db.SupportSQLiteDatabase
import javax.inject.Inject
import javax.inject.Singleton

sealed interface TriggerVerificationResult {
    object Valid : TriggerVerificationResult
    data class Invalid(val missingOrAlteredTriggers: List<String>) : TriggerVerificationResult
}

@Singleton
class DurableIntentTriggerVerifier @Inject constructor() {

    companion object {
        val EXPECTED_TRIGGERS = mapOf(
            "trigger_durable_intent_unresolved_unique_insert" to listOf("PENDING", "IN_FLIGHT", "RAISE(ABORT"),
            "trigger_durable_intent_unresolved_unique_update" to listOf("PENDING", "IN_FLIGHT", "RAISE(ABORT"),
            "trigger_durable_intent_phase_insert" to listOf("PENDING", "COMPLETED", "RAISE(ABORT"),
            "trigger_durable_intent_phase_update" to listOf("PENDING", "COMPLETED", "RAISE(ABORT"),
            "trigger_durable_intent_identity_insert" to listOf("operationToken", "incarnationId", "RAISE(ABORT"),
            "trigger_durable_intent_identity_update" to listOf("operationToken", "incarnationId", "RAISE(ABORT")
        )
    }

    /**
     * Verifies that all 6 required SQLite integrity triggers exist in [sqlite_master]
     * and that their SQL definitions contain expected validation clauses.
     *
     * Returns [TriggerVerificationResult.Valid] if all 6 triggers match their definitions,
     * or [TriggerVerificationResult.Invalid] if any trigger is missing or altered.
     */
    fun verifyTriggers(db: SupportSQLiteDatabase): TriggerVerificationResult {
        val missingOrAltered = mutableListOf<String>()

        for ((triggerName, expectedKeywords) in EXPECTED_TRIGGERS) {
            val cursor = db.query(
                "SELECT sql FROM sqlite_master WHERE type = 'trigger' AND name = ?",
                arrayOf(triggerName)
            )
            cursor.use { c ->
                if (!c.moveToFirst()) {
                    missingOrAltered.add("$triggerName (missing)")
                } else {
                    val sql = c.getString(0) ?: ""
                    val isAltered = expectedKeywords.any { keyword -> !sql.contains(keyword) }
                    if (isAltered) {
                        missingOrAltered.add("$triggerName (altered definition)")
                    }
                }
            }
        }

        return if (missingOrAltered.isEmpty()) {
            TriggerVerificationResult.Valid
        } else {
            TriggerVerificationResult.Invalid(missingOrAltered)
        }
    }
}
