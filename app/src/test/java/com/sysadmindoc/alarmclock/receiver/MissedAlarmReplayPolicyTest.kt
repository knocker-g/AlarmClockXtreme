package com.sysadmindoc.alarmclock.receiver

import com.sysadmindoc.alarmclock.receiver.MissedAlarmReplayPolicy.Decision
import com.sysadmindoc.alarmclock.receiver.MissedAlarmReplayPolicy.ACTION_POWER_DISCONNECTED
import com.sysadmindoc.alarmclock.receiver.MissedAlarmReplayPolicy.ACTION_USER_PRESENT
import com.sysadmindoc.alarmclock.receiver.MissedAlarmReplayPolicy.REPLAY_WINDOW_MS
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v1.5.2: Unit coverage for the repeat-missed-alarm replay window logic
 * that was inlined inside [MissedAlarmUnlockReceiver] through v1.5.1.
 */
class MissedAlarmReplayPolicyTest {

    private val now = 10_000_000L
    private val id = 42L

    @Test
    fun `replay triggers include unlock and power disconnect`() {
        assertTrue(MissedAlarmReplayPolicy.isReplayTrigger(ACTION_USER_PRESENT))
        assertTrue(MissedAlarmReplayPolicy.isReplayTrigger(ACTION_POWER_DISCONNECTED))
        assertFalse(MissedAlarmReplayPolicy.isReplayTrigger("android.intent.action.ACTION_POWER_CONNECTED"))
        assertFalse(MissedAlarmReplayPolicy.isReplayTrigger(null))
    }

    @Test
    fun `incident source identifies the replay trigger`() {
        assertEquals(
            "MissedAlarmUnlockReceiver",
            MissedAlarmReplayPolicy.sourceForTrigger(ACTION_USER_PRESENT)
        )
        assertEquals(
            "MissedAlarmPowerDisconnectReceiver",
            MissedAlarmReplayPolicy.sourceForTrigger(ACTION_POWER_DISCONNECTED)
        )
        assertEquals(
            "MissedAlarmUnlockReceiver",
            MissedAlarmReplayPolicy.sourceForTrigger("android.intent.action.SCREEN_ON")
        )
    }

    @Test
    fun `replays when inside window, feature on, no live alarm`() {
        val decision = MissedAlarmReplayPolicy.shouldReplay(
            repeatMissedEnabled = true,
            lastMissedAtMs = now - 60_000L, // 1 min ago
            lastMissedId = id,
            alarmCurrentlyFiringId = -1L,
            nowMs = now
        )
        assertEquals(Decision.REPLAY, decision)
        assertTrue(decision.shouldReplay)
        assertFalse("REPLAY decision MUST NOT clear state before positive delivery", decision.shouldClearState)
    }

    @Test
    fun `drops when feature disabled`() {
        val decision = MissedAlarmReplayPolicy.shouldReplay(
            repeatMissedEnabled = false,
            lastMissedAtMs = now - 60_000L,
            lastMissedId = id,
            alarmCurrentlyFiringId = -1L,
            nowMs = now
        )
        assertEquals(Decision.DROP_DISABLED, decision)
        assertFalse(decision.shouldReplay)
        assertTrue(decision.shouldClearState) // purge stale state even if off
    }

    @Test
    fun `drops when no recorded id`() {
        val decision = MissedAlarmReplayPolicy.shouldReplay(
            repeatMissedEnabled = true,
            lastMissedAtMs = now - 60_000L,
            lastMissedId = -1L,
            alarmCurrentlyFiringId = -1L,
            nowMs = now
        )
        assertEquals(Decision.DROP_NO_RECORD, decision)
    }

    @Test
    fun `drops when no recorded timestamp`() {
        val decision = MissedAlarmReplayPolicy.shouldReplay(
            repeatMissedEnabled = true,
            lastMissedAtMs = 0L,
            lastMissedId = id,
            alarmCurrentlyFiringId = -1L,
            nowMs = now
        )
        assertEquals(Decision.DROP_NO_RECORD, decision)
    }

    @Test
    fun `drops when outside window`() {
        val decision = MissedAlarmReplayPolicy.shouldReplay(
            repeatMissedEnabled = true,
            lastMissedAtMs = now - (REPLAY_WINDOW_MS + 1_000L),
            lastMissedId = id,
            alarmCurrentlyFiringId = -1L,
            nowMs = now
        )
        assertEquals(Decision.DROP_EXPIRED, decision)
    }

    @Test
    fun `drops at exact boundary because window is half-open`() {
        // v1.5.1 changed the range from `..` to `until` so a second unlock
        // landing exactly on the 10-minute boundary can't replay a miss
        // that is about to be overwritten by the next alarm.
        val decision = MissedAlarmReplayPolicy.shouldReplay(
            repeatMissedEnabled = true,
            lastMissedAtMs = now - REPLAY_WINDOW_MS,
            lastMissedId = id,
            alarmCurrentlyFiringId = -1L,
            nowMs = now
        )
        assertEquals(Decision.DROP_EXPIRED, decision)
    }

    @Test
    fun `drops if timestamp is in the future (clock drift)`() {
        // lastMissedAt after now — system clock jumped back. Treat as
        // expired rather than trying to replay.
        val decision = MissedAlarmReplayPolicy.shouldReplay(
            repeatMissedEnabled = true,
            lastMissedAtMs = now + 60_000L,
            lastMissedId = id,
            alarmCurrentlyFiringId = -1L,
            nowMs = now
        )
        assertEquals(Decision.DROP_EXPIRED, decision)
    }

    @Test
    fun `drops if another alarm is currently firing`() {
        val decision = MissedAlarmReplayPolicy.shouldReplay(
            repeatMissedEnabled = true,
            lastMissedAtMs = now - 60_000L,
            lastMissedId = id,
            alarmCurrentlyFiringId = 99L,
            nowMs = now
        )
        assertEquals(Decision.DROP_ALARM_LIVE, decision)
        assertFalse(decision.shouldReplay)
    }

    @Test
    fun `tryAcquireClaim grants lease once and blocks concurrent claims until released or expired`() = kotlinx.coroutines.runBlocking {
        val claim1 = MissedAlarmReplayPolicy.tryAcquireClaim(nowMs = now, leaseDurationMs = 15_000L)
        org.junit.Assert.assertNotNull("First claim attempt MUST succeed", claim1)

        val claim2 = MissedAlarmReplayPolicy.tryAcquireClaim(nowMs = now + 1_000L, leaseDurationMs = 15_000L)
        org.junit.Assert.assertNull("Concurrent claim attempt while lease active MUST return null", claim2)

        // After lease duration, new claim succeeds
        val claim3 = MissedAlarmReplayPolicy.tryAcquireClaim(nowMs = now + 16_000L, leaseDurationMs = 15_000L)
        org.junit.Assert.assertNotNull("Claim attempt after lease expiry MUST succeed", claim3)

        MissedAlarmReplayPolicy.releaseClaim(claim3!!)
    }

    @Test
    fun `clearMissedAlarmMarkerIfMatching prevents stale claimant A from deleting newer marker B`() = kotlinx.coroutines.runBlocking {
        val store = TestSharedPreferences()

        // 1. Producer B records missed alarm B (id = 200L, at = 2000L)
        MissedAlarmReplayPolicy.recordMissedAlarmMarker(store, alarmId = 200L, missedAtMs = 2000L, fireId = "fire-B")

        // 2. Stale claimant A (expecting id = 100L, at = 1000L) attempts conditional clear
        val clearedByStaleA = MissedAlarmReplayPolicy.clearMissedAlarmMarkerIfMatching(store, expectedId = 100L, expectedAt = 1000L)
        assertFalse("Stale claimant A MUST NOT clear marker B", clearedByStaleA)

        // Verify marker B remains intact in SharedPreferences
        assertEquals(200L, store.getLong("last_missed_id", -1L))
        assertEquals(2000L, store.getLong("last_missed_at", 0L))

        // 3. Matching claimant B attempts conditional clear -> succeeds
        val clearedByB = MissedAlarmReplayPolicy.clearMissedAlarmMarkerIfMatching(store, expectedId = 200L, expectedAt = 2000L)
        assertTrue("Matching claimant B MUST successfully clear marker B", clearedByB)
        assertEquals(-1L, store.getLong("last_missed_id", -1L))
    }

    private class TestSharedPreferences : android.content.SharedPreferences {
        private val data = java.util.concurrent.ConcurrentHashMap<String, Any>()

        override fun getAll(): MutableMap<String, *> = data
        override fun getString(key: String?, defValue: String?): String? = data[key] as? String ?: defValue
        override fun getStringSet(key: String?, defValue: MutableSet<String>?): MutableSet<String>? = defValue
        override fun getInt(key: String?, defValue: Int): Int = (data[key] as? Int) ?: defValue
        override fun getLong(key: String?, defValue: Long): Long = (data[key] as? Long) ?: defValue
        override fun getFloat(key: String?, defValue: Float): Float = (data[key] as? Float) ?: defValue
        override fun getBoolean(key: String?, defValue: Boolean): Boolean = (data[key] as? Boolean) ?: defValue
        override fun contains(key: String?): Boolean = data.containsKey(key)
        override fun edit(): android.content.SharedPreferences.Editor = TestEditor(data)
        override fun registerOnSharedPreferenceChangeListener(listener: android.content.SharedPreferences.OnSharedPreferenceChangeListener?) {}
        override fun unregisterOnSharedPreferenceChangeListener(listener: android.content.SharedPreferences.OnSharedPreferenceChangeListener?) {}

        private class TestEditor(private val data: MutableMap<String, Any>) : android.content.SharedPreferences.Editor {
            private val temp = mutableMapOf<String, Any>()
            private var clearFlag = false

            override fun putString(key: String?, value: String?): android.content.SharedPreferences.Editor { if (key != null && value != null) temp[key] = value; return this }
            override fun putStringSet(key: String?, values: MutableSet<String>?): android.content.SharedPreferences.Editor = this
            override fun putInt(key: String?, value: Int): android.content.SharedPreferences.Editor { if (key != null) temp[key] = value; return this }
            override fun putLong(key: String?, value: Long): android.content.SharedPreferences.Editor { if (key != null) temp[key] = value; return this }
            override fun putFloat(key: String?, value: Float): android.content.SharedPreferences.Editor { if (key != null) temp[key] = value; return this }
            override fun putBoolean(key: String?, value: Boolean): android.content.SharedPreferences.Editor { if (key != null) temp[key] = value; return this }
            override fun remove(key: String?): android.content.SharedPreferences.Editor { if (key != null) temp.remove(key); return this }
            override fun clear(): android.content.SharedPreferences.Editor { clearFlag = true; return this }
            override fun apply() { commit() }
            override fun commit(): Boolean {
                if (clearFlag) data.clear()
                data.putAll(temp)
                return true
            }
        }
    }

    @Test
    fun `terminal drop decisions request state clear so stale state cannot persist`() {
        Decision.entries.filter { it != Decision.REPLAY }.forEach { d ->
            assertTrue(
                "Terminal drop decision $d should always ask caller to clear state",
                d.shouldClearState
            )
        }
    }
}
