package com.kongye.keyboard

import android.content.Context
import android.content.SharedPreferences
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
    const val RECOGNIZE_ENGLISH="v3_recognize_english"
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
    const val LANDSCAPE_FLOAT="v3_landscape_float"
    const val LANDSCAPE_SPLIT="v3_landscape_split"
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
        if (p.contains("v3_remote_float")) p.edit().remove("v3_remote_float").apply()
        if(p.getBoolean(ORIENTATION_LAYOUT,false))p.edit().also {FloatingKeyboard.rememberLandscape(ctx,it)}.apply()
        if (p.getBoolean(ORIENTATION_LAYOUT, false) &&
            (!p.contains(LANDSCAPE_FLOAT) || !p.contains(LANDSCAPE_SPLIT) || !p.contains(LAYOUT_LANDSCAPE))) {
            setSeparateLayouts(ctx, true)
        }
        if(!p.contains(VIBRATION) && p.getInt(Prefs.VIB_STRENGTH,2)==0) {
            p.edit().putInt(VIBRATION,2).putInt(Prefs.VIB_STRENGTH,2).apply()
        }
    }

    fun enabled(ctx:Context,key:String,default:Boolean=true)=Prefs.of(ctx).getBoolean(key,default)
    fun glide(ctx:Context,zh:Boolean):Boolean {
        val p=Prefs.of(ctx)
        return p.getBoolean(if(zh) GLIDE_ZH else GLIDE_EN,p.getBoolean(Prefs.GLIDE,false))
    }
    fun usesLandscapeLayout(ctx: Context): Boolean =
        enabled(ctx, ORIENTATION_LAYOUT, false) && ctx.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

    fun layout(ctx: Context): String = layout(Prefs.of(ctx), usesLandscapeLayout(ctx))

    fun layout(prefs: SharedPreferences, landscape: Boolean): String {
        val portrait = prefs.getString(Prefs.LAYOUT, "26") ?: "26"
        return if (landscape) prefs.getString(LAYOUT_LANDSCAPE, portrait) ?: portrait else portrait
    }

    fun saveLayout(ctx: Context, value: String) = saveLayout(ctx, usesLandscapeLayout(ctx), value)

    fun saveLayout(ctx: Context, landscape: Boolean, value: String) {
        val prefs = Prefs.of(ctx)
        val editor = prefs.edit()
        if (!landscape && prefs.getBoolean(ORIENTATION_LAYOUT, false) && !prefs.contains(LAYOUT_LANDSCAPE)) {
            editor.putString(LAYOUT_LANDSCAPE, prefs.getString(Prefs.LAYOUT, "26"))
        }
        editor.putString(if (landscape) LAYOUT_LANDSCAPE else Prefs.LAYOUT, value).apply()
    }

    private fun floatingKey(landscape: Boolean) = if (landscape) LANDSCAPE_FLOAT else Prefs.FLOAT
    private fun splitKey(landscape: Boolean) = if (landscape) LANDSCAPE_SPLIT else Prefs.SPLIT_LAYOUT

    fun dockedSplit(ctx: Context): Boolean = dockedSplit(Prefs.of(ctx), usesLandscapeLayout(ctx))

    private fun dockedSplit(prefs: SharedPreferences, landscape: Boolean): Boolean =
        prefs.getBoolean(splitKey(landscape), prefs.getBoolean(Prefs.SPLIT_LAYOUT, false))

    fun keyboardForm(ctx: Context): KeyboardForm = keyboardForm(Prefs.of(ctx), usesLandscapeLayout(ctx))

    fun keyboardForm(prefs: SharedPreferences, landscape: Boolean): KeyboardForm = when {
        prefs.getBoolean(floatingKey(landscape), prefs.getBoolean(Prefs.FLOAT, false)) -> KeyboardForm.FLOATING
        dockedSplit(prefs, landscape) -> KeyboardForm.SPLIT
        else -> KeyboardForm.NORMAL
    }

    private fun rememberLandscapeForm(prefs: SharedPreferences, editor: SharedPreferences.Editor) {
        if (!prefs.contains(LANDSCAPE_FLOAT)) editor.putBoolean(LANDSCAPE_FLOAT, prefs.getBoolean(Prefs.FLOAT, false))
        if (!prefs.contains(LANDSCAPE_SPLIT)) editor.putBoolean(LANDSCAPE_SPLIT, prefs.getBoolean(Prefs.SPLIT_LAYOUT, false))
    }

    fun setSeparateLayouts(ctx: Context, separate: Boolean) {
        val prefs = Prefs.of(ctx)
        val editor = prefs.edit().putBoolean(ORIENTATION_LAYOUT, separate)
        if (separate) {
            FloatingKeyboard.rememberLandscape(ctx,editor)
            rememberLandscapeForm(prefs, editor)
            if (!prefs.contains(LAYOUT_LANDSCAPE)) editor.putString(LAYOUT_LANDSCAPE, prefs.getString(Prefs.LAYOUT, "26"))
        }
        editor.apply()
    }

    fun saveKeyboardForm(ctx: Context, landscape: Boolean, form: KeyboardForm) {
        val prefs = Prefs.of(ctx)
        val editor = prefs.edit()
        writeFormSetting(prefs, editor, landscape, Prefs.FLOAT, LANDSCAPE_FLOAT, form == KeyboardForm.FLOATING)
        if (form != KeyboardForm.FLOATING) {
            writeFormSetting(prefs, editor, landscape, Prefs.SPLIT_LAYOUT, LANDSCAPE_SPLIT, form == KeyboardForm.SPLIT)
        }
        editor.apply()
    }

    fun saveFloating(ctx: Context, floating: Boolean, editor: SharedPreferences.Editor) {
        writeFormSetting(Prefs.of(ctx), editor, usesLandscapeLayout(ctx), Prefs.FLOAT, LANDSCAPE_FLOAT, floating)
    }

    fun saveDockedSplit(ctx: Context, split: Boolean, editor: SharedPreferences.Editor) {
        writeFormSetting(Prefs.of(ctx), editor, usesLandscapeLayout(ctx), Prefs.SPLIT_LAYOUT, LANDSCAPE_SPLIT, split)
    }

    private fun writeFormSetting(
        prefs: SharedPreferences, editor: SharedPreferences.Editor, landscape: Boolean,
        portraitKey: String, landscapeKey: String, value: Boolean,
    ) {
        if (!landscape && prefs.getBoolean(ORIENTATION_LAYOUT, false) && !prefs.contains(landscapeKey)) {
            editor.putBoolean(landscapeKey, prefs.getBoolean(portraitKey, false))
        }
        editor.putBoolean(if (landscape) landscapeKey else portraitKey, value)
    }
}

enum class KeyboardForm(val label: String) {
    NORMAL("正常键盘"), SPLIT("分体键盘"), FLOATING("悬浮键盘");

    companion object {
        fun supportsSplit(layout: String, widthDp: Float, floating: Boolean): Boolean {
            val minWidth = if (floating) 600f else 320f
            return layout != "9" && widthDp >= minWidth
        }
    }
}
