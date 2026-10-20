package com.sysadmindoc.alarmclock.data.local

import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Single canonical source of truth for all durable operation intent SQLite trigger DDL definitions.
 *
 * Shared across:
 * - Fresh DB creation ([AlarmDatabase.DATABASE_CALLBACK])
 * - Migration from v28 to v29 ([AlarmDatabase.MIGRATION_28_29])
 * - Read-only trigger definition verification ([DurableIntentTriggerVerifier])
 */
object DurableIntentTriggers {

    val CANONICAL_TRIGGERS: Map<String, String> = mapOf(
        "trigger_durable_intent_unresolved_unique_insert" to """
            CREATE TRIGGER IF NOT EXISTS `trigger_durable_intent_unresolved_unique_insert`
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
            CREATE TRIGGER IF NOT EXISTS `trigger_durable_intent_unresolved_unique_update`
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
            CREATE TRIGGER IF NOT EXISTS `trigger_durable_intent_phase_insert`
            BEFORE INSERT ON `durable_operation_intent`
            FOR EACH ROW
            WHEN NEW.phase NOT IN ('PENDING', 'IN_FLIGHT', 'COMPLETED', 'ABORTED', 'EXPIRED')
            BEGIN
                SELECT RAISE(ABORT, 'SQLite Constraint Error: Invalid phase value');
            END
        """.trimIndent(),

        "trigger_durable_intent_phase_update" to """
            CREATE TRIGGER IF NOT EXISTS `trigger_durable_intent_phase_update`
            BEFORE UPDATE ON `durable_operation_intent`
            FOR EACH ROW
            WHEN NEW.phase NOT IN ('PENDING', 'IN_FLIGHT', 'COMPLETED', 'ABORTED', 'EXPIRED')
            BEGIN
                SELECT RAISE(ABORT, 'SQLite Constraint Error: Invalid phase value');
            END
        """.trimIndent(),

        "trigger_durable_intent_identity_insert" to """
            CREATE TRIGGER IF NOT EXISTS `trigger_durable_intent_identity_insert`
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
            CREATE TRIGGER IF NOT EXISTS `trigger_durable_intent_identity_update`
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

    fun installTriggers(db: SupportSQLiteDatabase) {
        for (sql in CANONICAL_TRIGGERS.values) {
            db.execSQL(sql)
        }
    }
}
