package com.kongye.keyboard

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * 候选栏上面的拼音条（照截图）：像一个从键盘顶上伸出来的小标签，显示正在打的拼音。
 * 点拼音里的某个位置，光标就停在那里，接着打字或删除都在光标处改。
 */
class PinyinTab(context: Context, private val onCaret: (Int) -> Unit) : View(context) {
    private val d = resources.displayMetrics.density
    var skin: Skin = Skins.KONGYE
        set(v) { field = v; invalidate() }
    private var text = ""
    private var caret = -1
    private var offset = 0f
    private val tp = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 17 * d }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    private val padX get() = 14 * d

    /** caret：光标在 text 里的位置；-1 表示在最后（不画光标） */
    fun set(text: String, caret: Int) {
        if (text == this.text && caret == this.caret) return
        this.text = text
        this.caret = caret
        requestLayout()
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val maxW = if (MeasureSpec.getMode(widthMeasureSpec) == MeasureSpec.UNSPECIFIED) resources.displayMetrics.widthPixels
        else MeasureSpec.getSize(widthMeasureSpec)
        val want = (tp.measureText(text) + 2 * padX + 6 * d).toInt().coerceIn((56 * d).toInt(), max((56 * d).toInt(), maxW - (16 * d).toInt()))
        setMeasuredDimension(want, (34 * d).toInt())
    }

    override fun onDraw(c: Canvas) {
        val r = 12 * d
        path.reset()
        path.addRoundRect(RectF(0f, 0f, width.toFloat(), height + r), floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f), Path.Direction.CW)
        fill.color = withAlpha(skin.kbBg, max(225, android.graphics.Color.alpha(skin.kbBg)))
        c.drawPath(path, fill)
        val textW = tp.measureText(text)
        val avail = width - 2 * padX
        val ci = if (caret < 0) text.length else caret.coerceIn(0, text.length)
        val caretX = tp.measureText(text, 0, ci)
        offset = if (textW <= avail) 0f else (caretX - avail + 6 * d).coerceIn(0f, textW - avail)
        c.save()
        c.clipRect(padX - 2 * d, 0f, width - padX + 4 * d, height.toFloat())
        tp.color = skin.candFg
        val fm = tp.fontMetrics
        val base = height / 2f + 2 * d - (fm.ascent + fm.descent) / 2
        c.drawText(text, padX - offset, base, tp)
        if (caret >= 0) {
            fill.color = skin.candHi
            val x = padX - offset + caretX
            c.drawRect(x, 7 * d, x + 2 * d, height - 4 * d, fill)
        }
        c.restore()
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (e.actionMasked == MotionEvent.ACTION_UP) {
            val x = e.x - padX + offset
            var best = 0
            var bestD = Float.MAX_VALUE
            for (i in 0..text.length) {
                val dx = abs(tp.measureText(text, 0, i) - x)
                if (dx < bestD) { bestD = dx; best = i }
            }
            onCaret(best)
        }
        return true
    }
}

/** 键盘里用的滑条（不依赖 Compose） */
class SliderView(
    context: Context,
    private val skin: Skin,
    private var value: Float,
    private val min: Float,
    private val max: Float,
    private val onChange: (Float) -> Unit,
    private val onDone: (Float) -> Unit = {},
) : View(context) {
    private val d = resources.displayMetrics.density
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val thumb get() = 22 * d

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) =
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), (30 * d).toInt())

    private fun pos() = thumb / 2 + (value - min) / (max - min) * (width - thumb)

    override fun onDraw(c: Canvas) {
        val cy = height / 2f
        fill.color = withAlpha(skin.candFg, 40)
        c.drawRoundRect(RectF(thumb / 2, cy - 2 * d, width - thumb / 2, cy + 2 * d), 2 * d, 2 * d, fill)
        fill.color = skin.candHi
        c.drawRoundRect(RectF(thumb / 2, cy - 2 * d, pos(), cy + 2 * d), 2 * d, 2 * d, fill)
        c.drawCircle(pos(), cy, thumb / 2, fill)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                value = (min + (e.x - thumb / 2) / (width - thumb) * (max - min)).coerceIn(min, max)
                onChange(value)
                invalidate()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> onDone(value)
        }
        return true
    }
}

/** 按住连发的按钮（删除） */
@SuppressLint("ClickableViewAccessibility")
private fun View.repeatOnHold(action: () -> Unit) {
    val h = Handler(Looper.getMainLooper())
    val task = object : Runnable { override fun run() { action(); h.postDelayed(this, 60) } }
    setOnTouchListener { v, e ->
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { v.isPressed = true; action(); h.postDelayed(task, 420) }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { v.isPressed = false; h.removeCallbacks(task) }
        }
        true
    }
}

/**
 * 符号面板（照截图）：上面是键帽样式的符号网格（7 列，长的符号 3 列），可以上下滑；
 * 最下面一行：回到中文/英文、123、可横滑的分类（常用、最近、中、英……）、删除。输入后不退出。
 */
class SymbolBoard(
    context: Context,
    private val skin: Skin,
    cats: List<Pair<String, List<String>>>,
    cat: Int,
    backLabel: String,
    onCat: (Int) -> Unit,
    private val onSym: (String) -> Unit,
    onBack: () -> Unit,
    onNum: () -> Unit,
    onDel: () -> Unit,
) : LinearLayout(context) {
    private val d = resources.displayMetrics.density

    init {
        orientation = VERTICAL
        val items = cats.getOrNull(cat)?.second.orEmpty()
        val grid = SymGrid(items)
        addView(ScrollView(context).apply { isVerticalScrollBarEnabled = false; addView(grid) }, lp(MATCH, 0, 1f))
        val bottom = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding((5 * d).toInt(), (4 * d).toInt(), (5 * d).toInt(), (6 * d).toInt())
        }
        val keyH = (44 * d).toInt()
        fun modKey(label: String?, icon: Int, w: Float, f: () -> Unit, repeat: Boolean = false): View {
            val v: View = if (icon != 0) IconButton(context, icon, skin.modFg, 22f) {} else TextView(context).apply {
                text = label; textSize = 16f; gravity = Gravity.CENTER; setTextColor(skin.modFg)
            }
            v.background = roundBg(skin.modBg, skin.radius * d)
            if (repeat) v.repeatOnHold(f) else v.setOnClickListener { f() }
            bottom.addView(v, lp(0, keyH, w).apply { marginEnd = (5 * d).toInt() })
            return v
        }
        modKey(backLabel, 0, 1.25f, onBack)
        modKey("123", 0, 1.25f, onNum)
        val tabs = LinearLayout(context).apply { orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        cats.forEachIndexed { i, (name, _) ->
            val on = i == cat
            tabs.addView(object : TextView(context) {
                private val p = Paint(Paint.ANTI_ALIAS_FLAG)
                override fun onDraw(c: Canvas) {
                    super.onDraw(c)
                    if (!on) return
                    p.color = skin.candFg
                    val w = 22 * d
                    c.drawRoundRect(RectF(width / 2f - w / 2, height - 4 * d, width / 2f + w / 2, height - 2 * d), d, d, p)
                }
            }.apply {
                text = name
                textSize = 16f
                gravity = Gravity.CENTER
                setTextColor(if (on) skin.candFg else skin.barFg)
                typeface = if (on) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
                if (on) background = roundBg(withAlpha(skin.candFg, 22), skin.radius * d)
                setPadding((15 * d).toInt(), 0, (15 * d).toInt(), 0)
                setOnClickListener { onCat(i) }
            }, lp(WRAP, keyH))
        }
        val scroller = HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = false
            background = roundBg(skin.modBg, skin.radius * d)
            addView(tabs)
        }
        bottom.addView(scroller, lp(0, keyH, 4.6f).apply { marginEnd = (5 * d).toInt() })
        modKey(null, R.drawable.ic_del, 1.25f, onDel, repeat = true).let { (it.layoutParams as LayoutParams).marginEnd = 0 }
        addView(bottom, lp(MATCH, WRAP))
        // 选中的分类滚到看得见的地方
        scroller.post { tabs.getChildAt(cat)?.let { scroller.smoothScrollTo(max(0, it.left - (40 * d).toInt()), 0) } }
    }

    private inner class SymGrid(val items: List<String>) : View(context) {
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
        private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
        private val cols = if (items.any { it.length > 3 }) 3 else 7
        private val pad get() = 5 * d
        private val gap get() = 5 * d
        private val cellH get() = 44 * d
        private var pressed = -1
        private var downX = 0f
        private var downY = 0f
        private val slop = ViewConfiguration.get(context).scaledTouchSlop

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val rows = max(1, ceil(items.size / cols.toFloat()).toInt())
            setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), (2 * pad + rows * cellH + (rows - 1) * gap).toInt())
        }

        private fun cell(i: Int): RectF {
            val cw = (width - 2 * pad - (cols - 1) * gap) / cols
            val x = pad + (i % cols) * (cw + gap)
            val y = pad + (i / cols) * (cellH + gap)
            return RectF(x, y, x + cw, y + cellH)
        }

        override fun onDraw(c: Canvas) {
            if (items.isEmpty()) {
                text.textSize = 13.5f * d
                text.color = skin.barFg
                c.drawText("还没有用过的符号", width / 2f, 40 * d, text)
                return
            }
            val rad = skin.radius * d
            for ((i, s) in items.withIndex()) {
                val r = cell(i)
                if (skin.keyShadow != 0) {
                    fill.color = skin.keyShadow
                    c.drawRoundRect(RectF(r.left, r.top + d, r.right, r.bottom + d), rad, rad, fill)
                }
                fill.color = if (i == pressed) skin.keyDown else skin.keyBg
                c.drawRoundRect(r, rad, rad, fill)
                text.color = skin.keyFg
                text.textSize = (if (s.length > 6) 13 else if (s.length > 2) 16 else 21) * d
                val fm = text.fontMetrics
                c.drawText(s, r.centerX(), r.centerY() - (fm.ascent + fm.descent) / 2, text)
            }
        }

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(e: MotionEvent): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.x; downY = e.y
                    pressed = items.indices.firstOrNull { cell(it).contains(e.x, e.y) } ?: -1
                    invalidate()
                }
                MotionEvent.ACTION_MOVE -> if (pressed >= 0 && (abs(e.x - downX) > slop || abs(e.y - downY) > slop)) { pressed = -1; invalidate() }
                MotionEvent.ACTION_UP -> {
                    items.getOrNull(pressed)?.let(onSym)
                    pressed = -1
                    invalidate()
                }
                MotionEvent.ACTION_CANCEL -> { pressed = -1; invalidate() }
            }
            return true
        }
    }
}

/** 键盘调节时的临时设置；点“完成”才保存 */
class Adjust(var scale: Float, var opacity: Float, var padL: Int, var padR: Int, var padB: Int)

/**
 * 键盘调节：盖在键盘上。顶上的手柄上下拖改高度，左右两边的手柄横拖把键盘收窄；
 * 下面调透明度、底部留白、震动、按键音。
 */
class AdjustOverlay(
    context: Context,
    private val skin: Skin,
    private val a: Adjust,
    vib: Int,
    sound: Boolean,
    private val onLive: () -> Unit,
    onVib: (Int) -> Unit,
    onSound: (Boolean) -> Unit,
    onReset: () -> Unit,
    onDone: () -> Unit,
) : FrameLayout(context) {
    private val d = resources.displayMetrics.density
    private val label = TextView(context)

    init {
        setBackgroundColor(withAlpha(mix(skin.kbBg, if (skin.dark) android.graphics.Color.BLACK else android.graphics.Color.WHITE, 0.1f), 232))
        val col = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; setPadding((26 * d).toInt(), 0, (26 * d).toInt(), (4 * d).toInt()) }
        col.addView(Handle(true) { dy, _ ->
            a.scale = (a.scale + dy / (220 * d)).coerceIn(0.7f, 1.5f); onLive()
        }, lp(MATCH, (30 * d).toInt()))
        label.textSize = 12.5f
        label.gravity = Gravity.CENTER
        label.setTextColor(skin.barFg)
        col.addView(label, lp(MATCH, WRAP))
        fun row(title: String, v: View) = col.addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, (5 * d).toInt(), 0, 0)
            addView(TextView(context).apply { text = title; textSize = 14f; setTextColor(skin.candFg) }, lp((72 * d).toInt(), WRAP))
            addView(v, lp(0, WRAP, 1f))
        }, lp(MATCH, WRAP))
        row("透明度", SliderView(context, skin, a.opacity, 0.3f, 1f, { a.opacity = it; refresh(); onLive() }))
        row("底部留白", SliderView(context, skin, a.padB.toFloat(), 0f, 48f, { a.padB = it.toInt(); refresh(); onLive() }))
        col.addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, (6 * d).toInt(), 0, 0)
            addView(TextView(context).apply { text = "震动"; textSize = 14f; setTextColor(skin.candFg) }, lp((44 * d).toInt(), WRAP))
            addView(segmented(context, skin, listOf("关", "弱", "中", "强"), vib) { onVib(it) }, lp(WRAP, WRAP))
            addView(View(context), lp(0, 1, 1f))
            addView(TextView(context).apply { text = "按键音"; textSize = 14f; setTextColor(skin.candFg); setPadding(0, 0, (6 * d).toInt(), 0) }, lp(WRAP, WRAP))
            addView(ToggleView(context, skin, sound) { onSound(it) }, lp(WRAP, WRAP))
        }, lp(MATCH, WRAP))
        val btns = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, (8 * d).toInt(), 0, 0) }
        fun btn(t: String, strong: Boolean, f: () -> Unit) = btns.addView(TextView(context).apply {
            text = t; textSize = 14f; gravity = Gravity.CENTER
            setTextColor(if (strong) skin.accFg else skin.modFg)
            background = roundBg(if (strong) skin.accBg else skin.modBg, 18 * d)
            setPadding(0, (8 * d).toInt(), 0, (8 * d).toInt())
            setOnClickListener { f() }
        }, lp(0, WRAP, 1f).apply { marginEnd = (8 * d).toInt() })
        btn("恢复默认", false, onReset)
        btn("完成", true, onDone)
        col.addView(btns, lp(MATCH, WRAP))
        addView(col, LayoutParams(MATCH, WRAP))
        // 左右两边的手柄：往里拖收窄
        // 手柄贴着屏幕边：要把系统的返回手势让开（见 Handle.onLayout），否则一拖就把键盘收起来了
        addView(Handle(false) { _, dx -> a.padL = (a.padL + dx / d).toInt().coerceIn(0, 90); refresh(); onLive() },
            LayoutParams((26 * d).toInt(), (90 * d).toInt(), Gravity.START or Gravity.CENTER_VERTICAL))
        addView(Handle(false) { _, dx -> a.padR = (a.padR - dx / d).toInt().coerceIn(0, 90); refresh(); onLive() },
            LayoutParams((26 * d).toInt(), (90 * d).toInt(), Gravity.END or Gravity.CENTER_VERTICAL))
        refresh()
    }

    private fun refresh() {
        label.text = "高度 ${(a.scale * 100).toInt()}%　左 ${a.padL}　右 ${a.padR}　留白 ${a.padB}　透明度 ${(a.opacity * 100).toInt()}%"
    }

    /** 手柄：横的（顶上，拖动给 dy）或竖的（两边，拖动给 dx）；回调里的增量是相对上一次 */
    private inner class Handle(private val horizontal: Boolean, private val onDrag: (dy: Float, dx: Float) -> Unit) : View(context) {
        private val p = Paint(Paint.ANTI_ALIAS_FLAG)
        private var lx = 0f
        private var ly = 0f

        override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
            super.onLayout(changed, left, top, right, bottom)
            if (!horizontal && android.os.Build.VERSION.SDK_INT >= 29) systemGestureExclusionRects = listOf(android.graphics.Rect(0, 0, width, height))
        }

        override fun onDraw(c: Canvas) {
            p.color = skin.candHi
            if (horizontal) {
                val w = 64 * d
                c.drawRoundRect(RectF(width / 2f - w / 2, 11 * d, width / 2f + w / 2, 16 * d), 3 * d, 3 * d, p)
            } else {
                val h = 54 * d
                c.drawRoundRect(RectF(width / 2f - 2.5f * d, height / 2f - h / 2, width / 2f + 2.5f * d, height / 2f + h / 2), 3 * d, 3 * d, p)
            }
        }

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(e: MotionEvent): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { lx = e.rawX; ly = e.rawY; parent?.requestDisallowInterceptTouchEvent(true) }
                MotionEvent.ACTION_MOVE -> {
                    if (horizontal) onDrag(ly - e.rawY, 0f) else onDrag(0f, e.rawX - lx)
                    lx = e.rawX; ly = e.rawY
                    refresh()
                }
            }
            return true
        }
    }
}

/** 剪贴板搜索时，输入条下面一排实时的搜索结果（还是剪贴板卡片的样子），点一下上屏 */
class ClipSearchRow(context: Context, private val skin: Skin, list: List<Clip>, query: String, onPaste: (Clip) -> Unit) : HorizontalScrollView(context) {
    private val d = resources.displayMetrics.density

    init {
        isHorizontalScrollBarEnabled = false
        val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; setPadding((8 * d).toInt(), (4 * d).toInt(), (8 * d).toInt(), (2 * d).toInt()) }
        if (list.isEmpty()) row.addView(TextView(context).apply {
            text = if (query.isBlank()) "输入要找的内容，结果会显示在这里" else "没有找到“$query”"
            textSize = 13f
            setTextColor(skin.barFg)
            gravity = Gravity.CENTER_VERTICAL
            setPadding((8 * d).toInt(), 0, 0, 0)
        }, LinearLayout.LayoutParams(WRAP, (72 * d).toInt()))
        for (c in list.take(40)) {
            val card = FrameLayout(context).apply {
                background = roundBg(skin.keyBg, 12 * d)
                setPadding((9 * d).toInt(), (7 * d).toInt(), (9 * d).toInt(), (7 * d).toInt())
                setOnClickListener { onPaste(c) }
            }
            val img = ClipStore.imageFile(c)
            if (img != null && img.exists()) {
                BitmapFactory.decodeFile(img.path, BitmapFactory.Options().apply { inSampleSize = 6 })?.let { bmp ->
                    card.addView(ImageView(context).apply { setImageBitmap(bmp); scaleType = ImageView.ScaleType.CENTER_CROP; clipToOutline = true; background = roundBg(skin.modBg, 8 * d) })
                }
            } else card.addView(TextView(context).apply {
                text = c.text; textSize = 13f; setLines(3); ellipsize = TextUtils.TruncateAt.END; setTextColor(skin.keyFg)
            })
            row.addView(card, LinearLayout.LayoutParams((140 * d).toInt(), (72 * d).toInt()).apply { marginEnd = (6 * d).toInt() })
        }
        addView(row)
    }
}

/**
 * 语音面板：说话时实时出字，下面的波形跟着音量跳；可以选普通话或四川话；
 * 取消就丢掉，确定才上屏。
 */
class VoiceBoard(
    context: Context,
    private val skin: Skin,
    dialect: Int,
    needPermission: Boolean,
    onDialect: (Int) -> Unit,
    onMic: () -> Unit,
    onCancel: () -> Unit,
    onOk: () -> Unit,
    onPermission: () -> Unit,
) : LinearLayout(context) {
    private val d = resources.displayMetrics.density
    private val textView = TextView(context)
    private val statusView = TextView(context)
    private val wave = Wave()
    private val mic = MicButton()
    private val scroll = ScrollView(context)

    init {
        orientation = VERTICAL
        val head = LinearLayout(context).apply { orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding((12 * d).toInt(), (6 * d).toInt(), (10 * d).toInt(), 0) }
        statusView.textSize = 12.5f
        statusView.setTextColor(skin.barFg)
        head.addView(statusView, lp(0, WRAP, 1f))
        head.addView(segmented(context, skin, listOf("普通话", "四川话"), dialect) { onDialect(it) }, lp(WRAP, WRAP))
        addView(head, lp(MATCH, WRAP))
        textView.textSize = 19f
        textView.setTextColor(skin.candFg)
        textView.setPadding((16 * d).toInt(), (8 * d).toInt(), (16 * d).toInt(), (4 * d).toInt())
        scroll.isVerticalScrollBarEnabled = false
        scroll.addView(textView)
        addView(scroll, lp(MATCH, 0, 1f))
        addView(wave, lp(MATCH, (34 * d).toInt()))
        val row = LinearLayout(context).apply { orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding((16 * d).toInt(), (2 * d).toInt(), (16 * d).toInt(), (8 * d).toInt()) }
        fun btn(t: String, strong: Boolean, f: () -> Unit) = TextView(context).apply {
            text = t; textSize = 15f; gravity = Gravity.CENTER
            setTextColor(if (strong) skin.accFg else skin.modFg)
            background = roundBg(if (strong) skin.accBg else skin.modBg, 20 * d)
            setOnClickListener { f() }
        }
        row.addView(btn("取消", false, onCancel), lp(0, (42 * d).toInt(), 1f))
        mic.setOnClickListener { if (needPermission) onPermission() else onMic() }
        row.addView(mic, lp((64 * d).toInt(), (64 * d).toInt()).apply { marginStart = (18 * d).toInt(); marginEnd = (18 * d).toInt() })
        row.addView(btn("确定", true, onOk), lp(0, (42 * d).toInt(), 1f))
        addView(row, lp(MATCH, WRAP))
    }

    /** text：已经说的话（含正在识别的部分）；listening 时麦克风按钮高亮、波形跳动 */
    fun update(text: String, status: String, listening: Boolean) {
        textView.text = text
        textView.setTextColor(if (text.isEmpty()) skin.barFg else skin.candFg)
        if (text.isEmpty()) textView.text = status
        statusView.text = if (text.isEmpty()) "" else status
        mic.listening = listening
        wave.active = listening
        scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
    }

    /** 音量（RecognitionListener.onRmsChanged 的 dB，大约 -2 到 10） */
    fun level(rmsDb: Float) { wave.target = ((rmsDb + 2f) / 12f).coerceIn(0.05f, 1f) }

    private inner class MicButton : View(context) {
        private val icons = Icons(context)
        private val p = Paint(Paint.ANTI_ALIAS_FLAG)
        var listening = false
            set(v) { field = v; invalidate() }

        override fun onDraw(c: Canvas) {
            p.color = if (listening) skin.accBg else skin.modBg
            c.drawCircle(width / 2f, height / 2f, min(width, height) / 2f - 2 * d, p)
            icons.draw(c, R.drawable.ic_mic, width / 2f, height / 2f, 28f, if (listening) skin.accFg else skin.modFg)
        }
    }

    /** 声波：一排竖条，跟着音量起伏 */
    private inner class Wave : View(context) {
        private val p = Paint(Paint.ANTI_ALIAS_FLAG)
        var active = false
            set(v) { field = v; if (v) postInvalidateOnAnimation() else invalidate() }
        var target = 0.05f
        private var level = 0.05f
        private var t = 0f

        override fun onDraw(c: Canvas) {
            level += (target - level) * 0.25f
            if (!active) level = 0.05f
            t += 0.18f
            val n = 31
            val gap = 3 * d
            val bw = 3 * d
            val total = n * bw + (n - 1) * gap
            var x = (width - total) / 2
            p.color = if (active) skin.candHi else withAlpha(skin.barFg, 90)
            for (i in 0 until n) {
                val center = 1f - abs(i - n / 2) / (n / 2f)
                val wob = 0.55f + 0.45f * sin(t + i * 0.7f)
                val h = max(3 * d, height * 0.9f * level * (0.25f + 0.75f * center) * wob)
                c.drawRoundRect(RectF(x, height / 2f - h / 2, x + bw, height / 2f + h / 2), bw / 2, bw / 2, p)
                x += bw + gap
            }
            if (active) postInvalidateOnAnimation()
        }
    }
}
