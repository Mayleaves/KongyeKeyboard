package com.kongye.keyboard

import android.app.Instrumentation
import android.os.Bundle
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo as Node
import android.view.inspector.WindowInspector
import android.widget.EditText
import android.widget.TextView
import java.io.File

/** Selected phonetics advance without editing raw input; real panel and keyboard deletion undo them. */
object CandidatePinyinSelectionRegression {
    fun run(i: Instrumentation) {
        val ctx=i.targetContext
        val auto=i.getUiAutomation(android.app.UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        auto.serviceInfo=auto.serviceInfo.apply {flags=flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS}
        @Suppress("UNCHECKED_CAST") fun <T> ui(block:()->T):T {
            var value:Any?=null;var error:Throwable?=null
            i.runOnMainSync {try {value=block()}catch(t:Throwable){error=t}}
            error?.let {throw it};return value as T
        }
        fun waitFor(name:String,block:()->Boolean) {
            val end=SystemClock.uptimeMillis()+30000
            while(SystemClock.uptimeMillis()<end) {if(block())return;SystemClock.sleep(50)}
            error("Timed out: $name")
        }
        fun descendants(v:View):List<View> = listOf(v)+(if(v is ViewGroup)(0 until v.childCount).flatMap {descendants(v.getChildAt(it))}else emptyList())
        fun views()=WindowInspector.getGlobalWindowViews().flatMap(::descendants)
        fun nodes(n:Node?):List<Node> = if(n==null)emptyList() else listOf(n)+(0 until n.childCount).flatMap {nodes(n.getChild(it))}
        fun clickLabel(label:String) {
            waitFor(label){auto.clearCache();auto.windows.flatMap {nodes(it.root)}.any {it.text?.toString()==label}}
            var n=auto.windows.flatMap {nodes(it.root)}.first {it.text?.toString()==label}
            repeat(6) {if(n.performAction(Node.ACTION_CLICK))return;n=n.parent ?: error("Not clickable $label")}
            error("Not clickable $label")
        }
        fun editor()=views().filterIsInstance<EditText>().firstOrNull {it.isShown && it.contentDescription=="键盘效果输入框"}
        fun keyboard()=views().filterIsInstance<KeyboardView>().firstOrNull {it.isShown && !it.preview}
        fun tab()=views().filterIsInstance<PinyinTab>().firstOrNull {it.isShown}
        fun expanded()=views().filterIsInstance<ExpandedCandidates>().firstOrNull {it.isShown}
        fun options()=ui {expanded()?.let {page->descendants(page).filterIsInstance<TextView>()
            .filter {it.contentDescription?.startsWith("选择拼音 ")==true}.map {it.text.toString()}}.orEmpty()}
        fun choose(sound:String) {
            try {waitFor("phonetic $sound") {sound in options()}}
            catch(t:Throwable) {error("${t.message}; spelling=${ui {tab()?.contentDescription}}, underline=${ui {tab()?.selectedPrefixLength}}, options=${options()}")}
            ui {check(descendants(checkNotNull(expanded())).filterIsInstance<TextView>().first {it.contentDescription=="选择拼音 $sound"}.performClick())}
        }
        fun underline()=ui {tab()?.selectedPrefixLength ?: 0}
        fun spelling()=ui {tab()?.contentDescription?.toString()?.removePrefix("拼音：").orEmpty()}
        val prefs=Prefs.of(ctx);val backup=prefs.all.toMap()
        var ime:KongyeIME?=null
        val pictures=File(ctx.getExternalFilesDir(null),"candidate-pinyin-selection").apply {mkdirs()}
        fun shot(name:String) {
            SystemClock.sleep(100)
            checkNotNull(auto.takeScreenshot()).also {image->File(pictures,"$name.png").outputStream().use {image.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)};image.recycle()}
        }
        try {
            // Model coverage also checks repeated syllables and async list replacement.
            val model=CandidatePinyinSelection()
            val readings=listOf("ni hao", "ni hai", "li hao", "ni hao zhong guo", "ni ni")
            model.updateInput("nihao".map(Char::toString))
            check(model.options(readings)==listOf("ni","li"))
            check(model.choose("ni",readings));check(model.options(readings)==listOf("hao","hai","ni"))
            check(model.underlineLength("ni'hao")==2)
            check(model.choose("hao",readings));check(model.options(readings)==listOf("zhong"))
            check(model.undo() && model.selected==listOf("ni"))
            check(model.options(listOf("ni hao", "ni bao"))==listOf("hao","bao"))
            check(!model.updateInput("nihaoma".map(Char::toString)) && model.selected==listOf("ni"))
            check(model.updateInput("lihao".map(Char::toString)) && model.selected.isEmpty())
            ui {prefs.edit().putBoolean(Prefs.ZH,true).putString(Prefs.LAYOUT,"26").putBoolean(Prefs.LEARN,false)
                .putBoolean(Prefs.VAULT,false).putBoolean(Prefs.FLOAT,false).putBoolean(Prefs.ABBR,true)
                .putInt(V3Prefs.ONE_HAND,0).putInt(V3Prefs.PINYIN_POSITION,0).putBoolean(V3Prefs.STATS_ENABLED,false).commit()}
            android.os.ParcelFileDescriptor.AutoCloseInputStream(auto.executeShellCommand("am start -W -f 0x10008000 -n com.kongye.keyboard/.SettingsActivity")).use {it.readBytes()}
            SystemClock.sleep(1200);clickLabel("显示键盘")
            waitFor("live keyboard") {ui {editor()!=null && (keyboard()?.listener as? KongyeIME)?.isInputViewShown==true}}
            val live=ui {keyboard()!!.listener as KongyeIME};ime=live
            fun reset() {
                ui {live.onKey(Key(Kind.RESET));editor()?.setText("")}
                // Let the editor's selection callbacks finish before beginning new input.
                i.waitForIdleSync();SystemClock.sleep(100)
            }
            fun type(raw:String,layout:String="26") {
                val groups=when(layout) {"9"->listOf("abc","def","ghi","jkl","mno","pqrs","tuv","wxyz");"14"->listOf("qw","er","ty","ui","op","as","df","gh","jk","l","zx","cv","bn","m");else->emptyList()}
                for(ch in raw) {ui {live.onKey(if(ch=='\'')Key(Kind.SEP) else Key(Kind.LETTER,groups.firstOrNull {ch in it} ?: ch.toString()))};SystemClock.sleep(20)}
            }
            fun expand() {ui {if(expanded()==null)live.onExpand()};waitFor("expanded candidates") {ui {expanded()!=null}}}
            fun delete()=ui {live.onKey(Key(Kind.DEL))}
            for(layout in listOf("26","14","9")) {
                reset();ui {prefs.edit().putString(Prefs.LAYOUT,layout).commit()};SystemClock.sleep(250)
                type("nihao",layout);expand();waitFor("initial ni choices") {"ni" in options()}
                val before=spelling();check(underline()==0)
                if(layout=="26")shot("26-initial")
                choose("ni")
                try {waitFor("advance to hao") {"hao" in options() && underline()==2}}
                catch(t:Throwable) {
                    shot("$layout-failure")
                    val words=ui {views().filterIsInstance<CandidateBar>().firstOrNull()?.let {bar->
                        @Suppress("UNCHECKED_CAST") val items=CandidateBar::class.java.getDeclaredField("items").apply {isAccessible=true}.get(bar) as List<CandItem>
                        items.take(20).map {it.w to it.py}
                    }}
                    error("$layout: spelling=${spelling()}, underline=${underline()}, options=${options()}, candidates=$words; ${t.message}")
                }
                check(ui {editor()?.text?.isEmpty()==true}) {"Phonetic choice committed text"}
                SystemClock.sleep(500);check(underline()==2 && "hao" in options()) {"Async candidates reset chosen syllable"}
                shot("$layout-selected-ni")
                ui {check(descendants(checkNotNull(expanded())).first {it.contentDescription=="删除"}.performClick())}
                waitFor("panel delete restores first options") {underline()==0 && "ni" in options()}
                check(spelling()==before) {"Panel delete changed original spelling"}
                if(layout=="26")shot("26-restored")
                choose("ni");choose("hao")
                waitFor("two selected syllables") {underline()>=6}
                delete();waitFor("keyboard delete restores hao options") {underline()==2 && "hao" in options()}
                delete();waitFor("keyboard delete restores first options") {underline()==0 && "ni" in options()}
                check(spelling()==before)
                i.sendStatus(0,Bundle().apply {putString("stream","$layout layout: next syllable, underline, async refresh, panel/keyboard delete and raw-key preservation passed\n")})
            }
            reset();ui {prefs.edit().putString(Prefs.LAYOUT,"26").commit()};SystemClock.sleep(200)
            type("nihaozhongguo");expand();choose("ni");choose("hao");choose("zhong")
            waitFor("fourth syllable") {"guo" in options()}
            delete();waitFor("undo third syllable") {"zhong" in options() && underline()==6}
            ui {prefs.edit().putInt(V3Prefs.READINGS,1).commit()};SystemClock.sleep(300)
            check(underline()==6 && "zhong" in options()) {"Panel rebuild lost selected prefix"}
            reset();type("nihao");expand();choose("ni");choose("hao")
            ui {live.onKey(Key(Kind.SPACE))}
            waitFor("selected word committed") {ui {editor()?.text?.toString()=="你好"}}
            type("ni");waitFor("new composition has no selection") {spelling().replace("'","")=="ni" && underline()==0}
            delete();waitFor("ordinary delete removes one key") {spelling().replace("'","")=="n"}
            i.sendStatus(0,Bundle().apply {putString("stream","CANDIDATE PINYIN SELECTION PASSED: 26/14/9, multi-stage undo, selected underline, stable raw input, rebuild and new composition reset\n")})
        } finally {
            ui {
                ime?.onKey(Key(Kind.RESET))
                val edit=prefs.edit().clear()
                backup.forEach {(key,value)->when(value){is String->edit.putString(key,value);is Boolean->edit.putBoolean(key,value);is Int->edit.putInt(key,value);is Long->edit.putLong(key,value);is Float->edit.putFloat(key,value);is Set<*> -> @Suppress("UNCHECKED_CAST")edit.putStringSet(key,value as Set<String>)}}
                edit.commit()
            }
        }
    }
}
