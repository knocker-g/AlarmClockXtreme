package com.sysadmindoc.alarmclock.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * v1.11.2 (ALA-5): user-managed RSS news sources.
 */
@Entity(tableName = "news_sources")
data class NewsSource(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val name: String,
    val feedUrl: String,
    val sortOrder: Int
)
