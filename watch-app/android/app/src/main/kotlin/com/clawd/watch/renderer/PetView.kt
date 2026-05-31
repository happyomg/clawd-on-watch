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
import android.webkit.WebSettings
import android.webkit.WebView
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import com.clawd.watch.domain.ClawdState
import com.clawd.watch.domain.ThemeConfig
import java.io.File

/**
 * Steady-state pet renderer. Plays a cached frame sequence with a plain
 * [ImageView] (native bitmap blitting, ~7 MB, no compositor) — the WebView is
 * used only as a transient live fallback while a state's frames are being
 * recorded for the first time, then it goes idle.
 *
 * View stack: ImageView (front) over WebView (back). When cached frames exist
 * the ImageView plays them and the WebView is paused; otherwise the WebView
 * renders live while [FrameRecorder] captures the loop in the background.
 *
 * Drop-in replacement for the old SvgPetView API (`state`, `activeSessionCount`,
 * `setSvgImmediate`, `pause`, `resume`).
 */
class PetView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    companion object {
        private const val TAG = "PetView"
        private const val FPS = 10
        private const val FRAME_SIZE_PX = 192
    }

    private var imageView: ImageView? = null
    private var webView: WebView? = null
    private var fallbackLabel: TextView? = null
    private var webViewLoaded = false
    private val webViewAvailable: Boolean

    private val player = FramePlayer()
    private var currentSvg: String = ""
    /** Bumped on every showSvg so stale recorder callbacks are ignored. */
    private var generation = 0

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
        imageView = ImageView(context).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            visibility = GONE
        }
        addView(imageView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))

        webViewAvailable = try {
            val wv = WebView(context).apply {
                setBackgroundColor(0x00000000)
                settings.apply {
                    javaScriptEnabled = true
                    useWideViewPort = true
                    loadWithOverviewMode = true
                    setSupportZoom(false)
                    allowFileAccess = false
                    cacheMode = WebSettings.LOAD_NO_CACHE
                }
                setLayerType(LAYER_TYPE_HARDWARE, null)
                isVerticalScrollBarEnabled = false
                isHorizontalScrollBarEnabled = false
                webViewClient = object : android.webkit.WebViewClient() {
                    override fun onPageFinished(view: WebView?, url: String?) {
                        webViewLoaded = true
                        refresh()
                    }
                }
            }
            webView = wv
            // Behind the ImageView.
            addView(wv, 0, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
            wv.loadUrl("file:///android_asset/pet_renderer.html")
            true
        } catch (e: Exception) {
            Log.w(TAG, "WebView not available, using fallback: ${e.message}")
            val label = TextView(context).apply {
                setTextColor(Color.WHITE)
                textSize = 14f
                gravity = Gravity.CENTER
                text = "IDLE"
            }
            fallbackLabel = label
            addView(label, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
            webViewLoaded = true
            false
        }
    }

    /** Re-resolve the SVG for the current state/count and (re)display it. */
    private fun refresh() {
        val svg = ThemeConfig.resolveSvg(state, activeSessionCount)
        showSvg(svg)
    }

    /** Public API parity with the old view — show a specific file immediately. */
    fun setSvgImmediate(filename: String) {
        showSvg(filename)
    }

    /**
     * The active theme changed (a CWD5 sync completed). Force a re-resolve so
     * the current state renders under the new theme — frames resolve under the
     * new theme hash, and any uncached state records lazily on display.
     */
    fun reloadForThemeChange() {
        currentSvg = ""
        refresh()
    }

    private fun showSvg(svg: String) {
        if (svg == currentSvg) return
        currentSvg = svg
        val gen = ++generation

        if (!webViewAvailable) {
            fallbackLabel?.text = "${state.name}\n$svg"
            return
        }

        val theme = ThemeConfig.active
        val framesDir = frameCacheDir(theme.hash, svg)
        val cached = listFrames(framesDir)
        if (cached.isNotEmpty()) {
            playFrames(cached)
            return
        }

        // No cache yet → render live in the WebView while we record.
        showWebViewLive(svg)
        recordInBackground(theme, svg, framesDir, gen)
    }

    private fun showWebViewLive(svg: String) {
        player.stop()
        imageView?.visibility = GONE
        webView?.visibility = VISIBLE
        if (webViewLoaded) {
            webView?.evaluateJavascript("setSvgImmediate('${escapeJs(svg)}')", null)
        }
    }

    private fun playFrames(frames: List<File>) {
        val bitmaps = decodeFrames(frames)
        if (bitmaps.isEmpty()) return
        val iv = imageView ?: return
        webView?.visibility = GONE
        iv.visibility = VISIBLE
        player.play(iv, bitmaps, FPS)
    }

    private fun recordInBackground(theme: com.clawd.watch.domain.ThemeManifest, svg: String, framesDir: File, gen: Int) {
        val svgText = readSvgSource(theme, svg) ?: run {
            Log.w(TAG, "svg source missing for $svg")
            return
        }
        val recorder = FrameRecorder(context.applicationContext, this, FRAME_SIZE_PX, FPS)
        recorder.record(svgText, framesDir) { frames ->
            recorder.shutdown()
            post {
                // Ignore if the displayed state changed while we were recording.
                if (gen != generation) return@post
                if (frames.isNotEmpty()) playFrames(frames)
            }
        }
    }

    // ── Sources & cache paths ──

    private fun frameCacheDir(hash: String, svg: String): File {
        val base = svg.substringBeforeLast('.')
        return File(context.filesDir, "themes/$hash/frames/$base")
    }

    private fun listFrames(dir: File): List<File> {
        if (!dir.isDirectory) return emptyList()
        return dir.listFiles { f -> f.isFile && f.name.endsWith(".webp") }
            ?.sortedBy { it.name } ?: emptyList()
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

    private fun escapeJs(s: String): String =
        s.replace("\\", "\\\\").replace("'", "\\'").replace("\n", "\\n").replace("\r", "\\r")

    // ── Lifecycle ──

    fun pause() {
        player.pause()
        webView?.onPause()
    }

    fun resume() {
        player.resume()
        webView?.onResume()
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onInterceptTouchEvent(ev: MotionEvent?): Boolean {
        if (hasOnClickListeners() || isLongClickable) return true
        return super.onInterceptTouchEvent(ev)
    }

    override fun onDetachedFromWindow() {
        player.stop()
        webView?.destroy()
        webView = null
        super.onDetachedFromWindow()
    }

    /**
     * Cycles a list of pre-decoded bitmaps on an ImageView at a fixed fps using
     * the view's own message loop. One sequence in memory at a time.
     */
    private class FramePlayer {
        private var frames: List<Bitmap> = emptyList()
        private var target: ImageView? = null
        private var index = 0
        private var intervalMs = 100L
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

        fun play(view: ImageView, bitmaps: List<Bitmap>, fps: Int) {
            stop()
            frames = bitmaps
            target = view
            index = 0
            intervalMs = (1000L / fps).coerceAtLeast(1L)
            if (frames.size == 1) {
                view.setImageBitmap(frames[0]) // static, no loop needed
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
