package com.kongye.keyboard

import android.app.Instrumentation
import android.content.Intent
import android.graphics.RectF
import android.os.Bundle
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Known intended targets on a real production view; synthetic accuracy is not a human error rate. */
object TouchRecognitionRegression {
    private data class Point(val id:Int,val x:Float,val y:Float)
    fun run(i:Instrumentation) {
        check(android.os.Build.MODEL.startsWith("sdk_"))
        val ctx=i.targetContext;val prefs=Prefs.of(ctx);val saved=prefs.all.toMap()
        @Suppress("UNCHECKED_CAST") fun <T> ui(block:()->T):T {
            var result:Any?=null;var error:Throwable?=null
            i.runOnMainSync {try{result=block()}catch(t:Throwable){error=t}}
            error?.let {throw it};return result as T
        }
        val activity=i.startActivitySync(Intent(ctx,SettingsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        var calibrationActivity:android.app.Activity?=null
        val output=JSONArray();val report=JSONObject().put("appVersion",ctx.packageManager.getPackageInfo(ctx.packageName,0).versionName)
            .put("device",android.os.Build.MODEL).put("api",android.os.Build.VERSION.SDK_INT)
            .put("synthetic",true).put("humanMistouchRateMeasured",false).put("queries",output)
        val keys=Layouts.letters(true)
        val letters=keys.flatMap {it.keys}.filter {it.kind==Kind.LETTER}
        val received=mutableListOf<String>()
        lateinit var board:KeyboardView
        val rowField=KeyboardView::class.java.getDeclaredField("rows").apply {isAccessible=true}
        fun sample(x:Float,y:Float,tool:Int=MotionEvent.TOOL_TYPE_FINGER,major:Float=0f,minor:Float=0f)=TouchResolver.Sample(x,y,major,minor,tool,SystemClock.uptimeMillis())
        fun resolve(x:Float,y:Float)=TouchResolver.resolve(keys.flatMap {it.keys},board.width.toFloat(),board.height.toFloat(),ctx.resources.displayMetrics.density,sample(x,y))
        fun event(action:Int,points:List<Point>,tool:Int=MotionEvent.TOOL_TYPE_FINGER,major:Float=0f,minor:Float=0f) {
            val now=SystemClock.uptimeMillis()
            val properties=points.map {MotionEvent.PointerProperties().apply {id=it.id;toolType=tool}}.toTypedArray()
            val coords=points.map {MotionEvent.PointerCoords().apply {x=it.x;y=it.y;pressure=1f;size=.1f;touchMajor=major;touchMinor=minor}}.toTypedArray()
            val e=MotionEvent.obtain(now,now,action,points.size,properties,coords,0,0,1f,1f,0,0,InputDevice.SOURCE_TOUCHSCREEN,0)
            try {board.onTouchEvent(e)}finally {e.recycle()}
        }
        fun tap(key:Key,dx:Float=0f,dy:Float=0f,tool:Int=MotionEvent.TOOL_TYPE_FINGER,major:Float=0f,minor:Float=0f) {
            val point=listOf(Point(7,key.rect.centerX()+dx,key.rect.centerY()+dy))
            event(MotionEvent.ACTION_DOWN,point,tool,major,minor);event(MotionEvent.ACTION_UP,point,tool,major,minor)
        }
        try {
            ui {
                prefs.edit().putBoolean(TouchCalibration.ENABLED,false).commit()
                board=KeyboardView(activity).apply {
                    swipeEnabled=false;glideEnabled=false;letterLong=2
                    listener=object:KeyboardView.Listener {
                        override fun onKey(key:Key) {received.add(if(key.kind==Kind.LETTER)key.letters else key.kind.name);setup(Skins.KONGYE,keys)}
                        override fun onLongKey(key:Key) {}
                        override fun onPress(key:Key) {}
                        override fun onCursorMove(dx:Int,dy:Int) {}
                        override fun onSwipeDel(down:Boolean) {}
                    }
                    setup(Skins.KONGYE,keys)
                }
                activity.setContentView(FrameLayout(activity).apply {addView(board,FrameLayout.LayoutParams(-1,-2))})
            }
            i.waitForIdleSync()
            ui {
                val start=System.nanoTime();var labelled=0;var errors=0
                for(k in letters)for(dx in listOf(-.22f,0f,.22f))for(dy in listOf(-.22f,0f,.22f)) {
                    received.clear();tap(k,dx*k.rect.width(),dy*k.rect.height())
                    labelled++;if(received!=listOf(k.letters))errors++
                }
                report.put("labelledInteriorSamples",labelled).put("interiorErrors",errors).put("syntheticMeanTapMs",(System.nanoTime()-start)/1e6/labelled)
                check(errors==0) {"Interior centre taps lost: $errors/$labelled"}
                val a=letters.first {it.letters=="a"};val b=letters.first {it.letters=="s"}
                received.clear()
                event(0,listOf(Point(7,a.rect.centerX(),a.rect.centerY())))
                event(5 or (1 shl 8),listOf(Point(7,a.rect.centerX(),a.rect.centerY()),Point(11,b.rect.centerX(),b.rect.centerY())))
                check(received.isEmpty()) {"Second finger prematurely committed the first"}
                // Lift second finger first. Both outputs must retain DOWN order even after setup refresh.
                event(6 or (1 shl 8),listOf(Point(7,a.rect.centerX(),a.rect.centerY()),Point(11,b.rect.centerX(),b.rect.centerY())))
                check(received.isEmpty())
                event(1,listOf(Point(7,a.rect.centerX(),a.rect.centerY())))
                check(received==listOf("a","s")) {"Two-finger order lost: $received"}
                received.clear()
                event(0,listOf(Point(7,a.rect.centerX(),a.rect.centerY())))
                event(5 or (1 shl 8),listOf(Point(7,a.rect.centerX(),a.rect.centerY()),Point(11,b.rect.centerX(),b.rect.centerY())))
                // First pointer is index 1 after a vendor reorders indices.
                event(6 or (1 shl 8),listOf(Point(11,b.rect.centerX(),b.rect.centerY()),Point(7,a.rect.centerX(),a.rect.centerY())))
                event(1,listOf(Point(11,b.rect.centerX(),b.rect.centerY())))
                check(received==listOf("a","s")) {"Pointer ID/index confused: $received"}
                received.clear()
                event(0,listOf(Point(7,a.rect.centerX(),a.rect.centerY())))
                event(2,listOf(Point(7,b.rect.centerX(),b.rect.centerY())))
                event(1,listOf(Point(7,b.rect.centerX(),b.rect.centerY())))
                check(received.isEmpty()) {"Large sideways drag emitted a key"}
                tap(a,a.rect.width()*.05f);check(received==listOf("a")) {"Fast short tap was filtered"}
                received.clear();event(0,listOf(Point(7,a.rect.centerX(),a.rect.centerY())));event(3,listOf(Point(7,a.rect.centerX(),a.rect.centerY())));check(received.isEmpty())
                val d=ctx.resources.displayMetrics.density
                tap(a,major=80*d,minor=45*d);check(received.isEmpty()) {"Broad palm-like contact accepted"}
                tap(a,tool=MotionEvent.TOOL_TYPE_STYLUS,major=80*d,minor=45*d);check(received==listOf("a")) {"Stylus incorrectly rejected as palm"}
                check(resolve(-1f,30f).key==null && resolve(board.width+1f,30f).key==null && resolve(1f,board.height+20f).key==null)
                check(resolve(0f,a.rect.centerY()).key==null) {"Indented-row blank margin became a letter"}
                val separator=Key(Kind.SEP).apply {rect.set(0f,0f,40*d,50*d)}
                check(TouchResolver.resolve(listOf(separator),100*d,100*d,d,sample(d,25*d)).key==null)
                check(TouchResolver.resolve(listOf(separator),100*d,100*d,d,sample(20*d,25*d)).key===separator)
                val shift=keys.flatMap {it.keys}.single {it.kind==Kind.SHIFT}
                check(TouchResolver.resolve(listOf(shift),board.width.toFloat(),board.height.toFloat(),d,
                    sample(shift.rect.left+d,shift.rect.centerY()),separator={it===shift}).key==null)
                // A small contextual prior can break a true geometric tie, but never move a visual-core hit.
                val h=Key(Kind.LETTER,"h").apply {rect.set(0f,0f,40*d,50*d)}
                val j=Key(Kind.LETTER,"j").apply {rect.set(40*d,0f,80*d,50*d)}
                check(TouchResolver.resolve(listOf(h,j),80*d,50*d,d,sample(40*d,25*d),context={if(it===h).02f else 0f}).key===h)
                check(TouchResolver.resolve(listOf(h,j),80*d,50*d,d,sample(60*d,25*d),context={if(it===h).02f else 0f}).key===j)
                for(row in keys)for((left,right) in row.keys.zipWithNext()) {
                    val x=(left.rect.right+right.rect.left)/2;val y=left.rect.centerY()
                    val found=resolve(x,y)
                    check(found.choices.size<=3)
                    output.put(JSONObject().put("kind","gap").put("between",left.label+"/"+right.label).put("x",x).put("y",y)
                        .put("selected",found.key?.label ?: "rejected").put("reason",found.reason))
                }
                val model=TouchCalibration(ctx);model.clear()
                val now=System.currentTimeMillis();repeat(8){model.record("fixture","a",.12f,-.08f,now)}
                check(model.bias("fixture","a",now).count==0f) // disabled model ignores saved values
                prefs.edit().putBoolean(TouchCalibration.ENABLED,true).commit()
                val fresh=model.bias("fixture","a",now);val aged=model.bias("fixture","a",now+60L*86400000)
                check(fresh.x>0 && aged.x in 0f..fresh.x*.6f && aged.count<fresh.count)
                check(model.bias("other","a",now).count==0f)
                model.clear();check(model.bias("fixture","a",now).count==0f)
            }
            Lexicon.load(ctx);PinyinEngine.initialize(ctx)
            val whitelist=Lexicon.pinyinSyllables().toSet()
            for(raw in listOf("zheme","zhemeda","zhemda","jingzhun","jinzhun","deshinali","dvshinali","dveshi","name","zhen","ni'hao","'ni''hao'")) {
                val trace=JSONArray()
                for(end in 1..raw.length) {
                    val prefix=raw.take(end);val shown=PinyinSegmentation.display(prefix,whitelist)
                    check(shown.replace("'","")==prefix.replace("'",""))
                    trace.put(JSONObject().put("raw",prefix).put("display",shown).put("boundaryTrace",PinyinSegmentation.boundaryTrace(prefix,shown)))
                }
                if(raw=="zhen")check(PinyinSegmentation.display(raw,whitelist)==raw)
                if(raw=="'ni''hao'")check(PinyinSegmentation.display(raw,whitelist)==raw)
                if(raw=="'ni''hao'")check(SpellingAssist.presentation(raw,"ni hao",PinyinSegmentation.display(raw,whitelist))==raw)
                val touchRaw=if('\'' !in raw)ui {
                    received.clear();raw.forEach {ch->tap(letters.single {it.letters==ch.toString()})};received.joinToString("")
                }else null
                if(touchRaw!=null)check(touchRaw==raw) {"Physical key stream changed $raw -> $touchRaw"}
                val words=PinyinEngine.candidates(raw.map(Char::toString))
                output.put(JSONObject().put("kind","pinyin").put("input",raw).put("trace",trace)
                    .put("touchRaw",touchRaw ?: JSONObject.NULL)
                    .put("candidates",JSONArray(words.take(20).map {JSONObject().put("word",it.w).put("reading",it.py ?: "").put("consumed",it.n)})))
            }
            val cal=i.startActivitySync(Intent(ctx,TouchCalibrationActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            calibrationActivity=cal
            fun all(v:View):List<View> = listOf(v)+if(v is android.view.ViewGroup)(0 until v.childCount).flatMap {all(v.getChildAt(it))}else emptyList()
            i.waitForIdleSync()
            ui {
                val children=all(cal.window.decorView)
                board=children.filterIsInstance<KeyboardView>().single()
                val start=children.filterIsInstance<android.widget.Button>().single {it.text=="开始校准"}
                start.performClick()
                repeat(78) {
                    val wanted=checkNotNull(board.calibrationTarget)
                    val rows=rowField.get(board) as List<*>
                    val key=rows.filterIsInstance<Row>().flatMap {it.keys}.single {it.kind==Kind.LETTER && it.letters==wanted}
                    tap(key,dx=key.rect.width()*.05f)
                }
                check(board.calibrationTarget==null && start.text=="保存校准")
                start.performClick()
                val model=TouchCalibration(ctx)
                check(prefs.getBoolean(TouchCalibration.ENABLED,false) && model.bias(board.touchProfile,"q").x>.005f)
                children.filterIsInstance<android.widget.Button>().single {it.text=="清空校准"}.performClick()
                check(!prefs.getBoolean(TouchCalibration.ENABLED,true) && prefs.all.keys.none {it.startsWith(TouchCalibration.PREFIX)})
            }
            val auto=i.getUiAutomation(android.app.UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
            i.waitForIdleSync();SystemClock.sleep(300)
            auto.takeScreenshot()?.also {bitmap->File(ctx.getExternalFilesDir(null),"touch-calibration.png").outputStream().use {bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)};bitmap.recycle()}
            report.put("passed",true)
            i.sendStatus(0,Bundle().apply {putString("stream","TOUCH RECOGNITION PASSED: 234 labelled taps, pointer IDs/order and refresh, drag/cancel/palm/stylus, bounded gaps, calibration decay/clear, pinyin raw/display preservation\n")})
        } finally {
            ui {
                val edit=prefs.edit().clear();saved.forEach {(k,v)->when(v){is String->edit.putString(k,v);is Boolean->edit.putBoolean(k,v);is Int->edit.putInt(k,v);is Long->edit.putLong(k,v);is Float->edit.putFloat(k,v);is Set<*> -> @Suppress("UNCHECKED_CAST")edit.putStringSet(k,v as Set<String>)}};edit.commit()
                activity.finish()
                calibrationActivity?.finish()
            }
            val file=File(ctx.getExternalFilesDir(null),"touch-recognition.json");file.writeText(report.toString(2))
        }
    }
}
