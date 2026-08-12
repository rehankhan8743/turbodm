package com.turbodm.app.ui

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.turbodm.app.ui.downloads.AddDownloadScreen
import com.turbodm.app.ui.downloads.DownloadsListScreen
import com.turbodm.app.ui.downloads.MagnetFilePickerScreen
import com.turbodm.app.ui.settings.SettingsScreen
import com.turbodm.app.ui.theme.TurboDMTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Volatile private var latestSharedUrl: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Resolve a URL from either ACTION_SEND (text share) or ACTION_VIEW (URI handoff).
        latestSharedUrl = extractSharedUrl(intent)

        setContent {
            TurboDMTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    AppNav(startWithUrl = latestSharedUrl)
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // singleTask: a second share re-uses the running activity — without
        // overriding onNewIntent, the new URL is silently dropped. Stash it
        // and re-render the Compose content so the user sees the Add screen.
        setIntent(intent)
        latestSharedUrl = extractSharedUrl(intent)
        if (latestSharedUrl != null) {
            setContent {
                TurboDMTheme {
                    Surface(modifier = Modifier.fillMaxSize()) {
                        AppNav(startWithUrl = latestSharedUrl)
                    }
                }
            }
        }
    }

    private fun extractSharedUrl(intent: Intent?): String? {
        if (intent == null) return null
        return when (intent.action) {
            Intent.ACTION_SEND -> {
                intent.getStringExtra(Intent.EXTRA_TEXT)?.let(::extractFirstUrl)
            }
            Intent.ACTION_VIEW -> {
                // ACTION_VIEW delivers a Uri in intent.data — return its string form.
                intent.data?.toString()?.takeIf { it.isNotBlank() }
            }
            else -> null
        }
    }

    /**
     * Pulls the first URL-ish token out of a freeform share string.
     * Matches the schemes TurboDM registers in the manifest, so magnets
     * and file URIs from share texts make it through.
     */
    private fun extractFirstUrl(text: String): String? =
        URL_REGEX.find(text)?.value

    companion object {
        // Covers the schemes declared in AndroidManifest's ACTION_VIEW filter.
        //   - `://` for http(s), ftp, content, file
        //   - `:` for magnet (which uses `magnet:?xt=...`)
        // We use an alternation so each scheme matches its own separator.
        private val URL_REGEX = Regex(
            """((https?|ftp|content|file)://\S+|magnet:\S+)""",
            RegexOption.IGNORE_CASE
        )
    }
}

@Composable
private fun AppNav(startWithUrl: String?) {
    val nav = rememberNavController()
    // If we arrived via share/deep-link, jump straight to the Add screen with
    // the URL prefilled — that's what the user expects from "share to TurboDM".
    // Starting on Downloads and hoping they tap "Add URL" is a hidden dead-end.
    val startDestination = if (startWithUrl != null) Route.Add.name else Route.Downloads.name
    NavHost(navController = nav, startDestination = startDestination) {
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
                onCancel = { nav.popBackStack() },
                onMagnetPicker = { torrentId ->
                    nav.navigate(Route.MagnetPicker.build(torrentId))
                }
            )
        }
        composable(Route.MagnetPicker.pattern, arguments = Route.MagnetPicker.args) {
            MagnetFilePickerScreen(
                onDone = { nav.popBackStack(Route.Downloads.name, inclusive = false) },
                onCancel = { nav.popBackStack() }
            )
        }
        composable(Route.Settings.name) {
            SettingsScreen(onBack = { nav.popBackStack() })
        }
    }
}

private enum class Route {
    Downloads,
    Add,
    Settings,
    MagnetPicker;

    // Route + args are split here: the simple ones use `.name` directly; the
    // picker uses a pattern with a `torrentId` argument so deep-linking from a
    // notification or share intent can land directly on the file list.
    val pattern: String get() = when (this) {
        MagnetPicker -> "magnet_picker?torrentId={torrentId}"
        else -> name
    }
    val args: List<androidx.navigation.NamedNavArgument> get() = when (this) {
        MagnetPicker -> listOf(
            navArgument("torrentId") {
                type = NavType.LongType
                defaultValue = -1L
            }
        )
        else -> emptyList()
    }

    fun build(vararg args: Any): String = when (this) {
        MagnetPicker -> "magnet_picker?torrentId=${args[0]}"
        else -> name
    }
}