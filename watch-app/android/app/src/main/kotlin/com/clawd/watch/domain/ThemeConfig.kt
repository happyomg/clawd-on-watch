package com.clawd.watch.domain

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/**
 * A loaded pet theme: a name plus a state → [svg files] map. The watch resolves
 * an abstract state ("working") and an active-session count into a concrete SVG
 * filename locally, so the desktop never has to send a filename.
 *
 * Multi-file state arrays act as intensity tiers ordered ascending: index
 * clamp(count-1) picks the file. For Clawd, working = [typing, groove, building]
 * so 1 session → typing, 2 → groove, 3+ → building (matching the desktop).
 *
 * `hash` is the theme fingerprint. It MUST equal the desktop's
 * computeThemeFingerprint (src/watch-theme-fingerprint.js):
 *   sha256(name + ":" + sortedUniqueFiles.join(","))[0..5]
 * The bundled Clawd theme's fingerprint is "79c952".
 */
data class ThemeManifest(
    val name: String,
    val stateMap: Map<String, List<String>>,
    /** true → SVG sources live in APK assets/svg/; false → in [svgDir]. */
    val isBundled: Boolean,
    /** Source directory for synced themes (filesDir/themes/<hash>/svg). */
    val svgDir: File? = null
) {
    val hash: String by lazy { ThemeConfig.computeFingerprint(name, stateMap) }

    /** All unique SVG filenames this theme can render. */
    val files: List<String> by lazy { stateMap.values.flatten().distinct() }

    fun resolve(state: ClawdState, activeSessionCount: Int): String {
        val key = state.name.lowercase()
        val files = stateMap[key] ?: stateMap["idle"] ?: return FALLBACK_IDLE
        if (files.isEmpty()) return FALLBACK_IDLE
        val idx = (maxOf(activeSessionCount, 1) - 1).coerceIn(0, files.size - 1)
        return files[idx]
    }

    companion object {
        const val FALLBACK_IDLE = "clawd-idle-follow.svg"
    }
}

object ThemeConfig {

    private const val TAG = "ThemeConfig"

    /**
     * Canonical Clawd state map — must match Desktop's themes/clawd/theme.json
     * exactly so the fingerprints are identical and no false-positive sync is
     * triggered. Each state maps to exactly the files listed in theme.json.
     */
    private val CLAWD_STATE_MAP: Map<String, List<String>> = linkedMapOf(
        "idle" to listOf("clawd-idle-follow.svg"),
        "yawning" to listOf("clawd-idle-yawn.svg"),
        "dozing" to listOf("clawd-idle-doze.svg"),
        "collapsing" to listOf("clawd-collapse-sleep.svg"),
        "thinking" to listOf("clawd-working-thinking.svg"),
        "working" to listOf("clawd-working-typing.svg"),
        "juggling" to listOf("clawd-headphones-groove.svg"),
        "sweeping" to listOf("clawd-working-sweeping.svg"),
        "error" to listOf("clawd-error.svg"),
        "attention" to listOf("clawd-happy.svg"),
        "notification" to listOf("clawd-notification.svg"),
        "carrying" to listOf("clawd-working-carrying.svg"),
        "sleeping" to listOf("clawd-sleeping.svg"),
        "waking" to listOf("clawd-wake.svg")
    )

    val bundledClawd: ThemeManifest = ThemeManifest("clawd", CLAWD_STATE_MAP, isBundled = true)

    @Volatile
    var active: ThemeManifest = bundledClawd
        private set

    fun setActive(manifest: ThemeManifest) {
        active = manifest
        Log.i(TAG, "active theme = ${manifest.name} (${manifest.hash}), ${manifest.files.size} files")
    }

    /** Convenience: resolve against the active theme. */
    fun resolveSvg(state: ClawdState, activeSessionCount: Int = 1): String =
        active.resolve(state, activeSessionCount)

    /**
     * Theme fingerprint — keep byte-identical to the desktop JS implementation.
     */
    fun computeFingerprint(name: String, stateMap: Map<String, List<String>>): String {
        val files = stateMap.values.flatten().toSortedSet().toList()
        val input = name.trim() + ":" + files.joinToString(",")
        val digest = MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8))
        // Mask each byte to 0..255 — a bare Byte would sign-extend in %x.
        return buildString(6) { for (i in 0 until 3) append("%02x".format(digest[i].toInt() and 0xFF)) }
    }

    /**
     * Load a synced theme from filesDir/themes/<hash>/manifest.json. Returns null
     * if missing or malformed (caller keeps the current theme).
     */
    fun loadFromDir(themeDir: File): ThemeManifest? {
        return try {
            val manifestFile = File(themeDir, "manifest.json")
            if (!manifestFile.isFile) return null
            val json = JSONObject(manifestFile.readText(Charsets.UTF_8))
            val name = json.optString("name", "")
            if (name.isEmpty()) return null
            val stateMap = parseStateMap(json.optJSONObject("stateMap") ?: return null)
            if (stateMap.isEmpty()) return null
            ThemeManifest(name, stateMap, isBundled = false, svgDir = File(themeDir, "svg"))
        } catch (e: Exception) {
            Log.w(TAG, "loadFromDir failed: ${e.message}")
            null
        }
    }

    private fun parseStateMap(obj: JSONObject): Map<String, List<String>> {
        val map = linkedMapOf<String, List<String>>()
        val keys = obj.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            val arr = obj.optJSONArray(k) ?: continue
            val files = ArrayList<String>(arr.length())
            for (i in 0 until arr.length()) {
                val f = arr.optString(i, "")
                if (f.isNotEmpty()) files.add(f)
            }
            if (files.isNotEmpty()) map[k.lowercase()] = files
        }
        return map
    }
}
