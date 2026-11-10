package com.kongye.keyboard

import android.content.Context

/** All tone-marked readings from mozillazg/pinyin-data, not an inferred spelling
 * of the current first candidate. In particular polyphonic characters keep all
 * readings, while handwriting never changes how the pinyin keyboard is decoded.
 */
object HandwritingPinyin {
    @Volatile private var readings: Map<String, String> = emptyMap()
    @Volatile private var phrases: Map<String, String> = emptyMap()
    @Synchronized fun load(ctx: Context) {
        if (readings.isNotEmpty()) return
        val data = HashMap<String, String>()
        ctx.assets.open("handwriting-readings.tsv").bufferedReader().useLines { lines ->
            lines.forEach { line ->
                val at = line.indexOf('\t')
                if (at > 0) data[line.substring(0, at)] = line.substring(at + 1)
            }
        }
        val words = HashMap<String, String>()
        ctx.assets.open("handwriting-phrases.tsv").bufferedReader().useLines { lines ->
            lines.forEach { line ->
                val at = line.indexOf('\t')
                if (at > 0) words[line.substring(0, at)] = line.substring(at + 1)
            }
        }
        phrases = words; readings = data
    }
    fun label(text: String): String {
        if (text.codePointCount(0, text.length) > 1) phrases[text]?.let { return it }
        val out = ArrayList<String>()
        var at = 0
        while (at < text.length) {
            val point = text.codePointAt(at)
            val char = String(Character.toChars(point))
            val py = readings[char] ?: Lexicon.pinyinOf(char)
            out.add(if (text.codePointCount(0, text.length) > 1 && " / " in py) "($py)" else py)
            at += Character.charCount(point)
        }
        return out.joinToString(" ")
    }
}
