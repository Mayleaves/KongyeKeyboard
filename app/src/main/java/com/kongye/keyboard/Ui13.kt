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

    private val textSizePx get() = (if (expanded) 22f else 15f) * d
    private val padX get() = (if (expanded) 10f else 5f) * d
    private val topInset get() = if (expanded) 9 * d else d
    private val rightInset get() = if (expanded) 10 * d else d
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
        val minW = (if (expanded) 62 else 32) * d
        val want = (tp.measureText(text) + 2 * padX + rightInset + 6 * d).toInt()
            .coerceIn(minW.toInt(), max(minW.toInt(), min(maxW - (16 * d).toInt(), (176 * d).toInt())))
        val h = ((if (expanded) 34 else 23) * d + topInset).toInt()
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
        // 所有状态都用细灰边与略透明底，避免玻璃模式下出现一整条模糊带。
        fill.color = withAlpha(if (skin.dark) skin.popBg else android.graphics.Color.WHITE, 222)
        c.drawPath(path, fill)
        stroke.strokeWidth = .8f * d
        stroke.color = if (skin.dark) android.graphics.Color.parseColor("#777B80") else android.graphics.Color.parseColor("#C5C7CA")
        c.drawPath(path, stroke)

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
        if (expanded) {
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
                    NativeMotion.pulse(this)
                    invalidate()
                    return true
                }
            }
            tp.textSize = textSizePx
            val x = e.x - (d + padX) + offset
            var best = 0
            var bestD = Float.MAX_VALUE
            for (i in 0..text.length) {
                val dx = abs(tp.measureText(text, 0, i) - x)
                if (dx < bestD) { bestD = dx; best = i }
            }
            if (!expanded) { expanded = true; requestLayout(); NativeMotion.pulse(this); invalidate() }
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
    private val pager = CategoryPager(context)
    private val tabViews = ArrayList<TextView>()

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
            // 输入法使用原生 View 自绘，不依赖 AppCompat 的主题着色替换。
            @SuppressLint("AppCompatCustomView")
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
                    pager.select(i)
                }
            }
            tabViews.add(tv)
            tabs.addView(tv, lp(WRAP, keyH))
        }
        scroller.addView(tabs)
        bottom.addView(scroller, lp(0, keyH, 4.6f).apply { marginEnd = (5 * d).toInt() })
        modKey(null, R.drawable.ic_del, 1.25f, onDel, repeat = true).let { (it.layoutParams as LayoutParams).marginEnd = 0 }
        addView(bottom, lp(MATCH, WRAP))
        pager.pages(initialCats.size, curCat, { index ->
            ScrollView(context).apply { isVerticalScrollBarEnabled = false; addView(SymGrid(catsProvider().getOrNull(index)?.second.orEmpty())) }
        }) { index -> curCat = index; onCat(index); updateTabs(index, true) }
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
    private val gray = if (skin.dark) android.graphics.Color.parseColor("#E4313338") else android.graphics.Color.parseColor("#F0E8E8EB")
    private val ink = if (skin.dark) android.graphics.Color.parseColor("#E7E8EA") else android.graphics.Color.parseColor("#4B4E50")
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style=Paint.Style.STROKE; strokeWidth=3*d; strokeCap=Paint.Cap.ROUND; color=ink }
    private val card = LinearLayout(context)
    private val label = TextView(context)
    private val blankLabel = TextView(context)
    init {
        setWillNotDraw(false)
        // 中央卡片、四角与边缘手柄，键盘始终留在后面作为实时预览。
        card.orientation=LinearLayout.VERTICAL
        card.background=roundBg(gray, 18*d)
        card.setPadding((12*d).toInt(), (10*d).toInt(), (12*d).toInt(), (10*d).toInt())
        card.addView(LinearLayout(context).apply {
            gravity=Gravity.CENTER_VERTICAL
            addView(SliderView(context, skin.copy(candHi=ink), 1f-a.opacity, 0f, .7f, {
                a.opacity=1f-it; refresh(); onLive()
            }), lp(0, (38*d).toInt(), 1f))
            label.setTextColor(ink); label.textSize=13f; label.gravity=Gravity.CENTER
            addView(label, lp((94*d).toInt(), WRAP))
        }, lp(MATCH, WRAP))
        val buttons=LinearLayout(context).apply { orientation=LinearLayout.HORIZONTAL; setPadding(0,(9*d).toInt(),0,0) }
        fun button(title: String, active: Boolean=false, action: () -> Unit) {
            buttons.addView(TextView(context).apply {
                text=title; textSize=14f; gravity=Gravity.CENTER
                setTextColor(if(active) android.graphics.Color.WHITE else ink)
                background=roundBg(if(active) ink else withAlpha(ink, 20), 12*d)
                setOnClickListener { action() }
            }, lp(0,(48*d).toInt(),1f).apply { setMargins((2*d).toInt(),0,(2*d).toInt(),0) })
        }
        button("↺ 重置", action=onReset)
        button("↗ 悬浮", floating, onFloating)
        button("分体", a.split) { a.split=!a.split; onLive(); (buttons.getChildAt(2) as TextView).apply {
            background=roundBg(if(a.split) ink else withAlpha(ink,20),12*d)
            setTextColor(if(a.split) android.graphics.Color.WHITE else ink)
        } }
        button("✓ 确认", action=onDone)
        card.addView(buttons, lp(MATCH,WRAP))
        addView(card, LayoutParams(MATCH,WRAP,Gravity.CENTER).apply { leftMargin=(34*d).toInt(); rightMargin=(34*d).toInt() })
        addView(Handle { _,dy,start -> resize(start, 0f, dy, vertical=-1) },
            LayoutParams((86*d).toInt(),(25*d).toInt(),Gravity.TOP or Gravity.CENTER_HORIZONTAL))
        addView(TextView(context).apply {
            text=if(floating) "✥ 拖动移动" else "↕ 高度  ↔ 宽度"
            textSize=11f; gravity=Gravity.CENTER; setTextColor(ink); background=roundBg(gray,10*d)
        }, LayoutParams((112*d).toInt(),(22*d).toInt(),Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { topMargin=(44*d).toInt() })
        if(floating) addView(Handle { dx,dy,start ->
            onMove(start.floatX+dx, start.floatY+dy)
        }, LayoutParams((130*d).toInt(),(40*d).toInt(),Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { topMargin=(24*d).toInt() })
        addView(Handle { dx,_,start -> resize(start, dx, 0f, horizontal=-1) },
            LayoutParams((24*d).toInt(),MATCH,Gravity.START).apply { topMargin=(28*d).toInt(); bottomMargin=(28*d).toInt() })
        addView(Handle { dx,_,start -> resize(start, dx, 0f, horizontal=1) },
            LayoutParams((24*d).toInt(),MATCH,Gravity.END).apply { topMargin=(28*d).toInt(); bottomMargin=(28*d).toInt() })
        addView(Handle { _,dy,start -> resize(start, 0f, dy, vertical=1) },
            LayoutParams((90*d).toInt(),(24*d).toInt(),Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL))
        for(right in listOf(false,true)) for(bottom in listOf(false,true)) addView(Handle { dx,dy,start ->
            resize(start, dx, dy, if(right) 1 else -1, if(bottom) 1 else -1)
        }, LayoutParams((28*d).toInt(),(28*d).toInt(),(if(right) Gravity.END else Gravity.START) or (if(bottom) Gravity.BOTTOM else Gravity.TOP)))
        // 独立调底部留白，顶部高度手柄只改 scale。
        val blank=LinearLayout(context).apply {
            orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER_VERTICAL
            background=roundBg(if(skin.dark) android.graphics.Color.parseColor("#45474B") else android.graphics.Color.parseColor("#D3D3D8"),10*d)
        }
        fun blankButton(title:String,delta:Int)=TextView(context).apply {
            text=title;textSize=23f;gravity=Gravity.CENTER;setTextColor(ink)
            repeatOnHold { a.padB=(a.padB+delta).coerceIn(0,48);refresh();onLive() }
        }
        blank.addView(blankButton("−",-1),lp((36*d).toInt(),MATCH))
        blankLabel.textSize=13f;blankLabel.gravity=Gravity.CENTER;blankLabel.setTextColor(ink)
        blank.addView(blankLabel,lp(0,MATCH,1f))
        blank.addView(blankButton("+",1),lp((36*d).toInt(),MATCH))
        addView(blank,LayoutParams((210*d).toInt(),(34*d).toInt(),Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply {bottomMargin=(32*d).toInt()})
        refresh()
    }
    private fun resize(start: Adjust, dx: Float, dy: Float, horizontal: Int=0, vertical: Int=0) {
        val screen = resources.displayMetrics.widthPixels.toFloat()
        if (vertical != 0) {
            val scalePixels = 4 * skin.keyH * d
            a.scale = (start.scale + vertical * dy / scalePixels).coerceIn(.7f, 1.5f)
            if (a.floating && vertical < 0) a.floatY = start.floatY - (a.scale-start.scale)*scalePixels
        }
        if (horizontal != 0) {
            if (a.floating) {
                a.floatWidth = (start.floatWidth + horizontal * dx / screen).coerceIn(.55f, 1f)
                if (horizontal < 0) a.floatX = start.floatX + (start.floatWidth-a.floatWidth)*screen
            } else {
                // 以按下时的原值累计，避免每帧取整丢失小幅移动；至少保留 55% 宽度。
                val maxTotalPad = (screen/d * .45f).toInt()
                if (horizontal < 0) a.padL = kotlin.math.round(start.padL + dx/d).toInt().coerceIn(0, (maxTotalPad-start.padR).coerceAtLeast(0))
                else a.padR = kotlin.math.round(start.padR - dx/d).toInt().coerceIn(0, (maxTotalPad-start.padL).coerceAtLeast(0))
            }
        }
        onLive()
    }
    private fun refresh() { label.text="透明度 ${((1-a.opacity)*100).toInt()}%"; blankLabel.text="↕ 底部留白 ${a.padB} dp"; invalidate() }
    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent)=true
    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w,h,oldw,oldh)
        if (android.os.Build.VERSION.SDK_INT >= 29) {
            // 边缘手柄用于缩放，拖动时不要被系统的侧滑返回抢走。
            val edge=(24*d).toInt(); val half=(44*d).toInt()
            systemGestureExclusionRects=listOf(
                android.graphics.Rect(0, (h/2-half).coerceAtLeast(0), edge, (h/2+half).coerceAtMost(h)),
                android.graphics.Rect((w-edge).coerceAtLeast(0), (h/2-half).coerceAtLeast(0), w, (h/2+half).coerceAtMost(h)),
            )
        }
    }
    override fun onDraw(c: Canvas) {
        val inset=4*d; val r=20*d; val w=width.toFloat(); val h=height.toFloat()
        val path=Path()
        for(right in listOf(false,true)) for(bottom in listOf(false,true)) {
            val x=if(right) w-inset else inset; val y=if(bottom) h-inset else inset
            val sx=if(right) -1 else 1; val sy=if(bottom) -1 else 1
            path.moveTo(x+sx*r*1.8f,y); path.lineTo(x+sx*r,y)
            path.quadTo(x,y,x,y+sy*r); path.lineTo(x,y+sy*r*1.8f)
        }
        c.drawPath(path,stroke)
        c.drawLine(w/2-16*d,6*d,w/2+16*d,6*d,stroke)
        c.drawLine(w/2-16*d,h-6*d,w/2+16*d,h-6*d,stroke)
        c.drawLine(6*d,h/2-14*d,6*d,h/2+14*d,stroke)
        c.drawLine(w-6*d,h/2-14*d,w-6*d,h/2+14*d,stroke)
    }
    private inner class Handle(private val drag:(Float,Float,Adjust)->Unit):View(context) {
        private var x0=0f; private var y0=0f
        private var start = a.copy()
        @SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(e:MotionEvent):Boolean {
            when(e.actionMasked) {
                MotionEvent.ACTION_DOWN->{ x0=e.rawX; y0=e.rawY; start=a.copy(); parent?.requestDisallowInterceptTouchEvent(true) }
                MotionEvent.ACTION_MOVE->{ drag(e.rawX-x0,e.rawY-y0,start); refresh() }
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
