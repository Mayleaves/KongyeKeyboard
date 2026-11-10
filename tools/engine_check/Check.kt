// 在电脑上用 JVM 跑一遍拼音引擎：python 版的展示页验证过的输入，Kotlin 版也要给出同样的首选。
// 用法见 tools/engine_check/run.sh
import com.kongye.keyboard.Lexicon
import com.kongye.keyboard.HandwritingCandidates
import com.kongye.keyboard.PinyinCorrection
import java.io.FileInputStream

fun main(args: Array<String>) {
    val t0 = System.nanoTime()
    Lexicon.loadFrom(FileInputStream(args[0]), null)
    println("load ms: " + (System.nanoTime() - t0) / 1_000_000)
    var fail = 0
    fun expect(input: String, top: String, disp: String? = null) {
        val t = System.nanoTime()
        val c = Lexicon.candidates(input)
        val us = (System.nanoTime() - t) / 1000
        val d = Lexicon.display(input, c.firstOrNull())
        val ok = c.firstOrNull()?.w == top && (disp == null || d == disp)
        if (!ok) fail++
        println("${if (ok) "ok  " else "FAIL"} $input → ${c.take(6).joinToString(" ") { it.w }}  [$d]  ${us}µs")
    }
    for ((raw, shown) in listOf("nhy" to "n'h'y", "zg" to "z'g", "nh" to "n'h", "zhg" to "zh'g", "nihao" to "ni'hao", "nihaoy" to "ni'hao'y")) {
        check(Lexicon.display(raw, null) == shown) { "Unstable abbreviation display: $raw -> ${Lexicon.display(raw, null)}" }
    }
    expect("nain", "年")
    expect("sih", "是")
    expect("jain", "建")
    expect("nihao", "你好", "ni'hao")
    expect("kongye", "空叶", "kong'ye")
    expect("jianpan", "键盘", "jian'pan")
    expect("xiexie", "谢谢")
    expect("woaini", "我爱你")
    expect("zg", "这个", "z'g")
    check(Lexicon.candidates("zg").take(3).any {it.w=="中国"})
    expect("wm", "我们")
    expect("shi", "是")
    expect("xian", "现")
    expect("u", "u", "u")
    expect("jintiantianqibucuo", "今天天气不错")
    expect("n", "年")
    expect("zhon", "中", "zhon")
    // 分词：xi'an 只能是两个音节
    expect("xi'an", "西安", "xi'an")
    expect("xian", "现")
    // 分次选词后记成新词
    Lexicon.learnPhrase("今天天气", listOf("jin", "tian", "tian", "qi"))
    expect("jintiantianqi", "今天天气")
    // 纠错：nihai 里应该有“你好”，拼音 ni hao，o 标红
    val nh = Lexicon.candidates("nihai")
    println("nihai → " + nh.take(8).joinToString(" ") { c -> c.w + (c.py?.let { p -> "(" + p.mapIndexed { i, ch -> if (c.mask?.getOrNull(i) == true) ch.uppercaseChar() else ch }.joinToString("") + ")" } ?: "") })
    check(nh.first().w=="你还" && nh.take(6).any {it.w=="你好"}) {"Valid spelling must stay first without suppressing nearby correction"}
    check(Lexicon.candidates("zhenbang").first().mask==null)
    // 九键：5426726 = jian pan
    val t9 = listOf("jkl", "ghi", "abc", "mno", "pqrs", "abc", "mno")
    val c9 = Lexicon.candidates(t9)
    if (c9.firstOrNull()?.w != "键盘") fail++
    println("九键 5426726 → " + c9.take(6).joinToString(" ") { it.w } + "  [" + Lexicon.display(t9, c9.firstOrNull()) + "]")
    // 14 键：ni hao = BN UI GH AS OP
    val k14 = listOf("bn", "ui", "gh", "as", "op")
    val c14 = Lexicon.candidates(k14)
    if (c14.take(3).none {it.w == "你好"}) fail++
    println("14键 nihao → " + c14.take(6).joinToString(" ") { it.w } + "  [" + Lexicon.display(k14, c14.firstOrNull()) + "]")
    println("分词 → " + Lexicon.segment("今天天气不错，我们去公园散步吧。Hello 123").joinToString("|"))
    println("拼音 → " + Lexicon.pinyinOf("空叶键盘"))
    println("assoc 你好 → " + Lexicon.assoc("你好").take(6))
    println("assoc 空叶 → " + Lexicon.assoc("空叶").take(6))
    println("decode xian → " + Lexicon.decode("xian") + ", xianzai → " + Lexicon.decode("xianzai"))
    val handwriting = HandwritingCandidates.expand(listOf("大"), Lexicon::assoc)
    println("手写 大 → $handwriting")
    if (handwriting.firstOrNull() != "大" || "太" !in handwriting || "天" !in handwriting || handwriting.none { it.startsWith("大") && it.length > 1 }) fail++
    if (HandwritingCandidates.expand(emptyList(), Lexicon::assoc).isNotEmpty()) fail++
    if (HandwritingCandidates.expand(listOf("你好", "你", "好"), Lexicon::assoc).take(3) != listOf("你好", "你", "好")) fail++
    // 模糊音：打开 z=zh、n=l 后 zongguo → 中国、lihao → 你好，候选吃掉的键数按原输入算
    Lexicon.fuzzyRules = setOf("z-zh", "n-l")
    val fz1 = Lexicon.candidates("zongguo")
    println("模糊 zongguo → " + fz1.take(5).joinToString(" ") { it.w } + "  [" + Lexicon.display("zongguo", fz1.firstOrNull()) + "]")
    if (fz1.take(3).none { it.w == "中国" } || fz1.first { it.w == "中国" }.n != 7) fail++
    val fz2 = Lexicon.candidates("lihao")
    println("模糊 lihao → " + fz2.take(5).joinToString(" ") { it.w })
    if (fz2.take(3).none { it.w == "你好" } || fz2.first { it.w == "你好" }.n != 5) fail++
    val fz3 = Lexicon.candidates("nihao")
    if (fz3.firstOrNull()?.w != "你好") fail++
    println("模糊 nihao → " + fz3.take(5).joinToString(" ") { it.w })
    Lexicon.fuzzyRules = emptySet()
    // 关掉模糊音后 lihao 不应再出你好
    if (Lexicon.candidates("lihao").take(5).any { it.w == "你好" }) fail++
    // 记忆：全拼打过的“夏星月”，之后简拼 xxy 排第一
    Lexicon.fuzzyRules=setOf("z-zh","c-ch","s-sh","n-l","f-h","r-l","an-ang","en-eng","in-ing","ian-iang","uan-uang")
    expect("nain","年")
    expect("nihao","你好")
    Lexicon.fuzzyRules=emptySet()
    val beforeLearning=Lexicon.candidates("xxy")
    Lexicon.learnPhrase("夏星月", listOf("xia", "xing", "yue"))
    val mem = Lexicon.candidates("xxy")
    check(mem!==beforeLearning) {"Learning did not invalidate old suggestions"}
    println("记忆 xxy → " + mem.take(5).joinToString(" ") { it.w })
    if (mem.firstOrNull()?.w != "夏星月") fail++
    // Real typo classes: neighbor key, omitted/duplicated letter, transposition and first-letter error.
    for(input in listOf("nihoa","mihao","nhao","nihaoo")) expect(input,"你好")
    expect("jainpan","键盘")
    expect("zhonhguo","中国")
    for(input in listOf("zhrnbang","zhembang","zhrmbang","zhenbng","zhenbgang","zhenbnag")) expect(input,"真棒")
    for((input,word) in listOf("jianpn" to "键盘","jiaanpan" to "键盘","zhonguo" to "中国","zhongguoo" to "中国","zhenbngg" to "真棒")) {
        val c=Lexicon.candidates(input)
        check(c.take(6).any {it.w==word}) {"Typo $input lacks $word: ${c.take(6).map {it.w}}"}
    }
    for((input,word) in listOf("meiwenti" to "没问题","nengbuneng" to "能不能","shejiao" to "社交","chongqing" to "重庆","yinhang" to "银行","yinyue" to "音乐")) {
        val result=Lexicon.candidates(input)
        if(result.take(3).none {it.w==word}) {fail++;println("FAIL lexicon $input lacks $word")}
    }
    Lexicon.fuzzyRules=setOf("z-zh","en-eng")
    for((input,word) in listOf("zong'guo" to "中国","nenbunen" to "能不能")) {
        val result=Lexicon.candidates(input)
        if(result.take(3).none {it.w==word}) {fail++;println("FAIL fuzzy $input lacks $word")}
    }
    Lexicon.fuzzyRules=emptySet()
    val groups=listOf("abc","def","ghi","jkl","mno","pqrs","tuv","wxyz")
    val learnedNine="xiaxingyue".map {ch -> groups.first {ch in it}}
    check(Lexicon.candidates(learnedNine).take(12).any {it.w=="夏星月"}) {"Learned word missing from indexed nine-key lookup"}
    // Retrieval must survive edits at both ends and across the seven-letter index boundary.
    val originals=listOf("zhenbang","zhongguo","jianpan","jintiantianqi","gongzuoshunli","chongqing","beijing","xiexieni")
    val correction=PinyinCorrection(originals)
    var editsChecked=0
    for(word in originals) {
        val variants=linkedSetOf<String>()
        for(i in word.indices) {
            variants.add(word.removeRange(i,i+1))
            variants.add(word.substring(0,i)+word[i]+word.substring(i))
            if(i<word.lastIndex) variants.add(word.substring(0,i)+word[i+1]+word[i]+word.substring(i+2))
        }
        if(word.length>=8) {
            variants.add(word.drop(1).dropLast(1))
            variants.add("q"+word+"p")
            variants.add(word.removeRange(1,2)+"q")
        }
        for(typed in variants.filter {it.length>=4 && it!=word && !word.startsWith(it)}) {
            check(correction.lookup(typed).any {it.spelling==word}) {"Delete index lost $typed → $word"}
            editsChecked++
        }
    }
    println("verified typo retrieval variants: $editsChecked")
    val correctionMs=ArrayList<Long>()
    repeat(5) {for(input in listOf("zhrmbang","zhenbgang","jianpn","zhongguoo","jintiantianqibucuu")) {
        val start=System.nanoTime();correction.lookup(input);correctionMs.add(System.nanoTime()-start)
    }}
    correctionMs.sort();println("uncached small-index correction p95 us: "+correctionMs[(correctionMs.size*.95).toInt()]/1000)
    val cache0=Lexicon.candidates("nihoa")
    check(cache0===Lexicon.candidates("nihoa")) {"Repeated query did not reuse cached result"}
    val samples=listOf("n","shi","nihoa","zhonhguo","jintiantianqibucuo")
    val timings=ArrayList<Long>()
    repeat(100) { for(input in samples) {val start=System.nanoTime();Lexicon.candidates(input);timings.add(System.nanoTime()-start)} }
    timings.sort();println("cached candidates p95 us: "+timings[(timings.size*.95).toInt()]/1000)
    println(if (fail == 0) "ALL OK" else "$fail FAILED")
    check(fail == 0) { "$fail checks failed" }
}
