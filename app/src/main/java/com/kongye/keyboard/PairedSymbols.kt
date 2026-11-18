package com.kongye.keyboard

/** Match Unicode brackets supported by Android, plus keyboard angle marks and Chinese quotes. */
object PairedSymbols {
    private val extra=mapOf("<" to ">","＜" to "＞","“" to "”","‘" to "’")
    fun closing(open:String):String? {
        extra[open]?.let {return it}
        if(open.codePointCount(0,open.length)!=1)return null
        val code=open.codePointAt(0)
        if(Character.getType(code)!=Character.START_PUNCTUATION.toInt())return null
        var close=android.icu.lang.UCharacter.getBidiPairedBracket(code)
        if(close==code)close=android.icu.lang.UCharacter.getMirror(code)
        return close.takeIf {it!=code&&Character.getType(it)==Character.END_PUNCTUATION.toInt()}?.let {String(Character.toChars(it))}
    }
    fun closingLength(pair:String):Int {
        if(pair.codePointCount(0,pair.length)!=2)return 0
        val split=pair.offsetByCodePoints(0,1)
        val close=closing(pair.substring(0,split)) ?: return 0
        return if(pair.substring(split)==close)close.length else 0
    }
}
