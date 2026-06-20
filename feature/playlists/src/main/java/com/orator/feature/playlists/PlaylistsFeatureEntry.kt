package com.orator.feature.playlists

import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavType
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.orator.core.navigation.CommonRoutes
import com.orator.core.navigation.FeatureEntry
import com.orator.feature.playlists.playback.PlaylistPlaybackController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import javax.inject.Inject

/**
 * Registers the playlists destinations. Taking [PlaylistPlaybackController] as a constructor
 * dependency makes Hilt instantiate it eagerly (the app injects Set<FeatureEntry> at startup),
 * so the controller starts observing playback immediately — playlist auto-advance survives
 * process death without the app ever referencing this module's types.
 */
class PlaylistsFeatureEntry @Inject constructor(
    controller: PlaylistPlaybackController,
) : FeatureEntry {

    init {
        controller.start(CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate))
    }

    override val route: String = CommonRoutes.Playlists

    override fun register(navGraphBuilder: NavGraphBuilder, navController: NavController) {
        navGraphBuilder.composable(CommonRoutes.Playlists) {
            PlaylistsScreen(
                onOpenPlaylist = { id -> navController.navigate(CommonRoutes.playlistDetail(id)) },
            )
        }
        navGraphBuilder.composable(
            CommonRoutes.PlaylistDetail,
            arguments = listOf(navArgument("playlistId") { type = NavType.LongType }),
        ) {
            PlaylistDetailScreen(
                onOpenPlayer = { navController.navigate(CommonRoutes.Player) },
                onBack = { navController.popBackStack() },
            )
        }
        navGraphBuilder.composable(
            CommonRoutes.AddToPlaylist,
            arguments = listOf(
                navArgument("mediaType") { type = NavType.StringType },
                navArgument("mediaId") { type = NavType.StringType },
            ),
        ) {
            AddToPlaylistSheet(onDone = { navController.popBackStack() })
        }
    }
}
