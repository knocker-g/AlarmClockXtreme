package com.sysadmindoc.alarmclock.data.backup

import android.content.Context
import android.util.AtomicFile
import androidx.room.withTransaction
import com.squareup.moshi.JsonClass
import com.squareup.moshi.Moshi
import com.sysadmindoc.alarmclock.data.holiday.ManualHolidayOverrideStore
import com.sysadmindoc.alarmclock.data.local.AlarmDatabase
import com.sysadmindoc.alarmclock.data.local.entity.AlarmGroup
import com.sysadmindoc.alarmclock.data.local.entity.IssueSlot
import com.sysadmindoc.alarmclock.data.local.entity.ScheduleAuthority
import com.sysadmindoc.alarmclock.data.model.Alarm
import com.sysadmindoc.alarmclock.data.preferences.AppSettings
import com.sysadmindoc.alarmclock.data.preferences.PreferencesManager
import com.sysadmindoc.alarmclock.data.repository.AlarmRepository
import com.sysadmindoc.alarmclock.data.repository.HolidayReconcileCoordinator
import com.sysadmindoc.alarmclock.data.repository.HolidayReconcileResult
import com.sysadmindoc.alarmclock.data.repository.ManualHolidayOverride
import com.sysadmindoc.alarmclock.domain.AlarmScheduler
import com.sysadmindoc.alarmclock.domain.QuickAlarmScheduler
import com.sysadmindoc.alarmclock.domain.QuickReconcileReport
import com.sysadmindoc.alarmclock.domain.ReconcileEntrySource
import com.sysadmindoc.alarmclock.domain.ScheduleAuthorityBootstrapAdapter
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.asContextElement
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.LocalDate
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.coroutineContext

enum class RecoveryOutcome {
    NONE,
    RESTORED_OLD,
    RECOVERED_NEW,
    PENDING_RETRY
}

@JsonClass(generateAdapter = true)
internal data class RestoreSnapshot(
    val settings: AppSettings,
    val alarms: List<Alarm>,
    val groups: List<AlarmGroup>,
    val manualOverrides: Map<String, String>? = null,
    val transientNormalIds: List<Long> = emptyList(),
    val transientQuickIds: List<Long> = emptyList()
)

internal class PreparedRestoreRecoveryContext private constructor(
    val transactionId: String,
    val expectedPhase: String,
    val journalCanonicalPath: String
) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<PreparedRestoreRecoveryContext> {
        internal fun createForRecovery(
            transactionId: String,
            expectedPhase: String,
            journalCanonicalPath: String
        ): PreparedRestoreRecoveryContext = PreparedRestoreRecoveryContext(
            transactionId = transactionId,
            expectedPhase = expectedPhase,
            journalCanonicalPath = journalCanonicalPath
        )
    }
}

internal data class ActiveRecoveryGrant(
    val transactionId: String,
    val expectedPhase: String
)

internal sealed interface JournalDiskState {
    data object Absent : JournalDiskState
    data class Verified(
        val transactionId: String,
        val phase: String,
        val snapshotJson: String
    ) : JournalDiskState
    data object InvalidOrBlocked : JournalDiskState
}

@Singleton
class RestoreJournalCoordinator @Inject constructor(
    @ApplicationContext private val context: Context,
    private val database: AlarmDatabase,
    private val preferencesManager: PreferencesManager,
    private val alarmScheduler: AlarmScheduler,
    moshi: Moshi,
    private val manualHolidayOverrideStore: ManualHolidayOverrideStore? = null,
    private val holidayReconcileCoordinator: HolidayReconcileCoordinator? = null,
    private val quickAlarmScheduler: QuickAlarmScheduler? = null
) {
    private val journalFile = File(context.filesDir, JOURNAL_FILENAME)
    private val bakFile = File(context.filesDir, "$JOURNAL_FILENAME.bak")
    private val canonicalPath = journalFile.canonicalPath
    private val atomicFile = AtomicFile(journalFile)

    private val snapshotAdapter = moshi.adapter(RestoreSnapshot::class.java)
    private val recoveryMutex = recoveryMutexFor(context)

    private val effectiveQuickScheduler: QuickAlarmScheduler by lazy {
        quickAlarmScheduler ?: QuickAlarmScheduler(
            context = context,
            repository = AlarmRepository(
                database = database,
                dao = database.alarmDao(),
                groupDao = database.alarmGroupDao()
            )
        )
    }

    fun hasUnresolvedJournal(): Boolean = hasJournalOrBak()

    private fun hasJournalOrBak(): Boolean = journalFile.exists() || bakFile.exists()

    private fun validateSnapshotManualOverrides(
        raw: Map<String, String>?
    ): Map<LocalDate, ManualHolidayOverride>? {
        if (raw == null) return null
        if (raw.isEmpty()) return emptyMap()

        val parsed = mutableMapOf<LocalDate, ManualHolidayOverride>()
        val seenNormalizedKeys = mutableSetOf<String>()

        for ((key, value) in raw) {
            if (key.isBlank() || value.isBlank()) {
                throw IllegalStateException("Blank key or value in restore snapshot manualOverrides: '$key'='$value'")
            }
            val trimmedKey = key.trim()
            val date = try {
                LocalDate.parse(trimmedKey)
            } catch (e: Exception) {
                throw IllegalStateException("Invalid ISO-8601 date in restore snapshot manualOverrides: '$key'", e)
            }
            if (trimmedKey != date.toString()) {
                throw IllegalStateException("Non-canonical ISO-8601 date format in restore snapshot manualOverrides: '$key'")
            }
            if (!seenNormalizedKeys.add(date.toString())) {
                throw IllegalStateException("Duplicate normalized date key in restore snapshot manualOverrides: '$key'")
            }
            val override = try {
                ManualHolidayOverride.valueOf(value.trim())
            } catch (e: Exception) {
                throw IllegalStateException("Unsupported ManualHolidayOverride enum in restore snapshot: '$value'", e)
            }
            parsed[date] = override
        }
        return parsed
    }

    suspend fun prepareTransaction(
        settings: AppSettings,
        alarms: List<Alarm>,
        groups: List<AlarmGroup>,
        manualOverrides: Map<String, String>? = null
    ) = recoveryMutex.withLock {
        if (hasJournalOrBak()) {
            throw IllegalStateException("Cannot prepare new restore transaction while an unresolved restore journal exists")
        }
        val snapshot = RestoreSnapshot(
            settings = settings,
            alarms = alarms,
            groups = groups,
            manualOverrides = manualOverrides
        )
        val snapshotJson = snapshotAdapter.toJson(snapshot)
            ?: throw IllegalStateException("Failed to serialize RestoreSnapshot")

        val formatVersion = CURRENT_FORMAT_VERSION
        val phase = PHASE_PREPARED
        val payloadToHash = "$formatVersion:$phase:$snapshotJson"
        val checksum = sha256(payloadToHash)

        val json = JSONObject().apply {
            put("formatVersion", formatVersion)
            put("transactionId", UUID.randomUUID().toString())
            put("phase", phase)
            put("createdAt", System.currentTimeMillis())
            put("snapshotJson", snapshotJson)
            put("checksum", checksum)
        }
        writeJournalAtomic(json.toString())
    }

    internal suspend fun recordPreparedTransientTargets(
        normalIds: List<Long>,
        quickIds: List<Long>
    ) = recoveryMutex.withLock {
        val normalizedNormal = normalIds.filter { it > 0L }.distinct().sorted()
        val normalizedQuick = quickIds.filter { it > 0L }.distinct().sorted()
        if (normalizedNormal.isEmpty() && normalizedQuick.isEmpty()) return@withLock

        val jsonStr = readJournalString()
            ?: throw IllegalStateException("No journal found for recordPreparedTransientTargets")
        val json = runCatching { JSONObject(jsonStr) }.getOrElse {
            throw SecurityException("Malformed journal JSON in recordPreparedTransientTargets. Fails closed.", it)
        }

        val formatVersion = json.optInt("formatVersion", -1)
        if (formatVersion != CURRENT_FORMAT_VERSION) {
            throw SecurityException("Unsupported formatVersion in recordPreparedTransientTargets: $formatVersion. Fails closed.")
        }
        val phase = json.optString("phase", "")
        if (phase != PHASE_PREPARED) {
            throw SecurityException("recordPreparedTransientTargets called from invalid phase: $phase. Must be PREPARED.")
        }
        val snapshotJson = json.optString("snapshotJson", "")
        if (snapshotJson.isBlank()) {
            throw SecurityException("Missing snapshotJson in recordPreparedTransientTargets. Fails closed.")
        }
        val existingChecksum = json.optString("checksum", "")
        val expectedChecksum = sha256("$formatVersion:$PHASE_PREPARED:$snapshotJson")
        if (expectedChecksum != existingChecksum) {
            throw SecurityException("PREPARED checksum validation failed in recordPreparedTransientTargets. Fails closed.")
        }
        val txId = requireValidTransactionId(json, "recordPreparedTransientTargets")

        val currentSnapshot = snapshotAdapter.fromJson(snapshotJson)
            ?: throw IllegalStateException("Failed to decode RestoreSnapshot in recordPreparedTransientTargets")

        val mergedNormal = (currentSnapshot.transientNormalIds + normalizedNormal)
            .filter { it > 0L }
            .distinct()
            .sorted()
        val mergedQuick = (currentSnapshot.transientQuickIds + normalizedQuick)
            .filter { it > 0L }
            .distinct()
            .sorted()

        if (mergedNormal == currentSnapshot.transientNormalIds &&
            mergedQuick == currentSnapshot.transientQuickIds
        ) {
            return@withLock
        }

        val updatedSnapshot = currentSnapshot.copy(
            transientNormalIds = mergedNormal,
            transientQuickIds = mergedQuick
        )
        val updatedSnapshotJson = snapshotAdapter.toJson(updatedSnapshot)
            ?: throw IllegalStateException("Failed to serialize updated RestoreSnapshot in recordPreparedTransientTargets")
        val updatedChecksum = sha256("$formatVersion:$PHASE_PREPARED:$updatedSnapshotJson")

        val updatedJson = JSONObject().apply {
            put("formatVersion", formatVersion)
            put("transactionId", txId)
            put("phase", PHASE_PREPARED)
            put("createdAt", json.optLong("createdAt", System.currentTimeMillis()))
            put("snapshotJson", updatedSnapshotJson)
            put("checksum", updatedChecksum)
        }
        writeJournalAtomic(updatedJson.toString())
    }

    internal fun readPreparedTransientTargets(): Pair<List<Long>, List<Long>> {
        val state = readVerifiedDiskState(context)
        if (state !is JournalDiskState.Verified || state.phase != PHASE_PREPARED) {
            return emptyList<Long>() to emptyList<Long>()
        }
        val snapshot = snapshotAdapter.fromJson(state.snapshotJson)
            ?: return emptyList<Long>() to emptyList<Long>()
        return snapshot.transientNormalIds.filter { it > 0L }.distinct().sorted() to
            snapshot.transientQuickIds.filter { it > 0L }.distinct().sorted()
    }

    suspend fun markCommitted() = recoveryMutex.withLock {
        val jsonStr = readJournalString() ?: throw IllegalStateException("No journal found for markCommitted")
        val json = runCatching { JSONObject(jsonStr) }.getOrElse {
            throw SecurityException("Malformed journal JSON in markCommitted. Fails closed.", it)
        }

        val formatVersion = json.optInt("formatVersion", -1)
        if (formatVersion != CURRENT_FORMAT_VERSION) {
            throw SecurityException("Unsupported or missing formatVersion in markCommitted: $formatVersion. Fails closed.")
        }

        val phase = json.optString("phase", "")
        if (phase != PHASE_PREPARED) {
            throw SecurityException("markCommitted called from invalid phase: $phase. Must be PREPARED. Fails closed.")
        }

        val snapshotJson = json.optString("snapshotJson", "")
        if (snapshotJson.isBlank()) {
            throw SecurityException("Missing snapshotJson in markCommitted. Fails closed.")
        }

        val existingChecksum = json.optString("checksum", "")
        val expectedPreparedChecksum = sha256("$formatVersion:$PHASE_PREPARED:$snapshotJson")
        if (expectedPreparedChecksum != existingChecksum) {
            throw SecurityException("PREPARED checksum validation failed in markCommitted. Tampered journal retained.")
        }
        val txId = requireValidTransactionId(json, "markCommitted")

        val newPhase = PHASE_COMMITTED
        val payloadToHash = "$formatVersion:$newPhase:$snapshotJson"
        val newChecksum = sha256(payloadToHash)

        val updatedJson = JSONObject().apply {
            put("formatVersion", formatVersion)
            put("transactionId", txId)
            put("phase", newPhase)
            put("createdAt", json.optLong("createdAt", System.currentTimeMillis()))
            put("snapshotJson", snapshotJson)
            put("checksum", newChecksum)
        }
        writeJournalAtomic(updatedJson.toString())
        onPostDurableCommittedHookForTest?.invoke()
    }

    /**
     * Test-only injectable hook invoked immediately after durable COMMITTED write in [markCommitted].
     */
    internal var onPostDurableCommittedHookForTest: (() -> Unit)? = null

    suspend fun checkAndRecover(): RecoveryOutcome = recoveryMutex.withLock {
        if (!hasJournalOrBak()) return RecoveryOutcome.NONE

        val jsonStr = runCatching { readJournalString() }.getOrElse {
            throw IllegalStateException("Corrupt or unparseable restore transaction journal. Startup aborted for safety.", it)
        }

        if (jsonStr == null) {
            atomicFile.delete()
            return RecoveryOutcome.NONE
        }

        val json = runCatching { JSONObject(jsonStr) }.getOrElse {
            throw SecurityException("Malformed journal JSON payload. Fails closed.", it)
        }

        val formatVersion = json.optInt("formatVersion", -1)
        if (formatVersion != CURRENT_FORMAT_VERSION) {
            throw SecurityException("Unsupported or missing restore journal format version: $formatVersion. Fails closed.")
        }

        val phase = json.optString("phase", "")
        if (phase != PHASE_PREPARED && phase != PHASE_COMMITTED) {
            throw SecurityException("Unknown or malformed journal phase: $phase. Fails closed.")
        }

        val snapshotJson = json.optString("snapshotJson", "")
        val checksum = json.optString("checksum", "")

        val expectedChecksum = sha256("$formatVersion:$phase:$snapshotJson")
        if (expectedChecksum != checksum) {
            throw SecurityException("Restore journal checksum mismatch or tampered phase. Fails closed for data safety.")
        }

        val txId = requireValidTransactionId(json, "checkAndRecover")

        return when (phase) {
            PHASE_PREPARED -> {
                val snapshot = snapshotAdapter.fromJson(snapshotJson)
                    ?: throw IllegalStateException("Failed to decode RestoreSnapshot in PREPARED journal")

                val validatedOverrides = validateSnapshotManualOverrides(snapshot.manualOverrides)

                // Capture any live transient Normal/Quick IDs present in Room BEFORE alarmDao.deleteAll().
                val snapshotOldNormalIds = snapshot.alarms
                    .filter { !it.isQuickAlarm && it.id > 0L }
                    .map { it.id }
                    .toSet()
                val snapshotOldQuickIds = snapshot.alarms
                    .filter { it.isQuickAlarm && it.id > 0L }
                    .map { it.id }
                    .toSet()
                val liveAlarms = database.alarmDao().getAll()
                val liveTransientNormalIds = liveAlarms
                    .filter { !it.isQuickAlarm && it.id > 0L && it.id !in snapshotOldNormalIds }
                    .map { it.id }
                val liveTransientQuickIds = liveAlarms
                    .filter { it.isQuickAlarm && it.id > 0L && it.id !in snapshotOldQuickIds }
                    .map { it.id }

                val persistedTransientNormalIds = (snapshot.transientNormalIds + liveTransientNormalIds)
                    .filter { it > 0L }
                    .distinct()
                    .sorted()
                val persistedTransientQuickIds = (snapshot.transientQuickIds + liveTransientQuickIds)
                    .filter { it > 0L }
                    .distinct()
                    .sorted()

                if (persistedTransientNormalIds != snapshot.transientNormalIds ||
                    persistedTransientQuickIds != snapshot.transientQuickIds
                ) {
                    val updatedSnapshot = snapshot.copy(
                        transientNormalIds = persistedTransientNormalIds,
                        transientQuickIds = persistedTransientQuickIds
                    )
                    val updatedSnapshotJson = snapshotAdapter.toJson(updatedSnapshot)
                        ?: throw IllegalStateException("Failed to serialize RestoreSnapshot with live transient IDs")
                    val updatedChecksum = sha256("$formatVersion:$PHASE_PREPARED:$updatedSnapshotJson")
                    val updatedJson = JSONObject().apply {
                        put("formatVersion", formatVersion)
                        put("transactionId", txId)
                        put("phase", PHASE_PREPARED)
                        put("createdAt", json.optLong("createdAt", System.currentTimeMillis()))
                        put("snapshotJson", updatedSnapshotJson)
                        put("checksum", updatedChecksum)
                    }
                    writeJournalAtomic(updatedJson.toString())
                }

                var restoredEpoch = 0L
                database.withTransaction {
                    val authorityDao = database.scheduleAuthorityDao()
                    val issuedDao = database.issuedOccurrenceDao()

                    val existingMeta = authorityDao.getMetaById(1)
                        ?: throw IllegalStateException("Cannot rollback restore: ScheduleAuthorityMeta missing")
                    if (existingMeta.restoreEpoch <= 0L || existingMeta.restoreEpoch == Long.MAX_VALUE) {
                        throw IllegalStateException("Cannot rollback restore: invalid or overflow restoreEpoch ${existingMeta.restoreEpoch}")
                    }
                    val bumpedRows = authorityDao.incrementRestoreEpoch()
                    if (bumpedRows != 1) {
                        throw IllegalStateException("Cannot rollback restore: failed to increment ScheduleAuthorityMeta restoreEpoch (rows=$bumpedRows)")
                    }
                    val updatedMeta = authorityDao.getMetaById(1)
                        ?: throw IllegalStateException("Cannot rollback restore: ScheduleAuthorityMeta missing after increment")
                    if (updatedMeta.restoreEpoch <= 0L) {
                        throw IllegalStateException("Cannot rollback restore: invalid post-increment restoreEpoch ${updatedMeta.restoreEpoch}")
                    }
                    restoredEpoch = updatedMeta.restoreEpoch

                    val alarmDao = database.alarmDao()
                    val groupDao = database.alarmGroupDao()
                    alarmDao.deleteAll()
                    groupDao.deleteAll()
                    authorityDao.getAllAuthorities().forEach { authorityDao.deleteAuthorityByAlarmId(it.alarmId) }
                    issuedDao.deleteAllSlots()

                    if (snapshot.groups.isNotEmpty()) groupDao.insertAll(snapshot.groups)
                    for (alarm in snapshot.alarms) {
                        val savedId = alarmDao.insert(alarm)
                        val persisted = if (alarm.id == savedId) alarm else alarm.copy(id = savedId)
                        if (!persisted.isQuickAlarm) {
                            val currentDefRev = ScheduleAuthorityBootstrapAdapter.computeDefinitionRevision(persisted)
                            val authority = ScheduleAuthority(
                                alarmId = savedId,
                                instanceToken = "auth_${savedId}_gen_${persisted.generation}_${UUID.randomUUID()}",
                                definitionRevision = currentDefRev,
                                scheduleRevision = persisted.revision.toLong(),
                                lifecycle = if (persisted.isEnabled) "LIVE" else "DISABLED",
                                desiredOperation = if (persisted.isEnabled) "SET" else "CANCEL",
                                desiredTriggerMillis = if (persisted.isEnabled) persisted.nextTriggerTime else null,
                                reconcileState = "RECONCILING",
                                restoreEpoch = updatedMeta.restoreEpoch,
                                actionKey = null
                            )
                            authorityDao.insertAuthority(authority)

                            val slot = IssueSlot(
                                alarmId = savedId,
                                generationId = persisted.generation.toLong(),
                                currentIssueToken = null
                            )
                            issuedDao.insertSlotInitial(slot)
                        }
                    }
                }

                preferencesManager.update { snapshot.settings }

                if (validatedOverrides != null) {
                    manualHolidayOverrideStore?.restoreOverrides(validatedOverrides, triggerReconcile = false)
                }

                val grant = ActiveRecoveryGrant(txId, PHASE_PREPARED)
                val recoveryContext = PreparedRestoreRecoveryContext.createForRecovery(txId, PHASE_PREPARED, canonicalPath) +
                    activeSyncGrantThreadLocal.asContextElement(grant)
                activeGrantRegistry[canonicalPath] = grant

                val disabledOldQuickIds = snapshot.alarms
                    .filter { it.isQuickAlarm && !it.isEnabled && it.id > 0L }
                    .map { it.id }
                val quickCancelTargets = ((persistedTransientQuickIds - snapshotOldQuickIds) + disabledOldQuickIds)
                    .distinct()
                    .sorted()
                val normalTransientCancelTargets = (persistedTransientNormalIds - snapshotOldNormalIds)
                    .distinct()
                    .sorted()
                val disabledSameIdOldNormalTargets = snapshot.alarms
                    .filter { !it.isQuickAlarm && !it.isEnabled && it.id > 0L && it.id in persistedTransientNormalIds }
                    .distinctBy { it.id }
                    .sortedBy { it.id }

                var quickCleanupFailed = false
                var legacyCleanupFailed = false
                var staleCleanup = AlarmScheduler.PreRestoreOccurrenceCleanupResult()
                var quickReport = QuickReconcileReport()

                val (batchResult, reconcileResult) = try {
                    withContext(recoveryContext) {
                        for (qId in quickCancelTargets) {
                            val ok = try {
                                effectiveQuickScheduler.cancelQuickPendingIntentOnly(qId)
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                android.util.Log.e("RestoreJournalCoordinator", "Failed Quick OS cleanup for $qId during rollback", e)
                                false
                            }
                            if (!ok) quickCleanupFailed = true
                        }

                        for (nId in normalTransientCancelTargets) {
                            val ok = try {
                                alarmScheduler.cancelScheduledEntriesSuspending(
                                    alarmId = nId,
                                    includeFollowUpWorkers = true,
                                    expectedRestoreEpoch = restoredEpoch
                                )
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                android.util.Log.e("RestoreJournalCoordinator", "Failed Normal OS cleanup for transient $nId during rollback", e)
                                false
                            }
                            if (!ok) legacyCleanupFailed = true
                        }

                        for (oldDisabled in disabledSameIdOldNormalTargets) {
                            val ok = try {
                                alarmScheduler.cancelScheduledEntriesSuspending(
                                    alarmId = oldDisabled.id,
                                    includeFollowUpWorkers = true,
                                    expectedGeneration = oldDisabled.generation,
                                    expectedRestoreEpoch = restoredEpoch
                                )
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                android.util.Log.e("RestoreJournalCoordinator", "Failed Normal OS cleanup for disabled OLD ${oldDisabled.id} during rollback", e)
                                false
                            }
                            if (!ok) legacyCleanupFailed = true
                        }

                        staleCleanup = try {
                            alarmScheduler.cleanupStalePreRestoreOccurrences(restoredEpoch)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            android.util.Log.e("RestoreJournalCoordinator", "Failed cleanupStalePreRestoreOccurrences during rollback", e)
                            AlarmScheduler.PreRestoreOccurrenceCleanupResult(failedCount = 1)
                        }

                        val br = try {
                            alarmScheduler.rescheduleAllInBatches()
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            android.util.Log.e("RestoreJournalCoordinator", "Failed to reschedule alarms during rollback", e)
                            null
                        }

                        quickReport = try {
                            effectiveQuickScheduler.reconcileQuickAlarms(ReconcileEntrySource.SYSTEM_RECOVERY)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            android.util.Log.e("RestoreJournalCoordinator", "Failed Quick reconcile during rollback", e)
                            QuickReconcileReport(failedAlarms = listOf(Alarm(id = -1L, hour = 0, minute = 0)))
                        }

                        val rr = if (holidayReconcileCoordinator != null) {
                            try {
                                holidayReconcileCoordinator.reconcile(
                                    isMaterialChange = true,
                                    previousClassifier = null,
                                    evaluationEpochMs = System.currentTimeMillis(),
                                    evaluationZoneId = java.time.ZoneId.systemDefault()
                                )
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                android.util.Log.e("RestoreJournalCoordinator", "Failed to reconcile holidays during rollback", e)
                                HolidayReconcileResult.PendingRetry(1, e)
                            }
                        } else {
                            null
                        }
                        br to rr
                    }
                } finally {
                    activeGrantRegistry.remove(canonicalPath)
                }

                val hasUnresolvedLegacyWitness = hasUnresolvedLegacyCancelWitness()
                val requiresHolidayReconcile = validatedOverrides != null || manualHolidayOverrideStore != null
                if (!canDeleteJournalAfterRecovery(
                        quickCleanupFailed = quickCleanupFailed,
                        legacyCleanupFailed = legacyCleanupFailed,
                        staleCleanup = staleCleanup,
                        batchResult = batchResult,
                        quickReport = quickReport,
                        hasUnresolvedLegacyWitness = hasUnresolvedLegacyWitness,
                        reconcileResult = reconcileResult,
                        requiresHolidayReconcile = requiresHolidayReconcile
                    )
                ) {
                    return RecoveryOutcome.PENDING_RETRY
                }

                // Cleanup journal after full OS cleanup, witness settlement, and old-truth reconcile
                atomicFile.delete()
                RecoveryOutcome.RESTORED_OLD
            }
            PHASE_COMMITTED -> {
                val snapshot = snapshotAdapter.fromJson(snapshotJson)
                    ?: throw IllegalStateException("Failed to decode RestoreSnapshot in COMMITTED journal")
                val requiresHolidayReconcile = (snapshot.manualOverrides != null) || (manualHolidayOverrideStore != null)

                val currentEpoch = database.scheduleAuthorityDao().getMetaById(1)?.restoreEpoch ?: 0L
                val currentAlarms = database.alarmDao().getAll()
                val currentById = currentAlarms.associateBy { it.id }

                val oldQuickCancelTargets = snapshot.alarms
                    .filter { it.isQuickAlarm && it.id > 0L }
                    .map { it.id }
                    .filter { qId ->
                        val cur = currentById[qId]
                        cur == null || !cur.isQuickAlarm || !cur.isEnabled
                    }
                    .distinct()
                    .sorted()

                val oldNormalTargets = snapshot.alarms
                    .filter { !it.isQuickAlarm && it.id > 0L }
                    .distinctBy { it.id }
                    .sortedBy { it.id }

                val grant = ActiveRecoveryGrant(txId, PHASE_COMMITTED)
                val recoveryContext = PreparedRestoreRecoveryContext.createForRecovery(txId, PHASE_COMMITTED, canonicalPath) +
                    activeSyncGrantThreadLocal.asContextElement(grant)
                activeGrantRegistry[canonicalPath] = grant

                var quickCleanupFailed = false
                var legacyCleanupFailed = false
                var staleCleanup = AlarmScheduler.PreRestoreOccurrenceCleanupResult()
                var quickReport = QuickReconcileReport()

                val (batchResult, reconcileResult) = try {
                    withContext(recoveryContext) {
                        for (qId in oldQuickCancelTargets) {
                            val ok = try {
                                effectiveQuickScheduler.cancelQuickPendingIntentOnly(qId)
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                android.util.Log.e("RestoreJournalCoordinator", "Failed OLD Quick OS cleanup for $qId during committed recovery", e)
                                false
                            }
                            if (!ok) quickCleanupFailed = true
                        }

                        for (oldNormal in oldNormalTargets) {
                            val cur = currentById[oldNormal.id]
                            if (cur == null || cur.isQuickAlarm) {
                                val ok = try {
                                    alarmScheduler.cancelScheduledEntriesSuspending(
                                        alarmId = oldNormal.id,
                                        includeFollowUpWorkers = true,
                                        expectedRestoreEpoch = currentEpoch
                                    )
                                } catch (e: CancellationException) {
                                    throw e
                                } catch (e: Exception) {
                                    android.util.Log.e("RestoreJournalCoordinator", "Failed OLD Normal OS cleanup for removed/converted ${oldNormal.id} during committed recovery", e)
                                    false
                                }
                                if (!ok) legacyCleanupFailed = true
                            } else if (!cur.isEnabled) {
                                val ok = try {
                                    alarmScheduler.cancelScheduledEntriesSuspending(
                                        alarmId = oldNormal.id,
                                        includeFollowUpWorkers = true,
                                        expectedGeneration = cur.generation,
                                        expectedRestoreEpoch = currentEpoch
                                    )
                                } catch (e: CancellationException) {
                                    throw e
                                } catch (e: Exception) {
                                    android.util.Log.e("RestoreJournalCoordinator", "Failed OLD Normal OS cleanup for disabled ${oldNormal.id} during committed recovery", e)
                                    false
                                }
                                if (!ok) legacyCleanupFailed = true
                            }
                            // Enabled NEW Normal alarm with same ID is NOT blindly cancelled before rescheduleAllInBatches;
                            // rescheduleAllInBatches re-arms it with currentEpoch & FLAG_UPDATE_CURRENT, and
                            // cleanupStalePreRestoreOccurrences revokes any pre-restore Rev2 tokenized PIs.
                        }

                        staleCleanup = try {
                            alarmScheduler.cleanupStalePreRestoreOccurrences(currentEpoch)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            android.util.Log.e("RestoreJournalCoordinator", "Failed cleanupStalePreRestoreOccurrences during committed recovery", e)
                            AlarmScheduler.PreRestoreOccurrenceCleanupResult(failedCount = 1)
                        }

                        val br = try {
                            alarmScheduler.rescheduleAllInBatches()
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            android.util.Log.e("RestoreJournalCoordinator", "Failed to reschedule alarms during committed recovery", e)
                            null
                        }

                        quickReport = try {
                            effectiveQuickScheduler.reconcileQuickAlarms(ReconcileEntrySource.SYSTEM_RECOVERY)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            android.util.Log.e("RestoreJournalCoordinator", "Failed Quick reconcile during committed recovery", e)
                            QuickReconcileReport(failedAlarms = listOf(Alarm(id = -1L, hour = 0, minute = 0)))
                        }

                        val rr = if (holidayReconcileCoordinator != null) {
                            try {
                                holidayReconcileCoordinator.reconcile(
                                    isMaterialChange = true,
                                    previousClassifier = null,
                                    evaluationEpochMs = System.currentTimeMillis(),
                                    evaluationZoneId = java.time.ZoneId.systemDefault()
                                )
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                android.util.Log.e("RestoreJournalCoordinator", "Failed to reconcile holidays during committed recovery", e)
                                HolidayReconcileResult.PendingRetry(1, e)
                            }
                        } else {
                            null
                        }
                        br to rr
                    }
                } finally {
                    activeGrantRegistry.remove(canonicalPath)
                }

                val hasUnresolvedLegacyWitness = hasUnresolvedLegacyCancelWitness()
                if (!canDeleteJournalAfterRecovery(
                        quickCleanupFailed = quickCleanupFailed,
                        legacyCleanupFailed = legacyCleanupFailed,
                        staleCleanup = staleCleanup,
                        batchResult = batchResult,
                        quickReport = quickReport,
                        hasUnresolvedLegacyWitness = hasUnresolvedLegacyWitness,
                        reconcileResult = reconcileResult,
                        requiresHolidayReconcile = requiresHolidayReconcile
                    )
                ) {
                    return RecoveryOutcome.PENDING_RETRY
                }

                // Cleanup journal after full OLD OS cleanup, witness settlement, and new-truth reconcile
                atomicFile.delete()
                RecoveryOutcome.RECOVERED_NEW
            }
            else -> RecoveryOutcome.NONE
        }
    }

    private suspend fun hasUnresolvedLegacyCancelWitness(): Boolean {
        return try {
            database.durableOperationIntentDao()
                .getUnresolvedCancellationIntents()
                .any { it.operationToken.startsWith(AlarmScheduler.LEGACY_V1_CANCEL_TOKEN_PREFIX) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            android.util.Log.e("RestoreJournalCoordinator", "Failed checking unresolved Legacy V1 cancel witnesses", e)
            true
        }
    }

    private fun canDeleteJournalAfterRecovery(
        quickCleanupFailed: Boolean,
        legacyCleanupFailed: Boolean,
        staleCleanup: AlarmScheduler.PreRestoreOccurrenceCleanupResult,
        batchResult: com.sysadmindoc.alarmclock.domain.ScheduleOutcome.BatchResult?,
        quickReport: QuickReconcileReport,
        hasUnresolvedLegacyWitness: Boolean,
        reconcileResult: HolidayReconcileResult?,
        requiresHolidayReconcile: Boolean
    ): Boolean {
        if (quickCleanupFailed || legacyCleanupFailed) return false
        if (staleCleanup.failedCount > 0 ||
            staleCleanup.isolatedCount > 0 ||
            staleCleanup.preservedUnknownCount > 0
        ) {
            return false
        }
        if (batchResult == null || batchResult.failedCount > 0 || batchResult.blockedCount > 0) {
            return false
        }
        if (quickReport.failedAlarms.isNotEmpty()) return false
        if (hasUnresolvedLegacyWitness) return false
        if (reconcileResult is HolidayReconcileResult.PendingRetry) return false
        if (holidayReconcileCoordinator == null && requiresHolidayReconcile) return false
        return true
    }

    private fun writeJournalAtomic(jsonContent: String) {
        val bytes = jsonContent.toByteArray(StandardCharsets.UTF_8)
        var stream: FileOutputStream? = null
        try {
            stream = atomicFile.startWrite()
            stream.write(bytes)
            atomicFile.finishWrite(stream)
        } catch (e: Exception) {
            if (stream != null) {
                atomicFile.failWrite(stream)
            }
            throw e
        }
    }

    private fun readJournalString(): String? {
        if (!hasJournalOrBak()) return null
        val bytes = atomicFile.readFully()
        return String(bytes, StandardCharsets.UTF_8)
    }

    companion object {
        const val CURRENT_FORMAT_VERSION = 1
        const val JOURNAL_FILENAME = "restore_transaction.journal"
        const val PHASE_PREPARED = "PREPARED"
        const val PHASE_COMMITTED = "COMMITTED"

        private val recoveryMutexRegistry = ConcurrentHashMap<String, Mutex>()
        private val activeGrantRegistry = ConcurrentHashMap<String, ActiveRecoveryGrant>()
        private val activeSyncGrantThreadLocal = ThreadLocal<ActiveRecoveryGrant?>()

        internal fun canonicalJournalPath(context: Context): String =
            File(context.filesDir, JOURNAL_FILENAME).canonicalPath

        internal fun recoveryMutexFor(context: Context): Mutex =
            recoveryMutexRegistry.computeIfAbsent(canonicalJournalPath(context)) { Mutex() }

        private fun sha256(input: String): String {
            val bytes = MessageDigest.getInstance("SHA-256").digest(input.toByteArray(StandardCharsets.UTF_8))
            return bytes.joinToString("") { "%02x".format(it) }
        }

        internal fun readVerifiedDiskState(context: Context): JournalDiskState {
            val jFile = File(context.filesDir, JOURNAL_FILENAME)
            val bFile = File(context.filesDir, "$JOURNAL_FILENAME.bak")
            if (!jFile.exists() && !bFile.exists()) {
                return JournalDiskState.Absent
            }
            val jsonStr = try {
                val bytes = AtomicFile(jFile).readFully()
                String(bytes, StandardCharsets.UTF_8)
            } catch (e: Exception) {
                android.util.Log.w("RestoreJournalCoordinator", "Failed reading restore journal; failing closed", e)
                return JournalDiskState.InvalidOrBlocked
            }
            if (jsonStr.isBlank()) {
                return JournalDiskState.InvalidOrBlocked
            }
            val json = runCatching { JSONObject(jsonStr) }.getOrNull()
                ?: return JournalDiskState.InvalidOrBlocked
            val formatVersion = json.optInt("formatVersion", -1)
            if (formatVersion != CURRENT_FORMAT_VERSION) {
                return JournalDiskState.InvalidOrBlocked
            }
            val phase = json.optString("phase", "")
            if (phase != PHASE_PREPARED && phase != PHASE_COMMITTED) {
                return JournalDiskState.InvalidOrBlocked
            }
            val snapshotJson = json.optString("snapshotJson", "")
            if (snapshotJson.isBlank()) {
                return JournalDiskState.InvalidOrBlocked
            }
            val checksum = json.optString("checksum", "")
            val expectedChecksum = sha256("$formatVersion:$phase:$snapshotJson")
            if (checksum != expectedChecksum) {
                return JournalDiskState.InvalidOrBlocked
            }
            val rawTxId = json.opt("transactionId")
            if (rawTxId !is String || rawTxId.isBlank()) {
                return JournalDiskState.InvalidOrBlocked
            }
            return JournalDiskState.Verified(
                transactionId = rawTxId,
                phase = phase,
                snapshotJson = snapshotJson
            )
        }

        private fun requireValidTransactionId(json: JSONObject, operation: String): String {
            val raw = json.opt("transactionId")
            if (raw !is String || raw.isBlank()) {
                throw SecurityException("Missing or blank transactionId in $operation. Fails closed.")
            }
            return raw
        }

        private fun isAuthenticatedBypass(
            context: Context,
            txId: String,
            expectedPhase: String,
            journalCanonicalPath: String
        ): Boolean {
            if (txId.isBlank()) return false
            val path = canonicalJournalPath(context)
            if (journalCanonicalPath != path) return false
            val mutex = recoveryMutexFor(context)
            if (!mutex.isLocked) return false
            val grant = activeGrantRegistry[path] ?: return false
            if (grant.transactionId != txId || grant.expectedPhase != expectedPhase) return false
            val disk = readVerifiedDiskState(context)
            return disk is JournalDiskState.Verified &&
                disk.transactionId == txId &&
                disk.phase == expectedPhase
        }

        internal suspend fun <T> withRecoveryGate(
            context: Context,
            onBlocked: suspend () -> T,
            block: suspend () -> T
        ): T {
            val bypass = coroutineContext[PreparedRestoreRecoveryContext]
            if (bypass != null) {
                if (!isAuthenticatedBypass(
                        context = context,
                        txId = bypass.transactionId,
                        expectedPhase = bypass.expectedPhase,
                        journalCanonicalPath = bypass.journalCanonicalPath
                    )
                ) {
                    return onBlocked()
                }
                return block()
            }

            return recoveryMutexFor(context).withLock {
                when (val disk = readVerifiedDiskState(context)) {
                    is JournalDiskState.Absent -> block()
                    is JournalDiskState.Verified -> {
                        if (disk.phase == PHASE_COMMITTED) block() else onBlocked()
                    }
                    is JournalDiskState.InvalidOrBlocked -> onBlocked()
                }
            }
        }

        internal inline fun <T> withSyncRecoveryGateNonBlocking(
            context: Context,
            onBlocked: () -> T,
            block: () -> T
        ): T {
            val syncGrant = activeSyncGrantThreadLocal.get()
            if (syncGrant != null) {
                val path = canonicalJournalPath(context)
                return if (isAuthenticatedBypass(context, syncGrant.transactionId, syncGrant.expectedPhase, path)) {
                    block()
                } else {
                    onBlocked()
                }
            }

            val mutex = recoveryMutexFor(context)
            if (!mutex.tryLock()) {
                return onBlocked()
            }
            return try {
                when (val disk = readVerifiedDiskState(context)) {
                    is JournalDiskState.Absent -> block()
                    is JournalDiskState.Verified -> {
                        if (disk.phase == PHASE_COMMITTED) block() else onBlocked()
                    }
                    is JournalDiskState.InvalidOrBlocked -> onBlocked()
                }
            } finally {
                mutex.unlock()
            }
        }
    }
}
