package com.sysadmindoc.alarmclock.ui.news

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.sysadmindoc.alarmclock.data.local.entity.NewsSource
import com.sysadmindoc.alarmclock.data.news.NewsFeedSnapshot
import com.sysadmindoc.alarmclock.data.news.NewsItem
import com.sysadmindoc.alarmclock.data.news.NewsRepository
import com.sysadmindoc.alarmclock.data.news.NewsSourceRepository
import com.sysadmindoc.alarmclock.data.preferences.AppSettings
import com.sysadmindoc.alarmclock.data.preferences.PreferencesManager
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class NewsViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var repository: NewsRepository
    private lateinit var sourceRepository: NewsSourceRepository
    private lateinit var preferencesManager: PreferencesManager
    private val settingsFlow = MutableStateFlow(AppSettings())
    private val sources = listOf(
        NewsSource(id = 1L, name = "Source 1", feedUrl = "url1", sortOrder = 1),
        NewsSource(id = 2L, name = "Source 2", feedUrl = "url2", sortOrder = 2)
    )
    private val sourcesFlow = MutableStateFlow(sources)

    private val item1 = NewsItem(id = "1", title = "Headline 1", link = "https://example.com/1", description = "Desc 1", source = "Source 1", publishedAtMillis = 1000L)
    private val item2 = NewsItem(id = "2", title = "Headline 2", link = "https://example.com/2", description = "Desc 2", source = "Source 2", publishedAtMillis = 2000L)

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        repository = mockk(relaxed = true)
        sourceRepository = mockk(relaxed = true)
        preferencesManager = mockk(relaxed = true)

        every { preferencesManager.settings } returns settingsFlow
        every { sourceRepository.observeAll() } returns sourcesFlow

        coEvery { repository.fetchFeed(any(), any()) } returns Result.success(
            NewsFeedSnapshot(items = emptyList(), fetchedAtMillis = 1000L, isStale = false)
        )
    }

    @After
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `viewModel matches sources and active ID from preferences`() = runTest(dispatcher) {
        coEvery { repository.fetchFeed(2L, "url2") } returns Result.success(
            NewsFeedSnapshot(items = listOf(item2), fetchedAtMillis = 2000L, isStale = false)
        )
        settingsFlow.value = AppSettings(newsActiveSourceId = 2L, newsSourcesSeeded = true)

        val viewModel = NewsViewModel(context, repository, sourceRepository, preferencesManager)
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(sources, state.sources)
        assertEquals(2L, state.activeSourceId)
        assertEquals("url2", state.activeFeedUrl)
        assertEquals(listOf(item2), state.items)
    }

    @Test
    fun `viewModel falls back to first source if active ID is missing`() = runTest(dispatcher) {
        coEvery { repository.fetchFeed(1L, "url1") } returns Result.success(
            NewsFeedSnapshot(items = listOf(item1), fetchedAtMillis = 1000L, isStale = false)
        )
        settingsFlow.value = AppSettings(newsActiveSourceId = null, newsSourcesSeeded = true)

        val viewModel = NewsViewModel(context, repository, sourceRepository, preferencesManager)
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(1L, state.activeSourceId)
        assertEquals("url1", state.activeFeedUrl)
        assertEquals(listOf(item1), state.items)
    }

    @Test
    fun `items are cleared when last source is deleted`() = runTest(dispatcher) {
        settingsFlow.value = AppSettings(newsActiveSourceId = 1L, newsSourcesSeeded = true)
        val viewModel = NewsViewModel(context, repository, sourceRepository, preferencesManager)
        advanceUntilIdle()

        // Verify initial non-empty sources
        assertEquals(sources, viewModel.uiState.value.sources)

        // Delete all sources via the active StateFlow
        sourcesFlow.value = emptyList()
        settingsFlow.value = AppSettings(newsActiveSourceId = null, newsSourcesSeeded = true)
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(emptyList<NewsSource>(), state.sources)
        assertEquals(null, state.activeSourceId)
        assertEquals(emptyList<NewsItem>(), state.items)
    }

    @Test
    fun `items are cleared when switching to a different source`() = runTest(dispatcher) {
        coEvery { repository.fetchFeed(1L, "url1") } returns Result.success(
            NewsFeedSnapshot(items = listOf(item1), fetchedAtMillis = 1000L, isStale = false)
        )
        coEvery { repository.fetchFeed(2L, "url2") } returns Result.success(
            NewsFeedSnapshot(items = listOf(item2), fetchedAtMillis = 2000L, isStale = false)
        )

        settingsFlow.value = AppSettings(newsActiveSourceId = 1L, newsSourcesSeeded = true)
        val viewModel = NewsViewModel(context, repository, sourceRepository, preferencesManager)
        advanceUntilIdle()

        // Verify source 1 items loaded
        assertEquals(listOf(item1), viewModel.uiState.value.items)

        // Switch from source 1 to source 2
        settingsFlow.value = AppSettings(newsActiveSourceId = 2L, newsSourcesSeeded = true)
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(2L, state.activeSourceId)
        // Prove stale source-1 items are not present
        assertFalse("Stale source-1 items must be cleared", state.items.contains(item1))
        // Final state is populated strictly with source 2 deterministic result
        assertEquals(listOf(item2), state.items)
    }
}
