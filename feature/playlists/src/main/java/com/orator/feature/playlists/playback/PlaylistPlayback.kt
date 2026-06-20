package com.orator.feature.playlists.playback

import com.orator.core.playback.PlaybackConnection
import com.orator.core.playback.PlaybackUiState
import com.orator.core.playback.PlayRequest
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject
import javax.inject.Singleton

/** The slice of playback the playlist controller needs (kept narrow so it can be faked in tests). */
interface PlaylistPlayback {
    val state: StateFlow<PlaybackUiState>
    fun play(request: PlayRequest)
}

@Singleton
class ConnectionPlaylistPlayback @Inject constructor(
    private val connection: PlaybackConnection,
) : PlaylistPlayback {
    override val state: StateFlow<PlaybackUiState> get() = connection.state
    override fun play(request: PlayRequest) = connection.play(request)
}

@Module
@InstallIn(SingletonComponent::class)
interface PlaylistPlaybackBindingModule {
    @Binds
    fun bindPlaylistPlayback(impl: ConnectionPlaylistPlayback): PlaylistPlayback
}
