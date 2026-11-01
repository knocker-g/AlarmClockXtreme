package com.sysadmindoc.alarmclock.data.backup

import android.content.Context
import android.util.AtomicFile
import androidx.room.withTransaction
import com.squareup.moshi.JsonClass
import com.squareup.moshi.Moshi
import com.sysadmindoc.alarmclock.data.holiday.ManualHolidayOverrideStore
import com.sysadmindoc.alarmclock.data.local.AlarmDatabase
import com.sysadmindoc.alarmclock.data.local.entity.AlarmGroup
import com.sysadmindoc.alarmclock.data.model.Alarm
import com.sysadmindoc.alarmclock.data.preferences.AppSettings
import com.sysadmindoc.alarmclock.data.preferences.PreferencesManager
import com.sysadmindoc.alarmclock.data.repository.HolidayReconcileCoordinator
import com.sysadmindoc.alarmclock.data.repository.HolidayReconcileResult
import com.sysadmindoc.alarmclock.data.repository.ManualHolidayOverride
import com.sysadmindoc.alarmclock.domain.AlarmScheduler
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.LocalDate
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

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
    val manualOverrides: Map<String, String>? = null
)

@Singleton
class RestoreJournalCoordinator @Inject constructor(
    @ApplicationContext private val context: Context,
    private val database: AlarmDatabase,
    private val preferencesManager: PreferencesManager,
    private val alarmScheduler: AlarmScheduler,
    moshi: Moshi,
    private val manualHolidayOverrideStore: ManualHolidayOverrideStore? = null,
    private val holidayReconcileCoordinator: HolidayReconcileCoordinator? = null
) {
    private val journalFile = File(context.filesDir, JOURNAL_FILENAME)
    private val atomicFile = AtomicFile(journalFile)

    private val snapshotAdapter = moshi.adapter(RestoreSnapshot::class.java)
    private val recoveryMutex = Mutex()

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

        val newPhase = PHASE_COMMITTED
        val payloadToHash = "$formatVersion:$newPhase:$snapshotJson"
        val newChecksum = sha256(payloadToHash)

        val updatedJson = JSONObject().apply {
            put("formatVersion", formatVersion)
            put("transactionId", json.optString("transactionId", UUID.randomUUID().toString()))
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
        if (!journalFile.exists()) return RecoveryOutcome.NONE

        val jsonStr = runCatching { readJournalString() }.getOrElse {
            throw IllegalStateException("Corrupt or unparseable restore transaction journal. Startup aborted for safety.", it)
        }

        if (jsonStr == null) {
            journalFile.delete()
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

        return when (phase) {
            PHASE_PREPARED -> {
                val snapshot = snapshotAdapter.fromJson(snapshotJson)
                    ?: throw IllegalStateException("Failed to decode RestoreSnapshot in PREPARED journal")

                val validatedOverrides = validateSnapshotManualOverrides(snapshot.manualOverrides)

                database.withTransaction {
                    val alarmDao = database.alarmDao()
                    val groupDao = database.alarmGroupDao()
                    alarmDao.deleteAll()
                    groupDao.deleteAll()
                    if (snapshot.alarms.isNotEmpty()) alarmDao.insertAll(snapshot.alarms)
                    if (snapshot.groups.isNotEmpty()) groupDao.insertAll(snapshot.groups)
                }

                preferencesManager.update { snapshot.settings }

                if (validatedOverrides != null) {
                    manualHolidayOverrideStore?.restoreOverrides(validatedOverrides, triggerReconcile = false)
                }

                // Reconcile old truth
                val batchResult = try {
                    alarmScheduler.rescheduleAllInBatches()
                } catch (e: Exception) {
                    android.util.Log.e("RestoreJournalCoordinator", "Failed to reschedule alarms during rollback", e)
                    return RecoveryOutcome.PENDING_RETRY
                }

                val requiresHolidayReconcile = validatedOverrides != null || manualHolidayOverrideStore != null
                val reconcileResult = if (holidayReconcileCoordinator != null) {
                    try {
                        holidayReconcileCoordinator.reconcile(
                            isMaterialChange = true,
                            previousClassifier = null,
                            evaluationEpochMs = System.currentTimeMillis(),
                            evaluationZoneId = java.time.ZoneId.systemDefault()
                        )
                    } catch (e: Exception) {
                        android.util.Log.e("RestoreJournalCoordinator", "Failed to reconcile holidays during rollback", e)
                        HolidayReconcileResult.PendingRetry(1, e)
                    }
                } else {
                    null
                }

                if (batchResult.failedCount > 0) {
                    return RecoveryOutcome.PENDING_RETRY
                }
                if (reconcileResult is HolidayReconcileResult.PendingRetry) {
                    return RecoveryOutcome.PENDING_RETRY
                }
                if (holidayReconcileCoordinator == null && requiresHolidayReconcile) {
                    return RecoveryOutcome.PENDING_RETRY
                }

                // Cleanup journal after successful old-truth reconcile attempt
                atomicFile.delete()
                RecoveryOutcome.RESTORED_OLD
            }
            PHASE_COMMITTED -> {
                val snapshot = snapshotAdapter.fromJson(snapshotJson)
                val requiresHolidayReconcile = (snapshot?.manualOverrides != null) || (manualHolidayOverrideStore != null)

                // Reconcile new truth
                val batchResult = try {
                    alarmScheduler.rescheduleAllInBatches()
                } catch (e: Exception) {
                    android.util.Log.e("RestoreJournalCoordinator", "Failed to reschedule alarms during committed recovery", e)
                    return RecoveryOutcome.PENDING_RETRY
                }

                val reconcileResult = if (holidayReconcileCoordinator != null) {
                    try {
                        holidayReconcileCoordinator.reconcile(
                            isMaterialChange = true,
                            previousClassifier = null,
                            evaluationEpochMs = System.currentTimeMillis(),
                            evaluationZoneId = java.time.ZoneId.systemDefault()
                        )
                    } catch (e: Exception) {
                        android.util.Log.e("RestoreJournalCoordinator", "Failed to reconcile holidays during committed recovery", e)
                        HolidayReconcileResult.PendingRetry(1, e)
                    }
                } else {
                    null
                }

                if (batchResult.failedCount > 0) {
                    return RecoveryOutcome.PENDING_RETRY
                }
                if (reconcileResult is HolidayReconcileResult.PendingRetry) {
                    return RecoveryOutcome.PENDING_RETRY
                }
                if (holidayReconcileCoordinator == null && requiresHolidayReconcile) {
                    return RecoveryOutcome.PENDING_RETRY
                }

                // Cleanup journal after reconcile attempt
                atomicFile.delete()
                RecoveryOutcome.RECOVERED_NEW
            }
            else -> RecoveryOutcome.NONE
        }
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
        if (!journalFile.exists()) return null
        val bytes = atomicFile.readFully()
        return String(bytes, StandardCharsets.UTF_8)
    }

    private fun sha256(input: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(input.toByteArray(StandardCharsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }

    companion object {
        const val CURRENT_FORMAT_VERSION = 1
        const val JOURNAL_FILENAME = "restore_transaction.journal"
        const val PHASE_PREPARED = "PREPARED"
        const val PHASE_COMMITTED = "COMMITTED"
    }
}
