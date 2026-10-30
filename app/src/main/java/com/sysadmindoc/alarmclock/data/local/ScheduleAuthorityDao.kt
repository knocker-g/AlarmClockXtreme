package com.sysadmindoc.alarmclock.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.sysadmindoc.alarmclock.data.local.entity.ScheduleAuthority
import com.sysadmindoc.alarmclock.data.local.entity.ScheduleAuthorityMeta

@Dao
interface ScheduleAuthorityDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAuthority(authority: ScheduleAuthority)

    @Query("SELECT * FROM schedule_authority WHERE alarmId = :alarmId")
    suspend fun getAuthorityByAlarmId(alarmId: Long): ScheduleAuthority?

    @Query("SELECT * FROM schedule_authority")
    suspend fun getAllAuthorities(): List<ScheduleAuthority>

    @Query("DELETE FROM schedule_authority WHERE alarmId = :alarmId")
    suspend fun deleteAuthorityByAlarmId(alarmId: Long)

    @Query("UPDATE schedule_authority SET reconcileState = 'QUARANTINE' WHERE alarmId = :alarmId AND instanceToken = :instanceToken AND scheduleRevision = :scheduleRevision")
    suspend fun quarantineAuthority(alarmId: Long, instanceToken: String, scheduleRevision: Long): Int

    /**
     * Generation-qualified activation CAS transitioning inert 'QUARANTINE' authority to 'RECONCILING'.
     * Validates matching alarmId, instanceToken, restoreEpoch, scheduleRevision, lifecycle, and current reconcileState = 'QUARANTINE'.
     * Returns 1 if activation succeeded, or 0 if stale, mismatched, or already modified.
     */
    @Query("""
        UPDATE schedule_authority
        SET reconcileState = 'RECONCILING'
        WHERE alarmId = :alarmId
          AND instanceToken = :instanceToken
          AND restoreEpoch = :restoreEpoch
          AND scheduleRevision = :scheduleRevision
          AND lifecycle = :lifecycle
          AND reconcileState = 'QUARANTINE'
    """)
    suspend fun activateAuthorityToReconciling(
        alarmId: Long,
        instanceToken: String,
        restoreEpoch: Long,
        scheduleRevision: Long,
        lifecycle: String
    ): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMeta(meta: ScheduleAuthorityMeta)

    @Query("SELECT * FROM schedule_authority_meta WHERE id = :id")
    suspend fun getMetaById(id: Int = 1): ScheduleAuthorityMeta?

    @Query("SELECT * FROM schedule_authority_meta ORDER BY id")
    suspend fun getAllMeta(): List<ScheduleAuthorityMeta>

    /**
     * Generation-qualified CAS claim update for ScheduleAuthority.
     * Strictly increments scheduleRevision by 1 and sets reconcileState = 'RECONCILING'.
     *
     * Validates matching alarmId, instanceToken, restoreEpoch, expectedLifecycle, scheduleRevision, and non-quarantined state.
     * Returns 1 if transition succeeded, or 0 if stale, absent, quarantined, or conflicting.
     */
    @Query("""
        UPDATE schedule_authority
        SET scheduleRevision = scheduleRevision + 1,
            desiredOperation = :desiredOperation,
            desiredTriggerMillis = :desiredTriggerMillis,
            reconcileState = 'RECONCILING',
            actionKey = :actionKey
        WHERE alarmId = :alarmId
          AND instanceToken = :instanceToken
          AND restoreEpoch = :restoreEpoch
          AND scheduleRevision = :expectedRevision
          AND lifecycle = :expectedLifecycle
          AND reconcileState IN ('SETTLED', 'RECONCILING')
    """)
    suspend fun casClaimAuthority(
        alarmId: Long,
        instanceToken: String,
        restoreEpoch: Long,
        expectedRevision: Long,
        expectedLifecycle: String,
        desiredOperation: String,
        desiredTriggerMillis: Long?,
        actionKey: String?
    ): Int

    /**
     * Conditional settlement requiring matching owner identity, revision, expected actionKey, and pending RECONCILING state.
     * Transitions reconcileState from 'RECONCILING' to 'SETTLED'.
     *
     * Returns 1 if settlement succeeded, or 0 if stale, mismatched actionKey, or superseded.
     */
    @Query("""
        UPDATE schedule_authority
        SET reconcileState = 'SETTLED'
        WHERE alarmId = :alarmId
          AND instanceToken = :instanceToken
          AND restoreEpoch = :restoreEpoch
          AND scheduleRevision = :claimedRevision
          AND actionKey IS :expectedActionKey
          AND reconcileState = 'RECONCILING'
    """)
    suspend fun settleAuthority(
        alarmId: Long,
        instanceToken: String,
        restoreEpoch: Long,
        claimedRevision: Long,
        expectedActionKey: String?
    ): Int

    /**
     * Generation-qualified lifecycle update for ScheduleAuthority (e.g. LIVE <-> DISABLED).
     * Strictly increments scheduleRevision by 1.
     *
     * Returns 1 if transition succeeded, or 0 if stale, quarantined, or conflicting.
     */
    @Query("""
        UPDATE schedule_authority
        SET scheduleRevision = scheduleRevision + 1,
            lifecycle = :newLifecycle,
            desiredOperation = :desiredOperation,
            desiredTriggerMillis = :desiredTriggerMillis,
            reconcileState = 'RECONCILING',
            actionKey = :actionKey
        WHERE alarmId = :alarmId
          AND instanceToken = :instanceToken
          AND restoreEpoch = :restoreEpoch
          AND scheduleRevision = :expectedRevision
          AND lifecycle = :expectedLifecycle
          AND reconcileState IN ('SETTLED', 'RECONCILING')
    """)
    suspend fun casUpdateLifecycle(
        alarmId: Long,
        instanceToken: String,
        restoreEpoch: Long,
        expectedRevision: Long,
        expectedLifecycle: String,
        newLifecycle: String,
        desiredOperation: String,
        desiredTriggerMillis: Long?,
        actionKey: String?
    ): Int

    /**
     * Generation-qualified CAS update for schedule-affecting edits.
     * Strictly increments scheduleRevision by 1 and transitions reconcileState = 'RECONCILING'.
     */
    @Query("""
        UPDATE schedule_authority
        SET definitionRevision = :newDefinitionRevision,
            scheduleRevision = scheduleRevision + 1,
            lifecycle = :newLifecycle,
            desiredOperation = :desiredOperation,
            desiredTriggerMillis = :desiredTriggerMillis,
            reconcileState = 'RECONCILING'
        WHERE alarmId = :alarmId
          AND instanceToken = :instanceToken
          AND restoreEpoch = :restoreEpoch
          AND scheduleRevision = :expectedRevision
    """)
    suspend fun casUpdateScheduleAuthority(
        alarmId: Long,
        instanceToken: String,
        restoreEpoch: Long,
        expectedRevision: Long,
        newDefinitionRevision: Long,
        newLifecycle: String,
        desiredOperation: String,
        desiredTriggerMillis: Long?
    ): Int

    /**
     * Generation-qualified CAS update for non-schedule secondary edits (e.g. label/ringtone).
     * Updates definitionRevision while preserving existing scheduleRevision and reconcileState.
     */
    @Query("""
        UPDATE schedule_authority
        SET definitionRevision = :newDefinitionRevision,
            lifecycle = :newLifecycle,
            desiredOperation = :desiredOperation,
            desiredTriggerMillis = :desiredTriggerMillis
        WHERE alarmId = :alarmId
          AND instanceToken = :instanceToken
          AND restoreEpoch = :restoreEpoch
          AND scheduleRevision = :expectedRevision
    """)
    suspend fun casUpdateNonScheduleAuthority(
        alarmId: Long,
        instanceToken: String,
        restoreEpoch: Long,
        expectedRevision: Long,
        newDefinitionRevision: Long,
        newLifecycle: String,
        desiredOperation: String,
        desiredTriggerMillis: Long?
    ): Int

    /**
     * Generation-qualified CAS update for enable/disable toggle.
     * Fenced by matching alarmId, instanceToken, restoreEpoch, and expectedRevision.
     * Strictly increments scheduleRevision by 1 and transitions reconcileState = 'RECONCILING'.
     */
    @Query("""
        UPDATE schedule_authority
        SET scheduleRevision = scheduleRevision + 1,
            lifecycle = :newLifecycle,
            desiredOperation = :desiredOperation,
            desiredTriggerMillis = :desiredTriggerMillis,
            reconcileState = 'RECONCILING'
        WHERE alarmId = :alarmId
          AND instanceToken = :instanceToken
          AND restoreEpoch = :restoreEpoch
          AND scheduleRevision = :expectedRevision
    """)
    suspend fun casUpdateToggleAuthority(
        alarmId: Long,
        instanceToken: String,
        restoreEpoch: Long,
        expectedRevision: Long,
        newLifecycle: String,
        desiredOperation: String,
        desiredTriggerMillis: Long?
    ): Int
}
