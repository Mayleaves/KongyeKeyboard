package com.kongye.keyboard

import android.app.Instrumentation
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import android.view.inspector.WindowInspector

/** V3 settings are exercised through the real IME and an isolated input field. */
object V3Regression {
    private fun descendants(v:View):List<View> = listOf(v)+(if(v is ViewGroup)(0 until v.childCount).flatMap {descendants(v.getChildAt(it))} else emptyList())
    fun run(inst:Instrumentation) {
        val ctx=inst.targetContext
        val auto=inst.getUiAutomation(android.app.UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        auto.serviceInfo=auto.serviceInfo.apply {flags=flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS}
        @Suppress("UNCHECKED_CAST") fun <T> ui(block:()->T):T {
            var result:Any?=null;var failure:Throwable?=null
            inst.runOnMainSync {try {result=block()}catch(t:Throwable){failure=t}}
            failure?.let {throw it};return result as T
        }
        fun waitFor(label:String,check:()->Boolean) {val until=SystemClock.uptimeMillis()+30000;while(SystemClock.uptimeMillis()<until){if(check())return;SystemClock.sleep(50)};error("Timed out: $label")}
        fun report(s:String)=inst.sendStatus(0,Bundle().apply {putString("stream",s+"\n")})
        fun nodes(n:AccessibilityNodeInfo?):List<AccessibilityNodeInfo> = if(n==null)emptyList() else listOf(n)+(0 until n.childCount).flatMap {nodes(n.getChild(it))}
        fun editor():AccessibilityNodeInfo? {auto.clearCache();return auto.windows.flatMap {nodes(it.root)}.firstOrNull {it.packageName?.toString()=="com.kongye.keyboard.test" && it.contentDescription?.toString()=="独立输入测试框"}}
        fun keyboard()=WindowInspector.getGlobalWindowViews().flatMap {descendants(it)}.filterIsInstance<KeyboardView>().firstOrNull {it.isShown&&!it.preview}
        val prefs=Prefs.of(ctx);val backup=prefs.all.toMap()
        var ime:KongyeIME?=null
        try {
            Lexicon.load(ctx);FcitxEngine.initialize(ctx)
            ui {prefs.edit().putBoolean(Prefs.ZH,true).putString(Prefs.LAYOUT,"26").putBoolean(Prefs.VAULT,false).putBoolean(Prefs.LEARN,false).putBoolean(V3Prefs.STATS_ENABLED,false)
                .putInt(V3Prefs.PINYIN_POSITION,0).putInt(V3Prefs.GLOSS,2).putBoolean(V3Prefs.VOICE_POLISH,false).putBoolean(V3Prefs.MIXED,false).putBoolean(Prefs.GLIDE,false)
                .putBoolean(V3Prefs.EN_CORRECT,true).putBoolean(V3Prefs.EN_SPACE,true).putBoolean(V3Prefs.CAPS,false).putBoolean(V3Prefs.PUNCT_SPACE,true)
                .putBoolean(V3Prefs.CORRECT_NEAR,true).putBoolean(V3Prefs.CORRECT_SWAP,true).putBoolean(V3Prefs.CORRECT_EXTRA,true).putBoolean(V3Prefs.CORRECT_MISSING,true).commit()}
            check(EnglishInput.candidates(ctx,"helo").any {it.w=="hello"})
            ui {prefs.edit().putBoolean(V3Prefs.CORRECT_SWAP,false).putBoolean(V3Prefs.CORRECT_NEAR,false).putBoolean(V3Prefs.CORRECT_EXTRA,false).putBoolean(V3Prefs.CORRECT_MISSING,false).commit();SpellingAssist.configure(ctx)}
            check(!SpellingAssist.allows("jeude","jue de"))
            check(SpellingAssist.allows("juede","jue de"))
            ui {prefs.edit().putBoolean(V3Prefs.CORRECT_SWAP,true).commit();SpellingAssist.configure(ctx)}
            check(SpellingAssist.allows("jeude","jue de"))
            ui {prefs.edit().putBoolean(V3Prefs.CORRECT_NEAR,true).putBoolean(V3Prefs.CORRECT_EXTRA,true).putBoolean(V3Prefs.CORRECT_MISSING,true).commit();SpellingAssist.configure(ctx)}
            ui {prefs.edit().putBoolean(V3Prefs.VOICE_NUMBERS,true).putBoolean(V3Prefs.VOICE_CLEAN,true).commit()}
            check(VoiceText.process(ctx,"嗯，今天二十三个人。").contains("23个人"))
            check(!VoiceText.process(ctx,"嗯，今天二十三个人。").contains("嗯"))
            check(Layouts.splitFourteen().first().keys.map {it.letters}==listOf("qw","er","t","y","ui","op"))
            report("English correction, independent spelling toggles, voice formatting and split 14-key geometry passed")
            android.os.ParcelFileDescriptor.AutoCloseInputStream(auto.executeShellCommand("am start -W -f 0x10008000 -n com.kongye.keyboard.test/com.kongye.keyboard.TypingFormActivity --ez bottom true")).use {it.readBytes()}
            waitFor("test editor") {editor()!=null}
            editor()!!.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            val bounds=android.graphics.Rect();editor()!!.getBoundsInScreen(bounds)
            val down=SystemClock.uptimeMillis()
            for(action in intArrayOf(0,1)) {
                val e=android.view.MotionEvent.obtain(down,SystemClock.uptimeMillis(),action,bounds.centerX().toFloat(),bounds.centerY().toFloat(),0)
                e.source=android.view.InputDevice.SOURCE_TOUCHSCREEN
                try {check(auto.injectInputEvent(e,true))}finally {e.recycle()}
            }
            waitFor("production IME") {ui {val k=keyboard();val live=k?.listener as? KongyeIME;live!=null&&live.isInputViewShown&&live.currentInputEditorInfo?.packageName=="com.kongye.keyboard.test"}}
            val k=ui {keyboard()!!};val live=ui {k.listener as KongyeIME};ime=live
            fun views()=descendants(k.rootView)
            fun raw()=ui {views().filterIsInstance<PinyinTab>().firstOrNull {it.isShown}?.contentDescription?.toString().orEmpty()}
            fun text()=editor()?.text?.toString().orEmpty().takeUnless {it=="只使用虚构测试内容"}.orEmpty()
            fun clear() {
                ui {live.onKey(Key(Kind.RESET))}
                editor()!!.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT,Bundle().apply {putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,"")})
                SystemClock.sleep(200)
            }
            fun type(word:String) {for(c in word) {ui {live.onKey(Key(Kind.LETTER,c.toString()))};SystemClock.sleep(35)};SystemClock.sleep(700)}
            clear();type("nihao")
            check(raw().startsWith("拼音："));check(text().isEmpty())
            ui {prefs.edit().putInt(V3Prefs.PINYIN_POSITION,1).commit()}
            waitFor("bar spelling") {ui {views().filterIsInstance<CandidateBar>().any {it.isShown&&it.inputSpelling.replace("'","")=="nihao"}}}
            check(raw().isEmpty())
            ui {prefs.edit().putInt(V3Prefs.PINYIN_POSITION,2).commit()}
            waitFor("app composing pinyin") {text().replace("'","")=="nihao"}
            ui {live.onKey(Key(Kind.SPACE))}
            waitFor("Chinese confirmed") {text()=="你好"}
            ui {live.onKey(Key(Kind.DEL))};waitFor("Chinese delete") {text()=="你"}
            report("Three pinyin locations, app composing replacement and ordinary deletion passed")
            clear()
            ui {prefs.edit().putInt(V3Prefs.PINYIN_POSITION,0).putBoolean(V3Prefs.GLIDE_ZH,true).putBoolean(V3Prefs.GLIDE_WORD_DELETE,true).commit()}
            val centers=ui {
                @Suppress("UNCHECKED_CAST") val rows=KeyboardView::class.java.getDeclaredField("rows").apply {isAccessible=true}.get(k) as List<Row>
                rows.flatMap {it.keys}.filter {it.kind==Kind.LETTER}.associate {it.letters[0] to GlidePoint(it.rect.centerX(),it.rect.centerY())}
            }
            val points=ArrayList<GlidePoint>()
            val word="nihao"
            for(index in 0 until word.lastIndex) {
                val from=centers.getValue(word[index]);val to=centers.getValue(word[index+1])
                for(step in 0..10)points.add(GlidePoint(from.x+(to.x-from.x)*step/10,from.y+(to.y-from.y)*step/10))
            }
            ui {live.onGlide(GlideTrace(points,centers,40f))}
            waitFor("Chinese glide spelling") {raw().replace("'","").contains("nihao")}
            SystemClock.sleep(500)
            ui {live.onKey(Key(Kind.DEL))}
            waitFor("delete last Chinese glide syllable") {raw().removePrefix("滑行拼音：").replace("'","")=="ni"}
            check(text().isEmpty())
            report("Chinese glide removed the last character encoding without committing text passed")

            clear();ui {live.onKey(Key(Kind.LANG))};SystemClock.sleep(300)
            type("hello");ui {live.onCandidate(0)}
            waitFor("English word spacing") {text()=="hello "}
            ui {live.onKey(Key(Kind.CHAR,"."))}
            waitFor("English punctuation spacing") {text()=="hello. "}
            check(raw().isEmpty())
            report("Real English candidate selection, automatic spaces and hidden English pinyin passed")
            clear()
            ui { prefs.edit().putBoolean(V3Prefs.CAPS_LOCK,true).commit();live.onKey(Key(Kind.SHIFT)) }
            check(ui {k.shiftState==2}) {"Enabled single-tap did not lock uppercase"}
            type("hello"); ui {live.onCandidate(0)}
            waitFor("locked English word") {text()=="HELLO "}
            check(ui {k.shiftState==2}) {"Word confirmation cleared caps lock"}
            auto.takeScreenshot()?.also {image ->
                java.io.File(ctx.getExternalFilesDir(null),"qa-caps-lock.png").outputStream().use {image.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)}
                image.recycle()
            }
            ui {live.onKey(Key(Kind.NUM)); live.onKey(Key(Kind.ABC))}
            check(ui {k.shiftState==2}) {"Number layer cleared caps lock"}
            ui {live.onKey(Key(Kind.SHIFT))}
            check(ui {k.shiftState==0}) {"Single tap did not unlock"}
            type("world");ui {live.onCandidate(0)}
            waitFor("unlocked English word") {text()=="HELLO world "}
            clear()
            ui {prefs.edit().putBoolean(V3Prefs.CAPS,true).commit();live.onKey(Key(Kind.ABC))}
            check(ui {k.shiftState==1}) {"Test field did not supply sentence capitalization"}
            ui {live.onKey(Key(Kind.SHIFT))}
            check(ui {k.shiftState==2}) {"Auto-capitalized sentence blocked single-tap lock"}
            ui {live.onKey(Key(Kind.SHIFT));prefs.edit().putBoolean(V3Prefs.CAPS,false).commit()}
            ui {live.onLongKey(Key(Kind.SHIFT))}
            check(ui {k.shiftState==2}) {"Long press did not lock uppercase"}
            ui {live.onKey(Key(Kind.SHIFT));live.onKey(Key(Kind.LANG));live.onLongKey(Key(Kind.SHIFT))}
            type("ab")
            waitFor("Chinese keyboard caps input") {text()=="AB"}
            check(ui {k.shiftState==2}) {"Chinese keyboard did not retain uppercase lock"}
            ui {live.onKey(Key(Kind.SHIFT));live.onKey(Key(Kind.LANG))}
            clear()
            ui {prefs.edit().putBoolean(V3Prefs.CAPS_LOCK,false).putBoolean(V3Prefs.CAPS,false).commit();live.onKey(Key(Kind.SHIFT))}
            check(ui {k.shiftState==1}) {"Disabled lock did not enable one-shot uppercase"}
            type("ab");ui {live.onCandidate(0)}
            waitFor("one-shot English uppercase"){text()=="Ab "}
            check(ui {k.shiftState==0}) {"Disabled lock kept uppercase after a letter"}
            ui {live.onKey(Key(Kind.SHIFT));live.onKey(Key(Kind.SHIFT))}
            check(ui {k.shiftState!=2}) {"Double tap bypassed the disabled lock"}
            clear();ui {live.onLongKey(Key(Kind.SHIFT))};type("cd");ui {live.onCandidate(0)}
            waitFor("long press remains one-shot when disabled"){text()=="Cd "}
            report("English caps-lock toggle: enabled single tap locks until manual unlock; disabled returns to lowercase after one letter, including double/long tap; auto capitalization, Chinese behavior and number-layer round trip passed")
            clear();editor()!!.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT,Bundle().apply {putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,"abcXYZ")})
            editor()!!.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION,Bundle().apply {putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT,3);putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT,3)})
            ui {prefs.edit().putInt(V3Prefs.CLEAR_SCOPE,1).commit()};SystemClock.sleep(200)
            ui {live.onSwipeDel(true)};waitFor("clear before cursor only") {text()=="XYZ"}
            report("Clear-before-cursor preserves text after the caret passed")
            clear()
            ui {if(!k.zh)live.onKey(Key(Kind.LANG))}
            SystemClock.sleep(300)
            ui {prefs.edit().putInt(V3Prefs.ONE_HAND,1).commit()}
            SystemClock.sleep(300)
            ui {
                check(k.width<ctx.resources.displayMetrics.widthPixels*.9f) {"One-hand setting did not change the actual keyboard width"}
                prefs.edit().putInt(V3Prefs.ONE_HAND,0).putBoolean(Prefs.SPLIT_LAYOUT,true).putString(Prefs.LAYOUT,"14").commit()
            }
            SystemClock.sleep(300)
            ui {
                val wide=ctx.resources.displayMetrics.widthPixels/ctx.resources.displayMetrics.density>=600
                check(k.splitLayout==wide) {"Split did not follow available screen width"}
                val rows=KeyboardView::class.java.getDeclaredField("rows").apply {isAccessible=true}.get(k) as List<*>
                val first=(rows.first() as Row).keys
                check(first.size==if(wide)6 else 5) {"Split 14-key first row had ${first.size} keys, wide=$wide, zh=${k.zh}"}
                prefs.edit().putBoolean(Prefs.SPLIT_LAYOUT,false).putString(Prefs.LAYOUT,"26").commit()
            }
            report("One-hand width and narrow-screen split fallback passed")
            val manager=ctx.getSystemService(android.media.AudioManager::class.java)
            val losses=java.util.concurrent.CopyOnWriteArrayList<Int>()
            val media=android.media.AudioFocusRequest.Builder(android.media.AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(android.media.AudioAttributes.Builder().setUsage(android.media.AudioAttributes.USAGE_MEDIA).setContentType(android.media.AudioAttributes.CONTENT_TYPE_MUSIC).build())
                .setOnAudioFocusChangeListener {losses.add(it)}.build()
            val controller=VoiceMedia(ctx)
            val supportReady=java.util.concurrent.atomic.AtomicBoolean(false)
            ui {
                prefs.edit().putInt(V3Prefs.MEDIA,0).commit()
                VoiceSupport.start(ctx,onStop={}) {success->check(success);supportReady.set(true)}
            }
            waitFor("foreground microphone support") {supportReady.get()}
            try {
                for(mode in listOf(2,1,0)) {
                    ui {
                        check(manager.requestAudioFocus(media)==android.media.AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
                        losses.clear();prefs.edit().putInt(V3Prefs.MEDIA,mode).commit();controller.acquire()
                    }
                    if(mode==0) {
                        SystemClock.sleep(150);check(losses.isEmpty()) {"Unchanged-media mode took audio focus"}
                    } else {
                        val expected=if(mode==2)android.media.AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK else android.media.AudioManager.AUDIOFOCUS_LOSS_TRANSIENT
                        waitFor("media signal $mode") {expected in losses}
                        ui {controller.release()}
                        waitFor("restore signal $mode") {android.media.AudioManager.AUDIOFOCUS_GAIN in losses}
                    }
                }
            } finally {ui {controller.release();manager.abandonAudioFocusRequest(media);VoiceSupport.stop(ctx)}}
            report("Pause, duck, unchanged-media and restore audio-focus callbacks passed")
            ui {prefs.edit().putBoolean(V3Prefs.DRIVE,true).commit();live.onTool("voice")}
            waitFor("voice board") {ui {views().filterIsInstance<VoiceBoard>().any {it.isShown}}}
            waitFor("ongoing voice notification") {ctx.getSystemService(android.app.NotificationManager::class.java).activeNotifications.any {it.id==300}}
            report("Foreground microphone session and status notification started from the IME passed")
            ui {views().filterIsInstance<android.widget.TextView>().first {it.isShown&&it.text.toString()=="收起"}.performClick()}
            waitFor("compact listening strip") {ui {!k.isShown && views().filterIsInstance<android.widget.TextView>().any {it.isShown&&it.text.toString()=="停止"}}}
            ui {
                val insets=android.inputmethodservice.InputMethodService.Insets()
                live.onComputeInsets(insets)
                check(insets.contentTopInsets>=ctx.resources.displayMetrics.heightPixels-ctx.resources.displayMetrics.density*80) {"Collapsed voice still reserves keyboard height"}
                views().filterIsInstance<android.widget.TextView>().first {it.isShown&&it.text.toString()=="停止"}.performClick()
            }
            waitFor("keyboard restored after stopping") {ui {k.isShown}}
            ui {live.onTool("layout")};SystemClock.sleep(250)
            report("Compact floating listening controls and keyboard restoration passed")
        } finally {
            ui {
                VoiceSupport.stop(ctx)
                ime?.onKey(Key(Kind.RESET))
                val edit=prefs.edit().clear()
                for((key,value) in backup) when(value) {is Boolean->edit.putBoolean(key,value);is Int->edit.putInt(key,value);is Float->edit.putFloat(key,value);is Long->edit.putLong(key,value);is String->edit.putString(key,value);is Set<*>->@Suppress("UNCHECKED_CAST") edit.putStringSet(key,value as Set<String>)}
                edit.commit();SpellingAssist.configure(ctx)
            }
        }
    }
}
