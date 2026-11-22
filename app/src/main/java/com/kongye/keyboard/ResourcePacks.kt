package com.kongye.keyboard

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.annotation.Keep
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.CancellationException
import java.util.concurrent.Executors

/** Data files are optional downloads. The rolling grammar release provides its own checksum.
 * A .part file is never used by an engine. All installation and hashing is off UI. */
@Keep
object ResourcePacks {
    private const val LEGACY_GRAMMAR_BYTES = 418071596L
    private const val LEGACY_GRAMMAR_SHA256 = "3457b69f5fe0fc4f5d211c878bbfa2e2361b90639952c9845c8e31e9d047cde6"
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
    fun bundled(ctx:Context,id:String)=id=="handwriting" &&
        ctx.assets.list("handwriting-neural")?.contains("ochwpro.onnx")==true

    /** File-backed ONNX input, extracted from the APK without a network request. */
    fun handwritingModel(ctx:Context):File {
        if(ready(ctx,"handwriting"))return file(ctx,"handwriting")
        check(bundled(ctx,"handwriting")) {"笔迹识别模型未安装"}
        val part=pack(ctx,"handwriting").files.first()
        val target=File(ctx.cacheDir,"handwriting/"+part.sha256+".onnx")
        if(target.length()==part.bytes)return target
        target.parentFile?.mkdirs()
        val atomic=android.util.AtomicFile(target)
        var out:java.io.FileOutputStream?=null
        try {
            out=atomic.startWrite()
            val digest=MessageDigest.getInstance("SHA-256")
            java.security.DigestInputStream(ctx.assets.open("handwriting-neural/ochwpro.onnx"),digest).use {it.copyTo(out)}
            check(digest.digest().joinToString(""){"%02x".format(it.toInt() and 255)}==part.sha256) {"内置笔迹模型校验失败"}
            atomic.finishWrite(out)
        } catch(t:Throwable) {atomic.failWrite(out);throw t}
        return target
    }
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
        if(id=="grammar" && file(ctx,p.files.first()).length()==LEGACY_GRAMMAR_BYTES && legacy(ctx,p))return true
        return p.files.all {part->
            val f=file(ctx,part)
            val bytes=f.length()
            val stamp=runCatching {verified(f).readText()}.getOrDefault("")
            if(id=="grammar" && ResourceTransfer.validReleaseSize(bytes) && stamp.startsWith("$bytes:")) {
                ResourceTransfer.validSha256(stamp.substringAfter(':'))
            } else bytes==part.bytes && (stamp==part.sha256 || legacy(ctx,p))
        }
    }
    fun state(id:String)=states[id] ?: State()
    fun remaining(ctx:Context,id:String)=pack(ctx,id).files.sumOf {part->
        val f=file(ctx,part);File(f.parentFile,f.name+".part").length().coerceAtMost(part.bytes)
    }
    fun cancel(id:String) {synchronized(jobs){jobs[id]?.cancel()}}

    fun importGrammar(ctx: Context, uri: Uri) {
        val id = "grammar"
        val app = ctx.applicationContext
        val pinned = pack(app, id).files.single()
        val legacy = pinned.copy(bytes = LEGACY_GRAMMAR_BYTES, sha256 = LEGACY_GRAMMAR_SHA256)
        val control = synchronized(jobs) {
            if (jobs.containsKey(id)) return
            ResourceTransfer.Control().also { jobs[id] = it }
        }
        states[id] = State(true, 0, legacy.bytes, "正在导入…")
        worker.execute {
            val result = runCatching {
                val input = checkNotNull(app.contentResolver.openInputStream(uri)) { "无法打开所选词库" }
                input.use {
                    ResourceTransfer.importGrammar(it, file(app, pinned), listOf(pinned, legacy), control) { bytes ->
                        states[id] = State(true, bytes, legacy.bytes, "正在导入…")
                    }
                }.also { PinyinEngine.reloadModel(app) }
            }
            synchronized(jobs) {
                val bytes = result.getOrNull()
                val message = when {
                    bytes != null -> "已导入，可离线使用"
                    control.cancelled -> "已取消导入"
                    else -> result.exceptionOrNull()?.message ?: "导入失败，请重试"
                }
                states[id] = State(false, bytes ?: 0, bytes ?: legacy.bytes, message)
                jobs.remove(id)
            }
        }
    }

    fun start(ctx:Context,id:String,done:((Boolean,String)->Unit)?=null) {
        val app=ctx.applicationContext;val p=pack(app,id)
        val control=synchronized(jobs) {
            if(jobs.containsKey(id))return
            ResourceTransfer.Control().also {jobs[id]=it}
        }
        states[id]=State(true,0,p.bytes,"正在连接…")
        worker.execute {
            var total=p.bytes
            val result=runCatching {
                val files=if(id=="grammar")listOf(ResourceTransfer.resolveGrammar(p.files.single(),control)) else p.files
                total=files.sumOf {it.bytes}
                var completed=0L
                for(part in files) {
                    control.check()
                    ResourceTransfer.fetch(part,file(app,part),control,releaseAsset=id=="grammar") {n->
                        states[id]=State(true,completed+n,total,"下载中")
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
                states[id]=State(false,if(result.isSuccess)total else states[id]?.downloaded ?: 0,total,message)
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
            val partial=File(f.parentFile,f.name+".part")
            for(target in listOf(f,verified(f),partial,verified(partial)))check(!target.exists()||target.delete()) {"无法删除 "+part.name+"，请稍后重试"}
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
    private const val GRAMMAR_RELEASE="https://api.github.com/repos/amzxyz/rime-wanxiang-data/releases/tags/LTS"
    private val sha256Pattern=Regex("[a-f0-9]{64}")
    internal fun validSha256(value:String)=sha256Pattern.matches(value)
    internal fun validReleaseSize(bytes:Long)=bytes in 1..1_000_000_000L

    class Control {
        @Volatile var cancelled=false
        @Volatile var connection:HttpURLConnection?=null
        fun cancel(){cancelled=true;connection?.disconnect()}
        fun check(){if(cancelled)throw CancellationException("下载已暂停")}
    }
    private fun connect(address:String,offset:Long,c:Control,accept:String="application/octet-stream"):HttpURLConnection {
        var url=URL(address)
        repeat(6) {
            c.check()
            check(url.protocol=="https" || url.protocol=="http" && url.host in listOf("127.0.0.1","localhost","::1")) {"下载地址必须使用 HTTPS"}
            val conn=url.openConnection() as HttpURLConnection;c.connection=conn
            conn.connectTimeout=20000;conn.readTimeout=20000;conn.instanceFollowRedirects=false
            conn.setRequestProperty("User-Agent","KongyeKeyboard/3")
            conn.setRequestProperty("Accept-Encoding","identity")
            conn.setRequestProperty("Accept",accept)
            if(offset>0)conn.setRequestProperty("Range","bytes=$offset-")
            val code=conn.responseCode
            if(code in listOf(301,302,303,307,308)) {
                val next=conn.getHeaderField("Location");conn.disconnect()
                check(!next.isNullOrEmpty()) {"下载链接无效"};url=URL(url,next)
            } else return conn
        }
        error("下载链接重定向过多")
    }
    internal fun grammarAsset(part:ResourcePacks.Part,release:JSONObject):ResourcePacks.Part {
        val assets=release.optJSONArray("assets")
        for(i in 0 until (assets?.length() ?: 0)) {
            val asset=assets?.optJSONObject(i) ?: continue
            if(asset.optString("name")!=part.name)continue
            val url=asset.optString("browser_download_url")
            val bytes=asset.optLong("size")
            val sha=asset.optString("digest").removePrefix("sha256:").lowercase()
            check(url==part.url && validReleaseSize(bytes) && validSha256(sha)) {"整句增强词库的下载校验信息无效，请稍后重试"}
            return part.copy(bytes=bytes,sha256=sha)
        }
        error("上游暂未提供整句增强词库，请稍后重试")
    }
    internal fun resolveGrammar(part:ResourcePacks.Part,c:Control):ResourcePacks.Part {
        val conn=connect(GRAMMAR_RELEASE,0,c,"application/vnd.github+json")
        try {
            val code=conn.responseCode
            check(code==200) {"无法获取整句增强词库的下载信息（HTTP $code），请稍后重试"}
            val body=conn.inputStream.bufferedReader().use {it.readText()}
            return grammarAsset(part,JSONObject(body))
        } finally {conn.disconnect();c.connection=null}
    }
    private fun digest(file:File,c:Control):String {
        val hash=MessageDigest.getInstance("SHA-256");val buffer=ByteArray(256*1024)
        file.inputStream().buffered().use {input->while(true){c.check();val n=input.read(buffer);if(n<0)break;hash.update(buffer,0,n)}}
        return hash.digest().joinToString(""){"%02x".format(it.toInt() and 255)}
    }

    internal fun importGrammar(input: InputStream, dest: File, approved: List<ResourcePacks.Part>,
        control: Control, progress: (Long) -> Unit): Long {
        val folder = checkNotNull(dest.parentFile)
        check(folder.mkdirs() || folder.isDirectory) { "无法准备存储目录" }
        val temp = File.createTempFile("grammar-", ".import", folder)
        val maximum = approved.maxOf { it.bytes }
        val hash = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(256 * 1024)
        var bytes = 0L
        try {
            temp.outputStream().buffered().use { output ->
                while (true) {
                    control.check()
                    val count = input.read(buffer)
                    if (count < 0) break
                    bytes += count
                    check(bytes <= maximum) { "词库版本不受支持，请选择配套的 .gram 文件" }
                    hash.update(buffer, 0, count)
                    output.write(buffer, 0, count)
                    progress(bytes)
                }
            }
            control.check()
            val sha = hash.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
            check(approved.any { it.bytes == bytes && it.sha256 == sha }) { "词库校验失败，请选择配套的 .gram 文件" }
            install(temp, dest, "$bytes:$sha")
            val partial = File(folder, dest.name + ".part")
            partial.delete()
            File(folder, partial.name + ".sha256").delete()
            return bytes
        } finally {
            temp.delete()
        }
    }

    private fun install(temp: File, dest: File, stamp: String) {
        java.nio.file.Files.move(temp.toPath(), dest.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING,
            java.nio.file.StandardCopyOption.ATOMIC_MOVE)
        File(dest.parentFile, dest.name + ".sha256").writeText(stamp)
    }

    fun fetch(part:ResourcePacks.Part,dest:File,c:Control,releaseAsset:Boolean=false,progress:(Long)->Unit) {
        check(dest.parentFile!!.mkdirs()||dest.parentFile!!.isDirectory) {"无法准备存储目录"}
        val marker=File(dest.parentFile,dest.name+".sha256")
        val stamp=if(releaseAsset)"${part.bytes}:${part.sha256}" else part.sha256
        if(dest.length()==part.bytes && digest(dest,c)==part.sha256) {marker.writeText(stamp);progress(part.bytes);return}
        val temp=File(dest.parentFile,dest.name+".part")
        val partialMarker=if(releaseAsset)File(dest.parentFile,temp.name+".sha256") else null
        if(partialMarker!=null) {
            val previous=runCatching {partialMarker.readText()}.getOrDefault("")
            if(temp.exists() && previous!=stamp)check(temp.delete()) {"无法清理旧版词库的下载进度"}
            partialMarker.writeText(stamp)
        }
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
        install(temp,dest,stamp)
        partialMarker?.delete();progress(part.bytes)
    }
}
