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

    private enum class Layer { LETTERS, NUM }
    private enum class Panel { NONE, EMOJI, CLIP, EDIT, SKIN, CANDS, MORE, ADJUST, ARRANGE, LAYOUT, SYMBOL, HAND, HAND_CANDS, VOICE, TOKENS }
    /** 打字的去向：编辑框，或者剪贴板搜索、编辑、新建分类、翻译的输入条 */
    private enum class Sink { NONE, SEARCH, EDIT, NEWCAT, TRANSLATE, FAVORITE }

    /** 分几次选词时，已经选好但还没上屏的部分 */
    private class Pick(val w: String, val syl: List<String>, val keys: List<String>, val isRaw: Boolean)

    private lateinit var prefs: SharedPreferences
    private val main = Handler(Looper.getMainLooper())

    private var root: Root? = null
    private lateinit var col: LinearLayout
    private lateinit var floatBar: FloatBar
    private lateinit var sinkStrip: SinkStrip
    private lateinit var bar: CandidateBar
    private lateinit var keyboard: KeyboardView
    private lateinit var panelHost: PanelHost
    private lateinit var bottomStrip: BottomStrip
    private lateinit var candidatePreview: android.widget.TextView
    private lateinit var pinyinLens: PinyinLens
    private var longCandidate: String? = null
    private lateinit var tab: PinyinTab
    private lateinit var clipSearch: FrameLayout
    private var voiceBoard: VoiceBoard? = null
    private var editView: EditPanelView? = null
    private var handView: HandwriteView? = null

    private var skin = Skins.KONGYE
    private var zh = true
    /** 输入的键：每个元素是这个键的字母集合，' 是分词符 */
    private val seq = ArrayList<String>()
    private val picks = ArrayList<Pick>()
    private var cands: List<Cand> = emptyList()
    private var assoc: List<String> = emptyList()
    private var shift = 0
    private var lastShiftTap = 0L
    private var layer = Layer.LETTERS
    private var panel = Panel.NONE
    private var learnField = true
    private var forceEn = false
    private var selecting = false
    private var anchor = -1
    private var floating = false

    private var sink = Sink.NONE
    private val sinkBuf = StringBuilder()
    private var sinkClip: Clip? = null
    private var clipTab = "全部"
    private var clipQuery = ""
    private var clipExpanded: Clip? = null
    private var clipPicking: Clip? = null
    private var tokens: List<String> = emptyList()
    private var tokensFromBar = false
    private var emojiTab = 0
    private var symCat = 0
    /** 拼音条上的编辑光标：seq 里的下标，-1 表示在最后 */
    private var pyCaret = -1
    /** 键盘调节中的临时设置 */
    private var adj: Adjust? = null
    private var adjustOverlay: View? = null
    private var candidateFilter = 0
    /** 翻译：正在选目标语言 */
    private var langPicking = false
    private var handCands: List<String> = emptyList()
    private var inkRevision = 0
    private var confirmAt = 0L
    private var confirmWhat = ""

    private var transResult = ""
    private var transStatus = ""
    private val transTask = Runnable { runLiveTranslation() }

    private var speech: SpeechRecognizer? = null
    private var offlineSpeech: OfflineVoice.Session? = null
    private var offlineAfterStop: (() -> Unit)? = null
    private var listening = false
    private var voiceWantsListening = false
    private var voiceStatus = "已暂停"
    private var voicePartial = ""
    /** 这次语音已经确定的文字（连续说多句会接起来） */
    private val voiceText = StringBuilder()
    private var voiceDraft: VoiceDraft? = null
    private var voiceBefore: Snap? = null
    /** 语音的去向：false 进语音面板，true 进翻译输入条 */
    private var voiceToSink = false

    private var clipboard: ClipboardManager? = null
    private var ignoreClipUntil = 0L
    private var lastImageUri = ""
    /** 刚复制的文字：在候选栏弹出快捷粘贴/分词/关闭条 */
    private var pendingClipText: String? = null
    private var pendingClipAt = 0L
    private var dismissedClipText = ""
    private val clipListener = ClipboardManager.OnPrimaryClipChangedListener { captureClip() }

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs.of(this)
        prefs.registerOnSharedPreferenceChangeListener(this)
        zh = prefs.getBoolean(Prefs.ZH, true)
        floating = prefs.getBoolean(Prefs.FLOAT, false)
        Lexicon.fuzzyRules = Prefs.fuzzy(this)
        ClipStore.load(this)
        Thread {
            Lexicon.load(applicationContext)
            main.post { if (seq.isNotEmpty()) refreshComposing() }
        }.start()
        clipboard = getSystemService(ClipboardManager::class.java)
        clipboard?.addPrimaryClipChangedListener(clipListener)
        OfflineVoice.prewarm(applicationContext,prefs.getInt(Prefs.VOICE_LANG,0))
    }

    override fun onDestroy() {
        candidateRevision++;candidateWorker.shutdownNow()
        cancelVoiceDraft()
        offlineSpeech?.cancel(); offlineSpeech = null
        OfflineVoice.releaseCache()
        Translator.cancel()
        clipboard?.removePrimaryClipChangedListener(clipListener)
        prefs.unregisterOnSharedPreferenceChangeListener(this)
        speech?.destroy()
        Lexicon.save()
        super.onDestroy()
    }

    override fun onEvaluateFullscreenMode() = false

    /**
     * 输入法视图占满整个输入法窗口（透明）：键盘贴底，拼音条可以伸到键盘上面，悬浮时键盘能拖到任何地方。
     * 只有键盘和拼音条那块能点，其余地方的触摸交给后面的应用（见 onComputeInsets）。
     */
    private inner class Root(ctx: Context) : FrameLayout(ctx) {
        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val h = if (MeasureSpec.getMode(heightMeasureSpec) != MeasureSpec.UNSPECIFIED && MeasureSpec.getSize(heightMeasureSpec) > 0)
                MeasureSpec.getSize(heightMeasureSpec) else resources.displayMetrics.heightPixels
            super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(h, MeasureSpec.EXACTLY))
            // 液态玻璃：窗口只要键盘（和正在显示的拼音条）那么高，系统模糊只作用在键盘后面
            if (blurOn() && ::col.isInitialized) {
                val extra = if (::tab.isInitialized && tab.visibility == View.VISIBLE) tab.measuredHeight else 0
                setMeasuredDimension(measuredWidth, col.measuredHeight + extra)
            }
        }
    }

    /** 悬浮键盘顶上的拖动条：拖动移动键盘，右边的 × 退出悬浮 */
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
            setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), (26 * d).toInt())

        override fun onDraw(c: Canvas) {
            p.color = withAlpha(skin.barFg, 150)
            c.drawRoundRect(RectF(width / 2f - 24 * d, 10 * d, width / 2f + 24 * d, 15 * d), 3 * d, 3 * d, p)
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
                    if (!moved && e.x > width - 44 * d) setFloating(false)
                    else prefs.edit().putFloat(Prefs.FLOAT_X, col.translationX).putFloat(Prefs.FLOAT_Y, col.translationY).apply()
                }
            }
            return true
        }
    }

    override fun onCreateInputView(): View {
        val r = Root(this)
        col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        floatBar = FloatBar(this)
        sinkStrip = SinkStrip(this, onOk = { sinkConfirm() }, onCancel = { sinkCancel() }).apply { visibility = View.GONE }
        bar = CandidateBar(this).also { it.listener = this }
        keyboard = KeyboardView(this).also { it.listener = this }
        panelHost = PanelHost(this).apply {
            visibility = View.GONE
            heightOf = { keyboard.desiredHeight() }
        }
        // 左下角：收起键盘
        bottomStrip = BottomStrip(this, onKeyboard = { requestHideSelf(0) }, onClip = { if (panel == Panel.CLIP) closePanel() else openPanel(Panel.CLIP) })
        val area = FrameLayout(this)
        area.addView(keyboard, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        area.addView(panelHost, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        clipSearch = FrameLayout(this).apply { visibility = View.GONE }
        col.addView(floatBar, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        col.addView(sinkStrip, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        col.addView(clipSearch, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        col.addView(bar, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        col.addView(area, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        col.addView(bottomStrip, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        r.addView(col, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT, Gravity.BOTTOM))
        tab = PinyinTab(this) { onTabCaret(it) }.apply { visibility = View.GONE }
        r.addView(tab, FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.TOP or Gravity.START))
        candidatePreview=android.widget.TextView(this).apply {
            textSize=17f;setPadding((8*resources.displayMetrics.density).toInt(),(5*resources.displayMetrics.density).toInt(),(8*resources.displayMetrics.density).toInt(),(5*resources.displayMetrics.density).toInt())
            maxLines=12;ellipsize=android.text.TextUtils.TruncateAt.END
            importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_YES
            visibility=View.GONE
        }
        r.addView(candidatePreview,FrameLayout.LayoutParams((resources.displayMetrics.widthPixels*.48f).toInt(),WRAP_CONTENT))
        candidatePreview.addOnLayoutChangeListener {_,_,_,_,_,_,_,_,_->positionCandidatePreview()}
        bar.onLongCandidate={value ->
            if(longCandidate!=value) {
                longCandidate=value;candidatePreview.text=value.orEmpty();positionCandidatePreview()
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
        col.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> positionTab(); positionAdjustment() }
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
        panel = Panel.NONE
        applySkin()
        applyFloat()
        return r
    }

    override fun onComputeInsets(outInsets: Insets) {
        super.onComputeInsets(outInsets)
        val r = root ?: return
        if (r.height <= 0 || !::col.isInitialized) return
        val loc = IntArray(2)
        col.getLocationInWindow(loc)
        // 停靠时应用只让出键盘那么高；悬浮时不让（键盘浮在应用上面）
        val top = if (floating || panel==Panel.ADJUST || (adj?.opacity ?: Prefs.opacity(this)) < .99f) r.height else loc[1]
        outInsets.contentTopInsets = top
        outInsets.visibleTopInsets = top
        outInsets.touchableInsets = Insets.TOUCHABLE_INSETS_REGION
        outInsets.touchableRegion.set(loc[0], loc[1], loc[0] + col.width, loc[1] + col.height)
        if (tab.visibility == View.VISIBLE && tab.width > 0) {
            val t = IntArray(2)
            tab.getLocationInWindow(t)
            outInsets.touchableRegion.op(t[0], t[1], t[0] + tab.width, t[1] + tab.height, Region.Op.UNION)
        }
    }

    /** 拼音条贴在键盘左上角的上面 */
    private fun positionTab() {
        if (!::tab.isInitialized) return
        val r=root ?: return
        tab.x=(col.x+col.paddingLeft+8*resources.displayMetrics.density).coerceIn(0f,(r.width-tab.width).toFloat().coerceAtLeast(0f))
        tab.y = (col.y-tab.height+1).coerceAtLeast(0f)
        positionCandidatePreview()
    }

    private fun positionCandidatePreview() {
        if(!::candidatePreview.isInitialized || !::tab.isInitialized) return
        val show=!longCandidate.isNullOrEmpty() && tab.visibility==View.VISIBLE && !tab.expanded
        candidatePreview.visibility=if(show) View.VISIBLE else View.GONE
        if(!show) return
        val r=root ?: return
        val desired=(col.width*.48f).toInt().coerceAtLeast((96*resources.displayMetrics.density).toInt())
        val lp=candidatePreview.layoutParams as FrameLayout.LayoutParams
        if(lp.width!=desired) {lp.width=desired;candidatePreview.layoutParams=lp}
        candidatePreview.x=(col.x+col.width-desired-4*resources.displayMetrics.density).coerceIn(0f,(r.width-desired).toFloat().coerceAtLeast(0f))
        candidatePreview.y=(col.y-candidatePreview.height).coerceAtLeast(0f)
    }

    private var inputPackage: String? = null
    private var inputKind = 0
    private var inputFieldId = 0
    private var restoringHistoryUntil = 0L
    private var editMoveAt = 0L
    override fun onStartInputView(info: EditorInfo, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        if (restarting && panel == Panel.ADJUST && adj != null) {
            applyFloat(); applySkin(rebuildPanel=false)
            root?.post { adjustOverlay?.bringToFront(); positionAdjustment() }
            return
        }
        val sameEditor=info.packageName==inputPackage && info.inputType==inputKind && info.fieldId==inputFieldId
        val keepEdit=panel==Panel.EDIT && sameEditor && (restarting || SystemClock.uptimeMillis()-editMoveAt<1000)
        inputPackage=info.packageName;inputKind=info.inputType;inputFieldId=info.fieldId
        if(keepEdit) {
            resetComposing();assoc=emptyList();renderBar();return
        }
        OfflineVoice.prewarm(applicationContext, prefs.getInt(Prefs.VOICE_LANG, 0))
        resetComposing()
        assoc = emptyList()
        selecting = false
        if (sink != Sink.NONE) endSink()
        if (!restarting && !(sameEditor && SystemClock.uptimeMillis()<restoringHistoryUntil)) { undoStack.clear(); redoStack.clear(); lastTypingAt=0L; lastTypingGroup="" }
        val cls = info.inputType and InputType.TYPE_MASK_CLASS
        val variation = info.inputType and InputType.TYPE_MASK_VARIATION
        val password = cls == InputType.TYPE_CLASS_TEXT && variation in setOf(
            InputType.TYPE_TEXT_VARIATION_PASSWORD, InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD, InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD,
        ) || cls == InputType.TYPE_CLASS_NUMBER && variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD
        val latin = cls == InputType.TYPE_CLASS_TEXT && variation in setOf(
            InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS, InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS, InputType.TYPE_TEXT_VARIATION_URI,
        )
        learnField = !password && (info.imeOptions and EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING) == 0
        forceEn = password || latin
        zh = if (forceEn) false else prefs.getBoolean(Prefs.ZH, true)
        layer = when (cls) {
            InputType.TYPE_CLASS_NUMBER, InputType.TYPE_CLASS_PHONE, InputType.TYPE_CLASS_DATETIME -> Layer.NUM
            else -> Layer.LETTERS
        }
        shift = 0
        if (panel != Panel.NONE) closePanel(render = false)
        checkPrimaryClipForSuggestion()
        applySkin()
        updateAutoCaps()
    }

    /** 打开键盘时检查系统剪贴板是否有新复制的文字（即使复制发生在键盘收起期间也能识别） */
    private fun checkPrimaryClipForSuggestion() {
        val cm = clipboard ?: return
        val clip = runCatching { cm.primaryClip }.getOrNull() ?: return
        val ts = clip.description?.timestamp ?: 0L
        val recentCopy = ts > 0L && (System.currentTimeMillis() - ts) in 0..120_000L
        val item = clip.getItemAt(0) ?: return
        if (item.uri != null) return
        val text = runCatching { item.coerceToText(this)?.toString() }.getOrNull()?.trim().orEmpty()
        if (text.isEmpty()) return
        ClipStore.add(text)
        if ((recentCopy || pendingClipText == text) && text != dismissedClipText) {
            pendingClipText = text
            pendingClipAt = SystemClock.uptimeMillis()
        }
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        super.onFinishInputView(finishingInput)
        resetComposing()
        stopListening()
        if (::keyboard.isInitialized) {
            if (sink != Sink.NONE) endSink()
            if (panel != Panel.NONE) closePanel(render = false)
        }
        Lexicon.save()
    }

    override fun onFinishInput() {
        super.onFinishInput()
        resetComposing()
    }

    override fun onUpdateSelection(oldSelStart: Int, oldSelEnd: Int, newSelStart: Int, newSelEnd: Int, candidatesStart: Int, candidatesEnd: Int) {
        super.onUpdateSelection(oldSelStart, oldSelEnd, newSelStart, newSelEnd, candidatesStart, candidatesEnd)
        if (sink != Sink.NONE || (seq.isEmpty() && picks.isEmpty())) return
        // 拼音只显示在拼音条上，编辑框里没有组字文字；打拼音时光标被挪走了就放弃这次输入
        if (newSelStart != oldSelStart || newSelEnd != oldSelEnd) { resetComposing(); renderBar() }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (root != null) applySkin()
    }

    override fun onSharedPreferenceChanged(p: SharedPreferences?, key: String?) {
        if(key==Prefs.VOICE_LANG) { OfflineVoice.prewarm(applicationContext,prefs.getInt(Prefs.VOICE_LANG,0)); return }
        if (key == Prefs.FUZZY) { Lexicon.fuzzyRules = Prefs.fuzzy(this); if (seq.isNotEmpty()) refreshComposing(); return }
        if (root == null || key in setOf(Prefs.ZH, Prefs.RECENT_EMOJI, Prefs.RECENT_SYM, Prefs.COUNT_TODAY, Prefs.COUNT_DATE, Prefs.FLOAT_X, Prefs.FLOAT_Y,
                Prefs.TRANS_TO, Prefs.VOICE_LANG, Prefs.VOICE_OFFLINE, Prefs.UPDATE_CHECKED)) return
        if(key==Prefs.FAV_EMOJI) {
            (panelHost.getChildAt(0) as? EmojiPanel)?.updateFavs(favEmoji());return
        }
        if (key == Prefs.TOOLS || key == Prefs.MENU) {
            renderBar()
            // 排列视图自己持有最新顺序，保留当前实例和滚动位置。
            if (panel == Panel.MORE) buildPanel()
            return
        }
        if (key == Prefs.FLOAT || key == Prefs.FLOAT_WIDTH) { floating = prefs.getBoolean(Prefs.FLOAT, false); applyFloat(); return }
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

    private fun applySkin(rebuildPanel: Boolean = true) {
        val a = adj.takeIf { panel == Panel.ADJUST }
        skin = Prefs.theme(this).pick(Prefs.night(this)).styled(1f, prefs.getBoolean(Prefs.GLASS, false))
        val r = root ?: return
        bar.skin = skin
        sinkStrip.skin = skin
        bottomStrip.skin = skin
        tab.skin = skin
        candidatePreview.setTextColor(skin.candFg)
        candidatePreview.background=GradientDrawable().apply {
            setColor(if(skin.dark) skin.popBg else android.graphics.Color.WHITE)
            cornerRadius=10*resources.displayMetrics.density
            setStroke((resources.displayMetrics.density*.7f).toInt().coerceAtLeast(1),withAlpha(skin.barFg,80))
        }
        pinyinLens.skin=skin
        floatBar.invalidate()
        val scale=a?.scale ?: Prefs.heightScale(this)
        keyboard.heightScale = scale
        bar.uiScale=scale;bottomStrip.uiScale=scale;tab.uiScale=scale
        col.alpha=a?.opacity ?: Prefs.opacity(this)
        tab.alpha=col.alpha
        col.setLayerType(if(panel==Panel.ADJUST || col.alpha<.99f) View.LAYER_TYPE_HARDWARE else View.LAYER_TYPE_NONE,null)
        floatBar.requestLayout()
        keyboard.swipeEnabled = flag(Prefs.SWIPE)
        keyboard.spaceLong = prefs.getInt(Prefs.SPACE_LONG, 0)
        keyboard.enterIcon = enterIcon(currentInputEditorInfo)
        applyFloatBackground(r)
        applyPads()
        applyBlur()
        render()
        if (panel != Panel.NONE && rebuildPanel) buildPanel()
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
        val l = if (floating) 0 else a?.padL ?: prefs.getInt(Prefs.PAD_L, 0)
        val rr = if (floating) 0 else a?.padR ?: prefs.getInt(Prefs.PAD_R, 0)
        val b = if (floating) 0 else a?.padB ?: prefs.getInt(Prefs.PAD_B, 0)
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
            col.background = skinBg()
            col.elevation = 0f
            col.clipToOutline = false
        }
    }

    private fun applyFloat() {
        val r = root ?: return
        val dm = resources.displayMetrics
        val lpCol = col.layoutParams as FrameLayout.LayoutParams
        val oldWidth=lpCol.width;val oldGravity=lpCol.gravity
        val a = adj.takeIf { panel == Panel.ADJUST }
        if (floating) {
            lpCol.width = (dm.widthPixels * (a?.floatWidth ?: prefs.getFloat(Prefs.FLOAT_WIDTH,.86f)).coerceIn(.55f,1f)).toInt()
            lpCol.gravity = Gravity.TOP or Gravity.START
            floatBar.visibility = View.VISIBLE
            bottomStrip.visibility = View.GONE
            if(oldWidth!=lpCol.width || oldGravity!=lpCol.gravity) col.layoutParams = lpCol
            placeFloat(a?.floatX ?: prefs.getFloat(Prefs.FLOAT_X, dm.widthPixels * 0.07f), a?.floatY ?: prefs.getFloat(Prefs.FLOAT_Y, dm.heightPixels * 0.45f))
            if (a != null) { a.floatX=col.translationX; a.floatY=col.translationY }
        } else {
            lpCol.width = MATCH_PARENT
            lpCol.gravity = Gravity.BOTTOM
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
        col.translationX = x.coerceIn(0f, (dm.widthPixels - w).toFloat().coerceAtLeast(0f))
        col.translationY = y.coerceIn(0f, (dm.heightPixels - h).toFloat().coerceAtLeast(0f))
        positionTab()
    }

    private fun setFloating(on: Boolean) {
        floating = on
        prefs.edit().putBoolean(Prefs.FLOAT, on).apply()
        if (panel != Panel.NONE) closePanel(render = false)
        applyFloat()
        render()
    }

    private fun rowsForLayer(): List<Row> = when {
        layer == Layer.NUM -> Layouts.numpad(Prefs.numKeys(this))
        !zh -> Layouts.letters(false)
        else -> when (Prefs.layout(this)) {
            "14" -> Layouts.fourteen()
            "9" -> Layouts.nine()
            else -> Layouts.letters(true)
        }
    }

    private fun sideForLayer(): List<String>? = when {
        layer == Layer.NUM -> Prefs.numSide(this)
        zh && Prefs.layout(this) == "9" -> Layouts.NINE_SIDE
        else -> null
    }

    private var renderedKeys = ""
    private fun render() {
        if (root == null) return
        val keys = "$layer/$zh/${Prefs.layout(this)}"
        val changed = renderedKeys.isNotEmpty() && renderedKeys != keys
        renderedKeys = keys
        keyboard.zh = zh
        keyboard.shiftState = shift
        keyboard.splitMode = splitMode()
        keyboard.splitLayout = layer == Layer.LETTERS && (adj?.split ?: prefs.getBoolean(Prefs.SPLIT_LAYOUT, false))
        keyboard.setup(skin, rowsForLayer(), sideForLayer())
        if (changed && keyboard.visibility == View.VISIBLE) NativeMotion.pulse(keyboard)
        renderBar()
    }

    private fun splitMode() = zh && layer == Layer.LETTERS && seq.isNotEmpty()

    /** 打开面板时工具栏里高亮哪个工具 */
    private fun activeTool(): String? {
        val id = when (panel) {
            Panel.EMOJI -> "emoji"; Panel.CLIP, Panel.TOKENS -> "clip"; Panel.EDIT -> "edit"; Panel.SKIN -> "skin"
            Panel.HAND, Panel.HAND_CANDS -> "handwrite"; Panel.VOICE -> "voice"
            Panel.ADJUST -> "adjust"; Panel.ARRANGE -> "arrange"; Panel.LAYOUT -> "layout"; Panel.MORE -> "more"
            else -> null
        } ?: return null
        return if (id == "more" || id in Prefs.tools(this)) id else "more"
    }

    private fun candItems() = cands.map { CandItem(it.w, it.py, it.mask) }

    private fun renderBar() {
        if (root == null) return
        val sm = splitMode()
        if (keyboard.splitMode != sm) { keyboard.splitMode = sm; keyboard.invalidate() }
        renderTab()
        when {
            seq.isNotEmpty() -> bar.showCandidates(candItems(), panel == Panel.CANDS)
            sink == Sink.TRANSLATE && langPicking -> bar.showAssoc(Translator.TARGETS.map { it.second })
            sink == Sink.TRANSLATE -> bar.showMessage(transStatus.ifEmpty { "译文直接显示在输入框里，点“完成”确认；点左边的语言可以换" })
            panel in listOf(Panel.HAND, Panel.HAND_CANDS) && handCands.isNotEmpty() -> bar.showCandidates(handCands.map { w -> CandItem(w, if (w.any { it in '一'..'鿿' }) Lexicon.pinyinOf(w) else null) }, panel == Panel.HAND_CANDS)
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

    private fun commitOut(text: String, typing: Boolean = false) {
        if (sink != Sink.NONE) {
            sinkBuf.append(text)
            sinkStrip.value = sinkBuf.toString()
            sinkStrip.composing = ""
            sinkStrip.refresh()
            onSinkChanged()
            return
        }
        checkpoint(typing = typing, group="latin")
        ic()?.commitText(text, 1)
        Prefs.addTyped(this, text.count { it.isLetterOrDigit() })
    }

    /** 正在打的拼音：输入条模式下显示在输入条里；平时只显示在拼音条上，不写进编辑框 */
    private fun composeOut(text: String) {
        if (sink != Sink.NONE) {
            sinkStrip.composing = text
            sinkStrip.refresh()
        }
        renderTab()
    }

    // —————————— 拼音条 ——————————

    private fun tabDisplay() = Lexicon.display(seq.toList(), null)

    private fun renderTab() {
        if (!::tab.isInitialized) return
        val show = (seq.isNotEmpty() || picks.isNotEmpty()) && sink == Sink.NONE
        if (!show) { if (tab.visibility != View.GONE) tab.visibility = View.GONE;candidatePreview.visibility=View.GONE;pinyinLens.visibility=View.GONE; return }
        if (pyCaret >= seq.size) pyCaret = -1
        val text = pendingText() + tabDisplay()
        tab.set(text, if (pyCaret < 0) -1 else displayIndexOf(pyCaret))
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
        if (sink != Sink.NONE || currentInputEditorInfo?.extras?.getInt("com.kongye.input.EDITOR_PROTOCOL",0)==1) return
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
        if (sink != Sink.NONE) return
        if (seq.isNotEmpty() || picks.isNotEmpty()) { resetComposing(); composeOut(""); renderBar(); return }
        if(editorCommand("UNDO")) return
        val s = undoStack.removeLastOrNull()
        if (s == null) { if (ic()?.performContextMenuAction(android.R.id.undo) != true) message("没有可以撤销的输入"); return }
        snap()?.let { redoStack.addLast(it) }
        restore(s)
    }

    private fun redo() {
        if(editorCommand("REDO")) return
        val s = redoStack.removeLastOrNull()
        if (s == null) { if (ic()?.performContextMenuAction(android.R.id.redo) != true) message("没有可以重做的输入"); return }
        snap()?.let { undoStack.addLast(it) }
        restore(s)
    }

    // —————————— 拼音组字 ——————————

    private fun pendingText() = picks.joinToString("") { it.w }

    private fun resetComposing() {
        candidateRevision++;candidateTask?.cancel(false);candidatesPending=false
        seq.clear()
        picks.clear()
        pyCaret = -1
        cands = emptyList()
        if (::sinkStrip.isInitialized) sinkStrip.composing = ""
        if (::tab.isInitialized) tab.reset()
    }

    private val candidateWorker=java.util.concurrent.Executors.newSingleThreadExecutor { task -> Thread(task,"Kongye candidates").apply {isDaemon=true} }
    private var candidateTask:java.util.concurrent.Future<*>?=null
    private var candidateRevision=0
    private var candidatesPending=false
    private fun refreshComposing() {
        val keys=seq.toList();val revision=++candidateRevision
        val pending=pendingText();val abbr=flag(Prefs.ABBR)
        candidatesPending=true
        val immediate=pending+Lexicon.display(keys,null)
        composeOut(immediate)
        candidateTask?.cancel(false)
        candidateTask=candidateWorker.submit {
            val result=runCatching {Lexicon.candidates(keys,abbr)}.getOrElse {
                android.util.Log.e("KongyeIME","Candidate query failed",it)
                listOf(Cand(keys.joinToString("") {it.take(1)},keys.count {it!="'"},null,0f))
            }
            bar.postOnAnimation {
                if(revision!=candidateRevision || seq.toList()!=keys) return@postOnAnimation
                cands=result;candidatesPending=false
                renderBar()
                if(panel==Panel.CANDS) buildPanel()
            }
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

    private fun pick(c: Cand) {
        if (c.n <= 0 || c.n > letterCount()) return
        val taken = consume(c.n)
        pyCaret = -1
        val syl = c.e?.syl?.toList() ?: Lexicon.decode(taken.filter { it != "'" })
        picks.add(Pick(c.w, syl, taken, c.e == null))
        if (learnOn()) {
            if (c.sentence) Lexicon.learnPhrase(c.w, syl) else if (c.e != null) Lexicon.recordPick(c.e)
        }
        if (letterCount() == 0) { seq.clear(); finishPicks() } else refreshComposing()
    }

    /** 一个字一个字连着打（中间没有别的输入）时，把它们合起来记成一个词，比如分三次打出的“夏星月” */
    private val chainW = StringBuilder()
    private val chainSyl = ArrayList<String>()
    private var chainAt = 0L

    private fun breakChain() { chainW.setLength(0); chainSyl.clear() }

    private fun extendChain(text: String, syl: List<String>) {
        val now = SystemClock.uptimeMillis()
        if (now - chainAt > 6000 || text.length != 1 || syl.size != 1 || chainW.length >= 4) breakChain()
        chainAt = now
        if (text.length != 1 || syl.size != 1 || text[0] !in '一'..'鿿') return
        chainW.append(text)
        chainSyl.addAll(syl)
        if (chainW.length >= 2) Lexicon.learnPhrase(chainW.toString(), chainSyl.toList())
    }

    private fun finishPicks() {
        val text = pendingText()
        commitOut(text)
        if (learnOn() && picks.size >= 2 && picks.none { it.isRaw }) Lexicon.learnPhrase(text, picks.flatMap { it.syl })
        if (learnOn() && picks.none { it.isRaw }) extendChain(text, picks.flatMap { it.syl }) else breakChain()
        picks.clear()
        cands = emptyList()
        assoc = if (zh && sink == Sink.NONE && flag(Prefs.ASSOC)) Lexicon.assoc(text) else emptyList()
        if (panel == Panel.CANDS) closePanel(render = false)
        renderBar()
    }

    /** 标点、回车等打断组字时：一路选第一个候选直到拼音用完 */
    private fun finalizeComposing() {
        var guard = 0
        while (seq.isNotEmpty() && guard++ < 40) {
            if (letterCount() == 0) { seq.clear(); if (picks.isNotEmpty()) finishPicks(); return }
            pick(Lexicon.candidates(seq.toList(), flag(Prefs.ABBR)).first())
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
        val lv = prefs.getInt(Prefs.VIB_STRENGTH, 2)
        if (lv <= 0) return
        val v = vibrator ?: return
        val (ms, amp) = when (lv) { 1 -> 10L to 70; 2 -> 16L to 150; else -> 24L to 255 }
        runCatching { v.vibrate(VibrationEffect.createOneShot(ms, if (v.hasAmplitudeControl()) amp else VibrationEffect.DEFAULT_AMPLITUDE)) }
    }

    override fun onPress(key: Key) {
        buzz()
        if (prefs.getBoolean(Prefs.SOUND, false)) {
            val fx = when (key.kind) {
                Kind.DEL -> AudioManager.FX_KEYPRESS_DELETE
                Kind.ENTER -> AudioManager.FX_KEYPRESS_RETURN
                Kind.SPACE -> AudioManager.FX_KEYPRESS_SPACEBAR
                else -> AudioManager.FX_KEYPRESS_STANDARD
            }
            getSystemService(AudioManager::class.java)?.playSoundEffect(fx, -1f)
        }
    }

    override fun onKey(key: Key) {
        when (key.kind) {
            Kind.LETTER -> typeLetter(key)
            Kind.CHAR -> typeChar(key.out)
            Kind.SPACE -> {
                if (seq.isNotEmpty()) cands.firstOrNull()?.let { pick(it) } ?: commitRaw()
                else { assoc = emptyList(); commitOut(" "); renderBar(); updateAutoCaps() }
            }
            Kind.ENTER -> {
                if (seq.isNotEmpty()) { commitRaw(); return }
                assoc = emptyList()
                if (sink != Sink.NONE) { sinkConfirm(); return }
                checkpoint(typing = false)
                performEnter()
                renderBar()
                updateAutoCaps()
            }
            Kind.DEL -> delete()
            Kind.SHIFT -> tapShift()
            Kind.SEP -> if (seq.isNotEmpty()) { addSeparator(); refreshComposing() } else typeChar("1")
            Kind.RESET -> if (seq.isNotEmpty() || picks.isNotEmpty()) { resetComposing(); composeOut(""); if (sink == Sink.NONE) ic()?.finishComposingText(); renderBar() }
            Kind.NUM -> { layer = Layer.NUM; render() }
            Kind.SYM -> openPanel(Panel.SYMBOL)
            Kind.ABC -> { layer = Layer.LETTERS; render(); updateAutoCaps() }
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
            key.longEmoji -> openPanel(Panel.EMOJI)
            key.kind == Kind.SHIFT && !splitMode() -> { shift = 2; keyboard.shiftState = 2; keyboard.invalidate() }
        }
    }

    /** 长按工具栏的编辑工具后左右滑 */
    override fun onToolCursor(dir: Int) { onCursorMove(dir, 0) }

    override fun onCursorModeChange(active: Boolean) {
        if (::bar.isInitialized) bar.cursorMode = active
        if (::bottomStrip.isInitialized) bottomStrip.cursorMode = active
    }

    override fun onCursorMove(dx: Int, dy: Int) {
        if (seq.isNotEmpty() || sink != Sink.NONE) return
        when {
            dx < 0 -> sendDownUpKeyEvents(KeyEvent.KEYCODE_DPAD_LEFT)
            dx > 0 -> sendDownUpKeyEvents(KeyEvent.KEYCODE_DPAD_RIGHT)
            dy < 0 -> sendDownUpKeyEvents(KeyEvent.KEYCODE_DPAD_UP)
            dy > 0 -> sendDownUpKeyEvents(KeyEvent.KEYCODE_DPAD_DOWN)
        }
    }

    override fun onSwipeDel(down: Boolean) {
        if (!down) { undo(); return }
        // 下滑清空
        if (seq.isNotEmpty() || picks.isNotEmpty()) { resetComposing(); composeOut(""); if (sink == Sink.NONE) ic()?.finishComposingText() }
        if (sink != Sink.NONE) {
            sinkBuf.setLength(0); sinkStrip.value = ""; sinkStrip.refresh(); onSinkChanged()
        } else {
            checkpoint(typing = false)
            val ic = ic() ?: return
            ic.beginBatchEdit()
            ic.commitText("", 1)
            ic.deleteSurroundingText(100000, 100000)
            ic.endBatchEdit()
        }
        assoc = emptyList()
        renderBar()
    }

    private fun typeLetter(k: Key) {
        pendingClipText?.let { dismissedClipText = it; pendingClipText = null }
        val ch = k.letters.take(1)
        if (!zh || layer != Layer.LETTERS || shift > 0) {
            if (seq.isNotEmpty()) finalizeComposing()
            assoc = emptyList()
            commitOut(if (shift > 0) ch.uppercase() else ch, typing=true)
            if (shift == 1) { shift = 0; keyboard.shiftState = 0; keyboard.invalidate() }
            renderBar()
            return
        }
        if (seq.size >= 128) return
        insertKey(k.letters)
        assoc = emptyList()
        refreshComposing()
    }

    /** 分词符：插在拼音条光标处，不重复 */
    private fun addSeparator() {
        val at = if (pyCaret in 0 until seq.size) pyCaret else seq.size
        if (at == 0 || seq[at - 1] == "'" || seq.getOrNull(at) == "'") return
        insertKey("'")
    }

    private fun typeChar(s: String, paired: Boolean = false) {
        breakChain()
        pendingClipText?.let { dismissedClipText = it; pendingClipText = null }
        if (seq.isNotEmpty()) finalizeComposing()
        assoc = emptyList()
        commitOut(s)
        // 打了 @：联想常见邮箱后缀
        if (s == "@" && sink == Sink.NONE) assoc = EMAIL_SUFFIXES
        if (paired && sink == Sink.NONE && s in PAIRED_SYMBOLS) {
            ic()?.let { connection ->
                val ext = connection.getExtractedText(ExtractedTextRequest(), 0)
                if (ext != null && ext.selectionEnd >= 1) {
                    val middle = ext.startOffset + ext.selectionEnd - 1
                    if (!connection.setSelection(middle, middle)) moveCursor(-1)
                } else moveCursor(-1)
            }
        }
        renderBar()
        updateAutoCaps()
    }

    private fun delete() {
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
            if (seq.isEmpty() && picks.isNotEmpty()) seq.addAll(picks.removeAt(picks.lastIndex).keys)
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
        breakChain()
        if (sink != Sink.NONE) {
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
        val selected=connection.getSelectedText(0)
        if(!selected.isNullOrEmpty()) connection.commitText("",1)
        else if(!connection.deleteSurroundingTextInCodePoints(1,0)) sendDownUpKeyEvents(KeyEvent.KEYCODE_DEL)
    }

    private fun tapShift() {
        if (splitMode()) {
            // 中文组字时大写键是“分词”
            addSeparator()
            refreshComposing()
            return
        }
        val now = SystemClock.uptimeMillis()
        shift = when {
            shift == 2 -> 0
            shift == 1 && now - lastShiftTap < 350 -> 2
            shift == 1 -> 0
            else -> 1
        }
        lastShiftTap = now
        keyboard.shiftState = shift
        keyboard.invalidate()
    }

    private fun toggleLang() {
        if (seq.isNotEmpty()) commitRaw()
        zh = !zh
        if (!forceEn) prefs.edit().putBoolean(Prefs.ZH, zh).apply()
        shift = 0
        layer = Layer.LETTERS
        assoc = emptyList()
        render()
        updateAutoCaps()
    }

    private fun updateAutoCaps() {
        if (zh || layer != Layer.LETTERS || shift == 2 || root == null || sink != Sink.NONE) return
        val info = currentInputEditorInfo ?: return
        val caps = ic()?.getCursorCapsMode(info.inputType) ?: 0
        val want = if (caps != 0) 1 else 0
        if (want != shift) {
            shift = want
            keyboard.shiftState = shift
            keyboard.invalidate()
        }
    }

    private fun performEnter() {
        val info = currentInputEditorInfo
        val action = (info?.imeOptions ?: 0) and EditorInfo.IME_MASK_ACTION
        val noAction = info == null || (info.imeOptions and EditorInfo.IME_FLAG_NO_ENTER_ACTION) != 0 ||
            action == EditorInfo.IME_ACTION_NONE || action == EditorInfo.IME_ACTION_UNSPECIFIED
        if (noAction) sendKeyChar('\n') else ic()?.performEditorAction(action)
    }

    // —————————— 候选栏与工具栏 ——————————

    override fun onCandidate(index: Int) {
        if(candidatesPending && seq.isNotEmpty()) return
        if (seq.isEmpty() && (panel == Panel.HAND || panel == Panel.HAND_CANDS)) {
            val w = handCands.getOrNull(index) ?: return
            commitOut(w)
            handCands = emptyList()
            handView?.clear()
            if (panel == Panel.HAND_CANDS) { panel = Panel.HAND; buildPanel() }
            assoc = if (flag(Prefs.ASSOC)) Lexicon.assoc(w) else emptyList()
            renderBar()
            return
        }
        cands.getOrNull(index)?.let { pick(it) }
    }

    override fun onAssoc(index: Int) {
        if (sink == Sink.TRANSLATE && langPicking) {
            val t = Translator.TARGETS.getOrNull(index) ?: return
            prefs.edit().putString(Prefs.TRANS_TO, t.first).apply()
            langPicking = false
            sinkStrip.chip = t.second
            sinkStrip.refresh()
            renderBar()
            onSinkChanged()
            return
        }
        val w = assoc.getOrNull(index) ?: return
        commitOut(w)
        assoc = Lexicon.assoc(w)
        renderBar()
    }

    override fun onDismissAssoc() {
        if (langPicking) { langPicking = false; renderBar(); return }
        assoc = emptyList()
        renderBar()
    }

    override fun onClipPaste() {
        val t = pendingClipText ?: return
        dismissedClipText = t
        pendingClipText = null
        typeChar(t)
    }

    override fun onClipSplit() {
        val t = pendingClipText ?: return
        dismissedClipText = t
        pendingClipText = null
        tokens = Lexicon.segment(t)
        tokensFromBar = true
        openPanel(Panel.TOKENS)
    }

    override fun onClipDismiss() {
        pendingClipText?.let { dismissedClipText = it }
        pendingClipText = null
        renderBar()
    }

    override fun onTool(id: String) {
        // 再点一下正打开的那个工具：收起面板回到键盘
        if (panel != Panel.NONE && id == activeTool()) { closePanel(); return }
        runFeature(id)
    }

    private fun newAdjustment(): Adjust {
        val dm = resources.displayMetrics
        return Adjust(Prefs.heightScale(this), Prefs.opacity(this), prefs.getInt(Prefs.PAD_L, 0), prefs.getInt(Prefs.PAD_R, 0), prefs.getInt(Prefs.PAD_B, 0),
            prefs.getBoolean(Prefs.SPLIT_LAYOUT, false), floating, prefs.getFloat(Prefs.FLOAT_WIDTH, .86f).coerceIn(.55f,1f),
            prefs.getFloat(Prefs.FLOAT_X, dm.widthPixels*.07f), prefs.getFloat(Prefs.FLOAT_Y, dm.heightPixels*.45f))
    }

    private fun runFeature(id: String) {
        if (panel == Panel.VOICE && id != "voice") closePanel()
        when (id) {
            "more" -> openPanel(Panel.MORE)
            "settings" -> openSettings()
            "adjust" -> {
                adj = newAdjustment()
                openPanel(Panel.ADJUST)
            }
            "arrange" -> openPanel(Panel.ARRANGE)
            "layout" -> openPanel(Panel.LAYOUT)
            "emoji" -> openPanel(Panel.EMOJI)
            "clip" -> openPanel(Panel.CLIP)
            "edit" -> openPanel(Panel.EDIT)
            "skin" -> openPanel(Panel.SKIN)
            "undo" -> undo()
            "redo" -> redo()
            "voice" -> { if (sink != Sink.NONE) voiceIntoSink() else { openVoice(); if (micGranted()) toggleListening() } }
            "handwrite" -> { handCands = emptyList(); openPanel(Panel.HAND) }
            "translate" -> startSink(Sink.TRANSLATE, "", null)
            "float" -> setFloating(!floating)
            "lang" -> toggleLang()
        }
    }

    override fun onExpand() {
        if (panel == Panel.HAND || panel == Panel.HAND_CANDS) {
            panel = if (panel == Panel.HAND) Panel.HAND_CANDS else Panel.HAND
            buildPanel()
            renderBar()
            return
        }
        if (panel == Panel.CANDS) closePanel() else openPanel(Panel.CANDS)
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
        cancelVoiceDraft()
        voiceText.setLength(0)
        voicePartial = ""
        voiceToSink = false
        voiceStatus = if (micGranted()) "已暂停" else "需要麦克风权限：点中间的麦克风去授权"
        openPanel(Panel.VOICE)
        if (sink == Sink.NONE) {
            voiceBefore = snap()
            voiceDraft = ic()?.let { VoiceDraft(it) }
        }
    }

    private fun cancelVoiceDraft() {
        voiceDraft?.cancel(); voiceDraft = null; voiceBefore = null
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
        voiceDraft?.preview(voiceText.toString() + voicePartial)
        voiceBoard?.update(voiceText.toString(), voicePartial, voiceStatus, listening)
    }

    /** 四川话：系统识别服务没有四川话模型时，退回普通话 */
    private var sichuanFallback = false

    private fun voiceLanguage(): String = when {
        !zh && !voiceToSink -> "en-US"
        prefs.getInt(Prefs.VOICE_LANG, 0) == 1 && !sichuanFallback -> "zh-CN-sichuan"
        else -> "zh-CN"
    }

    private fun toggleListening() {
        if (listening) {
            voiceWantsListening = false
            offlineSpeech?.let { it.stop(); voiceStatus = "正在识别..."; updateVoice(); return }
            stopListening(); updateVoice(); return
        }
        if (!micGranted()) { openSettings("grant_mic"); return }
        voiceWantsListening = true
        val dialect = if (!zh && !voiceToSink) 0 else prefs.getInt(Prefs.VOICE_LANG, 0)
        if (OfflineVoice.ready(this, dialect)) { startOfflineVoice(dialect); return }
        if (prefs.getBoolean(Prefs.VOICE_OFFLINE, false)) {
            voicePack()
            return
        }
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            voiceStatus = "这台手机没有可用的语音识别服务"
            updateVoice(); return
        }
        val sr = speech ?: SpeechRecognizer.createSpeechRecognizer(this).also { speech = it }
        sr.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                // 系统的谷歌语音服务没有四川话模型，会按普通话识别（不报错），这里如实说明
                voiceStatus = if (!voiceToSink && zh && prefs.getInt(Prefs.VOICE_LANG, 0) == 1) "请说话（识别服务没有四川话模型，按普通话识别，口音重时可能不准）"
                else "正在聆听..."
                updateVoice()
            }
            override fun onBeginningOfSpeech() = Unit
            override fun onRmsChanged(rmsdB: Float) { voiceBoard?.level(rmsdB) }
            override fun onBufferReceived(buffer: ByteArray?) = Unit
            override fun onEndOfSpeech() { voiceStatus = "正在识别…"; updateVoice() }
            override fun onError(error: Int) {
                listening = false
                if ((error == 12 || error == 13) && voiceLanguage() == "zh-CN-sichuan") {
                    // ERROR_LANGUAGE_NOT_SUPPORTED / UNAVAILABLE：没有四川话模型，用普通话再来
                    sichuanFallback = true
                    voiceStatus = "识别服务没有四川话模型，已改用普通话识别"
                    updateVoice()
                    main.postDelayed({ toggleListening() }, 200)
                    return
                }
                // 部分系统在流式识别结束后可能回调 ERROR_NO_MATCH，先保留已识别出的 partial 文字
                val leftover = voicePartial.trim()
                voicePartial = ""
                if (leftover.isNotEmpty()) {
                    if (voiceToSink) {
                        sinkBuf.append(leftover)
                        sinkStrip.value = sinkBuf.toString()
                        onSinkChanged()
                    } else {
                        voiceText.append(leftover)
                    }
                }
                voiceStatus = when (error) {
                    SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> if (leftover.isNotEmpty()) "语音已转文字" else "没听到声音，点麦克风继续说"
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "需要麦克风权限"
                    SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "语音识别需要联网，网络不通"
                    else -> "语音识别出错（$error），点麦克风再试"
                }
                updateVoice()
            }
            override fun onResults(results: Bundle?) {
                listening = false
                val recognized = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
                val text = recognized.ifEmpty { voicePartial }
                voicePartial = ""
                if (voiceToSink) {
                    if (text.isNotEmpty()) {
                        sinkBuf.append(text)
                        sinkStrip.value = sinkBuf.toString()
                        onSinkChanged()
                    }
                    voiceStatus = if (text.isNotEmpty()) "语音识别完成，正在翻译…" else "点麦克风继续说话"
                    updateVoice()
                    return
                }
                voiceText.append(text)
                voiceStatus = "说完点“确定”上屏，点“取消”丢掉"
                updateVoice()
                // 连续说：一句识别完接着听
                if (voiceWantsListening && panel == Panel.VOICE && text.isNotEmpty()) main.postDelayed({ if (voiceWantsListening && panel == Panel.VOICE && !listening) toggleListening() }, 150)
            }
            override fun onPartialResults(partialResults: Bundle?) {
                voicePartial = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
                updateVoice()
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
            override fun status(text: String) { if (active()) { voiceStatus = text; updateVoice() } }
            override fun level(value: Float) { if (active()) voiceBoard?.level(value) }
            override fun spectrum(values: FloatArray) { if (active()) voiceBoard?.spectrum(values) }
            override fun partial(text: String) { if (active()) { voicePartial = text; updateVoice() } }
            override fun result(text: String) {
                if (!active()) return
                voicePartial = ""
                if (voiceToSink) {
                    sinkBuf.append(text); sinkStrip.value = sinkBuf.toString(); onSinkChanged()
                } else voiceText.append(text)
                updateVoice()
            }
            override fun stopped(error: String?) {
                if (!active()) return
                offlineSpeech = null; listening = false
                voiceStatus = error ?: "已暂停"
                updateVoice()
                val finish = offlineAfterStop; offlineAfterStop = null
                finish?.invoke()
            }
        })
        offlineSpeech = session; listening = true
        voiceStatus = "正在打开麦克风…"
        updateVoice(); session.start()
    }

    private fun confirmVoice() {
        val finish = {
            val t = voiceText.toString() + voicePartial
            stopListening()
            val draft = voiceDraft
            val before = voiceBefore
            voiceDraft = null; voiceBefore = null
            if (draft != null) {
                draft.preview(t)
                draft.confirm()
                if (t.isNotEmpty()) {
                    if (before != null && undoStack.lastOrNull() != before) undoStack.addLast(before)
                    while (undoStack.size > 100) undoStack.removeFirst()
                    redoStack.clear(); lastTypingAt = 0L
                    Prefs.addTyped(this, t.count { it.isLetterOrDigit() })
                }
            }
            voiceText.setLength(0); voicePartial = ""
            closePanel()
            if (draft == null && t.isNotEmpty()) commitOut(t)
        }
        val session = offlineSpeech
        if (session != null && listening) {
            offlineAfterStop = finish
            voiceStatus = "正在识别..."; updateVoice()
            session.stop()
        } else finish()
    }

    private fun stopListening() {
        voiceWantsListening = false
        offlineSpeech?.cancel(); offlineSpeech = null; offlineAfterStop = null
        if (listening) runCatching { speech?.cancel() }
        listening = false
        voiceStatus = "已暂停"
        val leftover = voicePartial.trim()
        if (leftover.isNotEmpty()) {
            voicePartial = ""
            if (voiceToSink) {
                sinkBuf.append(leftover)
                sinkStrip.value = sinkBuf.toString()
                onSinkChanged()
            } else {
                voiceText.append(leftover)
            }
        }
    }

    // —————————— 剪贴板 ——————————

    private fun captureClip() {
        if (SystemClock.uptimeMillis() < ignoreClipUntil) return
        val cm = clipboard ?: return
        val clip = runCatching { cm.primaryClip }.getOrNull() ?: return
        val desc = clip.description
        val item = clip.getItemAt(0) ?: return
        val uri = item.uri
        if (uri != null && (0 until desc.mimeTypeCount).any { desc.getMimeType(it).startsWith("image/") }) {
            if (uri.toString() == lastImageUri) return
            lastImageUri = uri.toString()
            val mime = (0 until desc.mimeTypeCount).map { desc.getMimeType(it) }.firstOrNull { it.startsWith("image/") } ?: "image/png"
            val ext = when { mime.contains("jpeg") || mime.contains("jpg") -> "jpg"; mime.contains("gif") -> "gif"; mime.contains("webp") -> "webp"; else -> "png" }
            val dir = ClipStore.dir ?: return
            val name = "clip_${System.currentTimeMillis()}.$ext"
            val ok = runCatching {
                contentResolver.openInputStream(uri)?.use { input -> File(dir, name).outputStream().use { input.copyTo(it) } } != null
            }.getOrDefault(false)
            if (ok) ClipStore.addImage(name)
        } else {
            val text = runCatching { item.coerceToText(this)?.toString() }.getOrNull()
            if (text.isNullOrBlank()) return
            ClipStore.add(text)
            val trimmed = text.trim()
            pendingClipText = trimmed
            pendingClipAt = SystemClock.uptimeMillis()
            if (trimmed == dismissedClipText) dismissedClipText = ""
        }
        if (panel == Panel.CLIP) buildPanel() else renderBar()
    }

    private fun pasteClip(c: Clip) {
        val f = ClipStore.imageFile(c)
        if (f == null) { typeChar(c.text); return }
        if (!f.exists()) { message("图片文件已经不在了"); return }
        val uri = FileProvider.getUriForFile(this, "com.kongye.keyboard.files", f)
        val mime = when (f.extension) { "jpg" -> "image/jpeg"; "gif" -> "image/gif"; "webp" -> "image/webp"; else -> "image/png" }
        val accepts = currentInputEditorInfo?.contentMimeTypes?.any { ClipDescription.compareMimeTypes(mime, it) } == true
        if (accepts) {
            val info = InputContentInfo(uri, ClipDescription("图片", arrayOf(mime)), null)
            if (ic()?.commitContent(info, InputConnection.INPUT_CONTENT_GRANT_READ_URI_PERMISSION, null) == true) return
        }
        ignoreClipUntil = SystemClock.uptimeMillis() + 1500
        grantUriPermission(currentInputEditorInfo?.packageName ?: packageName, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        clipboard?.setPrimaryClip(ClipData.newUri(contentResolver, "图片", uri))
        message("这个输入框不能直接插图片，已复制到系统剪贴板，可以长按粘贴")
    }

    private fun clipAction(c: Clip, id: String) {
        when {
            id == "pin" -> ClipStore.togglePin(c)
            id == "copy" -> {
                ignoreClipUntil = SystemClock.uptimeMillis() + 1500
                val f = ClipStore.imageFile(c)
                if (f != null) clipboard?.setPrimaryClip(ClipData.newUri(contentResolver, "图片", FileProvider.getUriForFile(this, "com.kongye.keyboard.files", f)))
                else clipboard?.setPrimaryClip(ClipData.newPlainText("空叶键盘", c.text))
                message("已复制")
            }
            id == "edit" -> { startSink(Sink.EDIT, c.text, c); return }
            id == "split" -> { tokens = Lexicon.segment(c.text); tokensFromBar = false; openPanel(Panel.TOKENS); return }
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

    private fun startSink(mode: Sink, initial: String, clip: Clip?) {
        NativeMotion.swap(root,col) {startSinkNow(mode,initial,clip)}
    }
    private fun startSinkNow(mode: Sink, initial: String, clip: Clip?) {
        if (seq.isNotEmpty()) resetComposing()
        closePanel(render = false, animate=false)
        sink = mode
        sinkClip = clip
        sinkBuf.setLength(0)
        sinkBuf.append(initial)
        langPicking = false
        transResult = ""
        transStatus = ""
        sinkStrip.chip = if (mode == Sink.TRANSLATE) Translator.name(prefs.getString(Prefs.TRANS_TO, "auto") ?: "auto") else null
        sinkStrip.translatedPreview = ""
        sinkStrip.micOn = false
        sinkStrip.onChip = { langPicking = !langPicking; renderBar() }
        sinkStrip.onMic = { voiceIntoSink() }
        sinkStrip.onClear = {
            sinkBuf.setLength(0); sinkStrip.value = ""; sinkStrip.composing = ""; sinkStrip.translatedPreview = ""; sinkStrip.refresh()
            resetComposing(); onSinkChanged(); renderBar()
        }
        sinkStrip.multiline = mode == Sink.EDIT
        sinkStrip.icon = when (mode) { Sink.EDIT -> R.drawable.ic_edit; Sink.TRANSLATE -> R.drawable.ic_translate; Sink.NEWCAT -> R.drawable.ic_clip; Sink.FAVORITE -> R.drawable.ic_emoji; else -> R.drawable.ic_search }
        sinkStrip.hint = when (mode) { Sink.EDIT -> "修改内容"; Sink.TRANSLATE -> "输入要翻译的中文或外文"; Sink.NEWCAT -> "新分类的名字"; Sink.FAVORITE -> "输入要收藏的表情或颜文字"; else -> "搜索剪贴板" }
        sinkStrip.value = initial
        sinkStrip.composing = ""
        sinkStrip.showAnimated(true,animate=false)
        sinkStrip.refresh()
        layer = Layer.LETTERS
        assoc = emptyList()
        render()
        updateClipSearch()
    }

    private fun endSink(animate: Boolean=false) {
        if(animate) {NativeMotion.swap(root,col) {endSink(false)};return}
        resetComposing()
        if (sink == Sink.TRANSLATE) { Translator.cancel(); main.removeCallbacks(transTask) }
        if (voiceToSink) { stopListening(); voiceToSink = false }
        sink = Sink.NONE
        sinkClip = null
        langPicking = false
        if (::sinkStrip.isInitialized) sinkStrip.showAnimated(false, animate)
        updateClipSearch()
    }

    /** 输入条里的字变了：剪贴板搜索实时出结果，翻译实时出译文 */
    private fun onSinkChanged() {
        when (sink) {
            Sink.SEARCH -> updateClipSearch()
            Sink.TRANSLATE -> { main.removeCallbacks(transTask); main.postDelayed(transTask, 450) }
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
        Translator.run(applicationContext, text, false, target) { ok, result, status ->
            if (sink != Sink.TRANSLATE || (sinkBuf.toString() + sinkStrip.composing).trim() != text) return@run
            if (ok) {
                transResult = result
                sinkStrip.translatedPreview = result
                sinkStrip.refresh()
                ic()?.setComposingText(result, 1)
            }
            transStatus = status
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

    private fun sinkConfirm() {
        if (seq.isNotEmpty()) finalizeComposing()
        val text = sinkBuf.toString()
        when (sink) {
            Sink.FAVORITE -> {
                val value=text.trim().replace("\u0001","")
                if(value.isEmpty()) {message("先输入要收藏的内容");return}
                if(value.codePointCount(0,value.length)>128) {message("最多收藏 128 个字符");return}
                val list=(listOf(value)+favEmoji().filter {it!=value}).take(200)
                prefs.edit().putString(Prefs.FAV_EMOJI,list.joinToString("\u0001")).apply()
                endSink(animate=true);emojiTab=2;openPanel(Panel.EMOJI);return
            }
            Sink.SEARCH -> clipQuery = text.trim()
            Sink.EDIT -> sinkClip?.let { ClipStore.update(it, text) }
            Sink.NEWCAT -> { ClipStore.addCategory(text); if (text.isNotBlank()) clipTab = text.trim().take(8) }
            Sink.TRANSLATE -> {
                // 完成：把译文确定下来（还没出来就用原文）
                Translator.cancel()
                main.removeCallbacks(transTask)
                val ic = ic()
                if (transResult.isEmpty() && text.isNotBlank()) ic?.setComposingText(text, 1)
                checkpoint(typing = false)
                ic?.finishComposingText()
                endSink(animate=true)
                render()
                return
            }
            Sink.NONE -> return
        }
        endSink(animate=true)
        openPanel(Panel.CLIP)
    }

    private fun sinkCancel() {
        val wasTranslate = sink == Sink.TRANSLATE
        val wasFavorite = sink == Sink.FAVORITE
        if (wasTranslate) ic()?.setComposingText("", 1)
        endSink(animate=true)
        if(wasFavorite) {emojiTab=2;openPanel(Panel.EMOJI)} else if (wasTranslate) render() else openPanel(Panel.CLIP)
    }

    // —————————— 面板 ——————————

    private fun openPanel(p: Panel) {
        if (panel == Panel.ADJUST && p != Panel.ADJUST) closePanel(render=false)
        if (p != Panel.CANDS && seq.isNotEmpty()) finalizeComposing()
        if (p != Panel.CLIP && p != Panel.TOKENS) { clipExpanded = null; clipPicking = null }
        if (panel == Panel.VOICE && p != Panel.VOICE) { stopListening(); cancelVoiceDraft() }
        panel = p
        assoc = emptyList()
        buildPanel(animate=true)
        // 调节高度时键盘还要看得见
        keyboard.visibility = if (p == Panel.ADJUST) View.VISIBLE else View.INVISIBLE
        panelHost.visibility = View.VISIBLE
        renderBar()
    }

    private fun closePanel(render: Boolean = true, animate: Boolean = true) {
        val hadPanel = panel != Panel.NONE
        if (panel == Panel.VOICE) { stopListening(); cancelVoiceDraft() }
        val wasAdjust = panel == Panel.ADJUST
        adjustOverlay?.let { (it.parent as? android.view.ViewGroup)?.removeView(it) }; adjustOverlay = null
        panel = Panel.NONE
        voiceBoard = null
        if (wasAdjust) {
            adj = null
            floating=prefs.getBoolean(Prefs.FLOAT,false)
            applyFloat(); applySkin(rebuildPanel = false)
        }
        selecting = false
        anchor = -1
        editView = null
        handView = null
        handCands = emptyList()
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
        typeChar(sym, paired = true)
        val list = (listOf(sym) + recentSyms().filter { it != sym }).take(28)
        prefs.edit().putString(Prefs.RECENT_SYM, list.joinToString("\u0001")).apply()
    }

    /** 符号分类：常用后面插一个“最近” */
    private fun symCats(): List<Pair<String, List<String>>> =
        listOf(Layouts.SYMBOLS[0], "最近" to recentSyms()) + Layouts.SYMBOLS.drop(1)

    private fun buildPanel(animate: Boolean=false) {
        if (root == null) return
        val previous = if (animate && panelHost.visibility == View.VISIBLE) NativeMotion.snapshot(panelHost) else null
        val clipScroll = (panelHost.getChildAt(0) as? ClipboardPanel)?.scrollPosition ?: 0
        adjustOverlay?.let { (it.parent as? android.view.ViewGroup)?.removeView(it) }; adjustOverlay = null
        panelHost.removeAllViews()
        editView = null
        if (panel != Panel.HAND) handView = null
        val p = prefs
        val v: View = when (panel) {
            Panel.NONE -> return
            Panel.EMOJI -> EmojiPanel(this, skin, recentEmoji(), favEmoji(), emojiTab, ::useEmoji, ::toggleFav,
                onTab = { emojiTab = it }, onCustom = { startSink(Sink.FAVORITE,"",null) }) { onKey(Key(Kind.DEL)) }
            Panel.CLIP -> ClipboardPanel(
                this, skin, if (clipTab in ClipStore.tabs()) clipTab else "全部", clipQuery, clipExpanded, clipPicking,
                onTab = { clipTab = it; clipQuery = ""; clipExpanded = null; buildPanel(animate=true) },
                onTabLong = { t -> confirm("cat:$t", "再长按一次删除分类“$t”（里面的内容不删）") { ClipStore.removeCategory(t); clipTab = "全部"; buildPanel() } },
                onNewCat = { startSink(Sink.NEWCAT, "", null) },
                onSearch = { startSink(Sink.SEARCH, clipQuery, null) },
                onClearQuery = { clipQuery = ""; buildPanel() },
                onClear = { confirm("clear", "再点一次垃圾桶，清空没置顶的记录") { ClipStore.clearUnpinned(); clipExpanded = null; buildPanel(); message("已清空没置顶的记录") } },
                onPaste = ::pasteClip,
                onExpand = { clipExpanded = it; clipPicking = null },
                onAction = ::clipAction,
            )
            Panel.TOKENS -> TokensPanel(this, skin, tokens, onInsert = {
                typeChar(it)
                if (tokensFromBar) closePanel()
            }, onCopy = {
                ignoreClipUntil = SystemClock.uptimeMillis() + 1500
                clipboard?.setPrimaryClip(ClipData.newPlainText("空叶键盘", it)); message("已复制")
            }, onClose = { if (tokensFromBar) closePanel() else openPanel(Panel.CLIP) })
            Panel.EDIT -> EditPanelView(this, skin, selecting) { editAction(it) }.also { editView = it }
            Panel.SKIN -> SkinPanel(
                this, skin, Themes.all(this), Prefs.theme(this).id, p.getInt(Prefs.MODE, 0),
                onMode = { p.edit().putInt(Prefs.MODE, it).apply() },
                onPick = { p.edit().putString(Prefs.THEME, it.id).apply() },
                glass=p.getBoolean(Prefs.GLASS,false),onGlass={p.edit().putBoolean(Prefs.GLASS,it).apply()},
            )
            Panel.MORE -> scroll(TileGridView(this, skin, Prefs.menu(this).map { Triple(it, Prefs.NAMES[it] ?: it, Prefs.ICONS[it] ?: R.drawable.ic_settings) }) { runFeature(it) })
            Panel.ARRANGE -> scroll(ArrangeView(this, skin, Prefs.tools(this), Prefs.menu(this)) { t, m -> Prefs.saveArrangement(this, t, m) })
            Panel.LAYOUT -> LayoutPicker(this, skin, Prefs.layout(this)) { id ->
                p.edit().putString(Prefs.LAYOUT, id).apply()
                closePanel()
                if (!zh) toggleLang()
            }
            Panel.ADJUST -> {
                val a = adj ?: newAdjustment().also { adj = it }
                AdjustOverlay(
                    this, skin, a, a.floating,
                    onLive = { applyFloat(); applySkin(rebuildPanel = false) },
                    onFloating = {
                        a.floating=!a.floating
                        floating=a.floating
                        applyFloat(); applySkin(rebuildPanel=false); buildPanel()
                        root?.post { adjustOverlay?.bringToFront(); positionAdjustment() }
                    },
                    onMove = { x,y -> placeFloat(x,y); a.floatX=col.translationX; a.floatY=col.translationY },
                    onReset = {
                        adj = Adjust(1f, 1f, 0, 0, 0, floatX = resources.displayMetrics.widthPixels * .07f, floatY = resources.displayMetrics.heightPixels * .45f)
                        floating = false
                        applyFloat(); applySkin()
                        root?.post { adjustOverlay?.bringToFront(); positionAdjustment() }
                    },
                    onDone = {
                        p.edit().putFloat(Prefs.HEIGHT_SCALE, a.scale).putFloat(Prefs.OPACITY, a.opacity)
                            .putBoolean(Prefs.SPLIT_LAYOUT, a.split).putBoolean(Prefs.FLOAT, a.floating)
                            .putFloat(Prefs.FLOAT_WIDTH, a.floatWidth).putFloat(Prefs.FLOAT_X, a.floatX).putFloat(Prefs.FLOAT_Y, a.floatY)
                            .putInt(Prefs.PAD_L, a.padL).putInt(Prefs.PAD_R, a.padR).putInt(Prefs.PAD_B, a.padB).apply()
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
            Panel.HAND -> HandwriteView(this, skin, onRecognize = { v -> recognize(v) }, onAction = { a ->
                when (a) {
                    "del" -> if (handView?.empty == false) handView?.clear() else onKey(Key(Kind.DEL))
                    "space" -> if (handCands.isNotEmpty()) onCandidate(0) else typeChar(" ")
                    "enter" -> if (handCands.isNotEmpty()) onCandidate(0) else onKey(Key(Kind.ENTER))
                }
            }, onChanged = {
                inkRevision++
                handCands = emptyList()
                assoc = emptyList()
                renderBar()
            }).also { v ->
                handView = v
                v.status = HandInk.status
                HandInk.prepare { main.post { handView?.status = HandInk.status } }
            }
            Panel.VOICE -> VoiceBoard(this, skin, p.getInt(Prefs.VOICE_LANG, 0), !micGranted(),
                onDialect = { i ->
                    p.edit().putInt(Prefs.VOICE_LANG, i).apply()
                    sichuanFallback = false
                    if (listening) { stopListening(); main.postDelayed({ toggleListening() }, 200) }
                },
                onMic = { toggleListening() },
                onCancel = { stopListening(); voiceText.setLength(0); voicePartial = ""; closePanel() },
                onOk = { confirmVoice() },
                onPermission = { openSettings("grant_mic") },
            ).also { voiceBoard = it; it.update(voiceText.toString() + voicePartial, voiceStatus, listening) }
            Panel.CANDS -> ExpandedCandidates(this, skin, cands.take(120).map { it.w }, candidateFilter,
                onMode = { candidateFilter=it; buildPanel() },
                onPick = { i -> cands.getOrNull(i)?.let { pick(it) } },
                onDelete = { onKey(Key(Kind.DEL)); if (seq.isEmpty()) closePanel() }, onBack = { closePanel() })
            Panel.HAND_CANDS -> ExpandedCandidates(this, skin, handCands, candidateFilter,
                onMode = { candidateFilter=it; buildPanel() }, onPick = { onCandidate(it) },
                onDelete = { handCands=emptyList(); panel=Panel.HAND; buildPanel(); renderBar() },
                onBack = { panel=Panel.HAND; buildPanel(); renderBar() })
        }
        if (panel == Panel.ADJUST) {
            adjustOverlay=v
            root?.addView(v, FrameLayout.LayoutParams(col.width, col.height))
            positionAdjustment()
        } else panelHost.addView(v, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        if (v is ClipboardPanel) v.restoreScroll(clipScroll)
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
        if (l.width != width || l.height != col.height) { l.width=width; l.height=col.height; view.layoutParams=l }
        view.x=col.x+col.paddingLeft; view.y=col.y
    }

    private fun recognize(view: HandwriteView) {
        val revision = inkRevision
        fun deliver(list: List<String>, ocr: Boolean) {
            if (panel != Panel.HAND || handView !== view || inkRevision != revision) return
            handCands = HandwritingCandidates.expand(list, Lexicon::assoc, similar = ocr)
            if (list.isEmpty()) message("没认出来，写大一点再试") else renderBar()
        }
        val pre = runCatching { ic()?.getTextBeforeCursor(20, 0)?.toString() }.getOrNull().orEmpty()
        // 优先用数字墨水（按笔画认字）；模型还没好就用 OCR 兜底，同时去准备模型
        if (HandInk.recognize(view.inkStrokes(), view.padWidth(), view.padHeight(), pre) { deliver(it, ocr = false) }) return
        HandInk.prepare { main.post { handView?.status = HandInk.status } }
        view.bitmap()?.let { bmp -> Ink.recognize(bmp) { deliver(it, ocr = true) } }
    }

    // —————————— 编辑面板 ——————————

    private fun editAction(id: String) {
        buzz()
        val ic = ic() ?: return
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
                if (t.isNullOrEmpty()) message("先选中文字：点中间的“选择”再移动，或点“全选”")
                else {
                    clipboard?.setPrimaryClip(ClipData.newPlainText("空叶键盘", t))
                    if (id == "cut") { checkpoint(false); ic.commitText("", 1) }
                    message(if (id == "cut") "已剪切" else "已复制")
                }
                endSelect()
            }
            "paste" -> {
                val t = runCatching { clipboard?.primaryClip?.getItemAt(0)?.coerceToText(this)?.toString() }.getOrNull()
                if (t.isNullOrEmpty()) message("剪贴板是空的") else { checkpoint(false); commitOut(t) }
            }
            "del" -> { checkpoint(true,"delete"); deleteCommitted() }
            "enter" -> { checkpoint(false); ic.commitText("\n", 1) }
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
        private val PAIRED_SYMBOLS = setOf("（）", "【】", "“”", "‘’", "「」", "『』", "《》", "〈〉", "()", "[]", "{}")
    }
}
