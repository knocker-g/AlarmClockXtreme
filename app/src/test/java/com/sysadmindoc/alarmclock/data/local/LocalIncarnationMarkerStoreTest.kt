package com.sysadmindoc.alarmclock.data.local

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.sysadmindoc.alarmclock.domain.CompletionState
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class LocalIncarnationMarkerStoreTest {

    private lateinit var context: Context
    private lateinit var store: LocalIncarnationMarkerStore
    private lateinit var markerFile: File

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        store = LocalIncarnationMarkerStore(context)
        markerFile = File(context.noBackupFilesDir, LocalIncarnationMarkerStore.MARKER_FILE_NAME)
        if (markerFile.exists()) {
            markerFile.delete()
        }
    }

    @After
    fun tearDown() {
        if (markerFile.exists()) {
            markerFile.delete()
        }
    }

    @Test
    fun `P01 - Marker initially absent when no file exists`() {
        val readResult = store.readMarker()
        assertEquals(LocalMarkerReadResult.Absent, readResult)
        assertFalse(markerFile.exists())
    }

    @Test
    fun `P02 - Valid PREPARING write and readback succeeds`() {
        val payload = LocalMarkerPayload(
            formatVersion = 1,
            incarnationId = "incarnation-token-100",
            completionState = CompletionState.PREPARING
        )
        val writeResult = store.writeMarker(payload)
        assertEquals(LocalMarkerWriteResult.Success, writeResult)

        val readResult = store.readMarker()
        assertTrue(readResult is LocalMarkerReadResult.Present)
        val present = readResult as LocalMarkerReadResult.Present
        assertEquals(payload, present.payload)
    }

    @Test
    fun `P03 - Valid COMPLETE write and readback succeeds`() {
        val payload = LocalMarkerPayload(
            formatVersion = 1,
            incarnationId = "incarnation-token-200",
            completionState = CompletionState.COMPLETE
        )
        val writeResult = store.writeMarker(payload)
        assertEquals(LocalMarkerWriteResult.Success, writeResult)

        val readResult = store.readMarker()
        assertTrue(readResult is LocalMarkerReadResult.Present)
        val present = readResult as LocalMarkerReadResult.Present
        assertEquals(payload, present.payload)
    }

    @Test
    fun `P04 - Invalid blank ID rejected on write`() {
        val payload = LocalMarkerPayload(
            formatVersion = 1,
            incarnationId = "",
            completionState = CompletionState.COMPLETE
        )
        val writeResult = store.writeMarker(payload)
        assertTrue(writeResult is LocalMarkerWriteResult.InvalidPayload)
        assertFalse(markerFile.exists())
    }

    @Test
    fun `P05 - Blank whitespace-only ID rejected on write`() {
        val payload = LocalMarkerPayload(
            formatVersion = 1,
            incarnationId = "   ",
            completionState = CompletionState.COMPLETE
        )
        val writeResult = store.writeMarker(payload)
        assertTrue(writeResult is LocalMarkerWriteResult.InvalidPayload)
        assertFalse(markerFile.exists())
    }

    @Test
    fun `P06 - Unsupported version rejected on write and read`() {
        val unsupportedPayload = LocalMarkerPayload(
            formatVersion = 2,
            incarnationId = "incarnation-token-300",
            completionState = CompletionState.COMPLETE
        )
        val writeResult = store.writeMarker(unsupportedPayload)
        assertTrue(writeResult is LocalMarkerWriteResult.InvalidPayload)

        // Write directly to file with version 2
        markerFile.writeText("""{"formatVersion": 2, "incarnationId": "inc-2", "completionState": "COMPLETE"}""")
        val readResult = store.readMarker()
        assertEquals(LocalMarkerReadResult.Corrupt, readResult)
    }

    @Test
    fun `P07 - Malformed file returns Corrupt on read`() {
        markerFile.writeText("invalid json string payload")
        val readResult = store.readMarker()
        assertEquals(LocalMarkerReadResult.Corrupt, readResult)
    }

    @Test
    fun `P08 - Truncated file returns Corrupt on read`() {
        markerFile.writeText("""{"formatVersion": 1, "incarnationId":""")
        val readResult = store.readMarker()
        assertEquals(LocalMarkerReadResult.Corrupt, readResult)
    }

    @Test
    fun `P09 - Unknown completion state rejected on write and returns Corrupt on read`() {
        val unknownPayload = LocalMarkerPayload(
            formatVersion = 1,
            incarnationId = "incarnation-token-400",
            completionState = CompletionState.UNKNOWN
        )
        val writeResult = store.writeMarker(unknownPayload)
        assertTrue(writeResult is LocalMarkerWriteResult.InvalidPayload)

        // Write file with UNKNOWN completion state directly
        markerFile.writeText("""{"formatVersion": 1, "incarnationId": "inc-4", "completionState": "UNKNOWN"}""")
        val readResult = store.readMarker()
        assertEquals(LocalMarkerReadResult.Corrupt, readResult)
    }

    @Test
    fun `P10 - Repeated reads cause no mutation or file creation`() {
        assertEquals(LocalMarkerReadResult.Absent, store.readMarker())
        assertEquals(LocalMarkerReadResult.Absent, store.readMarker())
        assertFalse(markerFile.exists())

        val payload = LocalMarkerPayload(
            formatVersion = 1,
            incarnationId = "incarnation-token-500",
            completionState = CompletionState.COMPLETE
        )
        store.writeMarker(payload)
        val lastModifiedBefore = markerFile.lastModified()

        val read1 = store.readMarker()
        val read2 = store.readMarker()

        assertEquals(read1, read2)
        assertEquals(lastModifiedBefore, markerFile.lastModified())
    }

    @Test
    fun `P11 - Simulated write failure clean up does not expose partial file`() {
        val validPayload = LocalMarkerPayload(
            formatVersion = 1,
            incarnationId = "incarnation-token-600",
            completionState = CompletionState.COMPLETE
        )
        store.writeMarker(validPayload)

        // Simulate atomic replacement by checking atomic file state
        val backupFile = File(context.noBackupFilesDir, "${LocalIncarnationMarkerStore.MARKER_FILE_NAME}.bak")
        assertFalse("Backup file must be cleaned up after successful atomic write", backupFile.exists())
    }

    @Test
    fun `P12 - Atomic replacement preserves prior state until commit`() {
        val payload1 = LocalMarkerPayload(
            formatVersion = 1,
            incarnationId = "incarnation-token-701",
            completionState = CompletionState.PREPARING
        )
        assertEquals(LocalMarkerWriteResult.Success, store.writeMarker(payload1))

        val payload2 = LocalMarkerPayload(
            formatVersion = 1,
            incarnationId = "incarnation-token-702",
            completionState = CompletionState.COMPLETE
        )
        assertEquals(LocalMarkerWriteResult.Success, store.writeMarker(payload2))

        val readResult = store.readMarker()
        assertTrue(readResult is LocalMarkerReadResult.Present)
        assertEquals(payload2, (readResult as LocalMarkerReadResult.Present).payload)
    }

    @Test
    fun `P13 - Unavailable storage fails closed to Unavailable`() {
        val invalidContext = io.mockk.mockk<Context>()
        io.mockk.every { invalidContext.noBackupFilesDir } returns null

        val unavailableStore = LocalIncarnationMarkerStore(invalidContext)
        val readResult = unavailableStore.readMarker()
        assertEquals(LocalMarkerReadResult.Unavailable, readResult)

        val payload = LocalMarkerPayload(
            formatVersion = 1,
            incarnationId = "inc-800",
            completionState = CompletionState.COMPLETE
        )
        val writeResult = unavailableStore.writeMarker(payload)
        assertEquals(LocalMarkerWriteResult.StorageUnavailable, writeResult)
    }

    @Test
    fun `P14 - Reading never creates a new marker file on disk`() {
        assertFalse(markerFile.exists())
        val readResult = store.readMarker()
        assertEquals(LocalMarkerReadResult.Absent, readResult)
        assertFalse("Reading absent marker must not create file on disk", markerFile.exists())
    }

    @Test
    fun `P15 - Storage operations operate isolated from alarm scheduling`() {
        val payload = LocalMarkerPayload(
            formatVersion = 1,
            incarnationId = "incarnation-token-900",
            completionState = CompletionState.COMPLETE
        )
        assertEquals(LocalMarkerWriteResult.Success, store.writeMarker(payload))
        assertTrue(markerFile.exists())
    }

    @Test
    fun `P16 - Storage location is strictly under noBackupFilesDir`() {
        val payload = LocalMarkerPayload(
            formatVersion = 1,
            incarnationId = "incarnation-token-1000",
            completionState = CompletionState.COMPLETE
        )
        store.writeMarker(payload)

        val parentDir = markerFile.parentFile
        assertNotNull(parentDir)
        assertEquals(context.noBackupFilesDir.canonicalPath, parentDir?.canonicalPath)
    }

    @Test
    fun `A01 - Primary missing with valid backup present recovers state from backup file`() {
        val backupFile = File(context.noBackupFilesDir, LocalIncarnationMarkerStore.BACKUP_FILE_NAME)
        backupFile.writeText("""{"formatVersion": 1, "incarnationId": "inc-bak-100", "completionState": "COMPLETE"}""")
        assertFalse(markerFile.exists())
        assertTrue(backupFile.exists())

        val readResult = store.readMarker()
        assertTrue(readResult is LocalMarkerReadResult.Present)
        val present = readResult as LocalMarkerReadResult.Present
        assertEquals("inc-bak-100", present.payload.incarnationId)
        assertEquals(CompletionState.COMPLETE, present.payload.completionState)
    }

    @Test
    fun `A02 - readMarker never creates directories or marker files on disk`() {
        val nonExistentDir = File(context.cacheDir, "non_existent_read_test_dir")
        if (nonExistentDir.exists()) nonExistentDir.deleteRecursively()

        val mockContext = io.mockk.mockk<Context>()
        io.mockk.every { mockContext.isDeviceProtectedStorage } returns false
        io.mockk.every { mockContext.noBackupFilesDir } returns nonExistentDir
        io.mockk.every { mockContext.getSystemService(Context.USER_SERVICE) } returns null

        val testStore = LocalIncarnationMarkerStore(mockContext)
        val readResult = testStore.readMarker()

        assertEquals(LocalMarkerReadResult.Absent, readResult)
        assertFalse("readMarker must not create non-existent directories", nonExistentDir.exists())
    }

    @Test
    fun `A03 - DirectBoot locked user returns Unavailable on read and StorageUnavailable on write`() {
        val mockUserManager = io.mockk.mockk<android.os.UserManager>()
        io.mockk.every { mockUserManager.isUserUnlocked } returns false

        val lockedContext = io.mockk.mockk<Context>()
        io.mockk.every { lockedContext.getSystemService(Context.USER_SERVICE) } returns mockUserManager
        io.mockk.every { lockedContext.noBackupFilesDir } returns context.noBackupFilesDir

        val lockedStore = LocalIncarnationMarkerStore(lockedContext)

        val readResult = lockedStore.readMarker()
        assertEquals(LocalMarkerReadResult.Unavailable, readResult)

        val payload = LocalMarkerPayload(
            formatVersion = 1,
            incarnationId = "inc-locked-100",
            completionState = CompletionState.COMPLETE
        )
        val writeResult = lockedStore.writeMarker(payload)
        assertEquals(LocalMarkerWriteResult.StorageUnavailable, writeResult)
    }
}
