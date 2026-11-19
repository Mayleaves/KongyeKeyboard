package com.kongye.keyboard

import android.app.Instrumentation
import android.app.job.JobInfo
import android.content.Context
import android.content.ContextWrapper
import android.os.Bundle
import java.io.ByteArrayInputStream
import java.io.File
import java.net.ServerSocket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Loopback-only transfers and private fixtures; never changes personal settings or schedules uploads. */
object BackupFrequencyRegression {
    fun run(i:Instrumentation) {
        val root=File(i.targetContext.cacheDir,"backup-frequency-fixture").apply {deleteRecursively();mkdirs()}
        val ctx=object:ContextWrapper(i.targetContext) {
            override fun getApplicationContext():Context=this
            override fun getFilesDir()=File(root,"files").apply {mkdirs()}
            override fun getCacheDir()=File(root,"cache").apply {mkdirs()}
            override fun getNoBackupFilesDir()=File(root,"private").apply {mkdirs()}
            override fun getSharedPreferences(name:String,mode:Int)=super.getSharedPreferences("frequency-fixture-"+name,mode)
        }
        val p=Prefs.of(ctx)
        val server=ServerSocket(0,8,java.net.InetAddress.getByName("127.0.0.1"))
        val alive=AtomicBoolean(true)
        val directories=java.util.concurrent.ConcurrentHashMap.newKeySet<String>().apply {add("/dav/")}
        val files=java.util.concurrent.ConcurrentHashMap<String,ByteArray>()
        val requests=java.util.concurrent.CopyOnWriteArrayList<String>()
        val waiting=CountDownLatch(1)
        val worker=Thread {
            while(alive.get())try {server.accept().use {socket->
                val input=socket.getInputStream().buffered()
                fun line():String {val bytes=java.io.ByteArrayOutputStream();while(true){val c=input.read();check(c>=0);if(c==10)break;if(c!=13)bytes.write(c)};return bytes.toString("UTF-8")}
                val parts=line().split(' ');val method=parts[0];val path=parts[1]
                val headers=mutableMapOf<String,String>()
                while(true){val entry=line();if(entry.isEmpty())break;headers[entry.substringBefore(':').lowercase()]=entry.substringAfter(':').trim()}
                val length=headers["content-length"]?.toIntOrNull() ?: 0;check(length<2*1024*1024)
                val body=ByteArray(length);var n=0;while(n<length){val count=input.read(body,n,length-n);check(count>0);n+=count}
                requests.add("$method $path")
                var status=200;var output=ByteArray(0)
                when {
                    headers["authorization"]!="Basic "+android.util.Base64.encodeToString("sample:fixture-secret".toByteArray(),android.util.Base64.NO_WRAP)->status=401
                    path=="/slow/"->{waiting.countDown();Thread.sleep(5000);status=500}
                    method=="MKCOL"->{status=if(directories.add(path))201 else 405}
                    method=="PROPFIND" && directories.contains(path)->{
                        status=207
                        output=("<d:multistatus xmlns:d=\"DAV:\"><d:response><d:href>$path</d:href><d:propstat><d:prop><d:resourcetype><d:collection/></d:resourcetype></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response>"+
                            files.entries.filter {it.key.substringBeforeLast('/')+"/"==path}.joinToString(""){(name,data)->"<d:response><d:href>$name</d:href><d:propstat><d:prop><d:getcontentlength>${data.size}</d:getcontentlength></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response>"}+"</d:multistatus>").toByteArray()
                    }
                    method=="PUT"->{check(headers["if-none-match"]=="*");files[path]=body;status=201}
                    method=="GET"->{output=files[path] ?: ByteArray(0);if(!files.containsKey(path))status=404}
                    else->status=404
                }
                socket.getOutputStream().apply {write("HTTP/1.1 $status Test\r\nContent-Length: ${output.size}\r\nConnection: close\r\n\r\n".toByteArray());write(output);flush()}
            }} catch(_:Exception){if(!alive.get())break}
        }.apply {start()}
        fun report(message:String)=i.sendStatus(0,Bundle().apply {putString("stream",message+"\n")})
        try {
            p.edit().clear().commit()
            check(AutoWebDavBackup.frequency(ctx)==0)
            check(AutoWebDavBackup.runOnce(ctx,WebDavBackup.Transfer(),false)==null && requests.isEmpty())
            for(days in listOf(1,7,30)) {
                val info=AutoWebDavBackup.job(ctx,days)
                check(info.intervalMillis==TimeUnit.DAYS.toMillis(days.toLong()))
                check(info.isPersisted && info.isRequireDeviceIdle && info.isRequireBatteryNotLow && info.networkType==JobInfo.NETWORK_TYPE_ANY)
            }
            report("PASS: default manual makes no network request; daily/weekly/30-day jobs persist and require idle plus network")
            val provider=WebDavBackup.Config("https://dav.jianguoyun.com/dav/","sample","fixture-secret")
            check(WebDavBackup.backupEndpoint(provider).encodedPath=="/dav/KongyeKeyboard/")
            check(WebDavBackup.userConfig(provider.copy(address="https://dav.jianguoyun.com/dav/KongyeKeyboard/")).address==provider.address)
            val config=WebDavBackup.Config("http://127.0.0.1:${server.localPort}/dav/","sample","fixture-secret")
            val child=WebDavBackup.createBackupFolder(ctx,config)
            check(child.address.endsWith("/dav/KongyeKeyboard/") && WebDavBackup.read(ctx)==child)
            check(WebDavBackup.createBackupFolder(ctx,config)==child)
            val options=KeyboardBackup.Options(false,true,false)
            AutoWebDavBackup.setOptions(ctx,options);check(AutoWebDavBackup.options(ctx)==options)
            File(ctx.filesDir,"typing-stats.json").writeText("{\"chars\":42,\"migrated\":true}")
            p.edit().putInt(AutoWebDavBackup.FREQUENCY,7).commit()
            val name=AutoWebDavBackup.runOnce(ctx,WebDavBackup.Transfer(),false) ?: error("Expected upload")
            val bytes=files["/dav/KongyeKeyboard/$name"] ?: error("Missing uploaded archive")
            KeyboardBackup.read(ctx,ByteArrayInputStream(bytes)).use {prepared->
                check(prepared.options==options)
                check(!File(prepared.folder,"settings.json").readText().contains(AutoWebDavBackup.FREQUENCY))
                check(File(prepared.folder,"stats.json").readText().contains("42"))
            }
            check(WebDavBackup.list(child).single().name==name)
            AutoWebDavBackup.record(ctx,Result.success(name));check(AutoWebDavBackup.lastResult(ctx).second==null)
            AutoWebDavBackup.record(ctx,Result.failure(Exception("fixture failure")));check(AutoWebDavBackup.lastResult(ctx).second=="fixture failure")
            report("PASS: folder creation/existing folder, exact selected archive contents, secret/schedule exclusion and latest automatic result")
            p.edit().putInt(AutoWebDavBackup.FREQUENCY,0).commit()
            val before=requests.size;check(AutoWebDavBackup.runOnce(ctx,WebDavBackup.Transfer(),false)==null && before==requests.size)
            val transfer=WebDavBackup.Transfer();val aborted=AtomicBoolean(false)
            val call=Thread {try {WebDavBackup.list(config.copy(address=config.address.replace("/dav/","/slow/")),transfer)}catch(_:java.io.IOException){aborted.set(true)}}.apply {start()}
            check(waiting.await(5,TimeUnit.SECONDS));transfer.cancel();call.join(2500)
            check(!call.isAlive && aborted.get())
            report("PASS: disabling skips upload; stopping a transfer cancels an in-flight request")
        } finally {
            alive.set(false);server.close();worker.interrupt();worker.join(1000)
            p.edit().clear().commit();root.deleteRecursively()
        }
        report("BACKUP FREQUENCY PASSED")
    }
}
