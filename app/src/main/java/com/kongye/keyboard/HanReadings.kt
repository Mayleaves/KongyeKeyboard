package com.kongye.keyboard

import android.content.Context
import java.text.Normalizer

/** Full, polyphonic pinyin-data coverage. Native dictionaries keep word ranking. */
@androidx.annotation.Keep
object HanReadings {
    @Volatile private var readings:Map<String,List<String>> = emptyMap()
    @Volatile private var bySyllable:Map<String,List<String>> = emptyMap()
    fun plain(value:String)=Normalizer.normalize(value.lowercase(),Normalizer.Form.NFD)
        .replace("u\u0308","v").filter {it in 'a'..'z' || it==' ' || it=='\''}

    @Synchronized fun load(ctx:Context) {
        if(readings.isNotEmpty())return
        val map=LinkedHashMap<String,List<String>>()
        ctx.assets.open("handwriting-readings.tsv").bufferedReader().useLines {lines->lines.forEach {line->
            val fields=line.split('\t')
            if(fields.size==2)map[fields[0]]=fields[1].split(" / ").map(::plain).filter {it.isNotEmpty()}.distinct()
        }}
        val index=HashMap<String,MutableList<String>>()
        map.forEach {(word,sounds)->sounds.forEach {sound->index.getOrPut(sound){ArrayList()}.add(word)}}
        bySyllable=index;readings=map
    }

    fun options(character:String)=readings[character].orEmpty()

    fun resolve(text:String,typed:String,tokens:List<String> = emptyList()):String {
        val chars=text.codePoints().toArray().map {String(Character.toChars(it))}
        if(chars.size>1) {
            val original=Lexicon.pinyinOf(text)
            if(SpellingAssist.matchesTypedReading(typed,original))return original
        }
        return chars.mapIndexed {index,char->
            val input=if(chars.size==1)typed else tokens.getOrNull(index).orEmpty()
            val normalized=plain(input)
            val sounds=options(char)
            sounds.firstOrNull {it==normalized}
                ?: sounds.firstOrNull {SpellingAssist.matchesTypedReading(input,it)}
                ?: sounds.firstOrNull() ?: Lexicon.pinyinOf(char)
        }.joinToString(" ")
    }

    fun complete(candidates:List<Cand>,keys:List<String>):List<Cand> {
        val base=(Lexicon.userCandidates(keys,true)+candidates).distinctBy {it.w}
        val letters=keys.filter {it!="'"}
        if(letters.size !in 1..6 || letters.isEmpty())return base
        // These extras are single-syllable characters. An internal explicit
        // separator must not be erased to synthesize a different full match.
        val first=keys.indexOfFirst {it!="'"}
        val last=keys.indexOfLast {it!="'"}
        if((first+1 until last).any {keys[it]=="'"})return base
        val seen=base.mapTo(HashSet()) {it.w}
        val extra=ArrayList<Cand>()
        for((sound,chars) in bySyllable) {
            if(sound.length!=letters.size)continue
            if(!sound.indices.all {i->sound[i] in letters[i] || Prefs.FUZZY_V_U in Lexicon.fuzzyRules &&
                    sound[i] in "uv" && letters[i].any {it in "uv"}})continue
            for(char in chars)if(seen.add(char))extra.add(Cand(char,letters.size,Entry(arrayOf(sound),char,0),-30f,py=sound))
        }
        if(extra.isEmpty())return base
        extra.sortBy {if(it.w.codePointAt(0) in 0x4E00..0x9FFF)0 else 1}
        val literal=base.filter {it.w.all {c->c.code<128}}
        return base.filterNot {it in literal}+extra+literal
    }
}
