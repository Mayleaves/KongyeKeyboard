package com.kongye.keyboard

import android.os.SystemClock
import android.view.inputmethod.InputConnection

/** UU forwards text/DEL/ENTER, but not setSelection or whole-text replacement.
 * Keep only our current input tail in memory; never pretend this is remote full text. */
@androidx.annotation.Keep
class RemoteInputHistory(private val clock:()->Long=SystemClock::uptimeMillis) {
    private var tail=""
    private val undo=ArrayDeque<String>()
    private val redo=ArrayDeque<String>()
    private var group=""
    private var editedAt=0L
    fun reset() {EditorDeletion.cancel();tail="";undo.clear();redo.clear();group="";editedAt=0}
    fun knownCharacters()=tail.codePointCount(0,tail.length)
    fun currentText()=tail
    fun replace(c:InputConnection,text:String) {
        require(text.length<=10000)
        if(text==tail) return
        remember("edit",false);restore(c,text)
    }
    private fun remember(kind:String,combine:Boolean) {
        if(!combine || kind!=group || clock()-editedAt>700 || undo.isEmpty()) {
            if(undo.lastOrNull()!=tail) undo.addLast(tail)
            while(undo.size>100) undo.removeFirst()
        }
        redo.clear();group=kind;editedAt=clock()
    }
    fun inserted(text:String,typing:Boolean) {
        if(text.isEmpty()) return
        if(tail.length+text.length>10000) {reset();return}
        remember("type",typing);tail+=text
    }
    fun delete(c:InputConnection) {
        if(tail.isEmpty()) {reset();EditorDeletion.delete(c,"com.netease.uuremote");return}
        remember("delete",true)
        EditorDeletion.erase(c,1,{tail=tail.dropLast(Character.charCount(tail.codePointBefore(tail.length)))},{})
    }
    fun clear(c:InputConnection):Boolean {
        if(tail.isEmpty()) return false
        remember("clear",false)
        EditorDeletion.erase(c,tail.codePointCount(0,tail.length),{
            tail=tail.dropLast(Character.charCount(tail.codePointBefore(tail.length)))
        },{})
        return true
    }
    private fun restore(c:InputConnection,want:String) {
        var prefix=0
        while(prefix<tail.length && prefix<want.length && tail[prefix]==want[prefix]) prefix++
        if(prefix>0 && prefix<tail.length && Character.isSurrogatePair(tail[prefix-1],tail[prefix])) prefix--
        val suffix=want.substring(prefix)
        val count=tail.codePointCount(prefix,tail.length)
        EditorDeletion.erase(c,count,{
            tail=tail.dropLast(Character.charCount(tail.codePointBefore(tail.length)))
        },{if(suffix.isNotEmpty()) c.commitText(suffix,1);tail=want})
        group="";editedAt=0
    }
    fun undo(c:InputConnection):Boolean {
        EditorDeletion.cancel()
        val want=undo.removeLastOrNull() ?: return false
        redo.addLast(tail);restore(c,want);return true
    }
    fun redo(c:InputConnection):Boolean {
        EditorDeletion.cancel()
        val want=redo.removeLastOrNull() ?: return false
        undo.addLast(tail);restore(c,want);return true
    }
}
