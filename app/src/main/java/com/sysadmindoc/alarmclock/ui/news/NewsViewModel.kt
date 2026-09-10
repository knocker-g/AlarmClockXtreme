package com.sysadmindoc.alarmclock.ui.news

import android.content.res.Resources
import com.sysadmindoc.alarmclock.R
import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sysadmindoc.alarmclock.data.news.NewsItem
import com.sysadmindoc.alarmclock.data.news.NewsRepository
import com.sysadmindoc.alarmclock.data.news.NewsSourceRepository
import com.sysadmindoc.alarmclock.data.local.entity.NewsSource
import com.sysadmindoc.alarmclock.data.preferences.PreferencesManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException
import javax.inject.Inject

data class NewsUiState(
    val sources: List<NewsSource> = emptyList(),
    val activeSourceId: Long? = null,
    val activeFeedUrl: String = "",
    val items: List<NewsItem> = emptyList(),
    val loading: Boolean = false,
    val refreshing: Boolean = false,
    val errorMessage: String? = null,
    val lastUpdatedMillis: Long? = null,
    val isStale: Boolean = false,
    val staleMessage: String? = null,
)

@HiltViewModel
class NewsViewModel @Inject constructor(
    @dagger.hilt.android.qualifiers.ApplicationContext
    private val appContext: android.content.Context,
    private val repository: NewsRepository,
    private val sourceRepository: NewsSourceRepository,
    private val preferencesManager: PreferencesManager,
) : ViewModel() {

    private val _uiState = MutableStateFlow(NewsUiState())
    val uiState: StateFlow<NewsUiState> = _uiState.asStateFlow()

    private var loadJob: Job? = null

    init {
        // v1.11.2 (ALA-5): Seed defaults if this is the first time the news
        // feature is used. This runs exactly once per install/locale.
        viewModelScope.launch {
            sourceRepository.seedIfNeeded()
        }

        viewModelScope.launch {
            combine(
                sourceRepository.observeAll(),
                preferencesManager.settings.map { it.newsActiveSourceId to it.newsSourcesSeeded }.distinctUntilChanged()
            ) { sources, (activeId, seeded) ->
                if (!seeded) return@combine
                
                val activeSource = sources.firstOrNull { it.id == activeId }
                    ?: sources.firstOrNull()
                
                val effectiveUrl = activeSource?.feedUrl.orEmpty()

                if (effectiveUrl != _uiState.value.activeFeedUrl || 
                    activeSource?.id != _uiState.value.activeSourceId ||
                    sources != _uiState.value.sources) {
                    
                    val urlChanged = effectiveUrl != _uiState.value.activeFeedUrl && effectiveUrl.isNotBlank()

                    _uiState.value = _uiState.value.copy(
                        sources = sources,
                        activeSourceId = activeSource?.id,
                        activeFeedUrl = effectiveUrl
                    )
                    
                    if (urlChanged) {
                        refresh()
                    }
                }
            }.collect()
        }
    }

    fun selectSource(id: Long) {
        if (id == _uiState.value.activeSourceId) return
        viewModelScope.launch {
            preferencesManager.update { it.copy(newsActiveSourceId = id) }
        }
    }

    fun refresh() {
        val url = _uiState.value.activeFeedUrl
        val sourceId = _uiState.value.activeSourceId
        if (url.isBlank() || sourceId == null) return

        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            val isFirstLoad = _uiState.value.items.isEmpty()
            _uiState.value = _uiState.value.copy(
                loading = isFirstLoad,
                refreshing = !isFirstLoad,
                errorMessage = null
            )
            repository.fetchFeed(sourceId, url)
                .onSuccess { snapshot ->
                    _uiState.value = _uiState.value.copy(
                        loading = false,
                        refreshing = false,
                        items = snapshot.items,
                        errorMessage = null,
                        lastUpdatedMillis = snapshot.fetchedAtMillis,
                        isStale = snapshot.isStale,
                        staleMessage = if (snapshot.isStale) {
                            buildNewsStaleMessage(appContext.resources, snapshot.refreshError)
                        } else {
                            null
                        },
                    )
                }
                .onFailure { error ->
                    _uiState.value = _uiState.value.copy(
                        loading = false,
                        refreshing = false,
                        errorMessage = appContext.getString(newsLoadErrorMessage(error)),
                        isStale = false,
                        staleMessage = null,
                    )
                }
        }
    }
}

private fun buildNewsStaleMessage(resources: Resources, error: Throwable?): String {
    val reason = resources.getString(
        error?.let(::newsLoadErrorMessage) ?: R.string.news_error_generic
    )
    return resources.getString(R.string.news_stale_headlines, reason)
}

/**
 * The message id for [error]. An id rather than the text: this runs in the
 * ViewModel, where the Resources to read it from belongs to the caller.
 */
@StringRes
internal fun newsLoadErrorMessage(error: Throwable): Int {
    val message = error.message.orEmpty()
    return when {
        error is UnknownHostException ->
            R.string.news_error_no_connection
        error is SocketTimeoutException ->
            R.string.news_error_timeout
        error is SSLException ->
            R.string.news_error_tls
        error is IllegalArgumentException ->
            R.string.news_error_invalid_url
        message.contains("HTTP 401") || message.contains("HTTP 403") ->
            R.string.news_error_forbidden
        message.contains("HTTP", ignoreCase = true) ->
            R.string.news_error_unresponsive
        message.contains("Empty response body", ignoreCase = true) ->
            R.string.news_error_empty
        error is IOException ->
            R.string.news_error_io
        else ->
            R.string.news_error_unreadable
    }
}
