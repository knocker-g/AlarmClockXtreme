package com.sysadmindoc.alarmclock.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.sysadmindoc.alarmclock.data.local.entity.ScheduleAuthority
import com.sysadmindoc.alarmclock.data.local.entity.ScheduleAuthorityMeta

@Dao
interface ScheduleAuthorityDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertAuthority(authority: ScheduleAuthority)

    @Query("SELECT * FROM schedule_authority WHERE alarmId = :alarmId")
    suspend fun getAuthorityByAlarmId(alarmId: Long): ScheduleAuthority?

    @Query("SELECT * FROM schedule_authority")
    suspend fun getAllAuthorities(): List<ScheduleAuthority>

    @Query("DELETE FROM schedule_authority WHERE alarmId = :alarmId")
    suspend fun deleteAuthorityByAlarmId(alarmId: Long)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMeta(meta: ScheduleAuthorityMeta)

    @Query("SELECT * FROM schedule_authority_meta WHERE id = :id")
    suspend fun getMetaById(id: Int = 1): ScheduleAuthorityMeta?

    @Query("SELECT * FROM schedule_authority_meta ORDER BY id")
    suspend fun getAllMeta(): List<ScheduleAuthorityMeta>

    /**
     * Generation-qualified CAS update for ScheduleAuthority.
     * Validates matching alarmId, instanceToken, restoreEpoch, and scheduleRevision before claiming.
     *
     * On match:
     * - Advances scheduleRevision to [newRevision]
     * - Updates desiredOperation and desiredTriggerMillis
     * - Marks reconcileState = 'RECONCILING'
     * - Updates actionKey
     *
     * Returns 1 if transition succeeded, or 0 if stale, absent, or conflicting identity/revision.
     */
    @Query("""
        UPDATE schedule_authority
        SET scheduleRevision = :newRevision,
            desiredOperation = :desiredOperation,
            desiredTriggerMillis = :desiredTriggerMillis,
            reconcileState = 'RECONCILING',
            actionKey = :actionKey
        WHERE alarmId = :alarmId
          AND instanceToken = :instanceToken
          AND restoreEpoch = :restoreEpoch
          AND scheduleRevision = :expectedRevision
    """)
    suspend fun casClaimAuthority(
        alarmId: Long,
        instanceToken: String,
        restoreEpoch: Long,
        expectedRevision: Long,
        newRevision: Long,
        desiredOperation: String,
        desiredTriggerMillis: Long?,
        actionKey: String?
    ): Int

    /**
     * Conditional settlement requiring matching owner identity, revision, and pending RECONCILING state.
     * Transitions reconcileState from 'RECONCILING' to 'SETTLED'.
     *
     * Returns 1 if settlement succeeded, or 0 if stale or superseded.
     */
    @Query("""
        UPDATE schedule_authority
        SET reconcileState = 'SETTLED'
        WHERE alarmId = :alarmId
          AND instanceToken = :instanceToken
          AND restoreEpoch = :restoreEpoch
          AND scheduleRevision = :claimedRevision
          AND reconcileState = 'RECONCILING'
    """)
    suspend fun settleAuthority(
        alarmId: Long,
        instanceToken: String,
        restoreEpoch: Long,
        claimedRevision: Long
    ): Int

    /**
     * Generation-qualified lifecycle update for ScheduleAuthority (e.g. LIVE <-> DISABLED).
     *
     * Returns 1 if transition succeeded, or 0 if stale or conflicting.
     */
    @Query("""
        UPDATE schedule_authority
        SET scheduleRevision = :newRevision,
            lifecycle = :newLifecycle,
            desiredOperation = :desiredOperation,
            desiredTriggerMillis = :desiredTriggerMillis,
            reconcileState = 'RECONCILING',
            actionKey = :actionKey
        WHERE alarmId = :alarmId
          AND instanceToken = :instanceToken
          AND restoreEpoch = :restoreEpoch
          AND scheduleRevision = :expectedRevision
    """)
    suspend fun casUpdateLifecycle(
        alarmId: Long,
        instanceToken: String,
        restoreEpoch: Long,
        expectedRevision: Long,
        newRevision: Long,
        newLifecycle: String,
        desiredOperation: String,
        desiredTriggerMillis: Long?,
        actionKey: String?
    ): Int
}
