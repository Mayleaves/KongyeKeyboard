package com.kongye.keyboard

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager

/** Transient focus lets the media player pause/duck and restore itself when listening ends. */
class VoiceMedia(private val ctx:Context) {
    private var focus:AudioFocusRequest?=null
    fun acquire() {
        release()
        val mode=Prefs.of(ctx).getInt(V3Prefs.MEDIA,0)
        if(mode==0)return
        val attributes=AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANT).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
        val request=AudioFocusRequest.Builder(if(mode==1) AudioManager.AUDIOFOCUS_GAIN_TRANSIENT else AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(attributes).setWillPauseWhenDucked(mode==1).setOnAudioFocusChangeListener { }.build()
        if(ctx.getSystemService(AudioManager::class.java).requestAudioFocus(request)==AudioManager.AUDIOFOCUS_REQUEST_GRANTED) focus=request
    }
    fun release() {focus?.let {ctx.getSystemService(AudioManager::class.java).abandonAudioFocusRequest(it)};focus=null}
}

object VoiceText {
    fun process(ctx:Context,source:String):String {
        val p=Prefs.of(ctx)
        var text=source
        if(p.getBoolean(V3Prefs.VOICE_CLEAN,false)) {
            text=text.replace(Regex("(^|[，。！？、\\s])(嗯+|呃+|额+|啊+|那个|就是说)([，、\\s]*)"),"$1")
                .replace(Regex("([\\p{IsHan}]{2,6})(?:[，、\\s]*\\1)+"),"$1")
        }
        if(p.getBoolean(V3Prefs.VOICE_NUMBERS,false)) {
            text=Regex("[零〇一二两三四五六七八九十百千万亿]+(?=(点|年|月|日|号|个|元|块|米|公里|分钟|秒|小时|岁|次|人|本|张|件|台|\\b))").replace(text) {
                number(it.value) ?: it.value
            }
        }
        if(p.getBoolean(V3Prefs.VOICE_NUMBERS,false))text=text.replace(Regex("([0-9]+)点([0-9]+)"),"$1.$2")
        if(!p.getBoolean(V3Prefs.VOICE_PUNCT,true))text=text.replace(Regex("[，。！？；：、,!?;:]|(?<!\\d)\\.|\\.(?!\\d)"),"")
        return text.trim()
    }
    internal fun number(raw:String):String? {
        val digits="零一二三四五六七八九"
        if(raw.none {it in "十百千万亿"})return raw.map {c->when(c){'两'->'2';'〇'->'0';else->digits.indexOf(c).takeIf {it>=0}?.digitToChar() ?: return null}}.joinToString("")
        var total=0L;var section=0L;var digit=0L
        for(c in raw) {
            val d=if(c=='两')2 else digits.indexOf(c)
            if(d>=0) {digit=d.toLong();continue}
            val unit=when(c){'十'->10L;'百'->100L;'千'->1000L;'万'->10000L;'亿'->100000000L;else->return null}
            if(unit<10000)section+=(if(digit==0L)1 else digit)*unit
            else {
                section+=digit
                if(unit==100000000L)total=(total+section.coerceAtLeast(1))*unit
                else total+=section.coerceAtLeast(1)*unit
                section=0
            }
            digit=0
        }
        return (total+section+digit).toString()
    }
}
