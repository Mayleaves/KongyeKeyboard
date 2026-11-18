package com.kongye.keyboard

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlin.math.roundToInt

/** Renders the bundled, offline walkthrough inside the settings page. */
class SmsSetupGuideView @SuppressLint("SetJavaScriptEnabled") constructor(context: Context) : WebView(context) {
    private val template = context.assets.open("sms-setup-guide.html").bufferedReader().use { it.readText() }
    private var palette = emptyList<Int>()

    init {
        settings.javaScriptEnabled = true
        settings.blockNetworkLoads = true
        settings.allowFileAccess = false
        settings.allowContentAccess = false
        settings.useWideViewPort = true
        webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest) = true
        }
        setBackgroundColor(Color.TRANSPARENT)
        isVerticalScrollBarEnabled = false
        isHorizontalScrollBarEnabled = false
        overScrollMode = OVER_SCROLL_NEVER
    }

    fun setPalette(ink: Int, muted: Int, tile: Int, accent: Int, card: Int) {
        val colors = listOf(ink, muted, tile, accent, card)
        if (colors == palette) return
        palette = colors
        var html = template
        for ((name, color) in listOf("INK", "MUTED", "TILE", "ACCENT", "CARD").zip(colors)) {
            html = html.replace("#$name", "#%06x".format(color and 0xffffff))
        }
        loadDataWithBaseURL("https://appassets.androidplatform.net/", html, "text/html", "UTF-8", null)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val height = (width * 536f / 704f).roundToInt()
        super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(resolveSize(height, heightMeasureSpec), MeasureSpec.EXACTLY))
    }
}
