package com.kongye.keyboard

import android.app.Instrumentation
import android.graphics.Bitmap
import android.os.Bundle
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.inspector.WindowInspector
import android.widget.TextView
import java.io.File

/** Production IME path, including asynchronous supplementary handwriting. */
object Review306Regression {
    fun run(i:Instrumentation) {
        check(android.os.Build.MODEL.startsWith("sdk_"))
        val ctx=i.targetContext
        @Suppress("UNCHECKED_CAST") fun <T> ui(block:()->T):T {
            var out:Any?=null;var error:Throwable?=null
            i.runOnMainSync {try {out=block()}catch(t:Throwable){error=t}}
            error?.let {throw it};return out as T
        }
        fun report(s:String)=i.sendStatus(0,Bundle().apply {putString("stream",s+"\n")})
        fun all(v:View):List<View> = listOf(v)+if(v is ViewGroup)(0 until v.childCount).flatMap {all(v.getChildAt(it))}else emptyList()
        fun visible()=WindowInspector.getGlobalWindowViews().flatMap(::all).filter {it.isShown}
        fun waitFor(label:String,condition:()->Boolean) {
            val end=SystemClock.uptimeMillis()+30000
            while(!ui(condition) && SystemClock.uptimeMillis()<end)SystemClock.sleep(50)
            check(ui(condition)) {"Timed out: $label"}
        }
        fun screenshot(name:String) {
            i.waitForIdleSync();SystemClock.sleep(200)
            val bitmap=checkNotNull(i.uiAutomation.takeScreenshot())
            File(ctx.getExternalFilesDir(null),name).outputStream().use {bitmap.compress(Bitmap.CompressFormat.PNG,100,it)}
            bitmap.recycle()
        }
        val auto=i.getUiAutomation(android.app.UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        fun shell(cmd:String)=android.os.ParcelFileDescriptor.AutoCloseInputStream(auto.executeShellCommand(cmd)).use {it.readBytes()}
        shell("am force-stop ${i.context.packageName}")
        shell("am start -W -n ${i.context.packageName}/com.kongye.keyboard.TypingFormActivity --ez show_keyboard true")
        // API 37 may defer an activity's initial focus request. Use the actual
        // synthetic field's accessibility action, just as a user taps it.
        fun focusField(node:android.view.accessibility.AccessibilityNodeInfo?):Boolean {
            if(node==null)return false
            if(node.className=="android.widget.EditText")return node.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK)
            return (0 until node.childCount).any {focusField(node.getChild(it))}
        }
        for(attempt in 0..10) {
            if(focusField(auto.rootInActiveWindow))break
            SystemClock.sleep(150)
        }
        waitFor("IME") {visible().filterIsInstance<CandidateBar>().any {it.listener is KongyeIME}}
        val ime=ui {visible().filterIsInstance<CandidateBar>().first {it.listener is KongyeIME}.listener as KongyeIME}
        ui {ime.onTool("handwrite")}
        waitFor("handwriting pad") {visible().any {it is HandwriteView}}
        val pad=ui {visible().filterIsInstance<HandwriteView>().first()}
        val source=listOf(
            listOf(34f to 40f,39f to 65f),listOf(84f to 34f,69f to 62f),
            listOf(62f to 18f,55f to 61f,45f to 84f,23f to 112f),listOf(53f to 76f,72f to 91f,98f to 113f))
        val ink=listOf(0f to 0f,140f to 0f,0f to 140f,140f to 140f).flatMap {(x,y)->source.map {stroke->stroke.map {(a,b)->a+x to b+y}}}
        ui {
            fun stroke(points:List<Pair<Float,Float>>) {
                val area=pad.writingBounds()
                val scale=minOf(pad.padWidth()/290f,pad.padHeight()/290f)
                val time=SystemClock.uptimeMillis()
                points.forEachIndexed {at,(x,y)->
                    val action=if(at==0)MotionEvent.ACTION_DOWN else if(at==points.lastIndex)MotionEvent.ACTION_UP else MotionEvent.ACTION_MOVE
                    val event=MotionEvent.obtain(time,time+at*15,action,area.left+10+x*scale,area.top+10+y*scale,0)
                    pad.onTouchEvent(event);event.recycle()
                }
            }
            ink.forEach(::stroke)
            check(pad.inkStrokes().size==16 && pad.canUndo && !pad.canRedo)
            val actionRect=HandwriteView::class.java.getDeclaredMethod("sideRect",Int::class.javaPrimitiveType).apply {isAccessible=true}
            fun tap(action:Int) {
                val box=actionRect.invoke(pad,action) as android.graphics.RectF
                val time=SystemClock.uptimeMillis()
                for(type in intArrayOf(MotionEvent.ACTION_DOWN,MotionEvent.ACTION_UP)) {
                    val e=MotionEvent.obtain(time,time,type,box.centerX(),box.centerY(),0);pad.onTouchEvent(e);e.recycle()
                }
            }
            val top=(0..2).map {actionRect.invoke(pad,it) as android.graphics.RectF}
            val right=(3..5).map {actionRect.invoke(pad,it) as android.graphics.RectF}
            check(top.all {it.bottom<pad.writingBounds().top})
            check(right.all {kotlin.math.abs(it.width()-right.first().width())<1f && kotlin.math.abs(it.height()-right.first().height())<1f})
            check(kotlin.math.abs(right.first().top-pad.writingBounds().top)<=1f)
            tap(0);check(pad.inkStrokes().size==15 && pad.canRedo)
            tap(1);check(pad.inkStrokes().size==16 && !pad.canRedo)
            pad.undoStroke();stroke(ink.last());check(pad.inkStrokes().size==16 && !pad.canRedo)
        }
        val choices=KongyeIME::class.java.getDeclaredField("handCands").apply {isAccessible=true}
        @Suppress("UNCHECKED_CAST") fun candidates()=choices.get(ime) as List<String>
        waitFor("visible rare handwriting choice") {"燚" in candidates()}
        ui {
            check(candidates().all {it.codePointCount(0,it.length)==1}) {"A phrase leaked into handwriting choices"}
            check(candidates().indexOf("燚")<5) {"Supplementary result hidden past first screen"}
        }
        screenshot("review306-handwriting.png")
        ui {ime.onExpand()}
        waitFor("expanded candidates") {visible().any {it is ExpandedCandidates}}
        ui {
            val expanded=visible().filterIsInstance<ExpandedCandidates>().first()
            val tools=expanded.getChildAt(1) as ViewGroup
            tools.getChildAt(0).performClick()
        }
        waitFor("restored pad") {visible().any {it===pad}}
        ui {check(pad.inkStrokes().size==16) {"Expanding candidates discarded stroke history"}}
        ui {ime.onCandidate(candidates().indexOf("燚"))}
        waitFor("committed rare character") {ime.currentInputConnection?.getTextBeforeCursor(10,0)?.toString()=="燚"}
        ui {check(pad.empty && !pad.canUndo && !pad.canRedo)}
        report("LIVE HANDWRITING PASSED: bundled first-use model; 燚 in first screen; stroke undo/redo and branch reset; only single characters; expansion preserves ink; chosen character committed once")

        val preferences=Prefs.of(ctx)
        val oldEnglish=preferences.getBoolean(V3Prefs.RECOGNIZE_ENGLISH,true)
        val oldPosition=preferences.getInt(V3Prefs.PINYIN_POSITION,0)
        try {
            ui {ime.onTool("handwrite");preferences.edit().putBoolean(V3Prefs.RECOGNIZE_ENGLISH,true).putInt(V3Prefs.PINYIN_POSITION,0).commit()}
            waitFor("ordinary keyboard") {visible().none {it is HandwriteView}}
            val textField=PinyinTab::class.java.getDeclaredField("text").apply {isAccessible=true}
            fun text()=visible().filterIsInstance<PinyinTab>().firstOrNull()?.let {textField.get(it) as String}.orEmpty()
            val itemsField=CandidateBar::class.java.getDeclaredField("items").apply {isAccessible=true}
            val resolvedField=KongyeIME::class.java.getDeclaredField("resolvedKeys").apply {isAccessible=true}
            val pendingField=KongyeIME::class.java.getDeclaredField("candidatesPending").apply {isAccessible=true}
            fun resolved(raw:String)=resolvedField.get(ime)==raw.map(Char::toString) && !pendingField.getBoolean(ime)
            @Suppress("UNCHECKED_CAST") fun stripWords():List<String> {
                val bar=visible().filterIsInstance<CandidateBar>().firstOrNull {it.listener===ime} ?: return emptyList()
                return (itemsField.get(bar) as List<CandItem>).map {it.w}
            }
            for(letter in "chong") {ui {ime.onKey(Key(Kind.LETTER,letter.toString()))};SystemClock.sleep(15)}
            waitFor("normal chong candidates") {resolved("chong") && stripWords().size>=8}
            val allField=ExpandedCandidates::class.java.getDeclaredField("all").apply {isAccessible=true}
            fun expanded()=visible().filterIsInstance<ExpandedCandidates>().first()
            fun checkOrder(expected:List<String>) {
                val actual=(allField.get(expanded()) as List<*>).take(expected.size)
                check(actual==expected) {"Expanding changed order: expected ${expected.take(8)}, got ${actual.take(8)}"}
            }
            val original=ui {
                val words=stripWords()
                ime.onExpand()
                checkOrder(words)
                words
            }
            waitFor("expanded chong lookup") {resolved("chong")}
            ui {
                checkOrder(original)
                (expanded().getChildAt(1) as ViewGroup).getChildAt(0).performClick()
            }
            waitFor("collapsed candidate list") {visible().none {it is ExpandedCandidates}}
            report("LIVE EXPANSION PASSED: full lookup preserved all ${original.size} candidates from the ordinary strip in order")
            for(raw in listOf("claude","github","anthropic")) {
                ui {ime.onKey(Key(Kind.RESET))}
                for(letter in raw) {ui {ime.onKey(Key(Kind.LETTER,letter.toString()))};SystemClock.sleep(15)}
                waitFor("literal English spelling $raw") {resolved(raw) && text()==raw}
            }
            screenshot("layout308-english.png")
            ui {ime.onKey(Key(Kind.RESET))}
            for(letter in "xi'an") {ui {ime.onKey(if(letter=='\'')Key(Kind.SEP)else Key(Kind.LETTER,letter.toString()))};SystemClock.sleep(15)}
            waitFor("explicit pinyin separator") {text().contains("'")}
            ui {ime.onKey(Key(Kind.RESET))}
            report("ENGLISH SPELLING PASSED: claude, github, anthropic display without pinyin separators; explicitly entered Chinese separator retained")
        } finally {ui {preferences.edit().putBoolean(V3Prefs.RECOGNIZE_ENGLISH,oldEnglish).putInt(V3Prefs.PINYIN_POSITION,oldPosition).commit()}}

        ClipStore.load(ctx)
        val saved=ClipStore.items.toList();val p=Prefs.of(ctx);val categories=p.getString(Prefs.CLIP_CATS,"")
        try {
            ui {
                ClipStore.addCategory("test306")
                for(tab in listOf("全部","置顶","文本","图片","test306")) {
                    val image=File(checkNotNull(ClipStore.dir),"review306-image.png").apply {writeBytes(byteArrayOf(1,2,3))}
                    val fixtures=listOf(Clip("普通文字",false,1),Clip("置顶文字",true,2),Clip("分类文字",false,3,cat="test306"),
                        Clip("[图片]",true,4,image.name,cat="test306"))
                    ClipStore.items.clear();ClipStore.items.addAll(fixtures)
                    val expected=when(tab){"全部"->fixtures;"置顶"->fixtures.filter {it.pinned};"文本"->fixtures.filter {it.image==null};"图片"->fixtures.filter {it.image!=null};else->fixtures.filter {it.cat==tab}}
                    check(ClipStore.clearCategory(tab)==expected.size)
                    check(ClipStore.items==fixtures.filter {it !in expected}) {"Deleted outside $tab"}
                    check(image.exists()==fixtures.last().let {it !in expected})
                    check("test306" in ClipStore.tabs()) {"Clearing removed the category itself"}
                    image.delete()
                }
            }
            report("CATEGORY CLEAR PASSED: All / pinned / text / images / custom; includes pinned and saved records; other records and category names retained; image files removed")
        } finally {ui {ClipStore.items.clear();ClipStore.items.addAll(saved);p.edit().putString(Prefs.CLIP_CATS,categories).commit();ClipStore.prune()}}
    }
}
