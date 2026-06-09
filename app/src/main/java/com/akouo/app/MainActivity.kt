package com.akouo.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.navigation.compose.rememberNavController
import com.akouo.core.designsystem.theme.AkouoTheme
import com.akouo.core.navigation.FeatureEntry
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    /** All features registered in the app, collected by Hilt from @IntoSet bindings. */
    @Inject
    lateinit var featureEntries: Set<@JvmSuppressWildcards FeatureEntry>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            AkouoTheme {
                val navController = rememberNavController()
                AkouoNavHost(featureEntries = featureEntries, navController = navController)
            }
        }
    }
}
