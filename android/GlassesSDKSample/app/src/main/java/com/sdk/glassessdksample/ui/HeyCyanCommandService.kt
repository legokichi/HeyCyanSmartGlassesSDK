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
import com.oudmon.ble.base.bluetooth.DeviceManager
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
import java.util.Locale

class HeyCyanCommandService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var mediaSync: GlassMediaSync
    private var p2pReceiver: android.content.BroadcastReceiver? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var commandIntent: Intent? = null
    private var transferIp: CompletableDeferred<String>? = null
    private var transferP2pConnected: CompletableDeferred<Unit>? = null
    private var transferNotifyRegistered = false
    private var periodicCaptureJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        mediaSync = GlassMediaSync(applicationContext)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val command = intent?.getStringExtra(EXTRA_COMMAND).orEmpty()
        logInfo(command.ifBlank { "unknown" }, "service command received")

        return when (command) {
            COMMAND_PERIODIC_CAPTURE -> {
                startPeriodicCaptureJob(intent, COMMAND_PERIODIC_CAPTURE)
                START_STICKY
            }
            COMMAND_PERIODIC_CAPTURE_ONLY -> {
                startPeriodicCaptureJob(intent, COMMAND_PERIODIC_CAPTURE_ONLY)
                START_STICKY
            }
            COMMAND_PERIODIC_CAPTURE_STOP -> {
                stopPeriodicCapture()
                periodicCaptureJob?.cancel()
                if (periodicCaptureJob?.isActive != true) {
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
            COMMAND_SYNC_MEDIA_ALL_BATCHED_DELETE -> {
                scope.launch {
                    try {
                        syncAllMediaBatchedDelete()
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

    private fun startPeriodicCaptureJob(intent: Intent?, command: String) {
        if (periodicCaptureJob?.isActive == true) {
            logWarn(command, "periodic_capture already running")
            return
        }
        commandIntent = intent
        acquireWakeLock()
        startForeground(NOTIFICATION_ID, buildPeriodicCaptureNotification(command))
        periodicCaptureJob = scope.launch {
            try {
                runPeriodicCapture(command)
            } finally {
                releaseWakeLock()
                unregisterTransferNotifyListener()
                unregisterP2pReceiver()
                stopForegroundCompat()
                periodicCaptureJob = null
                stopSelf()
            }
        }
    }

    private fun buildPeriodicCaptureNotification(command: String): Notification {
        createNotificationChannel()
        val pendingIntentFlags = PendingIntent.FLAG_UPDATE_CURRENT or
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0
        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            pendingIntentFlags
        )
        val contentText = if (command == COMMAND_PERIODIC_CAPTURE_ONLY) {
            "Taking photos in the background without syncing"
        } else {
            "Taking and syncing photos in the background"
        }
        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("HeyCyan periodic capture")
            .setContentText(contentText)
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
            "Periodic capture",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Background periodic capture status"
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

    private suspend fun runPeriodicCapture(command: String) {
        try {
            logInfo(command, "command started")
            periodicCapture(command)
            logInfo(command, "command finished")
        } catch (e: CancellationException) {
            logWarn(command, "command cancelled", e)
            throw e
        } catch (e: Exception) {
            logError(command, "command failed", e)
        }
    }

    private suspend fun capture(command: String = COMMAND_PERIODIC_CAPTURE): GlassModelControlResponse? {
        if (!BleOperateManager.getInstance().isConnected) {
            logWarn(command, "BLE_NOT_CONNECTED")
            return null
        }
        logInfo(command, "sending photo command")
        val response = glassesControl(byteArrayOf(0x02, 0x01, 0x01))
        logInfo(
            command,
            "capture response type=${response?.dataType} error=${response?.errorCode} work=${response?.workTypeIng} p2pIp=${response?.p2pIp}"
        )
        return response
    }

    private suspend fun captureSync(command: String = COMMAND_PERIODIC_CAPTURE) {
        val captureResponse = capture(command) ?: return
        delay(5000L)

        val ip = resolveDeviceIp(captureResponse.p2pIp) ?: run {
            logWarn(command, "NO_P2P_IP_AFTER_CAPTURE")
            return
        }
        val targets = runCatching { mediaSync.fetchJpgNames(ip) }
            .onFailure { logWarn(command, "media list fetch failed ip=$ip", it) }
            .getOrNull()
            ?: return
        logInfo(command, "sync after capture count=${targets.size}")
        saveTargets(ip, targets, command)
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
        val videoTargets = targets.filter { it.isVideoCandidate() }
        val audioTargets = targets.filter { it.isAudioCandidate() }
        if (videoTargets.isEmpty()) {
            logWarn(COMMAND_SYNC_MEDIA_ALL, "no video filenames in media.config")
        } else {
            logInfo(COMMAND_SYNC_MEDIA_ALL, "video filenames=${videoTargets.joinToString(",")}")
        }
        if (audioTargets.isNotEmpty()) {
            logInfo(COMMAND_SYNC_MEDIA_ALL, "audio filenames=${audioTargets.joinToString(",")}")
        }
        logInfo(COMMAND_SYNC_MEDIA_ALL, "sync all media count=${targets.size}")
        saveTargets(ip, targets, COMMAND_SYNC_MEDIA_ALL)
    }

    private suspend fun syncAllMediaBatchedDelete() {
        if (!BleOperateManager.getInstance().isConnected) {
            logWarn(COMMAND_SYNC_MEDIA_ALL_BATCHED_DELETE, "BLE_NOT_CONNECTED")
            return
        }

        var currentIp = resolveDeviceIp(null) ?: run {
            logWarn(COMMAND_SYNC_MEDIA_ALL_BATCHED_DELETE, "NO_P2P_IP")
            return
        }
        val mediaConfig = runCatching { mediaSync.fetchMediaConfig(currentIp) }
            .onFailure {
                logWarn(
                    COMMAND_SYNC_MEDIA_ALL_BATCHED_DELETE,
                    "media list fetch failed ip=$currentIp",
                    it
                )
            }
            .getOrNull()
            ?: return
        logInfo(COMMAND_SYNC_MEDIA_ALL_BATCHED_DELETE, "media.config raw=${mediaConfig.raw.toLogPreview()}")

        val targets = mediaConfig.fileNames.toList()
        if (targets.isEmpty()) {
            logInfo(COMMAND_SYNC_MEDIA_ALL_BATCHED_DELETE, "no files to save")
            return
        }

        val batches = targets.chunked(BATCHED_DELETE_SIZE)
        var totalSaved = 0
        var totalDeleteRequested = 0
        var totalDeleteRequestFailed = 0
        logInfo(
            COMMAND_SYNC_MEDIA_ALL_BATCHED_DELETE,
            "sync all media batched count=${targets.size} batchSize=$BATCHED_DELETE_SIZE batches=${batches.size}"
        )

        for ((index, batch) in batches.withIndex()) {
            if (index > 0) {
                val nextIp = resolveDeviceIp(null)
                if (nextIp == null) {
                    logWarn(
                        COMMAND_SYNC_MEDIA_ALL_BATCHED_DELETE,
                        "NO_P2P_IP before batch=${index + 1}; stopping"
                    )
                    break
                }
                currentIp = nextIp
            }

            val batchSet = batch.toCollection(linkedSetOf())
            logInfo(
                COMMAND_SYNC_MEDIA_ALL_BATCHED_DELETE,
                "batch started batch=${index + 1}/${batches.size} count=${batchSet.size} files=${batchSet.joinToString(",")}"
            )
            val savedFiles = saveOnly(currentIp, batchSet, COMMAND_SYNC_MEDIA_ALL_BATCHED_DELETE)
            totalSaved += savedFiles.size

            if (savedFiles.isEmpty()) {
                logWarn(
                    COMMAND_SYNC_MEDIA_ALL_BATCHED_DELETE,
                    "batch saved no files batch=${index + 1}; skipping delete"
                )
                continue
            }

            // download2 deletes only the files saved in the current batch. This avoids deleting
            // a full media.config manifest if a later batch fails to save.
            prepareRemoteDelete(COMMAND_SYNC_MEDIA_ALL_BATCHED_DELETE)
            val deleteResult = deleteSavedFiles(savedFiles, COMMAND_SYNC_MEDIA_ALL_BATCHED_DELETE)
            totalDeleteRequested += deleteResult.requested
            totalDeleteRequestFailed += deleteResult.failed
            logInfo(
                COMMAND_SYNC_MEDIA_ALL_BATCHED_DELETE,
                "batch finished batch=${index + 1}/${batches.size} saved=${savedFiles.size} deleteRequested=${deleteResult.requested} deleteRequestFailed=${deleteResult.failed}"
            )
        }

        logInfo(
            COMMAND_SYNC_MEDIA_ALL_BATCHED_DELETE,
            "batched sync summary saved=$totalSaved deleteRequested=$totalDeleteRequested deleteRequestFailed=$totalDeleteRequestFailed"
        )
    }

    private suspend fun periodicCapture(command: String) {
        val intervalSeconds = commandIntent?.getIntExtra(EXTRA_SECONDS, DEFAULT_LOOP_SECONDS)
            ?.coerceIn(1, 24 * 60 * 60)
            ?: DEFAULT_LOOP_SECONDS
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        prefs.edit().putBoolean(PREF_PERIODIC_CAPTURE_RUNNING, true).apply()
        logInfo(command, "periodic_capture started intervalSeconds=$intervalSeconds")

        var cycle = 0
        val intervalMs = intervalSeconds * 1000L
        var nextStartAt = SystemClock.elapsedRealtime()
        try {
            while (scope.isActive && prefs.getBoolean(PREF_PERIODIC_CAPTURE_RUNNING, false)) {
                cycle++
                val scheduledAt = nextStartAt
                val startedAt = SystemClock.elapsedRealtime()
                val lateMs = (startedAt - scheduledAt).coerceAtLeast(0L)
                logInfo(command, "periodic_capture cycle started cycle=$cycle lateMs=$lateMs")
                runCatching {
                    if (command == COMMAND_PERIODIC_CAPTURE_ONLY) {
                        capture(command)
                    } else {
                        captureSync(command)
                    }
                }.onFailure { logWarn(command, "periodic_capture cycle failed cycle=$cycle", it) }

                val elapsedMs = SystemClock.elapsedRealtime() - startedAt
                nextStartAt = scheduledAt + intervalMs
                var skippedCycles = 0
                val finishedAt = SystemClock.elapsedRealtime()
                while (nextStartAt <= finishedAt) {
                    nextStartAt += intervalMs
                    skippedCycles++
                }
                val waitMs = (nextStartAt - finishedAt).coerceAtLeast(0L)
                val overrunMs = (elapsedMs - intervalMs).coerceAtLeast(0L)
                logInfo(
                    command,
                    "periodic_capture cycle finished cycle=$cycle elapsedMs=$elapsedMs waitMs=$waitMs overrunMs=$overrunMs skippedCycles=$skippedCycles"
                )
                waitForNextCycle(waitMs, prefs)
            }
        } finally {
            prefs.edit().putBoolean(PREF_PERIODIC_CAPTURE_RUNNING, false).apply()
            logInfo(command, "periodic_capture stopped")
        }
    }

    private suspend fun waitForNextCycle(waitMs: Long, prefs: android.content.SharedPreferences) {
        var remaining = waitMs
        while (remaining > 0L && scope.isActive && prefs.getBoolean(PREF_PERIODIC_CAPTURE_RUNNING, false)) {
            val step = remaining.coerceAtMost(1000L)
            delay(step)
            remaining -= step
        }
    }

    private fun stopPeriodicCapture() {
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            .edit()
            .putBoolean(PREF_PERIODIC_CAPTURE_RUNNING, false)
            .apply()
        logInfo(COMMAND_PERIODIC_CAPTURE_STOP, "periodic_capture stop requested")
    }

    private suspend fun saveOnly(
        ip: String,
        targets: Set<String>,
        command: String = COMMAND_PERIODIC_CAPTURE
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
        command: String = COMMAND_PERIODIC_CAPTURE
    ) {
        if (targets.isEmpty()) {
            logInfo(command, "no files to save")
            return
        }
        val savedFiles = saveOnly(ip, targets, command)

        if (savedFiles.isNotEmpty()) {
            // Download the batch first, then leave Wi-Fi transfer mode before issuing BLE deletes.
            // Deleting one-by-one during transfer caused delete callback timeouts and P2P disconnects.
            prepareRemoteDelete(command)
        }

        val deleteResult = deleteSavedFiles(savedFiles, command)
        logInfo(
            command,
            "sync summary saved=${savedFiles.size} deleteRequested=${deleteResult.requested} deleteRequestFailed=${deleteResult.failed}"
        )
    }

    private data class DeleteResult(
        val requested: Int,
        val failed: Int
    )

    private suspend fun deleteSavedFiles(
        savedFiles: List<String>,
        command: String
    ): DeleteResult {
        var deleteRequested = 0
        var deleteRequestFailed = 0
        savedFiles.forEach { fileName ->
            logInfo(command, "requesting remote delete file=$fileName")
            runCatching { mediaSync.requestDelete(fileName) }
                .onSuccess { requested ->
                    if (requested) {
                        deleteRequested++
                        logInfo(command, "remote delete requested file=$fileName")
                    } else {
                        deleteRequestFailed++
                        logWarn(command, "remote delete request failed file=$fileName")
                    }
                }
                .onFailure {
                    deleteRequestFailed++
                    logWarn(command, "remote delete request threw file=$fileName", it)
                }
        }
        return DeleteResult(deleteRequested, deleteRequestFailed)
    }

    private suspend fun prepareRemoteDelete(command: String) {
        logInfo(command, "leaving transfer mode before remote delete")
        // Alternative-HeyCyan-App-and-SDK closes the download session with glassesControl[0x02,0x01,0x09]
        // before cleaning up P2P. Deleting while the glasses are still in transfer mode causes this
        // device to emit P2P/Wi-Fi error=255 and never return FileHandle's delete callback.
        // https://github.com/legokichi/Alternative-HeyCyan-App-and-SDK/blob/main/android/CyanBridge/app/src/main/java/com/fersaiyan/cyanbridge/MainActivity.kt
        val exitTransfer = glassesControl(byteArrayOf(0x02, 0x01, 0x09))
        logInfo(
            command,
            "exit transfer response type=${exitTransfer?.dataType} error=${exitTransfer?.errorCode}"
        )
        unregisterTransferNotifyListener()
        unregisterP2pReceiver()
        val manager = WifiP2pManagerSingleton.getInstance(applicationContext)
        manager.cancelP2pConnection()
        val removedGroup = withTimeoutOrNull(5000L) {
            suspendCancellableCoroutine { continuation ->
                manager.removeGroup { success ->
                    if (continuation.isActive) {
                        continuation.resume(success)
                    }
                }
            }
        } ?: false
        logInfo(command, "p2p group removed before delete=$removedGroup")
        delay(2000L)
    }

    private suspend fun resetTransferForDelete() {
        logInfo(COMMAND_PERIODIC_CAPTURE, "resetting P2P before remote delete")
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

    private fun String.isVideoCandidate(): Boolean {
        val fileName = substringAfterLast('/')
        return fileName.endsWith(".mp4", ignoreCase = true) ||
            (!fileName.contains('.') && fileName.startsWith("video-", ignoreCase = true))
    }

    private fun String.isAudioCandidate(): Boolean {
        val fileName = substringAfterLast('/')
        return fileName.endsWith(".opus", ignoreCase = true) ||
            fileName.endsWith(".ogg", ignoreCase = true) ||
            (!fileName.contains('.') &&
                (fileName.startsWith("record-", ignoreCase = true) || fileName.startsWith("audio-", ignoreCase = true)))
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
            acquire()
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
            val target = selectBestGlassesP2pPeer(peers)
            if (target == null) {
                val peerSummary = peers.joinToString(",") { it.toPeerLogString() }
                logWarn("resolve_ip", "no unambiguous glasses P2P peer found; ignoring peers=$peerSummary")
                return
            }
            logInfo(
                "resolve_ip",
                "p2p connect target name=${target.deviceName} address=${target.deviceAddress}"
            )
            WifiP2pManagerSingleton.getInstance(applicationContext).connectToDevice(target)
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

    private fun selectBestGlassesP2pPeer(peers: Collection<WifiP2pDevice>): WifiP2pDevice? {
        // The Android SDK guide documents BLE scan/connect and media-count commands, but not
        // how to identify the glasses among Android Wi-Fi Direct peers. Avoid connecting to
        // arbitrary peers; use the practical signals observed in device logs and in
        // Alternative-HeyCyan-App-and-SDK's MainActivity.kt peer selection:
        // https://github.com/legokichi/Alternative-HeyCyan-App-and-SDK/blob/main/android/CyanBridge/app/src/main/java/com/fersaiyan/cyanbridge/MainActivity.kt
        val scoredPeers = peers.mapNotNull { peer ->
            peer.scoreAsGlassesP2pPeer()?.let { score ->
                ScoredP2pPeer(peer, score)
            }
        }
        if (scoredPeers.isEmpty()) return null

        scoredPeers
            .joinToString(",") { "${it.peer.toPeerLogString()}/score=${it.score.value}/${it.score.reason}" }
            .let { logInfo("resolve_ip", "glasses P2P candidates=$it") }

        val bestScore = scoredPeers.maxOf { it.score.value }
        val bestPeers = scoredPeers.filter { it.score.value == bestScore }
        if (bestPeers.size != 1) {
            val tied = bestPeers.joinToString(",") { it.peer.toPeerLogString() }
            logWarn("resolve_ip", "ambiguous glasses P2P peers score=$bestScore peers=$tied")
            return null
        }
        return bestPeers.single().peer
    }

    private fun WifiP2pDevice.scoreAsGlassesP2pPeer(): P2pPeerScore? {
        val name = deviceName.orEmpty()
        val normalized = name.uppercase(Locale.US)
        if (normalized.isBlank()) return null
        if (isKnownNonGlassesP2pPeer(normalized)) return null

        val bleMacNoColon = currentBleMacNoColonUpper()
        if (!bleMacNoColon.isNullOrBlank() && normalized.contains(bleMacNoColon)) {
            return P2pPeerScore(100, "ble_mac_token")
        }
        if (normalized.startsWith("MUSICCAM")) {
            return P2pPeerScore(90, "musiccam_prefix")
        }
        if (normalized.startsWith("AIM") || normalized.contains("AIMB-")) {
            return P2pPeerScore(80, "aim_name")
        }
        if (normalized.contains("GLASS") || normalized.contains("CYAN")) {
            return P2pPeerScore(70, "glasses_name")
        }
        if (normalized.contains("MUSIC") && HEX_12_REGEX.containsMatchIn(normalized)) {
            return P2pPeerScore(60, "music_hex_token")
        }
        return null
    }

    private fun currentBleMacNoColonUpper(): String? {
        return runCatching {
            DeviceManager.getInstance().deviceAddress
                ?.replace(":", "")
                ?.uppercase(Locale.US)
                ?.takeIf { it.isNotBlank() }
        }.getOrNull()
    }

    private fun isKnownNonGlassesP2pPeer(normalizedName: String): Boolean {
        return normalizedName.startsWith("DIRECT-") &&
            (normalizedName.contains("HP") ||
                normalizedName.contains("EPSON") ||
                normalizedName.contains("PRINTER") ||
                normalizedName.contains("PIXMA") ||
                normalizedName.contains("BROTHER") ||
                normalizedName.contains("CANON"))
    }

    private fun WifiP2pDevice.toPeerLogString(): String {
        val name = deviceName.orEmpty().ifBlank { "<blank>" }
        return "name=$name address=$deviceAddress type=$primaryDeviceType status=$status"
    }

    private data class P2pPeerScore(val value: Int, val reason: String)

    private data class ScoredP2pPeer(val peer: WifiP2pDevice, val score: P2pPeerScore)

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
        const val COMMAND_PERIODIC_CAPTURE = "periodic_capture"
        const val COMMAND_PERIODIC_CAPTURE_ONLY = "periodic_capture_only"
        const val COMMAND_PERIODIC_CAPTURE_STOP = "periodic_capture_stop"
        const val COMMAND_SYNC_MEDIA_ALL = "sync_media_all"
        const val COMMAND_SYNC_MEDIA_ALL_BATCHED_DELETE = "download2"
        private const val NOTIFICATION_CHANNEL_ID = "heycyan_periodic_capture"
        private const val NOTIFICATION_ID = 1001
        private const val PREFS_NAME = "heycyan_command"
        private const val PREF_PERIODIC_CAPTURE_RUNNING = "periodic_capture_running"
        private const val DEFAULT_LOOP_SECONDS = 60
        private const val BATCHED_DELETE_SIZE = 10
        private val HEX_12_REGEX = Regex("[A-F0-9]{12}")

        fun isPeriodicCaptureRunning(context: Context): Boolean {
            return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getBoolean(PREF_PERIODIC_CAPTURE_RUNNING, false)
        }
    }
}
