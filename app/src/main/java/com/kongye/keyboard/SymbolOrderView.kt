package com.kongye.keyboard

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import kotlin.math.ceil

/** Tap toggles selection; long-press and drag reorders the same symbol list. */
class SymbolOrderView(context:Context):View(context) {
    private val d=resources.displayMetrics.density
    private val handler=Handler(Looper.getMainLooper())
    private val paint=Paint(Paint.ANTI_ALIAS_FLAG)
    private var values=emptyList<String>()
    private var selected=-1
    private var drag=-1
    private var original=emptyList<String>()
    private var originalSelection=-1
    private var down=-1
    private var downX=0f;private var downY=0f
    private var x=0f;private var y=0f
    private var tile=0xfff0f2f6.toInt();private var ink=0xff18191c.toInt();private var accent=0xfff0492c.toInt()
    private var onSelect:(Int)->Unit={};private var onReorder:(List<String>)->Unit={}
    private val cols get()=(width/(48*d)).toInt().coerceAtLeast(1)
    private fun rect(index:Int):RectF {val cw=width/cols.toFloat();val left=(index%cols)*cw+3*d;val top=(index/cols)*48*d+3*d;return RectF(left,top,left+cw-6*d,top+42*d)}
    fun configure(symbols:List<String>,selection:Int,tile:Int,ink:Int,accent:Int,select:(Int)->Unit,reorder:(List<String>)->Unit) {
        if(drag<0){values=symbols;selected=selection}
        this.tile=tile;this.ink=ink;this.accent=accent;onSelect=select;onReorder=reorder
        contentDescription="左列符号："+values.joinToString(" ")+if(selected in values.indices)"，已选中：${values[selected]}" else "，未选中"
        requestLayout();invalidate()
    }
    override fun onMeasure(widthMeasureSpec:Int,heightMeasureSpec:Int) {
        val width=MeasureSpec.getSize(widthMeasureSpec);val columns=(width/(48*d)).toInt().coerceAtLeast(1)
        setMeasuredDimension(width,(ceil(values.size/columns.toFloat())*48*d).toInt())
    }
    private fun itemAt(x:Float,y:Float)=values.indices.firstOrNull {rect(it).contains(x,y)} ?: -1
    private val longTask=Runnable {
        if(down !in values.indices)return@Runnable
        original=values.toList();originalSelection=selected
        drag=down;selected=down;parent?.requestDisallowInterceptTouchEvent(true)
        performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS);invalidate()
    }
    override fun onDraw(canvas:Canvas) {
        for(index in values.indices) {
            if(index==drag)continue
            drawSymbol(canvas,index,rect(index),index==selected)
        }
        if(drag in values.indices) {
            val r=rect(drag);r.offset(x-r.centerX(),y-r.centerY());drawSymbol(canvas,drag,r,true)
        }
    }
    private fun drawSymbol(canvas:Canvas,index:Int,r:RectF,active:Boolean) {
        paint.color=if(active)accent else tile;canvas.drawRoundRect(r,10*d,10*d,paint)
        paint.color=if(active)0xffffffff.toInt() else ink;paint.textSize=17*d
        val fm=paint.fontMetrics;val value=values[index]
        canvas.drawText(value,r.centerX()-paint.measureText(value)/2,r.centerY()-(fm.ascent+fm.descent)/2,paint)
    }
    override fun onTouchEvent(event:MotionEvent):Boolean {
        when(event.actionMasked) {
            MotionEvent.ACTION_DOWN->{down=itemAt(event.x,event.y);downX=event.x;downY=event.y;x=event.x;y=event.y;if(down>=0)handler.postDelayed(longTask,ViewConfiguration.getLongPressTimeout().toLong())}
            MotionEvent.ACTION_MOVE->{
                x=event.x;y=event.y
                if(drag>=0) {
                    val target=itemAt(x,y)
                    if(target>=0&&target!=drag){val next=values.toMutableList();val value=next.removeAt(drag);next.add(target,value);values=next;drag=target;selected=target}
                    invalidate()
                } else if(kotlin.math.abs(x-downX)+kotlin.math.abs(y-downY)>ViewConfiguration.get(context).scaledTouchSlop){handler.removeCallbacks(longTask);down=-1}
            }
            MotionEvent.ACTION_UP->{
                handler.removeCallbacks(longTask)
                if(drag>=0){val result=values.toList();val index=selected;drag=-1;onReorder(result);onSelect(index)}
                else if(down>=0&&itemAt(event.x,event.y)==down)onSelect(if(selected==down)-1 else down)
                down=-1;parent?.requestDisallowInterceptTouchEvent(false);invalidate();performClick()
            }
            MotionEvent.ACTION_CANCEL->{handler.removeCallbacks(longTask);if(drag>=0){values=original;selected=originalSelection};drag=-1;down=-1;parent?.requestDisallowInterceptTouchEvent(false);onSelect(selected);invalidate()}
        }
        return true
    }
    override fun performClick():Boolean {super.performClick();return true}
    override fun onDetachedFromWindow(){handler.removeCallbacks(longTask);super.onDetachedFromWindow()}
}
