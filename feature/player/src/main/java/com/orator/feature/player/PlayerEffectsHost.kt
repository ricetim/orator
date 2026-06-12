package com.orator.feature.player

import androidx.compose.runtime.Composable
import com.orator.core.designsystem.components.EffectsSheet
import com.orator.core.designsystem.components.EffectsSheetState
import com.orator.core.playback.PlaybackUiState
import com.orator.core.playback.PlayerPrefs

/** Maps player state into the shared effects sheet (one editor everywhere — spec). */
@Composable
fun PlayerEffectsHost(
    content: NowPlayingContent,
    state: PlaybackUiState,
    prefs: PlayerPrefs,
    viewModel: PlayerViewModel,
    onDismiss: () -> Unit,
) {
    val title = when (content) {
        is NowPlayingContent.Book -> "Effects — ${content.book.title}"
        is NowPlayingContent.Episode -> "Effects — ${content.podcast?.title ?: content.episode.title}"
        NowPlayingContent.Empty -> "Effects"
    }
    EffectsSheet(
        state = EffectsSheetState(
            title = title,
            speed = state.speed, // live resolved speed
            overrideEnabled = viewModel.isOverrideActive(),
            trimOn = prefs.silenceTrim,
            boostMb = prefs.boostMb,
            clip = (content as? NowPlayingContent.Episode)?.podcast
                ?.let { it.clipIntroMs to it.clipOutroMs },
        ),
        onDismiss = onDismiss,
        onSpeed = viewModel::onSpeed,
        onOverrideToggle = viewModel::onOverrideToggle,
        onTrim = viewModel::onTrim,
        onBoost = viewModel::onBoost,
        onClip = viewModel::onClip,
    )
}
