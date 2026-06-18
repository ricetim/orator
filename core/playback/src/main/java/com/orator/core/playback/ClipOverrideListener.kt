package com.orator.core.playback

/**
 * Features persist intro/outro clip changes for the loaded item's show and rebuild the live
 * queue. Same fan-out pattern as [SpeedOverrideListener]: the player UI calls
 * [PlaybackConnection.setClipOverride]; the owning feature does the persistence.
 */
interface ClipOverrideListener {
    suspend fun onClipChanged(mediaId: String, introMs: Long, outroMs: Long)
}
