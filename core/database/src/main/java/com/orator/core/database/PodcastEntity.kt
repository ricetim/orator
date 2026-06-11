package com.orator.core.database

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * One subscribed feed. [clipIntroMs]/[clipOutroMs] are the per-show auto-skip windows fed into
 * PlayableItem clips. [etag]/[lastModified] are HTTP validators for cheap conditional refresh.
 */
@Entity(tableName = "podcasts")
data class PodcastEntity(
    @PrimaryKey val id: String,
    val feedUrl: String,
    val title: String,
    val author: String?,
    val description: String?,
    val artworkUrl: String?,
    val subscribedAtUtc: Long,
    val lastRefreshUtc: Long = 0,
    val etag: String? = null,
    val lastModified: String? = null,
    val clipIntroMs: Long = 0,
    val clipOutroMs: Long = 0,
    /** Per-show speed; null = fall back to per-type/global defaults. */
    val speedOverride: Float? = null,
)
