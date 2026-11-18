package com.kongye.keyboard

import android.content.Context
import android.graphics.*
import android.view.*

/** Live editable 26-key preview; the same saved mappings as the real keyboard. */
class FlickPreviewView(ctx:Context):View(ctx) {
    private var chinese=true;private var selected='q';private var down=true;private var draft="";private var literalDraft=false
    var onPick:(Char)->Unit={}
    private var keys=emptyList<Key>()
    private val paint=Paint(Paint.ANTI_ALIAS_FLAG)
    private val icons=Icons(ctx)
    private var draftIcon=0
    private val boxes=ArrayList<Pair<Char,RectF>>()
    private val d get()=resources.displayMetrics.density
    fun submit(zh:Boolean,letter:Char,down:Boolean,draft:String,revision:Int=0,literalDraft:Boolean=false,draftIcon:Int=0) {
        tag=revision
        chinese=zh;selected=letter;this.down=down;this.draft=draft;this.literalDraft=literalDraft;this.draftIcon=draftIcon
        keys=Layouts.letters(zh,context).flatMap {it.keys}.filter {it.kind==Kind.LETTER};invalidate()
    }
    override fun onMeasure(w:Int,h:Int) {setMeasuredDimension(MeasureSpec.getSize(w),(178*d).toInt())}
    override fun onDraw(c:Canvas) {
        val skin=Prefs.skin(context);boxes.clear()
        paint.style=Paint.Style.FILL;paint.color=skin.kbBg
        c.drawRoundRect(RectF(0f,0f,width.toFloat(),height.toFloat()),12*d,12*d,paint)
        val gap=4*d;val unit=(width-16*d-9*gap)/10;val kh=52*d
        val rows=listOf("qwertyuiop","asdfghjkl","zxcvbnm")
        rows.forEachIndexed {row,letters ->
            val total=letters.length*unit+(letters.length-1)*gap;val start=(width-total)/2
            letters.forEachIndexed {col,ch ->
                val rect=RectF(start+col*(unit+gap),4*d+row*(kh+5*d),start+col*(unit+gap)+unit,4*d+row*(kh+5*d)+kh)
                boxes.add(ch to rect)
                paint.style=Paint.Style.FILL;paint.color=if(ch==selected) skin.modBg else skin.keyBg;c.drawRoundRect(rect,9*d,9*d,paint)
                if(ch==selected){paint.style=Paint.Style.STROKE;paint.strokeWidth=1.5f*d;paint.color=skin.candHi;c.drawRoundRect(rect,9*d,9*d,paint);paint.style=Paint.Style.FILL}
                paint.textSize=22*d;paint.textAlign=Paint.Align.CENTER;paint.color=skin.keyFg
                c.drawText(ch.toString(),rect.centerX(),rect.centerY()+7*d,paint)
                val key=keys.firstOrNull {it.label==ch.toString()}
                var top=key?.hint.orEmpty();var bottom=key?.hint2.orEmpty()
                var topIcon=key?.hintIcon ?: 0;var bottomIcon=key?.hint2Icon ?: 0
                if(ch==selected && (draft.isNotEmpty() || literalDraft)) {if(down){top=draft;topIcon=draftIcon}else{bottom=draft;bottomIcon=draftIcon}}
                fun short(s:String)=if(s.codePointCount(0,s.length)>3)s.substring(0,s.offsetByCodePoints(0,2))+"…" else s
                paint.textSize=9*d;paint.color=skin.barFg;paint.textAlign=Paint.Align.RIGHT
                if(topIcon!=0)icons.draw(c,topIcon,rect.right-8*d,rect.top+8*d,10f,skin.barFg)
                else c.drawText(short(top),rect.right-3*d,rect.top+11*d,paint)
                if(bottomIcon!=0)icons.draw(c,bottomIcon,rect.left+8*d,rect.bottom-8*d,10f,skin.barFg)
                else {paint.textAlign=Paint.Align.LEFT;c.drawText(short(bottom),rect.left+3*d,rect.bottom-4*d,paint)}
            }
        }
    }
    override fun onTouchEvent(e:MotionEvent):Boolean {
        if(e.actionMasked==MotionEvent.ACTION_UP) boxes.firstOrNull {it.second.contains(e.x,e.y)}?.first?.let {onPick(it);performClick()}
        return true
    }
    override fun performClick():Boolean {super.performClick();return true}
}
