package com.kongye.keyboard

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import org.json.JSONObject

/** The factory profile is applied only to a new, empty settings store. */
object InstallDefaults {
    private const val MARKER="factory_profile_applied"
    fun profile(ctx:Context)=JSONObject(ctx.assets.open("factory-settings.json").bufferedReader().use {it.readText()})

    @Synchronized fun seed(ctx:Context,p:SharedPreferences=ctx.getSharedPreferences("kongye",Context.MODE_PRIVATE)):Boolean {
        if(p.contains(MARKER))return false
        if(p.all.isNotEmpty()) {
            check(p.edit().putInt(MARKER,1).commit())
            return false
        }
        val edit=p.edit()
        writeProfile(profile(ctx),edit)
        check(edit.putInt(MARKER,1).commit())
        return true
    }

    private fun writeProfile(data:JSONObject,edit:SharedPreferences.Editor) {
        for(key in data.keys()) {
            val item=data.getJSONObject(key)
            when(item.getString("type")) {
                "b"->edit.putBoolean(key,item.getBoolean("value"))
                "i"->edit.putInt(key,item.getInt("value"))
                "l"->edit.putLong(key,item.getLong("value"))
                "f"->edit.putFloat(key,item.getDouble("value").toFloat())
                "s"->edit.putString(key,item.getString("value"))
                else->error("Unsupported factory setting type")
            }
        }
    }

    private val functionalKeys=setOf(
        Prefs.THEME,Prefs.MODE,Prefs.VIBRATE,Prefs.VIB_STRENGTH,
        Prefs.SOUND,Prefs.HEIGHT_SCALE,Prefs.ZH,Prefs.TOOLS,
        Prefs.MENU,Prefs.GLIDE,Prefs.SMS_CODE,Prefs.SWIPE,
        Prefs.ABBR,Prefs.SHOW_PINYIN,Prefs.ASSOC,Prefs.LEARN,
        Prefs.LAYOUT,Prefs.FLOAT,Prefs.SPLIT_LAYOUT,Prefs.FLOAT_X,
        Prefs.FLOAT_Y,Prefs.FLOAT_WIDTH,Prefs.NUM_SIDE_KEY,Prefs.NUM_KEYS,
        Prefs.OPACITY,Prefs.GLASS,Prefs.PAD_L,Prefs.PAD_R,
        Prefs.PAD_B,Prefs.FUZZY,Prefs.TRANS_TO,Prefs.VOICE_LANG,
        Prefs.VAULT,Prefs.VAULT_INLINE,Prefs.SPACE_LONG,Prefs.VOICE_OFFLINE,
        V3Prefs.STATS_ENABLED,V3Prefs.VIBRATION_LEVEL,V3Prefs.VIBRATION_RESUME_MODE,V3Prefs.VIBRATION,
        V3Prefs.SOUND_VOLUME,V3Prefs.CORRECTION_ENABLED,V3Prefs.FUZZY_ENABLED,V3Prefs.CORRECT_NEAR,
        V3Prefs.CORRECT_SWAP,V3Prefs.CORRECT_EXTRA,V3Prefs.CORRECT_MISSING,V3Prefs.MIXED,
        V3Prefs.PUNCT_CONFIRM,V3Prefs.PAIRS,V3Prefs.EN_SPACE,V3Prefs.PUNCT_SPACE,
        V3Prefs.EMAIL,V3Prefs.CAPS,V3Prefs.CAPS_LOCK,V3Prefs.EN_CORRECT,
        V3Prefs.SPACE_LATIN,V3Prefs.SPACE_DIGIT,V3Prefs.ASSOC_CONTINUOUS,V3Prefs.ASSOC_SPACE,
        V3Prefs.EMOJI,V3Prefs.SYMBOLS,V3Prefs.KAOMOJI,V3Prefs.HOMOPHONE,
        V3Prefs.GLIDE_ZH,V3Prefs.GLIDE_EN,V3Prefs.GLIDE_LIVE,V3Prefs.GLIDE_TRACE,
        V3Prefs.GLIDE_WORD_DELETE,V3Prefs.SWIPE_REVERSE,V3Prefs.LETTER_LONG,V3Prefs.BOTTOM_CURSOR,
        V3Prefs.DELETE_DIRECT,V3Prefs.COMPOSE_RESTORE,V3Prefs.COMPOSE_HOLD_CLEAR,V3Prefs.CLEAR_SCOPE,
        V3Prefs.DEL_UP,V3Prefs.DEL_DOWN,V3Prefs.DEL_LEFT,V3Prefs.DEL_RIGHT,
        V3Prefs.PINYIN_POSITION,V3Prefs.READINGS,V3Prefs.GLOSS,V3Prefs.FEEDBACK,
        V3Prefs.ORIENTATION_LAYOUT,V3Prefs.LAYOUT_LANDSCAPE,V3Prefs.ONE_HAND,V3Prefs.FILL_GAPS,
        V3Prefs.SPLIT_GAP,V3Prefs.SPLIT_SIZE,V3Prefs.CLIP_LIMIT,V3Prefs.CLIP_DAYS,
        V3Prefs.MEDIA,V3Prefs.DRIVE,V3Prefs.VOICE_PUNCT,V3Prefs.VOICE_NUMBERS,
        V3Prefs.VOICE_POLISH,V3Prefs.VOICE_CLEAN,V3Prefs.HAND_TONE,VoicePolish.MODE,
        VoicePolish.ENDPOINT,VoicePolish.MODEL,
    )

    /** Restores functional preferences and connection configuration; personal files stay intact. */
    @Synchronized fun reset(ctx:Context,p:SharedPreferences=ctx.getSharedPreferences("kongye",Context.MODE_PRIVATE)) {
        val data=profile(ctx)
        val edit=p.edit()
        p.all.keys.filter {it in functionalKeys||it in FloatingKeyboard.keys||it.matches(Regex("flick26_(zh|en)_[a-z]_(up|down)"))}.forEach {edit.remove(it)}
        writeProfile(data,edit)
        check(edit.putInt(MARKER,1).commit())
        WebDavBackup.clear(ctx)
        PolishSecret.save(ctx,"")
    }
}

class KongyeApplication:Application() {
    override fun onCreate() {
        super.onCreate()
        InstallDefaults.seed(this)
    }
}
