package com.kongye.keyboard

import android.view.inputmethod.ExtractedTextRequest
import android.view.inputmethod.InputConnection
import androidx.annotation.Keep

/** 用编辑器原生 composing 区预览整次语音；确认才结束组字，取消恢复原选区。 */
@Keep
class VoiceDraft(private val connection: InputConnection) {
    private val selection = connection.getExtractedText(ExtractedTextRequest(), 0)?.let {
        if (it.selectionStart >= 0 && it.selectionEnd >= 0)
            (it.startOffset + it.selectionStart) to (it.startOffset + it.selectionEnd) else null
    }
    private val replaced = connection.getSelectedText(0)?.toString().orEmpty()
    private var touched = false
    private var closed = false
    private var text = ""

    private fun pinRegion(): Boolean {
        val range = selection ?: return true
        val start = minOf(range.first, range.second)
        val extracted = connection.getExtractedText(ExtractedTextRequest(), 0) ?: return true
        val local = start - extracted.startOffset
        val value = extracted.text ?: return true
        if (local < 0 || local + text.length > value.length || value.subSequence(local, local + text.length).toString() != text) return false
        return connection.setComposingRegion(start, start + text.length)
    }

    fun preview(value: String): Boolean {
        if (closed) return false
        if (value == text || !touched && value.isEmpty()) return true
        if (touched && !pinRegion()) return false
        if (!connection.setComposingText(value, 1)) return false
        touched = true; text = value
        return true
    }

    fun confirm() {
        if (closed) return
        closed = true
        if (touched) connection.finishComposingText()
    }

    fun cancel() {
        if (closed) return
        closed = true
        if (!touched) return
        if (!pinRegion()) { connection.finishComposingText(); return }
        connection.beginBatchEdit()
        connection.setComposingText(replaced, 1)
        connection.finishComposingText()
        selection?.let { connection.setSelection(it.first, it.second) }
        connection.endBatchEdit()
    }
}
