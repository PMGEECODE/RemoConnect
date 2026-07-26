package com.famage.remoconnect.data.protocol

import com.famage.remoconnect.data.model.RemoteKey
import com.famage.remoconnect.data.model.TvDevice

interface RemoteProtocolEngine {
    suspend fun connect(device: TvDevice): Boolean
    suspend fun disconnect()
    suspend fun sendKey(key: RemoteKey): Boolean
    suspend fun sendText(text: String): Boolean
    suspend fun sendSwipe(deltaX: Float, deltaY: Float): Boolean
    fun isConnected(): Boolean
    fun requiresPairing(): Boolean = false
    suspend fun verifyPin(pin: String): Boolean = true
}
