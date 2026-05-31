package com.clawd.watch.service

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Binder
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.ParcelUuid
import android.util.Log
import com.clawd.watch.ApprovalActivity
import com.clawd.watch.MainActivity
import com.clawd.watch.R
import com.clawd.watch.data.ApprovalResponse
import com.clawd.watch.data.WatchMessage
import com.clawd.watch.power.PowerManager
import org.json.JSONObject
import java.util.UUID

/**
 * BLE Peripheral GATT server. Advertises the Clawd service and waits for the
 * desktop's bleak (Central) to connect. Once connected, the desktop writes
 * state snapshots to CWD1 and approval requests to CWD2; the watch writes
 * approval responses to CWD3.
 *
 * Data flow (Peripheral mode — reversed from the Central version):
 *   Desktop (Central) writes CWD1 → watch receives state snapshot
 *   Desktop (Central) writes CWD2 → watch receives approval request
 *   Watch updates CWD3 → desktop reads (or watch notifies) approval response
 *   Desktop (Central) reads CWD4 → watch serves connection meta
 */
class BleService : Service() {

    companion object {
        private const val TAG = "BleService"
        private const val CHANNEL_ID = "clawd_ble"
        private const val NOTIFICATION_ID = 1

        // GATT identifiers — same UUIDs as the Central version.
        val SERVICE_UUID: UUID = UUID.fromString("00000cd0-0000-1000-8000-00805f9b34fb")
        val CHAR_STATE: UUID = UUID.fromString("00000cd1-0000-1000-8000-00805f9b34fb")
        val CHAR_APPROVAL_REQ: UUID = UUID.fromString("00000cd2-0000-1000-8000-00805f9b34fb")
        val CHAR_APPROVAL_RESP: UUID = UUID.fromString("00000cd3-0000-1000-8000-00805f9b34fb")
        val CHAR_META: UUID = UUID.fromString("00000cd4-0000-1000-8000-00805f9b34fb")
        val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

        fun start(context: Context) {
            val intent = Intent(context, BleService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, BleService::class.java))
        }
    }

    private val binder = LocalBinder()
    @Volatile private var gattServer: BluetoothGattServer? = null
    @Volatile private var advertiser: BluetoothLeAdvertiser? = null
    @Volatile private var connectedDevice: BluetoothDevice? = null
    @Volatile private var isAdvertising = false

    private fun hasBlePermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            checkSelfPermission(Manifest.permission.BLUETOOTH_ADVERTISE) == PackageManager.PERMISSION_GRANTED &&
                checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }

    private lateinit var powerManager: PowerManager
    private val handler = Handler(Looper.getMainLooper())

    // Characteristic references for sending notifications
    @Volatile private var charApprovalResp: BluetoothGattCharacteristic? = null

    // Thread-safe: accessed from GATT Binder threads and main thread
    private val cwd3Subscribers = java.util.Collections.synchronizedSet(mutableSetOf<String>())
    private val preparedWriteBuffer = java.util.Collections.synchronizedMap(mutableMapOf<Int, ByteArray>())

    var onWatchMessage: ((WatchMessage) -> Unit)? = null
    var onConnectionStateChanged: ((Boolean) -> Unit)? = null
    var onPowerModeChanged: ((PowerManager.PowerMode) -> Unit)? = null

    @Volatile private var lastCompactState: WatchMessage.CompactState? = null

    fun getLastState(): WatchMessage.CompactState? {
        if (lastCompactState != null) return lastCompactState
        val prefs = getSharedPreferences("clawd_state", Context.MODE_PRIVATE)
        val s = prefs.getString("last_s", null) ?: return null
        return WatchMessage.CompactState(
            state = s,
            svg = prefs.getString("last_svg", null),
            activeCount = prefs.getInt("last_n", 0)
        )
    }

    private fun cacheState(state: WatchMessage.CompactState) {
        lastCompactState = state
        getSharedPreferences("clawd_state", Context.MODE_PRIVATE).edit()
            .putString("last_s", state.state)
            .putString("last_svg", state.svg)
            .putInt("last_n", state.activeCount)
            .apply()
    }

    fun isConnected(): Boolean = connectedDevice != null

    inner class LocalBinder : Binder() {
        fun getService(): BleService = this@BleService
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        powerManager = PowerManager(this)
        powerManager.onModeChanged = { mode -> onPowerModeChanged?.invoke(mode) }
        powerManager.start()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, buildNotification("Starting..."))
        startGattServer()
        startAdvertising()
        return START_STICKY
    }

    override fun onDestroy() {
        stopAdvertising()
        powerManager.stop()
        handler.removeCallbacksAndMessages(null)
        gattServer?.close()
        gattServer = null
        connectedDevice = null
        super.onDestroy()
    }

    // ── GATT Server ──

    @SuppressLint("MissingPermission")
    private fun startGattServer() {
        if (!hasBlePermission()) {
            Log.e(TAG, "BLE permissions revoked — cannot start GATT server")
            return
        }
        gattServer?.close()
        gattServer = null
        val manager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        gattServer = manager.openGattServer(this, gattServerCallback) ?: run {
            Log.e(TAG, "Failed to open GATT server")
            return
        }

        val service = BluetoothGattService(SERVICE_UUID, BluetoothGattService.SERVICE_TYPE_PRIMARY)

        // CWD1: State — desktop writes snapshots here
        val stateChar = BluetoothGattCharacteristic(
            CHAR_STATE,
            BluetoothGattCharacteristic.PROPERTY_WRITE or BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE,
            BluetoothGattCharacteristic.PERMISSION_WRITE
        )
        service.addCharacteristic(stateChar)

        // CWD2: Approval request — desktop writes approval requests here
        val approvalReqChar = BluetoothGattCharacteristic(
            CHAR_APPROVAL_REQ,
            BluetoothGattCharacteristic.PROPERTY_WRITE or BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE,
            BluetoothGattCharacteristic.PERMISSION_WRITE
        )
        service.addCharacteristic(approvalReqChar)

        // CWD3: Approval response — watch writes here, desktop subscribes for notifications
        charApprovalResp = BluetoothGattCharacteristic(
            CHAR_APPROVAL_RESP,
            BluetoothGattCharacteristic.PROPERTY_READ or BluetoothGattCharacteristic.PROPERTY_NOTIFY,
            BluetoothGattCharacteristic.PERMISSION_READ
        )
        charApprovalResp!!.addDescriptor(
            BluetoothGattDescriptor(CCCD, BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE)
        )
        service.addCharacteristic(charApprovalResp!!)

        // CWD4: Meta — desktop reads connection info
        val metaChar = BluetoothGattCharacteristic(
            CHAR_META,
            BluetoothGattCharacteristic.PROPERTY_READ,
            BluetoothGattCharacteristic.PERMISSION_READ
        )
        service.addCharacteristic(metaChar)

        gattServer!!.addService(service)
        Log.i(TAG, "GATT server started with service $SERVICE_UUID")
    }

    private val gattServerCallback = object : BluetoothGattServerCallback() {

        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    Log.i(TAG, "Central connected: ${device.address}")
                    connectedDevice = device
                    handler.post {
                        onConnectionStateChanged?.invoke(true)
                        updateNotification("Connected to ${device.name ?: device.address}")
                    }
                    // Don't stop advertising yet — wait until we receive
                    // a CWD write to confirm this is a real GATT client,
                    // not just a system-level BLE connection.
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    Log.i(TAG, "Central disconnected: ${device.address}")
                    if (connectedDevice?.address == device.address) {
                        connectedDevice = null
                        cwd3Subscribers.remove(device.address)
                        handler.post {
                            onConnectionStateChanged?.invoke(false)
                            updateNotification("Disconnected")
                        }
                        startAdvertising()
                    }
                }
            }
        }

        override fun onCharacteristicWriteRequest(
            device: BluetoothDevice,
            requestId: Int,
            characteristic: BluetoothGattCharacteristic,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray?
        ) {
            if (responseNeeded) {
                gattServer?.sendResponse(device, requestId, android.bluetooth.BluetoothGatt.GATT_SUCCESS, offset, value)
            }
            val data = value ?: return
            if (preparedWrite) {
                val handle = characteristic.instanceId
                val existing = preparedWriteBuffer[handle] ?: ByteArray(0)
                val merged = if (offset == 0) data else {
                    val buf = existing.copyOf(maxOf(existing.size, offset + data.size))
                    data.copyInto(buf, offset)
                    buf
                }
                preparedWriteBuffer[handle] = merged
                return
            }
            dispatchWrite(characteristic.uuid, data)
        }

        override fun onExecuteWrite(device: BluetoothDevice, requestId: Int, execute: Boolean) {
            val entries = synchronized(preparedWriteBuffer) { preparedWriteBuffer.toMap() }
            synchronized(preparedWriteBuffer) { preparedWriteBuffer.clear() }
            if (execute) {
                for ((handle, data) in entries) {
                    val uuid = findCharUuidByHandle(handle)
                    if (uuid != null) {
                        Log.i(TAG, "Execute write: uuid=$uuid len=${data.size}")
                        dispatchWrite(uuid, data)
                    }
                }
            }
            gattServer?.sendResponse(device, requestId, android.bluetooth.BluetoothGatt.GATT_SUCCESS, 0, null)
        }

        override fun onCharacteristicReadRequest(
            device: BluetoothDevice,
            requestId: Int,
            offset: Int,
            characteristic: BluetoothGattCharacteristic
        ) {
            when (characteristic.uuid) {
                CHAR_META -> {
                    val meta = JSONObject().apply {
                        put("deviceName", Build.MODEL)
                        put("version", "0.1.0")
                        put("role", "peripheral")
                    }.toString().toByteArray(Charsets.UTF_8)
                    val chunk = if (offset < meta.size) meta.copyOfRange(offset, meta.size) else ByteArray(0)
                    gattServer?.sendResponse(device, requestId, android.bluetooth.BluetoothGatt.GATT_SUCCESS, offset, chunk)
                }
                CHAR_APPROVAL_RESP -> {
                    val empty = ByteArray(0)
                    gattServer?.sendResponse(device, requestId, android.bluetooth.BluetoothGatt.GATT_SUCCESS, 0, empty)
                }
                else -> {
                    gattServer?.sendResponse(device, requestId, android.bluetooth.BluetoothGatt.GATT_FAILURE, 0, null)
                }
            }
        }

        override fun onDescriptorWriteRequest(
            device: BluetoothDevice,
            requestId: Int,
            descriptor: BluetoothGattDescriptor,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray?
        ) {
            if (descriptor.uuid == CCCD && descriptor.characteristic.uuid == CHAR_APPROVAL_RESP) {
                if (value.contentEquals(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)) {
                    cwd3Subscribers.add(device.address)
                    Log.i(TAG, "Central subscribed to CWD3 notifications")
                } else {
                    cwd3Subscribers.remove(device.address)
                    Log.i(TAG, "Central unsubscribed from CWD3 notifications")
                }
            }
            if (responseNeeded) {
                gattServer?.sendResponse(device, requestId, android.bluetooth.BluetoothGatt.GATT_SUCCESS, 0, null)
            }
        }

        override fun onDescriptorReadRequest(
            device: BluetoothDevice,
            requestId: Int,
            offset: Int,
            descriptor: BluetoothGattDescriptor
        ) {
            if (descriptor.uuid == CCCD) {
                val value = if (cwd3Subscribers.contains(device.address))
                    BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                else
                    BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE
                gattServer?.sendResponse(device, requestId, android.bluetooth.BluetoothGatt.GATT_SUCCESS, 0, value)
            } else {
                gattServer?.sendResponse(device, requestId, android.bluetooth.BluetoothGatt.GATT_FAILURE, 0, null)
            }
        }

        override fun onMtuChanged(device: BluetoothDevice, mtu: Int) {
            Log.i(TAG, "MTU changed to $mtu for ${device.address}")
        }
    }

    // ── Advertising ──

    @SuppressLint("MissingPermission")
    @Synchronized
    private fun startAdvertising() {
        if (isAdvertising) return
        if (!hasBlePermission()) {
            Log.e(TAG, "BLE permissions revoked — cannot start advertising")
            return
        }
        val manager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        advertiser = manager.adapter?.bluetoothLeAdvertiser ?: run {
            Log.e(TAG, "BLE advertising not supported")
            return
        }

        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setConnectable(true)
            .setTimeout(0)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
            .build()

        val data = AdvertiseData.Builder()
            .setIncludeDeviceName(true)
            .addServiceUuid(ParcelUuid(SERVICE_UUID))
            .build()

        advertiser?.startAdvertising(settings, data, advertiseCallback)
    }

    @SuppressLint("MissingPermission")
    @Synchronized
    private fun stopAdvertising() {
        if (!isAdvertising) return
        advertiser?.stopAdvertising(advertiseCallback)
        isAdvertising = false
    }

    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings) {
            isAdvertising = true
            Log.i(TAG, "Advertising started")
            handler.post { updateNotification("Advertising...") }
        }

        override fun onStartFailure(errorCode: Int) {
            isAdvertising = false
            Log.e(TAG, "Advertising failed: $errorCode")
            handler.post { updateNotification("Advertising failed ($errorCode)") }
        }
    }

    private fun dispatchWrite(uuid: UUID, data: ByteArray) {
        when (uuid) {
            CHAR_STATE -> handleStateWrite(data)
            CHAR_APPROVAL_REQ -> handleApprovalRequestWrite(data)
            else -> Log.w(TAG, "Write to unknown characteristic: $uuid")
        }
    }

    private fun findCharUuidByHandle(handle: Int): UUID? {
        val service = gattServer?.getService(SERVICE_UUID) ?: return null
        return service.characteristics.firstOrNull { it.instanceId == handle }?.uuid
    }

    // ── Incoming data handlers ──

    private fun handleStateWrite(data: ByteArray) {
        stopAdvertising()
        val text = data.toString(Charsets.UTF_8)
        Log.i(TAG, "handleStateWrite: ${data.size} bytes")
        try {
            val msg = WatchMessage.parse(JSONObject(text))
            if (msg != null) {
                if (msg is WatchMessage.CompactState) {
                    val prev = lastCompactState
                    cacheState(msg)
                    updateNotification(msg.state)
                    if (prev != null && prev.state != msg.state) {
                        bringToForeground()
                    }
                }
                handler.post { onWatchMessage?.invoke(msg) }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Bad state payload: ${e.message}")
        }
    }

    @Suppress("DEPRECATION")
    private fun bringToForeground() {
        vibrateNotification()

        // Try direct startActivity (works if SYSTEM_ALERT_WINDOW is granted)
        try {
            val intent = Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP or
                    Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
            }
            startActivity(intent)
        } catch (e: Exception) {
            Log.w(TAG, "startActivity failed: ${e.message}")
        }
    }

    private fun handleApprovalRequestWrite(data: ByteArray) {
        val text = data.toString(Charsets.UTF_8)
        try {
            val json = JSONObject(text)
            val msg = WatchMessage.ApprovalRequest(
                requestId = json.getString("requestId"),
                sessionId = json.optString("sessionId", ""),
                tool = json.optString("tool", ""),
                command = json.optString("command", ""),
                risk = json.optString("risk", "medium"),
                timeoutMs = if (json.has("timeoutMs")) json.getLong("timeoutMs") else null,
                expiresAt = if (json.has("expiresAt")) json.getLong("expiresAt") else null
            )
            handler.post { dispatchApproval(msg) }
        } catch (e: Exception) {
            Log.e(TAG, "Bad approval payload: ${e.message}")
        }
    }

    // ── Outgoing ──

    @SuppressLint("MissingPermission")
    fun sendApprovalResponse(response: ApprovalResponse) {
        val server = gattServer ?: return
        val device = connectedDevice ?: return
        val char = charApprovalResp ?: return

        val decision = if (response.decision.startsWith("allow")) "allow" else "deny"
        val payload = JSONObject().apply {
            put("requestId", response.requestId)
            put("decision", decision)
        }.toString().toByteArray(Charsets.UTF_8)

        char.value = payload
        if (cwd3Subscribers.contains(device.address)) {
            server.notifyCharacteristicChanged(device, char, false)
        }
        val state = lastCompactState?.state ?: "idle"
        updateNotification(state)
    }

    // ── Approval dispatch ──

    private fun dispatchApproval(msg: WatchMessage.ApprovalRequest) {
        onWatchMessage?.invoke(msg)
        val intent = Intent(this, ApprovalActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra("requestId", msg.requestId)
            putExtra("command", msg.command)
            putExtra("tool", msg.tool)
            putExtra("risk", msg.risk)
            putExtra("sessionId", msg.sessionId)
            msg.timeoutMs?.let { putExtra("timeoutMs", it) }
            msg.expiresAt?.let { putExtra("expiresAt", it) }
        }

        // Try direct launch (works when app is in foreground)
        try { startActivity(intent) } catch (_: Exception) {}

        // Update indicator notification to point to ApprovalActivity
        // so tapping the indicator opens the approval screen
        val pending = PendingIntent.getActivity(
            this, 2, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val riskLabel = if (msg.risk == "high") "⚠ HIGH" else msg.risk
        val extras = Bundle().apply { putBoolean("show_heytap_indicator", true) }
        val notification = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Approve: ${msg.tool}")
            .setContentText("$riskLabel — ${msg.command.take(50)}")
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentIntent(pending)
            .setOngoing(true)
            .setCategory("navigation")
            .addExtras(extras)
            .build()
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, notification)

        vibrateNotification()
    }

    // ── Notification / vibration ──

    private fun vibrateNotification() {
        val vibrator = getSystemService(Context.VIBRATOR_SERVICE) as android.os.Vibrator
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator.vibrate(android.os.VibrationEffect.createWaveform(longArrayOf(0, 200, 100, 200), -1))
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(longArrayOf(0, 200, 100, 200), -1)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Clawd BLE",
                NotificationManager.IMPORTANCE_LOW
            ).apply { description = "Maintains the BLE link to the desktop" }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun buildNotification(status: String): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val extras = Bundle().apply {
            putBoolean("show_heytap_indicator", true)
        }
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Clawd")
            .setContentText(status)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setCategory("navigation")
            .addExtras(extras)
            .build()
    }

    private fun updateNotification(status: String) {
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, buildNotification(status))
    }
}
