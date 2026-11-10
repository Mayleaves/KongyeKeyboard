package com.kongye.keyboard

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.FileProvider
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/** App-private APK download. Nothing in the update flow launches a web page. */
object AppUpdate {
    private const val MAX_BYTES=1024L*1024*1024
    fun installer(context:Context,file:File):Intent = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(FileProvider.getUriForFile(context,"${context.packageName}.files",file),"application/vnd.android.package-archive")
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    @Suppress("DEPRECATION")
    fun validate(context:Context,file:File,version:String,sha256:String?) {
        require(file.length() in 1..MAX_BYTES) {"安装包大小异常"}
        if(sha256!=null) {
            val digest=MessageDigest.getInstance("SHA-256")
            file.inputStream().use {input -> val buffer=ByteArray(128*1024);while(true) {val n=input.read(buffer);if(n<0)break;digest.update(buffer,0,n)}}
            val actual=digest.digest().joinToString("") {"%02x".format(it)}
            require(actual.equals(sha256,true)) {"安装包校验失败，请重新下载"}
        }
        val flags=if(Build.VERSION.SDK_INT>=28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
        val pm=context.packageManager
        val archive=pm.getPackageArchiveInfo(file.absolutePath,flags) ?: error("不是有效的安装包")
        require(archive.packageName==context.packageName && archive.versionName==version) {"安装包与更新版本不一致"}
        val installed=pm.getPackageInfo(context.packageName,flags)
        fun certs(info:android.content.pm.PackageInfo):Set<String> {
            val signatures=if(Build.VERSION.SDK_INT>=28) info.signingInfo?.apkContentsSigners else info.signatures
            return signatures.orEmpty().map {sig -> MessageDigest.getInstance("SHA-256").digest(sig.toByteArray()).joinToString("") {"%02x".format(it)}}.toSet()
        }
        val signer=certs(archive)
        require(signer.isNotEmpty() && signer==certs(installed)) {"安装包签名不一致，已停止安装"}
    }
    fun download(context:Context,release:UpdateChecker.Release,cancelled:()->Boolean,progress:(Long,Long)->Unit):File {
        val browserSource=release.apkUrl ?: error("这个版本没有提供安装包")
        require(browserSource.startsWith("${UpdateChecker.REPOSITORY}/releases/download/")) {"安装包地址不受支持"}
        // The public asset API serves binaries without navigating github.com's web UI.
        val apiPrefix="https://api.github.com/repos/${UpdateChecker.REPO}/releases/assets/"
        val source=release.assetApiUrl?.takeIf {it.startsWith(apiPrefix) && it.removePrefix(apiPrefix).matches(Regex("[0-9]+"))} ?: browserSource
        val directory=File(context.cacheDir,"updates").apply {mkdirs()}
        val partial=File(directory,"update.apk.part")
        val complete=File(directory,"update.apk")
        var connection:HttpURLConnection?=null
        try {
            var url=URL(source)
            for(attempt in 0..5) {
                require(url.protocol=="https" && url.host in setOf("api.github.com","github.com","release-assets.githubusercontent.com","objects.githubusercontent.com")) {"下载地址不受支持"}
                val next=(url.openConnection() as HttpURLConnection).apply {
                    instanceFollowRedirects=false;connectTimeout=15000;readTimeout=20000
                    setRequestProperty("User-Agent","KongyeKeyboard")
                    setRequestProperty("Accept","application/octet-stream")
                }
                connection=next
                val code=next.responseCode
                if(code in setOf(301,302,303,307,308)) {
                    require(attempt<5) {"下载跳转次数过多"}
                    val location=next.getHeaderField("Location") ?: error("下载地址失效")
                    url=URL(url,location);next.disconnect();connection=null
                } else {require(code==200) {"下载失败（$code），请重试"};break}
            }
            val active=connection ?: error("下载地址失效")
            val total=active.contentLengthLong
            require(total<=MAX_BYTES) {"安装包过大"}
            active.inputStream.use {input -> partial.outputStream().use {output ->
                val buffer=ByteArray(128*1024);var received=0L;var reported=0L
                while(true) {
                    check(!cancelled()) {"下载已取消"}
                    val n=input.read(buffer);if(n<0)break
                    received+=n;require(received<=MAX_BYTES) {"安装包过大"};output.write(buffer,0,n)
                    if(received-reported>=512*1024) {reported=received;progress(received,total)}
                }
                require(total<=0 || received==total) {"安装包下载不完整"}
                progress(received,total)
            }}
            check(!cancelled()) {"下载已取消"}
            validate(context,partial,release.version,release.sha256)
            if(complete.exists()) check(complete.delete()) {"无法替换旧安装包"}
            check(partial.renameTo(complete)) {"无法保存安装包"}
            return complete
        } finally {connection?.disconnect();partial.delete()}
    }
}
