package com.kongye.keyboard

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.LruCache
import kotlin.math.floor
import kotlin.math.roundToInt

@androidx.annotation.Keep
enum class ReceiptStamp(val title:String) {
    CINNABAR("方章"), OVAL("椭圆章"), POSTMARK("邮戳"), TICKET("票据章");
    companion object {fun from(value:String?)=entries.firstOrNull {it.name==value}?:CINNABAR}
}
data class ReceiptSeal(val stamp:ReceiptStamp=ReceiptStamp.CINNABAR,val angle:Int=-14,val opacity:Int=82) {
    fun draw(context:Context,canvas:Canvas,bounds:RectF,theme:ReceiptTheme,date:String) {
        if(bounds.width()<=0f||bounds.height()<=0f)return
        val ratio=((bounds.height()/bounds.width())*100f).roundToInt().coerceIn(25,200)
        val key="${stamp.ordinal}:${theme.name}:$date:$ratio"
        val ink=synchronized(cache) {cache.get(key)?.takeUnless {it.isRecycled}} ?: renderBitmap(context,ratio,theme,date).also {
            synchronized(cache) {cache.put(key,it)}
        }
        canvas.save();canvas.rotate(angle.coerceIn(-30,30).toFloat(),bounds.centerX(),bounds.centerY())
        val paint=Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {alpha=(opacity.coerceIn(10,100)*2.55f).toInt()}
        canvas.drawBitmap(ink,null,bounds,paint)
        canvas.restore()
    }
    private fun renderBitmap(context:Context,ratio:Int,theme:ReceiptTheme,date:String):Bitmap {
        val xml=context.assets.open("receipt/stamp_${stamp.ordinal}.svg").bufferedReader().use {it.readText()}
            .replace("{{date}}",date.replace('-','.'))
            .replace("{{serial}}",date.replace("-","").takeLast(6))
        // SVG filters are not supported by our small renderer. Apply the missing ink-distress
        // effect to the stamp alpha itself, so paper color and other report art stay untouched.
        val width=384
        val height=(width*ratio/100).coerceIn(1,768)
        val ink=Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888)
        ReceiptBrandArt.drawSvg(Canvas(ink),RectF(0f,0f,width.toFloat(),height.toFloat()),xml,theme)
        val pixels=IntArray(width*height);ink.getPixels(pixels,0,width,0,0,width,height)
        fun noise(x:Int,y:Int):Float {
            var n=x*374761393+y*668265263+stamp.ordinal*1447
            n=(n xor (n ushr 13))*1274126177
            return ((n xor (n ushr 16)) ushr 8)/16777215f
        }
        fun field(x:Float,y:Float):Float {
            val ix=floor(x).toInt();val iy=floor(y).toInt()
            val fx=x-ix;val fy=y-iy
            return (noise(ix,iy)*(1-fx)+noise(ix+1,iy)*fx)*(1-fy)+
                (noise(ix,iy+1)*(1-fx)+noise(ix+1,iy+1)*fx)*fy
        }
        for(y in 0 until height)for(x in 0 until width) {
            val at=y*width+x;val color=pixels[at];val alpha=Color.alpha(color)
            if(alpha==0)continue
            val pressure=.48f+.52f*field(x/35f,y/35f)
            val dry=field(x/2.1f,y/2.1f)
            val grain=if(noise(x,y)<.12f)0f else if(dry<.3f).12f else .62f+.38f*dry
            pixels[at]=(color and 0x00ffffff) or ((alpha*pressure*grain).toInt().coerceIn(0,255) shl 24)
        }
        ink.setPixels(pixels,0,width,0,0,width,height)
        return ink
    }
    companion object {
        private val cache=LruCache<String,Bitmap>(16)
    }
}
