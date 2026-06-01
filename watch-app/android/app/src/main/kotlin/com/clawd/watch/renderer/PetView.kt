package com.clawd.watch.renderer

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.util.AttributeSet
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import com.clawd.watch.domain.ClawdState
import com.clawd.watch.domain.ThemeConfig
import org.json.JSONObject
import java.io.File

/**
 * Pet renderer. Plays pre-recorded frame sequences via [ImageView]. When frames
 * are missing, records them locally using [FrameRecorder] (invisible WebView,
 * alpha=0 — no UI impact). Desktop pushes SVGs; recording is done on device.
 */
class PetView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    companion object {
        private const val TAG = "PetView"
        private const val FPS = 20
        private const val FALLBACK_SIZE_PX = 192
    }

    private val imageView = ImageView(context).apply {
        scaleType = ImageView.ScaleType.FIT_CENTER
        visibility = GONE
    }
    private val fallbackLabel = TextView(context).apply {
        setTextColor(Color.WHITE); textSize = 14f; gravity = Gravity.CENTER; text = "…"
    }

    private val player = FramePlayer()
    private var currentSvg = ""
    private var generation = 0
    private var recorder: FrameRecorder? = null
    private var preRecording = false

    var onRecordingChanged: ((Boolean) -> Unit)? = null
    var onRecordProgress: ((String, Int, Int) -> Unit)? = null

    var state: ClawdState = ClawdState.IDLE
        set(value) { if (field != value) { field = value; refresh() } }

    var activeSessionCount: Int = 1
        set(value) { if (field != value) { field = value; refresh() } }

    init {
        addView(fallbackLabel, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(imageView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
    }

    private fun refresh() = showSvg(ThemeConfig.resolveSvg(state, activeSessionCount))
    fun setSvgImmediate(filename: String) = showSvg(filename)
    fun reloadForThemeChange() { currentSvg = ""; refresh() }

    private fun showSvg(svg: String) {
        if (svg == currentSvg || preRecording) return
        currentSvg = svg
        val gen = ++generation
        val theme = ThemeConfig.active
        val framesDir = frameCacheDir(theme.hash, svg)
        if (isComplete(framesDir)) { playFrames(framesDir); return }
        recordSingle(theme, svg, framesDir, gen)
    }

    /**
     * Pre-record ALL SVGs in the active theme. Called after a theme sync.
     * The WebView is invisible (alpha=0) — user sees whatever overlay the
     * caller provides.
     */
    fun preRecordAll(onComplete: (() -> Unit)? = null) {
        val theme = ThemeConfig.active
        val toRecord = theme.files.filter { !isComplete(frameCacheDir(theme.hash, it)) }
        if (toRecord.isEmpty()) {
            Log.i(TAG, "preRecordAll: all ${theme.files.size} cached")
            onComplete?.invoke(); return
        }
        preRecording = true
        onRecordingChanged?.invoke(true)
        fun recordNext(idx: Int) {
            if (idx >= toRecord.size || !preRecording) {
                preRecording = false
                post { onRecordingChanged?.invoke(false); currentSvg = ""; refresh(); onComplete?.invoke() }
                return
            }
            val svg = toRecord[idx]
            post { onRecordProgress?.invoke(svg.substringBeforeLast('.').replace('-', ' '), idx + 1, toRecord.size) }
            val framesDir = frameCacheDir(theme.hash, svg)
            val svgText = readSvgSource(theme, svg) ?: run { recordNext(idx + 1); return }
            recorder?.cancel()
            val rec = FrameRecorder(context, this, if (width > 0) width else FALLBACK_SIZE_PX, FPS)
            recorder = rec
            rec.record(svgText, framesDir) { frames ->
                rec.shutdown()
                post {
                    if (recorder === rec) recorder = null
                    Log.i(TAG, "preRecordAll: $svg ${frames.size} frames [${idx+1}/${toRecord.size}]")
                    // Play the just-recorded state so user sees each animation
                    if (frames.isNotEmpty() && isComplete(framesDir)) playFrames(framesDir)
                    recordNext(idx + 1)
                }
            }
        }
        recordNext(0)
    }

    fun cancelPreRecord() { preRecording = false; recorder?.cancel(); recorder = null }

    private fun recordSingle(theme: com.clawd.watch.domain.ThemeManifest, svg: String, framesDir: File, gen: Int) {
        val svgText = readSvgSource(theme, svg) ?: run { showFallback(); return }
        showFallback()
        recorder?.cancel()
        val rec = FrameRecorder(context, this, if (width > 0) width else FALLBACK_SIZE_PX, FPS)
        recorder = rec
        rec.record(svgText, framesDir) { frames ->
            rec.shutdown()
            post {
                if (recorder === rec) recorder = null
                if (gen != generation) return@post
                if (frames.isNotEmpty() && isComplete(framesDir)) playFrames(framesDir) else showFallback()
            }
        }
    }

    private fun playFrames(framesDir: File) {
        val frames = listFrames(framesDir)
        val bitmaps = decodeFrames(frames)
        if (bitmaps.isEmpty()) { showFallback(); return }
        val (loopMs, count) = readMeta(framesDir)
        val intervalMs = if (loopMs > 0 && count > 0) (loopMs / count).coerceAtLeast(1L) else (1000L / FPS)
        fallbackLabel.visibility = GONE; imageView.visibility = VISIBLE
        player.play(imageView, bitmaps, intervalMs)
    }

    private fun showFallback() {
        player.stop(); imageView.visibility = GONE
        fallbackLabel.visibility = VISIBLE; fallbackLabel.text = state.name
    }

    private fun frameCacheDir(hash: String, svg: String): File =
        File(context.filesDir, "themes/$hash/frames/${svg.substringBeforeLast('.')}")
    private fun isComplete(dir: File): Boolean =
        File(dir, "meta.json").isFile && listFrames(dir).isNotEmpty()
    private fun listFrames(dir: File): List<File> {
        if (!dir.isDirectory) return emptyList()
        return dir.listFiles { f -> f.isFile && (f.name.endsWith(".webp") || f.name.endsWith(".png") || f.name.endsWith(".jpg")) }
            ?.sortedBy { it.name } ?: emptyList()
    }
    private fun readMeta(dir: File): Pair<Long, Int> = try {
        val j = JSONObject(File(dir, "meta.json").readText(Charsets.UTF_8))
        j.optLong("loopMs", 0) to j.optInt("count", 0)
    } catch (_: Exception) { 0L to 0 }
    private fun decodeFrames(frames: List<File>): List<Bitmap> {
        val out = ArrayList<Bitmap>(frames.size)
        for (f in frames) try { BitmapFactory.decodeFile(f.absolutePath)?.let { out.add(it) } }
        catch (e: Exception) { Log.w(TAG, "decode ${f.name}: ${e.message}") }
        return out
    }
    private fun readSvgSource(theme: com.clawd.watch.domain.ThemeManifest, svg: String): String? = try {
        if (theme.isBundled) context.assets.open("svg/$svg").use { it.readBytes().toString(Charsets.UTF_8) }
        else theme.svgDir?.let { File(it, svg).takeIf { f -> f.isFile }?.readText(Charsets.UTF_8) }
    } catch (e: Exception) { Log.w(TAG, "readSvg $svg: ${e.message}"); null }

    fun pause() = player.pause()
    fun resume() = player.resume()

    @SuppressLint("ClickableViewAccessibility")
    override fun onInterceptTouchEvent(ev: MotionEvent?): Boolean {
        if (hasOnClickListeners() || isLongClickable) return true
        return super.onInterceptTouchEvent(ev)
    }
    override fun onDetachedFromWindow() { player.stop(); super.onDetachedFromWindow() }

    private class FramePlayer {
        private var frames: List<Bitmap> = emptyList()
        private var target: ImageView? = null
        private var index = 0; private var intervalMs = 50L; private var running = false
        private val handler = android.os.Handler(android.os.Looper.getMainLooper())
        private val tick = object : Runnable {
            override fun run() {
                if (!running || frames.isEmpty()) return
                target?.setImageBitmap(frames[index])
                index = (index + 1) % frames.size
                handler.postDelayed(this, intervalMs)
            }
        }
        fun play(view: ImageView, bitmaps: List<Bitmap>, ms: Long) {
            stop(); frames = bitmaps; target = view; intervalMs = ms.coerceAtLeast(1L)
            if (frames.size == 1) { view.setImageBitmap(frames[0]); return }
            running = true; handler.post(tick)
        }
        fun pause() { running = false; handler.removeCallbacks(tick) }
        fun resume() { if (!running && frames.size > 1 && target != null) { running = true; handler.post(tick) } }
        fun stop() { running = false; handler.removeCallbacks(tick); target?.setImageBitmap(null); frames = emptyList(); target = null; index = 0 }
    }
}
