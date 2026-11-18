package com.kongye.keyboard

import android.content.Context
import android.os.SystemClock
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt

fun statsNumber(n:Long)=java.text.NumberFormat.getIntegerInstance().format(n)
fun statsDate(at:Long)=java.time.Instant.ofEpochMilli(at).atZone(java.time.ZoneId.systemDefault()).toLocalDate().toString()
data class StatsMethod(val id:String,val chars:Long,val activeMs:Long,val codes:Long,val candidateChars:Long,val choices:Long,val first:Long,val days:Int) {
    val title:String get()=when(id){"zh26"->"中文 · 拼音 · 26键";"zh14"->"中文 · 拼音 · 14键";"zh9"->"中文 · 拼音 · 九键";"en26"->"英文 · 26键";"hand_zh"->"中文 · 手写";"hand_en"->"英文 · 手写";"glide_zh"->"中文 · 滑行";"glide_en"->"英文 · 滑行";"voice"->"语音";"legacy"->"旧版记录";else->"粘贴"}
    val speed:Int? get()=if(id !in setOf("voice","paste","legacy") && activeMs>=5000 && chars>=5)(chars*60000.0/activeMs).roundToInt() else null
    val codeLength:Double? get()=if(candidateChars>0)codes.toDouble()/candidateChars else null
    val firstRate:Int? get()=if(choices>0)(first*100.0/choices).roundToInt() else null
}
data class StatsSnapshot(val today:Long,val total:Long,val todayManual:Long,val todayActive:Long,val peakSpeed:Long,val streak:Int,
    val daily:Map<String,Long>,val hourly:LongArray,val activeMs:Long,val keys:Long,val deletes:Long,val codes:Long,val candidateChars:Long,
    val choices:Long,val first:Long,val association:Long,val pasted:Long,val automatic:Long,val errors:LongArray,val heat:LongArray,
    val pairs:List<Pair<String,Long>>,val methods:List<StatsMethod>,val since:String,val asOf:Long=System.currentTimeMillis()) {
    val speed:Int? get()=if(todayActive>=5000 && todayManual>=5)(todayManual*60000.0/todayActive).roundToInt() else null
    val codeLength:Double? get()=if(candidateChars>0)codes.toDouble()/candidateChars else null
    val firstRate:Int? get()=if(choices>0)(first*100.0/choices).roundToInt() else null
    val deleteRate:Int? get()=if(keys>0)(deletes*100.0/keys).roundToInt() else null
    val level:Pair<String,Pair<String,Long>?> get() {
        val levels=listOf(0L to "初来乍到",500L to "渐入佳境",10000L to "笔耕不辍",50000L to "行云流水",100000L to "妙笔生花")
        val next=levels.firstOrNull {total<it.first}
        return levels.last {total>=it.first}.second to next?.let {it.second to (it.first-total)}
    }
    fun days(year:Boolean,at:Long=asOf):List<Pair<String,Long>> {
        val c=Calendar.getInstance().apply {timeInMillis=at}
        val n=if(year){c.add(Calendar.DAY_OF_YEAR,-364);365}else {c.add(Calendar.DAY_OF_YEAR,-29);30}
        return (0 until n).map {val day=statsDate(c.timeInMillis);c.add(Calendar.DAY_OF_YEAR,1);day to (daily[day] ?: 0)}
    }
}

/** Only aggregate counts and letter-level error totals are stored; no entered words are retained. */
class TypingStatsStore(private val file:File,legacyToday:Int=0,at:Long=System.currentTimeMillis()) {
    private var root=runCatching {JSONObject(file.readText())}.getOrElse {JSONObject().put("since",statsDate(at))}
    private val writer=Executors.newSingleThreadScheduledExecutor {r->Thread(r,"KongyeStatsSave")}
    private var pending:ScheduledFuture<*>?=null
    private var lastKey=0L
    private var lastMode=""
    private var sessionStart=0L
    private val recent=java.util.ArrayDeque<Pair<Long,Int>>()
    private fun JSONObject.obj(key:String)=optJSONObject(key) ?: JSONObject().also {put(key,it)}
    private fun JSONObject.arr(key:String,size:Int)=optJSONArray(key) ?: JSONArray().also {a->repeat(size){a.put(0L)};put(key,a)}
    private fun JSONObject.add(key:String,n:Long){put(key,optLong(key)+n)}
    private fun JSONArray.add(index:Int,n:Long){put(index,optLong(index)+n)}
    private fun method(mode:String)=root.obj("methods").obj(mode)
    private fun day(at:Long)=root.obj("daily").obj(statsDate(at))
    private fun markDay(mode:String,at:Long) {method(mode).obj("dates").put(statsDate(at),true)}
    init {
        if(!root.optBoolean("migrated")) {
            root.put("migrated",true)
            if(legacyToday>0){root.add("chars",legacyToday.toLong());day(at).add("chars",legacyToday.toLong());method("legacy").add("chars",legacyToday.toLong());markDay("legacy",at);markStreak(at)}
            schedule()
        }
    }
    @Synchronized private fun schedule() {
        pending?.cancel(false)
        pending=writer.schedule({save()},300,TimeUnit.MILLISECONDS)
    }
    private fun save() {
        val text=synchronized(this){root.toString()}
        runCatching {file.parentFile?.mkdirs();val temp=File(file.parentFile,file.name+".tmp");temp.writeText(text);check(temp.renameTo(file))}
    }
    fun persist() {synchronized(this){pending?.cancel(false)};writer.submit {save()}.get(5,TimeUnit.SECONDS)}
    fun close() {persist();synchronized(this){pending?.cancel(false)};writer.shutdown();writer.awaitTermination(5,TimeUnit.SECONDS)}
    private fun markStreak(at:Long) {
        val current=statsDate(at);val previous=root.optString("lastDate","")
        if(current<=previous)return
        val yesterday=Calendar.getInstance().apply {timeInMillis=at;add(Calendar.DAY_OF_YEAR,-1)}.let {statsDate(it.timeInMillis)}
        root.put("streak",if(previous==yesterday)root.optInt("streak")+1 else 1);root.put("lastDate",current)
    }
    @Synchronized fun key(mode:String,letter:Char?,deleted:Boolean,at:Long=System.currentTimeMillis(),elapsed:Long=SystemClock.elapsedRealtime()) {
        root.add("keys",1);day(at).add("keys",1);if(deleted)root.add("deletes",1)
        activity(mode,at,elapsed);schedule()
    }
    private fun activity(mode:String,at:Long,elapsed:Long) {
        val gap=elapsed-lastKey
        if(mode==lastMode && lastKey>0 && gap in 1..5000 && mode !in setOf("voice","paste","legacy")) {
            root.add("active",gap);day(at).add("active",gap);method(mode).add("active",gap)
        } else if(lastKey==0L || gap>5000 || mode!=lastMode) {recent.clear();sessionStart=elapsed}
        lastKey=elapsed;lastMode=mode
    }
    @Synchronized fun commit(mode:String,n:Int,at:Long=System.currentTimeMillis(),elapsed:Long=SystemClock.elapsedRealtime()) {
        if(n<=0)return
        val count=n.toLong();markStreak(at);root.add("chars",count);day(at).add("chars",count);method(mode).add("chars",count);markDay(mode,at)
        val hour=Calendar.getInstance().apply {timeInMillis=at}.get(Calendar.HOUR_OF_DAY)
        root.arr("hours",24).add(hour,count)
        if(mode=="paste")root.add("pasted",count)
        if(mode !in setOf("voice","paste","legacy")) {
            activity(mode,at,elapsed);day(at).add("manual",count)
            recent.addLast(elapsed to n);while(recent.isNotEmpty() && elapsed-recent.first.first>60000)recent.removeFirst()
            val span=(elapsed-sessionStart).coerceAtMost(60000)
            if(span>=5000){val speed=(recent.sumOf {it.second}*60000.0/span).roundToInt();if(speed>root.optLong("peakSpeed"))root.put("peakSpeed",speed)}
        }
        val cutoff=Calendar.getInstance().apply {timeInMillis=at;add(Calendar.DAY_OF_YEAR,-400)}.let {statsDate(it.timeInMillis)}
        val days=root.obj("daily");days.keys().asSequence().toList().filter {it<cutoff}.forEach {days.remove(it)}
        schedule()
    }
    @Synchronized fun choice(mode:String,codeCount:Int,charCount:Int,first:Boolean,edits:List<SpellingAssist.Edit>) {
        if(charCount<=0)return
        for(target in listOf(root,method(mode))) {
            target.add("choices",1);if(first)target.add("first",1)
            if(codeCount>0){target.add("codes",codeCount.toLong());target.add("candidateChars",charCount.toLong())}
        }
        errors(first,edits)
        schedule()
    }
    /** Heavy alignment runs on the statistics worker, never on the IME's drawing/input thread. */
    fun choiceFromReading(mode:String,codeCount:Int,charCount:Int,first:Boolean,typed:String,reading:String?) {
        choice(mode,codeCount,charCount,first,emptyList())
        if(reading.isNullOrEmpty())return
        if(typed.filter {it.isLetter()}.lowercase()==reading.filter {it.isLetter()}.lowercase())return
        writer.execute {val edits=SpellingAssist.edits(typed,reading);synchronized(this){errors(first,edits);schedule()}}
    }
    private fun errors(first:Boolean,edits:List<SpellingAssist.Edit>) {
        if(first && edits.isNotEmpty())root.add("automatic",1)
        for(edit in edits) {
            root.arr("errors",4).add(edit.kind,1)
            val chars=(edit.from+if(edit.from.isEmpty())edit.to else "").lowercase().filter {it in 'a'..'z'}
            chars.forEach {root.arr("heat",26).add(it-'a',1)}
            if(edit.from.length==1 && edit.to.length==1 && edit.from!=edit.to)root.obj("pairs").add(edit.from+"→"+edit.to,1)
        }
    }
    @Synchronized fun today(at:Long=System.currentTimeMillis())=root.obj("daily").optJSONObject(statsDate(at))?.optLong("chars") ?: 0L
    @Synchronized fun association(n:Int){if(n>0){root.add("association",n.toLong());schedule()}}
    @Synchronized fun endSession(){lastKey=0;lastMode="";recent.clear();schedule()}
    @Synchronized fun clear(at:Long=System.currentTimeMillis()){root=JSONObject().put("migrated",true).put("since",statsDate(at));lastKey=0;recent.clear();schedule()}
    @Synchronized fun snapshot(at:Long=System.currentTimeMillis()):StatsSnapshot {
        val day=statsDate(at);val days=root.obj("daily")
        val daily=days.keys().asSequence().associateWith {days.getJSONObject(it).optLong("chars")}
        val c=Calendar.getInstance().apply {timeInMillis=at};if((daily[day] ?: 0)==0L)c.add(Calendar.DAY_OF_YEAR,-1)
        var streak=0;while((daily[statsDate(c.timeInMillis)] ?: 0)>0){streak++;c.add(Calendar.DAY_OF_YEAR,-1)}
        if(root.has("lastDate")) {
            val yesterday=Calendar.getInstance().apply {timeInMillis=at;add(Calendar.DAY_OF_YEAR,-1)}.let {statsDate(it.timeInMillis)}
            streak=if(root.optString("lastDate") in listOf(day,yesterday))root.optInt("streak") else 0
        }
        val methods=root.obj("methods")
        val required=listOf("zh26","zh14","en26","hand_zh","hand_en")
        val methodRows=(required+methods.keys().asSequence().toList()).distinct().filter {it!="paste"}.map {id->
            val value=methods.optJSONObject(id) ?: JSONObject()
            StatsMethod(id,value.optLong("chars"),value.optLong("active"),value.optLong("codes"),value.optLong("candidateChars"),value.optLong("choices"),value.optLong("first"),value.optJSONObject("dates")?.length() ?: 0)
        }
        val pairs=root.obj("pairs");val today=days.optJSONObject(day) ?: JSONObject()
        return StatsSnapshot(today.optLong("chars"),root.optLong("chars"),today.optLong("manual"),today.optLong("active"),root.optLong("peakSpeed"),streak,daily,
            LongArray(24){root.arr("hours",24).optLong(it)},root.optLong("active"),root.optLong("keys"),root.optLong("deletes"),root.optLong("codes"),root.optLong("candidateChars"),root.optLong("choices"),root.optLong("first"),root.optLong("association"),root.optLong("pasted"),root.optLong("automatic"),
            LongArray(4){root.arr("errors",4).optLong(it)},LongArray(26){root.arr("heat",26).optLong(it)},pairs.keys().asSequence().map {it to pairs.optLong(it)}.sortedByDescending {it.second}.take(5).toList(),methodRows,root.optString("since",day),at)
    }
}
object TypingStats {
    private var store:TypingStatsStore?=null
    @Synchronized fun <T> replaceFiles(action:()->T):T {store?.close();store=null;return action()}

    @Synchronized fun of(ctx:Context):TypingStatsStore=store ?: TypingStatsStore(File(ctx.filesDir,"typing-stats.json"),Prefs.typedToday(ctx)).also {store=it}
}
