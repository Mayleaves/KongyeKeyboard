package com.kongye.keyboard

import android.app.Instrumentation
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.os.Bundle
import android.view.View
import java.io.File

object Layout400Regression {
    fun run(i: Instrumentation) {
        var error: Throwable? = null
        i.runOnMainSync {
            try {
                val ctx = i.targetContext
                val density = ctx.resources.displayMetrics.density
                val folder = File(ctx.getExternalFilesDir(null), "layout400").apply { mkdirs() }
                val keys = (V3Prefs::class.java.fields + Prefs::class.java.fields).filter {
                    it.type == String::class.java && java.lang.reflect.Modifier.isStatic(it.modifiers)
                }.map { it.get(null) as String }.distinct() + Prefs.FUZZY_RULES.map { "fuzzy:" + it.first }
                val failures = mutableSetOf<String>()
                for (width in listOf(264, 320, 392, 460)) {
                    for (key in keys) {
                        val h = SettingExampleView.comparisonHeightDp(key)
                        val bitmap = Bitmap.createBitmap((width*density).toInt(), (h*density).toInt(), Bitmap.Config.ARGB_8888)
                        val canvas = object : Canvas(bitmap) {
                            override fun drawText(text: String, x: Float, y: Float, paint: Paint) {
                                val bounds = clipBounds
                                if (y + paint.fontMetrics.descent > bounds.bottom + 1 && text.isNotBlank()) {
                                    failures.add("$key at $width dp: $text below ${bounds.bottom/density}")
                                }
                                super.drawText(text, x, y, paint)
                            }
                        }
                        val view = SettingExampleView(ctx)
                        val numeric = key in setOf(V3Prefs.READINGS, V3Prefs.GLOSS, V3Prefs.PINYIN_POSITION,
                            V3Prefs.CLEAR_SCOPE, V3Prefs.LETTER_LONG, Prefs.SPACE_LONG, V3Prefs.ONE_HAND, Prefs.HEIGHT_SCALE)
                        view.measure(View.MeasureSpec.makeMeasureSpec(bitmap.width, View.MeasureSpec.EXACTLY),
                            View.MeasureSpec.makeMeasureSpec(bitmap.height, View.MeasureSpec.EXACTLY))
                        view.layout(0, 0, bitmap.width, bitmap.height)
                        val values: List<Any> = if(numeric) listOf(0,1,2) else listOf(false,true)
                        for (value in values) {
                            view.submit(key, key, value, value, value.toString(), value.toString())
                            view.draw(canvas)
                        }
                        view.submit(key, key, if(numeric) 0 else false, if(numeric) 1 else true, "关闭", "开启")
                        view.draw(canvas)
                        if (key == V3Prefs.PUNCT_CONFIRM || key == V3Prefs.COMPOSE_RESTORE || key == V3Prefs.BOTTOM_CURSOR) {
                            File(folder, "$width-$key.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                        }
                        bitmap.recycle()
                    }
                }
                check(failures.isEmpty()) { failures.joinToString("\n") }
            } catch (t: Throwable) {
                error = t
            }
        }
        error?.let { throw it }
        i.sendStatus(0, Bundle().apply { putString("stream", "SETTINGS EXAMPLE TEXT BOUNDS PASSED at 264/320/392/460 dp\n") })
    }
}
