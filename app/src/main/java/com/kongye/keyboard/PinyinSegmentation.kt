package com.kongye.keyboard

/** A spelling lattice for display and diagnostics. Native libime/Rime scores dictionary paths. */
@androidx.annotation.Keep
object PinyinSegmentation {
    @androidx.annotation.Keep
    data class Edge(val end: Int, val spelling: String)

    @androidx.annotation.Keep
    class Graph(val raw: String, val edges: List<List<Edge>>) {
        /** Enumerate on demand for diagnostics. The graph itself retains every legal edge. */
        fun paths(limit: Int = 64): List<List<String>> {
            val result = ArrayList<List<String>>()
            val path = ArrayList<String>()
            val reachesEnd = BooleanArray(raw.length + 1)
            reachesEnd[raw.length] = true
            for (at in raw.lastIndex downTo 0) reachesEnd[at] = edges[at].any { reachesEnd[it.end] }
            fun visit(at: Int) {
                if (result.size >= limit || !reachesEnd[at]) return
                if (at == raw.length) {
                    result.add(path.toList())
                    return
                }
                for (edge in edges[at]) {
                    if (edge.spelling.isNotEmpty()) path.add(edge.spelling)
                    visit(edge.end)
                    if (edge.spelling.isNotEmpty()) path.removeAt(path.lastIndex)
                }
            }
            visit(0)
            return result
        }

        fun longestPreferred(): List<String>? {
            val best = arrayOfNulls<List<String>>(raw.length + 1)
            best[raw.length] = emptyList()
            for (at in raw.lastIndex downTo 0) {
                for (edge in edges[at]) {
                    val tail = best[edge.end] ?: continue
                    val candidate = if (edge.spelling.isEmpty()) tail else listOf(edge.spelling) + tail
                    if (best[at] == null || candidate.size < best[at]!!.size) best[at] = candidate
                }
            }
            return best[0]
        }
    }

    fun graph(raw: String, syllables: Set<String>): Graph {
        val edges = List(raw.length + 1) { ArrayList<Edge>() }
        for (at in raw.indices) {
            if (raw[at] == '\'') {
                edges[at].add(Edge(at + 1, ""))
                continue
            }
            for (length in minOf(6, raw.length - at) downTo 1) {
                val part = raw.substring(at, at + length)
                if (part in syllables) edges[at].add(Edge(at + length, part))
            }
        }
        return Graph(raw, edges)
    }

    fun display(raw: String, syllables: Set<String>): String {
        // Preserve every explicit separator, including repeated/leading ones. Automatic
        // boundaries belong only to display; they never become input keys.
        if('\'' in raw)return raw.split('\'').joinToString("'") {displayChunk(it,syllables)}
        return displayChunk(raw,syllables)
    }

    fun boundaryTrace(raw:String,display:String):String {
        fun marks(value:String):List<Int> {var letters=0;return buildList {value.forEach {if(it=='\'')add(letters)else letters++}}}
        val manual=marks(raw);val remaining=manual.toMutableList()
        val automatic=marks(display).filter {if(it in remaining){remaining.remove(it);false}else true}
        return "raw=$raw display=$display manualBoundaries=$manual automaticDisplayBoundaries=$automatic literalPreserved=${raw.replace("'","")==display.replace("'","")} rule=legal-syllable-lattice-or-selected-reading"
    }

    private fun displayChunk(raw:String,syllables:Set<String>):String {
        val exact = graph(raw, syllables).longestPreferred()
        if (exact != null) return exact.joinToString("'") + if (raw.endsWith("'")) "'" else ""
        // A partial final or an initial abbreviation remains literal. Never lose input.
        val best = arrayOfNulls<List<String>>(raw.length + 1)
        val costs = IntArray(raw.length + 1) { Int.MAX_VALUE / 2 }
        best[raw.length] = emptyList()
        costs[raw.length] = 0
        val lattice = graph(raw, syllables)
        for (at in raw.lastIndex downTo 0) {
            val options = lattice.edges[at].toMutableList()
            val suffix = raw.substring(at)
            if ('\'' !in suffix && syllables.any { it.startsWith(suffix) }) {
                options.add(Edge(raw.length, suffix))
            }
            if (raw[at] != '\'') {
                for (initial in listOf("zh", "ch", "sh", raw[at].toString())) {
                    if (raw.startsWith(initial, at)) options.add(Edge(at + initial.length, initial))
                }
            }
            for (edge in options) {
                val tail = best[edge.end] ?: continue
                val penalty = if (edge.spelling.isEmpty()) 0 else if (edge.spelling in syllables) 1 else 100
                val cost = costs[edge.end] + penalty
                if (cost >= costs[at]) continue
                costs[at] = cost
                best[at] = if (edge.spelling.isEmpty()) tail else listOf(edge.spelling) + tail
            }
        }
        return (best[0]?.joinToString("'") ?: raw.trimEnd('\'')) + if (raw.endsWith("'")) "'" else ""
    }
}
