package com.kongye.keyboard

import android.app.Instrumentation
import android.content.ClipData
import android.content.ClipboardManager
import android.graphics.Canvas
import android.os.Bundle
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.view.inspector.WindowInspector
import android.widget.ListView
import android.widget.TextView

/** Large histories, recycled card actions, rapid external copies and verbatim paste. */
object ClipboardPerformanceRegression {
    fun run(i:Instrumentation) {
        val ctx=i.targetContext
        fun report(value:String)=i.sendStatus(0,Bundle().apply {putString("stream",value+"\n")})
        @Suppress("UNCHECKED_CAST") fun <T> ui(block:()->T):T {
            var result:Any?=null;var error:Throwable?=null
            i.runOnMainSync {try {result=block()} catch(t:Throwable) {error=t}}
            error?.let {throw it};return result as T
        }
        fun all(v:View):List<View> = listOf(v)+if(v is ViewGroup)(0 until v.childCount).flatMap {all(v.getChildAt(it))} else emptyList()
        fun labels(v:View)=all(v).filterIsInstance<TextView>()
        fun size(v:View,width:Int=1080) {
            v.measure(View.MeasureSpec.makeMeasureSpec(width,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(600,View.MeasureSpec.EXACTLY))
            v.layout(0,0,width,600)
        }
        ClipStore.load(ctx)
        val saved=ClipStore.items.toList()
        val p=Prefs.of(ctx);val oldLimit=p.getInt(V3Prefs.CLIP_LIMIT,500)
        val cm=ctx.getSystemService(ClipboardManager::class.java)
        val original=runCatching {cm.primaryClip}.getOrNull()
        var ime:KongyeIME?=null
        try {
            ui {
                p.edit().putInt(V3Prefs.CLIP_LIMIT,500).commit()
                ClipStore.items.clear()
                repeat(500) {n->ClipStore.items.add(Clip("记录 $n · 原文 https://example.test/a?x=1&y=2\n第二行",n%50==0,10000L-n))}
                var pasted:Clip?=null
                var expanded:Clip?=null
                val times=ArrayList<Long>()
                for(width in listOf(1080,640,1080)) {
                    val start=SystemClock.elapsedRealtimeNanos()
                    val panel=ClipboardPanel(ctx,Skins.KONGYE,"全部","",null,null,{}, {}, {}, {}, {}, {}, {}, {pasted=it}, {expanded=it}, {_,_->})
                    size(panel,width)
                    times.add((SystemClock.elapsedRealtimeNanos()-start)/1000000)
                    val count=all(panel).size
                    check(count<250) {"First frame inflated the entire history: $count views"}
                    val records=panel.getChildAt(panel.childCount-1) as ListView
                    check(records.adapter.count==250)
                    val first=labels(records).first {it.text.toString().startsWith("记录 0 ·")}
                    (first.parent as View).performLongClick()
                    check(expanded===ClipStore.items[0])
                    panel.restoreScroll(249*records.getChildAt(0).height)
                    size(panel,width)
                    val last=labels(records).first {it.text.toString().startsWith("记录 499 ·")}
                    (last.parent as View).performClick()
                    check(pasted===ClipStore.items[499]) {"Recycled card pasted a different record"}
                    val position=panel.scrollPosition
                    panel.restoreScroll(0);size(panel,width)
                    check(labels(records).any {it.text.toString().startsWith("记录 0 ·")})
                    panel.restoreScroll(position);size(panel,width)
                    check(labels(records).any {it.text.toString().startsWith("记录 499 ·")})
                    report("500 records / width $width: ${times.last()} ms; $count first-frame views; last card and restored scroll correct")
                }
            }

            val auto=i.getUiAutomation(android.app.UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
            android.os.ParcelFileDescriptor.AutoCloseInputStream(auto.executeShellCommand("am start -W -n ${i.context.packageName}/com.kongye.keyboard.TypingFormActivity --ez show_keyboard true")).use {it.readBytes()}
            val until=SystemClock.uptimeMillis()+30000
            while(ime==null && SystemClock.uptimeMillis()<until) {
                ime=ui {WindowInspector.getGlobalWindowViews().flatMap(::all).filterIsInstance<CandidateBar>().firstOrNull {it.isShown && it.listener is KongyeIME}?.listener as? KongyeIME}
                if(ime==null)SystemClock.sleep(100)
            }
            val service=checkNotNull(ime) {"IME did not appear"}
            ui {
                val self=ClipData.newPlainText("keyboard fixture","keyboard-origin")
                check(ClipboardTransfer.copy(cm,self));check(ClipboardTransfer.own(checkNotNull(cm.primaryClip)))
                val external=ClipData.newPlainText("external fixture","external-immediately-after-own-copy")
                check(!ClipboardTransfer.own(external));cm.setPrimaryClip(external)
            }
            val copyEnd=SystemClock.uptimeMillis()+3000
            while(!ui {ClipStore.items.any {it.text=="external-immediately-after-own-copy"}} && SystemClock.uptimeMillis()<copyEnd)SystemClock.sleep(20)
            check(ui {ClipStore.items.any {it.text=="external-immediately-after-own-copy"}}) {"Rapid external copy was dropped"}
            ui {
                val connection=checkNotNull(service.currentInputConnection)
                val pairs=p.getBoolean(V3Prefs.PAIRS,true);val spaces=p.getBoolean(V3Prefs.SPACE_LATIN,false)
                try {
                    p.edit().putBoolean(V3Prefs.PAIRS,true).putBoolean(V3Prefs.SPACE_LATIN,true).commit()
                    val paste=KongyeIME::class.java.getDeclaredMethod("pasteExternal",String::class.java).apply {isAccessible=true}
                    for(text in listOf("(",".","github\nclaude\tAnthropic 😀")) {
                        connection.performContextMenuAction(android.R.id.selectAll);connection.commitText("中文",1)
                        paste.invoke(service,text)
                        val actual=connection.getTextBeforeCursor(200,0)?.toString()
                        check(actual=="中文$text") {"Paste changed the original text: $actual"}
                    }
                } finally {p.edit().putBoolean(V3Prefs.PAIRS,pairs).putBoolean(V3Prefs.SPACE_LATIN,spaces).commit()}
            }
            ui {
                fun panel()=WindowInspector.getGlobalWindowViews().flatMap(::all).filterIsInstance<ClipboardPanel>().first {it.isShown}
                val open=KongyeIME::class.java.getDeclaredMethod("openClipboard").apply {isAccessible=true}
                open.invoke(service)
                labels(panel()).first {it.text.toString()=="置顶"}.performClick()
                check(labels(panel()).first {it.text.toString()=="置顶"}.typeface.isBold)
                open.invoke(service)
                check(labels(panel()).first {it.text.toString()=="全部"}.typeface.isBold) {"Reopening retained the previous category"}
                check(!labels(panel()).first {it.text.toString()=="置顶"}.typeface.isBold)
            }
            report("CLIPBOARD PERFORMANCE PASSED: bounded first frame; recycled actions; scroll restore; external copy without 1.5s suppression; verbatim paste; reopening defaults to All")
        } finally {
            ui {
                ime?.let {KongyeIME::class.java.getDeclaredField("ignoreClipUntil").apply {isAccessible=true}.setLong(it,SystemClock.uptimeMillis()+1500)}
                if(original!=null)cm.setPrimaryClip(original) else cm.clearPrimaryClip()
                ClipStore.items.clear();ClipStore.items.addAll(saved);ClipStore.prune()
                p.edit().putInt(V3Prefs.CLIP_LIMIT,oldLimit).commit()
            }
        }
    }
}
