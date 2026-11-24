package com.kongye.keyboard

import android.content.Context
import android.graphics.Canvas
import android.graphics.RectF

@androidx.annotation.Keep
enum class ReceiptFooter(val title:String) {
    LEAF("叶子"), BARCODE("条形码"), SOUNDWAVE("声波"), COLOPHON("文字");
    fun draw(context:Context,canvas:Canvas,bounds:RectF,theme:ReceiptTheme,snapshot:StatsSnapshot) {
        val xml=context.assets.open("receipt/footer_${ordinal}.svg").bufferedReader().use {it.readText()}
            .replace("{{serial}}",statsDate(snapshot.asOf).replace("-","").takeLast(6))
            .replace("{{keys}}",statsNumber(snapshot.keys))
        ReceiptBrandArt.drawSvg(canvas,bounds,xml,theme)
    }
    companion object {fun from(value:String?)=entries.firstOrNull {it.name==value}?:LEAF}
}
