package com.famage.remoconnect

import com.famage.remoconnect.data.model.ConnectionType
import com.famage.remoconnect.data.model.RemoteKey
import com.famage.remoconnect.data.model.TvDevice
import com.famage.remoconnect.data.protocol.AndroidTvRemoteEngine
import com.famage.remoconnect.data.protocol.RemoteProtocolEngine
import com.famage.remoconnect.data.repository.TvDeviceRepository
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class TvDeviceRepositoryTest {

    private lateinit var repository: TvDeviceRepository

    @Before
    fun setUp() {
        repository = TvDeviceRepository(
            discoveryService = null,
            adbEngine = FakeConnectedEngine(),
            androidTvEngine = AndroidTvRemoteEngine(),
            irEngine = null
        )
    }

    @Test
    fun testAddManualTvDevice() {
        val device = repository.addManualTv("Living Room TCL", "192.168.1.120", 5555)
        assertEquals("Living Room TCL", device.name)
        assertEquals("192.168.1.120", device.ipAddress)
        assertTrue(repository.savedDevices.value.any { it.ipAddress == "192.168.1.120" })
    }

    @Test
    fun testConnectDeviceSuccess() = runBlocking {
        val target = TvDevice("tcl_43", "TCL 43 Inch", "127.0.0.1", 5555, ConnectionType.ADB_WIFI)
        val connected = repository.connectToDevice(target)
        assertTrue(connected)
        assertNotNull(repository.activeDevice.value)
        assertEquals("TCL 43 Inch", repository.activeDevice.value?.name)
    }

    @Test
    fun testSendRemoteKeyDispatched() = runBlocking {
        val target = TvDevice("tcl_43", "TCL 43 Inch", "127.0.0.1", 5555, ConnectionType.ADB_WIFI)
        repository.connectToDevice(target)
        val success = repository.sendRemoteKey(RemoteKey.VOLUME_UP)
        assertTrue(success)
    }

    private class FakeConnectedEngine : RemoteProtocolEngine {
        private var connected = false

        override suspend fun connect(device: TvDevice): Boolean {
            connected = true
            return true
        }

        override suspend fun disconnect() {
            connected = false
        }

        override suspend fun sendKey(key: RemoteKey): Boolean = connected
        override suspend fun sendText(text: String): Boolean = connected
        override suspend fun sendSwipe(deltaX: Float, deltaY: Float): Boolean = connected
        override fun isConnected(): Boolean = connected
    }
}
