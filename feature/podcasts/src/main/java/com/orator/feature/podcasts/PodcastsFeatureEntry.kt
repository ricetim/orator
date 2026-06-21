package com.orator.feature.podcasts

import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import com.orator.core.model.MediaType
import com.orator.core.navigation.CommonRoutes
import com.orator.core.navigation.FeatureEntry
import com.orator.feature.podcasts.data.RefreshScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import javax.inject.Inject

class PodcastsFeatureEntry @Inject constructor(
    scheduler: RefreshScheduler,
) : FeatureEntry {

    init {
        // Eagerly constructed at app start (app injects Set<FeatureEntry>), so background refresh
        // is scheduled per the saved interval and an app-open refresh kicks off immediately.
        scheduler.start(CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate))
    }

    override val route: String = PodcastsRoute

    override fun register(navGraphBuilder: NavGraphBuilder, navController: NavController) {
        navGraphBuilder.composable(PodcastsRoute) {
            PodcastListScreen(
                onPodcastClick = { id -> navController.navigate(podcastDetailRoute(id)) },
            )
        }
        navGraphBuilder.composable(PodcastSearchRoute) {
            SearchScreen(onBack = { navController.popBackStack() })
        }
        navGraphBuilder.composable(PodcastDetailRoutePattern) {
            PodcastDetailScreen(
                onOpenPlayer = { navController.navigate(CommonRoutes.Player) },
                onBack = { navController.popBackStack() },
                onUnsubscribed = { navController.popBackStack() },
                onAddToPlaylist = { episodeId ->
                    navController.navigate(
                        CommonRoutes.addToPlaylist(MediaType.PODCAST.name, episodeId),
                    )
                },
            )
        }
    }
}
