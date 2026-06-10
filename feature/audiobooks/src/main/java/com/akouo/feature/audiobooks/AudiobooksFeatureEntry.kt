package com.akouo.feature.audiobooks

import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import com.akouo.core.navigation.FeatureEntry
import javax.inject.Inject

class AudiobooksFeatureEntry @Inject constructor() : FeatureEntry {

    override val route: String = AudiobooksRoute

    override fun register(navGraphBuilder: NavGraphBuilder, navController: NavController) {
        navGraphBuilder.composable(AudiobooksRoute) {
            AudiobookListScreen(onBookClick = { bookId ->
                navController.navigate(bookDetailRoute(bookId))
            })
        }
        navGraphBuilder.composable(BookDetailRoutePattern) {
            BookDetailScreen()
        }
    }
}
