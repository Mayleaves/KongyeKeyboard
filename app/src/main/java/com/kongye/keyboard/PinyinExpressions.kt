package com.kongye.keyboard

import android.content.Context
import org.json.JSONArray

/** Exact whole-input associations. They never change normal word ranking,
 * participate in spelling correction or train the user's Chinese dictionary.
 */
object PinyinExpressions {
    private class Expression(val text:String,val label:String)
    private var byCode:Map<String,List<Expression>> = emptyMap()
    private var byWord:Map<String,List<Expression>> = emptyMap()
    @Synchronized fun load(ctx:Context) {
        if(byWord.isNotEmpty()) return
        val codes=LinkedHashMap<String,MutableList<Expression>>()
        val words=LinkedHashMap<String,List<Expression>>()
        ctx.assets.open("pinyin-expressions.tsv").bufferedReader().useLines {lines -> lines.forEach {line ->
            val fields=line.split('\t')
            if(fields.size==3) {
                val json=JSONArray(fields[2])
                val items=(0 until json.length()).map {Expression(json.getString(it),fields[0])}
                words[fields[0]]=items
                for(code in fields[1].split(',')) codes.getOrPut(code) {ArrayList()}.addAll(items)
            }
        } }
        byWord=words;byCode=codes
    }
    fun insert(candidates:List<Cand>,keys:List<String>):List<Cand> {
        val length=keys.count {it!="'"}
        if(length<2) return candidates
        val exact=if(keys.all {it.length==1}) byCode[keys.joinToString("").replace("'","")].orEmpty() else emptyList()
        val recognized=candidates.asSequence().filter {it.n==length && !it.correction}.take(12)
            .flatMap {byWord[it.w].orEmpty().asSequence()}.toList()
        val expressions=(exact+recognized).distinctBy {it.text}.filter {item -> candidates.none {it.w==item.text}}.take(4)
        if(expressions.isEmpty()) return candidates
        val at=minOf(4,candidates.size)
        val matches=expressions.map {Cand(it.text,length,null,-50f)}
        return candidates.take(at)+matches+candidates.drop(at)
    }
}
