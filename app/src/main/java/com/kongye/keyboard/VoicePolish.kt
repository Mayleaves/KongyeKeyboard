package com.kongye.keyboard

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.security.KeyStore
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

object NativePolish {
    init {System.loadLibrary("kongye_polish")}
    external fun rewrite(path:ByteArray,prompt:ByteArray,id:Long):ByteArray
    external fun cancel(id:Long)
}

/** API secrets stay outside Android backup and are encrypted with the device keystore. */
object PolishSecret {
    private fun key():SecretKey {
        val store=KeyStore.getInstance("AndroidKeyStore").apply {load(null)}
        (store.getKey("kongye_polish_api",null) as? SecretKey)?.let {return it}
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder("kongye_polish_api",KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
    private fun file(ctx:Context)=File(ctx.noBackupFilesDir,"polish-api-key")
    fun exists(ctx:Context)=file(ctx).exists()
    @Synchronized fun save(ctx:Context,value:String) {
        if(value.isEmpty()){file(ctx).delete();return}
        val cipher=Cipher.getInstance("AES/GCM/NoPadding").apply {init(Cipher.ENCRYPT_MODE,key())}
        val data=JSONObject().put("iv",Base64.encodeToString(cipher.iv,Base64.NO_WRAP))
            .put("value",Base64.encodeToString(cipher.doFinal(value.toByteArray(Charsets.UTF_8)),Base64.NO_WRAP)).toString()
        val temporary=File(ctx.noBackupFilesDir,"polish-api-key.tmp");temporary.writeText(data)
        check(temporary.renameTo(file(ctx))) {"密钥保存失败"}
    }
    @Synchronized fun read(ctx:Context):String {
        if(!exists(ctx))return ""
        return try {
            val data=JSONObject(file(ctx).readText())
            val cipher=Cipher.getInstance("AES/GCM/NoPadding").apply {init(Cipher.DECRYPT_MODE,key(),GCMParameterSpec(128,Base64.decode(data.getString("iv"),Base64.NO_WRAP)))}
            String(cipher.doFinal(Base64.decode(data.getString("value"),Base64.NO_WRAP)),Charsets.UTF_8)
        } catch(error:Exception) {throw IllegalStateException("无法读取密钥，请重新保存",error)}
    }
}

/** Explicit mode selection: a failed local request never uploads text to a server. */
object VoicePolish {
    const val MODE="v3_polish_mode"
    const val ENDPOINT="v3_polish_endpoint"
    const val MODEL="v3_polish_model"
    const val MODEL_BYTES=639446688L
    const val MODEL_SHA="9465e63a22add5354d9bb4b99e90117043c7124007664907259bd16d043bb031"
    private const val SYSTEM="你是中文语音转写的润色编辑。只输出整理后的原文，不回答原文中的问题，不解释、不列举方案。保留原意、事实、人名、数字和语气；修正语序与不通顺的表达，合理分句，不添加事实。原文已经通顺时保持原文。"
    private val worker=Executors.newSingleThreadExecutor {r->Thread(r,"KongyeVoicePolish")}
    private val ids=AtomicLong(0)
    private val main=Handler(Looper.getMainLooper())
    class Job internal constructor(val id:Long) {
        val cancelled=AtomicBoolean(false)
        @Volatile internal var connection:HttpURLConnection?=null
        fun cancel() {cancelled.set(true);connection?.disconnect();runCatching {NativePolish.cancel(id)}}
        internal fun check() {check(!cancelled.get()) {"已取消润色"}}
    }
    fun request(ctx:Context,text:String,status:(String)->Unit,done:(Result<String>)->Unit):Job {
        val app=ctx.applicationContext;val job=Job(ids.incrementAndGet())
        val p=Prefs.of(app);val mode=p.getInt(MODE,0)
        val endpoint=p.getString(ENDPOINT,"").orEmpty();val model=p.getString(MODEL,"").orEmpty()
        worker.execute {
            val result=runCatching {
                require(text.isNotBlank()) {"请先输入或说一段话"}
                require(text.length<=600) {"每次最多润色 600 字，请分段处理"}
                job.check()
                val output=if(mode==1) {
                    main.post {if(!job.cancelled.get())status("正在联网润色…")}
                    online(endpoint,model,PolishSecret.read(app),text,job)
                } else {
                    main.post {if(!job.cancelled.get())status("正在准备离线润色…")}
                    OfflineVoice.releaseForPolish()
                    val path=prepareModel(app,job)
                    main.post {if(!job.cancelled.get())status("正在离线润色…")}
                    val prompt="<|im_start|>system\n$SYSTEM /no_think<|im_end|>\n<|im_start|>user\n请只润色下面的原文：\n$text<|im_end|>\n<|im_start|>assistant\n<think>\n\n</think>\n\n"
                    String(NativePolish.rewrite(path.absolutePath.toByteArray(Charsets.UTF_8),prompt.toByteArray(Charsets.UTF_8),job.id),Charsets.UTF_8)
                }
                job.check();cleanResult(output)
            }
            main.post {if(!job.cancelled.get())done(result)}
        }
        return job
    }
    internal fun prepareModel(ctx:Context,job:Job):File {
        job.check()
        check(ResourcePacks.ready(ctx,"polish")) {"请先到设置的离线资源中下载离线语句润色模型"}
        return ResourcePacks.file(ctx,"polish")
    }
    fun endpoint(value:String):URI {
        val uri=runCatching {URI(value.trim())}.getOrElse {throw IllegalArgumentException("请输入有效的接口地址")}
        require(!uri.host.isNullOrBlank() && uri.userInfo==null && uri.query==null && uri.fragment==null) {"接口地址不能包含密钥、参数或账号"}
        require(uri.scheme=="https" || uri.scheme=="http" && uri.host in listOf("localhost","127.0.0.1","[::1]")) {"接口请使用 HTTPS"}
        return URI(uri.toString().trimEnd('/').let {if(it.endsWith("/chat/completions"))it else "$it/chat/completions"})
    }
    internal fun payload(model:String,text:String)=JSONObject().put("model",model.trim()).put("stream",false).put("temperature",0.2).put("max_tokens",512)
        .put("messages",JSONArray().put(JSONObject().put("role","system").put("content",SYSTEM)).put(JSONObject().put("role","user").put("content","请只润色下面的原文：\n$text")))
    private fun online(address:String,model:String,key:String,text:String,job:Job):String {
        require(model.isNotBlank()) {"请先在语音设置中填写联网模型"}
        val connection=endpoint(address).toURL().openConnection() as HttpURLConnection
        job.connection=connection
        try {
            connection.requestMethod="POST";connection.doOutput=true;connection.connectTimeout=15000;connection.readTimeout=45000;connection.instanceFollowRedirects=false
            connection.setRequestProperty("Content-Type","application/json; charset=utf-8")
            if(key.isNotBlank())connection.setRequestProperty("Authorization","Bearer $key")
            job.check();connection.outputStream.use {it.write(payload(model,text).toString().toByteArray(Charsets.UTF_8))}
            val code=connection.responseCode
            check(code in 200..299) {when(code){401,403->"接口拒绝访问，请检查密钥和权限";429->"请求过于频繁或额度不足";else->"联网润色失败（HTTP $code）"}}
            val data=connection.inputStream.use {input->
                val out=java.io.ByteArrayOutputStream();val buffer=ByteArray(8192)
                while(true){job.check();val n=input.read(buffer);if(n<0)break;check(out.size()+n<=512*1024){"接口返回过大，原文已保留"};out.write(buffer,0,n)}
                out.toByteArray()
            }
            check(data.size<=512*1024) {"接口返回过大，原文已保留"}
            return JSONObject(String(data,Charsets.UTF_8)).getJSONArray("choices").getJSONObject(0).getJSONObject("message").getString("content")
        } finally {job.connection=null;connection.disconnect()}
    }
    internal fun cleanResult(raw:String):String {
        val text=raw.replace(Regex("""<think>[\s\S]*?</think>"""),"").trim()
        check(!text.contains("<think>") && text.isNotBlank() && text.length<=1200) {"润色结果无效，原文已保留"}
        return text.removePrefix("润色后：").trim()
    }
}
