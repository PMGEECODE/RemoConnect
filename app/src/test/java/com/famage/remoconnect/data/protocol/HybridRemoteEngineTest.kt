package com.famage.remoconnect.data.protocol

import com.famage.remoconnect.data.model.ConnectionType
import com.famage.remoconnect.data.model.RemoteKey
import com.famage.remoconnect.data.model.TvDevice
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class HybridRemoteEngineTest {

    private class FakeEngine(private val shouldSucceed: Boolean, private val requiresPairingVal: Boolean = false) : RemoteProtocolEngine {
        var connectCalled = false
        var lastDeviceConnected: TvDevice? = null

        override suspend fun connect(device: TvDevice): Boolean {
            connectCalled = true
            lastDeviceConnected = device
            return shouldSucceed
        }

        override suspend fun disconnect() {}
        override fun requiresPairing(): Boolean = requiresPairingVal
        override suspend fun verifyPin(pin: String): Boolean = true
        override suspend fun sendKey(key: RemoteKey): Boolean = true
        override suspend fun sendText(text: String): Boolean = true
        override suspend fun sendSwipe(deltaX: Float, deltaY: Float): Boolean = true
        override fun isConnected(): Boolean = shouldSucceed && !requiresPairingVal
    }

    private val nativeDevice = TvDevice(
        id = "192.168.74.3",
        name = "TCL TV",
        ipAddress = "192.168.74.3",
        port = 6466,
        connectionType = ConnectionType.ANDROID_TV_REMOTE_V2
    )

    private val adbDevice = TvDevice(
        id = "192.168.74.3",
        name = "TCL TV ADB",
        ipAddress = "192.168.74.3",
        port = 5555,
        connectionType = ConnectionType.ADB_WIFI
    )

    @Test
    fun testNativeTvDeviceEnforcesPrimaryProtocolWithoutAdbFallback() = runBlocking {
        val primary = FakeEngine(shouldSucceed = true, requiresPairingVal = true)
        val fallback = FakeEngine(shouldSucceed = true)
        val hybrid = HybridRemoteEngine(primaryEngine = primary, fallbackEngine = fallback)

        val result = hybrid.connect(nativeDevice)

        assertTrue("Native TV connection should invoke primary engine", result)
        assertTrue("Primary engine should be called", primary.connectCalled)
        assertFalse("Fallback ADB engine should NOT be called for port 6466 devices", fallback.connectCalled)
        assertFalse("Fallback should not be active", hybrid.isFallbackActive())
        assertTrue("Pairing requirement must be enforced", hybrid.requiresPairing())
        assertFalse("Device MUST NOT be marked isConnected before PIN verification", hybrid.isConnected())
    }

    @Test
    fun testAdbDeviceAllowsFallbackWhenPrimaryFails() = runBlocking {
        val primary = FakeEngine(shouldSucceed = false)
        val fallback = FakeEngine(shouldSucceed = true)
        val hybrid = HybridRemoteEngine(primaryEngine = primary, fallbackEngine = fallback)

        val result = hybrid.connect(adbDevice)

        assertTrue("ADB device connection should succeed via fallback engine", result)
        assertTrue("Primary engine should be attempted first", primary.connectCalled)
        assertTrue("Fallback engine should be called when primary fails", fallback.connectCalled)
        assertTrue("Fallback engine should be active", hybrid.isFallbackActive())
    }
}
