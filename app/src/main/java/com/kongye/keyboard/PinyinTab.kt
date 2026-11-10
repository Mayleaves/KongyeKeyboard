package com.kongye.keyboard

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.text.StaticLayout
import android.text.TextPaint
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/** Compact input spelling; expanding allows wrapped text and dragging the caret. */
class PinyinTab(context: Context, private val onCaret: (Int) -> Unit) : View(context) {
    data class Preview(val line: String, val caret: Int, val x: Float, val y: Float)
    var onPreview: (Preview?) -> Unit = {}
    var onExpanded: () -> Unit = {}
    var uiScale = 1f
        set(value) { if (field != value) { field=value; requestLayout(); invalidate() } }
    private val d get() = resources.displayMetrics.density * uiScale
    var skin: Skin = Skins.KONGYE
        set(value) { field=value; invalidate() }
    private var text = ""
    private var caret = -1
    private var offsetX = 0f
    private var offsetY = 0f
    var expanded = false
        private set
    private val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG)
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style=Paint.Style.STROKE; strokeCap=Paint.Cap.ROUND }
    private val path = Path()
    private var layout: StaticLayout? = null
    private var downX=0f
    private var downY=0f
    private var minusPressed=false
    private var dragging=false
    private var fingerX=0f
    private var fingerY=0f
    private val slop=ViewConfiguration.get(context).scaledTouchSlop
    private val size get() = (if(expanded) 22f else 15f)*d
    private val pad get() = (if(expanded) 10f else 5f)*d
    private val top get() = (if(expanded) 10f else 1f)*d
    private val right get() = width-(if(expanded) 13f else 1f)*d
    private val bodyLeft get() = d+pad
    private val bodyTop get() = top+5*d
    private val bodyWidth get() = max(1,(right-bodyLeft-pad).toInt())
    private val bodyHeight get() = max(1f,height-bodyTop-5*d)
    private val focus get() = if(caret<0) text.length else caret.coerceIn(0,text.length)

    fun reset() {
        stopDrag();expanded=false;text="";caret=-1;offsetX=0f;offsetY=0f;layout=null
        requestLayout();invalidate();onExpanded()
    }
    fun set(text: String, caret: Int) {
        if(text==this.text && caret==this.caret) return
        this.text=text;this.caret=caret
        if(text.isEmpty()) {expanded=false;stopDrag()}
        requestLayout();invalidate()
    }
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val limit=if(MeasureSpec.getMode(widthMeasureSpec)==MeasureSpec.UNSPECIFIED) resources.displayMetrics.widthPixels else MeasureSpec.getSize(widthMeasureSpec)
        textPaint.textSize=size
        val maxWidth=max(1,limit-(16*d).toInt())
        val cap=if(expanded) maxWidth else min(maxWidth,(176*d).toInt())
        val want=ceil(textPaint.measureText(text)+2*pad+(if(expanded) 15 else 7)*d).toInt().coerceIn(min((if(expanded) 62*d else 32*d).toInt(),cap),cap)
        if(expanded) {
            val contentWidth=max(1,(want-2*pad-14*d).toInt())
            layout=StaticLayout.Builder.obtain(text,0,text.length,textPaint,contentWidth)
                .setIncludePad(false).build()
            val l=layout!!
            val lines=min(3,l.lineCount)
            val h=ceil(top+10*d+l.getLineBottom(lines-1)).toInt()
            setMeasuredDimension(want,h)
            ensureFocusVisible()
        } else {
            layout=null;offsetY=0f
            setMeasuredDimension(want,(24*d).toInt())
            offsetX=max(0f,textPaint.measureText(text)-max(1f,want-2*pad-2*d))
        }
    }
    private fun ensureFocusVisible() {
        val l=layout ?: return
        val line=l.getLineForOffset(focus.coerceAtMost(l.text.length))
        val viewport=(measuredHeight-bodyTop-5*d).coerceAtLeast(1f)
        if(!dragging) {
            if(l.getLineTop(line)<offsetY) offsetY=l.getLineTop(line).toFloat()
            if(l.getLineBottom(line)>offsetY+viewport) offsetY=l.getLineBottom(line)-viewport
        }
        offsetY=offsetY.coerceIn(0f,max(0f,l.height-viewport))
    }
    override fun onDraw(c: Canvas) {
        textPaint.textSize=size;textPaint.color=skin.candFg
        val radius=(if(expanded) 13 else 11)*d
        path.reset();path.addRoundRect(RectF(d,top,right,height+radius),floatArrayOf(radius,radius,radius,radius,0f,0f,0f,0f),Path.Direction.CW)
        fill.color=withAlpha(if(skin.dark) skin.popBg else Color.WHITE,222)
        c.drawPath(path,fill)
        stroke.strokeWidth=.8f*d;stroke.color=if(skin.dark) Color.parseColor("#777B80") else Color.parseColor("#C5C7CA")
        c.drawPath(path,stroke)
        c.save();c.clipRect(bodyLeft-2*d,top,right-pad+3*d,height.toFloat())
        if(expanded) {
            val l=layout
            if(l!=null) {
                c.translate(bodyLeft,bodyTop-offsetY);l.draw(c)
                val line=l.getLineForOffset(focus.coerceAtMost(l.text.length))
                val x=l.getPrimaryHorizontal(focus.coerceAtMost(l.text.length))
                fill.color=skin.candHi
                c.drawRect(x,l.getLineTop(line).toFloat(),x+1.8f*d,l.getLineBottom(line).toFloat(),fill)
            }
        } else {
            val fm=textPaint.fontMetrics
            c.drawText(text,bodyLeft-offsetX,(top+height)/2f+d-(fm.ascent+fm.descent)/2f,textPaint)
        }
        c.restore()
        if(expanded) {
            val x=right-2*d;val y=top+3*d
            fill.color=if(skin.dark) skin.modBg else Color.WHITE;c.drawCircle(x,y,10.5f*d,fill)
            stroke.color=skin.candFg;stroke.strokeWidth=1.6f*d;c.drawCircle(x,y,10.5f*d,stroke)
            stroke.strokeWidth=2.2f*d;c.drawLine(x-4.8f*d,y,x+4.8f*d,y,stroke)
        }
    }
    private fun at(x:Float,y:Float):Int {
        val l=layout
        if(expanded && l!=null) return l.getOffsetForHorizontal(l.getLineForVertical((y-bodyTop+offsetY).toInt().coerceIn(0,l.height-1)),x-bodyLeft).coerceIn(0,text.length)
        textPaint.textSize=size
        val target=x-bodyLeft+offsetX
        return (0..text.length).minByOrNull {abs(textPaint.measureText(text,0,it)-target)} ?: 0
    }
    private fun select(position:Int) {
        val next=position.coerceIn(0,text.length)
        val changed=next!=focus || caret<0
        caret=next;if(changed) onCaret(next);ensureFocusVisible();invalidate()
    }
    private fun publishPreview() {
        val l=layout ?: return
        val line=l.getLineForOffset(focus.coerceAtMost(l.text.length))
        val start=l.getLineStart(line);val end=l.getLineEnd(line)
        onPreview(Preview(text.substring(start.coerceAtMost(text.length),end.coerceAtMost(text.length)),(focus-start).coerceIn(0,(end-start)),l.getPrimaryHorizontal(focus.coerceAtMost(l.text.length))+bodyLeft,bodyTop+l.getLineTop(line)-offsetY))
    }
    private val edgeScroll=object:Runnable {
        override fun run() {
            val l=layout ?: return
            if(!dragging) return
            val dy=when {fingerY<bodyTop+12*d -> -5*d;fingerY>height-12*d -> 5*d;else -> 0f}
            val next=(offsetY+dy).coerceIn(0f,max(0f,l.height-bodyHeight))
            if(next!=offsetY) {offsetY=next;select(at(fingerX,fingerY));publishPreview()}
            postDelayed(this,32)
        }
    }
    private fun stopDrag() {dragging=false;minusPressed=false;removeCallbacks(edgeScroll);onPreview(null)}
    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent):Boolean {
        when(e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                downX=e.rawX;downY=e.rawY;fingerX=e.x;fingerY=e.y
                minusPressed=expanded && abs(e.x-(right-2*d))<=17*d && abs(e.y-(top+3*d))<=17*d
                if(!minusPressed) {
                    val position=at(e.x,e.y)
                    if(!expanded) {expanded=true;offsetY=0f;requestLayout();onExpanded()}
                    select(position)
                }
            }
            MotionEvent.ACTION_MOVE -> if(!minusPressed) {
                if(abs(e.rawX-downX)+abs(e.rawY-downY)>slop) dragging=true
                if(dragging) {
                    val loc=IntArray(2);getLocationOnScreen(loc)
                    fingerX=e.rawX-loc[0];fingerY=e.rawY-loc[1]
                    select(at(fingerX,fingerY));publishPreview()
                    removeCallbacks(edgeScroll);postDelayed(edgeScroll,32)
                }
            }
            MotionEvent.ACTION_UP -> {
                if(minusPressed) {
                    expanded=false;caret=-1;offsetY=0f;onCaret(text.length)
                    requestLayout();invalidate();onExpanded()
                }
                stopDrag();parent?.requestDisallowInterceptTouchEvent(false)
            }
            MotionEvent.ACTION_CANCEL -> {stopDrag();parent?.requestDisallowInterceptTouchEvent(false)}
        }
        return true
    }
    override fun onDetachedFromWindow() {stopDrag();super.onDetachedFromWindow()}
}

/** Passive circular lens above the spelling editor; no system popup or focus change. */
class PinyinLens(context:Context):View(context) {
    var skin:Skin=Skins.KONGYE
    var preview:PinyinTab.Preview?=null
        set(value) {field=value;invalidate()}
    private val d=resources.displayMetrics.density
    private val paint=Paint(Paint.ANTI_ALIAS_FLAG)
    private val text=Paint(Paint.ANTI_ALIAS_FLAG)
    private val circle=Path()
    override fun onMeasure(widthMeasureSpec:Int,heightMeasureSpec:Int)=setMeasuredDimension((86*d).toInt(),(86*d).toInt())
    override fun onDraw(c:Canvas) {
        val value=preview ?: return
        val radius=width/2f-3*d;val center=width/2f
        paint.color=if(skin.dark) skin.popBg else Color.WHITE;c.drawCircle(center,center,radius,paint)
        circle.reset();circle.addCircle(center,center,radius,Path.Direction.CW)
        c.save();c.clipPath(circle)
        text.textSize=31*d;text.color=skin.candFg
        val x=center-text.measureText(value.line,0,value.caret.coerceIn(0,value.line.length))
        val fm=text.fontMetrics;val baseline=center-(fm.ascent+fm.descent)/2f
        c.drawText(value.line,x,baseline,text)
        paint.color=skin.candHi;c.drawRect(center,center-20*d,center+1.8f*d,center+20*d,paint)
        c.restore()
        paint.style=Paint.Style.STROKE;paint.strokeWidth=1.5f*d;paint.color=withAlpha(skin.barFg,210)
        c.drawCircle(center,center,radius,paint);paint.style=Paint.Style.FILL
    }
}
