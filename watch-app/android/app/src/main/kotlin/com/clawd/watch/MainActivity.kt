package com.clawd.watch

import android.app.AlertDialog
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.clawd.watch.data.ApprovalResponse
import com.clawd.watch.data.PairingStore
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

    private var bleService: BleService? = null
    private var bound = false
    private var flickDetector: FlickDetector? = null
    private var bleConnected = false

    private var pendingApprovalRequestId: String? = null
    private var pendingApprovalRisk: String? = null
    private var demoMode = false
    private var demoStateIndex = 0
    private var themeSyncNeeded = false

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val service = (binder as BleService.LocalBinder).getService()
            bleService = service
            bound = true

            service.onWatchMessage = { msg -> runOnUiThread { handleMessage(msg) } }
            service.onConnectionStateChanged = { connected ->
                runOnUiThread { updateConnectionState(connected) }
            }
            service.onPowerModeChanged = { mode ->
                runOnUiThread { handlePowerModeChange(mode) }
            }
            service.onThemeChanged = {
                runOnUiThread {
                    themeSyncNeeded = false
                    petView.reloadForThemeChange()
                    updateConnectionState(bleConnected) // clear the sync label
                }
            }
            service.onThemeProgress = { p ->
                runOnUiThread { showSyncProgress(p) }
            }
            val cached = service.getLastState()
            if (cached != null) {
                runOnUiThread { handleCompactState(cached) }
            }
            runOnUiThread { updateConnectionState(service.isConnected()) }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            bleService = null
            bound = false
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (!PairingStore.isPaired(this)) {
            startActivity(Intent(this, PairingActivity::class.java))
            finish()
            return
        }

        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_main)

        petView = findViewById(R.id.pet_view)
        connectionIndicator = findViewById(R.id.connection_indicator)
        stateChip = findViewById(R.id.state_chip)

        if (PairingStore.isPaired(this)) {
            demoMode = false
            updateConnectionState(false)
            updateStateChip(ClawdState.IDLE)
            BleService.start(this)
        } else {
            demoMode = true
            connectionIndicator.text = "Demo Mode"
            connectionIndicator.setTextColor(0xFFFF9800.toInt())
            setupDemoTapCycle()
        }

        flickDetector = FlickDetector(this) { gestureType ->
            runOnUiThread { handleGesture(gestureType) }
        }

        petView.setOnLongClickListener {
            if (!demoMode) showContextMenu()
            true
        }
    }

    override fun onStart() {
        super.onStart()
        if (PairingStore.isPaired(this)) {
            bindService(
                Intent(this, BleService::class.java),
                connection,
                Context.BIND_AUTO_CREATE
            )
        }
        flickDetector?.start()
        petView.resume()
    }

    override fun onStop() {
        flickDetector?.stop()
        petView.pause()
        if (bound) {
            bleService?.onWatchMessage = null
            bleService?.onConnectionStateChanged = null
            bleService?.onPowerModeChanged = null
            bleService?.onThemeChanged = null
            bleService?.onThemeProgress = null
            unbindService(connection)
            bound = false
        }
        super.onStop()
    }

    private fun updateConnectionState(connected: Boolean) {
        bleConnected = connected
        connectionIndicator.text = if (connected) "Connected" else "Disconnected"
        connectionIndicator.setTextColor(
            if (connected) StateChipConfig.COLOR_INDICATOR_TEXT else StateChipConfig.COLOR_DISCONNECTED
        )
        petView.alpha = if (connected) 1.0f else 0.5f
    }

    /** Show theme-transfer progress in the connection indicator. */
    private fun showSyncProgress(fraction: Float) {
        if (fraction >= 1f) {
            updateConnectionState(bleConnected)
            return
        }
        connectionIndicator.text = "🔄 Syncing ${(fraction * 100).toInt()}%"
        connectionIndicator.setTextColor(0xFFFF9800.toInt())
    }

    private fun updateStateChip(state: ClawdState) {
        if (!StateChipConfig.isChipVisible(state)) {
            stateChip.visibility = android.view.View.GONE
            return
        }
        stateChip.visibility = android.view.View.VISIBLE
        val color = StateChipConfig.chipColor(state)
        val dot = "● "
        stateChip.text = android.text.SpannableString("$dot${StateChipConfig.chipLabel(state)}").apply {
            setSpan(
                android.text.style.ForegroundColorSpan(color),
                0, dot.length,
                android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }
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
        // Theme-agnostic: PetView resolves the SVG locally from state + count
        // against the active theme manifest. The desktop no longer dictates a
        // filename.
        petView.state = state
        petView.activeSessionCount = msg.activeCount
        updateStateChip(state)
        updateConnectionState(true)
        onThemeHash(msg.themeHash)
    }

    /**
     * Compare the desktop's theme fingerprint against the watch's active theme.
     * A mismatch means the watch is rendering a stale theme; the actual sync
     * (CWD5 transfer) lands in Phase 2 — for now we just record the gap.
     */
    private fun onThemeHash(desktopHash: String?) {
        if (desktopHash.isNullOrEmpty()) return
        val localHash = ThemeConfig.active.hash
        themeSyncNeeded = desktopHash != localHash
    }

    private fun handleApprovalRequest(msg: WatchMessage.ApprovalRequest) {
        pendingApprovalRequestId = msg.requestId
        pendingApprovalRisk = msg.risk
        flickDetector?.highRiskLocked = msg.risk == "high"
    }

    private fun handleGesture(gestureType: FlickDetector.GestureType) {
        val reqId = pendingApprovalRequestId ?: return
        val decision = when (gestureType) {
            FlickDetector.GestureType.FLICK_APPROVE -> "allow-once"
            FlickDetector.GestureType.SHAKE_DENY -> "deny"
        }
        bleService?.sendApprovalResponse(
            ApprovalResponse(reqId, decision, "gesture")
        )
        clearPendingApproval()
    }

    private fun clearPendingApproval() {
        pendingApprovalRequestId = null
        pendingApprovalRisk = null
        flickDetector?.highRiskLocked = false
    }

    private fun showContextMenu() {
        val items = arrayOf("Re-pair", "About")
        AlertDialog.Builder(this)
            .setItems(items) { _, which ->
                when (which) {
                    0 -> rePair()
                    1 -> showAbout()
                }
            }
            .show()
    }

    private fun rePair() {
        PairingStore.clear(this)
        BleService.stop(this)
        startActivity(Intent(this, PairingActivity::class.java))
        finish()
    }

    private fun showAbout() {
        val version = try {
            packageManager.getPackageInfo(packageName, 0).versionName
        } catch (_: Exception) { "unknown" }
        AlertDialog.Builder(this)
            .setTitle("Clawd")
            .setMessage("Version $version")
            .setPositiveButton("OK", null)
            .show()
    }

    // ── Demo mode ──

    private val demoStates = listOf(
        ClawdState.IDLE, ClawdState.THINKING, ClawdState.WORKING,
        ClawdState.JUGGLING, ClawdState.ATTENTION, ClawdState.SWEEPING,
        ClawdState.NOTIFICATION, ClawdState.ERROR, ClawdState.CARRYING,
        ClawdState.SLEEPING, ClawdState.YAWNING, ClawdState.DOZING,
        ClawdState.COLLAPSING, ClawdState.WAKING,
    )

    private fun setupDemoTapCycle() {
        petView.setOnClickListener {
            demoStateIndex = (demoStateIndex + 1) % demoStates.size
            val state = demoStates[demoStateIndex]
            petView.state = state
            updateStateChip(state)
        }
        updateStateChip(demoStates[0])
    }

    private fun handlePowerModeChange(mode: PowerManager.PowerMode) {
        when (mode) {
            PowerManager.PowerMode.SCREEN_OFF -> { petView.pause(); flickDetector?.stop() }
            PowerManager.PowerMode.LOW_BATTERY,
            PowerManager.PowerMode.NORMAL -> { petView.resume(); flickDetector?.start() }
        }
    }
}
