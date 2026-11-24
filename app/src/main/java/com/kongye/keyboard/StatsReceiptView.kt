package com.kongye.keyboard

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.*
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.os.SystemClock
import android.view.*
import android.view.accessibility.AccessibilityNodeInfo
import android.view.animation.LinearInterpolator
import android.widget.OverScroller

/** First HTML's frameless slot, timed paper feed and gentle detached-paper pose. */
@androidx.annotation.Keep
class StatsReceiptView(context: Context) : View(context) {
    var printerAccent = Color.rgb(225,119,37)
    var onStateChanged: ((String,Int)->Unit)? = null
    var onStep:((Boolean)->Unit)?=null
    var onTear:(()->Unit)?=null
    private var lastSound=0L
    private val d = resources.displayMetrics.density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private var bitmap: Bitmap? = null
    private var description = ""
    private var progress = 0f
    private var offset = 0f
    private var phase = "idle"
    private var detach=0f
    private var completed:(()->Unit)?=null
    private var downX=0f
    private var downY=0f
    private var moved=false
    private var animator: ValueAnimator? = null
    private val scroller = OverScroller(context)
    private var velocity: VelocityTracker? = null
    private var lastY = 0f
    private var lastPercent = -1
    private val slot get() = 20*d
    private val toothDepth get() = 20f*paperWidth/1080f
    private val paperStart get() = slot-toothDepth+detach*(24*d+toothDepth)
    private val paperWidth get() = (width-48*d).coerceAtLeast(1f)
    private val paperHeight get() = bitmap?.let {it.height*paperWidth/it.width} ?: 0f
    private val maxOffset get() = (paperHeight-(height-paperStart-32*d)).coerceAtLeast(0f)
    init { isClickable=true;isFocusable=true;importantForAccessibility=IMPORTANT_FOR_ACCESSIBILITY_YES;contentDescription="正在生成打字报告" }

    private fun state(value:String) {
        val percent=(progress*100).toInt().coerceIn(0,100)
        if(phase!=value||lastPercent!=percent) {phase=value;lastPercent=percent;onStateChanged?.invoke(value,percent)}
    }
    fun clear() {
        animator?.cancel();scroller.abortAnimation();velocity?.recycle();velocity=null
        bitmap?.recycle();bitmap=null;offset=0f;progress=0f;detach=0f;completed=null;phase="idle";lastPercent=-1
        contentDescription="正在生成打字报告";invalidate()
    }
    fun submit(image:Bitmap,description:String,animate:Boolean,completed:()->Unit) {
        clear();bitmap=image;this.description=description;this.completed=completed
        if(animate)printReceipt(completed) else {progress=1f;contentDescription=description;state("complete");invalidate();completed()}
    }
    private fun printReceipt(completed:()->Unit) {
        if(bitmap==null)return
        animator?.cancel();scroller.abortAnimation();offset=0f;progress=0f;lastPercent=-1;lastSound=0
        contentDescription="正在打印打字报告";state("warmup")
        if(!ValueAnimator.areAnimatorsEnabled()) {
            progress=1f;contentDescription=description;state("complete");invalidate();completed();return
        }
        animator=ValueAnimator.ofFloat(0f,3420f).apply {
            duration=3420;interpolator=LinearInterpolator()
            addUpdateListener {
                val elapsed=it.animatedValue as Float
                when {
                    elapsed<260 -> state("warmup")
                    elapsed<3010 -> {
                        val t=((elapsed-260)/2750).coerceIn(0f,1f)
                        val smooth=if(t<.5f)2*t*t else 1-(-2*t+2).let {v->v*v}/2
                        progress=t*.68f+smooth*.32f;state("feeding")
                        val now=SystemClock.uptimeMillis()
                        if(now-lastSound>=48) {lastSound=now;onStep?.invoke(t in .2f.. .45f||t in .68f.. .85f)}
                    }
                    else -> {
                        progress=1f;state("stamping")
                    }
                }
                invalidate()
            }
            addListener(object:AnimatorListenerAdapter() {
                private var cancelled=false
                override fun onAnimationCancel(animation:Animator) {cancelled=true}
                override fun onAnimationEnd(animation:Animator) {
                    if(!cancelled) {progress=1f;contentDescription=description;state("complete");invalidate();completed()}
                }
            })
            start()
        }
    }
    private fun tear() {
        if(bitmap==null||phase=="torn")return
        val wasPrinting=progress<1f
        animator?.cancel();scroller.abortAnimation();progress=1f;offset=0f
        onTear?.invoke()
        contentDescription=description
        state("torn")
        if(wasPrinting)completed?.invoke()
        if(!ValueAnimator.areAnimatorsEnabled()) {detach=1f;invalidate();return}
        animator=ValueAnimator.ofFloat(detach,1f).apply {
            duration=280;interpolator=android.view.animation.DecelerateInterpolator()
            addUpdateListener {detach=it.animatedValue as Float;invalidate()};start()
        }
    }
    override fun onDraw(canvas:Canvas) {
        bitmap?.let {image ->
            canvas.save();canvas.clipRect(0f,slot,width.toFloat(),height.toFloat())
            val top=paperStart-(1-progress)*(paperHeight+10*d)-offset
            paint.alpha=255
            canvas.drawBitmap(image,null,RectF(24*d,top,width-24*d,top+paperHeight),paint)
            if(detach>0f) {
                // The retained paper strip and detached receipt use the same 36-tooth cut line.
                paint.color=image.getPixel(12,40.coerceAtMost(image.height-1));paint.alpha=255
                val cut=slot+8*d*detach;val pitch=paperWidth/36f
                val stub=Path().apply {
                    moveTo(24*d,slot-1);lineTo(width-24*d,slot-1);lineTo(width-24*d,cut+toothDepth)
                    for(i in 36 downTo 1) {lineTo(24*d+(i-.5f)*pitch,cut);lineTo(24*d+(i-1)*pitch,cut+toothDepth)}
                    close()
                }
                canvas.drawPath(stub,paint)
            }
            canvas.restore()
        }
        // Pin the progress hairline and seven-dp dark slot above the moving receipt.
        paint.color=Color.argb(28,128,128,128);canvas.drawRect(20*d,0f,width-20*d,d,paint)
        paint.color=printerAccent;paint.alpha=if(phase=="complete")115 else 255
        canvas.drawRect(20*d,0f,20*d+(width-40*d)*progress,d,paint);paint.alpha=255
        paint.color=Color.rgb(15,16,18)
        canvas.drawRoundRect(RectF(18*d,slot-7*d,width-18*d,slot),4*d,4*d,paint)
    }
    override fun onSizeChanged(w:Int,h:Int,oldw:Int,oldh:Int) {offset=offset.coerceIn(0f,maxOffset)}
    override fun onTouchEvent(event:MotionEvent):Boolean {
        when(event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                scroller.abortAnimation();velocity?.recycle();velocity=VelocityTracker.obtain()
                downX=event.x;downY=event.y;lastY=event.y;moved=false
                parent?.requestDisallowInterceptTouchEvent(true)
            }
            MotionEvent.ACTION_MOVE -> {
                if(kotlin.math.abs(event.y-downY)>ViewConfiguration.get(context).scaledTouchSlop ||
                    kotlin.math.abs(event.x-downX)>ViewConfiguration.get(context).scaledTouchSlop)moved=true
                if(progress==1f) {offset=(offset+lastY-event.y).coerceIn(0f,maxOffset);lastY=event.y;invalidate()}
            }
            MotionEvent.ACTION_UP -> {
                if(!moved && event.y>slot && event.x in 24*d..(width-24*d))performClick()
                else if(progress==1f) {
                    velocity?.addMovement(event);velocity?.computeCurrentVelocity(1000,ViewConfiguration.get(context).scaledMaximumFlingVelocity.toFloat())
                    scroller.fling(0,offset.toInt(),0,-(velocity?.yVelocity?:0f).toInt(),0,0,0,maxOffset.toInt());postInvalidateOnAnimation()
                }
                velocity?.recycle();velocity=null
            }
            MotionEvent.ACTION_CANCEL -> {velocity?.recycle();velocity=null}
        }
        velocity?.addMovement(event);return true
    }
    override fun performClick():Boolean {super.performClick();tear();return true}
    override fun computeScroll() {if(scroller.computeScrollOffset()) {offset=scroller.currY.toFloat().coerceIn(0f,maxOffset);postInvalidateOnAnimation()}}
    override fun onInitializeAccessibilityNodeInfo(info:AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(info);info.isScrollable=progress==1f&&maxOffset>0
        if(progress==1f) {
            if(offset<maxOffset)info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_FORWARD)
            if(offset>0)info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_BACKWARD)
        }
    }
    override fun performAccessibilityAction(action:Int,args:Bundle?):Boolean {
        if(progress==1f&&action in listOf(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD,AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)) {
            offset=(offset+(height-slot)*.8f*if(action==AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)1 else -1).coerceIn(0f,maxOffset)
            invalidate();return true
        }
        return super.performAccessibilityAction(action,args)
    }
    fun release() {clear();onStateChanged=null;onStep=null;onTear=null}
}

/** Small reference-style stroke icons; no font-dependent emoji. */
class ReceiptGlyph(private val kind:Int,color:Int,private val density:Float):Drawable() {
    private val p=Paint(Paint.ANTI_ALIAS_FLAG).apply {this.color=color;style=Paint.Style.STROKE;strokeWidth=1.4f;strokeCap=Paint.Cap.ROUND;strokeJoin=Paint.Join.ROUND}
    override fun draw(c:Canvas) {
        c.save();c.translate(bounds.left.toFloat(),bounds.top.toFloat());c.scale(bounds.width()/16f,bounds.height()/16f)
        val path=Path()
        when(kind) {
            0 -> {
                path.moveTo(2.5f,6f);path.lineTo(5.3f,6f);path.lineTo(9f,3f);path.lineTo(9f,13f);path.lineTo(5.3f,10f);path.lineTo(2.5f,10f);path.close()
                path.moveTo(11.5f,5.2f);path.cubicTo(13.2f,6.5f,13.2f,9.5f,11.5f,10.8f)
                path.moveTo(13.5f,3.5f);path.cubicTo(16f,6f,16f,10f,13.5f,12.5f)
            }
            1 -> {
                for(x in 1..13 step 4) {path.moveTo(x.toFloat(),7f);path.lineTo(x+2f,5f);path.lineTo(x+4f,7f)}
                path.moveTo(1f,11f);path.lineTo(3f,9f);path.lineTo(5f,11f);path.lineTo(7f,9f);path.lineTo(9f,11f);path.lineTo(11f,9f);path.lineTo(15f,11f)
            }
            else -> {path.moveTo(8f,2f);path.lineTo(8f,10f);path.moveTo(5f,7f);path.lineTo(8f,10f);path.lineTo(11f,7f);path.moveTo(3f,13f);path.lineTo(13f,13f)}
        }
        c.drawPath(path,p);c.restore()
    }
    override fun setAlpha(alpha:Int) {p.alpha=alpha}
    override fun setColorFilter(filter:ColorFilter?) {p.colorFilter=filter}
    @Deprecated("Deprecated in Java") override fun getOpacity()=PixelFormat.TRANSLUCENT
}
class ReceiptStatusDot(private val density:Float):Drawable() {
    private val p=Paint(Paint.ANTI_ALIAS_FLAG).apply {color=Color.rgb(225,119,37)}
    override fun draw(c:Canvas) {c.drawCircle(bounds.exactCenterX(),bounds.exactCenterY(),bounds.width()/2f,p)}
    override fun setAlpha(alpha:Int) {p.alpha=alpha}
    override fun setColorFilter(filter:ColorFilter?) {p.colorFilter=filter}
    @Deprecated("Deprecated in Java") override fun getOpacity()=PixelFormat.TRANSLUCENT
}
