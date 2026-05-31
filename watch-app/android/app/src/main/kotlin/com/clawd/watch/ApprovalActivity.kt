package com.clawd.watch

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.clawd.watch.data.ApprovalResponse
import com.clawd.watch.gesture.FlickDetector
import com.clawd.watch.service.BleService

class ApprovalActivity : AppCompatActivity() {

    private var bleService: BleService? = null
    private var bound = false
    private val handler = Handler(Looper.getMainLooper())
    private var responded = false
    private var flickDetector: FlickDetector? = null
    private var requestId: String? = null
    private var pendingDecision: Pair<String, String>? = null

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            bleService = (binder as BleService.LocalBinder).getService()
            bound = true
            pendingDecision?.let { (decision, source) ->
                pendingDecision = null
                respond(decision, source)
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            bleService = null
            bound = false
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        requestId = intent.getStringExtra("requestId") ?: run { finish(); return }
        val command = intent.getStringExtra("command") ?: "Unknown command"
        val tool = intent.getStringExtra("tool") ?: ""
        val risk = intent.getStringExtra("risk") ?: "medium"

        vibrate(risk)
        setContentView(R.layout.activity_approval)

        val riskColor = when (risk) {
            "high" -> 0xFFF44336.toInt()
            "medium" -> 0xFFFF9800.toInt()
            else -> 0xFF4CAF50.toInt()
        }

        findViewById<TextView>(R.id.risk_label).apply {
            text = "RISK: ${risk.uppercase()}"
            setTextColor(riskColor)
        }
        findViewById<TextView>(R.id.tool_label).text = "Tool: $tool"
        findViewById<TextView>(R.id.command_text).text = command

        findViewById<Button>(R.id.btn_allow).setOnClickListener {
            respond("allow-once", "button")
        }
        findViewById<Button>(R.id.btn_always).setOnClickListener {
            respond("allow-always", "button")
        }
        findViewById<Button>(R.id.btn_deny).setOnClickListener {
            respond("deny", "button")
        }

        val gestureHint = findViewById<TextView>(R.id.gesture_hint)
        val isHighRisk = risk == "high"
        if (isHighRisk) {
            gestureHint.text = "High risk: gesture disabled"
            gestureHint.setTextColor(0x88F44336.toInt())
        } else {
            gestureHint.text = "Flick wrist to allow / Shake to deny"
        }

        flickDetector = FlickDetector(this) { gestureType ->
            runOnUiThread {
                val decision = when (gestureType) {
                    FlickDetector.GestureType.FLICK_APPROVE -> "allow-once"
                    FlickDetector.GestureType.SHAKE_DENY -> "deny"
                }
                respond(decision, "gesture")
            }
        }
        flickDetector?.highRiskLocked = isHighRisk

        val timeoutMs = intent.getLongExtra("timeoutMs", 0L)
        val expiresAt = intent.getLongExtra("expiresAt", 0L)
        val delay = when {
            timeoutMs > 0 -> timeoutMs
            expiresAt > 0 -> maxOf(0L, expiresAt - System.currentTimeMillis())
            else -> 0L
        }
        if (expiresAt > 0 && delay == 0L) {
            handler.post { onTimeout() }
        } else if (delay > 0) {
            handler.postDelayed({ onTimeout() }, delay)
        }
    }

    override fun onStart() {
        super.onStart()
        bindService(Intent(this, BleService::class.java), connection, Context.BIND_AUTO_CREATE)
        flickDetector?.start()
    }

    override fun onStop() {
        flickDetector?.stop()
        if (bound) {
            unbindService(connection)
            bound = false
        }
        super.onStop()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    private fun respond(decision: String, source: String) {
        val id = requestId ?: return
        if (responded) return
        if (bleService == null) {
            pendingDecision = Pair(decision, source)
            return
        }
        responded = true
        handler.removeCallbacksAndMessages(null)
        bleService?.sendApprovalResponse(ApprovalResponse(id, decision, source))
        finish()
    }

    private fun onTimeout() {
        val id = requestId ?: return
        if (responded) return
        responded = true
        bleService?.sendApprovalResponse(ApprovalResponse(id, "deny", "timeout"))
        val vibrator = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 100, 50, 100), -1))
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(longArrayOf(0, 100, 50, 100), -1)
        }
        Toast.makeText(this, "Request timed out (denied)", Toast.LENGTH_SHORT).show()
        handler.postDelayed({ finish() }, 1500L)
    }

    private fun vibrate(risk: String) {
        val vibrator = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        val pattern = when (risk) {
            "high" -> longArrayOf(0, 300, 100, 300, 100, 300)
            "medium" -> longArrayOf(0, 200, 100, 200)
            else -> longArrayOf(0, 100)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator.vibrate(VibrationEffect.createWaveform(pattern, -1))
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(pattern, -1)
        }
    }
}
