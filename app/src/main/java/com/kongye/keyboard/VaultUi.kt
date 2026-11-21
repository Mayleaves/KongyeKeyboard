package com.kongye.keyboard

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.RippleDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/** 在键盘工具栏显示待确认的保存操作，沿用系统账号候选的胶囊样式。 */
internal class VaultSaveBar(context: Context) : LinearLayout(context) {
    private val d = resources.displayMetrics.density
    private val title = label("保存到空叶文档",14f)
    private val details = label("",12f)
    private val discard = label("不保存",14f)
    private val save = LinearLayout(context)
    private var saveAction: VaultFill.Action? = null
    private var discardAction: VaultFill.Action? = null
    private var palette: Skin? = null

    init {
        visibility = GONE
        gravity = Gravity.CENTER_VERTICAL
        setPaddingRelative((6*d).toInt(),(4*d).toInt(),(6*d).toInt(),(4*d).toInt())
        save.apply {
            gravity = Gravity.CENTER_VERTICAL
            setPaddingRelative((8*d).toInt(),0,(8*d).toInt(),0)
            addView(ImageView(context).apply {
                setImageResource(R.drawable.ic_key)
                setColorFilter(Color.rgb(65,127,249))
                scaleX=-1f
            },lp((20*d).toInt(),(20*d).toInt()))
            addView(title,lp(WRAP,WRAP).apply {marginStart=(4*d).toInt()})
            addView(details,lp(0,WRAP,1f).apply {marginStart=(4*d).toInt()})
            importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_YES
            setOnClickListener { saveAction?.run?.invoke() }
        }
        addView(save,lp(0,MATCH,1f))
        discard.setPaddingRelative((8*d).toInt(),0,(8*d).toInt(),0)
        discard.setOnClickListener { discardAction?.run?.invoke() }
        discard.importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_YES
        addView(discard,lp(WRAP,MATCH).apply {marginStart=(6*d).toInt()})
    }

    private fun label(value: String, size: Float) = TextView(context).apply {
        text = value
        textSize = size
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
        gravity = Gravity.CENTER
        includeFontPadding = false
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    fun bind(skin: Skin, actions: List<VaultFill.Action>): Boolean {
        val current = actions.firstOrNull()?.takeIf { it.saveDetails != null }
        saveAction = current
        discardAction = if (current != null) actions.getOrNull(1) else null
        visibility = if (current != null) VISIBLE else GONE
        save.contentDescription = current?.label
        details.text = current?.saveDetails.orEmpty()
        if (current == null) return false
        if (palette != skin) {
            palette = skin
            val surface = if (skin.dark) skin.keyBg else Color.WHITE
            fun chip() = RippleDrawable(ColorStateList.valueOf(withAlpha(skin.keyFg,20)),
                roundBg(surface,32*d).apply { setStroke(d.toInt().coerceAtLeast(1),withAlpha(skin.keyFg,31)) },null)
            setBackgroundColor(skin.kbBg)
            save.background = chip()
            discard.background = chip()
            title.setTextColor(skin.keyFg)
            details.setTextColor(skin.barFg)
            discard.setTextColor(skin.keyFg)
        }
        return true
    }
}

/**
 * 「密码空间」面板（需求文档 v2 第 9.2 节，样式参照下拉框）：
 * 每行首字头像 + 账号（空叶文档给的打码账号）+「名称 · ********」；注册页最上面是「生成随机密码」，最下面是「更多密码」（搜索）。
 * Search results contain metadata only. Never create a password TextView.
 */
class VaultPanel(
    context: Context, skin: Skin, entries: List<VaultFill.Entry>,
    onPick: (VaultFill.Entry) -> Unit, onSearch: () -> Unit, onBack: () -> Unit,
    title: String = "密码空间", canGenerate: Boolean = false, onGenerate: () -> Unit = {},
    canRemember:Boolean=false,onRememberedPick:(VaultFill.Entry)->Unit=onPick,
) : LinearLayout(context) {
    init {
        val d = resources.displayMetrics.density
        orientation = VERTICAL
        val head = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL; setPadding((8 * d).toInt(), 0, (8 * d).toInt(), 0) }
        head.addView(IconButton(context, R.drawable.ic_chevron_left, skin.barFg, 22f, onBack), lp((42 * d).toInt(), (40 * d).toInt()))
        head.addView(TextView(context).apply { text = title; textSize = 15f; setTextColor(skin.keyFg) }, lp(0, WRAP, 1f))
        head.addView(IconButton(context, R.drawable.ic_search, skin.barFg, 22f, onSearch).apply { contentDescription = "搜索密码空间" }, lp((42 * d).toInt(), (40 * d).toInt()))
        addView(head, lp(MATCH, WRAP))
        var remember=false
        if(canRemember) addView(android.widget.CheckBox(context).apply {
            text="记住所选账号用于此 App";textSize=13f;setTextColor(skin.barFg)
            setPadding((12*d).toInt(),0,(12*d).toInt(),0)
            contentDescription="记住用于此 App，默认关闭；关闭时仅本次填入"
            setOnCheckedChangeListener {_,checked ->remember=checked}
        },lp(MATCH,(38*d).toInt()))
        addView(TextView(context).apply {text=if(canRemember) "默认仅本次填入；选错后可重新搜索其他账号。" else "网页账号不按浏览器 App 关联。";textSize=11.5f;setTextColor(skin.barFg);setPadding((14*d).toInt(),0,(10*d).toInt(),(5*d).toInt())},lp(MATCH,WRAP))
        val list = LinearLayout(context).apply { orientation = VERTICAL; setPadding((12 * d).toInt(), 0, (12 * d).toInt(), (8 * d).toInt()) }

        fun row(avatar: String, line1: String, line2: String, warn: Boolean = false, click: () -> Unit) = LinearLayout(context).apply {
            orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            background = roundBg(skin.keyBg, 12 * d)
            setPadding((12 * d).toInt(), (6 * d).toInt(), (12 * d).toInt(), (6 * d).toInt())
            contentDescription = "$line1 $line2"
            addView(TextView(context).apply {
                text = avatar; textSize = 15f; gravity = Gravity.CENTER; typeface = Typeface.DEFAULT_BOLD
                setTextColor(skin.toolHiFg); background = roundBg(skin.toolHiBg, 16 * d)
            }, lp((32 * d).toInt(), (32 * d).toInt()))
            addView(LinearLayout(context).apply {
                orientation = VERTICAL; setPadding((12 * d).toInt(), 0, 0, 0)
                addView(TextView(context).apply { text = line1; textSize = 15f; setTextColor(skin.keyFg); maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END })
                addView(TextView(context).apply { text = line2; visibility=if(line2.isBlank())android.view.View.GONE else android.view.View.VISIBLE; textSize = 12.5f; setTextColor(if (warn) 0xFFE5534B.toInt() else skin.barFg); maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END })
            }, lp(0, WRAP, 1f))
            setOnClickListener { click() }
        }

        if (canGenerate) list.addView(row("+", "生成随机密码", "") { onGenerate() }, lp(MATCH, (54 * d).toInt()).apply { bottomMargin = (6 * d).toInt() })
        if (entries.isEmpty() && !canGenerate) list.addView(TextView(context).apply {
            text = "没有找到匹配项，点「更多密码」搜索"; textSize = 14f; setTextColor(skin.barFg); gravity = Gravity.CENTER; setPadding(0, (20 * d).toInt(), 0, (12 * d).toInt())
        })
        entries.forEach { entry ->
            val line1 = entry.account.ifEmpty { entry.name }
            val line2 = if (entry.certMismatch) "${entry.name} · ⚠ 签名和上次不同" else "${entry.name} · ********"
            list.addView(row(entry.name.trim().take(1).ifEmpty { "#" }, line1, line2, entry.certMismatch) {if(remember)onRememberedPick(entry) else onPick(entry)},
                lp(MATCH, (54 * d).toInt()).apply { bottomMargin = (6 * d).toInt() })
        }
        list.addView(TextView(context).apply {
            text = "更多密码"; textSize = 15f; setTextColor(skin.keyFg); gravity = Gravity.CENTER_VERTICAL
            setPadding((14 * d).toInt(), 0, (14 * d).toInt(), 0); background = roundBg(skin.keyBg, 12 * d)
            contentDescription = "更多密码，搜索密码空间"
            setOnClickListener { onSearch() }
        }, lp(MATCH, (46 * d).toInt()))
        addView(ScrollView(context).apply { addView(list) }, lp(MATCH, 0, 1f))
    }
}
