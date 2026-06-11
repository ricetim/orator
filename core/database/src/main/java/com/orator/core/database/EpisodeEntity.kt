package com.orator.core.database

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * One episode. [id] is hash(podcastId + guid) — GUIDs are only unique within a feed.
 * [durationMs] is ALWAYS the original unclipped timeline (0 = unknown); [positionMs] is
 * clip-relative (the Phase 3 invariant). [showNotesHtml] lives in the DB so the UI never
 * waits on network or SAF; the cache tree mirrors it for recent/downloaded episodes.
 */
@Entity(tableName = "episodes")
data class EpisodeEntity(
    @PrimaryKey val id: String,
    val podcastId: String,
    val title: String,
    val pubDateUtc: Long,
    val durationMs: Long = 0,
    val enclosureUrl: String,
    val showNotesHtml: String? = null,
    /** Content URI of the downloaded audio; null = stream from [enclosureUrl]. */
    val audioPath: String? = null,
    val positionMs: Long = 0,
    /** Wall-clock of the last position ping; drives cold-start smart rewind. */
    val lastPlayedAtMs: Long = 0,
    /** Podcasting-2.0 transcript: URL+type from the feed; path set once fetched. */
    val transcriptUrl: String? = null,
    val transcriptType: String? = null,
    val transcriptPath: String? = null,
)
