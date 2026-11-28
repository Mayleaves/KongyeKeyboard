package com.kongye.keyboard

import android.app.Instrumentation
import android.content.Intent
import android.graphics.RectF
import android.os.Bundle
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.inspector.WindowInspector
import android.widget.FrameLayout
import android.widget.TextView
import java.io.File

/** Real child dispatch plus injected gestures across the production IME's window regions. */
object HandwritingTouchRegression {
    fun run(i:Instrumentation) {
        check(android.os.Build.MODEL.startsWith("sdk_"))
        val ctx=i.targetContext
        @Suppress("UNCHECKED_CAST") fun <T> ui(block:()->T):T {
            var result:Any?=null;var error:Throwable?=null
            i.runOnMainSync {try {result=block()}catch(t:Throwable){error=t}}
            error?.let {throw it};return result as T
        }
        fun waitFor(label:String,test:()->Boolean) {
            val end=SystemClock.uptimeMillis()+30000
            while(SystemClock.uptimeMillis()<end) {if(ui(test))return;SystemClock.sleep(50)}
            error("Timed out: $label")
        }
        fun all(v:View):List<View> = listOf(v)+(if(v is ViewGroup)(0 until v.childCount).flatMap {all(v.getChildAt(it))}else emptyList())
        fun views()=WindowInspector.getGlobalWindowViews().flatMap(::all)
        fun report(text:String)=i.sendStatus(0,Bundle().apply {putString("stream",text+"\n")})
        val side=HandwriteView::class.java.getDeclaredMethod("sideRect",Int::class.javaPrimitiveType).apply {isAccessible=true}
        fun tool(pad:HandwriteView,n:Int)=side.invoke(pad,n) as RectF
        val activity=i.startActivitySync(Intent(ctx,SettingsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        var clicks=0;var cancelled=0;val actions=mutableListOf<String>()
        lateinit var pad:HandwriteView
        lateinit var toolbar:TextView
        val host=ui {object:FrameLayout(activity) {
            val route=HandwritingTouchRouter(this,{pad},{toolbar})
            override fun dispatchTouchEvent(e:MotionEvent)=route.dispatch(e){super.dispatchTouchEvent(it)}
        }}
        val d=ctx.resources.displayMetrics.density
        ui {
            toolbar=object:TextView(activity) {
                override fun onTouchEvent(e:MotionEvent):Boolean {if(e.actionMasked==MotionEvent.ACTION_CANCEL)cancelled++;return super.onTouchEvent(e)}
            }.apply {text="手写工具栏";setOnClickListener {clicks++}}
            pad=HandwriteView(activity,Skins.KONGYE,{}, {actions.add(it)})
            host.addView(toolbar,FrameLayout.LayoutParams(-1,(56*d).toInt()))
            host.addView(pad,FrameLayout.LayoutParams(-1,(300*d).toInt()).apply {topMargin=(56*d).toInt()})
            activity.setContentView(host)
        }
        i.waitForIdleSync()
        fun localPoint(v:View,x:Float,y:Float):Pair<Float,Float> {
            val at=IntArray(2);val root=IntArray(2);v.getLocationOnScreen(at);host.getLocationOnScreen(root)
            return at[0]-root[0]+x*v.scaleX to at[1]-root[1]+y*v.scaleY
        }
        fun dispatch(points:List<Pair<Float,Float>>,cancel:Boolean=false) {
            val time=SystemClock.uptimeMillis()
            points.forEachIndexed {n,(x,y)->
                val action=if(n==0)MotionEvent.ACTION_DOWN else if(n==points.lastIndex) {
                    if(cancel)MotionEvent.ACTION_CANCEL else MotionEvent.ACTION_UP
                } else MotionEvent.ACTION_MOVE
                val event=MotionEvent.obtain(time,time+n*16,action,x,y,0)
                try {check(host.dispatchTouchEvent(event))}finally {event.recycle()}
            }
        }
        ui {
            val r=pad.writingBounds()
            check(r.contains(tool(pad,5))) {"Full-screen button must be inside the pad's upper-left corner"}
            check(tool(pad,0).left>=r.right && tool(pad,1).left>=r.right) {"Undo/redo must be in the right rail"}
            val rail=listOf(2,0,1,3,4).map {tool(pad,it)}
            check(rail.zipWithNext().all {(a,b)->b.top-a.bottom>=5*d}) {"Right-rail tools must have separate, spaced hit regions"}
            check(rail.all {kotlin.math.abs(it.height()-rail.first().height())<1f}) {"Right-rail buttons must have equal height"}
            val inside=localPoint(pad,r.centerX(),r.centerY())
            val end=localPoint(pad,r.centerX()+30*d,r.centerY()+20*d)
            dispatch(listOf(localPoint(pad,1f,r.centerY()),inside,end))
            check(pad.inkStrokes().size==1 && pad.canUndo)
            pad.clear()
            dispatch(listOf(localPoint(pad,r.centerX(),pad.height-2*d),inside,end))
            check(pad.inkStrokes().size==1)
            pad.clear()
            dispatch(listOf(localPoint(toolbar,toolbar.width/2f,toolbar.height/2f),inside,end))
            check(pad.inkStrokes().size==1 && clicks==0 && cancelled==1)
            check(pad.inkStrokes().single().all {it[0]>=0 && it[1]>=0})
            val undo=tool(pad,0);val redo=tool(pad,1)
            dispatch(List(2){localPoint(pad,undo.centerX(),undo.centerY())});check(pad.empty && pad.canRedo)
            dispatch(List(2){localPoint(pad,redo.centerX(),redo.centerY())});check(pad.canUndo && !pad.canRedo)
            pad.clear()
            val del=tool(pad,2)
            dispatch(listOf(localPoint(pad,del.centerX(),del.centerY()),inside,end))
            check(pad.inkStrokes().size==1 && actions.isEmpty())
            pad.clear();dispatch(List(2){localPoint(pad,del.centerX(),del.centerY())});check(actions==listOf("del"))
            dispatch(listOf(localPoint(toolbar,10*d,10*d),inside,end),cancel=true)
            check(pad.empty && !pad.canUndo)
            // Coordinates must remain correct for scaled/floating view hierarchies.
            pad.pivotX=0f;pad.pivotY=0f;pad.scaleX=.8f;pad.scaleY=.8f
            dispatch(listOf(localPoint(toolbar,10*d,10*d),localPoint(pad,r.centerX(),r.centerY()),localPoint(pad,r.centerX()+10*d,r.centerY()+10*d)))
            val first=pad.inkStrokes().single().first()
            check(kotlin.math.abs(first[0]-(r.centerX()-r.left))<2 && kotlin.math.abs(first[1]-(r.centerY()-r.top))<2)
            pad.clear()
        }
        ui {dispatch(List(2){localPoint(toolbar,toolbar.width/2f,toolbar.height/2f)})}
        i.waitForIdleSync();check(ui {clicks==1}) {"Ordinary toolbar click was lost"}
        report("HANDWRITING DISPATCH PASSED: margin/blank/tool/toolbar entry, cancelled source click, undo/redo, cancellation and scaled coordinates")
        ui {activity.finish()}

        val prefs=Prefs.of(ctx);val backup=prefs.all.toMap()
        val auto=i.getUiAutomation(android.app.UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        auto.serviceInfo=auto.serviceInfo.apply {flags=flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS}
        var ime:KongyeIME?=null
        fun shell(command:String)=android.os.ParcelFileDescriptor.AutoCloseInputStream(auto.executeShellCommand(command)).use {it.readBytes()}
        fun screen(v:View,x:Float,y:Float):Pair<Float,Float> {
            val at=IntArray(2);v.getLocationOnScreen(at);return at[0]+x to at[1]+y
        }
        fun gesture(points:List<Pair<Float,Float>>) {
            val time=SystemClock.uptimeMillis()
            points.forEachIndexed {n,(x,y)->
                val action=if(n==0)MotionEvent.ACTION_DOWN else if(n==points.lastIndex)MotionEvent.ACTION_UP else MotionEvent.ACTION_MOVE
                val e=MotionEvent.obtain(time,SystemClock.uptimeMillis(),action,x,y,0).apply {source=android.view.InputDevice.SOURCE_TOUCHSCREEN}
                try {check(auto.injectInputEvent(e,true))}finally {e.recycle()}
                SystemClock.sleep(35)
            }
        }
        fun screenshot(name:String) {
            val image=checkNotNull(auto.takeScreenshot())
            File(ctx.getExternalFilesDir(null),"handwriting-$name.png").outputStream().use {image.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)}
            image.recycle()
        }
        try {
            ui {prefs.edit().putBoolean(Prefs.FLOAT,false).putBoolean(Prefs.LEARN,false).putBoolean(Prefs.VAULT,false).putBoolean(V3Prefs.STATS_ENABLED,false).commit()}
            shell("am start -W -f 0x10008000 -n com.kongye.keyboard.test/com.kongye.keyboard.TypingFormActivity --ez show_keyboard true --ez bottom true")
            SystemClock.sleep(1000)
            waitFor("production keyboard") {views().filterIsInstance<KeyboardView>().any {it.isShown && !it.preview && it.listener is KongyeIME}}
            val live=ui {views().filterIsInstance<KeyboardView>().first {it.isShown && !it.preview}.listener as KongyeIME};ime=live
            ui {live.onTool("handwrite")}
            waitFor("handwriting panel") {views().filterIsInstance<HandwriteView>().any {it.isShown}}
            val writing=ui {views().filterIsInstance<HandwriteView>().first {it.isShown}}
            SystemClock.sleep(400)
            val height=ui {writing.height}
            val bar=ui {views().filterIsInstance<CandidateBar>().first {it.isShown && it.listener===live}}
            fun reservedTop():Int {
                val insets=android.inputmethodservice.InputMethodService.Insets();live.onComputeInsets(insets)
                check(insets.contentTopInsets==insets.visibleTopInsets)
                return insets.contentTopInsets
            }
            val normalReservedTop=ui {reservedTop()}
            fun checkCompactReservation() {
                ui {
                    val fullBar=views().filterIsInstance<CandidateBar>().single {it.isShown && it.listener===live}
                    val at=IntArray(2);fullBar.getLocationInWindow(at)
                    check(reservedTop()==at[1]) {"Full-screen handwriting reserves the hidden normal keyboard instead of the visible controls"}
                    check(reservedTop()>normalReservedTop+40*d) {"Full-screen handwriting did not release normal keyboard space"}
                }
            }
            val start=ui {screen(bar,bar.width/2f,bar.height/2f)}
            val middle=ui {val r=writing.writingBounds();screen(writing,r.centerX(),r.top+30*d)}
            val end=ui {val r=writing.writingBounds();screen(writing,r.centerX()+30*d,r.top+80*d)}
            gesture(listOf(start,middle,end))
            waitFor("toolbar entry ink") {writing.canUndo && writing.inkStrokes().size==1}
            ui {
                val stroke=writing.inkStrokes().single()
                check(stroke.size>=2 && kotlin.math.abs(stroke.last()[1]-stroke.first()[1])>20*d) {"Toolbar entry lost its moving stroke: ${stroke.map {it.take(2)}}"}
            }
            screenshot("toolbar-entry")
            ui {writing.clear()}
            val expand=ui {val r=tool(writing,5);screen(writing,r.centerX(),r.centerY())}
            gesture(listOf(expand,expand))
            waitFor("full screen") {writing.fullScreen && writing.isShown && writing.height>height*1.4}
            ui {
                val controls=views().filterIsInstance<FullHandwritingControls>().single {it.isShown}
                val row=all(controls).filter {it.contentDescription?.toString() in listOf("收回全屏手写","撤销笔画","重做笔画","空格","删除","回车")}
                check(row.size==6 && row.all {it.height>0})
                check(row.maxOf {it.width}-row.minOf {it.width}<=1) {"Full-screen action buttons must divide the row equally"}
                check(controls.height<64*d && writing.writingBounds().right>writing.width-10*d)
                check(writing.background is android.graphics.drawable.ColorDrawable)
                check(android.graphics.Color.alpha((writing.background as android.graphics.drawable.ColorDrawable).color)==48)
            }
            SystemClock.sleep(500)
            checkCompactReservation()
            screenshot("full-screen-reservation")
            // Check the editor in the other application's window, not just our reported insets.
            waitFor("bottom editor follows compact controls") {
                auto.clearCache()
                fun find(v:android.view.accessibility.AccessibilityNodeInfo?):android.view.accessibility.AccessibilityNodeInfo? {
                    if(v==null)return null
                    if(v.contentDescription?.toString()=="独立输入测试框")return v
                    for(n in 0 until v.childCount)find(v.getChild(n))?.let {return it}
                    return null
                }
                val field=auto.windows.firstNotNullOfOrNull {find(it.root)} ?: return@waitFor false
                val bounds=android.graphics.Rect();field.getBoundsInScreen(bounds)
                val fullBar=views().filterIsInstance<CandidateBar>().single {it.isShown && it.listener===live}
                val at=IntArray(2);fullBar.getLocationOnScreen(at)
                at[1]-bounds.bottom in 0..(40*d).toInt()
            }
            val upper=ui {screen(writing,writing.writingBounds().centerX(),70*d)}
            check(upper.second<start.second-50*d)
            gesture(listOf(upper,upper.first+35*d to upper.second+50*d,upper.first+60*d to upper.second+80*d))
            waitFor("ink above original keyboard") {writing.canUndo && writing.inkStrokes().size==1}
            ui {
                val insets=android.inputmethodservice.InputMethodService.Insets();live.onComputeInsets(insets)
                val at=IntArray(2);writing.getLocationInWindow(at)
                check(insets.touchableRegion.contains((at[0]+writing.writingBounds().centerX()).toInt(),(at[1]+70*d).toInt()))
            }
            screenshot("full-screen")
            waitFor("full screen candidates") {views().filterIsInstance<CandidateBar>().any {candidate->
                @Suppress("UNCHECKED_CAST") val words=CandidateBar::class.java.getDeclaredField("items").apply {isAccessible=true}.get(candidate) as List<CandItem>
                candidate.isShown && candidate.listener===live && words.isNotEmpty()
            }}
            val fullBar=ui {views().filterIsInstance<CandidateBar>().first {it.isShown && it.listener===live}}
            val pick=ui {screen(fullBar,32*d,fullBar.height/2f)}
            gesture(listOf(pick,pick))
            waitFor("full screen candidate commits") {writing.empty && live.currentInputConnection?.getTextBeforeCursor(10,0)?.isNotEmpty()==true}
            checkCompactReservation()
            gesture(listOf(upper,upper.first+35*d to upper.second+50*d,upper.first+60*d to upper.second+80*d))
            waitFor("new full screen ink") {writing.canUndo && writing.inkStrokes().size==1}
            val collapse=ui {val button=views().first {it.isShown && it.contentDescription=="收回全屏手写"};screen(button,button.width/2f,button.height/2f)}
            gesture(listOf(collapse,collapse))
            waitFor("collapsed with ink") {!writing.fullScreen && writing.isShown && writing.height==height && writing.canUndo}
            waitFor("normal keyboard reservation restored") {reservedTop()==normalReservedTop}
            ui {check(writing.inkStrokes().size==1);writing.undoStroke();check(writing.canRedo);writing.redoStroke();check(writing.canUndo)}
            screenshot("collapsed")
            ui {writing.clear()}
            SystemClock.sleep(500)
            // Cross-window drags remain owned by the application in normal mode.
            gesture(listOf(upper,middle,end))
            check(ui {writing.empty}) {"Normal mode unexpectedly captured application touches"}
            report("LIVE HANDWRITING PASSED: real toolbar entry; expand/collapse; compact full-screen reservation and bottom editor placement; above-keyboard ink in full-screen mode; ink and undo history retained; normal reservation and application gesture ownership restored after collapse")
        } finally {
            ui {
                ime?.onKey(Key(Kind.ABC))
                val edit=prefs.edit().clear()
                backup.forEach {(key,value)->when(value){is String->edit.putString(key,value);is Boolean->edit.putBoolean(key,value);is Int->edit.putInt(key,value);is Long->edit.putLong(key,value);is Float->edit.putFloat(key,value);is Set<*> -> @Suppress("UNCHECKED_CAST")edit.putStringSet(key,value as Set<String>)}}
                edit.commit()
            }
        }
    }
}
