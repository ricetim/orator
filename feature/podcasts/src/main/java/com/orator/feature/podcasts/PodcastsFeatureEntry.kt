package com.orator.feature.podcasts

import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import com.orator.core.navigation.CommonRoutes
import com.orator.core.navigation.FeatureEntry
import javax.inject.Inject

class PodcastsFeatureEntry @Inject constructor() : FeatureEntry {

    override val route: String = PodcastsRoute

    override fun register(navGraphBuilder: NavGraphBuilder, navController: NavController) {
        navGraphBuilder.composable(PodcastsRoute) {
            PodcastListScreen(
                onPodcastClick = { id -> navController.navigate(podcastDetailRoute(id)) },
            )
        }
        navGraphBuilder.composable(PodcastSearchRoute) {
            SearchScreen()
        }
        navGraphBuilder.composable(PodcastDetailRoutePattern) {
            PodcastDetailScreen(
                onOpenPlayer = { navController.navigate(CommonRoutes.Player) },
                onBack = { navController.popBackStack() },
                onUnsubscribed = { navController.popBackStack() },
            )
        }
        navGraphBuilder.composable(EpisodeDetailRoutePattern) {
            EpisodeDetailScreen()
        }
    }
}
