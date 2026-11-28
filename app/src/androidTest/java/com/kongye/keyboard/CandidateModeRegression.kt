package com.kongye.keyboard

import android.app.Instrumentation
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Paint
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
        val phoneticSelection=CandidatePinyinSelection()
        try {
            i.runOnMainSync {
                expanded=ExpandedCandidates(activity,Skins.KONGYE,words,mode,{mode=it},{
                    picked=it;pickMode=mode
                    expanded.submit(listOf("好的","好"))
                },{}, {},pinyinSelection=phoneticSelection)
                val root=FrameLayout(activity)
                root.setBackgroundColor(Skins.KONGYE.kbBg)
                root.addView(expanded,FrameLayout.LayoutParams(-1,(280*ctx.resources.displayMetrics.density).toInt(),Gravity.BOTTOM))
                activity.setContentView(root)
            }
            i.waitForIdleSync()
            val content=expanded.getChildAt(0) as LinearLayout
            val scroll=content.getChildAt(2) as ScrollView
            val grid=scroll.getChildAt(0) as CandFlowView
            val tools=expanded.getChildAt(1) as LinearLayout
            val button=tools.getChildAt(0) as TextView
            check((1..4).map {tools.getChildAt(it).contentDescription.toString()}==listOf("删除","向上翻页","向下翻页","返回"))
            check(tools.getChildAt(4) is IconButton)
            val barPaint=CandidateBar::class.java.getDeclaredField("text").apply {isAccessible=true}
            val gridPaint=CandFlowView::class.java.getDeclaredField("text").apply {isAccessible=true}
            i.runOnMainSync {
                val bar=CandidateBar(activity)
                bar.showCandidates(listOf(CandItem("你")),false)
                val normal=barPaint.get(bar) as Paint
                val expandedPaint=gridPaint.get(grid) as Paint
                check(normal.textSize==expandedPaint.textSize && expandedPaint.textAlign==Paint.Align.CENTER)
            }
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
            i.runOnMainSync {
                button.performClick()
                expanded.submit(listOf("从","冲","重","重新")+(0..70).map {"候选$it"},
                    spellings=listOf("cong","chong","chong","chong xin")+(0..70).map {"chong"})
            }
            i.waitForIdleSync()
            val strip=content.getChildAt(0) as ScrollView
            val picker=strip.getChildAt(0) as LinearLayout
            val chong=(0 until picker.childCount).map {picker.getChildAt(it)}.first {it.contentDescription=="选择拼音 chong"}
            i.runOnMainSync {chong.performClick()};i.waitForIdleSync()
            check(0 !in (indices.get(expanded) as List<*>)) {"Pinyin picker retained a different pronunciation"}
            val cell=(boxesField.get(grid) as List<*>).first() as RectF
            check((boxesField.get(grid) as List<*>).take(4).map {(it as RectF).width()}.distinct().size==1) {"Candidate cells are not four equal columns"}
            check(strip.left<scroll.left && content.right<tools.left)
            val buttons=(0 until tools.childCount).map(tools::getChildAt)
            check(buttons.maxOf {it.height}-buttons.minOf {it.height}<=1)
            check(buttons.last().bottom==tools.height)
            check(kotlin.math.abs(cell.height()*5-scroll.height)<=1f) {"Expected five candidate rows per page"}
            i.runOnMainSync {tools.getChildAt(3).performClick()};i.waitForIdleSync();check(scroll.scrollY==scroll.height)
            i.runOnMainSync {tools.getChildAt(2).performClick()};i.waitForIdleSync();check(scroll.scrollY==0)
            i.runOnMainSync {
                // This fixture starts a new composition, rather than refining the previous one.
                phoneticSelection.reset()
                expanded.submit(listOf("艺","一","1️⃣","以","易","宜","已","逸","衣","义","燚","翼","依","亦","医","咦"),
                    spellings=listOf("yi","yi","yi","yi","yi","yi","yi","yi","yi","yi","yi","yi","yi","yi","yi","yi"))
            }
            i.waitForIdleSync()
            check((indices.get(expanded) as List<*>)==(0..15).toList()) {"Expanded grid changed the original candidate order"}
            check((0 until picker.childCount).map {(picker.getChildAt(it) as TextView).text.toString()}==listOf("yi")) {"Unexpected All filter"}
            i.runOnMainSync {
                val sounds=listOf("yi","ti","yo","yu","ge","le","shi","zhong","chong","chang")
                expanded.submit(listOf("你","呢","尼","妮","腻","泥","妳","倪","逆","拟","伱","霓","匿","溺","昵","旎","坭","伲","铌","鲵")+(1..10).map {"候选$it"},
                    spellings=listOf("bi","ji","mi","ni")+sounds+List(16) {"ni"})
            }
            i.waitForIdleSync()
            check(picker.height>strip.height)
            i.runOnMainSync {strip.isSmoothScrollingEnabled=false;strip.fullScroll(android.view.View.FOCUS_DOWN)}
            i.waitForIdleSync();check(strip.scrollY>0) {"Pronunciation column cannot scroll vertically"}
            i.runOnMainSync {strip.fullScroll(android.view.View.FOCUS_UP)}
            i.waitForIdleSync();check(strip.scrollY==0)
            screenshot("candidate-pinyin-filter-and-pages.png")
            for(style in listOf(ExpandedCandidates.Style.PINYIN,ExpandedCandidates.Style.ENGLISH,ExpandedCandidates.Style.HANDWRITING)) {
                val words=listOf("你","中".repeat(5),"中".repeat(10),"中".repeat(16),"好")
                lateinit var wideGrid:CandFlowView
                i.runOnMainSync {
                    val sheet=ExpandedCandidates(activity,Skins.KONGYE,words,0,{}, {},{}, {},
                        spellings=List(words.size){"ni"},style=style,
                        literals=if(style==ExpandedCandidates.Style.ENGLISH)listOf("i","I","8") else emptyList())
                    val root=FrameLayout(activity)
                    root.addView(sheet,FrameLayout.LayoutParams(-1,expanded.height,Gravity.BOTTOM))
                    activity.setContentView(root)
                    wideGrid=(((sheet.getChildAt(0) as LinearLayout).getChildAt(2) as ScrollView).getChildAt(0) as CandFlowView)
                }
                i.waitForIdleSync()
                val cells=(boxesField.get(wideGrid) as List<*>).map {it as RectF}
                val spans=cells.map {kotlin.math.round(it.width()/cells.first().width()).toInt()}
                check(spans.zipWithNext().take(3).all {(a,b)->a<=b} && spans[1]>=2 && spans[3]==4) {"Unexpected spans for $style: $spans"}
                check(cells[3].left==0f && cells[4].top>cells[3].top)
            }
            i.sendStatus(0,Bundle().apply {putString("stream","Vertical pinyin, five-row pages, action order, original-index selection and automatic word reset PASSED\n")})
        } finally {i.runOnMainSync {activity.finish()}}
    }
}
