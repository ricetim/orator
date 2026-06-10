package com.orator.core.playback

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.orator.core.model.MediaType
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Background-capable playback service: MediaSessionService gives lock-screen/notification
 * controls, Bluetooth buttons, and playback that survives the UI being swiped away.
 *
 * Phase 3: also the enforcement point for playback policy that must work no matter which
 * surface issued the command — silence trim, volume boost, smart rewind on resume, the sleep
 * timer, and start/end events for play history.
 */
@OptIn(UnstableApi::class)
@AndroidEntryPoint
class PlaybackService : MediaSessionService() {

    @Inject lateinit var positionListeners: Set<@JvmSuppressWildcards PlaybackPositionListener>
    @Inject lateinit var eventListeners: Set<@JvmSuppressWildcards PlaybackEventListener>
    @Inject lateinit var playerPreferences: PlayerPreferences
    @Inject lateinit var silenceTrim: SilenceTrim
    @Inject lateinit var loudnessBooster: LoudnessBooster
    @Inject lateinit var sleepTimer: SleepTimer
    @Inject lateinit var activeQueueInfo: ActiveQueueInfo
    @Inject lateinit var rewindController: SmartRewindController

    private var mediaSession: MediaSession? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var reportJob: Job? = null
    private var sleepJob: Job? = null

    private val latestPrefs = MutableStateFlow(PlayerPrefs())

    /** The item whose start we last reported, so ends pair with starts. */
    private var startedMediaId: String? = null

    override fun onCreate() {
        super.onCreate()
        val player = ExoPlayer.Builder(this, silenceTrim.renderersFactory(this)).build()

        player.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (isPlaying) {
                    applySmartRewind(player)
                    reportStartIfNew(player)
                    startReporting(player)
                } else {
                    rewindController.onPaused(player.currentMediaItem?.mediaId, System.currentTimeMillis())
                    stopReporting()
                    reportNow(player) // final position on pause/stop
                }
            }

            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED) {
                    // New queue: any pause pending from before the load must not fire on top
                    // of a cold-resume rewind or an exact chapter/bookmark position.
                    rewindController.reset()
                }
                reportEnd(
                    positionMs = 0, // previous item's final position was already pinged
                    completed = reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO,
                )
                if (player.isPlaying) reportStartIfNew(player)
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED) {
                    reportEnd(positionMs = player.currentPosition, completed = true)
                }
            }
        })

        player.addAnalyticsListener(object : AnalyticsListener {
            override fun onAudioSessionIdChanged(
                eventTime: AnalyticsListener.EventTime,
                audioSessionId: Int,
            ) = loudnessBooster.attach(audioSessionId)
        })
        loudnessBooster.attach(player.audioSessionId)

        scope.launch {
            playerPreferences.flow.collect { prefs ->
                latestPrefs.value = prefs
                silenceTrim.setEnabled(prefs.silenceTrim)
                loudnessBooster.setGain(prefs.boostMb)
            }
        }
        scope.launch {
            sleepTimer.state.collect { st -> onSleepTimerState(player, st) }
        }

        mediaSession = MediaSession.Builder(this, player).build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = mediaSession

    // --- smart rewind -------------------------------------------------------------------

    private fun applySmartRewind(player: Player) {
        val mediaId = player.currentMediaItem?.mediaId
        val type = player.currentMediaItem?.let { MediaItemFactory.mediaTypeOf(it.mediaMetadata) }
        val enabled = type != null && latestPrefs.value.smartRewind[type] == true
        val rewind = rewindController.onResumed(mediaId, System.currentTimeMillis(), enabled)
        if (rewind > 0) {
            // Clamp ≥ 0 only: with clipping, position 0 already IS the clip start.
            player.seekTo((player.currentPosition - rewind).coerceAtLeast(0))
        }
    }

    // --- sleep timer --------------------------------------------------------------------

    private fun onSleepTimerState(player: Player, state: SleepTimerState) {
        sleepJob?.cancel()
        sleepJob = when (state) {
            SleepTimerState.Off -> null

            is SleepTimerState.Duration -> scope.launch {
                delay((state.endsAtMs - System.currentTimeMillis()).coerceAtLeast(0))
                player.pause()
                sleepTimer.cancel()
            }

            SleepTimerState.EndOfBoundary -> scope.launch {
                val target = SleepTimer.nextBoundary(
                    activeQueueInfo.chapterBoundariesMs.value,
                    player.currentPosition,
                )
                if (target != null) {
                    while (isActive && player.currentPosition < target) delay(500)
                } else {
                    val startItem = player.currentMediaItemIndex
                    while (isActive && player.currentMediaItemIndex == startItem &&
                        player.playbackState != Player.STATE_ENDED
                    ) delay(500)
                }
                if (isActive) {
                    player.pause()
                    sleepTimer.cancel()
                }
            }
        }
    }

    // --- history events -----------------------------------------------------------------

    private fun reportStartIfNew(player: Player) {
        val item = player.currentMediaItem ?: return
        if (item.mediaId == startedMediaId) return
        startedMediaId = item.mediaId
        val title = item.mediaMetadata.title?.toString().orEmpty()
        val type: MediaType? = MediaItemFactory.mediaTypeOf(item.mediaMetadata)
        scope.launch {
            eventListeners.forEach { it.onItemStarted(item.mediaId, title, type) }
        }
    }

    private fun reportEnd(positionMs: Long, completed: Boolean) {
        val mediaId = startedMediaId ?: return
        startedMediaId = null
        scope.launch {
            eventListeners.forEach { it.onItemEnded(mediaId, positionMs, completed) }
        }
    }

    // --- position pings (unchanged from Phase 2) -----------------------------------------

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
        loudnessBooster.release()
        scope.cancel()
        mediaSession?.run {
            player.release()
            release()
        }
        mediaSession = null
        super.onDestroy()
    }
}
