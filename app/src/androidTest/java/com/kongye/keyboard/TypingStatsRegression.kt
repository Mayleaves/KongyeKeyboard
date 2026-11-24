package com.kongye.keyboard

import android.app.Instrumentation
import android.os.Bundle
import java.io.File
import java.util.Calendar

object TypingStatsRegression {
    fun run(inst:Instrumentation) {
        val ctx=inst.targetContext
        fun report(text:String)=inst.sendStatus(0,Bundle().apply {putString("stream",text+"\n")})
        val file=File(ctx.cacheDir,"typing-stats-isolated-qa.json");file.delete()
        val at=Calendar.getInstance().apply {set(2026,10,16,16,0,0);set(Calendar.MILLISECOND,0)}.timeInMillis
        val store=TypingStatsStore(file,7,at)
        try {
            check(store.snapshot(at).today==7L)
            for(i in 0..5)store.key("zh26",'n',i==5,at+i*1000,1000L+i*1000)
            store.commit("zh26",10,at+6000,7000)
            store.commit("voice",5,at+7000,8000);store.commit("paste",1000,at+8000,9000)
            val kinds=listOf("zhng" to 1,"zhongg" to 2,"zhnog" to 3,"zhobg" to 0)
            for((index,entry) in kinds.withIndex()) {
                val edits=SpellingAssist.edits(entry.first,"zhong");check(edits.size==1 && edits.single().kind==entry.second) {"Unexpected correction alignment ${entry.first}: $edits"}
                store.choice("zh26",if(index==0)14 else 4,if(index==0)10 else 1,index<3,edits)
            }
            check(SpellingAssist.edits("zg","zhong guo").isEmpty()) {"Abbreviations counted as mistakes"}
            store.association(3)
            var s=store.snapshot(at+9000)
            check(s.total==1022L && s.today==1022L && s.pasted==1000L && s.association==3L)
            check(s.keys==6L&&s.deletes==1L&&s.speed==100&&s.peakSpeed==100L)
            check(s.activeMs==6000L && s.codeLength==2.0 && s.firstRate==75)
            check(s.automatic==3L&&s.errors.all {it==1L})
            check(s.hourly[16]==1015L) {"Legacy time buckets should not be fabricated"}
            check(s.methods.first {it.id=="voice"}.speed==null)
            check(s.days(false,at).size==30 && s.days(true,at).size==365)
            store.commit("zh14",4,at+86400000,86407000)
            s=store.snapshot(at+86400000)
            check(s.streak==2 && s.today==4L && s.speed==null)
            store.persist()
            val serialized=file.readText();check(!serialized.contains("zhng")&&!serialized.contains("zhongg"))
            val restored=TypingStatsStore(file,999,at+86400000)
            try {check(restored.snapshot(at+86400000).total==1026L)}finally {restored.close()}
            val text=StatsReport.text(s)
            for(label in listOf("每日字数","时段分布","平均码长","首选率","自动纠错","删除键占比","800字作文"))check(text.contains(label))
            val image=StatsReport.image(ctx,s)
            val options=android.graphics.BitmapFactory.Options().apply {inJustDecodeBounds=true}
            android.graphics.BitmapFactory.decodeFile(image.absolutePath,options)
            check(options.outWidth==1080 && options.outHeight>=4000)
            android.graphics.BitmapFactory.decodeFile(image.absolutePath).also { bitmap ->
                fun alpha(x:Int,y:Int)=android.graphics.Color.alpha(bitmap.getPixel(x,y))
                check(alpha(2,3)<20 && alpha(15,7)>230) {"Receipt top edge has no visible teeth"}
                check(alpha(2,bitmap.height-3)<20 && alpha(15,bitmap.height-7)>230) {"Receipt bottom edge has no visible teeth"}
                bitmap.recycle()
            }
            val uri=android.net.Uri.parse("content://com.kongye.keyboard.files/stats/"+image.name)
            check(uri.scheme=="content");ctx.contentResolver.openInputStream(uri)!!.use {check(it.read()==137)}
            image.copyTo(File(ctx.getExternalFilesDir(null),"qa-statistics-export.png"),overwrite=true)
            image.delete()
            report("Real counters: migration, day/hour buckets, continuous speed, fastest speed, streak, input methods, correction classes and no raw-word storage passed")
            report("Full statistics text and PNG export, FileProvider access and image dimensions passed")
            store.clear(at);check(store.snapshot(at).total==0L)
        } finally {store.close();file.delete()}
        val longFile=File(ctx.cacheDir,"typing-stats-long-qa.json");longFile.delete()
        val long=TypingStatsStore(longFile,0,at)
        try {
            for(day in 0..405)long.commit("zh26",1,at+day*86400000L,1+day*86400000L)
            val s=long.snapshot(at+405*86400000L)
            check(s.streak==406 && s.total==406L && s.daily.size<=401)
            report("Long-running streak survives chart-history pruning passed")
        } finally {long.close();longFile.delete()}
        report("ALL REAL STATISTICS AND EXPORT CHECKS PASSED")
    }
}
