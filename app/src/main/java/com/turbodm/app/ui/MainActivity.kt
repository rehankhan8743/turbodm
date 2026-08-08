package com.turbodm.app.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.turbodm.app.ui.downloads.AddDownloadScreen
import com.turbodm.app.ui.downloads.DownloadsListScreen
import com.turbodm.app.ui.settings.SettingsScreen
import com.turbodm.app.ui.theme.TurboDMTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Handle share intent: route to AddDownload with prefilled URL.
        val sharedUrl = intent?.takeIf { it.action == android.content.Intent.ACTION_SEND }
            ?.getStringExtra(android.content.Intent.EXTRA_TEXT)
            ?.let { extractUrl(it) }

        setContent {
            TurboDMTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    AppNav(startWithUrl = sharedUrl)
                }
            }
        }
    }

    private fun extractUrl(text: String): String? =
        Regex("""https?://\S+""").find(text)?.value
}

@Composable
private fun AppNav(startWithUrl: String?) {
    val nav = rememberNavController()
    NavHost(navController = nav, startDestination = Route.Downloads.name) {
        composable(Route.Downloads.name) {
            DownloadsListScreen(
                onAdd = { nav.navigate(Route.Add.name) },
                onSettings = { nav.navigate(Route.Settings.name) }
            )
        }
        composable(Route.Add.name) {
            AddDownloadScreen(
                initialUrl = startWithUrl,
                onAdded = { nav.popBackStack() },
                onCancel = { nav.popBackStack() }
            )
        }
        composable(Route.Settings.name) {
            SettingsScreen(onBack = { nav.popBackStack() })
        }
    }
}

private enum class Route { Downloads, Add, Settings }
