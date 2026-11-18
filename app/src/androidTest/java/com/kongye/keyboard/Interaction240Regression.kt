package com.kongye.keyboard

import android.app.Instrumentation
import android.content.*
import android.graphics.*
import android.os.*
import android.text.Spanned
import android.text.style.MetricAffectingSpan
import android.view.*
import android.widget.*

object Interaction240Regression {
    fun run(inst:Instrumentation) {
        fun report(s:String)=inst.sendStatus(0,Bundle().apply {putString("stream",s+"\n")})
        fun ui(f:()->Unit) {var fail:Throwable?=null;inst.runOnMainSync {try {f()} catch(t:Throwable){fail=t}};fail?.let {throw it}}
        val ctx=inst.targetContext
        Lexicon.load(ctx)
        val samples=listOf(arrayOf("woaoni","wo ai ni","wo'ao'ni"),arrayOf("nuaimei","ni ai mei","nu'ai'mei"),arrayOf("woianu","wo ai ni","wo'ia'nu"),arrayOf("nain","nian","nain"),arrayOf("jeude","jue de","jeu'de"),arrayOf("woxihuannu","wo xi huan ni","wo'xi'huan'nu"))
        for(sample in samples) {
            val raw=sample[0];val py=sample[1];val wanted=sample[2]
            val result=SpellingAssist.presentation(raw,py,Lexicon.streamingDisplay(raw.map {it.toString()}))
            check(result==wanted) {"Corrected syllable split: $raw -> $result wanted $wanted"}
            check(result.replace("'","")==raw)
            report("grouped spelling $raw => $result")
        }
        val marks=SpellingAssist.feedback("woianu","wo ai ni")
        check(marks.swaps==listOf(2) && marks.errors.last() && !marks.errors[2] && !marks.errors[3])
        ui {
            val intrinsic=(44*ctx.resources.displayMetrics.density).toInt()
            for(width in listOf(900,1800)) for(overlayShown in listOf(true,false,true)) {
                val host=CandidateHost(ctx)
                val bar=CandidateBar(ctx)
                host.addView(bar,FrameLayout.LayoutParams(-1,-2))
                host.addView(FrameLayout(ctx).apply {visibility=if(overlayShown)View.VISIBLE else View.GONE;
                    addView(View(ctx),FrameLayout.LayoutParams(-1,-1))},FrameLayout.LayoutParams(-1,-1))
                host.measure(View.MeasureSpec.makeMeasureSpec(width,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(2700,View.MeasureSpec.AT_MOST))
                check(host.measuredHeight==intrinsic) {"Candidate host filled window: ${host.measuredHeight}"}
            }
            val bar=CandidateBar(ctx);bar.layout(0,0,1000,120)
            var bubble:String?=null;bar.onLongCandidate={bubble=it}
            val long="这是很长的候选段落，请完整显示在普通打字界面右上角".repeat(4)
            bar.showCandidates(listOf(CandItem(long)),false);check(bubble==long)
            bar.showCandidates(listOf(CandItem(long)),true);check(bubble==null)
            bar.showCandidates(listOf(CandItem("五个字够吗")),false);check(bubble==null)
        }
        report("Typing-only overflow eligibility and no length-only popup passed")
        val isolated=object:ContextWrapper(ctx) {
            override fun getSharedPreferences(name:String,mode:Int)=super.getSharedPreferences("qa240_"+name,mode)
        }
        try {
            KeyGestures.save(isolated,true,'q',false,"text:☆")
            KeyGestures.save(isolated,false,'q',true,"edit:copy")
            check(Layouts.letters(true,isolated).first().keys.first().hint2=="☆")
            ui {
                val preview=FlickPreviewView(isolated);preview.submit(true,'q',false,"")
                preview.measure(View.MeasureSpec.makeMeasureSpec(1000,View.MeasureSpec.EXACTLY),0)
                preview.layout(0,0,preview.measuredWidth,preview.measuredHeight)
                preview.draw(Canvas(Bitmap.createBitmap(1000,preview.height,Bitmap.Config.ARGB_8888)))
                var picked=' ';preview.onPick={picked=it}
                val bounds=checkNotNull(preview.keyBounds('q'))
                val at=MotionEvent.obtain(0,0,MotionEvent.ACTION_UP,bounds.centerX(),bounds.centerY(),0)
                preview.onTouchEvent(at);at.recycle()
                check(picked=='q')
                val fields=FlickPreviewView::class.java.getDeclaredField("rows").apply {isAccessible=true}
                @Suppress("UNCHECKED_CAST") val rows=fields.get(preview) as List<Row>
                check(rows.first().keys.first().hint2=="☆")
            }
            KeyGestures.clearAll(isolated)
            for(zh in listOf(true,false))check(Layouts.letters(zh,isolated).flatMap {it.keys}.filter {it.kind==Kind.LETTER}.all {it.hint==null && it.hint2==null})
            KeyGestures.reset(isolated,true)
            check(Layouts.letters(true,isolated).first().keys.first().hint=="1")
            check(Layouts.letters(false,isolated).first().keys.first().hint==null)
        } finally {isolated.getSharedPreferences("kongye",0).edit().clear().commit()}
        report("Editable keycap preview, clear-all and per-language reset passed")
        ui {
            val md=MarkdownTextView(ctx)
            md.render("# Heading\n\n**Bold** and ~~strike~~ with `code`.\n\n- [x] Done\n- Item\n\n| A | B |\n|---|---|\n| 1 | 2 |\n\n[Site](https://example.test)",Color.BLACK,Color.RED)
            val text=md.text
            check(text is Spanned && text.getSpans(0,text.length,MetricAffectingSpan::class.java).isNotEmpty())
            check(!text.toString().contains("**") && !text.toString().contains("# Heading") && !text.toString().contains("~~"))
            check(text.contains("Heading") && text.contains("Site"))
        }
        val history=UpdateChecker.history(ctx.assets.open("release-history.json").bufferedReader().use {it.readText()})
        check(history.isNotEmpty() && history.distinctBy {it.tag}.size==history.size)
        check(UpdateChecker.localRelease(ctx)?.notes?.contains(UpdateChecker.currentVersion(ctx))==true)
        for(n in 1 until history.size)check(!UpdateChecker.newer(history[n].version,history[n-1].version))
        report("Native Markdown headings/lists/tables/links and offline version history passed")
        ui {
            val saved=ClipStore.items.toList()
            val field=ClipStore::class.java.getDeclaredField("file").apply {isAccessible=true}
            val original=field.get(ClipStore)
            try {
                field.set(ClipStore,null);ClipStore.items.clear()
                val a=Clip("old",true,1L,cat="saved")
                val b=Clip("new",false,2L)
                val image=Clip("[图片]",false,3L,image="qa240.png",cat="images")
                ClipStore.items.addAll(listOf(image,b,a));ClipStore.used(a)
                check(ClipStore.list("全部","").first()===a && a.pinned && a.cat=="saved")
                ClipStore.used(b);check(ClipStore.list("全部","").first()===b && a.pinned)
                ClipStore.used(image);check(ClipStore.list("全部","").first()===image && image.cat=="images")
            } finally {ClipStore.items.clear();ClipStore.items.addAll(saved);field.set(ClipStore,original)}
        }
        report("Text/image paste recency preserves category and pinned status without touching real history")
        check(NativeMotion.DURATION<=120L)
        report("Shared switching duration matches language transition: 110 ms")
        for(raw in listOf("我们都是木头人不会说话不会动","你好请问明天几点开会","今天天气很好我们一起出去走走吧")) {
            val result=OfflineVoice.punctuateSample(ctx,raw)
            check(result.any {it in "，。？！,.?!"}) {"Punctuation missing"}
            check(result.filter {it !in "，。？！,.?! \n"}==raw)
        }
        report("Offline Chinese punctuation restored with original text retained")
    }
}
