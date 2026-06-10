package com.orator.core.playback

import com.orator.core.model.MediaType

/**
 * Session events for features that record listening (play history). Contributed via Hilt
 * @IntoSet, mirroring PlaybackPositionListener: core:playback emits, features persist.
 */
interface PlaybackEventListener {
    /** A queue item started playing (initial play or queue transition). */
    suspend fun onItemStarted(mediaId: String, title: String, mediaType: MediaType?)

    /** The previously started item stopped: ran to its end ([completed]) or was switched away. */
    suspend fun onItemEnded(mediaId: String, positionMs: Long, completed: Boolean)
}
