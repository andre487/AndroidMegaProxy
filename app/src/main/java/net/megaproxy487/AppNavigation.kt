package net.megaproxy487

import android.app.Activity
import android.net.Uri
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import net.megaproxy487.vpn.ConnectionStatsReader
import net.megaproxy487.vpn.NativeConnectionStats

internal object AppRoute {
    const val MAIN = "main"
    const val SETTINGS = "settings"
    const val PROFILES = "profiles"
    const val PROFILE_EDITOR = "profile/{profileId}"
    const val ALWAYS_ON = "always-on"
    const val FINGERPRINTS = "fingerprints"
    const val SPLIT_TUNNEL = "split-tunnel"
    const val FAILOVER = "failover"
    const val VISIBILITY = "visibility"
    const val CONNECTION_TEST = "connection-test"
    const val DIAGNOSTIC_LOG = "diagnostic-log"
    const val SSH_HOST_KEY = "ssh-host-key"

    val registeredStaticRoutes = setOf(
        MAIN,
        SETTINGS,
        PROFILES,
        ALWAYS_ON,
        FINGERPRINTS,
        SPLIT_TUNNEL,
        FAILOVER,
        VISIBILITY,
        CONNECTION_TEST,
        DIAGNOSTIC_LOG,
        SSH_HOST_KEY,
    )

    fun profileEditor(profileId: String) = "profile/${Uri.encode(profileId)}"
}

internal data class SettingsDestination(
    val titleRes: Int,
    val descriptionRes: Int,
    val route: String,
)

internal val connectionSettingsDestinations = listOf(
    SettingsDestination(R.string.profiles, R.string.profiles_description, AppRoute.PROFILES),
    SettingsDestination(R.string.always_on_vpn, R.string.always_on_description, AppRoute.ALWAYS_ON),
    SettingsDestination(R.string.fingerprints, R.string.fingerprints_description, AppRoute.FINGERPRINTS),
    SettingsDestination(R.string.split_tunneling, R.string.split_tunneling_description, AppRoute.SPLIT_TUNNEL),
    SettingsDestination(R.string.failover, R.string.failover_description, AppRoute.FAILOVER),
)

internal val visibilitySettingsDestination =
    SettingsDestination(R.string.visibility, R.string.visibility_description, AppRoute.VISIBILITY)

internal val diagnosticLogSettingsDestination =
    SettingsDestination(R.string.diagnostic_log, R.string.diagnostic_log_description, AppRoute.DIAGNOSTIC_LOG)

internal val settingsDestinations = connectionSettingsDestinations + listOf(
    visibilitySettingsDestination,
    diagnosticLogSettingsDestination,
)

@Composable
internal fun MegaProxyNavHost(
    activity: Activity,
    readConnectionStats: () -> NativeConnectionStats? = ConnectionStatsReader::snapshot,
) {
    val navController = rememberNavController()
    val hostKeyPrompt by net.megaproxy487.vpn.SshHostKeyPromptState.pending
    val back = { navController.popBackStack(); Unit }

    LaunchedEffect(hostKeyPrompt) {
        if (hostKeyPrompt != null && navController.currentDestination?.route != AppRoute.SSH_HOST_KEY) {
            navController.navigate(AppRoute.SSH_HOST_KEY)
        } else if (hostKeyPrompt == null && navController.currentDestination?.route == AppRoute.SSH_HOST_KEY) {
            navController.popBackStack()
        }
    }

    NavHost(navController = navController, startDestination = AppRoute.MAIN) {
        composable(AppRoute.MAIN) {
            ScreenDestination(AppRoute.MAIN) { MainScreen(
                activity = activity,
                readConnectionStats = readConnectionStats,
                onOpenSettings = { navController.navigate(AppRoute.SETTINGS) },
                onOpenConnectionTest = { navController.navigate(AppRoute.CONNECTION_TEST) },
                onEditProfile = { navController.navigate(AppRoute.profileEditor(it)) },
            ) }
        }
        composable(AppRoute.SETTINGS) {
            ScreenDestination(AppRoute.SETTINGS) { SettingsHomeScreen(activity, back) { navController.navigate(it) } }
        }
        composable(AppRoute.PROFILES) {
            ScreenDestination(AppRoute.PROFILES) { ProfilesScreen(activity, back) { navController.navigate(AppRoute.profileEditor(it)) } }
        }
        composable(
            route = AppRoute.PROFILE_EDITOR,
            arguments = listOf(navArgument("profileId") { type = NavType.StringType }),
        ) { entry ->
            ScreenDestination("profile-editor") { ProfileEditorScreen(activity, entry.arguments?.getString("profileId"), back) }
        }
        composable(AppRoute.ALWAYS_ON) { ScreenDestination(AppRoute.ALWAYS_ON) { AlwaysOnSettingsScreen(activity, back) } }
        composable(AppRoute.FINGERPRINTS) { ScreenDestination(AppRoute.FINGERPRINTS) { TlsFingerprintScreen(activity, back) } }
        composable(AppRoute.SPLIT_TUNNEL) { ScreenDestination(AppRoute.SPLIT_TUNNEL) { SplitTunnelScreen(activity, back) } }
        composable(AppRoute.FAILOVER) { ScreenDestination(AppRoute.FAILOVER) { FailoverSettingsScreen(activity, back) } }
        composable(AppRoute.VISIBILITY) { ScreenDestination(AppRoute.VISIBILITY) { VisibilityScreen(activity, back) } }
        composable(AppRoute.CONNECTION_TEST) { ScreenDestination(AppRoute.CONNECTION_TEST) { ConnectionTestScreen(activity, autoStart = true, onBack = back) } }
        composable(AppRoute.DIAGNOSTIC_LOG) { ScreenDestination(AppRoute.DIAGNOSTIC_LOG) { DiagnosticLogScreen(activity, back) } }
        composable(AppRoute.SSH_HOST_KEY) {
            hostKeyPrompt?.let { prompt ->
                SshHostKeyScreen(activity, prompt) {
                    net.megaproxy487.vpn.SshHostKeyPromptState.clear()
                    if (navController.currentDestination?.route == AppRoute.SSH_HOST_KEY) navController.popBackStack()
                }
            }
        }
    }
}

@Composable
private fun ScreenDestination(route: String, content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize().testTag("screen-$route")) { content() }
}
