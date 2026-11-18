package com.kongye.keyboard

/** Paragraph separators belong to the surrounding document, not the cleared paragraph. */
object ParagraphText {
    data class Range(val start: Int, val end: Int)
    fun separator(char: Char) = char in "\r\n\u2028\u2029"

    fun range(text: CharSequence, selectionStart: Int, selectionEnd: Int): Range {
        var start = minOf(selectionStart, selectionEnd).coerceIn(0, text.length)
        var end = maxOf(selectionStart, selectionEnd).coerceIn(0, text.length)
        while (start > 0 && !separator(text[start - 1])) start--
        while (end < text.length && !separator(text[end])) end++
        return Range(start, end)
    }
}
