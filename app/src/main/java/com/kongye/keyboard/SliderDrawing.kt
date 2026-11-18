package com.kongye.keyboard

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import kotlin.math.roundToInt

/** Shared track and value layout for the settings page and the keyboard adjustment panel. */
internal class SliderDrawing {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bounds = RectF()

    private fun inset(width: Float, density: Float) = minOf(9f * density, width / 2f)

    fun fractionAt(x: Float, width: Float, density: Float): Float {
        val inset = inset(width, density)
        return ((x - inset) / (width - 2 * inset).coerceAtLeast(1f)).coerceIn(0f, 1f)
    }

    fun draw(
        canvas: Canvas, width: Float, centerY: Float, density: Float, fraction: Float,
        track: Int, accent: Int, foreground: Int, labelY: Float, labelSize: Float,
        value: String, labels: List<String> = emptyList(),
    ) {
        if (width <= 0f) return
        val radius = inset(width, density)
        val travel = width - 2 * radius
        val center = radius + travel * fraction
        bounds.set(0f, centerY - radius, width, centerY + radius)
        paint.color = track
        canvas.drawRoundRect(bounds, radius, radius, paint)
        bounds.right = center + radius
        paint.color = accent
        canvas.drawRoundRect(bounds, radius, radius, paint)

        val stops = labels.lastIndex
        if (stops > 0) {
            for (index in labels.indices) {
                val dot = radius + travel * index / stops
                paint.color = if (dot <= center) withAlpha(Color.WHITE, 72) else withAlpha(foreground, 48)
                canvas.drawCircle(dot, centerY, 2.5f * density, paint)
            }
        } else if (fraction < .5f) {
            paint.color = withAlpha(foreground, 48)
            canvas.drawCircle(width / 2, centerY, 2.5f * density, paint)
        }
        paint.color = Color.WHITE
        canvas.drawCircle(center, centerY, radius * .68f, paint)

        paint.textSize = labelSize
        if (stops > 0) {
            val longest = labels.maxOf { paint.measureText(it) }
            paint.textSize *= ((width / labels.size - 2 * density) / longest).coerceIn(.5f, 1f)
            val selected = (fraction * stops).roundToInt()
            labels.forEachIndexed { index, label ->
                drawLabel(canvas, label, radius + travel * index / stops, width, labelY, if (index == selected) accent else foreground, index == selected)
            }
        } else drawLabel(canvas, value, center, width, labelY, foreground, false)
    }

    private fun drawLabel(canvas: Canvas, label: String, center: Float, width: Float, y: Float, color: Int, bold: Boolean) {
        paint.typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        paint.color = color
        val labelWidth = paint.measureText(label)
        val left = (center - labelWidth / 2).coerceIn(0f, (width - labelWidth).coerceAtLeast(0f))
        val metrics = paint.fontMetrics
        canvas.drawText(label, left, y - (metrics.ascent + metrics.descent) / 2, paint)
        paint.typeface = Typeface.DEFAULT
    }
}
