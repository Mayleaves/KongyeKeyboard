package com.kongye.keyboard

import android.app.Instrumentation
import android.content.Intent
import android.os.SystemClock
import java.io.File
import java.security.MessageDigest

object UpdateRegression {
    private fun fails(block:()->Unit):Boolean = try {block();false} catch(_:Exception) {true}
    @JvmStatic fun run(inst:Instrumentation) {
        val context=inst.targetContext
        val selected=UpdateChecker.parseReleases("""[
            {"tag_name":"v2.0.4","body":"compatibility bridge","assets":[]},
            {"tag_name":"v2.1.0","body":"stable engine","assets":[]},
            {"tag_name":"v9.0.0","prerelease":true,"assets":[]},
            {"tag_name":"nightly","assets":[]}
        ]""")
        check(selected.version=="2.1.0") {"Updater remained on compatibility label"}
        val variants="""[{"tag_name":"v2.1.0","assets":[
            {"name":"KongyeKeyboard-2.1.0-x86_64.apk","browser_download_url":"https://github.com/test/x86_64.apk"},
            {"name":"KongyeKeyboard-2.1.0-universal.apk","browser_download_url":"https://github.com/test/universal.apk"},
            {"name":"KongyeKeyboard-2.1.0-arm64.apk","browser_download_url":"https://github.com/test/arm64.apk"}
        ]}]"""
        check(UpdateChecker.parseReleases(variants,listOf("arm64-v8a","armeabi-v7a")).apkUrl!!.endsWith("/arm64.apk"))
        check(UpdateChecker.parseReleases(variants,listOf("x86_64","x86")).apkUrl!!.endsWith("/x86_64.apk"))
        check(UpdateChecker.parseReleases(variants,listOf("armeabi-v7a")).apkUrl!!.endsWith("/universal.apk"))
        val apk=File(context.cacheDir,"updates/test-installed.apk").apply {parentFile!!.mkdirs()}
        try {
            java.nio.file.Files.copy(File(context.applicationInfo.sourceDir).toPath(),apk.toPath(),java.nio.file.StandardCopyOption.REPLACE_EXISTING)
            val hash=MessageDigest.getInstance("SHA-256")
            val input=java.io.FileInputStream(apk)
            try {val buffer=ByteArray(128*1024);while(true) {val count=input.read(buffer);if(count<0) break;hash.update(buffer,0,count)}} finally {input.close()}
            val digest=hash.digest().joinToString("") {"%02x".format(it)}
            AppUpdate.validate(context,apk,UpdateChecker.currentVersion(context),digest)
            check(fails {AppUpdate.validate(context,apk,"99.99.99",digest)})
            check(fails {AppUpdate.validate(context,apk,UpdateChecker.currentVersion(context),"0".repeat(64))})
            check(fails {AppUpdate.download(context,UpdateChecker.Release("v99","","https://example.com/evil.apk",""),{false},{_,_->})})
        } finally {apk.delete()}
    }

    @JvmStatic fun testInstallation(inst: Instrumentation) {
        check(android.os.Build.MODEL.startsWith("sdk_"))
        val context = inst.targetContext
        check(context.packageManager.canRequestPackageInstalls()) { "Allow installation from this app on the test emulator" }
        val file = File(context.applicationInfo.sourceDir)
        val version = UpdateChecker.currentVersion(context)
        val release = UpdateChecker.Release("v$version", "", null, "")
        val activity = inst.startActivitySync(Intent(context, SettingsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))

        fun waitFor(label: String, condition: () -> Boolean) {
            val until = SystemClock.uptimeMillis() + 30_000
            while (!condition() && SystemClock.uptimeMillis() < until) SystemClock.sleep(50)
            check(condition()) { "$label: ${AppUpdate.installation.value}" }
        }
        try {
            inst.runOnMainSync {
                AppUpdate.clearInstallationResult(context)
                AppUpdate.install(context, file, release)
                check(AppUpdate.installation.value is AppUpdate.Installation.Preparing)
            }
            waitFor("System confirmation callback") { AppUpdate.installation.value is AppUpdate.Installation.Confirm }
            val confirmation = AppUpdate.installation.value as AppUpdate.Installation.Confirm
            check((AppUpdate.installationProgress(context, confirmation.sessionId) ?: 0f) > 0f)
            inst.runOnMainSync { AppUpdate.requestConfirmation(activity, confirmation) }
            waitFor("System installer in foreground") {
                inst.uiAutomation.rootInActiveWindow?.packageName?.toString()?.let { it != context.packageName } == true
            }
            android.os.ParcelFileDescriptor.AutoCloseInputStream(
                inst.uiAutomation.executeShellCommand("input keyevent KEYCODE_BACK")
            ).use { it.readBytes() }
            waitFor("Cancellation callback") { AppUpdate.installation.value is AppUpdate.Installation.Failed }
            check((AppUpdate.installation.value as AppUpdate.Installation.Failed).message == "已取消安装。")
        } finally {
            inst.runOnMainSync {
                AppUpdate.clearInstallationResult(context)
                activity.finish()
            }
        }
    }
    @JvmStatic fun download(inst:Instrumentation) {
        val original=inst.targetContext
        val directory=File(original.cacheDir,"app-update-download-regression").apply {mkdirs()}
        val context=object:android.content.ContextWrapper(original) {override fun getCacheDir()=directory}
        val release=UpdateChecker.Release("v2.0.1","","https://github.com/Mayleaves/KongyeKeyboard/releases/download/v2.0.1/KongyeKeyboard-2.0.1-arm64.apk","","824090afac96ff73388815c1bc1e388e0e17bfda0da927e162148e4f05c1cf48","https://api.github.com/repos/Mayleaves/KongyeKeyboard/releases/assets/731126983")
        val started=android.os.SystemClock.elapsedRealtime()
        var reported=0L
        val file=AppUpdate.download(context,release,{android.os.SystemClock.elapsedRealtime()-started>180000},progress={bytes,total ->
            if(bytes-reported>5*1024*1024) {
                reported=bytes
                inst.sendStatus(0,android.os.Bundle().apply {putString("stream","APK download ${bytes/1024/1024} MB / ${total/1024/1024} MB\n")})
            }
        })
        check(file.length()==78363144L)
        file.delete();File(file.parentFile,"update.apk.part").delete()
    }
}
