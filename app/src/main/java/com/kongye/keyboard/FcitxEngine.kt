package com.kongye.keyboard

import android.content.Context
import java.io.File
import java.util.concurrent.Executors
import java.util.zip.ZipInputStream
import org.fcitx.fcitx5.android.core.Fcitx
import org.fcitx.fcitx5.android.core.RawConfig

/** The official Fcitx5 Android + libime decoder; no frontend word re-ranking. */
object FcitxEngine {
    @Volatile var ready=false; private set
    @Volatile var failure:String?=null; private set
    private val owner=Executors.newSingleThreadExecutor { Thread(it,"Kongye Fcitx native").apply {isDaemon=true} }
    private const val SENSITIVE=1L shl 36
    private var input=""
    private var fuzzy=emptySet<String>()
    private fun <T> native(action:()->T):T = try {owner.submit<T> {action()}.get()}
        catch(e:java.util.concurrent.ExecutionException) {throw (e.cause ?: e)}
    fun initialize(ctx:Context) {
        if(ready) return
        try { native {
            if(ready) return@native
            val root=File(ctx.filesDir,"fcitx-0.1.3-model-r1")
            if(!File(root,".complete").exists()) {
                root.mkdirs()
                ZipInputStream(ctx.assets.open("fcitx-data.zip").buffered()).use { z ->
                    while(true) {
                        val e=z.nextEntry ?: break
                        val target=File(root,e.name)
                        check(target.canonicalPath.startsWith(root.canonicalPath+File.separator))
                        if(e.isDirectory) target.mkdirs() else {
                            target.parentFile?.mkdirs();target.outputStream().buffered().use {z.copyTo(it)}
                        }
                    }
                }
                File(root,".complete").writeText("Fcitx5 Android 0.1.3 / official updated libime models")
            }
            val user=File(ctx.filesDir,"fcitx-user").apply {mkdirs()}
            val cache=File(ctx.cacheDir,"fcitx").apply {mkdirs()}
            System.loadLibrary("native-lib")
            Fcitx.startupFcitx("zh_CN",root.absolutePath,ctx.applicationInfo.nativeLibraryDir,user.absolutePath,cache.absolutePath,emptyArray())
            Fcitx.activateInputContext(1,"com.kongye.keyboard")
            Fcitx.focusInputContext(true)
            Fcitx.setEnabledInputMethods(arrayOf("pinyin"))
            Fcitx.setInputMethod("pinyin")
            configure(emptySet())
            Fcitx.setCapabilityFlags(SENSITIVE)
            ready=true;failure=null
        } } catch(e:Throwable) {
            failure=e.cause?.message ?: e.message
            android.util.Log.e("KongyeFcitx","Native engine initialization failed",e)
        }
    }
    private fun config(name:String,value:String)=RawConfig(name,"",value,null)
    private fun configure(rules:Set<String>) {
        val ids=mapOf("z-zh" to "Z_ZH","c-ch" to "C_CH","s-sh" to "S_SH","n-l" to "L_N","f-h" to "F_H","r-l" to "L_R",
            "an-ang" to "AN_ANG","en-eng" to "EN_ENG","in-ing" to "IN_ING","ian-iang" to "IAN_IANG","uan-uang" to "UAN_UANG")
        val sounds=ids.map {(id,key)->config(key,if(id in rules) "True" else "False")}+config("Correction","Qwerty")
        val settings=arrayOf(config("SpellEnabled","False"),config("SymbolsEnabled","False"),config("CloudPinyinEnabled","False"),
            config("Prediction","False"),config("KeepCurrentContext","False"),config("StrokeCandidateEnabled","False"),
            RawConfig("Fuzzy","","",sounds.toTypedArray()))
        Fcitx.setFcitxAddonConfig("pinyin",RawConfig("","","",settings));fuzzy=rules.toSet()
        input="";Fcitx.resetInputContext();Fcitx.flush()
    }
    private fun prepare(raw:String,current:()->Boolean = {true}) {
        Fcitx.setCapabilityFlags(SENSITIVE)
        fun checkCurrent() {if(!current()) throw java.util.concurrent.CancellationException()}
        if(input.startsWith(raw)) {
            while(input.length>raw.length) {
                checkCurrent();Fcitx.sendKeySymToFcitx(0xff08,0,0,false,0);input=input.dropLast(1)
            }
        } else {
            if(!raw.startsWith(input)) {Fcitx.resetInputContext();input=""}
            while(input.length<raw.length) {
                checkCurrent()
                val ch=raw[input.length];Fcitx.sendKeyToFcitxChar(ch,0,0,false,0);input+=ch
            }
        }
        Fcitx.flush()
    }
    fun candidates(keys:List<String>,current:()->Boolean):List<Cand> = native {
        check(ready)
        if(!current()) throw java.util.concurrent.CancellationException()
        if(fuzzy!=Lexicon.fuzzyRules) configure(Lexicon.fuzzyRules)
        val raw=keys.joinToString("")
        prepare(raw,current)
        if(!current()) throw java.util.concurrent.CancellationException()
        val tokens=Fcitx.preedit.split(' ', '\'').filter {it.isNotEmpty()}
        val count=raw.count {it!='\''}
        val words=Fcitx.getFcitxCandidates(0,40).orEmpty().mapNotNull {c ->
            if(c.text.isEmpty() || c.text.all {it.code<128}) null else {
                val chars=c.text.codePointCount(0,c.text.length)
                val estimated=tokens.take(chars).sumOf {token->token.count {it in 'a'..'z'}}.coerceIn(1,count.coerceAtLeast(1))
                Cand(c.text,estimated,null,0f,py=Lexicon.pinyinOf(c.text),inputDisplay=Lexicon.streamingDisplay(keys),
                    nativeChoice=RimeChoice("fcitx/pinyin",raw))
            }
        }
        words + Cand(raw,count,null,-100f)
    }
    /** The native selected preedit is authoritative for how many keys a word eats. */
    fun consume(c:Cand,learn:Boolean):Int = native {
        val raw=c.nativeChoice!!.raw
        prepare(raw)
        val list=Fcitx.getFcitxCandidates(0,100).orEmpty()
        val index=list.indexOfFirst {it.text==c.w}
        check(index>=0) {"Native candidate is no longer available"}
        Fcitx.committed=""
        Fcitx.setCapabilityFlags(if(learn) 0 else SENSITIVE)
        try {
            check(Fcitx.selectCandidate(index));Fcitx.flush()
            val remaining=if(Fcitx.committed.isNotEmpty()) "" else Fcitx.preedit.filter {it in 'a'..'z'}
            val n=raw.count {it!='\''}-remaining.length
            check(n>0) {"Native candidate did not consume input"}
            if(learn) Fcitx.saveFcitxState()
            n
        } finally {
            Fcitx.setCapabilityFlags(SENSITIVE);Fcitx.resetInputContext();Fcitx.flush();input=""
        }
    }
    fun clear() {if(ready) native {Fcitx.resetInputContext();Fcitx.flush();input=""}}
    /** Called only by the user's explicit clear-learning action. */
    fun clearUser(ctx:Context) {
        native {
            if(ready) {Fcitx.resetInputContext();Fcitx.saveFcitxState();Fcitx.exitFcitx()}
            ready=false;input=""
            val user=File(ctx.filesDir,"fcitx-user").canonicalFile
            val words=File(user,"data/pinyin").canonicalFile
            check(words.path.startsWith(user.path+File.separator))
            check(!words.exists() || words.deleteRecursively()) {"Cannot clear native learned words"}
        }
        initialize(ctx);check(ready) {failure.orEmpty()}
    }
}
