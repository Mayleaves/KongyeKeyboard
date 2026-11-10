package com.kongye.keyboard

import android.app.Instrumentation
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.BaseInputConnection

object RemoteHistoryRegression {
    @JvmStatic fun run(inst:Instrumentation) {
        val text=StringBuilder("远端已有内容|")
        // UU's proxy buffer is empty and returns false even when text was forwarded.
        val connection=object:BaseInputConnection(View(inst.targetContext),false) {
            override fun commitText(value:CharSequence?,cursor:Int):Boolean {text.append(value);return false}
            override fun sendKeyEvent(event:KeyEvent):Boolean {
                if(event.action==KeyEvent.ACTION_UP && event.keyCode==KeyEvent.KEYCODE_DEL && text.isNotEmpty())
                    text.setLength(Character.offsetByCodePoints(text,text.length,-1))
                return false
            }
            override fun getTextBeforeCursor(length:Int,flags:Int):CharSequence=""
        }
        var now=1000L
        val history=RemoteInputHistory {now}
        fun ui(block:()->Unit) {var failure:Throwable?=null;inst.runOnMainSync {try {block()} catch(t:Throwable) {failure=t}};failure?.let {throw it}}
        ui {
            connection.commitText("你好😀",1);history.inserted("你好😀",false)
            check(history.clear(connection));check(text.toString()=="远端已有内容|")
            check(history.undo(connection));check(text.toString()=="远端已有内容|你好😀")
            check(history.undo(connection));check(text.toString()=="远端已有内容|")
            check(history.redo(connection));check(text.toString()=="远端已有内容|你好😀")
            history.delete(connection);check(text.toString()=="远端已有内容|你好")
            check(history.undo(connection));check(text.toString()=="远端已有内容|你好😀")
            history.replace(connection,"你真棒😀")
            check(text.toString()=="远端已有内容|你真棒😀")
            check(history.currentText()=="你真棒😀")
            check(history.undo(connection));check(text.toString()=="远端已有内容|你好😀")
            check(history.redo(connection));check(text.toString()=="远端已有内容|你真棒😀")
            history.reset();text.setLength(0)
            connection.commitText("a",1);history.inserted("a",true);now+=100
            connection.commitText("b",1);history.inserted("b",true);now+=1000
            connection.commitText("c",1);history.inserted("c",true)
            check(history.undo(connection));check(text.toString()=="ab")
            check(history.undo(connection));check(text.isEmpty())
            history.reset();text.setLength(0)
            val long="x".repeat(48);connection.commitText(long,1);history.inserted(long,false)
            check(history.clear(connection));check(text.length==32)
            EditorDeletion.cancel()
        }
        Thread.sleep(60);inst.waitForIdleSync()
        ui {
            check(text.length==32)
            check(history.undo(connection));check(text.length==48)
            history.reset();check(!history.clear(connection));check(!history.undo(connection))
        }
    }
}
