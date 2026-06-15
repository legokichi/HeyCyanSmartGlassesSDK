package com.sdk.glassessdksample.ui

import android.app.Service
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.bluetooth.BluetoothDevice
import android.bluetooth.le.ScanResult
import android.content.Context
import android.content.Intent
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import com.oudmon.ble.base.bluetooth.BleOperateManager
import com.oudmon.ble.base.bluetooth.DeviceManager
import com.oudmon.ble.base.communication.LargeDataHandler
import com.oudmon.ble.base.communication.bigData.resp.GlassModelControlResponse
import com.oudmon.ble.base.communication.bigData.resp.GlassesDeviceNotifyListener
import com.oudmon.ble.base.communication.bigData.resp.GlassesDeviceNotifyRsp
import com.oudmon.ble.base.scan.BleScannerHelper
import com.oudmon.ble.base.scan.ScanRecord
import com.oudmon.ble.base.scan.ScanWrapperCallback
import com.sdk.glassessdksample.MainActivity
import com.sdk.glassessdksample.R
import com.sdk.glassessdksample.ui.wifi.p2p.WifiP2pManagerSingleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
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
    private var transferIp: CompletableDeferred<String>? = null
    private var transferP2pConnected: CompletableDeferred<Unit>? = null
    private var transferNotifyRegistered = false
    private var periodicalCaptureJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        mediaSync = GlassMediaSync(applicationContext)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val command = intent?.getStringExtra(EXTRA_COMMAND).orEmpty().ifBlank { COMMAND_STATUS }
        HeyCyanLogger.info(this, command, "service command received")

        if (command == COMMAND_PERIODICAL_CAPTURE) {
            startPeriodicalCaptureJob(intent)
            return START_STICKY
        }

        if (command == COMMAND_PERIODICAL_CAPTURE_STOP) {
            stopPeriodicalCapture(command)
            periodicalCaptureJob?.cancel()
            if (periodicalCaptureJob?.isActive != true) {
                stopSelf(startId)
            }
            return START_NOT_STICKY
        }

        if (periodicalCaptureJob?.isActive == true) {
            HeyCyanLogger.warn(this, command, "command ignored while periodical_capture is running")
            return START_STICKY
        }

        commandIntent = intent
        acquireWakeLock()
        scope.launch {
            try {
                runCommand(command)
            } finally {
                releaseWakeLock()
                unregisterTransferNotifyListener()
                unregisterP2pReceiver()
                if (periodicalCaptureJob?.isActive != true) {
                    stopSelf(startId)
                }
            }
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        scope.cancel()
        releaseWakeLock()
        unregisterTransferNotifyListener()
        unregisterP2pReceiver()
        super.onDestroy()
    }

    private fun startPeriodicalCaptureJob(intent: Intent?) {
        if (periodicalCaptureJob?.isActive == true) {
            HeyCyanLogger.warn(this, COMMAND_PERIODICAL_CAPTURE, "periodical_capture already running")
            return
        }
        commandIntent = intent
        acquireWakeLock()
        startForeground(NOTIFICATION_ID, buildPeriodicalCaptureNotification())
        periodicalCaptureJob = scope.launch {
            try {
                runCommand(COMMAND_PERIODICAL_CAPTURE)
            } finally {
                releaseWakeLock()
                unregisterTransferNotifyListener()
                unregisterP2pReceiver()
                stopForegroundCompat()
                periodicalCaptureJob = null
                stopSelf()
            }
        }
    }

    private fun buildPeriodicalCaptureNotification(): Notification {
        createNotificationChannel()
        val pendingIntentFlags = PendingIntent.FLAG_UPDATE_CURRENT or
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0
        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            pendingIntentFlags
        )
        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("HeyCyan periodical capture")
            .setContentText("Taking and syncing photos in the background")
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            NOTIFICATION_CHANNEL_ID,
            "Periodical capture",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Background periodical capture status"
        }
        getSystemService(NotificationManager::class.java)
            .createNotificationChannel(channel)
    }

    @Suppress("DEPRECATION")
    private fun stopForegroundCompat() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            stopForeground(true)
        }
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
                COMMAND_TRANSFER_IP -> transferIp(command)
                COMMAND_LIST_FILES -> listFiles(command)
                COMMAND_SAVE_FILES -> saveFiles(command)
                COMMAND_DELETE_FILES -> deleteFiles(command)
                COMMAND_RESET_P2P -> resetP2p(command)
                COMMAND_SYNC -> syncAll(command)
                COMMAND_CAPTURE_SYNC -> captureSync(command)
                COMMAND_PERIODICAL_CAPTURE -> periodicalCapture(command)
                COMMAND_PERIODICAL_CAPTURE_STOP -> stopPeriodicalCapture(command)
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
        val target = when {
            targetAddress != null -> devices.firstOrNull { device ->
                device.deviceAddress.equals(targetAddress, ignoreCase = true)
            }
            nameContains != null -> devices.firstOrNull { device ->
                device.deviceName.contains(nameContains, ignoreCase = true)
            }
            else -> devices.firstOrNull()
        }

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
        val captureResponse = capture(command) ?: return
        delay(5000L)

        val ip = resolveDeviceIp(captureResponse.p2pIp)?.also { rememberIp(it) } ?: run {
            HeyCyanLogger.warn(this, command, "NO_P2P_IP_AFTER_CAPTURE")
            return
        }
        val targets = runCatching { mediaSync.fetchJpgNames(ip) }
            .onFailure { HeyCyanLogger.warn(this, command, "media list fetch failed ip=$ip", it) }
            .getOrNull()
            ?: return
        HeyCyanLogger.info(this, command, "sync after capture count=${targets.size}")
        saveTargets(command, ip, targets)
    }

    private suspend fun periodicalCapture(command: String) {
        val intervalSeconds = commandIntent?.getIntExtra(EXTRA_SECONDS, DEFAULT_LOOP_SECONDS)
            ?.coerceIn(1, 24 * 60 * 60)
            ?: DEFAULT_LOOP_SECONDS
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        if (prefs.getBoolean(PREF_PERIODICAL_CAPTURE_RUNNING, false)) {
            HeyCyanLogger.warn(this, command, "periodical_capture already running")
            return
        }
        prefs.edit().putBoolean(PREF_PERIODICAL_CAPTURE_RUNNING, true).apply()
        HeyCyanLogger.info(this, command, "periodical_capture started intervalSeconds=$intervalSeconds")

        var cycle = 0
        val intervalMs = intervalSeconds * 1000L
        try {
            while (scope.isActive && prefs.getBoolean(PREF_PERIODICAL_CAPTURE_RUNNING, false)) {
                cycle++
                val startedAt = SystemClock.elapsedRealtime()
                HeyCyanLogger.info(this, command, "periodical_capture cycle started cycle=$cycle")
                runCatching { captureSync(command) }
                    .onFailure { HeyCyanLogger.warn(this, command, "periodical_capture cycle failed cycle=$cycle", it) }

                val elapsedMs = SystemClock.elapsedRealtime() - startedAt
                val waitMs = (intervalMs - elapsedMs).coerceAtLeast(0L)
                val overrunMs = (elapsedMs - intervalMs).coerceAtLeast(0L)
                HeyCyanLogger.info(
                    this,
                    command,
                    "periodical_capture cycle finished cycle=$cycle elapsedMs=$elapsedMs waitMs=$waitMs overrunMs=$overrunMs"
                )
                if (waitMs > 0L) {
                    var remaining = waitMs
                    while (remaining > 0L && scope.isActive && prefs.getBoolean(PREF_PERIODICAL_CAPTURE_RUNNING, false)) {
                        val step = remaining.coerceAtMost(1000L)
                        delay(step)
                        remaining -= step
                    }
                }
            }
        } finally {
            prefs.edit().putBoolean(PREF_PERIODICAL_CAPTURE_RUNNING, false).apply()
            HeyCyanLogger.info(this, command, "periodical_capture stopped")
        }
    }

    private fun stopPeriodicalCapture(command: String) {
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            .edit()
            .putBoolean(PREF_PERIODICAL_CAPTURE_RUNNING, false)
            .apply()
        HeyCyanLogger.info(this, command, "periodical_capture stop requested")
    }

    private suspend fun transferIp(command: String) {
        val ip = resolveDeviceIp(commandIntent?.getStringExtra(EXTRA_IP)) ?: run {
            HeyCyanLogger.warn(this, command, "NO_P2P_IP")
            return
        }
        rememberIp(ip)
        HeyCyanLogger.info(this, command, "transfer ip=$ip")
    }

    private suspend fun listFiles(command: String): Set<String> {
        val names = fetchFileNames(command) ?: return emptySet()
        HeyCyanLogger.info(this, command, "file count=${names.size}")
        names.forEachIndexed { index, fileName ->
            HeyCyanLogger.info(this, command, "file index=$index name=$fileName")
        }
        rememberFiles(names)
        return names
    }

    private suspend fun saveFiles(command: String) {
        val requestedFile = commandIntent?.getStringExtra(EXTRA_FILE)?.takeIf { it.isNotBlank() }
        val targets = requestedFile?.let { setOf(it) } ?: fetchFileNames(command) ?: return
        val ip = activeIp(command) ?: return
        HeyCyanLogger.info(this, command, "save count=${targets.size}")
        saveOnly(command, ip, targets)
    }

    private suspend fun deleteFiles(command: String) {
        val requestedFile = commandIntent?.getStringExtra(EXTRA_FILE)?.takeIf { it.isNotBlank() }
        val targets = requestedFile?.let { setOf(it) }
            ?: fetchFileNames(command)
            ?: rememberedFiles()
        if (targets.isEmpty()) {
            HeyCyanLogger.info(this, command, "no files to delete")
            return
        }

        resetTransferForDelete(command)
        var deleted = 0
        var deletePending = 0
        targets.forEach { fileName ->
            runCatching { mediaSync.delete(fileName) }
                .onSuccess { deleteOk ->
                    if (deleteOk) {
                        deleted++
                        HeyCyanLogger.info(this, command, "deleted remote file=$fileName")
                    } else {
                        deletePending++
                        HeyCyanLogger.warn(this, command, "delete response timed out file=$fileName; next list/sync will verify")
                    }
                }
                .onFailure {
                    deletePending++
                    HeyCyanLogger.warn(this, command, "delete failed file=$fileName", it)
                }
        }
        HeyCyanLogger.info(this, command, "delete summary deleted=$deleted deletePending=$deletePending")
    }

    private suspend fun resetP2p(command: String) {
        resetTransferForDelete(command)
        HeyCyanLogger.info(this, command, "p2p reset requested")
    }

    private suspend fun syncAll(command: String) {
        val ip = resolveDeviceIp(null)?.also { rememberIp(it) } ?: run {
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

    private suspend fun saveOnly(command: String, ip: String, targets: Set<String>): List<String> {
        if (targets.isEmpty()) {
            HeyCyanLogger.info(this, command, "no files to save")
            return emptyList()
        }
        var currentIp = ip
        val savedFiles = mutableListOf<String>()

        targets.forEach { fileName ->
            val uri = runCatching { mediaSync.save(currentIp, fileName) }
                .recoverCatching { firstError ->
                    HeyCyanLogger.warn(this, command, "save failed once file=$fileName; reconnecting P2P", firstError)
                    currentIp = resolveDeviceIp(null)?.also { rememberIp(it) } ?: throw firstError
                    mediaSync.save(currentIp, fileName)
                }
                .onFailure { HeyCyanLogger.warn(this, command, "save failed file=$fileName", it) }
                .getOrNull()

            if (uri != null) {
                savedFiles.add(fileName)
                HeyCyanLogger.info(this, command, "saved file=$fileName uri=$uri")
            }
        }
        HeyCyanLogger.info(this, command, "save summary saved=${savedFiles.size}")
        return savedFiles
    }

    private suspend fun saveTargets(command: String, ip: String, targets: Set<String>) {
        if (targets.isEmpty()) {
            HeyCyanLogger.info(this, command, "no files to save")
            return
        }
        val savedFiles = saveOnly(command, ip, targets)
        var deleted = 0
        var deletePending = 0

        if (savedFiles.isNotEmpty()) {
            resetTransferForDelete(command)
        }

        savedFiles.forEach { fileName ->
            runCatching { mediaSync.delete(fileName) }
                .onSuccess { deleteOk ->
                    if (deleteOk) {
                        deleted++
                        HeyCyanLogger.info(this, command, "deleted remote file=$fileName")
                    } else {
                        deletePending++
                        HeyCyanLogger.warn(this, command, "delete response timed out file=$fileName; next sync will verify")
                    }
                }
                .onFailure {
                    deletePending++
                    HeyCyanLogger.warn(this, command, "delete failed file=$fileName", it)
                }
        }
        HeyCyanLogger.info(this, command, "sync summary saved=${savedFiles.size} deleted=$deleted deletePending=$deletePending")
    }

    private suspend fun activeIp(command: String): String? {
        commandIntent?.getStringExtra(EXTRA_IP)?.takeIf { it.isNotBlank() }?.let {
            rememberIp(it)
            return it
        }
        rememberedIp()?.let { return it }
        return resolveDeviceIp(null)?.also { rememberIp(it) } ?: run {
            HeyCyanLogger.warn(this, command, "NO_P2P_IP")
            null
        }
    }

    private suspend fun fetchFileNames(command: String): Set<String>? {
        val firstIp = activeIp(command) ?: return null
        val names = runCatching { mediaSync.fetchJpgNames(firstIp) }
            .recoverCatching { firstError ->
                HeyCyanLogger.warn(this, command, "media list fetch failed once ip=$firstIp; reconnecting P2P", firstError)
                val retryIp = resolveDeviceIp(null)?.also { rememberIp(it) } ?: throw firstError
                mediaSync.fetchJpgNames(retryIp)
            }
            .onFailure { HeyCyanLogger.warn(this, command, "media list fetch failed", it) }
            .getOrNull()
        names?.let { rememberFiles(it) }
        return names
    }

    private suspend fun resetTransferForDelete(command: String) {
        HeyCyanLogger.info(this, command, "resetting P2P before remote delete")
        unregisterTransferNotifyListener()
        unregisterP2pReceiver()
        val manager = WifiP2pManagerSingleton.getInstance(applicationContext)
        manager.resetDeviceP2p()
        withTimeoutOrNull(5000L) {
            suspendCancellableCoroutine { continuation ->
                manager.removeGroup { success ->
                    if (continuation.isActive) {
                        continuation.resume(success)
                    }
                }
            }
        }
        delay(3000L)
    }

    private suspend fun resolveDeviceIp(preferredIp: String?): String? {
        preferredIp?.takeIf { it.isNotBlank() && it != "0.0.0.0" }?.let {
            HeyCyanLogger.info(this, "resolve_ip", "using preferred ip=$it")
            rememberIp(it)
            return it
        }
        val ipWaiter = CompletableDeferred<String>()
        transferIp = ipWaiter
        transferP2pConnected = CompletableDeferred()
        registerTransferNotifyListener()
        ensureP2pDiscovery()

        val transferState = glassesControl(byteArrayOf(0x02, 0x01, 0x04))
        HeyCyanLogger.info(
            this,
            "resolve_ip",
            "transfer command type=${transferState?.dataType} images=${transferState?.imageCount} videos=${transferState?.videoCount} records=${transferState?.recordCount} p2pIp=${transferState?.p2pIp} error=${transferState?.errorCode}"
        )

        transferState?.p2pIp?.takeIf { it.isNotBlank() && it != "0.0.0.0" }?.let {
            HeyCyanLogger.info(this, "resolve_ip", "using transfer response ip=$it")
            rememberIp(it)
            return it
        }

        val ip = withTimeoutOrNull(45000L) { ipWaiter.await() }
        if (ip.isNullOrBlank()) {
            HeyCyanLogger.warn(this, "resolve_ip", "NO_BLE_WIFI_IP_NOTIFY")
            return null
        }
        withTimeoutOrNull(10000L) { transferP2pConnected?.await() }
        HeyCyanLogger.info(this, "resolve_ip", "using notify ip=$ip")
        rememberIp(ip)
        return ip
    }

    private fun rememberIp(ip: String) {
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            .edit()
            .putString(PREF_LAST_IP, ip)
            .apply()
    }

    private fun rememberedIp(): String? {
        return getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            .getString(PREF_LAST_IP, null)
            ?.takeIf { it.isNotBlank() && it != "0.0.0.0" }
    }

    private fun rememberFiles(files: Set<String>) {
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            .edit()
            .putStringSet(PREF_LAST_FILES, files)
            .apply()
    }

    private fun rememberedFiles(): Set<String> {
        return getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            .getStringSet(PREF_LAST_FILES, emptySet())
            .orEmpty()
            .toSet()
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

    private suspend fun ensureP2pDiscovery() {
        val manager = WifiP2pManagerSingleton.getInstance(applicationContext)
        if (p2pReceiver == null) {
            p2pReceiver = manager.registerReceiver()
        }
        manager.addCallback(p2pCallback)
        manager.resetFailCount()
        val removedStaleGroup = withTimeoutOrNull(5000L) {
            suspendCancellableCoroutine { continuation ->
                manager.removeGroup { success ->
                    if (continuation.isActive) {
                        continuation.resume(success)
                    }
                }
            }
        } ?: false
        HeyCyanLogger.info(this, "resolve_ip", "stale p2p group removed=$removedStaleGroup")
        delay(1000L)
        manager.startPeerDiscovery()
        HeyCyanLogger.info(this, "resolve_ip", "p2p discovery started")
    }

    private fun unregisterP2pReceiver() {
        val manager = WifiP2pManagerSingleton.getInstance(applicationContext)
        manager.removeCallback(p2pCallback)
        p2pReceiver?.let { manager.unregisterReceiver(it) }
        p2pReceiver = null
    }

    private fun registerTransferNotifyListener() {
        if (transferNotifyRegistered) return
        runCatching {
            LargeDataHandler.getInstance().addOutDeviceListener(2, transferNotifyListener)
            transferNotifyRegistered = true
            HeyCyanLogger.info(this, "resolve_ip", "registered transfer notify listener")
        }.onFailure {
            HeyCyanLogger.warn(this, "resolve_ip", "failed to register transfer notify listener", it)
        }
    }

    private fun unregisterTransferNotifyListener() {
        if (!transferNotifyRegistered) return
        runCatching {
            LargeDataHandler.getInstance().removeOutDeviceListener(2)
            HeyCyanLogger.info(this, "resolve_ip", "unregistered transfer notify listener")
        }.onFailure {
            HeyCyanLogger.warn(this, "resolve_ip", "failed to unregister transfer notify listener", it)
        }
        transferNotifyRegistered = false
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
        override fun onPeersChanged(peers: Collection<WifiP2pDevice>) {
            HeyCyanLogger.info(this@HeyCyanCommandService, "resolve_ip", "p2p peers count=${peers.size}")
            val target = peers.firstOrNull { peer ->
                val name = peer.deviceName.orEmpty()
                name.contains("Music", ignoreCase = true) ||
                    name.contains("Cyan", ignoreCase = true) ||
                    name.contains("Glass", ignoreCase = true)
            } ?: peers.firstOrNull()
            if (target != null) {
                HeyCyanLogger.info(
                    this@HeyCyanCommandService,
                    "resolve_ip",
                    "p2p connect target name=${target.deviceName} address=${target.deviceAddress}"
                )
                WifiP2pManagerSingleton.getInstance(applicationContext).connectToDevice(target)
            }
        }
        override fun onThisDeviceChanged(device: WifiP2pDevice) = Unit
        override fun onConnected(info: WifiP2pInfo) {
            HeyCyanLogger.info(
                this@HeyCyanCommandService,
                "resolve_ip",
                "p2p connected groupFormed=${info.groupFormed} isGroupOwner=${info.isGroupOwner} groupOwnerIp=${info.groupOwnerAddress?.hostAddress}"
            )
            transferP2pConnected?.takeIf { !it.isCompleted }?.complete(Unit)
        }
        override fun onDisconnected() {
            HeyCyanLogger.info(this@HeyCyanCommandService, "resolve_ip", "p2p disconnected")
        }
        override fun onPeerDiscoveryStarted() = Unit
        override fun onPeerDiscoveryFailed(reason: Int) {
            HeyCyanLogger.warn(this@HeyCyanCommandService, "resolve_ip", "p2p discovery failed reason=$reason")
        }
        override fun onConnectRequestSent() {
            HeyCyanLogger.info(this@HeyCyanCommandService, "resolve_ip", "p2p connect request sent")
        }
        override fun onConnectRequestFailed(reason: Int) {
            HeyCyanLogger.warn(this@HeyCyanCommandService, "resolve_ip", "p2p connect request failed reason=$reason")
        }
        override fun connecting() {
            HeyCyanLogger.info(this@HeyCyanCommandService, "resolve_ip", "p2p already connecting")
        }
        override fun cancelConnect() {
            HeyCyanLogger.info(this@HeyCyanCommandService, "resolve_ip", "p2p cancel connect")
        }
        override fun cancelConnectFail(reason: Int) {
            HeyCyanLogger.warn(this@HeyCyanCommandService, "resolve_ip", "p2p cancel connect failed reason=$reason")
        }
        override fun retryAlsoFailed() {
            HeyCyanLogger.warn(this@HeyCyanCommandService, "resolve_ip", "p2p retry also failed")
        }
    }

    private val transferNotifyListener = object : GlassesDeviceNotifyListener() {
        override fun parseData(cmdType: Int, response: GlassesDeviceNotifyRsp) {
            val load = response.loadData
            if (load.size < 7) return
            when (load[6].toInt() and 0xFF) {
                0x08 -> {
                    if (load.size >= 11) {
                        val ip = "${load[7].toInt() and 0xFF}.${load[8].toInt() and 0xFF}.${load[9].toInt() and 0xFF}.${load[10].toInt() and 0xFF}"
                        HeyCyanLogger.info(this@HeyCyanCommandService, "resolve_ip", "BLE reported WiFi IP: $ip")
                        transferIp?.takeIf { !it.isCompleted }?.complete(ip)
                    }
                }
                0x09 -> {
                    val error = load.getOrNull(7)?.toInt()?.and(0xFF)
                    HeyCyanLogger.warn(this@HeyCyanCommandService, "resolve_ip", "P2P/WiFi notify error=$error")
                    if (error == 255) {
                        WifiP2pManagerSingleton.getInstance(applicationContext).resetDeviceP2p()
                    }
                }
            }
        }
    }

    companion object {
        const val ACTION_COMMAND = "com.sdk.glassessdksample.COMMAND"
        const val EXTRA_COMMAND = "command"
        const val EXTRA_ADDRESS = "address"
        const val EXTRA_NAME_CONTAINS = "name_contains"
        const val EXTRA_SECONDS = "seconds"
        const val EXTRA_IP = "ip"
        const val EXTRA_FILE = "file"
        const val COMMAND_STATUS = "status"
        const val COMMAND_SCAN = "scan"
        const val COMMAND_CONNECT = "connect"
        const val COMMAND_SCAN_CONNECT = "scan_connect"
        const val COMMAND_DISCONNECT = "disconnect"
        const val COMMAND_CAPTURE = "capture"
        const val COMMAND_TRANSFER_IP = "transfer_ip"
        const val COMMAND_LIST_FILES = "list_files"
        const val COMMAND_SAVE_FILES = "save_files"
        const val COMMAND_DELETE_FILES = "delete_files"
        const val COMMAND_RESET_P2P = "reset_p2p"
        const val COMMAND_SYNC = "sync"
        const val COMMAND_CAPTURE_SYNC = "capture_sync"
        const val COMMAND_PERIODICAL_CAPTURE = "periodical_capture"
        const val COMMAND_PERIODICAL_CAPTURE_STOP = "periodical_capture_stop"
        private const val NOTIFICATION_CHANNEL_ID = "heycyan_periodical_capture"
        private const val NOTIFICATION_ID = 1001
        private const val PREFS_NAME = "heycyan_command"
        private const val PREF_LAST_IP = "last_ip"
        private const val PREF_LAST_FILES = "last_files"
        private const val PREF_PERIODICAL_CAPTURE_RUNNING = "periodical_capture_running"
        private const val DEFAULT_SCAN_SECONDS = 15
        private const val DEFAULT_LOOP_SECONDS = 60
        private const val WAKE_LOCK_TIMEOUT_MS = 10 * 60 * 1000L
    }
}
