# 4.0 拼音切分与验证

## 引擎与资料

- 小企鹅的 [libime](https://github.com/fcitx/libime) 负责全拼候选的音节图搜索、词典匹配与语言模型解码。项目使用的官方 Android 二进制和词库版本、摘要记录在 `app/src/main/assets/fcitx-sources.json`。
- [CyIME](https://github.com/Cyletix/CyIME) 和 [Xime](https://github.com/ximeiorg/Xime) 使用 Rime。空叶保留 Rime 处理九键、十四键及无简拼查询，其版本记录在 `app/src/main/assets/licenses/rime-sources.json`。
- 上游 [pinyinencoder.cpp](https://github.com/fcitx/libime/blob/master/src/libime/pinyin/pinyinencoder.cpp) 构造拼音图，[decoder.cpp](https://github.com/fcitx/libime/blob/master/src/libime/core/decoder.cpp) 进行词典与语言模型解码。
- Rime 的 [syllabifier.cc](https://github.com/rime/librime/blob/master/src/rime/algo/syllabifier.cc) 可对照音节图构建；[朙月拼音方案](https://github.com/rime/rime-luna-pinyin/blob/master/luna_pinyin.schema.yaml) 可对照分隔符、拼写运算和预编辑显示配置。方案配置、引擎源码和词库分别影响结果，单独的 tokenizer 不能验证中文候选质量。

## 开源复用核对

2026-11-23 按实际调用链核对。`PinyinEngine.candidatesLive` 在原生引擎可用、26 键并开启简拼时调用 `FcitxEngine.candidatesLive`；九键、十四键和无简拼查询进入 Rime 路径。引擎不可用时另有基础词典回退。

| 原任务 | 4.0 实现与验证位置 |
| --- | --- |
| 1、2 音节与拼写规则 | 应用白名单和下文规则；407 个编码逐项检查基础词典与原生候选覆盖。该列表代表当前应用支持集，不宣称是所有拼音方案的唯一标准。 |
| 3 原始输入 | 实际 IME 快速输入测试检查按键串完整；可选日志记录原始串。 |
| 4、5 多路径切分 | 原生引擎解析原始输入；`PinyinSegmentation` 的合法图用于显示回退和诊断。应用枚举的前 64 条路径仅供日志，不作为原生解码的候选路径上限。 |
| 6 解码与语言模型 | 复用 libime/Rime 原生解码；应用层没有另写一个 Viterbi 模型。 |
| 7、8、9 边界与歧义 | 手动隔音符、全部音节和 `xian/pingan/xiana/qina/zhangai/shanxi` 回归。 |
| 10、11 编码与排序 | 检查 148118 条基础词典编码；原生词库及语言模型提供候选，应用还会做补全和重排。 |
| 12 开关 | 16 种纠错配置组合及关闭纠错时的双向 v/u 模糊音回归。 |
| 13、14 长句与诊断 | IME 快速输入和长句候选测试；原始串、合法图、显示切分、最终读音日志。 |

当前结果经过 `FcitxEngine` 的简拼补全、纠错候选合并，以及 `PinyinExpressions` 插入等处理，不能把最终排序称为未经修改的原生排序。显示回退的最长音节偏好也不等于最终候选的语言模型评分。排查差异时依次核对原始串、原生结果、应用合并结果和显示切分，避免把显示分隔符错误与候选解码错误混在一起。

已有回归证明这些测试输入的覆盖和开关行为，尚不能证明与所有主流输入法排序一致，也不能证明所有长句都足够流畅。模拟器现有完整查询日志中部分长句约需 0.8–1.4 秒；该耗时包含查询处理，不是首选出现延迟或真机按键延迟的测量结果。

许可证应逐组件查阅。[librime 核心](https://github.com/rime/librime/blob/master/LICENSE) 使用 BSD-3-Clause，不是 GPLv3；[libime 解码源码](https://github.com/fcitx/libime/blob/master/src/libime/core/decoder.cpp) 标记为 LGPL-2.1-or-later。词库、插件、前端和二进制依赖另有各自许可，项目已附带的许可材料位于 `app/src/main/assets/licenses/`。不根据“Rime”这个名称统一推断全部依赖的许可。

## 合法音节与编码

`pinyin-syllables.txt` 列出基础词典的 407 个合法编码，测试逐项核对词典首行及原生引擎候选覆盖。含完整的整体认读音节，以及 m、ng、hng 等少量口语读音。

- j、q、x 后的 ü 使用标准编码 u，例如 ju、qu、xu。n、l 后使用 v，例如 nv、lv、nve、lve。
- zh、ch、sh 是双字母声母，整体参与音节匹配。
- iu、ui 为省写韵母。ou、ang、eng 等必须和声母组成白名单里的完整音节。
- `'` 是强制音节边界。`xi'an` 限制为“西安”的切分，`xian` 可以保留 xian 和 xi/an 两条合法路径。
- v/u 键位模糊音是可选配置，与标准 ü 编码分别处理。新安装的模糊音规则集合为空，已有用户选择继续沿用。

## 数据流

1. UI 保存原始字母及手动隔音符，不用候选反写按键。
2. 407 音节白名单形成 DAG，每个位置保留所有合法长度的边。图边按长音节优先排列，完整路径仍可回溯。
3. 候选查询把原始编码送给已集成的 libime/Rime，由原生图搜索、词频和语言模型完成候选解码。应用层诊断枚举不截断原生查询。
4. 候选未完成时，显示层从合法图选出完整切分，尾部允许未完成音节和声母简拼。候选完成后采用能与原始字母对齐的读音边界。
5. 自动分界允许随新按键重新判断。比如 xian 后输入 zai 时，要能回溯到 xian/zai。手动隔音符继续限制边界。
6. 原有常用词与整句排序、纠错开关、模糊音配置在原生解码结果上工作。

歧义输入通常没有唯一正确结果。`shanxi` 同时可表示山西、陕西，词语及上下文参与排序。`pingan` 同时保留 ping/an 和 pin/gan。最长音节优先用于无上下文的显示回退，最终候选由语言模型选择。

## 调试与测试

模拟器测试模式 `pinyin-400` 检查全部音节、歧义路径、隔音符、逐字输入保持及原生候选。模式 `fcitx` 覆盖常用词、混合简拼和长句。模式 `layout-400` 检查各类设置示例在 264、320、392、460 dp 下的文字边界。模式 `pinyin-live-400` 在独立输入框快速输入长句，并验证放大气泡在上屏后恢复。

全音节覆盖时关闭纠错和模糊音，407/407 个音节都有覆盖完整编码的汉字候选。148118 条基础词库编码逐条核对，修正“嗯了一声”的旧编码为 `en'le'yi'sheng`。歧义回归同时核对 `xian` 的“先 / 西安”、`xi'an` 的“西安”、`shanxi` 的“山西 / 陕西”，避免把可接受的多种结果误当成唯一答案。

开发时可用 `adb -s <设备> shell setprop log.tag.KongyePinyin DEBUG` 临时打开诊断，并用 `adb -s <设备> logcat -s KongyePinyin` 查看原始编码、完整图边、前 64 条可行路径、临时显示、最终候选读音。图保留全部合法边，日志限制路径展开数量，以控制长句的组合爆炸。

日志默认关闭。仅在明确打开日志后打印普通输入，密码框和工具内部输入不打印。结束后用 `setprop log.tag.KongyePinyin INFO` 关闭。诊断会包含测试输入，请使用虚构文字。


## 主动选词学习

用户学习在候选生成之后参与重排，使用候选的标准音节保存记录。普通点按按下的字母与显示用拼音保持独立。

- P 使用现有 `user_lexicon.txt`，保存词语、标准读音、次数和最后选择时间。旧版四列记录可读，新记录带第五列时间。
- Q 相同读音的个人候选按选择次数和最近使用时间排序，关闭“记住选词”后忽略个人候选与学习加分。
- R 只记录用户主动点击的非首位中文候选。空格、标点、首位候选和连续上屏不增加学习次数。原生解码器使用独立基础状态，避免历史自动确认权重干扰新的学习规则。
- S 最多保留最近 4096 条学习记录，超出时删除最久未选择的记录。
- T 使用“记住选词”和“清除选词记录”入口。备份与恢复兼容旧记录，并保留新记录的时间。

`learning-400` 验证计数、标准读音、学习开关、模糊音复用、记录上限和备份兼容。`pinyin-live-400` 验证实际上屏时首选不学习、手动选择其他候选后能够调频。
