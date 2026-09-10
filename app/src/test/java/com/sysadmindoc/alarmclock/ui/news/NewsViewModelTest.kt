package com.sysadmindoc.alarmclock.ui.news

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.sysadmindoc.alarmclock.data.news.NewsItem
import com.sysadmindoc.alarmclock.data.news.NewsRepository
import com.sysadmindoc.alarmclock.data.news.NewsSourceRepository
import com.sysadmindoc.alarmclock.data.local.entity.NewsSource
import com.sysadmindoc.alarmclock.data.preferences.AppSettings
import com.sysadmindoc.alarmclock.data.preferences.PreferencesManager
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
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

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        repository = mockk(relaxed = true)
        sourceRepository = mockk(relaxed = true)
        preferencesManager = mockk(relaxed = true)
        
        every { preferencesManager.settings } returns settingsFlow
        every { sourceRepository.observeAll() } returns flowOf(sources)
    }

    @After
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `viewModel matches sources and active ID from preferences`() = runTest(dispatcher) {
        settingsFlow.value = AppSettings(newsActiveSourceId = 2L, newsSourcesSeeded = true)
        
        val viewModel = NewsViewModel(context, repository, sourceRepository, preferencesManager)
        advanceUntilIdle()
        
        val state = viewModel.uiState.value
        assertEquals(sources, state.sources)
        assertEquals(2L, state.activeSourceId)
        assertEquals("url2", state.activeFeedUrl)
    }

    @Test
    fun `viewModel falls back to first source if active ID is missing`() = runTest(dispatcher) {
        settingsFlow.value = AppSettings(newsActiveSourceId = null, newsSourcesSeeded = true)
        
        val viewModel = NewsViewModel(context, repository, sourceRepository, preferencesManager)
        advanceUntilIdle()
        
        val state = viewModel.uiState.value
        assertEquals(1L, state.activeSourceId)
        assertEquals("url1", state.activeFeedUrl)
    }

    @Test
    fun `items are cleared when last source is deleted`() = runTest(dispatcher) {
        val viewModel = NewsViewModel(context, repository, sourceRepository, preferencesManager)
        advanceUntilIdle()
        
        // Simulate items loaded
        viewModel.refresh()
        advanceUntilIdle()
        
        // Delete last source
        every { sourceRepository.observeAll() } returns flowOf(emptyList())
        settingsFlow.value = AppSettings(newsActiveSourceId = null)
        advanceUntilIdle()
        
        val state = viewModel.uiState.value
        assertEquals(emptyList<NewsSource>(), state.sources)
        assertEquals(null, state.activeSourceId)
        assertEquals(emptyList<NewsItem>(), state.items)
    }

    @Test
    fun `items are cleared when switching to a different source`() = runTest(dispatcher) {
        val viewModel = NewsViewModel(context, repository, sourceRepository, preferencesManager)
        advanceUntilIdle()
        
        // Switch from source 1 to 2
        settingsFlow.value = AppSettings(newsActiveSourceId = 2L, newsSourcesSeeded = true)
        advanceUntilIdle()
        
        val state = viewModel.uiState.value
        assertEquals(2L, state.activeSourceId)
        assertEquals(emptyList<NewsItem>(), state.items)
    }
}
