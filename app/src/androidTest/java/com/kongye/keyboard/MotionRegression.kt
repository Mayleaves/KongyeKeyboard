package com.kongye.keyboard

import android.app.Instrumentation
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.view.View
import android.widget.FrameLayout

object MotionRegression {
    @JvmStatic fun run(inst:Instrumentation) {
        lateinit var host:FrameLayout
        lateinit var content:FrameLayout
        val background=Color.rgb(235,237,241)
        fun ui(action:()->Unit) {var failure:Throwable?=null;inst.runOnMainSync {try {action()} catch(t:Throwable) {failure=t}};failure?.let {throw it}}
        fun unchangedBackground() {
            check(content.alpha==1f) {"Transition changed the whole keyboard opacity"}
            val bitmap=Bitmap.createBitmap(600,500,Bitmap.Config.ARGB_8888)
            host.draw(Canvas(bitmap))
            check(bitmap.getPixel(300,250)==background) {"Transition darkened an unchanged background"}
            bitmap.recycle()
        }
        ui {
            host=FrameLayout(inst.targetContext).apply {setBackgroundColor(Color.BLACK)}
            content=FrameLayout(inst.targetContext).apply {setBackgroundColor(background)}
            host.addView(content,FrameLayout.LayoutParams(600,500))
            host.measure(View.MeasureSpec.makeMeasureSpec(600,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(500,View.MeasureSpec.EXACTLY))
            host.layout(0,0,600,500)
            NativeMotion.swap(host,content) {NativeMotion.swap(host,content) {content.setBackgroundColor(background)}}
            content.viewTreeObserver.dispatchOnPreDraw()
            unchangedBackground()
        }
        Thread.sleep(100)
        ui {unchangedBackground()}
        Thread.sleep(180)
        ui {
            unchangedBackground()
            check(host.childCount==1) {"Nested tool transition left a stale cover over the new panel"}
            NativeMotion.pulse(content);check(content.alpha==1f)
            NativeMotion.enter(content);check(content.alpha==1f)
            NativeMotion.change(content) {};check(content.alpha==1f)
        }
    }
}
