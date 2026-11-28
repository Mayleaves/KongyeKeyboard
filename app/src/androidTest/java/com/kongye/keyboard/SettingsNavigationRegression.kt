package com.kongye.keyboard

import android.app.Activity
import android.app.Instrumentation
import android.graphics.Rect
import android.os.Bundle
import android.os.SystemClock
import android.view.WindowInsets
import android.view.accessibility.AccessibilityNodeInfo as Node
import java.io.File

/** Real focused search -> menu navigation, sampled while the IME closes. */
object SettingsNavigationRegression {
    fun run(i:Instrumentation) {
        check(android.os.Build.MODEL.startsWith("sdk_"))
        val app=i.targetContext.applicationContext as android.app.Application
        val current=java.util.concurrent.atomic.AtomicReference<SettingsActivity>()
        val callback=object:android.app.Application.ActivityLifecycleCallbacks {
            override fun onActivityResumed(a:Activity){if(a is SettingsActivity)current.set(a)}
            override fun onActivityCreated(a:Activity,b:Bundle?){};override fun onActivityStarted(a:Activity){}
            override fun onActivityPaused(a:Activity){};override fun onActivityStopped(a:Activity){}
            override fun onActivitySaveInstanceState(a:Activity,b:Bundle){};override fun onActivityDestroyed(a:Activity){}
        }
        app.registerActivityLifecycleCallbacks(callback)
        val auto=i.getUiAutomation(android.app.UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        auto.serviceInfo=auto.serviceInfo.apply {flags=flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS}
        fun tree(n:Node?):List<Node> = if(n==null)emptyList() else listOf(n)+(0 until n.childCount).flatMap {tree(n.getChild(it))}
        fun nodes():List<Node>{auto.clearCache();return auto.windows.flatMap {tree(it.root)}.filter {it.packageName?.toString()==i.targetContext.packageName&&it.isVisibleToUser}}
        fun waitFor(label:String,test:()->Boolean){val end=SystemClock.uptimeMillis()+20000;while(SystemClock.uptimeMillis()<end){if(test())return;SystemClock.sleep(60)};error("Timed out: $label")}
        fun shell(s:String)=android.os.ParcelFileDescriptor.AutoCloseInputStream(auto.executeShellCommand(s)).use {it.readBytes()}
        fun imeBottom():Int {var value=0;i.runOnMainSync {value=current.get()?.window?.decorView?.rootWindowInsets?.getInsets(WindowInsets.Type.ime())?.bottom ?: 0};return value}
        fun click(node:Node){var n:Node?=node;repeat(8){if(n?.performAction(Node.ACTION_CLICK)==true)return;n=n?.parent};error("Cannot click ${node.text}")}
        val folder=File(i.targetContext.getExternalFilesDir(null),"settings-navigation").apply {mkdirs()}
        val traces=org.json.JSONArray()
        fun home(){shell("am start -W -f 0x10008000 -n com.kongye.keyboard/.SettingsActivity");waitFor("home"){nodes().any {it.isEditable}}}
        fun search(text:String) {
            waitFor("search visible"){
                val ns=nodes();if(ns.any {it.isEditable})true else {ns.firstOrNull {it.isScrollable}?.performAction(Node.ACTION_SCROLL_BACKWARD);false}
            }
            val field=nodes().first {it.isEditable};click(field)
            check(field.performAction(Node.ACTION_SET_TEXT,Bundle().apply {putCharSequence(Node.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,text)}))
            waitFor("IME shown"){imeBottom()>0}
        }
        fun transition(label:String,case:String) {
            waitFor(label){
                val ns=nodes()
                if(ns.any {it.text?.toString()==label&&!it.isEditable})true else {ns.firstOrNull {it.isScrollable}?.performAction(Node.ACTION_SCROLL_FORWARD);false}
            }
            click(nodes().first {it.text?.toString()==label&&!it.isEditable})
            val samples=org.json.JSONArray();val start=SystemClock.uptimeMillis()
            while(SystemClock.uptimeMillis()-start<2500) {
                val ns=nodes()
                if(ns.none {it.isEditable})ns.firstOrNull {it.text?.toString()==label}?.let {n->
                    val rect=Rect();n.getBoundsInScreen(rect)
                    samples.put(org.json.JSONObject().put("ms",SystemClock.uptimeMillis()-start).put("top",rect.top).put("imeBottom",imeBottom()))
                }
                SystemClock.sleep(16)
            }
            val trace=org.json.JSONObject().put("case",case).put("samples",samples);traces.put(trace)
            File(folder,"trace.json").writeText(traces.toString(2))
            auto.takeScreenshot()?.let {b->File(folder,"$case.png").outputStream().use {b.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)};b.recycle()}
            check(samples.length()>0) {"No destination samples: $case; visible="+nodes().map {it.text?.toString().orEmpty()}.filter {it.isNotEmpty()}}
            val top=samples.getJSONObject(samples.length()-1).getInt("top")
            check((0 until samples.length()).all {samples.getJSONObject(it).getInt("imeBottom")==0}) {"Destination entered before search IME settled: $case"}
            check((0 until samples.length()).all {kotlin.math.abs(samples.getJSONObject(it).getInt("top")-top)<=2}) {"Destination moved vertically: $case"}
            check(imeBottom()==0)
        }
        try {
            home();search("")
            transition("中文与拼音","empty-search-pinyin")
            shell("input keyevent 4");home();search("模糊音")
            transition("模糊音","filtered-search-fuzzy")
            shell("input keyevent 4")
            waitFor("search preserved"){nodes().any {it.isEditable&&it.text?.toString()=="模糊音"}}
            SystemClock.sleep(350);check(imeBottom()==0) {"Returning restored search focus and reopened IME"}
            val field=nodes().first {it.isEditable}
            check(field.performAction(Node.ACTION_SET_TEXT,Bundle().apply {putCharSequence(Node.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,"")}))
            i.sendStatus(0,Bundle().apply {putString("stream","SETTINGS NAVIGATION PASSED: empty and filtered search; destination after IME settles; stable header top; query preserved without reopening keyboard\n")})
        } finally {app.unregisterActivityLifecycleCallbacks(callback)}
    }
}
