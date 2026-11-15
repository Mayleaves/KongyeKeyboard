package com.kongye.keyboard

import android.content.Context
import android.graphics.Typeface
import android.text.SpannableString
import android.text.Spanned
import android.text.style.AbsoluteSizeSpan
import android.text.style.StyleSpan
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/** Stable four-column candidate surface; only its data changes during filtering. */
class ExpandedCandidates(
    context: Context, private val skin: Skin, items: List<String>, mode: Int,
    private val onMode: (Int) -> Unit, private val onPick: (Int) -> Unit,
    onDelete: () -> Unit, onBack: () -> Unit, labels: List<String?> = emptyList(),
    onOverflow: (String?) -> Unit = {},
) : LinearLayout(context) {
    private var all=items
    private var readings=labels
    private var filter=if(mode==1) 1 else 0
    private var indexes=emptyList<Int>()
    private val scroll=ScrollView(context)
    private val grid=CandFlowView(context,skin,emptyList()) { i -> indexes.getOrNull(i)?.let { original ->
        // Reset before the pick callback: partial picks can submit the remaining candidates immediately.
        if(filter==1) {filter=0;onMode(0);refresh()}
        onPick(original)
    } }
    private val modeButton=TextView(context)
    init {
        orientation=HORIZONTAL
        val d=resources.displayMetrics.density
        grid.onOverflow=onOverflow
        scroll.isVerticalScrollBarEnabled=false;scroll.addView(grid)
        addView(scroll,lp(0,MATCH,1f))
        addView(LinearLayout(context).apply {
            orientation=VERTICAL;setPadding(0,(6*d).toInt(),(4*d).toInt(),(6*d).toInt())
            addView(IconButton(context,R.drawable.ic_del,skin.modFg,22f,onDelete).apply {
                contentDescription="删除";background=roundBg(skin.modBg,10*d);repeatOnHold(onDelete)
            },lp(MATCH,0,1f).apply {bottomMargin=(6*d).toInt()})
            modeButton.apply {
                textSize=12f;setSingleLine();gravity=Gravity.CENTER;setTextColor(skin.modFg);background=roundBg(skin.modBg,10*d)
                setOnClickListener {filter=1-filter;onMode(filter);refresh()}
            }
            addView(modeButton,lp(MATCH,0,1f).apply {bottomMargin=(6*d).toInt()})
            addView(IconButton(context,R.drawable.ic_back,skin.modFg,22f,onBack).apply {
                contentDescription="返回";background=roundBg(skin.modBg,10*d)
            },lp(MATCH,0,1f))
        },lp((44*d).toInt(),MATCH))
        refresh()
    }
    fun submit(items:List<String>,labels:List<String?> = emptyList()) {
        if(all==items && readings==labels) return
        all=items;readings=labels;refresh()
    }
    private fun refresh() {
        fun length(i:Int)=all[i].codePointCount(0,all[i].length)
        indexes=all.indices.filter {filter==0 || length(it)==1}
            .sortedBy {i ->
                when {
                    i==0 -> -1 // Keep the native best candidate first, even if it is short.
                    all[i].all {c->c.code<128} -> 5
                    all[i].none {c->c in '一'..'鿿'} -> 4
                    length(i)>=4 -> 0
                    length(i)==3 -> 1
                    length(i)==2 -> 2
                    else -> 3
                }
            }
        val selected=if(filter==0) 0 else 2
        modeButton.text=SpannableString("词/字").apply {
            setSpan(AbsoluteSizeSpan(18,true),selected,selected+1,Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            setSpan(StyleSpan(Typeface.BOLD),selected,selected+1,Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        modeButton.contentDescription=if(filter==0) "词/字，当前词，点击切换字" else "词/字，当前字，点击切换词"
        grid.submit(indexes.map {all[it]},indexes.map {readings.getOrNull(it)})
        scroll.scrollTo(0,0)
    }
}
