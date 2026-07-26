package com.famage.remoconnect.data.discovery

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log
import com.famage.remoconnect.data.model.ConnectionType
import com.famage.remoconnect.data.model.TvDevice
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket

class DeviceDiscoveryService(
    private val context: Context,
    private val logger: ((String) -> Unit)? = null
) {
    private val nsdManager: NsdManager? by lazy {
        context.getSystemService(Context.NSD_SERVICE) as? NsdManager
    }

    private val scope = CoroutineScope(Dispatchers.IO)
    private var scanTimeoutJob: Job? = null

    private val _discoveredDevices = MutableStateFlow<List<TvDevice>>(emptyList())
    val discoveredDevices: StateFlow<List<TvDevice>> = _discoveredDevices.asStateFlow()

    private val _isScanning = MutableStateFlow(false)
    val isScanning: StateFlow<Boolean> = _isScanning.asStateFlow()

    private var discoveryListener: NsdManager.DiscoveryListener? = null

    private fun log(msg: String) {
        try { Log.d("RemoConnect-Discovery", msg) } catch (_: Throwable) {}
        logger?.invoke(msg)
    }

    fun startDiscovery() {
        stopDiscovery()
        _discoveredDevices.value = emptyList()
        _isScanning.value = true

        log("[SCAN-START] Starting mDNS network scan for '_androidtvremote2._tcp.'...")

        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(regType: String) {
                log("[SCAN-LISTENING] mDNS listener active on local Wi-Fi subnet for $regType")
            }

            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                log("[SCAN-FOUND] Raw mDNS service detected: '${serviceInfo.serviceName}' (Type: ${serviceInfo.serviceType}). Resolving IP & Port...")
                nsdManager?.resolveService(serviceInfo, object : NsdManager.ResolveListener {
                    override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                        log("[SCAN-RESOLVE-FAILED] Could not resolve mDNS service '${serviceInfo.serviceName}' (Error code: $errorCode)")
                    }

                    override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                        val host: InetAddress? = serviceInfo.host
                        val ip = host?.hostAddress ?: run {
                            log("[SCAN-RESOLVE-WARN] Resolved service '${serviceInfo.serviceName}' but IP host address was null.")
                            return
                        }
                        val name = serviceInfo.serviceName ?: "Android TV"
                        val port = if (serviceInfo.port > 0) serviceInfo.port else 6466

                        log("[SCAN-RESOLVED] Successfully resolved TV: '$name' -> IP: $ip, Port: $port")

                        scope.launch {
                            val reachable = isHostReachable(ip, port)
                            if (reachable) {
                                log("[SCAN-REACHABLE] Reachability confirmed for $name ($ip:$port). Adding to discovered devices list.")
                                val device = TvDevice(
                                    id = ip,
                                    name = name,
                                    ipAddress = ip,
                                    port = port,
                                    connectionType = ConnectionType.ANDROID_TV_REMOTE_V2,
                                    isConnected = false
                                )
                                addDevice(device)
                            } else {
                                log("[SCAN-UNREACHABLE] Host $ip:$port did not respond to ping test. Still adding for discovery selection.")
                                val device = TvDevice(
                                    id = ip,
                                    name = name,
                                    ipAddress = ip,
                                    port = port,
                                    connectionType = ConnectionType.ANDROID_TV_REMOTE_V2,
                                    isConnected = false
                                )
                                addDevice(device)
                            }
                        }
                    }
                })
            }

            override fun onServiceLost(serviceInfo: NsdServiceInfo) {
                val ip = serviceInfo.host?.hostAddress
                log("[SCAN-LOST] mDNS service lost: '${serviceInfo.serviceName}' (IP: ${ip ?: "unknown"})")
                if (ip != null) {
                    removeDevice(ip)
                }
            }

            override fun onDiscoveryStopped(serviceType: String) {
                log("[SCAN-STOPPED] mDNS network scan stopped for $serviceType")
                _isScanning.value = false
            }

            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                log("[SCAN-START-FAILED] mDNS start failed for $serviceType (Error: $errorCode)")
                nsdManager?.stopServiceDiscovery(this)
                _isScanning.value = false
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                log("[SCAN-STOP-FAILED] mDNS stop failed for $serviceType (Error: $errorCode)")
                _isScanning.value = false
            }
        }

        discoveryListener = listener
        try {
            nsdManager?.discoverServices("_androidtvremote2._tcp.", NsdManager.PROTOCOL_DNS_SD, listener)
            scanTimeoutJob = scope.launch {
                delay(12_000L)
                log("[SCAN-TIMEOUT] 12-second discovery period completed.")
                stopDiscovery()
            }
        } catch (e: Exception) {
            log("[SCAN-ERROR] Discovery invocation error: ${e.localizedMessage}")
            _isScanning.value = false
        }
    }

    fun stopDiscovery() {
        scanTimeoutJob?.cancel()
        scanTimeoutJob = null

        discoveryListener?.let {
            try {
                nsdManager?.stopServiceDiscovery(it)
            } catch (e: Exception) {
                // Ignore if already stopped
            }
        }
        discoveryListener = null
        _isScanning.value = false
    }

    fun clearDiscovered() {
        _discoveredDevices.value = emptyList()
    }

    fun addManualDevice(name: String, ipAddress: String, port: Int = 6466): TvDevice {
        log("[MANUAL-ADD] Adding manual device '$name' at $ipAddress:$port")
        val device = TvDevice(
            id = ipAddress,
            name = if (name.isNotBlank()) name else "TCL Smart TV ($ipAddress)",
            ipAddress = ipAddress,
            port = port,
            connectionType = if (port == 5555) ConnectionType.ADB_WIFI else ConnectionType.ANDROID_TV_REMOTE_V2
        )
        addDevice(device)
        return device
    }

    private fun addDevice(device: TvDevice) {
        val current = _discoveredDevices.value.toMutableList()
        val index = current.indexOfFirst { it.ipAddress == device.ipAddress }
        if (index >= 0) {
            current[index] = device
        } else {
            current.add(device)
        }
        _discoveredDevices.value = current
    }

    private fun removeDevice(ipAddress: String) {
        val current = _discoveredDevices.value.toMutableList()
        current.removeAll { it.ipAddress == ipAddress }
        _discoveredDevices.value = current
    }

    private fun isHostReachable(ip: String, port: Int): Boolean {
        return try {
            val socket = Socket()
            socket.connect(InetSocketAddress(ip, port), 1200)
            socket.close()
            true
        } catch (e: Exception) {
            try {
                val socket = Socket()
                socket.connect(InetSocketAddress(ip, 5555), 1000)
                socket.close()
                true
            } catch (e2: Exception) {
                false
            }
        }
    }
}
