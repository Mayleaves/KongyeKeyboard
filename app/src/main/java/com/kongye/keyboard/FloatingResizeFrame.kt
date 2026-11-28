package com.kongye.keyboard

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.animation.ValueAnimator
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs
import kotlin.math.hypot

/** Only the four curved corner grips resize; other touches pass to the keyboard. */
@androidx.annotation.Keep
class FloatingResizeFrame(context:Context,private val skin:Skin,
    private val start:()->Unit,private val resize:(Float,Float,Int,Int)->Unit,
    private val finish:()->Unit):View(context) {
    val margin=18*resources.displayMetrics.density
    private val d=resources.displayMetrics.density
    private val paint=Paint(Paint.ANTI_ALIAS_FLAG).apply {style=Paint.Style.STROKE;strokeWidth=2*d;strokeCap=Paint.Cap.ROUND}
    private var horizontal=0
    private var vertical=0
    private var pointer=MotionEvent.INVALID_POINTER_ID
    private var downX=0f
    private var downY=0f
    private var reveal=0f
    private var motion:ValueAnimator?=null
    private var dismissing=false
    private var cornersShown=false
    init {contentDescription="悬浮键盘调整框，拖动四个弧形角标缩放"}
    private fun edges(x:Float,y:Float):Pair<Int,Int> {
        val arm=12*d;val tolerance=7*d
        for(right in listOf(false,true))for(bottom in listOf(false,true)) {
            val u=if(right)width-margin+6*d-x else x-(margin-6*d)
            val v=if(bottom)height-margin+6*d-y else y-(margin-6*d)
            if(u in -tolerance..arm+tolerance && v in -tolerance..arm+tolerance &&
                abs(hypot(u-arm,v-arm)-arm)<=tolerance)
                return (if(right)1 else -1) to (if(bottom)1 else -1)
        }
        return 0 to 0
    }
    fun hitScreen(x:Float,y:Float):Boolean {
        val at=IntArray(2);getLocationOnScreen(at)
        val lx=x-at[0];val ly=y-at[1]
        return cornersShown && !dismissing && lx in 0f..width.toFloat() && ly in 0f..height.toFloat() && edges(lx,ly).let {it.first!=0 && it.second!=0}
    }
    fun showCorners() {
        if(dismissing)return
        cornersShown=true;motion?.cancel()
        motion=ValueAnimator.ofFloat(0f,1.22f,.94f,1f).apply {
            duration=360;interpolator=android.view.animation.AccelerateDecelerateInterpolator()
            addUpdateListener {reveal=it.animatedValue as Float;invalidate()};start()
        }
    }
    fun hideForMove() {motion?.cancel();cornersShown=false;reveal=0f;invalidate()}
    fun dismiss(done:()->Unit) {
        if(dismissing)return
        dismissing=true;motion?.cancel()
        animate().alpha(0f).setDuration(140).withEndAction(done).start()
    }
    override fun onDetachedFromWindow() {
        motion?.cancel();animate().cancel()
        super.onDetachedFromWindow()
    }
    override fun onSizeChanged(w:Int,h:Int,oldw:Int,oldh:Int) {
        super.onSizeChanged(w,h,oldw,oldh)
        if(android.os.Build.VERSION.SDK_INT>=29) {
            val edge=(36*d).toInt();val corner=(36*d).toInt()
            systemGestureExclusionRects=listOf(
                android.graphics.Rect(0,0,edge,corner),android.graphics.Rect(w-edge,0,w,corner),
                android.graphics.Rect(0,h-corner,edge,h),android.graphics.Rect(w-edge,h-corner,w,h))
        }
    }
    override fun onDraw(canvas:Canvas) {
        val shift=6*d*reveal
        val l=margin-shift;val t=margin-shift;val r=width-margin+shift;val b=height-margin+shift
        paint.color=withAlpha(skin.candHi,(255*reveal.coerceIn(0f,1f)).toInt());paint.strokeWidth=2.7f*d
        val arm=12*d*reveal.coerceAtLeast(0f)
        for(right in listOf(false,true))for(bottom in listOf(false,true)) {
            val x=if(right)r else l;val y=if(bottom)b else t
            canvas.save();canvas.translate(x,y);canvas.scale(if(right)-1f else 1f,if(bottom)-1f else 1f)
            val path=Path().apply {addArc(RectF(0f,0f,2*arm,2*arm),180f,90f)}
            canvas.drawPath(path,paint);canvas.restore()
        }
    }
    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event:MotionEvent):Boolean {
        if(event.actionMasked==MotionEvent.ACTION_DOWN) {
            if(dismissing || !cornersShown)return false
            val edges=edges(event.x,event.y)
            horizontal=edges.first;vertical=edges.second
            if(horizontal==0 && vertical==0)return false
            pointer=event.getPointerId(0);downX=event.rawX;downY=event.rawY
            start();parent?.requestDisallowInterceptTouchEvent(true);return true
        }
        if(pointer==MotionEvent.INVALID_POINTER_ID)return false
        val index=event.findPointerIndex(pointer)
        val end=event.actionMasked==MotionEvent.ACTION_UP || event.actionMasked==MotionEvent.ACTION_CANCEL ||
            event.actionMasked==MotionEvent.ACTION_POINTER_UP && event.getPointerId(event.actionIndex)==pointer
        if(index>=0 && (event.actionMasked==MotionEvent.ACTION_MOVE || end && event.actionMasked!=MotionEvent.ACTION_CANCEL))
            resize(event.rawX+event.getX(index)-event.x-downX,event.rawY+event.getY(index)-event.y-downY,horizontal,vertical)
        if(end) {pointer=MotionEvent.INVALID_POINTER_ID;parent?.requestDisallowInterceptTouchEvent(false);finish()}
        return true
    }
}
