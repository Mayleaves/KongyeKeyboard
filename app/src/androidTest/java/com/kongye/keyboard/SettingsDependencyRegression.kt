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
object SettingsDependencyRegression {
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
        val out=File(ctx.getExternalFilesDir(null),"dependency-pictures").apply {mkdirs()}
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
            val isolated=object:android.content.ContextWrapper(ctx) {
                override fun getSharedPreferences(name:String,mode:Int)=super.getSharedPreferences("dependency-regression-"+name,mode)
            }
            val q=Prefs.of(isolated);q.edit().clear().putString(Prefs.LAYOUT,"26").putBoolean(V3Prefs.GLIDE_ZH,false).putBoolean(V3Prefs.GLIDE_EN,false).putBoolean(Prefs.GLIDE,false).putBoolean(V3Prefs.GLIDE_TRACE,true).commit()
            fun enabled(k:String)=SettingAvailability.forKey(isolated,k).enabled
            check(!enabled(V3Prefs.GLIDE_TRACE)&&!enabled(V3Prefs.GLIDE_LIVE)&&!enabled(V3Prefs.GLIDE_WORD_DELETE))
            q.edit().putBoolean(V3Prefs.GLIDE_ZH,true).putBoolean(Prefs.SWIPE,true).commit()
            check(enabled(V3Prefs.GLIDE_TRACE)&&enabled(V3Prefs.GLIDE_WORD_DELETE))
            check(SettingAvailability.forKey(isolated,V3Prefs.GLIDE_ZH).warning!=null&&SettingAvailability.forKey(isolated,Prefs.SWIPE).warning!=null)
            check(q.getBoolean(V3Prefs.GLIDE_TRACE,false))
            q.edit().putString(Prefs.LAYOUT,"14").commit()
            check(!enabled(V3Prefs.GLIDE_ZH)&&!enabled(V3Prefs.GLIDE_WORD_DELETE))
            q.edit().putBoolean(V3Prefs.GLIDE_EN,true).commit();check(enabled(V3Prefs.GLIDE_TRACE))
            q.edit().putBoolean(Prefs.SOUND,false).putBoolean(Prefs.ASSOC,false).putInt(V3Prefs.VIBRATION,2).putBoolean(Prefs.SWIPE,false).putBoolean(V3Prefs.ORIENTATION_LAYOUT,false).commit()
            check(!enabled(V3Prefs.SOUND_VOLUME)&&!enabled(V3Prefs.ASSOC_SPACE)&&!enabled(V3Prefs.ASSOC_CONTINUOUS)&&!enabled(Prefs.VIB_STRENGTH)&&!enabled(V3Prefs.SWIPE_REVERSE)&&!enabled(V3Prefs.LAYOUT_LANDSCAPE))
            q.edit().putBoolean(Prefs.SOUND,true).putBoolean(Prefs.ASSOC,true).putInt(V3Prefs.VIBRATION,1).putBoolean(Prefs.SWIPE,true).putBoolean(V3Prefs.ORIENTATION_LAYOUT,true).putBoolean(V3Prefs.CORRECT_NEAR,false).commit()
            check(enabled(V3Prefs.SOUND_VOLUME)&&enabled(V3Prefs.ASSOC_SPACE)&&enabled(Prefs.VIB_STRENGTH)&&enabled(V3Prefs.SWIPE_REVERSE)&&enabled(V3Prefs.LAYOUT_LANDSCAPE))
            check(enabled(V3Prefs.CORRECT_SWAP)&&enabled(V3Prefs.CORRECT_EXTRA)&&enabled(V3Prefs.CORRECT_MISSING))
            q.edit().clear().commit()
            ui {p.edit().putBoolean(Prefs.ZH,true).putString(Prefs.LAYOUT,"26").putBoolean(V3Prefs.ORIENTATION_LAYOUT,false).putBoolean(Prefs.FLOAT,false).putBoolean(Prefs.VAULT,false).putBoolean(Prefs.GLIDE,false).putBoolean(V3Prefs.GLIDE_ZH,false).putBoolean(V3Prefs.GLIDE_EN,false).putBoolean(V3Prefs.GLIDE_TRACE,true).putBoolean(V3Prefs.GLIDE_LIVE,true).putBoolean(Prefs.SWIPE,true).putBoolean(V3Prefs.CORRECT_NEAR,true).commit()}
            search("滑行");click("滑行输入");capture("03-滑行关闭-依赖项置灰")
            val node=all().first {it.text?.toString()=="显示滑行轨迹"};val b=android.graphics.Rect();node.getBoundsInScreen(b)
            val now=SystemClock.uptimeMillis()
            for(a in intArrayOf(0,1)){val e=android.view.MotionEvent.obtain(now,SystemClock.uptimeMillis(),a,b.exactCenterX(),b.exactCenterY(),0);e.source=android.view.InputDevice.SOURCE_TOUCHSCREEN;try{check(auto.injectInputEvent(e,true))}finally{e.recycle()}}
            SystemClock.sleep(250);check(p.getBoolean(V3Prefs.GLIDE_TRACE,false)){"Disabled option changed its saved preference"}
            click("中文滑行");waitFor("enabled child"){SettingAvailability.forKey(ctx,V3Prefs.GLIDE_TRACE).enabled}
            waitFor("live conflict hint") {all().any {it.text?.toString()?.startsWith("上下滑与滑行已同时开启")==true} && all().none {it.text?.toString()?.startsWith("请先开启「中文滑行」或「英文滑行」")==true}}
            check(p.getBoolean(V3Prefs.GLIDE_TRACE,false));capture("04-上下滑与滑行-红色提示")
            search("拼音纠错");click("拼音纠错");click("邻键纠错");capture("05-修改前拼音气泡-灰底")
        } finally {
            ui {val edit=p.edit().clear();backup.forEach {(k,v)->when(v){is Boolean->edit.putBoolean(k,v);is Int->edit.putInt(k,v);is Long->edit.putLong(k,v);is Float->edit.putFloat(k,v);is String->edit.putString(k,v);is Set<*> -> @Suppress("UNCHECKED_CAST")edit.putStringSet(k,v as Set<String>)}};edit.commit()}
        }
        i.sendStatus(0,Bundle().apply {putString("stream","DEPENDENCIES PASSED: parent gating, settings preserved while unavailable, disabled touch ignored, recovery, gesture priority warnings, independent correction flags and actual previews\n")})
    }
}
