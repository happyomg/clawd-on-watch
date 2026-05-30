package com.clawd.watch.power

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager

class PowerManager(private val context: Context) {

    enum class PowerMode { NORMAL, LOW_BATTERY, SCREEN_OFF }

    var currentMode = PowerMode.NORMAL
        private set

    var onModeChanged: ((PowerMode) -> Unit)? = null

    private var isScreenOn = true
    private var batteryPct = 100

    val animIntervalMs: Long
        get() = when (currentMode) {
            PowerMode.NORMAL -> 100L       // 10 FPS
            PowerMode.LOW_BATTERY -> 200L  // 5 FPS
            PowerMode.SCREEN_OFF -> 0L     // paused
        }

    val heartbeatIntervalMs: Long
        get() = when (currentMode) {
            PowerMode.NORMAL -> 30_000L
            PowerMode.LOW_BATTERY -> 60_000L
            PowerMode.SCREEN_OFF -> 120_000L
        }

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_SCREEN_OFF -> {
                    isScreenOn = false
                    updateMode()
                }
                Intent.ACTION_SCREEN_ON -> {
                    isScreenOn = true
                    updateMode()
                }
            }
        }
    }

    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
            if (level >= 0 && scale > 0) {
                batteryPct = (level * 100) / scale
                updateMode()
            }
        }
    }

    fun start() {
        val screenFilter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
        }
        context.registerReceiver(screenReceiver, screenFilter)

        val batteryFilter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        context.registerReceiver(batteryReceiver, batteryFilter)

        readInitialBattery()
    }

    fun stop() {
        try {
            context.unregisterReceiver(screenReceiver)
            context.unregisterReceiver(batteryReceiver)
        } catch (_: IllegalArgumentException) {}
    }

    private fun readInitialBattery() {
        val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        if (intent != null) {
            val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
            if (level >= 0 && scale > 0) {
                batteryPct = (level * 100) / scale
            }
        }
    }

    private fun updateMode() {
        val newMode = when {
            !isScreenOn -> PowerMode.SCREEN_OFF
            batteryPct <= 15 -> PowerMode.LOW_BATTERY
            else -> PowerMode.NORMAL
        }
        if (newMode != currentMode) {
            currentMode = newMode
            onModeChanged?.invoke(newMode)
        }
    }
}
