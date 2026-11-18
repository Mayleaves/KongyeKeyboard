package com.kongye.keyboard

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View

/** Real four-row keyboard renderer, with selectable letter/comma/period keys. */
class FlickPreviewView(ctx:Context):View(ctx) {
    private val board=KeyboardView(ctx).apply {preview=true;zh=true;swipeEnabled=true}
    private var rows=emptyList<Row>()
    private var selected='q'
    private var sourceWidth=1
    private val paint=Paint(Paint.ANTI_ALIAS_FLAG).apply {style=Paint.Style.STROKE}
    var onPick:(Char)->Unit={}
    fun submit(zh:Boolean,letter:Char,down:Boolean,draft:String,revision:Int=0,literalDraft:Boolean=false,draftIcon:Int=0) {
        tag=revision;selected=letter
        val skin=Prefs.skin(context)
        val legend=if(draftIcon==0)KeyGestures.Action("text:$draft").label else draft
        rows=Layouts.letters(zh,context).map {row->Row(row.keys.map {key->
            if(key.gestureId!=letter||draft.isEmpty()&&!literalDraft)key else
                Key(key.kind,key.label,key.out,if(down)legend else key.hint,if(down)key.hint2 else legend,key.w,key.look,key.icon,key.longEmoji,key.repeat,key.letters).apply {
                    gestureId=key.gestureId;reversedSwipe=key.reversedSwipe
                    hintIcon=if(down)draftIcon else key.hintIcon
                    hint2Icon=if(down)key.hint2Icon else draftIcon
                }
        },row.indent)}
        board.zh=zh;board.heightScale=Prefs.heightScale(context);board.swipeEnabled=true
        board.setup(skin,rows)
        contentDescription="上下滑按键预览，当前选择${KeyGestures.displayName(zh,letter)}，可选择字母、逗号和句号"
        requestLayout();invalidate()
    }
    override fun onMeasure(w:Int,h:Int) {
        val width=MeasureSpec.getSize(w)
        sourceWidth=resources.displayMetrics.widthPixels.coerceAtLeast(1)
        board.measure(MeasureSpec.makeMeasureSpec(sourceWidth,MeasureSpec.EXACTLY),MeasureSpec.makeMeasureSpec(0,MeasureSpec.UNSPECIFIED))
        board.layout(0,0,sourceWidth,board.measuredHeight)
        setMeasuredDimension(width,resolveSize((board.height*width.toFloat()/sourceWidth).toInt(),h))
    }
    private fun scale()=minOf(width.toFloat()/sourceWidth,height.toFloat()/board.height.coerceAtLeast(1))
    fun keyBounds(id:Char):RectF? {
        val key=rows.flatMap {it.keys}.firstOrNull {it.gestureId==id} ?: return null
        val s=scale()
        return RectF(key.rect.left*s+(width-sourceWidth*s)/2,key.rect.top*s+(height-board.height*s)/2,
            key.rect.right*s+(width-sourceWidth*s)/2,key.rect.bottom*s+(height-board.height*s)/2)
    }
    override fun onDraw(c:Canvas) {
        val s=scale()
        c.save();c.translate((width-sourceWidth*s)/2,(height-board.height*s)/2);c.scale(s,s)
        board.draw(c)
        rows.flatMap {it.keys}.firstOrNull {it.gestureId==selected}?.let {key->
            paint.color=Prefs.skin(context).candHi;paint.strokeWidth=2*resources.displayMetrics.density
            board.drawPreviewOutline(c,key.rect,false,paint)
        }
        c.restore()
    }
    override fun onTouchEvent(e:MotionEvent):Boolean {
        if(e.actionMasked==MotionEvent.ACTION_UP) {
            KeyGestures.editableKeys.firstOrNull {keyBounds(it)?.contains(e.x,e.y)==true}?.let {onPick(it)}
            performClick()
        }
        return true
    }
    override fun performClick():Boolean {super.performClick();return true}
}
