package com.kongye.keyboard

import android.app.Instrumentation
import android.os.Bundle
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo as Node
import android.view.inspector.WindowInspector
import java.io.File

/** Captures only this app's settings, temporarily restoring factory flicks for the reference pictures. */
object SettingsNov17Pictures {
    fun run(i:Instrumentation) {
        val ctx=i.targetContext
        val auto=i.getUiAutomation(android.app.UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        auto.serviceInfo=auto.serviceInfo.apply {flags=flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS}
        fun descendants(n:Node?):List<Node> = if(n==null)emptyList() else listOf(n)+(0 until n.childCount).flatMap {descendants(n.getChild(it))}
        fun all():List<Node>{auto.clearCache();return auto.windows.flatMap {descendants(it.root)}}
        fun waitFor(name:String,f:()->Boolean){val end=SystemClock.uptimeMillis()+15000;while(SystemClock.uptimeMillis()<end){if(f())return;SystemClock.sleep(70)};error("Missing $name")}
        fun matches(n:Node,s:String)=n.text?.toString()==s||n.contentDescription?.toString()==s
        fun click(s:String) {
            var picked:Node?=null
            for(attempt in 0 until 14) {
                picked=all().firstOrNull {matches(it,s)&&it.isVisibleToUser&&!it.isEditable}
                if(picked!=null)break
                all().firstOrNull {it.isScrollable}?.performAction(Node.ACTION_SCROLL_FORWARD);SystemClock.sleep(150)
            }
            var n=checkNotNull(picked){"Missing click $s"}
            n.performAction(Node.AccessibilityAction.ACTION_SHOW_ON_SCREEN.id);SystemClock.sleep(150)
            repeat(5){if(n.performAction(Node.ACTION_CLICK)){SystemClock.sleep(300);return};n=n.parent?:error("Not clickable $s")}
            error("Not clickable $s")
        }
        val out=File(ctx.getExternalFilesDir(null),"nov17-pictures").apply {mkdirs()}
        fun capture(name:String) {
            SystemClock.sleep(500)
            val bmp=checkNotNull(auto.takeScreenshot());File(out,"$name.png").outputStream().use {bmp.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)};bmp.recycle()
            i.sendStatus(0,Bundle().apply {putString("stream","Captured $name\n")})
        }
        fun scroll(){all().firstOrNull {it.isScrollable}?.performAction(Node.ACTION_SCROLL_FORWARD);SystemClock.sleep(400)}
        fun home() {
            android.os.ParcelFileDescriptor.AutoCloseInputStream(auto.executeShellCommand("am start -W -f 0x10008000 -n com.kongye.keyboard/.SettingsActivity")).use {it.readBytes()}
            waitFor("home"){all().any {it.text?.toString()=="显示键盘"}};SystemClock.sleep(150)
        }
        fun search(q:String) {
            home();val n=all().first {it.isEditable}
            check(n.performAction(Node.ACTION_SET_TEXT,Bundle().apply {putCharSequence(Node.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,q)}));SystemClock.sleep(350)
        }
        fun viewTree(v:View):List<View> = listOf(v)+(if(v is ViewGroup)(0 until v.childCount).flatMap {viewTree(v.getChildAt(it))} else emptyList())
        fun ui(f:()->Unit)=i.runOnMainSync(f)
        val p=Prefs.of(ctx);val backup=p.all.toMap()
        try {
            ui {KeyGestures.reset(ctx,true);KeyGestures.reset(ctx,false);p.edit().putBoolean(Prefs.ZH,true).putBoolean(Prefs.SWIPE,true).putBoolean(Prefs.FLOAT,false).putBoolean(Prefs.LEARN,false).putBoolean(Prefs.ABBR,true).putBoolean(Prefs.VAULT,false).putBoolean(V3Prefs.SWIPE_REVERSE,false).putBoolean(V3Prefs.FEEDBACK,true).putString(Prefs.LAYOUT,"26").putInt(V3Prefs.PINYIN_POSITION,0).putInt(V3Prefs.ONE_HAND,0).commit()}
            home();click("输入设置");capture("01-首页菜单层级")
            search("简拼");click("中文与拼音");click("简拼");capture("02-修改前灰底-当前白底")
            home();click("输入设置");click("显示键盘")
            var live:KongyeIME?=null
            waitFor("IME") {ui {live=WindowInspector.getGlobalWindowViews().flatMap {viewTree(it)}.filterIsInstance<KeyboardView>().firstOrNull {it.isShown&&!it.preview}?.listener as? KongyeIME};live!=null}
            capture("03-居中输入-无提示文字")
            val close=all().first {it.contentDescription?.toString()=="收起键盘"&&it.className?.toString()=="android.widget.TextView"};check(close.performAction(Node.ACTION_CLICK));SystemClock.sleep(400)
        } finally {
            ui {val edit=p.edit().clear();backup.forEach {(k,v)->when(v){is Boolean->edit.putBoolean(k,v);is Int->edit.putInt(k,v);is Long->edit.putLong(k,v);is Float->edit.putFloat(k,v);is String->edit.putString(k,v);is Set<*> -> @Suppress("UNCHECKED_CAST")edit.putStringSet(k,v as Set<String>)}};edit.commit()}
        }
        i.sendStatus(0,Bundle().apply {putString("stream","NOV17 SETTINGS PICTURES PASSED\n")})
    }
}
