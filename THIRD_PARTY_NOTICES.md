# 第三方数据与算法参考

## 万象拼音词库

作者：amzxyz；项目：[万象拼音 / rime-wanxiang](https://github.com/amzxyz/rime-wanxiang)。
许可证：[Creative Commons Attribution 4.0 International / CC BY 4.0](https://creativecommons.org/licenses/by/4.0/)。
固定来源、原文件及导出 SHA-256 见 [tools/data/wanxiang-source.json](tools/data/wanxiang-source.json)。
完整许可见 [tools/data/wanxiang-LICENSE.txt](tools/data/wanxiang-LICENSE.txt)，APK 内也附带许可及来源。

改动：合并基础词库与多音兼容词库，去掉声调，保留 2–8 字汉字词，去重后选取词频最高的十万条。
构建 APK 时使用普通话音节白名单验证读音，将词频转为对数分值，与原词库及空叶常用词合并。
没有集成万象的 Rime 引擎、Lua 或语言模型。此署名不代表原作者认可或参与空叶键盘。

复现：按清单中的提交下载 `dicts/jichu.dict.yaml`、`dicts/duoyin.dict.yaml`、`LICENSE`，将提交写入 `commit.txt`；
执行 `python tools/import_wanxiang.py <下载目录>`，再执行 `tools/build_lexicon.py`。

## 拼音纠错算法参考

[SymSpell](https://github.com/wolfgarbe/SymSpell)，Wolf Garbe，MIT：参考其 symmetric delete 候选召回思路。
[simple-pinyin](https://github.com/OrangeX4/simple-pinyin)，OrangeX4，MIT：参考拼音划分及相邻字母颠倒纠错方案。
本项目使用独立 Kotlin 实现，未复制这些项目的源代码，加入了移动键盘邻键距离、词频排序和红字标记。

短输入最多纠正一处，六字母及以上最多两处；支持漏字母、多字母、相邻键替换、相邻字母颠倒。
正确全拼词优先。纠错提供候选，不会直接替换用户原文。
