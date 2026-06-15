package com.sdk.glassessdksample.ui

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import androidx.core.app.NotificationCompat
import com.oudmon.ble.base.bluetooth.BleOperateManager
import com.oudmon.ble.base.communication.LargeDataHandler
import com.oudmon.ble.base.communication.bigData.resp.GlassModelControlResponse
import com.oudmon.ble.base.communication.bigData.resp.GlassesDeviceNotifyListener
import com.oudmon.ble.base.communication.bigData.resp.GlassesDeviceNotifyRsp
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
        val command = intent?.getStringExtra(EXTRA_COMMAND).orEmpty()
        logInfo(command.ifBlank { "unknown" }, "service command received")

        return when (command) {
            COMMAND_PERIODICAL_CAPTURE -> {
                startPeriodicalCaptureJob(intent)
                START_STICKY
            }
            COMMAND_PERIODICAL_CAPTURE_STOP -> {
                stopPeriodicalCapture()
                periodicalCaptureJob?.cancel()
                if (periodicalCaptureJob?.isActive != true) {
                    stopSelf(startId)
                }
                START_NOT_STICKY
            }
            COMMAND_SYNC_MEDIA_ALL -> {
                scope.launch {
                    try {
                        syncAllMedia()
                    } finally {
                        unregisterTransferNotifyListener()
                        unregisterP2pReceiver()
                        stopSelf(startId)
                    }
                }
                START_NOT_STICKY
            }
            else -> {
                logWarn(command.ifBlank { "unknown" }, "unsupported service command")
                stopSelf(startId)
                START_NOT_STICKY
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        scope.cancel()
        releaseWakeLock()
        unregisterTransferNotifyListener()
        unregisterP2pReceiver()
        super.onDestroy()
    }

    private fun logInfo(command: String, message: String) {
        Log.i(TAG, "[$command] $message")
        broadcastProgress(command, message)
    }

    private fun logWarn(command: String, message: String, throwable: Throwable? = null) {
        Log.w(TAG, "[$command] $message", throwable)
        broadcastProgress(command, "WARN: $message")
    }

    private fun logError(command: String, message: String, throwable: Throwable? = null) {
        Log.e(TAG, "[$command] $message", throwable)
        broadcastProgress(command, "ERROR: $message")
    }

    private fun broadcastProgress(command: String, message: String) {
        LocalBroadcastManager.getInstance(this).sendBroadcast(
            Intent(ACTION_MEDIA_SYNC_PROGRESS)
                .putExtra(EXTRA_PROGRESS_LINE, "[$command] $message")
        )
    }

    private fun startPeriodicalCaptureJob(intent: Intent?) {
        if (periodicalCaptureJob?.isActive == true) {
            logWarn(COMMAND_PERIODICAL_CAPTURE, "periodical_capture already running")
            return
        }
        commandIntent = intent
        acquireWakeLock()
        startForeground(NOTIFICATION_ID, buildPeriodicalCaptureNotification())
        periodicalCaptureJob = scope.launch {
            try {
                runPeriodicalCapture()
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

    private suspend fun runPeriodicalCapture() {
        try {
            logInfo(COMMAND_PERIODICAL_CAPTURE, "command started")
            periodicalCapture()
            logInfo(COMMAND_PERIODICAL_CAPTURE, "command finished")
        } catch (e: CancellationException) {
            logWarn(COMMAND_PERIODICAL_CAPTURE, "command cancelled", e)
            throw e
        } catch (e: Exception) {
            logError(COMMAND_PERIODICAL_CAPTURE, "command failed", e)
        }
    }

    private suspend fun capture(): GlassModelControlResponse? {
        if (!BleOperateManager.getInstance().isConnected) {
            logWarn(COMMAND_PERIODICAL_CAPTURE, "BLE_NOT_CONNECTED")
            return null
        }
        logInfo(COMMAND_PERIODICAL_CAPTURE, "sending photo command")
        val response = glassesControl(byteArrayOf(0x02, 0x01, 0x01))
        logInfo(
            COMMAND_PERIODICAL_CAPTURE,
            "capture response type=${response?.dataType} error=${response?.errorCode} work=${response?.workTypeIng} p2pIp=${response?.p2pIp}"
        )
        return response
    }

    private suspend fun captureSync() {
        val captureResponse = capture() ?: return
        delay(5000L)

        val ip = resolveDeviceIp(captureResponse.p2pIp) ?: run {
            logWarn(COMMAND_PERIODICAL_CAPTURE, "NO_P2P_IP_AFTER_CAPTURE")
            return
        }
        val targets = runCatching { mediaSync.fetchJpgNames(ip) }
            .onFailure { logWarn(COMMAND_PERIODICAL_CAPTURE, "media list fetch failed ip=$ip", it) }
            .getOrNull()
            ?: return
        logInfo(COMMAND_PERIODICAL_CAPTURE, "sync after capture count=${targets.size}")
        saveTargets(ip, targets)
    }

    private suspend fun syncAllMedia() {
        if (!BleOperateManager.getInstance().isConnected) {
            logWarn(COMMAND_SYNC_MEDIA_ALL, "BLE_NOT_CONNECTED")
            return
        }
        val ip = resolveDeviceIp(null) ?: run {
            logWarn(COMMAND_SYNC_MEDIA_ALL, "NO_P2P_IP")
            return
        }
        val mediaConfig = runCatching { mediaSync.fetchMediaConfig(ip) }
            .onFailure { logWarn(COMMAND_SYNC_MEDIA_ALL, "media list fetch failed ip=$ip", it) }
            .getOrNull()
            ?: return
        logInfo(COMMAND_SYNC_MEDIA_ALL, "media.config raw=${mediaConfig.raw.toLogPreview()}")
        val targets = mediaConfig.fileNames
        val mp4Targets = targets.filter { it.endsWith(".mp4", ignoreCase = true) }
        if (mp4Targets.isEmpty()) {
            logWarn(COMMAND_SYNC_MEDIA_ALL, "no mp4 filenames in media.config")
        } else {
            logInfo(COMMAND_SYNC_MEDIA_ALL, "mp4 filenames=${mp4Targets.joinToString(",")}")
        }
        logInfo(COMMAND_SYNC_MEDIA_ALL, "sync all media count=${targets.size}")
        saveTargets(ip, targets, COMMAND_SYNC_MEDIA_ALL)
    }

    private suspend fun periodicalCapture() {
        val intervalSeconds = commandIntent?.getIntExtra(EXTRA_SECONDS, DEFAULT_LOOP_SECONDS)
            ?.coerceIn(1, 24 * 60 * 60)
            ?: DEFAULT_LOOP_SECONDS
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        if (prefs.getBoolean(PREF_PERIODICAL_CAPTURE_RUNNING, false)) {
            logWarn(COMMAND_PERIODICAL_CAPTURE, "periodical_capture already running")
            return
        }
        prefs.edit().putBoolean(PREF_PERIODICAL_CAPTURE_RUNNING, true).apply()
        logInfo(COMMAND_PERIODICAL_CAPTURE, "periodical_capture started intervalSeconds=$intervalSeconds")

        var cycle = 0
        val intervalMs = intervalSeconds * 1000L
        try {
            while (scope.isActive && prefs.getBoolean(PREF_PERIODICAL_CAPTURE_RUNNING, false)) {
                cycle++
                val startedAt = SystemClock.elapsedRealtime()
                logInfo(COMMAND_PERIODICAL_CAPTURE, "periodical_capture cycle started cycle=$cycle")
                runCatching { captureSync() }
                    .onFailure { logWarn(COMMAND_PERIODICAL_CAPTURE, "periodical_capture cycle failed cycle=$cycle", it) }

                val elapsedMs = SystemClock.elapsedRealtime() - startedAt
                val waitMs = (intervalMs - elapsedMs).coerceAtLeast(0L)
                val overrunMs = (elapsedMs - intervalMs).coerceAtLeast(0L)
                logInfo(
                    COMMAND_PERIODICAL_CAPTURE,
                    "periodical_capture cycle finished cycle=$cycle elapsedMs=$elapsedMs waitMs=$waitMs overrunMs=$overrunMs"
                )
                waitForNextCycle(waitMs, prefs)
            }
        } finally {
            prefs.edit().putBoolean(PREF_PERIODICAL_CAPTURE_RUNNING, false).apply()
            logInfo(COMMAND_PERIODICAL_CAPTURE, "periodical_capture stopped")
        }
    }

    private suspend fun waitForNextCycle(waitMs: Long, prefs: android.content.SharedPreferences) {
        var remaining = waitMs
        while (remaining > 0L && scope.isActive && prefs.getBoolean(PREF_PERIODICAL_CAPTURE_RUNNING, false)) {
            val step = remaining.coerceAtMost(1000L)
            delay(step)
            remaining -= step
        }
    }

    private fun stopPeriodicalCapture() {
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            .edit()
            .putBoolean(PREF_PERIODICAL_CAPTURE_RUNNING, false)
            .apply()
        logInfo(COMMAND_PERIODICAL_CAPTURE_STOP, "periodical_capture stop requested")
    }

    private suspend fun saveOnly(
        ip: String,
        targets: Set<String>,
        command: String = COMMAND_PERIODICAL_CAPTURE
    ): List<String> {
        if (targets.isEmpty()) {
            logInfo(command, "no files to save")
            return emptyList()
        }
        var currentIp = ip
        val savedFiles = mutableListOf<String>()

        targets.forEach { fileName ->
            val uri = runCatching { mediaSync.save(currentIp, fileName) }
                .recoverCatching { firstError ->
                    logWarn(command, "save failed once file=$fileName; reconnecting P2P", firstError)
                    currentIp = resolveDeviceIp(null) ?: throw firstError
                    mediaSync.save(currentIp, fileName)
                }
                .onFailure { logWarn(command, "save failed file=$fileName", it) }
                .getOrNull()

            if (uri != null) {
                savedFiles.add(fileName)
                logInfo(command, "saved file=$fileName uri=$uri")
            }
        }
        logInfo(command, "save summary saved=${savedFiles.size}")
        return savedFiles
    }

    private suspend fun saveTargets(
        ip: String,
        targets: Set<String>,
        command: String = COMMAND_PERIODICAL_CAPTURE
    ) {
        if (targets.isEmpty()) {
            logInfo(command, "no files to save")
            return
        }
        val savedFiles = saveOnly(ip, targets, command)
        var deleted = 0
        var deletePending = 0

        savedFiles.forEach { fileName ->
            logInfo(command, "deleting remote file=$fileName")
            runCatching { mediaSync.delete(fileName) }
                .onSuccess { deleteOk ->
                    if (deleteOk) {
                        deleted++
                        logInfo(command, "deleted remote file=$fileName")
                    } else {
                        deletePending++
                        logWarn(command, "delete response timed out file=$fileName; next sync will verify")
                    }
                }
                .onFailure {
                    deletePending++
                    logWarn(command, "delete failed file=$fileName", it)
                }
        }
        logInfo(command, "sync summary saved=${savedFiles.size} deleted=$deleted deletePending=$deletePending")
    }

    private suspend fun resetTransferForDelete() {
        logInfo(COMMAND_PERIODICAL_CAPTURE, "resetting P2P before remote delete")
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
            logInfo("resolve_ip", "using preferred ip=$it")
            return it
        }
        val ipWaiter = CompletableDeferred<String>()
        transferIp = ipWaiter
        transferP2pConnected = CompletableDeferred()
        registerTransferNotifyListener()
        ensureP2pDiscovery()

        val transferState = glassesControl(byteArrayOf(0x02, 0x01, 0x04))
        logInfo(
            "resolve_ip",
            "transfer command type=${transferState?.dataType} images=${transferState?.imageCount} videos=${transferState?.videoCount} records=${transferState?.recordCount} p2pIp=${transferState?.p2pIp} error=${transferState?.errorCode}"
        )

        transferState?.p2pIp?.takeIf { it.isNotBlank() && it != "0.0.0.0" }?.let {
            logInfo("resolve_ip", "using transfer response ip=$it")
            return it
        }

        val ip = withTimeoutOrNull(45000L) { ipWaiter.await() }
        if (ip.isNullOrBlank()) {
            logWarn("resolve_ip", "NO_BLE_WIFI_IP_NOTIFY")
            return null
        }
        withTimeoutOrNull(10000L) { transferP2pConnected?.await() }
        logInfo("resolve_ip", "using notify ip=$ip")
        return ip
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

    private fun String.toLogPreview(maxLength: Int = 700): String {
        val compact = lineSequence()
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .joinToString("|")
        return if (compact.length <= maxLength) compact else compact.take(maxLength) + "...(truncated)"
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
        logInfo("resolve_ip", "stale p2p group removed=$removedStaleGroup")
        delay(1000L)
        manager.startPeerDiscovery()
        logInfo("resolve_ip", "p2p discovery started")
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
            logInfo("resolve_ip", "registered transfer notify listener")
        }.onFailure {
            logWarn("resolve_ip", "failed to register transfer notify listener", it)
        }
    }

    private fun unregisterTransferNotifyListener() {
        if (!transferNotifyRegistered) return
        runCatching {
            LargeDataHandler.getInstance().removeOutDeviceListener(2)
            logInfo("resolve_ip", "unregistered transfer notify listener")
        }.onFailure {
            logWarn("resolve_ip", "failed to unregister transfer notify listener", it)
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
            logInfo("resolve_ip", "p2p peers count=${peers.size}")
            val target = peers.firstOrNull { peer ->
                val name = peer.deviceName.orEmpty()
                name.contains("Music", ignoreCase = true) ||
                    name.contains("Cyan", ignoreCase = true) ||
                    name.contains("Glass", ignoreCase = true)
            } ?: peers.firstOrNull()
            if (target != null) {
                logInfo(
                    "resolve_ip",
                    "p2p connect target name=${target.deviceName} address=${target.deviceAddress}"
                )
                WifiP2pManagerSingleton.getInstance(applicationContext).connectToDevice(target)
            }
        }
        override fun onThisDeviceChanged(device: WifiP2pDevice) = Unit
        override fun onConnected(info: WifiP2pInfo) {
            logInfo(
                "resolve_ip",
                "p2p connected groupFormed=${info.groupFormed} isGroupOwner=${info.isGroupOwner} groupOwnerIp=${info.groupOwnerAddress?.hostAddress}"
            )
            transferP2pConnected?.takeIf { !it.isCompleted }?.complete(Unit)
        }
        override fun onDisconnected() {
            logInfo("resolve_ip", "p2p disconnected")
        }
        override fun onPeerDiscoveryStarted() = Unit
        override fun onPeerDiscoveryFailed(reason: Int) {
            logWarn("resolve_ip", "p2p discovery failed reason=$reason")
        }
        override fun onConnectRequestSent() {
            logInfo("resolve_ip", "p2p connect request sent")
        }
        override fun onConnectRequestFailed(reason: Int) {
            logWarn("resolve_ip", "p2p connect request failed reason=$reason")
        }
        override fun connecting() {
            logInfo("resolve_ip", "p2p already connecting")
        }
        override fun cancelConnect() {
            logInfo("resolve_ip", "p2p cancel connect")
        }
        override fun cancelConnectFail(reason: Int) {
            logWarn("resolve_ip", "p2p cancel connect failed reason=$reason")
        }
        override fun retryAlsoFailed() {
            logWarn("resolve_ip", "p2p retry also failed")
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
                        logInfo("resolve_ip", "BLE reported WiFi IP: $ip")
                        transferIp?.takeIf { !it.isCompleted }?.complete(ip)
                    }
                }
                0x09 -> {
                    val error = load.getOrNull(7)?.toInt()?.and(0xFF)
                    logWarn("resolve_ip", "P2P/WiFi notify error=$error")
                    if (error == 255) {
                        WifiP2pManagerSingleton.getInstance(applicationContext).resetDeviceP2p()
                    }
                }
            }
        }
    }

    companion object {
        private const val TAG = "HeyCyanCmd"
        const val ACTION_COMMAND = "com.sdk.glassessdksample.COMMAND"
        const val EXTRA_COMMAND = "command"
        const val EXTRA_SECONDS = "seconds"
        const val ACTION_MEDIA_SYNC_PROGRESS = "com.sdk.glassessdksample.MEDIA_SYNC_PROGRESS"
        const val EXTRA_PROGRESS_LINE = "progress_line"
        const val COMMAND_PERIODICAL_CAPTURE = "periodical_capture"
        const val COMMAND_PERIODICAL_CAPTURE_STOP = "periodical_capture_stop"
        const val COMMAND_SYNC_MEDIA_ALL = "sync_media_all"
        private const val NOTIFICATION_CHANNEL_ID = "heycyan_periodical_capture"
        private const val NOTIFICATION_ID = 1001
        private const val PREFS_NAME = "heycyan_command"
        private const val PREF_PERIODICAL_CAPTURE_RUNNING = "periodical_capture_running"
        private const val DEFAULT_LOOP_SECONDS = 60
        private const val WAKE_LOCK_TIMEOUT_MS = 10 * 60 * 1000L
    }
}
