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
        auto.serviceInfo=auto.serviceInfo.apply {flags=flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS}
        fun nodes(node:AccessibilityNodeInfo?):List<AccessibilityNodeInfo> =
            if(node==null)emptyList() else listOf(node)+(0 until node.childCount).flatMap {nodes(node.getChild(it))}
        fun ownNodes():List<AccessibilityNodeInfo> {
            auto.clearCache()
            return auto.windows.flatMap {nodes(it.root)}.filter {it.packageName?.toString()=="com.kongye.keyboard"}
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
                ownNodes().firstOrNull {it.isScrollable}?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
                Thread.sleep(180)
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
                        val rows=FlickPreviewView::class.java.getDeclaredField("rows").apply {isAccessible=true}.get(preview) as List<Row>
                        passed=rows.first().keys.first().hint==expected
                    }
                }
                if(passed)return
                Thread.sleep(80)
            }
            error("Visible preview did not update to $expected")
        }
        try {
            android.os.ParcelFileDescriptor.AutoCloseInputStream(auto.executeShellCommand("am start -W -f 0x10008000 -n com.kongye.keyboard/.SettingsActivity")).use {it.readBytes()}
            val homeEnd=SystemClock.uptimeMillis()+15000
            while(SystemClock.uptimeMillis()<homeEnd && !ownNodes().any {it.text?.toString()=="显示键盘"})Thread.sleep(80)
            check(ownNodes().any {it.text?.toString()=="今日输入"}) {"Not on settings home"}
            val search=ownNodes().first {it.isEditable}
            check(search.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT,Bundle().apply {putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,"编辑")}))
            Thread.sleep(350)
            check(ownNodes().any {it.text?.toString()?.contains("匹配：编辑功能")==true}) {"Search result lacks matched feature"}
            click("上下滑")
            inst.sendStatus(0,Bundle().apply {putString("stream","Search 编辑 shows matching feature and settings path, then opens flick editor\n")})
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
