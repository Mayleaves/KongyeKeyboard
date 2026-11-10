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

/** 键盘里用的滑条（不依赖 Compose） */
class SliderView(
    context: Context,
    private val skin: Skin,
    private var value: Float,
    private val min: Float,
    private val max: Float,
    private val onChange: (Float) -> Unit,
    private val onDone: (Float) -> Unit = {},
    private val onStart: () -> Unit = {},
) : View(context) {
    private val d = resources.displayMetrics.density
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private var dragging = false
    private val padEnd get() = 10 * d

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) =
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), (28 * d).toInt())

    private fun pos() = (padEnd + (value - min) / (max - min) * (width - 2 * padEnd).coerceAtLeast(1f))
        .coerceIn(padEnd, (width - padEnd).coerceAtLeast(padEnd))

    override fun onDraw(c: Canvas) {
        val cy = height / 2f
        val th = 2.4f * d
        // 底轨
        fill.color = withAlpha(skin.candFg, if (skin.dark) 38 else 22)
        c.drawRoundRect(RectF(padEnd, cy - th, width - padEnd, cy + th), th, th, fill)
        // 已选进度轨
        val px = pos()
        fill.color = skin.candHi
        c.drawRoundRect(RectF(padEnd, cy - th, px, cy + th), th, th, fill)
        // 白芯带色环滑块 + 轻阴影
        val r = (if (dragging) 9.2f else 8.2f) * d
        fill.color = withAlpha(android.graphics.Color.BLACK, if (skin.dark) 85 else 28)
        c.drawCircle(px, cy + d, r + 0.8f * d, fill)
        fill.color = android.graphics.Color.WHITE
        c.drawCircle(px, cy, r, fill)
        ring.strokeWidth = 2.2f * d
        ring.color = skin.candHi
        c.drawCircle(px, cy, r - 1.1f * d, ring)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (!isEnabled) return false
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                dragging = true
                parent?.requestDisallowInterceptTouchEvent(true)
                onStart()
                value = (min + (e.x - padEnd) / (width - 2 * padEnd).coerceAtLeast(1f) * (max - min)).coerceIn(min, max)
                onChange(value)
                invalidate()
            }
            MotionEvent.ACTION_MOVE -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                value = (min + (e.x - padEnd) / (width - 2 * padEnd).coerceAtLeast(1f) * (max - min)).coerceIn(min, max)
                onChange(value)
                invalidate()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                dragging = false
                invalidate()
                onDone(value)
            }
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
    private val pager = CategoryPager(context)

    init {
        orientation = VERTICAL
        val initialCats = catsProvider()
        addView(pager, lp(MATCH, 0, 1f))
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
        val tabs=CategoryLabels(initialCats.map {it.first},curCat) {pager.select(it)}
        val scroller=HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled=false
            background=roundBg(skin.modBg,skin.radius*d)
        }
        fun centered(index:Int):Float {
            val center=tabs.centerOf(index)
            return (center-scroller.width/2f).coerceIn(0f,(tabs.width-scroller.width).coerceAtLeast(0).toFloat())
        }
        pager.onProgress={from,to,fraction ->
            tabs.progress(from,to,fraction)
            val target=centered(from)+(centered(to)-centered(from))*fraction
            scroller.scrollTo(kotlin.math.round(target).toInt(),0)
        }
        scroller.addView(tabs)
        bottom.addView(scroller, lp(0, keyH, 4.6f).apply { marginEnd = (5 * d).toInt() })
        modKey(null, R.drawable.ic_del, 1.25f, onDel, repeat = true).let { (it.layoutParams as LayoutParams).marginEnd = 0 }
        addView(bottom, lp(MATCH, WRAP))
        pager.pages(initialCats.size, curCat, { index ->
            ScrollView(context).apply { isVerticalScrollBarEnabled = false; addView(SymGrid(catsProvider().getOrNull(index)?.second.orEmpty())) }
        }) { index -> curCat=index;onCat(index);tabs.progress(index,index,0f) }
        scroller.post {scroller.scrollTo(kotlin.math.round(centered(curCat)).toInt(),0)}
    }

    /** Fixed label widths; the pill, underline and scrolling share the page's progress. */
    private inner class CategoryLabels(names:List<String>,selected:Int,onPick:(Int)->Unit):LinearLayout(context) {
        private val fill=Paint(Paint.ANTI_ALIAS_FLAG)
        private var from=selected
        private var to=selected
        private var fraction=0f
        init {
            orientation=HORIZONTAL;gravity=Gravity.CENTER_VERTICAL;setWillNotDraw(false)
            val measure=Paint(Paint.ANTI_ALIAS_FLAG).apply {textSize=15.5f*d;typeface=Typeface.DEFAULT_BOLD}
            names.forEachIndexed {i,name ->
                addView(TextView(context).apply {
                    text=name;textSize=15.5f;gravity=Gravity.CENTER
                    setTextColor(if(i==selected) skin.candFg else skin.barFg)
                    setOnClickListener {onPick(i)}
                },lp(kotlin.math.ceil(measure.measureText(name)+30*d).toInt(),(44*d).toInt()))
            }
        }
        fun centerOf(index:Int):Float=getChildAt(index)?.let {(it.left+it.right)/2f} ?: 0f
        fun progress(start:Int,end:Int,value:Float) {
            from=start;to=end;fraction=value
            for(i in 0 until childCount) {
                val weight=when {from==to -> if(i==from) 1f else 0f;i==from -> 1f-fraction;i==to -> fraction;else -> 0f}
                (getChildAt(i) as TextView).setTextColor(mix(skin.barFg,skin.candFg,weight))
            }
            invalidate()
        }
        override fun onDraw(c:Canvas) {
            super.onDraw(c)
            val start=getChildAt(from) ?: return
            val end=getChildAt(to) ?: return
            val left=start.left+(end.left-start.left)*fraction
            val right=start.right+(end.right-start.right)*fraction
            fill.color=withAlpha(skin.candFg,22)
            c.drawRoundRect(RectF(left,0f,right,height.toFloat()),skin.radius*d,skin.radius*d,fill)
            fill.color=skin.candHi
            val center=(left+right)/2f
            c.drawRoundRect(RectF(center-10*d,height-4*d,center+10*d,height-2*d),d,d,fill)
        }
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
data class Adjust(
    var scale: Float, var opacity: Float, var padL: Int, var padR: Int, var padB: Int,
    var split: Boolean = false, var floating: Boolean = false,
    var floatWidth: Float = .86f, var floatX: Float = 0f, var floatY: Float = 0f,
)

/**
 * 键盘调节：盖在键盘上。顶上的手柄上下拖改高度，左右两边的手柄横拖把键盘收窄；
 * 上下边调高度，左右边调宽度，四角同时缩放；悬浮时另有移动手柄。
 */
class AdjustOverlay(
    context: Context, private val skin: Skin, private val a: Adjust,
    floating: Boolean, private val onLive: () -> Unit,
    onFloating: () -> Unit, onMove: (Float, Float) -> Unit,
    onReset: () -> Unit, onDone: () -> Unit,
) : FrameLayout(context) {
    private val d = resources.displayMetrics.density
    private val accent = skin.candHi
    private val cardBg = if (skin.dark) android.graphics.Color.parseColor("#F224262C") else android.graphics.Color.parseColor("#F7FFFFFF")
    private val cardBorder = if (skin.dark) withAlpha(android.graphics.Color.WHITE, 28) else withAlpha(android.graphics.Color.BLACK, 18)
    private val titleFg = if (skin.dark) android.graphics.Color.parseColor("#F1F3F6") else android.graphics.Color.parseColor("#1F2329")
    private val subFg = if (skin.dark) android.graphics.Color.parseColor("#9AA1AC") else android.graphics.Color.parseColor("#6B7280")
    private val pillBg = if (skin.dark) android.graphics.Color.parseColor("#343741") else android.graphics.Color.parseColor("#EFF1F5")
    private val pillFg = if (skin.dark) android.graphics.Color.parseColor("#E5E7EB") else android.graphics.Color.parseColor("#374151")

    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1.2f * d }
    private val cornerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 2.4f * d; strokeCap = Paint.Cap.ROUND }
    private val handleFill = Paint(Paint.ANTI_ALIAS_FLAG)

    private val card = LinearLayout(context)
    private val sizeInfo = TextView(context)
    private val opacValue = TextView(context)
    private val blankValue = TextView(context)
    private val buttonViews = ArrayList<LinearLayout>()
    private var resizing = false

    init {
        setWillNotDraw(false)
        clipChildren = false
        clipToPadding = false

        // 悬浮毛玻璃感控制卡片：带圆角描边与柔和投影，拖动边框时自动变透明方便观察底下键盘
        card.orientation = LinearLayout.VERTICAL
        card.background = android.graphics.drawable.GradientDrawable().apply {
            setColor(cardBg)
            cornerRadius = 20 * d
            setStroke((1 * d).toInt().coerceAtLeast(1), cardBorder)
        }
        card.elevation = (if (skin.dark) 12 else 9) * d
        card.setPadding((14 * d).toInt(), (9 * d).toInt(), (14 * d).toInt(), (10 * d).toInt())

        // 1. 顶部尺寸状态栏（悬浮模式下按住此栏可直接拖动键盘位置）
        val header = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, (4 * d).toInt())
        }
        sizeInfo.textSize = 11.5f
        sizeInfo.setTextColor(subFg)
        header.addView(sizeInfo, lp(0, WRAP, 1f))
        val hintChip = TextView(context).apply {
            text = if (floating) "✥ 按住移位" else "拖动边框缩放"
            textSize = 11f
            setTextColor(if (floating) accent else withAlpha(subFg, 190))
            if (floating) {
                typeface = Typeface.DEFAULT_BOLD
                background = roundBg(withAlpha(accent, if (skin.dark) 40 else 22), 10 * d)
                setPadding((8 * d).toInt(), (2 * d).toInt(), (8 * d).toInt(), (2 * d).toInt())
            }
        }
        header.addView(hintChip, lp(WRAP, WRAP))
        if (floating) {
            var hx = 0f; var hy = 0f; var hStart = a.copy()
            @SuppressLint("ClickableViewAccessibility")
            header.setOnTouchListener { _, e ->
                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        hx = e.rawX; hy = e.rawY; hStart = a.copy()
                        parent?.requestDisallowInterceptTouchEvent(true)
                        fadeCard(0.22f)
                    }
                    MotionEvent.ACTION_MOVE -> onMove(hStart.floatX + (e.rawX - hx), hStart.floatY + (e.rawY - hy))
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> fadeCard(1f)
                }
                true
            }
        }
        card.addView(header, lp(MATCH, WRAP))

        // 2. 透明度滑条行
        card.addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(TextView(context).apply {
                text = "透明度"
                textSize = 13f
                setTextColor(titleFg)
            }, lp((58 * d).toInt(), WRAP))
            addView(SliderView(
                context, skin, 1f - a.opacity, 0f, .7f,
                onChange = { a.opacity = 1f - it; refresh(); onLive() },
                onDone = { fadeCard(1f) },
                onStart = { fadeCard(0.45f) },
            ), lp(0, (30 * d).toInt(), 1f))
            opacLabelStyle(opacValue)
            addView(opacValue, lp((42 * d).toInt(), WRAP))
        }, lp(MATCH, WRAP))

        // 3. 底部留白滑条行（悬浮模式下自动置灰）
        val blankSlider = SliderView(
            context, skin, a.padB.toFloat(), 0f, 48f,
            onChange = {
                val next = kotlin.math.round(it).toInt()
                if (a.padB != next) { a.padB = next; refresh(); onLive() }
            },
            onDone = { fadeCard(1f) },
            onStart = { if (!floating) fadeCard(0.28f) },
        ).apply { isEnabled = !floating }
        card.addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            alpha = if (floating) 0.42f else 1f
            addView(TextView(context).apply {
                text = "底部留白"
                textSize = 13f
                setTextColor(titleFg)
            }, lp((58 * d).toInt(), WRAP))
            addView(blankSlider, lp(0, (30 * d).toInt(), 1f))
            opacLabelStyle(blankValue)
            if (floating) blankValue.setTextColor(subFg)
            addView(blankValue, lp((42 * d).toInt(), WRAP))
        }, lp(MATCH, WRAP))

        // 4. 底部操作按钮行：重置 / 悬浮 / 分体 / 确认（与重置统一灰底）
        val buttons = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, (8 * d).toInt(), 0, 0)
        }
        fun styleButton(view: LinearLayout, kind: Int, active: Boolean) {
            val iconView = view.getChildAt(0) as ImageView
            val textView = view.getChildAt(1) as TextView
            when {
                kind == 2 -> {
                    view.background = roundBg(skin.accBg, 12 * d)
                    iconView.setColorFilter(skin.accFg)
                    textView.setTextColor(skin.accFg)
                    textView.typeface = Typeface.DEFAULT_BOLD
                }
                active -> {
                    view.background = android.graphics.drawable.GradientDrawable().apply {
                        setColor(withAlpha(accent, if (skin.dark) 48 else 24))
                        cornerRadius = 12 * d
                        setStroke((1.2f * d).toInt().coerceAtLeast(1), withAlpha(accent, 150))
                    }
                    iconView.setColorFilter(accent)
                    textView.setTextColor(accent)
                    textView.typeface = Typeface.DEFAULT_BOLD
                }
                else -> {
                    view.background = roundBg(pillBg, 12 * d)
                    iconView.setColorFilter(pillFg)
                    textView.setTextColor(pillFg)
                    textView.typeface = Typeface.DEFAULT
                }
            }
        }
        fun addActionButton(title: String, icon: Int, kind: Int, active: Boolean = false, isLast: Boolean = false, action: () -> Unit): LinearLayout {
            val b = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
                addView(ImageView(context).apply { setImageResource(icon) }, lp((15 * d).toInt(), (15 * d).toInt()))
                addView(TextView(context).apply {
                    text = title
                    textSize = 13f
                    maxLines = 1
                    gravity = Gravity.CENTER
                }, lp(WRAP, WRAP).apply { leftMargin = (4 * d).toInt() })
                setOnClickListener { action() }
            }
            styleButton(b, kind, active)
            buttonViews.add(b)
            buttons.addView(b, lp(0, (36 * d).toInt(), 1f).apply {
                if (!isLast) marginEnd = (6 * d).toInt()
            })
            return b
        }
        addActionButton("重置", R.drawable.ic_reset, kind = 0, action = onReset)
        addActionButton("悬浮", R.drawable.ic_float, kind = 1, active = floating, action = onFloating)
        lateinit var splitBtn: LinearLayout
        splitBtn = addActionButton("分体", R.drawable.ic_split_layout, kind = 1, active = a.split) {
            a.split = !a.split
            onLive()
            styleButton(splitBtn, kind = 1, active = a.split)
        }
        addActionButton("确认", R.drawable.ic_done, kind = 0, isLast = true, action = onDone)
        card.addView(buttons, lp(MATCH, WRAP))

        addView(card, LayoutParams(MATCH, WRAP, Gravity.CENTER).apply {
            leftMargin = (22 * d).toInt()
            rightMargin = (22 * d).toInt()
        })

        // 四边与四角缩放手柄（悬浮模式下顶部留给拖动条区域同样支持移位）
        addView(Handle { _, dy, start -> resize(start, 0f, dy, vertical = -1) },
            LayoutParams((90 * d).toInt(), (24 * d).toInt(), Gravity.TOP or Gravity.CENTER_HORIZONTAL))
        if (floating) {
            addView(Handle { dx, dy, start ->
                onMove(start.floatX + dx, start.floatY + dy)
            }, LayoutParams((140 * d).toInt(), (28 * d).toInt(), Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { topMargin = (24 * d).toInt() })
        }
        addView(Handle { dx, _, start -> resize(start, dx, 0f, horizontal = -1) },
            LayoutParams((22 * d).toInt(), MATCH, Gravity.START).apply { topMargin = (28 * d).toInt(); bottomMargin = (28 * d).toInt() })
        addView(Handle { dx, _, start -> resize(start, dx, 0f, horizontal = 1) },
            LayoutParams((22 * d).toInt(), MATCH, Gravity.END).apply { topMargin = (28 * d).toInt(); bottomMargin = (28 * d).toInt() })
        addView(Handle { _, dy, start -> resize(start, 0f, dy, vertical = 1) },
            LayoutParams((90 * d).toInt(), (24 * d).toInt(), Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL))
        for (right in listOf(false, true)) for (bottom in listOf(false, true)) addView(Handle { dx, dy, start ->
            resize(start, dx, dy, if (right) 1 else -1, if (bottom) 1 else -1)
        }, LayoutParams((28 * d).toInt(), (28 * d).toInt(), (if (right) Gravity.END else Gravity.START) or (if (bottom) Gravity.BOTTOM else Gravity.TOP)))
        refresh()
    }

    private fun opacLabelStyle(tv: TextView) {
        tv.setTextColor(accent)
        tv.textSize = 12.5f
        tv.typeface = Typeface.DEFAULT_BOLD
        tv.gravity = Gravity.END or Gravity.CENTER_VERTICAL
    }

    private fun fadeCard(targetAlpha: Float) {
        card.animate().alpha(targetAlpha).setDuration(if (targetAlpha < 1f) 110L else 160L).start()
    }

    private fun setResizing(active: Boolean) {
        if (resizing == active) return
        resizing = active
        fadeCard(if (active) 0.12f else 1f)
        invalidate()
    }

    private fun resize(start: Adjust, dx: Float, dy: Float, horizontal: Int = 0, vertical: Int = 0) {
        val screen = resources.displayMetrics.widthPixels.toFloat()
        if (vertical != 0) {
            val scalePixels = 4 * skin.keyH * d
            a.scale = (start.scale + vertical * dy / scalePixels).coerceIn(.7f, 1.5f)
            if (a.floating && vertical < 0) a.floatY = start.floatY - (a.scale - start.scale) * scalePixels
        }
        if (horizontal != 0) {
            if (a.floating) {
                a.floatWidth = (start.floatWidth + horizontal * dx / screen).coerceIn(.55f, 1f)
                if (horizontal < 0) a.floatX = start.floatX + (start.floatWidth - a.floatWidth) * screen
            } else {
                val maxTotalPad = (screen / d * .45f).toInt()
                if (horizontal < 0) a.padL = kotlin.math.round(start.padL + dx / d).toInt().coerceIn(0, (maxTotalPad - start.padR).coerceAtLeast(0))
                else a.padR = kotlin.math.round(start.padR - dx / d).toInt().coerceIn(0, (maxTotalPad - start.padL).coerceAtLeast(0))
            }
        }
        onLive()
    }

    private fun refresh() {
        val screenDp = (resources.displayMetrics.widthPixels / d).coerceAtLeast(1f)
        val widthPct = if (a.floating) kotlin.math.round(a.floatWidth * 100).toInt()
        else kotlin.math.round((screenDp - a.padL - a.padR) / screenDp * 100).toInt().coerceIn(55, 100)
        sizeInfo.text = "高度 ${kotlin.math.round(a.scale * 100).toInt()}%  ·  宽度 $widthPct%"
        opacValue.text = "${kotlin.math.round((1 - a.opacity) * 100).toInt()}%"
        blankValue.text = if (a.floating) "悬浮" else "${a.padB} dp"
        invalidate()
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent) = true

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        // 键盘被缩到极窄时，自动收紧按钮文字以保证不裁切
        val compact = w < 275 * d
        val lpCard = card.layoutParams as LayoutParams
        val wantMargin = ((if (compact) 14 else 22) * d).toInt()
        if (lpCard.leftMargin != wantMargin) {
            lpCard.leftMargin = wantMargin
            lpCard.rightMargin = wantMargin
            card.post { card.layoutParams = lpCard }
        }
        buttonViews.forEach { b ->
            (b.getChildAt(1) as? TextView)?.visibility = if (w < 235 * d) View.GONE else View.VISIBLE
        }
        if (android.os.Build.VERSION.SDK_INT >= 29) {
            val edge = (24 * d).toInt(); val half = (48 * d).toInt()
            systemGestureExclusionRects = listOf(
                android.graphics.Rect(0, (h / 2 - half).coerceAtLeast(0), edge, (h / 2 + half).coerceAtMost(h)),
                android.graphics.Rect((w - edge).coerceAtLeast(0), (h / 2 - half).coerceAtLeast(0), w, (h / 2 + half).coerceAtMost(h)),
            )
        }
    }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val inset = 2.5f * d
        val r = 14 * d

        // 1. 柔和细线外框，清晰标示当前键盘边界而不遮挡工具栏与底栏图标
        borderPaint.color = withAlpha(accent, if (resizing) 190 else if (skin.dark) 105 else 80)
        c.drawRoundRect(RectF(inset, inset, w - inset, h - inset), r, r, borderPaint)

        // 2. 四角小巧精致的圆弧角标（总跨度仅 16dp，完全避开角落图标）
        cornerPaint.color = withAlpha(accent, if (resizing) 255 else 215)
        val path = Path()
        val arm = 16 * d
        for (right in listOf(false, true)) for (bottom in listOf(false, true)) {
            val x = if (right) w - inset else inset
            val y = if (bottom) h - inset else inset
            val sx = if (right) -1 else 1
            val sy = if (bottom) -1 else 1
            path.moveTo(x + sx * arm, y)
            path.lineTo(x + sx * r, y)
            path.quadTo(x, y, x, y + sy * r)
            path.lineTo(x, y + sy * arm)
        }
        c.drawPath(path, cornerPaint)

        // 3. 四边中点胶囊手柄
        handleFill.color = withAlpha(accent, if (resizing) 255 else 210)
        val pillLen = 28 * d
        val pillThick = 3.6f * d
        val pr = pillThick / 2
        c.drawRoundRect(RectF(w / 2 - pillLen / 2, inset - pr, w / 2 + pillLen / 2, inset + pr), pr, pr, handleFill)
        c.drawRoundRect(RectF(w / 2 - pillLen / 2, h - inset - pr, w / 2 + pillLen / 2, h - inset + pr), pr, pr, handleFill)
        c.drawRoundRect(RectF(inset - pr, h / 2 - pillLen / 2, inset + pr, h / 2 + pillLen / 2), pr, pr, handleFill)
        c.drawRoundRect(RectF(w - inset - pr, h / 2 - pillLen / 2, w - inset + pr, h / 2 + pillLen / 2), pr, pr, handleFill)
    }

    private inner class Handle(private val drag: (Float, Float, Adjust) -> Unit) : View(context) {
        private var x0 = 0f
        private var y0 = 0f
        private var start = a.copy()

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(e: MotionEvent): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    x0 = e.rawX; y0 = e.rawY; start = a.copy()
                    parent?.requestDisallowInterceptTouchEvent(true)
                    setResizing(true)
                }
                MotionEvent.ACTION_MOVE -> {
                    drag(e.rawX - x0, e.rawY - y0, start)
                    refresh()
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> setResizing(false)
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
    private val statusView = TextView(context)
    private val wave = Wave()
    private val mic = MicButton()

    init {
        orientation = VERTICAL
        val head = LinearLayout(context).apply { orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding((12 * d).toInt(), (6 * d).toInt(), (10 * d).toInt(), 0) }
        statusView.textSize = 13f
        statusView.typeface = Typeface.DEFAULT_BOLD
        statusView.setTextColor(skin.candHi)
        head.addView(statusView, lp(0, WRAP, 1f))
        head.addView(segmented(context, skin, listOf("普通话", "四川话"), dialect) { onDialect(it) }, lp(WRAP, WRAP))
        addView(head, lp(MATCH, WRAP))
        addView(wave, lp(MATCH, 0, 1f))
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

    /** committed 为已识别句子，partial 为临时结果；麦克风保持静态，只有声波随音量变化。 */
    fun update(committed: String, partial: String, status: String, listening: Boolean) {
        statusView.text = status
        statusView.setTextColor(if (listening) skin.candHi else skin.barFg)
        mic.listening = listening
        wave.active = listening
    }

    fun update(text: String, status: String, listening: Boolean) = update(text, "", status, listening)

    /** 系统与离线录音统一到约 0–10 的音量；底噪不抬高声波。 */
    fun level(rmsDb: Float) {
        wave.sample(((rmsDb - 1.2f) / 7f).coerceIn(0f, 1f))
    }

    fun spectrum(values: FloatArray) { wave.bands(values) }

    private inner class MicButton : View(context) {
        private val icons = Icons(context)
        private val p = Paint(Paint.ANTI_ALIAS_FLAG)
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

            p.color = if (listening) skin.accBg else skin.modBg
            c.drawCircle(cx, cy, baseR, p)
            icons.draw(c, R.drawable.ic_mic, cx, cy, 28f, if (listening) skin.accFg else skin.modFg)

        }
    }

    /** 声波：一排竖条，只随说话的音量起伏；没声音时是平的 */
    private inner class Wave : View(context) {
        private val p = Paint(Paint.ANTI_ALIAS_FLAG)
        private val levels=FloatArray(17)
        private val displayed=FloatArray(17)
        private var lastSpectrum=0L
        var active = false
            set(v) {
                field=v
                if(!v) levels.fill(0f)
                postInvalidateOnAnimation()
            }

        fun bands(values: FloatArray) {
            if(!active) return
            lastSpectrum=android.os.SystemClock.uptimeMillis()
            val mirrored = VoiceSpectrum.mirrored(values)
            mirrored.copyInto(levels)
            postInvalidateOnAnimation()
        }

        fun sample(value:Float) {
            if(!active || android.os.SystemClock.uptimeMillis()-lastSpectrum<600) return
            // 系统识别只给总音量时采用固定位置的包络；离线录音使用实际频谱。
            for(i in levels.indices) {
                val profile=.35f+.65f*(1f-abs(i-8)/8f)
                levels[i]=value*profile
            }
            postInvalidateOnAnimation()
        }

        override fun onDraw(c: Canvas) {
            val n = levels.size
            val gap = 8.5f * d
            val bw = 2.6f * d
            val total = n * bw + (n - 1) * gap
            var x = (width - total) / 2
            val flat = 2.6f * d
            var moving=false
            // 整段颜色固定，波形不随音量在红色与灰色之间切换。
            p.color=if(skin.dark) android.graphics.Color.parseColor("#ACBEBB") else android.graphics.Color.parseColor("#879D9B")
            for (i in 0 until n) {
                val response = if(levels[i] > displayed[i]) .52f else .25f
                displayed[i]+=(levels[i]-displayed[i])*response
                if(abs(levels[i]-displayed[i])>.002f) moving=true
                val h = flat + min(36*d,height*.7f)*displayed[i]
                c.drawRoundRect(RectF(x, height / 2f - h / 2, x + bw, height / 2f + h / 2), bw / 2, bw / 2, p)
                x += bw + gap
            }
            if(moving) postInvalidateOnAnimation()
        }
    }
}
