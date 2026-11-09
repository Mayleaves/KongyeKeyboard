package com.kongye.keyboard

import android.content.Context
import android.content.SharedPreferences
import android.content.res.Configuration
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object Prefs {
    const val THEME = "theme"
    /** 0 跟随系统，1 始终浅色，2 始终深色 */
    const val MODE = "dark_mode"
    const val VIBRATE = "vibrate"
    /** 震动强度：0 关、1 弱、2 中、3 强 */
    const val VIB_STRENGTH = "vib_strength"
    const val SOUND = "sound"
    /** 键盘高度倍数，0.7–1.5，可以拖动调节 */
    const val HEIGHT_SCALE = "height_scale"
    const val ZH = "zh"
    const val RECENT_EMOJI = "recent_emoji"
    const val FAV_EMOJI = "fav_emoji"
    const val TOOLS = "tools_v2"
    const val MENU = "menu_v2"
    const val SWIPE = "swipe_symbols"
    const val ABBR = "abbr"
    const val ASSOC = "assoc"
    const val LEARN = "learn"
    /** 中文键盘布局：26、14、9 */
    const val LAYOUT = "layout"
    const val FLOAT = "float_mode"
    const val FLOAT_X = "float_x"
    const val FLOAT_Y = "float_y"
    const val CLIP_CATS = "clip_cats"
    const val COUNT_DATE = "count_date"
    const val COUNT_TODAY = "count_today"
    /** 数字键盘：左列符号（空格隔开）和右边三个可自定义的键（默认 @ . 空格） */
    const val NUM_SIDE_KEY = "num_side"
    const val NUM_KEYS = "num_keys"
    const val RECENT_SYM = "recent_sym"
    /** 键盘透明度 0.3–1 */
    const val OPACITY = "opacity"
    /** 液态玻璃外观 */
    const val GLASS = "glass"
    /** 键盘左右收进、底部留白（dp） */
    const val PAD_L = "pad_l"
    const val PAD_R = "pad_r"
    const val PAD_B = "pad_b"
    /** 模糊音：打开的规则 id，逗号隔开 */
    const val FUZZY = "fuzzy"
    /** 翻译目标语言："auto" 或 ML Kit 语言代码 */
    const val TRANS_TO = "trans_to"
    /** 语音：0 普通话，1 四川话 */
    const val VOICE_LANG = "voice_lang"
    const val UPDATE_CHECKED = "update_checked"
    /** 空格长按：0 光标模式，1 连续输入空格，2 开关语音 */
    const val SPACE_LONG = "space_long"

    val DEFAULT_NUM_SIDE = listOf("·", "/", "+", "-", "=", "%", "*", "#", ",", ":", "(", ")", "~", "_")
    val DEFAULT_NUM_KEYS = listOf("@", ".", " ")

    fun numSide(ctx: Context): List<String> =
        of(ctx).getString(NUM_SIDE_KEY, null)?.split(' ')?.filter { it.isNotEmpty() }?.takeIf { it.isNotEmpty() } ?: DEFAULT_NUM_SIDE

    /** 三个自定义键；保存时用 \u0001 隔开，空格键本身就是一个空格 */
    fun numKeys(ctx: Context): List<String> {
        val saved = of(ctx).getString(NUM_KEYS, null)?.split('\u0001')
        return List(3) { i -> saved?.getOrNull(i)?.takeIf { it.isNotEmpty() } ?: DEFAULT_NUM_KEYS[i] }
    }

    fun opacity(ctx: Context) = of(ctx).getFloat(OPACITY, 1f).coerceIn(0.3f, 1f)

    /** 模糊音规则：id 到说明 */
    val FUZZY_RULES = listOf(
        "z-zh" to "z = zh", "c-ch" to "c = ch", "s-sh" to "s = sh", "n-l" to "n = l", "f-h" to "f = h", "r-l" to "r = l",
        "an-ang" to "an = ang", "en-eng" to "en = eng", "in-ing" to "in = ing", "ian-iang" to "ian = iang", "uan-uang" to "uan = uang",
    )

    fun fuzzy(ctx: Context): Set<String> =
        of(ctx).getString(FUZZY, "")!!.split(',').filter { id -> FUZZY_RULES.any { it.first == id } }.toSet()

    /** 工具栏和菜单里可以放的功能 */
    val FEATURES = listOf(
        "emoji", "clip", "edit", "skin", "undo", "redo", "voice", "handwrite", "translate",
        "float", "layout", "adjust", "arrange", "settings", "lang",
    )
    val DEFAULT_TOOLS = listOf("emoji", "clip", "edit", "skin", "undo", "voice")
    const val MAX_TOOLS = 6

    val NAMES = mapOf(
        "emoji" to "表情", "clip" to "剪贴板", "edit" to "编辑", "skin" to "皮肤", "undo" to "撤销", "redo" to "重做",
        "voice" to "语音", "handwrite" to "手写", "translate" to "翻译", "float" to "悬浮键盘", "layout" to "键盘布局",
        "adjust" to "键盘调节", "arrange" to "排列", "settings" to "设置", "lang" to "中英切换", "more" to "更多",
    )

    val ICONS = mapOf(
        "emoji" to R.drawable.ic_emoji, "clip" to R.drawable.ic_clip, "edit" to R.drawable.ic_cursor,
        "skin" to R.drawable.ic_palette, "undo" to R.drawable.ic_undo, "redo" to R.drawable.ic_redo,
        "voice" to R.drawable.ic_mic, "handwrite" to R.drawable.ic_handwrite, "translate" to R.drawable.ic_translate,
        "float" to R.drawable.ic_float, "layout" to R.drawable.ic_kbd, "adjust" to R.drawable.ic_adjust,
        "arrange" to R.drawable.ic_toolbar, "settings" to R.drawable.ic_settings, "lang" to R.drawable.ic_globe,
        "more" to R.drawable.ic_grid,
    )

    fun of(ctx: Context): SharedPreferences = ctx.getSharedPreferences("kongye", Context.MODE_PRIVATE)

    fun systemNight(ctx: Context) =
        (ctx.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES

    fun theme(ctx: Context): Theme = Themes.byId(ctx, of(ctx).getString(THEME, "kongye"))

    fun night(ctx: Context): Boolean = when (of(ctx).getInt(MODE, 0)) {
        1 -> false
        2 -> true
        else -> systemNight(ctx)
    }

    fun skin(ctx: Context): Skin = theme(ctx).pick(night(ctx)).styled(opacity(ctx), of(ctx).getBoolean(GLASS, false))

    fun tools(ctx: Context): List<String> {
        val raw = of(ctx).getString(TOOLS, null) ?: return DEFAULT_TOOLS
        return raw.split(',').filter { it in FEATURES }.take(MAX_TOOLS)
    }

    /** 菜单：没放进工具栏的功能，按用户排的顺序 */
    fun menu(ctx: Context): List<String> {
        val t = tools(ctx)
        val saved = of(ctx).getString(MENU, null)?.split(',')?.filter { it in FEATURES && it !in t }.orEmpty()
        return saved + FEATURES.filter { it !in t && it !in saved }
    }

    fun saveArrangement(ctx: Context, tools: List<String>, menu: List<String>) {
        of(ctx).edit().putString(TOOLS, tools.joinToString(",")).putString(MENU, menu.joinToString(",")).apply()
    }

    fun heightScale(ctx: Context) = of(ctx).getFloat(HEIGHT_SCALE, 1f).coerceIn(0.7f, 1.5f)

    fun layout(ctx: Context) = of(ctx).getString(LAYOUT, "26") ?: "26"

    fun flag(ctx: Context, key: String, def: Boolean = true) = of(ctx).getBoolean(key, def)

    private fun today() = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())

    fun addTyped(ctx: Context, n: Int) {
        if (n <= 0) return
        val p = of(ctx)
        val d = today()
        val cur = if (p.getString(COUNT_DATE, "") == d) p.getInt(COUNT_TODAY, 0) else 0
        p.edit().putString(COUNT_DATE, d).putInt(COUNT_TODAY, cur + n).apply()
    }

    fun typedToday(ctx: Context): Int {
        val p = of(ctx)
        return if (p.getString(COUNT_DATE, "") == today()) p.getInt(COUNT_TODAY, 0) else 0
    }
}
