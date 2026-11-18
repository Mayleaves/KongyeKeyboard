package com.kongye.keyboard

import android.content.Context
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.TranslatorOptions
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.common.model.DownloadConditions

/** Candidate translations use their own clients, so they cannot cancel the translation tool. */
object CandidateGloss {
    private val cache=object:LinkedHashMap<String,String>(128,.75f,true) {
        override fun removeEldestEntry(eldest:MutableMap.MutableEntry<String,String>?)=size>128
    }
    private val pending=HashSet<String>()
    private val clients=HashMap<Boolean,com.google.mlkit.nl.translate.Translator>()
    private val ready=HashSet<Boolean>()
    var status="首次使用需要下载中英翻译模型"
        private set
    private fun client(zh:Boolean)=clients.getOrPut(zh) {
        Translation.getClient(TranslatorOptions.Builder().setSourceLanguage(if(zh) TranslateLanguage.CHINESE else TranslateLanguage.ENGLISH)
            .setTargetLanguage(if(zh) TranslateLanguage.ENGLISH else TranslateLanguage.CHINESE).build())
    }
    fun prepare(ctx:Context) {
        if(Prefs.of(ctx).getInt(V3Prefs.GLOSS,2)==2)return
        for(zh in listOf(true,false)) if(zh !in ready && pending.add("model:$zh")) {
            status="正在准备中英翻译模型…"
            client(zh).downloadModelIfNeeded(DownloadConditions.Builder().build()).addOnSuccessListener {ready.add(zh);status="中英翻译模型已就绪"}
                .addOnFailureListener {status="翻译模型下载失败，可重新选择后重试"}.addOnCompleteListener {pending.remove("model:$zh")}
        }
    }
    fun get(word:String,zh:Boolean)=cache["$zh:$word"]
    fun request(words:List<String>,zh:Boolean,done:()->Unit) {
        if(zh !in ready)return
        val available=(8-pending.size).coerceIn(0,4)
        val missing=words.distinct().filter {"$zh:$it" !in cache && "$zh:$it" !in pending}.take(available)
        for(word in missing) {
            val key="$zh:$word"
            if(cache.containsKey(key)||!pending.add(key))continue
            client(zh).translate(word).addOnSuccessListener {cache[key]=it;done()}.addOnCompleteListener {pending.remove(key)}
        }
    }
}
