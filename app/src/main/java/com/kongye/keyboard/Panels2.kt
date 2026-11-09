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
