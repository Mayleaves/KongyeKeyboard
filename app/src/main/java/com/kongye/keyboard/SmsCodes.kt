package com.kongye.keyboard

import android.Manifest
import android.app.AlarmManager
import android.app.KeyguardManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PersistableBundle
import android.os.SystemClock
import android.os.UserManager
import android.provider.Telephony
import java.util.UUID

/** Only new SMS broadcasts are considered. No inbox, notification, or history access. */
object SmsCodes {
    private val marker=Regex("验证码|驗證碼|校验码|校驗碼|动态码|動態碼|安全码|登录码|verification\\s*code|security\\s*code|one[- ]?time(?: password| code)?|passcode|\\botp\\b",RegexOption.IGNORE_CASE)
    private val code=Regex("(?<![A-Za-z0-9])(?:[0-9]{4,8}|[A-Za-z0-9]{6,8})(?![A-Za-z0-9])")
    fun extract(body:String):String? {
        val labels=marker.findAll(body).toList();if(labels.isEmpty()) return null
        val matches=code.findAll(body).filter {m -> m.value.any {it.isDigit()} && m.value.length in 4..8}.mapNotNull {m ->
            val near=labels.minOf {k -> when {m.range.first>k.range.last -> m.range.first-k.range.last; k.range.first>m.range.last -> k.range.first-m.range.last;else->0}}
            if(near>36) null else m to near
        }.sortedBy {it.second}.toList()
        val first=matches.firstOrNull() ?: return null
        if(matches.drop(1).any {it.second==first.second && it.first.value!=first.first.value}) return null
        return first.first.value
    }
}

/** The only persisted state is an opaque marker and expiry, never the code. */
object SmsCodeClipboard {
    const val TTL=10*60_000L
    private const val MARKER="com.kongye.keyboard.SMS_CODE"
    private const val EXPIRY="com.kongye.keyboard.SMS_CODE_EXPIRY"
    private const val ACTION="com.kongye.keyboard.EXPIRE_SMS_CODE"
    private val main=Handler(Looper.getMainLooper())
    private var expiryTask:Runnable?=null
    private var pending:Triple<String,Long,Context>?=null
    private var unlockReceiver:BroadcastReceiver?=null
    private val discardPending=Runnable {clearPending()}
    private fun clearPending() {
        val ctx=pending?.third
        unlockReceiver?.let {receiver -> if(ctx!=null) runCatching {ctx.unregisterReceiver(receiver)}}
        unlockReceiver=null;pending=null;main.removeCallbacks(discardPending)
    }
    private fun afterUnlock(ctx:Context,value:String,received:Long) {
        clearPending()
        val app=ctx.applicationContext
        pending=Triple(value,received,app)
        val receiver=object:BroadcastReceiver() {
            override fun onReceive(c:Context,intent:Intent) {
                val item=pending ?: return
                if(intent.action!=Intent.ACTION_USER_PRESENT || c.getSystemService(KeyguardManager::class.java).isDeviceLocked) return
                clearPending()
                if(System.currentTimeMillis()<item.second+TTL) copy(item.third,item.first,item.second)
            }
        }
        unlockReceiver=receiver
        if(Build.VERSION.SDK_INT>=33) app.registerReceiver(receiver,IntentFilter(Intent.ACTION_USER_PRESENT),Context.RECEIVER_NOT_EXPORTED)
        else app.registerReceiver(receiver,IntentFilter(Intent.ACTION_USER_PRESENT))
        main.postDelayed(discardPending,(received+TTL-System.currentTimeMillis()).coerceAtLeast(0))
    }
    private fun prefs(ctx:Context)=ctx.getSharedPreferences("sms_code_expiry",Context.MODE_PRIVATE)
    fun ephemeral(clip:ClipData?)=clip?.description?.extras?.getString(MARKER)!=null
    fun copy(ctx:Context,value:String,now:Long=System.currentTimeMillis()):Boolean {
        if(!Prefs.of(ctx).getBoolean(Prefs.SMS_CODE,false)) return false
        if(ctx.getSystemService(KeyguardManager::class.java)?.isDeviceLocked==true) {
            // Keep the lock-screen guard. An unexpired code is copied only after unlock.
            afterUnlock(ctx,value,now);return false
        }
        if(now+TTL<=System.currentTimeMillis()) return false
        clearPending()
        val id=UUID.randomUUID().toString();val expires=now+TTL
        val clip=ClipData.newPlainText("验证码",value).apply {
            description.extras=PersistableBundle().apply {
                putBoolean("android.content.extra.IS_SENSITIVE",true);putString(MARKER,id);putLong(EXPIRY,expires)
            }
        }
        return runCatching {
            prefs(ctx).edit().putString("id",id).putLong("expires",expires).commit()
            ctx.getSystemService(ClipboardManager::class.java).setPrimaryClip(clip)
            schedule(ctx,expires);true
        }.getOrDefault(false)
    }
    fun schedule(ctx:Context,expires:Long=prefs(ctx).getLong("expires",0)) {
        if(expires==0L) return
        val app=ctx.applicationContext
        expiryTask?.let {main.removeCallbacks(it)}
        val remaining=(expires-System.currentTimeMillis()).coerceAtLeast(0)
        expiryTask=Runnable {expire(app)}.also {main.postDelayed(it,remaining)}
        val intent=Intent(app,SmsCodeExpiryReceiver::class.java).setAction(ACTION)
        val pi=PendingIntent.getBroadcast(app,704,intent,PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        app.getSystemService(AlarmManager::class.java).setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP,SystemClock.elapsedRealtime()+remaining,pi)
        if(remaining==0L) expire(app)
    }
    fun expire(ctx:Context,now:Long=System.currentTimeMillis()) {
        val p=prefs(ctx);val expected=p.getString("id",null) ?: return
        if(now<p.getLong("expires",Long.MAX_VALUE)) {schedule(ctx);return}
        val cm=ctx.getSystemService(ClipboardManager::class.java)
        runCatching {
            if(cm.primaryClip?.description?.extras?.getString(MARKER)==expected) {
                if(Build.VERSION.SDK_INT>=28) cm.clearPrimaryClip() else cm.setPrimaryClip(ClipData.newPlainText("",""))
            }
            p.edit().clear().apply()
            expiryTask?.let {main.removeCallbacks(it)};expiryTask=null
        }
    }
}

class SmsCodeReceiver:BroadcastReceiver() {
    override fun onReceive(ctx:Context,intent:Intent) {
        if(intent.action!=Telephony.Sms.Intents.SMS_RECEIVED_ACTION || !Prefs.of(ctx).getBoolean(Prefs.SMS_CODE,false) ||
            ctx.checkSelfPermission(Manifest.permission.RECEIVE_SMS)!=PackageManager.PERMISSION_GRANTED ||
            !ctx.getSystemService(UserManager::class.java).isUserUnlocked) return
        val messages=runCatching {Telephony.Sms.Intents.getMessagesFromIntent(intent)}.getOrNull() ?: return
        // Multipart messages are joined only within the same sender.
        for(group in messages.groupBy {it.originatingAddress}.values) {
            val body=group.joinToString("") {it.messageBody.orEmpty()}
            val value=SmsCodes.extract(body) ?: continue
            SmsCodeClipboard.copy(ctx,value)
            break
        }
    }
}
class SmsCodeExpiryReceiver:BroadcastReceiver() {
    override fun onReceive(ctx:Context,intent:Intent) {SmsCodeClipboard.expire(ctx)}
}
