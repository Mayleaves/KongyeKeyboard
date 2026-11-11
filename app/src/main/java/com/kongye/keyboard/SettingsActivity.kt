package com.kongye.keyboard

import android.Manifest
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
import android.view.inputmethod.InputMethodManager
import android.widget.LinearLayout
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
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

private val LIGHT = Pal(Color(0xFFF4F5F8), Color(0xFFFFFFFF), Color(0xFF18191C), Color(0xFF686B73), Color(0xFFECEDF2), Color(0xFFF0F2F6), Color(0xFFE2E4EC), Color(0xFFF0492C), Color.White, Color(0xFF16A34A), Color(0xFFE11D48))
private val DARK = Pal(Color(0xFF0F1013), Color(0xFF1A1B20), Color(0xFFF4F5F8), Color(0xFF9498A3), Color(0xFF282A32), Color(0xFF24262E), Color(0xFF323540), Color(0xFFF0492C), Color.White, Color(0xFF4ADE80), Color(0xFFFB7185))

private enum class Page(val title: String) {
    MAIN("空叶键盘 设置"), LAYOUT("键盘布局"), KEYS("按键与手势"), PINYIN("拼音"), LOOK("主题与外观"), HEIGHT("键盘高度"),
    ARRANGE("工具栏与菜单"), CLIP("剪贴板"), TIPS("小技巧"), TRY("试一试"), NUMPAD("数字键盘"), VAULT("密码填充"), UPDATE("检查更新")
}

/** 主页上的一行：可以被搜索 */
private class Item(val section: String, val icon: Int, val tint: Color, val title: String, val sub: String, val value: String?, val valueColor: Color?, val onClick: () -> Unit)

class SettingsActivity : ComponentActivity() {
    private var restoredActivity = false
    private val tick = mutableIntStateOf(0)
    private val prefListener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> tick.intValue++ }
    private var confirmAt = 0L
    private var confirmWhat = ""
    private val micRequest = registerForActivityResult(ActivityResultContracts.RequestPermission()) { tick.intValue++ }
    private val notificationRequest = registerForActivityResult(ActivityResultContracts.RequestPermission()) { tick.intValue++ }
    private var checkingUpdate=false
    private val downloadState=mutableStateOf("")
    private val downloadedApk=mutableStateOf<java.io.File?>(null)
    private val downloading=mutableStateOf(false)
    @Volatile private var updateDownloadCancelled=false
    /** 检查更新的状态：显示在“检查更新”那一行 */
    private val updateState = mutableStateOf("")
    private val release = mutableStateOf<UpdateChecker.Release?>(null)

    private fun checkUpdate(silent: Boolean) {
        if (checkingUpdate) {if(!silent) updateState.value="检查中…";return}
        checkingUpdate=true
        if (!silent) updateState.value = "检查中…"
        Thread {
            val r = runCatching { UpdateChecker.fetch() }
            runOnUiThread {
                checkingUpdate=false
                Prefs.of(this).edit().putLong(Prefs.UPDATE_CHECKED, System.currentTimeMillis()).apply()
                r.onSuccess { rel ->
                    val newer = UpdateChecker.newer(rel.version, UpdateChecker.currentVersion(this))
                    release.value = if (newer) rel else null
                    updateState.value = if (newer) "有新版本 ${rel.tag}" else if (silent) "" else "已是最新"
                }.onFailure { e -> if (!silent) updateState.value = "检查失败：${e.message ?: "网络不通"}" }
            }
        }.start()
    }

    private fun downloadUpdate(rel:UpdateChecker.Release) {
        if(downloading.value) return
        downloading.value=true;downloadState.value="正在连接…";downloadedApk.value=null;updateDownloadCancelled=false
        Thread {
            val result=runCatching {AppUpdate.download(applicationContext,rel,{updateDownloadCancelled}) {bytes,total ->
                runOnUiThread {downloadState.value=if(total>0) "下载中 ${bytes*100/total}%" else "已下载 ${bytes/1024/1024} MB"}
            }}
            runOnUiThread {
                downloading.value=false
                result.onSuccess {downloadedApk.value=it;downloadState.value="下载完成，已校验安装包"}
                    .onFailure {downloadState.value=when {
                        updateDownloadCancelled -> "下载已取消"
                        it is java.net.SocketTimeoutException -> "下载超时，检查网络后重试"
                        it is java.net.UnknownHostException -> "无法连接下载服务器，请检查网络"
                        else -> it.message ?: "下载失败，请重试"
                    }}
            }
        }.start()
    }
    private fun installUpdate() {
        val file=downloadedApk.value ?: return
        if(android.os.Build.VERSION.SDK_INT>=26 && !packageManager.canRequestPackageInstalls()) {
            downloadState.value="请允许此应用安装更新，返回后点安装"
            startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,Uri.parse("package:$packageName")));return
        }
        runCatching {startActivity(AppUpdate.installer(this,file))}.onFailure {downloadState.value="无法打开系统安装器，请重试"}
    }

    private fun openUrl(url: String) {
        runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        restoredActivity = savedInstanceState != null
        Thread({
            Lexicon.load(applicationContext)
            PinyinEngine.initialize(applicationContext)
        }, "Kongye Rime preload").start()
        enableEdgeToEdge()
        ClipStore.load(this)
        Prefs.of(this).registerOnSharedPreferenceChangeListener(prefListener)
        if (intent?.getBooleanExtra("grant_mic", false) == true && !micGranted()) micRequest.launch(Manifest.permission.RECORD_AUDIO)
        setContent { Screen(tick.intValue) }
    }

    override fun onDestroy() {
        updateDownloadCancelled=true
        Prefs.of(this).unregisterOnSharedPreferenceChangeListener(prefListener)
        super.onDestroy()
    }

    override fun onResume() { super.onResume(); tick.intValue++ }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) tick.intValue++
    }

    private fun micGranted() = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
    private fun notificationsGranted()=getSystemService(android.app.NotificationManager::class.java).areNotificationsEnabled()
    private fun requestNotifications() {
        val prefs=Prefs.of(this)
        if(android.os.Build.VERSION.SDK_INT>=33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED &&
            (!prefs.getBoolean("notification_requested",false) || shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS))) {
            prefs.edit().putBoolean("notification_requested",true).apply()
            notificationRequest.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE,packageName))
    }
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
        // Rotation recreates the Activity, but it is not a new app launch.
        // Also skip the intro if rotation interrupts it halfway through.
        var showSplash by rememberSaveable { mutableStateOf(!restoredActivity && intent?.getBooleanExtra("grant_mic", false) != true) }
        if (restoredActivity && showSplash) showSplash = false
        var page by rememberSaveable { mutableStateOf(Page.MAIN) }
        val backStack = remember { mutableStateListOf<Page>() }
        // 主页独立保存滚动状态：从子页面返回时回到原先浏览的位置，不会跳回最顶部
        val mainScroll = rememberScrollState()

        fun navigateTo(target: Page) {
            if (target != page) {
                backStack.add(page)
                page = target
            }
        }

        fun navigateBack() {
            page = if (backStack.isNotEmpty()) backStack.removeAt(backStack.lastIndex) else Page.MAIN
        }

        BackHandler(enabled = page != Page.MAIN) { navigateBack() }

        Box(Modifier.fillMaxSize().background(pal.bg)) {
            AnimatedContent(
                targetState=page,
                transitionSpec={
                    (fadeIn(tween(180)) + slideInHorizontally(tween(220)) { it/12 }) togetherWith
                        (fadeOut(tween(130)) + slideOutHorizontally(tween(220)) { -it/12 })
                }, label="settingsPage",
            ) { shownPage ->
            if (shownPage == Page.MAIN) {
                Column(
                    Modifier.fillMaxSize().background(pal.bg).windowInsetsPadding(WindowInsets.safeDrawing)
                        .verticalScroll(mainScroll).padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    MainPage(pal, tick) { navigateTo(it) }
                    Spacer(Modifier.height(16.dp))
                }
            } else {
                val subScroll = androidx.compose.runtime.key(shownPage) { rememberScrollState() }
                Column(
                    Modifier.fillMaxSize().background(pal.bg).windowInsetsPadding(WindowInsets.safeDrawing),
                ) {
                    // 固定顶部返回栏
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Row(
                            Modifier.clip(RoundedCornerShape(14.dp)).clickable { navigateBack() }.padding(horizontal = 8.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Image(painterResource(R.drawable.ic_chevron_left), null, Modifier.size(24.dp), colorFilter = ColorFilter.tint(pal.ink))
                            Spacer(Modifier.width(4.dp))
                            BasicText(shownPage.title, style = TextStyle(color = pal.ink, fontSize = 20.sp, fontWeight = FontWeight.Bold))
                        }
                    }
                    Line(pal)
                    Column(
                        Modifier.fillMaxSize().verticalScroll(subScroll).padding(horizontal = 16.dp, vertical = 14.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        when (shownPage) {
                            Page.LAYOUT -> LayoutPage(pal, tick)
                            Page.KEYS -> KeysPage(pal, tick)
                            Page.PINYIN -> PinyinPage(pal, tick)
                            Page.LOOK -> LookPage(pal, tick)
                            Page.HEIGHT -> HeightPage(pal, tick)
                            Page.ARRANGE -> ArrangePage(pal, tick)
                            Page.CLIP -> ClipPage(pal, tick)
                            Page.TIPS -> TipsPage(pal)
                            Page.TRY -> TryPage(pal)
                            Page.NUMPAD -> NumpadPage(pal, tick)
                            Page.VAULT -> VaultPage(pal,tick)
                            Page.UPDATE -> UpdatePage(pal)
                            Page.MAIN -> Unit
                        }
                        Spacer(Modifier.height(20.dp))
                    }
                }
            }

            }

            if (showSplash) {
                SplashScreen(pal) { showSplash = false }
            }
        }
    }

    /** 开屏动画：立体键帽弹起 + 朱红叶片舒展 + 四颗迷你按键依次律动点亮 */
    @Composable
    private fun SplashScreen(pal: Pal, onDone: () -> Unit) {
        val capScale = remember { Animatable(0.88f) }
        val leafRot = remember { Animatable(-28f) }
        val textAlpha = remember { Animatable(0f) }
        val waveProg = remember { Animatable(0f) }
        val exitAlpha = remember { Animatable(1f) }
        // 退场：先让图标和文字淡出，再让底色淡出，避免 Logo 和后面的设置页叠在一起
        val contentAlpha = remember { Animatable(0f) }
        var dismiss by remember { mutableStateOf(false) }
        val version = remember { UpdateChecker.currentVersion(this) }

        LaunchedEffect(Unit) {
            launch { contentAlpha.animateTo(1f,tween(520,easing=FastOutSlowInEasing)) }
            launch {
                capScale.animateTo(1f,tween(800,easing=FastOutSlowInEasing))
            }
            launch {
                leafRot.animateTo(0f, tween(850, easing = FastOutSlowInEasing))
            }
            launch {
                delay(220)
                textAlpha.animateTo(1f, tween(580))
            }
            launch {
                delay(300)
                waveProg.animateTo(1f, tween(1050, easing = FastOutSlowInEasing))
            }
            delay(2000)
            dismiss=true
        }
        LaunchedEffect(dismiss) {
            if(!dismiss) return@LaunchedEffect
            contentAlpha.animateTo(0f,tween(420,easing=FastOutSlowInEasing))
            exitAlpha.animateTo(0f,tween(420,easing=FastOutSlowInEasing))
            onDone()
        }

        Box(
            Modifier.fillMaxSize()
                .graphicsLayer { alpha=exitAlpha.value }
                .background(pal.bg)
                .background(
                    Brush.radialGradient(
                        colors = listOf(pal.accent.copy(alpha = 0.16f), pal.bg),
                        radius = 900f,
                    ),
                )
                // A full-screen Material ripple darkens the whole screen on
                // every tap. Consume taps without indication; dismiss once.
                .semantics { contentDescription = "开屏动画" }
                .pointerInput(Unit) { detectTapGestures { onDone() } },
            contentAlignment = Alignment.Center,
        ) {
            Column(
                Modifier.graphicsLayer {alpha=contentAlpha.value},
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                // 中心立体键帽与红叶徽章
                Box(
                    Modifier.size(116.dp)
                        .scale(capScale.value)
                        .clip(RoundedCornerShape(32.dp))
                        .background(Color(0xFF181920))
                        .border(1.5.dp, pal.accent.copy(alpha = 0.35f), RoundedCornerShape(32.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    Image(
                        painter = painterResource(R.drawable.ic_launcher_fg),
                        contentDescription = null,
                        modifier = Modifier.size(106.dp).graphicsLayer { rotationZ = leafRot.value },
                    )
                }

                Spacer(Modifier.height(22.dp))

                // 品牌名与副标题
                Column(
                    Modifier.alpha(textAlpha.value),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    BasicText(
                        "空叶键盘",
                        style = TextStyle(color = pal.ink, fontSize = 28.sp, fontWeight = FontWeight.Bold),
                    )
                    Spacer(Modifier.height(6.dp))
                    BasicText(
                        "轻快如叶 · 落键生风",
                        style = TextStyle(color = pal.text2, fontSize = 14.sp),
                    )
                }

                Spacer(Modifier.height(28.dp))

                // 四颗迷你键帽依次按下律动动画
                Row(
                    Modifier.alpha(textAlpha.value),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    val chars = listOf("空", "叶", "键", "盘")
                    chars.forEachIndexed { idx, ch ->
                        val start = idx * 0.2f
                        val local = ((waveProg.value - start) / 0.35f).coerceIn(0f, 1f)
                        val active = local in 0.05f..0.95f
                        val lift = kotlin.math.sin(local * Math.PI).toFloat()
                        Box(
                            Modifier.offset(y = (-4 * lift).dp)
                                .size(36.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(if (active) pal.accent else pal.card)
                                .border(1.dp, if (active) pal.accent else pal.line, RoundedCornerShape(10.dp)),
                            contentAlignment = Alignment.Center,
                        ) {
                            BasicText(
                                ch,
                                style = TextStyle(
                                    color = if (active) Color.White else pal.ink,
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Bold,
                                ),
                            )
                        }
                    }
                }
            }

            BasicText(
                "v$version",
                style = TextStyle(color = pal.text2.copy(alpha = 0.7f), fontSize = 12.sp),
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 36.dp).alpha(textAlpha.value * contentAlpha.value),
            )
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
        val upd = updateState.value
        val version = remember { UpdateChecker.currentVersion(this) }
        // 一天最多自动查一次，有新版本才提示
        LaunchedEffect(Unit) {
            if (System.currentTimeMillis() - p.getLong(Prefs.UPDATE_CHECKED, 0L) > 24 * 3600_000L) checkUpdate(silent = true)
        }

        // 顶部：图标、名字、版本、今日字数
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(Color(0xFF181920)), contentAlignment = Alignment.Center) {
                Image(painterResource(R.drawable.ic_launcher_fg), null, Modifier.size(36.dp))
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                BasicText("空叶键盘", style = TextStyle(color = pal.ink, fontSize = 20.sp, fontWeight = FontWeight.Bold))
                BasicText("v$version", style = TextStyle(color = pal.text2, fontSize = 12.sp))
            }
            Column(horizontalAlignment = Alignment.End) {
                BasicText("$today", style = TextStyle(color = pal.ink, fontSize = 18.sp, fontWeight = FontWeight.Medium))
                BasicText("今日字数", style = TextStyle(color = pal.text2, fontSize = 11.sp))
            }
        }

        // 前置步骤始终保留，显示完成状态，方便首次启用或重新选择。
        val notifications=remember(tick) {notificationsGranted()}
        Card(pal) {
            BasicText("使用前准备",style=TextStyle(color=pal.ink,fontSize=16.sp,fontWeight=FontWeight.Medium),modifier=Modifier.padding(top=8.dp))
            ActionRow(pal,"1. 启用输入法",if(enabled) "已启用空叶键盘" else "先在系统设置中启用空叶键盘",if(enabled) "已启用" else "去启用") {
                startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
            }
            Line(pal)
            ActionRow(pal,"2. 选择输入法",if(current) "当前使用空叶键盘" else "选择空叶键盘作为当前输入法",if(current) "已选择" else "去选择") {
                if(enabled) getSystemService(InputMethodManager::class.java)?.showInputMethodPicker()
                else startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
            }
            Line(pal)
            ActionRow(pal,"3. 通知",if(notifications) "通知权限已开启" else "允许通知后完成此步骤",if(notifications) "已开启" else "去授权") {requestNotifications()}
        }
        val todo = listOfNotNull(
            if (!mic) "授权麦克风（语音输入用）" to { micRequest.launch(Manifest.permission.RECORD_AUDIO) } else null,
        )
        if (todo.isNotEmpty()) Card(pal) {
            todo.forEachIndexed { i, (label, action) ->
                if (i > 0) Line(pal)
                Row(Modifier.fillMaxWidth().clickable { action() }.padding(vertical = 13.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(7.dp).clip(CircleShape).background(pal.bad))
                    Spacer(Modifier.width(10.dp))
                    BasicText(label, style = TextStyle(color = pal.ink, fontSize = 15.sp), modifier = Modifier.weight(1f))
                    Image(painterResource(R.drawable.ic_chevron_right), null, Modifier.size(18.dp), colorFilter = ColorFilter.tint(pal.text2))
                }
            }
        }

        // 搜索，右边是“试一试”
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Row(
                Modifier.weight(1f).clip(RoundedCornerShape(18.dp)).background(pal.card)
                    .padding(horizontal = 14.dp, vertical = 11.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Image(painterResource(R.drawable.ic_search), null, Modifier.size(18.dp), colorFilter = ColorFilter.tint(pal.text2))
                Spacer(Modifier.width(10.dp))
                BasicTextField(
                    value = query, onValueChange = { query = it }, singleLine = true,
                    textStyle = TextStyle(color = pal.ink, fontSize = 15.sp), cursorBrush = SolidColor(pal.accent),
                    modifier = Modifier.weight(1f),
                    decorationBox = { inner -> Box { if (query.isEmpty()) BasicText("搜索", style = TextStyle(color = pal.text2, fontSize = 15.sp)); inner() } },
                )
            }
            Box(
                Modifier.size(44.dp).clip(RoundedCornerShape(18.dp)).background(pal.card).clickable { go(Page.TRY) },
                contentAlignment = Alignment.Center,
            ) {
                Image(painterResource(R.drawable.ic_kbd), "试一试", Modifier.size(20.dp), colorFilter = ColorFilter.tint(pal.ink))
            }
        }

        val cInput = Color(0xFF2563EB)
        val cLook = Color(0xFFF0492C)
        val cTool = Color(0xFF0D9488)
        val cAbout = Color(0xFF7C3AED)
        val items = listOf(
            Item("输入", R.drawable.ic_kbd, cInput, "键盘布局", "26 键 14 键 九键", mapOf("26" to "26 键", "14" to "14 键", "9" to "九键")[layout], null) { go(Page.LAYOUT) },
            Item("输入", R.drawable.ic_adjust, cInput, "按键与手势", "震动 按键音 上下滑 空格长按 删除", null, null) { go(Page.KEYS) },
            Item("输入", R.drawable.ic_translate, cInput, "拼音", "简拼 模糊音 纠错 联想 记忆 词库", null, null) { go(Page.PINYIN) },
            Item("输入", R.drawable.ic_symbol, cInput, "数字键盘", "符号列 快捷键", null, null) { go(Page.NUMPAD) },
            Item("外观", R.drawable.ic_palette, cLook, "主题", "配色 深色 液态玻璃 透明度 皮肤", theme.name, null) { go(Page.LOOK) },
            Item("外观", R.drawable.ic_float, cLook, "键盘高度", "高度 大小", "${(scale * 100).roundToInt()}%", null) { go(Page.HEIGHT) },
            Item("外观", R.drawable.ic_float, cLook, "悬浮键盘", "悬浮 拖动", if (floating) "开" else "关", null) { p.edit().putBoolean(Prefs.FLOAT, !floating).apply() },
            Item("其他", R.drawable.ic_key, cTool, "密码填充", "空叶文档 密码空间 登录 生成 保存", if(p.getBoolean(Prefs.VAULT,false)) "已开启" else "关闭", null) { go(Page.VAULT) },
            Item("其他", R.drawable.ic_toolbar, cTool, "工具栏", "工具栏 菜单 排序", null, null) { go(Page.ARRANGE) },
            Item("其他", R.drawable.ic_clip, cTool, "剪贴板", "剪贴板 分类 置顶", null, null) { go(Page.CLIP) },
            Item("其他", R.drawable.ic_settings, cAbout, "使用技巧", "帮助 技巧 手写 翻译", null, null) { go(Page.TIPS) },
            Item("其他", R.drawable.ic_download, cAbout, "检查更新", "更新 版本", upd.ifEmpty { null }, if (upd.startsWith("有新版本")) pal.accent else null) {
                go(Page.UPDATE)
                checkUpdate(silent = false)
            },
            Item("其他", R.drawable.ic_globe, cAbout, "GitHub", "开源 源码 仓库", null, null) {openUrl(UpdateChecker.REPOSITORY)},
        )
        val shown = items.filter { query.isBlank() || it.title.contains(query) || it.sub.contains(query) || it.section.contains(query) }
        for ((section, rows) in shown.groupBy { it.section }) {
            BasicText(section, style = TextStyle(color = pal.text2, fontSize = 13.sp), modifier = Modifier.padding(start = 6.dp, top = 6.dp))
            Card(pal) {
                rows.forEachIndexed { i, it ->
                    if (i > 0) Line(pal, start = 58.dp)
                    NavRow(pal, it)
                }
            }
        }
        if (shown.isEmpty()) BasicText("没有找到“$query”", style = TextStyle(color = pal.text2, fontSize = 15.sp), modifier = Modifier.padding(16.dp))
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
                update = { it.current = cur },
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
        val spaceLong = remember(tick) { p.getInt(Prefs.SPACE_LONG, 0) }
        Card(pal) {
            Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                BasicText("按键震动", style = TextStyle(color = pal.ink, fontSize = 16.sp), modifier = Modifier.weight(1f))
                Segment(pal, listOf("关", "弱", "中", "强"), vib) { p.edit().putInt(Prefs.VIB_STRENGTH, it).apply() }
            }
            Line(pal)
            ToggleRow(pal, "按键音", null, sound) { p.edit().putBoolean(Prefs.SOUND, it).apply() }
            Line(pal)
            ToggleRow(pal, "上下滑输入符号", "下滑右上角，上滑左下角", swipe) { p.edit().putBoolean(Prefs.SWIPE, it).apply() }
            Line(pal)
            Tip(pal, "删除键", "下滑清空，上滑撤销")
            Line(pal)
            Column(Modifier.padding(vertical = 10.dp)) {
                BasicText("空格长按", style = TextStyle(color = pal.ink, fontSize = 16.sp))
                Spacer(Modifier.height(8.dp))
                Segment(pal, listOf("移动光标", "连续空格", "语音"), spaceLong) { p.edit().putInt(Prefs.SPACE_LONG, it).apply() }
                Spacer(Modifier.height(6.dp))
                BasicText(
                    when (spaceLong) { 1 -> "按住一直输入空格"; 2 -> "长按开关语音"; else -> "长按后滑动移动光标" },
                    style = TextStyle(color = pal.text2, fontSize = 12.5.sp),
                )
            }
            Tip(pal, "长按字母", "左右滑选大小写和符号")
        }
    }

    @Composable
    private fun PinyinPage(pal: Pal, tick: Int) {
        val p = Prefs.of(this)
        val r = remember(tick) { listOf(Prefs.ABBR, Prefs.ASSOC, Prefs.LEARN).associateWith { p.getBoolean(it, true) } }
        var forgetHint by remember { mutableStateOf("") }
        Card(pal) {
            ToggleRow(pal, "候选区显示拼音", "只控制候选词旁的读音，上方拼音条始终保留", p.getBoolean(Prefs.SHOW_PINYIN, false)) { p.edit().putBoolean(Prefs.SHOW_PINYIN, it).apply() }
            Line(pal)
            ToggleRow(pal, "简拼", "zg → 中国", r[Prefs.ABBR]!!) { p.edit().putBoolean(Prefs.ABBR, it).apply() }
            Line(pal)
            ToggleRow(pal, "联想", null, r[Prefs.ASSOC]!!) { p.edit().putBoolean(Prefs.ASSOC, it).apply() }
            Line(pal)
            ToggleRow(pal, "记住选词", "常打的词排前面，也能用简拼打出", r[Prefs.LEARN]!!) { p.edit().putBoolean(Prefs.LEARN, it).apply() }
            Line(pal)
            Tip(pal, "纠错", "打错时给出正确的词，错的字母标红")
        }
        Card(pal) {
            Tip(pal, "拼音引擎", "26 键：Fcitx5 · libime 原生排序纠错；其他布局：Rime，全程离线")
            ActionRow(pal, "万象拼音 · CC BY 4.0", "作者、原始项目与许可", "查看") {
                startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://github.com/amzxyz/rime-wanxiang")))
            }
        }
        BasicText("模糊音", style = TextStyle(color = pal.text2, fontSize = 15.sp), modifier = Modifier.padding(start = 4.dp))
        Card(pal) {
            val on = remember(tick) { Prefs.fuzzy(this@SettingsActivity) }
            BasicText("例：开 z = zh，zi 也能出“只”", style = TextStyle(color = pal.text2, fontSize = 12.5.sp), modifier = Modifier.padding(vertical = 8.dp))
            Prefs.FUZZY_RULES.chunked(3).forEach { row ->
                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    row.forEach { (id, label) ->
                        val sel = id in on
                        Box(
                            Modifier.weight(1f).clip(RoundedCornerShape(50)).background(if (sel) pal.accent else pal.track)
                                .clickable {
                                    val next = if (sel) on - id else on + id
                                    p.edit().putString(Prefs.FUZZY, next.joinToString(",")).apply()
                                }.padding(vertical = 8.dp),
                            contentAlignment = Alignment.Center,
                        ) { BasicText(label, style = TextStyle(color = if (sel) pal.onAccent else pal.ink, fontSize = 14.sp, fontWeight = FontWeight.Medium)) }
                    }
                    repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
            Spacer(Modifier.height(6.dp))
        }
        Card(pal) {
            Line(pal, top = 10.dp)
            ActionRow(pal, "清除选词记录", forgetHint.ifEmpty { "只删学到的东西，不影响词库" }, "清除") {
                if (confirmed("forget")) {
                    forgetHint = "正在清除…"
                    Thread {
                        val result = runCatching { Lexicon.clearUser(this@SettingsActivity); PinyinEngine.clearUser(this@SettingsActivity) }
                        runOnUiThread { forgetHint = if (result.isSuccess) "已清除" else "清除失败，请重试" }
                    }.start()
                } else forgetHint = "再点一次“清除”确认"
            }
        }
    }

    @Composable
    private fun Preview(pal: Pal, tick: Int, scaleOverride: Float? = null) {
        val skin = remember(tick) { Prefs.theme(this).pick(Prefs.night(this)).styled(1f,Prefs.of(this).getBoolean(Prefs.GLASS,false)) }
        val layout = remember(tick) { Prefs.layout(this) }
        val previewBoxMod = Modifier.fillMaxWidth()

        Card(pal) {
            Box(previewBoxMod) {
                AndroidView(
                    factory = { ctx ->
                        LinearLayout(ctx).apply {
                            orientation = LinearLayout.VERTICAL
                            addView(CandidateBar(ctx))
                            addView(KeyboardView(ctx).apply { preview = true })
                        }
                    },
                    update = { ll ->
                        ll.alpha=Prefs.opacity(this@SettingsActivity)
                        ll.background = if (skin.bg2 != 0) android.graphics.drawable.GradientDrawable(android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(skin.kbBg, skin.bg2))
                        else android.graphics.drawable.ColorDrawable(skin.kbBg)
                        (ll.getChildAt(0) as CandidateBar).apply { this.skin = skin;uiScale=scaleOverride ?: Prefs.heightScale(this@SettingsActivity); showTools(listOf("more") + Prefs.tools(this@SettingsActivity)) }
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
    }

    @Composable
    private fun LookPage(pal: Pal, tick: Int) {
        val p = Prefs.of(this)
        val theme = remember(tick) { Prefs.theme(this) }
        val mode = remember(tick) { p.getInt(Prefs.MODE, 0) }
        val glass = remember(tick) { p.getBoolean(Prefs.GLASS, false) }
        var opacity by remember { mutableFloatStateOf(Prefs.opacity(this)) }
        Preview(pal, tick)
        Card(pal) {
            Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                BasicText("深色模式", style = TextStyle(color = pal.ink, fontSize = 16.sp), modifier = Modifier.weight(1f))
                Segment(pal, listOf("跟随系统", "浅色", "深色"), mode) { p.edit().putInt(Prefs.MODE, it).apply() }
            }
            Line(pal)
            ToggleRow(pal, "液态玻璃", null, glass) { p.edit().putBoolean(Prefs.GLASS, it).apply() }
            Line(pal)
            Column(Modifier.padding(vertical = 10.dp)) {
                BasicText("键盘透明度 ${(opacity * 100).roundToInt()}%", style = TextStyle(color = pal.ink, fontSize = 16.sp))
                Spacer(Modifier.height(10.dp))
                Slider(pal, opacity, 0.3f, 1f, { opacity = it }) { p.edit().putFloat(Prefs.OPACITY, opacity).apply() }
            }
        }
        BasicText("配色方案", style = TextStyle(color = pal.text2, fontSize = 15.sp), modifier = Modifier.padding(start = 4.dp))
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
            AndroidView(
                factory = { ctx -> ArrangeView(ctx, skin, tools, menu, pal.ink.toArgb(), pal.text2.toArgb()) { t, m -> Prefs.saveArrangement(ctx, t, m) } },
                update = { it.updateArrangement(skin, tools, menu) },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }

    @Composable
    private fun ClipPage(pal: Pal, tick: Int) {
        var hint by remember { mutableStateOf("") }
        val count = remember(tick, hint) { ClipStore.items.size }
        val pinned = remember(tick, hint) { ClipStore.items.count { it.pinned } }
        val images = remember(tick, hint) { ClipStore.items.count { it.image != null } }
        Card(pal) {
            BasicText("共 $count 条（置顶 $pinned 条，图片 $images 张），普通历史最多 ${ClipStore.MAX} 条，满了淘汰最旧的历史；置顶和自定义分类长期保存。", style = TextStyle(color = pal.ink, fontSize = 15.sp, lineHeight = 22.sp))
            BasicText("只保存在这台手机上", style = TextStyle(color = pal.text2, fontSize = 12.5.sp), modifier = Modifier.padding(top = 6.dp))
            Line(pal, top = 12.dp)
            ActionRow(pal, "清空历史（保留置顶和自定义分类）", hint.ifEmpty { null }, "清空") {
                if (confirmed("clips")) { ClipStore.clearUnpinned(); hint = "已清空" } else hint = "再点一次“清空”确认"
            }
        }
    }

    @Composable
    private fun NumpadPage(pal: Pal, tick: Int) {
        val p = Prefs.of(this)
        var sideList by remember { mutableStateOf(Prefs.numSide(this)) }
        val initialKeys = remember(tick) { Prefs.numKeys(this) }
        var customKeys by remember { mutableStateOf(initialKeys) }
        // selectedSlot: 0="6右边", 1="9右边", 2="0右边", 3="左侧符号列"
        var selectedSlot by remember { mutableIntStateOf(0) }
        var selectedSideIdx by remember { mutableIntStateOf(0) }
        var customInput by remember { mutableStateOf("") }

        fun saveAll(newSide: List<String> = sideList, newKeys: List<String> = customKeys) {
            sideList = newSide
            customKeys = newKeys
            p.edit()
                .putString(Prefs.NUM_SIDE_KEY, newSide.joinToString(" "))
                .putString(Prefs.NUM_KEYS, newKeys.joinToString("\u0001"))
                .apply()
        }

        fun displayKey(s: String) = if (s == " ") "␣ 空格" else s

        // 1. 顶部实时可视化数字键盘（点击任意可定制区域直接选中修改）
        Card(pal) {
            Row(Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 6.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                BasicText("点击下方键盘中的高亮区域即可直接修改", style = TextStyle(color = pal.text2, fontSize = 12.5.sp))
                Pill(pal, "恢复默认", false) {
                    p.edit().remove(Prefs.NUM_SIDE_KEY).remove(Prefs.NUM_KEYS).apply()
                    sideList = Prefs.DEFAULT_NUM_SIDE
                    customKeys = Prefs.DEFAULT_NUM_KEYS
                    selectedSideIdx = 0
                }
            }

            // 可视化数字键盘模拟布局
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(pal.bg).padding(8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Row(Modifier.fillMaxWidth().height(156.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    // 左列：可上下滑动的符号列
                    val sideActive = selectedSlot == 3
                    Column(
                        Modifier.weight(1f).fillMaxSize().clip(RoundedCornerShape(11.dp))
                            .background(pal.card)
                            .border(if (sideActive) 2.dp else 1.dp, if (sideActive) pal.accent else pal.line, RoundedCornerShape(11.dp))
                            .clickable { selectedSlot = 3 }
                            .padding(vertical = 4.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.SpaceEvenly,
                    ) {
                        BasicText("左列", style = TextStyle(color = if (sideActive) pal.accent else pal.text2, fontSize = 10.sp, fontWeight = FontWeight.Bold))
                        sideList.take(4).forEach { sym ->
                            BasicText(sym, style = TextStyle(color = pal.ink, fontSize = 15.sp, fontWeight = FontWeight.Medium))
                        }
                        BasicText("⋮", style = TextStyle(color = pal.text2, fontSize = 12.sp))
                    }

                    // 中间 3x3 数字 + 右列（退格、6右边、9右边）
                    Column(Modifier.weight(4.5f).fillMaxSize(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        @Composable
                        fun NumRow(n1: String, n2: String, n3: String, rightSlot: Int, rightLabel: String) {
                            Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                listOf(n1, n2, n3).forEach { num ->
                                    Box(
                                        Modifier.weight(1.3f).fillMaxSize().clip(RoundedCornerShape(10.dp)).background(pal.card),
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        BasicText(num, style = TextStyle(color = pal.ink, fontSize = 19.sp, fontWeight = FontWeight.Medium))
                                    }
                                }
                                if (rightSlot < 0) {
                                    Box(
                                        Modifier.weight(1f).fillMaxSize().clip(RoundedCornerShape(10.dp)).background(pal.tile),
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        Image(painterResource(R.drawable.ic_del), null, Modifier.size(20.dp), colorFilter = ColorFilter.tint(pal.ink))
                                    }
                                } else {
                                    val active = selectedSlot == rightSlot
                                    Box(
                                        Modifier.weight(1f).fillMaxSize().clip(RoundedCornerShape(10.dp))
                                            .background(if (active) pal.accent.copy(alpha = 0.14f) else pal.card)
                                            .border(if (active) 2.dp else 1.dp, if (active) pal.accent else pal.line, RoundedCornerShape(10.dp))
                                            .clickable { selectedSlot = rightSlot },
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        BasicText(rightLabel, style = TextStyle(color = if (active) pal.accent else pal.ink, fontSize = 14.sp, fontWeight = FontWeight.Bold))
                                    }
                                }
                            }
                        }
                        NumRow("1", "2", "3", -1, "")
                        NumRow("4", "5", "6", 0, displayKey(customKeys[0]))
                        NumRow("7", "8", "9", 1, displayKey(customKeys[1]))
                    }
                }

                // 底行：符号、返回、0、0右边、回车
                Row(Modifier.fillMaxWidth().height(46.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Box(Modifier.weight(1f).fillMaxSize().clip(RoundedCornerShape(10.dp)).background(pal.tile), contentAlignment = Alignment.Center) {
                        Image(painterResource(R.drawable.ic_symbol), null, Modifier.size(19.dp), colorFilter = ColorFilter.tint(pal.ink))
                    }
                    Box(Modifier.weight(1.3f).fillMaxSize().clip(RoundedCornerShape(10.dp)).background(pal.tile), contentAlignment = Alignment.Center) {
                        Image(painterResource(R.drawable.ic_back), null, Modifier.size(19.dp), colorFilter = ColorFilter.tint(pal.ink))
                    }
                    Box(Modifier.weight(1.3f).fillMaxSize().clip(RoundedCornerShape(10.dp)).background(pal.card), contentAlignment = Alignment.Center) {
                        BasicText("0", style = TextStyle(color = pal.ink, fontSize = 19.sp, fontWeight = FontWeight.Medium))
                    }
                    val slot2Active = selectedSlot == 2
                    Box(
                        Modifier.weight(1.3f).fillMaxSize().clip(RoundedCornerShape(10.dp))
                            .background(if (slot2Active) pal.accent.copy(alpha = 0.14f) else pal.card)
                            .border(if (slot2Active) 2.dp else 1.dp, if (slot2Active) pal.accent else pal.line, RoundedCornerShape(10.dp))
                            .clickable { selectedSlot = 2 },
                        contentAlignment = Alignment.Center,
                    ) {
                        BasicText(displayKey(customKeys[2]), style = TextStyle(color = if (slot2Active) pal.accent else pal.ink, fontSize = 14.sp, fontWeight = FontWeight.Bold))
                    }
                    Box(Modifier.weight(1f).fillMaxSize().clip(RoundedCornerShape(10.dp)).background(pal.accent), contentAlignment = Alignment.Center) {
                        Image(painterResource(R.drawable.ic_enter), null, Modifier.size(19.dp), colorFilter = ColorFilter.tint(pal.onAccent))
                    }
                }
            }
            Spacer(Modifier.height(4.dp))
        }

        // 2. 下方对应选中区域的可视化配置面板
        val slotNames = listOf("「6 右边」按键", "「9 右边」按键", "「0 右边」按键", "「左侧上下滑动符号列」")
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(0 to "6 右边", 1 to "9 右边", 2 to "0 右边", 3 to "左列符号").forEach { (idx, name) ->
                val sel = selectedSlot == idx
                Box(
                    Modifier.weight(1f).clip(RoundedCornerShape(50)).background(if (sel) pal.accent else pal.card)
                        .border(1.dp, if (sel) pal.accent else pal.line, RoundedCornerShape(50))
                        .clickable { selectedSlot = idx }.padding(vertical = 8.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    BasicText(name, style = TextStyle(color = if (sel) pal.onAccent else pal.ink, fontSize = 13.sp, fontWeight = FontWeight.Medium))
                }
            }
        }

        if (selectedSlot in 0..2) {
            val curVal = customKeys[selectedSlot]
            val presets = listOf(" ", "@", ".", ",", "-", "+", "/", "*", "=", ":", "#", "%", "00", "¥", "$", "_")
            Card(pal) {
                BasicText("点选要放在${slotNames[selectedSlot]}的功能或符号：", style = TextStyle(color = pal.ink, fontSize = 15.sp, fontWeight = FontWeight.Bold), modifier = Modifier.padding(top = 6.dp, bottom = 8.dp))
                presets.chunked(4).forEach { row ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        row.forEach { item ->
                            val isSel = curVal == item
                            Box(
                                Modifier.weight(1f).height(42.dp).clip(RoundedCornerShape(12.dp))
                                    .background(if (isSel) pal.accent else pal.tile)
                                    .clickable {
                                        val next = customKeys.toMutableList().also { it[selectedSlot] = item }
                                        saveAll(newKeys = next)
                                    },
                                contentAlignment = Alignment.Center,
                            ) {
                                BasicText(if (item == " ") "␣ 空格" else item, style = TextStyle(color = if (isSel) pal.onAccent else pal.ink, fontSize = 15.sp, fontWeight = FontWeight.Medium))
                            }
                        }
                    }
                }
                Line(pal, top = 8.dp)
                Row(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    BasicText("自定义字符：", style = TextStyle(color = pal.text2, fontSize = 13.5.sp))
                    Box(Modifier.weight(1f)) {
                        Field(pal, customInput) { customInput = it }
                    }
                    Pill(pal, "设为该键", true) {
                        val v = customInput.trim().take(6)
                        if (v.isNotEmpty()) {
                            val next = customKeys.toMutableList().also { it[selectedSlot] = v }
                            saveAll(newKeys = next)
                            customInput = ""
                        }
                    }
                }
            }
        } else {
            val candidatePool = listOf("·", "/", "+", "-", "=", "%", "*", "#", ",", ":", ";", "(", ")", "[", "]", "~", "_", "¥", "$", "@", "!", "?", "、", "。")
            Card(pal) {
                BasicText("当前左列符号（点选可前移 / 后移 / 删除）：", style = TextStyle(color = pal.ink, fontSize = 15.sp, fontWeight = FontWeight.Bold), modifier = Modifier.padding(top = 6.dp, bottom = 6.dp))
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    sideList.forEachIndexed { idx, sym ->
                        val active = idx == selectedSideIdx
                        Box(
                            Modifier.size(42.dp).clip(RoundedCornerShape(11.dp))
                                .background(if (active) pal.accent else pal.tile)
                                .clickable { selectedSideIdx = idx },
                            contentAlignment = Alignment.Center,
                        ) {
                            BasicText(sym, style = TextStyle(color = if (active) pal.onAccent else pal.ink, fontSize = 17.sp, fontWeight = FontWeight.Bold))
                        }
                    }
                }
                Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Pill(pal, "◀ 前移", false) {
                        if (selectedSideIdx in 1 until sideList.size) {
                            val m = sideList.toMutableList()
                            val tmp = m[selectedSideIdx - 1]; m[selectedSideIdx - 1] = m[selectedSideIdx]; m[selectedSideIdx] = tmp
                            selectedSideIdx--
                            saveAll(newSide = m)
                        }
                    }
                    Pill(pal, "后移 ▶", false) {
                        if (selectedSideIdx in 0 until sideList.lastIndex) {
                            val m = sideList.toMutableList()
                            val tmp = m[selectedSideIdx + 1]; m[selectedSideIdx + 1] = m[selectedSideIdx]; m[selectedSideIdx] = tmp
                            selectedSideIdx++
                            saveAll(newSide = m)
                        }
                    }
                    Pill(pal, "✕ 删除选中", false) {
                        if (sideList.size > 1 && selectedSideIdx in sideList.indices) {
                            val m = sideList.toMutableList().also { it.removeAt(selectedSideIdx) }
                            selectedSideIdx = selectedSideIdx.coerceAtMost(m.lastIndex)
                            saveAll(newSide = m)
                        }
                    }
                }
                Line(pal, top = 4.dp)
                BasicText("点击下方符号添加进左列：", style = TextStyle(color = pal.text2, fontSize = 13.sp), modifier = Modifier.padding(top = 8.dp, bottom = 4.dp))
                candidatePool.chunked(6).forEach { row ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        row.forEach { sym ->
                            val exists = sym in sideList
                            Box(
                                Modifier.weight(1f).height(38.dp).clip(RoundedCornerShape(10.dp))
                                    .background(if (exists) pal.accent.copy(alpha = 0.14f) else pal.tile)
                                    .clickable {
                                        if (!exists) {
                                            val m = sideList + sym
                                            selectedSideIdx = m.lastIndex
                                            saveAll(newSide = m)
                                        }
                                    },
                                contentAlignment = Alignment.Center,
                            ) {
                                BasicText(sym, style = TextStyle(color = if (exists) pal.accent else pal.ink, fontSize = 16.sp, fontWeight = FontWeight.Medium))
                            }
                        }
                    }
                }
                Spacer(Modifier.height(6.dp))
            }
        }
    }

    @Composable
    private fun Field(pal: Pal, value: String, onChange: (String) -> Unit) {
        BasicTextField(
            value = value, onValueChange = onChange, singleLine = true,
            textStyle = TextStyle(color = pal.ink, fontSize = 17.sp), cursorBrush = SolidColor(pal.accent),
            modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp).clip(RoundedCornerShape(12.dp)).background(pal.tile).padding(horizontal = 12.dp, vertical = 10.dp),
        )
    }

    @Composable
    private fun UpdatePage(pal: Pal) {
        val version = remember { UpdateChecker.currentVersion(this) }
        val state = updateState.value
        val rel = release.value
        Card(pal) {
            BasicText("当前版本 $version", style = TextStyle(color = pal.ink, fontSize = 16.sp, fontWeight = FontWeight.Medium), modifier = Modifier.padding(top = 10.dp))
            BasicText(state.ifEmpty { "从 GitHub 读取最新发布的版本" }, style = TextStyle(color = if (rel != null) pal.accent else pal.text2, fontSize = 14.sp, lineHeight = 20.sp), modifier = Modifier.padding(top = 4.dp))
            if (rel != null && rel.notes.isNotBlank()) {
                Line(pal, top = 10.dp)
                BasicText("更新内容", style = TextStyle(color = pal.ink, fontSize = 15.sp, fontWeight = FontWeight.Medium), modifier = Modifier.padding(top = 10.dp))
                BasicText(rel.notes.trim().take(1500), style = TextStyle(color = pal.text2, fontSize = 13.sp, lineHeight = 19.sp), modifier = Modifier.padding(top = 4.dp))
            }
            Row(Modifier.padding(vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (rel != null && rel.apkUrl!=null && !downloading.value && downloadedApk.value==null) Pill(pal, "下载 ${rel.tag}", true) {downloadUpdate(rel)}
                if(downloadedApk.value!=null) Pill(pal,"安装更新",true) {installUpdate()}
                if(downloading.value) Pill(pal,"取消下载",false) {updateDownloadCancelled=true}
                Pill(pal, "重新检查", rel == null) { checkUpdate(silent = false) }
            }
            if(downloadState.value.isNotBlank()) BasicText(downloadState.value,style=TextStyle(color=pal.text2,fontSize=13.sp),modifier=Modifier.padding(bottom=8.dp))
            BasicText("覆盖安装，设置和词库保留", style = TextStyle(color = pal.text2, fontSize = 12.5.sp), modifier = Modifier.padding(bottom = 8.dp))
        }
    }

    @Composable
    private fun VaultPage(pal:Pal,tick:Int) {
        val prefs=Prefs.of(this)
        val enabled=remember(tick) {prefs.getBoolean(Prefs.VAULT,false)}
        val source=remember(tick) {VaultGateway(this).owner()}
        var state by remember {mutableStateOf("")}
        LaunchedEffect(enabled,source) {
            if(!enabled) state="已关闭，不访问密码空间"
            else Thread {
                val status=VaultGateway(this@SettingsActivity).call("status",Bundle())
                val label=when(status.getString("status")) {
                    "ok" -> if(status.getBoolean("enabled")) "已连接空叶文档密码空间" else "请在空叶文档中允许键盘读取"
                    "disabled" -> "请在空叶文档中允许键盘读取"
                    "denied" -> "未获得密码空间权限，请确认两个应用签名一致"
                    "not_found" -> "未安装对应空叶文档，或密码接口尚未就绪"
                    else -> "密码空间暂不可用"
                }
                status.clear()
                runOnUiThread {if(prefs.getBoolean(Prefs.VAULT,false)==enabled && VaultGateway(this@SettingsActivity).owner()==source) state=label}
            }.start()
        }
        Card(pal) {
            ToggleRow(pal,"密码填充","使用空叶文档中的密码空间",enabled) {prefs.edit().putBoolean(Prefs.VAULT,it).apply()}
            BasicText(state,style=TextStyle(color=pal.text2,fontSize=13.sp,lineHeight=19.sp),modifier=Modifier.padding(vertical=8.dp))
            BasicText("点到账号或密码框时，工具栏出现「🔑 密码空间」，点开选账号即可填入；未解锁会打开空叶文档验证。账号填完后会跳到密码框接着填。注册页可按提示文字的要求生成强密码，确认框两分钟内可复用；提交后提示保存账号、手机号、邮箱和密码。",style=TextStyle(color=pal.text2,fontSize=13.sp,lineHeight=20.sp))
        }
        Card(pal) {
            val inline=remember(tick) {prefs.getBoolean(Prefs.VAULT_INLINE,true)}
            ToggleRow(pal,"自动填充候选显示在工具栏","关闭后用系统下拉框（输入框下面）",inline) {prefs.edit().putBoolean(Prefs.VAULT_INLINE,it).apply()}
            BasicText("在空叶文档里把它设为系统的自动填充服务后，登录框的账号、注册页的「生成强密码」会由系统推送；开启这一项时显示在键盘工具栏里，此时键盘不再重复列出匹配，也不再弹自己的保存提示。需要 Android 11 及以上。",style=TextStyle(color=pal.text2,fontSize=13.sp,lineHeight=20.sp),modifier=Modifier.padding(top=8.dp))
        }
        Card(pal) {
            BasicText("密码空间来源",style=TextStyle(color=pal.ink,fontSize=15.sp))
            VaultGateway.PACKAGES.forEachIndexed {index,pkg ->
                ActionRow(pal,listOf("正式版","测试 audit","测试 docktest","升级验证 fixture")[index],null,if(source==pkg) "已选择" else "选择") {
                    prefs.edit().putString(Prefs.VAULT_PACKAGE,pkg).apply()
                }
            }
        }
        Card(pal) {
            BasicText("输入的密码只暂存在内存，粘贴内容不记录；点击保存才写入空叶文档。关闭提示、锁屏或超时会清除暂存。",style=TextStyle(color=pal.text2,fontSize=13.sp,lineHeight=20.sp))
            BasicText("浏览器无法向键盘提供网站域名，网页登录请搜索密码空间。系统安全键盘可能替换第三方键盘，可在系统设置中关闭；银行等应用的自绘键盘不支持填充。",style=TextStyle(color=pal.text2,fontSize=13.sp,lineHeight=20.sp),modifier=Modifier.padding(top=8.dp))
        }
    }

    @Composable
    private fun TipsPage(pal: Pal) {
        Card(pal) {
            Tip(pal, "上下滑", "下滑输入右上角符号，上滑输入左下角")
            Tip(pal, "删除键", "下滑清空，上滑撤销")
            Tip(pal, "分词", "打拼音时大写键变成分词：xi 分词 an → 西安")
            Tip(pal, "移动光标", "左右滑空格，或长按空格后滑动")
            Tip(pal, "拼音条", "点拼音中间，可以在那里改字母")
            Tip(pal, "手写", "内置开源笔画识别，候选显示声调与多种读音")
            Tip(pal, "翻译", "译文直接出现在光标处，首次使用下载模型")
            Tip(pal, "工具栏", "再点一次已打开的工具，回到键盘")
        }
    }
}

@Composable
private fun Card(pal: Pal, content: @Composable () -> Unit) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(pal.card)
            .padding(horizontal = 14.dp, vertical = 6.dp),
    ) { content() }
}

@Composable
private fun Line(pal: Pal, top: Dp = 0.dp, start: Dp = 0.dp) {
    Box(Modifier.padding(top = top, start = start).fillMaxWidth().height(1.dp).background(pal.line))
}

@Composable
private fun NavRow(pal: Pal, it: Item) {
    Row(Modifier.fillMaxWidth().clickable(onClick = it.onClick).padding(vertical = 11.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.size(36.dp).clip(RoundedCornerShape(11.dp)).background(pal.tile),
            contentAlignment = Alignment.Center,
        ) {
            Image(painterResource(it.icon), null, Modifier.size(20.dp), colorFilter = ColorFilter.tint(pal.ink.copy(alpha = 0.82f)))
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            BasicText(it.title, style = TextStyle(color = pal.ink, fontSize = 16.sp))
            Spacer(Modifier.height(1.dp))

        }
        Spacer(Modifier.width(8.dp))
        if (it.value != null) BasicText(it.value, style = TextStyle(color = it.valueColor ?: pal.text2, fontSize = 13.5.sp, fontWeight = FontWeight.Medium))
        if (it.valueColor == null && it.title != "检查更新") Image(painterResource(R.drawable.ic_chevron_right), null, Modifier.size(20.dp), colorFilter = ColorFilter.tint(pal.text2))
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
