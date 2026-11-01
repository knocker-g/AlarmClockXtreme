package com.sysadmindoc.alarmclock.data.holiday

import android.content.Context
import androidx.core.util.AtomicFile
import com.squareup.moshi.JsonReader
import com.sysadmindoc.alarmclock.data.repository.HolidayReconcileResult
import com.sysadmindoc.alarmclock.data.repository.HolidayReconcileStateStore
import com.sysadmindoc.alarmclock.data.repository.ManualHolidayOverride
import okio.Buffer
import java.io.File
import java.io.FileOutputStream
import java.nio.charset.StandardCharsets
import java.time.LocalDate

object ManualHolidayOverridesJsonCodec {
    fun encode(payload: ManualHolidayOverridesPayload): String {
        val sb = StringBuilder()
        sb.append("{\"formatVersion\":").append(payload.formatVersion)
        sb.append(",\"updatedAtMillis\":").append(payload.updatedAtMillis)
        sb.append(",\"overrides\":{")
        var first = true
        payload.overrides.toSortedMap().forEach { (k, v) ->
            if (!first) sb.append(",")
            first = false
            sb.append("\"").append(k).append("\":\"").append(v).append("\"")
        }
        sb.append("}}")
        return sb.toString()
    }

    fun decode(jsonStr: String): ManualHolidayOverridesPayload {
        val reader = JsonReader.of(Buffer().writeUtf8(jsonStr))
        var formatVersion: Int? = null
        var updatedAtMillis: Long? = null
        val overrides = LinkedHashMap<String, String>()

        reader.beginObject()
        while (reader.hasNext()) {
            when (reader.nextName()) {
                "formatVersion" -> formatVersion = reader.nextInt()
                "updatedAtMillis" -> updatedAtMillis = reader.nextLong()
                "overrides" -> {
                    reader.beginObject()
                    while (reader.hasNext()) {
                        val key = reader.nextName()
                        val value = reader.nextString()
                        overrides[key] = value
                    }
                    reader.endObject()
                }
                else -> reader.skipValue()
            }
        }
        reader.endObject()

        if (formatVersion == null) throw IllegalArgumentException("Missing formatVersion")
        if (updatedAtMillis == null) throw IllegalArgumentException("Missing updatedAtMillis")
        return ManualHolidayOverridesPayload(formatVersion, updatedAtMillis, overrides)
    }
}

class FileManualHolidayOverrideStore(
    private val file: File,
    private val clockMillis: () -> Long = { System.currentTimeMillis() },
    var reconcileCoordinatorProvider: (() -> com.sysadmindoc.alarmclock.data.repository.HolidayReconcileCoordinator)? = null,
    var reconcileStateStore: HolidayReconcileStateStore? = null
) : ManualHolidayOverrideStore {

    constructor(
        context: Context,
        fileName: String = "manual_holiday_overrides.json",
        reconcileCoordinatorProvider: (() -> com.sysadmindoc.alarmclock.data.repository.HolidayReconcileCoordinator)? = null,
        reconcileStateStore: HolidayReconcileStateStore? = null
    ) : this(
        File(context.filesDir, fileName),
        clockMillis = { System.currentTimeMillis() },
        reconcileCoordinatorProvider = reconcileCoordinatorProvider,
        reconcileStateStore = reconcileStateStore ?: com.sysadmindoc.alarmclock.data.repository.FileHolidayReconcileStateStore(context)
    )

    private val atomicFile = AtomicFile(file)
    private val lock = Any()

    @Volatile
    private var memorySnapshot: Map<LocalDate, ManualHolidayOverride> = emptyMap()

    @Volatile
    override var isCorrupted: Boolean = false
        private set

    @Volatile
    override var corruptionCause: Throwable? = null
        private set

    @Volatile
    override var lastReconcileResult: HolidayReconcileResult? = null
        private set

    init {
        loadFromFile()
    }

    private fun loadFromFile() {
        if (!file.exists()) {
            memorySnapshot = emptyMap()
            isCorrupted = false
            corruptionCause = null
            return
        }
        try {
            if (file.length() == 0L) {
                throw CorruptedManualHolidayAuthorityException("Manual holiday override file exists but is 0 bytes (corrupted/truncated)")
            }
            val bytes = atomicFile.readFully()
            if (bytes.isEmpty()) {
                throw CorruptedManualHolidayAuthorityException("Manual holiday override file is empty (corrupted/truncated)")
            }
            val text = String(bytes, StandardCharsets.UTF_8).trim()
            if (text.isEmpty()) {
                throw CorruptedManualHolidayAuthorityException("Manual holiday override file contains only whitespace (corrupted/truncated)")
            }
            val payload = ManualHolidayOverridesJsonCodec.decode(text)
            if (payload.formatVersion != 1) {
                throw IllegalArgumentException("Unsupported or missing formatVersion in manual holiday overrides file: ${payload.formatVersion}")
            }
            val loaded = LinkedHashMap<LocalDate, ManualHolidayOverride>()
            for ((key, valueStr) in payload.overrides) {
                val date = LocalDate.parse(key)
                val override = ManualHolidayOverride.valueOf(valueStr)
                if (override != ManualHolidayOverride.AUTO) {
                    loaded[date] = override
                }
            }
            memorySnapshot = loaded
            isCorrupted = false
            corruptionCause = null
        } catch (e: Exception) {
            isCorrupted = true
            corruptionCause = e
            memorySnapshot = emptyMap()
            preserveCorruptedFile(e)
        }
    }

    private fun preserveCorruptedFile(cause: Exception) {
        runCatching {
            if (file.exists()) {
                val backup = File(file.parentFile, "${file.name}.corrupt.${clockMillis()}")
                file.copyTo(backup, overwrite = true)
            }
        }
    }

    private fun writeToDisk(map: Map<LocalDate, ManualHolidayOverride>) {
        val payload = ManualHolidayOverridesPayload(
            formatVersion = 1,
            updatedAtMillis = clockMillis(),
            overrides = map.toSortedMap().mapKeys { it.key.toString() }.mapValues { it.value.name }
        )
        val jsonString = ManualHolidayOverridesJsonCodec.encode(payload)
        val bytes = jsonString.toByteArray(StandardCharsets.UTF_8)
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

    private fun checkNotCorrupted(operation: String) {
        if (isCorrupted) {
            throw CorruptedManualHolidayAuthorityException(
                "Manual holiday override store is corrupted; $operation is rejected to prevent silent data loss",
                corruptionCause
            )
        }
    }

    override fun overrideFor(date: LocalDate): ManualHolidayOverride {
        checkNotCorrupted("overrideFor($date)")
        return memorySnapshot[date] ?: ManualHolidayOverride.AUTO
    }

    override fun snapshotOverrides(
        startDate: LocalDate,
        endDateInclusive: LocalDate
    ): Map<LocalDate, ManualHolidayOverride> {
        checkNotCorrupted("snapshotOverrides([$startDate, $endDateInclusive])")
        if (endDateInclusive.isBefore(startDate)) return emptyMap()
        val current = memorySnapshot
        val result = LinkedHashMap<LocalDate, ManualHolidayOverride>()
        var cursor = startDate
        while (!cursor.isAfter(endDateInclusive)) {
            val override = current[cursor]
            if (override != null && override != ManualHolidayOverride.AUTO) {
                result[cursor] = override
            }
            cursor = cursor.plusDays(1)
        }
        return result
    }

    internal suspend fun notifyReconciliation(): HolidayReconcileResult {
        val coordinator = try {
            reconcileCoordinatorProvider?.invoke()
        } catch (e: Exception) {
            markPendingReconcileDurable(e)
            val result = HolidayReconcileResult.PendingRetry(1, e)
            lastReconcileResult = result
            return result
        }

        if (coordinator == null) {
            val err = IllegalStateException("HolidayReconcileCoordinator is unavailable")
            markPendingReconcileDurable(err)
            val result = HolidayReconcileResult.PendingRetry(1, err)
            lastReconcileResult = result
            return result
        }

        val result = try {
            coordinator.reconcile(
                isMaterialChange = true,
                previousClassifier = null,
                evaluationEpochMs = clockMillis(),
                evaluationZoneId = java.time.ZoneId.systemDefault()
            )
        } catch (e: Exception) {
            android.util.Log.w("FileManualHolidayOverrideStore", "Post-mutation reconciliation reported error", e)
            HolidayReconcileResult.PendingRetry(1, e)
        }

        if (result is HolidayReconcileResult.PendingRetry) {
            markPendingReconcileDurable(result.failureCause)
        }
        lastReconcileResult = result
        return result
    }

    private fun markPendingReconcileDurable(cause: Throwable?) {
        try {
            val store = reconcileStateStore
            if (store != null) {
                val current = store.load()
                if (current.pendingSignature == null) {
                    store.save(current.copy(pendingSignature = "PENDING_MANUAL_RECONCILE"))
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("FileManualHolidayOverrideStore", "Failed to mark durable pending reconcile", e)
        }
    }

    override suspend fun setOverride(date: LocalDate, override: ManualHolidayOverride): HolidayReconcileResult? {
        val didChange = synchronized(lock) {
            checkNotCorrupted("setOverride")
            val current = memorySnapshot
            val existing = current[date] ?: ManualHolidayOverride.AUTO
            if (existing == override) return@synchronized false

            val next = current.toMutableMap()
            if (override == ManualHolidayOverride.AUTO) {
                next.remove(date)
            } else {
                next[date] = override
            }
            writeToDisk(next)
            memorySnapshot = next
            true
        }
        return if (didChange) {
            notifyReconciliation()
        } else {
            HolidayReconcileResult.Success(reconciledCount = 0, isNoOp = true)
        }
    }

    override suspend fun clearOverride(date: LocalDate): HolidayReconcileResult? {
        return setOverride(date, ManualHolidayOverride.AUTO)
    }

    override suspend fun setRange(
        startDate: LocalDate,
        endDateInclusive: LocalDate,
        override: ManualHolidayOverride,
        overwriteExisting: Boolean
    ): RangeMutationResult {
        require(!endDateInclusive.isBefore(startDate)) {
            "endDateInclusive ($endDateInclusive) must not be before startDate ($startDate)"
        }
        val result = synchronized(lock) {
            checkNotCorrupted("setRange")
            val current = memorySnapshot

            if (override == ManualHolidayOverride.AUTO) {
                var cursor = startDate
                val datesToClear = mutableListOf<LocalDate>()
                while (!cursor.isAfter(endDateInclusive)) {
                    if (current.containsKey(cursor)) {
                        datesToClear.add(cursor)
                    }
                    cursor = cursor.plusDays(1)
                }
                if (datesToClear.isEmpty()) {
                    return@synchronized RangeMutationResult.NoOp
                }
                val next = current.toMutableMap()
                datesToClear.forEach { next.remove(it) }
                writeToDisk(next)
                memorySnapshot = next
                return@synchronized RangeMutationResult.Applied(affectedDatesCount = datesToClear.size)
            }

            var cursor = startDate
            val conflicts = mutableListOf<LocalDate>()
            var identicalCount = 0
            var totalDates = 0
            while (!cursor.isAfter(endDateInclusive)) {
                totalDates++
                val existing = current[cursor] ?: ManualHolidayOverride.AUTO
                if (existing == override) {
                    identicalCount++
                } else if (existing != ManualHolidayOverride.AUTO) {
                    conflicts.add(cursor)
                }
                cursor = cursor.plusDays(1)
            }

            if (conflicts.isNotEmpty() && !overwriteExisting) {
                return@synchronized RangeMutationResult.Conflict(conflicts)
            }

            if (identicalCount == totalDates) {
                return@synchronized RangeMutationResult.NoOp
            }

            val next = current.toMutableMap()
            cursor = startDate
            var modifiedCount = 0
            while (!cursor.isAfter(endDateInclusive)) {
                val existing = current[cursor] ?: ManualHolidayOverride.AUTO
                if (existing != override) {
                    next[cursor] = override
                    modifiedCount++
                }
                cursor = cursor.plusDays(1)
            }

            writeToDisk(next)
            memorySnapshot = next
            RangeMutationResult.Applied(affectedDatesCount = modifiedCount)
        }
        if (result is RangeMutationResult.Applied) {
            val reconcileResult = notifyReconciliation()
            return result.copy(reconcileResult = reconcileResult)
        }
        return result
    }

    override suspend fun getAllOverrides(): Map<LocalDate, ManualHolidayOverride> {
        checkNotCorrupted("getAllOverrides")
        return memorySnapshot
    }

    override suspend fun restoreOverrides(
        overrides: Map<LocalDate, ManualHolidayOverride>,
        triggerReconcile: Boolean
    ): HolidayReconcileResult? {
        val didChange = synchronized(lock) {
            checkNotCorrupted("restoreOverrides")
            val clean = overrides.filterValues { it != ManualHolidayOverride.AUTO }
            if (clean == memorySnapshot) return@synchronized false
            writeToDisk(clean)
            memorySnapshot = clean
            true
        }
        return if (didChange) {
            if (triggerReconcile) {
                notifyReconciliation()
            } else {
                null
            }
        } else {
            if (triggerReconcile) HolidayReconcileResult.Success(reconciledCount = 0, isNoOp = true) else null
        }
    }

    override fun recoverFromCorruption(
        clearCorruptedFile: Boolean,
        initialOverrides: Map<LocalDate, ManualHolidayOverride>
    ) {
        synchronized(lock) {
            isCorrupted = false
            corruptionCause = null
            val clean = initialOverrides.filterValues { it != ManualHolidayOverride.AUTO }
            if (clearCorruptedFile) {
                try {
                    writeToDisk(clean)
                } catch (_: Exception) {
                    runCatching { atomicFile.delete() }
                }
            }
            memorySnapshot = clean
        }
    }
}
