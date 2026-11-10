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
    private val swaps=java.util.WeakHashMap<View,Triple<ImageView,Bitmap,Float>>()
    /** One layout change, then a compositor crossfade of the complete keyboard. */
    fun swap(host: FrameLayout?, content: View, change: () -> Unit) {
        swaps.remove(content)?.let {(cover, bitmap, target) -> cover.animate().cancel();host?.removeView(cover);cover.setImageDrawable(null);bitmap.recycle();content.alpha=target}
        val before=snapshot(content)
        if(host==null || before==null) {change();return}
        content.animate().cancel()
        val oldAlpha=content.alpha
        val cover=ImageView(host.context).apply {
            setImageBitmap(before);scaleType=ImageView.ScaleType.FIT_XY
            alpha=oldAlpha;x=content.x;y=content.y
            importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        change()
        val target=content.alpha
        host.addView(cover,FrameLayout.LayoutParams(before.width,before.height))
        swaps[content]=Triple(cover,before,target)
        content.alpha=target
        content.viewTreeObserver.addOnPreDrawListener(object:android.view.ViewTreeObserver.OnPreDrawListener {
            override fun onPreDraw():Boolean {
                content.viewTreeObserver.removeOnPreDrawListener(this)
                if(swaps[content]?.first!==cover) return true
                cover.animate().alpha(0f).setDuration(220).withLayer().withEndAction {
                    host.removeView(cover);cover.setImageDrawable(null);before.recycle()
                    if(swaps[content]?.first===cover) swaps.remove(content)
                }.start()
                return true
            }
        })
        content.invalidate()
    }
    /** A fixed-size content transition; no bitmap of a potentially very tall list. */
    fun change(view:View, update:()->Unit) {
        view.animate().cancel()
        if(!ValueAnimator.areAnimatorsEnabled()) {update();view.alpha=1f;return}
        update()
        val base=view.translationX
        view.alpha=1f;view.translationX=base+6f*view.resources.displayMetrics.density
        view.animate().translationX(base).setDuration(180).setInterpolator(DecelerateInterpolator()).start()
    }
    fun enter(view: View, distance: Float=7f) {
        if (!ValueAnimator.areAnimatorsEnabled()) return
        view.animate().cancel()
        val base=bases.getOrPut(view) { view.translationY }
        view.alpha=1f
        view.translationY=base+distance*view.resources.displayMetrics.density
        view.animate().translationY(base).setDuration(200)
            .setInterpolator(DecelerateInterpolator(1.5f)).withEndAction { bases.remove(view) }.start()
    }
    fun pulse(view: View) {
        if (!ValueAnimator.areAnimatorsEnabled()) return
        view.animate().cancel()
        bases.remove(view)?.let {view.translationY=it}
        enter(view,3f)
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
