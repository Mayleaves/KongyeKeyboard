package com.kongye.keyboard

import android.content.Context

/** Describes the same applicability rules used by the IME, without changing saved preferences. */
object SettingAvailability {
    data class State(val blocked:String?=null,val warning:String?=null) {val enabled get()=blocked==null}
    fun forKey(ctx:Context,key:String):State {
        val p=Prefs.of(ctx)
        val zh=V3Prefs.glide(ctx,true)
        val en=V3Prefs.glide(ctx,false)
        val layout=Prefs.layout(ctx)
        val activeZh=zh&&layout=="26"
        val any=activeZh||en
        val swipe=p.getBoolean(Prefs.SWIPE,true)
        fun require(on:Boolean,why:String)=if(on)State()else State(blocked=why)
        val gesture="上下滑与滑行已同时开启：短距离上下滑输入符号，跨过其他字母键后优先识别为滑行。上下滑距离过大时可能触发滑行。"
        return when(key) {
            V3Prefs.CORRECT_NEAR,V3Prefs.CORRECT_SWAP,V3Prefs.CORRECT_EXTRA,V3Prefs.CORRECT_MISSING->require(p.getBoolean(V3Prefs.CORRECTION_ENABLED,true),"请返回「中文与拼音」，在「拼音纠错」入口开启。")
            V3Prefs.GLIDE_ZH->if(layout!="26")State(blocked="中文滑行仅支持 26 键；请先在「键盘布局」切换当前中文布局。")
                else State(warning=if(zh&&swipe)gesture else null)
            V3Prefs.GLIDE_EN->State(warning=if(en&&swipe)gesture else null)
            Prefs.SWIPE->State(warning=if(swipe&&any)gesture else null)
            V3Prefs.SWIPE_REVERSE->require(swipe,"请先开启「启用上下滑」。")
            V3Prefs.GLIDE_LIVE,V3Prefs.GLIDE_TRACE->require(any,"请先开启「中文滑行」或「英文滑行」；中文滑行需要 26 键布局。")
            V3Prefs.GLIDE_WORD_DELETE->require(activeZh,"请先使用 26 键布局并开启「中文滑行」。")
            V3Prefs.ASSOC_SPACE,V3Prefs.ASSOC_CONTINUOUS,V3Prefs.DELETE_DIRECT->require(p.getBoolean(Prefs.ASSOC,true),"请先开启「联想词」。")
            Prefs.VIB_STRENGTH->require(p.getInt(V3Prefs.VIBRATION,0)==1,"如需自行调整强度，请在「按键震动」选择「启用」。")
            V3Prefs.SOUND_VOLUME->require(p.getBoolean(Prefs.SOUND,false),"请先开启「按键音」。")
            V3Prefs.LAYOUT_LANDSCAPE->require(p.getBoolean(V3Prefs.ORIENTATION_LAYOUT,false),"请先开启「横竖屏分别记住布局」。")
            Prefs.SPLIT_LAYOUT->if(layout=="9")State(blocked="九键布局不支持分体；请先切换为 26 键或 14 键。")
                else State(warning=if(p.getBoolean(Prefs.SPLIT_LAYOUT,false)&&ctx.resources.displayMetrics.widthPixels/ctx.resources.displayMetrics.density<600)
                    "当前屏幕宽度不足，暂时使用完整键盘；切换到足够宽的横屏或大屏后自动分体。" else null)
            VoicePolish.MODE->require(p.getBoolean(V3Prefs.VOICE_POLISH,false),"请先开启「语句润色」。")
            else->State()
        }
    }
}
