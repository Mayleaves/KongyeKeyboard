package com.kongye.keyboard

import android.app.Activity
import android.app.Instrumentation
import android.os.Bundle
import android.os.SystemClock
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo as Node

object HomeSearchBackRegression {
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
        fun tree(n:Node?):List<Node> = if(n==null)emptyList()else listOf(n)+(0 until n.childCount).flatMap {tree(n.getChild(it))}
        fun nodes():List<Node>{auto.clearCache();return auto.windows.flatMap {tree(it.root)}}
        fun waitFor(label:String,check:()->Boolean){val end=SystemClock.uptimeMillis()+20000;while(SystemClock.uptimeMillis()<end){if(check())return;SystemClock.sleep(60)};error("Timed out: $label")}
        fun has(t:String)=nodes().any {it.text?.toString()==t && it.isVisibleToUser}
        fun shell(s:String)=android.os.ParcelFileDescriptor.AutoCloseInputStream(auto.executeShellCommand(s)).use {it.readBytes()}
        fun search(value:String,keyboard:Boolean) {
            val field=nodes().first {it.isEditable && it.isVisibleToUser}
            field.performAction(Node.ACTION_CLICK)
            check(field.performAction(Node.ACTION_SET_TEXT,Bundle().apply {putCharSequence(Node.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,value)}))
            waitFor("search text"){nodes().any {it.isEditable&&it.text?.toString()==value}}
            if(keyboard)waitFor("IME visible") {current.get()?.window?.decorView?.rootWindowInsets?.isVisible(android.view.WindowInsets.Type.ime())==true}
        }
        fun gesture(right:Boolean=false,cancel:Boolean=false) {
            val dm=i.targetContext.resources.displayMetrics
            val start=SystemClock.uptimeMillis();val edge=if(right)dm.widthPixels-1f else 1f
            fun touch(action:Int,x:Float) {val e=MotionEvent.obtain(start,SystemClock.uptimeMillis(),action,x,dm.heightPixels*.35f,0);e.source=android.view.InputDevice.SOURCE_TOUCHSCREEN;check(auto.injectInputEvent(e,true));e.recycle()}
            touch(MotionEvent.ACTION_DOWN,edge)
            repeat(16) {step->SystemClock.sleep(18);touch(MotionEvent.ACTION_MOVE,edge+(if(right)-1 else 1)*dm.widthPixels*.36f*(step+1)/16)}
            if(cancel)repeat(16) {step->SystemClock.sleep(18);touch(MotionEvent.ACTION_MOVE,edge+(if(right)-1 else 1)*dm.widthPixels*.36f*(15-step)/16)}
            touch(MotionEvent.ACTION_UP,if(cancel)edge else edge+(if(right)-1 else 1)*dm.widthPixels*.36f)
            SystemClock.sleep(300)
        }
        try {
            shell("cmd overlay enable --user current com.android.internal.systemui.navbar.gestural")
            shell("am start -W -f 0x10008000 -n com.kongye.keyboard/.SettingsActivity")
            waitFor("home"){has("今日输入")&&nodes().any {it.isEditable}}
            waitFor("intro dismissed"){nodes().none {it.contentDescription?.toString()=="开屏动画"}}
            search("拼音",true)
            gesture(cancel=true)
            check(nodes().any {it.isEditable&&it.text?.toString()=="拼音"}) {"Cancelled gesture cleared search"}
            gesture()
            waitFor("full home after IME gesture"){has("启用输入法")&&nodes().any {it.isEditable&&it.text.isNullOrEmpty()}}
            check(current.get()?.isFinishing==false)
            search("模糊音",true)
            var n=nodes().first {it.text?.toString()=="模糊音"&&!it.isEditable}
            repeat(4){if(!n.performAction(Node.ACTION_CLICK))n=n.parent ?: n}
            waitFor("fuzzy page"){has("模糊音")&&nodes().none {it.isEditable}}
            gesture(right=true)
            waitFor("search results after child back"){nodes().any {it.isEditable&&it.text?.toString()=="模糊音"}}
            shell("input keyevent 4")
            waitFor("full home after back key"){has("启用输入法")&&nodes().any {it.isEditable&&it.text.isNullOrEmpty()}}
            i.sendStatus(0,Bundle().apply {putString("stream","HOME SEARCH BACK PASSED: one gesture with IME visible restores complete home; cancelled gesture preserved query; child returns to results; back key exits search\n")})
        } finally {app.unregisterActivityLifecycleCallbacks(callback)}
    }
}
