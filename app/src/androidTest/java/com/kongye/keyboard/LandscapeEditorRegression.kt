package com.kongye.keyboard

import android.app.Instrumentation
import android.content.res.Configuration
import android.view.MotionEvent
import android.view.View

object LandscapeEditorRegression {
    private class Hit(val action:String,val x:Float,val y:Float)
    @JvmStatic fun run(inst:Instrumentation) {
        var failure:Throwable?=null
        inst.runOnMainSync {
            try {
                val config=Configuration(inst.targetContext.resources.configuration).apply {orientation=Configuration.ORIENTATION_LANDSCAPE}
                val context=inst.targetContext.createConfigurationContext(config)
                val d=context.resources.displayMetrics.density
                val tapped=ArrayList<String>()
                val strip=SinkStrip(context,{},{})
                val panel=EditPanelView(context,strip.skin,false) {tapped.add(it)}
                panel.measure(View.MeasureSpec.makeMeasureSpec((900*d).toInt(),View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec((168*d).toInt(),View.MeasureSpec.EXACTLY))
                panel.layout(0,0,panel.measuredWidth,panel.measuredHeight)
                val actions=listOf(
                    Hit("undo",156f,31f),Hit("redo",296f,31f),Hit("home",156f,84f),Hit("cut",296f,84f),Hit("copy",226f,137f),
                    Hit("end",604f,31f),Hit("all",744f,31f),Hit("del",604f,84f),Hit("paste",744f,84f),Hit("enter",674f,137f),
                    Hit("select",450f,84f),Hit("left",406f,84f),Hit("right",494f,84f),Hit("up",450f,40f),Hit("down",450f,128f))
                actions.forEach {hit ->
                    val action=hit.action;val x=hit.x;val y=hit.y
                    val now=android.os.SystemClock.uptimeMillis()
                    for(type in listOf(MotionEvent.ACTION_DOWN,MotionEvent.ACTION_UP)) {
                        val event=MotionEvent.obtain(now,now,type,x*d,y*d,0)
                        panel.onTouchEvent(event);event.recycle()
                    }
                    check(tapped.lastOrNull()==action) {"Landscape hit area missed $action"}
                }
                strip.localEditing=true;strip.multiline=true;strip.caption="UU · 本次输入，点勾同步到远端";strip.value="横屏测试"
                strip.measure(View.MeasureSpec.makeMeasureSpec((744*d).toInt(),View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(0,View.MeasureSpec.UNSPECIFIED))
                strip.layout(0,0,strip.measuredWidth,strip.measuredHeight)
                check(strip.height<=66*d && strip.editor.height>=20*d) {"Landscape preview remained too tall"}
            } catch(t:Throwable) {failure=t}
        }
        failure?.let {throw it}
    }
}
