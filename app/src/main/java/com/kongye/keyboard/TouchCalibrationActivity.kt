package com.kongye.keyboard

import android.app.Activity
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.widget.Button
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView

/** Supervised calibration gives the touch model a known intended key. No typed text is stored. */
class TouchCalibrationActivity:Activity() {
    private var index=0
    private val targets=(0..2).flatMap {"qazwsxedcrfvtgbyhnujmikolp".toList()}
    private lateinit var board:KeyboardView
    private lateinit var target:TextView
    private lateinit var begin:Button
    private lateinit var use:Switch
    private lateinit var model:TouchCalibration
    private val draft=mutableListOf<Pair<String,Pair<Float,Float>>>()
    override fun onCreate(state:Bundle?) {
        super.onCreate(state)
        val p=Prefs.of(this);model=TouchCalibration(this)
        val skin=Prefs.theme(this).pick(Prefs.night(this))
        val d=resources.displayMetrics.density
        val root=LinearLayout(this).apply {orientation=LinearLayout.VERTICAL;setBackgroundColor(skin.kbBg)}
        val scroll=android.widget.ScrollView(this).apply {isFillViewport=true;addView(root)}
        if(android.os.Build.VERSION.SDK_INT>=30)scroll.setOnApplyWindowInsetsListener {view,insets->
            val bars=insets.getInsets(WindowInsets.Type.systemBars());view.setPadding(bars.left,bars.top,bars.right,bars.bottom);insets
        }
        fun text(value:String,size:Float)=TextView(this).apply {text=value;textSize=size;setTextColor(skin.keyFg);setPadding((16*d).toInt(),(8*d).toInt(),(16*d).toInt(),(8*d).toInt())}
        root.addView(Button(this).apply {text="‹ 返回";setOnClickListener {finish()}})
        root.addView(text("触控校准",24f))
        root.addView(text("按提示自然点击字母。完成后点击保存，只记录按键内的相对偏移；普通打字不自动训练。横竖屏和不同宽度分别记录，旧偏移会逐渐减弱。",14f))
        use=Switch(this).apply {text="使用校准结果";setTextColor(skin.keyFg);isChecked=p.getBoolean(TouchCalibration.ENABLED,false)
            setPadding((16*d).toInt(),0,(16*d).toInt(),0)
            setOnCheckedChangeListener {_,checked->p.edit().putBoolean(TouchCalibration.ENABLED,checked).apply()}}
        root.addView(use)
        root.addView(Switch(this).apply {text="边界处参考拼音";setTextColor(skin.keyFg);isChecked=p.getBoolean(TouchCalibration.CONTEXT,false)
            setPadding((16*d).toInt(),0,(16*d).toInt(),0)
            setOnCheckedChangeListener {_,checked->p.edit().putBoolean(TouchCalibration.CONTEXT,checked).apply()}})
        target=text("点击开始，然后按提示点击 78 次",20f).apply {gravity=Gravity.CENTER}
        root.addView(target,LinearLayout.LayoutParams(-1,(80*d).toInt()))
        val actions=LinearLayout(this)
        begin=Button(this).apply {text="开始校准";setOnClickListener {
            if(index==targets.size) {
                draft.forEach {(key,offset)->model.record(board.touchProfile,key,offset.first,offset.second)}
                use.isChecked=true;target.text="已保存本次校准";text="重新校准";index=0;draft.clear()
            } else {index=0;draft.clear();board.calibrationTarget=targets[0].toString();target.text="请点击 ${targets[0]}（1 / ${targets.size}）";isEnabled=false}
        }}
        actions.addView(begin,LinearLayout.LayoutParams(0,-2,1f))
        actions.addView(Button(this).apply {text="清空校准";setOnClickListener {model.clear();use.isChecked=false;target.text="已清空校准";index=0;draft.clear();board.calibrationTarget=null;begin.text="开始校准";begin.isEnabled=true}},LinearLayout.LayoutParams(0,-2,1f))
        root.addView(actions)
        board=KeyboardView(this).apply {
            zh=true;swipeEnabled=false;letterLong=2;glideEnabled=false
            setup(skin,Layouts.letters(true,this@TouchCalibrationActivity))
            onCalibrationTap={key,x,y->
                if(kotlin.math.abs(x)<=.45f && kotlin.math.abs(y)<=.45f) {
                    draft.add(key to (x to y));index++
                    if(index==targets.size) {calibrationTarget=null;target.text="校准完成，点击保存后生效";begin.text="保存校准";begin.isEnabled=true}
                    else {calibrationTarget=targets[index].toString();target.text="请点击 ${targets[index]}（${index+1} / ${targets.size}）"}
                } else target.text="请再点一次 $key"
            }
        }
        root.addView(board,LinearLayout.LayoutParams(-1,-2))
        setContentView(scroll)
    }
}
