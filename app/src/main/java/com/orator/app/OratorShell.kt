package com.orator.app

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Text
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.orator.core.designsystem.components.MiniPlayer
import com.orator.core.designsystem.icons.OnyxIcons
import com.orator.core.designsystem.shell.LocalShellControls
import com.orator.core.designsystem.shell.ShellControls
import com.orator.core.designsystem.theme.OnyxTokens
import com.orator.core.navigation.CommonRoutes
import com.orator.core.navigation.FeatureEntry
import kotlinx.coroutines.launch

private data class TabSpec(val route: String, val icon: ImageVector, val label: String?)

private val TABS = listOf(
    TabSpec(CommonRoutes.Podcasts, OnyxIcons.Mic, null),
    TabSpec(CommonRoutes.Audiobooks, OnyxIcons.Book, null),
    TabSpec(CommonRoutes.Queue, OnyxIcons.Queue, "Queue"),
)

/** Shell: drawer + content + (mini player above nav bar) overlay. Owns all chrome. */
@Composable
fun OratorShell(
    featureEntries: Set<@JvmSuppressWildcards FeatureEntry>,
    viewModel: ShellViewModel = hiltViewModel(),
) {
    val navController = rememberNavController()
    val backStack by navController.currentBackStackEntryAsState()
    val route = backStack?.destination?.route
    val isTab = TABS.any { it.route == route }
    val isPlayer = route == CommonRoutes.Player

    val mini by viewModel.mini.collectAsStateWithLifecycle()
    val counts by viewModel.counts.collectAsStateWithLifecycle()

    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val shellControls = remember {
        ShellControls(openDrawer = { scope.launch { drawerState.open() } })
    }

    fun goTab(tab: String) {
        navController.navigate(tab) {
            popUpTo(navController.graph.findStartDestination().id) { saveState = true }
            launchSingleTop = true
            restoreState = true
        }
    }

    fun closeDrawerThen(action: () -> Unit) {
        scope.launch { drawerState.close() }
        action()
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        gesturesEnabled = isTab, // drawer swipe only on top-level tabs
        drawerContent = {
            ModalDrawerSheet(drawerContainerColor = OnyxTokens.NavBackground) {
                OratorDrawer(
                    counts = counts,
                    onSearch = { closeDrawerThen { navController.navigate(CommonRoutes.PodcastSearch) } },
                    onAddFeed = { closeDrawerThen { navController.navigate(CommonRoutes.PodcastSearch) } },
                    onHistory = { closeDrawerThen { navController.navigate(CommonRoutes.History) } },
                    onSettings = { closeDrawerThen { navController.navigate(CommonRoutes.Settings) } },
                )
            }
        },
    ) {
        Box(Modifier.fillMaxSize().background(OnyxTokens.Background)) {
            CompositionLocalProvider(LocalShellControls provides shellControls) {
                OratorNavHost(featureEntries = featureEntries, navController = navController)
            }
            Column(Modifier.align(Alignment.BottomCenter)) {
                if (mini.visible && !isPlayer) {
                    MiniPlayer(
                        title = mini.title,
                        subLine = mini.subLine,
                        progress = mini.progress,
                        isPlaying = mini.isPlaying,
                        artworkModel = mini.artworkModel,
                        onClick = { navController.navigate(CommonRoutes.Player) },
                        onPlayPause = viewModel::onPlayPause,
                    )
                }
                if (isTab) {
                    OnyxNavBar(selected = route, onSelect = ::goTab)
                }
            }
        }
    }
}

@Composable
private fun OnyxNavBar(selected: String?, onSelect: (String) -> Unit) {
    Row(
        Modifier.fillMaxWidth().height(OnyxTokens.NavBarHeight).background(OnyxTokens.NavBackground),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TABS.forEach { tab ->
            val isSelected = tab.route == selected
            val tint = if (isSelected) OnyxTokens.Accent else OnyxTokens.TextFaint
            // Whole cell is the tap target — an inner IconButton clips icon+label past 48dp.
            Column(
                Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .clickable { onSelect(tab.route) },
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Icon(tab.icon, contentDescription = tab.route, tint = tint)
                if (tab.label != null) {
                    Text(tab.label, color = tint, fontSize = 10.5.sp)
                }
            }
        }
    }
}
