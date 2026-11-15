package com.kongye.keyboard

import android.app.Instrumentation
import android.app.UiAutomation
import android.graphics.Rect
import android.os.Bundle
import android.os.SystemClock
import android.view.Choreographer
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import android.view.inspector.WindowInspector
import android.widget.inline.InlineContentView
import kotlin.math.abs

/** Real browser fields; records geometry only, never reads credential text. */
object AutofillTransitionRegression {
    fun run(inst: Instrumentation) {
        val auto=inst.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        auto.serviceInfo=auto.serviceInfo.apply { flags=flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS }
        fun shell(command: String)=auto.executeShellCommand(command).use { java.io.FileInputStream(it.fileDescriptor).use { input -> input.readBytes() } }
        @Suppress("UNCHECKED_CAST")
        fun <T> main(action: ()->T): T {
            var value: Any?=null;var failure: Throwable?=null
            inst.runOnMainSync { try { value=action() } catch(t: Throwable) { failure=t } }
            failure?.let { throw it };return value as T
        }
        fun waitFor(label: String,condition: ()->Boolean) {
            val end=SystemClock.uptimeMillis()+12000
            while(SystemClock.uptimeMillis()<end) { if(condition()) return;Thread.sleep(80) }
            error("Timed out: $label")
        }
        fun descendants(view: View): List<View> = listOf(view)+(if(view is ViewGroup) (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList())
        fun ownViews()=WindowInspector.getGlobalWindowViews().flatMap { descendants(it) }
        fun keyboard()=ownViews().filterIsInstance<KeyboardView>().firstOrNull { it.isShown && it.height>100 }
        fun nodes(node: AccessibilityNodeInfo?): List<AccessibilityNodeInfo> = if(node==null) emptyList() else listOf(node)+(0 until node.childCount).flatMap { nodes(node.getChild(it)) }
        fun browserNodes(pkg: String): List<AccessibilityNodeInfo> {
            auto.clearCache()
            return auto.windows.mapNotNull { it.root }.filter { it.packageName?.toString()==pkg }.flatMap { nodes(it) }
        }
        fun fields(pkg: String)=browserNodes(pkg).filter { it.className?.toString()=="android.widget.EditText" && it.viewIdResourceName.isNullOrEmpty() && it.isVisibleToUser }
        fun tap(node: AccessibilityNodeInfo) { val at=Rect();node.getBoundsInScreen(at);check(at.height()>0);shell("input tap ${at.centerX()} ${at.centerY()}") }
        // Starting instrumentation restarts the IME process. Establish a fresh
        // isolated editor first so Android discards the previous input connection.
        shell("am force-stop com.kongye.keyboard.test")
        inst.targetContext.startActivity(android.content.Intent(inst.context,TypingFormActivity::class.java)
            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK).putExtra("bottom",true))
        waitFor("isolated editor warmup") {
            auto.clearCache()
            val field=auto.windows.mapNotNull {it.root}.flatMap {nodes(it)}.firstOrNull {
                it.packageName?.toString()=="com.kongye.keyboard.test" && it.contentDescription?.toString()=="独立输入测试框"
            }
            if(field!=null && main {keyboard()==null})tap(field)
            main {keyboard()!=null}
        }
        for(pkg in listOf("mark.via","com.mmbox.xbrowser","com.android.chrome")) {
            shell("am force-stop $pkg")
            shell("am start -a android.intent.action.VIEW -d https://shimo.im/loginByPassword -p $pkg")
            waitFor("login fields $pkg") { fields(pkg).size>=2 }
            tap(fields(pkg).first { !it.isPassword })
            waitFor("keyboard $pkg") {
                if(main {keyboard()==null}) fields(pkg).firstOrNull { !it.isPassword }?.let {tap(it)}
                main {keyboard()!=null}
            }
            waitFor("visible system candidate width $pkg") { main { ownViews().filterIsInstance<InlineContentView>().any { it.isShown && it.width>0 } } }
            val widths=main { ownViews().filterIsInstance<InlineContentView>().filter { it.isShown }.map { it.width } }
            check(widths.isNotEmpty() && widths.all { it>0 }) { "Collapsed inline candidates: $widths" }
            main {
                val host=ownViews().filterIsInstance<CandidateHost>().first {it.isShown}
                val bar=host.getChildAt(0)
                val gear=ownViews().first {it.isShown && it.contentDescription=="管理此页面的账号推荐"}
                val close=ownViews().first {it.isShown && it.contentDescription=="收起自动填充候选"}
                check(gear.parent===close.parent && gear.right==close.left)
                check(close.width<=33*inst.targetContext.resources.displayMetrics.density)
                check(host.height==bar.measuredHeight && host.height<100*inst.targetContext.resources.displayMetrics.density) {"Inline host expanded to ${host.height}"}
                check(keyboard()!!.height<inst.targetContext.resources.displayMetrics.heightPixels*.65) {"Keyboard filled browser"}
                check(ownViews().filterIsInstance<android.widget.TextView>().none { it.isShown && (it.text?.toString()=="🔑 更多" || it.text?.toString()?.startsWith("🔑 密码空间")==true) })
            }
            check(auto.windows.none { it.title?.toString()=="Autofill UI" }) { "Unexpected floating autofill dropdown" }
            val translations=mutableListOf<Float>()
            var missing=0
            var missingToolbar=0
            val frame=object: Choreographer.FrameCallback {
                override fun doFrame(time: Long) {
                    val view=keyboard();if(view==null) missing++ else translations+=view.translationY
                    if(ownViews().none { it.isShown && it.contentDescription=="收起自动填充候选" }) missingToolbar++
                    Choreographer.getInstance().postFrameCallback(this)
                }
            }
            main { Choreographer.getInstance().postFrameCallback(frame) }
            try {
                for(password in listOf(true,false,true,false,true,false)) { tap(fields(pkg).first { it.isPassword==password });Thread.sleep(75) }
            } finally { main { Choreographer.getInstance().removeFrameCallback(frame) } }
            check(missing==0 && translations.size>10 && translations.all { abs(it)<0.1f }) { "$pkg keyboard missing=$missing maxTranslation=${translations.maxOfOrNull { abs(it) }}" }
            check(missingToolbar==0) { "$pkg autofill toolbar disappeared for $missingToolbar frames" }
            waitFor("active toolbar after switching $pkg") { main { ownViews().any { it.isShown && it.contentDescription=="收起自动填充候选" && (it.parent as? View)?.importantForAccessibility!=View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS } } }
            inst.sendStatus(0,Bundle().apply { putString("stream","PASS $pkg login: inline widths=$widths, ${translations.size} frames, no keyboard loss or translation\n") })
            val moreAt=main {
                val view=ownViews().filterIsInstance<InlineContentView>().last { it.isShown && it.width>0 }
                val location=IntArray(2);view.getLocationOnScreen(location)
                Rect(location[0],location[1],location[0]+view.width,location[1]+view.height)
            }
            shell("input tap ${moreAt.centerX()} ${moreAt.centerY()}")
            waitFor("system password search page $pkg") { browserNodes("com.kongye.docs").any { it.text?.toString()=="选择要填入的密码" } }
            val searchTops=mutableListOf<Int>()
            repeat(14) {
                val title=browserNodes("com.kongye.docs").first { it.text?.toString()=="选择要填入的密码" }
                val bounds=Rect();title.getBoundsInScreen(bounds);searchTops+=bounds.top
                Thread.sleep(40)
            }
            check(searchTops.max()-searchTops.min()<3) { "$pkg password search title shifted: $searchTops" }
            check(main { keyboard()!=null }) { "Password search has no keyboard" }
            inst.sendStatus(0,Bundle().apply { putString("stream","PASS $pkg system more opens fixed password search; title y=${searchTops.first()}\n") })
            shell("input keyevent 4");Thread.sleep(200);shell("input keyevent 4")
            shell("am start -a android.intent.action.VIEW -d https://shimo.im/register -p $pkg")
            waitFor("personal registration choice $pkg") { browserNodes(pkg).any { it.className?.toString()!="android.widget.EditText" && it.text?.toString()=="为我自己注册" } }
            tap(browserNodes(pkg).first { it.className?.toString()!="android.widget.EditText" && it.text?.toString()=="为我自己注册" })
            waitFor("registration field $pkg") { fields(pkg).isNotEmpty() }
            tap(fields(pkg).first())
            waitFor("registration keyboard and candidate $pkg") { main { keyboard()!=null && ownViews().filterIsInstance<InlineContentView>().any { it.isShown && it.width>0 } } }
            Thread.sleep(600)
            check(main {
                val host=ownViews().filterIsInstance<CandidateHost>().first {it.isShown}
                keyboard()!=null && host.height==host.getChildAt(0).measuredHeight
            })
            inst.sendStatus(0,Bundle().apply { putString("stream","PASS $pkg registration: visible system candidate and keyboard\n") })
        }
    }
}
