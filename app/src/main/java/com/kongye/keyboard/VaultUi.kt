package com.kongye.keyboard

import android.content.Context
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/** Search results contain metadata only. Never create a password TextView. */
class VaultPanel(context:Context,skin:Skin,entries:List<VaultFill.Entry>,onPick:(VaultFill.Entry)->Unit,onSearch:()->Unit,onBack:()->Unit):LinearLayout(context) {
    init {
        val d=resources.displayMetrics.density
        orientation=VERTICAL
        val head=LinearLayout(context).apply { gravity=Gravity.CENTER_VERTICAL;setPadding((8*d).toInt(),0,(8*d).toInt(),0) }
        head.addView(IconButton(context,R.drawable.ic_chevron_left,skin.barFg,22f,onBack),lp((42*d).toInt(),(40*d).toInt()))
        head.addView(TextView(context).apply {text="密码空间";textSize=15f;setTextColor(skin.keyFg)},lp(0,WRAP,1f))
        head.addView(IconButton(context,R.drawable.ic_search,skin.barFg,22f,onSearch),lp((42*d).toInt(),(40*d).toInt()))
        addView(head,lp(MATCH,WRAP))
        val list=LinearLayout(context).apply {orientation=VERTICAL;setPadding((12*d).toInt(),0,(12*d).toInt(),(8*d).toInt())}
        if(entries.isEmpty()) list.addView(TextView(context).apply {text="没有找到匹配项，点右上角重新搜索";textSize=14f;setTextColor(skin.barFg);gravity=Gravity.CENTER;setPadding(0,(24*d).toInt(),0,0)})
        entries.forEach {entry -> list.addView(TextView(context).apply {
            text="🔑 ${entry.name} · ${entry.account}";textSize=15f;setTextColor(skin.keyFg)
            gravity=Gravity.CENTER_VERTICAL;background=roundBg(skin.keyBg,12*d)
            setPadding((12*d).toInt(),0,(12*d).toInt(),0)
            setOnClickListener {onPick(entry)}
        },lp(MATCH,(48*d).toInt()).apply {bottomMargin=(6*d).toInt()}) }
        addView(ScrollView(context).apply {addView(list)},lp(MATCH,0,1f))
    }
}
