package com.kongye.keyboard

internal object ResponsiveSize {
    fun keyboardScale(widthDp: Float, heightDp: Float): Float {
        val widthScale = (widthDp / 460f).coerceIn(.80f, 1f)
        val heightScale = (heightDp / 780f).coerceIn(.78f, 1f)
        return minOf(widthScale, heightScale)
    }
}
