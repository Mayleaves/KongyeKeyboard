package com.kongye.keyboard

import android.content.Context
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.LinearLayout

/** Compact full-screen controls; the large grey canvas stays above this row. */
@androidx.annotation.Keep
class FullHandwritingControls(context:Context,skin:Skin,private val pad:HandwriteView,onAction:(String)->Unit):LinearLayout(context) {
    private val undo:IconButton
    private val redo:IconButton
    init {
        orientation=HORIZONTAL;gravity=Gravity.CENTER_VERTICAL;contentDescription="全屏手写操作栏"
        val d=resources.displayMetrics.density
        setPadding((6*d).toInt(),(4*d).toInt(),(6*d).toInt(),(4*d).toInt())
        setBackgroundColor(skin.kbBg)
        fun button(label:String,icon:Int,action:()->Unit):IconButton {
            val holder=FrameLayout(context).apply {background=roundBg(skin.modBg,12*d)}
            addView(holder,LayoutParams(0,(48*d).toInt(),1f).apply {if(childCount>0)marginStart=(6*d).toInt()})
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
    fun refresh() {
        undo.isEnabled=pad.canUndo;undo.alpha=if(pad.canUndo)1f else .35f
        redo.isEnabled=pad.canRedo;redo.alpha=if(pad.canRedo)1f else .35f
    }
}
