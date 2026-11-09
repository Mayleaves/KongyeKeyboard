package com.kongye.keyboard

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.widget.OverScroller
import kotlin.math.abs
import kotlin.math.max

/** 候选栏的一项：词，可选的拼音（音节空格隔开）和标红的字母 */
class CandItem(val w: String, val py: String? = null, val mask: BooleanArray? = null)

/**
 * 键盘顶上那一条：空闲时是工具栏（打开面板时那个工具高亮，再点一下收起），
 * 打拼音时是候选词（可横滑，纠错的词后面带拼音、打错的字母标红，右边有展开按钮），上屏后是联想词。
 */
class CandidateBar(context: Context) : View(context) {

    interface Listener {
        fun onCandidate(index: Int)
        fun onAssoc(index: Int)
        fun onTool(id: String)
        fun onExpand()
        /** 联想栏右端的 ×：收起联想，回到工具栏 */
        fun onDismissAssoc()
    }

    enum class Mode { TOOLS, CANDS, ASSOC, MESSAGE }

    var listener: Listener? = null
    var skin: Skin = Skins.KONGYE
        set(v) { field = v; invalidate() }

    private var mode = Mode.TOOLS
    private var items: List<CandItem> = emptyList()
    private var starts = FloatArray(0)
    private var widths = FloatArray(0)
    private var tools: List<String> = emptyList()
    private var activeTool: String? = null
    private var message = ""
    private var expanded = false
    private var contentW = 0f
    private var scrollPos = 0f

    private val d = resources.displayMetrics.density
    private val icons = Icons(context)
    private val scroller = OverScroller(context)
    private var vt: VelocityTracker? = null
    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private var downX = 0f
    private var startScroll = 0f
    private var dragging = false
    private var pressed = -1

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.LEFT }
    private val small = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.LEFT }
    private val rect = RectF()
    private val red = Color.parseColor("#E5484D")

    private val pad get() = 12 * d
    private val expandW get() = if (mode == Mode.CANDS || mode == Mode.ASSOC) 44 * d else 0f

    fun showTools(t: List<String>, active: String? = null) {
        mode = Mode.TOOLS; tools = t; activeTool = active; invalidate()
    }

    fun showCandidates(list: List<CandItem>, expanded: Boolean) {
        mode = Mode.CANDS; items = list; this.expanded = expanded
        layoutItems(); resetScroll(); invalidate()
    }

    fun showAssoc(list: List<String>) {
        mode = Mode.ASSOC; items = list.map { CandItem(it) }
        layoutItems(); resetScroll(); invalidate()
    }

    fun showMessage(msg: String) {
        mode = Mode.MESSAGE; message = msg; invalidate()
    }

    private fun resetScroll() {
        scroller.forceFinished(true)
        scrollPos = 0f
    }

    private fun layoutItems() {
        text.textSize = 17 * d
        small.textSize = 11.5f * d
        starts = FloatArray(items.size)
        widths = FloatArray(items.size)
        var x = 4 * d
        for ((i, it) in items.withIndex()) {
            text.typeface = if (i == 0 && mode == Mode.CANDS) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
            var w = text.measureText(it.w) + 2 * pad
            if (it.py != null) w += 4 * d + small.measureText(it.py)
            starts[i] = x
            widths[i] = w
            x += w
        }
        contentW = x + 4 * d
    }

    private fun maxScroll() = max(0f, contentW - (width - expandW))

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), (44 * d).toInt())
    }

    override fun onDraw(c: Canvas) {
        when (mode) {
            Mode.TOOLS -> drawTools(c)
            Mode.CANDS, Mode.ASSOC -> drawItems(c)
            Mode.MESSAGE -> {
                text.textSize = 13 * d
                text.color = skin.barFg
                text.typeface = Typeface.DEFAULT
                val fm = text.fontMetrics
                c.drawText(message, 16 * d, height / 2f - (fm.ascent + fm.descent) / 2, text)
            }
        }
    }

    private fun drawTools(c: Canvas) {
        if (tools.isEmpty()) return
        val p = 6 * d
        val slot = (width - 2 * p) / tools.size
        val cy = height / 2f
        for ((i, t) in tools.withIndex()) {
            val cx = p + slot * i + slot / 2
            var tint = skin.barFg
            if (t == activeTool) {
                fill.color = if (skin.toolHiBg != 0) skin.toolHiBg else skin.modBg
                rect.set(cx - 22 * d, cy - 15 * d, cx + 22 * d, cy + 15 * d)
                c.drawRoundRect(rect, 15 * d, 15 * d, fill)
                tint = if (skin.toolHiFg != 0) skin.toolHiFg else skin.candFg
            }
            icons.draw(c, Prefs.ICONS[t] ?: R.drawable.ic_settings, cx, cy, 21f, tint)
        }
    }

    private fun drawItems(c: Canvas) {
        val viewW = width - expandW
        val h = height.toFloat()
        c.save()
        c.clipRect(0f, 0f, viewW, h)
        c.translate(-scrollPos, 0f)
        text.textSize = 17 * d
        val fm = text.fontMetrics
        val base = h / 2 - (fm.ascent + fm.descent) / 2
        for (i in items.indices) {
            val x = starts[i]
            val w = widths[i]
            if (x + w < scrollPos || x > scrollPos + viewW) continue
            if (i == pressed) {
                fill.color = skin.keyDown
                rect.set(x + 2 * d, 5 * d, x + w - 2 * d, h - 5 * d)
                c.drawRoundRect(rect, 9 * d, 9 * d, fill)
            }
            val it = items[i]
            val hi = mode == Mode.CANDS && i == 0
            text.color = if (hi) skin.candHi else skin.candFg
            text.typeface = if (hi) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
            text.textSize = 17 * d
            c.drawText(it.w, x + pad, base, text)
            val py = it.py ?: continue
            // 拼音：打错的字母标红
            var px = x + pad + text.measureText(it.w) + 4 * d
            small.textSize = 11.5f * d
            for ((j, ch) in py.withIndex()) {
                small.color = if (it.mask?.getOrNull(j) == true) red else skin.barFg
                val s = ch.toString()
                c.drawText(s, px, base, small)
                px += small.measureText(s)
            }
        }
        c.restore()
        if (expandW > 0) {
            val fadeW = 18 * d
            val clear = skin.kbBg and 0x00FFFFFF
            fill.shader = LinearGradient(viewW - fadeW, 0f, viewW, 0f, clear, skin.kbBg, Shader.TileMode.CLAMP)
            c.drawRect(viewW - fadeW, 0f, viewW, h, fill)
            fill.shader = null
            fill.color = skin.kbBg
            c.drawRect(viewW, 0f, width.toFloat(), h, fill)
            val ic = when {
                mode == Mode.ASSOC -> R.drawable.ic_close
                expanded -> R.drawable.ic_chevron_up
                else -> R.drawable.ic_chevron_down
            }
            icons.draw(c, ic, viewW + expandW / 2, h / 2, if (mode == Mode.ASSOC) 18f else 22f, skin.barFg)
        }
    }

    private fun indexAt(x: Float): Int {
        for (i in items.indices) if (x >= starts[i] && x < starts[i] + widths[i]) return i
        return -1
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        val scrollable = mode == Mode.CANDS || mode == Mode.ASSOC
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = e.x
                startScroll = scrollPos
                dragging = false
                scroller.forceFinished(true)
                vt?.recycle()
                vt = VelocityTracker.obtain().also { it.addMovement(e) }
                pressed = if (scrollable && e.x < width - expandW) indexAt(e.x + scrollPos) else -1
                invalidate()
            }
            MotionEvent.ACTION_MOVE -> {
                vt?.addMovement(e)
                val dx = e.x - downX
                if (!dragging && scrollable && abs(dx) > slop) {
                    dragging = true
                    pressed = -1
                    parent?.requestDisallowInterceptTouchEvent(true)
                }
                if (dragging) {
                    scrollPos = (startScroll - dx).coerceIn(0f, maxScroll())
                    invalidate()
                }
            }
            MotionEvent.ACTION_UP -> {
                vt?.addMovement(e)
                if (dragging) {
                    vt?.computeCurrentVelocity(1000)
                    val v = -(vt?.xVelocity ?: 0f)
                    scroller.fling(scrollPos.toInt(), 0, v.toInt(), 0, 0, maxScroll().toInt(), 0, 0)
                    postInvalidateOnAnimation()
                } else tap(e.x)
                pressed = -1
                vt?.recycle()
                vt = null
                invalidate()
            }
            MotionEvent.ACTION_CANCEL -> {
                pressed = -1
                dragging = false
                vt?.recycle()
                vt = null
                invalidate()
            }
        }
        return true
    }

    private fun tap(x: Float) {
        when (mode) {
            Mode.TOOLS -> {
                if (tools.isEmpty()) return
                val p = 6 * d
                val i = ((x - p) / ((width - 2 * p) / tools.size)).toInt().coerceIn(0, tools.size - 1)
                listener?.onTool(tools[i])
            }
            Mode.CANDS -> {
                if (x >= width - expandW) listener?.onExpand()
                else indexAt(x + scrollPos).takeIf { it >= 0 }?.let { listener?.onCandidate(it) }
            }
            Mode.ASSOC -> {
                if (x >= width - expandW) listener?.onDismissAssoc()
                else indexAt(x + scrollPos).takeIf { it >= 0 }?.let { listener?.onAssoc(it) }
            }
            Mode.MESSAGE -> Unit
        }
    }

    override fun computeScroll() {
        if (scroller.computeScrollOffset()) {
            scrollPos = scroller.currX.toFloat()
            postInvalidateOnAnimation()
        }
    }
}
