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
                onOpenSearch = { navController.navigate(AudiobookSearchRoute) },
            )
        }
        navGraphBuilder.composable(AudiobookDetailRoutePattern) {
            AudiobookDetailScreen(
                onOpenPlayer = { navController.navigate(CommonRoutes.Player) },
                onBack = { navController.popBackStack() },
            )
        }
        navGraphBuilder.composable(AudiobookSearchRoute) {
            AudiobookSearchScreen(
                onOpenBook = { navController.navigate(audiobookDetailRoute(it)) },
                onOpenSeries = { navController.navigate(audiobookFilterRoute("series", it)) },
                onOpenAuthor = { navController.navigate(audiobookFilterRoute("author", it)) },
                onBack = { navController.popBackStack() },
            )
        }
        navGraphBuilder.composable(AudiobookFilterRoutePattern) {
            AudiobookFilterScreen(
                onOpenBook = { navController.navigate(audiobookDetailRoute(it)) },
                onAddToPlaylist = { bookId ->
                    navController.navigate(CommonRoutes.addToPlaylist(MediaType.AUDIOBOOK.name, bookId))
                },
                onBack = { navController.popBackStack() },
            )
        }
    }
}
