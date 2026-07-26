package com.famage.remoconnect.ui.screens

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.speech.RecognizerIntent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Input
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.famage.remoconnect.data.model.RemoteKey
import com.famage.remoconnect.data.model.TvDevice
import com.famage.remoconnect.ui.components.PinPairingDialog
import com.famage.remoconnect.ui.viewmodel.RemoteViewModel
import androidx.compose.ui.graphics.Canvas
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

private val DPadOrange = Color(0xFFE8602C)
private val DPadDark  = Color(0xFF222536)
private val DPadBorder = Color(0xFF3D425E)
private val CardBg = Color(0xFF25283A)

// ─── Main Screen ─────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RemoteScreen(
    viewModel: RemoteViewModel,
    modifier: Modifier = Modifier
) {
    val activeDevice     by viewModel.activeDevice.collectAsState()
    val connectionStatus by viewModel.connectionStatus.collectAsState()
    val pairingRequired  by viewModel.pairingRequired.collectAsState()
    val showPairingDialog by viewModel.showPairingDialog.collectAsState()
    val pairingErrorMessage by viewModel.pairingErrorMessage.collectAsState()
    val isMuted          by viewModel.isMuted.collectAsState()
    val volumeLevel      by viewModel.volumeLevel.collectAsState()
    val triggerVoice     by viewModel.triggerVoiceInput.collectAsState()
    val context = LocalContext.current

    // ── Voice recognizer launcher ────────────────────────────────────────
    val voiceLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val matches = result.data
                ?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
            val query = matches?.firstOrNull()
            if (!query.isNullOrBlank()) viewModel.sendVoiceQuery(query)
        }
        viewModel.onVoiceInputConsumed()
    }

    // Permission launcher for RECORD_AUDIO
    val audioPermLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            voiceLauncher.launch(buildVoiceIntent())
        }
    }

    // Trigger voice when VM sets the flag
    LaunchedEffect(triggerVoice) {
        if (!triggerVoice) return@LaunchedEffect
        val hasPermission = ContextCompat.checkSelfPermission(
            context, Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
        if (hasPermission) {
            voiceLauncher.launch(buildVoiceIntent())
        } else {
            audioPermLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        Color(0xFF12141D),
                        Color(0xFF1A1C29),
                        Color(0xFF0D0E15)
                    )
                )
            )
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        HeaderSection(
            activeDevice = activeDevice,
            connectionStatus = connectionStatus,
            pairingRequired = pairingRequired,
            onPowerClick   = { viewModel.pressKey(RemoteKey.POWER) },
            onKeyboardClick = { viewModel.openKeyboardDialog() },
            onVoiceClick   = { viewModel.startVoiceInput() },
            onPairClick    = { if (activeDevice != null) viewModel.connectDevice(activeDevice!!) }
        )

        Spacer(modifier = Modifier.height(8.dp))

        DPadCluster(onKeyPress = { viewModel.pressKey(it) })

        Spacer(modifier = Modifier.height(12.dp))

        ActionRow(onKeyPress = { viewModel.pressKey(it) })

        Spacer(modifier = Modifier.height(12.dp))

        RockersSection(
            isMuted = isMuted,
            volumeLevel = volumeLevel,
            onMuteToggle  = { viewModel.toggleMute() },
            onKeyPress    = { viewModel.pressKey(it) }
        )

        Spacer(modifier = Modifier.height(12.dp))

        MediaControlRow(onKeyPress = { viewModel.pressKey(it) })
    }

    if (showPairingDialog) {
        PinPairingDialog(
            deviceName     = activeDevice?.name ?: "Android TV",
            errorMessage   = pairingErrorMessage,
            onDismiss      = { viewModel.dismissPairingDialog() },
            onVerifyPin    = { pin -> viewModel.submitPairingPin(pin) }
        )
    }
}

private fun buildVoiceIntent(): Intent =
    Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        putExtra(RecognizerIntent.EXTRA_PROMPT, "What would you like to search?")
        putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
    }

// ─── Header ──────────────────────────────────────────────────────────────────

@Composable
fun HeaderSection(
    activeDevice: TvDevice?,
    connectionStatus: String,
    pairingRequired: Boolean,
    onPowerClick: () -> Unit,
    onKeyboardClick: () -> Unit,
    onVoiceClick: () -> Unit,
    onPairClick: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = CardBg
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = activeDevice?.name ?: "Select a TV to connect",
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(
                                when {
                                    activeDevice?.isConnected == true && !pairingRequired -> Color(0xFF4CAF50)
                                    pairingRequired -> Color(0xFFFF9800)
                                    else -> Color(0xFFFF5252)
                                }
                            )
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(text = connectionStatus, color = Color.LightGray, fontSize = 12.sp)
                }
                if (pairingRequired) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Tap here to enter TV PIN code",
                        color = Color(0xFF80D8FF),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.clickable { onPairClick() }
                    )
                }
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                // Voice button
                VoiceButton(onClick = onVoiceClick)
                Spacer(modifier = Modifier.width(6.dp))
                // Keyboard button
                IconButton(onClick = onKeyboardClick) {
                    Icon(
                        imageVector = Icons.Default.Keyboard,
                        contentDescription = "Send Text",
                        tint = Color(0xFF4FC3F7)
                    )
                }
                // Power button
                IconButton(
                    onClick = onPowerClick,
                    modifier = Modifier
                        .clip(CircleShape)
                        .background(Color(0xFFE53935))
                ) {
                    Icon(
                        imageVector = Icons.Default.PowerSettingsNew,
                        contentDescription = "Power",
                        tint = Color.White
                    )
                }
            }
        }
    }
}

@Composable
private fun VoiceButton(onClick: () -> Unit) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(if (isPressed) 0.88f else 1f, tween(100), label = "voiceScale")
    val bgColor by animateColorAsState(
        if (isPressed) Color(0xFF1565C0) else Color(0xFF1976D2), tween(100), label = "voiceBg"
    )

    Box(
        modifier = Modifier
            .size(40.dp)
            .scale(scale)
            .shadow(6.dp, CircleShape, spotColor = Color(0xFF1976D2).copy(alpha = 0.5f))
            .clip(CircleShape)
            .background(bgColor)
            .clickable(interactionSource = interactionSource, indication = null) { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = Icons.Default.Mic,
            contentDescription = "Voice Search",
            tint = Color.White,
            modifier = Modifier.size(20.dp)
        )
    }
}

// ─── D-Pad ───────────────────────────────────────────────────────────────────

private enum class DPadSegment { UP, DOWN, LEFT, RIGHT }

@Composable
fun DPadCluster(onKeyPress: (RemoteKey) -> Unit) {
    var pressedSegment by remember { mutableStateOf<DPadSegment?>(null) }
    val dpadSize = 240.dp
    val okRadius = 38.dp

    Box(modifier = Modifier.size(dpadSize), contentAlignment = Alignment.Center) {
        androidx.compose.foundation.Canvas(
            modifier = Modifier
                .size(dpadSize)
                .pointerInput(Unit) {
                    detectTapGestures(
                        onPress = { offset ->
                            val cx = size.width / 2f
                            val cy = size.height / 2f
                            val dx = offset.x - cx
                            val dy = offset.y - cy
                            val dist = sqrt(dx * dx + dy * dy)
                            val outerR = size.width / 2f
                            val innerR = okRadius.toPx() + 4.dp.toPx()

                            if (dist in innerR..outerR) {
                                val angleDeg = Math.toDegrees(atan2(dy.toDouble(), dx.toDouble())).toFloat()
                                pressedSegment = when {
                                    angleDeg in -135f..-45f -> DPadSegment.UP
                                    angleDeg in 45f..135f   -> DPadSegment.DOWN
                                    angleDeg < -135f || angleDeg > 135f -> DPadSegment.LEFT
                                    else -> DPadSegment.RIGHT
                                }
                            }

                            val consumed = tryAwaitRelease()
                            val seg = pressedSegment
                            pressedSegment = null

                            if (consumed && seg != null) {
                                when (seg) {
                                    DPadSegment.UP    -> onKeyPress(RemoteKey.UP)
                                    DPadSegment.DOWN  -> onKeyPress(RemoteKey.DOWN)
                                    DPadSegment.LEFT  -> onKeyPress(RemoteKey.LEFT)
                                    DPadSegment.RIGHT -> onKeyPress(RemoteKey.RIGHT)
                                }
                            }
                        }
                    )
                }
        ) {
            val cx = size.width / 2f
            val cy = size.height / 2f
            val outerR = size.width / 2f
            val innerR = okRadius.toPx() + 4.dp.toPx()

            drawDPadWedge(cx, cy, outerR, innerR, -135f, 90f, pressedSegment == DPadSegment.UP)
            drawDPadWedge(cx, cy, outerR, innerR,   45f, 90f, pressedSegment == DPadSegment.DOWN)
            drawDPadWedge(cx, cy, outerR, innerR,  135f, 90f, pressedSegment == DPadSegment.LEFT)
            drawDPadWedge(cx, cy, outerR, innerR,  -45f, 90f, pressedSegment == DPadSegment.RIGHT)

            drawCircle(color = DPadBorder, radius = outerR - 1.dp.toPx(), style = Stroke(width = 2.dp.toPx()))
            drawCircle(color = DPadBorder, radius = innerR, style = Stroke(width = 1.5f.dp.toPx()))
        }

        DPadArrow(Icons.Default.KeyboardArrowUp,                    "Up",    Modifier.align(Alignment.TopCenter).padding(top = 12.dp),    pressedSegment == DPadSegment.UP)
        DPadArrow(Icons.Default.KeyboardArrowDown,                  "Down",  Modifier.align(Alignment.BottomCenter).padding(bottom = 12.dp), pressedSegment == DPadSegment.DOWN)
        DPadArrow(Icons.AutoMirrored.Filled.KeyboardArrowLeft,      "Left",  Modifier.align(Alignment.CenterStart).padding(start = 12.dp),  pressedSegment == DPadSegment.LEFT)
        DPadArrow(Icons.AutoMirrored.Filled.KeyboardArrowRight,     "Right", Modifier.align(Alignment.CenterEnd).padding(end = 12.dp),      pressedSegment == DPadSegment.RIGHT)

        OkButton(onClick = { onKeyPress(RemoteKey.ENTER_OK) })
    }
}

private fun DrawScope.drawDPadWedge(cx: Float, cy: Float, outerR: Float, innerR: Float, startAngleDeg: Float, sweepDeg: Float, pressed: Boolean) {
    val color = if (pressed) DPadOrange else DPadDark
    val path = Path().apply {
        val startRad = startAngleDeg * PI.toFloat() / 180f
        val endRad   = (startAngleDeg + sweepDeg) * PI.toFloat() / 180f
        moveTo(cx + innerR * cos(startRad), cy + innerR * sin(startRad))
        lineTo(cx + outerR * cos(startRad), cy + outerR * sin(startRad))
        arcTo(Rect(cx - outerR, cy - outerR, cx + outerR, cy + outerR), startAngleDeg, sweepDeg, false)
        lineTo(cx + innerR * cos(endRad), cy + innerR * sin(endRad))
        arcTo(Rect(cx - innerR, cy - innerR, cx + innerR, cy + innerR), startAngleDeg + sweepDeg, -sweepDeg, false)
        close()
    }
    drawPath(path = path, color = color)
}

@Composable
private fun DPadArrow(icon: ImageVector, desc: String, modifier: Modifier, pressed: Boolean) {
    Icon(imageVector = icon, contentDescription = desc, tint = if (pressed) Color.White else Color(0xFFBBBDD4), modifier = modifier.size(28.dp))
}

@Composable
private fun OkButton(onClick: () -> Unit) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    Box(
        modifier = Modifier
            .size(80.dp)
            .shadow(elevation = if (isPressed) 2.dp else 10.dp, shape = CircleShape, spotColor = DPadOrange.copy(alpha = 0.5f), ambientColor = DPadOrange.copy(alpha = 0.3f))
            .border(width = 3.dp, color = Color.White, shape = CircleShape)
            .clip(CircleShape)
            .background(if (isPressed) Color(0xFFBF4A1E) else DPadOrange)
            .clickable(interactionSource = interactionSource, indication = null) { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Text(text = "OK", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 18.sp, letterSpacing = 1.sp)
    }
}

// ─── Action Row ──────────────────────────────────────────────────────────────

@Composable
fun ActionRow(onKeyPress: (RemoteKey) -> Unit) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceAround) {
        RemoteActionButton(Icons.AutoMirrored.Filled.ArrowBack, "Back")     { onKeyPress(RemoteKey.BACK) }
        RemoteActionButton(Icons.Default.Home,                  "Home")     { onKeyPress(RemoteKey.HOME) }
        RemoteActionButton(Icons.Default.Settings,              "Settings") { onKeyPress(RemoteKey.SETTINGS) }
        RemoteActionButton(Icons.AutoMirrored.Filled.Input,     "Input")    { onKeyPress(RemoteKey.INPUT_SOURCE) }
    }
}

@Composable
fun RemoteActionButton(icon: ImageVector, label: String, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            modifier = Modifier
                .size(52.dp)
                .clip(CircleShape)
                .background(Color(0xFF282C40))
                .clickable { onClick() },
            contentAlignment = Alignment.Center
        ) {
            Icon(imageVector = icon, contentDescription = label, tint = Color.White)
        }
        Spacer(modifier = Modifier.height(4.dp))
        Text(text = label, color = Color.Gray, fontSize = 11.sp)
    }
}

// ─── Rockers Section ─────────────────────────────────────────────────────────

@Composable
fun RockersSection(
    isMuted: Boolean,
    volumeLevel: Int,
    onMuteToggle: () -> Unit,
    onKeyPress: (RemoteKey) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically
    ) {
        val volLabel = if (isMuted) "VOL 0" else "VOL $volumeLevel"
        // Volume rocker
        RockerControl(
            label = volLabel,
            onUp   = { onKeyPress(RemoteKey.VOLUME_UP) },
            onDown = { onKeyPress(RemoteKey.VOLUME_DOWN) },
            upIcon   = Icons.Default.Add,
            downIcon = Icons.Default.Remove,
            accentColor = if (isMuted) Color(0xFFFF8A80) else Color(0xFF4FC3F7)
        )

        // Mute button in centre
        MuteButton(isMuted = isMuted, onClick = onMuteToggle)

        // Channel rocker
        RockerControl(
            label = "CH",
            onUp   = { onKeyPress(RemoteKey.CHANNEL_UP) },
            onDown = { onKeyPress(RemoteKey.CHANNEL_DOWN) },
            upIcon   = Icons.Default.KeyboardArrowUp,
            downIcon = Icons.Default.KeyboardArrowDown,
            accentColor = Color(0xFFA5D6A7)
        )
    }
}

/**
 * Pill-shaped rocker control — top half = up action, bottom half = down action.
 * A thin divider line and a centred label sit between the two halves.
 */
@Composable
fun RockerControl(
    label: String,
    onUp: () -> Unit,
    onDown: () -> Unit,
    upIcon: ImageVector,
    downIcon: ImageVector,
    accentColor: Color
) {
    val shape = RoundedCornerShape(32.dp)
    Column(
        modifier = Modifier
            .width(72.dp)
            .shadow(6.dp, shape, spotColor = accentColor.copy(alpha = 0.2f))
            .clip(shape)
            .background(CardBg),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // UP half
        RockerHalf(icon = upIcon, desc = "$label Up", accentColor = accentColor, onClick = onUp)

        // Divider + label
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFF1A1C29))
                .padding(vertical = 4.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = label,
                color = accentColor,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 2.sp
            )
        }

        // DOWN half
        RockerHalf(icon = downIcon, desc = "$label Down", accentColor = accentColor, onClick = onDown)
    }
}

@Composable
private fun RockerHalf(
    icon: ImageVector,
    desc: String,
    accentColor: Color,
    onClick: () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val bg by animateColorAsState(
        if (isPressed) accentColor.copy(alpha = 0.25f) else Color.Transparent,
        tween(80), label = "rockerHalfBg"
    )

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(52.dp)
            .background(bg)
            .clickable(interactionSource = interactionSource, indication = null) { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = desc,
            tint = if (isPressed) accentColor else Color(0xFFCCCDD8),
            modifier = Modifier.size(26.dp)
        )
    }
}

@Composable
fun MuteButton(isMuted: Boolean, onClick: () -> Unit) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()

    val targetColor = if (isMuted) Color(0xFFD32F2F) else Color(0xFF2E7D32)
    val bgColor by animateColorAsState(
        if (isPressed) targetColor.copy(alpha = 0.7f) else targetColor,
        tween(200), label = "muteBg"
    )
    val scale by animateFloatAsState(
        if (isPressed) 0.88f else 1f, tween(100), label = "muteScale"
    )

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            modifier = Modifier
                .size(60.dp)
                .scale(scale)
                .shadow(
                    elevation = 8.dp,
                    shape = CircleShape,
                    spotColor = targetColor.copy(alpha = 0.5f),
                    ambientColor = targetColor.copy(alpha = 0.3f)
                )
                .clip(CircleShape)
                .background(bgColor)
                .clickable(interactionSource = interactionSource, indication = null) { onClick() },
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = if (isMuted) Icons.AutoMirrored.Filled.VolumeOff else Icons.AutoMirrored.Filled.VolumeUp,
                contentDescription = if (isMuted) "Unmute" else "Mute",
                tint = Color.White,
                modifier = Modifier.size(26.dp)
            )
        }
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = if (isMuted) "MUTED" else "SOUND",
            color = targetColor,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.sp
        )
    }
}

// ─── Media Controls ──────────────────────────────────────────────────────────

@Composable
fun MediaControlRow(onKeyPress: (RemoteKey) -> Unit) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceAround) {
        IconButton(onClick = { onKeyPress(RemoteKey.REWIND) }) {
            Icon(Icons.Default.FastRewind, contentDescription = "Rewind", tint = Color.White)
        }
        IconButton(onClick = { onKeyPress(RemoteKey.PLAY_PAUSE) }) {
            Icon(Icons.Default.PlayArrow, contentDescription = "Play/Pause", tint = Color(0xFF4CAF50), modifier = Modifier.size(32.dp))
        }
        IconButton(onClick = { onKeyPress(RemoteKey.STOP) }) {
            Icon(Icons.Default.Stop, contentDescription = "Stop", tint = Color(0xFFFF5252))
        }
        IconButton(onClick = { onKeyPress(RemoteKey.FAST_FORWARD) }) {
            Icon(Icons.Default.FastForward, contentDescription = "Fast Forward", tint = Color.White)
        }
    }
}
