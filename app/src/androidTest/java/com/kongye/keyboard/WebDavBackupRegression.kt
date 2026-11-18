package com.kongye.keyboard

import android.app.Instrumentation
import android.content.Context
import android.content.ContextWrapper
import android.os.Bundle
import java.io.File
import java.net.ServerSocket
import java.util.concurrent.atomic.AtomicBoolean

object WebDavBackupRegression {
    fun run(i:Instrumentation) {
        val root=File(i.targetContext.cacheDir,"webdav-regression").apply {deleteRecursively();mkdirs()}
        val ctx=object:ContextWrapper(i.targetContext) {
            override fun getApplicationContext():Context=this
            override fun getFilesDir()=File(root,"files").apply {mkdirs()}
            override fun getCacheDir()=File(root,"cache").apply {mkdirs()}
            override fun getNoBackupFilesDir()=File(root,"private").apply {mkdirs()}
            override fun getSharedPreferences(name:String,mode:Int)=super.getSharedPreferences("webdav-regression-"+name,mode)
        }
        val server=ServerSocket(0,8,java.net.InetAddress.getByName("127.0.0.1"))
        val alive=AtomicBoolean(true);val files=java.util.concurrent.ConcurrentHashMap<String,ByteArray>()
        val requests=java.util.concurrent.CopyOnWriteArrayList<String>()
        val worker=Thread {
            while(alive.get())try {server.accept().use {socket->
                socket.soTimeout=10000;val input=socket.getInputStream().buffered()
                fun line():String {val out=StringBuilder();while(true){val c=input.read();if(c<0||c==10)break;if(c!=13)out.append(c.toChar());check(out.length<10000)};return out.toString()}
                val first=line();val parts=first.split(' ');if(parts.size<2)return@use
                val method=parts[0];val path=parts[1];val headers=mutableMapOf<String,String>()
                while(true){val line=line();if(line.isEmpty())break;headers[line.substringBefore(':').lowercase()]=line.substringAfter(':').trim()}
                val length=headers["content-length"]?.toIntOrNull() ?: 0;check(length<2*1024*1024)
                val body=ByteArray(length);var n=0;while(n<length){val count=input.read(body,n,length-n);check(count>0);n+=count}
                requests+="$method $path"
                var status=200;var output=ByteArray(0);var extras=""
                when {
                    headers["authorization"]!="Basic "+android.util.Base64.encodeToString("sample:private-password".toByteArray(),android.util.Base64.NO_WRAP)->status=401
                    path=="/redirect/"->{status=302;extras="Location: http://127.0.0.1:${server.localPort}/dav/\r\n"}
                    path=="/huge/"->{status=207;extras="X-Test: huge\r\n"}
                    method=="PROPFIND"->{status=207;output=("<d:multistatus xmlns:d=\"DAV:\">"+files.keys.joinToString(""){"<d:response><d:href>$it</d:href><d:propstat><d:prop><d:getcontentlength>${files[it]!!.size}</d:getcontentlength></d:prop></d:propstat></d:response>"}+"<d:response><d:href>https://untrusted.invalid/dav/kongye-bad.zip</d:href></d:response><d:response><d:href>/outside/kongye-bad.zip</d:href></d:response></d:multistatus>").toByteArray()}
                    method=="PUT"->{check(headers["if-none-match"]=="*");files[path]=body;status=201}
                    method=="GET"->{output=files[path] ?: ByteArray(0);if(!files.containsKey(path))status=404}
                    else->status=405
                }
                socket.getOutputStream().apply {write("HTTP/1.1 $status Test\r\nContent-Length: ${if(path=="/huge/")3000000 else output.size}\r\n${extras}Connection: close\r\n\r\n".toByteArray());write(output);flush()}
            }} catch(_:Exception){if(!alive.get())break}
        }.apply {start()}
        val p=Prefs.of(ctx);val config=WebDavBackup.Config("http://127.0.0.1:${server.localPort}/dav","sample","private-password")
        fun fails(block:()->Unit)=try {block();false}catch(_:Exception){true}
        fun report(text:String)=i.sendStatus(0,Bundle().apply {putString("stream",text+"\n")})
        try {
            p.edit().clear().putInt(V3Prefs.SOUND_VOLUME,47).commit()
            WebDavBackup.save(ctx,config);check(WebDavBackup.read(ctx)==config)
            check(!File(ctx.noBackupFilesDir,"webdav-credentials").readText().contains("private-password"))
            check(WebDavBackup.list(config).isEmpty());check(requests.size==1&&files.isEmpty())
            report("Encrypted configuration roundtrip; connection test only lists the directory")
            val name=WebDavBackup.upload(ctx,config,KeyboardBackup.Options(false,false,false),false)
            val entries=WebDavBackup.list(config);check(entries.size==1&&entries[0].name==name&&entries[0].bytes>0)
            p.edit().putInt(V3Prefs.SOUND_VOLUME,3).commit()
            val prepared=WebDavBackup.download(ctx,config,entries[0]);check(p.getInt(V3Prefs.SOUND_VOLUME,0)==3)
            KeyboardBackup.restore(ctx,prepared,false);check(p.getInt(V3Prefs.SOUND_VOLUME,0)==47)
            check(WebDavBackup.read(ctx)==config)
            val second=WebDavBackup.upload(ctx,config,KeyboardBackup.Options(false,false,false),false);check(second!=name&&files.size==2)
            report("PUT, PROPFIND and GET preserve older backups; download validates without restoring; confirmed restore roundtrips settings")
            check(fails {WebDavBackup.list(config.copy(password="bad"))})
            check(fails {WebDavBackup.list(config.copy(address=config.address.replace("/dav","/redirect")))})
            check(requests.count {it=="PROPFIND /dav/"}==3)
            check(fails {WebDavBackup.list(config.copy(address=config.address.replace("/dav","/huge")))})
            check(fails {WebDavBackup.endpoint(config.copy(address="http://example.com/dav/"))})
            check(fails {WebDavBackup.download(ctx,config,WebDavBackup.Entry("kongye-bad.zip","https://example.com/kongye-bad.zip",1))})
            files["/dav/$name"]="corrupt".toByteArray();check(fails {WebDavBackup.download(ctx,config,entries[0]).close()})
            check(p.getInt(V3Prefs.SOUND_VOLUME,0)==47)
            check(ctx.cacheDir.listFiles().orEmpty().none {it.name.startsWith("webdav-")})
            WebDavBackup.clear(ctx);check(WebDavBackup.read(ctx)==WebDavBackup.Config())
            report("Bad credentials, redirects, external entries, oversized lists and corrupt backups rejected; temporary transfer files cleaned")
        } finally {alive.set(false);server.close();worker.join(1000);p.edit().clear().commit();root.deleteRecursively()}
        report("WEBDAV BACKUP PASSED")
    }
}
