package com.kongye.keyboard

import android.app.Instrumentation
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo as Node
import androidx.compose.runtime.MutableState
import java.io.File

/** Exercises the production dialog; simulated server/download states never launch an installer. */
object UpdateDialogRegression {
    fun run(i:Instrumentation) {
        val ctx=i.targetContext
        val auto=i.getUiAutomation(android.app.UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        auto.serviceInfo=auto.serviceInfo.apply {flags=flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS}
        val activity=java.util.concurrent.atomic.AtomicReference<SettingsActivity>()
        val app=ctx.applicationContext as android.app.Application
        val callback=object:android.app.Application.ActivityLifecycleCallbacks {
            override fun onActivityResumed(a:android.app.Activity){if(a is SettingsActivity)activity.set(a)}
            override fun onActivityCreated(a:android.app.Activity,b:Bundle?){}
            override fun onActivityStarted(a:android.app.Activity){}
            override fun onActivityPaused(a:android.app.Activity){}
            override fun onActivityStopped(a:android.app.Activity){}
            override fun onActivitySaveInstanceState(a:android.app.Activity,b:Bundle){}
            override fun onActivityDestroyed(a:android.app.Activity){}
        }
        app.registerActivityLifecycleCallbacks(callback)
        fun tree(n:Node?):List<Node> = if(n==null)emptyList() else listOf(n)+(0 until n.childCount).flatMap {tree(n.getChild(it))}
        fun nodes():List<Node>{
            auto.clearCache()
            return tree(auto.rootInActiveWindow) + auto.windows.flatMap {tree(it.root)}
        }
        fun has(t:String)=nodes().any {it.text?.lines()?.contains(t)==true||it.contentDescription?.toString()==t}
        fun hasProgress(expected:Float?)=nodes().any { node ->
            val range=node.rangeInfo
            node.isVisibleToUser && node.className=="android.widget.ProgressBar" &&
                if(expected==null) range==null
                else range!=null && range.min==0f && range.max==1f && kotlin.math.abs(range.current-expected)<.005f
        }
        fun waitFor(name:String,f:()->Boolean){
            val end=SystemClock.uptimeMillis()+35000
            while(SystemClock.uptimeMillis()<end){if(f())return;SystemClock.sleep(80)}
            val visible=nodes().mapNotNull {it.text?.toString()?.takeIf(String::isNotBlank)}.distinct().take(15)
            error("Timed out: $name, foreground=${auto.rootInActiveWindow?.packageName}, labels=$visible")
        }
        fun click(t:String) {
            var found:Node?=null
            for(attempt in 0..14) {
                found=nodes().firstOrNull {(it.text?.toString()==t||it.contentDescription?.toString()==t)&&it.isVisibleToUser&&!it.isEditable}
                if(found!=null)break
                nodes().firstOrNull {it.isScrollable}?.performAction(Node.ACTION_SCROLL_FORWARD);SystemClock.sleep(160)
            }
            var n=checkNotNull(found){"Missing "+t};n.performAction(Node.AccessibilityAction.ACTION_SHOW_ON_SCREEN.id);SystemClock.sleep(100)
            repeat(5){if(n.performAction(Node.ACTION_CLICK)){SystemClock.sleep(220);return};n=n.parent?:error("Not clickable "+t)}
        }
        fun ui(f:()->Unit)=i.runOnMainSync(f)
        fun shell(cmd:String)=android.os.ParcelFileDescriptor.AutoCloseInputStream(auto.executeShellCommand(cmd)).use {it.readBytes()}
        fun home(){
            activity.set(i.startActivitySync(Intent(ctx,SettingsActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)) as SettingsActivity)
            waitFor("home"){has("显示键盘")&&nodes().any {it.isEditable}}
        }
        val pictures=File(ctx.getExternalFilesDir(null),"update-dialog-pictures").apply {mkdirs()}
        fun capture(name:String){SystemClock.sleep(500);val b=checkNotNull(auto.takeScreenshot());File(pictures,name+".png").outputStream().use {b.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)};b.recycle()}
        @Suppress("UNCHECKED_CAST")
        fun <T> state(name:String)=SettingsActivity::class.java.getDeclaredField(name).apply {isAccessible=true}.get(activity.get()) as MutableState<T>
        fun checking()=state<Boolean>("checkingUpdate\$delegate").value
        val prefs=Prefs.of(ctx);val previousCheck=prefs.getLong(Prefs.UPDATE_CHECKED,0L);val hadCheck=prefs.contains(Prefs.UPDATE_CHECKED)
        try {
            ui {AppUpdate.clearInstallationResult(ctx)}
            home();click("输入设置");capture("01-菜单同宽对齐-小箭头")
            click("关于");click("检查更新")
            waitFor("real network check finished"){!checking()&&has("更新历史")}
            val rel=state<UpdateChecker.Release?>("release");val file=state<File?>("downloadedApk");val download=state<Boolean>("downloading");val message=state<String>("updateState");val progress=state<String>("downloadState")
            val fraction=state<Float?>("downloadProgress");val validating=state<Boolean>("validatingUpdate")
            val original=listOf(rel.value,file.value,download.value,message.value,progress.value,fraction.value,validating.value)
            try {
                val version=UpdateChecker.currentVersion(ctx)
                val future=UpdateChecker.Release("v99.0.0","UI state regression only","https://github.com/Mayleaves/KongyeKeyboard/releases/download/v99.0.0/test.apk","")
                ui {rel.value=future;file.value=null;message.value="有新版本 v99.0.0";progress.value=""}
                waitFor("download required"){has("发现新版本")&&has("下载更新")};check(!has("安装更新"))
                capture("02-检查更新-原页面弹窗")
                ui {download.value=true;fraction.value=.37f}
                waitFor("download progress"){has("正在下载安装包")&&has("37%")&&hasProgress(.37f)&&has("取消下载")}
                check(!has("安装更新"))
                capture("03-下载安装包-进度条")
                ui {fraction.value=null;progress.value="已下载 24 MB"}
                waitFor("unknown download size"){has("已下载 24 MB")&&!has("37%")&&hasProgress(null)}
                ui {fraction.value=1f}
                ui {validating.value=true}
                waitFor("validation status"){has("正在校验安装包")&&!has("100%")&&!has("取消下载")&&hasProgress(null)}
                ui {validating.value=false}
                ui {download.value=false;rel.value=UpdateChecker.Release("v99.0.0","",null,"")}
                waitFor("no compatible asset"){has("暂无适配本机的安装包。")};check(!has("下载更新")&&!has("安装更新"))
                ui {rel.value=future;file.value=File(ctx.applicationInfo.sourceDir)}
                val granted=ctx.packageManager.canRequestPackageInstalls()
                waitFor("validated package action"){has(if(granted)"安装更新" else "允许安装")}
                ui {file.value=null;rel.value=UpdateChecker.Release("v"+version,"",null,"")}
                waitFor("up to date"){has("当前版本 v"+version+"，已是最新版本。")};check(!has("下载更新")&&!has("安装更新"))
                ui {rel.value=null;message.value="检查失败：测试连接失败"}
                waitFor("retry after failure"){has("检查失败：测试连接失败")&&has("重新检查")};check(!has("下载更新")&&!has("安装更新"))
            } finally {
                ui {rel.value=original[0] as UpdateChecker.Release?;file.value=original[1] as File?;download.value=original[2] as Boolean;message.value=original[3] as String;progress.value=original[4] as String;fraction.value=original[5] as Float?;validating.value=original[6] as Boolean}
            }
            shell("input keyevent 4")
            waitFor("original page"){!has("软件更新")&&!has("发现新版本")&&has("检查更新")&&has("关于")}
            home();val search=nodes().first {it.isEditable};check(search.performAction(Node.ACTION_SET_TEXT,Bundle().apply {putCharSequence(Node.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,"检查更新")}));SystemClock.sleep(300)
            click("检查更新");waitFor("search entry dialog"){has("软件更新")&&has("更新历史")}
            shell("input keyevent 4");waitFor("search retained"){!has("软件更新")&&nodes().any {it.isEditable&&it.text?.toString()=="检查更新"}}
            i.sendStatus(0,Bundle().apply {putString("stream","UPDATE DIALOG PASSED: real check, original page/search preserved; verified known-size and unknown-size progress bars, validation, missing APK, cached APK with actual permission, current version and failure states. Installer was not launched.\n")})
        } finally {
            app.unregisterActivityLifecycleCallbacks(callback)
            ui {prefs.edit().apply {if(hadCheck)putLong(Prefs.UPDATE_CHECKED,previousCheck)else remove(Prefs.UPDATE_CHECKED)}.commit()}
        }
    }
}
