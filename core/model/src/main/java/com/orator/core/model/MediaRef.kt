package com.orator.core.model

/**
 * A type-tagged pointer to a playable entity. [id] is the entity's own String PK
 * (episode.id or book.id — both are already Strings).
 */
data class MediaRef(val type: MediaType, val id: String)

/** Display fields for one playlist row. Plain data — no Android, no playback. */
data class PlaylistItemContent(
    val title: String,
    val subtitle: String,
    val artworkUri: String?,
    val durationMs: Long,
)

/**
 * Resolves a [MediaRef] to its display fields. Each feature contributes one per media type via
 * Hilt @IntoSet (mirrors PlaybackEventListener). Returns null when the underlying entity is gone
 * (e.g. podcast unsubscribed, book removed) — the playlist then prunes that row.
 */
interface PlaylistItemResolver {
    val mediaType: MediaType
    suspend fun resolve(ref: MediaRef): PlaylistItemContent?
}
