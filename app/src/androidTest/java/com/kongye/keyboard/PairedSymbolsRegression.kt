package com.kongye.keyboard

import android.app.Instrumentation
import android.os.Bundle
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.inspector.WindowInspector

object PairedSymbolsRegression {
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
            ui {
                KeyGestures.save(ctx,true,'q',true,"edit:copy");KeyGestures.save(ctx,true,'w',true,"edit:cut");KeyGestures.save(ctx,true,'e',true,"edit:undo")
            }
            SystemClock.sleep(2000);click("显示键盘")
            waitFor("paired-symbol live editor"){ui {val ime=keyboard()?.listener as? KongyeIME;editor()!=null&&ime?.isInputViewShown==true&&((ime.currentInputEditorInfo?.inputType ?: 0) and android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE)!=0}}
            val live=ui {keyboard()!!.listener as KongyeIME}
            val pairs=listOf("()","[]","{}","<>","（）","［］","｛｝","＜＞","【】","〔〕","〖〗","〘〙","〚〛","「」","『』","｢｣","《》","〈〉","⟨⟩","⟪⟫","⟦⟧","⟮⟯","❨❩","❪❫","❬❭","❮❯","❰❱","❲❳","❴❵","﹙﹚","﹛﹜","﹝﹞","“”","‘’")
            for(pair in pairs)check(PairedSymbols.closing(pair.substring(0,1))==pair.substring(1)) {"Unsupported bracket "+pair}
            fun clear(){ui {live.onKey(Key(Kind.RESET));editor()!!.setText("");editor()!!.setSelection(0)};SystemClock.sleep(200)}
            fun typed(value:String){ui {live.onKey(Key(Kind.CHAR,value))}}
            fun expect(value:String,caret:Int){try {waitFor("paired output "+value){ui {editor()?.text?.toString()==value&&editor()?.selectionStart==caret&&editor()?.selectionEnd==caret}}}catch(t:Throwable){error("Expected "+value+" caret="+caret+"; actual="+ui {editor()?.text?.toString()}+" selection="+ui {editor()?.selectionStart.toString()+","+editor()?.selectionEnd}+" IME="+live.isInputViewShown+" inputType="+live.currentInputEditorInfo?.inputType)}}
            for(chinese in listOf(true,false)) {
                if(ui {keyboard()!!.zh}!=chinese){ui {live.onKey(Key(Kind.LANG))};waitFor("input language"){ui {keyboard()?.zh==chinese}}}
                for(pair in pairs) {
                    clear();typed(pair.substring(0,1));expect(pair,1)
                    typed("甲");expect(pair.substring(0,1)+"甲"+pair.substring(1),2)
                    clear();typed(pair);expect(pair,1)
                }
            }
            ui {prefs.edit().putBoolean(V3Prefs.PAIRS,false).commit()}
            for(left in listOf("(","（","[","{","<","〔","⟨")){clear();typed(left);expect(left,1)}
            ui {prefs.edit().putBoolean(V3Prefs.PAIRS,true).commit()}
            clear();typed(")");expect(")",1)
            if(ui {keyboard()!!.zh}!=true){ui {live.onKey(Key(Kind.LANG))};waitFor("restore Chinese"){ui {keyboard()?.zh==true}}}
            ui {prefs.edit().putBoolean(Prefs.SWIPE,true).putBoolean(V3Prefs.SWIPE_REVERSE,false).commit();KeyGestures.save(ctx,true,'z',true,"text:（")}
            clear();val board=ui {keyboard()!!}
            val rowsField=KeyboardView::class.java.getDeclaredField("rows").apply {isAccessible=true}
            @Suppress("UNCHECKED_CAST") val rows=ui {rowsField.get(board) as List<Row>}
            val key=rows.flatMap {it.keys}.first {it.kind==Kind.LETTER&&it.letters=="z"}
            ui {
                val start=SystemClock.uptimeMillis();val x=key.rect.centerX();val y=key.rect.centerY();val end=y+key.rect.height()*.6f
                for((action,atY) in listOf(0 to y,2 to end,1 to end)){val e=MotionEvent.obtain(start,SystemClock.uptimeMillis(),action,x,atY,0);board.onTouchEvent(e);e.recycle()}
            }
            expect("（）",1);typed("甲");expect("（甲）",2)
            SystemClock.sleep(350)
            val shot=auto.takeScreenshot()
            if(shot!=null){val folder=java.io.File(ctx.getExternalFilesDir(null),"paired-symbol-pictures").apply {mkdirs()};java.io.File(folder,"01-左括号补齐-光标在中间.png").outputStream().use {shot.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)};shot.recycle()}
            ui {prefs.edit().putBoolean(V3Prefs.CAPS,false).putInt(V3Prefs.PINYIN_POSITION,0).commit()}
            for(enabled in listOf(false,true)) {
                ui {prefs.edit().putBoolean(V3Prefs.PUNCT_CONFIRM,enabled).commit()};clear()
                for(letter in "nihao"){ui {live.onKey(Key(Kind.LETTER,letter.toString()))};SystemClock.sleep(35)}
                SystemClock.sleep(800);typed("，");expect(if(enabled)"你好，" else "nihao，",if(enabled)3 else 6)
            }
            val itemsField=CandidateBar::class.java.getDeclaredField("items").apply {isAccessible=true}
            fun candidates():List<String> = ui {
                val candidateBar=views().filterIsInstance<CandidateBar>().first {it.isShown}
                @Suppress("UNCHECKED_CAST") val items=itemsField.get(candidateBar) as List<CandItem>
                items.map {it.w}
            }
            for(enabled in listOf(false,true)) {
                ui {prefs.edit().putBoolean(V3Prefs.MIXED,enabled).commit()};clear()
                for(letter in "hel"){ui {live.onKey(Key(Kind.LETTER,letter.toString()))};SystemClock.sleep(35)}
                SystemClock.sleep(1000)
                val words=candidates()
                if(enabled)check("hello" in words) {"Mixed mode did not add hello: "+words}
                i.sendStatus(0,Bundle().apply {putString("stream","Mixed input "+enabled+": "+words.take(14).joinToString()+"\n")})
            }
            ui {live.onKey(Key(Kind.RESET))}
            click("收起键盘")
        } finally {
            ui {val e=prefs.edit().clear();backup.forEach {(k,v)->when(v){is Boolean->e.putBoolean(k,v);is Int->e.putInt(k,v);is Long->e.putLong(k,v);is Float->e.putFloat(k,v);is String->e.putString(k,v);is Set<*> -> @Suppress("UNCHECKED_CAST")e.putStringSet(k,v as Set<String>)}};e.commit()}
        }
        i.sendStatus(0,Bundle().apply {putString("stream","PAIRED SYMBOLS PASSED: 34 ASCII, Chinese, fullwidth, mathematical and ornamental bracket/quote pairs in both keyboard languages; auto closing and caret placement, typing inside, whole pairs without duplication, disabled setting, unmatched closer and real Z flick through production IME, actual punctuation-confirm output and hello mixed-input candidate.\n")})
    }
}
