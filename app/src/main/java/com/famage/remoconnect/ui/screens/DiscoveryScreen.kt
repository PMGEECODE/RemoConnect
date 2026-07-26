package com.famage.remoconnect.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.famage.remoconnect.data.model.TvDevice
import com.famage.remoconnect.ui.components.PinPairingDialog
import com.famage.remoconnect.ui.viewmodel.RemoteViewModel

@Composable
fun DiscoveryScreen(
    viewModel: RemoteViewModel,
    modifier: Modifier = Modifier
) {
    val activeDevice by viewModel.activeDevice.collectAsState()
    val savedDevices by viewModel.savedDevices.collectAsState()
    val allDiscoveredDevices by viewModel.discoveredDevices.collectAsState()
    val isScanning by viewModel.isScanning.collectAsState()
    val pairingRequired by viewModel.pairingRequired.collectAsState()
    val connectingDeviceIp by viewModel.connectingDeviceIp.collectAsState()
    val showPairingDialog by viewModel.showPairingDialog.collectAsState()
    val pairingErrorMessage by viewModel.pairingErrorMessage.collectAsState()
    val debugLogs by viewModel.debugLogs.collectAsState()

    var showManualDialog by remember { mutableStateOf(false) }
    var showDebugConsole by remember { mutableStateOf(true) }

    val savedIps = remember(savedDevices) { savedDevices.map { it.ipAddress }.toSet() }
    val newDiscoveredDevices = remember(allDiscoveredDevices, savedIps) {
        allDiscoveredDevices.filter { it.ipAddress !in savedIps }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFF12141D))
            .padding(16.dp)
    ) {
        // Header
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "TV Devices",
                color = Color.White,
                fontWeight = FontWeight.Bold,
                fontSize = 20.sp
            )

            Row {
                IconButton(onClick = { showManualDialog = true }) {
                    Icon(Icons.Default.Add, contentDescription = "Add IP", tint = Color(0xFF4FC3F7))
                }
                IconButton(
                    onClick = {
                        if (isScanning) viewModel.stopDeviceScan() else viewModel.startDeviceScan()
                    }
                ) {
                    Icon(
                        imageVector = Icons.Default.Refresh,
                        contentDescription = "Scan Network",
                        tint = if (isScanning) Color(0xFF4CAF50) else Color.White
                    )
                }
                IconButton(onClick = { showDebugConsole = !showDebugConsole }) {
                    Icon(
                        imageVector = Icons.Default.Terminal,
                        contentDescription = "Debug Console",
                        tint = if (showDebugConsole) Color(0xFFFFD54F) else Color(0xFF78909C)
                    )
                }
            }
        }

        // Scan progress
        if (isScanning) {
            Column(modifier = Modifier.padding(bottom = 12.dp)) {
                LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth(),
                    color = Color(0xFF3F51B5)
                )
                Text(
                    text = "Scanning network for Android TVs...",
                    color = Color.Gray,
                    fontSize = 11.sp,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
        }

        // Live Debug Console (expandable, selectable, copyable, scrollable)
        AnimatedVisibility(
            visible = showDebugConsole,
            enter = expandVertically(),
            exit = shrinkVertically()
        ) {
            DebugConsolePanel(
                logs = debugLogs,
                onClear = { viewModel.clearDebugLogs() },
                modifier = Modifier.padding(bottom = 12.dp)
            )
        }

        Text(
            text = "Saved Devices",
            color = Color.LightGray,
            fontWeight = FontWeight.SemiBold,
            fontSize = 14.sp,
            modifier = Modifier.padding(vertical = 8.dp)
        )

        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            if (savedDevices.isEmpty()) {
                item {
                    Text(
                        text = "No saved TVs yet. Select a discovered network TV below or add an IP manually.",
                        color = Color.Gray,
                        fontSize = 13.sp,
                        modifier = Modifier.padding(vertical = 4.dp)
                    )
                }
            } else {
                items(savedDevices) { device ->
                    val isSelected = activeDevice?.ipAddress == device.ipAddress
                    val isConnected = isSelected && (activeDevice?.isConnected == true)
                    val isConnecting = connectingDeviceIp == device.ipAddress
                    DeviceCard(
                        device = device,
                        isSelected = isSelected,
                        isConnected = isConnected,
                        isConnecting = isConnecting,
                        isPairingNeeded = isSelected && pairingRequired && !isConnected,
                        onSelect = { viewModel.selectDevice(device) },
                        onForget = { viewModel.forgetDevice(device) },
                        onConnect = { viewModel.connectDevice(device) }
                    )
                }
            }

            item {
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = "Discovered Network TVs (mDNS)",
                    color = Color.LightGray,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 14.sp
                )
            }

            if (newDiscoveredDevices.isEmpty()) {
                item {
                    Text(
                        text = if (isScanning) "Searching network for Google / TCL TVs..." else "No new network TVs found. Tap \u25B6 to scan or add IP manually.",
                        color = Color.Gray,
                        fontSize = 13.sp,
                        modifier = Modifier.padding(vertical = 8.dp)
                    )
                }
            } else {
                items(newDiscoveredDevices) { device ->
                    val isSelected = activeDevice?.ipAddress == device.ipAddress
                    val isConnected = isSelected && (activeDevice?.isConnected == true)
                    val isConnecting = connectingDeviceIp == device.ipAddress
                    DeviceCard(
                        device = device,
                        isSelected = isSelected,
                        isConnected = isConnected,
                        isConnecting = isConnecting,
                        isPairingNeeded = isSelected && pairingRequired && !isConnected,
                        onSelect = { viewModel.selectDevice(device) },
                        onForget = null,
                        onConnect = { viewModel.connectDevice(device) }
                    )
                }
            }
        }
    }

    if (showPairingDialog) {
        PinPairingDialog(
            deviceName = activeDevice?.name ?: "Android TV",
            errorMessage = pairingErrorMessage,
            onDismiss = { viewModel.dismissPairingDialog() },
            onVerifyPin = { pin -> viewModel.submitPairingPin(pin) }
        )
    }

    if (showManualDialog) {
        ManualIpDialog(
            onDismiss = { showManualDialog = false },
            onAddDevice = { name, ip, port ->
                viewModel.addManualTv(name, ip, port)
                showManualDialog = false
            }
        )
    }
}

@Composable
private fun DebugConsolePanel(
    logs: List<String>,
    onClear: () -> Unit,
    modifier: Modifier = Modifier
) {
    val clipboardManager = LocalClipboardManager.current
    var copyStatusText by remember { mutableStateOf<String?>(null) }

    Surface(
        shape = RoundedCornerShape(12.dp),
        color = Color(0xFF0D1117),
        modifier = modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Terminal,
                        contentDescription = null,
                        tint = Color(0xFFFFD54F),
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "Live Protocol Debug Console (${logs.size})",
                        color = Color(0xFFFFD54F),
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 12.sp
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (logs.isNotEmpty()) {
                        TextButton(
                            onClick = {
                                val fullText = logs.joinToString("\n")
                                clipboardManager.setText(AnnotatedString(fullText))
                                copyStatusText = "Copied!"
                            },
                            contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = Icons.Default.ContentCopy,
                                    contentDescription = "Copy All",
                                    tint = Color(0xFF4FC3F7),
                                    modifier = Modifier.size(12.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = copyStatusText ?: "Copy All",
                                    color = Color(0xFF4FC3F7),
                                    fontSize = 11.sp
                                )
                            }
                        }
                    }
                    TextButton(
                        onClick = {
                            copyStatusText = null
                            onClear()
                        },
                        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Text("Clear", color = Color(0xFF78909C), fontSize = 11.sp)
                    }
                }
            }

            HorizontalDivider(color = Color(0xFF1E2A38), thickness = 1.dp, modifier = Modifier.padding(vertical = 6.dp))

            if (logs.isEmpty()) {
                Text(
                    text = "No events yet. Start network scan or connect to a TV to see full protocol logs.",
                    color = Color(0xFF546E7A),
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace
                )
            } else {
                SelectionContainer {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 280.dp)
                    ) {
                        itemsIndexed(logs) { index, entry ->
                            val color = when {
                                entry.contains("[ERROR") || entry.contains("-ERROR]") || entry.contains("[CONNECT-FAILED]") || entry.contains("[mTLS-FAILED]") -> Color(0xFFEF5350)
                                entry.contains("[PAIRING-SUCCESS]") || entry.contains("[REPO-VERIFIED]") || entry.contains("[mTLS-SUCCESS]") -> Color(0xFF66BB6A)
                                entry.contains("[PAIRING-REQ-SENT]") || entry.contains("[mTLS-REQ-SENT]") || entry.contains("[mTLS-PIN-PROMPT]") -> Color(0xFF4FC3F7)
                                entry.contains("[PAIRING") || entry.contains("[mTLS") -> Color(0xFFFFD54F)
                                entry.contains("[CONNECT") -> Color(0xFF4FC3F7)
                                entry.contains("[KEYEVENT") || entry.contains("[V2-PAYLOAD]") -> Color(0xFFCE93D8)
                                entry.contains("[SCAN") -> Color(0xFF80CBC4)
                                entry.contains("[DISCONNECT") -> Color(0xFFFF8A65)
                                else -> Color(0xFF90A4AE)
                            }
                            Text(
                                text = "${logs.size - index}. $entry",
                                color = color,
                                fontSize = 10.sp,
                                fontFamily = FontFamily.Monospace,
                                lineHeight = 14.sp,
                                modifier = Modifier.padding(vertical = 1.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun DeviceCard(
    device: TvDevice,
    isSelected: Boolean,
    isConnected: Boolean,
    isConnecting: Boolean,
    isPairingNeeded: Boolean,
    onSelect: () -> Unit,
    onForget: (() -> Unit)?,
    onConnect: () -> Unit
) {
    val protocolLabel = when {
        device.connectionType == com.famage.remoconnect.data.model.ConnectionType.ANDROID_TV_REMOTE_V2 || device.port == 6466 || device.port == 6467 -> "Native TLS (6467/6466)"
        device.port == 5555 || device.connectionType == com.famage.remoconnect.data.model.ConnectionType.ADB_WIFI -> "ADB Wi-Fi (5555)"
        else -> "TCP · Port ${device.port}"
    }
    val serviceType = when {
        device.port == 6466 || device.port == 6467 -> "_androidtvremote2._tcp"
        else -> "_adb._tcp"
    }

    Surface(
        shape = RoundedCornerShape(16.dp),
        color = when {
            isPairingNeeded -> Color(0xFF4A3800)
            isConnected -> Color(0xFF1A2A4A)
            else -> Color(0xFF1E2130)
        },
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = !isConnecting) { onSelect() }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                    Icon(
                        imageVector = Icons.Default.Tv,
                        contentDescription = "TV",
                        tint = when {
                            isPairingNeeded -> Color(0xFFFFD54F)
                            isConnected -> Color(0xFF4FC3F7)
                            else -> Color.White
                        },
                        modifier = Modifier.size(32.dp)
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Column {
                        Text(
                            text = device.name,
                            color = Color.White,
                            fontWeight = FontWeight.Bold,
                            fontSize = 15.sp
                        )
                        Text(
                            text = "${device.ipAddress}:${device.port}",
                            color = Color.LightGray,
                            fontSize = 12.sp
                        )
                    }
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Button(
                        onClick = onConnect,
                        enabled = !isConnecting,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = when {
                                isConnecting -> Color(0xFF1565C0)
                                isPairingNeeded -> Color(0xFFFF9800)
                                isConnected -> Color(0xFF2E7D32)
                                isSelected -> Color(0xFF3F51B5)
                                else -> Color(0xFF455A64)
                            },
                            disabledContainerColor = Color(0xFF1565C0)
                        ),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
                    ) {
                        if (isConnecting) {
                            CircularProgressIndicator(
                                color = Color.White,
                                strokeWidth = 2.dp,
                                modifier = Modifier.size(14.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                        }
                        Text(
                            text = when {
                                isConnecting -> "Connecting"
                                isPairingNeeded -> "PIN"
                                isConnected -> "Active"
                                isSelected -> "Connect"
                                else -> "Use"
                            },
                            fontSize = 13.sp
                        )
                    }

                    if (onForget != null) {
                        IconButton(onClick = onForget, enabled = !isConnecting) {
                            Icon(
                                imageVector = Icons.Default.Delete,
                                contentDescription = "Forget TV",
                                tint = Color(0xFFFF8A65)
                            )
                        }
                    }
                }
            }

            // Rich device metadata row
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                DeviceChip(text = protocolLabel, color = Color(0xFF1565C0))
                DeviceChip(text = serviceType, color = Color(0xFF37474F))
                if (device.isPaired) DeviceChip(text = "Paired", color = Color(0xFF00695C))
                if (isSelected && !isConnected) DeviceChip(text = "Selected", color = Color(0xFF5E35B1))
                if (isConnected) DeviceChip(text = "● Connected", color = Color(0xFF2E7D32))
                if (isPairingNeeded) DeviceChip(text = "⚠ PIN Required", color = Color(0xFF795548))
            }
        }
    }
}

@Composable
private fun DeviceChip(text: String, color: Color) {
    Surface(
        shape = RoundedCornerShape(6.dp),
        color = color.copy(alpha = 0.7f)
    ) {
        Text(
            text = text,
            color = Color.White,
            fontSize = 10.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp)
        )
    }
}

@Composable
fun ManualIpDialog(
    onDismiss: () -> Unit,
    onAddDevice: (String, String, Int) -> Unit
) {
    var nameText by remember { mutableStateOf("Living room TV") }
    var ipText by remember { mutableStateOf("") }
    var portText by remember { mutableStateOf("6466") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add TV Manually") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = nameText,
                    onValueChange = { nameText = it },
                    label = { Text("Device Name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = ipText,
                    onValueChange = { ipText = it },
                    label = { Text("IP Address (e.g. 192.168.1.10)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = portText,
                    onValueChange = { portText = it },
                    label = { Text("Port (6466 = Native TV, 5555 = ADB)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val ip = ipText.trim()
                    val port = portText.trim().toIntOrNull() ?: 6466
                    if (ip.isNotBlank()) {
                        onAddDevice(nameText.trim().ifBlank { "My TV" }, ip, port)
                    }
                }
            ) { Text("Connect") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}
