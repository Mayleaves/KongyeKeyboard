package com.kongye.keyboard

import android.content.Context
import android.graphics.Canvas
import android.graphics.RectF
import android.view.View

/** Uses live layout measurements; intentionally never draws labels or icons. */
class KeyboardSilhouette(private val ctx:Context) {
    private val board by lazy {KeyboardView(ctx).apply {preview=true;zh=true}}
    private var signature=""
    fun draw(c:Canvas,box:RectF,layout:String,background:Int,normal:Int,modifier:Int,split:Boolean=false,oneHand:Int=0,landscape:Boolean=false,filled:Boolean=true,scale:Float=Prefs.heightScale(ctx),gapOverride:Float?=null,sizeOverride:Float?=null) {
        val metrics=ctx.resources.displayMetrics;val p=Prefs.of(ctx)
        val sourceWidth=(if(landscape)maxOf(metrics.widthPixels,metrics.heightPixels) else metrics.widthPixels)*(if(oneHand==0)1f else .8f)
        val w=(if(split)maxOf(sourceWidth,700*metrics.density) else sourceWidth).toInt().coerceAtLeast(1)
        val skin=Prefs.theme(ctx).pick(Prefs.night(ctx)).styled(1f,p.getBoolean(Prefs.GLASS,false))
        val gap=gapOverride ?: p.getInt(V3Prefs.SPLIT_GAP,72).toFloat();val size=sizeOverride ?: p.getFloat(V3Prefs.SPLIT_SIZE,1f)
        val next="$layout/$w/$split/$scale/$gap/$size/${skin.keyGap}/${skin.keyH}/${skin.rowGap}/${skin.padH}"
        if(signature!=next) {
            board.heightScale=scale;board.splitLayout=split&&layout!="9";board.splitGap=gap;board.splitSize=size
            val rows=when(layout){"14"->Layouts.fourteen(ctx);"9"->Layouts.nine();else->Layouts.letters(true,ctx)}
            board.setup(skin,rows,if(layout=="9")Layouts.NINE_SIDE else null)
            board.measure(View.MeasureSpec.makeMeasureSpec(w,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(0,View.MeasureSpec.UNSPECIFIED))
            board.layout(0,0,w,board.measuredHeight);signature=next
        }
        val ratio=board.height.toFloat()/board.width
        var width=if(oneHand==0)box.width() else box.width()*.8f
        width=kotlin.math.min(width,box.height()/ratio)
        val height=width*ratio
        val left=when(oneHand){1->box.left;2->box.right-width;else->box.centerX()-width/2}
        val target=RectF(left,box.centerY()-height/2,left+width,box.centerY()+height/2)
        board.drawSilhouette(c,target,if(filled)background else 0,normal,modifier)
    }
}
