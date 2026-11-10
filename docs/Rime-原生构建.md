# Rime Android 原生库来源与构建

应用只使用独立 Rime 核心，不引入其他输入法的键盘界面或用户数据。

## 已分发库的来源

- 平台：Android arm64-v8a，API 24+，16KB 页面兼容链接参数。
- 核心：librime 1.17.0，提交 `33e78140250125871856cdc5b42ddc6a5fcd3cd4`。
- 二进制载体：[KeyTao v1.2.4 官方 arm64 APK](https://github.com/xkinput/keytao-app/releases/tag/v1.2.4)。
- 仅取出 `librime.so`、`libc++_shared.so`；APK 内的 `assets/librime-release.txt` 记录了上述源提交。
- 构建流程：[上游固定版本脚本](https://github.com/xkinput/keytao-app/blob/v1.2.4/scripts/build-android-librime.sh)。
- Lua、Octagram、Predict 插件和第三方依赖版本按该版本的 `scripts/librime-versions.env` 与 Rime 子模块固定。

`tools/fetch_rime.py` 校验 APK SHA256 后生成本项目的原生 AAR。
`tools/package_native_sources.py` 打包对应源文件、原始许可证、补丁、构建脚本和 Boost 头文件。
发行版的 `KongyeKeyboard-2.1.0-native-sources.zip` 为可独立下载的对应源码包。

## 从源码重建 Android 库

在 Linux/macOS 安装 CMake、Git、curl、Perl、tar、Python 3、Android NDK **27.0.12077973**。
原始构建脚本会下载其固定源码版本，因此首次执行需要联网。

```sh
git clone --branch v1.2.4 --depth 1 https://github.com/xkinput/keytao-app.git
cd keytao-app
export ANDROID_NDK_HOME=/absolute/path/to/android-ndk-r27
bash scripts/build-android-librime.sh --abi arm64-v8a
```

默认产物位于 `vendor/librime/android/arm64-v8a/lib/`。
对应源码包保留相同版本的完整 Rime 源码、实际链接的依赖和插件；上游 Android 补丁、CMake 参数均在 `keytao/scripts/`。
本项目没有修改这些原生源文件。若需离线重建，将包中的源码按脚本中的缓存目录布局放置，并使用同一补丁和 CMake 参数；脚本中的 Git 下载步骤可跳过。
本机实际采用已校验的上游预编译 Android 库并完成真机验证，没有重新执行其 NDK 全量编译；不声称能得到逐字节一致的二进制。

## 字典和模型

`tools/fetch_rime.py` 使用官方 Windows x64 Rime 1.17.0 部署工具预编译可移植的字典、拼写图和方案。
运行期只解压已编译数据，不在手机上编译 160 多万条词典。

万象 18.1.11 词典转换步骤：去除声调用于拼音索引，保留原词频、地名和人名；去掉独立鼻音感叹词代码，避免 `m` 占据罕见单字首选；保留完整整句模型。
声调和多读音另由手写注音资源提供，转换步骤不会改变手写展示。

来源、SHA256 见脚本和生成的 `build/rime/manifest.json`。词典与语法模型为 amzxyz 的万象项目数据，按 CC BY 4.0 署名使用。

上游 LTS 下载标签会更新。若以后相同 URL 的文件不再符合固定摘要，不应取消校验：可从本版本发行 APK 恢复已验证的数据和独立核心，亦可在 macOS/Linux 使用此路径跳过 Windows 部署工具：

```sh
python tools/fetch_rime.py --from-apk /path/to/KongyeKeyboard-2.1.0-arm64.apk
```

脚本会核对词库数据和两份原生库的固定摘要。
