package com.orator.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import com.orator.core.designsystem.theme.OnyxTokens
import com.orator.core.designsystem.theme.OratorTheme
import com.orator.core.navigation.FeatureEntry
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    /** All features registered in the app, collected by Hilt from @IntoSet bindings. */
    @Inject
    lateinit var featureEntries: Set<@JvmSuppressWildcards FeatureEntry>

    private val requestNotificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* result ignored */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        maybeRequestNotificationPermission()
        setContent {
            OratorTheme {
                // Edge-to-edge is enabled, so the host must keep content out of the status
                // bar / camera cutout; doing it here covers every feature screen at once.
                // The background paints the inset areas so they read as theme, not void.
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(OnyxTokens.Background)
                        .safeDrawingPadding(),
                ) {
                    OratorShell(featureEntries = featureEntries)
                }
            }
        }
    }

    private fun maybeRequestNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val alreadyGranted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
        if (!alreadyGranted) {
            requestNotificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}
