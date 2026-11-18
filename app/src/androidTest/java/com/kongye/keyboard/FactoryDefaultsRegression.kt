package com.kongye.keyboard

import android.app.Instrumentation
import android.content.Context
import android.os.Bundle

object FactoryDefaultsRegression {
    fun run(i:Instrumentation) {
        val ctx=i.targetContext
        val fresh=ctx.getSharedPreferences("factory-profile-test-new",Context.MODE_PRIVATE)
        val existing=ctx.getSharedPreferences("factory-profile-test-existing",Context.MODE_PRIVATE)
        val deviceBefore=Prefs.of(ctx).all.toMap()
        val deviceValues=deviceBefore+mapOf(
            Prefs.NUM_SIDE_KEY to Prefs.numSide(ctx).joinToString(" "),
            Prefs.NUM_KEYS to Prefs.numKeys(ctx).joinToString("\u0001"),
            Prefs.TOOLS to Prefs.tools(ctx).joinToString(","),
            Prefs.MENU to Prefs.menu(ctx).joinToString(","))
        fresh.edit().clear().commit();existing.edit().clear().commit()
        try {
            val profile=InstallDefaults.profile(ctx)
            check(profile.length()==89)
            check(InstallDefaults.seed(ctx,fresh))
            for(key in profile.keys()) {
                val item=profile.getJSONObject(key)
                val expected=when(item.getString("type")) {
                    "b"->item.getBoolean("value")
                    "i"->item.getInt("value")
                    "l"->item.getLong("value")
                    "f"->item.getDouble("value").toFloat()
                    "s"->item.getString("value")
                    else->error("Unexpected type")
                }
                check(fresh.all[key]==expected) {"Factory setting mismatch: $key"}
                check(deviceValues[key]==expected) {"Current Sony setting differs: $key"}
            }
            fresh.edit().putFloat(Prefs.HEIGHT_SCALE,1.25f).commit()
            check(!InstallDefaults.seed(ctx,fresh))
            check(fresh.getFloat(Prefs.HEIGHT_SCALE,0f)==1.25f)
            existing.edit().putString(Prefs.THEME,"existing-theme").putBoolean("existing",true).commit()
            check(!InstallDefaults.seed(ctx,existing))
            check(existing.getString(Prefs.THEME,"")=="existing-theme")
            check(!existing.contains(Prefs.NUM_SIDE_KEY))
            check(Prefs.of(ctx).all==deviceBefore)
            i.sendStatus(0,Bundle().apply {putString("stream","FACTORY DEFAULTS PASSED: all 89 new-install values match Sony; existing settings and subsequent edits stay intact; phone configuration unchanged.\n")})
        } finally {
            val deviceValues=deviceBefore+mapOf(
            Prefs.NUM_SIDE_KEY to Prefs.numSide(ctx).joinToString(" "),
            Prefs.NUM_KEYS to Prefs.numKeys(ctx).joinToString("\u0001"),
            Prefs.TOOLS to Prefs.tools(ctx).joinToString(","),
            Prefs.MENU to Prefs.menu(ctx).joinToString(","))
        fresh.edit().clear().commit();existing.edit().clear().commit()
        }
    }
}
