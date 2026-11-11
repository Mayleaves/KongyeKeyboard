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

class RimeChoice(val schema: String, val raw: String)

/**
 * 候选：n 是它吃掉了多少个输入键；abbr 表示按简拼匹配；
 * fix 表示是纠错出来的，py 是要显示的拼音（音节间空格），mask 标出 py 里打错的字母（标红）
 */
class Cand(
    val w: String, val n: Int, val e: Entry?, val score: Float,
    val abbr: Boolean = false, val py: String? = null, val mask: BooleanArray? = null,
    /** 由几个词拼成的整句，选中后记成新词 */
    val sentence: Boolean = false,
    /** 按模糊音换了读音才对上的（拼音显示不按它的读音来） */
    val fuzzy: Boolean = false,
    val inputDisplay: String? = null,
    val nativeChoice: RimeChoice? = null,
    val correction: Boolean = false,
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
    private val prefixRanges=HashMap<String,IntRange>()
    private var rangeEntries:Array<Entry> = emptyArray()
    private val rangeExtras=ArrayList<Entry>()
    private var corrector: PinyinCorrection? = null
    private val candidateCache=object:LinkedHashMap<String,List<Cand>>(96,.75f,true) {
        override fun removeEldestEntry(eldest:MutableMap.MutableEntry<String,List<Cand>>?)=size>96
    }
    private fun rebuildRanges() {
        prefixRanges.clear();rangeEntries=sorted;rangeExtras.clear()
        for(i in sorted.indices) for(length in 1..min(3,sorted[i].p.length)) {
            val prefix=sorted[i].p.substring(0,length)
            prefixRanges[prefix]=prefixRanges[prefix]?.let {it.first..i} ?: i..i
        }
        corrector = PinyinCorrection(sorted.map {it.p})
        candidateCache.clear()
    }
    private val exact = HashMap<String, ArrayList<Entry>>()
    private val initials = HashMap<String, ArrayList<Entry>>()
    private val byHead = HashMap<Char, ArrayList<Entry>>()
    private var nativeOnly = false
    private val associationWords = HashMap<Char, List<String>>()

    /** Rime owns decoding. Retain compact words for clipboard segmentation and
     * associations, not a second full spelling trie and every Entry object.
     */
    @Synchronized fun compactForNative() {
        if (nativeOnly) return
        byHead.forEach { (head, entries) -> associationWords[head] = entries.filterNot { it.user }.map { it.w } }
        sorted = emptyArray(); rangeEntries = emptyArray(); rangeExtras.clear()
        prefixRanges.clear(); exact.clear(); initials.clear(); byHead.clear()
        corrector = null; candidateCache.clear(); nativeOnly = true
    }
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

    @Synchronized fun loadReadings(file: File) {
        file.forEachLine { line ->
            val fields = line.split('\t')
            if (fields.size == 2 && fields[0].length == 1) charPy[fields[0][0]] = fields[1]
        }
    }

    /** Keep previously learned personal phrases when upgrading to Rime. */
    @Synchronized fun userCandidates(keys: List<String>, abbr: Boolean): List<Cand> {
        val (sets, marks) = split(keys)
        return userWords.filter { e ->
            (e.p.length == sets.size && fitsSets(e.p, sets, sets.size) && fitsMarks(e, sets.size, marks)) ||
                (abbr && marks.isEmpty() && e.ini.length == sets.size && fitsSets(e.ini, sets, sets.size))
        }.sortedByDescending { userFreq[it.key] ?: 0 }.take(4).map { e ->
            Cand(e.w, sets.size, e, 100f, py = e.syl.joinToString(" "))
        }
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
            rebuildRanges()
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

    /** 打开的模糊音规则（Prefs.FUZZY_RULES 的 id） */
    @Volatile
    var fuzzyRules: Set<String> = emptySet()

    private val INITIAL_PAIRS = mapOf(
        "z-zh" to ("z" to "zh"), "c-ch" to ("c" to "ch"), "s-sh" to ("s" to "sh"),
        "n-l" to ("n" to "l"), "f-h" to ("f" to "h"), "r-l" to ("r" to "l"),
    )
    private val FINAL_PAIRS = mapOf(
        "an-ang" to ("an" to "ang"), "en-eng" to ("en" to "eng"), "in-ing" to ("in" to "ing"),
        "ian-iang" to ("ian" to "iang"), "uan-uang" to ("uan" to "uang"),
    )

    private fun initialOf(x: String): String = when {
        x.length >= 2 && x[1] == 'h' && x[0] in "zcs" -> x.substring(0, 2)
        x.isNotEmpty() && x[0] !in "aeiouv" -> x.substring(0, 1)
        else -> ""
    }

    /** 一个音节按打开的模糊音规则能换成的读音（含自己）；最后一个音节可以是半截 */
    fun syllableVariants(x: String, partial: Boolean, rules: Set<String> = fuzzyRules): List<String> {
        val ini = initialOf(x)
        val fin = x.substring(ini.length)
        val inis = linkedSetOf(ini)
        val fins = linkedSetOf(fin)
        for ((id, ab) in INITIAL_PAIRS) if (id in rules) {
            if (ini == ab.first) inis.add(ab.second) else if (ini == ab.second) inis.add(ab.first)
        }
        for ((id, ab) in FINAL_PAIRS) if (id in rules) {
            if (fin == ab.first) fins.add(ab.second) else if (fin == ab.second) fins.add(ab.first)
        }
        val out = ArrayList<String>()
        for (i in inis) for (f in fins) {
            val v = i + f
            if (v == x || v in sylSet || (partial && syllables.any { it.startsWith(v) })) out.add(v)
        }
        return out
    }

    @Synchronized
    fun candidates(keys: List<String>, abbr: Boolean = true, fuzzy: Boolean = true): List<Cand> {
        val rules=fuzzyRules
        val cacheKey=keys.joinToString("|")+"/$abbr/$fuzzy/"+rules.sorted().joinToString(",")
        candidateCache[cacheKey]?.let {return it}
        val base=core(keys,abbr,fuzzy)
        val (sets,marks)=split(keys)
        val result=if(rules.isEmpty() || !ready || sets.isEmpty() || sets.any {it.length!=1}) base
            else withFuzzy(sets,base,abbr,rules,marks)
        if(ready) candidateCache[cacheKey]=result
        return result
    }

    /** 模糊音：把输入切成音节，每个音节换成能对应的读音，拼出几种别的输入再查，结果按原输入的长度折算 */
    private fun withFuzzy(sets: List<String>, base: List<Cand>, abbr: Boolean, rules: Set<String>, marks: List<Int>): List<Cand> {
        val n = sets.size
        val cuts=listOf(0)+marks.filter {it in 1 until n}+n
        val syl=cuts.zipWithNext().flatMap {(a,b)->decode(sets.subList(a,b))}
        if (syl.joinToString("").length != n) return base
        val alts = syl.mapIndexed { i, x -> syllableVariants(x, i == syl.lastIndex, rules) }
        if (alts.all { it.size <= 1 }) return base
        val origBounds = IntArray(syl.size + 1).also { b -> syl.forEachIndexed { i, x -> b[i + 1] = b[i] + x.length } }
        var beam=listOf(syl to 0)
        for(i in alts.indices) {
            beam=beam.flatMap { (value,cost) -> alts[i].map { alt ->
                value.toMutableList().apply {this[i]=alt}.toList() to (cost+if(alt==syl[i]) 0 else 1)
            }}.filter {it.second<=3}.sortedBy {it.second}.take(65)
        }
        val combos=beam.filter {it.second>0}.map {it.first}
        val fz = ArrayList<Cand>()
        val completeFuzzy=HashSet<Cand>()
        for (combo in combos) {
            val varBounds = IntArray(combo.size + 1).also { b -> combo.forEachIndexed { i, x -> b[i + 1] = b[i] + x.length } }
            val v = combo.joinToString("")
            val variantMarks=marks.mapNotNull {mark->origBounds.indexOf(mark).takeIf {it>=0}?.let {varBounds[it]}}
            val variantKeys=ArrayList<String>()
            v.forEachIndexed {index,ch -> if(index in variantMarks) variantKeys.add("'");variantKeys.add(ch.toString())}
            for (c in core(variantKeys, abbr, fuzzy = false).take(40)) {
                val e = c.e ?: continue
                if (c.sentence || c.abbr || c.fix) continue
                val j = varBounds.indexOf(c.n)
                if (j <= 0) continue
                val candidate=Cand(c.w,origBounds[j],e,c.score-3f*combo.indices.count {combo[it]!=syl[it]},fuzzy=true)
                fz.add(candidate)
                if(candidate.n==n && e.p.length==c.n) completeFuzzy.add(candidate)
            }
        }
        if (fz.isEmpty()) return base
        fz.sortByDescending { it.score }
        // 整个输入都对上的词（原读音和模糊读音）放一起按分数排最前；拼出来的整句只在没有模糊整词时保留
        val baseFull = base.filter { it.n == n && !it.abbr && !it.fix && !it.sentence && it.e != null && it.e.p.length == n }
        val fzFull = fz.filter { it in completeFuzzy }
        val full = (baseFull + fzFull).sortedByDescending { it.score }
        val sentences = if (fzFull.isEmpty()) base.filter { it.sentence } else emptyList()
        val fixes=base.filter {it.fix}
        val rest = base.filterNot { it in baseFull || it.sentence || it.fix }
        val merged = full.take(8) + fixes.take(6) + sentences + rest.take(4) + fz.filterNot {it in completeFuzzy}.take(12) + rest.drop(4) + full.drop(8)
        val seen = HashSet<String>()
        return merged.filter { seen.add(it.w) }
    }

    private fun core(keys: List<String>, abbr: Boolean, fuzzy: Boolean): List<Cand> {
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
                // 自己打过的词（自造词）简拼时排前面：打过 xiaxingyue 的“夏星月”，之后 xxy 就是它
                for (e in list.take(60)) a.add(Cand(e.w, n, e, e.sc + boost(e) - 6f + (if (e.user) 24f else 0f), abbr = true))
            }
            for (k in n - 1 downTo 1) {
                exact[s.substring(0, k)]?.let { list ->
                    for (e in list.take(40)) if (fitsMarks(e, k, marks)) b.add(Cand(e.w, k, e, e.sc + boost(e) + k * 16f))
                }
            }
            // 没有正好对上的字词时：拼一个整句候选（你+还），再给纠错候选
            if (marks.isEmpty() && n >= 3) {
                if(a.none { it.e != null && it.e.p.length == n && !it.abbr }) sentence(s)?.let { a.add(it) }
                // A typo can itself spell a real word; retain that exact word first,
                // but still offer nearby alternatives instead of hiding correction.
                if (fuzzy && n >= 3) c.addAll(corrections(s))
            }
        } else {
            // 14 键、九键：一个键多个字母，按首键的每个字母在有序表里扫一段
            val prefixes=ArrayList<String>()
            fun buildPrefix(index:Int,prefix:String) {
                if(index==min(3,n)) {prefixes.add(prefix);return}
                for(ch in sets[index]) if(prefixRanges.containsKey(prefix+ch)) buildPrefix(index+1,prefix+ch)
            }
            buildPrefix(0,"")
            for(prefix in prefixes) for(i in (prefixRanges[prefix] ?: IntRange.EMPTY)) {
                val e=rangeEntries[i];val l=e.p.length
                if(l>=n) {
                    if(fitsSets(e.p,sets,n) && fitsMarks(e,n,marks)) a.add(Cand(e.w,n,e,e.sc+boost(e)+if(l==n) 6f else -(l-n)*.8f))
                } else if(fitsSets(e.p,sets,l) && fitsMarks(e,l,marks)) b.add(Cand(e.w,l,e,e.sc+boost(e)+l*16f))
            }
            for(e in rangeExtras) {
                val l=e.p.length
                if(l>=n && fitsSets(e.p,sets,n) && fitsMarks(e,n,marks)) a.add(Cand(e.w,n,e,e.sc+boost(e)+if(l==n) 6f else -(l-n)*.8f))
                else if(l<n && fitsSets(e.p,sets,l) && fitsMarks(e,l,marks)) b.add(Cand(e.w,l,e,e.sc+boost(e)+l*16f))
            }
            // Entries shorter than the indexed prefix are valid partial candidates.
            if(n>1) for(length in 1 until min(3,n)) {
                fun short(index:Int,prefix:String) {
                    if(index==length) {exact[prefix]?.take(40)?.forEach {e -> if(fitsMarks(e,length,marks)) b.add(Cand(e.w,length,e,e.sc+boost(e)+length*16f))};return}
                    for(ch in sets[index]) short(index+1,prefix+ch)
                }
                short(0,"")
            }
        }
        a.sortWith(compareByDescending<Cand> { if(n==1 && it.e?.w?.length==1) 1 else 0 }.thenByDescending {it.score})
        b.sortByDescending { it.score }
        c.sortByDescending { it.score }
        val out = ArrayList<Cand>()
        val seen = HashSet<String>()
        // 纠错的排在完全匹配的前几个之后
        val exactWords=a.filter {it.e!=null && it.e.p.length==n && !it.abbr && !it.sentence}
        val remaining=a.filterNot {it in exactWords}.take(150)
        for(x in exactWords) if(seen.add(x.w)) out.add(x)
        for(x in c.take(6)) if(seen.add(x.w)) out.add(x)
        for(x in remaining) if(seen.add(x.w)) out.add(x)
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

    /** 候选召回与有权重的编辑距离分开：漏打、多打、邻键及颠倒，长词最多两处。 */
    private fun corrections(s: String): List<Cand> {
        val out=ArrayList<Cand>()
        for (match in corrector?.lookup(s).orEmpty()) {
            for (e in exact[match.spelling].orEmpty().take(10)) {
                val singlePenalty=if(e.w.length==1 && match.penalty>7f) 18f else 0f
                out.add(Cand(e.w,s.length,e,e.sc+boost(e)-match.penalty-singlePenalty,
                    py=e.syl.joinToString(" "),mask=spaced(match.mask,e.syl)))
            }
        }
        return out.sortedByDescending {it.score}.distinctBy {it.w}.take(12)
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
        } else if (top?.e != null && !top.fix && !top.fuzzy) {
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
            for (j in 0 until cuts.size - 1) out.addAll(displaySyllables(sets.subList(cuts[j], cuts[j + 1])))
        }
        val text = out.joinToString("'")
        return if (keys.lastOrNull() == "'") "$text'" else text
    }

    /** Streaming display: appending a letter never inserts/removes a separator
     * between earlier letters. Candidate completion cannot resegment this line. */
    fun streamingDisplay(keys: List<String>): String {
        if (keys.any { it.length != 1 }) return display(keys,null)
        val out=StringBuilder()
        var syllable=""
        for (key in keys) {
            if (key=="'") {
                if (out.isNotEmpty() && out.last()!='\'') out.append('\'')
                syllable="";continue
            }
            val next=syllable+key
            if (syllable.isNotEmpty() && syllables.none {it.startsWith(next)}) {
                out.append('\'');syllable=key
            } else syllable=next
            out.append(key)
        }
        return out.toString()
    }

    fun display(raw: String, top: Cand?): String = display(raw.map { it.toString() }, top)

    /** 显示分隔不依赖异步候选；完整音节优先，也允许连续声母简拼 n'h'y。 */
    private fun displaySyllables(sets: List<String>): List<String> {
        val decoded = decode(sets)
        if (decoded.size != 1 || decoded[0] in syllables || sets.size <= 1) return decoded
        val failed = HashSet<Int>()
        fun walk(i: Int): List<String>? {
            if (i == sets.size) return emptyList()
            if (i in failed) return null
            for (len in min(6, sets.size - i) downTo 1) {
                for (syl in syllables) {
                    if (syl.length != len || syl.indices.any { syl[it] !in sets[i + it] }) continue
                    val tail = walk(i + len) ?: continue
                    return listOf(syl) + tail
                }
            }
            // zh/ch/sh 是一个声母；其他声母只占一个按键。
            for (initial in DISPLAY_INITIALS) {
                if (i + initial.length > sets.size || initial.indices.any { initial[it] !in sets[i + it] }) continue
                val tail = walk(i + initial.length) ?: continue
                return listOf(initial) + tail
            }
            failed.add(i)
            return null
        }
        return walk(0) ?: decoded
    }

    private val DISPLAY_INITIALS = listOf("zh", "ch", "sh") + "bpmfdtnlgkhjqxrzcsyw".map { it.toString() }

    /** 上屏后的联想：以刚上屏的词开头的常用词，取后半截 */
    fun assoc(w: String): List<String> {
        if (!ready || w.isEmpty() || w.last() !in '一'..'鿿') return emptyList()
        val out = LinkedHashSet<String>()
        fun add(prefix: String) {
            if (nativeOnly) {
                val candidates = userWords.asSequence().map { it.w } + associationWords[prefix[0]].orEmpty().asSequence()
                for (word in candidates) {
                    if (out.size >= 12) return
                    if (word.length > prefix.length && word.startsWith(prefix)) out.add(word.substring(prefix.length))
                }
                return
            }
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

    @Synchronized
    fun recordPick(e: Entry) {
        candidateCache.clear()
        userFreq[e.key] = (userFreq[e.key] ?: 0) + 1
        dirty = true
    }

    /** 分几次选出来的一整句，记成一个新词 */
    @Synchronized
    fun learnPhrase(w: String, syl: List<String>) {
        if (!ready || w.length < 2 || w.length > 8 || syl.size != w.length) return
        val p = syl.joinToString("")
        if (nativeOnly) {
            val existing = userWords.firstOrNull { it.p == p && it.w == w }
            if (existing != null) { recordPick(existing); return }
            val entry = Entry(syl.toTypedArray(), w, 40, user = true)
            userWords.add(entry); words.add(w); userFreq[entry.key] = 1; dirty = true
            return
        }
        exact[p]?.firstOrNull { it.w == w }?.let { recordPick(it); return }
        val e = Entry(syl.toTypedArray(), w, 40, user = true)
        userWords.add(e)
        words.add(w)
        exact.getOrPut(p) { ArrayList(2) }.add(0, e)
        initials.getOrPut(e.ini) { ArrayList(4) }.add(0, e)
        byHead.getOrPut(e.w[0]) { ArrayList(8) }.add(0, e)
        val i = lowerBound(p)
        sorted = (sorted.copyOfRange(0, i) + e + sorted.copyOfRange(i, sorted.size))
        rangeExtras.add(e)
        corrector?.learn(p)
        candidateCache.clear()
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
    @Synchronized
    fun clearUser(ctx: Context) {
        candidateCache.clear()
        synchronized(this) {
            if (ready && userWords.isNotEmpty()) {
                val gone = userWords.toHashSet()
                exact.values.forEach { it.removeAll(gone) }
                initials.values.forEach { it.removeAll(gone) }
                byHead.values.forEach { it.removeAll(gone) }
                sorted = sorted.filter { it !in gone }.toTypedArray()
                if (!nativeOnly) rebuildRanges()
            }
            userWords.clear()
            userFreq.clear()
            dirty = false
        }
        File(ctx.filesDir, "user_lexicon.txt").delete()
    }

    fun userCount(): Int = userFreq.size
}
