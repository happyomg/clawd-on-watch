package com.clawd.watch.renderer

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.WebView
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors

/**
 * Records one CSS-animated SVG loop into a frame sequence using View.draw().
 *
 * The WebView is attached to [parent] but invisible (alpha=0) so CSS animations
 * keep advancing while the user sees whatever overlay the caller shows.
 * enableSlowWholeDocumentDraw() ensures View.draw(Canvas) captures the full
 * WebView content including CSS-composited layers.
 *
 * No PixelCopy — View.draw directly to a Bitmap, so only the WebView's own
 * content is captured, never any surrounding UI chrome.
 */
class FrameRecorder(
    private val context: Context,
    private val parent: ViewGroup,
    private val sizePx: Int = 192,
    private val fps: Int = 20
) {
    companion object {
        private const val TAG = "FrameRecorder"
        private const val MIN_FRAMES = 12
        private const val MAX_FRAMES = 100
        private const val DEFAULT_LOOP_MS = 2000L
        private const val READY_TIMEOUT_MS = 8000L
        private const val WEBP_QUALITY = 90

        init {
            try { WebView.enableSlowWholeDocumentDraw() } catch (_: Exception) {}
        }
    }

    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    @Volatile private var finished = false
    @Volatile private var readyFired = false
    private var activeWebView: WebView? = null

    fun cancel() {
        if (finished) return
        finished = true
        main.post {
            try { activeWebView?.let { parent.removeView(it); it.destroy() } }
            catch (_: Exception) {}
            activeWebView = null
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    fun record(svgText: String, outputDir: File, onComplete: (List<File>) -> Unit) {
        readyFired = false
        val frameIntervalMs = (1000L / fps).coerceAtLeast(1L)
        val webView: WebView
        try { webView = WebView(context) }
        catch (e: Exception) { Log.w(TAG, "WebView unavailable: ${e.message}"); onComplete(emptyList()); return }

        activeWebView = webView

        fun finish(frames: List<File>) {
            if (finished) return
            finished = true
            main.post {
                try { parent.removeView(webView); webView.destroy() } catch (_: Exception) {}
                activeWebView = null
            }
            onComplete(frames)
        }

        webView.apply {
            layoutParams = ViewGroup.LayoutParams(sizePx, sizePx)
            setBackgroundColor(0x00000000)
            alpha = 0f  // invisible but attached — CSS animations keep advancing
            setLayerType(View.LAYER_TYPE_SOFTWARE, null)
            settings.javaScriptEnabled = true
            settings.allowFileAccess = false
            isVerticalScrollBarEnabled = false
            isHorizontalScrollBarEnabled = false
            addJavascriptInterface(Bridge { loopMs ->
                if (readyFired) return@Bridge
                readyFired = true
                onReady(this, loopMs, frameIntervalMs, outputDir, ::finish)
            }, "AndroidRec")
            webViewClient = object : android.webkit.WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    main.postDelayed({
                        if (!readyFired && !finished) {
                            view?.evaluateJavascript("try{AndroidRec.ready(loopMs())}catch(e){AndroidRec.ready(0)}", null)
                        }
                    }, 500)
                }
            }
        }

        try { parent.addView(webView) }
        catch (e: Exception) { Log.w(TAG, "attach failed: ${e.message}"); finish(emptyList()); return }

        webView.loadDataWithBaseURL("file:///android_asset/", buildHarness(svgText), "text/html", "utf-8", null)
        main.postDelayed({ if (!finished && !readyFired) { Log.w(TAG, "ready timeout"); finish(emptyList()) } }, READY_TIMEOUT_MS)
    }

    private fun onReady(webView: WebView, loopMs: Long, frameIntervalMs: Long, outputDir: File, finish: (List<File>) -> Unit) {
        if (finished) return
        val effectiveLoop = if (loopMs <= 0) DEFAULT_LOOP_MS else loopMs
        val frameCount = ((effectiveLoop / frameIntervalMs).toInt()).coerceIn(MIN_FRAMES, MAX_FRAMES)
        val coveredLoopMs = frameCount * frameIntervalMs
        outputDir.mkdirs()
        val written = arrayOfNulls<File>(frameCount)
        var index = 0
        val pendingWrites = java.util.concurrent.atomic.AtomicInteger(0)
        val allDispatched = java.util.concurrent.atomic.AtomicBoolean(false)

        fun tryFinalize() {
            if (!allDispatched.get() || pendingWrites.get() > 0) return
            writeMeta(outputDir, coveredLoopMs, frameCount)
            finish(written.filterNotNull())
        }

        val capture = object : Runnable {
            override fun run() {
                if (finished) return
                if (index >= frameCount) {
                    allDispatched.set(true)
                    io.execute { tryFinalize() }
                    return
                }
                val bmp = try {
                    Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888).also { b ->
                        webView.draw(Canvas(b))
                    }
                } catch (e: Exception) { Log.w(TAG, "capture @$index: ${e.message}"); null }
                val i = index; index++
                if (bmp != null) {
                    val out = File(outputDir, "%03d.webp".format(i))
                    pendingWrites.incrementAndGet()
                    io.execute {
                        try {
                            out.outputStream().use { os ->
                                @Suppress("DEPRECATION")
                                bmp.compress(Bitmap.CompressFormat.WEBP, WEBP_QUALITY, os)
                            }
                            written[i] = out
                        } catch (e: Exception) { Log.w(TAG, "compress @$i: ${e.message}") }
                        finally { bmp.recycle(); pendingWrites.decrementAndGet(); tryFinalize() }
                    }
                }
                main.postDelayed(this, frameIntervalMs)
            }
        }
        main.post(capture)
    }

    private fun writeMeta(dir: File, loopMs: Long, count: Int) {
        try {
            File(dir, "meta.json").writeText(
                JSONObject().put("loopMs", loopMs).put("count", count).put("fps", fps).toString(), Charsets.UTF_8
            )
        } catch (e: Exception) { Log.w(TAG, "meta write: ${e.message}") }
    }

    private class Bridge(val onReady: (Long) -> Unit) {
        @JavascriptInterface
        fun ready(loopMs: Double) { Handler(Looper.getMainLooper()).post { onReady(loopMs.toLong()) } }
    }

    private fun buildHarness(svgText: String): String = """
<!DOCTYPE html><html><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1,user-scalable=no">
<style>
*{margin:0;padding:0;box-sizing:border-box}
html,body{width:100%;height:100%;background:transparent;overflow:hidden}
#c{width:100%;height:100%;position:relative;overflow:hidden}
#c svg{width:160%;height:160%;position:absolute;left:-30%;top:-65%}
</style></head>
<body><div id="c">$svgText</div>
<script>
function parseSec(v){if(!v)return 0;var t=0;v.split(',').forEach(function(p){p=p.trim();var m=p.match(/([0-9.]+)(ms|s)?/);if(m){var n=parseFloat(m[1]);if(m[2]==='ms')n/=1000;if(n>t)t=n}});return t}
function loopMs(){var nodes=document.querySelectorAll('#c *');var max=0;for(var i=0;i<nodes.length;i++){var s=getComputedStyle(nodes[i]);var d=parseSec(s.animationDuration)+parseSec(s.animationDelay);var it=s.animationIterationCount;if(it&&it!=='infinite'){var n=parseFloat(it);if(!isNaN(n))d=parseSec(s.animationDuration)*n+parseSec(s.animationDelay)}if(d>max)max=d}return Math.round(max*1000)}
setTimeout(function(){try{AndroidRec.ready(loopMs())}catch(e){try{AndroidRec.ready(0)}catch(_){}}},250);
</script></body></html>
""".trimIndent()

    fun shutdown() { io.shutdown() }
}
