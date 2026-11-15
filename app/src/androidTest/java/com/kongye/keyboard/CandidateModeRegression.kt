package com.kongye.keyboard

import android.app.Instrumentation
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Bundle
import android.os.SystemClock
import android.text.Spanned
import android.text.style.AbsoluteSizeSpan
import android.text.style.StyleSpan
import android.view.Gravity
import android.view.MotionEvent
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.io.File

/** Real release widgets: two states, original-index selection and reset before partial refresh. */
object CandidateModeRegression {
    fun run(i:Instrumentation) {
        val ctx=i.targetContext
        val activity=i.startActivitySync(Intent(ctx,SettingsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        var mode=0;var picked=-1;var pickMode=-1
        val words=listOf("你觉得这个工作怎么样","你觉得","你好","你","好")
        lateinit var expanded:ExpandedCandidates
        try {
            i.runOnMainSync {
                expanded=ExpandedCandidates(activity,Skins.KONGYE,words,mode,{mode=it},{
                    picked=it;pickMode=mode
                    expanded.submit(listOf("好的","好"))
                },{}, {})
                val root=FrameLayout(activity)
                root.setBackgroundColor(Skins.KONGYE.modBg)
                root.addView(expanded,FrameLayout.LayoutParams(-1,(280*ctx.resources.displayMetrics.density).toInt(),Gravity.BOTTOM))
                activity.setContentView(root)
            }
            i.waitForIdleSync()
            val scroll=expanded.getChildAt(0) as ScrollView
            val grid=scroll.getChildAt(0) as CandFlowView
            val button=(expanded.getChildAt(1) as LinearLayout).getChildAt(1) as TextView
            val indices=ExpandedCandidates::class.java.getDeclaredField("indexes").apply {isAccessible=true}
            fun checkLabel(selected:Int) {
                check(button.text.toString()=="词/字")
                val label=button.text as Spanned
                val bold=label.getSpans(0,3,StyleSpan::class.java)
                val size=label.getSpans(0,3,AbsoluteSizeSpan::class.java)
                check(bold.size==1 && bold[0].style==Typeface.BOLD && label.getSpanStart(bold[0])==selected)
                check(size.size==1 && size[0].size>button.textSize/ctx.resources.displayMetrics.scaledDensity && label.getSpanStart(size[0])==selected)
            }
            fun screenshot(name:String) {
                SystemClock.sleep(180);i.waitForIdleSync()
                val bitmap=i.uiAutomation.takeScreenshot() ?: error("Missing screenshot")
                File(ctx.getExternalFilesDir(null),name).outputStream().use {bitmap.compress(Bitmap.CompressFormat.PNG,100,it)}
                bitmap.recycle()
            }
            checkLabel(0);check((indices.get(expanded) as List<*>).size==5)
            screenshot("candidate-mode-words.png")
            repeat(4) { turn ->
                i.runOnMainSync {button.performClick()};i.waitForIdleSync()
                check(mode==if(turn%2==0) 1 else 0)
                checkLabel(if(mode==0) 0 else 2)
                check(scroll.getChildAt(0)===grid && expanded.alpha==1f)
            }
            i.runOnMainSync {button.performClick()};i.waitForIdleSync()
            check((indices.get(expanded) as List<*>)==listOf(3,4));checkLabel(2)
            screenshot("candidate-mode-characters.png")
            val boxesField=CandFlowView::class.java.getDeclaredField("boxes").apply {isAccessible=true}
            @Suppress("UNCHECKED_CAST") val box=(boxesField.get(grid) as List<RectF>)[0]
            i.runOnMainSync {
                val time=SystemClock.uptimeMillis()
                for(action in intArrayOf(MotionEvent.ACTION_DOWN,MotionEvent.ACTION_UP)) {
                    val e=MotionEvent.obtain(time,time,action,box.centerX(),box.centerY(),0)
                    grid.onTouchEvent(e);e.recycle()
                }
            }
            i.waitForIdleSync()
            check(picked==3 && pickMode==0 && mode==0)
            checkLabel(0);check((indices.get(expanded) as List<*>).size==2)
            screenshot("candidate-mode-after-pick.png")
            // Handwriting uses this same surface, with pronunciation subtitles.
            i.runOnMainSync {expanded.submit(listOf("你好","你","好"),listOf("ni hao","ni","hao"));button.performClick()}
            i.waitForIdleSync();checkLabel(2)
            check((indices.get(expanded) as List<*>)==listOf(1,2))
            i.sendStatus(0,Bundle().apply {putString("stream","Combined label, selected typography, two-state cycling, stable grid, original-index pick and automatic word reset PASSED\n")})
        } finally {i.runOnMainSync {activity.finish()}}
    }
}
