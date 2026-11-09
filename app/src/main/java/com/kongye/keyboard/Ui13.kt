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
    var expanded = false
        private set
    private val tp = Paint(Paint.ANTI_ALIAS_FLAG)
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val minusPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val path = Path()

    private val textSizePx get() = (if (expanded) 24f else 15.5f) * d
    private val padX get() = (if (expanded) 15f else 13f) * d
    private val topInset get() = if (expanded) 9 * d else 2 * d
    private val rightInset get() = if (expanded) 11 * d else 2 * d
    private val btnRadius get() = 10.5f * d

    fun reset() {
        expanded = false
        text = ""
        caret = -1
        requestLayout()
        invalidate()
    }

    /** caret：光标在 text 里的位置；-1 表示在最后（不画光标） */
    fun set(text: String, caret: Int) {
        if (text.isEmpty()) expanded = false
        if (text == this.text && caret == this.caret) return
        this.text = text
        this.caret = caret
        requestLayout()
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val maxW = if (MeasureSpec.getMode(widthMeasureSpec) == MeasureSpec.UNSPECIFIED) resources.displayMetrics.widthPixels
        else MeasureSpec.getSize(widthMeasureSpec)
        tp.textSize = textSizePx
        val minW = (if (expanded) 88 else 56) * d
        val want = (tp.measureText(text) + 2 * padX + rightInset + 6 * d).toInt()
            .coerceIn(minW.toInt(), max(minW.toInt(), maxW - (16 * d).toInt()))
        val h = ((if (expanded) 44 else 32) * d + topInset).toInt()
        setMeasuredDimension(want, h)
    }

    override fun onDraw(c: Canvas) {
        tp.textSize = textSizePx
        val r = (if (expanded) 13 else 11) * d
        val boxTop = topInset
        val boxRight = width - rightInset
        val boxBottom = height.toFloat()
        path.reset()
        path.addRoundRect(
            RectF(d, boxTop, boxRight, boxBottom + r),
            floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f),
            Path.Direction.CW,
        )
        // 实色底 + 很淡的投影，不描黑边
        c.save()
        c.translate(0f, -0.8f * d)
        fill.color = withAlpha(android.graphics.Color.BLACK, if (skin.dark) 70 else 16)
        c.drawPath(path, fill)
        c.restore()
        fill.color = if (skin.dark) skin.popBg else android.graphics.Color.WHITE
        c.drawPath(path, fill)
        if (expanded) {
            stroke.strokeWidth = 1f * d
            stroke.color = withAlpha(skin.candFg, if (skin.dark) 60 else 30)
            c.drawPath(path, stroke)
        }

        val textW = tp.measureText(text)
        val avail = (boxRight - d) - 2 * padX
        val ci = if (caret < 0) text.length else caret.coerceIn(0, text.length)
        val caretX = tp.measureText(text, 0, ci)
        offset = if (textW <= avail) 0f else (caretX - avail + 6 * d).coerceIn(0f, textW - avail)
        c.save()
        c.clipRect(d + padX - 2 * d, boxTop, boxRight - padX + 4 * d, boxBottom)
        tp.color = skin.candFg
        val fm = tp.fontMetrics
        val base = boxTop + (boxBottom - boxTop) / 2f + d - (fm.ascent + fm.descent) / 2
        c.drawText(text, d + padX - offset, base, tp)
        if (caret >= 0 || expanded) {
            fill.color = if (expanded) skin.candFg else skin.candHi
            val x = d + padX - offset + caretX
            val barW = (if (expanded) 1.8f else 1.6f) * d
            c.drawRect(x, boxTop + 6 * d, x + barW, boxBottom - 5 * d, fill)
        }
        c.restore()

        // 放大态右上角画缩小按钮 ⊖（照图1）
        if (expanded) {
            val bx = boxRight - 2 * d
            val by = boxTop + 3 * d
            fill.color = if (skin.dark) skin.modBg else android.graphics.Color.WHITE
            c.drawCircle(bx, by, btnRadius, fill)
            stroke.strokeWidth = 1.6f * d
            stroke.color = skin.candFg
            c.drawCircle(bx, by, btnRadius, stroke)
            minusPaint.strokeWidth = 2.2f * d
            minusPaint.color = skin.candFg
            c.drawLine(bx - 4.8f * d, by, bx + 4.8f * d, by, minusPaint)
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (e.actionMasked == MotionEvent.ACTION_UP) {
            val boxRight = width - rightInset
            val boxTop = topInset
            if (expanded) {
                val bx = boxRight - 2 * d
                val by = boxTop + 3 * d
                if (abs(e.x - bx) <= 16 * d && abs(e.y - by) <= 16 * d) {
                    expanded = false
                    requestLayout()
                    invalidate()
                    return true
                }
            } else {
                expanded = true
                requestLayout()
                invalidate()
            }
            tp.textSize = textSizePx
            val x = e.x - (d + padX) + offset
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
    private val catsProvider: () -> List<Pair<String, List<String>>>,
    startCat: Int,
    onCat: (Int) -> Unit,
    private val onSym: (String) -> Unit,
    onBack: () -> Unit,
    onNum: () -> Unit,
    onDel: () -> Unit,
) : LinearLayout(context) {
    private val d = resources.displayMetrics.density
    private var curCat = startCat
    private val grid: SymGrid
    private val gridScroller: ScrollView
    private val tabViews = ArrayList<TextView>()

    init {
        orientation = VERTICAL
        val initialCats = catsProvider()
        val items = initialCats.getOrNull(curCat)?.second.orEmpty()
        grid = SymGrid(items)
        gridScroller = ScrollView(context).apply { isVerticalScrollBarEnabled = false; addView(grid) }
        addView(gridScroller, lp(MATCH, 0, 1f))
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
        // 左下角返回键用箭头符号代替“中文/英文”文字
        modKey(null, R.drawable.ic_back, 1.25f, onBack)
        modKey("123", 0, 1.25f, onNum)
        val tabs = LinearLayout(context).apply { orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val scroller = HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = false
            background = roundBg(skin.modBg, skin.radius * d)
        }
        fun updateTabs(selected: Int, animateScroll: Boolean) {
            tabViews.forEachIndexed { idx, tv ->
                val on = idx == selected
                tv.setTextColor(if (on) skin.candFg else skin.barFg)
                tv.typeface = if (on) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
                tv.background = if (on) roundBg(withAlpha(skin.candFg, 22), skin.radius * d) else null
                tv.invalidate()
            }
            tabs.getChildAt(selected)?.let { child ->
                val targetX = (child.left - (scroller.width - child.width) / 2).coerceAtLeast(0)
                if (animateScroll) scroller.smoothScrollTo(targetX, 0) else scroller.scrollTo(targetX, 0)
            }
        }
        initialCats.forEachIndexed { i, (name, _) ->
            val tv = object : TextView(context) {
                private val p = Paint(Paint.ANTI_ALIAS_FLAG)
                override fun onDraw(c: Canvas) {
                    super.onDraw(c)
                    if (i != curCat) return
                    p.color = skin.candHi
                    val w = 20 * d
                    c.drawRoundRect(RectF(width / 2f - w / 2, height - 4 * d, width / 2f + w / 2, height - 2 * d), d, d, p)
                }
            }.apply {
                text = name
                textSize = 15.5f
                gravity = Gravity.CENTER
                setPadding((15 * d).toInt(), 0, (15 * d).toInt(), 0)
                setOnClickListener {
                    if (curCat == i) return@setOnClickListener
                    curCat = i
                    onCat(i)
                    grid.updateItems(catsProvider().getOrNull(i)?.second.orEmpty())
                    gridScroller.scrollTo(0, 0)
                    updateTabs(i, animateScroll = true)
                }
            }
            tabViews.add(tv)
            tabs.addView(tv, lp(WRAP, keyH))
        }
        scroller.addView(tabs)
        bottom.addView(scroller, lp(0, keyH, 4.6f).apply { marginEnd = (5 * d).toInt() })
        modKey(null, R.drawable.ic_del, 1.25f, onDel, repeat = true).let { (it.layoutParams as LayoutParams).marginEnd = 0 }
        addView(bottom, lp(MATCH, WRAP))
        scroller.post { updateTabs(curCat, animateScroll = false) }
    }

    private inner class SymGrid(var items: List<String>) : View(context) {
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
        private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
        private val cols get() = if (items.any { it.length > 3 }) 3 else 7
        private val pad get() = 5 * d
        private val gap get() = 5 * d
        private val cellH get() = 44 * d
        private var pressed = -1
        private var downX = 0f
        private var downY = 0f
        private val slop = ViewConfiguration.get(context).scaledTouchSlop

        fun updateItems(newItems: List<String>) {
            items = newItems
            pressed = -1
            requestLayout()
            invalidate()
        }

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
    private val card = LinearLayout(context)
    private val frameStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f * d
    }

    init {
        setWillNotDraw(false)
        // 背景完全通透，直接看清底下正在调节的键盘；拖动时中间控制卡片自动变淡
        setBackgroundColor(android.graphics.Color.TRANSPARENT)

        // 顶部高度拖动手柄
        addView(
            Handle(true, "上下拖动调高度") { dy, _ ->
                a.scale = (a.scale + dy / (220 * d)).coerceIn(0.7f, 1.5f)
                onLive()
            },
            LayoutParams(MATCH, (34 * d).toInt(), Gravity.TOP),
        )

        // 中间悬浮的紧凑调节卡（不遮挡四周按键，按住拖动时自动透明）
        card.orientation = LinearLayout.VERTICAL
        val cardBg = if (skin.dark) android.graphics.Color.parseColor("#E61E2026") else android.graphics.Color.parseColor("#EBFFFFFF")
        card.background = roundBg(cardBg, 18 * d)
        card.setPadding((14 * d).toInt(), (10 * d).toInt(), (14 * d).toInt(), (10 * d).toInt())

        label.textSize = 12f
        label.typeface = Typeface.DEFAULT_BOLD
        label.gravity = Gravity.CENTER
        label.setTextColor(skin.candHi)
        card.addView(label, lp(MATCH, WRAP))

        fun row(title: String, v: View) = card.addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, (3 * d).toInt(), 0, 0)
            addView(TextView(context).apply { text = title; textSize = 13f; setTextColor(skin.candFg) }, lp((62 * d).toInt(), WRAP))
            addView(v, lp(0, WRAP, 1f))
        }, lp(MATCH, WRAP))

        row("透明度", SliderView(context, skin, a.opacity, 0.3f, 1f, {
            setInteracting(true); a.opacity = it; refresh(); onLive()
        }, { setInteracting(false) }))
        row("底部留白", SliderView(context, skin, a.padB.toFloat(), 0f, 48f, {
            setInteracting(true); a.padB = it.toInt(); refresh(); onLive()
        }, { setInteracting(false) }))

        val btns = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, (6 * d).toInt(), 0, 0) }
        fun btn(t: String, strong: Boolean, f: () -> Unit) = btns.addView(TextView(context).apply {
            text = t; textSize = 13.5f; gravity = Gravity.CENTER
            typeface = if (strong) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
            setTextColor(if (strong) skin.accFg else skin.modFg)
            background = roundBg(if (strong) skin.accBg else skin.modBg, 16 * d)
            setPadding(0, (6 * d).toInt(), 0, (6 * d).toInt())
            setOnClickListener { f() }
        }, lp(0, WRAP, 1f).apply { if (!strong) marginEnd = (8 * d).toInt() })
        btn("恢复默认", false, onReset)
        btn("完成", true, onDone)
        card.addView(btns, lp(MATCH, WRAP))

        // 宽度和位置：比两边拖手柄好控制
        val screenDp = resources.displayMetrics.widthPixels / d
        val total0 = a.padL + a.padR
        var widthPct = (100f - total0 / screenDp * 100f).coerceIn(60f, 100f)
        var align = when { total0 == 0 -> 1; a.padL == 0 -> 0; a.padR == 0 -> 2; else -> 1 }
        fun applyWidth() {
            val total = ((100f - widthPct) / 100f * screenDp).toInt()
            a.padL = when (align) { 0 -> 0; 2 -> total; else -> total / 2 }
            a.padR = total - a.padL
            refresh(); onLive()
        }
        card.addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, (3 * d).toInt(), 0, 0)
            addView(TextView(context).apply { text = "宽度"; textSize = 13f; setTextColor(skin.candFg) }, lp((62 * d).toInt(), WRAP))
            addView(SliderView(context, skin, widthPct, 60f, 100f, { setInteracting(true); widthPct = it; applyWidth() }, { setInteracting(false) }), lp(0, WRAP, 1f))
            addView(segmented(context, skin, listOf("左", "中", "右"), align) { align = it; applyWidth() }, lp(WRAP, WRAP).apply { marginStart = (6 * d).toInt() })
        }, card.childCount - 1, lp(MATCH, WRAP))

        addView(card, LayoutParams(MATCH, WRAP, Gravity.BOTTOM).apply {
            leftMargin = (20 * d).toInt()
            rightMargin = (20 * d).toInt()
            bottomMargin = (8 * d).toInt()
        })
        refresh()
    }

    // 调节时整块盖住键盘：点到的地方不会落到下面的按键上
    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean = true

    private fun setInteracting(active: Boolean) {
        card.animate().alpha(if (active) 0.14f else 0.94f).setDuration(120).start()
    }

    override fun onDraw(c: Canvas) {
        super.onDraw(c)
        frameStroke.color = withAlpha(skin.candHi, 170)
        val inset = 2 * d
        c.drawRoundRect(RectF(inset, inset, width - inset, height - inset), 12 * d, 12 * d, frameStroke)
    }

    private fun refresh() {
        label.text = "高度 ${(a.scale * 100).toInt()}%  ·  左距 ${a.padL}  ·  右距 ${a.padR}  ·  留白 ${a.padB}  ·  透明度 ${(a.opacity * 100).toInt()}%"
    }

    /** 手柄：横的（顶上，拖动给 dy）或竖的（两边，拖动给 dx）；拖动时让卡片变透明看清键盘 */
    private inner class Handle(private val horizontal: Boolean, private val tip: String, private val onDrag: (dy: Float, dx: Float) -> Unit) : View(context) {
        private val p = Paint(Paint.ANTI_ALIAS_FLAG)
        private val tp = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER; textSize = 11 * d }
        private var lx = 0f
        private var ly = 0f

        override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
            super.onLayout(changed, left, top, right, bottom)
            if (!horizontal && android.os.Build.VERSION.SDK_INT >= 29) systemGestureExclusionRects = listOf(android.graphics.Rect(0, 0, width, height))
        }

        override fun onDraw(c: Canvas) {
            if (horizontal) {
                p.color = withAlpha(skin.keyBg, 220)
                val pillW = 148 * d
                c.drawRoundRect(RectF(width / 2f - pillW / 2, 4 * d, width / 2f + pillW / 2, 28 * d), 12 * d, 12 * d, p)
                p.color = skin.candHi
                val w = 42 * d
                c.drawRoundRect(RectF(width / 2f - w / 2, 8 * d, width / 2f + w / 2, 12 * d), 2 * d, 2 * d, p)
                if (tip.isNotEmpty()) {
                    tp.color = skin.candFg
                    c.drawText(tip, width / 2f, 24 * d, tp)
                }
            } else {
                p.color = withAlpha(skin.keyBg, 220)
                c.drawRoundRect(RectF(3 * d, height / 2f - 32 * d, width - 3 * d, height / 2f + 32 * d), 8 * d, 8 * d, p)
                p.color = skin.candHi
                val h = 42 * d
                c.drawRoundRect(RectF(width / 2f - 2.5f * d, height / 2f - h / 2, width / 2f + 2.5f * d, height / 2f + h / 2), 3 * d, 3 * d, p)
            }
        }

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(e: MotionEvent): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    lx = e.rawX; ly = e.rawY
                    parent?.requestDisallowInterceptTouchEvent(true)
                    setInteracting(true)
                }
                MotionEvent.ACTION_MOVE -> {
                    if (horizontal) onDrag(ly - e.rawY, 0f) else onDrag(0f, e.rawX - lx)
                    lx = e.rawX; ly = e.rawY
                    refresh()
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> setInteracting(false)
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
    onPack: () -> Unit = {},
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
        statusView.textSize = 13f
        statusView.typeface = Typeface.DEFAULT_BOLD
        statusView.setTextColor(skin.candHi)
        head.addView(statusView, lp(0, WRAP, 1f))
        head.addView(TextView(context).apply {
            text = "离线包"
            textSize = 12.5f
            setTextColor(skin.modFg)
            gravity = Gravity.CENTER
            background = roundBg(skin.modBg, 14 * d)
            setPadding((10 * d).toInt(), (5 * d).toInt(), (10 * d).toInt(), (5 * d).toInt())
            setOnClickListener { onPack() }
        }, lp(WRAP, WRAP).apply { marginEnd = (8 * d).toInt() })
        head.addView(segmented(context, skin, listOf("普通话", "四川话"), dialect) { onDialect(it) }, lp(WRAP, WRAP))
        addView(head, lp(MATCH, WRAP))
        textView.textSize = 20f
        textView.setTextColor(skin.candFg)
        textView.setPadding((16 * d).toInt(), (8 * d).toInt(), (16 * d).toInt(), (4 * d).toInt())
        scroll.isVerticalScrollBarEnabled = false
        scroll.addView(textView)
        addView(scroll, lp(MATCH, 0, 1f))
        addView(wave, lp(MATCH, (42 * d).toInt()))
        val row = LinearLayout(context).apply { orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding((16 * d).toInt(), (2 * d).toInt(), (16 * d).toInt(), (8 * d).toInt()) }
        fun btn(t: String, strong: Boolean, f: () -> Unit) = TextView(context).apply {
            text = t; textSize = 15f; gravity = Gravity.CENTER
            typeface = if (strong) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
            setTextColor(if (strong) skin.accFg else skin.modFg)
            background = roundBg(if (strong) skin.accBg else skin.modBg, 21 * d)
            setOnClickListener { f() }
        }
        row.addView(btn("取消", false, onCancel), lp(0, (42 * d).toInt(), 1f))
        mic.setOnClickListener { if (needPermission) onPermission() else onMic() }
        row.addView(mic, lp((76 * d).toInt(), (68 * d).toInt()).apply { marginStart = (14 * d).toInt(); marginEnd = (14 * d).toInt() })
        row.addView(btn("确定", true, onOk), lp(0, (42 * d).toInt(), 1f))
        addView(row, lp(MATCH, WRAP))
    }

    /** committed：已识别完的句子；partial：正在流式识别的字；listening 时麦克风脉冲光环 + 动态声波 */
    fun update(committed: String, partial: String, status: String, listening: Boolean) {
        val hasText = committed.isNotEmpty() || partial.isNotEmpty()
        statusView.text = if (listening) "● $status" else status
        statusView.setTextColor(if (listening) skin.candHi else skin.barFg)
        if (!hasText) {
            textView.text = if (listening) "正在倾听，请说话…" else status
            textView.setTextColor(if (listening) skin.candHi else skin.barFg)
        } else {
            val sb = android.text.SpannableStringBuilder(committed)
            if (partial.isNotEmpty()) {
                val start = sb.length
                sb.append(partial)
                sb.setSpan(android.text.style.ForegroundColorSpan(skin.candHi), start, sb.length, 0)
                sb.setSpan(android.text.style.UnderlineSpan(), start, sb.length, 0)
            }
            if (listening) {
                val start = sb.length
                sb.append(" ▍")
                sb.setSpan(android.text.style.ForegroundColorSpan(skin.candHi), start, sb.length, 0)
            }
            textView.text = sb
            textView.setTextColor(skin.candFg)
        }
        mic.listening = listening
        wave.active = listening
        scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
    }

    fun update(text: String, status: String, listening: Boolean) = update(text, "", status, listening)

    /** 音量（RecognitionListener.onRmsChanged 的 dB，大约 -2 到 10） */
    /** 安静的房间底噪大约在 4 dB 以下，说话一般 6～10 dB：低于门槛就当没声音，声波是平的 */
    fun level(rmsDb: Float) { wave.target = max(wave.target, ((rmsDb - 4.5f) / 5.5f).coerceIn(0f, 1f)) }

    private inner class MicButton : View(context) {
        private val icons = Icons(context)
        private val p = Paint(Paint.ANTI_ALIAS_FLAG)
        private var phase = 0f
        var listening = false
            set(v) {
                if (field != v) {
                    field = v
                    if (v) postInvalidateOnAnimation() else invalidate()
                }
            }

        override fun onDraw(c: Canvas) {
            val cx = width / 2f
            val cy = height / 2f
            val baseR = min(width, height) / 2f - 10 * d
            if (listening) {
                phase = (phase + 0.045f) % 1f
                val r1 = baseR + phase * 11 * d
                val a1 = ((1f - phase) * 115).toInt().coerceIn(0, 255)
                p.color = withAlpha(skin.accBg, a1)
                c.drawCircle(cx, cy, r1, p)

                val p2 = (phase + 0.5f) % 1f
                val r2 = baseR + p2 * 11 * d
                val a2 = ((1f - p2) * 115).toInt().coerceIn(0, 255)
                p.color = withAlpha(skin.accBg, a2)
                c.drawCircle(cx, cy, r2, p)
            }
            p.color = if (listening) skin.accBg else skin.modBg
            c.drawCircle(cx, cy, baseR, p)
            icons.draw(c, R.drawable.ic_mic, cx, cy, 28f, if (listening) skin.accFg else skin.modFg)
            if (listening) postInvalidateOnAnimation()
        }
    }

    /** 声波：一排竖条，只随说话的音量起伏；没声音时是平的 */
    private inner class Wave : View(context) {
        private val p = Paint(Paint.ANTI_ALIAS_FLAG)
        var active = false
            set(v) { field = v; if (!v) target = 0f; if (v) postInvalidateOnAnimation() else invalidate() }
        var target = 0f
        private var level = 0f
        private var t = 0f

        override fun onDraw(c: Canvas) {
            level += (target - level) * 0.3f
            target *= 0.9f
            if (level > 0.02f) t += 0.25f
            val n = 31
            val gap = 3.2f * d
            val bw = 3.4f * d
            val total = n * bw + (n - 1) * gap
            var x = (width - total) / 2
            val flat = 4 * d
            for (i in 0 until n) {
                val center = 1f - abs(i - n / 2) / (n / 2f)
                val wob = 0.55f + 0.45f * abs(sin(t + i * 0.55f))
                val h = flat + (height * 0.9f - flat) * level * (0.3f + 0.7f * center) * wob
                p.color = if (active && level > 0.05f) skin.accBg else withAlpha(skin.barFg, if (active) 140 else 80)
                c.drawRoundRect(RectF(x, height / 2f - h / 2, x + bw, height / 2f + h / 2), bw / 2, bw / 2, p)
                x += bw + gap
            }
            if (active || level > 0.02f) postInvalidateOnAnimation()
        }
    }
}
