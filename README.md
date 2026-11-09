# 空叶键盘

Android 中文输入法，支持全拼、简拼、纠错、联想、自造词，以及 26 键、14 键和九键布局。

当前版本：**1.2.1**。Android 8.0 及以上，安装包面向 ARM64 设备。

## 功能

- 多套皮肤与日夜模式，支持键盘高度、振动强度、工具栏排序和悬浮键盘。
- 上下滑动输入符号，长按空格上下左右移动光标，撤销与重做。
- Emoji、颜文字、收藏；文字与图片剪贴板、自定义分类和分词。
- 离线中文手写识别，候选显示拼音，并补充常见形近字与联想词。
- ML Kit 离线翻译，首次使用需联网下载模型，之后可离线使用已下载模型。
- 语音输入使用系统识别服务，需在设置页手动授予麦克风权限。

功能仍在持续完善，部分皮肤和图片剪贴板的跨应用兼容性尚待完整验证。

## 安装

从仓库的 Releases 下载 APK。安装后，在应用设置中启用空叶键盘并将其选为当前输入法。

翻译首次下载需要能访问模型服务器的网络。如果一直没有进度，可点“重试”；界面会显示下载进度或系统暂停原因。

## 构建

使用 Android Studio 的 JDK 和 Android SDK，安装 SDK 37，并在本机的 `local.properties` 中配置 `sdk.dir`。本文件不提交到仓库。

Windows：

```powershell
./gradlew.bat assembleRelease lintRelease
```

macOS / Linux：

```sh
./gradlew assembleRelease lintRelease
```

输出：`app/build/outputs/apk/release/app-release.apk`。

项目当前为本地分发沿用 debug 签名。首次换一台电脑构建会生成该电脑的 debug 密钥，因此不保证能直接覆盖其他电脑签名的安装包；正式分发应配置独立签名。

## 词库和引擎检查

生成后的词库已包含在源码中，日常构建不需要原始词典数据库。

重新生成时需要空叶阅读的 `dict.db`，并安装 Python 依赖：

```sh
python -m pip install -r tools/requirements.txt
python -I tools/build_lexicon.py /path/to/dict.db app/src/main/assets/lexicon.txt --chars 9000 --words 60000
python -I tools/build_lexicon.py /path/to/dict.db design/lexicon.js
python -I tools/check_lexicon.py
```

生成器使用普通话音节白名单过滤异常读音，ü 写为 v。展示页位于 `design/`。

拼音引擎与手写候选检查：

```powershell
./tools/engine_check/run.ps1
```

脚本使用 Gradle 缓存中的 Kotlin 编译器；`JAVA_HOME` 需指向 JDK，脚本中的 Android SDK 路径需按本机环境调整。Git Bash 可使用 `tools/engine_check/run.sh`。

## 1.2.1 更新

- 过滤 uu、un、wbn、ie 等异常拼音，更新应用和展示页词库。
- 翻译增加 25 秒超时、下载进度、暂停原因和重试恢复，防止旧请求覆盖新结果。
- 手写补形近字和完整联想词，修复展开候选与过时识别结果干扰。
- 成对符号输入后光标自动移到中间。

已实机验证中英双向翻译、手写候选、九键与 14 键、悬浮拖动、空格光标移动、撤销重做及部分皮肤日夜切换。
