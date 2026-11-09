package com.kongye.keyboard

import android.content.Context
import android.graphics.Color
import android.os.Build
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** KONGYE：叶形圆角、红色回车；FLAT：圆角矩形的通用键盘（晴空、墨夜和各套配色） */
enum class SkinStyle { KONGYE, FLAT }

data class Skin(
    val id: String,
    val name: String,
    val style: SkinStyle,
    val dark: Boolean,
    val kbBg: Int,
    val barFg: Int,
    val candFg: Int,
    val candHi: Int,
    val keyBg: Int,
    val keyFg: Int,
    val keyDown: Int,
    /** 键下沿的 1dp 阴影，0 表示没有 */
    val keyShadow: Int,
    val modBg: Int,
    val modFg: Int,
    val accBg: Int,
    val accFg: Int,
    val hintFg: Int,
    val popBg: Int,
    val popFg: Int,
    val spaceFg: Int,
    /** 工具栏第一个图标（更多）的圆底 */
    val toolHiBg: Int,
    val toolHiFg: Int,
    val leaf: Int,
    /** 圆角（dp）；leafCorners 为真时左上右下用它，右上左下用 4dp，像一片叶子 */
    val radius: Float = 7f,
    val leafCorners: Boolean = false,
    val keyH: Float = 44f,
    val rowGap: Float = 8f,
    val keyGap: Float = 6f,
    val padH: Float = 4f,
    val letterSize: Float = 22f,
    /** 回车和 123 用功能键的颜色（晴空、墨夜） */
    val enterMod: Boolean = false,
    /** 键盘底色渐变的下端颜色，0 表示纯色 */
    val bg2: Int = 0,
    /** 液态玻璃：半透明按键、高光描边，键盘底透出后面的内容 */
    val glass: Boolean = false,
)

/** 一套配色：浅色和深色两个版本；只有浅色的配色在深色模式下也用浅色 */
class Theme(val id: String, val name: String, val swatch: Int, val light: Skin, val dark: Skin?) {
    fun pick(night: Boolean): Skin = if (night && dark != null) dark else light
}

private fun c(s: String) = Color.parseColor(s)

/** HSL → 颜色；h 0–360，s、l 0–1 */
fun hsl(h: Float, s: Float, l: Float): Int {
    val ss = s.coerceIn(0f, 1f)
    val ll = l.coerceIn(0f, 1f)
    val ch = (1 - abs(2 * ll - 1)) * ss
    val hp = ((h % 360 + 360) % 360) / 60f
    val x = ch * (1 - abs(hp % 2 - 1))
    val (r, g, b) = when {
        hp < 1 -> Triple(ch, x, 0f)
        hp < 2 -> Triple(x, ch, 0f)
        hp < 3 -> Triple(0f, ch, x)
        hp < 4 -> Triple(0f, x, ch)
        hp < 5 -> Triple(x, 0f, ch)
        else -> Triple(ch, 0f, x)
    }
    val m = ll - ch / 2
    fun v(f: Float) = ((f + m) * 255).roundToInt().coerceIn(0, 255)
    return Color.rgb(v(r), v(g), v(b))
}

fun toHsl(color: Int): FloatArray {
    val r = Color.red(color) / 255f
    val g = Color.green(color) / 255f
    val b = Color.blue(color) / 255f
    val mx = max(r, max(g, b))
    val mn = min(r, min(g, b))
    val l = (mx + mn) / 2
    if (mx == mn) return floatArrayOf(0f, 0f, l)
    val dd = mx - mn
    val s = if (l > 0.5f) dd / (2 - mx - mn) else dd / (mx + mn)
    val h = when (mx) {
        r -> ((g - b) / dd + (if (g < b) 6 else 0)) * 60
        g -> ((b - r) / dd + 2) * 60
        else -> ((r - g) / dd + 4) * 60
    }
    return floatArrayOf(h, s, l)
}

object Skins {
    val KONGYE = Skin(
        "kongye", "空叶", SkinStyle.KONGYE, dark = false,
        kbBg = c("#ECEDF1"), barFg = c("#686A72"), candFg = c("#18191C"), candHi = c("#F0492C"),
        keyBg = c("#FFFFFF"), keyFg = c("#18191C"), keyDown = c("#DFE1E7"), keyShadow = c("#CFD2DC"),
        modBg = c("#DEDFE5"), modFg = c("#18191C"), accBg = c("#F0492C"), accFg = c("#FFFFFF"),
        hintFg = c("#8E919B"), popBg = c("#FFFFFF"), popFg = c("#18191C"), spaceFg = c("#7C7F88"),
        toolHiBg = c("#FFE6E0"), toolHiFg = c("#D9381E"), leaf = c("#F0492C"),
        radius = 12f, leafCorners = true, keyH = 44f, rowGap = 9f, keyGap = 5f, padH = 5f, letterSize = 21f,
    )

    val KONGYE_NIGHT = Skin(
        "kongye_night", "空叶", SkinStyle.KONGYE, dark = true,
        kbBg = c("#17181B"), barFg = c("#9E9FA8"), candFg = c("#F5F6F8"), candHi = c("#F0492C"),
        keyBg = c("#2C2E33"), keyFg = c("#F5F6F8"), keyDown = c("#3D4047"), keyShadow = c("#0B0C0E"),
        modBg = c("#383B42"), modFg = c("#F5F6F8"), accBg = c("#F0492C"), accFg = c("#FFFFFF"),
        hintFg = c("#858892"), popBg = c("#383B42"), popFg = c("#FFFFFF"), spaceFg = c("#9E9FA8"),
        toolHiBg = c("#4A2620"), toolHiFg = c("#FF9E8A"), leaf = c("#F0492C"),
        radius = 12f, leafCorners = true, keyH = 44f, rowGap = 9f, keyGap = 5f, padH = 5f, letterSize = 21f,
    )

    val SKY = Skin(
        "sky", "晴空", SkinStyle.FLAT, dark = false,
        kbBg = c("#E8EEF5"), barFg = c("#444746"), candFg = c("#1F1F1F"), candHi = c("#0B57D0"),
        keyBg = c("#FFFFFF"), keyFg = c("#1F1F1F"), keyDown = c("#DCE3EB"), keyShadow = 0,
        modBg = c("#D3E3FD"), modFg = c("#1F1F1F"), accBg = c("#7DC8FC"), accFg = c("#0B2B47"),
        hintFg = c("#5F6368"), popBg = c("#FFFFFF"), popFg = c("#1F1F1F"), spaceFg = c("#8A9099"),
        toolHiBg = c("#A9D7FF"), toolHiFg = c("#0B2B47"), leaf = c("#0B57D0"), enterMod = true,
    )

    val DUSK = Skin(
        "dusk", "墨夜", SkinStyle.FLAT, dark = true,
        kbBg = c("#1B1B1C"), barFg = c("#C4C7C5"), candFg = c("#E3E3E3"), candHi = c("#C2E7FF"),
        keyBg = c("#3A3B3D"), keyFg = c("#E3E3E3"), keyDown = c("#4E5053"), keyShadow = 0,
        modBg = c("#55575B"), modFg = c("#E3E3E3"), accBg = c("#E8E9EB"), accFg = c("#1B1B1C"),
        hintFg = c("#B9BCC0"), popBg = c("#4E5053"), popFg = c("#FFFFFF"), spaceFg = c("#8E9196"),
        toolHiBg = c("#4A4C50"), toolHiFg = c("#E3E3E3"), leaf = c("#C2E7FF"), keyH = 46f, enterMod = true,
    )

    /** 由一个主色生成 Gboard 风格的浅色版本；pastel 是更浓一点的粉彩底 */
    fun lightOf(id: String, name: String, seed: Int, pastel: Boolean = false, gradientTo: Int = 0): Skin {
        val (h, s0, _) = toHsl(seed).let { Triple(it[0], it[1], it[2]) }
        val s = max(s0, 0.08f)
        val bg = if (pastel) hsl(h, min(s, 0.75f) * 0.85f, 0.935f) else hsl(h, min(s, 0.6f) * 0.5f, 0.945f)
        val mod = if (pastel) hsl(h, min(s, 0.7f) * 0.75f, 0.855f) else hsl(h, min(s, 0.6f) * 0.7f, 0.865f)
        return Skin(
            id, name, SkinStyle.FLAT, dark = false,
            kbBg = bg, barFg = c("#44474A"), candFg = c("#1F1F1F"), candHi = hsl(h, min(s, 0.85f), 0.38f),
            keyBg = Color.WHITE, keyFg = c("#1F1F1F"), keyDown = hsl(h, min(s, 0.5f) * 0.5f, 0.88f), keyShadow = 0,
            modBg = mod, modFg = c("#1F1F1F"), accBg = hsl(h, min(s, 0.85f), 0.5f), accFg = Color.WHITE,
            hintFg = c("#6B6F75"), popBg = Color.WHITE, popFg = c("#1F1F1F"), spaceFg = c("#8A9099"),
            toolHiBg = mod, toolHiFg = hsl(h, min(s, 0.85f), 0.3f), leaf = hsl(h, min(s, 0.85f), 0.5f),
            bg2 = gradientTo,
        )
    }

    fun darkOf(id: String, name: String, seed: Int, gradientTo: Int = 0): Skin {
        val (h, s0, _) = toHsl(seed).let { Triple(it[0], it[1], it[2]) }
        val s = max(s0, 0.06f)
        val mod = hsl(h, min(s, 0.6f) * 0.35f, 0.31f)
        return Skin(
            id, name, SkinStyle.FLAT, dark = true,
            kbBg = hsl(h, min(s, 0.6f) * 0.3f, 0.11f), barFg = c("#C4C7C5"), candFg = c("#E6E6E8"),
            candHi = hsl(h, min(s, 0.8f) * 0.8f, 0.78f),
            keyBg = hsl(h, min(s, 0.6f) * 0.16f, 0.225f), keyFg = c("#E6E6E8"), keyDown = hsl(h, min(s, 0.6f) * 0.2f, 0.3f), keyShadow = 0,
            modBg = mod, modFg = c("#E6E6E8"), accBg = hsl(h, min(s, 0.8f) * 0.75f, 0.72f), accFg = hsl(h, 0.5f, 0.12f),
            hintFg = c("#A9ADB3"), popBg = mod, popFg = Color.WHITE, spaceFg = c("#8E9196"),
            toolHiBg = mod, toolHiFg = hsl(h, min(s, 0.8f) * 0.8f, 0.82f), leaf = hsl(h, min(s, 0.8f) * 0.75f, 0.72f),
            bg2 = gradientTo,
        )
    }
}

/** 按设置给皮肤加透明度和液态玻璃效果 */
fun Skin.styled(opacity: Float, glass: Boolean): Skin {
    if (opacity >= 0.999f && !glass) return this
    fun a(c: Int, f: Float) = withAlpha(c, (Color.alpha(c) * f).toInt().coerceIn(0, 255))
    if (!glass) {
        // 透明度作用在整块键盘上（底色和按键一起），越透明越能看到键盘下面的内容；字不透明
        val keyF = opacity
        return copy(
            kbBg = a(kbBg, opacity), bg2 = if (bg2 != 0) a(bg2, opacity) else 0,
            keyBg = a(keyBg, keyF), modBg = a(modBg, keyF), keyDown = a(keyDown, keyF), keyShadow = 0,
        )
    }
    // iOS 液态玻璃：通透磨砂底 + 悬浮高折射率玻璃键帽
    // 键盘背后有系统模糊（见 KongyeIME.applyBlur），底色可以更通透
    val baseA = (0.2f + 0.42f * opacity).coerceIn(0.2f, 0.7f)
    val glassRadius = max(radius, 11f)
    return if (dark) copy(
        kbBg = withAlpha(mix(kbBg, Color.parseColor("#182030"), 0.32f), (255 * baseA).toInt()),
        bg2 = withAlpha(mix(if (bg2 != 0) bg2 else kbBg, accBg, 0.22f), (255 * (baseA + 0.06f).coerceAtMost(0.9f)).toInt()),
        keyBg = withAlpha(Color.WHITE, 38),
        modBg = withAlpha(Color.WHITE, 22),
        keyDown = withAlpha(Color.WHITE, 80),
        keyFg = Color.parseColor("#F8FAFC"),
        modFg = Color.parseColor("#E8EEF5"),
        candFg = Color.parseColor("#F8FAFC"),
        barFg = Color.parseColor("#C6D0DEC0".let { "#CBD5E1" }),
        hintFg = withAlpha(Color.WHITE, 165),
        toolHiBg = withAlpha(Color.WHITE, 52),
        keyShadow = withAlpha(Color.BLACK, 68),
        radius = glassRadius,
        glass = true,
    ) else copy(
        kbBg = withAlpha(mix(kbBg, Color.parseColor("#EBF3FF"), 0.45f), (255 * baseA).toInt()),
        bg2 = withAlpha(mix(if (bg2 != 0) bg2 else kbBg, accBg, 0.16f), (255 * (baseA + 0.06f).coerceAtMost(0.9f)).toInt()),
        keyBg = withAlpha(Color.WHITE, 120),
        modBg = withAlpha(Color.WHITE, 78),
        keyDown = withAlpha(Color.WHITE, 210),
        keyFg = Color.parseColor("#141820"),
        modFg = Color.parseColor("#1E2430"),
        candFg = Color.parseColor("#141820"),
        barFg = Color.parseColor("#4A5568"),
        hintFg = Color.parseColor("#5A6578"),
        toolHiBg = withAlpha(Color.WHITE, 195),
        keyShadow = withAlpha(Color.parseColor("#0F172A"), 24),
        radius = glassRadius,
        glass = true,
    )
}

object Themes {
    private fun dual(id: String, name: String, seed: String) = c(seed).let {
        Theme(id, name, it, Skins.lightOf("${id}_l", name, it), Skins.darkOf("${id}_d", name, it))
    }

    private fun pastel(id: String, name: String, seed: String) = c(seed).let {
        Theme(id, name, it, Skins.lightOf("${id}_l", name, it, pastel = true), Skins.darkOf("${id}_d", name, it))
    }

    private fun gradient(id: String, name: String, seed: String, l2: String, d2: String) = c(seed).let {
        Theme(id, name, it, Skins.lightOf("${id}_l", name, it, pastel = true, gradientTo = c(l2)), Skins.darkOf("${id}_d", name, it, gradientTo = c(d2)))
    }

    private val FIXED = listOf(
        // 晴空、墨夜各补一个反色版本：晴空的夜间、墨夜的白天
        Theme("sky", "晴空", c("#0B57D0"), Skins.SKY, Skins.darkOf("sky_d", "晴空", c("#0B57D0"))),
        Theme("dusk", "墨夜", c("#5F6368"), Skins.lightOf("dusk_l", "墨夜", c("#5F6368")), Skins.DUSK),
        dual("lavender", "薰衣草紫", "#7E57C2"),
        dual("ocean", "海洋蔚蓝", "#1E66D0"),
        dual("sunset", "落日橙光", "#E8590C"),
        dual("graphite", "沉稳石墨", "#5F6368"),
        dual("rose", "浪漫玫瑰", "#B0185A"),
        dual("teal", "青碧如水", "#00796B"),
        pastel("sakura", "樱花粉黛", "#E8336D"),
        pastel("skyblue", "晴空浅蓝", "#0A84D6"),
        pastel("mint", "薄荷浅绿", "#2E9E4F"),
        pastel("lilac", "丁香浅紫", "#9C27B0"),
        pastel("peach", "蜜桃浅橙", "#F57C00"),
        pastel("cream", "奶油米白", "#8D6E63"),
        gradient("aurora", "极光青蓝", "#00ACC1", "#E3F7EE", "#0B2A2E"),
        gradient("glow", "晚霞暖橙", "#F06A3C", "#FFF1E3", "#2E1410"),
    )

    /** 动态配色：取系统壁纸取色（Android 12+），更早的系统用蓝色 */
    private fun dynamicSeed(ctx: Context): Int =
        if (Build.VERSION.SDK_INT >= 31) ctx.getColor(android.R.color.system_accent1_500) else c("#3F6FD8")

    fun all(ctx: Context): List<Theme> {
        val seed = dynamicSeed(ctx)
        val dynamic = Theme("dynamic", "动态配色", seed, Skins.lightOf("dynamic_l", "动态配色", seed), Skins.darkOf("dynamic_d", "动态配色", seed))
        val kongye = Theme("kongye", "空叶", c("#F0492C"), Skins.KONGYE, Skins.KONGYE_NIGHT)
        return listOf(kongye, dynamic) + FIXED
    }

    fun byId(ctx: Context, id: String?): Theme = all(ctx).let { list -> list.firstOrNull { it.id == id } ?: list[0] }
}
