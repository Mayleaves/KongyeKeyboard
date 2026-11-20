package com.kongye.keyboard

import android.content.Context

/** Supplement a closed-label recognizer using general IDS geometry, not word overrides. */
@androidx.annotation.Keep
object HandwritingComponents {
    private val arity=mapOf('⿰' to 2,'⿱' to 2,'⿲' to 3,'⿳' to 3)
    @Volatile private var words:Map<String,List<String>> = emptyMap()
    @Volatile private var subtrees:Set<String> = emptySet()
    @Synchronized fun load(ctx:Context) {
        if(words.isNotEmpty())return
        val map=HashMap<String,MutableList<String>>();val parts=HashSet<String>()
        ctx.assets.open("handwriting-components.tsv").bufferedReader().useLines {lines->lines.forEach {line->
            val f=line.split('\t');if(f.size!=2)return@forEach
            map.getOrPut(f[0]){ArrayList()}.add(f[1])
            val codes=f[0].codePoints().toArray();var at=0
            fun node():String {
                val c=String(Character.toChars(codes[at++]));val n=arity[c.first()] ?: return c
                val value=c+(0 until n).joinToString(""){node()};parts.add(value);return value
            }
            node()
        }}
        subtrees=parts;words=map
    }
    fun release() {words=emptyMap();subtrees=emptySet()}

    fun recognize(strokes:List<List<FloatArray>>,known:Set<String>,current:()->Boolean,
                  predict:(List<List<FloatArray>>)->List<String>):List<String> {
        if(strokes.size<4 || words.isEmpty())return emptyList()
        val dictionary=words;val trees=subtrees
        data class Box(val left:Float,val top:Float,val right:Float,val bottom:Float) {
            fun min(axis:Int)=if(axis==0)left else top
            fun max(axis:Int)=if(axis==0)right else bottom
        }
        val bounds=strokes.map {s->Box(s.minOf {it[0]},s.minOf {it[1]},s.maxOf {it[0]},s.maxOf {it[1]})}
        val cache=HashMap<List<Int>,List<String>>();var budget=32
        fun split(ids:List<Int>,axis:Int,count:Int):List<List<Int>>? {
            val sorted=ids.sortedBy {(bounds[it].min(axis)+bounds[it].max(axis))/2}
            val low=ids.minOf {bounds[it].min(axis)};val high=ids.maxOf {bounds[it].max(axis)}
            val span=(high-low).coerceAtLeast(1f)
            val gaps=(0 until sorted.lastIndex).map {i->
                val a=(bounds[sorted[i]].min(axis)+bounds[sorted[i]].max(axis))/2
                val b=(bounds[sorted[i+1]].min(axis)+bounds[sorted[i+1]].max(axis))/2
                Triple(i,b-a,(a+b)/2)
            }.filter {(_,gap,cut)->gap>span*.08f && ids.none {bounds[it].min(axis)<cut-span*.20f && bounds[it].max(axis)>cut+span*.20f}}
                .sortedByDescending {it.second*minOf(it.first+1,ids.size-it.first-1)}.take(count-1).sortedBy {it.first}
            if(gaps.size!=count-1)return null
            val limits=listOf(-1)+gaps.map {it.first}+listOf(sorted.lastIndex)
            return limits.zipWithNext {a,b->sorted.subList(a+1,b+1).sorted()}.takeIf {it.all {part->part.isNotEmpty()}}
        }
        fun visit(ids:List<Int>,depth:Int):List<String> {
            cache[ids]?.let {return it}
            if(!current() || budget--<=0)return emptyList()
            val result=LinkedHashSet<String>()
            if(depth>0)result.addAll(predict(ids.map {strokes[it]}).take(2))
            // Small components are often written with two adjacent strokes in
            // the opposite order. Keep geometry unchanged and test those local
            // alternatives, rather than substituting a particular character.
            if(depth==2 && ids.size in 2..4)for(at in 0 until ids.lastIndex) {
                if(!current())return emptyList()
                val order=ids.toMutableList();val previous=order[at]
                order[at]=order[at+1];order[at+1]=previous
                result.addAll(predict(order.map {strokes[it]}).take(1))
            }
            if(depth<2 && ids.size>=4)for((op,n) in arity) {
                val groups=split(ids,if(op in "⿰⿲")0 else 1,n) ?: continue
                val choices=groups.map {visit(it,depth+1).take(18)}
                fun combine(at:Int,key:String) {
                    if(!current())return
                    if(at==choices.size) {
                        dictionary[key]?.take(4)?.let {result.addAll(it)}
                        if(key in trees)result.add(key)
                    } else choices[at].forEach {combine(at+1,key+it)}
                }
                combine(0,op.toString())
            }
            return result.take(18).also {cache[ids]=it}
        }
        return visit(strokes.indices.toList(),0).filter {it.codePointCount(0,it.length)==1 && it !in known &&
            Character.UnicodeScript.of(it.codePointAt(0))==Character.UnicodeScript.HAN}.distinct().take(12)
    }
}
