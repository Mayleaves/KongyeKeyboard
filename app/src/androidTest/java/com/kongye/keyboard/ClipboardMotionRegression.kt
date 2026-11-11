package com.kongye.keyboard

import android.app.Instrumentation
import android.content.Context
import android.content.ContextWrapper
import android.view.View
import android.view.ViewGroup
import android.widget.ScrollView
import android.widget.TextView
import java.io.File

object ClipboardMotionRegression {
    fun run(i: Instrumentation) {
        val isolated=object:ContextWrapper(i.targetContext) {
            override fun getFilesDir()=File(super.getCacheDir(),"clipboard-motion").apply {mkdirs()}
            override fun getApplicationContext():Context=this
            override fun getSharedPreferences(name:String,mode:Int)=super.getSharedPreferences("clip-motion-$name",mode)
        }
        ClipStore.load(isolated)
        ClipStore.items.clear()
        lateinit var panel:ClipboardPanel
        var selected="全部"
        fun labels(view:View):List<TextView> = when(view) {
            is TextView -> listOf(view)
            is ViewGroup -> (0 until view.childCount).flatMap {labels(view.getChildAt(it))}
            else -> emptyList()
        }
        i.runOnMainSync {
            val skin=SinkStrip(isolated,{},{}).skin
            panel=ClipboardPanel(isolated,skin,"全部","",null,null,{selected=it}, {}, {}, {}, {}, {}, {}, {}, {}, {_,_->})
            panel.measure(View.MeasureSpec.makeMeasureSpec(1080,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(600,View.MeasureSpec.EXACTLY))
            panel.layout(0,0,1080,600)
            for (category in listOf("置顶","文本","图片","全部","文本")) {
                labels(panel).first {it.text.toString()==category}.performClick()
                val scroll=panel.getChildAt(panel.childCount-1) as ScrollView
                check(scroll.translationX==0f) {"Empty clipboard guidance moved on category switch"}
                check(labels(scroll).any {it.text.toString()=="复制的内容会出现在这里"})
            }
            val scroll=panel.getChildAt(panel.childCount-1) as ScrollView
            val y=scroll.top+80f
            fun swipe(from:Float,to:Float) {
                val now=android.os.SystemClock.uptimeMillis()
                val down=android.view.MotionEvent.obtain(now,now,android.view.MotionEvent.ACTION_DOWN,from,y,0)
                val move=android.view.MotionEvent.obtain(now,now+80,android.view.MotionEvent.ACTION_MOVE,to,y,0)
                val up=android.view.MotionEvent.obtain(now,now+100,android.view.MotionEvent.ACTION_UP,to,y,0)
                try {
                    panel.onInterceptTouchEvent(down)
                    check(panel.onInterceptTouchEvent(move)) {"Horizontal clipboard swipe was not intercepted"}
                    panel.onTouchEvent(move);panel.onTouchEvent(up)
                } finally {down.recycle();move.recycle();up.recycle()}
            }
            swipe(850f,200f)
            check(selected=="图片" && scroll.translationX==0f) {"Left swipe did not select the next category with fixed empty guidance"}
            swipe(200f,850f)
            check(selected=="文本") {"Right swipe did not restore the previous category"}
        }
        // Exercise real record pages as well as empty guidance, and ensure
        // viewport snapshots are released after the transition.
        ClipStore.add("isolated pinned card")
        ClipStore.togglePin(ClipStore.items.first())
        ClipStore.add("isolated history card")
        i.runOnMainSync {
            val skin=SinkStrip(isolated,{},{}).skin
            panel=ClipboardPanel(isolated,skin,"全部","",null,null,{selected=it}, {}, {}, {}, {}, {}, {}, {}, {}, {_,_->})
            panel.measure(View.MeasureSpec.makeMeasureSpec(1080,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(600,View.MeasureSpec.EXACTLY))
            panel.layout(0,0,1080,600)
            labels(panel).first {it.text.toString()=="置顶"}.performClick()
        }
        Thread.sleep(650)
        i.runOnMainSync {
            check(selected=="置顶") {"Record category did not settle on the requested category"}
            val scroll=panel.getChildAt(panel.childCount-1) as ScrollView
            check(scroll.translationX==0f && labels(scroll).any {it.text.toString()=="isolated pinned card"} && labels(scroll).none {it.text.toString()=="isolated history card"})
            for(name in listOf("recordsAnimator","recordsBitmap","recordsCover")) {
                val field=ClipboardPanel::class.java.getDeclaredField(name);field.isAccessible=true
                check(field.get(panel)==null) {"Category animation retained $name after finishing"}
            }
        }
    }
}
