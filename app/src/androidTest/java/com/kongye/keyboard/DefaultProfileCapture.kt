package com.kongye.keyboard

import android.app.Instrumentation
import android.os.Bundle
import org.json.JSONObject
import java.io.File
import java.lang.reflect.Modifier

/** Captures declared functional settings, excluding personal history and service credentials. */
object DefaultProfileCapture {
    fun run(i:Instrumentation) {
        val ctx=i.targetContext
        val excluded=setOf(Prefs.RECENT_EMOJI,Prefs.FAV_EMOJI,Prefs.RECENT_SYM,Prefs.CLIP_CATS,
            Prefs.COUNT_DATE,Prefs.COUNT_TODAY,Prefs.VAULT_PACKAGE,Prefs.UPDATE_CHECKED)
        val allowed=listOf(Prefs::class.java,V3Prefs::class.java).flatMap {type->
            type.declaredFields.filter {Modifier.isStatic(it.modifiers)&&it.type==String::class.java}.mapNotNull {
                runCatching {it.isAccessible=true;it.get(null) as String}.getOrNull()
            }
        }.toSet()+VoicePolish.MODE
        val values=Prefs.of(ctx).all.filterKeys {key->
            key !in excluded&&(key in allowed||KeyGestures.isPreferenceKey(key))
        }.toMutableMap()
        values[Prefs.NUM_SIDE_KEY]=Prefs.numSide(ctx).joinToString(" ")
        values[Prefs.NUM_KEYS]=Prefs.numKeys(ctx).joinToString("\u0001")
        values[Prefs.TOOLS]=Prefs.tools(ctx).joinToString(",")
        values[Prefs.MENU]=Prefs.menu(ctx).joinToString(",")
        val json=JSONObject()
        for((key,value) in values.toSortedMap()) {
            val type=when(value){is Boolean->"b";is Int->"i";is Long->"l";is Float->"f";is String->"s";else->error("Unexpected settings type")}
            json.put(key,JSONObject().put("type",type).put("value",value))
        }
        File(ctx.getExternalFilesDir(null),"sony-functional-defaults.json").writeText(json.toString(2))
        i.sendStatus(0,Bundle().apply {putString("stream","Captured ${json.length()} functional settings without changing the phone configuration.\n")})
    }
}
