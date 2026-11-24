package com.kongye.keyboard

import android.app.Instrumentation
import android.os.Bundle
import android.os.SystemClock

object Pinyin400Regression {
    fun run(i: Instrumentation) {
        val context = i.targetContext
        fun report(text: String) = i.sendStatus(0, Bundle().apply { putString("stream", text + "\n") })
        Lexicon.load(context)
        val whitelist = context.assets.open("pinyin-syllables.txt").bufferedReader().use { it.readLines().toSet() }
        check(whitelist.size >= 400 && whitelist == Lexicon.pinyinSyllables().toSet())
        var entries = 0
        context.assets.open("lexicon.txt").bufferedReader().use { reader ->
            reader.readLine()
            while (true) {
                val line = reader.readLine() ?: break
                if (line.isBlank()) continue
                val encoding = line.substringBefore(' ')
                check(encoding.split('\'').all { it in whitelist }) { "Invalid dictionary encoding $encoding" }
                entries++
            }
        }
        report("Dictionary encoding checked for $entries entries")
        for (syllable in whitelist) {
            check(PinyinSegmentation.graph(syllable, whitelist).edges[0].any { it.spelling == syllable })
            check(Lexicon.streamingDisplay(syllable.map(Char::toString)) == syllable) { syllable }
        }
        for ((raw, paths) in mapOf(
            "pingan" to listOf("ping'an", "pin'gan"),
            "xian" to listOf("xian", "xi'an"),
            "xiana" to listOf("xian'a", "xia'na"),
            "qina" to listOf("qin'a", "qi'na"),
            "zhangai" to listOf("zhang'ai", "zhan'gai"),
            "shanxi" to listOf("shan'xi"),
        )) {
            val graph = PinyinSegmentation.graph(raw, whitelist)
            val actual = graph.paths(1024).map { it.joinToString("'") }
            check(actual.containsAll(paths)) { "$raw missing $paths in $actual" }
            check(graph.longestPreferred() != null)
            report("$raw => $actual")
        }
        check(PinyinSegmentation.graph("ping'an", whitelist).paths().all { path ->
            4 in path.runningFold(0) { at, syllable -> at + syllable.length }
        })
        check(PinyinSegmentation.graph("xi'an", whitelist).paths().none { it == listOf("xian") })
        for (raw in listOf("xianzai", "nihaoshijie", "wozhenbuzhidaonijiaoshenme", "hhhhhhhhhhhh",
            "ni'haoshi'jie", "jv", "nv", "shuang")) {
            for (end in 1..raw.length) {
                val shown = Lexicon.streamingDisplay(raw.take(end).map(Char::toString))
                check(shown.replace("'", "") == raw.take(end).replace("'", "")) { "$raw -> $shown" }
            }
        }
        check(SpellingAssist.presentation("xian", "xi an", "xian") == "xi'an")
        check(SpellingAssist.presentation("xi'an", "xian", "xi'an") == "xi'an")
        report("All ${whitelist.size} syllables, ambiguity paths, manual boundaries and literal preservation passed")
        FcitxEngine.initialize(context)
        check(FcitxEngine.ready) { FcitxEngine.failure.orEmpty() }
        val savedFlags = SpellingAssist.correctionFlags
        val savedFuzzy = Lexicon.fuzzyRules
        SpellingAssist.correctionFlags = 0
        Lexicon.fuzzyRules = emptySet()
        try {
            val at = SystemClock.elapsedRealtime()
            val unsupported = mutableListOf<String>()
            for (syllable in whitelist) {
                val candidates = FcitxEngine.primaryCandidates(syllable.map(Char::toString)) { true }
                if (candidates.none { it.w.any { c -> c in '一'..'鿿' } && it.n == syllable.length }) unsupported.add(syllable)
            }
            report("Native single-syllable coverage: ${whitelist.size - unsupported.size}/${whitelist.size}, unmatched=$unsupported, ${SystemClock.elapsedRealtime()-at} ms")
            check(unsupported.isEmpty()) { "Native whitelist mismatch: $unsupported" }
            for ((raw, word) in listOf("pingan" to "平安", "xian" to "先", "xi'an" to "西安",
                "zhangai" to "障碍", "shanxi" to "山西", "shanxi" to "陕西")) {
                val found = FcitxEngine.candidates(raw.map(Char::toString)) { true }
                report("$raw => ${found.take(8).map { it.w to it.py }}")
                check(found.any { it.w == word && it.n == raw.replace("'", "").length }) { "$raw missing $word" }
            }
            for (raw in listOf("xiana", "qina")) {
                val found = FcitxEngine.candidates(raw.map(Char::toString)) { true }
                check(found.any { it.n == raw.length && !it.py.isNullOrBlank() }) { "No full reading for $raw" }
                report("$raw => ${found.take(8).map { it.w to it.py }}")
            }
        } finally {
            SpellingAssist.correctionFlags = savedFlags
            Lexicon.fuzzyRules = savedFuzzy
            FcitxEngine.clear()
        }
    }
}
