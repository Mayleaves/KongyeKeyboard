package com.kongye.keyboard

import android.app.Instrumentation
import android.os.Bundle
import android.os.SystemClock

object FcitxRegression {
    fun run(i:Instrumentation) {
        fun report(s:String)=i.sendStatus(0,Bundle().apply {putString("stream",s+"\n")})
        Lexicon.load(i.targetContext);FcitxEngine.initialize(i.targetContext)
        check(FcitxEngine.ready) {FcitxEngine.failure.orEmpty()}
        val cases=arrayOf("nijuedzggzzmy" to "你觉得这个工作怎么样","nijeudezggzzmy" to "你觉得这个工作怎么样",
            "jeude" to "觉得","juede" to "觉得","wsmyoudianka" to "为什么有点卡","nhaoa" to "你好啊",
            "nihao" to "你好","xiexie" to "谢谢","zaijian" to "再见","shurufa" to "输入法",
            "jintiantianqibucuo" to "今天天气不错","woxiangchihuoguo" to "我想吃火锅",
            "womenmingtianqubeijing" to "我们明天去北京","wobuzhidaozenmeban" to "我不知道怎么办",
            "qingwenxianzaijidianle" to "请问现在几点了","jianpan" to "键盘","kaixin" to "开心")
        for((raw,wanted) in cases) {
            FcitxEngine.clear();val at=SystemClock.elapsedRealtime()
            val out=FcitxEngine.candidates(raw.map {it.toString()}) {true}
            report("$raw => ${out.take(5).map {it.w}} [${SystemClock.elapsedRealtime()-at} ms]")
            check(out.any {it.w==wanted}) {"Native engine missing $wanted for $raw"}
            if(raw in listOf("nijuedzggzzmy","nijeudezggzzmy","jeude","wsmyoudianka")) check(out.first().w==wanted)
        }
        FcitxEngine.clear()
        val first=FcitxEngine.candidates("nihaoshijie".map {it.toString()}) {true}.first {it.w=="你好"}
        check(FcitxEngine.consume(first,false)==5) {"Native prefix consumed trailing spelling"}
        val sentence=FcitxEngine.candidates("nijuedzggzzmy".map {it.toString()}) {true}.first()
        check(FcitxEngine.consume(sentence,false)==13) {"Native sentence consumption mismatch"}
        for(raw in arrayOf("nihaoshijie","nijuedzggzzmy","nijeudezggzzmy","xianzai","wsmyoudianka")) {
            var before=""
            for(size in 1..raw.length) {
                val shown=Lexicon.streamingDisplay(raw.take(size).map {it.toString()})
                check(shown.startsWith(before)) {"Retroactive separator: $before -> $shown"}
                check(shown.replace("'","")==raw.take(size));before=shown
            }
            report("stable pinyin $raw => $before")
        }
        FcitxEngine.clear()
        report("Official engine, updated model, native partial/full selection and stable spelling checks passed")
    }
}
