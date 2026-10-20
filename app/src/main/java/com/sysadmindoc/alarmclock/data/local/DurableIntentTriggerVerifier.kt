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
        private val CANONICAL_TRIGGERS = mapOf(
            "trigger_durable_intent_unresolved_unique_insert" to """
                CREATE TRIGGER `trigger_durable_intent_unresolved_unique_insert`
                BEFORE INSERT ON `durable_operation_intent`
                FOR EACH ROW
                WHEN NEW.phase IN ('PENDING', 'IN_FLIGHT')
                BEGIN
                    SELECT RAISE(ABORT, 'SQLite Constraint Error: An unresolved operation intent already exists for this alarmId')
                    WHERE EXISTS (
                        SELECT 1 FROM `durable_operation_intent`
                        WHERE `alarmId` = NEW.alarmId
                          AND `phase` IN ('PENDING', 'IN_FLIGHT')
                          AND `operationToken` != NEW.operationToken
                    );
                END
            """.trimIndent(),

            "trigger_durable_intent_unresolved_unique_update" to """
                CREATE TRIGGER `trigger_durable_intent_unresolved_unique_update`
                BEFORE UPDATE ON `durable_operation_intent`
                FOR EACH ROW
                WHEN NEW.phase IN ('PENDING', 'IN_FLIGHT')
                BEGIN
                    SELECT RAISE(ABORT, 'SQLite Constraint Error: An unresolved operation intent already exists for this alarmId')
                    WHERE EXISTS (
                        SELECT 1 FROM `durable_operation_intent`
                        WHERE `alarmId` = NEW.alarmId
                          AND `phase` IN ('PENDING', 'IN_FLIGHT')
                          AND `operationToken` != NEW.operationToken
                    );
                END
            """.trimIndent(),

            "trigger_durable_intent_phase_insert" to """
                CREATE TRIGGER `trigger_durable_intent_phase_insert`
                BEFORE INSERT ON `durable_operation_intent`
                FOR EACH ROW
                WHEN NEW.phase NOT IN ('PENDING', 'IN_FLIGHT', 'COMPLETED', 'ABORTED', 'EXPIRED')
                BEGIN
                    SELECT RAISE(ABORT, 'SQLite Constraint Error: Invalid phase value');
                END
            """.trimIndent(),

            "trigger_durable_intent_phase_update" to """
                CREATE TRIGGER `trigger_durable_intent_phase_update`
                BEFORE UPDATE ON `durable_operation_intent`
                FOR EACH ROW
                WHEN NEW.phase NOT IN ('PENDING', 'IN_FLIGHT', 'COMPLETED', 'ABORTED', 'EXPIRED')
                BEGIN
                    SELECT RAISE(ABORT, 'SQLite Constraint Error: Invalid phase value');
                END
            """.trimIndent(),

            "trigger_durable_intent_identity_insert" to """
                CREATE TRIGGER `trigger_durable_intent_identity_insert`
                BEFORE INSERT ON `durable_operation_intent`
                FOR EACH ROW
                WHEN length(trim(NEW.operationToken)) = 0
                  OR length(trim(NEW.incarnationId)) = 0
                  OR length(trim(NEW.operationKind)) = 0
                  OR NEW.alarmId <= 0
                  OR NEW.operationEpoch <= 0
                  OR NEW.intentRevision <= 0
                BEGIN
                    SELECT RAISE(ABORT, 'SQLite Constraint Error: Invalid identity or epoch invariants');
                END
            """.trimIndent(),

            "trigger_durable_intent_identity_update" to """
                CREATE TRIGGER `trigger_durable_intent_identity_update`
                BEFORE UPDATE ON `durable_operation_intent`
                FOR EACH ROW
                WHEN length(trim(NEW.operationToken)) = 0
                  OR length(trim(NEW.incarnationId)) = 0
                  OR length(trim(NEW.operationKind)) = 0
                  OR NEW.alarmId <= 0
                  OR NEW.operationEpoch <= 0
                  OR NEW.intentRevision <= 0
                BEGIN
                    SELECT RAISE(ABORT, 'SQLite Constraint Error: Invalid identity or epoch invariants');
                END
            """.trimIndent()
        )

        fun normalizeSql(sql: String): String {
            return sql
                .replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), " ")
                .replace(Regex("--.*?\n"), " ")
                .replace("`", "")
                .replace(Regex(";+$"), "")
                .replace(Regex("\\s+"), " ")
                .trim()
                .uppercase()
        }
    }

    /**
     * Strictly verifies that all 6 required SQLite integrity triggers exist in [sqlite_master]
     * and that their canonical SQL definitions match the required normalization fingerprint.
     *
     * Returns [TriggerVerificationResult.Valid] if all 6 triggers match,
     * or [TriggerVerificationResult.Invalid] if any trigger is missing or altered.
     */
    fun verifyTriggers(db: SupportSQLiteDatabase): TriggerVerificationResult {
        val missingOrAltered = mutableListOf<String>()

        for ((triggerName, expectedCanonicalSql) in CANONICAL_TRIGGERS) {
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
