package com.famage.remoconnect.ui.components

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.HelpOutline
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.famage.remoconnect.data.updater.UpdateState
import com.famage.remoconnect.ui.viewmodel.AppTab

@Composable
fun AppSidebarContent(
    selectedTab: AppTab,
    onTabSelected: (AppTab) -> Unit,
    updateState: UpdateState,
    onCheckForUpdatesClicked: () -> Unit,
    onCloseDrawer: () -> Unit
) {
    val context = LocalContext.current

    ModalDrawerSheet(
        drawerContainerColor = Color(0xFF141724),
        drawerContentColor = Color.White,
        modifier = Modifier.width(300.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxHeight()
                .verticalScroll(rememberScrollState())
        ) {
            // App Header
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF1E2235))
                    .padding(20.dp)
            ) {
                Column {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Surface(
                            modifier = Modifier.size(44.dp),
                            shape = CircleShape,
                            color = Color(0xFF3F51B5)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = Icons.Default.Tv,
                                    contentDescription = null,
                                    tint = Color.White,
                                    modifier = Modifier.size(24.dp)
                                )
                            }
                        }
                        Column {
                            Text(
                                text = "RemoConnect",
                                color = Color.White,
                                fontSize = 18.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "TV & Media Remote",
                                color = Color.Gray,
                                fontSize = 12.sp
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = Color(0xFF2B3047)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 6.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Version 1.0",
                                color = Color.LightGray,
                                fontSize = 12.sp
                            )

                            if (updateState is UpdateState.Available) {
                                Surface(
                                    shape = CircleShape,
                                    color = Color(0xFFFF9800)
                                ) {
                                    Text(
                                        text = "NEW UPDATE",
                                        color = Color.Black,
                                        fontSize = 9.sp,
                                        fontWeight = FontWeight.Bold,
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Navigation Header
            PaddingText(text = "NAVIGATION")

            NavigationDrawerItem(
                label = { Text("Remote Control") },
                icon = { Icon(Icons.Default.SettingsRemote, contentDescription = null) },
                selected = selectedTab == AppTab.REMOTE,
                onClick = {
                    onTabSelected(AppTab.REMOTE)
                    onCloseDrawer()
                },
                colors = drawerItemColors()
            )

            NavigationDrawerItem(
                label = { Text("Touchpad & Trackpad") },
                icon = { Icon(Icons.Default.TouchApp, contentDescription = null) },
                selected = selectedTab == AppTab.TOUCHPAD,
                onClick = {
                    onTabSelected(AppTab.TOUCHPAD)
                    onCloseDrawer()
                },
                colors = drawerItemColors()
            )

            NavigationDrawerItem(
                label = { Text("Screen & Stream Cast") },
                icon = { Icon(Icons.Default.PlayArrow, contentDescription = null) },
                selected = selectedTab == AppTab.STREAM,
                onClick = {
                    onTabSelected(AppTab.STREAM)
                    onCloseDrawer()
                },
                colors = drawerItemColors()
            )

            NavigationDrawerItem(
                label = { Text("App Shortcuts") },
                icon = { Icon(Icons.Default.Apps, contentDescription = null) },
                selected = selectedTab == AppTab.APPS,
                onClick = {
                    onTabSelected(AppTab.APPS)
                    onCloseDrawer()
                },
                colors = drawerItemColors()
            )

            NavigationDrawerItem(
                label = { Text("TV Devices") },
                icon = { Icon(Icons.Default.Tv, contentDescription = null) },
                selected = selectedTab == AppTab.DISCOVERY,
                onClick = {
                    onTabSelected(AppTab.DISCOVERY)
                    onCloseDrawer()
                },
                colors = drawerItemColors()
            )

            HorizontalDivider(
                modifier = Modifier.padding(vertical = 12.dp),
                color = Color(0xFF2C324B)
            )

            // Updates Section
            PaddingText(text = "UPDATES & MAINTENANCE")

            NavigationDrawerItem(
                label = {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Check for Updates")
                        if (updateState is UpdateState.Available) {
                            Badge(containerColor = Color(0xFF4CAF50)) {
                                Text("Available")
                            }
                        }
                    }
                },
                icon = { Icon(Icons.Default.SystemUpdate, contentDescription = null) },
                selected = false,
                onClick = {
                    onCloseDrawer()
                    onCheckForUpdatesClicked()
                },
                colors = drawerItemColors()
            )

            HorizontalDivider(
                modifier = Modifier.padding(vertical = 12.dp),
                color = Color(0xFF2C324B)
            )

            // External Links Section
            PaddingText(text = "RESOURCES & LINKS")

            NavigationDrawerItem(
                label = { Text("GitHub Releases & Source") },
                icon = { Icon(Icons.Default.Code, contentDescription = null) },
                selected = false,
                onClick = {
                    val intent = Intent(
                        Intent.ACTION_VIEW,
                        Uri.parse("https://github.com/famage/RemoConnect")
                    )
                    context.startActivity(intent)
                },
                colors = drawerItemColors()
            )

            NavigationDrawerItem(
                label = { Text("Help & Documentation") },
                icon = { Icon(Icons.AutoMirrored.Filled.HelpOutline, contentDescription = null) },
                selected = false,
                onClick = {
                    val intent = Intent(
                        Intent.ACTION_VIEW,
                        Uri.parse("https://github.com/famage/RemoConnect#readme")
                    )
                    context.startActivity(intent)
                },
                colors = drawerItemColors()
            )
        }
    }
}

@Composable
private fun PaddingText(text: String) {
    Text(
        text = text,
        color = Color(0xFF757D9A),
        fontSize = 11.sp,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)
    )
}

@Composable
private fun drawerItemColors() = NavigationDrawerItemDefaults.colors(
    selectedContainerColor = Color(0xFF3F51B5),
    selectedIconColor = Color.White,
    selectedTextColor = Color.White,
    unselectedContainerColor = Color.Transparent,
    unselectedIconColor = Color(0xFF9FA8DA),
    unselectedTextColor = Color.LightGray
)
