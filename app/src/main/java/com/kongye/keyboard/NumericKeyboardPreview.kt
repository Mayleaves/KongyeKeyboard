package com.kongye.keyboard

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View

/** The same numeric rows and key renderer as the production IME, with edit targets overlaid. */
class NumericKeyboardPreview(context:Context):View(context) {
    private val board=KeyboardView(context).apply {preview=true}
    private var rows=Layouts.numpad()
    private var slot=0
    private var tint=0xfff0492c.toInt()
    private val paint=Paint(Paint.ANTI_ALIAS_FLAG).apply {style=Paint.Style.STROKE}
    private var pick:(Int)->Unit={}
    fun configure(skin:Skin,custom:List<String>,side:List<String>,selected:Int,accent:Int,onSelect:(Int)->Unit) {
        slot=selected;tint=accent;pick=onSelect
        rows=Layouts.numpad(custom)
        board.heightScale=Prefs.heightScale(context);board.enterIcon=R.drawable.ic_enter
        board.setup(skin,rows,side)
        alpha=Prefs.opacity(context);requestLayout();invalidate()
    }
    override fun onMeasure(widthMeasureSpec:Int,heightMeasureSpec:Int) {
        val width=MeasureSpec.getSize(widthMeasureSpec)
        board.measure(MeasureSpec.makeMeasureSpec(width,MeasureSpec.EXACTLY),MeasureSpec.makeMeasureSpec(0,MeasureSpec.UNSPECIFIED))
        board.layout(0,0,width,board.measuredHeight)
        setMeasuredDimension(width,board.height)
    }
    private fun regions()=listOf(rows[1].keys.last().rect,rows[2].keys.last().rect,rows[3].keys[3].rect,board.previewSideBounds())
    override fun onDraw(canvas:Canvas) {
        board.draw(canvas)
        val d=resources.displayMetrics.density
        regions().forEachIndexed {index,rect->
            paint.color=tint;paint.alpha=if(index==slot)255 else 70;paint.strokeWidth=if(index==slot)2*d else d
            board.drawPreviewOutline(canvas,rect,index==3,paint)
        }
    }
    override fun onTouchEvent(event:MotionEvent):Boolean {
        if(event.actionMasked==MotionEvent.ACTION_UP) {
            regions().indexOfFirst {it.contains(event.x,event.y)}.takeIf {it>=0}?.let {pick(it)}
            performClick()
        }
        return true
    }
    override fun performClick():Boolean {super.performClick();return true}
}
