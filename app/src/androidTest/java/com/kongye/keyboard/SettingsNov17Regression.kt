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
object SettingsNov17Regression {
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
        try {
            ui {p.edit().putBoolean(Prefs.ZH,true).putBoolean(Prefs.FLOAT,false).putBoolean(Prefs.VAULT,false).putBoolean(Prefs.LEARN,false).putInt(V3Prefs.ONE_HAND,0).commit()}
            android.os.ParcelFileDescriptor.AutoCloseInputStream(auto.executeShellCommand("am start -W -f 0x10008000 -n com.kongye.keyboard/.SettingsActivity")).use {it.readBytes()}
            waitFor("home"){has("今日输入")&&has("显示键盘")};SystemClock.sleep(120)
            check(!has("中文与拼音"));click("输入设置");waitFor("inline children"){has("中文与拼音")};check(has("今日输入"))
            click("输入设置");waitFor("collapsed children"){!has("中文与拼音")}
            click("使用准备");waitFor("inline preparation"){has("启用输入法")};check(has("今日输入"));check(has("已设置"));check(!has("系统设置"))
            click("输入设置");waitFor("replace expanded group"){has("中文与拼音")&&!has("启用输入法")}
            click("中文与拼音");waitFor("leaf page"){!has("今日输入")&&has("简拼")}
            report("Main groups expand/collapse inline; preparation status labels and second-level navigation passed")
            // White candidate fields and real production arrow/wave rendering.
            ui {
                val d=ctx.resources.displayMetrics.density
                fun bitmap(key:String,on:Boolean):android.graphics.Bitmap {
                    val v=SettingExampleView(ctx);v.submit(key,"效果",on,on,"","")
                    val w=(320*d).toInt();val h=(110*d).toInt();v.measure(View.MeasureSpec.makeMeasureSpec(w,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(h,View.MeasureSpec.EXACTLY));v.layout(0,0,w,h)
                    return android.graphics.Bitmap.createBitmap(w,h,android.graphics.Bitmap.Config.ARGB_8888).also {v.draw(android.graphics.Canvas(it))}
                }
                val before=bitmap(V3Prefs.FEEDBACK,false);val after=bitmap(V3Prefs.FEEDBACK,true)
                fun redYs(b:android.graphics.Bitmap,left:Boolean):List<Int> {
                    val rows=ArrayList<Int>()
                    for(y in (25*d).toInt() until b.height)for(x in (if(left)0 else b.width/2) until (if(left)b.width/2 else b.width)){val c=b.getPixel(x,y);if(kotlin.math.abs(android.graphics.Color.red(c)-230)<=2&&kotlin.math.abs(android.graphics.Color.green(c)-57)<=2&&kotlin.math.abs(android.graphics.Color.blue(c)-70)<=2)rows.add(y)}
                    return rows
                }
                check(redYs(before,true).isEmpty());val ys=redYs(after,false);check(ys.size>15&&ys.max()-ys.min()>10*d) {"Missing real swap arrows or error wave"}
                val white=bitmap(V3Prefs.EMOJI,true);check(white.getPixel((176*d).toInt(),(58*d).toInt())==android.graphics.Color.WHITE)
                before.recycle();after.recycle();white.recycle()
            }
            report("Native PinyinTab swap arrows and waves are visible as pixels; disabled markers disappear; candidate background is white")
            click("中文与拼音");waitFor("home again"){has("今日输入")}
            click("工具");waitFor("expanded tools"){has("密码填充")}
            val originally=p.getBoolean(V3Prefs.STATS_ENABLED,true)
            click("打字统计 开关");check(p.getBoolean(V3Prefs.STATS_ENABLED,true)==!originally);check(has("打字统计 开关")&&!has("记录打字统计"))
            click("打字统计");waitFor("stats detail"){has("每日字数")&&!has("今日输入")}
            check(p.getBoolean(V3Prefs.STATS_ENABLED,true)==!originally)
            click("打字统计");waitFor("back home stats"){has("打字统计 开关")}
            report("Row body opens settings; trailing switch toggles independently without navigating; statistics history remains accessible")
            click("显示键盘");waitFor("center preview"){ui {editor()!=null&&keyboard()?.listener is KongyeIME}}
            val live=ui {keyboard()!!.listener as KongyeIME}
            val info=ui {live.currentInputEditorInfo!!};val flags=info.imeOptions
            val field=TypingStats::class.java.getDeclaredField("store").apply {isAccessible=true}
            val original=ui {TypingStats.of(ctx);field.get(null)}
            val file=File(ctx.cacheDir,"stats-toggle-nov17.json");file.delete();val isolated=TypingStatsStore(file,0)
            try {
                ui {field.set(null,isolated);p.edit().putBoolean(V3Prefs.STATS_ENABLED,false).commit();info.imeOptions=flags and android.view.inputmethod.EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING.inv()}
                val legacy=Prefs.typedToday(ctx)
                ui {live.onKey(Key(Kind.CHAR,"甲"))}
                waitFor("preview edit"){ui {editor()?.text?.toString()=="甲"}}
                check(isolated.snapshot().total==0L&&isolated.snapshot().keys==0L);check(Prefs.typedToday(ctx)==legacy)
                ui {
                    val f=live.javaClass.getDeclaredMethod("statsAllowed").apply {isAccessible=true}
                    check(f.invoke(live)==false);p.edit().putBoolean(V3Prefs.STATS_ENABLED,true).commit();check(f.invoke(live)==true)
                    info.imeOptions=flags;check(f.invoke(live)==false)
                }
                ui {
                    val e=editor()!!;val k=keyboard()!!;val r=android.graphics.Rect();e.getGlobalVisibleRect(r);val kr=android.graphics.Rect();k.getGlobalVisibleRect(kr)
                    check(r.centerY()>0&&r.centerY()<kr.top)
                    val area=e.parent as View;val ar=android.graphics.Rect();area.getGlobalVisibleRect(ar)
                    check(kotlin.math.abs(r.centerY()-ar.centerY())<20*ctx.resources.displayMetrics.density)
                    val bg=e.background as android.graphics.drawable.ColorDrawable;check(bg.color==android.graphics.Color.TRANSPARENT)
                }
            } finally {ui {info.imeOptions=flags;field.set(null,original)};isolated.close();file.delete()}
            click("收起键盘");waitFor("preview closes"){ui {editor()==null}}
            report("Transparent center input is above the keyboard; disabling statistics blocks actual commits and keys while preserving privacy-field exclusion")
            click("关于");click("更新历史");waitFor("history title actions"){has("打开 GitHub")&&has("刷新更新历史")}
            check(!has("项目源码与发行版"));check(!has("版本记录"))
            var footer:AccessibilityNodeInfo?=null
            for(attempt in 0 until 15) {
                footer=all().firstOrNull {node->val bounds=android.graphics.Rect();node.getBoundsInScreen(bounds);node.text?.toString()=="下一页"&&node.isVisibleToUser&&bounds.height()>0}
                if(footer!=null)break
                all().firstOrNull {it.isScrollable}?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD);SystemClock.sleep(170)
            }
            val fr=android.graphics.Rect();checkNotNull(footer) {"History pager missing at list end"}.getBoundsInScreen(fr)
            val button=all().first {it.text?.toString()=="显示键盘"};val br=android.graphics.Rect();button.getBoundsInScreen(br)
            check(fr.bottom<br.top) {"History pager obscured by keyboard button"}
            val search=all().first {it.className?.toString()=="android.widget.EditText"&&it.packageName?.toString()==ctx.packageName}
            search.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT,Bundle().apply {putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,"v2.4.7")})
            waitFor("filtered history"){has("v2.4.7")&&has("1 / 1")}
            report("History title GitHub/refresh actions, removed action rows, scrolling list-end pagination and version search passed")
        } finally {
            ui {val e=p.edit().clear();backup.forEach {(k,v)->when(v){is Boolean->e.putBoolean(k,v);is Int->e.putInt(k,v);is Long->e.putLong(k,v);is Float->e.putFloat(k,v);is String->e.putString(k,v);is Set<*> -> @Suppress("UNCHECKED_CAST")e.putStringSet(k,v as Set<String>)}};e.commit()}
        }
        report("NOV17 SETTINGS UI / GRAPHICS / STATISTICS SWITCH PASSED")
    }
}
