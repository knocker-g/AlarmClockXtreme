package com.sysadmindoc.alarmclock.data.local

import androidx.sqlite.db.SupportSQLiteDatabase
import com.sysadmindoc.alarmclock.data.local.entity.DurableOperationIntent
import javax.inject.Inject
import javax.inject.Singleton

sealed interface TriggerVerificationResult {
    object Valid : TriggerVerificationResult
    data class Invalid(val missingOrAlteredTriggers: List<String>) : TriggerVerificationResult
}

sealed interface TriggerClassificationResult {
    object KnownLegacy : TriggerClassificationResult
    object AlreadyCanonical : TriggerClassificationResult
    data class UnknownOrModified(val details: String) : TriggerClassificationResult
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

    /**
     * Classifies the trigger definitions currently present in [sqlite_master]:
     * - [TriggerClassificationResult.AlreadyCanonical] if all 6 triggers match current canonical definitions.
     * - [TriggerClassificationResult.KnownLegacy] if all 6 triggers match known legacy v29 definitions.
     * - [TriggerClassificationResult.UnknownOrModified] if any trigger is missing or modified.
     */
    fun classifyTriggers(db: SupportSQLiteDatabase): TriggerClassificationResult {
        var isLegacyMatch = true
        var isCanonicalMatch = true

        for ((triggerName, canonicalSql) in DurableIntentTriggers.CANONICAL_TRIGGERS) {
            val cursor = db.query(
                "SELECT sql FROM sqlite_master WHERE type = 'trigger' AND name = ?",
                arrayOf(triggerName)
            )
            cursor.use { c ->
                if (!c.moveToFirst()) {
                    return TriggerClassificationResult.UnknownOrModified("Missing trigger: $triggerName")
                }
                val actualSql = c.getString(0) ?: ""
                val normalizedActual = normalizeSql(actualSql)

                val normalizedCanonical = normalizeSql(canonicalSql)
                val legacySql = DurableIntentTriggers.LEGACY_TRIGGERS[triggerName] ?: ""
                val normalizedLegacy = normalizeSql(legacySql)

                if (normalizedActual != normalizedCanonical) {
                    isCanonicalMatch = false
                }
                if (normalizedActual != normalizedLegacy) {
                    isLegacyMatch = false
                }
            }
        }

        return when {
            isCanonicalMatch -> TriggerClassificationResult.AlreadyCanonical
            isLegacyMatch -> TriggerClassificationResult.KnownLegacy
            else -> TriggerClassificationResult.UnknownOrModified("Triggers do not match known legacy or canonical definitions")
        }
    }

    /**
     * Validates all existing rows in [durable_operation_intent] table against physical invariants:
     * - Non-blank operationToken, incarnationId, operationKind
     * - Positive alarmId, operationEpoch, intentRevision
     * - Valid closed phase string
     * - At most ONE unresolved intent (PENDING, IN_FLIGHT) per alarmId
     */
    fun validatePersistedIntents(db: SupportSQLiteDatabase) {
        val cursor = db.query(
            "SELECT operationToken, alarmId, operationEpoch, incarnationId, operationKind, phase, intentRevision FROM durable_operation_intent"
        )
        val unresolvedAlarmIds = mutableSetOf<Long>()

        cursor.use { c ->
            val tokenCol = c.getColumnIndexOrThrow("operationToken")
            val alarmIdCol = c.getColumnIndexOrThrow("alarmId")
            val epochCol = c.getColumnIndexOrThrow("operationEpoch")
            val incCol = c.getColumnIndexOrThrow("incarnationId")
            val kindCol = c.getColumnIndexOrThrow("operationKind")
            val phaseCol = c.getColumnIndexOrThrow("phase")
            val revCol = c.getColumnIndexOrThrow("intentRevision")

            while (c.moveToNext()) {
                val token = c.getString(tokenCol) ?: ""
                val alarmId = c.getLong(alarmIdCol)
                val epoch = c.getLong(epochCol)
                val incId = c.getString(incCol) ?: ""
                val kind = c.getString(kindCol) ?: ""
                val phase = c.getString(phaseCol) ?: ""
                val rev = c.getLong(revCol)

                if (token.isBlank()) error("Persisted row has blank operationToken: $token")
                if (incId.isBlank()) error("Persisted row has blank incarnationId: $incId")
                if (kind.isBlank()) error("Persisted row has blank operationKind: $kind")
                if (alarmId <= 0L) error("Persisted row has nonpositive alarmId: $alarmId")
                if (epoch <= 0L) error("Persisted row has nonpositive operationEpoch: $epoch")
                if (rev <= 0L) error("Persisted row has nonpositive intentRevision: $rev")
                if (!DurableOperationIntent.isValidPhase(phase)) error("Persisted row has invalid phase: $phase")

                if (DurableOperationIntent.isUnresolvedPhase(phase)) {
                    if (!unresolvedAlarmIds.add(alarmId)) {
                        error("Duplicate unresolved operation intent detected for alarmId: $alarmId")
                    }
                }
            }
        }
    }

    /**
     * Strictly verifies that all 6 required SQLite integrity triggers exist in [sqlite_master]
     * and that their canonical SQL definitions match [DurableIntentTriggers.CANONICAL_TRIGGERS].
     *
     * Returns [TriggerVerificationResult.Valid] if all 6 triggers match,
     * or [TriggerVerificationResult.Invalid] if any trigger is missing or altered.
     */
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
