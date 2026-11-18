package com.kongye.keyboard

import android.app.Instrumentation
import android.os.Bundle
import android.text.Editable
import android.text.Selection
import android.text.method.BaseKeyListener
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.BaseInputConnection

object NoteDeletionRegression {
    fun run(i:Instrumentation) {
        i.runOnMainSync {
            val view=View(i.targetContext)
            val listener=object:BaseKeyListener(){override fun getInputType()=android.text.InputType.TYPE_CLASS_TEXT}
            class Rich(val initial:String,val omitContext:Boolean=false):BaseInputConnection(view,true) {
                val value=Editable.Factory.getInstance().newEditable(initial)
                var deletes=0
                override fun getEditable()=value
                override fun getTextBeforeCursor(n:Int,flags:Int):CharSequence = if(omitContext)"" else (super.getTextBeforeCursor(n,flags) ?: "")
                // Simulate rich notes claiming success without merging paragraphs.
                override fun deleteSurroundingTextInCodePoints(before:Int,after:Int)=true
                override fun sendKeyEvent(event:KeyEvent):Boolean {
                    if(event.keyCode!=KeyEvent.KEYCODE_DEL)return false
                    if(event.action==KeyEvent.ACTION_DOWN){deletes++;listener.onKeyDown(view,value,event.keyCode,event)}
                    return true
                }
            }
            fun checkCase(text:String,caret:Int,expected:String,pkg:String?,omit:Boolean=false) {
                val c=Rich(text,omit);Selection.setSelection(c.value,caret)
                EditorDeletion.delete(c,pkg)
                check(c.value.toString()==expected) {"$pkg caret=$caret: ${c.value}"};check(c.deletes==if(pkg in listOf("com.coloros.note","com.oplus.note","com.oneplus.note")||omit)1 else 0)
            }
            for(pkg in listOf("com.coloros.note","com.oplus.note","com.oneplus.note")) {
                checkCase("甲\n乙",2,"甲乙",pkg)
                checkCase("甲\n",2,"甲",pkg)
                checkCase("😀\n乙",3,"😀乙",pkg)
                checkCase("甲乙",2,"甲",pkg)
                val c=Rich("甲\n乙");Selection.setSelection(c.value,0,c.value.length);EditorDeletion.delete(c,pkg);check(c.value.isEmpty())
            }
            checkCase("甲\n乙",2,"甲乙","test.editor")
            checkCase("甲\r\n乙",3,"甲乙","test.editor")
            checkCase("甲\n乙",2,"甲乙","test.editor",true)
            checkCase("甲",0,"甲","test.editor",true)
        }
        i.sendStatus(0,Bundle().apply {putString("stream","NOTE PARAGRAPH BACKSPACE PASSED: rich notes, invisible boundaries, selections, surrogate pairs and document start\n")})
    }
}
