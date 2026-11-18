package com.kongye.keyboard

import android.content.Context
import java.io.File
import java.util.concurrent.Executors
import java.util.zip.ZipInputStream
import org.fcitx.fcitx5.android.core.Fcitx
import org.fcitx.fcitx5.android.core.RawConfig

/** Official libime decoding with bounded spelling-path fallback, never example-word overrides. */
object FcitxEngine {
    @Volatile var ready=false; private set
    @Volatile var failure:String?=null; private set
    private val owner=Executors.newSingleThreadExecutor { Thread(it,"Kongye Fcitx native").apply {isDaemon=true} }
    private const val SENSITIVE=1L shl 36
    private var input=""
    private var fuzzy=emptySet<String>()
    private var correctionFlags=-1
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
            "an-ang" to "AN_ANG","en-eng" to "EN_ENG","in-ing" to "IN_ING","ian-iang" to "IAN_IANG","uan-uang" to "UAN_UANG", Prefs.FUZZY_V_U to "V_U")
        val sounds=ids.map {(id,key)->config(key,if(id in rules) "True" else "False")}+config("Correction",if(SpellingAssist.correctionFlags and 1!=0)"Qwerty" else "None")
        val settings=arrayOf(config("SpellEnabled","False"),config("SymbolsEnabled","False"),config("CloudPinyinEnabled","False"),
            config("Prediction","False"),config("KeepCurrentContext","False"),config("StrokeCandidateEnabled","False"),
            RawConfig("Fuzzy","","",sounds.toTypedArray()))
        Fcitx.setFcitxAddonConfig("pinyin",RawConfig("","","",settings));fuzzy=rules.toSet();correctionFlags=SpellingAssist.correctionFlags
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
    fun candidates(keys:List<String>,current:()->Boolean):List<Cand> = query(keys,current,true)
    fun primaryCandidates(keys:List<String>,current:()->Boolean):List<Cand> = query(keys,current,false)
    fun candidatesLive(keys:List<String>,current:()->Boolean,onPrimary:(List<Cand>)->Unit):List<Cand> = query(keys,current,false,onPrimary)
    private fun query(keys:List<String>,current:()->Boolean,includeShort:Boolean,onPrimary:((List<Cand>)->Unit)?=null):List<Cand> = native {
        check(ready)
        if(!current()) throw java.util.concurrent.CancellationException()
        if(fuzzy!=Lexicon.fuzzyRules || correctionFlags!=SpellingAssist.correctionFlags) configure(Lexicon.fuzzyRules)
        val raw=keys.joinToString("")
        prepare(raw,current)
        if(!current()) throw java.util.concurrent.CancellationException()
        val tokens=Fcitx.preedit.split(' ', '\'').filter {it.isNotEmpty()}
        val count=raw.count {it!='\''}
        fun collect(query:String,source:String,limit:Int=80):List<Cand> {
            val parts=Fcitx.preedit.split(' ', '\'').filter {it.isNotEmpty()}
            val length=source.count {it!='\''}
            return Fcitx.getFcitxCandidates(0,limit).orEmpty().mapNotNull {c ->
                if(c.text.isEmpty() || c.text.all {it.code<128}) null else {
                    val chars=c.text.codePointCount(0,c.text.length)
                    val queryLength=query.count {it!='\''}
                    val estimated=parts.take(chars).sumOf {token->token.count {it in 'a'..'z'}}.coerceIn(1,queryLength.coerceAtLeast(1))
                    val consumed=if(query!=source && estimated==queryLength)length else minOf(estimated,length)
                    Cand(c.text,consumed,null,0f,py=Lexicon.pinyinOf(c.text),inputDisplay=Lexicon.streamingDisplay(keys),
                        nativeChoice=RimeChoice("fcitx/pinyin",query,source),correction=query!=source)
                }
            }.filter {c->c.py.isNullOrEmpty() || SpellingAssist.allows(source.filter {it!='\''}.take(c.n),c.py.orEmpty())}
        }
        val original=collect(raw,raw,if(includeShort) 80 else 40)
        onPrimary?.invoke(original+Cand(raw,count,null,-100f))
        val result=original.toMutableList()
        // Native words appear immediately; bounded repairs refine the same page.
        try {
            val repairs=ArrayList<Cand>()
            val first=original.firstOrNull()
            val reading=first?.py.orEmpty().replace(" ","").replace("'","")
            val exact=first?.n==count && reading==raw
            val nativeRepair=first?.n==count && reading.length==raw.length && reading!=raw && SpellingAssist.wordCost(raw,first?.py.orEmpty())<=15
            val variants=if(nativeRepair)emptyList() else SpellingAssist.repairs(raw).filter {!exact || it.length<raw.length}
            for(variant in variants) {
                if(!current()) throw java.util.concurrent.CancellationException()
                prepare(variant,current)
                val alternative=collect(variant,raw,12).firstOrNull {it.n==count} ?: continue
                if((!exact || original.any {it.w==alternative.w&&it.n<count}) && repairs.none {it.w==alternative.w})repairs.add(alternative)
            }
            val additions=ArrayList<Cand>()
            for(c in repairs) {
                val old=result.indexOfFirst {it.w==c.w}
                if(old>=0 && result[old].n<count)result[old]=c
                else if(old<0)additions.add(c)
            }
            result.addAll(minOf(3,result.size),additions)
            // Ask the native dictionary for real short prefix phrases as well.
            // A full-sentence-heavy native page must not hide all 3/2/1-char words.
            if(includeShort && tokens.size>3) for(size in listOf(3,2,1)) {
                val prefix=tokens.take(size).joinToString("").filter {it in 'a'..'z'}
                if(prefix.isEmpty() || !raw.startsWith(prefix)) continue
                prepare(prefix,current)
                val short=collect(prefix,prefix,40).filter {it.w.codePointCount(0,it.w.length)==size}.take(8)
                for(c in short) if(result.none {it.w==c.w}) result.add(c)
            }
        } finally {prepare(raw,current)}
        result + Cand(raw,count,null,-100f)
    }

    /** Keep alternate dictionary spellings selectable after a gesture. Only
     * their full-word candidates may replace the primary gesture spelling. */
    fun glideCandidates(matches:List<GlideDecoder.Match>,current:()->Boolean):List<Cand> {
        if(matches.isEmpty() || !ready) return emptyList()
        val source=matches.first().spelling
        val base=primaryCandidates(source.map {it.toString()},current)
        val alternatives=matches.drop(1).take(2).mapNotNull {match ->
            val c=primaryCandidates(match.spelling.map {it.toString()},current).firstOrNull {it.nativeChoice!=null} ?: return@mapNotNull null
            Cand(c.w,source.length,null,c.score,py=c.py,
                nativeChoice=RimeChoice("fcitx/glide",match.spelling,source))
        }
        return (base.take(1)+alternatives+base.drop(1)).distinctBy {it.w}
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
            if(c.nativeChoice!!.schema=="fcitx/glide") check(n==raw.count {it!='\''}) {"Alternate glide candidate did not consume its full spelling"}
            if(learn) Fcitx.saveFcitxState()
            if(c.nativeChoice!!.source!=raw && n==raw.count {it!='\''}) c.nativeChoice!!.source.count {it!='\''} else n
        } finally {
            Fcitx.setCapabilityFlags(SENSITIVE);Fcitx.resetInputContext();Fcitx.flush();input=""
        }
    }
    fun clear() {if(ready) native {Fcitx.resetInputContext();Fcitx.flush();input=""}}
    @Synchronized fun <T> withUserFiles(ctx:Context,action:()->T):T {
        val wasReady=ready
        native {if(ready){Fcitx.resetInputContext();Fcitx.saveFcitxState();Fcitx.exitFcitx()};ready=false;input=""}
        try {return action()} finally {if(wasReady){initialize(ctx);check(ready){failure.orEmpty()}}}
    }

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
