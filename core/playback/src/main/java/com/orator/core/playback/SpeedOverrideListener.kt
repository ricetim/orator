package com.orator.core.playback

/**
 * Notified when the user sets/clears a per-item speed override from the player UI, so the
 * owning feature can persist it (feature:player must not write feature-owned tables directly).
 */
interface SpeedOverrideListener {
    suspend fun onSpeedOverrideChanged(mediaId: String, speed: Float?)
}
