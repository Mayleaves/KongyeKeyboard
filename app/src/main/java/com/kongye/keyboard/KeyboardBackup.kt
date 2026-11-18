package com.kongye.keyboard

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.util.concurrent.locks.ReentrantLock
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/** Portable, bounded backup. Only declared user-data paths can be restored. */
object KeyboardBackup {
    data class Options(val learning:Boolean=true,val statistics:Boolean=true,val clipboard:Boolean=false)
    class Prepared(val folder:File,val manifest:JSONObject):java.io.Closeable {
        val options get()=manifest.getJSONObject("includes").let {Options(it.getBoolean("learning"),it.getBoolean("statistics"),it.getBoolean("clipboard"))}
        val created get()=manifest.getLong("created")
        val version get()=manifest.getString("version")
        override fun close(){deleteTree(folder)}
    }
    private val lock=ReentrantLock()
    private const val LIMIT=256L*1024*1024
    private const val FILE_LIMIT=64L*1024*1024
    private val excluded=setOf(Prefs.UPDATE_CHECKED,"latest_update_tag","notification_requested",Prefs.COUNT_DATE,Prefs.COUNT_TODAY)
    private fun exportable(key:String,clipboard:Boolean)=key !in excluded&&!key.contains("secret",true)&&!key.contains("token",true)&&!key.contains("api_key",true)&&!key.startsWith("backup_")&&(clipboard||key!=Prefs.CLIP_CATS)
    private fun deleteTree(file:File) {
        if(!file.exists())return
        java.nio.file.Files.walk(file.toPath()).use {paths->paths.sorted(java.util.Comparator.reverseOrder()).forEach {java.nio.file.Files.deleteIfExists(it)}}
    }
    private fun temp(ctx:Context)=File(ctx.cacheDir,"keyboard-backups").apply {mkdirs()}.let {File.createTempFile("stage-","",it).apply {check(delete());check(mkdir())}}
    private fun sha(f:File):String=f.inputStream().use {input->val d=MessageDigest.getInstance("SHA-256");val b=ByteArray(65536);while(true){val n=input.read(b);if(n<0)break;d.update(b,0,n)};d.digest().joinToString(""){"%02x".format(it)}}
    private fun validName(name:String):Boolean {
        if(name.isEmpty()||name.length>512||name.startsWith("/")||'\\' in name||':' in name||'\u0000' in name)return false
        if(name.split('/').any {it.isEmpty()||it=="."||it==".."})return false
        return name in setOf("settings.json","learning/portable.txt","stats.json","clipboard.json")||
            name.startsWith("learning/rime/")||name.startsWith("learning/fcitx/")||name.startsWith("clips/")&&name.count {it=='/'}==1
    }
    private fun prefsJson(values:Map<String,*>,filter:(String)->Boolean):JSONObject {
        val out=JSONObject()
        for((key,value) in values)if(filter(key)) {
            val type=when(value){is Boolean->"b";is Int->"i";is Long->"l";is Float->"f";is String->"s";is Set<*>->"a";else->continue}
            out.put(key,JSONObject().put("type",type).put("value",if(value is Set<*>)JSONArray(value.toList())else value))
        }
        return out
    }
    private fun applyPrefs(ctx:Context,data:JSONObject,replace:Boolean,clipboard:Boolean,all:Boolean=false) {
        require(data.length()<=5000) {"备份设置数量异常"}
        val edit=Prefs.of(ctx).edit()
        if(replace)Prefs.of(ctx).all.keys.filter {all||exportable(it,clipboard)}.forEach {edit.remove(it)}
        for(key in data.keys()) {
            require(key.length<=200) {"备份设置名称异常"}
            if(!all&&!exportable(key,clipboard))continue
            val item=data.getJSONObject(key)
            when(item.getString("type")) {
                "b"->edit.putBoolean(key,item.getBoolean("value"))
                "i"->edit.putInt(key,item.getInt("value"))
                "l"->edit.putLong(key,item.getLong("value"))
                "f"->item.getDouble("value").toFloat().also {require(it.isFinite());edit.putFloat(key,it)}
                "s"->item.getString("value").also {require(it.length<=100000);edit.putString(key,it)}
                "a"->item.getJSONArray("value").also {a->require(a.length()<=5000);edit.putStringSet(key,(0 until a.length()).map {a.getString(it).also {v->require(v.length<=10000)}}.toSet())}
                else->error("不支持的备份设置类型")
            }
        }
        check(edit.commit()) {"无法保存恢复的设置"}
    }
    private fun copyTree(source:File,target:File) {
        if(!source.exists())return
        if(source.isDirectory) {
            target.mkdirs();val root=source.canonicalPath+File.separator
            source.walkTopDown().onEnter {it==source||!java.nio.file.Files.isSymbolicLink(it.toPath())&&!(source.invariantSeparatorsPath.endsWith("/rime/user")&&it.name=="build")}.filter {it.isFile&&!java.nio.file.Files.isSymbolicLink(it.toPath())}.forEach {f->
                check(f.canonicalPath.startsWith(root));val name=f.relativeTo(source).path
                if(f.name in setOf("LOCK",".lock"))return@forEach
                val dest=File(target,name);dest.parentFile?.mkdirs();f.copyTo(dest,true)
            }
        } else {target.parentFile?.mkdirs();source.copyTo(target,true)}
    }
    fun write(ctx:Context,out:OutputStream,options:Options,live:Boolean=true) {
        check(lock.tryLock()) {"已有备份或恢复操作正在进行"}
        val stage=temp(ctx)
        try {
            File(stage,"settings.json").writeText(prefsJson(Prefs.of(ctx).all){exportable(it,options.clipboard)}.toString())
            if(options.learning) {
                val capture={
                    File(stage,"learning").mkdirs()
                    File(stage,"learning/portable.txt").writeText(if(live){Lexicon.load(ctx);Lexicon.backupText()}else File(ctx.filesDir,"user_lexicon.txt").takeIf {it.isFile}?.readText().orEmpty())
                    copyTree(File(ctx.filesDir,"rime/user"),File(stage,"learning/rime"))
                    copyTree(File(ctx.filesDir,"fcitx-user"),File(stage,"learning/fcitx"))
                }
                if(live)PinyinEngine.withUserFiles(ctx,capture)else capture()
            }
            if(options.statistics) {
                if(live)TypingStats.of(ctx).persist()
                copyTree(File(ctx.filesDir,"typing-stats.json"),File(stage,"stats.json"))
            }
            if(options.clipboard) {
                val capture={copyTree(File(ctx.filesDir,"clipboard.json"),File(stage,"clipboard.json"));copyTree(File(ctx.filesDir,"clips"),File(stage,"clips"))}
                if(live)ClipStore.withFiles(ctx,false,capture)else capture()
                if(!File(stage,"clipboard.json").exists())File(stage,"clipboard.json").writeText("[]")
            }
            val files=stage.walkTopDown().filter {it.isFile}.toList();require(files.size<=8000)
            var total=0L;val entries=JSONObject()
            for(f in files){require(f.length()<=FILE_LIMIT);total+=f.length();require(total<=LIMIT){"备份超过 256 MB，请减少剪贴板图片后重试"};entries.put(f.relativeTo(stage).invariantSeparatorsPath,JSONObject().put("bytes",f.length()).put("sha256",sha(f)))}
            val manifest=JSONObject().put("format","kongye-keyboard-backup").put("schema",1).put("package",ctx.packageName).put("version",UpdateChecker.currentVersion(ctx)).put("created",System.currentTimeMillis()).put("includes",JSONObject().put("learning",options.learning).put("statistics",options.statistics).put("clipboard",options.clipboard)).put("files",entries)
            ZipOutputStream(out).use {zip->
                zip.putNextEntry(ZipEntry("manifest.json"));zip.write(manifest.toString().toByteArray());zip.closeEntry()
                for(f in files){zip.putNextEntry(ZipEntry(f.relativeTo(stage).invariantSeparatorsPath));f.inputStream().use {it.copyTo(zip)};zip.closeEntry()}
            }
        } finally {try {deleteTree(stage)} finally {lock.unlock()}}
    }
    fun read(ctx:Context,input:InputStream):Prepared {
        val stage=temp(ctx)
        try {
            var total=0L;var count=0;var manifest:JSONObject?=null;val names=HashSet<String>()
            ZipInputStream(input.buffered()).use {zip->
                while(true) {
                    val entry=zip.nextEntry ?: break
                    require(!entry.isDirectory&&names.add(entry.name)&&++count<=8001) {"备份含有重复或无效条目"}
                    require(entry.name=="manifest.json"||validName(entry.name)) {"备份含有不支持的路径"}
                    val f=File(stage,entry.name);f.parentFile?.mkdirs();var size=0L
                    f.outputStream().use {out->val b=ByteArray(65536);while(true){val n=zip.read(b);if(n<0)break;size+=n;total+=n;require(size<=if(entry.name=="manifest.json")2L*1024*1024 else FILE_LIMIT);require(total<=LIMIT);out.write(b,0,n)}}
                    if(entry.name=="manifest.json")manifest=JSONObject(f.readText())
                }
            }
            val m=manifest ?: error("缺少备份说明文件")
            require(m.optString("format")=="kongye-keyboard-backup"&&m.getInt("schema")==1&&m.getString("package")==ctx.packageName) {"不是兼容的空叶键盘备份"}
            val expected=m.getJSONObject("files");require(expected.has("settings.json"))
            require(names-setOf("manifest.json")==expected.keys().asSequence().toSet()) {"备份内容与清单不一致"}
            for(name in expected.keys()){val f=File(stage,name);val meta=expected.getJSONObject(name);require(f.length()==meta.getLong("bytes")&&sha(f)==meta.getString("sha256")){"备份损坏，请重新选择文件"}}
            val include=m.getJSONObject("includes")
            require(expected.keys().asSequence().all {name->
                name=="settings.json"||name.startsWith("learning/")&&include.getBoolean("learning")||name=="stats.json"&&include.getBoolean("statistics")||(name=="clipboard.json"||name.startsWith("clips/"))&&include.getBoolean("clipboard")
            })
            val data=JSONObject(File(stage,"settings.json").readText())
            // Check all preference value types before any user files are replaced.
            val scratch=object:android.content.ContextWrapper(ctx){override fun getSharedPreferences(name:String,mode:Int)=super.getSharedPreferences("backup-validate",mode)}
            try {applyPrefs(scratch,data,true,include.getBoolean("clipboard"))}finally{Prefs.of(scratch).edit().clear().commit()}
            if(include.getBoolean("statistics")&&File(stage,"stats.json").isFile)JSONObject(File(stage,"stats.json").readText())
            if(include.getBoolean("clipboard")) {
                val clips=JSONArray(File(stage,"clipboard.json").readText());require(clips.length()<=10000)
                for(i in 0 until clips.length()){val c=clips.getJSONObject(i);c.getString("t");val image=c.optString("img");require(image.isEmpty()||validName("clips/"+image)&&File(stage,"clips/"+image).isFile)}
            }
            return Prepared(stage,m)
        } catch(t:Throwable){deleteTree(stage);throw t}
    }
    fun restore(ctx:Context,prepared:Prepared,live:Boolean=true) {
        check(lock.tryLock()) {"已有备份或恢复操作正在进行"}
        val options=prepared.options;val rollback=temp(ctx);val moved=ArrayList<Pair<File,File?>>()
        val oldPrefs=prefsJson(Prefs.of(ctx).all){true}
        val oldWords=if(live&&options.learning){Lexicon.load(ctx);Lexicon.backupText()}else ""
        var committed=false;var recovered=false
        fun guarded(action:()->Unit) {
            val words={if(live&&options.learning)PinyinEngine.withUserFiles(ctx,action)else action()}
            val stats={if(live&&options.statistics)TypingStats.replaceFiles(words)else words()}
            if(live&&options.clipboard)ClipStore.withFiles(ctx,true,stats)else stats()
        }
        try {
            guarded {
                if(live&&options.learning)Lexicon.flushUserWrites()
                val targets=ArrayList<Triple<String,String,Boolean>>()
                if(options.learning){targets.add(Triple("learning/rime","rime/user",true));targets.add(Triple("learning/fcitx","fcitx-user",true));targets.add(Triple("learning/portable.txt","user_lexicon.txt",false))}
                if(options.statistics)targets.add(Triple("stats.json","typing-stats.json",false))
                if(options.clipboard){targets.add(Triple("clipboard.json","clipboard.json",false));targets.add(Triple("clips","clips",true))}
                for((sourceName,targetName,directory) in targets) {
                    val target=File(ctx.filesDir,targetName);val saved=File(rollback,targetName.replace('/','_'))
                    target.parentFile?.mkdirs()
                    if(target.exists())check(target.renameTo(saved)) {"无法准备恢复文件"}
                    moved.add(target to saved.takeIf {it.exists()})
                    val source=File(prepared.folder,sourceName)
                    if(source.exists())check(source.renameTo(target)){"无法恢复文件"}
                    else if(directory)check(target.mkdirs())
                }
                if(live&&options.learning)Lexicon.restoreBackupText(ctx,File(ctx.filesDir,"user_lexicon.txt").takeIf {it.isFile}?.readText().orEmpty())
                applyPrefs(ctx,JSONObject(File(prepared.folder,"settings.json").readText()),true,options.clipboard)
            }
            committed=true
        } catch(t:Throwable) {
            runCatching {guarded {
                for((target,saved) in moved.reversed()){if(target.isDirectory)deleteTree(target)else if(target.exists())check(target.delete());if(saved!=null)check(saved.renameTo(target))}
                applyPrefs(ctx,oldPrefs,true,true,true)
                if(live&&options.learning)Lexicon.restoreBackupText(ctx,oldWords)
            };if(live&&options.learning)PinyinEngine.initialize(ctx)}.onSuccess {recovered=true}.onFailure {t.addSuppressed(it)}
            throw t
        } finally {try {if(committed||recovered)deleteTree(rollback)} finally {try {prepared.close()} finally {lock.unlock()}}}
    }
}
