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
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
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
    private var pendingAnswers: Map<String, String>? = null

    private val focusButtons = mutableListOf<Button>()
    private var focusIndex = 0
    private lateinit var vibrator: Vibrator

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            bleService = (binder as BleService.LocalBinder).getService()
            bound = true
            pendingDecision?.let { (decision, source) ->
                pendingDecision = null
                respond(decision, source, pendingAnswers)
                pendingAnswers = null
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

        val questionTexts = intent.getStringArrayExtra("questionTexts")
        if (questionTexts != null && questionTexts.isNotEmpty()) {
            setupElicitationUI(questionTexts)
        } else {
            setupApprovalUI(tool, command, risk)
        }

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

    private fun setupApprovalUI(tool: String, command: String, risk: String) {
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
        updateFocus(0)
        findViewById<TextView>(R.id.gesture_hint).text = "Crown: select · Back: deny"
    }

    private fun setupElicitationUI(questionTexts: Array<String>) {
        val optCounts = intent.getIntArrayExtra("questionOptCounts") ?: intArrayOf()
        val allOpts = intent.getStringArrayExtra("questionOpts") ?: arrayOf()

        val questionText = questionTexts[0]
        val optCount = if (optCounts.isNotEmpty()) optCounts[0] else 0
        val options = allOpts.take(optCount)

        findViewById<TextView>(R.id.risk_label).visibility = View.GONE
        findViewById<TextView>(R.id.tool_label).visibility = View.GONE
        findViewById<Button>(R.id.btn_allow).visibility = View.GONE
        findViewById<Button>(R.id.btn_always).visibility = View.GONE
        findViewById<Button>(R.id.btn_deny).visibility = View.GONE

        findViewById<TextView>(R.id.command_text).apply {
            text = questionText
            setBackgroundColor(0)
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 13f
        }

        val hintView = findViewById<TextView>(R.id.gesture_hint)
        val parentLayout = hintView.parent as LinearLayout
        val insertIndex = parentLayout.indexOfChild(hintView)

        for ((i, opt) in options.withIndex()) {
            val btn = Button(this).apply {
                text = opt
                textSize = 11f
                isFocusable = true
                isFocusableInTouchMode = true
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    (38 * resources.displayMetrics.density).toInt()
                ).apply { topMargin = (4 * resources.displayMetrics.density).toInt() }
                setOnClickListener {
                    respond("allow", "button", mapOf(questionText to opt))
                }
            }
            parentLayout.addView(btn, insertIndex + i)
            focusButtons.add(btn)
        }

        val skipBtn = Button(this).apply {
            text = "Skip"
            textSize = 10f
            alpha = 0.6f
            isFocusable = true
            isFocusableInTouchMode = true
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                (34 * resources.displayMetrics.density).toInt()
            ).apply { topMargin = (4 * resources.displayMetrics.density).toInt() }
            setOnClickListener { respond("deny", "button") }
        }
        parentLayout.addView(skipBtn, insertIndex + options.size)
        focusButtons.add(skipBtn)

        if (focusButtons.isNotEmpty()) updateFocus(0)
        hintView.text = "Crown: select · Back: skip"
    }

    override fun onStart() {
        super.onStart()
        bindService(Intent(this, BleService::class.java), connection, Context.BIND_AUTO_CREATE)
    }

    override fun onStop() {
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
        if (focusButtons.isEmpty()) return
        focusIndex = newIndex
        focusButtons.forEachIndexed { i, btn ->
            btn.alpha = if (i == focusIndex) 1.0f else 0.4f
            if (i == focusIndex) btn.requestFocus()
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator.vibrate(VibrationEffect.createOneShot(5, 40))
        }
    }

    private fun respond(decision: String, source: String, answers: Map<String, String>? = null) {
        val id = requestId ?: return
        if (responded) return
        if (bleService == null) {
            pendingDecision = Pair(decision, source)
            pendingAnswers = answers
            return
        }
        responded = true
        handler.removeCallbacksAndMessages(null)
        bleService?.sendApprovalResponse(ApprovalResponse(id, decision, source, answers))
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
