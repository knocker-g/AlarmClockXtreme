package com.sysadmindoc.alarmclock.data.local

import androidx.room.*
import com.sysadmindoc.alarmclock.data.local.entity.NewsSource
import kotlinx.coroutines.flow.Flow

@Dao
interface NewsSourceDao {
    @Query("SELECT * FROM news_sources ORDER BY sortOrder ASC")
    fun observeAll(): Flow<List<NewsSource>>

    @Query("SELECT * FROM news_sources ORDER BY sortOrder ASC")
    suspend fun getAll(): List<NewsSource>

    @Query("SELECT * FROM news_sources WHERE id = :id")
    suspend fun getById(id: Long): NewsSource?

    @Query("SELECT MAX(sortOrder) FROM news_sources")
    suspend fun getMaxSortOrder(): Int?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(source: NewsSource): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(sources: List<NewsSource>)

    @Update
    suspend fun update(source: NewsSource)

    @Delete
    suspend fun delete(source: NewsSource)

    @Query("SELECT COUNT(*) FROM news_sources")
    suspend fun count(): Int
}
