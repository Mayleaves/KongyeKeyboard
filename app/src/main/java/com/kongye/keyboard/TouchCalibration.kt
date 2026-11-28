package com.kongye.keyboard

import android.content.Context
import kotlin.math.pow

/** Only explicitly labelled calibration taps train this model; normal typing never does. */
@androidx.annotation.Keep
class TouchCalibration(context:Context) {
    private val prefs=Prefs.of(context)
    @androidx.annotation.Keep
    data class Bias(val x:Float,val y:Float,val count:Float)
    fun bias(profile:String,key:String,now:Long=System.currentTimeMillis()):Bias {
        if(!prefs.getBoolean(ENABLED,false))return Bias(0f,0f,0f)
        return stored(profile,key,now)
    }
    private fun stored(profile:String,key:String,now:Long):Bias {
        val prefix="$PREFIX$profile.$key."
        val age=((now-prefs.getLong(prefix+"time",now)).coerceAtLeast(0)/86400000.0)
        val decay=.5.pow(age/30).toFloat()
        val n=prefs.getFloat(prefix+"count",0f)*decay
        val confidence=(n/6).coerceIn(0f,1f)
        return Bias(prefs.getFloat(prefix+"x",0f)*confidence,prefs.getFloat(prefix+"y",0f)*confidence,n)
    }
    fun record(profile:String,key:String,x:Float,y:Float,now:Long=System.currentTimeMillis()) {
        if(key.length!=1 || key[0] !in 'a'..'z' || !x.isFinite() || !y.isFinite() || kotlin.math.abs(x)>.45f || kotlin.math.abs(y)>.45f)return
        val prefix="$PREFIX$profile.$key."
        val old=stored(profile,key,now);val n=old.count.coerceAtMost(24f)
        // Stored means are independent of the confidence applied when reading.
        val mx=prefs.getFloat(prefix+"x",0f);val my=prefs.getFloat(prefix+"y",0f)
        prefs.edit().putFloat(prefix+"x",((mx*n+x)/(n+1)).coerceIn(-.18f,.18f))
            .putFloat(prefix+"y",((my*n+y)/(n+1)).coerceIn(-.18f,.18f))
            .putFloat(prefix+"count",n+1).putLong(prefix+"time",now).apply()
    }
    fun clear() {prefs.edit().also {edit->prefs.all.keys.filter {it.startsWith(PREFIX)}.forEach(edit::remove)}.apply()}
    companion object {
        const val ENABLED="touch_calibration_enabled"
        const val CONTEXT="touch_context_enabled"
        const val PREFIX="touch_calibration."
    }
}
