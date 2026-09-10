package com.sysadmindoc.alarmclock.data.news

import android.content.Context
import com.sysadmindoc.alarmclock.R
import com.sysadmindoc.alarmclock.data.local.NewsSourceDao
import com.sysadmindoc.alarmclock.data.local.entity.NewsSource
import com.sysadmindoc.alarmclock.data.preferences.PreferencesManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class NewsSourceRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val dao: NewsSourceDao,
    private val preferencesManager: PreferencesManager
) {
    fun observeAll(): Flow<List<NewsSource>> = dao.observeAll()

    suspend fun getAll(): List<NewsSource> = dao.getAll()

    suspend fun getById(id: Long): NewsSource? = dao.getById(id)

    suspend fun insert(source: NewsSource): Long {
        val maxOrder = dao.getMaxSortOrder() ?: 0
        return dao.insert(source.copy(sortOrder = maxOrder + 1))
    }

    suspend fun update(source: NewsSource) = dao.update(source)

    suspend fun delete(source: NewsSource) {
        val settings = preferencesManager.getCurrentSettings()
        if (settings.newsActiveSourceId == source.id) {
            val all = dao.getAll().filter { it.id != source.id }
            val nextId = all.firstOrNull()?.id
            preferencesManager.update { it.copy(newsActiveSourceId = nextId) }
        }
        dao.delete(source)
    }

    /**
     * v1.11.2 (ALA-5): Seed the news_sources table on first launch and
     * handle migration from the old single-URL preference.
     */
    suspend fun seedIfNeeded() {
        val settings = preferencesManager.getCurrentSettings()
        if (settings.newsSourcesSeeded) return

        // v1.11.3 (ALA-5): Fixed seed set (Top, World, Tech, BBC, NPR, HN)
        // regardless of locale. User data is persistent; locale only drives
        // UI labels elsewhere.
        val initialSources = listOf(
            NewsSource(name = "Top", feedUrl = "https://news.google.com/rss?hl=en-US&gl=US&ceid=US:en", sortOrder = 1),
            NewsSource(name = "World", feedUrl = "https://news.google.com/rss/headlines/section/topic/WORLD?hl=en-US&gl=US&ceid=US:en", sortOrder = 2),
            NewsSource(name = "Tech", feedUrl = "https://news.google.com/rss/headlines/section/topic/TECHNOLOGY?hl=en-US&gl=US&ceid=US:en", sortOrder = 3),
            NewsSource(name = "BBC", feedUrl = "https://feeds.bbci.co.uk/news/rss.xml", sortOrder = 4),
            NewsSource(name = "NPR", feedUrl = "https://feeds.npr.org/1001/rss.xml", sortOrder = 5),
            NewsSource(name = "Hacker News", feedUrl = "https://hnrss.org/frontpage", sortOrder = 6)
        )

        dao.insertAll(initialSources)

        // Migration from old news_feed_url
        val oldUrl = settings.newsFeedUrl
        val allSeeded = dao.getAll()
        
        val matchingSeeded = allSeeded.firstOrNull { it.feedUrl == oldUrl }
        if (matchingSeeded != null) {
            preferencesManager.update { 
                it.copy(newsActiveSourceId = matchingSeeded.id, newsSourcesSeeded = true) 
            }
        } else {
            // Old URL doesn't match any seeded default; treat as custom.
            val isDefaultOldUrl = oldUrl == "https://news.google.com/rss?hl=en-US&gl=US&ceid=US:en"
            if (!isDefaultOldUrl) {
                val customName = context.getString(R.string.news_custom_feed)
                val customSourceId = dao.insert(
                    NewsSource(name = customName, feedUrl = oldUrl, sortOrder = 0)
                )
                preferencesManager.update { 
                    it.copy(newsActiveSourceId = customSourceId, newsSourcesSeeded = true) 
                }
            } else {
                // It was the default URL, so just pick the first seeded one.
                preferencesManager.update { 
                    it.copy(newsActiveSourceId = allSeeded.firstOrNull()?.id, newsSourcesSeeded = true) 
                }
            }
        }
    }
}
