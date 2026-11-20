package com.kongye.keyboard

import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.common.model.RemoteModel
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.TranslateRemoteModel
import com.google.mlkit.vision.digitalink.DigitalInkRecognitionModel
import com.google.mlkit.vision.digitalink.DigitalInkRecognitionModelIdentifier

/** Optional SDK-managed data; querying availability never downloads anything. */
@androidx.annotation.Keep
object LanguageResources {
    data class Pack(val id:String,val title:String,val summary:String,val language:String?=null) {
        val approximateMb get()=if(language==null)20 else 30
    }
    data class State(val installed:Boolean=false,val busy:Boolean=false,val message:String="")
    private val states=java.util.concurrent.ConcurrentHashMap<String,State>()
    private val languages=java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    private var revision=0
    private val manager get()=RemoteModelManager.getInstance()
    val backup=Pack("digital-ink","备用手写识别","可选备用模型；内置手写识别无需下载此项。")
    private fun translation(code:String)=Pack("translation:$code",(if(code in listOf("zh","ja","ko"))Translator.name(code)else java.util.Locale.forLanguageTag(code).getDisplayLanguage(java.util.Locale.SIMPLIFIED_CHINESE))+"翻译语言包",
        if(code==TranslateLanguage.CHINESE)"用于中英互译和候选翻译。" else "用于该语言与其他语言之间的离线翻译。",code)
    fun packs():List<Pack> =(listOf("zh","ja","ko")+languages.sorted()).distinct().filter {it!="en"}.map(::translation)+backup
    fun state(pack:Pack)=states[pack.id] ?: State()
    private fun model(pack:Pack):RemoteModel=if(pack.language!=null)TranslateRemoteModel.Builder(pack.language).build()
        else DigitalInkRecognitionModel.builder(checkNotNull(DigitalInkRecognitionModelIdentifier.fromLanguageTag("zh-Hani-CN"))).build()
    fun refresh(done:()->Unit={}) {
        val token=revision
        manager.getDownloadedModels(TranslateRemoteModel::class.java).addOnSuccessListener {models->
            if(token!=revision){done();return@addOnSuccessListener}
            languages.addAll(models.map {it.language}.filter {it!="en"})
            val installed=models.map {it.language}.toSet()
            packs().filter {it.language!=null}.forEach {p->
                val old=state(p);if(!old.busy)states[p.id]=old.copy(installed=p.language in installed)
            }
            done()
        }.addOnFailureListener {done()}
        manager.isModelDownloaded(model(backup)).addOnSuccessListener {ready->
            if(token!=revision){done();return@addOnSuccessListener}
            val old=state(backup);if(!old.busy)states[backup.id]=old.copy(installed=ready);done()
        }.addOnFailureListener {done()}
    }
    fun translationReady(codes:List<String>,done:(Boolean)->Unit) {
        val required=codes.distinct().filter {it!=TranslateLanguage.ENGLISH}
        languages.addAll(required)
        fun check(at:Int) {
            if(at==required.size) {done(true);return}
            manager.isModelDownloaded(TranslateRemoteModel.Builder(required[at]).build())
                .addOnSuccessListener {if(it)check(at+1)else done(false)}.addOnFailureListener {done(false)}
        }
        check(0)
    }
    fun download(pack:Pack) {
        if(state(pack).busy)return
        revision++
        pack.language?.let {languages.add(it)}
        states[pack.id]=state(pack).copy(busy=true,message="正在下载…")
        manager.download(model(pack),DownloadConditions.Builder().build())
            .addOnSuccessListener {revision++;states[pack.id]=State(true,false,"已下载，可离线使用")}
            .addOnFailureListener {revision++;states[pack.id]=State(false,false,"下载失败，请检查网络后点继续")}
    }
    fun remove(pack:Pack) {
        if(state(pack).busy)return
        revision++
        if(pack.language!=null) {Translator.cancel();CandidateGloss.reset()}
        else HandInk.release()
        states[pack.id]=state(pack).copy(busy=true,message="正在删除…")
        manager.deleteDownloadedModel(model(pack))
            .addOnSuccessListener {
                revision++
                if(pack.language!=null)CandidateGloss.reset()else HandInk.release()
                states[pack.id]=State(false,false,"已删除，需要时可重新下载")
            }
            .addOnFailureListener {revision++;states[pack.id]=State(state(pack).installed,false,"删除失败，请重试")}
    }
}
