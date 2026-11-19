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
    val keys=setOf(HEIGHT,SPLIT,GAP,SIZE,LAND_WIDTH,LAND_X,LAND_Y)
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
    fun height(ctx:Context)=Prefs.of(ctx).getFloat(HEIGHT,1f).coerceIn(.7f,1.5f)
    fun newAdjustment(ctx:Context,floating:Boolean):Adjust {
        val p=Prefs.of(ctx);val d=ctx.resources.displayMetrics
        val w=width(ctx)
        return Adjust(if(floating)height(ctx) else Prefs.heightScale(ctx),Prefs.opacity(ctx),
            p.getInt(Prefs.PAD_L,0),p.getInt(Prefs.PAD_R,0),p.getInt(Prefs.PAD_B,0),
            if (floating) p.getBoolean(SPLIT, false) else V3Prefs.dockedSplit(ctx),floating,w,
            p.getFloat(xKey(ctx),d.widthPixels*(1-w)*if(landscape(ctx)).82f else .5f),
            p.getFloat(yKey(ctx),d.heightPixels*if(landscape(ctx)).18f else .45f),
            p.getInt(if(floating)GAP else V3Prefs.SPLIT_GAP,72).toFloat(),p.getFloat(if(floating)SIZE else V3Prefs.SPLIT_SIZE,1f))
    }
    fun save(ctx:Context,a:Adjust,e:SharedPreferences.Editor) {
        if(a.floating) {
            e.putFloat(HEIGHT,a.scale).putBoolean(SPLIT,a.split).putInt(GAP,a.splitGap.toInt()).putFloat(SIZE,a.splitSize)
                .putFloat(widthKey(ctx),a.floatWidth).putFloat(xKey(ctx),a.floatX).putFloat(yKey(ctx),a.floatY)
        } else {
            V3Prefs.saveDockedSplit(ctx, a.split, e)
            e.putFloat(Prefs.HEIGHT_SCALE,a.scale)
                .putInt(Prefs.PAD_L,a.padL).putInt(Prefs.PAD_R,a.padR).putInt(Prefs.PAD_B,a.padB)
                .putInt(V3Prefs.SPLIT_GAP,a.splitGap.toInt()).putFloat(V3Prefs.SPLIT_SIZE,a.splitSize)
        }
    }
}
