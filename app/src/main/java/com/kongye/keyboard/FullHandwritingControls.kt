package com.kongye.keyboard

import android.content.Context
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.LinearLayout

/** Compact full-screen controls; the large grey canvas stays above this row. */
@androidx.annotation.Keep
class FullHandwritingControls(context:Context,skin:Skin,private val pad:HandwriteView,
    referenceSize:android.graphics.PointF,onAction:(String)->Unit):LinearLayout(context) {
    private val undo:IconButton
    private val redo:IconButton
    private var actionWidth=referenceSize.x.toInt().coerceAtLeast(1)
    private var actionHeight=referenceSize.y.toInt().coerceAtLeast(1)
    private val minimumGap=(6*resources.displayMetrics.density).toInt()
    init {
        orientation=HORIZONTAL;gravity=Gravity.CENTER_VERTICAL;contentDescription="全屏手写操作栏"
        val d=resources.displayMetrics.density
        setPadding((6*d).toInt(),(4*d).toInt(),(6*d).toInt(),(4*d).toInt())
        setBackgroundColor(skin.kbBg)
        fun button(label:String,icon:Int,action:()->Unit):IconButton {
            val holder=FrameLayout(context).apply {background=roundBg(skin.modBg,12*d)}
            addView(holder,LayoutParams(actionWidth,actionHeight))
            return IconButton(context,icon,skin.modFg,22f,action).also {
                it.contentDescription=label
                holder.addView(it,FrameLayout.LayoutParams(-1,-1))
            }
        }
        button("收回全屏手写",R.drawable.ic_hand_collapse) {onAction("fullscreen")}
        undo=button("撤销笔画",R.drawable.ic_undo) {pad.undoStroke();refresh()}
        redo=button("重做笔画",R.drawable.ic_redo) {pad.redoStroke();refresh()}
        button("空格",R.drawable.ic_space) {onAction("space")}
        button("删除",R.drawable.ic_del) {onAction("del")}
        button("回车",R.drawable.ic_enter) {onAction("enter")}
        refresh()
    }
    fun updateActionSize(size:android.graphics.PointF) {
        val w=size.x.toInt().coerceAtLeast(1);val h=size.y.toInt().coerceAtLeast(1)
        if(w!=actionWidth || h!=actionHeight) {actionWidth=w;actionHeight=h;requestLayout()}
    }
    override fun onMeasure(widthMeasureSpec:Int,heightMeasureSpec:Int) {
        val count=childCount.coerceAtLeast(1)
        val available=if(MeasureSpec.getMode(widthMeasureSpec)==MeasureSpec.UNSPECIFIED)
            actionWidth*count+minimumGap*(count-1) else (MeasureSpec.getSize(widthMeasureSpec)-paddingLeft-paddingRight).coerceAtLeast(0)
        // Match the normal Enter key, spreading spare width as equal gaps. Only
        // extremely narrow rows shrink width so all six actions remain reachable.
        val gapFloor=minimumGap.coerceAtMost(if(count>1)available/(count-1)else 0)
        val buttonWidth=actionWidth.coerceAtMost(((available-gapFloor*(count-1))/count).coerceAtLeast(1))
        val spare=(available-buttonWidth*count).coerceAtLeast(0)
        for(index in 0 until count) {
            val params=getChildAt(index).layoutParams as LayoutParams
            val gap=if(index==0 || count==1)0 else spare*index/(count-1)-spare*(index-1)/(count-1)
            if(params.width!=buttonWidth || params.height!=actionHeight || params.marginStart!=gap) {
                params.width=buttonWidth;params.height=actionHeight;params.marginStart=gap
                getChildAt(index).layoutParams=params
            }
        }
        super.onMeasure(widthMeasureSpec,heightMeasureSpec)
    }
    fun refresh() {
        undo.isEnabled=pad.canUndo;undo.alpha=if(pad.canUndo)1f else .35f
        redo.isEnabled=pad.canRedo;redo.alpha=if(pad.canRedo)1f else .35f
    }
}
