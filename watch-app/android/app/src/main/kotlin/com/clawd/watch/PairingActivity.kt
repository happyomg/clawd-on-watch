package com.clawd.watch

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.util.Log
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.clawd.watch.data.PairingStore
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
    }

    private lateinit var statusText: TextView
    private var bleService: BleService? = null
    private var bound = false

    private fun savePairingAndProceed() {
        PairingStore.save(this, "peripheral-mode", "Clawd Watch")
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

        setContentView(buildUi())

        if (hasPermissions()) {
            startServiceAndBind()
        } else {
            requestPermissions()
        }
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

    private fun buildUi(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 48, 24, 24)
            gravity = Gravity.CENTER_HORIZONTAL
        }
        root.addView(TextView(this).apply {
            text = "Clawd Watch"
            textSize = 18f
            gravity = Gravity.CENTER
        })
        root.addView(ProgressBar(this).apply {
            setPadding(0, 24, 0, 12)
            isIndeterminate = true
        })
        statusText = TextView(this).apply {
            text = "Preparing..."
            gravity = Gravity.CENTER
            setPadding(0, 12, 0, 12)
        }
        root.addView(statusText)
        return root
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
            startServiceAndBind()
        } else {
            statusText.text = "Bluetooth permission required"
        }
    }

    private fun startMainAndFinish() {
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }
}
