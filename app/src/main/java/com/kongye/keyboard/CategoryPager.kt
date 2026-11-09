package com.kongye.keyboard

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import kotlin.math.abs

/** 分类内容跟随手指，松手后两页同时滑入/滑出；只保留当前页和相邻页。 */
class CategoryPager(context: Context) : FrameLayout(context) {
    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private var count = 0
    private var selected = 0
    private var factory: (Int) -> View = { View(context) }
    private var onSelected: (Int) -> Unit = {}
    private var current: View? = null
    private var neighbor: View? = null
    private var next = -1
    private var downX = 0f
    private var downY = 0f
    private var dragging = false
    private var offset = 0f
    private var animator: ValueAnimator? = null

    fun pages(count: Int, selected: Int, factory: (Int) -> View, onSelected: (Int) -> Unit) {
        animator?.cancel(); animator = null
        this.count = count
        this.selected = selected.coerceIn(0, (count - 1).coerceAtLeast(0))
        this.factory = factory; this.onSelected = onSelected
        removeAllViews(); neighbor = null; next = -1; offset = 0f
        current = factory(this.selected).also { addView(it, LayoutParams(-1, -1)) }
    }

    fun select(index: Int) {
        if (index !in 0 until count || index == selected || animator != null) return
        prepare(index)
        settle(true)
    }

    private fun prepare(index: Int) {
        if (next == index) return
        neighbor?.let(::removeView)
        next = index
        neighbor = factory(index).also { addView(it, LayoutParams(-1, -1)) }
        position(offset)
    }

    private fun position(x: Float) {
        offset = x
        current?.translationX = x
        neighbor?.translationX = x + (if (next > selected) width else -width)
    }

    private fun settle(commit: Boolean) {
        val end = if (commit) (if (next > selected) -width else width).toFloat() else 0f
        val start = offset
        animator = ValueAnimator.ofFloat(start, end).apply {
            duration = (220 * (abs(end - start) / width.coerceAtLeast(1)).coerceIn(.35f, 1f)).toLong()
            interpolator = DecelerateInterpolator(1.4f)
            addUpdateListener { position(it.animatedValue as Float) }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                private var cancelled = false
                override fun onAnimationCancel(animation: android.animation.Animator) { cancelled = true }
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    if (cancelled) return
                    if (commit) {
                        current?.let(::removeView); current = neighbor; selected = next
                    } else neighbor?.let(::removeView)
                    neighbor = null; next = -1; position(0f); animator = null
                    if (commit) onSelected(selected)
                }
            })
            start()
        }
    }

    override fun onInterceptTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { downX = e.x; downY = e.y; dragging = false }
            MotionEvent.ACTION_MOVE -> if (animator == null && abs(e.x - downX) > slop && abs(e.x - downX) > abs(e.y - downY) * 1.25f) {
                dragging = true
                parent?.requestDisallowInterceptTouchEvent(true)
                return true
            }
        }
        return false
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (animator != null) return true
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { downX = e.x; downY = e.y; dragging = true }
            MotionEvent.ACTION_MOVE -> if (dragging) {
                val dx = (e.x - downX).coerceIn(-width.toFloat(), width.toFloat())
                val target = selected + if (dx < 0) 1 else -1
                if (target in 0 until count) { prepare(target); position(dx) }
                else { neighbor?.let(::removeView); neighbor = null; next = -1; position(dx * .16f) }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                dragging = false
                settle(e.actionMasked == MotionEvent.ACTION_UP && next >= 0 && abs(offset) > width * .18f)
            }
        }
        return true
    }

    override fun onDetachedFromWindow() {
        animator?.cancel(); animator = null
        super.onDetachedFromWindow()
    }
}
