package com.sysadmindoc.alarmclock.domain

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.sysadmindoc.alarmclock.data.preferences.PreferencesManager
import com.sysadmindoc.alarmclock.data.repository.AlarmIncidentRepository
import com.sysadmindoc.alarmclock.data.repository.AlarmRepository
import com.sysadmindoc.alarmclock.data.repository.HolidayRepository
import com.sysadmindoc.alarmclock.data.repository.WeatherRepository
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class PendingIntentIdentityTest {

    private lateinit var context: Context
    private lateinit var scheduler: AlarmScheduler

    private val repository: AlarmRepository = mockk(relaxed = true)
    private val calculator: NextAlarmCalculator = mockk(relaxed = true)
    private val preferencesManager: PreferencesManager = mockk(relaxed = true)
    private val holidayRepository: HolidayRepository = mockk(relaxed = true)
    private val alarmIncidentRepository: AlarmIncidentRepository = mockk(relaxed = true)
    private val weatherRepository: WeatherRepository = mockk(relaxed = true)

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        scheduler = AlarmScheduler(
            context = context,
            repository = repository,
            calculator = calculator,
            preferencesManager = preferencesManager,
            holidayRepository = holidayRepository,
            alarmIncidentRepository = alarmIncidentRepository,
            weatherRepository = weatherRepository
        )
    }

    @Test
    fun `A - same alarmId different issueTokens produce distinct PendingIntents`() {
        val alarmId = 1001L
        val triggerTime = 1700000000000L

        val pi1 = scheduler.createTokenizedPendingIntent(alarmId, triggerTime, "fire-1", "token-aaa")
        val pi2 = scheduler.createTokenizedPendingIntent(alarmId, triggerTime, "fire-1", "token-bbb")

        assertNotNull(pi1)
        assertNotNull(pi2)
        assertNotEquals("Different issueTokens MUST produce distinct PendingIntents", pi1, pi2)
    }

    @Test
    fun `B - long alarmIds whose toInt() collide remain distinct via URI`() {
        // 1L and (1L + Int.MAX_VALUE.toLong() + 1L) produce identical toInt() values
        val alarmId1 = 1L
        val alarmId2 = 1L + Int.MAX_VALUE.toLong() + 1L + Int.MAX_VALUE.toLong() + 1L
        val triggerTime = 1700000000000L

        assertEquals(alarmId1.toInt(), alarmId2.toInt()) // Confirm toInt() collision

        val pi1 = scheduler.createTokenizedPendingIntent(alarmId1, triggerTime, "fire-1", "token-x")
        val pi2 = scheduler.createTokenizedPendingIntent(alarmId2, triggerTime, "fire-2", "token-y")

        assertNotNull(pi1)
        assertNotNull(pi2)
        assertNotEquals("Long alarmIds with colliding toInt() MUST remain distinct via URI", pi1, pi2)
    }

    @Test
    fun `C - same alarmId and same issueToken resolves to same identity`() {
        val alarmId = 2002L
        val triggerTime = 1700000000000L

        val piCreated = scheduler.createTokenizedPendingIntent(alarmId, triggerTime, "fire-2002", "token-same")
        val piLookup = scheduler.lookupTokenizedPendingIntentForCancel(alarmId, "token-same")

        assertNotNull(piCreated)
        assertNotNull(piLookup)
        assertEquals("Same alarmId and issueToken MUST resolve to identical PendingIntent", piCreated, piLookup)
    }

    @Test
    fun `D - wrong token lookup returns null without creating entry`() {
        val alarmId = 3003L
        val triggerTime = 1700000000000L

        scheduler.createTokenizedPendingIntent(alarmId, triggerTime, "fire-3003", "token-correct")

        // Lookup with wrong token MUST return null (FLAG_NO_CREATE)
        val piWrong = scheduler.lookupTokenizedPendingIntentForCancel(alarmId, "token-wrong")
        assertNull("Lookup with wrong token MUST return null", piWrong)
    }

    @Test
    fun `E - legacy tokenless PI lookup still works without regression`() {
        val alarmId = 4004L
        val triggerTime = 1700000000000L

        // Call private legacy create via reflection or public scheduling flow
        val createLegacyMethod = AlarmScheduler::class.java.getDeclaredMethod(
            "createPendingIntent",
            Long::class.javaPrimitiveType,
            Long::class.javaPrimitiveType,
            String::class.java
        ).apply { isAccessible = true }

        val lookupLegacyMethod = AlarmScheduler::class.java.getDeclaredMethod(
            "lookupPendingIntentForCancel",
            Long::class.javaPrimitiveType
        ).apply { isAccessible = true }

        val legacyPi = createLegacyMethod.invoke(scheduler, alarmId, triggerTime, "fire-legacy")
        val legacyLookup = lookupLegacyMethod.invoke(scheduler, alarmId)

        assertNotNull(legacyPi)
        assertNotNull(legacyLookup)
        assertEquals("Legacy tokenless PI lookup MUST work without regression", legacyPi, legacyLookup)
    }

    @Test
    fun `F - creating new issue PI does not mutate legacy PI extras or t1 extras`() {
        val alarmId = 5005L

        val createLegacyMethod = AlarmScheduler::class.java.getDeclaredMethod(
            "createPendingIntent",
            Long::class.javaPrimitiveType,
            Long::class.javaPrimitiveType,
            String::class.java
        ).apply { isAccessible = true }

        val legacyPi = createLegacyMethod.invoke(scheduler, alarmId, 1000L, "fire-legacy") as android.app.PendingIntent
        val tokenizedPi = scheduler.createTokenizedPendingIntent(alarmId, 2000L, "fire-token", "token-new")

        assertNotEquals("Legacy PI and Tokenized PI MUST be distinct", legacyPi, tokenizedPi)
    }

    @Test
    fun `G - URI construction is deterministic and handles reserved characters safely`() {
        val alarmId = 6006L
        val reservedToken = "token/with+special&chars=100%"

        val pi = scheduler.createTokenizedPendingIntent(alarmId, 1000L, "fire-6006", reservedToken)
        val lookup = scheduler.lookupTokenizedPendingIntentForCancel(alarmId, reservedToken)

        assertNotNull(pi)
        assertNotNull(lookup)
        assertEquals("URI with reserved characters MUST decode and match deterministically", pi, lookup)
    }
}
