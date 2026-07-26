package com.famage.remoconnect.ui.viewmodel

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.famage.remoconnect.data.model.RemoteKey
import com.famage.remoconnect.data.model.TvDevice
import com.famage.remoconnect.data.repository.TvDeviceRepository
import com.famage.remoconnect.data.updater.AppUpdateManager
import com.famage.remoconnect.data.updater.UpdateInfo
import com.famage.remoconnect.data.updater.UpdateState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

enum class AppTab {
    REMOTE,
    TOUCHPAD,
    STREAM,
    APPS,
    DISCOVERY
}

class RemoteViewModel(
    private val repository: TvDeviceRepository
) : ViewModel() {

    val activeDevice: StateFlow<TvDevice?> = repository.activeDevice
    val connectionStatus: StateFlow<String> = repository.connectionStatus
    val pairingRequired: StateFlow<Boolean> = repository.pairingRequired
    val savedDevices: StateFlow<List<TvDevice>> = repository.savedDevices
    val discoveredDevices: StateFlow<List<TvDevice>> = repository.getDiscoveredDevices()
    val isScanning: StateFlow<Boolean> = repository.isScanning()
    val debugLogs: StateFlow<List<String>> = repository.debugLogs

    private val _connectingDeviceIp = MutableStateFlow<String?>(null)
    val connectingDeviceIp: StateFlow<String?> = _connectingDeviceIp.asStateFlow()

    private val _selectedTab = MutableStateFlow(AppTab.REMOTE)
    val selectedTab: StateFlow<AppTab> = _selectedTab.asStateFlow()

    private val _showKeyboardDialog = MutableStateFlow(false)
    val showKeyboardDialog: StateFlow<Boolean> = _showKeyboardDialog.asStateFlow()

    private val _showPairingDialog = MutableStateFlow(false)
    val showPairingDialog: StateFlow<Boolean> = _showPairingDialog.asStateFlow()

    private val _pairingErrorMessage = MutableStateFlow<String?>(null)
    val pairingErrorMessage: StateFlow<String?> = _pairingErrorMessage.asStateFlow()

    private val _lastPressedKey = MutableStateFlow<RemoteKey?>(null)
    val lastPressedKey: StateFlow<RemoteKey?> = _lastPressedKey.asStateFlow()

    private val _isMuted = MutableStateFlow(false)
    val isMuted: StateFlow<Boolean> = _isMuted.asStateFlow()

    private val _volumeLevel = MutableStateFlow(15)
    val volumeLevel: StateFlow<Int> = _volumeLevel.asStateFlow()

    // One-shot event: UI observes this to launch the system speech recognizer
    private val _triggerVoiceInput = MutableStateFlow(false)
    val triggerVoiceInput: StateFlow<Boolean> = _triggerVoiceInput.asStateFlow()

    // In-App Update state
    private val _updateState = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val updateState: StateFlow<UpdateState> = _updateState.asStateFlow()

    private val _showUpdateDialog = MutableStateFlow(false)
    val showUpdateDialog: StateFlow<Boolean> = _showUpdateDialog.asStateFlow()

    init {
        syncDiscoveredDevices()
        reconnectLastPairedDevice()
    }

    fun selectTab(tab: AppTab) {
        _selectedTab.value = tab
    }

    fun startDeviceScan() {
        repository.startScanning()
    }

    fun stopDeviceScan() {
        repository.stopScanning()
    }

    fun connectDevice(device: TvDevice) {
        _connectingDeviceIp.value = device.ipAddress
        _pairingErrorMessage.value = null

        viewModelScope.launch {
            try {
                val success = repository.connectToDevice(device)
                if (success && repository.pairingRequired.value) {
                    _showPairingDialog.value = true
                }
            } finally {
                _connectingDeviceIp.value = null
            }
        }
    }

    fun selectDevice(device: TvDevice) {
        repository.selectActiveDevice(device)
    }

    private fun reconnectLastPairedDevice() {
        val device = repository.getLastPairedDevice() ?: return
        _connectingDeviceIp.value = device.ipAddress
        viewModelScope.launch {
            try {
                repository.reconnectActiveDevice()
                if (repository.pairingRequired.value) {
                    _showPairingDialog.value = true
                }
            } finally {
                _connectingDeviceIp.value = null
            }
        }
    }

    fun submitPairingPin(pin: String) {
        viewModelScope.launch {
            val verified = repository.verifyPairingPin(pin)
            if (verified) {
                _showPairingDialog.value = false
                _pairingErrorMessage.value = null
            } else {
                _pairingErrorMessage.value = "Incorrect PIN code. Please try again."
            }
        }
    }

    fun dismissPairingDialog() {
        _showPairingDialog.value = false
        val current = repository.activeDevice.value
        if (current != null && !current.isConnected) {
            viewModelScope.launch {
                repository.disconnectCurrentDevice()
            }
        }
    }

    fun disconnectCurrent() {
        viewModelScope.launch {
            repository.disconnectCurrentDevice()
        }
    }

    fun forgetDevice(device: TvDevice) {
        viewModelScope.launch {
            val active = repository.activeDevice.value
            if (active?.id == device.id || active?.ipAddress == device.ipAddress) {
                repository.disconnectCurrentDevice()
            }
            repository.forgetDevice(device)
        }
    }

    fun addManualTv(name: String, ipAddress: String, port: Int = 6466) {
        val newDevice = repository.addManualTv(name, ipAddress, port)
        connectDevice(newDevice)
    }

    fun pressKey(key: RemoteKey) {
        _lastPressedKey.value = key

        when (key) {
            RemoteKey.VOLUME_UP -> {
                if (_isMuted.value) {
                    _isMuted.value = false
                }
                val newVol = (_volumeLevel.value + 1).coerceAtMost(100)
                _volumeLevel.value = newVol
            }
            RemoteKey.VOLUME_DOWN -> {
                val newVol = (_volumeLevel.value - 1).coerceAtLeast(0)
                _volumeLevel.value = newVol
                if (newVol == 0) {
                    _isMuted.value = true
                } else if (_isMuted.value) {
                    _isMuted.value = false
                }
            }
            RemoteKey.MUTE -> {
                _isMuted.value = !_isMuted.value
            }
            else -> {}
        }

        viewModelScope.launch {
            repository.sendRemoteKey(key)
        }
    }

    fun toggleMute() {
        pressKey(RemoteKey.MUTE)
    }

    fun startVoiceInput() {
        _triggerVoiceInput.value = true
    }

    fun onVoiceInputConsumed() {
        _triggerVoiceInput.value = false
    }

    fun sendVoiceQuery(text: String) {
        // Send voice search key first, then the recognised text as keyboard input
        pressKey(RemoteKey.VOICE_ASSISTANT)
        viewModelScope.launch {
            repository.sendTextInput(text)
        }
    }

    fun sendText(text: String) {
        viewModelScope.launch {
            repository.sendTextInput(text)
            _showKeyboardDialog.value = false
        }
    }

    fun sendSwipe(deltaX: Float, deltaY: Float) {
        viewModelScope.launch {
            repository.sendTouchpadSwipe(deltaX, deltaY)
        }
    }

    fun openKeyboardDialog() {
        _showKeyboardDialog.value = true
    }

    fun dismissKeyboardDialog() {
        _showKeyboardDialog.value = false
    }

    fun clearDebugLogs() {
        repository.clearDebugLogs()
    }

    private fun syncDiscoveredDevices() {
        viewModelScope.launch {
            discoveredDevices.collect { devices ->
                devices.forEach { repository.mergeDiscoveredDevice(it) }
            }
        }
    }

    fun openUpdateDialog() {
        _showUpdateDialog.value = true
    }

    fun dismissUpdateDialog() {
        _showUpdateDialog.value = false
    }

    fun checkForUpdates(
        context: Context,
        currentVersionCode: Int = 1,
        currentVersionName: String = "1.0",
        customUrl: String? = null
    ) {
        _showUpdateDialog.value = true
        _updateState.value = UpdateState.Checking

        viewModelScope.launch {
            try {
                val updateManager = AppUpdateManager(context)
                val newInfo = updateManager.checkForUpdates(currentVersionCode, customUrl)
                if (newInfo != null) {
                    _updateState.value = UpdateState.Available(newInfo)
                } else {
                    _updateState.value = UpdateState.UpToDate(currentVersionName, currentVersionCode)
                }
            } catch (e: Exception) {
                _updateState.value = UpdateState.Error("Failed to check for updates: ${e.localizedMessage ?: "Unknown error"}")
            }
        }
    }

    fun downloadUpdate(context: Context) {
        val currentState = _updateState.value
        if (currentState !is UpdateState.Available) return

        val updateInfo = currentState.info
        _updateState.value = UpdateState.Downloading(updateInfo, 0)

        viewModelScope.launch {
            try {
                val updateManager = AppUpdateManager(context)
                val apkFile = updateManager.downloadApk(updateInfo) { progress ->
                    _updateState.value = UpdateState.Downloading(updateInfo, progress)
                }
                _updateState.value = UpdateState.ReadyToInstall(updateInfo, apkFile)
            } catch (e: Exception) {
                _updateState.value = UpdateState.Error("Download failed: ${e.localizedMessage ?: "Unknown error"}")
            }
        }
    }

    fun installUpdate(context: Context) {
        val currentState = _updateState.value
        if (currentState !is UpdateState.ReadyToInstall) return

        try {
            val updateManager = AppUpdateManager(context)
            updateManager.installApk(currentState.apkFile)
        } catch (e: Exception) {
            _updateState.value = UpdateState.Error("Installation failed: ${e.localizedMessage ?: "Unknown error"}")
        }
    }
}

