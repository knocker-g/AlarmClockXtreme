package com.sysadmindoc.alarmclock.data.repository

import android.content.Context
import androidx.core.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.nio.charset.StandardCharsets

data class HolidayReconcileState(
    val lastReconciledSignature: String? = null,
    val pendingSignature: String? = null,
    val completedAlarmIds: Set<Long> = emptySet(),
    val completedGenerations: Map<Long, Int> = emptyMap()
)

interface HolidayReconcileStateStore {
    fun load(): HolidayReconcileState
    fun save(state: HolidayReconcileState)
    fun clear()
}

class FileHolidayReconcileStateStore(
    private val file: File
) : HolidayReconcileStateStore {

    constructor(
        context: Context,
        fileName: String = "holiday_reconcile_state.json"
    ) : this(File(context.filesDir, fileName))

    private val atomicFile: AtomicFile
        get() = AtomicFile(file)

    @Synchronized
    override fun load(): HolidayReconcileState {
        val target = file
        if (!target.exists()) {
            return HolidayReconcileState()
        }
        val bytes = atomicFile.readFully()
        val text = String(bytes, StandardCharsets.UTF_8)
        val json = JSONObject(text)
        val lastSig = if (json.isNull("lastReconciledSignature")) null else json.optString("lastReconciledSignature")
        val pendingSig = if (json.isNull("pendingSignature")) null else json.optString("pendingSignature")
        val array = json.optJSONArray("completedAlarmIds")
        val ids = mutableSetOf<Long>()
        if (array != null) {
            for (i in 0 until array.length()) {
                ids.add(array.getLong(i))
            }
        }
        val completedGens = mutableMapOf<Long, Int>()
        val gensObj = json.optJSONObject("completedGenerations")
        if (gensObj != null) {
            val keys = gensObj.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                val alarmId = key.toLongOrNull()
                if (alarmId != null) {
                    completedGens[alarmId] = gensObj.getInt(key)
                }
            }
        }
        return HolidayReconcileState(
            lastReconciledSignature = lastSig,
            pendingSignature = pendingSig,
            completedAlarmIds = ids,
            completedGenerations = completedGens
        )
    }

    @Synchronized
    override fun save(state: HolidayReconcileState) {
        val json = JSONObject().apply {
            put("lastReconciledSignature", state.lastReconciledSignature ?: JSONObject.NULL)
            put("pendingSignature", state.pendingSignature ?: JSONObject.NULL)
            val array = JSONArray()
            state.completedAlarmIds.forEach { array.put(it) }
            put("completedAlarmIds", array)
            val gensObj = JSONObject()
            state.completedGenerations.forEach { (id, gen) -> gensObj.put(id.toString(), gen) }
            put("completedGenerations", gensObj)
        }
        val bytes = json.toString().toByteArray(StandardCharsets.UTF_8)
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

    @Synchronized
    override fun clear() {
        try {
            atomicFile.delete()
        } catch (e: Exception) {
            android.util.Log.e("HolidayReconcileStateStore", "Failed to delete reconcile state file", e)
            throw e
        }
    }
}
