package com.orator.feature.player

import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import com.orator.core.navigation.CommonRoutes
import com.orator.core.navigation.FeatureEntry
import javax.inject.Inject

/** Plugs the now-playing and history screens into the app navigation graph. */
class PlayerFeatureEntry @Inject constructor() : FeatureEntry {

    override val route: String = CommonRoutes.Player

    override fun register(navGraphBuilder: NavGraphBuilder, navController: NavController) {
        navGraphBuilder.composable(route) {
            PlayerScreen()
        }
        navGraphBuilder.composable(CommonRoutes.History) {
            HistoryScreen()
        }
        navGraphBuilder.composable(CommonRoutes.Queue) {
            QueueScreen()
        }
    }
}
