package com.kongye.keyboard

import android.app.Instrumentation
import android.content.Context
import android.content.ContextWrapper
import android.content.res.Configuration
import android.os.Bundle
import android.os.SystemClock
import android.text.Editable
import android.text.Selection
import android.text.method.BaseKeyListener
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.BaseInputConnection
import android.view.inspector.WindowInspector
import android.widget.ScrollView

object FloatingLayout400Regression {
    fun run(i: Instrumentation) {
        val ctx = i.targetContext
        val isolated = ctx.getSharedPreferences("floating-layout-400", Context.MODE_PRIVATE)
        fun profile(orientation: Int): Context {
            val config = Configuration(ctx.resources.configuration).apply { this.orientation = orientation }
            return object : ContextWrapper(ctx.createConfigurationContext(config)) {
                override fun getSharedPreferences(name: String, mode: Int) = isolated
            }
        }
        val portrait = profile(Configuration.ORIENTATION_PORTRAIT)
        val landscape = profile(Configuration.ORIENTATION_LANDSCAPE)
        isolated.edit().clear().putFloat(Prefs.HEIGHT_SCALE, 1.1f).putInt(Prefs.PAD_L, 5).commit()
        V3Prefs.setSeparateLayouts(portrait, true)
        val normal = FloatingKeyboard.newAdjustment(portrait, false).apply {
            scale = 1.25f; padL = 14; padR = 16; padB = 18; splitGap = 82f; splitSize = .9f
        }
        isolated.edit().also { FloatingKeyboard.save(portrait,normal,it) }.commit()
        check(FloatingKeyboard.newAdjustment(landscape, false).scale == 1.1f)
        val wide = FloatingKeyboard.newAdjustment(landscape, false).apply {
            scale = .8f; padL = 1; padR = 2; padB = 3; splitGap = 110f; splitSize = .7f
        }
        isolated.edit().also { FloatingKeyboard.save(landscape,wide,it) }.commit()
        check(FloatingKeyboard.newAdjustment(portrait, false).let { it.scale == 1.25f && it.padL == 14 && it.padB == 18 && it.splitGap == 82f })
        check(FloatingKeyboard.newAdjustment(landscape, false).let { it.scale == .8f && it.padL == 1 && it.padB == 3 && it.splitSize == .7f })
        for ((context, height) in listOf(portrait to 1.05f, landscape to .75f)) {
            val floating = FloatingKeyboard.newAdjustment(context, true).apply { scale = height }
            val edit = isolated.edit()
            FloatingKeyboard.save(context, floating, edit)
            edit.commit()
        }
        check(FloatingKeyboard.height(portrait) == 1.05f && FloatingKeyboard.height(landscape) == .75f)
        V3Prefs.setSeparateLayouts(portrait, false)
        check(FloatingKeyboard.newAdjustment(landscape, false).scale == 1.25f)
        V3Prefs.setSeparateLayouts(portrait, true)
        check(FloatingKeyboard.newAdjustment(landscape, false).scale == .8f)
        isolated.edit().clear().commit()
        i.runOnMainSync {
            val view = View(ctx)
            val keys = object : BaseKeyListener() { override fun getInputType() = android.text.InputType.TYPE_CLASS_TEXT }
            class Editor(text: String, val codePoints: Boolean) : BaseInputConnection(view, true) {
                val value = Editable.Factory.getInstance().newEditable(text)
                var nativeDeletes = 0
                override fun getEditable() = value
                override fun deleteSurroundingTextInCodePoints(before: Int, after: Int) = codePoints
                override fun sendKeyEvent(event: KeyEvent): Boolean {
                    if (event.keyCode != KeyEvent.KEYCODE_DEL) return false
                    if (event.action == KeyEvent.ACTION_DOWN) { nativeDeletes++; keys.onKeyDown(view, value, event.keyCode, event) }
                    return true
                }
            }
            for (pkg in listOf("com.oplus.launcher", "com.miui.home", "com.android.launcher3")) {
                val editor = Editor("社交", true)
                Selection.setSelection(editor.value, 2)
                EditorDeletion.delete(editor, pkg)
                check(editor.value.toString() == "社" && editor.nativeDeletes == 1)
                Selection.setSelection(editor.value, 0, 1)
                EditorDeletion.delete(editor, pkg)
                check(editor.value.isEmpty())
            }
            val emoji = Editor("甲😀", false)
            Selection.setSelection(emoji.value, emoji.value.length)
            EditorDeletion.delete(emoji, "test.editor")
            check(emoji.value.toString() == "甲" && emoji.nativeDeletes == 0)
            for (key in listOf(V3Prefs.READINGS, V3Prefs.GLOSS)) {
                val example = SettingExampleView(ctx).apply { choiceOnly = true; submit(key, key, 0, 1, "", "") }
                val density = ctx.resources.displayMetrics.density
                example.measure(View.MeasureSpec.makeMeasureSpec((100*density).toInt(), View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec((120*density).toInt(), View.MeasureSpec.EXACTLY))
                example.layout(0, 0, example.measuredWidth, example.measuredHeight)
                val bitmap = android.graphics.Bitmap.createBitmap(example.width, example.height, android.graphics.Bitmap.Config.ARGB_8888)
                example.draw(android.graphics.Canvas(bitmap))
                java.io.File(ctx.getExternalFilesDir(null), "compact-$key.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
                bitmap.recycle()
            }
        }
        i.sendStatus(0, Bundle().apply { putString("stream", "PROFILES / LAUNCHER DELETION / COMPACT EXAMPLES PASSED\n") })
    }

    fun live(i: Instrumentation) {
        val ctx = i.targetContext
        val prefs = Prefs.of(ctx)
        val before = prefs.all.toMap()
        val automation = i.getUiAutomation(android.app.UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        @Suppress("UNCHECKED_CAST") fun <T> ui(block: () -> T): T {
            var result: Any? = null
            var failure: Throwable? = null
            i.runOnMainSync { try { result = block() } catch (error: Throwable) { failure = error } }
            failure?.let { throw it }
            return result as T
        }
        fun descendants(view: View): List<View> = listOf(view) + if (view is ViewGroup) (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList()
        fun views() = WindowInspector.getGlobalWindowViews().flatMap(::descendants)
        fun keyboard() = views().filterIsInstance<KeyboardView>().firstOrNull { it.isShown && !it.preview }
        fun waitFor(label: String, block: () -> Boolean) {
            val deadline = SystemClock.uptimeMillis() + 15000
            while (SystemClock.uptimeMillis() < deadline) { if (block()) return; Thread.sleep(60) }
            error("Timed out $label")
        }
        try {
            ui {
                prefs.edit().putBoolean(Prefs.ZH, true).putInt(V3Prefs.PINYIN_POSITION, 0).commit()
                prefs.edit().also { V3Prefs.saveFloating(ctx, true, it) }.commit()
            }
            android.os.ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand("am start -W -f 0x10008000 -n com.kongye.keyboard.test/com.kongye.keyboard.TypingFormActivity --ez bottom true --ez show_keyboard true")).use { it.readBytes() }
            waitFor("floating keyboard") { ui { (keyboard()?.listener as? KongyeIME)?.isInputViewShown==true } }
            Thread.sleep(700)
            waitFor("test editor owns IME") {ui {(keyboard()?.listener as? KongyeIME)?.currentInputEditorInfo?.packageName=="com.kongye.keyboard.test"}}
            val ime = ui { keyboard()!!.listener as KongyeIME }
            val columnField = KongyeIME::class.java.getDeclaredField("col").apply { isAccessible = true }
            fun column() = columnField.get(ime) as ViewGroup
            ui {
                ime.onKey(Key(Kind.RESET))
                if(!keyboard()!!.zh)ime.onKey(Key(Kind.LANG))
                if(keyboard()!!.shiftState!=0)ime.onKey(Key(Kind.SHIFT))
                ime.onKey(Key(Kind.LETTER,"g"))
            }
            waitFor("floating spelling inside title") { ui {
                val tab = views().filterIsInstance<PinyinTab>().firstOrNull { it.isShown && it.contentDescription?.toString()=="拼音：g" } ?: return@ui false
                val col = column()
                tab.y >= col.y && tab.y + tab.height <= col.y + col.getChildAt(0).height + 1 && tab.x + tab.width < col.x + col.width/2f
            } }
            val resolved=KongyeIME::class.java.getDeclaredField("resolvedKeys").apply {isAccessible=true}
            val pending=KongyeIME::class.java.getDeclaredField("candidatesPending").apply {isAccessible=true}
            waitFor("floating candidate page") {ui {resolved.get(ime)==listOf("g") && !pending.getBoolean(ime)}}
            ui {
                val tab=views().filterIsInstance<PinyinTab>().first {it.isShown}
                check(tab.elevation>column().elevation) {"Floating spelling is behind the keyboard"}
            }
            Thread.sleep(450)
            automation.takeScreenshot()?.also { bitmap ->
                java.io.File(ctx.getExternalFilesDir(null),"floating-header-400.png").outputStream().use {bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)}
                bitmap.recycle()
            }
            ui {
                val header = column().getChildAt(0)
                val now = SystemClock.uptimeMillis()
                for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
                    val event = MotionEvent.obtain(now, now+20, action, header.width-10f, header.height/2f, 0)
                    header.onTouchEvent(event); event.recycle()
                }
            }
            waitFor("close hides IME") { ui { !ime.isInputViewShown } }
            check(V3Prefs.keyboardForm(ctx) == KeyboardForm.FLOATING)
            ui { ime.requestShowSelf(0) }
            waitFor("reopened floating keyboard") { ui { ime.isInputViewShown && column().getChildAt(0).isShown } }
            ui { ime.onKey(Key(Kind.RESET)); ime.onTool("skin") }
            waitFor("skin panel") { ui { views().filterIsInstance<SkinPanel>().any { it.isShown } } }
            val offset = ui {
                val panel = views().filterIsInstance<SkinPanel>().first { it.isShown }
                val scroll = descendants(panel).filterIsInstance<ScrollView>().first()
                scroll.scrollTo(0, 200)
                scroll.scrollY
            }
            check(offset > 0)
            val originalPanel=ui {views().filterIsInstance<SkinPanel>().first {it.isShown}}
            val originalScroller=ui {descendants(originalPanel).filterIsInstance<ScrollView>().first()}
            val originalGrid=ui {descendants(originalPanel).filterIsInstance<ThemeGridView>().first()}
            ui { prefs.edit().putString(Prefs.THEME, if(prefs.getString(Prefs.THEME,"")=="sky")"dusk" else "sky").commit() }
            waitFor("skin scrolling preserved") { ui { views().filterIsInstance<SkinPanel>().firstOrNull { it.isShown }?.let {descendants(it).filterIsInstance<ScrollView>().first().scrollY} == offset } }
            ui {
                val active=views().filterIsInstance<SkinPanel>().first {it.isShown}
                check(active===originalPanel) {"Theme selection rebuilt the entire panel"}
                check(descendants(active).filterIsInstance<ScrollView>().first()===originalScroller)
                check(descendants(active).filterIsInstance<ThemeGridView>().first()===originalGrid)
                check(originalScroller.scrollY==offset)
                prefs.edit().putInt(Prefs.MODE,1).commit()
                prefs.edit().putBoolean(Prefs.GLASS,!prefs.getBoolean(Prefs.GLASS,false)).commit()
            }
            Thread.sleep(300)
            ui {
                check(views().filterIsInstance<SkinPanel>().first {it.isShown}===originalPanel)
                check(originalScroller.scrollY==offset)
            }
            i.sendStatus(0, Bundle().apply { putString("stream", "FLOATING SPELLING / CLOSE-REOPEN MODE / SKIN SCROLL PASSED\n") })
        } finally {
            ui {
                val edit = prefs.edit().clear()
                for ((key, value) in before) when (value) {
                    is String -> edit.putString(key, value)
                    is Boolean -> edit.putBoolean(key, value)
                    is Int -> edit.putInt(key, value)
                    is Long -> edit.putLong(key, value)
                    is Float -> edit.putFloat(key, value)
                    is Set<*> -> edit.putStringSet(key, value.filterIsInstance<String>().toSet())
                }
                edit.commit()
            }
        }
    }
}
