package com.orator.core.playback

import com.orator.core.model.MediaType

/** User playback-speed settings: a global default plus optional per-media-type defaults. */
data class SpeedPreferences(
    val global: Float = SpeedResolver.DEFAULT_SPEED,
    val perType: Map<MediaType, Float> = emptyMap(),
)
