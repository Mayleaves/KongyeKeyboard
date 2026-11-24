package com.kongye.keyboard

import android.Manifest
import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Region
import android.graphics.drawable.GradientDrawable
import android.inputmethodservice.InputMethodService
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.text.InputType
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.WindowInsets
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.ExtractedTextRequest
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputContentInfo
import android.view.inputmethod.InputMethodManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import androidx.core.content.FileProvider
import java.io.File

class KongyeIME : InputMethodService(), KeyboardView.Listener, CandidateBar.Listener,
    SharedPreferences.OnSharedPreferenceChangeListener {

    private var passwordField = false
    private var externalInsertion = false
    private var vaultResults = emptyList<VaultFill.Entry>()
    private val vaultGateway by lazy { VaultGateway(this) }
    private val vault: VaultFill by lazy { VaultFill(
        enabled = { prefs.getBoolean(Prefs.VAULT,false) }, call = vaultGateway::call, unlock = vaultGateway::unlock,
        fill = { value ->
            val connection=ic()
            if (connection==null || sink!=Sink.NONE) false else {
                undoStack.clear();redoStack.clear();resetComposing();assoc=emptyList()
                val extracted=connection.getExtractedText(ExtractedTextRequest().apply {hintMaxChars=4096},0)
                val body=extracted?.text
                val ok = if(body!=null && body.isNotEmpty()) {
                    if(extracted.startOffset!=0 || body.length>4096 || !connection.setSelection(0,body.length)) false
                    else connection.commitText(value,1)
                } else connection.commitText(value,1)
                connection.finishComposingText()
                if(panel==Panel.VAULT) closePanel()
                ok
            }
        }, next = { ic()?.performEditorAction(EditorInfo.IME_ACTION_NEXT) },
        changed = { if(root!=null) renderBar() }, notice = ::message,
        searchUi = { startSink(Sink.VAULT_SEARCH,"",null) },
        appName = { pkg -> runCatching { packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg,0)).toString() }.getOrDefault(pkg) },
        panelUi = { vaultResults = vault.panelEntries(); vaultMatches = true; openPanel(Panel.VAULT) },
    ) }
    /** 面板显示的是本页匹配（true）还是搜索结果（false） */
    private var vaultMatches = false
    /** 系统推送的自动填充候选（安卓 11 起）：盖在工具栏上的一排 */
    private var inlineStrip: FrameLayout? = null
    private var inlineRow: LinearLayout? = null
    private lateinit var vaultSaveBar: VaultSaveBar
    private var inlineGeneration = 0
    private var inlineOwner = ""
    private var inlineChangingField = false
    private val clearUnreplacedInline = Runnable { hideInline() }
    private var associationRows=emptyList<Bundle>()
    private var associationScope=""
    private var associationPackage=""
    private var associationLabel=""
    private var associationMessage=""
    private data class AssociationRemoval(val id:String,val pkg:String,val scope:String,val website:Boolean,val owner:String,val until:Long)
    private var pendingAssociationRemoval:AssociationRemoval?=null
    private var associationRemoving=""
    private var associationRemoveMode=""
    private var associationNotice=""
    private var associationEpoch=0
    private var associationBusy=false

    private fun loadAssociations() {
        associationPackage=currentInputEditorInfo?.packageName.orEmpty()
        associationMessage="正在读取推荐…"
        associationRows=emptyList();associationRemoving="";pendingAssociationRemoval=null
        openPanel(Panel.ASSOCIATIONS)
        associationCall("association.list",Bundle()) {result ->
            @Suppress("DEPRECATION")
            associationRows=result.getParcelableArrayList<Bundle>("entries").orEmpty()
            if(associationScope!=result.getString("scope").orEmpty())associationNotice=""
            associationScope=result.getString("scope").orEmpty()
            associationLabel=result.getString("label").orEmpty()
            associationRemoveMode=result.getString("removeMode").orEmpty()
            associationMessage=if(result.getString("status")=="ok") {
                if(associationRows.isEmpty()) "此页面没有关联账号" else if(associationRemoveMode=="clear_url") "清除网址后，此账号不再按该网站推荐；账号和密码保留。" else "解除后，此账号不再按该 App 推荐；账号和密码保留。"
            } else result.getString("message") ?: when(result.getString("status")) {
                "disabled" -> "请在键盘和空叶文档中开启密码填充"
                "denied" -> "请确认空叶文档已允许键盘访问"
                else -> "请更新空叶文档后使用推荐管理"
            }
            buildPanel()
        }
    }

    private fun associationCall(method:String,args:Bundle,done:(Bundle)->Unit) {
        val pkg=associationPackage
        val revision=++associationEpoch
        args.putString("package",pkg)
        associationBusy=true
        Thread({
            val result=vaultGateway.call(method,args)
            main.post {
                if(revision==associationEpoch && currentInputEditorInfo?.packageName==pkg && panel==Panel.ASSOCIATIONS) {
                    associationBusy=false;done(result)
                }
            }
        },"KongyeAssociation").start()
    }

    private fun removeAssociation(removal:AssociationRemoval,allowUnlock:Boolean=true) {
        if(associationBusy)return
        associationRemoving=removal.id
        associationMessage=if(removal.website) "正在清除网址…" else "正在解除关联…"
        associationCall("association.remove",Bundle().apply {putString("id",removal.id);putString("scope",removal.scope)}) {result ->
            associationRemoving=""
            when(result.getString("status")) {
                "ok","not_found" -> {
                    pendingAssociationRemoval=null;hideInline()
                    associationRows=associationRows.filterNot {it.getString("id")==removal.id}
                    associationNotice=if(removal.website) "网址已清除，账号和密码仍保留。" else "App 关联已解除，账号和密码仍保留。"
                    associationMessage=if(associationRows.isEmpty()) "此页面没有关联账号" else "点击对应账号的按钮即可解除推荐。"
                }
                "locked" -> {
                    @Suppress("DEPRECATION") val intent=result.getParcelable<android.app.PendingIntent>("unlock")
                    if(allowUnlock && intent!=null) {
                        pendingAssociationRemoval=removal
                        associationMessage="请验证身份，返回后会继续处理。"
                        if(!vaultGateway.unlock(intent)) {pendingAssociationRemoval=null;associationMessage="无法打开验证，请重试。"}
                    } else {pendingAssociationRemoval=null;associationMessage="未完成解锁，关联保持不变。"}
                }
                else -> {pendingAssociationRemoval=null;associationMessage=result.getString("message") ?: "操作失败，请重试。"}
            }
            buildPanel()
        }
        buildPanel()
    }

    private fun associationPanel():View {
        val d=resources.displayMetrics.density
        val column=LinearLayout(this).apply {orientation=LinearLayout.VERTICAL;setPadding((14*d).toInt(),(4*d).toInt(),(14*d).toInt(),(12*d).toInt())}
        fun text(value:String,size:Float=13f)=android.widget.TextView(this).apply {
            text=value;textSize=size;setTextColor(skin.candFg)
        }
        fun button(value:String,danger:Boolean=false,action:()->Unit)=text(value).apply {
            gravity=Gravity.CENTER;minHeight=(40*d).toInt()
            setPadding((14*d).toInt(),(6*d).toInt(),(14*d).toInt(),(6*d).toInt())
            setTextColor(if(danger) {if(skin.dark) android.graphics.Color.rgb(255,153,160) else android.graphics.Color.rgb(180,44,55)} else skin.candFg)
            background=roundBg(if(danger) mix(skin.keyBg,android.graphics.Color.rgb(230,57,70),.12f) else skin.modBg,12*d)
            isEnabled=!associationBusy;alpha=if(isEnabled)1f else .6f
            setOnClickListener {action()}
        }
        val head=LinearLayout(this).apply {gravity=Gravity.CENTER_VERTICAL}
        head.addView(text(associationLabel.ifEmpty {"账号推荐"},15f).apply {typeface=android.graphics.Typeface.DEFAULT_BOLD;maxLines=1;ellipsize=android.text.TextUtils.TruncateAt.END},lp(0,WRAP_CONTENT,1f))
        head.addView(button("返回键盘") {closePanel()},lp(WRAP_CONTENT,WRAP_CONTENT).apply {marginEnd=(8*d).toInt()})
        head.addView(button("刷新") {associationNotice="";loadAssociations()},lp(WRAP_CONTENT,WRAP_CONTENT))
        column.addView(head,lp(MATCH_PARENT,WRAP_CONTENT))
        column.addView(text(associationMessage).apply {setPadding(0,(6*d).toInt(),0,(8*d).toInt())})
        if(associationNotice.isNotEmpty()) column.addView(text(associationNotice).apply {
            setPadding((12*d).toInt(),(10*d).toInt(),(12*d).toInt(),(10*d).toInt());background=roundBg(skin.keyBg,12*d)
        })
        if(!prefs.getBoolean(Prefs.VAULT,false)) column.addView(button("允许键盘管理账号推荐") {
            prefs.edit().putBoolean(Prefs.VAULT,true).apply();loadAssociations()
        },lp(MATCH_PARENT,WRAP_CONTENT))
        if(associationRemoveMode.isEmpty() && !associationBusy) {
            column.addView(text("清除网址需要配套的空叶文档新版本。"))
            column.addView(button("打开空叶文档") {
                val pkg=prefs.getString(Prefs.VAULT_PACKAGE,"com.kongye.docs") ?: "com.kongye.docs"
                packageManager.getLaunchIntentForPackage(pkg)?.let {startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))}
            },lp(MATCH_PARENT,WRAP_CONTENT).apply {topMargin=(8*d).toInt()})
        }
        for(row in associationRows) {
            val id=row.getString("id").orEmpty()
            val website=associationRemoveMode=="clear_url"
            val card=LinearLayout(this).apply {
                orientation=LinearLayout.VERTICAL;setPadding((14*d).toInt(),(10*d).toInt(),(14*d).toInt(),(10*d).toInt())
                background=roundBg(skin.keyBg,14*d)
            }
            card.addView(text(row.getString("name").orEmpty(),15f).apply {typeface=android.graphics.Typeface.DEFAULT_BOLD})
            card.addView(text(row.getString("account").orEmpty()).apply {setPadding(0,(4*d).toInt(),0,(8*d).toInt())})
            val label=if(associationBusy && associationRemoving==id) {
                if(website) "正在清除…" else "正在解除…"
            } else if(website) "清除网址" else "解除关联"
            card.addView(button(label,true) {
                if(associationBusy || associationRemoveMode.isEmpty())return@button
                removeAssociation(AssociationRemoval(id,associationPackage,associationScope,website,vaultGateway.owner(),SystemClock.uptimeMillis()+30000))
            }.apply {isEnabled=associationRemoveMode.isNotEmpty() && !associationBusy;alpha=if(isEnabled)1f else .5f},lp(MATCH_PARENT,WRAP_CONTENT))
            column.addView(card,lp(MATCH_PARENT,WRAP_CONTENT).apply {topMargin=(8*d).toInt()})
        }
        return scroll(column)
    }
    private val vaultLock = object:android.content.BroadcastReceiver() {
        override fun onReceive(context:android.content.Context?,intent:Intent?) {
            vault.reset()
            if(sink==Sink.REMOTE_EDIT) {endSink();closePanel()}
            remoteHistory.reset();EditorDeletion.cancel()
        }
    }
    private fun browser(pkg:String):Boolean = pkg in setOf("com.android.chrome","com.microsoft.emmx","org.mozilla.firefox","com.heytap.browser","com.android.browser","com.mi.globalbrowser","com.sec.android.app.sbrowser") ||
        packageManager.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_APP_BROWSER),0).any { it.activityInfo.packageName==pkg }

    private enum class Layer { LETTERS, NUM }
    private enum class Panel { NONE, EMOJI, CLIP, EDIT, SKIN, CANDS, MORE, ADJUST, ARRANGE, LAYOUT, SYMBOL, HAND, HAND_CANDS, VOICE, TOKENS, VAULT, ASSOCIATIONS }
    /** 打字的去向：编辑框，或者剪贴板搜索、编辑、新建分类、翻译的输入条 */
    private enum class Sink { NONE, SEARCH, EDIT, NEWCAT, CLIP_ADD, TRANSLATE, FAVORITE, VAULT_SEARCH, REMOTE_EDIT }

    /** 分几次选词时，已经选好但还没上屏的部分 */
    private class CandidateSnapshot(
        val keys: List<String>, val candidates: List<Cand>, val displayRaw: String, val display: String,
        val abbr: Boolean, val fuzzy: Set<String>, val expanded: Boolean,
    )
    private class Pick(val w: String, val syl: List<String>, val keys: List<String>, val isRaw: Boolean,
        var before: CandidateSnapshot? = null)

    private lateinit var prefs: SharedPreferences
    private val main = Handler(Looper.getMainLooper())

    private var root: Root? = null
    private lateinit var col: LinearLayout
    private lateinit var floatBar: FloatBar
    private lateinit var sinkStrip: SinkStrip
    private lateinit var bar: CandidateBar
    private lateinit var barHost: FrameLayout
    private lateinit var languageBar: CandidateBar
    private lateinit var keyboard: KeyboardView
    private lateinit var panelHost: PanelHost
    private lateinit var bottomStrip: BottomStrip
    private lateinit var candidatePreview: android.widget.TextView
    private var candidatePreviewMeasureCap = 0
    private lateinit var pinyinLens: PinyinLens
    private var longCandidate: String? = null
    private lateinit var tab: PinyinTab
    private lateinit var clipSearch: FrameLayout
    private var voiceBoard: VoiceBoard? = null
    private var editView: EditPanelView? = null
    private var handView: HandwriteView? = null

    private var skin = Skins.KONGYE
    private var zh = true
    private var appComposing=false
    private var appComposingText=""
    private var voicePolishJob:VoicePolish.Job?=null
    private var voicePolishOriginal:String?=null
    private var voicePolishApproved:String?=null
    private var voiceStarting=false
    private var continuousVoiceOwner:String?=null
    private var voiceCollapsed=false
    private var continuousStrip: FrameLayout? = null
    private var continuousStatusButton: android.widget.TextView? = null
    private var continuousExpandButton: android.widget.ImageView? = null
    private var continuousStopButton: android.widget.ImageView? = null
    private var lastHomophone=""

    /** 输入的键：每个元素是这个键的字母集合，' 是分词符 */
    private val seq = ArrayList<String>()
    private val picks = ArrayList<Pick>()
    private var cands: List<Cand> = emptyList()
    private var assoc: List<String> = emptyList()
    private var shift = 0
    private var englishCapsLocked = false
    private var lastShiftTap = 0L
    private var layer = Layer.LETTERS
    private var panel = Panel.NONE
    private var learnField = true
    private var forceEn = false
    private var selecting = false
    private var anchor = -1
    private var floating = false
    private var lastOrientation = Configuration.ORIENTATION_UNDEFINED

    private var sink = Sink.NONE
    private val sinkBuf = StringBuilder()
    private var sinkClip: Clip? = null
    private var sinkCategory = ""
    private val remoteHistory=RemoteInputHistory()
    private fun remoteEditor()=EditorDeletion.remote(currentInputEditorInfo?.packageName)
    private var clipTab = "全部"
    private var clipQuery = ""
    private var clipExpanded: Clip? = null
    private var clipPicking: Clip? = null
    private var tokens: List<String> = emptyList()
    private var tokenSelection = BooleanArray(0)
    private var tokensFromBar = false
    private var tokenDraft: VoiceDraft? = null
    private var emojiTab = 0
    private var symCat = 0
    /** 拼音条上的编辑光标：seq 里的下标，-1 表示在最后 */
    private var pyCaret = -1
    /** 键盘调节中的临时设置 */
    private var adj: Adjust? = null
    private val adjustmentDrafts=mutableMapOf<Boolean,Adjust>()
    private var adjustOverlay: View? = null
    private var candidateFilter = 0
    /** 翻译：正在选目标语言 */
    private var langPicking = false
    private var handCands: List<String> = emptyList()
    private var inkRevision = 0
    private var handwritingDraft: VoiceDraft? = null
    private var handwritingPreview = ""

    private fun previewHandwriting(word: String) {
        if (word.isEmpty()) {
            cancelHandwritingPreview()
            return
        }
        if (sink != Sink.NONE) {
            handwritingPreview = word
            sinkStrip.previewInput(word)
        } else if (!passwordField && !remoteEditor()) {
            val draft = handwritingDraft ?: ic()?.let { VoiceDraft(it) } ?: return
            handwritingDraft = draft
            if (draft.preview(word, underline = true)) handwritingPreview = word
        }
    }

    private fun cancelHandwritingPreview(keepSelection: Pair<Int, Int>? = null) {
        inkRevision++
        val draft = handwritingDraft
        handwritingDraft = null
        val hadPreview = handwritingPreview.isNotEmpty()
        handwritingPreview = ""
        if (keepSelection == null) draft?.cancel()
        else draft?.cancelKeepingSelection(keepSelection.first, keepSelection.second)
        if (hadPreview && sink != Sink.NONE && ::sinkStrip.isInitialized) {
            sinkStrip.cancelInputPreview()
        }
    }
    private var confirmAt = 0L
    private var confirmWhat = ""

    private var transResult = ""
    private var transStatus = ""
    private var transDirection = ""
    private val transTask = Runnable { runLiveTranslation() }

    private var speech: SpeechRecognizer? = null
    private var offlineSpeech: OfflineVoice.Session? = null
    private var afterVoiceStop: (() -> Unit)? = null
    private var systemVoiceGeneration = 0
    private var listening = false
    private var voiceCapturing = false
    private var voiceWantsListening = false
    private var voiceStatus = "已暂停"
    private var voicePartial = ""
    /** 这次语音已经确定的文字（连续说多句会接起来） */
    private val voiceText = StringBuilder()
    private var voiceDraft: VoiceDraft? = null
    private var voiceBefore: Snap? = null
    private var voiceStart: Int? = null
    /** 语音的去向：false 进语音面板，true 进翻译输入条 */
    private var voiceToSink = false

    private var clipboard: ClipboardManager? = null
    private var ignoreClipUntil = 0L
    private var lastImageUri = ""
    private var lastCapturedClip:Triple<Long,String?,String?>?=null
    private var clipImportGeneration=0
    private val clipImporter=java.util.concurrent.Executors.newSingleThreadExecutor {task->Thread(task,"clipboard-import").apply {isDaemon=true}}
    /** 刚复制的文字：在候选栏弹出快捷粘贴/分词/关闭条 */
    private var pendingClipText: String? = null
    private var pendingSmsCode:SmsCodeClipboard.Code?=null
    private var dismissedSmsId=""
    private var pendingClipAt = 0L
    private var dismissedClipText = ""
    private val clipListener = ClipboardManager.OnPrimaryClipChangedListener { captureClip() }
    private val releaseTools = Runnable {
        if (panel != Panel.VOICE && !listening) OfflineVoice.releaseCache()
        if (panel != Panel.HAND && panel != Panel.HAND_CANDS) OpenHandwriting.release()
        GlideDecoder.release()
    }
    private fun releaseToolsLater() {
        main.removeCallbacks(releaseTools); main.postDelayed(releaseTools, 30_000)
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) releaseTools.run()
    }

    override fun onCreate() {
        super.onCreate()
        V3Prefs.migrate(this)
        prefs = Prefs.of(this)
        if (Translator.TARGETS.none { it.first == prefs.getString(Prefs.TRANS_TO,"auto") })
            prefs.edit().putString(Prefs.TRANS_TO,"auto").apply()
        prefs.registerOnSharedPreferenceChangeListener(this)
        zh = prefs.getBoolean(Prefs.ZH, true)
        lastOrientation = resources.configuration.orientation
        floating = V3Prefs.keyboardForm(this) == KeyboardForm.FLOATING
        Lexicon.fuzzyRules = Prefs.fuzzy(this)
        ClipStore.load(this)
        Thread {
            Lexicon.load(applicationContext)
            PinyinEngine.initialize(applicationContext)
            main.post { if (seq.isNotEmpty()) refreshComposing() }
        }.start()
        clipboard = getSystemService(ClipboardManager::class.java)
        SmsCodeClipboard.schedule(this)
        clipboard?.addPrimaryClipChangedListener(clipListener)
        if(android.os.Build.VERSION.SDK_INT>=33) registerReceiver(vaultLock,android.content.IntentFilter(Intent.ACTION_SCREEN_OFF),RECEIVER_NOT_EXPORTED)
        else @Suppress("DEPRECATION") registerReceiver(vaultLock,android.content.IntentFilter(Intent.ACTION_SCREEN_OFF))
    }

    override fun onDestroy() {
        clipImportGeneration++;clipImporter.shutdown()
        main.removeCallbacks(releaseTools)
        hideInline()
        candidateRevision++;candidateWorker.shutdownNow()
        stopListening(keepPartial=false)
        cancelVoiceDraft()
        cancelHandwritingPreview()
        if (panel == Panel.TOKENS) cancelTokenPreview()
        OfflineVoice.releaseCache()
        OpenHandwriting.release()
        Translator.cancel()
        clipboard?.removePrimaryClipChangedListener(clipListener)
        runCatching { unregisterReceiver(vaultLock) }; vault.destroy()
        prefs.unregisterOnSharedPreferenceChangeListener(this)
        speech?.destroy()
        Lexicon.save()
        super.onDestroy()
    }

    override fun onEvaluateFullscreenMode() = false

    override fun dump(fd: java.io.FileDescriptor?, out: java.io.PrintWriter?, args: Array<out String>?) {
        out?.println("VaultFill: ${vault.diagnosticState()} passwordField=$passwordField sink=$sink panel=$panel provider=${vaultGateway.owner()} failure=${vaultGateway.lastFailure}")
        out?.println("RemoteInput: active=${remoteEditor()} knownCharacters=${remoteHistory.knownCharacters()}")
        out?.println("PinyinEngine: ready=${PinyinEngine.ready} version=${PinyinEngine.version} failure=${PinyinEngine.failure}")
        out?.println("KeyboardUi: sink=$sink panel=$panel languagePicker=$langPicking listening=$listening voiceStatus=$voiceStatus")
    }

    /**
     * 输入法视图占满整个输入法窗口（透明）：键盘贴底，拼音条可以伸到键盘上面，悬浮时键盘能拖到任何地方。
     * 只有键盘和拼音条那块能点，其余地方的触摸交给后面的应用（见 onComputeInsets）。
     */
    private inner class Root(ctx: Context) : FrameLayout(ctx) {
        // InputMethodService adds exclusions to the decor after computing its insets.
        // Clear that default before the frame is drawn so Android owns edge Back.
        private val edgeBack = android.view.ViewTreeObserver.OnPreDrawListener {
            if (Build.VERSION.SDK_INT >= 29) {
                val decor = rootView
                if (decor.systemGestureExclusionRects.isNotEmpty()) {
                    decor.systemGestureExclusionRects = emptyList()
                }
            }
            true
        }

        override fun onAttachedToWindow() {
            super.onAttachedToWindow()
            viewTreeObserver.addOnPreDrawListener(edgeBack)
        }

        override fun onDetachedFromWindow() {
            if (viewTreeObserver.isAlive) viewTreeObserver.removeOnPreDrawListener(edgeBack)
            super.onDetachedFromWindow()
        }

        init {setWillNotDraw(false)}
        override fun onDraw(canvas:Canvas) {
            super.onDraw(canvas)
            if(!floating && !voiceCollapsed && V3Prefs.enabled(this@KongyeIME,V3Prefs.FILL_GAPS) && ::col.isInitialized) {
                val paint=Paint().apply {color=skin.kbBg}
                canvas.drawRect(0f,col.top.toFloat(),width.toFloat(),height.toFloat(),paint)
            }
        }
        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val h = if (MeasureSpec.getMode(heightMeasureSpec) != MeasureSpec.UNSPECIFIED && MeasureSpec.getSize(heightMeasureSpec) > 0)
                MeasureSpec.getSize(heightMeasureSpec) else resources.displayMetrics.heightPixels
            super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(h, MeasureSpec.EXACTLY))
            // 液态玻璃：窗口只要键盘（和正在显示的拼音条）那么高，系统模糊只作用在键盘后面
            if (blurOn() && ::col.isInitialized) {
                val spelling=if(::tab.isInitialized && tab.visibility==View.VISIBLE) tab.measuredHeight else 0
                val candidate=if(::candidatePreview.isInitialized && candidatePreview.visibility==View.VISIBLE) candidatePreview.measuredHeight else 0
                val aboveKeys=(col.measuredHeight-keyboard.measuredHeight-bottomStrip.measuredHeight).coerceAtLeast(0)
                val popup=(keyboard.popupAboveKeyboard-aboveKeys).coerceAtLeast(0)
                setMeasuredDimension(measuredWidth,col.measuredHeight+maxOf(spelling,candidate,popup))
            }
        }
    }

    /** 悬浮键盘顶上的拖动条：拖动移动键盘，右边的 × 收起键盘 */
    private inner class FloatBar(ctx: Context) : View(ctx) {
        private val d get() = resources.displayMetrics.density * keyboard.heightScale
        private val icons = Icons(ctx)
        private val p = Paint(Paint.ANTI_ALIAS_FLAG)
        private var sx = 0f
        private var sy = 0f
        private var ox = 0f
        private var oy = 0f
        private var moved = false

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) =
            setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), ((if(prefs.getInt(V3Prefs.PINYIN_POSITION,0)==0)40 else 26) * d).toInt())

        override fun onDraw(c: Canvas) {
            p.color = withAlpha(skin.barFg, 150)
            c.drawRoundRect(RectF(width / 2f - 24 * d, height / 2f - 2.5f*d, width / 2f + 24 * d, height / 2f + 2.5f*d), 3 * d, 3 * d, p)
            icons.scale=keyboard.heightScale
            icons.draw(c, R.drawable.ic_close, width - 20 * d, height / 2f, 16f, skin.barFg)
        }

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(e: MotionEvent): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { sx = e.rawX; sy = e.rawY; ox = col.translationX; oy = col.translationY; moved = false }
                MotionEvent.ACTION_MOVE -> {
                    if (kotlin.math.abs(e.rawX - sx) + kotlin.math.abs(e.rawY - sy) > 8 * d) moved = true
                    if (moved) placeFloat(ox + e.rawX - sx, oy + e.rawY - sy)
                }
                MotionEvent.ACTION_UP -> {
                    if (!moved && e.x > width - 44 * d) requestHideSelf(0)
                    else prefs.edit().putFloat(FloatingKeyboard.xKey(this@KongyeIME), col.translationX).putFloat(FloatingKeyboard.yKey(this@KongyeIME), col.translationY).apply()
                }
            }
            return true
        }
    }

    override fun onCreateInputView(): View {
        if (panel == Panel.TOKENS) cancelTokenPreview()
        floating = V3Prefs.keyboardForm(this) == KeyboardForm.FLOATING
        val r = Root(this)
        val voicePanelWasOpen = panel == Panel.VOICE
        continuousStrip = null
        continuousStatusButton = null
        continuousExpandButton = null
        continuousStopButton = null
        col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        floatBar = FloatBar(this)
        sinkStrip = SinkStrip(this, onOk = { sinkConfirm() }, onCancel = { sinkCancel() }).apply { visibility = View.GONE }
        bar = CandidateBar(this).also { it.listener = this;it.onVisibleWords={words->requestVisibleGloss(words)} }
        barHost=CandidateHost(this)
        languageBar=CandidateBar(this).apply {
            visibility=View.INVISIBLE;alpha=0f
            listener=object:CandidateBar.Listener {
                override fun onCandidate(index:Int) {}
                override fun onAssoc(index:Int) {if(langPicking) this@KongyeIME.onAssoc(index)}
                override fun onTool(id:String) {}
                override fun onExpand() {}
                override fun onDismissAssoc() {setLanguagePicker(false)}
            }
        }
        barHost.addView(bar,FrameLayout.LayoutParams(MATCH_PARENT,WRAP_CONTENT))
        barHost.addView(languageBar,FrameLayout.LayoutParams(MATCH_PARENT,WRAP_CONTENT))
        inlineRow=LinearLayout(this).apply { orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER_VERTICAL }
        inlineStrip=object : FrameLayout(this) {
            override fun dispatchTouchEvent(event: MotionEvent): Boolean {
                return inlineChangingField || super.dispatchTouchEvent(event)
            }
        }.apply {
            visibility=View.GONE
            val scroll=android.widget.HorizontalScrollView(this@KongyeIME).apply {
                isHorizontalScrollBarEnabled=false
                addView(inlineRow,FrameLayout.LayoutParams(WRAP_CONTENT,MATCH_PARENT))
            }
            addView(scroll,FrameLayout.LayoutParams(MATCH_PARENT,MATCH_PARENT).apply {
                rightMargin=(64*resources.displayMetrics.density).toInt()
            })
            addView(IconButton(this@KongyeIME,R.drawable.ic_settings,skin.barFg,19f) {loadAssociations()}.apply {
                contentDescription="管理此页面的账号推荐"
            },FrameLayout.LayoutParams((32*resources.displayMetrics.density).toInt(),MATCH_PARENT,Gravity.END).apply {rightMargin=(32*resources.displayMetrics.density).toInt()})
            addView(android.widget.TextView(this@KongyeIME).apply {
                text="×";textSize=18f;gravity=Gravity.CENTER
                contentDescription="收起自动填充候选"
                setOnClickListener {hideInline();renderBar()}
            },FrameLayout.LayoutParams((32*resources.displayMetrics.density).toInt(),MATCH_PARENT,Gravity.END))
        }
        barHost.addView(inlineStrip,FrameLayout.LayoutParams(MATCH_PARENT,MATCH_PARENT))
        vaultSaveBar=VaultSaveBar(this)
        barHost.addView(vaultSaveBar,FrameLayout.LayoutParams(MATCH_PARENT,MATCH_PARENT))
        keyboard = KeyboardView(this).also { it.listener = this }
        panelHost = PanelHost(this).apply {
            visibility = View.GONE
            heightOf = {
                (if(landscapeEdit()) (168*resources.displayMetrics.density).toInt() else keyboard.desiredHeight())+
                    if(panel in listOf(Panel.CANDS,Panel.HAND_CANDS))bar.measuredHeight else 0
            }
        }
        // 左下角：收起键盘
        bottomStrip = BottomStrip(this, onKeyboard = { requestHideSelf(0) }, onClip = { if (panel == Panel.CLIP) closePanel() else openClipboard() })
        val area = FrameLayout(this)
        area.addView(keyboard, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        area.addView(panelHost, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        clipSearch = FrameLayout(this).apply { visibility = View.GONE }
        col.addView(floatBar, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        col.addView(sinkStrip, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        col.addView(clipSearch, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        col.addView(barHost, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        col.addView(area, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        col.addView(bottomStrip, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        r.addView(col, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT, Gravity.BOTTOM))
        tab = PinyinTab(this) { onTabCaret(it) }.apply { visibility = View.GONE }
        r.addView(tab, FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.TOP or Gravity.START))
        candidatePreview=android.widget.TextView(this).apply {
            textSize=17f;setPadding((8*resources.displayMetrics.density).toInt(),(5*resources.displayMetrics.density).toInt(),(8*resources.displayMetrics.density).toInt(),(5*resources.displayMetrics.density).toInt())
            maxLines=32;movementMethod=android.text.method.ScrollingMovementMethod()
            importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_YES
            visibility=View.GONE
            contentDescription="完整候选，点击输入"
            setOnClickListener {
                val target=currentCandidatePreview() ?: return@setOnClickListener
                if(target.word!=text.toString())return@setOnClickListener
                val page=target.page
                if(page!=null)page.selectCandidate(target.index)
                else if(resolvedKeys==seq)onCandidate(target.index)
            }
        }
        r.addView(candidatePreview,FrameLayout.LayoutParams((resources.displayMetrics.widthPixels*.48f).toInt(),WRAP_CONTENT))
        candidatePreview.addOnLayoutChangeListener {_,_,_,_,_,_,_,_,_->positionCandidatePreview()}
        bar.onLongCandidate={value ->
            if(longCandidate!=value) {
                longCandidate=value;positionCandidatePreview()
            }
        }
        pinyinLens=PinyinLens(this).apply {visibility=View.GONE;importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO}
        r.addView(pinyinLens,FrameLayout.LayoutParams(WRAP_CONTENT,WRAP_CONTENT))
        tab.onExpanded={r.post {positionTab();positionCandidatePreview()} }
        tab.onPreview={value ->
            pinyinLens.preview=value
            pinyinLens.visibility=if(value==null) View.GONE else View.VISIBLE
            if(value!=null) {
                val size=86*resources.displayMetrics.density
                pinyinLens.x=(tab.x+value.x-size/2f).coerceIn(0f,(r.width-size).coerceAtLeast(0f))
                pinyinLens.y=(tab.y+value.y-size-12*resources.displayMetrics.density).coerceAtLeast(0f)
                pinyinLens.bringToFront()
            }
        }
        // 拼音条出现、消失时，液态玻璃下的窗口高度要跟着变
        tab.addOnLayoutChangeListener { _, _, top, _, bottom, _, oldTop, _, oldBottom -> if (blurOn() && (bottom - top) != (oldBottom - oldTop)) r.requestLayout() }
        col.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> if(floating)placeFloat(col.translationX,col.translationY); positionTab(); positionAdjustment() }
        tab.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> positionTab() }
        r.setOnApplyWindowInsetsListener { _, insets ->
            // 全面屏手势且隐藏导航条时导航栏边距是 0，但底部仍有系统上滑手势区（tappableElement）
            bottomStrip.inset = if (Build.VERSION.SDK_INT >= 30) maxOf(
                insets.getInsets(WindowInsets.Type.navigationBars()).bottom,
                insets.getInsets(WindowInsets.Type.tappableElement()).bottom,
            ) else @Suppress("DEPRECATION") insets.systemWindowInsetBottom
            insets
        }
        root = r
        panel = if (voicePanelWasOpen) Panel.VOICE else Panel.NONE
        applySkin()
        applyFloat()
        if (voicePanelWasOpen) openPanelNow(Panel.VOICE)
        updateContinuousStrip()
        return r
    }

    override fun onComputeInsets(outInsets: Insets) {
        super.onComputeInsets(outInsets)
        val r = root ?: return
        if (r.height <= 0 || !::col.isInitialized) return
        val loc = IntArray(2)
        col.getLocationInWindow(loc)
        // 停靠时应用只让出键盘那么高；悬浮时不让（键盘浮在应用上面）
        val top = if (floating || voiceCollapsed) r.height else loc[1]
        outInsets.contentTopInsets = top
        outInsets.visibleTopInsets = top
        outInsets.touchableInsets = Insets.TOUCHABLE_INSETS_REGION
        outInsets.touchableRegion.set(loc[0], loc[1], loc[0] + col.width, loc[1] + col.height)
        if (tab.visibility == View.VISIBLE && tab.width > 0) {
            val t = IntArray(2)
            tab.getLocationInWindow(t)
            outInsets.touchableRegion.op(t[0], t[1], t[0] + tab.width, t[1] + tab.height, Region.Op.UNION)
        }
        if(::candidatePreview.isInitialized && candidatePreview.visibility==View.VISIBLE) {
            val p=IntArray(2);candidatePreview.getLocationInWindow(p)
            outInsets.touchableRegion.op(p[0],p[1],p[0]+candidatePreview.width,p[1]+candidatePreview.height,Region.Op.UNION)
        }
    }

    /** 拼音条贴在键盘左上角的上面 */
    private fun positionTab() {
        if (!::tab.isInitialized) return
        val r=root ?: return
        tab.x=(col.x+col.paddingLeft+8*resources.displayMetrics.density).coerceIn(0f,(r.width-tab.width).toFloat().coerceAtLeast(0f))
        val unit=resources.displayMetrics.density*keyboard.heightScale
        tab.widthLimit=if(floating) (if(tab.expanded)col.width-52*unit else col.width/2f-40*unit).toInt().coerceAtLeast(1) else 0
        tab.y = if(floating) col.y+((floatBar.height-tab.height)/2f).coerceAtLeast(0f) else (col.y-tab.height+1).coerceAtLeast(0f)
        positionCandidatePreview()
    }

    private data class PreviewTarget(val word:String,val index:Int,val page:ExpandedCandidates? = null)

    private fun currentCandidatePreview():PreviewTarget? {
        if(sink!=Sink.NONE || tab.visibility==View.VISIBLE && tab.expanded)return null
        val toolbarWord=longCandidate?.takeIf {seq.isNotEmpty() && it==cands.firstOrNull()?.w}
        return when(panel) {
            Panel.NONE -> toolbarWord?.let {PreviewTarget(it,0)}
            Panel.CANDS,Panel.HAND_CANDS -> {
                if(panel==Panel.CANDS && (seq.isEmpty() || resolvedKeys!=seq))return null
                val page=panelHost.getChildAt(0) as? ExpandedCandidates ?: return null
                page.previewCandidate(toolbarWord)?.let {(index,word)->PreviewTarget(word,index,page)}
            }
            else -> null
        }
    }

    private fun positionCandidatePreview() {
        if(!::candidatePreview.isInitialized || !::tab.isInitialized) return
        val target=currentCandidatePreview()
        candidatePreview.visibility=if(target!=null) View.VISIBLE else View.GONE
        if(target==null)return
        val r=root ?: return
        val density=resources.displayMetrics.density
        val maxHeight=minOf((320*density).toInt(),resources.displayMetrics.heightPixels-col.height-(48*density).toInt())
            .coerceAtLeast((60*density).toInt())
        if(candidatePreview.maxHeight!=maxHeight)candidatePreview.maxHeight=maxHeight
        val cap=(col.width*.48f).toInt().coerceAtLeast((96*density).toInt())
        val lp=candidatePreview.layoutParams as FrameLayout.LayoutParams
        val wordChanged=candidatePreview.text.toString()!=target.word
        if(wordChanged || candidatePreviewMeasureCap!=cap) {
            val word=target.word
            if(wordChanged) {
                candidatePreview.text=word
                candidatePreview.scrollTo(0,0)
            }
            val padding=candidatePreview.paddingLeft+candidatePreview.paddingRight
            val layout=android.text.StaticLayout.Builder.obtain(word,0,word.length,candidatePreview.paint,
                (cap-padding).coerceAtLeast(1)).setIncludePad(false).build()
            val longest=(0 until layout.lineCount).maxOf {layout.getLineWidth(it)}
            val desired=(kotlin.math.ceil(longest).toInt()+padding+1).coerceIn(1,cap)
            candidatePreviewMeasureCap=cap
            if(lp.width!=desired) {lp.width=desired;candidatePreview.layoutParams=lp}
        }
        val desired=lp.width
        candidatePreview.x=(col.x+col.width-desired).coerceIn(0f,(r.width-desired).toFloat().coerceAtLeast(0f))
        candidatePreview.y=(col.y-candidatePreview.height).coerceAtLeast(0f)
    }

    private var inputPackage: String? = null
    private var inputKind = 0
    private var inputFieldId = 0
    private var restoringHistoryUntil = 0L
    private var editMoveAt = 0L
    override fun onStartInputView(info: EditorInfo, restarting: Boolean) {
        EditorDeletion.cancel()
        if (!restarting) {
            if (inlineOwner == info.packageName) deferInlineClear() else hideInline()
        }
        super.onStartInputView(info, restarting)
        SmsCodeClipboard.expire(this)
        if (panel != Panel.ADJUST) {
            val nextFloating = V3Prefs.keyboardForm(this) == KeyboardForm.FLOATING
            if (floating != nextFloating) {
                floating = nextFloating
                applyFloat()
            }
        }
        if(V3Prefs.glide(this,zh)) candidateWorker.execute {GlideDecoder.warm(applicationContext,zh)}
        if (restarting && panel == Panel.ADJUST && adj != null) {
            applyFloat(); applySkin(rebuildPanel=false)
            root?.post { adjustOverlay?.bringToFront(); positionAdjustment() }
            return
        }
        val sameEditor=info.packageName==inputPackage && info.inputType==inputKind && info.fieldId==inputFieldId
        if (!sameEditor || !restarting) bottomStrip.cancelCursorGesture()
        if (panel == Panel.TOKENS) {
            if (sameEditor && restarting && !VaultFields.sensitive(info)) {
                ic()?.let { tokenDraft?.rebind(it) }
                renderBar()
                return
            }
            closePanel(render = false, animate = false)
        }
        if (sameEditor && restarting && !VaultFields.sensitive(info) &&
            panel in listOf(Panel.HAND, Panel.HAND_CANDS)) {
            ic()?.let { handwritingDraft?.rebind(it) }
            renderBar()
            return
        }
        cancelHandwritingPreview()
        if (sameEditor && restarting && !VaultFields.sensitive(info) && (panel == Panel.VOICE || voiceCollapsed)) {
            ic()?.let { voiceDraft?.rebind(it) }
            updateVoice()
            return
        }
        if (panel == Panel.VOICE || voiceCollapsed) {
            stopListening(keepPartial=false)
            cancelVoiceDraft()
        }
        if(sameEditor && restarting && panel==Panel.ASSOCIATIONS && associationBusy) {
            renderBar();return
        }
        if(!sameEditor || !restarting) remoteHistory.reset()
        if(sameEditor && SystemClock.uptimeMillis()-editMoveAt<1000 && (keyboard.cursorMode || restarting && panel==Panel.NONE)) return
        val keepEdit=panel==Panel.EDIT && sameEditor && (restarting || SystemClock.uptimeMillis()-editMoveAt<1000)
        inputPackage=info.packageName;inputKind=info.inputType;inputFieldId=info.fieldId
        if(keepEdit) {
            resetComposing();assoc=emptyList();renderBar();return
        }
        resetComposing()
        assoc = emptyList()
        selecting = false
        if (sink != Sink.NONE) endSink()
        if (!restarting && !(sameEditor && SystemClock.uptimeMillis()<restoringHistoryUntil)) { undoStack.clear(); redoStack.clear(); lastTypingAt=0L; lastTypingGroup="" }
        passwordField=VaultFields.sensitive(info)
        if(passwordField) { undoStack.clear();redoStack.clear();pendingClipText=null;pendingSmsCode=null;window?.window?.addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE) }
        else window?.window?.clearFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
        val cls = info.inputType and InputType.TYPE_MASK_CLASS
        val variation = info.inputType and InputType.TYPE_MASK_VARIATION
        val password = VaultFields.passwordType(info)
        val latin = cls == InputType.TYPE_CLASS_TEXT && variation in setOf(
            InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS, InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS, InputType.TYPE_TEXT_VARIATION_URI,
        )
        learnField = !passwordField && (info.imeOptions and EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING) == 0
        forceEn = passwordField || latin
        zh = if (forceEn) false else prefs.getBoolean(Prefs.ZH, true)
        layer = when (cls) {
            InputType.TYPE_CLASS_NUMBER, InputType.TYPE_CLASS_PHONE, InputType.TYPE_CLASS_DATETIME -> Layer.NUM
            else -> Layer.LETTERS
        }
        shift = if(!zh && englishCapsLocked && V3Prefs.enabled(this,V3Prefs.CAPS_LOCK,false))2 else 0
        lastShiftTap = 0L
        if (panel != Panel.NONE) closePanel(render = false)
        checkPrimaryClipForSuggestion()
        // Changing the focused form field should not animate the entire keyboard.
        applySkin(animateKeys=false)
        updateAutoCaps()
        vault.focus(info,browser(info.packageName.orEmpty()), ic()?.getTextBeforeCursor(1,0)?.isEmpty()==true && ic()?.getTextAfterCursor(1,0)?.isEmpty()==true)
        if(continuousVoiceOwner!=null) {
            if(continuousVoiceOwner==info.packageName && !passwordField && V3Prefs.enabled(this,V3Prefs.DRIVE,false)) {
                main.post {if(!listening) {openVoice();collapseVoice();toggleListening()}}
            } else {continuousVoiceOwner=null;voiceCollapsed=false;updateContinuousStrip()}
        }
        val removal=pendingAssociationRemoval
        pendingAssociationRemoval=null
        if(removal!=null && removal.pkg==info.packageName && removal.owner==vaultGateway.owner() && SystemClock.uptimeMillis()<removal.until) {
            associationPackage=removal.pkg;associationScope=removal.scope;associationBusy=false
            openPanelNow(Panel.ASSOCIATIONS)
            removeAssociation(removal,allowUnlock=false)
        }
    }

    /** 打开键盘时检查系统剪贴板是否有新复制的文字（即使复制发生在键盘收起期间也能识别） */
    private fun checkPrimaryClipForSuggestion() {
        if(passwordField) return
        val cm = clipboard ?: return
        val clip = runCatching { cm.primaryClip }.getOrNull() ?: run {pendingSmsCode=null;if(root!=null)renderBar();return}
        if(SmsCodeClipboard.ephemeral(clip)){captureSmsCode(clip);return}
        pendingSmsCode=null
        val ts = clip.description?.timestamp ?: 0L
        val recentCopy = ts > 0L && (System.currentTimeMillis() - ts) in 0..120_000L
        val item = clip.getItemAt(0) ?: return
        if (item.uri != null) return
        val text = runCatching { item.coerceToText(this)?.toString() }.getOrNull().orEmpty()
        if (text.isBlank()) return
        ClipStore.add(text)
        if ((recentCopy || pendingClipText == text) && text != dismissedClipText) {
            pendingClipText = text
            pendingClipAt = SystemClock.uptimeMillis()
        }
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        cancelHandwritingPreview()
        if (panel == Panel.TOKENS) cancelTokenPreview()
        if (::bottomStrip.isInitialized) bottomStrip.cancelCursorGesture()
        if(statsAllowed())TypingStats.of(this).endSession()
        EditorDeletion.cancel()
        if (finishingInput) deferInlineClear() else hideInline()
        vault.hide()
        stopListening()
        if (!finishingInput && (panel == Panel.VOICE || voiceCollapsed)) {
            updateVoice()
            return
        }
        cancelVoiceDraft()
        super.onFinishInputView(finishingInput)
        resetComposing()
        if (::keyboard.isInitialized) {
            if (sink != Sink.NONE) endSink()
            if (panel != Panel.NONE) closePanel(render = false)
        }
        Lexicon.save()
        releaseToolsLater()
    }

    // —————————— 系统自动填充候选（需求文档 v2 第 9.2 节，安卓 11 起） ——————————

    /** 让系统把自动填充候选交给键盘，在工具栏里显示。 */
    @android.annotation.SuppressLint("NewApi")
    override fun onCreateInlineSuggestionsRequest(uiExtras: Bundle): android.view.inputmethod.InlineSuggestionsRequest? {
        if (Build.VERSION.SDK_INT < 30) return null
        return runCatching {
            val d = resources.displayMetrics.density
            val style = androidx.autofill.inline.UiVersions.newStylesBuilder().addStyle(
                androidx.autofill.inline.v1.InlineSuggestionUi.newStyleBuilder()
                    .setTitleStyle(androidx.autofill.inline.common.TextViewStyle.Builder().setTextColor(skin.keyFg).setTextSize(14f).build())
                    .setSubtitleStyle(androidx.autofill.inline.common.TextViewStyle.Builder().setTextColor(skin.barFg).setTextSize(12f).build())
                    .build()
            ).build()
            val spec = android.widget.inline.InlinePresentationSpec.Builder(
                android.util.Size((80 * d).toInt(), (32 * d).toInt()), android.util.Size((300 * d).toInt(), (40 * d).toInt()),
            ).setStyle(style).build()
            android.view.inputmethod.InlineSuggestionsRequest.Builder(listOf(spec)).setMaxSuggestionCount(6).build()
        }.getOrNull()
    }

    @android.annotation.SuppressLint("NewApi")
    override fun onInlineSuggestionsResponse(response: android.view.inputmethod.InlineSuggestionsResponse): Boolean {
        val list = response.inlineSuggestions
        val pkg = currentInputEditorInfo?.packageName.orEmpty()
        val generation = ++inlineGeneration
        if (list.isEmpty() || inlineRow == null) {
            deferInlineClear()
            return true
        }
        val d = resources.displayMetrics.density
        val views = arrayOfNulls<View>(list.size)
        var remaining = list.size
        list.forEachIndexed { i, suggestion ->
            runCatching {
                suggestion.inflate(this, android.util.Size(WRAP_CONTENT, (36 * d).toInt()), mainExecutor) { view ->
                    views[i] = view
                    if (--remaining == 0 && generation == inlineGeneration) showInline(views.filterNotNull(), pkg)
                }
            }.onFailure { if (--remaining == 0 && generation == inlineGeneration) showInline(views.filterNotNull(), pkg) }
        }
        return true
    }

    private fun showInline(views: List<View>, pkg: String) {
        val row = inlineRow ?: return
        if (views.isEmpty()) {
            deferInlineClear()
            return
        }
        main.removeCallbacks(clearUnreplacedInline)
        inlineChangingField = false
        inlineOwner = pkg
        inlineStrip?.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_AUTO
        row.removeAllViews()
        val d = resources.displayMetrics.density
        views.forEach { v ->
            // InlineSuggestion supplies the measured surface size. Replacing
            // its width with WRAP_CONTENT collapses InlineContentView to zero.
            val size=v.layoutParams
            row.addView(v, LinearLayout.LayoutParams(size?.width?.takeIf { it>0 } ?: WRAP_CONTENT,
                size?.height?.takeIf { it>0 } ?: (36*d).toInt()).apply { marginStart=(6*d).toInt() })
        }

        vault.inlineShown(pkg, true)
        renderBar()
    }

    private fun deferInlineClear() {
        inlineGeneration++
        if (inlineOwner.isEmpty() || inlineStrip?.visibility != View.VISIBLE || inlineRow?.childCount == 0) {
            hideInline()
            return
        }
        if (inlineChangingField) return
        // Keep the previous frame while Android renders the new field's chips.
        // During this brief handoff, the old chips cannot be tapped or focused.
        inlineChangingField = true
        inlineStrip?.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        main.postDelayed(clearUnreplacedInline, 450)
    }

    private fun hideInline() {
        inlineGeneration++
        main.removeCallbacks(clearUnreplacedInline)
        inlineChangingField = false
        val owner = inlineOwner
        inlineOwner = ""
        inlineRow?.removeAllViews()
        inlineStrip?.visibility = View.GONE
        inlineStrip?.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_AUTO
        if(owner.isNotEmpty()) vault.inlineShown(owner, false)
    }

    override fun onStartInput(info: EditorInfo, restarting: Boolean) {
        if (inlineOwner.isNotEmpty() && inlineOwner != info.packageName) hideInline()
        super.onStartInput(info, restarting)
    }

    override fun onWindowHidden() {
        if (::bottomStrip.isInitialized) bottomStrip.cancelCursorGesture()
        hideInline()
        super.onWindowHidden()
    }

    override fun onFinishInput() {
        remoteHistory.reset()
        if (panel == Panel.TOKENS) closePanel(render = false, animate = false)
        if (panel == Panel.VOICE || voiceCollapsed) {
            stopListening()
            cancelVoiceDraft()
            if (panel == Panel.VOICE) closePanel(render = false, animate = false)
        }
        super.onFinishInput()
        resetComposing()
        if(!candidateWorker.isShutdown) candidateWorker.submit { PinyinEngine.endInput() }
    }

    override fun onUpdateSelection(oldSelStart: Int, oldSelEnd: Int, newSelStart: Int, newSelEnd: Int, candidatesStart: Int, candidatesEnd: Int) {
        super.onUpdateSelection(oldSelStart, oldSelEnd, newSelStart, newSelEnd, candidatesStart, candidatesEnd)
        if(passwordField && sink==Sink.NONE) vault.selection(newSelStart,newSelEnd)
        tokenDraft?.let { draft ->
            if (newSelStart != oldSelStart || newSelEnd != oldSelEnd) {
                val composingCaret = candidatesStart >= 0 && newSelStart == candidatesEnd && newSelStart == newSelEnd
                if (!composingCaret && !draft.selectionAtPreviewEnd(newSelStart, newSelEnd)) {
                    tokenDraft = null
                    draft.cancelKeepingSelection(newSelStart, newSelEnd)
                    closePanel(animate = false)
                }
            }
        }
        handwritingDraft?.let { draft ->
            if (newSelStart != oldSelStart || newSelEnd != oldSelEnd) {
                val composingCaret = candidatesStart >= 0 && newSelStart == candidatesEnd && newSelStart == newSelEnd
                if (!composingCaret && !draft.selectionAtPreviewEnd(newSelStart, newSelEnd)) {
                    cancelHandwritingPreview(newSelStart to newSelEnd)
                    handCands = emptyList()
                    handView?.clear()
                    renderBar()
                }
            }
        }
        if(deferFollowingActions && (newSelStart!=oldSelStart || newSelEnd!=oldSelEnd)) {
            main.removeCallbacks(replayFollowingActions)
            main.post(replayFollowingActions)
        }
        if (sink != Sink.NONE || (seq.isEmpty() && picks.isEmpty())) return
        // 拼音只显示在拼音条上，编辑框里没有组字文字；打拼音时光标被挪走了就放弃这次输入
        if(appComposing && candidatesStart>=0 && newSelStart==candidatesEnd && newSelStart==newSelEnd)return
        if (newSelStart != oldSelStart || newSelEnd != oldSelEnd) { resetComposing(); renderBar() }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        val orientationChanged = lastOrientation != newConfig.orientation
        lastOrientation = newConfig.orientation
        super.onConfigurationChanged(newConfig)
        if (orientationChanged && panel == Panel.ADJUST) closePanel(render = false, animate = false)
        if (panel != Panel.ADJUST) floating = V3Prefs.keyboardForm(this) == KeyboardForm.FLOATING
        if (root != null) { applyFloat(); applySkin() }
    }

    override fun onSharedPreferenceChanged(p: SharedPreferences?, key: String?) {
        if(key==Prefs.LEARN){Lexicon.learningEnabled=flag(Prefs.LEARN);if(seq.isNotEmpty())refreshComposing();return}
        if(key in setOf(V3Prefs.CORRECTION_ENABLED,V3Prefs.CORRECT_NEAR,V3Prefs.CORRECT_SWAP,V3Prefs.CORRECT_EXTRA,V3Prefs.CORRECT_MISSING,V3Prefs.FUZZY_ENABLED)) {
            SpellingAssist.configure(this);Lexicon.fuzzyRules=Prefs.fuzzy(this)
            if(seq.isNotEmpty())refreshComposing()
            return
        }
        if(key==V3Prefs.CAPS_LOCK && !V3Prefs.enabled(this,V3Prefs.CAPS_LOCK,false)) {
            englishCapsLocked=false
            if(!zh && shift==2)shift=0
        }
        if(key==V3Prefs.STATS_ENABLED){TypingStats.of(this).endSession();return}
        if (key == V3Prefs.RECOGNIZE_ENGLISH || key == V3Prefs.MIXED) {
            if (seq.isNotEmpty()) refreshComposing()
            return
        }
        if(key==Prefs.GLIDE && prefs.getBoolean(Prefs.GLIDE,false)) candidateWorker.execute {GlideDecoder.warm(applicationContext,zh)}
        if(key==Prefs.VAULT || key==Prefs.VAULT_PACKAGE) {
            vault.reset()
            if(prefs.getBoolean(Prefs.VAULT,false)) currentInputEditorInfo?.let { vault.focus(it,browser(it.packageName.orEmpty())) }
            return
        }
        if(key==Prefs.VOICE_LANG) { if (panel==Panel.VOICE) OfflineVoice.prewarm(applicationContext,prefs.getInt(Prefs.VOICE_LANG,0)); return }
        if (key == Prefs.FUZZY) { Lexicon.fuzzyRules = Prefs.fuzzy(this); if (seq.isNotEmpty()) refreshComposing(); return }
        if(key==V3Prefs.PINYIN_POSITION && appComposing) {ic()?.setComposingText("",1);ic()?.finishComposingText();appComposing=false;appComposingText=""}
        if (root == null || key in setOf(Prefs.ZH, Prefs.RECENT_EMOJI, Prefs.RECENT_SYM, Prefs.COUNT_TODAY, Prefs.COUNT_DATE, Prefs.FLOAT_X, Prefs.FLOAT_Y,
                Prefs.TRANS_TO, Prefs.VOICE_LANG, Prefs.VOICE_OFFLINE, Prefs.UPDATE_CHECKED)) return
        if(key==Prefs.FAV_EMOJI) {
            (panelHost.getChildAt(0) as? EmojiPanel)?.updateFavs(favEmoji());return
        }
        if(panel==Panel.ADJUST && key in setOf(V3Prefs.SPLIT_GAP,V3Prefs.SPLIT_SIZE)) {applySkin(rebuildPanel=false);return}
        if (key == Prefs.TOOLS || key == Prefs.MENU) {
            renderBar()
            // 排列视图自己持有最新顺序，保留当前实例和滚动位置。
            if (panel == Panel.MORE) buildPanel()
            return
        }
        if(key==V3Prefs.ONE_HAND) {applyFloat();applySkin();return}
        if (key in setOf(Prefs.FLOAT, Prefs.SPLIT_LAYOUT, V3Prefs.ORIENTATION_LAYOUT, V3Prefs.LANDSCAPE_FLOAT,
                V3Prefs.LANDSCAPE_SPLIT, Prefs.FLOAT_WIDTH, FloatingKeyboard.LAND_WIDTH)) {
            if (panel != Panel.ADJUST) floating = V3Prefs.keyboardForm(this) == KeyboardForm.FLOATING
            applyFloat()
            applySkin(rebuildPanel = panel == Panel.MORE)
            return
        }
        // 调节键盘时改震动、按键音不用重建面板
        if (panel == Panel.ADJUST && key in setOf(Prefs.VIB_STRENGTH, Prefs.SOUND)) return
        applySkin()
    }

    // —————————— 外观 ——————————

    private fun flag(key: String) = prefs.getBoolean(key, true)

    /** 液态玻璃时底色从上到下多一点主题色，像磨砂玻璃 */
    private fun skinBg(): GradientDrawable =
        if (skin.bg2 != 0) GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(skin.kbBg, skin.bg2))
        else GradientDrawable().apply { setColor(skin.kbBg) }

    private fun applySkin(rebuildPanel: Boolean = true,animateKeys: Boolean = true) {
        val a = adj.takeIf { panel == Panel.ADJUST }
        skin = Prefs.theme(this).pick(Prefs.night(this)).styled(1f, prefs.getBoolean(Prefs.GLASS, false))
        val r = root ?: return
        bar.skin = skin
        bar.reserveSpelling = zh && prefs.getInt(V3Prefs.PINYIN_POSITION,0)==1
        languageBar.skin=skin
        languageBar.setBackgroundColor(skin.kbBg or android.graphics.Color.BLACK)
        sinkStrip.skin = skin
        bottomStrip.skin = skin
        tab.skin = skin
        (inlineStrip?.getChildAt(2) as? android.widget.TextView)?.setTextColor(skin.barFg)
        candidatePreview.setTextColor(skin.candFg)
        candidatePreview.background=GradientDrawable().apply {
            setColor(withAlpha(if(skin.dark) skin.popBg else android.graphics.Color.WHITE,222))
            val radius=11*resources.displayMetrics.density
            cornerRadii=floatArrayOf(radius,radius,radius,radius,0f,0f,0f,0f)
            setStroke((resources.displayMetrics.density*.7f).toInt().coerceAtLeast(1),withAlpha(skin.barFg,80))
        }
        pinyinLens.skin=skin
        floatBar.invalidate()
        val userScale=a?.scale ?: if(floating)FloatingKeyboard.height(this) else Prefs.heightScale(this)
        val config=resources.configuration
        val scale=userScale*ResponsiveSize.keyboardScale(config.screenWidthDp.toFloat(),config.screenHeightDp.toFloat())
        keyboard.heightScale = scale
        keyboard.visibility=when {panel==Panel.NONE || panel==Panel.ADJUST -> View.VISIBLE;landscapeEdit()->View.GONE;else->View.INVISIBLE}
        panelHost.requestLayout()
        bar.uiScale=scale;bottomStrip.uiScale=scale;tab.uiScale=scale
        languageBar.uiScale=scale
        col.alpha=a?.opacity ?: Prefs.opacity(this)
        tab.alpha=col.alpha
        col.setLayerType(if(panel==Panel.ADJUST || col.alpha<.99f) View.LAYER_TYPE_HARDWARE else View.LAYER_TYPE_NONE,null)
        floatBar.requestLayout()
        keyboard.clearCompositionOnHold = V3Prefs.enabled(this,V3Prefs.COMPOSE_HOLD_CLEAR) && (seq.isNotEmpty() || picks.isNotEmpty())
        keyboard.swipeEnabled = flag(Prefs.SWIPE)
        keyboard.letterLong=prefs.getInt(V3Prefs.LETTER_LONG,0)
        keyboard.showGlideTrace=V3Prefs.enabled(this,V3Prefs.GLIDE_TRACE)
        keyboard.splitGap=a?.splitGap ?: FloatingKeyboard.intValue(this,if(floating)FloatingKeyboard.GAP else V3Prefs.SPLIT_GAP,72).toFloat()
        keyboard.splitSize=a?.splitSize ?: FloatingKeyboard.floatValue(this,if(floating)FloatingKeyboard.SIZE else V3Prefs.SPLIT_SIZE,1f)
        keyboard.fillGaps=V3Prefs.enabled(this,V3Prefs.FILL_GAPS)
        bottomStrip.cursorEnabled=panel==Panel.NONE && V3Prefs.enabled(this,V3Prefs.BOTTOM_CURSOR,false)
        bottomStrip.onCursor={dx,dy->onCursorMove(dx,dy)}
        bottomStrip.onCursorModeChange=keyboard::setExternalCursorMode
        SpellingAssist.configure(this)
        CandidateGloss.prepare(this)
        keyboard.spaceLong = prefs.getInt(Prefs.SPACE_LONG, 0)
        keyboard.enterIcon = enterIcon(currentInputEditorInfo)
        applyFloatBackground(r)
        applyPads()
        applyBlur()
        render(animateKeys)
        val skinPanel=panelHost.getChildAt(0) as? SkinPanel
        if(panel==Panel.SKIN && skinPanel!=null) {
            skinPanel.updateAppearance(skin,Prefs.theme(this).id,prefs.getInt(Prefs.MODE,0),prefs.getBoolean(Prefs.GLASS,false))
        } else if (panel != Panel.NONE && rebuildPanel) buildPanel()
        if(voiceCollapsed) updateContinuousStrip()
    }

    /** 输入法窗口包含伸出的拼音标签；系统矩形模糊会模糊标签旁的应用内容。 */
    private fun blurOn(): Boolean = false

    /** 液态玻璃：让系统把键盘后面的内容模糊后透出来 */
    private fun applyBlur() {
        if (Build.VERSION.SDK_INT < 31) return
        val w = window?.window ?: return
        val on = blurOn()
        if (on) w.setFormat(android.graphics.PixelFormat.TRANSLUCENT)
        w.setBackgroundBlurRadius(if (on) (26 * resources.displayMetrics.density).toInt() else 0)
    }

    /** 键盘左右收进和底部留白（悬浮时不用） */
    private fun applyPads() {
        val dd = resources.displayMetrics.density
        val a = adj.takeIf { panel == Panel.ADJUST }
        val l = if (floating) 0 else a?.padL ?: FloatingKeyboard.intValue(this,Prefs.PAD_L,0)
        val rr = if (floating) 0 else a?.padR ?: FloatingKeyboard.intValue(this,Prefs.PAD_R,0)
        val b = if (floating) 0 else a?.padB ?: FloatingKeyboard.intValue(this,Prefs.PAD_B,0)
        col.setPadding((l * dd).toInt(), 0, (rr * dd).toInt(), 0)
        bottomStrip.extra = (b * dd).toInt()
    }

    private fun applyFloatBackground(r: Root) {
        r.background = null
        if (floating) {
            col.background = skinBg().apply { cornerRadius = 18 * resources.displayMetrics.density }
            col.elevation = 14 * resources.displayMetrics.density
            col.clipToOutline = true
        } else {
            col.background = if(V3Prefs.enabled(this,V3Prefs.FILL_GAPS))skinBg() else null
            col.elevation = 0f
            col.clipToOutline = false
        }
        val overlayElevation=if(floating)col.elevation+2*resources.displayMetrics.density else 0f
        if(::tab.isInitialized)tab.elevation=overlayElevation
        if(::candidatePreview.isInitialized)candidatePreview.elevation=overlayElevation
        if(::pinyinLens.isInitialized)pinyinLens.elevation=overlayElevation
    }

    private fun applyFloat() {
        val r = root ?: return
        val dm = resources.displayMetrics
        val lpCol = col.layoutParams as FrameLayout.LayoutParams
        val oldWidth=lpCol.width;val oldGravity=lpCol.gravity
        val a = adj.takeIf { panel == Panel.ADJUST }
        if (floating) {
            lpCol.width = (dm.widthPixels * (a?.floatWidth ?: FloatingKeyboard.width(this)).coerceIn(FloatingKeyboard.widthRange(dm.widthPixels.toFloat(),dm.heightPixels.toFloat()))).toInt()
            lpCol.gravity = Gravity.TOP or Gravity.START
            floatBar.visibility = View.VISIBLE
            bottomStrip.visibility = View.GONE
            if(oldWidth!=lpCol.width || oldGravity!=lpCol.gravity) col.layoutParams = lpCol
            val stored=FloatingKeyboard.newAdjustment(this,true)
            placeFloat(a?.floatX ?: stored.floatX, a?.floatY ?: stored.floatY)
            if (a != null) { a.floatX=col.translationX; a.floatY=col.translationY }
        } else {
            val hand=activeOneHand()
            lpCol.width = if(hand==0) MATCH_PARENT else (dm.widthPixels*.8f).toInt()
            lpCol.gravity = Gravity.BOTTOM or if(hand==1)Gravity.START else if(hand==2)Gravity.END else Gravity.CENTER_HORIZONTAL
            floatBar.visibility = View.GONE
            bottomStrip.visibility = View.VISIBLE
            col.translationX = 0f
            col.translationY = 0f
            if(oldWidth!=lpCol.width || oldGravity!=lpCol.gravity) col.layoutParams = lpCol
        }
        applyFloatBackground(r)
        if(oldWidth!=lpCol.width || oldGravity!=lpCol.gravity) r.requestLayout()
    }

    private fun placeFloat(x: Float, y: Float) {
        val dm = resources.displayMetrics
        val w = col.layoutParams.width.takeIf { it > 0 } ?: col.width
        val h = if (col.height > 0) col.height else (dm.heightPixels * 0.4f).toInt()
        val availableW=root?.width?.takeIf {it>0} ?: dm.widthPixels
        val availableH=root?.height?.takeIf {it>0} ?: dm.heightPixels
        col.translationX = x.coerceIn(0f, (availableW - w).toFloat().coerceAtLeast(0f))
        col.translationY = y.coerceIn(0f, (availableH - h).toFloat().coerceAtLeast(0f))
        positionTab()
        positionAdjustment()
    }

    private fun setFloating(on: Boolean) {
        floating = on
        val edit=prefs.edit()
        V3Prefs.saveFloating(this, on, edit)
        edit.apply()
        if (panel != Panel.NONE) closePanel(render = false)
        applyFloat()
        applySkin()
    }

    private fun activeOneHand(): Int {
        val split = adj.takeIf { panel == Panel.ADJUST }?.split ?: V3Prefs.dockedSplit(this)
        return if (floating || split) 0 else prefs.getInt(V3Prefs.ONE_HAND, 0)
    }

    private fun useSplit(): Boolean {
        val metrics = resources.displayMetrics
        val draft = adj.takeIf { panel == Panel.ADJUST }
        var widthDp = metrics.widthPixels / metrics.density
        if (!floating) {
            if (activeOneHand() != 0) widthDp *= .8f
            widthDp -= (draft?.padL ?: FloatingKeyboard.intValue(this,Prefs.PAD_L,0)) + (draft?.padR ?: FloatingKeyboard.intValue(this,Prefs.PAD_R,0))
        }
        if (layer != Layer.LETTERS || !KeyboardForm.supportsSplit(Prefs.layout(this), widthDp, floating)) return false
        return draft?.split ?: if (floating) FloatingKeyboard.split(this) else V3Prefs.dockedSplit(this)
    }

    private fun rowsForLayer(): List<Row> = when {
        layer == Layer.NUM -> Layouts.numpad(Prefs.numKeys(this))
        !zh -> Layouts.letters(false,this)
        else -> when (Prefs.layout(this)) {
            "14" -> if(useSplit()) Layouts.splitFourteen(this) else Layouts.fourteen(this)
            "9" -> Layouts.nine()
            else -> Layouts.letters(true,this)
        }
    }

    private fun sideForLayer(): List<String>? = when {
        layer == Layer.NUM -> Prefs.numSide(this)
        zh && Prefs.layout(this) == "9" -> Layouts.NINE_SIDE
        else -> null
    }

    private var renderedKeys = ""
    private fun render(animateKeys:Boolean=true) {
        if (root == null) return
        val keys = "$layer/$zh/${Prefs.layout(this)}"
        val changed = renderedKeys.isNotEmpty() && renderedKeys != keys
        renderedKeys = keys
        keyboard.zh = zh
        keyboard.shiftState = shift
        keyboard.splitMode = splitMode()
        keyboard.splitLayout = useSplit()
        keyboard.setup(skin, rowsForLayer(), sideForLayer())
        if (animateKeys && changed && keyboard.visibility == View.VISIBLE) NativeMotion.pulse(keyboard)
        renderBar()
    }

    private fun splitMode() = zh && layer == Layer.LETTERS && seq.isNotEmpty()

    /** 打开面板时工具栏里高亮哪个工具 */
    private fun activeTool(): String? {
        if (sink == Sink.TRANSLATE) return "translate"
        val id = when (panel) {
            Panel.EMOJI -> "emoji"; Panel.CLIP, Panel.TOKENS -> "clip"; Panel.EDIT -> "edit"; Panel.SKIN -> "skin"
            Panel.HAND, Panel.HAND_CANDS -> "handwrite"; Panel.VOICE -> "voice"
            Panel.ADJUST -> "adjust"; Panel.ARRANGE -> "arrange"; Panel.LAYOUT -> "layout"; Panel.MORE -> "more"
            else -> null
        } ?: return null
        return if (id == "more" || id in Prefs.tools(this)) id else "more"
    }

    private fun requestVisibleGloss(words:List<String>) {
        if(passwordField || prefs.getInt(V3Prefs.GLOSS,2)==2)return
        val revision=candidateRevision
        val shown=if(prefs.getInt(V3Prefs.GLOSS,2)==0) words.filter {it.codePointCount(0,it.length)<=6} else words
        CandidateGloss.request(shown,zh) {
            if(revision==candidateRevision) {
                renderBar()
                updateExpandedCandidates()
            }
        }
    }

    private fun updateExpandedCandidates() {
        if(panel!=Panel.CANDS)return
        (panelHost.getChildAt(0) as? ExpandedCandidates)?.submit(
            cands.map {it.w},candItems().map {it.py},cands.map {it.py},
            if(zh)emptyList() else EnglishInput.literalOptions(seq.joinToString("")),
        )
    }

    private fun candItems(): List<CandItem> {
        val mode=prefs.getInt(V3Prefs.READINGS,if(prefs.getBoolean(Prefs.SHOW_PINYIN,false))1 else 2)
        val gloss=prefs.getInt(V3Prefs.GLOSS,2)
        if(gloss!=2 && !passwordField) requestVisibleGloss(cands.take(4).map {it.w})
        val typed = if (mode == 0 && zh && !glideSpelling && Prefs.layout(this) == "26")
            resolvedKeys.joinToString("").filter { it in 'a'..'z' } else ""
        val matches = mutableMapOf<Pair<Int, String>, Boolean>()
        return cands.map {c->
            val reading = c.py?.takeIf { pronunciation ->
                mode == 1 || mode == 0 && (c.correction ||
                    !c.abbr && typed.isNotEmpty() && c.n in 1..typed.length && !matches.getOrPut(c.n to pronunciation) {
                        SpellingAssist.matchesTypedReading(typed.take(c.n), pronunciation)
                    })
            }
            val translate=gloss==1 || gloss==0 && c.w.codePointCount(0,c.w.length)<=6
            val translation=if(!translate || !CandidateGloss.available(zh))null else CandidateGloss.get(c.w,zh) ?: "…"
            CandItem(c.w,listOfNotNull(reading,translation).joinToString(" · ").ifEmpty {null},
                if(translation==null && V3Prefs.enabled(this,V3Prefs.FEEDBACK))c.mask else null)
        }
    }

    private fun renderBar() {
        if (root == null) return
        barHost.visibility=if(voiceCollapsed || panel in listOf(Panel.CANDS,Panel.HAND_CANDS))View.GONE else View.VISIBLE
        val rowWidth=if(landscapeEdit()) minOf(resources.displayMetrics.widthPixels,(744*resources.displayMetrics.density).toInt()) else MATCH_PARENT
        for(view in listOf(barHost,sinkStrip)) {
            val params=view.layoutParams as LinearLayout.LayoutParams
            if(params.width!=rowWidth) {params.width=rowWidth;params.gravity=Gravity.CENTER_HORIZONTAL;view.layoutParams=params}
        }
        val vaultActions = if(panel==Panel.NONE && sink==Sink.NONE) vault.actions() else emptyList()
        val showSave = vaultSaveBar.bind(skin,vaultActions)
        bar.visibility = if (showSave) View.INVISIBLE else View.VISIBLE
        inlineStrip?.let { strip ->
            val show=!showSave && (inlineRow?.childCount ?: 0)>0 && panel==Panel.NONE && sink==Sink.NONE && seq.isEmpty()
            strip.visibility=if(show) View.VISIBLE else View.GONE
            if(show) strip.setBackgroundColor(skin.kbBg)
        }
        keyboard.clearCompositionOnHold = V3Prefs.enabled(this,V3Prefs.COMPOSE_HOLD_CLEAR) && (seq.isNotEmpty() || picks.isNotEmpty())
        keyboard.glideEnabled = V3Prefs.glide(this,zh) && !passwordField && layer==Layer.LETTERS && (!zh || Prefs.layout(this)=="26")
        val sm = splitMode()
        if (keyboard.splitMode != sm) { keyboard.splitMode = sm; keyboard.invalidate() }
        renderTab()
        when {
            showSave -> bar.showMessage("")
            vaultActions.isNotEmpty() -> bar.showActions(vaultActions.map { it.label }, { i -> vaultActions.getOrNull(i)?.run?.invoke() }, { vault.dismiss() })
            liveGlideRaw!=null -> bar.showCandidates(candItems(),false)
            seq.isNotEmpty() -> bar.showCandidates(candItems(), panel == Panel.CANDS)
            sink == Sink.TRANSLATE && sinkBuf.isNotEmpty() -> bar.showMessage(transDirection)
            panel in listOf(Panel.HAND, Panel.HAND_CANDS) && handCands.isNotEmpty() -> bar.showCandidates(handCands.map { w -> CandItem(w, if (V3Prefs.enabled(this,V3Prefs.HAND_TONE) && w.any { it in '一'..'鿿' }) HandwritingPinyin.label(w) else null) }, panel == Panel.HAND_CANDS)
            !passwordField && prefs.getBoolean(Prefs.SMS_CODE,false) && pendingSmsCode?.let {it.expires>System.currentTimeMillis()}==true && panel==Panel.NONE && sink==Sink.NONE -> bar.showSmsCode(pendingSmsCode!!.value)
            assoc.isNotEmpty() && (panel == Panel.NONE || panel == Panel.HAND) -> bar.showAssoc(assoc)
            pendingClipText != null && panel == Panel.NONE && sink == Sink.NONE -> bar.showClipSuggestion(pendingClipText!!)
            else -> bar.showTools(listOf("more") + Prefs.tools(this), if (panel == Panel.ADJUST) null else activeTool())
        }
    }

    private fun message(text: String) {
        if (root == null) return
        bar.showMessage(text)
        main.removeCallbacksAndMessages(MSG_TOKEN)
        main.postAtTime({ renderBar() }, MSG_TOKEN, SystemClock.uptimeMillis() + 2200)
    }

    private fun enterIcon(info: EditorInfo?): Int {
        if (info == null || (info.imeOptions and EditorInfo.IME_FLAG_NO_ENTER_ACTION) != 0) return R.drawable.ic_enter
        return when (info.imeOptions and EditorInfo.IME_MASK_ACTION) {
            EditorInfo.IME_ACTION_SEARCH -> R.drawable.ic_search
            EditorInfo.IME_ACTION_SEND -> R.drawable.ic_send
            EditorInfo.IME_ACTION_GO -> R.drawable.ic_go
            EditorInfo.IME_ACTION_NEXT -> R.drawable.ic_next
            EditorInfo.IME_ACTION_DONE -> R.drawable.ic_done
            EditorInfo.IME_ACTION_PREVIOUS -> R.drawable.ic_chevron_left
            else -> R.drawable.ic_enter
        }
    }

    // —————————— 输出：编辑框或输入条 ——————————

    private fun ic(): InputConnection? = currentInputConnection

    private fun statsAllowed()= V3Prefs.enabled(this,V3Prefs.STATS_ENABLED) && !passwordField && sink==Sink.NONE && currentInputEditorInfo!=null &&
        (currentInputEditorInfo!!.imeOptions and EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING)==0
    private fun statsMode():String=when {
        externalInsertion->"paste"
        panel==Panel.VOICE || voiceToSink->"voice"
        panel==Panel.HAND || panel==Panel.HAND_CANDS->if(zh)"hand_zh" else "hand_en"
        glideSpelling->if(zh)"glide_zh" else "glide_en"
        !zh->"en26"
        else->"zh"+Prefs.layout(this)
    }
    private fun statsChars(text:String)=text.codePoints().filter {Character.isLetterOrDigit(it)}.count().toInt()
    private fun commitOut(text: String, typing: Boolean = false, statsSource:String?=null) {
        EditorDeletion.cancel()
        if (sink != Sink.NONE) {
            if(sinkStrip.localEditing) {sinkStrip.insertAtCursor(text);return}
            sinkBuf.append(text)
            sinkStrip.value = sinkBuf.toString()
            sinkStrip.composing = ""
            sinkStrip.refresh()
            onSinkChanged()
            return
        }
        checkpoint(typing = typing, group="latin")
        val automaticSpace=!externalInsertion && (V3Prefs.enabled(this,V3Prefs.SPACE_LATIN,false) || V3Prefs.enabled(this,V3Prefs.SPACE_DIGIT,false))
        val before=if(appComposing || !automaticSpace) "" else ic()?.getTextBeforeCursor(1,0)?.toString().orEmpty()
        val current=text.firstOrNull()
        val previous=before.lastOrNull()
        fun han(c:Char?)=c!=null && c in '一'..'鿿'
        val needsSpace=automaticSpace && ((V3Prefs.enabled(this,V3Prefs.SPACE_LATIN,false) &&
            (han(previous) && current?.isLetter()==true && !han(current) || han(current) && previous?.isLetter()==true && !han(previous))) ||
            (V3Prefs.enabled(this,V3Prefs.SPACE_DIGIT,false) && (han(previous) && current?.isDigit()==true || han(current) && previous?.isDigit()==true)))
        val output=(if(needsSpace) " " else "")+text
        appComposing=false;appComposingText=""
        val committed=ic()?.commitText(output,1)==true
        if(remoteEditor() && !passwordField) remoteHistory.inserted(output,typing)
        if(committed) {
            if(externalInsertion) vault.pasted(text) else vault.typed(text)
        }
        if(committed && !passwordField) {
            val count=statsChars(text)
            if(statsAllowed()){TypingStats.of(this).commit(statsSource ?: statsMode(),count);Prefs.addTyped(this,count)}
        }
    }

    /** 正在打的拼音：输入条模式下显示在输入条里；平时只显示在拼音条上，不写进编辑框 */
    private fun composeOut(text: String) {
        if(sink==Sink.TRANSLATE && langPicking && text.isNotEmpty()) setLanguagePicker(false)
        if (sink != Sink.NONE) {
            sinkStrip.composing = text
            sinkStrip.refresh()
        }
        renderTab()
    }

    // —————————— 拼音条 ——————————

    private var resolvedKeys: List<String> = emptyList()
    private var tabPresentationRaw=""
    private var tabPresentation=""
    private fun tabDisplay(): String {
        val raw=seq.joinToString("")
        val fallback=Lexicon.streamingDisplay(seq.toList())
        val first=cands.firstOrNull()
        val display=if(seq.all {it.length==1} && resolvedKeys==seq.toList()) {
            EnglishInput.literalSpelling(raw,first) ?: if(first?.abbr==true)fallback
            else SpellingAssist.presentation(raw,first?.py.orEmpty(),fallback)
        } else fallback
        tabPresentationRaw=raw;tabPresentation=display
        return display
    }
    private fun List<String>.dropWhileConsumed(n: Int): List<String> {
        var letters = 0; var index = 0
        while (index < size && letters < n) { if (this[index] != "'") letters++; index++ }
        while (index < size && this[index] == "'") index++
        return drop(index)
    }

    private fun renderTab() {
        if (!::tab.isInitialized) return
        val position=prefs.getInt(V3Prefs.PINYIN_POSITION,0)
        bar.reserveSpelling=zh && position==1
        val pending=pendingText()
        val spelling=if(zh)tabDisplay() else seq.joinToString("")
        val composingText=pending+spelling
        bar.inputSpelling=if(zh && position==1 && seq.isNotEmpty())composingText else ""
        if((zh && position==2 || !zh && !glideSpelling) && sink==Sink.NONE && !passwordField && seq.isNotEmpty() && composingText!=appComposingText) {
            appComposing=true;appComposingText=composingText;ic()?.setComposingText(composingText,1)
        }
        val show = zh && position==0 && (seq.isNotEmpty() || picks.isNotEmpty() || liveGlideRaw!=null) && sink==Sink.NONE && !(glideSpelling && !zh)
        if (!show) {
            if (tab.visibility != View.GONE) tab.visibility = View.GONE
            pinyinLens.visibility=View.GONE;positionCandidatePreview();return
        }
        if (pyCaret >= seq.size) pyCaret = -1
        tab.glideUnderline=glideSpelling && zh
        val text = pending + (liveGlideRaw?.let {Lexicon.streamingDisplay(it.map {c->c.toString()})} ?: spelling)
        tab.set(text, if (pyCaret < 0) -1 else displayIndexOf(pyCaret))
        tab.selectedPrefixLength = pendingText().length
        val first=cands.firstOrNull()
        val raw=seq.joinToString("")
        val source=first?.nativeChoice?.source?.takeIf {raw.startsWith(it)} ?: raw
        tab.feedback(if(V3Prefs.enabled(this,V3Prefs.FEEDBACK) && !glideSpelling &&
            resolvedKeys==seq.toList() && seq.all {it.length==1}) SpellingAssist.feedback(source,first?.py.orEmpty())
            else SpellingAssist.Feedback(BooleanArray(0),emptyList()))
        if (tab.visibility != View.VISIBLE) tab.visibility = View.VISIBLE
        positionCandidatePreview()
    }

    /** 拼音条上第 b 个位置对应 seq 的哪个下标（拼音里每个字母对应一个键，' 是分隔） */
    private fun seqIndexAt(b: Int): Int {
        val pend = pendingText().length
        if (b <= pend) return 0
        val disp = tabDisplay()
        var letters = 0
        var lastApos = false
        for (ch in disp.substring(0, (b - pend).coerceAtMost(disp.length))) {
            if (ch == '\'') lastApos = true else { letters++; lastApos = false }
        }
        var i = 0
        var cnt = 0
        while (i < seq.size && cnt < letters) { if (seq[i] != "'") cnt++; i++ }
        if (lastApos) while (i < seq.size && seq[i] == "'") i++
        return i
    }

    private fun displayIndexOf(seqIdx: Int): Int {
        val disp = tabDisplay()
        val letters = seq.subList(0, seqIdx.coerceIn(0, seq.size)).count { it != "'" }
        var cnt = 0
        var j = 0
        while (j < disp.length && cnt < letters) { if (disp[j] != '\'') cnt++; j++ }
        if (seqIdx > 0 && seq[seqIdx - 1] == "'" && j < disp.length && disp[j] == '\'') j++
        return pendingText().length + j
    }

    private fun onTabCaret(b: Int) {
        if (seq.isEmpty()) return
        val i = seqIndexAt(b)
        pyCaret = if (i >= seq.size) -1 else i
        buzz()
        renderTab()
    }

    /** 在拼音条光标处插入一个键（光标在最后就是追加） */
    private fun insertKey(k: String) {
        if (pyCaret in 0 until seq.size) { seq.add(pyCaret, k); pyCaret++ } else seq.add(k)
    }

    // —————————— 撤销与重做：和空叶文档一样，存整段文字和光标的快照；连续输入停顿不到 1 秒合成一步 ——————————

    private data class Snap(val text: String, val sel: Int, val end: Int=sel)

    private val undoStack = ArrayDeque<Snap>()
    private val redoStack = ArrayDeque<Snap>()
    private var lastTypingAt = 0L
    private var lastTypingGroup = ""

    private fun snap(): Snap? {
        if(passwordField) return null
        val ic = ic() ?: return null
        val ext = ic.getExtractedText(ExtractedTextRequest().apply { hintMaxChars = 200000 }, 0)
        if (ext?.text != null && ext.startOffset == 0) return Snap(ext.text.toString(), ext.selectionStart.coerceAtLeast(0),ext.selectionEnd.coerceAtLeast(0))
        val b = ic.getTextBeforeCursor(100000, 0)?.toString() ?: return null
        val a = ic.getTextAfterCursor(100000, 0)?.toString().orEmpty()
        return Snap(b + a, b.length)
    }

    private fun editorCommand(action:String,direction:String?=null):Boolean {
        if(currentInputEditorInfo?.extras?.getInt("com.kongye.input.EDITOR_PROTOCOL",0)!=1) return false
        val data=android.os.Bundle().apply {direction?.let {putString("direction",it)}}
        return ic()?.performPrivateCommand("com.kongye.input.$action",data)==true
    }

    private fun checkpoint(typing: Boolean, group: String = "typing") {
        if (passwordField || remoteEditor() || sink != Sink.NONE || currentInputEditorInfo?.extras?.getInt("com.kongye.input.EDITOR_PROTOCOL",0)==1) return
        val now = SystemClock.uptimeMillis()
        if (typing && group==lastTypingGroup && now - lastTypingAt <= 700) { lastTypingAt = now; return }
        snap()?.let { s ->
            if (undoStack.lastOrNull() != s) undoStack.addLast(s)
            while (undoStack.size > 100) undoStack.removeFirst()
        }
        redoStack.clear()
        lastTypingAt = if (typing) now else 0L
        lastTypingGroup = if(typing) group else ""
    }

    private fun restore(s: Snap) {
        val ic = ic() ?: return
        val cur = snap() ?: return
        restoringHistoryUntil=SystemClock.uptimeMillis()+1000
        ic.beginBatchEdit()
        ic.finishComposingText()
        // Keep the unchanged prefix/suffix (including editor sentinel characters).
        // Selecting/replacing the entire extraction breaks block-based editors.
        var left=0
        while(left<cur.text.length && left<s.text.length && cur.text[left]==s.text[left]) left++
        if(left>0 && left<cur.text.length && Character.isSurrogatePair(cur.text[left-1],cur.text[left])) left--
        var right=0
        while(right<cur.text.length-left && right<s.text.length-left && cur.text[cur.text.lastIndex-right]==s.text[s.text.lastIndex-right]) right++
        if(right>0 && right<cur.text.length-left && Character.isSurrogatePair(cur.text[cur.text.length-right-1],cur.text[cur.text.length-right])) right--
        if(cur.text!=s.text) {
            ic.setSelection(left,left)
            ic.deleteSurroundingText(0,cur.text.length-left-right)
            val insert=s.text.substring(left,s.text.length-right)
            if(insert.isNotEmpty()) ic.commitText(insert,1)
        }
        ic.endBatchEdit()
        ic.setSelection(s.sel.coerceIn(0,s.text.length),s.end.coerceIn(0,s.text.length))
        lastTypingAt = 0L
    }

    private fun undo() {
        if(sink!=Sink.NONE && sinkStrip.localEditing) {sinkStrip.editAction("undo");return}
        if(passwordField) return
        if (sink != Sink.NONE) return
        if (seq.isNotEmpty() || picks.isNotEmpty()) { resetComposing(); composeOut(""); renderBar(); return }
        if(remoteEditor()) {ic()?.let {if(!remoteHistory.undo(it)) message("没有可撤回的本次键盘输入")};return}
        if(editorCommand("UNDO")) return
        val s = undoStack.removeLastOrNull()
        if (s == null) { if (ic()?.performContextMenuAction(android.R.id.undo) != true) message("没有可以撤销的输入"); return }
        snap()?.let { redoStack.addLast(it) }
        restore(s)
    }

    private fun redo() {
        if(sink!=Sink.NONE && sinkStrip.localEditing) {sinkStrip.editAction("redo");return}
        if(passwordField) return
        if(remoteEditor()) {ic()?.let {if(!remoteHistory.redo(it)) message("没有可重做的本次键盘输入")};return}
        if(editorCommand("REDO")) return
        val s = redoStack.removeLastOrNull()
        if (s == null) { if (ic()?.performContextMenuAction(android.R.id.redo) != true) message("没有可以重做的输入"); return }
        snap()?.let { undoStack.addLast(it) }
        restore(s)
    }

    // —————————— 拼音组字 ——————————

    private fun pendingText() = picks.joinToString("") { it.w }

    private fun resetComposing() {
        cancelHandwritingPreview()
        candidateRevision++;candidateTask?.cancel(false);candidatesPending=false
        pendingPick=null;pendingSymbol=null;deferFollowingActions=false;main.removeCallbacks(replayFollowingActions)
        glidePending=false;liveGlideRaw=null;glideSpelling=false;glidePrefixRaw="";glidePrefixText="";glideBefore=emptyList();queuedGlides.clear();followingActions.clear()
        if(appComposing) {ic()?.setComposingText("",1);ic()?.finishComposingText()}
        appComposing=false;appComposingText=""
        seq.clear();tabPresentationRaw="";tabPresentation=""
        picks.clear()
        pyCaret = -1
        cands = emptyList()
        if (::sinkStrip.isInitialized) sinkStrip.composing = ""
        if (::tab.isInitialized) tab.reset()
    }

    private val candidateWorker=java.util.concurrent.ScheduledThreadPoolExecutor(1) { task -> Thread(task,"Kongye candidates").apply {isDaemon=true} }.apply { removeOnCancelPolicy=true }
    private var candidateTask:java.util.concurrent.Future<*>?=null
    @Volatile private var candidateRevision=0
    private var liveGlideRaw:String?=null
    private var glideSpelling=false
    private var glidePrefixRaw=""
    private var glidePrefixText=""
    private var glideBefore=emptyList<Cand>()
    private val queuedGlides=java.util.ArrayDeque<GlideTrace>()

    private fun beginGlide() {
        glidePrefixRaw=seq.joinToString("")
        glidePrefixText=cands.firstOrNull()?.w.orEmpty()
        glideBefore=cands
        glideSpelling=true
        assoc=emptyList()
    }

    private fun joinGlide(words:List<Cand>,raw:String):List<Cand> {
        if(glidePrefixRaw.isEmpty()) return words
        val prefix=glidePrefixText+if(!zh && glidePrefixText.isNotEmpty()) " " else ""
        return words.map {Cand(prefix+it.w,glidePrefixRaw.length+raw.length,null,it.score,py=it.py,sentence=true,correction=it.correction)}
    }
    private var glidePreviewAt=0L
    private var glidePending=false
    private var candidatesPending=false
    private var pendingPick: Pair<Int,Int>?=null
    private var pendingExplicitChoice=false
    private var pendingSymbol: (() -> Unit)?=null
    private val followingActions=ArrayDeque<() -> Unit>()
    private var deferFollowingActions=false
    private val replayFollowingActions=Runnable {
        deferFollowingActions=false
        drainFollowingKeys()
    }
    private fun drainFollowingKeys() {
        while(followingActions.isNotEmpty() && pendingPick==null && pendingSymbol==null && !glidePending && !deferFollowingActions)
            followingActions.removeFirst().invoke()
    }
    private var candidateFrame: (() -> Unit)? = null
    private var candidateFrameScheduled = false
    private fun showCandidateResult(
        revision: Int, keys: List<String>, result: List<Cand>, complete: Boolean,
        fullLookup: Boolean = false, originalOrder: List<Cand> = emptyList(),
    ) {
        if(revision!=candidateRevision)return
        val raw=keys.joinToString("")
        val english=if(zh && V3Prefs.enabled(this,V3Prefs.MIXED) && raw.all {it in 'a'..'z'} && keys.all {it.length==1})
            EnglishInput.candidates(applicationContext,raw,checkCorrection=false).filter {it.w!=raw}.take(2) else emptyList()
        val mixed=if(zh)EnglishInput.mixed(applicationContext,raw,result) else emptyList()
        val base=if(zh)result.filter {c->
            c.py.isNullOrEmpty() || c.abbr && !c.correction || c.nativeChoice?.schema?.startsWith("fcitx/")==true ||
                c.verifiedSentenceRepair ||
                SpellingAssist.allows((c.nativeChoice?.source ?: raw).filter {it!='\''}.take(c.n),c.py.orEmpty())
        } else result
        main.post {
            if(revision!=candidateRevision) return@post
            // Primary and refined results that finish within one display frame
            // share a single layout/draw, keeping only the newest candidate list.
            candidateFrame = frame@{
                if(revision!=candidateRevision || seq.toList()!=keys) return@frame
                val growing=resolvedKeys.isNotEmpty() && keys.size>=resolvedKeys.size && keys.take(resolvedKeys.size)==resolvedKeys
                val updated=base.toMutableList()
                if(zh) {
                    updated.removeAll {c->
                        when {
                            c.w.codePoints().anyMatch {Character.getType(it)==Character.OTHER_SYMBOL.toInt()} -> !V3Prefs.enabled(this,V3Prefs.EMOJI)
                            c.w.codePoints().noneMatch {Character.UnicodeScript.of(it)==Character.UnicodeScript.HAN} && c.w.any {it in "()￣▽╰╯"} -> !V3Prefs.enabled(this,V3Prefs.KAOMOJI)
                            c.w.codePoints().noneMatch {Character.isLetterOrDigit(it)} -> !V3Prefs.enabled(this,V3Prefs.SYMBOLS)
                            else -> false
                        }
                    }
                    if(V3Prefs.enabled(this,V3Prefs.MIXED) && keys.all {it.length==1 && it[0] in 'a'..'z'}) {
                        updated.addAll(minOf(8,updated.size),english)
                    }
                }
                if(mixed.isNotEmpty())updated.addAll(0,mixed)
                if(zh && growing && updated.firstOrNull()?.verifiedSentenceRepair != true) {
                    val previous=cands.firstOrNull { !it.py.isNullOrBlank() && it.w.any {ch->ch in '一'..'鿿'} }
                    val previousChars=previous?.w?.codePointCount(0,previous.w.length) ?: 0
                    if(previous!=null && previousChars>=2 && previous.n<keys.count {it!="'"}) {
                        // Only replace a matched phrase when the new candidate
                        // covers at least as much text. The suffix stays editable.
                        val index=updated.indexOfFirst { !it.py.isNullOrBlank() &&
                            (!it.correction || it.abbr && it.nativeChoice==null) && it.n>=previous.n &&
                            it.w.codePointCount(0,it.w.length)>=previousChars }
                        val best=if(index>=0)updated.removeAt(index) else previous
                        updated.removeAll {it.w==best.w && it.n==best.n}
                        updated.add(0,best)
                    }
                }
                val next=if(zh) EnglishInput.recognize(applicationContext,raw,updated) else updated
                cands=if(panel==Panel.CANDS && originalOrder.isNotEmpty()) {
                    // Extend the original row in place when the full lookup arrives.
                    val existing=originalOrder.mapTo(HashSet()) {it.w to it.n}
                    originalOrder+next.filter {existing.add(it.w to it.n)}
                } else next
                resolvedKeys=keys
                if (!passwordField && sink == Sink.NONE && android.util.Log.isLoggable("KongyePinyin", android.util.Log.DEBUG)) {
                    android.util.Log.d("KongyePinyin", "resolved=" + keys.joinToString("") +
                        " selected=" + cands.firstOrNull()?.py + " display=" + tabDisplay())
                }
                if(complete) candidatesPending=false
                renderBar()
                updateExpandedCandidates()
                val requested=pendingPick
                if(complete && requested?.first==revision) {
                    pendingPick=null
                    cands.getOrNull(requested.second)?.let {pick(it,explicitChoice=pendingExplicitChoice && requested.second>0)}
                    pendingExplicitChoice=false
                    drainFollowingKeys()
                } else if(complete && pendingSymbol!=null) {
                    finishPendingSymbol()
                } else if(complete && !fullLookup && panel==Panel.CANDS && canExpandLookup(keys)) {
                    // The panel opened before the lookup for the latest keys had produced a row.
                    refreshComposing(originalOrder=cands)
                }
            }
            if(!candidateFrameScheduled) {
                candidateFrameScheduled=true
                android.view.Choreographer.getInstance().postFrameCallback {
                    candidateFrameScheduled=false
                    val frame=candidateFrame;candidateFrame=null;frame?.invoke()
                }
            }
        }
    }
    private fun canExpandLookup(keys: List<String>) =
        zh && keys.isNotEmpty() && keys.all {it.length==1} && flag(Prefs.ABBR) && FcitxEngine.ready

    private fun refreshComposing(originalOrder: List<Cand> = emptyList()) {
        val keys=seq.toList();val revision=++candidateRevision
        if (!passwordField && sink == Sink.NONE && android.util.Log.isLoggable("KongyePinyin", android.util.Log.DEBUG)) {
            val raw = keys.joinToString("")
            val graph = PinyinSegmentation.graph(raw, Lexicon.pinyinSyllables().toSet())
            android.util.Log.d("KongyePinyin", "raw=$raw graph=" +
                graph.edges.mapIndexedNotNull { at, edges -> edges.takeIf { it.isNotEmpty() }?.let { "$at:$it" } } +
                " paths(first64)=" + graph.paths().map { it.joinToString("'") } +
                " provisional=" + Lexicon.streamingDisplay(keys))
        }
        val pending=pendingText();val abbr=flag(Prefs.ABBR)
        val fullLookup=panel==Panel.CANDS && canExpandLookup(keys)
        candidatesPending=true
        val chinese=zh
        if(originalOrder.isEmpty()) {
            if(sink==Sink.NONE)renderTab() else composeOut(pending+Lexicon.streamingDisplay(keys))
        }
        candidateTask?.cancel(false)
        candidateTask=candidateWorker.submit {
            val result=try {
                if(!chinese) EnglishInput.candidates(applicationContext,keys.joinToString(""))
                else if(fullLookup) PinyinEngine.expandedCandidates(keys) {revision==candidateRevision}
                else PinyinEngine.candidatesLive(keys,abbr,{revision==candidateRevision}) {primary ->
                    showCandidateResult(revision,keys,primary,false)
                }
            } catch (_: java.util.concurrent.CancellationException) { return@submit }
            catch (error: Exception) {
                android.util.Log.e("KongyeIME","Candidate query failed",error)
                listOf(Cand(keys.joinToString("") {it.take(1)},keys.count {it!="'"},null,0f))
            }
            showCandidateResult(revision,keys,result,true,fullLookup,originalOrder)
        }
    }

    /** 从输入里拿掉前 n 个键（连同中间和紧跟着的分词符），返回拿掉的键 */
    private fun consume(n: Int): List<String> {
        var cnt = 0
        var i = 0
        while (i < seq.size && cnt < n) { if (seq[i] != "'") cnt++; i++ }
        while (i < seq.size && seq[i] == "'") i++
        val taken = seq.subList(0, i).toList()
        repeat(i) { seq.removeAt(0) }
        return taken
    }

    private fun learnOn() = learnField && sink == Sink.NONE && flag(Prefs.LEARN)

    private fun letterCount() = seq.count { it != "'" }

    private fun pick(c: Cand, resolvedLength:Boolean=false, explicitChoice:Boolean=false) {
        val before=if(!candidatesPending && resolvedKeys==seq.toList()) CandidateSnapshot(
            seq.toList(),cands,tabPresentationRaw,tabPresentation,flag(Prefs.ABBR),Lexicon.fuzzyRules.toSet(),panel==Panel.CANDS
        ) else null
        val fcitx=c.nativeChoice?.schema?.startsWith("fcitx/")==true
        val fullSpelling=fcitx && !c.correction && c.n==letterCount() &&
            c.py.orEmpty().replace(" ","")==seq.filter {it!="'"}.joinToString("")
        val consumed=if(fcitx && !resolvedLength && !fullSpelling) runCatching {FcitxEngine.consume(c,false)}.getOrElse {
            android.util.Log.e("KongyeFcitx","Selection failed",it);return
        } else c.n
        if (consumed <= 0 || consumed > letterCount()) return
        val taken = consume(consumed)
        if(statsAllowed())TypingStats.of(this).choiceFromReading(statsMode(),taken.count {it!="'"},statsChars(c.w),cands.indexOf(c)<=0,
            taken.joinToString(""),if(!zh && !c.w.lowercase().startsWith(taken.joinToString("").lowercase()))c.w else c.py)
        pyCaret = -1
        val syl = c.e?.syl?.toList() ?: c.py?.split(' ')?.takeIf {c.abbr && it.none(String::isBlank)}
            ?: Lexicon.decode(taken.filter { it != "'" })
        picks.add(Pick(c.w, syl, taken, c.e == null, before))
        // Keep at most eight recent candidate pages; older picks still retain their original keys.
        if(picks.size>8) picks[picks.size-9].before=null
        if (explicitChoice && learnOn() && zh) {
            val reading=c.py?.split(' ')?.filter {it.isNotEmpty()}.orEmpty()
            if(reading.isNotEmpty() && reading.all {it in Lexicon.pinyinSyllables()} &&
                c.w.codePoints().allMatch {Character.UnicodeScript.of(it)==Character.UnicodeScript.HAN}) {
                Lexicon.learnPhrase(c.w,reading);Lexicon.save()
            }
        }
        if (letterCount() == 0) { seq.clear(); finishPicks() } else refreshComposing()
    }

    private fun finishPicks() {
        val text = pendingText()
        lastHomophone=if(zh)text.takeLast(1) else ""
        commitOut(text+if(!zh && V3Prefs.enabled(this,V3Prefs.EN_SPACE)) " " else "")
        picks.clear()
        cands = emptyList()
        tabPresentationRaw="";tabPresentation=""
        if (::tab.isInitialized) tab.reset()
        assoc = if (zh && sink == Sink.NONE && flag(Prefs.ASSOC)) Lexicon.assoc(text) else emptyList()
        if (panel == Panel.CANDS) closePanel(render = false)
        renderBar()
    }

    /** 标点、回车等打断组字时：一路选第一个候选直到拼音用完 */
    private fun finalizeComposing() {
        var guard = 0
        while (seq.isNotEmpty() && guard++ < 40) {
            if (letterCount() == 0) { seq.clear(); if (picks.isNotEmpty()) finishPicks(); return }
            val resolved=cands.firstOrNull().takeIf {resolvedKeys==seq && !candidatesPending}
            pick(resolved ?: if(zh) {val words=PinyinEngine.candidates(seq.toList(), flag(Prefs.ABBR));EnglishInput.recognize(this,seq.joinToString(""),EnglishInput.mixed(this,seq.joinToString(""),words)+words).first()}
                else EnglishInput.candidates(this,seq.joinToString("")).first())
        }
    }

    private fun commitRaw() {
        val text = pendingText() + Lexicon.decode(seq.filter { it != "'" }).joinToString("")
        resetComposing()
        commitOut(text)
        assoc = emptyList()
        if (panel == Panel.CANDS) closePanel(render = false)
        renderBar()
    }

    // —————————— 按键 ——————————

    private val vibrator: Vibrator? by lazy {
        if (Build.VERSION.SDK_INT >= 31) getSystemService(VibratorManager::class.java)?.defaultVibrator
        else @Suppress("DEPRECATION") getSystemService(Vibrator::class.java)
    }

    private fun buzz() {
        val mode=prefs.getInt(V3Prefs.VIBRATION,0)
        if(mode==2)return
        if(mode==0) {keyboard.performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP);return}
        val legacy=prefs.getInt(Prefs.VIB_STRENGTH,2)
        val level=prefs.getInt(V3Prefs.VIBRATION_LEVEL,when(legacy){1->2;3->6;else->4}).coerceIn(2,6)
        val v=vibrator ?: return
        val (ms,amp)=when(level){2->8L to 55;3->12L to 100;4->16L to 150;5->20L to 200;else->24L to 255}
        runCatching {v.vibrate(VibrationEffect.createOneShot(ms,if(v.hasAmplitudeControl())amp else VibrationEffect.DEFAULT_AMPLITUDE))}
    }

    override fun onPress(key: Key) {
        if(sink==Sink.NONE) vault.editing()
        EditorDeletion.cancel()
        buzz()
        if (prefs.getBoolean(Prefs.SOUND, false)) {
            val audio = getSystemService(AudioManager::class.java) ?: return
            if (audio.ringerMode != AudioManager.RINGER_MODE_NORMAL) return
            val fx = when (key.kind) {
                Kind.DEL -> AudioManager.FX_KEYPRESS_DELETE
                Kind.ENTER -> AudioManager.FX_KEYPRESS_RETURN
                Kind.SPACE -> AudioManager.FX_KEYPRESS_SPACEBAR
                else -> AudioManager.FX_KEYPRESS_STANDARD
            }
            audio.playSoundEffect(fx, prefs.getInt(V3Prefs.SOUND_VOLUME, 50) / 100f)
        }
    }

    override fun onGlidePreview(trace:GlideTrace) {
        if(!V3Prefs.enabled(this,V3Prefs.GLIDE_LIVE))return
        if(glidePending)return
        if(!V3Prefs.glide(this,zh) || passwordField || layer!=Layer.LETTERS) return
        if(liveGlideRaw==null) {
            beginGlide()
            liveGlideRaw=glidePrefixRaw
        }
        val now=SystemClock.uptimeMillis()
        if(now-glidePreviewAt<120) return
        glidePreviewAt=now
        val revision=++candidateRevision;val chinese=zh
        candidateTask?.cancel(false)
        candidateTask=candidateWorker.submit {
            val matches=try {GlideDecoder.decode(applicationContext,trace,chinese) {revision==candidateRevision}}
                catch(_:java.util.concurrent.CancellationException) {return@submit}
                catch(_:Exception) {emptyList()}
            val raw=matches.firstOrNull()?.spelling
            if(raw==null)return@submit
            val words=try {if(chinese) FcitxEngine.primaryCandidates(raw.map {it.toString()}) {revision==candidateRevision}
                else matches.map {Cand(it.spelling,raw.length,null,-it.cost)}}
                catch(_:java.util.concurrent.CancellationException) {return@submit}
                catch(_:Exception) {emptyList()}
            main.post {if(revision==candidateRevision && liveGlideRaw!=null) {
                liveGlideRaw=glidePrefixRaw+raw;cands=joinGlide(words,raw);renderBar()
            }}
        }
    }
    override fun onGlideCancel() {
        if(liveGlideRaw==null)return
        candidateRevision++;candidateTask?.cancel(false);liveGlideRaw=null;glideSpelling=seq.isNotEmpty();cands=glideBefore;renderBar()
    }

    override fun onGlide(trace:GlideTrace) {
        if(glidePending) {queuedGlides.addLast(trace);return}
        if(!V3Prefs.glide(this,zh) || passwordField || layer!=Layer.LETTERS) return
        if(liveGlideRaw==null) beginGlide()
        liveGlideRaw=null;glideSpelling=true
        assoc=emptyList()
        val revision=++candidateRevision;val chinese=zh
        candidateTask?.cancel(false);glidePending=true
        candidateTask=candidateWorker.submit {
            val matches=try {GlideDecoder.decode(applicationContext,trace,chinese) {revision==candidateRevision}}
                catch(_:java.util.concurrent.CancellationException) {return@submit}
                catch(e:Exception) {android.util.Log.e("KongyeGlide","Offline glide failed",e);emptyList()}
            val decoded=try {
                if(chinese) FcitxEngine.glideCandidates(matches) {revision==candidateRevision} else emptyList()
            } catch(_:java.util.concurrent.CancellationException) {return@submit}
              catch(e:Exception) {android.util.Log.e("KongyeGlide","Native glide candidates failed",e);emptyList()}
            main.post {
                if(revision!=candidateRevision) return@post
                glidePending=false
                if(matches.isNotEmpty()) {
                    val raw=matches.first().spelling
                    seq.clear();seq.addAll((glidePrefixRaw+raw).map {it.toString()});pyCaret=-1
                    if(chinese && decoded.isNotEmpty()) {
                        cands=joinGlide(PinyinExpressions.insert(decoded,raw.map {it.toString()}),raw);resolvedKeys=seq.toList();candidatesPending=false
                        composeOut(raw);renderBar()
                    } else if(chinese) refreshComposing()
                    else {
                        cands=joinGlide(matches.map {Cand(when(shift) {2->it.spelling.uppercase();1->it.spelling.replaceFirstChar(Char::uppercaseChar);else->it.spelling},raw.length,null,-it.cost)},raw)
                        resolvedKeys=seq.toList();candidatesPending=false;composeOut(raw);renderBar()
                    }
                } else {
                    cands=glideBefore
                    resolvedKeys=seq.toList()
                    renderBar()
                    message("未识别到滑行，请再试一次")
                }
                if(queuedGlides.isNotEmpty()) onGlide(queuedGlides.removeFirst())
                drainFollowingKeys()
            }
        }
    }

    override fun onKey(key: Key) {
        // An immediate space must choose the just-typed word, never the previous
        // frame's candidates. Preserve subsequent key order while lookup finishes.
        if(pendingPick!=null || pendingSymbol!=null || glidePending || deferFollowingActions) {followingActions.addLast {onKey(key)};return}
        if (key.kind != Kind.SHIFT) lastShiftTap = 0L
        if(statsAllowed())TypingStats.of(this).key(statsMode(),key.letters.firstOrNull(),key.kind==Kind.DEL)
        when (key.kind) {
            Kind.ACTION -> {
                if(key.out=="clear") onSwipeDel(true)
                else if(key.out=="del") delete()
                else {if(seq.isNotEmpty()) finalizeComposing();if(remoteEditor() && sink==Sink.NONE && key.out in setOf("left","right","up","down","home","end","select","all","copy","cut")) runFeature("edit");editAction(key.out)}
            }
            Kind.LETTER -> typeLetter(key)
            Kind.CHAR -> typeChar(key.out)
            Kind.SPACE -> {
                if(seq.isEmpty() && assoc.isNotEmpty() && V3Prefs.enabled(this,V3Prefs.ASSOC_SPACE,false)) {onAssoc(0);return}
                if (seq.isNotEmpty()) {
                    if(candidatesPending) {pendingPick=candidateRevision to 0;pendingExplicitChoice=false}
                    else cands.firstOrNull()?.let { pick(it) } ?: commitRaw()
                }
                else { assoc = emptyList(); commitOut(" "); renderBar(); updateAutoCaps() }
            }
            Kind.ENTER -> {
                if (seq.isNotEmpty()) { commitRaw(); return }
                assoc = emptyList()
                if (sink != Sink.NONE) { sinkConfirm(); return }
                checkpoint(typing = false)
                vault.submitted()
                performEnter()
                renderBar()
                updateAutoCaps()
            }
            Kind.DEL -> delete()
            Kind.SHIFT -> tapShift()
            Kind.SEP -> if (seq.isNotEmpty()) { addSeparator(); refreshComposing() } else typeChar("1")
            Kind.RESET -> if (seq.isNotEmpty() || picks.isNotEmpty()) { resetComposing(); composeOut(""); if (sink == Sink.NONE) ic()?.finishComposingText(); renderBar() }
            Kind.NUM -> NativeMotion.swap(root,col) { layer = Layer.NUM; render() }
            Kind.SYM -> openPanel(Panel.SYMBOL)
            Kind.ABC -> NativeMotion.swap(root,col) { layer = Layer.LETTERS; render(); updateAutoCaps() }
            Kind.LANG -> toggleLang()
        }
    }

    override fun onLongKey(key: Key) {
        when {
            key.kind == Kind.SPACE && prefs.getInt(Prefs.SPACE_LONG, 0) == 2 -> {
                // 空格长按开关语音
                if (sink != Sink.NONE) voiceIntoSink()
                else if (panel == Panel.VOICE) toggleListening() else { openVoice(); toggleListening() }
            }
            key.kind == Kind.DEL && V3Prefs.enabled(this,V3Prefs.COMPOSE_HOLD_CLEAR) && (seq.isNotEmpty() || picks.isNotEmpty()) -> { resetComposing();composeOut("");assoc=emptyList();renderBar();if(panel==Panel.CANDS) closePanel() }
            key.longEmoji -> openPanel(Panel.EMOJI)
            key.kind == Kind.SHIFT && !splitMode() -> {
                lastShiftTap = 0L
                shift = if(zh || V3Prefs.enabled(this,V3Prefs.CAPS_LOCK,false))2 else 1
                if(!zh)englishCapsLocked=shift==2
                keyboard.shiftState = shift
                keyboard.invalidate()
            }
        }
    }

    /** 长按工具栏的编辑工具后左右滑 */
    override fun onToolCursor(dir: Int) {
        if(passwordField) vault.invalidateCapture()
        onCursorMove(dir, 0)
    }

    override fun onCursorModeChange(active: Boolean) {
        if (::bar.isInitialized) bar.cursorMode = active
        if (::bottomStrip.isInitialized) bottomStrip.cursorMode = active
    }

    override fun onCursorMove(dx: Int, dy: Int) {
        editMoveAt=SystemClock.uptimeMillis()
        if(sink!=Sink.NONE && sinkStrip.localEditing) {
            if(seq.isNotEmpty()) finalizeComposing()
            sinkStrip.editAction(when {dx<0->"left";dx>0->"right";dy<0->"up";else->"down"})
            return
        }
        if(passwordField) vault.invalidateCapture()
        if (seq.isNotEmpty() || sink != Sink.NONE) return
        if(remoteEditor()) {message("UU 未转发方向键，请用 UU 自带键盘移动光标");return}
        if(dy!=0) {
            val extracted=ic()?.getExtractedText(ExtractedTextRequest(),0)
            val value=extracted?.text
            if(value!=null && extracted.selectionStart==extracted.selectionEnd) {
                val caret=extracted.selectionStart
                if(dy<0 && extracted.startOffset==0 && caret<=0)return
                if(dy>0 && extracted.partialEndOffset<0 && caret>=value.length)return
            }
        }
        when {
            dx < 0 -> sendDownUpKeyEvents(KeyEvent.KEYCODE_DPAD_LEFT)
            dx > 0 -> sendDownUpKeyEvents(KeyEvent.KEYCODE_DPAD_RIGHT)
            dy < 0 -> sendDownUpKeyEvents(KeyEvent.KEYCODE_DPAD_UP)
            dy > 0 -> sendDownUpKeyEvents(KeyEvent.KEYCODE_DPAD_DOWN)
        }
    }

    override fun onDeleteGesture(direction:String) {deleteGesture(direction)}
    override fun onSwipeDel(down: Boolean) { deleteGesture(if(down) "down" else "up") }

    private fun deleteGesture(direction:String) {
        val key=when(direction){"up"->V3Prefs.DEL_UP;"left"->V3Prefs.DEL_LEFT;"right"->V3Prefs.DEL_RIGHT;else->V3Prefs.DEL_DOWN}
        when(prefs.getString(key,if(direction=="up")"undo" else if(direction=="down")"clear" else "none")) {
            "clear"->clearInput()
            "undo"->undo()
            "redo"->redo()
            "delete"->delete()
            "left"->moveCursor(-1)
            "right"->moveCursor(1)
        }
    }
    private fun clearInput() {
        cancelHandwritingPreview()
        if(assoc.isNotEmpty() && !V3Prefs.enabled(this,V3Prefs.DELETE_DIRECT)) {assoc=emptyList();renderBar();return}
        vault.invalidateCapture()
        // 下滑清空
        if (seq.isNotEmpty() || picks.isNotEmpty()) { resetComposing(); composeOut(""); if (sink == Sink.NONE) ic()?.finishComposingText() }
        if (sink != Sink.NONE) {
            sinkStrip.clearInput(prefs.getInt(V3Prefs.CLEAR_SCOPE,0))
            sinkBuf.setLength(0);sinkBuf.append(sinkStrip.value)
            onSinkChanged()
        } else {
            checkpoint(typing = false)
            val ic = ic() ?: return
            ic.beginBatchEdit()
            val scope=prefs.getInt(V3Prefs.CLEAR_SCOPE,0)
            val cleared = if(remoteEditor() && !passwordField) remoteHistory.clear(ic,paragraphOnly=scope==2) else when(scope) {
                1 -> {
                    val before=ic.getTextBeforeCursor(100000,0)?.length ?: 0
                    if(!ic.getSelectedText(0).isNullOrEmpty()) ic.commitText("",1)
                    ic.deleteSurroundingText(before,0)
                }
                2 -> EditorDeletion.clearParagraph(ic)
                else -> EditorDeletion.clear(ic, currentInputEditorInfo?.packageName)
            }
            ic.endBatchEdit()
            if (!cleared) {
                assoc = emptyList()
                message(if(remoteEditor()) "没有可清空的本次键盘输入，请在远端全选后删除" else "当前应用未提供完整段落，无法清空")
                return
            }
        }
        assoc = emptyList()
        renderBar()
    }

    private fun typeLetter(k: Key) {
        glideSpelling=false;liveGlideRaw=null
        pendingClipText?.let { dismissedClipText = it; pendingClipText = null }
        val ch = k.letters.take(1)
        if ((passwordField && sink==Sink.NONE) || layer != Layer.LETTERS || zh && shift > 0) {
            if (seq.isNotEmpty()) finalizeComposing()
            assoc = emptyList()
            commitOut(if (shift > 0) ch.uppercase() else ch, typing=true)
            if (shift == 1) { shift = 0; keyboard.shiftState = 0; keyboard.invalidate() }
            renderBar()
            return
        }
        if (seq.size >= 128) return
        insertKey(if(!zh && shift>0) ch.uppercase() else k.letters)
        if(!zh && shift==1) {shift=0;keyboard.shiftState=0}
        assoc = emptyList()
        refreshComposing()
    }

    /** 分词符：插在拼音条光标处，不重复 */
    private fun addSeparator() {
        val at = if (pyCaret in 0 until seq.size) pyCaret else seq.size
        if (at == 0 || seq[at - 1] == "'" || seq.getOrNull(at) == "'") return
        insertKey("'")
    }

    private fun typeChar(s: String, paired: Boolean = true) {
        if(pendingSymbol!=null || deferFollowingActions) {followingActions.addLast {typeChar(s,paired)};return}
        pendingClipText?.let { dismissedClipText = it; pendingClipText = null }
        if (seq.isNotEmpty()) {
            if(!zh || V3Prefs.enabled(this,V3Prefs.PUNCT_CONFIRM)) {
                pendingSymbol={commitSymbol(s,paired)}
                finishPendingSymbol()
                return
            }
            commitRaw()
        }
        commitSymbol(s,paired)
    }
    private fun finishPendingSymbol() {
        val commit=pendingSymbol ?: return
        if(candidatesPending)return
        val letters=letterCount()
        val first=cands.firstOrNull()?.takeIf {resolvedKeys==seq && it.n in 1..letters}
        if(first!=null && first.n<letters) {
            pick(first,resolvedLength=true)
            return
        }
        val connection=if(sink==Sink.NONE)ic() else null
        connection?.beginBatchEdit()
        try {
            if(first!=null)pick(first,resolvedLength=true)
            else if(seq.isNotEmpty()) {
                val queued=followingActions.toList()
                commitRaw()
                followingActions.addAll(queued)
            }
            pendingSymbol=null
            commit()
        } finally {
            if(connection!=null) {
                // The selection change after this batch belongs to the text
                // just committed, so wait before starting new composition.
                deferFollowingActions=true
                connection.endBatchEdit()
                main.postDelayed(replayFollowingActions,60)
            } else drainFollowingKeys()
        }
    }

    private fun commitSymbol(s:String,paired:Boolean) {
        assoc = emptyList()
        if(!zh && s.firstOrNull() in listOf('.',',','!','?',':',';') && ic()?.getTextBeforeCursor(1,0)?.toString()==" ")ic()?.let {connection ->
            if(remoteEditor() && !passwordField)remoteHistory.delete(connection) else EditorDeletion.delete(connection,currentInputEditorInfo?.packageName)
        }
        val pairEnabled=!passwordField && paired && V3Prefs.enabled(this,V3Prefs.PAIRS) && sink==Sink.NONE
        val closer=if(pairEnabled)PairedSymbols.closing(s) else null
        val output=s+closer.orEmpty()
        commitOut(output+if(!zh && V3Prefs.enabled(this,V3Prefs.PUNCT_SPACE,false) && s in listOf(".",",","!","?",":",";"))" " else "")
        // 打了 @：联想常见邮箱后缀
        if (!passwordField && s == "@" && sink == Sink.NONE && V3Prefs.enabled(this,V3Prefs.EMAIL)) assoc = EMAIL_SUFFIXES
        val closeLength=if(pairEnabled)PairedSymbols.closingLength(output) else 0
        if (closeLength>0) {
            ic()?.let { connection ->
                val ext = connection.getExtractedText(ExtractedTextRequest(), 0)
                if (ext != null && ext.selectionEnd >= closeLength) {
                    val middle = ext.startOffset + ext.selectionEnd - closeLength
                    if (!connection.setSelection(middle, middle)) moveCursor(-1)
                } else moveCursor(-1)
            }
        }
        renderBar()
        updateAutoCaps()
    }

    private fun restorePick(): Boolean {
        if(picks.isEmpty()) return false
        val picked=picks.removeAt(picks.lastIndex)
        seq.addAll(0,picked.keys)
        pyCaret=-1
        val saved=picked.before
        if(saved!=null && saved.keys==seq && saved.abbr==flag(Prefs.ABBR) &&
            saved.fuzzy==Lexicon.fuzzyRules && saved.expanded==(panel==Panel.CANDS)) {
            // Cancel suffix work and restore the exact pre-selection frame synchronously.
            candidateRevision++;candidateTask?.cancel(false);candidateFrame=null;candidatesPending=false
            cands=saved.candidates;resolvedKeys=saved.keys
            tabPresentationRaw=saved.displayRaw;tabPresentation=saved.display
            renderBar()
            updateExpandedCandidates()
        } else refreshComposing()
        return true
    }

    private fun delete() {
        if(assoc.isNotEmpty() && !V3Prefs.enabled(this,V3Prefs.DELETE_DIRECT)) {assoc=emptyList();renderBar();return}
        if(glideSpelling && zh && V3Prefs.enabled(this,V3Prefs.GLIDE_WORD_DELETE,false) && seq.isNotEmpty() && picks.isEmpty()) {
            val reading=cands.firstOrNull()?.py.orEmpty().split(' ','\'').filter {it.isNotEmpty()}
            val raw=seq.joinToString("")
            val last=reading.lastOrNull()
            val count=if(last!=null && raw.endsWith(last))last.length else Lexicon.decode(seq.filter {it!="'"}).lastOrNull()?.length ?: 1
            repeat(count.coerceIn(1,seq.size)) {seq.removeAt(seq.lastIndex)}
            while(seq.lastOrNull()=="'")seq.removeAt(seq.lastIndex)
            if(seq.isEmpty()) {resetComposing();composeOut("");renderBar()} else refreshComposing()
            return
        }
        // Keep explicit edits inside the pinyin tab local to its caret.
        if (picks.isNotEmpty() && pyCaret !in 0 until seq.size) {
            if (V3Prefs.enabled(this, V3Prefs.COMPOSE_RESTORE)) {
                restorePick()
                return
            }
            if (seq.isNotEmpty()) {
                seq.removeAt(seq.lastIndex)
                while (seq.lastOrNull() == "'") seq.removeAt(seq.lastIndex)
                if (seq.isEmpty()) finishPicks() else refreshComposing()
                return
            }
        }
        if (pyCaret in 0 until seq.size) {
            // 拼音条中间有光标：删光标前的那个键
            if (pyCaret == 0) return
            seq.removeAt(pyCaret - 1)
            pyCaret--
            if (seq.isEmpty()) pyCaret = -1
            if (seq.isEmpty() && picks.isEmpty()) {
                resetComposing(); composeOut(""); if (panel == Panel.CANDS) closePanel(render = false); renderBar()
            } else refreshComposing()
            return
        }
        if (seq.isNotEmpty() || picks.isNotEmpty()) {
            if (seq.isNotEmpty()) seq.removeAt(seq.lastIndex)
            if (seq.isNotEmpty() && seq.last() == "'") seq.removeAt(seq.lastIndex)
            if (seq.isEmpty() && picks.isNotEmpty()) {val picked=picks.removeAt(picks.lastIndex);if(V3Prefs.enabled(this,V3Prefs.COMPOSE_RESTORE))seq.addAll(picked.keys)}
            if (seq.isEmpty()) {
                resetComposing()
                composeOut("")
                if (sink == Sink.NONE) ic()?.finishComposingText()
                if (panel == Panel.CANDS) closePanel(render = false)
                renderBar()
            } else refreshComposing()
            return
        }
        assoc = emptyList()
        if (sink != Sink.NONE) {
            if(sinkStrip.localEditing) {
                sinkStrip.deleteAtCursor();renderBar();updateAutoCaps();return
            }
            if (sinkBuf.isNotEmpty()) sinkBuf.setLength(Character.offsetByCodePoints(sinkBuf, sinkBuf.length, -1))
            sinkStrip.value = sinkBuf.toString()
            sinkStrip.refresh()
            onSinkChanged()
        } else {
            checkpoint(typing = true, group="delete")
            deleteCommitted()
        }
        renderBar()
        updateAutoCaps()
    }

    private fun deleteCommitted() {
        val connection=ic() ?: return
        connection.finishComposingText()
        if(remoteEditor() && !passwordField) remoteHistory.delete(connection)
        else EditorDeletion.delete(connection, currentInputEditorInfo?.packageName)
        vault.backspace()
        if(V3Prefs.enabled(this,V3Prefs.HOMOPHONE,false) && lastHomophone.isNotEmpty()) {
            val reading=Lexicon.pinyinOf(lastHomophone).replace(" ","")
            if(reading.isNotEmpty()) candidateWorker.execute {
                val words=PinyinEngine.candidates(reading.map {it.toString()},true).map {it.w}.filter {it.length==1 && it!=lastHomophone}.distinct().take(20)
                main.post {if(seq.isEmpty()) {assoc=words;renderBar()}}
            }
            lastHomophone=""
        }
    }

    private fun tapShift() {
        if (splitMode()) {
            // 中文组字时大写键是“分词”
            addSeparator()
            refreshComposing()
            return
        }
        if(!zh) {
            shift=if(V3Prefs.enabled(this,V3Prefs.CAPS_LOCK,false)) {if(shift==2)0 else 2} else {if(shift>0)0 else 1}
            englishCapsLocked=shift==2
            lastShiftTap=0L
            keyboard.shiftState=shift;keyboard.invalidate()
            return
        }
        val now = SystemClock.uptimeMillis()
        shift = when {
            shift == 2 -> 0
            lastShiftTap != 0L && now - lastShiftTap < 350 -> 2
            shift == 1 -> 0
            else -> 1
        }
        lastShiftTap = if (shift == 2) 0L else now
        keyboard.shiftState = shift
        keyboard.invalidate()
    }

    private fun toggleLang() {
        if(passwordField && sink==Sink.NONE) return
        keyboard.transitionNormalKeys { toggleLangNow() }
    }
    private fun toggleLangNow() {
        if (seq.isNotEmpty()) commitRaw()
        lastShiftTap = 0L
        zh = !zh
        if (!forceEn) prefs.edit().putBoolean(Prefs.ZH, zh).apply()
        shift = if(!zh && englishCapsLocked && V3Prefs.enabled(this,V3Prefs.CAPS_LOCK,false))2 else 0
        layer = Layer.LETTERS
        assoc = emptyList()
        render(animateKeys=false)
        updateAutoCaps()
    }

    private fun updateAutoCaps() {
        if (zh || layer != Layer.LETTERS || shift == 2 || root == null || sink != Sink.NONE) return
        val info = currentInputEditorInfo ?: return
        val caps = ic()?.getCursorCapsMode(info.inputType) ?: 0
        val want = if (caps != 0 && V3Prefs.enabled(this,V3Prefs.CAPS)) 1 else 0
        if (want != shift) {
            shift = want
            keyboard.shiftState = shift
            keyboard.invalidate()
        }
    }

    private fun performEnter() {
        if(remoteEditor()) {commitOut("\n");return}
        val info = currentInputEditorInfo
        val action = (info?.imeOptions ?: 0) and EditorInfo.IME_MASK_ACTION
        val noAction = info == null || (info.imeOptions and EditorInfo.IME_FLAG_NO_ENTER_ACTION) != 0 ||
            action == EditorInfo.IME_ACTION_NONE || action == EditorInfo.IME_ACTION_UNSPECIFIED
        if (noAction) sendKeyChar('\n') else ic()?.performEditorAction(action)
    }

    // —————————— 候选栏与工具栏 ——————————

    override fun onCandidate(index: Int) {
        if(pendingSymbol!=null || deferFollowingActions)return
        if (cands.getOrNull(index)!=null || handCands.getOrNull(index)!=null) buzz()
        if(candidatesPending && seq.isNotEmpty() && resolvedKeys!=seq.toList()) {pendingPick=candidateRevision to index;pendingExplicitChoice=true;return}
        if (seq.isEmpty() && (panel == Panel.HAND || panel == Panel.HAND_CANDS)) {
            val w = handCands.getOrNull(index) ?: return
            val connection=if(sink==Sink.NONE)ic() else null
            connection?.beginBatchEdit()
            try {
                // Restore the original selection before committing the chosen candidate once.
                cancelHandwritingPreview()
                commitOut(w)
                connection?.finishComposingText()
            } finally {
                connection?.endBatchEdit()
            }
            handCands = emptyList()
            handView?.clear()
            if (panel == Panel.HAND_CANDS) { panel = Panel.HAND; buildPanel() }
            assoc = if (flag(Prefs.ASSOC)) Lexicon.assoc(w) else emptyList()
            renderBar()
            return
        }
        cands.getOrNull(index)?.let { pick(it,explicitChoice=index>0) }
    }

    override fun onAssoc(index: Int) {
        if (sink == Sink.TRANSLATE && langPicking) {
            val t = Translator.TARGETS.getOrNull(index) ?: return
            prefs.edit().putString(Prefs.TRANS_TO, t.first).apply()
            setLanguagePicker(false)
            sinkStrip.chip = t.second
            sinkStrip.refresh()
            renderBar()
            onSinkChanged()
            return
        }
        val w = assoc.getOrNull(index) ?: return
        if(statsAllowed())TypingStats.of(this).association(statsChars(w))
        commitOut(w)
        assoc = if(prefs.getInt(V3Prefs.ASSOC_CONTINUOUS,1)==1) Lexicon.assoc(w) else emptyList()
        renderBar()
    }

    override fun onDismissAssoc() {
        if (langPicking) { setLanguagePicker(false); return }
        assoc = emptyList()
        renderBar()
    }

    private fun captureSmsCode(clip:ClipData) {
        val code=SmsCodeClipboard.read(this,clip)?.takeUnless {it.id==dismissedSmsId}
        pendingClipText=null;pendingSmsCode=code
        if(code!=null) {
            val id=code.id
            main.postDelayed({if(pendingSmsCode?.id==id){pendingSmsCode=null;renderBar()}},(code.expires-System.currentTimeMillis()).coerceAtLeast(0))
        }
        if(root!=null)renderBar()
    }
    override fun onSmsCodePaste() {
        if(passwordField)return
        val expected=pendingSmsCode ?: return
        val current=SmsCodeClipboard.read(this,runCatching {clipboard?.primaryClip}.getOrNull())
        if(current?.id!=expected.id){pendingSmsCode=null;renderBar();return}
        dismissedSmsId=expected.id;pendingSmsCode=null
        externalInsertion=true
        try {typeChar(current.value)} finally {externalInsertion=false;vault.invalidateCapture()}
        renderBar()
    }
    override fun onSmsCodeDismiss() {pendingSmsCode?.let {dismissedSmsId=it.id};pendingSmsCode=null;renderBar()}

    override fun onClipPaste() {
        val t = pendingClipText ?: return
        dismissedClipText = t
        pendingClipText = null
        pasteExternal(t)
    }

    override fun onClipSplit() {
        val t = pendingClipText ?: return
        dismissedClipText = t
        pendingClipText = null
        openTokens(t, fromBar = true)
    }

    private fun openTokens(text: String, fromBar: Boolean) {
        tokens = Lexicon.segment(text)
        tokenSelection = BooleanArray(tokens.size)
        tokensFromBar = fromBar
        openPanel(Panel.TOKENS)
    }

    private fun previewTokens(text: String) {
        if (text.isEmpty()) {
            cancelTokenPreview()
        } else if (sink != Sink.NONE) {
            sinkStrip.previewInput(text)
        } else if (!passwordField && !remoteEditor()) {
            val draft = tokenDraft ?: ic()?.let { VoiceDraft(it) } ?: return
            if (draft.preview(text, underline = true)) tokenDraft = draft
        }
    }

    private fun cancelTokenPreview() {
        val draft = tokenDraft
        tokenDraft = null
        draft?.cancel()
        if (sink != Sink.NONE) sinkStrip.cancelInputPreview()
    }

    override fun onClipDismiss() {
        pendingClipText?.let { dismissedClipText = it }
        pendingClipText = null
        renderBar()
    }

    override fun onTool(id: String) {
        if(pendingSymbol!=null || deferFollowingActions) {followingActions.addLast {onTool(id)};return}
        if(passwordField && id in setOf("voice","translate","handwrite","undo","redo")) { message("密码框中不使用此工具");return }
        // 再点一下正打开的那个工具：收起面板回到键盘
        if (panel != Panel.NONE && id == activeTool()) { closePanel(); return }
        runFeature(id)
    }

    private fun newAdjustment(): Adjust = FloatingKeyboard.newAdjustment(this,floating)

    private fun runFeature(id: String) {
        if(sink==Sink.TRANSLATE) {
            NativeMotion.swap(root,col) {
                ic()?.setComposingText("",1)
                endSink(false)
                if(id=="translate") render() else runFeatureNow(id)
            }
        } else runFeatureNow(id)
    }

    private fun runFeatureNow(id: String) {
        // The menu and toolbar share this gate; neither may start a password tool.
        if(passwordField && id in setOf("voice","translate","handwrite","undo","redo")) { message("密码框中不使用此工具");return }
        if (panel == Panel.VOICE && id != "voice") closePanel()
        when (id) {
            "more" -> openPanel(Panel.MORE)
            "settings" -> openSettings()
            "adjust" -> {
                adjustmentDrafts.clear()
                adj = newAdjustment().also {adjustmentDrafts[floating]=it}
                openPanel(Panel.ADJUST)
            }
            "arrange" -> openPanel(Panel.ARRANGE)
            "layout" -> openPanel(Panel.LAYOUT)
            "emoji" -> openPanel(Panel.EMOJI)
            "clip" -> openPanel(Panel.CLIP)
            "edit" -> {
                if(remoteEditor() && !passwordField && sink==Sink.NONE) {
                    if(seq.isNotEmpty()) finalizeComposing()
                    startSink(Sink.REMOTE_EDIT,remoteHistory.currentText(),null)
                    openPanel(Panel.EDIT)
                } else openPanel(Panel.EDIT)
            }
            "skin" -> openPanel(Panel.SKIN)
            "undo" -> undo()
            "redo" -> redo()
            "voice" -> when {
                sink != Sink.NONE -> voiceIntoSink()
                panel == Panel.VOICE -> if (afterVoiceStop == null && voicePolishJob == null) finishVoicePanel()
                else -> { openVoice(); if (micGranted()) toggleListening() }
            }
            "handwrite" -> { handCands = emptyList(); openPanel(Panel.HAND) }
            "translate" -> startSink(Sink.TRANSLATE, "", null)
            "float" -> setFloating(!floating)
            "lang" -> { if(panel!=Panel.NONE) closePanel();toggleLang() }
        }
    }

    override fun onExpand() {
        if(pendingSymbol!=null || deferFollowingActions)return
        if (panel == Panel.HAND || panel == Panel.HAND_CANDS) {
            openPanel(if (panel == Panel.HAND) Panel.HAND_CANDS else Panel.HAND)
            return
        }
        if(panel==Panel.CANDS) closePanel() else {
            val keys=seq.toList()
            val ready=resolvedKeys==keys
            candidateFilter=0
            openPanel(Panel.CANDS)
            if(ready && canExpandLookup(keys)) refreshComposing(originalOrder=cands)
        }
    }

    /** 危险操作点两次才生效 */
    private fun confirm(what: String, hint: String, action: () -> Unit) {
        val now = SystemClock.uptimeMillis()
        if (confirmWhat == what && now - confirmAt < 3000) {
            confirmWhat = ""
            action()
        } else {
            confirmWhat = what
            confirmAt = now
            message(hint)
        }
    }

    private fun openSettings(extra: String? = null) {
        startActivity(Intent(this, SettingsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).apply { if (extra != null) putExtra(extra, true) })
        requestHideSelf(0)
    }

    private fun switchToVoiceIme() {
        val imm = getSystemService(InputMethodManager::class.java)
        if (Build.VERSION.SDK_INT >= 28 && imm != null) {
            for ((imi, subtypes) in imm.shortcutInputMethodsAndSubtypes) {
                val st = subtypes.firstOrNull { it.mode == "voice" } ?: continue
                switchInputMethod(imi.id, st)
                return
            }
        }
        message("没有找到语音输入法")
    }

    // —————————— 语音 ——————————

    private fun micGranted() = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private fun openVoice() {
        voiceCollapsed=false;updateContinuousStrip()
        if(passwordField && sink==Sink.NONE) {message("密码框中不使用语音");return}
        cancelVoiceDraft()
        voiceToSink = false
        voiceStatus = if (micGranted()) "已暂停" else "需要麦克风权限"
        openPanel(Panel.VOICE)
    }

    private fun cancelVoiceDraft() {
        voicePolishJob?.cancel();voicePolishJob=null;voicePolishOriginal=null;voicePolishApproved=null
        voiceDraft?.cancel(); voiceDraft = null; voiceBefore = null
        voiceStart = null
        voiceText.setLength(0)
        voicePartial = ""
    }

    private fun voiceSegmentDraft(): VoiceDraft? {
        voiceDraft?.let { return it }
        val editor = ic() ?: return null
        val draft = VoiceDraft(editor)
        val start = voiceStart
        if (start != null && draft.start != null && draft.start != start + voiceText.length) {
            voiceText.setLength(0)
            voicePolishOriginal = null
            voicePolishApproved = null
        }
        if (voiceText.isEmpty()) {
            voiceStart = draft.start
            voiceBefore = snap()
        }
        voiceDraft = draft
        return draft
    }

    private fun voiceSegmentText(text: String): String =
        if (!zh && voiceText.lastOrNull()?.isLetterOrDigit() == true &&
            text.firstOrNull()?.isLetterOrDigit() == true) " $text" else text

    private fun previewVoice(text: String) {
        voicePartial = text
        if (!voiceToSink && text.isNotBlank() && !passwordField) {
            voiceSegmentDraft()?.preview(voiceSegmentText(text))
        }
        updateVoice()
    }

    private fun commitVoiceSegment(raw: String) {
        voicePartial = ""
        if (voiceToSink) {
            if (raw.isNotBlank()) commitOut(raw)
            return
        }
        val text = VoiceText.process(this,raw)
        if (text.isBlank() || passwordField) {
            voiceDraft?.cancel()
            voiceDraft = null
            return
        }
        val draft = voiceSegmentDraft() ?: return
        val output = voiceSegmentText(text)
        voiceDraft = null
        if (!draft.commit(output)) {
            draft.confirm()
            voiceStatus = "无法写入语音文字，请重新定位输入框"
            return
        }
        if (voiceText.isEmpty()) {
            voiceBefore?.let { if (undoStack.lastOrNull() != it) undoStack.addLast(it) }
            while (undoStack.size > 100) undoStack.removeFirst()
        }
        voiceBefore = null
        voiceText.append(output)
        redoStack.clear()
        lastTypingAt = 0L
        if (remoteEditor()) remoteHistory.inserted(output,typing=false)
        if (statsAllowed()) {
            val count = statsChars(text)
            TypingStats.of(this).commit("voice",count)
            Prefs.addTyped(this,count)
        }
    }

    private fun updateVoice() {
        if (voiceToSink) {
            sinkStrip.composing = voicePartial
            sinkStrip.micOn = listening
            sinkStrip.refresh()
            if (sink == Sink.TRANSLATE) {
                transStatus = if (listening) "● $voiceStatus" else voiceStatus
                renderBar()
                if (voicePartial.isNotEmpty()) onSinkChanged()
            }
            return
        }
        voiceBoard?.update(voiceText.isNotEmpty(), voiceStatus, listening,
            listening || voiceStarting || voiceWantsListening, afterVoiceStop != null)
        voiceBoard?.polishState(voicePolishJob!=null,voicePolishOriginal!=null)
        updateContinuousStrip()
    }

    /** 四川话：系统识别服务没有四川话模型时，退回普通话 */
    private var sichuanFallback = false

    private fun voiceLanguage(): String = when {
        !zh && !voiceToSink -> "en-US"
        prefs.getInt(Prefs.VOICE_LANG, 0) == 1 && !sichuanFallback -> "zh-CN-sichuan"
        else -> "zh-CN"
    }

    private fun toggleListening() {
        if (afterVoiceStop != null) return
        if(voicePolishJob!=null) {voicePolishJob?.cancel();voicePolishJob=null}
        voicePolishOriginal=null;voicePolishApproved=null
        if(passwordField) { stopListening(); return }
        if (listening || voiceWantsListening || voiceStarting) {
            finishRecognition { requestVoicePolish() }
            return
        }
        if (!micGranted()) { openSettings("grant_mic"); return }
        voiceWantsListening = true;voiceStarting=true
        updateVoice()
        VoiceSupport.start(this,onStop={
            continuousVoiceOwner=null;voiceCollapsed=false;stopListening();cancelVoiceDraft();updateContinuousStrip();updateVoice()
        }) {ready ->
            voiceStarting=false
            if(!voiceWantsListening)return@start
            if(ready)beginListening() else {
                voiceWantsListening=false;voiceStatus="无法启动语音，请检查麦克风权限后重试";updateVoice()
            }
        }
    }

    private fun beginListening() {
        val dialect = if (!zh && !voiceToSink) 0 else prefs.getInt(Prefs.VOICE_LANG, 0)
        if ((zh || voiceToSink) && OfflineVoice.available(this, dialect)) { startOfflineVoice(dialect); return }
        if ((zh || voiceToSink) || prefs.getBoolean(Prefs.VOICE_OFFLINE, false)) {
            voicePack()
            return
        }
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            VoiceSupport.stop(this@KongyeIME)
            voiceWantsListening = false
            voiceStatus = "这台手机没有可用的语音识别服务"
            updateVoice(); return
        }
        speech?.destroy()
        val sr = SpeechRecognizer.createSpeechRecognizer(this).also { speech = it }
        val generation = ++systemVoiceGeneration
        sr.setRecognitionListener(object : RecognitionListener {
            private fun active() = generation == systemVoiceGeneration && listening
            override fun onReadyForSpeech(params: Bundle?) {
                if (!active() || afterVoiceStop != null) return
                voiceCapturing = true
                // 系统的谷歌语音服务没有四川话模型，会按普通话识别（不报错），这里如实说明
                voiceStatus = if (!voiceToSink && zh && prefs.getInt(Prefs.VOICE_LANG, 0) == 1) "请说话（识别服务没有四川话模型，按普通话识别，口音重时可能不准）"
                else "正在聆听..."
                updateVoice()
            }
            override fun onBeginningOfSpeech() = Unit
            override fun onRmsChanged(rmsdB: Float) { if (active()) voiceBoard?.level(rmsdB) }
            override fun onBufferReceived(buffer: ByteArray?) = Unit
            override fun onEndOfSpeech() {
                if (!active()) return
                voiceCapturing = false
                voiceStatus = "正在识别…"
                updateVoice()
            }
            override fun onError(error: Int) {
                if (!active()) return
                val retryDialect = afterVoiceStop == null && voiceWantsListening &&
                    (error == 12 || error == 13) && voiceLanguage() == "zh-CN-sichuan"
                VoiceSupport.stop(this@KongyeIME)
                listening = false
                voiceCapturing = false
                voiceWantsListening = false
                if (retryDialect) {
                    // ERROR_LANGUAGE_NOT_SUPPORTED / UNAVAILABLE：没有四川话模型，用普通话再来
                    sichuanFallback = true
                    voiceStatus = "识别服务没有四川话模型，已改用普通话识别"
                    updateVoice()
                    main.postDelayed({ if (generation == systemVoiceGeneration && panel == Panel.VOICE) toggleListening() }, 200)
                    return
                }
                // 部分系统在流式识别结束后可能回调 ERROR_NO_MATCH，先保留已识别出的 partial 文字
                val leftover = voicePartial.trim()
                voiceStatus = when (error) {
                    SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> if (leftover.isNotEmpty()) "已暂停" else "没听到声音，可以再试一次"
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "需要麦克风权限"
                    SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "语音识别需要联网，网络不通"
                    else -> "语音识别出错（$error），可以再试一次"
                }
                commitVoiceSegment(leftover)
                updateVoice()
                completeVoiceStop()
            }
            override fun onResults(results: Bundle?) {
                if (!active()) return
                listening = false
                voiceCapturing = false
                val recognized = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
                val text = recognized.ifEmpty { voicePartial }
                if (voiceToSink) {
                    voiceWantsListening = false
                    voiceStatus = if (text.isNotEmpty()) "语音识别完成，正在翻译…" else "点麦克风继续说话"
                    commitVoiceSegment(text)
                    updateVoice()
                    completeVoiceStop()
                    return
                }
                voiceStatus = "已暂停"
                commitVoiceSegment(text)
                val resume = voiceWantsListening && text.isNotBlank() && (panel == Panel.VOICE || voiceCollapsed)
                voiceWantsListening = resume
                updateVoice()
                if (completeVoiceStop()) return
                // 连续说：一句识别完接着听
                if (resume) main.postDelayed({
                    if (generation == systemVoiceGeneration && voiceWantsListening && !listening) {
                        voiceWantsListening = false
                        toggleListening()
                    }
                }, 150)
            }
            override fun onPartialResults(partialResults: Bundle?) {
                if (active()) previewVoice(partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty())
            }
            override fun onEvent(eventType: Int, params: Bundle?) = Unit
        })
        val lang = voiceLanguage()
        sr.startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, lang)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, lang)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            // 装好了离线语音包就优先离线识别（断网也能用）
            if (prefs.getBoolean(Prefs.VOICE_OFFLINE, false)) putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
        })
        listening = true
        voiceStatus = "正在聆听..."
        updateVoice()
    }

    /** 独立离线引擎：普通话小模型与川渝专用模型分别校验、安装。 */
    private fun voicePack() {
        val dialect = prefs.getInt(Prefs.VOICE_LANG, 0)
        stopListening()
        OfflineVoice.install(applicationContext, dialect) { ready, status ->
            if (ready) prefs.edit().putBoolean(Prefs.VOICE_OFFLINE, true).apply()
            voiceStatus = status; updateVoice()
        }
    }

    private fun startOfflineVoice(dialect: Int) {
        speech?.destroy(); speech = null
        lateinit var session: OfflineVoice.Session
        session = OfflineVoice.Session(applicationContext, dialect, object : OfflineVoice.Listener {
            private fun active() = offlineSpeech === session
            override fun status(text: String) {
                if (!active() || afterVoiceStop != null) return
                voiceCapturing = text.startsWith("正在聆听")
                voiceStatus = text
                updateVoice()
            }
            override fun level(value: Float) { if (active()) voiceBoard?.level(value) }
            override fun spectrum(values: FloatArray) { if (active()) voiceBoard?.spectrum(values) }
            override fun partial(text: String) { if (active()) previewVoice(text) }
            override fun result(text: String) {
                if (!active()) return
                commitVoiceSegment(text)
                updateVoice()
            }
            override fun stopped(error: String?) {
                if (!active()) return
                offlineSpeech = null; listening = false; voiceWantsListening = false
                voiceCapturing = false
                VoiceSupport.stop(this@KongyeIME)
                voiceStatus = error ?: "已暂停"
                if (voicePartial.isNotBlank()) commitVoiceSegment(voicePartial)
                updateVoice()
                if (!completeVoiceStop() && error == null) requestVoicePolish()
            }
        })
        offlineSpeech = session; listening = true
        // Keep the same status across startup and dialect restarts. Errors and
        // pause still have distinct states, but no transient opening label.
        voiceStatus = "正在聆听..."
        updateVoice(); session.start()
    }

    private fun collapseVoice() {
        continuousVoiceOwner=currentInputEditorInfo?.packageName
        voiceCollapsed=true
        closePanel(animate=false)
        updateContinuousStrip()
    }
    private fun expandVoice() {
        if (!voiceCollapsed || passwordField) return
        continuousVoiceOwner = null
        voiceCollapsed = false
        updateContinuousStrip()
        openPanelNow(Panel.VOICE)
        updateVoice()
    }

    private fun updateContinuousStrip() {
        if(!::col.isInitialized)return
        var strip=continuousStrip
        if(strip==null && !voiceCollapsed) return
        val d = resources.displayMetrics.density
        if(strip==null) {
            strip=FrameLayout(this).apply {visibility=View.GONE}
            continuousStrip=strip
            col.addView(strip,col.indexOfChild(barHost),LinearLayout.LayoutParams(MATCH_PARENT,(44*d).toInt()))
        }
        val previouslyCollapsed=strip.visibility==View.VISIBLE
        strip.visibility=if(voiceCollapsed)View.VISIBLE else View.GONE
        if(!voiceCollapsed) {
            if(previouslyCollapsed) {
                barHost.visibility=View.VISIBLE
                applyFloat();applySkin(rebuildPanel=false)
            }
            return
        }
        keyboard.visibility=View.GONE;barHost.visibility=View.GONE;bottomStrip.visibility=View.GONE;panelHost.visibility=View.GONE;floatBar.visibility=View.GONE
        tab.visibility=View.GONE;candidatePreview.visibility=View.GONE
        val params=col.layoutParams as FrameLayout.LayoutParams
        val desiredWidth=(resources.displayMetrics.widthPixels*.80f).toInt().coerceAtMost((360*d).toInt())
        if(params.width!=desiredWidth || params.gravity!=(Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL)) {
            params.width=desiredWidth;params.gravity=Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL;col.layoutParams=params
            col.translationX=0f;col.translationY=0f
        }
        col.setPadding(0,0,0,0)
        col.background=roundBg(skin.keyBg,22*d).apply {
            setStroke(d.toInt().coerceAtLeast(1),withAlpha(skin.keyFg,22))
        }
        col.elevation=4*d
        if (strip.childCount > 0) {
            refreshContinuousControls()
            return
        }
        val controls = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            val inset = (8*d).toInt()
            setPaddingRelative(inset,0,inset,0)
        }
        strip.addView(controls,FrameLayout.LayoutParams(MATCH_PARENT,MATCH_PARENT).apply {
            topMargin = (9*d).toInt()
            bottomMargin = (1*d).toInt()
        })
        fun icon(action: () -> Unit) = android.widget.ImageView(this).apply {
            scaleType = android.widget.ImageView.ScaleType.CENTER_INSIDE
            val pad = (7 * d).toInt()
            setPadding(pad, pad, pad, pad)
            setOnClickListener { action() }
        }
        val handle = object : View(this) {
            private val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
            override fun onDraw(canvas: android.graphics.Canvas) {
                paint.color = withAlpha(skin.keyFg,96)
                val center = width/2f
                canvas.drawRoundRect(center-12*d,4*d,center+12*d,6*d,d,d,paint)
            }
        }.apply {
            isClickable = true
            contentDescription = "拖动听写条"
        }
        var fingerX=0f;var fingerY=0f;var originX=0f;var originY=0f
        handle.setOnTouchListener {_,event ->
            when(event.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN -> {fingerX=event.rawX;fingerY=event.rawY;originX=col.translationX;originY=col.translationY}
                android.view.MotionEvent.ACTION_MOVE -> {
                    val window=root
                    if(window!=null) {
                        val room=((window.width-col.width)/2).coerceAtLeast(0).toFloat()
                        col.translationX=(originX+event.rawX-fingerX).coerceIn(-room,room)
                        col.translationY=(originY+event.rawY-fingerY).coerceIn(-(window.height-col.height).coerceAtLeast(0).toFloat(),0f)
                        window.requestLayout()
                    }
                }
            }
            true
        }
        strip.addView(handle,FrameLayout.LayoutParams((76*d).toInt(),(14*d).toInt(),Gravity.TOP or Gravity.CENTER_HORIZONTAL))
        val sideWidth = (44*d).toInt()
        val expand = icon { expandVoice() }.apply {
            setImageResource(R.drawable.ic_chevron_up)
            contentDescription = "展开听写面板"
        }
        continuousExpandButton = expand
        controls.addView(expand,LinearLayout.LayoutParams(sideWidth,MATCH_PARENT))
        val status = android.widget.TextView(this).apply {
            textSize = 13f
            gravity = Gravity.CENTER
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            typeface = android.graphics.Typeface.create("sans-serif-medium",android.graphics.Typeface.NORMAL)
            setOnClickListener { if (voicePolishJob != null) polishVoiceAction() else toggleListening() }
        }
        continuousStatusButton = status
        controls.addView(status,LinearLayout.LayoutParams(0,MATCH_PARENT,1f))
        continuousStopButton = icon {
            finishRecognition {
                continuousVoiceOwner = null
                voiceCollapsed = false
                cancelVoiceDraft()
                updateContinuousStrip()
            }
        }.apply {
            setImageResource(R.drawable.ic_voice_stop)
            contentDescription = "停止听写"
            tooltipText = "停止"
        }.also { controls.addView(it,LinearLayout.LayoutParams(sideWidth,MATCH_PARENT)) }
        refreshContinuousControls()
    }

    private fun refreshContinuousControls() {
        continuousExpandButton?.setColorFilter(skin.modFg)
        continuousStopButton?.setColorFilter(skin.modFg)
        continuousStatusButton?.apply {
            val busy = voicePolishJob != null
            val stopping = afterVoiceStop != null
            val recognizing = stopping || listening && !voiceCapturing && voiceStatus.startsWith("正在识别")
            val preparing = voiceStarting || voiceWantsListening || listening
            val label = when {
                busy -> "正在润色…"
                recognizing -> "正在识别…"
                voiceCapturing -> "● 正在录音"
                preparing -> "准备录音…"
                else -> "已暂停"
            }
            if (text != label) text = label
            setTextColor(if (voiceCapturing) skin.candHi else skin.modFg)
            isEnabled = !stopping
            contentDescription = when {
                stopping -> "正在识别"
                busy -> "正在润色，点按取消"
                recognizing -> "正在识别，点按停止"
                voiceCapturing -> "正在录音，点按暂停"
                preparing -> "准备录音，点按停止"
                else -> "已暂停，点按继续"
            }
        }
    }

    private fun polishText():String = if(voiceToSink) VoiceText.process(this,sinkBuf.toString()) else voiceText.toString()

    private fun replaceVoiceTranscript(expected: String, replacement: String): Boolean {
        if (voiceToSink) {
            if (sink == Sink.NONE || polishText() != expected) return false
            sinkBuf.setLength(0);sinkBuf.append(replacement)
            sinkStrip.value=replacement;sinkStrip.refresh();onSinkChanged()
            return true
        }
        val start = voiceStart ?: return false
        val editor = ic() ?: return false
        val state = editor.getExtractedText(ExtractedTextRequest(),0) ?: return false
        val offset = start - state.startOffset
        val end = offset + expected.length
        val content = state.text ?: return false
        if (state.selectionStart != end || state.selectionEnd != end ||
            offset < 0 || end > content.length || content.subSequence(offset,end).toString() != expected) return false
        editor.beginBatchEdit()
        try {
            if (!editor.setComposingRegion(start,start+expected.length)) return false
            if (!editor.commitText(replacement,1)) {
                editor.finishComposingText()
                return false
            }
        } finally { editor.endBatchEdit() }
        voiceText.setLength(0);voiceText.append(replacement)
        return true
    }

    private fun requestVoicePolish(): Boolean {
        if(!V3Prefs.enabled(this,V3Prefs.VOICE_POLISH,false) || voicePolishJob!=null)return false
        val source=polishText()
        if(source.isBlank() || source==voicePolishApproved)return false
        if(if(voiceToSink) sink==Sink.NONE else voiceStart==null)return false
        voicePolishOriginal=null
        lateinit var job:VoicePolish.Job
        job=VoicePolish.request(applicationContext,source,{status->
            if(voicePolishJob===job){voiceStatus=status;updateVoice()}
        }) {result->
            if(voicePolishJob!==job)return@request
            voicePolishJob=null
            if(polishText()!=source){voiceStatus="内容已变化，已保留当前文字";updateVoice();return@request}
            result.onSuccess {text->
                if (replaceVoiceTranscript(source,text)) {
                    voicePolishOriginal=source;voicePolishApproved=text
                    voiceStatus="已润色，可恢复原文"
                } else {
                    voicePolishApproved=source
                    voiceStatus="输入位置或内容已变化，已保留当前文字"
                }
            }.onFailure {
                voicePolishApproved=source
                voiceStatus=(it.message ?: "润色失败")+"，原文已保留"
            }
            updateVoice()
        }
        voicePolishJob=job;voiceStatus=if(prefs.getInt(VoicePolish.MODE,0)==1)"正在联网润色…" else "正在准备离线润色…";updateVoice()
        return true
    }
    private fun polishVoiceAction() {
        voicePolishJob?.let {it.cancel();voicePolishJob=null;voicePolishApproved=polishText();voiceStatus="已取消润色，原文已保留";updateVoice();return}
        voicePolishOriginal?.let {original->
            if (replaceVoiceTranscript(polishText(),original)) {
                voicePolishOriginal=null;voicePolishApproved=original
                voiceStatus="已恢复原文"
            } else voiceStatus="输入位置或内容已变化，已保留当前文字"
            updateVoice()
            return
        }
        voicePolishApproved=null
        finishRecognition { requestVoicePolish() }
    }

    private fun finishVoicePanel() {
        if (!listening && !voiceStarting && !voiceWantsListening) {
            closePanel()
            return
        }
        finishRecognition { if (!requestVoicePolish()) closePanel() }
    }

    private fun finishRecognition(after: () -> Unit) {
        if (afterVoiceStop != null) return
        voiceWantsListening = false
        if (!listening) {
            stopListening()
            updateVoice()
            after()
            return
        }
        afterVoiceStop = after
        voiceCapturing = false
        voiceStatus = "正在识别…"
        updateVoice()
        val session = offlineSpeech
        if (session != null) {
            session.stop()
            return
        }
        runCatching { speech?.stopListening() }
        main.postDelayed({
            if (afterVoiceStop === after) {
                stopListening()
                updateVoice()
                after()
            }
        }, 2000)
    }

    private fun completeVoiceStop(): Boolean {
        val after = afterVoiceStop ?: return false
        afterVoiceStop = null
        VoiceSupport.stop(this)
        updateVoice()
        after()
        return true
    }

    private fun stopListening(keepPartial: Boolean = true) {
        voiceStarting=false
        voiceCapturing=false
        VoiceSupport.stop(this@KongyeIME)
        voiceWantsListening = false
        systemVoiceGeneration++
        offlineSpeech?.cancel(); offlineSpeech = null; afterVoiceStop = null
        if (listening) runCatching { speech?.cancel() }
        listening = false
        voiceStatus = "已暂停"
        if (keepPartial && voicePartial.isNotBlank()) commitVoiceSegment(voicePartial.trim())
        else {
            voicePartial = ""
            voiceDraft?.cancel()
            voiceDraft = null
        }
    }

    // —————————— 剪贴板 ——————————

    private fun captureClip(refreshUi:Boolean=true) {
        if(passwordField) return
        if (SystemClock.uptimeMillis() < ignoreClipUntil) return
        val cm = clipboard ?: return
        val clip = runCatching { cm.primaryClip }.getOrNull() ?: return
        if(ClipboardTransfer.own(clip)) return
        if(SmsCodeClipboard.ephemeral(clip)){captureSmsCode(clip);return}
        pendingSmsCode=null
        val desc = clip.description
        if(clip.itemCount==0) return
        val item = clip.getItemAt(0) ?: return
        val uri = item.uri
        val signature=Triple(desc.timestamp,item.text?.toString(),uri?.toString())
        if(signature==lastCapturedClip) return
        if (uri != null && (0 until desc.mimeTypeCount).any { desc.getMimeType(it).startsWith("image/") }) {
            if (uri.toString() == lastImageUri) return
            lastImageUri = uri.toString()
            val mime = (0 until desc.mimeTypeCount).map { desc.getMimeType(it) }.firstOrNull { it.startsWith("image/") } ?: "image/png"
            val ext = when { mime.contains("jpeg") || mime.contains("jpg") -> "jpg"; mime.contains("gif") -> "gif"; mime.contains("webp") -> "webp"; else -> "png" }
            val dir = ClipStore.dir ?: return
            val name = "clip_${System.currentTimeMillis()}.$ext"
            val generation=clipImportGeneration
            clipImporter.execute {
                val file=File(dir,name)
                val ok=runCatching {contentResolver.openInputStream(uri)?.use {input->file.outputStream().use {input.copyTo(it)}}!=null}.getOrDefault(false)
                main.post {
                    if(ok && generation==clipImportGeneration) {
                        ClipStore.addImage(name)
                        if(panel==Panel.CLIP) buildPanel()
                    } else {file.delete();if(lastImageUri==uri.toString())lastImageUri=""}
                }
            }
        } else {
            val text = runCatching { item.coerceToText(this)?.toString() }.getOrNull()
            if (text.isNullOrBlank()) return
            ClipStore.add(text)
            val trimmed = text.trim()
            pendingClipText = trimmed
            pendingClipAt = SystemClock.uptimeMillis()
            if (trimmed == dismissedClipText) dismissedClipText = ""
        }
        lastCapturedClip=signature
        if(refreshUi) {if (panel == Panel.CLIP) buildPanel() else renderBar()}
    }

    private fun pasteExternal(text:String) {
        vault.invalidateCapture();externalInsertion=true
        try {
                pendingClipText?.let {dismissedClipText=it;pendingClipText=null}
            if(seq.isNotEmpty()) finalizeComposing()
            assoc=emptyList()
            commitOut(text);ClipStore.usedText(text)
            renderBar();updateAutoCaps()
        } finally {externalInsertion=false}
    }
    private fun pasteClip(c: Clip) {
        val f = ClipStore.imageFile(c)
        if (f == null) { pasteExternal(c.text);ClipStore.used(c);if(panel==Panel.CLIP) buildPanel();return }
        if (!f.exists()) { message("图片文件已经不在了"); return }
        val uri = FileProvider.getUriForFile(this, "com.kongye.keyboard.files", f)
        val mime = when (f.extension) { "jpg" -> "image/jpeg"; "gif" -> "image/gif"; "webp" -> "image/webp"; else -> "image/png" }
        val accepts = currentInputEditorInfo?.contentMimeTypes?.any { ClipDescription.compareMimeTypes(mime, it) } == true
        if (accepts) {
            val info = InputContentInfo(uri, ClipDescription("图片", arrayOf(mime)), null)
            if (ic()?.commitContent(info, InputConnection.INPUT_CONTENT_GRANT_READ_URI_PERMISSION, null) == true) {ClipStore.used(c);if(panel==Panel.CLIP) buildPanel();return}
        }
        grantUriPermission(currentInputEditorInfo?.packageName ?: packageName, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        if(!ClipboardTransfer.copy(clipboard,ClipData.newUri(contentResolver,"图片",uri))) {message("未能写入剪贴板，请检查写入剪贴板权限");return}
        ClipStore.used(c);if(panel==Panel.CLIP) buildPanel()
        message("这个输入框不能直接插图片，已复制到系统剪贴板，可以长按粘贴")
    }

    private fun clipAction(c: Clip, id: String) {
        when {
            id == "pin" -> ClipStore.togglePin(c)
            id == "copy" -> {
                val f = ClipStore.imageFile(c)
                val clip=if(f!=null) ClipData.newUri(contentResolver,"图片",FileProvider.getUriForFile(this,"com.kongye.keyboard.files",f))
                    else ClipData.newPlainText("空叶键盘",c.text)
                message(if(ClipboardTransfer.copy(clipboard,clip)) "已复制" else "未能写入剪贴板，请检查写入剪贴板权限")
            }
            id == "edit" -> { startSink(Sink.EDIT, c.text, c); return }
            id == "split" -> { openTokens(c.text, fromBar = false); return }
            id == "cat" -> clipPicking = if (clipPicking === c) null else c
            id.startsWith("cat:") -> {
                val cat = id.removePrefix("cat:")
                ClipStore.setCategory(c, if (cat == "无分类") null else cat)
                clipPicking = null
            }
            id == "delete" -> { ClipStore.remove(c); clipExpanded = null }
        }
        buildPanel()
    }

    // —————————— 输入条：剪贴板搜索、编辑、新建分类、翻译 ——————————

    private fun setLanguagePicker(show:Boolean) {
        langPicking=show;sinkStrip.chipExpanded=show;sinkStrip.refresh()
        languageBar.animate().cancel()
        val dy=8*resources.displayMetrics.density
        if(!android.animation.ValueAnimator.areAnimatorsEnabled()) {
            languageBar.visibility=if(show) View.VISIBLE else View.INVISIBLE
            languageBar.alpha=if(show) 1f else 0f;languageBar.translationY=0f
            if(show) languageBar.showAssoc(Translator.TARGETS.map {it.second})
            return
        }
        if(show) {
            languageBar.showAssoc(Translator.TARGETS.map {it.second})
            if(languageBar.visibility!=View.VISIBLE) {languageBar.alpha=0f;languageBar.translationY=dy}
            languageBar.visibility=View.VISIBLE
            languageBar.animate().alpha(1f).translationY(0f).setDuration(NativeMotion.DURATION)
                .setInterpolator(android.view.animation.DecelerateInterpolator()).start()
        } else {
            languageBar.animate().alpha(0f).translationY(-dy).setDuration(NativeMotion.DURATION)
                .setInterpolator(android.view.animation.DecelerateInterpolator()).withEndAction {
                    if(!langPicking) languageBar.visibility=View.INVISIBLE
                }.start()
        }
    }

    private fun startSink(mode: Sink, initial: String, clip: Clip?) {
        NativeMotion.swap(root,col) {startSinkNow(mode,initial,clip)}
    }
    private fun startSinkNow(mode: Sink, initial: String, clip: Clip?) {
        if(sink!=Sink.NONE) {
            if(sink==Sink.TRANSLATE) ic()?.setComposingText("",1)
            endSink(false)
        }
        if (seq.isNotEmpty()) resetComposing()
        closePanel(render = false, animate=false)
        sink = mode
        sinkClip = clip
        sinkCategory = if(mode==Sink.CLIP_ADD) clipTab else ""
        sinkBuf.setLength(0)
        sinkBuf.append(initial)
        langPicking = false
        transResult = ""
        transStatus = ""
        transDirection = ""
        sinkStrip.chip = if (mode == Sink.TRANSLATE) Translator.name(prefs.getString(Prefs.TRANS_TO, "auto") ?: "auto") else null
        sinkStrip.translatedPreview = ""
        sinkStrip.micOn = false
        sinkStrip.chipExpanded = false
        sinkStrip.onChip = {
            if(seq.isNotEmpty()) finalizeComposing()
            setLanguagePicker(!langPicking)
        }
        sinkStrip.onMic = { voiceIntoSink() }
        sinkStrip.onClear = {
            sinkBuf.setLength(0); sinkStrip.value = ""; sinkStrip.composing = ""; sinkStrip.translatedPreview = ""; sinkStrip.refresh()
            resetComposing(); onSinkChanged(); renderBar()
        }
        sinkStrip.multiline = mode == Sink.EDIT || mode == Sink.CLIP_ADD || mode==Sink.REMOTE_EDIT
        sinkStrip.localEditing = sinkStrip.multiline || mode == Sink.TRANSLATE
        sinkStrip.caption=if(mode==Sink.REMOTE_EDIT) "UU · 本次输入，点勾同步到远端" else ""
        sinkStrip.onBeforeSelection = {
            if (panel == Panel.TOKENS) closePanel(animate = false)
            if (seq.isNotEmpty() || picks.isNotEmpty()) finalizeComposing()
        }
        sinkStrip.onEdited = {text ->
            sinkBuf.setLength(0);sinkBuf.append(text);onSinkChanged()
        }
        sinkStrip.icon = when (mode) { Sink.EDIT, Sink.CLIP_ADD, Sink.REMOTE_EDIT -> R.drawable.ic_edit; Sink.TRANSLATE -> R.drawable.ic_translate; Sink.NEWCAT -> R.drawable.ic_clip; Sink.FAVORITE -> R.drawable.ic_emoji; else -> R.drawable.ic_search }
        sinkStrip.hint = when (mode) { Sink.REMOTE_EDIT -> "先输入文字，再用编辑工具调整"; Sink.VAULT_SEARCH -> "搜索密码空间（名称或账号）"; Sink.CLIP_ADD -> "添加到分类“$sinkCategory”"; Sink.EDIT -> "修改内容"; Sink.TRANSLATE -> "输入要翻译的中文或外文"; Sink.NEWCAT -> "新分类的名字"; Sink.FAVORITE -> "输入要收藏的表情或颜文字"; else -> "搜索剪贴板" }
        sinkStrip.value = initial
        sinkStrip.composing = ""
        sinkStrip.showAnimated(true,animate=mode==Sink.TRANSLATE)
        sinkStrip.refresh()
        layer = Layer.LETTERS
        assoc = emptyList()
        render()
        updateClipSearch()
    }

    private fun endSink(animate: Boolean=false) {
        if(animate) {NativeMotion.swap(root,col) {endSink(false)};return}
        if (panel == Panel.TOKENS) cancelTokenPreview()
        resetComposing()
        if (sink == Sink.TRANSLATE) { Translator.cancel(); main.removeCallbacks(transTask) }
        if (voiceToSink) {voicePolishJob?.cancel();voicePolishJob=null;stopListening();voiceToSink = false}
        sink = Sink.NONE
        sinkClip = null
        sinkCategory = ""
        langPicking = false
        if(::languageBar.isInitialized) {languageBar.animate().cancel();languageBar.visibility=View.INVISIBLE;languageBar.alpha=0f}
        if (::sinkStrip.isInitialized) {sinkStrip.localEditing=false;sinkStrip.showAnimated(false, animate)}
        updateClipSearch()
    }

    /** 输入条里的字变了：剪贴板搜索实时出结果，翻译实时出译文 */
    private fun onSinkChanged() {
        when (sink) {
            Sink.SEARCH -> updateClipSearch()
            Sink.TRANSLATE -> {
                if(langPicking && sinkBuf.isNotEmpty()) setLanguagePicker(false)
                Translator.cancel();main.removeCallbacks(transTask)
                transResult="";transStatus=""
                val text=sinkBuf.toString()
                val source=if(text.any {it in '一'..'鿿'}) "zh" else if(text.any {it in 'a'..'z' || it in 'A'..'Z'}) "en" else "auto"
                val target=prefs.getString(Prefs.TRANS_TO,"auto") ?: "auto"
                val destination=if(target!="auto") target else if(source=="zh") "en" else "zh"
                transDirection=if(text.isBlank()) "" else "${Translator.name(source)}→${Translator.name(destination)}"
                renderBar();main.postDelayed(transTask,450)
            }
            else -> Unit
        }
    }

    private fun updateClipSearch() {
        if (!::clipSearch.isInitialized) return
        clipSearch.removeAllViews()
        if (sink != Sink.SEARCH) { clipSearch.visibility = View.GONE; return }
        val q = sinkBuf.toString().trim()
        val list = if (q.isEmpty()) ClipStore.list("全部", "") else ClipStore.list("全部", q)
        clipSearch.addView(ClipSearchRow(this, skin, list, q) { c ->
            endSink()
            clipQuery = ""
            render()
            pasteClip(c)
        })
        clipSearch.visibility = View.VISIBLE
    }

    /** 翻译：实时把译文作为组字文字放进编辑框，并在翻译条内同步预览，点“完成”才确定 */
    private fun runLiveTranslation() {
        if (sink != Sink.TRANSLATE) return
        val text = (sinkBuf.toString() + sinkStrip.composing).trim()
        if (text.isBlank()) {
            Translator.cancel()
            transResult = ""
            transStatus = ""
            sinkStrip.translatedPreview = ""
            sinkStrip.refresh()
            ic()?.setComposingText("", 1)
            renderBar()
            return
        }
        val target = prefs.getString(Prefs.TRANS_TO, "auto") ?: "auto"
        Translator.run(applicationContext, text, false, target, onDirection={ source,destination ->
            if(sink==Sink.TRANSLATE && (sinkBuf.toString()+sinkStrip.composing).trim()==text) {
                transDirection="${Translator.name(source)}→${Translator.name(destination)}";renderBar()
            }
        }) { ok, result, status ->
            if (sink != Sink.TRANSLATE || (sinkBuf.toString() + sinkStrip.composing).trim() != text) return@run
            if (ok) {
                transResult = result
                sinkStrip.translatedPreview = result
                sinkStrip.refresh()
                ic()?.setComposingText(result, 1)
            }
            transStatus = status
            if(!ok && ("失败" in status || "超时" in status)) message(status)
            renderBar()
        }
    }

    private fun voiceIntoSink() {
        if (seq.isNotEmpty()) finalizeComposing()
        if (listening) { stopListening(); updateVoice(); return }
        voiceToSink = true
        voicePartial = ""
        toggleListening()
    }

    private fun finishSink(after:Panel?=null) {
        NativeMotion.swap(root,col) {
            if(after==null) closePanel(render=false,animate=false)
            endSink(animate=false)
            if(after==null) render() else openPanelNow(after)
        }
    }
    private fun sinkConfirm() {
        if (seq.isNotEmpty()) finalizeComposing()
        val text = sinkBuf.toString()
        when (sink) {
            Sink.REMOTE_EDIT -> {
                if(!remoteEditor()) {message("输入目标已切换，请重新编辑");return}
                if(text.length>10000) {message("本次编辑最多 10000 字符");return}
                val connection=ic() ?: return
                finishSink()
                remoteHistory.replace(connection,text)
                return
            }
            Sink.FAVORITE -> {
                val value=text.trim().replace("\u0001","")
                if(value.isEmpty()) {message("先输入要收藏的内容");return}
                if(value.codePointCount(0,value.length)>128) {message("最多收藏 128 个字符");return}
                val list=(listOf(value)+favEmoji().filter {it!=value}).take(200)
                prefs.edit().putString(Prefs.FAV_EMOJI,list.joinToString("\u0001")).apply()
                emojiTab=2;finishSink(Panel.EMOJI);return
            }
            Sink.VAULT_SEARCH -> {
                val query=text.trim();vaultResults=emptyList();vaultMatches=false;finishSink(Panel.VAULT)
                vault.search(query) { vaultResults=it;if(panel==Panel.VAULT && sink==Sink.NONE) buildPanel(animate=true) };return
            }
            Sink.SEARCH -> clipQuery = text.trim()
            Sink.EDIT -> sinkClip?.let { ClipStore.update(it, text) }
            Sink.CLIP_ADD -> {
                if(text.isBlank()) {message("先输入要添加的内容");return}
                if(sinkCategory !in ClipStore.customTabs()) {message("这个分类已删除，请重新选择分类");return}
                ClipStore.addCustom(text,sinkCategory);clipTab=sinkCategory
            }
            Sink.NEWCAT -> { ClipStore.addCategory(text); if (text.isNotBlank()) clipTab = text.trim().take(8) }
            Sink.TRANSLATE -> {
                // 完成：把译文确定下来（还没出来就用原文）
                Translator.cancel()
                main.removeCallbacks(transTask)
                val ic = ic()
                if (transResult.isEmpty() && text.isNotBlank()) ic?.setComposingText(text, 1)
                checkpoint(typing = false)
                ic?.finishComposingText()
                finishSink()
                return
            }
            Sink.NONE -> return
        }
        finishSink(Panel.CLIP)
    }

    private fun sinkCancel() {
        val wasVault = sink == Sink.VAULT_SEARCH || sink==Sink.REMOTE_EDIT
        val wasTranslate = sink == Sink.TRANSLATE
        val wasFavorite = sink == Sink.FAVORITE
        if (wasTranslate) ic()?.setComposingText("", 1)
        if(wasVault || wasTranslate) finishSink()
        else if(wasFavorite) {emojiTab=2;finishSink(Panel.EMOJI)} else finishSink(Panel.CLIP)
    }

    // —————————— 面板 ——————————

    private fun openClipboard() {
        NativeMotion.finishSwap(root,col)
        clipTab="全部";clipQuery="";clipExpanded=null;clipPicking=null
        openPanelNow(Panel.CLIP)
        // Render the cached first page immediately; re-read the system clip
        // after that frame in case a copy arrived while the IME was hidden.
        panelHost.postOnAnimation {panelHost.post {if(panel==Panel.CLIP) captureClip()}}
    }

    private fun openPanel(p: Panel) {
        if(p==Panel.CLIP) {openClipboard();return}
        if(p!=Panel.ADJUST && panel!=Panel.ADJUST) NativeMotion.swap(root,col) { openPanelNow(p) }
        else openPanelNow(p)
    }
    private fun landscapeEdit()=panel==Panel.EDIT && !floating && resources.configuration.orientation==Configuration.ORIENTATION_LANDSCAPE
    private fun openPanelNow(p:Panel) {
        // Bottom shortcuts enter panels directly, without runFeature(). They
        // follow the same translation-exit rule as the toolbar and menu.
        if(sink==Sink.TRANSLATE && p!=Panel.NONE && p!=Panel.CANDS) {
            ic()?.setComposingText("",1);endSink(false)
        }
        if (p == Panel.VOICE) OfflineVoice.prewarm(applicationContext, prefs.getInt(Prefs.VOICE_LANG, 0))
        if (p != Panel.VOICE && p != Panel.HAND && p != Panel.HAND_CANDS) releaseToolsLater()
        else main.removeCallbacks(releaseTools)
        if (panel == Panel.ADJUST && p != Panel.ADJUST) closePanel(render=false)
        if (p != Panel.CANDS && seq.isNotEmpty()) finalizeComposing()
        if (p !in listOf(Panel.HAND, Panel.HAND_CANDS)) cancelHandwritingPreview()
        if (panel == Panel.TOKENS && p != Panel.TOKENS) cancelTokenPreview()
        if (p != Panel.CLIP && p != Panel.TOKENS) { clipExpanded = null; clipPicking = null }
        if (panel == Panel.VOICE && p != Panel.VOICE) { stopListening(); cancelVoiceDraft() }
        panel = p
        assoc = emptyList()
        buildPanel(animate=p==Panel.ADJUST)
        // 调节高度时键盘还要看得见
        keyboard.visibility = when {p==Panel.ADJUST -> View.VISIBLE;landscapeEdit()->View.GONE;else->View.INVISIBLE}
        panelHost.visibility = View.VISIBLE
        renderBar()
    }

    private fun closePanel(render: Boolean = true, animate: Boolean = true) {
        if(animate && panel!=Panel.NONE && panel!=Panel.ADJUST) {
            NativeMotion.swap(root,col) {closePanel(render,animate=false)};return
        }
        val hadPanel = panel != Panel.NONE
        cancelHandwritingPreview()
        if (panel == Panel.TOKENS) cancelTokenPreview()
        if (panel == Panel.VOICE && !voiceCollapsed) { stopListening(); cancelVoiceDraft() }
        val wasAdjust = panel == Panel.ADJUST
        adjustOverlay?.let { (it.parent as? android.view.ViewGroup)?.removeView(it) }; adjustOverlay = null
        panel = Panel.NONE
        voiceBoard = null
        if (wasAdjust) {
            adj = null
            adjustmentDrafts.clear()
            floating = V3Prefs.keyboardForm(this) == KeyboardForm.FLOATING
            applyFloat(); applySkin(rebuildPanel = false)
        }
        selecting = false
        anchor = -1
        editView = null
        handView = null
        handCands = emptyList()
        releaseToolsLater()
        if (::panelHost.isInitialized) {
            panelHost.removeAllViews()
            panelHost.visibility = View.GONE
            keyboard.visibility = View.VISIBLE
            if (animate && hadPanel) NativeMotion.pulse(keyboard)
        }
        if (render) renderBar()
    }

    private fun recentEmoji(): List<String> = prefs.getString(Prefs.RECENT_EMOJI, "")!!.split('\u0001').filter { it.isNotEmpty() }
    private fun favEmoji(): List<String> = prefs.getString(Prefs.FAV_EMOJI, "")!!.split('\u0001').filter { it.isNotEmpty() }

    private fun useEmoji(e: String) {
        typeChar(e)
        if(passwordField) return
        val list = (listOf(e) + recentEmoji().filter { it != e }).take(32)
        prefs.edit().putString(Prefs.RECENT_EMOJI, list.joinToString("\u0001")).apply()
    }

    private fun toggleFav(e: String) {
        val f = favEmoji().toMutableList()
        val added = if (e in f) { f.remove(e); false } else { f.add(0, e); true }
        prefs.edit().putString(Prefs.FAV_EMOJI, f.joinToString("\u0001")).apply()
        message(if (added) "已收藏 $e" else "已取消收藏")
        (panelHost.getChildAt(0) as? EmojiPanel)?.updateFavs(f)
    }

    private fun scroll(v: View) = ScrollView(this).apply { isVerticalScrollBarEnabled = false; addView(v) }

    private fun recentSyms(): List<String> = prefs.getString(Prefs.RECENT_SYM, "")!!.split('\u0001').filter { it.isNotEmpty() }

    private fun useSym(sym: String) {
        typeChar(sym, paired = !passwordField)
        if(passwordField) return
        val list = (listOf(sym) + recentSyms().filter { it != sym }).take(28)
        prefs.edit().putString(Prefs.RECENT_SYM, list.joinToString("\u0001")).apply()
    }

    /** 符号分类：常用后面插一个“最近” */
    private fun symCats(): List<Pair<String, List<String>>> =
        listOf(Layouts.SYMBOLS[0], "最近" to recentSyms()) + Layouts.SYMBOLS.drop(1)

    private fun buildPanel(animate: Boolean=false) {
        if (root == null) return
        val previous = if (animate && panelHost.visibility == View.VISIBLE) NativeMotion.snapshot(panelHost) else null
        val skinScroll=(panelHost.getChildAt(0) as? SkinPanel)?.scrollPosition ?: 0
        val oldClipPanel = panelHost.getChildAt(0) as? ClipboardPanel
        val clipScroll = oldClipPanel?.scrollPosition ?: 0
        val tabsScroll = oldClipPanel?.tabsScrollPosition ?: 0
        adjustOverlay?.let { (it.parent as? android.view.ViewGroup)?.removeView(it) }; adjustOverlay = null
        panelHost.removeAllViews()
        editView = null
        if (panel !in listOf(Panel.HAND,Panel.HAND_CANDS)) handView = null
        val p = prefs
        val v: View = when (panel) {
            Panel.NONE -> return
            Panel.VAULT -> VaultPanel(this,skin,vaultResults,onPick={vault.choose(it)},canRemember=vault.canRemember,onRememberedPick={vault.choose(it,remember=true)},onSearch={startSink(Sink.VAULT_SEARCH,"",null)},onBack={closePanel()},
                title=if(vaultMatches) "密码空间" else "搜索结果",canGenerate=vaultMatches && vault.canGenerate,onGenerate={ closePanel(); vault.generateFromPanel() })
            Panel.EMOJI -> EmojiPanel(this, skin, recentEmoji(), favEmoji(), emojiTab, ::useEmoji, ::toggleFav,
                onTab = { emojiTab = it }, onCustom = { startSink(Sink.FAVORITE,"",null) }) { onKey(Key(Kind.DEL)) }
            Panel.CLIP -> ClipboardPanel(
                this, skin, if (clipTab in ClipStore.tabs()) clipTab else "全部", clipQuery, clipExpanded, clipPicking,
                onTab = { clipTab = it; clipQuery = ""; clipExpanded = null; clipPicking = null },
                onTabLong = { t -> confirm("cat:$t", "再长按一次删除分类“$t”（内容移回历史记录）") { ClipStore.removeCategory(t); clipTab = "全部"; buildPanel() } },
                onNewCat = { startSink(Sink.NEWCAT, "", null) },
                onNewContent = { clipTab = it; startSink(Sink.CLIP_ADD, "", null) },
                onSearch = { startSink(Sink.SEARCH, clipQuery, null) },
                onClearQuery = { clipQuery = ""; buildPanel() },
                onClear = {
                    val category=clipTab.takeIf {it in ClipStore.tabs()} ?: "全部"
                    val count=ClipStore.list(category,"").size
                    if(count==0)message("“$category”中没有内容")
                    else confirm("clear:$category", "再点一次垃圾桶，删除“$category”中的全部 $count 条内容（含置顶）") {
                        ClipStore.clearCategory(category);clipExpanded=null;clipPicking=null;buildPanel()
                        message("已清空“$category”")
                    }
                },
                onPaste = ::pasteClip,
                onExpand = { clipExpanded = it; clipPicking = null },
                onAction = ::clipAction,
            )
            Panel.TOKENS -> TokensPanel(this, skin, tokens, tokenSelection, ::previewTokens, onInsert = {
                val connection = ic().takeIf { tokenDraft != null }
                connection?.beginBatchEdit()
                try {
                    cancelTokenPreview()
                    typeChar(it)
                } finally {
                    connection?.endBatchEdit()
                }
                if (tokensFromBar) closePanel()
            }, onCopy = {
                message(if(ClipboardTransfer.copy(clipboard,ClipData.newPlainText("空叶键盘",it))) "已复制" else "未能写入剪贴板，请检查写入剪贴板权限")
            }, onClose = { if (tokensFromBar) closePanel() else openPanel(Panel.CLIP) })
            Panel.EDIT -> EditPanelView(this, skin, selecting) { editAction(it) }.also { editView = it }
            Panel.SKIN -> SkinPanel(
                this, skin, Themes.all(this), Prefs.theme(this).id, p.getInt(Prefs.MODE, 0),
                onMode = { p.edit().putInt(Prefs.MODE, it).apply() },
                onPick = { p.edit().putString(Prefs.THEME, it.id).apply() },
                glass=p.getBoolean(Prefs.GLASS,false),onGlass={p.edit().putBoolean(Prefs.GLASS,it).apply()},initialScrollY=skinScroll,
            )
            Panel.ASSOCIATIONS -> associationPanel()
            Panel.MORE -> scroll(TileGridView(this, skin, Prefs.menu(this).map { Triple(it, Prefs.NAMES[it] ?: it, Prefs.ICONS[it] ?: R.drawable.ic_settings) },if(floating)setOf("float") else emptySet()) { runFeature(it) })
            Panel.ARRANGE -> scroll(ArrangeView(this, skin, Prefs.tools(this), Prefs.menu(this)) { t, m -> Prefs.saveArrangement(this, t, m) })
            Panel.LAYOUT -> LayoutPicker(this, skin, Prefs.layout(this)) { id ->
                V3Prefs.saveLayout(this,id)
                closePanel()
                if (!zh) toggleLang()
            }
            Panel.ADJUST -> {
                val a = adj ?: newAdjustment().also { adj = it }
                AdjustOverlay(
                    this, skin, a, a.floating,
                    onLive = { applyFloat(); applySkin(rebuildPanel = false) },
                    onFloating = {
                        adjustmentDrafts[a.floating]=a
                        floating=!a.floating
                        adj=adjustmentDrafts.getOrPut(floating){newAdjustment()}
                        applyFloat(); applySkin(rebuildPanel=false); buildPanel()
                        root?.post { adjustOverlay?.bringToFront(); positionAdjustment() }
                    },
                    onMove = { x,y -> placeFloat(x,y); a.floatX=col.translationX; a.floatY=col.translationY },
                    onReset = {
                        val dm=resources.displayMetrics
                        val width=FloatingKeyboard.defaultWidth(dm.widthPixels.toFloat(),dm.heightPixels.toFloat())
                        adj=Adjust(1f,a.opacity,0,0,0,floating=a.floating,floatWidth=width,
                            floatX=dm.widthPixels*(1-width)*if(FloatingKeyboard.landscape(this)).82f else .5f,
                            floatY=dm.heightPixels*if(FloatingKeyboard.landscape(this)).18f else .45f)
                        adjustmentDrafts[a.floating]=adj!!
                        floating=a.floating
                        applyFloat(); applySkin()
                        root?.post { adjustOverlay?.bringToFront(); positionAdjustment() }
                    },
                    onDone = {
                        adjustmentDrafts[a.floating]=a
                        val edit=p.edit()
                        V3Prefs.saveFloating(this, a.floating, edit)
                        adjustmentDrafts.values.forEach {FloatingKeyboard.save(this,it,edit)}
                        edit.apply()
                        closePanel()
                    },
                )
            }

            Panel.SYMBOL -> SymbolBoard(this, skin, ::symCats, symCat,
                onCat = { symCat = it },
                onSym = { s -> useSym(s) },
                onBack = { closePanel(render = false); layer = Layer.LETTERS; render(); updateAutoCaps() },
                onNum = { closePanel(render = false); layer = Layer.NUM; render() },
                onDel = { onKey(Key(Kind.DEL)) })
            Panel.HAND -> handView?.also {it.updateSkin(skin)} ?: HandwriteView(this, skin, onRecognize = { v -> recognize(v) }, onAction = { a ->
                when (a) {
                    "del" -> if (handView?.empty == false) handView?.clear() else onKey(Key(Kind.DEL))
                    "space" -> if (handCands.isNotEmpty()) onCandidate(0) else typeChar(" ")
                    "enter" -> if (handCands.isNotEmpty()) onCandidate(0) else onKey(Key(Kind.ENTER))
                }
            }, onChanged = {
                OpenHandwriting.cancelRecognition()
                cancelHandwritingPreview()
                handCands = emptyList()
                assoc = emptyList()
                renderBar()
            }).also { v ->
                handView = v
                v.status = if (OpenHandwriting.ready) "" else "正在准备手写…"
                OpenHandwriting.prepare(applicationContext) {
                    if (handView === v) v.status = if (OpenHandwriting.ready) "" else "开源手写启动失败，使用备用识别"
                }
            }
            Panel.VOICE -> VoiceBoard(this, skin, p.getInt(Prefs.VOICE_LANG, 0), !micGranted(),
                onDialect = { i ->
                    if (i == p.getInt(Prefs.VOICE_LANG, 0)) return@VoiceBoard
                    if (afterVoiceStop != null) return@VoiceBoard
                    val resume = listening || voiceStarting || voiceWantsListening
                    if (resume) stopListening()
                    p.edit().putInt(Prefs.VOICE_LANG, i).apply()
                    sichuanFallback = false
                    // Restart synchronously. An unguarded delayed toggle could
                    // reopen after cancel or toggle a newer session off.
                    if (resume) toggleListening()
                },
                onStart = { toggleListening() },
                onFinish = { finishVoicePanel() },
                onPermission = { openSettings("grant_mic") },
                onCollapse = if(V3Prefs.enabled(this,V3Prefs.DRIVE,false)) ({collapseVoice()}) else null,
                onPolish = if(V3Prefs.enabled(this,V3Prefs.VOICE_POLISH,false)) ({polishVoiceAction()}) else null,
            ).also {
                voiceBoard = it
                it.update(voiceText.isNotEmpty(), voiceStatus, listening,
                    listening || voiceStarting || voiceWantsListening, afterVoiceStop != null)
                it.polishState(voicePolishJob != null, voicePolishOriginal != null)
            }
            Panel.CANDS -> {
                val candidates=if(resolvedKeys==seq.toList())cands else emptyList()
                ExpandedCandidates(this, skin, candidates.map {it.w}, candidateFilter,
                    onMode = { candidateFilter=it },
                    onPick = { i -> onCandidate(i) },
                    onDelete = { onKey(Key(Kind.DEL)); if (seq.isEmpty()) closePanel() }, onBack = { closePanel() },
                    labels=if(candidates.isEmpty())emptyList() else candItems().map {it.py},
                    onOverflow={positionCandidatePreview()},
                    onVisibleWords={requestVisibleGloss(it)},spellings=candidates.map {it.py},
                    style=if(zh)ExpandedCandidates.Style.PINYIN else ExpandedCandidates.Style.ENGLISH,
                    literals=if(zh)emptyList() else EnglishInput.literalOptions(seq.joinToString("")),
                    onLiteral={
                        if(it in EnglishInput.literalOptions(seq.joinToString("")))pick(Cand(it,letterCount(),null,0f))
                    })
            }
            Panel.HAND_CANDS -> ExpandedCandidates(this, skin, handCands, candidateFilter,
                onMode = { candidateFilter=it }, onPick = { onCandidate(it) },
                onDelete = { cancelHandwritingPreview();handCands=emptyList();handView?.clear(); panel=Panel.HAND; buildPanel(); renderBar() },
                onBack = { openPanel(Panel.HAND) }, labels=handCands.map {if(V3Prefs.enabled(this,V3Prefs.HAND_TONE))HandwritingPinyin.label(it) else null},
                onOverflow={positionCandidatePreview()},
                style=ExpandedCandidates.Style.HANDWRITING)
        }
        if (panel == Panel.ADJUST) {
            adjustOverlay=v
            root?.addView(v, FrameLayout.LayoutParams(col.width, col.height))
            positionAdjustment()
        } else panelHost.addView(v, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        if (v is ClipboardPanel) { v.restoreScroll(clipScroll); v.restoreTabsScroll(tabsScroll) }
        if (animate) {
            if (panel == Panel.ADJUST) NativeMotion.pulse(v)
            else NativeMotion.reveal(panelHost, v, previous)
        }
    }

    private fun positionAdjustment() {
        val view=adjustOverlay ?: return
        view.elevation=col.elevation+4*resources.displayMetrics.density
        val l=view.layoutParams as FrameLayout.LayoutParams
        val width=col.width-col.paddingLeft-col.paddingRight
        if (l.width != width || l.height != col.height || view.width!=width || view.height!=col.height) {
            l.width=width;l.height=col.height;view.layoutParams=l
            // The keyboard can resize during its parent's layout pass. Measure the
            // independent overlay now so its card never keeps the previous size.
            view.measure(View.MeasureSpec.makeMeasureSpec(width.coerceAtLeast(1),View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(col.height.coerceAtLeast(1),View.MeasureSpec.EXACTLY))
            view.layout(0,0,width,col.height)
        }
        view.x=col.x+col.paddingLeft; view.y=col.y
    }

    private fun recognize(view: HandwriteView) {
        val revision = inkRevision
        var primary:List<String>?=null
        val supplementary=LinkedHashSet<String>()
        val structural=LinkedHashSet<String>()
        fun publish() {
            if(panel !in listOf(Panel.HAND,Panel.HAND_CANDS) || handView!==view || inkRevision!=revision || primary==null)return
            val base=primary.orEmpty()
            handCands=HandwritingCandidates.singleCharacters(base.take(2)+structural+supplementary+base.drop(2)).take(96)
            previewHandwriting(handCands.firstOrNull().orEmpty())
            if(panel==Panel.HAND_CANDS)(panelHost.getChildAt(0) as? ExpandedCandidates)?.submit(handCands,
                handCands.map {if(V3Prefs.enabled(this,V3Prefs.HAND_TONE))HandwritingPinyin.label(it) else null})
            renderBar()
        }
        fun deliver(list: List<String>, ocr: Boolean) {
            if (panel !in listOf(Panel.HAND,Panel.HAND_CANDS) || handView !== view || inkRevision != revision) return
            primary=HandwritingCandidates.singleCharacters(list)
            publish()
        }
        fun supplement(list:List<String>) {supplementary.addAll(list.filter {it.isNotBlank()});publish()}
        fun structure(list:List<String>) {structural.addAll(list);publish()}
        // A closed-label neural model can return plausible common characters
        // even when the written character is outside its alphabet. Supplement
        // successful results too, instead of waiting for an empty result.
        view.bitmap()?.let {Ink.recognize(it,::supplement)}
        TemplateHandwriting.prepare(applicationContext) {
            if(panel==Panel.HAND && handView===view && inkRevision==revision && TemplateHandwriting.ready && !OpenHandwriting.usingTemplate)
                TemplateHandwriting.recognize(view.inkStrokes(),::supplement)
        }
        val pre = runCatching { ic()?.getTextBeforeCursor(20, 0)?.toString() }.getOrNull().orEmpty()
        if(HandInk.state==2)HandInk.recognize(view.inkStrokes(),view.padWidth(),view.padHeight(),pre,::supplement)
        fun backup() {
            if (panel != Panel.HAND || handView !== view || inkRevision != revision) return
            if (HandInk.recognize(view.inkStrokes(), view.padWidth(), view.padHeight(), pre) { deliver(it, ocr = false) }) return
            HandInk.prepare { main.post {
                if (panel == Panel.HAND && handView === view && inkRevision == revision && !OpenHandwriting.ready) {
                    view.status = HandInk.status
                    HandInk.recognize(view.inkStrokes(), view.padWidth(), view.padHeight(), pre) { deliver(it, ocr = false) }
                }
            } }
            view.bitmap()?.let { bmp -> Ink.recognize(bmp) { deliver(it, ocr = true) } }
        }
        if (OpenHandwriting.recognize(view.inkStrokes(), { result ->
            if (result.isNotEmpty()) deliver(result, ocr = false)
            else backup()
        },::structure)) return
        OpenHandwriting.prepare(applicationContext) {
            if (panel == Panel.HAND && handView === view && inkRevision == revision) {
                if (OpenHandwriting.ready) OpenHandwriting.recognize(view.inkStrokes(),{if(it.isEmpty())backup()else deliver(it,ocr=false)},::structure)
                else backup()
            }
        }
        if (!OpenHandwriting.ready) view.status = "正在准备手写…"
    }

    // —————————— 编辑面板 ——————————

    private fun editAction(id: String) {
        val transfer = id == "copy" || id == "cut"
        if(sink!=Sink.NONE && sinkStrip.localEditing) {
            buzz()
            if(seq.isNotEmpty()) finalizeComposing()
            if(id=="select") {
                selecting=!selecting;editView?.selecting=selecting;editView?.invalidate()
            } else {
                val done = sinkStrip.editAction(id, selecting)
                if (transfer && done && panel == Panel.EDIT) closePanel()
            }
            return
        }
        if(passwordField) { vault.invalidateCapture(); if(id in setOf("copy","cut")) {message("密码不复制到剪贴板");return} }
        buzz()
        val ic = ic() ?: return
        if(remoteEditor() && id in setOf("left","right","up","down","home","end","select","all","copy","cut")) {
            message("UU 未转发此编辑操作，请用 UU 自带键盘或远端菜单");return
        }
        if(id in listOf("left","right","up","down","home","end")) editMoveAt=SystemClock.uptimeMillis()
        if(selecting && id in listOf("left","right","up","down","home","end") && editorCommand("SELECT_MOVE",id)) return
        if(selecting && (id=="up" || id=="down")) {
            val code=when(id) {"left"->KeyEvent.KEYCODE_DPAD_LEFT;"right"->KeyEvent.KEYCODE_DPAD_RIGHT;"up"->KeyEvent.KEYCODE_DPAD_UP;"down"->KeyEvent.KEYCODE_DPAD_DOWN;"home"->KeyEvent.KEYCODE_MOVE_HOME;"end"->KeyEvent.KEYCODE_MOVE_END;else->0}
            if(code!=0) {shiftDirection(code);return}
        }
        when (id) {
            "left" -> moveCursor(-1)
            "right" -> moveCursor(1)
            "up" -> { endSelect(); sendDownUpKeyEvents(KeyEvent.KEYCODE_DPAD_UP) }
            "down" -> { endSelect(); sendDownUpKeyEvents(KeyEvent.KEYCODE_DPAD_DOWN) }
            "home" -> jump(-1)
            "end" -> jump(1)
            "select" -> {
                selecting = !selecting
                editorCommand(if(selecting) "SELECTION_START" else "SELECTION_END")
                anchor = -1
                editView?.selecting = selecting
                editView?.invalidate()
            }
            "undo" -> undo()
            "redo" -> redo()
            "all" -> {
                if(!ic.performContextMenuAction(android.R.id.selectAll)) {
                    val ext=ic.getExtractedText(ExtractedTextRequest().apply {hintMaxChars=200000},0)
                    if(ext?.text!=null) ic.setSelection(ext.startOffset,ext.startOffset+ext.text.length)
                }
            }
            "copy", "cut" -> {
                val t = ic.getSelectedText(0)?.toString()?.takeIf { it.isNotEmpty() } ?: run {
                    val ext = ic.getExtractedText(ExtractedTextRequest().apply { hintMaxChars = 200000 }, 0)
                    val s = minOf(ext?.selectionStart ?: 0, ext?.selectionEnd ?: 0)
                    val e = maxOf(ext?.selectionStart ?: 0, ext?.selectionEnd ?: 0)
                    ext?.text?.takeIf { e > s && e <= it.length }?.subSequence(s, e)?.toString()
                }
                if (t.isNullOrEmpty()) {
                    endSelect()
                    message("先选中文字：点中间的“选择”再移动，或点“全选”")
                    return
                }
                val manager = clipboard
                val copied = manager != null && runCatching {
                    manager.setPrimaryClip(ClipData.newPlainText("空叶键盘", t))
                }.isSuccess
                if (!copied) {
                    message("未能写入剪贴板，请检查写入剪贴板权限")
                    return
                }
                if (id == "cut") {
                    checkpoint(false)
                    if (!ic.commitText("", 1)) {
                        message("已复制，当前输入框未能剪切")
                        return
                    }
                }
                endSelect()
                if (panel == Panel.EDIT) closePanel()
                message(if (id == "cut") "已剪切" else "已复制")
            }
            "paste" -> {
                SmsCodeClipboard.expire(this)
                val t = runCatching { clipboard?.primaryClip?.getItemAt(0)?.coerceToText(this)?.toString() }.getOrNull()
                if (t.isNullOrEmpty()) message("剪贴板是空的") else pasteExternal(t)
            }
            "del" -> { checkpoint(true,"delete"); deleteCommitted() }
            "enter" -> { checkpoint(false); commitOut("\n") }
        }
    }

    private fun shiftDirection(code: Int) {
        val now=SystemClock.uptimeMillis()
        val connection=ic() ?: return
        val flags=KeyEvent.FLAG_SOFT_KEYBOARD or KeyEvent.FLAG_KEEP_TOUCH_MODE
        fun event(action:Int,key:Int,meta:Int)=KeyEvent(now,SystemClock.uptimeMillis(),action,key,0,meta,android.view.KeyCharacterMap.VIRTUAL_KEYBOARD,0,flags)
        // Native TextView tracks Shift in its editable buffer, while Compose reads
        // the arrow event's modifiers. Send both for consistent line selection.
        connection.sendKeyEvent(event(KeyEvent.ACTION_DOWN,KeyEvent.KEYCODE_SHIFT_LEFT,0))
        val shift=KeyEvent.META_SHIFT_ON or KeyEvent.META_SHIFT_LEFT_ON
        connection.sendKeyEvent(event(KeyEvent.ACTION_DOWN,code,shift))
        connection.sendKeyEvent(event(KeyEvent.ACTION_UP,code,shift))
        connection.sendKeyEvent(event(KeyEvent.ACTION_UP,KeyEvent.KEYCODE_SHIFT_LEFT,0))
    }

    private fun endSelect() {
        if (!selecting) return
        selecting = false
        editorCommand("SELECTION_END")
        anchor = -1
        editView?.selecting = false
        editView?.invalidate()
    }

    /** 选择模式下用 setSelection 扩选；否则发方向键 */
    private fun moveCursor(dir: Int) {
        if (!selecting) {
            sendDownUpKeyEvents(if (dir < 0) KeyEvent.KEYCODE_DPAD_LEFT else KeyEvent.KEYCODE_DPAD_RIGHT)
            return
        }
        val ic = ic() ?: return
        val ext = ic.getExtractedText(ExtractedTextRequest().apply { hintMaxChars = 200000 }, 0) ?: return
        val text = ext.text ?: return
        val off = ext.startOffset
        val a = ext.selectionStart
        val b = ext.selectionEnd
        if (anchor < 0) anchor = if (dir < 0) maxOf(a, b) + off else minOf(a, b) + off
        var focus = (if (anchor - off == a) b else a)
        focus = if (dir < 0) {
            if (focus <= 0) 0 else focus - (if (focus >= 2 && Character.isSurrogatePair(text[focus - 2], text[focus - 1])) 2 else 1)
        } else {
            if (focus >= text.length) text.length else focus + (if (focus + 1 < text.length && Character.isSurrogatePair(text[focus], text[focus + 1])) 2 else 1)
        }
        ic.setSelection(anchor, focus + off)
    }

    /** 段首、段尾（以换行分段） */
    private fun jump(dir: Int) {
        val ic = ic() ?: return
        val ext = ic.getExtractedText(ExtractedTextRequest().apply { hintMaxChars = 200000 }, 0)
        if (ext?.text == null) {
            sendDownUpKeyEvents(if (dir < 0) KeyEvent.KEYCODE_MOVE_HOME else KeyEvent.KEYCODE_MOVE_END)
            return
        }
        val text = ext.text
        val off = ext.startOffset
        val a = ext.selectionStart
        val b = ext.selectionEnd
        val from = if (selecting && anchor >= 0 && anchor - off == a) b else if (selecting && anchor >= 0) a else b
        val target = if (dir < 0) text.lastIndexOf('\n', (from - 1).coerceAtLeast(0)).let { if (it < 0 || from == 0) 0 else it + 1 }
        else text.indexOf('\n', from).let { if (it < 0) text.length else it }
        if (selecting) {
            if (anchor < 0) anchor = (if (target < a) b else a) + off
            ic.setSelection(anchor, target + off)
        } else ic.setSelection(target + off, target + off)
    }

    companion object {
        private val MSG_TOKEN = Any()
        private val EMAIL_SUFFIXES = listOf("qq.com", "163.com", "gmail.com", "outlook.com", "126.com", "foxmail.com", "icloud.com", "hotmail.com", "sina.com", "yahoo.com")
    }
}
