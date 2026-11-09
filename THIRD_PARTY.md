# 离线语音资源

- 推理引擎：[sherpa-onnx v2.0.1](https://github.com/k2-fsa/sherpa-onnx/tree/v2.0.1)，Apache-2.0。构建脚本从官方 Release 获取 AAR 并校验 SHA256。
- 普通话模型：[sherpa-onnx-paraformer-zh-small-2024-03-09](https://huggingface.co/csukuangfj/sherpa-onnx-paraformer-zh-small-2024-03-09)，来自 [crazyant/speech_paraformer_asr_nat-zh-cn-16k-common-vocab8358-onnx](https://www.modelscope.cn/models/crazyant/speech_paraformer_asr_nat-zh-cn-16k-common-vocab8358-onnx/summary)。
- 四川话模型：[sherpa-onnx-paraformer-zh-int8-2025-10-07](https://huggingface.co/csukuangfj/sherpa-onnx-paraformer-zh-int8-2025-10-07)，转换自 [ASLP-lab/WSChuan-ASR / Paraformer-large-Chuan](https://huggingface.co/ASLP-lab/WSChuan-ASR/tree/main/Paraformer-large-Chuan)（上游模型标注 Apache-2.0）。

模型按固定提交版本下载，安装时核对完整 SHA256。模型权重不存放于 Git，也不打入 APK；各模型的使用条件以上游为准。解码验证使用上游公开 WAV 样本；语音启动检查短暂打开麦克风，音频仅在内存处理，不保存或上传。
