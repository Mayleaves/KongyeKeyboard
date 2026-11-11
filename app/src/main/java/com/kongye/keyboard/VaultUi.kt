package com.kongye.keyboard

import android.content.Context
import android.graphics.Typeface
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * 「密码空间」面板（需求文档 v2 第 9.2 节，样式参照下拉框）：
 * 每行首字头像 + 账号（空叶文档给的打码账号）+「名称 · ********」；注册页最上面是「生成强密码」，最下面是「更多密码」（搜索）。
 * Search results contain metadata only. Never create a password TextView.
 */
class VaultPanel(
    context: Context, skin: Skin, entries: List<VaultFill.Entry>,
    onPick: (VaultFill.Entry) -> Unit, onSearch: () -> Unit, onBack: () -> Unit,
    title: String = "密码空间", canGenerate: Boolean = false, onGenerate: () -> Unit = {},
) : LinearLayout(context) {
    init {
        val d = resources.displayMetrics.density
        orientation = VERTICAL
        val head = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL; setPadding((8 * d).toInt(), 0, (8 * d).toInt(), 0) }
        head.addView(IconButton(context, R.drawable.ic_chevron_left, skin.barFg, 22f, onBack), lp((42 * d).toInt(), (40 * d).toInt()))
        head.addView(TextView(context).apply { text = title; textSize = 15f; setTextColor(skin.keyFg) }, lp(0, WRAP, 1f))
        head.addView(IconButton(context, R.drawable.ic_search, skin.barFg, 22f, onSearch).apply { contentDescription = "搜索密码空间" }, lp((42 * d).toInt(), (40 * d).toInt()))
        addView(head, lp(MATCH, WRAP))
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
                addView(TextView(context).apply { text = line2; textSize = 12.5f; setTextColor(if (warn) 0xFFE5534B.toInt() else skin.barFg); maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END })
            }, lp(0, WRAP, 1f))
            setOnClickListener { click() }
        }

        if (canGenerate) list.addView(row("+", "生成强密码", "按页面提示的要求生成，填入新密码框") { onGenerate() }, lp(MATCH, (54 * d).toInt()).apply { bottomMargin = (6 * d).toInt() })
        if (entries.isEmpty() && !canGenerate) list.addView(TextView(context).apply {
            text = "没有找到匹配项，点「更多密码」搜索"; textSize = 14f; setTextColor(skin.barFg); gravity = Gravity.CENTER; setPadding(0, (20 * d).toInt(), 0, (12 * d).toInt())
        })
        entries.forEach { entry ->
            val line1 = entry.account.ifEmpty { entry.name }
            val line2 = if (entry.certMismatch) "${entry.name} · ⚠ 签名和上次不同" else "${entry.name} · ********"
            list.addView(row(entry.name.trim().take(1).ifEmpty { "#" }, line1, line2, entry.certMismatch) { onPick(entry) },
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
