package com.kongye.keyboard

import android.app.Instrumentation
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.nl.translate.TranslateRemoteModel
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.zip.ZipInputStream

object ResourcePolicyRegression {
    fun run(i:Instrumentation) {
        check(android.os.Build.MODEL.startsWith("sdk_"))
        val ctx=i.targetContext
        fun report(s:String)=i.sendStatus(0,Bundle().apply {putString("stream",s+"\n")})
        fun await(latch:CountDownLatch)=check(latch.await(15,TimeUnit.SECONDS)) {"SDK local model query timed out"}
        fun installed():Set<String> {
            val done=CountDownLatch(1);var found=emptySet<String>();var failure:Exception?=null
            RemoteModelManager.getInstance().getDownloadedModels(TranslateRemoteModel::class.java)
                .addOnSuccessListener {found=it.map {it.language}.toSet()}.addOnFailureListener {failure=it}.addOnCompleteListener {done.countDown()}
            await(done);check(failure==null) {failure.toString()};return found
        }
        check(ResourcePacks.bundled(ctx,"handwriting"))
        check(ctx.assets.open("handwriting-neural/ochwpro.onnx").use {it.readBytes().size}==7001270)
        for(path in listOf("voice-streaming/model.int8.onnx","voice-punctuation/model.int8.onnx","voice-polish/Qwen3-0.6B-Q8_0.gguf"))
            check(runCatching {ctx.assets.open(path).close()}.isFailure) {"Optional model was packaged: $path"}
        ZipInputStream(ctx.assets.open("rime-data.zip")).use {zip->
            while(true) {val entry=zip.nextEntry ?: break;check(!entry.name.endsWith(".gram"));zip.closeEntry()}
        }
        report("PACKAGE POLICY PASSED: handwriting available in APK; optional speech, polish and large sentence model excluded")
        val p=Prefs.of(ctx);val old=p.getInt(V3Prefs.GLOSS,2);val before=installed()
        check("zh" !in before) {"Use a clean emulator for the missing-resource case"}
        try {
            val query=CountDownLatch(1)
            i.runOnMainSync {
                p.edit().putInt(V3Prefs.GLOSS,1).commit()
                CandidateGloss.reset();CandidateGloss.prepare(ctx)
                HandInk.release();HandInk.prepare {query.countDown()}
            }
            await(query);SystemClock.sleep(300)
            check(!CandidateGloss.available(true))
            check(HandInk.state==3 && "离线资源" in HandInk.status)
            val translated=CountDownLatch(1);var message=""
            i.runOnMainSync {Translator.run(ctx,"你好",target="en") {ok,_,status->
                check(!ok);message=status;if("离线资源" in status)translated.countDown()
            }}
            await(translated)
            check("中文" in message)
            check(installed()==before) {"A passive feature downloaded a model"}
            check(LanguageResources.packs().all {!LanguageResources.state(it).busy})
            report("PASSIVE FEATURE POLICY PASSED: candidate translation, missing-language translation and backup ink did not start downloads; missing models direct users to offline resources")
        } finally {i.runOnMainSync {Translator.cancel();CandidateGloss.reset();p.edit().putInt(V3Prefs.GLOSS,old).commit()}}

        val activity=i.startActivitySync(Intent(ctx,SettingsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        val auto=i.getUiAutomation(android.app.UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        fun nodes():List<AccessibilityNodeInfo> {
            val out=ArrayList<AccessibilityNodeInfo>()
            fun walk(n:AccessibilityNodeInfo?) {if(n==null)return;out.add(n);repeat(n.childCount){walk(n.getChild(it))}}
            walk(auto.rootInActiveWindow);return out
        }
        var opened=false
        for(step in 0..35) {
            SystemClock.sleep(200)
            val entry=nodes().firstOrNull {it.isVisibleToUser && it.text?.toString()=="离线资源"}
            if(entry!=null) {
                var target:AccessibilityNodeInfo?=entry
                repeat(5) {if(!opened && target?.performAction(AccessibilityNodeInfo.ACTION_CLICK)==true)opened=true;target=target?.parent}
                if(opened)break
            }
            if(step>10)nodes().firstOrNull {it.isScrollable && it.isVisibleToUser}?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
        }
        check(opened) {"Resource entry not reachable from home"}
        val wanted=setOf("内置资源","可选增强资源","翻译与备用识别","中文翻译语言包","日文翻译语言包","韩文翻译语言包","备用手写识别")
        val seen=HashSet<String>()
        try {
            repeat(30) {
                SystemClock.sleep(200)
                nodes().filter {it.isVisibleToUser}.mapNotNull {it.text?.toString()}.forEach {seen.add(it)}
                if(seen.containsAll(wanted))return@repeat
                nodes().firstOrNull {it.isScrollable && it.isVisibleToUser}?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
            }
            check(seen.containsAll(wanted)) {"Missing resource UI entries: ${wanted-seen}"}
            val bitmap=auto.takeScreenshot()
            File(ctx.getExternalFilesDir(null),"resources307.png").outputStream().use {bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)}
            bitmap.recycle()
            report("RESOURCE MANAGEMENT UI PASSED: built-in / optional / translation and backup groups, all language and backup entries reachable")
        } finally {i.runOnMainSync {activity.finish()}}
    }
}
