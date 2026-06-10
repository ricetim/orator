package com.orator.core.navigation

import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder

/**
 * The contract every feature module implements to plug itself into the app's navigation graph.
 *
 * The app collects all FeatureEntry instances (via a Hilt multibinding) and asks each one to
 * register its destinations. This is what makes features pluggable: adding a feature means
 * shipping a new module that provides a FeatureEntry; removing it means deleting the module.
 * The app never references any specific feature.
 */
interface FeatureEntry {
    /** The unique navigation route this feature owns. */
    val route: String

    /**
     * Adds this feature's composable destination(s) to the navigation graph.
     * Called from inside the app's NavHost builder.
     */
    fun register(navGraphBuilder: NavGraphBuilder, navController: NavController)
}
