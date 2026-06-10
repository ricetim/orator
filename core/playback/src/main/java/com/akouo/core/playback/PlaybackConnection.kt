package com.akouo.core.playback

import android.content.ComponentName
import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.datasource.RawResourceDataSource
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.akouo.core.model.MediaType
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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

    private val listener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) = updateState()
        override fun onMediaMetadataChanged(mediaMetadata: MediaMetadata) = updateState()
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
