package com.famage.remoconnect.data.protocol

import android.util.Log
import android.content.Context
import com.famage.remoconnect.data.model.RemoteKey
import com.famage.remoconnect.data.model.TvDevice
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.math.BigInteger
import java.net.InetSocketAddress
import java.net.Socket
import java.security.MessageDigest
import java.security.cert.X509Certificate
import javax.net.ssl.SSLSocket

class AndroidTvRemoteEngine(
    context: Context? = null,
    private val logger: ((String) -> Unit)? = null
) : RemoteProtocolEngine {
    private var activeDevice: TvDevice? = null
    private var connectedState: Boolean = false
    private var pairingNeeded: Boolean = false
    private var socket: Socket? = null
    private var outputStream: OutputStream? = null
    private var pairingSocket: SSLSocket? = null
    private var remoteSocket: SSLSocket? = null
    private var remoteReaderJob: Job? = null
    private var imeCounter: Int = 0
    private var imeFieldCounter: Int = 0
    private val remoteSocketLock = Any()
    private val remoteScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val sslKeyManager by lazy { TvSslKeyManager(context) }

    private fun log(msg: String) {
        try { Log.d("RemoConnect-Engine", msg) } catch (_: Throwable) {}
        logger?.invoke(msg)
    }

    override suspend fun connect(device: TvDevice): Boolean = withContext(Dispatchers.IO) {
        activeDevice = device
        connectedState = false
        pairingNeeded = false
        log("[CONNECT] Initiating connection to ${device.name} (${device.ipAddress}:${device.port}) via Android TV Remote v2 mTLS")

        return@withContext try {
            val isRemoteProtocolPort = device.port == 6466 || device.port == 6467
            if (isRemoteProtocolPort) {
                if (device.isPaired) {
                    log("[mTLS-RESUME] ${device.name} is already paired. Reusing stored certificate for ${device.ipAddress}:6466...")
                    if (openRemoteControlSession(device.ipAddress)) {
                        pairingNeeded = false
                        connectedState = true
                        log("[mTLS-RESUME-SUCCESS] Reconnected to ${device.name} without PIN pairing.")
                        return@withContext true
                    }
                    log("[mTLS-RESUME-FAILED] Stored pairing could not open the remote channel. Keeping paired identity and reporting the TV offline instead of requesting a new PIN.")
                    pairingNeeded = false
                    connectedState = false
                    return@withContext false
                }

                log("[mTLS-INIT] Initialized Mutual TLS (mTLS) with client certificate CN=RemoConnect")
                val pairingInitiated = sendPairingRequestToTv(device.ipAddress)
                if (pairingInitiated) {
                    pairingNeeded = true
                    connectedState = false
                    log("[mTLS-PIN-PROMPT] mTLS Handshake & PairingRequest accepted by ${device.ipAddress}:6467! TV PIN prompt displayed on TV screen. Enter PIN...")
                    true
                } else {
                    log("[mTLS-FAILED] Could not complete mTLS handshake with TV pairing service at ${device.ipAddress}:6467")
                    false
                }
            } else {
                log("[CONNECT] Checking socket reachability at ${device.ipAddress}:${device.port}")
                val testSocket = Socket()
                testSocket.connect(InetSocketAddress(device.ipAddress, device.port), 2500)
                testSocket.close()
                log("[CONNECT] Reachability successful for ${device.ipAddress}:${device.port}")
                connectedState = true
                pairingNeeded = false
                true
            }
        } catch (e: Exception) {
            log("[CONNECT-ERROR] Connection failed to ${device.ipAddress}: ${e.localizedMessage ?: e.message}")
            connectedState = false
            pairingNeeded = false
            false
        }
    }

    override suspend fun disconnect() {
        withContext(Dispatchers.IO) {
            log("[DISCONNECT] Closing sockets for ${activeDevice?.name ?: "active device"}")
            try {
                stopRemoteReader()
                outputStream?.close()
                socket?.close()
                closePairingSocket()
                closeRemoteSocket()
            } catch (e: Exception) {
                log("[DISCONNECT-ERROR] ${e.localizedMessage}")
            } finally {
                outputStream = null
                socket = null
                activeDevice = null
                connectedState = false
                pairingNeeded = false
            }
        }
    }

    override fun requiresPairing(): Boolean = pairingNeeded

    override suspend fun verifyPin(pin: String): Boolean = withContext(Dispatchers.IO) {
        val device = activeDevice ?: run {
            log("[PAIRING-VERIFY-ERROR] No active device selected for PIN verification")
            return@withContext false
        }
        val normalizedPin = pin.trim().uppercase()
        log("[mTLS-VERIFY] Verifying 6-character pairing code with ${device.ipAddress}:6467 over mTLS...")

        return@withContext try {
            if (!normalizedPin.matches(Regex("[0-9A-F]{6}"))) {
                log("[mTLS-VERIFY-ERROR] Pairing code must be exactly 6 hexadecimal characters.")
                return@withContext false
            }

            val activePairingSocket = pairingSocket ?: run {
                log("[mTLS-VERIFY-ERROR] No active pairing socket. Start pairing again before entering the PIN.")
                return@withContext false
            }

            val clientCert = sslKeyManager.getClientCertificate() ?: run {
                log("[mTLS-VERIFY-ERROR] Client certificate is unavailable.")
                return@withContext false
            }
            val serverCert = activePairingSocket.session.peerCertificates.firstOrNull() as? X509Certificate ?: run {
                log("[mTLS-VERIFY-ERROR] TV server certificate is unavailable.")
                return@withContext false
            }

            val secret = buildPairingSecret(clientCert, serverCert, normalizedPin)
            sendPoloMessage(activePairingSocket, buildSecretMessage(secret))

            val response = readPoloMessage(activePairingSocket)
            if (response.status != POLO_STATUS_OK || !response.hasSecretAck) {
                log("[mTLS-VERIFY-ERROR] TV rejected pairing secret (status=${response.status}).")
                return@withContext false
            }

            closePairingSocket()

            log("[mTLS-SUCCESS] PIN secret verified with ${device.ipAddress}:6467. TV Remote pairing completed.")
            pairingNeeded = false
            connectedState = openRemoteControlSession(device.ipAddress)
            connectedState
        } catch (e: Exception) {
            log("[mTLS-VERIFY-ERROR] Verification failed with ${device.ipAddress}:6467: ${e.localizedMessage ?: e.message}")
            pairingNeeded = true
            connectedState = false
            false
        }
    }

    override suspend fun sendKey(key: RemoteKey): Boolean = withContext(Dispatchers.IO) {
        val device = activeDevice
        if (device == null || !connectedState) {
            log("[KEYEVENT-REJECTED] Cannot send key '${key.name}' - TV is not connected or PIN unverified.")
            return@withContext false
        }

        log("[KEYEVENT] Sending key '${key.name}' (Android Keycode: ${key.androidKeycode}) to ${device.ipAddress}:${device.port}")
        return@withContext try {
            val isRemotePort = device.port == 6466 || device.port == 6467
            if (key.appPackage != null || key.appLink != null) {
                if (isRemotePort) {
                    val launched = sendRemoteV2AppLaunch(device.ipAddress, key)
                    if (key == RemoteKey.SETTINGS) {
                        Thread.sleep(180L)
                        sendRemoteV2SystemKeyFallback(
                            ip = device.ipAddress,
                            label = key.name,
                            keycodes = listOf(KEYCODE_SETTINGS, KEYCODE_MENU, KEYCODE_TV_CONTENTS_MENU)
                        )
                        sendAdbKeyFallback(device.ipAddress, listOf(KEYCODE_SETTINGS, KEYCODE_MENU)) || launched
                    } else {
                        launched
                    }
                } else {
                    log("[APP-LAUNCH] Launching package ${key.appPackage} via ADB shell")
                    sendAdbCommand(device.ipAddress, device.port, "monkey -p ${key.appPackage} -c android.intent.category.LAUNCHER 1\n")
                }
            } else {
                val keycode = key.androidKeycode
                if (isRemotePort) {
                    when (key) {
                        RemoteKey.INPUT_SOURCE -> sendRemoteV2InputSource(device.ipAddress)
                        RemoteKey.CHANNEL_UP -> {
                            val remoteSent = sendRemoteV2SystemKeyFallback(
                                ip = device.ipAddress,
                                label = key.name,
                                keycodes = listOf(KEYCODE_CHANNEL_UP, KEYCODE_PAGE_UP, KEYCODE_MEDIA_NEXT)
                            )
                            sendAdbKeyFallback(device.ipAddress, listOf(KEYCODE_CHANNEL_UP, KEYCODE_PAGE_UP)) || remoteSent
                        }
                        RemoteKey.CHANNEL_DOWN -> {
                            val remoteSent = sendRemoteV2SystemKeyFallback(
                                ip = device.ipAddress,
                                label = key.name,
                                keycodes = listOf(KEYCODE_CHANNEL_DOWN, KEYCODE_PAGE_DOWN, KEYCODE_MEDIA_PREVIOUS)
                            )
                            sendAdbKeyFallback(device.ipAddress, listOf(KEYCODE_CHANNEL_DOWN, KEYCODE_PAGE_DOWN)) || remoteSent
                        }
                        else -> sendRemoteV2KeyPayload(device.ipAddress, keycode)
                    }
                } else {
                    sendAdbCommand(device.ipAddress, device.port, "input keyevent $keycode\n")
                }
            }
        } catch (e: Exception) {
            log("[KEYEVENT-ERROR] Failed to send key '${key.name}': ${e.localizedMessage}")
            false
        }
    }

    override suspend fun sendText(text: String): Boolean = withContext(Dispatchers.IO) {
        val device = activeDevice
        if (device == null || !connectedState) {
            log("[TEXT-REJECTED] Device not connected")
            return@withContext false
        }
        log("[TEXT-INPUT] Sending text input '$text' to ${device.ipAddress}:${device.port}")
        return@withContext try {
            val isRemotePort = device.port == 6466 || device.port == 6467
            if (isRemotePort) {
                sendRemoteV2Text(device.ipAddress, text)
            } else {
                val sanitized = text.replace(" ", "%s")
                sendAdbCommand(device.ipAddress, device.port, "input text $sanitized\n")
            }
        } catch (e: Exception) {
            log("[TEXT-INPUT-ERROR] ${e.localizedMessage}")
            false
        }
    }

    override suspend fun sendSwipe(deltaX: Float, deltaY: Float): Boolean = withContext(Dispatchers.IO) {
        val key = when {
            deltaY < -40 -> RemoteKey.UP
            deltaY > 40 -> RemoteKey.DOWN
            deltaX < -40 -> RemoteKey.LEFT
            deltaX > 40 -> RemoteKey.RIGHT
            else -> RemoteKey.ENTER_OK
        }
        log("[SWIPE] Touchpad swipe (dx=$deltaX, dy=$deltaY) mapped to ${key.name}")
        return@withContext sendKey(key)
    }

    override fun isConnected(): Boolean = connectedState

    private fun sendPairingRequestToTv(ip: String): Boolean {
        return try {
            closePairingSocket()
            log("[mTLS-CONNECT] Opening mTLS socket to $ip:6467 presenting client cert (CN=RemoConnect)...")
            val sslContext = sslKeyManager.getOrCreateSslContext()
            val activePairingSocket = sslContext.socketFactory.createSocket() as SSLSocket
            activePairingSocket.soTimeout = 6000
            activePairingSocket.connect(InetSocketAddress(ip, 6467), 5000)
            activePairingSocket.startHandshake()

            sendPoloMessage(activePairingSocket, buildPairingRequestMessage())
            log("[mTLS-REQ-SENT] PairingRequest transmitted over mTLS to $ip:6467. Waiting for TV acknowledgement...")
            val requestAck = readPoloMessage(activePairingSocket)
            if (requestAck.status != POLO_STATUS_OK || !requestAck.hasPairingRequestAck) {
                log("[mTLS-REQ-REJECTED] TV did not acknowledge PairingRequest (status=${requestAck.status}).")
                activePairingSocket.close()
                return false
            }

            sendPoloMessage(activePairingSocket, buildOptionsMessage())
            log("[mTLS-OPTIONS-SENT] Sent hexadecimal PIN options to $ip:6467. Waiting for TV configuration...")
            val options = readPoloMessage(activePairingSocket)
            if (options.status != POLO_STATUS_OK || !options.hasOptions) {
                log("[mTLS-OPTIONS-REJECTED] TV did not provide pairing options (status=${options.status}).")
                activePairingSocket.close()
                return false
            }

            sendPoloMessage(activePairingSocket, buildConfigurationMessage())
            log("[mTLS-CONFIG-SENT] Sent pairing configuration to $ip:6467. Waiting for PIN prompt acknowledgement...")
            val configurationAck = readPoloMessage(activePairingSocket)
            if (configurationAck.status != POLO_STATUS_OK || !configurationAck.hasConfigurationAck) {
                log("[mTLS-CONFIG-REJECTED] TV did not acknowledge pairing configuration (status=${configurationAck.status}).")
                activePairingSocket.close()
                return false
            }

            pairingSocket = activePairingSocket
            log("[mTLS-PIN-PROMPT] Pairing configuration accepted by $ip:6467. The TV should now display a 6-character PIN.")
            true
        } catch (e: Exception) {
            log("[mTLS-REQ-ERROR] mTLS PairingRequest failed for $ip:6467: ${e.localizedMessage ?: e.message}")
            closePairingSocket()
            false
        }
    }

    private fun closePairingSocket() {
        try {
            pairingSocket?.close()
        } catch (_: Exception) {
        } finally {
            pairingSocket = null
        }
    }

    private fun closeRemoteSocket() {
        try {
            remoteSocket?.close()
        } catch (_: Exception) {
        } finally {
            remoteSocket = null
        }
    }

    private suspend fun stopRemoteReader() {
        val job = remoteReaderJob
        remoteReaderJob = null
        if (job != null && job.isActive) {
            job.cancelAndJoin()
        }
    }

    private fun sendPoloMessage(socket: SSLSocket, message: ByteArray) {
        socket.outputStream.write(encodeVarint(message.size))
        socket.outputStream.write(message)
        socket.outputStream.flush()
    }

    private fun readPoloMessage(socket: SSLSocket): PoloMessage {
        val length = readVarint(socket)
        if (length <= 0 || length > 64 * 1024) {
            throw IllegalStateException("Invalid Polo message length: $length")
        }
        val payload = ByteArray(length)
        var offset = 0
        while (offset < length) {
            val read = socket.inputStream.read(payload, offset, length - offset)
            if (read < 0) throw IllegalStateException("Pairing socket closed while reading Polo message")
            offset += read
        }
        return parsePoloMessage(payload)
    }

    private fun readVarint(socket: SSLSocket): Int {
        var result = 0
        var shift = 0
        while (shift < 32) {
            val next = socket.inputStream.read()
            if (next < 0) throw IllegalStateException("Pairing socket closed while reading message length")
            result = result or ((next and 0x7F) shl shift)
            if ((next and 0x80) == 0) return result
            shift += 7
        }
        throw IllegalStateException("Polo message length varint is too long")
    }

    private fun buildPairingRequestMessage(): ByteArray {
        val request = protoString(1, "atvremote") + protoString(2, "RemoConnect")
        return buildOuterMessage(10, request)
    }

    private fun buildOptionsMessage(): ByteArray {
        val encoding = protoVarint(1, ENCODING_HEXADECIMAL) + protoVarint(2, PIN_SYMBOL_LENGTH)
        val options = protoBytes(1, encoding) + protoVarint(3, ROLE_INPUT)
        return buildOuterMessage(20, options)
    }

    private fun buildConfigurationMessage(): ByteArray {
        val encoding = protoVarint(1, ENCODING_HEXADECIMAL) + protoVarint(2, PIN_SYMBOL_LENGTH)
        val configuration = protoBytes(1, encoding) + protoVarint(2, ROLE_INPUT)
        return buildOuterMessage(30, configuration)
    }

    private fun buildSecretMessage(secret: ByteArray): ByteArray {
        return buildOuterMessage(40, protoBytes(1, secret))
    }

    private fun buildOuterMessage(messageField: Int, nestedMessage: ByteArray): ByteArray {
        return protoVarint(1, POLO_PROTOCOL_VERSION) +
            protoVarint(2, POLO_STATUS_OK) +
            protoBytes(messageField, nestedMessage)
    }

    private fun parsePoloMessage(payload: ByteArray): PoloMessage {
        var index = 0
        var status = POLO_STATUS_OK
        var hasPairingRequestAck = false
        var hasOptions = false
        var hasConfigurationAck = false
        var hasSecretAck = false

        while (index < payload.size) {
            val tagResult = decodeVarint(payload, index)
            val tag = tagResult.value
            index = tagResult.nextIndex
            val fieldNumber = tag ushr 3
            val wireType = tag and 0x07

            when (wireType) {
                WIRE_VARINT -> {
                    val value = decodeVarint(payload, index)
                    if (fieldNumber == 2) status = value.value
                    index = value.nextIndex
                }
                WIRE_LENGTH_DELIMITED -> {
                    val length = decodeVarint(payload, index)
                    index = length.nextIndex + length.value
                    when (fieldNumber) {
                        11 -> hasPairingRequestAck = true
                        20 -> hasOptions = true
                        31 -> hasConfigurationAck = true
                        41 -> hasSecretAck = true
                    }
                }
                else -> throw IllegalStateException("Unsupported Polo wire type: $wireType")
            }
        }

        return PoloMessage(
            status = status,
            hasPairingRequestAck = hasPairingRequestAck,
            hasOptions = hasOptions,
            hasConfigurationAck = hasConfigurationAck,
            hasSecretAck = hasSecretAck
        )
    }

    private fun protoVarint(fieldNumber: Int, value: Int): ByteArray {
        return encodeVarint((fieldNumber shl 3) or WIRE_VARINT) + encodeVarint(value)
    }

    private fun protoString(fieldNumber: Int, value: String): ByteArray {
        return protoBytes(fieldNumber, value.toByteArray(Charsets.UTF_8))
    }

    private fun protoBytes(fieldNumber: Int, value: ByteArray): ByteArray {
        return encodeVarint((fieldNumber shl 3) or WIRE_LENGTH_DELIMITED) + encodeVarint(value.size) + value
    }

    private fun encodeVarint(value: Int): ByteArray {
        val output = ByteArrayOutputStream()
        var remaining = value
        while (true) {
            if ((remaining and 0x7F.inv()) == 0) {
                output.write(remaining)
                return output.toByteArray()
            }
            output.write((remaining and 0x7F) or 0x80)
            remaining = remaining ushr 7
        }
    }

    private fun decodeVarint(bytes: ByteArray, startIndex: Int): VarintResult {
        var result = 0
        var shift = 0
        var index = startIndex
        while (shift < 32 && index < bytes.size) {
            val next = bytes[index].toInt() and 0xFF
            result = result or ((next and 0x7F) shl shift)
            index++
            if ((next and 0x80) == 0) return VarintResult(result, index)
            shift += 7
        }
        throw IllegalStateException("Invalid protobuf varint")
    }

    private fun buildPairingSecret(clientCert: X509Certificate, serverCert: X509Certificate, pin: String): ByteArray {
        val clientKey = clientCert.publicKey as java.security.interfaces.RSAPublicKey
        val serverKey = serverCert.publicKey as java.security.interfaces.RSAPublicKey
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(toUnsignedBytes(clientKey.modulus))
        digest.update(toEvenHexBytes(clientKey.publicExponent))
        digest.update(toUnsignedBytes(serverKey.modulus))
        digest.update(toEvenHexBytes(serverKey.publicExponent))
        digest.update(hexToBytes(pin.substring(2)))
        val secret = digest.digest()
        val expectedFirstByte = pin.substring(0, 2).toInt(16)
        if ((secret[0].toInt() and 0xFF) != expectedFirstByte) {
            throw IllegalArgumentException("Pairing code checksum did not match certificates")
        }
        return secret
    }

    private fun toUnsignedBytes(value: BigInteger): ByteArray {
        val bytes = value.toByteArray()
        return if (bytes.size > 1 && bytes[0].toInt() == 0) bytes.copyOfRange(1, bytes.size) else bytes
    }

    private fun toEvenHexBytes(value: BigInteger): ByteArray {
        var hex = value.toString(16)
        if (hex.length % 2 != 0) hex = "0$hex"
        return hexToBytes(hex)
    }

    private fun hexToBytes(hex: String): ByteArray {
        require(hex.length % 2 == 0) { "Hex string must have an even length" }
        return ByteArray(hex.length / 2) { index ->
            hex.substring(index * 2, index * 2 + 2).toInt(16).toByte()
        }
    }

    private data class VarintResult(val value: Int, val nextIndex: Int)

    private data class PoloMessage(
        val status: Int,
        val hasPairingRequestAck: Boolean = false,
        val hasOptions: Boolean = false,
        val hasConfigurationAck: Boolean = false,
        val hasSecretAck: Boolean = false
    )

    private companion object {
        private const val POLO_PROTOCOL_VERSION = 2
        private const val POLO_STATUS_OK = 200
        private const val ROLE_INPUT = 1
        private const val ENCODING_HEXADECIMAL = 3
        private const val PIN_SYMBOL_LENGTH = 6
        private const val WIRE_VARINT = 0
        private const val WIRE_LENGTH_DELIMITED = 2
        private const val REMOTE_FEATURE_PING = 1
        private const val REMOTE_FEATURE_KEY = 2
        private const val REMOTE_FEATURE_IME = 4
        private const val REMOTE_FEATURE_POWER = 32
        private const val REMOTE_FEATURE_VOLUME = 64
        private const val REMOTE_FEATURE_APP_LINK = 512
        private const val REMOTE_ACTIVE_FEATURES = REMOTE_FEATURE_PING or
            REMOTE_FEATURE_KEY or
            REMOTE_FEATURE_IME or
            REMOTE_FEATURE_POWER or
            REMOTE_FEATURE_VOLUME or
            REMOTE_FEATURE_APP_LINK
        private const val REMOTE_DIRECTION_DOWN = 1
        private const val REMOTE_DIRECTION_UP = 2
        private const val REMOTE_DIRECTION_SHORT = 3
        private const val ADB_PORT = 5555
        private const val KEYCODE_MENU = 82
        private const val KEYCODE_PAGE_UP = 92
        private const val KEYCODE_PAGE_DOWN = 93
        private const val KEYCODE_MEDIA_NEXT = 87
        private const val KEYCODE_MEDIA_PREVIOUS = 88
        private const val KEYCODE_CHANNEL_UP = 166
        private const val KEYCODE_CHANNEL_DOWN = 167
        private const val KEYCODE_SETTINGS = 176
        private const val KEYCODE_TV_INPUT = 178
        private const val KEYCODE_TV_INPUT_HDMI_1 = 243
        private const val KEYCODE_TV_INPUT_HDMI_2 = 244
        private const val KEYCODE_TV_CONTENTS_MENU = 256
    }

    private fun openRemoteControlSession(ip: String): Boolean {
        return try {
            synchronized(remoteSocketLock) {
                closeRemoteSocket()
                remoteReaderJob?.cancel()
                remoteReaderJob = null
                log("[V2-CONNECT] Opening Android TV Remote v2 control socket to $ip:6466...")
                val sslContext = sslKeyManager.getOrCreateSslContext()
                val activeRemoteSocket = sslContext.socketFactory.createSocket() as SSLSocket
                activeRemoteSocket.soTimeout = 5000
                activeRemoteSocket.connect(InetSocketAddress(ip, 6466), 5000)
                activeRemoteSocket.startHandshake()
                remoteSocket = activeRemoteSocket

                val deadline = System.currentTimeMillis() + 6000L
                while (System.currentTimeMillis() < deadline) {
                    val message = readRemoteMessage(activeRemoteSocket)
                    val started = handleRemoteServerMessage(activeRemoteSocket, message)
                    if (started) {
                        activeRemoteSocket.soTimeout = 0
                        startRemoteReader(activeRemoteSocket)
                        log("[V2-READY] Remote control channel established on $ip:6466.")
                        return true
                    }
                }
            }
            log("[V2-CONNECT-ERROR] Timed out waiting for RemoteStart from $ip:6466.")
            closeRemoteSocket()
            false
        } catch (e: Exception) {
            log("[V2-CONNECT-ERROR] Remote control channel failed for $ip:6466: ${e.localizedMessage ?: e.message}")
            closeRemoteSocket()
            false
        }
    }

    private fun ensureRemoteControlSession(ip: String): SSLSocket? {
        val current = remoteSocket
        if (current != null && current.isConnected && !current.isClosed && !current.isOutputShutdown) return current
        return if (openRemoteControlSession(ip)) remoteSocket else null
    }

    private fun sendRemoteV2KeyPayload(ip: String, keycode: Int): Boolean {
        return try {
            log("[V2-PAYLOAD] Sending RemoteKeyInject(keycode=$keycode, direction=DOWN/UP) to $ip:6466")
            sendRemoteV2Message(ip, buildRemoteKeyMessage(keycode, REMOTE_DIRECTION_DOWN))
            Thread.sleep(60L)
            sendRemoteV2Message(ip, buildRemoteKeyMessage(keycode, REMOTE_DIRECTION_UP))
            log("[V2-PAYLOAD-SUCCESS] RemoteKeyInject (DOWN/UP) sent for keycode $keycode to $ip:6466")
            true
        } catch (e: Exception) {
            log("[V2-PAYLOAD-ERROR] Key payload error for $ip:6466: ${e.localizedMessage ?: e.message}")
            false
        }
    }

    private fun sendRemoteV2Text(ip: String, text: String): Boolean {
        return try {
            log("[V2-TEXT] Sending RemoteImeBatchEdit('${text.take(32)}') to $ip:6466")
            val message = buildRemoteTextMessage(text)
            sendRemoteV2Message(ip, message)
            log("[V2-TEXT-SUCCESS] Text payload sent to $ip:6466")
            true
        } catch (e: Exception) {
            log("[V2-TEXT-ERROR] Text payload error for $ip:6466: ${e.localizedMessage ?: e.message}")
            false
        }
    }

    private fun sendRemoteV2AppLink(ip: String, appLink: String): Boolean {
        return try {
            log("[V2-APP-LAUNCH] Sending app link '$appLink' to $ip:6466")
            sendRemoteV2Message(ip, buildRemoteAppLinkMessage(appLink))
            log("[V2-APP-LAUNCH-SUCCESS] App link payload sent to $ip:6466")
            true
        } catch (e: Exception) {
            log("[V2-APP-LAUNCH-ERROR] App link payload error for $ip:6466: ${e.localizedMessage ?: e.message}")
            false
        }
    }

    private fun sendRemoteV2AppLaunch(ip: String, key: RemoteKey): Boolean {
        val candidates = buildAppLaunchCandidates(key)
        if (candidates.isEmpty()) return false

        var sentAny = false
        candidates.forEachIndexed { index, appLink ->
            val sent = sendRemoteV2AppLink(ip, appLink)
            sentAny = sentAny || sent
            if (index < candidates.lastIndex) {
                Thread.sleep(180L)
            }
        }
        return sentAny
    }

    private fun sendRemoteV2InputSource(ip: String): Boolean {
        var sentAny = sendRemoteV2SystemKeyFallback(
            ip = ip,
            label = RemoteKey.INPUT_SOURCE.name,
            keycodes = listOf(KEYCODE_TV_INPUT, KEYCODE_TV_INPUT_HDMI_1, KEYCODE_TV_INPUT_HDMI_2, KEYCODE_MENU)
        )
        Thread.sleep(180L)

        listOf(
            "intent://inputs#Intent;action=android.settings.TV_INPUT_SETTINGS;end",
            "intent://inputs#Intent;action=android.settings.INPUT_METHOD_SETTINGS;end",
            "android-app://com.android.tv.settings"
        ).forEach { appLink ->
            sentAny = sendRemoteV2AppLink(ip, appLink) || sentAny
            Thread.sleep(120L)
        }

        return sendAdbKeyFallback(ip, listOf(KEYCODE_TV_INPUT, KEYCODE_MENU)) || sentAny
    }

    private fun sendRemoteV2SystemKeyFallback(ip: String, label: String, keycodes: List<Int>): Boolean {
        var sentAny = false
        keycodes.distinct().forEachIndexed { index, keycode ->
            log("[V2-SYSTEM-FALLBACK] Trying $label fallback keycode=$keycode")
            sentAny = sendRemoteV2KeyPayload(ip, keycode) || sentAny
            if (index < keycodes.lastIndex) {
                Thread.sleep(140L)
            }
        }
        return sentAny
    }

    private fun sendAdbKeyFallback(ip: String, keycodes: List<Int>): Boolean {
        var sentAny = false
        keycodes.distinct().forEach { keycode ->
            log("[ADB-FALLBACK] Trying input keyevent $keycode on $ip:5555")
            sentAny = sendAdbCommand(ip, ADB_PORT, "input keyevent $keycode\n") || sentAny
        }
        return sentAny
    }

    private fun buildAppLaunchCandidates(key: RemoteKey): List<String> {
        val packageName = key.appPackage
        val candidates = mutableListOf<String>()
        key.appLink?.let { candidates.add(it) }

        when (key) {
            RemoteKey.SETTINGS -> {
                candidates.add("intent://settings#Intent;package=com.android.tv.settings;end")
                candidates.add("intent://settings#Intent;action=android.settings.SETTINGS;end")
                candidates.add("intent://settings#Intent;action=android.settings.TV_SETTINGS;end")
                candidates.add("android-app://com.google.android.tv.settings")
                candidates.add("intent://settings#Intent;package=com.google.android.tv.settings;end")
                candidates.add("android-app://com.tcl.settings")
                candidates.add("intent://settings#Intent;package=com.tcl.settings;end")
            }
            RemoteKey.NETFLIX -> {
                candidates.add("nflx://www.netflix.com/browse")
                candidates.add("intent://www.netflix.com/browse#Intent;scheme=nflx;package=com.netflix.ninja;end")
            }
            RemoteKey.YOUTUBE -> {
                candidates.add("vnd.youtube://")
                candidates.add("intent://#Intent;package=com.google.android.youtube.tv;scheme=vnd.youtube;end")
            }
            RemoteKey.PRIME_VIDEO -> {
                candidates.add("primevideo://home")
                candidates.add("amazonvideo://home")
                candidates.add("aiv://aiv/home")
                candidates.add("aiv://home")
                candidates.add("https://app.primevideo.com")
                candidates.add("intent://watch.amazon.com#Intent;package=com.amazon.amazonvideo.livingroom;scheme=https;end")
                candidates.add("android-app://com.amazon.avod.thirdpartyclient")
                candidates.add("intent://watch.amazon.com#Intent;package=com.amazon.avod.thirdpartyclient;scheme=https;end")
                candidates.add("intent://aiv/home#Intent;package=com.amazon.amazonvideo.livingroom;scheme=aiv;end")
                candidates.add("intent://aiv/home#Intent;package=com.amazon.avod.thirdpartyclient;scheme=aiv;end")
                candidates.add("intent://home#Intent;package=com.amazon.amazonvideo.livingroom;scheme=primevideo;end")
                candidates.add("intent://home#Intent;package=com.amazon.avod.thirdpartyclient;scheme=primevideo;end")
                candidates.add("intent://home#Intent;package=com.amazon.amazonvideo.livingroom;scheme=amazonvideo;end")
                candidates.add("intent://home#Intent;package=com.amazon.avod.thirdpartyclient;scheme=amazonvideo;end")
                candidates.add("intent://#Intent;action=android.intent.action.MAIN;category=android.intent.category.LEANBACK_LAUNCHER;package=com.amazon.amazonvideo.livingroom;end")
                candidates.add("intent://#Intent;action=android.intent.action.MAIN;category=android.intent.category.LEANBACK_LAUNCHER;package=com.amazon.avod.thirdpartyclient;end")
            }
            RemoteKey.DISNEY_PLUS -> {
                candidates.add("disneyplus://")
                candidates.add("intent://#Intent;package=com.disney.disneyplus;scheme=disneyplus;end")
                candidates.add("android-app://com.disney.disneyplus.androidtv")
                candidates.add("intent://#Intent;package=com.disney.disneyplus.androidtv;scheme=disneyplus;end")
                candidates.add("intent://www.disneyplus.com#Intent;package=com.disney.disneyplus;scheme=https;end")
                candidates.add("intent://www.disneyplus.com#Intent;package=com.disney.disneyplus.androidtv;scheme=https;end")
            }
            RemoteKey.SPOTIFY -> {
                candidates.add("spotify://")
                candidates.add("intent://#Intent;package=com.spotify.tv.android;scheme=spotify;end")
            }
            RemoteKey.GOOGLE_PLAY -> {
                candidates.add("market://")
                candidates.add("market://apps")
                candidates.add("market://search?q=")
                candidates.add("intent://#Intent;package=com.android.vending;scheme=market;end")
                candidates.add("intent://apps#Intent;package=com.android.vending;scheme=market;end")
                candidates.add("intent://#Intent;action=android.intent.action.MAIN;category=android.intent.category.LEANBACK_LAUNCHER;package=com.android.vending;end")
            }
            else -> Unit
        }

        if (packageName != null) {
            candidates.add("android-app://$packageName")
            candidates.add("intent://#Intent;package=$packageName;end")
        }

        return candidates.distinct()
    }

    private fun sendRemoteV2Message(ip: String, message: ByteArray) {
        synchronized(remoteSocketLock) {
            try {
                val activeRemoteSocket = ensureRemoteControlSession(ip)
                    ?: throw IllegalStateException("Remote control socket is not connected")
                sendLengthPrefixedMessage(activeRemoteSocket, message)
            } catch (e: Exception) {
                log("[V2-RECONNECT] Remote socket send failed (${e.localizedMessage ?: e.message}). Reopening channel and retrying once...")
                closeRemoteSocket()
                val retrySocket = ensureRemoteControlSession(ip)
                    ?: throw IllegalStateException("Remote control socket reconnect failed")
                sendLengthPrefixedMessage(retrySocket, message)
            }
        }
    }

    private fun readRemoteMessage(socket: SSLSocket): RemoteServerMessage {
        val length = readVarint(socket)
        if (length <= 0 || length > 256 * 1024) {
            throw IllegalStateException("Invalid RemoteMessage length: $length")
        }
        val payload = ByteArray(length)
        var offset = 0
        while (offset < length) {
            val read = socket.inputStream.read(payload, offset, length - offset)
            if (read < 0) throw IllegalStateException("Remote socket closed while reading RemoteMessage")
            offset += read
        }
        return parseRemoteServerMessage(payload)
    }

    private fun handleRemoteServerMessage(socket: SSLSocket, message: RemoteServerMessage): Boolean {
        if (message.remoteError) {
            log("[V2-REMOTE-ERROR] TV reported a RemoteMessage error. Closing channel so the next command reconnects cleanly.")
            closeRemoteSocket()
            throw IllegalStateException("TV rejected the previous Remote v2 message")
        }

        message.configureFeatures?.let { features ->
            val negotiatedFeatures = features and REMOTE_ACTIVE_FEATURES
            log("[V2-CONFIGURE] TV supports features=$features. Enabling features=$negotiatedFeatures.")
            sendLengthPrefixedMessage(socket, buildRemoteConfigureMessage(negotiatedFeatures))
        }

        if (message.setActiveRequested) {
            log("[V2-ACTIVE] TV requested active feature state.")
            sendLengthPrefixedMessage(socket, buildRemoteSetActiveMessage(REMOTE_ACTIVE_FEATURES))
        }

        message.pingValue?.let { pingValue ->
            log("[V2-PING] Replying to TV ping ($pingValue).")
            sendLengthPrefixedMessage(socket, buildRemotePingResponseMessage(pingValue))
        }

        message.imeCounters?.let { counters ->
            imeCounter = counters.imeCounter
            imeFieldCounter = counters.fieldCounter
            log("[V2-IME] Updated IME counters (${counters.imeCounter}, ${counters.fieldCounter}).")
        }

        return message.remoteStarted == true
    }

    private fun sendLengthPrefixedMessage(socket: SSLSocket, message: ByteArray) {
        synchronized(remoteSocketLock) {
            socket.outputStream.write(encodeVarint(message.size))
            socket.outputStream.write(message)
            socket.outputStream.flush()
        }
    }

    private fun startRemoteReader(socket: SSLSocket) {
        remoteReaderJob?.cancel()
        remoteReaderJob = remoteScope.launch {
            while (isActive && remoteSocket === socket && !socket.isClosed) {
                try {
                    handleRemoteServerMessage(socket, readRemoteMessage(socket))
                } catch (e: Exception) {
                    if (isActive && remoteSocket === socket) {
                        log("[V2-READER-CLOSED] Remote control channel closed: ${e.localizedMessage ?: e.message}")
                        closeRemoteSocket()
                        connectedState = false
                    }
                    break
                }
            }
        }
    }

    private fun buildRemoteKeyMessage(keycode: Int, direction: Int = REMOTE_DIRECTION_SHORT): ByteArray {
        val keyInject = protoVarint(1, keycode) + protoVarint(2, direction)
        return protoBytes(10, keyInject)
    }

    private fun buildRemoteTextMessage(text: String): ByteArray {
        val end = (text.length - 1).coerceAtLeast(0)
        val imeObject = protoVarint(1, end) + protoVarint(2, end) + protoString(3, text)
        val editInfo = protoVarint(1, 1) + protoBytes(2, imeObject)
        val batchEdit = protoVarint(1, imeCounter) +
            protoVarint(2, imeFieldCounter) +
            protoBytes(3, editInfo)
        return protoBytes(21, batchEdit)
    }

    private fun buildRemoteAppLinkMessage(appLink: String): ByteArray {
        return protoBytes(90, protoString(1, appLink))
    }

    private fun buildRemoteConfigureMessage(features: Int): ByteArray {
        val deviceInfo = protoString(1, "RemoConnect") +
            protoString(2, "Famage") +
            protoVarint(3, 1) +
            protoString(4, "1") +
            protoString(5, "com.famage.remoconnect") +
            protoString(6, "1.0")
        val configure = protoVarint(1, features) + protoBytes(2, deviceInfo)
        return protoBytes(1, configure)
    }

    private fun buildRemoteSetActiveMessage(activeFeatures: Int): ByteArray {
        return protoBytes(2, protoVarint(1, activeFeatures))
    }

    private fun buildRemotePingResponseMessage(value: Int): ByteArray {
        return protoBytes(9, protoVarint(1, value))
    }

    private fun parseRemoteServerMessage(payload: ByteArray): RemoteServerMessage {
        var index = 0
        var configureFeatures: Int? = null
        var setActiveRequested = false
        var pingValue: Int? = null
        var remoteStarted: Boolean? = null
        var imeCounters: ImeCounters? = null
        var remoteError = false

        while (index < payload.size) {
            val tagResult = decodeVarint(payload, index)
            val fieldNumber = tagResult.value ushr 3
            val wireType = tagResult.value and 0x07
            index = tagResult.nextIndex

            when (wireType) {
                WIRE_VARINT -> {
                    val value = decodeVarint(payload, index)
                    index = value.nextIndex
                }
                WIRE_LENGTH_DELIMITED -> {
                    val length = decodeVarint(payload, index)
                    val start = length.nextIndex
                    val end = start + length.value
                    if (end > payload.size) throw IllegalStateException("RemoteMessage field exceeded payload length")
                    val nested = payload.copyOfRange(start, end)
                    when (fieldNumber) {
                        1 -> configureFeatures = parseFirstVarintField(nested, 1)
                        2 -> setActiveRequested = true
                        3 -> remoteError = parseFirstVarintField(nested, 1) == 1
                        8 -> pingValue = parseFirstVarintField(nested, 1)
                        21 -> imeCounters = parseImeCounters(nested)
                        40 -> remoteStarted = parseFirstVarintField(nested, 1) == 1
                    }
                    index = end
                }
                else -> throw IllegalStateException("Unsupported RemoteMessage wire type: $wireType")
            }
        }

        return RemoteServerMessage(
            configureFeatures = configureFeatures,
            setActiveRequested = setActiveRequested,
            pingValue = pingValue,
            remoteStarted = remoteStarted,
            imeCounters = imeCounters,
            remoteError = remoteError
        )
    }

    private fun parseFirstVarintField(payload: ByteArray, targetField: Int): Int? {
        var index = 0
        while (index < payload.size) {
            val tag = decodeVarint(payload, index)
            val fieldNumber = tag.value ushr 3
            val wireType = tag.value and 0x07
            index = tag.nextIndex
            when (wireType) {
                WIRE_VARINT -> {
                    val value = decodeVarint(payload, index)
                    if (fieldNumber == targetField) return value.value
                    index = value.nextIndex
                }
                WIRE_LENGTH_DELIMITED -> {
                    val length = decodeVarint(payload, index)
                    index = length.nextIndex + length.value
                }
                else -> throw IllegalStateException("Unsupported nested wire type: $wireType")
            }
        }
        return null
    }

    private fun parseImeCounters(payload: ByteArray): ImeCounters {
        var index = 0
        var counter = 0
        var fieldCounter = 0

        while (index < payload.size) {
            val tag = decodeVarint(payload, index)
            val fieldNumber = tag.value ushr 3
            val wireType = tag.value and 0x07
            index = tag.nextIndex
            when (wireType) {
                WIRE_VARINT -> {
                    val value = decodeVarint(payload, index)
                    when (fieldNumber) {
                        1 -> counter = value.value
                        2 -> fieldCounter = value.value
                    }
                    index = value.nextIndex
                }
                WIRE_LENGTH_DELIMITED -> {
                    val length = decodeVarint(payload, index)
                    index = length.nextIndex + length.value
                }
                else -> throw IllegalStateException("Unsupported IME wire type: $wireType")
            }
        }

        return ImeCounters(counter, fieldCounter)
    }

    private data class RemoteServerMessage(
        val configureFeatures: Int? = null,
        val setActiveRequested: Boolean = false,
        val pingValue: Int? = null,
        val remoteStarted: Boolean? = null,
        val imeCounters: ImeCounters? = null,
        val remoteError: Boolean = false
    )

    private data class ImeCounters(val imeCounter: Int, val fieldCounter: Int)

    private fun sendAdbCommand(ip: String, port: Int, command: String): Boolean {
        return try {
            val targetPort = if (port > 0) port else 5555
            val socket = Socket()
            socket.connect(InetSocketAddress(ip, targetPort), 1500)
            val os = socket.getOutputStream()
            os.write(command.toByteArray(Charsets.UTF_8))
            os.flush()
            os.close()
            socket.close()
            true
        } catch (e: Exception) {
            false
        }
    }
}
