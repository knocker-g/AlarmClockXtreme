package com.sysadmindoc.alarmclock.data.backup

import android.content.Context
import android.util.AtomicFile
import androidx.room.withTransaction
import com.sysadmindoc.alarmclock.data.local.AlarmDatabase
import com.sysadmindoc.alarmclock.data.model.Alarm
import com.sysadmindoc.alarmclock.data.local.entity.AlarmGroup
import com.sysadmindoc.alarmclock.data.preferences.AppSettings
import com.sysadmindoc.alarmclock.data.preferences.PreferencesManager
import com.sysadmindoc.alarmclock.domain.AlarmScheduler
import dagger.hilt.android.qualifiers.ApplicationContext
import org.json.JSONArray
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
        settings: AppSettings,
        alarms: List<Alarm>,
        groups: List<AlarmGroup>
    ) {
        val settingsJson = serializeSettings(settings)
        val alarmsJson = serializeAlarms(alarms)
        val groupsJson = serializeGroups(groups)
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

    suspend fun markCommitted() {
        val jsonStr = readJournalString() ?: return
        val json = JSONObject(jsonStr)
        json.put("phase", PHASE_COMMITTED)
        writeJournalAtomic(json.toString())
    }

    suspend fun cleanup() {
        if (journalFile.exists()) {
            atomicFile.delete()
        }
    }

    suspend fun checkAndRecover() {
        if (!journalFile.exists()) return

        val jsonStr = runCatching { readJournalString() }.getOrElse {
            throw IllegalStateException("Corrupt or unparseable restore transaction journal. Startup aborted for safety.", it)
        }

        if (jsonStr == null) {
            atomicFile.delete()
            return
        }

        val json = JSONObject(jsonStr)
        val formatVersion = json.optInt("formatVersion", 1)
        if (formatVersion != 1) {
            throw SecurityException("Unsupported restore journal format version: $formatVersion. Fails closed.")
        }

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
                val oldSettings = parseSettings(settingsJson)
                val oldAlarms = parseAlarms(alarmsJson)
                val oldGroups = parseGroups(groupsJson)

                database.withTransaction {
                    val alarmDao = database.alarmDao()
                    val groupDao = database.alarmGroupDao()
                    alarmDao.deleteAll()
                    groupDao.deleteAll()
                    if (oldAlarms.isNotEmpty()) alarmDao.insertAll(oldAlarms)
                    if (oldGroups.isNotEmpty()) groupDao.insertAll(oldGroups)
                }

                if (oldSettings != null) {
                    preferencesManager.update { oldSettings }
                }

                // Reconcile old truth
                alarmScheduler.rescheduleAllInBatches()

                // Cleanup journal after successful old-truth reconcile attempt
                atomicFile.delete()
            }
            PHASE_COMMITTED -> {
                // Reconcile new truth
                alarmScheduler.rescheduleAllInBatches()

                // Cleanup journal after reconcile attempt
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

    private fun serializeSettings(settings: AppSettings): String {
        return JSONObject().apply {
            put("defaultSnoozeDuration", settings.defaultSnoozeDuration)
            put("showAlarmClockIcon", settings.showAlarmClockIcon)
            put("holidayAutoSkipEnabled", settings.holidayAutoSkipEnabled)
            put("bedtimeDndEnabled", settings.bedtimeDndEnabled)
        }.toString()
    }

    private fun parseSettings(jsonStr: String): AppSettings? {
        return runCatching {
            val obj = JSONObject(jsonStr)
            AppSettings(
                defaultSnoozeDuration = obj.optInt("defaultSnoozeDuration", 10),
                showAlarmClockIcon = obj.optBoolean("showAlarmClockIcon", true),
                holidayAutoSkipEnabled = obj.optBoolean("holidayAutoSkipEnabled", false),
                bedtimeDndEnabled = obj.optBoolean("bedtimeDndEnabled", false)
            )
        }.getOrNull()
    }

    private fun serializeAlarms(alarms: List<Alarm>): String {
        val arr = JSONArray()
        for (a in alarms) {
            arr.put(JSONObject().apply {
                put("id", a.id)
                put("hour", a.hour)
                put("minute", a.minute)
                put("label", a.label)
                put("isEnabled", a.isEnabled)
            })
        }
        return arr.toString()
    }

    private fun parseAlarms(jsonStr: String): List<Alarm> {
        val list = mutableListOf<Alarm>()
        val arr = JSONArray(jsonStr)
        for (i in 0 until arr.length()) {
            val obj = arr.getJSONObject(i)
            list.add(
                Alarm(
                    id = obj.getLong("id"),
                    hour = obj.getInt("hour"),
                    minute = obj.getInt("minute"),
                    label = obj.getString("label"),
                    isEnabled = obj.getBoolean("isEnabled")
                )
            )
        }
        return list
    }

    private fun serializeGroups(groups: List<AlarmGroup>): String {
        val arr = JSONArray()
        for (g in groups) {
            arr.put(JSONObject().apply {
                put("name", g.name)
            })
        }
        return arr.toString()
    }

    private fun parseGroups(jsonStr: String): List<AlarmGroup> {
        val list = mutableListOf<AlarmGroup>()
        val arr = JSONArray(jsonStr)
        for (i in 0 until arr.length()) {
            val obj = arr.getJSONObject(i)
            list.add(AlarmGroup(name = obj.getString("name")))
        }
        return list
    }

    companion object {
        const val JOURNAL_FILENAME = "restore_transaction.journal"
        const val PHASE_PREPARED = "PREPARED"
        const val PHASE_COMMITTED = "COMMITTED"
    }
}
