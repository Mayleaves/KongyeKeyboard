package com.kongye.keyboard

import android.content.Context

/** Bounded local prefix and edit-distance lookup using the bundled English vocabulary. */
object EnglishInput {
    @Volatile private var vocabulary:Map<String,List<String>> = emptyMap()
    private var byLength:Map<Int,List<String>> = emptyMap()
    private val names = mapOf(
        "github" to "GitHub", "gitlab" to "GitLab", "claude" to "Claude",
        "anthropic" to "Anthropic", "openai" to "OpenAI", "chatgpt" to "ChatGPT",
        "codex" to "Codex", "gemini" to "Gemini", "deepseek" to "DeepSeek",
        "android" to "Android", "iphone" to "iPhone", "ipad" to "iPad",
        "javascript" to "JavaScript", "typescript" to "TypeScript",
    )
    private val common=listOf("the","to","and","a","of","in","is","it","you","that","hello","world","thanks","keyboard","please","today","tomorrow")
    private val contractions=listOf("I'm","I'll","I've","I'd","don't","can't","won't","it's","you're","that's","we're","they're")
    @Synchronized fun load(ctx:Context) {
        if(vocabulary.isNotEmpty())return
        val words=LinkedHashSet<String>();words.addAll(common);words.addAll(names.keys)
        ctx.assets.open("glide-english.tsv").bufferedReader().useLines {lines->
            lines.forEach {line->val word=line.substringBefore('\t');if(word.length in 2..32 && word.all {it in 'a'..'z'}) words.add(word)}
        }
        byLength=words.groupBy {it.length}
        vocabulary=words.groupBy {it.take(1)}
    }
    fun literalOptions(raw:String):List<String> {
        if(raw.isEmpty()) return emptyList()
        if(raw.length!=1)return listOf(raw)
        val key=raw[0].lowercaseChar()
        val number="qwertyuiop".indexOf(key)
        return listOfNotNull(raw,if(raw[0].isUpperCase())raw.lowercase() else raw.uppercase(),
            if(number>=0)"1234567890"[number].toString() else null).distinct()
    }
    fun candidates(ctx:Context,raw:String,checkCorrection:Boolean=true):List<Cand> {
        load(ctx)
        val lower=raw.lowercase()
        val prefix=vocabulary[lower.take(1)].orEmpty().filter {it.startsWith(lower)}.take(12)
        val words=LinkedHashSet<String>();words.add(raw)
        if(lower=="i") words.add("I")
        words.addAll(prefix.take(4))
        val matchingContractions=if(lower.isEmpty())emptyList()
            else contractions.filter {it.lowercase().replace("'","").startsWith(lower)}
        words.addAll(matchingContractions)
        words.addAll(prefix.drop(4))
        if(checkCorrection && lower.length>=3 && V3Prefs.enabled(ctx,V3Prefs.EN_CORRECT)) {
            val nearby=(lower.length-1..lower.length+1).asSequence().flatMap {byLength[it].orEmpty()}
                .filter {it.length>2 && distance(lower,it)<=1}.take(8).toList()
            if(prefix.isEmpty() && matchingContractions.isEmpty() && nearby.isNotEmpty()) {
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
        }.distinctBy {it.w}
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
            val first=a.indices.first {a[it]!=b[it]}
            val second=(first+1 until a.length).firstOrNull {a[it]!=b[it]} ?: return 1
            return if(second==first+1 && a[first]==b[second] && a[second]==b[first] &&
                (second+1 until a.length).all {a[it]==b[it]})1 else 2
        }
        val short=if(a.length<b.length)a else b;val long=if(a.length<b.length)b else a
        var i=0;var j=0
        while(i<short.length && j<long.length) {if(short[i]==long[j])i++;j++}
        return if(i==short.length)1 else 2
    }
}
