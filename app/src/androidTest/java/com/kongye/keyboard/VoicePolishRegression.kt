package com.kongye.keyboard

import android.app.Instrumentation
import android.os.Bundle
import android.os.SystemClock
import android.content.Intent
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import android.view.inspector.WindowInspector
import java.io.File
import java.net.ServerSocket
import java.net.InetAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.json.JSONObject

object VoicePolishRegression {
    fun run(inst:Instrumentation,offline:Boolean) {
        val ctx=inst.targetContext;val p=Prefs.of(ctx);val backup=p.all.toMap()
        val secretFile=File(ctx.noBackupFilesDir,"polish-api-key");val secretBackup=secretFile.takeIf {it.exists()}?.readBytes()
        fun report(text:String)=inst.sendStatus(0,Bundle().apply {putString("stream",text+"\n")})
        @Suppress("UNCHECKED_CAST") fun <T> ui(block:()->T):T {var out:Any?=null;var error:Throwable?=null;inst.runOnMainSync {try{out=block()}catch(t:Throwable){error=t}};error?.let {throw it};return out as T}
        fun waitFor(name:String,check:()->Boolean){val until=SystemClock.uptimeMillis()+30000;while(SystemClock.uptimeMillis()<until){if(check())return;SystemClock.sleep(50)};error("Timed out: $name")}
        fun request(source:String):Result<String> {
            val done=CountDownLatch(1);val result=AtomicReference<Result<String>>()
            ui {VoicePolish.request(ctx,source,{}) {result.set(it);done.countDown()}}
            check(done.await(100,TimeUnit.SECONDS)) {"Polish callback timed out"}
            return result.get()
        }
        class Mock(val source:String,val output:String,val status:Int=200,val delayMs:Long=0):java.io.Closeable {
            val server=ServerSocket(0,1,InetAddress.getByName("127.0.0.1"))
            val failure=AtomicReference<Throwable?>();val done=CountDownLatch(1)
            val address="http://127.0.0.1:${server.localPort}/v1"
            init {Thread {
                try {server.soTimeout=30000;server.accept().use {socket->
                    socket.soTimeout=30000;val input=java.io.DataInputStream(socket.getInputStream());val headers=StringBuilder()
                    while(!headers.endsWith("\r\n\r\n")){check(headers.length<8192);headers.append(input.readUnsignedByte().toChar())}
                    val head=headers.toString();check(head.startsWith("POST /v1/chat/completions HTTP/1.1"))
                    check(head.contains("Authorization: Bearer KONGYE_TEST_ONLY",true))
                    val length=Regex("Content-Length: (\\d+)",RegexOption.IGNORE_CASE).find(head)!!.groupValues[1].toInt()
                    check(length in 1..20000);val body=ByteArray(length);input.readFully(body)
                    val json=JSONObject(String(body,Charsets.UTF_8));check(json.getString("model")=="fixture-model")
                    check(!json.getBoolean("stream"));check(json.getJSONArray("messages").getJSONObject(1).getString("content").contains(source))
                    val payload=if(status==200)JSONObject().put("choices",org.json.JSONArray().put(JSONObject().put("message",JSONObject().put("content",output)))).toString() else "{\"error\":\"KONGYE_TEST_ONLY\"}"
                    val bytes=payload.toByteArray(Charsets.UTF_8)
                    val response="HTTP/1.1 $status ${if(status==200)"OK" else "Unauthorized"}\r\nContent-Type: application/json\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n"
                    if(delayMs>0) SystemClock.sleep(delayMs)
                    socket.getOutputStream().use {it.write(response.toByteArray(Charsets.US_ASCII));it.write(bytes)}
                }} catch(t:Throwable){failure.set(t)}finally {done.countDown()}
            }.start()}
            fun verify(){check(done.await(5,TimeUnit.SECONDS));failure.get()?.let {throw it}}
            override fun close(){server.close()}
        }
        fun configure(mock:Mock){ui {p.edit().putInt(VoicePolish.MODE,1).putString(VoicePolish.ENDPOINT,mock.address).putString(VoicePolish.MODEL,"fixture-model").commit()}}
        try {
            ui {p.edit().putBoolean(V3Prefs.VOICE_CLEAN,false).putBoolean(V3Prefs.VOICE_NUMBERS,false).putBoolean(V3Prefs.VOICE_PUNCT,true).commit()}
            if(offline) {
                ui {p.edit().putInt(VoicePolish.MODE,0).putString(VoicePolish.ENDPOINT,"https://invalid.invalid/v1").commit()}
                val before=android.os.Debug.getPss()
                val start=SystemClock.uptimeMillis();val one=request("这个方案我觉得明天讨论再。").getOrThrow()
                check(one.contains("明天")&&one.contains("讨论")&&one.contains("方案")) {"Offline rewrite did not retain the topic: $one"}
                report("Offline arbitrary-sentence rewrite: $one [${SystemClock.uptimeMillis()-start} ms]")
                val two=request("预算是3200元，下周二再确认这个安排我们。").getOrThrow()
                check(two.contains("3200") && two.contains("下周二") && two.contains("确认")) {"Offline rewrite changed protected facts: $two"}
                report("Offline numbers and date retained: $two")
                System.gc();SystemClock.sleep(600);val after=android.os.Debug.getPss()
                check(after<before+400*1024) {"Model memory was retained after inference: before=$before after=$after KB"}
                report("Native model freed after inference: PSS $before -> $after KB")
                report("REAL OFFLINE LLM POLISH PASSED");return
            }
            check(VoicePolish.endpoint("https://example.invalid/v1").path=="/v1/chat/completions")
            check(runCatching {VoicePolish.endpoint("http://example.invalid/v1")}.isFailure)
            check(runCatching {VoicePolish.endpoint("https://user:secret@example.invalid/v1")}.isFailure)
            check(VoicePolish.cleanResult("<think>private reasoning</think>整理后的原文。")=="整理后的原文。")
            PolishSecret.save(ctx,"KONGYE_TEST_ONLY");check(PolishSecret.read(ctx)=="KONGYE_TEST_ONLY")
            val source="这个方案我觉得明天讨论再。";val output="我们明天再讨论这个方案。"
            Mock(source,output).use {mock->configure(mock);check(request(source).getOrThrow()==output);mock.verify()}
            Mock(source,output,401).use {mock->configure(mock);val result=request(source);check(result.isFailure);check(!result.exceptionOrNull()!!.message.orEmpty().contains("KONGYE_TEST_ONLY"));mock.verify()}
            report("Loopback HTTP protocol, encrypted key round-trip, endpoint validation and safe HTTP-error handling passed")
            val auto=inst.getUiAutomation(android.app.UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
            auto.serviceInfo=auto.serviceInfo.apply {flags=flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS}
            fun nodes(n:AccessibilityNodeInfo?):List<AccessibilityNodeInfo> = if(n==null)emptyList() else listOf(n)+(0 until n.childCount).flatMap {nodes(n.getChild(it))}
            fun editor():AccessibilityNodeInfo? {auto.clearCache();return auto.windows.flatMap {nodes(it.root)}.firstOrNull {it.packageName?.toString()=="com.kongye.keyboard.test"&&it.contentDescription?.toString()=="独立输入测试框"}}
            fun descendants(v:View):List<View> = listOf(v)+(if(v is ViewGroup)(0 until v.childCount).flatMap {descendants(v.getChildAt(it))}else emptyList())
            fun views()=WindowInspector.getGlobalWindowViews().flatMap {descendants(it)}
            fun keyboard()=views().filterIsInstance<KeyboardView>().firstOrNull {it.isShown&&!it.preview}
            ui {p.edit().putBoolean(Prefs.VAULT,false).putBoolean(V3Prefs.VOICE_POLISH,true).commit()}
            android.os.ParcelFileDescriptor.AutoCloseInputStream(auto.executeShellCommand("am start -W -f 0x10008000 -n com.kongye.keyboard.test/com.kongye.keyboard.TypingFormActivity --ez bottom true")).use {it.readBytes()}
            waitFor("isolated editor") {editor()!=null};val bounds=android.graphics.Rect();editor()!!.getBoundsInScreen(bounds)
            val now=SystemClock.uptimeMillis()
            for(action in intArrayOf(0,1)){val e=android.view.MotionEvent.obtain(now,SystemClock.uptimeMillis(),action,bounds.centerX().toFloat(),bounds.centerY().toFloat(),0);e.source=android.view.InputDevice.SOURCE_TOUCHSCREEN;try{check(auto.injectInputEvent(e,true))}finally{e.recycle()}}
            waitFor("IME window"){ui {keyboard()!=null}}
            val live=ui {keyboard()!!.listener as KongyeIME}
            fun text()=editor()?.text?.toString().orEmpty().takeUnless {it=="只使用虚构测试内容"}.orEmpty()
            fun click(label:String){ui {views().filterIsInstance<android.widget.TextView>().first {it.isShown&&it.text.toString()==label}.performClick()}}
            val open=KongyeIME::class.java.getDeclaredMethod("openVoice").apply {isAccessible=true}
            val update=KongyeIME::class.java.getDeclaredMethod("updateVoice").apply {isAccessible=true}
            val field=KongyeIME::class.java.getDeclaredField("voiceText").apply {isAccessible=true}
            fun seed() {
                editor()!!.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT,Bundle().apply {putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,"")});SystemClock.sleep(150)
                ui {open.invoke(live);(field.get(live) as StringBuilder).apply {setLength(0);append(source)};update.invoke(live)}
                waitFor("voice panel opened") { ui { views().filterIsInstance<VoiceBoard>().any { it.isShown } } }
            }
            Mock(source,output).use {mock->configure(mock);seed();click("确定");waitFor("polish preview"){text()==output&&ui {views().filterIsInstance<android.widget.TextView>().any {it.isShown&&it.text.toString()=="恢复原文"}}}
                check(ui {views().filterIsInstance<VoiceBoard>().any {it.isShown}});click("恢复原文");waitFor("original restored"){text()==source};click("确定");waitFor("original confirmed"){text()==source&&ui {keyboard()!=null}};mock.verify()}
            Mock(source,output).use {mock->configure(mock);seed();click("确定");waitFor("second preview"){text()==output};click("确定");waitFor("rewrite confirmed"){text()==output&&ui {keyboard()!=null}};mock.verify()}
            report("Production voice panel: model preview stays open, restore-original works, second confirmation retains text without repeat requests")
            Mock(source,output,delayMs=1700).use { mock ->
                configure(mock);seed();click("润色")
                waitFor("dedicated cancel button") { ui { views().filterIsInstance<android.widget.TextView>().any { it.isShown && it.text.toString() == "取消润色" } } }
                click("取消润色")
                waitFor("polish cancelled without losing draft") { text() == source && ui { views().filterIsInstance<VoiceBoard>().any { it.isShown } } }
                click("取消");waitFor("cancelled board closes") { ui { keyboard() != null } }
            }
            ui { p.edit().putBoolean(V3Prefs.DRIVE, true).commit() }
            seed()
            ui {
                val board = views().filterIsInstance<VoiceBoard>().first { it.isShown }
                board.update(source, "", "正在聆听...", true)
                check(descendants(board).filterIsInstance<android.widget.TextView>().none { it.isShown && it.text.toString().startsWith("正在聆听") })
                update.invoke(live)
            }
            ui { live.onStartInputView(live.currentInputEditorInfo, true) }
            waitFor("same-editor restart preserves unconfirmed panel") { text() == source && ui { views().filterIsInstance<VoiceBoard>().any { it.isShown } } }
            fun visibleAction(label: String) = views().firstOrNull { it.isShown && it.contentDescription == label }
            click("收起")
            waitFor("collapsed icon controls") { ui { visibleAction("已暂停，点按继续") is android.widget.ImageView && visibleAction("展开听写面板") != null } }
            ui { checkNotNull(visibleAction("展开听写面板")).performClick() }
            waitFor("expanded draft preserved") { text() == source && ui { views().filterIsInstance<VoiceBoard>().any { it.isShown } } }
            click("收起")
            waitFor("compact fill icon") { ui { visibleAction("填入已识别的文字") is android.widget.ImageView } }
            ui { checkNotNull(visibleAction("填入已识别的文字")).performClick() }
            waitFor("fill confirms once and disables when empty") { text() == source && ui { visibleAction("填入：暂无可确认的文字")?.isEnabled == false } }
            ui { checkNotNull(visibleAction("停止听写")).performClick() }
            waitFor("keyboard after stop") { ui { keyboard() != null } }
            report("Explicit polish cancellation, editor restart, pause icon, collapse/expand, and one-tap fill passed")
            report("ONLINE PROTOCOL AND REAL IME POLISH PREVIEW PASSED")
        } finally {
            ui {
                val edit=p.edit().clear();for((k,v) in backup)when(v){is Boolean->edit.putBoolean(k,v);is Int->edit.putInt(k,v);is Float->edit.putFloat(k,v);is Long->edit.putLong(k,v);is String->edit.putString(k,v);is Set<*>->@Suppress("UNCHECKED_CAST") edit.putStringSet(k,v as Set<String>)};edit.commit()
                if(secretBackup==null)secretFile.delete() else secretFile.writeBytes(secretBackup)
            }
        }
    }
}
