package com.kongye.keyboard

import android.view.inputmethod.ExtractedTextRequest
import android.view.inputmethod.InputConnection
import androidx.annotation.Keep

/** 用编辑器原生 composing 区预览语音或手写；确认才结束组字，取消恢复原选区。 */
@Keep
class VoiceDraft(private var connection: InputConnection) {
    private val selection = connection.getExtractedText(ExtractedTextRequest(), 0)?.let {
        if (it.selectionStart >= 0 && it.selectionEnd >= 0)
            (it.startOffset + it.selectionStart) to (it.startOffset + it.selectionEnd) else null
    }
    private val replaced = connection.getSelectedText(0)?.toString().orEmpty()
    private var touched = false
    private var closed = false
    private var text = ""

    fun rebind(connection: InputConnection) { this.connection = connection }

    private fun pinRegion(): Boolean {
        val range = selection ?: return true
        val start = minOf(range.first, range.second)
        val extracted = connection.getExtractedText(ExtractedTextRequest(), 0) ?: return true
        val local = start - extracted.startOffset
        val value = extracted.text ?: return true
        if (local < 0 || local + text.length > value.length || value.subSequence(local, local + text.length).toString() != text) return false
        return connection.setComposingRegion(start, start + text.length)
    }

    fun selectionAtPreviewEnd(start: Int, end: Int): Boolean {
        val range = selection ?: return true
        return start == end && start == minOf(range.first, range.second) + text.length
    }

    fun preview(value: String, underline: Boolean = false): Boolean {
        if (closed) return false
        if (value == text || !touched && value.isEmpty()) return true
        if (touched && !pinRegion()) return false
        val composing: CharSequence = if (underline) android.text.SpannableString(value).apply {
            if (isNotEmpty()) setSpan(android.text.style.UnderlineSpan(), 0, length,
                android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE or android.text.Spanned.SPAN_COMPOSING)
        } else value
        if (!connection.setComposingText(composing, 1)) return false
        touched = true; text = value
        return true
    }

    fun confirm() {
        if (closed) return
        closed = true
        if (touched) connection.finishComposingText()
    }

    fun cancel() = cancel(null)

    fun cancelKeepingSelection(start: Int, end: Int) = cancel(start to end)

    private fun cancel(keepSelection: Pair<Int, Int>?) {
        if (closed) return
        closed = true
        if (!touched) return
        if (!pinRegion()) { connection.finishComposingText(); return }
        connection.beginBatchEdit()
        connection.setComposingText(replaced, 1)
        connection.finishComposingText()
        if (keepSelection == null) {
            selection?.let { connection.setSelection(it.first, it.second) }
        } else {
            val start = selection?.let { minOf(it.first, it.second) }
            fun adjusted(position: Int): Int = when {
                start == null || position <= start -> position
                position >= start + text.length -> position + replaced.length - text.length
                else -> start + replaced.length
            }
            connection.setSelection(adjusted(keepSelection.first), adjusted(keepSelection.second))
        }
        connection.endBatchEdit()
    }
}
