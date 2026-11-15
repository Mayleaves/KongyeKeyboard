package com.kongye.keyboard

import android.app.Instrumentation
import android.app.UiAutomation
import android.content.Intent
import android.graphics.Rect
import android.os.Bundle
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import android.view.inspector.WindowInspector

object SettingsInteraction241Regression {
    fun run(inst:Instrumentation) {
        val ctx=inst.targetContext
        val prefs=Prefs.of(ctx)
        val backup=prefs.all.filterKeys {it.startsWith("flick26_")}
        val auto=inst.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        fun nodes(node:AccessibilityNodeInfo?):List<AccessibilityNodeInfo> =
            if(node==null)emptyList() else listOf(node)+(0 until node.childCount).flatMap {nodes(node.getChild(it))}
        fun ownNodes():List<AccessibilityNodeInfo> {
            auto.clearCache()
            return nodes(auto.rootInActiveWindow).filter {it.packageName?.toString()=="com.kongye.keyboard"}
        }
        fun click(label:String,last:Boolean=false) {
            val end=SystemClock.uptimeMillis()+10000
            while(SystemClock.uptimeMillis()<end) {
                val found=ownNodes().filter {it.text?.toString()==label}
                var node=if(last)found.lastOrNull() else found.firstOrNull()
                repeat(4) {
                    if(node?.performAction(AccessibilityNodeInfo.ACTION_CLICK)==true) {Thread.sleep(180);return}
                    node=node?.parent
                }
                Thread.sleep(80)
            }
            error("Setting not clickable: $label")
        }
        fun descendants(view:View):List<View> = listOf(view)+if(view is ViewGroup)(0 until view.childCount).flatMap {descendants(view.getChildAt(it))} else emptyList()
        fun verifyPreview(expected:String?) {
            val end=SystemClock.uptimeMillis()+5000
            while(SystemClock.uptimeMillis()<end) {
                var passed=false
                inst.runOnMainSync {
                    val preview=WindowInspector.getGlobalWindowViews().flatMap {descendants(it)}.filterIsInstance<FlickPreviewView>().firstOrNull {it.isShown}
                    if(preview!=null) {
                        @Suppress("UNCHECKED_CAST")
                        val keys=FlickPreviewView::class.java.getDeclaredField("keys").apply {isAccessible=true}.get(preview) as List<Key>
                        val draft=FlickPreviewView::class.java.getDeclaredField("draft").apply {isAccessible=true}.get(preview) as String
                        val literal=FlickPreviewView::class.java.getDeclaredField("literalDraft").apply {isAccessible=true}.getBoolean(preview)
                        val down=FlickPreviewView::class.java.getDeclaredField("down").apply {isAccessible=true}.getBoolean(preview)
                        passed=(if((draft.isNotEmpty() || literal) && down) draft else keys.first().hint)==expected
                    }
                }
                if(passed)return
                Thread.sleep(80)
            }
            error("Visible preview did not update to $expected")
        }
        try {
            ctx.startActivity(Intent(ctx,SettingsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
            Thread.sleep(2200)
            click("自定义按键")
            KeyGestures.save(ctx,false,'q',true,"edit:cut")
            val before=prefs.all.filterKeys {it.startsWith("flick26_") && it !in setOf("flick26_en_q_down")}
            click("复制")
            verifyPreview("复制")
            check(prefs.all.filterKeys {it.startsWith("flick26_") && it!="flick26_en_q_down"}==before) {"Function applied before Save"}
            click("保存",true)
            check(Layouts.letters(true,ctx).first().keys.first().hint=="复制")
            click("清空",true);click("清空",true)
            verifyPreview(null)
            check(Layouts.letters(false,ctx).first().keys.first().hint=="剪切") {"Chinese clear changed English"}
            val field=ownNodes().first {it.isEditable}
            check(field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT,Bundle().apply {putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,"☆")}))
            Thread.sleep(220)
            verifyPreview("☆")
            check(Layouts.letters(true,ctx).first().keys.first().hint==null) {"Symbol applied before Save"}
            click("保存")
            check(Layouts.letters(true,ctx).first().keys.first().hint=="☆") {"Symbol save did not apply"}
            click("重置",true)
            verifyPreview("1")
            inst.sendStatus(0,Bundle().apply {putString("stream","SETTINGS DRAFT / EXPLICIT FUNCTION SAVE / SYMBOL EXPLICIT SAVE / LANGUAGE CLEAR CHECKS PASSED\n")})
        } finally {
            inst.runOnMainSync {
                val edit=prefs.edit()
                prefs.all.keys.filter {it.startsWith("flick26_")}.forEach {edit.remove(it)}
                backup.forEach {(key,value)->edit.putString(key,value as String)}
                edit.commit()
            }
        }
    }
}
