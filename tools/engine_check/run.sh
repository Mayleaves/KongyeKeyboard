#!/bin/sh
# 用 Gradle 缓存里的 Kotlin 编译器，在 JVM 上编译并运行 Lexicon.kt + Check.kt。
# 需要 JAVA_HOME 指向 Android Studio 自带的 JBR。
set -e
cd "$(dirname "$0")/../.."
M2=~/.gradle/caches/modules-2/files-2.1
# Windows 上的 java 不认 /c/... 这种路径，用 cygpath 转成 C:/...
jar() { p="$(find "$M2/$1" -name "$2" 2>/dev/null | grep -v sources | sort -r | head -1)"; if [ -n "$p" ]; then cygpath -m "$p"; fi; }
KV=2.4.21
CP="$(jar org.jetbrains.kotlin/kotlin-compiler-embeddable "kotlin-compiler-embeddable-$KV.jar")"
for a in kotlin-stdlib kotlin-script-runtime kotlin-reflect kotlin-daemon-embeddable; do
  CP="$CP;$(jar org.jetbrains.kotlin/$a "$a-*.jar")"
done
CP="$CP;$(jar org.jetbrains.kotlinx/kotlinx-coroutines-core-jvm 'kotlinx-coroutines-core-jvm-*.jar')"
CP="$CP;$(jar org.jetbrains/annotations 'annotations-*.jar')"
CP="$CP;$(jar org.jetbrains.intellij.deps/trove4j 'trove4j-*.jar' 2>/dev/null || true)"
STD="$(jar org.jetbrains.kotlin/kotlin-stdlib "kotlin-stdlib-$KV.jar")"
ANDROID="D:/Program/Android/Sdk/platforms/android-37.0/android.jar"
OUT="${TMPDIR:-/tmp}/ky_engine_check"
mkdir -p "$OUT"
"$JAVA_HOME/bin/java" -cp "$CP" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler \
  -no-stdlib -classpath "$STD;$ANDROID" -d "$OUT" -jvm-target 17 \
  app/src/main/java/com/kongye/keyboard/Lexicon.kt app/src/main/java/com/kongye/keyboard/PinyinCorrection.kt app/src/main/java/com/kongye/keyboard/HandwritingCandidates.kt app/src/main/java/com/kongye/keyboard/HandwritingPinyin.kt tools/engine_check/Check.kt
"$JAVA_HOME/bin/java" -cp "$OUT;$STD" CheckKt app/src/main/assets/lexicon.txt
