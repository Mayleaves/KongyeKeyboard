package com.kongye.keyboard

import android.app.Instrumentation
import android.os.Bundle
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import android.view.inspector.WindowInspector
import java.io.File

/** UI behavior and pictures are checked through the production settings/IME.
 * Only the temporary statistics singleton receives the synthetic text. */
object SettingsNoticeRegression {
    @JvmOverloads fun run(i:Instrumentation,numericOnly:Boolean=false) {
        val ctx=i.targetContext
        val auto=i.getUiAutomation(android.app.UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        auto.serviceInfo=auto.serviceInfo.apply {flags=flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS}
        fun report(t:String)=i.sendStatus(0,Bundle().apply {putString("stream",t+"\n")})
        fun nodes(n:AccessibilityNodeInfo?):List<AccessibilityNodeInfo> = if(n==null)emptyList() else listOf(n)+(0 until n.childCount).flatMap {nodes(n.getChild(it))}
        fun all():List<AccessibilityNodeInfo>{auto.clearCache();return auto.windows.flatMap {nodes(it.root)}}
        fun waitFor(name:String,check:()->Boolean){val end=SystemClock.uptimeMillis()+20000;while(SystemClock.uptimeMillis()<end){if(check())return;SystemClock.sleep(70)};error("Timed out: $name")}
        fun has(t:String)=all().any {it.text?.toString()==t || it.contentDescription?.toString()==t}
        fun click(t:String,scroll:Boolean=true) {
            var n:AccessibilityNodeInfo?=null
            for(attempt in 0 until if(scroll)12 else 1) {
                n=all().firstOrNull {
                    val b=android.graphics.Rect();it.getBoundsInScreen(b)
                    (it.text?.toString()==t||it.contentDescription?.toString()==t)&&it.isVisibleToUser&&b.width()>0&&b.height()>0
                }
                if(n!=null)break
                all().firstOrNull {it.isScrollable}?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
                SystemClock.sleep(170)
            }
            val node=checkNotNull(n){"Missing $t"}
            node.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SHOW_ON_SCREEN.id)
            SystemClock.sleep(100)
            var action:AccessibilityNodeInfo?=node
            repeat(5) {
                if(action?.performAction(AccessibilityNodeInfo.ACTION_CLICK)==true){SystemClock.sleep(250);return}
                action=action?.parent
            }
            val r=android.graphics.Rect();node.getBoundsInScreen(r)
            val now=SystemClock.uptimeMillis()
            for(a in intArrayOf(0,1)){val e=android.view.MotionEvent.obtain(now,SystemClock.uptimeMillis(),a,r.exactCenterX(),r.exactCenterY(),0);e.source=android.view.InputDevice.SOURCE_TOUCHSCREEN;try{check(auto.injectInputEvent(e,true))}finally{e.recycle()}}
            SystemClock.sleep(240)
        }
        @Suppress("UNCHECKED_CAST") fun <T> ui(block:()->T):T{var value:Any?=null;var error:Throwable?=null;i.runOnMainSync {try{value=block()}catch(t:Throwable){error=t}};error?.let {throw it};return value as T}
        fun descendants(v:View):List<View> = listOf(v)+(if(v is ViewGroup)(0 until v.childCount).flatMap {descendants(v.getChildAt(it))} else emptyList())
        fun views()=WindowInspector.getGlobalWindowViews().flatMap {descendants(it)}
        fun keyboard()=views().filterIsInstance<KeyboardView>().firstOrNull {it.isShown&&!it.preview}
        fun editor()=views().filterIsInstance<android.widget.EditText>().firstOrNull {it.isShown&&it.contentDescription=="键盘效果输入框"}
        val p=Prefs.of(ctx);val backup=p.all.toMap()
        fun start() {
            android.os.ParcelFileDescriptor.AutoCloseInputStream(auto.executeShellCommand("am start -W -f 0x10008000 -n com.kongye.keyboard/.SettingsActivity")).use {it.readBytes()}
            waitFor("home"){has("今日输入")};SystemClock.sleep(300)
        }
        fun range(max:Float):AccessibilityNodeInfo {
            var node:AccessibilityNodeInfo?=null
            waitFor("slider $max") {node=all().firstOrNull {it.rangeInfo?.max==max};node!=null}
            node!!.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SHOW_ON_SCREEN.id);SystemClock.sleep(250)
            return all().first {it.rangeInfo?.max==max}
        }
        fun progress(max:Float,value:Float) {
            check(range(max).performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS.id,Bundle().apply {putFloat(AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE,value)}))
            waitFor("slider value $value"){all().any {it.rangeInfo?.max==max&&kotlin.math.abs((it.rangeInfo?.current ?: -100f)-value)<.1f}}
        }
        fun conflictLink(word:String) {
            val node=all().first {it.text?.toString()?.startsWith("上下滑与滑行已同时开启")==true}
            node.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SHOW_ON_SCREEN.id);SystemClock.sleep(200)
            val bounds=android.graphics.Rect();node.getBoundsInScreen(bounds)
            val textSize=12*ctx.resources.displayMetrics.scaledDensity
            val x=bounds.left+(if(word=="滑行")5f else 1.5f)*textSize
            val y=bounds.top+9*ctx.resources.displayMetrics.density
            val now=SystemClock.uptimeMillis()
            for(action in intArrayOf(0,1)){val event=android.view.MotionEvent.obtain(now,SystemClock.uptimeMillis(),action,x,y,0);event.source=android.view.InputDevice.SOURCE_TOUCHSCREEN;try{check(auto.injectInputEvent(event,true))}finally{event.recycle()}}
            SystemClock.sleep(300)
        }
        val pictures=File(ctx.getExternalFilesDir(null),"example-refinements-pictures").apply {mkdirs()}
        fun shot(name:String){auto.takeScreenshot().let {b->File(pictures,name+".png").outputStream().use {b.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)};b.recycle()}}
        fun back(){auto.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK);SystemClock.sleep(250)}
        fun focused(key: String, contains: String? = null): Boolean {
            val items = all()
            val target = items.firstOrNull { it.stateDescription?.toString() == "正在定位：$key" } ?: return false
            if (contains == null) return true
            val control = items.firstOrNull { it.text?.toString() == contains } ?: return false
            val cardBounds = android.graphics.Rect().also { target.getBoundsInScreen(it) }
            val controlBounds = android.graphics.Rect().also { control.getBoundsInScreen(it) }
            return !controlBounds.isEmpty && cardBounds.contains(controlBounds)
        }
        fun link(prefix:String,word:String) {
            val node=all().first {it.text?.toString()?.startsWith(prefix)==true}
            node.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SHOW_ON_SCREEN.id);SystemClock.sleep(180)
            val rect=android.graphics.Rect();node.getBoundsInScreen(rect)
            val value=node.text.toString();val at=value.indexOf(word);check(at>=0)
            val paint=android.text.TextPaint().apply {textSize=12*ctx.resources.displayMetrics.scaledDensity;typeface=android.graphics.Typeface.DEFAULT}
            val layout=android.text.StaticLayout.Builder.obtain(value,0,value.length,paint,rect.width()).setIncludePad(false).build()
            val line=layout.getLineForOffset(at)
            val x=rect.left+(layout.getPrimaryHorizontal(at)+layout.getPrimaryHorizontal(at+word.length))/2
            val y=rect.top+(line+.5f)*18*ctx.resources.displayMetrics.scaledDensity
            val down=SystemClock.uptimeMillis()
            for(action in intArrayOf(0,1)){val e=android.view.MotionEvent.obtain(down,SystemClock.uptimeMillis(),action,x,y,0);e.source=android.view.InputDevice.SOURCE_TOUCHSCREEN;try {check(auto.injectInputEvent(e,true))}finally {e.recycle()}}
        }
        try {
            if(!numericOnly) {
            ui {p.edit().putString(Prefs.LAYOUT,"26").putBoolean(Prefs.SWIPE,true).putBoolean(V3Prefs.GLIDE_ZH,true).putBoolean(V3Prefs.GLIDE_EN,true).putBoolean(Prefs.SOUND,false).putBoolean(V3Prefs.CORRECTION_ENABLED,false).putBoolean(V3Prefs.FUZZY_ENABLED,false).commit()}
            start()
            val search = all().first { it.isEditable }
            check(search.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "字期间")
            }))
            waitFor("arbitrary substring search") { has("组字期间") && all().any { it.text?.toString()?.endsWith("→ 删除键") == true } }
            shot("19-search-compact-results")
            click("组字期间");click("组字期间")
            report("Home search finds a non-prefix substring of a settings section and opens its destination")
            start();click("输入设置");click("滑行输入")
            repeat(4) {
                conflictLink("上下滑");waitFor("whole flick card"){focused(Prefs.SWIPE, "反转上下滑方向")}
                conflictLink("滑行");waitFor("glide target"){focused(V3Prefs.GLIDE_ZH)}
            }
            shot("20-conflict-link-highlight")
            back();waitFor("recent flick page"){has("启用上下滑")}
            back();waitFor("unique navigation root"){has("按键与手势")&&!has("启用上下滑")&&!has("中文滑行")}
            check(!has("中文滑行"))
            report("Eight reciprocal warning jumps highlight their targets; two Back presses return through the previous page to the original menu")
            click("按键与手势")
            check(has("按键反馈"));shot("21-keys-feedback-group")
            link("请先开启「按键音","按键音");waitFor("same page audio focus"){focused(Prefs.SOUND)};shot("22-sound-local-highlight")
            click("长按字母");check(has("字母键"));shot("23-letter-popup-options")
            click("空格长按");check(has("空格与光标"));shot("24-space-long-options")
            click("删除键");check(has("滑动动作"));shot("25-delete-groups")
            click("下滑");waitFor("dropdown"){has("下滑 下拉菜单")};check(!has("取消"));shot("26-delete-dropdown");click("右移光标");waitFor("selection persisted"){p.getString(V3Prefs.DEL_DOWN,"")=="right"};check(!has("下滑 下拉菜单"))
            click("重置")
            waitFor("all four slide actions reset") { listOf(V3Prefs.DEL_UP, V3Prefs.DEL_DOWN, V3Prefs.DEL_LEFT, V3Prefs.DEL_RIGHT).none { p.contains(it) } }
            check(has("撤回") && has("清空"))
            click("退格还原原始拼音")
            shot("27-compose-restore-comparison")
            click("长按清空整段拼音")
            check(ui { views().filterIsInstance<SettingExampleView>().count { it.key in listOf(V3Prefs.COMPOSE_RESTORE, V3Prefs.COMPOSE_HOLD_CLEAR) } == 2 })
            shot("27-compose-hold-comparison")
            report("Full-card focus, anchored dropdown, four-action reset, and two composing comparisons verified")
            start();click("输入设置");click("中文与拼音");click("拼音纠错")
            check(!has("启用拼音纠错"));check(has("邻键纠错"))
            link("请返回「中文与拼音","拼音纠错");waitFor("master entry focus"){focused(V3Prefs.CORRECTION_ENABLED)};shot("28-correction-entry-focus")
            click("模糊音");check(!has("启用模糊音"));check(has("近似读音匹配"));shot("29-fuzzy-without-master")
            start();click("输入设置");click("中文与拼音");click("记住选词")
            check(ui {views().filterIsInstance<SettingExampleView>().none {it.key==Prefs.LEARN}})
            shot("30-pinyin-learning-no-example")
            start();click("输入设置");click("语音输入")
            check(has("模型与语言"));shot("31-voice-language")
            click("外放音处理");check(has("播放与连续听写"));shot("32-voice-playback")
            click("转写整理");shot("33-voice-cleanup")
            click("语句润色");check(has("润色方式"))
            report("Duplicate correction/fuzzy master switches removed, learning example absent, voice settings grouped with polishing last")
            ui {p.edit().putString(Prefs.LAYOUT,"9").commit()}
            start();click("输入设置");click("滑行输入")
            link("中文滑行仅支持","键盘布局");waitFor("layout focus"){focused(Prefs.LAYOUT)};shot("34-layout-prerequisite-focus")
            report("Cross-page blocked layout prerequisite locates and highlights the actual layout picker")
            start();click("工具");click("短信验证码")
            click("自动复制设置流程")
            waitFor("embedded SMS guide") { ui { views().any { it is SmsSetupGuideView } } }
            val guide = ui { views().filterIsInstance<SmsSetupGuideView>().first() }
            fun html(expression: String): String {
                val done = java.util.concurrent.CountDownLatch(1)
                var result: String? = null
                ui { guide.evaluateJavascript(expression) { result = it; done.countDown() } }
                check(done.await(5, java.util.concurrent.TimeUnit.SECONDS))
                return result.orEmpty()
            }
            val preview = android.graphics.Rect()
            waitFor("pure SVG guide ready") {html("document.querySelector('svg') !== null && typeof render === 'function'") == "true"}
            check(html("document.querySelector('video, img, image, button, .progress, .step, #count') === null") == "true")
            html("pause();render(100)")
            check(html("Number(document.querySelector('#home-ripple').getAttribute('opacity')) === 0") == "true")
            html("render(480)")
            check(html("Number(document.querySelector('#home-ripple').getAttribute('opacity')) > 0 && Number(document.querySelector('#home-press').getAttribute('opacity')) > 0") == "true")
            html("render(900)")
            check(html("Number(document.querySelector('#home-ripple').getAttribute('opacity')) === 0") == "true")
            html("render(2400)")
            check(html("Number(document.querySelector('#more').getAttribute('opacity')) > 0 && Number(document.querySelector('#more').getAttribute('opacity')) < 1") == "true")
            html("render(2700)")
            check(html("Number(document.querySelector('#more').getAttribute('opacity')) === 1") == "true")
            html("render(5480)")
            check(html("document.querySelector('#service-press').getAttribute('width') === '624' && Number(document.querySelector('#service-press').getAttribute('opacity')) > 0") == "true")
            check(html("Number(document.querySelector('#switch-track').getAttribute('y')) + Number(document.querySelector('#switch-track').getAttribute('height')) / 2 === Number(document.querySelector('#switch-knob').getAttribute('cy'))") == "true")
            check(html("document.querySelector('#sms-icon rect').getAttribute('width') === '120' && document.querySelectorAll('#sms-icon path').length === 2 && document.querySelector('#sms-icon circle') === null") == "true")
            html("render(7400)")
            val before=html("document.querySelector('#switch-knob').getAttribute('cx')")
            html("render(7800)")
            check(before!=html("document.querySelector('#switch-knob').getAttribute('cx')"))
            for((step,time) in listOf(480,2330,3570,5480,7370).withIndex()) {
                html("render($time)")
                SystemClock.sleep(500)
                ui {guide.getGlobalVisibleRect(preview)}
                if(preview.height()>0)shot("sms-animation-$step")
            }
            val beforeSlide=html("document.querySelector('#home').getAttribute('transform')")
            html("render(1200)")
            check(beforeSlide!=html("document.querySelector('#home').getAttribute('transform')"))
            html("resume()")
            waitFor("SVG animation running") {html("running && position > 1200") == "true"}
            report("Pure SVG walkthrough renders five vector stages, sliding pages, hand taps, and moving switch; no video, bitmap or playback controls")
            start();click("工具");click("备份与恢复")
            val beforeCancel=p.all.toMap()
            click("重置全部设置")
            check(has("恢复默认设置？")&&has("恢复默认"))
            shot("factory-reset-confirmation")
            click("取消")
            check(!has("恢复默认设置？"))
            check(p.all==beforeCancel)
            report("Factory reset is available under backup and restore; confirmation describes retained data; Cancel keeps the configuration intact")
            start();click("工具");click("密码填充")
            check(has("开始使用")&&has("更多信息"))
            check(!has("测试 audit")&&!has("选错账号"));shot("38-vault-simplified")
            click("更多信息");check(has("选错账号"));check(!has("密码空间来源")&&!has("测试 audit"));shot("39-vault-optional-details")
            start();click("工具");click("剪贴板")
            click("记录上限");waitFor("clipboard limit dropdown"){has("记录上限 下拉菜单")};check(!has("取消"));shot("40-clipboard-limit-dropdown");click("1000 条");waitFor("limit persisted"){p.getInt(V3Prefs.CLIP_LIMIT,0)==1000}
            click("保留时间");waitFor("retention dropdown"){has("保留时间 下拉菜单")};shot("41-clipboard-retention-dropdown");back();check(!has("保留时间 下拉菜单"))
            report("Vault hides long explanations and test sources by default; clipboard limit and retention use anchored dropdowns")
            }
            start();click("外观与布局");click("键盘调整")
            check(has("重置"))
            fun controlBounds(label:String)=android.graphics.Rect().also { rect ->
                all().first {it.text?.toString()==label||it.contentDescription?.toString()==label}.getBoundsInScreen(rect)
            }
            val fullBounds=controlBounds("完整键盘");val splitBounds=controlBounds("分体键盘");val resetBounds=controlBounds("重置")
            check(fullBounds.left<splitBounds.left&&splitBounds.right<resetBounds.left)
            check(kotlin.math.abs(fullBounds.centerY()-resetBounds.centerY())<10)
            val heightBounds=android.graphics.Rect().also {range(1.5f).getBoundsInScreen(it)}
            val heightTap=SystemClock.uptimeMillis()
            for(action in intArrayOf(0,1)) {
                val event=android.view.MotionEvent.obtain(heightTap,SystemClock.uptimeMillis(),action,heightBounds.exactCenterX(),heightBounds.top+22*ctx.resources.displayMetrics.density,0)
                event.source=android.view.InputDevice.SOURCE_TOUCHSCREEN
                try {check(auto.injectInputEvent(event,true))} finally {event.recycle()}
            }
            waitFor("100 percent at height slider center") {kotlin.math.abs(Prefs.heightScale(ctx)-1f)<.005f}
            shot("35-height-reset-top")
            report("Height preview modes align left and reset icon right; touching the slider center sets height to 100 percent")
            click("分体按键尺寸");check(has("分体设置"));check(has("重置"));shot("36-height-parameter-groups")
            start();click("输入设置");click("数字键盘");click("左列符号")
            waitFor("native symbol order"){ui {views().any {it is SymbolOrderView&&it.isShown}}}
            fun order()=ui {views().filterIsInstance<SymbolOrderView>().first {it.isShown}}
            fun points(index:Int):FloatArray {val view=order();return ui {
                val method=SymbolOrderView::class.java.getDeclaredMethod("rect",Int::class.javaPrimitiveType).apply {isAccessible=true}
                val rect=method.invoke(view,index) as android.graphics.RectF;val location=IntArray(2);view.getLocationOnScreen(location)
                floatArrayOf(location[0]+rect.centerX(),location[1]+rect.centerY())
            }}
            fun tapPoint(point:FloatArray) {
                val down=SystemClock.uptimeMillis()
                for(action in intArrayOf(0,1)){val event=android.view.MotionEvent.obtain(down,SystemClock.uptimeMillis(),action,point[0],point[1],0);event.source=android.view.InputDevice.SOURCE_TOUCHSCREEN;try {check(auto.injectInputEvent(event,true))}finally {event.recycle()}}
                SystemClock.sleep(180)
            }
            val original=Prefs.numSide(ctx)
            tapPoint(points(0));waitFor("symbol selected"){order().contentDescription.contains("已选中")}
            tapPoint(points(0));waitFor("symbol deselected"){order().contentDescription.contains("未选中")}
            val from=points(0);val to=points(2);val down=SystemClock.uptimeMillis()
            fun touch(action:Int,x:Float,y:Float){val event=android.view.MotionEvent.obtain(down,SystemClock.uptimeMillis(),action,x,y,0);event.source=android.view.InputDevice.SOURCE_TOUCHSCREEN;try {check(auto.injectInputEvent(event,true))}finally {event.recycle()}}
            touch(0,from[0],from[1]);SystemClock.sleep(android.view.ViewConfiguration.getLongPressTimeout()+100L)
            for(step in 1..12){touch(2,from[0]+(to[0]-from[0])*step/12,from[1]+(to[1]-from[1])*step/12);SystemClock.sleep(20)}
            touch(1,to[0],to[1]);waitFor("symbol order saved"){Prefs.numSide(ctx).getOrNull(2)==original[0]}
            check(Prefs.numSide(ctx).sorted()==original.sorted());shot("37-numeric-native-and-reorder")
            var numberCapture=0
            fun captureSymbolNumbers() {
                // Scroll the page directly: this explanatory text has no click action.
                // Tapping a stale offscreen text bound can hit the fixed keyboard button.
                repeat(3) {
                    all().firstOrNull {it.isScrollable}?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
                    SystemClock.sleep(220)
                }
                numberCapture++
                report("Numbering screenshot $numberCapture, expected order: "+Prefs.numSide(ctx).mapIndexed {index,symbol->"${index+1}:$symbol"}.joinToString())
                shot("37-pool-numbering-$numberCapture")
            }
            captureSymbolNumbers()
            shot("37b-numeric-pool-order-numbers")
            val symbol = original.first()
            click("移除 $symbol")
            waitFor("red pool symbol removed") { symbol !in Prefs.numSide(ctx) }
            click("加入 $symbol")
            waitFor("gray pool symbol added") { Prefs.numSide(ctx).lastOrNull() == symbol }
            captureSymbolNumbers()
            report("Pinned reset and grouped sizing controls visible; numeric symbols tap-select, tap-deselect, and long-drag reorder persists")
            ui {
                val d=ctx.resources.displayMetrics.density
                for(position in 0..2) {
                    val view=SettingExampleView(ctx).apply {choiceOnly=true;exampleActive=true}
                    view.submit(V3Prefs.PINYIN_POSITION,"拼音位置",position,position,"","")
                    val w=(180*d).toInt();val h=(90*d).toInt();view.layout(0,0,w,h)
                    val bitmap=android.graphics.Bitmap.createBitmap(w,h,android.graphics.Bitmap.Config.ARGB_8888);view.draw(android.graphics.Canvas(bitmap))
                    File(pictures,"position-$position.png").outputStream().use {bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)};bitmap.recycle()
                }
            }
        } finally {
            ui {val e=p.edit().clear();backup.forEach {(k,v)->when(v){is Boolean->e.putBoolean(k,v);is Int->e.putInt(k,v);is Long->e.putLong(k,v);is Float->e.putFloat(k,v);is String->e.putString(k,v);is Set<*> -> @Suppress("UNCHECKED_CAST")e.putStringSet(k,v as Set<String>)}};e.commit()}
        }
        report("SETTINGS NOTICE PASSED")
    }
}
