package com.kongye.keyboard

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

fun Context.dp(v: Float) = v * resources.displayMetrics.density

fun roundBg(color: Int, radiusPx: Float) = GradientDrawable().apply {
    setColor(color)
    cornerRadius = radiusPx
}

internal fun lp(w: Int, h: Int, weight: Float = 0f) = LinearLayout.LayoutParams(w, h, weight)
internal const val MATCH = LinearLayout.LayoutParams.MATCH_PARENT
internal const val WRAP = LinearLayout.LayoutParams.WRAP_CONTENT

/** 一个画图标的小按钮 */
class IconButton(context: Context, private val icon: Int, private val tint: Int, private val sizeDp: Float = 21f, onClick: () -> Unit) : View(context) {
    private val icons = Icons(context)
    init { setOnClickListener { onClick() } }
    override fun onDraw(c: Canvas) = icons.draw(c, icon, width / 2f, height / 2f, sizeDp, tint)
}

/** 开关 */
class ToggleView(context: Context, private val skin: Skin, var on: Boolean, private val onChange: (Boolean) -> Unit) : View(context) {
    private val d = resources.displayMetrics.density
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val r = RectF()

    init {
        setOnClickListener { on = !on; invalidate(); onChange(on) }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) = setMeasuredDimension((48 * d).toInt(), (30 * d).toInt())

    override fun onDraw(c: Canvas) {
        val th = 26 * d
        r.set(1 * d, (height - th) / 2, 47 * d, (height + th) / 2)
        fill.color = if (on) skin.candHi else withAlpha(skin.barFg, 80)
        c.drawRoundRect(r, th / 2, th / 2, fill)
        fill.color = Color.WHITE
        c.drawCircle(if (on) r.right - th / 2 else r.left + th / 2, r.centerY(), th / 2 - 3 * d, fill)
    }
}

/** 等宽格子：表情、颜文字 */
class CellGridView(context: Context) : View(context) {
    var items: List<String> = emptyList()
        set(v) { field = v; requestLayout(); invalidate() }
    var columns = 8
    var cellH = 46f
    var textSize = 26f
    var fg = Color.BLACK
    var downBg = Color.LTGRAY
    var onPick: ((Int) -> Unit)? = null

    private val d = resources.displayMetrics.density
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private var pressed = -1
    private var dx = 0f
    private var dy = 0f

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val rows = ceil(items.size / columns.toFloat()).toInt()
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), (rows * cellH * d + 6 * d).toInt())
    }

    override fun onDraw(c: Canvas) {
        val cw = width / columns.toFloat()
        val ch = cellH * d
        text.textSize = textSize * d
        text.color = fg
        val fm = text.fontMetrics
        for ((i, s) in items.withIndex()) {
            val x = (i % columns) * cw
            val y = (i / columns) * ch + 3 * d
            if (i == pressed) {
                fill.color = downBg
                rect.set(x + 2 * d, y + 2 * d, x + cw - 2 * d, y + ch - 2 * d)
                c.drawRoundRect(rect, 10 * d, 10 * d, fill)
            }
            c.drawText(s, x + cw / 2, y + ch / 2 - (fm.ascent + fm.descent) / 2, text)
        }
    }

    private fun indexAt(x: Float, y: Float): Int {
        val col = (x / (width / columns.toFloat())).toInt()
        val row = ((y - 3 * d) / (cellH * d)).toInt()
        val i = row * columns + col
        return if (col in 0 until columns && row >= 0 && i < items.size) i else -1
    }

    /** 长按（收藏） */
    var onLong: ((Int) -> Unit)? = null
    private val handler = Handler(Looper.getMainLooper())
    private var longFired = false
    private val longTask = Runnable {
        if (pressed >= 0) { longFired = true; onLong?.invoke(pressed); pressed = -1; invalidate() }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                dx = e.x; dy = e.y; pressed = indexAt(e.x, e.y); longFired = false
                if (pressed >= 0 && onLong != null) handler.postDelayed(longTask, 450)
                invalidate()
            }
            MotionEvent.ACTION_MOVE -> if (abs(e.x - dx) > slop || abs(e.y - dy) > slop) { handler.removeCallbacks(longTask); pressed = -1; invalidate() }
            MotionEvent.ACTION_UP -> { handler.removeCallbacks(longTask); if (pressed >= 0 && !longFired) onPick?.invoke(pressed); pressed = -1; invalidate() }
            MotionEvent.ACTION_CANCEL -> { handler.removeCallbacks(longTask); pressed = -1; invalidate() }
        }
        return true
    }
}

/** 展开的候选：自动换行的格子 */
class CandFlowView(context: Context, private val skin: Skin, private val items: List<String>, private val onPick: (Int) -> Unit) : View(context) {
    private val d = resources.displayMetrics.density
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER; textSize = 18 * d }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val boxes = ArrayList<RectF>()
    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private var pressed = -1
    private var dx = 0f
    private var dy = 0f
    private val cellH get() = 48 * d

    private fun layoutBoxes(w: Int): Int {
        boxes.clear()
        val gap = 6 * d
        val pad = 6 * d
        val cols = if (w / d < 280) 3 else 4
        val bw = (w - 2 * pad - (cols - 1) * gap) / cols
        items.forEachIndexed { i, _ ->
            val x = pad + i % cols * (bw + gap)
            val y = pad + i / cols * (cellH + gap)
            boxes.add(RectF(x, y, x + bw, y + cellH))
        }
        val y = pad + ((items.size - 1).coerceAtLeast(0) / cols) * (cellH + gap)
        return (y + cellH + pad).toInt()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        setMeasuredDimension(w, layoutBoxes(w))
    }

    override fun onDraw(c: Canvas) {
        val fm = text.fontMetrics
        for ((i, r) in boxes.withIndex()) {
            val kongyeFirst = i == 0 && skin.style == SkinStyle.KONGYE
            fill.color = when {
                kongyeFirst && i == pressed -> darken(Color.rgb(240, 73, 44), 0.88f)
                kongyeFirst -> Color.rgb(240, 73, 44)
                i == pressed -> skin.keyDown
                else -> skin.keyBg
            }
            c.drawRoundRect(r, 10 * d, 10 * d, fill)
            text.color = when {
                kongyeFirst -> Color.WHITE
                i == 0 -> skin.candHi
                else -> skin.keyFg
            }
            text.typeface = Typeface.DEFAULT
            text.textSize = 18 * d
            while (text.measureText(items[i]) > r.width() - 12 * d && text.textSize > 10 * d) text.textSize -= d
            val tfm = text.fontMetrics
            c.drawText(items[i], r.centerX(), r.centerY() - (tfm.ascent + tfm.descent) / 2, text)
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { dx = e.x; dy = e.y; pressed = boxes.indexOfFirst { it.contains(e.x, e.y) }; invalidate() }
            MotionEvent.ACTION_MOVE -> if (abs(e.x - dx) > slop || abs(e.y - dy) > slop) { pressed = -1; invalidate() }
            MotionEvent.ACTION_UP -> { if (pressed >= 0) onPick(pressed); pressed = -1; invalidate() }
            MotionEvent.ACTION_CANCEL -> { pressed = -1; invalidate() }
        }
        return true
    }
}

/**
 * 配色方案网格（照“主题与定制”截图）：每张卡左半浅色、右半深色；只有浅色的整张浅色。
 * 键盘里的换肤面板和设置页共用。
 */
class ThemeGridView(
    context: Context,
    private val themes: List<Theme>,
    var selected: String,
    private val labelFg: Int,
    private val hiFg: Int,
    private val lineColor: Int,
    private val columns: Int = 3,
    private val onPick: (Theme) -> Unit,
) : View(context) {
    private val d = resources.displayMetrics.density
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG)
    private val r = RectF()
    private val clip = Path()
    private val cards = ArrayList<RectF>()
    private val pad get() = 12 * d
    private val gap get() = 12 * d
    private val labelH get() = 30 * d

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val cw = (w - 2 * pad - (columns - 1) * gap) / columns
        val ch = cw * 0.62f
        val rows = ceil(themes.size / columns.toFloat()).toInt()
        cards.clear()
        for (i in themes.indices) {
            val x = pad + (i % columns) * (cw + gap)
            val y = pad + (i / columns) * (ch + labelH + gap)
            cards.add(RectF(x, y, x + cw, y + ch))
        }
        setMeasuredDimension(w, (pad * 2 + rows * (ch + labelH + gap)).toInt())
    }

    override fun onDraw(c: Canvas) {
        for ((i, t) in themes.withIndex()) {
            val card = cards.getOrNull(i) ?: continue
            val rad = 12 * d
            c.save()
            clip.reset()
            clip.addRoundRect(card, rad, rad, Path.Direction.CW)
            c.clipPath(clip)
            val dark = t.dark
            if (dark != null) {
                val mid = card.centerX()
                mini(c, RectF(card.left, card.top, mid, card.bottom), t.light)
                mini(c, RectF(mid, card.top, card.right, card.bottom), dark)
            } else mini(c, card, t.light)
            c.restore()
            val sel = t.id == selected
            stroke.strokeWidth = (if (sel) 2.5f else 1f) * d
            stroke.color = if (sel) hiFg else lineColor
            if (sel) {
                r.set(card.left - 3 * d, card.top - 3 * d, card.right + 3 * d, card.bottom + 3 * d)
                c.drawRoundRect(r, rad + 3 * d, rad + 3 * d, stroke)
            } else c.drawRoundRect(card, rad, rad, stroke)
            // 名字前面的小色块
            text.textSize = 13 * d
            text.typeface = if (sel) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
            val tw = text.measureText(t.name)
            val sq = 10 * d
            val x0 = card.centerX() - (sq + 6 * d + tw) / 2
            val ty = card.bottom + labelH / 2 + 2 * d
            fill.color = t.swatch
            r.set(x0, ty - sq / 2, x0 + sq, ty + sq / 2)
            c.drawRoundRect(r, 2 * d, 2 * d, fill)
            text.color = if (sel) hiFg else labelFg
            val fm = text.fontMetrics
            c.drawText(t.name, x0 + sq + 6 * d, ty - (fm.ascent + fm.descent) / 2, text)
        }
    }

    private fun mini(c: Canvas, area: RectF, s: Skin) {
        if (s.bg2 != 0) {
            fill.shader = LinearGradient(0f, area.top, 0f, area.bottom, s.kbBg, s.bg2, Shader.TileMode.CLAMP)
            c.drawRect(area, fill)
            fill.shader = null
        } else {
            fill.color = s.kbBg
            c.drawRect(area, fill)
        }
        val p = area.width() * 0.09f
        val g = area.width() * 0.035f
        val inner = area.width() - 2 * p
        // 左上角的小色条
        fill.color = s.accBg
        r.set(area.left + p, area.top + p, area.left + p + inner * 0.3f, area.top + p + area.height() * 0.07f)
        c.drawRoundRect(r, 3 * d, 3 * d, fill)
        val top = area.top + p + area.height() * 0.14f
        val rh = (area.bottom - p - top - 2 * g) / 3f
        for (row in 0 until 2) {
            val n = 5
            val kw = (inner - (n - 1) * g) / n
            for (k in 0 until n) {
                val x = area.left + p + k * (kw + g)
                fill.color = s.keyBg
                r.set(x, top + row * (rh + g), x + kw, top + row * (rh + g) + rh)
                c.drawRoundRect(r, 1.5f * d, 1.5f * d, fill)
            }
        }
        val y = top + 2 * (rh + g)
        val ws = floatArrayOf(1f, 3f, 1f)
        val unit = (inner - 2 * g) / ws.sum()
        var x = area.left + p
        for ((k, wgt) in ws.withIndex()) {
            fill.color = when (k) { 0 -> s.modBg; 2 -> if (s.enterMod) s.modBg else s.accBg; else -> s.keyBg }
            r.set(x, y, x + wgt * unit, y + rh)
            c.drawRoundRect(r, 1.5f * d, 1.5f * d, fill)
            x += wgt * unit + g
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (e.actionMasked == MotionEvent.ACTION_UP) {
            for ((i, card) in cards.withIndex()) {
                r.set(card.left - 4 * d, card.top - 4 * d, card.right + 4 * d, card.bottom + labelH)
                if (r.contains(e.x, e.y)) { selected = themes[i].id; invalidate(); onPick(themes[i]); break }
            }
        }
        return true
    }
}

/** 一排分段按钮 */
fun segmented(context: Context, skin: Skin, options: List<String>, index: Int, onSel: (Int) -> Unit): LinearLayout {
    val row = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        background = roundBg(withAlpha(skin.barFg, 40), context.dp(18f))
        val p = context.dp(3f).toInt()
        setPadding(p, p, p, p)
    }
    // 点了先自己换高亮：有的面板改设置后不重建（语音、键盘调节）
    fun mark(sel: Int) {
        for (j in 0 until row.childCount) (row.getChildAt(j) as TextView).apply {
            setTextColor(if (j == sel) skin.keyFg else skin.barFg)
            typeface = if (j == sel) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
            background = if (j == sel) roundBg(skin.keyBg, context.dp(15f)) else null
        }
    }
    options.forEachIndexed { i, s ->
        row.addView(TextView(context).apply {
            text = s
            textSize = 13.5f
            gravity = Gravity.CENTER
            val h = context.dp(12f).toInt()
            setPadding(h, context.dp(5f).toInt(), h, context.dp(5f).toInt())
            setOnClickListener { mark(i); onSel(i) }
        }, lp(WRAP, WRAP))
    }
    mark(index)
    return row
}

/** 换皮肤面板：深浅模式 + 配色网格 */
class SkinPanel(context: Context, skin: Skin, themes: List<Theme>, selected: String, mode: Int, onMode: (Int) -> Unit, onPick: (Theme) -> Unit) : LinearLayout(context) {
    init {
        orientation = VERTICAL
        val head = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val p = context.dp(12f).toInt()
            setPadding(p, context.dp(6f).toInt(), p, 0)
        }
        head.addView(TextView(context).apply { text = "深色模式"; textSize = 14f; setTextColor(skin.candFg) }, lp(0, WRAP, 1f))
        head.addView(segmented(context, skin, listOf("跟随系统", "浅色", "深色"), mode, onMode), lp(WRAP, WRAP))
        addView(head, lp(MATCH, WRAP))
        val grid = ThemeGridView(context, themes, selected, skin.candFg, skin.candHi, withAlpha(skin.candFg, 40), 3, onPick)
        addView(ScrollView(context).apply { isVerticalScrollBarEnabled = false; addView(grid) }, lp(MATCH, 0, 1f))
    }
}

/** “更多”面板：图标 + 名字的格子 */
class TileGridView(context: Context, private val skin: Skin, private val items: List<Triple<String, String, Int>>, private val onPick: (String) -> Unit) : View(context) {
    private val d = resources.displayMetrics.density
    private val icons = Icons(context)
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val r = RectF()
    private val cols = 4
    private var pressed = -1
    private val tileH get() = 70 * d

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val rows = ceil(items.size / cols.toFloat()).toInt()
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), (rows * tileH + 12 * d).toInt())
    }

    override fun onDraw(c: Canvas) {
        val cw = width / cols.toFloat()
        for ((i, it) in items.withIndex()) {
            val cx = (i % cols) * cw + cw / 2
            val top = (i / cols) * tileH + 6 * d
            val box = 44 * d
            r.set(cx - box / 2, top, cx + box / 2, top + box)
            fill.color = if (i == pressed) skin.keyDown else skin.keyBg
            c.drawRoundRect(r, 14 * d, 14 * d, fill)
            icons.draw(c, it.third, cx, r.centerY(), 24f, skin.keyFg)
            text.textSize = 12 * d
            text.color = skin.candFg
            c.drawText(it.second, cx, r.bottom + 16 * d, text)
        }
    }

    private fun indexAt(x: Float, y: Float): Int {
        val col = (x / (width / cols.toFloat())).toInt()
        val row = ((y - 6 * d) / tileH).toInt()
        val i = row * cols + col
        return if (row >= 0 && i in items.indices) i else -1
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { pressed = indexAt(e.x, e.y); invalidate() }
            MotionEvent.ACTION_UP -> { val i = pressed; pressed = -1; invalidate(); if (i >= 0 && i == indexAt(e.x, e.y)) onPick(items[i].first) }
            MotionEvent.ACTION_CANCEL -> { pressed = -1; invalidate() }
        }
        return true
    }
}

/**
 * 编辑面板（照截图 5）：中间圆形方向盘，四角段首/段尾/复制/粘贴，左列撤销/重做/剪切，右列删除/全选/换行。
 */
class EditPanelView(context: Context, private val skin: Skin, var selecting: Boolean, private val onAction: (String) -> Unit) : View(context) {
    private val d = resources.displayMetrics.density
    private val icons = Icons(context)
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val handler = Handler(Looper.getMainLooper())
    private var repeat: Runnable? = null
    private var pressed: String? = null

    private class Tile(val id: String, val label: String, val icon: Int, val mod: Boolean, val rect: RectF = RectF())

    private val tiles = listOf(
        Tile("undo", "撤销", R.drawable.ic_undo, false), Tile("redo", "重做", R.drawable.ic_redo, false), Tile("cut", "剪切", R.drawable.ic_cut, false),
        Tile("home", "段首", R.drawable.ic_home, false), Tile("end", "段尾", R.drawable.ic_end, false),
        Tile("copy", "复制", R.drawable.ic_copy, false), Tile("paste", "粘贴", R.drawable.ic_paste, false),
        Tile("del", "删除", R.drawable.ic_del, true), Tile("all", "全选", R.drawable.ic_select_all, false), Tile("enter", "换行", R.drawable.ic_enter, true),
    )
    private var cx = 0f
    private var cy = 0f
    private var radius = 0f

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        val pad = 8 * d
        val gap = 8 * d
        val colW = (w - 2 * pad) * 0.18f
        val rowH = (h - 2 * pad - 2 * gap) / 3f
        val mid = RectF(pad + colW + gap, pad, w - pad - colW - gap, h - pad)
        cx = mid.centerX(); cy = mid.centerY()
        radius = min(mid.width(), mid.height()) * 0.46f
        fun col(x: Float, i: Int) = RectF(x, pad + i * (rowH + gap), x + colW, pad + i * (rowH + gap) + rowH)
        tiles[0].rect.set(col(pad, 0)); tiles[1].rect.set(col(pad, 1)); tiles[2].rect.set(col(pad, 2))
        val rx = w - pad - colW
        tiles[7].rect.set(col(rx, 0)); tiles[8].rect.set(col(rx, 1)); tiles[9].rect.set(col(rx, 2))
        val cw = mid.width() * 0.3f
        val ch = mid.height() * 0.42f
        tiles[3].rect.set(mid.left, mid.top, mid.left + cw, mid.top + ch)
        tiles[4].rect.set(mid.right - cw, mid.top, mid.right, mid.top + ch)
        tiles[5].rect.set(mid.left, mid.bottom - ch, mid.left + cw, mid.bottom)
        tiles[6].rect.set(mid.right - cw, mid.bottom - ch, mid.right, mid.bottom)
    }

    override fun onDraw(c: Canvas) {
        val rad = (if (skin.leafCorners) 12f else 10f) * d
        for (t in tiles) {
            fill.color = if (pressed == t.id) skin.keyDown else if (t.mod) skin.modBg else skin.keyBg
            c.drawRoundRect(t.rect, rad, rad, fill)
            // 四角的图标和字放在远离圆盘的外角
            val tx = when (t.id) { "home", "copy" -> t.rect.left + t.rect.width() * 0.25f; "end", "paste" -> t.rect.right - t.rect.width() * 0.25f; else -> t.rect.centerX() }
            val ty = when (t.id) {
                "home", "end" -> t.rect.top + 16 * d
                "copy", "paste" -> t.rect.bottom - 38 * d
                else -> t.rect.centerY() - 9 * d
            }
            icons.draw(c, t.icon, tx, ty, 22f, if (t.mod) skin.modFg else skin.keyFg)
            label(c, t.label, tx, ty + 22 * d)
        }
        // 圆盘：先画一圈底色做出缝隙，再画键色圆
        fill.color = skin.kbBg
        c.drawCircle(cx, cy, radius + 8 * d, fill)
        fill.color = skin.keyBg
        c.drawCircle(cx, cy, radius, fill)
        pressed?.let { p ->
            if (p in setOf("up", "down", "left", "right")) {
                fill.color = skin.keyDown
                val ang = when (p) { "right" -> 0f; "down" -> 90f; "left" -> 180f; else -> 270f }
                c.drawArc(RectF(cx - radius, cy - radius, cx + radius, cy + radius), ang - 45, 90f, true, fill)
            }
        }
        val a = radius * 0.68f
        icons.draw(c, R.drawable.ic_chevron_up, cx, cy - a, 26f, skin.keyFg)
        icons.draw(c, R.drawable.ic_chevron_down, cx, cy + a, 26f, skin.keyFg)
        icons.draw(c, R.drawable.ic_chevron_left, cx - a, cy, 26f, skin.keyFg)
        icons.draw(c, R.drawable.ic_chevron_right, cx + a, cy, 26f, skin.keyFg)
        // 中间的“选择”
        val sr = RectF(cx - radius * 0.3f, cy - radius * 0.26f, cx + radius * 0.3f, cy + radius * 0.3f)
        fill.color = if (selecting) skin.accBg else if (pressed == "select") skin.keyDown else skin.keyBg
        c.drawRoundRect(sr, 12 * d, 12 * d, fill)
        val fg = if (selecting) skin.accFg else skin.keyFg
        text.textSize = 22 * d
        text.typeface = Typeface.DEFAULT_BOLD
        text.color = fg
        c.drawText("Tт", cx, cy + 2 * d, text)
        text.typeface = Typeface.DEFAULT
        text.textSize = 12 * d
        c.drawText(if (selecting) "选择中" else "选择", cx, cy + 20 * d, text)
    }

    private fun label(c: Canvas, s: String, x: Float, y: Float) {
        text.textSize = 13 * d
        text.color = skin.keyFg
        text.typeface = Typeface.DEFAULT
        c.drawText(s, x, y, text)
    }

    private fun hit(x: Float, y: Float): String? {
        val dist = hypot(x - cx, y - cy)
        if (dist <= radius) {
            if (dist < radius * 0.32f) return "select"
            val ang = Math.toDegrees(atan2((y - cy).toDouble(), (x - cx).toDouble()))
            return when {
                ang >= -45 && ang < 45 -> "right"
                ang >= 45 && ang < 135 -> "down"
                ang >= -135 && ang < -45 -> "up"
                else -> "left"
            }
        }
        return tiles.firstOrNull { it.rect.contains(x, y) }?.id
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val id = hit(e.x, e.y) ?: return true
                pressed = id
                invalidate()
                onAction(id)
                if (id in setOf("up", "down", "left", "right", "del")) {
                    val task = object : Runnable {
                        override fun run() { onAction(id); handler.postDelayed(this, 60) }
                    }
                    repeat = task
                    handler.postDelayed(task, 420)
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                repeat?.let { handler.removeCallbacks(it) }
                repeat = null
                pressed = null
                invalidate()
            }
        }
        return true
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        repeat?.let { handler.removeCallbacks(it) }
    }
}

/** 设置类面板的一行 */
sealed class Opt {
    class Toggle(val title: String, val sub: String?, val on: Boolean, val onChange: (Boolean) -> Unit) : Opt()
    class Segment(val title: String, val options: List<String>, val index: Int, val onSel: (Int) -> Unit) : Opt()
    class Action(val title: String, val sub: String?, val button: String, val onClick: () -> Unit) : Opt()
    class Note(val text: String) : Opt()
}

fun optionList(context: Context, skin: Skin, opts: List<Opt>): ScrollView {
    val col = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        val p = context.dp(10f).toInt()
        setPadding(p, context.dp(4f).toInt(), p, p)
    }
    for (o in opts) {
        if (o is Opt.Note) {
            col.addView(TextView(context).apply {
                text = o.text
                textSize = 12.5f
                setTextColor(skin.barFg)
                setPadding(context.dp(6f).toInt(), context.dp(8f).toInt(), context.dp(6f).toInt(), context.dp(4f).toInt())
            }, lp(MATCH, WRAP))
            continue
        }
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = roundBg(skin.keyBg, context.dp(12f))
            val p = context.dp(14f).toInt()
            setPadding(p, context.dp(10f).toInt(), context.dp(10f).toInt(), context.dp(10f).toInt())
        }
        val texts = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        val title = when (o) { is Opt.Toggle -> o.title; is Opt.Segment -> o.title; is Opt.Action -> o.title; else -> "" }
        val sub = when (o) { is Opt.Toggle -> o.sub; is Opt.Action -> o.sub; else -> null }
        texts.addView(TextView(context).apply { text = title; textSize = 15f; setTextColor(skin.keyFg) })
        if (sub != null) texts.addView(TextView(context).apply { text = sub; textSize = 12f; setTextColor(skin.barFg) })
        row.addView(texts, lp(0, WRAP, 1f))
        when (o) {
            is Opt.Toggle -> row.addView(ToggleView(context, skin, o.on, o.onChange), lp(WRAP, WRAP))
            is Opt.Segment -> row.addView(segmented(context, skin, o.options, o.index, o.onSel), lp(WRAP, WRAP))
            is Opt.Action -> row.addView(TextView(context).apply {
                text = o.button
                textSize = 13.5f
                setTextColor(skin.accFg)
                background = roundBg(skin.accBg, context.dp(16f))
                val p = context.dp(14f).toInt()
                setPadding(p, context.dp(6f).toInt(), p, context.dp(6f).toInt())
                setOnClickListener { o.onClick() }
            }, lp(WRAP, WRAP))
            else -> Unit
        }
        col.addView(row, lp(MATCH, WRAP).apply { topMargin = context.dp(6f).toInt() })
    }
    return ScrollView(context).apply { isVerticalScrollBarEnabled = false; addView(col) }
}

/** 键盘最下面一条：左边切换输入法，右边剪贴板（照截图 1 的红框） */
class BottomStrip(context: Context, private val onKeyboard: () -> Unit, private val onClip: () -> Unit) : View(context) {
    var uiScale = 1f
        set(v) { if(field!=v) {field=v;icons.scale=v;requestLayout();invalidate() } }
    private val d get() = resources.displayMetrics.density * uiScale
    private val icons = Icons(context)
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    var skin: Skin = Skins.KONGYE
        set(v) { field = v; invalidate() }
    var cursorMode: Boolean = false
        set(v) { if (field != v) { field = v; invalidate() } }
    /** 系统手势区或导航栏的高度：图标画在它上面 */
    var inset = 0
        set(v) { field = v; requestLayout() }
    /** 键盘调节里的“底部留白”：加在图标下面 */
    var extra = 0
        set(v) { if (v != field) { field = v; requestLayout() } }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), max((40 * d).toInt(), inset + (24 * d).toInt()) + extra)
    }

    override fun onDraw(c: Canvas) {
        val cy = (height - inset - extra) / 2f + (if (inset < 30 * d) inset / 4f else 0f)
        icons.draw(c, R.drawable.ic_chevron_down, 30 * d, cy, 22f, skin.barFg)
        icons.draw(c, R.drawable.ic_clip, width - 30 * d, cy, 22f, skin.barFg)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (e.actionMasked == MotionEvent.ACTION_UP) {
            if (e.x < 70 * d) onKeyboard() else if (e.x > width - 70 * d) onClip()
        }
        return true
    }
}

/** 面板容器：面板和键盘一样高，叠在键盘位置上。高度固定取键盘的，免得面板内容把输入法撑高 */
class PanelHost(context: Context) : FrameLayout(context) {
    var heightOf: () -> Int = { 0 }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(heightOf(), MeasureSpec.EXACTLY))
    }
}
