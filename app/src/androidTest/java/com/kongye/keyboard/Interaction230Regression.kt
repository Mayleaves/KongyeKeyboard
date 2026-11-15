package com.kongye.keyboard

import android.app.Instrumentation
import android.content.*
import android.graphics.RectF
import android.os.*
import android.view.*
import android.widget.*
import kotlin.math.*

object Interaction230Regression {
    fun run(i:Instrumentation) {
        fun report(s:String)=i.sendStatus(0,Bundle().apply {putString("stream",s+"\n")})
        val ctx=i.targetContext
        Lexicon.load(ctx);FcitxEngine.initialize(ctx)
        for(raw in listOf("woianu","woaini","nain","jeude","nihoa","xainzai")) {
            val out=FcitxEngine.candidates(raw.map {it.toString()}) {true}
            report("generic spelling $raw => ${out.take(4).map {it.w}}; variants=${SpellingAssist.transpositions(raw)}")
            check(out.size>1)
            if(raw=="woianu") check(out.first().w=="我爱你")
            if(raw=="jeude") check(out.first().w=="觉得")
            val first=out.first()
            check(FcitxEngine.consume(first,false) in 1..raw.length)
        }
        val long=FcitxEngine.candidates("nijuedzggzzmy".map {it.toString()}) {true}
        for(n in 1..3) check(long.any {it.w.length==n}) {"Missing $n-character native prefix words"}
        val short=long.first {it.w.length==3}
        val consumed=FcitxEngine.consume(short,false)
        check(consumed in 1..12) {"Short prefix swallowed the whole sentence"}
        check(SpellingAssist.transpositions("nijuedzggzzmy").isEmpty())
        check(SpellingAssist.wordCost("nijeudezggzzmy","ni jue de zhe ge gong zuo zen me yang")==7)
        check(SpellingAssist.wordCost("woianu","wo ai ni")==15)
        check(SpellingAssist.feedback("woianu","wo ai ni").swaps.isNotEmpty())
        check(SpellingAssist.feedback("nijuedzggzzmy","ni jue de zhe ge gong zuo zen me yang").errors.none {it})
        report("Native 3/2/1-word retrieval, partial selection and spelling feedback passed")
        val keyboard=KeyboardView(ctx)
        val rows=Layouts.letters(true)
        i.runOnMainSync {keyboard.setup(Skins.KONGYE,rows);keyboard.layout(0,0,1080,keyboard.desiredHeight())}
        val keys=rows.flatMap {it.keys}.filter {it.kind==Kind.LETTER}
        val centers=keys.associate {it.label[0] to GlidePoint(it.rect.centerX(),it.rect.centerY())}
        fun trace(word:String,jitter:Float=0f):GlideTrace {
            val points=ArrayList<GlidePoint>()
            for(n in 0 until word.lastIndex) {
                val a=centers.getValue(word[n]);val b=centers.getValue(word[n+1])
                for(k in 0..10) {val t=k/10f;points.add(GlidePoint(a.x+(b.x-a.x)*t+jitter*sin(k.toFloat()),a.y+(b.y-a.y)*t+jitter*cos(k.toFloat())))}
            }
            return GlideTrace(points,centers,keys.first().rect.width())
        }
        for((zh,words) in listOf(true to listOf("nihao","zhongguo","xiexie","kaixin","jianpan"),false to listOf("hello","world","keyboard","thanks"))) for(word in words) {
            val at=SystemClock.elapsedRealtime();val matches=GlideDecoder.decode(ctx,trace(word,3f),zh)
            report("glide $word => ${matches.take(3).map {it.spelling}} [${SystemClock.elapsedRealtime()-at} ms]")
            check(matches.take(3).any {it.spelling==word}) {"Glide missed dictionary path $word"}
            if(zh && word=="zhongguo") {
                val decoded=FcitxEngine.glideCandidates(matches) {true}
                val country=decoded.first {it.w=="中国"}
                check(FcitxEngine.consume(country,false)==matches.first().spelling.length)
                report("Alternate glide spelling selectable with correct full-input consumption")
            }
        }
        check(!keyboard.glideEnabled) {"Glide must default off"}
        var glides=0;var clears=0;var deletions=0;val outputs=ArrayList<Key>()
        i.runOnMainSync {keyboard.listener=object:KeyboardView.Listener {
            override fun onGlide(trace:GlideTrace) {glides++}
            override fun onKey(key:Key) {outputs.add(key);if(key.kind==Kind.DEL) deletions++}
            override fun onLongKey(key:Key) {if(key.kind==Kind.DEL) clears++}
            override fun onPress(key:Key) {}
            override fun onCursorMove(dx:Int,dy:Int) {}
            override fun onSwipeDel(down:Boolean) {}
        }}
        fun event(action:Int,x:Float,y:Float) {i.runOnMainSync {val e=MotionEvent.obtain(SystemClock.uptimeMillis(),SystemClock.uptimeMillis(),action,x,y,0);keyboard.onTouchEvent(e);e.recycle()}}
        fun stroke(path:List<GlidePoint>) {
            event(0,path.first().x,path.first().y)
            path.drop(1).forEach {event(2,it.x,it.y)}
            event(1,path.last().x,path.last().y)
        }
        stroke(trace("nihao").points);check(glides==0)
        i.runOnMainSync {keyboard.glideEnabled=true};outputs.clear()
        stroke(trace("nihao").points);check(glides==1 && outputs.isEmpty())
        val del=rows.flatMap {it.keys}.first {it.kind==Kind.DEL}.rect
        i.runOnMainSync {keyboard.clearCompositionOnHold=true}
        event(0,del.centerX(),del.centerY());SystemClock.sleep(680);event(1,del.centerX(),del.centerY())
        check(clears==1 && deletions==0)
        i.runOnMainSync {keyboard.clearCompositionOnHold=false}
        event(0,del.centerX(),del.centerY());SystemClock.sleep(650);event(1,del.centerX(),del.centerY())
        check(deletions>=3)
        report("Default-off glide routing, enabled path and long-hold clear/delete passed")

        check(SmsCodes.extract("【测试】验证码 483921，5 分钟内有效") == "483921")
        check(SmsCodes.extract("Your verification code is 9A38B1") == "9A38B1")
        check(SmsCodes.extract("订单 483921，金额 1234.00") == null)
        check(SmsCodes.extract("联系电话 13800138000，验证码为 8264") == "8264")
        val activity=i.startActivitySync(Intent(ctx,SettingsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        try {
            val isolated=object:ContextWrapper(activity) {
                override fun getApplicationContext():Context=this
                override fun getSharedPreferences(name:String,mode:Int)=super.getSharedPreferences("qa230_"+name,mode)
            }
            KeyGestures.save(isolated,true,'q',false,"edit:copy")
            KeyGestures.save(isolated,false,'q',false,"text:☆")
            check(Layouts.letters(true,isolated).first().keys.first().upAction?.command=="copy")
            check(Layouts.letters(false,isolated).first().keys.first().upAction?.text=="☆")
            KeyGestures.reset(isolated,true)
            check(Layouts.letters(true,isolated).first().keys.first().upAction?.text=="!")
            check(Layouts.letters(false,isolated).first().keys.first().upAction?.text=="☆")
            report("Independent Chinese/English gesture persistence and restore passed")
            isolated.getSharedPreferences("kongye",0).edit().putBoolean(Prefs.SMS_CODE,true).commit()
            val cm=ctx.getSystemService(ClipboardManager::class.java)
            var before:ClipData?=null
            i.runOnMainSync {before=cm.primaryClip}
            try {
                i.runOnMainSync {
                    check(SmsCodeClipboard.copy(isolated,"483921"))
                    check(SmsCodeClipboard.ephemeral(cm.primaryClip))
                    check(cm.primaryClip!!.description.extras!!.getBoolean("android.content.extra.IS_SENSITIVE"))
                    val stored=isolated.getSharedPreferences("sms_code_expiry",0).all.toString()
                    check(!stored.contains("483921"))
                    SmsCodeClipboard.expire(isolated,System.currentTimeMillis()+SmsCodeClipboard.TTL+1)
                    check(cm.primaryClip?.getItemAt(0)?.text.toString()!="483921")
                    check(SmsCodeClipboard.copy(isolated,"483921"))
                    cm.setPrimaryClip(ClipData.newPlainText("test","new clipboard"))
                    SmsCodeClipboard.expire(isolated,System.currentTimeMillis()+SmsCodeClipboard.TTL+1)
                    check(cm.primaryClip!!.getItemAt(0).text=="new clipboard")
                }
            } finally {
                i.runOnMainSync {if(before!=null) cm.setPrimaryClip(before!!) else cm.clearPrimaryClip()}
                isolated.getSharedPreferences("kongye",0).edit().clear().commit()
                isolated.getSharedPreferences("sms_code_expiry",0).edit().clear().commit()
            }
            report("SMS extraction, sensitive ephemeral clipboard, expiry and newer-clip preservation passed")
            var overflow:String?=null;var deletes=0
            val words=listOf("这是很长的整句候选内容".repeat(8),"你觉得这个工作怎么样","三个字","两个","字")
            lateinit var expanded:ExpandedCandidates
            i.runOnMainSync {
                val root=FrameLayout(activity)
                expanded=ExpandedCandidates(activity,Skins.KONGYE,words,0,{}, {}, {deletes++}, {},onOverflow={overflow=it})
                root.addView(expanded,FrameLayout.LayoutParams(-1,650));activity.setContentView(root)
            }
            i.waitForIdleSync()
            val scroll=expanded.getChildAt(0) as ScrollView;val grid=scroll.getChildAt(0) as CandFlowView
            val f=CandFlowView::class.java.getDeclaredField("boxes").apply {isAccessible=true}
            @Suppress("UNCHECKED_CAST") val boxes=f.get(grid) as List<RectF>
            check(boxes.size==5 && boxes.first().width()>boxes.last().width()*3.5f)
            check(overflow==words.first())
            val orderField=ExpandedCandidates::class.java.getDeclaredField("indexes").apply {isAccessible=true}
            i.runOnMainSync {expanded.submit(listOf("开心","今天特别开心","😊","o(*￣▽￣*)ブ"))}
            i.waitForIdleSync()
            check((orderField.get(expanded) as List<*>).first()==0) {"Expanded layout reordered the native best candidate"}
            i.runOnMainSync {expanded.submit(words)}
            i.waitForIdleSync()
            val modeButton=((expanded.getChildAt(1) as LinearLayout).getChildAt(1) as TextView)
            i.runOnMainSync {modeButton.performClick()};i.waitForIdleSync()
            check((expanded.getChildAt(0) as ScrollView).getChildAt(0)===grid && expanded.alpha==1f)
            val delete=(expanded.getChildAt(1) as LinearLayout).getChildAt(0)
            i.runOnMainSync {val e=MotionEvent.obtain(0,0,0,delete.width/2f,delete.height/2f,0);delete.dispatchTouchEvent(e);e.recycle()}
            SystemClock.sleep(650)
            i.runOnMainSync {val e=MotionEvent.obtain(0,650,1,delete.width/2f,delete.height/2f,0);delete.dispatchTouchEvent(e);e.recycle()}
            check(deletes>=3) {"Expanded delete did not repeat"}
            report("Four-column spans, overflow, in-place word filter and candidate repeat-delete passed")
        } finally {i.runOnMainSync {activity.finish()}}
        GlideDecoder.release();FcitxEngine.clear()
    }
}
