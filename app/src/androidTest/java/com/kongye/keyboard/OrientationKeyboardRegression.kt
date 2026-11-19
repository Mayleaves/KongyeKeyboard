package com.kongye.keyboard

import android.app.Instrumentation
import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.content.res.Configuration
import android.os.Bundle
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import android.view.inspector.WindowInspector
import android.widget.FrameLayout

object OrientationKeyboardRegression {
    fun run(i: Instrumentation) {
        check(android.os.Build.MODEL.startsWith("sdk_")) { "Run this check on the emulator." }
        val ctx = i.targetContext
        val prefs = Prefs.of(ctx)
        val before = prefs.all.toMap()
        val automation = i.getUiAutomation(android.app.UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        automation.serviceInfo = automation.serviceInfo.apply {
            flags = flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        }
        fun shell(command: String): String = android.os.ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand(command)).use {
            it.readBytes().decodeToString().trim()
        }
        val rotation = shell("settings get system user_rotation")
        val accelerometer = shell("settings get system accelerometer_rotation")
        fun report(text: String) = i.sendStatus(0, Bundle().apply { putString("stream", "$text\n") })
        fun ui(block: () -> Unit) = i.runOnMainSync { block() }
        fun descendants(view: View): List<View> = listOf(view) +
            if (view is ViewGroup) (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList()
        fun keyboard(): KeyboardView? = WindowInspector.getGlobalWindowViews().flatMap(::descendants)
            .filterIsInstance<KeyboardView>().firstOrNull { it.isShown && !it.preview }
        fun nodes(node: AccessibilityNodeInfo?): List<AccessibilityNodeInfo> =
            if (node == null) emptyList() else listOf(node) + (0 until node.childCount).flatMap { nodes(node.getChild(it)) }
        fun controls(): List<AccessibilityNodeInfo> {
            automation.clearCache()
            return automation.windows.flatMap { nodes(it.root) }
        }
        fun click(label: String) {
            val target = controls().first { it.text?.toString() == label || it.contentDescription?.toString() == label }
            var node: AccessibilityNodeInfo? = target
            repeat(6) {
                if (node?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true) return
                node = node?.parent
            }
            error("Cannot click $label")
        }
        var ime: KongyeIME? = null
        fun waitFor(label: String, check: () -> Boolean) {
            val end = SystemClock.uptimeMillis() + 20000
            while (SystemClock.uptimeMillis() < end) {
                if (check()) return
                SystemClock.sleep(80)
            }
            ui {
                report("DEBUG $label: windows=" + WindowInspector.getGlobalWindowViews().joinToString { root ->
                    root.javaClass.name + " views=" + descendants(root).filter { it is KeyboardView || it is android.widget.EditText }.joinToString {
                        it.javaClass.name + " shown=" + it.isShown + if (it is KeyboardView) " preview=" + it.preview + " listener=" + it.listener?.javaClass?.name else ""
                    }
                })
                report("DEBUG service=" + ime + " shown=" + ime?.isInputViewShown + " editor=" + ime?.currentInputEditorInfo?.packageName + " target=" + ctx.packageName)
            }
            runCatching { automation.takeScreenshot()?.let { b -> java.io.File(ctx.getExternalFilesDir(null), "orientation-failure.png").outputStream().use { b.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it) }; b.recycle() } }
            error("Timed out: $label")
        }
        fun showEditor() {
            shell("am start -W -f 0x10008000 -n com.kongye.keyboard/.SettingsActivity")
            waitFor("settings home") { controls().any { it.text?.toString() == "显示键盘" } }
            click("显示键盘")
            waitFor("production IME") {
                var shown = false
                ui {
                    ime = keyboard()?.listener as? KongyeIME
                    shown = ime?.isInputViewShown == true && ime?.currentInputEditorInfo?.packageName == ctx.packageName
                }
                shown
            }
        }
        fun matches(form: KeyboardForm, split: Boolean): Boolean {
            var matched = false
            ui {
                val board = keyboard() ?: return@ui
                val col = KongyeIME::class.java.getDeclaredField("col").apply { isAccessible = true }.get(ime) as View
                val gravity = (col.layoutParams as FrameLayout.LayoutParams).gravity and Gravity.VERTICAL_GRAVITY_MASK
                matched = (gravity == Gravity.TOP) == (form == KeyboardForm.FLOATING) && board.splitLayout == split && (form != KeyboardForm.SPLIT || col.layoutParams.width == -1)
            }
            return matched
        }
        fun rotate(value: Int) {
            shell("settings put system user_rotation $value")
            val expected = if (value == 0) Configuration.ORIENTATION_PORTRAIT else Configuration.ORIENTATION_LANDSCAPE
            SystemClock.sleep(700)
            showEditor()
            waitFor("rotation $value") {
                var correct = false
                ui { correct = keyboard()?.resources?.configuration?.orientation == expected }
                correct
            }
        }
        fun isolated(orientation: Int): Context = object : ContextWrapper(ctx.createConfigurationContext(Configuration(ctx.resources.configuration).apply { this.orientation = orientation })) {
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
                ctx.getSharedPreferences("orientation-fixture-$name", mode)
        }
        val portrait = isolated(Configuration.ORIENTATION_PORTRAIT)
        val landscape = isolated(Configuration.ORIENTATION_LANDSCAPE)
        val fixture = Prefs.of(portrait)
        try {
            fixture.edit().clear().putString(Prefs.LAYOUT, "26").putBoolean(Prefs.FLOAT, true).putBoolean(Prefs.SPLIT_LAYOUT, true)
                .putBoolean(V3Prefs.ORIENTATION_LAYOUT, true).putBoolean("v3_remote_float", true).commit()
            V3Prefs.migrate(portrait)
            check(!fixture.contains("v3_remote_float"))
            check(V3Prefs.keyboardForm(landscape) == KeyboardForm.FLOATING)
            V3Prefs.saveLayout(portrait, "14")
            V3Prefs.saveKeyboardForm(portrait, false, KeyboardForm.NORMAL)
            check(V3Prefs.layout(landscape) == "26" && V3Prefs.keyboardForm(landscape) == KeyboardForm.FLOATING)
            check(V3Prefs.layout(portrait) == "14" && V3Prefs.keyboardForm(portrait) == KeyboardForm.NORMAL)
            val edit = fixture.edit()
            V3Prefs.saveFloating(landscape, false, edit)
            FloatingKeyboard.save(landscape, FloatingKeyboard.newAdjustment(landscape, false).apply { split = true }, edit)
            edit.commit()
            check(V3Prefs.keyboardForm(landscape) == KeyboardForm.SPLIT && V3Prefs.keyboardForm(portrait) == KeyboardForm.NORMAL)
            V3Prefs.setSeparateLayouts(portrait, false)
            check(V3Prefs.keyboardForm(landscape) == KeyboardForm.NORMAL && V3Prefs.layout(landscape) == "14")
            V3Prefs.setSeparateLayouts(portrait, true)
            check(V3Prefs.keyboardForm(landscape) == KeyboardForm.SPLIT && V3Prefs.layout(landscape) == "26")
            report("PASS: legacy migration, independent orientation edits, shared mode and orientation-specific adjustment save")

            shell("settings put system accelerometer_rotation 0")
            shell("settings put system user_rotation 0")
            ui {
                prefs.edit().putBoolean(V3Prefs.ORIENTATION_LAYOUT, true)
                    .putString(Prefs.LAYOUT, "26").putString(V3Prefs.LAYOUT_LANDSCAPE, "14")
                    .putBoolean(Prefs.FLOAT, false).putBoolean(Prefs.SPLIT_LAYOUT, false)
                    .putBoolean(V3Prefs.LANDSCAPE_FLOAT, true).putBoolean(V3Prefs.LANDSCAPE_SPLIT, false)
                    .putBoolean(FloatingKeyboard.SPLIT, false).putInt(V3Prefs.ONE_HAND, 0)
                    .putInt(Prefs.PAD_L, 0).putInt(Prefs.PAD_R, 0).putBoolean(Prefs.ZH, true).commit()
            }
            showEditor()
            report("PASS: portrait editor shown")
            waitFor("portrait normal") { matches(KeyboardForm.NORMAL, false) }
            rotate(1)
            waitFor("landscape floating") { matches(KeyboardForm.FLOATING, false) }
            ui { check(Prefs.layout(ime!!) == "14"); ime!!.onTool("float") }
            waitFor("landscape floating off") { matches(KeyboardForm.NORMAL, false) }
            check(!prefs.getBoolean(V3Prefs.LANDSCAPE_FLOAT, true) && !prefs.getBoolean(Prefs.FLOAT, true))
            ui { ime!!.onTool("float") }
            waitFor("landscape floating restored") { matches(KeyboardForm.FLOATING, false) }
            rotate(0)
            waitFor("portrait preserved") { matches(KeyboardForm.NORMAL, false) }
            ui { prefs.edit().putInt(V3Prefs.ONE_HAND,1).commit(); V3Prefs.saveKeyboardForm(ime!!, false, KeyboardForm.SPLIT) }
            waitFor("portrait split") { matches(KeyboardForm.SPLIT, true) }
            ui { V3Prefs.saveLayout(ime!!, "9") }
            waitFor("nine-key fallback") { matches(KeyboardForm.SPLIT, false) }
            check(!prefs.getBoolean(Prefs.FLOAT, true) && prefs.getBoolean(Prefs.SPLIT_LAYOUT, false))
            ui {
                V3Prefs.saveLayout(ime!!, "26")
                V3Prefs.saveKeyboardForm(ime!!, false, KeyboardForm.NORMAL)
                V3Prefs.saveKeyboardForm(ime!!, true, KeyboardForm.SPLIT)
            }
            rotate(1)
            waitFor("landscape split") { matches(KeyboardForm.SPLIT, true) }
            ui { V3Prefs.setSeparateLayouts(ime!!, false) }
            waitFor("landscape shared normal") { matches(KeyboardForm.NORMAL, false) }
            ui { V3Prefs.setSeparateLayouts(ime!!, true) }
            waitFor("landscape separate split restored") { matches(KeyboardForm.SPLIT, true) }
            report("PASS: real IME rotation, toolbar floating toggle, portrait/landscape split, nine-key fallback and shared-mode switch")
            report("ORIENTATION KEYBOARD PASSED")
        } finally {
            fixture.edit().clear().commit()
            ui {
                val edit = prefs.edit().clear()
                before.forEach { (key, value) ->
                    when (value) {
                        is Boolean -> edit.putBoolean(key, value)
                        is Int -> edit.putInt(key, value)
                        is Long -> edit.putLong(key, value)
                        is Float -> edit.putFloat(key, value)
                        is String -> edit.putString(key, value)
                        is Set<*> -> @Suppress("UNCHECKED_CAST") edit.putStringSet(key, value as Set<String>)
                    }
                }
                edit.commit()
            }
            shell("settings put system user_rotation $rotation")
            shell("settings put system accelerometer_rotation $accelerometer")
        }
    }
}
