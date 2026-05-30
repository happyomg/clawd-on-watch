package com.clawd.watch.gesture

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import kotlin.math.abs

class FlickDetector(
    private val context: Context,
    private val onGesture: (GestureType) -> Unit
) : SensorEventListener {

    enum class GestureType { FLICK_APPROVE, SHAKE_DENY }

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator

    private var isActive = false
    var highRiskLocked = false

    // Flick detection (wrist flip — Z axis)
    private var prevZ = 0f
    private var lastFlickTime = 0L

    // Shake detection (lateral shake — X axis)
    private var shakeCount = 0
    private var lastShakeTime = 0L
    private var prevX = 0f
    private var shakeDirection = 0 // 1 = positive, -1 = negative

    // Sensitivity tuning
    private var flickThreshold = DEFAULT_FLICK_THRESHOLD
    private var flickDeltaThreshold = DEFAULT_DELTA_THRESHOLD
    private var successCount = 0
    private var falsePositiveCount = 0

    companion object {
        private const val DEFAULT_FLICK_THRESHOLD = 18f
        private const val DEFAULT_DELTA_THRESHOLD = 12f
        private const val COOLDOWN_MS = 2000L
        private const val SHAKE_THRESHOLD = 14f
        private const val SHAKE_DELTA_THRESHOLD = 10f
        private const val SHAKE_WINDOW_MS = 1500L
        private const val REQUIRED_SHAKES = 3
        private const val SENSITIVITY_ADJUST_INTERVAL = 20
        private const val MIN_THRESHOLD = 10f
        private const val MAX_THRESHOLD = 25f
    }

    fun start() {
        if (accelerometer != null && !isActive) {
            sensorManager.registerListener(this, accelerometer, SensorManager.SENSOR_DELAY_GAME)
            isActive = true
        }
    }

    fun stop() {
        if (isActive) {
            sensorManager.unregisterListener(this)
            isActive = false
        }
    }

    override fun onSensorChanged(event: SensorEvent) {
        val x = event.values[0]
        val z = event.values[2]
        val now = System.currentTimeMillis()

        detectFlick(z, now)
        detectShake(x, now)

        prevZ = z
        prevX = x
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    private fun detectFlick(z: Float, now: Long) {
        if (highRiskLocked) return

        val deltaZ = abs(z - prevZ)
        if (deltaZ > flickDeltaThreshold && abs(z) > flickThreshold) {
            if (now - lastFlickTime > COOLDOWN_MS) {
                lastFlickTime = now
                vibrateConfirm()
                onGesture(GestureType.FLICK_APPROVE)
                recordSuccess()
            }
        }
    }

    private fun detectShake(x: Float, now: Long) {
        val deltaX = abs(x - prevX)

        if (deltaX > SHAKE_DELTA_THRESHOLD && abs(x) > SHAKE_THRESHOLD) {
            val newDir = if (x > 0) 1 else -1
            if (newDir != shakeDirection) {
                shakeDirection = newDir

                if (now - lastShakeTime < SHAKE_WINDOW_MS) {
                    shakeCount++
                } else {
                    shakeCount = 1
                }
                lastShakeTime = now

                if (shakeCount >= REQUIRED_SHAKES) {
                    shakeCount = 0
                    vibrateDeny()
                    onGesture(GestureType.SHAKE_DENY)
                }
            }
        }

        if (now - lastShakeTime > SHAKE_WINDOW_MS) {
            shakeCount = 0
        }
    }

    private fun recordSuccess() {
        successCount++
        adjustSensitivity()
    }

    private fun adjustSensitivity() {
        val total = successCount + falsePositiveCount
        if (total < SENSITIVITY_ADJUST_INTERVAL) return

        val fpRate = falsePositiveCount.toFloat() / total
        if (fpRate > 0.3f) {
            flickThreshold = (flickThreshold + 1f).coerceAtMost(MAX_THRESHOLD)
            flickDeltaThreshold = (flickDeltaThreshold + 0.5f).coerceAtMost(MAX_THRESHOLD)
        } else if (fpRate < 0.1f && successCount > 10) {
            flickThreshold = (flickThreshold - 0.5f).coerceAtLeast(MIN_THRESHOLD)
            flickDeltaThreshold = (flickDeltaThreshold - 0.25f).coerceAtLeast(MIN_THRESHOLD * 0.6f)
        }

        successCount = 0
        falsePositiveCount = 0
    }

    private fun vibrateConfirm() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator.vibrate(VibrationEffect.createOneShot(50, VibrationEffect.DEFAULT_AMPLITUDE))
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(50)
        }
    }

    private fun vibrateDeny() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 80, 60, 80), -1))
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(longArrayOf(0, 80, 60, 80), -1)
        }
    }
}
