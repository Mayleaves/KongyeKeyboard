package com.kongye.keyboard

import android.app.Instrumentation
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import android.view.inspector.WindowInspector

/** Isolated real editor and production IME; no saved documents or accounts. */
object PartialSelectionDeleteRegression {
    fun run(inst:Instrumentation) {
        val ctx=inst.targetContext
        val auto=inst.getUiAutomation(android.app.UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        auto.serviceInfo=auto.serviceInfo.apply {flags=flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS}
        @Suppress("UNCHECKED_CAST") fun <T> ui(f:()->T):T {
            var value:Any?=null;var failure:Throwable?=null
            inst.runOnMainSync {try {value=f()}catch(t:Throwable){failure=t}}
            failure?.let {throw it};return value as T
        }
        fun waitFor(label:String,f:()->Boolean) {val end=SystemClock.uptimeMillis()+30000;while(SystemClock.uptimeMillis()<end){if(f())return;SystemClock.sleep(50)};error("Timed out: $label")}
        fun nodes(n:AccessibilityNodeInfo?):List<AccessibilityNodeInfo> = if(n==null)emptyList() else listOf(n)+(0 until n.childCount).flatMap {nodes(n.getChild(it))}
        fun editor():AccessibilityNodeInfo? {auto.clearCache();return auto.windows.flatMap {nodes(it.root)}.firstOrNull {it.packageName?.toString()=="com.kongye.keyboard.test" && it.contentDescription?.toString()=="独立输入测试框"}}
        fun descendants(v:View):List<View> = listOf(v)+(if(v is ViewGroup)(0 until v.childCount).flatMap {descendants(v.getChildAt(it))} else emptyList())
        fun views()=WindowInspector.getGlobalWindowViews().flatMap {descendants(it)}
        fun keyboard()=views().filterIsInstance<KeyboardView>().firstOrNull {it.isShown && !it.preview}
        Lexicon.load(ctx);FcitxEngine.initialize(ctx)
        val prefs=Prefs.of(ctx)
        val backup=listOf(Prefs.ZH,Prefs.LAYOUT,Prefs.VAULT,Prefs.LEARN,Prefs.ABBR,Prefs.COUNT_TODAY,Prefs.COUNT_DATE).associateWith {prefs.all[it]}
        var ime:KongyeIME?=null
        try {
            ui {prefs.edit().putBoolean(Prefs.ZH,true).putString(Prefs.LAYOUT,"26").putBoolean(Prefs.VAULT,false).putBoolean(Prefs.LEARN,false).putBoolean(Prefs.ABBR,true).commit()}
            ctx.startActivity(Intent(inst.context,TypingFormActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra("bottom",true))
            waitFor("independent editor") {editor()!=null}
            editor()!!.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            val bounds=android.graphics.Rect();editor()!!.getBoundsInScreen(bounds)
            val now=SystemClock.uptimeMillis()
            for(action in intArrayOf(android.view.MotionEvent.ACTION_DOWN,android.view.MotionEvent.ACTION_UP)) {
                val event=android.view.MotionEvent.obtain(now,SystemClock.uptimeMillis(),action,bounds.centerX().toFloat(),bounds.centerY().toFloat(),0)
                event.source=android.view.InputDevice.SOURCE_TOUCHSCREEN
                try {check(auto.injectInputEvent(event,true))}finally {event.recycle()}
            }
            waitFor("production keyboard") {ui {
                val current=keyboard()?.listener as? KongyeIME
                current!=null && current.isInputViewShown && current.currentInputEditorInfo?.packageName=="com.kongye.keyboard.test" && current.currentInputConnection!=null
            }}
            check(editor()!!.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT,Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "")
            }))
            SystemClock.sleep(250)
            val liveKeyboard=ui {keyboard()!!}
            val live=ui {liveKeyboard.listener as KongyeIME};ime=live
            fun imeViews()=descendants(liveKeyboard.rootView)
            fun tab()=ui {imeViews().filterIsInstance<PinyinTab>().firstOrNull {it.isShown}?.contentDescription?.toString()?.removePrefix("拼音：").orEmpty()}
            val pages=ArrayList<List<String>>()
            fun tabView()=imeViews().filterIsInstance<PinyinTab>().first {it.isShown}
            fun candidateWords():List<String> {
                val bar=imeViews().filterIsInstance<CandidateBar>().first {it.isShown}
                @Suppress("UNCHECKED_CAST") val items=CandidateBar::class.java.getDeclaredField("items").apply {isAccessible=true}.get(bar) as List<CandItem>
                return items.map {it.w}
            }
            fun compact(s:String)=s.replace("'","")
            fun editorText()=editor()?.text?.toString().orEmpty().takeUnless {it=="只使用虚构测试内容"}.orEmpty()
            fun type(raw:String) {for(c in raw) {ui {live.onKey(if(c=='\'')Key(Kind.SEP) else Key(Kind.LETTER,c.toString()))};SystemClock.sleep(20)}}
            fun expand() {
                ui {if(imeViews().filterIsInstance<ExpandedCandidates>().none {it.isShown}) live.onExpand()}
                SystemClock.sleep(600)
            }
            fun pick(word:String) {
                var index=-1
                waitFor("candidate $word") {ui {
                    val bar=imeViews().filterIsInstance<CandidateBar>().firstOrNull {it.isShown} ?: return@ui false
                    @Suppress("UNCHECKED_CAST") val items=CandidateBar::class.java.getDeclaredField("items").apply {isAccessible=true}.get(bar) as List<CandItem>
                    index=items.indexOfFirst {it.w==word};index>=0
                }}
                inst.sendStatus(0,Bundle().apply {putString("stream","Selecting $word at index $index from ${tab()}\n")})
                ui {pages.add(candidateWords());live.onCandidate(index)}
                try {waitFor("partial choice $word") {tab().contains(word)}}
                catch(t:Throwable) {error("Partial selection $word failed; current tab=${tab()}; ${t.message}") }
                val selected=tab().takeWhile {it in '一'..'鿿'}.length
                check(ui {tabView().selectedPrefixLength==selected && selected>0})
                ui {
                    val t=tabView()
                    val bitmap=android.graphics.Bitmap.createBitmap(t.width,t.height,android.graphics.Bitmap.Config.ARGB_8888)
                    t.draw(android.graphics.Canvas(bitmap))
                    java.io.File(ctx.getExternalFilesDir(null),"partial-selected-underline.png").outputStream().use {bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)}
                    bitmap.recycle()
                }
                // Let the remaining-syllable query settle before another choice.
                SystemClock.sleep(300)
            }
            fun back(expected:String) {
                val saved=if(pages.isNotEmpty()) pages.removeAt(pages.lastIndex) else null
                var elapsed=0L
                ui {
                    val start=SystemClock.elapsedRealtimeNanos()
                    live.onKey(Key(Kind.DEL))
                    elapsed=(SystemClock.elapsedRealtimeNanos()-start)/1000000
                    val t=tabView()
                    check(t.selectedPrefixLength==expected.takeWhile {it in '一'..'鿿'}.length)
                    if(saved!=null) {
                        check(candidateWords()==saved) {"Restore waited for a candidate query instead of restoring its snapshot"}
                        val errors=PinyinTab::class.java.getDeclaredField("errors").apply {isAccessible=true}.get(t) as BooleanArray
                        val swaps=PinyinTab::class.java.getDeclaredField("swaps").apply {isAccessible=true}.get(t) as List<*>
                        check(errors.none {it} && swaps.isEmpty()) {"Incorrect feedback in restored valid spelling frame"}
                        check(elapsed<120) {"Synchronous restore took $elapsed ms"}
                    }
                }
                inst.sendStatus(0,Bundle().apply {putString("stream","Restore $expected: $elapsed ms, immediate matching candidate page and clean feedback\n")})
                waitFor("restored $expected") {compact(tab())==compact(expected)}
                check(editorText().isEmpty()) {"A partial choice was committed before confirmation: ${editorText()}"}
            }
            type("nihaozhongguo");expand()
            pick("你");check(compact(tab())=="你haozhongguo")
            back("nihaozhongguo")
            // Re-select two prefixes, then undo them newest first without losing the suffix.
            pick("你");pick("好")
            check(compact(tab())=="你好zhongguo")
            back("你haozhongguo");back("nihaozhongguo");back("nihaozhonggu")
            ui {live.onKey(Key(Kind.RESET))}
            type("ni'hao'zhongguo");expand()
            pick("你好");back("ni'hao'zhongguo")
            // After all input is confirmed, delete edits committed text normally.
            ui {live.onKey(Key(Kind.SPACE))}
            waitFor("committed text") {tab().isEmpty()}
            waitFor("committed editor text") {editorText().isNotEmpty()}
            val committed=editorText()
            check(committed.isNotEmpty() && committed.none {it in 'a'..'z'})
            ui {live.onKey(Key(Kind.DEL))}
            waitFor("normal committed delete") {editorText().length==committed.length-1}
            inst.sendStatus(0,Bundle().apply {putString("stream","Single and multiple partial picks, unchanged suffix, original separator keys, word restore, ordinary letter delete, committed-text delete, selected underline and synchronous clean candidate restore PASSED\n")})
        } finally {
            ui {
                ime?.onKey(Key(Kind.RESET))
                val e=prefs.edit()
                for((key,value) in backup)when(value){null->e.remove(key);is Boolean->e.putBoolean(key,value);is String->e.putString(key,value);is Int->e.putInt(key,value)}
                e.commit()
            }
        }
    }
}
