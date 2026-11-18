package com.kongye.keyboard

import android.app.Instrumentation
import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.os.Bundle
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

object BackupResourceRegression {
    fun run(i:Instrumentation) {
        val original=i.targetContext
        val root=File(original.cacheDir,"backup-roundtrip-regression").apply {deleteRecursively();mkdirs()}
        val ctx=object:ContextWrapper(original) {
            override fun getApplicationContext():Context=this
            override fun getFilesDir()=File(root,"files").apply {mkdirs()}
            override fun getCacheDir()=File(root,"cache").apply {mkdirs()}
            override fun getNoBackupFilesDir()=File(root,"private").apply {mkdirs()}
            override fun getExternalFilesDir(type:String?)=File(root,"external/"+type.orEmpty()).apply {mkdirs()}
            override fun getSharedPreferences(name:String,mode:Int)=super.getSharedPreferences("backup-regression-"+name,mode)
        }
        val p=Prefs.of(ctx)
        fun put(path:String,text:String){File(ctx.filesDir,path).apply {parentFile?.mkdirs();writeText(text)}}
        fun zip(entries:List<Pair<String,ByteArray>>):ByteArray=ByteArrayOutputStream().also {out->ZipOutputStream(out).use {z->for((name,data) in entries){z.putNextEntry(ZipEntry(name));z.write(data);z.closeEntry()}}}.toByteArray()
        fun entries(bytes:ByteArray):List<Pair<String,ByteArray>> {
            val values=ArrayList<Pair<String,ByteArray>>()
            ZipInputStream(ByteArrayInputStream(bytes)).use {z->while(true){val e=z.nextEntry ?: break;values.add(e.name to z.readBytes())}}
            return values
        }
        fun fails(f:()->Unit)=try {f();false}catch(_:Exception){true}
        try {
            p.edit().clear().putBoolean(Prefs.SOUND,true).putInt(V3Prefs.SOUND_VOLUME,37).putString("flick26_zh_q_down","text:☆").putString("flick26_zh_comma_down","text:!").putString("flick26_en_period_up","edit:undo").putString("api_key","must-not-export").putString(Prefs.CLIP_CATS,"工作").commit()
            put("user_lexicon.txt","P\t空叶\tkong'ye\t7\n")
            put("rime/user/sample.userdb/data","native-rime")
            put("fcitx-user/data/pinyin/user.dict","native-fcitx")
            put("typing-stats.json","{\"chars\":42,\"migrated\":true}")
            put("clipboard.json","[{\"t\":\"回归样本\",\"p\":true,\"ts\":1,\"img\":\"sample.png\",\"cat\":\"工作\"}]")
            put("clips/sample.png","image-regression-bytes")
            val output=ByteArrayOutputStream();KeyboardBackup.write(ctx,output,KeyboardBackup.Options(true,true,true),false);val backup=output.toByteArray()
            check(entries(backup).none {it.first.contains("api_key")})
            check(!entries(backup).first {it.first=="settings.json"}.second.decodeToString().contains("must-not-export"))
            p.edit().putBoolean(Prefs.SOUND,false).putInt(V3Prefs.SOUND_VOLUME,2).putString("api_key","keep-local-secret").commit()
            put("user_lexicon.txt","old");put("clips/obsolete.png","old")
            KeyboardBackup.restore(ctx,KeyboardBackup.read(ctx,ByteArrayInputStream(backup)),false)
            check(p.getBoolean(Prefs.SOUND,false)&&p.getInt(V3Prefs.SOUND_VOLUME,0)==37&&p.getString("api_key","")=="keep-local-secret")
            check(p.getString("flick26_zh_comma_down",null)=="text:!"&&p.getString("flick26_en_period_up",null)=="edit:undo")
            check(File(ctx.filesDir,"user_lexicon.txt").readText().contains("空叶"))
            check(File(ctx.filesDir,"rime/user/sample.userdb/data").readText()=="native-rime")
            check(File(ctx.filesDir,"fcitx-user/data/pinyin/user.dict").readText()=="native-fcitx")
            check(File(ctx.filesDir,"typing-stats.json").readText().contains("42"))
            check(File(ctx.filesDir,"clips/sample.png").readText()=="image-regression-bytes"&&!File(ctx.filesDir,"clips/obsolete.png").exists())
            val corrupt=zip(entries(backup).map {if(it.first=="user_lexicon.txt"||it.first=="learning/portable.txt")it.first to "changed".toByteArray()else it})
            check(fails {KeyboardBackup.read(ctx,ByteArrayInputStream(corrupt)).close()})
            check(fails {KeyboardBackup.read(ctx,ByteArrayInputStream(zip(listOf("../outside" to "x".toByteArray())))).close()})
            check(!File(root,"outside").exists())
            val prepared=KeyboardBackup.read(ctx,ByteArrayInputStream(backup))
            p.edit().putInt(V3Prefs.SOUND_VOLUME,81).commit();put("user_lexicon.txt","before-failure")
            val first=java.util.concurrent.atomic.AtomicBoolean(true)
            val failure=object:ContextWrapper(ctx) {
                override fun getSharedPreferences(name:String,mode:Int):SharedPreferences {
                    val delegate=ctx.getSharedPreferences(name,mode)
                    return object:SharedPreferences by delegate {
                        override fun edit():SharedPreferences.Editor {
                            val editor=delegate.edit()
                            return object:SharedPreferences.Editor by editor {
                                override fun commit():Boolean=if(first.compareAndSet(true,false))false else editor.commit()
                            }
                        }
                    }
                }
            }
            check(fails {KeyboardBackup.restore(failure,prepared,false)})
            check(p.getInt(V3Prefs.SOUND_VOLUME,0)==81&&File(ctx.filesDir,"user_lexicon.txt").readText()=="before-failure")
            val stateField=ResourcePacks::class.java.getDeclaredField("states").apply {isAccessible=true}
            @Suppress("UNCHECKED_CAST") val states=stateField.get(null) as MutableMap<String,ResourcePacks.State>
            val oldState=states["handwriting"]
            val pack=ResourcePacks.pack(ctx,"handwriting")
            for(part in pack.files){val f=ResourcePacks.file(ctx,part);f.parentFile?.mkdirs();f.writeText("owned model");File(f.parentFile,f.name+".part").writeText("partial");File(f.parentFile,f.name+".sha256").writeText("hash")}
            put("base-dictionary.txt","keep");put("user_lexicon.txt","keep")
            val latch=java.util.concurrent.CountDownLatch(1);var success=false
            ResourcePacks.remove(ctx,"handwriting") {ok,_->success=ok;latch.countDown()}
            check(latch.await(30,java.util.concurrent.TimeUnit.SECONDS)&&success)
            check(pack.files.none {ResourcePacks.file(ctx,it).exists()})
            if(oldState==null)states.remove("handwriting")else states["handwriting"]=oldState
            check(File(ctx.filesDir,"base-dictionary.txt").readText()=="keep"&&File(ctx.filesDir,"user_lexicon.txt").readText()=="keep")
            // Exercise the real engine pause/restart and actual data serialization; do not restore personal data.
            PinyinEngine.initialize(original)
            val live=File(original.cacheDir,"live-backup-regression.zip")
            try {
                live.outputStream().use {KeyboardBackup.write(original,it,KeyboardBackup.Options(true,true,false))}
                live.inputStream().use {KeyboardBackup.read(original,it)}.use {check(it.options.learning&&it.options.statistics&&!it.options.clipboard)}
                check(PinyinEngine.ready&&FcitxEngine.ready)
            } finally {live.delete()}
            i.sendStatus(0,Bundle().apply {putString("stream","BACKUP / RESOURCE DELETE PASSED: complete isolated round trip, settings/custom flicks/native learning/statistics/clipboard, secret exclusion, corrupt archive/path rejection, rollback after failed commit, optional resource deletion preserves base data, real engine snapshot/restart without restoring personal data.\n")})
        } finally {p.edit().clear().commit();root.deleteRecursively()}
    }
}
