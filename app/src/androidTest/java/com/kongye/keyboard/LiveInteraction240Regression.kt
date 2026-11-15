package com.kongye.keyboard

import android.app.*
import android.content.*
import android.graphics.*
import android.os.*
import android.view.*
import android.view.accessibility.AccessibilityNodeInfo
import android.view.inspector.WindowInspector
import android.widget.TextView

/** Actual independent editor + real IME window. No saved documents or credentials. */
object LiveInteraction240Regression {
    fun run(inst:Instrumentation) {
        val ctx=inst.targetContext;val auto=inst.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        auto.serviceInfo=auto.serviceInfo.apply {flags=flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS}
        fun report(s:String)=inst.sendStatus(0,Bundle().apply {putString("stream",s+"\n")})
        @Suppress("UNCHECKED_CAST") fun <T> ui(f:()->T):T {var value:Any?=null;var fail:Throwable?=null;inst.runOnMainSync {try {value=f()} catch(t:Throwable){fail=t}};fail?.let {throw it};return value as T}
        fun waitFor(label:String,f:()->Boolean) {val end=SystemClock.uptimeMillis()+15000;while(SystemClock.uptimeMillis()<end){if(f())return;Thread.sleep(50)};error("Timed out: $label")}
        fun nodes(n:AccessibilityNodeInfo?):List<AccessibilityNodeInfo> = if(n==null)emptyList() else listOf(n)+(0 until n.childCount).flatMap {nodes(n.getChild(it))}
        fun editor():AccessibilityNodeInfo? {
            auto.clearCache()
            return auto.windows.flatMap {nodes(it.root)}.firstOrNull {it.packageName?.toString()=="com.kongye.keyboard.test" && it.contentDescription?.toString()=="独立输入测试框"}
        }
        fun descendants(v:View):List<View> = listOf(v)+(if(v is ViewGroup)(0 until v.childCount).flatMap {descendants(v.getChildAt(it))} else emptyList())
        fun views()=WindowInspector.getGlobalWindowViews().flatMap {descendants(it)}
        fun keyboard()=views().filterIsInstance<KeyboardView>().firstOrNull {it.isShown && !it.preview}
        fun event(action:Int,x:Float,y:Float,down:Long=SystemClock.uptimeMillis()) {
            val e=MotionEvent.obtain(down,SystemClock.uptimeMillis(),action,x,y,0);e.source=android.view.InputDevice.SOURCE_TOUCHSCREEN
            try {check(auto.injectInputEvent(e,true))} finally {e.recycle()}
        }
        fun tap(v:View) {val loc=ui {IntArray(2).also {v.getLocationOnScreen(it)}};val x=loc[0]+v.width/2f;val y=loc[1]+v.height/2f;val now=SystemClock.uptimeMillis();event(0,x,y,now);event(1,x,y,now)}
        fun clear() {val e=editor() ?: error("Isolated field missing");check(e.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT,Bundle().apply {putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,"")}));Thread.sleep(200)}
        fun text()=editor()?.text?.toString().orEmpty().takeUnless {it=="只使用虚构测试内容"}.orEmpty()
        val p=Prefs.of(ctx)
        val keys=listOf(Prefs.GLIDE,Prefs.ZH,Prefs.LAYOUT,Prefs.VAULT,Prefs.COUNT_TODAY,Prefs.COUNT_DATE,Prefs.SPACE_LONG)
        val backup=keys.associateWith {p.all[it]}
        try {
            ui {p.edit().putBoolean(Prefs.GLIDE,true).putBoolean(Prefs.ZH,true).putString(Prefs.LAYOUT,"26").putBoolean(Prefs.VAULT,false).putInt(Prefs.SPACE_LONG,0).commit()}
            ctx.startActivity(Intent(inst.context,TypingFormActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra("bottom",true))
            waitFor("isolated editor") {editor()!=null}
            editor()!!.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            waitFor("keyboard window") {ui {keyboard()!=null}}
            val k=ui {keyboard()!!};val ime=k.listener as KongyeIME
            fun type(raw:String) {for(ch in raw){ui {ime.onKey(Key(Kind.LETTER,ch.toString()))};Thread.sleep(20)}}
            val raw="woxiwangmingtiandajiakeyiyiqiqugongyuanwanranhouzaizheliyiqichidunfan"
            clear();type(raw)
            lateinit var bubble:TextView
            waitFor("typing overflow preview") {ui {views().filterIsInstance<TextView>().firstOrNull {it.isShown && it.contentDescription?.toString()=="完整候选，点击输入"}?.also {bubble=it}!=null}}
            ui {
                val col=bubble.parent as ViewGroup
                val host=views().filterIsInstance<CandidateHost>().first {it.isShown}
                val a=IntArray(2);val b=IntArray(2)
                bubble.getLocationOnScreen(a);host.getLocationOnScreen(b)
                check(kotlin.math.abs(a[0]+bubble.width-b[0]-host.width)<=2) {"Candidate bubble has right margin"}
            }
            ui {ime.onExpand()};Thread.sleep(250)
            check(ui {!bubble.isShown}) {"Long bubble leaked into expanded candidate page"}
            ui {ime.onExpand()};Thread.sleep(250)
            waitFor("typing bubble restored") {ui {bubble.isShown}}
            Thread.sleep(1400)
            val wanted=ui {bubble.text.toString()}
            tap(bubble);waitFor("bubble selected text") {text()==wanted}
            report("Real typing-only bubble click committed full long candidate; expanded page hid bubble")
            clear();type("woianu")
            waitFor("grouped typo tab") {ui {views().filterIsInstance<PinyinTab>().any {it.isShown && it.contentDescription?.toString()?.contains("wo'ia'nu")==true}}}
            val tab=ui {views().filterIsInstance<PinyinTab>().first {it.isShown}}
            val height=ui {tab.height}
            check(editor()!=null)
            auto.takeScreenshot()?.also {shot->java.io.File(ctx.getExternalFilesDir(null),"review-243-pinyin.png").outputStream().use {shot.compress(Bitmap.CompressFormat.PNG,100,it)};shot.recycle()}
            ui {tab.feedback(SpellingAssist.Feedback(BooleanArray(6),emptyList()))}
            Thread.sleep(100)
            check(ui {tab.height==height}) {"Feedback changed compact spelling height"}
            tap(tab)
            waitFor("expanded spelling editor") {ui {tab.expanded}}
            val caret=PinyinTab::class.java.getDeclaredField("caretVisible").apply {isAccessible=true}
            val seen=mutableSetOf<Boolean>()
            repeat(14) {seen+=ui {caret.getBoolean(tab)};Thread.sleep(80)}
            check(seen.size==2) {"Expanded caret did not blink"}
            report("Fixed-height typo feedback, right-aligned bubble and blinking spelling caret passed")
            ui {ime.onKey(Key(Kind.RESET))}
            clear()
            fun gesture(word:String,chinese:Boolean,confirm:Boolean=true) {
                val centers=ui {
                    val rows=KeyboardView::class.java.getDeclaredField("rows").apply {isAccessible=true}.get(k) as List<*>
                    val loc=IntArray(2);k.getLocationOnScreen(loc)
                    rows.filterIsInstance<Row>().flatMap {it.keys}.filter {it.kind==Kind.LETTER}.associate {it.letters[0] to PointF(loc[0]+it.rect.centerX(),loc[1]+it.rect.centerY())}
                }
                val start=centers.getValue(word.first());val time=SystemClock.uptimeMillis();event(0,start.x,start.y,time)
                for(n in 1 until word.length) {
                    val a=centers.getValue(word[n-1]);val b=centers.getValue(word[n])
                    for(j in 1..9){event(2,a.x+(b.x-a.x)*j/9,a.y+(b.y-a.y)*j/9,time);Thread.sleep(35)}
                }
                // The finger remains down: these must be live preview results.
                waitFor("live candidate before lift") {ui {
                    val field=KongyeIME::class.java.getDeclaredField("liveGlideRaw").apply {isAccessible=true}
                    val current=field.get(ime) as? String
                    val tab=views().filterIsInstance<PinyinTab>().firstOrNull {it.isShown}
                    if(chinese) !current.isNullOrEmpty() && tab!=null && tab.glideUnderline
                    else !current.isNullOrEmpty() && tab==null
                }}
                report("${if(chinese) "Chinese" else "English"} candidates updated before finger lift; spelling visibility correct")
                val end=centers.getValue(word.last());event(1,end.x,end.y,time);Thread.sleep(600)
                check(text().isEmpty()) {"Glide committed before explicit selection: ${text()}"}
                if(!confirm)return
                ui {ime.onKey(Key(Kind.SPACE))}
                waitFor("glide committed") {text().isNotBlank() && text()!="只使用虚构测试内容"}
                check(text()==if(chinese)"你好你好" else "hello hello") {"Actual live glide commit: ${text()}"}
            }
            GlideDecoder.warm(ctx,true);gesture("nihao",true,false);gesture("nihao",true)
            ui {ime.onKey(Key(Kind.LANG))};Thread.sleep(220);clear();GlideDecoder.warm(ctx,false);gesture("hello",false,false);gesture("hello",false)
            report("Live Chinese/English gestures committed correctly without exposing English spelling")
            clear()
            val oldSpace=ui {k.spaceLong}
            try {
                ui {k.spaceLong=0}
                fun key(kind:Kind)=ui {
                    val rows=KeyboardView::class.java.getDeclaredField("rows").apply {isAccessible=true}.get(k) as List<*>
                    rows.filterIsInstance<Row>().flatMap {it.keys}.first {it.kind==kind}
                }
                val space=key(Kind.SPACE)
                val loc=ui {IntArray(2).also {k.getLocationOnScreen(it)}}
                val x=loc[0]+space.rect.centerX();val y=loc[1]+space.rect.centerY()
                val pressed=SystemClock.uptimeMillis()
                event(0,x,y,pressed);Thread.sleep(k.longMs+150)
                check(ui {k.cursorMode})
                for(step in 1..8) {
                    event(2,x,y-step*15,pressed);Thread.sleep(45)
                    check(ui {k.cursorMode}) {"Vertical movement reset cursor mode"}
                }
                event(1,x,y-120,pressed)
                check(ui {!k.cursorMode})
                val rows=ui {KeyboardView::class.java.getDeclaredField("rows").apply {isAccessible=true}.get(k) as List<*>}
                val q=rows.filterIsInstance<Row>().flatMap {it.keys}.first {it.kind==Kind.LETTER && it.letters=="q"}
                val qloc=ui {IntArray(2).also {k.getLocationOnScreen(it)}}
                val qx=qloc[0]+q.rect.centerX();val qy=qloc[1]+q.rect.centerY()
                val held=SystemClock.uptimeMillis();event(0,qx,qy,held)
                Thread.sleep(k.longMs+150)
                ui {
                    val presses=KeyboardView::class.java.getDeclaredField("presses").apply {isAccessible=true}.get(k) as Map<*,*>
                    check(presses.isNotEmpty()) {"Top-row press was cancelled while showing its menu"}
                    val press=presses.values.first()!!
                    val popup=press.javaClass.getDeclaredField("popup").apply {isAccessible=true}.get(press) as List<*>
                    val method=KeyboardView::class.java.declaredMethods.first {it.name=="popupRect"}
                    method.isAccessible=true
                    val rect=method.invoke(k,press,popup.size) as RectF
                    check(rect.bottom<q.rect.top) {"First-row popup did not sit above its key"}
                    check(KeyboardView::class.java.getDeclaredField("popupRoot").apply {isAccessible=true}.get(k)!=null)
                }
                event(3,qx,qy,held)
                check(Layouts.letters(true,ctx).flatMap {it.keys}.none {it.longEmoji})
                check(Layouts.letters(false,ctx).flatMap {it.keys}.none {it.longEmoji})
                ui {ime.onTool("more")}
                Thread.sleep(180)
                ui {ime.onTool("lang")}
                Thread.sleep(180)
                check(ui {k.isShown})
                report("Cursor mode survived vertical moves; top-row popup used window overlay; more-language returned to keys")
            } finally {ui {k.spaceLong=oldSpace}}
            ui {
                val bottom=views().filterIsInstance<BottomStrip>().first {it.isShown}
                ime.onKey(Key(Kind.LANG))
                val e=MotionEvent.obtain(0,0,MotionEvent.ACTION_UP,bottom.width-25f,bottom.height/2f,0)
                try {bottom.onTouchEvent(e)} finally {e.recycle()}
                check(views().filterIsInstance<ClipboardPanel>().any {it.isShown}) {"Clipboard did not open in the click handler"}
                val root=bottom.parent.parent as ViewGroup
                check((0 until root.childCount).none {root.getChildAt(it) is android.widget.ImageView}) {"Old keyboard snapshot covers clipboard"}
            }
            report("Bottom-right clipboard displayed immediately with no covering old keyboard snapshot")
            val fixture=java.io.File(ctx.cacheDir,"thumbnail242.png")
            Bitmap.createBitmap(1200,2400,Bitmap.Config.ARGB_8888).also {bitmap->
                bitmap.eraseColor(Color.rgb(130,170,150))
                fixture.outputStream().use {bitmap.compress(Bitmap.CompressFormat.PNG,100,it)}
                bitmap.recycle()
            }
            val active=mutableSetOf<ClipThumbnailView>()
            lateinit var thumbnail:ClipThumbnailView
            lateinit var parent:ViewGroup
            try {
                ui {
                    val bottom=views().filterIsInstance<BottomStrip>().first {it.isShown}
                    parent=bottom.parent.parent as ViewGroup
                    thumbnail=ClipThumbnailView(ctx,fixture,active)
                    check(thumbnail.drawable==null) {"Thumbnail decoded during construction"}
                    parent.addView(thumbnail,android.widget.FrameLayout.LayoutParams(256,256,Gravity.BOTTOM or Gravity.END))
                }
                waitFor("async visible thumbnail") {ui {thumbnail.drawable!=null}}
                ui {
                    val bmp=(thumbnail.drawable as android.graphics.drawable.BitmapDrawable).bitmap
                    check(bmp.width<=512 && bmp.height<=512) {"Oversized thumbnail ${bmp.width}x${bmp.height}"}
                    parent.removeView(thumbnail)
                    check(active.isEmpty() && thumbnail.drawable==null) {"Detached image retained thumbnail"}
                }
                report("Visible image loaded asynchronously at bounded resolution; detached thumbnail released")
            } finally {ui {try {if(thumbnail.parent!=null)(thumbnail.parent as ViewGroup).removeView(thumbnail)} catch(_:UninitializedPropertyAccessException) {}};fixture.delete()}
        } finally {
            ui {
                val e=p.edit()
                for((key,value) in backup)when(value){null->e.remove(key);is Boolean->e.putBoolean(key,value);is String->e.putString(key,value);is Int->e.putInt(key,value)}
                e.commit()
            }
        }
    }
}
