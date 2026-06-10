package com.orator.app

import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import com.orator.core.navigation.FeatureEntry
import com.orator.feature.audiobooks.AudiobooksRoute

/**
 * Builds the navigation graph by asking every registered feature to add its destinations.
 * The set is supplied by Hilt; this function names no feature except the start route.
 */
@Composable
fun OratorNavHost(
    featureEntries: Set<@JvmSuppressWildcards FeatureEntry>,
    navController: NavHostController,
) {
    NavHost(navController = navController, startDestination = AudiobooksRoute) {
        featureEntries.forEach { entry ->
            entry.register(this, navController)
        }
    }
}
