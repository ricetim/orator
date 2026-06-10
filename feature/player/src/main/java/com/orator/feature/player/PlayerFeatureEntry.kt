package com.orator.feature.player

import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import com.orator.core.navigation.FeatureEntry
import javax.inject.Inject

/** Plugs the player screen into the app navigation graph. */
class PlayerFeatureEntry @Inject constructor() : FeatureEntry {

    override val route: String = PlayerRoute

    override fun register(navGraphBuilder: NavGraphBuilder, navController: NavController) {
        navGraphBuilder.composable(route) {
            PlayerScreen()
        }
    }
}
