package com.clawd.watch.renderer

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.util.AttributeSet
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.webkit.WebSettings
import android.webkit.WebView
import android.widget.FrameLayout
import android.widget.TextView
import com.clawd.watch.domain.ClawdState
import com.clawd.watch.domain.ThemeConfig

@SuppressLint("SetJavaScriptEnabled")
class SvgPetView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    companion object {
        private const val TAG = "SvgPetView"
    }

    private var webView: WebView? = null
    private var fallbackLabel: TextView? = null
    private var currentSvg: String = ""
    private var loaded = false
    private val webViewAvailable: Boolean

    var state: ClawdState = ClawdState.IDLE
        set(value) {
            if (field == value) return
            field = value
            updateSvg()
        }

    var activeSessionCount: Int = 1
        set(value) {
            if (field == value) return
            field = value
            updateSvg()
        }

    var displayHint: String? = null
        set(value) {
            if (field == value) return
            field = value
            updateSvg()
        }

    init {
        webViewAvailable = try {
            val wv = WebView(context).apply {
                setBackgroundColor(0x00000000)
                settings.apply {
                    javaScriptEnabled = true
                    useWideViewPort = true
                    loadWithOverviewMode = true
                    setSupportZoom(false)
                    allowFileAccess = true
                    cacheMode = WebSettings.LOAD_NO_CACHE
                }
                setLayerType(LAYER_TYPE_HARDWARE, null)
                isVerticalScrollBarEnabled = false
                isHorizontalScrollBarEnabled = false

                webViewClient = object : android.webkit.WebViewClient() {
                    override fun onPageFinished(view: WebView?, url: String?) {
                        loaded = true
                        updateSvg()
                    }
                }
            }
            webView = wv
            addView(wv, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
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
            loaded = true
            false
        }
    }

    private fun updateSvg() {
        if (!loaded) return
        val svg = ThemeConfig.resolveSvg(state, activeSessionCount, displayHint)
        if (svg == currentSvg) return
        currentSvg = svg

        if (webViewAvailable) {
            webView?.evaluateJavascript("setSvg('$svg')", null)
        } else {
            fallbackLabel?.text = "${state.name}\n$svg"
        }
    }

    fun setSvgImmediate(filename: String) {
        if (!loaded) return
        currentSvg = filename
        if (webViewAvailable) {
            webView?.evaluateJavascript("setSvgImmediate('$filename')", null)
        } else {
            fallbackLabel?.text = filename
        }
    }

    fun pause() {
        webView?.onPause()
    }

    fun resume() {
        webView?.onResume()
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onInterceptTouchEvent(ev: MotionEvent?): Boolean {
        if (hasOnClickListeners() || isLongClickable) return true
        return super.onInterceptTouchEvent(ev)
    }

    override fun onDetachedFromWindow() {
        webView?.destroy()
        super.onDetachedFromWindow()
    }
}
