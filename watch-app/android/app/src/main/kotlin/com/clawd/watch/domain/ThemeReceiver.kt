package com.clawd.watch.domain

import android.util.Base64
import android.util.Log
import org.json.JSONObject
import java.io.File

/**
 * Reassembles a theme pushed over CWD5 into svg files + manifest:
 *   <themesRoot>/<hash>/manifest.json
 *   <themesRoot>/<hash>/svg/<file>.svg
 *
 * After assembly, the watch records frames locally using FrameRecorder.
 */
class ThemeReceiver(private val themesRoot: File) {

    companion object { private const val TAG = "ThemeReceiver" }

    private var name = ""; private var hash = ""; private var stateMap: JSONObject? = null
    private val chunks = HashMap<String, Array<String?>>()
    private var totalBytes = 0L; private var receivedBytes = 0L

    @Synchronized fun reset() {
        name = ""; hash = ""; stateMap = null; chunks.clear(); totalBytes = 0; receivedBytes = 0
    }

    @Synchronized fun progress(): Float =
        if (totalBytes > 0) (receivedBytes.toFloat() / totalBytes).coerceIn(0f, 1f) else 0f

    @Synchronized fun onFrame(json: JSONObject): ThemeManifest? = when (json.optString("t")) {
        "manifest" -> { handleManifest(json); null }
        "chunk" -> { handleChunk(json); null }
        "done" -> handleDone(json)
        else -> null
    }

    private fun handleManifest(json: JSONObject) {
        chunks.clear(); name = json.optString("name", ""); hash = json.optString("hash", "")
        stateMap = json.optJSONObject("stateMap")
        totalBytes = json.optLong("totalBytes", 0); receivedBytes = 0
        Log.i(TAG, "manifest: $name ($hash), $totalBytes bytes")
    }

    private fun handleChunk(json: JSONObject) {
        val file = json.optString("f", ""); if (file.isEmpty()) return
        val count = json.optInt("c", 0); val index = json.optInt("i", -1)
        if (count <= 0 || index < 0 || index >= count) return
        val arr = chunks.getOrPut(file) { arrayOfNulls(count) }
        if (arr.size == count && arr[index] == null) {
            val d = json.optString("d", ""); arr[index] = d
            receivedBytes += (d.length * 3L) / 4L
        }
    }

    private fun handleDone(json: JSONObject): ThemeManifest? {
        val doneHash = json.optString("hash", hash)
        if (doneHash.isEmpty() || stateMap == null) { Log.w(TAG, "incomplete"); return null }
        return try {
            val themeDir = File(themesRoot, doneHash)
            val svgDir = File(themeDir, "svg").apply { mkdirs() }
            for ((file, parts) in chunks) {
                if (parts.any { it == null }) { Log.w(TAG, "missing chunks for $file"); return null }
                File(svgDir, File(file).name.ifEmpty { "unnamed.svg" })
                    .writeBytes(Base64.decode(parts.joinToString(""), Base64.DEFAULT))
            }
            File(themeDir, "manifest.json").writeText(
                JSONObject().put("name", name).put("hash", doneHash).put("stateMap", stateMap).toString(), Charsets.UTF_8
            )
            ThemeConfig.loadFromDir(themeDir).also {
                if (it != null) Log.i(TAG, "assembled: ${it.name} (${it.hash}), ${it.files.size} files")
                else Log.w(TAG, "loadFromDir failed")
            }
        } catch (e: Exception) { Log.w(TAG, "assembly failed: ${e.message}"); null }
        finally { chunks.clear() }
    }
}
