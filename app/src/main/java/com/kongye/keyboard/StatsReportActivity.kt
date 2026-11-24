package com.kongye.keyboard

import android.animation.ValueAnimator
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import java.io.File
import java.util.concurrent.Executors

/**
 * Printer status indicator LED modeled after code_artifact (4).html (.ky-led-dot.is-printing):
 * fast 0.24s breathing/pulsing green LED with soft halo while printing, turning matte gray when finished.
 */
internal class PrinterLedView(context: Context, private val dark: Boolean) : View(context) {
    private val d = resources.displayMetrics.density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var animator: ValueAnimator? = null
    private var pulse = 0.6f

    var printing: Boolean = false
        private set

    val activeColor: Int = if (dark) Color.rgb(74, 222, 128) else Color.rgb(34, 197, 94)
    val idleColor: Int = if (dark) Color.rgb(108, 112, 122) else Color.rgb(172, 176, 186)

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    fun setPrinting(active: Boolean) {
        if (printing == active && (active == (animator?.isRunning == true) || !ValueAnimator.areAnimatorsEnabled())) {
            invalidate()
            return
        }
        printing = active
        animator?.cancel()
        animator = null
        if (active && ValueAnimator.areAnimatorsEnabled()) {
            animator = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 120L // 120ms up + 120ms down = 0.24s cycle matching HTML
                repeatMode = ValueAnimator.REVERSE
                repeatCount = ValueAnimator.INFINITE
                interpolator = AccelerateDecelerateInterpolator()
                addUpdateListener {
                    pulse = it.animatedValue as Float
                    invalidate()
                }
                start()
            }
        } else {
            pulse = 1f
            invalidate()
        }
    }

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        if (cx <= 0f || cy <= 0f) return
        val baseRadius = 3.5f * d
        if (printing) {
            val scale = 0.95f + 0.13f * pulse
            val opacity = 0.45f + 0.55f * pulse
            val glowRadius = (4.8f + 4.2f * pulse) * d
            val r = Color.red(activeColor)
            val g = Color.green(activeColor)
            val b = Color.blue(activeColor)
            paint.shader = RadialGradient(
                cx, cy, glowRadius,
                intArrayOf(
                    Color.argb((125 * pulse + 35).toInt().coerceIn(0, 255), r, g, b),
                    Color.argb((48 * pulse + 12).toInt().coerceIn(0, 255), r, g, b),
                    Color.TRANSPARENT,
                ),
                floatArrayOf(0f, 0.52f, 1f),
                Shader.TileMode.CLAMP,
            )
            canvas.drawCircle(cx, cy, glowRadius, paint)
            paint.shader = null
            paint.color = Color.argb((255 * opacity).toInt().coerceIn(0, 255), r, g, b)
            canvas.drawCircle(cx, cy, baseRadius * scale, paint)
            if (pulse > 0.55f) {
                paint.color = Color.argb(((pulse - 0.55f) / 0.45f * 95f).toInt().coerceIn(0, 255), 220, 252, 231)
                canvas.drawCircle(cx, cy, baseRadius * 0.42f * scale, paint)
            }
        } else {
            paint.shader = null
            paint.color = idleColor
            canvas.drawCircle(cx, cy, baseRadius, paint)
        }
    }

    override fun onDetachedFromWindow() {
        animator?.cancel()
        animator = null
        super.onDetachedFromWindow()
    }

    fun release() {
        animator?.cancel()
        animator = null
    }
}

/** Generate a local receipt before exposing export actions. Never auto-opens the share sheet. */
class StatsReportActivity : ComponentActivity() {
    private val worker = Executors.newSingleThreadExecutor()
    private var reportFile: File? = null
    private lateinit var receipt: StatsReceiptView
    private lateinit var status: TextView
    private lateinit var share: TextView
    private lateinit var save: TextView
    private var printed = false
    private lateinit var sounds: ReceiptSounds
    private lateinit var printerStatus: TextView
    private lateinit var printerLed: PrinterLedView
    private var refreshStyle: (() -> Unit)? = null
    private val editStyle = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { refreshStyle?.invoke() }
    private var generation = 0
    private val receiptPrefs by lazy { getSharedPreferences("stats_receipt", MODE_PRIVATE) }
    private var theme = ReceiptTheme.WARM
    private var brand = ReceiptBrand.KEYCAP
    private var seal = ReceiptSeal()
    private var footer = ReceiptFooter.LEAF
    private val saveDocument = registerForActivityResult(ActivityResultContracts.CreateDocument("image/png")) { uri ->
        val file = reportFile
        if (uri != null && file != null) {
            status.text = "正在保存…"
            worker.execute {
                val result = runCatching {
                    checkNotNull(contentResolver.openOutputStream(uri)).use { output -> file.inputStream().use { it.copyTo(output) } }
                }
                runOnUiThread { if (!isDestroyed) status.text = if (result.isSuccess) "图片已保存" else "保存失败，请重试" }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        printed = savedInstanceState?.getBoolean("printed") == true
        val dark = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = !dark
            isAppearanceLightNavigationBars = !dark
        }
        val bg = if (dark) Color.rgb(15, 16, 19) else Color.rgb(244, 245, 248)
        val ink = if (dark) Color.rgb(244, 245, 248) else Color.rgb(24, 25, 28)
        val muted = if (dark) Color.rgb(148, 152, 163) else Color.rgb(104, 107, 115)
        val line = if (dark) Color.rgb(40, 42, 50) else Color.rgb(236, 237, 242)
        val accent = Color.rgb(240, 73, 44)
        theme = ReceiptTheme.from(receiptPrefs.getString("theme", null))
        brand = ReceiptBrand.from(receiptPrefs.getString("brand", null))
        seal = ReceiptSeal(ReceiptStamp.from(receiptPrefs.getString("stamp", null)), receiptPrefs.getInt("angle", -14).coerceIn(-30, 30), receiptPrefs.getInt("opacity", 82).coerceIn(10, 100))
        footer = ReceiptFooter.from(receiptPrefs.getString("footer", null))
        sounds = ReceiptSounds(this)
        syncSounds()
        fun dp(n: Int) = (n * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(bg) }
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom); insets
        }
        val header = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(6), dp(16), dp(6))
        }
        val backChip = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            isClickable = true; isFocusable = true
            contentDescription = "返回打字统计"
            background = RippleDrawable(
                ColorStateList.valueOf(if (dark) Color.argb(28, 255, 255, 255) else Color.argb(18, 0, 0, 0)),
                null,
                GradientDrawable().apply { cornerRadius = dp(14).toFloat(); setColor(Color.WHITE) },
            )
            setPadding(dp(8), dp(6), dp(10), dp(6))
            setOnClickListener { finish() }
            addView(ImageView(this@StatsReportActivity).apply {
                setImageResource(R.drawable.ic_chevron_left)
                setColorFilter(ink)
            }, LinearLayout.LayoutParams(dp(24), dp(24)))
            addView(TextView(this@StatsReportActivity).apply {
                text = "打字报告"; textSize = 20f; setTextColor(ink); typeface = Typeface.DEFAULT_BOLD
                setPadding(dp(4), 0, 0, 0)
            })
        }
        header.addView(backChip, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(44)))
        header.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        val styleButton = TextView(this).apply {
            text = "样式"; textSize = 13.5f; gravity = Gravity.CENTER; setTextColor(accent)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            isClickable = true; isFocusable = true; contentDescription = "报告样式"; includeFontPadding = false
            ContextCompat.getDrawable(this@StatsReportActivity, R.drawable.ic_palette)?.mutate()?.let { icon ->
                icon.setTint(accent)
                icon.setBounds(0, 0, dp(15), dp(15))
                setCompoundDrawablesRelative(icon, null, null, null)
                compoundDrawablePadding = dp(5)
            }
            setPadding(dp(13), 0, dp(14), 0)
            val pill = GradientDrawable().apply {
                cornerRadius = dp(50).toFloat()
                setColor(Color.argb(if (dark) 36 else 20, Color.red(accent), Color.green(accent), Color.blue(accent)))
                setStroke(dp(1), Color.argb(if (dark) 80 else 52, Color.red(accent), Color.green(accent), Color.blue(accent)))
            }
            background = RippleDrawable(ColorStateList.valueOf(Color.argb(32, Color.red(accent), Color.green(accent), Color.blue(accent))), pill, null)
        }
        header.addView(styleButton, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(34)))
        root.addView(header)
        root.addView(View(this).apply { setBackgroundColor(line) }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1).coerceAtLeast(1)))
        printerStatus = TextView(this).apply {
            text = "准备中"; textSize = 11.5f; setTextColor(muted); gravity = Gravity.CENTER_VERTICAL or Gravity.END
            includeFontPadding = false; maxLines = 1
        }
        printerLed = PrinterLedView(this, dark).apply { setPrinting(true) }
        val summary = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(dp(22), dp(8), dp(18), dp(6)) }
        summary.addView(TextView(this).apply {
            text = if (intent.getBooleanExtra("year", false)) "全年 · 小票报告" else "近30天 · 小票报告"
            textSize = 12f; setTextColor(muted); gravity = Gravity.CENTER_VERTICAL
        }, LinearLayout.LayoutParams(0, dp(26), 1f))
        summary.addView(printerStatus, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(26)).apply { marginEnd = dp(3) })
        summary.addView(printerLed, LinearLayout.LayoutParams(dp(18), dp(18)))
        root.addView(summary)
        styleButton.setOnClickListener { editStyle.launch(Intent(this, ReceiptStyleActivity::class.java).putExtra("year", intent.getBooleanExtra("year", false))) }
        receipt = StatsReceiptView(this).apply {
            printerAccent = Color.rgb(225, 119, 37)
            onStep = { sounds.step(it) }
            onTear = { sounds.tear() }
            onStateChanged = { phase, pct ->
                val ready = phase == "complete" || phase == "torn"
                printerState(
                    when (phase) {
                        "warmup" -> "预热中"
                        "feeding" -> "打印中 ${pct}%"
                        "stamping" -> "生成中"
                        "torn" -> "已撕开"
                        else -> "已完成"
                    },
                    printing = !ready,
                )
                if (::share.isInitialized) for (button in listOf(share, save)) { button.isEnabled = ready; button.alpha = if (ready) 1f else .4f }
            }
        }
        root.addView(receipt, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        status = TextView(this).apply {
            text = "正在生成打字报告…"; textSize = 12f; gravity = Gravity.CENTER; setTextColor(muted)
            setPadding(dp(16), dp(7), dp(16), dp(7)); accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        root.addView(status)
        val actions = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL; setPadding(dp(20), dp(6), dp(20), dp(14))
        }
        fun action(label: String, primary: Boolean): TextView = TextView(this).apply {
            text = label; textSize = 15f; gravity = Gravity.CENTER; isEnabled = false; alpha = .4f
            isClickable = true; isFocusable = true; includeFontPadding = false
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            // Use our own background and press feedback; platform Button elevation/insets vary by phone.
            elevation = 0f; stateListAnimator = null
            setTextColor(if (primary) Color.WHITE else ink)
            val shape = GradientDrawable().apply {
                cornerRadius = dp(16).toFloat()
                setColor(if (primary) accent else if (dark) Color.rgb(26, 27, 32) else Color.WHITE)
                if (!primary) setStroke(dp(1), line)
            }
            background = RippleDrawable(ColorStateList.valueOf(if (primary) Color.argb(36, 255, 255, 255) else Color.argb(20, 0, 0, 0)), shape, null)
            accessibilityDelegate = object : View.AccessibilityDelegate() {
                override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfo) {
                    super.onInitializeAccessibilityNodeInfo(host, info); info.className = Button::class.java.name
                }
            }
        }
        share = action("分享图片", false).apply { setOnClickListener { shareImage() } }
        save = action("保存图片", true).apply {
            setOnClickListener { saveDocument.launch("空叶打字报告-${statsDate(System.currentTimeMillis())}.png") }
        }
        actions.addView(share, LinearLayout.LayoutParams(0, dp(48), 1f).apply { marginEnd = dp(12) })
        actions.addView(save, LinearLayout.LayoutParams(0, dp(48), 1f))
        root.addView(actions)
        setContentView(root)
        val snapshot = TypingStats.of(this).snapshot()
        val year = intent.getBooleanExtra("year", false)
        fun generate(animate: Boolean) {
            val request = ++generation
            val selected = theme
            val selectedBrand = brand
            val selectedSeal = seal
            val selectedFooter = footer
            reportFile = null
            for (button in listOf(share, save)) { button.isEnabled = false; button.alpha = .4f }
            status.text = "正在生成打字报告…"
            printerState("准备中", printing = true)
            receipt.clear()
            worker.execute {
                val result = runCatching {
                    val file = StatsReport.image(this, snapshot, year, selected, selectedBrand, selectedSeal, selectedFooter)
                    file to checkNotNull(BitmapFactory.decodeFile(file.absolutePath))
                }
                runOnUiThread {
                    if (isFinishing || isDestroyed || request != generation) { result.getOrNull()?.second?.recycle(); return@runOnUiThread }
                    result.onSuccess { (file, bitmap) ->
                        reportFile = file
                        receipt.submit(bitmap, StatsReport.text(snapshot, year), animate) {
                            printed = true; status.text = "报告已生成 · 上下滑动查看"
                        }
                    }.onFailure {
                        printerState("生成失败", printing = false)
                        status.text = "报告生成失败，请返回重试"
                    }
                }
            }
        }
        refreshStyle = {
            theme = ReceiptTheme.from(receiptPrefs.getString("theme", null))
            brand = ReceiptBrand.from(receiptPrefs.getString("brand", null))
            seal = ReceiptSeal(ReceiptStamp.from(receiptPrefs.getString("stamp", null)), receiptPrefs.getInt("angle", -14), receiptPrefs.getInt("opacity", 82))
            footer = ReceiptFooter.from(receiptPrefs.getString("footer", null))
            syncSounds()
            generate(false)
        }
        generate(!printed)
    }

    private fun printerState(label: String, printing: Boolean) {
        printerStatus.text = label
        printerLed.setPrinting(printing)
    }

    private fun shareImage() {
        val file = reportFile ?: return
        val uri = FileProvider.getUriForFile(this, "com.kongye.keyboard.files", file)
        val send = Intent(Intent.ACTION_SEND).setType("image/png").putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        send.clipData = ClipData.newRawUri("打字报告", uri)
        startActivity(Intent.createChooser(send, "分享打字报告"))
    }

    private fun syncSounds() {
        if (!::sounds.isInitialized) return
        val fallback = receiptPrefs.getBoolean("sound", true)
        sounds.printEnabled = receiptPrefs.getBoolean("print_sound", fallback)
        sounds.tearEnabled = receiptPrefs.getBoolean("tear_sound", fallback)
    }

    override fun onSaveInstanceState(outState: Bundle) { outState.putBoolean("printed", printed); super.onSaveInstanceState(outState) }
    override fun onResume() { super.onResume(); if (::sounds.isInitialized) { syncSounds(); sounds.active = true } }
    override fun onPause() { if (::sounds.isInitialized) sounds.active = false; super.onPause() }
    override fun onDestroy() {
        receipt.release()
        if (::printerLed.isInitialized) printerLed.release()
        if (::sounds.isInitialized) sounds.release()
        worker.shutdownNow()
        super.onDestroy()
    }
}
