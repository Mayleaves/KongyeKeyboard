package com.kongye.keyboard

import android.app.Instrumentation
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Bundle
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.inspector.WindowInspector
import java.io.File

object ClipSuggestionRegression {
    fun run(i:Instrumentation) {
        val ctx=i.targetContext
        val auto=i.getUiAutomation(android.app.UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        auto.serviceInfo=auto.serviceInfo.apply {flags=flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS}
        @Suppress("UNCHECKED_CAST") fun <T> ui(block:()->T):T {var result:Any?=null;var error:Throwable?=null;i.runOnMainSync{try{result=block()}catch(t:Throwable){error=t}};error?.let {throw it};return result as T}
        val d=ctx.resources.displayMetrics.density
        val short="你好"
        val long="里面的一些离线模型，词库之类的，软件设置和自定义按键都可以备份。".repeat(8)
        ui {
            for(scale in listOf(1f,1.3f))for(widthDp in listOf(240,392,700)) {
                val bar=CandidateBar(ctx).apply {uiScale=scale}
                bar.measure(View.MeasureSpec.makeMeasureSpec((widthDp*d).toInt(),View.MeasureSpec.EXACTLY),0)
                bar.layout(0,0,bar.measuredWidth,bar.measuredHeight)
                fun span(value:String):IntRange {
                    bar.showClipSuggestion(value)
                    val image=Bitmap.createBitmap(bar.width,bar.height,Bitmap.Config.ARGB_8888)
                    val canvas=Canvas(image);canvas.drawColor(bar.skin.kbBg);bar.draw(canvas)
                    val xs=(0 until image.width).filter {image.getPixel(it,image.height/2)==bar.skin.keyBg}
                    check(xs.isNotEmpty());val range=xs.first()..xs.last();image.recycle()
                    check(kotlin.math.abs(range.first+range.last-bar.width)<=3) {"Clipboard pill is not centered"}
                    check(range.first>44*d*scale&&range.last<bar.width-40*d*scale) {"Clipboard pill overlaps actions"}
                    return range
                }
                check(span(short).count()<span(long).count()) {"Short clipboard pill did not shrink"}
            }
        }
        fun descendants(v:View):List<View> = listOf(v)+(if(v is ViewGroup)(0 until v.childCount).flatMap {descendants(v.getChildAt(it))} else emptyList())
        fun views()=WindowInspector.getGlobalWindowViews().flatMap {descendants(it)}
        android.os.ParcelFileDescriptor.AutoCloseInputStream(auto.executeShellCommand("am start -W -f 0x10008000 -n com.kongye.keyboard/.SettingsActivity")).use {it.readBytes()}
        SystemClock.sleep(1600)
        fun nodes(n:android.view.accessibility.AccessibilityNodeInfo?):List<android.view.accessibility.AccessibilityNodeInfo> = if(n==null)emptyList()else listOf(n)+(0 until n.childCount).flatMap {nodes(n.getChild(it))}
        var show:android.view.accessibility.AccessibilityNodeInfo?=null
        val showEnd=SystemClock.uptimeMillis()+30000
        while(show==null&&SystemClock.uptimeMillis()<showEnd){auto.clearCache();show=(auto.windows.flatMap {nodes(it.root)}+nodes(auto.rootInActiveWindow)).firstOrNull {it.text?.toString()=="显示键盘"};if(show==null)SystemClock.sleep(100)}
        val button=android.graphics.Rect();checkNotNull(show) {"Settings keyboard preview button did not appear"}.getBoundsInScreen(button)
        val down=SystemClock.uptimeMillis()
        for(action in intArrayOf(0,1)){val event=MotionEvent.obtain(down,SystemClock.uptimeMillis(),action,button.exactCenterX(),button.exactCenterY(),0);event.source=android.view.InputDevice.SOURCE_TOUCHSCREEN;try{check(auto.injectInputEvent(event,true)) {"Unable to touch preview button"}}finally{event.recycle()}}
        var bar:CandidateBar?=null
        val end=SystemClock.uptimeMillis()+20000
        while(SystemClock.uptimeMillis()<end&&bar==null){bar=ui {views().filterIsInstance<CandidateBar>().firstOrNull {it.isShown&&it.listener is KongyeIME}};SystemClock.sleep(100)}
        val live=checkNotNull(bar)
        val original=ui {live.listener}
        val events=java.util.Collections.synchronizedList(ArrayList<String>())
        val stub=object:CandidateBar.Listener {
            override fun onCandidate(index:Int){};override fun onAssoc(index:Int){};override fun onTool(id:String){};override fun onExpand(){};override fun onDismissAssoc(){}
            override fun onClipPaste(){events.add("paste")};override fun onClipSplit(){events.add("split")};override fun onClipDismiss(){events.add("close")}
        }
        fun tap(x:Float) {
            val pos=IntArray(2);val y=ui {live.getLocationOnScreen(pos);live.height/2f}
            val start=SystemClock.uptimeMillis()
            for(action in intArrayOf(0,1)){val event=MotionEvent.obtain(start,SystemClock.uptimeMillis(),action,pos[0]+x,pos[1]+y,0);event.source=android.view.InputDevice.SOURCE_TOUCHSCREEN;try{check(auto.injectInputEvent(event,true))}finally{event.recycle()}}
            SystemClock.sleep(100)
        }
        val pictures=File(ctx.getExternalFilesDir(null),"clip-suggestion-pictures").apply {mkdirs()}
        fun capture(name:String){SystemClock.sleep(250);auto.takeScreenshot().also {b->File(pictures,name+".png").outputStream().use {b.compress(Bitmap.CompressFormat.PNG,100,it)};b.recycle()}}
        try {
            ui {live.listener=stub;live.showClipSuggestion(short)}
            capture("01-短内容-白框居中缩短")
            tap(22*d);check(events.toList()==listOf("split")) {"Left touch: "+events}
            tap(live.width/2f);check(events.lastOrNull()=="paste") {"Center touch: "+events}
            tap(live.width-20*d);check(events.lastOrNull()=="close") {"Right touch: "+events}
            val count=events.size;tap(65*d);check(events.size==count) {"Blank margin pasted content"}
            ui {live.showClipSuggestion(long)}
            capture("02-长内容-白框居中省略")
            tap(live.width/2f);check(events.lastOrNull()=="paste") {"Center touch: "+events}
        } finally {ui {live.listener=original;live.showTools(Prefs.tools(ctx))}}
        auto.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
        i.sendStatus(0,Bundle().apply {putString("stream","CLIP SUGGESTION PASSED: centered content-sized pill across narrow/wide and scaled layouts; left split, center paste, right close callbacks; blank margins inert; real Sony keyboard screenshots.\n")})
    }
}
