# ML Kit 靠反射 newInstance() 创建各个 ComponentRegistrar；R8 full mode 会删掉没人直接调用的无参构造，
# 结果文字识别组件注册不上，TextRecognition.getClient() 里拿到 null 直接崩溃（1.2.0 手写崩溃就是这个）
-keep class * implements com.google.firebase.components.ComponentRegistrar { public <init>(); }
# 识别/翻译的原生库会通过 JNI 按名字回调 Java 类，模型配置也是按字段名反射读的 protobuf，整包保留
-keep class com.google.mlkit.** { *; }
-keep class com.google.android.gms.internal.mlkit_** { *; }
-keep class com.google.android.odml.** { *; }
