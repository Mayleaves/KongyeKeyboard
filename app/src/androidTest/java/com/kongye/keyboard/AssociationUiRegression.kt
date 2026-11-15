package com.kongye.keyboard

import android.app.Instrumentation
import android.app.UiAutomation
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.view.inspector.WindowInspector
import android.widget.TextView

/** Uses only the audit provider's synthetic association fixture. */
object AssociationUiRegression {
    fun run(inst:Instrumentation) {
        val context=inst.targetContext
        val prefs=Prefs.of(context)
        val oldEnabled=prefs.all[Prefs.VAULT]
        val oldSource=prefs.all[Prefs.VAULT_PACKAGE]
        fun ui(action:()->Unit) {var failure:Throwable?=null;inst.runOnMainSync {try {action()} catch(t:Throwable){failure=t}};failure?.let {throw it}}
        fun descendants(view:View):List<View> = listOf(view)+if(view is ViewGroup) (0 until view.childCount).flatMap {descendants(view.getChildAt(it))} else emptyList()
        fun views()=WindowInspector.getGlobalWindowViews().flatMap {descendants(it)}
        fun waitFor(label:String,condition:()->Boolean) {
            val end=SystemClock.uptimeMillis()+20000
            while(SystemClock.uptimeMillis()<end) {var done=false;ui {done=condition()};if(done)return;Thread.sleep(80)}
            error("Timed out $label")
        }
        try {
            ui {prefs.edit().putBoolean(Prefs.VAULT,true).putString(Prefs.VAULT_PACKAGE,"com.kongye.docs.audit").commit()}
            val auto=inst.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
            auto.serviceInfo=auto.serviceInfo.apply {flags=flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS}
            fun nodes(node:android.view.accessibility.AccessibilityNodeInfo?):List<android.view.accessibility.AccessibilityNodeInfo> =
                if(node==null)emptyList() else listOf(node)+(0 until node.childCount).flatMap {nodes(node.getChild(it))}
            context.startActivity(Intent(inst.context,TypingFormActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra("bottom",true))
            waitFor("independent field") {
                auto.clearCache()
                val editor=auto.windows.flatMap {nodes(it.root)}.firstOrNull {it.packageName?.toString()=="com.kongye.keyboard.test" && it.contentDescription?.toString()=="独立输入测试框"}
                editor?.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK)==true
            }
            waitFor("keyboard attached to current field") {views().filterIsInstance<KeyboardView>().any {
                it.isShown && !it.preview && (it.listener as? KongyeIME)?.currentInputEditorInfo?.packageName=="com.kongye.keyboard.test"
            }}
            lateinit var ime:KongyeIME
            ui {
                ime=views().filterIsInstance<KeyboardView>().first {it.isShown && !it.preview}.listener as KongyeIME
                KongyeIME::class.java.getDeclaredMethod("loadAssociations").apply {isAccessible=true}.invoke(ime)
            }
            fun row()=views().filterIsInstance<TextView>().firstOrNull {it.isShown && it.text.toString().startsWith("Keyboard association fixture")}
            waitFor("masked association") {row()!=null}
            Thread.sleep(NativeMotion.DURATION+180)
            auto.takeScreenshot()?.also {shot->java.io.File(context.getExternalFilesDir(null),"review-243-association.png").outputStream().use {shot.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)};shot.recycle()}
            fun action(label:String)=views().filterIsInstance<TextView>().firstOrNull {it.isShown && it.text.toString()==label && it.isClickable}
            fun visibleAction(label:String) {
                val button=action(label) ?: error("Missing action $label")
                val rect=android.graphics.Rect()
                check(button.getGlobalVisibleRect(rect) && rect.height()==button.height && rect.width()==button.width) {"Clipped action $label"}
            }
            ui {visibleAction("返回键盘");visibleAction("刷新");check(row()!!.text.toString()=="Keyboard association fixture");visibleAction("解除关联");val deleteButton=action("解除关联")!!;deleteButton.performClick();deleteButton.performClick();
                ime.onStartInputView(ime.currentInputEditorInfo,true)
                check(action("正在解除…")!=null) {"Editor restart interrupted removal"}
            }
            waitFor("association removed") {row()==null && views().filterIsInstance<TextView>().any {it.isShown && it.text=="此页面没有关联账号"}}
            ui {check(views().filterIsInstance<TextView>().none {it.text.toString().contains("SyntheticOnly!7")})}
            inst.sendStatus(0,Bundle().apply {putString("stream","KEYBOARD MASKED ASSOCIATION / SINGLE CLICK / DUPLICATE GUARD CHECKS PASSED\n")})
        } finally {
            ui {
                val edit=prefs.edit()
                if(oldEnabled is Boolean)edit.putBoolean(Prefs.VAULT,oldEnabled) else edit.remove(Prefs.VAULT)
                if(oldSource is String)edit.putString(Prefs.VAULT_PACKAGE,oldSource) else edit.remove(Prefs.VAULT_PACKAGE)
                edit.commit()
            }
        }
    }
}
