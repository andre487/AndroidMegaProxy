package net.megaproxy487

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import net.megaproxy487.vpn.ConnectionSession
import net.megaproxy487.vpn.connectionDuration
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.IconButton
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material3.Button
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import net.megaproxy487.data.ConfigWrites
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.foundation.clickable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.graphics.Color
import net.megaproxy487.uiStringResource as stringResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import net.megaproxy487.vpn.ConnectionStatsReader
import net.megaproxy487.vpn.NativeConnectionStats
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.megaproxy487.data.ConfigStore
import net.megaproxy487.data.ConfigIoDispatcher
import net.megaproxy487.vpn.ProxyVpnService
import net.megaproxy487.vpn.SshHostKeyPromptState
import net.megaproxy487.vpn.VpnConnectionState
import net.megaproxy487.vpn.VpnRuntimeState
import net.megaproxy487.vpn.VpnTransportProtocol
import net.megaproxy487.vpn.readAlwaysOnVpnStatus
import net.megaproxy487.vpn.AlwaysOnVpnStatus
import net.megaproxy487.vpn.hasOtherProvider
import net.megaproxy487.vpn.openAndroidVpnSettings
import net.megaproxy487.model.ProfileColors
import net.megaproxy487.model.ProxyType
import net.megaproxy487.model.ProxyProfile
import net.megaproxy487.ui.theme.MegaProxyTheme

class MainActivity : LocalizedActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        BatteryOptimizationReminder.maybeRequest(this)
        setContent { MegaProxyTheme { MegaProxyNavHost(this) } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        ProxyVpnService.refreshStatus(this)
        val status = readAlwaysOnVpnStatus(this)
        val store = ConfigStore(this)
        val profileId = if (status.enabled && !ProxyVpnService.isRunning && !store.isFailoverActive()) store.alwaysOnProfileId() else store.connectionProfileId()
        VpnRuntimeState.updateSystem(status.enabled, status.lockdown, profileId)
    }

    companion object {
        const val ACTION_REVIEW_SSH_HOST_KEY = "net.megaproxy487.REVIEW_SSH_HOST_KEY"
        const val EXTRA_PROFILE_ID = "profile_id"
        const val EXTRA_HOP = "hop"
        const val EXTRA_ALGORITHM = "algorithm"
        const val EXTRA_FINGERPRINT = "fingerprint"
        const val EXTRA_CHANGED = "changed"
        const val EXTRA_TEST_ONLY = "test_only"
    }
}

@Composable
internal fun ProfileTypeBadge(type: ProxyType, foreground: Color, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        color = Color.Transparent,
        contentColor = foreground,
        shape = RoundedCornerShape(8.dp),
        border = BorderStroke(1.dp, foreground.copy(alpha = 0.5f)),
    ) {
        Text(
            stringResource(type.titleRes),
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 12.sp, lineHeight = 16.sp, letterSpacing = 0.sp),
            maxLines = 2,
            softWrap = true,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp),
        )
    }
}

/** Keep the type readable while a long name fades beneath its trailing badge. */
@Composable
private fun ProfileSelectorLabel(
    name: String,
    type: ProxyType,
    foreground: Color,
    modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.bodyLarge,
) {
    var badgeWidth by remember { mutableStateOf(0) }
    var availableWidth by remember { mutableStateOf(Int.MAX_VALUE) }
    val density = LocalDensity.current
    Box(modifier.onSizeChanged { availableWidth = it.width }) {
        val stacked = availableWidth < with(density) { (220.dp * fontScale).toPx() }
        if (stacked) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(name, color = foreground, style = style, maxLines = 1, overflow = TextOverflow.Ellipsis)
                ProfileTypeBadge(type, foreground)
            }
        } else {
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterStart) {
                Text(
                    name,
                    color = foreground,
                    style = style,
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Clip,
                    modifier = Modifier.fillMaxWidth()
                        .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                        .drawWithContent {
                            drawContent()
                            // Mask only the name layer: the badge and its border stay crisp.
                            val end = (size.width - badgeWidth - 6.dp.toPx()).coerceAtLeast(0f)
                            val start = (end - 24.dp.toPx()).coerceAtLeast(0f)
                            drawRect(
                                brush = Brush.horizontalGradient(
                                    colors = listOf(Color.Black, Color.Transparent),
                                    startX = start,
                                    endX = end.coerceAtLeast(start + 1f),
                                ),
                                blendMode = BlendMode.DstIn,
                            )
                        },
                )
                ProfileTypeBadge(
                    type,
                    foreground,
                    Modifier.align(Alignment.CenterEnd).onSizeChanged { badgeWidth = it.width },
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MainScreen(
    activity: Activity,
    onOpenSettings: () -> Unit,
    onOpenConnectionTest: () -> Unit,
    onEditProfile: (String) -> Unit,
) {
    val connection by VpnRuntimeState.connection
    val runtimeAlwaysOn by VpnRuntimeState.alwaysOn
    val runtimeLockdown by VpnRuntimeState.lockdown
    val runtimeProfileId by VpnRuntimeState.connectionProfileId
    val networkWarning by VpnRuntimeState.networkWarning
    val transportProtocol by VpnRuntimeState.transportProtocol
    val store = remember { ConfigStore(activity) }
    val writeStatus by ConfigWrites.status.collectAsState()
    var error by remember { mutableStateOf<String?>(null) }
    var actionsMenuExpanded by remember { mutableStateOf(false) }
    var profileMenuExpanded by remember { mutableStateOf(false) }
    var profiles by remember { mutableStateOf(store.sortedProfiles()) }
    var activeProfileId by remember { mutableStateOf(store.activeProfileId()) }
    var connectionProfileId by remember { mutableStateOf(store.connectionProfileId()) }
    var connectionStats by remember { mutableStateOf<DisplayedConnectionStats?>(null) }
    var systemVpnStatus by remember { mutableStateOf(readAlwaysOnVpnStatus(activity)) }
    var showCrashReport by remember { mutableStateOf(CrashHandler.hasPendingReport()) }
    var showAlwaysOnConflict by remember { mutableStateOf(false) }
    var pendingReconnect by remember { mutableStateOf(store.hasPendingReconnect()) }
    var globalSettings by remember { mutableStateOf(store.globalConnectionSettings()) }
    val alwaysOn = runtimeAlwaysOn || systemVpnStatus.enabled
    val lockdown = runtimeLockdown || systemVpnStatus.lockdown
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                scope.launch {
                    val refreshed = withContext(ConfigIoDispatcher) {
                        val status = readAlwaysOnVpnStatus(activity)
                        RefreshedMainConfig(
                            status = status,
                            profiles = store.sortedProfiles(),
                            activeProfileId = store.activeProfileId(),
                            connectionProfileId = if (status.enabled && !ProxyVpnService.isRunning && !store.isFailoverActive()) store.alwaysOnProfileId() else store.connectionProfileId(),
                            pendingReconnect = store.hasPendingReconnect(),
                            globalSettings = store.globalConnectionSettings(),
                        )
                    }
                    systemVpnStatus = refreshed.status
                    profiles = refreshed.profiles
                    activeProfileId = refreshed.activeProfileId
                    connectionProfileId = refreshed.connectionProfileId
                    pendingReconnect = refreshed.pendingReconnect
                    globalSettings = refreshed.globalSettings
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(connection, writeStatus) {
        if (writeStatus.pending == 0) {
            val saved = withContext(ConfigIoDispatcher) {
                Triple(store.sortedProfiles(), store.globalConnectionSettings(), store.hasPendingReconnect())
            }
            profiles = saved.first
            globalSettings = saved.second
            pendingReconnect = saved.third
        }
    }
    LaunchedEffect(lifecycleOwner, connection) {
        if (connection != VpnConnectionState.CONNECTED) {
            connectionStats = null
            return@LaunchedEffect
        }
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            var previous: NativeConnectionStats? = null
            var previousAt = 0L
            var smoothedDownload = 0.0
            var smoothedUpload = 0.0
            while (true) {
                // JNI reflection and JSON decoding are small but not frame work. Some
                // vendor devices expose their cost as visible input latency, so sample
                // away from the main dispatcher.
                val snapshot = withContext(Dispatchers.Default) {
                    ConnectionStatsReader.snapshot()
                }
                val sampledAt = android.os.SystemClock.elapsedRealtime()
                if (snapshot != null) {
                    previous?.let { old ->
                        val download = sampledTrafficRate(snapshot.downloadBytes, old.downloadBytes, sampledAt - previousAt)
                        val upload = sampledTrafficRate(snapshot.uploadBytes, old.uploadBytes, sampledAt - previousAt)
                        val alpha = 0.35
                        smoothedDownload = if (smoothedDownload == 0.0) download else alpha * download + (1 - alpha) * smoothedDownload
                        smoothedUpload = if (smoothedUpload == 0.0) upload else alpha * upload + (1 - alpha) * smoothedUpload
                    }
                    previous = snapshot
                    previousAt = sampledAt
                    connectionStats = DisplayedConnectionStats(snapshot, smoothedDownload, smoothedUpload)
                }
                delay(1_000)
            }
        }
    }
    val vpnPermission = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (VpnService.prepare(activity) == null) {
            if (!isAlwaysOnVpnActive(activity)) ProxyVpnService.start(activity) else error = null
        } else {
            val status = readAlwaysOnVpnStatus(activity)
            if (status.hasOtherProvider) {
                error = null
                showAlwaysOnConflict = true
            } else {
                error = activity.uiText(R.string.vpn_permission_denied)
            }
        }
    }
    val requestVpnAccess = {
        val status = readAlwaysOnVpnStatus(activity)
        if (status.hasOtherProvider) {
            error = null
            showAlwaysOnConflict = true
        } else {
            val intent = VpnService.prepare(activity)
            if (intent == null) {
                ProxyVpnService.start(activity)
            } else {
                vpnPermission.launch(intent)
            }
        }
    }
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        requestVpnAccess()
    }
    val connect = {
        if (isAlwaysOnVpnActive(activity)) {
            systemVpnStatus = readAlwaysOnVpnStatus(activity)
            error = null
        } else {
            error = globalSettings.applyTo(store.activeProfile().config).validationError()?.let { activity.uiText(it) }
        }
        if (error == null && !isAlwaysOnVpnActive(activity)) {
            if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(
                    activity,
                    Manifest.permission.POST_NOTIFICATIONS,
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            } else {
                requestVpnAccess()
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { ScreenTitle(stringResource(R.string.app_name)) },
                actions = {
                    Box {
                        IconButton(onClick = { actionsMenuExpanded = true }) {
                            Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.main_actions))
                        }
                        DropdownMenu(actionsMenuExpanded, { actionsMenuExpanded = false }) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.test_connection)) },
                                enabled = connection != VpnConnectionState.CONNECTING,
                                onClick = { actionsMenuExpanded = false; onOpenConnectionTest() },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.settings)) },
                                onClick = { actionsMenuExpanded = false; onOpenSettings() },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.feedback)) },
                                onClick = {
                                    actionsMenuExpanded = false
                                    scope.launch {
                                        runCatching {
                                            val intent = withContext(Dispatchers.IO) {
                                                FeedbackEmail.createIntent(activity, connection, alwaysOn, lockdown)
                                            }
                                            activity.startActivity(intent)
                                        }.onFailure {
                                            error = activity.uiText(R.string.could_not_open_email)
                                        }
                                    }
                                },
                            )
                        }
                    }
                },
            )
        },
        contentWindowInsets = WindowInsets.safeDrawing,
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.TopCenter) {
            Column(
                Modifier.widthIn(max = 720.dp).fillMaxWidth().fillMaxHeight().verticalScroll(rememberScrollState()).padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
            SaveStatusBanner()
            val connected = connection == VpnConnectionState.CONNECTED
            val statusLabel = when (connection) {
                VpnConnectionState.CONNECTED -> activity.uiText(R.string.status_connected)
                VpnConnectionState.CONNECTING -> activity.uiText(R.string.status_connecting)
                VpnConnectionState.DISCONNECTED -> activity.uiText(R.string.status_disconnected)
            }
            val statusColor = if (connected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
            Card(Modifier.fillMaxWidth().semantics {
                liveRegion = LiveRegionMode.Polite
                stateDescription = statusLabel
            }) {
                Column(
                    Modifier.fillMaxWidth().padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Icon(
                            if (connected) Icons.Filled.CheckCircle else Icons.Outlined.RadioButtonUnchecked,
                            contentDescription = null,
                            tint = statusColor,
                        )
                        Text(
                            if (connection == VpnConnectionState.CONNECTING) activity.uiText(R.string.status_connecting_progress) else statusLabel,
                            style = MaterialTheme.typography.titleLarge,
                            color = statusColor,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                    }
                    if (alwaysOn) {
                        Text(
                            if (lockdown) activity.uiText(R.string.always_on_lockdown) else activity.uiText(R.string.always_on_badge),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (connected && transportProtocol != VpnTransportProtocol.UNKNOWN) {
                        val transportLabel = when (transportProtocol) {
                            VpnTransportProtocol.HTTP_1_1 -> stringResource(R.string.transport_http_1_1)
                            VpnTransportProtocol.HTTP_2 -> stringResource(R.string.transport_http_2)
                            VpnTransportProtocol.SSH_MULTIPLEXED -> stringResource(R.string.transport_ssh_multiplexed)
                            VpnTransportProtocol.UNKNOWN -> ""
                        }
                        Text(
                            transportLabel,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            networkWarning?.let {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer), modifier = Modifier.fillMaxWidth()) {
                    Text(it, color = MaterialTheme.colorScheme.onTertiaryContainer, modifier = Modifier.padding(14.dp))
                }
                Spacer(Modifier.height(12.dp))
            }
            val displayedProfileId = if (alwaysOn) runtimeProfileId.ifEmpty { connectionProfileId } else activeProfileId
            val activeProfile = profiles.firstOrNull { it.id == displayedProfileId } ?: profiles.first()
            val actualProfile = profiles.firstOrNull { it.id == runtimeProfileId }
            val activeProfileError = globalSettings.applyTo(activeProfile.config).connectionValidationError()?.let { activity.uiText(it) }
            val profileColor = Color(ProfileColors.argb[Math.floorMod(activeProfile.colorIndex, ProfileColors.argb.size)])
            val onProfileColor = profileForeground(profileColor)
            Box(Modifier.fillMaxWidth()) {
                Card(
                    Modifier.fillMaxWidth().clickable { profileMenuExpanded = true },
                    colors = CardDefaults.cardColors(
                        containerColor = profileColor,
                        contentColor = onProfileColor,
                        disabledContainerColor = profileColor,
                        disabledContentColor = onProfileColor,
                    ),
                ) {
                    Column(Modifier.fillMaxWidth().padding(14.dp)) {
                    Text(stringResource(R.string.profile), style = MaterialTheme.typography.labelMedium)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        if (activeProfile.flagEmoji.isNotEmpty()) {
                            Surface(
                                color = Color.White,
                                contentColor = Color.Black,
                                shape = RoundedCornerShape(6.dp),
                                border = BorderStroke(1.dp, Color.Black.copy(alpha = 0.45f)),
                            ) {
                                Text(activeProfile.flagEmoji, modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp))
                            }
                        }
                        ProfileSelectorLabel(
                            activeProfile.localizedName(activity),
                            activeProfile.config.type,
                            onProfileColor,
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Icon(Icons.Filled.ArrowDropDown, contentDescription = stringResource(R.string.select_profile), tint = onProfileColor)
                    }
                    if (actualProfile != null && actualProfile.id != activeProfile.id && connection != VpnConnectionState.DISCONNECTED) {
                        Text(stringResource(R.string.connected_through, actualProfile.localizedNameWithFlag(activity)), style = MaterialTheme.typography.bodySmall)
                    }
                        DropdownMenu(
                            profileMenuExpanded,
                            { profileMenuExpanded = false },
                            modifier = Modifier.widthIn(min = 280.dp),
                        ) {
                            profiles.forEach { profile ->
                                DropdownMenuItem(
                                    text = {
                                        ProfileSelectorLabel(
                                            profile.localizedNameWithFlag(activity),
                                            profile.config.type,
                                            MaterialTheme.colorScheme.onSurface,
                                            modifier = Modifier.fillMaxWidth(),
                                        )
                                    },
                                    onClick = {
                                        val useAsAlwaysOn = isAlwaysOnVpnActive(activity)
                                        if (useAsAlwaysOn) {
                                            connectionProfileId = profile.id
                                        } else {
                                            activeProfileId = profile.id
                                        }
                                        if (useAsAlwaysOn || connection != VpnConnectionState.DISCONNECTED) {
                                            ProxyVpnService.switchProfile(activity, profile.id, useAsAlwaysOn)
                                        } else {
                                            ConfigWrites.submit("active-profile") { store.setActiveProfile(profile.id) }
                                        }
                                        profileMenuExpanded = false
                                    },
                                )
                            }
                        }
                    }
                }
            }
            if (connection == VpnConnectionState.DISCONNECTED && activeProfileError != null) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    AdaptiveLabelRow(
                        modifier = Modifier.padding(14.dp),
                        label = { Text(activeProfileError, modifier = it, color = MaterialTheme.colorScheme.onErrorContainer) },
                        trailing = {
                            TextButton(shape = RoundedCornerShape(12.dp), onClick = { onEditProfile(activeProfile.id) }) { Text(stringResource(R.string.configure)) }
                        },
                    )
                }
            }
            Button(shape = RoundedCornerShape(12.dp),
                onClick = {
                    if (isAlwaysOnVpnActive(activity)) {
                        systemVpnStatus = readAlwaysOnVpnStatus(activity)
                    } else if (connection != VpnConnectionState.DISCONNECTED) {
                        ProxyVpnService.stop(activity)
                    } else {
                        connect()
                    }
                },
                enabled = connectionActionEnabled(connection, alwaysOn, writeStatus, activeProfileError == null),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    when (connection) {
                        VpnConnectionState.CONNECTED -> stringResource(R.string.disconnect)
                        VpnConnectionState.CONNECTING -> stringResource(R.string.disconnect)
                        VpnConnectionState.DISCONNECTED -> stringResource(R.string.connect)
                    },
                )
            }
            if (connected) {
                FilledTonalButton(shape = RoundedCornerShape(12.dp),
                    onClick = { ProxyVpnService.reconnect(activity) },
                    enabled = writeStatus.pending == 0 && !writeStatus.failed,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    WrappingActions(horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally)) {
                        Text(stringResource(R.string.reconnect))
                        if (pendingReconnect) {
                            Surface(
                                color = MaterialTheme.colorScheme.tertiaryContainer,
                                contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                                shape = RoundedCornerShape(12.dp),
                            ) {
                                Text(
                                    stringResource(R.string.apply_new_settings),
                                    style = MaterialTheme.typography.labelSmall,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                                )
                            }
                        }
                    }
                }
            }
            error?.let {
                Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 12.dp))
            }
            if (alwaysOn) {
                Text(
                    activity.uiText(R.string.always_on_profile_help),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 12.dp),
                )
            }
            connectionStats?.let { stats ->
                ConnectionStatsCard(stats)
            }
            Text(
                stringResource(R.string.version_and_commit, BuildConfig.VERSION_NAME, BuildConfig.GIT_COMMIT_HASH),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.align(Alignment.CenterHorizontally).padding(top = 8.dp),
            )
            }
        }
    }

    if (showCrashReport) {
        AlertDialog(
            onDismissRequest = {},
            title = { DialogTitle(stringResource(R.string.unexpected_stop_title)) },
            text = { ScrollableDialogText(stringResource(R.string.crash_report_saved)) },
            confirmButton = {
                TextButton(shape = RoundedCornerShape(12.dp), onClick = {
                    scope.launch {
                        runCatching {
                            val intent = withContext(Dispatchers.IO) {
                                FeedbackEmail.createIntent(
                                    activity,
                                    connection,
                                    alwaysOn,
                                    lockdown,
                                    crashReport = true,
                                )
                            }
                            activity.startActivity(intent)
                            CrashHandler.markReportHandled()
                            showCrashReport = false
                        }.onFailure {
                            error = activity.uiText(R.string.could_not_open_email)
                        }
                    }
                }) { Text(stringResource(R.string.report)) }
            },
            dismissButton = {
                TextButton(shape = RoundedCornerShape(12.dp), onClick = {
                    CrashHandler.markReportHandled()
                    showCrashReport = false
                }) { Text(stringResource(R.string.close)) }
            },
        )
    }

    if (showAlwaysOnConflict) {
        AlertDialog(
            onDismissRequest = { showAlwaysOnConflict = false },
            title = { DialogTitle(stringResource(R.string.always_on_conflict_title)) },
            text = { ScrollableDialogText(activity.uiText(R.string.other_always_on_vpn)) },
            confirmButton = {
                TextButton(shape = RoundedCornerShape(12.dp), onClick = {
                    showAlwaysOnConflict = false
                    openAndroidVpnSettings(activity)
                }) { Text(stringResource(R.string.change_settings)) }
            },
            dismissButton = {
                TextButton(shape = RoundedCornerShape(12.dp), onClick = { showAlwaysOnConflict = false }) { Text(stringResource(R.string.cancel)) }
            },
        )
    }


}

private fun isAlwaysOnVpnActive(activity: Activity): Boolean =
    ProxyVpnService.isAlwaysOnMode || readAlwaysOnVpnStatus(activity).enabled

private data class DisplayedConnectionStats(
    val native: NativeConnectionStats,
    val downloadBytesPerSecond: Double,
    val uploadBytesPerSecond: Double,
)

private data class RefreshedMainConfig(
    val status: AlwaysOnVpnStatus,
    val profiles: List<ProxyProfile>,
    val activeProfileId: String,
    val connectionProfileId: String,
    val pendingReconnect: Boolean,
    val globalSettings: net.megaproxy487.model.GlobalConnectionSettings,
)

@Composable
private fun ConnectionStatsCard(stats: DisplayedConnectionStats) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val unitSystem = TrafficUnitPreferences.current(context)
    Card(Modifier.fillMaxWidth()) {
        AdaptiveStatColumns(Modifier.padding(14.dp)) { statModifier ->
            StatValue(
                label = stringResource(R.string.traffic_download),
                value = "↓ ${formatTrafficRate(stats.downloadBytesPerSecond, unitSystem, systemFormattingLocale())}",
                supportingValue = stringResource(
                    R.string.traffic_total,
                    formatTrafficBytes(stats.native.downloadBytes, unitSystem, systemFormattingLocale()),
                ),
                modifier = statModifier,
            )
            StatValue(
                label = stringResource(R.string.traffic_upload),
                value = "↑ ${formatTrafficRate(stats.uploadBytesPerSecond, unitSystem, systemFormattingLocale())}",
                supportingValue = stringResource(
                    R.string.traffic_total,
                    formatTrafficBytes(stats.native.uploadBytes, unitSystem, systemFormattingLocale()),
                ),
                modifier = statModifier,
            )
            val latency = stats.native.proxyLatencyMillis
            val ageMillis = System.currentTimeMillis() - stats.native.proxyLatencyAtMillis
            StatValue(
                label = stringResource(R.string.proxy_latency),
                value = if (latency <= 0) "—" else stringResource(
                    R.string.latency_milliseconds,
                    latency.toInt(),
                ),
                supportingValue = if (latency <= 0) null else formatAge(ageMillis).ifEmpty { null },
                modifier = statModifier,
            )
        }
        val samples = stats.native.connectionSamples
        Text(
            if (samples == 0) stringResource(R.string.connection_errors_no_samples)
            else stringResource(
                R.string.connection_errors,
                stats.native.connectionErrorRate * 100,
                pluralStringResource(R.plurals.connection_samples, samples, samples),
            ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 14.dp, end = 14.dp, bottom = 12.dp),
        )
        val session by VpnRuntimeState.session
        session?.let { ConnectionTimingDetails(it) }
    }
}

@Composable
private fun ConnectionTimingDetails(session: ConnectionSession) {
    val lifecycleOwner = LocalLifecycleOwner.current
    var duration by remember(session) {
        mutableStateOf(connectionDuration(session.elapsedMillis(SystemClock.elapsedRealtime())))
    }
    LaunchedEffect(session, lifecycleOwner) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                duration = connectionDuration(session.elapsedMillis(SystemClock.elapsedRealtime()))
                delay(duration.nextUpdateDelayMillis)
            }
        }
    }
    val startedAt = formatConnectionStartedAt(session.startedAtMillis, systemFormattingLocale())
    val seconds = duration.seconds
    val elapsed = when {
        seconds < 60 -> stringResource(R.string.connection_duration_seconds, seconds)
        seconds < 600 -> stringResource(R.string.connection_duration_minutes_seconds, seconds / 60, seconds % 60)
        seconds < 3_600 -> stringResource(R.string.connection_duration_minutes, seconds / 60)
        seconds < 86_400 -> stringResource(R.string.connection_duration_hours_minutes, seconds / 3_600, seconds / 60 % 60)
        else -> stringResource(R.string.connection_duration_days_hours, seconds / 86_400, seconds / 3_600 % 24)
    }
    Column(Modifier.padding(start = 14.dp, end = 14.dp, bottom = 12.dp)) {
        Text(
            stringResource(R.string.connection_started_at, startedAt),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            stringResource(R.string.connection_duration, elapsed),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun StatValue(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    supportingValue: String? = null,
) {
    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, style = MaterialTheme.typography.titleSmall, textAlign = TextAlign.Center)
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        supportingValue?.let {
            Text(
                it,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun formatAge(ageMillis: Long): String = when {
    ageMillis < 30_000 -> ""
    ageMillis < 120_000 -> pluralStringResource(
        R.plurals.latency_seconds_ago,
        (ageMillis / 1_000).toInt(),
        (ageMillis / 1_000).toInt(),
    )
    else -> pluralStringResource(
        R.plurals.latency_minutes_ago,
        (ageMillis / 60_000).toInt(),
        (ageMillis / 60_000).toInt(),
    )
}
