package com.kongye.keyboard

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** 一条剪贴板记录：文字，或图片（image 是 filesDir/clips 下的文件名）；cat 是用户自定义的分类 */
@androidx.annotation.Keep
class Clip(var text: String, var pinned: Boolean, var time: Long, val image: String? = null, var cat: String? = null)

/**
 * 历史记录最多 500 条；置顶和自定义分类长期保存，不计入历史上限。
 * 只有键盘进程活着时才能收到复制事件，所以只记录用空叶键盘期间复制的内容。
 */
@androidx.annotation.Keep
object ClipStore {
    const val MAX = 500
    val FIXED_TABS = listOf("全部", "置顶", "文本", "图片")

    val items = ArrayList<Clip>()
    private var file: File? = null
    var dir: File? = null
        private set
    private var prefsCtx: Context? = null

    fun load(ctx: Context) {
        prefsCtx = ctx.applicationContext
        if (file != null) return
        val f = File(ctx.filesDir, "clipboard.json")
        file = f
        dir = File(ctx.filesDir, "clips").apply { mkdirs() }
        if (!android.util.AtomicFile(f).baseFile.exists() && !File(f.path + ".bak").exists()) return
        runCatching {
            val arr = JSONArray(android.util.AtomicFile(f).openRead().bufferedReader().use { it.readText() })
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                items.add(Clip(o.getString("t"), (o.optBoolean("p") || o.optBoolean("pinned") || o.optString("cat") == "置顶"), o.optLong("ts"), o.optString("img").ifEmpty { null }, o.optString("cat").ifEmpty { null }))
            }
        }
    }

    private val writer = java.util.concurrent.Executors.newSingleThreadExecutor()

    private fun save() {
        val f = file ?: return
        val arr = JSONArray()
        for (c in items) arr.put(JSONObject().put("t", c.text).put("p", c.pinned).put("ts", c.time).put("img", c.image ?: "").put("cat", c.cat ?: ""))
        val text = arr.toString()
        writer.execute {
            val atomic = android.util.AtomicFile(f)
            var out: java.io.FileOutputStream? = null
            try {
                out = atomic.startWrite()
                out.write(text.toByteArray(Charsets.UTF_8))
                atomic.finishWrite(out)
            } catch (_: Exception) { atomic.failWrite(out) }
        }
    }

    fun isSaved(c: Clip) = c.pinned || (!c.cat.isNullOrBlank() && c.cat !in FIXED_TABS)
    fun historyCount() = items.count { !isSaved(it) }

    private fun trim() {
        var excess = historyCount() - MAX
        while (excess-- > 0) {
            val i = items.indexOfLast { !isSaved(it) }
            if (i < 0) break
            removeFile(items.removeAt(i))
        }
    }

    fun add(text: String) {
        if (text.isBlank()) return
        val old = items.firstOrNull { it.image == null && it.text == text }
        if (old != null) items.remove(old)
        items.add(0, Clip(text, old?.pinned ?: false, System.currentTimeMillis(), cat = old?.cat))
        trim()
        save()
    }

    fun addImage(fileName: String) {
        items.add(0, Clip("[图片]", false, System.currentTimeMillis(), image = fileName))
        trim()
        save()
    }

    fun addCustom(text: String, category: String) {
        if (text.isBlank() || category !in customTabs()) return
        val old = items.firstOrNull { it.image == null && it.text == text }
        if (old != null) items.remove(old)
        items.add(0, Clip(text, old?.pinned ?: false, System.currentTimeMillis(), cat = category))
        trim()
        save()
    }

    fun imageFile(c: Clip): File? = c.image?.let { n -> dir?.let { File(it, n) } }

    private fun removeFile(c: Clip) { imageFile(c)?.delete() }

    fun remove(c: Clip) { val actual = stored(c) ?: return; items.remove(actual); removeFile(actual); save() }
    private fun stored(c: Clip) = items.firstOrNull { it === c }
        ?: items.firstOrNull { it.image == c.image && it.text == c.text }
    fun togglePin(c: Clip) {
        val actual = stored(c) ?: return
        actual.pinned = !actual.pinned
        c.pinned = actual.pinned
        trim()
        save()
    }
    fun update(c: Clip, text: String) {
        if (text.isBlank()) return
        val actual = stored(c) ?: return
        actual.text = text; actual.time = System.currentTimeMillis()
        c.text = actual.text; c.time = actual.time
        save()
    }
    fun setCategory(c: Clip, cat: String?) {
        val actual = stored(c) ?: return
        actual.cat = cat; c.cat = cat
        trim(); save()
    }
    fun clearUnpinned() { items.filter { !isSaved(it) }.forEach { removeFile(it) }; items.removeAll { !isSaved(it) }; save() }

    // —————— 自定义分类 ——————

    fun customTabs(): List<String> =
        prefsCtx?.let { Prefs.of(it).getString(Prefs.CLIP_CATS, "")!!.split('\u0001').filter { s -> s.isNotBlank() } }.orEmpty()

    fun tabs(): List<String> = FIXED_TABS + customTabs()

    fun addCategory(name: String) {
        val n = name.trim().take(8)
        val ctx = prefsCtx ?: return
        if (n.isEmpty() || n in tabs()) return
        Prefs.of(ctx).edit().putString(Prefs.CLIP_CATS, (customTabs() + n).joinToString("\u0001")).apply()
    }

    fun removeCategory(name: String) {
        val ctx = prefsCtx ?: return
        Prefs.of(ctx).edit().putString(Prefs.CLIP_CATS, customTabs().filter { it != name }.joinToString("\u0001")).apply()
        items.filter { it.cat == name }.forEach { it.cat = null }
        save()
    }

    private val LINK = Regex("""(https?://|www\.)\S+""", RegexOption.IGNORE_CASE)
    private val NUMBER = Regex("""^[\d\s+\-()（）.]{3,}$""")
    private val CODE = Regex("""(?<!\d)\d{4,8}(?!\d)""")

    fun kind(c: Clip): String = when {
        c.image != null -> "图片"
        else -> "文本"
    }

    /** 置顶的排在前面，其余按时间倒序 */
    fun list(tab: String, query: String): List<Clip> {
        val q = query.trim()
        return items.filter { c ->
            (q.isEmpty() || (c.image == null && c.text.contains(q, ignoreCase = true))) && when (tab) {
                "全部" -> true
                "置顶" -> c.pinned
                "文本", "图片" -> kind(c) == tab
                else -> c.cat == tab
            }
        }.sortedWith(compareByDescending<Clip> { it.pinned }.thenByDescending { it.time })
    }
}
