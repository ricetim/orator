package com.akouo.core.playback

import com.akouo.core.model.MediaType

/** User playback-speed settings: a global default plus optional per-media-type defaults. */
data class SpeedPreferences(
    val global: Float = SpeedResolver.DEFAULT_SPEED,
    val perType: Map<MediaType, Float> = emptyMap(),
)
