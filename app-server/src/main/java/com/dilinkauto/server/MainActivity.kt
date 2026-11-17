package com.dilinkauto.server

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Bundle
import android.os.IBinder
import android.view.View
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.dilinkauto.server.service.CarConnectionService
import com.dilinkauto.server.ui.screen.CarShell
import com.dilinkauto.server.ui.theme.CarTheme

/**
 * Car-side launcher Activity. Owns only process-level concerns:
 *  - Service bind / unbind and forwarding the USB-attach intent.
 *  - Immersive mode (sticky fullscreen).
 *  - Rotation re-handshake: when the car panel rotates, push the new viewport
 *    dims into [CarConnectionService.onCarViewportChanged] so the phone
 *    recreates the VD at the new orientation without tearing down control.
 *
 * The Compose tree (streaming vs launch screen, nav bar, app-info dialog)
 * lives in [CarShell].
 */
class MainActivity : ComponentActivity() {

    private var carService: CarConnectionService? = null
    internal var pendingUsbDevice: android.hardware.usb.UsbDevice? = null
    private var serviceBound by mutableStateOf(false)

    /**
     * issue #40：运行时定位授权。`ACCESS_FINE_LOCATION` 已在 manifest 声明，但 app-server
     * 此前 0 处运行时申请 → 海拔无法获取。首次进入主屏时一次性请求；拒绝则海拔显示 `—`，
     * 不阻塞启动、不影响其他功能（验收标准 2）。授权后通知 provider 恢复采样。
     */
    private val locationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            carService?.let { svc ->
                // 通过反射友好的内部方法触发；service 暴露 altitudeProvider 的恢复入口。
                svc.onLocationPermissionGranted()
            }
        }
        // 拒绝 → 什么都不做，provider 保持 null，UI 显示 `—`。
    }

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            carService = (binder as CarConnectionService.LocalBinder).service
            serviceBound = true
            pendingUsbDevice?.let { device ->
                carService?.onUsbDeviceFromActivity(device)
                pendingUsbDevice = null
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            carService = null
            serviceBound = false
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val serviceIntent = Intent(this, CarConnectionService::class.java).apply {
            action = CarConnectionService.ACTION_START
        }
        startForegroundService(serviceIntent)
        bindService(serviceIntent, serviceConnection, Context.BIND_AUTO_CREATE)

        // Handle USB device attached intent (from manifest intent-filter)
        handleUsbIntent(intent)

        // issue #40：首次进入主屏时请求定位授权（仅未授予时弹一次）。
        // ACCESS_FINE_LOCATION 已在 manifest 声明，但 app-server 此前无运行时申请。
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED
        ) {
            locationPermissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
        }

        setContent {
            CarTheme {
                if (serviceBound) {
                    carService?.let { service ->
                        CarShell(service)
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        handleUsbIntent(intent)
    }

    internal fun handleUsbIntent(intent: Intent?) {
        if (intent?.action == android.hardware.usb.UsbManager.ACTION_USB_DEVICE_ATTACHED) {
            val device = intent.getParcelableExtra<android.hardware.usb.UsbDevice>(
                android.hardware.usb.UsbManager.EXTRA_DEVICE
            )
            if (device != null) {
                android.util.Log.i("MainActivity", "USB device from intent: ${device.productName}")
                // Forward to service — it handles USB ADB
                val service = carService
                if (service != null) {
                    service.onUsbDeviceFromActivity(device)
                } else {
                    pendingUsbDevice = device
                }
            }
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) enableImmersiveMode()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // Rotatable car panel: when the viewport dimensions change (rotation),
        // re-handshake mid-stream so the phone recreates the VD at the new
        // orientation. The control connection is reused; only video/input and
        // the VD server are recycled.
        val dm = resources.displayMetrics
        carService?.onCarViewportChanged(dm.widthPixels, dm.heightPixels, dm.densityDpi)
    }

    @Suppress("DEPRECATION")
    private fun enableImmersiveMode() {
        window.decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                        or View.SYSTEM_UI_FLAG_FULLSCREEN
                        or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                )
    }

    override fun onDestroy() {
        if (serviceBound) {
            unbindService(serviceConnection)
            serviceBound = false
        }
        super.onDestroy()
    }
}
