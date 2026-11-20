package com.kongye.keyboard

import android.content.Context
import android.graphics.Typeface
import android.text.SpannableString
import android.text.Spanned
import android.text.style.AbsoluteSizeSpan
import android.text.style.StyleSpan
import android.view.Gravity
import android.widget.HorizontalScrollView
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/** Stable four-column candidate surface; only its data changes during filtering. */
class ExpandedCandidates(
    context: Context, private val skin: Skin, items: List<String>, mode: Int,
    private val onMode: (Int) -> Unit, private val onPick: (Int) -> Unit,
    onDelete: () -> Unit, onBack: () -> Unit, labels: List<String?> = emptyList(),
    onOverflow: (String?) -> Unit = {},
    onVisibleWords:(List<String>)->Unit = {},
    spellings:List<String?> = emptyList(),
) : LinearLayout(context) {
    private var all=items
    private var readings=labels
    private var phonetics=spellings
    private var selectedPinyin:String?=null
    private val pinyinStrip=HorizontalScrollView(context)
    private val pinyinButtons=LinearLayout(context)
    private val tools=LinearLayout(context)
    private val content=LinearLayout(context)
    private val d=resources.displayMetrics.density
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
        grid.onVisibleWords=onVisibleWords
        scroll.isVerticalScrollBarEnabled=false;scroll.addView(grid)
        pinyinStrip.isHorizontalScrollBarEnabled=false
        pinyinStrip.isFillViewport=true
        pinyinStrip.addView(pinyinButtons)
        pinyinStrip.background=roundBg(skin.keyBg,10*d)
        content.orientation=VERTICAL
        content.addView(pinyinStrip,lp(MATCH,(44*d).toInt()).apply {setMargins((6*d).toInt(),(3*d).toInt(),(6*d).toInt(),0)})
        content.addView(scroll,lp(MATCH,0,1f))
        addView(content,lp(0,MATCH,1f))
        tools.orientation=VERTICAL
        fun tool(icon:Int,label:String,action:()->Unit)=IconButton(context,icon,skin.modFg,22f,action).apply {
            contentDescription=label;background=roundBg(skin.modBg,12*d)
        }
        tools.addView(IconButton(context,R.drawable.ic_chevron_up,skin.barFg,22f,onBack).apply {contentDescription="收起候选"})
        tools.addView(tool(R.drawable.ic_del,"删除",onDelete).apply {repeatOnHold(onDelete)})
        tools.addView(tool(R.drawable.ic_chevron_up,"向上翻页") {page(-1)}.apply {repeatOnHold {page(-1)}})
        tools.addView(tool(R.drawable.ic_chevron_down,"向下翻页") {page(1)}.apply {repeatOnHold {page(1)}})
        modeButton.apply {
            textSize=12f;setSingleLine();gravity=Gravity.CENTER;setTextColor(skin.modFg);background=roundBg(skin.modBg,10*d)
            setOnClickListener {filter=1-filter;onMode(filter);refresh()}
        }
        tools.addView(modeButton)
        addView(tools,lp((52*d).toInt(),MATCH))
        refresh()
    }
    fun submit(items:List<String>,labels:List<String?> = emptyList(),spellings:List<String?> = phonetics) {
        if(all==items && readings==labels && phonetics==spellings) return
        val sameWords=all==items
        val position=scroll.scrollY
        all=items;readings=labels;phonetics=spellings;refresh()
        if(sameWords)scroll.post {scroll.scrollTo(0,position)}
    }
    private fun page(direction:Int) {
        val step=(grid.rowHeight+6*d)*4
        scroll.scrollBy(0,(direction*step).toInt())
    }
    override fun onMeasure(widthMeasureSpec:Int,heightMeasureSpec:Int) {
        val header=(47*d).toInt()
        val height=View.MeasureSpec.getSize(heightMeasureSpec)
        val cell=((height-header-30*d)/4).coerceAtLeast(1f)
        grid.rowHeight=cell
        tools.setPadding(0,0,(6*d).toInt(),0)
        tools.getChildAt(0).layoutParams=lp(MATCH,header).apply {bottomMargin=(6*d).toInt()}
        for(i in 1 until tools.childCount) {
            val top=header+6*d+(i-1)*(cell+6*d)
            val bottom=(top+cell).toInt()
            tools.getChildAt(i).layoutParams=lp(MATCH,bottom-top.toInt()).apply {
                if(i<tools.childCount-1)bottomMargin=(top+cell+6*d).toInt()-bottom
            }
        }
        super.onMeasure(widthMeasureSpec,heightMeasureSpec)
    }
    private fun syllable(index:Int)=phonetics.getOrNull(index)?.let {HanReadings.plain(it).split(' ', '\'').firstOrNull {s->s.isNotEmpty()}}
    private fun refresh() {
        val sounds=all.indices.mapNotNull(::syllable).distinct()
        if(selectedPinyin !in sounds)selectedPinyin=null
        pinyinStrip.visibility=if(sounds.isEmpty())View.INVISIBLE else View.VISIBLE
        pinyinButtons.removeAllViews()
        for(sound in sounds) pinyinButtons.addView(TextView(context).apply {
            text=sound;textSize=21f;gravity=Gravity.CENTER;minWidth=(56*d).toInt()
            setPadding((12*d).toInt(),0,(12*d).toInt(),0)
            setTextColor(skin.candFg)
            typeface=if(sound==selectedPinyin)Typeface.DEFAULT_BOLD else Typeface.DEFAULT
            contentDescription="选择拼音 $sound"
            setOnClickListener {selectedPinyin=if(selectedPinyin==sound)null else sound;refresh()}
        },lp(WRAP,MATCH))
        fun length(i:Int)=all[i].codePointCount(0,all[i].length)
        indexes=all.indices.filter {(filter==0 || length(it)==1) && (selectedPinyin==null || syllable(it)==selectedPinyin)}
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
