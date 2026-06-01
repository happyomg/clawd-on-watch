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
    var onBatteryChanged: ((Int) -> Unit)? = null

    private var isScreenOn = true
    var batteryPct = 100
        private set

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
                val prev = batteryPct
                batteryPct = (level * 100) / scale
                if (batteryPct != prev) onBatteryChanged?.invoke(batteryPct)
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
