package com.kongye.keyboard

import android.content.Context
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/** 四列等宽候选；右列在候选栏原有的收起箭头下面。 */
class ExpandedCandidates(
    context: Context, skin: Skin, items: List<String>, mode: Int,
    onMode: (Int) -> Unit, onPick: (Int) -> Unit, onDelete: () -> Unit, onBack: () -> Unit,
) : LinearLayout(context) {
    init {
        orientation = HORIZONTAL
        val d = resources.displayMetrics.density
        val filtered = items.indices.filter { mode == 0 || if (mode == 1) items[it].codePointCount(0, items[it].length) == 1 else items[it].codePointCount(0, items[it].length) > 1 }
        addView(ScrollView(context).apply {
            isVerticalScrollBarEnabled = false
            addView(CandFlowView(context, skin, filtered.map { items[it] }) { onPick(filtered[it]) })
        }, lp(0, MATCH, 1f))
        addView(LinearLayout(context).apply {
            orientation = VERTICAL
            setPadding(0, (6*d).toInt(), (4*d).toInt(), (6*d).toInt())
            addView(IconButton(context, R.drawable.ic_del, skin.modFg, 22f, onDelete).apply {
                contentDescription = "删除"; background = roundBg(skin.modBg, 10*d)
            }, lp(MATCH, 0, 1f).apply { bottomMargin=(6*d).toInt() })
            addView(TextView(context).apply {
                text = listOf("词/字", "字", "词")[mode]; textSize=14f; gravity=Gravity.CENTER
                setTextColor(skin.modFg); background=roundBg(skin.modBg, 10*d)
                setOnClickListener { onMode((mode+1)%3) }
            }, lp(MATCH, 0, 1f).apply { bottomMargin=(6*d).toInt() })
            addView(IconButton(context, R.drawable.ic_back, skin.modFg, 22f, onBack).apply {
                contentDescription="返回"; background=roundBg(skin.modBg, 10*d)
            }, lp(MATCH, 0, 1f))
        }, lp((44*d).toInt(), MATCH))
    }
}
