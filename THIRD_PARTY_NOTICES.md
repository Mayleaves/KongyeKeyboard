# 第三方数据与算法参考

空叶键盘项目源代码按 GPL-3.0-only 分发；下列第三方组件、模型与数据保留各自许可和版权。

## 2.1.0 实际接入的输入核心

| 部件 | 上游 | 许可与版本 |
| --- | --- | --- |
| 拼音解码、字典、分词、简拼、整句生成 | [rime/librime](https://github.com/rime/librime) | BSD-3-Clause，1.17.0 |
| 整句语法模型插件 | [lotem/librime-octagram](https://github.com/lotem/librime-octagram) | GPL-3.0，`dfcc15115788c828d9dd7b4bff68067d3ce2ffb8` |
| 候选边界元数据适配 | [hchunhui/librime-lua](https://github.com/hchunhui/librime-lua) | BSD-3-Clause，`6da5d6cedb853380ed1b5b9b6979cb1a9c028353` |
| 合并在上游运行库中的 Predict 插件 | [rime/librime-predict](https://github.com/rime/librime-predict) | BSD-3-Clause，`920bd41ebf6f9bf6855d14fbe80212e54e749791`；空叶方案不启用预测插件 |
| Android 原生库载体与构建脚本 | [xkinput/keytao-app](https://github.com/xkinput/keytao-app) | 载体项目 MIT，v1.2.4；各原生组件仍按其各自许可分发 |
| C ABI 调用 | [Java Native Access](https://github.com/java-native-access/jna) | 5.17.0，选择 Apache-2.0 |
| 主拼音词库 | [iDvel/rime-ice](https://github.com/iDvel/rime-ice) | GPL-3.0，`99662ba01e3affa6a0f009174ca530b887bac3b6`；原始 8105、base、ext、tencent、others 表未修改 |
| 拼音表情关联 | 同一雾凇提交的 `opencc/emoji.txt` | GPL-3.0；用完整拼音与词条关联，另添加本项目的符号及颜文字映射，见 `tools/data/expression-overrides.json`；不修改中文词条及词频 |
| LTS 语法模型和注音辅助 | [amzxyz/rime-wanxiang](https://github.com/amzxyz/rime-wanxiang)、[数据项目](https://github.com/amzxyz/rime-wanxiang-data) | CC BY 4.0，方案包 v18.1.11，LTS 模型以 SHA256 固定 |
| 中文神经网络手写 | [ximeiorg/ochwpro](https://github.com/ximeiorg/ochwpro)、bikeand 的 ModelScope 模型 | MIT，源码 `54c326d33d86cc0754dbea5a36c1448682c8202c`；模型及字表摘要见资源清单 |
| 神经网络手写运行时 | [microsoft/onnxruntime](https://github.com/microsoft/onnxruntime) | MIT，官方 Android 1.28.0；为避免与语音运行库冲突，仅等长修改 ELF SONAME/DT_NEEDED 为 libkongye_ort_.so，未改变计算代码 |
| 开源笔画识别 | [gugray/hanzi_lookup](https://github.com/gugray/hanzi_lookup) | LGPL-3.0，`01f90c3ab99a8fadf0696c28e5eb097223c500db`；原始 JS/WASM 未修改 |
| 手写笔画数据 | HanziLookup 内嵌 Make Me a Hanzi / Arphic 衍生数据 | Arphic Public License，9,507 字 |
| 带调字音和多音字数据 | [mozillazg/pinyin-data](https://github.com/mozillazg/pinyin-data) | MIT，`fe29ad32bdf352b31a02b9936e537f575432e9a6` |
| 流式语音与原有离线模型运行时 | [k2-fsa/sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx) | Apache-2.0，2.0.1 |
| 普通话流式模型 | [官方转换模型](https://huggingface.co/csukuangfj/sherpa-onnx-streaming-zipformer-small-ctc-zh-int8-2025-04-01)、[原始 checkpoint](https://huggingface.co/csukuangfj/icefall-streaming-zipformer-small-ctc-zh-2025-04-01) | Apache-2.0，转换模型提交 `a5f60fe00dcfbaf68fcc1c6b5cf53061e144d6da` |

Rime 主词库直接导入雾凇原始字表、基础、扩展、腾讯和补充表，共 1,873,493 行，保留上游词频及注音规则。
全拼、14 键和九键各自编译拼写图，共用词库和语言模型。没有按测试句子加入特例词。

手写显示转换：pinyin-data 转成 Unicode 规范化的带调 TSV；保留单字的多个读音。
万象词典中选频率最高的六万常用词作带调词组注音，单字多音提示不与词组读音混淆。
主手写调用原始 ochwpro ONNX 模型；前端按上游五维轨迹特征归一化并给连笔保留更多点。HanziLookup 原始 WASM 保留为初始化失败时的离线备用，私有页面禁止网络、文件和内容访问。

语音按官方 Android OnlineRecognizer 接口增量处理音频帧；普通话流式模型作为即时预览，已安装的高容量普通话模型作句末复核。
四川话使用原有川渝 Paraformer 专用模型。Dolphin 及其他方言模型仅作调研，未加入本版。

固定下载地址、摘要和处理脚本：`tools/fetch_rime.py`、`tools/fetch_handwriting.py`、`tools/fetch_neural_handwriting.py`、`tools/fetch_streaming_voice.py`。
原生库构建、完整对应源码包说明见 [Rime 原生构建](docs/Rime-原生构建.md)。
完整许可随 APK 位于 `assets/licenses/`；各项数据的第三方许可保持不变。

Rime 合并库还包含 Boost、glog、LevelDB、marisa、OpenCC、yaml-cpp、Lua 和 Android libc++；对应版权及许可同样保留在 APK 和原生源码包中。

## 旧辅助词库与算法

## 万象拼音词库

作者：amzxyz；项目：[万象拼音 / rime-wanxiang](https://github.com/amzxyz/rime-wanxiang)。
许可证：[Creative Commons Attribution 4.0 International / CC BY 4.0](https://creativecommons.org/licenses/by/4.0/)。
固定来源、原文件及导出 SHA-256 见 [tools/data/wanxiang-source.json](tools/data/wanxiang-source.json)。
完整许可见 [tools/data/wanxiang-LICENSE.txt](tools/data/wanxiang-LICENSE.txt)，APK 内也附带许可及来源。

改动：合并基础词库与多音兼容词库，去掉声调，保留 2–8 字汉字词，去重后选取词频最高的十万条。
构建 APK 时使用普通话音节白名单验证读音，将词频转为对数分值，与原词库及空叶常用词合并。
上述筛选方式用于旧 Kotlin 辅助词库；2.1.0 主拼音引擎的完整词库和模型见上一节。此署名不代表原作者认可或参与空叶键盘。

复现：按清单中的提交下载 `dicts/jichu.dict.yaml`、`dicts/duoyin.dict.yaml`、`LICENSE`，将提交写入 `commit.txt`；
执行 `python tools/import_wanxiang.py <下载目录>`，再执行 `tools/build_lexicon.py`。

## 拼音纠错算法参考

[SymSpell](https://github.com/wolfgarbe/SymSpell)，Wolf Garbe，MIT：参考其 symmetric delete 候选召回思路。
[simple-pinyin](https://github.com/OrangeX4/simple-pinyin)，OrangeX4，MIT：参考拼音划分及相邻字母颠倒纠错方案。
本项目使用独立 Kotlin 实现，未复制这些项目的源代码，加入了移动键盘邻键距离、词频排序和红字标记。

短输入最多纠正一处，六字母及以上最多两处；支持漏字母、多字母、相邻键替换、相邻字母颠倒。
正确全拼词优先。纠错提供候选，不会直接替换用户原文。


## Fcitx5 Android / libime (2.2.2)

26 键默认接入 [Fcitx5 Android 0.1.3](https://github.com/fcitx5-android/fcitx5-android/releases/tag/0.1.3)（发布源提交 048f581c）、其原生 Fcitx5/libime/Chinese Addons 和上游更新的 libime 模型。原生 JNI 与解码器保持上游实现，没有添加例词、例句排序覆盖。JNI ABI 对接类仅实现官方类名、构造签名与回调，未引入上游应用界面。

Fcitx5 Android、Fcitx5、libime 与 Chinese Addons 各组件保留其 LGPL-2.1-or-later / 组件源码中的许可；完整应用仍为 GPL-3.0-only。其他依赖遵守上游各自许可。固定来源、模型版本与全部下载 SHA256 见 `app/src/main/assets/fcitx-sources.json`，获取及复建脚本为 `tools/fetch_fcitx.py`。

为与已有原生模块隔离，上游 libc++ 的库名及依赖名等长改为 `libfcx_shared.so`，未修改算法或模型。9 键、14 键和关闭简拼时暂保留已接入的 Rime 路径，不伪称 Fcitx 原生支持这些布局。
