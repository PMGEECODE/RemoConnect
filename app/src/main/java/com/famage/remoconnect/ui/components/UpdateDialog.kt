package com.famage.remoconnect.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.famage.remoconnect.data.updater.UpdateState

@Composable
fun UpdateDialog(
    updateState: UpdateState,
    onDismiss: () -> Unit,
    onDownloadClicked: () -> Unit,
    onInstallClicked: () -> Unit,
    onCheckAgainClicked: () -> Unit
) {
    AlertDialog(
        onDismissRequest = {
            // Allow dismiss unless downloading
            if (updateState !is UpdateState.Downloading) {
                onDismiss()
            }
        },
        containerColor = Color(0xFF1E2235),
        titleContentColor = Color.White,
        textContentColor = Color.LightGray,
        title = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Icon(
                    imageVector = when (updateState) {
                        is UpdateState.UpToDate -> Icons.Default.CheckCircle
                        is UpdateState.Error -> Icons.Default.Error
                        else -> Icons.Default.SystemUpdate
                    },
                    contentDescription = null,
                    tint = when (updateState) {
                        is UpdateState.UpToDate -> Color(0xFF4CAF50)
                        is UpdateState.Error -> Color(0xFFF44336)
                        else -> Color(0xFF3F51B5)
                    }
                )
                Text(
                    text = when (updateState) {
                        is UpdateState.Checking -> "Checking for Updates"
                        is UpdateState.UpToDate -> "App Up to Date"
                        is UpdateState.Available -> "New Update Available"
                        is UpdateState.Downloading -> "Downloading Update"
                        is UpdateState.ReadyToInstall -> "Ready to Install"
                        is UpdateState.Error -> "Update Failed"
                        is UpdateState.Idle -> "App Version Info"
                    },
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                when (updateState) {
                    is UpdateState.Checking -> {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(16.dp),
                            modifier = Modifier.padding(vertical = 12.dp)
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(36.dp),
                                color = Color(0xFF3F51B5)
                            )
                            Text(
                                "Checking server for the latest version...",
                                color = Color.White,
                                fontSize = 14.sp
                            )
                        }
                    }

                    is UpdateState.UpToDate -> {
                        Text(
                            text = "You are using the latest version (v${updateState.currentVersionName}). No updates required at this time.",
                            color = Color.White,
                            fontSize = 14.sp
                        )
                    }

                    is UpdateState.Available -> {
                        val info = updateState.info
                        Text(
                            text = "Version ${info.versionName} is now available!",
                            color = Color.White,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 15.sp
                        )

                        if (info.releaseNotes.isNotBlank()) {
                            Text(
                                text = "What's New:",
                                color = Color.White,
                                fontWeight = FontWeight.Bold,
                                fontSize = 13.sp
                            )
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(Color(0xFF141724), RoundedCornerShape(8.dp))
                                    .padding(12.dp)
                            ) {
                                Text(
                                    text = info.releaseNotes,
                                    color = Color.LightGray,
                                    fontSize = 13.sp
                                )
                            }
                        }
                    }

                    is UpdateState.Downloading -> {
                        val info = updateState.info
                        val progress = updateState.progress
                        Text(
                            text = "Downloading RemoConnect v${info.versionName}...",
                            color = Color.White,
                            fontSize = 14.sp
                        )

                        LinearProgressIndicator(
                            progress = { progress / 100f },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(8.dp),
                            color = Color(0xFF3F51B5),
                            trackColor = Color(0xFF2C324B)
                        )

                        Text(
                            text = "$progress%",
                            color = Color.LightGray,
                            fontSize = 13.sp,
                            modifier = Modifier.align(Alignment.End)
                        )
                    }

                    is UpdateState.ReadyToInstall -> {
                        val info = updateState.info
                        Text(
                            text = "RemoConnect v${info.versionName} has been downloaded and is ready to install.",
                            color = Color.White,
                            fontSize = 14.sp
                        )
                    }

                    is UpdateState.Error -> {
                        Text(
                            text = updateState.message,
                            color = Color(0xFFFF6B6B),
                            fontSize = 14.sp
                        )
                    }

                    is UpdateState.Idle -> {
                        Text("Click below to check for updates.")
                    }
                }
            }
        },
        confirmButton = {
            when (updateState) {
                is UpdateState.Available -> {
                    Button(
                        onClick = onDownloadClicked,
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF3F51B5))
                    ) {
                        Icon(Icons.Default.Download, contentDescription = null)
                        Spacer(Modifier.width(6.dp))
                        Text("Download & Install")
                    }
                }

                is UpdateState.ReadyToInstall -> {
                    Button(
                        onClick = onInstallClicked,
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4CAF50))
                    ) {
                        Text("Install Now")
                    }
                }

                is UpdateState.Error -> {
                    Button(
                        onClick = onCheckAgainClicked,
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF3F51B5))
                    ) {
                        Text("Try Again")
                    }
                }

                is UpdateState.UpToDate -> {
                    TextButton(onClick = onDismiss) {
                        Text("OK", color = Color.White)
                    }
                }

                else -> {}
            }
        },
        dismissButton = {
            if (updateState is UpdateState.Available || updateState is UpdateState.Error) {
                TextButton(onClick = onDismiss) {
                    Text("Cancel", color = Color.Gray)
                }
            }
        }
    )
}
