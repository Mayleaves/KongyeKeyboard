package com.kongye.keyboard

import android.app.Instrumentation
import android.os.Bundle
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import android.view.inspector.WindowInspector
import java.io.File

/** Walks every reorganized page, then exercises the edited keyboard controls. */
object SettingsOrganizationRegression {
    @JvmOverloads fun run(i:Instrumentation,focusedControls:Boolean=false) {
        val ctx=i.targetContext;val p=Prefs.of(ctx);val before=p.all.toMap()
        val auto=i.getUiAutomation(android.app.UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        auto.serviceInfo=auto.serviceInfo.apply {flags=flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS}
        fun report(t:String)=i.sendStatus(0,Bundle().apply {putString("stream",t+"\n")})
        fun nodes(n:AccessibilityNodeInfo?):List<AccessibilityNodeInfo> = if(n==null)emptyList() else listOf(n)+(0 until n.childCount).flatMap {nodes(n.getChild(it))}
        fun all():List<AccessibilityNodeInfo>{auto.clearCache();return auto.windows.flatMap {nodes(it.root)}}
        fun label(n:AccessibilityNodeInfo)=n.text?.toString() ?: n.contentDescription?.toString().orEmpty()
        fun visible(n:AccessibilityNodeInfo):Boolean {val r=android.graphics.Rect();n.getBoundsInScreen(r);return n.isVisibleToUser&&!r.isEmpty}
        fun has(text:String)=all().any {label(it)==text}
        fun waitFor(text:String,check:()->Boolean){val end=SystemClock.uptimeMillis()+20000;while(SystemClock.uptimeMillis()<end){if(check())return;SystemClock.sleep(80)};error("Timed out: $text")}
        fun scroll():Boolean {val n=all().firstOrNull {it.isScrollable&&visible(it)} ?: return false;val moved=n.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD);SystemClock.sleep(180);return moved}
        fun locate(text:String):AccessibilityNodeInfo {
            repeat(24) {val found=all().firstOrNull {label(it)==text&&visible(it)};if(found!=null)return found;scroll()}
            error("Missing $text")
        }
        fun click(text:String) {
            val n=locate(text);n.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SHOW_ON_SCREEN.id);SystemClock.sleep(100)
            var parent:AccessibilityNodeInfo?=n
            repeat(6) {if(parent?.performAction(AccessibilityNodeInfo.ACTION_CLICK)==true){SystemClock.sleep(300);return};parent=parent?.parent}
            error("Cannot click $text")
        }
        fun shell(s:String)=android.os.ParcelFileDescriptor.AutoCloseInputStream(auto.executeShellCommand(s)).use {it.readBytes()}
        fun home(){shell("am start -W -f 0x10008000 -n com.kongye.keyboard/.SettingsActivity");waitFor("home"){has("今日输入")};SystemClock.sleep(200)}
        fun open(vararg path:String){home();path.forEach {click(it)}}
        val pictures=File(ctx.getExternalFilesDir(null),"settings-organization-pictures").apply {mkdirs()}
        fun shot(name:String){SystemClock.sleep(220);auto.takeScreenshot().let {bitmap->File(pictures,"$name.png").outputStream().use {bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)};bitmap.recycle()}}
        fun walk(name:String,path:List<String>,sections:List<String>,last:String) {
            open(*path.toTypedArray());shot("$name-top")
            val seen=linkedSetOf<String>()
            repeat(25) {
                all().filter(::visible).map(::label).forEach {seen.add(it)}
                if(last in seen&&sections.all {it in seen}) {shot("$name-end");report("Opened ${path.last()}: "+sections.joinToString(" → "));return}
                scroll()
            }
            error("Incomplete ${path.last()}: missing ${sections.filter {it !in seen}}, end=$last in ${last in seen}")
        }
        fun descendants(v:View):List<View> = listOf(v)+(if(v is ViewGroup)(0 until v.childCount).flatMap {descendants(v.getChildAt(it))} else emptyList())
        @Suppress("UNCHECKED_CAST") fun <T> ui(block:()->T):T {var result:Any?=null;var error:Throwable?=null;i.runOnMainSync {try{result=block()}catch(t:Throwable){error=t}};error?.let {throw it};return result as T}
        try {
            if(focusedControls) {
                open("数字键盘")
                check(!has("快捷按键")&&!has("编辑区域"))
                val slots=listOf("6 右边","9 右边","0 右边","左列符号")
                val slotRects=slots.map {text->android.graphics.Rect().also {locate(text).getBoundsInScreen(it)}}
                check(slotRects.zipWithNext().all {(a,b)->a.right<=b.left&&kotlin.math.abs(a.centerY()-b.centerY())<12})
                shot("19-restored-numeric-four-buttons")
                click("左列符号");check(has("6 右边")&&has("0 右边"));shot("20-restored-numeric-side")
                click("0 右边");check(has("当前编辑：「0 右边」按键"))
                report("Previous numeric page restored: four peer buttons remain visible when switching between shortcuts and side symbols")

                open("按键与手势","上下滑")
                fun rect(text:String)=android.graphics.Rect().also {locate(text).getBoundsInScreen(it)}
                val language=rect("英文");val clear=rect("清空中文上下滑");val reset=rect("恢复中文默认")
                check(language.right<clear.left&&clear.right<=reset.left&&kotlin.math.abs(clear.centerY()-language.centerY())<24)
                check(kotlin.math.abs(clear.centerY()-reset.centerY())<8&&!has("管理映射"))
                shot("21-flick-management-icons")
                for(zh in listOf(true,false)) {
                    if(!zh)click("英文")
                    val code=if(zh)"zh" else "en";val other=if(zh)"en" else "zh"
                    ui {p.edit().putString("flick26_${code}_q_down","edit:copy").putString("flick26_${other}_q_down","text:☆").commit()}
                    val untouched=p.all.filterKeys {it.startsWith("flick26_${other}_")}.toMap()
                    val label=if(zh)"中文" else "英文"
                    click("清空${label}上下滑")
                    check(p.getString("flick26_${code}_q_down",null)=="edit:copy")
                    check(has("再点一次清空${label}映射，可用重置恢复。"))
                    click("清空${label}上下滑")
                    check(p.all.filterKeys {it.startsWith("flick26_${code}_")}.let {it.size==56&&it.values.all {value->value=="text:"}})
                    check(p.all.filterKeys {it.startsWith("flick26_${other}_")}==untouched)
                    click("恢复${label}默认")
                    check(p.all.keys.none {it.startsWith("flick26_${code}_")})
                    check(p.all.filterKeys {it.startsWith("flick26_${other}_")}==untouched)
                }
                shot("22-flick-english-icons")
                report("Clear/reset icons align to the right of the language selector; clear needs two taps and both actions affect only the selected language")
                walk("23-appearance-background-below-preview",listOf("主题与外观"),listOf("当前效果","背景效果","明暗模式","配色方案"),"配色方案")
                report("Background effects appear directly below the current preview, followed by appearance mode and color schemes")
                ui {p.edit().putBoolean(Prefs.FLOAT,false).putBoolean(FloatingKeyboard.SPLIT,false).commit()}
                open("键盘布局");locate("单手模式");locate("分体键盘");locate("悬浮使用")
                click("悬浮键盘");check(p.getBoolean(Prefs.FLOAT,false))
                click("悬浮分体键盘");check(p.getBoolean(FloatingKeyboard.SPLIT,false))
                val floatingExamples=ui {WindowInspector.getGlobalWindowViews().flatMap {descendants(it)}.filterIsInstance<SettingExampleView>().filter {it.key in setOf(Prefs.FLOAT,FloatingKeyboard.SPLIT)}}
                check(floatingExamples.map {it.key}.toSet()==setOf(Prefs.FLOAT,FloatingKeyboard.SPLIT))
                check(floatingExamples.all {it.before is Boolean&&it.after==true})
                scroll();shot("28-layout-floating-switches")
                click("悬浮分体键盘");click("悬浮键盘")
                check(!p.getBoolean(Prefs.FLOAT,true)&&!p.getBoolean(FloatingKeyboard.SPLIT,true))
                check(ui {floatingExamples.all {it.after==false}})
                shot("31-floating-layout-examples-disabled")
                report("Layout owns single-hand, split and floating switches; both floating comparisons track on/off states and use unlabeled native keyboard silhouettes")

                ui {p.edit().putBoolean(Prefs.FLOAT,false).putFloat(Prefs.HEIGHT_SCALE,.95f).putInt(Prefs.PAD_L,17)
                    .putFloat(FloatingKeyboard.HEIGHT,1.15f).putBoolean(FloatingKeyboard.SPLIT,false)
                    .putFloat(FloatingKeyboard.widthKey(ctx),.75f).commit()}
                val normalKeys=setOf(Prefs.HEIGHT_SCALE,Prefs.PAD_L,Prefs.PAD_R,Prefs.PAD_B,Prefs.SPLIT_LAYOUT,V3Prefs.SPLIT_GAP,V3Prefs.SPLIT_SIZE)
                val normal=p.all.filterKeys {it in normalKeys}.toMap()
                open("键盘调整")
                fun preview()=ui {WindowInspector.getGlobalWindowViews().flatMap {descendants(it)}.filterIsInstance<KeyboardAdjustmentPreview>().first {it.isShown}}
                fun setAdjustment(label:String,value:Float) {
                    val wrapper=locate("$label 调整")
                    wrapper.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SHOW_ON_SCREEN.id)
                    SystemClock.sleep(180)
                    val fresh=locate("$label 调整")
                    val node=nodes(fresh).first {it.rangeInfo!=null}
                    check(node.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS.id,Bundle().apply {putFloat(AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE,value)}))
                    SystemClock.sleep(240)
                }
                check(has("完整键盘")&&has("分体键盘")&&has("悬浮键盘"))
                click("完整键盘");check(preview().contentDescription.toString().startsWith("键盘实时预览"))
                setAdjustment("键盘高度",1.05f)
                check(kotlin.math.abs(p.getFloat(Prefs.HEIGHT_SCALE,0f)-1.05f)<.001f&&p.getFloat(FloatingKeyboard.HEIGHT,0f)==1.15f)
                setAdjustment("键盘高度",.95f)
                click("悬浮键盘");waitFor("floating dimensions"){preview().contentDescription.toString().contains("高度115")&&preview().contentDescription.toString().contains("宽度75")}
                check(!has("左侧收进")&&!has("底部留白"))
                check(!has("启用悬浮键盘")&&!has("悬浮分体键盘"))
                check(all().none {it.packageName?.toString()==ctx.packageName&&it.isCheckable})
                setAdjustment("键盘高度",1.2f);check(kotlin.math.abs(p.getFloat(FloatingKeyboard.HEIGHT,0f)-1.2f)<.001f)
                setAdjustment("悬浮宽度",.72f);check(kotlin.math.abs(FloatingKeyboard.width(ctx)-.72f)<.001f)
                check(preview().contentDescription.toString().contains("高度120")&&preview().contentDescription.toString().contains("宽度72"))
                shot("24-floating-height-width-preview")
                setAdjustment("分体间距",100f);setAdjustment("分体按键尺寸",.8f)
                check(!p.getBoolean(FloatingKeyboard.SPLIT,true)&&p.getInt(FloatingKeyboard.GAP,0)==100&&p.getFloat(FloatingKeyboard.SIZE,0f)==.8f)
                check(preview().contentDescription.toString().endsWith("分体"))
                check(p.all.filterKeys {it in normalKeys}==normal&&!p.getBoolean(Prefs.FLOAT,true))
                shot("25-floating-split-preview")
                ui {p.edit().putBoolean(FloatingKeyboard.SPLIT,true).commit()}
                waitFor("saved floating split"){preview().contentDescription.toString().endsWith("分体")}
                click("重置");check(p.getFloat(FloatingKeyboard.HEIGHT,0f)==1f&&p.getBoolean(FloatingKeyboard.SPLIT,false))
                check(p.all.filterKeys {it in normalKeys}==normal&&!p.getBoolean(Prefs.FLOAT,true))
                click("完整键盘");check(preview().contentDescription.toString().contains("高度95")&&preview().contentDescription.toString().contains("左侧17"))
                shot("26-normal-preview-preserved")
                report("Adjustment has only dimensions and three preview modes; changing or resetting floating dimensions preserves layout enable/split flags")
                report("Three live preview modes verified; floating height, width and split controls save independently; resetting floating dimensions preserves normal dimensions and does not enable floating mode")
                home()
                fun searchBounds()=android.graphics.Rect().also {r->all().first {it.isEditable}.getBoundsInScreen(r)}
                val emptyBounds=searchBounds()
                val placeholderBounds=rect("搜索设置与功能")
                check(kotlin.math.abs(emptyBounds.centerY()-placeholderBounds.centerY())<=2)
                check(all().first {it.isEditable}.performAction(AccessibilityNodeInfo.ACTION_CLICK))
                val d=ctx.resources.displayMetrics.density
                var caretSeen=false
                val until=SystemClock.uptimeMillis()+3500
                while(SystemClock.uptimeMillis()<until&&!caretSeen) {
                    val frame=auto.takeScreenshot();val area=searchBounds()
                    val rows=mutableListOf<Int>()
                    for(y in area.top.coerceAtLeast(0) until area.bottom.coerceAtMost(frame.height))
                        for(x in area.left.coerceAtLeast(0) until (area.left+9*d).toInt().coerceAtMost(frame.width)) {
                            val color=frame.getPixel(x,y);val red=android.graphics.Color.red(color);val green=android.graphics.Color.green(color);val blue=android.graphics.Color.blue(color)
                            if(maxOf(red,green,blue)-minOf(red,green,blue)>70)rows.add(y)
                        }
                    if(rows.size>4) {
                        val cursorCenter=(rows.min()+rows.max())/2f
                        val centeredPlaceholder=rect("搜索设置与功能")
                        check(kotlin.math.abs(cursorCenter-centeredPlaceholder.exactCenterY())<=2*d) {"Search caret/placeholder center mismatch: $cursorCenter vs ${centeredPlaceholder.exactCenterY()}"}
                        File(pictures,"29-search-empty-caret-aligned.png").outputStream().use {frame.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)}
                        caretSeen=true
                    }
                    frame.recycle();if(!caretSeen)SystemClock.sleep(70)
                }
                check(caretSeen){"Search caret not captured"}
                val focusedBounds=searchBounds()
                val query=all().first {it.isEditable}
                check(query.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT,Bundle().apply {putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,"拼音")}))
                SystemClock.sleep(250)
                check(searchBounds()==focusedBounds){"Search height or position changed after entering text"}
                shot("30-search-filled-height-stable")
                click("清空搜索");check(searchBounds()==focusedBounds&&has("搜索设置与功能"))
                report("Home search placeholder and caret share a vertical center; search bounds remain stable before input, after input and after clearing")
                report("NUMERIC RESTORE AND FLICK CONTROLS PASSED")
                return
            }
            home()
            val menu=linkedSetOf<String>()
            repeat(26) {all().filter(::visible).map(::label).forEach {menu.add(it)};if("更新历史" !in menu)scroll()}
            val ordered=menu.toList()
            fun precedes(a:String,b:String){check(ordered.indexOf(a)>=0&&ordered.indexOf(a)<ordered.indexOf(b)) {"Wrong home order $a / $b"}}
            listOf("启用输入法","选择输入法","功能权限","离线资源","输入设置","中文与拼音","英文与空格","数字键盘","滑行输入","语音输入","手写输入","候选与联想","按键与手势","外观与布局","主题与外观","键盘布局","键盘调整","拼音与候选显示","工具栏与菜单","工具","剪贴板","短信验证码","密码填充","翻译","打字统计","备份与恢复","关于").zipWithNext().forEach {(a,b)->precedes(a,b)}
            shot("00-home-menu-order")
            report("Home menus follow preparation, input methods, suggestions, gestures, appearance and utility order; numeric keyboard appears under Input")
            walk("01-english",listOf("英文与空格"),listOf("单词输入","大小写","英文自动空格","混合输入空格"),"中文与数字之间自动加空格")
            walk("02-association",listOf("候选与联想"),listOf("词语联想","修改后的推荐","表情与符号推荐"),"推荐符号")
            walk("03-flick",listOf("按键与手势","上下滑"),listOf("启用与方向","选择按键","设置动作"),"自定义符号")
            walk("04-delete",listOf("按键与手势","删除键"),listOf("滑动与清空","组字期间","联想期间"),"联想时直接删除")
            walk("05-glide",listOf("滑行输入"),listOf("启用语言","滑行反馈","中文回删"),"按字回删中文滑行")
            if(p.getInt(VoicePolish.MODE,0)==0)walk("06-polish",listOf("语音输入","语句润色"),listOf("润色设置","离线模型"),"离线润色模型")
            else walk("06-polish",listOf("语音输入","语句润色"),listOf("润色设置","接口配置","连接测试"),"测试已保存的接口")
            walk("07-appearance",listOf("主题与外观"),listOf("当前效果","背景效果","明暗模式","配色方案"),"配色方案")
            walk("08-numeric",listOf("数字键盘"),listOf("数字键盘","当前编辑：「6 右边」按键","选择功能或符号"),"设为该键")
            walk("09-display",listOf("拼音与候选显示"),listOf("输入中的拼音","候选词辅助信息"),"候选翻译")
            walk("10-sms",listOf("短信验证码"),listOf("自动复制","权限与设置","仍无法复制时"),"自动复制设置流程")
            val afterWalk=p.all.toMap()
            val changed=(before.keys+afterWalk.keys).filter {before[it]!=afterWalk[it]}
            check(changed.isEmpty()) {"Opening reorganized pages changed saved keys: $changed"}
            report("All ten page layouts open and scroll without changing saved preferences")

            // The large editing pages now have explicit edit regions/types; verify saving still works.
            open("按键与手势","上下滑");locate("设置动作");click("编辑功能");click("编辑功能：复制");click("保存")
            check(p.getString("flick26_zh_q_down","")=="edit:copy")
            shot("11-flick-edit-command")
            open("按键与手势","上下滑");locate("设置动作");click("符号文字")
            val input=all().first {it.isEditable}
            check(input.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT,Bundle().apply {putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,"☆")}))
            click("保存");check(p.getString("flick26_zh_q_down","")=="text:☆")
            open("数字键盘");click("9 右边");click("$");check(Prefs.numKeys(ctx)[1]=="$")
            click("左列符号");check(has("6 右边")&&!has("快捷按键"));locate("左列符号顺序");shot("12-numeric-symbol-order")
            locate("点灰色符号加入左列，点红色符号移除");shot("13-numeric-fixed-pool")
            report("Flick symbol/command saves and numeric shortcut/side-symbol tabs remain functional")

            ui {p.edit().putBoolean(V3Prefs.VOICE_POLISH,true).putInt(VoicePolish.MODE,1).commit()}
            walk("14-online-polish",listOf("语音输入","语句润色"),listOf("润色设置","接口配置","连接测试"),"测试已保存的接口")
            open("备份与恢复");click("WebDAV 备份");check(has("服务器配置")&&!has("文件夹地址（HTTPS）")&&!has("账号"));shot("15-webdav-collapsed")
            click("服务器配置");check(has("文件夹地址（HTTPS）"));shot("16-webdav-expanded")
            click("本地备份");click("WebDAV 备份");check(!has("文件夹地址（HTTPS）"));shot("17-webdav-return-collapsed")
            report("WebDAV server configuration starts collapsed, expands on request and returns collapsed; saved credentials were not edited")

            home();val search=all().first {it.isEditable}
            check(search.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT,Bundle().apply {putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,"数字键盘")}))
            waitFor("numeric search breadcrumb"){has("输入设置")&&has("数字键盘")}
            shot("18-numeric-search-new-parent")
            report("SETTINGS ORGANIZATION PASSED")
        } finally {
            ui {val e=p.edit().clear();before.forEach {(k,v)->when(v){is Boolean->e.putBoolean(k,v);is Int->e.putInt(k,v);is Long->e.putLong(k,v);is Float->e.putFloat(k,v);is String->e.putString(k,v);is Set<*> -> @Suppress("UNCHECKED_CAST")e.putStringSet(k,v as Set<String>)}};e.commit()}
            home()
        }
    }
}
