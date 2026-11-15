package com.kongye.keyboard

import android.content.Context
import kotlin.math.*

data class GlidePoint(val x:Float,val y:Float)
data class GlideTrace(val points:List<GlidePoint>,val centers:Map<Char,GlidePoint>,val unit:Float)

/** Offline shape matching over dictionary spellings, followed by the native decoder.
 * Endpoints/coarse shape prune candidates; dynamic time warping tolerates pace and curves.
 * No trace, typed text or result is persisted or sent over the network.
 */
object GlideDecoder {
    data class Match(val spelling:String,val cost:Float)
    private data class Word(val spelling:String,val weight:Int)
    @Volatile private var generation=0
    @Volatile private var chinese:List<Word>?=null
    @Volatile private var english:List<Word>?=null
    @Synchronized private fun words(ctx:Context,zh:Boolean):List<Word> {
        (if(zh) chinese else english)?.let {return it}
        val started=generation
        val list=HashMap<String,Int>()
        ctx.assets.open(if(zh) "lexicon.txt" else "glide-english.tsv").bufferedReader().useLines {lines ->
            lines.forEachIndexed {i,line ->
                if(zh && i==0) return@forEachIndexed
                val f=if(zh) line.split(' ') else line.split('\t')
                val spelling=f.firstOrNull()?.replace("'","").orEmpty()
                if(spelling.length in 2..32 && spelling.all {it in 'a'..'z'}) {
                    val weight=f.lastOrNull()?.toIntOrNull() ?: 1
                    list[spelling]=max(list[spelling] ?: 0,weight)
                }
            }
        }
        return list.map {Word(it.key,it.value)}.also {if(started==generation) {if(zh) chinese=it else english=it}}
    }
    fun warm(ctx:Context,zh:Boolean) {words(ctx,zh)}
    fun release() {generation++;chinese=null;english=null}
    private fun distance(a:GlidePoint,b:GlidePoint)=hypot(a.x-b.x,a.y-b.y)
    internal fun resample(points:List<GlidePoint>,n:Int):List<GlidePoint> {
        val filtered=points.filterIndexed {i,p ->i==0 || distance(p,points[i-1])>.001f}
        if(filtered.isEmpty()) return emptyList()
        if(filtered.size==1) return List(n) {filtered[0]}
        val lengths=FloatArray(filtered.size)
        for(i in 1 until lengths.size) lengths[i]=lengths[i-1]+distance(filtered[i-1],filtered[i])
        if(lengths.last()<=.001f) return List(n) {filtered[0]}
        var j=1
        return List(n) {i ->
            val d=lengths.last()*i/(n-1)
            while(j<lengths.lastIndex && lengths[j]<d) j++
            val t=((d-lengths[j-1])/(lengths[j]-lengths[j-1])).coerceIn(0f,1f)
            GlidePoint(filtered[j-1].x+(filtered[j].x-filtered[j-1].x)*t,filtered[j-1].y+(filtered[j].y-filtered[j-1].y)*t)
        }
    }
    private fun dtw(a:List<GlidePoint>,b:List<GlidePoint>):Float {
        var prev=FloatArray(b.size+1) {Float.POSITIVE_INFINITY};prev[0]=0f
        for(i in a.indices) {
            val next=FloatArray(b.size+1) {Float.POSITIVE_INFINITY}
            for(j in max(0,i-7)..min(b.lastIndex,i+7)) {
                val d=distance(a[i],b[j])
                next[j+1]=d*d+min(prev[j],min(prev[j+1],next[j]))
            }
            prev=next
        }
        return prev.last()/a.size
    }
    fun decode(ctx:Context,trace:GlideTrace,zh:Boolean,current:()->Boolean={true}):List<Match> {
        if(trace.points.size<2 || trace.centers.size!=26) return emptyList()
        val unit=trace.unit.coerceAtLeast(1f)
        fun norm(p:GlidePoint)=GlidePoint(p.x/unit,p.y/unit)
        val points=trace.points.map(::norm);val centers=trace.centers.mapValues {norm(it.value)}
        val coarse=resample(points,8)
        val candidates=ArrayList<Triple<Word,List<GlidePoint>,Float>>()
        for((i,word) in words(ctx,zh).withIndex()) {
            if(i%128==0 && !current()) throw java.util.concurrent.CancellationException()
            val first=centers.getValue(word.spelling.first());val last=centers.getValue(word.spelling.last())
            if(distance(first,points.first())>1.05f || distance(last,points.last())>1.05f) continue
            val template=word.spelling.map {centers.getValue(it)}
            val shape=resample(template,8)
            val score=coarse.indices.sumOf {distance(coarse[it],shape[it]).toDouble()}.toFloat()/8
            if(score<2.4f) candidates.add(Triple(word,template,score))
        }
        val sampled=resample(points,32)
        return candidates.sortedBy {it.third}.take(96).map {(word,template,_) ->
            if(!current()) throw java.util.concurrent.CancellationException()
            val score=dtw(sampled,resample(template,32))-.004f*ln(1+word.weight.toFloat())
            Match(word.spelling,score)
        }.sortedBy {it.cost}.filter {it.cost<1.25f}.take(6)
    }
}
