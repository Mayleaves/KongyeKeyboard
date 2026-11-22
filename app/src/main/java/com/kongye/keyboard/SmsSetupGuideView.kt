package com.kongye.keyboard

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.os.Build
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlin.math.roundToInt

internal enum class SmsSetupGuide {
    GENERAL, XIAOMI;

    companion object {
        fun current(): SmsSetupGuide = if (listOf(Build.MANUFACTURER, Build.BRAND).any {
                it.equals("xiaomi", true) || it.equals("redmi", true) || it.equals("poco", true)
            }) XIAOMI else GENERAL
    }
}

/** Renders the bundled, offline walkthrough inside the settings page. */
class SmsSetupGuideView @SuppressLint("SetJavaScriptEnabled") constructor(context: Context) : WebView(context) {
    private val template = context.assets.open("sms-setup-guide.svg").bufferedReader().use { it.readText() }
    private var previousTheme: Pair<SmsSetupGuide, List<Int>>? = null

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

    internal fun showGuide(guide: SmsSetupGuide, ink: Int, muted: Int, tile: Int, accent: Int, card: Int) {
        val colors = listOf(ink, muted, tile, accent, card)
        val theme = guide to colors
        if (theme == previousTheme) return
        previousTheme = theme
        var svg = template.replace("GUIDE_VARIANT", if (guide == SmsSetupGuide.XIAOMI) "xiaomi" else "general")
        for ((name, color) in listOf("INK", "MUTED", "TILE", "ACCENT", "CARD").zip(colors)) {
            svg = svg.replace("#$name", "#%06x".format(color and 0xffffff))
        }
        val html = """<!doctype html><html lang="zh-CN"><meta charset="UTF-8">
            <meta name="viewport" content="width=device-width,initial-scale=1">
            <meta http-equiv="Content-Security-Policy" content="default-src 'none'; script-src 'unsafe-inline'; style-src 'unsafe-inline'">
            <style>html,body{margin:0;overflow:hidden}svg{display:block;width:100%;height:auto}</style>
            <body>$svg</body></html>"""
        loadDataWithBaseURL("https://appassets.androidplatform.net/", html, "text/html", "UTF-8", null)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val height = (width * 402f / 704f).roundToInt()
        super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(resolveSize(height, heightMeasureSpec), MeasureSpec.EXACTLY))
    }
}
