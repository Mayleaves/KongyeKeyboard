package com.kongye.keyboard

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo as Node
import android.view.inspector.WindowInspector
import java.io.File

object StatsReportUiRegression {
    fun run(i: Instrumentation) {
        val ctx=i.targetContext
        val receiptPrefs=ctx.getSharedPreferences("stats_receipt",android.content.Context.MODE_PRIVATE)
        val previous=receiptPrefs.all.toMap()
        receiptPrefs.edit().clear().putBoolean("sound",false).putBoolean("print_sound",false).putBoolean("tear_sound",false).commit()
        val auto=i.getUiAutomation(android.app.UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        auto.serviceInfo=auto.serviceInfo.apply { flags=flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS }
        fun tree(n:Node?):List<Node> = if(n==null)emptyList() else listOf(n)+(0 until n.childCount).flatMap {tree(n.getChild(it))}
        fun nodes():List<Node> {auto.clearCache();return auto.windows.flatMap {tree(it.root)}}
        fun label(n:Node)=n.text?.toString() ?: n.contentDescription?.toString().orEmpty()
        fun has(t:String)=nodes().any {label(it)==t && it.isVisibleToUser}
        fun waitFor(t:String,check:()->Boolean) {
            val end=SystemClock.uptimeMillis()+20000
            while(SystemClock.uptimeMillis()<end) {if(check())return;SystemClock.sleep(60)}
            error("Timed out $t: "+nodes().map {label(it)}.filter {it.isNotBlank()}.take(20))
        }
        fun locate(t:String):Node {
            repeat(30) {
                nodes().firstOrNull {label(it)==t&&it.isVisibleToUser}?.let {return it}
                nodes().firstOrNull {it.isScrollable&&it.isVisibleToUser}?.performAction(Node.ACTION_SCROLL_FORWARD)
                SystemClock.sleep(130)
            }
            error("Missing $t")
        }
        fun click(t:String) {
            var n=locate(t)
            repeat(6) {if(n.performAction(Node.ACTION_CLICK)){SystemClock.sleep(180);return};n=n.parent ?: error("Not clickable $t")}
        }
        fun descendants(v:View):List<View> = listOf(v)+(if(v is ViewGroup)(0 until v.childCount).flatMap {descendants(v.getChildAt(it))} else emptyList())
        fun ledPrinting():Boolean {
            var state=false
            i.runOnMainSync {
                val led=WindowInspector.getGlobalWindowViews().flatMap {descendants(it)}.filterIsInstance<PrinterLedView>().first {it.isShown}
                state=led.printing
            }
            return state
        }
        val pictures=File(ctx.getExternalFilesDir(null),"stats-report-ui").apply {mkdirs()}
        fun shot(name:String) {checkNotNull(auto.takeScreenshot()).also {b->File(pictures,"$name.png").outputStream().use {b.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)};b.recycle()}}
        var chooser:Intent?=null
        var create:Intent?=null
        val saved=File(ctx.cacheDir,"stats/save-ui-check.png").apply {parentFile!!.mkdirs();writeBytes(byteArrayOf())}
        val saveUri=android.net.Uri.parse("content://com.kongye.keyboard.files/stats/"+saved.name)
        val monitor=object:Instrumentation.ActivityMonitor() {
            override fun onStartActivity(intent:Intent):Instrumentation.ActivityResult? {
                if(intent.action==Intent.ACTION_CHOOSER) {chooser=intent;return Instrumentation.ActivityResult(Activity.RESULT_CANCELED,null)}
                if(intent.action==Intent.ACTION_CREATE_DOCUMENT) {create=intent;return Instrumentation.ActivityResult(Activity.RESULT_OK,Intent().setData(saveUri))}
                return null
            }
        }
        i.addMonitor(monitor)
        try {
            val steps=java.util.concurrent.atomic.AtomicInteger()
            val tears=java.util.concurrent.atomic.AtomicInteger()
            var cueView:StatsReceiptView?=null
            i.runOnMainSync {
                cueView=StatsReceiptView(ctx).apply {
                    onStep={steps.incrementAndGet()};onTear={tears.incrementAndGet()}
                    submit(android.graphics.Bitmap.createBitmap(108,400,android.graphics.Bitmap.Config.ARGB_8888),"sound fixture",true) {}
                }
            }
            SystemClock.sleep(650)
            i.runOnMainSync {cueView?.performClick();cueView?.release()}
            check(steps.get()>0&&tears.get()==1) {"Missing printer or tear audio cue"}
            i.sendStatus(0,Bundle().apply {putString("stream","Printer feed and tap-to-tear audio cues passed\n")})
            val before=TypingStats.of(ctx).snapshot().total
            i.startActivitySync(Intent(ctx,SettingsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
            waitFor("settings"){has("今日输入")}
            click("今日输入")
            waitFor("statistics"){has("每日字数")}
            check(!has("生成小票报告")) {"Receipt report should be under 统计管理, not at top"}
            locate("清空统计")
            SystemClock.sleep(150)
            val headingRect=android.graphics.Rect().also {locate("统计管理").getBoundsInScreen(it)}
            val reportRect=android.graphics.Rect().also {locate("查看报告").getBoundsInScreen(it)}
            val clearRect=android.graphics.Rect().also {locate("清空统计").getBoundsInScreen(it)}
            check(headingRect.bottom<reportRect.top && reportRect.bottom<clearRect.top) {"生成小票报告 is not ordered inside 统计管理"}
            shot("02-management-actions")
            click("查看报告")
            waitFor("printing"){has("正在生成打字报告…")}
            check(chooser==null)
            check(nodes().filter {label(it) in listOf("分享图片","保存图片")}.all {!it.isEnabled})
            SystemClock.sleep(500)
            check(ledPrinting()) {"Printer LED should be green-blinking while printing"}
            shot("03-paper-feeding")
            nodes().firstOrNull {label(it)=="正在打印打字报告"}?.performAction(Node.ACTION_CLICK)
            waitFor("receipt ready"){has("报告已生成 · 上下滑动查看")}
            check(!ledPrinting()) {"Printer LED should turn static gray once finished"}
            check(chooser==null);shot("04-report-ready")
            check(!has("纸张配色") && !has("报告头图"))
            click("样式");waitFor("dedicated style screen"){has("纸张配色 · 实时预览")&&has("报告头图")}
            val printSwitch=locate("打印音效开关")
            val tearSwitch=locate("撕票音效开关")
            val previewNode=locate("样式实时预览")
            val printRect=android.graphics.Rect().also {printSwitch.getBoundsInScreen(it)}
            val tearRect=android.graphics.Rect().also {tearSwitch.getBoundsInScreen(it)}
            val previewRect=android.graphics.Rect().also {previewNode.getBoundsInScreen(it)}
            check(printRect.bottom<previewRect.top && tearRect.bottom<previewRect.top) {"Sound toggles must be placed above live preview"}
            check(!receiptPrefs.getBoolean("print_sound",true) && !receiptPrefs.getBoolean("tear_sound",true)) {"Sound switches did not start unchecked"}
            click("打印音效开关")
            check(receiptPrefs.getBoolean("print_sound",false) && !receiptPrefs.getBoolean("tear_sound",true)) {"Print sound did not toggle independently"}
            click("打印音效开关")
            check(!receiptPrefs.getBoolean("print_sound",true)) {"Print sound did not turn off"}
            click("撕票音效开关")
            check(receiptPrefs.getBoolean("tear_sound",false) && !receiptPrefs.getBoolean("print_sound",true)) {"Tear sound did not toggle independently"}
            click("撕票音效开关")
            check(!receiptPrefs.getBoolean("tear_sound",true)) {"Tear sound did not turn off"}
            shot("08-style-sheet");click("完成")
            waitFor("receipt after style sheet"){nodes().any {it.isScrollable&&label(it).startsWith("空叶键盘 · 打字统计")}}
            val paperNode=nodes().first {it.isScrollable&&label(it).startsWith("空叶键盘 · 打字统计")}
            val bounds=android.graphics.Rect();paperNode.getBoundsInScreen(bounds)
            checkNotNull(auto.takeScreenshot()).also { bitmap ->
                val d=ctx.resources.displayMetrics.density
                var dark=0
                for(y in (bounds.top+75*d).toInt() until (bounds.top+300*d).toInt().coerceAtMost(bitmap.height))
                    for(x in bounds.left.coerceAtLeast(0) until bounds.right.coerceAtMost(bitmap.width)) {
                        val color=bitmap.getPixel(x,y)
                        if(android.graphics.Color.red(color)<140&&android.graphics.Color.green(color)<140&&android.graphics.Color.blue(color)<140)dark++
                    }
                bitmap.recycle();check(dark>150) {"Receipt text became translucent: only $dark dark pixels"}
            }
            check(nodes().filter {label(it) in listOf("分享图片","保存图片")}.all {it.isEnabled})
            i.sendStatus(0,Bundle().apply {putString("stream","Report layout, green/gray LED and split top sound controls ready\n")})
            val actionBounds=nodes().filter {it.isVisibleToUser && label(it) in listOf("分享图片","保存图片")}.map {node->
                android.graphics.Rect().also {node.getBoundsInScreen(it)}
            }
            check(actionBounds.size==2 && actionBounds[0].top==actionBounds[1].top &&
                actionBounds[0].bottom==actionBounds[1].bottom &&
                kotlin.math.abs(actionBounds[0].width()-actionBounds[1].width())<=1) {"Report buttons do not align"}
            val receipt=nodes().first {it.isScrollable&&label(it).startsWith("空叶键盘 · 打字统计")}
            repeat(5) {receipt.performAction(Node.ACTION_SCROLL_FORWARD)}
            SystemClock.sleep(120);shot("05-receipt-footer")
            click("分享图片")
            waitFor("image chooser intent"){chooser!=null}
            @Suppress("DEPRECATION") val send=chooser!!.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)!!
            check(send.action==Intent.ACTION_SEND&&send.type=="image/png")
            @Suppress("DEPRECATION") val uri=send.getParcelableExtra<android.net.Uri>(Intent.EXTRA_STREAM)!!
            ctx.contentResolver.openInputStream(uri)!!.use {check(it.read()==137)}
            click("保存图片")
            waitFor("save intent"){create!=null}
            check(create!!.type=="image/png")
            waitFor("PNG saved"){has("图片已保存")}
            check(saved.readBytes().contentEquals(ctx.contentResolver.openInputStream(uri)!!.use {it.readBytes()}))
            check(!has("音效") && !has("撕票") && !has("重打"))
            nodes().first {it.isScrollable&&label(it).startsWith("空叶键盘 · 打字统计")}.performAction(Node.ACTION_CLICK)
            SystemClock.sleep(350);shot("09-torn-matching-teeth")
            i.sendStatus(0,Bundle().apply {putString("stream","Share/save passed; sound, tear and reprint controls absent\n")})
            for(theme in ReceiptTheme.entries) {
                click("样式");click(theme.title)
                if(theme==ReceiptTheme.LETTERPRESS)shot("preview-letterpress-grid")
                click("完成")
                waitFor("theme ${theme.title}"){has("报告已生成 · 上下滑动查看")}
                shot("theme-${theme.name.lowercase()}")
                nodes().first {it.isScrollable&&label(it).startsWith("空叶键盘 · 打字统计")}.performAction(Node.ACTION_CLICK)
                SystemClock.sleep(350);shot("torn-theme-${theme.name.lowercase()}")
                chooser=null;click("分享图片");waitFor("themed image"){chooser!=null}
                @Suppress("DEPRECATION") val themedSend=chooser!!.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)!!
                @Suppress("DEPRECATION") val themedUri=themedSend.getParcelableExtra<android.net.Uri>(Intent.EXTRA_STREAM)!!
                if(theme==ReceiptTheme.LETTERPRESS)ctx.contentResolver.openInputStream(themedUri)!!.use {input->
                    File(pictures,"letterpress-export.png").outputStream().use {input.copyTo(it)}
                }
                ctx.contentResolver.openInputStream(themedUri)!!.use {input->
                    val b=checkNotNull(android.graphics.BitmapFactory.decodeStream(input))
                    check(b.getPixel(60,45)==theme.paper) {"Export palette does not match ${theme.title}"}
                    b.recycle()
                }
            }
            val brandHashes=mutableSetOf<Int>()
            for(brand in ReceiptBrand.entries) {
                click("样式");click("报告头图");shot("style-brands");click(brand.title);click("完成")
                waitFor("brand ${brand.title}"){has("报告已生成 · 上下滑动查看")}
                chooser=null;click("分享图片");waitFor("branded image"){chooser!=null}
                @Suppress("DEPRECATION") val brandSend=chooser!!.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)!!
                @Suppress("DEPRECATION") val brandUri=brandSend.getParcelableExtra<android.net.Uri>(Intent.EXTRA_STREAM)!!
                ctx.contentResolver.openInputStream(brandUri)!!.use {input->
                    val b=checkNotNull(android.graphics.BitmapFactory.decodeStream(input))
                    val pixels=IntArray(700*250);b.getPixels(pixels,0,700,190,65,700,250)
                    check(pixels.count {it!=ReceiptTheme.WHITE.paper}>150) {"Empty brand art ${brand.title}"}
                    brandHashes.add(pixels.contentHashCode());b.recycle()
                }
                shot("brand-${brand.name.lowercase()}")
            }
            check(brandHashes.size==4) {"Brand options render identically"}
            val sealHashes=mutableSetOf<Int>()
            for(seal in ReceiptStamp.entries) {
                click("样式")
                click("印章")
                waitFor("stamp settings"){has("印章 · 实时预览")}
                click(seal.title)
                val angleSlider=locate("印章倾角微调")
                val opacitySlider=locate("印泥浓淡微调")
                check(angleSlider.performAction(Node.AccessibilityAction.ACTION_SET_PROGRESS.id,Bundle().apply {putFloat(Node.ACTION_ARGUMENT_PROGRESS_VALUE,10f)}))
                check(opacitySlider.performAction(Node.AccessibilityAction.ACTION_SET_PROGRESS.id,Bundle().apply {putFloat(Node.ACTION_ARGUMENT_PROGRESS_VALUE,75f)}))
                SystemClock.sleep(150)
                shot("seal-controls-${seal.name.lowercase()}")
                click("完成");waitFor("stamp applied"){has("报告已生成 · 上下滑动查看")}
                check(receiptPrefs.getInt("angle",0)==10 && receiptPrefs.getInt("opacity",0)==75)
                chooser=null;click("分享图片");waitFor("stamped image"){chooser!=null}
                @Suppress("DEPRECATION") val sealSend=chooser!!.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)!!
                @Suppress("DEPRECATION") val sealUri=sealSend.getParcelableExtra<android.net.Uri>(Intent.EXTRA_STREAM)!!
                ctx.contentResolver.openInputStream(sealUri)!!.use {input->
                    val b=checkNotNull(android.graphics.BitmapFactory.decodeStream(input))
                    val pixels=IntArray(330*320);b.getPixels(pixels,0,330,710,b.height-550,330,320)
                    check(pixels.count {it!=ReceiptTheme.WHITE.paper}>100)
                    sealHashes.add(pixels.contentHashCode());b.recycle()
                }
            }
            check(sealHashes.size==4) {"Stamp options render identically"}
            val footerHashes=mutableSetOf<Int>()
            for(footer in ReceiptFooter.entries) {
                click("样式")
                click("落款");waitFor("footer chooser"){has("落款 · 实时预览")};shot("style-footers");click(footer.title);click("完成")
                waitFor("footer applied"){has("报告已生成 · 上下滑动查看")}
                chooser=null;click("分享图片");waitFor("footer image"){chooser!=null}
                @Suppress("DEPRECATION") val footerSend=chooser!!.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)!!
                @Suppress("DEPRECATION") val footerUri=footerSend.getParcelableExtra<android.net.Uri>(Intent.EXTRA_STREAM)!!
                ctx.contentResolver.openInputStream(footerUri)!!.use {input->
                    val b=checkNotNull(android.graphics.BitmapFactory.decodeStream(input))
                    val pixels=IntArray(700*165);b.getPixels(pixels,0,700,190,b.height-250,700,165)
                    check(pixels.count {it!=ReceiptTheme.WHITE.paper}>50)
                    footerHashes.add(pixels.contentHashCode());b.recycle()
                }
            }
            check(footerHashes.size==4) {"Footer options render identically"}
            nodes().first {it.isScrollable&&label(it).startsWith("空叶键盘 · 打字统计")}.let {paper->repeat(8){paper.performAction(Node.ACTION_SCROLL_FORWARD)}}
            shot("07-selected-stamp-footer")
            click("返回打字统计")
            waitFor("returned statistics"){has("打字统计")}
            check(TypingStats.of(ctx).snapshot().total==before)
            i.sendStatus(0,Bundle().apply {putString("stream","STATS REPORT UI PASSED: management grouping, green pulsing / gray idle LED, split top sound toggles, printing animation, exports disabled until ready, scroll, explicit FileProvider image sharing, PNG save bytes, four paper palettes, four distinct SVG brands, four stamps and angle/opacity sliders, four distinct vector footers, and counters preserved\n")})
        } finally {
            i.removeMonitor(monitor);saved.delete()
            receiptPrefs.edit().clear().apply {
                previous.forEach {(key,value)->when(value) {is String->putString(key,value);is Boolean->putBoolean(key,value);is Int->putInt(key,value)}}
            }.commit()
        }
    }
}
