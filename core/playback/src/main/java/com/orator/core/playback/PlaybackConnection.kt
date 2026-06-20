package com.orator.core.playback

import android.content.ComponentName
import android.content.Context
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.orator.core.model.MediaType
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The UI-side handle on playback. Connects a Media3 MediaController to PlaybackService and exposes
 * the player's state as a StateFlow that ViewModels can observe (unidirectional data flow).
 *
 * Phase 1 simplification: a process-scoped singleton that connects once and is never explicitly
 * released. Lifecycle-aware connect/release is added in Phase 3 when multiple screens connect.
 */
@Singleton
class PlaybackConnection @Inject constructor(
    @ApplicationContext private val context: Context,
    playerPreferences: PlayerPreferences,
    private val activeQueueInfo: ActiveQueueInfo,
    private val speedOverrideListeners: Set<@JvmSuppressWildcards SpeedOverrideListener>,
    private val clipOverrideListeners: Set<@JvmSuppressWildcards ClipOverrideListener>,
) {
    private val _state = MutableStateFlow(PlaybackUiState())
    val state: StateFlow<PlaybackUiState> = _state.asStateFlow()

    private var controller: MediaController? = null
    private var controllerFuture: ListenableFuture<MediaController>? = null

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var positionTicker: Job? = null

    /** Prefs snapshot, always current; Eagerly because play() reads .value synchronously. */
    private val prefs: StateFlow<PlayerPrefs> =
        playerPreferences.flow.stateIn(scope, SharingStarted.Eagerly, PlayerPrefs())

    private var currentMediaType: MediaType? = null
    private var currentOverride: Float? = null

    private val listener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            updateState()
            if (isPlaying) startPositionTicker() else stopPositionTicker()
        }

        override fun onMediaMetadataChanged(mediaMetadata: MediaMetadata) = updateState()
        override fun onPlaybackStateChanged(playbackState: Int) = updateState()
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) = updateState()
    }

    /** currentPosition only changes on events; while playing we sample it for the UI once a second. */
    private fun startPositionTicker() {
        positionTicker?.cancel()
        positionTicker = scope.launch {
            while (isActive) {
                updateState()
                delay(1_000)
            }
        }
    }

    private fun stopPositionTicker() {
        positionTicker?.cancel()
        positionTicker = null
    }

    init {
        connect()
        scope.launch {
            prefs.collect { applySpeed() } // live re-apply when settings change
        }
    }

    private fun connect() {
        val token = SessionToken(context, ComponentName(context, PlaybackService::class.java))
        val future = MediaController.Builder(context, token).buildAsync()
        controllerFuture = future
        future.addListener(
            {
                val newController = future.get()
                newController.addListener(listener)
                controller = newController
                updateState()
            },
            MoreExecutors.directExecutor(),
        )
    }

    private fun updateState() {
        val c = controller ?: return
        _state.value = PlaybackUiState(
            isPlaying = c.isPlaying,
            isEnded = c.playbackState == Player.STATE_ENDED,
            title = c.mediaMetadata.title?.toString().orEmpty(),
            artist = c.mediaMetadata.artist?.toString().orEmpty(),
            mediaId = c.currentMediaItem?.mediaId,
            mediaType = MediaItemFactory.mediaTypeOf(c.mediaMetadata),
            currentIndex = c.currentMediaItemIndex,
            positionMs = c.currentPosition.coerceAtLeast(0),
            durationMs = c.duration.takeIf { it != C.TIME_UNSET } ?: 0,
            speed = c.playbackParameters.speed,
        )
    }

    /** One row per loaded Media3 queue item. Read-only; the full mixed queue is Phase 5. */
    data class QueueItemSnapshot(val index: Int, val mediaId: String, val title: String)

    /** Snapshot of the loaded queue. Call again when state.currentIndex/mediaId changes. */
    fun queueSnapshot(): List<QueueItemSnapshot> {
        val c = controller ?: return emptyList()
        return (0 until c.mediaItemCount).map { i ->
            val item = c.getMediaItemAt(i)
            QueueItemSnapshot(i, item.mediaId, item.mediaMetadata.title?.toString().orEmpty())
        }
    }

    /** Toggles play/pause for whatever is currently loaded. */
    fun playPause() {
        val c = controller ?: return
        if (c.isPlaying) {
            c.pause()
        } else {
            if (c.playbackState == Player.STATE_IDLE) c.prepare()
            c.play()
        }
    }

    /** Loads a feature-built queue and starts playing from the requested spot. */
    fun play(request: PlayRequest) {
        val c = controller ?: return
        currentMediaType = request.mediaType
        currentOverride = request.speedOverride
        activeQueueInfo.onNewQueue(request.chapterBoundariesMs, request.fileDurationsMs)
        val items = request.items.map { MediaItemFactory.from(it, request.mediaType) }
        c.setMediaItems(items, request.startIndex, request.startPositionMs)
        c.prepare()
        applySpeed()
        c.play()
    }

    private fun applySpeed() {
        val c = controller ?: return
        val type = currentMediaType ?: return
        c.setPlaybackSpeed(
            SpeedResolver.resolve(prefs.value.toSpeedPreferences(), type, currentOverride),
        )
    }

    /** Sets/clears the per-item speed override; features persist it via SpeedOverrideListener. */
    fun setSpeedOverride(speed: Float?) {
        currentOverride = speed
        applySpeed()
        val mediaId = controller?.currentMediaItem?.mediaId ?: return
        scope.launch {
            speedOverrideListeners.forEach { it.onSpeedOverrideChanged(mediaId, speed) }
        }
    }

    /** Per-show intro/outro edit from the player's effects sheet; features persist + rebuild. */
    fun setClipOverride(introMs: Long, outroMs: Long) {
        val mediaId = controller?.currentMediaItem?.mediaId ?: return
        scope.launch {
            clipOverrideListeners.forEach { it.onClipChanged(mediaId, introMs, outroMs) }
        }
    }

    /** Jumps to a queue item + offset (chapter taps, bookmark taps). */
    fun seekTo(index: Int, positionMs: Long) {
        controller?.seekTo(index, positionMs)
    }

    /** Skip ±N within the current item, clamped to [0, duration]. */
    fun seekBy(deltaMs: Long) {
        val c = controller ?: return
        val duration = c.duration.takeIf { it != C.TIME_UNSET } ?: Long.MAX_VALUE
        c.seekTo((c.currentPosition + deltaMs).coerceIn(0, duration))
    }

    /** Absolute seek within the current item (slider drags). */
    fun seekWithinCurrent(positionMs: Long) {
        val c = controller ?: return
        c.seekTo(positionMs.coerceAtLeast(0))
    }
}
