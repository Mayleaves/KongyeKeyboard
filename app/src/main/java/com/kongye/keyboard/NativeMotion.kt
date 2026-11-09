package com.kongye.keyboard

import android.animation.ValueAnimator
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageView

/** 只在用户切换页面时过渡；设置刷新和每帧拖动不重播动画。 */
object NativeMotion {
    private val bases=java.util.WeakHashMap<View,Float>()
    fun enter(view: View, distance: Float=7f) {
        if (!ValueAnimator.areAnimatorsEnabled()) return
        view.animate().cancel()
        val base=bases.getOrPut(view) { view.translationY }
        view.alpha=.45f
        view.translationY=base+distance*view.resources.displayMetrics.density
        view.animate().alpha(1f).translationY(base).setDuration(200)
            .setInterpolator(DecelerateInterpolator(1.5f)).withEndAction { bases.remove(view) }.start()
    }
    fun pulse(view: View) {
        if (!ValueAnimator.areAnimatorsEnabled()) return
        view.animate().cancel(); view.alpha=.65f
        view.animate().alpha(1f).setDuration(170).start()
    }
    fun snapshot(view: View): Bitmap? {
        if (!ValueAnimator.areAnimatorsEnabled() || view.width<=0 || view.height<=0) return null
        return runCatching {
            Bitmap.createBitmap(view.width,view.height,Bitmap.Config.ARGB_8888).also { view.draw(Canvas(it)) }
        }.getOrNull()
    }
    fun reveal(host: FrameLayout, content: View, previous: Bitmap?) {
        if (previous == null) { enter(content); return }
        val cover=ImageView(host.context).apply {
            setImageBitmap(previous); scaleType=ImageView.ScaleType.FIT_XY
            importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO
            isClickable=false
        }
        host.addView(cover, FrameLayout.LayoutParams(-1,-1))
        enter(content)
        cover.animate().alpha(0f).setDuration(170).withEndAction {
            host.removeView(cover); cover.setImageDrawable(null); previous.recycle()
        }.start()
    }
}
