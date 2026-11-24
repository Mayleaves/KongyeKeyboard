package com.kongye.keyboard

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.view.MotionEvent
import android.view.View
import java.io.File
import java.util.Locale
import kotlin.math.max

class StatsBarsView(ctx:Context):View(ctx) {
    var values=LongArray(0)
    var ink=Color.DKGRAY
    var base=Color.LTGRAY
    var accent=Color.RED
    var selected=-1
    var onPick:((Int)->Unit)?=null
    private val p=Paint(Paint.ANTI_ALIAS_FLAG)
    override fun onDraw(c:Canvas) {
        if(values.isEmpty())return
        val peak=(values.maxOrNull() ?: 0).coerceAtLeast(1).toFloat();val step=width.toFloat()/values.size
        for(i in values.indices) {
            val h=max(2f*resources.displayMetrics.density,(height-4)*values[i]/peak)
            p.color=if(i==selected)accent else if(values[i]==0L)base else ink
            val gap=(step*.22f).coerceAtMost(8*resources.displayMetrics.density)
            c.drawRoundRect(RectF(i*step+gap/2,height-h,(i+1)*step-gap/2,height.toFloat()),(step*.15f).coerceAtMost(4f),4f,p)
        }
    }
    override fun onTouchEvent(e:MotionEvent):Boolean {
        if(values.isEmpty())return false
        if(e.actionMasked==MotionEvent.ACTION_UP){selected=(e.x/width*values.size).toInt().coerceIn(values.indices);invalidate();onPick?.invoke(selected);performClick()}
        return true
    }
    override fun performClick():Boolean {super.performClick();return true}
}
class StatsHeatView(ctx:Context):View(ctx) {
    var values=LongArray(26)
    var ink=Color.DKGRAY
    var base=Color.LTGRAY
    private val p=Paint(Paint.ANTI_ALIAS_FLAG)
    override fun onDraw(c:Canvas) {drawHeat(c,RectF(0f,0f,width.toFloat(),height.toFloat()),values,ink,base,p)}
    companion object {
        fun drawHeat(c:Canvas,rect:RectF,values:LongArray,ink:Int,base:Int,p:Paint,palette:IntArray?=null,peakInk:Int=Color.WHITE) {
            val rows=listOf("qwertyuiop","asdfghjkl","zxcvbnm");val cell=rect.width()/10.8f;val h=rect.height()/3.2f
            val peak=(values.maxOrNull() ?: 0).coerceAtLeast(1).toFloat()
            for((row,letters) in rows.withIndex())for((index,ch) in letters.withIndex()) {
                val fraction=(values[ch-'a']/peak).coerceIn(0f,1f)
                fun mix(a:Int,b:Int)=(a+(b-a)*fraction).toInt()
                p.color=if(palette==null)Color.rgb(mix(Color.red(base),239),mix(Color.green(base),83),mix(Color.blue(base),80))
                    else palette[if(fraction==0f)0 else if(fraction<.34f)1 else if(fraction<.67f)2 else 3]
                val x=rect.left+(rect.width()-letters.length*cell)/2+index*cell
                val y=rect.top+row*(h+rect.height()*.025f)
                c.drawRoundRect(RectF(x+cell*.06f,y,x+cell*.94f,y+h),cell*.15f,cell*.15f,p)
                p.color=if(fraction>.55f)peakInk else ink;p.textSize=cell*.47f;p.typeface=Typeface.DEFAULT_BOLD;p.textAlign=Paint.Align.CENTER
                c.drawText(ch.toString(),x+cell/2,y+h/2-(p.ascent()+p.descent())/2,p)
            }
            p.textAlign=Paint.Align.LEFT;p.typeface=Typeface.DEFAULT
        }
    }
}
object StatsReport {
    fun duration(ms:Long):String=if(ms<60000)"${ms/1000} 秒" else if(ms<3600000)"${ms/60000} 分钟" else "${ms/3600000} 小时 ${ms%3600000/60000} 分钟"
    private fun rate(n:Int?)=n?.let {"$it%"} ?: "—"
    private fun code(n:Double?)=n?.let {String.format(Locale.getDefault(),"%.1f",it)} ?: "—"
    fun text(s:StatsSnapshot,year:Boolean=false):String=buildString {
        appendLine("空叶键盘 · 打字统计");appendLine("${statsDate(s.asOf)} · ${s.level.first}")
        appendLine("今日 ${statsNumber(s.today)} 字，累计 ${statsNumber(s.total)} 字，连续 ${s.streak} 天")
        appendLine("今日速度 ${s.speed?.let {"$it 字/分"} ?: "—"}，最快 ${s.peakSpeed.takeIf {it>0}?.let {"$it 字/分"} ?: "—"}")
        appendLine("${if(year)"全年" else "近30天"}每日字数：")
        for((day,count) in s.days(year))appendLine("$day  $count")
        appendLine("时段分布（累计）："+s.hourly.mapIndexed {i,n->"$i 点 $n 字"}.joinToString("；"))
        appendLine("平均码长 ${code(s.codeLength)}，首选率 ${rate(s.firstRate)}，联想上屏 ${s.association} 字，粘贴 ${s.pasted} 字")
        for(m in s.methods)appendLine("${m.title}：${m.speed?.let {"$it 字/分"} ?: "—"}，码长 ${code(m.codeLength)}，首选 ${rate(m.firstRate)}，使用 ${m.days} 天，${m.chars} 字")
        appendLine("自动纠错 ${s.automatic} 次；错键 ${s.errors[0]}，漏键 ${s.errors[1]}，多键 ${s.errors[2]}，颠倒 ${s.errors[3]}")
        appendLine("删除键占比 ${rate(s.deleteRate)}；常见误触："+s.pairs.joinToString("，"){it.first+"（${it.second}次）"}.ifEmpty {"暂无"})
        appendLine("按键 ${s.keys} 次，连续输入时长 ${duration(s.activeMs)}，折合800字作文 ${s.total/800} 篇")
        appendLine("记录起始 ${s.since}；旧版未记录的指标不补造；速度不包含语音和粘贴。")
    }
    fun image(ctx:Context,s:StatsSnapshot,year:Boolean=false,theme:ReceiptTheme=ReceiptTheme.WARM,brand:ReceiptBrand=ReceiptBrand.KEYCAP,seal:ReceiptSeal=ReceiptSeal(),footer:ReceiptFooter=ReceiptFooter.LEAF):File {
        val width=1080; val height=5800+s.methods.size*200
        val bitmap=Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888)
        val c=Canvas(bitmap); val p=Paint(Paint.ANTI_ALIAS_FLAG)
        val paper=theme.paper;val ink=theme.ink;val muted=theme.sub
        val accent=theme.accent;val tile=theme.card
        val numberFace=if(theme==ReceiptTheme.LETTERPRESS)Typeface.MONOSPACE else Typeface.DEFAULT
        fun face(text:String,bold:Boolean):Typeface {
            val base=if(text.any {it in '0'..'9'})numberFace else Typeface.DEFAULT
            return Typeface.create(base,if(bold)Typeface.BOLD else Typeface.NORMAL)
        }
        // Clip both ends to a receipt edge; exported PNG retains the transparent cut-outs.
        var y=105f
        fun label(t:String,x:Float=80f,size:Float=34f,color:Int=ink,bold:Boolean=false) {
            p.textSize=size;p.color=color;p.typeface=face(t,bold)
            p.textAlign=Paint.Align.LEFT;c.drawText(t,x,y,p)
        }
        fun center(t:String,size:Float=34f,color:Int=ink,bold:Boolean=false) {
            p.textSize=size;p.color=color;p.typeface=face(t,bold)
            p.textAlign=Paint.Align.CENTER;c.drawText(t,width/2f,y,p);p.textAlign=Paint.Align.LEFT
        }
        fun rule() {
            p.color=theme.rule;p.strokeWidth=2f;p.pathEffect=android.graphics.DashPathEffect(floatArrayOf(11f,11f),0f)
            c.drawLine(80f,y,1000f,y,p);p.pathEffect=null
        }
        var section=0
        fun heading(t:String) {
            y+=55;rule();y+=78;section++
            label("%02d".format(section),size=34f,color=accent,bold=true)
            label(t,x=158f,size=37f,bold=true);y+=65
        }
        fun line(left:String,right:String) {
            label(left,color=muted,size=33f)
            p.color=ink;p.typeface=face(right,false);p.textAlign=Paint.Align.RIGHT;c.drawText(right,1000f,y,p);p.textAlign=Paint.Align.LEFT;y+=66
        }
        fun bars(data:LongArray,h:Float) {
            val peak=(data.maxOrNull() ?: 0).coerceAtLeast(1);val step=920f/data.size
            for(i in data.indices) {
                p.color=if(data[i]==0L)tile else theme.bar;val bh=max(4f,data[i]*h/peak)
                c.drawRoundRect(RectF(80+i*step,y+h-bh,80+(i+1)*step-step*.22f,y+h),4f,4f,p)
            };y+=h+45
        }
        // Reserve a large canvas, then trim the bottom to the actual content height.
        theme.drawPaper(c,RectF(0f,0f,width.toFloat(),height.toFloat()))
        val headerSpace=if(theme==ReceiptTheme.LETTERPRESS)100f else 0f
        theme.drawInfoHeader(c,RectF(80f,62f,1000f,117f),statsDate(s.asOf),year)
        ReceiptBrandArt.draw(ctx,c,RectF(190f,65f+headerSpace,890f,315f+headerSpace),brand,theme);y=400f+headerSpace
        center("${if(year)"全年" else "近30天"}打字报告",size=47f,bold=true);y+=55
        center("截至 ${statsDate(s.asOf)}",size=29f,color=muted);y+=65;rule();y+=82
        center("今日输入",size=32f,color=muted);y+=136
        center("${statsNumber(s.today)} 字",size=112f,bold=true);y+=67
        center(s.level.first,size=33f,color=accent);y+=58
        val metrics=listOf(statsNumber(s.total) to "累计字数",s.streak.toString() to "连续天数",duration(s.activeMs) to "输入时长")
        for((index,metric) in metrics.withIndex()) {
            val x=80f+index*314;p.color=tile;c.drawRoundRect(RectF(x,y,x+292,y+156),23f,23f,p)
            val old=y;y+=65;label(metric.first,x+24,if(metric.first.length>9)29f else 43f,bold=true)
            y+=51;label(metric.second,x+24,27f,muted);y=old
        };y+=170
        heading("每日字数 · ${if(year)"全年" else "近30天"}")
        if(year) {
            StatsYearView.drawYear(c,RectF(80f,y,1000f,y+210f),s.days(true),ink,tile,accent,null,p);y+=265
        } else {
            bars(s.days(false).map {it.second}.toLongArray(),245f)
            line(s.days(false).first().first,"今天")
        }
        line("活跃天数","${s.days(year).count {it.second>0}} 天")
        line("单日最高","${statsNumber(s.days(year).maxOf {it.second})} 字")
        heading("时段分布")
        bars(s.hourly,180f);line("0 点","23 点")
        val hour=s.hourly.indices.maxByOrNull {s.hourly[it]} ?: 0
        line("累计高峰",if(s.hourly.all {it==0L})"暂无记录" else "${hour}:00—${hour+1}:00")
        heading("输入效率")
        line("今日速度",s.speed?.let {"$it 字/分"} ?: "—")
        line("最快速度",s.peakSpeed.takeIf {it>0}?.let {"$it 字/分"} ?: "—")
        line("平均码长",code(s.codeLength));line("首选率",rate(s.firstRate))
        line("联想上屏","${statsNumber(s.association)} 字");line("粘贴","${statsNumber(s.pasted)} 字")
        heading("各输入方式")
        val most=s.methods.maxOfOrNull {it.chars}?.coerceAtLeast(1) ?: 1
        if(s.methods.isEmpty()) {label("暂无输入记录",color=muted);y+=60}
        for(m in s.methods) {
            label(m.title,size=34f,bold=true)
            p.textAlign=Paint.Align.RIGHT;p.color=accent;c.drawText(m.speed?.let {"$it 字/分"} ?: "—",1000f,y,p);p.textAlign=Paint.Align.LEFT;y+=48
            label("码长 ${code(m.codeLength)} · 首选 ${rate(m.firstRate)} · 使用 ${m.days} 天 · ${statsNumber(m.chars)} 字",size=26f,color=muted)
            y+=29;p.color=tile;c.drawRect(80f,y,1000f,y+12,p)
            p.color=accent;c.drawRect(80f,y,80f+920f*m.chars/most,y+12,p);y+=70
        }
        heading("纠错与按键")
        line("自动纠错","${statsNumber(s.automatic)} 次")
        line("错键 · 漏键 · 多键 · 颠倒",s.errors.joinToString(" · "))
        line("删除键占比",rate(s.deleteRate))
        StatsHeatView.drawHeat(c,RectF(80f,y,1000f,y+280f),s.heat,ink,tile,p,theme.heat,if(theme==ReceiptTheme.AMBER)Color.rgb(20,19,18) else Color.WHITE);y+=340
        label("常见误触："+s.pairs.take(3).joinToString("  "){it.first}.ifEmpty {"暂无"},size=30f,color=muted);y+=66
        heading("累计记录")
        line("按键次数",statsNumber(s.keys));line("输入时长",duration(s.activeMs))
        line("折合800字作文","${statsNumber(s.total/800)} 篇")
        y+=30;rule()
        val sealTop=y+30
        seal.draw(ctx,c,RectF(760f,sealTop,990f,sealTop+260f),theme,statsDate(s.asOf))
        y+=72;label("速度按连续输入时间计算，",size=26f,color=muted);y+=43
        label("语音和粘贴不计速度。",size=26f,color=muted);y+=53
        label("记录起始 ${s.since}",size=25f,color=muted);y+=43
        label("仅统计数量，不保存输入内容",size=25f,color=muted)
        y=sealTop+330
        footer.draw(ctx,c,RectF(190f,y,890f,y+165f),theme,s);y+=250
        val used=y.toInt().coerceAtMost(height)
        if(theme==ReceiptTheme.AMBER) {
            p.style=Paint.Style.STROKE;p.strokeWidth=2f;p.color=theme.rule
            c.drawRoundRect(RectF(30f,30f,width-30f,used-30f),9f,9f,p);p.style=Paint.Style.FILL
        }
        val result=Bitmap.createBitmap(width,used,Bitmap.Config.ARGB_8888)
        val output=Canvas(result);val edge=android.graphics.Path().apply {
            val teeth=36;val pitch=width.toFloat()/teeth;val depth=20f
            moveTo(0f,depth)
            for(i in 0 until teeth) {lineTo((i+.5f)*pitch,0f);lineTo((i+1)*pitch,depth)}
            lineTo(width.toFloat(),used-depth)
            for(i in teeth downTo 1) {lineTo((i-.5f)*pitch,used.toFloat());lineTo((i-1)*pitch,used-depth)}
            close()
        }
        output.clipPath(edge);output.drawBitmap(bitmap,0f,0f,null);bitmap.recycle()
        val dir=File(ctx.cacheDir,"stats").apply {mkdirs()}
        dir.listFiles()?.filter {it.name.startsWith("typing-stats-") && it.extension=="png" && it.lastModified()<System.currentTimeMillis()-86400000L}?.forEach {it.delete()}
        val file=File(dir,"typing-stats-${System.nanoTime()}.png")
        try {file.outputStream().use {check(result.compress(Bitmap.CompressFormat.PNG,100,it))}}finally {result.recycle()}
        return file
    }
}
