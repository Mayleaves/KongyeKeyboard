package com.kongye.keyboard

import android.app.Instrumentation
import android.os.Bundle
import android.os.SystemClock

/** Exercise the packaged Android library, grammar and frontend, not mocked words. */
object RimeRegression {
    private fun report(i: Instrumentation, message: String) {
        i.sendStatus(0, Bundle().apply { putString("stream", message + "\n") })
    }
    fun run(i: Instrumentation) {
        val ctx = i.targetContext
        Lexicon.load(ctx)
        val start = SystemClock.elapsedRealtime()
        PinyinEngine.initialize(ctx)
        check(PinyinEngine.ready) { "Rime unavailable: ${PinyinEngine.failure}" }
        report(i, "Native Rime ${PinyinEngine.version}; initialize ${SystemClock.elapsedRealtime()-start} ms")
        val cases = arrayOf(
            "xianzaidejianpanjvnanyong" to "现在的键盘巨难用",
            "wozhenfukni" to "我真服你", "wobzdzmb" to "我不知道怎么办",
            "nain" to "年", "nihao" to "你好", "xiexie" to "谢谢",
            "zaijian" to "再见", "zhenbang" to "真棒", "jianpan" to "键盘",
            "jintiantianqibucuo" to "今天天气不错",
            "woxiangchihuoguo" to "我想吃火锅",
            "womenmingtianqubeijing" to "我们明天去北京",
            "zhongguorenmin" to "中国人民", "shurufa" to "输入法",
            "wozhidaole" to "我知道了", "meiyouwenti" to "没有问题",
            "wozhenfuni" to "我真服你", "wozhenfuleni" to "我真服了你",
            "wobuzhidaozenmeban" to "我不知道怎么办",
            "qingwenxianzaijidianle" to "请问现在几点了"
        )
        var worst = 0L
        for ((raw, wanted) in cases) {
            val at = SystemClock.elapsedRealtime()
            val list = PinyinEngine.candidates(raw.map { it.toString() }, true)
            val elapsed = SystemClock.elapsedRealtime()-at; worst = maxOf(worst, elapsed)
            report(i, "$raw => ${list.take(5).joinToString { "${it.w}(${it.n})" }} [$elapsed ms]")
            check(list.any { it.w == wanted && it.n == raw.length }) { "Missing full candidate $wanted for $raw" }
            check(list.first().n == raw.length) { "Sentence was truncated for $raw" }
        }
        val m = PinyinEngine.candidates(listOf("m"), true)
        for ((raw,wanted) in listOf("nhaoa" to "你好啊", "budn" to "不懂呢", "nh" to "你好", "wsm" to "为什么", "wzdl" to "我知道了")) {
            val result=PinyinEngine.candidates(raw.map {it.toString()},true)
            report(i,"abbreviation $raw => ${result.take(12).map {it.w}}")
            if (raw == "nh") {
                // nh has many ordinary readings; do not override upstream's
                // dictionary frequency just to force a test's preferred word.
                check(result.take(12).any { it.w == wanted }) {"Abbreviation missing: $raw"}
            } else check(result.first().w==wanted) {"Abbreviation was replaced: $raw => ${result.take(5).map {it.w}}"}
        }
        check(m.first().w in listOf("吗", "没", "么", "们")) { "Rare m ranking: ${m.first().w}" }
        for (raw in arrayOf("zhrmbang", "zhrnbang", "zhembang", "zhenbng", "zhenbgang", "zhenbnag")) {
            val alternatives=PinyinEngine.candidates(raw.map {it.toString()},true)
            report(i,"typo $raw => ${alternatives.take(12).joinToString {it.w}}")
            check(alternatives.take(12).any {it.w=="真棒" && it.n==raw.length}) {"Adjacent/missing/extra/transposed input: $raw"}
        }
        check(PinyinEngine.candidates("nain".map {it.toString()},true).first().mask?.any {it}==true) {"Correction hint missing"}
        val initials = PinyinEngine.candidates(listOf("n", "h", "y"), true)
        for(case in arrayOf(arrayOf("you","👉","→"),arrayOf("kaixin","😊","o(*￣▽￣*)ブ"))) {
            val raw=case[0];val first=case[1];val second=case[2]
            val output=PinyinEngine.candidates(raw.map {it.toString()},true)
            check(output[4].w==first && output[5].w==second) {"Expression slots for $raw: ${output.take(9).map {it.w}}"}
            check(output[4].n==raw.length && output[5].n==raw.length && output[4].e==null && output[4].nativeChoice==null) {"Expression consumption/learning boundary"}
            report(i,"expressions $raw => ${output.take(8).map {it.w}}")
        }
        check(initials.first().inputDisplay?.contains("'") == true) { "Initial separators missing" }
        val phrase = PinyinEngine.candidates("nihaoshijie".map { it.toString() }, true)
        check(phrase.any { it.w == "你好" && it.n == 5 }) { "Native prefix consumption lost" }
        check(phrase.first().inputDisplay?.replace("'", "") == "nihaoshijie") { "Preedit changed raw input" }
        val nineGroups = mapOf('2' to "abc", '3' to "def", '4' to "ghi", '5' to "jkl", '6' to "mno", '7' to "pqrs", '8' to "tuv", '9' to "wxyz")
        val nine = PinyinEngine.candidates("5426726".map { nineGroups[it]!! }, true)
        report(i, "nine-key jianpan => ${nine.take(8).joinToString { it.w }}")
        check(nine.any { it.w == "键盘" && it.n == 7 }) { "Nine-key decoder failed" }
        val groups = listOf("qw", "er", "ty", "ui", "op", "as", "df", "gh", "jk", "l", "zx", "cv", "bn", "m")
        val fourteen = PinyinEngine.candidates("jianpan".map { ch -> groups.first { ch in it } }, true)
        report(i, "14-key jianpan => ${fourteen.take(8).joinToString { it.w }}")
        check(fourteen.any { it.w == "键盘" && it.n == 7 }) { "14-key decoder failed" }
        check(Lexicon.pinyinOf("呒") == "fu") { "Handwriting reading was not migrated" }
        check(Lexicon.assoc("大").isNotEmpty()) { "Associations were lost when releasing the old decoder" }
        report(i, "Sentence/abbreviation/typo/partial-candidate/layout checks passed; worst query $worst ms")
    }
}
