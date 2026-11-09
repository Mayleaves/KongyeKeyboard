package com.kongye.keyboard

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/**
 * 符号面板（照截图）：上面一排分类；下面 4 列符号，右边一列锁定/删除、上翻、下翻、返回。
 * 没锁定时输入一个符号就回到键盘；锁定后右上角变成删除，可以连续输入。
 */
class SymbolPanel(
    context: Context,
    private val skin: Skin,
    startCat: Int,
    private val locked: Boolean,
    private val onCat: (Int) -> Unit,
    private val onSym: (String) -> Unit,
    private val onLock: () -> Unit,
    private val onDel: () -> Unit,
    private val onBack: () -> Unit,
) : LinearLayout(context) {
    private val d = resources.displayMetrics.density
    private val grid = Grid(Layouts.SYMBOLS[startCat].second)

    init {
        orientation = VERTICAL
        val tabs = LinearLayout(context).apply { orientation = HORIZONTAL }
        Layouts.SYMBOLS.forEachIndexed { i, (name, _) ->
            tabs.addView(TextView(context).apply {
                text = name
                textSize = 16f
                gravity = Gravity.CENTER
                setTextColor(if (i == startCat) skin.keyFg else skin.barFg)
                typeface = if (i == startCat) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
                if (i == startCat) setBackgroundColor(skin.modBg)
                setPadding((16 * d).toInt(), 0, (16 * d).toInt(), 0)
                setOnClickListener { onCat(i) }
            }, lp(WRAP, (42 * d).toInt()))
        }
        addView(HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = false
            setBackgroundColor(skin.keyBg)
            addView(tabs)
        }, lp(MATCH, WRAP))
        addView(grid, lp(MATCH, 0, 1f))
    }

    private inner class Grid(val items: List<String>) : View(context) {
        private val icons = Icons(context)
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
        private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
        private val r = RectF()
        private val rows = 4
        private var page = 0
        private val pages get() = max(1, ceil(items.size / (rows * 4f)).toInt())
        private var pressed = -1
        private var downY = 0f
        private val slop = ViewConfiguration.get(context).scaledTouchSlop

        private fun cell(col: Int, row: Int): RectF {
            val pad = 6 * d
            val gap = 7 * d
            val cw = (width - 2 * pad - 4 * gap) / 4.6f
            val ch = (height - 2 * pad - (rows - 1) * gap) / rows
            val x = if (col < 4) pad + col * (cw + gap) else pad + 4 * (cw + gap)
            val w = if (col < 4) cw else width - pad - x
            val y = pad + row * (ch + gap)
            return RectF(x, y, x + w, y + ch)
        }

        override fun onDraw(c: Canvas) {
            val rad = skin.radius * d
            for (row in 0 until rows) for (col in 0 until 4) {
                val i = page * rows * 4 + row * 4 + col
                val s = items.getOrNull(i) ?: continue
                val rc = cell(col, row)
                fill.color = if (pressed == i) skin.keyDown else skin.keyBg
                c.drawRoundRect(rc, rad, rad, fill)
                text.color = skin.keyFg
                text.textSize = (if (s.length > 6) 12 else if (s.length > 3) 15 else 22) * d
                val fm = text.fontMetrics
                c.drawText(s, rc.centerX(), rc.centerY() - (fm.ascent + fm.descent) / 2, text)
            }
            val side = listOf(if (locked) R.drawable.ic_del else R.drawable.ic_lock_open, R.drawable.ic_chevron_up, R.drawable.ic_chevron_down, 0)
            for (row in 0 until rows) {
                val rc = cell(4, row)
                fill.color = if (pressed == -10 - row) skin.keyDown else skin.modBg
                c.drawRoundRect(rc, rad, rad, fill)
                val dim = (row == 1 && page == 0) || (row == 2 && page >= pages - 1)
                if (side[row] != 0) icons.draw(c, side[row], rc.centerX(), rc.centerY(), 24f, if (dim) withAlpha(skin.modFg, 90) else skin.modFg)
                else {
                    text.color = skin.modFg
                    text.textSize = 16 * d
                    val fm = text.fontMetrics
                    c.drawText("返回", rc.centerX(), rc.centerY() - (fm.ascent + fm.descent) / 2, text)
                }
            }
        }

        private fun hit(x: Float, y: Float): Int {
            for (row in 0 until rows) {
                for (col in 0 until 4) if (cell(col, row).contains(x, y)) return page * rows * 4 + row * 4 + col
                if (cell(4, row).contains(x, y)) return -10 - row
            }
            return -1
        }

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(e: MotionEvent): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { pressed = hit(e.x, e.y); downY = e.y; invalidate() }
                MotionEvent.ACTION_UP -> {
                    val dy = e.y - downY
                    if (abs(dy) > 40 * d) {
                        // 上下滑翻页
                        page = (page + if (dy < 0) 1 else -1).coerceIn(0, pages - 1)
                    } else when (val p = pressed) {
                        -10 -> if (locked) onDel() else onLock()
                        -11 -> page = max(0, page - 1)
                        -12 -> page = min(pages - 1, page + 1)
                        -13 -> onBack()
                        else -> items.getOrNull(p)?.let(onSym)
                    }
                    pressed = -1
                    invalidate()
                }
                MotionEvent.ACTION_MOVE -> if (abs(e.y - downY) > slop && pressed != -1) { pressed = -1; invalidate() }
                MotionEvent.ACTION_CANCEL -> { pressed = -1; invalidate() }
            }
            return true
        }
    }
}

/**
 * 排列：工具栏（最多 6 个）和菜单两组，长按一个拖到想要的位置；拖进工具栏满了，挤出最后一个到菜单最前。
 */
class ArrangeView(
    context: Context,
    private val skin: Skin,
    tools: List<String>,
    menu: List<String>,
    private val labelFg: Int = skin.candFg,
    private val subFg: Int = skin.barFg,
    private val onChange: (List<String>, List<String>) -> Unit,
) : View(context) {
    private val d = resources.displayMetrics.density
    private val icons = Icons(context)
    private val tools = tools.toMutableList()
    private val menu = menu.toMutableList()
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val head = Paint(Paint.ANTI_ALIAS_FLAG)
    private val r = RectF()
    private val cols = 4
    private val tileH get() = 66 * d
    private val headH get() = 34 * d
    private val handler = Handler(Looper.getMainLooper())
    private val slop = ViewConfiguration.get(context).scaledTouchSlop

    private var drag: String? = null
    private var dragX = 0f
    private var dragY = 0f
    private var downX = 0f
    private var downY = 0f
    private var downItem: String? = null

    private fun rowsOf(n: Int) = max(1, ceil(n / cols.toFloat()).toInt())
    private val toolsTop get() = headH
    private val menuHeadTop get() = toolsTop + rowsOf(Prefs.MAX_TOOLS) * tileH
    private val menuTop get() = menuHeadTop + headH

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), (menuTop + rowsOf(menu.size + 1) * tileH + 10 * d).toInt())
    }

    private fun cellRect(section: Int, i: Int): RectF {
        val cw = width / cols.toFloat()
        val top = if (section == 0) toolsTop else menuTop
        val x = (i % cols) * cw
        val y = top + (i / cols) * tileH
        return RectF(x, y, x + cw, y + tileH)
    }

    override fun onDraw(c: Canvas) {
        head.textSize = 13 * d
        head.typeface = Typeface.DEFAULT_BOLD
        head.color = subFg
        c.drawText("工具栏（${tools.size}/${Prefs.MAX_TOOLS}）　长按拖动排序，拖到下面就从工具栏拿掉", 14 * d, toolsTop - 11 * d, head)
        c.drawText("菜单（左上角“更多”里）", 14 * d, menuTop - 11 * d, head)
        for ((i, id) in tools.withIndex()) tile(c, cellRect(0, i), id, id == drag)
        for ((i, id) in menu.withIndex()) tile(c, cellRect(1, i), id, id == drag)
        drag?.let { id ->
            val cw = width / cols.toFloat()
            r.set(dragX - cw / 2, dragY - tileH / 2, dragX + cw / 2, dragY + tileH / 2)
            tile(c, RectF(r), id, false, lifted = true)
        }
    }

    private fun tile(c: Canvas, rc: RectF, id: String, ghost: Boolean, lifted: Boolean = false) {
        val box = 42 * d
        val cx = rc.centerX()
        r.set(cx - box / 2, rc.top + 4 * d, cx + box / 2, rc.top + 4 * d + box)
        fill.color = if (lifted) skin.accBg else skin.keyBg
        fill.alpha = if (ghost) 70 else 255
        c.drawRoundRect(r, 13 * d, 13 * d, fill)
        fill.alpha = 255
        icons.draw(c, Prefs.ICONS[id] ?: R.drawable.ic_settings, cx, r.centerY(), 22f, if (lifted) skin.accFg else withAlpha(skin.keyFg, if (ghost) 80 else 255))
        text.textSize = 11.5f * d
        text.color = withAlpha(labelFg, if (ghost) 80 else 255)
        c.drawText(Prefs.NAMES[id] ?: id, cx, r.bottom + 14 * d, text)
    }

    private fun itemAt(x: Float, y: Float): String? {
        for ((i, id) in tools.withIndex()) if (cellRect(0, i).contains(x, y)) return id
        for ((i, id) in menu.withIndex()) if (cellRect(1, i).contains(x, y)) return id
        return null
    }

    private val longTask = Runnable {
        val id = downItem ?: return@Runnable
        drag = id
        dragX = downX; dragY = downY
        parent?.requestDisallowInterceptTouchEvent(true)
        performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
        invalidate()
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = e.x; downY = e.y
                downItem = itemAt(e.x, e.y)
                if (downItem != null) handler.postDelayed(longTask, 380)
            }
            MotionEvent.ACTION_MOVE -> {
                if (drag != null) { dragX = e.x; dragY = e.y; invalidate() }
                else if (abs(e.x - downX) > slop || abs(e.y - downY) > slop) handler.removeCallbacks(longTask)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                handler.removeCallbacks(longTask)
                val id = drag
                if (id != null && e.actionMasked == MotionEvent.ACTION_UP) drop(id, e.x, e.y)
                drag = null
                invalidate()
            }
        }
        return true
    }

    private fun drop(id: String, x: Float, y: Float) {
        val cw = width / cols.toFloat()
        val col = (x / cw).toInt().coerceIn(0, cols - 1)
        tools.remove(id); menu.remove(id)
        if (y < menuHeadTop) {
            val row = ((y - toolsTop) / tileH).toInt().coerceAtLeast(0)
            tools.add((row * cols + col).coerceIn(0, tools.size), id)
            while (tools.size > Prefs.MAX_TOOLS) menu.add(0, tools.removeAt(tools.lastIndex))
        } else {
            val row = ((y - menuTop) / tileH).toInt().coerceAtLeast(0)
            menu.add((row * cols + col).coerceIn(0, menu.size), id)
        }
        requestLayout()
        onChange(tools.toList(), menu.toList())
    }
}

/** 键盘布局选择：26 键、14 键、九键 */
class LayoutPicker(context: Context, private val skin: Skin, private val current: String, private val onPick: (String) -> Unit) : View(context) {
    private val d = resources.displayMetrics.density
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val opts = listOf("26" to "26 键", "14" to "14 键", "9" to "九键")
    private val cards = ArrayList<RectF>()
    private val r = RectF()

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        cards.clear()
        val pad = 12 * d
        val gap = 12 * d
        val cw = (w - 2 * pad - 2 * gap) / 3
        val ch = min(h - 70 * d, cw * 0.85f)
        for (i in 0 until 3) cards.add(RectF(pad + i * (cw + gap), 14 * d, pad + i * (cw + gap) + cw, 14 * d + ch))
    }

    override fun onDraw(c: Canvas) {
        for ((i, card) in cards.withIndex()) {
            val (id, name) = opts[i]
            val sel = id == current
            fill.color = skin.kbBg
            c.drawRoundRect(card, 12 * d, 12 * d, fill)
            stroke.strokeWidth = (if (sel) 2.5f else 1f) * d
            stroke.color = if (sel) skin.candHi else withAlpha(skin.candFg, 50)
            c.drawRoundRect(card, 12 * d, 12 * d, stroke)
            val rows = when (id) { "26" -> listOf(10, 9, 9, 6); "14" -> listOf(5, 5, 6, 6); else -> listOf(5, 5, 5, 4) }
            val p = card.width() * 0.08f
            val g = card.width() * 0.03f
            val rh = (card.height() - 2 * p - 3 * g) / 4
            for ((ri, n) in rows.withIndex()) {
                val kw = (card.width() - 2 * p - (n - 1) * g) / n
                for (k in 0 until n) {
                    val x = card.left + p + k * (kw + g)
                    val y = card.top + p + ri * (rh + g)
                    fill.color = if (ri == 3 || (id == "9" && (k == 0 || k == n - 1))) skin.modBg else skin.keyBg
                    r.set(x, y, x + kw, y + rh)
                    c.drawRoundRect(r, 2 * d, 2 * d, fill)
                }
            }
            text.textSize = 15 * d
            text.color = if (sel) skin.candHi else skin.candFg
            text.typeface = if (sel) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
            c.drawText(name, card.centerX(), card.bottom + 24 * d, text)
        }
        text.textSize = 12 * d
        text.typeface = Typeface.DEFAULT
        text.color = skin.barFg
        c.drawText("只影响中文；英文一直是 26 键", width / 2f, height - 12 * d, text)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (e.actionMasked == MotionEvent.ACTION_UP) {
            cards.forEachIndexed { i, card -> if (e.x in card.left..card.right && e.y < card.bottom + 34 * d) onPick(opts[i].first) }
        }
        return true
    }
}

/**
 * 键盘调节：盖在键盘上，拖动顶上的手柄改变高度（实时生效），下面可以调震动和按键音
 */
class ResizeOverlay(
    context: Context,
    private val skin: Skin,
    private val scale: Float,
    private val vib: Int,
    private val sound: Boolean,
    private val onScale: (Float) -> Unit,
    private val onVib: (Int) -> Unit,
    private val onSound: (Boolean) -> Unit,
    private val onDone: () -> Unit,
) : LinearLayout(context) {
    private val d = resources.displayMetrics.density
    private var startY = 0f
    private var startScale = scale
    private var cur = scale
    private val label = TextView(context)

    init {
        orientation = VERTICAL
        setBackgroundColor(withAlpha(skin.kbBg, 225))
        val handle = object : View(context) {
            private val fill = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
            override fun onDraw(c: Canvas) {
                fill.color = skin.candHi
                val w = 64 * d
                c.drawRoundRect(RectF(width / 2f - w / 2, 10 * d, width / 2f + w / 2, 16 * d), 3 * d, 3 * d, fill)
            }

            @SuppressLint("ClickableViewAccessibility")
            override fun onTouchEvent(e: MotionEvent): Boolean {
                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN -> { startY = e.rawY; startScale = cur; parent?.requestDisallowInterceptTouchEvent(true) }
                    MotionEvent.ACTION_MOVE -> {
                        // 往上拖变高：每拖 220dp 约等于高度翻一倍的变化
                        cur = (startScale + (startY - e.rawY) / (220 * d)).coerceIn(0.7f, 1.5f)
                        label.text = text()
                        onScale(cur)
                    }
                }
                return true
            }
        }
        addView(handle, lp(MATCH, (40 * d).toInt()))
        label.text = text()
        label.textSize = 14f
        label.gravity = Gravity.CENTER
        label.setTextColor(skin.candFg)
        addView(label, lp(MATCH, WRAP))
        val row1 = LinearLayout(context).apply { orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding((14 * d).toInt(), (10 * d).toInt(), (14 * d).toInt(), 0) }
        row1.addView(TextView(context).apply { text = "震动"; textSize = 14f; setTextColor(skin.candFg) }, lp(0, WRAP, 1f))
        row1.addView(segmented(context, skin, listOf("关", "弱", "中", "强"), vib) { onVib(it) }, lp(WRAP, WRAP))
        addView(row1, lp(MATCH, WRAP))
        val row2 = LinearLayout(context).apply { orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding((14 * d).toInt(), (8 * d).toInt(), (14 * d).toInt(), 0) }
        row2.addView(TextView(context).apply { text = "按键音"; textSize = 14f; setTextColor(skin.candFg) }, lp(0, WRAP, 1f))
        row2.addView(ToggleView(context, skin, sound) { onSound(it) }, lp(WRAP, WRAP))
        addView(row2, lp(MATCH, WRAP))
        val row3 = LinearLayout(context).apply { orientation = HORIZONTAL; gravity = Gravity.CENTER; setPadding((14 * d).toInt(), (10 * d).toInt(), (14 * d).toInt(), (8 * d).toInt()) }
        fun btn(t: String, strong: Boolean, f: () -> Unit) = row3.addView(TextView(context).apply {
            text = t
            textSize = 14f
            gravity = Gravity.CENTER
            setTextColor(if (strong) skin.accFg else skin.modFg)
            background = roundBg(if (strong) skin.accBg else skin.modBg, 18 * d)
            setPadding(0, (8 * d).toInt(), 0, (8 * d).toInt())
            setOnClickListener { f() }
        }, lp(0, WRAP, 1f).apply { marginEnd = (8 * d).toInt() })
        btn("恢复默认高度", false) { cur = 1f; label.text = text(); onScale(1f) }
        btn("完成", true) { onDone() }
        addView(row3, lp(MATCH, WRAP))
    }

    private fun text() = "拖动最上面的手柄调高度：${(cur * 100).toInt()}%"
}
