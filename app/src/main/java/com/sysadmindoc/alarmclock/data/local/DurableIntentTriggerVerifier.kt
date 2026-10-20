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
        fun normalizeSql(sql: String): String {
            return sql
                .replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), " ")
                .replace(Regex("--.*?\n"), " ")
                .replace("`", "")
                .replace(Regex("IF\\s+NOT\\s+EXISTS", RegexOption.IGNORE_CASE), " ")
                .replace(Regex(";+$"), "")
                .replace(Regex("\\s+"), " ")
                .trim()
                .uppercase()
        }
    }
    fun verifyTriggers(db: SupportSQLiteDatabase): TriggerVerificationResult {
        val missingOrAltered = mutableListOf<String>()

        for ((triggerName, expectedCanonicalSql) in DurableIntentTriggers.CANONICAL_TRIGGERS) {
            val cursor = db.query(
                "SELECT sql FROM sqlite_master WHERE type = 'trigger' AND name = ?",
                arrayOf(triggerName)
            )
            cursor.use { c ->
                if (!c.moveToFirst()) {
                    missingOrAltered.add("$triggerName (missing)")
                } else {
                    val actualSql = c.getString(0) ?: ""
                    val normalizedActual = normalizeSql(actualSql)
                    val normalizedExpected = normalizeSql(expectedCanonicalSql)

                    if (normalizedActual != normalizedExpected) {
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
