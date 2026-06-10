package com.orator.core.playback

/**
 * Contributed by feature modules (Hilt @IntoSet) to be told where playback is, every few
 * seconds while playing and once on pause/stop. Runs on the main dispatcher — implementations
 * must hop to IO for persistence.
 */
interface PlaybackPositionListener {
    suspend fun onPositionChanged(mediaId: String, positionMs: Long, durationMs: Long)
}
