package com.orator.feature.playlists.playback

import com.orator.core.model.MediaRef
import com.orator.core.playback.PlayRequestFactory
import com.orator.core.playback.PlaybackUiState
import com.orator.feature.playlists.data.ActivePlaylist
import com.orator.feature.playlists.data.MediaRefMatch
import com.orator.feature.playlists.data.PlaylistRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Drives a draining playlist on top of the single-entity playback core. Loads the TOP item, and on
 * each end-of-queue (isEnded rising edge) pops the top row and plays the next. Stands down if the
 * user plays something outside the active playlist. All advance logic is in [onState] for testing;
 * [start] wires it to live playback in production.
 */
@Singleton
class PlaylistPlaybackController @Inject constructor(
    private val playback: PlaylistPlayback,
    private val repo: PlaylistRepository,
    private val factories: Set<@JvmSuppressWildcards PlayRequestFactory>,
    private val active: ActivePlaylist,
) {
    private val factoryByType = factories.associateBy { it.mediaType }
    private var wasEnded = false

    /** Production wiring: call once at app start (from PlaylistsFeatureEntry). */
    fun start(scope: CoroutineScope) {
        scope.launch { playback.state.collect { onState(it) } }
    }

    suspend fun playFromTop(playlistId: Long) {
        active.set(playlistId)
        wasEnded = false
        val ref = repo.topRef(playlistId) ?: run { active.clear(); return }
        playRef(ref)
    }

    suspend fun playItem(playlistId: Long, itemId: Long) {
        repo.moveToTop(playlistId, itemId)
        playFromTop(playlistId)
    }

    /** Reacts to one playback state. Idempotent per state; only the rising edge of isEnded advances. */
    suspend fun onState(state: PlaybackUiState) {
        val activeId = active.activePlaylistId() ?: run { wasEnded = state.isEnded; return }

        // Stand down if a non-blank, non-ended mediaId points outside this playlist's top.
        if (!state.isEnded && !state.mediaId.isNullOrBlank()) {
            val top = repo.topRef(activeId)
            if (top != null && !MediaRefMatch.matches(top, state.mediaId)) {
                active.clear()
                wasEnded = false
                return
            }
        }

        if (state.isEnded && !wasEnded) {
            repo.removeTop(activeId)
            val next = repo.topRef(activeId)
            if (next != null) playRef(next) else active.clear()
        }
        wasEnded = state.isEnded
    }

    private suspend fun playRef(ref: MediaRef) {
        val request = factoryByType[ref.type]?.create(ref) ?: return
        playback.play(request)
        wasEnded = false // new queue clears STATE_ENDED; keep our flag in sync so the edge re-arms
    }
}
