package com.kongye.keyboard

import android.content.Context

/** Bounded local prefix and edit-distance lookup using the bundled English vocabulary. */
object EnglishInput {
    @Volatile private var vocabulary:Map<String,List<String>> = emptyMap()
    private val names = mapOf(
        "github" to "GitHub", "gitlab" to "GitLab", "claude" to "Claude",
        "anthropic" to "Anthropic", "openai" to "OpenAI", "chatgpt" to "ChatGPT",
        "codex" to "Codex", "gemini" to "Gemini", "deepseek" to "DeepSeek",
        "android" to "Android", "iphone" to "iPhone", "ipad" to "iPad",
        "javascript" to "JavaScript", "typescript" to "TypeScript",
    )
    private val common=listOf("the","to","and","a","of","in","is","it","you","that","hello","world","thanks","keyboard","please","today","tomorrow")
    @Synchronized fun load(ctx:Context) {
        if(vocabulary.isNotEmpty())return
        val words=LinkedHashSet<String>();words.addAll(common);words.addAll(names.keys)
        ctx.assets.open("glide-english.tsv").bufferedReader().useLines {lines->
            lines.forEach {line->val word=line.substringBefore('\t');if(word.length in 2..32 && word.all {it in 'a'..'z'}) words.add(word)}
        }
        vocabulary=words.groupBy {it.take(1)}
    }
    fun candidates(ctx:Context,raw:String):List<Cand> {
        load(ctx)
        val lower=raw.lowercase()
        val prefix=vocabulary[lower.take(1)].orEmpty().filter {it.startsWith(lower)}.take(12)
        val words=LinkedHashSet<String>();words.add(raw);words.addAll(prefix)
        if(V3Prefs.enabled(ctx,V3Prefs.EN_CORRECT)) {
            val nearby=vocabulary.values.asSequence().flatten().filter {kotlin.math.abs(it.length-lower.length)<=1 && it.length>2}
                .filter {distance(lower,it)<=1}.take(8).toList()
            if(lower.length>=3 && prefix.isEmpty() && lower !in vocabulary[lower.take(1)].orEmpty() && nearby.isNotEmpty()) {
                words.clear();words.addAll(nearby);words.add(raw)
            } else words.addAll(nearby)
        }
        return words.take(20).map {word->
            val display=when {
                raw.length>1 && raw.all {it.isUpperCase()} -> word.uppercase()
                raw.firstOrNull()?.isUpperCase()==true -> word.replaceFirstChar {it.uppercase()}
                else -> word
            }
            Cand(display,raw.length,null,0f)
        }
    }
    fun mixed(ctx:Context,raw:String,base:List<Cand>):List<Cand> {
        if(!V3Prefs.enabled(ctx,V3Prefs.MIXED) || raw.any {it !in 'a'..'z'})return emptyList()
        load(ctx)
        return base.asSequence().filter {it.n in 2 until raw.length-2 && it.w.any {ch->ch in '一'..'鿿'}}
            .filter {it.py?.replace(" ","")?.replace("'","")==raw.take(it.n)}
            .sortedByDescending {it.n}.mapNotNull {prefix->
                val tail=raw.drop(prefix.n)
                if(tail !in vocabulary[tail.take(1)].orEmpty())null else Cand(prefix.w+tail,raw.length,null,prefix.score)
            }.distinctBy {it.w}.take(2).toList()
    }
    /** Exact English words are offered independently of Chinese/English mixed input. */
    fun recognize(ctx: Context, raw: String, base: List<Cand>): List<Cand> {
        if (!V3Prefs.enabled(ctx, V3Prefs.RECOGNIZE_ENGLISH, true) ||
            raw.length < 2 || raw.any { it !in 'a'..'z' }) return base
        load(ctx)
        if (raw !in vocabulary[raw.take(1)].orEmpty()) return base
        val word = names[raw] ?: raw
        val candidate = Cand(word, raw.length, null, 0f,inputDisplay=raw)
        val result = base.filterNot { it.w == word && it.n == raw.length }.toMutableList()
        // An English word that is also full pinyin keeps Chinese as the first choice.
        val pinyin = Lexicon.decode(raw)
        val completePinyin = pinyin.joinToString("") == raw &&
            pinyin.all { it in Lexicon.pinyinSyllables() }
        val position = if (raw in names || raw.length >= 3 && !completePinyin) 0 else minOf(1, result.size)
        result.add(position, candidate)
        return result
    }
    /** An exact recognized English choice keeps its literal spelling in composing UI. */
    fun literalSpelling(raw:String,first:Cand?):String? = raw.takeIf {
        raw.isNotEmpty() && raw.all {it in 'a'..'z'} && first?.inputDisplay==raw &&
            first.n==raw.length && first.py.isNullOrEmpty() && first.w.equals(raw,ignoreCase=true)
    }

    private fun distance(a:String,b:String):Int {
        if(a==b)return 0
        if(a.length==b.length) {
            val different=a.indices.filter {a[it]!=b[it]}
            if(different.size==1)return 1
            if(different.size==2) {val x=different[0];val y=different[1];if(y==x+1 && a[x]==b[y] && a[y]==b[x])return 1}
            return 2
        }
        val short=if(a.length<b.length)a else b;val long=if(a.length<b.length)b else a
        var i=0;var j=0
        while(i<short.length && j<long.length) {if(short[i]==long[j])i++;j++}
        return if(i==short.length)1 else 2
    }
}
