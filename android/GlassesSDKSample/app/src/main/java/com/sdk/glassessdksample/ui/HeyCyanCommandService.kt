package com.sdk.glassessdksample.ui

import android.app.Service
import android.bluetooth.BluetoothDevice
import android.bluetooth.le.ScanResult
import android.content.Context
import android.content.Intent
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pInfo
import android.os.IBinder
import android.os.PowerManager
import com.oudmon.ble.base.bluetooth.BleOperateManager
import com.oudmon.ble.base.bluetooth.DeviceManager
import com.oudmon.ble.base.communication.LargeDataHandler
import com.oudmon.ble.base.communication.bigData.resp.GlassModelControlResponse
import com.oudmon.ble.base.scan.BleScannerHelper
import com.oudmon.ble.base.scan.ScanRecord
import com.oudmon.ble.base.scan.ScanWrapperCallback
import com.sdk.glassessdksample.ui.wifi.p2p.WifiP2pManagerSingleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

class HeyCyanCommandService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var mediaSync: GlassMediaSync
    private var p2pReceiver: android.content.BroadcastReceiver? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var commandIntent: Intent? = null

    override fun onCreate() {
        super.onCreate()
        mediaSync = GlassMediaSync(applicationContext)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        commandIntent = intent
        val command = intent?.getStringExtra(EXTRA_COMMAND).orEmpty().ifBlank { COMMAND_STATUS }
        HeyCyanLogger.info(this, command, "service command received")
        acquireWakeLock()
        scope.launch {
            try {
                runCommand(command)
            } finally {
                releaseWakeLock()
                unregisterP2pReceiver()
                stopSelf(startId)
            }
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        scope.cancel()
        releaseWakeLock()
        unregisterP2pReceiver()
        super.onDestroy()
    }

    private suspend fun runCommand(command: String) {
        try {
            HeyCyanLogger.info(this, command, "command started")
            when (command) {
                COMMAND_STATUS -> logStatus(command)
                COMMAND_SCAN -> scanBle(command)
                COMMAND_CONNECT -> connectBle(command)
                COMMAND_SCAN_CONNECT -> scanAndConnect(command)
                COMMAND_DISCONNECT -> disconnectBle(command)
                COMMAND_CAPTURE -> capture(command)
                COMMAND_SYNC -> syncAll(command)
                COMMAND_CAPTURE_SYNC -> captureSync(command)
                else -> HeyCyanLogger.warn(this, command, "unknown command")
            }
            HeyCyanLogger.info(this, command, "command finished")
        } catch (e: CancellationException) {
            HeyCyanLogger.warn(this, command, "command cancelled", e)
            throw e
        } catch (e: Exception) {
            HeyCyanLogger.error(this, command, "command failed", e)
        }
    }

    private fun logStatus(command: String) {
        val connected = BleOperateManager.getInstance().isConnected
        val ready = BleOperateManager.getInstance().isReady
        val knownAddress = DeviceManager.getInstance().deviceAddress.orEmpty()
        HeyCyanLogger.info(this, command, "bleConnected=$connected bleReady=$ready knownAddress=$knownAddress")
    }

    private suspend fun scanBle(command: String): List<SmartWatch> {
        val seconds = commandIntent?.getIntExtra(EXTRA_SECONDS, DEFAULT_SCAN_SECONDS)
            ?.coerceIn(1, 60)
            ?: DEFAULT_SCAN_SECONDS
        val devices = scanBleDevices(command, seconds)
        HeyCyanLogger.info(this, command, "scan summary count=${devices.size}")
        devices.forEachIndexed { index, device ->
            HeyCyanLogger.info(
                this,
                command,
                "scan result index=$index name=${device.deviceName} address=${device.deviceAddress} rssi=${device.rssi}"
            )
        }
        return devices
    }

    private suspend fun connectBle(command: String) {
        val address = commandIntent?.getStringExtra(EXTRA_ADDRESS)
            ?: DeviceManager.getInstance().deviceAddress.orEmpty()
        if (address.isBlank()) {
            HeyCyanLogger.warn(this, command, "NO_BLE_ADDRESS")
            return
        }
        connectToAddress(command, address)
    }

    private suspend fun scanAndConnect(command: String) {
        val seconds = commandIntent?.getIntExtra(EXTRA_SECONDS, DEFAULT_SCAN_SECONDS)
            ?.coerceIn(1, 60)
            ?: DEFAULT_SCAN_SECONDS
        val targetAddress = commandIntent?.getStringExtra(EXTRA_ADDRESS)?.takeIf { it.isNotBlank() }
        val nameContains = commandIntent?.getStringExtra(EXTRA_NAME_CONTAINS)?.takeIf { it.isNotBlank() }
        val devices = scanBleDevices(command, seconds)
        val target = devices.firstOrNull { device ->
            targetAddress != null && device.deviceAddress.equals(targetAddress, ignoreCase = true)
        } ?: devices.firstOrNull { device ->
            nameContains != null && device.deviceName.contains(nameContains, ignoreCase = true)
        } ?: devices.firstOrNull()

        val address = target?.deviceAddress
        if (address.isNullOrBlank()) {
            HeyCyanLogger.warn(
                this,
                command,
                "NO_SCAN_TARGET count=${devices.size} address=${targetAddress.orEmpty()} nameContains=${nameContains.orEmpty()}"
            )
            return
        }
        HeyCyanLogger.info(this, command, "scan target name=${target.deviceName} address=${target.deviceAddress} rssi=${target.rssi}")
        connectToAddress(command, address)
    }

    private suspend fun disconnectBle(command: String) {
        withContext(Dispatchers.Main) {
            BleOperateManager.getInstance().unBindDevice()
        }
        delay(1000L)
        HeyCyanLogger.info(this, command, "disconnect requested bleConnected=${BleOperateManager.getInstance().isConnected}")
    }

    private suspend fun connectToAddress(command: String, address: String) {
        HeyCyanLogger.info(this, command, "connecting address=$address")
        withContext(Dispatchers.Main) {
            BleOperateManager.getInstance().connectDirectly(address)
        }
        val connected = waitForBleReady()
        HeyCyanLogger.info(
            this,
            command,
            "connect result connected=$connected bleConnected=${BleOperateManager.getInstance().isConnected} bleReady=${BleOperateManager.getInstance().isReady}"
        )
    }

    private suspend fun waitForBleReady(): Boolean {
        repeat(40) {
            if (BleOperateManager.getInstance().isConnected && BleOperateManager.getInstance().isReady) {
                return true
            }
            delay(500L)
        }
        return BleOperateManager.getInstance().isConnected
    }

    private suspend fun scanBleDevices(command: String, seconds: Int): List<SmartWatch> {
        val devices = linkedSetOf<SmartWatch>()
        val callback = object : ScanWrapperCallback {
            override fun onStart() {
                HeyCyanLogger.info(this@HeyCyanCommandService, command, "ble scan started seconds=$seconds")
            }

            override fun onStop() {
                HeyCyanLogger.info(this@HeyCyanCommandService, command, "ble scan stopped count=${devices.size}")
            }

            override fun onLeScan(device: BluetoothDevice?, rssi: Int, scanRecord: ByteArray?) {
                val parsedName = scanRecord?.let { ScanRecord.parseFromBytes(it).deviceName }
                recordScanDevice(devices, device, parsedName, rssi)
            }

            override fun onScanFailed(errorCode: Int) {
                HeyCyanLogger.warn(this@HeyCyanCommandService, command, "ble scan failed errorCode=$errorCode")
            }

            override fun onParsedData(device: BluetoothDevice?, scanRecord: ScanRecord?) {
                recordScanDevice(devices, device, scanRecord?.deviceName, 0)
            }
            override fun onBatchScanResults(results: MutableList<ScanResult>?) = Unit
        }

        withContext(Dispatchers.Main) {
            BleScannerHelper.getInstance().reSetCallback()
            BleScannerHelper.getInstance().scanDevice(this@HeyCyanCommandService, null, callback)
        }
        delay(seconds * 1000L)
        withContext(Dispatchers.Main) {
            BleScannerHelper.getInstance().stopScan(this@HeyCyanCommandService)
        }
        return devices.sortedByDescending { it.rssi }
    }

    private fun recordScanDevice(
        devices: MutableSet<SmartWatch>,
        device: BluetoothDevice?,
        advertisedName: String?,
        rssi: Int
    ) {
        val address = device?.address ?: return
        val name = device.name?.takeIf { it.isNotBlank() }
            ?: advertisedName?.takeIf { it.isNotBlank() }
            ?: "(unnamed)"
        devices.add(SmartWatch(name, address, rssi))
    }

    private suspend fun capture(command: String): GlassModelControlResponse? {
        if (!BleOperateManager.getInstance().isConnected) {
            HeyCyanLogger.warn(this, command, "BLE_NOT_CONNECTED")
            return null
        }
        HeyCyanLogger.info(this, command, "sending photo command")
        val response = glassesControl(byteArrayOf(0x02, 0x01, 0x01))
        HeyCyanLogger.info(
            this,
            command,
            "capture response type=${response?.dataType} error=${response?.errorCode} work=${response?.workTypeIng} p2pIp=${response?.p2pIp}"
        )
        return response
    }

    private suspend fun captureSync(command: String) {
        val beforeIp = resolveDeviceIp(null)
        val before = beforeIp?.let { ip ->
            runCatching { mediaSync.fetchJpgNames(ip) }
                .onFailure { HeyCyanLogger.warn(this, command, "baseline fetch failed ip=$ip", it) }
                .getOrNull()
        }
        HeyCyanLogger.info(this, command, "baseline count=${before?.size ?: -1}")

        val captureResponse = capture(command) ?: return
        delay(3000L)

        val ip = resolveDeviceIp(captureResponse.p2pIp) ?: run {
            HeyCyanLogger.warn(this, command, "NO_P2P_IP_AFTER_CAPTURE")
            return
        }
        val current = runCatching { mediaSync.fetchJpgNames(ip) }
            .onFailure { HeyCyanLogger.warn(this, command, "media list fetch failed ip=$ip", it) }
            .getOrNull()
            ?: return
        val targets = before?.let { current - it } ?: emptySet()
        HeyCyanLogger.info(this, command, "current=${current.size} new=${targets.size}")
        if (before == null) {
            HeyCyanLogger.warn(this, command, "sync skipped because baseline was unavailable")
            return
        }
        saveTargets(command, ip, targets)
    }

    private suspend fun syncAll(command: String) {
        val ip = resolveDeviceIp(null) ?: run {
            HeyCyanLogger.warn(this, command, "NO_P2P_IP")
            return
        }
        val targets = runCatching { mediaSync.fetchJpgNames(ip) }
            .onFailure { HeyCyanLogger.warn(this, command, "media list fetch failed ip=$ip", it) }
            .getOrNull()
            ?: return
        HeyCyanLogger.info(this, command, "sync all count=${targets.size}")
        saveTargets(command, ip, targets)
    }

    private suspend fun saveTargets(command: String, ip: String, targets: Set<String>) {
        if (targets.isEmpty()) {
            HeyCyanLogger.info(this, command, "no files to save")
            return
        }
        var saved = 0
        var deleted = 0
        var deletePending = 0
        targets.forEach { fileName ->
            runCatching { mediaSync.saveAndDelete(ip, fileName) }
                .onSuccess { result ->
                    saved++
                    when (result) {
                        is GlassMediaSync.SyncResult.SavedAndDeleted -> {
                            deleted++
                            HeyCyanLogger.info(this, command, "saved_and_deleted file=${result.fileName} uri=${result.uri}")
                        }
                        is GlassMediaSync.SyncResult.SavedDeleteFailed -> {
                            deletePending++
                            HeyCyanLogger.warn(this, command, "saved_delete_pending file=${result.fileName} uri=${result.uri}")
                        }
                    }
                }
                .onFailure { HeyCyanLogger.warn(this, command, "save failed file=$fileName", it) }
        }
        HeyCyanLogger.info(this, command, "sync summary saved=$saved deleted=$deleted deletePending=$deletePending")
    }

    private suspend fun resolveDeviceIp(preferredIp: String?): String? {
        preferredIp?.takeIf { it.isNotBlank() && it != "0.0.0.0" }?.let {
            HeyCyanLogger.info(this, "resolve_ip", "using preferred ip=$it")
            return it
        }
        val p2pReady = ensureP2pGroup()
        HeyCyanLogger.info(this, "resolve_ip", "p2pGroupReady=$p2pReady")
        val mediaState = glassesControl(byteArrayOf(0x02, 0x04))
        HeyCyanLogger.info(
            this,
            "resolve_ip",
            "media state type=${mediaState?.dataType} images=${mediaState?.imageCount} videos=${mediaState?.videoCount} records=${mediaState?.recordCount} p2pIp=${mediaState?.p2pIp} error=${mediaState?.errorCode}"
        )
        return mediaState?.p2pIp?.takeIf { it.isNotBlank() && it != "0.0.0.0" }
    }

    private suspend fun glassesControl(command: ByteArray): GlassModelControlResponse? {
        return withTimeoutOrNull(15000L) {
            suspendCancellableCoroutine { continuation ->
                LargeDataHandler.getInstance().glassesControl(command) { _, response ->
                    if (continuation.isActive) {
                        continuation.resume(response)
                    }
                }
            }
        }
    }

    private suspend fun ensureP2pGroup(): Boolean {
        val manager = WifiP2pManagerSingleton.getInstance(applicationContext)
        if (p2pReceiver == null) {
            p2pReceiver = manager.registerReceiver()
        }
        manager.addCallback(p2pCallback)
        return withTimeoutOrNull(10000L) {
            suspendCancellableCoroutine { continuation ->
                manager.createGroup { success ->
                    if (continuation.isActive) {
                        continuation.resume(success)
                    }
                }
            }
        } ?: false
    }

    private fun unregisterP2pReceiver() {
        val manager = WifiP2pManagerSingleton.getInstance(applicationContext)
        manager.removeCallback(p2pCallback)
        p2pReceiver?.let { manager.unregisterReceiver(it) }
        p2pReceiver = null
    }

    private fun acquireWakeLock() {
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "HeyCyan:Command").apply {
            setReferenceCounted(false)
            acquire(WAKE_LOCK_TIMEOUT_MS)
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.takeIf { it.isHeld }?.release()
        wakeLock = null
    }

    private val p2pCallback = object : WifiP2pManagerSingleton.WifiP2pCallback {
        override fun onWifiP2pEnabled() = Unit
        override fun onWifiP2pDisabled() = Unit
        override fun onPeersChanged(peers: Collection<WifiP2pDevice>) = Unit
        override fun onThisDeviceChanged(device: WifiP2pDevice) = Unit
        override fun onConnected(info: WifiP2pInfo) = Unit
        override fun onDisconnected() = Unit
        override fun onPeerDiscoveryStarted() = Unit
        override fun onPeerDiscoveryFailed(reason: Int) = Unit
        override fun onConnectRequestSent() = Unit
        override fun onConnectRequestFailed(reason: Int) = Unit
        override fun connecting() = Unit
        override fun cancelConnect() = Unit
        override fun cancelConnectFail(reason: Int) = Unit
        override fun retryAlsoFailed() = Unit
    }

    companion object {
        const val EXTRA_COMMAND = "command"
        const val EXTRA_ADDRESS = "address"
        const val EXTRA_NAME_CONTAINS = "name_contains"
        const val EXTRA_SECONDS = "seconds"
        const val COMMAND_STATUS = "status"
        const val COMMAND_SCAN = "scan"
        const val COMMAND_CONNECT = "connect"
        const val COMMAND_SCAN_CONNECT = "scan_connect"
        const val COMMAND_DISCONNECT = "disconnect"
        const val COMMAND_CAPTURE = "capture"
        const val COMMAND_SYNC = "sync"
        const val COMMAND_CAPTURE_SYNC = "capture_sync"
        private const val DEFAULT_SCAN_SECONDS = 15
        private const val WAKE_LOCK_TIMEOUT_MS = 10 * 60 * 1000L
    }
}
