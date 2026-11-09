package com.kongye.keyboard

import android.annotation.SuppressLint
import android.app.DownloadManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.nl.languageid.LanguageIdentification
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions
import com.google.mlkit.nl.translate.TranslateRemoteModel
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import kotlin.math.max
import kotlin.math.min

/** 手写识别：把笔画画成图，用 ML Kit 自带的中文文字识别模型在手机上认（不联网） */
object Ink {
    private var reader: com.google.mlkit.vision.text.TextRecognizer? = null

    fun recognize(bmp: Bitmap, cb: (List<String>) -> Unit) {
        // 识别器初始化失败（比如组件没注册上）只当作没认出来，不能让整个键盘崩掉
        val r = reader ?: runCatching { TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build()) }
            .getOrNull()?.also { reader = it }
        if (r == null) { bmp.recycle(); cb(emptyList()); return }
        runCatching { r.process(InputImage.fromBitmap(bmp, 0)) }.getOrElse { bmp.recycle(); cb(emptyList()); return }
            .addOnSuccessListener { t ->
                val s = t.text.replace("\n", "").replace(" ", "")
                val out = LinkedHashSet<String>()
                if (s.isNotEmpty()) out.add(s)
                // 每个字单独也给一个候选，长的识别结果再给前两个字
                if (s.length > 1) { s.forEach { out.add(it.toString()) }; out.add(s.take(2)) }
                cb(out.toList())
            }
            .addOnFailureListener { cb(emptyList()) }
            .addOnCompleteListener { bmp.recycle() }
    }
}

/** 手写板：左边写字，右边一列重写、删除、空格、回车；停笔 0.5 秒自动识别 */
class HandwriteView(context: Context, private val skin: Skin, private val onRecognize: (Bitmap) -> Unit, private val onAction: (String) -> Unit, private val onChanged: () -> Unit = {}) : View(context) {
    private val d = resources.displayMetrics.density
    private val icons = Icons(context)
    private val strokes = ArrayList<Path>()
    private var cur: Path? = null
    private val bounds = RectF()
    private val pen = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val handler = Handler(Looper.getMainLooper())
    private val recognize = Runnable { bitmap()?.let(onRecognize) }
    private var pressedSide = -1
    private val side = listOf("rewrite" to "重写", "del" to "", "space" to "空格", "enter" to "")

    private val sideW get() = 64 * d
    private fun sideRect(i: Int): RectF {
        val pad = 6 * d
        val h = (height - 2 * pad - 3 * pad) / 4
        val x = width - sideW - pad
        return RectF(x, pad + i * (h + pad), width - pad, pad + i * (h + pad) + h)
    }

    private fun padRect() = RectF(6 * d, 6 * d, width - sideW - 12 * d, height - 6 * d)

    fun clear() {
        strokes.clear(); cur = null; bounds.setEmpty(); handler.removeCallbacks(recognize); invalidate()
        onChanged()
    }

    val empty get() = strokes.isEmpty()

    override fun onDraw(c: Canvas) {
        val pr = padRect()
        fill.color = skin.keyBg
        c.drawRoundRect(pr, 14 * d, 14 * d, fill)
        // 米字格淡线
        fill.color = withAlpha(skin.keyFg, 18)
        c.drawRect(pr.centerX() - d / 2, pr.top + 10 * d, pr.centerX() + d / 2, pr.bottom - 10 * d, fill)
        c.drawRect(pr.left + 10 * d, pr.centerY() - d / 2, pr.right - 10 * d, pr.centerY() + d / 2, fill)
        if (strokes.isEmpty()) {
            text.textSize = 15 * d
            text.color = skin.barFg
            c.drawText("在这里写字，停笔自动识别", pr.centerX(), pr.centerY() + 30 * d, text)
        }
        pen.color = skin.keyFg
        pen.strokeWidth = 5 * d
        for (p in strokes) c.drawPath(p, pen)
        for ((i, s) in side.withIndex()) {
            val r = sideRect(i)
            fill.color = if (pressedSide == i) skin.keyDown else skin.modBg
            c.drawRoundRect(r, skin.radius * d, skin.radius * d, fill)
            when (s.first) {
                "del" -> icons.draw(c, R.drawable.ic_del, r.centerX(), r.centerY(), 22f, skin.modFg)
                "enter" -> icons.draw(c, R.drawable.ic_enter, r.centerX(), r.centerY(), 22f, skin.modFg)
                else -> {
                    text.textSize = 15 * d
                    text.color = skin.modFg
                    val fm = text.fontMetrics
                    c.drawText(s.second, r.centerX(), r.centerY() - (fm.ascent + fm.descent) / 2, text)
                }
            }
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (e.actionMasked == MotionEvent.ACTION_DOWN && e.x > width - sideW - 8 * d) {
            pressedSide = (0 until 4).firstOrNull { sideRect(it).contains(e.x, e.y) } ?: -1
            invalidate()
            return true
        }
        if (pressedSide >= 0) {
            if (e.actionMasked == MotionEvent.ACTION_UP || e.actionMasked == MotionEvent.ACTION_CANCEL) {
                if (e.actionMasked == MotionEvent.ACTION_UP) {
                    val id = side[pressedSide].first
                    if (id == "rewrite") clear() else onAction(id)
                }
                pressedSide = -1
                invalidate()
            }
            return true
        }
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                handler.removeCallbacks(recognize)
                onChanged()
                cur = Path().also { it.moveTo(e.x, e.y); strokes.add(it) }
                if (bounds.isEmpty) bounds.set(e.x, e.y, e.x + 1, e.y + 1) else bounds.union(e.x, e.y)
                parent?.requestDisallowInterceptTouchEvent(true)
            }
            MotionEvent.ACTION_MOVE -> {
                for (h in 0 until e.historySize) { cur?.lineTo(e.getHistoricalX(h), e.getHistoricalY(h)); bounds.union(e.getHistoricalX(h), e.getHistoricalY(h)) }
                cur?.lineTo(e.x, e.y); bounds.union(e.x, e.y)
                invalidate()
            }
            MotionEvent.ACTION_UP -> { cur = null; handler.postDelayed(recognize, 500) }
            MotionEvent.ACTION_CANCEL -> { cur = null; handler.removeCallbacks(recognize); onChanged() }
        }
        return true
    }

    /** 把笔画画成白底黑字的小图，留边距，长边 256 像素 */
    private fun bitmap(): Bitmap? {
        if (strokes.isEmpty()) return null
        val margin = 24 * d
        val bw = bounds.width() + 2 * margin
        val bh = bounds.height() + 2 * margin
        val side = max(bw, bh)
        val scale = 256f / side
        val bmp = Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(Color.WHITE)
        c.scale(scale, scale)
        c.translate(-bounds.left + margin + (side - bw) / 2, -bounds.top + margin + (side - bh) / 2)
        val ink = Paint(pen).apply { color = Color.BLACK; strokeWidth = max(6 * d, side / 28) }
        for (p in strokes) c.drawPath(p, ink)
        return bmp
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        handler.removeCallbacks(recognize)
    }
}

/** 语音面板：大麦克风按钮，用系统的语音识别服务；没有可用服务时可以切到系统里的语音输入法 */
class VoicePanel(context: Context, skin: Skin, status: String, listening: Boolean, partial: String, needPermission: Boolean, onMic: () -> Unit, onPermission: () -> Unit, onSwitch: () -> Unit) : LinearLayout(context) {
    init {
        val d = resources.displayMetrics.density
        orientation = VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        addView(TextView(context).apply {
            text = partial.ifEmpty { status }
            textSize = if (partial.isNotEmpty()) 18f else 14f
            setTextColor(if (partial.isNotEmpty()) skin.candFg else skin.barFg)
            gravity = Gravity.CENTER
            setPadding((16 * d).toInt(), (14 * d).toInt(), (16 * d).toInt(), (8 * d).toInt())
        }, lp(MATCH, WRAP))
        val mic = object : View(context) {
            private val icons = Icons(context)
            private val p = Paint(Paint.ANTI_ALIAS_FLAG)
            override fun onDraw(c: Canvas) {
                p.color = if (listening) skin.accBg else skin.modBg
                c.drawCircle(width / 2f, height / 2f, min(width, height) / 2f - 2 * d, p)
                icons.draw(c, R.drawable.ic_mic, width / 2f, height / 2f, 34f, if (listening) skin.accFg else skin.modFg)
            }
        }
        mic.setOnClickListener { if (needPermission) onPermission() else onMic() }
        addView(mic, lp((84 * d).toInt(), (84 * d).toInt()).apply { topMargin = (4 * d).toInt() })
        addView(TextView(context).apply {
            text = if (needPermission) "需要麦克风权限：点这里去授权" else "改用系统里的语音输入法"
            textSize = 13f
            setTextColor(skin.candHi)
            setPadding(0, (12 * d).toInt(), 0, 0)
            setOnClickListener { if (needPermission) onPermission() else onSwitch() }
        }, lp(WRAP, WRAP))
    }
}

/** 翻译：中文译成英文，其他语言译成中文；用 ML Kit 的离线翻译模型（第一次用要下载模型） */
object Translator {
    private var cancelActive: (() -> Unit)? = null

    fun cancel() { cancelActive?.invoke(); cancelActive = null }

    fun run(context: Context, text: String, retryDownload: Boolean = false, cb: (ok: Boolean, result: String, status: String) -> Unit) {
        cancel()
        var done = false
        val main = Handler(Looper.getMainLooper())
        var client: com.google.mlkit.nl.translate.Translator? = null
        var languageId: com.google.mlkit.nl.languageid.LanguageIdentifier? = null
        var stage = "识别语言"
        var lastProgress: String? = null
        val poll = object : Runnable {
            override fun run() {
                if (done) return
                if (stage == "准备翻译模型") downloadStatus(context)?.let {
                    if (it != lastProgress) { lastProgress = it; cb(false, "", it) }
                }
                main.postDelayed(this, 1000)
            }
        }
        fun close() { client?.close(); client = null; languageId?.close(); languageId = null }
        val timeout = Runnable {
            if (done) return@Runnable
            done = true
            main.removeCallbacks(poll)
            close()
            cancelActive = null
            Log.w("KongyeTranslate", "$stage timed out after 25s")
            cb(false, "", "${stage}超时（25 秒）${lastProgress?.let { "：$it" } ?: ""}。请检查网络后重试；首次下载需让系统下载管理器也通过 VPN。")
        }
        cancelActive = { done = true; main.removeCallbacks(timeout); main.removeCallbacks(poll); close() }
        main.postDelayed(timeout, 25_000)
        main.postDelayed(poll, 1000)

        fun finish(ok: Boolean, result: String, status: String) {
            if (done) return
            done = true
            main.removeCallbacks(timeout)
            main.removeCallbacks(poll)
            close()
            cancelActive = null
            cb(ok, result, status)
        }
        fun failed(e: Exception) {
            if (done) return
            val code = (e as? com.google.mlkit.common.MlKitException)?.errorCode
            Log.w("KongyeTranslate", "$stage failed, code=$code", e)
            finish(false, "", "$stage 失败${code?.let { "（错误码 $it）" } ?: ""}：${e.message ?: "请检查网络后重试"}")
        }
        fun translate(src: String, tgt: String) {
            if (done) return
            runCatching {
                languageId?.close(); languageId = null
                val translator = Translation.getClient(TranslatorOptions.Builder().setSourceLanguage(src).setTargetLanguage(tgt).build())
                client = translator
                stage = "准备翻译模型"
                cb(false, "", "正在准备${name(src)} → ${name(tgt)}模型（首次需下载，约 30MB）…")
                fun download() {
                    if (done) return
                    Log.i("KongyeTranslate", "downloadModelIfNeeded requested: $src -> $tgt")
                    translator.downloadModelIfNeeded(DownloadConditions.Builder().build())
                    .addOnSuccessListener {
                        Log.i("KongyeTranslate", "Models ready: $src -> $tgt")
                        if (!done) {
                            stage = "翻译"
                            cb(false, "", "模型已就绪，正在离线翻译…")
                            runCatching {
                                translator.translate(text)
                                    .addOnSuccessListener { finish(true, it, "${name(src)} → ${name(tgt)}") }
                                    .addOnFailureListener { failed(it) }
                            }.onFailure { failed(it as? Exception ?: Exception(it)) }
                        }
                    }
                    .addOnFailureListener { failed(it) }
                }
                if (!retryDownload) download()
                else {
                    // SDK 会继续等待系统下载器里旧的退避任务；只在用户点重试时清理尚未安装的模型任务。
                    // 已安装的离线模型保留，正在正常下载的任务也保留。
                    val manager = RemoteModelManager.getInstance()
                    val models = listOf(src, tgt).distinct().filter { it != TranslateLanguage.ENGLISH }
                    fun prepare(index: Int) {
                        if (done) return
                        if (index >= models.size) { download(); return }
                        val model = TranslateRemoteModel.Builder(models[index]).build()
                        manager.isModelDownloaded(model).addOnSuccessListener { installed ->
                            if (done) return@addOnSuccessListener
                            if (installed || downloadRunning(context)) prepare(index + 1)
                            else {
                                Log.i("KongyeTranslate", "Resetting unfinished model download: ${models[index]}")
                                manager.deleteDownloadedModel(model)
                                    .addOnSuccessListener { prepare(index + 1) }
                                    .addOnFailureListener { failed(it) }
                            }
                        }.addOnFailureListener { failed(it) }
                    }
                    prepare(0)
                }
            }.onFailure { failed(it as? Exception ?: Exception(it)) }
        }
        runCatching {
            if (text.any { it in '一'..'鿿' }) translate(TranslateLanguage.CHINESE, TranslateLanguage.ENGLISH)
            else {
                cb(false, "", "正在识别语言…")
                val identifier = LanguageIdentification.getClient()
                languageId = identifier
                identifier.identifyLanguage(text).addOnSuccessListener { code ->
                    val src = TranslateLanguage.fromLanguageTag(code) ?: TranslateLanguage.ENGLISH
                    translate(if (src == TranslateLanguage.CHINESE) TranslateLanguage.ENGLISH else src, TranslateLanguage.CHINESE)
                }.addOnFailureListener { translate(TranslateLanguage.ENGLISH, TranslateLanguage.CHINESE) }
            }
        }.onFailure { failed(it as? Exception ?: Exception(it)) }
    }

    private fun downloadRunning(context: Context): Boolean = runCatching {
        context.getSystemService(DownloadManager::class.java).query(DownloadManager.Query()).use { c ->
            c.moveToFirst() && c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)) == DownloadManager.STATUS_RUNNING
        }
    }.getOrDefault(false)

    private fun downloadStatus(context: Context): String? = runCatching {
        context.getSystemService(DownloadManager::class.java).query(DownloadManager.Query()).use { c ->
            if (!c.moveToFirst()) return@use null
            val status = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
            val reason = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON))
            val bytes = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
            val total = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES))
            Log.d("KongyeTranslate", "DownloadManager status=$status reason=$reason bytes=$bytes total=$total")
            when (status) {
                DownloadManager.STATUS_RUNNING -> if (total > 0) "正在下载翻译模型：${bytes * 100 / total}%" else "正在下载翻译模型…"
                DownloadManager.STATUS_PENDING -> "翻译模型等待系统开始下载…"
                DownloadManager.STATUS_PAUSED -> when (reason) {
                    DownloadManager.PAUSED_WAITING_TO_RETRY -> "模型下载暂停，系统等待重试"
                    DownloadManager.PAUSED_WAITING_FOR_NETWORK -> "模型下载暂停，系统等待网络"
                    DownloadManager.PAUSED_QUEUED_FOR_WIFI -> "模型下载暂停，系统等待 Wi-Fi"
                    else -> "模型下载暂停（原因 $reason）"
                }
                DownloadManager.STATUS_FAILED -> "模型下载失败（系统错误 $reason）"
                else -> null
            }
        }
    }.getOrNull()

    private fun name(code: String) = when (code) {
        TranslateLanguage.CHINESE -> "中文"
        TranslateLanguage.ENGLISH -> "英文"
        TranslateLanguage.JAPANESE -> "日文"
        TranslateLanguage.KOREAN -> "韩文"
        else -> code
    }
}

/** 翻译结果面板 */
class TranslatePanel(context: Context, skin: Skin, source: String, result: String, status: String, onCommit: () -> Unit, onCopy: () -> Unit, onAgain: () -> Unit, onRetry: () -> Unit) : LinearLayout(context) {
    init {
        val d = resources.displayMetrics.density
        orientation = VERTICAL
        setPadding((10 * d).toInt(), (6 * d).toInt(), (10 * d).toInt(), (6 * d).toInt())
        val box = LinearLayout(context).apply { orientation = VERTICAL; background = roundBg(skin.keyBg, 12 * d); setPadding((12 * d).toInt(), (10 * d).toInt(), (12 * d).toInt(), (10 * d).toInt()) }
        box.addView(TextView(context).apply { text = source; textSize = 13f; setTextColor(skin.barFg); maxLines = 2 })
        box.addView(TextView(context).apply { text = result.ifEmpty { "…" }; textSize = 17f; setTextColor(skin.keyFg); setPadding(0, (6 * d).toInt(), 0, 0) })
        addView(ScrollView(context).apply { isVerticalScrollBarEnabled = false; addView(box) }, lp(MATCH, 0, 1f))
        addView(TextView(context).apply { text = status; textSize = 12f; setTextColor(skin.barFg); setPadding((4 * d).toInt(), (6 * d).toInt(), 0, (6 * d).toInt()) }, lp(MATCH, WRAP))
        val acts = LinearLayout(context).apply { orientation = HORIZONTAL }
        fun btn(t: String, strong: Boolean, f: () -> Unit) = acts.addView(TextView(context).apply {
            text = t; textSize = 14f; gravity = Gravity.CENTER
            setTextColor(if (strong) skin.accFg else skin.modFg)
            background = roundBg(if (strong) skin.accBg else skin.modBg, 16 * d)
            setPadding(0, (8 * d).toInt(), 0, (8 * d).toInt())
            setOnClickListener { f() }
        }, lp(0, WRAP, 1f).apply { marginEnd = (6 * d).toInt() })
        btn("再翻一句", false, onAgain)
        btn("重试", false, onRetry)
        btn("复制", false, onCopy)
        btn("上屏", true, onCommit)
        addView(acts, lp(MATCH, WRAP))
    }
}
