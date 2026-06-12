package com.orator.app

import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import com.orator.core.navigation.CommonRoutes
import com.orator.core.navigation.FeatureEntry

/**
 * Builds the navigation graph by asking every registered feature to add its destinations.
 * The set is supplied by Hilt; this function names no feature module.
 */
@Composable
fun OratorNavHost(
    featureEntries: Set<@JvmSuppressWildcards FeatureEntry>,
    navController: NavHostController,
) {
    NavHost(navController = navController, startDestination = CommonRoutes.Podcasts) {
        featureEntries.forEach { entry ->
            entry.register(this, navController)
        }
    }
}
