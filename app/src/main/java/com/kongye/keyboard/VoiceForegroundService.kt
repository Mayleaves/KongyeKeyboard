package com.kongye.keyboard

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder

/** The microphone session enters foreground before acquiring focus on Android 15+. */
object VoiceSupport {
    private var generation=0
    private var ready:((Boolean)->Unit)?=null
    private var stopped:(()->Unit)?=null
    fun start(ctx:Context,onStop:()->Unit,onReady:(Boolean)->Unit) {
        val token=++generation
        ready=onReady;stopped=onStop
        try {ctx.startForegroundService(Intent(ctx,VoiceForegroundService::class.java).putExtra("token",token))}
        catch(error:RuntimeException) {
            android.util.Log.w("KongyeVoice","Unable to start foreground microphone session",error)
            ready=null;stopped=null;onReady(false)
        }
    }
    fun current(token:Int)=token==generation && stopped!=null
    fun started(token:Int,success:Boolean) {
        if(token!=generation)return
        val callback=ready;ready=null
        if(!success)stopped=null
        callback?.invoke(success)
    }
    fun stop(ctx:Context) {
        generation++;ready=null;stopped=null
        ctx.stopService(Intent(ctx,VoiceForegroundService::class.java))
    }
    fun userStop(token:Int?=null) {
        if(token!=null && token!=generation)return
        val callback=stopped
        generation++;ready=null;stopped=null
        callback?.invoke()
    }
}

class VoiceForegroundService:Service() {
    private var media:VoiceMedia?=null
    private var sessionToken=-1
    override fun onBind(intent:Intent?):IBinder?=null
    override fun onStartCommand(intent:Intent?,flags:Int,startId:Int):Int {
        if(intent?.action=="stop") {VoiceSupport.userStop();stopSelf();return START_NOT_STICKY}
        val token=intent?.getIntExtra("token",-1) ?: -1
        sessionToken=token
        try {
            val manager=getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(NotificationChannel("voice_session","语音输入",NotificationManager.IMPORTANCE_LOW).apply {
                description="显示正在进行的语音输入，可随时停止";setSound(null,null)
            })
            val stop=PendingIntent.getService(this,0,Intent(this,VoiceForegroundService::class.java).setAction("stop"),PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            val notification=Notification.Builder(this,"voice_session").setSmallIcon(R.drawable.ic_mic)
                .setContentTitle("空叶键盘正在听写").setContentText("可在听写条暂停，或点停止结束语音")
                .setOngoing(true).setOnlyAlertOnce(true)
                .addAction(Notification.Action.Builder(null,"停止",stop).build()).build()
            if(Build.VERSION.SDK_INT>=29)startForeground(300,notification,ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
            else startForeground(300,notification)
            if(!VoiceSupport.current(token)) {stopSelf();return START_NOT_STICKY}
            media?.release();media=VoiceMedia(this).also {it.acquire()}
            VoiceSupport.started(token,true)
        } catch(error:RuntimeException) {
            android.util.Log.e("KongyeVoice","Foreground voice support failed",error)
            VoiceSupport.started(token,false);stopSelf()
        }
        return START_NOT_STICKY
    }
    override fun onDestroy() {
        media?.release();media=null
        stopForeground(STOP_FOREGROUND_REMOVE)
        VoiceSupport.userStop(sessionToken)
        super.onDestroy()
    }
}
