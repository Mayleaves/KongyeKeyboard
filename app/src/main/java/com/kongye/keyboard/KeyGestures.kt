package com.kongye.keyboard

import android.content.Context

/** A saved flick is either literal text or one of the existing editor commands. */
object KeyGestures {
    val editableKeys=('a'..'z').toList()+listOf(',','.')
    private val preferencePattern=Regex("flick26_(zh|en)_([a-z]|comma|period)_(up|down)")
    fun isPreferenceKey(key:String)=preferencePattern.matches(key)
    fun displayName(zh:Boolean,id:Char)=when(id) {
        ','->if(zh)"逗号（，）" else "逗号（,）"
        '.'->if(zh)"句号（。）" else "句号（.）"
        else->id.uppercaseChar().toString()
    }
    data class Action(val value:String) {
        val command get()=value.takeIf {it.startsWith("edit:")}?.removePrefix("edit:")
        val icon get()=command?.let {commandIcons[it]} ?: 0
        val text get()=value.takeIf {it.startsWith("text:")}?.removePrefix("text:")
        val label get()=command?.let {commands[it]} ?: text.orEmpty().let {
            if(it.codePointCount(0,it.length)>3)it.substring(0,it.offsetByCodePoints(0,2))+"…" else it
        }
    }
    val commands=linkedMapOf("undo" to "撤销","redo" to "重做","cut" to "剪切","copy" to "复制","paste" to "粘贴",
        "all" to "全选","select" to "选择","left" to "左移","right" to "右移","up" to "上移","down" to "下移",
        "home" to "段首","end" to "段尾","del" to "删除","enter" to "换行","clear" to "清空")
    val commandIcons=mapOf("undo" to R.drawable.ic_undo,"redo" to R.drawable.ic_redo,"cut" to R.drawable.ic_cut,"copy" to R.drawable.ic_copy,
        "paste" to R.drawable.ic_paste,"all" to R.drawable.ic_select_all,"select" to R.drawable.ic_select,"left" to R.drawable.ic_chevron_left,
        "right" to R.drawable.ic_chevron_right,"up" to R.drawable.ic_chevron_up,"down" to R.drawable.ic_chevron_down,"home" to R.drawable.ic_home,
        "end" to R.drawable.ic_end,"del" to R.drawable.ic_del,"enter" to R.drawable.ic_enter,"clear" to R.drawable.ic_trash)
    fun icon(command:String?)=command?.let {commandIcons[it]} ?: 0
    // Preserve the existing letter preference names so upgrades keep saved mappings.
    private fun key(zh:Boolean,id:Char,down:Boolean):String {
        require(id in editableKeys)
        val name=when(id){','->"comma";'.'->"period";else->id.toString()}
        return "flick26_${if(zh)"zh" else "en"}_${name}_${if(down)"down" else "up"}"
    }
    fun action(ctx:Context?,zh:Boolean,letter:Char,down:Boolean,default:String?):Action? {
        val value=ctx?.let {Prefs.of(it).getString(key(zh,letter,down),null)} ?: default?.let {"text:$it"}
        return value?.takeIf {it!="text:"&&(it.startsWith("text:")||it.removePrefix("edit:") in commands)}?.let(::Action)
    }
    fun save(ctx:Context,zh:Boolean,letter:Char,down:Boolean,value:String) {
        require(letter in editableKeys&&(value.startsWith("text:")||value.removePrefix("edit:") in commands))
        Prefs.of(ctx).edit().putString(key(zh,letter,down),value).apply()
    }
    fun clear(ctx:Context,zh:Boolean) {
        val e=Prefs.of(ctx).edit()
        for(id in editableKeys)for(down in listOf(false,true))e.putString(key(zh,id,down),"text:")
        e.apply()
    }
    fun clearAll(ctx:Context){clear(ctx,false);clear(ctx,true)}
    fun reset(ctx:Context,zh:Boolean) {
        val e=Prefs.of(ctx).edit()
        for(id in editableKeys)for(down in listOf(false,true))e.remove(key(zh,id,down))
        e.apply()
    }
}
