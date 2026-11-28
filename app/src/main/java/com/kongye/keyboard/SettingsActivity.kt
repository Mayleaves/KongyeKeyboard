package com.kongye.keyboard

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import android.provider.OpenableColumns
import android.provider.Settings
import android.text.format.Formatter
import android.view.inputmethod.InputMethodManager
import android.widget.LinearLayout
import androidx.compose.animation.animateContentSize
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
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.core.content.ContextCompat
import androidx.compose.foundation.Image
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.disabled
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import kotlin.math.roundToInt

private val LocalPlainMenu=androidx.compose.runtime.staticCompositionLocalOf {false}

private class Pal(
    val bg: Color, val card: Color, val ink: Color, val text2: Color, val line: Color, val tile: Color,
    val track: Color, val accent: Color, val onAccent: Color, val ok: Color, val bad: Color,
) {
    val searchMatch = if (card == Color.White) Color(0xFFBC351C) else Color(0xFFFF8265)
}

private val LIGHT = Pal(Color(0xFFF4F5F8), Color(0xFFFFFFFF), Color(0xFF18191C), Color(0xFF686B73), Color(0xFFECEDF2), Color(0xFFF0F2F6), Color(0xFFE2E4EC), Color(0xFFF0492C), Color.White, Color(0xFF16A34A), Color(0xFFE11D48))
private val DARK = Pal(Color(0xFF0F1013), Color(0xFF1A1B20), Color(0xFFF4F5F8), Color(0xFF9498A3), Color(0xFF282A32), Color(0xFF24262E), Color(0xFF323540), Color(0xFFF0492C), Color.White, Color(0xFF4ADE80), Color(0xFFFB7185))

internal enum class Page(val title: String) {
    MAIN("空叶键盘"), LAYOUT("键盘布局"), KEYS("按键与手势"), PINYIN("中文与拼音"), LOOK("主题与外观"), HEIGHT("键盘调整"),
    FLICKS("上下滑"), ARRANGE("工具栏与菜单"), CLIP("剪贴板"), NUMPAD("数字键盘"), VAULT("密码填充"), UPDATE("检查更新"), HISTORY("更新历史"), RELEASE("更新详情"),
    READY("使用准备"), INPUT("输入设置"), APPEARANCE("外观与布局"), TOOLS("工具"), ABOUT("关于"),
    ENGLISH("英文与空格"), ASSOC("候选与联想"), GLIDE("滑行输入"), VOICE("语音输入"), HAND("手写输入"),
    DISPLAY("拼音与候选显示"), DELETE("删除键"), RESOURCES("离线资源"), PERMISSIONS("功能权限"), STATS("打字统计"), TRANSLATION("翻译"), CORRECTION("拼音纠错"), FUZZY("模糊音"), POLISH("语句润色"), SMS("短信验证码"), BACKUP("备份与恢复")
}

private val LocalNoticePage = staticCompositionLocalOf<Page?> { null }
private val LocalLayoutPreviewLandscape = staticCompositionLocalOf<Boolean?> { null }

/** 主页上的一行：可以被搜索 */
private class Item(val section: String, val icon: Int, val tint: Color, val title: String, val sub: String, val value: String?, val valueColor: Color?, val onClick: () -> Unit)

class SettingsActivity : ComponentActivity() {
    private var keyboardOverlay:android.app.Dialog?=null
    private val overlayShown=mutableStateOf(false)
    private val backupOptions=mutableStateOf(KeyboardBackup.Options())
    private val backupBusy=mutableStateOf(false)
    private val backupMessage=mutableStateOf("")
    private data class BackupFeedback(
        val title: String,
        val detail: String,
        val ok: Boolean?,
    )
    private val backupFeedback=mutableStateOf<Map<Boolean,BackupFeedback>>(emptyMap())
    private var pendingRestoreCloud=false
    private val pendingBackupRestore=mutableStateOf<KeyboardBackup.Prepared?>(null)
    private fun clearBackupFeedback(cloud:Boolean=false) { backupFeedback.value=backupFeedback.value-cloud }
    private fun reportBackup(operation:String,result:Result<String>,cloud:Boolean=false) {
        backupMessage.value=""
        backupFeedback.value=backupFeedback.value+(cloud to BackupFeedback(operation+if(result.isSuccess)"成功"else "失败",
            result.getOrElse {it.message ?: "操作失败，请重试。"},result.isSuccess))
    }
    private fun updateBackupOptions(options:KeyboardBackup.Options) {
        backupOptions.value=options;AutoWebDavBackup.setOptions(this,options)
    }
    private val backupExport=registerForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) {uri->
        if(uri!=null) {
            val options=backupOptions.value
            clearBackupFeedback();backupBusy.value=true;backupMessage.value="正在导出备份…"
            Thread {
                val result=runCatching {contentResolver.openOutputStream(uri,"w")?.use {KeyboardBackup.write(applicationContext,it,options)} ?: error("无法写入所选文件")}
                if(result.isSuccess)Prefs.of(applicationContext).edit().putLong("backup_last_export",System.currentTimeMillis()).apply()
                runOnUiThread {backupBusy.value=false;reportBackup("导出备份",result.map {"文件已保存到所选位置。"})}
            }.start()
        }
    }
    private data class ManagedBackup(
        val name: String,
        val bytes: Long,
        val read: () -> KeyboardBackup.Prepared,
        val delete: (() -> Unit)?,
    )

    private data class BackupListing(val source: String, val files: List<ManagedBackup>, val cloud:Boolean)
    private val backupListing = mutableStateOf<BackupListing?>(null)
    private val pendingBackupDelete = mutableStateOf<ManagedBackup?>(null)

    private val localBackupPicker = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) {
            runBackupWork("正在读取本地备份…", {
                require(uris.size <= 256) { "请一次选择不超过 256 份备份。" }
                uris.distinct().map(::localBackup)
            }) { openBackupListing("本地备份", it) }
        }
    }

    private fun localBackup(uri: Uri): ManagedBackup {
        val resolver = contentResolver
        val isDocument = DocumentsContract.isDocumentUri(this, uri)
        val projection = if (isDocument) arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE, Document.COLUMN_FLAGS)
            else arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        val (name, bytes, flags) = resolver.query(uri, projection, null, null, null)?.use { cursor ->
            check(cursor.moveToFirst()) { "无法读取所选文件的信息。" }
            val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
            val flagsIndex = cursor.getColumnIndex(Document.COLUMN_FLAGS)
            Triple(
                if (nameIndex >= 0) cursor.getString(nameIndex).orEmpty() else "",
                if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) cursor.getLong(sizeIndex) else -1L,
                if (flagsIndex >= 0) cursor.getInt(flagsIndex) else 0,
            )
        } ?: error("无法读取所选文件的信息。")
        val read = {
            resolver.openInputStream(uri)?.use { KeyboardBackup.read(applicationContext, it) }
                ?: error("无法读取所选文件。")
        }
        val canDelete = isDocument && (flags and Document.FLAG_SUPPORTS_DELETE) != 0 &&
            checkCallingOrSelfUriPermission(uri, Intent.FLAG_GRANT_WRITE_URI_PERMISSION) == PackageManager.PERMISSION_GRANTED
        return ManagedBackup(name.ifBlank { "所选文件" }, bytes, read, if (canDelete) {
            {
                // A renamed backup is still eligible, after confirming it is an app backup.
                if (!localBackupName.matches(name)) read().use {}
                check(DocumentsContract.deleteDocument(resolver, uri)) { "系统未能删除所选文件。" }
            }
        } else null)
    }

    private val localBackupName = Regex("(?:空叶键盘-备份-\\d{8}-\\d{4}|kongye-\\d{8}-\\d{6}-[0-9a-f]{8})(?: \\(\\d+\\))?\\.zip")

    private fun openBackupListing(source: String, result: Result<List<ManagedBackup>>, cloud:Boolean=false) {
        result.onSuccess { files ->
            if (files.isEmpty()) reportBackup("读取备份", Result.success("这里还没有空叶键盘备份。"),cloud)
            else backupListing.value = BackupListing(source, files, cloud)
        }.onFailure { reportBackup("读取备份", Result.failure(it),cloud) }
    }

    private fun <T> runBackupWork(message: String, work: () -> T, cloud:Boolean=false, complete: (Result<T>) -> Unit) {
        if (backupBusy.value) return
        backupFeedback.value = backupFeedback.value + (cloud to BackupFeedback("正在处理",message,null))
        backupMessage.value = message
        backupBusy.value = true
        Thread {
            val result = runCatching(work)
            runOnUiThread {
                backupBusy.value = false
                backupMessage.value = ""
                complete(result)
            }
        }.start()
    }

    private fun confirmBackupRestore(prepared:KeyboardBackup.Prepared,cloud:Boolean) {
        if(isDestroyed||isFinishing){prepared.close();return}
        pendingBackupRestore.value?.close()
        pendingBackupRestore.value=prepared
        pendingRestoreCloud=cloud
        backupMessage.value=""
        backupFeedback.value=backupFeedback.value+(cloud to BackupFeedback("备份已校验","请确认要恢复的内容。",null))
    }
    private data class NoticeFocus(val page: Page, val key: String, val serial: Long)
    private val noticeFocus=mutableStateOf<NoticeFocus?>(null)
    private var noticeSerial=0L
    private var noticeNavigation:((Page)->Unit)?=null

    @Composable
    private fun Modifier.noticeAttention(pal:Pal,key:String):Modifier {
        val request = noticeFocus.value
        if (request?.key != key || request.page != LocalNoticePage.current) return this
        val bring = remember(key) { BringIntoViewRequester() }
        val pulse = remember(key) { Animatable(0f) }
        LaunchedEffect(request.serial) {
            try {
                pulse.snapTo(0f)
                // Wait for page transition and layout restoration before locating the card.
                delay(180)
                bring.bringIntoView()
                delay(100)
                repeat(2) {
                    pulse.animateTo(.2f, tween(140))
                    pulse.animateTo(0f, tween(300))
                }
                delay(250)
            } finally {
                if (noticeFocus.value == request) noticeFocus.value = null
            }
        }
        return this.bringIntoViewRequester(bring).drawWithContent {
            drawContent()
            if (pulse.value > 0f) {
                drawRoundRect(pal.accent.copy(alpha = pulse.value), cornerRadius = CornerRadius(20.dp.toPx()))
            }
        }.semantics { stateDescription = "正在定位：$key" }
    }

    private var restoredActivity = false
    private val tick = mutableIntStateOf(0)
    private val prefListener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> tick.intValue++ }
    private var confirmAt = 0L
    private var confirmWhat = ""
    private val micRequest = registerForActivityResult(ActivityResultContracts.RequestPermission()) { tick.intValue++ }
    private val smsRequest = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted -> Prefs.of(this).edit().putBoolean(Prefs.SMS_CODE,granted).apply();tick.intValue++ }
    private val notificationRequest = registerForActivityResult(ActivityResultContracts.RequestPermission()) { tick.intValue++ }
    private val grammarPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) ResourcePacks.importGrammar(this, uri)
    }
    private var checkingUpdate by mutableStateOf(false)
    private val updateDialogVisible=mutableStateOf(false)
    private val downloadState=mutableStateOf("")
    private val downloadProgress=mutableStateOf<Float?>(null)
    private val validatingUpdate=mutableStateOf(false)
    private val downloadedApk=mutableStateOf<java.io.File?>(null)
    private val downloading=mutableStateOf(false)
    @Volatile private var updateDownloadCancelled=false
    /** 检查更新的状态：显示在“检查更新”那一行 */
    private val updateState = mutableStateOf("")
    private val release = mutableStateOf<UpdateChecker.Release?>(null)
    private val historyState=mutableStateOf<List<UpdateChecker.Release>>(emptyList())
    private val historyMessage=mutableStateOf("")
    private val historyLoading=mutableStateOf(false)
    private val selectedRelease=mutableStateOf<UpdateChecker.Release?>(null)
    private fun loadHistory() {
        if(historyLoading.value)return
        historyLoading.value=true;historyMessage.value="正在读取更新历史…"
        Thread {
            val result=runCatching {UpdateChecker.fetchHistory(applicationContext)}
            runOnUiThread {
                historyLoading.value=false
                result.onSuccess {historyState.value=it;historyMessage.value="${it.size} 个已发布版本"}
                    .onFailure {historyMessage.value="暂时无法刷新，显示本地历史"}
            }
        }.start()
    }

    private fun checkUpdate(silent: Boolean) {
        if (checkingUpdate || downloading.value || AppUpdate.isInstalling()) return
        checkingUpdate=true
        if (!silent) updateState.value = "检查中…"
        Thread {
            val r = runCatching {
                val found=UpdateChecker.fetch()
                val cached=java.io.File(cacheDir,"updates/update.apk").takeIf {it.isFile &&
                    runCatching {AppUpdate.validate(applicationContext,it,found.version,found.sha256)}.isSuccess}
                found to cached
            }
            runOnUiThread {
                checkingUpdate=false
                r.onSuccess { (rel,cached) ->
                    Prefs.of(this).edit().putLong(Prefs.UPDATE_CHECKED,System.currentTimeMillis()).putString("latest_update_tag",rel.tag).apply()
                    val newer = UpdateChecker.newer(rel.version, UpdateChecker.currentVersion(this))
                    release.value = rel
                    downloadedApk.value=cached
                    downloadState.value=if(cached!=null)"安装包已下载并通过校验" else ""
                    updateState.value = if (newer) "有新版本 ${rel.tag}" else if (silent) "" else "已是最新"
                }.onFailure { e -> if (!silent) {release.value=null;downloadedApk.value=null;updateState.value = "检查失败：${e.message ?: "网络不通"}"} }
            }
        }.start()
    }

    private fun openUpdateDialog() {
        updateDialogVisible.value=true
        if(release.value==null && AppUpdate.installation.value==AppUpdate.Installation.Idle)checkUpdate(false)
    }

    private fun downloadUpdate(rel:UpdateChecker.Release) {
        if(downloading.value || checkingUpdate || AppUpdate.isInstalling()) return
        downloading.value=true;downloadState.value="正在连接…";downloadedApk.value=null;updateDownloadCancelled=false
        downloadProgress.value=null;validatingUpdate.value=false
        Thread {
            val result=runCatching {AppUpdate.download(applicationContext,rel,{updateDownloadCancelled},progress={bytes,total ->
                runOnUiThread {
                    downloadProgress.value=if(total>0)(bytes.toFloat()/total).coerceIn(0f,1f) else null
                    downloadState.value=if(total>0) "${bytes*100/total}%" else "已下载 ${bytes/1024/1024} MB"
                }
            },validating={runOnUiThread {validatingUpdate.value=true}})}
            runOnUiThread {
                downloading.value=false;validatingUpdate.value=false
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
    private fun installUpdate(rel:UpdateChecker.Release) {
        val file=downloadedApk.value ?: return
        if(!packageManager.canRequestPackageInstalls()) {
            runCatching {startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,Uri.parse("package:$packageName")))}
                .onFailure {downloadState.value="无法打开系统安装权限设置。"}
            return
        }
        AppUpdate.install(this,file,rel)
    }

    private fun openUrl(url: String) {
        runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if(android.os.Build.VERSION.SDK_INT>=31)splashScreen.setOnExitAnimationListener {it.remove()}
        V3Prefs.migrate(this)
        backupOptions.value=AutoWebDavBackup.options(this)
        restoredActivity = savedInstanceState != null
        AppUpdate.restoreInstallation(this)
        updateDialogVisible.value=savedInstanceState?.getBoolean("update_dialog_visible",false)==true ||
            AppUpdate.isInstalling() || AppUpdate.installation.value==AppUpdate.Installation.Complete
        Thread({
            Lexicon.load(applicationContext)
            PinyinEngine.initialize(applicationContext)
        }, "Kongye Rime preload").start()
        enableEdgeToEdge()
        if(android.os.Build.VERSION.SDK_INT>=29)window.isNavigationBarContrastEnforced=false
        historyState.value=UpdateChecker.storedHistory(this)
        ClipStore.load(this)
        SmsCodeClipboard.schedule(this)
        Prefs.of(this).registerOnSharedPreferenceChangeListener(prefListener)
        if (intent?.getBooleanExtra("grant_mic", false) == true && !micGranted()) micRequest.launch(Manifest.permission.RECORD_AUDIO)
        setContent {
            Screen(tick.intValue)
        }
        if(updateDialogVisible.value)checkUpdate(false)
        else if(System.currentTimeMillis()-Prefs.of(this).getLong(Prefs.UPDATE_CHECKED,0L)>6*60*60*1000L)checkUpdate(true)
    }

    override fun onSaveInstanceState(outState:Bundle) {
        outState.putBoolean("update_dialog_visible",updateDialogVisible.value)
        super.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        pendingBackupRestore.value?.close();pendingBackupRestore.value=null
        keyboardOverlay?.dismiss();keyboardOverlay=null
        updateDownloadCancelled=true
        Prefs.of(this).unregisterOnSharedPreferenceChangeListener(prefListener)
        super.onDestroy()
    }

    override fun onResume() { super.onResume(); SmsCodeClipboard.expire(this); tick.intValue++ }

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
        val focusManager=LocalFocusManager.current
        val keyboardController=LocalSoftwareKeyboardController.current
        val pal = if (isSystemInDarkTheme()) DARK else LIGHT
        // Rotation recreates the Activity, but it is not a new app launch.
        // Also skip the intro if rotation interrupts it halfway through.
        var showSplash by rememberSaveable { mutableStateOf(!restoredActivity && intent?.getBooleanExtra("grant_mic", false) != true) }
        if (restoredActivity && showSplash) showSplash = false
        var page by rememberSaveable { mutableStateOf(Page.MAIN) }
        var mainQuery by rememberSaveable {mutableStateOf("")}
        val backStack=rememberSaveable(saver=androidx.compose.runtime.saveable.listSaver<MutableList<Page>,String>(
            save={pages->pages.map {it.name}},restore={names->names.map {Page.valueOf(it)}.toMutableList()})) {mutableListOf<Page>()}
        var mainExpanded by rememberSaveable {mutableStateOf<Page?>(null)}
        var backward by remember {mutableStateOf(false)}
        // 主页独立保存滚动状态：从子页面返回时回到原先浏览的位置，不会跳回最顶部
        val mainScroll = rememberScrollState()
        val scope=rememberCoroutineScope()
        val pageStateHolder=rememberSaveableStateHolder()

        fun navigateTo(target: Page) {
            if (target != page) {
                backward=false
                // A revisited destination moves to the front of recency history;
                // reciprocal prerequisite links must never build A/B/A/B loops.
                backStack.removeAll {it==target || it==page}
                backStack.add(page)
                page = target
            }
        }

        fun navigateBack() {
            backward=true
            noticeFocus.value=null
            page = if (backStack.isNotEmpty()) backStack.removeAt(backStack.lastIndex) else Page.MAIN
            backStack.removeAll {it==page}
        }

        SideEffect {noticeNavigation={target->focusManager.clearFocus(force=true);keyboardController?.hide();navigateTo(target)}}
        BackHandler(enabled = page != Page.MAIN) { navigateBack() }
        HomeSearchBackHandler(page==Page.MAIN && mainQuery.isNotEmpty() && !showSplash &&
            !overlayShown.value && !updateDialogVisible.value) {
            focusManager.clearFocus(force=true)
            keyboardController?.hide()
            mainQuery=""
            scope.launch {mainScroll.scrollTo(0)}
        }

        Box(Modifier.fillMaxSize().background(pal.bg)) {
            val contentInsets=WindowInsets.safeDrawing.only(WindowInsetsSides.Top+WindowInsetsSides.Horizontal)
                .union(WindowInsets.ime.only(WindowInsetsSides.Bottom))
            Column(Modifier.fillMaxSize().windowInsetsPadding(contentInsets),
                horizontalAlignment=Alignment.CenterHorizontally) {
            AnimatedContent(
                targetState=page,
                modifier=Modifier.weight(1f).widthIn(max=600.dp).fillMaxWidth(),
                transitionSpec={
                    if(backward) {
                        (androidx.compose.animation.EnterTransition.None togetherWith slideOutHorizontally(tween(110)) {it}).apply {targetContentZIndex=-1f}
                    } else {
                        (slideInHorizontally(tween(110)) {it} togetherWith androidx.compose.animation.ExitTransition.None).apply {targetContentZIndex=1f}
                    }
                }, label="settingsPage",
            ) { shownPage ->
            pageStateHolder.SaveableStateProvider(shownPage.name) {
            if (shownPage == Page.MAIN) {
                Column(
                    Modifier.fillMaxSize().background(pal.bg)
                        .verticalScroll(mainScroll).padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    MainPage(pal,tick,mainExpanded,{focusManager.clearFocus(force=true);keyboardController?.hide();mainExpanded=it},mainQuery,{mainQuery=it}) {navigateTo(it)}
                    Spacer(Modifier.height(92.dp))
                }
            } else {
                val subScroll = androidx.compose.runtime.key(shownPage) { rememberScrollState() }
                Column(
                    Modifier.fillMaxSize().background(pal.bg),
                ) {
                    // 固定顶部返回栏
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Row(
                            Modifier.clip(RoundedCornerShape(14.dp)).clickable(interactionSource = null, indication = null) { navigateBack() }.padding(horizontal = 8.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Image(painterResource(R.drawable.ic_chevron_left), null, Modifier.size(24.dp), colorFilter = ColorFilter.tint(pal.ink))
                            Spacer(Modifier.width(4.dp))
                            BasicText(shownPage.title, style = TextStyle(color = pal.ink, fontSize = 20.sp, fontWeight = FontWeight.Bold))
                        }
                        Spacer(Modifier.weight(1f))
                        if(shownPage==Page.HISTORY) {
                            HeaderAction(pal,R.drawable.ic_github,"打开 GitHub") {openUrl(UpdateChecker.REPOSITORY)}
                            HeaderAction(pal,R.drawable.ic_refresh,"刷新更新历史") {loadHistory()}
                        }
                    }
                    Line(pal)
                    if(shownPage==Page.HISTORY) Box(Modifier.weight(1f).padding(horizontal=16.dp)) {
                        HistoryPage(pal) {selectedRelease.value=it;navigateTo(Page.RELEASE)}
                    } else if(shownPage==Page.HEIGHT) HeightPage(pal,tick)
                    else if(shownPage==Page.BACKUP) BackupPage(pal)
                    else if(shownPage==Page.LAYOUT) CompositionLocalProvider(LocalNoticePage provides Page.LAYOUT) { LayoutPage(pal,tick) }
                    else Column(
                        Modifier.fillMaxSize().verticalScroll(subScroll).padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        CompositionLocalProvider(LocalNoticePage provides shownPage) {
                            when (shownPage) {
                                Page.LAYOUT -> LayoutPage(pal, tick)
                                Page.KEYS -> V3Page(pal,shownPage,tick) {navigateTo(it)}
                                Page.FLICKS -> FlicksPage(pal,tick) {navigateTo(it)}
                                Page.PINYIN -> V3Page(pal,shownPage,tick) {navigateTo(it)}
                                Page.LOOK -> LookPage(pal, tick)
                                Page.HEIGHT -> HeightPage(pal, tick)
                                Page.ARRANGE -> ArrangePage(pal, tick)
                                Page.CLIP -> ClipPage(pal, tick)
                                Page.NUMPAD -> NumpadPage(pal, tick)
                                Page.VAULT -> VaultPage(pal,tick)
                                Page.UPDATE -> Unit
                                Page.HISTORY -> HistoryPage(pal) {selectedRelease.value=it;navigateTo(Page.RELEASE)}
                                Page.RELEASE -> ReleasePage(pal)
                                Page.MAIN -> Unit
                                else -> V3Page(pal,shownPage,tick) {navigateTo(it)}
                            }
                        }
                        Spacer(Modifier.height(92.dp))
                    }
                }
            }

            }

            }

            }
            if(!showSplash && !overlayShown.value) {
                Box(Modifier.align(Alignment.BottomCenter).windowInsetsPadding(WindowInsets.safeDrawing).padding(bottom=6.dp)) {
                    Row(Modifier.width(240.dp).heightIn(min=54.dp).clip(RoundedCornerShape(50)).background(pal.card.copy(alpha=.78f))
                        .border(1.dp,pal.line,RoundedCornerShape(50)).clickable {
                            if(isCurrent())showKeyboardOverlay(pal) {focusManager.clearFocus(force=true)} else {navigateTo(Page.READY);android.widget.Toast.makeText(this@SettingsActivity,"请先启用并选择空叶键盘",android.widget.Toast.LENGTH_SHORT).show()}
                        }.padding(horizontal=24.dp,vertical=12.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.Center) {
                        Image(painterResource(R.drawable.ic_kbd),null,Modifier.size(20.dp),colorFilter=ColorFilter.tint(pal.ink))
                        Spacer(Modifier.width(10.dp));BasicText("显示键盘",style=TextStyle(color=pal.ink,fontSize=15.sp,fontWeight=FontWeight.Medium))
                    }
                }
            }
            if(updateDialogVisible.value)UpdateDialog(pal,tick) {updateDialogVisible.value=false;navigateTo(Page.HISTORY);loadHistory()}
            ManageBackupDialog(pal)
            BackupRestoreDialog(pal)
            if(backupBusy.value)androidx.compose.ui.window.Dialog(onDismissRequest={},properties=androidx.compose.ui.window.DialogProperties(dismissOnBackPress=false,dismissOnClickOutside=false)) {
                Column(Modifier.clip(RoundedCornerShape(20.dp)).background(pal.card).padding(24.dp),horizontalAlignment=Alignment.CenterHorizontally) {
                    AndroidView(factory={android.widget.ProgressBar(it)},modifier=Modifier.size(32.dp))
                    BasicText(backupMessage.value,style=TextStyle(color=pal.ink,fontSize=15.sp),modifier=Modifier.padding(top=16.dp))
                }
            }
            if (showSplash) {
                SplashScreen(pal) { showSplash = false }
            }
        }
    }

    private fun showKeyboardOverlay(pal:Pal,onClose:()->Unit) {
        if(keyboardOverlay?.isShowing==true)return
        val dialog=android.app.Dialog(this)
        val metrics=resources.displayMetrics
        val d=metrics.density
        fun dp(v:Int)=(v*d).roundToInt()
        val root=android.widget.FrameLayout(this).apply {
            setBackgroundColor(android.graphics.Color.argb(185,android.graphics.Color.red(pal.bg.toArgb()),android.graphics.Color.green(pal.bg.toArgb()),android.graphics.Color.blue(pal.bg.toArgb())))
        }
        val inputArea=android.widget.FrameLayout(this)
        val edit=android.widget.EditText(this).apply {
            textSize=27f;gravity=android.view.Gravity.CENTER
            setTextColor(pal.ink.toArgb());setHintTextColor(pal.text2.copy(alpha=.55f).toArgb())
            setBackgroundColor(android.graphics.Color.TRANSPARENT)
            inputType=android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
            imeOptions=android.view.inputmethod.EditorInfo.IME_FLAG_NO_EXTRACT_UI or android.view.inputmethod.EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
            importantForAutofill=android.view.View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
            minLines=1;maxLines=6;setPadding(dp(20),dp(12),dp(20),dp(12));isFocusableInTouchMode=true
            contentDescription="键盘效果输入框"
        }
        inputArea.addView(edit,android.widget.FrameLayout.LayoutParams(-1,-2,android.view.Gravity.CENTER))
        val close=android.widget.TextView(this).apply {
            text="×";textSize=27f;contentDescription="收起键盘";gravity=android.view.Gravity.CENTER;setTextColor(pal.text2.toArgb())
            setOnClickListener {getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(edit.windowToken,0);dialog.dismiss()}
        }
        inputArea.addView(close,android.widget.FrameLayout.LayoutParams(dp(48),dp(48),android.view.Gravity.TOP or android.view.Gravity.END).apply {setMargins(dp(8),dp(8),dp(8),dp(8))})
        root.addView(inputArea,android.widget.FrameLayout.LayoutParams(-1,-1))
        root.setOnClickListener {edit.requestFocus();getSystemService(InputMethodManager::class.java).showSoftInput(edit,InputMethodManager.SHOW_IMPLICIT)}
        if(android.os.Build.VERSION.SDK_INT>=30) {
            var animating=false
            var latest:android.view.WindowInsets?=null
            fun position(insets:android.view.WindowInsets) {
                val bottom=insets.getInsets(android.view.WindowInsets.Type.ime() or android.view.WindowInsets.Type.systemBars()).bottom
                val top=insets.getInsets(android.view.WindowInsets.Type.statusBars()).top
                val params=inputArea.layoutParams as android.widget.FrameLayout.LayoutParams
                if(params.bottomMargin!=bottom||params.topMargin!=top){params.bottomMargin=bottom;params.topMargin=top;inputArea.layoutParams=params}
            }
            root.setOnApplyWindowInsetsListener {_,insets->latest=insets;if(!animating)position(insets);insets}
            root.setWindowInsetsAnimationCallback(object:android.view.WindowInsetsAnimation.Callback(DISPATCH_MODE_CONTINUE_ON_SUBTREE) {
                override fun onPrepare(animation:android.view.WindowInsetsAnimation) {if(animation.typeMask and android.view.WindowInsets.Type.ime()!=0)animating=true}
                override fun onProgress(insets:android.view.WindowInsets,running:MutableList<android.view.WindowInsetsAnimation>):android.view.WindowInsets {position(insets);return insets}
                override fun onEnd(animation:android.view.WindowInsetsAnimation) {animating=false;latest?.let {position(it)}}
            })
        }
        dialog.setContentView(root)
        dialog.window?.apply {
            setBackgroundDrawableResource(android.R.color.transparent)
            clearFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            setWindowAnimations(R.style.KeyboardPreviewWindow)
            if(android.os.Build.VERSION.SDK_INT>=30) {
                setDecorFitsSystemWindows(false)
                setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING or android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN)
            } else setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE or android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN)
        }
        dialog.setOnDismissListener {
            edit.isFocusableInTouchMode=false;edit.isFocusable=false;edit.clearFocus()
            onClose()
            keyboardOverlay=null;overlayShown.value=false
            val imm=getSystemService(InputMethodManager::class.java)
            imm.hideSoftInputFromWindow(window.decorView.windowToken,0)
            if(android.os.Build.VERSION.SDK_INT>=30)window.insetsController?.hide(android.view.WindowInsets.Type.ime())
            window.decorView.post {
                if(!isDestroyed&&keyboardOverlay==null) {
                    imm.hideSoftInputFromWindow(window.decorView.windowToken,0)
                    if(android.os.Build.VERSION.SDK_INT>=30)window.insetsController?.hide(android.view.WindowInsets.Type.ime())
                }
            }
        }
        keyboardOverlay=dialog;overlayShown.value=true;dialog.show()
        dialog.window?.setLayout(-1,-1)
        root.requestApplyInsets();edit.requestFocus()
        edit.postDelayed({if(dialog.isShowing) {
            if(android.os.Build.VERSION.SDK_INT>=30)edit.windowInsetsController?.show(android.view.WindowInsets.Type.ime())
            else getSystemService(InputMethodManager::class.java).showSoftInput(edit,InputMethodManager.SHOW_IMPLICIT)
        }},80)
    }

    @Composable
    private fun SettingComparison(pal:Pal,title:String,key:String,current:Any,compareChoices:List<Pair<Any,String>> = emptyList(),label:(Any)->String) {
        val before=remember(key) {current}
        val previewLandscape = LocalLayoutPreviewLandscape.current
        val height = SettingExampleView.comparisonHeightDp(key).dp
        AndroidView(factory={ctx->SettingExampleView(ctx)},update={
            it.ink=pal.ink.toArgb();it.muted=pal.text2.toArgb();it.tile=pal.tile.toArgb();it.accent=pal.accent.toArgb()
            it.layoutLandscape = previewLandscape
            it.comparisonOptions=compareChoices;it.submit(key,title,before,current,label(before),label(current))
        },modifier=Modifier.fillMaxWidth().semantics {contentDescription="$title：修改前 ${label(before)}，当前 ${label(current)}"}.padding(top=2.dp,bottom=10.dp).height(height))
    }

    @Composable
    private fun ResourcesPage(pal:Pal) {
        var pendingRemoval by remember {mutableStateOf<ResourcePacks.Pack?>(null)}
        var pendingLanguageRemoval by remember {mutableStateOf<LanguageResources.Pack?>(null)}
        var refresh by remember {mutableIntStateOf(0)}
        LaunchedEffect(Unit) {while(true){delay(450);refresh++}}
        LaunchedEffect(Unit) {while(true){LanguageResources.refresh {refresh++};delay(1800)}}
        @Suppress("UNUSED_VARIABLE")val revision=refresh
        StatsHeading(pal,"内置资源")
        Card(pal) {
            BasicText("基础输入",style=TextStyle(color=pal.ink,fontSize=17.sp,fontWeight=FontWeight.Bold),modifier=Modifier.padding(top=12.dp))
            BasicText("基础拼音、常用词库和单字手写随软件提供，安装后即可使用。",style=TextStyle(color=pal.text2,fontSize=13.sp,lineHeight=19.sp),modifier=Modifier.padding(top=6.dp))
            BasicText("手写模型约 7 MB · 已内置",style=TextStyle(color=pal.ok,fontSize=12.sp),modifier=Modifier.padding(top=8.dp,bottom=12.dp))
        }
        StatsHeading(pal,"可选增强资源")
        val packs=remember {ResourcePacks.catalog(this).filterNot {ResourcePacks.bundled(this,it.id)}}
        packs.forEach {pack->
            val state=ResourcePacks.state(pack.id)
            val bundled=ResourcePacks.bundled(this,pack.id)
            val ready=ResourcePacks.ready(this,pack.id)
            Card(pal) {
                BasicText(pack.title,style=TextStyle(color=pal.ink,fontSize=17.sp,fontWeight=FontWeight.Bold),modifier=Modifier.padding(top=12.dp))
                BasicText(pack.summary,style=TextStyle(color=pal.text2,fontSize=13.sp,lineHeight=19.sp),modifier=Modifier.padding(top=6.dp))
                val occupied=ResourcePacks.installedBytes(this@SettingsActivity,pack.id)
                val bytes=if(ready)occupied else state.total.takeIf {it>0} ?: pack.bytes
                Row(Modifier.fillMaxWidth().padding(top=6.dp,bottom=6.dp),verticalAlignment=Alignment.CenterVertically) {
                    BasicText("约 ${"%.1f".format(bytes/1000000.0)} MB · ${if(bundled)"已内置，离线可用" else if(ready)"已安装" else "未安装"}",
                        style=TextStyle(color=if(ready || bundled)pal.ok else pal.text2,fontSize=12.sp,lineHeight=18.sp),modifier=Modifier.weight(1f).padding(end=8.dp))
                    Row(horizontalArrangement=Arrangement.spacedBy(8.dp),verticalAlignment=Alignment.CenterVertically) {
                        when {
                            bundled->Unit
                            state.removing->BasicText("正在删除…",style=TextStyle(color=pal.text2,fontSize=12.sp))
                            state.busy->ResourceAction(pal,"取消") {ResourcePacks.cancel(pack.id)}
                            else->{
                                if(occupied>0)ResourceAction(pal,"删除",if(pal==DARK)Color(0xFFE1A29A) else Color(0xFFAD5149)) {pendingRemoval=pack}
                                if(!ready)ResourceAction(pal,if(occupied>0)"继续" else "下载",if(pal==DARK)Color(0xFF9ABFEA) else Color(0xFF356A9C)) {ResourcePacks.start(applicationContext,pack.id)}
                            }
                        }
                    }
                }
                if(pack.id=="grammar" && !ready && !state.busy) {
                    ResourceAction(pal,"从文件导入") { grammarPicker.launch(arrayOf("*/*")) }
                }
                if(state.busy) {
                    val fraction=if(state.total>0)(state.downloaded.toFloat()/state.total).coerceIn(0f,1f) else 0f
                    Box(Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(50)).background(pal.track)) {
                        Box(Modifier.fillMaxWidth(fraction).height(4.dp).background(pal.accent))
                    }
                    BasicText("${(fraction*100).toInt()}% · ${"%.1f".format(state.downloaded/1000000.0)} MB",
                        style=TextStyle(color=pal.text2,fontSize=12.sp),modifier=Modifier.padding(top=6.dp,bottom=8.dp))
                } else if(state.message.isNotBlank())BasicText(state.message,style=TextStyle(color=if(ready)pal.ok else pal.text2,fontSize=12.sp),modifier=Modifier.padding(bottom=8.dp))
            }
        }
        StatsHeading(pal,"翻译与备用识别")
        LanguageResources.packs().forEach {pack->
            val state=LanguageResources.state(pack)
            Card(pal) {
                BasicText(pack.title,style=TextStyle(color=pal.ink,fontSize=17.sp,fontWeight=FontWeight.Bold),modifier=Modifier.padding(top=12.dp))
                BasicText(pack.summary,style=TextStyle(color=pal.text2,fontSize=13.sp,lineHeight=19.sp),modifier=Modifier.padding(top=6.dp))
                Row(Modifier.fillMaxWidth().padding(top=6.dp,bottom=6.dp),verticalAlignment=Alignment.CenterVertically) {
                    BasicText("约 ${pack.approximateMb} MB · ${if(state.installed)"已安装" else "未安装"}",
                        style=TextStyle(color=if(state.installed)pal.ok else pal.text2,fontSize=12.sp),modifier=Modifier.weight(1f))
                    if(state.busy)BasicText(state.message,style=TextStyle(color=pal.text2,fontSize=12.sp))
                    else if(state.installed)ResourceAction(pal,"删除",if(pal==DARK)Color(0xFFE1A29A) else Color(0xFFAD5149)) {pendingLanguageRemoval=pack}
                    else ResourceAction(pal,if(state.message.startsWith("下载失败"))"继续" else "下载",if(pal==DARK)Color(0xFF9ABFEA) else Color(0xFF356A9C)) {LanguageResources.download(pack)}
                }
                if(!state.busy && state.message.isNotBlank())BasicText(state.message,style=TextStyle(color=pal.text2,fontSize=12.sp),modifier=Modifier.padding(bottom=8.dp))
            }
        }
        pendingLanguageRemoval?.let {pack->
            androidx.compose.ui.window.Dialog(onDismissRequest={pendingLanguageRemoval=null},properties=androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth=false)) {
                Column(Modifier.widthIn(max=420.dp).fillMaxWidth(.9f).clip(RoundedCornerShape(14.dp)).background(pal.card).padding(24.dp)) {
                    BasicText("删除"+pack.title+"？",style=TextStyle(color=pal.ink,fontSize=19.sp,fontWeight=FontWeight.Bold))
                    BasicText("删除后该项增强功能需重新下载资源。基础打字和内置手写保留。",style=TextStyle(color=pal.text2,fontSize=15.sp,lineHeight=23.sp),modifier=Modifier.padding(top=20.dp,bottom=24.dp))
                    Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween,verticalAlignment=Alignment.CenterVertically) {
                        BasicText("取消",style=TextStyle(color=pal.text2,fontSize=14.sp),modifier=Modifier.clickable {pendingLanguageRemoval=null}.padding(10.dp))
                        Pill(pal,"删除",true) {pendingLanguageRemoval=null;LanguageResources.remove(pack)}
                    }
                }
            }
        }
        pendingRemoval?.let {pack->
            androidx.compose.ui.window.Dialog(onDismissRequest={pendingRemoval=null},properties=androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth=false)) {
                Column(Modifier.widthIn(max=420.dp).fillMaxWidth(.9f).clip(RoundedCornerShape(14.dp)).background(pal.card).padding(24.dp)) {
                    BasicText("删除"+pack.title+"？",style=TextStyle(color=pal.ink,fontSize=19.sp,fontWeight=FontWeight.Bold))
                    BasicText("将释放约 "+String.format("%.1f",ResourcePacks.installedBytes(this@SettingsActivity,pack.id)/1000000.0)+" MB，可随时重新下载。基础词库和学习记录保留。",
                        style=TextStyle(color=pal.text2,fontSize=15.sp,lineHeight=23.sp),modifier=Modifier.padding(top=20.dp,bottom=24.dp))
                    Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween,verticalAlignment=Alignment.CenterVertically) {
                        BasicText("取消",style=TextStyle(color=pal.text2,fontSize=14.sp),modifier=Modifier.clip(RoundedCornerShape(10.dp)).clickable {pendingRemoval=null}.padding(top=10.dp,bottom=10.dp,end=12.dp))
                        Pill(pal,"删除",true) {
                            pendingRemoval=null
                            ResourcePacks.remove(applicationContext,pack.id) {_,message->android.widget.Toast.makeText(this@SettingsActivity,message,android.widget.Toast.LENGTH_SHORT).show();refresh++}
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun ResourceAction(pal:Pal,label:String,tint:Color?=null,onClick:()->Unit) {
        val color = tint ?: pal.text2
        Row(
            Modifier.height(40.dp).widthIn(min = 52.dp).clip(RoundedCornerShape(10.dp))
                .clickable(role = androidx.compose.ui.semantics.Role.Button, onClick = onClick)
                .padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            BasicText(label, style = TextStyle(color = color, fontSize = 13.sp, fontWeight = FontWeight.Medium))
        }
    }

    @Composable
    private fun BackupPage(pal:Pal) {
        val options=backupOptions.value
        val pager=rememberPagerState(pageCount={2})
        val scope=rememberCoroutineScope()
        val focus=LocalFocusManager.current
        val keyboardController=LocalSoftwareKeyboardController.current
        var contents by rememberSaveable {mutableStateOf(false)}
        var resetConfirm by rememberSaveable {mutableStateOf(false)}
        LaunchedEffect(pager.settledPage) {
            focus.clearFocus()
            keyboardController?.hide()
        }
        Column(Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxWidth().padding(horizontal=16.dp,vertical=12.dp)) {
                Card(pal) {
                    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable {resetConfirm=true}.padding(vertical=10.dp),
                        verticalAlignment=Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            BasicText("恢复默认设置",style=TextStyle(color=pal.ink,fontSize=16.sp,fontWeight=FontWeight.Medium))
                            BasicText("重置全部功能配置，保留个人数据",style=TextStyle(color=pal.text2,fontSize=12.5.sp),modifier=Modifier.padding(top=4.dp))
                        }
                        HeaderAction(pal,R.drawable.ic_refresh,"重置全部设置") {resetConfirm=true}
                    }
                }
            }
            Box(Modifier.fillMaxWidth().padding(horizontal=16.dp).padding(bottom=12.dp)) {
                Segment(pal,listOf("本地备份","WebDAV 备份"),pager.currentPage) {method->
                    scope.launch {pager.animateScrollToPage(method)}
                }
            }
            HorizontalPager(state=pager,modifier=Modifier.weight(1f).fillMaxWidth()) {method->
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                    BackupFeedbackBar(pal,cloud=method==1)
                    Column(Modifier.fillMaxWidth().padding(horizontal=16.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
                        if(method==0) {
                            BackupActions(pal,cloud=false,onCreate={
                                val timestamp=java.text.SimpleDateFormat("yyyyMMdd-HHmm",java.util.Locale.ROOT).format(java.util.Date())
                                backupExport.launch("空叶键盘-备份-$timestamp.zip")
                            },onManage={localBackupPicker.launch(arrayOf("*/*"))})
                        } else WebDavBackupCard(pal,active=pager.settledPage==method)
                        Card(pal) {
                            Row(Modifier.fillMaxWidth().clickable {contents=!contents}.padding(vertical=14.dp),verticalAlignment=Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    BasicText("备份内容",style=TextStyle(color=pal.ink,fontSize=16.sp,fontWeight=FontWeight.Medium))
                                    BasicText(listOfNotNull("设置",if(options.learning)"自造词与选词记录" else null,
                                        if(options.statistics)"打字统计" else null,if(options.clipboard)"剪贴板" else null).joinToString("、"),
                                        style=TextStyle(color=pal.text2,fontSize=12.sp,lineHeight=18.sp),modifier=Modifier.padding(top=4.dp))
                                }
                                ExpandArrow(pal,contents)
                            }
                            if(contents) {
                                Line(pal)
                                BasicText("设置、自定义按键和布局始终备份。以下选择适用于本地和 WebDAV。",
                                    style=TextStyle(color=pal.text2,fontSize=12.sp,lineHeight=19.sp),modifier=Modifier.padding(top=12.dp,bottom=4.dp))
                                ToggleRow(pal,"自造词与选词记录",null,options.learning) {updateBackupOptions(options.copy(learning=it))}
                                ToggleRow(pal,"打字统计",null,options.statistics) {updateBackupOptions(options.copy(statistics=it))}
                                ToggleRow(pal,"剪贴板","包含文本、图片和自定义分类",options.clipboard) {updateBackupOptions(options.copy(clipboard=it))}
                            }
                        }
                        BasicText("离线模型、系统权限和密钥不备份。文件可能包含个人词汇与剪贴板，请妥善保管。",
                            style=TextStyle(color=pal.text2,fontSize=12.sp,lineHeight=19.sp),modifier=Modifier.padding(horizontal=4.dp,vertical=4.dp))
                        Spacer(Modifier.height(106.dp))
                    }
                }
            }
        }
        if(resetConfirm) androidx.compose.ui.window.Dialog(onDismissRequest={resetConfirm=false},properties=androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth=false)) {
            Column(Modifier.widthIn(max=420.dp).fillMaxWidth(.9f).clip(RoundedCornerShape(14.dp)).background(pal.card).padding(24.dp)) {
                BasicText("恢复默认设置？",style=TextStyle(color=pal.ink,fontSize=19.sp,fontWeight=FontWeight.Bold))
                BasicText("外观、布局、输入、手势等全部功能配置将恢复默认，已保存的联网接口和 WebDAV 连接配置会清除。",style=TextStyle(color=pal.ink,fontSize=15.sp,lineHeight=23.sp),modifier=Modifier.padding(top=18.dp))
                BasicText("词库、学习记录、剪贴板、打字统计和已下载资源保留。",style=TextStyle(color=pal.text2,fontSize=13.sp,lineHeight=21.sp),modifier=Modifier.padding(top=12.dp,bottom=22.dp))
                Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween,verticalAlignment=Alignment.CenterVertically) {
                    BasicText("取消",style=TextStyle(color=pal.text2,fontSize=14.sp),modifier=Modifier.clip(RoundedCornerShape(10.dp)).clickable {resetConfirm=false}.padding(top=10.dp,bottom=10.dp,end=12.dp))
                    Pill(pal,"恢复默认",true) {
                        resetConfirm=false
                        runCatching {InstallDefaults.reset(this@SettingsActivity)}.onSuccess {
                            backupOptions.value=KeyboardBackup.Options()
                            android.widget.Toast.makeText(this@SettingsActivity,"已恢复默认设置",android.widget.Toast.LENGTH_SHORT).show()
                            recreate()
                        }.onFailure {reportBackup("恢复默认设置",Result.failure(it),pager.currentPage==1)}
                    }
                }
            }
        }
    }

    @Composable
    private fun BackupFeedbackBar(pal:Pal,cloud:Boolean) {
        // Each destination owns its feedback, including while the other page is visible.
        androidx.compose.runtime.key(cloud) {
            AnimatedContent(targetState=backupFeedback.value[cloud],
                transitionSpec={
                    (fadeIn(tween(180,delayMillis=50)) togetherWith fadeOut(tween(120)))
                        .using(androidx.compose.animation.SizeTransform {_,_->tween(220)})
                },label="backupFeedback") {result->
                if(result!=null) {
                    val color=when(result.ok) {true->pal.ok;false->pal.bad;null->pal.text2}
                    Row(Modifier.fillMaxWidth().padding(horizontal=16.dp).padding(bottom=12.dp)
                        .clip(RoundedCornerShape(16.dp)).background(color.copy(alpha=.06f))
                        .padding(start=14.dp,end=6.dp,top=10.dp,bottom=10.dp)
                        .semantics {liveRegion=androidx.compose.ui.semantics.LiveRegionMode.Polite},
                        verticalAlignment=Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            BasicText(result.title,style=TextStyle(color=color,fontSize=14.sp,fontWeight=FontWeight.Bold))
                            BasicText(result.detail,style=TextStyle(color=pal.ink,fontSize=12.5.sp,lineHeight=19.sp),
                                modifier=Modifier.padding(top=3.dp))
                        }
                        HeaderAction(pal,R.drawable.ic_close,"关闭操作提示") {clearBackupFeedback(cloud)}
                    }
                }
            }
        }
    }

    @Composable
    private fun BackupActions(pal: Pal, cloud: Boolean, onCreate: () -> Unit, onManage: () -> Unit) {
        Card(pal) {
            val destination = if (cloud) "上传到 WebDAV" else "保存到所选位置"
            ActionRow(pal, "新建备份", destination, if (cloud) "上传" else "导出", onCreate)
            Line(pal)
            val description = if (cloud) "查看、恢复或删除云端备份" else "选择文件后可恢复或删除"
            ActionRow(pal, "管理备份", description, if (cloud) "查看" else "选择文件", onManage)
        }
    }

    @Composable
    private fun ManageBackupDialog(pal: Pal) {
        val listing = backupListing.value ?: return
        val target = pendingBackupDelete.value
        if (target != null) {
            val delete = target.delete ?: return
            val cancel = { pendingBackupDelete.value = null }
            androidx.compose.ui.window.Dialog(onDismissRequest = cancel,
                properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)) {
                Column(Modifier.widthIn(max = 440.dp).fillMaxWidth(.91f).clip(RoundedCornerShape(24.dp)).background(pal.card).padding(24.dp)) {
                    BasicText("删除${listing.source}？", style = TextStyle(color = pal.ink, fontSize = 20.sp, fontWeight = FontWeight.Bold))
                    BasicText(target.name, style = TextStyle(color = pal.ink, fontSize = 14.sp, lineHeight = 21.sp),
                        modifier = Modifier.padding(top = 14.dp), maxLines = 3, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                    BasicText("将永久删除这份备份文件。", style = TextStyle(color = pal.text2, fontSize = 13.sp, lineHeight = 20.sp),
                        modifier = Modifier.padding(top = 10.dp, bottom = 22.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        ResourceAction(pal, "取消", onClick = cancel)
                        Pill(pal, "确认删除", true) {
                            if (!backupBusy.value && pendingBackupDelete.value === target) {
                                pendingBackupDelete.value = null
                                backupListing.value = null
                                runBackupWork("正在删除备份…", delete, cloud=listing.cloud) { result ->
                                    reportBackup("删除备份", result.map { "已删除「${target.name}」。" },listing.cloud)
                                    if (result.isSuccess) {
                                        val remaining = listing.files.filterNot { it === target }
                                        if (remaining.isNotEmpty()) backupListing.value = listing.copy(files = remaining)
                                    }
                                }
                            }
                        }
                    }
                }
            }
            return
        }

        val close = { backupListing.value = null }
        androidx.compose.ui.window.Dialog(onDismissRequest = close,
            properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)) {
            Column(Modifier.widthIn(max = 440.dp).fillMaxWidth(.91f).heightIn(max = 580.dp)
                .clip(RoundedCornerShape(24.dp)).background(pal.card).padding(22.dp)) {
                BasicText(listing.source, style = TextStyle(color = pal.ink, fontSize = 20.sp, fontWeight = FontWeight.Bold))
                BasicText("共 ${listing.files.size} 份备份", style = TextStyle(color = pal.text2, fontSize = 12.sp, lineHeight = 19.sp),
                    modifier = Modifier.padding(top = 5.dp, bottom = 14.dp))
                LazyColumn(Modifier.weight(1f, fill = false), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(listing.files.size) { index ->
                        val file = listing.files[index]
                        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(pal.tile).padding(horizontal = 14.dp, vertical = 10.dp)) {
                            BasicText(file.name, style = TextStyle(color = pal.ink, fontSize = 14.sp, lineHeight = 21.sp, fontWeight = FontWeight.Medium),
                                maxLines = 2, overflow = TextOverflow.Ellipsis)
                            if (file.bytes >= 0) {
                                BasicText(Formatter.formatShortFileSize(this@SettingsActivity, file.bytes),
                                    style = TextStyle(color = pal.text2, fontSize = 12.sp), modifier = Modifier.padding(top = 3.dp))
                            }
                            Row(Modifier.fillMaxWidth().padding(top = 3.dp), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                                if (file.delete != null) {
                                    ResourceAction(pal, "删除", pal.bad) { pendingBackupDelete.value = file }
                                } else {
                                    BasicText("系统不允许删除", style = TextStyle(color = pal.text2, fontSize = 12.sp), modifier = Modifier.padding(end = 8.dp))
                                }
                                ResourceAction(pal, "恢复", pal.ink) {
                                    backupListing.value = null
                                    runBackupWork("正在校验备份…", file.read, cloud=listing.cloud) { result ->
                                        result.onSuccess {confirmBackupRestore(it,listing.cloud)}.onFailure { reportBackup("读取备份", Result.failure(it),listing.cloud) }
                                    }
                                }
                            }
                        }
                    }
                }
                Box(Modifier.align(Alignment.End).padding(top = 10.dp)) {
                    ResourceAction(pal, "关闭", onClick = close)
                }
            }
        }
    }

    @Composable
    private fun BackupRestoreDialog(pal:Pal) {
        val prepared=pendingBackupRestore.value ?: return
        val cloud=pendingRestoreCloud
        fun dismiss() {pendingBackupRestore.value=null;prepared.close();clearBackupFeedback(cloud)}
        val options=prepared.options
        val contents=listOfNotNull("设置与自定义按键",if(options.learning)"自造词与选词记录"else null,
            if(options.statistics)"打字统计"else null,if(options.clipboard)"剪贴板文字、图片与分类"else null)
        val locale=androidx.compose.ui.platform.LocalConfiguration.current.locales.get(0) ?: java.util.Locale.ROOT
        val time=java.text.SimpleDateFormat("yyyy-MM-dd HH:mm",locale).format(java.util.Date(prepared.created))
        androidx.compose.ui.window.Dialog(onDismissRequest={dismiss()},properties=androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth=false)) {
            Column(Modifier.widthIn(max=440.dp).fillMaxWidth(.91f).heightIn(max=640.dp)
                .clip(RoundedCornerShape(24.dp)).background(pal.card).verticalScroll(rememberScrollState()).padding(24.dp)) {
                Row(verticalAlignment=Alignment.CenterVertically) {
                    Box(Modifier.size(42.dp).clip(RoundedCornerShape(14.dp)).background(pal.accent.copy(alpha=.1f)),contentAlignment=Alignment.Center) {
                        Image(painterResource(R.drawable.ic_backup),null,Modifier.size(23.dp),colorFilter=ColorFilter.tint(pal.accent))
                    }
                    Column(Modifier.padding(start=12.dp)) {
                        BasicText("恢复备份",style=TextStyle(color=pal.ink,fontSize=21.sp,fontWeight=FontWeight.Bold))
                        BasicText("备份文件已校验",style=TextStyle(color=pal.text2,fontSize=12.sp),modifier=Modifier.padding(top=3.dp))
                    }
                }
                Column(Modifier.fillMaxWidth().padding(top=20.dp).clip(RoundedCornerShape(14.dp)).background(pal.tile).padding(14.dp)) {
                    Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) {
                        BasicText("备份时间",style=TextStyle(color=pal.text2,fontSize=12.sp))
                        BasicText(time,style=TextStyle(color=pal.ink,fontSize=13.sp,fontWeight=FontWeight.Medium))
                    }
                    Row(Modifier.fillMaxWidth().padding(top=10.dp),horizontalArrangement=Arrangement.SpaceBetween) {
                        BasicText("应用版本",style=TextStyle(color=pal.text2,fontSize=12.sp))
                        BasicText(prepared.version,style=TextStyle(color=pal.ink,fontSize=13.sp,fontWeight=FontWeight.Medium))
                    }
                }
                BasicText("将恢复以下内容",style=TextStyle(color=pal.ink,fontSize=14.sp,fontWeight=FontWeight.Medium),modifier=Modifier.padding(top=20.dp,bottom=8.dp))
                contents.forEach {item->
                    Row(Modifier.fillMaxWidth().padding(vertical=6.dp),verticalAlignment=Alignment.CenterVertically) {
                        Box(Modifier.size(6.dp).clip(CircleShape).background(pal.text2))
                        BasicText(item,style=TextStyle(color=pal.ink,fontSize=14.sp,lineHeight=21.sp),modifier=Modifier.padding(start=10.dp))
                    }
                }
                BasicText("以上内容将被覆盖，其他数据保留。",style=TextStyle(color=pal.text2,fontSize=12.sp,lineHeight=19.sp),modifier=Modifier.padding(top=12.dp,bottom=22.dp))
                Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(10.dp)) {
                    Box(Modifier.weight(1f).height(46.dp).clip(RoundedCornerShape(14.dp)).background(pal.tile).clickable {dismiss()},contentAlignment=Alignment.Center) {
                        BasicText("取消",style=TextStyle(color=pal.ink,fontSize=15.sp,fontWeight=FontWeight.Medium))
                    }
                    Box(Modifier.weight(1f).height(46.dp).clip(RoundedCornerShape(14.dp)).background(pal.accent).clickable {
                        pendingBackupRestore.value=null
                        clearBackupFeedback(cloud);backupBusy.value=true;backupMessage.value="正在恢复备份…"
                        Thread {
                            val result=runCatching {prepared.use {KeyboardBackup.restore(applicationContext,it)}}
                            runOnUiThread {
                                backupBusy.value=false
                                reportBackup("恢复备份",result.map {"已载入备份设置和所选数据"},cloud)
                                if(result.isSuccess) {backupOptions.value=AutoWebDavBackup.options(this@SettingsActivity);runCatching {AutoWebDavBackup.sync(this@SettingsActivity)}}
                                tick.intValue++
                            }
                        }.start()
                    },contentAlignment=Alignment.Center) {
                        BasicText("确认恢复",style=TextStyle(color=pal.onAccent,fontSize=15.sp,fontWeight=FontWeight.Medium))
                    }
                }
            }
        }
    }

    @Composable
    private fun WebDavBackupCard(pal:Pal,active:Boolean=true) {
        val initial=remember {runCatching {WebDavBackup.read(this)}.getOrDefault(WebDavBackup.Config())}
        var address by remember {mutableStateOf(initial.address)}
        var user by remember {mutableStateOf(initial.user)}
        var password by remember {mutableStateOf("")}
        var storedPassword by remember {mutableStateOf(initial.password)}
        var configOpen by remember {mutableStateOf(false)}
        val focus=LocalFocusManager.current
        val keyboardController=LocalSoftwareKeyboardController.current
        LaunchedEffect(active) { if (!active) configOpen = false }
        fun config()=WebDavBackup.Config(address.trim(),user,if(password.isEmpty())storedPassword else password)
        fun save():WebDavBackup.Config {
            val value=WebDavBackup.userConfig(config());WebDavBackup.save(this@SettingsActivity,value);address=value.address;storedPassword=value.password;password=""
            focus.clearFocus();keyboardController?.hide();return value
        }
        fun remote(
            message: String,
            operation: String,
            work: (WebDavBackup.Config) -> String,
        ) {
            if (backupBusy.value) return
            val value = runCatching { save() }.getOrElse {
                reportBackup(operation, Result.failure(it),true)
                return
            }
            runBackupWork(message, { work(value) },cloud=true) { result ->
                reportBackup(operation, result,true)
            }
        }
        Card(pal) {
            Row(Modifier.fillMaxWidth().clickable { configOpen = !configOpen }.padding(vertical=14.dp),verticalAlignment=Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    BasicText("服务器配置",style=TextStyle(color=pal.ink,fontSize=16.sp,fontWeight=FontWeight.Medium))
                    BasicText(if(address.isBlank())"未配置" else "已保存 WebDAV 连接",style=TextStyle(color=pal.text2,fontSize=12.sp),modifier=Modifier.padding(top=4.dp))
                }
                ExpandArrow(pal,configOpen)
            }
            if(configOpen) {
                BasicText("坚果云填写服务器地址即可，上传时自动准备备份目录。",style=TextStyle(color=pal.text2,fontSize=13.sp,lineHeight=20.sp))
                BasicText("服务器地址（HTTPS）",style=TextStyle(color=pal.text2,fontSize=13.sp),modifier=Modifier.padding(top=8.dp))
                Field(pal,address){address=it;clearBackupFeedback(true)}
                BasicText("账号",style=TextStyle(color=pal.text2,fontSize=13.sp),modifier=Modifier.padding(top=6.dp))
                Field(pal,user){user=it;clearBackupFeedback(true)}
                BasicText(if(storedPassword.isEmpty())"密码 / 应用专用密码" else "密码已保存，留空沿用",style=TextStyle(color=pal.text2,fontSize=13.sp),modifier=Modifier.padding(top=6.dp))
                BasicTextField(value=password,onValueChange={password=it;clearBackupFeedback(true)},singleLine=true,
                    visualTransformation=androidx.compose.ui.text.input.PasswordVisualTransformation(mask='\u2022'),
                    keyboardOptions=androidx.compose.foundation.text.KeyboardOptions(keyboardType=androidx.compose.ui.text.input.KeyboardType.Password,autoCorrectEnabled=false),
                    decorationBox={inner->Box {if(password.isEmpty()&&storedPassword.isNotEmpty())BasicText("••••••••",style=TextStyle(color=pal.text2,fontSize=17.sp));inner()}},
                    textStyle=TextStyle(color=pal.ink,fontSize=17.sp),cursorBrush=SolidColor(pal.accent),
                    modifier=Modifier.fillMaxWidth().semantics {contentDescription="WebDAV 密码"}.padding(vertical=6.dp).clip(RoundedCornerShape(12.dp)).background(pal.tile).padding(horizontal=12.dp,vertical=10.dp))
                Row(Modifier.fillMaxWidth().padding(vertical=8.dp),horizontalArrangement=Arrangement.spacedBy(10.dp)) {
                    Pill(pal,"保存配置",false) {
                        val result=runCatching {save();AutoWebDavBackup.sync(this@SettingsActivity);"WebDAV 配置已保存"}
                        if (result.isSuccess) configOpen = false
                        reportBackup("保存配置",result,true)
                    }
                    Pill(pal,"测试连接",false) {remote("正在测试 WebDAV 连接…", "连接测试") {
                        val count=WebDavBackup.list(it).size
                        if(count==0)"目录可以读取，暂无备份。上传权限将在上传时确认。"
                        else "目录可以读取，找到 "+count+" 份备份。上传权限将在上传时确认。"
                    }}
                }
                Line(pal,top=8.dp)
                BasicText("清除服务器配置",style=TextStyle(color=pal.text2,fontSize=13.sp),modifier=Modifier.clip(RoundedCornerShape(8.dp)).clickable {
                    AutoWebDavBackup.disable(this@SettingsActivity)
                    WebDavBackup.clear(this@SettingsActivity);address="";user="";password="";storedPassword=""
                    reportBackup("清除配置",Result.success("本机连接及自动备份已关闭，云端备份保留。"),true);tick.intValue++
                }.padding(vertical=14.dp))
            }
        }
        BackupActions(pal, cloud = true, onCreate = {
            val options = backupOptions.value
            remote("正在上传 WebDAV 备份…", "上传备份") {
                WebDavBackup.upload(applicationContext, it, options)
                "备份已保存到 WebDAV。"
            }
        }, onManage = manage@{
            if (backupBusy.value) return@manage
            val value = runCatching { save() }.getOrElse {
                reportBackup("读取备份", Result.failure(it),true)
                return@manage
            }
            runBackupWork("正在读取 WebDAV 备份…", {
                WebDavBackup.list(value).map { file ->
                    ManagedBackup(file.name, file.bytes,
                        read = { WebDavBackup.download(applicationContext, value, file) },
                        delete = { WebDavBackup.delete(value, file) })
                }
            },cloud=true) { openBackupListing("云端备份", it,cloud=true) }
        })
        Card(pal) {
            val days=AutoWebDavBackup.frequency(this)
            SettingsChoice(pal,"备份频率",AutoWebDavBackup.FREQUENCY,listOf(0 to "仅手动",1 to "每天",7 to "每周",30 to "每30天"),0,tick.intValue,showExample=false,inCard=false,inlineDropdown=true) {
                val result=runCatching {
                    if(AutoWebDavBackup.frequency(this@SettingsActivity)>0)save()
                    AutoWebDavBackup.sync(this@SettingsActivity)
                    if(AutoWebDavBackup.frequency(this@SettingsActivity)==0)"已关闭自动备份，可随时手动上传。" else "将按所选频率自动上传，沿用所选备份内容。"
                }
                if(result.isFailure) {
                    Prefs.of(this@SettingsActivity).edit().putInt(AutoWebDavBackup.FREQUENCY,days).commit()
                    runCatching {AutoWebDavBackup.sync(this@SettingsActivity)}
                }
                reportBackup("备份频率",result,true);tick.intValue++
            }
            if(days>0) {
                BasicText("联网且手机空闲时自动上传，省电设置可能延后执行。",style=TextStyle(color=pal.text2,fontSize=12.sp,lineHeight=19.sp),modifier=Modifier.padding(start=6.dp,end=6.dp,bottom=10.dp))
                Line(pal)
                val last=AutoWebDavBackup.lastResult(this)
                BasicText(last.first,style=TextStyle(color=pal.text2,fontSize=12.sp,lineHeight=19.sp),modifier=Modifier.padding(top=10.dp,bottom=if(last.second==null)10.dp else 3.dp))
                last.second?.let {BasicText(it,style=TextStyle(color=pal.bad,fontSize=12.sp,lineHeight=19.sp),modifier=Modifier.padding(bottom=10.dp))}
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
        val contentAlpha = remember { Animatable(1f) }
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

    /** Leaving search restores the menu even when the IME is still showing. */
    @Composable
    private fun HomeSearchBackHandler(enabled:Boolean,onBack:()->Unit) {
        val currentOnBack by rememberUpdatedState(onBack)
        BackHandler(enabled=enabled) {currentOnBack()}
        if(android.os.Build.VERSION.SDK_INT>=33) DisposableEffect(enabled) {
            val dispatcher=window.onBackInvokedDispatcher
            val callback=android.window.OnBackInvokedCallback {currentOnBack()}
            var registered=false
            fun update() {
                val active=enabled && lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED)
                if(active && !registered) {
                    dispatcher.registerOnBackInvokedCallback(android.window.OnBackInvokedDispatcher.PRIORITY_OVERLAY,callback)
                    registered=true
                } else if(!active && registered) {
                    dispatcher.unregisterOnBackInvokedCallback(callback);registered=false
                }
            }
            val observer=androidx.lifecycle.LifecycleEventObserver {_,_->update()}
            lifecycle.addObserver(observer);update()
            onDispose {
                lifecycle.removeObserver(observer)
                if(registered)dispatcher.unregisterOnBackInvokedCallback(callback)
            }
        }
    }

    @Composable
    private fun MainPage(pal:Pal,tick:Int,expandedGroup:Page?,onExpand:(Page?)->Unit,query:String,onQueryChange:(String)->Unit,go:(Page)->Unit) {
        val version=remember {UpdateChecker.currentVersion(this)}
        Row(Modifier.fillMaxWidth().padding(vertical=6.dp),verticalAlignment=Alignment.CenterVertically) {
            Image(painterResource(R.drawable.ic_launcher_fg),null,Modifier.size(48.dp).clip(RoundedCornerShape(14.dp)).background(Color(0xFF141416)))
            Column(Modifier.weight(1f).padding(start=12.dp)) {
                BasicText("空叶键盘",style=TextStyle(color=pal.ink,fontSize=24.sp,fontWeight=FontWeight.Medium))
                BasicText("v$version",style=TextStyle(color=pal.text2,fontSize=13.sp),modifier=Modifier.padding(top=4.dp))
            }
            Column(Modifier.clip(RoundedCornerShape(12.dp)).clickable {go(Page.STATS)}.padding(6.dp),horizontalAlignment=Alignment.End) {
                BasicText("${TypingStats.of(this@SettingsActivity).today()}",style=TextStyle(color=pal.accent,fontSize=23.sp,fontWeight=FontWeight.Medium))
                BasicText("今日输入",style=TextStyle(color=pal.text2,fontSize=12.sp))
            }
        }
        val searchTextStyle=TextStyle(color=pal.ink,fontSize=15.sp,lineHeight=22.sp,
            platformStyle=androidx.compose.ui.text.PlatformTextStyle(includeFontPadding=false),
            lineHeightStyle=androidx.compose.ui.text.style.LineHeightStyle(
                alignment=androidx.compose.ui.text.style.LineHeightStyle.Alignment.Center,
                trim=androidx.compose.ui.text.style.LineHeightStyle.Trim.None))
        val searchLineHeight=with(LocalDensity.current){22.sp.toDp()}
        Row(Modifier.fillMaxWidth().heightIn(min=48.dp).clip(RoundedCornerShape(16.dp)).background(pal.card).padding(horizontal=16.dp,vertical=9.dp),verticalAlignment=Alignment.CenterVertically) {
            Image(painterResource(R.drawable.ic_search),null,Modifier.size(20.dp),colorFilter=ColorFilter.tint(pal.text2))
            Spacer(Modifier.width(10.dp))
            BasicTextField(value=query,onValueChange=onQueryChange,singleLine=true,
                textStyle=searchTextStyle,cursorBrush=SolidColor(pal.accent),modifier=Modifier.weight(1f).height(searchLineHeight),
                decorationBox={inner->Box(Modifier.fillMaxSize(),contentAlignment=Alignment.CenterStart) {
                    if(query.isEmpty())BasicText("搜索设置与功能",style=searchTextStyle.copy(color=pal.text2),maxLines=1)
                    inner()
                }})
            Box(Modifier.padding(start = 8.dp).width(32.dp).height(24.dp), contentAlignment = Alignment.Center) {
                if (query.isNotEmpty()) {
                    Box(Modifier.fillMaxSize().clip(CircleShape)
                        .clickable(role = androidx.compose.ui.semantics.Role.Button) { onQueryChange("") }
                        .semantics { contentDescription = "清空搜索" }, contentAlignment = Alignment.Center) {
                        Image(painterResource(R.drawable.ic_close), null, Modifier.size(18.dp), colorFilter = ColorFilter.tint(pal.text2))
                    }
                }
            }
        }
        val searchQuery=query.trim()
        val groups=listOf(
            Page.READY to "输入法状态与功能权限",Page.INPUT to "拼音、英文、手势、滑行、语音与手写",
            Page.APPEARANCE to "主题、布局、尺寸与候选显示",Page.TOOLS to "密码、剪贴板、翻译、统计与备份",Page.ABOUT to "版本检查与更新记录")
        if(searchQuery.isEmpty()) {
            groups.forEach {group->
                val icon=when(group.first) {
                    Page.READY->R.drawable.ic_settings
                    Page.INPUT->R.drawable.ic_kbd
                    Page.APPEARANCE->R.drawable.ic_palette
                    Page.TOOLS->R.drawable.ic_grid
                    else->R.drawable.ic_leaf
                }
                Row(Modifier.fillMaxWidth().clickable {onExpand(group.first)}.heightIn(min=48.dp).padding(start=4.dp,top=10.dp,bottom=4.dp),verticalAlignment=Alignment.CenterVertically) {
                    Image(painterResource(icon),null,Modifier.size(19.dp),colorFilter=ColorFilter.tint(pal.ink))
                    BasicText(group.first.title,style=TextStyle(color=pal.ink,fontSize=14.sp,fontWeight=FontWeight.Medium),modifier=Modifier.padding(start=10.dp))
                }
                androidx.compose.runtime.CompositionLocalProvider(LocalPlainMenu provides true) {
                    if(group.first==Page.READY) {
                        Card(pal) {StatusActionRow(pal,"启用输入法","在系统设置中启用空叶键盘",R.drawable.ic_settings,isEnabled()) {startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))}}
                        Card(pal) {StatusActionRow(pal,"选择输入法","将空叶键盘设为当前输入法",R.drawable.ic_kbd,isCurrent()) {getSystemService(InputMethodManager::class.java).showInputMethodPicker()}}
                        Card(pal) {ActionRow(pal,"功能权限","麦克风、短信与通知","›") {go(Page.PERMISSIONS)}}
                        Card(pal) {DestinationRow(pal,Page.RESOURCES,tick,go)}
                    } else menuChildren(group.first).forEach {target->Card(pal) {DestinationRow(pal,target,tick,go)}}
                }
            }

        } else {
            val catalog = remember { SettingsSearchCatalog(this@SettingsActivity, ::pageSummary) }
            val rows = remember(searchQuery, catalog) { catalog.find(searchQuery) }
            if (rows.isNotEmpty()) Card(pal) { rows.forEachIndexed { n, (target, matches) ->
                if (n > 0) Line(pal)
                val matched = if (target.title.contains(searchQuery, ignoreCase = true)) target.title else matches.first()
                val parent=when(target) {
                    Page.PINYIN,Page.ENGLISH,Page.NUMPAD,Page.ASSOC,Page.KEYS,Page.GLIDE,Page.VOICE,Page.HAND->"输入设置"
                    Page.CORRECTION,Page.FUZZY->"输入设置 → 中文与拼音"
                    Page.FLICKS,Page.DELETE->"输入设置 → 按键与手势"
                    Page.POLISH->"输入设置 → 语音输入"
                    Page.LOOK,Page.LAYOUT,Page.HEIGHT,Page.ARRANGE,Page.DISPLAY->"外观与布局"
                    Page.VAULT,Page.CLIP,Page.SMS,Page.TRANSLATION,Page.STATS,Page.BACKUP->"工具"
                    Page.RESOURCES->"使用准备"
                    Page.HISTORY,Page.UPDATE->"关于"
                    Page.READY,Page.INPUT,Page.APPEARANCE,Page.TOOLS,Page.ABOUT->Page.MAIN.title
                    else->"使用准备"
                }
                val path = if (matched == target.title) parent else "$parent → ${target.title}"
                SearchResultRow(pal, matched, path, searchQuery) { openMenuPage(target, go) }
            }}
            if(rows.isEmpty())BasicText("没有找到相关设置",style=TextStyle(color=pal.text2,fontSize=14.sp))
        }

    }

    private fun openMenuPage(target:Page,go:(Page)->Unit) {
        if(target==Page.UPDATE){openUpdateDialog();return}
        go(target)
        if(target==Page.HISTORY)loadHistory()
    }

    private fun menuChildren(group:Page):List<Page> = when(group) {
        Page.INPUT->listOf(Page.PINYIN,Page.ENGLISH,Page.NUMPAD,Page.GLIDE,Page.VOICE,Page.HAND,Page.ASSOC,Page.KEYS)
        Page.APPEARANCE->listOf(Page.LOOK,Page.LAYOUT,Page.HEIGHT,Page.DISPLAY,Page.ARRANGE)
        Page.TOOLS->listOf(Page.CLIP,Page.SMS,Page.VAULT,Page.TRANSLATION,Page.STATS,Page.BACKUP)
        Page.ABOUT->listOf(Page.UPDATE,Page.HISTORY)
        else->emptyList()
    }

    @Composable
    private fun DestinationRow(pal:Pal,target:Page,tick:Int,go:(Page)->Unit) {
        val p=Prefs.of(this)
        val toggle=when(target) {
            Page.VAULT->Triple(Prefs.VAULT,false,R.drawable.ic_key)
            Page.SMS->Triple(Prefs.SMS_CODE,false,R.drawable.ic_copy)
            Page.STATS->Triple(V3Prefs.STATS_ENABLED,true,R.drawable.ic_stats)
            Page.CORRECTION->Triple(V3Prefs.CORRECTION_ENABLED,true,R.drawable.ic_edit)
            Page.FUZZY->Triple(V3Prefs.FUZZY_ENABLED,Prefs.fuzzySelection(this).isNotEmpty(),R.drawable.ic_kbd)
            Page.FLICKS->Triple(Prefs.SWIPE,true,R.drawable.ic_kbd)
            Page.POLISH->Triple(V3Prefs.VOICE_POLISH,false,R.drawable.ic_edit)
            else->null
        }
        if(target==Page.UPDATE)UpdateEntry(pal,pageSummary(target))
        else if(target in menuChildren(Page.TOOLS) && toggle==null) {
            val icon=when(target){Page.CLIP->R.drawable.ic_clip;Page.TRANSLATION->R.drawable.ic_translate;else->R.drawable.ic_backup}
            NavigationSwitchRow(pal,target.title,pageSummary(target),icon,null,onOpen={openMenuPage(target,go)},onToggle={})
        }
        else if(toggle==null) ActionRow(pal,target.title,pageSummary(target),"›") {openMenuPage(target,go)}
        else {
            val allowed=target!=Page.SMS || checkSelfPermission(Manifest.permission.RECEIVE_SMS)==PackageManager.PERMISSION_GRANTED
            val checked=remember(tick,toggle.first,allowed) {p.getBoolean(toggle.first,toggle.second)&&allowed}
            NavigationSwitchRow(pal,target.title,pageSummary(target),toggle.third,checked,
                onOpen={openMenuPage(target,go)},onToggle={on->
                    if(target==Page.SMS&&on&&!allowed)smsRequest.launch(Manifest.permission.RECEIVE_SMS)
                    else {p.edit().putBoolean(toggle.first,on).apply();this@SettingsActivity.tick.intValue++}
                })
        }
    }

    @Composable
    private fun NavigationSwitchRow(pal:Pal,title:String,sub:String,icon:Int,on:Boolean?,onOpen:()->Unit,onToggle:(Boolean)->Unit) {
        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable(onClick=onOpen),verticalAlignment=Alignment.CenterVertically) {
            Row(Modifier.weight(1f).clip(RoundedCornerShape(12.dp)).padding(vertical=9.dp),verticalAlignment=Alignment.CenterVertically) {
                if(!LocalPlainMenu.current) {
                    Image(painterResource(icon),null,Modifier.size(26.dp),colorFilter=ColorFilter.tint(pal.text2))
                    Spacer(Modifier.width(12.dp))
                }
                Column(Modifier.weight(1f)) {
                    BasicText(title,style=TextStyle(color=pal.ink,fontSize=15.sp,fontWeight=FontWeight.Medium))
                    BasicText(sub,style=TextStyle(color=pal.text2,fontSize=12.sp,lineHeight=17.sp),modifier=Modifier.padding(top=4.dp))
                }
            }
            if(on!=null) Box(Modifier.size(52.dp).semantics {contentDescription="$title 开关"}
                .toggleable(value=on,role=androidx.compose.ui.semantics.Role.Switch,onValueChange=onToggle),contentAlignment=Alignment.Center) {
                Box(Modifier.size(44.dp,27.dp).clip(RoundedCornerShape(50)).background(if(on)pal.accent else pal.track),contentAlignment=if(on)Alignment.CenterEnd else Alignment.CenterStart) {
                    Box(Modifier.padding(3.dp).size(21.dp).clip(CircleShape).background(Color.White))
                }
            }
            Image(painterResource(R.drawable.ic_chevron_right),null,Modifier.padding(start=8.dp).size(19.dp),colorFilter=ColorFilter.tint(pal.text2))
        }
    }

    @Composable
    private fun StatusActionRow(pal:Pal,title:String,sub:String,icon:Int,complete:Boolean?,tag:String?=null,
        statusLabel:String?=null,onClick:()->Unit) {
        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable(onClick=onClick).heightIn(min=48.dp).padding(vertical=9.dp),verticalAlignment=Alignment.CenterVertically) {
            if(!LocalPlainMenu.current)Box(Modifier.size(42.dp).clip(RoundedCornerShape(12.dp)).background(pal.tile),contentAlignment=Alignment.Center) {
                Image(painterResource(icon),null,Modifier.size(23.dp),colorFilter=ColorFilter.tint(pal.text2))
            }
            Column(Modifier.weight(1f).padding(start=if(LocalPlainMenu.current)0.dp else 12.dp,end=12.dp)) {
                Row(verticalAlignment=Alignment.CenterVertically) {
                    BasicText(title,style=TextStyle(color=pal.ink,fontSize=15.sp,fontWeight=FontWeight.Medium))
                    if(tag!=null) {
                        Spacer(Modifier.width(6.dp))
                        BasicText(tag,style=TextStyle(color=pal.text2,fontSize=10.sp),modifier=Modifier.background(pal.tile,RoundedCornerShape(4.dp)).padding(horizontal=5.dp,vertical=2.dp))
                    }
                }
                BasicText(sub,style=TextStyle(color=pal.text2,fontSize=12.sp,lineHeight=17.sp),modifier=Modifier.padding(top=4.dp))
            }
            val (label, color) = when (complete) {
                true -> "已设置" to pal.ok
                false -> "未设置" to pal.bad
                null -> "去设置" to pal.text2
            }
            BasicText(statusLabel ?: label,style=TextStyle(color=color,fontSize=13.sp,fontWeight=FontWeight.Medium))
            if(LocalPlainMenu.current)Image(painterResource(R.drawable.ic_chevron_right),null,Modifier.padding(start=10.dp).size(19.dp),colorFilter=ColorFilter.tint(pal.text2))
        }
    }

    @Composable
    private fun PreparationRows(pal:Pal,go:(Page)->Unit) {
        StatusActionRow(pal,"启用输入法","在系统设置中启用空叶键盘",R.drawable.ic_settings,isEnabled()) {startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))}
        Line(pal)
        StatusActionRow(pal,"选择输入法","将空叶键盘设为当前输入法",R.drawable.ic_kbd,isCurrent()) {getSystemService(InputMethodManager::class.java).showInputMethodPicker()}
        Line(pal);ActionRow(pal,"功能权限","麦克风、短信与通知","›") {go(Page.PERMISSIONS)}
        Line(pal);DestinationRow(pal,Page.RESOURCES,tick.intValue,go)
    }

    @Composable
    private fun HeaderAction(pal:Pal,icon:Int,label:String,onClick:()->Unit) {
        Box(Modifier.size(44.dp).clip(CircleShape).clickable(onClick=onClick).semantics {contentDescription=label},contentAlignment=Alignment.Center) {
            Image(painterResource(icon),null,Modifier.size(23.dp),colorFilter=ColorFilter.tint(pal.ink))
        }
    }

    @Composable
    private fun <T:Any> VisualChoice(
        pal: Pal, title: String, key: String, options: List<Pair<T,String>>, default: T, tick: Int,
        accessibilityTitle: String = title, onChanged: () -> Unit = {}, onSelect: ((T) -> Unit)? = null,
    ) {
        val p = Prefs.of(this)
        val selected = if (onSelect != null) default else remember(tick, key, default) { p.all[key] ?: default }
        val previewLandscape = LocalLayoutPreviewLandscape.current
        val availability=SettingAvailability.forKey(this,key)
        Card(pal, Modifier.noticeAttention(pal, key)) {
            BasicText(title,style=TextStyle(color=pal.ink,fontSize=16.sp,fontWeight=FontWeight.Medium),modifier=Modifier.padding(top=12.dp,bottom=12.dp))
            Row(Modifier.fillMaxWidth().padding(bottom=8.dp).alpha(if(availability.enabled)1f else .4f),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                options.forEach {option->
                    val active=option.first==selected
                    Column(Modifier.weight(1f).clip(RoundedCornerShape(14.dp))
                        .border(if(active)2.dp else 1.dp,if(active)pal.accent else pal.line,RoundedCornerShape(14.dp))
                        .clickable(enabled=availability.enabled) {
                            if (onSelect != null) onSelect(option.first)
                            else {
                                val editor = p.edit()
                                when (val value = option.first) {
                                    is Int -> editor.putInt(key,value)
                                    is String -> editor.putString(key,value)
                                }
                                editor.apply()
                                onChanged()
                            }
                        }.semantics {contentDescription="$accessibilityTitle：${option.second}"},horizontalAlignment=Alignment.CenterHorizontally) {
                        AndroidView(factory={SettingExampleView(it)},update={
                            it.ink=pal.ink.toArgb();it.muted=pal.text2.toArgb();it.tile=pal.tile.toArgb();it.accent=pal.accent.toArgb();it.choiceOnly=true;it.exampleActive=active
                            it.layoutLandscape = previewLandscape
                            it.submit(key,title,option.first,option.first,"","")
                        },modifier=Modifier.fillMaxWidth().padding(5.dp).height(if(key==V3Prefs.READINGS || key==V3Prefs.GLOSS)120.dp else 90.dp))
                        BasicText("${if(active)"●" else "○"} ${option.second}",style=TextStyle(color=if(active)pal.accent else pal.text2,fontSize=12.sp,fontWeight=if(active)FontWeight.Bold else FontWeight.Normal),modifier=Modifier.padding(bottom=10.dp))
                    }
                }
            }
            SettingNotice(pal,key)
        }
    }

    private fun pageSummary(page:Page):String = when(page) {
        Page.PINYIN->"简拼、纠错、中英混输与模糊音"
        Page.ENGLISH->"单词纠错、大小写与自动空格"
        Page.ASSOC->"后续推荐与表情符号"
        Page.KEYS->"震动、按键音与长按操作"
        Page.GLIDE->"中英文滑行、轨迹与回删"
        Page.VOICE->"识别语言、声音处理与听写方式"
        Page.HAND->"手写识别与读音显示"
        Page.LOOK->"皮肤、日夜模式与半透明效果"
        Page.LAYOUT->"布局、单手模式与分体键盘"
        Page.HEIGHT->"高度、宽度、底部留白与分体间距"
        Page.NUMPAD->"符号列与快捷按键"
        Page.ARRANGE->"选择功能并调整顺序"
        Page.DISPLAY->"拼音位置、读音、翻译与纠错标记"
        Page.VAULT->"密码空间、填充与账号推荐"
        Page.CLIP->"历史上限、保留时间与分类"
        Page.SMS->"自动复制新短信中的验证码"
        Page.TRANSLATION->"选择翻译目标语言"
        Page.STATS->"查看与清除输入统计"
        Page.RESOURCES->"下载、继续或删除离线资源"
        Page.BACKUP->"导出或恢复设置、词库与个人数据"
        Page.POLISH->"润色方式、模型与联网配置"
        Page.FLICKS->"字母、逗号和句号的符号与编辑动作"
        Page.CORRECTION->"邻键、顺序、多输与漏输纠错"
        Page.FUZZY->"声母、韵母的近似读音匹配"
        Page.UPDATE->"查看版本、下载进度和安装状态"
        Page.HISTORY->"历史更新内容、搜索与分页"
        else->""
    }

    @Composable
    private fun StatsHeading(pal:Pal,text:String) {BasicText(text,style=TextStyle(color=pal.ink,fontSize=14.sp,fontWeight=FontWeight.Medium),modifier=Modifier.padding(top=8.dp,bottom=2.dp))}
    @Composable
    private fun StatsRow(pal:Pal,name:String,value:String) {
        Row(Modifier.fillMaxWidth().padding(vertical=8.dp),horizontalArrangement=Arrangement.SpaceBetween,verticalAlignment=Alignment.CenterVertically) {
            BasicText(name,style=TextStyle(color=pal.ink,fontSize=16.sp),modifier=Modifier.weight(1f))
            BasicText(value,style=TextStyle(color=pal.ink,fontSize=16.sp,fontWeight=FontWeight.Bold))
        }
    }
    @Composable
    private fun StatisticsPage(pal:Pal,tick:Int) {
        var revision by remember {mutableIntStateOf(0)}
        val stats=remember(tick,revision){TypingStats.of(this).snapshot()}
        var year by rememberSaveable {mutableStateOf(false)}
        var selectedDay by remember(year) {mutableStateOf<String?>(null)}
        var selectedHour by remember {mutableStateOf<Int?>(null)}
        var resetHint by remember {mutableStateOf("")}
        val daily=remember(stats,year) {stats.days(year)}
        fun number(n:Int?)=n?.toString() ?: "—"
        fun code(n:Double?)=n?.let {String.format(java.util.Locale.getDefault(),"%.1f",it)} ?: "—"
        fun rate(n:Int?)=n?.let {"$it%"} ?: "—"
        StatsHeading(pal,"今天")
        Card(pal) {
            Row(Modifier.fillMaxWidth().padding(top=12.dp),verticalAlignment=Alignment.CenterVertically) {
                Row(Modifier.weight(1f),verticalAlignment=Alignment.Bottom) {
                    BasicText(statsNumber(stats.today),style=TextStyle(color=pal.ink,fontSize=if(stats.today>99999)34.sp else 44.sp,fontWeight=FontWeight.Bold))
                    BasicText(" 字",style=TextStyle(color=pal.ink,fontSize=17.sp,fontWeight=FontWeight.Medium),modifier=Modifier.padding(bottom=8.dp))
                }
                BasicText(stats.level.first,style=TextStyle(color=pal.ink,fontSize=13.sp),modifier=Modifier.background(pal.tile,RoundedCornerShape(20.dp)).padding(horizontal=12.dp,vertical=8.dp))
            }
            BasicText(stats.level.second?.let {"距「${it.first}」还差 ${statsNumber(it.second)} 字"} ?: "已解锁最高称号，继续记录每次输入",style=TextStyle(color=pal.text2,fontSize=13.sp),modifier=Modifier.padding(bottom=14.dp))
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(7.dp)) {
                for((value,label) in listOf(number(stats.speed) to "速度 字/分",stats.peakSpeed.takeIf {it>0}?.toString().orEmpty().ifEmpty {"—"} to "最快 字/分",stats.streak.toString() to "连续天数",statsNumber(stats.total) to "累计字数")) {
                    Column(Modifier.weight(1f).background(pal.tile,RoundedCornerShape(12.dp)).padding(vertical=12.dp),horizontalAlignment=Alignment.CenterHorizontally) {
                        BasicText(value,style=TextStyle(color=pal.ink,fontSize=17.sp,fontWeight=FontWeight.Bold),maxLines=1,overflow=androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                        BasicText(label,style=TextStyle(color=pal.text2,fontSize=10.sp),modifier=Modifier.padding(top=4.dp))
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
        }
        StatsHeading(pal,"每日字数")
        Card(pal) {
            Row(horizontalArrangement=Arrangement.spacedBy(8.dp),modifier=Modifier.padding(vertical=12.dp)) {
                Pill(pal,"近30天",!year){year=false};Pill(pal,"全年",year){year=true}
            }
            if(year)AndroidView(factory={StatsYearView(it)},update={view->
                view.days=daily;view.ink=pal.ink.toArgb();view.base=pal.line.toArgb();view.accent=pal.accent.toArgb()
                view.selected=selectedDay;view.onPick={selectedDay=it};view.contentDescription="近一年每日字数热力图，按周排列，点击方格查看日期与字数";view.invalidate()
            },modifier=Modifier.fillMaxWidth().height(66.dp))
            else AndroidView(factory={StatsBarsView(it)},update={view->
                view.values=daily.map {it.second}.toLongArray();view.ink=pal.ink.toArgb();view.base=pal.line.toArgb();view.accent=pal.accent.toArgb()
                view.selected=daily.indexOfFirst {it.first==selectedDay};view.onPick={index->selectedDay=daily[index].first};view.invalidate()
                view.contentDescription="每日字数，点击柱形查看日期与字数"
            },modifier=Modifier.fillMaxWidth().height(155.dp))
            Row(Modifier.fillMaxWidth().padding(top=8.dp),horizontalArrangement=Arrangement.SpaceBetween) {
                BasicText(if(year)"一年前" else daily.first().first.substring(5),style=TextStyle(color=pal.text2,fontSize=12.sp))
                BasicText(if(year)"本周" else "今天",style=TextStyle(color=pal.text2,fontSize=12.sp))
            }
            BasicText(if(year)"近一年活跃 "+daily.count {it.second>0}+" 天" else selectedDay?.let {it+" · "+statsNumber(stats.daily[it] ?: 0)+" 字"} ?: "单日最高 "+statsNumber(daily.maxOf {it.second})+" 字",style=TextStyle(color=pal.text2,fontSize=13.sp),modifier=Modifier.padding(vertical=12.dp))
            if(year&&selectedDay!=null)BasicText(selectedDay+" · "+statsNumber(stats.daily[selectedDay] ?: 0)+" 字",style=TextStyle(color=pal.text2,fontSize=12.sp),modifier=Modifier.padding(bottom=8.dp))
        }
        StatsHeading(pal,"时段分布")
        Card(pal) {
            Spacer(Modifier.height(16.dp))
            AndroidView(factory={StatsBarsView(it)},update={view->view.values=stats.hourly;view.ink=pal.ink.toArgb();view.base=pal.line.toArgb();view.accent=pal.accent.toArgb();view.selected=selectedHour ?: -1;view.onPick={selectedHour=it};view.contentDescription="累计时段分布，点击查看小时字数";view.invalidate()},modifier=Modifier.fillMaxWidth().height(100.dp))
            Row(Modifier.fillMaxWidth().padding(top=8.dp),horizontalArrangement=Arrangement.SpaceBetween) {for(label in listOf("0点","6","12","18","23点"))BasicText(label,style=TextStyle(color=pal.text2,fontSize=12.sp))}
            val hour=selectedHour ?: stats.hourly.indices.maxByOrNull {stats.hourly[it]} ?: 0
            BasicText(if(stats.hourly.all {it==0L}) "暂无时段记录" else if(selectedHour!=null)"${hour}:00—${hour+1}:00 · ${statsNumber(stats.hourly[hour])} 字" else "累计高峰 ${hour}:00—${hour+1}:00",style=TextStyle(color=pal.text2,fontSize=13.sp),modifier=Modifier.padding(vertical=12.dp))
        }
        StatsHeading(pal,"效率")
        Card(pal) {
            StatsRow(pal,"平均码长",code(stats.codeLength));StatsRow(pal,"首选率",rate(stats.firstRate))
            StatsRow(pal,"联想上屏","${statsNumber(stats.association)} 字");StatsRow(pal,"粘贴","${statsNumber(stats.pasted)} 字")
            val groups=linkedMapOf("全拼" to (Color(0xFF8056C8) to 0L),"英文" to (Color(0xFFFFA21A) to 0L),"手写" to (Color(0xFFEC407A) to 0L))
            for(m in stats.methods){val name=when {m.id.startsWith("zh")->"全拼";m.id.startsWith("en")->"英文";m.id.startsWith("hand")->"手写";m.id.startsWith("glide")->"滑行";m.id=="voice"->"语音";else->"旧版"};val color=groups[name]?.first ?: when(name){"滑行"->Color(0xFF159A85);"语音"->Color(0xFF3F81E8);else->pal.text2};if(m.chars>0 || name in groups)groups[name]=color to ((groups[name]?.second ?: 0)+m.chars)}
            val total=groups.values.sumOf {it.second}
            Row(Modifier.fillMaxWidth().padding(top=12.dp).height(10.dp).clip(RoundedCornerShape(8.dp)).background(pal.tile)) {if(total>0)for((_,pair) in groups)if(pair.second>0)Box(Modifier.weight(pair.second.toFloat()).height(10.dp).background(pair.first))}
            for(row in groups.entries.toList().chunked(3))Row(Modifier.fillMaxWidth().padding(top=10.dp),horizontalArrangement=Arrangement.spacedBy(10.dp)) {
                for((name,pair) in row)Row(verticalAlignment=Alignment.CenterVertically) {Box(Modifier.size(8.dp).background(pair.first,RoundedCornerShape(2.dp)));Spacer(Modifier.width(4.dp));BasicText("$name ${if(total>0)(pair.second*100.0/total).roundToInt() else 0}%",style=TextStyle(color=pal.ink,fontSize=12.sp))}
            }
            Spacer(Modifier.height(8.dp))
        }
        StatsHeading(pal,"各输入方式速度")
        Card(pal) {
            val peak=stats.methods.maxOfOrNull {it.chars}?.coerceAtLeast(1) ?: 1
            for((index,m) in stats.methods.withIndex()) {
                if(index>0)Spacer(Modifier.height(10.dp))
                StatsRow(pal,m.title,m.speed?.let {"$it 字/分"} ?: "—")
                val parts=buildList {m.codeLength?.let {add("码长 ${code(it)}")};m.firstRate?.let {add("首选 $it%")};add("使用 ${m.days} 天");add("${statsNumber(m.chars)} 字")}
                BasicText(parts.joinToString(" · "),style=TextStyle(color=pal.text2,fontSize=12.sp))
                Box(Modifier.fillMaxWidth().padding(top=6.dp).height(4.dp).background(pal.line,RoundedCornerShape(3.dp))) {if(m.chars>0)Box(Modifier.fillMaxWidth((m.chars.toFloat()/peak).coerceIn(.005f,1f)).height(4.dp).background(pal.ink,RoundedCornerShape(3.dp)))}
            }
            BasicText("速度按连续输入时间计算，语音和粘贴不计速度。",style=TextStyle(color=pal.text2,fontSize=12.sp),modifier=Modifier.padding(top=16.dp))

        }
        StatsHeading(pal,"纠错")
        Card(pal) {
            StatsRow(pal,"自动纠错","${statsNumber(stats.automatic)} 次")
            StatsRow(pal,"错键 · 漏键 · 多键 · 颠倒",stats.errors.joinToString(" · "))
            StatsRow(pal,"删除键占比",rate(stats.deleteRate))
            AndroidView(factory={StatsHeatView(it)},update={it.values=stats.heat;it.ink=pal.ink.toArgb();it.base=pal.tile.toArgb();it.contentDescription="字母误触热图，颜色越深表示纠错中出现越多";it.invalidate()},modifier=Modifier.fillMaxWidth().height(120.dp).padding(top=8.dp))
            BasicText("常见误触："+stats.pairs.take(3).joinToString("  "){it.first}.ifEmpty {"暂无"},style=TextStyle(color=pal.text2,fontSize=13.sp),modifier=Modifier.padding(vertical=12.dp))
        }
        StatsHeading(pal,"累计")
        Card(pal) {StatsRow(pal,"按键次数",statsNumber(stats.keys));StatsRow(pal,"输入时长",StatsReport.duration(stats.activeMs));StatsRow(pal,"折合800字作文","${statsNumber(stats.total/800)} 篇")}
        BasicText("记录起始 ${stats.since}。旧版未保存的历史指标从新版开始累计；仅保存计数，不保存输入内容。",style=TextStyle(color=pal.text2,fontSize=12.sp,lineHeight=18.sp))
        StatsHeading(pal,"统计管理")
        SettingsToggle(pal,"记录打字统计",V3Prefs.STATS_ENABLED,true,tick,"关闭后停止记录，已有数据保留",showExample=false)
        Card(pal) {
            ActionRow(pal,"生成小票报告","纸张、头图、印章与落款，预览后保存或分享","查看报告") {
                startActivity(Intent(this@SettingsActivity,StatsReportActivity::class.java).putExtra("year",year))
            }
            Line(pal)
            ActionRow(pal,"分享文字","分享当前统计摘要与明细","分享") {
                startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT,StatsReport.text(stats,year)),"分享打字统计"))
            }
        }
        Card(pal) {ActionRow(pal,"清空统计",resetHint.ifEmpty {"删除本机统计记录"},"清空") {if(confirmed("stats-reset")){TypingStats.of(this).clear();Prefs.of(this).edit().putInt(Prefs.COUNT_TODAY,0).apply();revision++;resetHint="已清空"}else resetHint="再点一次确认清空"}}

    }

    @Composable
    private fun PolishConnection(pal:Pal) {
        val p=Prefs.of(this)
        var address by remember {mutableStateOf(p.getString(VoicePolish.ENDPOINT,"").orEmpty())}
        var model by remember {mutableStateOf(p.getString(VoicePolish.MODEL,"").orEmpty())}
        var secret by remember {mutableStateOf("")}
        var message by remember {mutableStateOf(if(PolishSecret.exists(this)) "已保存密钥，留空则保留" else "尚未保存密钥")}
        var sample by remember {mutableStateOf("明天开会这个事情我觉得可以再讨论一下。")}
        var preview by remember {mutableStateOf("")}
        var active by remember {mutableStateOf<VoicePolish.Job?>(null)}
        androidx.compose.runtime.DisposableEffect(Unit) {onDispose {active?.cancel()}}
        StatsHeading(pal,"接口配置")
        Card(pal) {
            Tip(pal,"联网润色接口","兼容 Chat Completions 协议。仅在选择联网润色时，将待润色文字发到你保存的接口；失败保留原文。")
            BasicText("接口地址（HTTPS，例如 https://example.com/v1）",style=TextStyle(color=pal.text2,fontSize=13.sp))
            Field(pal,address){address=it}
            BasicText("模型名称",style=TextStyle(color=pal.text2,fontSize=13.sp));Field(pal,model){model=it}
            BasicText("API 密钥（可选）",style=TextStyle(color=pal.text2,fontSize=13.sp))
            BasicTextField(secret,{secret=it},singleLine=true,visualTransformation=androidx.compose.ui.text.input.PasswordVisualTransformation(),keyboardOptions=androidx.compose.foundation.text.KeyboardOptions(keyboardType=androidx.compose.ui.text.input.KeyboardType.Password),
                textStyle=TextStyle(color=pal.ink,fontSize=16.sp),cursorBrush=SolidColor(pal.accent),
                modifier=Modifier.fillMaxWidth().background(pal.tile,RoundedCornerShape(12.dp)).padding(12.dp))
            BasicText(message,style=TextStyle(color=pal.text2,fontSize=13.sp),modifier=Modifier.padding(vertical=8.dp))
            Row(horizontalArrangement=Arrangement.spacedBy(10.dp)) {
                Pill(pal,"保存配置",true) {
                    runCatching {
                        VoicePolish.endpoint(address);require(model.isNotBlank()) {"请填写模型名称"}
                        if(secret.isNotBlank())PolishSecret.save(this@SettingsActivity,secret.trim())
                        p.edit().putString(VoicePolish.ENDPOINT,address.trim()).putString(VoicePolish.MODEL,model.trim()).apply()
                        secret=""
                    }.onSuccess {message="配置已保存"}.onFailure {message=it.message ?: "保存失败"}
                }
                Pill(pal,"清除密钥",false) {PolishSecret.save(this@SettingsActivity,"");secret="";message="密钥已清除"}
            }
        }
        StatsHeading(pal,"连接测试")
        Card(pal) {
            Tip(pal,"测试润色","使用下面的示例测试已保存的配置，不读取聊天或剪贴板内容。")
            Field(pal,sample){sample=it}
            ActionRow(pal,if(active==null)"测试已保存的接口" else "取消测试",null,if(active==null)"测试" else "取消") {
                if(active!=null){active?.cancel();active=null;preview="已取消，原文未修改"}
                else {preview="正在连接…";active=VoicePolish.request(this@SettingsActivity,sample,{preview=it}) {result->active=null;preview=result.getOrElse {it.message ?: "接口测试失败"}}}
            }
            if(preview.isNotBlank())BasicText(preview,style=TextStyle(color=pal.ink,fontSize=15.sp),modifier=Modifier.padding(vertical=12.dp))
        }
    }

    @Composable
    private fun SettingsToggle(pal:Pal,title:String,key:String,default:Boolean,tick:Int,sub:String?=null,showExample:Boolean=true,onNavigate:((Page)->Unit)?=null) {
        val p=Prefs.of(this)
        val on=remember(tick,key) {p.getBoolean(key,default)}
        val availability=SettingAvailability.forKey(this,key)
        Card(pal, Modifier.noticeAttention(pal, key)) {
            ToggleRow(pal,title,sub,on&&availability.enabled,enabled=availability.enabled) {p.edit().putBoolean(key,it).apply()}
            SettingNotice(pal,key,onNavigate)
            if(showExample&&availability.enabled)SettingComparison(pal,title,key,p.getBoolean(key,default)) {if(it==true) "开启" else "关闭"}
        }
    }

    @Composable
    private fun SettingNotice(pal:Pal,key:String,onNavigate:((Page)->Unit)?=null) {
        val state=remember(tick.intValue,key) {SettingAvailability.forKey(this,key)}
        val message=state.blocked ?: state.warning ?: return
        data class Link(val word:String,val page:Page,val target:String)
        val links=listOf(
            Link("拼音纠错",Page.PINYIN,V3Prefs.CORRECTION_ENABLED),
            Link("键盘布局",Page.LAYOUT,Prefs.LAYOUT),Link("26 键",Page.LAYOUT,Prefs.LAYOUT),Link("14 键",Page.LAYOUT,Prefs.LAYOUT),
            Link("启用上下滑",Page.FLICKS,Prefs.SWIPE),Link("上下滑",Page.FLICKS,Prefs.SWIPE),
            Link("中文滑行",Page.GLIDE,V3Prefs.GLIDE_ZH),Link("英文滑行",Page.GLIDE,V3Prefs.GLIDE_EN),
            Link("滑行",Page.GLIDE,if(V3Prefs.glide(this,false)&&!V3Prefs.glide(this,true))V3Prefs.GLIDE_EN else V3Prefs.GLIDE_ZH),
            Link("联想词",Page.ASSOC,Prefs.ASSOC),Link("按键震动",Page.KEYS,V3Prefs.VIBRATION),
            Link("按键音",Page.KEYS,Prefs.SOUND),Link("横竖屏分别记住布局",Page.LAYOUT,V3Prefs.ORIENTATION_LAYOUT),
            Link("语句润色",Page.VOICE,V3Prefs.VOICE_POLISH),
        ).sortedByDescending {it.word.length}
        val text=AnnotatedString.Builder(message).apply {
            var at=0
            while(at<message.length) {
                val link=links.firstOrNull {message.startsWith(it.word,at)}
                if(link==null){at++;continue}
                addLink(androidx.compose.ui.text.LinkAnnotation.Clickable(link.target,androidx.compose.ui.text.TextLinkStyles(SpanStyle(color=pal.bad,textDecoration=androidx.compose.ui.text.style.TextDecoration.Underline))) {
                    noticeFocus.value=NoticeFocus(link.page, link.target, ++noticeSerial)
                    (noticeNavigation ?: onNavigate)?.invoke(link.page)
                },at,at+link.word.length)
                at+=link.word.length
            }
        }.toAnnotatedString()
        BasicText(text,style=TextStyle(color=pal.bad,fontSize=12.sp,lineHeight=18.sp),modifier=Modifier.padding(top=2.dp,bottom=10.dp))
    }

    @Composable
    private fun UnavailableSetting(pal:Pal,title:String,value:String,key:String) {
        Card(pal) {
            Column(Modifier.fillMaxWidth().padding(vertical=12.dp).alpha(.45f).clickable(enabled=false){}) {
                BasicText(title,style=TextStyle(color=pal.ink,fontSize=16.sp))
                BasicText(value,style=TextStyle(color=pal.text2,fontSize=13.sp),modifier=Modifier.padding(top=4.dp))
            }
            SettingNotice(pal,key)
        }
    }

    @Composable
    private fun <T:Any> SettingsChoice(pal:Pal,title:String,key:String,options:List<Pair<T,String>>,default:T,tick:Int,showExample:Boolean=true,inCard:Boolean=true,inlineDropdown:Boolean=false,onChanged:()->Unit={}) {
        if (showExample && options.size==3 && !inlineDropdown && inCard) {
            VisualChoice(pal,title,key,options,default,tick,onChanged=onChanged)
            return
        }
        val p=Prefs.of(this)
        val selected=remember(tick,key) {p.all[key] ?: default}
        var showOptions by rememberSaveable(key) {mutableStateOf(false)}
        val availability=SettingAvailability.forKey(this,key)
        LaunchedEffect(availability.enabled) {if(!availability.enabled)showOptions=false}
        if(!availability.enabled){UnavailableSetting(pal,title,options.firstOrNull {it.first==selected}?.second ?: options.first().second,key);return}
        fun choose(option:Pair<T,String>) {
            val edit=p.edit()
            when(val value=option.first){is Int->edit.putInt(key,value);is String->edit.putString(key,value)}
            edit.apply();showOptions=false;onChanged()
        }
        ChoiceContainer(pal,inCard) {
            if(options.size<=3) {
                BasicText(title,style=TextStyle(color=pal.ink,fontSize=16.sp,fontWeight=FontWeight.Medium),modifier=Modifier.padding(top=12.dp,bottom=10.dp))
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(pal.tile).padding(3.dp).height(IntrinsicSize.Min).selectableGroup()) {
                    options.forEach {option->
                        val active=option.first==selected
                        Box(Modifier.weight(1f).fillMaxHeight().heightIn(min=44.dp).clip(RoundedCornerShape(9.dp)).background(if(active)pal.card else Color.Transparent)
                            .selectable(active,role=androidx.compose.ui.semantics.Role.RadioButton,onClick={choose(option)}).padding(horizontal=6.dp,vertical=8.dp),contentAlignment=Alignment.Center) {
                            BasicText(option.second,style=TextStyle(color=if(active)pal.ink else pal.text2,fontSize=13.sp,lineHeight=17.sp,fontWeight=if(active)FontWeight.Medium else FontWeight.Normal,textAlign=TextAlign.Center),maxLines=2)
                        }
                    }
                }
                Spacer(Modifier.height(10.dp))
            } else {
                Box(Modifier.fillMaxWidth()) {
                    val interactions = remember { MutableInteractionSource() }
                    val pressed by interactions.collectIsPressedAsState()
                    val expanded = inlineDropdown && showOptions
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
                            .background(pal.card)
                            .clickable(interactionSource = interactions, indication = if (inlineDropdown) null else androidx.compose.foundation.LocalIndication.current) {
                                showOptions = if (inlineDropdown) !showOptions else true
                            }.semantics { if (inlineDropdown) stateDescription = if (expanded) "已展开" else "已收起" }
                            .padding(start = 6.dp, end = 4.dp, top = 9.dp, bottom = 9.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        BasicText(title,style=TextStyle(color=pal.ink,fontSize=16.sp,fontWeight=FontWeight.Medium),modifier=Modifier.weight(1f))
                        BasicText(options.firstOrNull {it.first==selected}?.second ?: options.first().second,style=TextStyle(color=pal.text2,fontSize=13.sp),modifier=Modifier.widthIn(max=160.dp).padding(start=12.dp,end=8.dp),maxLines=1)
                        ExpandArrow(pal, expanded, pressed)
                    }
                    if(inlineDropdown&&showOptions) {
                        val inset=with(LocalDensity.current){8.dp.roundToPx()}
                        val position=remember(inset) {object:androidx.compose.ui.window.PopupPositionProvider {
                            override fun calculatePosition(anchorBounds:androidx.compose.ui.unit.IntRect,windowSize:androidx.compose.ui.unit.IntSize,layoutDirection:androidx.compose.ui.unit.LayoutDirection,popupContentSize:androidx.compose.ui.unit.IntSize):IntOffset {
                                val x=(anchorBounds.right-popupContentSize.width).coerceIn(inset,maxOf(inset,windowSize.width-popupContentSize.width-inset))
                                val below=anchorBounds.bottom
                                val y=if(below+popupContentSize.height<=windowSize.height-inset)below else anchorBounds.top-popupContentSize.height
                                return IntOffset(x,y.coerceIn(inset,maxOf(inset,windowSize.height-popupContentSize.height-inset)))
                            }
                        }}
                        androidx.compose.ui.window.Popup(popupPositionProvider=position,onDismissRequest={showOptions=false},properties=androidx.compose.ui.window.PopupProperties(focusable=true)) {
                            Column(Modifier.width(220.dp).heightIn(max=320.dp).shadow(10.dp,RoundedCornerShape(12.dp)).clip(RoundedCornerShape(12.dp)).background(pal.card)
                                .semantics {contentDescription="$title 下拉菜单"}.verticalScroll(rememberScrollState()).padding(4.dp).selectableGroup()) {
                                options.forEach {option->
                                    val active=option.first==selected
                                    Row(Modifier.fillMaxWidth().heightIn(min=44.dp).clip(RoundedCornerShape(8.dp))
                                        .selectable(active,role=androidx.compose.ui.semantics.Role.RadioButton,onClick={choose(option)}).padding(horizontal=12.dp,vertical=10.dp),verticalAlignment=Alignment.CenterVertically) {
                                        BasicText(option.second,style=TextStyle(color=if(active)pal.accent else pal.ink,fontSize=14.sp),modifier=Modifier.weight(1f))
                                        if(active)Image(painterResource(R.drawable.ic_done),null,Modifier.size(18.dp),colorFilter=ColorFilter.tint(pal.accent)) else Spacer(Modifier.size(18.dp))
                                    }
                                }
                            }
                        }
                    }
                }
            }
            SettingNotice(pal,key)
            if(showExample)SettingComparison(pal,title,key,selected,if(options.size==2 || key in listOf(V3Prefs.LETTER_LONG,Prefs.SPACE_LONG,V3Prefs.READINGS,V3Prefs.GLOSS,V3Prefs.CLEAR_SCOPE))options.map {it.first as Any to it.second} else emptyList()) {v->options.firstOrNull {it.first==v}?.second ?: v.toString()}
        }
        if(showOptions&&!inlineDropdown)androidx.compose.ui.window.Dialog(onDismissRequest={showOptions=false},properties=androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth=false)) {
            Column(Modifier.widthIn(max=420.dp).fillMaxWidth(.9f).heightIn(max=520.dp).clip(RoundedCornerShape(14.dp)).background(pal.card).padding(20.dp)) {
                BasicText(title,style=TextStyle(color=pal.ink,fontSize=19.sp,fontWeight=FontWeight.Bold),modifier=Modifier.padding(bottom=12.dp))
                Column(Modifier.weight(1f,fill=false).verticalScroll(rememberScrollState()).selectableGroup(),verticalArrangement=Arrangement.spacedBy(4.dp)) {
                    options.forEach {option->
                        val active=option.first==selected
                        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(if(active)pal.accent.copy(alpha=.06f) else Color.Transparent)
                            .selectable(active,role=androidx.compose.ui.semantics.Role.RadioButton,onClick={choose(option)}).padding(horizontal=12.dp,vertical=13.dp),verticalAlignment=Alignment.CenterVertically) {
                            BasicText(option.second,style=TextStyle(color=pal.ink,fontSize=15.sp,fontWeight=if(active)FontWeight.Medium else FontWeight.Normal),modifier=Modifier.weight(1f))
                            Box(Modifier.size(20.dp).border(1.5.dp,if(active)pal.accent else pal.track,CircleShape),contentAlignment=Alignment.Center) {
                                if(active)Box(Modifier.size(10.dp).background(pal.accent,CircleShape))
                            }
                        }
                    }
                }
                Row(Modifier.fillMaxWidth().padding(top=10.dp),horizontalArrangement=Arrangement.End) {
                    BasicText("取消",style=TextStyle(color=pal.text2,fontSize=14.sp),modifier=Modifier.clip(RoundedCornerShape(8.dp)).clickable {showOptions=false}.padding(horizontal=12.dp,vertical=10.dp))
                }
            }
        }
    }

    @Composable
    private fun ChoiceContainer(pal:Pal,inCard:Boolean,content:@Composable ()->Unit) {
        if(inCard)Card(pal, content = content) else content()
    }

    @Composable
    private fun VibrationMode(pal:Pal,tick:Int) {
        val p=Prefs.of(this);val selected=remember(tick) {p.getInt(V3Prefs.VIBRATION,0)}
        Card(pal, Modifier.noticeAttention(pal, V3Prefs.VIBRATION)) {
            BasicText("按键震动",style=TextStyle(color=pal.ink,fontSize=16.sp,fontWeight=FontWeight.Medium),modifier=Modifier.padding(top=12.dp))
            BasicText("跟随系统：强弱跟随手机的触感设置；想自行调整强弱，选择「启用」。",style=TextStyle(color=pal.text2,fontSize=13.sp,lineHeight=20.sp),modifier=Modifier.padding(top=6.dp,bottom=12.dp))
            Row(Modifier.fillMaxWidth().selectableGroup(),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                listOf("跟随系统","启用","关闭").forEachIndexed {mode,label->
                    val active=mode==selected
                    Column(Modifier.weight(1f).clip(RoundedCornerShape(12.dp)).selectable(active,role=androidx.compose.ui.semantics.Role.RadioButton) {
                            val edit = p.edit().putInt(V3Prefs.VIBRATION, mode)
                            val previous = p.getInt(V3Prefs.VIBRATION, 0)
                            if (mode == 2 && previous != 2) edit.putInt(V3Prefs.VIBRATION_RESUME_MODE, previous)
                            edit.apply()
                        },horizontalAlignment=Alignment.CenterHorizontally) {
                        AndroidView(factory={SettingExampleView(it).apply {choiceOnly=true}},update={
                            it.ink=pal.ink.toArgb();it.muted=pal.text2.toArgb();it.tile=pal.tile.toArgb();it.accent=pal.accent.toArgb()
                            it.submit(V3Prefs.VIBRATION,"按键震动",mode,mode,label,label)
                        },modifier=Modifier.fillMaxWidth().height(80.dp).clip(RoundedCornerShape(12.dp)).background(pal.tile).border(if(active)2.dp else 1.dp,if(active)pal.ink else pal.line,RoundedCornerShape(12.dp)))
                        BasicText(label,style=TextStyle(color=if(active)pal.ink else pal.text2,fontSize=13.sp,fontWeight=if(active)FontWeight.Bold else FontWeight.Normal),modifier=Modifier.padding(top=8.dp,bottom=12.dp))
                    }
                }
            }
        }
    }

    @Composable
    private fun VibrationStrength(pal: Pal, tick: Int) {
        val prefs = Prefs.of(this)
        val labels = listOf("无", "跟随系统", "弱", "较弱", "适中", "较强", "强")
        val mode = remember(tick) { prefs.getInt(V3Prefs.VIBRATION, 0) }
        val manualLevel = remember(tick) {
            val fallback = when (prefs.getInt(Prefs.VIB_STRENGTH, 2)) { 1 -> 2; 3 -> 6; else -> 4 }
            prefs.getInt(V3Prefs.VIBRATION_LEVEL, fallback).coerceIn(2, 6)
        }
        var value by remember(mode, manualLevel) {
            mutableFloatStateOf(when (mode) { 0 -> 1f; 2 -> 0f; else -> manualLevel.toFloat() })
        }
        Card(pal) {
            ToggleRow(pal, "震动强度", null, mode != 2) { enabled ->
                val edit = prefs.edit()
                if (!enabled) edit.putInt(V3Prefs.VIBRATION_RESUME_MODE, mode)
                edit.putInt(V3Prefs.VIBRATION, if (enabled) prefs.getInt(V3Prefs.VIBRATION_RESUME_MODE, 0) else 2).apply()
            }
            Slider(pal, value, 0f, 6f, { value = it }, labels = labels) { committed ->
                val step = committed.roundToInt().coerceIn(0, 6)
                val nextMode = when (step) { 0 -> 2; 1 -> 0; else -> 1 }
                val edit = prefs.edit().putInt(V3Prefs.VIBRATION, nextMode)
                if (step == 0 && mode != 2) edit.putInt(V3Prefs.VIBRATION_RESUME_MODE, mode)
                if (step >= 2) {
                    edit.putInt(V3Prefs.VIBRATION_LEVEL, step)
                    edit.putInt(Prefs.VIB_STRENGTH, when { step <= 3 -> 1; step <= 5 -> 2; else -> 3 })
                }
                edit.apply()
            }
        }
    }

    @Composable
    private fun KeySound(pal: Pal, tick: Int) {
        val prefs = Prefs.of(this)
        val enabled = remember(tick) { prefs.getBoolean(Prefs.SOUND, false) }
        var volume by remember(tick) { mutableFloatStateOf(prefs.getInt(V3Prefs.SOUND_VOLUME, 50).toFloat()) }
        val audio = remember { getSystemService(AudioManager::class.java) }
        var ringerMode by remember(audio) { mutableIntStateOf(audio?.ringerMode ?: AudioManager.RINGER_MODE_NORMAL) }
        DisposableEffect(audio) {
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    ringerMode = audio?.ringerMode ?: AudioManager.RINGER_MODE_NORMAL
                }
            }
            ContextCompat.registerReceiver(this@SettingsActivity, receiver,
                IntentFilter(AudioManager.RINGER_MODE_CHANGED_ACTION), ContextCompat.RECEIVER_EXPORTED)
            onDispose { unregisterReceiver(receiver) }
        }
        val silentMode = when (ringerMode) {
            AudioManager.RINGER_MODE_SILENT -> "静音"
            AudioManager.RINGER_MODE_VIBRATE -> "仅振动"
            else -> null
        }
        Card(pal, Modifier.noticeAttention(pal, Prefs.SOUND)) {
            ToggleRow(pal, "按键音", null, enabled) { prefs.edit().putBoolean(Prefs.SOUND, it).apply() }
            Slider(pal, volume, 0f, 100f, { volume = it }, format = { "${it.roundToInt()}%" }, enabled = enabled && silentMode == null) {
                prefs.edit().putInt(V3Prefs.SOUND_VOLUME, it.roundToInt()).apply()
            }
            if (silentMode == null) {
                SettingNotice(pal, V3Prefs.SOUND_VOLUME)
            } else {
                BasicText("手机处于${silentMode}模式。切换到响铃模式后才能听到按键音。",
                    modifier = Modifier.padding(bottom = 12.dp),
                    style = TextStyle(color = pal.bad, fontSize = 12.sp, lineHeight = 18.sp))
            }
        }
    }

    @Composable
    private fun V3Page(pal:Pal,page:Page,tick:Int,go:(Page)->Unit) {
        val p=Prefs.of(this)
        val pages=if(page in listOf(Page.INPUT,Page.APPEARANCE,Page.TOOLS))menuChildren(page) else emptyList()
        if(pages.isNotEmpty()) Card(pal) {pages.forEachIndexed {index,destination ->
            if(index>0)Line(pal)
            DestinationRow(pal,destination,tick,go)
        }}
        when(page) {
            Page.RESOURCES -> ResourcesPage(pal)
            Page.BACKUP -> BackupPage(pal)
            Page.READY -> Card(pal) {PreparationRows(pal,go)}
            Page.PERMISSIONS -> Card(pal) {
                fun appPermissions() {startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,Uri.parse("package:$packageName")))}
                StatusActionRow(pal,"麦克风","语音输入使用",R.drawable.ic_mic,micGranted()) {if(micGranted())appPermissions() else micRequest.launch(Manifest.permission.RECORD_AUDIO)}
                Line(pal)
                val sms=checkSelfPermission(Manifest.permission.RECEIVE_SMS)==PackageManager.PERMISSION_GRANTED
                StatusActionRow(pal,"接收短信","复制新短信中的验证码",R.drawable.ic_copy,sms) {if(sms)appPermissions() else smsRequest.launch(Manifest.permission.RECEIVE_SMS)}
                Line(pal);StatusActionRow(pal,"通知","语音输入进行时显示状态和停止按钮",R.drawable.ic_settings,notificationsGranted(),tag="可选") {requestNotifications()}
            }
            Page.PINYIN -> {
                StatsHeading(pal,"拼音输入")
                SettingsToggle(pal,"简拼",Prefs.ABBR,true,tick,"例如 zg → 中国")
                SettingsToggle(pal,"中英混输",V3Prefs.MIXED,true,tick,"中文与英文连续输入，无需切换键盘")
                Card(pal, Modifier.noticeAttention(pal, V3Prefs.CORRECTION_ENABLED)) { DestinationRow(pal, Page.CORRECTION, tick, go) }
                Card(pal) {DestinationRow(pal,Page.FUZZY,tick,go)}

                StatsHeading(pal,"选词与符号")
                SettingsToggle(pal,"标点确认首选",V3Prefs.PUNCT_CONFIRM,true,tick,"按标点时，先选中候选栏的第一个词")
                SettingsToggle(pal,"成对符号",V3Prefs.PAIRS,true,tick,"输入左括号时补齐右括号，光标停在中间")

                StatsHeading(pal,"词库与学习")
                Card(pal) {ActionRow(pal,"整句增强词库",if(ResourcePacks.ready(this@SettingsActivity,"grammar")) "已下载；基础词库已可用" else "基础拼音已可用，可下载整句增强词库","管理") {go(Page.RESOURCES)}}
                SettingsToggle(pal,"记住选词",Prefs.LEARN,true,tick,"记住主动选择的非首选词；最多保留最近 4096 条记录",showExample=false)
                var forgetHint by remember {mutableStateOf("")}
                Card(pal) {ActionRow(pal,"清除选词记录",forgetHint.ifEmpty {"清除本机学习的选词与自造词"},"清除") {
                    if(confirmed("forget")) {
                        forgetHint="正在清除…"
                        Thread {
                            val result=runCatching {Lexicon.clearUser(this@SettingsActivity);PinyinEngine.clearUser(this@SettingsActivity)}
                            runOnUiThread {forgetHint=if(result.isSuccess) "已清除" else "清除失败，请重试"}
                        }.start()
                    } else forgetHint="再点一次清除确认"
                }}
            }
            Page.FUZZY -> {
                val active=Prefs.fuzzySelection(this)
                val fuzzyOn=remember(tick){Prefs.fuzzyEnabled(this)}
                Card(pal) {Tip(pal,"近似读音匹配",if(!fuzzyOn)"模糊音已关闭，规则已保留。可返回「中文与拼音」，在「模糊音」入口开启。" else if(active.isEmpty())"当前严格区分各组拼音；可按自己的发音习惯开启。" else active.joinToString(" · ") {it.replace("-"," ↔ ")})}
                listOf("声母" to Prefs.FUZZY_RULES.take(6),"韵母" to Prefs.FUZZY_RULES.drop(6)).forEach {group->
                    StatsHeading(pal,group.first)
                    Card(pal) {group.second.forEachIndexed {n,rule->if(n>0)Line(pal)
                        ToggleRow(pal,rule.second.replace(" = "," / "),null,rule.first in active,enabled=fuzzyOn) {on->
                            p.edit().putString(Prefs.FUZZY,(if(on)active+rule.first else active-rule.first).joinToString(",")).apply()
                        }
                    }}
                }
            }
            Page.CORRECTION -> {
                SettingsToggle(pal,"邻键纠错",V3Prefs.CORRECT_NEAR,true,tick,"处理误触到相邻按键")
                SettingsToggle(pal,"顺序纠错",V3Prefs.CORRECT_SWAP,true,tick,"处理相邻字母顺序颠倒")
                SettingsToggle(pal,"多输纠错",V3Prefs.CORRECT_EXTRA,true,tick,"处理多输入的字母")
                SettingsToggle(pal,"漏输纠错",V3Prefs.CORRECT_MISSING,true,tick,"处理遗漏的字母")
            }
            Page.ENGLISH -> {
                StatsHeading(pal,"单词输入")
                SettingsToggle(pal,"英文单词纠错",V3Prefs.EN_CORRECT,true,tick)
                SettingsToggle(pal,"邮箱后缀推荐",V3Prefs.EMAIL,true,tick)
                StatsHeading(pal,"大小写")
                SettingsToggle(pal,"首字母自动大写",V3Prefs.CAPS,true,tick)
                SettingsToggle(pal,"大写锁定",V3Prefs.CAPS_LOCK,false,tick,"开启后点击大写键持续大写，再点解除；关闭时只大写一个字母")
                StatsHeading(pal,"英文自动空格")
                SettingsToggle(pal,"选词后自动加空格",V3Prefs.EN_SPACE,true,tick)
                SettingsToggle(pal,"英文标点后自动加空格",V3Prefs.PUNCT_SPACE,false,tick)
                StatsHeading(pal,"混合输入空格")
                SettingsToggle(pal,"中英文之间自动加空格",V3Prefs.SPACE_LATIN,false,tick)
                SettingsToggle(pal,"中文与数字之间自动加空格",V3Prefs.SPACE_DIGIT,false,tick)
            }
            Page.ASSOC -> {
                StatsHeading(pal,"英文候选")
                SettingsToggle(pal,"识别英文单词",V3Prefs.RECOGNIZE_ENGLISH,true,tick,"中文输入时识别 GitHub、Claude、Anthropic 等英文词")
                StatsHeading(pal,"词语联想")
                SettingsToggle(pal,"联想词",Prefs.ASSOC,true,tick)
                SettingsChoice(pal,"联想方式",V3Prefs.ASSOC_CONTINUOUS,listOf(0 to "单次联想",1 to "连续联想"),1,tick)
                SettingsToggle(pal,"空格确认联想",V3Prefs.ASSOC_SPACE,false,tick)
                StatsHeading(pal,"修改后的推荐")
                SettingsToggle(pal,"删除后推荐同音字",V3Prefs.HOMOPHONE,false,tick)
                StatsHeading(pal,"表情与符号推荐")
                SettingsToggle(pal,"推荐 Emoji",V3Prefs.EMOJI,true,tick)
                SettingsToggle(pal,"推荐颜文字",V3Prefs.KAOMOJI,true,tick)
                SettingsToggle(pal,"推荐符号",V3Prefs.SYMBOLS,true,tick)
            }
            Page.KEYS -> {
                StatsHeading(pal,"触控识别")
                Card(pal) {
                    NavigationSwitchRow(pal,"触控校准","按提示点击，调整边缘识别偏移",R.drawable.ic_adjust,null,
                        onOpen={startActivity(Intent(this@SettingsActivity,TouchCalibrationActivity::class.java))},onToggle={})
                }
                StatsHeading(pal,"按键反馈")
                VibrationMode(pal,tick)
                VibrationStrength(pal,tick)
                KeySound(pal, tick)

                StatsHeading(pal,"字母键")
                SettingsChoice(pal,"长按字母",V3Prefs.LETTER_LONG,listOf(0 to "大小写与符号",1 to "输入大写",2 to "关闭"),0,tick)

                StatsHeading(pal,"空格与光标")
                SettingsChoice(pal,"空格长按",Prefs.SPACE_LONG,listOf(0 to "移动光标",1 to "连续空格",2 to "开关语音"),0,tick)
                SettingsToggle(pal,"底部滑动移光标",V3Prefs.BOTTOM_CURSOR,false,tick)

                StatsHeading(pal,"滑动与删除")
                Card(pal) {
                    DestinationRow(pal,Page.FLICKS,tick,go)
                    Line(pal)
                    NavigationSwitchRow(pal,"删除键","滑动动作、清空范围与联想删除",R.drawable.ic_del,null,onOpen={go(Page.DELETE)},onToggle={})
                }
            }
            Page.DELETE -> {
                StatsHeading(pal,"滑动与清空")
                val actions=listOf("none" to "不执行动作","clear" to "清空","undo" to "撤回","redo" to "重做","delete" to "删除","left" to "左移光标","right" to "右移光标")
                Card(pal) {
                    Row(Modifier.fillMaxWidth().padding(top = 6.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        BasicText("滑动动作", style = TextStyle(color = pal.ink, fontSize = 16.sp, fontWeight = FontWeight.Medium), modifier = Modifier.weight(1f))
                        HeaderAction(pal, R.drawable.ic_refresh, "重置") {
                            val edit = p.edit()
                            listOf(V3Prefs.DEL_UP, V3Prefs.DEL_DOWN, V3Prefs.DEL_LEFT, V3Prefs.DEL_RIGHT).forEach { edit.remove(it) }
                            edit.apply()
                        }
                    }
                    SettingsChoice(pal,"上滑",V3Prefs.DEL_UP,actions,"undo",tick,showExample=false,inCard=false,inlineDropdown=true)
                    Line(pal)
                    SettingsChoice(pal,"下滑",V3Prefs.DEL_DOWN,actions,"clear",tick,showExample=false,inCard=false,inlineDropdown=true)
                    Line(pal)
                    SettingsChoice(pal,"左滑",V3Prefs.DEL_LEFT,actions,"none",tick,showExample=false,inCard=false,inlineDropdown=true)
                    Line(pal)
                    SettingsChoice(pal,"右滑",V3Prefs.DEL_RIGHT,actions,"none",tick,showExample=false,inCard=false,inlineDropdown=true)
                }
                SettingsChoice(pal,"清空范围",V3Prefs.CLEAR_SCOPE,listOf(0 to "整个输入框",1 to "仅光标前",2 to "仅当前段落"),0,tick)
                StatsHeading(pal,"组字期间")
                SettingsToggle(pal,"退格还原原始拼音",V3Prefs.COMPOSE_RESTORE,true,tick,"已选词未上屏时，开启后退格还原拼音；关闭后从末尾逐字母删除")
                SettingsToggle(pal,"长按清空整段拼音",V3Prefs.COMPOSE_HOLD_CLEAR,true,tick,"长按删除键清空当前整段拼音；关闭时连续退格")
                StatsHeading(pal,"联想期间")
                SettingsToggle(pal,"联想时直接删除",V3Prefs.DELETE_DIRECT,true,tick,"关闭后，第一次退格或清空只收起联想词")
            }
            Page.GLIDE -> {
                StatsHeading(pal,"启用语言")
                SettingsToggle(pal,"中文滑行",V3Prefs.GLIDE_ZH,p.getBoolean(Prefs.GLIDE,false),tick,onNavigate=go)
                SettingsToggle(pal,"英文滑行",V3Prefs.GLIDE_EN,p.getBoolean(Prefs.GLIDE,false),tick,onNavigate=go)
                StatsHeading(pal,"滑行反馈")
                SettingsToggle(pal,"显示滑行轨迹",V3Prefs.GLIDE_TRACE,true,tick)
                SettingsToggle(pal,"实时显示候选",V3Prefs.GLIDE_LIVE,true,tick)
                StatsHeading(pal,"中文回删")
                SettingsToggle(pal,"按字回删中文滑行",V3Prefs.GLIDE_WORD_DELETE,false,tick,"每次删除最后一个汉字对应的编码；关闭后每次删一个字母")
            }
            Page.VOICE -> {
                StatsHeading(pal,"模型与语言")
                Card(pal) {ActionRow(pal,"听写与标点模型","普通话、四川话及语音标点，下载后离线使用","管理") {go(Page.RESOURCES)}}
                SettingsChoice(pal,"识别语言",Prefs.VOICE_LANG,listOf(0 to "普通话",1 to "四川话"),0,tick,showExample=false)
                StatsHeading(pal,"播放与连续听写")
                SettingsChoice(pal,"外放音处理",V3Prefs.MEDIA,listOf(0 to "不处理",1 to "暂停播放",2 to "压低音量"),0,tick,showExample=false)
                SettingsToggle(pal,"收起后连续听写",V3Prefs.DRIVE,false,tick,"收起为听写条，发送后返回输入框自动继续聆听",showExample=false)
                StatsHeading(pal,"转写整理")
                SettingsToggle(pal,"自动标点",V3Prefs.VOICE_PUNCT,true,tick)
                SettingsToggle(pal,"阿拉伯数字",V3Prefs.VOICE_NUMBERS,false,tick)
                SettingsToggle(pal,"去除口头语",V3Prefs.VOICE_CLEAN,false,tick,"清理嗯、啊和重复表达")
                Card(pal, Modifier.noticeAttention(pal, V3Prefs.VOICE_POLISH)) { DestinationRow(pal, Page.POLISH, tick, go) }
            }
            Page.POLISH -> {
                StatsHeading(pal,"润色设置")
                SettingsToggle(pal,"语句润色",V3Prefs.VOICE_POLISH,false,tick,"听写结束后整理已输入的文字，可恢复原文")
                SettingsChoice(pal,"润色方式",VoicePolish.MODE,listOf(0 to "离线模型",1 to "联网接口"),0,tick,showExample=false)
                if(p.getInt(VoicePolish.MODE,0)==0) {
                    StatsHeading(pal,"离线模型")
                    Card(pal) {ActionRow(pal,"离线润色模型",if(ResourcePacks.ready(this@SettingsActivity,"polish")) "已下载，处理结束释放模型内存" else "Qwen3 0.6B，约 640 MB，按需下载","管理") {go(Page.RESOURCES)}}
                } else PolishConnection(pal)
            }
            Page.HAND -> {
                SettingsToggle(pal,"显示带声调读音",V3Prefs.HAND_TONE,true,tick,"显示单字读音，多音字显示多种读音")
                Card(pal) {ActionRow(pal,"笔迹识别模型",if(ResourcePacks.bundled(this@SettingsActivity,"handwriting")) "已内置，离线可用，无需另外下载" else if(ResourcePacks.ready(this@SettingsActivity,"handwriting")) "已下载，离线可用" else "下载后使用神经网络识别；未下载时使用基础备用识别","管理") {go(Page.RESOURCES)}}
            }
            Page.DISPLAY -> {
                StatsHeading(pal,"输入中的拼音")
                VisualChoice(pal,"输入拼音位置",V3Prefs.PINYIN_POSITION,listOf(0 to "键盘上方气泡",1 to "候选栏内",2 to "应用输入框内"),0,tick)
                SettingsToggle(pal,"显示纠错标记",V3Prefs.FEEDBACK,true,tick)
                StatsHeading(pal,"候选词辅助信息")
                SettingsChoice(pal,"候选读音",V3Prefs.READINGS,listOf(0 to "自动",1 to "显示",2 to "隐藏"),if(p.getBoolean(Prefs.SHOW_PINYIN,false)) 1 else 2,tick)
                SettingsChoice(pal,"候选翻译",V3Prefs.GLOSS,listOf(0 to "自动",1 to "显示",2 to "隐藏"),2,tick) {CandidateGloss.prepare(this)}
                if(p.getInt(V3Prefs.GLOSS,2)!=2)Card(pal) {ActionRow(pal,"翻译语言包","候选翻译需要中文翻译语言包，可在离线资源中下载或删除。","管理") {go(Page.RESOURCES)}}
            }
            Page.SMS -> {
              val guide = remember { SmsSetupGuide.current() }
              val xiaomi = guide == SmsSetupGuide.XIAOMI
              val allowed = checkSelfPermission(Manifest.permission.RECEIVE_SMS) == PackageManager.PERMISSION_GRANTED
              val clipboard = SmsSetupPermission.CLIPBOARD
              val sms = SmsSetupPermission.NOTIFICATION_SMS
              val clipboardStatus = remember(tick, guide) { clipboard.systemStatus(this, guide) }
              var clipboardConfirmed by remember { mutableStateOf(clipboard.isConfirmed(this)) }
              var smsConfirmed by remember { mutableStateOf(sms.isConfirmed(this)) }
              var confirmPermission by rememberSaveable { mutableStateOf<SmsSetupPermission?>(null) }
              fun openAppDetails() {
                  val details = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))
                  runCatching { startActivity(details) }.onFailure { startActivity(Intent(Settings.ACTION_SETTINGS)) }
              }
              StatsHeading(pal,"自动复制")
              Card(pal) {
                ToggleRow(pal,"短信验证码自动复制","仅处理新短信，10 分钟后清除，不进入剪贴板历史",allowed&&p.getBoolean(Prefs.SMS_CODE,false)) {
                    if(it&&!allowed)smsRequest.launch(Manifest.permission.RECEIVE_SMS) else p.edit().putBoolean(Prefs.SMS_CODE,it).apply()
                }
                SettingComparison(pal,"短信验证码",Prefs.SMS_CODE,allowed&&p.getBoolean(Prefs.SMS_CODE,false)) {if(it==true)"开启" else "关闭"}
            }
              StatsHeading(pal,"权限与设置")
              Card(pal) {
                StatusActionRow(pal,"短信权限","允许接收新短信，用于提取验证码",R.drawable.ic_copy,allowed) {
                    openAppDetails()
                }
                Line(pal)
                if(xiaomi) {
                    StatusActionRow(pal,sms.title,"进入「权限管理 → 其他权限」。若有「通知类短信」，打开并选择「始终允许」。",
                        R.drawable.ic_copy,complete=null,statusLabel=if(smsConfirmed) "已确认" else "查看") {
                        confirmPermission = sms
                    }
                    Line(pal)
                }
                val clipboardPermission = if(xiaomi) "在「其他权限」中找到「写入剪贴板」。若有此项，选择「始终允许」。"
                    else "在应用的「权限」或「权限管理」中查找「写入剪贴板」。若有此项，选择允许后台写入的选项。"
                StatusActionRow(pal,clipboard.title,clipboardPermission,R.drawable.ic_clip,complete=clipboardStatus,
                    statusLabel=if(clipboardStatus==null) if(clipboardConfirmed) "已确认" else "查看" else null) {
                    if(clipboardStatus==null) confirmPermission = clipboard else openAppDetails()
                }
              }
              confirmPermission?.let { permission ->
                  val confirmed = if(permission==sms) smsConfirmed else clipboardConfirmed
                  SmsPermissionConfirmation(pal,permission,confirmed,{confirmPermission=null},::openAppDetails) {
                      val next = !confirmed
                      if(permission.setConfirmed(this,next)) {
                          if(permission==sms) smsConfirmed=next else clipboardConfirmed=next
                          confirmPermission=null
                      }
                  }
              }
              StatsHeading(pal,"自动复制设置流程")
              Card(pal) {
                val path = if(xiaomi) "小米澎湃 OS 设置 → 应用设置 → 空叶键盘 → 权限管理 → 其他权限"
                    else "系统设置 → 应用 → 空叶键盘 → 权限"
                BasicText(path,style=TextStyle(color=pal.ink,fontSize=14.sp,lineHeight=22.sp),modifier=Modifier.padding(vertical=12.dp))
                AndroidView(
                    factory = { SmsSetupGuideView(it) },
                    update = { it.showGuide(guide, pal.ink.toArgb(), pal.text2.toArgb(), pal.tile.toArgb(), pal.accent.toArgb(), pal.card.toArgb()) },
                    onRelease = { it.destroy() },
                    modifier = Modifier.fillMaxWidth(),
                )
                BasicText("点击动画可暂停或继续",style=TextStyle(color=pal.text2,fontSize=12.sp),
                    modifier=Modifier.padding(top=8.dp))
                val detail = if(xiaomi) "按上方开启自动复制并允许短信权限。若系统有「通知类短信」，也请在其他权限中允许。"
                    else "按上方开启自动复制并允许短信权限。菜单名称因系统而异，可在设置里搜索「空叶键盘」。"
                BasicText(detail,style=TextStyle(color=pal.text2,fontSize=13.sp,lineHeight=20.sp),modifier=Modifier.padding(top=10.dp))
                ActionRow(pal,"空叶键盘应用权限",null,"去设置",::openAppDetails)
              }
            }
            Page.TRANSLATION -> SettingsChoice(pal,"目标语言",Prefs.TRANS_TO,Translator.TARGETS,"auto",tick,showExample=false,inlineDropdown=true)
            Page.STATS -> {
                StatisticsPage(pal,tick)
            }
            Page.ABOUT -> Card(pal) {
                Tip(pal,"空叶键盘","v${UpdateChecker.currentVersion(this)}")
                UpdateEntry(pal,"查看版本和安装状态")
                Line(pal);ActionRow(pal,"更新历史","搜索、分页与完整更新内容","›") {go(Page.HISTORY);loadHistory()}
            }
            else -> Unit
        }
    }

    @Composable
    private fun LayoutPage(pal: Pal, tick: Int) {
        val prefs = Prefs.of(this)
        val separate = remember(tick) { prefs.getBoolean(V3Prefs.ORIENTATION_LAYOUT, false) }
        val pager = rememberPagerState(initialPage = if (separate && FloatingKeyboard.landscape(this)) 1 else 0,
            pageCount = { if (separate) 2 else 1 })
        val scope = rememberCoroutineScope()
        val keyOptions = listOf("26" to "26 键", "14" to "14 键", "9" to "九键")
        val formOptions = KeyboardForm.entries.map { it to it.label }
        val request = noticeFocus.value
        LaunchedEffect(request?.serial, separate) {
            if (request?.page == Page.LAYOUT && separate) {
                val target = if (request.key in setOf(V3Prefs.LAYOUT_LANDSCAPE, V3Prefs.LANDSCAPE_FLOAT, V3Prefs.LANDSCAPE_SPLIT)) 1 else 0
                pager.scrollToPage(target)
            }
        }
        Column(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Card(pal, Modifier.noticeAttention(pal, V3Prefs.ORIENTATION_LAYOUT)) {
                    ToggleRow(pal, "横竖屏分别记住布局", "分别记住键位、键盘形态、高度、边距和分体尺寸", separate) {
                        V3Prefs.setSeparateLayouts(this@SettingsActivity, it)
                    }
                }
                if (separate) Segment(pal, listOf("竖屏", "横屏"), pager.currentPage) { index ->
                    scope.launch { pager.animateScrollToPage(index) }
                }
            }
            HorizontalPager(state = pager, modifier = Modifier.weight(1f).fillMaxWidth()) { index ->
                val landscape = separate && index == 1
                val title = if (!separate) "共用" else if (landscape) "横屏" else "竖屏"
                val layoutKey = if (landscape) V3Prefs.LAYOUT_LANDSCAPE else Prefs.LAYOUT
                val formKey = if (landscape) V3Prefs.LANDSCAPE_FLOAT else Prefs.FLOAT
                val layout = V3Prefs.layout(prefs, landscape)
                val form = V3Prefs.keyboardForm(prefs, landscape)
                CompositionLocalProvider(LocalLayoutPreviewLandscape provides (if (separate) landscape else FloatingKeyboard.landscape(this@SettingsActivity))) {
                    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        if (!separate) StatsHeading(pal, "共用布局")
                        VisualChoice(pal, "中文键位", layoutKey, keyOptions, layout, tick,
                            accessibilityTitle = title + "中文键位") {
                            V3Prefs.saveLayout(this@SettingsActivity, landscape, it)
                        }
                        VisualChoice(pal, "键盘形态", formKey, formOptions, form, tick,
                            accessibilityTitle = title + "键盘形态") {
                            V3Prefs.saveKeyboardForm(this@SettingsActivity, landscape, it)
                        }
                        when (form) {
                            KeyboardForm.NORMAL -> {
                                StatsHeading(pal, "单手使用")
                                VisualChoice(pal, "单手模式", V3Prefs.ONE_HAND,
                                    listOf(0 to "关闭", 1 to "靠左", 2 to "靠右"), 0, tick)
                            }
                            KeyboardForm.SPLIT -> {
                                StatsHeading(pal, "分体选项")
                                if (layout == "9") BasicText("九键暂不支持分体，当前按正常键盘显示。",
                                    style = TextStyle(color = pal.text2, fontSize = 12.sp, lineHeight = 19.sp))
                                else {
                                    SettingsToggle(pal, "铺满键盘空白区域", V3Prefs.FILL_GAPS, true, tick,
                                        "填充分体中缝与键盘两侧，关闭后透出应用内容")
                                    BasicText("屏幕过窄时会显示完整键盘。",
                                        style = TextStyle(color = pal.text2, fontSize = 12.sp, lineHeight = 19.sp))
                                }
                            }
                            KeyboardForm.FLOATING -> if (layout != "9") {
                                StatsHeading(pal, "悬浮选项")
                                SettingsToggle(pal, "悬浮分体键盘", FloatingKeyboard.SPLIT, false, tick,
                                    "支持 26 键和 14 键，宽屏下生效")
                            }
                        }
                        Spacer(Modifier.height(106.dp))
                    }
                }
            }
        }
    }

    @Composable
    private fun key(k: Int, content: @Composable () -> Unit) = androidx.compose.runtime.key(k) { content() }

    @Composable
    private fun Preview(pal: Pal, tick: Int, scaleOverride: Float? = null) {
        val skin = remember(tick) { Prefs.theme(this).pick(Prefs.night(this)).styled(1f,Prefs.of(this).getBoolean(Prefs.GLASS,false)) }
        val layout = remember(tick) { Prefs.layout(this) }
        val previewBoxMod = Modifier.fillMaxWidth()

        Card(pal) {
            Box(previewBoxMod) {
                AndroidView(
                    factory = { ctx ->
                        KeyboardPreviewLayout(ctx).apply {
                            orientation = LinearLayout.VERTICAL
                            addView(CandidateBar(ctx))
                            addView(KeyboardView(ctx).apply { preview = true })
                        }
                    },
                    update = { ll ->
                        ll.alpha=Prefs.opacity(this@SettingsActivity)
                        ll.background = if (skin.bg2 != 0) android.graphics.drawable.GradientDrawable(android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(skin.kbBg, skin.bg2))
                        else android.graphics.drawable.ColorDrawable(skin.kbBg)
                        (ll.getChildAt(0) as CandidateBar).apply { this.skin = skin;uiScale=scaleOverride ?: (Prefs.heightScale(this@SettingsActivity)*ResponsiveSize.keyboardScale(resources.configuration.screenWidthDp.toFloat(),resources.configuration.screenHeightDp.toFloat())); showTools(listOf("more") + Prefs.tools(this@SettingsActivity)) }
                        (ll.getChildAt(1) as KeyboardView).apply {
                            heightScale = scaleOverride ?: (Prefs.heightScale(this@SettingsActivity)*ResponsiveSize.keyboardScale(resources.configuration.screenWidthDp.toFloat(),resources.configuration.screenHeightDp.toFloat()))
                            zh = true
                            val rows = when (layout) { "14" -> Layouts.fourteen(this@SettingsActivity); "9" -> Layouts.nine(); else -> Layouts.letters(true,this@SettingsActivity) }
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
        StatsHeading(pal,"当前效果")
        Preview(pal, tick)
        StatsHeading(pal,"背景效果")
        Card(pal) {
            ToggleRow(pal, "半透明效果", null, glass) { p.edit().putBoolean(Prefs.GLASS, it).apply() }
            Line(pal)
            BasicText("键盘透明度", style = TextStyle(color = pal.ink, fontSize = 16.sp), modifier = Modifier.padding(top = 12.dp))
            Slider(pal, opacity, 0.3f, 1f, { opacity = it }, format = { "${(it * 100).roundToInt()}%" }) {
                p.edit().putFloat(Prefs.OPACITY, it).apply()
            }
        }
        StatsHeading(pal,"明暗模式")
        VisualChoice(pal,"深色模式",Prefs.MODE,listOf(0 to "跟随系统",1 to "浅色模式",2 to "深色模式"),0,tick)
        StatsHeading(pal,"配色方案")
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
    private fun HeightPage(pal:Pal,tick:Int) {
        val p=Prefs.of(this)
        var previewMode by rememberSaveable { mutableIntStateOf(V3Prefs.keyboardForm(this).ordinal) }
        val floatingPreview=previewMode==2
        val metrics=resources.displayMetrics
        val widthRange=FloatingKeyboard.widthRange(metrics.widthPixels.toFloat(),metrics.heightPixels.toFloat())
        var height by remember(tick,floatingPreview){mutableFloatStateOf(if(floatingPreview)FloatingKeyboard.height(this) else p.getFloat(Prefs.HEIGHT_SCALE,1f))}
        var left by remember(tick){mutableFloatStateOf(p.getInt(Prefs.PAD_L,0).toFloat())}
        var right by remember(tick){mutableFloatStateOf(p.getInt(Prefs.PAD_R,0).toFloat())}
        var bottom by remember(tick){mutableFloatStateOf(p.getInt(Prefs.PAD_B,0).toFloat())}
        var gap by remember(tick,floatingPreview){mutableFloatStateOf(p.getInt(if(floatingPreview)FloatingKeyboard.GAP else V3Prefs.SPLIT_GAP,72).toFloat())}
        var size by remember(tick,floatingPreview){mutableFloatStateOf(p.getFloat(if(floatingPreview)FloatingKeyboard.SIZE else V3Prefs.SPLIT_SIZE,1f))}
        var floatWidth by remember(tick){mutableFloatStateOf(FloatingKeyboard.width(this))}
        val floatSplit=p.getBoolean(FloatingKeyboard.SPLIT,false)
        var floatSplitPreview by rememberSaveable {mutableStateOf(floatSplit)}
        LaunchedEffect(floatSplit){floatSplitPreview=floatSplit}
        val splitPreview=previewMode==1||(floatingPreview&&floatSplitPreview)
        val skin=remember(tick){Prefs.skin(this)}
        fun resetDimensions() {
            height=1f;gap=72f;size=1f
            if(floatingPreview) {
                floatWidth=FloatingKeyboard.defaultWidth(metrics.widthPixels.toFloat(),metrics.heightPixels.toFloat()).coerceIn(widthRange)
                floatSplitPreview=floatSplit
                p.edit().putFloat(FloatingKeyboard.HEIGHT,1f).putFloat(FloatingKeyboard.widthKey(this),floatWidth)
                    .putInt(FloatingKeyboard.GAP,72).putFloat(FloatingKeyboard.SIZE,1f).apply()
            } else {
                left=0f;right=0f;bottom=0f
                p.edit().putFloat(Prefs.HEIGHT_SCALE,1f).putInt(Prefs.PAD_L,0).putInt(Prefs.PAD_R,0).putInt(Prefs.PAD_B,0)
                    .putInt(V3Prefs.SPLIT_GAP,72).putFloat(V3Prefs.SPLIT_SIZE,1f).apply()
            }
        }
        Column(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxWidth().padding(horizontal=16.dp,vertical=10.dp)) {
                Row(Modifier.fillMaxWidth().padding(bottom=8.dp),verticalAlignment=Alignment.CenterVertically) {
                    BasicText("实时预览",style=TextStyle(color=pal.ink,fontSize=14.sp,fontWeight=FontWeight.Medium),modifier=Modifier.weight(1f))
                }
                Card(pal) {
                    Row(Modifier.fillMaxWidth().padding(vertical=6.dp),verticalAlignment=Alignment.CenterVertically) {
                        Row(Modifier.clip(RoundedCornerShape(10.dp)).background(pal.track).padding(3.dp)) {
                            listOf(0 to "完整键盘",1 to "分体键盘",2 to "悬浮键盘").forEach { (mode,label)->
                                val selected=previewMode==mode
                                BasicText(label,style=TextStyle(color=if(selected)pal.ink else pal.text2,fontSize=12.sp,fontWeight=if(selected)FontWeight.Medium else FontWeight.Normal),
                                    modifier=Modifier.clip(RoundedCornerShape(8.dp)).background(if(selected)pal.card else Color.Transparent).clickable {previewMode=mode;if(mode==2)floatSplitPreview=floatSplit}.padding(horizontal=8.dp,vertical=8.dp))
                            }
                        }
                        Spacer(Modifier.weight(1f))
                        HeaderAction(pal,R.drawable.ic_refresh,"重置") {resetDimensions()}

                    }
                    AndroidView(factory={KeyboardAdjustmentPreview(it)},update={it.configure(skin,Prefs.layout(this@SettingsActivity),height,left.roundToInt(),right.roundToInt(),bottom.roundToInt(),gap,size,splitPreview,floatingPreview,floatWidth)},
                        modifier=Modifier.fillMaxWidth().heightIn(max=minOf(380f,androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp*.42f).dp).clip(RoundedCornerShape(12.dp)))
                }
            }
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal=16.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
                @Composable fun adjust(title:String,key:String,value:Float,min:Float,max:Float,factor:Float=1f,unit:String="dp",floating:Boolean=false,inCard:Boolean=true,centerValue:Float?=null,change:(Float)->Unit) {
                    ChoiceContainer(pal,inCard) {
                        BasicText(title,style=TextStyle(color=pal.ink,fontSize=16.sp),modifier=Modifier.padding(top=12.dp))
                        Box(Modifier.semantics {contentDescription="$title 调整"}) {
                            Slider(pal,value,min,max,change,format={"${(it*factor).roundToInt()}$unit"},centerValue=centerValue) {committed->
                                val edit=p.edit()
                                if(floating)edit.putFloat(key,committed) else edit.putInt(key,committed.roundToInt())
                                edit.apply()
                            }
                        }
                    }
                }
                StatsHeading(pal,if(floatingPreview)"悬浮尺寸" else "整体尺寸")
                adjust("键盘高度",if(floatingPreview)FloatingKeyboard.HEIGHT else Prefs.HEIGHT_SCALE,height,.7f,1.5f,100f,"%",true,centerValue=1f){height=it}
                if(floatingPreview) {
                    adjust("悬浮宽度",FloatingKeyboard.widthKey(this@SettingsActivity),floatWidth,widthRange.start,widthRange.endInclusive,100f,"%",true){floatWidth=it}
                } else {
                    StatsHeading(pal,"左右收进")
                    Card(pal) {
                        adjust("左侧收进",Prefs.PAD_L,left,0f,120f,inCard=false){left=it}
                        Line(pal)
                        adjust("右侧收进",Prefs.PAD_R,right,0f,120f,inCard=false){right=it}
                    }
                    StatsHeading(pal,"底部留白")
                    adjust("底部留白",Prefs.PAD_B,bottom,0f,120f){bottom=it}
                }
                StatsHeading(pal,"分体设置")
                Card(pal) {
                    adjust("分体间距",if(floatingPreview)FloatingKeyboard.GAP else V3Prefs.SPLIT_GAP,gap,24f,220f,inCard=false){gap=it;if(floatingPreview)floatSplitPreview=true else previewMode=1}
                    Line(pal)
                    adjust("分体按键尺寸",if(floatingPreview)FloatingKeyboard.SIZE else V3Prefs.SPLIT_SIZE,size,.7f,1f,100f,"%",true,inCard=false){size=it;if(floatingPreview)floatSplitPreview=true else previewMode=1}
                }
                Spacer(Modifier.height(92.dp))
            }
        }
    }


    @Composable
    private fun ArrangePage(pal: Pal, tick: Int) {
        val skin = remember(tick) { Prefs.skin(this) }
        val tools = remember(tick) { Prefs.tools(this) }
        val menu = remember(tick) { Prefs.menu(this) }
        Card(pal) {
            AndroidView(
                factory = { ctx -> ArrangeView(ctx, skin, tools, menu) { t, m -> Prefs.saveArrangement(ctx, t, m) } },
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
        SettingsChoice(pal,"记录上限",V3Prefs.CLIP_LIMIT,listOf(100 to "100 条",500 to "500 条",1000 to "1000 条",5000 to "5000 条"),500,tick,showExample=false,inlineDropdown=true) {ClipStore.prune()}
        SettingsChoice(pal,"保留时间",V3Prefs.CLIP_DAYS,listOf(0 to "一直保留",1 to "1 天",7 to "7 天",30 to "30 天"),0,tick,showExample=false,inlineDropdown=true) {ClipStore.prune()}
        Card(pal) {
            BasicText("剪贴板记录", style = TextStyle(color = pal.ink, fontSize = 16.sp, fontWeight = FontWeight.Medium),
                modifier = Modifier.padding(top = 8.dp, bottom = 12.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("总记录" to count, "置顶" to pinned, "图片" to images).forEach { (label, total) ->
                    Column(Modifier.weight(1f).clip(RoundedCornerShape(12.dp)).background(pal.tile).padding(vertical = 8.dp),
                        horizontalAlignment = Alignment.CenterHorizontally) {
                        BasicText(total.toString(), style = TextStyle(color = pal.ink, fontSize = 20.sp, fontWeight = FontWeight.Bold))
                        BasicText(label, style = TextStyle(color = pal.text2, fontSize = 12.sp), modifier = Modifier.padding(top = 2.dp))
                    }
                }
            }
            BasicText("只保存在这台手机上。达到上限时，先移除最旧的普通记录。",
                style = TextStyle(color = pal.text2, fontSize = 12.sp, lineHeight = 18.sp), modifier = Modifier.padding(top = 10.dp, bottom = 6.dp))
        }
        Card(pal) {
            ActionRow(pal, "清空历史", hint.ifEmpty { "保留置顶和自定义分类" }, "清空") {
                if (confirmed("clips")) { ClipStore.clearUnpinned(); hint = "已清空" } else hint = "再点一次“清空”确认"
            }
        }
    }

    @Composable
    private fun FlicksPage(pal:Pal,tick:Int,go:(Page)->Unit) {
        StatsHeading(pal,"启用与方向")
        Card(pal, Modifier.noticeAttention(pal, Prefs.SWIPE)) {
            val p=Prefs.of(this)
            val enabled=p.getBoolean(Prefs.SWIPE,true)
            ToggleRow(pal,"启用上下滑",null,enabled) {p.edit().putBoolean(Prefs.SWIPE,it).apply()}
            SettingNotice(pal,Prefs.SWIPE,go)
            Line(pal)
            ToggleRow(pal,"反转上下滑方向","仅反转手势方向，角标位置保持不变",enabled&&p.getBoolean(V3Prefs.SWIPE_REVERSE,false),enabled=enabled) {p.edit().putBoolean(V3Prefs.SWIPE_REVERSE,it).apply()}
            SettingNotice(pal,V3Prefs.SWIPE_REVERSE)
        }
        @Suppress("UNUSED_VARIABLE") val settingsRevision=tick
        var zh by remember {mutableStateOf(true)}
        var letter by remember {mutableStateOf('q')}
        var down by remember {mutableStateOf(true)}
        var content by remember {mutableStateOf("")}
        var editKind by rememberSaveable {mutableIntStateOf(0)}
        var pendingCommand by remember {mutableStateOf<String?>(null)}
        var pendingSymbol by remember {mutableStateOf(false)}
        var clearHint by remember {mutableStateOf("")}
        var mappingRevision by remember {mutableIntStateOf(0)}
        val revision=mappingRevision
        val key=Layouts.letters(zh,this).flatMap {it.keys}.first {it.gestureId==letter}
        val reverse=Prefs.of(this).getBoolean(V3Prefs.SWIPE_REVERSE,false)
        val physicalDown=down!=reverse
        val action=if(physicalDown) key.downAction else key.upAction
        val preview=pendingCommand?.let {KeyGestures.commands[it]}.orEmpty().ifEmpty {content}
        fun resetDraft() {content="";pendingCommand=null;pendingSymbol=false;clearHint=""}
        StatsHeading(pal,"选择按键")
        Card(pal) {
            Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
                Segment(pal,listOf("中文","英文"),if(zh) 0 else 1) {zh=it==0;resetDraft()}
                Spacer(Modifier.weight(1f))
                HeaderAction(pal,R.drawable.ic_trash,"清空${if(zh) "中文" else "英文"}上下滑") {
                    if(confirmed("flick-clear-$zh")) {KeyGestures.clear(this@SettingsActivity,zh);mappingRevision++;resetDraft()}
                    else clearHint="再点一次清空${if(zh) "中文" else "英文"}映射，可用重置恢复。"
                }
                HeaderAction(pal,R.drawable.ic_refresh,"恢复${if(zh) "中文" else "英文"}默认") {
                    KeyGestures.reset(this@SettingsActivity,zh);mappingRevision++;resetDraft()
                }
            }
            if(clearHint.isNotEmpty()) BasicText(clearHint,style=TextStyle(color=pal.accent,fontSize=12.sp,lineHeight=18.sp),modifier=Modifier.padding(top=6.dp))
            Spacer(Modifier.height(12.dp))
            AndroidView(factory={ctx ->FlickPreviewView(ctx)},
                update={it.onPick={c->letter=c;resetDraft()};it.submit(zh,letter,down,preview,revision,pendingSymbol,KeyGestures.icon(pendingCommand))},
                modifier=Modifier.fillMaxWidth().heightIn(max=280.dp).clip(RoundedCornerShape(12.dp)))
            Spacer(Modifier.height(12.dp))
            Segment(pal,if(reverse)listOf("上滑 · 右上角","下滑 · 左下角") else listOf("下滑 · 右上角","上滑 · 左下角"),if(down) 0 else 1) {down=it==0;resetDraft()}
            Spacer(Modifier.height(12.dp))
            Tip(pal,"当前 ${KeyGestures.displayName(zh,letter)} ${if(physicalDown) "下滑" else "上滑"}",if(pendingSymbol && content.isEmpty()) "未设置" else preview.ifEmpty {action?.label.orEmpty().ifEmpty {"未设置"}})
        }
        StatsHeading(pal,"设置动作")
        Card(pal) {
            Segment(pal,listOf("符号文字","编辑功能"),editKind) {editKind=it}
            ActionRow(pal,if(editKind==0)"自定义符号" else "编辑功能","${if(zh)"中文" else "英文"} ${KeyGestures.displayName(zh,letter)} · ${if(physicalDown)"下滑" else "上滑"}","保存") {
                if(editKind==0&&pendingSymbol) {KeyGestures.save(this@SettingsActivity,zh,letter,down,"text:$content");mappingRevision++;resetDraft()}
                else if(editKind==1)pendingCommand?.let {KeyGestures.save(this@SettingsActivity,zh,letter,down,"edit:$it");mappingRevision++;resetDraft()}
            }
            if(editKind==0) {
            BasicTextField(content,{value ->
                content=value.take(24)
                pendingCommand=null
                pendingSymbol=true
            },textStyle=TextStyle(color=pal.ink,fontSize=18.sp),modifier=Modifier.fillMaxWidth().background(pal.tile,RoundedCornerShape(12.dp)).padding(14.dp),
                decorationBox={inner ->if(content.isEmpty()) BasicText("输入自定义符号或文字",style=TextStyle(color=pal.text2,fontSize=15.sp));inner()})
            } else {
            val commandIcons=KeyGestures.commandIcons
            Column(Modifier.padding(top=4.dp,bottom=14.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {
                KeyGestures.commands.entries.chunked(4).forEach {row->
                    Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(10.dp)) {
                        row.forEach {(id,label)->
                            val active=(pendingCommand ?: action?.command)==id
                            Column(Modifier.weight(1f).height(62.dp).clip(RoundedCornerShape(13.dp))
                                .background(if(active)pal.accent.copy(alpha=.08f) else pal.tile)
                                .border(if(active)1.6.dp else 1.dp,if(active)pal.accent else pal.line,RoundedCornerShape(13.dp))
                                .clickable {pendingCommand=id;pendingSymbol=false;content=""}.semantics {contentDescription="编辑功能：$label"},
                                horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.Center) {
                                Image(painterResource(commandIcons.getValue(id)),null,Modifier.size(21.dp),colorFilter=ColorFilter.tint(if(active)pal.accent else pal.ink))
                                Spacer(Modifier.height(6.dp))
                                BasicText(label,style=TextStyle(color=if(active)pal.accent else pal.ink,fontSize=13.sp,fontWeight=if(active)FontWeight.Medium else FontWeight.Normal))
                            }
                        }
                    }
                }
            }
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
        var selectedSideIdx by remember { mutableIntStateOf(-1) }
        var customInput by remember { mutableStateOf("") }

        fun saveAll(newSide: List<String> = sideList, newKeys: List<String> = customKeys) {
            sideList = newSide
            customKeys = newKeys
            p.edit()
                .putString(Prefs.NUM_SIDE_KEY, newSide.joinToString(" "))
                .putString(Prefs.NUM_KEYS, newKeys.joinToString("\u0001"))
                .apply()
        }

        fun displayKey(s: String) = if (s == " ") "空格" else s

        // 1. 顶部实时可视化数字键盘（点击任意可定制区域直接选中修改）
        Card(pal) {
            Row(Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 6.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                BasicText("数字键盘",style=TextStyle(color=pal.ink,fontSize=16.sp,fontWeight=FontWeight.Medium))
                HeaderAction(pal,R.drawable.ic_refresh,"恢复默认") {
                    p.edit().remove(Prefs.NUM_SIDE_KEY).remove(Prefs.NUM_KEYS).apply()
                    sideList = Prefs.DEFAULT_NUM_SIDE
                    customKeys = Prefs.DEFAULT_NUM_KEYS
                    selectedSideIdx = -1
                }
            }

            BasicText("点选描边区域，在下方修改",style=TextStyle(color=pal.text2,fontSize=13.sp,lineHeight=20.sp),modifier=Modifier.padding(bottom=10.dp))

            AndroidView(factory={NumericKeyboardPreview(it)},update={view->
                view.configure(Prefs.skin(this),customKeys,sideList,selectedSlot,pal.accent.toArgb()){selectedSlot=it}
            },modifier=Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)))
            Spacer(Modifier.height(4.dp))
        }

        // 2. 下方对应选中区域的可视化配置面板
        val slotNames = listOf("「6 右边」按键", "「9 右边」按键", "「0 右边」按键", "「左侧上下滑动符号列」")
        BasicText("当前编辑：${slotNames[selectedSlot]}",style=TextStyle(color=pal.ink,fontSize=16.sp,fontWeight=FontWeight.Medium),modifier=Modifier.padding(top=4.dp,bottom=2.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(0 to "6 右边", 1 to "9 右边", 2 to "0 右边", 3 to "左列符号").forEach { (idx, name) ->
                val sel = selectedSlot == idx
                Box(
                    Modifier.weight(1f).clip(RoundedCornerShape(50)).background(if (sel) pal.accent.copy(alpha=.1f) else pal.card)
                        .border(1.dp, if (sel) pal.accent else pal.line, RoundedCornerShape(50))
                        .clickable { selectedSlot = idx }.padding(vertical = 8.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    BasicText(name, style = TextStyle(color = if (sel) pal.accent else pal.text2, fontSize = 13.sp, fontWeight = FontWeight.Medium))
                }
            }
        }

        if (selectedSlot in 0..2) {
            val curVal = customKeys[selectedSlot]
            val presets = listOf(" ", "@", ".", ",", "-", "+", "/", "*", "=", ":", "#", "%", "00", "¥", "$", "_")
            Card(pal) {
                BasicText("选择功能或符号", style = TextStyle(color = pal.ink, fontSize = 15.sp, fontWeight = FontWeight.Bold), modifier = Modifier.padding(top = 6.dp, bottom = 8.dp))
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
            val symbolOptions = listOf("%", "+", "-", "/", ":", "=", "*", "#", "¥", "(", ")", "~", "_", "·", ",", ";", "!", "?", "、", "。", "[", "]", "$", "@")
            val candidatePool = (Prefs.DEFAULT_NUM_SIDE + symbolOptions).distinct()
            Card(pal) {
                BasicText("左列符号顺序", style = TextStyle(color = pal.ink, fontSize = 15.sp, fontWeight = FontWeight.Bold), modifier = Modifier.padding(top = 6.dp, bottom = 6.dp))
                BasicText("单击选中，再点取消；长按拖动调整位置",style=TextStyle(color=pal.text2,fontSize=12.sp,lineHeight=18.sp),modifier=Modifier.padding(bottom=8.dp))
                AndroidView(factory={SymbolOrderView(it)},update={view->
                    view.configure(sideList,selectedSideIdx,pal.tile.toArgb(),pal.ink.toArgb(),pal.accent.toArgb(),
                        {selectedSideIdx=it},{saveAll(newSide=it)})
                },modifier=Modifier.fillMaxWidth())
                if(selectedSideIdx in sideList.indices) {
                    Row(Modifier.fillMaxWidth().padding(vertical=8.dp),verticalAlignment=Alignment.CenterVertically) {
                        BasicText("已选中：${sideList[selectedSideIdx]}",style=TextStyle(color=pal.text2,fontSize=13.sp),modifier=Modifier.weight(1f))
                        Pill(pal,"删除选中",false) {
                            val next = sideList.toMutableList().also { it.removeAt(selectedSideIdx) }
                            selectedSideIdx = -1
                            saveAll(newSide = next)
                        }
                    }
                }
                Line(pal, top = 4.dp)
                BasicText("点灰色符号加入左列，点红色符号移除", style = TextStyle(color = pal.text2, fontSize = 13.sp), modifier = Modifier.padding(top = 8.dp, bottom = 4.dp))
                candidatePool.chunked(6).forEach { row ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        row.forEach { sym ->
                            val order = sideList.indexOf(sym) + 1
                            val exists = order > 0
                            Box(
                                Modifier.weight(1f).height(38.dp).clip(RoundedCornerShape(10.dp))
                                    .background(if (exists) pal.accent.copy(alpha = 0.14f) else pal.tile)
                                    .clickable {
                                        val previouslySelected = sideList.getOrNull(selectedSideIdx)
                                        val next = if (exists) sideList.filterNot { it == sym } else sideList + sym
                                        selectedSideIdx = if (exists) next.indexOf(previouslySelected) else next.lastIndex
                                        saveAll(newSide = next)
                                    }.semantics {
                                        contentDescription = if (exists) "移除 $sym" else "加入 $sym"
                                        if (exists) stateDescription = "左列第 $order 个"
                                    },
                                contentAlignment = Alignment.Center,
                            ) {
                                BasicText(sym, style = TextStyle(color = if (exists) pal.accent else pal.ink, fontSize = 16.sp, fontWeight = FontWeight.Medium))
                                if (exists) BasicText(order.toString(), style = TextStyle(color = pal.accent, fontSize = 10.sp, fontWeight = FontWeight.Medium),
                                    modifier = Modifier.align(Alignment.TopEnd).padding(top = 2.dp, end = 5.dp))
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
    private fun SmsPermissionConfirmation(
        pal: Pal,
        permission: SmsSetupPermission,
        confirmed: Boolean,
        dismiss: () -> Unit,
        openSettings: () -> Unit,
        toggleConfirmation: () -> Unit,
    ) {
        androidx.compose.ui.window.Dialog(onDismissRequest = dismiss) {
            Column(
                Modifier.widthIn(max = 420.dp).fillMaxWidth().clip(RoundedCornerShape(22.dp))
                    .background(pal.card).padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                BasicText(permission.title, style = TextStyle(color = pal.ink, fontSize = 18.sp, fontWeight = FontWeight.Bold))
                val message = if (confirmed) "你已在本机确认这项权限。可以打开系统设置重新查看。"
                    else "请在系统设置中查看这项权限。若已允许，可在这里标记为已确认。"
                BasicText(message, style = TextStyle(color = pal.text2, fontSize = 14.sp, lineHeight = 22.sp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                    Pill(pal, "打开设置", false) { dismiss(); openSettings() }
                    Pill(pal, if (confirmed) "取消确认" else "我已允许", !confirmed, toggleConfirmation)
                }
            }
        }
    }

    @Composable
    private fun UpdateEntry(pal:Pal,sub:String) {
        val tag=release.value?.tag ?: Prefs.of(this).getString("latest_update_tag","").orEmpty()
        val newer=tag.isNotEmpty()&&UpdateChecker.newer(tag.trimStart('v','V'),UpdateChecker.currentVersion(this))
        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable {openUpdateDialog()}.heightIn(min=48.dp).padding(vertical=9.dp),verticalAlignment=Alignment.CenterVertically) {
            Column(Modifier.weight(1f).padding(end=12.dp)) {
                BasicText("检查更新",style=TextStyle(color=pal.ink,fontSize=15.sp,fontWeight=FontWeight.Medium))
                BasicText(if(newer)"发现新版本 "+tag else sub,style=TextStyle(color=if(newer)pal.accent else pal.text2,fontSize=12.sp,lineHeight=17.sp),modifier=Modifier.padding(top=4.dp))
            }
            if(newer)Box(Modifier.size(7.dp).clip(CircleShape).background(pal.accent))
            else Image(painterResource(R.drawable.ic_refresh),null,Modifier.size(18.dp),colorFilter=ColorFilter.tint(pal.text2))
        }
    }

    @Composable
    private fun UpdateDialog(pal: Pal, tick: Int, onHistory: () -> Unit) {
        val version = remember { UpdateChecker.currentVersion(this) }
        val rel = release.value
        val newer = rel != null && UpdateChecker.newer(rel.version, version)
        val ready = newer && downloadedApk.value?.isFile == true
        val permission = remember(tick) { packageManager.canRequestPackageInstalls() }
        val installation by AppUpdate.installation.collectAsState()
        val session = installation as? AppUpdate.Installation.Installing
        val preparation = installation as? AppUpdate.Installation.Preparing
        val confirmation = installation as? AppUpdate.Installation.Confirm
        val failure = installation as? AppUpdate.Installation.Failed
        val complete = installation == AppUpdate.Installation.Complete
        val busy = preparation != null || session != null || confirmation != null
        val transferring = downloading.value || busy
        var systemProgress by remember(session) { mutableStateOf<Float?>(null) }
        LaunchedEffect(session) {
            if (session != null) {
                while (true) {
                    systemProgress = AppUpdate.installationProgress(this@SettingsActivity, session.sessionId)
                    delay(250)
                }
            }
        }
        LaunchedEffect(confirmation, tick) {
            if (confirmation != null && lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)) {
                AppUpdate.requestConfirmation(this@SettingsActivity, confirmation)
            }
        }
        val dismiss = {
            updateDialogVisible.value = false
            AppUpdate.clearInstallationResult(this)
        }
        val title = when {
            complete -> "更新已安装"
            busy -> "安装更新"
            downloading.value -> "下载更新"
            newer -> "发现新版本"
            else -> "软件更新"
        }
        val progress = when {
            preparation != null -> preparation.percent?.div(100f)
            session != null -> systemProgress?.takeIf { it > 0f && it < 1f }
            validatingUpdate.value -> null
            downloading.value -> downloadProgress.value
            else -> null
        }
        val progressText = when {
            preparation != null && preparation.percent == null -> "正在校验安装包"
            preparation != null -> "正在准备安装"
            confirmation != null -> "等待系统确认"
            session != null -> "正在安装更新"
            validatingUpdate.value -> "正在校验安装包"
            downloading.value -> "正在下载安装包"
            else -> "正在检查更新"
        }
        val progressValue = when {
            progress != null -> "${(progress * 100).roundToInt()}%"
            downloading.value && !validatingUpdate.value -> downloadState.value.takeIf { it.startsWith("已下载") }
            else -> null
        }
        val detail = when {
            complete -> "当前版本 v$version"
            failure != null -> failure.message
            checkingUpdate -> null
            rel == null -> updateState.value.ifBlank { "暂时无法获取版本信息。" }
            !newer -> "当前版本 v$version，已是最新版本。"
            ready && !permission -> "请允许空叶键盘安装更新，返回后继续。"
            ready -> "安装包已校验，覆盖安装会保留现有设置。"
            rel.apkUrl == null -> "暂无适配本机的安装包。"
            else -> "下载完成后即可安装。"
        }
        androidx.compose.ui.window.Dialog(
            onDismissRequest = dismiss,
            properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false),
        ) {
            Column(
                Modifier.widthIn(max = 400.dp).fillMaxWidth(.9f).heightIn(max = 560.dp)
                    .clip(RoundedCornerShape(24.dp)).background(pal.card).padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Box(Modifier.size(44.dp).clip(RoundedCornerShape(14.dp)).background(pal.tile),
                        contentAlignment = Alignment.Center) {
                        Image(painterResource(R.drawable.ic_download), null, Modifier.size(22.dp),
                            colorFilter = ColorFilter.tint(pal.accent))
                    }
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        BasicText(title, style = TextStyle(color = pal.ink, fontSize = 20.sp, fontWeight = FontWeight.Bold))
                        if (newer && rel != null && !complete) {
                            BasicText("v$version → ${rel.tag}", style = TextStyle(color = pal.text2, fontSize = 13.sp))
                        }
                    }
                }
                if (transferring || checkingUpdate) {
                    UpdateProgress(pal, progressText, progressValue, progress)
                } else {
                    Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (detail != null) {
                            BasicText(detail, style = TextStyle(
                                color = if (failure != null) pal.bad else pal.text2, fontSize = 14.sp, lineHeight = 22.sp))
                        }
                        if (!complete && failure == null && downloadState.value.isNotBlank()) {
                            BasicText(downloadState.value, style = TextStyle(color = pal.text2, fontSize = 13.sp))
                        }
                        if (newer && !complete && !ready && rel != null && rel.notes.isNotBlank()) {
                            MarkdownBody(pal, rel.notes.take(480))
                        }
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                    if (!transferring && !complete) {
                        BasicText("更新历史", style = TextStyle(color = pal.text2, fontSize = 14.sp),
                            modifier = Modifier.clip(RoundedCornerShape(10.dp)).clickable {
                                dismiss()
                                onHistory()
                            }.padding(vertical = 12.dp))
                        Spacer(Modifier.weight(1f))
                    }
                    when {
                        downloading.value && !validatingUpdate.value -> Pill(pal, "取消下载", false) { updateDownloadCancelled = true }
                        confirmation != null -> Pill(pal, "继续安装", true) { AppUpdate.requestConfirmation(this@SettingsActivity, confirmation) }
                        transferring || complete || checkingUpdate -> Pill(pal, "关闭", complete, dismiss)
                        ready && rel != null -> Pill(pal, if (permission) "安装更新" else "允许安装", true) { installUpdate(rel) }
                        newer && rel != null && rel.apkUrl != null -> Pill(pal, "下载更新", true) {
                            AppUpdate.clearInstallationResult(this@SettingsActivity)
                            downloadUpdate(rel)
                        }
                        rel == null -> Pill(pal, "重新检查", true) {
                            AppUpdate.clearInstallationResult(this@SettingsActivity)
                            checkUpdate(false)
                        }
                        else -> Pill(pal, "关闭", false, dismiss)
                    }
                }
            }
        }
    }

    @Composable
    private fun UpdateProgress(pal: Pal, status: String, value: String?, progress: Float?) {
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(pal.tile).padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                BasicText(status, Modifier.weight(1f),
                    style = TextStyle(color = pal.ink, fontSize = 14.sp, fontWeight = FontWeight.Medium))
                if (value != null) {
                    BasicText(value, style = TextStyle(color = pal.searchMatch, fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold, fontFeatureSettings = "tnum"))
                }
            }
            val track = Modifier.fillMaxWidth().height(9.dp).clip(CircleShape).background(pal.track)
                .semantics {
                    contentDescription = status
                    progressBarRangeInfo = if (progress == null) ProgressBarRangeInfo.Indeterminate
                        else ProgressBarRangeInfo(progress, 0f..1f)
                }
            if (progress != null) {
                val displayed by animateFloatAsState(progress, animationSpec = tween(180), label = "update progress")
                Canvas(track) {
                    val width = size.width * displayed
                    if (width > 0f) {
                        drawRoundRect(pal.accent, size = Size(width, size.height),
                            cornerRadius = CornerRadius(minOf(width, size.height) / 2f))
                    }
                }
            } else {
                val position by rememberInfiniteTransition(label = "update activity").animateFloat(
                    initialValue = -.32f, targetValue = 1f,
                    animationSpec = infiniteRepeatable(tween(1200, easing = LinearEasing)),
                    label = "progress segment",
                )
                Canvas(track) {
                    drawRoundRect(pal.accent, topLeft = Offset(size.width * position, 0f),
                        size = Size(size.width * .32f, size.height), cornerRadius = CornerRadius(size.height / 2f))
                }
            }
        }
    }

    @Composable
    private fun MarkdownBody(pal:Pal,source:String) {
        AndroidView(factory={ctx->MarkdownTextView(ctx)},update={it.render(source,pal.ink.toArgb(),pal.accent.toArgb())},modifier=Modifier.fillMaxWidth().padding(vertical=8.dp))
    }
    @Composable
    private fun HistoryPage(pal:Pal,onPick:(UpdateChecker.Release)->Unit) {
        val local=UpdateChecker.localRelease(this)
        val releases=listOfNotNull(local)+historyState.value.filter {it.version!=local?.version}
        var query by rememberSaveable {mutableStateOf("")}
        var page by rememberSaveable {mutableIntStateOf(0)}
        val filtered=releases.filter {query.isBlank() || it.tag.contains(query,true) || it.notes.contains(query,true)}
        val pages=maxOf(1,(filtered.size+9)/10)
        val current=page.coerceAtMost(pages-1)
        val scroll=rememberScrollState()
        var lastScrollKey by rememberSaveable {mutableStateOf("$current/$query")}
        LaunchedEffect(current,query) {val next="$current/$query";if(lastScrollKey!=next){scroll.scrollTo(0);lastScrollKey=next}}
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().padding(top=12.dp,bottom=10.dp).clip(RoundedCornerShape(16.dp)).background(pal.card).padding(horizontal=14.dp,vertical=12.dp),verticalAlignment=Alignment.CenterVertically) {
                Image(painterResource(R.drawable.ic_search),null,Modifier.size(19.dp),colorFilter=ColorFilter.tint(pal.text2))
                Spacer(Modifier.width(9.dp))
                BasicTextField(query,{query=it;page=0},singleLine=true,textStyle=TextStyle(color=pal.ink,fontSize=15.sp),cursorBrush=SolidColor(pal.accent),modifier=Modifier.weight(1f),
                    decorationBox={inner->Box {if(query.isEmpty())BasicText("搜索版本或更新内容",style=TextStyle(color=pal.text2,fontSize=15.sp));inner()}})
                Box(Modifier.padding(start = 8.dp).width(32.dp).height(24.dp), contentAlignment = Alignment.Center) {
                    if (query.isNotEmpty()) {
                        Box(Modifier.fillMaxSize().clip(CircleShape)
                            .clickable(role = androidx.compose.ui.semantics.Role.Button) { query = ""; page = 0 }
                            .semantics { contentDescription = "清空更新历史搜索" }, contentAlignment = Alignment.Center) {
                            Image(painterResource(R.drawable.ic_close), null, Modifier.size(18.dp), colorFilter = ColorFilter.tint(pal.text2))
                        }
                    }
                }
            }
            if(historyLoading.value)BasicText("正在刷新…",style=TextStyle(color=pal.text2,fontSize=12.sp),modifier=Modifier.padding(bottom=6.dp))
            else if(historyMessage.value.contains("失败")||historyMessage.value.contains("暂时"))BasicText(historyMessage.value,style=TextStyle(color=pal.text2,fontSize=12.sp),modifier=Modifier.padding(bottom=6.dp))
            Column(Modifier.weight(1f).verticalScroll(scroll),verticalArrangement=Arrangement.spacedBy(10.dp)) {
                filtered.drop(current*10).take(10).forEach {item->Card(pal) {
                    ActionRow(pal,item.tag,item.notes.lineSequence().firstOrNull {it.isNotBlank()}?.trimStart('#',' ')?.take(72),"详情") {onPick(item)}
                }}
                if(filtered.isEmpty())Card(pal){Tip(pal,"没有匹配的版本","试试其他关键词。")}
                Row(Modifier.fillMaxWidth().padding(top=10.dp,bottom=12.dp),horizontalArrangement=Arrangement.SpaceBetween,verticalAlignment=Alignment.CenterVertically) {
                @Composable fun Pager(label:String,enabled:Boolean,click:()->Unit) {
                    Box(Modifier.clip(RoundedCornerShape(50)).background(pal.card).clickable(enabled=enabled,onClick=click).padding(horizontal=18.dp,vertical=11.dp),contentAlignment=Alignment.Center) {
                        BasicText(label,style=TextStyle(color=if(enabled)pal.ink else pal.text2.copy(alpha=.4f),fontSize=14.sp))
                    }
                }
                Pager("上一页",current>0) {page=current-1}
                BasicText("${current+1} / $pages",style=TextStyle(color=pal.text2,fontSize=13.sp))
                Pager("下一页",current<pages-1) {page=current+1}
                }
                Spacer(Modifier.height(92.dp))
            }
        }
    }

    @Composable
    private fun ReleasePage(pal:Pal) {
        val item=selectedRelease.value ?: return
        Card(pal) {
            BasicText(item.tag,style=TextStyle(color=pal.accent,fontSize=22.sp,fontWeight=FontWeight.Bold),modifier=Modifier.padding(top=12.dp))
            MarkdownBody(pal,item.notes.ifBlank {"此版本没有提供更新说明。"})
        }
    }

    @Composable
    private fun VaultPage(pal:Pal,tick:Int) {
        val prefs=Prefs.of(this)
        val enabled=remember(tick) {prefs.getBoolean(Prefs.VAULT,false)}
        val source=remember(tick) {VaultGateway(this).owner()}
        var state by remember {mutableStateOf("")}
        var diagnostic by remember {mutableStateOf("")}
        var more by rememberSaveable {mutableStateOf(false)}
        LaunchedEffect(enabled,source,tick) {
            if(!enabled){state="已关闭";diagnostic=""}
            else {
                state="正在检查连接…"
                val result=kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    val status=VaultGateway(this@SettingsActivity).call("status",Bundle())
                    val message=when(status.getString("status")) {
                        "ok" -> if(status.getBoolean("enabled")) "已连接" else "请在空叶文档中允许键盘读取"
                        "disabled" -> "请在空叶文档中允许键盘读取"
                        "denied" -> "连接未授权"
                        "not_found" -> "未找到空叶文档密码空间"
                        else -> "暂时无法连接"
                    }
                    val detail=when(status.getString("status")) {
                        "denied"->"请确认空叶键盘与空叶文档来自同一发行版本。"
                        "not_found"->"请安装对应版本的空叶文档，并启用密码空间。"
                        else->""
                    }
                    status.clear();message to detail
                }
                state=result.first;diagnostic=result.second
            }
        }
        Card(pal) {
            ToggleRow(pal,"密码填充","从空叶文档读取账号，点击键盘推荐即可填充",enabled) {prefs.edit().putBoolean(Prefs.VAULT,it).apply()}
            Row(Modifier.fillMaxWidth().padding(bottom=12.dp),verticalAlignment=Alignment.CenterVertically) {
                Box(Modifier.size(6.dp).background(if(state=="已连接")pal.ok else pal.text2,CircleShape))
                BasicText(state,style=TextStyle(color=if(state=="已连接")pal.ok else pal.text2,fontSize=13.sp,lineHeight=19.sp),modifier=Modifier.padding(start=8.dp))
            }
        }
        Card(pal) {
            BasicText("开始使用",style=TextStyle(color=pal.ink,fontSize=16.sp,fontWeight=FontWeight.Medium),modifier=Modifier.padding(top=12.dp,bottom=4.dp))
            ActionRow(pal,"1. 开启密码空间","在空叶文档中允许键盘读取","打开") {
                val launch=packageManager.getLaunchIntentForPackage(source)
                if(launch!=null)startActivity(launch) else android.widget.Toast.makeText(this@SettingsActivity,"请先安装对应版本的空叶文档",android.widget.Toast.LENGTH_SHORT).show()
            }
            Line(pal)
            ActionRow(pal,"2. 设置自动填充服务","选择空叶文档，在键盘显示账号候选","去设置") {
                val intent=Intent(Settings.ACTION_REQUEST_SET_AUTOFILL_SERVICE).setData(Uri.parse("package:$source"))
                runCatching {startActivity(intent)}.onFailure {startActivity(Intent(Settings.ACTION_SETTINGS))}
            }
            Line(pal)
            BasicText("3. 登录时点选键盘上的账号",style=TextStyle(color=pal.ink,fontSize=16.sp,fontWeight=FontWeight.Medium),modifier=Modifier.padding(top=14.dp,bottom=4.dp))
            BasicText("找不到账号时点「更多密码」；未解锁时先验证身份。",style=TextStyle(color=pal.text2,fontSize=13.sp,lineHeight=20.sp),modifier=Modifier.padding(bottom=14.dp))
        }
        Card(pal) {
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable {more=!more}.padding(vertical=14.dp),verticalAlignment=Alignment.CenterVertically) {
                BasicText("更多信息",style=TextStyle(color=pal.ink,fontSize=16.sp,fontWeight=FontWeight.Medium),modifier=Modifier.weight(1f))
                ExpandArrow(pal, more)
            }
            if(more) {
                if(diagnostic.isNotEmpty())Tip(pal,"连接排查",diagnostic)
                Tip(pal,"选错账号","点「更多密码」重新选择，或点候选旁的设置管理推荐；可解除 App 关联、清除账号网址后重新关联。")
                Tip(pal,"隐私与支持范围","密码暂存在内存，点击保存才写入空叶文档；关闭提示、锁屏或超时会清除暂存。系统安全键盘和部分应用的自绘键盘可能不支持填充。系统候选需要 Android 11 及以上。")
            }
        }
    }


}

@Composable
private fun Card(pal: Pal, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(pal.card)
            .then(modifier).padding(horizontal = 14.dp, vertical = 4.dp),
    ) { content() }
}

@Composable
private fun Line(pal: Pal, top: Dp = 0.dp, start: Dp = 0.dp) {
    Box(Modifier.padding(top = top, start = start).fillMaxWidth().height(1.dp).background(pal.line))
}

@Composable
private fun NavRow(pal: Pal, it: Item) {
    Row(Modifier.fillMaxWidth().clickable(onClick = it.onClick).heightIn(min=48.dp).padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.size(36.dp).clip(RoundedCornerShape(11.dp)).background(pal.tile),
            contentAlignment = Alignment.Center,
        ) {
            Image(painterResource(it.icon), null, Modifier.size(20.dp), colorFilter = ColorFilter.tint(pal.ink.copy(alpha = 0.82f)))
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            BasicText(it.title, style = TextStyle(color = pal.ink, fontSize = 15.sp))
            Spacer(Modifier.height(1.dp))

        }
        Spacer(Modifier.width(8.dp))
        if (it.value != null) BasicText(it.value, style = TextStyle(color = it.valueColor ?: pal.text2, fontSize = 13.5.sp, fontWeight = FontWeight.Medium))
        if (it.valueColor == null && it.title != "检查更新") Image(painterResource(R.drawable.ic_chevron_right), null, Modifier.size(20.dp), colorFilter = ColorFilter.tint(pal.text2))
    }
}

@Composable
private fun ExpandArrow(pal: Pal, expanded: Boolean, pressed: Boolean = false) {
    val angle by animateFloatAsState(if (expanded) 180f else 0f, tween(160), label = "expandArrow")
    Box(
        Modifier.size(30.dp).background(
            if (expanded || pressed) pal.accent.copy(alpha = .13f) else Color.Transparent,
            CircleShape,
        ),
        contentAlignment = Alignment.Center,
    ) {
        Image(painterResource(R.drawable.ic_menu_expand), null, Modifier.size(19.dp).graphicsLayer { rotationZ = angle },
            colorFilter = ColorFilter.tint(if (expanded || pressed) pal.accent else pal.text2))
    }
}

@Composable
private fun Pill(pal: Pal, text: String, strong: Boolean, onClick: () -> Unit) {
    Box(Modifier.clip(RoundedCornerShape(50)).background(if (strong) pal.accent else pal.track).clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 7.dp)) {
        BasicText(text, style = TextStyle(color = if (strong) pal.onAccent else pal.ink, fontSize = 14.sp, fontWeight = FontWeight.Medium))
    }
}

@Composable
private fun ToggleRow(pal: Pal, title: String, sub: String?, on: Boolean, enabled:Boolean=true, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().alpha(if(enabled)1f else .4f).clickable(enabled=enabled) { onChange(!on) }.heightIn(min=48.dp).padding(vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            BasicText(title, style = TextStyle(color = pal.ink, fontSize = 15.sp))
            if (sub != null) BasicText(sub, style = TextStyle(color = pal.text2, fontSize = 12.sp, lineHeight = 17.sp))
        }
        Spacer(Modifier.width(12.dp))
        Box(
            Modifier.size(width = 46.dp, height = 28.dp).clip(RoundedCornerShape(50)).background(if (on) pal.accent else pal.track),
            contentAlignment = if (on) Alignment.CenterEnd else Alignment.CenterStart,
        ) { Box(Modifier.padding(3.dp).size(22.dp).clip(CircleShape).background(Color.White)) }
    }
}

@Composable
private fun SearchResultRow(pal: Pal, match: String, path: String, query: String, onClick: () -> Unit) {
    val hit = match.indexOf(query, ignoreCase = true)
    val left = (hit - 11).coerceAtLeast(0)
    val right = (hit + query.length + 17).coerceIn(left, match.length)
    val label = (if (left > 0) "…" else "") + match.substring(left, right) + (if (right < match.length) "…" else "")
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable(onClick = onClick).heightIn(min=48.dp).padding(vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 10.dp)) {
            BasicText(highlightSearch(label, query, pal.searchMatch), style = TextStyle(color = pal.ink, fontSize = 15.sp,
                fontWeight = FontWeight.Medium), maxLines = 2, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
            BasicText(path, style = TextStyle(color = pal.text2, fontSize = 12.sp, lineHeight = 17.sp),
                modifier = Modifier.padding(top = 3.dp), maxLines = 2, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
        }
        Image(painterResource(R.drawable.ic_chevron_right), null, Modifier.size(19.dp), colorFilter = ColorFilter.tint(pal.text2))
    }
}

private fun highlightSearch(value: String, query: String, matchColor: Color): AnnotatedString {
    val result=AnnotatedString.Builder(value)
    if(query.isBlank())return result.toAnnotatedString()
    var start=value.indexOf(query,ignoreCase=true)
    while(start>=0) {
        result.addStyle(SpanStyle(color = matchColor, fontWeight = FontWeight.Bold),start,start+query.length)
        start=value.indexOf(query,start+query.length,ignoreCase=true)
    }
    return result.toAnnotatedString()
}

@Composable
private fun ActionRow(pal: Pal, title: String, sub: String?, button: String, onClick: () -> Unit) {
    val navigation=button in listOf("›","⌄","⌃","○","✓")
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable(onClick=onClick).heightIn(min=48.dp).padding(vertical=9.dp),verticalAlignment=Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end=12.dp)) {
            BasicText(title,style=TextStyle(color=pal.ink,fontSize=15.sp,fontWeight=FontWeight.Medium))
            if(sub!=null) BasicText(sub,style=TextStyle(color=pal.text2,fontSize=12.sp,lineHeight=17.sp),modifier=Modifier.padding(top=4.dp))
        }
        if(navigation) BasicText(button,style=TextStyle(color=if(button=="✓")pal.accent else pal.text2,fontSize=24.sp),modifier=Modifier.padding(horizontal=8.dp))
        else Pill(pal,button,true,onClick=onClick)
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
private fun Slider(
    pal: Pal,
    value: Float,
    min: Float,
    max: Float,
    onChange: (Float) -> Unit,
    labels: List<String> = emptyList(),
    format: (Float) -> String = { "${it.roundToInt()}" },
    enabled: Boolean = true,
    centerValue: Float? = null,
    onDone: (Float) -> Unit,
) {
    val currentChange by rememberUpdatedState(onChange)
    val currentDone by rememberUpdatedState(onDone)
    val drawing = remember { SliderDrawing() }
    val stops = (labels.size - 1).coerceAtLeast(0)
    // Keep the normal height at the center without changing either endpoint.
    val center = centerValue?.takeIf { it > min && it < max }
    val fraction = (if (center == null) (value - min) / (max - min)
        else if (value <= center) .5f * (value - min) / (center - min)
        else .5f + .5f * (value - center) / (max - center)).coerceIn(0f, 1f)
    fun valueAt(fraction: Float): Float = if (center == null) min + (max - min) * fraction
        else if (fraction <= .5f) min + (center - min) * fraction * 2f
        else center + (max - center) * (fraction - .5f) * 2f
    val selection = (fraction * stops).roundToInt()
    val valueText = labels.getOrNull(selection) ?: format(value)

    fun snap(next: Float): Float {
        val clamped = next.coerceIn(min, max)
        if (stops == 0) return clamped
        val step = ((clamped - min) / (max - min) * stops).roundToInt()
        return min + (max - min) * step / stops
    }

    val gestures = if (enabled) {
        Modifier.pointerInput(min, max, stops, center) {
            detectTapGestures { point ->
                val next = snap(valueAt(drawing.fractionAt(point.x, size.width.toFloat(), density)))
                currentChange(next)
                currentDone(next)
            }
        }.pointerInput(min, max, stops, center) {
            var draft: Float? = null
            detectHorizontalDragGestures(
                onDragStart = { draft = null },
                onDragEnd = { draft?.let(currentDone) },
                onDragCancel = { draft?.let(currentDone) },
            ) { change, _ ->
                change.consume()
                val next = snap(valueAt(drawing.fractionAt(change.position.x, size.width.toFloat(), density)))
                draft = next
                currentChange(next)
            }
        }
    } else Modifier

    androidx.compose.foundation.Canvas(
        Modifier.fillMaxWidth().height(60.dp).alpha(if (enabled) 1f else .45f).semantics {
            progressBarRangeInfo = androidx.compose.ui.semantics.ProgressBarRangeInfo(value, min..max, (stops - 1).coerceAtLeast(0))
            stateDescription = valueText
            if (enabled) setProgress {
                val next = snap(it)
                currentChange(next)
                currentDone(next)
                true
            } else disabled()
        }.then(gestures)
    ) {
        drawIntoCanvas { canvas ->
            drawing.draw(
                canvas.nativeCanvas, size.width, 22.dp.toPx(), density, fraction,
                pal.track.toArgb(), (if (enabled) pal.accent else pal.text2).toArgb(), pal.text2.toArgb(),
                42.dp.toPx(), 12.sp.toPx(), valueText, labels,
            )
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
