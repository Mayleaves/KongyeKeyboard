package com.kongye.keyboard

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
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
        /** 候选栏剪贴板快捷条：点击粘贴、分词、关闭 */
        fun onClipPaste() {}
        fun onClipSplit() {}
        fun onClipDismiss() {}
        /** 长按工具栏的“编辑”后左右滑：每一步移动一格光标 */
        fun onToolCursor(dir: Int) {}
    }

    enum class Mode { TOOLS, CANDS, ASSOC, MESSAGE, CLIP_SUGGEST, ACTIONS }

    var onLongCandidate: (String?) -> Unit = {}
    var listener: Listener? = null
    var skin: Skin = Skins.KONGYE
        set(v) { field = v; invalidate() }
    var cursorMode: Boolean = false
        set(v) { if (field != v) { field = v; invalidate() } }

    private var actionPick: (Int) -> Unit = {}
    private var actionClose: () -> Unit = {}
    private var mode = Mode.TOOLS
    private var items: List<CandItem> = emptyList()
    private var starts = FloatArray(0)
    private var widths = FloatArray(0)
    private var tools: List<String> = emptyList()
    private var activeTool: String? = null
    private var message = ""
    private var clipSuggest = ""
    private var clipPressedZone = 0 // 1: 粘贴胶囊, 2: 分词, 3: 关闭
    private var expanded = false
    private var contentW = 0f
    private var scrollPos = 0f

    var uiScale = 1f
        set(v) { if(field!=v) {field=v;icons.scale=v;measuredItems.clear();layoutItems();requestLayout();invalidate() } }
    private val d get() = resources.displayMetrics.density * uiScale
    private val icons = Icons(context)
    private val scroller = OverScroller(context)
    private var vt: VelocityTracker? = null
    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private var downX = 0f
    private var startScroll = 0f
    private var dragging = false
    private var pressed = -1
    /** 长按“编辑”工具后进入的左右滑动移光标状态 */
    private var toolDrag = false
    private var toolDragAcc = 0f
    private var toolDragLast = 0f
    private val toolLong = Runnable {
        toolDrag = true
        toolDragAcc = 0f
        toolDragLast = downX
        performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
        parent?.requestDisallowInterceptTouchEvent(true)
        invalidate()
    }

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.LEFT }
    private val small = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.LEFT }
    private val rect = RectF()
    private val red = Color.parseColor("#E5484D")

    private val measuredItems=object:LinkedHashMap<String,Float>(512,.75f,true) {
        override fun removeEldestEntry(eldest:MutableMap.MutableEntry<String,Float>?)=size>512
    }
    private val pad get() = 12 * d
    private val expandW get() = if (mode == Mode.CANDS || mode == Mode.ASSOC || mode == Mode.ACTIONS) 44 * d else 0f

    fun showTools(t: List<String>, active: String? = null) {
        contentDescription=null
        mode = Mode.TOOLS; tools = t; activeTool = active; onLongCandidate(null); invalidate()
    }

    fun showCandidates(list: List<CandItem>, expanded: Boolean) {
        val visible=list
        val same=mode==Mode.CANDS && this.expanded==expanded && items.size==visible.size && items.indices.all { i ->
            items[i].w==visible[i].w && items[i].py==visible[i].py && items[i].mask.contentEquals(visible[i].mask)
        }
        if(same) return
        contentDescription=null
        mode=Mode.CANDS;items=visible;this.expanded=expanded
        layoutItems();resetScroll();updateLongCandidate();invalidate()
        animate().cancel();alpha=1f
    }

    fun showAssoc(list: List<String>) {
        contentDescription=null
        mode = Mode.ASSOC; items = list.map { CandItem(it) };onLongCandidate(null)
        layoutItems(); resetScroll(); invalidate()
    }

    fun showActions(labels: List<String>, pick: (Int) -> Unit, close: () -> Unit) {
        val same = mode == Mode.ACTIONS && items.map { it.w } == labels
        contentDescription=labels.joinToString("；")
        actionPick = pick; actionClose = close; mode = Mode.ACTIONS
        items = labels.map { CandItem(it) }; onLongCandidate(null)
        if (!same) { layoutItems(); resetScroll() }; invalidate()
    }

    fun showMessage(msg: String) {
        contentDescription=msg
        mode = Mode.MESSAGE; message = msg;onLongCandidate(null); invalidate()
    }

    fun showClipSuggestion(text: String) {
        contentDescription=null
        mode = Mode.CLIP_SUGGEST;onLongCandidate(null); clipSuggest = text; clipPressedZone = 0; invalidate()
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
            text.typeface = Typeface.DEFAULT
            val key=it.w+"\u0000"+it.py.orEmpty()
            val w=measuredItems.getOrPut(key) {text.measureText(it.w)+2*pad+(it.py?.let {py->4*d+small.measureText(py)} ?: 0f)}
            starts[i] = x
            widths[i] = w
            x += w
        }
        contentW = x + 4 * d
    }

    private fun updateLongCandidate() {
        text.textSize=17*d
        val word=items.firstOrNull()?.w
        onLongCandidate(word?.takeIf {mode==Mode.CANDS && it.codePointCount(0,it.length)>4})
    }
    override fun onSizeChanged(w:Int,h:Int,oldw:Int,oldh:Int) {
        super.onSizeChanged(w,h,oldw,oldh);updateLongCandidate()
    }

    private fun maxScroll() = max(0f, contentW - (width - expandW))

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), (44 * d).toInt())
    }

    override fun onDraw(c: Canvas) {
        if (toolDrag) { drawToolDrag(c); return }
        when (mode) {
            Mode.TOOLS -> drawTools(c)
            Mode.CANDS, Mode.ASSOC, Mode.ACTIONS -> drawItems(c)
            Mode.CLIP_SUGGEST -> drawClipSuggest(c)
            Mode.MESSAGE -> {
                text.textSize = 13 * d
                text.color = skin.barFg
                text.typeface = Typeface.DEFAULT
                val fm = text.fontMetrics
                c.drawText(message, 16 * d, height / 2f - (fm.ascent + fm.descent) / 2, text)
            }
        }
    }

    private fun clipCloseX() = width - 40 * d
    private fun clipSplitLeft() = clipCloseX() - 44 * d

    /** 复制快捷条：左边一条浅色胶囊（剪贴板图标 + 一行内容，点一下粘贴），右边分词图标按钮和关闭 */
    private fun drawClipSuggest(c: Canvas) {
        val h = height.toFloat()
        val cy = h / 2f
        val pillLeft = 8 * d
        val pillRight = clipSplitLeft() - 6 * d
        fill.color = if (clipPressedZone == 1) skin.keyDown else skin.keyBg
        rect.set(pillLeft, 7 * d, pillRight, h - 7 * d)
        c.drawRoundRect(rect, 15 * d, 15 * d, fill)
        icons.draw(c, R.drawable.ic_clip, pillLeft + 18 * d, cy, 17f, skin.barFg)
        text.textSize = 14.5f * d
        text.typeface = Typeface.DEFAULT
        text.color = skin.candFg
        val clean = clipSuggest.replace('\n', ' ').replace('\r', ' ').trim()
        val left = pillLeft + 34 * d
        val availW = max(10f, pillRight - left - 12 * d)
        val shown = android.text.TextUtils.ellipsize(clean, android.text.TextPaint(text), availW, android.text.TextUtils.TruncateAt.END).toString()
        val fm = text.fontMetrics
        c.drawText(shown, left, cy - (fm.ascent + fm.descent) / 2, text)
        // 分词：圆形图标按钮
        val sx = clipSplitLeft() + 22 * d
        fill.color = if (clipPressedZone == 2) skin.keyDown else skin.modBg
        c.drawCircle(sx, cy, 15 * d, fill)
        icons.draw(c, R.drawable.ic_split, sx, cy, 17f, skin.modFg)
        if (clipPressedZone == 3) {
            fill.color = skin.keyDown
            c.drawCircle(width - 20 * d, cy, 14 * d, fill)
        }
        icons.draw(c, R.drawable.ic_close, width - 20 * d, cy, 17f, skin.barFg)
    }

    /** 长按编辑工具后：整条变成一个提示，左右滑移动光标 */
    private fun drawToolDrag(c: Canvas) {
        val h = height.toFloat()
        fill.color = withAlpha(skin.barFg, if (skin.dark) 32 else 18)
        rect.set(10 * d, 7 * d, width - 10 * d, h - 7 * d)
        c.drawRoundRect(rect, 15 * d, 15 * d, fill)
        val fg = skin.barFg
        icons.draw(c, R.drawable.ic_chevron_left, 30 * d, h / 2, 20f, fg)
        icons.draw(c, R.drawable.ic_chevron_right, width - 30 * d, h / 2, 20f, fg)
        icons.draw(c, R.drawable.ic_cursor, width / 2f, h / 2, 21f, fg)
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
            val it = items[i]
            val hi = mode == Mode.CANDS && i == 0
            val kongyeHi = hi && skin.style == SkinStyle.KONGYE
            rect.set(x + 3 * d, 7 * d, x + w - 3 * d, h - 7 * d)
            // 只有第一个候选有底色（空叶是朱红底白字，其他配色是主题浅色底）；按下的候选给一点按下色
            if (kongyeHi) {
                fill.color = if (i == pressed) darken(Color.rgb(240, 73, 44), 0.88f) else Color.rgb(240, 73, 44)
                c.drawRoundRect(rect, 11 * d, 11 * d, fill)
            } else if (hi || i == pressed) {
                fill.color = if (i == pressed) skin.keyDown else if (skin.toolHiBg != 0) skin.toolHiBg else skin.modBg
                c.drawRoundRect(rect, 11 * d, 11 * d, fill)
            }
            text.color = if (kongyeHi) Color.WHITE else skin.candFg
            text.typeface = Typeface.DEFAULT
            text.textSize = 17 * d
            c.drawText(it.w, x + pad, base, text)
            val py = it.py ?: continue
            // 拼音：打错的字母标红
            var px = x + pad + text.measureText(it.w) + 4 * d
            small.textSize = 11.5f * d
            // Draw tone marks as a shaped string (including combining marks).
            if (it.mask == null) {
                small.color = withAlpha(if (kongyeHi) Color.WHITE else skin.barFg, 200)
                c.drawText(py, px, base, small)
                continue
            }
            for ((j, ch) in py.withIndex()) {
                val err = it.mask?.getOrNull(j) == true
                small.color = when {
                    kongyeHi && err -> Color.parseColor("#FFE066")
                    kongyeHi -> withAlpha(Color.WHITE, 200)
                    err -> red
                    else -> withAlpha(skin.barFg, 200)
                }
                val s = ch.toString()
                c.drawText(s, px, base, small)
                px += small.measureText(s)
            }
        }
        c.restore()
        if (expandW > 0) {
            fill.color = skin.kbBg
            c.drawRect(viewW, 0f, width.toFloat(), h, fill)
            val ic = when {
                mode == Mode.ASSOC || mode == Mode.ACTIONS -> R.drawable.ic_close
                expanded -> R.drawable.ic_chevron_up
                else -> R.drawable.ic_chevron_down
            }
            icons.draw(c, ic, viewW + expandW / 2, h / 2, if (mode == Mode.ASSOC || mode == Mode.ACTIONS) 18f else 22f, skin.barFg)
        }
    }

    private fun indexAt(x: Float): Int {
        for (i in items.indices) if (x >= starts[i] && x < starts[i] + widths[i]) return i
        return -1
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        val scrollable = mode == Mode.CANDS || mode == Mode.ASSOC || mode == Mode.ACTIONS
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = e.x
                startScroll = scrollPos
                dragging = false
                scroller.forceFinished(true)
                vt?.recycle()
                vt = VelocityTracker.obtain().also { it.addMovement(e) }
                pressed = if (scrollable && e.x < width - expandW) indexAt(e.x + scrollPos) else -1
                toolDrag = false
                removeCallbacks(toolLong)
                if (mode == Mode.TOOLS && !cursorMode && tools.isNotEmpty()) {
                    val p = 6 * d
                    val i = ((e.x - p) / ((width - 2 * p) / tools.size)).toInt().coerceIn(0, tools.size - 1)
                    if (tools[i] == "edit") postDelayed(toolLong, 380)
                }
                clipPressedZone = if (mode == Mode.CLIP_SUGGEST) when {
                    e.x >= clipCloseX() -> 3
                    e.x >= clipSplitLeft() -> 2
                    else -> 1
                } else 0
                invalidate()
            }
            MotionEvent.ACTION_MOVE -> {
                vt?.addMovement(e)
                if (toolDrag) {
                    toolDragAcc += e.x - toolDragLast
                    toolDragLast = e.x
                    val step = 9 * d
                    while (abs(toolDragAcc) >= step) {
                        val dir = if (toolDragAcc > 0) 1 else -1
                        listener?.onToolCursor(dir)
                        toolDragAcc -= dir * step
                    }
                    return true
                }
                if (abs(e.x - downX) > slop) removeCallbacks(toolLong)
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
                removeCallbacks(toolLong)
                if (toolDrag) {
                    toolDrag = false
                    vt?.recycle()
                    vt = null
                    invalidate()
                    return true
                }
                if (dragging) {
                    vt?.computeCurrentVelocity(1000)
                    val v = -(vt?.xVelocity ?: 0f)
                    scroller.fling(scrollPos.toInt(), 0, v.toInt(), 0, 0, maxScroll().toInt(), 0, 0)
                    postInvalidateOnAnimation()
                } else tap(e.x)
                pressed = -1
                clipPressedZone = 0
                vt?.recycle()
                vt = null
                invalidate()
            }
            MotionEvent.ACTION_CANCEL -> {
                removeCallbacks(toolLong)
                toolDrag = false
                pressed = -1
                clipPressedZone = 0
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
            Mode.ACTIONS -> {
                if (x >= width - expandW) actionClose()
                else indexAt(x + scrollPos).takeIf { it >= 0 }?.let(actionPick)
            }
            Mode.CLIP_SUGGEST -> when {
                x >= clipCloseX() -> listener?.onClipDismiss()
                x >= clipSplitLeft() -> listener?.onClipSplit()
                else -> listener?.onClipPaste()
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
