package com.kongye.keyboard

import android.app.Instrumentation
import android.os.Bundle

object InputCoverageRegression {
    fun run(i:Instrumentation) {
        check(android.os.Build.MODEL.startsWith("sdk_"))
        fun report(s:String)=i.sendStatus(0,Bundle().apply {putString("stream",s+"\n")})
        val oldRules=Lexicon.fuzzyRules;val oldFlags=SpellingAssist.correctionFlags
        Lexicon.load(i.targetContext);PinyinEngine.initialize(i.targetContext)
        check(PinyinEngine.ready && FcitxEngine.ready) {PinyinEngine.failure ?: FcitxEngine.failure.orEmpty()}
        try {
            SpellingAssist.correctionFlags=0
            Lexicon.fuzzyRules=setOf("c-ch","z-zh","s-sh","n-l","an-ang","en-eng","in-ing")
            val chong=PinyinEngine.candidates("chong".map(Char::toString))
            check(chong.any {it.w=="重" && it.py=="chong"}) {"Polyphonic 重 removed: ${chong.take(20).map {it.w to it.py}}"}
            check(chong.first().py=="chong") {"Fuzzy candidates outranked the exact spelling"}
            report("chong: ${chong.take(10).map {it.w to it.py}}; 重 remains selectable")
            Lexicon.fuzzyRules=emptySet()
            val groups=listOf("qw","er","ty","ui","op","as","df","gh","jk","l","zx","cv","bn","m")
            for((raw,char) in listOf("yi" to "燚","yao" to "垚","ben" to "犇","da" to "龘","chi" to "𠮟")) {
                for(abbr in listOf(true,false)) {
                    val words=PinyinEngine.candidates(raw.map(Char::toString),abbr)
                    val at=words.indexOfFirst {it.w==char && it.n==raw.length}
                    check(at>=0) {"Missing full dictionary coverage: $raw -> $char, abbreviation=$abbr"}
                    report("$raw -> $char index=$at / ${words.size}, abbreviation=$abbr")
                }
                val keys=raw.map {c->groups.first {c in it}}
                check(PinyinEngine.candidates(keys).any {it.w==char && it.n==raw.length}) {"Rare char missing on grouped keys: $char"}
            }
            for(enabled in listOf(false,true,false)) {
                Lexicon.fuzzyRules=if(enabled)setOf(Prefs.FUZZY_V_U)else emptySet()
                for((raw,word) in listOf("shv" to "书","hv" to "湖","lv" to "路","chvqu" to "出去","luse" to "绿色")) {
                    for(abbr in listOf(true,false)) {
                        val out=PinyinEngine.candidates(raw.map(Char::toString),abbr)
                        val match=out.firstOrNull {it.w==word && it.n==raw.length}
                        check((match!=null)==enabled) {"v/u enabled=$enabled abbr=$abbr $raw -> $word: ${out.take(12).map {it.w to it.py}}"}
                        if(match?.nativeChoice?.schema?.startsWith("fcitx/")==true)check(FcitxEngine.consume(match,false)==raw.length)
                    }
                }
            }
            report("INPUT COVERAGE PASSED: polyphones, rare and supplementary Han, full/grouped pinyin, independent v/u key substitution, native selection boundaries")
        } finally {Lexicon.fuzzyRules=oldRules;SpellingAssist.correctionFlags=oldFlags;FcitxEngine.clear()}
    }

}
