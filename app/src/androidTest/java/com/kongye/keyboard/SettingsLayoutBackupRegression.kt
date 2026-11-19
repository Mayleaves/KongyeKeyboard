package com.kongye.keyboard

import android.app.Instrumentation
import android.os.Bundle
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import java.io.File

/** Exercises orientation tabs and destination feedback without accessing personal backups. */
object SettingsLayoutBackupRegression {
    fun run(i:Instrumentation) {
        check(android.os.Build.MODEL.startsWith("sdk_"))
        val ctx=i.targetContext;val prefs=Prefs.of(ctx);val saved=prefs.all.toMap()
        val auto=i.getUiAutomation(android.app.UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        auto.serviceInfo=auto.serviceInfo.apply {flags=flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS}
        fun shell(command:String)=android.os.ParcelFileDescriptor.AutoCloseInputStream(auto.executeShellCommand(command)).use {it.readBytes().decodeToString().trim()}
        val rotation=shell("settings get system user_rotation");val sensor=shell("settings get system accelerometer_rotation")
        fun report(message:String)=i.sendStatus(0,Bundle().apply {putString("stream",message+"\n")})
        fun ui(block:()->Unit)=i.runOnMainSync {block()}
        fun nodes(n:AccessibilityNodeInfo?):List<AccessibilityNodeInfo> = if(n==null)emptyList() else listOf(n)+(0 until n.childCount).flatMap {nodes(n.getChild(it))}
        fun all():List<AccessibilityNodeInfo> {auto.clearCache();return auto.windows.flatMap {nodes(it.root)}}
        fun label(n:AccessibilityNodeInfo)=n.text?.toString() ?: n.contentDescription?.toString().orEmpty()
        fun has(text:String)=all().any {label(it)==text&&it.isVisibleToUser}
        fun waitFor(text:String,block:()->Boolean) {val until=SystemClock.uptimeMillis()+20000;while(SystemClock.uptimeMillis()<until){if(block())return;SystemClock.sleep(100)};runCatching {auto.takeScreenshot().let {b->java.io.File(ctx.getExternalFilesDir(null),"settings-ui-failure.png").outputStream().use {b.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)};b.recycle()}};error("Timed out: $text")}
        fun scroll(forward:Boolean=true) {
            val dm=ctx.resources.displayMetrics
            val start=SystemClock.uptimeMillis()
            val y1=dm.heightPixels*(if(forward).79f else .32f)
            val y2=dm.heightPixels*(if(forward).32f else .79f)
            fun touch(action:Int,y:Float) {
                android.view.MotionEvent.obtain(start,SystemClock.uptimeMillis(),action,dm.widthPixels*.5f,y,0).let {event->
                    event.source=android.view.InputDevice.SOURCE_TOUCHSCREEN
                    check(auto.injectInputEvent(event,true));event.recycle()
                }
            }
            touch(android.view.MotionEvent.ACTION_DOWN,y1)
            repeat(16) {step->SystemClock.sleep(16);touch(android.view.MotionEvent.ACTION_MOVE,y1+(y2-y1)*(step+1)/16)}
            touch(android.view.MotionEvent.ACTION_UP,y2)
            SystemClock.sleep(180)
        }
        fun find(text:String):AccessibilityNodeInfo {
            for(forward in listOf(true,false)) repeat(8) {
                all().firstOrNull {label(it)==text&&it.isVisibleToUser}?.let {return it}
                scroll(forward)
            }
            runCatching {auto.takeScreenshot().let {b->java.io.File(ctx.getExternalFilesDir(null),"settings-ui-failure.png").outputStream().use {b.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)};b.recycle()}}
            report("Visible labels: "+all().filter {it.isVisibleToUser}.map(::label).filter {it.isNotBlank()}.take(35))
            error("Missing $text")
        }
        fun click(text:String) {
            var n:AccessibilityNodeInfo?=find(text)
            repeat(6) {if(n?.performAction(AccessibilityNodeInfo.ACTION_CLICK)==true){SystemClock.sleep(350);return};n=n?.parent}
            error("Cannot click $text")
        }
        var activity:SettingsActivity?=null
        fun home() {
            val monitor=i.addMonitor(SettingsActivity::class.java.name,null,false)
            shell("am start -W -f 0x10008000 -n com.kongye.keyboard/.SettingsActivity")
            activity=i.waitForMonitorWithTimeout(monitor,10000) as? SettingsActivity
            i.removeMonitor(monitor)
            check(activity!=null)
            waitFor("home"){has("今日输入")}
        }
        val pictures=File(ctx.getExternalFilesDir(null),"layout-backup-pictures").apply {mkdirs()}
        fun shot(name:String) {SystemClock.sleep(250);auto.takeScreenshot().let {b->File(pictures,"$name.png").outputStream().use {b.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)};b.recycle()}}
        @Suppress("UNCHECKED_CAST") fun feedback():androidx.compose.runtime.MutableState<Map<Boolean,Any>> = SettingsActivity::class.java.getDeclaredField("backupFeedback").apply {isAccessible=true}.get(activity) as androidx.compose.runtime.MutableState<Map<Boolean,Any>>
        fun setFeedback(cloud:Boolean,title:String) {
            val c=Class.forName("com.kongye.keyboard.SettingsActivity\$BackupFeedback").getDeclaredConstructor(String::class.java,String::class.java,java.lang.Boolean::class.java).apply {isAccessible=true}
            ui {val state=feedback();state.value=state.value+(cloud to c.newInstance(title,"模拟器验证消息，不连接服务器。",true))}
            SystemClock.sleep(450)
        }
        try {
            shell("settings put system accelerometer_rotation 0");shell("settings put system user_rotation 0")
            ui {prefs.edit().putBoolean(V3Prefs.ORIENTATION_LAYOUT,true).putBoolean(Prefs.FLOAT,false).putBoolean(Prefs.SPLIT_LAYOUT,false)
                .putBoolean(V3Prefs.LANDSCAPE_FLOAT,false).putBoolean(V3Prefs.LANDSCAPE_SPLIT,false)
                .putString(Prefs.LAYOUT,"26").putString(V3Prefs.LAYOUT_LANDSCAPE,"14").putInt(V3Prefs.ONE_HAND,1).commit()}
            home();click("键盘布局")
            find("竖屏键盘形态：正常键盘");check(has("竖屏")&&has("横屏"))
            shot("01-layout-portrait")
            click("竖屏键盘形态：悬浮键盘")
            find("悬浮分体键盘");check(!has("单手模式")&&prefs.getBoolean(Prefs.FLOAT,false))
            check(!prefs.getBoolean(V3Prefs.LANDSCAPE_FLOAT,true)&&prefs.getInt(V3Prefs.ONE_HAND,0)==1)
            shot("02-layout-floating-options")
            click("横屏");click("横屏键盘形态：分体键盘")
            find("铺满键盘空白区域");check(!has("单手模式")&&!has("悬浮分体键盘"))
            check(prefs.getBoolean(V3Prefs.LANDSCAPE_SPLIT,false)&&prefs.getBoolean(Prefs.FLOAT,false))
            shot("03-layout-landscape-split-options")
            val dm=ctx.resources.displayMetrics
            SystemClock.sleep(500)
            val started=SystemClock.uptimeMillis()
            fun touch(action:Int,x:Float) {
                android.view.MotionEvent.obtain(started,SystemClock.uptimeMillis(),action,x,dm.heightPixels/2f,0).let {event->
                    event.source=android.view.InputDevice.SOURCE_TOUCHSCREEN
                    check(auto.injectInputEvent(event,true));event.recycle()
                }
            }
            report("Swipe display ${dm.widthPixels}x${dm.heightPixels}")
            touch(android.view.MotionEvent.ACTION_DOWN,dm.widthPixels*.12f)
            repeat(24) {step->SystemClock.sleep(20);touch(android.view.MotionEvent.ACTION_MOVE,dm.widthPixels*(.12f+.76f*(step+1)/24))}
            touch(android.view.MotionEvent.ACTION_UP,dm.widthPixels*.88f)
            SystemClock.sleep(700)
            find("竖屏键盘形态：悬浮键盘")
            report("Swipe selected portrait; locating the preserved portrait controls")
            click("竖屏键盘形态：正常键盘");find("单手模式")
            check(prefs.getInt(V3Prefs.ONE_HAND,0)==1&&prefs.getBoolean(V3Prefs.LANDSCAPE_SPLIT,false))
            ui {V3Prefs.setSeparateLayouts(ctx,false)}
            waitFor("shared layout"){has("共用布局")};find("共用键盘形态：正常键盘")
            check(!has("竖屏")&&!has("横屏"))
            report("PASS: orientation tabs and swipe; independent form saves; contextual options; shared mode preserves landscape and single-hand settings")

            home();click("备份与恢复")
            setFeedback(true,"连接测试成功")
            check(!has("连接测试成功"))
            click("WebDAV 备份");waitFor("cloud feedback"){has("连接测试成功")}
            shot("04-cloud-feedback")
            click("服务器配置");check(has("连接测试成功"));click("服务器配置");check(has("连接测试成功"))
            click("本地备份");check(!has("连接测试成功"))
            setFeedback(false,"导出备份成功");check(has("导出备份成功"))
            shot("05-local-feedback")
            click("WebDAV 备份");check(has("连接测试成功")&&!has("导出备份成功"))
            click("关闭操作提示");waitFor("cloud feedback closed"){!has("连接测试成功")}
            click("本地备份");check(has("导出备份成功"));click("关闭操作提示")
            find("恢复默认设置")
            check(all().count {label(it)=="恢复默认设置"}==1)
            click("恢复默认设置");check(has("恢复默认设置？"));shot("06-common-reset-confirmation");click("取消")
            check(prefs.getInt(V3Prefs.ONE_HAND,0)==1)
            report("PASS: local/cloud feedback isolation, independent close, persistent feedback after folding; one common reset entry; reset cancelled without changing data")
            report("LAYOUT AND BACKUP UI PASSED")
        } finally {
            ui {val edit=prefs.edit().clear();saved.forEach {(k,v)->when(v){is Boolean->edit.putBoolean(k,v);is Int->edit.putInt(k,v);is Long->edit.putLong(k,v);is Float->edit.putFloat(k,v);is String->edit.putString(k,v);is Set<*> -> @Suppress("UNCHECKED_CAST") edit.putStringSet(k,v as Set<String>)}};edit.commit()}
            shell("settings put system user_rotation $rotation");shell("settings put system accelerometer_rotation $sensor")
        }
    }
}
