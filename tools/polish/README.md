# Offline voice polish

Pinned runtime: llama.cpp 1.0.0 release commit in `sources.json` (MIT).
Pinned model: official Qwen3-0.6B-GGUF Q8_0 (Apache-2.0).

The offline model is downloaded on demand from the pinned source in `resource-packs.json` and SHA-256 verified before installation to app-private no-backup storage. The lightweight APK includes the runtime and licenses, not the model weights. The decoder uses a bounded 2048-token CPU context, cancellation/timeout, and frees model/context weights after each request. Existing ASR weights are released before LLM inference. Cloud failures never fall back to uploading text to another endpoint.

To reproduce on Windows/Linux with Android NDK r29, CMake and Ninja:

```sh
python tools/fetch_polish.py --ndk /path/to/android-ndk-r29 --cmake /path/to/cmake --ninja /path/to/ninja
```

The script validates the model/source archive, builds arm64-v8a and x86_64 with the checked-in JNI binding, strips debug symbols and installs libraries/assets at their Gradle input paths. Model and compiled binary files are intentionally excluded from Git. The lightweight release APK contains the compiled runtime; Gradle excludes the downloaded model input from the packaged assets. Release source artifacts retain the complete pinned upstream source archive and model/runtime licenses.

The online client uses a user-configured HTTPS Chat Completions endpoint. API keys are encrypted with Android Keystore and stored outside backup. Online integration tests use an isolated loopback fixture, never a real user key or external provider.
