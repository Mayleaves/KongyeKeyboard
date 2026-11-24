package com.kongye.keyboard

import android.content.Context
import android.graphics.*
import androidx.core.graphics.PathParser
import org.xmlpull.v1.XmlPullParser

@androidx.annotation.Keep
enum class ReceiptBrand(val title:String) {
    KEYCAP("键帽"), BOOKMARK("书签"), LEAF("叶子"), PLATE("铭牌");
    companion object {fun from(value:String?)=entries.firstOrNull {it.name==value}?:KEYCAP}
}

/** Renders the four supplied SVGs locally, retaining their fine strokes and theme variables. */
object ReceiptBrandArt {
    fun draw(context:Context,canvas:Canvas,rect:RectF,brand:ReceiptBrand,theme:ReceiptTheme) {
        val xml=context.assets.open("receipt/brand_${brand.ordinal}.svg").bufferedReader().use {it.readText()}
        drawSvg(canvas,rect,xml,theme)
    }
    internal fun drawSvg(canvas:Canvas,rect:RectF,xml:String,theme:ReceiptTheme) {
        val parser=android.util.Xml.newPullParser()
        run {
            parser.setInput(java.io.StringReader(xml))
            val stack=java.util.ArrayDeque<Map<String,String>>()
            var attrs=emptyMap<String,String>()
            val p=Paint(Paint.ANTI_ALIAS_FLAG)
            val gradients=mutableMapOf<String,Shader>()
            fun resolveColor(value:String?)=when(value) {
                "var(--r-text)"->theme.ink;"var(--r-sub)"->theme.sub
                "var(--r-accent)"->theme.accent;"var(--r-stamp)","currentColor"->theme.stamp
                else->Color.BLACK
            }
            while(parser.eventType!=XmlPullParser.END_DOCUMENT) {
                val tag=parser.name
                if(parser.eventType==XmlPullParser.START_TAG) {
                    val own=(0 until parser.attributeCount).associate {parser.getAttributeName(it) to parser.getAttributeValue(it)}
                    when(tag) {
                        "linearGradient" -> {
                            val colors=mutableListOf<Int>();val stops=mutableListOf<Float>()
                            while(parser.next()!=XmlPullParser.END_DOCUMENT) {
                                if(parser.eventType==XmlPullParser.END_TAG&&parser.name=="linearGradient")break
                                if(parser.eventType==XmlPullParser.START_TAG&&parser.name=="stop") {
                                    val color=resolveColor(parser.getAttributeValue(null,"stop-color"))
                                    val alpha=parser.getAttributeValue(null,"stop-opacity")?.toFloatOrNull()?:1f
                                    colors.add(Color.argb((255*alpha).toInt(),Color.red(color),Color.green(color),Color.blue(color)))
                                    stops.add((parser.getAttributeValue(null,"offset")?:"0%").removeSuffix("%").toFloat()/100)
                                }
                            }
                            if(colors.size>=2)gradients[own.getValue("id")]=LinearGradient(
                                own["x1"]?.toFloatOrNull()?:0f,own["y1"]?.toFloatOrNull()?:0f,
                                own["x2"]?.toFloatOrNull()?:1f,own["y2"]?.toFloatOrNull()?:0f,
                                colors.toIntArray(),stops.toFloatArray(),Shader.TileMode.CLAMP)
                        }
                        "svg" -> {
                            val box=own.getValue("viewBox").split(" ").map {it.toFloat()}
                            val scale=minOf(rect.width()/box[2],rect.height()/box[3])
                            canvas.save();canvas.translate(rect.centerX()-box[2]*scale/2,rect.centerY()-box[3]*scale/2);canvas.scale(scale,scale)
                            stack.push(attrs);attrs=own
                        }
                        "g" -> {
                            stack.push(attrs);attrs=attrs+own;canvas.save()
                            Regex("translate\\(([-.0-9]+) ([-.0-9]+)\\)").find(own["transform"].orEmpty())?.let {
                                canvas.translate(it.groupValues[1].toFloat(),it.groupValues[2].toFloat())
                            }
                        }
                        else -> {
                            val a=attrs+own
                            fun n(key:String,default:Float=0f)=a[key]?.toFloatOrNull()?:default
                            fun color(value:String?)=resolveColor(value)
                            fun shape():Path?=when(tag) {
                                "path"->PathParser.createPathFromPathData(a.getValue("d"))
                                "rect"->Path().apply {addRoundRect(RectF(n("x"),n("y"),n("x")+n("width"),n("y")+n("height")),n("rx"),n("rx"),Path.Direction.CW)}
                                "circle"->Path().apply {addCircle(n("cx"),n("cy"),n("r"),Path.Direction.CW)}
                                "line"->Path().apply {moveTo(n("x1"),n("y1"));lineTo(n("x2"),n("y2"))}
                                else->null
                            }
                            if(tag=="text") {
                                val style=a["style"].orEmpty().split(';').mapNotNull {item->item.split(':',limit=2).takeIf {it.size==2}?.let {it[0].trim() to it[1].trim()}}.toMap()
                                val family=when {(style["font-family"]?:a["font-family"]).orEmpty().let {it.contains("Mono")||it.contains("mono")}->"monospace";style["font-family"].orEmpty().contains("Serif")||style["font-family"]=="serif"||a["font-family"]=="var(--font-serif)"->"serif";else->"sans-serif"}
                                p.style=Paint.Style.FILL;p.color=color(a["fill"]);p.textSize=(style["font-size"]?:a["font-size"])?.removeSuffix("px")?.toFloatOrNull()?:8f
                                p.typeface=Typeface.create(family,if(((style["font-weight"]?:a["font-weight"])?.toIntOrNull()?:400)>=600)Typeface.BOLD else Typeface.NORMAL)
                                p.textAlign=if(a["text-anchor"]=="middle")Paint.Align.CENTER else Paint.Align.LEFT
                                val text=parser.nextText()
                                val spacing=style["letter-spacing"]?.removeSuffix("em")?.toFloatOrNull()?.times(p.textSize)?:a["letter-spacing"]?.toFloatOrNull()?:0f
                                val w=p.measureText(text)+spacing*(text.length-1)
                                var x=n("x")-if(p.textAlign==Paint.Align.CENTER)w/2 else 0f
                                p.textAlign=Paint.Align.LEFT
                                for(ch in text) {val t=ch.toString();canvas.drawText(t,x,n("y"),p);x+=p.measureText(t)+spacing}
                            } else shape()?.let {path ->
                                p.typeface=Typeface.DEFAULT;p.strokeCap=Paint.Cap.ROUND;p.strokeJoin=Paint.Join.ROUND
                                if(a["fill"]!=null&&a["fill"]!="none") {
                                    p.style=Paint.Style.FILL;p.color=color(a["fill"]);p.alpha=(255*n("fill-opacity",1f)).toInt();canvas.drawPath(path,p)
                                }
                                if(a["stroke"]!=null&&a["stroke"]!="none") {
                                    p.style=Paint.Style.STROKE;p.color=color(a["stroke"]);p.strokeWidth=n("stroke-width",1f)
                                    p.alpha=(255*n("stroke-opacity",n("opacity",1f))).toInt()
                                    p.pathEffect=a["stroke-dasharray"]?.split(' ')?.mapNotNull {it.toFloatOrNull()}?.takeIf {it.size>=2}?.let {DashPathEffect(it.toFloatArray(),0f)}
                                    p.shader=gradients[a["stroke"]?.removePrefix("url(#")?.removeSuffix(")")]
                                    canvas.drawPath(path,p);p.pathEffect=null;p.shader=null
                                }
                            }
                        }
                    }
                } else if(parser.eventType==XmlPullParser.END_TAG&&(tag=="svg"||tag=="g")) {canvas.restore();attrs=stack.pop()}
                parser.next()
            }
        }
    }
}
