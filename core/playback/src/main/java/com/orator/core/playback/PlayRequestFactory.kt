package com.orator.core.playback

import com.orator.core.model.MediaRef
import com.orator.core.model.MediaType

/**
 * Builds a single-entity [PlayRequest] for one [MediaRef], reading the target entity and its
 * saved resume position. Each feature contributes one per media type via Hilt @IntoSet
 * (mirrors PlaybackEventListener). Returns null when the ref is no longer resolvable.
 */
interface PlayRequestFactory {
    val mediaType: MediaType
    suspend fun create(ref: MediaRef): PlayRequest?
}
