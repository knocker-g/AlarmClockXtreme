package com.sysadmindoc.alarmclock.directboot

import android.content.Context
import androidx.core.os.UserManagerCompat
import androidx.room.withTransaction
import com.sysadmindoc.alarmclock.data.local.AlarmDatabase
import com.sysadmindoc.alarmclock.data.local.entity.ScheduleAuthority
import com.sysadmindoc.alarmclock.data.local.entity.ScheduleAuthorityMeta
import com.sysadmindoc.alarmclock.data.model.Alarm
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Coherent Credential-Encrypted (CE) database snapshot containing the exact unmodified
 * entity collections retrieved in a single atomic Room transaction.
 *
 * Success certifies only that the read was structurally coherent and atomic;
 * it does NOT certify valid Authority coverage, freshness, or candidate eligibility.
 * Downstream Planner owns eligibility, Authority validation, and candidate construction.
 */
data class DirectBootNormalCeSnapshot(
    val alarms: List<Alarm>,
    val authorities: List<ScheduleAuthority>,
    val metaRows: List<ScheduleAuthorityMeta>
)

/**
 * Typed classification of CE snapshot read results.
 */
sealed interface DirectBootNormalSnapshotResult {
    /**
     * Snapshot read succeeded within a single atomic Room transaction.
     * Contains the complete, unmodified lists of [Alarm], [ScheduleAuthority],
     * and [ScheduleAuthorityMeta] without filtering.
     */
    data class Success(
        val snapshot: DirectBootNormalCeSnapshot
    ) : DirectBootNormalSnapshotResult

    /**
     * CE storage is locked (Direct Boot state) or unlock check failed closed.
     * Room database access is strictly bypassed to prevent disk I/O or decryption failures.
     */
    data object CeLocked : DirectBootNormalSnapshotResult

    /**
     * Room read failure (e.g. SQLite read error or disk failure).
     * Failure details are sanitized to prevent leaking user alarm data.
     */
    data class ReadFailure(
        val details: String
    ) : DirectBootNormalSnapshotResult
}

/**
 * Reads a coherent Credential-Encrypted (CE) snapshot of Alarms, Authorities,
 * and Metadata from Room within a single transaction.
 *
 * Enforces:
 * - Preflight CE unlock gate before accessing Room database.
 * - Single atomic [withTransaction] block querying Alarm, Authority, and Meta tables.
 * - Complete snapshot return without filtering Quick alarms, disabled alarms, or unverified authorities.
 * - Transparent [CancellationException] propagation from both unlock gate and Room transaction.
 * - Fail-closed exception handling with sanitized error details.
 * - Zero storage mutation.
 */
@Singleton
class DirectBootNormalSnapshotReader(
    private val database: AlarmDatabase,
    private val unlockGate: () -> Boolean
) {
    /**
     * Production injectable constructor for Hilt.
     * Resolves dependencies via standard Hilt bindings ([ApplicationContext] and [AlarmDatabase])
     * without requiring a new Hilt module or default arguments.
     */
    @Inject
    constructor(
        @ApplicationContext context: Context,
        database: AlarmDatabase
    ) : this(
        database = database,
        unlockGate = {
            try {
                UserManagerCompat.isUserUnlocked(context)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                false
            }
        }
    )

    /**
     * Reads a consistent transactional snapshot of Alarms, Authorities, and Metadata
     * from Room inside a single `withTransaction` block after verifying CE storage is unlocked.
     */
    suspend fun readSnapshot(): DirectBootNormalSnapshotResult {
        val isUnlocked = try {
            unlockGate()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            false
        }

        if (!isUnlocked) {
            return DirectBootNormalSnapshotResult.CeLocked
        }

        return try {
            val (alarms, authorities, metaRows) = database.withTransaction {
                Triple(
                    database.alarmDao().getAll(),
                    database.scheduleAuthorityDao().getAllAuthorities(),
                    database.scheduleAuthorityDao().getAllMeta()
                )
            }
            DirectBootNormalSnapshotResult.Success(
                DirectBootNormalCeSnapshot(
                    alarms = alarms,
                    authorities = authorities,
                    metaRows = metaRows
                )
            )
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            DirectBootNormalSnapshotResult.ReadFailure(
                "Failed to read CE snapshot from Room database"
            )
        }
    }
}
