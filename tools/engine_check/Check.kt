// 在电脑上用 JVM 跑一遍拼音引擎：python 版的展示页验证过的输入，Kotlin 版也要给出同样的首选。
// 用法见 tools/engine_check/run.sh
import com.kongye.keyboard.Lexicon
import com.kongye.keyboard.HandwritingCandidates
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
    expect("nihao", "你好", "ni'hao")
    expect("kongye", "空叶", "kong'ye")
    expect("jianpan", "键盘", "jian'pan")
    expect("xiexie", "谢谢")
    expect("woaini", "我爱你")
    expect("zg", "中国", "z'g")
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
    // 九键：5426726 = jian pan
    val t9 = listOf("jkl", "ghi", "abc", "mno", "pqrs", "abc", "mno")
    val c9 = Lexicon.candidates(t9)
    if (c9.firstOrNull()?.w != "键盘") fail++
    println("九键 5426726 → " + c9.take(6).joinToString(" ") { it.w } + "  [" + Lexicon.display(t9, c9.firstOrNull()) + "]")
    // 14 键：ni hao = BN UI GH AS OP
    val k14 = listOf("bn", "ui", "gh", "as", "op")
    val c14 = Lexicon.candidates(k14)
    if (c14.firstOrNull()?.w != "你好") fail++
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
    println(if (fail == 0) "ALL OK" else "$fail FAILED")
    check(fail == 0) { "$fail checks failed" }
}
