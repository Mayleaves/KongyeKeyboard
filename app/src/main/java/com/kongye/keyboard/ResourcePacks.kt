package com.kongye.keyboard

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.annotation.Keep
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.CancellationException
import java.util.concurrent.Executors

/** Data files are optional downloads. Catalog hashes pin bytes, not mutable URLs.
 * A .part file is never used by an engine. All installation and hashing is off UI. */
@Keep
object ResourcePacks {
    data class Part(val name:String,val url:String,val path:String,val bytes:Long,val sha256:String)
    data class Pack(val id:String,val title:String,val summary:String,val files:List<Part>) {
        val bytes:Long get()=files.sumOf {it.bytes}
    }
    data class State(val busy:Boolean=false,val downloaded:Long=0,val total:Long=0,val message:String="",val removing:Boolean=false)
    private val main=Handler(Looper.getMainLooper())
    private val worker=Executors.newFixedThreadPool(2) {Thread(it,"Kongye resource download")}
    private val states=java.util.concurrent.ConcurrentHashMap<String,State>()
    private val jobs=HashMap<String,ResourceTransfer.Control>()
    @Volatile private var catalog:List<Pack>?=null
    fun catalog(ctx:Context):List<Pack> = catalog ?: synchronized(this) {
        catalog ?: JSONObject(ctx.assets.open("resource-packs.json").bufferedReader().use {it.readText()}).getJSONArray("packs").let {a->
            (0 until a.length()).map {n->val p=a.getJSONObject(n);val f=p.getJSONArray("files")
                Pack(p.getString("id"),p.getString("title"),p.getString("summary"),(0 until f.length()).map {j->val v=f.getJSONObject(j)
                    Part(v.getString("name"),v.getString("url"),v.getString("path"),v.getLong("bytes"),v.getString("sha256"))})
            }.also {catalog=it}
        }
    }
    fun pack(ctx:Context,id:String)=catalog(ctx).first {it.id==id}
    fun file(ctx:Context,part:Part):File {
        val (kind,relative)=part.path.split(':',limit=2)
        val root=when(kind) {"files"->ctx.filesDir;"private"->ctx.noBackupFilesDir;"voice"->checkNotNull(ctx.getExternalFilesDir("offline-voice"));else->error("Unknown resource location")}
        val target=File(root,relative).canonicalFile
        check(target.path.startsWith(root.canonicalPath+File.separator)) {"Invalid resource location"}
        return target
    }
    fun file(ctx:Context,id:String,name:String?=null):File {
        val p=pack(ctx,id);return file(ctx,if(name==null)p.files.first() else p.files.first {it.name==name})
    }
    private fun verified(file:File)=File(file.parentFile,file.name+".sha256")
    private fun legacy(ctx:Context,p:Pack):Boolean=when(p.id) {
        "grammar"->File(file(ctx,p.files.first()).parentFile,".complete").isFile
        "polish"->runCatching {File(file(ctx,p.files.first()).parentFile,"verified").readText()==p.files.first().sha256}.getOrDefault(false)
        "mandarin","sichuan"->runCatching {File(file(ctx,p.files.first()).parentFile,".verified").readText()==p.files.joinToString("\n") {it.sha256}}.getOrDefault(false)
        else->false
    }
    fun ready(ctx:Context,id:String):Boolean {
        val p=pack(ctx,id)
        if(id=="grammar" && file(ctx,p.files.first()).length()==418071596L && legacy(ctx,p))return true
        return p.files.all {part->val f=file(ctx,part);f.length()==part.bytes && (runCatching {verified(f).readText()==part.sha256}.getOrDefault(false)||legacy(ctx,p))}
    }
    fun state(id:String)=states[id] ?: State()
    fun remaining(ctx:Context,id:String)=pack(ctx,id).files.sumOf {part->
        val f=file(ctx,part);File(f.parentFile,f.name+".part").length().coerceAtMost(part.bytes)
    }
    fun cancel(id:String) {synchronized(jobs){jobs[id]?.cancel()}}
    fun start(ctx:Context,id:String,done:((Boolean,String)->Unit)?=null) {
        val app=ctx.applicationContext;val p=pack(app,id)
        val control=synchronized(jobs) {
            if(jobs.containsKey(id))return
            ResourceTransfer.Control().also {jobs[id]=it}
        }
        states[id]=State(true,0,p.bytes,"正在连接…")
        worker.execute {
            val result=runCatching {
                var completed=0L
                for(part in p.files) {
                    control.check()
                    ResourceTransfer.fetch(part,file(app,part),control) {n->
                        states[id]=State(true,completed+n,p.bytes,"下载中")
                    }
                    completed+=part.bytes
                }
                if(id=="polish")File(file(app,p.files.first()).parentFile,"verified").writeText(p.files.first().sha256)
                if(id in listOf("mandarin","sichuan"))File(file(app,p.files.first()).parentFile,".verified").writeText(p.files.joinToString("\n"){it.sha256})
                control.check()
                // Rime maps its grammar when sessions open. Recreate them only
                // after the complete model was verified and atomically installed.
                if(id=="grammar")PinyinEngine.reloadModel(app)
            }
            val message=when {
                result.isSuccess->"已下载，可离线使用"
                control.cancelled->"已暂停，点继续下载"
                result.exceptionOrNull() is java.net.UnknownHostException->"无法连接，请检查网络后重试"
                result.exceptionOrNull() is java.net.SocketTimeoutException->"连接超时，可继续下载"
                else->result.exceptionOrNull()?.message ?: "下载失败，请重试"
            }
            synchronized(jobs) {
                states[id]=State(false,if(result.isSuccess)p.bytes else states[id]?.downloaded ?: 0,p.bytes,message)
                jobs.remove(id)
            }
            main.post {if(result.isSuccess && id=="handwriting")OpenHandwriting.release();done?.invoke(result.isSuccess,message)}
        }
    }
    fun installedBytes(ctx:Context,id:String):Long=pack(ctx,id).files.sumOf {part->
        val f=file(ctx,part);f.length()+File(f.parentFile,f.name+".part").length()
    }
    /** Only catalogued optional files are removed; base dictionaries and learned words stay intact. */
    internal fun eraseFiles(ctx:Context,id:String) {
        for(part in pack(ctx,id).files) {
            val f=file(ctx,part)
            for(target in listOf(f,verified(f),File(f.parentFile,f.name+".part")))check(!target.exists()||target.delete()) {"无法删除 "+part.name+"，请稍后重试"}
        }
    }
    fun remove(ctx:Context,id:String,done:(Boolean,String)->Unit) {
        val app=ctx.applicationContext;val p=pack(app,id)
        synchronized(jobs) {
            if(jobs.containsKey(id)){main.post {done(false,"请先暂停下载，等下载停止后再删除")};return}
            jobs[id]=ResourceTransfer.Control()
        }
        states[id]=State(true,0,p.bytes,"正在删除…",true)
        worker.execute {
            val result=runCatching {
                val erase={eraseFiles(app,id)}
                when(id) {
                    "grammar"->synchronized(PinyinEngine){erase();PinyinEngine.reloadModel(app)}
                    "streaming","punctuation","mandarin","sichuan"->OfflineVoice.withModelFilesReleased(erase)
                    "handwriting"->{OpenHandwriting.release();erase()}
                    else->erase()
                }
            }
            val message=if(result.isSuccess)"已删除，需要时可重新下载" else result.exceptionOrNull()?.message ?: "删除失败，请重试"
            synchronized(jobs){jobs.remove(id);states[id]=State(false,0,p.bytes,message)}
            main.post {done(result.isSuccess,message)}
        }
    }

}

@Keep
object ResourceTransfer {
    class Control {
        @Volatile var cancelled=false
        @Volatile var connection:HttpURLConnection?=null
        fun cancel(){cancelled=true;connection?.disconnect()}
        fun check(){if(cancelled)throw CancellationException("下载已暂停")}
    }
    private fun connect(address:String,offset:Long,c:Control):HttpURLConnection {
        var url=URL(address)
        repeat(6) {
            c.check()
            check(url.protocol=="https" || url.protocol=="http" && url.host in listOf("127.0.0.1","localhost","::1")) {"下载地址必须使用 HTTPS"}
            val conn=url.openConnection() as HttpURLConnection;c.connection=conn
            conn.connectTimeout=20000;conn.readTimeout=20000;conn.instanceFollowRedirects=false
            conn.setRequestProperty("User-Agent","KongyeKeyboard/3")
            conn.setRequestProperty("Accept-Encoding","identity")
            conn.setRequestProperty("Accept","application/octet-stream")
            if(offset>0)conn.setRequestProperty("Range","bytes=$offset-")
            val code=conn.responseCode
            if(code in listOf(301,302,303,307,308)) {
                val next=conn.getHeaderField("Location");conn.disconnect()
                check(!next.isNullOrEmpty()) {"下载链接无效"};url=URL(url,next)
            } else return conn
        }
        error("下载链接重定向过多")
    }
    private fun digest(file:File,c:Control):String {
        val hash=MessageDigest.getInstance("SHA-256");val buffer=ByteArray(256*1024)
        file.inputStream().buffered().use {input->while(true){c.check();val n=input.read(buffer);if(n<0)break;hash.update(buffer,0,n)}}
        return hash.digest().joinToString(""){"%02x".format(it.toInt() and 255)}
    }
    fun fetch(part:ResourcePacks.Part,dest:File,c:Control,progress:(Long)->Unit) {
        check(dest.parentFile!!.mkdirs()||dest.parentFile!!.isDirectory) {"无法准备存储目录"}
        val marker=File(dest.parentFile,dest.name+".sha256")
        if(dest.length()==part.bytes && digest(dest,c)==part.sha256) {marker.writeText(part.sha256);progress(part.bytes);return}
        val temp=File(dest.parentFile,dest.name+".part")
        if(temp.length()>part.bytes)temp.delete()
        var offset=temp.length()
        if(offset==part.bytes) {
            if(digest(temp,c)!=part.sha256){temp.delete();offset=0}
        }
        if(offset<part.bytes) {
            check(dest.parentFile!!.usableSpace>part.bytes-offset+16*1024*1024) {"存储空间不足，请清理后重试"}
            val connection=connect(part.url,offset,c)
            try {
                val code=connection.responseCode
                check(code==200||code==206) {"下载失败（HTTP $code），请重试"}
                if(code==200)offset=0
                else check(connection.getHeaderField("Content-Range")?.startsWith("bytes $offset-")==true) {"下载续传响应无效，请重试"}
                var count=offset;progress(count)
                connection.inputStream.buffered().use {input->java.io.FileOutputStream(temp,offset>0).buffered().use {out->
                    val buffer=ByteArray(128*1024)
                    while(true){c.check();val n=input.read(buffer);if(n<0)break
                        count+=n;check(count<=part.bytes) {"下载内容大小异常"};out.write(buffer,0,n);progress(count)
                    }
                }}
            } finally {connection.disconnect();c.connection=null}
        }
        c.check()
        check(temp.length()==part.bytes) {"下载未完成，可继续下载"}
        if(digest(temp,c)!=part.sha256) {temp.delete();error("文件校验失败，请重新下载")}
        java.nio.file.Files.move(temp.toPath(),dest.toPath(),java.nio.file.StandardCopyOption.REPLACE_EXISTING,java.nio.file.StandardCopyOption.ATOMIC_MOVE)
        marker.writeText(part.sha256);progress(part.bytes)
    }
}
