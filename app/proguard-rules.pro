# ML Kit 靠反射 newInstance() 创建各个 ComponentRegistrar；R8 full mode 会删掉没人直接调用的无参构造，
# 结果文字识别组件注册不上，TextRecognition.getClient() 里拿到 null 直接崩溃（1.2.0 手写崩溃就是这个）
-keep class * implements com.google.firebase.components.ComponentRegistrar { public <init>(); }
# 识别/翻译的原生库会通过 JNI 按名字回调 Java 类，模型配置也是按字段名反射读的 protobuf，整包保留
-keep class com.google.mlkit.** { *; }
-keep class com.google.android.gms.internal.mlkit_** { *; }
-keep class com.google.android.odml.** { *; }
-keep class com.k2fsa.sherpa.onnx.** { *; }
# ONNX JNI constructs TensorInfo and value wrappers by exact Java signatures.
-keep class ai.onnxruntime.** { *; }
# 独立设备验证程序和 ML Kit 共用任务 API，保留异步任务的公开入口。
-keep class com.google.android.gms.tasks.** { *; }
# The separately built release-device test loads the real engine and reads candidates.
-keep class com.kongye.keyboard.Lexicon { *; }
-keep class com.kongye.keyboard.Cand { *; }
-keep class com.sun.jna.** { *; }
-dontwarn java.awt.**
-keep class com.kongye.keyboard.RimeBridge** { *; }
-keep class com.kongye.keyboard.PinyinEngine** { *; }
-keep class com.kongye.keyboard.OpenHandwriting** { *; }
-keep class com.kongye.keyboard.HandwritingPinyin { *; }

# 1.3.0：手写的数字墨水库带进了 WorkManager 和 Room。数据库实现类（WorkDatabase_Impl）、
# androidx.startup 的 Initializer、Worker 都是反射创建的，R8 full mode 删掉构造后进程一启动就崩
-keep class * extends androidx.room.RoomDatabase { <init>(); }
-keep class * implements androidx.startup.Initializer { <init>(); }
-keep class * extends androidx.work.ListenableWorker { <init>(android.content.Context, androidx.work.WorkerParameters); }
-keep class * extends androidx.work.InputMerger { <init>(); }
-keep class androidx.work.impl.** { *; }

# Composition editor is exercised by the separate on-device regression APK.
-keep class com.kongye.keyboard.PinyinTab { *; }
-keep class com.kongye.keyboard.PinyinTab$Preview { *; }
-keep class com.kongye.keyboard.SinkStrip { *; }
-keep class com.kongye.keyboard.ClipboardPanel { *; }
-keep class com.kongye.keyboard.RemoteInputHistory { *; }
-keep class com.kongye.keyboard.AppUpdate { *; }
-keep class com.kongye.keyboard.UpdateChecker** { *; }
-keep class com.kongye.keyboard.NativeMotion { *; }
-keep class com.kongye.keyboard.EditPanelView { *; }

# Device regressions exercise the v2 password protocol state machine.
-keep class com.kongye.keyboard.VaultFill** { *; }
-keep class com.kongye.keyboard.VaultFields** { *; }
-keep class com.kongye.keyboard.VaultGateway { *; }

# The release-device Kotlin tests are compiled separately from the app: keep the
# standard-library entry points they share, so R8 cannot specialize signatures.
-keep class kotlin.collections.** { *; }
-keep class kotlin.text.** { *; }
-keep class kotlin.jvm.** { *; }
-keep class kotlin.Unit { *; }
-keep class kotlin.TuplesKt* { *; }
-keep class kotlin.Pair { *; }
-keep class kotlin.ranges.** { *; }
-keep class kotlin.io.** { *; }

# Official Fcitx JNI constructors and callbacks retain their ABI.
-keep class org.fcitx.fcitx5.android.** { *; }
-keep class com.kongye.keyboard.FcitxEngine** { *; }

# Release-device coverage of candidate spans, gesture persistence and one-time SMS clipboard.
-keep class com.kongye.keyboard.Glide** { *; }
-keep class com.kongye.keyboard.SpellingAssist** { *; }
-keep class com.kongye.keyboard.KeyGestures** { *; }
-keep class com.kongye.keyboard.SmsCode** { *; }
-keep class com.kongye.keyboard.ExpandedCandidates { *; }
-keep class com.kongye.keyboard.CandFlowView { *; }
-keep class com.kongye.keyboard.KeyboardView** { *; }
-keep class com.kongye.keyboard.Key { *; }
-keep class com.kongye.keyboard.Layouts { *; }
-keep class com.kongye.keyboard.Row { *; }
-keep class com.kongye.keyboard.Kind { *; }

-keep class com.kongye.keyboard.Skin** { *; }

# Release-device UI coverage for the new Markdown and editable keycap previews.
-keep class com.kongye.keyboard.MarkdownTextView { *; }
-keep class com.kongye.keyboard.FlickPreviewView { *; }
-keep class com.kongye.keyboard.CandidateBar** { *; }
-keep class com.kongye.keyboard.VaultPanel { *; }
-keep class com.kongye.keyboard.NativeMotion { *; }

-keep class com.kongye.keyboard.ClipStore { *; }

-keepclassmembers class com.kongye.keyboard.KongyeIME { java.lang.String liveGlideRaw; }

-keep class com.kongye.keyboard.CandItem { *; }
-keep class com.kongye.keyboard.Prefs { *; }

-keepclassmembers class com.kongye.keyboard.KongyeIME { private void loadAssociations(); }

-keep class com.kongye.keyboard.CandidateHost { *; }

-keepclassmembers class com.kongye.keyboard.PinyinTab { boolean caretVisible; }

# Release-device regression exercises the new settings through the production IME.
-keep class com.kongye.keyboard.V3Prefs { *; }
-keep class com.kongye.keyboard.EnglishInput { *; }
-keep class com.kongye.keyboard.VoiceText { *; }
-keep class com.kongye.keyboard.VoiceMedia { *; }
-keep class com.kongye.keyboard.VoiceBoard { *; }

-keep class com.kongye.keyboard.VoiceSupport { *; }

-keep class com.kongye.keyboard.NativePolish { *; }
-keep class com.kongye.keyboard.VoicePolish** { *; }
-keep class com.kongye.keyboard.PolishSecret { *; }

-keep class com.kongye.keyboard.TypingStats** { *; }
-keep class com.kongye.keyboard.StatsSnapshot { *; }
-keep class com.kongye.keyboard.StatsMethod { *; }
-keep class com.kongye.keyboard.StatsReport { *; }

-keepclassmembers class com.kongye.keyboard.KongyeIME {
    private java.lang.StringBuilder voiceText;
    private void openVoice();
    private void updateVoice();
}

# The separately packaged device checks share Result callbacks with the app.
-keep class kotlin.Result** { *; }

-keep class com.kongye.keyboard.ResourcePacks** { *; }
-keep class com.kongye.keyboard.ResourceTransfer** { *; }

-keep class com.kongye.keyboard.SettingExampleView { *; }

-keepclassmembers class com.kongye.keyboard.KongyeIME { private boolean statsAllowed(); }

# Production updater dialog state coverage (no installer interaction).
-keepclassmembers class com.kongye.keyboard.SettingsActivity {
    private boolean checkingUpdate;
    private *** release;
    private *** downloadedApk;
    private *** downloading;
    private *** updateState;
    private *** downloadState;
}
-keep class com.kongye.keyboard.SettingAvailability** { *; }
-keep class com.kongye.keyboard.KeyboardBackup** { *; }
-keep class com.kongye.keyboard.StatsYearView { *; }

# Direct Unicode pairing checks from the release instrumentation.
-keep class com.kongye.keyboard.PairedSymbols { *; }

# Release-device validation of shared editing, live sizing and WebDAV transfers.
-keep class com.kongye.keyboard.KeyboardAdjustmentPreview { *; }
-keep class com.kongye.keyboard.ArrangeView { *; }
-keep class com.kongye.keyboard.WebDavBackup** { *; }

-keep class com.kongye.keyboard.NumericKeyboardPreview { *; }
-keep class com.kongye.keyboard.SymbolOrderView { *; }

-keep class com.kongye.keyboard.SmsSetupGuideView { *; }

# The instrumented clipboard test restores the original clip without recapturing it.
-keepclassmembers class com.kongye.keyboard.KongyeIME { private long ignoreClipUntil; }

# Validate the same first-install path from the release companion tests.
-keep class com.kongye.keyboard.InstallDefaults { *; }

-keep class com.kongye.keyboard.FloatingKeyboard { *; }
-keep class com.kongye.keyboard.Adjust { *; }
-keep class com.kongye.keyboard.TileGridView { *; }

-keep class com.kongye.keyboard.AdjustOverlay { *; }
-keepclassmembers class com.kongye.keyboard.KongyeIME {
    private *** adj;
    private *** col;
    private void applyFloat();
    private void applySkin(boolean,boolean);
}
