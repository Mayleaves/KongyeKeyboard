package com.kongye.keyboard

import android.app.Instrumentation
import android.os.Bundle
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import android.view.inspector.WindowInspector
import android.widget.TextView
import java.io.File

object FloatingModeRegression {
    fun run(i:Instrumentation) {
        val ctx=i.targetContext;val p=Prefs.of(ctx);val before=p.all.toMap()
        val auto=i.getUiAutomation(android.app.UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        auto.serviceInfo=auto.serviceInfo.apply {flags=flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS}
        fun shell(command:String)=android.os.ParcelFileDescriptor.AutoCloseInputStream(auto.executeShellCommand(command)).use {String(it.readBytes()).trim()}
        val rotation=shell("settings get system user_rotation");val accelerometer=shell("settings get system accelerometer_rotation")
        fun report(t:String)=i.sendStatus(0,Bundle().apply {putString("stream",t+"\n")})
        @Suppress("UNCHECKED_CAST") fun <T> ui(block:()->T):T {if(android.os.Looper.myLooper()==android.os.Looper.getMainLooper())return block();var value:Any?=null;var failure:Throwable?=null;i.runOnMainSync {try{value=block()}catch(t:Throwable){failure=t}};failure?.let {throw it};return value as T}
        fun waitFor(label:String,check:()->Boolean) {val end=SystemClock.uptimeMillis()+20000;while(SystemClock.uptimeMillis()<end){if(check())return;SystemClock.sleep(70)};error("Timed out: $label")}
        fun descendants(v:View):List<View> = listOf(v)+(if(v is ViewGroup)(0 until v.childCount).flatMap {descendants(v.getChildAt(it))} else emptyList())
        fun views()=WindowInspector.getGlobalWindowViews().flatMap {descendants(it)}
        fun keyboard()=views().filterIsInstance<KeyboardView>().firstOrNull {it.isShown&&!it.preview}
        fun nodes(n:AccessibilityNodeInfo?):List<AccessibilityNodeInfo> = if(n==null)emptyList() else listOf(n)+(0 until n.childCount).flatMap {nodes(n.getChild(it))}
        fun editor():AccessibilityNodeInfo? {auto.clearCache();return auto.windows.flatMap {nodes(it.root)}.firstOrNull {it.contentDescription?.toString()=="键盘效果输入框"}}
        var currentIme:KongyeIME?=null
        fun showEditor() {
            shell("am start -W -f 0x10008000 -n com.kongye.keyboard/.SettingsActivity")
            var show:AccessibilityNodeInfo?=null
            waitFor("show keyboard button") {
                auto.clearCache();show=auto.windows.flatMap {nodes(it.root)}.firstOrNull {it.text?.toString()=="显示键盘"};show!=null
            }
            var action=show
            var clicked=false
            repeat(5) {if(!clicked){clicked=action?.performAction(AccessibilityNodeInfo.ACTION_CLICK)==true;action=action?.parent}}
            check(clicked)
            waitFor("test editor"){editor()!=null}
            waitFor("production keyboard"){ui {val service=keyboard()?.listener as? KongyeIME;service!=null&&service.isInputViewShown&&service.currentInputEditorInfo?.packageName==ctx.packageName}}
            SystemClock.sleep(700)
            currentIme=ui {keyboard()!!.listener as KongyeIME}
        }
        fun live()=checkNotNull(currentIme)
        fun field(name:String)=KongyeIME::class.java.getDeclaredField(name).apply {isAccessible=true}
        fun draft()=ui {field("adj").get(live()) as Adjust}
        fun col()=ui {field("col").get(live()) as View}
        fun overlay()=ui {views().firstOrNull {it is AdjustOverlay&&it.isShown}}
        fun refresh(){ui {
            overlay()?.let {AdjustOverlay::class.java.getDeclaredMethod("refresh").apply {isAccessible=true}.invoke(it)}
            KongyeIME::class.java.getDeclaredMethod("applyFloat").apply {isAccessible=true}.invoke(live())
            KongyeIME::class.java.getDeclaredMethod("applySkin",Boolean::class.javaPrimitiveType,Boolean::class.javaPrimitiveType).apply {isAccessible=true}.invoke(live(),false,false)
        };SystemClock.sleep(350)}
        fun tool(id:String){report("Opening tool: $id");ui {live().onTool(id)};SystemClock.sleep(500)}
        fun action(label:String){ui {
            val text=descendants(overlay()!!).filterIsInstance<TextView>().first {it.text.toString()==label}
            check((text.parent as View).performClick())
        };SystemClock.sleep(350)}
        val pics=File(ctx.getExternalFilesDir(null),"floating-mode-pictures").apply {mkdirs()}
        fun shot(name:String){SystemClock.sleep(250);auto.takeScreenshot().let {bitmap->File(pics,"$name.png").outputStream().use {bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)};bitmap.recycle()}}
        fun iconOrange(expected:Boolean){
            tool("more")
            report(ui {"Menu expected $expected: col ${col().width}x${col().height} at ${col().x},${col().y}; grids "+views().filterIsInstance<TileGridView>().joinToString {"${it.width}x${it.height} shown=${it.isShown}"}})
            shot(if(expected)"debug-floating-menu" else "debug-normal-menu")
            waitFor("menu grid"){ui {views().any {it is TileGridView&&it.isShown}}}
            val colored=ui {
                val grid=views().filterIsInstance<TileGridView>().first {it.isShown}
                val bitmap=android.graphics.Bitmap.createBitmap(grid.width,grid.height,android.graphics.Bitmap.Config.ARGB_8888)
                grid.draw(android.graphics.Canvas(bitmap))
                val index=Prefs.menu(ctx).indexOf("float");check(index>=0)
                val d=ctx.resources.displayMetrics.density;val cw=grid.width/4f
                val cx=(index%4+.5f)*cw;val cy=(index/4)*70*d+28*d
                var orange=0
                for(y in (cy-13*d).toInt()..(cy+13*d).toInt())for(x in (cx-13*d).toInt()..(cx+13*d).toInt()) {
                    val c=bitmap.getPixel(x.coerceIn(0,bitmap.width-1),y.coerceIn(0,bitmap.height-1))
                    if(android.graphics.Color.red(c)>180&&android.graphics.Color.green(c)<125&&android.graphics.Color.blue(c)<105)orange++
                }
                bitmap.recycle();orange>20
            }
            check(colored==expected) {"Floating icon orange=$colored, expected=$expected"}
            shot(if(expected)"02-floating-menu-orange" else "01-normal-menu-ink")
            tool("more")
        }
        try {
            shell("settings put system accelerometer_rotation 0");shell("settings put system user_rotation 0")
            SystemClock.sleep(700)
            ui {p.edit().putBoolean(V3Prefs.ORIENTATION_LAYOUT,false).putBoolean(Prefs.FLOAT,false).putBoolean(Prefs.LEARN,false).putBoolean(V3Prefs.STATS_ENABLED,false).putString(Prefs.THEME,"kongye").putInt(Prefs.MODE,1).putFloat(Prefs.HEIGHT_SCALE,1f).remove(FloatingKeyboard.HEIGHT).putString(Prefs.TOOLS,Prefs.DEFAULT_TOOLS.joinToString(",")).putString(Prefs.MENU,Prefs.DEFAULT_MENU.joinToString(",")).commit()}
            showEditor();iconOrange(false)
            tool("adjust");waitFor("normal adjustment"){ui {overlay()!=null&&!draft().floating}}
            ui {draft().apply {scale=1.12f;padL=12;padR=18;padB=8;splitGap=90f;splitSize=.9f}}
            refresh();action("确认")
            check(p.getFloat(Prefs.HEIGHT_SCALE,0f)==1.12f&&!p.contains(FloatingKeyboard.HEIGHT))
            tool("float");iconOrange(true)
            tool("adjust");waitFor("floating adjustment"){ui {overlay()!=null&&draft().floating}}
            check(draft().scale==1f)
            val a=draft();ui {a.scale=.7f;a.floatWidth=FloatingKeyboard.widthRange(ctx.resources.displayMetrics.widthPixels.toFloat(),ctx.resources.displayMetrics.heightPixels.toFloat()).start;a.floatX=100000f;a.floatY=100000f}
            refresh()
            report(ui {"Minimum resize: actual ${col().width}x${col().height}, requested ${col().layoutParams.width}; draft ${draft().scale},${draft().floatWidth}; service enums "+KongyeIME::class.java.declaredFields.filter {it.type.isEnum}.joinToString {it.isAccessible=true;"${it.name}=${it.get(live())}"}})
            waitFor("small adjustment measured"){ui {col().width==col().layoutParams.width&&keyboard()?.heightScale==.7f}}
            waitFor("small adjustment follows keyboard"){ui {val o=overlay()!!;val c=col();kotlin.math.abs(o.x-c.x)<2&&kotlin.math.abs(o.y-c.y)<2}}
            shot("03-small-floating-adjust-before-check")
            ui {
                val o=overlay()!!;val box=android.graphics.Rect();o.getGlobalVisibleRect(box)
                for(label in listOf("重置","悬浮","分体","确认")) {
                    val t=descendants(o).filterIsInstance<TextView>().first {it.text.toString()==label}
                    val b=android.graphics.Rect();check((t.parent as View).getGlobalVisibleRect(b)) {"Action not visible: $label; overlay $box; action size ${(t.parent as View).width}x${(t.parent as View).height}"}
                    check(box.contains(b)&&b.height()>=30*ctx.resources.displayMetrics.density) {"Clipped adjustment action $label: $b in $box"}
                }
            }
            shot("03-small-floating-adjust-actions")
            action("悬浮")
            check(!draft().floating&&draft().scale==1.12f&&draft().padL==12)
            action("悬浮")
            check(draft().floating&&draft().scale==.7f&&draft().floatWidth==.55f)
            action("确认")
            check(p.getFloat(FloatingKeyboard.HEIGHT,0f)==.7f&&p.getFloat(Prefs.HEIGHT_SCALE,0f)==1.12f)
            check(p.getInt(Prefs.PAD_L,0)==12&&p.getInt(Prefs.PAD_R,0)==18&&p.getInt(Prefs.PAD_B,0)==8)
            tool("adjust");action("重置");check(draft().floating&&draft().scale==1f);action("确认")
            tool("float");check(ui {keyboard()!!.heightScale}==1.12f)
            tool("adjust");check(!draft().floating&&draft().padL==12)
            tool("adjust")
            report("Floating icon is orange only while floating; normal and floating dimensions save independently; minimum-size adjustment actions remain visible; reset retains floating mode")
            shell("settings put system user_rotation 1");SystemClock.sleep(1000)
            showEditor();waitFor("landscape resources"){ui {keyboard()!!.resources.displayMetrics.widthPixels>keyboard()!!.resources.displayMetrics.heightPixels}}
            if(!p.getBoolean(Prefs.FLOAT,false))tool("float")
            waitFor("compact landscape floating"){ui {val c=col();val r=c.parent as View;c.width<r.width*.55f&&c.x>=0&&c.y>=0&&c.x+c.width<=r.width+2&&c.y+c.height<=r.height+2}}
            shot("04-landscape-compact-floating")
            ui {p.edit().putFloat(FloatingKeyboard.LAND_X,100000f).putFloat(FloatingKeyboard.LAND_Y,100000f).commit()}
            refresh()
            check(ui {val c=col();val r=c.parent as View;c.x+c.width<=r.width+2&&c.y+c.height<=r.height+2})
            report("Landscape floating uses the short display edge for compact width and stays inside its actual window after resize and stale positions")
            report("FLOATING MODE PASSED")
        } finally {
            ui {val e=p.edit().clear();before.forEach {(k,v)->when(v){is Boolean->e.putBoolean(k,v);is Int->e.putInt(k,v);is Long->e.putLong(k,v);is Float->e.putFloat(k,v);is String->e.putString(k,v);is Set<*> -> @Suppress("UNCHECKED_CAST")e.putStringSet(k,v as Set<String>)}};e.commit()}
            shell("settings put system user_rotation $rotation");shell("settings put system accelerometer_rotation $accelerometer")
            shell("am start -W -f 0x10008000 -n com.kongye.keyboard/.SettingsActivity")
        }
    }
}
