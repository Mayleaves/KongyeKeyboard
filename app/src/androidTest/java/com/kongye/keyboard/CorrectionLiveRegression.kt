package com.kongye.keyboard

import android.app.Instrumentation
import android.os.Bundle
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.inspector.WindowInspector

object CorrectionLiveRegression {
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
            ui {prefs.edit().putBoolean(Prefs.ZH,true).putString(Prefs.LAYOUT,"26").putBoolean(Prefs.LEARN,false).putBoolean(V3Prefs.STATS_ENABLED,false).putBoolean(Prefs.VAULT,false).putBoolean(V3Prefs.PAIRS,true).putBoolean(Prefs.GLIDE,false).putBoolean(V3Prefs.GLIDE_ZH,false).putBoolean(V3Prefs.GLIDE_EN,false).putBoolean(Prefs.FLOAT,false).putBoolean(V3Prefs.SWIPE_REVERSE,false).commit()}
            android.os.ParcelFileDescriptor.AutoCloseInputStream(auto.executeShellCommand("am start -W -f 0x10008000 -n com.kongye.keyboard/.SettingsActivity")).use {it.readBytes()}
            SystemClock.sleep(2000);click("显示键盘")
            waitFor("paired-symbol live editor"){ui {val ime=keyboard()?.listener as? KongyeIME;editor()!=null&&ime?.isInputViewShown==true&&((ime.currentInputEditorInfo?.inputType ?: 0) and android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE)!=0}}
            val live=ui {keyboard()!!.listener as KongyeIME}
            val names=listOf(V3Prefs.CORRECT_NEAR,V3Prefs.CORRECT_SWAP,V3Prefs.CORRECT_EXTRA,V3Prefs.CORRECT_MISSING)
            fun mask(value:Int) {ui {val e=prefs.edit().putBoolean(V3Prefs.CORRECTION_ENABLED,true).putBoolean(V3Prefs.MIXED,false).putBoolean(Prefs.ABBR,true).putBoolean(Prefs.ASSOC,false);names.forEachIndexed {i,k->e.putBoolean(k,value and (1 shl i)!=0)};e.commit()};SystemClock.sleep(150)}
            fun clear(){ui {live.onKey(Key(Kind.RESET));editor()!!.setText("");editor()!!.setSelection(0)};SystemClock.sleep(200)}
            fun type(raw:String){for(c in raw){ui {live.onKey(Key(Kind.LETTER,c.toString()))};SystemClock.sleep(35)};SystemClock.sleep(1000)}
            val itemsField=CandidateBar::class.java.getDeclaredField("items").apply {isAccessible=true}
            fun items(): List<CandItem> = ui {
                @Suppress("UNCHECKED_CAST")
                (itemsField.get(views().filterIsInstance<CandidateBar>().first { it.isShown }) as List<CandItem>).toList()
            }
            fun words(): List<String> = items().map { it.w }
            ui { prefs.edit().putInt(V3Prefs.READINGS, 0).putInt(V3Prefs.GLOSS, 2).commit() }
            mask(15);clear();type("nihao")
            waitFor("correct spelling has no automatic annotation") { items().any { it.w == "你好" && it.py == null } }
            clear();type("nuhao")
            waitFor("native correction shows its reading") { items().any { it.w == "你好" && !it.py.isNullOrBlank() } }
            i.sendStatus(0, Bundle().apply { putString("stream", "Automatic readings distinguish nihao and nuhao on the live candidate bar\n") })
            data class InputCase(val bit:Int,val raw:String,val wanted:String)
            for((bit,raw,wanted) in listOf(InputCase(1,"nuhao","你好"),InputCase(2,"jeude","觉得"),InputCase(4,"nihaoo","你好"),InputCase(8,"niho","你好"))) {
                mask(15);clear();type(raw)
                waitFor("real correction $raw"){wanted in words()}
                val index=words().indexOf(wanted);ui {live.onCandidate(index)}
                waitFor("committed correction $raw"){ui {editor()?.text?.toString()==wanted}}
                ui {live.onKey(Key(Kind.CHAR,"甲"))}
                waitFor("no leftover letters $raw"){ui {editor()?.text?.toString()==wanted+"甲"}}
                i.sendStatus(0,Bundle().apply {putString("stream","Real input $raw => $wanted with no leftover letters\n")})
                if(bit<=2) {
                    clear();type(raw);ui {prefs.edit().putBoolean(names[if(bit==1)0 else 1],false).commit()}
                    waitFor("live switch removes $wanted"){wanted !in words()}
                    ui {prefs.edit().putBoolean(names[if(bit==1)0 else 1],true).commit()}
                    waitFor("live switch restores $wanted"){wanted in words()}
                }
            }
            mask(0);clear();ui {prefs.edit().putBoolean(V3Prefs.MIXED,true).commit()};type("nihaohello")
            waitFor("mixed full result"){words().firstOrNull()=="你好hello"}
            ui {live.onKey(Key(Kind.SPACE))};waitFor("mixed committed"){ui {editor()?.text?.toString()=="你好hello"}}
            clear();ui {prefs.edit().putBoolean(V3Prefs.MIXED,false).commit()};type("nihaohello");check("你好hello" !in words())
            clear();click("收起键盘")
        } finally {
            ui {val e=prefs.edit().clear();backup.forEach {(k,v)->when(v){is Boolean->e.putBoolean(k,v);is Int->e.putInt(k,v);is Long->e.putLong(k,v);is Float->e.putFloat(k,v);is String->e.putString(k,v);is Set<*> -> @Suppress("UNCHECKED_CAST")e.putStringSet(k,v as Set<String>)}};e.commit()}
        }
        i.sendStatus(0,Bundle().apply {putString("stream","CORRECTION LIVE PASSED: real four-type commits without leftover letters, neighboring/swap toggles update active composition, nihaohello mixed input on/off\n")})
    }
}
