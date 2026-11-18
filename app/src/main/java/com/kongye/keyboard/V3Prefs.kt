package com.kongye.keyboard

import android.content.Context
import android.content.res.Configuration

/** Versioned settings keys. Existing preference values remain the migration fallback. */
object V3Prefs {
    const val STATS_ENABLED="v3_stats_enabled"
    const val VIBRATION_LEVEL="v3_vibration_level"
    const val VIBRATION_RESUME_MODE="v3_vibration_resume_mode"
    const val VIBRATION="v3_vibration"
    const val SOUND_VOLUME="v3_sound_volume"
    const val CORRECTION_ENABLED="v3_correction_enabled"
    const val FUZZY_ENABLED="v3_fuzzy_enabled"
    const val CORRECT_NEAR="v3_correct_near"
    const val CORRECT_SWAP="v3_correct_swap"
    const val CORRECT_EXTRA="v3_correct_extra"
    const val CORRECT_MISSING="v3_correct_missing"
    const val MIXED="v3_mixed"
    const val PUNCT_CONFIRM="v3_punct_confirm"
    const val PAIRS="v3_pairs"
    const val EN_SPACE="v3_en_space"
    const val PUNCT_SPACE="v3_punct_space"
    const val EMAIL="v3_email"
    const val CAPS="v3_caps"
    const val CAPS_LOCK="v3_caps_lock"
    const val EN_CORRECT="v3_en_correct"
    const val SPACE_LATIN="v3_space_latin"
    const val SPACE_DIGIT="v3_space_digit"
    const val ASSOC_CONTINUOUS="v3_assoc_continuous"
    const val ASSOC_SPACE="v3_assoc_space"
    const val EMOJI="v3_emoji"
    const val SYMBOLS="v3_symbols"
    const val KAOMOJI="v3_kaomoji"
    const val HOMOPHONE="v3_homophone"
    const val GLIDE_ZH="v3_glide_zh"
    const val GLIDE_EN="v3_glide_en"
    const val GLIDE_LIVE="v3_glide_live"
    const val GLIDE_TRACE="v3_glide_trace"
    const val GLIDE_WORD_DELETE="v3_glide_word_delete"
    const val SWIPE_REVERSE="v3_swipe_reverse"
    const val LETTER_LONG="v3_letter_long"
    const val BOTTOM_CURSOR="v3_bottom_cursor"
    const val DELETE_DIRECT="v3_delete_direct"
    const val COMPOSE_RESTORE="v3_compose_restore"
    const val COMPOSE_HOLD_CLEAR="v3_compose_hold_clear"
    const val CLEAR_SCOPE="v3_clear_scope"
    const val DEL_UP="v3_del_up"
    const val DEL_DOWN="v3_del_down"
    const val DEL_LEFT="v3_del_left"
    const val DEL_RIGHT="v3_del_right"
    const val PINYIN_POSITION="v3_pinyin_position"
    const val READINGS="v3_readings"
    const val GLOSS="v3_gloss"
    const val FEEDBACK="v3_feedback"
    const val ORIENTATION_LAYOUT="v3_orientation_layout"
    const val LAYOUT_LANDSCAPE="v3_layout_landscape"
    const val ONE_HAND="v3_one_hand"
    const val FILL_GAPS="v3_fill_gaps"
    const val SPLIT_GAP="v3_split_gap"
    const val SPLIT_SIZE="v3_split_size"
    const val CLIP_LIMIT="v3_clip_limit"
    const val CLIP_DAYS="v3_clip_days"
    const val MEDIA="v3_media"
    const val DRIVE="v3_drive"
    const val VOICE_PUNCT="v3_voice_punct"
    const val VOICE_NUMBERS="v3_voice_numbers"
    const val VOICE_POLISH="v3_voice_polish"
    const val VOICE_CLEAN="v3_voice_clean"
    const val HAND_TONE="v3_hand_tone"

    fun migrate(ctx:Context) {
        val p=Prefs.of(ctx)
        if(!p.contains(VIBRATION) && p.getInt(Prefs.VIB_STRENGTH,2)==0) {
            p.edit().putInt(VIBRATION,2).putInt(Prefs.VIB_STRENGTH,2).apply()
        }
    }

    fun enabled(ctx:Context,key:String,default:Boolean=true)=Prefs.of(ctx).getBoolean(key,default)
    fun glide(ctx:Context,zh:Boolean):Boolean {
        val p=Prefs.of(ctx)
        return p.getBoolean(if(zh) GLIDE_ZH else GLIDE_EN,p.getBoolean(Prefs.GLIDE,false))
    }
    fun layout(ctx:Context):String {
        val p=Prefs.of(ctx)
        return if(p.getBoolean(ORIENTATION_LAYOUT,false) && ctx.resources.configuration.orientation==Configuration.ORIENTATION_LANDSCAPE)
            p.getString(LAYOUT_LANDSCAPE,p.getString(Prefs.LAYOUT,"26")) ?: "26" else p.getString(Prefs.LAYOUT,"26") ?: "26"
    }
    fun saveLayout(ctx:Context,value:String) {
        val landscape=enabled(ctx,ORIENTATION_LAYOUT,false) && ctx.resources.configuration.orientation==Configuration.ORIENTATION_LANDSCAPE
        Prefs.of(ctx).edit().putString(if(landscape) LAYOUT_LANDSCAPE else Prefs.LAYOUT,value).apply()
    }
}
