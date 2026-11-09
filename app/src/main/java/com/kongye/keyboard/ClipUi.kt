package com.kongye.keyboard

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.text.Layout
import android.text.SpannableStringBuilder
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import android.text.style.ForegroundColorSpan
import android.text.style.UnderlineSpan
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

object EmojiData {
    val EMOJI_TABS = listOf("最近", "😀", "👍", "🐱", "🍔", "🎉")
    private const val FACES = "😀 😃 😄 😁 😆 😅 😂 🤣 😊 😇 🙂 🙃 😉 😌 😍 🥰 😘 😗 😙 😚 😋 😛 😝 😜 🤪 🤨 🧐 🤓 😎 🥳 😏 😒 😞 😔 😟 😕 🙁 😣 😖 😫 😩 🥺 😢 😭 😤 😠 😡 🤬 🤯 😳 🥵 🥶 😱 😨 😰 😥 😓 🤗 🤔 🤭 🤫 🤥 😶 😐 😑 😬 🙄 😯 😦 😧 😮 😲 🥱 😴 🤤 😪 😵 🤐 🥴 🤢 🤮 🤧 😷 🤒 🤕 🤑 🤠 😈 👻 💀 👽 🤖 💩 😺 😸 😹 😻 😼 😽 🙀 😿 😾"
    private const val HANDS = "👍 👎 👌 ✌️ 🤞 🤟 🤘 🤙 👈 👉 👆 👇 ☝️ ✋ 🤚 🖐️ 🖖 👋 🤝 🙏 👏 🙌 👐 🤲 💪 ✍️ 🫶 ❤️ 🧡 💛 💚 💙 💜 🖤 🤍 🤎 💔 ❣️ 💕 💞 💓 💗 💖 💘 💝 ✨ ⭐ 🌟 💫 🔥 💯 💢 💥 💦 💤"
    private const val NATURE = "🐱 🐶 🐭 🐹 🐰 🦊 🐻 🐼 🐨 🐯 🦁 🐮 🐷 🐸 🐵 🐔 🐧 🐦 🐤 🦆 🦉 🐺 🐴 🦄 🐝 🦋 🐌 🐞 🐢 🐍 🐙 🐠 🐬 🐳 🍂 🍁 🌿 🍀 🌱 🌲 🌳 🌴 🌵 🌷 🌸 🌹 🌺 🌻 🌼 💐 🌙 ☀️ ⛅ 🌧️ ⛄ 🌈 ⚡ ❄️ 🌊"
    private const val FOOD = "🍎 🍊 🍋 🍌 🍉 🍇 🍓 🍒 🍑 🥭 🍍 🥝 🍅 🥑 🌽 🥕 🍞 🥐 🧀 🍳 🍔 🍟 🍕 🌭 🥪 🌮 🍜 🍝 🍣 🍱 🥟 🍙 🍚 🍰 🎂 🍦 🍩 🍪 🍫 🍬 🍭 ☕ 🍵 🧋 🥤 🍺 🍷 🥂"
    private const val THINGS = "🎉 🎊 🎁 🎈 🎀 🏆 ⚽ 🏀 🎮 🎧 🎵 🎶 📚 📖 ✏️ 📝 💡 📱 💻 ⌚ 📷 🔑 🏠 🚗 ✈️ 🚀 ⏰ 💰 🛒 💌 📦 ✅ ❌ ❓ ❗ ⭕ 🆗"
    val KAOMOJI = listOf(
        "(๑•̀ㅂ•́)و✧", "( ˘ω˘ )", "(￣▽￣)", "(｡•́︿•̀｡)", "(๑ˉ∀ˉ๑)", "ヾ(≧▽≦*)o", "(⊙ˍ⊙)", "╰(*°▽°*)╯",
        "(╯°□°）╯︵ ┻━┻", "( ´▽` )ﾉ", "(。・ω・。)", "ฅ^•ﻌ•^ฅ", "(=^･ω･^=)", "ヽ(・∀・)ﾉ", "(≧∇≦)ﾉ", "(つ﹏⊂)",
        "(ಥ_ಥ)", "¯\\_(ツ)_/¯", "(ˊᗜˋ)", "(•̀ᴗ•́)و", "( ・ิω・ิ)", "(*/ω＼*)", "(｀・ω・´)", "_(:з」∠)_",
        "(*^▽^*)", "o(*￣▽￣*)ブ", "(っ °Д °;)っ", "(´･ω･`)", "Σ(っ °Д °;)っ", "(o゜▽゜)o☆", "♪(^∇^*)", "(。>︿<)_θ",
    )

    fun emojiTab(i: Int, recent: List<String>): List<String> = when (i) {
        0 -> recent
        1 -> FACES.split(' ')
        2 -> HANDS.split(' ')
        3 -> NATURE.split(' ')
        4 -> FOOD.split(' ')
        else -> THINGS.split(' ')
    }

    fun isKaomoji(s: String) = s.length > 3
}

/** 表情面板：Emoji / 颜文字 / 收藏；长按任意一个收藏或取消收藏 */
class EmojiPanel(
    context: Context,
    private val skin: Skin,
    private val recent: List<String>,
    private val favs: List<String>,
    startTab: Int,
    private val onEmoji: (String) -> Unit,
    private val onFav: (String) -> Unit,
    private val onTab: (Int) -> Unit,
    private val onDel: () -> Unit,
) : LinearLayout(context) {
    private val grid = CellGridView(context)
    private val subTabs = ArrayList<TextView>()
    private val subRow = LinearLayout(context)

    init {
        orientation = VERTICAL
        val head = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(context.dp(10f).toInt(), context.dp(4f).toInt(), context.dp(10f).toInt(), 0)
        }
        head.addView(segmented(context, skin, listOf("Emoji", "颜文字", "收藏"), startTab) { onTab(it) }, lp(WRAP, WRAP))
        head.addView(View(context), lp(0, 1, 1f))
        head.addView(TextView(context).apply {
            text = "长按收藏"
            textSize = 11.5f
            setTextColor(skin.barFg)
        }, lp(WRAP, WRAP))
        addView(head, lp(MATCH, WRAP))
        grid.fg = skin.candFg
        grid.downBg = skin.keyDown
        grid.onPick = { i -> grid.items.getOrNull(i)?.takeIf { it.isNotEmpty() }?.let(onEmoji) }
        grid.onLong = { i -> grid.items.getOrNull(i)?.takeIf { it.isNotEmpty() }?.let(onFav) }
        addView(ScrollView(context).apply { isVerticalScrollBarEnabled = false; addView(grid) }, lp(MATCH, 0, 1f))
        subRow.orientation = HORIZONTAL
        subRow.setPadding(context.dp(4f).toInt(), context.dp(2f).toInt(), context.dp(4f).toInt(), context.dp(4f).toInt())
        when (startTab) {
            0 -> {
                EmojiData.EMOJI_TABS.forEachIndexed { i, label ->
                    val tv = TextView(context).apply {
                        text = label
                        gravity = Gravity.CENTER
                        textSize = if (label.length > 2) 12f else 17f
                        setTextColor(skin.candFg)
                        setOnClickListener { selectEmoji(i) }
                    }
                    subTabs.add(tv)
                    subRow.addView(tv, lp(0, context.dp(36f).toInt(), 1f))
                }
                selectEmoji(if (recent.isEmpty()) 1 else 0)
            }
            1 -> show(EmojiData.KAOMOJI, kao = true)
            else -> {
                show(favs.ifEmpty { listOf("") }, kao = favs.any { EmojiData.isKaomoji(it) })
                if (favs.isEmpty()) subRow.addView(TextView(context).apply {
                    text = "还没有收藏：在 Emoji 或颜文字里长按一个"
                    textSize = 12.5f
                    setTextColor(skin.barFg)
                    gravity = Gravity.CENTER_VERTICAL
                }, lp(0, context.dp(36f).toInt(), 6f))
            }
        }
        if (startTab != 0) subRow.addView(View(context), lp(0, 1, 6f))
        subRow.addView(IconButton(context, R.drawable.ic_del, skin.modFg, 22f) { onDel() }, lp(0, context.dp(36f).toInt(), 1.2f))
        addView(subRow, lp(MATCH, WRAP))
    }

    private fun show(list: List<String>, kao: Boolean) {
        grid.columns = if (kao) 3 else 8
        grid.textSize = if (kao) 15f else 26f
        grid.cellH = if (kao) 50f else 46f
        grid.items = list
    }

    private fun selectEmoji(i: Int) {
        show(EmojiData.emojiTab(i, recent).ifEmpty { listOf("") }, kao = false)
        subTabs.forEachIndexed { j, tv -> tv.background = if (j == i) roundBg(skin.keyBg, context.dp(10f)) else null }
    }
}

/** 剪贴板管理：两列网格；分类（含自定义）、搜索、置顶、复制、编辑、分词、删除，支持图片 */
class ClipboardPanel(
    context: Context,
    private val skin: Skin,
    tab: String,
    query: String,
    private val expanded: Clip?,
    private val picking: Clip?,
    onTab: (String) -> Unit,
    onTabLong: (String) -> Unit,
    onNewCat: () -> Unit,
    onSearch: () -> Unit,
    onClearQuery: () -> Unit,
    onClear: () -> Unit,
    private val onPaste: (Clip) -> Unit,
    private val onExpand: (Clip?) -> Unit,
    private val onAction: (Clip, String) -> Unit,
) : LinearLayout(context) {
    private val d = resources.displayMetrics.density

    init {
        orientation = VERTICAL
        val head = LinearLayout(context).apply { orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val tabs = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding((6 * d).toInt(), (2 * d).toInt(), (4 * d).toInt(), (2 * d).toInt())
        }
        for (t in ClipStore.tabs()) {
            tabs.addView(TextView(context).apply {
                text = t
                textSize = 13.5f
                gravity = Gravity.CENTER
                setTextColor(if (t == tab) skin.keyFg else skin.barFg)
                typeface = if (t == tab) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
                background = if (t == tab) roundBg(skin.keyBg, 14 * d) else null
                setPadding((11 * d).toInt(), (5 * d).toInt(), (11 * d).toInt(), (5 * d).toInt())
                setOnClickListener { onTab(t) }
                if (t !in ClipStore.FIXED_TABS) setOnLongClickListener { onTabLong(t); true }
            }, lp(WRAP, WRAP))
        }
        tabs.addView(TextView(context).apply {
            text = "＋分类"
            textSize = 13f
            setTextColor(skin.candHi)
            setPadding((10 * d).toInt(), (5 * d).toInt(), (10 * d).toInt(), (5 * d).toInt())
            setOnClickListener { onNewCat() }
        }, lp(WRAP, WRAP))
        head.addView(HorizontalScrollView(context).apply { isHorizontalScrollBarEnabled = false; addView(tabs) }, lp(0, WRAP, 1f))
        head.addView(IconButton(context, R.drawable.ic_search, skin.barFg) { onSearch() }, lp((38 * d).toInt(), (36 * d).toInt()))
        head.addView(IconButton(context, R.drawable.ic_trash, skin.barFg) { onClear() }, lp((38 * d).toInt(), (36 * d).toInt()))
        addView(head, lp(MATCH, WRAP))
        if (query.isNotEmpty()) {
            val q = LinearLayout(context).apply { orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding((14 * d).toInt(), 0, (4 * d).toInt(), 0) }
            q.addView(TextView(context).apply { text = "搜索：$query"; textSize = 13f; setTextColor(skin.candHi) }, lp(0, WRAP, 1f))
            q.addView(IconButton(context, R.drawable.ic_close, skin.barFg, 16f) { onClearQuery() }, lp((36 * d).toInt(), (28 * d).toInt()))
            addView(q, lp(MATCH, WRAP))
        }
        val list = ClipStore.list(tab, query)
        val col = LinearLayout(context).apply { orientation = VERTICAL; setPadding((6 * d).toInt(), 0, (6 * d).toInt(), (8 * d).toInt()) }
        if (list.isEmpty()) {
            col.addView(TextView(context).apply {
                text = if (ClipStore.items.isEmpty()) "用空叶键盘时复制的文字和图片会存在这里，最多 ${ClipStore.MAX} 条。\n点一下上屏，长按可以置顶、复制、编辑、分词、分类、删除。" else "这一类里还没有内容"
                setTextColor(skin.barFg)
                textSize = 13.5f
                gravity = Gravity.CENTER
                setPadding(0, (30 * d).toInt(), 0, 0)
            }, lp(MATCH, WRAP))
        }
        // 两列网格：每行两张卡
        var i = 0
        while (i < list.size) {
            val row = LinearLayout(context).apply { orientation = HORIZONTAL }
            for (j in 0 until 2) {
                val clip = list.getOrNull(i + j)
                if (clip == null) row.addView(View(context), lp(0, 1, 1f))
                else row.addView(card(clip), lp(0, WRAP, 1f).apply { marginStart = (3 * d).toInt(); marginEnd = (3 * d).toInt() })
            }
            col.addView(row, lp(MATCH, WRAP).apply { topMargin = (6 * d).toInt() })
            i += 2
        }
        col.addView(TextView(context).apply {
            text = "共 ${ClipStore.items.size} 条，最多 ${ClipStore.MAX} 条；置顶的不会被挤掉。长按自定义分类可以删除它"
            textSize = 11.5f
            gravity = Gravity.CENTER
            setTextColor(skin.barFg)
            setPadding(0, (10 * d).toInt(), 0, 0)
        }, lp(MATCH, WRAP))
        addView(ScrollView(context).apply { isVerticalScrollBarEnabled = false; addView(col) }, lp(MATCH, 0, 1f))
    }

    private fun card(clip: Clip): View {
        val card = LinearLayout(context).apply {
            orientation = VERTICAL
            background = roundBg(skin.keyBg, 12 * d)
            setPadding((10 * d).toInt(), (9 * d).toInt(), (10 * d).toInt(), (9 * d).toInt())
            setOnClickListener { onPaste(clip) }
            setOnLongClickListener { onExpand(if (expanded === clip) null else clip); true }
        }
        val img = ClipStore.imageFile(clip)
        if (img != null && img.exists()) {
            val opts = BitmapFactory.Options().apply { inSampleSize = 4 }
            BitmapFactory.decodeFile(img.path, opts)?.let { bmp ->
                card.addView(ImageView(context).apply {
                    setImageBitmap(bmp)
                    scaleType = ImageView.ScaleType.CENTER_CROP
                    clipToOutline = true
                    background = roundBg(skin.modBg, 8 * d)
                }, lp(MATCH, (86 * d).toInt()))
            }
        } else {
            card.addView(TextView(context).apply {
                text = clip.text
                maxLines = 4
                ellipsize = TextUtils.TruncateAt.END
                textSize = 14f
                setTextColor(skin.keyFg)
            }, lp(MATCH, WRAP))
        }
        val tags = listOfNotNull(if (clip.pinned) "置顶" else null, clip.cat)
        if (tags.isNotEmpty()) card.addView(TextView(context).apply {
            text = tags.joinToString(" · ")
            textSize = 11f
            setTextColor(skin.candHi)
            setPadding(0, (4 * d).toInt(), 0, 0)
        }, lp(WRAP, WRAP))
        if (expanded === clip) {
            val acts = if (clip.image != null) listOf("pin" to if (clip.pinned) "取消置顶" else "置顶", "copy" to "复制", "cat" to "分类", "delete" to "删除")
            else listOf("pin" to if (clip.pinned) "取消置顶" else "置顶", "copy" to "复制", "edit" to "编辑", "split" to "分词", "cat" to "分类", "delete" to "删除")
            acts.chunked(3).forEach { chunk ->
                val r = LinearLayout(context).apply { orientation = HORIZONTAL; setPadding(0, (6 * d).toInt(), 0, 0) }
                chunk.forEach { (id, label) -> r.addView(actButton(label, id == "delete") { onAction(clip, id) }, lp(0, WRAP, 1f).apply { marginEnd = (4 * d).toInt() }) }
                card.addView(r, lp(MATCH, WRAP))
            }
            if (picking === clip) {
                val r = LinearLayout(context).apply { orientation = HORIZONTAL; setPadding(0, (6 * d).toInt(), 0, 0) }
                (listOf("无分类") + ClipStore.customTabs()).forEach { cat ->
                    r.addView(actButton(cat, false) { onAction(clip, "cat:$cat") }, lp(WRAP, WRAP).apply { marginEnd = (4 * d).toInt() })
                }
                card.addView(HorizontalScrollView(context).apply { isHorizontalScrollBarEnabled = false; addView(r) }, lp(MATCH, WRAP))
                if (ClipStore.customTabs().isEmpty()) card.addView(TextView(context).apply {
                    text = "先点上面的“＋分类”新建一个"
                    textSize = 11.5f
                    setTextColor(skin.barFg)
                })
            }
        }
        return card
    }

    private fun actButton(label: String, danger: Boolean, onClick: () -> Unit) = TextView(context).apply {
        text = label
        textSize = 12.5f
        gravity = Gravity.CENTER
        maxLines = 1
        setTextColor(if (danger) Color.parseColor("#D93025") else skin.modFg)
        background = roundBg(skin.modBg, 13 * d)
        setPadding((8 * d).toInt(), (5 * d).toInt(), (8 * d).toInt(), (5 * d).toInt())
        setOnClickListener { onClick() }
    }
}

/**
 * 剪贴板搜索、编辑，新建分类、翻译时出现在候选栏上方的输入条：键盘打的字进这里，不进编辑框。
 * 编辑时能显示多行（最多 5 行），搜索时一行。
 */
class SinkStrip(context: Context, private val onOk: () -> Unit, private val onCancel: () -> Unit) : View(context) {
    private val d = resources.displayMetrics.density
    private val icons = Icons(context)
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val tp = TextPaint(Paint.ANTI_ALIAS_FLAG)
    private val r = RectF()
    var skin: Skin = Skins.KONGYE
    var icon = R.drawable.ic_search
    var hint = "搜索剪贴板"
    var multiline = false
    var value = ""
    var composing = ""

    fun refresh() { requestLayout(); invalidate() }

    private fun textWidth(w: Int) = max(1, (w - 44 * d - 92 * d).toInt())

    private fun layoutFor(w: Int): StaticLayout {
        tp.textSize = 15 * d
        val sb = SpannableStringBuilder(if (value.isEmpty() && composing.isEmpty()) hint else value)
        if (value.isEmpty() && composing.isEmpty()) sb.setSpan(ForegroundColorSpan(skin.barFg), 0, sb.length, 0)
        else if (composing.isNotEmpty()) {
            val s = sb.length
            sb.append(composing)
            sb.setSpan(UnderlineSpan(), s, sb.length, 0)
        }
        tp.color = skin.keyFg
        return StaticLayout.Builder.obtain(sb, 0, sb.length, tp, textWidth(w)).setAlignment(Layout.Alignment.ALIGN_NORMAL).build()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val lines = if (multiline) min(5, max(2, layoutFor(w).lineCount)) else 1
        val lh = 21 * d
        setMeasuredDimension(w, (lines * lh + 26 * d).toInt())
    }

    override fun onDraw(c: Canvas) {
        r.set(8 * d, 6 * d, width - 8 * d, height - 4 * d)
        fill.color = skin.keyBg
        c.drawRoundRect(r, 12 * d, 12 * d, fill)
        icons.draw(c, icon, 26 * d, 6 * d + 19 * d, 19f, skin.barFg)
        val lay = layoutFor(width)
        val left = 44 * d
        val top = 6 * d + 9 * d
        c.save()
        c.clipRect(left, r.top, width - 92 * d, r.bottom)
        // 内容超过可见行数时只看最后几行
        val visible = if (multiline) 5 else 1
        val shift = if (lay.lineCount > visible) lay.getLineTop(lay.lineCount - visible) else 0
        c.translate(left, top - shift)
        if (!multiline && lay.lineCount > 1) {
            // 搜索框一行：横向显示末尾
            c.translate(0f, (shift - lay.getLineTop(lay.lineCount - 1)).toFloat())
        }
        lay.draw(c)
        if (value.isNotEmpty() || composing.isNotEmpty()) {
            val line = lay.lineCount - 1
            val x = lay.getLineRight(line)
            fill.color = skin.candHi
            c.drawRect(x + d, lay.getLineTop(line).toFloat(), x + 3 * d, lay.getLineBottom(line).toFloat(), fill)
        }
        c.restore()
        icons.draw(c, R.drawable.ic_done, width - 66 * d, 6 * d + 19 * d, 21f, skin.candHi)
        icons.draw(c, R.drawable.ic_close, width - 32 * d, 6 * d + 19 * d, 18f, skin.barFg)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (e.actionMasked == MotionEvent.ACTION_UP) {
            if (e.x > width - 48 * d) onCancel() else if (e.x > width - 86 * d) onOk()
        }
        return true
    }
}

/** 分词：把一段文字切成词块，点选后插入或复制 */
class TokensPanel(context: Context, private val skin: Skin, private val tokens: List<String>, onInsert: (String) -> Unit, onCopy: (String) -> Unit, onClose: () -> Unit) : LinearLayout(context) {
    private val d = resources.displayMetrics.density
    private val chosen = BooleanArray(tokens.size)
    private val flow = TokenFlow()

    init {
        orientation = VERTICAL
        addView(TextView(context).apply {
            text = "点选要的词，可以连着点好几个"
            textSize = 12.5f
            setTextColor(skin.barFg)
            setPadding((12 * d).toInt(), (6 * d).toInt(), 0, 0)
        }, lp(MATCH, WRAP))
        addView(ScrollView(context).apply { isVerticalScrollBarEnabled = false; addView(flow) }, lp(MATCH, 0, 1f))
        val acts = LinearLayout(context).apply { orientation = HORIZONTAL; setPadding((8 * d).toInt(), (4 * d).toInt(), (8 * d).toInt(), (6 * d).toInt()) }
        fun btn(label: String, strong: Boolean, f: () -> Unit) = acts.addView(TextView(context).apply {
            text = label
            textSize = 14f
            gravity = Gravity.CENTER
            setTextColor(if (strong) skin.accFg else skin.modFg)
            background = roundBg(if (strong) skin.accBg else skin.modBg, 16 * d)
            setPadding(0, (8 * d).toInt(), 0, (8 * d).toInt())
            setOnClickListener { f() }
        }, lp(0, WRAP, 1f).apply { marginEnd = (6 * d).toInt() })
        btn("全选", false) { val all = chosen.all { it }; chosen.fill(!all); flow.invalidate() }
        btn("复制", false) { onCopy(selected()) }
        btn("插入", true) { onInsert(selected()) }
        btn("关闭", false) { onClose() }
        addView(acts, lp(MATCH, WRAP))
    }

    private fun selected(): String {
        val picked = tokens.filterIndexed { i, _ -> chosen[i] }
        val list = picked.ifEmpty { tokens }
        val sb = StringBuilder()
        for (t in list) {
            if (sb.isNotEmpty() && sb.last().isLetterOrDigit() && sb.last() !in '一'..'鿿' && t.first().isLetterOrDigit() && t.first() !in '一'..'鿿') sb.append(' ')
            sb.append(t)
        }
        return sb.toString()
    }

    private inner class TokenFlow : View(context) {
        private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER; textSize = 16 * d }
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
        private val boxes = ArrayList<RectF>()
        private val slop = ViewConfiguration.get(context).scaledTouchSlop
        private var dx = 0f
        private var dy = 0f

        private fun layout(w: Int): Int {
            boxes.clear()
            var x = 8 * d
            var y = 8 * d
            val h = 38 * d
            for (t in tokens) {
                val bw = min(w - 16 * d, max(36 * d, text.measureText(t) + 20 * d))
                if (x + bw > w - 8 * d && x > 8 * d) { x = 8 * d; y += h + 6 * d }
                boxes.add(RectF(x, y, x + bw, y + h))
                x += bw + 6 * d
            }
            return (y + h + 8 * d).toInt()
        }

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val w = MeasureSpec.getSize(widthMeasureSpec)
            setMeasuredDimension(w, layout(w))
        }

        override fun onDraw(c: Canvas) {
            val fm = text.fontMetrics
            for ((i, r) in boxes.withIndex()) {
                fill.color = if (chosen[i]) skin.accBg else skin.keyBg
                c.drawRoundRect(r, 9 * d, 9 * d, fill)
                text.color = if (chosen[i]) skin.accFg else skin.keyFg
                c.drawText(tokens[i], r.centerX(), r.centerY() - (fm.ascent + fm.descent) / 2, text)
            }
        }

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(e: MotionEvent): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { dx = e.x; dy = e.y }
                MotionEvent.ACTION_UP -> if (abs(e.x - dx) < slop && abs(e.y - dy) < slop) {
                    val i = boxes.indexOfFirst { it.contains(e.x, e.y) }
                    if (i >= 0) { chosen[i] = !chosen[i]; invalidate() }
                }
            }
            return true
        }
    }
}
