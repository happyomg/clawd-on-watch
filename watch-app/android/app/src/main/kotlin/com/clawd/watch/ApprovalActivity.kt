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
import android.view.InputDevice
import android.view.MotionEvent
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
    private var requestId: String? = null
    private var pendingDecision: Pair<String, String>? = null

    private val focusButtons = mutableListOf<Button>()
    private val focusDecisions = mutableListOf<String>()
    private var focusIndex = 0
    private lateinit var vibrator: Vibrator

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

        vibrator = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
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

        val btnAllow = findViewById<Button>(R.id.btn_allow)
        val btnAlways = findViewById<Button>(R.id.btn_always)
        val btnDeny = findViewById<Button>(R.id.btn_deny)

        btnAllow.setOnClickListener { respond("allow-once", "button") }
        btnAlways.setOnClickListener { respond("allow-always", "button") }
        btnDeny.setOnClickListener { respond("deny", "button") }

        focusButtons.addAll(listOf(btnAllow, btnAlways, btnDeny))
        focusDecisions.addAll(listOf("allow-once", "allow-always", "deny"))
        updateFocus(0)

        val gestureHint = findViewById<TextView>(R.id.gesture_hint)
        gestureHint.text = "Crown: select · Back: deny"

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
    }

    override fun onStop() {
        if (!responded) {
            handler.removeCallbacksAndMessages(null)
            respond("deny", "screen-off")
        }
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

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        respond("deny", "back")
    }

    override fun onGenericMotionEvent(event: MotionEvent): Boolean {
        if (event.action == MotionEvent.ACTION_SCROLL &&
            event.isFromSource(InputDevice.SOURCE_ROTARY_ENCODER)) {
            val delta = event.getAxisValue(MotionEvent.AXIS_SCROLL)
            val direction = if (delta < 0) 1 else -1
            updateFocus((focusIndex + direction + focusButtons.size) % focusButtons.size)
            return true
        }
        return super.onGenericMotionEvent(event)
    }

    private fun updateFocus(newIndex: Int) {
        focusIndex = newIndex
        focusButtons.forEachIndexed { i, btn ->
            btn.alpha = if (i == focusIndex) 1.0f else 0.4f
            if (i == focusIndex) btn.requestFocus()
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator.vibrate(VibrationEffect.createOneShot(5, 40))
        }
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
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            when (risk) {
                "high" -> vibrator.vibrate(VibrationEffect.createWaveform(
                    longArrayOf(0, 120, 60, 120, 60, 120),
                    intArrayOf(0, 255, 0, 200, 0, 160), -1))
                "medium" -> vibrator.vibrate(VibrationEffect.createWaveform(
                    longArrayOf(0, 80, 50, 80),
                    intArrayOf(0, 200, 0, 160), -1))
                else -> vibrator.vibrate(VibrationEffect.createOneShot(60, 120))
            }
        } else {
            @Suppress("DEPRECATION")
            val pattern = when (risk) {
                "high" -> longArrayOf(0, 120, 60, 120, 60, 120)
                "medium" -> longArrayOf(0, 80, 50, 80)
                else -> longArrayOf(0, 60)
            }
            vibrator.vibrate(pattern, -1)
        }
    }
}
