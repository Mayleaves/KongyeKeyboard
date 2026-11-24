package com.kongye.keyboard

import android.app.Instrumentation
import android.os.Bundle
import android.os.SystemClock

object FcitxRegression {
    fun learning(i: Instrumentation) {
        Lexicon.load(i.targetContext)
        PinyinEngine.initialize(i.targetContext)
        val saved=Lexicon.backupText()
        val enabled=Lexicon.learningEnabled
        val fuzzy=Lexicon.fuzzyRules
        try {
            Lexicon.restoreBackupText(i.targetContext, "")
            Lexicon.learningEnabled=true
            Lexicon.learnPhrase("妮好",listOf("ni","hao"))
            Lexicon.learnPhrase("妮好",listOf("ni","hao"))
            val entry=Lexicon.backupText().lineSequence().first {it.startsWith("P\t妮好\t")}.split('\t')
            check(entry[2]=="ni'hao" && entry[3]=="2" && entry[4].toLong()>0)
            check(PinyinEngine.candidates("nihao".map(Char::toString)).first().w=="妮好")
            Lexicon.learningEnabled=false
            check(Lexicon.userCandidates("nihao".map(Char::toString),true).isEmpty())
            check(PinyinEngine.candidates("nihao".map(Char::toString)).first().w!="妮好")
            Lexicon.learningEnabled=true
            Lexicon.learnPhrase("第一个",listOf("di","yi","ge"))
            check(Lexicon.userCandidates(listOf("d"),true).none {it.w=="第一个"})
            check(Lexicon.userCandidates("di".map(Char::toString),true).none {it.w=="第一个"})
            check(Lexicon.userCandidates("diy".map(Char::toString),true).none {it.w=="第一个"})
            for(raw in listOf("dyg","diyige","di'yige")) {
                check(Lexicon.userCandidates(raw.map(Char::toString),true).any {it.w=="第一个"}) {"Personal phrase lost $raw"}
            }
            check(PinyinEngine.candidates(listOf("d")).first().w!="第一个")
            Lexicon.learnPhrase("站",listOf("zhan"));Lexicon.fuzzyRules=setOf("z-zh")
            check(Lexicon.userCandidates("zan".map(Char::toString),true).any {it.w=="站" && it.py=="zhan"})
            val roundtrip=Lexicon.backupText()
            Lexicon.restoreBackupText(i.targetContext,roundtrip)
            check(Lexicon.backupText()==roundtrip)
            Lexicon.restoreBackupText(i.targetContext,"P\t妮好\tni'hao\t2\n")
            check(Lexicon.userCandidates("nihao".map(Char::toString),true).first().w=="妮好")
            for(n in 0..4096)Lexicon.learnPhrase(String(Character.toChars(0x4e00+n)),listOf("yi"))
            check(Lexicon.userCount()==4096)
            check(Lexicon.userCandidates("nihao".map(Char::toString),true).none {it.w=="妮好"})
        } finally {
            Lexicon.restoreBackupText(i.targetContext,saved)
            Lexicon.learningEnabled=enabled;Lexicon.fuzzyRules=fuzzy
        }
        i.sendStatus(0,Bundle().apply {putString("stream","LEARNING PASSED counts, canonical readings, off switch, fuzzy reuse, 4096-entry LRU, old/new backup restoration\n")})
    }

    fun sentenceRepairs(i: Instrumentation) {
        val prefs=Prefs.of(i.targetContext)
        val preferenceKeys=listOf(V3Prefs.CORRECTION_ENABLED,V3Prefs.CORRECT_NEAR,V3Prefs.CORRECT_SWAP,V3Prefs.CORRECT_EXTRA,V3Prefs.CORRECT_MISSING,Prefs.LEARN)
        val savedPreferences=preferenceKeys.associateWith {prefs.all[it]}
        i.runOnMainSync {
            prefs.edit().also {e->preferenceKeys.forEach {e.putBoolean(it,it!=Prefs.LEARN)}}.commit()
            SpellingAssist.configure(i.targetContext)
        }
        try {
        Lexicon.load(i.targetContext)
        PinyinEngine.initialize(i.targetContext)
        check(FcitxEngine.ready && PinyinEngine.ready)
        for (raw in listOf("jeude", "wsmdakuaile")) {
            val direct = PinyinEngine.candidates(raw.map(Char::toString))
            val native = FcitxEngine.primaryCandidates(raw.map(Char::toString)) {true}
            i.sendStatus(0, Bundle().apply {putString("stream", "$raw native=${native.take(3).map {it.w to it.py}} flags=${SpellingAssist.correctionFlags} swaps=${SpellingAssist.transpositions(raw)} detail=${direct.take(8).map {Triple(it.w,it.py,it.n)}}\n")})
        }
        val coreField=PinyinEngine::class.java.declaredFields.first {it.type==RimeBridge::class.java}.apply {isAccessible=true}
        val bridge=coreField.get(PinyinEngine) as RimeBridge
        for (raw in listOf("dshih","dashih","deshihou","dashihua")) {
            val decoded=bridge.query("kongye_26",raw)
            i.sendStatus(0, Bundle().apply {putString("stream","rime $raw ${decoded.candidates.take(5).map {listOf(it.text,it.quality,it.type,it.pinyin)}}\n")})
        }
        val expected = mapOf("dvshih" to "的时候", "deshihou" to "的时候", "buyaifangda" to "不要放大",
            "buyaofangda" to "不要放大", "xusnfuqiu" to "悬浮球", "xuanfuqiu" to "悬浮球",
            "jianshaocishu" to "减少次数", "qingwenshijian" to "请问时间", "buyaifangqi" to "不要放弃",
            "keyifangda" to "可以放大", "wsmdakuaile" to "为什么打快了")
        for ((raw, word) in expected) {
            val started = SystemClock.uptimeMillis()
            var primaryMs = -1L
            var initial = emptyList<Cand>()
            val result = PinyinEngine.candidatesLive(raw.map(Char::toString), true, {true}) {initial=it;primaryMs=SystemClock.uptimeMillis()-started}
            i.sendStatus(0, Bundle().apply {putString("stream",
                "$raw primary=${primaryMs}ms total=${SystemClock.uptimeMillis()-started}ms first=${initial.take(2).map {it.w}} final=${result.take(8).map {listOf(it.w,it.n,it.score,it.nativeChoice?.raw)}}\n")})
            for (page in listOf(result,PinyinEngine.expandedCandidates(raw.map(Char::toString)) {true}))
                check(page.first().w == word && page.first().n == raw.length) { "$raw expected $word got ${page.take(3).map {it.w}}" }
        }
        for ((raw, word) in mapOf("jeude" to "觉得", "woianu" to "我爱你", "nihaoo" to "你好")) {
            val result = PinyinEngine.candidates(raw.map(Char::toString))
            i.sendStatus(0, Bundle().apply { putString("stream", "$raw flags=${SpellingAssist.correctionFlags} swaps=${SpellingAssist.transpositions(raw)} correction=${result.take(5).map {Triple(it.w,it.py,it.n)}}\n") })
            check(if(raw=="nihaoo")result.any {it.w==word && it.n==raw.length && it.correction} else result.first().w == word) { "$raw expected $word" }
        }
        val feedback = SpellingAssist.feedback("woianu", "wo ai ni")
        check(feedback.errors.any {it} && feedback.swaps.isNotEmpty())
        i.runOnMainSync {
            val bar = CandidateBar(i.targetContext).apply { reserveSpelling = true }
            val width = android.view.View.MeasureSpec.makeMeasureSpec(1000, android.view.View.MeasureSpec.EXACTLY)
            bar.measure(width, 0)
            val height = bar.measuredHeight
            bar.inputSpelling = "n"
            bar.showCandidates(listOf(CandItem("你")), false)
            bar.measure(width, 0)
            check(bar.measuredHeight == height) { "Inline spelling resized the candidate bar" }
            val rows = Layouts.letters(true,i.targetContext)
            val emitted = ArrayList<Key>()
            val keyboard = KeyboardView(i.targetContext).apply {
                longMs=10000;glideEnabled=false
                listener=object:KeyboardView.Listener {
                    override fun onKey(key:Key){emitted.add(key)}
                    override fun onLongKey(key:Key){};override fun onPress(key:Key){}
                    override fun onCursorMove(dx:Int,dy:Int){};override fun onSwipeDel(down:Boolean){}
                }
            }
            keyboard.setup(Skins.KONGYE,rows)
            keyboard.measure(width,0);keyboard.layout(0,0,keyboard.measuredWidth,keyboard.measuredHeight)
            val a=rows.flatMap {it.keys}.first {it.letters=="a"}.rect
            val b=rows.flatMap {it.keys}.first {it.letters=="s"}.rect
            val now=SystemClock.uptimeMillis()
            for((action,x) in listOf(0 to a.centerX(),2 to b.centerX(),1 to b.centerX())) {
                val event=android.view.MotionEvent.obtain(now,now+20,action,x,a.centerY(),0)
                keyboard.onTouchEvent(event);event.recycle()
            }
            check(emitted.single().letters=="a") { "Release drift selected another key" }
            val preview=KeyboardPreviewLayout(i.targetContext).apply {
                orientation=android.widget.LinearLayout.VERTICAL
                addView(CandidateBar(i.targetContext).apply {showTools(listOf("more","emoji","edit","voice"))})
                addView(KeyboardView(i.targetContext).apply {this.preview=true;setup(Skins.KONGYE,Layouts.letters(true,i.targetContext))})
            }
            preview.measure(android.view.View.MeasureSpec.makeMeasureSpec(850,android.view.View.MeasureSpec.EXACTLY),0)
            preview.layout(0,0,preview.measuredWidth,preview.measuredHeight)
            val bitmap=android.graphics.Bitmap.createBitmap(preview.width,preview.height,android.graphics.Bitmap.Config.ARGB_8888)
            preview.draw(android.graphics.Canvas(bitmap))
            java.io.File(i.targetContext.getExternalFilesDir(null),"display-preview.png").outputStream().use {bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)}
            bitmap.recycle()

        }
        val flags = SpellingAssist.correctionFlags
        try {
            for ((raw, required) in mapOf("dvshih" to 4, "buyaifangda" to 1, "xusnfuqiu" to 1)) {
                SpellingAssist.correctionFlags = 15 xor required
                val words = PinyinEngine.candidates(raw.map(Char::toString))
                check(words.none {it.w == expected[raw] && it.verifiedSentenceRepair})
            }
        } finally { SpellingAssist.correctionFlags = flags }
        i.sendStatus(0, Bundle().apply {putString("stream","Sentence repairs and controls passed\n")})
        } finally {
            i.runOnMainSync {
                prefs.edit().also {e->savedPreferences.forEach {(k,v)->if(v is Boolean)e.putBoolean(k,v) else e.remove(k)}}.commit()
                SpellingAssist.configure(i.targetContext)
            }
        }
    }
    fun mixedTail(i: Instrumentation) {
        Lexicon.load(i.targetContext)
        PinyinEngine.initialize(i.targetContext)
        check(PinyinEngine.ready && FcitxEngine.ready)
        val cases = mapOf("huangg" to "黄瓜", "huanggua" to "黄瓜", "huang'g" to "黄瓜",
            "pingg" to "苹果", "xiangj" to "香蕉", "shuigu" to "水果", "xig" to "西瓜",
            "nang" to "囊", "nan'g" to "南瓜", "dongg" to "冬瓜", "kug" to "苦瓜", "dianh" to "电话",
            "shouj" to "手机", "jianp" to "键盘", "diann" to "电脑", "xuex" to "学习",
            "beij" to "北京", "shangh" to "上海", "gongz" to "工作", "huog" to "火锅",
            "nihao" to "你好", "pingan" to "平安", "xian" to "先", "shanxi" to "陕西")
        val failures = mutableListOf<String>()
        for ((raw, word) in cases) {
            var initial = emptyList<Cand>()
            val final = PinyinEngine.candidatesLive(raw.map(Char::toString), true, { true }) { initial = it }
            fun rank(list: List<Cand>) = list.indexOfFirst { it.w == word && it.n == raw.count { ch -> ch != '\'' } } + 1
            val firstRank = rank(initial)
            val finalRank = rank(final)
            i.sendStatus(0, Bundle().apply { putString("stream", "$raw $word ranks=$firstRank/$finalRank top=${final.take(6).map {it.w}}\n") })
            val limit = if (raw in setOf("huangg", "huanggua", "huang'g")) 1 else 6
            if (firstRank !in 1..limit || finalRank !in 1..limit) failures.add(raw)
        }
        check(failures.isEmpty()) { "Outside expected toolbar positions: $failures" }
    }
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
                check(shown.replace("'","")==raw.take(size));before=shown
            }
            report("stable pinyin $raw => $before")
        }
        FcitxEngine.clear()
        report("Official engine, updated model, native partial/full selection and stable spelling checks passed")
    }
}
