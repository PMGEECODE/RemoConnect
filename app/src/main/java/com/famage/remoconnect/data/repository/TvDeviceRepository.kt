package com.famage.remoconnect.data.repository

import android.content.Context
import com.famage.remoconnect.data.discovery.DeviceDiscoveryService
import com.famage.remoconnect.data.model.ConnectionType
import com.famage.remoconnect.data.model.RemoteKey
import com.famage.remoconnect.data.model.TvDevice
import com.famage.remoconnect.data.protocol.AdbProtocolEngine
import com.famage.remoconnect.data.protocol.AndroidTvRemoteEngine
import com.famage.remoconnect.data.protocol.HybridRemoteEngine
import com.famage.remoconnect.data.protocol.RemoteProtocolEngine
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.net.URLDecoder
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class TvDeviceRepository(
    context: Context? = null,
    private var discoveryService: DeviceDiscoveryService? = null,
    adbEngine: RemoteProtocolEngine? = null,
    androidTvEngine: RemoteProtocolEngine? = null,
    private val irEngine: RemoteProtocolEngine? = null
) {
    private val appContext = context?.applicationContext
    private val preferences = appContext?.getSharedPreferences("remoconnect_devices", Context.MODE_PRIVATE)

    private val _debugLogs = MutableStateFlow<List<String>>(emptyList())
    val debugLogs: StateFlow<List<String>> = _debugLogs.asStateFlow()

    fun addLog(msg: String) {
        val timestamp = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        val entry = "[$timestamp] $msg"
        val updated = _debugLogs.value.toMutableList()
        updated.add(0, entry) // Newest logs first
        if (updated.size > 200) updated.removeAt(updated.lastIndex)
        _debugLogs.value = updated
    }

    fun setDiscoveryService(service: DeviceDiscoveryService) {
        this.discoveryService = service
    }

    private val actualAndroidTvEngine: RemoteProtocolEngine = androidTvEngine ?: AndroidTvRemoteEngine(
        context = appContext,
        logger = { addLog(it) }
    )
    private val actualAdbEngine: RemoteProtocolEngine = adbEngine ?: AdbProtocolEngine(logger = { addLog(it) })
    private val hybridEngine: RemoteProtocolEngine = HybridRemoteEngine(
        primaryEngine = actualAndroidTvEngine,
        fallbackEngine = actualAdbEngine,
        logger = { addLog(it) }
    )

    private val _activeDevice = MutableStateFlow<TvDevice?>(null)
    val activeDevice: StateFlow<TvDevice?> = _activeDevice.asStateFlow()

    private val _connectionStatus = MutableStateFlow("Disconnected")
    val connectionStatus: StateFlow<String> = _connectionStatus.asStateFlow()

    private val _pairingRequired = MutableStateFlow(false)
    val pairingRequired: StateFlow<Boolean> = _pairingRequired.asStateFlow()

    private val _savedDevices = MutableStateFlow<List<TvDevice>>(emptyList())
    val savedDevices: StateFlow<List<TvDevice>> = _savedDevices.asStateFlow()

    init {
        val restoredDevices = loadSavedDevices()
        if (restoredDevices.isNotEmpty()) {
            _savedDevices.value = restoredDevices
            restoreActiveDevice(restoredDevices)
            addLog("[REPO-RESTORE] Restored ${restoredDevices.size} saved TV device(s) from app storage.")
        }
    }

    fun getDiscoveredDevices(): StateFlow<List<TvDevice>> {
        return discoveryService?.discoveredDevices ?: MutableStateFlow(emptyList())
    }

    fun isScanning(): StateFlow<Boolean> {
        return discoveryService?.isScanning ?: MutableStateFlow(false)
    }

    fun startScanning() {
        addLog("[SCAN] Initiating mDNS network scan for _androidtvremote2._tcp.")
        discoveryService?.startDiscovery()
    }

    fun stopScanning() {
        addLog("[SCAN] Network discovery scan stopped.")
        discoveryService?.stopDiscovery()
    }

    fun clearDebugLogs() {
        _debugLogs.value = emptyList()
    }

    fun getLastPairedDevice(): TvDevice? {
        getActiveSavedDevice()?.let { active ->
            if (active.isPaired) return active
        }
        return _savedDevices.value
            .filter { it.isPaired }
            .maxByOrNull { it.lastSeenTimestamp }
    }

    fun selectActiveDevice(device: TvDevice) {
        val savedDevice = findMatchingSavedDevice(device)?.let { mergeDeviceRecords(it, device) }
            ?: device.copy(isConnected = false)
        _activeDevice.value = savedDevice.copy(isConnected = false)
        _pairingRequired.value = false
        _connectionStatus.value = if (savedDevice.isPaired) {
            "${savedDevice.name} selected"
        } else {
            "${savedDevice.name} selected - not paired"
        }
        saveActiveDeviceId(savedDevice.id)
        saveDevice(savedDevice)
        addLog("[REPO-ACTIVE] ${savedDevice.name} selected as active TV.")
    }

    fun mergeDiscoveredDevice(device: TvDevice) {
        val existing = findMatchingSavedDevice(device) ?: return
        val merged = mergeDeviceRecords(existing, device).copy(
            isConnected = activeDevice.value?.id == existing.id && activeDevice.value?.isConnected == true
        )
        saveDevice(merged)
        if (_activeDevice.value?.id == existing.id || _activeDevice.value?.ipAddress == existing.ipAddress) {
            _activeDevice.value = merged.copy(isConnected = _activeDevice.value?.isConnected == true)
        }
        addLog("[REPO-SYNC] Updated saved TV '${existing.name}' from discovery (${merged.ipAddress}:${merged.port}).")
    }

    suspend fun connectToDevice(device: TvDevice): Boolean {
        val savedDevice = findMatchingSavedDevice(device)
        val targetDevice = savedDevice?.let { mergeDeviceRecords(it, device) } ?: device
        selectActiveDevice(targetDevice)

        val modeLabel = if (device.connectionType == ConnectionType.ANDROID_TV_REMOTE_V2 || device.port == 6466) {
            "Native Wi-Fi TLS (6467/6466)"
        } else {
            "ADB Wi-Fi (5555)"
        }
        addLog("[REPO-CONNECT] Initiating connection to ${targetDevice.name} (${targetDevice.ipAddress}:${targetDevice.port}) via $modeLabel")
        _connectionStatus.value = "Connecting to ${targetDevice.name}..."
        val engine = getEngineForDevice(targetDevice)
        val success = engine.connect(targetDevice)

        if (success) {
            if (engine.requiresPairing()) {
                val pendingDevice = targetDevice.copy(isConnected = false)
                _activeDevice.value = pendingDevice
                _pairingRequired.value = true
                _connectionStatus.value = "Pairing Required for ${targetDevice.name}"
                addLog("[REPO-PAIRING] ${targetDevice.name} requires PIN pairing. Enter PIN displayed on TV.")
                saveDevice(pendingDevice)
                return true
            } else {
                val connectedDevice = targetDevice.copy(
                    isConnected = true,
                    isPaired = targetDevice.isPaired || targetDevice.connectionType == ConnectionType.ANDROID_TV_REMOTE_V2 || targetDevice.port == 6466 || targetDevice.port == 6467,
                    lastSeenTimestamp = System.currentTimeMillis()
                )
                _activeDevice.value = connectedDevice
                _pairingRequired.value = false
                _connectionStatus.value = "Connected to ${targetDevice.name}"
                addLog("[REPO-CONNECTED] Connection established with ${targetDevice.name}")
                saveDevice(connectedDevice)
                return true
            }
        } else {
            _activeDevice.value = targetDevice.copy(isConnected = false)
            _pairingRequired.value = false
            _connectionStatus.value = "Connection failed to ${targetDevice.name}"
            addLog("[REPO-ERROR] Connection failed to ${targetDevice.name}; saved pairing/device record was kept.")
            return false
        }
    }

    suspend fun verifyPairingPin(pin: String): Boolean {
        val current = _activeDevice.value ?: return false
        addLog("[REPO-VERIFY-PIN] Verifying PIN code for ${current.name}...")
        val engine = getEngineForDevice(current)
        val verified = engine.verifyPin(pin)
        if (verified) {
            val connectedDevice = current.copy(
                isConnected = true,
                isPaired = true,
                lastSeenTimestamp = System.currentTimeMillis()
            )
            _activeDevice.value = connectedDevice
            _pairingRequired.value = false
            _connectionStatus.value = "Connected to ${current.name}"
            addLog("[REPO-VERIFIED] PIN verified! Connected to ${current.name}")
            saveDevice(connectedDevice)
            return true
        } else {
            _pairingRequired.value = true
            _connectionStatus.value = "Incorrect PIN for ${current.name}"
            addLog("[REPO-PIN-FAILED] PIN verification failed for ${current.name}")
            return false
        }
    }

    suspend fun disconnectCurrentDevice() {
        val current = _activeDevice.value ?: return
        addLog("[REPO-DISCONNECT] Disconnecting from ${current.name}")
        val engine = getEngineForDevice(current)
        engine.disconnect()
        _activeDevice.value = current.copy(isConnected = false)
        _pairingRequired.value = false
        _connectionStatus.value = "Disconnected"
        saveDevice(current.copy(isConnected = false))
    }

    suspend fun sendRemoteKey(key: RemoteKey): Boolean {
        val current = _activeDevice.value
        if (current == null || !current.isConnected) {
            addLog("[REPO-KEY-REJECTED] Cannot send '${key.name}' - No TV connected.")
            return false
        }
        addLog("[REPO-KEY] Key '${key.name}' requested for ${current.name}")
        val engine = getEngineForDevice(current)
        val sent = engine.sendKey(key)
        if (sent || !current.isPaired) return sent

        addLog("[REPO-RECONNECT] Key send failed. Resuming paired session with ${current.name} and retrying '${key.name}'...")
        return if (resumePairedDevice(current)) {
            engine.sendKey(key)
        } else {
            false
        }
    }

    suspend fun sendTextInput(text: String): Boolean {
        val current = _activeDevice.value
        if (current == null || !current.isConnected) {
            addLog("[REPO-TEXT-REJECTED] Cannot send text - No TV connected.")
            return false
        }
        addLog("[REPO-TEXT] Sending text input to ${current.name}")
        val engine = getEngineForDevice(current)
        val sent = engine.sendText(text)
        if (sent || !current.isPaired) return sent

        addLog("[REPO-RECONNECT] Text send failed. Resuming paired session with ${current.name} and retrying text input...")
        return if (resumePairedDevice(current)) {
            engine.sendText(text)
        } else {
            false
        }
    }

    suspend fun sendTouchpadSwipe(deltaX: Float, deltaY: Float): Boolean {
        val current = _activeDevice.value ?: return false
        val engine = getEngineForDevice(current)
        val sent = engine.sendSwipe(deltaX, deltaY)
        if (sent || !current.isPaired) return sent

        addLog("[REPO-RECONNECT] Touchpad send failed. Resuming paired session with ${current.name} and retrying gesture...")
        return if (resumePairedDevice(current)) {
            engine.sendSwipe(deltaX, deltaY)
        } else {
            false
        }
    }

    fun addManualTv(name: String, ipAddress: String, port: Int = 6466): TvDevice {
        val connType = if (port == 5555) ConnectionType.ADB_WIFI else ConnectionType.ANDROID_TV_REMOTE_V2
        addLog("[REPO-MANUAL] Added manual device '$name' ($ipAddress:$port) via $connType")
        val newDevice = TvDevice(
            id = ipAddress,
            name = if (name.isNotBlank()) name else "TCL TV ($ipAddress)",
            ipAddress = ipAddress,
            port = port,
            connectionType = connType
        )
        val savedDevice = findMatchingSavedDevice(newDevice)?.let { mergeDeviceRecords(it, newDevice) } ?: newDevice
        saveDevice(savedDevice)
        return savedDevice
    }

    suspend fun reconnectActiveDevice(): Boolean {
        val current = _activeDevice.value ?: getLastPairedDevice() ?: return false
        if (!current.isPaired) {
            addLog("[REPO-RESUME-SKIP] ${current.name} is saved but not paired yet.")
            return false
        }
        return resumePairedDevice(current)
    }

    fun forgetDevice(device: TvDevice) {
        val updated = _savedDevices.value.filterNot {
            it.id == device.id || it.ipAddress == device.ipAddress
        }
        _savedDevices.value = updated
        persistSavedDevices(updated)

        val active = _activeDevice.value
        if (active?.id == device.id || active?.ipAddress == device.ipAddress) {
            _activeDevice.value = null
            _pairingRequired.value = false
            _connectionStatus.value = "Disconnected"
            saveActiveDeviceId(null)
        }
        addLog("[REPO-FORGET] Removed saved TV '${device.name}' from this app.")
    }

    private fun saveDevice(device: TvDevice) {
        val currentList = _savedDevices.value.toMutableList()
        val index = currentList.indexOfFirst { existing ->
            existing.id == device.id || existing.ipAddress == device.ipAddress
        }
        val cleanDevice = device.copy(isConnected = false)
        if (index >= 0) {
            currentList[index] = cleanDevice
        } else {
            currentList.add(cleanDevice)
        }
        _savedDevices.value = currentList.sortedWith(
            compareByDescending<TvDevice> { it.isPaired }.thenByDescending { it.lastSeenTimestamp }
        )
        persistSavedDevices(currentList)
    }

    private suspend fun resumePairedDevice(device: TvDevice): Boolean {
        val engine = getEngineForDevice(device)
        val resumed = engine.connect(device.copy(isConnected = false, isPaired = true))
        if (!resumed || engine.requiresPairing()) {
            _pairingRequired.value = engine.requiresPairing()
            _connectionStatus.value = if (engine.requiresPairing()) {
                "Pairing Required for ${device.name}"
            } else {
                "Connection lost to ${device.name}"
            }
            addLog("[REPO-RECONNECT-FAILED] Could not resume paired session with ${device.name}.")
            return false
        }

        val connectedDevice = device.copy(isConnected = true, isPaired = true)
        _activeDevice.value = connectedDevice
        _pairingRequired.value = false
        _connectionStatus.value = "Connected to ${device.name}"
        saveDevice(connectedDevice)
        addLog("[REPO-RECONNECT-SUCCESS] Paired session resumed with ${device.name}.")
        return true
    }

    private fun persistSavedDevices(devices: List<TvDevice>) {
        preferences?.edit()
            ?.putString(SAVED_DEVICES_KEY, devices.joinToString("\n") { encodeDevice(it.copy(isConnected = false)) })
            ?.commit()
    }

    private fun loadSavedDevices(): List<TvDevice> {
        val raw = preferences?.getString(SAVED_DEVICES_KEY, null) ?: return emptyList()
        return raw.lineSequence()
            .mapNotNull { decodeDevice(it) }
            .toList()
    }

    private fun encodeDevice(device: TvDevice): String {
        val fields = listOf(
            device.id,
            device.name,
            device.ipAddress,
            device.port.toString(),
            device.connectionType.name,
            device.isPaired.toString(),
            device.lastSeenTimestamp.toString()
        )
        return fields.joinToString("|") { field ->
            URLEncoder.encode(field, Charsets.UTF_8.name())
        }
    }

    private fun decodeDevice(encoded: String): TvDevice? {
        return try {
            val fields = encoded.split("|").map { field ->
                URLDecoder.decode(field, Charsets.UTF_8.name())
            }
            if (fields.size < 7) return null
            TvDevice(
                id = fields[0],
                name = fields[1],
                ipAddress = fields[2],
                port = fields[3].toInt(),
                connectionType = ConnectionType.valueOf(fields[4]),
                isConnected = false,
                isPaired = fields[5].toBoolean(),
                lastSeenTimestamp = fields[6].toLong()
            )
        } catch (_: Exception) {
            null
        }
    }

    private fun restoreActiveDevice(restoredDevices: List<TvDevice>) {
        val activeId = preferences?.getString(ACTIVE_DEVICE_ID_KEY, null)
        val active = restoredDevices.firstOrNull { it.id == activeId }
            ?: restoredDevices.filter { it.isPaired }.maxByOrNull { it.lastSeenTimestamp }
            ?: restoredDevices.maxByOrNull { it.lastSeenTimestamp }
        if (active != null) {
            _activeDevice.value = active.copy(isConnected = false)
            _connectionStatus.value = "${active.name} selected"
            saveActiveDeviceId(active.id)
        }
    }

    private fun getActiveSavedDevice(): TvDevice? {
        val activeId = preferences?.getString(ACTIVE_DEVICE_ID_KEY, null) ?: return null
        return _savedDevices.value.firstOrNull { it.id == activeId }
    }

    private fun saveActiveDeviceId(id: String?) {
        val editor = preferences?.edit() ?: return
        if (id == null) {
            editor.remove(ACTIVE_DEVICE_ID_KEY)
        } else {
            editor.putString(ACTIVE_DEVICE_ID_KEY, id)
        }
        editor.commit()
    }

    private fun findMatchingSavedDevice(device: TvDevice): TvDevice? {
        val normalizedName = normalizeDeviceName(device.name)
        return _savedDevices.value.firstOrNull { saved ->
            saved.id == device.id ||
                saved.ipAddress == device.ipAddress ||
                normalizeDeviceName(saved.name) == normalizedName
        }
    }

    private fun mergeDeviceRecords(saved: TvDevice, incoming: TvDevice): TvDevice {
        return saved.copy(
            name = incoming.name.ifBlank { saved.name },
            ipAddress = incoming.ipAddress.ifBlank { saved.ipAddress },
            port = incoming.port,
            connectionType = incoming.connectionType,
            isConnected = false,
            isPaired = saved.isPaired || incoming.isPaired,
            lastSeenTimestamp = maxOf(saved.lastSeenTimestamp, incoming.lastSeenTimestamp, System.currentTimeMillis())
        )
    }

    private fun normalizeDeviceName(name: String): String {
        return name.trim().lowercase(Locale.US)
            .removePrefix("google tv")
            .removePrefix("android tv")
            .trim()
    }

    private fun getEngineForDevice(device: TvDevice): RemoteProtocolEngine {
        return when {
            device.connectionType == ConnectionType.INFRARED -> irEngine ?: hybridEngine
            else -> hybridEngine
        }
    }

    private companion object {
        private const val SAVED_DEVICES_KEY = "saved_devices"
        private const val ACTIVE_DEVICE_ID_KEY = "active_device_id"
    }
}
