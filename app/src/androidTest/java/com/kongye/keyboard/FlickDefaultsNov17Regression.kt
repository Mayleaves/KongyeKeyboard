package com.kongye.keyboard

import android.app.Instrumentation
import android.content.Context
import android.content.ContextWrapper
import android.os.Bundle

object FlickDefaultsNov17Regression {
    fun run(i:Instrumentation) {
        val ctx=object:ContextWrapper(i.targetContext) {
            override fun getApplicationContext():Context=this
            override fun getSharedPreferences(name:String,mode:Int)=super.getSharedPreferences("nov17_defaults_"+name,mode)
        }
        fun icon(name:String)=ctx.resources.getIdentifier(name,"drawable",ctx.packageName).also {check(it!=0)}
        Prefs.of(ctx).edit().clear().commit()
        fun keys(zh:Boolean)=Layouts.letters(zh,ctx).flatMap {it.keys}.filter {it.kind==Kind.LETTER}.associateBy {it.letters.single()}
        try {
            val common="qwertyuiop".zip("1234567890").associate {it.first to it.second.toString()}
            val cn=common+"asdfghjkl".toList().zip(listOf("～","！","@","/","%","“","”","*","？"))+"zxcvbnm".toList().zip(listOf("（","）","-","_","：","；","、"))
            val en=common+"asdfghjkl".toList().zip(listOf("~","!","@","#","%","'","&","*","?"))+"zxcvbnm".toList().zip(listOf("(",")","-","_",":",";","/"))
            for((zh,wanted) in listOf(true to cn,false to en)) {
                val actual=keys(zh);check(actual.size==26)
                for((letter,text) in wanted) {check(actual.getValue(letter).downAction?.text==text) {"$zh $letter"};check(actual.getValue(letter).upAction==null&&actual.getValue(letter).hint2==null)}
            }
            KeyGestures.save(ctx,true,'q',false,"edit:copy");KeyGestures.save(ctx,false,'q',false,"text:☆")
            check(keys(true).getValue('q').upAction?.command=="copy");check(keys(false).getValue('q').upAction?.text=="☆")
            check(keys(true).getValue('q').hint2Icon==icon("ic_copy"))
            Prefs.of(ctx).edit().putBoolean(V3Prefs.SWIPE_REVERSE,true).commit()
            check(keys(true).getValue('q').hint2Icon==icon("ic_copy")&&keys(true).getValue('q').downAction?.command=="copy")
            Prefs.of(ctx).edit().putBoolean(V3Prefs.SWIPE_REVERSE,false).commit()
            check(KeyGestures.commands.keys.all {KeyGestures.icon(it)!=0})
            var iconFailure:Throwable?=null
            i.runOnMainSync {try {
                fun frame(label:String,icon:Int):android.graphics.Bitmap {
                    val key=Key(Kind.LETTER,"q",hint=label).apply {hintIcon=icon}
                    val board=KeyboardView(ctx);board.setup(Skins.KONGYE,listOf(Row(listOf(key))))
                    board.measure(android.view.View.MeasureSpec.makeMeasureSpec(800,android.view.View.MeasureSpec.EXACTLY),0)
                    board.layout(0,0,board.measuredWidth,board.measuredHeight)
                    return android.graphics.Bitmap.createBitmap(board.width,board.height,android.graphics.Bitmap.Config.ARGB_8888).also {board.draw(android.graphics.Canvas(it))}
                }
                val copy=frame("复制",icon("ic_copy"));val changedLabel=frame("不应显示这些中文",icon("ic_copy"));val undo=frame("复制",icon("ic_undo"))
                check(copy.sameAs(changedLabel)) {"Command hint still draws Chinese instead of its icon"}
                check(!copy.sameAs(undo)) {"Different edit command icons render identically"}
                copy.recycle();changedLabel.recycle();undo.recycle()
            }catch(t:Throwable){iconFailure=t}}
            iconFailure?.let {throw it}

            KeyGestures.clear(ctx,true);check(keys(true).values.all {it.upAction==null&&it.downAction==null});check(keys(false).getValue('q').upAction?.text=="☆")
            KeyGestures.reset(ctx,true);check(keys(true).getValue('q').downAction?.text=="1"&&keys(true).getValue('q').upAction==null);check(keys(false).getValue('q').upAction?.text=="☆")
            val normal=keys(true)
            Prefs.of(ctx).edit().putBoolean(V3Prefs.SWIPE_REVERSE,true).commit()
            check(keys(true).getValue('q').downAction==null&&keys(true).getValue('q').upAction?.text=="1")
            val reversed=keys(true)
            check(normal.keys.all {normal.getValue(it).hint==reversed.getValue(it).hint&&normal.getValue(it).hint2==reversed.getValue(it).hint2}) {"Reversal moved keycap legends"}
            check(reversed.getValue('m').hint=="、"&&reversed.getValue('m').upAction?.text=="、")
            i.runOnMainSync {
                val emitted=ArrayList<Key>()
                val view=KeyboardView(ctx).apply {longMs=10000;listener=object:KeyboardView.Listener {
                    override fun onKey(key:Key){emitted.add(key)};override fun onLongKey(key:Key){};override fun onPress(key:Key){}
                    override fun onCursorMove(dx:Int,dy:Int){};override fun onSwipeDel(down:Boolean){}
                }}
                view.setup(Skins.KONGYE,Layouts.letters(true,ctx))
                view.measure(android.view.View.MeasureSpec.makeMeasureSpec(1080,android.view.View.MeasureSpec.EXACTLY),0)
                view.layout(0,0,view.measuredWidth,view.measuredHeight)
                for(reverse in listOf(false,true)) {
                    Prefs.of(ctx).edit().putBoolean(V3Prefs.SWIPE_REVERSE,reverse).commit()
                    val rows=Layouts.letters(true,ctx);view.setup(Skins.KONGYE,rows)
                    val m=rows.flatMap {it.keys}.first {it.kind==Kind.LETTER&&it.letters=="m"}
                    fun flick(down:Boolean):List<String> {
                        emitted.clear();val start=android.os.SystemClock.uptimeMillis();val x=m.rect.centerX();val y=m.rect.centerY();val endY=y+(if(down)1 else -1)*m.rect.height()*.6f
                        for((action,atY) in listOf(0 to y,2 to endY,1 to endY)) {
                            val e=android.view.MotionEvent.obtain(start,android.os.SystemClock.uptimeMillis(),action,x,atY,0);view.onTouchEvent(e);e.recycle()
                        }
                        return emitted.map {it.out}
                    }
                    check(m.hint=="、"&&m.hint2==null)
                    check(flick(!reverse)==listOf("、")) {"Reversed M gesture did not emit punctuation"}
                    check(flick(reverse).isEmpty()) {"Empty slot used the opposite legend as fallback"}
                }
            }
            check(Layouts.fourteen().first().keys.first().hint=="1"&&Layouts.fourteen().first().keys.first().hint2=="2")
        } finally {Prefs.of(ctx).edit().clear().commit()}
        i.sendStatus(0,Bundle().apply {putString("stream","NOV17 FLICK DEFAULTS PASSED: all 52 language mappings, empty up flicks, custom overrides, independent clear/reset, reverse direction with fixed legends, actual M swipes, edit-command icon pixels with no Chinese labels, empty-slot behavior and unchanged 14-key layout\n")})
    }
}
