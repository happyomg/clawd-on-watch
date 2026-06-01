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
import android.os.SystemClock
import android.util.Log
import com.clawd.watch.ApprovalActivity
import com.clawd.watch.MainActivity
import com.clawd.watch.R
import com.clawd.watch.data.ApprovalResponse
import com.clawd.watch.data.WatchMessage
import com.clawd.watch.domain.ThemeCache
import com.clawd.watch.domain.ThemeConfig
import com.clawd.watch.data.PairingStore
import com.clawd.watch.domain.ThemeReceiver
import com.clawd.watch.power.PowerManager
import org.json.JSONObject
import android.content.ComponentName
import java.io.File
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
 *
 * Security trust boundary: GATT characteristics use PERMISSION_READ/WRITE
 * (not *_ENCRYPTED). Any nearby BLE Central can connect. This is acceptable
 * for a personal-use, short-range (~10m) desktop companion — the watch only
 * renders state and relays approval decisions (desktop validates via
 * watchApprovalId matching). Future hardening: PERMISSION_*_ENCRYPTED +
 * bonded-device-only check in onConnectionStateChange.
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
        val CHAR_THEME: UUID = UUID.fromString("00000cd5-0000-1000-8000-00805f9b34fb")
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
    @Volatile private var advertiseRequested = false
    @Volatile var manualOffline = false
        private set

    // Connection watchdog: if the central disappears without sending an
    // LL_TERMINATE_IND (e.g. macOS CoreBluetooth cache), the watch stays
    // in connected state indefinitely. This timer forces a disconnect
    // after 30s of no GATT activity, recovering advertising so the watch
    // can be re-discovered.
    @Volatile private var lastCentralActivityMs = 0L
    private val connectionWatchdog = object : Runnable {
        @SuppressLint("MissingPermission")
        override fun run() {
            val device = connectedDevice
            if (device != null) {
                val elapsed = SystemClock.elapsedRealtime() - lastCentralActivityMs
                if (elapsed > 30_000L) {
                    Log.w(TAG, "No central activity for ${elapsed}ms \u2014 forcing disconnect")
                    forceDisconnectCentral()
                    return // Don't repost; advertising will restart the loop on next connect
                }
            }
            handler.postDelayed(this, 10_000L)
        }
    }

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
    var onBatteryLevelChanged: ((Int) -> Unit)? = null
    /** Fired after theme transfer completes and becomes the active theme. */
    var onThemeChanged: (() -> Unit)? = null

    data class ThemeSyncProgress(
        val fraction: Float,
        val currentFile: String?,
        val fileIndex: Int,
        val fileTotal: Int
    )
    /** Fired during theme transfer with file-level progress. */
    var onThemeProgress: ((ThemeSyncProgress) -> Unit)? = null

    private val themeReceiver by lazy { ThemeReceiver(File(filesDir, "themes")) }

    @Volatile private var lastCompactState: WatchMessage.CompactState? = null

    fun getLastState(): WatchMessage.CompactState? {
        if (lastCompactState != null) return lastCompactState
        val prefs = getSharedPreferences("clawd_state", Context.MODE_PRIVATE)
        val s = prefs.getString("last_s", null) ?: return null
        return WatchMessage.CompactState(
            state = s,
            activeCount = prefs.getInt("last_n", 0),
            themeHash = prefs.getString("last_th", null)
        )
    }

    private fun cacheState(state: WatchMessage.CompactState) {
        lastCompactState = state
        getSharedPreferences("clawd_state", Context.MODE_PRIVATE).edit()
            .putString("last_s", state.state)
            .putInt("last_n", state.activeCount)
            .putString("last_th", state.themeHash)
            .apply()
    }

    fun isConnected(): Boolean = connectedDevice != null
    fun getConnectedDeviceAddress(): String? = connectedDevice?.address
    @SuppressLint("MissingPermission")
    fun getConnectedDeviceName(): String? = connectedDevice?.name

    inner class LocalBinder : Binder() {
        fun getService(): BleService = this@BleService
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        restoreActiveTheme()
        powerManager = PowerManager(this)
        powerManager.onModeChanged = { mode -> onPowerModeChanged?.invoke(mode) }
        powerManager.onBatteryChanged = { level -> onBatteryLevelChanged?.invoke(level) }
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
        handler.removeCallbacks(connectionWatchdog)
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

        // CWD5: Theme Data — desktop writes manifest/chunk/done frames here
        val themeChar = BluetoothGattCharacteristic(
            CHAR_THEME,
            BluetoothGattCharacteristic.PROPERTY_WRITE or BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE,
            BluetoothGattCharacteristic.PERMISSION_WRITE
        )
        service.addCharacteristic(themeChar)

        gattServer!!.addService(service)
        Log.i(TAG, "GATT server started with service $SERVICE_UUID")
    }

    private val gattServerCallback = object : BluetoothGattServerCallback() {

        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    val pairedAddr = PairingStore.getDeviceAddress(this@BleService)
                    if (pairedAddr != null && pairedAddr != "peripheral-mode" && pairedAddr != device.address) {
                        Log.w(TAG, "Rejecting unknown Central: ${device.address} (paired=$pairedAddr)")
                        gattServer?.cancelConnection(device)
                        return
                    }
                    Log.i(TAG, "Central connected: ${device.address}")
                    connectedDevice = device
                    lastCentralActivityMs = SystemClock.elapsedRealtime()
                    stopAdvertising()
                    handler.removeCallbacks(connectionWatchdog)
                    handler.postDelayed(connectionWatchdog, 10_000L)
                    handler.post {
                        onConnectionStateChanged?.invoke(true)
                        updateNotification("Connected to ${device.name ?: device.address}")
                    }
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    Log.i(TAG, "Central disconnected: ${device.address}")
                    if (connectedDevice?.address == device.address) {
                        connectedDevice = null
                        cwd3Subscribers.remove(device.address)
                        handler.removeCallbacks(connectionWatchdog)
                        handler.post {
                            onConnectionStateChanged?.invoke(false)
                            updateNotification("Disconnected")
                        }
                        if (!manualOffline) startAdvertising()
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
            lastCentralActivityMs = android.os.SystemClock.elapsedRealtime()
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
            val entries = synchronized(preparedWriteBuffer) {
                val copy = preparedWriteBuffer.toMap()
                preparedWriteBuffer.clear()
                copy
            }
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
            lastCentralActivityMs = SystemClock.elapsedRealtime()
            when (characteristic.uuid) {
                CHAR_META -> {
                    val meta = JSONObject().apply {
                        put("deviceName", Build.MODEL)
                        put("version", "0.1.0")
                        put("role", "peripheral")
                        val prefs = getSharedPreferences("clawd_state", Context.MODE_PRIVATE)
                        val syncedHash = prefs.getString("active_theme_hash", null)
                        put("themeHash", syncedHash ?: "")
                        val cached = ThemeCache.listCachedHashes(File(filesDir, "themes"))
                        put("cachedThemes", org.json.JSONArray(cached))
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
        if (isAdvertising || advertiseRequested) return
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

        advertiseRequested = true
        advertiser?.startAdvertising(settings, data, advertiseCallback)
    }

    @SuppressLint("MissingPermission")
    @Synchronized
    private fun stopAdvertising() {
        if (!isAdvertising && !advertiseRequested) return
        advertiser?.stopAdvertising(advertiseCallback)
        isAdvertising = false
        advertiseRequested = false
    }

    @Volatile private var advertiseRetryCount = 0
    private val MAX_ADVERTISE_RETRIES = 3

    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings) {
            isAdvertising = true
            advertiseRetryCount = 0
            Log.i(TAG, "Advertising started")
            handler.post { updateNotification("Advertising...") }
        }

        override fun onStartFailure(errorCode: Int) {
            isAdvertising = false
            advertiseRequested = false
            Log.e(TAG, "Advertising failed: $errorCode (retry $advertiseRetryCount/$MAX_ADVERTISE_RETRIES)")
            // After a cancelConnection the BLE adapter may briefly reject
            // advertising (INTERNAL_ERROR=4). Retry with back-off so the
            // watch reliably returns to discoverable state.
            if (advertiseRetryCount < MAX_ADVERTISE_RETRIES) {
                advertiseRetryCount++
                val delay = (500L * advertiseRetryCount)
                handler.postDelayed({ startAdvertising() }, delay)
            } else {
                advertiseRetryCount = 0
                handler.post { updateNotification("Advertising failed ($errorCode)") }
            }
        }
    }

    private fun dispatchWrite(uuid: UUID, data: ByteArray) {
        lastCentralActivityMs = SystemClock.elapsedRealtime()
        when (uuid) {
            CHAR_STATE -> handleStateWrite(data)
            CHAR_APPROVAL_REQ -> handleApprovalRequestWrite(data)
            CHAR_THEME -> handleThemeWrite(data)
            else -> Log.w(TAG, "Write to unknown characteristic: $uuid")
        }
    }

    private fun findCharUuidByHandle(handle: Int): UUID? {
        val service = gattServer?.getService(SERVICE_UUID) ?: return null
        return service.characteristics.firstOrNull { it.instanceId == handle }?.uuid
    }

    // ── Incoming data handlers ──

    private fun handleStateWrite(data: ByteArray) {
        val text = data.toString(Charsets.UTF_8)
        try {
            val json = JSONObject(text)
            // Theme frames (manifest/chunk/done) have a "t" field
            if (json.has("t")) {
                handleThemeWrite(data)
                return
            }
            // Explicit disconnect signal
            if (json.optString("type") == "disconnect") {
                Log.i(TAG, "Received explicit disconnect signal from Central")
                forceDisconnectCentral()
                return
            }
            // Activate a locally cached theme without full transfer
            if (json.optString("type") == "activate_theme") {
                handleActivateTheme(json.optString("hash", ""))
                return
            }
            val msg = WatchMessage.parse(json)
            if (msg != null) {
                if (msg is WatchMessage.CompactState) {
                    val prev = lastCompactState
                    cacheState(msg)
                    updateNotification(msg.state)
                    requestComplicationUpdate()
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

    /**
     * Actively tear down the Central connection and resume advertising.
     * Called when the desktop sends an explicit `{"type":"disconnect"}` via CWD1,
     * or by the 30s keepalive watchdog as a fallback for abnormal disconnects.
     */
    @SuppressLint("MissingPermission")
    private fun forceDisconnectCentral() {
        val device = connectedDevice ?: return
        try { gattServer?.cancelConnection(device) } catch (_: Exception) {}
        connectedDevice = null
        cwd3Subscribers.remove(device.address)
        handler.removeCallbacks(connectionWatchdog)
        handler.post {
            onConnectionStateChanged?.invoke(false)
            updateNotification("Disconnected")
        }
        if (!manualOffline) startAdvertising()
    }

    private fun handleActivateTheme(hash: String) {
        if (hash.isBlank()) return
        val themesRoot = File(filesDir, "themes")
        val dir = File(themesRoot, hash)
        val manifest = ThemeConfig.loadFromDir(dir)
        if (manifest != null) {
            val prefs = getSharedPreferences("clawd_state", Context.MODE_PRIVATE)
            val prev = prefs.getString("active_theme_hash", null)
            ThemeConfig.setActive(manifest)
            ThemeCache.touch(themesRoot, hash)
            prefs.edit()
                .putString("active_theme_hash", hash)
                .putString("prev_theme_hash", prev)
                .apply()
            handler.post {
                onThemeProgress?.invoke(ThemeSyncProgress(1f, null, 0, 0))
                onThemeChanged?.invoke()
            }
            Log.i(TAG, "activate_theme: switched to cached $hash (${manifest.name})")
        } else {
            Log.w(TAG, "activate_theme: $hash not in local cache, ignoring")
        }
    }

    fun manualDisconnect() {
        manualOffline = true
        val device = connectedDevice
        if (device != null) {
            forceDisconnectCentral()
        } else {
            stopAdvertising()
        }
        handler.post { updateNotification("Offline (manual)") }
    }

    fun resumeAdvertising() {
        manualOffline = false
        if (connectedDevice == null) {
            startAdvertising()
            handler.post { updateNotification("Advertising...") }
        }
    }

    /** Restore the last synced theme from filesDir so it survives a restart. */
    private fun restoreActiveTheme() {
        try {
            val hash = getSharedPreferences("clawd_state", Context.MODE_PRIVATE)
                .getString("active_theme_hash", null) ?: return
            val dir = File(File(filesDir, "themes"), hash)
            ThemeConfig.loadFromDir(dir)?.let {
                ThemeConfig.setActive(it)
                Log.i(TAG, "restored synced theme ${it.name} ($hash)")
            }
        } catch (e: Exception) {
            Log.w(TAG, "restoreActiveTheme failed: ${e.message}")
        }
    }

    private var themeWriteCount = 0

    private fun handleThemeWrite(data: ByteArray) {
        themeWriteCount++
        val text = data.toString(Charsets.UTF_8)
        try {
            val json = JSONObject(text)
            val frameType = json.optString("t", "?")
            if (themeWriteCount % 50 == 1 || frameType == "manifest" || frameType == "done") {
                Log.i(TAG, "handleThemeWrite #$themeWriteCount: t=$frameType len=${data.size}")
            }
            val manifest = themeReceiver.onFrame(json)
            if (manifest == null) {
                val progress = ThemeSyncProgress(
                    fraction = themeReceiver.progress(),
                    currentFile = themeReceiver.currentFileName(),
                    fileIndex = themeReceiver.fileIndex(),
                    fileTotal = themeReceiver.fileCount()
                )
                handler.post { onThemeProgress?.invoke(progress) }
                return
            }
            // Transfer complete — activate, persist (survives restarts), prune
            // the cache, and re-render.
            val prefs = getSharedPreferences("clawd_state", Context.MODE_PRIVATE)
            val prev = prefs.getString("active_theme_hash", null)
            ThemeConfig.setActive(manifest)
            val themesRoot = File(filesDir, "themes")
            ThemeCache.touch(themesRoot, manifest.hash)
            prefs.edit()
                .putString("active_theme_hash", manifest.hash)
                .putString("prev_theme_hash", prev)
                .apply()
            // LRU eviction: protect current + bundled; rest evicted by age
            ThemeCache.cleanup(
                themesRoot,
                setOfNotNull(ThemeConfig.bundledClawd.hash, manifest.hash)
            )
            handler.post {
                onThemeProgress?.invoke(ThemeSyncProgress(1f, null, 0, 0))
                onThemeChanged?.invoke()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Bad theme frame: ${e.message}")
        }
    }

    @Suppress("DEPRECATION")
    private fun bringToForeground() {
        vibrateStateChange()

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
            val questions = if (json.has("questions")) {
                val arr = json.getJSONArray("questions")
                (0 until arr.length()).map { i ->
                    val qObj = arr.getJSONObject(i)
                    val opts = if (qObj.has("opts")) {
                        val oArr = qObj.getJSONArray("opts")
                        (0 until oArr.length()).map { j -> oArr.getString(j) }
                    } else emptyList()
                    WatchMessage.ApprovalRequest.Question(qObj.optString("q", ""), opts)
                }
            } else null
            val msg = WatchMessage.ApprovalRequest(
                requestId = json.getString("requestId"),
                sessionId = json.optString("sessionId", ""),
                tool = json.optString("tool", ""),
                command = json.optString("command", ""),
                risk = json.optString("risk", "medium"),
                timeoutMs = if (json.has("timeoutMs")) json.getLong("timeoutMs") else null,
                expiresAt = if (json.has("expiresAt")) json.getLong("expiresAt") else null,
                questions = questions
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
            if (response.answers != null) {
                put("answers", JSONObject(response.answers))
            }
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
            msg.questions?.let { qs ->
                putExtra("questionTexts", qs.map { it.text }.toTypedArray())
                putExtra("questionOptCounts", qs.map { it.options.size }.toIntArray())
                putExtra("questionOpts", qs.flatMap { it.options }.toTypedArray())
            }
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
    }

    // ── Complication update ──

    private fun requestComplicationUpdate() {
        try {
            val cn = ComponentName(this, "com.clawd.watch.complication.StateComplicationService")
            androidx.wear.watchface.complications.datasource.ComplicationDataSourceUpdateRequester
                .create(this, cn)
                .requestUpdateAll()
        } catch (_: Exception) {}
    }

    // ── Notification / vibration ──

    private fun vibrateStateChange() {
        val vibrator = getSystemService(Context.VIBRATOR_SERVICE) as android.os.Vibrator
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator.vibrate(android.os.VibrationEffect.createOneShot(40, 80))
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(40)
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
