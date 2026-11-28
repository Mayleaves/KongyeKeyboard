package com.kongye.keyboard

import android.app.Instrumentation
import android.os.Bundle
import android.os.SystemClock
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/** Independent development probes; frozen candidate corpora are not modified. */
object FuzzySwitchRegression {
    private data class Rule(val id:String,val left:String,val right:String)
    private data class CandidateSignature(val word:String,val consumed:Int,val reading:String?)
    fun run(i:Instrumentation,runToken:String) {
        check(android.os.Build.MODEL.startsWith("sdk_"))
        val ctx=i.targetContext;val prefs=Prefs.of(ctx);val saved=prefs.all.toMap()
        fun hash(file:File)=if(file.exists())MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString(""){"%02x".format(it.toInt() and 255)}else "missing"
        val personal=File(ctx.filesDir,"user_lexicon.txt");val personalBefore=hash(personal)
        val oldFlags=SpellingAssist.correctionFlags;val oldFuzzy=Lexicon.fuzzyRules;val oldLearning=Lexicon.learningEnabled
        val pairs=listOf(
            Rule("z-zh","zao","zhao"),Rule("c-ch","cai","chai"),Rule("s-sh","sao","shao"),
            Rule("n-l","nan","lan"),Rule("f-h","fan","han"),Rule("r-l","rao","lao"),
            Rule("an-ang","ban","bang"),Rule("en-eng","ben","beng"),Rule("in-ing","bin","bing"),
            Rule("ian-iang","jian","jiang"),Rule("uan-uang","guan","guang"),Rule(Prefs.FUZZY_V_U,"lu","lv"))
        check(pairs.size==12)
        val rows=JSONArray();val failures=mutableListOf<String>();val reviews=mutableListOf<String>()
        val output=File(ctx.getExternalFilesDir(null),"fuzzy-switches/results.json").apply {parentFile?.mkdirs()}
        val document=JSONObject().put("runToken",runToken).put("appVersion",ctx.packageManager.getPackageInfo(ctx.packageName,0).versionName)
            .put("device",android.os.Build.MODEL).put("api",android.os.Build.VERSION.SDK_INT)
            .put("purpose","Independent bidirectional fuzzy-switch probes; rankings are not prescribed")
            .put("restorationPolicy","Restore top 10 and fuzzy-branch coverage; record tail ordering/coverage differences for review")
            .put("pairs",JSONArray(pairs.map {listOf(it.id,it.left,it.right)})).put("cases",rows).put("complete",false)
        fun save() {output.writeText(document.put("failures",JSONArray(failures)).put("reviews",JSONArray(reviews)).toString(2))}
        fun report(s:String)=i.sendStatus(0,Bundle().apply {putString("stream",s+"\n")})
        fun signature(words:List<Cand>)=words.map {CandidateSignature(it.w,it.n,it.py)}
        fun reading(c:Cand)=c.py.orEmpty().replace("'"," ").trim()
        try {
            Lexicon.load(ctx);PinyinEngine.initialize(ctx)
            check(PinyinEngine.ready && FcitxEngine.ready)
            prefs.edit().putString(Prefs.LAYOUT,"26").putBoolean(Prefs.LEARN,false)
                .putBoolean(V3Prefs.CORRECTION_ENABLED,false).commit()
            Lexicon.learningEnabled=false;SpellingAssist.configure(ctx)
            check(SpellingAssist.correctionFlags==0)
            for((rule,a,b) in pairs) for((raw,target) in listOf(a to b,b to a)) for(abbr in listOf(false,true)) {
                var baseline:List<CandidateSignature>?=null
                var baselineTarget=false
                for((stage,enabled) in listOf(false,true,false).withIndex()) {
                    val row=JSONObject().put("rule",rule).put("input",raw).put("targetReading",target)
                        .put("abbreviation",abbr).put("stage",stage).put("enabled",enabled).put("failures",JSONArray())
                    rows.put(row)
                    fun fail(message:String) {val text="$rule $raw->$target abbr=$abbr stage=$stage: $message";failures.add(text);row.getJSONArray("failures").put(message)}
                    try {
                        prefs.edit().putString(Prefs.FUZZY,rule).putBoolean(V3Prefs.FUZZY_ENABLED,enabled).commit()
                        Lexicon.fuzzyRules=Prefs.fuzzy(ctx)
                        if(Prefs.fuzzySelection(ctx)!=setOf(rule) || Lexicon.fuzzyRules!=(if(enabled)setOf(rule)else emptySet()))fail("Preference switch/selection mismatch")
                        val variants=Lexicon.syllableVariants(raw,false)
                        row.put("variants",JSONArray(variants))
                        if((target in variants)!=enabled)fail("Syllable variant gating mismatch")
                        val start=SystemClock.elapsedRealtime();var firstMs:Long?=null
                        val words=PinyinEngine.candidatesLive(raw.map(Char::toString),abbr,{SystemClock.elapsedRealtime()-start<15000}) {
                            if(firstMs==null)firstMs=SystemClock.elapsedRealtime()-start
                        }
                        row.put("firstBatchMs",firstMs ?: JSONObject.NULL).put("totalMs",SystemClock.elapsedRealtime()-start)
                            .put("candidates",JSONArray().apply {words.forEach {c->put(JSONObject().put("word",c.w).put("reading",c.py ?: "").put("consumed",c.n).put("fuzzy",c.fuzzy).put("abbreviation",c.abbr))}})
                        val targetPresent=words.any {it.n==raw.length && reading(it)==target}
                        row.put("targetPresent",targetPresent)
                        if(enabled && !targetPresent)fail("Enabled fuzzy reading missing from complete candidates")
                        if(stage==0) {
                            baseline=signature(words);baselineTarget=targetPresent
                            if(targetPresent)reviews.add("$rule $raw->$target abbr=$abbr: target already present with fuzzy off; assess prefix completion/abbreviation separately")
                        }
                        if(stage==2) {
                            val actual=signature(words)
                            if(baseline?.take(10)!=actual.take(10) || baselineTarget!=targetPresent)fail("Turning fuzzy off did not restore leading candidates or fuzzy-branch coverage")
                            if(baseline!=actual) {
                                val difference="Candidate tail changed after off/on/off; baseline=${baseline?.size}, actual=${actual.size}; leading 10 and fuzzy branch checked separately"
                                row.put("restorationReview",difference);reviews.add("$rule $raw->$target abbr=$abbr: $difference")
                            }
                        }
                        if(words.none {it.n==raw.length && reading(it)==raw})fail("Original exact reading disappeared")
                        report("$rule $raw->$target abbr=$abbr stage=$stage enabled=$enabled target=$targetPresent failures=${row.getJSONArray("failures").length()}")
                    } catch(e:Exception) {fail(e.javaClass.simpleName+": "+e.message)}
                    save()
                }
            }
        } finally {
            val edit=prefs.edit().clear()
            saved.forEach {(k,v)->when(v){is String->edit.putString(k,v);is Boolean->edit.putBoolean(k,v);is Int->edit.putInt(k,v);is Long->edit.putLong(k,v);is Float->edit.putFloat(k,v);is Set<*> -> @Suppress("UNCHECKED_CAST")edit.putStringSet(k,v as Set<String>)}}
            edit.commit();SpellingAssist.correctionFlags=oldFlags;Lexicon.fuzzyRules=oldFuzzy;Lexicon.learningEnabled=oldLearning;FcitxEngine.clear()
            val settingsPreserved=prefs.all==saved;val personalPreserved=hash(personal)==personalBefore
            if(!settingsPreserved)failures.add("User settings not restored")
            if(!personalPreserved)failures.add("Personal learning data changed")
            document.put("settingsPreserved",settingsPreserved).put("personalDataPreserved",personalPreserved)
            save()
        }
        document.put("complete",true);save()
        report("FUZZY SWITCHES: ${pairs.size} rules, ${rows.length()} bidirectional off/on/off queries; ${failures.size} failures; ${reviews.size} completion reviews")
        check(failures.isEmpty()) {failures.joinToString("; ")}
    }
}
