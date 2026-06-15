package com.sdk.glassessdksample.ui

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.oudmon.ble.base.bluetooth.BleOperateManager
import com.oudmon.ble.base.communication.LargeDataHandler
import com.oudmon.ble.base.communication.bigData.resp.GlassModelControlResponse
import com.sdk.glassessdksample.MainActivity
import com.sdk.glassessdksample.R
import com.sdk.glassessdksample.ui.wifi.p2p.WifiP2pManagerSingleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.CancellationException
import kotlin.coroutines.resume

class MinutePhotoService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var mediaSync: GlassMediaSync
    private var loopJob: Job? = null
    private var baseline: MutableSet<String>? = null
    private val pendingDelete = linkedSetOf<String>()
    private var p2pReceiver: BroadcastReceiver? = null

    override fun onCreate() {
        super.onCreate()
        mediaSync = GlassMediaSync(applicationContext)
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> stopWork()
            ACTION_START, null -> startWork()
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        cleanupWork()
        scope.cancel()
        super.onDestroy()
    }

    private fun startWork() {
        startForeground(NOTIFICATION_ID, buildNotification("Starting minute photos"))
        if (loopJob?.isActive == true) {
            return
        }
        loopJob = scope.launch {
            runLoop()
        }
    }

    private fun stopWork() {
        cleanupWork()
        stopSelf()
    }

    private fun cleanupWork() {
        loopJob?.cancel()
        loopJob = null
        unregisterP2pReceiver()
        stopForeground(STOP_FOREGROUND_REMOVE)
    }

    private suspend fun runLoop() {
        try {
            updateNotification("Preparing media baseline")
            prepareBaseline()

            while (true) {
                if (!BleOperateManager.getInstance().isConnected) {
                    updateNotification("Glasses disconnected")
                    stopSelf()
                    return
                }

                updateNotification("Taking photo")
                val capture = capturePhoto()
                if (capture == null) {
                    updateNotification("Photo command timed out")
                } else {
                    Log.i(TAG, "Photo command result: type=${capture.dataType}, error=${capture.errorCode}, work=${capture.workTypeIng}, p2pIp=${capture.p2pIp}")
                    delay(3000L)
                    syncNewPhotos(capture.p2pIp)
                }
                delay(PHOTO_INTERVAL_MS)
            }
        } catch (e: CancellationException) {
            Log.i(TAG, "Minute photo loop cancelled")
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Minute photo loop failed", e)
            updateNotification("Stopped: ${e.message}")
            stopSelf()
        }
    }

    private suspend fun prepareBaseline() {
        val ip = resolveDeviceIp(null) ?: run {
            baseline = mutableSetOf()
            Log.w(TAG, "Could not resolve IP for baseline; continuing with empty baseline")
            updateNotification("Baseline skipped; will still take photos")
            return
        }
        val names = runCatching { mediaSync.fetchJpgNames(ip) }
            .onFailure { Log.w(TAG, "Failed to fetch baseline from $ip", it) }
            .getOrNull()
            ?: emptySet()
        baseline = names.toMutableSet()
        updateNotification("Baseline ready: ${names.size} existing photos")
    }

    private suspend fun syncNewPhotos(preferredIp: String?) {
        val ip = resolveDeviceIp(preferredIp) ?: run {
            updateNotification("Waiting for glasses IP")
            return
        }
        val known = baseline ?: mutableSetOf<String>().also { baseline = it }
        val current = runCatching { mediaSync.fetchJpgNames(ip) }
            .onFailure { Log.w(TAG, "Failed to fetch media list from $ip", it) }
            .getOrNull()
            ?: run {
                updateNotification("Could not read media list")
                return
            }

        val newFiles = (current - known) + pendingDelete
        Log.i(TAG, "Media sync state: current=${current.size}, known=${known.size}, pendingDelete=${pendingDelete.size}, new=${newFiles.size}")
        if (newFiles.isEmpty()) {
            updateNotification("No new photos")
            return
        }

        updateNotification("Saving ${newFiles.size} photo(s)")
        newFiles.forEach { fileName ->
            runCatching { mediaSync.saveAndDelete(ip, fileName) }
                .onSuccess { result ->
                    when (result) {
                        is GlassMediaSync.SyncResult.SavedAndDeleted -> {
                            known.add(result.fileName)
                            pendingDelete.remove(result.fileName)
                            updateNotification("Saved and deleted ${result.fileName.substringAfterLast('/')}")
                        }
                        is GlassMediaSync.SyncResult.SavedDeleteFailed -> {
                            known.add(result.fileName)
                            pendingDelete.add(result.fileName)
                            updateNotification("Saved, delete pending ${result.fileName.substringAfterLast('/')}")
                        }
                    }
                }
                .onFailure { error ->
                    Log.w(TAG, "Failed to save $fileName", error)
                    updateNotification("Save failed: ${fileName.substringAfterLast('/')}")
                }
        }
    }

    private suspend fun resolveDeviceIp(preferredIp: String?): String? {
        preferredIp?.takeIf { it.isNotBlank() && it != "0.0.0.0" }?.let {
            Log.i(TAG, "Using preferred P2P IP: $it")
            return it
        }
        val p2pReady = ensureP2pGroup()
        Log.i(TAG, "P2P group ensure result: $p2pReady")
        val mediaState = queryMediaState()
        Log.i(TAG, "Media state result: type=${mediaState?.dataType}, images=${mediaState?.imageCount}, videos=${mediaState?.videoCount}, records=${mediaState?.recordCount}, p2pIp=${mediaState?.p2pIp}, error=${mediaState?.errorCode}")
        return mediaState?.p2pIp?.takeIf { it.isNotBlank() && it != "0.0.0.0" }
    }

    private suspend fun capturePhoto(): GlassModelControlResponse? {
        return glassesControl(byteArrayOf(0x02, 0x01, 0x01))
    }

    private suspend fun queryMediaState(): GlassModelControlResponse? {
        return glassesControl(byteArrayOf(0x02, 0x04))
    }

    private suspend fun glassesControl(command: ByteArray): GlassModelControlResponse? {
        Log.i(TAG, "Sending glassesControl command=${command.joinToString(prefix = "[", postfix = "]") { "0x%02X".format(it) }}")
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
        p2pReceiver?.let { receiver ->
            manager.unregisterReceiver(receiver)
        }
        p2pReceiver = null
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

    private fun updateNotification(status: String) {
        Log.i(TAG, status)
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, buildNotification(status))
    }

    private fun buildNotification(status: String) =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(getString(R.string.minute_photo_notification_title))
            .setContentText(status)
            .setOngoing(true)
            .setContentIntent(mainPendingIntent())
            .addAction(0, getString(R.string.minute_photo_stop), stopPendingIntent())
            .build()

    private fun mainPendingIntent(): PendingIntent {
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        return PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            flags
        )
    }

    private fun stopPendingIntent(): PendingIntent {
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        return PendingIntent.getService(
            this,
            1,
            Intent(this, MinutePhotoService::class.java).setAction(ACTION_STOP),
            flags
        )
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.minute_photo_channel_name),
                NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    companion object {
        const val ACTION_START = "com.sdk.glassessdksample.action.START_MINUTE_PHOTOS"
        const val ACTION_STOP = "com.sdk.glassessdksample.action.STOP_MINUTE_PHOTOS"
        private const val CHANNEL_ID = "minute_photo"
        private const val NOTIFICATION_ID = 1001
        private const val PHOTO_INTERVAL_MS = 60_000L
        private const val TAG = "MinutePhotoService"
    }
}
