package com.clawd.watch

import android.app.AlertDialog
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.clawd.watch.data.ApprovalResponse
import com.clawd.watch.data.PairingStore
import com.clawd.watch.data.SettingsStore
import com.clawd.watch.data.WatchMessage
import com.clawd.watch.domain.ClawdState
import com.clawd.watch.domain.StateChipConfig
import com.clawd.watch.domain.ThemeConfig
import com.clawd.watch.gesture.FlickDetector
import com.clawd.watch.power.PowerManager
import com.clawd.watch.renderer.PetView
import com.clawd.watch.service.BleService

class MainActivity : AppCompatActivity() {

    private lateinit var petView: PetView
    private lateinit var connectionIndicator: TextView
    private lateinit var stateChip: TextView
    private lateinit var reconnectButton: TextView
    private lateinit var gestureFeedback: TextView
    private lateinit var highRiskBorder: View
    private lateinit var highRiskHint: TextView
    private lateinit var batteryWarning: TextView

    private var bleService: BleService? = null
    private var bound = false
    private var flickDetector: FlickDetector? = null
    private var bleConnected = false

    private var pendingApprovalRequestId: String? = null
    private var pendingApprovalRisk: String? = null
    private var demoMode = false
    private var demoStateIndex = 0

    private val syncHandler = Handler(Looper.getMainLooper())
    private var syncCompleteRunnable: Runnable? = null

    private var isSyncing = false

    // Disconnect duration tracking
    private var disconnectedSince = 0L
    private val disconnectTimerHandler = Handler(Looper.getMainLooper())
    private val disconnectTimerRunnable = object : Runnable {
        override fun run() {
            if (!bleConnected && disconnectedSince > 0 && !isSyncing) {
                val elapsed = SystemClock.elapsedRealtime() - disconnectedSince
                val minutes = (elapsed / 60_000).toInt()
                connectionIndicator.text = if (minutes < 1) "Disconnected" else "Disconnected ${minutes}m"
                disconnectTimerHandler.postDelayed(this, 60_000L)
            }
        }
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val service = (binder as BleService.LocalBinder).getService()
            bleService = service
            bound = true

            service.onWatchMessage = { msg -> runOnUiThread { handleMessage(msg) } }
            service.onConnectionStateChanged = { connected -> runOnUiThread { updateConnectionState(connected) } }
            service.onPowerModeChanged = { mode -> runOnUiThread { handlePowerModeChange(mode) } }
            service.onThemeChanged = { runOnUiThread { onThemeSynced() } }
            service.onThemeProgress = { p -> runOnUiThread { showTransferProgress(p) } }
            service.onBatteryLevelChanged = { level -> runOnUiThread { updateBatteryWarning(level) } }
            petView.onRecordingChanged = { r -> runOnUiThread { onRecordingChanged(r) } }
            petView.onRecordProgress = { n, c, t -> runOnUiThread { showRecordProgress(n, c, t) } }

            val cached = service.getLastState()
            if (cached != null) runOnUiThread { handleCompactState(cached) }
            runOnUiThread { updateConnectionState(service.isConnected()) }
        }

        override fun onServiceDisconnected(name: ComponentName?) { bleService = null; bound = false }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!PairingStore.isPaired(this)) {
            startActivity(Intent(this, PairingActivity::class.java)); finish(); return
        }

        if (SettingsStore.getKeepScreenOn(this)) {
            window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }

        setContentView(R.layout.activity_main)
        petView = findViewById(R.id.pet_view)
        connectionIndicator = findViewById(R.id.connection_indicator)
        stateChip = findViewById(R.id.state_chip)
        reconnectButton = findViewById(R.id.reconnect_button)
        gestureFeedback = findViewById(R.id.gesture_feedback)
        highRiskBorder = findViewById(R.id.high_risk_border)
        highRiskHint = findViewById(R.id.high_risk_hint)
        batteryWarning = findViewById(R.id.battery_warning)

        reconnectButton.setOnClickListener { reconnect() }
        highRiskHint.setOnClickListener { openApprovalForHighRisk() }

        if (PairingStore.isPaired(this)) {
            demoMode = false; updateConnectionState(false); updateStateChip(ClawdState.IDLE)
            BleService.start(this)
        } else {
            demoMode = true; connectionIndicator.text = "Demo Mode"
            connectionIndicator.setTextColor(0xFFFF9800.toInt()); setupDemoTapCycle()
        }

        createFlickDetector()
        petView.setOnLongClickListener { if (!demoMode) showContextMenu(); true }
    }

    override fun onStart() {
        super.onStart()
        if (PairingStore.isPaired(this)) bindService(Intent(this, BleService::class.java), connection, Context.BIND_AUTO_CREATE)
        flickDetector?.start(); petView.resume()
    }

    override fun onResume() {
        super.onResume()
        applyScreenOnSetting()
        recreateFlickDetectorIfNeeded()
    }

    override fun onStop() {
        flickDetector?.stop(); petView.pause()
        disconnectTimerHandler.removeCallbacks(disconnectTimerRunnable)
        if (bound) {
            bleService?.onWatchMessage = null; bleService?.onConnectionStateChanged = null
            bleService?.onPowerModeChanged = null; bleService?.onThemeChanged = null
            bleService?.onThemeProgress = null; bleService?.onBatteryLevelChanged = null
            petView.onRecordingChanged = null; petView.onRecordProgress = null
            unbindService(connection); bound = false
        }
        super.onStop()
    }

    // ── FlickDetector lifecycle ──

    private var currentSensitivity = "standard"
    private var currentVibration = "normal"

    private fun createFlickDetector() {
        currentSensitivity = SettingsStore.getGestureSensitivity(this)
        currentVibration = SettingsStore.getVibrationStrength(this)
        flickDetector = FlickDetector(
            this,
            FlickDetector.sensitivityMultiplier(currentSensitivity),
            FlickDetector.vibrationAmplitude(currentVibration)
        ) { g -> runOnUiThread { handleGesture(g) } }
    }

    private fun recreateFlickDetectorIfNeeded() {
        val newSens = SettingsStore.getGestureSensitivity(this)
        val newVib = SettingsStore.getVibrationStrength(this)
        if (newSens != currentSensitivity || newVib != currentVibration) {
            flickDetector?.stop()
            createFlickDetector()
            flickDetector?.start()
        }
    }

    private fun applyScreenOnSetting() {
        if (SettingsStore.getKeepScreenOn(this)) {
            window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    // ── Connection state UI ──

    private fun updateConnectionState(connected: Boolean) {
        bleConnected = connected
        if (!connected && isSyncing) {
            isSyncing = false
            stateChip.visibility = View.GONE
            cancelSyncCompleteTimer()
        }
        if (isSyncing) return
        connectionIndicator.text = if (connected) "Connected" else "Disconnected"
        connectionIndicator.setTextColor(if (connected) StateChipConfig.COLOR_INDICATOR_TEXT else StateChipConfig.COLOR_DISCONNECTED)
        petView.alpha = if (connected) 1.0f else 0.5f
        reconnectButton.visibility = if (!connected) View.VISIBLE else View.GONE
        if (!connected) {
            petView.state = ClawdState.IDLE
            updateStateChip(ClawdState.IDLE)
            if (disconnectedSince == 0L) disconnectedSince = SystemClock.elapsedRealtime()
            disconnectTimerHandler.removeCallbacks(disconnectTimerRunnable)
            disconnectTimerHandler.postDelayed(disconnectTimerRunnable, 60_000L)
        } else {
            disconnectedSince = 0L
            disconnectTimerHandler.removeCallbacks(disconnectTimerRunnable)
        }
    }

    private fun updateStateChip(state: ClawdState) {
        if (isSyncing) return
        if (!StateChipConfig.isChipVisible(state)) { stateChip.visibility = View.GONE; return }
        stateChip.visibility = View.VISIBLE
        val color = StateChipConfig.chipColor(state)
        val dot = "● "
        stateChip.text = android.text.SpannableString("$dot${StateChipConfig.chipLabel(state)}").apply {
            setSpan(android.text.style.ForegroundColorSpan(color), 0, dot.length, android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
    }

    // ── Battery warning ──

    private fun updateBatteryWarning(level: Int) {
        batteryWarning.visibility = if (level <= 15) View.VISIBLE else View.GONE
    }

    // ── Sync UX ──

    private fun showTransferProgress(progress: BleService.ThemeSyncProgress) {
        if (progress.fraction >= 1f) return
        enterSyncMode()
        val file = progress.currentFile ?: "..."
        connectionIndicator.text = "📥 Receiving theme"
        stateChip.visibility = View.VISIBLE
        stateChip.text = "$file (${progress.fileIndex}/${progress.fileTotal})"
        stateChip.setTextColor(0xAAFFFFFF.toInt())
    }

    private fun onThemeSynced() {
        enterSyncMode()
        connectionIndicator.text = "🎬 Preparing animations"
        stateChip.visibility = View.GONE
        petView.preRecordAll {
            runOnUiThread { exitSyncMode() }
        }
    }

    @Suppress("UNUSED_PARAMETER")
    private fun onRecordingChanged(recording: Boolean) {}

    private fun showRecordProgress(name: String, current: Int, total: Int) {
        if (!isSyncing) return
        connectionIndicator.text = "🎬 Preparing animations"
        stateChip.visibility = View.VISIBLE
        stateChip.text = "$name ($current/$total)"
        stateChip.setTextColor(0xAAFFFFFF.toInt())
    }

    private fun enterSyncMode() {
        isSyncing = true
        reconnectButton.visibility = View.GONE
        connectionIndicator.visibility = View.VISIBLE
        connectionIndicator.setTextColor(0xFFFF9800.toInt())
        stateChip.visibility = View.GONE
    }

    private fun exitSyncMode() {
        isSyncing = false
        stateChip.visibility = View.GONE
        val name = ThemeConfig.active.name.replaceFirstChar { it.uppercase() }
        connectionIndicator.text = "✅ Theme: $name"
        connectionIndicator.setTextColor(0xFF4CAF50.toInt())
        petView.reloadForThemeChange()
        cancelSyncCompleteTimer()
        syncCompleteRunnable = Runnable { updateConnectionState(bleConnected) }
        syncHandler.postDelayed(syncCompleteRunnable!!, 3000)
    }

    private fun cancelSyncCompleteTimer() {
        syncCompleteRunnable?.let { syncHandler.removeCallbacks(it) }; syncCompleteRunnable = null
    }

    // ── Message handling ──

    private fun handleMessage(msg: WatchMessage) {
        when (msg) {
            is WatchMessage.CompactState -> handleCompactState(msg)
            is WatchMessage.ApprovalRequest -> handleApprovalRequest(msg)
        }
    }

    private fun handleCompactState(msg: WatchMessage.CompactState) {
        val state = ClawdState.fromStringOrIdle(msg.state)
        if (!isSyncing) {
            petView.state = state
            petView.activeSessionCount = msg.activeCount
            updateStateChip(state)
        }
        updateConnectionState(true)
    }

    private fun handleApprovalRequest(msg: WatchMessage.ApprovalRequest) {
        pendingApprovalRequestId = msg.requestId; pendingApprovalRisk = msg.risk
        flickDetector?.highRiskLocked = msg.risk == "high"
        if (msg.risk == "high") showHighRiskIndication()
    }

    // ── Gesture handling + visual feedback ──

    private fun handleGesture(gestureType: FlickDetector.GestureType) {
        val reqId = pendingApprovalRequestId ?: return
        val decision = when (gestureType) {
            FlickDetector.GestureType.FLICK_APPROVE -> "allow-once"
            FlickDetector.GestureType.SHAKE_DENY -> "deny"
        }
        bleService?.sendApprovalResponse(ApprovalResponse(reqId, decision, "gesture"))
        showGestureFeedback(gestureType)
        clearPendingApproval()
    }

    private fun showGestureFeedback(type: FlickDetector.GestureType) {
        val (symbol, color) = when (type) {
            FlickDetector.GestureType.FLICK_APPROVE -> "✓" to 0xFF4CAF50.toInt()
            FlickDetector.GestureType.SHAKE_DENY -> "✗" to 0xFFF44336.toInt()
        }
        gestureFeedback.text = symbol
        gestureFeedback.setTextColor(color)
        gestureFeedback.alpha = 1.0f
        gestureFeedback.visibility = View.VISIBLE
        gestureFeedback.animate().alpha(0f).setDuration(1000).withEndAction {
            gestureFeedback.visibility = View.GONE
        }.start()
    }

    private fun clearPendingApproval() {
        pendingApprovalRequestId = null; pendingApprovalRisk = null
        flickDetector?.highRiskLocked = false
        hideHighRiskIndication()
    }

    // ── High-risk indication ──

    private fun showHighRiskIndication() {
        highRiskBorder.visibility = View.VISIBLE
        highRiskHint.visibility = View.VISIBLE
        pulseHighRiskBorder()
    }

    private fun pulseHighRiskBorder() {
        if (highRiskBorder.visibility != View.VISIBLE) return
        highRiskBorder.animate().alpha(0.3f).setDuration(500).withEndAction {
            if (highRiskBorder.visibility == View.VISIBLE) {
                highRiskBorder.animate().alpha(1.0f).setDuration(500).withEndAction {
                    pulseHighRiskBorder()
                }.start()
            }
        }.start()
    }

    private fun hideHighRiskIndication() {
        highRiskBorder.animate().cancel()
        highRiskBorder.visibility = View.GONE
        highRiskHint.visibility = View.GONE
        highRiskBorder.alpha = 1.0f
    }

    private fun openApprovalForHighRisk() {
        hideHighRiskIndication()
        val intent = Intent(this, ApprovalActivity::class.java).apply {
            putExtra("requestId", pendingApprovalRequestId ?: "")
            putExtra("risk", pendingApprovalRisk ?: "high")
        }
        startActivity(intent)
    }

    // ── Context menu ──

    private fun showContextMenu() {
        val items = mutableListOf<String>()
        val actions = mutableListOf<() -> Unit>()

        if (bleConnected) {
            items.add("Disconnect"); actions.add { manualDisconnect() }
        } else {
            items.add("Reconnect"); actions.add { reconnect() }
        }
        items.add("Themes"); actions.add { openThemeManager() }
        items.add("Re-pair"); actions.add { confirmRePair() }
        items.add("Settings"); actions.add { openSettings() }
        items.add("About"); actions.add { showAbout() }

        AlertDialog.Builder(this)
            .setItems(items.toTypedArray()) { _, w -> actions[w]() }
            .show()
    }

    private fun manualDisconnect() {
        bleService?.manualDisconnect()
    }

    private fun reconnect() {
        bleService?.resumeAdvertising()
        reconnectButton.visibility = View.GONE
        connectionIndicator.text = "Reconnecting..."
        connectionIndicator.setTextColor(0xFFFF9800.toInt())
    }

    private fun confirmRePair() {
        AlertDialog.Builder(this)
            .setTitle("Re-pair?")
            .setMessage("Current connection will be lost.")
            .setPositiveButton("Re-pair") { _, _ -> rePair() }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun rePair() {
        PairingStore.clear(this); BleService.stop(this)
        startActivity(Intent(this, PairingActivity::class.java)); finish()
    }

    private fun openThemeManager() {
        startActivity(Intent(this, ThemeManagerActivity::class.java))
    }

    private fun openSettings() {
        startActivity(Intent(this, SettingsActivity::class.java))
    }

    private fun showAbout() {
        val v = try { packageManager.getPackageInfo(packageName, 0).versionName } catch (_: Exception) { "?" }
        AlertDialog.Builder(this).setTitle("Clawd").setMessage("Version $v").setPositiveButton("OK", null).show()
    }

    // ── Demo mode ──

    private val demoStates = listOf(
        ClawdState.IDLE, ClawdState.THINKING, ClawdState.WORKING, ClawdState.JUGGLING,
        ClawdState.ATTENTION, ClawdState.SWEEPING, ClawdState.NOTIFICATION, ClawdState.ERROR,
        ClawdState.CARRYING, ClawdState.SLEEPING, ClawdState.YAWNING, ClawdState.DOZING,
        ClawdState.COLLAPSING, ClawdState.WAKING,
    )

    private fun setupDemoTapCycle() {
        petView.setOnClickListener {
            demoStateIndex = (demoStateIndex + 1) % demoStates.size
            petView.state = demoStates[demoStateIndex]; updateStateChip(demoStates[demoStateIndex])
        }
        updateStateChip(demoStates[0])
    }

    // ── Power mode ──

    private fun handlePowerModeChange(mode: PowerManager.PowerMode) {
        when (mode) {
            PowerManager.PowerMode.SCREEN_OFF -> { petView.pause(); flickDetector?.stop() }
            PowerManager.PowerMode.LOW_BATTERY, PowerManager.PowerMode.NORMAL -> { petView.resume(); flickDetector?.start() }
        }
    }
}
