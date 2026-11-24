package com.kongye.keyboard

import android.graphics.Color
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface

/** First three palettes: code_artifact (4).html. Pure white: kongye-typing-receipt-v4.html. */
@androidx.annotation.Keep
enum class ReceiptTheme(
    val title: String, val paper: Int, val card: Int, val ink: Int, val sub: Int,
    val accent: Int, val rule: Int, val bar: Int, val heat: IntArray
) {
    WARM("米色", c("F7F3EA"), c("EFE9DC"), c("2C2825"), c("867C72"),
        c("C8553D"), c("D6CEC1"), c("CB6852"), colors("ECE5D7", "E7C6BC", "DC9584", "C8553D")),
    AMBER("黑色", c("151413"), c("1E1C1A"), c("EAE3D5"), c("8E867B"),
        c("D99B4E"), c("383026"), c("AA7634"), colors("1F1D1A", "3E301F", "865E2C", "D99B4E")),
    LETTERPRESS("方格纸", c("F0EFEA"), c("E6E4DD"), c("25282A"), c("6D7175"),
        c("A65E38"), c("C7C7C4"), c("54595E"), colors("E3E1DA", "D4C0B3", "BC8669", "A65E38")),
    WHITE("白色", Color.WHITE, c("F7F8FA"), c("1D1E22"), c("767B87"),
        c("C84B31"), c("E5E7EE"), c("23252B"), colors("F2F3F6", "E7C7C0", "D99283", "C84B31"));
    val stamp:Int get()=when(this) {WARM->c("C4452D");WHITE->c("C8402E");else->accent}
    fun drawInfoHeader(canvas:Canvas,bounds:RectF,date:String,year:Boolean=false) {
        if(this!=LETTERPRESS)return
        val scale=bounds.width()/920f
        val period=if(year)"365" else "30"
        val left="LETTERPRESS // KONGYE-${period}D"
        val right="NO. KY-${date.replace("-","")}-LOG$period"
        val paint=Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color=sub;typeface=Typeface.MONOSPACE;textSize=23f*scale
        }
        // Keep both columns clear at narrow preview widths as well as in the exported receipt.
        val available=bounds.width()-36f*scale
        val needed=paint.measureText(left)+paint.measureText(right)
        if(needed>available)paint.textSize*=available/needed
        val baseline=bounds.top+25f*scale
        canvas.drawText(left,bounds.left,baseline,paint)
        paint.textAlign=Paint.Align.RIGHT;canvas.drawText(right,bounds.right,baseline,paint)
        paint.color=rule;paint.strokeWidth=1.3f*scale
        canvas.drawLine(bounds.left,bounds.bottom,bounds.right,bounds.bottom,paint)
    }
    /** Shared paper texture for export and every section of the live style preview. */
    fun drawPaper(canvas:Canvas,bounds:RectF) {
        val paint=Paint(Paint.ANTI_ALIAS_FLAG).apply {color=paper}
        canvas.drawRect(bounds,paint)
        if(this!=LETTERPRESS)return
        val scale=bounds.width()/1080f
        val step=48f*scale
        if(step<=0f)return
        paint.color=Color.argb(16,37,40,42)
        paint.strokeWidth=(1.5f*scale).coerceAtLeast(.75f)
        var x=bounds.left
        while(x<=bounds.right){canvas.drawLine(x,bounds.top,x,bounds.bottom,paint);x+=step}
        var y=bounds.top
        while(y<=bounds.bottom){canvas.drawLine(bounds.left,y,bounds.right,y,paint);y+=step}
    }
    companion object {
        fun from(value: String?) = entries.firstOrNull { it.name == value } ?: WARM
    }
}
private fun c(hex: String) = Color.parseColor("#$hex")
private fun colors(vararg hex: String) = hex.map { c(it) }.toIntArray()
