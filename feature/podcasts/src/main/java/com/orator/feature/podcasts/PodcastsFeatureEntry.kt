package com.orator.feature.podcasts

import androidx.compose.material3.Text
import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import com.orator.core.navigation.FeatureEntry
import javax.inject.Inject

class PodcastsFeatureEntry @Inject constructor() : FeatureEntry {

    override val route: String = PodcastsRoute

    override fun register(navGraphBuilder: NavGraphBuilder, navController: NavController) {
        navGraphBuilder.composable(PodcastsRoute) {
            Text("Podcasts")
        }
    }
}
