package com.kongye.keyboard

/** OCR 没有备选接口，补充的形近字和联想词排在原始识别结果后面。 */
object HandwritingCandidates {
    private val similar = listOf(
        "大太犬天夫夭", "人入八", "十土士干千于", "日目白田由甲申电", "口囗回吕",
        "未末木本术禾", "王玉主生", "己已巳巴", "山出凶", "水永冰泉", "火灭灰炎",
        "小少沙", "刀力九丸", "牛午年", "乌鸟马", "月用甩", "贝见页", "手毛乎",
        "子了予", "今令金", "石右古舌", "文丈又义", "干开井并", "上止正下", "几儿凡",
        "好如妈她", "晴睛情清", "问间闻闷", "青清请情", "休体住往", "白百自首",
    )

    fun expand(recognized: List<String>, associate: (String) -> List<String>): List<String> {
        val originals = recognized.filter { it.isNotBlank() }.distinct()
        if (originals.isEmpty()) return emptyList()
        val out = LinkedHashSet(originals)
        val first = originals.first()
        if (first.length == 1) {
            similar.filter { first[0] in it }.forEach { group ->
                group.forEach { if (out.size < 10) out.add(it.toString()) }
            }
        }
        associate(first).take(4).forEach { out.add(first + it) }
        return out.take(16)
    }
}
