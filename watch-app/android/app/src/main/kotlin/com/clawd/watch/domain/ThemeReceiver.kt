package com.clawd.watch.domain

import android.util.Base64
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Reassembles a theme pushed over CWD5 into a self-contained theme directory:
 *
 *   <themesRoot>/<hash>/manifest.json
 *   <themesRoot>/<hash>/svg/<file>.svg ...
 *
 * Frames arrive in order (manifest → chunks → done). Chunks are buffered per
 * file by index and written once `done` is seen. [onDone] is invoked with the
 * loaded [ThemeManifest] on success, or null on failure (caller keeps the
 * current theme). All state lives in this instance, so a fresh receiver should
 * be used per transfer; [reset] clears it for reuse.
 */
class ThemeReceiver(private val themesRoot: File) {

    companion object {
        private const val TAG = "ThemeReceiver"
    }

    private var name = ""
    private var hash = ""
    private var stateMap: JSONObject? = null
    private val chunks = HashMap<String, Array<String?>>()
    private var totalBytes = 0L
    private var receivedBytes = 0L

    @Synchronized
    fun reset() {
        name = ""
        hash = ""
        stateMap = null
        chunks.clear()
        totalBytes = 0L
        receivedBytes = 0L
    }

    /** Transfer progress in [0,1], based on bytes received vs. manifest total. */
    @Synchronized
    fun progress(): Float =
        if (totalBytes > 0) (receivedBytes.toFloat() / totalBytes).coerceIn(0f, 1f) else 0f

    /**
     * Feed one CWD5 frame. Returns a loaded ThemeManifest when the transfer
     * completes (the "done" frame), otherwise null.
     */
    @Synchronized
    fun onFrame(json: JSONObject): ThemeManifest? {
        return when (json.optString("t")) {
            "manifest" -> { handleManifest(json); null }
            "chunk" -> { handleChunk(json); null }
            "done" -> handleDone(json)
            else -> null
        }
    }

    private fun handleManifest(json: JSONObject) {
        chunks.clear()
        name = json.optString("name", "")
        hash = json.optString("hash", "")
        stateMap = json.optJSONObject("stateMap")
        totalBytes = json.optLong("totalBytes", 0)
        receivedBytes = 0L
        Log.i(TAG, "manifest: $name ($hash), $totalBytes bytes")
    }

    private fun handleChunk(json: JSONObject) {
        val file = json.optString("f", "")
        if (file.isEmpty()) return
        val count = json.optInt("c", 0)
        val index = json.optInt("i", -1)
        if (count <= 0 || index < 0 || index >= count) return
        val arr = chunks.getOrPut(file) { arrayOfNulls(count) }
        if (arr.size == count && arr[index] == null) {
            val d = json.optString("d", "")
            arr[index] = d
            receivedBytes += (d.length * 3L) / 4L // base64 → bytes estimate
        }
    }

    private fun handleDone(json: JSONObject): ThemeManifest? {
        val doneHash = json.optString("hash", hash)
        if (doneHash.isEmpty() || stateMap == null) {
            Log.w(TAG, "done with incomplete manifest")
            return null
        }
        return try {
            val themeDir = File(themesRoot, doneHash)
            val svgDir = File(themeDir, "svg").apply { mkdirs() }

            for ((file, parts) in chunks) {
                if (parts.any { it == null }) {
                    Log.w(TAG, "missing chunk(s) for $file — aborting")
                    return null
                }
                val bytes = Base64.decode(parts.joinToString(""), Base64.DEFAULT)
                File(svgDir, sanitize(file)).writeBytes(bytes)
            }

            // Persist manifest.json so the theme survives restarts and can be
            // re-loaded by ThemeConfig.loadFromDir.
            val manifestOut = JSONObject()
                .put("name", name)
                .put("hash", doneHash)
                .put("stateMap", stateMap)
            File(themeDir, "manifest.json").writeText(manifestOut.toString(), Charsets.UTF_8)

            val loaded = ThemeConfig.loadFromDir(themeDir)
            if (loaded == null) {
                Log.w(TAG, "loadFromDir failed after write")
                return null
            }
            // Integrity check: recomputed fingerprint should match the sender's.
            if (loaded.hash != doneHash) {
                Log.w(TAG, "fingerprint mismatch: got ${loaded.hash}, expected $doneHash")
            }
            Log.i(TAG, "theme assembled: ${loaded.name} (${loaded.hash}), ${loaded.files.size} files")
            loaded
        } catch (e: Exception) {
            Log.w(TAG, "assembly failed: ${e.message}")
            null
        } finally {
            chunks.clear()
        }
    }

    /** Defensive: never let a filename escape the svg dir. */
    private fun sanitize(file: String): String =
        File(file).name.ifEmpty { "unnamed.svg" }
}
