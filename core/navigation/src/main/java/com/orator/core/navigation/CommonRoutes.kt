package com.orator.core.navigation

/**
 * Routes that other features navigate to. Owning features register the destinations;
 * keeping the strings here avoids feature→feature dependencies.
 */
object CommonRoutes {
    const val Player = "player"
    const val Settings = "settings"
    const val History = "history"
    const val Podcasts = "podcasts"
    const val Audiobooks = "audiobooks"
    const val Queue = "queue"
    const val PodcastSearch = "podcast-search"

    const val Playlists = "playlists"

    const val PlaylistDetail = "playlist/{playlistId}"
    fun playlistDetail(playlistId: Long) = "playlist/$playlistId"

    /** [mediaType] is a MediaType.name; [mediaId] is the entity's String id. */
    const val AddToPlaylist = "add-to-playlist/{mediaType}/{mediaId}"
    fun addToPlaylist(mediaType: String, mediaId: String) = "add-to-playlist/$mediaType/$mediaId"
}
