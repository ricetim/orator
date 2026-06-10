package com.orator.core.playback

import com.orator.core.model.MediaType

/**
 * Resolves the playback speed to apply, using the most specific value available:
 * per-item override > per-type default > global default > hardcoded fallback.
 *
 * Pure function — no Android, no I/O — so it is unit-tested directly on the JVM.
 */
object SpeedResolver {

    const val DEFAULT_SPEED = 1.0f

    fun resolve(
        preferences: SpeedPreferences,
        mediaType: MediaType,
        itemOverride: Float?,
    ): Float {
        return itemOverride
            ?: preferences.perType[mediaType]
            ?: preferences.global
    }
}
