package com.orator.core.playback

import com.orator.core.model.MediaType

/** One playable file/stream in a queue. [mediaId] must be globally unique and parseable by its owning feature. */
data class PlayableItem(
    val mediaId: String,
    val uri: String,
    val title: String,
    val artist: String = "",
)

/** A complete "play this" command from a feature: the queue plus where to start in it. */
data class PlayRequest(
    val items: List<PlayableItem>,
    val startIndex: Int = 0,
    val startPositionMs: Long = 0,
    val mediaType: MediaType,
)
