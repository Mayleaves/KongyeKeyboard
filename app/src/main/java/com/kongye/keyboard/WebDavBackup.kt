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
        endpoint(config)
        val cipher=Cipher.getInstance("AES/GCM/NoPadding").apply {init(Cipher.ENCRYPT_MODE,key())}
        val plain=JSONObject().put("address",config.address.trim()).put("user",config.user).put("password",config.password).toString()
        val json=JSONObject().put("iv",Base64.encodeToString(cipher.iv,Base64.NO_WRAP)).put("value",Base64.encodeToString(cipher.doFinal(plain.toByteArray()),Base64.NO_WRAP))
        val temp=File(ctx.noBackupFilesDir,"webdav-credentials.tmp");temp.writeText(json.toString());check(temp.renameTo(secret(ctx))){"无法保存 WebDAV 配置"}
    }
    @Synchronized fun read(ctx:Context):Config {
        if(!secret(ctx).exists())return Config()
        val data=JSONObject(secret(ctx).readText())
        val cipher=Cipher.getInstance("AES/GCM/NoPadding").apply {init(Cipher.DECRYPT_MODE,key(),GCMParameterSpec(128,Base64.decode(data.getString("iv"),Base64.NO_WRAP)))}
        val plain=JSONObject(String(cipher.doFinal(Base64.decode(data.getString("value"),Base64.NO_WRAP))))
        return Config(plain.getString("address"),plain.getString("user"),plain.getString("password"))
    }
    @Synchronized fun clear(ctx:Context){check(!secret(ctx).exists()||secret(ctx).delete())}
    internal fun endpoint(config:Config):HttpUrl {
        val url=runCatching {config.address.trim().toHttpUrl()}.getOrElse {error("请输入完整的 WebDAV 文件夹地址")}
        require(url.isHttps||url.host in setOf("127.0.0.1","localhost","::1")){"WebDAV 地址需要使用 HTTPS"}
        require(url.username.isEmpty()&&url.password.isEmpty()&&url.query==null&&url.fragment==null){"地址请只填写服务器和文件夹，账号密码填在下方"}
        return if(url.encodedPath.endsWith('/'))url else url.newBuilder().addPathSegment("").build()
    }
    private fun request(config:Config,url:HttpUrl)=Request.Builder().url(url).header("Authorization",Credentials.basic(config.user,config.password,Charsets.UTF_8))
    private fun failure(code:Int):Nothing=error(when(code){401,403->"账号、密码或文件夹权限不正确";404,409->"找不到 WebDAV 文件夹，请先在服务器创建并填写完整地址";301,302,307,308->"服务器要求跳转，请填写跳转后的完整地址";412->"同名备份已存在，请重试生成新备份";else->"WebDAV 请求失败（$code）"})
    fun list(config:Config):List<Entry> {
        val base=endpoint(config)
        val body="""<?xml version="1.0" encoding="utf-8"?><d:propfind xmlns:d="DAV:"><d:prop><d:resourcetype/><d:getcontentlength/></d:prop></d:propfind>""".toRequestBody("application/xml; charset=utf-8".toMediaType())
        client.newCall(request(config,base).header("Depth","1").method("PROPFIND",body).build()).execute().use {response->
            if(!response.isSuccessful)failure(response.code)
            val input=response.body ?: error("服务器没有返回文件列表")
            require(input.contentLength()<=2*1024*1024){"文件列表过大"}
            val bytes=input.byteStream().use {it.readBytesLimited(2*1024*1024)}
            val parser=Xml.newPullParser();parser.setInput(bytes.inputStream(),"UTF-8")
            val entries=mutableListOf<Entry>();var href="";var size=0L;var collection=false;var count=0;var multi=false
            while(parser.eventType!=XmlPullParser.END_DOCUMENT) {
                require(parser.eventType!=XmlPullParser.DOCDECL){"不支持的文件列表格式"}
                if(parser.eventType==XmlPullParser.START_TAG) when(parser.name) {
                    "multistatus"->multi=true
                    "response"->{href="";size=0;collection=false;require(++count<=5000){"文件列表过大"}}
                    "href"->href=parser.nextText()
                    "getcontentlength"->size=parser.nextText().toLongOrNull() ?: 0
                    "collection"->collection=true
                }
                if(parser.eventType==XmlPullParser.END_TAG&&parser.name=="response") {
                    val url=base.resolve(href)
                    if(url!=null&&!collection&&url.scheme==base.scheme&&url.host==base.host&&url.port==base.port&&url.username.isEmpty()&&url.password.isEmpty()&&url.query==null&&url.fragment==null&&url.encodedPath.substringBeforeLast('/')==base.encodedPath.trimEnd('/')) {
                        val name=url.pathSegments.last()
                        if(name.startsWith("kongye-")&&name.endsWith(".zip")&&!name.contains('/')&&!name.contains('\\'))entries+=Entry(name,url.toString(),size)
                    }
                }
                parser.next()
            }
            require(multi){"此地址没有返回 WebDAV 文件列表"}
            return entries.distinctBy {it.url}.sortedByDescending {it.name}
        }
    }
    fun upload(ctx:Context,config:Config,options:KeyboardBackup.Options,live:Boolean=true):String {
        val name="kongye-"+java.text.SimpleDateFormat("yyyyMMdd-HHmmss",java.util.Locale.ROOT).format(java.util.Date())+"-"+java.util.UUID.randomUUID().toString().take(8)+".zip"
        val temp=File.createTempFile("webdav-export-",".zip",ctx.cacheDir)
        try {
            temp.outputStream().use {KeyboardBackup.write(ctx,it,options,live)}
            val url=endpoint(config).newBuilder().addPathSegment(name).build()
            client.newCall(request(config,url).header("If-None-Match","*").put(temp.asRequestBody("application/zip".toMediaType())).build()).execute().use {if(!it.isSuccessful)failure(it.code)}
            return name
        } finally {temp.delete()}
    }
    fun download(ctx:Context,config:Config,entry:Entry):KeyboardBackup.Prepared {
        val base=endpoint(config);val url=entry.url.toHttpUrl()
        require(url==base.newBuilder().addPathSegment(entry.name).build()&&entry.name.startsWith("kongye-")&&entry.name.endsWith(".zip")){"备份地址超出当前文件夹"}
        val temp=File.createTempFile("webdav-import-",".zip",ctx.cacheDir)
        try {
            client.newCall(request(config,url).get().build()).execute().use {response->
                if(!response.isSuccessful)failure(response.code)
                val body=response.body ?: error("备份文件为空");require(body.contentLength()<=260L*1024*1024){"备份文件过大"}
                body.byteStream().use {input->temp.outputStream().use {out->val b=ByteArray(65536);var total=0L;while(true){val n=input.read(b);if(n<0)break;total+=n;require(total<=260L*1024*1024){"备份文件过大"};out.write(b,0,n)}}}
            }
            return temp.inputStream().use {KeyboardBackup.read(ctx,it)}
        } finally {temp.delete()}
    }
    private fun java.io.InputStream.readBytesLimited(limit:Int):ByteArray {
        val out=java.io.ByteArrayOutputStream();val b=ByteArray(8192);var count=0
        while(true){val n=read(b);if(n<0)break;count+=n;require(count<=limit){"文件列表过大"};out.write(b,0,n)}
        return out.toByteArray()
    }
}
