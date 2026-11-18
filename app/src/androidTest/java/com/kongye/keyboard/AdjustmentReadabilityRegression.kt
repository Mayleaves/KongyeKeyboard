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
object AdjustmentReadabilityRegression {
    fun run(i:Instrumentation) {
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
        val pictures=File(ctx.getExternalFilesDir(null),"adjustment-pictures").apply {mkdirs()}
        fun picture(name:String){SystemClock.sleep(300);auto.takeScreenshot().let {b->File(pictures,name+".png").outputStream().use {b.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)};b.recycle()}}
        fun preview()=ui {views().filterIsInstance<KeyboardAdjustmentPreview>().first()}
        fun diagram(key:String)=ui {views().filterIsInstance<SettingExampleView>().first {it.key==key}}
        fun scrollTo(label:String):AccessibilityNodeInfo {
            repeat(16) {
                val node=all().firstOrNull {it.contentDescription?.toString()==label||it.text?.toString()==label}
                if(node!=null) {node.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SHOW_ON_SCREEN.id);SystemClock.sleep(200);return node}
                all().firstOrNull {it.isScrollable}?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD);SystemClock.sleep(150)
            };error("Missing $label")
        }
        fun adjustment(label:String):AccessibilityNodeInfo {
            scrollTo(label+" 调整")
            val wrapper=all().first {it.contentDescription?.toString()==label+" 调整"}
            return nodes(wrapper).firstOrNull {it.rangeInfo!=null} ?: all().first {it.rangeInfo!=null&&run {val a=android.graphics.Rect();val b=android.graphics.Rect();wrapper.getBoundsInScreen(a);it.getBoundsInScreen(b);a.contains(b)}}
        }
        fun set(label:String,value:Float) {
            val node=adjustment(label)
            check(node.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS.id,Bundle().apply {putFloat(AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE,value)}));SystemClock.sleep(250)
        }
        try {
            ui {p.edit().putFloat(Prefs.HEIGHT_SCALE,1f).putInt(Prefs.PAD_L,0).putInt(Prefs.PAD_R,0).putInt(Prefs.PAD_B,0).putInt(V3Prefs.SPLIT_GAP,72).putFloat(V3Prefs.SPLIT_SIZE,1f).putBoolean(Prefs.FLOAT,false).putString(Prefs.LAYOUT,"26").commit()}
            start();click("外观与布局");click("键盘调整")
            waitFor("live preview"){ui {views().any {it is KeyboardAdjustmentPreview}}}
            check(ui {views().filterIsInstance<SettingExampleView>().isEmpty()})
            val node=adjustment("键盘高度");val r=android.graphics.Rect();node.getBoundsInScreen(r)
            val down=SystemClock.uptimeMillis();val y=r.exactCenterY();val from=r.centerX().toFloat();val to=r.left+r.width()*.85f
            fun inject(action:Int,x:Float){val e=android.view.MotionEvent.obtain(down,SystemClock.uptimeMillis(),action,x,y,0);e.source=android.view.InputDevice.SOURCE_TOUCHSCREEN;try {check(auto.injectInputEvent(e,true))}finally{e.recycle()}}
            inject(0,from);for(step in 1..12){inject(2,from+(to-from)*step/12);SystemClock.sleep(25)}
            check(p.getFloat(Prefs.HEIGHT_SCALE,0f)==1f)
            check(preview().contentDescription.toString().contains("高度13")||preview().contentDescription.toString().contains("高度14")) {"Preview didn't update before release"}
            picture("01-height-during-drag");inject(1,to);waitFor("height saved"){p.getFloat(Prefs.HEIGHT_SCALE,0f)>1.3f}
            val labels=listOf("左侧收进","右侧收进","底部留白","分体间距","分体按键尺寸")
            val keys=listOf(Prefs.PAD_L,Prefs.PAD_R,Prefs.PAD_B,V3Prefs.SPLIT_GAP,V3Prefs.SPLIT_SIZE)
            val values=listOf(35f,25f,60f,110f,.8f)
            for(index in labels.indices) {
                val label=labels[index];val key=keys[index];val value=values[index]
                set(label,value)
                val saved=if(key==V3Prefs.SPLIT_SIZE)p.getFloat(key,0f) else p.getInt(key,-1).toFloat()
                check(kotlin.math.abs(saved-value)<.01f){"$label didn't save $saved"}
                check(preview().isShown&&preview().top>=0)
            }
            picture("02-split-adjustments");report("Six controls use live production keyboard preview; horizontal drag updates before release, preferences save on release; preview stays visible")
            start();click("外观与布局");click("拼音与候选显示")
            for(key in listOf(V3Prefs.READINGS,V3Prefs.GLOSS))for(mode in 0..2) {
                ui {p.edit().putInt(key,mode).commit()};SystemClock.sleep(220)
                val view=diagram(key)
                check(ui {view.comparisonOptions.map {it.first}==listOf(0,1,2)&&view.after==mode})
                ui {
                    val width=(360*ctx.resources.displayMetrics.density).toInt();val height=(148*ctx.resources.displayMetrics.density).toInt()
                    view.measure(View.MeasureSpec.makeMeasureSpec(width,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(height,View.MeasureSpec.EXACTLY));view.layout(0,0,width,height)
                    val b=android.graphics.Bitmap.createBitmap(width,height,android.graphics.Bitmap.Config.ARGB_8888);view.draw(android.graphics.Canvas(b));File(pictures,"diagram-$key-$mode.png").outputStream().use {b.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)};b.recycle()
                }
            }
            check(all().none {it.text?.toString()?.contains("英文候选显示中文，中文候选显示英文")==true})
            picture("03-candidate-display");report("Reading and gloss diagrams have fixed Auto/Show/Hide panels; duplicated translation card removed")
            start();click("工具");picture("04-tools-entries");click("剪贴板")
            check(ui {views().filterIsInstance<SettingExampleView>().none {it.key==V3Prefs.CLIP_LIMIT||it.key==V3Prefs.CLIP_DAYS}})
            start();click("工具");click("翻译");check(ui {views().filterIsInstance<SettingExampleView>().none {it.key==Prefs.TRANS_TO}})
            start();click("工具");click("打字统计");scrollTo("记录打字统计");check(ui {views().filterIsInstance<SettingExampleView>().none {it.key==V3Prefs.STATS_ENABLED}})
            report("Clipboard limits, retention, target language and statistics recording retain controls without diagrams")
            start();click("工具");click("短信验证码")
            check(has("自动复制设置流程"));scrollTo("「短信」 → 「右上角三个点」 → 「设置」 → 「智能信息服务」 → 关闭「验证码安全保护」");picture("05-sms-guide")
            ui {
                val d=ctx.resources.displayMetrics.density
                for(on in listOf(false,true)) {
                    val view=SettingExampleView(ctx);view.submit(Prefs.SMS_CODE,"验证码",on,on,"",if(on)"开启" else "关闭")
                    val w=(360*d).toInt();val h=(110*d).toInt();view.layout(0,0,w,h)
                    val b=android.graphics.Bitmap.createBitmap(w,h,android.graphics.Bitmap.Config.ARGB_8888);view.draw(android.graphics.Canvas(b));File(pictures,"sms-$on.png").outputStream().use {b.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)};b.recycle()
                }
            }
            start();click("输入设置");click("数字键盘");picture("06-numeric-editor");click("左列符号");check(has("左列符号顺序"));picture("07-numeric-side");click("0 右边");check(has("当前编辑：「0 右边」按键"))
            start();click("外观与布局");click("工具栏与菜单");picture("08-shared-arrangement")
            check(ui {views().any {it is ArrangeView}})
            ui {
                val tools=listOf("voice","emoji","clip","edit");val menu=listOf("translate","adjust")
                var savedTools=tools;var savedMenu=menu
                val view=ArrangeView(ctx,Prefs.skin(ctx),tools,menu){t,m->savedTools=t;savedMenu=m}
                val w=(360*ctx.resources.displayMetrics.density).toInt();view.measure(View.MeasureSpec.makeMeasureSpec(w,View.MeasureSpec.EXACTLY),0);view.layout(0,0,w,view.measuredHeight)
                val cell=ArrangeView::class.java.getDeclaredMethod("cellRect",Int::class.javaPrimitiveType,Int::class.javaPrimitiveType).apply {isAccessible=true}
                val a=cell.invoke(view,0,0) as android.graphics.RectF;val b=cell.invoke(view,0,1) as android.graphics.RectF;check(a.top==b.top&&a.left>0)
                val drop=ArrangeView::class.java.getDeclaredMethod("drop",String::class.java,Float::class.javaPrimitiveType,Float::class.javaPrimitiveType).apply {isAccessible=true}
                val menuCell=cell.invoke(view,1,1) as android.graphics.RectF;drop.invoke(view,"voice",menuCell.centerX(),menuCell.centerY());check("voice" !in savedTools&&"voice" in savedMenu)
                drop.invoke(view,"voice",a.centerX(),a.centerY());check(savedTools.first()=="voice"&&"voice" !in savedMenu)
            }
            report("Numeric edit targets remain selectable; shared arrangement uses one toolbar row and transfers items both ways")
            start();click("工具");click("备份与恢复");click("WebDAV 备份");picture("09-backup-types");check(has("本地备份"));scrollTo("文件夹地址（HTTPS）");picture("10-webdav-config")
        } finally {
            ui {val e=p.edit().clear();backup.forEach {(k,v)->when(v){is Boolean->e.putBoolean(k,v);is Int->e.putInt(k,v);is Long->e.putLong(k,v);is Float->e.putFloat(k,v);is String->e.putString(k,v);is Set<*> -> @Suppress("UNCHECKED_CAST")e.putStringSet(k,v as Set<String>)}};e.commit()}
        }
        report("ADJUSTMENT READABILITY PASSED")
    }
}
