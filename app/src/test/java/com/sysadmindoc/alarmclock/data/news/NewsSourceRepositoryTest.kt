package com.sysadmindoc.alarmclock.data.news

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.sysadmindoc.alarmclock.data.local.NewsSourceDao
import com.sysadmindoc.alarmclock.data.local.entity.NewsSource
import com.sysadmindoc.alarmclock.data.preferences.AppSettings
import com.sysadmindoc.alarmclock.data.preferences.PreferencesManager
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class NewsSourceRepositoryTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var dao: NewsSourceDao
    private lateinit var preferencesManager: PreferencesManager
    private lateinit var repository: NewsSourceRepository

    @Before
    fun setUp() {
        dao = mockk(relaxed = true)
        preferencesManager = mockk(relaxed = true)
        repository = NewsSourceRepository(context, dao, preferencesManager)
    }

    @Test
    fun `seedIfNeeded does nothing if already seeded`() = runTest {
        coEvery { preferencesManager.getCurrentSettings() } returns AppSettings(newsSourcesSeeded = true)
        
        repository.seedIfNeeded()
        
        coVerify(exactly = 0) { dao.insertAll(any()) }
    }

    @Test
    fun `seedIfNeeded inserts fixed 6 defaults regardless of locale`() = runTest {
        coEvery { preferencesManager.getCurrentSettings() } returns AppSettings(
            newsSourcesSeeded = false,
            newsFeedUrl = "https://news.google.com/rss?hl=en-US&gl=US&ceid=US:en"
        )
        
        val insertedSources = slot<List<NewsSource>>()
        coEvery { dao.insertAll(capture(insertedSources)) } returns Unit
        coEvery { dao.getAll() } answers {
            insertedSources.captured.mapIndexed { index, source -> source.copy(id = index + 1L) }
        }

        repository.seedIfNeeded()

        coVerify { dao.insertAll(any()) }
        assertEquals(6, insertedSources.captured.size)
        assertEquals("Top", insertedSources.captured[0].name)
        assertEquals("BBC", insertedSources.captured[3].name)
        
        val updateSlot = slot<(AppSettings) -> AppSettings>()
        coVerify { preferencesManager.update(capture(updateSlot)) }
        val result = updateSlot.captured(AppSettings())
        assertTrue(result.newsSourcesSeeded)
        assertEquals(1L, result.newsActiveSourceId)
    }

    @Test
    fun `seedIfNeeded migrates unknown old URL as Custom source`() = runTest {
        val customUrl = "https://my.blog/feed"
        coEvery { preferencesManager.getCurrentSettings() } returns AppSettings(
            newsSourcesSeeded = false,
            newsFeedUrl = customUrl
        )
        
        coEvery { dao.insert(any()) } returns 100L // New ID for custom source

        repository.seedIfNeeded()

        coVerify { dao.insert(match { it.feedUrl == customUrl }) }
        val updateSlot = slot<(AppSettings) -> AppSettings>()
        coVerify { preferencesManager.update(capture(updateSlot)) }
        
        val result = updateSlot.captured(AppSettings())
        assertEquals(100L, result.newsActiveSourceId)
        assertTrue(result.newsSourcesSeeded)
    }
}
