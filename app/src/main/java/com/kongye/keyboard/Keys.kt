package com.kongye.keyboard

import android.graphics.RectF

enum class Kind { LETTER, CHAR, SHIFT, DEL, ENTER, SPACE, SYM, NUM, ABC, LANG, SEP, RESET }

/** 按键外形：普通键、功能键 */
enum class Look { NORMAL, MOD }

class Key(
    val kind: Kind,
    val label: String = "",
    val out: String = label,
    /** 右上角标：下滑或长按输入它 */
    val hint: String? = null,
    /** 左下角标：上滑输入它 */
    val hint2: String? = null,
    val w: Float = 1f,
    val look: Look = Look.NORMAL,
    val icon: Int = 0,
    /** 长按打开表情面板（逗号键） */
    val longEmoji: Boolean = false,
    /** 按住连发（删除） */
    val repeat: Boolean = false,
    /** 字母键代表的字母集合：全键盘是一个字母，14 键是两个，九键是三四个 */
    val letters: String = label.lowercase(),
) {
    val rect = RectF()
    val hit = RectF()
}

/** indent：这一行的键宽跟第一行（10 个键）一样，整行居中，用于 asdf 行 */
class Row(val keys: List<Key>, val indent: Boolean = false)

object Layouts {
    private val DIGITS = "1234567890".map { it.toString() }
    private val ROW1_UP = listOf("!", "@", "#", "$", "%", "^", "&", "*", "(", ")")

    // 中文：右上（下滑）/ 左下（上滑）
    private val ZH2_DOWN = listOf("～", "—", "…", "·", "¥", "、", "【", "】", "！")
    private val ZH2_UP = listOf("~", "-", "_", "'", "=", "\\", "[", "]", "/")
    private val ZH3_DOWN = listOf("（", "）", "：", "；", "《", "》", "？")
    private val ZH3_UP = listOf("\"", "`", ":", ";", "<", ">", "?")

    // 英文
    private val EN2_DOWN = listOf("~", "-", "…", "·", "¥", "\\", "[", "]", "!")
    private val EN2_UP = listOf("≈", "+", "_", "'", "=", "|", "{", "}", "/")
    private val EN3_DOWN = listOf("(", ")", ":", ";", "<", ">", "?")
    private val EN3_UP = listOf("\"", "`", "×", "÷", "≤", "≥", "°")

    /** 所有皮肤共用的底行（空叶布局）：123、中/英、逗号、空格、句号、回车 */
    private fun bottom(zh: Boolean): List<Key> = listOf(
        Key(Kind.NUM, "123", w = 1.3f, look = Look.MOD),
        Key(Kind.LANG, w = 1f, look = Look.MOD),
        Key(Kind.CHAR, if (zh) "，" else ",", longEmoji = true),
        Key(Kind.SPACE, w = 3.6f),
        Key(Kind.CHAR, if (zh) "。" else "."),
        Key(Kind.ENTER, w = 1.6f, look = Look.MOD),
    )

    private fun del(w: Float = 1.5f) = Key(Kind.DEL, hint = "清空", w = w, look = Look.MOD, repeat = true)

    fun letters(zh: Boolean): List<Row> {
        val d2 = if (zh) ZH2_DOWN else EN2_DOWN
        val u2 = if (zh) ZH2_UP else EN2_UP
        val d3 = if (zh) ZH3_DOWN else EN3_DOWN
        val u3 = if (zh) ZH3_UP else EN3_UP
        val r1 = "qwertyuiop".mapIndexed { i, c -> Key(Kind.LETTER, c.toString(), hint = DIGITS[i], hint2 = ROW1_UP[i]) }
        val r2 = "asdfghjkl".mapIndexed { i, c -> Key(Kind.LETTER, c.toString(), hint = d2[i], hint2 = u2[i]) }
        val r3 = listOf(Key(Kind.SHIFT, w = 1.5f, look = Look.MOD)) +
            "zxcvbnm".mapIndexed { i, c -> Key(Kind.LETTER, c.toString(), hint = d3[i], hint2 = u3[i]) } + del()
        return listOf(Row(r1), Row(r2, indent = true), Row(r3), Row(bottom(zh)))
    }

    /** 14 键：两个字母合一个键（只用于中文） */
    fun fourteen(): List<Row> {
        val r1 = listOf("QW", "ER", "TY", "UI", "OP").mapIndexed { i, t -> Key(Kind.LETTER, t, hint = DIGITS[i * 2], hint2 = DIGITS[i * 2 + 1]) }
        val r2 = listOf("AS", "DF", "GH", "JK", "L").mapIndexed { i, t -> Key(Kind.LETTER, t, hint = ZH2_DOWN[i * 2], hint2 = ZH2_UP[i * 2]) }
        val r3 = listOf(Key(Kind.SHIFT, w = 1.2f, look = Look.MOD)) +
            listOf("ZX", "CV", "BN", "M").mapIndexed { i, t -> Key(Kind.LETTER, t, hint = ZH3_DOWN[i * 2], hint2 = ZH3_UP[i * 2]) } + del(1.2f)
        return listOf(Row(r1), Row(r2), Row(r3), Row(bottom(true)))
    }

    /** 九键：左边一列可滚动的标点，由 KeyboardView 的 side 画 */
    val NINE_SIDE = listOf("，", "。", "？", "！", "…", "～", "、", "：", "；", "@", "—")

    /**
     * 九键（照截图）：左边一列标点；中间 3×3 字母键；右边一列删除、重输、0，最下面一行
     * 符号、123（和左列一样宽）、加宽的空格、中英切换、回车。
     */
    fun nine(): List<Row> {
        fun k(t: String, d: String) = Key(Kind.LETTER, t, hint = d, w = 1.5f)
        return listOf(
            Row(listOf(Key(Kind.SEP, "分词", hint = "1", w = 1.5f), k("ABC", "2"), k("DEF", "3"), del(1f))),
            Row(listOf(k("GHI", "4"), k("JKL", "5"), k("MNO", "6"), Key(Kind.RESET, look = Look.MOD, icon = R.drawable.ic_refresh))),
            Row(listOf(k("PQRS", "7"), k("TUV", "8"), k("WXYZ", "9"), Key(Kind.CHAR, "0"))),
            Row(listOf(
                Key(Kind.SYM, look = Look.MOD, icon = R.drawable.ic_symbol),
                Key(Kind.NUM, "123", look = Look.MOD),
                Key(Kind.SPACE, w = 2.5f),
                Key(Kind.LANG, look = Look.MOD),
                Key(Kind.ENTER, look = Look.MOD),
            )),
        )
    }

    /** 123 数字键盘的默认左列符号（可在设置里改） */
    val NUM_SIDE = Prefs.DEFAULT_NUM_SIDE

    /** 数字键盘；custom 是右边三个可自定义的键（默认 @ . 空格），是空格就画成空格键 */
    fun numpad(custom: List<String> = Prefs.DEFAULT_NUM_KEYS): List<Row> {
        fun c(s: String, w: Float = 1f) = if (s == " ") Key(Kind.SPACE, w = w, icon = R.drawable.ic_space) else Key(Kind.CHAR, s, w = w)
        return listOf(
            Row(listOf(Key(Kind.CHAR, "1", w = 1.5f), Key(Kind.CHAR, "2", w = 1.5f), Key(Kind.CHAR, "3", w = 1.5f), Key(Kind.DEL, look = Look.MOD, repeat = true))),
            Row(listOf(Key(Kind.CHAR, "4", w = 1.5f), Key(Kind.CHAR, "5", w = 1.5f), Key(Kind.CHAR, "6", w = 1.5f), c(custom[0]))),
            Row(listOf(Key(Kind.CHAR, "7", w = 1.5f), Key(Kind.CHAR, "8", w = 1.5f), Key(Kind.CHAR, "9", w = 1.5f), c(custom[1]))),
            Row(listOf(
                Key(Kind.ABC, look = Look.MOD, icon = R.drawable.ic_back),
                Key(Kind.SYM, w = 1.5f, look = Look.MOD, icon = R.drawable.ic_symbol),
                Key(Kind.CHAR, "0", w = 1.5f),
                c(custom[2], 1.5f),
                Key(Kind.ENTER, look = Look.MOD),
            )),
        )
    }

    /** 符号面板（照截图）：分类和符号 */
    val SYMBOLS: List<Pair<String, List<String>>> = listOf(
        "常用" to "（） 【】 ： “” 「」 ！ / _ ？ + : —— @ * - …… ， 。 、 ； 《》 ~ # % & = | ¥".split(' '),
        "中" to "， 。 ？ ！ 、 ； ： “ ” ‘ ’ （ ） 【 】 《 》 〈 〉 「 」 『 』 …… —— · ～ ￥".split(' '),
        "英" to ", . ? ! ; : ' \" ( ) [ ] { } < > - _ / \\ | @ # $ % ^ & * + = ~ `".split(' '),
        "网址" to "http:// https:// www. .com .cn .net .org / : ? = & # @ - _ ~ % .html".split(' '),
        "数学" to "+ − × ÷ = ≠ ≈ < > ≤ ≥ ± √ ∞ π % ‰ ° ∑ ∫ ∠ ⊥ ∥ ∈ ∩ ∪ ∵ ∴ ½ ⅓ ¼ ² ³".split(' '),
        "邮箱" to "@ @qq.com @163.com @126.com @gmail.com @outlook.com @foxmail.com @sina.com @icloud.com @hotmail.com .com .cn".split(' '),
        "序号" to "① ② ③ ④ ⑤ ⑥ ⑦ ⑧ ⑨ ⑩ ⑪ ⑫ ⑴ ⑵ ⑶ ⑷ ⑸ ⑹ Ⅰ Ⅱ Ⅲ Ⅳ Ⅴ Ⅵ Ⅶ Ⅷ Ⅸ Ⅹ ㈠ ㈡ ㈢ ㈣".split(' '),
        "特殊" to "★ ☆ ♥ ♡ ✓ ✔ ✗ ※ § № ® © ™ ♪ ♫ ☀ ☁ ☂ ☎ ✈ ♀ ♂ ↑ ↓ ← → ↖ ↗ ↘ ↙ ○ ● □ ■ △ ▲ ◇ ◆".split(' '),
    )
}
