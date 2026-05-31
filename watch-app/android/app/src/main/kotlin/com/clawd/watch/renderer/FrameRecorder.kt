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
import java.io.File
import java.util.concurrent.Executors

/**
 * Records one animation loop of a CSS-animated SVG into a sequence of bitmap
 * frames, so the steady-state renderer can play them with a plain ImageView
 * instead of a permanently-resident WebView.
 *
 * The WebView lives only for the duration of one recording: it is attached to
 * [parent] (CSS animation timelines only advance while attached), sized to the
 * target resolution, kept visually invisible, and destroyed when done. A
 * software layer is used so [View.draw] captures the exact on-screen pixels of
 * the current animation frame regardless of hardware-layer compositing.
 *
 * Frames are captured on the main thread (WebView is single-threaded) and
 * WebP-compressed on a worker thread. The whole job is best-effort: any failure
 * yields an empty result and the caller falls back to live WebView rendering.
 */
class FrameRecorder(
    private val context: Context,
    private val parent: ViewGroup,
    private val sizePx: Int = 192,
    private val fps: Int = 10
) {
    companion object {
        private const val TAG = "FrameRecorder"
        private const val MIN_FRAMES = 8
        private const val MAX_FRAMES = 60          // 6s at 10fps — caps cache size
        private const val DEFAULT_LOOP_MS = 2500L
        private const val READY_TIMEOUT_MS = 8000L
        private const val WEBP_QUALITY = 90
    }

    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()

    @Volatile private var finished = false

    @SuppressLint("SetJavaScriptEnabled")
    fun record(svgText: String, outputDir: File, onComplete: (List<File>) -> Unit) {
        val frameIntervalMs = (1000L / fps).coerceAtLeast(1L)
        val webView: WebView
        try {
            webView = WebView(context)
        } catch (e: Exception) {
            Log.w(TAG, "WebView unavailable: ${e.message}")
            onComplete(emptyList())
            return
        }

        fun finish(frames: List<File>) {
            if (finished) return
            finished = true
            main.post {
                try {
                    parent.removeView(webView)
                    webView.destroy()
                } catch (_: Exception) {}
            }
            onComplete(frames)
        }

        webView.apply {
            layoutParams = ViewGroup.LayoutParams(sizePx, sizePx)
            setBackgroundColor(0x00000000)
            alpha = 0f                              // invisible but still animating
            setLayerType(View.LAYER_TYPE_SOFTWARE, null)
            settings.javaScriptEnabled = true
            settings.allowFileAccess = false
            isVerticalScrollBarEnabled = false
            isHorizontalScrollBarEnabled = false
            addJavascriptInterface(Bridge { loopMs -> onReady(this, loopMs, frameIntervalMs, outputDir, ::finish) }, "AndroidRec")
        }

        try {
            parent.addView(webView)
        } catch (e: Exception) {
            Log.w(TAG, "attach failed: ${e.message}")
            finish(emptyList())
            return
        }

        webView.loadDataWithBaseURL("file:///android_asset/", buildHarness(svgText), "text/html", "utf-8", null)

        // Safety net — if onReady never fires, abort.
        main.postDelayed({ if (!finished) { Log.w(TAG, "ready timeout"); finish(emptyList()) } }, READY_TIMEOUT_MS)
    }

    private fun onReady(
        webView: WebView,
        loopMs: Long,
        frameIntervalMs: Long,
        outputDir: File,
        finish: (List<File>) -> Unit
    ) {
        if (finished) return
        val effectiveLoop = if (loopMs <= 0) DEFAULT_LOOP_MS else loopMs
        val frameCount = ((effectiveLoop / frameIntervalMs).toInt()).coerceIn(MIN_FRAMES, MAX_FRAMES)
        outputDir.mkdirs()
        val written = ArrayList<File>(frameCount)
        var index = 0

        val capture = object : Runnable {
            override fun run() {
                if (finished) return
                if (index >= frameCount) {
                    // Compression is async; ensure all queued work flushes before reporting.
                    io.execute { finish(written.toList()) }
                    return
                }
                val bmp = try {
                    Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888).also { b ->
                        webView.draw(Canvas(b))
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "capture failed @${index}: ${e.message}")
                    null
                }
                val i = index
                index++
                if (bmp != null) {
                    val out = File(outputDir, "%03d.webp".format(i))
                    io.execute {
                        try {
                            out.outputStream().use { os ->
                                @Suppress("DEPRECATION")
                                bmp.compress(Bitmap.CompressFormat.WEBP, WEBP_QUALITY, os)
                            }
                            synchronized(written) { written.add(out) }
                        } catch (e: Exception) {
                            Log.w(TAG, "compress failed @${i}: ${e.message}")
                        } finally {
                            bmp.recycle()
                        }
                    }
                }
                main.postDelayed(this, frameIntervalMs)
            }
        }
        main.post(capture)
    }

    /** JS → Kotlin: reports the SVG's loop duration in ms once layout settles. */
    private class Bridge(val onReady: (Long) -> Unit) {
        @JavascriptInterface
        fun ready(loopMs: Double) {
            Handler(Looper.getMainLooper()).post { onReady(loopMs.toLong()) }
        }
    }

    private fun buildHarness(svgText: String): String {
        // Inline the SVG (same-document → getComputedStyle works, no cross-origin
        // <object> access). Compute the longest finite animation cycle; infinite
        // iterations contribute their single-cycle duration.
        val escaped = svgText // injected as raw markup inside the container
        return """
<!DOCTYPE html><html><head><meta charset="utf-8">
<style>
*{margin:0;padding:0;box-sizing:border-box}
html,body{width:100%;height:100%;background:transparent;overflow:hidden}
#c{width:100%;height:100%;display:flex;align-items:center;justify-content:center;position:relative;overflow:hidden}
#c svg{width:160%;height:160%;position:absolute;left:-30%;top:-65%}
</style></head>
<body><div id="c">$escaped</div>
<script>
(function(){
  function parseSec(v){ if(!v) return 0; var t=0; v.split(',').forEach(function(p){ p=p.trim(); var m=p.match(/([0-9.]+)(ms|s)?/); if(m){ var n=parseFloat(m[1]); if(m[2]==='ms') n=n/1000; if(n>t) t=n; } }); return t; }
  function loopMs(){
    var nodes=document.querySelectorAll('#c *'); var max=0;
    for(var i=0;i<nodes.length;i++){
      var s=getComputedStyle(nodes[i]);
      var d=parseSec(s.animationDuration)+parseSec(s.animationDelay);
      var it=s.animationIterationCount;
      if(it && it!=='infinite'){ var n=parseFloat(it); if(!isNaN(n)) d=parseSec(s.animationDuration)*n+parseSec(s.animationDelay); }
      if(d>max) max=d;
    }
    return Math.round(max*1000);
  }
  function go(){ try{ AndroidRec.ready(loopMs()); }catch(e){ try{AndroidRec.ready(0);}catch(_){} } }
  // Let layout + first animation tick settle before measuring.
  setTimeout(go, 250);
})();
</script></body></html>
""".trimIndent()
    }

    fun shutdown() {
        io.shutdown()
    }
}
