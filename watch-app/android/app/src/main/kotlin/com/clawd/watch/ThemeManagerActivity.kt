package com.clawd.watch

import android.app.AlertDialog
import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.clawd.watch.domain.ThemeCache
import com.clawd.watch.domain.ThemeConfig
import com.clawd.watch.renderer.PetView
import com.clawd.watch.service.BleService
import java.io.File

class ThemeManagerActivity : AppCompatActivity() {

    private lateinit var container: LinearLayout
    private lateinit var themesRoot: File

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_theme_manager)
        container = findViewById(R.id.theme_list)
        themesRoot = File(filesDir, "themes")
        loadThemes()
    }

    private fun loadThemes() {
        container.removeAllViews()
        val hashes = ThemeCache.listCachedHashes(themesRoot)
        if (hashes.isEmpty()) {
            addEmptyLabel()
            return
        }
        val activeHash = getSharedPreferences("clawd_state", Context.MODE_PRIVATE)
            .getString("active_theme_hash", null)
        val sorted = hashes.sortedByDescending { File(themesRoot, it).lastModified() }
        for (hash in sorted) {
            addThemeCard(hash, hash == activeHash)
        }
    }

    private fun addEmptyLabel() {
        val tv = TextView(this).apply {
            text = "No cached themes"
            setTextColor(0x99FFFFFF.toInt())
            textSize = 12f
            setPadding(0, 24, 0, 0)
        }
        container.addView(tv)
    }

    private fun addThemeCard(hash: String, isActive: Boolean) {
        val themeDir = File(themesRoot, hash)
        val manifest = ThemeConfig.loadFromDir(themeDir)
        val name = manifest?.name?.replaceFirstChar { it.uppercase() } ?: hash.take(6)
        val totalStates = manifest?.files?.size ?: 0
        val recordedStates = ThemeCache.recordedStateCount(themeDir)
        val sizeBytes = ThemeCache.cacheSize(themeDir)
        val sizeLabel = formatSize(sizeBytes)
        val isBundled = manifest?.isBundled == true
        val readyLabel = if (totalStates > 0 && recordedStates >= totalStates) "ready" else "$recordedStates/$totalStates recorded"

        val card = LayoutInflater.from(this).inflate(R.layout.item_theme, container, false)
        val nameText = card.findViewById<TextView>(R.id.theme_name)
        val infoText = card.findViewById<TextView>(R.id.theme_info)
        val statusBadge = card.findViewById<TextView>(R.id.theme_status)
        val btnPrimary = card.findViewById<Button>(R.id.btn_primary)
        val btnSecondary = card.findViewById<Button>(R.id.btn_secondary)

        nameText.text = name
        infoText.text = "$totalStates states · $readyLabel · $sizeLabel"

        if (isActive) {
            statusBadge.text = "● Active"
            statusBadge.setTextColor(0xFF4CAF50.toInt())
            btnPrimary.text = "Re-record"
            btnPrimary.setOnClickListener { confirmReRecord(hash, name) }
            btnSecondary.text = "Clear cache"
            btnSecondary.setOnClickListener { confirmClearCache(hash, name, isActive = true) }
        } else {
            statusBadge.text = if (isBundled) "Built-in" else ""
            statusBadge.setTextColor(0x99FFFFFF.toInt())
            btnPrimary.text = "Switch"
            btnPrimary.setOnClickListener { switchTheme(hash) }
            if (!isBundled) {
                btnSecondary.text = "Delete"
                btnSecondary.setOnClickListener { confirmDelete(hash, name) }
            } else {
                btnSecondary.text = "Clear cache"
                btnSecondary.setOnClickListener { confirmClearCache(hash, name, isActive = false) }
            }
        }

        container.addView(card)
    }

    private fun switchTheme(hash: String) {
        val dir = File(themesRoot, hash)
        val manifest = ThemeConfig.loadFromDir(dir) ?: return
        ThemeConfig.setActive(manifest)
        ThemeCache.touch(themesRoot, hash)
        getSharedPreferences("clawd_state", Context.MODE_PRIVATE).edit()
            .putString("active_theme_hash", hash)
            .apply()
        loadThemes()
    }

    private fun confirmReRecord(hash: String, name: String) {
        AlertDialog.Builder(this)
            .setMessage("Re-record all frames for $name?")
            .setPositiveButton("Re-record") { _, _ ->
                ThemeCache.clearFrames(File(themesRoot, hash))
                loadThemes()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun confirmClearCache(hash: String, name: String, isActive: Boolean) {
        AlertDialog.Builder(this)
            .setMessage("Clear cached frames for $name?${if (isActive) "\nFrames will be re-recorded." else ""}")
            .setPositiveButton("Clear") { _, _ ->
                ThemeCache.clearFrames(File(themesRoot, hash))
                loadThemes()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun confirmDelete(hash: String, name: String) {
        AlertDialog.Builder(this)
            .setMessage("Delete $name? This cannot be undone.")
            .setPositiveButton("Delete") { _, _ ->
                ThemeCache.deleteTheme(File(themesRoot, hash))
                loadThemes()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun formatSize(bytes: Long): String = when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "${bytes / 1024} KB"
        else -> String.format("%.1f MB", bytes / (1024.0 * 1024.0))
    }
}
