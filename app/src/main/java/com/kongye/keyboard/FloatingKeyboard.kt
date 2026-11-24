package com.kongye.keyboard

import android.content.Context
import android.content.SharedPreferences

/** Floating dimensions use their own settings and the short display edge in landscape. */
object FloatingKeyboard {
    const val HEIGHT="float_height_scale"
    const val SPLIT="float_split_layout"
    const val GAP="float_split_gap"
    const val SIZE="float_split_size"
    const val LAND_WIDTH="float_land_width"
    const val LAND_X="float_land_x"
    const val LAND_Y="float_land_y"
    private val adjustmentDefaults=mapOf<String,Any>(Prefs.HEIGHT_SCALE to 1f, Prefs.PAD_L to 0, Prefs.PAD_R to 0,
        Prefs.PAD_B to 0, V3Prefs.SPLIT_GAP to 72, V3Prefs.SPLIT_SIZE to 1f,
        HEIGHT to 1f, SPLIT to false, GAP to 72, SIZE to 1f)
    private fun landscapeKey(key:String)=key+"_landscape"
    val keys=setOf(HEIGHT,SPLIT,GAP,SIZE,LAND_WIDTH,LAND_X,LAND_Y)+adjustmentDefaults.keys.map(::landscapeKey)
    fun adjustmentKey(ctx:Context,key:String)=if(V3Prefs.usesLandscapeLayout(ctx))landscapeKey(key) else key
    fun intValue(ctx:Context,key:String,default:Int)=Prefs.of(ctx).let {it.getInt(adjustmentKey(ctx,key),it.getInt(key,default))}
    fun floatValue(ctx:Context,key:String,default:Float)=Prefs.of(ctx).let {it.getFloat(adjustmentKey(ctx,key),it.getFloat(key,default))}
    fun split(ctx:Context)=Prefs.of(ctx).let {it.getBoolean(adjustmentKey(ctx,SPLIT),it.getBoolean(SPLIT,false))}
    fun rememberLandscape(ctx:Context,editor:SharedPreferences.Editor) {
        val p=Prefs.of(ctx)
        adjustmentDefaults.forEach {(key,default)->
            if(!p.contains(landscapeKey(key)))when(val value=p.all[key] ?: default) {
                is Int->editor.putInt(landscapeKey(key),value)
                is Float->editor.putFloat(landscapeKey(key),value)
                is Boolean->editor.putBoolean(landscapeKey(key),value)
            }
        }
    }
    fun landscape(ctx:Context)=ctx.resources.displayMetrics.widthPixels>ctx.resources.displayMetrics.heightPixels
    fun widthKey(ctx:Context)=if(landscape(ctx))LAND_WIDTH else Prefs.FLOAT_WIDTH
    fun xKey(ctx:Context)=if(landscape(ctx))LAND_X else Prefs.FLOAT_X
    fun yKey(ctx:Context)=if(landscape(ctx))LAND_Y else Prefs.FLOAT_Y
    fun widthRange(width:Float,height:Float):ClosedFloatingPointRange<Float> {
        val short=minOf(width,height)
        return short*.55f/width..if(width>height)minOf(.8f,short*1.15f/width) else 1f
    }
    fun defaultWidth(width:Float,height:Float)=minOf(width,height)*.86f/width
    fun width(ctx:Context):Float {
        val d=ctx.resources.displayMetrics
        return Prefs.of(ctx).getFloat(widthKey(ctx),defaultWidth(d.widthPixels.toFloat(),d.heightPixels.toFloat()))
            .coerceIn(widthRange(d.widthPixels.toFloat(),d.heightPixels.toFloat()))
    }
    fun height(ctx:Context)=floatValue(ctx,HEIGHT,1f).coerceIn(.7f,1.5f)
    fun newAdjustment(ctx:Context,floating:Boolean):Adjust {
        val p=Prefs.of(ctx);val d=ctx.resources.displayMetrics
        val w=width(ctx)
        return Adjust(if(floating)height(ctx) else Prefs.heightScale(ctx),Prefs.opacity(ctx),
            intValue(ctx,Prefs.PAD_L,0),intValue(ctx,Prefs.PAD_R,0),intValue(ctx,Prefs.PAD_B,0),
            if (floating) split(ctx) else V3Prefs.dockedSplit(ctx),floating,w,
            p.getFloat(xKey(ctx),d.widthPixels*(1-w)*if(landscape(ctx)).82f else .5f),
            p.getFloat(yKey(ctx),d.heightPixels*if(landscape(ctx)).18f else .45f),
            intValue(ctx,if(floating)GAP else V3Prefs.SPLIT_GAP,72).toFloat(),floatValue(ctx,if(floating)SIZE else V3Prefs.SPLIT_SIZE,1f))
    }
    fun save(ctx:Context,a:Adjust,e:SharedPreferences.Editor) {
        if(Prefs.of(ctx).getBoolean(V3Prefs.ORIENTATION_LAYOUT,false) && !V3Prefs.usesLandscapeLayout(ctx))rememberLandscape(ctx,e)
        if(a.floating) {
            e.putFloat(adjustmentKey(ctx,HEIGHT),a.scale).putBoolean(adjustmentKey(ctx,SPLIT),a.split).putInt(adjustmentKey(ctx,GAP),a.splitGap.toInt()).putFloat(adjustmentKey(ctx,SIZE),a.splitSize)
                .putFloat(widthKey(ctx),a.floatWidth).putFloat(xKey(ctx),a.floatX).putFloat(yKey(ctx),a.floatY)
        } else {
            V3Prefs.saveDockedSplit(ctx, a.split, e)
            e.putFloat(adjustmentKey(ctx,Prefs.HEIGHT_SCALE),a.scale)
                .putInt(adjustmentKey(ctx,Prefs.PAD_L),a.padL).putInt(adjustmentKey(ctx,Prefs.PAD_R),a.padR).putInt(adjustmentKey(ctx,Prefs.PAD_B),a.padB)
                .putInt(adjustmentKey(ctx,V3Prefs.SPLIT_GAP),a.splitGap.toInt()).putFloat(adjustmentKey(ctx,V3Prefs.SPLIT_SIZE),a.splitSize)
        }
    }
}
