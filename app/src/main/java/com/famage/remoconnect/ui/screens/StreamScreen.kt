package com.famage.remoconnect.ui.screens

import android.view.ContextThemeWrapper
import android.widget.FrameLayout
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.mediarouter.app.MediaRouteButton
import com.famage.remoconnect.R
import com.famage.remoconnect.cast.CastPlaybackState
import com.famage.remoconnect.cast.CastStreamingController
import com.google.android.gms.cast.framework.CastButtonFactory

private val StreamBgTop = Color(0xFF10131B)
private val StreamBgMid = Color(0xFF171B27)
private val PanelBg = Color(0xFF25283A)
private val Accent = Color(0xFF4FC3F7)

@Composable
fun StreamScreen(
    controller: CastStreamingController,
    modifier: Modifier = Modifier
) {
    val state by controller.playbackState.collectAsState()
    var videoUrl by remember { mutableStateOf("") }
    var sliderPosition by remember(state.positionMs, state.durationMs) {
        mutableFloatStateOf(state.positionMs.toFloat())
    }

    val videoPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (uris.isNotEmpty()) controller.castLocalVideos(uris)
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(StreamBgTop, StreamBgMid, Color(0xFF0D0E15))
                )
            )
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        StreamHeader(state = state)

        Surface(
            color = PanelBg,
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = state.deviceName ?: "No Cast device selected",
                            color = Color.White,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 16.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = state.statusText,
                            color = Color(0xFFB5B8C8),
                            fontSize = 12.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    CastRouteButton(
                        enabled = state.isCastAvailable,
                        onSetupError = { controller.reportCastButtonError(it) }
                    )
                }

                if (!state.isCastAvailable) {
                    Text(
                        text = state.errorMessage ?: "Google Cast is unavailable on this device.",
                        color = Color(0xFFFFB4AB),
                        fontSize = 13.sp
                    )
                }
            }
        }

        Surface(
            color = PanelBg,
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = "Stream Video",
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp
                )

                OutlinedTextField(
                    value = videoUrl,
                    onValueChange = { videoUrl = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text("Video URL") },
                    leadingIcon = { Icon(Icons.Default.Link, contentDescription = null) },
                    supportingText = { Text("MP4, WebM, HLS, or DASH links") }
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Button(
                        onClick = { controller.castUrl(videoUrl) },
                        enabled = state.isCastAvailable,
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(containerColor = Accent)
                    ) {
                        Icon(Icons.Default.Tv, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Cast URL")
                    }

                    Button(
                        onClick = { videoPicker.launch(arrayOf("video/*")) },
                        enabled = state.isCastAvailable,
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF66BB6A))
                    ) {
                        Icon(Icons.Default.FolderOpen, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Phone Videos")
                    }
                }
            }
        }

        PlaybackPanel(
            state = state,
            sliderPosition = sliderPosition,
            onSliderChange = { sliderPosition = it },
            onSeek = { controller.seekTo(it.toLong()) },
            onToggle = { controller.togglePlayback() },
            onStop = { controller.stop() }
        )

        val errorMessage = state.errorMessage
        if (errorMessage != null && state.isCastAvailable) {
            Surface(
                color = Color(0xFF3A2528),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = errorMessage,
                        color = Color(0xFFFFDAD6),
                        fontSize = 13.sp,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(onClick = { controller.clearError() }) {
                        Text("Dismiss")
                    }
                }
            }
        }
    }
}

@Composable
private fun StreamHeader(state: CastPlaybackState) {
    Column {
        Text(
            text = "Stream",
            color = Color.White,
            fontWeight = FontWeight.Bold,
            fontSize = 24.sp
        )
        Text(
            text = if (state.isConnected) "Send video to ${state.deviceName ?: "your TV"}" else "Pick a Cast TV and start playback",
            color = Color(0xFFB5B8C8),
            fontSize = 13.sp
        )
    }
}

@Composable
private fun CastRouteButton(
    enabled: Boolean,
    onSetupError: (String?) -> Unit
) {
    var setupFailed by remember { mutableStateOf(false) }

    Box(
        modifier = Modifier
            .size(48.dp)
            .background(Color(0xFF30364A), CircleShape),
        contentAlignment = Alignment.Center
    ) {
        if (setupFailed || !enabled) {
            Icon(
                imageVector = Icons.Default.Tv,
                contentDescription = "Cast unavailable",
                tint = Color(0xFF9E9EB8)
            )
        } else {
            AndroidView(
                factory = { context ->
                    try {
                        val themedContext = ContextThemeWrapper(context, R.style.Theme_RemoConnect)
                        MediaRouteButton(themedContext).apply {
                            isEnabled = true
                            CastButtonFactory.setUpMediaRouteButton(themedContext, this)
                        }
                    } catch (e: Exception) {
                        setupFailed = true
                        onSetupError(e.localizedMessage ?: e.message)
                        FrameLayout(context)
                    }
                },
                update = { view ->
                    if (view is MediaRouteButton) {
                        view.isEnabled = enabled
                    }
                },
                modifier = Modifier.size(42.dp)
            )
        }
    }
}

@Composable
private fun PlaybackPanel(
    state: CastPlaybackState,
    sliderPosition: Float,
    onSliderChange: (Float) -> Unit,
    onSeek: (Float) -> Unit,
    onToggle: () -> Unit,
    onStop: () -> Unit
) {
    val hasMedia = state.title.isNotBlank() || state.contentUrl.isNotBlank()
    val canControlPlayback = state.isConnected && hasMedia

    Surface(
        color = PanelBg,
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (hasMedia) {
                Text(
                    text = buildPlaybackTitle(state),
                    color = Color.White,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 16.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            } else {
                Text(
                    text = "Ready for video",
                    color = Color.White,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 16.sp
                )
                Text(
                    text = "Choose a Cast device, then send a URL or phone video.",
                    color = Color(0xFFB5B8C8),
                    fontSize = 13.sp
                )
            }

            if (hasMedia && state.durationMs > 0L) {
                Slider(
                    value = sliderPosition.coerceIn(0f, state.durationMs.toFloat()),
                    onValueChange = onSliderChange,
                    onValueChangeFinished = { onSeek(sliderPosition) },
                    valueRange = 0f..state.durationMs.toFloat()
                )
            } else if (hasMedia) {
                LinearProgressIndicator(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(4.dp),
                    color = Accent,
                    trackColor = Color(0xFF3A3F55)
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = formatTime(state.positionMs),
                    color = Color(0xFFB5B8C8),
                    fontSize = 12.sp
                )
                Text(
                    text = formatTime(state.durationMs),
                    color = Color(0xFFB5B8C8),
                    fontSize = 12.sp
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(
                    onClick = onToggle,
                    enabled = canControlPlayback,
                    modifier = Modifier
                        .size(56.dp)
                        .background(Accent, CircleShape)
                ) {
                    Icon(
                        imageVector = if (state.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription = if (state.isPlaying) "Pause" else "Play",
                        tint = Color.White,
                        modifier = Modifier.size(30.dp)
                    )
                }

                Spacer(modifier = Modifier.width(16.dp))

                IconButton(
                    onClick = onStop,
                    enabled = canControlPlayback,
                    modifier = Modifier
                        .size(48.dp)
                        .background(Color(0xFF3A3F55), CircleShape)
                ) {
                    Icon(
                        imageVector = Icons.Default.Stop,
                        contentDescription = "Stop",
                        tint = Color.White
                    )
                }
            }

            state.localStreamUrl?.let { url ->
                Text(
                    text = url,
                    color = Color(0xFF9E9EB8),
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }
}

private fun formatTime(ms: Long): String {
    if (ms <= 0L) return "0:00"
    val totalSeconds = ms / 1000L
    val seconds = totalSeconds % 60L
    val minutes = (totalSeconds / 60L) % 60L
    val hours = totalSeconds / 3600L
    return if (hours > 0L) {
        "%d:%02d:%02d".format(hours, minutes, seconds)
    } else {
        "%d:%02d".format(minutes, seconds)
    }
}

private fun buildPlaybackTitle(state: CastPlaybackState): String {
    val title = state.title.ifBlank { "Loading video" }
    return if (state.queueSize > 1) {
        "$title (${state.queueIndex}/${state.queueSize})"
    } else {
        title
    }
}
