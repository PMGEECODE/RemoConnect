package com.famage.remoconnect.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.famage.remoconnect.data.model.RemoteKey
import com.famage.remoconnect.ui.viewmodel.RemoteViewModel

@Composable
fun TouchpadScreen(
    viewModel: RemoteViewModel,
    modifier: Modifier = Modifier
) {
    var gestureFeedbackText by remember { mutableStateOf("Swipe or Tap to navigate") }
    val scrollStepThreshold = 96f

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFF12141D))
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "Trackpad Gesture Remote",
            color = Color.White,
            fontWeight = FontWeight.Bold,
            fontSize = 20.sp,
            modifier = Modifier.padding(vertical = 12.dp)
        )

        Text(
            text = gestureFeedbackText,
            color = Color(0xFF4FC3F7),
            fontSize = 14.sp,
            modifier = Modifier.padding(bottom = 16.dp)
        )

        // Large Gesture Canvas Box
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .clip(RoundedCornerShape(24.dp))
                .background(Color(0xFF1E2130))
                .border(2.dp, Color(0xFF3F4668), RoundedCornerShape(24.dp))
                .pointerInput(Unit) {
                    detectTapGestures(
                        onTap = {
                            gestureFeedbackText = "Tap -> Select (OK)"
                            viewModel.pressKey(RemoteKey.ENTER_OK)
                        }
                    )
                }
                .pointerInput(Unit) {
                    var accumulatedX = 0f
                    var accumulatedY = 0f
                    detectDragGestures(
                        onDragEnd = {
                            accumulatedX = 0f
                            accumulatedY = 0f
                            gestureFeedbackText = "Gesture completed"
                        },
                        onDrag = { change, dragAmount ->
                            change.consume()
                            accumulatedX += dragAmount.x
                            accumulatedY += dragAmount.y

                            val absX = kotlin.math.abs(accumulatedX)
                            val absY = kotlin.math.abs(accumulatedY)
                            if (absX < scrollStepThreshold && absY < scrollStepThreshold) return@detectDragGestures

                            if (absY >= absX) {
                                if (accumulatedY < 0f) {
                                    gestureFeedbackText = "Step Up"
                                    viewModel.pressKey(RemoteKey.UP)
                                    accumulatedY += scrollStepThreshold
                                } else {
                                    gestureFeedbackText = "Step Down"
                                    viewModel.pressKey(RemoteKey.DOWN)
                                    accumulatedY -= scrollStepThreshold
                                }
                                accumulatedX = 0f
                            } else {
                                if (accumulatedX < 0f) {
                                    gestureFeedbackText = "Step Left"
                                    viewModel.pressKey(RemoteKey.LEFT)
                                    accumulatedX += scrollStepThreshold
                                } else {
                                    gestureFeedbackText = "Step Right"
                                    viewModel.pressKey(RemoteKey.RIGHT)
                                    accumulatedX -= scrollStepThreshold
                                }
                                accumulatedY = 0f
                            }
                        }
                    )
                },
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(
                    imageVector = Icons.Default.TouchApp,
                    contentDescription = "Touch Surface",
                    tint = Color(0xFF5C6BC0),
                    modifier = Modifier.size(72.dp)
                )
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = "Swipe to Move • Tap to Select",
                    color = Color.Gray,
                    fontSize = 14.sp
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Quick Access Row
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            Button(
                onClick = { viewModel.pressKey(RemoteKey.BACK) },
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF282C40))
            ) {
                Text("Back")
            }
            Button(
                onClick = { viewModel.pressKey(RemoteKey.HOME) },
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF3F51B5))
            ) {
                Text("Home")
            }
        }
    }
}
