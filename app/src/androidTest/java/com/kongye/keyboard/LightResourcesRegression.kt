package com.kongye.keyboard

import android.app.Instrumentation
import android.os.Bundle
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.ServerSocket
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Production downloader against an isolated HTTP fixture: restart, range,
 * interrupted transfer, cancellation and bad hashes never expose partial data. */
object LightResourcesRegression {
    fun pack(i:Instrumentation,id:String) {
        val ctx=i.targetContext
        if(!ResourcePacks.ready(ctx,id)) {
            val latch=CountDownLatch(1);var okay=false;var message=""
            ResourcePacks.start(ctx,id) {ok,t->okay=ok;message=t;latch.countDown()}
            check(latch.await(600,TimeUnit.SECONDS)) {"Download timed out: $id"};check(okay) {message}
        }
        check(ResourcePacks.ready(ctx,id))
        if(id=="punctuation") {
            val result=OfflineVoice.punctuateSample(ctx,"今天我们讨论一下这个安排明天再确认具体时间")
            check(result.any {it in "，。！？"}) {result}
            i.sendStatus(0,Bundle().apply {putString("stream","Downloaded CT Transformer result: $result\n")})
            OfflineVoice.releaseForPolish()
        }
        i.sendStatus(0,Bundle().apply {putString("stream","PINNED MODEL DOWNLOAD / VALIDATION PASSED: $id\n")})
    }
    fun grammar(i:Instrumentation) {
        val ctx=i.targetContext
        Lexicon.load(ctx);PinyinEngine.initialize(ctx);check(PinyinEngine.ready)
        val latch=CountDownLatch(1);var okay=false;var message=""
        if(!ResourcePacks.ready(ctx,"grammar")) {
            ResourcePacks.start(ctx,"grammar") {ok,t->okay=ok;message=t;latch.countDown()}
            check(latch.await(600,TimeUnit.SECONDS)) {"Grammar download timed out"};check(okay) {message}
        }
        check(ResourcePacks.ready(ctx,"grammar"))
        val result=PinyinEngine.candidates(listOf("mno","ghi","ghi","abc","mno"),true)
        check(result.any {it.w=="你好"}) {result.take(5).joinToString {it.w}}
        i.sendStatus(0,Bundle().apply {putString("stream","PINNED GRAMMAR DOWNLOAD / HOT RELOAD / NINE-KEY DECODING PASSED\n")})
    }
    fun run(i:Instrumentation,download:Boolean) {
        val ctx=i.targetContext
        fun report(t:String)=i.sendStatus(0,Bundle().apply {putString("stream",t+"\n")})
        val names=listOf("voice-polish/Qwen3-0.6B-Q8_0.gguf","voice-streaming/model.int8.onnx","voice-punctuation/model.int8.onnx","handwriting-neural/ochwpro.onnx")
        for(name in names)check(runCatching {ctx.assets.open(name).use {it.read()}}.isFailure) {"Model still bundled: $name"}
        var baseEntries=0
        java.util.zip.ZipInputStream(ctx.assets.open("rime-data.zip")).use {z->while(true){val entry=z.nextEntry ?: break;check(!entry.name.endsWith(".gram"));baseEntries++}}
        check(baseEntries>10);check(ResourcePacks.catalog(ctx).size==7)
        Lexicon.load(ctx);PinyinEngine.initialize(ctx)
        check(PinyinEngine.ready) {PinyinEngine.failure.orEmpty()}
        check(FcitxEngine.ready) {FcitxEngine.failure.orEmpty()}
        check(PinyinEngine.candidates("nihao".map {it.toString()},true).any {it.w=="你好"})
        report("Light package excludes large weights; base Fcitx/Rime dictionaries and real Chinese decoding passed")
        val dir=File(ctx.cacheDir,"light-resource-regression").apply {mkdirs()}
        val data=ByteArray(1024*1024) {(it*31%251).toByte()}
        val sha=MessageDigest.getInstance("SHA-256").digest(data).joinToString(""){"%02x".format(it.toInt() and 255)}
        val server=ServerSocket(0,4,java.net.InetAddress.getByName("127.0.0.1"))
        var ranged=false
        val worker=Thread {
            while(!server.isClosed)runCatching {
                server.accept().use {s->
                    val reader=s.getInputStream().bufferedReader();val first=reader.readLine().orEmpty();var offset=0;var line=reader.readLine()
                    while(!line.isNullOrEmpty()){if(line.startsWith("Range:",true)){offset=line.substringAfter("bytes=").substringBefore('-').toInt();ranged=true};line=reader.readLine()}
                    val ignore=first.contains("/ignore");if(ignore)offset=0
                    val out=s.getOutputStream();val code=if(offset>0)206 else 200
                    out.write(("HTTP/1.1 $code OK\r\nContent-Length: ${data.size-offset}\r\n"+(if(code==206)"Content-Range: bytes $offset-${data.lastIndex}/${data.size}\r\n" else "")+"Connection: close\r\n\r\n").toByteArray())
                    var n=offset;while(n<data.size){val end=minOf(data.size,n+8192);out.write(data,n,end-n);out.flush();n=end;Thread.sleep(2)}
                }
            }
        }.apply {isDaemon=true;start()}
        try {
            val dest=File(dir,"test-model.bin")
            val part=ResourcePacks.Part(dest.name,"http://127.0.0.1:${server.localPort}/data","private:unused",data.size.toLong(),sha)
            val control=ResourceTransfer.Control()
            check(runCatching {ResourceTransfer.fetch(part,dest,control){if(it>32000)control.cancel()}}.isFailure)
            check(!dest.exists());check(File(dir,"test-model.bin.part").length() in 1 until data.size.toLong())
            ResourceTransfer.fetch(part,dest,ResourceTransfer.Control()){}
            check(ranged && dest.readBytes().contentEquals(data));check(!File(dir,"test-model.bin.part").exists())
            report("Cancel preserves partial download; byte-range resume, SHA-256 and atomic install passed")
            val bad=File(dir,"bad.bin")
            check(runCatching {ResourceTransfer.fetch(part.copy(sha256="0".repeat(64)),bad,ResourceTransfer.Control()) {}}.isFailure)
            check(!bad.exists()&&!File(dir,"bad.bin.part").exists())
            val restart=File(dir,"restart.bin");File(dir,"restart.bin.part").writeBytes(data.copyOf(8192))
            ResourceTransfer.fetch(part.copy(url="http://127.0.0.1:${server.localPort}/ignore"),restart,ResourceTransfer.Control()){}
            check(restart.readBytes().contentEquals(data))
            check(runCatching {ResourceTransfer.fetch(part.copy(url="http://example.invalid/insecure"),File(dir,"insecure.bin"),ResourceTransfer.Control()) {}}.isFailure)
            report("Bad hashes rejected without installing; non-range restart and HTTPS policy passed")

            val release=JSONObject().put("assets",JSONArray().put(JSONObject()
                .put("name",part.name).put("size",data.size).put("digest","sha256:$sha").put("browser_download_url",part.url)))
            val resolved=ResourceTransfer.grammarAsset(part.copy(bytes=1,sha256="0".repeat(64)),release)
            check(resolved==part)
            check(runCatching {ResourceTransfer.grammarAsset(part.copy(url="https://example.invalid"),release)}.isFailure)
            val grammar=File(dir,"grammar");val partial=File(dir,"grammar.part");val partialMarker=File(dir,"grammar.part.sha256")
            partial.writeBytes(ByteArray(8192));partialMarker.writeText("${part.bytes}:${"0".repeat(64)}")
            ranged=false
            ResourceTransfer.fetch(resolved,grammar,ResourceTransfer.Control(),releaseAsset=true){}
            check(!ranged && grammar.readBytes().contentEquals(data) && !partialMarker.exists())
            check(File(dir,"grammar.sha256").readText()=="${part.bytes}:$sha")
            report("Rolling grammar release validates its metadata and restarts a partial download from a different release")

            val imported = File(dir, "offline.gram")
            check(runCatching {
                ResourceTransfer.importGrammar(ByteArray(data.size).inputStream(), imported, listOf(part), ResourceTransfer.Control()) {}
            }.isFailure)
            check(!imported.exists())
            val importedBytes = ResourceTransfer.importGrammar(data.inputStream(), imported, listOf(part), ResourceTransfer.Control()) {}
            check(importedBytes == part.bytes && imported.readBytes().contentEquals(data))
            check(File(dir, "offline.gram.sha256").readText() == "${part.bytes}:$sha")
            report("Offline grammar import rejects incorrect hashes and installs verified files")
        } finally {server.close();worker.join(1000);dir.deleteRecursively()}
        if(download) {
            val done=CountDownLatch(1);var okay=false;var message=""
            ResourcePacks.start(ctx,"handwriting") {ok,t->okay=ok;message=t;done.countDown()}
            check(done.await(240,TimeUnit.SECONDS)) {"Network model download timed out"}
            check(okay && ResourcePacks.ready(ctx,"handwriting")) {message}
            val warm=CountDownLatch(1)
            i.runOnMainSync {OpenHandwriting.release();OpenHandwriting.prepare(ctx) {warm.countDown()}}
            check(warm.await(45,TimeUnit.SECONDS));check(OpenHandwriting.ready) {OpenHandwriting.failure.orEmpty()}
            check(OpenHandwriting.failure==null) {"Unexpected fallback instead of downloaded neural model"}
            i.runOnMainSync {OpenHandwriting.release()}
            report("Real pinned neural handwriting download, verified storage and production model initialization passed")
        }
        report("LIGHT RESOURCES PASSED")
    }
}
