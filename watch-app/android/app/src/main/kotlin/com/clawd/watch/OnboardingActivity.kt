package com.clawd.watch

import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.TextView
import android.widget.ViewFlipper
import androidx.appcompat.app.AppCompatActivity
import com.clawd.watch.data.SettingsStore

class OnboardingActivity : AppCompatActivity() {

    private lateinit var flipper: ViewFlipper
    private lateinit var btnNext: Button
    private lateinit var dots: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_onboarding)

        flipper = findViewById(R.id.flipper)
        btnNext = findViewById(R.id.btn_next)
        dots = findViewById(R.id.dots)

        updateDots()
        btnNext.setOnClickListener { advance() }

        flipper.setOnClickListener { advance() }
    }

    private fun advance() {
        if (flipper.displayedChild < flipper.childCount - 1) {
            flipper.showNext()
            updateDots()
        } else {
            SettingsStore.setOnboardingComplete(this)
            finish()
        }
    }

    private fun updateDots() {
        val current = flipper.displayedChild
        val total = flipper.childCount
        val sb = StringBuilder()
        for (i in 0 until total) {
            sb.append(if (i == current) "●" else "○")
            if (i < total - 1) sb.append(" ")
        }
        dots.text = sb.toString()
        btnNext.text = if (current == total - 1) "Done" else "Next"
    }
}
