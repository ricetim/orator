package com.akouo.app

import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import com.akouo.core.navigation.FeatureEntry
import com.akouo.feature.player.PlayerRoute

/**
 * Builds the navigation graph by asking every registered feature to add its destinations.
 * The set is supplied by Hilt; this function names no feature except the start route.
 */
@Composable
fun AkouoNavHost(
    featureEntries: Set<@JvmSuppressWildcards FeatureEntry>,
    navController: NavHostController,
) {
    NavHost(navController = navController, startDestination = PlayerRoute) {
        featureEntries.forEach { entry ->
            entry.register(this, navController)
        }
    }
}
