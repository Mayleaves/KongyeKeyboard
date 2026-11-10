package com.kongye.keyboard

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.annotation.Keep
import org.json.JSONArray

/** Unmodified HanziLookup Rust/WASM engine and bundled character model.
 * Its computation runs in Android WebView's renderer, never in the IME draw
 * loop. The private runtime has no visible page, network, file or content access.
 * The bridge accepts only numeric stroke arrays and returns dictionary matches.
 */
object OpenHandwriting {
    private val main = Handler(Looper.getMainLooper())
    private var web: WebView? = null
    @Volatile var ready = false; private set
    @Volatile var failure: String? = null; private set
    private var loading = false
    private var nextId = 0
    private var generation = 0
    private val pending = HashMap<Int, (List<String>) -> Unit>()
    private val waiters = ArrayList<() -> Unit>()

    fun prepare(ctx: Context, callback: () -> Unit = {}) {
        if (Looper.myLooper() != Looper.getMainLooper()) { main.post { prepare(ctx, callback) }; return }
        if (ready) { callback(); return }
        waiters.add(callback)
        if (loading) return
        loading = true
        val token = ++generation
        Thread({
            try {
                val js = ctx.assets.open("handwriting/hanzi_lookup.js").bufferedReader().use { it.readText() }
                val bytes = ctx.assets.open("handwriting/hanzi_lookup_bg.wasm").use { it.readBytes() }
                val encoded = Base64.encodeToString(bytes, Base64.NO_WRAP)
                main.post { if(token==generation) create(ctx.applicationContext, js, encoded, token) }
            } catch (e: Exception) { main.post { if(token==generation) failed(e.message ?: "Handwriting module unavailable") } }
        }, "Kongye handwriting module").start()
        main.postDelayed({ if(token==generation && loading) failed("Handwriting initialization timeout") },8000)
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun create(ctx: Context, js: String, encoded: String, token: Int) {
        try {
            web = WebView(ctx).apply {
                settings.javaScriptEnabled = true
                settings.allowFileAccess = false; settings.allowContentAccess = false
                settings.blockNetworkLoads = true; settings.domStorageEnabled = false
                webViewClient = object : WebViewClient() {
                    @Deprecated("Legacy navigation callback")
                    override fun shouldOverrideUrlLoading(view: WebView?, url: String?) = true
                }
                addJavascriptInterface(Bridge(token), "KongyeInk")
                val html = """<!doctype html><meta charset="utf-8">
                    <meta http-equiv="Content-Security-Policy" content="default-src 'none'; script-src 'unsafe-inline' 'wasm-unsafe-eval'; connect-src 'none'">
                    <script>$js</script><script>
                    function recognize(id, strokes) {
                      try { KongyeInk.result(id, wasm_bindgen.lookup(strokes, 12)); }
                      catch(e) { KongyeInk.error(String(e)); }
                    }
                    wasm_bindgen(Uint8Array.from(atob('$encoded'), c => c.charCodeAt(0)))
                      .then(() => KongyeInk.loaded()).catch(e => KongyeInk.error(String(e)));
                    </script>""".trimIndent()
                loadDataWithBaseURL("https://keyboard.invalid/", html, "text/html", "utf-8", null)
            }
        } catch (e: Exception) { failed(e.message ?: "Handwriting runtime unavailable") }
    }

    private fun failed(message: String) {
        failure = message; ready = false; loading = false
        generation++
        web?.removeJavascriptInterface("KongyeInk"); web?.destroy(); web = null
        val callbacks = pending.values.toList(); pending.clear()
        callbacks.forEach { it(emptyList()) }
        val all = waiters.toList(); waiters.clear(); all.forEach { it() }
    }

    @Keep class Bridge(private val token: Int) {
        @JavascriptInterface fun loaded() { main.post {
            if(token!=generation) return@post
            ready = true; loading = false; failure = null
            val callbacks = waiters.toList(); waiters.clear(); callbacks.forEach { it() }
        } }
        @JavascriptInterface fun error(message: String) { main.post { if(token==generation) failed(message.take(240)) } }
        @JavascriptInterface fun result(id: Int, json: String) { main.post {
            if(token!=generation) return@post
            val callback = pending.remove(id) ?: return@post
            val matches = ArrayList<String>()
            try {
                val array = JSONArray(json)
                for (i in 0 until minOf(array.length(), 12)) {
                    val ch = array.getJSONObject(i).getString("hanzi")
                    if (ch.codePointCount(0, ch.length) == 1) matches.add(ch)
                }
            } catch (e: Exception) { failure = e.message }
            callback(matches.distinct())
        } }
    }

    fun recognize(strokes: List<List<FloatArray>>, callback: (List<String>) -> Unit): Boolean {
        check(Looper.myLooper() == Looper.getMainLooper())
        if (!ready || web == null) return false
        val points = strokes.flatten().filter { it.size >= 2 && it[0].isFinite() && it[1].isFinite() }
        if (points.isEmpty()) { callback(emptyList()); return true }
        val left = points.minOf { it[0] }; val top = points.minOf { it[1] }
        val size = maxOf(points.maxOf { it[0] }-left, points.maxOf { it[1] }-top, 1f)
        val array = JSONArray()
        for (stroke in strokes) {
            val line = JSONArray()
            for (point in stroke) {
                if (point.size < 2 || !point[0].isFinite() || !point[1].isFinite()) continue
                line.put(JSONArray().put((12+(point[0]-left)*230/size).toDouble()).put((12+(point[1]-top)*230/size).toDouble()))
            }
            if (line.length() > 0) array.put(line)
        }
        val id = ++nextId
        // Superseded strokes need no callbacks; the IME also checks revisions.
        pending.clear(); pending[id] = callback
        web!!.evaluateJavascript("recognize($id,$array)", null)
        main.postDelayed({ pending.remove(id)?.invoke(emptyList()) }, 3000)
        return true
    }

    fun release() {
        main.post {
            pending.clear(); waiters.clear(); ready = false; loading = false
            generation++
            web?.removeJavascriptInterface("KongyeInk"); web?.destroy(); web = null
        }
    }
}
