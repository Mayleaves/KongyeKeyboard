package com.kongye.keyboard

import android.content.Context
import android.graphics.Canvas
import android.widget.LinearLayout

/** Scale a full-width keyboard uniformly to fit its settings card. */
class KeyboardPreviewLayout(context: Context) : LinearLayout(context) {
    private var previewScale = 1f
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val available = MeasureSpec.getSize(widthMeasureSpec)
        val keyboardWidth = resources.displayMetrics.widthPixels.coerceAtLeast(available)
        previewScale = available.toFloat() / keyboardWidth
        super.onMeasure(MeasureSpec.makeMeasureSpec(keyboardWidth, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
        setMeasuredDimension(available, (measuredHeight * previewScale).toInt())
    }
    override fun dispatchDraw(canvas: Canvas) {
        val save = canvas.save()
        canvas.scale(previewScale, previewScale)
        super.dispatchDraw(canvas)
        canvas.restoreToCount(save)
    }
}
