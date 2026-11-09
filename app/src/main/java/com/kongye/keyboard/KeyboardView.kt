package com.kongye.keyboard

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** 颜色压暗一点，用作按下态 */
fun darken(c: Int, f: Float = 0.88f): Int =
    Color.argb(Color.alpha(c), (Color.red(c) * f).toInt(), (Color.green(c) * f).toInt(), (Color.blue(c) * f).toInt())

/** 两种颜色按比例混合 */
fun mix(a: Int, b: Int, t: Float): Int = Color.rgb(
    (Color.red(a) + (Color.red(b) - Color.red(a)) * t).toInt(),
    (Color.green(a) + (Color.green(b) - Color.green(a)) * t).toInt(),
    (Color.blue(a) + (Color.blue(b) - Color.blue(a)) * t).toInt(),
)

fun withAlpha(c: Int, a: Int) = Color.argb(a, Color.red(c), Color.green(c), Color.blue(c))

/** 图标缓存与绘制，键盘、候选栏和面板共用 */
class Icons(private val ctx: Context) {
    private val cache = HashMap<Int, Drawable>()
    private val d = ctx.resources.displayMetrics.density

    fun draw(c: Canvas, id: Int, cx: Float, cy: Float, sizeDp: Float, tint: Int?) {
        val dr = cache.getOrPut(id) { ctx.getDrawable(id)!!.mutate() }
        val s = (sizeDp * d).toInt()
        val l = (cx - s / 2f).toInt()
        val t = (cy - s / 2f).toInt()
        dr.setBounds(l, t, l + s, t + s)
        if (tint != null) dr.setTint(tint) else dr.setTintList(null)
        dr.draw(c)
    }
}

class KeyboardView(context: Context) : View(context) {

    interface Listener {
        fun onKey(key: Key)
        fun onLongKey(key: Key)
        fun onPress(key: Key)
        /** 移动光标：dx 左右一步，dy 上下一步 */
        fun onCursorMove(dx: Int, dy: Int)
        /** 删除键下滑清空（down=true），上滑撤回 */
        fun onSwipeDel(down: Boolean)
    }

    var listener: Listener? = null
    /** 设置页里的预览：只画，不响应触摸 */
    var preview = false
    var skin: Skin = Skins.KONGYE
        private set
    private var rows: List<Row> = emptyList()
    var shiftState = 0
    var zh = true
    /** 中文组字时大写键变成“分词” */
    var splitMode = false
    var swipeEnabled = true
    var enterIcon = R.drawable.ic_enter
    var heightScale = 1f
        set(v) { if (v != field) { field = v; requestLayout() } }
    var longMs = 360L

    /** 数字键盘、九键左边那一列可滚动的符号 */
    private var side: List<String>? = null
    private val sideRect = RectF()
    private var sideScroll = 0f
    private var sideDownY = 0f
    private var sideStart = 0f
    private var sideDragging = false
    private var sideTouch = false
    private var sidePressed = -1

    private var cursorMode = false

    private val d = resources.displayMetrics.density
    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private val icons = Icons(context)
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val hintPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    private val radii = FloatArray(8)
    private val handler = Handler(Looper.getMainLooper())

    private val padTop get() = 6 * d
    private val padBottom get() = 6 * d
    private fun rowH() = skin.keyH * d * heightScale

    fun setup(skin: Skin, rows: List<Row>, side: List<String>? = null) {
        val relayout = rows.size != this.rows.size || skin.keyH != this.skin.keyH || skin.rowGap != this.skin.rowGap
        this.skin = skin
        this.rows = rows
        if (side != this.side) sideScroll = 0f
        this.side = side
        cancelAll()
        if (relayout) requestLayout()
        layoutKeys()
        invalidate()
    }

    fun desiredHeight(): Int {
        val n = max(4, rows.size)
        return (padTop + padBottom + n * rowH() + (n - 1) * skin.rowGap * d).toInt()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), desiredHeight())
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        layoutKeys()
    }

    private fun layoutKeys() {
        val wv = width.toFloat()
        if (wv <= 0f || rows.isEmpty()) return
        val pad = skin.padH * d
        val gap = skin.keyGap * d
        val rg = skin.rowGap * d
        val rh = rowH()
        val ref = (wv - 2 * pad - 9 * gap) / 10f
        val sideList = side
        val last = rows.last().keys
        val lastUnit = (wv - 2 * pad - (last.size - 1) * gap) / last.sumOf { it.w.toDouble() }.toFloat()
        val sideW = if (sideList != null) last[0].w * lastUnit else 0f
        var y = padTop
        for ((ri, row) in rows.withIndex()) {
            val n = row.keys.size
            val withSide = sideList != null && ri < rows.size - 1
            if (row.indent) {
                val total = row.keys.sumOf { (it.w * ref).toDouble() }.toFloat() + (n - 1) * gap
                var x = (wv - total) / 2f
                for (k in row.keys) { val kw = k.w * ref; k.rect.set(x, y, x + kw, y + rh); x += kw + gap }
            } else if (withSide) {
                var x = pad + sideW + gap
                for (k in row.keys) { val kw = k.w * lastUnit; k.rect.set(x, y, x + kw, y + rh); x += kw + gap }
            } else {
                val sumW = row.keys.sumOf { it.w.toDouble() }.toFloat()
                val unit = (wv - 2 * pad - (n - 1) * gap) / sumW
                var x = pad
                for (k in row.keys) { val kw = k.w * unit; k.rect.set(x, y, x + kw, y + rh); x += kw + gap }
            }
            for ((i, k) in row.keys.withIndex()) {
                val l = if (i == 0) (if (withSide) k.rect.left - gap / 2 else 0f) else k.rect.left - gap / 2
                val r = if (i == n - 1) wv else k.rect.right + gap / 2
                val t = if (ri == 0) 0f else y - rg / 2
                val b = if (ri == rows.size - 1) Float.MAX_VALUE else y + rh + rg / 2
                k.hit.set(l, t, r, b)
            }
            y += rh + rg
        }
        if (sideList != null) {
            sideRect.set(pad, rows[0].keys[0].rect.top, pad + sideW, rows[rows.size - 2].keys[0].rect.bottom)
        }
    }

    private fun findKey(x: Float, y: Float): Key? {
        for (row in rows) for (k in row.keys) if (k.hit.contains(x, y)) return k
        return null
    }

    // —————————— 绘制 ——————————

    override fun onDraw(c: Canvas) {
        val pressMap = HashMap<Key, Press>()
        for (p in presses.values) pressMap[p.key] = p
        for (row in rows) for (k in row.keys) drawKey(c, k, pressMap[k])
        side?.let { drawSide(c, it) }
        if (cursorMode) drawCursorMode(c)
    }

    private fun buildShape(r: RectF) {
        path.reset()
        val big: Float
        val small: Float
        if (skin.leafCorners) { big = skin.radius * d; small = 4 * d } else { big = skin.radius * d; small = big }
        radii[0] = big; radii[1] = big; radii[2] = small; radii[3] = small
        radii[4] = big; radii[5] = big; radii[6] = small; radii[7] = small
        path.addRoundRect(r, radii, Path.Direction.CW)
    }

    private fun drawKey(c: Canvas, k: Key, p: Press?) {
        val s = skin
        val r = k.rect
        val down = p != null && !cursorMode
        val base = if (k.look == Look.MOD) s.modBg else s.keyBg
        val fg = if (k.look == Look.MOD) s.modFg else s.keyFg
        // 按下：底色变深，再描一圈边
        val bg = if (down) (if (k.look == Look.MOD) darken(base, if (s.dark) 1.25f else 0.86f) else s.keyDown) else base
        buildShape(r)
        if (s.keyShadow != 0) {
            c.save(); c.translate(0f, d)
            fill.color = s.keyShadow
            c.drawPath(path, fill)
            c.restore()
        }
        fill.color = bg
        c.drawPath(path, fill)
        if (down) {
            stroke.strokeWidth = 1.6f * d
            stroke.color = withAlpha(fg, 110)
            c.drawPath(path, stroke)
        }
        val cx = r.centerX()
        val cy = r.centerY()
        val prog = p?.prog ?: 0f
        val dir = p?.swipe ?: 0
        when (k.kind) {
            Kind.LETTER -> {
                val label = when {
                    k.label.length > 1 -> k.label.uppercase()
                    shiftState > 0 -> k.label.uppercase()
                    else -> k.label
                }
                val size = if (k.label.length > 2) 17f else if (k.label.length == 2) 19f else s.letterSize
                drawText(c, label, cx, cy + d, size, withAlpha(fg, (255 * (1 - prog)).toInt()))
                drawHints(c, k, r, prog, dir, size)
            }
            Kind.CHAR -> {
                val size = if (k.label.length > 2) 14f else if (r.width() > 60 * d) 24f else 20f
                drawText(c, k.label, cx, cy, size, withAlpha(fg, (255 * (1 - prog)).toInt()))
                drawHints(c, k, r, prog, dir, size)
                if (k.longEmoji) icons.draw(c, R.drawable.ic_emoji, r.right - 9 * d, r.top + 9 * d, 11f, s.hintFg)
            }
            Kind.SHIFT -> if (splitMode) drawText(c, "分词", cx, cy, 15f, fg) else icons.draw(
                c, when (shiftState) { 1 -> R.drawable.ic_shift_on; 2 -> R.drawable.ic_shift_lock; else -> R.drawable.ic_shift },
                cx, cy, 22f, fg,
            )
            Kind.DEL -> icons.draw(c, R.drawable.ic_del, cx, cy, 22f, fg)
            Kind.ENTER -> icons.draw(c, enterIcon, cx, cy, 22f, fg)
            Kind.SPACE -> Unit
            Kind.SYM, Kind.NUM, Kind.ABC, Kind.SEP, Kind.RESET -> {
                drawText(c, k.label, cx, cy, if (k.label.any { it.code > 0x2E80 }) 16f else 15f, fg)
                k.hint?.let { drawHint(c, it, r.right - 5 * d, r.top + 3 * d, Paint.Align.RIGHT, 10f, s.hintFg) }
            }
            Kind.LANG -> drawLang(c, cx, cy, fg)
        }
    }

    /** 两个角标；滑动时被选中的那个随进度移到按键中间、变大 */
    private fun drawHints(c: Canvas, k: Key, r: RectF, prog: Float, dir: Int, bigSize: Float) {
        val topX = r.right - 5 * d
        val topY = r.top + 3 * d
        val botX = r.left + 5 * d
        val botY = r.bottom - 14 * d
        k.hint?.let { h ->
            if (dir > 0 && prog > 0f) moving(c, h, topX, topY, Paint.Align.RIGHT, r, prog, bigSize)
            else drawHint(c, h, topX, topY, Paint.Align.RIGHT, 10f, withAlpha(skin.hintFg, (255 * (1 - prog)).toInt()))
        }
        if (!swipeEnabled) return
        k.hint2?.let { h ->
            if (dir < 0 && prog > 0f) moving(c, h, botX, botY, Paint.Align.LEFT, r, prog, bigSize)
            else drawHint(c, h, botX, botY, Paint.Align.LEFT, 9.5f, withAlpha(skin.hintFg, (255 * (1 - prog)).toInt()))
        }
    }

    private fun moving(c: Canvas, h: String, x0: Float, y0: Float, align: Paint.Align, r: RectF, prog: Float, bigSize: Float) {
        hintPaint.textAlign = align
        hintPaint.textSize = 10 * d
        val w0 = hintPaint.measureText(h)
        val sx = if (align == Paint.Align.RIGHT) x0 - w0 / 2 else x0 + w0 / 2
        val sy = y0 - hintPaint.ascent() / 2
        val x = sx + (r.centerX() - sx) * prog
        val y = sy + (r.centerY() - sy) * prog
        val size = 10f + (bigSize - 10f) * prog
        drawText(c, h, x, y, size, mix(skin.hintFg, skin.candHi, prog), bold = prog > 0.7f)
    }

    private fun drawHint(c: Canvas, h: String, x: Float, top: Float, align: Paint.Align, sizeDp: Float, color: Int) {
        hintPaint.textSize = sizeDp * d
        hintPaint.color = color
        hintPaint.textAlign = align
        c.drawText(h, x, top - hintPaint.ascent(), hintPaint)
    }

    private fun drawText(c: Canvas, s: String, cx: Float, cy: Float, sizeDp: Float, color: Int, bold: Boolean = false) {
        text.textSize = sizeDp * d
        text.color = color
        text.typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        val fm = text.fontMetrics
        c.drawText(s, cx, cy - (fm.ascent + fm.descent) / 2, text)
    }

    private fun drawLang(c: Canvas, cx: Float, cy: Float, fg: Int) {
        val a = if (zh) "中" else "中/"
        val b = if (zh) "/英" else "英"
        text.typeface = Typeface.DEFAULT_BOLD
        text.textSize = (if (zh) 16 else 11) * d
        val wa = text.measureText(a)
        text.typeface = Typeface.DEFAULT
        text.textSize = (if (zh) 11 else 16) * d
        val wb = text.measureText(b)
        val x0 = cx - (wa + wb) / 2
        val dim = withAlpha(fg, 140)
        drawText(c, a, x0 + wa / 2, cy, if (zh) 16f else 11f, if (zh) fg else dim, bold = zh)
        drawText(c, b, x0 + wa + wb / 2, cy, if (zh) 11f else 16f, if (zh) dim else fg, bold = !zh)
    }

    private fun sideItemH() = rowH() * 0.78f

    private fun drawSide(c: Canvas, list: List<String>) {
        val r = sideRect
        val rad = skin.radius * d
        fill.color = skin.keyBg
        c.drawRoundRect(r, rad, rad, fill)
        c.save()
        c.clipRect(r)
        val ih = sideItemH()
        for ((i, s) in list.withIndex()) {
            val top = r.top + i * ih - sideScroll
            if (top + ih < r.top || top > r.bottom) continue
            if (i == sidePressed) {
                fill.color = skin.keyDown
                c.drawRect(r.left, top, r.right, top + ih, fill)
            }
            drawText(c, if (s == "-") "−" else s, r.centerX(), top + ih / 2, 18f, skin.keyFg)
            if (i > 0) {
                fill.color = withAlpha(skin.keyFg, 22)
                c.drawRect(r.left + 6 * d, top, r.right - 6 * d, top + d * 0.8f, fill)
            }
        }
        c.restore()
    }

    private fun drawCursorMode(c: Canvas) {
        fill.color = withAlpha(skin.kbBg, 215)
        c.drawRect(0f, 0f, width.toFloat(), height.toFloat(), fill)
        drawText(c, "光标模式", width / 2f, height / 2f - 16 * d, 16f, skin.candFg, bold = true)
        drawText(c, "手指在屏幕任意位置上下左右滑动，松手结束", width / 2f, height / 2f + 12 * d, 13f, skin.barFg)
    }

    // —————————— 触摸 ——————————

    private class Press(val id: Int, var key: Key, val x0: Float, val y0: Float) {
        var long = false
        var fired = false
        var dragging = false
        /** 1 下滑，-1 上滑，0 没滑 */
        var swipe = 0
        /** 滑动进度 0–1：角标往中间移动的程度 */
        var prog = 0f
        var lastX = x0
        var lastY = y0
        var accX = 0f
        var accY = 0f
    }

    private val presses = LinkedHashMap<Int, Press>()
    private var longTask: Runnable? = null
    private var repeatTask: Runnable? = null

    private fun cancelAll() {
        longTask?.let { handler.removeCallbacks(it) }
        repeatTask?.let { handler.removeCallbacks(it) }
        longTask = null
        repeatTask = null
        presses.clear()
        cursorMode = false
        sideTouch = false
        sidePressed = -1
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (preview) return false
        if (handleSide(e)) return true
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                if (cursorMode) return true
                val i = e.actionIndex
                // 快打时第二根手指落下：前一个普通键直接输出
                for (p in presses.values.toList()) {
                    if (!p.long && !p.dragging && p.swipe == 0 && p.key.kind != Kind.DEL && p.key.kind != Kind.SHIFT) release(p, true)
                }
                val k = findKey(e.getX(i), e.getY(i)) ?: return true
                val p = Press(e.getPointerId(i), k, e.getX(i), e.getY(i))
                presses[p.id] = p
                down(p)
            }
            MotionEvent.ACTION_MOVE -> for (i in 0 until e.pointerCount) {
                val p = presses[e.getPointerId(i)] ?: continue
                move(p, e.getX(i), e.getY(i))
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> presses[e.getPointerId(e.actionIndex)]?.let {
                // 快速轻扫时中途的移动事件很少，抬手的位置也要算一次滑动
                if (it.key.kind != Kind.SPACE) move(it, e.getX(e.actionIndex), e.getY(e.actionIndex))
                release(it, true)
            }
            MotionEvent.ACTION_CANCEL -> presses.values.toList().forEach { release(it, false) }
        }
        return true
    }

    /** 左边的符号列：可以上下滑，点一下输入 */
    private fun handleSide(e: MotionEvent): Boolean {
        val list = side ?: return false
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (!sideRect.contains(e.x, e.y)) return false
                sideTouch = true
                sideDragging = false
                sideDownY = e.y
                sideStart = sideScroll
                sidePressed = ((e.y - sideRect.top + sideScroll) / sideItemH()).toInt().takeIf { it in list.indices } ?: -1
                list.getOrNull(sidePressed)?.let { listener?.onPress(Key(Kind.CHAR, it)) }
                invalidate()
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (!sideTouch) return false
                val dy = e.y - sideDownY
                if (!sideDragging && abs(dy) > slop) { sideDragging = true; sidePressed = -1 }
                if (sideDragging) sideScroll = (sideStart - dy).coerceIn(0f, max(0f, list.size * sideItemH() - sideRect.height()))
                invalidate()
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (!sideTouch) return false
                if (e.actionMasked == MotionEvent.ACTION_UP && !sideDragging) list.getOrNull(sidePressed)?.let { listener?.onKey(Key(Kind.CHAR, it)) }
                sideTouch = false
                sidePressed = -1
                invalidate()
                return true
            }
        }
        return sideTouch
    }

    private fun down(p: Press) {
        val k = p.key
        listener?.onPress(k)
        invalidate()
        if (k.kind == Kind.DEL || k.repeat) {
            // 删除在抬手时才删一个：留出判断上下滑的时间；按住 0.42 秒后开始连删
            val task = object : Runnable {
                override fun run() {
                    p.fired = true
                    listener?.onKey(k)
                    handler.postDelayed(this, 55)
                }
            }
            repeatTask = task
            handler.postDelayed(task, 420)
            return
        }
        scheduleLong(p)
    }

    private fun scheduleLong(p: Press) {
        longTask?.let { handler.removeCallbacks(it) }
        longTask = null
        val k = p.key
        val hintLong = k.hint != null && (k.kind == Kind.LETTER || k.kind == Kind.CHAR)
        val actionLong = k.longEmoji || k.kind == Kind.SPACE || k.kind == Kind.SHIFT
        if (!hintLong && !actionLong) return
        val task = Runnable {
            if (p.key !== k || p.dragging || p.swipe != 0) return@Runnable
            p.long = true
            when {
                hintLong -> { p.swipe = 1; p.prog = 1f; invalidate() }
                k.kind == Kind.SPACE -> {
                    // 长按空格：进入光标模式，上下左右滑都能移动光标
                    p.fired = true
                    p.dragging = true
                    cursorMode = true
                    listener?.onLongKey(k)
                    invalidate()
                }
                else -> {
                    p.fired = true
                    listener?.onLongKey(k)
                }
            }
        }
        longTask = task
        handler.postDelayed(task, longMs)
    }

    private fun canSwipe(k: Key) = swipeEnabled && when (k.kind) {
        Kind.LETTER, Kind.CHAR -> k.hint != null || k.hint2 != null
        Kind.DEL -> k.hint != null
        else -> false
    }

    private fun move(p: Press, x: Float, y: Float) {
        val k = p.key
        if (k.kind == Kind.SPACE) {
            if (!p.dragging && abs(x - p.x0) > 12 * d) {
                p.dragging = true
                longTask?.let { handler.removeCallbacks(it) }
                p.lastX = x; p.lastY = y
            }
            if (p.dragging) {
                p.accX += x - p.lastX
                p.accY += y - p.lastY
                p.lastX = x; p.lastY = y
                val sx = (if (cursorMode) 8 else 10) * d
                while (abs(p.accX) >= sx) {
                    val dir = if (p.accX > 0) 1 else -1
                    listener?.onCursorMove(dir, 0)
                    p.accX -= dir * sx
                }
                if (cursorMode) {
                    val sy = 22 * d
                    while (abs(p.accY) >= sy) {
                        val dir = if (p.accY > 0) 1 else -1
                        listener?.onCursorMove(0, dir)
                        p.accY -= dir * sy
                    }
                } else p.accY = 0f
            }
            return
        }
        if (p.long && (k.kind == Kind.LETTER || k.kind == Kind.CHAR)) return
        val dx = x - p.x0
        val dy = y - p.y0
        if (canSwipe(k) && abs(dy) > 6 * d && abs(dy) > abs(dx) * 1.2f) {
            val dir = if (dy > 0) 1 else -1
            if (p.swipe != dir) {
                p.swipe = dir
                longTask?.let { handler.removeCallbacks(it) }
                repeatTask?.let { handler.removeCallbacks(it) }
            }
            p.prog = ((abs(dy) - 6 * d) / (rowH() * 0.7f)).coerceIn(0f, 1f)
            invalidate()
            return
        }
        if (p.swipe != 0 && abs(dy) <= 6 * d) {
            p.swipe = 0
            p.prog = 0f
            invalidate()
        }
        if (p.swipe != 0 || k.kind == Kind.DEL || k.repeat) return
        val k2 = findKey(x, y) ?: return
        if (k2 === k || abs(dx) < abs(dy)) return
        val slidable = { q: Key -> q.kind == Kind.LETTER || q.kind == Kind.CHAR }
        if (slidable(k) && slidable(k2)) {
            p.key = k2
            scheduleLong(p)
            invalidate()
        }
    }

    private fun release(p: Press, fire: Boolean) {
        presses.remove(p.id)
        longTask?.let { handler.removeCallbacks(it) }
        longTask = null
        if (p.key.kind == Kind.DEL || p.key.repeat) {
            repeatTask?.let { handler.removeCallbacks(it) }
            repeatTask = null
        }
        if (cursorMode && p.key.kind == Kind.SPACE) cursorMode = false
        invalidate()
        if (!fire || p.fired) return
        val k = p.key
        // 滑得够远（进度过 0.12）才算滑动输入，不够就当普通点击
        val swiped = p.swipe != 0 && p.prog > 0.12f
        if (p.long && k.hint != null) { listener?.onKey(Key(Kind.CHAR, k.hint)); return }
        if (swiped) {
            if (k.kind == Kind.DEL) listener?.onSwipeDel(p.swipe > 0)
            else (if (p.swipe > 0) k.hint else k.hint2)?.let { listener?.onKey(Key(Kind.CHAR, it)) }
            return
        }
        if (!p.dragging) listener?.onKey(k)
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        cancelAll()
    }
}
