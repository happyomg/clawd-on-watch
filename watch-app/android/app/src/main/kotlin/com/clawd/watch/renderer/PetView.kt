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
 * Pet renderer. Plays pre-rendered frame sequences from cache with a plain
 * [ImageView]. No WebView needed — frames are rendered on the Desktop side
 * and pushed to the watch over BLE.
 *
 * If no cached frames exist for the current state, a text fallback is shown
 * until the Desktop syncs the theme.
 */
class PetView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    companion object {
        private const val TAG = "PetView"
        private const val DEFAULT_FPS = 20
    }

    private val imageView = ImageView(context).apply {
        scaleType = ImageView.ScaleType.FIT_CENTER
        visibility = GONE
    }
    private val fallbackLabel = TextView(context).apply {
        setTextColor(Color.WHITE)
        textSize = 14f
        gravity = Gravity.CENTER
        text = "…"
    }

    private val player = FramePlayer()
    private var currentSvg: String = ""

    var state: ClawdState = ClawdState.IDLE
        set(value) {
            if (field == value) return
            field = value
            refresh()
        }

    var activeSessionCount: Int = 1
        set(value) {
            if (field == value) return
            field = value
            refresh()
        }

    init {
        addView(fallbackLabel, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(imageView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
    }

    private fun refresh() = showSvg(ThemeConfig.resolveSvg(state, activeSessionCount))

    fun setSvgImmediate(filename: String) = showSvg(filename)

    fun reloadForThemeChange() {
        currentSvg = ""
        refresh()
    }

    private fun showSvg(svg: String) {
        if (svg == currentSvg) return
        currentSvg = svg

        val theme = ThemeConfig.active
        val framesDir = frameCacheDir(theme.hash, svg)
        if (isComplete(framesDir)) {
            playFrames(framesDir)
        } else {
            showFallback()
        }
    }

    private fun playFrames(framesDir: File) {
        val frames = listFrames(framesDir)
        val bitmaps = decodeFrames(frames)
        if (bitmaps.isEmpty()) { showFallback(); return }
        val (loopMs, count) = readMeta(framesDir)
        val intervalMs = if (loopMs > 0 && count > 0) (loopMs / count).coerceAtLeast(1L) else (1000L / DEFAULT_FPS)
        fallbackLabel.visibility = GONE
        imageView.visibility = VISIBLE
        player.play(imageView, bitmaps, intervalMs)
    }

    private fun showFallback() {
        player.stop()
        imageView.visibility = GONE
        fallbackLabel.visibility = VISIBLE
        fallbackLabel.text = state.name
    }

    // ── Cache paths ──

    private fun frameCacheDir(hash: String, svg: String): File =
        File(context.filesDir, "themes/$hash/frames/${svg.substringBeforeLast('.')}")

    private fun isComplete(dir: File): Boolean =
        File(dir, "meta.json").isFile && listFrames(dir).isNotEmpty()

    private fun listFrames(dir: File): List<File> {
        if (!dir.isDirectory) return emptyList()
        return dir.listFiles { f -> f.isFile && (f.name.endsWith(".webp") || f.name.endsWith(".png")) }
            ?.sortedBy { it.name } ?: emptyList()
    }

    private fun readMeta(dir: File): Pair<Long, Int> {
        return try {
            val j = JSONObject(File(dir, "meta.json").readText(Charsets.UTF_8))
            j.optLong("loopMs", 0) to j.optInt("count", 0)
        } catch (e: Exception) { 0L to 0 }
    }

    private fun decodeFrames(frames: List<File>): List<Bitmap> {
        val out = ArrayList<Bitmap>(frames.size)
        for (f in frames) {
            try { BitmapFactory.decodeFile(f.absolutePath)?.let { out.add(it) } }
            catch (e: Exception) { Log.w(TAG, "decode failed ${f.name}: ${e.message}") }
        }
        return out
    }

    // ── Lifecycle ──

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
        private var index = 0
        private var intervalMs = 50L
        private var running = false
        private val handler = android.os.Handler(android.os.Looper.getMainLooper())

        private val tick = object : Runnable {
            override fun run() {
                if (!running || frames.isEmpty()) return
                val iv = target ?: return
                iv.setImageBitmap(frames[index])
                index = (index + 1) % frames.size
                handler.postDelayed(this, intervalMs)
            }
        }

        fun play(view: ImageView, bitmaps: List<Bitmap>, frameIntervalMs: Long) {
            stop(); frames = bitmaps; target = view; index = 0
            intervalMs = frameIntervalMs.coerceAtLeast(1L)
            if (frames.size == 1) { view.setImageBitmap(frames[0]); return }
            running = true; handler.post(tick)
        }
        fun pause() { running = false; handler.removeCallbacks(tick) }
        fun resume() { if (!running && frames.size > 1 && target != null) { running = true; handler.post(tick) } }
        fun stop() { running = false; handler.removeCallbacks(tick); target?.setImageBitmap(null); frames = emptyList(); target = null; index = 0 }
    }
}
