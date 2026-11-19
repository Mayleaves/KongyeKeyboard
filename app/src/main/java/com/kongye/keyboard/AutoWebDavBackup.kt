package com.kongye.keyboard

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.os.Handler
import android.os.Looper
import java.util.concurrent.atomic.AtomicBoolean

/** Opt-in, persistent background uploads. Connection secrets remain in WebDavBackup. */
object AutoWebDavBackup {
    const val FREQUENCY="backup_webdav_frequency_days"
    internal const val JOB_ID=7421
    private const val LEARNING="backup_include_learning"
    private const val STATISTICS="backup_include_statistics"
    private const val CLIPBOARD="backup_include_clipboard"
    private const val ATTEMPT="backup_auto_attempt"
    private const val SUCCESS="backup_auto_success"
    private const val ERROR="backup_auto_error"
    fun frequency(ctx:Context)=Prefs.of(ctx).getInt(FREQUENCY,0).takeIf {it in setOf(0,1,7,30)} ?: 0
    fun options(ctx:Context)=Prefs.of(ctx).let {KeyboardBackup.Options(it.getBoolean(LEARNING,true),it.getBoolean(STATISTICS,true),it.getBoolean(CLIPBOARD,false))}
    fun setOptions(ctx:Context,value:KeyboardBackup.Options) {
        Prefs.of(ctx).edit().putBoolean(LEARNING,value.learning).putBoolean(STATISTICS,value.statistics).putBoolean(CLIPBOARD,value.clipboard).apply()
    }
    internal fun job(ctx:Context,days:Int):JobInfo {
        require(days in setOf(1,7,30))
        val interval=java.util.concurrent.TimeUnit.DAYS.toMillis(days.toLong())
        return JobInfo.Builder(JOB_ID,ComponentName(ctx,WebDavBackupJob::class.java))
            .setPeriodic(interval,minOf(java.util.concurrent.TimeUnit.HOURS.toMillis(3),interval/10))
            .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY).setRequiresDeviceIdle(true)
            .setRequiresBatteryNotLow(true).setPersisted(true).build()
    }
    @Synchronized fun sync(ctx:Context) {
        val scheduler=ctx.getSystemService(JobScheduler::class.java)
        val days=frequency(ctx)
        if(days==0) {scheduler.cancel(JOB_ID);return}
        val config=WebDavBackup.read(ctx)
        require(config.address.isNotBlank()) {"请先保存 WebDAV 服务器配置，再开启自动备份。"}
        WebDavBackup.endpoint(config)
        val wanted=job(ctx,days)
        val current=scheduler.getPendingJob(JOB_ID)
        if(current?.intervalMillis==wanted.intervalMillis && current.service==wanted.service && current.isPersisted && current.isRequireDeviceIdle && current.networkType==wanted.networkType)return
        check(scheduler.schedule(wanted)==JobScheduler.RESULT_SUCCESS) {"系统暂时无法安排自动备份，请稍后重试。"}
    }
    @Synchronized fun disable(ctx:Context) {
        Prefs.of(ctx).edit().putInt(FREQUENCY,0).commit()
        ctx.getSystemService(JobScheduler::class.java).cancel(JOB_ID)
    }
    internal fun runOnce(ctx:Context,transfer:WebDavBackup.Transfer,live:Boolean=true):String? {
        if(frequency(ctx)==0)return null
        transfer.checkActive()
        val config=WebDavBackup.read(ctx)
        require(config.address.isNotBlank()) {"请重新保存 WebDAV 服务器配置。"}
        return WebDavBackup.upload(ctx,config,options(ctx),live,transfer)
    }
    internal fun record(ctx:Context,result:Result<String>) {
        val now=System.currentTimeMillis()
        val edit=Prefs.of(ctx).edit().putLong(ATTEMPT,now)
        result.onSuccess {edit.putLong(SUCCESS,now).remove(ERROR)}
            .onFailure {edit.putString(ERROR,it.message?.take(240) ?: "自动备份失败，联网后会重试。")}
        edit.apply()
    }
    fun lastResult(ctx:Context):Pair<String,String?> {
        val p=Prefs.of(ctx)
        val success=p.getLong(SUCCESS,0)
        val stamp=if(success>0)java.text.SimpleDateFormat("MM-dd HH:mm",java.util.Locale.getDefault()).format(java.util.Date(success)) else null
        val title=if(stamp==null)"最近自动备份：尚未执行" else "最近自动备份成功："+stamp
        return title to p.getString(ERROR,null)
    }
}

class WebDavBackupJob:JobService() {
    private class Run {
        val stopped=AtomicBoolean(false)
        val transfer=WebDavBackup.Transfer()
        var thread:Thread?=null
        fun stop() {stopped.set(true);transfer.cancel();thread?.interrupt()}
    }
    private var active:Run?=null
    override fun onStartJob(params:JobParameters):Boolean {
        if(AutoWebDavBackup.frequency(this)==0)return false
        val run=Run();active=run
        run.thread=Thread({
            val result=runCatching {AutoWebDavBackup.runOnce(applicationContext,run.transfer)}
            Handler(Looper.getMainLooper()).post {
                if(active!==run || run.stopped.get())return@post
                active=null
                if(result.getOrNull()!=null || result.isFailure)AutoWebDavBackup.record(applicationContext,result.map {it.orEmpty()})
                jobFinished(params,result.isFailure && AutoWebDavBackup.frequency(this)>0)
            }
        },"Kongye automatic WebDAV backup").also {it.start()}
        return true
    }
    override fun onStopJob(params:JobParameters):Boolean {
        active?.stop();active=null
        return AutoWebDavBackup.frequency(this)>0
    }
    override fun onDestroy() {active?.stop();active=null;super.onDestroy()}
}
