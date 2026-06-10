package com.orator.feature.audiobooks

import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import com.orator.core.navigation.CommonRoutes
import com.orator.core.navigation.FeatureEntry
import javax.inject.Inject

class AudiobooksFeatureEntry @Inject constructor() : FeatureEntry {

    override val route: String = AudiobooksRoute

    override fun register(navGraphBuilder: NavGraphBuilder, navController: NavController) {
        navGraphBuilder.composable(AudiobooksRoute) {
            AudiobookListScreen(
                onBookClick = { bookId ->
                    navController.navigate(bookDetailRoute(bookId))
                },
                onOpenSettings = { navController.navigate(CommonRoutes.Settings) },
                onOpenHistory = { navController.navigate(CommonRoutes.History) },
                onOpenPlayer = { navController.navigate(CommonRoutes.Player) },
            )
        }
        navGraphBuilder.composable(BookDetailRoutePattern) {
            BookDetailScreen()
        }
    }
}
