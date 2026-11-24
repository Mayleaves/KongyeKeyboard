package com.kongye.keyboard

import android.graphics.Canvas as AndroidCanvas
import android.graphics.Color as AndroidColor
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import kotlin.math.max
import kotlin.math.roundToInt

/** A dedicated editor: every control updates the exact stamp/brand/footer renderer used by export. */
class ReceiptStyleActivity : ComponentActivity() {
    private val prefs by lazy { getSharedPreferences("stats_receipt", MODE_PRIVATE) }
    private lateinit var soundPreview: ReceiptSounds

    private class StylePal(
        val dark: Boolean,
        val bg: Color,
        val card: Color,
        val studio: Color,
        val ink: Color,
        val text2: Color,
        val line: Color,
        val tile: Color,
        val track: Color,
        val tabActive: Color,
        val accent: Color,
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val legacySound = prefs.getBoolean("sound", true)
        soundPreview = ReceiptSounds(this).apply {
            printEnabled = prefs.getBoolean("print_sound", legacySound)
            tearEnabled = prefs.getBoolean("tear_sound", legacySound)
        }
        val year = intent.getBooleanExtra("year", false)
        setContent {
            val dark = isSystemInDarkTheme()
            SideEffect {
                WindowCompat.getInsetsController(window, window.decorView).apply {
                    isAppearanceLightStatusBars = !dark
                    isAppearanceLightNavigationBars = !dark
                }
            }
            val pal = remember(dark) {
                if (dark) StylePal(
                    dark = true,
                    bg = Color(0xFF0F1013),
                    card = Color(0xFF1A1B20),
                    studio = Color(0xFF121318),
                    ink = Color(0xFFF4F5F8),
                    text2 = Color(0xFF9498A3),
                    line = Color(0xFF282A32),
                    tile = Color(0xFF24262E),
                    track = Color(0xFF323540),
                    tabActive = Color(0xFF333744),
                    accent = Color(0xFFF0492C),
                ) else StylePal(
                    dark = false,
                    bg = Color(0xFFF4F5F8),
                    card = Color(0xFFFFFFFF),
                    studio = Color(0xFFECEEF3),
                    ink = Color(0xFF18191C),
                    text2 = Color(0xFF686B73),
                    line = Color(0xFFECEDF2),
                    tile = Color(0xFFF0F2F6),
                    track = Color(0xFFE2E4EC),
                    tabActive = Color(0xFFFFFFFF),
                    accent = Color(0xFFF0492C),
                )
            }
            StyleScreen(pal, year, savedInstanceState?.getInt("category") ?: 0)
        }
    }

    @Composable
    private fun StyleScreen(pal: StylePal, year: Boolean, initialCategory: Int) {
        val names = remember { listOf("纸张配色", "报告头图", "印章", "落款") }
        val snapshot = remember { TypingStats.of(this@ReceiptStyleActivity).snapshot() }
        val today = remember { statsDate(System.currentTimeMillis()) }
        val legacySound = remember { prefs.getBoolean("sound", true) }

        var category by rememberSaveable { mutableIntStateOf(initialCategory.coerceIn(0, 3)) }
        var themeName by rememberSaveable { mutableStateOf(ReceiptTheme.from(prefs.getString("theme", null)).name) }
        var brandName by rememberSaveable { mutableStateOf(ReceiptBrand.from(prefs.getString("brand", null)).name) }
        var stampName by rememberSaveable { mutableStateOf(ReceiptStamp.from(prefs.getString("stamp", null)).name) }
        var angle by rememberSaveable { mutableIntStateOf(prefs.getInt("angle", -14).coerceIn(-30, 30)) }
        var opacity by rememberSaveable { mutableIntStateOf(prefs.getInt("opacity", 82).coerceIn(10, 100)) }
        var footerName by rememberSaveable { mutableStateOf(ReceiptFooter.from(prefs.getString("footer", null)).name) }
        var printSound by rememberSaveable { mutableStateOf(prefs.getBoolean("print_sound", legacySound)) }
        var tearSound by rememberSaveable { mutableStateOf(prefs.getBoolean("tear_sound", legacySound)) }

        val theme = ReceiptTheme.from(themeName)
        val brand = ReceiptBrand.from(brandName)
        val seal = ReceiptSeal(ReceiptStamp.from(stampName), angle, opacity)
        val footer = ReceiptFooter.from(footerName)

        fun persist(
            nextTheme: String = themeName,
            nextBrand: String = brandName,
            nextStamp: String = stampName,
            nextAngle: Int = angle,
            nextOpacity: Int = opacity,
            nextFooter: String = footerName,
        ) {
            themeName = nextTheme
            brandName = nextBrand
            stampName = nextStamp
            angle = nextAngle.coerceIn(-30, 30)
            opacity = nextOpacity.coerceIn(10, 100)
            footerName = nextFooter
            prefs.edit()
                .putString("theme", themeName)
                .putString("brand", brandName)
                .putString("stamp", stampName)
                .putInt("angle", angle)
                .putInt("opacity", opacity)
                .putString("footer", footerName)
                .apply()
        }

        fun togglePrintSound(on: Boolean) {
            printSound = on
            prefs.edit().putBoolean("print_sound", on).apply()
            soundPreview.printEnabled = on
            if (on) soundPreview.previewPrint()
        }

        fun toggleTearSound(on: Boolean) {
            tearSound = on
            prefs.edit().putBoolean("tear_sound", on).apply()
            soundPreview.tearEnabled = on
            if (on) soundPreview.previewTear()
        }

        val isDefault = theme == ReceiptTheme.WARM && brand == ReceiptBrand.KEYCAP &&
            seal.stamp == ReceiptStamp.CINNABAR && angle == -14 && opacity == 82 && footer == ReceiptFooter.LEAF

        val screenHeightDp = LocalConfiguration.current.screenHeightDp
        val previewHeight = (screenHeightDp * 0.24f).coerceIn(152f, 198f).dp
        val currentSummary = when (category) {
            0 -> theme.title
            1 -> brand.title
            2 -> "${seal.stamp.title} · ${if (angle > 0) "+$angle" else "$angle"}°"
            else -> footer.title
        }

        Box(Modifier.fillMaxSize().background(pal.bg)) {
            Column(
                Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)
                    .widthIn(max = 600.dp).align(Alignment.TopCenter),
            ) {
                // Top Bar matching SettingsActivity
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Row(
                        Modifier.clip(RoundedCornerShape(14.dp))
                            .clickable { finish() }
                            .padding(horizontal = 8.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Image(
                            painterResource(R.drawable.ic_chevron_left),
                            contentDescription = "返回报告",
                            modifier = Modifier.size(24.dp),
                            colorFilter = ColorFilter.tint(pal.ink),
                        )
                        Spacer(Modifier.width(4.dp))
                        BasicText(
                            "报告样式",
                            style = TextStyle(color = pal.ink, fontSize = 20.sp, fontWeight = FontWeight.Bold),
                        )
                    }
                    Spacer(Modifier.weight(1f))
                    if (!isDefault) {
                        Box(
                            Modifier.size(38.dp).clip(CircleShape)
                                .clickable {
                                    persist(
                                        nextTheme = ReceiptTheme.WARM.name,
                                        nextBrand = ReceiptBrand.KEYCAP.name,
                                        nextStamp = ReceiptStamp.CINNABAR.name,
                                        nextAngle = -14,
                                        nextOpacity = 82,
                                        nextFooter = ReceiptFooter.LEAF.name,
                                    )
                                },
                            contentAlignment = Alignment.Center,
                        ) {
                            Image(
                                painterResource(R.drawable.ic_refresh),
                                contentDescription = "恢复默认样式",
                                modifier = Modifier.size(20.dp),
                                colorFilter = ColorFilter.tint(pal.text2),
                            )
                        }
                        Spacer(Modifier.width(6.dp))
                    }
                    Box(
                        Modifier.clip(RoundedCornerShape(50))
                            .background(pal.accent)
                            .clickable(role = Role.Button) { finish() }
                            .padding(horizontal = 16.dp, vertical = 7.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        BasicText(
                            "完成",
                            style = TextStyle(color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Medium),
                        )
                    }
                }
                Box(Modifier.fillMaxWidth().height(1.dp).background(pal.line))

                // Dual Sound Controls Card (placed directly ABOVE Live Preview)
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(top = 10.dp)
                        .clip(RoundedCornerShape(18.dp)).background(pal.card)
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CompactSoundToggle(
                        pal = pal,
                        label = "打印音效",
                        switchDescription = "打印音效开关",
                        checked = printSound,
                        onToggle = ::togglePrintSound,
                        modifier = Modifier.weight(1f),
                    )
                    Box(Modifier.padding(horizontal = 12.dp).width(1.dp).height(22.dp).background(pal.line))
                    CompactSoundToggle(
                        pal = pal,
                        label = "撕票音效",
                        switchDescription = "撕票音效开关",
                        checked = tearSound,
                        onToggle = ::toggleTearSound,
                        modifier = Modifier.weight(1f),
                    )
                }

                // Pinned Live Specimen Stage + Segmented Category Switcher
                Column(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)
                        .clip(RoundedCornerShape(20.dp)).background(pal.card)
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                ) {
                    Row(
                        Modifier.fillMaxWidth().padding(bottom = 9.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(Modifier.size(6.dp).clip(CircleShape).background(Color(theme.stamp)))
                        Spacer(Modifier.width(7.dp))
                        BasicText(
                            "${names[category]} · 实时预览",
                            style = TextStyle(color = pal.text2, fontSize = 12.5.sp, fontWeight = FontWeight.Medium),
                        )
                        Spacer(Modifier.weight(1f))
                        BasicText(
                            currentSummary,
                            style = TextStyle(
                                color = pal.ink,
                                fontSize = 12.5.sp,
                                fontWeight = FontWeight.SemiBold,
                                fontFeatureSettings = "tnum",
                            ),
                        )
                    }

                    LiveReceiptSpecimen(
                        pal = pal,
                        category = category,
                        theme = theme,
                        brand = brand,
                        seal = seal,
                        footer = footer,
                        snapshot = snapshot,
                        today = today,
                        year = year,
                        modifier = Modifier.fillMaxWidth().height(previewHeight),
                    )

                    Spacer(Modifier.height(11.dp))

                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                            .background(pal.tile).padding(3.dp).selectableGroup(),
                    ) {
                        names.forEachIndexed { index, name ->
                            val active = category == index
                            Box(
                                Modifier.weight(1f).height(38.dp)
                                    .let { if (active && !pal.dark) it.shadow(1.5.dp, RoundedCornerShape(9.dp)) else it }
                                    .clip(RoundedCornerShape(9.dp))
                                    .background(if (active) pal.tabActive else Color.Transparent)
                                    .selectable(selected = active, role = Role.Tab, onClick = { category = index }),
                                contentAlignment = Alignment.Center,
                            ) {
                                BasicText(
                                    name,
                                    style = TextStyle(
                                        color = if (active) pal.ink else pal.text2,
                                        fontSize = 13.sp,
                                        fontWeight = if (active) FontWeight.Bold else FontWeight.Medium,
                                    ),
                                    maxLines = 1,
                                )
                            }
                        }
                    }
                }

                // Scrollable Options Area
                Column(
                    Modifier.weight(1f).fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp)
                        .padding(bottom = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    // 2x2 Visual Specimen Grid Card
                    Column(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp))
                            .background(pal.card).padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        when (category) {
                            0 -> SpecimenGrid(ReceiptTheme.entries, theme, pal, { persist(nextTheme = it.name) }, { it.title }) { item, canvas, bounds, d ->
                                drawThemeThumbnail(canvas, bounds, item, d)
                            }
                            1 -> SpecimenGrid(ReceiptBrand.entries, brand, pal, { persist(nextBrand = it.name) }, { it.title }, paperRule = theme.rule) { item, canvas, bounds, d ->
                                theme.drawPaper(canvas, bounds)
                                val inset = RectF(bounds).apply { inset(12f * d, 6f * d) }
                                ReceiptBrandArt.draw(this@ReceiptStyleActivity, canvas, inset, item, theme)
                            }
                            2 -> SpecimenGrid(ReceiptStamp.entries, seal.stamp, pal, { persist(nextStamp = it.name) }, { it.title }, paperRule = theme.rule) { item, canvas, bounds, d ->
                                theme.drawPaper(canvas, bounds)
                                val size = minOf(bounds.width() * 0.68f, bounds.height() * 0.84f)
                                val box = RectF(bounds.centerX() - size / 2f, bounds.centerY() - size / 2f, bounds.centerX() + size / 2f, bounds.centerY() + size / 2f)
                                ReceiptSeal(item, angle = -8, opacity = 88).draw(this@ReceiptStyleActivity, canvas, box, theme, today)
                            }
                            else -> SpecimenGrid(ReceiptFooter.entries, footer, pal, { persist(nextFooter = it.name) }, { it.title }, paperRule = theme.rule) { item, canvas, bounds, d ->
                                theme.drawPaper(canvas, bounds)
                                val inset = RectF(bounds).apply { inset(8f * d, 6f * d) }
                                item.draw(this@ReceiptStyleActivity, canvas, inset, theme, snapshot)
                            }
                        }
                    }

                    // Stamp Fine-Tuning Controls Card
                    AnimatedVisibility(
                        visible = category == 2,
                        enter = fadeIn(tween(150)) + expandVertically(tween(180)),
                        exit = fadeOut(tween(120)) + shrinkVertically(tween(150)),
                    ) {
                        Column(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp))
                                .background(pal.card).padding(horizontal = 14.dp, vertical = 6.dp),
                        ) {
                            Row(
                                Modifier.fillMaxWidth().padding(top = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                BasicText(
                                    "印章倾角",
                                    style = TextStyle(color = pal.ink, fontSize = 15.5.sp, fontWeight = FontWeight.Medium),
                                    modifier = Modifier.weight(1f),
                                )
                                if (angle != -14 || opacity != 82) {
                                    BasicText(
                                        "默认参数",
                                        style = TextStyle(color = pal.accent, fontSize = 12.5.sp, fontWeight = FontWeight.Medium),
                                        modifier = Modifier.clip(RoundedCornerShape(8.dp))
                                            .clickable { persist(nextAngle = -14, nextOpacity = 82) }
                                            .padding(horizontal = 6.dp, vertical = 2.dp),
                                    )
                                }
                            }
                            StyleSlider(
                                pal = pal,
                                value = angle.toFloat(),
                                min = -30f,
                                max = 30f,
                                centerValue = 0f,
                                description = "印章倾角微调",
                                format = { val v = it.roundToInt(); "${if (v > 0) "+$v" else "$v"}°" },
                            ) { next ->
                                persist(nextAngle = next.roundToInt())
                            }

                            Box(Modifier.fillMaxWidth().height(1.dp).background(pal.line))

                            BasicText(
                                "印泥浓淡",
                                style = TextStyle(color = pal.ink, fontSize = 15.5.sp, fontWeight = FontWeight.Medium),
                                modifier = Modifier.padding(top = 12.dp),
                            )
                            StyleSlider(
                                pal = pal,
                                value = opacity.toFloat(),
                                min = 10f,
                                max = 100f,
                                description = "印泥浓淡微调",
                                format = { "${it.roundToInt()}%" },
                            ) { next ->
                                persist(nextOpacity = next.roundToInt())
                            }
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun CompactSoundToggle(
        pal: StylePal,
        label: String,
        switchDescription: String,
        checked: Boolean,
        onToggle: (Boolean) -> Unit,
        modifier: Modifier = Modifier,
    ) {
        val currentToggle by rememberUpdatedState(onToggle)
        Row(
            modifier,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BasicText(
                label,
                style = TextStyle(color = pal.ink, fontSize = 14.5.sp, fontWeight = FontWeight.Medium),
                maxLines = 1,
                modifier = Modifier.weight(1f)
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(interactionSource = null, indication = null) { currentToggle(!checked) }
                    .padding(vertical = 3.dp),
            )
            Spacer(Modifier.width(8.dp))
            val thumbOffset by animateDpAsState(if (checked) 18.dp else 0.dp, tween(150), label = switchDescription)
            Box(
                Modifier.size(width = 44.dp, height = 26.dp)
                    .clip(RoundedCornerShape(50))
                    .background(if (checked) pal.accent else pal.track)
                    .toggleable(value = checked, role = Role.Switch) { currentToggle(it) }
                    .padding(3.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                Box(
                    Modifier.offset(x = thumbOffset).size(20.dp).clip(CircleShape).background(Color.White)
                        .semantics { contentDescription = switchDescription },
                )
            }
        }
    }

    @Composable
    private fun <T> SpecimenGrid(
        entries: List<T>,
        selected: T,
        pal: StylePal,
        onSelect: (T) -> Unit,
        labelOf: (T) -> String,
        paperRule: Int? = null,
        drawThumb: (T, AndroidCanvas, RectF, Float) -> Unit,
    ) {
        Column(Modifier.fillMaxWidth().selectableGroup(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            for (row in entries.chunked(2)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    for (item in row) {
                        val active = item == selected
                        val borderColor = if (active) pal.accent else pal.line
                        val tileBg = if (active) pal.accent.copy(alpha = if (pal.dark) 0.14f else 0.06f) else pal.tile
                        val thumbBorder = Color(paperRule ?: (item as? ReceiptTheme)?.rule ?: pal.line.toArgb()).copy(alpha = 0.65f)
                        Column(
                            Modifier.weight(1f)
                                .clip(RoundedCornerShape(14.dp))
                                .background(tileBg)
                                .border(if (active) 1.8.dp else 1.dp, borderColor, RoundedCornerShape(14.dp))
                                .selectable(selected = active, role = Role.RadioButton, onClick = { onSelect(item) })
                                .padding(7.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Canvas(
                                Modifier.fillMaxWidth().height(58.dp)
                                    .clip(RoundedCornerShape(9.dp))
                                    .border(0.75.dp, thumbBorder, RoundedCornerShape(9.dp)),
                            ) {
                                drawIntoCanvas { canvas ->
                                    drawThumb(item, canvas.nativeCanvas, RectF(0f, 0f, size.width, size.height), density)
                                }
                            }
                            Row(
                                Modifier.fillMaxWidth().padding(top = 7.dp, bottom = 3.dp, start = 2.dp, end = 2.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.Center,
                            ) {
                                if (active) {
                                    Box(
                                        Modifier.size(15.dp).clip(CircleShape).background(pal.accent),
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        Image(
                                            painterResource(R.drawable.ic_done),
                                            contentDescription = null,
                                            modifier = Modifier.size(10.dp),
                                            colorFilter = ColorFilter.tint(Color.White),
                                        )
                                    }
                                    Spacer(Modifier.width(5.dp))
                                }
                                BasicText(
                                    labelOf(item),
                                    style = TextStyle(
                                        color = if (active) pal.accent else pal.ink,
                                        fontSize = 13.5.sp,
                                        fontWeight = if (active) FontWeight.Bold else FontWeight.Medium,
                                    ),
                                    maxLines = 1,
                                )
                            }
                        }
                    }
                    if (row.size == 1) Spacer(Modifier.weight(1f))
                }
            }
        }
    }

    private fun drawThemeThumbnail(c: AndroidCanvas, bounds: RectF, t: ReceiptTheme, d: Float) {
        t.drawPaper(c, bounds)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        val pad = 8f * d
        // Mini card block on left
        val cardRect = RectF(bounds.left + pad, bounds.top + pad, bounds.left + bounds.width() * 0.44f, bounds.bottom - pad)
        p.color = t.card
        c.drawRoundRect(cardRect, 5f * d, 5f * d, p)
        p.color = t.ink
        c.drawRoundRect(RectF(cardRect.left + 6f * d, cardRect.top + 8f * d, cardRect.right - 10f * d, cardRect.top + 12.5f * d), 2f * d, 2f * d, p)
        p.color = t.sub
        c.drawRoundRect(RectF(cardRect.left + 6f * d, cardRect.top + 17f * d, cardRect.left + cardRect.width() * 0.62f, cardRect.top + 20.5f * d), 1.5f * d, 1.5f * d, p)
        p.color = t.accent
        c.drawRoundRect(RectF(cardRect.left + 6f * d, cardRect.bottom - 12f * d, cardRect.left + 22f * d, cardRect.bottom - 8f * d), 2f * d, 2f * d, p)

        // Mini bar chart in center
        val heights = floatArrayOf(0.38f, 0.72f, 1f, 0.54f)
        val barAreaLeft = cardRect.right + 8f * d
        val barAreaRight = bounds.right - 34f * d
        val barBottom = bounds.bottom - pad - 2f * d
        val maxBarH = (bounds.height() - pad * 2 - 8f * d).coerceAtLeast(8f * d)
        val step = ((barAreaRight - barAreaLeft) / heights.size).coerceAtLeast(4f * d)
        for (i in heights.indices) {
            val bh = maxBarH * heights[i]
            p.color = if (i == 2) t.accent else t.bar
            c.drawRoundRect(
                RectF(barAreaLeft + i * step, barBottom - bh, barAreaLeft + (i + 1) * step - 2.5f * d, barBottom),
                2f * d, 2f * d, p,
            )
        }

        // Mini cinnabar/amber stamp emblem on right
        val cx = bounds.right - 17f * d
        val cy = bounds.centerY()
        val r = 9.5f * d
        p.style = Paint.Style.STROKE
        p.color = t.stamp
        p.strokeWidth = 1.4f * d
        c.drawRoundRect(RectF(cx - r, cy - r, cx + r, cy + r), 3f * d, 3f * d, p)
        p.strokeWidth = 0.8f * d
        p.alpha = 165
        c.drawRoundRect(RectF(cx - r + 2.5f * d, cy - r + 2.5f * d, cx + r - 2.5f * d, cy + r - 2.5f * d), 1.8f * d, 1.8f * d, p)
        p.style = Paint.Style.FILL
        p.alpha = 210
        c.drawCircle(cx, cy, 2.2f * d, p)
    }

    @Composable
    private fun LiveReceiptSpecimen(
        pal: StylePal,
        category: Int,
        theme: ReceiptTheme,
        brand: ReceiptBrand,
        seal: ReceiptSeal,
        footer: ReceiptFooter,
        snapshot: StatsSnapshot,
        today: String,
        year: Boolean,
        modifier: Modifier = Modifier,
    ) {
        Canvas(
            modifier.clip(RoundedCornerShape(14.dp))
                .background(pal.studio)
                .border(1.dp, pal.line, RoundedCornerShape(14.dp))
                .semantics { contentDescription = "样式实时预览" },
        ) {
            drawIntoCanvas { composeCanvas ->
                val c = composeCanvas.nativeCanvas
                val d = density
                fun dp(v: Float) = v * d

                val paper = RectF(dp(14f), dp(10f), size.width - dp(14f), size.height - dp(12f))
                val teeth = 32
                val pitch = paper.width() / teeth
                val depth = dp(4f)

                fun buildCut(offsetY: Float = 0f) = Path().apply {
                    val top = paper.top + offsetY
                    val bottom = paper.bottom + offsetY
                    moveTo(paper.left, top + depth)
                    for (i in 0 until teeth) {
                        lineTo(paper.left + (i + 0.5f) * pitch, top)
                        lineTo(paper.left + (i + 1f) * pitch, top + depth)
                    }
                    lineTo(paper.right, bottom - depth)
                    for (i in teeth downTo 1) {
                        lineTo(paper.left + (i - 0.5f) * pitch, bottom)
                        lineTo(paper.left + (i - 1f) * pitch, bottom - depth)
                    }
                    close()
                }

                val p = Paint(Paint.ANTI_ALIAS_FLAG)
                // Layered crisp drop shadow under serrated paper
                p.style = Paint.Style.FILL
                p.color = AndroidColor.argb(if (pal.dark) 92 else 18, 0, 0, 0)
                c.drawPath(buildCut(dp(4.2f)), p)
                p.color = AndroidColor.argb(if (pal.dark) 70 else 24, 0, 0, 0)
                c.drawPath(buildCut(dp(1.8f)), p)

                val cut = buildCut(0f)
                c.save()
                c.clipPath(cut)
                theme.drawPaper(c, paper)

                val inner = RectF(paper).apply { inset(dp(18f), dp(14f)) }
                val dash = DashPathEffect(floatArrayOf(dp(4f), dp(4f)), 0f)

                fun drawRule(y: Float) {
                    p.style = Paint.Style.STROKE
                    p.color = theme.rule
                    p.strokeWidth = dp(1f)
                    p.pathEffect = dash
                    c.drawLine(inner.left, y, inner.right, y, p)
                    p.pathEffect = null
                    p.style = Paint.Style.FILL
                }

                val headerOffset = if (theme == ReceiptTheme.LETTERPRESS && category in 0..1) {
                    val scale = paper.width() / 1080f
                    theme.drawInfoHeader(
                        c,
                        RectF(inner.left, paper.top + 50f * scale, inner.right, paper.top + 102f * scale),
                        today,
                        year,
                    )
                    dp(16f)
                } else 0f

                when (category) {
                    0 -> {
                        val topY = inner.top + headerOffset
                        p.style = Paint.Style.FILL
                        p.textAlign = Paint.Align.LEFT
                        p.typeface = Typeface.DEFAULT_BOLD
                        p.textSize = dp(11f)
                        p.color = theme.accent
                        c.drawText("01", inner.left, topY + dp(13f), p)

                        p.color = theme.ink
                        p.textSize = dp(14.5f)
                        c.drawText("${if (year) "全年" else "近30天"}打字报告", inner.left + dp(20f), topY + dp(13.5f), p)

                        p.typeface = Typeface.DEFAULT
                        p.color = theme.sub
                        p.textSize = dp(10.5f)
                        c.drawText("截至 $today · ${snapshot.level.first}", inner.left, topY + dp(30f), p)

                        val miniSealSize = dp(40f)
                        val miniSealRect = RectF(inner.right - miniSealSize, topY - dp(2f), inner.right, topY + miniSealSize - dp(2f))
                        seal.draw(this@ReceiptStyleActivity, c, miniSealRect, theme, today)

                        val ruleY = topY + dp(43f)
                        drawRule(ruleY)

                        val cardsTop = ruleY + dp(9f)
                        val cardsBottom = inner.bottom - dp(2f)
                        if (cardsBottom > cardsTop + dp(26f)) {
                            val leftWidth = inner.width() * 0.54f
                            val cardGap = dp(7f)
                            val cardW = (leftWidth - cardGap) / 2f
                            val metrics = listOf(
                                statsNumber(snapshot.today) to "今日输入",
                                "${snapshot.streak} 天" to "连续记录",
                            )
                            for ((idx, pair) in metrics.withIndex()) {
                                val cx = inner.left + idx * (cardW + cardGap)
                                val rect = RectF(cx, cardsTop, cx + cardW, cardsBottom)
                                p.color = theme.card
                                c.drawRoundRect(rect, dp(8f), dp(8f), p)
                                p.color = theme.ink
                                p.typeface = Typeface.DEFAULT_BOLD
                                p.textSize = if (pair.first.length > 6) dp(11.5f) else dp(14f)
                                c.drawText(pair.first, rect.left + dp(9f), rect.centerY() - dp(2f), p)
                                p.color = theme.sub
                                p.typeface = Typeface.DEFAULT
                                p.textSize = dp(9.5f)
                                c.drawText(pair.second, rect.left + dp(9f), rect.centerY() + dp(13f), p)
                            }

                            // Mini daily bar chart on right
                            val chartLeft = inner.left + leftWidth + dp(12f)
                            val chartRight = inner.right
                            val recent = snapshot.days(false).takeLast(10).map { it.second }
                            val hasReal = recent.any { it > 0L }
                            val fallback = floatArrayOf(0.25f, 0.42f, 0.3f, 0.68f, 0.52f, 0.95f, 0.45f, 0.6f, 0.78f, 0.5f)
                            val peak = (recent.maxOrNull() ?: 1L).coerceAtLeast(1L).toFloat()
                            val count = 10
                            val step = (chartRight - chartLeft) / count
                            val maxH = (cardsBottom - cardsTop - dp(6f)).coerceAtLeast(dp(12f))
                            for (i in 0 until count) {
                                val ratio = if (hasReal) (recent.getOrElse(i) { 0L } / peak) else fallback[i]
                                val bh = max(dp(3f), maxH * ratio)
                                p.color = if (ratio == 0f) theme.card else if (i == (if (hasReal) recent.indices.maxByOrNull { recent[it] } ?: 5 else 5)) theme.accent else theme.bar
                                c.drawRoundRect(
                                    RectF(chartLeft + i * step, cardsBottom - bh, chartLeft + (i + 1) * step - step * 0.26f, cardsBottom),
                                    dp(2f), dp(2f), p,
                                )
                            }
                        }
                    }

                    1 -> {
                        val topY = inner.top + headerOffset
                        val brandMaxW = minOf(inner.width() * 0.84f, dp(224f))
                        val brandBottom = (inner.bottom - dp(28f)).coerceAtLeast(topY + dp(40f))
                        val brandRect = RectF(inner.centerX() - brandMaxW / 2f, topY, inner.centerX() + brandMaxW / 2f, brandBottom)
                        ReceiptBrandArt.draw(this@ReceiptStyleActivity, c, brandRect, brand, theme)
                        drawRule(inner.bottom - dp(20f))
                        p.color = theme.sub
                        p.typeface = Typeface.DEFAULT
                        p.textSize = dp(10.5f)
                        p.textAlign = Paint.Align.CENTER
                        c.drawText("${if (year) "全年" else "近30天"}打字报告 · 截至 $today", inner.centerX(), inner.bottom - dp(4f), p)
                        p.textAlign = Paint.Align.LEFT
                    }

                    2 -> {
                        drawRule(inner.top + dp(4f))
                        p.textAlign = Paint.Align.LEFT
                        p.typeface = Typeface.DEFAULT
                        p.color = theme.sub
                        p.textSize = dp(10.5f)
                        val baseTop = inner.centerY() - dp(18f)
                        c.drawText("速度按连续输入时间计算", inner.left, baseTop, p)
                        c.drawText("语音和粘贴不计速度", inner.left, baseTop + dp(16f), p)
                        p.textSize = dp(9.5f)
                        p.alpha = 190
                        c.drawText("记录起始 ${snapshot.since}", inner.left, baseTop + dp(34f), p)
                        c.drawText("仅统计数量，不保存内容", inner.left, baseTop + dp(48f), p)
                        p.alpha = 255

                        val sealSize = minOf(inner.width() * 0.44f, inner.height() * 0.84f)
                        val cx = inner.right - sealSize * 0.54f
                        val cy = inner.centerY() + dp(4f)
                        val sealRect = RectF(cx - sealSize / 2f, cy - sealSize / 2f, cx + sealSize / 2f, cy + sealSize / 2f)
                        seal.draw(this@ReceiptStyleActivity, c, sealRect, theme, today)
                    }

                    else -> {
                        drawRule(inner.top + dp(6f))
                        val footerW = minOf(inner.width() * 0.88f, dp(248f))
                        val footerRect = RectF(inner.centerX() - footerW / 2f, inner.top + dp(16f), inner.centerX() + footerW / 2f, inner.bottom - dp(4f))
                        footer.draw(this@ReceiptStyleActivity, c, footerRect, theme, snapshot)
                    }
                }
                c.restore()

                // Crisp subtle paper edge outline
                p.style = Paint.Style.STROKE
                p.strokeWidth = dp(0.75f)
                p.color = AndroidColor.argb(if (theme == ReceiptTheme.AMBER) 170 else 135, AndroidColor.red(theme.rule), AndroidColor.green(theme.rule), AndroidColor.blue(theme.rule))
                c.drawPath(cut, p)
            }
        }
    }

    @Composable
    private fun StyleSlider(
        pal: StylePal,
        value: Float,
        min: Float,
        max: Float,
        description: String,
        centerValue: Float? = null,
        format: (Float) -> String,
        onChange: (Float) -> Unit,
    ) {
        val currentChange by rememberUpdatedState(onChange)
        val drawing = remember { SliderDrawing() }
        val center = centerValue?.takeIf { it > min && it < max }
        val fraction = (if (center == null) (value - min) / (max - min)
        else if (value <= center) 0.5f * (value - min) / (center - min)
        else 0.5f + 0.5f * (value - center) / (max - center)).coerceIn(0f, 1f)

        fun valueAt(f: Float): Float = (if (center == null) min + (max - min) * f
        else if (f <= 0.5f) min + (center - min) * f * 2f
        else center + (max - center) * (f - 0.5f) * 2f).coerceIn(min, max)

        val valueText = format(value)

        Canvas(
            Modifier.fillMaxWidth().height(56.dp)
                .semantics {
                    contentDescription = description
                    progressBarRangeInfo = ProgressBarRangeInfo(value, min..max, 0)
                    stateDescription = valueText
                    setProgress { target ->
                        currentChange(target.coerceIn(min, max))
                        true
                    }
                }
                .pointerInput(min, max, center) {
                    detectTapGestures { point ->
                        currentChange(valueAt(drawing.fractionAt(point.x, size.width.toFloat(), density)))
                    }
                }
                .pointerInput(min, max, center) {
                    detectHorizontalDragGestures { change, _ ->
                        change.consume()
                        currentChange(valueAt(drawing.fractionAt(change.position.x, size.width.toFloat(), density)))
                    }
                },
        ) {
            drawIntoCanvas { canvas ->
                drawing.draw(
                    canvas.nativeCanvas,
                    size.width,
                    20.dp.toPx(),
                    density,
                    fraction,
                    pal.track.toArgb(),
                    pal.accent.toArgb(),
                    pal.text2.toArgb(),
                    40.dp.toPx(),
                    12.sp.toPx(),
                    valueText,
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (::soundPreview.isInitialized) soundPreview.active = true
    }

    override fun onPause() {
        if (::soundPreview.isInitialized) soundPreview.active = false
        super.onPause()
    }

    override fun onDestroy() {
        if (::soundPreview.isInitialized) soundPreview.release()
        super.onDestroy()
    }
}
