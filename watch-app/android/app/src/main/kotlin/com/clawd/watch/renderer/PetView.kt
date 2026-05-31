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
 * Steady-state pet renderer. Plays a cached frame sequence with a plain
 * [ImageView] — no resident WebView. When a state has no cached frames yet it
 * enters "foreground sync mode": [FrameRecorder] attaches a *visible* WebView
 * on top, the user watches the real animation while it is captured, then it is
 * destroyed and playback switches to the ImageView. So a WebView exists only
 * during the brief one-time recording of each state.
 *
 * Playback loop is spaced as loopMs/count (from the recorder's meta.json) for a
 * seamless cycle. Frames are recorded at 20fps.
 *
 * Drop-in API parity with the old view: `state`, `activeSessionCount`,
 * `setSvgImmediate`, `reloadForThemeChange`, `pause`, `resume`.
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
        setTextColor(Color.WHITE)
        textSize = 14f
        gravity = Gravity.CENTER
        text = "…"
    }

    /**
     * Sync overlay is managed at the Activity level (outside PetView) so that
     * PixelCopy captures only the WebView content without UI chrome.
     */

    private val player = FramePlayer()
    private var currentSvg: String = ""
    /** Bumped on every showSvg so stale recorder callbacks are ignored. */
    private var generation = 0
    private var recorder: FrameRecorder? = null

    /** Notifies when on-device recording (foreground sync) starts/stops. */
    var onRecordingChanged: ((Boolean) -> Unit)? = null
    /** Notifies when frame capture actually begins (WebView loaded, overlay should hide). */
    var onCaptureStarted: (() -> Unit)? = null
    /** Progress during preRecordAll: (svgName, currentIndex 1-based, total). */
    var onRecordProgress: ((String, Int, Int) -> Unit)? = null

    private var preRecording = false

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

    /** Public API parity — show a specific file immediately. */
    fun setSvgImmediate(filename: String) = showSvg(filename)

    /**
     * The active theme changed (a CWD5 sync completed). Force a re-resolve so
     * the current state renders under the new theme — frames resolve under the
     * new theme hash, and any uncached state records lazily on display.
     */
    fun reloadForThemeChange() {
        currentSvg = ""
        refresh()
    }

    /**
     * Pre-record ALL SVG files in the active theme. Skips already-cached states.
     * Each state's animation is rendered in a visible WebView (user sees it), then
     * switches to the next. On completion, displays the current state via cached
     * playback.
     *
     * Call after receiving a new theme or on first launch.
     */
    fun preRecordAll(onComplete: (() -> Unit)? = null) {
        val theme = ThemeConfig.active
        val allFiles = theme.files
        if (allFiles.isEmpty()) { onComplete?.invoke(); return }

        val toRecord = allFiles.filter { !isComplete(frameCacheDir(theme.hash, it)) }
        if (toRecord.isEmpty()) {
            Log.i(TAG, "preRecordAll: all ${allFiles.size} states cached, nothing to do")
            onComplete?.invoke()
            return
        }

        val total = toRecord.size
        Log.i(TAG, "preRecordAll: ${total} states to record for ${theme.name}")
        preRecording = true
        onRecordingChanged?.invoke(true)

        fun recordNext(idx: Int) {
            if (idx >= total || !preRecording) {
                preRecording = false
                post {
                    onRecordingChanged?.invoke(false)
                    currentSvg = ""
                    refresh()
                    onComplete?.invoke()
                }
                return
            }

            val svg = toRecord[idx]
            val prettyName = svg.substringBeforeLast('.').replace('-', ' ')
            post { onRecordProgress?.invoke(prettyName, idx + 1, total) }

            val framesDir = frameCacheDir(theme.hash, svg)
            val svgText = readSvgSource(theme, svg)
            if (svgText == null) {
                Log.w(TAG, "preRecordAll: missing $svg, skipping")
                recordNext(idx + 1)
                return
            }

            recorder?.cancel()
            val size = if (width > 0) width else FALLBACK_SIZE_PX
            val rec = FrameRecorder(context, this, size, FPS)
            recorder = rec
            rec.record(svgText, framesDir, onCaptureStarted = {
                post { onCaptureStarted?.invoke() }
            }) { frames ->
                rec.shutdown()
                post {
                    if (recorder === rec) recorder = null
                    Log.i(TAG, "preRecordAll: recorded $svg (${frames.size} frames) [${idx+1}/$total]")
                    recordNext(idx + 1)
                }
            }
        }
        recordNext(0)
    }

    /** Cancel an in-progress preRecordAll. */
    fun cancelPreRecord() {
        preRecording = false
        recorder?.cancel()
        recorder = null
    }

    private fun showSvg(svg: String) {
        if (preRecording) return
        if (svg == currentSvg) return
        currentSvg = svg
        val gen = ++generation

        val theme = ThemeConfig.active
        val framesDir = frameCacheDir(theme.hash, svg)
        if (isComplete(framesDir)) {
            playFrames(framesDir)
            return
        }
        // No cache → foreground-record this state, then switch to playback.
        recordInForeground(theme, svg, framesDir, gen)
    }

    private fun playFrames(framesDir: File) {
        val frames = listFrames(framesDir)
        val bitmaps = decodeFrames(frames)
        if (bitmaps.isEmpty()) {
            showFallback()
            return
        }
        val (loopMs, count) = readMeta(framesDir)
        val intervalMs = if (loopMs > 0 && count > 0) (loopMs / count).coerceAtLeast(1L) else (1000L / FPS)
        fallbackLabel.visibility = GONE
        imageView.visibility = VISIBLE
        player.play(imageView, bitmaps, intervalMs)
    }

    private fun recordInForeground(theme: com.clawd.watch.domain.ThemeManifest, svg: String, framesDir: File, gen: Int) {
        val svgText = readSvgSource(theme, svg)
        if (svgText == null) {
            Log.w(TAG, "svg source missing for $svg")
            showFallback()
            return
        }
        player.stop()
        imageView.visibility = GONE
        fallbackLabel.visibility = GONE
        recorder?.cancel()
        onRecordingChanged?.invoke(true)

        val size = if (width > 0) width else FALLBACK_SIZE_PX
        val rec = FrameRecorder(context, this, size, FPS)
        recorder = rec
        rec.record(svgText, framesDir, onCaptureStarted = {
            post { onCaptureStarted?.invoke() }
        }) { frames ->
            rec.shutdown()
            post {
                if (recorder === rec) recorder = null
                        onRecordingChanged?.invoke(false)
                if (gen != generation) return@post
                if (frames.isNotEmpty() && isComplete(framesDir)) playFrames(framesDir)
                else showFallback()
            }
        }
    }

    private fun showFallback() {
        player.stop()
        imageView.visibility = GONE
        fallbackLabel.visibility = VISIBLE
        fallbackLabel.text = state.name
    }

    // ── Sources & cache paths ──

    private fun frameCacheDir(hash: String, svg: String): File =
        File(context.filesDir, "themes/$hash/frames/${svg.substringBeforeLast('.')}")

    /** A dir is a complete recording once meta.json exists (written last). */
    private fun isComplete(dir: File): Boolean =
        File(dir, "meta.json").isFile && listFrames(dir).isNotEmpty()

    private fun listFrames(dir: File): List<File> {
        if (!dir.isDirectory) return emptyList()
        return dir.listFiles { f -> f.isFile && f.name.endsWith(".webp") }
            ?.sortedBy { it.name } ?: emptyList()
    }

    private fun readMeta(dir: File): Pair<Long, Int> {
        return try {
            val j = JSONObject(File(dir, "meta.json").readText(Charsets.UTF_8))
            j.optLong("loopMs", 0) to j.optInt("count", 0)
        } catch (e: Exception) {
            0L to 0
        }
    }

    private fun decodeFrames(frames: List<File>): List<Bitmap> {
        val out = ArrayList<Bitmap>(frames.size)
        for (f in frames) {
            try {
                BitmapFactory.decodeFile(f.absolutePath)?.let { out.add(it) }
            } catch (e: Exception) {
                Log.w(TAG, "decode failed ${f.name}: ${e.message}")
            }
        }
        return out
    }

    private fun readSvgSource(theme: com.clawd.watch.domain.ThemeManifest, svg: String): String? {
        return try {
            if (theme.isBundled) {
                context.assets.open("svg/$svg").use { it.readBytes().toString(Charsets.UTF_8) }
            } else {
                val dir = theme.svgDir ?: return null
                File(dir, svg).takeIf { it.isFile }?.readText(Charsets.UTF_8)
            }
        } catch (e: Exception) {
            Log.w(TAG, "readSvgSource $svg: ${e.message}")
            null
        }
    }

    // ── Lifecycle ──

    fun pause() = player.pause()

    fun resume() = player.resume()

    @SuppressLint("ClickableViewAccessibility")
    override fun onInterceptTouchEvent(ev: MotionEvent?): Boolean {
        if (hasOnClickListeners() || isLongClickable) return true
        return super.onInterceptTouchEvent(ev)
    }

    override fun onDetachedFromWindow() {
        player.stop()
        super.onDetachedFromWindow()
    }

    /**
     * Cycles pre-decoded bitmaps on an ImageView using the view's message loop.
     * Frame interval comes from the recorded cycle (loopMs/count) for a seamless
     * loop. One sequence in memory at a time.
     */
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
            stop()
            frames = bitmaps
            target = view
            index = 0
            intervalMs = frameIntervalMs.coerceAtLeast(1L)
            if (frames.size == 1) {
                view.setImageBitmap(frames[0])
                return
            }
            running = true
            handler.post(tick)
        }

        fun pause() {
            running = false
            handler.removeCallbacks(tick)
        }

        fun resume() {
            if (!running && frames.size > 1 && target != null) {
                running = true
                handler.post(tick)
            }
        }

        fun stop() {
            running = false
            handler.removeCallbacks(tick)
            target?.setImageBitmap(null)
            frames = emptyList()
            target = null
            index = 0
        }
    }
}
