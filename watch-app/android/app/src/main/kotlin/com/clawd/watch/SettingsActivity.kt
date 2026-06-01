package com.clawd.watch

import android.os.Bundle
import android.widget.Button
import android.widget.CheckBox
import androidx.appcompat.app.AppCompatActivity
import com.clawd.watch.data.SettingsStore

class SettingsActivity : AppCompatActivity() {

    private lateinit var vibStrong: Button
    private lateinit var vibNormal: Button
    private lateinit var vibOff: Button
    private lateinit var sensHigh: Button
    private lateinit var sensStandard: Button
    private lateinit var sensLow: Button
    private lateinit var keepScreenOn: CheckBox

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        vibStrong = findViewById(R.id.vib_strong)
        vibNormal = findViewById(R.id.vib_normal)
        vibOff = findViewById(R.id.vib_off)
        sensHigh = findViewById(R.id.sens_high)
        sensStandard = findViewById(R.id.sens_standard)
        sensLow = findViewById(R.id.sens_low)
        keepScreenOn = findViewById(R.id.keep_screen_on)

        loadSettings()

        vibStrong.setOnClickListener { setVibration("strong") }
        vibNormal.setOnClickListener { setVibration("normal") }
        vibOff.setOnClickListener { setVibration("off") }

        sensHigh.setOnClickListener { setSensitivity("high") }
        sensStandard.setOnClickListener { setSensitivity("standard") }
        sensLow.setOnClickListener { setSensitivity("low") }

        keepScreenOn.setOnCheckedChangeListener { _, checked ->
            SettingsStore.setKeepScreenOn(this, checked)
        }
    }

    private fun loadSettings() {
        highlightVibration(SettingsStore.getVibrationStrength(this))
        highlightSensitivity(SettingsStore.getGestureSensitivity(this))
        keepScreenOn.isChecked = SettingsStore.getKeepScreenOn(this)
    }

    private fun setVibration(value: String) {
        SettingsStore.setVibrationStrength(this, value)
        highlightVibration(value)
    }

    private fun setSensitivity(value: String) {
        SettingsStore.setGestureSensitivity(this, value)
        highlightSensitivity(value)
    }

    private fun highlightVibration(active: String) {
        vibStrong.alpha = if (active == "strong") 1.0f else 0.4f
        vibNormal.alpha = if (active == "normal") 1.0f else 0.4f
        vibOff.alpha = if (active == "off") 1.0f else 0.4f
    }

    private fun highlightSensitivity(active: String) {
        sensHigh.alpha = if (active == "high") 1.0f else 0.4f
        sensStandard.alpha = if (active == "standard") 1.0f else 0.4f
        sensLow.alpha = if (active == "low") 1.0f else 0.4f
    }
}
