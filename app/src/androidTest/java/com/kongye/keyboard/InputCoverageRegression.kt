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
            for(size in listOf(5,8,32,128)) {
                val words=PinyinEngine.candidates(List(size) {"h"})
                check(words.any {it.w=="哈".repeat(size) && it.n==size}) {"Repeated initials stopped at $size: ${words.take(5).map {it.w}}"}
            }
            val mixed="wozhebkan"
            val phrases=PinyinEngine.candidates(mixed.map(Char::toString))
            check(phrases.take(8).any {it.w=="我这边看" && it.n==mixed.length}) {
                "Mixed full spelling and initials lost full phrase: ${phrases.take(12).map {it.w to it.n}}"
            }
            report("ABBREVIATION PASSED: same initial from 5 through 128 keys, and full match for wozhebkan")
            for(enabled in listOf(0,15)) {
                SpellingAssist.correctionFlags=enabled
                for((raw,expected) in listOf("zaim" to "在吗","nh" to "你好","nihao" to "你好")) {
                    val keys=raw.map(Char::toString)
                    var primary:List<Cand> = emptyList()
                    val final=PinyinEngine.candidatesLive(keys,true,{true}) {primary=it}
                    val expanded=FcitxEngine.candidates(keys) {true}
                    for(page in listOf(primary,final,expanded)) {
                        check(page.firstOrNull()?.let {it.w==expected && it.n==raw.length && !it.correction}==true) {
                            "$raw with correction flags $enabled: ${page.take(8).map {it.w to it.n}}"
                        }
                    }
                }
            }
            SpellingAssist.correctionFlags=0
            for ((raw, expected) in listOf(
                "nizaishuosm" to "你在说什么",
                "shismyanse" to "是什么颜色",
                "nizaishuosmyanse" to "你在说什么颜色"
            )) {
                val keys=raw.map(Char::toString)
                var primary:List<Cand> = emptyList()
                val started=android.os.SystemClock.elapsedRealtime()
                var firstMs=-1L
                val final=PinyinEngine.candidatesLive(keys,true,{true}) {
                    primary=it
                    firstMs=android.os.SystemClock.elapsedRealtime()-started
                }
                check(listOf(primary,final).all {it.first().w==expected && it.first().n==raw.length}) {
                    "$raw: primary ${primary.take(5).map {it.w}}, final ${final.take(5).map {it.w}}"
                }
                report("$raw: primary ${firstMs} ms, final ${android.os.SystemClock.elapsedRealtime()-started} ms")
            }
            SpellingAssist.correctionFlags=15
            Lexicon.fuzzyRules=setOf(Prefs.FUZZY_V_U)
            val noisy="wozhenbduzdnijiaohusom"
            val extended=PinyinEngine.candidates(noisy.map(Char::toString))
            check(extended.first().w.startsWith("我真不知道你") &&
                extended.first().n in noisy.indexOf("jiao") until noisy.length) {
                "Noisy sentence lost its matched prefix: ${extended.take(6).map {it.w to it.n}}"
            }
            report("Common mixed phrases rank first in both live and final results; noisy long input keeps its matched prefix")
            report("INPUT COVERAGE PASSED: polyphones, rare and supplementary Han, full/grouped pinyin, independent v/u key substitution, native selection boundaries")
        } finally {Lexicon.fuzzyRules=oldRules;SpellingAssist.correctionFlags=oldFlags;FcitxEngine.clear()}
    }

}
