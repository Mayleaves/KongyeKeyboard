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
        fun drawHeat(c:Canvas,rect:RectF,values:LongArray,ink:Int,base:Int,p:Paint) {
            val rows=listOf("qwertyuiop","asdfghjkl","zxcvbnm");val cell=rect.width()/10.8f;val h=rect.height()/3.2f
            val peak=(values.maxOrNull() ?: 0).coerceAtLeast(1).toFloat()
            for((row,letters) in rows.withIndex())for((index,ch) in letters.withIndex()) {
                val fraction=(values[ch-'a']/peak).coerceIn(0f,1f)
                fun mix(a:Int,b:Int)=(a+(b-a)*fraction).toInt()
                p.color=Color.rgb(mix(Color.red(base),239),mix(Color.green(base),83),mix(Color.blue(base),80))
                val x=rect.left+(rect.width()-letters.length*cell)/2+index*cell
                val y=rect.top+row*(h+rect.height()*.025f)
                c.drawRoundRect(RectF(x+cell*.06f,y,x+cell*.94f,y+h),cell*.15f,cell*.15f,p)
                p.color=if(fraction>.55f)Color.WHITE else ink;p.textSize=cell*.47f;p.typeface=Typeface.DEFAULT_BOLD;p.textAlign=Paint.Align.CENTER
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
    fun image(ctx:Context,s:StatsSnapshot,year:Boolean=false):File {
        val width=1080;val height=3700+s.methods.size*190
        val bitmap=Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888);val c=Canvas(bitmap);c.drawColor(Color.rgb(244,245,248))
        val p=Paint(Paint.ANTI_ALIAS_FLAG);val ink=Color.rgb(36,37,40);val muted=Color.rgb(106,109,115);var y=80f
        fun label(t:String,x:Float=70f,size:Float=34f,color:Int=ink,bold:Boolean=false) {p.textSize=size;p.color=color;p.typeface=if(bold)Typeface.DEFAULT_BOLD else Typeface.DEFAULT;p.textAlign=Paint.Align.LEFT;c.drawText(t,x,y,p)}
        fun section(t:String){y+=60;label(t,size=34f,bold=true);y+=28}
        fun card(h:Float,body:()->Unit){p.color=Color.WHITE;c.drawRoundRect(RectF(32f,y,1048f,y+h),32f,32f,p);val start=y;y+=60;body();y=start+h+12}
        fun line(left:String,right:String){label(left);p.textAlign=Paint.Align.RIGHT;c.drawText(right,1000f,y,p);p.textAlign=Paint.Align.LEFT;y+=68}
        fun bars(data:LongArray,h:Float){val peak=(data.maxOrNull() ?: 0).coerceAtLeast(1);val step=930f/data.size;for(i in data.indices){p.color=if(data[i]==0L)Color.LTGRAY else ink;val bh=max(3f,data[i]*h/peak);c.drawRoundRect(RectF(75+i*step,y+h-bh,75+(i+1)*step-step*.2f,y+h),3f,3f,p)};y+=h+58}
        label("打字统计",size=54f,bold=true);y+=52;label("空叶键盘 · ${statsDate(s.asOf)}",size=27f,color=muted);y+=32
        section("今天");card(400f){y+=40;label("${statsNumber(s.today)} 字",size=84f,bold=true);y+=75;label(s.level.first,size=35f);y+=55
            label(s.level.second?.let {"距「${it.first}」还差 ${statsNumber(it.second)} 字"} ?: "已解锁最高称号",size=29f,color=muted);y+=70
            line("速度 ${s.speed?.toString() ?: "—"} 字/分", "最快 ${s.peakSpeed.takeIf {it>0}?.toString() ?: "—"} 字/分")
            line("连续 ${s.streak} 天","累计 ${statsNumber(s.total)} 字")}
        section("每日字数 · ${if(year)"全年" else "近30天"}");card(390f){if(year){StatsYearView.drawYear(c,RectF(75f,y,1005f,y+230f),s.days(true),ink,Color.LTGRAY,ink,null,p);y+=288f}else bars(s.days(false).map {it.second}.toLongArray(),230f);label("单日最高 ${statsNumber(s.days(year).maxOf {it.second})} 字",size=29f,color=muted)}
        section("时段分布（累计）");card(335f){bars(s.hourly,180f);val hour=s.hourly.indices.maxByOrNull {s.hourly[it]} ?: 0;label(if(s.hourly.all {it==0L})"暂无时段记录" else "高峰 ${hour}:00—${hour+1}:00",size=29f,color=muted)}
        section("效率");card(565f){
            line("平均码长",code(s.codeLength));line("首选率",rate(s.firstRate));line("联想上屏","${statsNumber(s.association)} 字");line("粘贴","${statsNumber(s.pasted)} 字")
            val groups=linkedMapOf("全拼" to (Color.rgb(128,86,200) to 0L),"英文" to (Color.rgb(255,162,26) to 0L),"手写" to (Color.rgb(236,64,122) to 0L))
            for(m in s.methods){val name=when {m.id.startsWith("zh")->"全拼";m.id.startsWith("en")->"英文";m.id.startsWith("hand")->"手写";m.id.startsWith("glide")->"滑行";m.id=="voice"->"语音";else->"旧版"};val color=groups[name]?.first ?: Color.GRAY;if(m.chars>0 || name in groups)groups[name]=color to ((groups[name]?.second ?: 0)+m.chars)}
            val total=groups.values.sumOf {it.second}.coerceAtLeast(1);var x=70f
            y+=8
            for((_,pair) in groups){p.color=pair.first;val w=930f*pair.second/total;c.drawRect(x,y,x+w,y+22,p);x+=w}
            y+=65
            for((index,entry) in groups.entries.withIndex()){if(index==3)y+=45;val x0=70f+(index%3)*310;label("${entry.key} ${(entry.value.second*100.0/total).toInt()}%",x0,26f,entry.value.first)}
        }
        section("各输入方式速度");card(s.methods.size*190f+50) {
            val most=s.methods.maxOfOrNull {it.chars}?.coerceAtLeast(1) ?: 1
            for(m in s.methods) {
                line(m.title,m.speed?.let {"$it 字/分"} ?: "—")
                label("码长 ${code(m.codeLength)} · 首选 ${rate(m.firstRate)} · 使用 ${m.days} 天 · ${statsNumber(m.chars)} 字",size=26f,color=muted)
                y+=35;p.color=Color.LTGRAY;c.drawRect(70f,y,1000f,y+8,p)
                p.color=ink;c.drawRect(70f,y,70f+930f*m.chars/most,y+8,p);y+=85
            }
        }
        section("纠错");card(650f){line("自动纠错","${s.automatic} 次");line("错键 · 漏键 · 多键 · 颠倒",s.errors.joinToString(" · "));line("删除键占比",rate(s.deleteRate));StatsHeatView.drawHeat(c,RectF(70f,y,1010f,y+245),s.heat,ink,Color.rgb(231,232,236),p);y+=295;label("常见误触："+s.pairs.take(3).joinToString("  "){it.first}.ifEmpty {"暂无"},size=29f,color=muted)}
        section("累计");card(270f){line("按键次数",statsNumber(s.keys));line("输入时长",duration(s.activeMs));line("折合800字作文","${statsNumber(s.total/800)} 篇")}
        y+=45;label("速度按连续输入时间计算，语音和粘贴不计速度。",size=27f,color=muted);y+=45;label("记录起始 ${s.since} · 不保存输入内容",size=26f,color=muted)
        val dir=File(ctx.cacheDir,"stats").apply {mkdirs()};dir.listFiles()?.filter {it.name.startsWith("typing-stats-") && it.extension=="png" && it.lastModified()<System.currentTimeMillis()-86400000L}?.forEach {it.delete()}
        val file=File(dir,"typing-stats-${System.nanoTime()}.png")
        try {file.outputStream().use {bitmap.compress(Bitmap.CompressFormat.PNG,100,it)}}finally {bitmap.recycle()}
        return file
    }
}
