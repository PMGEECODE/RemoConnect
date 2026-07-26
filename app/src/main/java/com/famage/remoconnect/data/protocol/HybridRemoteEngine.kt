package com.famage.remoconnect.data.protocol

import com.famage.remoconnect.data.model.ConnectionType
import com.famage.remoconnect.data.model.RemoteKey
import com.famage.remoconnect.data.model.TvDevice
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class HybridRemoteEngine(
    private val primaryEngine: RemoteProtocolEngine = AndroidTvRemoteEngine(),
    private val fallbackEngine: RemoteProtocolEngine = AdbProtocolEngine(),
    private val logger: ((String) -> Unit)? = null
) : RemoteProtocolEngine {

    private var activeEngine: RemoteProtocolEngine = primaryEngine
    private var isUsingFallback: Boolean = false

    private fun log(msg: String) {
        logger?.invoke(msg)
    }

    override suspend fun connect(device: TvDevice): Boolean = withContext(Dispatchers.IO) {
        log("[HYBRID-CONNECT] Connecting to ${device.name} (${device.ipAddress}:${device.port})")

        val isNativeTvDevice = device.port == 6466 || device.port == 6467 || device.connectionType == ConnectionType.ANDROID_TV_REMOTE_V2
        if (isNativeTvDevice) {
            log("[HYBRID-PRIMARY] Using Native Android TV Remote v2 TLS protocol on ports 6466/6467...")
            activeEngine = primaryEngine
            isUsingFallback = false
            return@withContext primaryEngine.connect(device)
        }

        // For explicitly configured ADB devices (port 5555), attempt primary first, then fallback
        log("[HYBRID-ADB] Device configured for ADB Wi-Fi on port ${device.port}...")
        activeEngine = primaryEngine
        val primarySuccess = try {
            primaryEngine.connect(device)
        } catch (e: Exception) {
            false
        }

        if (primarySuccess) return@withContext true

        log("[HYBRID-FALLBACK] Primary TLS connection failed. Falling back to ADB Wi-Fi engine...")
        activeEngine = fallbackEngine
        isUsingFallback = true
        return@withContext fallbackEngine.connect(device)
    }

    override suspend fun disconnect() {
        log("[HYBRID-DISCONNECT] Disconnecting active engine (${if (isUsingFallback) "ADB Fallback" else "Native TLS"})")
        activeEngine.disconnect()
    }

    override fun requiresPairing(): Boolean {
        return activeEngine.requiresPairing()
    }

    override suspend fun verifyPin(pin: String): Boolean {
        log("[HYBRID-VERIFY-PIN] Delegating PIN verification to active engine...")
        return activeEngine.verifyPin(pin)
    }

    override suspend fun sendKey(key: RemoteKey): Boolean {
        return activeEngine.sendKey(key)
    }

    override suspend fun sendText(text: String): Boolean {
        return activeEngine.sendText(text)
    }

    override suspend fun sendSwipe(deltaX: Float, deltaY: Float): Boolean {
        return activeEngine.sendSwipe(deltaX, deltaY)
    }

    override fun isConnected(): Boolean {
        return activeEngine.isConnected()
    }

    fun isFallbackActive(): Boolean = isUsingFallback
}
