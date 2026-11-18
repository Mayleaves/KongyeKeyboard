package com.kongye.keyboard

import android.app.Instrumentation
import android.os.Bundle
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.inspector.WindowInspector

object SettingsPreviewRegression {
    fun run(i:Instrumentation) {
        val ctx=i.targetContext
        val auto=i.getUiAutomation(android.app.UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        auto.serviceInfo=auto.serviceInfo.apply {flags=flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS}
        fun nodes(n:AccessibilityNodeInfo?):List<AccessibilityNodeInfo> = if(n==null)emptyList() else listOf(n)+(0 until n.childCount).flatMap {nodes(n.getChild(it))}
        fun allNodes():List<AccessibilityNodeInfo>{auto.clearCache();return auto.windows.flatMap {nodes(it.root)}}
        fun waitFor(name:String,test:()->Boolean) {val end=SystemClock.uptimeMillis()+30000;while(SystemClock.uptimeMillis()<end){if(test())return;SystemClock.sleep(60)};error("Timed out: $name")}
        fun click(t:String) {
            waitFor(t){allNodes().any {it.text?.toString()==t || it.contentDescription?.toString()==t}}
            val n=allNodes().first {(it.text?.toString()==t || it.contentDescription?.toString()==t) && (t!="收起键盘" || it.className?.toString()=="android.widget.TextView")}
            val b=android.graphics.Rect();n.getBoundsInScreen(b)
            val down=SystemClock.uptimeMillis()
            for(a in intArrayOf(0,1)){val e=MotionEvent.obtain(down,SystemClock.uptimeMillis(),a,b.exactCenterX(),b.exactCenterY(),0);e.source=android.view.InputDevice.SOURCE_TOUCHSCREEN;try{check(auto.injectInputEvent(e,true))}finally{e.recycle()}}
            SystemClock.sleep(200)
        }
        @Suppress("UNCHECKED_CAST") fun <T> ui(block:()->T):T {var r:Any?=null;var e:Throwable?=null;i.runOnMainSync{try{r=block()}catch(t:Throwable){e=t}};e?.let {throw it};return r as T}
        fun descendants(v:View):List<View> = listOf(v)+(if(v is ViewGroup)(0 until v.childCount).flatMap {descendants(v.getChildAt(it))} else emptyList())
        fun views()=WindowInspector.getGlobalWindowViews().flatMap {descendants(it)}
        fun keyboard()=views().filterIsInstance<KeyboardView>().firstOrNull {it.isShown&&!it.preview}
        fun editor()=views().filterIsInstance<android.widget.EditText>().firstOrNull {it.isShown&&it.contentDescription=="键盘效果输入框"}
        val prefs=Prefs.of(ctx);val backup=prefs.all.toMap()
        try {
            ui {prefs.edit().putBoolean(Prefs.ZH,true).putBoolean(Prefs.VAULT,false).putBoolean(Prefs.LEARN,false).putInt(V3Prefs.ONE_HAND,0).putBoolean(Prefs.FLOAT,false).putString(Prefs.LAYOUT,"26").putInt(V3Prefs.GLOSS,2).putInt(V3Prefs.PINYIN_POSITION,0).commit()}
            android.os.ParcelFileDescriptor.AutoCloseInputStream(auto.executeShellCommand("am start -W -f 0x10008000 -n com.kongye.keyboard/.SettingsActivity")).use {it.readBytes()}
            SystemClock.sleep(2000)
            click("显示键盘")
            waitFor("real settings IME"){ui {editor()!=null&&keyboard()?.listener is KongyeIME&&(keyboard()?.listener as KongyeIME).isInputViewShown}}
            val live=ui {keyboard()!!.listener as KongyeIME}
            for(ch in "nihao"){ui {live.onKey(Key(Kind.LETTER,ch.toString()))};SystemClock.sleep(40)}
            SystemClock.sleep(900);ui {live.onKey(Key(Kind.SPACE))}
            waitFor("settings preview Chinese commit"){ui {editor()?.text?.toString()=="你好"}}
            ui {live.onKey(Key(Kind.CHAR,"\n"));live.onKey(Key(Kind.CHAR,"乙"))}
            waitFor("multiline preview"){ui {editor()?.text?.toString()=="你好\n乙"}}
            ui {live.onKey(Key(Kind.DEL))}
            waitFor("first multiline backspace"){ui {editor()?.text?.toString()=="你好\n"}}
            ui {live.onKey(Key(Kind.DEL))}
            SystemClock.sleep(500)
            i.sendStatus(0,Bundle().apply {putString("stream","Preview after paragraph backspace: "+ui {editor()?.text?.toString()?.map {it.code}?.joinToString(",")}+"\n")})
            waitFor("actual multiline backspace"){ui {editor()?.text?.toString()=="你好"}}
            click("收起键盘")
            waitFor("restore same page"){allNodes().any {it.text?.toString()=="今日输入"}}
            click("输入设置");click("候选与联想")
            // The first toggle is visible without scrolling. Its example must
            // preserve the entry value and update only the current side.
            val initial=prefs.getBoolean(Prefs.ASSOC,true)
            click("联想词")
            waitFor("live before and after"){ui {views().filterIsInstance<SettingExampleView>().any {it.key==Prefs.ASSOC && it.before==initial && it.after==!initial}}}
            click("显示键盘")
            waitFor("nested settings IME"){ui {editor()!=null&&keyboard()?.listener is KongyeIME&&(keyboard()?.listener as KongyeIME).isInputViewShown}}
            click("收起键盘")
            waitFor("nested page preserved"){allNodes().any {it.text?.toString()=="候选与联想"}}
            click("候选与联想");waitFor("home search"){allNodes().any {it.isEditable}}
            val search=allNodes().first {it.isEditable}
            check(search.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT,Bundle().apply {putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,"纠错")}))
            click("纠错")
            waitFor("search keyboard already open"){ui {keyboard()?.let {(it.listener as? KongyeIME)?.isInputViewShown}==true}}
            click("显示键盘");waitFor("overlay over open keyboard"){ui {editor()!=null}}
            click("收起键盘")
            waitFor("IME hidden after closing overlay"){ui {editor()==null&&keyboard()?.let {(it.listener as? KongyeIME)?.isInputViewShown}!=true}}
            SystemClock.sleep(700)
            check(ui {keyboard()?.let {(it.listener as? KongyeIME)?.isInputViewShown}!=true}) {"Keyboard reopened for the old search focus"}
            check(allNodes().any {it.isEditable&&it.text?.toString()=="纠错"}) {"Search query lost after closing overlay"}
            val screenshot=auto.takeScreenshot()
            if(screenshot!=null) {
                val folder=java.io.File(ctx.getExternalFilesDir(null),"search-highlight-pictures").apply {mkdirs()}
                java.io.File(folder,"01-搜索命中高亮-关闭后键盘收起.png").outputStream().use {screenshot.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)};screenshot.recycle()
            }
            check(allNodes().first {it.isEditable}.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT,Bundle().apply {putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,"")}))
            click("搜索设置与功能")
            waitFor("empty search keyboard open"){ui {keyboard()?.let {(it.listener as? KongyeIME)?.isInputViewShown}==true}}
            click("输入设置")
            waitFor("main group hides search IME"){ui {keyboard()?.let {(it.listener as? KongyeIME)?.isInputViewShown}!=true}}
            if(allNodes().none {it.text?.toString()=="中文与拼音"})click("输入设置")
            waitFor("input group expanded"){allNodes().any {it.text?.toString()=="中文与拼音"}}
            click("按键与手势");click("26 键上下滑")
            ui {prefs.edit().putBoolean(Prefs.SWIPE,true).putBoolean(V3Prefs.SWIPE_REVERSE,false).apply()}
            waitFor("normal direction labels"){allNodes().any {it.text?.toString()=="下滑 · 右上角"}}
            click("反转上下滑方向")
            waitFor("reversed direction labels"){allNodes().any {it.text?.toString()=="上滑 · 右上角"}&&allNodes().any {it.text?.toString()=="下滑 · 左下角"}}
            val reversedShot=auto.takeScreenshot()
            if(reversedShot!=null) {
                val folder=java.io.File(ctx.getExternalFilesDir(null),"search-highlight-pictures").apply {mkdirs()}
                java.io.File(folder,"02-反转仅改方向-角标固定.png").outputStream().use {reversedShot.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)};reversedShot.recycle()
            }
        } finally {
            ui {val e=prefs.edit().clear();backup.forEach {(k,v)->when(v){is Boolean->e.putBoolean(k,v);is Int->e.putInt(k,v);is Long->e.putLong(k,v);is Float->e.putFloat(k,v);is String->e.putString(k,v);is Set<*> -> @Suppress("UNCHECKED_CAST")e.putStringSet(k,v as Set<String>)}};e.commit()}
        }
        i.sendStatus(0,Bundle().apply {putString("stream","SETTINGS LIVE KEYBOARD / CHINESE INPUT / MULTILINE DELETE / BEFORE-AFTER / PAGE RESTORE / CLOSE WITH PREVIOUS IME OPEN / MAIN GROUP HIDES IME / REVERSE LABELS PASSED\n")})
    }
}
