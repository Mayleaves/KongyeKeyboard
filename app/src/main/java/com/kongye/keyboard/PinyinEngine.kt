package com.kongye.keyboard

import android.content.Context
import android.util.Log
import java.io.File
import java.util.zip.ZipInputStream
import kotlin.math.min

/** Mobile frontend for the real Rime decoder. All native access is serialized.
 * Lexicon remains the handwriting/association helper and cold-start fallback.
 */
object PinyinEngine {
    @Volatile var ready = false; private set
    @Volatile var failure: String? = null; private set
    @Volatile var version = ""; private set
    private var core: RimeBridge? = null
    private val cache = object : LinkedHashMap<String, List<Cand>>(96, .75f, true) {
        override fun removeEldestEntry(e: MutableMap.MutableEntry<String, List<Cand>>?) = size > 96
    }
    private val nine = listOf("abc", "def", "ghi", "jkl", "mno", "pqrs", "tuv", "wxyz")
    private val fourteen = listOf("qw", "er", "ty", "ui", "op", "as", "df", "gh", "jk", "l", "zx", "cv", "bn", "m")

    @Synchronized fun initialize(ctx: Context) {
        if (ready) return
        FcitxEngine.initialize(ctx)
        try {
            val root = File(ctx.filesDir, "rime").apply { mkdirs() }
            val data = File(root, "data-2.1.0-ice-r3")
            if (!File(data, ".complete").isFile) {
                data.mkdirs()
                ZipInputStream(ctx.assets.open("rime-data.zip").buffered()).use { zip ->
                    while (true) {
                        val entry = zip.nextEntry ?: break
                        val target = File(data, entry.name)
                        check(target.canonicalPath.startsWith(data.canonicalPath + File.separator))
                        if (entry.isDirectory) target.mkdirs() else {
                            target.parentFile?.mkdirs()
                            val temporary = File(target.parentFile, target.name + ".part")
                            temporary.outputStream().buffered().use { zip.copyTo(it) }
                            check(temporary.renameTo(target)) { "Cannot install ${target.name}" }
                        }
                        zip.closeEntry()
                    }
                }
                File(data, ".complete").writeText("Rime 1.17.0 / Rime Ice 99662ba / Wanxiang grammar")
            }
            val user = File(root, "base-user").apply { mkdirs() }
            core = RimeBridge(data.absolutePath, user.absolutePath)
            version = core!!.version
            // Force native dictionary/model mapping off the keyboard UI thread.
            check(core!!.query("kongye_26", "nihao").candidates.any { it.text == "你好" })
            Lexicon.loadReadings(File(data, "readings.tsv"))
            Lexicon.compactForNative()
            PinyinExpressions.load(ctx)
            failure = null; ready = true
            // These are regenerated dictionaries, never rime/user learning data.
            for (name in listOf("data-2.1.0-r1", "data-2.1.0-r2")) {
                val obsolete = File(root, name)
                if (obsolete.canonicalFile.parentFile == root.canonicalFile && File(obsolete, ".complete").isFile)
                    obsolete.deleteRecursively()
            }
        } catch (e: Exception) {
            failure = e.javaClass.simpleName + ": " + e.message
            Log.e("KongyeRime", "Rime initialization failed", e)
        } catch (e: LinkageError) {
            failure = e.javaClass.simpleName + ": " + e.message
            Log.e("KongyeRime", "Rime library failed", e)
        }
    }

    @Synchronized fun reloadModel(ctx:Context) {
        if(!ready)return
        core?.close();core=null;ready=false;cache.clear()
        initialize(ctx)
        check(ready) {failure ?: "整句模型准备失败"}
    }

    private fun layout(keys: List<String>): String = when {
        keys.none { it.length > 1 } -> "26"
        keys.any { it.length > 2 } -> "9"
        else -> "14"
    }
    private fun encode(keys: List<String>, layout: String): String {
        val groups = if (layout == "9") nine else fourteen
        val codes = if (layout == "9") "23456789" else "qetuoadgjlzcbm"
        return keys.joinToString("") { key ->
            if (key == "'" || layout == "26") key else {
                val index = groups.indexOfFirst { group -> key.all { it in group } }
                check(index >= 0) { "Unknown $layout-key input" }
                codes[index].toString()
            }
        }
    }

    fun candidates(keys: List<String>, abbr: Boolean = true): List<Cand> {
        if(FcitxEngine.ready && layout(keys)=="26" && abbr) return nativeCandidates(keys, {true}, false, null)
        if (!ready) return Lexicon.candidates(keys, abbr)
        return queryReady(keys, abbr, { true }, {})
    }

    fun candidatesLive(keys: List<String>, abbr: Boolean, current: () -> Boolean, onPrimary: (List<Cand>) -> Unit): List<Cand> {
        if(FcitxEngine.ready && layout(keys)=="26" && abbr) {
            return nativeCandidates(keys,current,false,onPrimary)
        }
        if (!ready) return Lexicon.candidates(keys, abbr)
        return queryReady(keys, abbr, current, onPrimary)
    }

    fun expandedCandidates(keys: List<String>, current: () -> Boolean) =
        nativeCandidates(keys, current, true, null)

    private fun nativeCandidates(keys: List<String>, current: () -> Boolean, expanded: Boolean,
                                 onPrimary: ((List<Cand>) -> Unit)?): List<Cand> {
        var repairs = emptyList<Cand>()
        fun merge(words: List<Cand>): List<Cand> {
            val first = words.firstOrNull()
            val keepFirst = first != null && first.n == keys.size &&
                (first.py?.replace(" ", "") == keys.joinToString("") &&
                    (repairs.firstOrNull()?.correction != false || Lexicon.knownWord(first.w)) ||
                    first.abbr && !first.correction && first.e != null ||
                    first.correction && first.nativeChoice?.raw==first.py?.replace(" ", "") &&
                    SpellingAssist.edits(keys.joinToString(""),first.py.orEmpty()).let {edits->edits.isNotEmpty() && edits.all {it.kind==3}})
            val ranked = if (repairs.isEmpty()) words else
                ((if (keepFirst) words.take(1) else emptyList()) + repairs + words).distinctBy { it.w }
            return PinyinExpressions.insert((Lexicon.userCandidates(keys,true)+ranked).distinctBy {it.w}, keys)
        }
        val words = when {
            expanded -> FcitxEngine.candidates(keys,current)
            onPrimary == null -> FcitxEngine.primaryCandidates(keys,current)
            else -> FcitxEngine.candidatesLive(keys,current) { onPrimary(merge(it)) }
        }
        repairs = sentenceRepairs(keys, current, words.firstOrNull())
        return merge(words)
    }

    @Synchronized private fun sentenceRepairs(keys: List<String>, current: () -> Boolean, nativeFirst: Cand?): List<Cand> {
        val rime = core ?: return emptyList()
        if (keys.size !in 5..32 || "'" in keys) return emptyList()
        val raw = keys.joinToString("")
        val display = Lexicon.streamingDisplay(keys)
        val parts = display.split('\'')
        if (parts.size < 3) return emptyList()
        val variants = (SpellingAssist.sentenceVariants(raw, parts, current) +
            if((nativeFirst?.n!=keys.size || nativeFirst.correction) && SpellingAssist.correctionFlags and 3==3 && SpellingAssist.transpositions(raw).isNotEmpty())
                SpellingAssist.repairs(raw,current) else emptyList()).distinct()
        val full = parts.all { Lexicon.isCompleteSyllable(it) }
        val schema = "kongye_26"
        fun query(input: String): List<RimeBridge.Candidate> {
            if (!current()) throw java.util.concurrent.CancellationException()
            return rime.query(schema, input).candidates.toList()
        }
        val original = query(raw)
        val initials = parts.takeWhile { it.length==1 && it[0] in "bpmfdtnlgkhjqxrzcsyw" || it in listOf("zh","ch","sh") }
        for (size in minOf(initials.size,6) downTo 2) {
            if(parts.drop(size).count {it.length>=2}<2) continue
            val prefixLength=initials.take(size).sumOf {it.length}
            val prefix=query(raw.take(prefixLength)).firstOrNull {it.type in listOf("phrase","user_phrase") &&
                it.end==prefixLength && it.pinyin.split(' ').size==size && nativeFirst?.w?.startsWith(it.text)==true}
                ?: continue
            val expanded=prefix.pinyin.replace(" ","")+raw.drop(prefixLength)
            val resolved=query(expanded).firstOrNull {it.end==expanded.length && it.text.startsWith(prefix.text) &&
                it.pinyin.replace(" ","")==expanded && SpellingAssist.matchesTypedReading(raw,it.pinyin)}
            if(resolved!=null) return listOf(Cand(resolved.text,keys.size,null,resolved.quality.toFloat(),
                abbr=true,py=resolved.pinyin,inputDisplay=display,nativeChoice=RimeChoice(schema,expanded,raw)))
        }
        if (variants.isEmpty() && !full) return emptyList()
        if (full) {
            val hit = original.firstOrNull() ?: return emptyList()
            if (hit.end == raw.length && hit.type == "phrase" && hit.pinyin.replace(" ", "") == raw &&
                hit.quality >= 0.8 && !Lexicon.knownWord(hit.text) &&
                original.drop(1).none { it.end == raw.length && it.quality >= hit.quality - 0.00001 })
                return listOfNotNull(convert(hit,schema,raw,"26",false))
            return emptyList()
        }
        if (original.any { it.type == "phrase" && it.end == raw.length && it.quality > 0.5 }) return emptyList()
        val found = ArrayList<Cand>()
        val phraseScores = HashMap<String, Double>()
        for (variant in variants) {
            for (word in query(variant).take(8)) {
                if (word.type != "phrase" || word.end != variant.length || word.quality < 0.5 ||
                    word.pinyin.split(' ').size < 3 || word.text.any { it.code < 128 }) continue
                val typed = word.preedit.split(' ', '\'').filter { it.isNotEmpty() }
                val sounds = word.pinyin.split(' ')
                if (typed.joinToString("") != variant || typed.size != sounds.size || !typed.indices.all { i ->
                        typed[i] == sounds[i] || sounds[i].startsWith(typed[i]) &&
                            (typed[i].length == 1 || typed[i] in listOf("zh","ch","sh"))
                    }) continue
                // For near-tied abbreviated readings, prefer fewer inferred letters.
                val spelling = sounds.joinToString("")
                val quality = phraseScores.getOrPut(word.text+"/"+spelling) {
                    query(spelling).firstOrNull {it.text==word.text && it.end==spelling.length && it.type=="phrase"}?.quality ?: word.quality
                }
                val inferred = spelling.length - raw.length
                val score = quality.toFloat() - inferred * 0.012f
                found.add(Cand(word.text,keys.size,null,score,py=word.pinyin,inputDisplay=display,
                    correction=true,nativeChoice=RimeChoice(schema,variant,raw)))
            }
        }
        return found.sortedByDescending { it.score }.distinctBy { it.w }.take(4)
    }

    @Synchronized private fun queryReady(keys: List<String>, abbr: Boolean, current: () -> Boolean, onPrimary: (List<Cand>) -> Unit): List<Cand> {
        fun checkCurrent() { if (!current()) throw java.util.concurrent.CancellationException() }
        checkCurrent()
        val rime = core
        if (!ready || rime == null) return Lexicon.candidates(keys, abbr)
        val cacheKey = keys.joinToString(",") + "/$abbr/${Lexicon.learningEnabled}/${SpellingAssist.correctionFlags}/" + Lexicon.fuzzyRules.sorted().joinToString(",")
        cache[cacheKey]?.let { onPrimary(it); checkCurrent(); return it }
        val layout = layout(keys)
        val raw = encode(keys, layout)
        if (raw.isEmpty()) return emptyList()
        val schema = "kongye_$layout" + if (abbr) "" else "_full"
        val normal = rime.query(schema, raw)
        val normalCandidates = normal.candidates.mapNotNull { convert(it, schema, raw, layout, false) }
        val output = normalCandidates.toMutableList()
        var resolvedAbbreviation = false
        var coherentAbbreviation = false
        checkCurrent()
        // Resolve a run of initial-only syllables using a real dictionary phrase,
        // then let Rime decode the whole expanded spelling with its language model.
        // This keeps common abbreviated words together in mixed full/initial input;
        // no application-specific phrases or manufactured Chinese sentences.
        if (abbr && layout == "26" && normal.candidates.firstOrNull()?.type == "sentence") {
            val tokens = normal.preedit.split(' ', '\'').filter { it.isNotEmpty() }
            val initials = tokens.takeWhile { it.length == 1 && it[0] !in "aeouv" || it in listOf("zh", "ch", "sh") }
            val prefixLength = initials.sumOf { it.length }
            val tail = normalize(raw.drop(prefixLength))
            coherentAbbreviation = initials.size >= 2 && tail.length >= 2 &&
                normalize(normal.candidates.first().pinyin.split(' ').drop(initials.size).joinToString("")) == tail
            // A single following syllable provides too little context: e.g. a
            // neighboring-key typo must not become an expanded person's name.
            if ((!coherentAbbreviation || initials.size >= 3) && initials.size >= 2 &&
                tokens.drop(initials.size).count { it.length >= 2 } >= 2) {
                val prefix = normal.candidates.filter { it.type in listOf("phrase", "user_phrase") && it.end == prefixLength &&
                    it.pinyin.split(' ').size == initials.size }.maxByOrNull { it.quality }
                if (prefix != null) {
                    val expanded = normalize(prefix.pinyin) + raw.drop(prefixLength)
                    val result = rime.query(schema, expanded)
                    checkCurrent()
                    val resolved = result.candidates.firstOrNull { it.end == expanded.length && it.text.startsWith(prefix.text) &&
                        normalize(it.pinyin) == normalize(expanded) }
                    if (resolved != null) convert(resolved, schema, expanded, layout, false)?.let { c ->
                        output.add(0, Cand(c.w, keys.count { it != "'" }, c.e, c.score, py = c.py,
                            inputDisplay = Lexicon.display(keys, null), nativeChoice = c.nativeChoice))
                        resolvedAbbreviation = true
                    }
                }
            }
        }
        val learned = Lexicon.userCandidates(keys, abbr)
        fun complete(source: List<Cand>): List<Cand> {
            val distinct = (learned + source).distinctBy { it.w + "/" + it.n }.toMutableList()
            val letters = keys.count { it != "'" }
            val literal = keys.joinToString("") { if (it == "'") "'" else it.take(1) }
            if (distinct.none { it.w == literal && it.n == letters }) distinct.add(Cand(literal, letters, null, -100f))
            return PinyinExpressions.insert(HanReadings.complete(distinct,keys), keys)
        }
        onPrimary(complete(output))
        checkCurrent()
        // A separate full-pinyin graph avoids the combinatorial ambiguity of
        // simultaneously treating every typo as an initial. Correct input keeps
        // its original path; typo alternatives must pass a bounded edit check.
        val primary = normal.candidates.firstOrNull()
        val needsCorrection = primary == null || primary.end < raw.length ||
            (normalize(primary.pinyin) != normalize(raw) && !normalize(primary.pinyin).startsWith(normalize(raw)))
        if (layout == "26" && raw.length in 3..64 && needsCorrection) {
            val tokens = normal.preedit.split(' ', '\'').filter { it.isNotEmpty() }
            val initialCount = tokens.count { it.length == 1 && it[0] !in "aeouv" || it in listOf("zh", "ch", "sh") }
            if (initialCount <= 2 || raw.length <= 12) {
                val correction = rime.query("kongye_correction", raw)
                checkCurrent()
                val repaired = correction.candidates.filter { it.end == raw.length &&
                    spellingDistance(raw, it.pinyin) <= 2.05 }
                    .mapNotNull { convert(it, "kongye_correction", raw, layout, true) }
                val first = repaired.firstOrNull()
                val old = normalCandidates.firstOrNull()
                // An initial is not a spelling mistake. A full-coverage normal
                // abbreviation must precede repairs which delete/substitute it.
                val promote = !resolvedAbbreviation && !coherentAbbreviation && first != null && (old == null || old.n < keys.count { it != "'" } ||
                    primary?.type == "sentence" && initialCount > 0 &&
                    spellingDistance(raw, first.py.orEmpty()) + .1 < spellingDistance(raw, old.py.orEmpty()))
                if (promote) output.add(0, first)
                val at = output.indexOfFirst { it.n < keys.count { key -> key != "'" } }.let { if (it < 0) output.size else it }
                output.addAll(at, repaired.take(8))
                // A second native correction pass handles two adjacent-key slips
                // inside a syllable. Run only on short, ambiguous initial strings;
                // its alternatives cannot replace the normal sentence outright.
                if (initialCount >= 3 && raw.length <= 12) {
                    val neighbor = rime.query("kongye_neighbor", raw)
                    checkCurrent()
                    val alternatives = neighbor.candidates.filter { it.end == raw.length &&
                        spellingDistance(raw, it.pinyin) <= 2.05 }
                        .mapNotNull { convert(it, "kongye_neighbor", raw, layout, true) }
                    output.addAll(at + repaired.take(8).size, alternatives.take(6))
                }
            }
        }
        // Optional fuzzy sounds still use Rime's full sentence decoder, rather
        // than falling back to a prefix word lookup in the old dictionary.
        if (layout == "26" && Lexicon.fuzzyRules.isNotEmpty()) {
            val pieces = normal.preedit.split(' ').filter { it.isNotEmpty() }
            var count = 0
            for (i in pieces.indices) {
                val variants = Lexicon.syllableVariants(pieces[i], i == pieces.lastIndex).filter { it != pieces[i] }
                for (variant in variants) {
                    if (count++ >= 6) break
                    checkCurrent()
                    val alternative = pieces.toMutableList().apply { set(i, variant) }.joinToString("")
                    val result = rime.query(schema, alternative)
                    result.candidates.filter { it.end == alternative.length }.take(3).forEach { c ->
                        val converted = convert(c, schema, alternative, layout, false)
                        if (converted != null) output.add(Cand(converted.w, keys.count { it != "'" }, converted.e,
                            converted.score, py = converted.py, fuzzy = true, inputDisplay = normal.preedit.replace(' ', '\'')))
                    }
                }
                if (count >= 6) break
            }
        }
        checkCurrent()
        if(layout=="26" && !abbr && FcitxEngine.ready && (SpellingAssist.correctionFlags!=0 || Prefs.FUZZY_V_U in Lexicon.fuzzyRules)) {
            for(c in FcitxEngine.primaryCandidates(keys,current).filter {
                (it.correction || !it.abbr && Prefs.FUZZY_V_U in Lexicon.fuzzyRules) && it.n==keys.count {key->key!="'"}
            }) {
                val old=output.indexOfFirst {it.w==c.w}
                if(old>=0 && output[old].n<c.n)output[old]=c
                else if(old<0)output.add(minOf(3,output.size),c)
            }
        }
        val associated = complete(output)
        cache[cacheKey] = associated
        return associated
    }

    private fun convert(c: RimeBridge.Candidate, schema: String, raw: String, layout: String, correction: Boolean): Cand? {
        if (c.end <= 0 || c.end > raw.length || c.text.isEmpty() || c.pinyin.isEmpty()) return null
        val consumed = raw.take(c.end).count { it != '\'' && it != ' ' }
        val py = c.pinyin.split(' ').filter { it.isNotEmpty() }
        if (py.isEmpty()) return null
        val entry = Entry(py.toTypedArray(), c.text, 0)
        val display = if (layout == "26") c.preedit.replace(' ', '\'') else {
            val typed = c.preedit.split(' ', '\'').filter { it.isNotEmpty() }
            if (typed.size == py.size) typed.indices.joinToString("'") { py[it].take(typed[it].length) }
            else py.joinToString("'")
        }
        val typed = raw.take(c.end)
        val changedSpelling = normalize(typed) != normalize(c.pinyin)
        val mask = if (changedSpelling && (correction || normalize(typed).length == normalize(c.pinyin).length))
            spellingMask(typed, c.pinyin) else null
        return Cand(c.text, consumed, entry, c.quality.toFloat(),
            py = c.pinyin, mask = mask, sentence = false, inputDisplay = display,
            nativeChoice = RimeChoice(schema, raw.take(c.end)), correction = correction)
    }

    @Synchronized fun recordPick(c: Cand) {
        val choice = c.nativeChoice ?: return
        if(choice.schema.startsWith("fcitx/")) return
        core?.learn(choice.schema, choice.raw, c.w)
        cache.clear()
    }

    @Synchronized fun endInput() {
        cache.clear(); core?.clearInput(); FcitxEngine.clear()
    }

    @Synchronized fun <T> withUserFiles(ctx:Context,action:()->T):T {
        val wasReady=ready;core?.close();core=null;ready=false;cache.clear()
        try {return FcitxEngine.withUserFiles(ctx,action)} finally {if(wasReady){initialize(ctx);check(ready){failure.orEmpty()}}}
    }

    @Synchronized fun clearUser(ctx: Context) {
        FcitxEngine.clearUser(ctx)
        ready = false; cache.clear(); core?.close(); core = null
        val user = File(ctx.filesDir, "rime/user")
        check(user.canonicalFile.parentFile == File(ctx.filesDir, "rime").canonicalFile)
        check(!user.exists() || user.deleteRecursively()) { "Cannot clear Rime learning" }
        initialize(ctx)
        check(ready) { failure.orEmpty() }
    }

    private fun normalize(s: String) = s.replace(" ", "").replace("'", "")
        .replace("jv", "ju").replace("qv", "qu").replace("xv", "xu").replace("yv", "yu")

    /** Mark repaired letters in candidate hints; abbreviation expansion is not
     * a typing error. Indices refer to the displayed, space-separated spelling.
     */
    private fun spellingMask(raw: String, spelling: String): BooleanArray {
        val a = normalize(raw); val b = normalize(spelling)
        val d = Array(a.length+1) { IntArray(b.length+1) }
        for(i in 0..a.length) d[i][0]=i
        for(j in 0..b.length) d[0][j]=j
        for(i in 1..a.length) for(j in 1..b.length) {
            d[i][j]=minOf(d[i-1][j]+1,d[i][j-1]+1,d[i-1][j-1]+if(a[i-1]==b[j-1]) 0 else 1)
            if(i>1 && j>1 && a[i-1]==b[j-2] && a[i-2]==b[j-1]) d[i][j]=minOf(d[i][j],d[i-2][j-2]+1)
        }
        val marks=BooleanArray(b.length)
        var i=a.length;var j=b.length
        while(i>0 || j>0) {
            when {
                i>0 && j>0 && a[i-1]==b[j-1] && d[i][j]==d[i-1][j-1] -> {i--;j--}
                i>1 && j>1 && a[i-1]==b[j-2] && a[i-2]==b[j-1] && d[i][j]==d[i-2][j-2]+1 -> {
                    marks[j-1]=true;marks[j-2]=true;i-=2;j-=2
                }
                i>0 && j>0 && d[i][j]==d[i-1][j-1]+1 -> {marks[j-1]=true;i--;j--}
                j>0 && d[i][j]==d[i][j-1]+1 -> {marks[j-1]=true;j--}
                else -> {if(j>0) marks[j-1]=true;i--}
            }
        }
        var at=0
        return BooleanArray(spelling.length) { index ->
            if(spelling[index]==' ' || spelling[index]=='\'') false else marks.getOrElse(at++) {false}
        }
    }

    /** Damerau edit distance for validating native spelling-graph alternatives.
     * This does not decode or manufacture words; it only bounds Rime's repairs.
     */
    fun spellingDistance(raw: String, spelling: String): Double {
        val a = normalize(raw); val b = normalize(spelling)
        val d = Array(a.length + 1) { DoubleArray(b.length + 1) }
        for (i in 0..a.length) d[i][0] = i.toDouble()
        for (j in 0..b.length) d[0][j] = j.toDouble()
        for (i in 1..a.length) for (j in 1..b.length) {
            d[i][j] = minOf(d[i-1][j]+1, d[i][j-1]+1, d[i-1][j-1] + if (a[i-1] == b[j-1]) 0.0 else 1.0)
            if (i > 1 && j > 1 && a[i-1] == b[j-2] && a[i-2] == b[j-1]) d[i][j] = min(d[i][j], d[i-2][j-2] + 1)
        }
        return d[a.length][b.length]
    }
}
