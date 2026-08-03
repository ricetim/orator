package com.orator.feature.audiobooks

import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import com.orator.core.model.MediaType
import com.orator.core.navigation.CommonRoutes
import com.orator.core.navigation.FeatureEntry
import javax.inject.Inject

class AudiobooksFeatureEntry @Inject constructor() : FeatureEntry {

    override val route: String = AudiobooksRoute

    override fun register(navGraphBuilder: NavGraphBuilder, navController: NavController) {
        navGraphBuilder.composable(AudiobooksRoute) {
            AudiobookListScreen(
                onOpenBook = { bookId -> navController.navigate(audiobookDetailRoute(bookId)) },
                onAddToPlaylist = { bookId ->
                    navController.navigate(
                        CommonRoutes.addToPlaylist(MediaType.AUDIOBOOK.name, bookId),
                    )
                },
                onOpenSearch = {}, // wired to the real search route in Chunk 5
            )
        }
        navGraphBuilder.composable(AudiobookDetailRoutePattern) {
            AudiobookDetailScreen(
                onOpenPlayer = { navController.navigate(CommonRoutes.Player) },
                onBack = { navController.popBackStack() },
            )
        }
    }
}
