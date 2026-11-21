package com.kongye.keyboard

import android.content.Context
import android.graphics.Typeface
import android.text.SpannableString
import android.text.Spanned
import android.text.style.AbsoluteSizeSpan
import android.text.style.StyleSpan
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/** Stable four-column candidate surface; only its data changes during filtering. */
class ExpandedCandidates(
    context: Context, private val skin: Skin, items: List<String>, mode: Int,
    private val onMode: (Int) -> Unit, private val onPick: (Int) -> Unit,
    onDelete: () -> Unit, onBack: () -> Unit, labels: List<String?> = emptyList(),
    private val onOverflow: (String?) -> Unit = {},
    onVisibleWords:(List<String>)->Unit = {},
    spellings:List<String?> = emptyList(),
    private val style:Style = Style.PINYIN,
    literals:List<String> = emptyList(),
    private val onLiteral:(String)->Unit = {},
) : LinearLayout(context) {
    enum class Style { PINYIN, ENGLISH, HANDWRITING }
    private var all=items
    private var readings=labels
    private var phonetics=spellings
    private var literalOptions=literals
    private var selectedPinyin:String?=null
    private val pinyinStrip=ScrollView(context)
    private val pinyinButtons=LinearLayout(context)
    private val tools=LinearLayout(context)
    private val content=LinearLayout(context)
    private val divider=View(context)
    private val d=resources.displayMetrics.density
    private var pinyinRowHeight=(48*d).toInt()
    private var pinyinOptions=emptyList<String>()
    private var filter=if(style==Style.PINYIN && mode==1) 1 else 0
    private var indexes=emptyList<Int>()
    private val scroll=ScrollView(context)
    private val grid=CandFlowView(context,skin,emptyList()) { i -> indexes.getOrNull(i)?.let(::selectCandidate) }
    private val modeButton=TextView(context)
    private val pageUp=IconButton(context,R.drawable.ic_chevron_up,skin.modFg,22f) {page(-1)}
    private val pageDown=IconButton(context,R.drawable.ic_chevron_down,skin.modFg,22f) {page(1)}
    init {
        orientation=HORIZONTAL
        val inset=(6*d).toInt()
        val toolGap=(4*d).toInt()
        setPadding(inset,toolGap,inset,toolGap)
        grid.onOverflow={reportOverflow()}
        grid.onVisibleWords=onVisibleWords
        scroll.isVerticalScrollBarEnabled=false
        scroll.addView(grid)
        pinyinButtons.orientation=VERTICAL
        pinyinStrip.isVerticalScrollBarEnabled=false
        pinyinStrip.addView(pinyinButtons)
        content.orientation=HORIZONTAL
        content.background=roundBg(skin.keyBg,skin.radius*d)
        content.clipToOutline=true
        content.addView(pinyinStrip,lp(0,MATCH,19f))
        divider.setBackgroundColor(withAlpha(skin.keyFg,24))
        content.addView(divider,lp((d*.5f).toInt().coerceAtLeast(1),MATCH))
        content.addView(scroll,lp(0,MATCH,81f))
        addView(content,lp(0,MATCH,84f))
        tools.orientation=VERTICAL
        fun addTool(view:View,label:String,gap:Int=toolGap) {
            view.contentDescription=label
            view.background=roundBg(skin.modBg,skin.radius*d)
            tools.addView(view,lp(MATCH,0,1f).apply {bottomMargin=gap})
        }
        modeButton.apply {
            textSize=12f;setSingleLine();gravity=Gravity.CENTER;setTextColor(skin.modFg)
            setOnClickListener {filter=1-filter;onMode(filter);refresh()}
        }
        if(style==Style.PINYIN)addTool(modeButton,"词/字")
        addTool(IconButton(context,R.drawable.ic_del,skin.modFg,22f,onDelete).apply {repeatOnHold(onDelete)},"删除")
        addTool(pageUp.apply {repeatOnHold {page(-1)}},"向上翻页")
        addTool(pageDown.apply {repeatOnHold {page(1)}},"向下翻页")
        addTool(IconButton(context,R.drawable.ic_back,skin.modFg,22f,onBack),"返回",0)
        addView(tools,lp(0,MATCH).apply {marginStart=inset})
        scroll.setOnScrollChangeListener {_,_,_,_,_ ->
            updatePageButtons()
            reportOverflow()
        }
        grid.addOnLayoutChangeListener {_,_,_,_,_,_,_,_,_ -> updatePageButtons()}
        refresh()
    }
    fun submit(items:List<String>,labels:List<String?> = emptyList(),spellings:List<String?> = phonetics,
               literals:List<String> = literalOptions) {
        if(all==items && readings==labels && phonetics==spellings && literalOptions==literals) return
        val sameWords=all==items
        val position=scroll.scrollY
        all=items;readings=labels;phonetics=spellings;literalOptions=literals;refresh()
        if(sameWords)scroll.post {scroll.scrollTo(0,position)}
    }
    private fun page(direction:Int) {
        scroll.scrollBy(0,direction*scroll.height)
    }
    private fun reportOverflow() = onOverflow(previewCandidate(null)?.second)

    fun previewCandidate(toolbarWord:String?):Pair<Int,String>? {
        if(toolbarWord!=null && indexes.firstOrNull()==0 && all.firstOrNull()==toolbarWord)return 0 to toolbarWord
        val clipped=grid.firstClippedCandidate(scroll.scrollY,scroll.scrollY+scroll.height) ?: return null
        val index=indexes.getOrNull(clipped) ?: return null
        return index to all[index]
    }

    fun selectCandidate(index:Int) {
        if(index !in indexes)return
        // Reset before the pick callback in case it submits remaining candidates.
        if(filter==1) {filter=0;onMode(0);refresh()}
        onPick(index)
    }
    private fun updatePageButtons() {
        pageUp.alpha=if(scroll.canScrollVertically(-1))1f else .47f
        pageDown.alpha=if(scroll.canScrollVertically(1))1f else .47f
    }
    override fun onMeasure(widthMeasureSpec:Int,heightMeasureSpec:Int) {
        val width=(View.MeasureSpec.getSize(widthMeasureSpec)-paddingLeft-paddingRight).coerceAtLeast(0)
        val toolParams=tools.layoutParams as LayoutParams
        val available=(width-toolParams.marginStart).coerceAtLeast(0)
        toolParams.width=maxOf((available*.16f).toInt(),(46*d).toInt().coerceAtMost(available/4))
        val height=(View.MeasureSpec.getSize(heightMeasureSpec)-paddingTop-paddingBottom).coerceAtLeast(1)
        grid.rowHeight=height/5f
        val optionHeight=(height/4).coerceAtLeast(1)
        if(pinyinRowHeight!=optionHeight) {
            pinyinRowHeight=optionHeight
            for(i in 0 until pinyinButtons.childCount) {
                val option=pinyinButtons.getChildAt(i)
                option.layoutParams=lp(MATCH,pinyinRowHeight)
            }
        }
        super.onMeasure(widthMeasureSpec,heightMeasureSpec)
    }
    private fun syllable(index:Int)=phonetics.getOrNull(index)?.let {HanReadings.plain(it).split(' ', '\'').firstOrNull {s->s.isNotEmpty()}}
    private fun updatePinyin(sounds:List<String>) {
        if(selectedPinyin !in sounds)selectedPinyin=null
        val visibility=if(sounds.isEmpty())View.GONE else View.VISIBLE
        pinyinStrip.visibility=visibility
        divider.visibility=visibility
        if(sounds!=pinyinOptions) {
            pinyinOptions=sounds
            pinyinButtons.removeAllViews()
            for(sound in sounds) pinyinButtons.addView(TextView(context).apply {
                text=sound;gravity=Gravity.CENTER;setSingleLine()
                setAutoSizeTextTypeUniformWithConfiguration(10,18,1,android.util.TypedValue.COMPLEX_UNIT_SP)
                setPadding((3*d).toInt(),0,(3*d).toInt(),0)
                contentDescription=if(style==Style.ENGLISH)"输入 $sound" else "选择拼音 $sound"
                setOnClickListener {
                    if(style==Style.ENGLISH)onLiteral(sound)
                    else {selectedPinyin=if(selectedPinyin==sound)null else sound;refresh()}
                }
            },lp(MATCH,pinyinRowHeight))
        }
        for((index,sound) in sounds.withIndex()) {
            val option=pinyinButtons.getChildAt(index) as TextView
            option.isSelected=sound==selectedPinyin
            option.typeface=if(option.isSelected)Typeface.DEFAULT_BOLD else Typeface.DEFAULT
            option.setTextColor(if(option.isSelected)skin.candHi else skin.candFg)
        }
    }
    private fun refresh() {
        val syllables=if(style==Style.PINYIN)all.indices.map(::syllable) else emptyList()
        updatePinyin(if(style==Style.ENGLISH)literalOptions else syllables.filterNotNull().distinct())
        indexes=all.indices.filter {i ->
            (filter==0 || all[i].codePointCount(0,all[i].length)==1) &&
                (selectedPinyin==null || syllables[i]==selectedPinyin)
        }
        val selected=if(filter==0) 0 else 2
        modeButton.text=SpannableString("词/字").apply {
            setSpan(AbsoluteSizeSpan(18,true),selected,selected+1,Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            setSpan(StyleSpan(Typeface.BOLD),selected,selected+1,Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        modeButton.contentDescription=if(filter==0) "词/字，当前词，点击切换字" else "词/字，当前字，点击切换词"
        grid.submit(indexes.map {all[it]},indexes.map {readings.getOrNull(it)})
        scroll.scrollTo(0,0)
        scroll.post {updatePageButtons()}
    }
}
