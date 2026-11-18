package com.kongye.keyboard

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View

/** One column per week and one square per day, ending today. */
class StatsYearView(ctx:Context):View(ctx) {
    var days=emptyList<Pair<String,Long>>()
    var ink=Color.DKGRAY
    var base=Color.LTGRAY
    var accent=Color.RED
    var selected:String?=null
    var onPick:((String)->Unit)?=null
    private val p=Paint(Paint.ANTI_ALIAS_FLAG)
    override fun onDraw(c:Canvas){drawYear(c,RectF(0f,0f,width.toFloat(),height.toFloat()),days,ink,base,accent,selected,p)}
    override fun onTouchEvent(e:MotionEvent):Boolean {
        if(days.isEmpty())return false
        if(e.actionMasked==MotionEvent.ACTION_UP) {
            val offset=java.time.LocalDate.parse(days.first().first).dayOfWeek.value-1
            val columns=(offset+days.size+6)/7
            val step=width.toFloat()/columns;val top=(height-step*7)/2
            val col=(e.x/step).toInt();val row=((e.y-top)/step).toInt()
            val index=col*7+row-offset
            if(e.y>=top&&row in 0..6&&col in 0 until columns&&index in days.indices){selected=days[index].first;onPick?.invoke(selected!!);invalidate()}
            performClick()
        }
        return true
    }
    override fun performClick():Boolean {super.performClick();return true}
    companion object {
        fun drawYear(c:Canvas,r:RectF,days:List<Pair<String,Long>>,ink:Int,base:Int,accent:Int,selected:String?,p:Paint) {
            if(days.isEmpty())return
            val offset=java.time.LocalDate.parse(days.first().first).dayOfWeek.value-1
            val columns=(offset+days.size+6)/7;val step=r.width()/columns
            val top=r.top+(r.height()-step*7)/2;val gap=step*.18f
            val peak=(days.maxOfOrNull {it.second} ?: 0L).coerceAtLeast(1)
            days.forEachIndexed {i,day->
                val slot=offset+i;val f=if(day.second==0L)0f else .25f+.75f*kotlin.math.sqrt(day.second.toDouble()/peak).toFloat()
                fun mix(a:Int,b:Int)=(a+(b-a)*f).toInt()
                p.style=Paint.Style.FILL;p.color=if(day.first==selected)accent else Color.rgb(mix(Color.red(base),Color.red(ink)),mix(Color.green(base),Color.green(ink)),mix(Color.blue(base),Color.blue(ink)))
                val x=r.left+(slot/7)*step;val y=top+(slot%7)*step
                c.drawRoundRect(RectF(x+gap/2,y+gap/2,x+step-gap/2,y+step-gap/2),step*.18f,step*.18f,p)
            }
        }
    }
}
