package com.famage.remoconnect

import com.famage.remoconnect.data.model.ConnectionType
import com.famage.remoconnect.data.model.RemoteKey
import com.famage.remoconnect.data.model.TvDevice
import com.famage.remoconnect.data.protocol.AdbProtocolEngine
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class AdbProtocolEngineTest {

    private lateinit var engine: AdbProtocolEngine
    private lateinit var testDevice: TvDevice

    @Before
    fun setUp() {
        engine = AdbProtocolEngine()
        testDevice = TvDevice(
            id = "test_tcl_43",
            name = "TCL 43\" Test TV",
            ipAddress = "127.0.0.1",
            port = 5555,
            connectionType = ConnectionType.ADB_WIFI
        )
    }

    @Test
    fun testConnectFailsWhenAdbPortIsClosed() = runBlocking {
        val result = engine.connect(testDevice)
        assertFalse(result)
        assertFalse(engine.isConnected())
    }

    @Test
    fun testSendKeyFailsWithoutConnection() = runBlocking {
        engine.connect(testDevice)
        val powerResult = engine.sendKey(RemoteKey.POWER)
        assertFalse(powerResult)

        val okResult = engine.sendKey(RemoteKey.ENTER_OK)
        assertFalse(okResult)

        val netflixResult = engine.sendKey(RemoteKey.NETFLIX)
        assertFalse(netflixResult)
    }

    @Test
    fun testSendTextFailsWithoutConnection() = runBlocking {
        engine.connect(testDevice)
        val textResult = engine.sendText("TCL Android TV Search")
        assertFalse(textResult)
    }

    @Test
    fun testDisconnect() = runBlocking {
        engine.connect(testDevice)
        engine.disconnect()
        assertFalse(engine.isConnected())
    }
}
