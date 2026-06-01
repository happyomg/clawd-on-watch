package com.clawd.watch

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.provider.Settings
import android.util.Log
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.clawd.watch.data.PairingStore
import com.clawd.watch.data.SettingsStore
import com.clawd.watch.service.BleService

/**
 * Peripheral-mode pairing screen. Starts the GATT server + BLE advertising,
 * then waits for the desktop's bleak (Central) to connect. Once connected,
 * saves the Central's address and moves to MainActivity.
 */
class PairingActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "PairingActivity"
        private const val REQ_PERMS = 1
        private const val REQ_NOTIFICATION = 2
    }

    private lateinit var statusText: TextView
    private var bleService: BleService? = null
    private var bound = false

    private fun savePairingAndProceed() {
        val addr = bleService?.getConnectedDeviceAddress() ?: "peripheral-mode"
        val name = bleService?.getConnectedDeviceName() ?: "Desktop"
        PairingStore.save(this, addr, name)
        Log.i(TAG, "Paired with $name ($addr)")
        startMainAndFinish()
    }

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val service = (binder as BleService.LocalBinder).getService()
            bleService = service
            bound = true
            if (service.isConnected()) {
                Log.i(TAG, "Desktop already connected — proceeding to main")
                savePairingAndProceed()
                return
            }
            service.onConnectionStateChanged = { connected ->
                if (connected) {
                    Log.i(TAG, "Desktop connected — proceeding to main")
                    savePairingAndProceed()
                }
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            bleService = null
            bound = false
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (PairingStore.isPaired(this)) {
            startMainAndFinish()
            return
        }

        setContentView(R.layout.activity_pairing)
        statusText = findViewById(R.id.status_text)

        checkAndRequestPermissions()
    }

    override fun onDestroy() {
        if (bound) {
            bleService?.onConnectionStateChanged = null
            unbindService(serviceConnection)
            bound = false
        }
        super.onDestroy()
    }

    private fun startServiceAndBind() {
        statusText.text = "Advertising...\nOpen Clawd on your Mac\nand connect to this watch"
        BleService.start(this)
        bindService(Intent(this, BleService::class.java), serviceConnection, Context.BIND_AUTO_CREATE)
    }

    private fun checkAndRequestPermissions() {
        if (!hasPermissions()) {
            requestPermissions()
            return
        }
        if (!isNotificationEnabled()) {
            statusText.text = "Please enable notifications\nfor Clawd"
            try {
                val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                    putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
                }
                @Suppress("DEPRECATION")
                startActivityForResult(intent, REQ_NOTIFICATION)
            } catch (e: Exception) {
                Log.w(TAG, "Notification settings unavailable: ${e.message}")
                startServiceAndBind()
            }
            return
        }
        startServiceAndBind()
    }

    private fun isNotificationEnabled(): Boolean {
        val mgr = getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
        return mgr.areNotificationsEnabled()
    }

    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_NOTIFICATION) {
            startServiceAndBind()
        }
    }

    private fun requiredPermissions(): Array<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(
                android.Manifest.permission.BLUETOOTH_ADVERTISE,
                android.Manifest.permission.BLUETOOTH_CONNECT,
            )
        } else {
            arrayOf(android.Manifest.permission.ACCESS_FINE_LOCATION)
        }

    private fun hasPermissions(): Boolean = requiredPermissions().all {
        ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
    }

    private fun requestPermissions() {
        ActivityCompat.requestPermissions(this, requiredPermissions(), REQ_PERMS)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_PERMS && grantResults.isNotEmpty() &&
            grantResults.all { it == PackageManager.PERMISSION_GRANTED }
        ) {
            checkAndRequestPermissions()
        } else {
            statusText.text = "Bluetooth permission required"
        }
    }

    private fun startMainAndFinish() {
        if (!SettingsStore.isOnboardingComplete(this)) {
            startActivity(Intent(this, OnboardingActivity::class.java))
        }
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }
}
