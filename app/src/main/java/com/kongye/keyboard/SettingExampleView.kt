package com.kongye.keyboard

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.text.TextUtils
import android.view.View

/** Illustrative settings examples: no user text, decoding or network on draw. */
class SettingExampleView(private val ctx:Context):View(ctx) {
    var exampleActive=true
    var choiceOnly=false
    var layoutLandscape:Boolean?=null
    var comparisonOptions:List<Pair<Any,String>> = emptyList()
    private val inactivePaint=Paint(3).apply {colorFilter=android.graphics.ColorMatrixColorFilter(android.graphics.ColorMatrix().apply {setSaturation(0f)})}
    private val paint=Paint(3)
    private val glyphBounds=Rect()
    private val graphicIcons=Icons(ctx)
    var ink=0xff18191c.toInt();var muted=0xff686b73.toInt();var tile=0xfff0f2f6.toInt();var accent=0xfff0492c.toInt()
    var key="";var title="";var before:Any=false;var after:Any=false;var beforeLabel="";var afterLabel=""
    private val d=resources.displayMetrics.density
    fun submit(k:String,t:String,b:Any,a:Any,bl:String,al:String){key=k;title=t;before=b;after=a;beforeLabel=bl;afterLabel=al;contentDescription=if(a is Boolean)"$t：左侧关闭效果，右侧开启效果，当前$al。${examples(k,a).joinToString("，")}" else "$t：修改前 $bl，当前 $al。${examples(k,a).joinToString("，")}";invalidate()}
    override fun onDraw(c:Canvas) {
        super.onDraw(c)
        if(choiceOnly&&!exampleActive) {
            val save=c.saveLayer(0f,0f,width.toFloat(),height.toFloat(),inactivePaint)
            drawExample(c);c.restoreToCount(save)
        } else drawExample(c)
    }
    private fun drawExample(c:Canvas) {
        if(choiceOnly && key==Prefs.MODE){themeGraphic(c,(after as Number).toInt());return}
        if(choiceOnly && key==V3Prefs.VIBRATION){vibrationGraphic(c,(after as Number).toInt());return}
        if(choiceOnly){panel(c,0f,width.toFloat(),"",after,true);return}
        if(comparisonOptions.size in 2..3) {
            val gap=8*d;val w=(width-gap*(comparisonOptions.size-1))/comparisonOptions.size
            comparisonOptions.forEachIndexed {index,option->comparisonPanel(c,index*(w+gap),w,option.second,option.first,after==option.first)}
            return
        }
        if(before is Boolean && after is Boolean) {
            val gap=8*d;val w=(width-gap)/2
            comparisonPanel(c,0f,w,"关闭效果",false,after==false)
            comparisonPanel(c,w+gap,w,"开启效果",true,after==true)
            return
        }
        if(before==after) {comparisonPanel(c,0f,width.toFloat(),"效果示例 · $afterLabel",after,true);return}
        val gap=8*d;val w=(width-gap)/2
        comparisonPanel(c,0f,w,"修改前 · $beforeLabel",before,false)
        comparisonPanel(c,w+gap,w,"当前 · $afterLabel",after,true)
    }
    private fun comparisonPanel(c:Canvas,x:Float,w:Float,label:String,value:Any,current:Boolean) {
        val save=if(current)c.save() else c.saveLayer(RectF(x,0f,x+w,height.toFloat()),inactivePaint)
        panel(c,x,w,label,value,current)
        if(!current) {
            paint.style=Paint.Style.FILL;paint.color=(tile and 0x00ffffff) or 0x50000000
            c.drawRoundRect(RectF(x,0f,x+w,height.toFloat()),12*d,12*d,paint)
        }
        c.restoreToCount(save)
    }
    private val silhouette by lazy {KeyboardSilhouette(ctx)}
    private fun themeGraphic(c:Canvas,mode:Int) {
        val box=RectF(4*d,4*d,width-4*d,height-4*d);val mid=width/2f
        fun keyboard(dark:Boolean) {silhouette.draw(c,box,Prefs.layout(ctx),if(dark)0xff34363c.toInt() else 0xffe9eaf0.toInt(),if(dark)0xff73767d.toInt() else 0xffffffff.toInt(),if(dark)0xff53565d.toInt() else 0xffd5d7de.toInt())}
        if(mode==0) {
            c.save();c.clipRect(0f,0f,mid,height.toFloat());keyboard(false);c.restore()
            c.save();c.clipRect(mid,0f,width.toFloat(),height.toFloat());keyboard(true);c.restore()
            val cy=height/2f;paint.color=0xfff9fafb.toInt()
            for(direction in listOf(-1,1)) {val path=android.graphics.Path();path.moveTo(mid+direction*9*d,cy);path.lineTo(mid+direction*2*d,cy-5*d);path.lineTo(mid+direction*2*d,cy+5*d);path.close();c.drawPath(path,paint)}
        } else keyboard(mode==2)
    }
    private fun layoutGraphic(c:Canvas,x:Float,w:Float,value:Any) {
        val p=Prefs.of(ctx)
        val landscape = layoutLandscape ?: (key == V3Prefs.LAYOUT_LANDSCAPE)
        val selectedLandscape = landscape && p.getBoolean(V3Prefs.ORIENTATION_LAYOUT,false)
        val layout=if(key==Prefs.LAYOUT||key==V3Prefs.LAYOUT_LANDSCAPE)value.toString() else if(layoutLandscape!=null)V3Prefs.layout(p,selectedLandscape) else Prefs.layout(ctx)
        val split=when(key) {
            Prefs.LAYOUT,V3Prefs.LAYOUT_LANDSCAPE->V3Prefs.keyboardForm(p,key==V3Prefs.LAYOUT_LANDSCAPE)==KeyboardForm.SPLIT
            Prefs.SPLIT_LAYOUT->value==true
            else->if(layoutLandscape!=null)V3Prefs.keyboardForm(p,selectedLandscape)==KeyboardForm.SPLIT else V3Prefs.dockedSplit(ctx)
        }
        val hand=if(key==V3Prefs.ONE_HAND)(value as Number).toInt() else 0
        val filled=key!=V3Prefs.FILL_GAPS || value==true
        val scale=if(key==Prefs.HEIGHT_SCALE)(value as Number).toFloat() else Prefs.heightScale(ctx)
        silhouette.draw(c,RectF(x+5*d,if(choiceOnly)5*d else 27*d,x+w-5*d,height-6*d),layout,0xffe6e8ed.toInt(),0xffffffff.toInt(),0xffcdd0d8.toInt(),split,hand,landscape,filled,scale,sourceWidthOverride=if(layoutLandscape!=null)(if(landscape)maxOf(resources.displayMetrics.widthPixels,resources.displayMetrics.heightPixels)else minOf(resources.displayMetrics.widthPixels,resources.displayMetrics.heightPixels)).toFloat() else null)
    }
    private fun keyboardFormGraphic(c: Canvas, x: Float, w: Float, form: KeyboardForm) {
        val metrics = ctx.resources.displayMetrics
        val landscape = key == V3Prefs.LANDSCAPE_FLOAT ||
            (!Prefs.of(ctx).getBoolean(V3Prefs.ORIENTATION_LAYOUT, false) && FloatingKeyboard.landscape(ctx))
        val layout = V3Prefs.layout(Prefs.of(ctx), key == V3Prefs.LANDSCAPE_FLOAT)
        val width = if (landscape) maxOf(metrics.widthPixels, metrics.heightPixels) else minOf(metrics.widthPixels, metrics.heightPixels)
        val sourceDp = width / metrics.density
        val background = 0xffe6e8ed.toInt()
        val keyColor = 0xffffffff.toInt()
        val modifierColor = 0xffcdd0d8.toInt()
        val scene = RectF(x + 6*d, 10*d, x + w - 6*d, height - 10*d)
        rounded(c, scene, 0xfffafbfc.toInt(), 7f)
        rounded(c, RectF(scene.left + 6*d, scene.top + 8*d, scene.right - 10*d, scene.top + 11*d), 0xffe0e3e9.toInt(), 1.5f)
        val floating = form == KeyboardForm.FLOATING
        val split = if (floating) Prefs.flag(ctx, FloatingKeyboard.SPLIT, false) else form == KeyboardForm.SPLIT
        val drawSplit = split && KeyboardForm.supportsSplit(layout, sourceDp, floating)
        val board = if (floating) {
            val window = RectF(scene.left + scene.width()*.19f, scene.top + 17*d, scene.right - 4*d, scene.bottom - 7*d)
            rounded(c, RectF(window).apply { offset(0f, 2*d) }, 0xffd2d5dd.toInt(), 5f)
            rounded(c, window, background, 5f)
            rounded(c, RectF(window.centerX() - 7*d, window.top + 3*d, window.centerX() + 7*d, window.top + 5*d), 0xffa2a7b1.toInt(), 1f)
            RectF(window.left + 2*d, window.top + 8*d, window.right - 2*d, window.bottom - 2*d)
        } else {
            RectF(scene.left + 2*d, scene.top + 22*d, scene.right - 2*d, scene.bottom - 2*d)
        }
        val p = Prefs.of(ctx)
        silhouette.draw(c, board, layout, background, keyColor, modifierColor, split = drawSplit, landscape = landscape,
            scale = if (floating) FloatingKeyboard.height(ctx) else Prefs.heightScale(ctx),
            gapOverride = p.getInt(if (floating) FloatingKeyboard.GAP else V3Prefs.SPLIT_GAP, 72).toFloat(),
            sizeOverride = p.getFloat(if (floating) FloatingKeyboard.SIZE else V3Prefs.SPLIT_SIZE, 1f),
            sourceWidthOverride = if (floating) width*.74f else width.toFloat())
    }

    private fun floatingGraphic(c:Canvas,x:Float,w:Float,value:Any) {
        val on=value==true
        val splitExample=key==FloatingKeyboard.SPLIT
        val floating=splitExample||on
        val p=Prefs.of(ctx)
        val layout=if(layoutLandscape!=null)V3Prefs.layout(p,layoutLandscape==true&&p.getBoolean(V3Prefs.ORIENTATION_LAYOUT,false)) else Prefs.layout(ctx)
        val background=0xffe6e8ed.toInt();val keyColor=0xffffffff.toInt();val modifier=0xffcdd0d8.toInt()
        val scene=RectF(x+8*d,28*d,x+w-8*d,height-25*d)
        rounded(c,scene,0xfffafbfc.toInt(),7f)
        // A few application lines make the keyboard's placement immediately visible.
        rounded(c,RectF(scene.left+7*d,scene.top+9*d,scene.right-18*d,scene.top+12*d),0xffe0e3e9.toInt(),1.5f)
        rounded(c,RectF(scene.left+7*d,scene.top+17*d,scene.left+scene.width()*.53f,scene.top+20*d),0xffe0e3e9.toInt(),1.5f)
        val split=if(splitExample)on else if(floating)p.getBoolean(FloatingKeyboard.SPLIT,false) else p.getBoolean(Prefs.SPLIT_LAYOUT,false)
        val scale=if(floating)FloatingKeyboard.height(ctx) else Prefs.heightScale(ctx)
        if(floating) {
            val windowWidth=scene.width()*if(splitExample).93f else .74f
            val windowHeight=scene.height()*if(splitExample).65f else .76f
            val left=if(splitExample)scene.centerX()-windowWidth/2 else scene.right-windowWidth-4*d
            val top=scene.top+(scene.height()-windowHeight)*.6f
            val window=RectF(left,top,left+windowWidth,top+windowHeight)
            rounded(c,RectF(window).apply {offset(0f,2*d)},0xffd2d5dd.toInt(),5f)
            rounded(c,window,background,5f)
            rounded(c,RectF(window.centerX()-10*d,window.top+4*d,window.centerX()+10*d,window.top+6*d),0xffa2a7b1.toInt(),1f)
            graphicIcons.draw(c,R.drawable.ic_close,window.right-6*d,window.top+6*d,6f,muted)
            silhouette.draw(c,RectF(window.left+2*d,window.top+10*d,window.right-2*d,window.bottom-3*d),layout,background,keyColor,modifier,
                split=split,landscape=splitExample,scale=scale,gapOverride=p.getInt(FloatingKeyboard.GAP,72).toFloat(),sizeOverride=p.getFloat(FloatingKeyboard.SIZE,1f))
        } else {
            val dock=RectF(scene.left+2*d,scene.top+24*d,scene.right-2*d,scene.bottom-2*d)
            silhouette.draw(c,dock,layout,background,keyColor,modifier,split=split,scale=scale)
        }
        val caption=if(splitExample) {if(on)"中间留出间距" else "完整悬浮键盘"} else {if(on)"独立窗口，可移动" else "键盘贴底显示"}
        text(c,caption,x+9*d,height-9*d,w-18*d,10f,muted)
    }
    private fun vibrationGraphic(c:Canvas,mode:Int) {
        val cx=width/2f;val cy=height*.38f
        paint.color=if(mode==2)muted else ink
        val block=if(mode==0)cx+17*d else cx
        c.drawRect(block-3*d,cy-11*d,block+3*d,cy+11*d,paint)
        if(mode==0) {
            graphicIcons.draw(c,R.drawable.ic_settings,cx-18*d,cy,20f,ink)
            paint.color=ink;paint.strokeWidth=1.5f*d;paint.strokeCap=Paint.Cap.ROUND
            c.drawLine(cx-4*d,cy,cx+8*d,cy,paint);c.drawLine(cx+8*d,cy,cx+4*d,cy-3*d,paint);c.drawLine(cx+8*d,cy,cx+4*d,cy+3*d,paint)
        } else if(mode==1) {
            paint.color=ink;paint.style=Paint.Style.STROKE;paint.strokeWidth=1.4f*d;paint.strokeCap=Paint.Cap.ROUND
            for(offset in listOf(-22f,22f)) {
                val path=android.graphics.Path();val left=cx+(offset-9)*d;path.moveTo(left,cy)
                for(step in 0..3){val start=left+step*4.5f*d;val dy=if(step%2==0)4*d else -4*d;path.cubicTo(start+1.2f*d,cy+dy,start+3.3f*d,cy+dy,start+4.5f*d,cy)}
                c.drawPath(path,paint)
            }
            paint.style=Paint.Style.FILL
        }
        val label=when(mode){0->"系统设置";1->"按键振动";else->"不振动"}
        paint.textSize=11*d;paint.color=if(mode==2)muted else ink
        c.drawText(label,cx-paint.measureText(label)/2,cy+28*d,paint)
    }
    private fun text(c:Canvas,t:String,x:Float,y:Float,max:Float,size:Float,color:Int,bold:Boolean=false) {
        paint.color=color;paint.textSize=size*d;paint.isFakeBoldText=bold
        val value=TextUtils.ellipsize(t,android.text.TextPaint(paint),max,TextUtils.TruncateAt.END).toString()
        c.drawText(value,x,y,paint);paint.isFakeBoldText=false
    }
    private fun panel(c:Canvas,x:Float,w:Float,label:String,value:Any,current:Boolean) {
        paint.color=tile;c.drawRoundRect(RectF(x,0f,x+w,height.toFloat()),12*d,12*d,paint)
        if(!choiceOnly)text(c,label,x+9*d,19*d,w-18*d,10f,if(current)accent else muted,true)
        if(previewGraphics(c,x,w,value,current))return
        val examples=examples(key,value)
        if(examples.size==3 && examples[0]=="keyboard") {
            val rows=if(key in listOf(Prefs.LAYOUT,V3Prefs.LAYOUT_LANDSCAPE) && value=="9")listOf(listOf("abc","def","ghi"),listOf("jkl","mno","pqrs"),listOf("tuv","wxyz","⌫")) else if(key in listOf(Prefs.LAYOUT,V3Prefs.LAYOUT_LANDSCAPE) && value=="14")listOf(listOf("qw","er","ty","ui","op"),listOf("as","df","gh","jk","l"),listOf("zx","cv","bn","m")) else listOf("qwertyuiop","asdfghjkl","zxcvbnm").map {row->row.map {it.toString()}}
            val split=(key==Prefs.SPLIT_LAYOUT && value==true) || key==V3Prefs.FILL_GAPS || key==V3Prefs.SPLIT_GAP
            if(key==Prefs.MODE) {
                if(value==2)rounded(c,RectF(x+6*d,27*d,x+w-6*d,80*d),0xff25262b.toInt())
                else if(value==0)rounded(c,RectF(x+w/2,27*d,x+w-6*d,80*d),0xff25262b.toInt())
            }
            if(key==V3Prefs.FILL_GAPS && value==false) {
                for(gy in 0..3)for(gx in 0..2)rounded(c,RectF(x+w/2-7*d+gx*5*d,28*d+gy*13*d,x+w/2-2*d+gx*5*d,41*d+gy*13*d),if((gx+gy)%2==0)0xffdedfe4.toInt() else 0xfff9fafc.toInt(),0f)
            }
            val left=if(key==V3Prefs.ONE_HAND && value==2)x+w*.25f else x+9*d
            val available=if(key==V3Prefs.ONE_HAND && value!=0)w*.67f else w-18*d
            val height=if(key==Prefs.HEIGHT_SCALE)(value as? Number)?.toFloat()?.coerceIn(.7f,1.4f) ?: 1f else 1f
            rows.forEachIndexed {n,row->
                val count=rows.maxOf {it.size};val gap=if(split)14*d else 0f;val cw=(available-gap)/count;row.forEachIndexed {i,ch->
                    val kx=left+cw*(i+(count-row.size)*.5f)+if(split&&i>=row.size/2)gap else 0f
                    val darkKey=key==Prefs.MODE&&(value==2||value==0&&kx>x+w/2)
                    paint.color=if(darkKey)0xff44464c.toInt() else 0xffffffff.toInt()
                    c.drawRoundRect(RectF(kx,30*d+n*15*d*height,kx+cw-2*d,42*d+n*15*d*height),2*d,2*d,paint)
                    text(c,ch.toString(),kx+2*d,39*d+n*15*d*height,cw,7f,if(darkKey)0xffeeeeee.toInt() else 0xff686b73.toInt())
                }
            }
            if(!choiceOnly)text(c,examples[2],x+9*d,98*d,w-18*d,10f,muted)
        } else {
            text(c,examples.first(),x+9*d,43*d,w-18*d,11f,muted)
            paint.color=if(current)0xffffffff.toInt() else 0xffd9dbe0.toInt()
            c.drawRoundRect(RectF(x+7*d,53*d,x+w-7*d,84*d),7*d,7*d,paint)
            text(c,examples[1],x+12*d,74*d,w-24*d,14f,0xff18191c.toInt(),true)

        }
    }
    private val nativeTab = PinyinTab(ctx) {}
    private val nativeCandidates by lazy { CandidateBar(ctx).apply { showCandidateExpand = false } }
    private fun rounded(c:Canvas,r:RectF,color:Int,radius:Float=6f) {
        paint.style=Paint.Style.FILL;paint.color=color;c.drawRoundRect(r,radius*d,radius*d,paint)
    }
    private fun previewGraphics(c:Canvas,x:Float,w:Float,value:Any,current:Boolean):Boolean {
        val on=value==true
        if (value is KeyboardForm) { keyboardFormGraphic(c, x, w, value); return true }
        if (key == FloatingKeyboard.SPLIT) { floatingGraphic(c, x, w, value); return true }
        if(key in listOf(Prefs.LAYOUT,V3Prefs.LAYOUT_LANDSCAPE,V3Prefs.ONE_HAND,Prefs.SPLIT_LAYOUT,V3Prefs.FILL_GAPS,Prefs.HEIGHT_SCALE,V3Prefs.SPLIT_SIZE,V3Prefs.SPLIT_GAP)) {layoutGraphic(c,x,w,value);return true}
        nativeTab.bubbleColor=if(current)null else 0xffd9dbe0.toInt()
        nativeTab.errorUnderlineColor = accent
        val y=if(choiceOnly)6*d else 30*d
        val white=if(current)0xffffffff.toInt() else 0xffd9dbe0.toInt();val black=0xff18191c.toInt();val gray=0xffb3b6be.toInt()
        fun field(label:String,top:Float=y,size:Float=14f) {
            rounded(c,RectF(x+8*d,top,x+w-8*d,top+27*d),white)
            text(c,label,x+13*d,top+19*d,w-26*d,size,black)
        }
        fun fieldWithCursor(prefix:String,suffix:String="",top:Float=y) {
            field(prefix,top,14f)
            val cursor=x+13*d+paint.measureText(prefix)
            paint.color=accent;paint.strokeWidth=d;paint.strokeCap=Paint.Cap.BUTT
            c.drawLine(cursor,top+4*d,cursor,top+23*d,paint)
            if (suffix.isNotEmpty()) {
                paint.getTextBounds(suffix, 0, suffix.length, glyphBounds)
                val suffixStart = cursor + paint.strokeWidth / 2f - glyphBounds.left
                text(c, suffix, suffixStart, top + 19*d, (x + w - 13*d - suffixStart).coerceAtLeast(0f), 14f, black)
            }
        }
        fun candidates(label:String,top:Float=y+35*d) {
            rounded(c,RectF(x+8*d,top,x+w-8*d,top+27*d),white)
            text(c,label,x+13*d,top+19*d,w-26*d,14f,black,true)
        }
        fun toolbar(top:Float,tall:Float=31f) {
            rounded(c,RectF(x+7*d,top,x+w-7*d,top+tall*d),white)
            val tools=listOf("more")+Prefs.tools(ctx)
            val slot=(w-20*d)/tools.size
            tools.forEachIndexed {index,id->
                graphicIcons.draw(c,Prefs.ICONS[id] ?: R.drawable.ic_settings,x+10*d+slot*(index+.5f),top+tall*d/2,(slot/d*.68f).coerceIn(10f,if(tall<25)13f else 18f),black)
            }
        }
        if (key == V3Prefs.COMPOSE_RESTORE || key == V3Prefs.COMPOSE_HOLD_CLEAR) {
            val restore = key == V3Prefs.COMPOSE_RESTORE
            fieldWithCursor(if (restore) "你好shi" else "ni'hao", top = y + 4*d)
            if (restore) {
                val start = x + 13*d
                paint.color = black
                paint.strokeWidth = d
                c.drawLine(start, y + 26*d, start + paint.measureText("你好"), y + 26*d, paint)
            }
            graphicIcons.draw(c, R.drawable.ic_del, x + 18*d, y + 48*d, 15f, black)
            text(c, if (restore) "退格一次" else "长按删除键", x + 31*d, y + 52*d, w - 54*d, 10f, muted)
            graphicIcons.draw(c, R.drawable.ic_chevron_down, x + w - 17*d, y + 48*d, 12f, muted)
            val result = if (restore) { if (on) "ni'hao'shi" else "你好sh" } else { if (on) "" else "ni'h" }
            fieldWithCursor(result, top = y + 62*d)
            val caption = if (restore) { if (on) "还原已选词的拼音" else "删去末尾字母" } else { if (on) "一次清空拼音" else "逐字母删除" }
            text(c, caption, x + 9*d, y + 106*d, w - 18*d, 10f, muted)
            return true
        }
        if(key==V3Prefs.DELETE_DIRECT) {
            fieldWithCursor("今天天气")
            rounded(c,RectF(x+8*d,y+31*d,x+w-8*d,y+51*d),white)
            text(c,"很好   明天   ⋯",x+13*d,y+45*d,w-26*d,11f,black,true)
            rounded(c,RectF(x+8*d,y+57*d,x+36*d,y+81*d),white)
            graphicIcons.draw(c,R.drawable.ic_del,x+22*d,y+69*d,17f,black)
            text(c,"按一次退格",x+44*d,y+73*d,w-65*d,10f,muted)
            graphicIcons.draw(c,R.drawable.ic_chevron_down,x+w-19*d,y+69*d,10f,muted)
            fieldWithCursor(if(on)"今天天" else "今天天气",top=y+87*d)
            toolbar(y+118*d,20f)
            text(c,if(on)"删去最后一个字" else "收起联想，保留正文",x+9*d,180*d,w-18*d,9f,muted)
            return true
        }
        if(key==Prefs.SMS_CODE) {
            if(!on)toolbar(30*d,26f)
            if(on) {
                val source="来自「信息」";paint.textSize=9*d;paint.color=muted
                c.drawText(source,x+(w-paint.measureText(source))/2,36*d,paint)
                val code="2046";paint.textSize=19*d;paint.color=black;paint.isFakeBoldText=true
                c.drawText(code,x+(w-paint.measureText(code))/2,56*d,paint);paint.isFakeBoldText=false
            }
            val gap=3*d;val kw=(w-16*d-9*gap)/10
            for(row in 0..1) {
                val count=10-row;val left=x+8*d+(kw+gap)*row/2
                for(index in 0 until count)rounded(c,RectF(left+index*(kw+gap),(65+row*20)*d,left+index*(kw+gap)+kw,(82+row*20)*d),white,3f)
            }
            return true
        }
        if(key==V3Prefs.READINGS || key==V3Prefs.GLOSS) {
            val mode=(value as Number).toInt()
            val reading=key==V3Prefs.READINGS
            for(index in 0..1) {
                val top=(30+index*52)*d
                val prompt=if(reading)if(index==0)"输入 nihao" else "输入 nuhao" else if(index==0)"短词 hello" else "长词 afternoon"
                text(c,prompt,x+7*d,top+7*d,w-14*d,9f,muted)
                val shown=mode==1 || mode==0&&if(reading)index==1 else index==0
                val annotation=if(!shown)null else if(reading)"nǐ hǎo" else if(index==0)"你好" else "下午"
                val word=if(reading)"你好" else if(index==0)"hello" else "afternoon"
                rounded(c,RectF(x+5*d,top+13*d,x+w-5*d,top+45*d),white)
                nativeCandidates.skin=Skins.KONGYE;nativeCandidates.uiScale=.68f;nativeCandidates.inputSpelling=""
                nativeCandidates.showCandidates(listOf(CandItem(word,annotation)),false)
                nativeCandidates.measure(MeasureSpec.makeMeasureSpec((w-10*d).toInt(),MeasureSpec.EXACTLY),0)
                nativeCandidates.layout(0,0,nativeCandidates.measuredWidth,nativeCandidates.measuredHeight)
                c.save();c.translate(x+5*d,top+13*d);nativeCandidates.draw(c);c.restore()
            }
            val caption=if(reading)when(mode){0->"仅输错时显示读音";1->"都显示读音";else->"不显示读音"}
                else when(mode){0->"短词显示翻译";1->"都显示翻译";else->"不显示翻译"}
            text(c,caption,x+7*d,143*d,w-14*d,9f,muted)
            return true
        }
        if(key==V3Prefs.CAPS_LOCK) {
            text(c,"输入 abc",x+9*d,39*d,w-18*d,11f,muted)
            rounded(c,RectF(x+8*d,49*d,x+38*d,79*d),white)
            graphicIcons.draw(c,if(on)R.drawable.ic_shift_lock else R.drawable.ic_shift_on,x+23*d,64*d,21f,black)
            paint.color=accent;paint.strokeWidth=1.5f*d
            c.drawLine(x+45*d,64*d,x+60*d,64*d,paint)
            c.drawLine(x+60*d,64*d,x+56*d,60*d,paint);c.drawLine(x+60*d,64*d,x+56*d,68*d,paint)
            text(c,if(on)"ABC" else "Abc",x+68*d,73*d,w-77*d,20f,black,true)
            text(c,if(on)"持续大写，再点大写键解除" else "只大写第一个字母",x+9*d,100*d,w-18*d,9f,muted)
            return true
        }
        if(key in listOf(Prefs.ASSOC,V3Prefs.HOMOPHONE) && !on) {
            text(c,if(key==Prefs.ASSOC)"已输入：今天" else "删除“在”",x+9*d,43*d,w-18*d,11f,muted)
            toolbar(y+23*d)
            return true
        }
        if(key==V3Prefs.PAIRS) {
            text(c,"输入左括号",x+9*d,43*d,w-18*d,11f,muted)
            fieldWithCursor("（",if(on)"）" else "",y+23*d)
            return true
        }
        if(key==V3Prefs.PUNCT_CONFIRM) {
            fieldWithCursor("nihao")
            val top=y+31*d
            rounded(c,RectF(x+8*d,top,x+w-8*d,top+28*d),white)
            rounded(c,RectF(x+10*d,top+3*d,x+77*d,top+25*d),if(current)0xffffeee7.toInt() else 0xffc9ccd2.toInt(),4f)
            text(c,"首选",x+13*d,top+18*d,23*d,9f,if(current)accent else muted)
            text(c,"你好",x+38*d,top+19*d,34*d,14f,black,true)
            text(c,"你  ⋯",x+84*d,top+19*d,w-95*d,12f,muted)
            val resultTop=y+66*d
            rounded(c,RectF(x+8*d,resultTop,x+34*d,resultTop+26*d),white)
            text(c,"，",x+14*d,resultTop+19*d,20*d,18f,black)
            val cy=resultTop+13*d
            paint.color=accent;paint.strokeWidth=1.5f*d;paint.strokeCap=Paint.Cap.ROUND
            c.drawLine(x+40*d,cy,x+55*d,cy,paint)
            c.drawLine(x+55*d,cy,x+51*d,cy-4*d,paint);c.drawLine(x+55*d,cy,x+51*d,cy+4*d,paint)
            rounded(c,RectF(x+62*d,resultTop,x+w-8*d,resultTop+26*d),white)
            text(c,if(on)"你好，" else "nihao，",x+66*d,resultTop+18*d,w-78*d,12.5f,black,true)
            text(c,if(on)"按逗号，先选中“你好”" else "按逗号，保留输入的字母",x+9*d,140*d,w-18*d,9f,muted)
            return true
        }
        if(key==V3Prefs.MIXED) {
            field("nihaohello",y,14f)
            graphicIcons.draw(c,R.drawable.ic_chevron_down,x+w/2,y+31*d,9f,muted)
            candidates(if(on)"你好hello" else "你好  你  ⋯",y+35*d)
            text(c,if(on)"中文与英文连续输入" else "关闭时只提供中文候选",x+9*d,105*d,w-18*d,9f,muted)
            return true
        }
        val feedback=key in listOf(V3Prefs.FEEDBACK,V3Prefs.CORRECT_SWAP,V3Prefs.CORRECT_NEAR,V3Prefs.CORRECT_EXTRA,V3Prefs.CORRECT_MISSING)
        nativeTab.drawOutline=!feedback
        if(feedback) {
            val raw=when(key){V3Prefs.CORRECT_SWAP->"jeude";V3Prefs.CORRECT_NEAR->"nuhao";V3Prefs.CORRECT_EXTRA->"nihaoo";V3Prefs.CORRECT_MISSING->"niho";else->"woianu"}
            val display=when(key){V3Prefs.CORRECT_SWAP->"jeu'de";V3Prefs.CORRECT_NEAR->"nu'hao";V3Prefs.CORRECT_EXTRA->"ni'haoo";V3Prefs.CORRECT_MISSING->"ni'ho";else->"wo'ia'nu"}
            val errors=BooleanArray(raw.length)
            if(on)when(key){V3Prefs.CORRECT_NEAR->errors[1]=true;V3Prefs.CORRECT_EXTRA->errors[5]=true;V3Prefs.CORRECT_MISSING->errors[2]=true;V3Prefs.FEEDBACK->errors[5]=true}
            val swaps=if(on)when(key){V3Prefs.CORRECT_SWAP->listOf(1);V3Prefs.FEEDBACK->listOf(2);else->emptyList()}else emptyList()
            nativeTab.uiScale=1.08f;nativeTab.skin=Skins.KONGYE;nativeTab.glideUnderline=false
            nativeTab.set(display,-1);nativeTab.feedback(SpellingAssist.Feedback(errors,swaps))
            nativeTab.measure(MeasureSpec.makeMeasureSpec((w-16*d).toInt(),MeasureSpec.AT_MOST),MeasureSpec.makeMeasureSpec((34*d).toInt(),MeasureSpec.AT_MOST))
            nativeTab.layout(0,0,nativeTab.measuredWidth,nativeTab.measuredHeight)
            c.save();c.translate(x+8*d,y-2*d);nativeTab.draw(c);c.restore()
            candidates(if(!on&&key!=V3Prefs.FEEDBACK)raw else if(key==V3Prefs.CORRECT_SWAP)"觉得" else if(key==V3Prefs.FEEDBACK)"我爱你" else "你好",y+35*d)
            return true
        }
        if(key==V3Prefs.PINYIN_POSITION) {
            val position=(value as Number).toInt()
            fieldWithCursor(if(position==2)"nihao" else "")
            val barTop = y + if (position == 0) 51*d else 35*d
            if (position == 0) {
                val spelling = "ni'hao"
                val top = y + 30*d
                paint.textSize = 12*d
                val bubbleWidth = paint.measureText(spelling) + 16*d
                val baseline = (top + barTop - paint.ascent() - paint.descent()) / 2
                rounded(c, RectF(x + 8*d, top, x + 8*d + bubbleWidth, barTop + 5*d), white, 6f)
                text(c, spelling, x + 16*d, baseline, bubbleWidth - 12*d, 12f, black)
            }
            nativeCandidates.skin=Skins.KONGYE;nativeCandidates.uiScale=.7f
            nativeCandidates.inputSpelling=if(position==1)"ni'hao" else ""
            nativeCandidates.showCandidates(listOf(CandItem("你好"),CandItem("你")),false)
            nativeCandidates.measure(MeasureSpec.makeMeasureSpec((w-16*d).toInt(),MeasureSpec.EXACTLY),0)
            nativeCandidates.layout(0,0,nativeCandidates.measuredWidth,nativeCandidates.measuredHeight)
            c.save();c.translate(x+8*d,barTop);nativeCandidates.draw(c);c.restore()
            return true
        }
        if (key in listOf(V3Prefs.GLIDE_ZH, V3Prefs.GLIDE_EN, V3Prefs.GLIDE_LIVE, V3Prefs.GLIDE_TRACE)) {
            val spelling = if (key == V3Prefs.GLIDE_EN) "hello" else "ni'hao"
            val sequence = spelling.filter { it.isLetter() }
            val tapping = !on && (key == V3Prefs.GLIDE_ZH || key == V3Prefs.GLIDE_EN)
            val spellingVisible = key != V3Prefs.GLIDE_LIVE || on
            val traceVisible = !tapping && (key != V3Prefs.GLIDE_TRACE || on)
            field(if (spellingVisible) spelling else "", y, 13f)
            if (spellingVisible && !tapping && key != V3Prefs.GLIDE_EN) {
                paint.getTextBounds(spelling, 0, spelling.length, glyphBounds)
                val textStart = x + 13*d
                paint.color = black
                paint.strokeWidth = d
                paint.strokeCap = Paint.Cap.BUTT
                c.drawLine(textStart + glyphBounds.left, y + 23*d, textStart + glyphBounds.right, y + 23*d, paint)
            }

            val keyboardTop = y + 35*d
            val keyWidth = (w - 16*d) / 10
            val keyHeight = 17*d
            val centers = mutableMapOf<Char, android.graphics.PointF>()
            listOf("qwertyuiop", "asdfghjkl", "zxcvbnm").forEachIndexed { row, letters ->
                val left = x + 8*d + (10 - letters.length) * keyWidth / 2
                val top = keyboardTop + row * keyHeight
                letters.forEachIndexed { col, letter ->
                    val keyLeft = left + col * keyWidth
                    val center = keyLeft + (keyWidth - d) / 2
                    centers[letter] = android.graphics.PointF(center, top + 7.5f*d)
                    val tapped = tapping && letter in sequence
                    val face = if (tapped) android.graphics.Color.rgb(252, 223, 212) else white
                    rounded(c, RectF(keyLeft, top, keyLeft + keyWidth - d, top + keyHeight - 1.5f*d), face, 3f)
                    text(c, letter.toString(), keyLeft + 2*d, top + 10.5f*d, keyWidth - d, 8f, if (tapped) black else muted)
                    if (tapped) {
                        paint.color = accent
                        c.drawCircle(keyLeft + keyWidth - 3.5f*d, top + 3.5f*d, 1.5f*d, paint)
                    }
                }
            }
            if (traceVisible) {
                val points = sequence.mapNotNull { centers[it] }
                val path = android.graphics.Path().apply {
                    moveTo(points.first().x, points.first().y)
                    for (point in points.drop(1)) lineTo(point.x, point.y)
                }
                paint.color = accent
                paint.strokeWidth = 2*d
                paint.strokeJoin = Paint.Join.ROUND
                paint.strokeCap = Paint.Cap.ROUND
                paint.style = Paint.Style.STROKE
                c.drawPath(path, paint)
                paint.style = Paint.Style.FILL
                c.drawCircle(points.last().x, points.last().y, 2.8f*d, paint)
            }
            val caption = when {
                tapping -> "逐键点击 ${sequence.toList().joinToString(" · ")}"
                !traceVisible -> "隐藏滑行轨迹"
                !spellingVisible -> "抬手后显示拼音"
                else -> "手指连滑 ${sequence.toList().joinToString(" · ")}"
            }
            text(c, caption, x + 9*d, y + 103*d, w - 18*d, 9f, muted)
            return true
        }
        if(key in listOf(Prefs.SWIPE,V3Prefs.SWIPE_REVERSE)) {
            val enabled=key!=Prefs.SWIPE||on
            val reverse=key==V3Prefs.SWIPE_REVERSE&&on
            val left=x+w/2-22*d
            rounded(c,RectF(left,y,left+44*d,y+61*d),white,9f)
            text(c,"Q",left+10*d,y+38*d,35*d,24f,black)
            if(enabled)text(c,"1",left+32*d,y+13*d,12*d,10f,gray)
            if(enabled) {
                val ax=left+57*d;val from=y+if(reverse)48*d else 12*d;val to=y+if(reverse)12*d else 48*d
                paint.color=accent;paint.strokeWidth=1.8f*d;paint.strokeCap=Paint.Cap.ROUND
                c.drawLine(ax,from,ax,to,paint)
                val tip=if(reverse)to+5*d else to-5*d
                c.drawLine(ax,to,ax-4*d,tip,paint);c.drawLine(ax,to,ax+4*d,tip,paint)
            }
            return true
        }
        if(key==V3Prefs.CLEAR_SCOPE) {
            val mode=(value as Number).toInt()
            val left=x+7*d
            val right=x+w-7*d
            fun document(top:Float,cleared:Boolean) {
                rounded(c,RectF(left,top,right,top+45*d),white,5f)
                val lines=if(!cleared)listOf("上一段","你好世界","下一段") else when(mode) {
                    1->listOf("世界","下一段")
                    2->listOf("上一段","","下一段")
                    else->emptyList()
                }
                lines.forEachIndexed {index,line->text(c,line,left+5*d,top+(12+13*index)*d,right-left-10*d,9f,black)}
                val line=if(cleared&&mode!=2)0 else 1
                val prefix=if(cleared)"" else "你好"
                paint.textSize=9*d
                val cursor=left+5*d+paint.measureText(prefix)
                paint.color=accent;paint.strokeWidth=1.3f*d
                c.drawLine(cursor,top+(3+13*line)*d,cursor,top+(13+13*line)*d,paint)
            }
            document(y,false)
            graphicIcons.draw(c,R.drawable.ic_chevron_down,x+w/2,y+51*d,9f,muted)
            document(y+57*d,true)
            text(c,when(mode){1->"保留光标后的文字";2->"保留前后段落";else->"清空全部文字"},x+9*d,145*d,w-18*d,8f,muted)
            return true
        }
        fun horizontalArrow(mid:Float,cy:Float,span:Float) {
            paint.color=accent;paint.strokeWidth=1.4f*d;paint.strokeCap=Paint.Cap.ROUND
            c.drawLine(mid-span,cy,mid+span,cy,paint)
            c.drawLine(mid-span,cy,mid-span+4*d,cy-4*d,paint);c.drawLine(mid-span,cy,mid-span+4*d,cy+4*d,paint)
            c.drawLine(mid+span,cy,mid+span-4*d,cy-4*d,paint);c.drawLine(mid+span,cy,mid+span-4*d,cy+4*d,paint)
        }
        if(key==V3Prefs.LETTER_LONG) {
            val mode=(value as Number).toInt()
            val cx=x+w/2
            val keyTop=y+39*d
            val keyBox=RectF(cx-16*d,keyTop,cx+16*d,keyTop+29*d)
            rounded(c,keyBox,white,6f)
            paint.style=Paint.Style.STROKE;paint.color=if(current)accent else muted;paint.strokeWidth=d
            c.drawRoundRect(keyBox,6*d,6*d,paint);paint.style=Paint.Style.FILL
            text(c,"a",cx-5*d,keyTop+21*d,20*d,19f,black)
            if(mode==0) {
                val key=Layouts.letters(true,ctx).flatMap {it.keys}.first {it.letters=="a"}
                val letters=key.letters.map {it.toString()}
                val upper=if(key.reversedSwipe)key.upAction else key.downAction
                val lower=if(key.reversedSwipe)key.downAction else key.upAction
                val options=(letters+letters.map {it.uppercase()}+listOfNotNull(key.hint.takeIf {upper?.command==null},key.hint2.takeIf {lower?.command==null})).distinct()
                val box=RectF(x+5*d,y,x+w-5*d,y+28*d)
                rounded(c,RectF(box.left,box.top+2*d,box.right,box.bottom+2*d),0x18000000,7f)
                rounded(c,box,white,7f)
                val slot=box.width()/options.size
                options.forEachIndexed {index,option->
                    val left=box.left+index*slot
                    if(index==1)rounded(c,RectF(left+2*d,box.top+3*d,left+slot-2*d,box.bottom-3*d),if(current)accent else muted,4f)
                    paint.textSize=13*d
                    val start=left+(slot-paint.measureText(option))/2
                    text(c,option,start,box.top+19*d,slot-2*d,13f,if(index==1)0xffffffff.toInt() else black)
                }
                graphicIcons.draw(c,R.drawable.ic_chevron_up,cx,y+34*d,9f,muted)
            } else if(mode==1) {
                fieldWithCursor("A")
                graphicIcons.draw(c,R.drawable.ic_chevron_up,cx,y+33*d,9f,muted)
            } else text(c,"不弹出菜单",x+9*d,y+19*d,w-18*d,11f,muted)
            text(c,when(mode){0->"按住后滑选";1->"直接输入 A";else->"松手输入 a"},x+9*d,107*d,w-18*d,9f,muted)
            return true
        }
        if(key==Prefs.SPACE_LONG) {
            val mode=(value as Number).toInt()
            field(if(mode==2)"" else "你好世界",y,14f)
            if(mode==0) {
                val cursor=x+13*d+paint.measureText("你好");paint.color=accent;paint.strokeWidth=1.5f*d;c.drawLine(cursor,y+4*d,cursor,y+23*d,paint)
            } else if(mode==1) {
                field("你好",y,14f)
                val start=x+16*d+paint.measureText("你好")
                rounded(c,RectF(start,y+5*d,start+8*d,y+22*d),gray,1f)
                rounded(c,RectF(start+11*d,y+5*d,start+19*d,y+22*d),gray,1f)
                paint.color=accent;paint.strokeWidth=1.5f*d;c.drawLine(start+22*d,y+4*d,start+22*d,y+23*d,paint)
            } else {
                graphicIcons.draw(c,R.drawable.ic_mic,x+24*d,y+13*d,18f,muted)
                paint.color=accent;paint.strokeWidth=2*d;paint.strokeCap=Paint.Cap.ROUND
                for(i in 0..4){val lineX=x+(45+i*7)*d;val half=(if(i%2==0)5 else 9)*d;c.drawLine(lineX,y+13*d-half,lineX,y+13*d+half,paint)}
            }
            val top=y+35*d;val left=x+34*d;val right=x+w-34*d;val mid=(left+right)/2
            rounded(c,RectF(x+8*d,top,x+30*d,top+31*d),white)
            rounded(c,RectF(left,top,right,top+31*d),white)
            rounded(c,RectF(x+w-30*d,top,x+w-8*d,top+31*d),white)
            text(c,"，",x+12*d,top+22*d,18*d,17f,black)
            text(c,"。",x+w-26*d,top+21*d,18*d,14f,black)
            val half=kotlin.math.min(17*d,(right-left)/4)
            paint.color=muted;paint.strokeWidth=1.6f*d;c.drawLine(mid-half,top+25*d,mid+half,top+25*d,paint)
            if(mode==0)horizontalArrow(mid,top+12*d,kotlin.math.min(20*d,(right-left)/2-5*d))
            paint.color=accent;paint.style=Paint.Style.STROKE;paint.strokeWidth=1.4f*d;c.drawCircle(mid,top+13*d,5*d,paint);paint.style=Paint.Style.FILL
            text(c,when(mode){1->"长按连续空格";2->"长按开关语音";else->"长按后左右滑动"},x+9*d,116*d,w-18*d,9f,muted)
            return true
        }
        if(key==V3Prefs.BOTTOM_CURSOR) {
            field("你好世界",y,14f)
            val cursor=x+13*d+paint.measureText("你好");paint.color=accent;paint.strokeWidth=1.5f*d;c.drawLine(cursor,y+4*d,cursor,y+23*d,paint)
            val top=y+35*d;val cy=top+17*d;val mid=x+w/2
            rounded(c,RectF(x+8*d,top,x+w-8*d,top+34*d),white)
            graphicIcons.draw(c,R.drawable.ic_chevron_down,x+23*d,cy,15f,muted)
            graphicIcons.draw(c,R.drawable.ic_clip,x+w-23*d,cy,15f,muted)
            paint.color=muted;paint.strokeWidth=2*d;paint.strokeCap=Paint.Cap.ROUND;c.drawLine(mid-15*d,cy+8*d,mid+15*d,cy+8*d,paint)
            if(on)horizontalArrow(mid,cy-5*d,kotlin.math.min(20*d,w/2-42*d))
            text(c,if(on)"左右滑动底部，移动光标" else "底部滑动不移动光标",x+9*d,116*d,w-18*d,9f,muted)
            return true
        }
        return false
    }

    private fun examples(k:String,v:Any):List<String> {
        if(k.startsWith("fuzzy:")) {
            val parts=k.substringAfter(':').split('-')
            return listOf("输入 ${parts[0]}",if(v==true)"匹配 ${parts.joinToString(" / ")}" else "仅匹配 ${parts[0]}")
        }
        val on=v==true;val n=(v as? Number)?.toInt() ?: -1
        return when(k) {
            V3Prefs.EMOJI->listOf("kaixin",if(on)"开心   😊" else "开心")
            V3Prefs.SYMBOLS->listOf("you",if(on)"右   👉   →" else "右   有")
            V3Prefs.KAOMOJI->listOf("kaixin",if(on)"开心   o(*≧▽≦)ツ" else "开心")
            V3Prefs.CORRECT_NEAR->listOf("nuhao",if(on)"你好" else "nihaoo")
            V3Prefs.CORRECT_SWAP->listOf("jeude",if(on)"觉得 · 顺序纠正" else "jeude")
            V3Prefs.CORRECT_EXTRA->listOf("nihaoo",if(on)"你好 · 多输纠正" else "nihaoo")
            V3Prefs.CORRECT_MISSING->listOf("niho",if(on)"你好 · 漏输纠正" else "niho")
            Prefs.ABBR->listOf("zg",if(on)"中国" else "zg")
            V3Prefs.MIXED->listOf("输入 nihaohello",if(on)"你好hello" else "只提供中文候选")
            V3Prefs.RECOGNIZE_ENGLISH->listOf("输入 github / claude",if(on)"GitHub / Claude" else "保留中文候选排序")
            V3Prefs.EN_CORRECT->listOf("hellp",if(on)"hello" else "hellp")
            V3Prefs.CAPS_LOCK->listOf("点击英文大写键后输入 abc",if(on)"ABC，保持大写直到手动解除" else "Abc，第一个字母后回到小写")
            V3Prefs.CAPS->listOf("新句子输入 hello",if(on)"Hello" else "hello")
            V3Prefs.EN_SPACE->listOf("选择单词 hello",if(on)"hello ␣" else "hello")
            V3Prefs.PUNCT_SPACE->listOf("输入英文标点",if(on)"Hello,␣world" else "Hello,world")
            V3Prefs.EMAIL->listOf("name@",if(on)"@qq.com   @gmail.com" else "name@")
            V3Prefs.SPACE_LATIN->listOf("中文 + English",if(on)"中文 English" else "中文English")
            V3Prefs.SPACE_DIGIT->listOf("输入 2026 年",if(on)"2026 年" else "2026年")
            V3Prefs.PUNCT_CONFIRM->listOf("输入 nihao，首选候选为你好，再按逗号",if(on)"选中首选并输入标点：你好，" else "保留字母并输入标点：nihao，")
            V3Prefs.PAIRS->listOf("输入左括号",if(on)"（|）" else "（|")
            Prefs.ASSOC->listOf("已输入：今天",if(on)"天气   下午   晚上" else "工具栏")
            V3Prefs.ASSOC_CONTINUOUS->listOf("今天 → 天气",if(n==1)"继续推荐：很好" else "本次联想结束")
            V3Prefs.ASSOC_SPACE->listOf("联想首选：天气",if(on)"空格 → 天气" else "空格 → ␣")
            V3Prefs.HOMOPHONE->listOf("删除“在”",if(on)"再   载   仔" else "工具栏")
            V3Prefs.GLIDE_ZH,V3Prefs.GLIDE_EN->if(on)listOf("手指连过按键","滑行后点候选确认") else listOf("逐键点击字母","输入文字")
            V3Prefs.GLIDE_LIVE->listOf("手指滑行中",if(on)"ni'hao   你好" else "抬手后显示结果")
            V3Prefs.GLIDE_TRACE->listOf("滑行轨迹",if(on)"q  ─╮  w  ╰─  e" else "q     w     e")
            V3Prefs.GLIDE_WORD_DELETE->listOf("ni'hao + 删除",if(on)"ni" else "ni'ha")
            V3Prefs.PINYIN_POSITION->listOf("ni'hao",when(n){1->"候选栏：ni'hao 你好";2->"应用输入框：nihao|";else->"上方气泡：ni'hao"})
            V3Prefs.READINGS->listOf("候选：你好",if(n==2)"你好" else "nǐ hǎo  ·  你好")
            V3Prefs.GLOSS->listOf("候选：hello",if(n==2)"hello" else "hello  ·  你好")
            V3Prefs.FEEDBACK->listOf("wo'ia'nu", "我爱你")
            V3Prefs.HAND_TONE->listOf("手写：行",if(on)"行  xíng / háng" else "行")
            V3Prefs.VOICE_PUNCT->listOf("今天天气很好",if(on)"今天天气很好。" else "今天天气很好")
            V3Prefs.VOICE_NUMBERS->listOf("一百二十元",if(on)"120元" else "一百二十元")
            V3Prefs.VOICE_CLEAN->listOf("嗯 我我今天去",if(on)"我今天去。" else "嗯，我我今天去。")
            V3Prefs.VOICE_POLISH->listOf("我今天去那个地方买菜",if(on)"今天我去那里买菜。" else "保留识别原文")
            V3Prefs.MEDIA->listOf("开始听写时",when(n){1->"暂停正在播放的音乐";2->"压低音乐音量";else->"保持播放音量"})
            V3Prefs.DRIVE->listOf("收起听写并发送",if(on)"返回输入框后接着听" else "手动再次开始听写")
            V3Prefs.CLEAR_SCOPE->listOf("上一段、你好世界、下一段，光标位于你好之后",when(n){1->"清空光标前文字";2->"仅清空你好世界，保留前后段落";else->"清空整个输入框"})
            V3Prefs.COMPOSE_RESTORE->listOf("已选「你好」并继续输入 shi 后退格", if (on) "还原为 ni'hao'shi" else "删去末尾 i，剩下你好sh")
            V3Prefs.COMPOSE_HOLD_CLEAR->listOf("输入 ni'hao 后长按删除键", if (on) "一次清空拼音" else "逐字母删除，例如剩下 ni'h")
            V3Prefs.DELETE_DIRECT->listOf("正文 + 联想词",if(on)"退格 → 直接删正文" else "第一次只收起联想")
            V3Prefs.DEL_UP,V3Prefs.DEL_DOWN,V3Prefs.DEL_LEFT,V3Prefs.DEL_RIGHT->listOf("在删除键上滑动",KeyGestures.commands[v.toString()] ?: v.toString())
            V3Prefs.VIBRATION->listOf("点击按键",when(n){1->"震动反馈 ≋";2->"无震动";else->"跟随系统触感"})
            Prefs.SOUND->listOf("点击按键",if(on)"♪ 按键音" else "静音")
            V3Prefs.BOTTOM_CURSOR->listOf("滑动底部留白",if(on)"文字 |  →  文 | 字" else "不移动光标")
            Prefs.SPACE_LONG->listOf("长按空格",when(n){1->"连续输入空格";2->"开关语音";else->"移动输入光标"})
            V3Prefs.LETTER_LONG->listOf("长按 a",when(n){1->"A";2->"无长按操作";else->"A  à  á  â  ä"})
            Prefs.SWIPE,V3Prefs.SWIPE_REVERSE->listOf("按键 Q 上下滑",if(k==Prefs.SWIPE&&!on)"仅输入 q" else if(k==V3Prefs.SWIPE_REVERSE&&on)"↑ 1    ↓ —" else "↑ —    ↓ 1")
            V3Prefs.CLIP_LIMIT->listOf("普通剪贴板历史", "最多 $v 条")
            V3Prefs.CLIP_DAYS->listOf("普通剪贴板历史",if(n==0)"一直保留" else "$v 天后移除")
            Prefs.SMS_CODE->listOf("新短信：验证码 123456",if(on)"只复制：123456" else "不自动复制")
            Prefs.FLOAT->listOf("键盘位置",if(on)"独立悬浮窗口，可移动" else "完整键盘贴底显示")
            FloatingKeyboard.SPLIT->listOf("悬浮键盘布局",if(on)"左右分体，中间留出间距" else "完整悬浮键盘")
            Prefs.MODE,Prefs.LAYOUT,V3Prefs.LAYOUT_LANDSCAPE,Prefs.SPLIT_LAYOUT,V3Prefs.ONE_HAND,Prefs.HEIGHT_SCALE,V3Prefs.SPLIT_SIZE,Prefs.PAD_L,Prefs.PAD_R,Prefs.PAD_B,V3Prefs.SPLIT_GAP,V3Prefs.FILL_GAPS->listOf("keyboard","",when(k){Prefs.SPLIT_LAYOUT->if(on)"中间留出分体间距" else "完整键盘";V3Prefs.ONE_HAND->when(n){1->"键盘靠左";2->"键盘靠右";else->"双手完整宽度"};else->"$title · $v"})
            Prefs.LEARN->listOf("多次选择同一个词",if(on)"记住常用选择" else "不学习选词")
            V3Prefs.ORIENTATION_LAYOUT->listOf("旋转屏幕",if(on)"使用横屏专属布局" else "沿用当前布局")
            else->listOf(title,when(v){true->"已开启";false->"已关闭";else->v.toString()})
        }
    }
}
