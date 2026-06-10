package com.orator.core.playback

import android.content.ComponentName
import android.content.Context
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.datasource.RawResourceDataSource
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
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
) {
    private val _state = MutableStateFlow(PlaybackUiState())
    val state: StateFlow<PlaybackUiState> = _state.asStateFlow()

    private var controller: MediaController? = null
    private var controllerFuture: ListenableFuture<MediaController>? = null

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var positionTicker: Job? = null

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
            title = c.mediaMetadata.title?.toString().orEmpty(),
            mediaId = c.currentMediaItem?.mediaId,
            currentIndex = c.currentMediaItemIndex,
            positionMs = c.currentPosition.coerceAtLeast(0),
            durationMs = c.duration.takeIf { it != C.TIME_UNSET } ?: 0,
        )
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
        val items = request.items.map { item ->
            MediaItem.Builder()
                .setMediaId(item.mediaId)
                .setUri(item.uri)
                .setMediaMetadata(
                    MediaMetadata.Builder().setTitle(item.title).setArtist(item.artist).build(),
                )
                .build()
        }
        c.setMediaItems(items, request.startIndex, request.startPositionMs)
        c.prepare()
        c.setPlaybackSpeed(
            SpeedResolver.resolve(SpeedPreferences(), request.mediaType, itemOverride = null),
        )
        c.play()
    }

    /** Jumps to a queue item + offset (chapter taps, bookmark taps). */
    fun seekTo(index: Int, positionMs: Long) {
        controller?.seekTo(index, positionMs)
    }

    /**
     * Phase 1 smoke-test entry point: loads the bundled sample clip and starts playback.
     * Superseded in Phase 2 when media comes from the library/repository.
     */
    fun playBundledSample() {
        val c = controller ?: return
        val uri = RawResourceDataSource.buildRawResourceUri(R.raw.sample)
        val item = MediaItem.Builder()
            .setUri(uri)
            .setMediaMetadata(MediaMetadata.Builder().setTitle("Sample clip").build())
            .build()
        c.setMediaItem(item)
        c.prepare()
        c.setPlaybackSpeed(
            SpeedResolver.resolve(SpeedPreferences(), MediaType.PODCAST, itemOverride = null),
        )
        c.play()
    }
}
