package com.kongye.keyboard

import android.app.Instrumentation
import android.os.Bundle
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import android.view.inspector.WindowInspector
import java.io.File

/** UI behavior and pictures are checked through the production settings/IME.
 * Only the temporary statistics singleton receives the synthetic text. */
object FeedbackControlsRegression {
    fun run(i:Instrumentation) {
        val ctx=i.targetContext
        val auto=i.getUiAutomation(android.app.UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        auto.serviceInfo=auto.serviceInfo.apply {flags=flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS}
        fun report(t:String)=i.sendStatus(0,Bundle().apply {putString("stream",t+"\n")})
        fun nodes(n:AccessibilityNodeInfo?):List<AccessibilityNodeInfo> = if(n==null)emptyList() else listOf(n)+(0 until n.childCount).flatMap {nodes(n.getChild(it))}
        fun all():List<AccessibilityNodeInfo>{auto.clearCache();return auto.windows.flatMap {nodes(it.root)}}
        fun waitFor(name:String,check:()->Boolean){val end=SystemClock.uptimeMillis()+20000;while(SystemClock.uptimeMillis()<end){if(check())return;SystemClock.sleep(70)};error("Timed out: $name")}
        fun has(t:String)=all().any {it.text?.toString()==t || it.contentDescription?.toString()==t}
        fun click(t:String,scroll:Boolean=true) {
            var n:AccessibilityNodeInfo?=null
            for(attempt in 0 until if(scroll)12 else 1) {
                n=all().firstOrNull {
                    val b=android.graphics.Rect();it.getBoundsInScreen(b)
                    (it.text?.toString()==t||it.contentDescription?.toString()==t)&&it.isVisibleToUser&&b.width()>0&&b.height()>0
                }
                if(n!=null)break
                all().firstOrNull {it.isScrollable}?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
                SystemClock.sleep(170)
            }
            val node=checkNotNull(n){"Missing $t"}
            node.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SHOW_ON_SCREEN.id)
            SystemClock.sleep(100)
            var action:AccessibilityNodeInfo?=node
            repeat(5) {
                if(action?.performAction(AccessibilityNodeInfo.ACTION_CLICK)==true){SystemClock.sleep(250);return}
                action=action?.parent
            }
            val r=android.graphics.Rect();node.getBoundsInScreen(r)
            val now=SystemClock.uptimeMillis()
            for(a in intArrayOf(0,1)){val e=android.view.MotionEvent.obtain(now,SystemClock.uptimeMillis(),a,r.exactCenterX(),r.exactCenterY(),0);e.source=android.view.InputDevice.SOURCE_TOUCHSCREEN;try{check(auto.injectInputEvent(e,true))}finally{e.recycle()}}
            SystemClock.sleep(240)
        }
        @Suppress("UNCHECKED_CAST") fun <T> ui(block:()->T):T{var value:Any?=null;var error:Throwable?=null;i.runOnMainSync {try{value=block()}catch(t:Throwable){error=t}};error?.let {throw it};return value as T}
        fun descendants(v:View):List<View> = listOf(v)+(if(v is ViewGroup)(0 until v.childCount).flatMap {descendants(v.getChildAt(it))} else emptyList())
        fun views()=WindowInspector.getGlobalWindowViews().flatMap {descendants(it)}
        fun keyboard()=views().filterIsInstance<KeyboardView>().firstOrNull {it.isShown&&!it.preview}
        fun editor()=views().filterIsInstance<android.widget.EditText>().firstOrNull {it.isShown&&it.contentDescription=="键盘效果输入框"}
        val p=Prefs.of(ctx);val backup=p.all.toMap()
        fun start() {
            android.os.ParcelFileDescriptor.AutoCloseInputStream(auto.executeShellCommand("am start -W -f 0x10008000 -n com.kongye.keyboard/.SettingsActivity")).use {it.readBytes()}
            waitFor("home"){has("今日输入")};SystemClock.sleep(300)
        }
        fun range(max:Float):AccessibilityNodeInfo {
            var node:AccessibilityNodeInfo?=null
            waitFor("slider $max") {node=all().firstOrNull {it.rangeInfo?.max==max};node!=null}
            node!!.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SHOW_ON_SCREEN.id);SystemClock.sleep(250)
            return all().first {it.rangeInfo?.max==max}
        }
        fun progress(max:Float,value:Float) {
            check(range(max).performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS.id,Bundle().apply {putFloat(AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE,value)}))
            waitFor("slider value $value"){all().any {it.rangeInfo?.max==max&&kotlin.math.abs((it.rangeInfo?.current ?: -100f)-value)<.1f}}
        }
        fun conflictLink(word:String) {
            val node=all().first {it.text?.toString()?.startsWith("上下滑与滑行已同时开启")==true}
            node.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SHOW_ON_SCREEN.id);SystemClock.sleep(200)
            val bounds=android.graphics.Rect();node.getBoundsInScreen(bounds)
            val textSize=12*ctx.resources.displayMetrics.scaledDensity
            val x=bounds.left+(if(word=="滑行")5f else 1.5f)*textSize
            val y=bounds.top+9*ctx.resources.displayMetrics.density
            val now=SystemClock.uptimeMillis()
            for(action in intArrayOf(0,1)){val event=android.view.MotionEvent.obtain(now,SystemClock.uptimeMillis(),action,x,y,0);event.source=android.view.InputDevice.SOURCE_TOUCHSCREEN;try{check(auto.injectInputEvent(event,true))}finally{event.recycle()}}
            SystemClock.sleep(300)
        }
        try {
            ui {p.edit().putBoolean(Prefs.SOUND,true).putBoolean(Prefs.FLOAT,false).putBoolean(Prefs.VAULT,false).putInt(V3Prefs.SOUND_VOLUME,50).putInt(V3Prefs.VIBRATION,1).putBoolean(Prefs.SWIPE,true).putBoolean(V3Prefs.GLIDE_ZH,true).putBoolean(V3Prefs.GLIDE_EN,true).putString(Prefs.LAYOUT,"26").commit()}
            start();click("输入设置");click("按键与手势")
            check(ui {views().filterIsInstance<SettingExampleView>().none {it.key==Prefs.SOUND||it.key==V3Prefs.SOUND_VOLUME}}) {"Sound still has a diagram"}
            for(step in 0..6) {
                progress(6f,step.toFloat())
                check(p.getInt(V3Prefs.VIBRATION,-1)==when(step){0->2;1->0;else->1})
                if(step>=2)check(p.getInt(V3Prefs.VIBRATION_LEVEL,-1)==step)
            }
            for((label,value) in listOf("跟随系统" to 0,"启用" to 1,"关闭" to 2)) {click(label);waitFor("vibration card $label"){p.getInt(V3Prefs.VIBRATION,-1)==value}}
            report("Seven vibration stops synchronize the three mode cards; sound switch and volume have no diagrams")
            progress(100f,50f)
            val volume=range(100f);val rect=android.graphics.Rect();volume.getBoundsInScreen(rect)
            val down=SystemClock.uptimeMillis();val y=rect.exactCenterY();val startX=rect.exactCenterX();val endX=rect.left+rect.width()*.85f
            fun inject(action:Int,x:Float) {val event=android.view.MotionEvent.obtain(down,SystemClock.uptimeMillis(),action,x,y,0);event.source=android.view.InputDevice.SOURCE_TOUCHSCREEN;try{check(auto.injectInputEvent(event,true))}finally{event.recycle()}}
            inject(0,startX)
            for(step in 1..12){inject(2,startX+(endX-startX)*step/12);SystemClock.sleep(20)}
            check(all().any {it.rangeInfo?.max==100f&&(it.rangeInfo?.current ?: 0f)>75f}) {"Volume thumb did not move before release"}
            check(p.getInt(V3Prefs.SOUND_VOLUME,-1)==50) {"Volume saves during drag"}
            inject(1,endX);waitFor("volume saved on release"){p.getInt(V3Prefs.SOUND_VOLUME,0)>75}
            report("Real horizontal volume drag updates before finger-up and saves only on release")
            ui {
                val d=ctx.resources.displayMetrics.density
                for(key in listOf(V3Prefs.LETTER_LONG,Prefs.SPACE_LONG,V3Prefs.ASSOC_CONTINUOUS,V3Prefs.CLEAR_SCOPE)) {
                    val count=if(key==V3Prefs.LETTER_LONG||key==Prefs.SPACE_LONG)3 else 2
                    for(selected in 0 until count) {
                        val view=SettingExampleView(ctx);view.comparisonOptions=(0 until count).map {it as Any to "选项$it"};view.submit(key,"效果",selected,selected,"","")
                        val w=(336*d).toInt();val h=(124*d).toInt();view.measure(View.MeasureSpec.makeMeasureSpec(w,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(h,View.MeasureSpec.EXACTLY));view.layout(0,0,w,h)
                        val bitmap=android.graphics.Bitmap.createBitmap(w,h,android.graphics.Bitmap.Config.ARGB_8888);view.draw(android.graphics.Canvas(bitmap))
                        val panel=(w-8*d*(count-1))/count
                        for(index in 0 until count) {
                            val x=(index*(panel+8*d)+if(key==V3Prefs.LETTER_LONG)panel/2-10*d else 14*d).toInt()
                            val y=((if(key==V3Prefs.LETTER_LONG)77 else if(key==Prefs.SPACE_LONG||key==V3Prefs.CLEAR_SCOPE)32 else 55)*d).toInt()
                            check((bitmap.getPixel(x,y)==android.graphics.Color.WHITE)==(index==selected)) {"Fixed comparison highlight mismatch $key / $selected / $index"}
                        }
                        bitmap.recycle()
                    }
                }
            }
            report("Fixed two/three-option diagrams retain order and only the selected panel is bright")
            ui {
                val d=ctx.resources.displayMetrics.density
                val pictures=File(ctx.getExternalFilesDir(null),"example-refinements-pictures").apply {mkdirs()}
                val keys=listOf(V3Prefs.PAIRS,V3Prefs.PUNCT_CONFIRM,Prefs.ASSOC,V3Prefs.HOMOPHONE,V3Prefs.DELETE_DIRECT,V3Prefs.LETTER_LONG,Prefs.SPACE_LONG,V3Prefs.BOTTOM_CURSOR)
                for(key in keys) {
                    val choices=key==V3Prefs.LETTER_LONG||key==Prefs.SPACE_LONG
                    for(selected in 0 until if(choices)3 else 2) {
                        val view=SettingExampleView(ctx)
                        if(choices)view.comparisonOptions=listOf(0 as Any to "左",1 as Any to "中",2 as Any to "右")
                        val value:Any=if(choices)selected else selected==1
                        view.submit(key,"效果",value,value,"","")
                        val width=(360*d).toInt();val h=(when(key){V3Prefs.DELETE_DIRECT->190;V3Prefs.PUNCT_CONFIRM->148;Prefs.SPACE_LONG,V3Prefs.BOTTOM_CURSOR->124;else->110}*d).toInt()
                        view.layout(0,0,width,h)
                        val bitmap=android.graphics.Bitmap.createBitmap(width,h,android.graphics.Bitmap.Config.ARGB_8888);view.draw(android.graphics.Canvas(bitmap))
                        val count=if(choices)3 else 2;val gap=8*d;val pw=(width-gap*(count-1))/count
                        for(panel in 0 until count)if(panel!=selected) {
                            val left=(panel*(pw+gap)+5*d).toInt();val right=(panel*(pw+gap)+pw-5*d).toInt()
                            for(y in (4*d).toInt() until h-(4*d).toInt() step 3)for(x in left until right step 3) {
                                val color=bitmap.getPixel(x,y)
                                if(android.graphics.Color.alpha(color)>200) {
                                    val channels=listOf(android.graphics.Color.red(color),android.graphics.Color.green(color),android.graphics.Color.blue(color))
                                    check(channels.max()-channels.min()<=2){"Inactive panel retained color: $key $selected $panel"}
                                }
                            }
                        }
                        File(pictures,"$key-$selected.png").outputStream().use {bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)};bitmap.recycle()
                    }
                }
            }
            report("Inactive diagrams are fully grayscale; shared thin carets, actual tool icons and long-press bubble pictures rendered")
            click("删除键")
            check(has("滑动动作"))
            check(ui {views().filterIsInstance<SettingExampleView>().none {it.key in listOf(V3Prefs.DEL_UP,V3Prefs.DEL_DOWN,V3Prefs.DEL_LEFT,V3Prefs.DEL_RIGHT)}})
            for(label in listOf("上滑","下滑","左滑","右滑")){click(label);waitFor("action dropdown"){has(label+" 下拉菜单")};check(!has("取消"));auto.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK);SystemClock.sleep(200)}
            report("Four delete gestures share one group without diagrams; each picker opens")
            start();click("输入设置");click("按键与手势");click("26 键上下滑")
            conflictLink("滑行");waitFor("flick warning link"){has("滑行输入")&&has("中文滑行")}
            conflictLink("上下滑");waitFor("glide warning link"){has("26 键上下滑")&&has("启用上下滑")}
            report("Underlined conflict links navigate both directions")
            start();click("输入设置");click("语音输入")
            check(ui {views().filterIsInstance<SettingExampleView>().none {it.key in listOf(Prefs.VOICE_LANG,V3Prefs.MEDIA,V3Prefs.DRIVE)}}) {"Removed voice diagrams still present"}
            report("Language, media handling and continue-dictation controls have no diagrams")
            click("语句润色")
            check(ui {views().filterIsInstance<SettingExampleView>().none {it.key==VoicePolish.MODE}}) {"Polish mode still has a diagram"}
            report("Polish mode retains its selector without an example")
        } finally {
            ui {val e=p.edit().clear();backup.forEach {(k,v)->when(v){is Boolean->e.putBoolean(k,v);is Int->e.putInt(k,v);is Long->e.putLong(k,v);is Float->e.putFloat(k,v);is String->e.putString(k,v);is Set<*> -> @Suppress("UNCHECKED_CAST")e.putStringSet(k,v as Set<String>)}};e.commit()}
        }
        report("FEEDBACK CONTROLS PASSED")
    }
}
