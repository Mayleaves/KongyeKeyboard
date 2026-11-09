package com.kongye.keyboard

import android.Manifest
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
import android.view.inputmethod.InputMethodManager
import android.widget.LinearLayout
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import kotlin.math.roundToInt

private class Pal(
    val bg: Color, val card: Color, val ink: Color, val text2: Color, val line: Color, val tile: Color,
    val track: Color, val accent: Color, val onAccent: Color, val ok: Color, val bad: Color,
)

private val LIGHT = Pal(Color(0xFFF2F2F4), Color(0xFFFAFAFB), Color(0xFF1C1C1E), Color(0xFF6E6E73), Color(0xFFE6E6EA), Color(0xFFEDEDF0), Color(0xFFDCDCE0), Color(0xFFF0492C), Color.White, Color(0xFF1E8E3E), Color(0xFFD93025))
private val DARK = Pal(Color(0xFF0E0E10), Color(0xFF1A1A1C), Color(0xFFF2F2F2), Color(0xFF9A9AA0), Color(0xFF2A2A2D), Color(0xFF26262A), Color(0xFF3A3A3D), Color(0xFFFF5A3C), Color(0xFF140806), Color(0xFF6DD58C), Color(0xFFFF7A70))

private enum class Page(val title: String) {
    MAIN("空叶键盘 设置"), LAYOUT("键盘布局"), KEYS("按键与手势"), PINYIN("拼音"), LOOK("主题与外观"), HEIGHT("键盘高度"),
    ARRANGE("工具栏与菜单"), CLIP("剪贴板"), TIPS("小技巧"), TRY("试一试")
}

/** 主页上的一行：可以被搜索 */
private class Item(val section: String, val icon: Int, val title: String, val sub: String, val value: String?, val valueColor: Color?, val onClick: () -> Unit)

class SettingsActivity : ComponentActivity() {
    private val tick = mutableIntStateOf(0)
    private val prefListener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> tick.intValue++ }
    private var confirmAt = 0L
    private var confirmWhat = ""
    private val micRequest = registerForActivityResult(ActivityResultContracts.RequestPermission()) { tick.intValue++ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        ClipStore.load(this)
        Prefs.of(this).registerOnSharedPreferenceChangeListener(prefListener)
        if (intent?.getBooleanExtra("grant_mic", false) == true && !micGranted()) micRequest.launch(Manifest.permission.RECORD_AUDIO)
        setContent { Screen(tick.intValue) }
    }

    override fun onDestroy() {
        Prefs.of(this).unregisterOnSharedPreferenceChangeListener(prefListener)
        super.onDestroy()
    }

    override fun onResume() { super.onResume(); tick.intValue++ }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) tick.intValue++
    }

    private fun micGranted() = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
    private fun isEnabled() = getSystemService(InputMethodManager::class.java)?.enabledInputMethodList?.any { it.packageName == packageName } == true
    private fun isCurrent() = Settings.Secure.getString(contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)?.startsWith("$packageName/") == true

    private fun confirmed(what: String): Boolean {
        val now = SystemClock.uptimeMillis()
        val ok = confirmWhat == what && now - confirmAt < 3000
        confirmWhat = if (ok) "" else what
        confirmAt = now
        return ok
    }

    @Composable
    private fun Screen(tick: Int) {
        val pal = if (isSystemInDarkTheme()) DARK else LIGHT
        var page by remember { mutableStateOf(Page.MAIN) }
        BackHandler(enabled = page != Page.MAIN) { page = Page.MAIN }
        Column(
            Modifier.fillMaxSize().background(pal.bg).windowInsetsPadding(WindowInsets.safeDrawing)
                .verticalScroll(rememberScrollState()).padding(horizontal = 18.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (page == Page.MAIN) MainPage(pal, tick) { page = it }
            else {
                Row(Modifier.clickable { page = Page.MAIN }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Image(painterResource(R.drawable.ic_chevron_left), null, Modifier.size(26.dp), colorFilter = ColorFilter.tint(pal.ink))
                    Spacer(Modifier.width(6.dp))
                    BasicText(page.title, style = TextStyle(color = pal.ink, fontSize = 24.sp, fontWeight = FontWeight.Bold))
                }
                when (page) {
                    Page.LAYOUT -> LayoutPage(pal, tick)
                    Page.KEYS -> KeysPage(pal, tick)
                    Page.PINYIN -> PinyinPage(pal, tick)
                    Page.LOOK -> LookPage(pal, tick)
                    Page.HEIGHT -> HeightPage(pal, tick)
                    Page.ARRANGE -> ArrangePage(pal, tick)
                    Page.CLIP -> ClipPage(pal, tick)
                    Page.TIPS -> TipsPage(pal)
                    Page.TRY -> TryPage(pal)
                    Page.MAIN -> Unit
                }
            }
            Spacer(Modifier.height(16.dp))
        }
    }

    @Composable
    private fun MainPage(pal: Pal, tick: Int, go: (Page) -> Unit) {
        val p = Prefs.of(this)
        val enabled = remember(tick) { isEnabled() }
        val current = remember(tick) { isCurrent() }
        val mic = remember(tick) { micGranted() }
        val today = remember(tick) { Prefs.typedToday(this) }
        val theme = remember(tick) { Prefs.theme(this) }
        val layout = remember(tick) { Prefs.layout(this) }
        val scale = remember(tick) { Prefs.heightScale(this) }
        val floating = remember(tick) { p.getBoolean(Prefs.FLOAT, false) }
        var query by remember { mutableStateOf("") }

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            BasicText("今天 $today 字", style = TextStyle(color = pal.text2, fontSize = 14.sp))
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Image(painterResource(R.drawable.ic_leaf), null, Modifier.size(28.dp), colorFilter = ColorFilter.tint(pal.accent))
            Spacer(Modifier.width(8.dp))
            BasicText("空叶键盘 设置", style = TextStyle(color = pal.ink, fontSize = 32.sp, fontWeight = FontWeight.Normal))
        }
        // 搜索
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(28.dp)).background(pal.tile).padding(horizontal = 18.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Image(painterResource(R.drawable.ic_search), null, Modifier.size(22.dp), colorFilter = ColorFilter.tint(pal.text2))
            Spacer(Modifier.width(12.dp))
            BasicTextField(
                value = query, onValueChange = { query = it }, singleLine = true,
                textStyle = TextStyle(color = pal.ink, fontSize = 16.sp), cursorBrush = SolidColor(pal.accent),
                modifier = Modifier.weight(1f),
                decorationBox = { inner -> Box { if (query.isEmpty()) BasicText("搜索设置，如“震动”“皮肤”“剪贴板”", style = TextStyle(color = pal.text2, fontSize = 16.sp)); inner() } },
            )
        }
        val ok = pal.ok
        val bad = pal.bad
        val items = listOf(
            Item("配置选择", R.drawable.ic_kbd, "启用输入法", "在系统设置中启用空叶键盘", if (enabled) "已设置" else "未设置", if (enabled) ok else bad) { startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS)) },
            Item("配置选择", R.drawable.ic_globe, "选择输入法", "把空叶键盘设为当前输入法", if (current) "已设置" else "未设置", if (current) ok else bad) { getSystemService(InputMethodManager::class.java)?.showInputMethodPicker() },
            Item("配置选择", R.drawable.ic_mic, "麦克风权限", "语音输入要用", if (mic) "已授权" else "未授权", if (mic) ok else bad) { if (!mic) micRequest.launch(Manifest.permission.RECORD_AUDIO) },
            Item("配置选择", R.drawable.ic_edit, "试一试", "打开一个输入框试打", null, null) { go(Page.TRY) },
            Item("输入", R.drawable.ic_kbd, "键盘布局", "26 键、14 键、九键", mapOf("26" to "26 键", "14" to "14 键", "9" to "九键")[layout], null) { go(Page.LAYOUT) },
            Item("输入", R.drawable.ic_adjust, "按键与手势", "震动、按键音、上下滑输入符号、删除键手势", null, null) { go(Page.KEYS) },
            Item("输入", R.drawable.ic_translate, "拼音", "简拼、联想、纠错、记住选词", null, null) { go(Page.PINYIN) },
            Item("外观", R.drawable.ic_palette, "主题与外观", "配色方案、深色模式", theme.name, null) { go(Page.LOOK) },
            Item("外观", R.drawable.ic_float, "键盘高度", "拖动调节", "${(scale * 100).roundToInt()}%", null) { go(Page.HEIGHT) },
            Item("外观", R.drawable.ic_float, "悬浮键盘", "键盘浮在屏幕上，可以拖动", if (floating) "开" else "关", null) { p.edit().putBoolean(Prefs.FLOAT, !floating).apply() },
            Item("工具", R.drawable.ic_toolbar, "工具栏与菜单", "长按拖动排列顺序和位置", null, null) { go(Page.ARRANGE) },
            Item("工具", R.drawable.ic_clip, "剪贴板", "最多 ${ClipStore.MAX} 条，分类、图片", "${ClipStore.items.size} 条", null) { go(Page.CLIP) },
            Item("关于", R.drawable.ic_settings, "小技巧", "滑动输入、分词、光标、手写", null, null) { go(Page.TIPS) },
        )
        val shown = items.filter { query.isBlank() || it.title.contains(query) || it.sub.contains(query) || it.section.contains(query) }
        for ((section, rows) in shown.groupBy { it.section }) {
            BasicText(section, style = TextStyle(color = pal.text2, fontSize = 15.sp), modifier = Modifier.padding(start = 4.dp, top = 6.dp))
            Card(pal) {
                rows.forEachIndexed { i, it ->
                    if (i > 0) Line(pal, start = 66.dp)
                    NavRow(pal, it)
                }
            }
        }
        if (shown.isEmpty()) BasicText("没有找到“$query”", style = TextStyle(color = pal.text2, fontSize = 15.sp), modifier = Modifier.padding(16.dp))
        BasicText("空叶键盘 1.2.0", style = TextStyle(color = pal.text2, fontSize = 12.sp), modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
    }

    @Composable
    private fun TryPage(pal: Pal) {
        var test by remember { mutableStateOf("") }
        Card(pal) {
            BasicTextField(
                value = test, onValueChange = { test = it },
                textStyle = TextStyle(color = pal.ink, fontSize = 17.sp), cursorBrush = SolidColor(pal.accent),
                modifier = Modifier.fillMaxWidth().height(160.dp).padding(6.dp),
                decorationBox = { inner -> Box { if (test.isEmpty()) BasicText("在这里试打，比如 nihao、zg、kongye", style = TextStyle(color = pal.text2, fontSize = 17.sp)); inner() } },
            )
        }
    }

    @Composable
    private fun LayoutPage(pal: Pal, tick: Int) {
        val p = Prefs.of(this)
        val skin = remember(tick) { Prefs.skin(this) }
        val cur = remember(tick) { Prefs.layout(this) }
        Card(pal) {
            AndroidView(
                factory = { ctx -> LayoutPicker(ctx, skin, cur) { p.edit().putString(Prefs.LAYOUT, it).apply() } },
                update = { },
                modifier = Modifier.fillMaxWidth().height(200.dp),
            )
        }
        key(tick) { Preview(pal, tick) }
    }

    @Composable
    private fun key(k: Int, content: @Composable () -> Unit) = androidx.compose.runtime.key(k) { content() }

    @Composable
    private fun KeysPage(pal: Pal, tick: Int) {
        val p = Prefs.of(this)
        val vib = remember(tick) { p.getInt(Prefs.VIB_STRENGTH, 2) }
        val sound = remember(tick) { p.getBoolean(Prefs.SOUND, false) }
        val swipe = remember(tick) { p.getBoolean(Prefs.SWIPE, true) }
        Card(pal) {
            Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                BasicText("按键震动", style = TextStyle(color = pal.ink, fontSize = 16.sp), modifier = Modifier.weight(1f))
                Segment(pal, listOf("关", "弱", "中", "强"), vib) { p.edit().putInt(Prefs.VIB_STRENGTH, it).apply() }
            }
            Line(pal)
            ToggleRow(pal, "按键音", null, sound) { p.edit().putBoolean(Prefs.SOUND, it).apply() }
            Line(pal)
            ToggleRow(pal, "上下滑输入符号", "按住字母往下滑输入右上角的符号，往上滑输入左下角的", swipe) { p.edit().putBoolean(Prefs.SWIPE, it).apply() }
            Line(pal)
            Tip(pal, "删除键", "往下滑清空整个输入框，往上滑撤销。撤销、重做和空叶文档一样：连续打字停顿不到 1 秒算一步，最多记 100 步。")
            Tip(pal, "空格键", "左右滑移动光标；长按进入光标模式，手指在屏幕任意位置上下左右滑。")
        }
    }

    @Composable
    private fun PinyinPage(pal: Pal, tick: Int) {
        val p = Prefs.of(this)
        val r = remember(tick) { listOf(Prefs.ABBR, Prefs.ASSOC, Prefs.LEARN).associateWith { p.getBoolean(it, true) } }
        var forgetHint by remember { mutableStateOf("") }
        Card(pal) {
            ToggleRow(pal, "简拼", "只打声母也能出词：zg → 中国", r[Prefs.ABBR]!!) { p.edit().putBoolean(Prefs.ABBR, it).apply() }
            Line(pal)
            ToggleRow(pal, "联想", "上屏后推荐接下来的词", r[Prefs.ASSOC]!!) { p.edit().putBoolean(Prefs.ASSOC, it).apply() }
            Line(pal)
            ToggleRow(pal, "记住选词", "选过的词排到前面，分几次选出的整句记成新词；密码框里打的字不记录", r[Prefs.LEARN]!!) { p.edit().putBoolean(Prefs.LEARN, it).apply() }
            Line(pal)
            Tip(pal, "纠错", "打错一个字母时，候选里会给出正确的词，后面带拼音，打错的字母标红。")
            Line(pal, top = 10.dp)
            ActionRow(pal, "清除选词记录", forgetHint.ifEmpty { "只删学到的东西，不影响词库" }, "清除") {
                if (confirmed("forget")) { Lexicon.clearUser(this@SettingsActivity); forgetHint = "已清除" } else forgetHint = "再点一次“清除”确认"
            }
        }
    }

    @Composable
    private fun Preview(pal: Pal, tick: Int, scaleOverride: Float? = null) {
        val skin = remember(tick) { Prefs.skin(this) }
        val layout = remember(tick) { Prefs.layout(this) }
        Card(pal) {
            AndroidView(
                factory = { ctx ->
                    LinearLayout(ctx).apply {
                        orientation = LinearLayout.VERTICAL
                        addView(CandidateBar(ctx))
                        addView(KeyboardView(ctx).apply { preview = true })
                    }
                },
                update = { ll ->
                    ll.background = if (skin.bg2 != 0) android.graphics.drawable.GradientDrawable(android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(skin.kbBg, skin.bg2))
                    else android.graphics.drawable.ColorDrawable(skin.kbBg)
                    (ll.getChildAt(0) as CandidateBar).apply { this.skin = skin; showTools(listOf("more") + Prefs.tools(this@SettingsActivity)) }
                    (ll.getChildAt(1) as KeyboardView).apply {
                        heightScale = scaleOverride ?: Prefs.heightScale(this@SettingsActivity)
                        zh = true
                        val rows = when (layout) { "14" -> Layouts.fourteen(); "9" -> Layouts.nine(); else -> Layouts.letters(true) }
                        setup(skin, rows, if (layout == "9") Layouts.NINE_SIDE else null)
                    }
                },
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)),
            )
        }
    }

    @Composable
    private fun LookPage(pal: Pal, tick: Int) {
        val p = Prefs.of(this)
        val theme = remember(tick) { Prefs.theme(this) }
        val mode = remember(tick) { p.getInt(Prefs.MODE, 0) }
        Preview(pal, tick)
        Card(pal) {
            Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                BasicText("深色模式", style = TextStyle(color = pal.ink, fontSize = 16.sp), modifier = Modifier.weight(1f))
                Segment(pal, listOf("跟随系统", "浅色", "深色"), mode) { p.edit().putInt(Prefs.MODE, it).apply() }
            }
        }
        BasicText("配色方案", style = TextStyle(color = pal.text2, fontSize = 15.sp), modifier = Modifier.padding(start = 4.dp))
        BasicText("点一下就换。每套都有浅色和深色两个版本（卡片左半、右半），跟随系统时白天黑夜自动切换；动态配色跟着壁纸变。", style = TextStyle(color = pal.text2, fontSize = 12.5.sp, lineHeight = 18.sp), modifier = Modifier.padding(start = 4.dp))
        Card(pal) {
            val labelFg = pal.ink.toArgb()
            val hi = pal.accent.toArgb()
            val line = pal.line.toArgb()
            AndroidView(
                factory = { ctx -> ThemeGridView(ctx, Themes.all(ctx), theme.id, labelFg, hi, line, 3) { t -> p.edit().putString(Prefs.THEME, t.id).apply() } },
                update = { it.selected = theme.id; it.invalidate() },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }

    @Composable
    private fun HeightPage(pal: Pal, tick: Int) {
        val p = Prefs.of(this)
        var scale by remember { mutableFloatStateOf(Prefs.heightScale(this)) }
        Preview(pal, tick, scaleOverride = scale)
        Card(pal) {
            BasicText("键盘高度 ${(scale * 100).roundToInt()}%", style = TextStyle(color = pal.ink, fontSize = 16.sp, fontWeight = FontWeight.Medium))
            Spacer(Modifier.height(14.dp))
            Slider(pal, scale, 0.7f, 1.5f, { scale = it }) { p.edit().putFloat(Prefs.HEIGHT_SCALE, scale).apply() }
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Pill(pal, "恢复默认", false) { scale = 1f; p.edit().putFloat(Prefs.HEIGHT_SCALE, 1f).apply() }
            }
            Spacer(Modifier.height(6.dp))
            BasicText("在键盘上也能调：更多 → 键盘调节，拖动最上面的手柄。", style = TextStyle(color = pal.text2, fontSize = 12.5.sp))
        }
    }

    @Composable
    private fun ArrangePage(pal: Pal, tick: Int) {
        val skin = remember(tick) { Prefs.skin(this) }
        val tools = remember(tick) { Prefs.tools(this) }
        val menu = remember(tick) { Prefs.menu(this) }
        Card(pal) {
            key(tick) {
                AndroidView(
                    factory = { ctx -> ArrangeView(ctx, skin, tools, menu, pal.ink.toArgb(), pal.text2.toArgb()) { t, m -> Prefs.saveArrangement(ctx, t, m) } },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }

    @Composable
    private fun ClipPage(pal: Pal, tick: Int) {
        var hint by remember { mutableStateOf("") }
        val count = remember(tick, hint) { ClipStore.items.size }
        val pinned = remember(tick, hint) { ClipStore.items.count { it.pinned } }
        val images = remember(tick, hint) { ClipStore.items.count { it.image != null } }
        Card(pal) {
            BasicText("共 $count 条（置顶 $pinned 条，图片 $images 张），最多保存 ${ClipStore.MAX} 条，满了先挤掉最旧的；置顶的不会被挤掉。", style = TextStyle(color = pal.ink, fontSize = 15.sp, lineHeight = 22.sp))
            BasicText("点键盘右下角的剪贴板图标打开。只记录用空叶键盘期间复制的内容，存在这台手机里。分类可以自己加：在剪贴板顶上点“＋分类”。", style = TextStyle(color = pal.text2, fontSize = 12.5.sp, lineHeight = 18.sp), modifier = Modifier.padding(top = 6.dp))
            Line(pal, top = 12.dp)
            ActionRow(pal, "清空没置顶的记录", hint.ifEmpty { null }, "清空") {
                if (confirmed("clips")) { ClipStore.clearUnpinned(); hint = "已清空" } else hint = "再点一次“清空”确认"
            }
        }
    }

    @Composable
    private fun TipsPage(pal: Pal) {
        Card(pal) {
            Tip(pal, "上下滑输入符号", "按住字母往下滑，右上角的符号会移到中间，松手输入它；往上滑是左下角的。长按也输入右上角的。")
            Tip(pal, "删除键", "往下滑清空整个输入框，往上滑撤销。")
            Tip(pal, "分词", "打中文时大写键会变成“分词”，比如 xi 分词 an 出“西安”。")
            Tip(pal, "移动光标", "在空格上左右滑；长按空格进入光标模式，可以上下左右移动。")
            Tip(pal, "纠错", "打错一个字母时候选会给出正确的词，拼音里打错的字母标红。")
            Tip(pal, "手写", "更多 → 手写。写完停笔半秒自动识别，候选带拼音。识别在手机上完成，不联网。")
            Tip(pal, "翻译", "更多 → 翻译。中文译成英文，其他语言译成中文。第一次用要下载约 30MB 的翻译模型。")
            Tip(pal, "工具栏", "点工具栏里的工具打开它，再点一次回到键盘。左上角“更多”里是其余的功能。")
        }
    }
}

@Composable
private fun Card(pal: Pal, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(pal.card).padding(horizontal = 14.dp, vertical = 8.dp)) { content() }
}

@Composable
private fun Line(pal: Pal, top: Dp = 0.dp, start: Dp = 0.dp) {
    Box(Modifier.padding(top = top, start = start).fillMaxWidth().height(1.dp).background(pal.line))
}

@Composable
private fun NavRow(pal: Pal, it: Item) {
    Row(Modifier.fillMaxWidth().clickable(onClick = it.onClick).padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(42.dp).clip(RoundedCornerShape(13.dp)).background(pal.tile), contentAlignment = Alignment.Center) {
            Image(painterResource(it.icon), null, Modifier.size(23.dp), colorFilter = ColorFilter.tint(pal.ink))
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            BasicText(it.title, style = TextStyle(color = pal.ink, fontSize = 17.sp, fontWeight = FontWeight.Medium))
            BasicText(it.sub, style = TextStyle(color = pal.text2, fontSize = 13.sp, lineHeight = 18.sp))
        }
        Spacer(Modifier.width(8.dp))
        if (it.value != null) BasicText(it.value, style = TextStyle(color = it.valueColor ?: pal.text2, fontSize = 14.sp))
        if (it.valueColor == null) Image(painterResource(R.drawable.ic_chevron_right), null, Modifier.size(22.dp), colorFilter = ColorFilter.tint(pal.text2))
    }
}

@Composable
private fun Pill(pal: Pal, text: String, strong: Boolean, onClick: () -> Unit) {
    Box(Modifier.clip(RoundedCornerShape(50)).background(if (strong) pal.accent else pal.track).clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 7.dp)) {
        BasicText(text, style = TextStyle(color = if (strong) pal.onAccent else pal.ink, fontSize = 14.sp, fontWeight = FontWeight.Medium))
    }
}

@Composable
private fun ToggleRow(pal: Pal, title: String, sub: String?, on: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clickable { onChange(!on) }.padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            BasicText(title, style = TextStyle(color = pal.ink, fontSize = 16.sp))
            if (sub != null) BasicText(sub, style = TextStyle(color = pal.text2, fontSize = 12.5.sp, lineHeight = 18.sp))
        }
        Spacer(Modifier.width(12.dp))
        Box(
            Modifier.size(width = 46.dp, height = 28.dp).clip(RoundedCornerShape(50)).background(if (on) pal.accent else pal.track),
            contentAlignment = if (on) Alignment.CenterEnd else Alignment.CenterStart,
        ) { Box(Modifier.padding(3.dp).size(22.dp).clip(CircleShape).background(Color.White)) }
    }
}

@Composable
private fun ActionRow(pal: Pal, title: String, sub: String?, button: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            BasicText(title, style = TextStyle(color = pal.ink, fontSize = 16.sp))
            if (sub != null) BasicText(sub, style = TextStyle(color = pal.text2, fontSize = 12.5.sp, lineHeight = 18.sp))
        }
        Pill(pal, button, true, onClick)
    }
}

@Composable
private fun Segment(pal: Pal, options: List<String>, index: Int, onSel: (Int) -> Unit) {
    Row(Modifier.clip(RoundedCornerShape(50)).background(pal.track).padding(3.dp)) {
        options.forEachIndexed { i, label ->
            val on = i == index
            Box(Modifier.clip(RoundedCornerShape(50)).background(if (on) pal.card else Color.Transparent).clickable { onSel(i) }.padding(horizontal = 12.dp, vertical = 6.dp)) {
                BasicText(label, style = TextStyle(color = if (on) pal.ink else pal.text2, fontSize = 13.5.sp, fontWeight = if (on) FontWeight.Bold else FontWeight.Normal))
            }
        }
    }
}

/** 拖动的滑条 */
@Composable
private fun Slider(pal: Pal, value: Float, min: Float, max: Float, onChange: (Float) -> Unit, onDone: () -> Unit) {
    BoxWithConstraints(Modifier.fillMaxWidth().height(36.dp)) {
        val w = constraints.maxWidth.toFloat()
        val density = LocalDensity.current
        val thumb = with(density) { 28.dp.toPx() }
        fun toValue(x: Float) = (min + (x - thumb / 2) / (w - thumb) * (max - min)).coerceIn(min, max)
        val pos = (value - min) / (max - min) * (w - thumb)
        Box(
            Modifier.fillMaxSize()
                .pointerInput(w) { detectTapGestures { o -> onChange(toValue(o.x)); onDone() } }
                .pointerInput(w) { detectDragGestures(onDragEnd = onDone) { change, _ -> onChange(toValue(change.position.x)) } },
            contentAlignment = Alignment.CenterStart,
        ) {
            Box(Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)).background(pal.track))
            Box(Modifier.width(with(density) { (pos + thumb / 2).toDp() }).height(6.dp).clip(RoundedCornerShape(3.dp)).background(pal.accent))
            Box(Modifier.offset { IntOffset(pos.roundToInt(), 0) }.size(28.dp).clip(CircleShape).background(pal.accent))
        }
    }
}

@Composable
private fun Tip(pal: Pal, title: String, body: String) {
    Column(Modifier.padding(vertical = 8.dp)) {
        BasicText(title, style = TextStyle(color = pal.ink, fontSize = 15.sp, fontWeight = FontWeight.Medium))
        BasicText(body, style = TextStyle(color = pal.text2, fontSize = 13.sp, lineHeight = 19.sp))
    }
}
