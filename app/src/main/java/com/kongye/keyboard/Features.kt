package com.kongye.keyboard

import android.annotation.SuppressLint
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
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.nl.languageid.LanguageIdentification
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.google.mlkit.vision.digitalink.DigitalInkRecognition
import com.google.mlkit.vision.digitalink.DigitalInkRecognitionModel
import com.google.mlkit.vision.digitalink.DigitalInkRecognitionModelIdentifier
import com.google.mlkit.vision.digitalink.DigitalInkRecognizer
import com.google.mlkit.vision.digitalink.DigitalInkRecognizerOptions
import com.google.mlkit.vision.digitalink.RecognitionContext
import com.google.mlkit.vision.digitalink.WritingArea
import com.google.mlkit.vision.digitalink.Ink as DInk
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

/**
 * Optional digital-ink backup. Availability checks only load an installed model;
 * downloads are managed explicitly from offline resources.
 */
@androidx.annotation.Keep
object HandInk {
    private var recognizer: DigitalInkRecognizer? = null
    /** 0 未开始，1 下载中，2 可用，3 失败 */
    @Volatile var state = 0
        private set
    var status = ""
        private set
    private var generation=0
    fun release() {generation++;recognizer?.close();recognizer=null;state=0;status=""}

    fun prepare(onState: () -> Unit) {
        if (state == 1 || state == 2) return
        val token=++generation
        val id = runCatching { DigitalInkRecognitionModelIdentifier.fromLanguageTag("zh-Hani-CN") }.getOrNull()
        if (id == null) { state = 3; status = "没有中文手写模型，暂用文字识别"; onState(); return }
        val model = DigitalInkRecognitionModel.builder(id).build()
        val mgr = RemoteModelManager.getInstance()
        state = 1
        status = "正在准备手写模型…"
        fun ready() {
            if(token!=generation)return
            recognizer = runCatching { DigitalInkRecognition.getClient(DigitalInkRecognizerOptions.builder(model).build()) }.getOrNull()
            state = if (recognizer != null) 2 else 3
            status = if (recognizer != null) "" else "手写模型打不开，暂用文字识别"
            onState()
        }
        fun fail(e: Exception) {
            if(token!=generation)return
            Log.w("KongyeInk", "model failed", e)
            state = 3
            status = "备用手写模型不可用，可在离线资源中管理"
            onState()
        }
        runCatching {
            mgr.isModelDownloaded(model).addOnSuccessListener { ok ->
                if(token!=generation)return@addOnSuccessListener
                if (ok) ready() else {state=3;status="备用模型未下载，可在离线资源中下载";onState()}
            }.addOnFailureListener { fail(it) }
        }.onFailure { fail(it as? Exception ?: Exception(it)) }
    }

    /** strokes：每笔是 (x, y, 毫秒) 的点；返回 false 表示模型还不能用 */
    fun recognize(strokes: List<List<FloatArray>>, w: Float, h: Float, pre: String, cb: (List<String>) -> Unit): Boolean {
        val r = recognizer ?: return false
        return runCatching {
            val b = DInk.builder()
            for (st in strokes) {
                if (st.isEmpty()) continue
                val sb = DInk.Stroke.builder()
                for (pt in st) sb.addPoint(DInk.Point.create(pt[0], pt[1], pt[2].toLong()))
                b.addStroke(sb.build())
            }
            val ctx = RecognitionContext.builder().setPreContext(pre.takeLast(20)).setWritingArea(WritingArea(w, h)).build()
            r.recognize(b.build(), ctx)
                .addOnSuccessListener { res -> cb(res.candidates.map { it.text.trim() }.filter { it.isNotEmpty() }.distinct()) }
                .addOnFailureListener { cb(emptyList()) }
            true
        }.getOrDefault(false)
    }
}

/** Single-character pad; stroke undo/redo never changes committed text. */
@androidx.annotation.Keep
class HandwriteView(context: Context, private var skin: Skin, private val onRecognize: (HandwriteView) -> Unit, private val onAction: (String) -> Unit, private val onChanged: () -> Unit = {}) : View(context) {
    fun updateSkin(value:Skin) {skin=value;invalidate()}
    var fullScreen=false
        set(value) {field=value;contentDescription=if(value)"全屏手写，底部操作栏收回" else "手写区域，左上角展开全屏";invalidate()}
    /** 带时间的笔画点（给数字墨水识别用）：x、y、离第一笔的毫秒数 */
    private val points = ArrayList<ArrayList<FloatArray>>()
    private var t0 = 0L
    /** 模型下载等状态，写在格子下方 */
    var status = ""
        set(v) { field = v; invalidate() }
    private val d = resources.displayMetrics.density
    private val icons = Icons(context)
    private val strokes = ArrayList<Path>()
    private val undone = ArrayList<Pair<Path,ArrayList<FloatArray>>>()
    private var cur: Path? = null
    private val bounds = RectF()
    private val pen = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val handler = Handler(Looper.getMainLooper())
    private val recognize = Runnable { if (strokes.isNotEmpty()) onRecognize(this) }
    private var pressedSide = -1
    private var writingPointer = MotionEvent.INVALID_POINTER_ID
    private val side = listOf("undo", "redo", "del", "space", "enter", "fullscreen")

    private val sideW get() = 52 * d
    private fun actionButtonHeight(halfHeight:Int)=(halfHeight-36*d)/5
    /** Shared by the normal right rail and the full-screen action row. */
    fun actionButtonSize(halfHeight:Int=height)=android.graphics.PointF(sideW,actionButtonHeight(halfHeight).coerceAtLeast(1f))
    private val lastWritingBounds=RectF()
    private fun boardRect(w:Int=width,h:Int=height) = RectF(6*d,6*d,w-sideW-12*d,h-6*d)
    private fun sideRect(i: Int): RectF {
        val gap=6*d
        val board=boardRect()
        if(i==5)return RectF(board.left+4*d,board.top+4*d,board.left+44*d,board.top+44*d)
        val h=actionButtonHeight(height)
        val slot=when(i){2->0;0->1;1->2;3->3;else->4}
        val top=board.top+slot*(h+gap)
        return RectF(board.right+gap,top,width-gap,top+h)
    }

    private fun padRect(w:Int=width,h:Int=height) = if(fullScreen)RectF(6*d,6*d,w-6*d,h-6*d) else boardRect(w,h)
    fun writingBounds()=padRect()

    override fun onSizeChanged(w:Int,h:Int,oldw:Int,oldh:Int) {
        super.onSizeChanged(w,h,oldw,oldh)
        val from=RectF(lastWritingBounds);val to=padRect(w,h)
        lastWritingBounds.set(to)
        if(oldw<=0 || oldh<=0 || w<=0 || h<=0 || strokes.isEmpty())return
        cancelStroke()
        if(from.width()<=0 || from.height()<=0 || to.width()<=0 || to.height()<=0)return
        val scale=min(to.width()/from.width(),to.height()/from.height())
        val dx=to.centerX()-from.centerX()*scale;val dy=to.centerY()-from.centerY()*scale
        val matrix=android.graphics.Matrix().apply {setScale(scale,scale);postTranslate(dx,dy)}
        (strokes+undone.map {it.first}).forEach {it.transform(matrix)}
        (points+undone.map {it.second}).forEach {stroke->stroke.forEach {p->p[0]=p[0]*scale+dx;p[1]=p[1]*scale+dy}}
        changedStrokes()
    }

    fun clear() {
        strokes.clear(); points.clear(); undone.clear(); cur = null; pressedSide=-1; writingPointer=MotionEvent.INVALID_POINTER_ID
        bounds.setEmpty(); handler.removeCallbacks(recognize); invalidate()
        onChanged()
    }

    val canUndo get()=strokes.isNotEmpty() && cur==null
    val canRedo get()=undone.isNotEmpty() && cur==null
    fun undoStroke() {
        if(!canUndo)return
        undone.add(strokes.removeAt(strokes.lastIndex) to points.removeAt(points.lastIndex))
        changedStrokes()
    }
    fun redoStroke() {
        if(!canRedo)return
        val (path,stroke)=undone.removeAt(undone.lastIndex)
        strokes.add(path);points.add(stroke);changedStrokes()
    }
    private fun changedStrokes() {
        handler.removeCallbacks(recognize);bounds.setEmpty()
        points.flatten().forEach {if(bounds.isEmpty)bounds.set(it[0],it[1],it[0]+1,it[1]+1)else bounds.union(it[0],it[1])}
        onChanged();invalidate()
        if(strokes.isNotEmpty())handler.postDelayed(recognize,150)
    }

    val empty get() = strokes.isEmpty()

    override fun onDraw(c: Canvas) {
        val pr = padRect()
        val board=boardRect()
        fill.color=skin.keyBg
        if(!fullScreen)c.drawRoundRect(board,14*d,14*d,fill)
        // The entire white board is writable; tools occupy the separate right rail.
        fill.color = withAlpha(skin.keyFg, 18)
        if(!fullScreen) {
            c.drawRect(pr.centerX() - d / 2, pr.top + 10 * d, pr.centerX() + d / 2, pr.bottom - 10 * d, fill)
            c.drawRect(pr.left + 10 * d, pr.centerY() - d / 2, pr.right - 10 * d, pr.centerY() + d / 2, fill)
        }
        if (status.isNotEmpty()) {
            text.textSize = 11.5f * d
            text.color = skin.barFg
            c.drawText(status, pr.centerX(), pr.bottom - 10 * d, text)
        }
        pen.color = skin.keyFg
        pen.strokeWidth = 5 * d
        c.save();c.clipRect(pr)
        for (p in strokes) c.drawPath(p, pen)
        c.restore()
        if(fullScreen)return
        for ((i, s) in side.withIndex()) {
            val r = sideRect(i)
            if(i in 0..4) {
                fill.color = if (pressedSide == i) skin.keyDown else skin.modBg
                c.drawRoundRect(r,12*d,12*d,fill)
            }
            if(pressedSide==i && i==5) {
                fill.color=skin.keyDown
                c.drawRoundRect(r.left+4*d,r.top+4*d,r.right-4*d,r.bottom-4*d,8*d,8*d,fill)
            }
            val smallIcon=min(19f,min(r.width(),r.height())/d*.65f)
            when (s) {
                "undo" -> icons.draw(c,R.drawable.ic_undo,r.centerX(),r.centerY(),smallIcon,if(canUndo)skin.modFg else withAlpha(skin.modFg,65))
                "redo" -> icons.draw(c,R.drawable.ic_redo,r.centerX(),r.centerY(),smallIcon,if(canRedo)skin.modFg else withAlpha(skin.modFg,65))
                "del" -> icons.draw(c, R.drawable.ic_del, r.centerX(), r.centerY(), 22f, skin.modFg)
                "space" -> icons.draw(c, R.drawable.ic_space, r.centerX(), r.centerY(), 24f, skin.modFg)
                "enter" -> icons.draw(c, R.drawable.ic_enter, r.centerX(), r.centerY(), 22f, skin.modFg)
                "fullscreen" -> icons.draw(c,R.drawable.ic_hand_expand,r.centerX(),r.centerY(),14f,withAlpha(skin.barFg,if(skin.dark)150 else 100))
            }
        }
    }

    override fun onInitializeAccessibilityNodeInfo(info:android.view.accessibility.AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(info)
        info.addAction(if(fullScreen)android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction.ACTION_COLLAPSE
            else android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction.ACTION_EXPAND)
    }
    override fun performAccessibilityAction(action:Int,arguments:android.os.Bundle?):Boolean {
        if(action==(if(fullScreen)android.view.accessibility.AccessibilityNodeInfo.ACTION_COLLAPSE else android.view.accessibility.AccessibilityNodeInfo.ACTION_EXPAND)) {
            onAction("fullscreen");return true
        }
        return super.performAccessibilityAction(action,arguments)
    }

    private fun writePoint(x:Float,y:Float,time:Long) {
        if(cur==null) {
            if(!padRect().contains(x,y))return
            if(pressedSide>=0 && sideRect(pressedSide).contains(x,y))return
            pressedSide=-1
            handler.removeCallbacks(recognize)
            onChanged();undone.clear()
            cur=Path().also {it.moveTo(x,y);strokes.add(it)}
            if(points.isEmpty())t0=time
            points.add(arrayListOf())
        } else cur?.lineTo(x,y)
        points.last().add(floatArrayOf(x,y,(time-t0).toFloat()))
        if(bounds.isEmpty)bounds.set(x,y,x+1,y+1)else bounds.union(x,y)
        invalidate()
    }

    private fun cancelStroke() {
        if(cur!=null) {
            strokes.removeAt(strokes.lastIndex);points.removeAt(points.lastIndex)
            cur=null;changedStrokes()
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        if(e.actionMasked==MotionEvent.ACTION_DOWN) {
            cancelStroke()
            writingPointer=e.getPointerId(0)
            pressedSide=if(fullScreen)-1 else side.indices.firstOrNull {sideRect(it).contains(e.x,e.y)} ?: -1
            if(pressedSide<0)writePoint(e.x,e.y,e.eventTime)
            parent?.requestDisallowInterceptTouchEvent(true)
            invalidate()
            // Keep the gesture even if it began in a margin or the blank tool row.
            // Android will otherwise stop delivering moves when the finger enters the pad.
            return true
        }
        if(e.actionMasked==MotionEvent.ACTION_CANCEL) {
            cancelStroke();pressedSide=-1;writingPointer=MotionEvent.INVALID_POINTER_ID
            parent?.requestDisallowInterceptTouchEvent(false);invalidate();return true
        }
        val index=e.findPointerIndex(writingPointer)
        if(index<0)return true
        val finishing=e.actionMasked==MotionEvent.ACTION_UP ||
            e.actionMasked==MotionEvent.ACTION_POINTER_UP && e.getPointerId(e.actionIndex)==writingPointer
        if(e.actionMasked==MotionEvent.ACTION_MOVE || finishing) {
            // A tool tap remains a tap; dragging it into the pad cancels the tool action.
            for(h in 0 until e.historySize)writePoint(e.getHistoricalX(index,h),e.getHistoricalY(index,h),e.getHistoricalEventTime(h))
            writePoint(e.getX(index),e.getY(index),e.eventTime)
        }
        if(finishing) {
            if(cur!=null) {cur=null;handler.postDelayed(recognize,500)}
            else if(pressedSide>=0 && sideRect(pressedSide).contains(e.getX(index),e.getY(index))) {
                when(val id=side[pressedSide]) {"undo"->undoStroke();"redo"->redoStroke();else->onAction(id)}
            }
            pressedSide=-1;writingPointer=MotionEvent.INVALID_POINTER_ID
            parent?.requestDisallowInterceptTouchEvent(false);invalidate()
        }
        return true
    }

    fun inkStrokes(): List<List<FloatArray>> {
        val pad=padRect()
        return points.map {s->s.map {it.copyOf().also {p->p[0]-=pad.left;p[1]-=pad.top}}}
    }
    fun padWidth() = padRect().width()
    fun padHeight() = padRect().height()

    /** 把笔画画成白底黑字的小图，留边距，长边 256 像素（OCR 兜底用） */
    fun bitmap(): Bitmap? {
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

/** 翻译：中文译成英文，其他语言译成中文；用 ML Kit 的离线翻译模型（第一次用要下载模型） */
@androidx.annotation.Keep
object Translator {
    private var cancelActive: (() -> Unit)? = null

    fun cancel() { cancelActive?.invoke(); cancelActive = null }

    /** 可选的目标语言：ML Kit 代码和名字；"auto" 表示中文译英文、其他译中文 */
    val TARGETS = listOf(
        "auto" to "自动", TranslateLanguage.ENGLISH to "英文", TranslateLanguage.CHINESE to "中文", TranslateLanguage.JAPANESE to "日文",
        TranslateLanguage.KOREAN to "韩文",
    )

    fun run(context: Context, text: String, retryDownload: Boolean = false, target: String = "auto", onDirection: (String, String) -> Unit = { _, _ -> }, cb: (ok: Boolean, result: String, status: String) -> Unit) {
        cancel()
        var done = false
        val main = Handler(Looper.getMainLooper())
        var client: com.google.mlkit.nl.translate.Translator? = null
        var languageId: com.google.mlkit.nl.languageid.LanguageIdentifier? = null
        var stage = "识别语言"
        fun close() { client?.close(); client = null; languageId?.close(); languageId = null }
        val timeout = Runnable {
            if (done) return@Runnable
            done = true
            close()
            cancelActive = null
            Log.w("KongyeTranslate", "$stage timed out after 25s")
            cb(false, "", "${stage}超时，请重试。")
        }
        cancelActive = { done = true; main.removeCallbacks(timeout); close() }
        main.postDelayed(timeout, 25_000)

        fun finish(ok: Boolean, result: String, status: String) {
            if (done) return
            done = true
            main.removeCallbacks(timeout)
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
                languageId?.close();languageId=null;stage="检查翻译语言包"
                LanguageResources.translationReady(listOf(src,tgt),models@ {installed->
                    if(done)return@models
                    if(!installed) {
                        finish(false,"","请在离线资源中下载${listOf(src,tgt).distinct().filter {it!=TranslateLanguage.ENGLISH}.joinToString("、"){name(it)}}翻译语言包")
                        return@models
                    }
                    runCatching {
                        val translator=Translation.getClient(TranslatorOptions.Builder().setSourceLanguage(src).setTargetLanguage(tgt).build())
                        client=translator;stage="翻译"
                        cb(false,"","正在离线翻译…")
                        translator.translate(text).addOnSuccessListener {finish(true,it,"${name(src)} → ${name(tgt)}")}
                            .addOnFailureListener {failed(it)}
                    }.onFailure {failed(it as? Exception ?: Exception(it))}
                })
            }.onFailure {failed(it as? Exception ?: Exception(it))}
        }
        fun go(src: String) {
            if (done) return
            val tgt = if (target != "auto") target else if (src == TranslateLanguage.CHINESE) TranslateLanguage.ENGLISH else TranslateLanguage.CHINESE
            onDirection(src, tgt)
            if (src == tgt) finish(true, text, "原文已经是${name(tgt)}") else translate(src, tgt)
        }
        runCatching {
            if (text.any { it in '一'..'鿿' }) go(TranslateLanguage.CHINESE)
            else {
                cb(false, "", "正在识别语言…")
                val identifier = LanguageIdentification.getClient()
                languageId = identifier
                identifier.identifyLanguage(text).addOnSuccessListener { code ->
                    go(TranslateLanguage.fromLanguageTag(code) ?: TranslateLanguage.ENGLISH)
                }.addOnFailureListener { go(TranslateLanguage.ENGLISH) }
            }
        }.onFailure { failed(it as? Exception ?: Exception(it)) }
    }

    fun name(code: String) = TARGETS.firstOrNull { it.first == code }?.second ?: code
}
