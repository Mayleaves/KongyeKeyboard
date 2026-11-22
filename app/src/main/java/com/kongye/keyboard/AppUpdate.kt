package com.kongye.keyboard

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.IntentCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/** App-private APK download. Nothing in the update flow launches a web page. */
object AppUpdate {
    private const val MAX_BYTES=1024L*1024*1024
    sealed interface Installation {
        data object Idle : Installation
        data class Preparing(val percent: Int? = null) : Installation
        data class Confirm(val sessionId: Int, val intent: Intent) : Installation
        data class Installing(val sessionId: Int) : Installation
        data object Complete : Installation
        data class Failed(val message: String) : Installation
    }

    private val installState = MutableStateFlow<Installation>(Installation.Idle)
    val installation = installState.asStateFlow()
    private fun installPrefs(context: Context) = context.getSharedPreferences("app_update_install", Context.MODE_PRIVATE)
    fun isInstalling() = installState.value.let {
        it is Installation.Preparing || it is Installation.Confirm || it is Installation.Installing
    }

    fun restoreInstallation(context: Context) {
        if (installState.value != Installation.Idle) return
        val prefs = installPrefs(context)
        val version = prefs.getString("version", null) ?: return
        val id = prefs.getInt("session", -1)
        val installer = context.packageManager.packageInstaller
        installState.value = when {
            UpdateChecker.currentVersion(context) == version -> Installation.Complete
            prefs.contains("error") -> Installation.Failed(prefs.getString("error", "").orEmpty())
            id >= 0 && prefs.getBoolean("committed", false) && !prefs.getBoolean("confirmation_pending", false) &&
                installer.getSessionInfo(id) != null ->
                Installation.Installing(id)
            else -> {
                if (id >= 0) runCatching { installer.abandonSession(id) }
                Installation.Failed("安装已中断，请重试。")
            }
        }
    }

    fun clearInstallationResult(context: Context) {
        if (isInstalling()) return
        installPrefs(context).edit().clear().apply()
        installState.value = Installation.Idle
    }

    fun install(context: Context, file: File, release: UpdateChecker.Release) {
        if (isInstalling()) return
        val app = context.applicationContext
        installState.value = Installation.Preparing()
        Thread({
            val installer = app.packageManager.packageInstaller
            val prefs = installPrefs(app)
            var id = -1
            try {
                validate(app, file, release.version, release.sha256)
                val total = file.length()
                val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
                    setAppPackageName(app.packageName)
                    setSize(total)
                    if (Build.VERSION.SDK_INT >= 31) setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_REQUIRED)
                }
                id = installer.createSession(params)
                check(prefs.edit().clear().putInt("session", id).putString("version", release.version).commit())
                installer.openSession(id).use { session ->
                    var written = 0L
                    var percent = 0
                    installState.value = Installation.Preparing(0)
                    file.inputStream().use { input ->
                        session.openWrite("base.apk", 0, total).use { output ->
                            val buffer = ByteArray(256 * 1024)
                            while (true) {
                                val count = input.read(buffer)
                                if (count < 0) break
                                output.write(buffer, 0, count)
                                written += count
                                val next = (written * 100 / total).toInt().coerceIn(0, 99)
                                if (next != percent) {
                                    percent = next
                                    installState.value = Installation.Preparing(percent)
                                    session.setStagingProgress(written.toFloat() / total)
                                }
                            }
                            check(written == total) { "安装包不完整，请重新下载。" }
                            session.fsync(output)
                        }
                    }
                    session.setStagingProgress(1f)
                    val callback = Intent(app, UpdateInstallReceiver::class.java)
                    val flags = if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
                    val status = PendingIntent.getBroadcast(app, id, callback, flags)
                    check(prefs.edit().putBoolean("committed", true).commit())
                    installState.value = Installation.Installing(id)
                    session.commit(status.intentSender)
                }
            } catch (error: Exception) {
                if (id >= 0) runCatching { installer.abandonSession(id) }
                prefs.edit().clear().apply()
                val message = if (error is IllegalArgumentException) error.message else null
                installState.value = Installation.Failed(message ?: "无法准备安装，请检查存储空间后重试。")
                android.util.Log.w("KongyeUpdate", "Cannot stage update", error)
            }
        }, "Kongye update installer").start()
    }

    fun requestConfirmation(context: Context, confirmation: Installation.Confirm) {
        if (installState.value != confirmation) return
        runCatching {
            context.startActivity(confirmation.intent)
            installPrefs(context).edit().remove("confirmation_pending").apply()
            installState.value = Installation.Installing(confirmation.sessionId)
        }.onFailure {
            runCatching { context.packageManager.packageInstaller.abandonSession(confirmation.sessionId) }
            installPrefs(context).edit().clear().apply()
            installState.value = Installation.Failed("无法打开系统安装确认，请重试。")
        }
    }

    fun installationProgress(context: Context, sessionId: Int): Float? =
        context.packageManager.packageInstaller.getSessionInfo(sessionId)?.progress?.coerceIn(0f, 1f)

    internal fun receiveInstallResult(context: Context, intent: Intent) {
        val id = intent.getIntExtra(PackageInstaller.EXTRA_SESSION_ID, -1)
        val prefs = installPrefs(context)
        if (id < 0 || prefs.getInt("session", -1) != id) return
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            val confirmation = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_INTENT, Intent::class.java)
            if (confirmation != null) {
                prefs.edit().putBoolean("confirmation_pending", true).apply()
                installState.value = Installation.Confirm(id, confirmation)
                return
            }
            runCatching { context.packageManager.packageInstaller.abandonSession(id) }
        }
        val message = when (status) {
            PackageInstaller.STATUS_SUCCESS -> null
            PackageInstaller.STATUS_FAILURE_ABORTED -> "已取消安装。"
            PackageInstaller.STATUS_FAILURE_STORAGE -> "存储空间不足，请清理空间后重试。"
            PackageInstaller.STATUS_FAILURE_BLOCKED -> "系统阻止了安装，请检查此应用的安装权限。"
            PackageInstaller.STATUS_FAILURE_CONFLICT -> "安装包与设备上已有的应用冲突。"
            PackageInstaller.STATUS_FAILURE_INCOMPATIBLE -> "此安装包与设备不兼容。"
            else -> "安装失败，请重试。"
        }
        prefs.edit().remove("session").putString("error", message).apply()
        installState.value = if (message == null) Installation.Complete else Installation.Failed(message)
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
    fun download(context:Context,release:UpdateChecker.Release,cancelled:()->Boolean,
        progress:(Long,Long)->Unit,validating:()->Unit={}):File {
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
            validating()
            validate(context,partial,release.version,release.sha256)
            if(complete.exists()) check(complete.delete()) {"无法替换旧安装包"}
            check(partial.renameTo(complete)) {"无法保存安装包"}
            return complete
        } finally {connection?.disconnect();partial.delete()}
    }
}

class UpdateInstallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) = AppUpdate.receiveInstallResult(context, intent)
}
