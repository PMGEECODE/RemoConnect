package com.famage.remoconnect

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SettingsRemote
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import com.famage.remoconnect.cast.CastStreamingController
import com.famage.remoconnect.ui.components.TextInputDialog
import com.famage.remoconnect.ui.screens.AppShortcutsScreen
import com.famage.remoconnect.ui.screens.DiscoveryScreen
import com.famage.remoconnect.ui.screens.RemoteScreen
import com.famage.remoconnect.ui.screens.StreamScreen
import com.famage.remoconnect.ui.screens.TouchpadScreen
import com.famage.remoconnect.ui.theme.RemoConnectTheme
import com.famage.remoconnect.ui.viewmodel.AppTab
import com.famage.remoconnect.ui.viewmodel.RemoteViewModel

import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.SystemUpdate
import com.famage.remoconnect.data.updater.UpdateState
import com.famage.remoconnect.ui.components.AppSidebarContent
import com.famage.remoconnect.ui.components.UpdateDialog
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

class MainActivity : FragmentActivity() {
    private lateinit var viewModel: RemoteViewModel

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        requestNotificationPermissionIfNeeded()
        val repository = RemoConnectController.getRepository(applicationContext)
        viewModel = RemoteViewModel(repository)
        RemoConnectController.showRemoteNotification(applicationContext)

        setContent {
            RemoConnectTheme {
                RemoConnectApp(viewModel = viewModel)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        RemoConnectController.showRemoteNotification(applicationContext)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == NOTIFICATION_PERMISSION_REQUEST &&
            grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED
        ) {
            RemoConnectController.showRemoteNotification(applicationContext)
        }
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < 33) return
        val granted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                NOTIFICATION_PERMISSION_REQUEST
            )
        }
    }

    private companion object {
        private const val NOTIFICATION_PERMISSION_REQUEST = 2001
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RemoConnectApp(viewModel: RemoteViewModel) {
    val selectedTab by viewModel.selectedTab.collectAsState()
    val showKeyboardDialog by viewModel.showKeyboardDialog.collectAsState()
    val activeDevice by viewModel.activeDevice.collectAsState()
    val updateState by viewModel.updateState.collectAsState()
    val showUpdateDialog by viewModel.showUpdateDialog.collectAsState()

    val context = LocalContext.current
    val castController = remember { CastStreamingController(context) }
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val scope = rememberCoroutineScope()

    DisposableEffect(castController) {
        onDispose { castController.release() }
    }

    LaunchedEffect(activeDevice?.id, activeDevice?.isConnected) {
        RemoConnectController.showRemoteNotification(context)
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            AppSidebarContent(
                selectedTab = selectedTab,
                onTabSelected = { tab -> viewModel.selectTab(tab) },
                updateState = updateState,
                onCheckForUpdatesClicked = { viewModel.checkForUpdates(context) },
                onCloseDrawer = { scope.launch { drawerState.close() } }
            )
        }
    ) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = {
                        Text(
                            text = when (selectedTab) {
                                AppTab.REMOTE -> "RemoConnect Remote"
                                AppTab.TOUCHPAD -> "Touchpad"
                                AppTab.STREAM -> "Stream & Cast"
                                AppTab.APPS -> "App Shortcuts"
                                AppTab.DISCOVERY -> "TV Devices"
                            },
                            color = Color.White
                        )
                    },
                    navigationIcon = {
                        IconButton(onClick = { scope.launch { drawerState.open() } }) {
                            Icon(
                                imageVector = Icons.Default.Menu,
                                contentDescription = "Open Sidebar Menu",
                                tint = Color.White
                            )
                        }
                    },
                    actions = {
                        IconButton(onClick = { viewModel.checkForUpdates(context) }) {
                            BadgeBox(
                                showBadge = updateState is UpdateState.Available
                            ) {
                                Icon(
                                    imageVector = Icons.Default.SystemUpdate,
                                    contentDescription = "Check for updates",
                                    tint = if (updateState is UpdateState.Available) Color(0xFFFF9800) else Color.White
                                )
                            }
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = Color(0xFF141724)
                    )
                )
            },
            bottomBar = {
                NavigationBar(
                    containerColor = Color(0xFF1A1C29),
                    contentColor = Color.White
                ) {
                    NavigationBarItem(
                        selected = selectedTab == AppTab.REMOTE,
                        onClick = { viewModel.selectTab(AppTab.REMOTE) },
                        icon = { Icon(Icons.Default.SettingsRemote, contentDescription = "Remote") },
                        label = { Text("Remote") },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = Color.White,
                            indicatorColor = Color(0xFF3F51B5)
                        )
                    )
                    NavigationBarItem(
                        selected = selectedTab == AppTab.TOUCHPAD,
                        onClick = { viewModel.selectTab(AppTab.TOUCHPAD) },
                        icon = { Icon(Icons.Default.TouchApp, contentDescription = "Touchpad") },
                        label = { Text("Touchpad") },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = Color.White,
                            indicatorColor = Color(0xFF3F51B5)
                        )
                    )
                    NavigationBarItem(
                        selected = selectedTab == AppTab.STREAM,
                        onClick = { viewModel.selectTab(AppTab.STREAM) },
                        icon = { Icon(Icons.Default.PlayArrow, contentDescription = "Stream") },
                        label = { Text("Stream") },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = Color.White,
                            indicatorColor = Color(0xFF3F51B5)
                        )
                    )
                    NavigationBarItem(
                        selected = selectedTab == AppTab.APPS,
                        onClick = { viewModel.selectTab(AppTab.APPS) },
                        icon = { Icon(Icons.Default.Apps, contentDescription = "Apps") },
                        label = { Text("Apps") },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = Color.White,
                            indicatorColor = Color(0xFF3F51B5)
                        )
                    )
                    NavigationBarItem(
                        selected = selectedTab == AppTab.DISCOVERY,
                        onClick = { viewModel.selectTab(AppTab.DISCOVERY) },
                        icon = { Icon(Icons.Default.Tv, contentDescription = "TVs") },
                        label = { Text("TVs") },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = Color.White,
                            indicatorColor = Color(0xFF3F51B5)
                        )
                    )
                }
            }
        ) { innerPadding ->
            Box(modifier = Modifier.padding(innerPadding)) {
                when (selectedTab) {
                    AppTab.REMOTE -> RemoteScreen(viewModel = viewModel)
                    AppTab.TOUCHPAD -> TouchpadScreen(viewModel = viewModel)
                    AppTab.STREAM -> StreamScreen(controller = castController)
                    AppTab.APPS -> AppShortcutsScreen(viewModel = viewModel)
                    AppTab.DISCOVERY -> DiscoveryScreen(viewModel = viewModel)
                }

                if (showKeyboardDialog) {
                    TextInputDialog(
                        onDismiss = { viewModel.dismissKeyboardDialog() },
                        onSendText = { text -> viewModel.sendText(text) }
                    )
                }

                if (showUpdateDialog) {
                    UpdateDialog(
                        updateState = updateState,
                        onDismiss = { viewModel.dismissUpdateDialog() },
                        onDownloadClicked = { viewModel.downloadUpdate(context) },
                        onInstallClicked = { viewModel.installUpdate(context) },
                        onCheckAgainClicked = { viewModel.checkForUpdates(context) }
                    )
                }
            }
        }
    }
}

@Composable
private fun BadgeBox(
    showBadge: Boolean,
    content: @Composable () -> Unit
) {
    Box {
        content()
        if (showBadge) {
            Surface(
                modifier = Modifier
                    .size(8.dp)
                    .align(androidx.compose.ui.Alignment.TopEnd),
                shape = androidx.compose.foundation.shape.CircleShape,
                color = Color(0xFFFF9800)
            ) {}
        }
    }
}
