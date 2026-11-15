package com.kongye.keyboard

import kotlin.math.*

/** Bounded spelling-lattice repairs. The Chinese words and their order come from libime. */
object SpellingAssist {
    data class Feedback(val errors:BooleanArray,val swaps:List<Int>)
    private fun quality(raw:String):Float {
        val syllables=Lexicon.pinyinSyllables();if(syllables.isEmpty()) return 0f
        val dp=FloatArray(raw.length+1) {1000f};dp[0]=0f
        for(i in raw.indices) {
            dp[i+1]=min(dp[i+1],dp[i]+if(raw[i] in "bpmfdtnlgkhjqxrzcsyw") .3f else 3.4f)
            for(s in syllables) if(raw.startsWith(s,i)) dp[i+s.length]=min(dp[i+s.length],dp[i]+.08f)
        }
        return dp.last()
    }
    fun transpositions(raw:String):List<String> {
        if(raw.length !in 4..40 || raw.any {it !in 'a'..'z'}) return emptyList()
        val broken=IntArray(raw.length+1) {1000};broken[0]=0
        for(i in raw.indices) {
            broken[i+1]=min(broken[i+1],broken[i]+if(raw[i] in "bpmfdtnlgkhjqxrzcsyw") 0 else 1)
            for(s in Lexicon.pinyinSyllables()) if(raw.startsWith(s,i)) broken[i+s.length]=min(broken[i+s.length],broken[i])
        }
        if(broken.last()==0) return emptyList()
        val old=quality(raw)
        if(old<2.3f) return emptyList()
        return (0 until raw.lastIndex).filter {raw[it]!=raw[it+1]}.map {i ->
            raw.substring(0,i)+raw[i+1]+raw[i]+raw.substring(i+2)
        }.distinct().map {it to quality(it)}.filter {it.second+.9f<old}
            .sortedBy {it.second}.take(2).map {it.first}
    }
    private fun plain(s:String)=s.lowercase().replace("ü","v").filter {it in 'a'..'z'}
    /** Minimal Damerau distance through full/initial syllable paths. This is
     * used only to reject a repair that contradicts the native sentence model. */
    fun wordCost(raw:String,reading:String):Int {
        val input=plain(raw)
        val syllables=reading.split(' ', '\'').map(::plain).filter {it.isNotEmpty()}
        if(input.isEmpty() || syllables.isEmpty()) return 1000
        val keyboard=buildMap<Char,Pair<Float,Int>> {
            listOf("qwertyuiop","asdfghjkl","zxcvbnm").forEachIndexed {row,keys ->
                keys.forEachIndexed {x,c ->put(c,(x+when(row) {1->.5f;2->1.5f;else->0f}) to row)}
            }
        }
        fun substitution(a:Char,b:Char):Int {
            if(a==b) return 0
            val p=keyboard[a];val q=keyboard[b]
            return if(p!=null && q!=null && abs(p.first-q.first)<=1.25f && abs(p.second-q.second)<=1) 8 else 14
        }
        fun edits(a:String,b:String):Int {
            val w=b.length+1;val d=IntArray((a.length+1)*w)
            for(i in 0..a.length) d[i*w]=i*12
            for(j in 0..b.length) d[j]=j*12
            for(i in 1..a.length) for(j in 1..b.length) {
                val at=i*w+j
                d[at]=min(d[at-w-1]+substitution(a[i-1],b[j-1]),min(d[at-w]+12,d[at-1]+12))
                if(i>1 && j>1 && a[i-1]==b[j-2] && a[i-2]==b[j-1]) d[at]=min(d[at],d[at-2*w-2]+7)
            }
            return d.last()
        }
        var prev=IntArray(input.length+1) {1000};prev[0]=0
        for(syl in syllables) {
            val next=IntArray(input.length+1) {1000}
            for(at in input.indices) if(prev[at]<1000) {
                if(input[at]==syl.first()) next[at+1]=min(next[at+1],prev[at])
                if(syl.take(2) in listOf("zh","ch","sh") && input.startsWith(syl.take(2),at)) next[at+2]=min(next[at+2],prev[at])
                for(length in 1..min(syl.length+2,input.length-at)) {
                    val cost=edits(input.substring(at,at+length),syl)
                    if(cost<=24) next[at+length]=min(next[at+length],prev[at]+cost)
                }
            }
            prev=next
        }
        return prev.last()
    }
    private data class Aligned(val typed:String,val expected:String)

    private fun align(raw:String,reading:String):List<Aligned>? {
        val input=plain(raw)
        val syllables=reading.split(' ', '\'').map(::plain).filter {it.isNotEmpty()}
        if(input.isEmpty() || input.length>128 || syllables.isEmpty()) return null
        fun distance(a:String,b:String):Int {
            val width=b.length+1
            val dp=IntArray((a.length+1)*width)
            for(i in 0..a.length) dp[i*width]=i
            for(j in 0..b.length) dp[j]=j
            for(i in 1..a.length) for(j in 1..b.length) {
                val at=i*width+j
                dp[at]=min(dp[at-width-1]+if(a[i-1]==b[j-1])0 else 1,min(dp[at-width]+1,dp[at-1]+1))
                if(i>1 && j>1 && a[i-1]==b[j-2] && a[i-2]==b[j-1]) dp[at]=min(dp[at],dp[at-2*width-2]+1)
            }
            return dp.last()
        }
        val costs=Array(syllables.size+1) {IntArray(input.length+1) {9999}}
        val previous=Array(syllables.size+1) {IntArray(input.length+1) {-1}}
        costs[0][0]=0
        for(t in syllables.indices) for(at in input.indices) {
            if(costs[t][at]>=9999) continue
            val expected=syllables[t]
            for(length in 1..min(expected.length+2,input.length-at)) {
                val part=input.substring(at,at+length)
                val abbreviated=part==expected.take(1) || (part in listOf("zh","ch","sh") && expected.startsWith(part))
                val edits=if(abbreviated)0 else distance(part,expected)
                if(edits>2) continue
                val cost=costs[t][at]+edits*100+if(part==expected)0 else if(abbreviated)1 else abs(length-expected.length)
                if(cost<costs[t+1][at+length]) {
                    costs[t+1][at+length]=cost
                    previous[t+1][at+length]=at
                }
            }
        }
        if(costs.last().last()>=300) return null
        val result=ArrayList<Aligned>()
        var end=input.length
        for(t in syllables.size downTo 1) {
            val at=previous[t][end]
            if(at<0)return null
            result.add(Aligned(input.substring(at,end),syllables[t-1]));end=at
        }
        return result.reversed()
    }

    fun presentation(raw:String,reading:String,fallback:String):String {
        val groups=align(raw,reading) ?: return fallback
        if(groups.all {it.typed==it.expected || it.typed==it.expected.take(1) ||
            (it.typed in listOf("zh","ch","sh") && it.expected.startsWith(it.typed))}) return fallback
        return groups.joinToString("'") {it.typed}
    }

    fun feedback(raw:String,reading:String):Feedback {
        val groups=align(raw,reading) ?: return Feedback(BooleanArray(plain(raw).length),emptyList())
        val errors=BooleanArray(plain(raw).length)
        val swaps=ArrayList<Int>()
        var offset=0
        for(group in groups) {
            val part=literalFeedback(group.typed,group.expected)
            part.errors.forEachIndexed {index,error ->errors[offset+index]=error}
            swaps.addAll(part.swaps.map {it+offset})
            offset+=group.typed.length
        }
        return Feedback(errors,swaps)
    }
    private fun literalFeedback(raw:String,reading:String):Feedback {
        val input=plain(raw);val tokens=reading.split(' ','\'').map(::plain).filter {it.isNotEmpty()}
        val target=tokens.joinToString("")
        val errors=BooleanArray(input.length)
        if(input.isEmpty() || target.isEmpty() || input.length>128) return Feedback(errors,emptyList())
        // Full and mixed-initial spellings are not mistakes.
        var reachable=setOf(0)
        for(t in tokens) {
            reachable=reachable.flatMap {at ->
                buildList {if(input.startsWith(t,at)) add(at+t.length);if(input.getOrNull(at)==t.first()) add(at+1)}
            }.toSet()
        }
        if(input.length in reachable || abs(input.length-target.length)>2) return Feedback(errors,emptyList())
        val w=target.length+1;val dp=IntArray((input.length+1)*w);val steps=ByteArray(dp.size)
        for(i in 1..input.length) {dp[i*w]=i;steps[i*w]=1}
        for(j in 1..target.length) {dp[j]=j;steps[j]=2}
        for(i in 1..input.length) for(j in 1..target.length) {
            val at=i*w+j;var best=dp[at-w-1]+if(input[i-1]==target[j-1]) 0 else 1;var step=0
            if(dp[at-w]+1<best) {best=dp[at-w]+1;step=1}
            if(dp[at-1]+1<best) {best=dp[at-1]+1;step=2}
            if(i>1 && j>1 && input[i-1]==target[j-2] && input[i-2]==target[j-1] && dp[at-2*w-2]+1<=best) {best=dp[at-2*w-2]+1;step=3}
            dp[at]=best;steps[at]=step.toByte()
        }
        if(dp.last()>2) return Feedback(errors,emptyList())
        val swaps=ArrayList<Int>();var i=input.length;var j=target.length
        while(i>0 || j>0) when(steps[i*w+j].toInt()) {
            1 -> {errors[--i]=true}
            2 -> {if(i>0) errors[i-1]=true;j--}
            3 -> {swaps.add(i-2);i-=2;j-=2}
            else -> {if(input[i-1]!=target[j-1]) errors[i-1]=true;i--;j--}
        }
        return Feedback(errors,swaps)
    }
}
