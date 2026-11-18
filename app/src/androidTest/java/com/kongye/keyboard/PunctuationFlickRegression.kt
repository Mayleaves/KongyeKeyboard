package com.kongye.keyboard

import android.app.Instrumentation
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Rect
import android.os.Bundle
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import android.view.inspector.WindowInspector
import java.io.File

object PunctuationFlickRegression {
    fun run(i:Instrumentation) {
        val app=i.targetContext
        val ctx=object:ContextWrapper(app) {
            override fun getApplicationContext():Context=this
            override fun getSharedPreferences(name:String,mode:Int)=super.getSharedPreferences("punctuation-flick-check-"+name,mode)
        }
        val p=Prefs.of(ctx)
        @Suppress("UNCHECKED_CAST") fun <T> ui(block:()->T):T {var result:Any?=null;var failure:Throwable?=null;i.runOnMainSync {try{result=block()}catch(t:Throwable){failure=t}};failure?.let {throw it};return result as T}
        fun report(s:String)=i.sendStatus(0,Bundle().apply {putString("stream",s+"\n")})
        try {
            p.edit().clear().commit()
            KeyGestures.save(ctx,true,'q',true,"text:legacy")
            check(p.getString("flick26_zh_q_down",null)=="text:legacy")
            for(zh in listOf(true,false)) {
                KeyGestures.save(ctx,zh,',',true,"text:${if(zh)"；" else ";"}")
                KeyGestures.save(ctx,zh,',',false,"edit:copy")
                KeyGestures.save(ctx,zh,'.',true,"edit:undo")
                KeyGestures.save(ctx,zh,'.',false,"text:${if(zh)"？" else "?"}")
                for(reverse in listOf(false,true))for(paired in if(zh)listOf(false,true)else listOf(false)) {
                    p.edit().putBoolean(V3Prefs.SWIPE_REVERSE,reverse).commit()
                    ui {
                        val emitted=ArrayList<Key>()
                        val rows=if(paired)Layouts.fourteen(ctx)else Layouts.letters(zh,ctx)
                        val board=KeyboardView(ctx).apply {longMs=10000;glideEnabled=true;listener=object:KeyboardView.Listener {
                            override fun onKey(key:Key){emitted.add(key)}
                            override fun onLongKey(key:Key){};override fun onPress(key:Key){}
                            override fun onCursorMove(dx:Int,dy:Int){};override fun onSwipeDel(down:Boolean){}
                        }}
                        board.setup(Skins.KONGYE,rows)
                        board.measure(View.MeasureSpec.makeMeasureSpec(1080,View.MeasureSpec.EXACTLY),0);board.layout(0,0,board.measuredWidth,board.measuredHeight)
                        fun send(key:Key,dir:Int):Key {
                            emitted.clear();val start=SystemClock.uptimeMillis();val x=key.rect.centerX();val y=key.rect.centerY();val end=y+dir*key.rect.height()*.6f
                            val points=if(dir==0)listOf(0 to y,1 to y)else listOf(0 to y,2 to end,1 to end)
                            for((action,at)in points){val e=MotionEvent.obtain(start,SystemClock.uptimeMillis(),action,x,at,0);board.onTouchEvent(e);e.recycle()}
                            check(emitted.size==1){"Unexpected emission count ${emitted.size}, zh=$zh reverse=$reverse paired=$paired id=${key.gestureId} dir=$dir"}
                            return emitted.single()
                        }
                        val comma=rows.flatMap {it.keys}.first {it.gestureId==','}
                        val period=rows.flatMap {it.keys}.first {it.gestureId=='.'}
                        val normalDown=if(reverse)-1 else 1
                        check(send(comma,0).out==if(zh)"，"else",")
                        check(send(period,0).out==if(zh)"。"else".")
                        check(send(comma,normalDown).out==if(zh)"；"else";")
                        check(send(comma,-normalDown).let {it.kind==Kind.ACTION&&it.out=="copy"})
                        check(send(period,normalDown).let {it.kind==Kind.ACTION&&it.out=="undo"})
                        check(send(period,-normalDown).out==if(zh)"？"else"?")
                        check(comma.hint==if(zh)"；"else";")
                        check(comma.hint2Icon==KeyGestures.icon("copy")&&period.hintIcon==KeyGestures.icon("undo"))
                        board.swipeEnabled=false
                        check(send(comma,0).out==if(zh)"，"else",")
                    }
                }
            }
            p.edit().putBoolean(V3Prefs.SWIPE_REVERSE,false).commit()
            check(Layouts.letters(true,ctx).first().keys.first().downAction?.text=="legacy")
            val english=p.all.filterKeys {it.startsWith("flick26_en_")}.toMap()
            KeyGestures.clear(ctx,true)
            check(p.all.filterKeys {it.startsWith("flick26_zh_")}.let {it.size==56&&it.values.all {v->v=="text:"}})
            check(Layouts.letters(true,ctx).flatMap {it.keys}.filter {it.gestureId!=null}.all {it.upAction==null&&it.downAction==null})
            check(p.all.filterKeys {it.startsWith("flick26_en_")}==english)
            KeyGestures.reset(ctx,true)
            val restored=Layouts.letters(true,ctx).last().keys
            check(restored.first {it.gestureId==','}.downAction?.text==",")
            check(restored.first {it.gestureId=='.'}.downAction?.text==".")
            check(p.all.filterKeys {it.startsWith("flick26_en_")}==english)
            report("Real touch checks passed for comma/period taps, both swipe directions, Chinese/English, reversed direction, command icons, 14-key punctuation and independent clear/reset")
        } finally {p.edit().clear().commit()}

        val phone=Prefs.of(app);val before=phone.all.toMap()
        val auto=i.getUiAutomation(android.app.UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        auto.serviceInfo=auto.serviceInfo.apply {flags=flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS}
        fun nodes(n:AccessibilityNodeInfo?):List<AccessibilityNodeInfo> = if(n==null)emptyList()else listOf(n)+(0 until n.childCount).flatMap {nodes(n.getChild(it))}
        fun all():List<AccessibilityNodeInfo>{auto.clearCache();return auto.windows.flatMap {nodes(it.root)}}
        fun label(n:AccessibilityNodeInfo)=n.text?.toString() ?: n.contentDescription?.toString().orEmpty()
        fun matches(n:AccessibilityNodeInfo,text:String)=label(n)==text||n.contentDescription?.toString()==text
        fun waitFor(name:String,condition:()->Boolean){val end=SystemClock.uptimeMillis()+15000;while(SystemClock.uptimeMillis()<end){if(condition())return;SystemClock.sleep(80)};error("Timed out: $name")}
        fun locate(text:String):AccessibilityNodeInfo {
            repeat(18) {
                val n=all().firstOrNull {matches(it,text)}
                if(n!=null) {
                    n.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SHOW_ON_SCREEN.id);SystemClock.sleep(120)
                    all().firstOrNull {matches(it,text)&&it.isVisibleToUser}?.let {return it}
                }
                all().firstOrNull {it.isScrollable}?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD);SystemClock.sleep(150)
            };error("Missing $text")
        }
        fun click(text:String) {
            var n:AccessibilityNodeInfo?=locate(text)
            repeat(6){if(n?.performAction(AccessibilityNodeInfo.ACTION_CLICK)==true){SystemClock.sleep(250);return};n=n?.parent}
            error("Cannot click $text")
        }
        fun descendants(v:View):List<View> = listOf(v)+(if(v is ViewGroup)(0 until v.childCount).flatMap {descendants(v.getChildAt(it))}else emptyList())
        fun preview()=ui {WindowInspector.getGlobalWindowViews().flatMap {descendants(it)}.filterIsInstance<FlickPreviewView>().first {it.isShown}}
        fun pick(id:Char) {
            all().first {it.contentDescription?.startsWith("上下滑按键预览")==true}.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SHOW_ON_SCREEN.id)
            SystemClock.sleep(220)
            val v=preview()
            val point=ui {val r=checkNotNull(v.keyBounds(id));val xy=IntArray(2);v.getLocationOnScreen(xy);floatArrayOf(xy[0]+r.centerX(),xy[1]+r.centerY())}
            val start=SystemClock.uptimeMillis()
            for(action in intArrayOf(0,1)){val e=MotionEvent.obtain(start,SystemClock.uptimeMillis(),action,point[0],point[1],0);e.source=android.view.InputDevice.SOURCE_TOUCHSCREEN;try{check(auto.injectInputEvent(e,true))}finally{e.recycle()}}
            SystemClock.sleep(200)
            check(preview().contentDescription.toString().contains(if(id==',')"逗号"else"句号"))
        }
        val pictures=File(app.getExternalFilesDir(null),"punctuation-flick-pictures").apply {mkdirs()}
        fun shot(name:String){SystemClock.sleep(180);auto.takeScreenshot().let {b->File(pictures,name+".png").outputStream().use {b.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)};b.recycle()}}
        fun home(){android.os.ParcelFileDescriptor.AutoCloseInputStream(auto.executeShellCommand("am start -W -f 0x10008000 -n com.kongye.keyboard/.SettingsActivity")).use {it.readBytes()};waitFor("home"){all().any {label(it)=="今日输入"}}}
        fun setText(value:String){val field=all().first {it.isEditable};check(field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT,Bundle().apply {putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,value)}));SystemClock.sleep(120)}
        try {
            ui {phone.edit().putBoolean(Prefs.SWIPE,true).putBoolean(V3Prefs.SWIPE_REVERSE,false).commit()}
            home();click("按键与手势");click("上下滑")
            waitFor("preview"){ui {WindowInspector.getGlobalWindowViews().flatMap {descendants(it)}.any {it is FlickPreviewView&&it.height>0}}}
            check(all().none {label(it)=="26 键上下滑"})
            pick(',');shot("01-comma-selected")
            click("符号文字");setText("@");click("保存")
            check(phone.getString("flick26_zh_comma_down",null)=="text:@")
            pick('.');click("编辑功能");click("编辑功能：复制");click("保存")
            check(phone.getString("flick26_zh_period_down",null)=="edit:copy")
            pick('.');shot("02-period-command-saved")
            click("英文");pick(',');click("符号文字");setText("#");click("保存")
            check(phone.getString("flick26_en_comma_down",null)=="text:#")
            check(phone.getString("flick26_zh_comma_down",null)=="text:@")
            pick(',');shot("03-english-comma-saved")
            report("UI preview includes the real bottom row; comma and period are selectable and save literal/command actions separately for Chinese and English")
            report("PUNCTUATION FLICKS PASSED")
        } finally {
            ui {val e=phone.edit().clear();before.forEach {(k,v)->when(v){is Boolean->e.putBoolean(k,v);is Int->e.putInt(k,v);is Long->e.putLong(k,v);is Float->e.putFloat(k,v);is String->e.putString(k,v);is Set<*> -> @Suppress("UNCHECKED_CAST")e.putStringSet(k,v as Set<String>)}};e.commit()}
            home()
        }
    }
}
