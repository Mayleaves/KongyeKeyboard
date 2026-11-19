package com.kongye.keyboard

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.View

/** Draws the production toolbar and keys from the selected mode's slider drafts. */
class KeyboardAdjustmentPreview(context:Context):View(context) {
    private val board=KeyboardView(context).apply {preview=true;zh=true}
    private val toolbar=CandidateBar(context)
    private val bottomStrip=BottomStrip(context,{},{})
    private val paint=Paint(Paint.ANTI_ALIAS_FLAG)
    private val icons=Icons(context)
    private var skin=Prefs.skin(context)
    private var signature=""
    private var sourceWidth=1
    private var sourceHeight=1
    private var leftPixels=0
    private var floating=false
    private var keyboardScale=1f
    private val d get()=resources.displayMetrics.density
    fun configure(skin:Skin,layout:String,height:Float,left:Int,right:Int,bottom:Int,gap:Float,size:Float,split:Boolean,floating:Boolean=false,floatWidth:Float=1f) {
        val next="$skin/$layout/$height/$left/$right/$bottom/$gap/$size/$split/$floating/$floatWidth/${Prefs.tools(context)}"
        if(next==signature)return
        signature=next;this.skin=skin;this.floating=floating;keyboardScale=height
        sourceWidth=resources.displayMetrics.widthPixels
        leftPixels=if(floating)0 else (left*d).toInt()
        val keyWidth=if(floating)(sourceWidth*floatWidth).toInt().coerceAtLeast((90*d).toInt())
            else (sourceWidth-leftPixels-(right*d).toInt()).coerceAtLeast((90*d).toInt())
        board.heightScale=height;board.splitLayout=split&&layout!="9";board.splitGap=gap;board.splitSize=size
        board.setup(skin,when(layout){"14"->if(board.splitLayout)Layouts.splitFourteen(context)else Layouts.fourteen(context);"9"->Layouts.nine();else->Layouts.letters(true,context)},if(layout=="9")Layouts.NINE_SIDE else null)
        toolbar.skin=skin;toolbar.uiScale=height;toolbar.showTools(listOf("more")+Prefs.tools(context))
        bottomStrip.skin=skin;bottomStrip.uiScale=height;bottomStrip.extra=if(floating)0 else (bottom*d).toInt()
        fun measure(view:View,width:Int) {
            view.measure(MeasureSpec.makeMeasureSpec(width,MeasureSpec.EXACTLY),MeasureSpec.makeMeasureSpec(0,MeasureSpec.UNSPECIFIED))
            view.layout(0,0,width,view.measuredHeight)
        }
        measure(toolbar,keyWidth);measure(board,keyWidth);measure(bottomStrip,sourceWidth)
        sourceHeight=toolbar.height+board.height+if(floating)((26*height+32)*d).toInt() else bottomStrip.height
        contentDescription=if(floating)"悬浮键盘实时预览：高度${(height*100).toInt()}%，宽度${(floatWidth*100).toInt()}%，分体间距${gap.toInt()}，按键尺寸${(size*100).toInt()}%，${if(split)"分体" else "完整"}"
            else "键盘实时预览：高度${(height*100).toInt()}%，左侧${left}，右侧${right}，底部${bottom}，分体间距${gap.toInt()}，按键尺寸${(size*100).toInt()}%，${if(split)"分体" else "完整"}"
        requestLayout();invalidate()
    }
    override fun onMeasure(widthMeasureSpec:Int,heightMeasureSpec:Int) {
        val width=MeasureSpec.getSize(widthMeasureSpec)
        val wanted=(sourceHeight*width.toFloat()/sourceWidth).toInt()
        setMeasuredDimension(width,resolveSize(wanted,heightMeasureSpec))
    }
    override fun onDraw(canvas:Canvas) {
        val scale=minOf(width.toFloat()/sourceWidth,height.toFloat()/sourceHeight)
        canvas.save();canvas.translate((width-sourceWidth*scale)/2,(height-sourceHeight*scale)/2);canvas.scale(scale,scale)
        if(floating) {
            val x=(sourceWidth-board.width)/2f
            val top=16*d
            val header=26*d*keyboardScale
            val bounds=RectF(x,top,x+board.width,top+header+toolbar.height+board.height)
            paint.color=0x14000000;canvas.drawRoundRect(RectF(bounds).apply {offset(0f,4*d)},18*d,18*d,paint)
            val outline=Path().apply {addRoundRect(bounds,18*d,18*d,Path.Direction.CW)}
            canvas.save();canvas.clipPath(outline)
            paint.color=skin.kbBg;canvas.drawRect(bounds,paint)
            canvas.translate(x,top)
            paint.color=(skin.barFg and 0x00ffffff) or (150 shl 24)
            val hd=d*keyboardScale
            canvas.drawRoundRect(RectF(board.width/2f-24*hd,10*hd,board.width/2f+24*hd,15*hd),3*hd,3*hd,paint)
            icons.scale=keyboardScale
            icons.draw(canvas,R.drawable.ic_close,board.width-20*hd,header/2f,16f,skin.barFg)
            canvas.translate(0f,header);toolbar.draw(canvas)
            canvas.translate(0f,toolbar.height.toFloat());board.draw(canvas)
            canvas.restore()
        } else {
            canvas.drawColor(skin.kbBg)
            canvas.save();canvas.clipRect(0,0,sourceWidth,sourceHeight)
            canvas.save();canvas.translate(leftPixels.toFloat(),0f);toolbar.draw(canvas);canvas.translate(0f,toolbar.height.toFloat());board.draw(canvas);canvas.restore()
            canvas.translate(0f,(toolbar.height+board.height).toFloat());bottomStrip.draw(canvas)
            canvas.restore()
        }
        canvas.restore()
    }
}
