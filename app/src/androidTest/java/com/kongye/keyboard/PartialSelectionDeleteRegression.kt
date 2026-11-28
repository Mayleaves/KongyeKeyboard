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
        val backup=listOf(Prefs.ZH,Prefs.LAYOUT,Prefs.VAULT,Prefs.LEARN,Prefs.ABBR,Prefs.COUNT_TODAY,Prefs.COUNT_DATE,Prefs.SMS_CODE,V3Prefs.COMPOSE_RESTORE,V3Prefs.COMPOSE_HOLD_CLEAR).associateWith {prefs.all[it]}
        val clipboard=ctx.getSystemService(android.content.ClipboardManager::class.java)
        var savedClip:android.content.ClipData?=null
        val expiry=ctx.getSharedPreferences("sms_code_expiry",android.content.Context.MODE_PRIVATE)
        val savedExpiry=expiry.all.toMap()
        var touchedClipboard=false
        var ime:KongyeIME?=null
        try {
            ui {prefs.edit().putBoolean(Prefs.ZH,true).putString(Prefs.LAYOUT,"26").putBoolean(Prefs.VAULT,false).putBoolean(Prefs.LEARN,false).putBoolean(Prefs.ABBR,true).putBoolean(V3Prefs.COMPOSE_RESTORE,true).putBoolean(V3Prefs.COMPOSE_HOLD_CLEAR,true).commit()}
            android.os.ParcelFileDescriptor.AutoCloseInputStream(auto.executeShellCommand("am start -W -f 0x10008000 -n com.kongye.keyboard.test/com.kongye.keyboard.TypingFormActivity --ez bottom true")).use {it.readBytes()}
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
                // Expanded candidates hide the toolbar, but keep its current candidate data.
                val bar=imeViews().filterIsInstance<CandidateBar>().first()
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
                    val bar=imeViews().filterIsInstance<CandidateBar>().firstOrNull() ?: return@ui false
                    @Suppress("UNCHECKED_CAST") val items=CandidateBar::class.java.getDeclaredField("items").apply {isAccessible=true}.get(bar) as List<CandItem>
                    index=items.indexOfFirst {it.w==word};index>=0
                }}
                inst.sendStatus(0,Bundle().apply {putString("stream","Selecting $word at index $index from ${tab()}\n")})
                waitFor("stable candidate dispatch $word") {ui {
                    val current=candidateWords();val selected=current.indexOf(word)
                    if(selected<0)false else {pages.add(current);live.onCandidate(selected);true}
                }}
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
            // Both new switches gate the real IME rather than only changing settings UI.
            ui {live.onKey(Key(Kind.RESET));prefs.edit().putBoolean(V3Prefs.COMPOSE_RESTORE,false).commit()}
            type("nihaozhongguo");expand();pick("你")
            ui {live.onKey(Key(Kind.DEL))}
            waitFor("disabled restore deletes one letter and keeps the selected word") {compact(tab())=="你haozhonggu"}
            val preserved=editorText()
            ui {live.onKey(Key(Kind.RESET));prefs.edit().putBoolean(V3Prefs.COMPOSE_RESTORE,true).commit()}
            // Physical keyboard gestures require returning from the expanded candidate panel.
            ui {if(imeViews().filterIsInstance<ExpandedCandidates>().any {it.isShown})live.onExpand()}
            waitFor("letter keyboard before hold") {ui {liveKeyboard.isShown}}
            fun holdDelete() {
                val point=ui {
                    @Suppress("UNCHECKED_CAST") val rows=KeyboardView::class.java.getDeclaredField("rows").apply {isAccessible=true}.get(liveKeyboard) as List<Row>
                    val key=rows.flatMap {it.keys}.first {it.kind==Kind.DEL}
                    val location=IntArray(2);liveKeyboard.getLocationOnScreen(location)
                    floatArrayOf(location[0]+key.rect.centerX(),location[1]+key.rect.centerY())
                }
                val down=SystemClock.uptimeMillis()
                fun touch(action:Int) {val e=android.view.MotionEvent.obtain(down,SystemClock.uptimeMillis(),action,point[0],point[1],0);e.source=android.view.InputDevice.SOURCE_TOUCHSCREEN;try {check(auto.injectInputEvent(e,true))}finally {e.recycle()}}
                // Repeat starts at 420 ms, then every 55 ms; exercise several repeats.
                touch(0);SystemClock.sleep(620);touch(1)
            }
            ui {prefs.edit().putBoolean(V3Prefs.COMPOSE_HOLD_CLEAR,false).commit()}
            type("nihaozhongguo");waitFor("hold clear disabled"){ui {!liveKeyboard.clearCompositionOnHold}}
            holdDelete();waitFor("held delete repeats instead of clearing"){val t=compact(tab());t.isNotEmpty()&&t.length<12}
            check(editorText()==preserved)
            ui {live.onKey(Key(Kind.RESET));prefs.edit().putBoolean(V3Prefs.COMPOSE_HOLD_CLEAR,true).commit()}
            type("nihaozhongguo");waitFor("hold clear enabled"){ui {liveKeyboard.clearCompositionOnHold}}
            holdDelete();waitFor("held delete clears composition"){tab().isEmpty()}
            check(editorText()==preserved)
            inst.sendStatus(0,Bundle().apply {putString("stream","Independent composing switches: disabled restore deletes the final letter and keeps the selected word, disabled long-clear repeats deletion, enabled long-clear clears only composing text. PASSED\n")})
            savedClip=ui {clipboard.primaryClip};touchedClipboard=true
            val history=ClipStore.items.map {it.text to it.time}
            ui {prefs.edit().putBoolean(Prefs.SMS_CODE,true).commit();check(SmsCodeClipboard.copy(ctx,"2046"))}
            fun smsBar()=ui {imeViews().filterIsInstance<CandidateBar>().firstOrNull {it.isShown&&it.contentDescription?.toString()?.contains("来自「信息」的验证码：2046")==true}}
            waitFor("real SMS code bar"){smsBar()!=null}
            check(ClipStore.items.map {it.text to it.time}==history){"SMS leaked into clipboard history"}
            ui {
                val bar=checkNotNull(imeViews().filterIsInstance<CandidateBar>().firstOrNull {it.isShown&&it.contentDescription?.toString()?.contains("2046")==true})
                val bitmap=android.graphics.Bitmap.createBitmap(bar.width,bar.height,android.graphics.Bitmap.Config.ARGB_8888);bar.draw(android.graphics.Canvas(bitmap))
                java.io.File(ctx.getExternalFilesDir(null),"sms-real-candidate-bar.png").outputStream().use {bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)};bitmap.recycle()
            }
            val beforeCode=editorText()
            val target=ui {val bar=imeViews().filterIsInstance<CandidateBar>().first {it.isShown&&it.contentDescription?.toString()?.contains("2046")==true};val location=IntArray(2);bar.getLocationOnScreen(location);floatArrayOf(location[0]+bar.width/2f,location[1]+bar.height/2f)}
            val tapTime=SystemClock.uptimeMillis()
            for(action in intArrayOf(0,1)){val event=android.view.MotionEvent.obtain(tapTime,SystemClock.uptimeMillis(),action,target[0],target[1],0);event.source=android.view.InputDevice.SOURCE_TOUCHSCREEN;try {check(auto.injectInputEvent(event,true))}finally {event.recycle()}}
            waitFor("code tap fills editor"){editorText()==beforeCode+"2046"}
            check(ClipStore.items.map {it.text to it.time}==history)
            ui {prefs.edit().putBoolean(Prefs.SMS_CODE,false).commit()}
            waitFor("disabled SMS stops suggestion"){smsBar()==null}
            inst.sendStatus(0,Bundle().apply {putString("stream","Real SMS clipboard path shows source and code, touch fills only the code, disabling hides it, and no history entry is created. PASSED\n")})
            inst.sendStatus(0,Bundle().apply {putString("stream","Single and multiple partial picks, unchanged suffix, original separator keys, word restore, ordinary letter delete, committed-text delete, selected underline and synchronous clean candidate restore PASSED\n")})
        } finally {
            ui {
                ime?.onKey(Key(Kind.RESET));ime?.onSmsCodeDismiss()
                if(touchedClipboard) {
                    val e=expiry.edit().clear();savedExpiry.forEach {(key,value)->when(value){is String->e.putString(key,value);is Long->e.putLong(key,value)}};e.commit()
                    ime?.let {service->KongyeIME::class.java.getDeclaredField("ignoreClipUntil").apply {isAccessible=true}.setLong(service,SystemClock.uptimeMillis()+1500)}
                    if(savedClip!=null)clipboard.setPrimaryClip(savedClip!!) else if(android.os.Build.VERSION.SDK_INT>=28)clipboard.clearPrimaryClip()
                    SmsCodeClipboard.schedule(ctx)
                }
                ime?.onKey(Key(Kind.RESET))
                val e=prefs.edit()
                for((key,value) in backup)when(value){null->e.remove(key);is Boolean->e.putBoolean(key,value);is String->e.putString(key,value);is Int->e.putInt(key,value)}
                e.commit()
            }
        }
    }
}
