package com.kongye.keyboard

import android.app.Instrumentation
import android.os.Bundle
import android.os.SystemClock
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/** Data-driven review corpus: semantic ranking suggestions are distinct from rule violations. */
object PinyinAdversarialRegression {
    fun run(i: Instrumentation) {
        runCases(i, "pinyin-adversarial-v1.json", "pinyin-adversarial-v1", 20, "")
    }

    fun corpus(i: Instrumentation, split: String, runToken: String) {
        check(split in listOf("train", "validation", "test"))
        val manifest = JSONObject(i.context.assets.open("pinyin41/manifest.json").bufferedReader().use { it.readText() })
        val bytes = i.context.assets.open("pinyin41/$split.json").use { it.readBytes() }
        val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
        val entry = manifest.getJSONObject("splits").getJSONObject(split)
        check(hash == entry.getString("sha256")) { "Frozen dataset hash differs: $split" }
        runCases(i, "pinyin41/$split.json", "pinyin41/$split", entry.getInt("cases"), runToken)
    }

    private fun runCases(i: Instrumentation, asset: String, directory: String, expectedCount: Int, runToken: String) {
        val ctx = i.targetContext
        fun report(s: String) = i.sendStatus(0, Bundle().apply { putString("stream", s + "\n") })
        fun JSONArray.strings() = (0 until length()).map { getString(it) }
        fun JSONObject.words(key: String) = optJSONArray(key)?.strings().orEmpty()
        fun digest(file: File): String? = if (!file.isFile) null else file.inputStream().use { stream ->
            val hash = MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(8192)
            while (true) { val n = stream.read(buffer); if (n < 0) break; hash.update(buffer, 0, n) }
            hash.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
        }
        val personal = File(ctx.filesDir, "user_lexicon.txt")
        val personalBefore = digest(personal)
        val prefsBefore = Prefs.of(ctx).all.toMap()
        Lexicon.load(ctx)
        PinyinEngine.initialize(ctx)
        check(PinyinEngine.ready && FcitxEngine.ready) { "Both native decoders are required" }
        val previousFuzzy = Lexicon.fuzzyRules
        val previousFlags = SpellingAssist.correctionFlags
        val previousLearning = Lexicon.learningEnabled
        val fixture = i.context.assets.open(asset).use { it.readBytes() }
        val cases = JSONObject(String(fixture, Charsets.UTF_8)).getJSONArray("cases")
        check(cases.length() == expectedCount)
        val fixtureHash = MessageDigest.getInstance("SHA-256").digest(fixture).joinToString("") { "%02x".format(it.toInt() and 255) }
        val packageInfo = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
        val results = JSONArray()
        val issues = mutableListOf<String>()
        val out = File(ctx.getExternalFilesDir(null), directory).apply { mkdirs() }
        fun document() = JSONObject().put("cases", results).put("learningEnabled", false)
            .put("asset", asset).put("datasetSha256", fixtureHash).put("appVersion", packageInfo.versionName)
            .put("device", android.os.Build.MODEL).put("api", android.os.Build.VERSION.SDK_INT).put("runToken", runToken)
        fun serialized(words: List<Cand>) = JSONArray().apply {
            words.take(80).forEach { c -> put(JSONObject().put("word", c.w).put("consumed", c.n)
                .put("reading", c.py ?: "").put("correction", c.correction).put("abbreviation", c.abbr)
                .put("source", c.nativeChoice?.schema ?: "dictionary")) }
        }
        fun fail(result: JSONObject, text: String) {
            issues.add(result.getString("id") + ": " + text)
            result.getJSONArray("failures").put(text)
        }
        try {
            Lexicon.learningEnabled = false
            SpellingAssist.correctionFlags = 0
            Lexicon.fuzzyRules = emptySet()
            for ((plain, split) in listOf("xian" to "xi'an", "tian" to "ti'an", "nian" to "ni'an", "pian" to "pi'an", "dian" to "di'an", "zhuang" to "zhu'ang")) {
                check(HanReadings.complete(emptyList(), plain.map(Char::toString)).isNotEmpty()) { "Missing normal single-syllable coverage: $plain" }
                check(HanReadings.complete(emptyList(), split.map(Char::toString)).isEmpty()) { "Single-character coverage crossed explicit separator: $split" }
            }
            check(HanReadings.complete(emptyList(), "xian'".map(Char::toString)).any { it.w == "先" }) { "Trailing separator lost completed syllable" }
            report("Single-character coverage respects six internal separators and retains trailing-boundary candidates")
            PinyinEngine.candidatesLive("nihao".map(Char::toString), true, { true }, {})
            for (index in 0 until cases.length()) {
                val case = cases.getJSONObject(index)
                val raw = case.getString("input")
                val keys = raw.map(Char::toString)
                val count = raw.count { it != '\'' }
                Lexicon.fuzzyRules = case.words("fuzzy").toSet()
                SpellingAssist.correctionFlags = case.optInt("correction", 0)
                FcitxEngine.clear()
                val result = JSONObject().put("id", case.getString("id")).put("category", case.getString("category"))
                    .put("family", case.optString("family", case.getString("id")))
                    .put("input", raw).put("fuzzy", JSONArray(Lexicon.fuzzyRules.toList()))
                    .put("correction", SpellingAssist.correctionFlags).put("expectedTop3", JSONArray(case.words("expectedTop3")))
                    .put("reason", case.getString("reason")).put("failures", JSONArray()).put("review", JSONArray())
                results.put(result)
                try {
                    val started = SystemClock.elapsedRealtime()
                    val deadline = started + 15000
                    var firstMs: Long? = null
                    var primary = emptyList<Cand>()
                    val words = PinyinEngine.candidatesLive(keys, true, { SystemClock.elapsedRealtime() < deadline }) {
                        if (firstMs == null) { firstMs = SystemClock.elapsedRealtime() - started; primary = it.toList() }
                    }
                    result.put("firstBatchMs", firstMs ?: JSONObject.NULL).put("totalMs", SystemClock.elapsedRealtime() - started)
                        .put("firstBatch", serialized(primary)).put("candidates", serialized(words))
                    check(keys.joinToString("") == raw)
                    if (words.any { it.n !in 1..count }) fail(result, "Illegal candidate consumption length")
                    if (words.map { it.w to it.n }.distinct().size != words.size) fail(result, "Duplicate word/consumption candidates")
                    val full = words.filter { it.n == count }
                    val missing = case.words("mustInclude").filter { expected -> full.none { it.w == expected } }
                    if (missing.isNotEmpty()) {
                        val expanded = PinyinEngine.expandedCandidates(keys) { SystemClock.elapsedRealtime() < deadline }
                        result.put("expanded", serialized(expanded))
                        val absent = missing.filter { word -> expanded.none { it.w == word && it.n == count } }
                        if (absent.isNotEmpty()) fail(result, "Missing full candidates: " + absent.joinToString())
                        missing.filter { it !in absent }.forEach { result.getJSONArray("review").put("Only in expanded candidates: $it") }
                    }
                    case.words("forbiddenFull").filter { forbidden -> full.any { it.w == forbidden } }
                        .forEach { fail(result, "Disabled mapping or forced boundary leaked: $it") }
                    val top = words.take(3).map { it.w }
                    val expected = case.words("expectedTop3")
                    if (expected.isNotEmpty() && top.none { it in expected }) result.getJSONArray("review").put("Reference candidates absent from top 3")
                    case.words("watch").filter { it in top }.forEach { result.getJSONArray("review").put("High-risk candidate in top 3: $it") }
                    if (case.words("offAbsent").isNotEmpty() || Lexicon.fuzzyRules.isNotEmpty()) {
                        SpellingAssist.correctionFlags = 0; Lexicon.fuzzyRules = emptySet(); FcitxEngine.clear()
                        val off = PinyinEngine.candidatesLive(keys, true, { SystemClock.elapsedRealtime() < deadline }, {})
                        result.put("disabledCandidates", serialized(off))
                        val forbidden = case.words("offAbsent") + if (case.words("fuzzy").isNotEmpty()) {
                            (0 until cases.length()).map { cases.getJSONObject(it) }.firstOrNull { it.getString("input") == raw && it.words("fuzzy").isEmpty() }?.words("forbiddenFull").orEmpty()
                        } else emptyList()
                        forbidden.filter { word -> off.any { it.w == word && it.n == count } }.forEach { fail(result, "Switch-off leakage: $it") }
                    }
                    val walk = case.words("walk")
                    if (walk.isNotEmpty()) {
                        val trace = JSONArray(); var prefix = ""; var previousChars = 0
                        for (syllable in walk) {
                            prefix += syllable
                            val prefixStart = SystemClock.elapsedRealtime()
                            val found = PinyinEngine.candidatesLive(prefix.map(Char::toString), true, { SystemClock.elapsedRealtime() - prefixStart < 15000 }, {})
                            val first = found.firstOrNull()
                            trace.put(JSONObject().put("input", prefix).put("display", Lexicon.streamingDisplay(prefix.map(Char::toString)))
                                .put("totalMs", SystemClock.elapsedRealtime() - prefixStart).put("candidates", serialized(found.take(3))))
                            val chars = first?.w?.codePointCount(0, first.w.length) ?: 0
                            if (previousChars >= chars + 3) result.getJSONArray("review").put("Prefix coverage drop: $previousChars -> $chars at $prefix")
                            previousChars = chars
                        }
                        result.put("prefixTrace", trace)
                    }
                    report("${case.getString("id")} $raw -> ${top.joinToString(" / ")}; first=${firstMs}ms final=${result.getLong("totalMs")}ms failures=${result.getJSONArray("failures").length()} review=${result.getJSONArray("review").length()}")
                } catch (error: Exception) {
                    fail(result, error.javaClass.simpleName + ": " + error.message)
                    report("${case.getString("id")} ERROR: ${error.javaClass.simpleName}: ${error.message}")
                }
                File(out, "results.json").writeText(document().toString(2))
            }
        } finally {
            SpellingAssist.correctionFlags = previousFlags
            Lexicon.fuzzyRules = previousFuzzy
            Lexicon.learningEnabled = previousLearning
            FcitxEngine.clear()
        }
        check(Prefs.of(ctx).all == prefsBefore) { "Test changed user settings" }
        check(digest(personal) == personalBefore) { "Test changed personal learning data" }
        File(out, "results.json").writeText(document()
            .put("complete", true).put("settingsPreserved", true).put("personalDataPreserved", true).put("failures", JSONArray(issues)).toString(2))
        report("PINYIN ADVERSARIAL: $asset, ${cases.length()} cases, ${issues.size} rule failures; settings and personal learning data preserved")
        check(issues.isEmpty()) { issues.joinToString("; ") }
    }
}
