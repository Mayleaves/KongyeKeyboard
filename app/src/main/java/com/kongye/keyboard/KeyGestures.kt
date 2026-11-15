package com.kongye.keyboard

import android.content.Context

/** A saved flick is either literal text or one of the existing editor commands. */
object KeyGestures {
    data class Action(val value:String) {
        val command get()=value.takeIf {it.startsWith("edit:")}?.removePrefix("edit:")
        val text get()=value.takeIf {it.startsWith("text:")}?.removePrefix("text:")
        val label get()=command?.let {commands[it]} ?: text.orEmpty().let {
            if(it.codePointCount(0,it.length)>3) it.substring(0,it.offsetByCodePoints(0,2))+"…" else it
        }
    }
    val commands=linkedMapOf("undo" to "撤销","redo" to "重做","cut" to "剪切","copy" to "复制","paste" to "粘贴",
        "all" to "全选","select" to "选择","left" to "左移","right" to "右移","up" to "上移","down" to "下移",
        "home" to "段首","end" to "段尾","del" to "删除","enter" to "换行","clear" to "清空")
    private fun key(zh:Boolean,letter:Char,down:Boolean)="flick26_${if(zh) "zh" else "en"}_${letter}_${if(down) "down" else "up"}"
    fun action(ctx:Context?,zh:Boolean,letter:Char,down:Boolean,default:String?):Action? {
        val value=ctx?.let {Prefs.of(it).getString(key(zh,letter,down),null)} ?: default?.let {"text:$it"}
        return value?.takeIf {it!="text:" && (it.startsWith("text:") || it.removePrefix("edit:") in commands)}?.let(::Action)
    }
    fun save(ctx:Context,zh:Boolean,letter:Char,down:Boolean,value:String) {
        require(letter in 'a'..'z' && (value.startsWith("text:") || value.removePrefix("edit:") in commands))
        Prefs.of(ctx).edit().putString(key(zh,letter,down),value).apply()
    }
    /** Empty is an intentional disabled action, distinct from missing/default. */
    fun clear(ctx:Context,zh:Boolean) {
        val editor=Prefs.of(ctx).edit()
        for(letter in 'a'..'z') for(down in listOf(false,true)) editor.putString(key(zh,letter,down),"text:")
        editor.apply()
    }
    fun clearAll(ctx:Context) {clear(ctx,false);clear(ctx,true)}
    fun reset(ctx:Context,zh:Boolean) {
        val e=Prefs.of(ctx).edit()
        for(c in 'a'..'z') for(d in listOf(false,true)) e.remove(key(zh,c,d))
        e.apply()
    }
}
