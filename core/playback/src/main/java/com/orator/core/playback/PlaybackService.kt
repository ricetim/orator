package com.orator.core.playback

import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Background-capable playback service. Hosting playback in a MediaSessionService is what gives us
 * lock-screen / notification controls, Bluetooth & headset buttons, and playback that survives the
 * UI being swiped away. The UI connects to it through a MediaController (see PlaybackConnection).
 *
 * While playing it reports the current position to all registered PlaybackPositionListeners
 * (features contribute these via Hilt multibinding) so resume positions survive process death.
 */
@AndroidEntryPoint
class PlaybackService : MediaSessionService() {

    @Inject
    lateinit var positionListeners: Set<@JvmSuppressWildcards PlaybackPositionListener>

    private var mediaSession: MediaSession? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var reportJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        val player = ExoPlayer.Builder(this).build()
        player.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (isPlaying) {
                    startReporting(player)
                } else {
                    stopReporting()
                    reportNow(player) // final position on pause/stop
                }
            }
        })
        mediaSession = MediaSession.Builder(this, player).build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = mediaSession

    private fun startReporting(player: Player) {
        reportJob?.cancel()
        reportJob = scope.launch {
            while (isActive) {
                reportNow(player)
                delay(3_000)
            }
        }
    }

    private fun stopReporting() {
        reportJob?.cancel()
        reportJob = null
    }

    private fun reportNow(player: Player) {
        val mediaId = player.currentMediaItem?.mediaId ?: return
        val positionMs = player.currentPosition.coerceAtLeast(0)
        val durationMs = player.duration.takeIf { it != C.TIME_UNSET } ?: 0
        scope.launch {
            positionListeners.forEach { it.onPositionChanged(mediaId, positionMs, durationMs) }
        }
    }

    override fun onDestroy() {
        scope.cancel()
        mediaSession?.run {
            player.release()
            release()
        }
        mediaSession = null
        super.onDestroy()
    }
}
