package com.sysadmindoc.alarmclock.data.backup

import android.content.Context
import android.util.AtomicFile
import androidx.room.withTransaction
import com.squareup.moshi.JsonClass
import com.squareup.moshi.Moshi
import com.sysadmindoc.alarmclock.data.local.AlarmDatabase
import com.sysadmindoc.alarmclock.data.local.entity.AlarmGroup
import com.sysadmindoc.alarmclock.data.model.Alarm
import com.sysadmindoc.alarmclock.data.preferences.AppSettings
import com.sysadmindoc.alarmclock.data.preferences.PreferencesManager
import com.sysadmindoc.alarmclock.domain.AlarmScheduler
import dagger.hilt.android.qualifiers.ApplicationContext
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

enum class RecoveryOutcome {
    NONE,
    RESTORED_OLD,
    RECOVERED_NEW
}

@JsonClass(generateAdapter = true)
internal data class RestoreSnapshot(
    val settings: AppSettings,
    val alarms: List<Alarm>,
    val groups: List<AlarmGroup>
)

@Singleton
class RestoreJournalCoordinator @Inject constructor(
    @ApplicationContext private val context: Context,
    private val database: AlarmDatabase,
    private val preferencesManager: PreferencesManager,
    private val alarmScheduler: AlarmScheduler,
    moshi: Moshi
) {
    private val journalFile = File(context.filesDir, JOURNAL_FILENAME)
    private val atomicFile = AtomicFile(journalFile)

    private val snapshotAdapter = moshi.adapter(RestoreSnapshot::class.java)

    suspend fun prepareTransaction(
        settings: AppSettings,
        alarms: List<Alarm>,
        groups: List<AlarmGroup>
    ) {
        val snapshot = RestoreSnapshot(settings = settings, alarms = alarms, groups = groups)
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

    suspend fun markCommitted() {
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

    suspend fun checkAndRecover(): RecoveryOutcome {
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

                database.withTransaction {
                    val alarmDao = database.alarmDao()
                    val groupDao = database.alarmGroupDao()
                    alarmDao.deleteAll()
                    groupDao.deleteAll()
                    if (snapshot.alarms.isNotEmpty()) alarmDao.insertAll(snapshot.alarms)
                    if (snapshot.groups.isNotEmpty()) groupDao.insertAll(snapshot.groups)
                }

                preferencesManager.update { snapshot.settings }

                // Reconcile old truth
                alarmScheduler.rescheduleAllInBatches()

                // Cleanup journal after successful old-truth reconcile attempt
                atomicFile.delete()
                RecoveryOutcome.RESTORED_OLD
            }
            PHASE_COMMITTED -> {
                // Reconcile new truth
                alarmScheduler.rescheduleAllInBatches()

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
