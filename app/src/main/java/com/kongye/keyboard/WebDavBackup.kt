package com.kongye.keyboard

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Xml
import okhttp3.Credentials
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MediaType.Companion.toMediaType
import org.json.JSONObject
import org.xmlpull.v1.XmlPullParser
import java.io.File
import java.security.KeyStore
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Explicit WebDAV transfers; credentials never enter exported settings or redirects. */
object WebDavBackup {
    data class Config(val address:String="",val user:String="",val password:String="")
    data class Entry(val name:String,val url:String,val bytes:Long)
    private val client=OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
        .connectTimeout(15,TimeUnit.SECONDS).readTimeout(60,TimeUnit.SECONDS).writeTimeout(60,TimeUnit.SECONDS).callTimeout(5,TimeUnit.MINUTES).build()
    /** Every scheduled transfer has its own cancellation; manual requests are unaffected. */
    class Transfer {
        @Volatile private var cancelled=false
        @Volatile private var current:okhttp3.Call?=null
        @Synchronized fun cancel() {cancelled=true;current?.cancel()}
        fun checkActive() {if(cancelled||Thread.currentThread().isInterrupted)throw java.io.InterruptedIOException("备份已停止")}
        fun execute(request:Request):okhttp3.Response {
            val call=client.newCall(request)
            synchronized(this) {checkActive();current=call}
            return call.execute()
        }
    }
    private fun key():SecretKey {
        val store=KeyStore.getInstance("AndroidKeyStore").apply {load(null)}
        (store.getKey("kongye_webdav",null) as? SecretKey)?.let {return it}
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder("kongye_webdav",KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
    private fun secret(ctx:Context)=File(ctx.noBackupFilesDir,"webdav-credentials")
    @Synchronized fun save(ctx:Context,config:Config) {
        val value=userConfig(config)
        val cipher=Cipher.getInstance("AES/GCM/NoPadding").apply {init(Cipher.ENCRYPT_MODE,key())}
        val plain=JSONObject().put("address",value.address.trim()).put("user",value.user).put("password",value.password).toString()
        val json=JSONObject().put("iv",Base64.encodeToString(cipher.iv,Base64.NO_WRAP)).put("value",Base64.encodeToString(cipher.doFinal(plain.toByteArray()),Base64.NO_WRAP))
        val temp=File(ctx.noBackupFilesDir,"webdav-credentials.tmp");temp.writeText(json.toString());check(temp.renameTo(secret(ctx))){"无法保存 WebDAV 配置"}
    }
    @Synchronized fun read(ctx:Context):Config {
        if(!secret(ctx).exists())return Config()
        val data=JSONObject(secret(ctx).readText())
        val cipher=Cipher.getInstance("AES/GCM/NoPadding").apply {init(Cipher.DECRYPT_MODE,key(),GCMParameterSpec(128,Base64.decode(data.getString("iv"),Base64.NO_WRAP)))}
        val plain=JSONObject(String(cipher.doFinal(Base64.decode(data.getString("value"),Base64.NO_WRAP))))
        return userConfig(Config(plain.getString("address"),plain.getString("user"),plain.getString("password")))
    }
    @Synchronized fun clear(ctx:Context){check(!secret(ctx).exists()||secret(ctx).delete())}
    internal fun endpoint(config:Config):HttpUrl {
        val url=runCatching {config.address.trim().toHttpUrl()}.getOrElse {error("请输入完整的 WebDAV 文件夹地址")}
        require(url.isHttps||url.host in setOf("127.0.0.1","localhost","::1")){"WebDAV 地址需要使用 HTTPS"}
        require(url.username.isEmpty()&&url.password.isEmpty()&&url.query==null&&url.fragment==null){"地址请只填写服务器和文件夹，账号密码填在下方"}
        return if(url.encodedPath.endsWith('/'))url else url.newBuilder().addPathSegment("").build()
    }
    private const val DIRECTORY="KongyeKeyboard"
    /** Old saved full paths are shown and stored as the provider's base address. */
    internal fun userConfig(config:Config):Config {
        val base=endpoint(config)
        return if(base.host=="dav.jianguoyun.com" && base.encodedPath.trimEnd('/') in setOf("/dav","/dav/"+DIRECTORY))
            config.copy(address=base.newBuilder().encodedPath("/dav/").build().toString()) else config
    }
    internal fun backupEndpoint(config:Config):HttpUrl {
        val base=endpoint(config)
        return if(needsBackupFolder(config))base.newBuilder().addPathSegment(DIRECTORY).addPathSegment("").build() else base
    }
    private fun request(config:Config,url:HttpUrl)=Request.Builder().url(url).header("Authorization",Credentials.basic(config.user,config.password,Charsets.UTF_8))
    private fun failure(code:Int):Nothing=error(when(code){401,403->"账号、密码或文件夹权限不正确";404,409->"备份目录暂不可用，请检查服务器地址和权限后重试。";301,302,307,308->"服务器要求跳转，请填写跳转后的完整地址";412->"同名备份已存在，请重试生成新备份";else->"WebDAV 请求失败（$code）"})
    fun list(config:Config,transfer:Transfer=Transfer()):List<Entry> =
        listAt(config,backupEndpoint(config),transfer,needsBackupFolder(config))
    private fun listAt(config:Config,base:HttpUrl,transfer:Transfer,allowMissing:Boolean):List<Entry> {
        val body="""<?xml version="1.0" encoding="utf-8"?><d:propfind xmlns:d="DAV:"><d:prop><d:resourcetype/><d:getcontentlength/></d:prop></d:propfind>""".toRequestBody("application/xml; charset=utf-8".toMediaType())
        transfer.execute(request(config,base).header("Depth","1").method("PROPFIND",body).build()).use {response->
            if(response.code==404 && allowMissing) {
                // Verify the account without creating anything during a connection test.
                listAt(config,endpoint(config),transfer,false)
                return emptyList()
            }
            if(!response.isSuccessful)failure(response.code)
            val input=response.body ?: error("服务器没有返回文件列表")
            require(input.contentLength()<=2*1024*1024){"文件列表过大"}
            val bytes=input.byteStream().use {it.readBytesLimited(2*1024*1024)}
            val parser=Xml.newPullParser();parser.setInput(bytes.inputStream(),"UTF-8")
            val entries=mutableListOf<Entry>();var href="";var size=0L;var collection=false;var count=0;var multi=false
            var folderFound=false
            val statuses=mutableListOf<Int>()
            while(parser.eventType!=XmlPullParser.END_DOCUMENT) {
                require(parser.eventType!=XmlPullParser.DOCDECL){"不支持的文件列表格式"}
                if(parser.eventType==XmlPullParser.START_TAG) when(parser.name) {
                    "multistatus"->multi=true
                    "response"->{href="";size=0;collection=false;statuses.clear();require(++count<=5000){"文件列表过大"}}
                    "href"->href=parser.nextText()
                    "getcontentlength"->size=parser.nextText().toLongOrNull() ?: 0
                    "collection"->collection=true
                    "status"->parser.nextText().split(' ').getOrNull(1)?.toIntOrNull()?.let {statuses.add(it)}
                }
                if(parser.eventType==XmlPullParser.END_TAG&&parser.name=="response") {
                    val url=base.resolve(href)
                    val allowed= url!=null && url.scheme==base.scheme && url.host==base.host && url.port==base.port &&
                        url.username.isEmpty() && url.password.isEmpty() && url.query==null && url.fragment==null
                    val successful=statuses.isEmpty() || statuses.any {it in 200..299}
                    if(allowed && url!!.encodedPath.trimEnd('/')==base.encodedPath.trimEnd('/')) {
                        if(!successful)failure(statuses.first())
                        require(collection) {"此地址不是 WebDAV 文件夹，请填写文件夹的完整地址"}
                        folderFound=true
                    }
                    if(successful&&url!=null&&!collection&&url.scheme==base.scheme&&url.host==base.host&&url.port==base.port&&url.username.isEmpty()&&url.password.isEmpty()&&url.query==null&&url.fragment==null&&url.encodedPath.substringBeforeLast('/')==base.encodedPath.trimEnd('/')) {
                        val name=url.pathSegments.last()
                        if(name.startsWith("kongye-")&&name.endsWith(".zip")&&!name.contains('/')&&!name.contains('\\'))entries+=Entry(name,url.toString(),size)
                    }
                }
                parser.next()
            }
            require(multi){"此地址没有返回 WebDAV 文件列表"}
            require(folderFound){"服务器未确认此 WebDAV 文件夹，请检查完整地址及文件夹权限"}
            return entries.distinctBy {it.url}.sortedByDescending {it.name}
        }
    }
    fun needsBackupFolder(config:Config):Boolean = runCatching {
        val base=endpoint(config)
        base.host=="dav.jianguoyun.com" && base.encodedPath.trimEnd('/')=="/dav"
    }.getOrDefault(false)

    /** Creates only our direct child; never replaces an existing resource. */
    fun createBackupFolder(ctx:Context,config:Config,transfer:Transfer=Transfer()):Config {
        val base=endpoint(config)
        val folder=if(base.pathSegments.filter {it.isNotEmpty()}.lastOrNull()==DIRECTORY) base
            else base.newBuilder().addPathSegment(DIRECTORY).addPathSegment("").build()
        val child=config.copy(address=folder.toString())
        transfer.execute(request(config,folder).method("MKCOL",null).build()).use {response->
            // 405 may mean it already exists. PROPFIND below must still confirm a collection.
            if(!response.isSuccessful && response.code!=405) {
                if(response.code in setOf(404,409))error("服务器无法准备备份目录（HTTP "+response.code+"），请检查地址和写入权限")
                failure(response.code)
            }
        }
        listAt(child,folder,transfer,false)
        transfer.checkActive()
        save(ctx,child)
        return child
    }

    fun upload(ctx:Context,config:Config,options:KeyboardBackup.Options,live:Boolean=true,transfer:Transfer=Transfer()):String {
        val uploadConfig=if(needsBackupFolder(config))createBackupFolder(ctx,config,transfer) else config
        val base=backupEndpoint(uploadConfig)
        val name="kongye-"+java.text.SimpleDateFormat("yyyyMMdd-HHmmss",java.util.Locale.ROOT).format(java.util.Date())+"-"+java.util.UUID.randomUUID().toString().take(8)+".zip"
        val temp=File.createTempFile("webdav-export-",".zip",ctx.cacheDir)
        try {
            transfer.checkActive()
            temp.outputStream().use {raw->
                val checked=object:java.io.FilterOutputStream(raw) {
                    override fun write(value:Int) {transfer.checkActive();out.write(value)}
                    override fun write(bytes:ByteArray,offset:Int,length:Int) {transfer.checkActive();out.write(bytes,offset,length)}
                }
                KeyboardBackup.write(ctx,checked,options,live)
            }
            transfer.checkActive()
            val url=base.newBuilder().addPathSegment(name).build()
            transfer.execute(request(uploadConfig,url).header("If-None-Match","*").put(temp.asRequestBody("application/zip".toMediaType())).build()).use {
                if(it.code in setOf(404,409)) {
                    val hint="请检查服务器地址和备份目录的写入权限。"
                    error("服务器未接受上传路径（HTTP "+it.code+"）。"+hint)
                }
                if(!it.isSuccessful)failure(it.code)
            }
            return name
        } finally {temp.delete()}
    }
    fun download(ctx:Context,config:Config,entry:Entry,transfer:Transfer=Transfer()):KeyboardBackup.Prepared {
        val url = backupFileUrl(config, entry)
        val temp=File.createTempFile("webdav-import-",".zip",ctx.cacheDir)
        try {
            transfer.execute(request(config,url).get().build()).use {response->
                if(!response.isSuccessful)failure(response.code)
                val body=response.body ?: error("备份文件为空");require(body.contentLength()<=260L*1024*1024){"备份文件过大"}
                body.byteStream().use {input->temp.outputStream().use {out->val b=ByteArray(65536);var total=0L;while(true){val n=input.read(b);if(n<0)break;total+=n;require(total<=260L*1024*1024){"备份文件过大"};out.write(b,0,n)}}}
            }
            return temp.inputStream().use {KeyboardBackup.read(ctx,it)}
        } finally {temp.delete()}
    }
    fun delete(config: Config, entry: Entry) {
        val url = backupFileUrl(config, entry)
        Transfer().execute(request(config, url).delete().build()).use { response ->
            when (response.code) {
                200, 204 -> Unit
                404 -> error("这份备份已不存在，请刷新列表。")
                202 -> error("服务器正在处理删除，请刷新列表确认。")
                else -> failure(response.code)
            }
        }
    }

    private fun backupFileUrl(config: Config, entry: Entry): HttpUrl {
        val url = entry.url.toHttpUrl()
        val name = entry.name
        require(name.startsWith("kongye-") && name.endsWith(".zip") && '/' !in name && '\\' !in name &&
            url == backupEndpoint(config).newBuilder().addPathSegment(name).build()) {
            "备份地址超出当前文件夹"
        }
        return url
    }

    private fun java.io.InputStream.readBytesLimited(limit:Int):ByteArray {
        val out=java.io.ByteArrayOutputStream();val b=ByteArray(8192);var count=0
        while(true){val n=read(b);if(n<0)break;count+=n;require(count<=limit){"文件列表过大"};out.write(b,0,n)}
        return out.toByteArray()
    }
}
