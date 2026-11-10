package com.kongye.keyboard

import android.app.Instrumentation
import android.content.Intent
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
            val installer=AppUpdate.installer(context,apk)
            check(installer.action==Intent.ACTION_VIEW && installer.data?.scheme=="content" && installer.type=="application/vnd.android.package-archive")
            check(installer.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION!=0)
            check(fails {AppUpdate.download(context,UpdateChecker.Release("v99","","https://example.com/evil.apk",""),{false},{_,_->})})
        } finally {apk.delete()}
    }
    @JvmStatic fun download(inst:Instrumentation) {
        val original=inst.targetContext
        val directory=File(original.cacheDir,"app-update-download-regression").apply {mkdirs()}
        val context=object:android.content.ContextWrapper(original) {override fun getCacheDir()=directory}
        val release=UpdateChecker.Release("v2.0.1","","https://github.com/Mayleaves/KongyeKeyboard/releases/download/v2.0.1/KongyeKeyboard-2.0.1-arm64.apk","","824090afac96ff73388815c1bc1e388e0e17bfda0da927e162148e4f05c1cf48","https://api.github.com/repos/Mayleaves/KongyeKeyboard/releases/assets/731126983")
        val started=android.os.SystemClock.elapsedRealtime()
        var reported=0L
        val file=AppUpdate.download(context,release,{android.os.SystemClock.elapsedRealtime()-started>180000}) {bytes,total ->
            if(bytes-reported>5*1024*1024) {
                reported=bytes
                inst.sendStatus(0,android.os.Bundle().apply {putString("stream","APK download ${bytes/1024/1024} MB / ${total/1024/1024} MB\n")})
            }
        }
        check(file.length()==78363144L)
        file.delete();File(file.parentFile,"update.apk.part").delete()
    }
}
