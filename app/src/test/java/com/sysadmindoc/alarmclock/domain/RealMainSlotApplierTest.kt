package com.sysadmindoc.alarmclock.domain

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.sysadmindoc.alarmclock.data.local.entity.ScheduleAuthority
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class RealMainSlotApplierTest {

    private lateinit var context: Context
    private lateinit var alarmScheduler: AlarmScheduler
    private lateinit var applier: RealMainSlotApplier

    private val testAuthority = ScheduleAuthority(
        alarmId = 42L,
        instanceToken = "token-instance-42",
        definitionRevision = 1L,
        scheduleRevision = 2L,
        lifecycle = "LIVE",
        desiredOperation = "SET",
        desiredTriggerMillis = 1700000000000L,
        reconcileState = "RECONCILING",
        restoreEpoch = 100L,
        actionKey = "act-100"
    )

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        alarmScheduler = mockk(relaxed = true)
        applier = RealMainSlotApplier(alarmScheduler)
    }

    @Test
    fun `applySlotOperation SET calls alarmScheduler applyRawOsSet`() = runBlocking {
        coEvery { alarmScheduler.applyRawOsSet(42L, 1700000000000L) } returns true

        val result = applier.applySlotOperation(testAuthority)

        assertTrue("SET operation must return true on applyRawOsSet success", result)
        coVerify(exactly = 1) { alarmScheduler.applyRawOsSet(42L, 1700000000000L) }
    }

    @Test
    fun `applySlotOperation CANCEL calls alarmScheduler applyRawOsCancel`() = runBlocking {
        val cancelAuthority = testAuthority.copy(desiredOperation = "CANCEL", desiredTriggerMillis = null)
        coEvery { alarmScheduler.applyRawOsCancel(42L) } returns true

        val result = applier.applySlotOperation(cancelAuthority)

        assertTrue("CANCEL operation must return true on applyRawOsCancel success", result)
        coVerify(exactly = 1) { alarmScheduler.applyRawOsCancel(42L) }
    }

    @Test
    fun `applySlotOperation unsupported operation returns false`() = runBlocking {
        val invalidAuthority = testAuthority.copy(desiredOperation = "INVALID_OP")

        val result = applier.applySlotOperation(invalidAuthority)

        assertFalse("Unsupported operation must return false", result)
    }
}
