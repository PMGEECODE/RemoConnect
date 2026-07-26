package com.famage.remoconnect.data.model

data class TvDevice(
    val id: String,
    val name: String,
    val ipAddress: String,
    val port: Int = 6466,
    val connectionType: ConnectionType = ConnectionType.ANDROID_TV_REMOTE_V2,
    val isConnected: Boolean = false,
    val isPaired: Boolean = false,
    val lastSeenTimestamp: Long = System.currentTimeMillis()
)
