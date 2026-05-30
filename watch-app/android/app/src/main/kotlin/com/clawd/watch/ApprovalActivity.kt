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
import com.clawd.watch.service.BleService

class ApprovalActivity : AppCompatActivity() {

    private var bleService: BleService? = null
    private var bound = false
    private val handler = Handler(Looper.getMainLooper())
    private var responded = false

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            bleService = (binder as BleService.LocalBinder).getService()
            bound = true
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            bleService = null
            bound = false
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val requestId = intent.getStringExtra("requestId") ?: run { finish(); return }
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
            respond(requestId, "allow-once", "button")
        }
        findViewById<Button>(R.id.btn_always).setOnClickListener {
            respond(requestId, "allow-always", "button")
        }
        findViewById<Button>(R.id.btn_deny).setOnClickListener {
            respond(requestId, "deny", "button")
        }

        val gestureHint = findViewById<TextView>(R.id.gesture_hint)
        if (risk == "high") {
            gestureHint.text = "High risk: gesture disabled"
            gestureHint.setTextColor(0x88F44336.toInt())
        } else {
            gestureHint.text = "Flick wrist to allow / Shake to deny"
        }

        val expiresAt = intent.getLongExtra("expiresAt", 0L)
        if (expiresAt > 0) {
            val delay = expiresAt - System.currentTimeMillis()
            if (delay > 0) {
                handler.postDelayed({ onTimeout() }, delay)
            }
        }
    }

    private fun onTimeout() {
        if (responded) return
        responded = true
        val vibrator = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 100, 50, 100), -1))
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(longArrayOf(0, 100, 50, 100), -1)
        }
        Toast.makeText(this, "Request timed out", Toast.LENGTH_SHORT).show()
        handler.postDelayed({ finish() }, 1500L)
    }

    override fun onStart() {
        super.onStart()
        bindService(
            Intent(this, BleService::class.java),
            connection,
            Context.BIND_AUTO_CREATE
        )
    }

    override fun onStop() {
        if (bound) {
            unbindService(connection)
            bound = false
        }
        super.onStop()
    }

    private fun respond(requestId: String, decision: String, source: String) {
        if (responded) return
        responded = true
        handler.removeCallbacksAndMessages(null)
        bleService?.sendApprovalResponse(
            ApprovalResponse(requestId, decision, source)
        )
        finish()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
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
