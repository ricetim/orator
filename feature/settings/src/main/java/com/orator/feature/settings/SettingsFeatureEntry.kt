package com.orator.feature.settings

import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import com.orator.core.navigation.CommonRoutes
import com.orator.core.navigation.FeatureEntry
import javax.inject.Inject

/** Plugs the settings screen into the app navigation graph. */
class SettingsFeatureEntry @Inject constructor() : FeatureEntry {

    override val route: String = CommonRoutes.Settings

    override fun register(navGraphBuilder: NavGraphBuilder, navController: NavController) {
        navGraphBuilder.composable(route) {
            SettingsScreen()
        }
    }
}
