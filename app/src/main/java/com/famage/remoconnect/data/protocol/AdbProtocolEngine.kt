package com.famage.remoconnect.data.protocol

import android.util.Log
import com.famage.remoconnect.data.model.RemoteKey
import com.famage.remoconnect.data.model.TvDevice
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket

class AdbProtocolEngine(
    private val logger: ((String) -> Unit)? = null
) : RemoteProtocolEngine {
    private var activeDevice: TvDevice? = null
    private var connectedState: Boolean = false

    private fun log(msg: String) {
        try { Log.d("RemoConnect-ADB", msg) } catch (_: Throwable) {}
        logger?.invoke(msg)
    }

    override suspend fun connect(device: TvDevice): Boolean = withContext(Dispatchers.IO) {
        log("[ADB-CONNECT] Connecting to ${device.name} (${device.ipAddress}:${device.port}) via ADB Wi-Fi")
        return@withContext try {
            val socket = Socket()
            socket.connect(InetSocketAddress(device.ipAddress, device.port), 2500)
            socket.close()
            activeDevice = device
            connectedState = true
            log("[ADB-CONNECTED] ADB socket reachability successful for ${device.ipAddress}:${device.port}")
            true
        } catch (e: Exception) {
            log("[ADB-CONNECT-WARN] ADB socket test failed for ${device.ipAddress}:${device.port}: ${e.localizedMessage}")
            activeDevice = null
            connectedState = false
            false
        }
    }

    override suspend fun disconnect() {
        withContext(Dispatchers.IO) {
            log("[ADB-DISCONNECT] Disconnecting ADB session for ${activeDevice?.name ?: "device"}")
            activeDevice = null
            connectedState = false
        }
    }

    override suspend fun sendKey(key: RemoteKey): Boolean = withContext(Dispatchers.IO) {
        val device = activeDevice ?: return@withContext false
        return@withContext try {
            val command = if (key.appPackage != null) {
                "monkey -p ${key.appPackage} -c android.intent.category.LAUNCHER 1\n"
            } else {
                "input keyevent ${key.androidKeycode}\n"
            }
            log("[ADB-KEY] Transmitting ADB command '${command.trim()}' to ${device.ipAddress}:${device.port}")
            sendAdbRawCommand(device.ipAddress, device.port, command)
        } catch (e: Exception) {
            log("[ADB-KEY-ERROR] ${e.localizedMessage}")
            false
        }
    }

    override suspend fun sendText(text: String): Boolean = withContext(Dispatchers.IO) {
        val device = activeDevice ?: return@withContext false
        return@withContext try {
            val sanitized = text.replace(" ", "%s")
            val command = "input text $sanitized\n"
            log("[ADB-TEXT] Sending text input '$text' to ${device.ipAddress}:${device.port}")
            sendAdbRawCommand(device.ipAddress, device.port, command)
        } catch (e: Exception) {
            log("[ADB-TEXT-ERROR] ${e.localizedMessage}")
            false
        }
    }

    override suspend fun sendSwipe(deltaX: Float, deltaY: Float): Boolean = withContext(Dispatchers.IO) {
        val device = activeDevice ?: return@withContext false
        return@withContext try {
            val key = when {
                deltaY < -40 -> RemoteKey.UP
                deltaY > 40 -> RemoteKey.DOWN
                deltaX < -40 -> RemoteKey.LEFT
                deltaX > 40 -> RemoteKey.RIGHT
                else -> RemoteKey.ENTER_OK
            }
            sendKey(key)
        } catch (e: Exception) {
            false
        }
    }

    override fun isConnected(): Boolean = connectedState

    private fun sendAdbRawCommand(ip: String, port: Int, commandStr: String): Boolean {
        return try {
            val socket = Socket()
            socket.connect(InetSocketAddress(ip, port), 1500)
            val outputStream: OutputStream = socket.getOutputStream()
            outputStream.write(commandStr.toByteArray(Charsets.UTF_8))
            outputStream.flush()
            outputStream.close()
            socket.close()
            true
        } catch (e: Exception) {
            false
        }
    }
}
