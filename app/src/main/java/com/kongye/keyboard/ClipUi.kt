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
    private val onCustom: () -> Unit = {},
    private val onDel: () -> Unit,
) : LinearLayout(context) {
    private val d = resources.displayMetrics.density
    private val pager = CategoryPager(context)
    private val subRow = LinearLayout(context)
    private val headerAction=TextView(context)
    private val tabs = ArrayList<TextView>()
    private var main = startTab
    private var emoji = if (recent.isEmpty()) 1 else 0
    private var kao = 0

    init {
        orientation = VERTICAL
        addView(LinearLayout(context).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding((10*d).toInt(), (4*d).toInt(), (10*d).toInt(), 0)
            addView(segmented(context, skin, listOf("Emoji", "颜文字", "收藏"), main) {
                NativeMotion.change(pager) { main = it; onTab(it); render() }
            }, lp(WRAP, WRAP))
            headerAction.gravity=Gravity.END or Gravity.CENTER_VERTICAL;headerAction.minHeight=(32*d).toInt()
            headerAction.setOnClickListener {if(main==2) onCustom()}
            addView(headerAction,lp(0,WRAP,1f))
        }, lp(MATCH, WRAP))
        addView(pager, lp(MATCH, 0, 1f))
        subRow.orientation = HORIZONTAL; subRow.gravity = Gravity.CENTER_VERTICAL
        subRow.setPadding((4*d).toInt(), (2*d).toInt(), (4*d).toInt(), (4*d).toInt())
        addView(subRow, lp(MATCH, WRAP))
        render()
    }

    fun updateFavs(newFavs: List<String>) { favs = newFavs; if (main == 2) render() }

    private fun page(index: Int): View {
        val isKao = main == 1 || main == 2 && favs.any { EmojiData.isKaomoji(it) }
        val data = when (main) {
            0 -> EmojiData.emojiTab(index, recent)
            1 -> Kaomoji.CATS[index].second
            else -> favs
        }
        val grid = CellGridView(context).apply {
            columns = if (isKao) 3 else 8; textSize = if (isKao) 15f else 26f
            cellH = if (isKao) 50f else 46f; fg = skin.candFg; downBg = skin.keyDown
            items = data
            onPick = { i -> data.getOrNull(i)?.let(onEmoji) }
            onLong = { i -> data.getOrNull(i)?.let(onFav) }
        }
        return ScrollView(context).apply {
            isVerticalScrollBarEnabled = false
            if (data.isNotEmpty()) addView(grid) else addView(TextView(context).apply {
                text = if (main == 2) "点右上角自定义添加，或在 Emoji、颜文字里长按收藏" else "暂无最近使用的表情"
                gravity = Gravity.CENTER; textSize = 13f; setTextColor(skin.barFg)
                setPadding(0, (30*d).toInt(), 0, 0)
            })
        }
    }

    private fun mark(index: Int) {
        tabs.forEachIndexed { i, tv -> tv.background = if (i == index) roundBg(skin.keyBg, 10*d) else null }
        tabs.getOrNull(index)?.let { tv ->
            (tv.parent?.parent as? HorizontalScrollView)?.smoothScrollTo((tv.left - 40*d).toInt().coerceAtLeast(0), 0)
        }
    }

    private fun render() {
        headerAction.text=if(main==2) "+ 自定义" else "左右滑换分类 · 长按收藏"
        headerAction.textSize=if(main==2) 14f else 11.5f
        headerAction.setTextColor(if(main==2) skin.candHi else skin.barFg)
        tabs.clear(); subRow.removeAllViews()
        val labels = when (main) { 0 -> EmojiData.EMOJI_TABS; 1 -> Kaomoji.CATS.map { it.first }; else -> listOf("收藏") }
        val selected = when (main) { 0 -> emoji; 1 -> kao; else -> 0 }
        val row = LinearLayout(context).apply { orientation = HORIZONTAL }
        labels.forEachIndexed { i, label ->
            val tv = TextView(context).apply {
                text = label; textSize = if (main == 0 && i != 0) 18f else 13f
                setTextColor(skin.candFg); gravity = Gravity.CENTER
                setPadding((13*d).toInt(), 0, (13*d).toInt(), 0)
                setOnClickListener { pager.select(i) }
            }
            tabs.add(tv); row.addView(tv, lp(WRAP, (36*d).toInt()))
        }
        subRow.addView(HorizontalScrollView(context).apply { isHorizontalScrollBarEnabled = false; addView(row) }, lp(0, (36*d).toInt(), 1f))
        subRow.addView(IconButton(context, R.drawable.ic_del, skin.modFg, 22f, onDel), lp((44*d).toInt(), (36*d).toInt()))
        pager.pages(labels.size, selected, ::page) { index ->
            if (main == 0) emoji = index else if (main == 1) kao = index
            mark(index)
        }
        mark(selected)
    }
}

/** 剪贴板管理：两列网格；分类（含自定义）、搜索、置顶、复制、编辑、分词、删除，支持图片 */
class ClipboardPanel(
    context: Context,
    private val skin: Skin,
    tab: String,
    private var query: String,
    private var expanded: Clip?,
    private var picking: Clip?,
    private val onTab: (String) -> Unit,
    onTabLong: (String) -> Unit,
    onNewCat: () -> Unit,
    onNewContent: (String) -> Unit,
    onSearch: () -> Unit,
    onClearQuery: () -> Unit,
    onClear: () -> Unit,
    private val onPaste: (Clip) -> Unit,
    private val onExpand: (Clip?) -> Unit,
    private val onAction: (Clip, String) -> Unit,
) : LinearLayout(context) {
    private val d = resources.displayMetrics.density
    private val overlays = HashMap<Clip, View>()
    private val previewLineHeight = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 13.5f * resources.displayMetrics.scaledDensity
    }.fontMetricsInt.let { it.descent - it.ascent + 2 * d }
    private val cardHeight = (12 * d + 3 * previewLineHeight + 34 * d).toInt()
    private var activeTab = tab
    private val labels = LinkedHashMap<String, TextView>()
    private val tabScroll = HorizontalScrollView(context)
    private val positions = HashMap<String, Int>()
    private var queryRow: View? = null
    private lateinit var addContent: View
    val tabsScrollPosition get() = tabScroll.scrollX
    fun restoreTabsScroll(x: Int) { tabScroll.post { tabScroll.scrollTo(x, 0) } }
    private lateinit var recordsScroll: ScrollView
    val scrollPosition get() = recordsScroll.scrollY
    fun restoreScroll(y: Int) { recordsScroll.post { recordsScroll.scrollTo(0, y) } }

    private fun expand(clip: Clip?) {
        expanded = clip
        overlays.forEach { (entry, view) ->
            view.animate().cancel()
            if (entry === clip) {
                view.visibility = View.VISIBLE
                view.animate().translationY(0f).alpha(1f).setDuration(200).start()
            } else view.animate().translationY(32*d).alpha(0f).setDuration(160).withEndAction { view.visibility = View.INVISIBLE }.start()
        }
        onExpand(clip)
    }

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
            val active = t == activeTab
            val label = TextView(context).apply {
                text = t
                textSize = 14f
                gravity = Gravity.CENTER
                styleTab(this, active)
                setPadding((10 * d).toInt(), 0, (10 * d).toInt(), 0)
                setOnClickListener { selectTab(t) }
                if (t !in ClipStore.FIXED_TABS) setOnLongClickListener { onTabLong(t); true }
            }
            labels[t] = label
            tabs.addView(label, lp(WRAP, (36*d).toInt()))
        }
        // 新建分类：一个小加号
        tabs.addView(IconButton(context, R.drawable.ic_plus, skin.barFg, 18f) { onNewCat() }.apply {
            contentDescription = "新建分类"
        }, lp((34 * d).toInt(), (36 * d).toInt()))
        tabScroll.isHorizontalScrollBarEnabled = false
        tabScroll.addView(tabs)
        head.addView(tabScroll, lp(0, (36*d).toInt(), 1f))
        // 右边三个图标：往当前自定义分类里加内容、搜索、清空；都不加底色
        addContent = IconButton(context, R.drawable.ic_edit, skin.barFg, 19f) { onNewContent(activeTab) }.apply {
            contentDescription = "向当前分类添加内容"
            visibility = if (activeTab in ClipStore.customTabs()) View.VISIBLE else View.GONE
        }
        head.addView(addContent, lp((36 * d).toInt(), (36 * d).toInt()))
        head.addView(IconButton(context, R.drawable.ic_search, skin.barFg, 20f) { onSearch() }.apply { contentDescription = "搜索" },
            lp((36 * d).toInt(), (36 * d).toInt()))
        head.addView(IconButton(context, R.drawable.ic_trash, skin.barFg, 20f) { onClear() }.apply { contentDescription = "清空" },
            lp((36 * d).toInt(), (36 * d).toInt()))
        addView(head, lp(MATCH, WRAP))
        if (query.isNotEmpty()) {
            val q = LinearLayout(context).apply { orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding((14 * d).toInt(), 0, (4 * d).toInt(), 0) }
            q.addView(TextView(context).apply { text = "搜索：$query"; textSize = 13f; setTextColor(skin.candHi) }, lp(0, WRAP, 1f))
            q.addView(IconButton(context, R.drawable.ic_close, skin.barFg, 16f) { onClearQuery() }, lp((36 * d).toInt(), (28 * d).toInt()))
            queryRow = q
            addView(q, lp(MATCH, WRAP))
        }
        recordsScroll = ScrollView(context).apply { isVerticalScrollBarEnabled = false }
        addView(recordsScroll, lp(MATCH, 0, 1f))
        renderRecords()
    }

    private fun selectTab(tab: String) {
        if (tab == activeTab) return
        positions[activeTab] = recordsScroll.scrollY
        activeTab = tab; query = ""; expanded = null; picking = null
        queryRow?.visibility = View.GONE
        labels.forEach { (name, label) -> styleTab(label, name == tab) }
        addContent.visibility = if (tab in ClipStore.customTabs()) View.VISIBLE else View.GONE
        onTab(tab)
        NativeMotion.change(recordsScroll) { renderRecords(); restoreScroll(positions[activeTab] ?: 0) }
    }

    /** 分类标签：选中的是正文色加粗，下面一条短横线；没选中的是灰字 */
    private fun styleTab(v: TextView, active: Boolean) {
        v.setTextColor(if (active) skin.candFg else skin.barFg)
        v.typeface = if (active) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        v.background = if (!active) null else object : android.graphics.drawable.Drawable() {
            private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = skin.candHi }
            override fun draw(c: Canvas) {
                val b = bounds
                val w = 14 * d
                c.drawRoundRect(RectF(b.exactCenterX() - w / 2, b.bottom - 4 * d, b.exactCenterX() + w / 2, b.bottom - 1.5f * d), 2 * d, 2 * d, p)
            }
            override fun setAlpha(alpha: Int) {}
            override fun setColorFilter(cf: android.graphics.ColorFilter?) {}
            @Deprecated("Deprecated in Java")
            override fun getOpacity() = android.graphics.PixelFormat.TRANSLUCENT
        }
    }

    private fun showCat(clip: Clip) = clip.cat != null && clip.cat != "置顶" && clip.cat != activeTab

    /** 右上角标签占的宽度，正文要让开 */
    private fun tagWidth(clip: Clip): Float {
        var w = 0f
        if (clip.pinned) w += 16 * d
        if (showCat(clip)) w += TextPaint().apply { textSize = 10.5f * resources.displayMetrics.scaledDensity }.measureText(clip.cat!!) + 14 * d
        return if (w > 0) w + 2 * d else 0f
    }

    private fun renderRecords() {
        overlays.clear()
        val list = ClipStore.list(activeTab, query)
        val col = LinearLayout(context).apply { orientation = VERTICAL; setPadding((8 * d).toInt(), (2 * d).toInt(), (8 * d).toInt(), (10 * d).toInt()) }
        if (list.isEmpty()) {
            col.addView(TextView(context).apply {
                text = if (ClipStore.items.isEmpty()) "复制的内容会出现在这里" else "暂无记录"
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
                row.addView(card(first), lp(0, cardHeight, 1f).apply { marginStart = (2 * d).toInt(); marginEnd = (4 * d).toInt() })
                if (second == null) row.addView(View(context), lp(0, 1, 1f).apply { marginStart = (4 * d).toInt(); marginEnd = (2 * d).toInt() })
                else row.addView(card(second), lp(0, cardHeight, 1f).apply { marginStart = (4 * d).toInt(); marginEnd = (2 * d).toInt() })
                col.addView(row, lp(MATCH, WRAP).apply { topMargin = (7 * d).toInt() })
                i += 2
            }
        }
        recordsScroll.removeAllViews()
        recordsScroll.addView(col)
    }

    private fun card(clip: Clip): View {
        val card = android.widget.FrameLayout(context).apply {
            background = roundBg(skin.keyBg, 12 * d)
            clipChildren = true; clipToOutline = true
            minimumHeight = cardHeight
            setOnClickListener { if (expanded === clip) expand(null) else onPaste(clip) }
            setOnLongClickListener { expand(if (expanded === clip) null else clip); true }
        }
        val img = ClipStore.imageFile(clip)
        if (img != null && img.exists()) {
            BitmapFactory.decodeFile(img.path, BitmapFactory.Options().apply { inSampleSize = 4 })?.let { bmp ->
                card.addView(ImageView(context).apply { setImageBitmap(bmp); scaleType = ImageView.ScaleType.CENTER_CROP },
                    android.widget.FrameLayout.LayoutParams(MATCH, cardHeight))
            }
        } else card.addView(TextView(context).apply {
            text = clip.text; textSize = 13.5f; maxLines = 4; ellipsize = TextUtils.TruncateAt.END
            includeFontPadding = false
            setLineSpacing(2*d, 1f); setTextColor(skin.keyFg)
            setPadding((12*d).toInt(), (12*d).toInt(), (12*d + tagWidth(clip)).toInt(), 0)
        }, android.widget.FrameLayout.LayoutParams(MATCH, cardHeight))
        // 右上角：置顶图钉和分类小标签（当前就在这个分类里时不重复写）
        if (clip.pinned || showCat(clip)) card.addView(LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            if (clip.pinned) addView(IconButton(context, R.drawable.ic_pin, skin.candHi, 12f) {}.apply {
                contentDescription = "已置顶"; isClickable = false; isFocusable = false
            }, lp((16 * d).toInt(), (16 * d).toInt()))
            if (showCat(clip)) addView(TextView(context).apply {
                text = clip.cat; textSize = 10.5f; setTextColor(skin.barFg); maxLines = 1
                background = roundBg(skin.modBg, 8 * d)
                setPadding((6 * d).toInt(), (1 * d).toInt(), (6 * d).toInt(), (1 * d).toInt())
            }, lp(WRAP, WRAP).apply { marginStart = (2 * d).toInt() })
        }, android.widget.FrameLayout.LayoutParams(WRAP, (20 * d).toInt(), Gravity.TOP or Gravity.END).apply {
            topMargin = (8 * d).toInt(); rightMargin = (8 * d).toInt()
        })
        val tools = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            // 只盖住卡片最下面一条，上面的正文还能看到两三行
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(if(skin.dark) mix(skin.kbBg,Color.BLACK,.2f) or (0xFF shl 24) else mix(skin.kbBg,Color.WHITE,.6f) or (0xFF shl 24))
                cornerRadii=floatArrayOf(0f,0f,0f,0f,12*d,12*d,12*d,12*d)
            }
            setPadding((4*d).toInt(), 0, (4*d).toInt(), 0)
        }
        if (picking === clip) {
            // 选分类：一行可以横滑的小标签
            val categories = listOf("无分类") + ClipStore.customTabs()
            val cats = LinearLayout(context).apply { orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            if (categories.size == 1) cats.addView(TextView(context).apply {
                text = "先点顶部 + 新建分类"; textSize = 11f; setTextColor(skin.barFg)
            })
            categories.forEach { cat -> cats.addView(actButton(cat, false) { onAction(clip, "cat:$cat") }, lp(WRAP, (28*d).toInt()).apply { marginEnd = (4*d).toInt() }) }
            tools.addView(HorizontalScrollView(context).apply { isHorizontalScrollBarEnabled = false; addView(cats) }, lp(MATCH, MATCH))
        } else {
            val acts = listOf(
                Triple("pin", R.drawable.ic_pin, if (clip.pinned) "取消置顶" else "置顶"),
                Triple("copy", R.drawable.ic_copy, "复制"), Triple("edit", R.drawable.ic_edit, "编辑"),
                Triple("split", R.drawable.ic_split, "分词"), Triple("cat", R.drawable.ic_tag, "分类"), Triple("delete", R.drawable.ic_trash, "删除"))
            acts.forEach { (id, icon, label) ->
                tools.addView(IconButton(context, icon, when {
                    id == "pin" && clip.pinned -> skin.candHi
                    id == "delete" -> Color.parseColor("#E5484D")
                    else -> skin.modFg
                }, 17f) { onAction(clip, id) }.apply {
                    contentDescription = label
                    isEnabled = clip.image == null || id !in listOf("edit", "split")
                    alpha = if (isEnabled) 1f else .3f
                }, lp(0, (34*d).toInt(), 1f))
            }
        }
        card.addView(tools, android.widget.FrameLayout.LayoutParams(MATCH, (40*d).toInt(), Gravity.BOTTOM))
        overlays[clip] = tools
        tools.visibility = if (expanded === clip) View.VISIBLE else View.INVISIBLE
        tools.alpha = if (expanded === clip) 1f else 0f
        tools.translationY = if (expanded === clip) 0f else 32*d
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
        set(value) { if(field!=value) { field=value;requestLayout() } }
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
        if (multiline) requestLayout()
        if (textChanged && multiline) {
            post {
                scrollOffsetY = maxScrollY
                invalidate()
            }
        }
        invalidate()
    }

    private var motion=0
    /** 只对绘制做动画；输入法窗口在开始/结束时各布局一次，不逐帧变高度。 */
    fun showAnimated(show:Boolean, animate:Boolean=true) {
        val generation=++motion
        animate().cancel()
        isEnabled=show
        if(!animate || !android.animation.ValueAnimator.areAnimatorsEnabled()) {
            visibility=if(show) View.VISIBLE else View.GONE
            alpha=1f;translationY=0f;return
        }
        if(show) {
            visibility=View.VISIBLE;alpha=0f;translationY=8*d
            post {
                if(generation==motion) animate().alpha(1f).translationY(0f).setDuration(200)
                    .setInterpolator(android.view.animation.DecelerateInterpolator(1.5f)).withLayer().start()
            }
        } else if(visibility==View.VISIBLE) {
            animate().alpha(0f).translationY(8*d).setDuration(160).withLayer().withEndAction {
                if(generation==motion) { visibility=View.GONE;alpha=1f;translationY=0f }
            }.start()
        }
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
        val lay = if(multiline) layoutFor(w) else null
        val lines = if (lay != null) min(5, max(2, lay.lineCount)) else 1
        val lh = 21 * d
        val h = (lines * lh + 26 * d).toInt()
        val viewportH = h - 22 * d
        maxScrollY = max(0f, (lay?.height ?: 0) - viewportH)
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
