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

/** 表情面板：Emoji / 颜文字 / 收藏；支持左右滑动切换大类，长按任意一个收藏或取消收藏 */
class EmojiPanel(
    context: Context,
    private val skin: Skin,
    private val recent: List<String>,
    private var favs: List<String>,
    startTab: Int,
    private val onEmoji: (String) -> Unit,
    private val onFav: (String) -> Unit,
    private val onTab: (Int) -> Unit,
    private val onDel: () -> Unit,
) : LinearLayout(context) {
    private val d = resources.displayMetrics.density
    private val grid = CellGridView(context)
    private val gridScroller = ScrollView(context).apply { isVerticalScrollBarEnabled = false }
    private val subTabs = ArrayList<TextView>()
    private val subRow = LinearLayout(context)
    private val topSeg: LinearLayout
    private var curTab = startTab
    private var curEmojiSub = if (recent.isEmpty()) 1 else 0
    private var curKaoSub = 0

    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private var downX = 0f
    private var downY = 0f
    private var swipingHoriz = false

    init {
        orientation = VERTICAL
        val head = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(context.dp(10f).toInt(), context.dp(4f).toInt(), context.dp(10f).toInt(), 0)
        }
        topSeg = segmented(context, skin, listOf("Emoji", "颜文字", "收藏"), curTab) { switchMainTab(it, 0) }
        head.addView(topSeg, lp(WRAP, WRAP))
        head.addView(View(context), lp(0, 1, 1f))
        head.addView(TextView(context).apply {
            text = "左右滑换分类 · 长按收藏"
            textSize = 11.5f
            setTextColor(skin.barFg)
        }, lp(WRAP, WRAP))
        addView(head, lp(MATCH, WRAP))

        grid.fg = skin.candFg
        grid.downBg = skin.keyDown
        grid.onPick = { i -> grid.items.getOrNull(i)?.takeIf { it.isNotEmpty() }?.let(onEmoji) }
        grid.onLong = { i -> grid.items.getOrNull(i)?.takeIf { it.isNotEmpty() }?.let(onFav) }
        gridScroller.addView(grid)
        addView(gridScroller, lp(MATCH, 0, 1f))

        subRow.orientation = HORIZONTAL
        subRow.gravity = Gravity.CENTER_VERTICAL
        subRow.setPadding(context.dp(4f).toInt(), context.dp(2f).toInt(), context.dp(4f).toInt(), context.dp(4f).toInt())
        addView(subRow, lp(MATCH, WRAP))

        renderMainTab(curTab)
    }

    private fun markTopSeg(sel: Int) {
        for (j in 0 until topSeg.childCount) (topSeg.getChildAt(j) as? TextView)?.apply {
            setTextColor(if (j == sel) skin.keyFg else skin.barFg)
            typeface = if (j == sel) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
            background = if (j == sel) roundBg(skin.keyBg, context.dp(15f)) else null
        }
    }

    fun updateFavs(newFavs: List<String>) {
        favs = newFavs
        if (curTab == 2) renderMainTab(2)
    }

    private fun switchMainTab(next: Int, dir: Int) {
        val target = next.coerceIn(0, 2)
        if (target == curTab) return
        curTab = target
        onTab(target)
        markTopSeg(target)
        renderMainTab(target)
        if (dir != 0) {
            grid.translationX = dir * 36 * d
            grid.alpha = 0.35f
            grid.animate().translationX(0f).alpha(1f).setDuration(150).start()
        }
    }

    private fun renderMainTab(tab: Int) {
        subTabs.clear()
        subRow.removeAllViews()
        gridScroller.scrollTo(0, 0)
        when (tab) {
            0 -> {
                EmojiData.EMOJI_TABS.forEachIndexed { i, label ->
                    val tv = TextView(context).apply {
                        text = label
                        gravity = Gravity.CENTER
                        textSize = if (label.length > 2) 12f else 17f
                        setTextColor(skin.candFg)
                        setOnClickListener { curEmojiSub = i; selectEmoji(i) }
                    }
                    subTabs.add(tv)
                    subRow.addView(tv, lp(0, context.dp(36f).toInt(), 1f))
                }
                selectEmoji(curEmojiSub)
            }
            1 -> {
                val row = LinearLayout(context).apply { orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
                Kaomoji.CATS.forEachIndexed { i, (name, _) ->
                    val tv = TextView(context).apply {
                        text = name
                        gravity = Gravity.CENTER
                        textSize = 13f
                        setTextColor(skin.candFg)
                        setPadding(context.dp(11f).toInt(), 0, context.dp(11f).toInt(), 0)
                        setOnClickListener { curKaoSub = i; selectKao(i) }
                    }
                    subTabs.add(tv)
                    row.addView(tv, lp(WRAP, context.dp(32f).toInt()))
                }
                subRow.addView(HorizontalScrollView(context).apply { isHorizontalScrollBarEnabled = false; addView(row) }, lp(0, context.dp(36f).toInt(), 6f))
                selectKao(curKaoSub)
            }
            else -> {
                show(favs.ifEmpty { listOf("") }, kao = favs.any { EmojiData.isKaomoji(it) })
                if (favs.isEmpty()) subRow.addView(TextView(context).apply {
                    text = "还没有收藏：在 Emoji 或颜文字里长按一个"
                    textSize = 12.5f
                    setTextColor(skin.barFg)
                    gravity = Gravity.CENTER_VERTICAL
                }, lp(0, context.dp(36f).toInt(), 6f))
                else subRow.addView(View(context), lp(0, 1, 6f))
            }
        }
        subRow.addView(IconButton(context, R.drawable.ic_del, skin.modFg, 22f) { onDel() }, lp(0, context.dp(36f).toInt(), 1.2f))
    }

    override fun onInterceptTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = e.x; downY = e.y
                swipingHoriz = false
            }
            MotionEvent.ACTION_MOVE -> {
                // 在中间网格区域横滑：切换 Emoji / 颜文字 / 收藏
                val inGrid = e.y > topSeg.bottom && e.y < subRow.top
                val dx = e.x - downX
                val dy = e.y - downY
                if (inGrid && abs(dx) > slop * 1.3f && abs(dx) > abs(dy) * 1.3f) {
                    swipingHoriz = true
                    parent?.requestDisallowInterceptTouchEvent(true)
                    return true
                }
            }
        }
        return super.onInterceptTouchEvent(e)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (swipingHoriz && (e.actionMasked == MotionEvent.ACTION_UP || e.actionMasked == MotionEvent.ACTION_CANCEL)) {
            val dx = e.x - downX
            swipingHoriz = false
            if (abs(dx) > 28 * d) {
                switchSub(if (dx < 0) 1 else -1)
            }
            return true
        }
        return super.onTouchEvent(e)
    }

    private fun show(list: List<String>, kao: Boolean) {
        grid.columns = if (kao) 3 else 8
        grid.textSize = if (kao) 15f else 26f
        grid.cellH = if (kao) 50f else 46f
        grid.items = list
    }

    /** 左右滑：Emoji 里换表情分类，颜文字里换心情分类 */
    private fun switchSub(dir: Int) {
        val changed = when (curTab) {
            0 -> (curEmojiSub + dir).coerceIn(0, EmojiData.EMOJI_TABS.lastIndex).takeIf { it != curEmojiSub }?.also { curEmojiSub = it; selectEmoji(it) }
            1 -> (curKaoSub + dir).coerceIn(0, Kaomoji.CATS.lastIndex).takeIf { it != curKaoSub }?.also { curKaoSub = it; selectKao(it) }
            else -> null
        }
        if (changed != null) {
            gridScroller.scrollTo(0, 0)
            subTabs.getOrNull(changed)?.let { tv -> (tv.parent?.parent as? HorizontalScrollView)?.smoothScrollTo(maxOf(0, tv.left - (40 * d).toInt()), 0) }
            grid.translationX = dir * 36 * d
            grid.alpha = 0.35f
            grid.animate().translationX(0f).alpha(1f).setDuration(150).start()
        }
    }

    private fun selectKao(i: Int) {
        show(Kaomoji.CATS[i].second, kao = true)
        subTabs.forEachIndexed { j, tv -> tv.background = if (j == i) roundBg(skin.keyBg, context.dp(10f)) else null }
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
        val head = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding((6 * d).toInt(), (4 * d).toInt(), (8 * d).toInt(), (4 * d).toInt())
        }
        val tabs = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding((4 * d).toInt(), 0, (4 * d).toInt(), 0)
        }
        for (t in ClipStore.tabs()) {
            val active = t == tab
            tabs.addView(TextView(context).apply {
                text = t
                textSize = 13f
                gravity = Gravity.CENTER
                setTextColor(if (active) skin.accFg else skin.barFg)
                typeface = if (active) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
                background = if (active) roundBg(skin.accBg, 15 * d) else roundBg(withAlpha(skin.modBg, 110), 15 * d)
                setPadding((12 * d).toInt(), (5 * d).toInt(), (12 * d).toInt(), (5 * d).toInt())
                setOnClickListener { onTab(t) }
                if (t !in ClipStore.FIXED_TABS) setOnLongClickListener { onTabLong(t); true }
            }, lp(WRAP, WRAP).apply { marginEnd = (5 * d).toInt() })
        }
        tabs.addView(TextView(context).apply {
            text = "+ 分类"
            textSize = 12.5f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(skin.candHi)
            background = roundBg(withAlpha(skin.candHi, 28), 15 * d)
            setPadding((11 * d).toInt(), (5 * d).toInt(), (11 * d).toInt(), (5 * d).toInt())
            setOnClickListener { onNewCat() }
        }, lp(WRAP, WRAP))
        head.addView(HorizontalScrollView(context).apply { isHorizontalScrollBarEnabled = false; addView(tabs) }, lp(0, WRAP, 1f))
        head.addView(IconButton(context, R.drawable.ic_search, skin.barFg, 19f) { onSearch() }.apply {
            background = roundBg(withAlpha(skin.modBg, 140), 16 * d)
        }, lp((32 * d).toInt(), (32 * d).toInt()).apply { marginStart = (4 * d).toInt(); marginEnd = (5 * d).toInt() })
        head.addView(IconButton(context, R.drawable.ic_trash, skin.barFg, 19f) { onClear() }.apply {
            background = roundBg(withAlpha(skin.modBg, 140), 16 * d)
        }, lp((32 * d).toInt(), (32 * d).toInt()))
        addView(head, lp(MATCH, WRAP))
        if (query.isNotEmpty()) {
            val q = LinearLayout(context).apply { orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding((14 * d).toInt(), 0, (4 * d).toInt(), 0) }
            q.addView(TextView(context).apply { text = "搜索：$query"; textSize = 13f; setTextColor(skin.candHi) }, lp(0, WRAP, 1f))
            q.addView(IconButton(context, R.drawable.ic_close, skin.barFg, 16f) { onClearQuery() }, lp((36 * d).toInt(), (28 * d).toInt()))
            addView(q, lp(MATCH, WRAP))
        }
        val list = ClipStore.list(tab, query)
        val col = LinearLayout(context).apply { orientation = VERTICAL; setPadding((8 * d).toInt(), (2 * d).toInt(), (8 * d).toInt(), (10 * d).toInt()) }
        if (list.isEmpty()) {
            col.addView(TextView(context).apply {
                text = if (ClipStore.items.isEmpty()) "复制的文字和图片会保存在这里（最多 ${ClipStore.MAX} 条）\n点卡片直接上屏 · 长按展开编辑与分词" else "该分类下暂无记录"
                setTextColor(skin.barFg)
                textSize = 13f
                gravity = Gravity.CENTER
                setPadding(0, (32 * d).toInt(), 0, 0)
            }, lp(MATCH, WRAP))
        }
        // 两列瀑布/自适应网格；长按展开的卡片独占整行，避免6个操作按钮挤在一半宽度里
        var i = 0
        while (i < list.size) {
            val first = list[i]
            val second = list.getOrNull(i + 1)
            run {
                val row = LinearLayout(context).apply { orientation = HORIZONTAL }
                row.addView(card(first, fullWidth = false), lp(0, MATCH, 1f).apply { marginStart = (2 * d).toInt(); marginEnd = (4 * d).toInt() })
                if (second == null) row.addView(View(context), lp(0, 1, 1f).apply { marginStart = (4 * d).toInt(); marginEnd = (2 * d).toInt() })
                else row.addView(card(second, fullWidth = false), lp(0, MATCH, 1f).apply { marginStart = (4 * d).toInt(); marginEnd = (2 * d).toInt() })
                col.addView(row, lp(MATCH, WRAP).apply { topMargin = (7 * d).toInt() })
                i += 2
            }
        }
        if (list.isNotEmpty()) {
            col.addView(TextView(context).apply {
                text = "共 ${ClipStore.items.size}/${ClipStore.MAX} 条 · 点按上屏 · 长按操作"
                textSize = 11f
                gravity = Gravity.CENTER
                setTextColor(withAlpha(skin.barFg, 180))
                setPadding(0, (10 * d).toInt(), 0, 0)
            }, lp(MATCH, WRAP))
        }
        addView(ScrollView(context).apply { isVerticalScrollBarEnabled = false; addView(col) }, lp(MATCH, 0, 1f))
    }

    private fun card(clip: Clip, fullWidth: Boolean): View {
        val isExp = expanded === clip
        val cardBg = android.graphics.drawable.GradientDrawable().apply {
            setColor(skin.keyBg)
            cornerRadius = 14 * d
            setStroke((1 * d).toInt(), if (isExp) skin.candHi else if (clip.pinned) withAlpha(skin.candHi, 95) else withAlpha(skin.candFg, if (skin.dark) 24 else 16))
        }
        val card = LinearLayout(context).apply {
            orientation = VERTICAL
            background = cardBg
            setPadding((11 * d).toInt(), (9 * d).toInt(), (11 * d).toInt(), (9 * d).toInt())
            setOnClickListener { onPaste(clip) }
            setOnLongClickListener { onExpand(if (isExp) null else clip); true }
        }
        val box = android.widget.FrameLayout(context)
        val img = ClipStore.imageFile(clip)
        if (img != null && img.exists()) {
            val opts = BitmapFactory.Options().apply { inSampleSize = 4 }
            BitmapFactory.decodeFile(img.path, opts)?.let { bmp ->
                box.addView(ImageView(context).apply {
                    setImageBitmap(bmp)
                    scaleType = ImageView.ScaleType.CENTER_CROP
                    clipToOutline = true
                    background = roundBg(skin.modBg, 8 * d)
                }, android.widget.FrameLayout.LayoutParams(MATCH, (74 * d).toInt()))
            }
        } else {
            box.addView(TextView(context).apply {
                text = clip.text
                minLines = 2
                maxLines = if (isExp) 6 else 3
                ellipsize = TextUtils.TruncateAt.END
                textSize = 13.5f
                setLineSpacing(2 * d, 1f)
                setTextColor(skin.keyFg)
                setPadding(0, 0, if (clip.pinned || clip.cat != null) (28 * d).toInt() else 0, 0)
            }, android.widget.FrameLayout.LayoutParams(MATCH, WRAP))
        }
        val tags = listOfNotNull(if (clip.pinned) "置顶" else null, clip.cat)
        if (tags.isNotEmpty()) box.addView(TextView(context).apply {
            text = tags.joinToString(" · ")
            textSize = 10f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(skin.candHi)
            background = roundBg(withAlpha(skin.candHi, 26), 6 * d)
            setPadding((6 * d).toInt(), (2 * d).toInt(), (6 * d).toInt(), (2 * d).toInt())
        }, android.widget.FrameLayout.LayoutParams(WRAP, WRAP, Gravity.TOP or Gravity.END))
        card.addView(box, lp(MATCH, WRAP))
        if (isExp) {
            // 展开后的操作栏：整行宽敞排布，图标+文字清晰明了
            val acts = listOfNotNull(
                Triple("pin", R.drawable.ic_pin, if (clip.pinned) "取消置顶" else "置顶"),
                Triple("copy", R.drawable.ic_copy, "复制"),
                if (clip.image == null) Triple("edit", R.drawable.ic_edit, "编辑") else null,
                if (clip.image == null) Triple("split", R.drawable.ic_split, "分词") else null,
                Triple("cat", R.drawable.ic_tag, "分类"),
                Triple("delete", R.drawable.ic_trash, "删除"),
            )
            // 操作只用图标，卡片还是半宽，两行三个
            acts.chunked(3).forEach { chunk ->
                val r = LinearLayout(context).apply { orientation = HORIZONTAL; setPadding(0, (6 * d).toInt(), 0, 0) }
                chunk.forEachIndexed { idx, (id, icon, _) ->
                    val isDanger = id == "delete"
                    val isHighlight = (id == "pin" && clip.pinned) || (id == "cat" && picking === clip)
                    val tint = when {
                        isDanger -> Color.parseColor("#E5484D")
                        isHighlight -> skin.candHi
                        else -> skin.modFg
                    }
                    r.addView(IconButton(context, icon, tint, 18f) { onAction(clip, id) }.apply {
                        background = roundBg(if (isHighlight) withAlpha(skin.candHi, 28) else skin.modBg, 10 * d)
                    }, lp(0, (30 * d).toInt(), 1f).apply { if (idx < chunk.lastIndex) marginEnd = (5 * d).toInt() })
                }
                card.addView(r, lp(MATCH, WRAP))
            }
            if (picking === clip) {
                val catRow = LinearLayout(context).apply { orientation = HORIZONTAL; setPadding(0, (6 * d).toInt(), 0, 0) }
                (listOf("无分类") + ClipStore.customTabs()).forEach { cat ->
                    catRow.addView(actButton(cat, false) { onAction(clip, "cat:$cat") }, lp(WRAP, WRAP).apply { marginEnd = (5 * d).toInt() })
                }
                card.addView(HorizontalScrollView(context).apply { isHorizontalScrollBarEnabled = false; addView(catRow) }, lp(MATCH, WRAP))
                if (ClipStore.customTabs().isEmpty()) card.addView(TextView(context).apply {
                    text = "请先点顶部“+ 分类”创建自定义分类"
                    textSize = 11.5f
                    setTextColor(skin.barFg)
                    setPadding(0, (4 * d).toInt(), 0, 0)
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
    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    var skin: Skin = Skins.KONGYE
    var icon = R.drawable.ic_search
    var hint = "搜索剪贴板"
    var multiline = false
    var value = ""
    var composing = ""
    /** 翻译模式：左边的语言按钮文字；null 表示普通输入条 */
    var chip: String? = null
    var translatedPreview = ""
    var micOn = false
    var onChip: () -> Unit = {}
    var onMic: () -> Unit = {}
    var onClear: () -> Unit = {}

    private var scrollOffsetY = 0f
    private var maxScrollY = 0f
    private var lastTextSnapshot = ""
    private var downX = 0f
    private var downY = 0f
    private var startScrollY = 0f
    private var draggingVert = false
    private var micPulse = 0f

    private val chipW get() = max(48 * d, tpChip.measureText((chip ?: "") + " ▾") + 16 * d)
    private val tpChip = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 14f * d }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val boxLeft get() = 8 * d + chipW + 4 * d
    private val boxRight get() = width - 70 * d

    fun refresh() {
        val snap = value + "\u0000" + composing
        val textChanged = snap != lastTextSnapshot
        lastTextSnapshot = snap
        requestLayout()
        if (textChanged && multiline) {
            post {
                scrollOffsetY = maxScrollY
                invalidate()
            }
        }
        invalidate()
    }

    private fun textWidth(w: Int) = if (chip != null) max(1, (boxRight - boxLeft - 34 * d).toInt()) else max(1, (w - 44 * d - 92 * d).toInt())

    private fun layoutFor(w: Int): StaticLayout {
        tp.textSize = 15 * d
        val empty = value.isEmpty() && composing.isEmpty()
        val sb = SpannableStringBuilder(if (empty) hint else value)
        if (empty) sb.setSpan(ForegroundColorSpan(skin.barFg), 0, sb.length, 0)
        else if (composing.isNotEmpty()) {
            val s = sb.length
            sb.append(composing)
            sb.setSpan(ForegroundColorSpan(skin.candHi), s, sb.length, 0)
            sb.setSpan(UnderlineSpan(), s, sb.length, 0)
        }
        tp.color = skin.keyFg
        return StaticLayout.Builder.obtain(sb, 0, sb.length, tp, textWidth(w)).setAlignment(Layout.Alignment.ALIGN_NORMAL).build()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val lay = layoutFor(w)
        val lines = if (multiline) min(5, max(2, lay.lineCount)) else 1
        val lh = 21 * d
        val h = (lines * lh + 26 * d).toInt()
        val viewportH = h - 22 * d
        maxScrollY = max(0f, lay.height - viewportH)
        scrollOffsetY = scrollOffsetY.coerceIn(0f, maxScrollY)
        setMeasuredDimension(w, h)
    }

    override fun onDraw(c: Canvas) {
        if (chip != null) { drawTranslate(c); return }
        r.set(8 * d, 6 * d, width - 8 * d, height - 4 * d)
        fill.color = skin.keyBg
        c.drawRoundRect(r, 14 * d, 14 * d, fill)
        stroke.strokeWidth = d
        stroke.color = withAlpha(skin.candFg, if (skin.dark) 30 else 18)
        c.drawRoundRect(r, 14 * d, 14 * d, stroke)

        val iconCy = r.centerY()
        icons.draw(c, icon, 26 * d, iconCy, 19f, skin.barFg)
        val lay = layoutFor(width)
        val left = 44 * d
        val top = r.top + 8 * d
        val viewportH = r.height() - 16 * d
        maxScrollY = max(0f, lay.height - viewportH)
        scrollOffsetY = scrollOffsetY.coerceIn(0f, maxScrollY)

        c.save()
        c.clipRect(left, r.top + 4 * d, width - 92 * d, r.bottom - 4 * d)
        val shift = if (multiline) scrollOffsetY else if (lay.lineCount > 1) lay.getLineTop(lay.lineCount - 1).toFloat() else 0f
        c.translate(left, top - shift)
        lay.draw(c)
        if (value.isNotEmpty() || composing.isNotEmpty()) {
            val line = lay.lineCount - 1
            val x = lay.getLineRight(line)
            fill.color = skin.candHi
            c.drawRect(x + d, lay.getLineTop(line).toFloat(), x + 3 * d, lay.getLineBottom(line).toFloat(), fill)
        }
        c.restore()

        // 多行编辑可上下滑动时画右侧滚动条指示器
        if (multiline && maxScrollY > 2 * d) {
            val trackTop = r.top + 8 * d
            val trackH = r.height() - 16 * d
            val thumbH = max(14 * d, trackH * (viewportH / lay.height))
            val thumbY = trackTop + (trackH - thumbH) * (scrollOffsetY / maxScrollY)
            fill.color = withAlpha(skin.barFg, 110)
            c.drawRoundRect(RectF(width - 96 * d, thumbY, width - 93 * d, thumbY + thumbH), 1.5f * d, 1.5f * d, fill)
        }

        icons.draw(c, R.drawable.ic_done, width - 66 * d, iconCy, 21f, skin.candHi)
        icons.draw(c, R.drawable.ic_close, width - 32 * d, iconCy, 18f, skin.barFg)
    }

    /** 翻译输入条：一行。左边选语言，中间输入要翻译的内容（译文直接出现在聊天框的光标处），右边完成 */
    private fun drawTranslate(c: Canvas) {
        val cy = height / 2f + d
        // 语言：文字 + ▾，不加底色
        tpChip.color = skin.candFg
        tpChip.textAlign = Paint.Align.CENTER
        val fm = tpChip.fontMetrics
        c.drawText("${chip ?: "自动"} ▾", 8 * d + chipW / 2, cy - (fm.ascent + fm.descent) / 2, tpChip)
        // 输入框：浅色圆角，不描边
        r.set(boxLeft, cy - 17 * d, boxRight, cy + 17 * d)
        fill.color = skin.keyBg
        c.drawRoundRect(r, 17 * d, 17 * d, fill)
        val lay = layoutFor(width)
        c.save()
        c.clipRect(boxLeft + 12 * d, r.top, boxRight - 30 * d, r.bottom)
        val last = lay.lineCount - 1
        c.translate(boxLeft + 14 * d, cy - (lay.getLineTop(last) + lay.getLineBottom(last)) / 2f)
        lay.draw(c)
        if (value.isNotEmpty() || composing.isNotEmpty()) {
            val x = lay.getLineRight(last)
            fill.color = skin.candHi
            c.drawRect(x + d, lay.getLineTop(last).toFloat(), x + 2.5f * d, lay.getLineBottom(last).toFloat(), fill)
        }
        c.restore()
        if (value.isNotEmpty() || composing.isNotEmpty()) {
            fill.color = withAlpha(skin.barFg, 90)
            c.drawCircle(boxRight - 16 * d, cy, 9 * d, fill)
            icons.draw(c, R.drawable.ic_close, boxRight - 16 * d, cy, 11f, skin.keyBg)
        }
        // 完成
        val done = RectF(boxRight + 8 * d, cy - 15 * d, width - 10 * d, cy + 15 * d)
        fill.color = skin.accBg
        c.drawRoundRect(done, 15 * d, 15 * d, fill)
        tpChip.color = skin.accFg
        c.drawText("完成", done.centerX(), cy - (fm.ascent + fm.descent) / 2, tpChip)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = e.x
                downY = e.y
                startScrollY = scrollOffsetY
                draggingVert = false
                parent?.requestDisallowInterceptTouchEvent(true)
            }
            MotionEvent.ACTION_MOVE -> {
                val dy = e.y - downY
                if (multiline && maxScrollY > 0f && abs(dy) > slop * 0.7f) {
                    draggingVert = true
                    scrollOffsetY = (startScrollY - dy).coerceIn(0f, maxScrollY)
                    invalidate()
                }
            }
            MotionEvent.ACTION_UP -> {
                if (draggingVert) {
                    draggingVert = false
                    return true
                }
                if (chip != null) {
                    when {
                        e.x < boxLeft -> onChip()
                        e.x > boxRight + 4 * d -> onOk()
                        e.x > boxRight - 34 * d && (value.isNotEmpty() || composing.isNotEmpty()) -> onClear()
                    }
                    return true
                }
                if (e.x > width - 48 * d) onCancel() else if (e.x > width - 86 * d) onOk()
            }
            MotionEvent.ACTION_CANCEL -> draggingVert = false
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
            text = "点选要的词，手指按住滑过去可以一次选好几个"
            textSize = 12.5f
            setTextColor(skin.barFg)
            setPadding((12 * d).toInt(), (6 * d).toInt(), 0, 0)
        }, lp(MATCH, WRAP))
        addView(ScrollView(context).apply { isVerticalScrollBarEnabled = false; addView(flow) }, lp(MATCH, 0, 1f))
        val acts = LinearLayout(context).apply { orientation = HORIZONTAL; setPadding((8 * d).toInt(), (4 * d).toInt(), (8 * d).toInt(), (6 * d).toInt()) }
        fun btn(icon: Int, f: () -> Unit) = acts.addView(IconButton(context, icon, skin.modFg, 21f) { f() }.apply {
            background = roundBg(skin.modBg, 16 * d)
        }, lp(0, (38 * d).toInt(), 1f).apply { marginEnd = (6 * d).toInt() })
        btn(R.drawable.ic_select_all) { val all = chosen.all { it }; chosen.fill(!all); flow.invalidate() }
        btn(R.drawable.ic_copy) { onCopy(selected()) }
        btn(R.drawable.ic_insert) { onInsert(selected()) }
        btn(R.drawable.ic_close) { onClose() }
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
        private var dragging = false
        private var target = true

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
                MotionEvent.ACTION_DOWN -> {
                    val i = boxes.indexOfFirst { it.contains(e.x, e.y) }
                    dragging = i >= 0
                    if (dragging) {
                        // 从一个词开始按：滑过的词都设成和它一样（选上或取消），不让外面的列表滚动
                        target = !chosen[i]
                        chosen[i] = target
                        parent?.requestDisallowInterceptTouchEvent(true)
                        invalidate()
                    }
                }
                MotionEvent.ACTION_MOVE -> if (dragging) {
                    val i = boxes.indexOfFirst { it.contains(e.x, e.y) }
                    if (i >= 0 && chosen[i] != target) { chosen[i] = target; invalidate() }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> dragging = false
            }
            return true
        }
    }
}
