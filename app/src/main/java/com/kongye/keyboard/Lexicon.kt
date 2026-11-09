package com.kongye.keyboard

import android.content.Context
import java.io.File
import java.io.InputStream
import kotlin.math.abs
import kotlin.math.log2
import kotlin.math.min

/** 一条字或词。p 是去掉分隔的拼音字母，syl 是音节，ini 是每个音节的首字母（简拼用） */
class Entry(val syl: Array<String>, val w: String, val sc: Int, val user: Boolean = false) {
    val p: String = syl.joinToString("")
    val ini: String = String(CharArray(syl.size) { syl[it][0] })
    val key: String get() = "$w\t$p"
}

/**
 * 候选：n 是它吃掉了多少个输入键；abbr 表示按简拼匹配；
 * fix 表示是纠错出来的，py 是要显示的拼音（音节间空格），mask 标出 py 里打错的字母（标红）
 */
class Cand(
    val w: String, val n: Int, val e: Entry?, val score: Float,
    val abbr: Boolean = false, val py: String? = null, val mask: BooleanArray? = null,
    /** 由几个词拼成的整句，选中后记成新词 */
    val sentence: Boolean = false,
) {
    val fix get() = mask != null
}

/**
 * 拼音词库。词库文本由 tools/build_lexicon.py 从空叶阅读的 dict.db 生成，放在 assets/lexicon.txt。
 * 用户选词次数和自造词存在 filesDir/user_lexicon.txt。
 *
 * 输入用“键序列”表示：每个元素是一个键对应的字母集合（全键盘是一个字母，14 键两个，九键三四个），
 * "'" 是分词符。
 */
object Lexicon {
    @Volatile
    var ready = false
        private set

    private var sorted: Array<Entry> = emptyArray()
    private val exact = HashMap<String, ArrayList<Entry>>()
    private val initials = HashMap<String, ArrayList<Entry>>()
    private val byHead = HashMap<Char, ArrayList<Entry>>()
    /** 单字 → 最常用的读音，手写候选显示拼音用 */
    private val charPy = HashMap<Char, String>()
    /** 所有词，分词用 */
    private val words = HashSet<String>()
    private var syllables: List<String> = emptyList()
    private var sylSet: Set<String> = emptySet()
    private val userFreq = HashMap<String, Int>()
    private val userWords = ArrayList<Entry>()
    private var dirty = false
    private var userFile: File? = null

    fun load(ctx: Context) {
        if (ready) return
        loadFrom(ctx.assets.open("lexicon.txt"), File(ctx.filesDir, "user_lexicon.txt"))
    }

    /** 拆出来是为了能在电脑上用 JVM 直接测引擎（tools/engine_check） */
    fun loadFrom(input: InputStream, uf: File?) {
        if (ready) return
        synchronized(this) {
            if (ready) return
            val list = ArrayList<Entry>(72000)
            input.bufferedReader().use { br ->
                syllables = br.readLine().split(' ').filter { it.isNotEmpty() }
                sylSet = HashSet(syllables)
                br.forEachLine { line ->
                    val a = line.indexOf(' ')
                    val b = line.indexOf(' ', a + 1)
                    if (a > 0 && b > a) {
                        val syl = line.substring(0, a).split('\'').toTypedArray()
                        list.add(Entry(syl, line.substring(a + 1, b), line.substring(b + 1).trim().toIntOrNull() ?: 0))
                    }
                }
            }
            userFile = uf
            if (uf != null && uf.exists()) {
                runCatching {
                    uf.forEachLine { line ->
                        val f = line.split('\t')
                        if (f.size < 4) return@forEachLine
                        val cnt = f[3].toIntOrNull() ?: return@forEachLine
                        if (f[0] == "P") {
                            val e = Entry(f[2].split('\'').toTypedArray(), f[1], 40, user = true)
                            userWords.add(e)
                            list.add(e)
                            userFreq[e.key] = cnt
                        } else {
                            userFreq["${f[1]}\t${f[2]}"] = cnt
                        }
                    }
                }
            }
            // 单字读音：同一个字取分数最高的读音
            val best = HashMap<Char, Entry>()
            for (e in list) if (e.w.length == 1) {
                val old = best[e.w[0]]
                if (old == null || e.sc > old.sc) best[e.w[0]] = e
            } else words.add(e.w)
            for ((ch, e) in best) charPy[ch] = e.p
            list.sortWith(compareBy<Entry> { it.p }.thenByDescending { it.sc })
            sorted = list.toTypedArray()
            for (e in sorted) {
                exact.getOrPut(e.p) { ArrayList(2) }.add(e)
                if (e.w.length > 1) {
                    initials.getOrPut(e.ini) { ArrayList(4) }.add(e)
                    byHead.getOrPut(e.w[0]) { ArrayList(8) }.add(e)
                }
            }
            val bySc = Comparator<Entry> { x, y -> y.sc - x.sc }
            exact.values.forEach { it.sortWith(bySc) }
            initials.values.forEach { it.sortWith(bySc) }
            for ((k, v) in byHead) {
                v.sortWith(bySc)
                if (v.size > 800) byHead[k] = ArrayList(v.subList(0, 800))
            }
            ready = true
        }
    }

    private fun boost(e: Entry): Float {
        val c = userFreq[e.key] ?: return 0f
        return min(28f, 7f * log2(1f + c))
    }

    private fun lowerBound(s: String): Int {
        var lo = 0
        var hi = sorted.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (sorted[mid].p < s) lo = mid + 1 else hi = mid
        }
        return lo
    }

    /** 键序列 → 字母集合列表和分词位置 */
    fun split(keys: List<String>): Pair<List<String>, List<Int>> {
        val sets = ArrayList<String>()
        val marks = ArrayList<Int>()
        for (k in keys) {
            if (k == "'") { if (sets.isNotEmpty() && marks.lastOrNull() != sets.size) marks.add(sets.size) } else sets.add(k)
        }
        return sets to marks
    }

    /** 兼容旧接口：字符串里的 ' 是分词符 */
    fun splitMarks(raw: String): Pair<String, List<Int>> {
        val (sets, marks) = split(raw.map { it.toString() })
        return sets.joinToString("") to marks
    }

    private fun fitsMarks(e: Entry, k: Int, marks: List<Int>): Boolean {
        if (marks.isEmpty()) return true
        for (m in marks) {
            if (m >= k) continue
            var acc = 0
            var ok = false
            for (x in e.syl) { acc += x.length; if (acc == m) { ok = true; break }; if (acc > m) break }
            if (!ok) return false
        }
        return true
    }

    private fun fitsSets(p: String, sets: List<String>, k: Int): Boolean {
        for (i in 0 until k) if (sets[i].indexOf(p[i]) < 0) return false
        return true
    }

    fun candidates(raw: String, abbr: Boolean = true): List<Cand> = candidates(raw.map { it.toString() }, abbr)

    fun candidates(keys: List<String>, abbr: Boolean = true, fuzzy: Boolean = true): List<Cand> {
        val (sets, marks) = split(keys)
        val n = sets.size
        if (n == 0) return emptyList()
        val single = sets.all { it.length == 1 }
        if (!ready) return listOf(Cand(sets.joinToString("") { it.take(1) }, n, null, 0f))
        val a = ArrayList<Cand>()
        val b = ArrayList<Cand>()
        val c = ArrayList<Cand>()
        if (single) {
            val s = sets.joinToString("")
            var i = lowerBound(s)
            while (i < sorted.size && sorted[i].p.startsWith(s)) {
                val e = sorted[i]
                val l = e.p.length
                if (fitsMarks(e, n, marks)) a.add(Cand(e.w, n, e, e.sc + boost(e) + if (l == n) 6f else -(l - n) * 0.8f))
                i++
            }
            if (abbr && marks.isEmpty() && n in 2..8) initials[s]?.let { list ->
                for (e in list.take(60)) a.add(Cand(e.w, n, e, e.sc + boost(e) - 6f, abbr = true))
            }
            for (k in n - 1 downTo 1) {
                exact[s.substring(0, k)]?.let { list ->
                    for (e in list.take(40)) if (fitsMarks(e, k, marks)) b.add(Cand(e.w, k, e, e.sc + boost(e) + k * 16f))
                }
            }
            // 没有正好对上的字词时：拼一个整句候选（你+还），再给纠错候选
            if (marks.isEmpty() && n >= 3 && a.none { it.e != null && it.e.p.length == n && !it.abbr }) {
                sentence(s)?.let { a.add(it) }
                if (fuzzy && n >= 4) c.addAll(corrections(s))
            }
        } else {
            // 14 键、九键：一个键多个字母，按首键的每个字母在有序表里扫一段
            for (ch in sets[0]) {
                var i = lowerBound(ch.toString())
                while (i < sorted.size && sorted[i].p[0] == ch) {
                    val e = sorted[i]
                    val l = e.p.length
                    if (l >= n) {
                        if (fitsSets(e.p, sets, n) && fitsMarks(e, n, marks)) a.add(Cand(e.w, n, e, e.sc + boost(e) + if (l == n) 6f else -(l - n) * 0.8f))
                    } else if (fitsSets(e.p, sets, l) && fitsMarks(e, l, marks)) {
                        b.add(Cand(e.w, l, e, e.sc + boost(e) + l * 16f))
                    }
                    i++
                }
            }
        }
        a.sortByDescending { it.score }
        b.sortByDescending { it.score }
        c.sortByDescending { it.score }
        val out = ArrayList<Cand>()
        val seen = HashSet<String>()
        // 纠错的排在完全匹配的前几个之后
        val head = a.take(150)
        for (x in head.take(3)) if (seen.add(x.w)) out.add(x)
        for (x in c.take(6)) if (seen.add(x.w)) out.add(x)
        for (x in head.drop(3)) if (seen.add(x.w)) out.add(x)
        for (x in b.take(100)) if (seen.add(x.w)) out.add(x)
        if (out.isEmpty()) out.add(Cand(decode(sets).joinToString(""), n, null, 0f))
        return out
    }

    /** 整句：从左到右每次取能对上的最长、最常用的词，拼成一句；有一段对不上就放弃 */
    private fun sentence(s: String): Cand? {
        val syl = ArrayList<String>()
        val sb = StringBuilder()
        var i = 0
        var parts = 0
        var score = 0f
        while (i < s.length) {
            var found: Entry? = null
            for (k in min(12, s.length - i) downTo 1) {
                val e = exact[s.substring(i, i + k)]?.maxByOrNull { it.sc + boost(it) } ?: continue
                found = e
                break
            }
            val e = found ?: return null
            sb.append(e.w)
            syl.addAll(e.syl)
            score += e.sc
            i += e.p.length
            parts++
        }
        if (parts < 2) return null
        val entry = Entry(syl.toTypedArray(), sb.toString(), (score / parts).toInt(), user = true)
        return Cand(entry.w, s.length, entry, 200f, sentence = true)
    }

    /**
     * 纠错：拼音和输入只差一个字母（替换、多一个、少一个）的常用词；
     * mask 标出拼音里和输入不一样的那个字母
     */
    private fun corrections(s: String): List<Cand> {
        val out = ArrayList<Cand>()
        val n = s.length
        var i = lowerBound(s.substring(0, 1))
        val first = s[0]
        while (i < sorted.size && sorted[i].p[0] == first) {
            val e = sorted[i++]
            val p = e.p
            if (e.w.length < 2 || abs(p.length - n) > 1 || p == s || p.startsWith(s)) continue
            val bad = oneEdit(s, p) ?: continue
            val mask = BooleanArray(p.length)
            for (j in bad) if (j in mask.indices) mask[j] = true
            out.add(Cand(e.w, n, e, e.sc + boost(e) - 4f, py = e.syl.joinToString(" "), mask = spaced(mask, e.syl)))
        }
        return out.sortedByDescending { it.score }.take(12)
    }

    /** 差一个字母时返回拼音里不一样的位置，否则 null */
    private fun oneEdit(s: String, p: String): List<Int>? {
        if (s.length == p.length) {
            var diff = -1
            for (j in s.indices) if (s[j] != p[j]) { if (diff >= 0) return null; diff = j }
            return if (diff >= 0) listOf(diff) else null
        }
        if (p.length == s.length + 1) {
            // 拼音多一个字母：漏打了
            var j = 0
            while (j < s.length && s[j] == p[j]) j++
            return if (s.substring(j) == p.substring(j + 1)) listOf(j) else null
        }
        if (p.length == s.length - 1) {
            // 多打了一个字母：标出拼音里多打位置后的那个字母
            var j = 0
            while (j < p.length && s[j] == p[j]) j++
            return if (s.substring(j + 1) == p.substring(j)) listOf(min(j, p.length - 1)) else null
        }
        return null
    }

    /** 把按字母的 mask 展开成带空格的拼音（音节间一个空格）的 mask */
    private fun spaced(mask: BooleanArray, syl: Array<String>): BooleanArray {
        val out = ArrayList<Boolean>()
        var k = 0
        for ((si, x) in syl.withIndex()) {
            if (si > 0) out.add(false)
            repeat(x.length) { out.add(mask.getOrElse(k++) { false }) }
        }
        return out.toBooleanArray()
    }

    /** 把字母集合序列切成音节（九键、14 键会选最可能的字母），最后一段可以是半个音节 */
    fun decode(sets: List<String>): List<String> {
        val n = sets.size
        val fail = BooleanArray(n + 1)
        val bySyl = syllables
        fun match(x: String, i: Int, len: Int): Boolean {
            for (j in 0 until len) if (sets[i + j].indexOf(x[j]) < 0) return false
            return true
        }
        fun dfs(i: Int): List<String>? {
            if (i == n) return emptyList()
            if (fail[i]) return null
            val rem = n - i
            for (len in min(6, rem) downTo 1) {
                for (x in bySyl) {
                    if (x.length != len || !match(x, i, len)) continue
                    val r = dfs(i + len)
                    if (r != null) return listOf(x) + r
                }
            }
            for (x in bySyl) if (x.length > rem && match(x, i, rem)) return listOf(x.substring(0, rem))
            fail[i] = true
            return null
        }
        return dfs(0) ?: listOf(sets.joinToString("") { it.take(1) })
    }

    fun decode(s: String): List<String> = decode(s.map { it.toString() })

    /** 输入框里显示的拼音，音节之间加 ' */
    fun display(keys: List<String>, top: Cand?): String {
        val (sets, marks) = split(keys)
        if (sets.isEmpty()) return if (keys.isEmpty()) "" else "'"
        val out = ArrayList<String>()
        var pos = 0
        if (top?.e != null && top.abbr) {
            sets.forEach { out.add(it.take(1)) }
            pos = sets.size
        } else if (top?.e != null && !top.fix) {
            var acc = 0
            for (x in top.e.syl) {
                if (acc >= top.n) break
                val take = min(x.length, top.n - acc)
                out.add(x.substring(0, take))
                acc += take
            }
            pos = min(top.n, sets.size)
        }
        if (pos < sets.size) {
            val cuts = (listOf(pos) + marks.filter { it > pos } + sets.size).distinct()
            for (j in 0 until cuts.size - 1) out.addAll(decode(sets.subList(cuts[j], cuts[j + 1])))
        }
        val text = out.joinToString("'")
        return if (keys.lastOrNull() == "'") "$text'" else text
    }

    fun display(raw: String, top: Cand?): String = display(raw.map { it.toString() }, top)

    /** 上屏后的联想：以刚上屏的词开头的常用词，取后半截 */
    fun assoc(w: String): List<String> {
        if (!ready || w.isEmpty() || w.last() !in '一'..'鿿') return emptyList()
        val out = LinkedHashSet<String>()
        fun add(prefix: String) {
            for (e in byHead[prefix[0]] ?: return) {
                if (out.size >= 12) return
                if (e.w.length > prefix.length && e.w.startsWith(prefix)) out.add(e.w.substring(prefix.length))
            }
        }
        add(w)
        if (out.size < 6 && w.length > 1) add(w.last().toString())
        return out.toList()
    }

    /** 每个字的拼音，空格隔开；认不出的字照原样 */
    fun pinyinOf(text: String): String = text.map { ch -> charPy[ch] ?: ch.toString() }.joinToString(" ")

    /** 分词：汉字按词库正向最大匹配（最长 6 个字），英文、数字成段，标点单独 */
    fun segment(text: String): List<String> {
        val out = ArrayList<String>()
        var i = 0
        while (i < text.length) {
            val ch = text[i]
            when {
                ch in '一'..'鿿' -> {
                    var len = min(6, text.length - i)
                    while (len > 1) {
                        val sub = text.substring(i, i + len)
                        if (sub.all { it in '一'..'鿿' } && sub in words) break
                        len--
                    }
                    out.add(text.substring(i, i + len)); i += len
                }
                ch.isLetterOrDigit() -> {
                    var j = i
                    while (j < text.length && (text[j].isLetterOrDigit() && text[j] !in '一'..'鿿' || text[j] == '.' && j + 1 < text.length && text[j + 1].isDigit())) j++
                    out.add(text.substring(i, j)); i = j
                }
                ch.isWhitespace() -> i++
                else -> { out.add(ch.toString()); i++ }
            }
        }
        return out
    }

    fun recordPick(e: Entry) {
        userFreq[e.key] = (userFreq[e.key] ?: 0) + 1
        dirty = true
    }

    /** 分几次选出来的一整句，记成一个新词 */
    fun learnPhrase(w: String, syl: List<String>) {
        if (!ready || w.length < 2 || w.length > 8 || syl.size != w.length) return
        val p = syl.joinToString("")
        exact[p]?.firstOrNull { it.w == w }?.let { recordPick(it); return }
        val e = Entry(syl.toTypedArray(), w, 40, user = true)
        userWords.add(e)
        words.add(w)
        exact.getOrPut(p) { ArrayList(2) }.add(0, e)
        initials.getOrPut(e.ini) { ArrayList(4) }.add(0, e)
        byHead.getOrPut(e.w[0]) { ArrayList(8) }.add(0, e)
        val i = lowerBound(p)
        sorted = (sorted.copyOfRange(0, i) + e + sorted.copyOfRange(i, sorted.size))
        userFreq[e.key] = 1
        dirty = true
    }

    fun save() {
        val f = userFile ?: return
        if (!dirty) return
        dirty = false
        val sb = StringBuilder()
        val phraseKeys = HashSet<String>()
        for (e in userWords) {
            phraseKeys.add(e.key)
            sb.append("P\t").append(e.w).append('\t').append(e.syl.joinToString("'")).append('\t').append(userFreq[e.key] ?: 1).append('\n')
        }
        for ((k, v) in userFreq) if (k !in phraseKeys) sb.append("F\t").append(k).append('\t').append(v).append('\n')
        val text = sb.toString()
        Thread { runCatching { f.writeText(text) } }.start()
    }

    /** 清除学习记录（选词次数和自造词） */
    fun clearUser(ctx: Context) {
        synchronized(this) {
            if (ready && userWords.isNotEmpty()) {
                val gone = userWords.toHashSet()
                exact.values.forEach { it.removeAll(gone) }
                initials.values.forEach { it.removeAll(gone) }
                byHead.values.forEach { it.removeAll(gone) }
                sorted = sorted.filter { it !in gone }.toTypedArray()
            }
            userWords.clear()
            userFreq.clear()
            dirty = false
        }
        File(ctx.filesDir, "user_lexicon.txt").delete()
    }

    fun userCount(): Int = userFreq.size
}
