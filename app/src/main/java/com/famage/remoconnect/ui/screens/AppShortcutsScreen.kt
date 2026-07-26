package com.famage.remoconnect.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.svg.SvgDecoder
import com.famage.remoconnect.data.model.RemoteKey
import com.famage.remoconnect.ui.viewmodel.RemoteViewModel

data class AppShortcutItem(
    val key: RemoteKey,
    val name: String,
    val svgAsset: String,
    val gradientStart: Color,
    val gradientEnd: Color,
    val textColor: Color = Color.White
)

@Composable
fun AppShortcutsScreen(
    viewModel: RemoteViewModel,
    modifier: Modifier = Modifier
) {
    val shortcuts = listOf(
        AppShortcutItem(
            key = RemoteKey.NETFLIX,
            name = "Netflix",
            svgAsset = "netflix.svg",
            gradientStart = Color(0xFFE50914),
            gradientEnd = Color(0xFF8B0000)
        ),
        AppShortcutItem(
            key = RemoteKey.YOUTUBE,
            name = "YouTube",
            svgAsset = "youtube.svg",
            gradientStart = Color(0xFF212121),
            gradientEnd = Color(0xFF000000)
        ),
        AppShortcutItem(
            key = RemoteKey.PRIME_VIDEO,
            name = "Prime Video",
            svgAsset = "prime_video.svg",
            gradientStart = Color(0xFF00A8E1),
            gradientEnd = Color(0xFF00547A)
        ),
        AppShortcutItem(
            key = RemoteKey.DISNEY_PLUS,
            name = "Disney+",
            svgAsset = "disney_plus.svg",
            gradientStart = Color(0xFF113CCF),
            gradientEnd = Color(0xFF0A1F6E)
        ),
        AppShortcutItem(
            key = RemoteKey.SPOTIFY,
            name = "Spotify",
            svgAsset = "spotify.svg",
            gradientStart = Color(0xFF1DB954),
            gradientEnd = Color(0xFF0D6E31)
        ),
        AppShortcutItem(
            key = RemoteKey.GOOGLE_PLAY,
            name = "Play Store",
            svgAsset = "google_play.svg",
            gradientStart = Color(0xFF1C1C1E),
            gradientEnd = Color(0xFF000000)
        )
    )

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFF12141D))
            .padding(horizontal = 16.dp)
    ) {
        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = "App Launcher",
            color = Color.White,
            fontWeight = FontWeight.Bold,
            fontSize = 22.sp
        )

        Text(
            text = "Launch streaming apps on your TV",
            color = Color(0xFF9E9EB8),
            fontSize = 13.sp,
            modifier = Modifier.padding(top = 4.dp, bottom = 20.dp)
        )

        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            modifier = Modifier.fillMaxSize()
        ) {
            items(shortcuts) { item ->
                AppShortcutCard(item = item, onClick = { viewModel.pressKey(item.key) })
            }
        }
    }
}

@Composable
private fun AppShortcutCard(
    item: AppShortcutItem,
    onClick: () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val context = LocalContext.current

    Box(
        modifier = Modifier
            .height(120.dp)
            .shadow(
                elevation = 8.dp,
                shape = RoundedCornerShape(20.dp),
                ambientColor = item.gradientStart.copy(alpha = 0.4f),
                spotColor = item.gradientStart.copy(alpha = 0.4f)
            )
            .clip(RoundedCornerShape(20.dp))
            .background(
                brush = Brush.linearGradient(
                    colors = listOf(item.gradientStart, item.gradientEnd)
                )
            )
            .clickable(
                interactionSource = interactionSource,
                indication = ripple(color = Color.White.copy(alpha = 0.3f))
            ) { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
        ) {
            AsyncImage(
                model = ImageRequest.Builder(context)
                    .data("file:///android_asset/${item.svgAsset}")
                    .decoderFactory(SvgDecoder.Factory())
                    .build(),
                contentDescription = item.name,
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp)
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = item.name,
                color = item.textColor,
                fontWeight = FontWeight.SemiBold,
                fontSize = 13.sp,
                maxLines = 1
            )
        }
    }
}
