# 空叶键盘

[下载APK](https://github.com/Mayleaves/KongyeKeyboard/releases/latest) · [更新日志](https://github.com/Mayleaves/KongyeKeyboard/releases)

空叶键盘是一款开源的 Android 中文输入法，支持全拼、简拼、中英混输、手写和语音输入。基础中英文输入与单字手写可离线使用，整句增强词库、语音和翻译模型可在「离线资源」中按需下载。

## 主要功能

- **拼音与候选**支持全拼、简拼和混合输入，可按需开启模糊音、拼写纠错和英文单词识别。支持自造词、联想、候选翻页和字词筛选。
- **单字手写**支持笔画撤回和重做，候选可显示带声调的拼音。**语音输入**支持普通话和四川话，识别结果直接写入输入框。
- **布局与手势**支持 26 键、14 键和九键。横竖屏可分别选择正常、分体或悬浮键盘。可调整键盘尺寸、底部留白和工具栏，设置上下滑动作或用空格移动光标。
- **编辑与工具**提供撤回、重做、文字和图片剪贴板、表情、符号、翻译、短信验证码自动复制和打字统计。
- **打字报告**可将统计生成小票图片，支持打印与撕票动画、音效、纸张配色、头图、印章和落款。在独立样式页面实时预览，完成后保存或分享图片。
- **备份与恢复**支持导出和恢复本地备份，也可以管理 WebDAV 备份并设置自动备份频率。
- **密码填充**可与同签名的空叶文档联动，解锁后填充账号和密码。此功能默认关闭，密码字段的输入不进入词库学习和剪贴板历史。[联动说明](docs/密码填充-接口与任务.md)

## 安装

支持 **Android 8.0 及以上的 64 位设备**。大多数安卓手机可选 ARM64，x86_64 设备可选对应安装包，通用包同时包含这两种架构。

1. 打开[下载页](https://github.com/Mayleaves/KongyeKeyboard/releases/latest)，在 `Assets` 中选择对应架构的 APK 并安装。
2. 打开空叶键盘，在「使用准备」中启用输入法，再把它选为当前输入法。
3. 按需开启功能权限或在「离线资源」下载模型。

## 构建

使用 Android Studio 自带的 JDK 和 Android SDK 37。在本机的 `local.properties` 中配置 `sdk.dir`。首次构建需准备原生依赖和离线数据。Rime 数据生成使用 Windows x64 工具，其他平台可使用预先生成的依赖和数据。[原生依赖构建说明](docs/Rime-原生构建.md) · [Fcitx5 引擎说明](docs/Fcitx5-成熟引擎接入.md) · [离线资源说明](docs/轻量资源与设置预览.md)

<details>
<summary>获取构建依赖</summary>

```sh
python tools/fetch_offline_voice.py --engine-only
python tools/fetch_rime.py
python tools/fetch_fcitx.py
python tools/fetch_markdown.py
python tools/build_expressions.py
python tools/fetch_handwriting.py
python tools/fetch_neural_handwriting.py
python tools/fetch_streaming_voice.py
```

离线润色运行库需要 Android NDK r29、CMake 和 Ninja。

```sh
python tools/fetch_polish.py --ndk /path/to/android-ndk-r29 --cmake /path/to/cmake --ninja /path/to/ninja
```

</details>

Windows 上运行 `./gradlew.bat :app:assembleRelease :app:lintRelease`。macOS 和 Linux 上使用 `./gradlew`。APK 输出到 `app/build/outputs/apk/release/`。覆盖安装时，安装包签名须与设备上已有版本一致。

## 开源许可

项目以 [GPL-3.0-only](LICENSE) 开源。第三方代码、模型和数据沿用各自许可证，详见 [第三方说明](THIRD_PARTY_NOTICES.md)。
