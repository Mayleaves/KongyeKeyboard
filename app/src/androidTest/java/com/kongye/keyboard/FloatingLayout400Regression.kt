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
        fun shell(command:String)=android.os.ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand(command)).use {String(it.readBytes()).trim()}
        val rotation=shell("settings get system user_rotation")
        val accelerometer=shell("settings get system accelerometer_rotation")
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
                prefs.edit().putBoolean(Prefs.ZH, true).putBoolean(Prefs.LEARN,false).putBoolean(Prefs.VAULT,false).putString(Prefs.LAYOUT,"26").putInt(V3Prefs.PINYIN_POSITION, 0).putBoolean(V3Prefs.BOTTOM_CURSOR,true).commit()
                prefs.edit().also { V3Prefs.saveFloating(ctx, true, it) }.commit()
            }
            android.os.ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand("am start -W -f 0x10008000 -n com.kongye.keyboard.test/com.kongye.keyboard.TypingFormActivity --ez bottom true --ez show_keyboard true")).use { it.readBytes() }
            waitFor("floating keyboard") { ui { (keyboard()?.listener as? KongyeIME)?.isInputViewShown==true } }
            Thread.sleep(700)
            waitFor("test editor owns IME") {ui {(keyboard()?.listener as? KongyeIME)?.currentInputEditorInfo?.packageName=="com.kongye.keyboard.test"}}
            val ime = ui { keyboard()!!.listener as KongyeIME }
            val columnField = KongyeIME::class.java.getDeclaredField("col").apply { isAccessible = true }
            fun column() = columnField.get(ime) as ViewGroup
            val d=ctx.resources.displayMetrics.density
            val adaptive=ui {keyboard()!!.heightScale}/FloatingKeyboard.height(ctx)
            fun footer()=views().filterIsInstance<BottomStrip>().first {it.isShown && it.floating}
            fun point(v:View,x:Float,y:Float):Pair<Float,Float> {
                val at=IntArray(2);v.getLocationOnScreen(at);return at[0]+x to at[1]+y
            }
            fun checkGripsFollowKeyboard(label:String) {
                waitFor(label) {ui {
                    val c=column()
                    val frame=views().filterIsInstance<FloatingResizeFrame>().firstOrNull {it.isShown} ?: return@ui false
                    val expected=point(c,-frame.margin,-frame.margin)
                    val actual=point(frame,0f,0f)
                    kotlin.math.abs(actual.first-expected.first)<=1 && kotlin.math.abs(actual.second-expected.second)<=1 &&
                        frame.width==c.width+(2*frame.margin).toInt() && frame.height==c.height+(2*frame.margin).toInt() &&
                        listOf(-3*d,c.width+3*d).all {x->listOf(-3*d,c.height+3*d).all {y->
                            val corner=point(c,x,y);frame.hitScreen(corner.first,corner.second)
                        }}
                }}
            }
            fun gesture(points:List<Pair<Float,Float>>,hold:Long=0,afterHold:(()->Unit)?=null) {
                val time=SystemClock.uptimeMillis()
                points.forEachIndexed {n,(x,y)->
                    val action=if(n==0)MotionEvent.ACTION_DOWN else if(n==points.lastIndex)MotionEvent.ACTION_UP else MotionEvent.ACTION_MOVE
                    val event=MotionEvent.obtain(time,SystemClock.uptimeMillis(),action,x,y,0).apply {source=android.view.InputDevice.SOURCE_TOUCHSCREEN}
                    try {check(automation.injectInputEvent(event,true))}finally {event.recycle()}
                    if(n==0 && hold>0) {Thread.sleep(hold);afterHold?.invoke()}else Thread.sleep(30)
                }
            }
            fun screenshot(name:String) {
                Thread.sleep(150)
                automation.takeScreenshot()?.also {bitmap->java.io.File(ctx.getExternalFilesDir(null),name).outputStream().use {bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)};bitmap.recycle()}
            }
            ui {
                check(column().getChildAt(0).visibility==View.GONE || column().getChildAt(0).height==0) // hidden input strip
                check(footer().height<=42*d && !footer().cursorEnabled)
                check(descendants(column()).none {it.javaClass.simpleName=="FloatBar"})
            }
            screenshot("floating-413-normal.png")
            val original=ui {column().x to column().y}
            var handle=ui {point(footer(),footer().width/2f,footer().height/2f)}
            gesture(listOf(handle,handle))
            check(ui {column().x==original.first && column().y==original.second && footer().floatingAdjusting}) {"A light tap should activate the move/resize state without jumping"}
            handle=ui {point(footer(),footer().width/2f,footer().height/2f)}
            gesture(listOf(handle,handle.first+15*d to handle.second-25*d,handle.first+15*d to handle.second-25*d))
            check(ui {column().y<original.second-10*d && footer().floatingAdjusting}) {"A short drag should move immediately"}
            val afterShort=ui {column().x to column().y}
            handle=ui {point(footer(),footer().width/2f,footer().height/2f)}
            gesture(listOf(handle,handle.first+15*d to handle.second-25*d,handle.first+15*d to handle.second-25*d),android.view.ViewConfiguration.getLongPressTimeout()+120L,afterHold={
                check(ui {
                    val frame=views().filterIsInstance<FloatingResizeFrame>().first {it.isShown}
                    val corner=point(column(),column().width+3*d,column().height+3*d)
                    !frame.hitScreen(corner.first,corner.second)
                }) {"Resize markers should appear on release, not while holding the move bar"}
            })
            waitFor("long hold enters resizing") {ui {footer().floatingAdjusting && views().filterIsInstance<FloatingResizeFrame>().any {it.isShown}}}
            check(ui {column().y<afterShort.second-10*d}) {"Long-press drag did not move"}
            screenshot("floating-413-moving.png")
            val adjustedOrigin=ui {column().x to column().y}
            val adjustedHandle=ui {point(footer(),footer().width/2f,footer().height/2f)}
            gesture(listOf(adjustedHandle,adjustedHandle.first-15*d to adjustedHandle.second+15*d,adjustedHandle.first-15*d to adjustedHandle.second+15*d))
            check(ui {column().x<adjustedOrigin.first-5*d && column().y>adjustedOrigin.second+5*d}) {"Adjustment mode should allow direct dragging"}
            val originalSize=ui {column().width to column().height}
            val blankEdge=ui {val c=column();point(c,c.width+2*d,c.height/2f)}
            gesture(listOf(blankEdge,blankEdge.first+15*d to blankEdge.second,blankEdge.first+15*d to blankEdge.second))
            check(ui {column().width==originalSize.first && column().height==originalSize.second}) {"An unmarked edge resized the keyboard"}
            val reopenHandle=ui {point(footer(),footer().width/2f,footer().height/2f)}
            gesture(listOf(reopenHandle,reopenHandle),android.view.ViewConfiguration.getLongPressTimeout()+120L)
            waitFor("corner grips after release") {ui {footer().floatingAdjusting}}
            val beforeWidth=ui {column().width}
            val edge=ui {val c=column();point(c,c.width+3*d,c.height+3*d)}
            gesture(listOf(edge,edge.first-30*d to edge.second-15*d,edge.first-30*d to edge.second-15*d))
            waitFor("resize changes width") {ui {column().width<beforeWidth-20*d}}
            checkGripsFollowKeyboard("all four grips follow resized keyboard")
            screenshot("floating-413-resized.png")
            val beforeResetPosition=ui {column().x to column().y}
            val reset=ui {point(footer(),footer().width-30*d,footer().height/2f)}
            gesture(listOf(reset,reset))
            waitFor("reset restores default dimensions") {ui {kotlin.math.abs(column().width-ctx.resources.displayMetrics.widthPixels*FloatingKeyboard.defaultWidth(ctx.resources.displayMetrics.widthPixels.toFloat(),ctx.resources.displayMetrics.heightPixels.toFloat()))<3 && kotlin.math.abs(keyboard()!!.heightScale-adaptive)<.02f}}
            check(ui {kotlin.math.abs(column().x-beforeResetPosition.first)<1 && kotlin.math.abs(column().y-beforeResetPosition.second)<1}) {"Size reset also moved the keyboard"}
            checkGripsFollowKeyboard("all four grips follow reset dimensions")
            screenshot("floating-417-reset.png")
            // Translation adds a row above the toolbar, as in the reported failure.
            ui {ime.onTool("translate")}
            waitFor("translation row visible") {ui {column().getChildAt(0).isShown && column().getChildAt(0).height>0}}
            Thread.sleep(350)
            checkGripsFollowKeyboard("grips follow added translation row")
            val translatedWidth=ui {column().width}
            val translatedCorner=ui {val c=column();point(c,c.width+3*d,c.height+3*d)}
            gesture(listOf(translatedCorner,translatedCorner.first-24*d to translatedCorner.second-12*d,translatedCorner.first-24*d to translatedCorner.second-12*d))
            waitFor("resizing still works after reset") {ui {column().width<translatedWidth-15*d}}
            val translatedPosition=ui {column().x to column().y}
            val translatedReset=ui {point(footer(),footer().width-30*d,footer().height/2f)}
            gesture(listOf(translatedReset,translatedReset))
            waitFor("translation reset restores width") {ui {kotlin.math.abs(column().width-translatedWidth)<3}}
            checkGripsFollowKeyboard("translation reset aligns all four grips")
            check(ui {kotlin.math.abs(column().x-translatedPosition.first)<1 && kotlin.math.abs(column().y-translatedPosition.second)<1}) {"Translation reset moved the keyboard"}
            screenshot("floating-417-translation-reset.png")
            ui {ime.onTool("translate")}
            waitFor("translation row removed") {ui {column().getChildAt(0).visibility==View.GONE}}
            checkGripsFollowKeyboard("grips follow removed translation row")
            check(ui {footer().floatingAdjusting && ime.isInputViewShown})
            waitFor("idle frame disappears") {ui {!footer().floatingAdjusting && views().filterIsInstance<FloatingResizeFrame>().none {it.isShown}}}
            screenshot("floating-413-idle.png")
            val clip=ui {point(footer(),footer().width-30*d,footer().height/2f)}
            gesture(listOf(clip,clip))
            waitFor("footer clipboard works") {ui {views().filterIsInstance<ClipboardPanel>().any {it.isShown}}}
            val closeClip=ui {point(footer(),footer().width-30*d,footer().height/2f)}
            gesture(listOf(closeClip,closeClip))
            waitFor("keyboard after clipboard") {ui {keyboard()!=null}}
            ui {
                ime.onKey(Key(Kind.RESET))
                if(!keyboard()!!.zh)ime.onKey(Key(Kind.LANG))
                if(keyboard()!!.shiftState!=0)ime.onKey(Key(Kind.SHIFT))
                ime.onKey(Key(Kind.LETTER,"g"))
            }
            waitFor("floating spelling above toolbar") { ui {
                val tab = views().filterIsInstance<PinyinTab>().firstOrNull { it.isShown && it.contentDescription?.toString()=="拼音：g" } ?: return@ui false
                val col = column()
                tab.y >= 0 && tab.y + tab.height <= col.y + 2 && tab.x + tab.width < col.x + col.width/2f
            } }
            val resolved=KongyeIME::class.java.getDeclaredField("resolvedKeys").apply {isAccessible=true}
            val pending=KongyeIME::class.java.getDeclaredField("candidatesPending").apply {isAccessible=true}
            waitFor("floating candidate page") {ui {resolved.get(ime)==listOf("g") && !pending.getBoolean(ime)}}
            ui {
                val tab=views().filterIsInstance<PinyinTab>().first {it.isShown}
                val col=column()
                check(tab.elevation>col.elevation) {"Floating spelling is behind the keyboard"}
                check(tab.x>=col.x+18*d) {"Floating spelling overhangs rounded top-left corner: tab.x=${tab.x} col.x=${col.x}"}
            }
            Thread.sleep(450)
            automation.takeScreenshot()?.also { bitmap ->
                java.io.File(ctx.getExternalFilesDir(null),"floating-header-400.png").outputStream().use {bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)}
                bitmap.recycle()
            }
            val close=ui {point(footer(),30*d,footer().height/2f)}
            gesture(listOf(close,close))
            waitFor("close hides IME") { ui { !ime.isInputViewShown } }
            check(V3Prefs.keyboardForm(ctx) == KeyboardForm.FLOATING)
            ui { ime.requestShowSelf(0) }
            waitFor("reopened floating keyboard") { ui { ime.isInputViewShown && footer().isShown } }
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
            ui {
                ime.onTool(if("skin" in Prefs.tools(ctx))"skin" else "more")
                V3Prefs.setSeparateLayouts(ctx,true)
                prefs.edit().putFloat(FloatingKeyboard.LAND_WIDTH,.36f).putBoolean(V3Prefs.LANDSCAPE_FLOAT,true).commit()
            }
            waitFor("keyboard before rotation") {ui {keyboard()!=null}}
            val savedPortrait=ui {listOf(Prefs.FLOAT_WIDTH,Prefs.FLOAT_X,Prefs.FLOAT_Y,FloatingKeyboard.HEIGHT).associateWith {prefs.all[it]}}
            val rotateHandle=ui {point(footer(),footer().width/2f,footer().height/2f)}
            gesture(listOf(rotateHandle,rotateHandle),android.view.ViewConfiguration.getLongPressTimeout()+120L)
            waitFor("frame before rotation") {ui {footer().floatingAdjusting}}
            shell("settings put system accelerometer_rotation 0");shell("settings put system user_rotation 1")
            Thread.sleep(600);ui {ime.requestShowSelf(0)}
            waitFor("rotation clears temporary adjustment") {ui {
                keyboard()?.resources?.configuration?.orientation==Configuration.ORIENTATION_LANDSCAPE &&
                    footer().isShown && !footer().floatingAdjusting && views().filterIsInstance<FloatingResizeFrame>().none {it.isShown}
            }}
            check(ui {prefs.getFloat(FloatingKeyboard.LAND_WIDTH,0f)==.36f && savedPortrait.all {(key,value)->prefs.all[key]==value}}) {"Rotation wrote the draft into another profile"}
            screenshot("floating-413-landscape.png")
            i.sendStatus(0, Bundle().apply { putString("stream", "FLOATING 4.1.7 PASSED: headerless layout, tap activation, immediate short/long dragging in both states, corner-only resizing, all four grips track resize/reset and translation row changes, size-only reset, idle exit, footer clipboard/hide, spelling placement, reopen mode, skin scroll and rotation during adjustment\n") })
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
            shell("settings put system user_rotation $rotation")
            shell("settings put system accelerometer_rotation $accelerometer")
        }
    }
}
