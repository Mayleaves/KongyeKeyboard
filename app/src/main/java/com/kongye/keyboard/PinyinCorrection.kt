package com.kongye.keyboard

import kotlin.math.abs
import kotlin.math.min

/**
 * Symmetric-delete candidate retrieval, inspired by wolfgarbe/SymSpell (MIT).
 * This is an independent Kotlin implementation: packed primitive arrays keep the
 * mobile index small; every hash hit is verified against the complete spelling.
 */
internal class PinyinCorrection(words: Collection<String>) {
    private val spellings = words.distinct().sorted().toTypedArray()
    private val index: LongArray
    private val extras = LinkedHashSet<String>()

    init {
        var packed = LongArray(spellings.size * 29)
        var count = 0
        spellings.forEachIndexed { id, word ->
            for (key in deletes(word.take(7))) {
                if (count == packed.size) packed = packed.copyOf(maxOf(32, count * 2))
                packed[count++] = (hash(key) shl 32) or id.toLong()
            }
        }
        index = packed.copyOf(count).apply { sort() }
    }

    fun learn(word: String) { extras.add(word) }

    data class Match(val spelling: String, val penalty: Float, val mask: BooleanArray)

    fun lookup(input: String): List<Match> {
        if (input.length !in 4..32) return emptyList()
        val ids = HashSet<Int>()
        for (key in deletes(input.take(7))) {
            val h = hash(key)
            val start = h shl 32
            var lo = 0
            var hi = index.size
            while (lo < hi) {
                val mid = (lo + hi) ushr 1
                if (index[mid] < start) lo = mid + 1 else hi = mid
            }
            while (lo < index.size && index[lo] ushr 32 == h) ids.add(index[lo++].toInt())
        }
        val out = ArrayList<Match>()
        for (id in ids) match(input, spellings[id])?.let(out::add)
        for (word in extras) match(input, word)?.let(out::add)
        return out
    }

    private fun match(input: String, word: String): Match? {
        val maxCost = if (input.length >= 6) 28 else 14
        if (input == word || word.startsWith(input) || abs(input.length - word.length) * 14 > maxCost) return null
        val width = word.length + 1
        val cost = IntArray((input.length + 1) * width)
        val step = ByteArray(cost.size)
        for (i in 1..input.length) { cost[i * width] = i * 14; step[i * width] = 1 }
        for (j in 1..word.length) { cost[j] = j * 14; step[j] = 2 }
        for (i in 1..input.length) {
            var rowMin = cost[i * width]
            for (j in 1..word.length) {
                val at = i * width + j
                val same = input[i - 1] == word[j - 1]
                var best = cost[at - width - 1] + if (same) 0 else if (neighbors(input[i - 1], word[j - 1])) 12 else 45
                var action: Byte = 0
                val drop = cost[at - width] + 14
                if (drop < best) { best = drop; action = 1 }
                val add = cost[at - 1] + 14
                if (add < best) { best = add; action = 2 }
                if (i >= 2 && j >= 2 && input[i - 1] == word[j - 2] && input[i - 2] == word[j - 1]) {
                    val swap = cost[at - width * 2 - 2] + 9
                    if (swap < best) { best = swap; action = 3 }
                }
                cost[at] = best; step[at] = action
                rowMin = min(rowMin, best)
            }
            if (rowMin > maxCost) return null
        }
        val total = cost.last()
        if (total > maxCost) return null
        val mask = BooleanArray(word.length)
        var i = input.length
        var j = word.length
        var edits = 0
        while (i > 0 || j > 0) {
            when (step[i * width + j].toInt()) {
                1 -> { if (mask.isNotEmpty()) mask[min(j, mask.lastIndex)] = true; i--; edits++ }
                2 -> { mask[--j] = true; edits++ }
                3 -> { mask[j - 1] = true; mask[j - 2] = true; i -= 2; j -= 2; edits++ }
                else -> { if (input[i - 1] != word[j - 1]) { mask[j - 1] = true; edits++ }; i--; j-- }
            }
        }
        if (edits > if (input.length >= 6) 2 else 1) return null
        return Match(word, total * .65f, mask)
    }

    companion object {
        private fun hash(text: String) = (text.hashCode().toLong() and 0x7fffffffL)
        private fun deletes(text: String): Set<String> {
            val out = linkedSetOf(text)
            var level: Set<String> = setOf(text)
            repeat(2) {
                val next = HashSet<String>()
                for (s in level) for (i in s.indices) next.add(s.removeRange(i, i + 1))
                out.addAll(next); level = next
            }
            return out
        }
        // Match the actual stagger of the 26-key layout, including diagonal keys.
        private val points = buildMap<Char, Pair<Float, Int>> {
            listOf("qwertyuiop", "asdfghjkl", "zxcvbnm").forEachIndexed { row, letters ->
                letters.forEachIndexed { x, ch -> put(ch, (x + when (row) { 1 -> .5f; 2 -> 1.5f; else -> 0f }) to row) }
            }
        }
        private fun neighbors(a: Char, b: Char): Boolean {
            val p = points[a] ?: return false
            val q = points[b] ?: return false
            return abs(p.first - q.first) <= 1.25f && abs(p.second - q.second) <= 1
        }
    }
}
