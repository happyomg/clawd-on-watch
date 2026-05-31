package com.clawd.watch.renderer

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.view.PixelCopy
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.WebView
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors

/**
 * Records one animation loop of a CSS-animated SVG into a frame sequence.
 *
 * Capture uses [PixelCopy] (API 26+) which reads from the hardware-composited
 * surface — this works reliably on all devices whereas `View.draw(Canvas)` with
 * a software layer often produces blank frames on real hardware. The WebView
 * renders normally with hardware acceleration; PixelCopy grabs the
 * already-composited pixels from the GPU.
 *
 * The recording WebView is added to [parent] *visibly* (foreground sync mode)
 * so the user watches the real animation while it is captured. It is destroyed
 * when recording finishes.
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
            try {
                activeWebView?.let { wv -> parent.removeView(wv); wv.destroy() }
            } catch (_: Exception) {}
            activeWebView = null
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    fun record(svgText: String, outputDir: File, onCaptureStarted: (() -> Unit)? = null, onComplete: (List<File>) -> Unit) {
        readyFired = false
        val frameIntervalMs = (1000L / fps).coerceAtLeast(1L)
        val webView: WebView
        try {
            webView = WebView(context)
        } catch (e: Exception) {
            Log.w(TAG, "WebView unavailable: ${e.message}")
            onComplete(emptyList())
            return
        }

        activeWebView = webView

        fun finish(frames: List<File>) {
            if (finished) return
            finished = true
            main.post {
                try {
                    parent.removeView(webView)
                    webView.destroy()
                } catch (_: Exception) {}
                activeWebView = null
            }
            onComplete(frames)
        }

        webView.apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            setBackgroundColor(0x00000000)
            setLayerType(View.LAYER_TYPE_HARDWARE, null)
            settings.javaScriptEnabled = true
            settings.allowFileAccess = false
            isVerticalScrollBarEnabled = false
            isHorizontalScrollBarEnabled = false
            addJavascriptInterface(
                Bridge { loopMs ->
                    if (readyFired) return@Bridge
                    readyFired = true
                    main.post { onCaptureStarted?.invoke() }
                    onReady(this, loopMs, frameIntervalMs, outputDir, ::finish)
                },
                "AndroidRec"
            )
            webViewClient = object : android.webkit.WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    // Belt-and-suspenders: the harness <script> calls ready()
                    // on its own, but if that fails we retry from Kotlin.
                    main.postDelayed({
                        if (!readyFired && !finished) {
                            Log.i(TAG, "onPageFinished retry: invoking ready via evaluateJavascript")
                            view?.evaluateJavascript(
                                "try{AndroidRec.ready(loopMs())}catch(e){AndroidRec.ready(0)}", null
                            )
                        }
                    }, 500)
                }
            }
        }

        try {
            parent.addView(webView)
        } catch (e: Exception) {
            Log.w(TAG, "attach failed: ${e.message}")
            finish(emptyList())
            return
        }

        webView.loadDataWithBaseURL(
            "file:///android_asset/", buildHarness(svgText), "text/html", "utf-8", null
        )
        main.postDelayed({
            if (!finished && !readyFired) { Log.w(TAG, "ready timeout"); finish(emptyList()) }
        }, READY_TIMEOUT_MS)
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
        val coveredLoopMs = frameCount * frameIntervalMs
        outputDir.mkdirs()
        val written = arrayOfNulls<File>(frameCount)
        var index = 0
        val pendingWrites = java.util.concurrent.atomic.AtomicInteger(0)
        val allDispatched = java.util.concurrent.atomic.AtomicBoolean(false)

        val pixelCopyThread = HandlerThread("FrameRecCapture").apply { start() }
        val pixelCopyHandler = Handler(pixelCopyThread.looper)

        fun tryFinalize() {
            if (!allDispatched.get() || pendingWrites.get() > 0) return
            writeMeta(outputDir, coveredLoopMs, frameCount)
            pixelCopyThread.quitSafely()
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

                val bmp = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
                val i = index
                index++

                try {
                    val loc = IntArray(2)
                    webView.getLocationInWindow(loc)
                    val srcRect = Rect(loc[0], loc[1], loc[0] + webView.width, loc[1] + webView.height)

                    val activity = findActivity(webView)
                    if (activity == null) {
                        Log.w(TAG, "no window for PixelCopy")
                        bmp.recycle()
                        main.postDelayed(this, frameIntervalMs)
                        return
                    }

                    pendingWrites.incrementAndGet()
                    PixelCopy.request(
                        activity.window, srcRect, bmp,
                        { result ->
                            if (result == PixelCopy.SUCCESS) {
                                val out = File(outputDir, "%03d.webp".format(i))
                                io.execute {
                                    try {
                                        out.outputStream().use { os ->
                                            @Suppress("DEPRECATION")
                                            bmp.compress(Bitmap.CompressFormat.WEBP, WEBP_QUALITY, os)
                                        }
                                        written[i] = out
                                    } catch (e: Exception) {
                                        Log.w(TAG, "compress failed @$i: ${e.message}")
                                    } finally {
                                        bmp.recycle()
                                        pendingWrites.decrementAndGet()
                                        tryFinalize()
                                    }
                                }
                            } else {
                                Log.w(TAG, "PixelCopy failed @$i result=$result")
                                bmp.recycle()
                                pendingWrites.decrementAndGet()
                                tryFinalize()
                            }
                        },
                        pixelCopyHandler
                    )
                } catch (e: Exception) {
                    Log.w(TAG, "capture failed @$i: ${e.message}")
                    bmp.recycle()
                }

                main.postDelayed(this, frameIntervalMs)
            }
        }
        main.post(capture)
    }

    private fun findActivity(view: View): android.app.Activity? {
        var ctx = view.context
        while (ctx is android.content.ContextWrapper) {
            if (ctx is android.app.Activity) return ctx
            ctx = ctx.baseContext
        }
        return null
    }

    private fun writeMeta(dir: File, loopMs: Long, count: Int) {
        try {
            val meta = JSONObject().put("loopMs", loopMs).put("count", count).put("fps", fps)
            File(dir, "meta.json").writeText(meta.toString(), Charsets.UTF_8)
        } catch (e: Exception) {
            Log.w(TAG, "meta write failed: ${e.message}")
        }
    }

    private class Bridge(val onReady: (Long) -> Unit) {
        @JavascriptInterface
        fun ready(loopMs: Double) {
            Handler(Looper.getMainLooper()).post { onReady(loopMs.toLong()) }
        }
    }

    private fun buildHarness(svgText: String): String {
        return """
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
  setTimeout(go, 250);
})();
</script></body></html>
""".trimIndent()
    }

    fun shutdown() {
        io.shutdown()
    }
}
