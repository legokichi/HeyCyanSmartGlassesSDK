package com.sdk.glassessdksample

import android.Manifest
import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.hjq.permissions.OnPermissionCallback
import com.hjq.permissions.XXPermissions
import com.oudmon.ble.base.bluetooth.BleOperateManager
import com.oudmon.ble.base.bluetooth.DeviceManager
import com.oudmon.ble.base.communication.LargeDataHandler
import com.sdk.glassessdksample.databinding.AcitivytMainBinding
import com.sdk.glassessdksample.ui.BluetoothUtils
import com.sdk.glassessdksample.ui.DeviceBindActivity
import com.sdk.glassessdksample.ui.HeyCyanCommandService
import com.sdk.glassessdksample.ui.hasBluetooth
import com.sdk.glassessdksample.ui.requestAllPermission
import com.sdk.glassessdksample.ui.requestBluetoothPermission
import com.sdk.glassessdksample.ui.requestLocationPermission
import com.sdk.glassessdksample.ui.requestNearbyWifiDevicesPermission
import com.sdk.glassessdksample.ui.setOnClickListener
import com.sdk.glassessdksample.ui.startKtxActivity
import android.widget.Toast
import org.greenrobot.eventbus.EventBus
import java.io.File
import java.util.ArrayDeque

private const val DEVICE_INFO_BATTERY_CALLBACK = "device_info_panel"
private const val MEDIA_SYNC_LOG_MAX_LINES = 100

class MainActivity : AppCompatActivity() {
    private lateinit var binding: AcitivytMainBinding
    private var deviceInfoVersionsText = "--"
    private var deviceInfoBatteryText = "--"
    private var deviceInfoVolumeText = "--"
    private var deviceInfoMediaCountText = "--"
    private val mediaSyncLogLines = ArrayDeque<String>()
    private val mediaSyncProgressReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val line = intent.getStringExtra(HeyCyanCommandService.EXTRA_PROGRESS_LINE) ?: return
            appendMediaSyncLog(line)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = AcitivytMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        initView()
    }

    override fun onStart() {
        super.onStart()
        LocalBroadcastManager.getInstance(this).registerReceiver(
            mediaSyncProgressReceiver,
            IntentFilter(HeyCyanCommandService.ACTION_MEDIA_SYNC_PROGRESS)
        )
    }

    override fun onStop() {
        LocalBroadcastManager.getInstance(this).unregisterReceiver(mediaSyncProgressReceiver)
        super.onStop()
    }

    inner class PermissionCallback : OnPermissionCallback {
        override fun onGranted(permissions: MutableList<String>, all: Boolean) {
            if (!all) {

            }else{
                startKtxActivity<DeviceBindActivity>()
            }
        }

        override fun onDenied(permissions: MutableList<String>, never: Boolean) {
            super.onDenied(permissions, never)
            if(never){
                XXPermissions.startPermissionActivity(this@MainActivity, permissions);
            }
        }

    }


    override fun onResume() {
        super.onResume()
        try {
            if (!BluetoothUtils.isEnabledBluetooth(this)) {
                val intent = Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    if (ActivityCompat.checkSelfPermission(
                            this,
                            Manifest.permission.BLUETOOTH_CONNECT
                        ) != PackageManager.PERMISSION_GRANTED
                    ) {
                        return
                    }
                }
                startActivityForResult(intent, 300)
            }
        } catch (e: Exception) {
        }
        if (!hasBluetooth(this)) {
            requestBluetoothPermission(this, BluetoothPermissionCallback())
        }

        requestAllPermission(this, OnPermissionCallback { permissions, all ->  })
        refreshDeviceInfoPanel()
    }

    inner class BluetoothPermissionCallback : OnPermissionCallback {
        override fun onGranted(permissions: MutableList<String>, all: Boolean) {
            if (!all) {

            }
        }

        override fun onDenied(permissions: MutableList<String>, never: Boolean) {
            super.onDenied(permissions, never)
            if (never) {
                XXPermissions.startPermissionActivity(this@MainActivity, permissions)
            }
        }

    }

    private fun initView() {
        setOnClickListener(
            binding.btnScan,
            binding.btnDisconnect,
            binding.btnCamera,
            binding.btnVideo,
            binding.btnRecord,
            binding.btnDataDownload,
            binding.btnMediaSyncLogClear,
            binding.btnRefreshDeviceInfo,
            binding.btnPeriodicalCaptureStart,
            binding.btnPeriodicalCaptureOnlyStart,
            binding.btnPeriodicalCaptureStop
        ) {
            when (this) {
                binding.btnScan -> {
                    requestLocationPermission(this@MainActivity, PermissionCallback())
                }

                binding.btnDisconnect -> {
                    BleOperateManager.getInstance().unBindDevice()
                    resetDeviceInfoPanel()
                }

                binding.btnCamera -> {
                    LargeDataHandler.getInstance().glassesControl(
                        byteArrayOf(0x02, 0x01, 0x01)
                    ) { _, it ->
                        if (it.dataType == 1 && it.errorCode == 0) {
                            when (it.workTypeIng) {
                                2 -> {
                                    //眼镜正在录像
                                }
                                4 -> {
                                    //眼镜正在传输模式
                                }
                                5 -> {
                                    //眼镜正在OTA模式
                                }
                                1, 6 ->{
                                    //眼镜正在拍照模式
                                }
                                7 -> {
                                    //眼镜正在AI对话
                                }
                                8 ->{
                                    //眼镜正在录音模式
                                }
                            }
                        } else {
                            //执行开始和结束
                        }
                    }
                }

                binding.btnVideo -> {
                    //videoStart  true 开始录制   false 停止录制
                    val videoStart=true
                    val value = if (videoStart) 0x02 else 0x03
                    LargeDataHandler.getInstance().glassesControl(
                        byteArrayOf(0x02, 0x01, value.toByte())
                    ) { _, it ->
                        if (it.dataType == 1) {
                            if (it.errorCode == 0) {
                                when (it.workTypeIng) {
                                    2 -> {
                                        //眼镜正在录像
                                    }
                                    4 -> {
                                        //眼镜正在传输模式
                                    }
                                    5 -> {
                                        //眼镜正在OTA模式
                                    }
                                    1, 6 ->{
                                        //眼镜正在拍照模式
                                    }
                                    7 -> {
                                        //眼镜正在AI对话
                                    }
                                    8 ->{
                                        //眼镜正在录音模式
                                    }
                                }
                            } else {
                                //执行开始和结束
                            }
                        }
                    }
                }

                binding.btnRecord -> {
                    //recordStart  true 开始录制   false 停止录制
                    val recordStart=true
                    val value = if (recordStart) 0x08 else 0x0c
                    LargeDataHandler.getInstance().glassesControl(
                        byteArrayOf(0x02, 0x01, value.toByte())
                    ) { _, it ->
                        if (it.dataType == 1) {
                            if (it.errorCode == 0) {
                                when (it.workTypeIng) {
                                    2 -> {
                                        //眼镜正在录像
                                    }
                                    4 -> {
                                        //眼镜正在传输模式
                                    }
                                    5 -> {
                                        //眼镜正在OTA模式
                                    }
                                    1, 6 ->{
                                        //眼镜正在拍照模式
                                    }
                                    7 -> {
                                        //眼镜正在AI对话
                                    }
                                    8 ->{
                                        //眼镜正在录音模式
                                    }
                                }
                            } else {
                                //执行开始和结束
                            }
                        }
                    }
                }

                binding.btnDataDownload -> {
                    // 检查并请求必要的权限
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        // Android 13+ 需要 NEARBY_WIFI_DEVICES 权限
                        requestNearbyWifiDevicesPermission(this@MainActivity, object : OnPermissionCallback {
                            override fun onGranted(permissions: MutableList<String>, all: Boolean) {
                                if (all) {
                                    startMediaSyncAllFromUi()
                                }
                            }

                            override fun onDenied(permissions: MutableList<String>, never: Boolean) {
                                super.onDenied(permissions, never)
                                if (never) {
                                    XXPermissions.startPermissionActivity(this@MainActivity, permissions)
                                }
                            }
                        })
                    } else {
                        // Android 12 及以下版本直接启动下载
                        startMediaSyncAllFromUi()
                    }
                }
                binding.btnMediaSyncLogClear -> {
                    clearMediaSyncLog()
                }
                binding.btnRefreshDeviceInfo -> {
                    refreshDeviceInfoPanel()
                }
                binding.btnPeriodicalCaptureStart -> {
                    startPeriodicalCaptureFromUi()
                }
                binding.btnPeriodicalCaptureOnlyStart -> {
                    startPeriodicalCaptureOnlyFromUi()
                }
                binding.btnPeriodicalCaptureStop -> {
                    stopPeriodicalCaptureFromUi()
                }
            }
        }
    }

    private fun resetDeviceInfoPanel() {
        deviceInfoVersionsText = "--"
        deviceInfoBatteryText = "--"
        deviceInfoVolumeText = "--"
        deviceInfoMediaCountText = "--"
        renderDeviceInfoPanel()
    }

    private fun refreshDeviceInfoPanel() {
        renderDeviceInfoPanel()
        if (!BleOperateManager.getInstance().isConnected) {
            return
        }
        deviceInfoVersionsText = "Loading..."
        deviceInfoBatteryText = "Loading..."
        deviceInfoVolumeText = "Loading..."
        deviceInfoMediaCountText = "Loading..."
        renderDeviceInfoPanel()
        requestDeviceVersions()
        requestDeviceBattery()
        requestDeviceVolume()
        requestDeviceMediaCount()
        requestDeviceTimeSync()
    }

    private fun requestDeviceVersions() {
        if (!BleOperateManager.getInstance().isConnected) {
            renderDeviceInfoPanel()
            return
        }
        LargeDataHandler.getInstance().syncDeviceInfo { _, response ->
            if (response != null) {
                deviceInfoVersionsText = listOf(
                    "BLE firmware: ${response.firmwareVersion.orDash()}",
                    "BLE hardware: ${response.hardwareVersion.orDash()}",
                    "Wi-Fi firmware: ${response.wifiFirmwareVersion.orDash()}",
                    "Wi-Fi hardware: ${response.wifiHardwareVersion.orDash()}"
                ).joinToString("\n")
                runOnUiThread { renderDeviceInfoPanel() }
            }
        }
    }

    private fun requestDeviceBattery() {
        if (!BleOperateManager.getInstance().isConnected) {
            renderDeviceInfoPanel()
            return
        }
        LargeDataHandler.getInstance().removeBatteryCallBack(DEVICE_INFO_BATTERY_CALLBACK)
        LargeDataHandler.getInstance().addBatteryCallBack(DEVICE_INFO_BATTERY_CALLBACK) { _, response ->
            if (response != null) {
                val charging = if (response.isCharging) "charging" else "not charging"
                deviceInfoBatteryText = "${response.battery}% ($charging)"
                runOnUiThread { renderDeviceInfoPanel() }
            }
        }
        LargeDataHandler.getInstance().syncBattery()
    }

    private fun requestDeviceVolume() {
        if (!BleOperateManager.getInstance().isConnected) {
            renderDeviceInfoPanel()
            return
        }
        LargeDataHandler.getInstance().getVolumeControl { _, response ->
            if (response != null) {
                deviceInfoVolumeText = listOf(
                    "Current type: ${response.currVolumeType}",
                    "Music: ${response.currVolumeMusic}/${response.maxVolumeMusic} (min ${response.minVolumeMusic})",
                    "Call: ${response.currVolumeCall}/${response.maxVolumeCall} (min ${response.minVolumeCall})",
                    "System: ${response.currVolumeSystem}/${response.maxVolumeSystem} (min ${response.minVolumeSystem})"
                ).joinToString("\n")
                runOnUiThread { renderDeviceInfoPanel() }
            }
        }
    }

    private fun requestDeviceMediaCount() {
        if (!BleOperateManager.getInstance().isConnected) {
            renderDeviceInfoPanel()
            return
        }
        LargeDataHandler.getInstance().glassesControl(byteArrayOf(0x02, 0x04)) { _, response ->
            if (response.dataType == 4) {
                val mediaCount = response.imageCount + response.videoCount + response.recordCount
                deviceInfoMediaCountText = listOf(
                    "Total: $mediaCount",
                    "Images: ${response.imageCount}",
                    "Videos: ${response.videoCount}",
                    "Audio: ${response.recordCount}"
                ).joinToString("\n")
                runOnUiThread { renderDeviceInfoPanel() }
            }
        }
    }

    private fun requestDeviceTimeSync() {
        if (!BleOperateManager.getInstance().isConnected) {
            renderDeviceInfoPanel()
            return
        }
        LargeDataHandler.getInstance().syncTime { _, _ -> }
    }

    private fun renderDeviceInfoPanel() {
        val connected = BleOperateManager.getInstance().isConnected
        val ready = BleOperateManager.getInstance().isReady
        val name = DeviceManager.getInstance().deviceName.orDash()
        val address = DeviceManager.getInstance().deviceAddress.orDash()
        binding.textDeviceInfoBody.text = if (!connected) {
            getString(R.string.device_info_placeholder)
        } else {
            listOf(
                "Name: $name",
                "Address: $address",
                "BLE connected: $connected",
                "BLE ready: $ready",
                "",
                "Versions:",
                deviceInfoVersionsText,
                "",
                "Battery:",
                deviceInfoBatteryText,
                "",
                "Volume:",
                deviceInfoVolumeText,
                "",
                "Undownloaded media:",
                deviceInfoMediaCountText
            ).joinToString("\n")
        }
    }

    private fun String?.orDash(): String {
        return takeUnless { it.isNullOrBlank() } ?: "--"
    }

    private fun startPeriodicalCaptureFromUi() {
        val seconds = readPeriodicalCaptureSeconds()
        sendCommandService(HeyCyanCommandService.COMMAND_PERIODICAL_CAPTURE) {
            putExtra(HeyCyanCommandService.EXTRA_SECONDS, seconds)
        }
        Toast.makeText(this, getString(R.string.periodical_capture_start_requested), Toast.LENGTH_SHORT).show()
    }

    private fun startPeriodicalCaptureOnlyFromUi() {
        val seconds = readPeriodicalCaptureSeconds()
        sendCommandService(HeyCyanCommandService.COMMAND_PERIODICAL_CAPTURE_ONLY) {
            putExtra(HeyCyanCommandService.EXTRA_SECONDS, seconds)
        }
        Toast.makeText(this, getString(R.string.periodical_capture_only_start_requested), Toast.LENGTH_SHORT).show()
    }

    private fun readPeriodicalCaptureSeconds(): Int {
        val seconds = binding.inputPeriodicalCaptureInterval.text
            ?.toString()
            ?.toIntOrNull()
            ?.coerceIn(1, 86_400)
            ?: 60
        binding.inputPeriodicalCaptureInterval.setText(seconds.toString())
        return seconds
    }

    private fun stopPeriodicalCaptureFromUi() {
        sendCommandService(HeyCyanCommandService.COMMAND_PERIODICAL_CAPTURE_STOP)
        Toast.makeText(this, getString(R.string.periodical_capture_stop_requested), Toast.LENGTH_SHORT).show()
    }

    private fun sendCommandService(command: String, configure: Intent.() -> Unit = {}) {
        val intent = Intent(this, HeyCyanCommandService::class.java)
            .setAction(HeyCyanCommandService.ACTION_COMMAND)
            .putExtra(HeyCyanCommandService.EXTRA_COMMAND, command)
        intent.configure()
        startService(intent)
    }

    private fun startMediaSyncAllFromUi() {
        clearMediaSyncLog()
        appendMediaSyncLog("Starting media sync")
        sendCommandService(HeyCyanCommandService.COMMAND_SYNC_MEDIA_ALL)
    }

    private fun clearMediaSyncLog() {
        mediaSyncLogLines.clear()
        renderMediaSyncLog()
    }

    private fun appendMediaSyncLog(line: String) {
        mediaSyncLogLines.addLast(line)
        trimMediaSyncLog()
        renderMediaSyncLog()
    }

    private fun trimMediaSyncLog() {
        while (mediaSyncLogLines.size > MEDIA_SYNC_LOG_MAX_LINES) {
            mediaSyncLogLines.removeFirst()
        }
    }

    private fun renderMediaSyncLog() {
        binding.textMediaSyncLogBody.text = if (mediaSyncLogLines.isEmpty()) {
            getString(R.string.media_sync_log_placeholder)
        } else {
            mediaSyncLogLines.joinToString("\n")
        }
    }
}
