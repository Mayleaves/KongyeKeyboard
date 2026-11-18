package com.kongye.keyboard

import android.app.Instrumentation
import android.os.Bundle

object CorrectionSwitchRegression {
    fun run(i:Instrumentation) {
        val ctx=i.targetContext;val p=Prefs.of(ctx);val saved=p.all.toMap()
        fun report(s:String)=i.sendStatus(0,Bundle().apply {putString("stream",s+"\n")})
        val names=listOf(V3Prefs.CORRECT_NEAR,V3Prefs.CORRECT_SWAP,V3Prefs.CORRECT_EXTRA,V3Prefs.CORRECT_MISSING)
        try {
            Lexicon.load(ctx);PinyinEngine.initialize(ctx)
            check(FcitxEngine.ready&&PinyinEngine.ready)
            Lexicon.fuzzyRules=emptySet()
            data class Case(val bit:Int,val raw:String,val word:String,val py:String)
            val cases=listOf(Case(1,"nuhao","你好","ni hao"),Case(2,"jeude","觉得","jue de"),Case(4,"nihaoo","你好","ni hao"),Case(8,"niho","你好","ni hao"))
            p.edit().putBoolean(V3Prefs.CORRECTION_ENABLED,true).commit()
            for(mask in 0..15) {
                val edit=p.edit();names.forEachIndexed {index,key->edit.putBoolean(key,mask and (1 shl index)!=0)};edit.commit();SpellingAssist.configure(ctx)
                for(case in cases) {
                    FcitxEngine.clear()
                    val start=android.os.SystemClock.elapsedRealtime()
                    val words=PinyinEngine.candidatesLive(case.raw.map {it.toString()},true,{true},{})
                    val repaired=words.firstOrNull {it.w==case.word&&it.n==case.raw.length}
                    check((repaired!=null)==(mask and case.bit!=0)) {"mask=$mask ${case.raw}: "+words.joinToString {it.w+":"+it.n}}
                    check(SpellingAssist.allows(case.raw,case.py)==(mask and case.bit!=0)) {"Filter bypass: mask=$mask ${case.raw}"}
                    if(repaired!=null)check(FcitxEngine.consume(repaired,false)==case.raw.length) {"Repair did not consume all original letters"}
                    report("mask=$mask ${case.raw} correction=${repaired!=null}, ${android.os.SystemClock.elapsedRealtime()-start} ms")
                }
            }
            p.edit().putBoolean(V3Prefs.CORRECTION_ENABLED,false).commit();SpellingAssist.configure(ctx);check(SpellingAssist.correctionFlags==0)
            for(case in cases)check(PinyinEngine.candidates(case.raw.map {it.toString()}).none {it.w==case.word&&it.n==case.raw.length}) {"Master switch off leaked ${case.raw}"}
            for((raw,word) in listOf("nihao" to "你好","xiexie" to "谢谢","jintian" to "今天","shurufa" to "输入法","yinhang" to "银行","chongqing" to "重庆","kuaile" to "快乐","zg" to "中国"))check(PinyinEngine.candidates(raw.map {it.toString()}).any {it.w==word}) {"Normal spelling lost: $raw"}
            p.edit().putBoolean(V3Prefs.CORRECTION_ENABLED,true).commit();SpellingAssist.configure(ctx)
            for(case in cases.filter {it.bit in listOf(4,8)})check(PinyinEngine.candidates(case.raw.map {it.toString()},false).any {it.w==case.word&&it.n==case.raw.length}) {"Full-pinyin mode missing ${case.raw}"}
            p.edit().putString(Prefs.FUZZY,"n-l").putBoolean(V3Prefs.FUZZY_ENABLED,false).commit();check(Prefs.fuzzy(ctx).isEmpty()&&Prefs.fuzzySelection(ctx)==setOf("n-l"))
            p.edit().putBoolean(V3Prefs.FUZZY_ENABLED,true).commit();check(Prefs.fuzzy(ctx)==setOf("n-l"))
            p.edit().putBoolean(V3Prefs.CORRECTION_ENABLED,false).commit()
            SpellingAssist.configure(ctx)
            data class FuzzyCase(val raw:String,val word:String,val reading:String)
            val fuzzyCases=listOf(
                FuzzyCase("luse", "绿色", "lv se"),
                FuzzyCase("lvshang", "路上", "lu shang"),
                FuzzyCase("nuer", "女儿", "nv er"),
            )
            try {
                for (enabled in listOf(false, true, false)) {
                    p.edit().putString(Prefs.FUZZY,Prefs.FUZZY_V_U)
                        .putBoolean(V3Prefs.FUZZY_ENABLED,enabled).commit()
                    check(Prefs.fuzzySelection(ctx) == setOf(Prefs.FUZZY_V_U))
                    Lexicon.fuzzyRules=Prefs.fuzzy(ctx)
                    for ((raw, word, reading) in fuzzyCases) {
                        val keys=raw.map(Char::toString)
                        check(SpellingAssist.allows(raw,reading) == enabled)
                        if (enabled) check(SpellingAssist.edits(raw,reading).isEmpty())
                        for (abbreviation in listOf(true, false)) {
                            val words=PinyinEngine.candidates(keys,abbreviation)
                            val match=words.firstOrNull { it.w == word && it.n == keys.size }
                            check((match != null) == enabled) {
                                "v/u enabled=$enabled abbreviation=$abbreviation $raw -> $word: " +
                                    words.take(20).joinToString { "${it.w}:${it.n}" }
                            }
                            if (match != null && abbreviation) {
                                check(FcitxEngine.consume(match,false) == keys.size)
                            }
                        }
                    }
                    check(("lv" in Lexicon.syllableVariants("lu", false)) == enabled)
                    check(("lu" in Lexicon.syllableVariants("lv", false)) == enabled)
                    check("niv" !in Lexicon.syllableVariants("niu", false))
                }
                report("v/u fuzzy finals passed in both directions with correction off, including full spelling and live switch changes")
            } finally {
                FcitxEngine.clear()
            }
            report("CORRECTION SWITCHES PASSED: 16 combinations, no neighboring/swap bypass, full extra/missing consumption, master off, normal words/abbreviations, full-pinyin and fuzzy selection preservation")

        } finally {
            val edit=p.edit().clear();saved.forEach {(k,v)->when(v){is Boolean->edit.putBoolean(k,v);is Int->edit.putInt(k,v);is Long->edit.putLong(k,v);is Float->edit.putFloat(k,v);is String->edit.putString(k,v);is Set<*> -> @Suppress("UNCHECKED_CAST")edit.putStringSet(k,v as Set<String>)}};edit.commit();SpellingAssist.configure(ctx);Lexicon.fuzzyRules=Prefs.fuzzy(ctx)
        }
    }
}
