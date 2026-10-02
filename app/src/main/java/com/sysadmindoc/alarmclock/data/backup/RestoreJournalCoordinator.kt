package com.sysadmindoc.alarmclock.data.backup

import android.content.Context
import android.util.AtomicFile
import com.sysadmindoc.alarmclock.data.local.AlarmDatabase
import com.sysadmindoc.alarmclock.data.preferences.PreferencesManager
import com.sysadmindoc.alarmclock.domain.AlarmScheduler
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class RestoreJournalCoordinator @Inject constructor(
    @ApplicationContext private val context: Context,
    private val database: AlarmDatabase,
    private val preferencesManager: PreferencesManager,
    private val alarmScheduler: AlarmScheduler
) {
    private val journalFile = File(context.filesDir, JOURNAL_FILENAME)
    private val atomicFile = AtomicFile(journalFile)

    suspend fun prepareTransaction(
        settingsJson: String,
        alarmsJson: String,
        groupsJson: String
    ) = withContext(Dispatchers.IO) {
        val payloadToHash = settingsJson + alarmsJson + groupsJson
        val checksum = sha256(payloadToHash)

        val json = JSONObject().apply {
            put("formatVersion", 1)
            put("transactionId", UUID.randomUUID().toString())
            put("phase", PHASE_PREPARED)
            put("createdAt", System.currentTimeMillis())
            put("settingsJson", settingsJson)
            put("alarmsJson", alarmsJson)
            put("groupsJson", groupsJson)
            put("checksum", checksum)
        }
        writeJournalAtomic(json.toString())
    }

    suspend fun markCommitted() = withContext(Dispatchers.IO) {
        val jsonStr = readJournalString() ?: return@withContext
        val json = JSONObject(jsonStr)
        json.put("phase", PHASE_COMMITTED)
        writeJournalAtomic(json.toString())
    }

    suspend fun cleanup() = withContext(Dispatchers.IO) {
        if (journalFile.exists()) {
            atomicFile.delete()
        }
    }

    suspend fun checkAndRecover() = withContext(Dispatchers.IO) {
        if (!journalFile.exists()) return@withContext

        val jsonStr = runCatching { readJournalString() }.getOrElse {
            throw IllegalStateException("Corrupt or unparseable restore transaction journal. Startup aborted for safety.", it)
        }

        if (jsonStr == null) {
            atomicFile.delete()
            return@withContext
        }

        val json = JSONObject(jsonStr)
        val phase = json.getString("phase")
        val settingsJson = json.getString("settingsJson")
        val alarmsJson = json.getString("alarmsJson")
        val groupsJson = json.getString("groupsJson")
        val checksum = json.getString("checksum")

        val expectedChecksum = sha256(settingsJson + alarmsJson + groupsJson)
        if (expectedChecksum != checksum) {
            throw SecurityException("Restore journal checksum mismatch. Fails closed for data safety.")
        }

        when (phase) {
            PHASE_PREPARED -> {
                // Reconcile old truth
                alarmScheduler.rescheduleAllInBatches()
                atomicFile.delete()
            }
            PHASE_COMMITTED -> {
                // Reconcile new truth
                alarmScheduler.rescheduleAllInBatches()
                atomicFile.delete()
            }
            else -> {
                throw IllegalStateException("Unknown restore journal phase: $phase")
            }
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
        const val JOURNAL_FILENAME = "restore_transaction.journal"
        const val PHASE_PREPARED = "PREPARED"
        const val PHASE_COMMITTED = "COMMITTED"
    }
}
