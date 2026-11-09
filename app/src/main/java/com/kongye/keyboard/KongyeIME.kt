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
    private enum class Panel { NONE, EMOJI, CLIP, EDIT, SKIN, CANDS, MORE, ADJUST, ARRANGE, LAYOUT, SYMBOL, HAND, HAND_CANDS, VOICE, TRANSLATE, TOKENS }
    /** 打字的去向：编辑框，或者剪贴板搜索、编辑、新建分类、翻译的输入条 */
    private enum class Sink { NONE, SEARCH, EDIT, NEWCAT, TRANSLATE }

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
    private var emojiTab = 0
    private var symCat = 0
    private var symLocked = false
    private var handCands: List<String> = emptyList()
    private var inkRevision = 0
    private var adjustScale = 1f
    private var confirmAt = 0L
    private var confirmWhat = ""

    private var transSource = ""
    private var transResult = ""
    private var transStatus = ""

    private var speech: SpeechRecognizer? = null
    private var listening = false
    private var voiceStatus = "点麦克风开始说话"
    private var voicePartial = ""

    private var clipboard: ClipboardManager? = null
    private var ignoreClipUntil = 0L
    private var lastImageUri = ""
    private val clipListener = ClipboardManager.OnPrimaryClipChangedListener { captureClip() }

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs.of(this)
        prefs.registerOnSharedPreferenceChangeListener(this)
        zh = prefs.getBoolean(Prefs.ZH, true)
        floating = prefs.getBoolean(Prefs.FLOAT, false)
        ClipStore.load(this)
        Thread {
            Lexicon.load(applicationContext)
            main.post { if (seq.isNotEmpty()) refreshComposing() }
        }.start()
        clipboard = getSystemService(ClipboardManager::class.java)
        clipboard?.addPrimaryClipChangedListener(clipListener)
    }

    override fun onDestroy() {
        Translator.cancel()
        clipboard?.removePrimaryClipChangedListener(clipListener)
        prefs.unregisterOnSharedPreferenceChangeListener(this)
        speech?.destroy()
        Lexicon.save()
        super.onDestroy()
    }

    override fun onEvaluateFullscreenMode() = false

    /** 悬浮时输入法视图占满整屏（透明），只有键盘那块能点 */
    private inner class Root(ctx: Context) : FrameLayout(ctx) {
        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            if (floating) super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(resources.displayMetrics.heightPixels, MeasureSpec.EXACTLY))
            else super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        }
    }

    /** 悬浮键盘顶上的拖动条：拖动移动键盘，右边的 × 退出悬浮 */
    private inner class FloatBar(ctx: Context) : View(ctx) {
        private val d = resources.displayMetrics.density
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
        bottomStrip = BottomStrip(this, onKeyboard = {
            getSystemService(InputMethodManager::class.java)?.showInputMethodPicker()
        }, onClip = { if (panel == Panel.CLIP) closePanel() else openPanel(Panel.CLIP) })
        val area = FrameLayout(this)
        area.addView(keyboard, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        area.addView(panelHost, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        col.addView(floatBar, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        col.addView(sinkStrip, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        col.addView(bar, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        col.addView(area, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        col.addView(bottomStrip, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        r.addView(col, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT, Gravity.BOTTOM))
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
        if (floating && r.height > 0 && ::col.isInitialized) {
            outInsets.contentTopInsets = r.height
            outInsets.visibleTopInsets = r.height
            outInsets.touchableInsets = Insets.TOUCHABLE_INSETS_REGION
            val loc = IntArray(2)
            col.getLocationInWindow(loc)
            outInsets.touchableRegion.set(loc[0], loc[1], loc[0] + col.width, loc[1] + col.height)
        }
    }

    override fun onStartInputView(info: EditorInfo, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        resetComposing()
        assoc = emptyList()
        selecting = false
        if (sink != Sink.NONE) endSink()
        if (!restarting) { undoStack.clear(); redoStack.clear() }
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
        applySkin()
        updateAutoCaps()
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
        if (candidatesStart == -1) {
            resetComposing(); renderBar()
        } else if (newSelStart < candidatesStart || newSelStart > candidatesEnd || newSelEnd != newSelStart) {
            resetComposing()
            currentInputConnection?.finishComposingText()
            renderBar()
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (root != null) applySkin()
    }

    override fun onSharedPreferenceChanged(p: SharedPreferences?, key: String?) {
        if (root == null || key in setOf(Prefs.ZH, Prefs.RECENT_EMOJI, Prefs.COUNT_TODAY, Prefs.COUNT_DATE, Prefs.FLOAT_X, Prefs.FLOAT_Y)) return
        if (key == Prefs.FLOAT) { floating = prefs.getBoolean(Prefs.FLOAT, false); applyFloat(); return }
        applySkin()
    }

    // —————————— 外观 ——————————

    private fun flag(key: String) = prefs.getBoolean(key, true)

    private fun skinBg(): GradientDrawable =
        if (skin.bg2 != 0) GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(skin.kbBg, skin.bg2))
        else GradientDrawable().apply { setColor(skin.kbBg) }

    private fun applySkin() {
        skin = Prefs.skin(this)
        val r = root ?: return
        bar.skin = skin
        sinkStrip.skin = skin
        bottomStrip.skin = skin
        floatBar.invalidate()
        keyboard.heightScale = if (panel == Panel.ADJUST) adjustScale else Prefs.heightScale(this)
        keyboard.swipeEnabled = flag(Prefs.SWIPE)
        keyboard.enterIcon = enterIcon(currentInputEditorInfo)
        applyFloatBackground(r)
        render()
        if (panel != Panel.NONE) buildPanel()
    }

    private fun applyFloatBackground(r: Root) {
        if (floating) {
            r.background = null
            col.background = skinBg().apply { cornerRadius = 18 * resources.displayMetrics.density }
            col.elevation = 14 * resources.displayMetrics.density
            col.clipToOutline = true
        } else {
            r.background = skinBg()
            col.background = null
            col.elevation = 0f
        }
    }

    private fun applyFloat() {
        val r = root ?: return
        val dm = resources.displayMetrics
        val lpCol = col.layoutParams as FrameLayout.LayoutParams
        if (floating) {
            lpCol.width = (dm.widthPixels * 0.86f).toInt()
            lpCol.gravity = Gravity.TOP or Gravity.START
            floatBar.visibility = View.VISIBLE
            bottomStrip.visibility = View.GONE
            col.layoutParams = lpCol
            placeFloat(prefs.getFloat(Prefs.FLOAT_X, dm.widthPixels * 0.07f), prefs.getFloat(Prefs.FLOAT_Y, dm.heightPixels * 0.45f))
        } else {
            lpCol.width = MATCH_PARENT
            lpCol.gravity = Gravity.BOTTOM
            floatBar.visibility = View.GONE
            bottomStrip.visibility = View.VISIBLE
            col.translationX = 0f
            col.translationY = 0f
            col.layoutParams = lpCol
        }
        applyFloatBackground(r)
        r.requestLayout()
    }

    private fun placeFloat(x: Float, y: Float) {
        val dm = resources.displayMetrics
        val w = if (col.width > 0) col.width else (dm.widthPixels * 0.86f).toInt()
        val h = if (col.height > 0) col.height else (dm.heightPixels * 0.4f).toInt()
        col.translationX = x.coerceIn(0f, (dm.widthPixels - w).toFloat().coerceAtLeast(0f))
        col.translationY = y.coerceIn(0f, (dm.heightPixels - h).toFloat().coerceAtLeast(0f))
        root?.requestLayout()
    }

    private fun setFloating(on: Boolean) {
        floating = on
        prefs.edit().putBoolean(Prefs.FLOAT, on).apply()
        if (panel != Panel.NONE) closePanel(render = false)
        applyFloat()
        render()
    }

    private fun rowsForLayer(): List<Row> = when {
        layer == Layer.NUM -> Layouts.numpad()
        !zh -> Layouts.letters(false)
        else -> when (Prefs.layout(this)) {
            "14" -> Layouts.fourteen()
            "9" -> Layouts.nine()
            else -> Layouts.letters(true)
        }
    }

    private fun sideForLayer(): List<String>? = when {
        layer == Layer.NUM -> Layouts.NUM_SIDE
        zh && Prefs.layout(this) == "9" -> Layouts.NINE_SIDE
        else -> null
    }

    private fun render() {
        if (root == null) return
        keyboard.zh = zh
        keyboard.shiftState = shift
        keyboard.splitMode = splitMode()
        keyboard.setup(skin, rowsForLayer(), sideForLayer())
        renderBar()
    }

    private fun splitMode() = zh && layer == Layer.LETTERS && seq.isNotEmpty()

    /** 打开面板时工具栏里高亮哪个工具 */
    private fun activeTool(): String? {
        val id = when (panel) {
            Panel.EMOJI -> "emoji"; Panel.CLIP, Panel.TOKENS -> "clip"; Panel.EDIT -> "edit"; Panel.SKIN -> "skin"
            Panel.HAND, Panel.HAND_CANDS -> "handwrite"; Panel.VOICE -> "voice"; Panel.TRANSLATE -> "translate"
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
        when {
            seq.isNotEmpty() -> bar.showCandidates(candItems(), panel == Panel.CANDS)
            panel in listOf(Panel.HAND, Panel.HAND_CANDS) && handCands.isNotEmpty() -> bar.showCandidates(handCands.map { CandItem(it, Lexicon.pinyinOf(it)) }, panel == Panel.HAND_CANDS)
            assoc.isNotEmpty() && (panel == Panel.NONE || panel == Panel.HAND) -> bar.showAssoc(assoc)
            else -> bar.showTools(listOf("more") + Prefs.tools(this), activeTool())
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

    private fun commitOut(text: String) {
        if (sink != Sink.NONE) {
            sinkBuf.append(text)
            sinkStrip.value = sinkBuf.toString()
            sinkStrip.composing = ""
            sinkStrip.refresh()
            return
        }
        checkpoint(typing = true)
        ic()?.commitText(text, 1)
        Prefs.addTyped(this, text.count { it.isLetterOrDigit() })
    }

    private fun composeOut(text: String) {
        if (sink != Sink.NONE) {
            sinkStrip.composing = text
            sinkStrip.refresh()
        } else ic()?.setComposingText(text, 1)
    }

    // —————————— 撤销与重做：和空叶文档一样，存整段文字和光标的快照；连续输入停顿不到 1 秒合成一步 ——————————

    private data class Snap(val text: String, val sel: Int)

    private val undoStack = ArrayDeque<Snap>()
    private val redoStack = ArrayDeque<Snap>()
    private var lastTypingAt = 0L

    private fun snap(): Snap? {
        val ic = ic() ?: return null
        val ext = ic.getExtractedText(ExtractedTextRequest().apply { hintMaxChars = 200000 }, 0)
        if (ext?.text != null && ext.startOffset == 0) return Snap(ext.text.toString(), ext.selectionStart.coerceAtLeast(0))
        val b = ic.getTextBeforeCursor(100000, 0)?.toString() ?: return null
        val a = ic.getTextAfterCursor(100000, 0)?.toString().orEmpty()
        return Snap(b + a, b.length)
    }

    private fun checkpoint(typing: Boolean) {
        if (sink != Sink.NONE) return
        val now = SystemClock.uptimeMillis()
        if (typing && now - lastTypingAt <= 1000) { lastTypingAt = now; return }
        snap()?.let { s ->
            if (undoStack.lastOrNull() != s) undoStack.addLast(s)
            while (undoStack.size > 100) undoStack.removeFirst()
        }
        redoStack.clear()
        lastTypingAt = if (typing) now else 0L
    }

    private fun restore(s: Snap) {
        val ic = ic() ?: return
        val cur = snap() ?: return
        ic.beginBatchEdit()
        ic.finishComposingText()
        ic.setSelection(0, cur.text.length)
        ic.commitText(s.text, 1)
        val p = s.sel.coerceIn(0, s.text.length)
        ic.setSelection(p, p)
        ic.endBatchEdit()
        lastTypingAt = 0L
    }

    private fun undo() {
        if (sink != Sink.NONE) return
        if (seq.isNotEmpty() || picks.isNotEmpty()) { resetComposing(); composeOut(""); renderBar(); return }
        val s = undoStack.removeLastOrNull()
        if (s == null) { if (ic()?.performContextMenuAction(android.R.id.undo) != true) message("没有可以撤销的输入"); return }
        snap()?.let { redoStack.addLast(it) }
        restore(s)
    }

    private fun redo() {
        val s = redoStack.removeLastOrNull()
        if (s == null) { if (ic()?.performContextMenuAction(android.R.id.redo) != true) message("没有可以重做的输入"); return }
        snap()?.let { undoStack.addLast(it) }
        restore(s)
    }

    // —————————— 拼音组字 ——————————

    private fun pendingText() = picks.joinToString("") { it.w }

    private fun resetComposing() {
        seq.clear()
        picks.clear()
        cands = emptyList()
        if (::sinkStrip.isInitialized) sinkStrip.composing = ""
    }

    private fun refreshComposing() {
        cands = Lexicon.candidates(seq.toList(), flag(Prefs.ABBR))
        composeOut(pendingText() + Lexicon.display(seq.toList(), cands.firstOrNull()))
        renderBar()
        if (panel == Panel.CANDS) buildPanel()
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
        val syl = c.e?.syl?.toList() ?: Lexicon.decode(taken.filter { it != "'" })
        picks.add(Pick(c.w, syl, taken, c.e == null))
        if (learnOn()) {
            if (c.sentence) Lexicon.learnPhrase(c.w, syl) else if (c.e != null) Lexicon.recordPick(c.e)
        }
        if (letterCount() == 0) { seq.clear(); finishPicks() } else refreshComposing()
    }

    private fun finishPicks() {
        val text = pendingText()
        commitOut(text)
        if (learnOn() && picks.size >= 2 && picks.none { it.isRaw }) Lexicon.learnPhrase(text, picks.flatMap { it.syl })
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
            Kind.SEP -> if (seq.isNotEmpty()) { if (seq.last() != "'") seq.add("'"); refreshComposing() } else typeChar("1")
            Kind.RESET -> if (seq.isNotEmpty() || picks.isNotEmpty()) { resetComposing(); composeOut(""); if (sink == Sink.NONE) ic()?.finishComposingText(); renderBar() }
            Kind.NUM -> { layer = Layer.NUM; render() }
            Kind.SYM -> openPanel(Panel.SYMBOL)
            Kind.ABC -> { layer = Layer.LETTERS; render(); updateAutoCaps() }
            Kind.LANG -> toggleLang()
        }
    }

    override fun onLongKey(key: Key) {
        when {
            key.longEmoji -> openPanel(Panel.EMOJI)
            key.kind == Kind.SHIFT && !splitMode() -> { shift = 2; keyboard.shiftState = 2; keyboard.invalidate() }
        }
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
            sinkBuf.setLength(0); sinkStrip.value = ""; sinkStrip.refresh()
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
        val ch = k.letters.take(1)
        if (!zh || layer != Layer.LETTERS || shift > 0) {
            if (seq.isNotEmpty()) finalizeComposing()
            assoc = emptyList()
            commitOut(if (shift > 0) ch.uppercase() else ch)
            if (shift == 1) { shift = 0; keyboard.shiftState = 0; keyboard.invalidate() }
            renderBar()
            return
        }
        if (seq.size >= 40) return
        seq.add(k.letters)
        assoc = emptyList()
        refreshComposing()
    }

    private fun typeChar(s: String, paired: Boolean = false) {
        if (seq.isNotEmpty()) finalizeComposing()
        assoc = emptyList()
        commitOut(s)
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
        if (sink != Sink.NONE) {
            if (sinkBuf.isNotEmpty()) sinkBuf.setLength(Character.offsetByCodePoints(sinkBuf, sinkBuf.length, -1))
            sinkStrip.value = sinkBuf.toString()
            sinkStrip.refresh()
        } else {
            checkpoint(typing = true)
            sendDownUpKeyEvents(KeyEvent.KEYCODE_DEL)
        }
        renderBar()
        updateAutoCaps()
    }

    private fun tapShift() {
        if (splitMode()) {
            // 中文组字时大写键是“分词”
            if (seq.last() != "'") seq.add("'")
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
        val w = assoc.getOrNull(index) ?: return
        commitOut(w)
        assoc = Lexicon.assoc(w)
        renderBar()
    }

    override fun onDismissAssoc() {
        assoc = emptyList()
        renderBar()
    }

    override fun onTool(id: String) {
        // 再点一下正打开的那个工具：收起面板回到键盘
        if (panel != Panel.NONE && id == activeTool()) { closePanel(); return }
        runFeature(id)
    }

    private fun runFeature(id: String) {
        when (id) {
            "more" -> openPanel(Panel.MORE)
            "settings" -> openSettings()
            "adjust" -> { adjustScale = Prefs.heightScale(this); openPanel(Panel.ADJUST) }
            "arrange" -> openPanel(Panel.ARRANGE)
            "layout" -> openPanel(Panel.LAYOUT)
            "emoji" -> openPanel(Panel.EMOJI)
            "clip" -> openPanel(Panel.CLIP)
            "edit" -> openPanel(Panel.EDIT)
            "skin" -> openPanel(Panel.SKIN)
            "undo" -> undo()
            "redo" -> redo()
            "voice" -> { voicePartial = ""; voiceStatus = "点麦克风开始说话"; openPanel(Panel.VOICE) }
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

    private fun toggleListening() {
        if (listening) { stopListening(); return }
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            voiceStatus = "这台手机没有可用的语音识别服务，可以改用系统里的语音输入法"
            buildPanel(); return
        }
        val sr = speech ?: SpeechRecognizer.createSpeechRecognizer(this).also { speech = it }
        sr.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) { voiceStatus = "请说话…"; if (panel == Panel.VOICE) buildPanel() }
            override fun onBeginningOfSpeech() = Unit
            override fun onRmsChanged(rmsdB: Float) = Unit
            override fun onBufferReceived(buffer: ByteArray?) = Unit
            override fun onEndOfSpeech() { voiceStatus = "正在识别…"; if (panel == Panel.VOICE) buildPanel() }
            override fun onError(error: Int) {
                listening = false
                voiceStatus = when (error) {
                    SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "没听清，再点麦克风试一次"
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "需要麦克风权限"
                    SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "语音识别需要联网，网络不通"
                    else -> "语音识别出错（$error），可以改用系统里的语音输入法"
                }
                if (panel == Panel.VOICE) buildPanel()
            }
            override fun onResults(results: Bundle?) {
                listening = false
                val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
                if (text.isNotEmpty()) commitOut(text)
                voicePartial = ""
                voiceStatus = if (text.isEmpty()) "没听清，再点麦克风试一次" else "已输入：$text"
                if (panel == Panel.VOICE) buildPanel()
            }
            override fun onPartialResults(partialResults: Bundle?) {
                voicePartial = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
                if (panel == Panel.VOICE) buildPanel()
            }
            override fun onEvent(eventType: Int, params: Bundle?) = Unit
        })
        sr.startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, if (zh) "zh-CN" else "en-US")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        })
        listening = true
        voiceStatus = "请说话…"
        buildPanel()
    }

    private fun stopListening() {
        if (listening) runCatching { speech?.stopListening() }
        listening = false
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
        }
        if (panel == Panel.CLIP) buildPanel()
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
            id == "split" -> { tokens = Lexicon.segment(c.text); openPanel(Panel.TOKENS); return }
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
        if (seq.isNotEmpty()) resetComposing()
        closePanel(render = false)
        sink = mode
        sinkClip = clip
        sinkBuf.setLength(0)
        sinkBuf.append(initial)
        sinkStrip.multiline = mode == Sink.EDIT || mode == Sink.TRANSLATE
        sinkStrip.icon = when (mode) { Sink.EDIT -> R.drawable.ic_edit; Sink.TRANSLATE -> R.drawable.ic_translate; Sink.NEWCAT -> R.drawable.ic_clip; else -> R.drawable.ic_search }
        sinkStrip.hint = when (mode) { Sink.EDIT -> "修改内容"; Sink.TRANSLATE -> "输入要翻译的中文或外文"; Sink.NEWCAT -> "新分类的名字"; else -> "搜索剪贴板" }
        sinkStrip.value = initial
        sinkStrip.composing = ""
        sinkStrip.visibility = View.VISIBLE
        sinkStrip.refresh()
        layer = Layer.LETTERS
        assoc = emptyList()
        render()
    }

    private fun endSink() {
        resetComposing()
        sink = Sink.NONE
        sinkClip = null
        if (::sinkStrip.isInitialized) sinkStrip.visibility = View.GONE
    }

    private fun sinkConfirm() {
        if (seq.isNotEmpty()) finalizeComposing()
        val text = sinkBuf.toString()
        when (sink) {
            Sink.SEARCH -> clipQuery = text.trim()
            Sink.EDIT -> sinkClip?.let { ClipStore.update(it, text) }
            Sink.NEWCAT -> { ClipStore.addCategory(text); if (text.isNotBlank()) clipTab = text.trim().take(8) }
            Sink.TRANSLATE -> {
                endSink()
                if (text.isBlank()) { renderBar(); return }
                transSource = text
                openPanel(Panel.TRANSLATE)
                runTranslation()
                return
            }
            Sink.NONE -> return
        }
        endSink()
        openPanel(Panel.CLIP)
    }

    private fun sinkCancel() {
        val wasTranslate = sink == Sink.TRANSLATE
        endSink()
        if (wasTranslate) render() else openPanel(Panel.CLIP)
    }

    private fun runTranslation(retryDownload: Boolean = false) {
        if (transSource.isBlank()) return
        transResult = ""
        Translator.run(applicationContext, transSource, retryDownload) { ok, result, status ->
            if (ok) transResult = result
            transStatus = status
            if (panel == Panel.TRANSLATE) buildPanel()
        }
    }

    // —————————— 面板 ——————————

    private fun openPanel(p: Panel) {
        if (p != Panel.CANDS && seq.isNotEmpty()) finalizeComposing()
        if (p != Panel.CLIP && p != Panel.TOKENS) { clipExpanded = null; clipPicking = null }
        if (p != Panel.SYMBOL) symLocked = false
        if (panel == Panel.VOICE && p != Panel.VOICE) stopListening()
        panel = p
        assoc = emptyList()
        buildPanel()
        // 调节高度时键盘还要看得见
        keyboard.visibility = if (p == Panel.ADJUST) View.VISIBLE else View.INVISIBLE
        panelHost.visibility = View.VISIBLE
        renderBar()
    }

    private fun closePanel(render: Boolean = true) {
        if (panel == Panel.VOICE) stopListening()
        if (panel == Panel.ADJUST) keyboard.heightScale = Prefs.heightScale(this)
        panel = Panel.NONE
        selecting = false
        anchor = -1
        editView = null
        handView = null
        handCands = emptyList()
        if (::panelHost.isInitialized) {
            panelHost.removeAllViews()
            panelHost.visibility = View.GONE
            keyboard.visibility = View.VISIBLE
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
        if (emojiTab == 2) buildPanel()
    }

    private fun scroll(v: View) = ScrollView(this).apply { isVerticalScrollBarEnabled = false; addView(v) }

    private fun buildPanel() {
        if (root == null) return
        panelHost.removeAllViews()
        editView = null
        if (panel != Panel.HAND) handView = null
        val p = prefs
        val v: View = when (panel) {
            Panel.NONE -> return
            Panel.EMOJI -> EmojiPanel(this, skin, recentEmoji(), favEmoji(), emojiTab, ::useEmoji, ::toggleFav,
                onTab = { emojiTab = it; buildPanel() }) { onKey(Key(Kind.DEL)) }
            Panel.CLIP -> ClipboardPanel(
                this, skin, if (clipTab in ClipStore.tabs()) clipTab else "全部", clipQuery, clipExpanded, clipPicking,
                onTab = { clipTab = it; clipExpanded = null; buildPanel() },
                onTabLong = { t -> confirm("cat:$t", "再长按一次删除分类“$t”（里面的内容不删）") { ClipStore.removeCategory(t); clipTab = "全部"; buildPanel() } },
                onNewCat = { startSink(Sink.NEWCAT, "", null) },
                onSearch = { startSink(Sink.SEARCH, clipQuery, null) },
                onClearQuery = { clipQuery = ""; buildPanel() },
                onClear = { confirm("clear", "再点一次垃圾桶，清空没置顶的记录") { ClipStore.clearUnpinned(); clipExpanded = null; buildPanel(); message("已清空没置顶的记录") } },
                onPaste = ::pasteClip,
                onExpand = { clipExpanded = it; clipPicking = null; buildPanel() },
                onAction = ::clipAction,
            )
            Panel.TOKENS -> TokensPanel(this, skin, tokens, onInsert = { typeChar(it) }, onCopy = {
                ignoreClipUntil = SystemClock.uptimeMillis() + 1500
                clipboard?.setPrimaryClip(ClipData.newPlainText("空叶键盘", it)); message("已复制")
            }, onClose = { openPanel(Panel.CLIP) })
            Panel.EDIT -> EditPanelView(this, skin, selecting) { editAction(it) }.also { editView = it }
            Panel.SKIN -> SkinPanel(
                this, skin, Themes.all(this), Prefs.theme(this).id, p.getInt(Prefs.MODE, 0),
                onMode = { p.edit().putInt(Prefs.MODE, it).apply() },
                onPick = { p.edit().putString(Prefs.THEME, it.id).apply() },
            )
            Panel.MORE -> scroll(TileGridView(this, skin, Prefs.menu(this).map { Triple(it, Prefs.NAMES[it] ?: it, Prefs.ICONS[it] ?: R.drawable.ic_settings) }) { runFeature(it) })
            Panel.ARRANGE -> scroll(ArrangeView(this, skin, Prefs.tools(this), Prefs.menu(this)) { t, m -> Prefs.saveArrangement(this, t, m) })
            Panel.LAYOUT -> LayoutPicker(this, skin, Prefs.layout(this)) { id ->
                p.edit().putString(Prefs.LAYOUT, id).apply()
                closePanel()
                if (!zh) toggleLang()
            }
            Panel.ADJUST -> ResizeOverlay(
                this, skin, adjustScale, p.getInt(Prefs.VIB_STRENGTH, 2), p.getBoolean(Prefs.SOUND, false),
                onScale = { s -> adjustScale = s; keyboard.heightScale = s },
                onVib = { lv -> p.edit().putInt(Prefs.VIB_STRENGTH, lv).apply(); buzz() },
                onSound = { on -> p.edit().putBoolean(Prefs.SOUND, on).apply() },
                onDone = { p.edit().putFloat(Prefs.HEIGHT_SCALE, adjustScale).apply(); closePanel() },
            )
            Panel.SYMBOL -> SymbolPanel(this, skin, symCat, symLocked,
                onCat = { symCat = it; buildPanel() },
                onSym = { s -> typeChar(s, paired = true); if (!symLocked) closePanel() },
                onLock = { symLocked = true; buildPanel() },
                onDel = { onKey(Key(Kind.DEL)) },
                onBack = { symLocked = false; closePanel() })
            Panel.HAND -> HandwriteView(this, skin, onRecognize = { bmp -> recognize(bmp) }, onAction = { a ->
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
            }).also { handView = it }
            Panel.VOICE -> VoicePanel(this, skin, voiceStatus, listening, voicePartial, !micGranted(),
                onMic = { toggleListening() }, onPermission = { openSettings("grant_mic") }, onSwitch = { switchToVoiceIme() })
            Panel.TRANSLATE -> TranslatePanel(this, skin, transSource, transResult, transStatus,
                onCommit = { if (transResult.isNotEmpty()) { typeChar(transResult); closePanel() } },
                onCopy = { if (transResult.isNotEmpty()) { clipboard?.setPrimaryClip(ClipData.newPlainText("译文", transResult)); message("已复制译文") } },
                onAgain = { Translator.cancel(); startSink(Sink.TRANSLATE, "", null) },
                onRetry = { runTranslation(retryDownload = true) })
            Panel.CANDS -> scroll(CandFlowView(this, skin, cands.take(120).map { it.w }) { i -> cands.getOrNull(i)?.let { pick(it) } })
            Panel.HAND_CANDS -> scroll(CandFlowView(this, skin, handCands) { onCandidate(it) })
        }
        panelHost.addView(v, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
    }

    private fun recognize(bmp: Bitmap) {
        val revision = inkRevision
        val view = handView
        Ink.recognize(bmp) { list ->
            if (panel != Panel.HAND || handView !== view || inkRevision != revision) return@recognize
            handCands = HandwritingCandidates.expand(list, Lexicon::assoc)
            if (list.isEmpty()) message("没认出来，写大一点再试") else renderBar()
        }
    }

    // —————————— 编辑面板 ——————————

    private fun editAction(id: String) {
        buzz()
        val ic = ic() ?: return
        when (id) {
            "left" -> moveCursor(-1)
            "right" -> moveCursor(1)
            "up" -> { endSelect(); sendDownUpKeyEvents(KeyEvent.KEYCODE_DPAD_UP) }
            "down" -> { endSelect(); sendDownUpKeyEvents(KeyEvent.KEYCODE_DPAD_DOWN) }
            "home" -> jump(-1)
            "end" -> jump(1)
            "select" -> {
                selecting = !selecting
                anchor = -1
                editView?.selecting = selecting
                editView?.invalidate()
            }
            "undo" -> undo()
            "redo" -> redo()
            "all" -> {
                val ext = ic.getExtractedText(ExtractedTextRequest().apply { hintMaxChars = 200000 }, 0)
                if (ext?.text != null) ic.setSelection(ext.startOffset, ext.startOffset + ext.text.length)
                else ic.performContextMenuAction(android.R.id.selectAll)
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
            "del" -> { checkpoint(true); sendDownUpKeyEvents(KeyEvent.KEYCODE_DEL) }
            "enter" -> { checkpoint(false); ic.commitText("\n", 1) }
        }
    }

    private fun endSelect() {
        if (!selecting) return
        selecting = false
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
        private val PAIRED_SYMBOLS = setOf("（）", "【】", "“”", "‘’", "「」", "『』", "《》", "〈〉", "()", "[]", "{}")
    }
}
