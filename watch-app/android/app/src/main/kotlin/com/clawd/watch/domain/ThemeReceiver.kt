package com.clawd.watch.domain

import android.util.Base64
import android.util.Log
import org.json.JSONObject
import java.io.File

/**
 * Reassembles a theme pushed over CWD5. The Desktop now sends pre-rendered
 * frame images (PNG) instead of raw SVGs, so the Watch never needs a WebView.
 *
 * Frame structure:
 *   manifest: {t:"manifest", name, hash, stateMap, files:["idle/000.png",...], frameMeta:{idle:{loopMs,count,fps},...}, totalBytes}
 *   chunk:    {t:"chunk", f:"idle/000.png", i, c, d:<base64>}
 *   done:     {t:"done", hash}
 *
 * On completion, writes:
 *   <themesRoot>/<hash>/manifest.json
 *   <themesRoot>/<hash>/frames/<stateName>/000.png, 001.png, ...
 *   <themesRoot>/<hash>/frames/<stateName>/meta.json  (from frameMeta)
 */
class ThemeReceiver(private val themesRoot: File) {

    companion object {
        private const val TAG = "ThemeReceiver"
    }

    private var name = ""
    private var hash = ""
    private var stateMap: JSONObject? = null
    private var frameMeta: JSONObject? = null
    private val chunks = HashMap<String, Array<String?>>()
    private var totalBytes = 0L
    private var receivedBytes = 0L

    @Synchronized
    fun reset() {
        name = ""; hash = ""; stateMap = null; frameMeta = null
        chunks.clear(); totalBytes = 0L; receivedBytes = 0L
    }

    @Synchronized
    fun progress(): Float =
        if (totalBytes > 0) (receivedBytes.toFloat() / totalBytes).coerceIn(0f, 1f) else 0f

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
        frameMeta = json.optJSONObject("frameMeta")
        totalBytes = json.optLong("totalBytes", 0)
        receivedBytes = 0L
        Log.i(TAG, "manifest: $name ($hash), $totalBytes bytes, frameMeta=${frameMeta != null}")
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
            receivedBytes += (d.length * 3L) / 4L
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
            val framesRoot = File(themeDir, "frames")

            for ((file, parts) in chunks) {
                if (parts.any { it == null }) {
                    Log.w(TAG, "missing chunk(s) for $file — aborting")
                    return null
                }
                val bytes = Base64.decode(parts.joinToString(""), Base64.DEFAULT)
                val outFile = File(framesRoot, sanitize(file))
                outFile.parentFile?.mkdirs()
                outFile.writeBytes(bytes)
            }

            // Write per-state meta.json from frameMeta
            if (frameMeta != null) {
                val metaKeys = frameMeta!!.keys()
                while (metaKeys.hasNext()) {
                    val stateName = metaKeys.next()
                    val meta = frameMeta!!.optJSONObject(stateName) ?: continue
                    val stateDir = File(framesRoot, stateName)
                    stateDir.mkdirs()
                    File(stateDir, "meta.json").writeText(meta.toString(), Charsets.UTF_8)
                }
            }

            // Write manifest.json
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
            Log.i(TAG, "theme assembled: ${loaded.name} (${loaded.hash}), ${loaded.files.size} states")
            loaded
        } catch (e: Exception) {
            Log.w(TAG, "assembly failed: ${e.message}")
            null
        } finally {
            chunks.clear()
        }
    }

    private fun sanitize(file: String): String {
        val parts = file.split("/").filter { it.isNotEmpty() && it != ".." && it != "." }
        return if (parts.isEmpty()) "unnamed" else parts.joinToString("/")
    }
}
