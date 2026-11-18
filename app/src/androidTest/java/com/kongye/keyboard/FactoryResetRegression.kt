package com.kongye.keyboard

import android.app.Instrumentation
import android.content.Context
import android.content.ContextWrapper
import android.os.Bundle
import java.io.File

object FactoryResetRegression {
    fun run(i:Instrumentation) {
        val app=i.targetContext
        val original=Prefs.of(app).all.toMap()
        val work=File(app.cacheDir,"factory-reset-regression").apply {mkdirs()}
        val isolated=object:ContextWrapper(app) {
            override fun getNoBackupFilesDir()=File(work,"connections").apply {mkdirs()}
            override fun getFilesDir()=File(work,"user-data").apply {mkdirs()}
        }
        val p=app.getSharedPreferences("factory-reset-regression",Context.MODE_PRIVATE)
        p.edit().clear().commit()
        try {
            val profile=InstallDefaults.profile(app)
            val changed=p.edit()
            for(key in profile.keys())changed.putString(key,"changed")
            FloatingKeyboard.keys.forEach {changed.putString(it,"custom floating value")}
            changed.putString(V3Prefs.LAYOUT_LANDSCAPE,"9").putInt(V3Prefs.CLIP_DAYS,30)
                .putString(VoicePolish.ENDPOINT,"https://example.invalid/api")
                .putString(VoicePolish.MODEL,"custom")
                .putString("flick26_zh_a_down","edit:copy").putString("flick26_zh_comma_down","text:!").putString("flick26_en_period_up","edit:undo")
                .putString(Prefs.CLIP_CATS,"personal categories")
                .putString(Prefs.FAV_EMOJI,"personal favorites")
                .putInt(Prefs.COUNT_TODAY,321).putLong("backup_last_export",123L).commit()
            val personal=listOf("user_lexicon.txt","typing-stats.json","clipboard.json","downloaded-model.bin")
                .associateWith {"retained data for $it"}
            personal.forEach {(name,value)->File(isolated.filesDir,name).writeText(value)}
            File(isolated.noBackupFilesDir,"webdav-credentials").writeText("isolated server credentials")
            File(isolated.noBackupFilesDir,"polish-api-key").writeText("isolated API credentials")
            InstallDefaults.reset(isolated,p)
            for(key in profile.keys()) {
                val item=profile.getJSONObject(key)
                val expected=when(item.getString("type")) {
                    "b"->item.getBoolean("value");"i"->item.getInt("value");"l"->item.getLong("value")
                    "f"->item.getDouble("value").toFloat();"s"->item.getString("value");else->error("type")
                }
                check(p.all[key]==expected) {"Reset did not restore $key"}
            }
            for(key in listOf(V3Prefs.LAYOUT_LANDSCAPE,V3Prefs.CLIP_DAYS,VoicePolish.ENDPOINT,VoicePolish.MODEL,"flick26_zh_a_down","flick26_zh_comma_down","flick26_en_period_up"))check(!p.contains(key)) {"Custom setting remains: $key"}
            FloatingKeyboard.keys.forEach {check(!p.contains(it)) {"Floating override remains: $it"}}
            check(p.getString(Prefs.CLIP_CATS,"")=="personal categories")
            check(p.getString(Prefs.FAV_EMOJI,"")=="personal favorites")
            check(p.getInt(Prefs.COUNT_TODAY,0)==321&&p.getLong("backup_last_export",0)==123L)
            personal.forEach {(name,value)->check(File(isolated.filesDir,name).readText()==value)}
            check(!File(isolated.noBackupFilesDir,"webdav-credentials").exists())
            check(!PolishSecret.exists(isolated))
            val once=p.all.toMap();InstallDefaults.reset(isolated,p);check(p.all==once)
            check(Prefs.of(app).all==original) {"Phone configuration changed during isolated reset check"}
            i.sendStatus(0,Bundle().apply {putString("stream","FACTORY RESET PASSED: factory values restored; extra layouts, flicks and connections removed; personal data, history and downloaded resources retained; Sony configuration unchanged.\n")})
        } finally {
            p.edit().clear().commit()
            work.walkBottomUp().forEach {check(it.delete()||!it.exists())}
        }
    }
}
