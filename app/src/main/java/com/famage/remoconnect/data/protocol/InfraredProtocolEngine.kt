package com.famage.remoconnect.data.protocol

import android.content.Context
import android.hardware.ConsumerIrManager
import com.famage.remoconnect.data.model.RemoteKey
import com.famage.remoconnect.data.model.TvDevice
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class InfraredProtocolEngine(private val context: Context) : RemoteProtocolEngine {
    private var irManager: ConsumerIrManager? = null
    private var connected: Boolean = false

    init {
        irManager = context.getSystemService(Context.CONSUMER_IR_SERVICE) as? ConsumerIrManager
    }

    override suspend fun connect(device: TvDevice): Boolean = withContext(Dispatchers.IO) {
        connected = irManager?.hasIrEmitter() == true
        return@withContext connected
    }

    override suspend fun disconnect() {
        withContext(Dispatchers.IO) {
            connected = false
        }
    }

    override suspend fun sendKey(key: RemoteKey): Boolean = withContext(Dispatchers.IO) {
        val manager = irManager ?: return@withContext false
        if (!manager.hasIrEmitter()) return@withContext false

        val pattern = getTclIrPattern(key)
        return@withContext try {
            manager.transmit(38000, pattern)
            true
        } catch (e: Exception) {
            false
        }
    }

    override suspend fun sendText(text: String): Boolean = false

    override suspend fun sendSwipe(deltaX: Float, deltaY: Float): Boolean = false

    override fun isConnected(): Boolean = connected

    private fun getTclIrPattern(key: RemoteKey): IntArray {
        // Standard NEC / TCL TV IR signal timing sequence (38kHz)
        val header = intArrayOf(9000, 4500)
        val bit0 = intArrayOf(560, 560)
        val bit1 = intArrayOf(560, 1690)
        val stop = intArrayOf(560, 40000)

        val code = when (key) {
            RemoteKey.POWER -> 0x00FF02FD
            RemoteKey.UP -> 0x00FF906F
            RemoteKey.DOWN -> 0x00FFA857
            RemoteKey.LEFT -> 0x00FFE01F
            RemoteKey.RIGHT -> 0x00FF9867
            RemoteKey.ENTER_OK -> 0x00FFA25D
            RemoteKey.VOLUME_UP -> 0x00FF629D
            RemoteKey.VOLUME_DOWN -> 0x00FFA857
            RemoteKey.MUTE -> 0x00FF22DD
            RemoteKey.HOME -> 0x00FFC23D
            RemoteKey.BACK -> 0x00FF22DD
            else -> 0x00FF02FD
        }

        val patternList = mutableListOf<Int>()
        patternList.addAll(header.toList())
        for (i in 31 downTo 0) {
            val bit = (code shr i) and 1
            if (bit == 1) {
                patternList.addAll(bit1.toList())
            } else {
                patternList.addAll(bit0.toList())
            }
        }
        patternList.addAll(stop.toList())
        return patternList.toIntArray()
    }
}
